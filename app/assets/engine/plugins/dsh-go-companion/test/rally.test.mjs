import { test } from 'node:test'
import assert from 'node:assert/strict'
import { nextShot, rallyStats, shotCount, RALLY_WINDOW_MS } from '../lib/rally.js'

const NOW = Date.parse('2026-11-06T10:00:00+08:00')

test('没有历史就是新回合的第一拍', () => {
  const r = nextShot([], { sessionId: 's1', now: NOW })
  assert.equal(r.shot, 1)
  assert.match(r.rallyId, /^rally-[0-9a-f]{16}$/)
})

test('窗口内接着上一拍往下数', () => {
  const rallyId = 'rally-aaaaaaaaaaaaaaaa'
  const entries = [
    { state: 'generated', rallyId, sessionId: 's1', at: NOW - 120_000 },
    { state: 'replied', rallyId, sessionId: 's1', at: NOW - 60_000 },
  ]
  const r = nextShot(entries, { sessionId: 's1', now: NOW })
  assert.equal(r.rallyId, rallyId)
  assert.equal(r.shot, 3)
})

test('substantive 是 replied 的标签，不重复算一拍', () => {
  const rallyId = 'rally-bbbbbbbbbbbbbbbb'
  const entries = [
    { state: 'generated', rallyId, at: NOW - 90_000 },
    { state: 'replied', rallyId, at: NOW - 60_000 },
    { state: 'substantive', rallyId, at: NOW - 60_000 },
  ]
  assert.equal(shotCount(entries, rallyId), 2)
  assert.equal(nextShot(entries, { now: NOW }).shot, 3)
})

test('超过窗口就收拍，下次开口是新的一轮', () => {
  const rallyId = 'rally-cccccccccccccccc'
  const entries = [{ state: 'generated', rallyId, at: NOW - RALLY_WINDOW_MS - 1 }]
  const r = nextShot(entries, { sessionId: 's1', now: NOW })
  assert.notEqual(r.rallyId, rallyId)
  assert.equal(r.shot, 1)
})

test('两个会话的回合不串', () => {
  const other = { state: 'generated', rallyId: 'rally-dddddddddddddddd', sessionId: 's2', at: NOW - 1000 }
  const mine = { state: 'generated', rallyId: 'rally-eeeeeeeeeeeeeeee', sessionId: 's1', at: NOW - 2000 }
  const r = nextShot([other, mine], { sessionId: 's1', now: NOW })
  assert.equal(r.rallyId, 'rally-eeeeeeeeeeeeeeee')
})

test('有效回合 = 至少两拍且有一拍接住了', () => {
  const dayStart = Date.parse('2026-11-06T00:00:00+08:00')
  const entries = [
    // 有效：发球 + 接住
    { state: 'generated', rallyId: 'r1', at: dayStart + 1000 },
    { state: 'replied', rallyId: 'r1', at: dayStart + 2000, verdict: 'engaged' },
    // 有效：真反驳也算接住
    { state: 'generated', rallyId: 'r2', at: dayStart + 3000 },
    { state: 'replied', rallyId: 'r2', at: dayStart + 4000, verdict: 'pushback' },
    // 无效：只发了一球，没人回
    { state: 'generated', rallyId: 'r3', at: dayStart + 5000 },
    // 无效：两拍但都很短
    { state: 'generated', rallyId: 'r4', at: dayStart + 6000 },
    { state: 'replied', rallyId: 'r4', at: dayStart + 7000, verdict: 'brief' },
    // 昨天的回合不算今天
    { state: 'generated', rallyId: 'r5', at: dayStart - 1000 },
    { state: 'replied', rallyId: 'r5', at: dayStart - 500, verdict: 'engaged' },
  ]
  assert.deepEqual(rallyStats(entries, dayStart), { total: 4, effective: 2 })
})

test('老账本没有 verdict 时按 replyClass 认', () => {
  const dayStart = Date.parse('2026-11-06T00:00:00+08:00')
  const entries = [
    { state: 'generated', rallyId: 'r1', at: dayStart + 1000 },
    { state: 'replied', rallyId: 'r1', at: dayStart + 2000, replyClass: 'substantive' },
  ]
  assert.deepEqual(rallyStats(entries, dayStart), { total: 1, effective: 1 })
})
