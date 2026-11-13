// 第九轮 A 组：预案接到真实插件上以后的行为（走 /plan、/presets、/bouts、/event 和工具）。
//
// presets.test.mjs 钉的是纯规则；这里钉的是「接线」：
//   - 引擎起来以后没有任何预案触发，就没有默认锁（第二轮的全天默认锁改成了一条停用的预案）；
//   - 局里在不允许的 App 待满 1 分钟 / 10 分钟切出 3 次 → 同一次 /plan 的回答里 session 就是严格模式；
//   - 严格模式的 session：只放行本局应用、0 宽限、带一句「哪条预案、怎么解除」；
//   - AI 工具新建只能是影子、放宽被拒；面板放宽进冷却期、能撤销；
//   - 破窗是出口：严格模式一起收掉。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { wallDecision, WALL_KIND_DEFAULT } from '../lib/wall.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'

function agentOf(id) {
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts: [], tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents, state) {
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  setStatePin(state)
  apply(ctx)
  return () => handler
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = PREFIX + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-presets-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

function setup(home, { state = 'standby', ...rest } = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, ...rest }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big }, state)
  const plan = (phone) => call(h(), 'POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone })
  const presets = (body) => call(h(), 'POST', '/presets', body)
  const open = (protocolId, apps) => call(h(), 'POST', '/bouts', { action: 'open', protocolId, apps })
  const event = (body) => call(h(), 'POST', '/event', { timeZone: 'Asia/Shanghai', ...body })
  const ledger = () => readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  return { sis, big, plan, presets, open, event, ledger, h }
}

const at = (app, extra = {}) => ({ app, fun: false, minutes: 0, screenOn: true, ...extra })

test('引擎起来没有预案触发：/plan 的规则快照里没有默认锁', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.plan(at('com.miui.home'))
    const kinds = (r.body.rules?.rules || []).map((x) => x.kind)
    assert.equal(kinds.includes(WALL_KIND_DEFAULT), false, '默认锁不该在：' + kinds.join(','))
  })
})

test('他打开「全天默认锁」这条预案（收紧，立即生效）：默认锁按墙的时间表回来', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.presets({ action: 'set', preset: { id: 'default-lock', 状态: '生效' } })
    assert.equal(r.body.ok, true, r.body.text)
    assert.equal(r.body.kind, 'tighten')
    const p = await t.plan(at('com.miui.home'))
    const kinds = (p.body.rules?.rules || []).map((x) => x.kind)
    // 现在是不是吃饭 / 深夜由真实时钟决定，所以用同一份墙规则算一遍期望值。
    const want = wallDecision({}, Date.now(), 'Asia/Shanghai')
    assert.equal(kinds.includes(WALL_KIND_DEFAULT), want.locked && want.kind === WALL_KIND_DEFAULT)
  })
})

test('/presets list：内置预案都在，全天默认锁是停用', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await call(t.h(), 'GET', '/presets')
    const byId = Object.fromEntries(r.body.presets.map((p) => [p.id, p]))
    assert.equal(byId['default-lock'].status, '停用')
    assert.equal(byId['switch-storm'].status, '生效')
    assert.equal(byId['off-dwell'].status, '生效')
    assert.match(byId['switch-storm'].explain, /预案「局内频繁切出」/)
  })
})

test('/bouts open：带上 App 要求开局；不认识的协议拒绝', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const bad = await t.open('nope', {})
    assert.equal(bad.body.ok, false)
    const r = await t.open('execute', { need: ['com.study'], allow: ['com.dict'] })
    assert.equal(r.body.ok, true, r.body.toast)
    const l = await t.presets({ action: 'list' })
    assert.deepEqual(l.body.bout.apps, { need: ['com.study'], allow: ['com.dict'] })
  })
})

test('局里在不允许的 App 待满 1 分钟：这一次 /plan 的回答里就是严格模式', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.open('execute', { need: ['com.study'], allow: ['com.dict'] })
    await t.plan(at('com.study'))
    const r = await t.plan(at('com.douyin', { fun: true, minutes: 2 }))
    const s = r.body.session
    assert.ok(s, '应当有 session')
    assert.deepEqual(s.allow.sort(), ['com.dict', 'com.study'])
    assert.equal(s.graceMs, 0, '严格模式不给宽限')
    assert.equal(s.kind, 'strict')
    assert.match(s.why, /局内偏离停留/)
    assert.match(s.why, /解除：本局结束 \/ 破窗/)
    assert.ok(t.ledger().some((e) => e.state === 'preset-fire' && e.presetId === 'off-dwell'))
  })
})

test('局里 10 分钟切出 3 次：严格模式', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.open('execute', { need: ['com.study'] })
    for (let i = 0; i < 3; i += 1) {
      await t.plan(at('com.study'))
      await t.plan(at(i % 2 ? 'com.bili' : 'com.douyin', { fun: true }))
    }
    const r = await t.plan(at('com.study'))
    assert.equal(r.body.session?.kind, 'strict')
    assert.deepEqual(r.body.session.allow, ['com.study'])
    assert.ok(t.ledger().some((e) => e.state === 'preset-fire' && e.presetId === 'switch-storm'))
  })
})

test('没有局：在娱乐 App 里待着也不触发局内预案', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.plan(at('com.douyin', { fun: true, minutes: 3 }))
    assert.notEqual(r.body.session?.kind, 'strict')
    assert.equal(t.ledger().some((e) => e.state === 'preset-fire'), false)
  })
})

test('严格模式期间：舱内放行的包不进封禁名单', async () => {
  await withHome(async (home) => {
    const t = setup(home, { apps: [{ pkg: 'com.study', fun: true }] })
    await t.open('execute', { need: ['com.study'] })
    const r = await t.plan(at('com.douyin', { fun: true, minutes: 2 }))
    assert.equal(r.body.session?.kind, 'strict')
    assert.equal(Object.hasOwn(r.body.rules?.block || {}, 'com.study'), false)
  })
})

test('破窗是出口：严格模式一起收掉', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.open('execute', { need: ['com.study'] })
    const on = await t.plan(at('com.douyin', { fun: true, minutes: 2 }))
    assert.equal(on.body.session?.kind, 'strict')
    const b = await t.event({ type: 'breakglass', minutes: 10 })
    assert.equal(b.body.ok, true)
    const off = await t.plan(at('com.study'))
    assert.notEqual(off.body.session?.kind, 'strict')
    assert.ok(t.ledger().some((e) => e.state === 'strict-off' && e.by === 'breakglass'))
  })
})

test('面板直接把这一局切进严格模式', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const none = await t.presets({ action: 'strict' })
    assert.equal(none.body.ok, false, '没有局不能进严格模式')
    await t.open('execute', { need: ['com.study'] })
    const r = await t.presets({ action: 'strict', why: '我自己要的' })
    assert.equal(r.body.ok, true)
    const p = await t.plan(at('com.study'))
    assert.equal(p.body.session?.kind, 'strict')
    assert.match(p.body.session.why, /我自己要的/)
  })
})

test('AI 工具（计划模态）：新建只能从影子开始；放宽被拒；能给这一局记 App', async () => {
  await withHome(async (home) => {
    const t = setup(home, { state: 'plan' })
    const tool = t.big.tools.preset
    assert.ok(tool, '计划模态里应当挂着 preset 工具')
    const made = tool.execute({
      action: 'set',
      preset: { id: 'two-out', 名称: '两次切出', 状态: '生效', 何时: { 事件: '切出', 窗口: '10m', 次数: 2, 仅局内: true }, 动作: [{ 严格模式: {} }], 解除: ['本局结束'] },
    })
    assert.equal(made.ok, true, made.text)
    const l = await t.presets({ action: 'list' })
    assert.equal(l.body.presets.find((p) => p.id === 'two-out').status, '影子')
    const loosen = tool.execute({ action: 'set', preset: { id: 'switch-storm', 状态: '停用' } })
    assert.equal(loosen.ok, false)
    assert.match(loosen.text, /放宽/)
    const noBout = tool.execute({ action: 'apps', need: ['com.study'] })
    assert.equal(noBout.ok, false)
    await t.open('execute', {})
    const apps = tool.execute({ action: 'apps', need: ['com.study'], allow: ['com.dict'] })
    assert.equal(apps.ok, true, apps.text)
    assert.match(tool.execute({ action: 'list' }).text, /必需 com\.study/)
  })
})

test('AI 的收紧开关：dafeiyu_control strict_bout', async () => {
  await withHome(async (home) => {
    const t = setup(home, { state: 'plan' })
    await t.open('execute', { need: ['com.study'] })
    const r = t.big.tools.dafeiyu_control.execute({ action: 'strict_bout' })
    assert.equal(r.ok, true, r.text)
    const p = await t.plan(at('com.study'))
    assert.equal(p.body.session?.kind, 'strict')
  })
})

test('他放宽：进 2 小时冷却期，期间照旧，可以撤销', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.presets({ action: 'set', preset: { id: 'off-dwell', 状态: '停用' } })
    assert.equal(r.body.ok, true)
    assert.equal(r.body.kind, 'loosen')
    assert.ok(r.body.effectiveAt > Date.now() + 3_600_000)
    const l = await t.presets({ action: 'list' })
    assert.equal(l.body.presets.find((p) => p.id === 'off-dwell').status, '生效')
    assert.equal(l.body.pending.length, 1)
    const c = await t.presets({ action: 'cancel', pendingId: r.body.pendingId })
    assert.equal(c.body.ok, true)
    assert.equal((await t.presets({ action: 'list' })).body.pending.length, 0)
  })
})

test('影子预案触发只记账，不落手机', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    // 他自己可以直接把新预案建成影子。同一刻内置的 off-dwell 也会触发（严格模式），这里只看影子那条。
    const made = await t.presets({
      action: 'set',
      preset: { id: 'shadow-dwell', 名称: '影子停留', 状态: '影子', 何时: { 事件: '偏离停留', 分钟: 1, 仅局内: true }, 动作: [{ 全机锁: {} }], 解除: ['到点'] },
    })
    assert.equal(made.body.ok, true, made.body.text)
    await t.open('execute', { need: ['com.study'] })
    const r = await t.plan(at('com.douyin', { fun: true, minutes: 2 }))
    assert.notEqual(r.body.session?.cell, 'all-lock', '影子的全机锁不该落下去')
    assert.ok(t.ledger().some((e) => e.state === 'preset-shadow' && e.presetId === 'shadow-dwell'))
    assert.equal(t.ledger().some((e) => e.state === 'preset-fire' && e.presetId === 'shadow-dwell'), false)
  })
})
