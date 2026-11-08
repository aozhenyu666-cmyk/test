// 视图层测试：卡片 / 面板 / 状态条的预算与内容，以及用**真的桥接测试宿主**
// （sdk/plugin-kit/lib/testing.js，桥接自己的解析器 + 效果规范化）过一遍。
import assert from 'node:assert/strict'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

// **先** import 这个：它注册 '@deepseek-ai/dsh-tools' 的解析钩子，否则 lib/index.js 起不来
import '../support/dsh-tools.js'
import { createTestHost } from '../../../../sdk/plugin-kit/lib/testing.js'
import { checkView } from '../../../../sdk/plugin-kit/lib/view-check.js'
import { peekCard, peekPanel, stripData, validate } from '../lib/views.mjs'
import { emptyLedger, recordRun } from '../lib/ledger.mjs'

const DIR = fileURLToPath(new URL('..', import.meta.url))

/** 桥接测试宿主 + 一个真的 ctx.tools 服务（测试宿主本身不提供 tools）。 */
function bridgeHost(options = {}) {
  const tools = new Map()
  const toolsService = { register: (def) => { tools.set(def.name, def); return () => tools.delete(def.name) } }
  const host = createTestHost({ dir: DIR, ...options, services: { tools: toolsService } })
  return { host, tools }
}
const NOW = 1_700_000_000_000

const okValue = {
  ok: true, code: 'OK', bvid: 'BV1uv411q7Mv', title: '爆肝98小时！在MC中还原糖调小镇',
  author: '落忆', desc: '相关游戏：Minecraft、しゅがてん！', duration: 314,
  pubdate: new Date(NOW - 86_400_000).toISOString(),
  stats: { view: 5_576_839, like: 277_783, danmaku: 128_787 },
  url: 'https://www.bilibili.com/video/BV1uv411q7Mv', source: 'api.bilibili.com/x/web-interface/view',
  pages: [{ index: 1, title: '正片', duration: 314 }, { index: 2, title: '花絮', duration: 90 }],
  currentPage: { index: 1, title: '正片', duration: 314 },
  fetchedAt: NOW,
}
const ledgerOk = () => {
  const l = emptyLedger()
  l.installed = true
  l.loaded = true
  l.toolRegistered = true
  recordRun(l, { ok: true, at: NOW })
  return l
}

// ── 1. 工具卡 ───────────────────────────────────────────────────────────────
test('卡片：成功 —— 播放/点赞/时长 + 作者/发布日期 + 简介', () => {
  const view = peekCard({ id: 'BV1uv411q7Mv' }, okValue)
  assert.equal(view.type, 'stack')
  assert.ok(view.title.includes('爆肝'), '标题进折叠摘要')

  const r = validate(view, 'toolCard')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  assert.ok(r.bytes < 8 * 1024)

  const s = JSON.stringify(view)
  assert.ok(s.includes('557.7万'), '播放量要压成万')
  assert.ok(s.includes('5:14'), '时长要 5:14 这种')
  assert.ok(s.includes('落忆'))
  assert.ok(s.includes('Minecraft'))
})

test('卡片：只用工具卡白名单的 8 个组件（不能出现 list/keyValue/table）', () => {
  const allow = new Set(['stack', 'row', 'text', 'metric', 'chart', 'compare', 'steps', 'callout'])
  const walk = (n, out) => {
    if (!n || typeof n !== 'object') return out
    if (typeof n.type === 'string') out.add(n.type)
    for (const c of [...(Array.isArray(n.children) ? n.children : []), ...(Array.isArray(n.items) ? n.items : [])]) walk(c, out)
    return out
  }
  for (const view of [peekCard({ id: 'x' }, okValue), peekCard({ id: 'x' }, { ok: false, code: 'E_NETWORK', message: 'm', hint: 'h' })]) {
    for (const type of walk(view, new Set())) assert.ok(allow.has(type), '工具卡里不许有 ' + type)
  }
})

test('卡片：失败 —— 写清错误码、原因、下一步，不留一张空卡', () => {
  const view = peekCard({ id: 'BV1bad' }, { ok: false, code: 'E_TIMEOUT', message: '请求超时：超过 12000 ms', hint: '过一会儿再试一次。' })
  const r = validate(view, 'toolCard')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  const s = JSON.stringify(view)
  assert.ok(s.includes('E_TIMEOUT'))
  assert.ok(s.includes('过一会儿再试一次'), '下一步提示必须在卡上')
  assert.ok(s.includes('BV1bad'), '要写清查的是什么')
})

test('卡片：分P 多于一个时要说明当前是哪一 P', () => {
  const view = peekCard({ id: 'x' }, { ...okValue, currentPage: { index: 2, title: '花絮', duration: 90 } })
  const s = JSON.stringify(view)
  assert.ok(s.includes('P2'), s)
  assert.ok(s.includes('花絮'))
})

test('卡片：简介为空时明说「没有简介」，不画一个空 text', () => {
  const view = peekCard({ id: 'x' }, { ...okValue, desc: '   ' })
  assert.ok(JSON.stringify(view).includes('没有简介'))
})

// ── 2. 面板 ─────────────────────────────────────────────────────────────────
test('面板：四态分开写（installed / loaded / toolRegistered / lastRunOk）', () => {
  const ledger = ledgerOk()
  const view = peekPanel({ recent: [], ledger })
  const r = validate(view, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))

  const kv = JSON.stringify(view)
  for (const key of ['installed', 'loaded', 'toolRegistered', 'lastRunOk']) assert.ok(kv.includes(key), '缺少状态：' + key)
  assert.ok(kv.includes('peek_bili_video'), 'toolRegistered 要写出工具名')
  assert.ok(kv.includes('成功'), 'lastRunOk 要写出结果')
})

test('面板：空台账（什么都没跑过）也给得出合法视图 + 一句人话', () => {
  const view = peekPanel({})
  const r = validate(view, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  const s = JSON.stringify(view)
  assert.ok(s.includes('还没跑过'))
  assert.ok(s.includes('还没查过'), '要有引导语')
})

test('面板：失败时把失败原因画出来（这是台账存在的理由）', () => {
  const ledger = emptyLedger()
  ledger.installed = true
  ledger.loaded = true
  ledger.toolRegistered = false
  recordRun(ledger, { ok: false, code: 'E_NO_FETCH', message: '这个宿主没有 fetch', at: NOW })
  const view = peekPanel({ recent: [], ledger })
  assert.ok(validate(view, 'panel').ok)
  const s = JSON.stringify(view)
  assert.ok(s.includes('E_NO_FETCH'))
  assert.ok(s.includes('这个宿主没有 fetch'))
  assert.ok(s.includes('callout'), '失败原因要用提醒条画出来')
})

test('面板：30 条记录也不超预算（靠截断）', () => {
  const recent = Array.from({ length: 30 }, (_, i) => ({
    bvid: 'BV1uv411q7M' + (i % 10), title: '第 ' + i + ' 个视频标题很长很长很长很长很长很长很长',
    author: 'UP主' + i, view: 1000 * i, ok: i % 3 !== 0, code: i % 3 === 0 ? 'E_BIZ' : undefined, at: NOW - i * 1000,
  }))
  const view = peekPanel({ recent, ledger: ledgerOk() })
  const r = validate(view, 'panel')
  assert.ok(r.ok, r.issues.map((i) => i.problem).join('; '))
  assert.ok(r.nodes <= 120)
})

// ── 3. 状态条 ───────────────────────────────────────────────────────────────
test('状态条：有记录时报标题 + 播放量，≤24 字，≤2KiB', () => {
  const data = stripData({ recent: [{ bvid: 'BV1uv411q7Mv', title: '爆肝98小时！在MC中还原糖调小镇', view: 5_576_839, ok: true, at: NOW }], ledger: ledgerOk() })
  assert.ok([...data.text].length <= 24, data.text + ' 超 24 字')
  assert.ok(Buffer.byteLength(JSON.stringify(data)) <= 2048)
  assert.ok(data.text.includes('557.7万'))
  assert.ok(validate(data, 'strip').ok)

  // 长标题也必须被截到 24 字以内
  const longOne = stripData({ recent: [{ title: '这是一个非常非常非常非常非常非常长的视频标题', view: 123, ok: true, at: NOW }] })
  assert.ok([...longOne.text].length <= 24, longOne.text)
})

test('状态条：最近一次失败时报错误码（不是假装一切正常）', () => {
  const data = stripData({ recent: [{ bvid: 'x', ok: false, code: 'E_TIMEOUT', at: NOW }] })
  assert.ok(data.text.includes('E_TIMEOUT'))
  assert.equal(data.tone, 'warn')
})

test('状态条：没有记录 / 工具没注册时也要有一句人话', () => {
  const idle = stripData({ ledger: { ...emptyLedger(), toolRegistered: true } })
  assert.ok(idle.text.includes('待命'))
  const broken = stripData({ ledger: { ...emptyLedger(), toolRegistered: false } })
  assert.ok(broken.text.includes('未就绪'))
  for (const d of [idle, broken]) assert.ok(validate(d, 'strip').ok)
})

test('validate: 状态条判据不套组件树规则（emit 的是 { text, icon, tone }）', () => {
  assert.ok(validate({ text: 'ok' }, 'strip').ok, '合法状态条数据不该被当成缺 type 的视图')
  assert.equal(validate({ text: 'x'.repeat(25) }, 'strip').ok, false, '超 24 字要报')
  assert.equal(validate({}, 'strip').ok, false, '缺 text 要报')
  assert.equal(validate({ text: 'ok', tone: 'purple' }, 'strip').ok, false, '不认识的 tone 要报')
  assert.equal(validate({ text: 'ok', icon: 'nope' }, 'strip').ok, false, '不认识的图标要报')
})

// ── 4. 与 App 的解析器逐条对齐（sdk/plugin-kit 是 App 解析器的孪生）────────────
test('App 解析器孪生：全部视图 checkView 无 error（只有 note 可以接受）', () => {
  const cases = [
    ['toolCard', peekCard({ id: 'BV1uv411q7Mv' }, okValue)],
    ['toolCard', peekCard({ id: 'x' }, { ok: false, code: 'E_BIZ', message: 'code=-404 啥都木有', hint: '确认 BV 号' })],
    ['panel', peekPanel({ recent: [], ledger: ledgerOk() })],
    ['panel', peekPanel({ recent: [], ledger: emptyLedger() })],
  ]
  for (const [budget, view] of cases) {
    const r = checkView(view, { budget, lang: 'zh' })
    const errors = r.issues.filter((i) => i.kind !== 'note')
    assert.deepEqual(errors, [], budget + '：' + errors.map((i) => i.path + '：' + i.message).join('; '))
    assert.equal(r.fatal, false)
  }
})

test('App 解析器孪生：状态条数据（桥接自己的 strip 规则）', () => {
  // 状态条是 { text, icon, tone }，不是组件树：用桥接的 stripValue 规则算
  const item = stripData({ recent: [{ title: '爆肝98小时！在MC中还原糖调小镇', view: 5_576_839, ok: true, at: NOW }], ledger: ledgerOk() })
  assert.ok([...item.text].length <= 24)
  assert.ok(['good', 'warn', 'info', 'muted', undefined].includes(item.tone))
})

// ── 5. 真的桥接测试宿主（清单解析 + 视图 + 数据推送 + 动作）────────────────────
test('桥接宿主：清单生效（1 条状态条 + 1 个面板），不是「整块不显示」', async () => {
  const plugin = await import('../lib/index.js')
  const { host } = bridgeHost()
  plugin.apply(host.ctx, { ledgerPath: null })

  const row = host.contributions()
  assert.equal(row.state, 'ok', JSON.stringify(row.issues))
  assert.deepEqual(row.issues, [])
  assert.equal(row.contributes.panels.length, 1)
  assert.equal(row.contributes.panels[0].id, 'peek')
  assert.deepEqual(row.contributes['composer.strip'].map((s) => s.id), ['peek.strip'])
})

test('桥接宿主：面板视图能被 App 的解析器画出来（含 stats）', async () => {
  const plugin = await import('../lib/index.js')
  const { host } = bridgeHost()
  plugin.apply(host.ctx, { ledgerPath: null })

  const { view, stats } = await host.view('peek', { sessionId: 's1' })
  assert.equal(view.type, 'stack')
  assert.ok(stats.nodes <= 120 && stats.depth <= 8, JSON.stringify(stats))
  const r = checkView(view, { budget: 'panel', lang: 'zh' })
  assert.deepEqual(r.issues.filter((i) => i.kind !== 'note'), [])
})

test('桥接宿主：状态条推数据；查过之后会推新值，且没变化不重复推', async () => {
  const plugin = await import('../lib/index.js')
  const f = async () => new Response(JSON.stringify({ code: 0, message: 'OK', data: {
    bvid: 'BV1uv411q7Mv', title: '爆肝98小时！在MC中还原糖调小镇', duration: 314,
    owner: { name: '落忆' }, stat: { view: 5_576_839, like: 277_783 },
  } }), { status: 200 })
  const { host } = bridgeHost()
  const handle = plugin.apply(host.ctx, { fetch: f, now: () => NOW, ledgerPath: null })

  const feed = host.subscribe('peek.strip', { sessionId: 's1' })
  assert.ok(feed.data, '要立刻推一次')
  assert.equal(feed.history.length, 1, '只有一次推送（没有变化不重复推）')
  assert.ok(feed.data.text.length > 0)

  // 跑一次查询：状态条要换成「标题 + 播放量」
  await handle.peek('BV1uv411q7Mv')
  assert.equal(feed.history.length, 2, '记录变了要再推一次')
  assert.ok(feed.data.text.includes('557.7万'), JSON.stringify(feed.data))
  assert.ok([...feed.data.text].length <= 24)
})

test('桥接宿主：面板数据推送带四态台账', async () => {
  const plugin = await import('../lib/index.js')
  const { host } = bridgeHost()
  plugin.apply(host.ctx, { ledgerPath: null })

  const feed = host.subscribe('peek', { sessionId: 's1' })
  assert.ok(feed.data, '面板会推数据')
  for (const key of ['installed', 'loaded', 'toolRegistered']) {
    assert.ok(Object.hasOwn(feed.data.ledger, key), '台账缺 ' + key)
  }
  assert.equal(feed.data.ledger.toolRegistered, true)
  // 桥接把 null 当缺失丢掉（protocol 规定），所以「还没跑过」的 lastRunOk 不会出现在推送里 ——
  // 面板视图本身仍从 snapshot() 读到 null 并画成「还没跑过」（视图那条测试已覆盖）。
  assert.equal(Object.hasOwn(feed.data.ledger, 'lastRunOk'), false, 'null 字段由桥接丢弃')
  assert.equal(feed.errors.length, 0, JSON.stringify(feed.errors))
})

test('桥接宿主：动作返回合法效果（刷新 / 复制台账）', async () => {
  const plugin = await import('../lib/index.js')
  const { host } = bridgeHost()
  plugin.apply(host.ctx, { ledgerPath: null })

  const refresh = await host.invoke('peek', { surface: 'panel', action: 'refresh', scope: { sessionId: 's1' } })
  assert.deepEqual(refresh.issues, [])
  assert.deepEqual(refresh.effects, [{ type: 'toast', text: '已刷新' }])

  const ledger = await host.invoke('peek', { surface: 'panel', action: 'ledger', scope: { sessionId: 's1' } })
  assert.deepEqual(ledger.issues, [])
  assert.equal(ledger.effects[0].type, 'copy')
  assert.ok(ledger.effects[0].text.includes('toolRegistered'), '复制出来的是台账原文')
})

test('桥接宿主：没有桥接的主机上插件不注册原生界面，也不崩', async () => {
  const plugin = await import('../lib/index.js')
  const { host } = bridgeHost({ bridge: false })
  assert.doesNotThrow(() => plugin.apply(host.ctx, { ledgerPath: null }))
  const row = host.contributions()
  // 桥接不在时 dshGo 服务根本不出现 → 没有 strip / panel 注册
  assert.deepEqual(host.registrations().strips, [])
  assert.deepEqual(host.registrations().panels, [])
  assert.ok(row, '清单本身仍然可读')
})
