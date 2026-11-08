// cap-ledger 插件层测试：加载 / 工具注册 / 工具调用 / 台账四态真的分开 / 落盘 / 桥接。
// 全部用假 ctx + 假 fs，不碰真 home、不碰网络。
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path, { join } from 'node:path'

import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { createTestHost } from '../../../../sdk/plugin-kit/lib/testing.js'
import { checkView } from '../../../../sdk/plugin-kit/lib/view-check.js'
import { apply as applyLedger, SELF_PLUGIN, ledgerPathFor } from '../lib/index.js'
import { describeEntry, emptyEntry } from '../lib/ledger.mjs'
import { ledgerPanel, stripData, validate } from '../lib/views.mjs'

const DIR = fileURLToPath(new URL('..', import.meta.url))
const NOW = 1_700_000_000_000

/** 最小假 ctx：能不能给 tools / 桥接 / effect 由参数决定。 */
function fakeCtx(shape = {}) {
  const tools = new Map()
  const registrations = []
  const ctx = {
    inject(names, cb) {
      if (!names.includes('dshGo')) return
      if (shape.bridge === false) return
      const dshGo = {
        strip: (id, dataFn) => { registrations.push({ kind: 'strip', id, dataFn }); return () => {} },
        panel: (id, def) => { registrations.push({ kind: 'panel', id, def }) },
        refresh: () => {},
      }
      cb({ dshGo })
    },
    on() {},
    effect: shape.effect === false ? undefined : (fn) => { const d = fn(); return () => { if (typeof d === 'function') d() } },
    get: (n) => (n === 'tools' && shape.tools !== false ? ctx.tools : undefined),
  }
  if (shape.tools !== false) {
    ctx.tools = {
      register: (def) => {
        if (shape.registerThrows === true) throw new TypeError('tool "' + def.name + '" must declare output { schema, render }')
        tools.set(def.name, def)
        return () => tools.delete(def.name)
      },
    }
  }
  return { ctx, tools, registrations }
}

const tmpFile = () => join(mkdtempSync(join(tmpdir(), 'cap-ledger-')), 'ledger.json')

// ── 1. 清单 ─────────────────────────────────────────────────────────────────
test('manifest: dshGo.v=1、id 唯一、strip 指的面板存在、零第三方依赖', () => {
  const pkg = JSON.parse(readFileSync(join(DIR, 'package.json'), 'utf8'))
  assert.equal(pkg.dshGo.v, 1)
  assert.equal(pkg.name, 'dsh-go-cap-ledger')
  assert.deepEqual(pkg.dependencies, {})
  assert.deepEqual(pkg.peerDependencies, {})

  const c = pkg.dshGo.contributes
  assert.ok(c['composer.strip'].length <= 1)
  assert.ok(c.panels.length <= 4)
  const ids = []
  for (const list of Object.values(c)) for (const it of list) ids.push(it.id)
  assert.equal(new Set(ids).size, ids.length)
  for (const p of c.panels) {
    const title = typeof p.title === 'string' ? p.title : p.title.zh
    assert.ok(title.length <= 16)
  }
  const panelIds = new Set(c.panels.map((p) => p.id))
  for (const s of c['composer.strip']) if (s.panel) assert.ok(panelIds.has(s.panel))
  assert.ok(Buffer.byteLength(JSON.stringify(pkg.dshGo)) <= 16 * 1024)

  const patch = readFileSync(join(DIR, 'cordis.patch.yml'), 'utf8')
  assert.ok(patch.includes('name: ' + pkg.name))
})

test('manifest: 包名常量与 package.json 一致（台账自己要登记自己）', () => {
  const pkg = JSON.parse(readFileSync(join(DIR, 'package.json'), 'utf8'))
  assert.equal(SELF_PLUGIN, pkg.name)
})

// ── 2. 加载：自己那条台账 ───────────────────────────────────────────────────
test('加载：apply 后自己那条台账 installed/loaded 都是 true', () => {
  const { ctx } = fakeCtx()
  const h = applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  const self = h.api.get(SELF_PLUGIN)
  assert.equal(self.installed, '是')
  assert.equal(self.loaded, '是')
  assert.equal(self.toolRegistered, '是', '工具注册成功')
  assert.equal(self.lastRunOk, '还没跑过', '注册成功 ≠ 跑过一次')
  assert.equal(self.healthy, true)
})

test('加载：工具 cap_ledger_status 注册成功（register 返回了 disposer）', () => {
  const { ctx, tools } = fakeCtx()
  const h = applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  assert.ok(tools.has('cap_ledger_status'))
  assert.equal(h.api.get(SELF_PLUGIN).toolRegistered, '是')
})

test('加载：没有 ctx.tools —— 不崩，toolRegistered=false，但那不算一次执行失败', () => {
  const { ctx } = fakeCtx({ tools: false })
  let h
  assert.doesNotThrow(() => { h = applyLedger(ctx, { now: () => NOW, ledgerPath: null }) })
  const self = h.api.get(SELF_PLUGIN)
  assert.equal(self.installed, '是')
  assert.equal(self.loaded, '是')
  assert.equal(self.toolRegistered, '否')
  assert.equal(self.lastRunOk, '还没跑过', '宿主没工具能力不是插件的执行失败')
  assert.equal(h.ledger.plugins[SELF_PLUGIN].runs, 0)
})

test('加载：register 抛错 —— toolRegistered=false，原因进 lastRun*', () => {
  const { ctx } = fakeCtx({ registerThrows: true })
  let h
  assert.doesNotThrow(() => { h = applyLedger(ctx, { now: () => NOW, ledgerPath: null }) })
  const entry = h.ledger.plugins[SELF_PLUGIN]
  assert.equal(entry.toolRegistered, false)
  assert.equal(entry.lastRunOk, false)
  assert.equal(entry.lastRunCode, 'E_REGISTER')
  assert.match(entry.lastRunMessage, /must declare output/)
  assert.equal(describeEntry(entry).healthy, false)
})

test('加载：没有 ctx.effect / 没有桥接，都不崩', () => {
  const a = fakeCtx({ effect: false })
  assert.doesNotThrow(() => applyLedger(a.ctx, { ledgerPath: null }))
  const b = fakeCtx({ bridge: false })
  const h = applyLedger(b.ctx, { ledgerPath: null })
  assert.equal(b.registrations.length, 0, '没桥接就不注册原生界面')
  assert.equal(h.api.get(SELF_PLUGIN).loaded, '是')
})

// ── 3. 工具调用 ─────────────────────────────────────────────────────────────
test('工具：返回四项状态 + 汇总，且这次执行被记进 lastRunOk', async () => {
  const { ctx, tools } = fakeCtx()
  const h = applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  h.api.register('dsh-go-other', { installed: true, loaded: true, toolRegistered: true })

  const tool = tools.get('cap_ledger_status')
  const value = await tool.execute({}, {})

  assert.equal(value.ok, true)
  assert.equal(value.summary.total, 2, '自己和 other')
  const other = value.plugins.find((p) => p.plugin === 'dsh-go-other')
  assert.equal(other.installed, true)
  assert.equal(other.loaded, true)
  assert.equal(other.toolRegistered, true)
  assert.equal(Object.hasOwn(other, 'lastRunOk'), false, '没跑过的插件不该有 lastRunOk 字段')

  const self = h.ledger.plugins[SELF_PLUGIN]
  assert.equal(self.lastRunOk, true)
  assert.equal(self.runs, 1)
})

test('工具：按插件名过滤；查不到 -> ok:false + E_NOT_FOUND', async () => {
  const { ctx, tools } = fakeCtx()
  applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  const tool = tools.get('cap_ledger_status')

  const hit = await tool.execute({ plugin: SELF_PLUGIN }, {})
  assert.equal(hit.ok, true)
  assert.equal(hit.plugins.length, 1)

  const miss = await tool.execute({ plugin: '不存在的' }, {})
  assert.equal(miss.ok, false)
  assert.deepEqual(miss.plugins, [])
})

test('工具：失败者的 code 与原因原样出现在结果里（这就是台账的意义）', async () => {
  const { ctx, tools } = fakeCtx()
  const h = applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  h.api.register('bad', { installed: true, loaded: true, toolRegistered: true })
  h.api.record('bad', { ok: false, code: 'E_TIMEOUT', message: '请求超时：超过 12000 ms' })

  const value = await tools.get('cap_ledger_status').execute({}, {})
  const bad = value.plugins.find((p) => p.plugin === 'bad')
  assert.equal(bad.lastRunOk, false)
  assert.equal(bad.lastRunCode, 'E_TIMEOUT')
  assert.match(bad.lastRunMessage, /12000/)
  assert.equal(bad.healthy, false)
  assert.equal(value.summary.failing, 1)
})

test('工具：输出是无损 JSON + 有 render 和 presentationMeta（注册表要求）', async () => {
  const { ctx, tools } = fakeCtx()
  applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  const tool = tools.get('cap_ledger_status')

  assert.equal(typeof tool.output.render, 'function')
  assert.equal(typeof tool.output.presentationMeta, 'function')
  const value = await tool.execute({}, {})
  assert.deepEqual(JSON.parse(JSON.stringify(value)), value)

  const text = tool.output.render({}, value)[0].text
  assert.match(text, /Capability ledger:/)
  assert.ok(text.includes(SELF_PLUGIN))

  const meta = tool.output.presentationMeta({}, value)
  assert.deepEqual(Object.keys(meta), ['dsh-go/view'])
  assert.ok(Buffer.byteLength(JSON.stringify(meta), 'utf8') < 8 * 1024)
})

test('工具：没有登记时也不崩（返回空列表 + summary）', async () => {
  const { ctx, tools } = fakeCtx({ tools: false })
  // tools:false 时没有工具，这里换个方式：先注册再清空
  const c = fakeCtx()
  const h = applyLedger(c.ctx, { now: () => NOW, ledgerPath: null })
  // 手动清掉非自己的条目
  for (const name of Object.keys(h.ledger.plugins)) if (name !== SELF_PLUGIN) delete h.ledger.plugins[name]
  const value = await c.tools.get('cap_ledger_status').execute({}, {})
  assert.equal(value.ok, true)
  assert.equal(value.plugins.length, 1, '至少还有自己那条')
})

// ── 4. 对外 API：别的插件登记自己 ───────────────────────────────────────────
test('API：register 只写 installed/loaded，不动 lastRun*', () => {
  const { ctx } = fakeCtx()
  const h = applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  const d = h.api.register('p', { installed: true, loaded: true })
  assert.equal(d.installed, '是')
  assert.equal(d.loaded, '是')
  assert.equal(d.toolRegistered, '未知')
  assert.equal(d.lastRunOk, '还没跑过')
  assert.equal(d.healthy, false, '工具没注册上就不算健康')
})

test('API：record 五次两个失败 —— 计数与最近一次都对', () => {
  const { ctx } = fakeCtx()
  const h = applyLedger(ctx, { now: () => NOW, ledgerPath: null })
  h.api.register('p', { installed: true, loaded: true, toolRegistered: true })
  ;[true, false, true, false, true].forEach((ok, i) => h.api.record('p', { ok, ...(ok ? {} : { code: 'E_HTTP', message: 'HTTP 503' }), at: NOW + i }))

  const entry = h.ledger.plugins.p
  assert.equal(entry.runs, 5)
  assert.equal(entry.fails, 2)
  assert.equal(entry.lastRunOk, true)
  assert.equal(h.api.get('p').healthy, true)

  h.api.record('p', { ok: false, code: 'E_BIZ', message: '视频不存在', at: NOW + 9 })
  assert.equal(h.api.get('p').lastRunOk, '失败（E_BIZ）')
  assert.equal(h.api.get('p').healthy, false)
  assert.equal(h.api.summary().failing, 1)
})

// ── 5. 落盘 ─────────────────────────────────────────────────────────────────
test('落盘：重启后四态与失败原因都还在', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cap-ledger-disk-'))
  const file = join(dir, 'ledger.json')
  try {
    const a = fakeCtx()
    const h1 = applyLedger(a.ctx, { now: () => NOW, ledgerPath: file })
    h1.api.register('p', { installed: true, loaded: true, toolRegistered: true })
    h1.api.record('p', { ok: false, code: 'E_NO_FETCH', message: '这个宿主没有 fetch', at: NOW })
    assert.equal(h1.save(), true)

    const b = fakeCtx()
    const h2 = applyLedger(b.ctx, { now: () => NOW, ledgerPath: file })
    const d = h2.api.get('p')
    assert.equal(d.installed, '是')
    assert.equal(d.loaded, '是')
    assert.equal(d.toolRegistered, '是')
    assert.equal(d.lastRunOk, '失败（E_NO_FETCH）')
    assert.equal(h2.ledger.plugins.p.lastRunMessage, '这个宿主没有 fetch')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('落盘：坏文件不影响启动', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cap-ledger-bad-'))
  const file = join(dir, 'ledger.json')
  writeFileSync(file, '{ 坏掉的 json')
  try {
    const { ctx } = fakeCtx()
    let h
    assert.doesNotThrow(() => { h = applyLedger(ctx, { now: () => NOW, ledgerPath: file }) })
    assert.equal(h.api.get(SELF_PLUGIN).loaded, '是', '插件仍然起来了')
    assert.equal(Object.keys(h.ledger.plugins).length, 1, '只有自己那条新建的记录')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('落盘：DSH_HOME 是延迟读的（不是 import 那一刻定死）', () => {
  assert.equal(ledgerPathFor({ DSH_HOME: '/tmp/x' }), path.join('/tmp/x', 'cache', 'dsh-go-cap-ledger', 'ledger.json'))
  const a = ledgerPathFor({ DSH_HOME: 'D:/a' })
  const b = ledgerPathFor({ DSH_HOME: 'D:/b' })
  assert.notEqual(a, b, '换 DSH_HOME 要换路径')
  assert.ok(ledgerPathFor({}).includes('.dsh'))
})

// ── 6. 视图 ─────────────────────────────────────────────────────────────────
test('视图：面板把四态逐条写出来，不是只显示插件名', () => {
  const ledger = {
    v: 2,
    plugins: {
      ok: { plugin: 'ok', installed: true, loaded: true, toolRegistered: true, lastRunOk: true, lastRunAt: NOW, runs: 3, fails: 0 },
      broken: { plugin: 'broken', installed: true, loaded: true, toolRegistered: false, lastRunOk: false, lastRunAt: NOW, lastRunCode: 'E_REGISTER', lastRunMessage: 'schema 被拒', runs: 1, fails: 1 },
    },
  }
  const view = ledgerPanel({ ledger, now: NOW })
  const r = validate(view, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  const s = JSON.stringify(view)
  for (const key of ['installed', 'loaded', 'toolRegistered', 'lastRunOk']) assert.ok(s.includes(key), '缺 ' + key)
  assert.ok(s.includes('schema 被拒'), '失败原因要出现在面板上')
  assert.ok(s.includes('E_REGISTER'))
})

test('视图：空台账也是合法视图 + 一句人话', () => {
  const view = ledgerPanel({ ledger: { v: 2, plugins: {} }, now: NOW })
  assert.ok(validate(view, 'panel').ok)
  assert.ok(JSON.stringify(view).includes('还没有任何记录'))
})

test('视图：25 个插件不超预算（靠截断）', () => {
  const plugins = {}
  for (let i = 0; i < 25; i++) plugins['p' + i] = { plugin: 'p' + i, installed: true, loaded: true, toolRegistered: true, lastRunOk: true, lastRunAt: NOW, runs: 1, fails: 0 }
  const r = validate(ledgerPanel({ ledger: { v: 2, plugins }, now: NOW }), 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  assert.ok(r.nodes <= 120)
})

test('视图：状态条一眼能看出有没有事（三种情况三种话）', () => {
  const good = stripData({ ledger: { v: 2, plugins: { a: { plugin: 'a', installed: true, loaded: true, toolRegistered: true, lastRunOk: true, runs: 1, fails: 0 } } } })
  assert.ok(good.text.includes('能用 1/1'))
  assert.equal(good.tone, 'good')

  const bad = stripData({ ledger: { v: 2, plugins: { a: { plugin: 'a', installed: true, loaded: true, toolRegistered: true, lastRunOk: false, lastRunCode: 'E_X', runs: 1, fails: 1 } } } })
  assert.ok(bad.text.includes('失败'))
  assert.equal(bad.tone, 'warn')

  const empty = stripData({ ledger: { v: 2, plugins: {} } })
  assert.ok(empty.text.length > 0)

  for (const d of [good, bad, empty]) {
    assert.ok([...d.text].length <= 24, d.text)
    assert.ok(validate(d, 'strip').ok)
  }
})

test('视图：App 解析器孪生对面板零 error', () => {
  const ledger = { v: 2, plugins: { ok: { plugin: 'ok', installed: true, loaded: true, toolRegistered: true, lastRunOk: true, lastRunAt: NOW, runs: 1, fails: 0 } } }
  for (const view of [ledgerPanel({ ledger, now: NOW }), ledgerPanel({ ledger: { v: 2, plugins: {} }, now: NOW })]) {
    const r = checkView(view, { budget: 'panel', lang: 'zh' })
    assert.deepEqual(r.issues.filter((i) => i.kind !== 'note'), [])
  }
})

// ── 7. 真桥接测试宿主 ───────────────────────────────────────────────────────
test('桥接：清单生效、面板可画、状态条推数据、动作有效果', async () => {
  const toolsMap = new Map()
  const host = createTestHost({
    dir: DIR,
    services: { tools: { register: (def) => { toolsMap.set(def.name, def); return () => toolsMap.delete(def.name) } } },
  })
  const h = applyLedger(host.ctx, { now: () => NOW, ledgerPath: null })

  const row = host.contributions()
  assert.equal(row.state, 'ok', JSON.stringify(row.issues))
  assert.deepEqual(row.contributes['composer.strip'].map((s) => s.id), ['ledger.strip'])
  assert.deepEqual(row.contributes.panels.map((p) => p.id), ['ledger'])

  const { view, stats } = await host.view('ledger', { sessionId: 's1' })
  assert.equal(view.type, 'stack')
  assert.ok(stats.nodes <= 120)
  assert.deepEqual(checkView(view, { budget: 'panel', lang: 'zh' }).issues.filter((i) => i.kind !== 'note'), [])

  const feed = host.subscribe('ledger.strip', { sessionId: 's1' })
  assert.ok(feed.data, '状态条要立刻推一次')
  assert.ok(feed.data.text.length > 0)
  assert.equal(feed.errors.length, 0, JSON.stringify(feed.errors))

  // 记一次失败 → 状态条要变
  h.api.record(SELF_PLUGIN, { ok: false, code: 'E_NETWORK', message: '断了', at: NOW })
  assert.ok(feed.data.text.includes('失败'), JSON.stringify(feed.data))

  const refresh = await host.invoke('ledger', { surface: 'panel', action: 'refresh', scope: { sessionId: 's1' } })
  assert.deepEqual(refresh.issues, [])
  assert.deepEqual(refresh.effects, [{ type: 'toast', text: '已刷新' }])

  const copy = await host.invoke('ledger', { surface: 'panel', action: 'copy', scope: { sessionId: 's1' } })
  assert.deepEqual(copy.issues, [])
  assert.equal(copy.effects[0].type, 'copy')
  assert.ok(copy.effects[0].text.includes('toolRegistered'))
})

test('桥接：面板数据推送带完整台账（不是只有名字）', async () => {
  const toolsMap = new Map()
  const host = createTestHost({
    dir: DIR,
    services: { tools: { register: (def) => { toolsMap.set(def.name, def); return () => toolsMap.delete(def.name) } } },
  })
  applyLedger(host.ctx, { now: () => NOW, ledgerPath: null })

  const feed = host.subscribe('ledger', { sessionId: 's1' })
  assert.ok(feed.data)
  const entry = feed.data.ledger.plugins[SELF_PLUGIN]
  assert.equal(entry.installed, true)
  assert.equal(entry.loaded, true)
  assert.equal(entry.toolRegistered, true)
})
