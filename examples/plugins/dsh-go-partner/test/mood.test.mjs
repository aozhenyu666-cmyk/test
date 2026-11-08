import assert from 'node:assert/strict'
import path from 'node:path'
import { test } from 'node:test'

import { tempHome, seedAll, moodConfig, moodState, writeJson, NOW } from './fixtures.mjs'
import {
  DIM_LABEL,
  DIMS,
  decayed,
  dims,
  moodBand,
  moodContextText,
  pickSlot,
  readMood,
  timeline,
} from '../lib/mood.mjs'
import { loadConfig } from '../lib/config.mjs'

test('七维用的是上游真名（happy/calm/sweet/heartache/worry/sad/jealous）', () => {
  assert.deepEqual(DIMS.slice(), ['happy', 'calm', 'sweet', 'heartache', 'worry', 'sad', 'jealous'])
  assert.equal(DIM_LABEL.heartache, '心疼')
  assert.equal(DIM_LABEL.calm, '安稳')
  assert.ok(!('tender' in DIM_LABEL), 'tender 是旧版臆造的键，上游从来不发')
  assert.ok(!('steady' in DIM_LABEL), 'steady 同理')
})

test('七维读出来是真实数值：heartache=7 必须看得见（上一版这里恒为 0）', () => {
  const d = dims({ dims: { happy: 0.6, calm: 0.3, sweet: 0.2, heartache: 7, worry: 5, sad: 4, jealous: 0.5 } })
  const byKey = Object.fromEntries(d.map((x) => [x.key, x.value]))
  assert.equal(byKey.heartache, 7)
  assert.equal(byKey.worry, 5)
  assert.equal(byKey.sad, 4)
  assert.equal(d.length, 7, '七根一根都不许少')
})

test('五档阈值', () => {
  assert.equal(moodBand(85).label, '欢喜')
  assert.equal(moodBand(70).label, '开心')
  assert.equal(moodBand(50).label, '一般')
  assert.equal(moodBand(30).label, '心疼')
  assert.equal(moodBand(10).label, '难过')
  assert.equal(moodBand('x').label, '不知道')
})

test('衰减：从 lastDecayAt 起按 decayHalfHours 向基线回落，只算不写', () => {
  const m = { mood: 90, lastDecayAt: NOW - 12 * 3600000 }
  const d = decayed(m, NOW, 12)
  assert.equal(d.applied, true)
  assert.ok(Math.abs(d.value - 80) < 0.1, '90 与基线 70 差 20，一个半衰期后差 10 → 80，实际 ' + d.value)
  assert.equal(d.from, 90, '原始值必须保留，不能就地改掉')
})

test('衰减：没到点就不动', () => {
  const d = decayed({ mood: 90, lastDecayAt: NOW }, NOW, 12)
  assert.equal(d.applied, false)
  assert.equal(d.value, 90)
})

test('衰减：缺时间戳时如实说算不了，不假装', () => {
  const d = decayed({ mood: 90 }, NOW, 12)
  assert.equal(d.applied, false)
  assert.equal(d.value, 90)
})

test('挑份：会话绑了对就挑对的那一份', () => {
  const cfg = moodConfig({ slots: [
    { id: 'a', label: 'A', selfName: '甲', ownerName: '一', sessions: ['session-other'] },
    { id: 'b', label: 'B', selfName: '乙', ownerName: '二', sessions: ['session-s1'] },
  ] })
  const state = { moods: { a: { mood: 10 }, b: { mood: 90 } } }
  const r = pickSlot(cfg, state, 'session-s1')
  assert.equal(r.ok, true)
  assert.equal(r.slot.id, 'b')
  assert.equal(r.mood.mood, 90)
  assert.equal(r.matched, true)
})

test('挑份：绑定的份在 state 里不存在、且只有一份记录 → 显示它但标 matched=false', () => {
  const cfg = moodConfig({ slots: [{ id: '__default__', sessions: ['session-s1'] }] })
  const state = { moods: { '4d6beaf3-1111': { mood: 60 } } }
  const r = pickSlot(cfg, state, 'session-s1')
  assert.equal(r.ok, true)
  assert.equal(r.matched, false, '必须让上层知道「这不是你绑的那份」')
  assert.equal(r.mood.mood, 60)
})

test('挑份：多份记录且都对不上 → 明确报 slot-mismatch，不挑一个凑数', () => {
  const cfg = moodConfig({ slots: [{ id: '__default__', sessions: ['session-s1'] }] })
  const state = { moods: { x: { mood: 60 }, y: { mood: 40 } } }
  const r = pickSlot(cfg, state, 'session-s1')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'slot-mismatch')
  assert.equal(r.keys.length, 2, '把真实存在的键交出去，供界面显示')
})

test('readMood：完全没装 feiyu-mood 时是 no-feiyu-mood', () => {
  tempHome()
  const r = readMood('s1')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'no-feiyu-mood')
})

test('readMood：装了但没判过是 no-state', () => {
  const home = tempHome()
  writeJson(path.join(home, 'feiyu-mood', 'config.json'), moodConfig())
  const r = readMood('s1')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'no-state')
})

test('readMood：完整读通，名字/七维/时间线/配置都在', () => {
  const home = tempHome()
  seedAll(home)
  const r = readMood('session-s1')
  assert.equal(r.ok, true)
  assert.equal(r.names.selfName, '大肥鱼')
  assert.equal(r.names.ownerName, '鱼片')
  assert.equal(r.dims.length, 7)
  assert.ok(r.timeline.length >= 2)
  assert.equal(r.cfg.decayHalfHours, 12)
  assert.ok(r.current.value > 0)
})

test('时间线：最近的在前，字段照上游（at/mood/delta/reason/conf）', () => {
  const tl = timeline(moodState().moods.__default__, 8)
  assert.ok(tl.length >= 2)
  assert.ok(tl[0].at > tl[1].at, '最新的必须在最前')
  assert.equal(tl[0].delta, -2)
  assert.match(tl[0].reason, /领导/)
  assert.equal(tl[0].conf, 88)
})

test('时间线：上限生效，且不修改源数组', () => {
  const m = moodState().moods.__default__
  const before = m.timeline.length
  const tl = timeline(m, 1)
  assert.equal(tl.length, 1)
  assert.equal(m.timeline.length, before, '源数据不许被 slice/reverse 副作用改掉')
})

test('上下文注入：injectMode=off 时一个字都不给', () => {
  const home = tempHome()
  seedAll(home, { moodConfig: moodConfig({ injectMode: 'off' }) })
  const snap = readMood('session-s1')
  const cfg = loadConfig()
  assert.equal(moodContextText(snap, cfg), '')
})

test('上下文注入：可信度低于门槛时不给（上游就是这么做决定的）', () => {
  const home = tempHome()
  seedAll(home, { moodState: moodState({ lastConfidence: 30 }) })
  const snap = readMood('session-s1')
  const cfg = loadConfig()
  assert.equal(moodContextText(snap, cfg), '', 'conf 30 < 门槛 55，不该进上下文')
})

test('上下文注入：event 模式带上「刚才那件事」，并且明说不要念出来', () => {
  const home = tempHome()
  seedAll(home)
  const snap = readMood('session-s1')
  const cfg = loadConfig()
  const t = moodContextText(snap, cfg)
  assert.match(t, /这会儿的心情/)
  assert.match(t, /心疼/, 'heartache=7 应该出现在「还剩着」里')
  assert.match(t, /刚才那件事/)
  assert.match(t, /别把这段念出来/)
})

test('上下文注入：state 模式只说心情，不复述事件', () => {
  const home = tempHome()
  seedAll(home, { moodConfig: moodConfig({ injectMode: 'state' }) })
  const snap = readMood('session-s1')
  const t = moodContextText(snap, loadConfig())
  assert.match(t, /这会儿的心情/)
  assert.ok(!/刚才那件事/.test(t), 'state 模式不该带事件原文')
})

test('上下文注入：模块被关掉时立刻不给（每轮现查配置，不用重启）', () => {
  const home = tempHome()
  seedAll(home)
  const snap = readMood('session-s1')
  const cfg = loadConfig()
  cfg.mood.enabled = false
  assert.equal(moodContextText(snap, cfg), '')
})

test('readMood：没有会话 id 时也能读（取第一份）', () => {
  const home = tempHome()
  seedAll(home)
  const r = readMood()
  assert.equal(r.ok, true)
})
