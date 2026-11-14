import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { openStore } from '../lib/store.js'
import { recall, remember } from '../lib/model.js'
import { matchLorebook, normalizeSceneTags, normalizeTriggers } from '../lib/lorebook.js'

function tempHome() {
  return mkdtempSync(join(tmpdir(), 'dsh-memory-lore-'))
}

test('SQLite FTS5：存储 triggers/sceneTags 持久化并能通过 ftsSearch 命中中英文词条', () => {
  const home = tempHome()
  try {
    const s = openStore({ home })
    assert.equal(s.kind, 'sqlite')
    const r1 = remember(s.doc, {
      text: '视频学习时每看五分钟要停下来用六骰子做一次口头复述',
      kind: 'preference',
      importance: 2,
      triggers: ['视频', '/bilibili|youtube/i'],
      sceneTags: ['scene:video_learning', 'app:bilibili'],
      scope: 'global',
    }, 1000)
    assert.equal(r1.ok, true)
    assert.equal(s.save(), true)

    const hitsCn = s.ftsSearch('六骰子', { scopes: ['global'] })
    assert.ok(hitsCn.some((h) => h.id === r1.fact.id), 'FTS5 应命中中文条目')

    const hitsEn = s.ftsSearch('bilibili', { scopes: ['global'] })
    assert.ok(hitsEn.some((h) => h.id === r1.fact.id), 'FTS5 应命中英文 scene_tags/triggers')

    s.close()

    const s2 = openStore({ home })
    assert.deepEqual(s2.doc.facts[0].triggers, ['视频', '/bilibili|youtube/i'])
    assert.deepEqual(s2.doc.facts[0].sceneTags, ['scene:video_learning', 'app:bilibili'])
    s2.close()
  } finally {
    try { rmSync(home, { recursive: true, force: true }) } catch {}
  }
})

test('Lorebook：keyword、regex 与 sceneTags 按优先顺序匹配且限制 ≤3 槽位', () => {
  const doc = { facts: [], topics: [], episodes: [], tombstones: [], hits: [] }
  for (let i = 0; i < 5; i += 1) {
    remember(doc, {
      text: '规则卡' + i + '：通勤听播客时禁止切回短视频',
      triggers: ['播客', '/commute\\s*mode/i'],
      sceneTags: i === 4 ? ['scene:commute'] : ['scene:other'],
      importance: i === 4 ? 2 : 1,
      scope: 'global',
    }, 2000 + i)
  }
  const m = matchLorebook(doc, {
    scope: 'global',
    query: '正在 commute mode 听播客',
    sceneTags: ['scene:commute'],
    limit: 3,
  }, 3000)
  assert.equal(m.items.length, 3)
  assert.equal(m.items[0].text.startsWith('规则卡4'), true, 'sceneTags 命中应排第一')

  const rec = recall(doc, {
    scope: 'global',
    query: '耳机里正在放播客',
    sceneTags: ['scene:commute'],
    limit: 2,
    lorebookLimit: 3,
  }, 3000)
  assert.ok(rec.lorebook.length <= 3 && rec.lorebook.length >= 1)
  assert.match(rec.text, /情境触发规则/)
})

test('Lorebook：非法正则与过长模式安全过滤而不崩溃', () => {
  const tr = normalizeTriggers(['/[unclosed/', '正常关键词', 'x'.repeat(200)])
  assert.equal(tr.includes('/[unclosed/'), false)
  assert.equal(tr.includes('正常关键词'), true)
  const st = normalizeSceneTags(['  SCENE:Test  ', 'scene:test'])
  assert.deepEqual(st, ['scene:test'])
})
