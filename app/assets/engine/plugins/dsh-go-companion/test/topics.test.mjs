import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { backgroundFacts, chooseTopic, loadCandidates, openMemoryDb } from '../lib/topics.js'

const NOW = Date.parse('2026-11-05T20:00:00+08:00')

function makeDb(dir) {
  const db = new DatabaseSync(join(dir, 'fish-memory.sqlite'))
  db.exec(`CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT,
    priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER,
    attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)`)
  db.exec(`CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER,
    updated_at INTEGER, status TEXT)`)
  db.exec(`CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)`)
  return db
}

test('话题：未完成优先，按优先级和年龄排序', () => {
  const dir = mkdtempSync(join(tmpdir(), 'companion-topics-'))
  let db
  try {
    db = makeDb(dir)
    db.prepare('INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?,?)')
      .run('t1', 'session:s1', '面试结果', '她说下周出结果', 'open', 2, NOW - 9 * 86400000, 0)
    db.prepare('INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?,?)')
      .run('t2', 'global', '要不要买键盘', '', 'open', 1, NOW - 2 * 86400000, 0)
    const c = loadCandidates(db, { sessionId: 's1', now: NOW })
    const picked = chooseTopic(c, { now: NOW })
    assert.equal(picked.id, 'topic:t1')
    assert.equal(picked.kind, 'unfinished')
  } finally {
    db.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('话题：未来才可问的跳过；上次聊过的在冷却内跳过', () => {
  const dir = mkdtempSync(join(tmpdir(), 'companion-topics-'))
  let db
  try {
    db = makeDb(dir)
    db.prepare('INSERT INTO topics (id, scope, title, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?)')
      .run('t1', 'global', '明天再问', 'open', 2, NOW - 86400000, NOW + 3600000)
    db.prepare('INSERT INTO topics (id, scope, title, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?)')
      .run('t2', 'global', '刚聊过', 'open', 1, NOW - 86400000, 0)
    const c = loadCandidates(db, { sessionId: 's1', now: NOW })
    const picked = chooseTopic(c, { now: NOW, lastTopicId: 'topic:t2', lastAt: NOW - 3600000 })
    assert.equal(picked, null)
  } finally {
    db.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('话题：没有未完成时退到近期经历（分享）', () => {
  const dir = mkdtempSync(join(tmpdir(), 'companion-topics-'))
  let db
  try {
    db = makeDb(dir)
    db.prepare('INSERT INTO episodes (id, scope, text, tags, at) VALUES (?,?,?,?,?)')
      .run('e1', 'session:s1', '一起看了《葬送的芙莉莲》', '[]', NOW - 3600000)
    const c = loadCandidates(db, { sessionId: 's1', now: NOW })
    const picked = chooseTopic(c, { now: NOW })
    assert.equal(picked.kind, 'share')
    assert.match(picked.text, /芙莉莲/)
  } finally {
    db.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('背景事实：钉住/重要、最多 5 条、不含会话私事', () => {
  const dir = mkdtempSync(join(tmpdir(), 'companion-topics-'))
  let db
  try {
    db = makeDb(dir)
    const ins = db.prepare('INSERT INTO facts (id, scope, text, importance, pinned, updated_at, status) VALUES (?,?,?,?,?,?,?)')
    ins.run('f1', 'global', '她对芒果过敏', 2, 1, NOW, 'active')
    ins.run('f2', 'session:s2', '别的会话的私事', 2, 1, NOW, 'active')
    for (let i = 0; i < 8; i += 1) ins.run('f' + (3 + i), 'global', '事实' + i, 2, 0, NOW, 'active')
    const c = loadCandidates(db, { sessionId: 's1', now: NOW })
    const facts = backgroundFacts(c)
    assert.equal(facts.length, 5)
    assert.ok(facts.includes('她对芒果过敏'))
    assert.ok(!facts.includes('别的会话的私事'))
  } finally {
    db.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('没有记忆库时安全降级', () => {
  assert.equal(openMemoryDb(''), null)
  const c = loadCandidates(null, { sessionId: 's1', now: NOW })
  assert.deepEqual(c, { topics: [], facts: [], episodes: [] })
  assert.equal(chooseTopic(c, { now: NOW }), null)
})
