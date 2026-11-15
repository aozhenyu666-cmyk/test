import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { emptyDoc, normalizeKind, recall, remember } from '../lib/model.js'
import { appendDialog, dialogLines, readDialog, trimDialog, userTextOf } from '../lib/dialog.js'

const DAY = 86_400_000
const T0 = Date.UTC(2026, 10, 10, 4, 0)

function seed() {
  const doc = emptyDoc()
  remember(doc, { text: '他下周三下午字节二面，有点慌', scope: 'global', importance: 2 }, T0)
  remember(doc, { text: '称呼：叫他阿澳', scope: 'global', pinned: true }, T0)
  remember(doc, { text: '他不吃香菜', scope: 'global', kind: 'preference' }, T0 + 1000)
  remember(doc, { text: '他在追一部叫《漂流》的番', scope: 'global' }, T0 + 2000)
  return doc
}

test('常驻档案：「嗯」「好烦」这种对不上的话，钉住和重要的也每轮都带', () => {
  const doc = seed()
  for (const q of ['嗯', '好烦', '在干嘛']) {
    const r = recall(doc, { scope: 'session:s1', query: q }, T0 + DAY)
    assert.match(r.text, /关于他·常驻/)
    assert.match(r.text, /阿澳/)
    assert.match(r.text, /字节二面/)
  }
})

test('对不上时用最近的条目补位，不再是空的', () => {
  const doc = seed()
  const r = recall(doc, { scope: 'session:s1', query: '今天不想动' }, T0 + DAY)
  assert.ok(r.facts.length >= 2, '至少补两条普通记忆')
  assert.ok(r.facts.some((f) => /香菜|漂流/.test(f.text)))
})

test('常驻档案不重复出现在相关条目里，也不累加命中数', () => {
  const doc = seed()
  const r = recall(doc, { scope: 'session:s1', query: '字节面试' }, T0 + DAY)
  const ids = [...r.facts, ...r.core].map((f) => f.id)
  assert.equal(new Set(ids).size, ids.length)
  const pin = doc.facts.find((f) => f.pinned)
  assert.equal(pin.hits, 0)
})

test('随机想起：给了随机数才挑，挑的是一天前的旧事', () => {
  const doc = seed()
  for (let i = 0; i < 5; i++) remember(doc, { text: '旧事' + i + '：他提过想学吉他', scope: 'global', importance: 0 }, T0 + 3000 + i)
  assert.equal(recall(doc, { scope: 'global', query: '嗯' }, T0 + DAY / 2).drift, null)
  const r = recall(doc, { scope: 'global', query: '嗯', random: 0.99 }, T0 + 3 * DAY)
  assert.ok(r.drift)
  assert.match(r.text, /顺手想起/)
})

test('kind 认中文：「小事」记成共同经历、默认琐事重要度', () => {
  assert.equal(normalizeKind('小事'), 'event')
  assert.equal(normalizeKind('偏好'), 'preference')
  assert.equal(normalizeKind('乱写'), 'fact')
  const doc = emptyDoc()
  const r = remember(doc, { text: '中午吃了螺蛳粉', kind: '小事', scope: 'global' }, T0)
  assert.equal(r.fact.kind, 'event')
  assert.equal(r.fact.importance, 0)
  assert.equal(doc.episodes.length, 1)
})

test('原话留底：只认他本人说的，插件提示和模型回答不算', () => {
  assert.equal(userTextOf({ role: 'user', content: [{ type: 'text', text: '我明天面试' }], source: { kind: 'user' } }), '我明天面试')
  assert.equal(userTextOf({ role: 'user', content: [{ type: 'text', text: '（这不是主人说的话，是手机端让你主动去找他的一次机会' }] }), '')
  assert.equal(userTextOf({ role: 'user', content: [{ type: 'text', text: '随便' }], source: { kind: 'dsh-go-companion' } }), '')
  assert.equal(userTextOf({ role: 'assistant', content: [{ type: 'text', text: '好的' }] }), '')
  assert.equal(userTextOf({ message: { role: 'user', text: '包一层也认' } }), '包一层也认')
})

test('原话留底：落盘 0600、去重、按时间召回、跨会话、对得上的旧话也带', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dialog-'))
  try {
    const seen = new Map()
    assert.equal(appendDialog(dir, { sid: 'a', text: '简历第二段我想重写', at: T0 - 5 * DAY }, seen), true)
    appendDialog(dir, { sid: 'a', text: '今天好累', at: T0 - 60_000 }, seen)
    assert.equal(appendDialog(dir, { sid: 'a', text: '今天好累', at: T0 - 55_000 }, seen), false)
    appendDialog(dir, { sid: 'b', text: '跟姐姐说一声我晚点交', at: T0 - 30_000 }, seen)
    appendDialog(dir, { sid: 'a', text: '帮我看看简历', at: T0 }, seen)
    assert.equal((statSync(join(dir, 'dialog.jsonl')).mode & 0o777), 0o600)
    const rows = readDialog(dir)
    assert.equal(rows.length, 4)
    const lines = dialogLines(rows, { query: '帮我看看简历', now: T0 + 1000 })
    const texts = lines.map((l) => l.text)
    assert.ok(texts.includes('今天好累'))
    assert.ok(texts.includes('跟姐姐说一声我晚点交'), '另一个会话说的也带')
    assert.ok(texts.includes('简历第二段我想重写'), '五天前对得上的话也带')
    assert.ok(!texts.includes('帮我看看简历'), '刚说的那句不重复')
    assert.match(lines.find((l) => l.text === '今天好累').when, /^今天 /)
    for (let i = 0; i < 20; i++) appendDialog(dir, { sid: 'a', text: '第' + i + '句', at: T0 + i * 1000 })
    assert.equal(trimDialog(dir, 10), 14)
    assert.equal(readDialog(dir).length, 10)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('召回文本带上原话那一块', () => {
  const doc = seed()
  const r = recall(doc, { scope: 'global', query: '嗯', dialog: [{ when: '今天 10:00', text: '我有点不想去面试了' }] }, T0 + DAY)
  assert.match(r.text, /他最近说过的话/)
  assert.match(r.text, /不想去面试/)
})
