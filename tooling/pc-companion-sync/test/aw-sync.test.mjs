import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { buildPcUnknownHeartbeat, syncPcActivitySafe } from '../aw_sync.mjs'

test('PC offline / ECONNREFUSED yields pc_unknown and never marks slacking', async () => {
  const tmp = mkdtempSync(join(tmpdir(), 'aw-offline-'))
  try {
    const outFile = join(tmp, 'pc-status.json')
    const posted = []
    const res = await syncPcActivitySafe({
      awUrl: 'http://127.0.0.1:5600',
      companionUrl: 'http://100.64.0.12:3000/dsh-go-companion/v1/event',
      outFile,
      fetchFn: async (url, init = {}) => {
        if (url.includes('5600')) throw new Error('connect ECONNREFUSED 127.0.0.1:5600')
        posted.push(JSON.parse(init.body))
        return { ok: true, status: 200, json: async () => ({ ok: true }) }
      },
    })
    assert.equal(res.ok, true)
    assert.equal(res.status, 'pc_unknown')
    assert.equal(res.reason, 'aw-offline')
    assert.equal(res.payload.category, 'pc_unknown')
    assert.equal(res.payload.slacking, false)
    assert.equal(res.payload.entertainmentMinutes, 0)
    assert.equal(res.pushed, true)
    assert.equal(posted[0].category, 'pc_unknown')
    assert.equal(posted[0].slacking, false)

    const disk = JSON.parse(readFileSync(outFile, 'utf8'))
    assert.equal(disk.status, 'pc_unknown')
    assert.equal(disk.slacking, false)
  } finally {
    rmSync(tmp, { recursive: true, force: true })
  }
})

test('ActivityWatch request timeout aborts cleanly into pc_unknown (aw-timeout)', async () => {
  const hangingFetch = (_url, init = {}) =>
    new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => {
        const e = new Error('aborted')
        e.name = 'AbortError'
        reject(e)
      })
    })
  const res = await syncPcActivitySafe({
    awUrl: 'http://127.0.0.1:5600',
    timeoutMs: 120,
    fetchFn: hangingFetch,
  })
  assert.equal(res.ok, true)
  assert.equal(res.status, 'pc_unknown')
  assert.equal(res.reason, 'aw-timeout')
  assert.equal(res.payload.slacking, false)

  const direct = buildPcUnknownHeartbeat({ reason: 'manual-offline' })
  assert.equal(direct.category, 'pc_unknown')
  assert.equal(direct.slacking, false)
})

test('Healthy ActivityWatch buckets report online status and coding is not slacking', async () => {
  const res = await syncPcActivitySafe({
    awUrl: 'http://127.0.0.1:5600',
    fetchFn: async (url) => {
      if (url.endsWith('/api/0/buckets/')) {
        return { ok: true, status: 200, json: async () => ({ 'aw-watcher-window_pc': {} }) }
      }
      if (url.includes('/events')) {
        return {
          ok: true,
          status: 200,
          json: async () => [{ timestamp: '2026-11-14T10:00:00Z', duration: 600, data: { app: 'Code.exe', title: 'aw_sync.mjs' } }],
        }
      }
      return { ok: true, status: 200, json: async () => ({ ok: true }) }
    },
  })
  assert.equal(res.ok, true)
  assert.equal(res.status, 'online')
  assert.equal(res.payload.category, 'coding')
  assert.equal(res.payload.slacking, false)
})
