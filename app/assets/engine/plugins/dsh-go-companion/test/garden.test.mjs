import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  MAX_ATTEMPTS, addTopic, applyNoReply, applyReply, closeTopic, discoverFromMessage, emptyGarden,
  gardenStats, loadGarden, markGenerated, markUsed, nextTopic, saveGarden, seedFromCommitments, seedFromMemory,
} from '../lib/garden.js'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const NOW = Date.parse('2026-11-06T20:00:00+08:00')
const DAY = 86_400_000

test('种子：记忆里的话题和经历进入花园，重复的合并', () => {
  const g = emptyGarden()
  seedFromMemory(g, {
    topics: [{ id: 't1', title: '面试结果', detail: '她说周五出结果', priority: 2 }],
    episodes: [{ text: '一起看了《芙莉莲》', at: NOW - 3_600_000 }],
  }, NOW)
  assert.equal(g.topics.length, 2)
  assert.equal(g.topics.find((t) => t.id === 'topic:t1').kind, 'continue')
  assert.equal(g.topics.find((t) => t.id === 'topic:t1').interest, 2)

  seedFromMemory(g, {
    topics: [{ id: 't1', title: '面试结果', detail: '她说周五出结果', priority: 2 }],
    episodes: [{ text: '一起看了《芙莉莲》', at: NOW - 3_600_000 }],
  }, NOW + 1000)
  assert.equal(g.topics.length, 2)
})

test('种子：只有钉住事实时也能选出话题，不因缺话题永远沉默', () => {
  const g = emptyGarden()
  const facts = [{ id: 'f1', text: '我养的猫叫毛毛', importance: 1, pinned: 1 }]
  seedFromMemory(g, { topics: [], episodes: [], facts }, NOW)
  assert.equal(g.topics.length, 1)
  assert.equal(g.topics[0].id, 'fact:f1')
  assert.equal(g.topics[0].kind, 'share')
  assert.equal(nextTopic(g, { now: NOW }).id, 'fact:f1')

  // 重复播种合并同一条，不产生重复话题
  seedFromMemory(g, { topics: [], episodes: [], facts }, NOW + 1000)
  assert.equal(g.topics.length, 1)
})

test('生命周期：选中 → 实质回答继续；简短/回避/拒绝', () => {
  const g = emptyGarden()
  seedFromMemory(g, { topics: [{ id: 't1', title: '面试结果', priority: 2 }], episodes: [] }, NOW)
  const picked = nextTopic(g, { now: NOW })
  assert.equal(picked.id, 'topic:t1')
  markUsed(g, picked.id, NOW)
  assert.equal(g.topics[0].status, 'active')

  applyReply(g, picked.id, 'substantive', NOW + 1000)
  assert.equal(g.topics[0].status, 'active')
  assert.equal(g.topics[0].attempts, 0)

  applyReply(g, picked.id, 'brief', NOW + 2000)
  assert.equal(g.topics[0].status, 'paused')
  assert.ok(g.topics[0].nextEligibleAt >= NOW + 2 * 3_600_000)

  applyReply(g, picked.id, 'avoid', NOW + 3000)
  assert.equal(g.topics[0].status, 'paused')
  assert.ok(g.topics[0].nextEligibleAt >= NOW + 12 * 3_600_000)

  applyReply(g, picked.id, 'refuse', NOW + 4000)
  assert.equal(g.topics[0].status, 'done')
})

test('无回应：最多两次，之后冷却一周再开新周期', () => {
  const g = emptyGarden()
  seedFromMemory(g, { topics: [{ id: 't1', title: '面试结果', priority: 2 }], episodes: [] }, NOW)
  markUsed(g, 'topic:t1', NOW)
  applyNoReply(g, 'topic:t1', NOW + 1000)
  assert.equal(g.topics[0].attempts, 1)
  assert.equal(g.topics[0].status, 'active')
  applyNoReply(g, 'topic:t1', NOW + 2000)
  assert.equal(g.topics[0].attempts, 0)
  assert.equal(g.topics[0].status, 'paused')
  assert.ok(g.topics[0].nextEligibleAt >= NOW + 7 * DAY - 3000)
  assert.equal(nextTopic(g, { now: NOW + 3000 }), null)
})

test('冷却/尝试上限：未到时间不选，超过次数不选', () => {
  const g = emptyGarden()
  seedFromMemory(g, { topics: [{ id: 't1', title: '面试结果', priority: 2 }], episodes: [] }, NOW)
  const t = g.topics[0]
  t.attempts = MAX_ATTEMPTS
  assert.equal(nextTopic(g, { now: NOW }), null)
  t.attempts = 0
  t.nextEligibleAt = NOW + DAY
  assert.equal(nextTopic(g, { now: NOW }), null)
})

test('多样性：连续两次追问后优先分享/探索', () => {
  const g = emptyGarden()
  addTopic(g, { title: '面试结果', kind: 'continue', interest: 2 }, NOW)
  addTopic(g, { title: '今天看到一朵像鲸鱼的云', kind: 'share', interest: 1 }, NOW)
  g.meta.lastKinds = ['continue', 'continue']
  const picked = nextTopic(g, { now: NOW })
  assert.equal(picked.kind, 'share')
})

test('承诺：到期前会进入花园，但不提前催', () => {
  const g = emptyGarden()
  seedFromCommitments(g, [{ id: 'c1', statement: '周五交稿', dueAt: new Date(NOW + 2 * DAY).toISOString() }], NOW)
  assert.equal(g.topics[0].id, 'commit:c1')
  assert.equal(nextTopic(g, { now: NOW }), null) // nextEligibleAt = due-1h
  assert.ok(nextTopic(g, { now: NOW + 2 * DAY }))
})

test('正常聊天里发现新话题：每天最多 3 条', () => {
  const g = emptyGarden()
  assert.ok(discoverFromMessage(g, '我最近想买个机械键盘，还在纠结轴体', NOW))
  assert.equal(discoverFromMessage(g, '我最近想买个机械键盘，还在纠结轴体', NOW + 1), null) // 合并，不新增
  assert.ok(discoverFromMessage(g, '周末想去看看那家新开的店', NOW + 2))
  assert.ok(discoverFromMessage(g, '最近在玩一个新的解谜游戏', NOW + 3))
  assert.equal(discoverFromMessage(g, '下次想试试做饭', NOW + 4), null)
  assert.equal(g.topics.length, 3)
})

test('生成后保存摘要，只有实质回应后才算“聊过”', () => {
  const g = emptyGarden()
  addTopic(g, { title: '面试结果', kind: 'continue' }, NOW)
  const id = g.topics[0].id
  markGenerated(g, id, '周五出结果，她说有点紧张', NOW)
  assert.match(g.topics[0].lastSentSummary, /周五出结果/)
  assert.equal(g.topics[0].lastSummary, '')
  applyReply(g, id, 'substantive', NOW + 1000)
  assert.match(g.topics[0].lastSummary, /周五出结果/)
  assert.equal(g.topics[0].lastSentSummary, '')

  markGenerated(g, id, '第二轮只说了一半', NOW + 2000)
  applyReply(g, id, 'brief', NOW + 3000)
  assert.match(g.topics[0].lastSummary, /周五出结果/) // 没被单方面的话覆盖
})

test('关闭与统计', () => {
  const g = emptyGarden()
  addTopic(g, { title: 'a', kind: 'continue' }, NOW)
  addTopic(g, { title: 'b', kind: 'share' }, NOW)
  closeTopic(g, g.topics[0].id, 'done', NOW)
  const s = gardenStats(g, NOW)
  assert.equal(s.done, 1)
  assert.equal(s.candidate, 1)
})

test('结束的话题不会因为记忆仍是 open 就复活；新触碰才复活', () => {
  const g = emptyGarden()
  seedFromMemory(g, { topics: [{ id: 't1', title: '面试结果', priority: 2, last_touch_at: NOW - 10_000 }], episodes: [] }, NOW)
  closeTopic(g, 'topic:t1', 'done', NOW)
  const updated = g.topics[0].updatedAt
  // 旧的 last_touch_at：保持 done
  seedFromMemory(g, { topics: [{ id: 't1', title: '面试结果', priority: 2, last_touch_at: NOW - 20_000 }], episodes: [] }, NOW + 1000)
  assert.equal(g.topics[0].status, 'done')
  // 新的触碰时间：允许重新候选
  seedFromMemory(g, { topics: [{ id: 't1', title: '面试结果', priority: 2, last_touch_at: updated + 1 }], episodes: [] }, NOW + 2000)
  assert.equal(g.topics[0].status, 'candidate')
})

test('连续回避：24 小时后恢复，不消耗无回应额度', () => {
  const g = emptyGarden()
  addTopic(g, { title: '面试结果', kind: 'continue', interest: 2 }, NOW)
  const id = g.topics[0].id
  markUsed(g, id, NOW)
  applyReply(g, id, 'avoid', NOW + 1000)
  assert.equal(g.topics[0].attempts, 0)
  applyReply(g, id, 'avoid', NOW + 2000)
  assert.equal(g.topics[0].attempts, 0)
  assert.ok(g.topics[0].nextEligibleAt >= NOW + 24 * 3_600_000 - 1000)
  assert.equal(nextTopic(g, { now: NOW + 13 * 3_600_000 }), null)
  assert.ok(nextTopic(g, { now: NOW + 25 * 3_600_000 }))
})

test('持久化：写入后重新读回', () => {
  const dir = mkdtempSync(join(tmpdir(), 'companion-garden-'))
  try {
    const file = join(dir, 'topics.json')
    const g = emptyGarden()
    addTopic(g, { title: '面试结果', kind: 'continue' }, NOW)
    saveGarden(file, g)
    const again = loadGarden(file)
    assert.equal(again.topics.length, 1)
    assert.equal(again.topics[0].title, '面试结果')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
