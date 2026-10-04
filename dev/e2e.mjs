// End-to-end check of the plugin (index.html + plugin.js) in Chromium against
// the mock SP host + mock model + mock MCP server.
//   node dev/e2e.mjs            (needs Playwright: npm i -D playwright, or a global install)
//   SHOTS=1 node dev/e2e.mjs    also writes phone-size screenshots to dev/shots/
import { execSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer } from './mock-server.mjs';

async function loadPlaywright() {
  try { return await import('playwright'); } catch {
    const globalRoot = execSync('npm root -g').toString().trim();
    return import(join(globalRoot, 'playwright', 'index.mjs'));
  }
}

const here = dirname(fileURLToPath(import.meta.url));
const SHOTS = process.env.SHOTS ? join(here, 'shots') : null;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

// Test the minified build that actually ships (run `node scripts/pack.mjs` first), unless PLUGIN_DIR says otherwise.
if (!process.env.PLUGIN_DIR) process.env.PLUGIN_DIR = join(here, '..', 'dist', 'build');
console.log('testing plugin files from ' + process.env.PLUGIN_DIR);

const { chromium } = await loadPlaywright();
const server = await startServer();
const port = server.address().port;
// "localhost" vs "127.0.0.1" makes the API cross-origin to the harness page.
const ORIGIN = `http://localhost:${port}`;

let failures = 0;
function check(cond, label) { console.log((cond ? 'PASS ' : 'FAIL ') + label); if (!cond) failures++; }

async function run({ native, theme }) {
  const name = native ? 'android' : 'desktop';
  console.log(`\n== ${native ? 'Android-like host: native HTTP, no streaming' : 'desktop host: streaming via CORS'} ==`);
  const browser = await chromium.launch(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {});
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 });
  page.on('pageerror', (e) => { console.log('pageerror:', e.message); failures++; });
  page.on('dialog', (d) => d.accept());
  await page.exposeFunction('__nodeFetch', async (url, init) => {
    const r = await fetch(url, { method: init.method, headers: init.headers, body: init.body });
    return { status: r.status, headers: Object.fromEntries(r.headers.entries()), text: await r.text() };
  });
  await page.goto(`http://127.0.0.1:${port}/harness.html?${native ? 'native=1&' : ''}theme=${theme}`);
  const f = page.frameLocator('#f');
  const shot = async (n) => { if (SHOTS) await page.screenshot({ path: join(SHOTS, `${name}-${n}.png`) }); };

  // first run without API key -> lands on settings
  await f.locator('[data-preset="custom"]').waitFor({ timeout: 10000 });
  await shot('0-settings');
  await f.locator('[data-preset="custom"]').click();
  await f.locator('#fBase').fill(`${ORIGIN}${native ? '' : '/cors'}/v1`);
  await f.locator('#fKey').fill('sk-test');
  await f.locator('#fModel').fill('mock-large');
  await f.locator('[data-act="save-llm"]').click();
  await f.locator('#llmStatus .dot.ok').waitFor({ timeout: 15000 });
  const llmStatus = await f.locator('#llmStatus').innerText();
  check(native ? /原生 HTTP/.test(llmStatus) : /流式/.test(llmStatus), 'LLM test uses expected channel: ' + llmStatus.replace(/\n/g, ' '));
  await f.locator('#fMcpUrl').fill(`${ORIGIN}/mcp`);
  await f.locator('#fMcpToken').fill('mcp-secret');
  await f.locator('[data-act="save-mcp"]').click();
  await f.locator('#mcpStatus .dot.ok').waitFor({ timeout: 15000 });
  check(/mock-mcp · 1 个工具/.test(await f.locator('#mcpStatus').innerText()), 'MCP connects (SSE + session id)');

  await f.locator('#tabs [data-tab="chat"]').click();
  await f.locator('.hero .starter button').first().waitFor();
  await page.waitForTimeout(300);
  await shot('1-home');

  async function waitIdle() {
    await page.waitForFunction(() => { const d = document.querySelector('#f').contentDocument; return d && !d.querySelector('#btnSend.stop'); }, null, { timeout: 20000 });
  }
  async function say(text) {
    await f.locator('#input').fill(text);
    await f.locator('#btnSend').click();
    await page.waitForTimeout(50);
    await waitIdle();
    return f.locator('.row.ai').last().innerText();
  }
  const store = () => page.evaluate(() => window.store);
  const data = async (key) => { const s = await store(); return s.data[key] ? JSON.parse(s.data[key]) : null; };
  const today = await page.evaluate(() => { const d = new Date(); return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); });

  // plain reply with markdown (+ streaming on desktop)
  let reply = await say('你好');
  check(/参谋/.test(reply) && (await f.locator('.row.ai').last().locator('table').count()) === 1 && (await f.locator('.row.ai').last().locator('li').count()) === 2, 'markdown rendered (bold, list, table)');

  // planning flow: get_today -> ask_user chips -> tap -> save_daily_plan
  await f.locator('#input').fill('帮我定今天的计划');
  await f.locator('#btnSend').click();
  await f.locator('.choices button', { hasText: '就按这个来' }).waitFor({ timeout: 15000 });
  await waitIdle();
  check((await f.locator('.choices button').count()) === 3, 'ask_user renders 3 tappable options');
  await shot('2-plan-question');
  await f.locator('.choices button', { hasText: '就按这个来' }).click();
  await page.waitForTimeout(50);
  await waitIdle();
  const jr = await data('journal-' + today.slice(0, 7));
  const plan = jr && jr.days[today] && jr.days[today].plan;
  check(plan && plan.items.length === 2 && plan.theme.includes('备考'), 'daily plan saved to synced journal');
  let s = await store();
  const xingce = s.tasks.find((t) => t.title === '行测刷题 40 道');
  const guokaoTag = s.tags.find((t) => t.title === '国考');
  check(xingce && guokaoTag && xingce.tagIds.includes(guokaoTag.id) && xingce.dueWithTime, 'plan items became SP tasks with goal tag + start time');
  await shot('3-plan-done');

  // create task with reminder + goal
  await say('明天下午3点写周报，预估30分钟');
  s = await store();
  const wk = s.tasks.find((t) => t.title === '写周报');
  const tmr = new Date(); tmr.setDate(tmr.getDate() + 1); tmr.setHours(15, 0, 0, 0);
  const rem = new Date(tmr); rem.setMinutes(50); rem.setHours(14);
  check(wk && wk.dueWithTime === tmr.getTime() && wk.remindAt === rem.getTime() && wk.timeEstimate === 30 * 60000, 'create_task: time, estimate and native reminder');
  check(wk && s.tags.some((t) => t.title === '求职' && wk.tagIds.includes(t.id)), 'goal 找工作 -> #求职 tag auto-created');

  // delete with in-chat confirmation card
  await f.locator('#input').fill('删除买牛奶');
  await f.locator('#btnSend').click();
  await f.locator('.confirm button', { hasText: '删除' }).waitFor({ timeout: 15000 });
  await shot('4-confirm');
  await f.locator('.confirm button', { hasText: '删除' }).click();
  await waitIdle();
  check(!(await store()).tasks.some((t) => t.id === 't1'), 'delete_task waits for in-chat confirm, then deletes');

  // remote MCP tool
  reply = await say('调用远程工具');
  check(/remote says: ping/.test(reply), 'remote MCP tool call works');

  // memory
  await say('记住我晚上效率低');
  check(((await data('goals')).memory || []).some((m) => m.text.includes('10 点')), 'remember() stores long-term memory');

  // case file: commitments are stored with their kind
  await say('从明天起我每天 9 点前开始刷行测');
  check(((await data('goals')).memory || []).some((m) => m.kind === 'commit' && m.text.includes('9 点前')), 'remember(kind=commit) stores a commitment in the case file');

  // long rant -> diagnosis pass (facts + case file) -> one sharp question instead of a point-by-point answer
  await f.locator('#input').fill('最近感觉一直在瞎忙，每天都挺累的但好像什么都没干成，备考也没什么进展。是不是我的方法有问题？要不要换一个刷题软件，或者干脆再搭一套更好的管理系统，把每天的安排都自动化？');
  await f.locator('#btnSend').click();
  await f.locator('.choices button', { hasText: '刷手机' }).waitFor({ timeout: 15000 });
  await waitIdle();
  const diagTxt = await f.locator('.row.ai').last().locator('.diag').innerText();
  check(/先追问/.test(diagTxt) && /真正的问题：离国考笔试不到两个月/.test(diagTxt), 'diagnosis card shows the move and the real question (fact sheet reached the diagnosis)');
  await f.locator('.row.ai').last().locator('.diag summary').click();
  check(/对不上[\s\S]*每天 9 点前开始刷行测/.test(await f.locator('.row.ai').last().locator('.diag').innerText()), 'diagnosis cites the stored commitment as evidence');
  check(/先别换工具/.test(await f.locator('.row.ai').last().locator('.md').innerText()) && /昨天你在什么时候/.test(await f.locator('.row.ai').last().innerText()), 'reply follows the diagnosis: opening line + the one question');
  await shot('3b-diagnosis');

  // short commands skip the diagnosis
  await say('只回复 OK');
  check((await f.locator('.row.ai').last().locator('.diag').count()) === 0, 'short messages are answered without a diagnosis');

  // review -> report written into SP
  await say('开始今天的晚间复盘');
  s = await store();
  const rep = s.tasks.find((t) => t.title.startsWith('🧭 参谋：日报'));
  check(rep && rep.isDone && /## 复盘/.test(rep.notes) && /## 时间投入/.test(rep.notes), 'review saved and daily report written into SP task notes');

  // stop button aborts immediately
  await page.evaluate(() => { const w = document.querySelector('#f').contentWindow; const of = w.fetch; w.__slow = true; w.fetch = (...a) => new Promise((r) => setTimeout(() => r(of(...a)), 60000)); });
  if (native) await page.evaluate(() => { const of = window.fetch; window.fetch = (...a) => new Promise((r) => setTimeout(() => r(of(...a)), 60000)); });
  await f.locator('#input').fill('你好');
  await f.locator('#btnSend').click();
  await page.waitForTimeout(400);
  const t0 = Date.now();
  await f.locator('#btnSend').click();
  await waitIdle();
  check(Date.now() - t0 < 2000, `stop button frees the UI immediately (${Date.now() - t0} ms)`);
  await page.evaluate(() => { const w = document.querySelector('#f').contentWindow; delete w.__slow; });

  // background script: away check (Android only) + ritual reminder task
  if (native) {
    await page.waitForFunction(() => !!window.__spAiAssistantBg, null, { timeout: 5000 });
    await page.evaluate(() => { const t = window.store.tasks.find((x) => x.title === '行测刷题 40 道'); t.timeSpentOnDay = {}; t.timeSpent = 0; });
    await page.evaluate(() => window.__spAiAssistantBg.takeSnapshot());
    await page.evaluate((day) => { const t = window.store.tasks.find((x) => x.title === '行测刷题 40 道'); t.timeSpentOnDay = { [day]: 20 * 60000 }; t.timeSpent = 20 * 60000; }, today);
    await page.evaluate(() => { window.__dialogAnswer = '☕ 在休息 / 刷手机'; return window.__spAiAssistantBg.checkAway(Date.now() - 20 * 60000); });
    s = await store();
    const xt = s.tasks.find((x) => x.title === '行测刷题 40 道');
    const dlg = (await page.evaluate(() => window.__log.dialogs)).find((d) => d.title === '刚才在忙什么？');
    check(dlg && /20 分钟/.test(dlg.html), 'away dialog asks about the 20 minutes away');
    check(xt.timeSpentOnDay[today] === 0, 'answering "break" removes the away time from the task');
    const lg = await data('log-' + today.slice(0, 7));
    check(lg && lg.days[today].away[0].verdict === 'break', 'away event logged for the timeline/report');

    // timeline segment via currentTaskChange hook
    await page.evaluate((id) => window.fireHook('currentTaskChange', { current: { id, title: '行测刷题 40 道' } }), xt.id);
    await page.waitForTimeout(100);
    await page.evaluate(() => { const o = JSON.parse(localStorage.getItem('sp-ai-assistant:open')); o.s -= 30 * 60000; localStorage.setItem('sp-ai-assistant:open', JSON.stringify(o)); });
    await page.evaluate(() => window.fireHook('currentTaskChange', { current: null, previous: {} }));
    await page.waitForTimeout(300);
    const lg2 = await data('log-' + today.slice(0, 7));
    check(lg2.days[today].segs.length === 1 && lg2.days[today].segs[0].title === '行测刷题 40 道', 'timeline segment recorded from currentTaskChange');

    // ritual task with native reminder
    await page.evaluate(() => window.__spAiAssistantBg.rhythm());
    s = await store();
    const ritual = s.tasks.find((t) => !t.isDone && t.title.startsWith('🧭 参谋'));
    check(ritual && ritual.remindAt && ritual.remindAt > Date.now(), 'ritual task keeps a future native reminder: ' + (ritual && ritual.title));
  }

  // today & goals tabs render
  await f.locator('#tabs [data-tab="today"]').click();
  await f.locator('#today .card').first().waitFor({ timeout: 10000 });
  await page.waitForTimeout(200);
  const todayTxt = await f.locator('#today').innerText();
  check(/主线：备考为主/.test(todayTxt) && /时间投入/.test(todayTxt), 'today tab shows plan and time');
  await shot('5-today');
  await f.locator('#tabs [data-tab="goals"]').click();
  await f.locator('#goals .goal').first().waitFor({ timeout: 10000 });
  const goalsTxt = await f.locator('#goals').innerText();
  check(/国考（2027 年度）/.test(goalsTxt) && /四川省考/.test(goalsTxt) && /社交突破/.test(goalsTxt), 'goals tab shows the default goals');
  await f.locator('#goals .goal', { hasText: '国考' }).locator('.goal-head').click();
  await page.waitForTimeout(150);
  await shot('6-goals');
  await f.locator('#tabs [data-tab="chat"]').click();
  await page.waitForTimeout(150);
  await shot('7-chat');

  const log = await page.evaluate(() => window.__log);
  if (native) check(log.native > 0 && log.proxy === 0, `native channel used (native=${log.native}, proxy=${log.proxy})`);

  // reload restores the conversation
  await page.reload();
  await f.locator('.row.me').first().waitFor({ timeout: 10000 });
  check((await f.locator('.row.me').count()) >= 8, 'conversation restored after reload');
  check((await f.locator('.diag').count()) === 1, 'diagnosis card restored after reload');

  await browser.close();
}

try {
  await run({ native: true, theme: 'dark' });
  await run({ native: false, theme: 'light' });
} finally {
  server.close();
}
console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
