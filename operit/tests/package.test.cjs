'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {setup} = require('./helpers.cjs');

const root = path.join(__dirname, '..', 'toolpkg');

test('METADATA parses and every declared tool is exported', () => {
  const src = fs.readFileSync(path.join(root, 'packages', 'zhukong.js'), 'utf8');
  const meta = JSON.parse(/\/\* METADATA\s*([\s\S]*?)\*\//.exec(src)[1]);
  const mod = require(path.join(root, 'packages', 'zhukong.js'));
  for (const t of meta.tools) assert.equal(typeof mod[t.name], 'function', t.name);
  assert.equal(Object.keys(mod).length, meta.tools.length);
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'manifest.json'), 'utf8'));
  assert.equal(manifest.subpackages[0].id, meta.name);
  for (const f of [manifest.main, manifest.subpackages[0].entry, manifest.resources[0].path]) assert.ok(fs.existsSync(path.join(root, f)), f);
});

test('main.js registers the console route and sidebar entry', () => {
  const regs = [];
  global.ToolPkg = new Proxy({}, {get: (_, k) => (...a) => regs.push([k, a[0]])});
  try {
    const main = require(path.join(root, 'main.js'));
    assert.equal(main.registerToolPkg(), true);
  } finally { delete global.ToolPkg; }
  const routes = regs.filter(r => r[0] === 'registerUiRoute').map(r => r[1]);
  const core = routes.find(r => r.id === 'console');
  assert.equal(core.route, 'toolpkg:com.community.zhukong:ui:console');
  assert.ok(routes.every(r => typeof r.screen === 'function'));
  assert.ok(regs.some(r => r[0] === 'registerNavigationEntry' && r[1].surface === 'main_sidebar_plugins' && r[1].route === core.route));
  assert.ok(routes.some(r => r.id === 'panel'));
});

// 一个极简的 Compose DSL 假宿主：渲染成树，按钮可点。
function render(Screen, callTool) {
  const state = new Map(), refs = new Map();
  const node = type => (props = {}, children) => ({type, props, children: children === undefined ? [] : Array.isArray(children) ? children : [children]});
  const UI = new Proxy({}, {get: (_, k) => node(k)});
  const ctx = {UI,
    useState: (k, v) => { if (!state.has(k)) state.set(k, v); return [state.get(k), x => state.set(k, x)]; },
    useRef: (k, v) => { if (!refs.has(k)) refs.set(k, {current: v}); return refs.get(k); },
    callTool, showToast: () => {}};
  const tree = () => Screen(ctx);
  const all = t => [t, ...t.children.flatMap(all)];
  return {
    tree, state,
    texts: () => all(tree()).filter(n => n.type === 'Text').map(n => n.props.text).join('\n'),
    button: label => all(tree()).find(n => n.type === 'Button' && n.props.text.includes(label)),
    field: label => all(tree()).find(n => n.type === 'TextField' && n.props.label.includes(label))
  };
}

test('console panel: refresh, start reading, gate submit all run against real tools', async () => {
  const {svc} = setup();
  const pkg = require(path.join(root, 'ui', 'console.ui.js'));
  const map = {status: () => svc.status(), reading_start: p => svc.readingStart(p), gate_open: p => svc.gateOpen(p),
    gate_submit: p => svc.gateSubmit({gate_id: p.gate_id, answers: JSON.parse(p.answers_json)})};
  const callTool = async (name, params) => {
    const fn = map[name.split(':')[1]];
    try { return {success: true, data: await fn(params)}; } catch (e) { return {success: false, message: e.message}; }
  };
  const ui = render(pkg.default, callTool);
  assert.equal(pkg.Screen, pkg.default);
  await ui.tree().props.onLoad();
  assert.match(ui.texts(), /今天钥匙：剩 2 把/);
  ui.field('材料标题').props.onValueChange('资料分析 第3讲');
  await ui.button('开始伴读').props.onClick();
  assert.match(ui.texts(), /已打开 Gemini/);
  await ui.button('我想打开 B站').props.onClick();
  ui.field('1. ').props.onValueChange('看一个up主的新视频');
  ui.field('2. ').props.onValueChange('十分钟');
  ui.field('3. ').props.onValueChange('回来做完资料分析五道题');
  await ui.button('交上').props.onClick();
  assert.match(ui.texts(), /钥匙给你：10 分钟/);
  assert.match(ui.texts(), /B站 还能用 10 分钟，回来先做：回来做完资料分析五道题/);
});

test('core console: chat is the main area, the side panel runs the task loop against real tools', async () => {
  const {svc, host} = setup();
  host.files['/sdcard/Download/Operit/p2/usage_last.json'] = JSON.stringify({data: {apps: [{package: 'tv.danmaku.bili', minutes: 20}]}});
  const pkg = require(path.join(root, 'ui', 'core.ui.js'));
  const map = {console_state: () => svc.consoleState(), dashboard: () => svc.dashboard(), start_task: p => svc.startTask(p),
    answer: p => svc.answer(p), pause_task: () => svc.pause(), resume_task: () => svc.resume(), gate_open: p => svc.gateOpen(p),
    gate_submit: p => svc.gateSubmit({gate_id: p.gate_id, answers: JSON.parse(p.answers_json)}), switch_chat: p => svc.switchChat(p)};
  const callTool = async (name, params) => {
    const fn = map[name.split(':')[1]];
    if (!fn) return {success: false, message: 'Tool not found: ' + name};
    try { return {success: true, data: await fn(params)}; } catch (e) { return {success: false, message: e.message}; }
  };
  const ui = render(pkg.default, callTool);
  const tree = ui.tree();
  assert.equal(tree.type, 'AdaptiveSidePanel');
  assert.equal(tree.children.length, 1);
  const flat = t => [t, ...t.children.flatMap(flat)];
  assert.ok(flat(tree).some(n => n.type === 'AiChat'));
  await tree.props.onLoad();
  const sideTexts = () => { const all = []; const walk = n => { if (n.type === 'Text') all.push(n.props.text); n.children.forEach(walk); (n.props.side ? [n.props.side] : []).forEach(walk); }; walk(ui.tree()); return all.join('\n'); };
  assert.match(sideTexts(), /还没开始/);
  assert.match(sideTexts(), /重度 App 合计 20 分钟/);
  const side = () => ui.tree().props.side;
  const find = (pred) => flat(side()).find(pred);
  find(n => n.type === 'TextField' && n.props.label === '现在在做什么').props.onValueChange('把资料分析第3讲弄懂');
  await find(n => n.type === 'Button' && n.props.text === '开始').props.onClick();
  assert.match(sideTexts(), /把资料分析第3讲弄懂/);
  find(n => n.type === 'TextField' && n.props.label === '我的回答').props.onValueChange('先找基期');
  await find(n => n.type === 'Button' && n.props.text === '保存并继续').props.onClick();
  assert.match(sideTexts(), /已写回答 1 条/);
  assert.equal(host.cc.read().core.session.cognition[0].text, '先找基期');
  await find(n => n.type === 'Button' && n.props.text === '我想打开 B站').props.onClick();
  find(n => n.type === 'TextField' && n.props.label.startsWith('1. ')).props.onValueChange('看一个up主的新视频');
  find(n => n.type === 'TextField' && n.props.label.startsWith('2. ')).props.onValueChange('十分钟');
  find(n => n.type === 'TextField' && n.props.label.startsWith('3. ')).props.onValueChange('回来做五道资料分析题');
  await find(n => n.type === 'Button' && n.props.text === '交上').props.onClick();
  assert.match(sideTexts(), /钥匙给你：10 分钟/);
  await find(n => n.type === 'Button' && n.props.text === '找小满聊').props.onClick();
  assert.equal(host.current_chat, '52a18815-xiaoman');
  assert.equal(ui.tree().props.open, false);
});

// 复现 Operit 注册阶段的行为（JsExecutionScriptBuilder.kt）：require 到 *.ui.js 时返回占位函数，
// 并按 JsToolPkgRegistration.kt 的 normalizeScreenField 规则检查 screen 是否可序列化。
test('registration mode: every UI route passes a serializable screen reference', () => {
  const src = fs.readFileSync(path.join(root, 'main.js'), 'utf8');
  const fakeRequire = req => {
    if (/\.ui\.js$/.test(req)) { const f = function ScreenPlaceholder() { return null; }; f.__operit_toolpkg_module_path = req.replace(/^\.\//, ''); return f; }
    throw new Error('unexpected require in main.js: ' + req);
  };
  const regs = [];
  global.ToolPkg = new Proxy({}, {get: (_, k) => (...a) => regs.push([k, a[0]])});
  const mod = {exports: {}};
  try {
    new Function('module', 'exports', 'require', src)(mod, mod.exports, fakeRequire);
    assert.equal(mod.exports.registerToolPkg(), true);
  } finally { delete global.ToolPkg; }
  const routes = regs.filter(r => r[0] === 'registerUiRoute').map(r => r[1]);
  assert.equal(routes.length, 2);
  for (const r of routes) {
    const sc = r.screen;
    const ok = typeof sc === 'string' || (typeof sc === 'function' && typeof sc.__operit_toolpkg_module_path === 'string');
    assert.ok(ok, 'route ' + r.id + ' needs a serializable screen reference');
  }
  assert.deepEqual(routes.map(r => r.screen.__operit_toolpkg_module_path).sort(), ['ui/console.ui.js', 'ui/core.ui.js']);
});
