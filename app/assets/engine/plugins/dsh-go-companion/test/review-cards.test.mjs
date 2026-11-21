// 复习卡池（2026-11-21 验收第二批）：去重、500 张上限、哪些卡算数、什么复习算「验过」、
// 学习制卡两段兑现（各发一次、12 小时内自刷不算）、锁屏无选项题只算 0.7 题且不进题库答对数。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  addCards, cleanPool, deferredProgress, delayedRecall, dueCards, rateCard, receiptToCards, reviewSummary, reviewWeekLine, mirrorLine,
  MAX_CARDS, FIRST_DUE_MS,
} from '../lib/review_cards.js'
import { addSeal, cleanSeals, settleDeferred, tickSeals, SEAL_KINDS } from '../lib/seal.js'
import { creditReviewAnswer, judgeQuizAnswer, reviewCapFor, SELF_JUDGED_WEIGHT } from '../lib/bank_unlock.js'

const T0 = Date.parse('2026-11-21T01:00:00Z')
const H = 3_600_000

test('进池：缺答案 / 题干太短 / 选项对不上答案的不收；收的 12 小时后才第一次到期', () => {
  const r = addCards(null, [
    { q: '截位直除什么时候要截三位？', a: '首位相同且次位差小' },
    { q: '短', a: '答' },
    { q: '没有答案的题干在这里', a: '' },
    { q: '下列哪个是质数？', options: ['4', '6', '7', '9'], answer: 'C' },
    { q: '选项对不上的题目在这里', options: ['甲', '乙'], answer: '丙' },
  ], { now: T0, day: '2026-11-21', source: 'gemini_mcp' })
  assert.equal(r.added.length, 2)
  assert.equal(r.skipped.length, 3)
  const choice = r.added.find((x) => x.options.length)
  assert.equal(choice.answerIndex, 2)
  assert.equal(choice.a, '7')
  for (const c of r.added) assert.equal(c.fsrs.due, T0 + FIRST_DUE_MS)
})

test('去重：同 externalId、或题干只差标点空格大小写的，不生第二张', () => {
  const r1 = addCards(null, [{ externalId: 'brain-prop-42', q: '资料分析：截位直除的前提？', a: '首位相同' }], { now: T0, source: 'brainos' })
  const r2 = addCards(r1.state, [
    { externalId: 'brain-prop-42', q: '换了个说法的同一张', a: 'x' },
    { q: '资料分析 截位直除的前提', a: '首位相同' },
    { q: '资料分析：截位直除的前提？', a: '首位相同' },
  ], { now: T0 + 1, source: 'brainos' })
  assert.equal(r2.added.length, 0)
  assert.equal(r2.duplicate.length, 3)
  assert.equal(r2.state.items.length, 1)
})

test('算不算学习制卡：回执 / BrainOS / 带选项的算；聊天随手记的不算', () => {
  const r = addCards(null, [
    { q: '回执里的问答一条', a: '答案在这里', source: 'gemini_receipt' },
    { q: 'BrainOS 推的命题卡', a: '命题的结论', source: 'brainos' },
    { q: 'Gemini 推的无选项卡', a: '一个答案', source: 'gemini_mcp' },
    { q: 'Gemini 推的选择卡', options: ['A1', 'B1'], answer: 'A', source: 'gemini_mcp' },
    { q: '聊天里说记一下的卡', a: '一个答案', source: 'dsh_chat' },
  ], { now: T0 })
  const by = Object.fromEntries(r.added.map((c) => [c.q, c.counts]))
  assert.deepEqual(by, {
    '回执里的问答一条': true, 'BrainOS 推的命题卡': true, 'Gemini 推的无选项卡': false, 'Gemini 推的选择卡': true, '聊天里说记一下的卡': false,
  })
  // 调用方显式说不算，就不算（聊天工具、手动加卡）
  const r2 = addCards(null, [{ q: '带选项但是手动加的', options: ['x', 'y'], answer: 'B' }], { now: T0, counts: false })
  assert.equal(r2.added[0].counts, false)
})

test('卡池最多 500 张：满了先挤掉最稳的，再挤最老的', () => {
  const many = Array.from({ length: MAX_CARDS }, (_, i) => ({ q: '第 ' + i + ' 张卡的题干', a: '答' + i }))
  let r = addCards(null, many, { now: T0, max: MAX_CARDS })
  assert.equal(r.state.items.length, MAX_CARDS)
  // 第 0 张很稳
  r.state.items[0].fsrs.stability = 200
  r = addCards(r.state, [{ q: '新来的一张卡', a: '新' }], { now: T0 + 1 })
  assert.equal(r.state.items.length, MAX_CARDS)
  assert.equal(r.evicted.length, 1)
  assert.ok(!r.state.items.some((x) => x.q === '第 0 张卡的题干'), '挤掉最稳的那张')
  assert.ok(r.state.items.some((x) => x.q === '新来的一张卡'))
})

test('回执转卡：只有「过」的回执，题干 ≥6 字、答 ≥4 字，最多 4 张', () => {
  const qa = Array.from({ length: 6 }, (_, i) => ({ q: '第' + i + '个问题问得够长', a: '答得也够长' + i }))
  assert.deepEqual(receiptToCards({ verdict: 'partial', qa }), [])
  assert.deepEqual(receiptToCards({ verdict: 'fail', qa }), [])
  const cards = receiptToCards({ verdict: 'pass', topic: '言语', qa: [{ q: '太短', a: '够长的答案' }, { q: '问题够长够长', a: '短' }, ...qa] })
  assert.equal(cards.length, 4)
  assert.ok(cards.every((c) => c.source === 'gemini_receipt' && c.topic === '言语'))
})

test('什么复习算验过：锁屏 / 复习页的 4 选 1（代码判）、Gemini、BrainOS；自己翻答案、两条鱼抽问不算', () => {
  const r = addCards(null, [{ q: '无选项卡的题干', a: '答案' }, { q: '选择卡的题干', options: ['a', 'b'], answer: 'B' }], { now: T0 })
  const [plain, choice] = r.added
  const at = T0 + 13 * H
  assert.equal(rateCard(r.state, choice.id, 3, { now: at, channel: 'lockscreen' }).verified, true)
  assert.equal(rateCard(r.state, choice.id, 3, { now: at, channel: 'app_choice' }).verified, true)
  assert.equal(rateCard(r.state, plain.id, 3, { now: at, channel: 'lockscreen' }).verified, false, '没选项的卡在锁屏只能自判')
  assert.equal(rateCard(r.state, plain.id, 3, { now: at, channel: 'self' }).verified, false)
  assert.equal(rateCard(r.state, plain.id, 3, { now: at, channel: 'sister' }).verified, false)
  assert.equal(rateCard(r.state, plain.id, 'good', { now: at, channel: 'gemini' }).verified, true)
  assert.equal(rateCard(r.state, plain.id, 'easy', { now: at, channel: 'brainos' }).verified, true)
  assert.equal(rateCard(r.state, 'nope', 3, { now: at }).ok, false)
  assert.equal(rateCard(r.state, plain.id, 7, { now: at }).ok, false)
})

test('延迟回忆：只看创建 12 小时后的第一次复习；12 小时内刷的不算；没验过的那次算用掉了', () => {
  const r = addCards(null, [{ q: '延迟回忆用的卡', options: ['x', 'y'], answer: 'A' }], { now: T0, sealId: 'seal-1' })
  const id = r.added[0].id
  let s = rateCard(r.state, id, 3, { now: T0 + H, channel: 'lockscreen' }).state
  assert.equal(delayedRecall(s.items[0]), '', '12 小时内答对不算')
  s = rateCard(s, id, 3, { now: T0 + 13 * H, channel: 'lockscreen' }).state
  assert.equal(delayedRecall(s.items[0]), 'pass')
  // 隔夜第一次是自己翻答案（没验过）：这次回忆用掉了，不算
  const r2 = addCards(null, [{ q: '另一张没选项的卡', a: '答案' }], { now: T0, sealId: 'seal-1' })
  const s2 = rateCard(r2.state, r2.added[0].id, 3, { now: T0 + 13 * H, channel: 'self' }).state
  assert.equal(delayedRecall(s2.items[0]), 'fail')
  // 隔夜第一次验过但答错：不算
  const s3 = rateCard(r.state, id, 1, { now: T0 + 13 * H, channel: 'lockscreen' }).state
  assert.equal(delayedRecall(s3.items[0]), 'fail')
})

test('学习制卡：第一半当天按 0.5 发；第二半够保底才发、只发一次、和第一半一样多', () => {
  assert.equal(SEAL_KINDS.study_cards.unit, '张')
  const today = '2026-11-21'
  const add = addSeal(null, { title: '资料分析制卡', kind: 'study_cards', stretch: 6, floor: 3, dueMinute: 22 * 60, estMinutes: 60, target: 'today' },
    { now: T0, today, minuteOfDay: 9 * 60, workStarted: false })
  assert.equal(add.ok, true, JSON.stringify(add))
  const sid = add.seal.id
  const tick = tickSeals(add.state, { studyCards: { [sid]: 3 } }, { now: T0, today, dueAtOf: () => T0 + 10 * H })
  const seal = tick.state.items[0]
  assert.equal(seal.status, 'floor')
  assert.equal(seal.paidMinutes, 10, 'round(60/3)=20 × 保底 1.0 × 学习制卡 0.5')
  // 还不够保底：不发
  let r = settleDeferred(tick.state, () => 2, { now: T0 + 20 * H })
  assert.equal(r.pays.length, 0)
  r = settleDeferred(tick.state, () => 3, { now: T0 + 20 * H })
  assert.equal(r.pays.length, 1)
  assert.equal(r.pays[0].minutes, 10)
  assert.equal(r.state.items[0].deferredPaid, 10)
  // 幂等：再结一次不发
  const again = settleDeferred(r.state, () => 6, { now: T0 + 30 * H })
  assert.equal(again.pays.length, 0)
  // 没过档的不发第二半
  const below = settleDeferred(add.state, () => 6, { now: T0 + 20 * H })
  assert.equal(below.pays.length, 0)
  // 旧数据（没有 deferred 字段）照常清洗
  assert.equal(cleanSeals({ items: [{ ...seal, deferredPaid: undefined }] }).items[0].deferredPaid, 0)
})

test('deferredProgress：绑在这一点上的卡，延迟回忆过了几张、还在等几张', () => {
  let r = addCards(null, [
    { q: '绑定的第一张卡', options: ['x', 'y'], answer: 'A' },
    { q: '绑定的第二张卡', options: ['x', 'y'], answer: 'A' },
    { q: '绑定的第三张卡', options: ['x', 'y'], answer: 'A' },
  ], { now: T0, sealId: 'seal-9' })
  const [a, b] = r.added
  let s = rateCard(r.state, a.id, 3, { now: T0 + 13 * H, channel: 'lockscreen' }).state
  s = rateCard(s, b.id, 1, { now: T0 + 13 * H, channel: 'lockscreen' }).state
  assert.deepEqual(deferredProgress(s, 'seal-9', T0 + 14 * H), { cards: 3, passed: 1, waiting: 1 })
  assert.deepEqual(deferredProgress(s, 'other', T0), { cards: 0, passed: 0, waiting: 0 })
})

test('摘要 / 周冷数据 / 镜像：到期几张、首次回忆通过率、错得最多的主题', () => {
  const r = addCards(null, [
    { q: '资料分析的一张卡', a: '答一', topic: '资料分析' },
    { q: '言语理解的一张卡', a: '答二', topic: '言语' },
  ], { now: T0 })
  let s = rateCard(r.state, r.added[0].id, 1, { now: T0 + 13 * H, channel: 'gemini' }).state
  s = rateCard(s, r.added[1].id, 3, { now: T0 + 13 * H, channel: 'gemini' }).state
  const sum = reviewSummary(s, T0 + 13 * H + 20 * 60_000)
  assert.equal(sum.totalCards, 2)
  assert.equal(sum.dueCount, 1, '答错的 10 分钟后又到期')
  assert.equal(sum.retention7d, 50)
  assert.deepEqual(sum.weakTopics, [{ topic: '资料分析', lapses: 1 }])
  assert.equal(dueCards(s, T0 + 13 * H + 20 * 60_000).length, 1)
  const line = reviewWeekLine(s, { from: T0 - H, to: T0 + 48 * H, now: T0 + 14 * H })
  assert.match(line, /本周新建 2 张 · 复习 2 次 · 首次回忆通过 50%（1\/2）/)
  assert.equal(reviewWeekLine(null, { from: 0, to: 1 }), '')
  assert.match(mirrorLine(cleanPool(s).items[0]), /^- \[资料分析\] 资料分析的一张卡 :: 答一/)
})

test('锁屏：无选项的题库题自己对答案只算 0.7 题、不进题库答对数；复习卡同样不进', () => {
  const bank = { 题目: [{ id: 'q1', 题干: '简答题', 答案: '标准答案' }, { id: 'q2', 题干: '选择题', 选项: ['甲', '乙'], 答案: 'B' }] }
  const self = judgeQuizAnswer(bank, null, { questionId: 'q1', answer: 'PASS', now: T0 })
  assert.equal(self.correct, true)
  assert.equal(self.solved, SELF_JUDGED_WEIGHT)
  assert.equal(self.state.stats.correct, 0, '自判不进题库答对数（题库类验收点不认）')
  assert.equal(self.state.stats.selfPassed, 1)
  const coded = judgeQuizAnswer(bank, self.state, { questionId: 'q2', answer: 'B', now: T0 })
  assert.equal(coded.solved, 1.7)
  assert.equal(coded.state.stats.correct, 1)
  // 复习卡：按权重记进度，不碰题库答对数；够了解锁
  const st = { config: { requiredCount: 2, unlockMinutes: 5 }, session: { solved: 1.4 }, stats: { correct: 7 } }
  const r = creditReviewAnswer(st, { weight: 0.7, now: T0 })
  assert.equal(r.unlocked, true)
  assert.equal(r.state.stats.correct, 7)
  assert.equal(r.state.stats.reviewed, 1)
  assert.equal(r.state.session.solved, 0)
  const r2 = creditReviewAnswer({ ...st, session: { solved: 0 } }, { weight: 0, now: T0 })
  assert.equal(r2.unlocked, false)
  assert.equal(r2.state.session.reviewDone, 1)
  assert.equal(reviewCapFor(10), 5)
  assert.equal(reviewCapFor(1), 1)
})
