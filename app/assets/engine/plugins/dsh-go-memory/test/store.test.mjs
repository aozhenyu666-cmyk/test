import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { openStore } from '../lib/store.js'
import { remember, upsertTopic, listTopics, search } from '../lib/model.js'

function tempHome() {
  return mkdtempSync(join(tmpdir(), 'dsh-memory-'))
}

test('SQLite：写入后重开还在（权威库）', () => {
  const home = tempHome()
  try {
    const first = openStore({ home })
    assert.equal(first.kind, 'sqlite')
    remember(first.doc, { text: '她怕吵', scope: 'session:s1', pinned: true }, Date.now())
    upsertTopic(first.doc, { title: '面试结果', scope: 'session:s1' }, Date.now())
    assert.equal(first.save(), true)
    first.close()

    const file = join(home, 'fish-memory', 'fish-memory.sqlite')
    assert.equal(existsSync(file), true)

    const second = openStore({ home })
    assert.equal(second.kind, 'sqlite')
    assert.equal(second.doc.facts.length, 1)
    assert.equal(second.doc.facts[0].text, '她怕吵')
    assert.equal(second.doc.facts[0].pinned, true)
    assert.equal(second.doc.topics.length, 1)
    assert.equal(listTopics(second.doc, { scope: 'session:s1' }).items[0].title, '面试结果')
    second.close()
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('SQLite：删掉/改状态后再重开结果一致', () => {
  const home = tempHome()
  try {
    const s = openStore({ home })
    const a = remember(s.doc, { text: '她住在杭州', scope: 'global' }, 1)
    const f = s.doc.facts.find((x) => x.id === a.fact.id)
    f.status = 'forgotten'
    s.doc.tombstones.push({ id: 'tomb_x', scope: 'global', kind: 'fact', refId: f.id, textHash: 'h', reason: 'test', at: 2 })
    assert.equal(s.save(), true)
    s.close()

    const again = openStore({ home })
    assert.equal(again.doc.facts[0].status, 'forgotten')
    assert.equal(again.doc.tombstones.length, 1)
    assert.equal(search(again.doc, { scope: 'global' }).items.length, 0)
    again.close()
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('JSON 兜底：强制 json 也能读写，文件权限内容可读', () => {
  const home = tempHome()
  try {
    const s = openStore({ home, json: true })
    assert.equal(s.kind, 'json')
    remember(s.doc, { text: 'JSON 也能记', scope: 'global' }, 3)
    assert.equal(s.save(), true)
    s.close()

    const file = join(home, 'fish-memory', 'memory.json')
    assert.equal(existsSync(file), true)
    const raw = JSON.parse(readFileSync(file, 'utf8'))
    assert.equal(raw.facts.length, 1)
    assert.equal(raw.facts[0].text, 'JSON 也能记')

    const again = openStore({ home, json: true })
    assert.equal(again.doc.facts.length, 1)
    again.close()
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test('坏文件：SQLite 读坏后降级到 JSON，不崩', () => {
  const home = tempHome()
  try {
    const dir = join(home, 'fish-memory')
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(dir, 'fish-memory.sqlite'), 'not a database')
    const s = openStore({ home })
    assert.equal(s.kind, 'json')
    assert.match(s.note, /SQLite/)
    assert.equal(s.save(), true)
    s.close()
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})
