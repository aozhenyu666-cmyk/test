import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { closeBout, getBout, openBout, openBoutsDb, reapRunning, runningBout } from '../lib/bouts.js'

const NOW = Date.parse('2026-11-12T09:00:00+08:00')

function withDb(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-reap-'))
  const db = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
  try {
    assert.ok(db, '库应该能打开')
    return fn(db, home)
  } finally {
    try {
      db.close()
    } catch {}
    rmSync(home, { recursive: true, force: true })
  }
}

function ledgerLines(home) {
  const file = join(home, 'companion', 'ledger.jsonl')
  if (!existsSync(file)) return []
  return readFileSync(file, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
}

test('库里没有局：什么都不做', () => {
  withDb((db, home) => {
    const r = reapRunning(db, { now: NOW, home })
    assert.equal(r.ok, true)
    assert.equal(r.closed, false)
    assert.equal(r.bout, null)
    assert.equal(ledgerLines(home).filter((x) => x.state === 'bout-close').length, 0)
  })
})

test('库里有局、进程里没有：收成空局', () => {
  withDb((db, home) => {
    const opened = openBout(db, { protocolId: 'p1', openedBy: 'plan', now: NOW - 3 * 60_000, home })
    assert.equal(opened.ok, true)
    const r = reapRunning(db, { now: NOW, home })
    assert.equal(r.closed, true)
    assert.equal(r.bout.id, opened.id)
    assert.equal(runningBout(db), null)
    const row = getBout(db, opened.id)
    assert.equal(row.status, 'closed')
    // 关键：记成 timeout 而不是 done——崩溃没收的局不算做完。
    assert.equal(row.closeReason, 'timeout')
    assert.equal(row.closedAt, NOW)
  })
})

test('收尾会进账本，看得出是收尾不是收局', () => {
  withDb((db, home) => {
    const opened = openBout(db, { protocolId: 'p1', now: NOW, home })
    reapRunning(db, { now: NOW + 1_000, home })
    const closes = ledgerLines(home).filter((x) => x.state === 'bout-close')
    assert.equal(closes.length, 1)
    assert.equal(closes[0].boutId, opened.id)
    assert.equal(closes[0].reason, 'timeout')
  })
})

test('已经收过的局不会被再收一次', () => {
  withDb((db, home) => {
    const opened = openBout(db, { protocolId: 'p1', now: NOW, home })
    closeBout(db, opened.id, { now: NOW + 1, reason: 'done', home })
    const r = reapRunning(db, { now: NOW + 2_000, home })
    assert.equal(r.closed, false)
    assert.equal(getBout(db, opened.id).closeReason, 'done')
  })
})

test('没有库：如实说没有，不假装收掉了', () => {
  const r = reapRunning(null, { now: NOW })
  assert.equal(r.ok, false)
  assert.equal(r.error, 'no-db')
  assert.equal(r.closed, false)
})
