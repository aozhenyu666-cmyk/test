// End-to-end check of the plugin in Chromium against the mock host + mock model + mock MCP.
//   node dev/e2e.mjs
// Needs Playwright (npm i -D playwright, or a global install).
import { execSync } from 'node:child_process';
import { join } from 'node:path';
import { startServer } from './mock-server.mjs';

async function loadPlaywright() {
  try {
    return await import('playwright');
  } catch {
    const globalRoot = execSync('npm root -g').toString().trim();
    return import(join(globalRoot, 'playwright', 'index.mjs'));
  }
}

const { chromium } = await loadPlaywright();
const server = await startServer();
const port = server.address().port;
// "localhost" vs "127.0.0.1" makes the API cross-origin to the harness page, so
// the plugin's plain fetch hits CORS exactly as it would against a real LLM API.
const API_BASE = `http://localhost:${port}/v1`;
const MCP_URL = `http://localhost:${port}/mcp`;

let failures = 0;
function check(cond, label) {
  console.log((cond ? 'PASS ' : 'FAIL ') + label);
  if (!cond) failures++;
}

async function run(native) {
  console.log(`\n== ${native ? 'Android-like host (native HTTP)' : 'desktop/web host (fetch -> proxy fallback)'} ==`);
  const browser = await chromium.launch(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {});
  const page = await browser.newPage();
  page.on('pageerror', (e) => { console.log('pageerror:', e.message); failures++; });
  page.on('dialog', (d) => d.accept());
  await page.exposeFunction('__nodeFetch', async (url, init) => {
    const r = await fetch(url, { method: init.method, headers: init.headers, body: init.body });
    return { status: r.status, headers: Object.fromEntries(r.headers.entries()), text: await r.text() };
  });
  await page.goto(`http://127.0.0.1:${port}/harness.html${native ? '?native=1' : ''}`);
  const f = page.frameLocator('#f');

  await f.locator('#fPreset').selectOption('custom');
  await f.locator('#fBase').fill(API_BASE);
  await f.locator('#fKey').fill('sk-test');
  await f.locator('#fModel').fill('mock-large');
  await f.locator('#fMcpUrl').fill(MCP_URL);
  await f.locator('#fMcpToken').fill('mcp-secret');
  await f.locator('#btnSave').click();
  await f.locator('.msg', { hasText: '大模型' }).waitFor({ timeout: 15000 });
  const testNote = await f.locator('.msg', { hasText: '大模型' }).innerText();
  check(/MCP：mock-mcp，1 个工具/.test(testNote), 'connection test reports LLM + MCP OK: ' + testNote.replace(/\n/g, ' | '));

  async function say(text) {
    const before = await f.locator('.msg.ai, .msg.err').count();
    await f.locator('#input').fill(text);
    await f.locator('#btnSend').click();
    await f.locator('.msg.ai, .msg.err').nth(before).waitFor({ timeout: 20000 });
    await page.waitForFunction(() => document.querySelector('#f').contentDocument.querySelector('#btnSend').textContent === '发送');
    return f.locator('.msg.ai, .msg.err').last().innerText();
  }

  let reply = await say('明天下午3点写周报，预估30分钟');
  let store = await page.evaluate(() => window.store);
  const created = store.tasks.find((t) => t.title === '写周报');
  const tmr = new Date(); tmr.setDate(tmr.getDate() + 1); tmr.setHours(15, 0, 0, 0);
  check(created && created.dueWithTime === tmr.getTime() && !created.dueDay, 'create_task scheduled tomorrow 15:00');
  check(created && created.timeEstimate === 30 * 60000, 'estimate 30 min');
  check(created && store.tags.some((t) => t.title === '工作' && created.tagIds.includes(t.id)), 'tag auto-created and attached');
  check(/已处理/.test(reply), 'model produced final reply after tool: ' + reply.slice(0, 60));

  reply = await say('把买牛奶标记完成');
  store = await page.evaluate(() => window.store);
  check(store.tasks.find((t) => t.id === 't1').isDone === true, 'list_tasks -> complete_task chain marks t1 done');

  reply = await say('今天怎么样');
  check(/todayPlanned/.test(reply) && /trackedTodayMin/.test(reply), 'today_summary returned data: ' + reply.slice(0, 90));

  reply = await say('调用远程工具');
  check(/remote says: ping/.test(reply), 'remote MCP tool over SSE with session id: ' + reply.slice(0, 60));

  const log = await page.evaluate(() => window.__log);
  if (native) check(log.native > 0 && log.proxy === 0, `native channel used (native=${log.native}, proxy=${log.proxy})`);
  else check(log.proxy > 0, `fell back to SP proxy after CORS failure (proxy=${log.proxy})`);

  // history survives a reload of the iframe (localStorage)
  await page.reload();
  await f.locator('.msg.me').first().waitFor({ timeout: 10000 });
  check((await f.locator('.msg.me').count()) === 4, 'conversation restored after reload');
  await browser.close();
}

try {
  await run(true);
  await run(false);
} finally {
  server.close();
}
console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
