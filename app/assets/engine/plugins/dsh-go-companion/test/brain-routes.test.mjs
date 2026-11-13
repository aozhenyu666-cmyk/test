// 行为大脑第一版与法典接到真实插件上以后的行为（/plan 记片段、/brain、/codex、brain 工具、预案续期）。
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
import { writeFileSync as write } from 'node:fs'

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
const get = (t, path) => call(t.h(), 'GET', path)
const brain = (t, body) => call(t.h(), 'POST', '/brain', body)

test('/codex：四级都在；内置预案是法规；没做到的宪法条目照实标出', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await get(t, '/codex')
    assert.equal(r.body.ok, true)
    assert.ok(r.body.constitution.length >= 9)
    assert.ok(r.body.constitution.some((c) => c.id === 'C-7' && c.enforced === false))
    assert.ok(r.body.laws.some((l) => l.id === 'L-01' && /09:00/.test(l.value)))
    assert.ok(r.body.regulations.some((x) => x.id === 'R-switch-storm' && x.by === '内置'))
  })
})

test('/plan 记片段：在娱乐 App 里待着，/brain 能看到正开着的这一段', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(at('com.douyin', { fun: true, minutes: 3 }))
    const r = await get(t, '/brain')
    assert.equal(r.body.ok, true)
    assert.equal(r.body.open.app, 'com.douyin')
    assert.equal(r.body.open.fun, true)
  })
})

test('开局记进当天摘要：面板开的算他自己开的', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.open('execute', { need: ['com.study'] })
    const r = await get(t, '/brain')
    assert.equal(r.body.today.bouts.opened, 1)
    assert.equal(r.body.today.bouts.self, 1)
    assert.equal(r.body.today.selfOpenRatio, 1)
  })
})

test('/brain scan：攒够的摘要扫出模式卡；鲸澜写解释，鱼丸写不了；他写说法', async () => {
  await withHome(async (home) => {
    // 预先放 10 天的摘要：每天早上 7 点刷 30 分钟。
    const digests = {}
    for (let i = 1; i <= 10; i += 1) {
      const day = new Date(Date.now() - i * 86_400_000 + 8 * 3_600_000).toISOString().slice(0, 10)
      const funByHour = Array(24).fill(0).map((_, h) => (h >= 9 && h < 22 ? 2 : 0))
      funByHour[7] = 30
      digests[day] = { day, funByHour, funMin: 56, bouts: { opened: 0, self: 0, done: 0, bad: 0, byProtocol: {} }, locks: 0 }
    }
    mkdirSync(join(home, 'companion'), { recursive: true })
    write(join(home, 'companion', 'episodes.json'), JSON.stringify({ v: 1, open: null, segs: [], digests, marks: {}, bouts: {}, seq: 0 }))
    const t = setup(home, { state: 'plan' })
    const s = await brain(t, { action: 'scan' })
    assert.equal(s.body.ok, true)
    const card = s.body.cards.find((c) => c.type === '时段习惯')
    assert.ok(card, '应当扫出时段习惯')
    assert.equal(card.status, '已确认')
    const day = card.evidence.days[0]
    const sis = t.sis.tools.brain.execute({ action: 'explain', cardId: card.id, text: '醒来没计划', refs: [day] })
    assert.equal(sis.ok, false, '鱼丸写不了解释')
    const big = t.big.tools.brain.execute({ action: 'explain', cardId: card.id, text: '醒来没计划，第一屏就是娱乐', refs: [day, 'S-不存在'] })
    assert.equal(big.ok, true, big.text)
    assert.match(big.text, /已去掉/)
    const n = await brain(t, { action: 'note', cardId: card.id, text: '我早上在等快递' })
    assert.equal(n.body.ok, true)
    const v = await get(t, '/brain')
    const after = v.body.cards.find((c) => c.id === card.id)
    assert.equal(after.explain.refs.length, 1)
    assert.equal(after.notes.length, 1)
    assert.match(t.big.tools.brain.execute({ action: 'cards' }).text, /他说：我早上在等快递/)
  })
})

test('鲸澜立的预案带日落；他在面板上续期', async () => {
  await withHome(async (home) => {
    const t = setup(home, { state: 'plan' })
    const made = t.big.tools.preset.execute({
      action: 'set',
      preset: { id: 'morning', 名称: '早上先定计划', 状态: '影子', 何时: { 事件: '到点', 时刻: '07:00' }, 动作: [{ 进入计划: {} }], 解除: ['用户确认'] },
    })
    assert.equal(made.ok, true, made.text)
    const codex = await get(t, '/codex')
    const reg = codex.body.regulations.find((x) => x.id === 'R-morning')
    assert.equal(reg.by, '鲸澜')
    assert.equal(reg.daysLeft, 30)
    const r = await t.presets({ action: 'renew', id: 'morning' })
    assert.equal(r.body.ok, true)
  })
})
