// 插件本体测试：加载 / 工具注册 / 工具调用 / 异常 / 网络失败 / 依赖缺失 / 台账四态。
//
// 关于「真 dsh-tools」：本机桌面版 profile 里那份是转发到 Electron app.asar 的 shim，
// 纯 node 下 import 不了（ERR_MODULE_NOT_FOUND），所以这里用 stub 把定义原样返回。
// 代价是「schema DSL 能不能被真编译器接受」不能用真编译器验 —— 所以下面写了一条
// **独立的 DSL 合规检查**，按 dsh-tools 源码里 checkSchemaNode 的规则逐条走一遍。
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { loadPlugin, realDshTools } from '../support/dsh-tools.js'
import { describe as describeLedger, emptyLedger, normalizeLedger, recordRun } from '../lib/ledger.mjs'

const HERE = fileURLToPath(new URL('..', import.meta.url))
const NOW = 1_700_000_000_000

const VIEW_DATA = {
  bvid: 'BV1uv411q7Mv',
  aid: 243922477,
  title: '爆肝98小时！在MC中还原糖调小镇',
  desc: '相关游戏：Minecraft',
  pic: 'https://i2.hdslb.com/bfs/archive/abc.jpg',
  duration: 314,
  pubdate: 1595203214,
  tid: 17,
  owner: { mid: 4390964, name: '落忆' },
  stat: { view: 5576839, danmaku: 128787, reply: 89521, like: 277783 },
  pages: [{ page: 1, part: '正片', duration: 314 }],
}

const fakeFetch = (handler) => {
  const calls = []
  const impl = async (url, init) => { calls.push({ url: String(url), init }); return handler(String(url), init) }
  impl.calls = calls
  return impl
}
const okFetch = () => fakeFetch(() => new Response(JSON.stringify({ code: 0, message: 'OK', data: VIEW_DATA }), { status: 200 }))

/** 起一个带注入 fetch 的插件实例，返回它的工具表与句柄。 */
async function boot(options = {}) {
  const plugin = await import('../lib/index.js')
  const tools = new Map()
  const ctx = {
    inject() {},
    on() {},
    effect: (fn) => { const d = fn(); return () => { if (typeof d === 'function') d() } },
    get: (name) => (name === 'tools' ? ctx.tools : undefined),
    tools: { register: (def) => { tools.set(def.name, def); return () => tools.delete(def.name) } },
  }
  const handle = plugin.apply(ctx, { fetch: options.fetch ?? okFetch(), now: () => NOW, ledgerPath: null, ...options.applyOptions })
  return { plugin, tools, handle, ctx, tool: tools.get('peek_bili_video') }
}

// ── 1. package.json 清单 ────────────────────────────────────────────────────
test('manifest: dshGo.v=1、插槽 id 唯一、条数在限内、strip 指的面板存在', () => {
  const pkg = JSON.parse(readFileSync(join(HERE, 'package.json'), 'utf8'))
  assert.equal(pkg.dshGo.v, 1)
  assert.match(pkg.name, /^dsh-go-/)

  const contributes = pkg.dshGo.contributes
  assert.ok(Object.keys(contributes).every((k) => ['composer.actions', 'message.actions', 'composer.strip', 'panels'].includes(k)))
  assert.ok(contributes['composer.strip'].length <= 1, '状态条每插件最多 1 条')
  assert.ok(contributes.panels.length <= 4, '面板每插件最多 4 个')

  const ids = []
  for (const list of Object.values(contributes)) for (const it of list) ids.push(it.id)
  assert.equal(new Set(ids).size, ids.length, '同一插件内所有插槽 id 不许重复：' + ids.join(','))

  for (const id of ids) assert.match(id, /^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$/, id)
  for (const p of contributes.panels) {
    const title = typeof p.title === 'string' ? p.title : p.title.zh
    assert.ok(title.length <= 16, p.id + ' 标题 ' + title.length + ' 字 > 16')
  }
  const panelIds = new Set(contributes.panels.map((p) => p.id))
  for (const s of contributes['composer.strip']) if (s.panel) assert.ok(panelIds.has(s.panel), s.panel)

  assert.ok(Buffer.byteLength(JSON.stringify(pkg.dshGo)) <= 16 * 1024, '整个 dshGo ≤ 16 KiB')

  const patch = readFileSync(join(HERE, 'cordis.patch.yml'), 'utf8')
  assert.ok(patch.includes('name: ' + pkg.name), 'cordis.patch.yml 与包名一致')
})

test('manifest: 零第三方依赖（不给插件生态加安装负担）', () => {
  const pkg = JSON.parse(readFileSync(join(HERE, 'package.json'), 'utf8'))
  assert.deepEqual(pkg.dependencies, {})
  assert.deepEqual(pkg.peerDependencies, {})
})

// ── 2. output.schema 的 DSL 合规（独立于 dsh-tools 的检查）────────────────────
// 规则来自 dsh-tools/lib/index.js 的 checkSchemaNode + runSchemaCompiler：
//   · 只允许 type/oneOf/properties/required/additionalProperties/items/enum/const + 注释键
//   · object 节点必须显式写 additionalProperties（true/false）
//   · 作者 DSL 里 property 上的 required 只能是 true（由 runSchemaCompiler 收进 required 数组）
const CONSTRAINT = new Set(['type', 'oneOf', 'properties', 'required', 'additionalProperties', 'items', 'enum', 'const'])
const ANNOTATION = new Set(['description', 'title', 'default', 'examples'])

function checkAuthorSchema(node, path, issues) {
  if (typeof node !== 'object' || node === null || Array.isArray(node)) { issues.push(path + ' 不是 schema 对象'); return }
  for (const key of Object.keys(node)) {
    if (!CONSTRAINT.has(key) && !ANNOTATION.has(key)) issues.push(path + '.' + key + ' 不是受支持的键')
  }
  if (node.required !== undefined && node.required !== true) issues.push(path + '.required 必须是 true（作者 DSL）')
  if (node.type === 'object') {
    if (typeof node.additionalProperties !== 'boolean') issues.push(path + '.additionalProperties 必须显式写 true 或 false')
    if (node.properties !== undefined) {
      for (const [k, v] of Object.entries(node.properties)) checkAuthorSchema(v, path + '.properties.' + k, issues)
    }
  } else if (node.type === 'array') {
    if (node.items !== undefined) checkAuthorSchema(node.items, path + '.items', issues)
  } else if (!['string', 'number', 'integer', 'boolean', 'null', 'json'].includes(node.type)) {
    issues.push(path + '.type 不认识：' + String(node.type))
  }
}

test('schema: 工具声明符合 dsh-tools 的作者 DSL（object 必须显式 additionalProperties）', async () => {
  const { tool } = await boot()

  if (realDshTools) {
    // 真 dsh-tools 在：parameters / output.schema 已经被它的编译器编译成 raw JSON Schema。
    // 这正是最硬的那条证据 —— 我写的作者 DSL 被真编译器接受了。
    assert.deepEqual(tool.parameters.required, ['id'])
    assert.equal(tool.parameters.properties.id.type, 'string')
    assert.equal(tool.output.schema.type, 'object')
    assert.equal(tool.output.schema.additionalProperties, false)
    assert.ok(tool.output.schema.required.includes('ok'))
    assert.ok(tool.output.schema.required.includes('code'))
    assert.ok(tool.output.schema.required.includes('source'))
    assert.equal(tool.output.schema.properties.stats.additionalProperties, false)
    assert.equal(tool.output.schema.properties.pages.items.additionalProperties, false)
    return
  }

  // stub 在：装饰器原样返回，可以直接检查作者 DSL 本身
  const parameters = tool.parameters
  assert.deepEqual(Object.keys(parameters), ['id'])
  assert.equal(parameters.id.type, 'string')
  assert.equal(parameters.id.required, true)

  const issues = []
  checkAuthorSchema(tool.output.schema, 'schema', issues)
  assert.deepEqual(issues, [], issues.join('; '))
  assert.equal(tool.output.schema.properties.ok.required, true)
  assert.equal(tool.output.schema.properties.source.required, true)
})

test('schema: 真 dsh-tools 找得到时，用它自己的校验器复核输出值', async (t) => {
  if (!realDshTools) {
    t.diagnostic('本机没有可 import 的 @deepseek-ai/dsh-tools（profile 里那份是 asar shim），跳过真校验器复核')
    return
  }
  const { validateJsonSchemaValue } = await import(realDshTools)
  const { tool } = await boot()
  const value = await tool.execute({ id: 'BV1uv411q7Mv' }, {})
  assert.deepEqual(validateJsonSchemaValue(tool.output.schema, value, 'value'), [])
})

// ── 3. 加载 + 工具注册 ──────────────────────────────────────────────────────
test('加载：有 ctx.tools 时注册 peek_bili_video，台账四态里前三态都是 true', async () => {
  const { tools, handle } = await loadPlugin()
  assert.ok(tools.has('peek_bili_video'), '工具没注册上')
  assert.equal(handle.toolRegistered(), true)
  assert.equal(handle.ledger.installed, true)
  assert.equal(handle.ledger.loaded, true)
  assert.equal(handle.ledger.toolRegistered, true)
})

test('加载：工具声明里 render 和 presentationMeta 都在（缺一个注册表就拒）', async () => {
  const { tool } = await boot()
  assert.equal(typeof tool.output.render, 'function')
  assert.equal(typeof tool.output.presentationMeta, 'function')
  assert.equal(typeof tool.execute, 'function')
})

test('加载：没有 ctx.tools 的宿主 —— 不崩、降级、面板仍能画并说明原因', async () => {
  const plugin = await import('../lib/index.js')
  const regs = []
  const ctx = {
    inject(names, cb) {
      if (!names.includes('dshGo')) return
      cb({
        dshGo: {
          strip: (id, dataFn) => { regs.push({ kind: 'strip', id, dataFn }); return () => {} },
          panel: (id, def) => { regs.push({ kind: 'panel', id, def }) },
          refresh: () => {},
        },
      })
    },
    effect: (fn) => { fn(); return () => {} },
    get: () => undefined,
  }
  let handle
  assert.doesNotThrow(() => { handle = plugin.apply(ctx, { ledgerPath: null }) })
  assert.equal(handle.toolRegistered(), false, '没有 tools 就是没注册')
  assert.equal(handle.ledger.toolRegistered, false)
  assert.equal(handle.ledger.loaded, true, '插件本体仍然起来了')
  assert.deepEqual(regs.map((r) => r.kind).sort(), ['panel', 'strip'], '原生界面照样注册')

  const view = regs.find((r) => r.kind === 'panel').def.view({}, {})
  assert.equal(view.type, 'stack')
  const text = JSON.stringify(view)
  assert.ok(text.includes('toolRegistered'), '面板要显示四态')
  assert.ok(text.includes('否'), '要明确写「否」，不能只是不说')

  // 状态条也要有一句人话（不是空白）
  const pushed = []
  const cancel = regs.find((r) => r.kind === 'strip').dataFn({}, (d) => pushed.push(d))
  assert.equal(typeof cancel, 'function')
  assert.ok(pushed.length >= 1)
  assert.ok(pushed[0].text.includes('未就绪'), JSON.stringify(pushed[0]))
})

test('加载：ctx.tools.register 抛错（schema 被拒 / 重名）—— 记下来但不崩', async () => {
  const plugin = await import('../lib/index.js')
  const ctx = {
    inject() {},
    effect: (fn) => { fn() },
    get: (n) => (n === 'tools' ? ctx.tools : undefined),
    tools: { register: () => { throw new TypeError('tool "peek_bili_video" must declare output { schema, render }') } },
  }
  let handle
  assert.doesNotThrow(() => { handle = plugin.apply(ctx, { ledgerPath: null }) })
  assert.equal(handle.toolRegistered(), false)
  assert.match(handle.registerError(), /must declare output/)
  assert.equal(handle.ledger.toolRegistered, false)
  assert.match(handle.ledger.lastRunMessage, /注册失败/)
})

test('加载：没有 ctx.effect 的宿主也不崩（清理注册可有可无）', async () => {
  const plugin = await import('../lib/index.js')
  const ctx = { inject() {}, get: () => undefined }
  assert.doesNotThrow(() => plugin.apply(ctx, { ledgerPath: null }))
})

test('加载：没有 dsh-go 桥接时 ctx.inject 不回调，插件安静地只做工具', async () => {
  const { tool, handle } = await boot()
  // inject 是空实现 → 桥接代码一行都没跑；工具照常注册、照常能调
  assert.ok(tool, '工具仍在')
  const value = await tool.execute({ id: 'BV1uv411q7Mv' }, {})
  assert.equal(value.ok, true)
  assert.equal(handle.ledger.lastRunOk, true)
})

// ── 4. 工具调用（成功路径）──────────────────────────────────────────────────
test('调用：走注入的 fetch 端口；结果 + 卡片 + 给模型的文字都对', async () => {
  const f = okFetch()
  const { tool, handle } = await boot({ fetch: f })

  const value = await tool.execute({ id: 'https://www.bilibili.com/video/BV1uv411q7Mv' }, { signal: new AbortController().signal })

  assert.equal(f.calls.length, 1)
  assert.match(f.calls[0].url, /bvid=BV1uv411q7Mv/)
  assert.equal(value.ok, true)
  assert.equal(value.bvid, 'BV1uv411q7Mv')
  assert.equal(value.title, VIEW_DATA.title)

  // 给模型的文字：标题 / 作者 / 播放 / 链接 / 来源
  const content = tool.output.render({ id: 'x' }, value)
  assert.equal(content[0].type, 'text')
  for (const piece of [VIEW_DATA.title, '落忆', '557.7万', 'https://www.bilibili.com/video/BV1uv411q7Mv', 'api.bilibili.com']) {
    assert.ok(content[0].text.includes(piece), '模型文字里缺：' + piece)
  }

  // 给 App 的卡片：只有 dsh-go/view 一个键、无损 JSON、在 8 KiB 内、根节点是 stack
  const meta = tool.output.presentationMeta({ id: 'x' }, value)
  assert.deepEqual(Object.keys(meta), ['dsh-go/view'])
  assert.deepEqual(JSON.parse(JSON.stringify(meta)), meta, '必须是无损 JSON（否则整次工具调用失败）')
  assert.ok(Buffer.byteLength(JSON.stringify(meta), 'utf8') < 8 * 1024)
  const view = meta['dsh-go/view']
  assert.equal(view.type, 'stack')
  assert.ok(String(view.title).includes('爆肝'))

  // 台账 ④：成功
  assert.equal(handle.ledger.lastRunOk, true)
  assert.equal(handle.ledger.runs, 1)
  assert.equal(handle.ledger.fails, 0)
  assert.equal(handle.ledger.lastRunAt, NOW)
  assert.equal(handle.recent[0].bvid, 'BV1uv411q7Mv')
})

test('调用：结果能交给 Agent 继续分析 —— 是普通可往返 JSON，没有函数/类实例', async () => {
  const { tool } = await boot()
  const value = await tool.execute({ id: 'BV1uv411q7Mv' }, {})
  assert.deepEqual(JSON.parse(JSON.stringify(value)), value)
  assert.equal(Object.getPrototypeOf(value), Object.prototype)
})

test('调用：失败**不抛异常**，交给模型的是 ok:false + code + hint', async () => {
  const f = fakeFetch(() => { throw new TypeError('fetch failed') })
  const { tool, handle } = await boot({ fetch: f })

  const value = await tool.execute({ id: 'BV1uv411q7Mv' }, {})
  assert.equal(value.ok, false)
  assert.equal(value.code, 'E_NETWORK')
  assert.equal(typeof value.hint, 'string')
  assert.ok(value.hint.length > 0)

  const text = tool.output.render({}, value)[0].text
  assert.match(text, /E_NETWORK/)
  assert.match(text, /下一步/)

  const view = tool.output.presentationMeta({ id: 'BV1uv411q7Mv' }, value)['dsh-go/view']
  assert.equal(view.type, 'stack')
  assert.ok(JSON.stringify(view).includes('E_NETWORK'), '失败也要出卡片并写清错误码')

  // 台账 ④：失败 + 原因
  assert.equal(handle.ledger.lastRunOk, false)
  assert.equal(handle.ledger.lastRunCode, 'E_NETWORK')
  assert.equal(handle.ledger.fails, 1)
  assert.equal(handle.recent[0].ok, false)
})

test('调用失败：输入认不出来 -> E_INPUT，且一次都不联网', async () => {
  const f = okFetch()
  const { tool } = await boot({ fetch: f })
  const value = await tool.execute({ id: '随便一句话' }, {})
  assert.equal(value.code, 'E_INPUT')
  assert.equal(f.calls.length, 0, '输入不合法不该发请求')
})

test('调用失败：宿主没有全局 fetch 且没注入 -> E_NO_FETCH（缺依赖必须说出来）', async () => {
  const plugin = await import('../lib/index.js')
  const tools = new Map()
  const ctx = {
    inject() {}, effect: (fn) => { fn() },
    get: (n) => (n === 'tools' ? ctx.tools : undefined),
    tools: { register: (def) => { tools.set(def.name, def); return () => {} } },
  }
  const saved = globalThis.fetch
  try {
    globalThis.fetch = undefined
    plugin.apply(ctx, { ledgerPath: null })
    const value = await tools.get('peek_bili_video').execute({ id: 'BV1uv411q7Mv' }, {})
    assert.equal(value.ok, false)
    assert.equal(value.code, 'E_NO_FETCH')
  } finally {
    globalThis.fetch = saved
  }
})

test('调用失败：presentationMeta 遇到烂值也不抛（烂值出一张「没查到」卡，不是崩）', async () => {
  const { tool } = await boot()
  for (const bad of [null, undefined, {}, 42, 'x']) {
    const meta = tool.output.presentationMeta({ id: 'BV1uv411q7Mv' }, bad)
    assert.deepEqual(Object.keys(meta), ['dsh-go/view'], 'always a card, never a throw: ' + String(bad))
    assert.equal(meta['dsh-go/view'].type, 'stack')
  }
})

test('调用失败：卡片超预算时返回 {}（不出卡，不失败）', async () => {
  const plugin = await import('../lib/index.js')
  const { toolCard } = plugin
  const big = toolCard(() => ({ type: 'stack', title: 'x', children: [{ type: 'text', text: 'x'.repeat(9000) }] }))
  assert.deepEqual(big({}, {}), {})
  const boom = toolCard(() => { throw new Error('视图构造炸了') })
  assert.deepEqual(boom({}, {}), {})
})

// ── 5. 台账 ─────────────────────────────────────────────────────────────────
test('台账：四个状态各自独立（不是只显示插件名）', async () => {
  const { handle } = await loadPlugin()
  const d = handle.describe()
  assert.equal(d.installed, '是')
  assert.equal(d.loaded, '是')
  assert.equal(d.toolRegistered, 'peek_bili_video')
  assert.equal(d.lastRunOk, '还没跑过', '还没跑过 ≠ 失败')
  assert.equal(d.healthy, true)
})

test('台账：失败原因落盘，重启后还记得', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'bili-ledger-'))
  const file = join(dir, 'ledger.json')
  try {
    const plugin = await import('../lib/index.js')
    const build = () => {
      const tools = new Map()
      const ctx = {
        inject() {}, effect: (fn) => { fn() },
        get: (n) => (n === 'tools' ? ctx.tools : undefined),
        tools: { register: (def) => { tools.set(def.name, def); return () => {} } },
      }
      return { ctx, tools }
    }
    const fail = fakeFetch(() => new Response(JSON.stringify({ code: -404, message: '啥都木有' }), { status: 200 }))

    const first = build()
    const h1 = plugin.apply(first.ctx, { fetch: fail, now: () => NOW, ledgerPath: file })
    await first.tools.get('peek_bili_video').execute({ id: 'BV1xx411c7mD' }, {})
    assert.equal(h1.save(), true, '落盘应该成功')

    // 第二次启动 = 模拟重启：从盘上读回四态
    const second = build()
    const h2 = plugin.apply(second.ctx, { fetch: okFetch(), now: () => NOW, ledgerPath: file })
    assert.equal(h2.ledger.lastRunOk, false)
    assert.equal(h2.ledger.lastRunCode, 'E_BIZ')
    assert.equal(h2.ledger.runs, 1)
    assert.equal(h2.ledger.fails, 1)
    assert.match(h2.ledger.lastRunMessage, /啥都木有/)
    assert.equal(h2.ledger.loaded, true)
    assert.equal(h2.recent[0].ok, false, '最近记录也要留下来')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('台账：坏文件不影响启动（不回退成「假装健康」）', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'bili-bad-'))
  const file = join(dir, 'ledger.json')
  writeFileSync(file, '{ 这不是 json')
  try {
    const plugin = await import('../lib/index.js')
    const ctx = { inject() {}, effect: (fn) => { fn() }, get: () => undefined }
    let handle
    assert.doesNotThrow(() => { handle = plugin.apply(ctx, { ledgerPath: file }) })
    assert.equal(handle.ledger.loaded, true)
    assert.equal(handle.ledger.runs, 0)
    assert.equal(handle.ledger.lastRunOk, null, '读不到就是「还没跑过」，不是成功也不是失败')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('台账：describe 在四态不全时判不健康（宁可保守，不假绿）', () => {
  assert.equal(describeLedger({ v: 1, installed: true, loaded: true, toolRegistered: false }).healthy, false)
  assert.equal(describeLedger({ v: 1, installed: true, loaded: false, toolRegistered: true }).healthy, false)
  assert.equal(describeLedger({ v: 1, installed: true, loaded: true, toolRegistered: true, lastRunOk: false }).healthy, false)
  assert.equal(describeLedger({ v: 1, installed: true, loaded: true, toolRegistered: true, lastRunOk: true }).healthy, true)
})

test('台账：normalizeLedger 不信任何输入（类型不对就退回默认，fails 不会多于 runs）', () => {
  assert.deepEqual(normalizeLedger(null), emptyLedger())
  assert.deepEqual(normalizeLedger('x'), emptyLedger())
  assert.deepEqual(normalizeLedger({ v: 99, loaded: true }), emptyLedger(), '版本不对就整份退回')
  const fixed = normalizeLedger({ v: 1, runs: 2, fails: 9, loaded: 'yes', toolRegistered: 1 })
  assert.equal(fixed.runs, 2)
  assert.equal(fixed.fails, 2, 'fails 不能多于 runs')
  assert.equal(fixed.loaded, null, '不是布尔就写 null，不猜成 true')
  assert.equal(fixed.toolRegistered, null)
})

test('台账：recordRun 成功会清掉上一次的失败原因', () => {
  const l = emptyLedger()
  recordRun(l, { ok: false, code: 'E_TIMEOUT', message: '超时了', at: NOW })
  assert.equal(l.lastRunCode, 'E_TIMEOUT')
  assert.equal(l.fails, 1)
  recordRun(l, { ok: true, at: NOW + 1 })
  assert.equal(l.lastRunOk, true)
  assert.equal(l.lastRunCode, null, '成功后不该还挂着旧错误码')
  assert.equal(l.lastRunMessage, null)
  assert.equal(l.runs, 2)
  assert.equal(l.fails, 1)
})
