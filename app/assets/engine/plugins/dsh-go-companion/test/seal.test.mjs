// 2026-11-20 封印（验收第一批）：前一晚立约、只能加码、只靠代码判、到点没过保底就锁、按档发奖励。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  addSeal, captureBaselines, claimSeal, lockingSeal, measure, raiseSeal, rewardMinutes, sealPromptLines,
  tickSeals, waiveSeal, SEAL_GRACE_MS, SEAL_LOCK_MAX_MS,
} from '../lib/seal.js'

const TODAY = '2026-11-20'
const TOMORROW = '2026-11-21'
const NOON = Date.parse('2026-11-20T04:00:00Z') // 12:00 北京
const dueAtOf = (s) => Date.parse(s.day + 'T00:00:00Z') - 8 * 3_600_000 + s.dueMinute * 60_000

test('前一晚封明天；过了 23:00 再封明天的算当天现定；当天现定 ×0.7', () => {
  const a = addSeal(null, { title: '资料分析', kind: 'quiz', stretch: 30, dueMinute: 12 * 60 }, { now: NOON, today: TODAY, minuteOfDay: 21 * 60 + 30 })
  assert.equal(a.ok, true)
  assert.equal(a.seal.day, TOMORROW)
  assert.equal(a.seal.floor, 15, '保底默认拉伸的一半')
  assert.equal(a.seal.sameDay, false)
  const late = addSeal(null, { title: '言语', kind: 'quiz', stretch: 20 }, { now: NOON, today: TODAY, minuteOfDay: 23 * 60 + 10 })
  assert.equal(late.seal.sameDay, true)
  const t = addSeal(null, { title: '今天现定', kind: 'quiz', stretch: 10, dueMinute: 20 * 60, target: 'today' }, { now: NOON, today: TODAY, minuteOfDay: 12 * 60 })
  assert.equal(t.seal.day, TODAY)
  assert.equal(rewardMinutes(t.seal, 'floor'), Math.round(15 * 0.7))
})

test('I15 只能加码：调低拉伸 / 保底一律拒绝，原值不动', () => {
  const a = addSeal(null, { title: 'x', kind: 'quiz', stretch: 30 }, { now: NOON, today: TODAY, minuteOfDay: 600 })
  const lower = raiseSeal(a.state, a.seal.id, { stretch: 5 })
  assert.equal(lower.ok, false)
  assert.equal(lower.error, 'cannot-lower')
  assert.equal(lower.state.items[0].stretch, 30)
  assert.equal(raiseSeal(a.state, a.seal.id, { floor: 10 }).ok, false)
  const up = raiseSeal(a.state, a.seal.id, { stretch: 40 })
  assert.equal(up.ok, true)
  assert.equal(up.seal.stretch, 40)
  assert.equal(up.seal.floor, 15)
})

test('I17 只认代码证据：没有口头类；外部刷题报数有截图和用时上限', () => {
  assert.equal(addSeal(null, { title: '聊天', kind: 'oral', stretch: 1 }, { now: NOON, today: TODAY }).ok, false)
  const base = { baseline: { at: 0, correct: 100, bytes: 1000 } }
  assert.equal(measure({ ...base, kind: 'quiz' }, { bankCorrect: 118 }), 18)
  assert.equal(measure({ ...base, kind: 'file', path: 'a.md' }, { fileBytes: { 'a.md': 1900 } }), 300)
  const ext = { ...base, kind: 'external_quiz', app: 'fenbi', claimed: 30 }
  assert.equal(measure(ext, { screenshotsSince: 0, appMinutes: { fenbi: 60 } }), 0, '没截图，报多少都是 0')
  assert.equal(measure(ext, { screenshotsSince: 1, appMinutes: { fenbi: 8 } }), 16, '用时 8 分钟最多算 16 题')
  assert.equal(measure(ext, { screenshotsSince: 1, appMinutes: { fenbi: 60 } }), 30)
  const c = claimSeal({ items: [{ id: 's', day: TODAY, title: 't', kind: 'external_quiz', stretch: 30, app: 'fenbi', claimed: 20 }] }, 's', 5)
  assert.equal(c.seal.claimed, 20, '报数也只能往上')
})

test('自动结算：过保底发一次，到拉伸补差额；不重复发', () => {
  let st = addSeal(null, { title: '刷题', kind: 'quiz', stretch: 30, dueMinute: 18 * 60, estMinutes: 60 }, { now: NOON, today: TODAY, minuteOfDay: 600, }).state
  st = { items: st.items.map((x) => ({ ...x, day: TODAY })) }
  st = captureBaselines(st, { bankCorrect: 100 }, { now: NOON, today: TODAY }).state
  let r = tickSeals(st, { bankCorrect: 115 }, { now: NOON, today: TODAY, dueAtOf })
  assert.deepEqual(r.pays.map((p) => [p.tier, p.minutes]), [['floor', 20]])
  r = tickSeals(r.state, { bankCorrect: 115 }, { now: NOON + 60_000, today: TODAY, dueAtOf })
  assert.equal(r.pays.length, 0, '同一档不重复发')
  r = tickSeals(r.state, { bankCorrect: 131 }, { now: NOON + 120_000, today: TODAY, dueAtOf })
  assert.deepEqual(r.pays.map((p) => [p.tier, p.minutes]), [['stretch', 5]], '拉伸 25 分钟，补差额 5')
})

test('到点没过保底 → 锁（最长 60 分钟）；锁里交够保底 → 解锁、按晚交 ×0.5 发', () => {
  let st = { items: [addSeal(null, { title: '刷题', kind: 'quiz', stretch: 30, dueMinute: 12 * 60, estMinutes: 60, target: 'today' }, { now: NOON - 7_200_000, today: TODAY, minuteOfDay: 600 }).seal] }
  st = captureBaselines(st, { bankCorrect: 0 }, { now: NOON - 7_000_000, today: TODAY }).state
  const due = dueAtOf(st.items[0])
  let r = tickSeals(st, { bankCorrect: 3 }, { now: due + 30_000, today: TODAY, dueAtOf })
  assert.equal(lockingSeal(r.state, { now: due + 30_000, today: TODAY }), null, '缓冲 1 分钟内不锁')
  r = tickSeals(r.state, { bankCorrect: 3 }, { now: due + SEAL_GRACE_MS + 1, today: TODAY, dueAtOf })
  const locked = lockingSeal(r.state, { now: due + SEAL_GRACE_MS + 5, today: TODAY })
  assert.ok(locked)
  assert.equal(locked.lockUntil, due + SEAL_GRACE_MS + SEAL_LOCK_MAX_MS)
  r = tickSeals(r.state, { bankCorrect: 15 }, { now: due + 10 * 60_000, today: TODAY, dueAtOf })
  assert.equal(lockingSeal(r.state, { now: due + 10 * 60_000, today: TODAY }), null, '交够保底就解')
  assert.deepEqual(r.pays.map((p) => [p.tier, p.late, p.minutes]), [['floor', true, Math.round(20 * 0.7 * 0.5)]])
})

test('豁免要写理由；过了的不用豁免；原始数字留着', () => {
  const st = { items: [{ id: 's', day: TODAY, title: 't', kind: 'quiz', stretch: 30, floor: 15, status: 'below', lockUntil: NOON + 1 }] }
  assert.equal(waiveSeal(st, 's', '').ok, false)
  const w = waiveSeal(st, 's', '发烧去医院')
  assert.equal(w.seal.status, 'waived')
  assert.equal(w.seal.stretch, 30)
  assert.equal(lockingSeal(w.state, { now: NOON, today: TODAY }), null)
})

test('姐妹上下文：列出今天的封印，并明说封印改不了、别替他求情', () => {
  const lines = sealPromptLines({ items: [{ id: 's', day: TODAY, title: '资料分析', kind: 'quiz', stretch: 30, floor: 15, actual: 12, dueMinute: 720 }] }, { today: TODAY, hm: () => '12:00' })
  assert.match(lines[0], /资料分析.*12\/30题.*保底 15.*12:00/)
  assert.match(lines.at(-1), /只能加码/)
})
