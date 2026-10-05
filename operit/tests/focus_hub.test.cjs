'use strict';
// Focus Hub 0.8.0 新增部分的模拟测试：「现在」页任务循环、专注时段与钥匙、专注推进工具。
// 宿主接口按手机上的真实形状模拟（readPart 带行号前缀、Intent、ChatHistoryManager、TTS）。
// cognitive_core 用其真实状态机（vendor），control_plane 的任务绑定和 coach 由测试控制。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const CC = require('./vendor/cognitive-continuity-0.2.0/runtime.js');

const FH = path.join(__dirname, '..', 'focus_hub', 'dist');
const BJ = (h, m) => Date.UTC(2026, 9, 6, h - 8, m); // 北京时间

function env({purpose = 'real_task', coach, begin} = {}) {
  let now = BJ(10, 0);
  const files = {};
  const calls = [];
  let doc = CC.fresh();
  const store = {read: () => JSON.parse(JSON.stringify(doc)), transact(fn) { const d = JSON.parse(JSON.stringify(doc)); const r = fn(d); doc = d; return r; }};
  const cc = CC.makeRuntime(store, {}, () => now);
  const lines = c => c.split('\n').filter((l, i, a) => !(i === a.length - 1 && l === ''));
  global.Tools = {
    Files: {
      async exists(p) { return {exists: p in files, isDirectory: false}; },
      async readPart(p, start, end) {
        const ls = lines(files[p] || '');
        const slice = ls.slice(start - 1, end);
        return {content: slice.map((l, i) => `${start + i}| ${l}`).join('\n'), totalLines: ls.length};
      },
      async write(p, content, append) { files[p] = append ? (files[p] || '') + content : content; return {success: true}; },
    },
    Chat: {
      async listChats(q) { calls.push(['listChats', q]); return {chats: [{id: 'core-1', title: '核心对话'}, {id: 'xm-1', title: '小满'}].filter(c => !q || !q.query || c.title.includes(q.query))}; },
      async startService(o) { calls.push(['startService', o]); return {}; },
      async switchTo(id) { calls.push(['switchTo', id]); return {}; },
    },
    SoftwareSettings: {async testTtsPlayback(text) { calls.push(['tts', text]); return {playbackTriggered: true}; }},
  };
  global.toolCall = async (name, params) => {
    calls.push(['toolCall', name, params]);
    if (name === 'voice_bar:say') throw new Error('Tool not found: voice_bar:say');
    if (name === 'cognitive_core:status') {
      const state = cc.read(), s = state.core.session;
      state.control_plane = {revision: 7, policy: {task_binding: s ? {session_id: s.id, purpose} : null}};
      return {success: true, data: {state}};
    }
    if (name === 'cognitive_core:apply') {
      try { return {success: true, data: {result: cc.event(JSON.parse(params.event_json))}}; }
      catch (e) { return {success: false, message: e.message, data: {code: e.code}}; }
    }
    if (name === 'control_plane:coach' && coach) return coach(params, cc);
    if (name === 'cognitive_core:begin_task' && begin) return begin(params, cc);
    throw new Error('Tool not found: ' + name);
  };
  global.Intent = class { constructor(a) { this.a = a; } setComponent() { return this; } addFlag() { return this; } putExtra(k, v) { this.route = v; return this; } async start() { calls.push(['intent', this.route]); } };
  global.Java = {
    getApplicationContext: () => ({getPackageName: () => 'com.ai.assistance.operit'}),
    com: {ai: {assistance: {operit: {data: {repository: {ChatHistoryManager: {getInstance: () => ({async callSuspend(m, id) { calls.push(['chm', m, id]); return m === 'chatExists' ? true : null; }})}}}}}}},
    type: () => class { setDataSource() {} prepare() {} start() {} getDuration() { return 0; } release() {} },
  };
  global.Icons = new Proxy({}, {get: (_, k) => k});
  const realNow = Date.now;
  Date.now = () => now;
  for (const k of Object.keys(require.cache)) if (k.startsWith(FH)) delete require.cache[k];
  return {files, calls, cc, set: t => { now = t; }, advance: ms => { now += ms; }, at: () => now, restore: () => { Date.now = realNow; }};
}

const thinking = () => require(path.join(FH, 'shared', 'thinking.js'));
const gate = () => require(path.join(FH, 'shared', 'gate.js'));
const nav = () => require(path.join(FH, 'packages', 'focus_hub_nav.js'));

test('install-test session is not treated as the user’s task; answers are refused', async () => {
  const e = env({purpose: 'machine_test'});
  try {
    e.cc.event({type: 'start', event_id: 's', session_id: 'machine', title: '认知接续系统安装实测', object: 'o', goal: 'g'});
    const t = await thinking().loadTask();
    assert.equal(t.real, false);
    await assert.rejects(() => thinking().answer('随便写一句'), /安装测试/);
    assert.equal(e.cc.read().core.session.cognition.length, 0);
  } finally { e.restore(); }
});

test('real task: the answer is saved as the user’s words, then the backend model is asked to continue', async () => {
  const seen = [];
  const e = env({coach: p => { seen.push(p); return {success: true, data: {status: 'saved'}}; }});
  try {
    e.cc.event({type: 'start', event_id: 's', session_id: 'real:1', title: '资料分析', object: '资料分析第3讲', goal: 'g'});
    const r = await thinking().answer('增长率先找基期');
    assert.equal(r.coach.status, 'saved');
    assert.equal(e.cc.read().core.session.cognition[0].text, '增长率先找基期');
    assert.equal(seen[0].expected_control_revision, 7);
    assert.equal(seen[0].expected_core_revision, e.cc.read().core.revision);
    assert.equal(seen[0].event_id, 'hub-coach:real:1:q:2');
  } finally { e.restore(); }
});

test('coach refusals come back in plain words; pause and resume work', async () => {
  const e = env({coach: () => ({success: false, message: 'x', data: {code: 'COACH_ALREADY_ATTEMPTED'}})});
  try {
    e.cc.event({type: 'start', event_id: 's', session_id: 'real:1', title: 't', object: 'o', goal: 'g'});
    const c = await thinking().coach('retry');
    assert.match(c.note, /已经试过一次/);
    await thinking().pause();
    assert.equal(e.cc.read().core.session.status, 'PAUSED');
    await assert.rejects(() => thinking().answer('x'), /我回来了/);
    await thinking().resume();
    assert.equal(e.cc.read().core.session.status, 'ACTIVE');
  } finally { e.restore(); }
});

test('start falls back to the backend console when begin_task is missing; uses it when present', async () => {
  let e = env();
  try { assert.equal((await thinking().startTask('读一篇文章', '')).status, 'use_backend_console'); } finally { e.restore(); }
  e = env({begin: (p, cc) => { cc.event({type: 'start', event_id: 'b', session_id: 'real:2', title: p.object, object: p.object, goal: 'g'}); return {success: true, data: {}}; },
    coach: () => ({success: true, data: {status: 'saved'}})});
  try {
    const r = await thinking().startTask('读一篇文章', 'https://example.com/a');
    assert.equal(r.status, 'started');
    const call = e.calls.find(c => c[1] === 'cognitive_core:begin_task');
    assert.equal(call[2].material_ref, 'https://example.com/a');
  } finally { e.restore(); }
});

test('gate: no key needed outside windows; inside, three real answers give a 10-minute key that is persisted', async () => {
  const e = env();
  try {
    e.set(BJ(13, 0));
    assert.equal((await gate().openGate('tv.danmaku.bili', e.at())).status, 'not_in_window');
    e.set(BJ(14, 40));
    const o = await gate().openGate('tv.danmaku.bili', e.at());
    assert.equal(o.status, 'asking');
    const bad = await gate().submitGate(o.gate.id, ['随便', '', ''], e.at());
    assert.equal(bad.status, 'retry');
    const ok = await gate().submitGate(o.gate.id, ['看一个up主的新视频', '十分钟', '回来做五道资料分析题'], e.at());
    assert.equal(ok.status, 'granted');
    assert.equal(ok.key.status, 'unbound');
    assert.equal(ok.key.minutes, 10);
    const state = await gate().loadGate();
    assert.equal(state.keys.length, 1);
    e.advance(11 * 60000);
    const t = await gate().tickGate(e.at());
    assert.deepEqual(t.closed, [{app: 'B站', back_to: '回来做五道资料分析题'}]);
  } finally { e.restore(); }
});

test('focus nudge: silent outside windows, speaks the current question and pops up the hub inside', async () => {
  const e = env();
  try {
    e.cc.event({type: 'start', event_id: 's', session_id: 'real:1', title: '资料分析', object: '资料分析第3讲', goal: 'g'});
    e.set(BJ(12, 0));
    assert.equal((await nav().focus_nudge({})).status, 'OUTSIDE_WINDOW');
    e.set(BJ(9, 40));
    const r = await nav().focus_nudge({});
    assert.equal(r.status, 'NUDGED');
    assert.match(r.line, /^回来接着想：/);
    assert.equal(r.speak, 'ACCEPTED');
    assert.equal(r.popup, 'ACCEPTED');
    assert.ok(e.calls.some(c => c[0] === 'intent' && c[1] === 'toolpkg:local.focus_hub:ui:focus_hub'));
    assert.ok(e.calls.some(c => c[0] === 'tts' && /回来接着想/.test(c[1])));
    e.advance(5 * 60000);
    assert.equal((await nav().focus_nudge({})).status, 'SKIP_SPACING');
  } finally { e.restore(); }
});

test('focus nudge: recent answer, short pause and no task are handled as agreed', async () => {
  const e = env({coach: () => ({success: true, data: {status: 'saved'}})});
  try {
    e.set(BJ(20, 10));
    const none = await nav().focus_nudge({});
    assert.equal(none.reason, 'NO_TASK');
    e.cc.event({type: 'start', event_id: 's', session_id: 'real:1', title: 't', object: 'o', goal: 'g'});
    e.advance(25 * 60000);
    await thinking().answer('刚答了一句');
    assert.equal((await nav().focus_nudge({})).status, 'SKIP_ACTIVE');
    await thinking().pause();
    e.advance(5 * 60000);
    assert.equal((await nav().focus_nudge({})).status, 'SKIP_PAUSED');
    e.advance(16 * 60000);
    const back = await nav().focus_nudge({});
    assert.equal(back.reason, 'PAUSE_OVER');
  } finally { e.restore(); }
});

test('focus nudge also closes expired keys and reminds what the user said they would do', async () => {
  const e = env();
  try {
    e.set(BJ(10, 0));
    const o = await gate().openGate('com.ss.android.ugc.aweme', e.at());
    await gate().submitGate(o.gate.id, ['回一个朋友的私信', '5分钟', '回来把第二段写完'], e.at());
    e.set(BJ(12, 0));
    const r = await nav().focus_nudge({});
    assert.equal(r.status, 'OUTSIDE_WINDOW');
    assert.equal(r.closed_keys, 1);
    assert.ok(e.calls.some(c => c[0] === 'tts' && /你说过回来先做：回来把第二段写完/.test(c[1])));
  } finally { e.restore(); }
});

// 极简 Compose DSL 假宿主
function render(Screen) {
  const state = new Map(), refs = new Map();
  const node = type => (props = {}, children) => ({type, props, children: children === undefined ? [] : Array.isArray(children) ? children : [children]});
  const UI = new Proxy({}, {get: (_, k) => node(k)});
  const colors = new Proxy({}, {get: (_, k) => '#' + String(k)});
  const ctx = {UI, MaterialTheme: {colorScheme: colors},
    useState: (k, v) => { if (!state.has(k)) state.set(k, v); return [state.get(k), x => state.set(k, x)]; },
    useRef: (k, v) => { if (!refs.has(k)) refs.set(k, {current: v}); return refs.get(k); },
    navigate: async r => { ctx.navigated = r; }, showToast: async () => {}};
  const flat = t => [t, ...(t.children || []).flatMap(flat)];
  return {ctx, tree: () => Screen(ctx), all: () => flat(Screen(ctx)),
    texts: () => flat(Screen(ctx)).filter(n => n.type === 'Text').map(n => n.props.text).join('\n')};
}

test('hub opens on the Now page and runs start → answer → AI continues', async () => {
  const e = env({coach: (p, cc) => {
    const s = cc.read().core.session;
    cc.event({type: 'set_next_entry', event_id: 'c:' + p.event_id, session_id: s.id, question_id: s.current_question_id, question: 'AI 的追问：基期怎么找？', source: 'CONTROL_MODEL'});
    return {success: true, data: {status: 'saved'}};
  }});
  try {
    e.cc.event({type: 'start', event_id: 's', session_id: 'real:1', title: '资料分析第3讲', object: '资料分析第3讲', goal: 'g'});
    const Screen = require(path.join(FH, 'ui', 'focus_hub', 'index.ui.js')).default;
    const ui = render(Screen);
    const root = ui.tree();
    await root.props.onLoad();
    assert.match(ui.texts(), /资料分析第3讲/);
    assert.match(ui.texts(), /专注时段|进行中/);
    ui.all().find(n => n.type === 'TextField' && /写下你自己的回答/.test(n.props.placeholder)).props.onValueChange('增长率=（现期-基期）/基期');
    await ui.all().find(n => n.type === 'Button' && n.props.text === '保存并继续').props.onClick();
    assert.match(ui.texts(), /已保存，AI 接着问了/);
    assert.match(ui.texts(), /AI 的追问：基期怎么找？/);
    assert.equal(e.cc.read().core.session.cognition[0].text, '增长率=（现期-基期）/基期');
    await ui.all().find(n => n.type === 'Button' && n.props.text === '打开核心对话').props.onClick();
    assert.ok(e.calls.some(c => c[0] === 'chm' && c[1] === 'setCurrentChatId' && c[2] === 'core-1'));
    assert.equal(ui.ctx.navigated, 'native.ai_chat');
  } finally { e.restore(); }
});
