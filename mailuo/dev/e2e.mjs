// 端到端验收：在 Chromium（手机尺寸）里跑一遍 v0.1 验收清单。
//   node dev/e2e.mjs            SHOTS=1 时把截图写到 docs/screenshots/
import { execSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer, calls } from './server.mjs';
import { load as yamlLoad } from '../www/vendor/js-yaml.mjs';

async function loadPlaywright() {
  try { return await import('playwright'); } catch {
    return import(join(execSync('npm root -g').toString().trim(), 'playwright', 'index.mjs'));
  }
}
const here = dirname(fileURLToPath(import.meta.url));
const SHOTS = process.env.SHOTS ? join(here, '..', 'docs', 'screenshots') : null;
if (SHOTS) mkdirSync(SHOTS, { recursive: true });

const { chromium } = await loadPlaywright();
const server = await startServer();
const ORIGIN = 'http://localhost:' + server.address().port;
let failures = 0;
const check = (c, label) => { console.log((c ? 'PASS ' : 'FAIL ') + label); if (!c) failures++; };

const browser = await chromium.launch(process.env.PW_CHROMIUM ? { executablePath: process.env.PW_CHROMIUM } : {});
const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, colorScheme: process.env.DARK ? 'dark' : 'light' });
const page = await ctx.newPage();
page.on('pageerror', (e) => { console.log('pageerror:', e.message); failures++; });
page.on('console', (m) => { if (m.type() === 'error') console.log('console:', m.text()); });
const shot = async (name) => { if (SHOTS) { await page.evaluate(() => document.querySelectorAll('.toast').forEach((t) => t.remove())); await page.waitForTimeout(450); await page.screenshot({ path: join(SHOTS, (process.env.DARK ? 'dark-' : '') + name + '.png') }); } };
const files = () => page.evaluate(async () => {
  const v = window.__mailuo.vault; const out = {};
  for (const p of await v.list()) out[p] = await v.read(p);
  return out;
});

await page.goto(ORIGIN + '/');
await page.evaluate((o) => {
  const m = { preset: 'custom', label: 'Mock', format: 'openai', base: o + '/mock/v1', model: 'mock', key: 'test' };
  localStorage.setItem('mailuo.config', JSON.stringify({ split: m, think: { ...m, model: 'mock-pro' }, budget: 1500, theme: 'auto' }));
}, ORIGIN);
await page.reload();
await page.waitForSelector('[data-testid=seed]');
await shot('01-empty');

// ---------- 首个试用体系 ----------
await page.click('[data-testid=seed]');
await page.waitForSelector('.toast');
let f = await files();
check(Object.keys(f).some((p) => p === '思维问题/思维问题.md'), '体系文件 思维问题/思维问题.md 已写入');
check(!!f['思维问题/根因/下一问没有产生.md'], '层级 = 子文件夹（思维问题/根因/下一问没有产生.md）');
const rootA = f['思维问题/根因/下一问没有产生.md'];
const metaA = yamlLoad(rootA.split('---')[1]);
check(metaA.links.some((l) => l.type === '对立' && l.to === 'n-0007'), '两个竞争根因建立了「对立」关系');
check(metaA.verify && yamlLoad(f['思维问题/根因/单步成本高、停止信号来得早.md'].split('---')[1]).verify, '两个根因各填了验证方式');
check(rootA.includes('- 对立 [[单步成本高、停止信号来得早]]'), '正文写了 Obsidian 双链');
await page.goto(ORIGIN + '/#/');
await page.waitForSelector('[data-testid=usage]');
await shot('02-today');

// ---------- 树视图 + 拖动改父级 ----------
await page.goto(ORIGIN + '/#/tree');
await page.waitForSelector('.lv-head');
await shot('03-tree');
const grip = page.locator('.node-row[data-id="n-0003"] .grip');
const target = page.locator('.lv-head[data-drop="思维问题|加工层"]');
const gb = await grip.boundingBox(), tb = await target.boundingBox();
await page.mouse.move(gb.x + gb.width / 2, gb.y + gb.height / 2);
await page.mouse.down();
await page.mouse.move(tb.x + 60, tb.y + tb.height / 2, { steps: 8 });
await page.mouse.up();
await page.waitForFunction(async () => (await window.__mailuo.vault.list()).includes('思维问题/加工层/依据缺失.md'));
f = await files();
check(!!f['思维问题/加工层/依据缺失.md'] && !f['思维问题/检验层/依据缺失.md'], '拖动改父级：文件移到新层级文件夹');

// ---------- 录入与拆分 ----------
await page.goto(ORIGIN + '/#/capture');
const material = ('我发现自己思考时，第一个念头出来之后，就会出现一种「想完了」的舒缓感，于是停下来，没有继续加工。' +
  '这种舒缓感像一个停止信号。如果在停下之前强制写下下一个问题，就能继续往下推。').repeat(9).slice(0, 1000);
await page.fill('[data-testid=capture-text]', material);
const t0 = Date.now();
await page.click('[data-testid=split]');
await page.waitForSelector('[data-testid=cand]', { timeout: 30000 });
const secs = (Date.now() - t0) / 1000;
check(secs < 30, '1000 字材料 ' + secs.toFixed(1) + ' 秒内得到候选卡片（< 30 秒）');
check(await page.locator('[data-testid=cand]').count() === 3, '3 张候选卡片');
check(await page.locator('[data-testid=dupe]').count() >= 1, '与已有节点重合时提示「可能重复」');
check(await page.locator('.link-cand').count() === 2, '自造关系类型被过滤，只留 2 条候选关系');
await shot('04-cands');
await page.locator('[data-testid=dupe] button:has-text("合并")').first().click();
await page.locator('[data-testid=cand] [data-testid=accept]').first().click();
await page.locator('[data-testid=cand] [data-testid=accept]').first().click();
await page.click('[data-testid=commit]');
await page.waitForSelector('.toast');
check(await page.evaluate(() => window.__mailuo.store.nodes.size) === 9, '写入 2 个新节点 + 1 个合并（共 9 个节点）');
f = await files();
const merged = yamlLoad(f['思维问题/加工层/第一反应停止.md'].split('---')[1]);
check(merged.keywords.length <= 5, '合并：激活词并入已有节点（≤ 5 个）');
const shu = Object.entries(f).find(([p]) => p.endsWith('/舒缓感.md'));
check(shu && shu[1].includes('导致 [[第一反应停止]]'), '新节点的关系指向合并后的已有节点，并写成双链');
check(calls[0].user.includes('n-0001 第一反应停止'), '拆分请求带上了已有节点列表（用于 merge_into）');

// 双链都能在仓库里找到对应文件（Obsidian 关系图可见）
const names = new Set(Object.keys(f).map((p) => p.split('/').at(-1).replace(/\.md$/, '')));
const links = Object.values(f).flatMap((t) => [...t.matchAll(/\[\[([^\]]+)\]\]/g)].map((m) => m[1]));
check(links.length > 8 && links.every((l) => names.has(l)), '全部 ' + links.length + ' 条双链都指向存在的文件');
let yamlOk = true;
for (const [p, t] of Object.entries(f)) if (p.endsWith('.md')) { try { yamlLoad(t.split(/^---$/m)[1]); } catch { yamlOk = false; console.log('bad yaml', p); } }
check(yamlOk, '所有文件的 YAML 头都能被标准解析器读取');

// ---------- 提问：打包 ≤ 1500 token、可预览、对立自动带「如何区分」 ----------
await page.goto(ORIGIN + '/#/node/n-0006');
await page.waitForSelector('[data-testid=ask]');
await shot('05-node');
await page.click('[data-testid=ask]');
await page.fill('[data-testid=question]', '这两个根因哪个更可能是真的？');
const tokens = +(await page.textContent('[data-testid=pack-tokens]')).split('/')[0];
check(tokens > 0 && tokens <= 1500, '打包内容 ' + tokens + ' token（≤ 1500）');
await page.click('text=预览发送全文');
const raw = await page.textContent('[data-testid=pack-raw]');
check(raw.includes('请说明怎样区分这两个解释'), '对立关系：自动加入「请说明怎样区分这两个解释」');
check((raw.match(/对立·竞争解释/g) || []).length === 1, '双向写的对立关系只打包一次');
check(raw.includes('【路径】思维问题 › 根因 › 下一问没有产生') && raw.includes('【激活词】'), '打包含路径、节点、邻居、激活词');
await shot('06-ask-pack');
await page.click('[data-testid=send]');
await page.waitForSelector('[data-testid=core]');
const sent = calls.at(-1);
check(sent.model === 'mock-pro' && !sent.user.includes('推理链断：'), '推演走旗舰模型，且不发整库（无关节点不在请求里）');
check((await page.textContent('[data-testid=core]')).includes('单步成本高'), '回答按结构先行格式解析出「核心判断」');
await page.locator('[data-testid=answer]').scrollIntoViewIfNeeded();
await shot('07-answer');
await page.click('[data-testid=to-cands]');
await page.waitForSelector('[data-testid=cand]');
check(await page.locator('[data-testid=cand]').count() === 3, '「建议新增」解析成 3 张候选卡片');
check((await page.textContent('body')).includes('舒缓感提前'), '候选卡片标题正确');

// ---------- 预算砍减顺序 ----------
await page.goto(ORIGIN + '/#/ask/n-0001');
await page.fill('[data-testid=question]', '为什么会这样？');
const full = +(await page.textContent('[data-testid=pack-tokens]')).split('/')[0];
await page.evaluate((b) => { const c = JSON.parse(localStorage.getItem('mailuo.config')); c.budget = b; localStorage.setItem('mailuo.config', JSON.stringify(c)); }, full - 20);
await page.reload();
await page.fill('[data-testid=question]', '为什么会这样？');
let states = await page.$$eval('.pack-item', (els) => els.map((e) => e.className + '|' + e.querySelector('.k').textContent));
check(states.some((s) => s.includes('short') && s.includes('邻居')) && !states.some((s) => s.includes('dropped')) && states.some((s) => s.includes('full') && s.includes('|路径')),
  '超预算一点：先砍邻居说明，路径不动、不删邻居');
await page.evaluate(() => { const c = JSON.parse(localStorage.getItem('mailuo.config')); c.budget = 120; localStorage.setItem('mailuo.config', JSON.stringify(c)); });
await page.reload();
await page.fill('[data-testid=question]', '为什么会这样？');
states = await page.$$eval('.pack-item', (els) => els.map((e) => e.className + '|' + e.querySelector('.k').textContent));
check(states.some((s) => s.includes('short') && s.includes('|路径')) && states.some((s) => s.includes('dropped')), '超预算很多：再砍远层路径、去掉低优先级邻居');
check(states.some((s) => s.includes('full') && s.includes('|节点')), '节点本身永不砍');
await shot('08-budget');
await page.evaluate(() => { const c = JSON.parse(localStorage.getItem('mailuo.config')); c.budget = 1500; localStorage.setItem('mailuo.config', JSON.stringify(c)); });

// ---------- 回填 ----------
await page.goto(ORIGIN + '/#/verify');
await page.waitForSelector('[data-testid=hyp]');
await shot('09-verify');
await page.locator('[data-testid=hyp] button:has-text("回填")').first().click();
await page.fill('.sheet textarea', '记录 7 天：舒缓感后多做一步 2 次');
await page.click('.sheet .seg button:has-text("已验证")');
await shot('10-backfill');
await page.click('[data-testid=backfill-save]');
await page.waitForFunction(() => [...window.__mailuo.store.nodes.values()].some((n) => n.checks.length));
check(true, '回填写入验证记录并更新状态');

// ---------- 新增模块：体检 / 温故 / 溯源 ----------
await page.goto(ORIGIN + '/#/health');
await page.waitForSelector('.issue');
await shot('11-health');
check(await page.locator('.issue').count() > 0, '体检列出问题');
await page.goto(ORIGIN + '/#/review');
await page.click('[data-testid=flash]');
await shot('12-review');
await page.click('.grades button:has-text("记得")');
f = await files();
check(!!f['.mailuo/review.json'], '温故进度写入 .mailuo/review.json');
await page.goto(ORIGIN + '/#/trace/n-0002');
await page.waitForSelector('.t-self');
check(await page.locator('.t-node.root').count() >= 1, '溯源找到根因候选');
await shot('13-trace');
await page.goto(ORIGIN + '/#/settings');
await shot('14-settings');

// 当日用量
await page.goto(ORIGIN + '/#/');
const usageText = await page.textContent('[data-testid=usage]');
check(/拆分 1 次 · 推演 1 次/.test(usageText), '主界面显示当日 token 用量');
await shot('02-today');

await browser.close();
server.close();
console.log(failures ? '\n' + failures + ' FAILED' : '\nALL PASSED');
process.exit(failures ? 1 : 0);
