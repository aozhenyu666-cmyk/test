import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { test } from 'node:test'

import { tempHome, seedAll, fishMemory, surfcoreCard, writeJson, NOW, DAY } from './fixtures.mjs'
import {
  adaptStream,
  appendOwn,
  clarityOf,
  correct,
  extractFacts,
  listStreams,
  loadCorrections,
  loadLongTermFull,
  memoryStats,
  readMemory,
  readOwn,
  recall,
  vitality,
} from '../lib/memory.mjs'
import { loadConfig } from '../lib/config.mjs'

test('适配层：fish-memory 的真实形状能读，字段名一个不猜', () => {
  const a = adaptStream(fishMemory())
  assert.equal(a.shape, 'fish-memory')
  assert.equal(a.records.length, 3)
  const r = a.records[0]
  assert.equal(r.id, 'mmvw95m9j274')
  assert.equal(r.fact, 'dshgor4.拦截实现方式', '事实键在原文件里就叫「事实键」')
  assert.equal(r.importance, 70, '0-1 上游映射成 0-100')
  assert.equal(r.importanceScale, '0-1')
  assert.equal(r.at, NOW - 3 * DAY, 'createdAt 解析成毫秒')
  assert.deepEqual(r.tags, ['DSH Go R4', '主动干预', '拦截'])
})

test('适配层：surfcore 的 records[] 也认，importance 是 0-100 不缩放', () => {
  const a = adaptStream(surfcoreCard())
  assert.equal(a.shape, 'surfcore-card')
  assert.equal(a.records[0].importance, 30)
  assert.equal(a.records[0].importanceScale, '0-100')
  assert.equal(a.records[0].clarityRaw, 'clear')
})

test('适配层：不认识的形状如实报 unknown，不瞎猜', () => {
  const a = adaptStream({ hello: 'world' })
  assert.equal(a.shape, 'unknown')
  assert.equal(a.records.length, 0)
  assert.ok(a.problems.length > 0)
  assert.match(a.problems[0], /memories|records/)
})

test('适配层：非对象/空条目不会让整批崩', () => {
  const a = adaptStream({ memories: [null, 42, { text: 'ok' }] })
  assert.equal(a.records.length, 1)
  assert.equal(a.records[0].text, 'ok')
})

test('存储发现：只认 session-*.json，.bak 和别的文件一律不碰', () => {
  const home = tempHome()
  seedAll(home)
  fs.writeFileSync(path.join(home, 'fish-memory', 'session-s1.json.bak'), '{}')
  fs.writeFileSync(path.join(home, 'fish-memory', 'diag.log'), 'x')
  fs.writeFileSync(path.join(home, 'fish-memory', 'fish-memory-config.json'), '{}')
  const s = listStreams()
  assert.equal(s.length, 1, '只有 session-s1.json')
  assert.equal(s[0].sessionId, 's1')
})

test('读不到目录时是 no-fish-memory，不是抛错', () => {
  tempHome()
  const r = readMemory('s1')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'no-fish-memory')
})

test('指定的会话文件存在就读它', () => {
  const home = tempHome()
  seedAll(home)
  writeJson(path.join(home, 'fish-memory', 'session-other.json'), fishMemory())
  const r = readMemory('s1')
  assert.equal(r.ok, true)
  assert.equal(r.sessionId, 's1')
  assert.equal(r.fellBackTo, '', '精确命中就不该说「退回」')
})

test('会话文件不存在时退回最近改动的那个，并且明说退回了', () => {
  const home = tempHome()
  seedAll(home)
  const r = readMemory('does-not-exist')
  assert.equal(r.ok, true)
  assert.equal(r.fellBackTo, 's1')
})

test('衰减：半衰期取自上游 policy.baseHalfLifeDays，且只算不写', () => {
  const v = vitality({ at: NOW - 30 * DAY, reinforcedAt: 0 }, NOW, 30)
  assert.ok(Math.abs(v.score - 0.5) < 0.01, '30 天 = 一个半衰期 → 0.5，实际 ' + v.score)
  assert.equal(v.halfLifeDays, 30)
  const fresh = vitality({ at: NOW }, NOW, 30)
  assert.ok(fresh.score > 0.99)
  const rein = vitality({ at: NOW - 100 * DAY, reinforcedAt: NOW - DAY }, NOW, 30)
  assert.ok(rein.score > 0.97, '按强化时间算，不该还是 0')
})

test('清晰度：上游给了 clarity 就用它的，没给就按保鲜度分档', () => {
  assert.equal(clarityOf({ clarityRaw: 'fuzzy' }, { score: 0.99 }), 'fuzzy')
  assert.equal(clarityOf({ clarityRaw: '' }, { score: 0.8 }), 'vivid')
  assert.equal(clarityOf({ clarityRaw: '' }, { score: 0.5 }), 'clear')
  assert.equal(clarityOf({ clarityRaw: '' }, { score: 0.25 }), 'fuzzy')
  assert.equal(clarityOf({ clarityRaw: '' }, { score: 0.05 }), 'trace')
})

test('短/长期按 config 的时间窗切，事实按可复述的规则抽', () => {
  const home = tempHome()
  seedAll(home)
  const lt = loadLongTermFull(loadConfig(), 's1')
  assert.equal(lt.shortTerm.length, 1, '只有一条是 1 小时内的')
  assert.equal(lt.shortTerm[0].id, 'mfresh')
  assert.ok(lt.all.length >= 4, 'fish 3 条 + surfcore 1 条')
  const facts = extractFacts(lt.all)
  const ids = facts.map((x) => x.id)
  assert.ok(ids.includes('mmvw95m9j274'), '事实键那条必须算')
  assert.ok(ids.includes('mold1'), 'importance 0.85 + owner 标签必须算')
  assert.ok(!ids.includes('mfresh'), 'importance 0.3 无标签不该算')
  assert.match(facts.map((x) => x.why).join('|'), /事实键/)
})

test('两份上游合并时按 事实键 / 正文 去重', () => {
  const home = tempHome()
  seedAll(home, {
    card: surfcoreCard({ records: [{ id: 'dup', text: '在 DSH Go R4 的主动干预系统里，娱乐 App 可以拦住、不只是提醒；红线是绝不劫持返回键。', tags: [], importance: 50 }] }),
  })
  const lt = loadLongTermFull(loadConfig(), 's1')
  const dup = lt.all.filter((r) => r.text.startsWith('在 DSH Go R4'))
  assert.equal(dup.length, 1, '同一句话两份都有一份，合并后只留一条')
})

test('检索：字面命中排在前面，且能解释分数', () => {
  const home = tempHome()
  seedAll(home)
  const lt = loadLongTermFull(loadConfig(), 's1')
  const hits = recall('散热硅脂', lt.all, { limit: 5 })
  assert.ok(hits.length >= 1)
  assert.equal(hits[0].id, 'mfresh')
  assert.ok(hits[0].score > 0)
  assert.ok(hits[0].lexical > 0, '必须能说出是字面命中的')
})

test('检索：完全无关的词返回空，不硬凑', () => {
  const home = tempHome()
  seedAll(home)
  const lt = loadLongTermFull(loadConfig(), 's1')
  const hits = recall('量子色动力学的胶子禁闭', lt.all, { limit: 5 })
  assert.equal(hits.length, 0)
})

test('检索：空查询不返回全部（避免把整库倒进上下文）', () => {
  const home = tempHome()
  seedAll(home)
  const lt = loadLongTermFull(loadConfig(), 's1')
  const hits = recall('', lt.all, { limit: 3 })
  assert.ok(hits.length <= 3)
})

test('纠正层：隐藏后检索不到，但上游文件字节未变', () => {
  const home = tempHome()
  seedAll(home)
  const file = path.join(home, 'fish-memory', 'session-s1.json')
  const before = fs.readFileSync(file, 'utf8')
  correct('mfresh', 'hide')
  const lt = loadLongTermFull(loadConfig(), 's1')
  assert.ok(!lt.all.some((r) => r.id === 'mfresh'), '藏起来的不该在可见列表里')
  assert.equal(lt.hiddenCount, 1)
  assert.equal(fs.readFileSync(file, 'utf8'), before, '上游文件一个字节都不许动')
})

test('纠正层：pin / unpin / rewrite / note 都落盘且可读回', () => {
  const home = tempHome()
  seedAll(home)
  correct('mold1', 'pin')
  correct('mold1', 'rewrite', '我最怕吵，标书别催我')
  correct('mold1', 'note', '她说过两次')
  const lt = loadLongTermFull(loadConfig(), 's1')
  const rec = lt.all.filter((r) => r.id === 'mold1')[0]
  assert.ok(rec.pinned)
  assert.equal(rec.text, '我最怕吵，标书别催我')
  assert.equal(rec.rewritten, true)
  assert.equal(rec.note, '她说过两次')
  correct('mold1', 'unpin')
  assert.ok(!loadCorrections().pin.includes('mold1'))
})

test('纠正层：动作写错会抛错，不会静默吞掉', () => {
  tempHome()
  assert.throws(() => correct('x', 'destroy'), /不认识的动作/)
  assert.throws(() => correct('', 'hide'), /缺少记忆 id/)
  assert.throws(() => correct('x', 'rewrite', '  '), /不能为空/)
})

test('partner 自己的库：同一事实键重复记不会存成两条', () => {
  const home = tempHome()
  seedAll(home)
  const a = appendOwn({ text: '她喜欢安静', fact: '她.偏好.安静' })
  assert.equal(a.duplicate, false)
  const b = appendOwn({ text: '她喜欢安静这件事说过了', fact: '她.偏好.安静' })
  assert.equal(b.duplicate, true)
  assert.equal(b.id, a.id)
  assert.equal(readOwn().memories.length, 1)
  assert.ok(readOwn().memories[0].reinforcedAt, '重复记应该更新强化时间')
})

test('partner 自己的库：并进长期记忆，且标 origin=partner-own', () => {
  const home = tempHome()
  seedAll(home)
  appendOwn({ text: '她讨厌开会前被临时拉去聊需求', importance: 75 })
  const lt = loadLongTermFull(loadConfig(), 's1')
  const own = lt.all.filter((r) => r.origin === 'partner-own')
  assert.equal(own.length, 1)
  assert.equal(own[0].importance, 75)
})

test('partner 自己的库：空内容抛错、超长抛错', () => {
  const home = tempHome()
  seedAll(home)
  assert.throws(() => appendOwn({ text: '   ' }), /不能为空/)
  assert.throws(() => appendOwn({ text: 'x'.repeat(2001) }), /2000/)
})

test('统计：全部是派生量，不存第二份真相', () => {
  const home = tempHome()
  seedAll(home)
  const lt = loadLongTermFull(loadConfig(), 's1')
  const st = memoryStats(lt)
  assert.equal(st.total, lt.all.length)
  assert.ok(st.firstAt <= st.lastAt)
  assert.equal(st.shape, 'fish-memory')
  assert.equal(st.streams, 1)
})

test('上游坏了（JSON 语法错）时是 unreadable，不是崩溃', () => {
  const home = tempHome()
  seedAll(home)
  fs.writeFileSync(path.join(home, 'fish-memory', 'session-s1.json'), '{ broken', 'utf8')
  const r = readMemory('s1')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'unreadable')
})

test('隐私边界：只读白名单文件，屏幕采集目录绝不被读', () => {
  const home = tempHome()
  seedAll(home)
  fs.mkdirSync(path.join(home, 'screen-capture'), { recursive: true })
  fs.writeFileSync(path.join(home, 'screen-capture', 'session-s1.json'), JSON.stringify({ memories: [{ id: 'evil', text: '屏幕内容' }] }))
  const lt = loadLongTermFull(loadConfig(), 's1')
  assert.ok(!lt.all.some((r) => r.id === 'evil'), 'screen-capture 目录绝不该被读')
})
