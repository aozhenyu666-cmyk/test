// FSRS（2026-11-21 验收第二批）：间隔单调、答错 10 分钟后再问、连续答对间隔递增、到期排序。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createFsrsCard, isCardDue, previewIntervals, ratingOf, reviewFsrsCard, sortDueCards, AGAIN_DELAY_MS, MAX_INTERVAL_DAYS } from '../lib/fsrs.js'

const T0 = Date.parse('2026-11-21T01:00:00Z')
const DAY = 86_400_000

test('评分认 1–4 和 again/hard/good/easy，别的都是 0', () => {
  assert.equal(ratingOf('good'), 3)
  assert.equal(ratingOf('Easy'), 4)
  assert.equal(ratingOf(1), 1)
  assert.equal(ratingOf('4'), 4)
  assert.equal(ratingOf(5), 0)
  assert.equal(ratingOf('meh'), 0)
})

test('新卡：第一次什么时候问由调用方定（个人卡隔 12 小时）', () => {
  const c = createFsrsCard(T0, { firstDueMs: 12 * 3_600_000 })
  assert.equal(c.state, 0)
  assert.equal(c.due, T0 + 12 * 3_600_000)
  assert.equal(isCardDue(c, T0), false)
  assert.equal(isCardDue(c, T0 + 13 * 3_600_000), true)
})

test('间隔单调：Again < Hard ≤ Good < Easy（新卡和复习中的卡都一样）', () => {
  let c = createFsrsCard(T0)
  for (let i = 0; i < 6; i++) {
    const now = c.due
    const p = previewIntervals(c, now)
    assert.ok(p.again < p.hard, JSON.stringify(p))
    assert.ok(p.hard <= p.good, JSON.stringify(p))
    assert.ok(p.good < p.easy, JSON.stringify(p))
    c = reviewFsrsCard(c, i % 2 ? 2 : 3, now)
  }
})

test('连续答对：间隔一次比一次长，有上限', () => {
  let c = createFsrsCard(T0)
  let last = 0
  for (let i = 0; i < 8; i++) {
    c = reviewFsrsCard(c, 3, c.due)
    assert.ok(c.scheduledDays > last, '第 ' + (i + 1) + ' 次：' + c.scheduledDays + ' 天，上次 ' + last)
    assert.ok(c.scheduledDays <= MAX_INTERVAL_DAYS)
    last = c.scheduledDays
  }
  assert.equal(c.reps, 8)
  assert.equal(c.lapses, 0)
  assert.equal(c.state, 2)
})

test('答错：10 分钟后再问、遗忘次数 +1、稳定度掉下来', () => {
  let c = createFsrsCard(T0)
  c = reviewFsrsCard(c, 3, T0)
  c = reviewFsrsCard(c, 3, c.due)
  const before = c.stability
  const now = c.due
  const d = reviewFsrsCard(c, 'again', now)
  assert.equal(d.due, now + AGAIN_DELAY_MS)
  assert.equal(d.lapses, 1)
  assert.equal(d.state, 3)
  assert.ok(d.stability < before)
  // 认不出的评分不动卡
  assert.deepEqual(reviewFsrsCard(c, 9, now), c)
})

test('到期排序：逾期最久的先，同样逾期的错得多的先；没到期的不出', () => {
  const a = { id: 'a', fsrs: { ...createFsrsCard(T0 - 2 * DAY), lapses: 0 } }
  const b = { id: 'b', fsrs: { ...createFsrsCard(T0 - DAY), lapses: 3 } }
  const c = { id: 'c', fsrs: { ...createFsrsCard(T0 - DAY), lapses: 1 } }
  const d = { id: 'd', fsrs: createFsrsCard(T0 + DAY) }
  assert.deepEqual(sortDueCards([d, c, b, a], T0).map((x) => x.id), ['a', 'b', 'c'])
})
