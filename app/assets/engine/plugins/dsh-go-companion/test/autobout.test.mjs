// P2 全天主动：到点自动开局、换局额度、不许说「不开」、非工作时段规则。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  OFFHOUR_GRACE_MS, OFFHOUR_IDLE_MIN, SWAP_QUOTA,
  nextDue, offhour, refuseOpen, swapBout, swapQuota,
} from '../lib/autobout.js'
import { DAY_RESTDAY, DAY_WORKDAY, settingsOf } from '../lib/settings.js'

const TZ = 'Asia/Shanghai'
const DAY = Date.parse('2026-03-09T16:00:00Z')
const at = (hour, minute = 0) => DAY + (hour * 60 + minute) * 60_000
const MIN = (hour, minute = 0) => hour * 60 + minute
const S = settingsOf({})

const q = () => [
  { id: 'a', protocolId: 'study', minute: MIN(9), budget: 25 },
  { id: 'b', protocolId: 'execute', minute: MIN(10, 30), budget: 30 },
]

test('P2 到点就开：9:00 开学习局', () => {
  const r = nextDue({ min: MIN(9), queue: q() })
  assert.equal(r.open.id, 'a')
  assert.equal(r.why, 'due')
})

test('P2 没到点不开', () => {
  assert.equal(nextDue({ min: MIN(8, 59), queue: q() }).open, null)
  assert.equal(nextDue({ min: MIN(8, 59), queue: q() }).why, 'not-yet')
})

test('P2 已经过点的照样开，不等到明天', () => {
  const r = nextDue({ min: MIN(11), queue: q() })
  assert.equal(r.open.id, 'a', '第一局还没开就先开它')
})

test('P2 同时只跑一局', () => {
  const r = nextDue({ min: MIN(11), queue: q(), running: q()[0] })
  assert.equal(r.open, null)
  assert.equal(r.why, 'already-running')
})

test('P2 队列空了不开', () => {
  assert.equal(nextDue({ min: MIN(11), queue: [] }).why, 'queue-empty')
  assert.equal(nextDue({ min: MIN(11), queue: null }).why, 'queue-empty')
})

test('P2 队列排好序时按顺序开', () => {
  const queue = q()
  assert.equal(nextDue({ min: MIN(9), queue }).open.id, 'a')
  assert.equal(nextDue({ min: MIN(10, 30), queue: queue.slice(1) }).open.id, 'b')
})

test('P2 换局额度：一天 3 次', () => {
  assert.equal(SWAP_QUOTA, 3)
  const r = swapQuota({}, DAY)
  assert.equal(r.left, 3)
  assert.equal(r.used, 0)
})

test('P2 换局额度：用掉的次数算得对', () => {
  const r = swapQuota({ swapDay: DAY, swapUsed: 2 }, DAY)
  assert.equal(r.left, 1)
})

test('P2 换局额度：换天就重置', () => {
  const r = swapQuota({ swapDay: DAY, swapUsed: 3 }, DAY + 86_400_000)
  assert.equal(r.left, 3, '第二天重新给 3 次')
})

test('P2 换局：把当前的挪到队尾，换下最早的那一局', () => {
  const r = swapBout({ queue: q(), day: DAY, state: {} })
  assert.equal(r.ok, true)
  assert.equal(r.bout.id, 'b', '换成队列里的下一局')
  assert.equal(r.queue[0].id, 'b')
  assert.equal(r.queue[r.queue.length - 1].id, 'a', '换下来的排到后面')
  assert.equal(r.state.swapUsed, 1)
})

test('P2 换局：换下来的局跟着新位置走，不会立刻又开', () => {
  const r = swapBout({ queue: q(), day: DAY, state: {} })
  const moved = r.queue[r.queue.length - 1]
  assert.equal(moved.minute, MIN(10, 30), '接手的是被换上去那一局的时段')
})

test('P2 换局：额度用完就不许换', () => {
  const r = swapBout({ queue: q(), day: DAY, state: { swapDay: DAY, swapUsed: 3 } })
  assert.equal(r.ok, false)
  assert.match(r.why, /每天 3 次/)
})

test('P2 换局：队列只有一个就换不了', () => {
  const r = swapBout({ queue: [q()[0]], day: DAY, state: {} })
  assert.equal(r.ok, false)
  assert.match(r.why, /只有这一局/)
})

test('P2 换局：空队列不崩', () => {
  const r = swapBout({ queue: null, day: DAY, state: null })
  assert.equal(r.ok, false)
  assert.deepEqual(r.queue, [])
})

test('P2 「不开」不许说，但要给他别的路', () => {
  for (const bad of ['我不想开', '今天不开了', '跳过这局', '免了']) {
    const out = refuseOpen(bad)
    assert.ok(out, bad + ' 该被拦')
    assert.match(out, /换/, '要告诉他可以换')
  }
})

test('P2 正常说话不拦', () => {
  assert.equal(refuseOpen('好，现在开始'), '')
  assert.equal(refuseOpen(''), '')
})

test('P2 非工作时段：无局满 30 分钟由小鱼提议', () => {
  // 13:00–13:30 是午饭后的空档，不在工作时段（上午到 12:00、下午 14:00 起）。
  const r = offhour({ now: at(13, 30), min: MIN(13, 30), kind: DAY_WORKDAY, settings: S, noBoutSince: at(13) })
  assert.equal(r.act, 'propose')
  assert.equal(r.protocolId, 'rest')
})

test('P2 非工作时段：差一分钟不提议（边界）', () => {
  const now = at(13, 30) - 1
  const r = offhour({ now, min: MIN(13, 29), kind: DAY_WORKDAY, settings: S, noBoutSince: at(13) })
  assert.equal(r.act, 'none')
  assert.equal(r.nextCheckAt, at(13, 30), '下一次检查就定在满 30 分钟那一刻')
})

test('P2 非工作时段：提议后 10 分钟不理就自动开休息局', () => {
  const proposedAt = at(13, 30)
  const waiting = offhour({ now: proposedAt + OFFHOUR_GRACE_MS - 1, min: MIN(13, 39), kind: DAY_WORKDAY, settings: S, proposedAt })
  assert.equal(waiting.act, 'none')
  const auto = offhour({ now: proposedAt + OFFHOUR_GRACE_MS, min: MIN(13, 40), kind: DAY_WORKDAY, settings: S, proposedAt })
  assert.equal(auto.act, 'auto-open')
  assert.equal(auto.protocolId, 'rest')
})

test('P2 工作时段不走非工作时段这条规则', () => {
  const r = offhour({ now: at(10), min: MIN(10), kind: DAY_WORKDAY, settings: S, noBoutSince: at(8) })
  assert.equal(r.act, 'none')
  assert.equal(r.why, 'work-hours')
})

test('P2 正在局里就不再提议', () => {
  const r = offhour({ now: at(13) + 40 * 60_000, min: MIN(13, 40), kind: DAY_WORKDAY, settings: S, running: { id: 'x' } })
  assert.equal(r.act, 'none')
  assert.equal(r.why, 'already-running')
})

test('P2 休息日也算非工作时段', () => {
  const r = offhour({ now: at(15) + 30 * 60_000, min: MIN(15, 30), kind: DAY_RESTDAY, settings: S, noBoutSince: at(15) })
  assert.equal(r.act, 'propose')
})

test('P2 OFFHOUR_IDLE_MIN 是 30 分钟', () => {
  assert.equal(OFFHOUR_IDLE_MIN, 30)
})
