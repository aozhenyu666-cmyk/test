import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { emptyDoc } from '../lib/model.js'
import { importFishMemory } from '../lib/migrate.js'

test('迁移：把旧 dsh-fish-memory 的 memories 搬进权威库', () => {
  const home = mkdtempSync(join(tmpdir(), 'dsh-memory-mig-'))
  try {
    const dir = join(home, 'fish-memory')
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(dir, 'sess-1.json'), JSON.stringify({
      memories: [
        { id: 'm1', text: '她怕吵，晚上十一点后别主动发消息', importance: 1, pinned: true, createdAt: '2026-09-01T10:00:00.000Z', tags: ['作息'] },
        { id: 'm2', text: '她最近在准备面试', importance: 0.5, createdAt: '2026-10-01T10:00:00.000Z' },
        { id: 'm3', text: '吃了火锅', importance: 0, createdAt: '2026-10-02T10:00:00.000Z' },
      ],
    }))
    writeFileSync(join(dir, 'memory.json'), JSON.stringify({ facts: [{ id: 'x', text: '我们自己的兜底库' }] }))
    const doc = emptyDoc()
    const r = importFishMemory(doc, { home, now: Date.parse('2026-11-05T00:00:00.000Z') })
    assert.equal(r.files, 1)
    assert.equal(r.added, 3)
    assert.equal(r.skipped, 0)
    assert.equal(doc.facts.length, 3)
    const pinned = doc.facts.find((f) => f.pinned)
    assert.ok(pinned)
    assert.equal(pinned.importance, 2)
    assert.ok(pinned.tags.includes('迁移'))
    const low = doc.facts.find((f) => f.text === '吃了火锅')
    assert.equal(low.importance, 0)
    // 旧库不删
    assert.equal(existsSync(join(dir, 'sess-1.json')), true)
    // 我们自己的兜底文件不会被当成旧库导入
    assert.equal(doc.facts.some((f) => f.text === '我们自己的兜底库'), false)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('迁移：坏文件只记失败，不影响其它文件', () => {
  const home = mkdtempSync(join(tmpdir(), 'dsh-memory-mig2-'))
  try {
    const dir = join(home, 'fish-memory')
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(dir, 'bad.json'), '{not json')
    writeFileSync(join(dir, 'good.json'), JSON.stringify({ memories: [{ text: '她喜欢下雨天', importance: 0.6 }] }))
    const doc = emptyDoc()
    const r = importFishMemory(doc, { home })
    assert.equal(r.failed, 1)
    assert.equal(r.added, 1)
    assert.equal(doc.facts[0].text, '她喜欢下雨天')
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('迁移：没有旧库时什么都不做', () => {
  const home = mkdtempSync(join(tmpdir(), 'dsh-memory-mig3-'))
  try {
    const r = importFishMemory(emptyDoc(), { home })
    assert.deepEqual({ added: r.added, files: r.files }, { added: 0, files: 0 })
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})
