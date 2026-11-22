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

// ---------------------------------------------------------------- 第二版：时间轴
import { addSeals, markOpened, timelineDue, rewardMinutes as rewardOf2 } from '../lib/seal.js'

test('时间轴：开工前定的不打折，开工后现加的七折；同名同截止不重复', () => {
  const morning = addSeals(null, [
    { title: '资料分析 30 题', kind: 'quiz', stretch: 30, startMinute: 9 * 60, dueMinute: 10 * 60 + 30, target: 'today' },
    { title: '行测笔记', kind: 'file', path: '学习/行测.md', stretch: 600, startMinute: 14 * 60, dueMinute: 15 * 60 + 30, target: 'today' },
    { title: '资料分析 30 题', kind: 'quiz', stretch: 30, dueMinute: 10 * 60 + 30, target: 'today' },
  ], { now: 1, today: TODAY, minuteOfDay: 8 * 60 + 40 })
  assert.equal(morning.added.length, 2)
  assert.equal(morning.skipped[0].error, 'duplicate')
  assert.ok(morning.added.every((x) => x.sameDay === false && x.source === 'morning'))
  assert.notEqual(morning.added[0].id, morning.added[1].id, '同一毫秒加的也不撞号')
  // 开过局了：就算还没到 10 点，也是现加
  const live = addSeal(morning.state, { title: '投递', kind: 'screenshots', stretch: 3, dueMinute: 18 * 60, target: 'today' }, { now: 2, today: TODAY, minuteOfDay: 9 * 60, workStarted: true })
  assert.equal(live.seal.sameDay, true)
  assert.equal(live.seal.source, 'live')
  const ai = addSeal(null, { title: 'Gemini 排的', kind: 'gemini_receipt', stretch: 1, dueMinute: 20 * 60, target: 'today' }, { now: 3, today: TODAY, minuteOfDay: 8 * 60, by: 'gemini' })
  assert.equal(ai.seal.source, 'gemini')
  assert.equal(addSeal(null, { title: 'x', kind: 'quiz', stretch: 5, startMinute: 900, dueMinute: 800, target: 'today' }, { today: TODAY, minuteOfDay: 60 }).error, 'bad-start')
})

test('时间轴推进：到开始时刻 → start；截止前 10 分钟没过保底 → warn；开过的不再推 start', () => {
  const st = addSeals(null, [
    { title: 'A', kind: 'quiz', stretch: 20, startMinute: 9 * 60, dueMinute: 10 * 60, target: 'today' },
    { title: 'B', kind: 'quiz', stretch: 20, dueMinute: 15 * 60, estMinutes: 60, target: 'today' },
  ], { now: 1, today: TODAY, minuteOfDay: 8 * 60 }).state
  assert.equal(timelineDue(st, { today: TODAY, minuteOfDay: 8 * 60 + 59 }), null)
  const s1 = timelineDue(st, { today: TODAY, minuteOfDay: 9 * 60 + 3 })
  assert.equal(s1.kind, 'start')
  assert.equal(s1.seal.title, 'A')
  assert.equal(s1.late, 3)
  assert.equal(timelineDue(st, { today: TODAY, minuteOfDay: 9 * 60 + 3, boutRunning: true }), null, '有局在跑不再开')
  const opened = markOpened(st, s1.seal.id, { boutId: 'b1', now: 5 }).state
  assert.equal(timelineDue(opened, { today: TODAY, minuteOfDay: 9 * 60 + 10 }), null)
  assert.equal(timelineDue(opened, { today: TODAY, minuteOfDay: 9 * 60 + 52, boutRunning: true }).kind, 'warn', '截止前 10 分钟有局在跑也提醒')
  // B 没写开始：按截止前 est 分钟算（15:00 - 60 = 14:00）
  assert.equal(timelineDue(opened, { today: TODAY, minuteOfDay: 13 * 60 + 59 }), null)
  assert.equal(timelineDue(opened, { today: TODAY, minuteOfDay: 14 * 60 }).seal.title, 'B')
})

test('新证据：通过验收的局分钟数、Gemini 回执；局类奖励减半', () => {
  const st = addSeals(null, [
    { title: '写申论', kind: 'bout_minutes', stretch: 90, dueMinute: 12 * 60, estMinutes: 90, target: 'today' },
    { title: 'Gemini 抽背', kind: 'gemini_receipt', stretch: 2, dueMinute: 21 * 60, target: 'today' },
  ], { now: 1, today: TODAY, minuteOfDay: 8 * 60 }).state
  const [bout, rec] = st.items
  const facts = { boutMinutes: { [bout.id]: 50 }, receipts: { [rec.id]: 2 } }
  assert.equal(measure(bout, facts), 50)
  assert.equal(measure(rec, facts), 2)
  assert.equal(rewardOf2(bout, 'floor'), 15) // round(90/3)=30 × 1 × 0.5
  assert.equal(rewardOf2(rec, 'stretch'), 19) // 默认预计 45 分钟 → 15 × 1.25
})

test('2026-11-22 AI 提议：两条鱼 / Gemini 加的默认没点头——不判档、不发奖励、不锁、不自动开局；点头后照常', async () => {
  const { addSeal: add, tickSeals: tick, lockingSeal: locking, confirmSeal, timelineDue: due } = await import('../lib/seal.js')
  const today = '2026-11-22'
  const r = add(null, { title: '鱼加的', kind: 'quiz', stretch: 10, floor: 5, dueMinute: 600, startMinute: 540, target: 'today' }, { now: 1, today, minuteOfDay: 500, by: 'ai' })
  assert.equal(r.seal.confirmed, false)
  assert.equal(r.seal.source, 'ai')
  const t = tick(r.state, { bankCorrect: 0 }, { now: 10_000_000, today, dueAtOf: () => 1 })
  assert.equal(t.state.items[0].status, 'open')
  assert.equal(t.pays.length, 0)
  assert.equal(locking(t.state, { now: 2, today }), null)
  assert.equal(due(r.state, { today, minuteOfDay: 550 }), null, '没点头不自动开局')
  const c = confirmSeal(r.state, r.seal.id, { now: 5, minuteOfDay: 550, today })
  assert.equal(c.ok, true)
  assert.equal(due(c.state, { today, minuteOfDay: 550 })?.kind, 'start')
  const own = add(null, { title: '自己封的', kind: 'quiz', stretch: 10, dueMinute: 600, target: 'today' }, { now: 1, today, minuteOfDay: 500 })
  assert.equal(own.seal.confirmed, true)
})
