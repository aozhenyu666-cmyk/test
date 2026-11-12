import { test } from 'node:test'
import assert from 'node:assert/strict'

import { buildWallSnapshot, escalateRows } from '../lib/wall_panel.js'

// 2026-03-10 15:00 +08
const NOW = Date.parse('2026-03-10T07:00:00Z')
const TZ = 'Asia/Shanghai'
const H = (n) => String(n).padStart(2, '0')
const at = (h, m = 0) => Date.parse(`2026-03-10T${H(h)}:${H(m)}:00+08:00`)

const locked = (until) => ({
  state: 'sprint',
  agenda: '冲刺：少打扰。',
  funLocked: true,
  layers: [{ kind: 'sprint', priority: 70, funLocked: true, until, why: '冲刺中：娱乐锁着，通知收进防波堤。' }],
})

test('锁着的时候写清锁到几点和为什么', () => {
  const s = buildWallSnapshot({ now: NOW, tz: TZ, state: locked(at(15, 25)) })
  assert.equal(s.lock.locked, true)
  assert.equal(s.lock.headline, '娱乐锁着')
  assert.equal(s.lock.until, '15:25')
  assert.ok(s.lock.caption.includes('15:25'), s.lock.caption)
  assert.ok(s.lock.caption.includes('冲刺中'), s.lock.caption)
  assert.equal(s.lock.tone, 'bad')
})

test('放开的时候不编一个到期时刻', () => {
  const s = buildWallSnapshot({ now: NOW, tz: TZ, state: { state: 'break', funLocked: false, layers: [] } })
  assert.equal(s.lock.locked, false)
  assert.equal(s.lock.headline, '娱乐开着')
  assert.equal(s.lock.until, '')
  assert.equal(s.lock.tone, 'good')
})

test('锁已经过点就不写「锁到」——那会看着像还在锁', () => {
  const s = buildWallSnapshot({ now: NOW, tz: TZ, state: locked(at(14, 0)) })
  assert.equal(s.lock.until, '')
  assert.ok(s.lock.caption.startsWith('锁到今天结束'), s.lock.caption)
})

test('主状态有中文名；认不出来的原样写，不吞掉', () => {
  assert.equal(buildWallSnapshot({ now: NOW, tz: TZ, state: { state: 'plan' } }).state.label, '计划')
  assert.equal(buildWallSnapshot({ now: NOW, tz: TZ, state: { state: 'sprint' } }).state.label, '冲刺')
  assert.equal(buildWallSnapshot({ now: NOW, tz: TZ, state: { state: 'weird' } }).state.label, 'weird')
  assert.equal(buildWallSnapshot({ now: NOW, tz: TZ }).state.label, '不知道')
})

test('并存的锁层逐条列出，最高的那条是 lock', () => {
  const s = buildWallSnapshot({
    now: NOW, tz: TZ,
    state: {
      state: 'sprint', funLocked: true,
      layers: [
        { kind: 'followup', priority: 80, until: at(15, 30), why: '追问锁：把这一个问题说清楚。' },
        { kind: 'sprint', priority: 70, until: at(15, 25), why: '冲刺中：娱乐锁着。' },
      ],
    },
  })
  assert.equal(s.layers.length, 2)
  assert.ok(s.layers[0].startsWith('追问锁'), s.layers[0])
  assert.ok(s.layers[0].includes('15:30'))
  assert.ok(s.layers[1].startsWith('冲刺锁'))
  assert.ok(s.layersNote.includes('2 层'))
})

test('没有锁层时写「没有锁层」，不是空白', () => {
  const s = buildWallSnapshot({ now: NOW, tz: TZ, state: { state: 'meal', funLocked: false, layers: [] } })
  assert.deepEqual(s.layers, [])
  assert.equal(s.layersNote, '现在没有锁层。')
})

test('今天的局：开过/空局/还差分开数', () => {
  const s = buildWallSnapshot({
    now: NOW, tz: TZ, state: locked(at(16)),
    plan: {
      accepted: true,
      items: [
        { protocolId: 'study', title: '学习', minute: 540, openedAt: at(9) },
        { protocolId: 'execute', title: '执行', minute: 630, missed: '过点 30 分钟，记空局' },
        { protocolId: 'rest', title: '休息', minute: 900 },
      ],
    },
  })
  assert.equal(s.plan.headline, '今天 3 局：开过 1，空 1，还差 1')
  assert.ok(s.plan.rows[0].startsWith('9:00 学习 · 开过了'), s.plan.rows[0])
  assert.ok(s.plan.rows[1].includes('空局'), s.plan.rows[1])
  assert.equal(s.plan.accepted, true)
  assert.ok(s.plan.note.includes('15:00'), s.plan.note)
})

test('还没有计划时不假装有', () => {
  const s = buildWallSnapshot({ now: NOW, tz: TZ })
  assert.equal(s.plan.headline, '今天的局还没定')
  assert.deepEqual(s.plan.rows, [])
  assert.ok(s.plan.note.includes('不许临时拼一局'), s.plan.note)
})

test('升级记录只收当天的，最新在最上面', () => {
  const rows = escalateRows([
    { state: 'escalate', at: at(10), rule: 'E1', action: '审讯舱', why: '30 分钟内敷衍 3 次' },
    { state: 'escalate', at: at(14), rule: 'L1', action: '锁' },
    { state: 'escalate', at: at(2), rule: 'E2' },
    { state: 'verdict', at: at(13), rule: 'E9' },
  ], { todayStart: at(3), tz: TZ })
  assert.equal(rows.length, 2, '昨天的不算，别的账也不混进来')
  assert.ok(rows[0].includes('L1'), rows[0])
  assert.ok(rows[1].includes('E1'))
  assert.ok(rows[1].includes('审讯舱'))
})

test('今天没升级就写「今天没有升级」，不写 0 分', () => {
  const s = buildWallSnapshot({ now: NOW, tz: TZ, entries: [] })
  assert.deepEqual(s.escalate.rows, [])
  assert.equal(s.escalate.note, '今天没有升级。')
})

test('审查记录来自 wall_voice 模板，不在这里另编句子', () => {
  const s = buildWallSnapshot({
    now: NOW, tz: TZ,
    entries: [{ state: 'verdict', at: at(13), satisfied: true, summary: '过。文件涨了 900 字节。' }],
  })
  assert.equal(s.audit.rows.length, 1)
  assert.ok(s.audit.rows[0].includes('墙：'), s.audit.rows[0])
  assert.ok(s.audit.rows[0].includes('文件涨了 900 字节'))
  assert.ok(s.audit.note.includes('1 条'))
})

test('半个月前的不进审查记录', () => {
  const s = buildWallSnapshot({
    now: NOW, tz: TZ,
    entries: [{ state: 'verdict', at: NOW - 20 * 86_400_000, summary: '过。' }],
  })
  assert.deepEqual(s.audit.rows, [])
  assert.equal(s.audit.note, '这半个月还没有审查记录。')
})

test('阈值原样来自设定，不在面板里另写一套数', () => {
  const s = buildWallSnapshot({
    now: NOW, tz: TZ,
    settings: { minBouts: 4, minThinking: 1, escalate: { e1Count: 3, e1WindowMs: 30 * 60_000, l1WorkMin: 15, l1RestMin: 30, l3Min: 60 } },
  })
  assert.ok(s.rules.rows.some((r) => r.includes('30 分钟内敷衍 3 次')), s.rules.rows.join(' / '))
  assert.ok(s.rules.rows.some((r) => r.includes('娱乐连续 15 分钟')))
  assert.ok(s.rules.rows.some((r) => r.includes('60 分钟')))
  assert.ok(s.rules.rows.some((r) => r.includes('每天最少 4 局')))
})

test('没有设定就写「还没读到阈值」，不编默认值', () => {
  const s = buildWallSnapshot({ now: NOW, tz: TZ })
  assert.deepEqual(s.rules.rows, [])
  assert.equal(s.rules.note, '还没读到阈值。')
})

test('坏输入不炸', () => {
  for (const bad of [undefined, null, {}, { state: 'x' }, { state: {}, entries: 'no' }, { state: { layers: 'no' } }]) {
    const s = buildWallSnapshot(bad)
    assert.equal(typeof s.lock.headline, 'string')
    assert.ok(Array.isArray(s.plan.rows))
    assert.ok(Array.isArray(s.audit.rows))
  }
  assert.deepEqual(escalateRows(null, {}), [])
  assert.deepEqual(escalateRows([null, 1, 'x'], {}), [])
})
