// R9 第四项：挣来的娱乐。每通过一局换一段放风额度，只松「默认」那一条。
// 每条都有触发、不触发边界、换天、上限。

import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  EARN_MAX_PER_DAY, EARN_MAX_PER_PASS, EARN_PER_PASS,
  addForPass, covers, empty, leftOf, lineOf, ofDay, rateOf, spend,
} from '../lib/earned.js'

// 2026-03-10 00:00 +08，周二，工作日。
const DAY = Date.parse('2026-03-09T16:00:00Z')
const TZ = 'Asia/Shanghai'
const at = (h, m = 0) => DAY + (h * 60 + m) * 60_000

// 工作时段 09:00–18:00，别的都算非工作时段。
const inWork = (settings, kind, min) => min >= 9 * 60 && min < 18 * 60

test('E1 空账：0 分钟额度，剩 0', () => {
  const e = empty(DAY)
  assert.equal(e.earned, 0)
  assert.equal(e.spent, 0)
  assert.equal(leftOf(e), 0)
})

test('E2 换天了就换空账：昨天剩下的额度带不到今天', () => {
  const y = addForPass(empty(DAY), {}, at(10), DAY)
  assert.equal(leftOf(y), EARN_PER_PASS)
  const today = ofDay(y, DAY + 86_400_000)
  assert.equal(today.earned, 0)
  assert.equal(leftOf(today), 0)
})

test('E3 同一天再算一次：额度不变（幂等）', () => {
  const a = addForPass(empty(DAY), {}, at(10), DAY)
  const b = ofDay(a, DAY)
  assert.equal(b.earned, a.earned)
})

test('E4 没配就是默认 15 分钟', () => {
  assert.equal(rateOf({}), EARN_PER_PASS)
  assert.equal(rateOf(null), EARN_PER_PASS)
  assert.equal(rateOf({ earnMinPerBout: 0 }), EARN_PER_PASS)
  assert.equal(rateOf({ earnMinPerBout: -5 }), EARN_PER_PASS)
})

test('E5 一次最多 60 分钟：改成 600 也不会买下一整天', () => {
  assert.equal(rateOf({ earnMinPerBout: 600 }), EARN_MAX_PER_PASS)
  assert.equal(rateOf({ earnMinPerBout: 60 }), 60)
  assert.equal(rateOf({ earnMinPerBout: 30 }), 30)
})

test('E6 通过一局：加一段额度，并说清加了多少', () => {
  const r = addForPass(empty(DAY), { earnMinPerBout: 20 }, at(10), DAY)
  assert.equal(r.earned, 20)
  assert.equal(r.lastAdd, 20)
  assert.equal(leftOf(r), 20)
})

test('E7 一天上限 240：到顶就不再给', () => {
  let s = empty(DAY)
  for (let i = 0; i < 20; i += 1) s = addForPass(s, { earnMinPerBout: 60 }, at(10), DAY)
  assert.equal(s.earned, EARN_MAX_PER_DAY)
  assert.equal(s.lastAdd, 0, '到顶那一次没加，就不该说加了')
})

test('E8 花额度：不够就花光为止，不存负值', () => {
  const s = addForPass(empty(DAY), { earnMinPerBout: 20 }, at(10), DAY)
  const a = spend(s, 5)
  assert.equal(a.spent, 5)
  assert.equal(leftOf(a.state), 15)
  const b = spend(a.state, 999)
  assert.equal(b.spent, 15, '只剩 15 就只花 15')
  assert.equal(leftOf(b.state), 0)
})

test('E9 花 0 分钟 / 花负数：不动账', () => {
  const s = addForPass(empty(DAY), {}, at(10), DAY)
  assert.equal(spend(s, 0).spent, 0)
  assert.equal(spend(s, -10).spent, 0)
  assert.equal(leftOf(spend(s, 0).state), EARN_PER_PASS)
})

test('E10 有额度 + 非工作时段：算挣来的', () => {
  const s = addForPass(empty(DAY), {}, at(10), DAY)
  assert.equal(covers({ state: s, settings: {}, kind: 'workday', min: 20 * 60, inWork }), true)
})

test('E11 没额度：不算（边界：正好 0）', () => {
  assert.equal(covers({ state: empty(DAY), settings: {}, kind: 'workday', min: 20 * 60, inWork }), false)
})

test('E12 工作时段：有额度也不松——不能把白天让出去', () => {
  const s = addForPass(empty(DAY), {}, at(10), DAY)
  assert.equal(covers({ state: s, settings: {}, kind: 'workday', min: 10 * 60, inWork }), false)
})

test('E13 判不了工作时段：不松（宁可少放一次）', () => {
  const s = addForPass(empty(DAY), {}, at(10), DAY)
  assert.equal(covers({ state: s, settings: {}, kind: 'workday', min: 20 * 60 }), false)
})

test('E14 null 状态：不崩、不松', () => {
  assert.equal(covers({ state: null, settings: {}, kind: 'workday', min: 20 * 60, inWork }), false)
  assert.equal(leftOf(null), 0)
  assert.ok(lineOf(null).includes('还没有'))
})

test('E15 面板那一句：有额度说还剩多少，没有就说怎么挣', () => {
  const s = addForPass(empty(DAY), {}, at(10), DAY)
  assert.ok(lineOf(s).includes('15'), lineOf(s))
  assert.ok(lineOf(s).includes('还剩'), lineOf(s))
  assert.ok(lineOf(empty(DAY)).includes('每通过一局'), lineOf(empty(DAY)))
})
