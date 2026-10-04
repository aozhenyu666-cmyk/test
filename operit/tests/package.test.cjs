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
  const route = regs.find(r => r[0] === 'registerUiRoute')[1];
  assert.equal(typeof route.screen, 'function');
  assert.ok(regs.some(r => r[0] === 'registerNavigationEntry' && r[1].surface === 'main_sidebar_plugins' && r[1].route === route.route));
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
