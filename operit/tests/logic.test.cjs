'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const L = require('../toolpkg/lib/logic.js');

const T0 = Date.UTC(2026, 9, 5, 2, 0, 0); // 北京 10:00
const MIN = 60000;

test('defaults follow existing unlock policy (2 keys/day, 10 min, max 15)', () => {
  const c = L.defaultConfig();
  assert.equal(c.gate.daily_key_limit, 2);
  assert.equal(c.gate.key_default_minutes, 10);
  assert.equal(c.gate.key_max_minutes, 15);
  assert.equal(c.routes.unlock, null);
});

test('answers must be specific: empty, perfunctory, short and duplicate answers rejected', () => {
  const c = L.defaultConfig();
  assert.equal(L.judgeAnswer(c, '', []).valid, false);
  assert.equal(L.judgeAnswer(c, '随便。', []).valid, false);
  assert.equal(L.judgeAnswer(c, '看看', []).valid, false);
  assert.equal(L.judgeAnswer(c, '刷一下', []).valid, false); // 3 字，不够 4 字
  assert.equal(L.judgeAnswer(c, '看一个up主更新的视频', []).valid, true);
  assert.equal(L.judgeAnswer(c, '看一个up主更新的视频', [{text: '看一个up主更新的视频'}]).valid, false);
});

test('gate flow: open → answer all → granted key with user-chosen shorter minutes', () => {
  const s = L.freshState();
  const o = L.gateOpen(s, T0, 'tv.danmaku.bili');
  assert.equal(o.status, 'asking');
  const r = L.gateSubmit(s, T0 + MIN, o.gate.id, ['看一个up主的新视频', '大概5分钟就够', '回来把第三章笔记写完']);
  assert.equal(r.status, 'granted');
  assert.equal(r.key.minutes, 5);
  assert.equal(r.key.back_to, '回来把第三章笔记写完');
  assert.equal(L.summary(s, T0 + MIN).keys_left_today, 1);
});

test('user asking for longer than max is clamped to max', () => {
  const s = L.freshState();
  const o = L.gateOpen(s, T0, 'tv.danmaku.bili');
  ['看比赛的直播回放内容', '一个小时左右吧', '回来接着做那套题目'].forEach((a, i) => L.gateAnswer(s, T0, o.gate.id, i, a));
  const r = L.gateDecide(s, T0, o.gate.id, 'grant', 60, 'model');
  assert.equal(r.key.minutes, 15);
});

test('perfunctory answers keep the gate open and report problems', () => {
  const s = L.freshState();
  const o = L.gateOpen(s, T0, 'com.ss.android.ugc.aweme');
  const r = L.gateSubmit(s, T0, o.gate.id, ['随便', '不知道', '']);
  assert.equal(r.status, 'retry');
  assert.equal(r.problems.length, 3);
  assert.throws(() => L.gateDecide(s, T0, o.gate.id, 'grant', null, 'model'), /认真回答/);
});

test('unanswered gate expires and cooldown escalates 5 → 10 → 20 minutes', () => {
  const s = L.freshState();
  let t = T0;
  const g1 = L.gateOpen(s, t, 'tv.danmaku.bili');
  t += 4 * MIN; // 超过 3 分钟
  const e1 = L.expireGates(s, t);
  assert.equal(e1[0].cooldown_min, 5);
  assert.equal(L.gateOpen(s, t + MIN, 'tv.danmaku.bili').status, 'cooldown');
  t += 6 * MIN;
  const g2 = L.gateOpen(s, t, 'tv.danmaku.bili');
  assert.equal(g2.status, 'asking');
  const d = L.gateDecide(s, t, g2.gate.id, 'deny', null, 'model', '说不清要干什么');
  assert.equal(d.cooldown_min, 10);
  t += 11 * MIN;
  L.gateOpen(s, t, 'tv.danmaku.bili');
  t += 4 * MIN;
  assert.equal(L.expireGates(s, t)[0].cooldown_min, 20);
  assert.ok(g1.gate.id !== g2.gate.id);
});

test('daily limit stops new gates; resets next Beijing day', () => {
  const s = L.freshState();
  for (let i = 0; i < 2; i++) {
    const o = L.gateOpen(s, T0 + i * 30 * MIN, 'com.baidu.tieba');
    L.gateSubmit(s, T0 + i * 30 * MIN, o.gate.id, ['回一个帖子的消息' + i, '十分钟以内结束', '回来继续背单词' + i]);
  }
  assert.equal(L.gateOpen(s, T0 + 70 * MIN, 'com.xingin.xhs').status, 'limit');
  const nextDay = Date.UTC(2026, 9, 6, 1, 45); // 北京 10-06 09:45，处于上午专注时段
  assert.equal(L.gateOpen(s, nextDay, 'com.xingin.xhs').status, 'asking');
});

test('active key short-circuits a new gate; due keys are reported after expiry', () => {
  const s = L.freshState();
  const o = L.gateOpen(s, T0, 'tv.danmaku.bili');
  const r = L.gateSubmit(s, T0, o.gate.id, ['看一个up主的新视频', '十分钟', '回来写报告第二段']);
  L.setKeyStatus(s, r.key.id, 'active');
  assert.equal(L.gateOpen(s, T0 + MIN, 'tv.danmaku.bili').status, 'key_active');
  assert.equal(L.dueKeys(s, T0 + 9 * MIN).length, 0);
  assert.equal(L.dueKeys(s, T0 + 11 * MIN).length, 1);
});

test('apps outside the heavy list need no key', () => {
  const s = L.freshState();
  assert.equal(L.gateOpen(s, T0, 'com.google.android.apps.bard').status, 'not_guarded');
});

test('config validation: Operit cannot be listed; loosening limits is explicit', () => {
  const c = L.defaultConfig();
  assert.throws(() => L.mergeConfig(c, {heavy_apps: [{package: 'com.ai.assistance.operit', name: 'Operit'}]}), /UP-08/);
  assert.throws(() => L.mergeConfig(c, {gate: {key_default_minutes: 40}}), /钥匙时长/);
  assert.throws(() => L.mergeConfig(c, {foo: 1}), /不支持/);
  const n = L.mergeConfig(c, {gate: {key_default_minutes: 15, key_max_minutes: 30, daily_key_limit: 4}});
  assert.equal(n.gate.daily_key_limit, 4);
  assert.equal(c.gate.daily_key_limit, 2);
});

test('reading start/finish requires the user’s own sentence', () => {
  const s = L.freshState();
  L.readingStart(s, T0, {title: '行测·资料分析 第3讲', ref: 'https://example.com/v'});
  assert.throws(() => L.readingFinish(s, T0, '  '), /自己的一句话/);
  const r = L.readingFinish(s, T0 + 30 * MIN, '增长率要先看基期', '明天做10道题');
  assert.equal(r.status, 'finished');
  assert.equal(r.ref_kind, 'web');
});

test('durations parse in digits and Chinese; short duration answers are valid', () => {
  assert.equal(L.parseMinutes('5分钟'), 5);
  assert.equal(L.parseMinutes('十分钟'), 10);
  assert.equal(L.parseMinutes('二十分钟左右'), 20);
  assert.equal(L.parseMinutes('十五分'), 15);
  assert.equal(L.parseMinutes('半小时'), 30);
  assert.equal(L.parseMinutes('一个小时'), 60);
  assert.equal(L.parseMinutes('一会儿'), null);
  assert.equal(L.judgeAnswer(L.defaultConfig(), '十分钟', []).valid, true);
});

test('focus windows: keys needed only inside 09:30-11:30 / 14:30-16:30 / 20:00-22:00 (Beijing)', () => {
  const s = L.freshState();
  const at = (h, m) => Date.UTC(2026, 9, 5, h - 8, m);
  assert.equal(L.focusWindow(at(10, 0), s.config).active, true);
  assert.equal(L.focusWindow(at(11, 30), s.config).active, false);
  assert.equal(L.focusWindow(at(12, 0), s.config).next, '14:30');
  const late = L.focusWindow(at(22, 30), s.config);
  assert.equal(late.next, '09:30');
  assert.equal(late.next_is_tomorrow, true);
  const o = L.gateOpen(s, at(13, 0), 'tv.danmaku.bili');
  assert.equal(o.status, 'not_in_window');
  assert.equal(o.next, '14:30');
  assert.equal(L.gateOpen(s, at(14, 31), 'tv.danmaku.bili').status, 'asking');
  assert.throws(() => L.mergeConfig(s.config, {focus_windows: [{start: '12:00', end: '11:00'}]}), /开始早于结束/);
});
