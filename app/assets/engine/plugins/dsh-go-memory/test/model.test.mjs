import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  correct, emptyDoc, exportMarkdown, forget, listTopics, markTopicAsked, normalizeScope, pickTopicToAsk,
  recall, remember, resolveTopic, search, scopeOfSession, stats, tokens, upsertTopic, visibleScopes,
} from '../lib/model.js'

const T0 = Date.parse('2026-11-05T10:00:00+08:00')
const DAY = 86_400_000

test('scope：只认 session/workspace，其余归 global', () => {
  assert.equal(normalizeScope('session:abc-1'), 'session:abc-1')
  assert.equal(normalizeScope('workspace:proj~1'), 'workspace:proj~1')
  assert.equal(normalizeScope('sessions:abc'), 'global')
  assert.equal(scopeOfSession(''), 'global')
  assert.deepEqual(visibleScopes('session:x'), ['session:x', 'global'])
  assert.deepEqual(visibleScopes('global'), ['global'])
})

test('remember：同一句话不重复记', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '主人怕吵，晚上十一点后别主动发消息', scope: 'global' }, T0)
  assert.equal(a.ok, true)
  const b = remember(doc, { text: '  主人怕吵，晚上十一点后别主动发消息  ', scope: 'global' }, T0 + 1000)
  assert.equal(b.ok, true)
  assert.equal(b.duplicate, true)
  assert.equal(doc.facts.length, 1)
})

test('remember：同 key 只留最新，旧的可回溯', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '她身高 170', key: '她.身高', scope: 'global' }, T0)
  const b = remember(doc, { text: '她身高 172', key: '她.身高', scope: 'global' }, T0 + DAY)
  assert.equal(b.replaced.length, 1)
  assert.equal(b.replaced[0], a.fact.id)
  const old = doc.facts.find((f) => f.id === a.fact.id)
  assert.equal(old.status, 'superseded')
  const active = doc.facts.filter((f) => f.status === 'active')
  assert.equal(active.length, 1)
  assert.equal(active[0].text, '她身高 172')
})

test('remember：replaces 按原文片段替换', () => {
  const doc = emptyDoc()
  remember(doc, { text: '面试还没开始', scope: 'global' }, T0)
  const r = remember(doc, { text: '面试已经结束了', replaces: '面试还没开始', scope: 'global' }, T0 + 1000)
  assert.equal(r.replaced.length, 1)
  assert.equal(doc.facts.filter((f) => f.status === 'active').length, 1)
})

test('remember：key 与 replaces 指向同一条时不会二次替换', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '工作：自由职业', key: '她.工作', scope: 'global' }, T0)
  const b = remember(doc, { text: '工作：在准备面试', key: '她.工作', replaces: a.fact.id, scope: 'global' }, T0 + 1)
  assert.deepEqual(b.replaced, [a.fact.id])
  assert.equal(doc.facts.filter((f) => f.status === 'active').length, 1)
})

test('remember：forget 之后同一句话默认拒绝，force 才能写回', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '她喜欢喝冰美式', scope: 'session:s1' }, T0)
  const f = forget(doc, { id: a.fact.id, reason: '她说别记' }, T0 + 1)
  assert.equal(f.ok, true)
  const blocked = remember(doc, { text: '她喜欢喝冰美式', scope: 'session:s1' }, T0 + 2)
  assert.equal(blocked.ok, false)
  assert.equal(blocked.tombstone, true)
  const forced = remember(doc, { text: '她喜欢喝冰美式', scope: 'session:s1', force: true }, T0 + 3)
  assert.equal(forced.ok, true)
  assert.equal(doc.tombstones.length, 0)
})

test('作用域：会话只看见自己的 + 全局，all 才跨会话', () => {
  const doc = emptyDoc()
  remember(doc, { text: '会话一的私事', scope: 'session:s1' }, T0)
  remember(doc, { text: '会话二的私事', scope: 'session:s2' }, T0)
  remember(doc, { text: '全局的约定', scope: 'global' }, T0)
  const s1 = search(doc, { scope: 'session:s1' }, T0 + 1)
  assert.equal(s1.total, 2)
  const all = search(doc, { scope: 'all' }, T0 + 1)
  assert.equal(all.total, 3)
})

test('search：中文按 bigram 命中，相关度低的排后面', () => {
  const doc = emptyDoc()
  remember(doc, { text: '下周三要去看牙医', scope: 'global' }, T0)
  remember(doc, { text: '她喜欢下雨天待在室内', scope: 'global' }, T0)
  const r = search(doc, { query: '牙医 下周三', scope: 'global' }, T0 + 1)
  assert.equal(r.total, 1)
  assert.match(r.items[0].text, /牙医/)
})

test('search：不相关的词不返回', () => {
  const doc = emptyDoc()
  remember(doc, { text: '她喜欢下雨天待在室内', scope: 'global' }, T0)
  const r = search(doc, { query: '量子力学', scope: 'global' }, T0 + 1)
  assert.equal(r.items.length, 0)
})

test('correct：写新版本，旧版本保留并标记 superseded', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '她住在杭州', scope: 'global' }, T0)
  const c = correct(doc, { id: a.fact.id, text: '她住在上海' }, T0 + 1)
  assert.equal(c.ok, true)
  assert.notEqual(c.fact.id, a.fact.id)
  const old = doc.facts.find((f) => f.id === a.fact.id)
  assert.equal(old.status, 'superseded')
  assert.equal(old.supersededReason, 'corrected')
  const active = doc.facts.filter((f) => f.status === 'active')
  assert.equal(active.length, 1)
  assert.equal(active[0].text, '她住在上海')
})

test('correct：只改钉住不改文字也能生效', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '约定每天复盘', scope: 'global' }, T0)
  const c = correct(doc, { id: a.fact.id, pinned: true }, T0 + 1)
  assert.equal(c.ok, true)
  assert.equal(c.fact.pinned, true)
})

test('recall：命中的条目累加 hits，旧条目随时间变模糊', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '她对芒果过敏', scope: 'global', importance: 2 }, T0)
  remember(doc, { text: '她最近在准备面试', scope: 'global' }, T0 + 30 * DAY)
  recall(doc, { scope: 'session:s1', query: '芒果' }, T0 + 60 * DAY)
  const hit = doc.facts.find((f) => f.id === a.fact.id)
  assert.equal(hit.hits, 1)
  assert.ok(hit.lastHitAt > 0)
  const oldClarity = recall(doc, { scope: 'global', query: '', limit: 10 }, T0 + 200 * DAY).facts
    .find((f) => f.id === a.fact.id)
  assert.ok(oldClarity)
})

test('recall：注入文本带日期和「不要复述」的约束', () => {
  const doc = emptyDoc()
  remember(doc, { text: '她怕吵', scope: 'session:s1' }, T0)
  upsertTopic(doc, { title: '面试结果', scope: 'session:s1' }, T0)
  const r = recall(doc, { scope: 'session:s1', query: '怕吵' }, T0 + DAY)
  assert.match(r.text, /本地记忆/)
  assert.match(r.text, /面试结果/)
  assert.match(r.text, /不要逐条复述/)
  assert.match(r.text, /2026-11-05/)
})

test('recall：过期条目不再注入', () => {
  const doc = emptyDoc()
  const a = remember(doc, { text: '这周五交稿', scope: 'global', expiresAt: T0 + DAY }, T0)
  assert.equal(doc.facts.find((f) => f.id === a.fact.id).status, 'active')
  const r = recall(doc, { scope: 'global', query: '交稿' }, T0 + 2 * DAY)
  assert.equal(r.facts.length, 0)
})

test('话题：打开、列表、完成、延后、放掉', () => {
  const doc = emptyDoc()
  const t = upsertTopic(doc, { title: '面试结果', detail: '她说下周出结果', scope: 'session:s1' }, T0)
  assert.equal(t.ok, true)
  assert.equal(listTopics(doc, { scope: 'session:s1' }, T0).items.length, 1)
  const done = resolveTopic(doc, { id: t.topic.id, status: 'done' }, T0 + 1)
  assert.equal(done.ok, true)
  assert.equal(done.topic.status, 'done')
  assert.equal(listTopics(doc, { scope: 'session:s1' }, T0 + 1).items.length, 0)
  const defer = resolveTopic(doc, { id: t.topic.id, status: 'deferred' }, T0 + 2)
  assert.equal(defer.topic.status, 'deferred')
  const drop = resolveTopic(doc, { id: t.topic.id, status: 'dropped' }, T0 + 3)
  assert.equal(drop.topic.status, 'dropped')
})

test('话题：同一个标题不重复开', () => {
  const doc = emptyDoc()
  upsertTopic(doc, { title: '面试结果', scope: 'session:s1' }, T0)
  upsertTopic(doc, { title: '面试结果', scope: 'session:s1' }, T0 + 1)
  assert.equal(doc.topics.length, 1)
})

test('话题：冷却与尝试次数上限', () => {
  const doc = emptyDoc()
  const t = upsertTopic(doc, { title: '面试结果', scope: 'session:s1', priority: 2 }, T0)
  assert.ok(pickTopicToAsk(doc, { scope: 'session:s1', cooldownMs: 0 }, T0 + 1))
  markTopicAsked(doc, t.topic.id, T0 + 1, 6 * 3600_000)
  assert.equal(pickTopicToAsk(doc, { scope: 'session:s1', cooldownMs: 0 }, T0 + 2), null)
  assert.ok(pickTopicToAsk(doc, { scope: 'session:s1', cooldownMs: 0 }, T0 + 7 * 3600_000))
})

test('stats：按范围统计', () => {
  const doc = emptyDoc()
  remember(doc, { text: '全局一', scope: 'global', pinned: true }, T0)
  remember(doc, { text: '会话一', scope: 'session:s1' }, T0)
  upsertTopic(doc, { title: '话题', scope: 'session:s1' }, T0)
  const s = stats(doc, { scope: 'session:s1' }, T0)
  assert.equal(s.facts, 2)
  assert.equal(s.pinned, 1)
  assert.equal(s.topics, 1)
})

test('导出 Markdown：包含有效记忆和话题', () => {
  const doc = emptyDoc()
  remember(doc, { text: '她怕吵', scope: 'global', pinned: true }, T0)
  upsertTopic(doc, { title: '面试结果', scope: 'global' }, T0)
  const md = exportMarkdown(doc, T0)
  assert.match(md, /大肥鱼本地记忆/)
  assert.match(md, /\[钉住\] 她怕吵/)
  assert.match(md, /面试结果/)
})

test('tokens：短句也能出检索词', () => {
  assert.ok(tokens('芒果').has('芒果'))
  assert.equal(tokens('的').size, 0)
  assert.ok(tokens('deadline 周五').has('deadline'))
})
