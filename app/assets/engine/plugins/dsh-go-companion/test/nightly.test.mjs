// dsh-go-companion · B2 夜间整理的单测
//
// 这一层的价值全在「不许自作主张」上，所以断言都盯着边界：
//   · 输入里不能有原文（只有 id/标题/条数/文件名）
//   · 产物必须是严格 JSON，不合格的单条丢掉、整段不合格不算成功
//   · 对象 id 和账本行号解析不到就丢（代码说了算，不是模型说了算）
//   · 每天两次、对话中不跑、单条过期删除、用过续期
//   · 三个去向各有一条真接线
//   · 没有消息出口：源码扫描，nightly.js 不碰 chat/desk，也不写「要说的话」

import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import {
  CARRY_MAX, MISSING_MAX, NIGHTLY_ITEMS_MAX, NIGHTLY_MAX_PER_DAY, NIGHTLY_OBJECTS_MAX,
  NIGHTLY_SLOTS, NIGHTLY_TTL_MS, WHY_MAX,
  carryTexts, cleanNightly, emptyNightly, gapTexts, linkWords, mergeNightly,
  nightlyDue, nightlyInputs, nightlyLines, nightlyLive, nightlySlot, nightlyStatus, nightlySystem, nightlyUser,
  parseNightly, resolveNightly, tidyNightly, touchNightly,
} from '../lib/nightly.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const LIB = join(HERE, '..', 'lib')

const TZ = 'Asia/Shanghai'
const H = 3_600_000
/** 2026-11-11 13:30 北京时间 = 05:30Z。 */
const AT_13 = Date.UTC(2026, 10, 11, 5, 30)
const AT_22 = Date.UTC(2026, 10, 11, 14, 30)

const OBJECTS = [
  { id: 'topic:T1', kind: 'topic', label: '把首页改完' },
  { id: 'fact:F1', kind: 'fact', label: '他不吃香菜' },
  { id: 'event:1000', kind: 'event', label: '一起看了个片子' },
  { id: 'stance:S1', kind: 'stance', label: '这个月先把 App 发出去' },
  { id: 'note:周报.md', kind: 'note', label: '周报.md' },
]

const ENTRIES = [
  { state: 'generated', at: AT_13, text: '今天把首页那版定下来' },
  { state: 'replied', at: AT_13 + 60_000, text: '行，晚上弄' },
  { state: 'generated', at: AT_13 + 2 * H, text: '你又没交' },
]

function inputs(over = {}) {
  return { counts: { generated: 2, replied: 1, none: 0, failed: 0 }, objects: OBJECTS, ledgerLines: 3, ...over }
}

// ---------------------------------------------------------------- 槽位与次数

test('N1 槽位：13 点与 22 点是两个槽，其余时刻不在槽位', () => {
  assert.equal(nightlySlot(AT_13, TZ), 0)
  assert.equal(nightlySlot(AT_22, TZ), 1)
  assert.equal(nightlySlot(Date.UTC(2026, 10, 11, 7, 0), TZ), null) // 15:00 北京
  assert.equal(NIGHTLY_SLOTS.length, 2)
})

test('N2 到点该跑；不在槽位不跑', () => {
  assert.deepEqual(nightlyDue([], AT_13, TZ), { due: true, slot: 0, why: '' })
  assert.equal(nightlyDue([], Date.UTC(2026, 10, 11, 7, 0), TZ).why, 'no-slot')
})

test('N3 同一个槽位一天只跑一次', () => {
  const did = [{ state: 'nightly', at: AT_13, slot: 0 }]
  assert.deepEqual(nightlyDue(did, AT_13 + 10 * 60_000, TZ), { due: false, slot: 0, why: 'ran-slot' })
  // 22 点还是可以跑第二次
  assert.equal(nightlyDue(did, AT_22, TZ).due, true)
})

test('N4 每天上限 2 次，超了就不跑', () => {
  const did = [{ state: 'nightly', at: AT_13, slot: 0 }, { state: 'nightly', at: AT_22, slot: 1 }]
  assert.equal(nightlyDue(did, AT_22, TZ).why, 'daily-cap')
  assert.equal(NIGHTLY_MAX_PER_DAY, 2)
})

test('N5 对话进行中不跑（busy 优先于槽位）', () => {
  assert.deepEqual(nightlyDue([], AT_13, TZ, { busy: true }), { due: false, slot: 0, why: 'in-conversation' })
})

test('N6 昨天的记录不算今天（跨天重置）', () => {
  const yest = [{ state: 'nightly', at: AT_13 - 86_400_000, slot: 0 }]
  assert.equal(nightlyDue(yest, AT_13, TZ).due, true)
})

// ---------------------------------------------------------------- 输入只有索引

test('N7 输入只有 id / 类型 / 短标题，不带笔记原文', () => {
  const inp = nightlyInputs({
    entries: ENTRIES,
    candidates: {
      topics: [{ id: 'T1', title: '把首页改完' }],
      facts: [{ id: 'F1', text: '他不吃香菜' }],
      episodes: [{ at: 1000, text: '一起看了个片子' }],
    },
    stances: { list: [{ id: 'S1', status: 'held', text: '这个月先把 App 发出去' }, { id: 'S2', status: 'kept', text: '已经守住的' }] },
    notes: ['周报.md'],
    tz: TZ,
  }, AT_13)
  const ids = inp.objects.map((o) => o.id)
  assert.deepEqual(ids, ['topic:T1', 'fact:F1', 'event:1000', 'stance:S1', 'note:周报.md'])
  // 结算掉的立场不进索引
  assert.ok(!ids.includes('stance:S2'))
  // 长标题会被切短，不整段搬进去
  const long = nightlyInputs({
    candidates: { topics: [{ id: 'T9', title: 'x'.repeat(200) }] }, tz: TZ,
  }, AT_13)
  assert.ok(long.objects[0].label.length <= 60)
})

test('N8 索引对象有上限，多的不塞', () => {
  const many = Array.from({ length: 100 }, (_, i) => ({ id: 'T' + i, title: 't' + i }))
  const inp = nightlyInputs({ candidates: { topics: many }, tz: TZ }, AT_13)
  assert.equal(inp.objects.length, NIGHTLY_OBJECTS_MAX)
})

test('N9 账本行号上限来自实际行数（carry 的证据要能对上）', () => {
  const inp = nightlyInputs({ entries: ENTRIES, tz: TZ }, AT_13)
  assert.equal(inp.ledgerLines, ENTRIES.length)
  assert.equal(inp.counts.generated, 2)
})

test('N10 prompt 里写了只输出 JSON、不调工具，且带着每个可引用的 id', () => {
  const sys = nightlySystem()
  assert.match(sys, /没有工具/)
  assert.match(sys, /只输出一个 JSON/)
  const user = nightlyUser(inputs())
  for (const o of OBJECTS) assert.ok(user.includes(o.id), '缺 ' + o.id)
  assert.match(user, /每类最多 3 条/)
  assert.match(user, /"links"/)
})

// ---------------------------------------------------------------- 严格 JSON

test('N11 整段不是 JSON 就是失败，不抛异常', () => {
  assert.equal(parseNightly('我想了想，今天没什么好整理的。').ok, false)
  assert.equal(parseNightly('').ok, false)
  assert.equal(parseNightly(null).ok, false)
  assert.equal(parseNightly('{"links": [}').ok, false)
})

test('N12 合格的 JSON 通过，且三类都收', () => {
  const r = parseNightly(JSON.stringify({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都是在改首页' }],
    gaps: [{ about: 'topic:T1', missing: '没说几点交' }],
    carry: [{ text: '他今天答应晚上弄首页', evidence: [1] }],
  }))
  assert.equal(r.ok, true)
  assert.equal(r.value.links.length, 1)
  assert.equal(r.value.gaps.length, 1)
  assert.equal(r.value.carry.length, 1)
})

test('N13 单条不合格只丢那一条，其余照收', () => {
  const r = parseNightly(JSON.stringify({
    links: [
      { a: 'topic:T1', b: 'topic:T1', why: '自己跟自己' }, // a===b 丢
      { a: 'topic:T1', b: 'fact:F1', why: '' },             // why 空丢
      { a: 'topic:T1', b: 'fact:F1', why: '都在改首页' },    // 收
    ],
    gaps: [],
    carry: [],
  }))
  assert.equal(r.ok, true)
  assert.equal(r.value.links.length, 1)
  assert.equal(r.dropped, 2)
})

test('N14 每类最多 3 条，多出来的丢掉（不是截断成半条）', () => {
  const many = Array.from({ length: 6 }, (_, i) => ({ a: 'topic:T1', b: 'fact:F' + i, why: 'w' + i }))
  const r = parseNightly(JSON.stringify({ links: many, gaps: [], carry: [] }))
  assert.equal(r.value.links.length, NIGHTLY_ITEMS_MAX)
  assert.equal(r.dropped, 3)
})

test('N15 超长字段被切到上限，不整段搬', () => {
  const r = parseNightly(JSON.stringify({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: 'w'.repeat(200) }],
    gaps: [{ about: 'topic:T1', missing: 'm'.repeat(200) }],
    carry: [{ text: 'c'.repeat(200), evidence: ['今天说了首页'] }],
  }))
  assert.equal(r.value.links[0].why.length, WHY_MAX)
  assert.equal(r.value.gaps[0].missing.length, MISSING_MAX)
  assert.equal(r.value.carry[0].text.length, CARRY_MAX)
})

test('N16 一条合格的都没有就是失败（不算整理成功）', () => {
  const r = parseNightly(JSON.stringify({ links: [{ a: '', b: '', why: '' }], gaps: [], carry: [] }))
  assert.equal(r.ok, false)
  assert.match(r.problems[0], /一条合格的都没有/)
})

// ---------------------------------------------------------------- 证据绑定

test('N17 对象 id 不在索引里的条目被丢掉', () => {
  const parsed = {
    links: [
      { a: 'topic:T1', b: 'topic:不存在', why: '编的' },
      { a: 'topic:T1', b: 'fact:F1', why: '真的' },
    ],
    gaps: [{ about: 'fact:乱写', missing: '编的' }],
    carry: [],
  }
  const r = resolveNightly(parsed, { objects: OBJECTS, entries: ENTRIES })
  assert.equal(r.links.length, 1)
  assert.equal(r.links[0].b, 'fact:F1')
  assert.equal(r.gaps.length, 0)
  assert.equal(r.dropped, 2)
})

test('N18 carry 的账本行号超出范围就是解析不到，整条丢', () => {
  const r = resolveNightly({
    links: [], gaps: [],
    carry: [{ text: '有证据的', evidence: [1] }, { text: '没证据的', evidence: [999] }],
  }, { objects: OBJECTS, entries: ENTRIES })
  assert.equal(r.carry.length, 1)
  assert.equal(r.carry[0].text, '有证据的')
  assert.deepEqual(r.carry[0].evidence, [1])
  assert.equal(r.dropped, 1)
})

test('N19 证据也可以给一句原话，由代码换成行号；太短的原话不算证据', () => {
  const r = resolveNightly({
    links: [], gaps: [],
    carry: [{ text: '他自己说的', evidence: ['晚上弄'] }],
  }, { objects: OBJECTS, entries: ENTRIES })
  // 三个字太短，对不上就算没有（和 stance.js 的 resolveEvidence 同一个口径）
  assert.equal(r.carry.length, 0)
  assert.equal(r.dropped, 1)

  const ok = resolveNightly({
    links: [], gaps: [],
    carry: [{ text: '他自己说的', evidence: ['行，晚上弄'] }],
  }, { objects: OBJECTS, entries: ENTRIES })
  assert.equal(ok.carry.length, 1)
  assert.deepEqual(ok.carry[0].evidence, [2]) // 第 2 行就是「行，晚上弄」
})

test('N20 解析成功的那一条会带上标签，好让注入和关键词卡直接用', () => {
  const r = resolveNightly({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都在改首页' }],
    gaps: [{ about: 'topic:T1', missing: '没说几点交' }],
    carry: [{ text: '要带上的事', evidence: [1] }],
  }, { objects: OBJECTS, entries: ENTRIES })
  assert.equal(r.links[0].aLabel, '把首页改完')
  assert.equal(r.gaps[0].aboutLabel, '把首页改完')
})

// ---------------------------------------------------------------- 衰减

test('N21 每条带默认 3 天的 until；到期就不算活着', () => {
  const resolved = resolveNightly({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都在改首页' }], gaps: [], carry: [],
  }, { objects: OBJECTS, entries: ENTRIES })
  const { store, added } = mergeNightly(emptyNightly(), resolved, AT_13)
  assert.equal(added, 1)
  assert.equal(store.items[0].until, AT_13 + NIGHTLY_TTL_MS)
  assert.equal(nightlyLive(store, AT_13 + NIGHTLY_TTL_MS - 1).links.length, 1)
  assert.equal(nightlyLive(store, AT_13 + NIGHTLY_TTL_MS + 1).links.length, 0)
})

test('N22 用过的续期，没用过的到期就删', () => {
  const resolved = resolveNightly({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都在改首页' }],
    gaps: [], carry: [{ text: '要带上的事', evidence: [1] }],
  }, { objects: OBJECTS, entries: ENTRIES })
  const { store } = mergeNightly(emptyNightly(), resolved, AT_13)
  const touched = touchNightly(store, ['links|topic:T1|fact:F1'], AT_13 + 2 * 86_400_000)
  // 用过的活到期后 3 天，没用过的按原期
  const link = touched.items.find((i) => i.kind === 'links')
  const carry = touched.items.find((i) => i.kind === 'carry')
  assert.equal(link.until, AT_13 + 2 * 86_400_000 + NIGHTLY_TTL_MS)
  assert.equal(link.used, 1)
  assert.equal(carry.until, AT_13 + NIGHTLY_TTL_MS)
  assert.equal(carry.used, 0)
})

test('N23 同一条再整理一次是刷新而不是堆两条', () => {
  const resolved = resolveNightly({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都在改首页' }], gaps: [], carry: [],
  }, { objects: OBJECTS, entries: ENTRIES })
  const one = mergeNightly(emptyNightly(), resolved, AT_13).store
  const two = mergeNightly(one, resolved, AT_13 + H)
  assert.equal(two.store.items.length, 1)
  assert.equal(two.added, 0)
  assert.equal(two.store.items[0].until, AT_13 + H + NIGHTLY_TTL_MS)
})

test('N24 每类活着的封顶 3 条，留新的', () => {
  let store = emptyNightly()
  for (let i = 0; i < 5; i += 1) {
    const r = resolveNightly({
      links: [{ a: 'topic:T1', b: 'fact:F1', why: 'why' + i }], gaps: [], carry: [],
    }, { objects: OBJECTS, entries: ENTRIES })
    store = mergeNightly(store, r, AT_13 + i * 1000).store
  }
  // keyOf 不含 why，所以同一对只留一条
  assert.equal(store.items.length, 1)
})

// ---------------------------------------------------------------- 三个去向

test('N25 去向一：links 进 B3 的关键词卡', () => {
  const resolved = resolveNightly({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都在改首页' }], gaps: [], carry: [],
  }, { objects: OBJECTS, entries: ENTRIES })
  const store = mergeNightly(emptyNightly(), resolved, AT_13).store
  assert.deepEqual(linkWords(store, AT_13), ['把首页改完', '他不吃香菜'])
  assert.deepEqual(linkWords(store, AT_13 + NIGHTLY_TTL_MS + 1), [])
})

test('N26 去向二：gaps 给 C 组出题', () => {
  const resolved = resolveNightly({
    links: [], gaps: [{ about: 'topic:T1', missing: '没说几点交' }], carry: [],
  }, { objects: OBJECTS, entries: ENTRIES })
  const store = mergeNightly(emptyNightly(), resolved, AT_13).store
  assert.deepEqual(gapTexts(store, AT_13), ['把首页改完：没说几点交'])
})

test('N27 去向三：carry 进随机器 innerOS arm', () => {
  const resolved = resolveNightly({
    links: [], gaps: [], carry: [{ text: '他今天答应晚上弄首页', evidence: [1] }],
  }, { objects: OBJECTS, entries: ENTRIES })
  const store = mergeNightly(emptyNightly(), resolved, AT_13).store
  assert.deepEqual(carryTexts(store, AT_13), ['他今天答应晚上弄首页'])
})

// ---------------------------------------------------------------- 注入与面板

test('N28 注入那一行写明「不是要你现在说出去的话」；空池子返回空串不占预算', () => {
  const resolved = resolveNightly({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都在改首页' }],
    gaps: [{ about: 'topic:T1', missing: '没说几点交' }],
    carry: [{ text: '他今天答应晚上弄首页', evidence: [1] }],
  }, { objects: OBJECTS, entries: ENTRIES })
  const store = mergeNightly(emptyNightly(), resolved, AT_13).store
  const line = nightlyLines(store, AT_13)
  assert.match(line, /不是要你现在说出去的话/)
  assert.ok(line.length <= 240)
  assert.equal(nightlyLines(emptyNightly(), AT_13), '')
})

test('N29 面板：今天跑了几次、还剩几条活着', () => {
  const resolved = resolveNightly({
    links: [{ a: 'topic:T1', b: 'fact:F1', why: '都在改首页' }], gaps: [], carry: [],
  }, { objects: OBJECTS, entries: ENTRIES })
  const store = mergeNightly(emptyNightly(), resolved, AT_13).store
  const st = nightlyStatus(store, [{ state: 'nightly', at: AT_13, slot: 0 }], AT_13, TZ)
  assert.equal(st.today, 1)
  assert.equal(st.max, 2)
  assert.deepEqual(st.live, { links: 1, gaps: 0, carry: 0 })
})

// ---------------------------------------------------------------- 模型通道与硬边界

test('N30 模型通道走 llm.stream：拿到 JSON 就解析，解析不出来不算成功', async () => {
  const seen = []
  const stream = async function* (req) {
    seen.push(req)
    yield { type: 'text-delta', text: '{"links":[{"a":"topic:T1","b":"fact:F1","why":"都在改首页"}],' }
    yield { type: 'text-delta', text: '"gaps":[],"carry":[]}' }
  }
  const r = await tidyNightly({ stream, provider: 'p', model: 'm', inputs: inputs() })
  assert.equal(r.ok, true)
  assert.equal(r.value.links.length, 1)
  assert.equal(seen.length, 1)
  // 一次调用、temperature 0、system 是整理的身份，不是聊天
  assert.equal(seen[0].provider, 'p')
  assert.equal(seen[0].temperature, 0)
  assert.match(seen[0].system, /不是发给谁的消息/)
  assert.equal(seen[0].messages.length, 1)
})

test('N31 没有模型路由时直接返回失败，不抛', async () => {
  const r = await tidyNightly({ stream: undefined, provider: 'p', model: 'm', inputs: inputs() })
  assert.equal(r.ok, false)
  const r2 = await tidyNightly({ stream: async function* () {}, provider: '', model: '', inputs: inputs() })
  assert.equal(r2.ok, false)
})

test('N32 模型报错 / 超时返回失败（不重试、不落盘）', async () => {
  const boom = async function* () {
    yield { type: 'finish', reason: { kind: 'error', error: { message: 'x' } } }
  }
  assert.equal((await tidyNightly({ stream: boom, provider: 'p', model: 'm', inputs: inputs() })).ok, false)
  const slow = async function* () { /* 永不产出 */ }
  const r = await tidyNightly({ stream: slow, provider: 'p', model: 'm', inputs: inputs(), timeoutMs: 20 })
  assert.equal(r.ok, false)
})

test('N33 无消息出口：nightly.js 不 import chat/desk，不写要说的话', () => {
  const src = readFileSync(join(LIB, 'nightly.js'), 'utf8')
  assert.ok(!/from '\.\/core\.js'/.test(src), '不许 import core.js（Desk 在那里）')
  assert.ok(!/chat\.request|desk\.request|\.wait\(/.test(src), '不许走会话通道')
  // muse.js 那四条硬边界不动：两边互不 import
  const muse = readFileSync(join(LIB, 'muse.js'), 'utf8')
  assert.ok(!muse.includes('nightly'), 'muse.js 不该知道夜间整理')
  assert.ok(!/from '\.\/muse\.js'/.test(src), 'nightly.js 不该依赖 muse.js')
})

test('N34 muse.js 那四条边界还在：仍是纯函数、不调模型', () => {
  const src = readFileSync(join(LIB, 'muse.js'), 'utf8')
  assert.ok(!/llm|stream\(|fetch\(/.test(src), 'muse.js 不许调模型')
})

test('N35 空 store 也能安全地问每一路', () => {
  const s = cleanNightly(null)
  assert.deepEqual(s, emptyNightly())
  assert.deepEqual(nightlyLive(s, AT_13), { links: [], gaps: [], carry: [] })
  assert.deepEqual(linkWords(s, AT_13), [])
  assert.deepEqual(gapTexts(s, AT_13), [])
  assert.deepEqual(carryTexts(s, AT_13), [])
  assert.equal(nightlyLines(s, AT_13), '')
})
