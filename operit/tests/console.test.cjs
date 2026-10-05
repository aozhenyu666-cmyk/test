'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {setup} = require('./helpers.cjs');

// 模拟 cognitive_continuity 0.3：status 带 control_plane；可选 coach / begin_task 工具。
function managed(host, {coach, begin} = {}) {
  const orig = host.callTool.bind(host);
  host.callTool = async (name, params) => {
    if (name === 'control_plane:coach' && coach) { host.calls.push({fn: 'callTool', name, params}); return coach(params); }
    if (name === 'cognitive_core:begin_task' && begin) { host.calls.push({fn: 'callTool', name, params}); return begin(params); }
    const r = await orig(name, params);
    if (name === 'cognitive_core:status' && r.success) {
      const s = r.data.state.core.session;
      r.data.state.control_plane = {revision: 3, policy: {task_binding: s ? {session_id: s.id, purpose: 'real_task'} : null}};
    }
    return r;
  };
}

test('console state on 0.2 backend: start a task, answer it, pause and resume', async () => {
  const {svc, host} = setup();
  let st = await svc.consoleState();
  assert.equal(st.task, null);
  assert.equal(st.focus.active, true);
  await svc.startTask({object: '把资料分析第3讲弄懂', material_text: '增长率=（现期-基期）/基期'});
  st = await svc.consoleState();
  assert.equal(st.task.title, '把资料分析第3讲弄懂');
  assert.ok(st.task.question);
  const r = await svc.answer({text: '增长率要先找基期，再看现期'});
  assert.equal(r.saved, true);
  assert.equal(r.coach.status, 'unavailable');
  assert.equal(host.cc.read().core.session.cognition[0].text, '增长率要先找基期，再看现期');
  await svc.pause();
  await assert.rejects(() => svc.answer({text: '暂停时的回答'}), /我回来了/);
  await svc.resume();
  assert.equal(host.cc.read().core.session.status, 'ACTIVE');
});

test('0.3 backend: answer saves the user’s words, then asks the backend model to continue', async () => {
  const {svc, host} = setup();
  host.cc.event({type: 'start', event_id: 'rt', session_id: 'real:1', title: '资料分析', object: '资料分析', goal: 'g'});
  managed(host, {coach: p => ({success: true, data: {status: 'saved', id: p.event_id}})});
  const r = await svc.answer({text: '先找基期'});
  assert.equal(r.coach.status, 'saved');
  const call = host.calls.find(c => c.name === 'control_plane:coach');
  assert.equal(call.params.expected_control_revision, 3);
  assert.equal(call.params.expected_core_revision, host.cc.read().core.revision);
  assert.match(call.params.event_id, /^zk-coach:real:1:q:2$/);
});

test('0.3 backend: a refused retry is explained in plain words', async () => {
  const {svc, host} = setup();
  host.cc.event({type: 'start', event_id: 'rt', session_id: 'real:1', title: 't', object: 'o', goal: 'g'});
  managed(host, {coach: () => ({success: false, message: 'x', data: {code: 'COACH_ALREADY_ATTEMPTED'}})});
  const r = await svc.askAgain();
  assert.equal(r.status, 'failed');
  assert.match(r.note, /已经试过一次/);
});

test('0.3 backend without begin_task: start falls back to the backend console', async () => {
  const {svc, host} = setup();
  managed(host);
  const r = await svc.startTask({object: '读一篇文章'});
  assert.equal(r.status, 'use_backend_console');
  assert.equal(host.cc.read().core.session, null);
});

test('0.3 backend with begin_task: start uses it and asks the model', async () => {
  const {svc, host} = setup();
  managed(host, {
    begin: p => { host.cc.event({type: 'start', event_id: 'b', session_id: 'real:2', title: p.object, object: p.object, goal: 'g'}); return {success: true, data: {session_id: 'real:2'}}; },
    coach: () => ({success: true, data: {status: 'saved'}})
  });
  const r = await svc.startTask({object: '读一篇文章', material_text: '正文'});
  assert.equal(r.status, 'started');
  assert.equal(r.coach.status, 'saved');
});

test('dashboard reads today’s usage and digest; missing files are reported, not fatal', async () => {
  const {svc, host} = setup();
  host.files['/sdcard/Download/Operit/p2/usage_last.json'] = JSON.stringify({success: true, data: {apps: [
    {package: 'tv.danmaku.bili', minutes: 37.5}, {package: 'com.openai.chatgpt', minutes: 31.3}, {package: 'com.ss.android.ugc.aweme', minutes: 28}]}});
  const d = await svc.dashboard();
  assert.equal(d.usage.heavy_minutes, 66);
  assert.deepEqual(d.usage.top[0], {name: 'B站', minutes: 38, heavy: true});
  assert.equal(d.digest, null);
  assert.match(d.errors[0], /日报/);
  host.files['/sdcard/Download/Operit/events/20261005/digest.txt'] = 'line1\n\nline2';
  assert.deepEqual((await svc.dashboard()).digest, ['line1', 'line2']);
});

test('switch chat: by configured id, by title, and a clear error when missing', async () => {
  const {svc, host} = setup();
  assert.equal((await svc.switchChat({target: 'companion'})).chat_id, '52a18815-xiaoman');
  assert.equal((await svc.switchChat({target: 'core'})).chat_id, 'core-chat-1');
  await svc.configure({core_chat_id: 'explicit-core'});
  assert.equal((await svc.switchChat({target: 'core'})).chat_id, 'explicit-core');
  host.chats = [];
  await assert.rejects(() => svc.switchChat({target: 'companion'}), /没找到小满/);
  const v = await (setup().svc).voice({target: 'companion'});
  assert.equal(v.voice, 'accepted');
});

test('outside focus windows no key is needed', async () => {
  const {svc} = setup({start: Date.UTC(2026, 9, 5, 5, 0)}); // 北京 13:00
  const r = await svc.gateOpen({app: 'tv.danmaku.bili'});
  assert.equal(r.status, 'not_in_window');
  assert.equal(r.next, '14:30');
});
