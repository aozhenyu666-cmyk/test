// cap-ledger 纯内核测试：四态判定、规整、计数、序列化。零依赖、零 IO。
import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  STATES, describeEntry, deserialize, emptyEntry, emptyLedger, entryOf, looksInstalled,
  markInstalled, markLoaded, markRun, markToolRegistered, normalizeEntry, normalizeLedger,
  putEntry, serialize, summarize,
} from '../lib/ledger.mjs'

const NOW = 1_700_000_000_000

// ── 1. 四个状态必须分得开 ───────────────────────────────────────────────────
test('STATES 是六个（顺序 = 面板显示顺序）', () => {
  // 硬化轮：从四态扩到六态。装没装、加没加载、注册没注册、现在能不能用、真跑过没、失败没失败 ——
  // 六件不同的事，任何一个塌了都不该被另外五个掩盖。
  assert.deepEqual(STATES, ['installed', 'loaded', 'toolRegistered', 'available', 'executed', 'failed'])
})

test('空条目：四态都是 null（未知），不是 false 也不是 true', () => {
  const e = emptyEntry('x')
  assert.equal(e.plugin, 'x')
  for (const k of STATES) assert.equal(e[k], null, k + ' 应该是 null')
  assert.equal(e.runs, 0)
  assert.equal(e.fails, 0)
  assert.equal(e.lastRunAt, null)
  assert.equal(e.lastRunCode, null)
})

test('四态可以两两组合，互相不被覆盖', () => {
  const e = emptyEntry('p')
  markInstalled(e, { ok: true, at: NOW })
  assert.deepEqual([e.installed, e.loaded, e.toolRegistered, e.lastRunOk], [true, null, null, null])

  markLoaded(e, { at: NOW })
  assert.deepEqual([e.installed, e.loaded, e.toolRegistered, e.lastRunOk], [true, true, null, null])

  markToolRegistered(e, { ok: true, at: NOW })
  assert.deepEqual([e.installed, e.loaded, e.toolRegistered, e.lastRunOk], [true, true, true, null])

  // 前三态齐了但还没跑过 —— 这是最常见的正常状态，不能被当成失败
  assert.equal(e.lastRunOk, null)
  const d = describeEntry(e)
  assert.equal(d.lastRunOk, '还没跑过')
  assert.equal(d.usable, true)
  assert.equal(d.healthy, true, '还没跑过不算不健康')

  markRun(e, { ok: true, at: NOW })
  assert.deepEqual([e.installed, e.loaded, e.toolRegistered, e.lastRunOk], [true, true, true, true])
  assert.equal(e.runs, 1)
  assert.equal(e.fails, 0)
})

test('工具注册失败：toolRegistered=false **并且**原因落进 lastRun*', () => {
  const e = emptyEntry('p')
  markInstalled(e, { ok: true, at: NOW })
  markLoaded(e, { at: NOW })
  markToolRegistered(e, { ok: false, error: 'schema 被拒', at: NOW })

  assert.equal(e.toolRegistered, false)
  assert.equal(e.lastRunOk, false, '注册失败本身就是一次不成功')
  assert.equal(e.lastRunCode, 'E_REGISTER')
  assert.match(e.lastRunMessage, /schema 被拒/)
  assert.equal(e.runs, 1)
  assert.equal(e.fails, 1)

  const d = describeEntry(e)
  assert.equal(d.toolRegistered, '否')
  assert.equal(d.healthy, false)
  assert.equal(d.usable, false, '工具没注册上就是不能用')
})

test('describeEntry：三种 lastRunOk 说法完全不同', () => {
  const e = emptyEntry('p')
  e.installed = true
  e.loaded = true
  e.toolRegistered = true

  assert.equal(describeEntry(e).lastRunOk, '还没跑过')
  markRun(e, { ok: true, at: NOW })
  assert.equal(describeEntry(e).lastRunOk, '成功')
  markRun(e, { ok: false, code: 'E_TIMEOUT', message: 'x', at: NOW })
  assert.equal(describeEntry(e).lastRunOk, '失败（E_TIMEOUT）')
})

test('describeEntry：null 写成「未知」，不猜成「否」', () => {
  const d = describeEntry(emptyEntry('p'))
  assert.equal(d.installed, '未知')
  assert.equal(d.loaded, '未知')
  assert.equal(d.toolRegistered, '未知')
  assert.equal(d.healthy, false, '未知一律判不健康（不报假绿）')
  assert.equal(d.usable, false)
})

// ── 2. 执行记账 ─────────────────────────────────────────────────────────────
test('markRun：成功会清掉上一次的错误码（不残留）', () => {
  const e = emptyEntry('p')
  markRun(e, { ok: false, code: 'E_NETWORK', message: '断了', at: NOW })
  assert.equal(e.lastRunCode, 'E_NETWORK')
  assert.equal(e.fails, 1)

  markRun(e, { ok: true, at: NOW + 1 })
  assert.equal(e.lastRunOk, true)
  assert.equal(e.lastRunCode, null)
  assert.equal(e.lastRunMessage, null)
  assert.equal(e.runs, 2)
  assert.equal(e.fails, 1, '历史失败次数不该被抹掉')
})

test('markRun：失败必须带原因（没给就补一个，不留空）', () => {
  const e = emptyEntry('p')
  markRun(e, { ok: false })
  assert.equal(e.lastRunCode, 'E_UNKNOWN')
  assert.equal(typeof e.lastRunMessage, 'string')
  assert.ok(e.lastRunMessage.length > 0)
})

test('markRun：连跑 5 次 2 次失败 —— 计数与最近一次都对', () => {
  const e = emptyEntry('p')
  const results = [true, false, true, false, true]
  results.forEach((ok, i) => markRun(e, { ok, ...(ok ? {} : { code: 'E_HTTP', message: 'HTTP 503' }), at: NOW + i }))
  assert.equal(e.runs, 5)
  assert.equal(e.fails, 2)
  assert.equal(e.lastRunOk, true)
  assert.equal(e.lastRunAt, NOW + 4)
})

// ── 3. 规整：不信任何输入 ───────────────────────────────────────────────────
test('normalizeEntry：类型不对退回默认，不抛', () => {
  assert.deepEqual(normalizeEntry(null, 'x'), emptyEntry('x'))
  assert.deepEqual(normalizeEntry('boom', 'x'), emptyEntry('x'))
  const fixed = normalizeEntry({ plugin: 'p', installed: 'yes', loaded: 1, toolRegistered: true, runs: -3, fails: 10 }, 'p')
  assert.equal(fixed.installed, null, '不是布尔就写 null，不猜成 true')
  assert.equal(fixed.loaded, null)
  assert.equal(fixed.toolRegistered, true)
  assert.equal(fixed.runs, 0, '负数不是一个合法计数')
  assert.equal(fixed.fails, 0)
})

test('normalizeEntry：fails 不可能多于 runs', () => {
  const e = normalizeEntry({ plugin: 'p', runs: 2, fails: 9 }, 'p')
  assert.equal(e.runs, 2)
  assert.equal(e.fails, 2)
})

test('normalizeEntry：runs=0 时 lastRun* 必须全空（「没跑过」不能被写成失败）', () => {
  const e = normalizeEntry({ plugin: 'p', runs: 0, lastRunOk: false, lastRunCode: 'E_X', lastRunAt: NOW, lastRunMessage: 'm' }, 'p')
  assert.equal(e.lastRunOk, null)
  assert.equal(e.lastRunCode, null)
  assert.equal(e.lastRunAt, null)
  assert.equal(e.lastRunMessage, null)
})

test('normalizeEntry：失败记录缺 code 时补 E_UNKNOWN（不许留一个光秃秃的 false）', () => {
  const e = normalizeEntry({ plugin: 'p', runs: 1, fails: 1, lastRunOk: false, lastRunAt: NOW }, 'p')
  assert.equal(e.lastRunCode, 'E_UNKNOWN')
})

test('normalizeLedger：版本不对 / 形状不对 → 整份退回空', () => {
  assert.deepEqual(normalizeLedger(null), emptyLedger())
  assert.deepEqual(normalizeLedger({ v: 1, plugins: { a: {} } }), emptyLedger(), '旧版本不硬套')
  assert.deepEqual(normalizeLedger({ plugins: [] }), emptyLedger(), 'plugins 不是对象')
  const good = normalizeLedger({ v: 2, plugins: { a: { plugin: 'a', installed: true } } })
  assert.equal(good.plugins.a.installed, true)
})

test('putEntry / entryOf：写入后能取回，取不存在的给空条目（不自动写入）', () => {
  const l = emptyLedger()
  assert.deepEqual(entryOf(l, 'nope'), emptyEntry('nope'))
  assert.equal(Object.keys(l.plugins).length, 0, 'entryOf 不能有副作用')

  const e = emptyEntry('nope')
  markInstalled(e, { ok: true, at: NOW })
  putEntry(l, e)
  assert.equal(entryOf(l, 'nope').installed, true)
  assert.equal(l.updatedAt, NOW)
})

// ── 4. 汇总与序列化 ─────────────────────────────────────────────────────────
test('summarize：total / healthy / failing 三个数各自独立', () => {
  const l = { v: 2, plugins: {} }
  const a = emptyEntry('a'); a.installed = true; a.loaded = true; a.toolRegistered = true; markRun(a, { ok: true, at: NOW })
  const b = emptyEntry('b'); b.installed = true; b.loaded = true; b.toolRegistered = true; markRun(b, { ok: false, code: 'E_X', message: 'm', at: NOW })
  const c = emptyEntry('c'); c.installed = true; c.loaded = true; c.toolRegistered = false
  const d = emptyEntry('d') // 什么都没登记
  putEntry(l, a); putEntry(l, b); putEntry(l, c); putEntry(l, d)

  const s = summarize(l)
  assert.equal(s.total, 4)
  assert.equal(s.healthy, 1, '只有 a 是四态齐全且上次成功')
  assert.equal(s.failing, 1, '只有 b 上次失败')
})

test('serialize / deserialize：往返一致，坏文本给空台账', () => {
  const l = emptyLedger()
  const e = emptyEntry('p')
  markInstalled(e, { ok: true, at: NOW })
  markLoaded(e, { at: NOW })
  markToolRegistered(e, { ok: true, at: NOW })
  markRun(e, { ok: false, code: 'E_TIMEOUT', message: '超时', at: NOW })
  putEntry(l, e)

  const round = deserialize(serialize(l))
  assert.deepEqual(round.plugins.p, l.plugins.p)
  assert.deepEqual(deserialize('{ 坏'), emptyLedger())
  assert.deepEqual(deserialize(''), emptyLedger())
  assert.deepEqual(deserialize(null), emptyLedger())
})

test('looksInstalled：只看文件在不在，不猜能不能跑', () => {
  const present = new Set(['/pkg/package.json', '/pkg/lib/index.js'])
  const exists = (p) => present.has(p)
  assert.equal(looksInstalled(exists, '/pkg/', './lib/index.js'), true)
  assert.equal(looksInstalled(exists, '/pkg', 'lib/index.js'), true)
  assert.equal(looksInstalled(exists, '/nope', 'lib/index.js'), false, '缺 package.json 就是没装')
  assert.equal(looksInstalled(exists, '/pkg', 'lib/missing.js'), false, '入口文件不在也算没装')
  assert.equal(looksInstalled(exists, '/pkg', ''), true, '没声明 main 时，有 package.json 就算')
  assert.equal(looksInstalled(exists, '', 'x'), false)
  assert.equal(looksInstalled(null, '/pkg', 'x'), false, '注入的 exists 必须是函数')
})
