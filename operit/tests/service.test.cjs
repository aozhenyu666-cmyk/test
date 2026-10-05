'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {setup} = require('./helpers.cjs');

const MIN = 60000;

test('reading_start opens a thread in cognitive_core, attaches material, launches Gemini', async () => {
  const {svc, host} = setup();
  const r = await svc.readingStart({title: '行测·资料分析 第3讲', ref: 'https://example.com/lesson3'});
  assert.equal(r.reading.launch.status, 'accepted');
  assert.equal(r.reading.thread.backend, 'cognitive_core');
  assert.ok(!r.reading.thread.error, r.reading.thread.error);
  const session = host.cc.read().core.session;
  assert.equal(session.status, 'ACTIVE');
  assert.equal(session.activity, 'watching');
  assert.equal(session.materials[0].title, '行测·资料分析 第3讲');
  assert.equal(session.return_point.ref, 'https://example.com/lesson3');
  assert.ok(host.calls.some(c => c.fn === 'startApp' && c.pkg === 'com.google.android.apps.bard'));
});

test('reading_finish writes the user’s takeaway as a USER answer on the current question', async () => {
  const {svc, host, advance} = setup();
  await svc.readingStart({title: '行测·资料分析 第3讲'});
  advance(40 * MIN);
  const r = await svc.readingFinish({takeaway: '增长率题先找基期，再看是否要估算', next_step: '明天做10道真题'});
  assert.equal(r.thread.saved, 'thread');
  const session = host.cc.read().core.session;
  assert.equal(session.cognition.length, 1);
  assert.equal(session.cognition[0].source, 'USER');
  assert.match(session.cognition[0].text, /基期/);
});

test('existing ACTIVE thread is reused: second reading attaches, not starts', async () => {
  const {svc, host, advance} = setup();
  await svc.readingStart({title: '第一份材料'});
  advance(MIN);
  await svc.readingStart({title: '第二份材料'});
  const session = host.cc.read().core.session;
  assert.equal(session.materials.length, 2);
  assert.equal(host.cc.read().core.history.length, 0);
});

test('PAUSED thread is never auto-resumed; takeaway is kept locally instead of lost', async () => {
  const {svc, host} = setup();
  await svc.readingStart({title: '材料'});
  const sid = host.cc.read().core.session.id;
  host.cc.event({type: 'pause', event_id: 'p1', session_id: sid});
  const r = await svc.threadCapture({text: '和GPT聊出来：先列条件再推结论', source: 'GPT'});
  assert.equal(r.saved, 'local');
  assert.equal(host.cc.read().core.session.status, 'PAUSED');
  assert.equal((await svc.status()).summary.pending_takeaways, 1);
});

test('without cognitive_core installed everything still works and says so', async () => {
  const {svc} = setup({withThread: false});
  const r = await svc.readingStart({title: '材料'});
  assert.equal(r.reading.thread.backend, 'missing');
  const c = await svc.threadCapture({text: '下完一盘棋：中局太早兑子'});
  assert.equal(c.saved, 'local');
});

test('gate with no executor route: key is recorded as unbound and the note says limits are unchanged', async () => {
  const {svc} = setup();
  const o = await svc.gateOpen({app: 'tv.danmaku.bili'});
  const r = await svc.gateSubmit({gate_id: o.gate.id, answers: ['看一个up主的新视频', '十分钟', '回来写报告第二段']});
  assert.equal(r.status, 'granted');
  assert.equal(r.key.status, 'unbound');
  assert.match(r.note, /没绑定/);
});

test('gate with bound routes: unlock on grant, relock and reminder at expiry via tick', async () => {
  const seen = [];
  const routes = {'lockq:enqueue': p => { seen.push(p); return {success: true, queued: p.action}; }};
  const {svc, host, advance} = setup({routes});
  await svc.configure({routes: {unlock: {tool: 'lockq:enqueue', params: {queue: 'events/lock_queue.txt'}}, lock: {tool: 'lockq:enqueue', params: {queue: 'events/lock_queue.txt'}}}});
  const o = await svc.gateOpen({app: 'tv.danmaku.bili'});
  const r = await svc.gateSubmit({gate_id: o.gate.id, answers: ['看一个up主的新视频', '5分钟', '回来写报告第二段']});
  assert.equal(r.key.status, 'active');
  assert.deepEqual(seen[0], {queue: 'events/lock_queue.txt', package: 'tv.danmaku.bili', action: 'unlock', minutes: 5, key_id: r.key.id, reason: '对话门放行'});
  advance(6 * MIN);
  const t = await svc.gateTick();
  assert.equal(t.closed_keys.length, 1);
  assert.equal(seen[1].action, 'lock');
  const note = host.calls.find(c => c.fn === 'notify');
  assert.match(note.text, /回来先做：回来写报告第二段/);
  assert.equal((await svc.gateTick()).closed_keys.length, 0, 'second tick must not relock again');
});

test('failed unlock route is reported as unknown, not as success', async () => {
  const routes = {'lockq:enqueue': () => ({success: false, message: 'Shizuku binder is null'})};
  const {svc} = setup({routes});
  await svc.configure({routes: {unlock: {tool: 'lockq:enqueue', params: {}}}});
  const o = await svc.gateOpen({app: 'tv.danmaku.bili'});
  const r = await svc.gateSubmit({gate_id: o.gate.id, answers: ['看一个up主的新视频', '十分钟', '回来写报告第二段']});
  assert.equal(r.key.status, 'unknown');
  assert.equal(r.executor.status, 'failed');
});

test('speak via 小满 chat sends a 【可用事实】 message instead of a notification', async () => {
  const {svc, host} = setup();
  await svc.configure({speak: {mode: 'chat', chat_id: '52a18815-xiaoman'}});
  const r = await svc.readingInvite();
  assert.equal(r.spoke.via, 'chat');
  const m = host.calls.find(c => c.fn === 'chatSend');
  assert.equal(m.chatId, '52a18815-xiaoman');
  assert.match(m.message, /^【可用事实】到了约定的伴读时间/);
  assert.ok(!host.calls.some(c => c.fn === 'notify'));
});

test('invite is skipped while reading; nudge only when a reading is unfinished today', async () => {
  const {svc, advance} = setup();
  assert.equal((await svc.threadNudge()).skipped, 'nothing_open');
  await svc.readingStart({title: '材料'});
  assert.equal((await svc.readingInvite()).skipped, 'reading_in_progress');
  advance(MIN);
  assert.equal((await svc.threadNudge()).spoke.status, 'accepted');
  await svc.readingFinish({takeaway: '自己的一句收获'});
  assert.equal((await svc.threadNudge()).skipped, 'nothing_open');
});

test('gate_voice briefs the gate keeper chat and starts voice; unbound chat falls back to panel', async () => {
  const a = setup();
  const r1 = await a.svc.gateVoice({app: 'tv.danmaku.bili'});
  assert.equal(r1.voice, 'unbound');
  const b = setup();
  await b.svc.configure({gate_chat_id: 'gatekeeper-chat'});
  const r2 = await b.svc.gateVoice({app: 'tv.danmaku.bili'});
  assert.equal(r2.briefing, 'accepted');
  const brief = b.host.calls.find(c => c.fn === 'chatSend');
  assert.match(brief.message, /gate_id=gate:/);
  assert.ok(b.host.calls.some(c => c.fn === 'startVoice' && c.chatId === 'gatekeeper-chat'));
});

test('install_workflows creates disabled schedule workflows once, then updates them', async () => {
  const {svc, host} = setup();
  const r = await svc.installWorkflows({});
  assert.equal(r.workflows.length, 4);
  assert.ok(r.workflows.every(w => w.action === 'created' && w.enabled === false));
  const create = host.calls.filter(c => c.fn === 'workflowCreate');
  const tick = create.find(c => c.name === '主控·钥匙巡检');
  assert.deepEqual(tick.nodes[0].triggerConfig, {schedule_type: 'interval', interval_ms: '900000', repeat: 'true', enabled: 'true'});
  assert.equal(tick.nodes[1].actionType, 'zhukong:gate_tick');
  const invite = create.find(c => c.name === '主控·伴读邀请 09:30');
  assert.equal(invite.nodes[0].triggerConfig.cron_expression, '30 9 * * *');
  const again = await svc.installWorkflows({enable: true});
  assert.ok(again.workflows.every(w => w.action === 'updated' && w.enabled === true));
});

// cognitive_core 0.3.0 起有 control_plane.task_binding；模拟这种状态。
function withBinding(host, purpose) {
  const orig = host.callTool.bind(host);
  host.callTool = async (name, params) => {
    const r = await orig(name, params);
    if (name === 'cognitive_core:status' && r.success) {
      const s = r.data.state.core.session;
      r.data.state.control_plane = {revision: 1, policy: {task_binding: s ? {session_id: s.id, purpose} : null}};
    }
    return r;
  };
}

test('0.3.0 managed thread: machine_test session is never written to', async () => {
  const {svc, host} = setup();
  host.cc.event({type: 'start', event_id: 'mt', session_id: 'machine', title: '安装验收', object: '安装验收', goal: '施工'});
  withBinding(host, 'machine_test');
  const r = await svc.readingStart({title: '真实材料'});
  assert.match(r.reading.thread.note, /认知主控台/);
  assert.equal(host.cc.read().core.session.materials.length, 0);
  const c = await svc.threadCapture({text: '和GPT聊出来的一句'});
  assert.equal(c.saved, 'local');
  assert.equal(host.cc.read().core.session.cognition.length, 0);
});

test('0.3.0 managed thread: no session means no raw start; user must pick the task in 认知主控台', async () => {
  const {svc, host} = setup();
  withBinding(host, 'real_task');
  const r = await svc.readingStart({title: '真实材料'});
  assert.match(r.reading.thread.note, /认知主控台/);
  assert.equal(host.cc.read().core.session, null);
});

test('0.3.0 managed thread: bound real task receives material and takeaway', async () => {
  const {svc, host} = setup();
  host.cc.event({type: 'start', event_id: 'rt', session_id: 'real:1', title: '资料分析', object: '资料分析', goal: '弄懂增长率'});
  withBinding(host, 'real_task');
  await svc.readingStart({title: '第3讲'});
  assert.equal(host.cc.read().core.session.materials.length, 1);
  const r = await svc.readingFinish({takeaway: '先找基期再估算'});
  assert.equal(r.thread.saved, 'thread');
});
