import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { lastReplyClass, readCommitments, readMood } from '../lib/context.js'

const NOW = Date.parse('2026-11-06T20:00:00+08:00')

test('心情：从 lastActive 读当前值，缺文件/坏文件返回 null', () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-context-'))
  try {
    mkdirSync(join(home, 'feiyu-mood'), { recursive: true })
    writeFileSync(join(home, 'feiyu-mood', 'state.json'), JSON.stringify({
      moods: { dafiyu: { mood: 63.4 }, other: { mood: 20 } },
      lastActive: 'dafiyu',
    }))
    assert.deepEqual(readMood(home), { mood: 63 })

    writeFileSync(join(home, 'feiyu-mood', 'state.json'), '{bad')
    assert.equal(readMood(home), null)
    assert.equal(readMood(join(home, 'nope')), null)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('承诺：只读 pending，按删除/完成状态过滤', () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-context-'))
  try {
    mkdirSync(join(home, 'relationship'), { recursive: true })
    const db = new DatabaseSync(join(home, 'relationship', 'relationship.db'))
    db.exec('CREATE TABLE commitments (id TEXT PRIMARY KEY, owner TEXT, thread_id TEXT, statement TEXT, due_at TEXT, status TEXT, evidence TEXT, follow_up_at TEXT, closed_at TEXT, created_at TEXT)')
    db.prepare('INSERT INTO commitments (id, owner, thread_id, statement, due_at, status, created_at) VALUES (?,?,?,?,?,?,?)')
      .run('c1', 'agent', 't1', '周五交稿', new Date(NOW + 86_400_000).toISOString(), 'pending', new Date(NOW).toISOString())
    db.prepare('INSERT INTO commitments (id, owner, thread_id, statement, due_at, status, created_at) VALUES (?,?,?,?,?,?,?)')
      .run('c2', 'user', 't1', '已经做完', new Date(NOW).toISOString(), 'done', new Date(NOW).toISOString())
    db.close()

    const rows = readCommitments(home, NOW, 5)
    assert.equal(rows.length, 1)
    assert.equal(rows[0].id, 'c1')
    assert.match(rows[0].statement, /周五交稿/)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('最近回应：取最后一条 replied 的分类', () => {
  const rows = [
    { state: 'generated', at: 1 },
    { state: 'replied', replyClass: 'brief', at: 2 },
    { state: 'replied', replyClass: 'substantive', at: 3 },
    { state: 'none', at: 4 },
  ]
  assert.equal(lastReplyClass(rows), 'substantive')
  assert.equal(lastReplyClass([]), '')
})
