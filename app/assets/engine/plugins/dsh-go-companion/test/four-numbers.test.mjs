// 第九轮第二项：四个数。主动开局比 / 空局率 / 交付率从日摘要直接出，
// 局内偏离分钟从片段 + 这一局声明的 App 算。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { emptyState, observe, noteBout, digestOf, rollDay, deviationOf } from '../lib/episodes.js'

const MIN = 60_000
const HOUR = 3_600_000
const DAY0 = Date.UTC(2026, 10, 13, 16, 0, 0)
const bj = (dayIndex, h, m = 0) => DAY0 + dayIndex * 86_400_000 + h * HOUR + m * MIN
const dayOf = (ms) => new Date(ms + 8 * HOUR).toISOString().slice(0, 10)
const minuteOf = (ms) => { const d = new Date(ms + 8 * HOUR); return d.getUTCHours() * 60 + d.getUTCMinutes() }
const hourOf = (ms) => Math.floor(minuteOf(ms) / 60)
const snap = (now, app, extra = {}) => ({ now, day: dayOf(now), minute: minuteOf(now), app, fun: false, minutes: 0, screenOn: true, bout: null, night: false, ...extra })

function feed(state, list) {
  let s = state
  for (const x of list) s = observe(s, x).state
  return s
}

// ---------------------------------------------------------------- 交付率

test('S1 开了 4 局、通过 3 局：交付率 0.75', () => {
  let s = emptyState()
  for (let i = 0; i < 4; i += 1) s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'open' })
  for (let i = 0; i < 3; i += 1) s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'done', accepted: true })
  const d = digestOf(s, '2026-11-14', { now: bj(0, 20), hourOf })
  assert.equal(d.bouts.opened, 4)
  assert.equal(d.deliverRatio, 0.75)
})

test('S2 收局但没过验收不算交付（say 收尾、缺交付物）', () => {
  let s = emptyState()
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'open' })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'done', accepted: false })
  const d = digestOf(s, '2026-11-14', { now: bj(0, 20), hourOf })
  assert.equal(d.deliverRatio, 0)
  assert.equal(d.bouts.done, 1)
  assert.equal(d.bouts.passed, 0)
})

test('S3 通过数按局型累加，不会互相覆盖', () => {
  let s = emptyState()
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'open' })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'study', outcome: 'open' })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'done', accepted: true })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'study', outcome: 'done', accepted: true })
  const d = digestOf(s, '2026-11-14', { now: bj(0, 20), hourOf })
  assert.equal(d.deliverRatio, 1)
  assert.equal(d.bouts.byProtocol.job.passed, 1)
  assert.equal(d.bouts.byProtocol.study.passed, 1)
})

test('S4 一局都没开时交付率是 null，不是 0', () => {
  const d = digestOf(emptyState(), '2026-11-14', { now: bj(0, 20), hourOf })
  assert.equal(d.deliverRatio, null)
  assert.equal(d.selfOpenRatio, null)
  assert.equal(d.badRatio, null)
})

test('S5 四个数一起出：主动开局比、空局率、交付率、偏离分钟', () => {
  let s = emptyState()
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'open', self: true })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'open', self: false })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'bad' })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'job', outcome: 'done', accepted: true })
  const d = digestOf(s, '2026-11-14', { now: bj(0, 20), hourOf, appsOf: () => ({ need: [], allow: [] }) })
  assert.equal(d.selfOpenRatio, 0.5)
  assert.equal(d.badRatio, 0.5)
  assert.equal(d.deliverRatio, 0.5)
  assert.equal(d.deviationMin, 0)
})

// ---------------------------------------------------------------- 局内偏离分钟

test('S6 局内待在允许的 App 里不算偏离', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 10), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 20), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 21), null, { bout: { id: 'B1' } }),
  ])
  const d = digestOf(s, '2026-11-14', { now: bj(0, 11), hourOf, appsOf: () => ({ need: ['com.boss'], allow: [] }) })
  assert.equal(d.deviationMin, 0)
})

test('S7 局内刷 7 分钟抖音：偏离 7 分钟，且点名是哪个 App', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 10), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 5), 'com.douyin', { bout: { id: 'B1' }, fun: true }),
    snap(bj(0, 10, 12), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 20), null, { bout: { id: 'B1' } }),
  ])
  const d = digestOf(s, '2026-11-14', { now: bj(0, 11), hourOf, appsOf: () => ({ need: ['com.boss'], allow: ['com.tencent.mm'] }) })
  assert.equal(d.deviationMin, 7)
  assert.deepEqual(d.deviationTop, [{ app: 'com.douyin', min: 7 }])
})

test('S8 允许应用里的不算偏离（微信在允许清单里）', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 10), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 5), 'com.tencent.mm', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 15), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 20), null, { bout: { id: 'B1' } }),
  ])
  const d = digestOf(s, '2026-11-14', { now: bj(0, 11), hourOf, appsOf: () => ({ need: ['com.boss'], allow: ['com.tencent.mm'] }) })
  assert.equal(d.deviationMin, 0)
})

test('S9 没声明 App 的局不参与偏离：没有允许清单就谈不上不允许', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 10), 'com.douyin', { bout: { id: 'B1' }, fun: true }),
    snap(bj(0, 10, 30), null, { bout: { id: 'B1' } }),
  ])
  const d = digestOf(s, '2026-11-14', { now: bj(0, 11), hourOf, appsOf: () => ({ need: [], allow: [] }) })
  assert.equal(d.deviationMin, 0)
})

test('S10 无局的片段不算偏离，只算局内', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 8), 'com.douyin', { fun: true }),
    snap(bj(0, 8, 30), null),
  ])
  const d = digestOf(s, '2026-11-14', { now: bj(0, 9), hourOf, appsOf: () => ({ need: ['com.boss'], allow: [] }) })
  assert.equal(d.deviationMin, 0)
})

test('S11 两个局型各自的允许清单分开判', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 10), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 10), 'com.douyin', { bout: { id: 'B1' }, fun: true }),
    snap(bj(0, 10, 15), null, { bout: { id: 'B1' } }),
    snap(bj(0, 14), 'com.douyin', { bout: { id: 'B2' }, fun: true }),
    snap(bj(0, 14, 20), null, { bout: { id: 'B2' } }),
  ])
  const apps = { B1: { need: ['com.boss'], allow: [] }, B2: { need: ['com.douyin'], allow: [] } }
  const d = digestOf(s, '2026-11-14', { now: bj(0, 15), hourOf, appsOf: (id) => apps[id] || { need: [], allow: [] } })
  assert.equal(d.deviationMin, 5)
})

test('S12 偏离分钟取整，不吐小数', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 10), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 1), 'com.douyin', { bout: { id: 'B1' }, fun: true }),
    snap(bj(0, 10, 4), null, { bout: { id: 'B1' } }),
  ])
  const d = digestOf(s, '2026-11-14', { now: bj(0, 11), hourOf, appsOf: () => ({ need: ['com.boss'], allow: [] }) })
  assert.equal(d.deviationMin, Math.round(d.deviationMin))
  assert.equal(Number.isInteger(d.deviationMin), true)
})

test('S13 一天里最早的几个偏离 App 按分钟排序，最多 5 个', () => {
  const segs = []
  const apps = ['a.one', 'b.two', 'c.three', 'd.four', 'e.five', 'f.six']
  apps.forEach((app, i) => {
    segs.push({ id: 'S' + i, day: '2026-11-14', app, fun: true, start: bj(0, 10, i * 10), end: bj(0, 10, i * 10 + (i + 1)), min: i + 1, context: '局内', cause: '', effect: '', boutId: 'B1' })
  })
  const d = deviationOf(segs, () => ({ need: ['com.boss'], allow: [] }))
  assert.equal(d.top.length, 5)
  assert.equal(d.top[0].app, 'f.six')
})

test('S14 换天定稿时偏离分钟也要算进去，不是只算实时', () => {
  let s = feed(emptyState(), [
    snap(bj(0, 10), 'com.boss', { bout: { id: 'B1' } }),
    snap(bj(0, 10, 12), 'com.douyin', { bout: { id: 'B1' }, fun: true }),
    snap(bj(0, 10, 20), null, { bout: { id: 'B1' } }),
  ])
  // 先让状态知道「昨天是哪天」（rollDay 定稿的是 lastDay 那一天），否则按第一天处理、不定稿。
  s.marks.lastDay = '2026-11-14'
  const rolled = rollDay(s, { day: dayOf(bj(1, 8)), now: bj(1, 8), hourOf, appsOf: () => ({ need: ['com.boss'], allow: [] }) })
  assert.ok(rolled.finalized)
  assert.equal(rolled.finalized.day, '2026-11-14')
  assert.equal(rolled.finalized.deviationMin, 8)
})

test('S15 脏输入不炸：appsOf 返回 null、片段缺字段', () => {
  assert.deepEqual(deviationOf([], () => null), { min: 0, top: [] })
  assert.deepEqual(deviationOf(null, null), { min: 0, top: [] })
  const segs = [{ context: '局内', boutId: 'B1', app: 'x.y', start: 0, end: MIN }]
  assert.deepEqual(deviationOf(segs, () => null), { min: 0, top: [] })
})
