// 端到端验收：在 Chromium（手机尺寸）里跑一遍 v0.1 验收清单。
//   node dev/e2e.mjs            SHOTS=1 时把截图写到 docs/screenshots/
import { execSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer, calls, COLLIDE } from './server.mjs';
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
await ctx.grantPermissions(['clipboard-read', 'clipboard-write']);
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
await page.waitForSelector('[data-testid=setup-hint]');
check(true, '首次打开：提示配置模型或使用手动模式');
await page.evaluate((o) => {
  const m = { preset: 'custom', label: 'Mock', format: 'openai', base: o + '/mock/v1', model: 'mock', key: 'test' };
  localStorage.setItem('mailuo.config', JSON.stringify({ split: m, think: { ...m, model: 'mock-pro' }, budget: 1500, theme: 'auto' }));
}, ORIGIN);
await page.reload();
await page.waitForSelector('[data-testid=seed]');
check(await page.locator('[data-testid=setup-hint]').count() === 0, '填好密钥后不再提示');
await shot('01-empty');

// ---------- 首个试用体系 ----------
await page.click('[data-testid=seed]');
await page.waitForSelector('.toast');
let f = await files();
check(Object.keys(f).some((p) => p === '思维问题/思维问题.md'), '体系文件 思维问题/思维问题.md 已写入');
check(!!f['思维问题/下一问没有产生.md'], '节点文件扁平存放（思维问题/下一问没有产生.md）');
const rootA = f['思维问题/下一问没有产生.md'];
const metaA = yamlLoad(rootA.split('---')[1]);
check(metaA.links.some((l) => l.type === '对立' && l.to === 'n-0007'), '两个竞争根因建立了「对立」关系');
check(metaA.verify && yamlLoad(f['思维问题/单步成本高、停止信号来得早.md'].split('---')[1]).verify, '两个根因各填了验证方式');
check(rootA.includes('- 对立 [[单步成本高、停止信号来得早]]'), '正文写了 Obsidian 双链');
await page.goto(ORIGIN + '/#/');
await page.waitForSelector('[data-testid=usage]');
await shot('02-today');

// ---------- 链条视图 + 拖动挂载 ----------
await page.goto(ORIGIN + '/#/tree');
await page.waitForSelector('.chain-tree');
await shot('03-tree');
const drag = async (id, targetId) => {
  const grip = page.locator('.node-row[data-id="' + id + '"] .grip');
  const target = page.locator('[data-drop="node:' + targetId + '"]');
  await grip.scrollIntoViewIfNeeded();
  const gb = await grip.boundingBox(), tb = await target.boundingBox();
  await page.mouse.move(gb.x + gb.width / 2, gb.y + gb.height / 2);
  await page.mouse.down();
  await page.mouse.move(tb.x + tb.width / 2, tb.y + tb.height / 2, { steps: 8 });
  await page.mouse.up();
};
await drag('n-0101', 'n-0007');
await page.waitForFunction(async () => (await window.__mailuo.vault.read(window.__mailuo.store.nodes.get('n-0101').path)).includes('under: n-0007'));
check(true, '拖动规到论：under 写入目标节点');
await page.evaluate(async () => {
  const { blankNode } = await import('/js/model.js');
  await window.__mailuo.store.saveNode(blankNode({id:'n-0102', title:'挂载校验用规', type:'规', system:'思维问题', under:'n-0006'}));
});
await page.reload();
await page.waitForSelector('.chain-tree');
await drag('n-0101', 'n-0102');
await page.waitForSelector('.toast');
f = await files();
check(Object.values(f).some((t) => t.includes('id: n-0101') && t.includes('under: n-0007')), '规不能挂在另一条规下面，原挂载保留');
check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), '390px 链条视图无横向滚动');

await page.evaluate(() => window.__mailuo.store.deleteNode('n-0102'));

// ---------- 录入碰撞引擎 ----------
await page.goto(ORIGIN + '/#/capture');
const material = ('我发现自己思考时，第一个念头出来之后，就会出现一种「想完了」的舒缓感，于是停下来，没有继续加工。' +
  '这种舒缓感像一个停止信号。如果在停下之前强制写下下一个问题，就能继续往下推。').repeat(9).slice(0, 1000);
await page.fill('[data-testid=capture-text]', material);
check((await page.textContent('[data-testid=digest-summary]')).includes('将附上节点库摘要'), '录入前显示节点库摘要和 token 估算');
const t0 = Date.now();
await page.click('[data-testid=split]');
await page.waitForSelector('[data-testid=cand]', { timeout: 30000 });
check((Date.now() - t0) < 30000, '1000 字材料 30 秒内得到碰撞候选');
const draftCands = await page.evaluate(() => JSON.parse(localStorage.getItem('mailuo.draft')));
check(draftCands.adjust.length === 2 && draftCands.nodes.length === 2, '碰撞结果保存 2 条调整与 2 张新节点草稿');
check(calls[0].sys.includes('碰撞引擎') && calls[0].user.includes('节点库摘要') && calls[0].user.includes('n-0006'), '录入请求使用碰撞引擎并带相关节点库摘要');
await shot('04-cands');
await page.locator('[data-testid=cand] [data-testid=accept]').first().click();
await page.locator('[data-testid=cand] [data-testid=accept]').first().click();
await page.click('[data-testid=commit]');
await page.waitForURL(/#\/tree/);
f = await files();
check(Object.values(f).some((t) => t.includes('title: 累了先把下一步写在纸上') && t.includes('under: n-0007') && t.includes('triggers:')), '补链新规带触发场景挂在已有论下面');
check(Object.values(f).some((t) => t.includes('title: 写下来能延后停止点') && t.includes('under: n-0100') && t.includes('sentinels:')), '补链新论带验证哨挂在已有问下面');

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
check((await page.textContent('.slots')).includes('反方盲区') && await page.locator('.slots .sentinel').count() === 2 && (await page.textContent('.slots')).includes('+10'), '论详情展示盲区与两条有分值的验证哨');
await shot('05-node');
await page.click('button[aria-label="编辑"]');
await page.click('[data-testid=add-sentinel]');
await page.locator('.sentinel-edit').last().locator('input').first().fill('连续三天卡住都写了下一问');
await page.locator('.sentinel-edit').last().locator('input[type=number]').fill('5');
await page.click('.sheet button:text-is("保存")');
await page.waitForFunction(async () => {
  const n=window.__mailuo.store.nodes.get('n-0006');
  return (await window.__mailuo.vault.read(n.path)).includes('连续三天卡住都写了下一问, delta: 5');
});
check(true, '编辑验证哨后现象和 delta: 5 写回文件');
await page.goto(ORIGIN + '/#/node/n-0101');
await page.waitForSelector('.slots');
check((await page.textContent('.slots')).includes('触发场景') && (await page.textContent('.slots')).includes('破戒代价'), '规详情展示触发场景和破戒代价');
await shot('05b-rule');
await page.click('button[aria-label="编辑"]');
await page.click('.mount-picker summary');
check(await page.locator('[data-testid=mount-list] .node-row[data-id="n-0101"]').count() === 0 && !await page.locator('[data-testid=mount-list] .glyph').evaluateAll((els) => els.some((e) => e.textContent === '规')), '挂载选择器不列出自己或任何规');
await page.evaluate(() => window.__mailuo.back());
await page.goto(ORIGIN + '/#/node/n-0006');
await page.waitForSelector('[data-testid=ask]');
await page.click('[data-testid=ask]');
await page.fill('[data-testid=question]', '这两个根因哪个更可能是真的？');
const tokens = +(await page.textContent('[data-testid=pack-tokens]')).split('/')[0];
check(tokens > 0 && tokens <= 1500, '打包内容 ' + tokens + ' token（≤ 1500）');
await page.click('text=预览发送全文');
const raw = await page.textContent('[data-testid=pack-raw]');
check(/省 \d+%/.test(await page.textContent('[data-testid=savings]')), '提问页显示「整库 vs 本次」省了多少 token：' + (await page.textContent('[data-testid=savings]')).replace(/\s+/g, ''));
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
check(await page.locator('[data-testid=cand]').first().locator('.kw').count() === 3, '「建议新增」带回 3 个激活词');

// 左滑丢弃一张候选卡
const card = page.locator('[data-testid=cand]').nth(1);
const cb = await card.boundingBox();
await page.mouse.move(cb.x + cb.width / 2, cb.y + 40);
await page.mouse.down();
await page.mouse.move(cb.x + cb.width / 2 - 160, cb.y + 44, { steps: 10 });
await page.mouse.up();
await page.waitForSelector('[data-testid=cand].discarded');
check(true, '候选卡片左滑即丢弃');
await shot('15-swipe');

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

// ---------- 新增：不走 API 的手动模式 ----------
await page.goto(ORIGIN + '/#/ask/n-0007');
await page.fill('[data-testid=question]', '怎样区分这两个根因？');
await page.click('details.manual summary');
await page.click('[data-testid=manual-ask-copy]');
const clip = await page.evaluate(() => navigator.clipboard.readText());
check(clip.includes('只输出：') && clip.includes('【路径】思维问题 › 根因') && clip.includes('问题：怎样区分这两个根因？'), '手动模式：复制的是「提示词 B + 打包内容」');
const before = calls.length;
await page.fill('[data-testid=manual-ask-paste]', `**核心判断**：先看「累不累」，再看「有没有问题可问」。
**结构**：单步成本 → 舒缓感 → 停止
**把握**：7成
**验证**：本周卡住时同时记录疲劳度和是否有下一问。
**建议新增**：
- 对立｜疲劳主导｜停下时疲劳评分高，与有没有下一问无关｜疲劳、认知负荷、精力`);
await page.click('[data-testid=manual-ask-parse]');
await page.waitForSelector('[data-testid=core]');
check(calls.length === before, '手动模式不调用 API');
check((await page.textContent('[data-testid=answer]')).includes('未走 API') && (await page.textContent('[data-testid=core]')).includes('累不累'), '手动模式：粘贴的回答（含 Markdown 加粗）能解析');
await shot('16-manual');

await page.goto(ORIGIN + '/#/capture');
await page.fill('[data-testid=capture-text]', '学习新知识时，先建立框架再填细节，记忆更牢；框架像挂钩。');
await page.click('details.manual summary');
await page.click('[data-testid=manual-split-copy]');
check((await page.evaluate(() => navigator.clipboard.readText())).includes('碰撞引擎'), '手动模式：复制碰撞指令');
const manualBefore = calls.length;
await page.fill('[data-testid=manual-split-paste]', JSON.stringify(COLLIDE));
await page.click('[data-testid=manual-split-parse]');
await page.waitForSelector('[data-testid=cand]');
const manualDraft = await page.evaluate(() => JSON.parse(localStorage.getItem('mailuo.draft')));
check(manualDraft.adjust.length === 2 && manualDraft.nodes.length === 2 && calls.length === manualBefore, '手动碰撞结果走同一规范化流程，不调用 API');

// ---------- 新增：全库搜索 / 返回键关闭弹层 ----------
await page.goto(ORIGIN + '/#/');
await page.click('[data-testid=search]');
await page.fill('[data-testid=search-input]', '停止');
check(await page.locator('.sheet .node-row').count() >= 2, '全库搜索：按标题 / 结论 / 激活词命中');
await shot('17-search');
await page.locator('.sheet .node-row').first().click();
await page.waitForSelector('.node-hero');
await page.click('[data-testid=backfill]');
await page.goBack();
await page.waitForTimeout(200);
check(await page.locator('.sheet').count() === 0, '返回时自动关掉弹层');

// ---------- 新增：备份与恢复 ----------
await page.goto(ORIGIN + '/#/settings');
await page.waitForTimeout(300);
const nBackups = await page.locator('[data-testid=backup]').count();
check(nBackups >= 1 && (await page.textContent('[data-testid=backup]')).includes('每日自动'), '每天第一次打开自动备份');
await page.click('[data-testid=backup-now]');
await page.waitForFunction((n) => document.querySelectorAll('[data-testid=backup]').length === n + 1, nBackups);
const nodeCount = await page.evaluate(() => window.__mailuo.store.nodes.size);
// 同一文件夹里的其他 Obsidian 笔记（含 id 字段），备份之后才建
await page.evaluate(() => window.__mailuo.vault.write('日记/2026-10-11.md', '---\nid: 20261011\ntags: [日记]\n---\n今天的随手记。\n'));
await page.evaluate(() => window.__mailuo.store.deleteNode('n-0004'));
await page.locator('[data-testid=backup] button:has-text("恢复")').first().click();
await page.click('.sheet .btn.accent');
await page.waitForFunction((n) => window.__mailuo.store.nodes.size === n, nodeCount);
check(await page.evaluate(() => window.__mailuo.store.nodes.has('n-0004')), '备份恢复：删掉的节点回来了');
check(await page.evaluate(async () => !!(await window.__mailuo.vault.read('日记/2026-10-11.md'))), '恢复备份不会删掉文件夹里你自己的其他笔记');
check(await page.evaluate(() => window.__mailuo.store.nodes.size) === nodeCount, '带 id 的普通笔记不会被当成节点');
await page.waitForFunction((n) => document.querySelectorAll('[data-testid=backup]').length >= n + 2, nBackups, { timeout: 5000 }).catch(() => {});
check((await page.textContent('body')).includes('恢复前'), '恢复前自动给当前状态做了备份');
await shot('14-settings');

// ---------- 新增：AI 补全激活词 / 复制体系大纲 ----------
await page.evaluate(() => { const n = window.__mailuo.store.nodes.get('n-0004'); n.keywords = ['反例']; return window.__mailuo.store.saveNode(n); });
await page.goto(ORIGIN + '/#/health');
await page.click('[data-testid=fill-kw]');
await page.waitForSelector('[data-testid=kw-pick]');
await shot('18-fill-kw');
await page.click('[data-testid=fill-kw-save]');
await page.waitForFunction(() => window.__mailuo.store.nodes.get('n-0004').keywords.length === 3);
check(calls.at(-1).sys.includes('激活词生成器') && calls.at(-1).model === 'mock', '补全激活词走便宜模型，确认后写入');
await page.goto(ORIGIN + '/#/tree');
await page.click('[data-testid=copy-outline]');
const outline = await page.evaluate(() => navigator.clipboard.readText());
check(outline.startsWith('# 思维问题') && outline.includes('[论 60%] 第一反应停止') && outline.includes('[问]') && outline.includes('对立→'), '复制体系大纲（含问论规链条、结论、激活词、关系）');

// ---------- 新增：类比候选一键建「类似」、重复节点合并、本周回顾 ----------
await page.evaluate(async () => {
  const {blankNode}=await import('/js/model.js');
  await window.__mailuo.store.saveNode(blankNode({id:'analogy-fixture',title:'提问清单先行',claim:'先准备问题清单',type:'论',system:'思维问题',under:'n-0100',keywords:['追问','问题生成','清单']}));
});
await page.goto(ORIGIN + '/#/node/n-0006');
await page.waitForSelector('[data-testid=link-like]');
const likeBefore = await page.evaluate(() => window.__mailuo.store.nodes.get('n-0006').links.length);
await page.locator('[data-testid=link-like]').first().click();
await page.waitForFunction((n) => window.__mailuo.store.nodes.get('n-0006').links.length === n + 1, likeBefore);
check(await page.evaluate(() => window.__mailuo.store.nodes.get('n-0006').links.at(-1).type === '类似'), '类比候选一键建立「类似」关系');
await page.evaluate(() => window.__mailuo.store.deleteNode('analogy-fixture'));

await page.evaluate(async () => {
  const s = window.__mailuo.store;
  await s.saveNode({ id: 'n-0099', title: '推理链中断', type: '论断', system: '思维问题', parent: '', keywords: ['推理链', '断点', '演绎'], status: '假设', confidence: 40, verify: '记录断点', claim: '推理到一半就断了。', note: '', links: [], checks: [], extra: {} });
  const src = s.nodes.get('n-0005');
  src.links.push({ type: '支撑', to: 'n-0099', why: '测试用' });
  await s.saveNode(src);
});
await page.goto(ORIGIN + '/#/health');
await page.waitForSelector('[data-testid=dupe-pair]');
check(await page.locator('[data-testid=dupe-pair]', { hasText: '推理链中断' }).count() === 1, '体检找出可能重复的节点对');
await page.locator('[data-testid=dupe-pair]', { hasText: '推理链中断' }).locator('button').click();
await page.click('.sheet label:has-text("推理链断") input');
await page.click('[data-testid=merge-confirm]');
await page.waitForFunction(async () => !window.__mailuo.store.nodes.has('n-0099') && !(await window.__mailuo.vault.read(window.__mailuo.store.nodes.get('n-0005').path)).includes('n-0099'));
// 等 n-0005 的文件写成新双链再检查（CI 机器较慢）
await page.waitForFunction(async () => (await window.__mailuo.vault.read(window.__mailuo.store.nodes.get('n-0005').path)).includes('支撑 [[推理链断]]'), null, { timeout: 5000 }).catch(() => {});
const mergedState = await page.evaluate(async () => {
  const s = window.__mailuo.store;
  const files = await window.__mailuo.vault.list();
  return { redirected: s.nodes.get('n-0005').links.some((l) => l.to === 'n-0002' && l.type === '支撑'), fileGone: !files.some((f) => f.includes('推理链中断')), note: s.nodes.get('n-0002').note,
    body: await window.__mailuo.vault.read(s.nodes.get('n-0005').path) };
});
check(mergedState.redirected && mergedState.fileGone && mergedState.note.includes('推理到一半'), '合并：关系改指向保留节点，旧文件删除，结论并入说明');
const linkOk = mergedState.body.includes('支撑 [[推理链断]]') && !mergedState.body.includes('推理链中断');
if (!linkOk) console.log('n-0005 文件内容：\n' + mergedState.body);
check(linkOk, '合并后双链同步更新');

await page.goto(ORIGIN + '/#/');
await page.waitForSelector('[data-testid=weekly]');
check(/\d+\s*新增节点/.test(await page.textContent('[data-testid=weekly]')) && (await page.textContent('[data-testid=weekly]')).includes('次提问'), '今日页显示本周回顾');
await shot('19-weekly');

// 从文件重新读入再保存，日期字段必须保持 YYYY-MM-DD（不能被 YAML 解析成 Date）
const dates = await page.evaluate(async () => {
  const { store, vault } = window.__mailuo;
  await store.load();
  await store.saveNode(store.nodes.get('n-0005'));
  const out = [];
  for (const p of await vault.list()) if (p.endsWith('.md')) out.push(...((await vault.read(p)).match(/^(created|updated): .*$/gm) || []));
  return out;
});
check(dates.length > 10 && dates.every((d) => /^(created|updated): \d{4}-\d{2}-\d{2}$/.test(d)), '重新读入后保存，日期保持 YYYY-MM-DD');

// ---------- 新增：安卓返回键逻辑 ----------
await page.goto(ORIGIN + '/#/node/n-0001');
await page.waitForSelector('.node-hero');
await page.click('[data-testid=backfill]');
check(await page.evaluate(() => window.__mailuo.back()) === 'sheet' && await page.locator('.sheet').count() === 0, '返回键：先关弹层');
check(await page.evaluate(() => window.__mailuo.back()) === 'back', '返回键：再回上一页');
await page.goto(ORIGIN + '/#/');
check(await page.evaluate(() => window.__mailuo.back()) === 'exit', '返回键：首页时退到后台');

// ---------- 新增：大库加载 ----------
const big = await page.evaluate(async () => {
  const { vault, store } = window.__mailuo;
  for (let i = 0; i < 300; i++) {
    await vault.write('压测/批量/节点' + i + '.md', `---\nid: x-${i}\ntitle: 节点${i}\ntype: 概念\nsystem: 压测\nparent: 批量\nkeywords: [a, b, c]\nstatus: 已验证\nconfidence: 80\n---\n第 ${i} 个节点。\n`);
  }
  const t0 = performance.now();
  await store.load();
  const ms = performance.now() - t0;
  const n = store.list('压测').length;
  for (let i = 0; i < 300; i++) await vault.remove('压测/批量/节点' + i + '.md');
  await store.load();
  return { ms, n };
});
check(big.n === 300 && big.ms < 3000, '300 个节点的库 ' + Math.round(big.ms) + ' ms 读完');

// 当日用量
await page.goto(ORIGIN + '/#/');
const usageText = await page.textContent('[data-testid=usage]');
check(/拆分 2 次 · 推演 1 次/.test(usageText), '主界面显示当日 token 用量');
await shot('02-today');

await browser.close();
server.close();
console.log(failures ? '\n' + failures + ' FAILED' : '\nALL PASSED');
process.exit(failures ? 1 : 0);
