import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { appendLedger, ledgerPath, readLedger, summarize } from '../lib/ledger.js'

const NOW = Date.parse('2026-11-05T20:00:00+08:00')
const DAY = 86400000

test('账本：追加、按顺序读回、跳过坏行', () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-ledger-'))
  try {
    appendLedger(home, { requestId: 'r1', state: 'accepted', at: NOW - 1000, topicId: 'topic:a' })
    appendLedger(home, { requestId: 'r1', state: 'generated', at: NOW, topicId: 'topic:a', text: 'hi' })
    const rows = readLedger(home, 10)
    assert.equal(rows.length, 2)
    assert.equal(rows[1].state, 'generated')
    assert.equal(rows[1].text, 'hi')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('账本：没有文件时返回空', () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-ledger-'))
  try {
    assert.deepEqual(readLedger(home, 5), [])
    assert.equal(ledgerPath(home).endsWith('companion\\ledger.jsonl') || ledgerPath(home).endsWith('companion/ledger.jsonl'), true)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('汇总：只算当天，记录最近话题', () => {
  const rows = [
    { requestId: 'r1', state: 'generated', at: NOW, topicId: 'topic:a' },
    { requestId: 'r1', state: 'accepted', at: NOW, topicId: 'topic:a' },
    { requestId: 'r2', state: 'generated', at: NOW, topicId: 'topic:b' },
    { requestId: 'r3', state: 'failed', at: NOW, topicId: 'topic:c' },
    { requestId: 'r0', state: 'generated', at: NOW - 3 * DAY, topicId: 'topic:old' },
  ]
  const s = summarize(rows, NOW - DAY)
  assert.equal(s.counts.generated, 2)
  assert.equal(s.counts.accepted, 1)
  assert.equal(s.counts.failed, 1)
  assert.equal(s.lastTopicId, 'topic:b')
})
