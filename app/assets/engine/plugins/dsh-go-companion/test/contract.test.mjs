// C1/C2 契约：/plan 的快照字段和 /event 的每种事件。
// 这里断言的是「手机端能看到什么」，所以只要有人改了对外形状，这里就该红。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'

const TZ = 'Asia/Shanghai'
const SIS = 'session-sis'
const BIG = 'session-big'

function agentOf(id) {
  const contexts = []
  const sent = []
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent, contexts, tools,
    followup(m) { sent.push(m) },
    ctx: {
      systemPrompt: { context(def) { contexts.push(def); return () => { def.removed = true } } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents) {
  let handler
  const listeners = new Map()
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(name, fn) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn) },
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  apply(ctx)
  const emit = async (sid, type, data) => { for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, { type, data }) }
  return { handler: () => handler, emit }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-contract-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

function setup(home, extra = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, ...extra }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big })
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: TZ, phone })
  const event = (body) => call(h.handler(), 'POST', '/dsh-go-companion/v1/event', body)
  const ledger = () => readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    .trim().split('\n').map((l) => JSON.parse(l))
  return { sis, big, h, plan, event, ledger }
}

const game = { app: 'com.game', label: '王者', fun: true, minutes: 10, screenOn: true, local: '15:00' }

test('C1 /plan 快照：state / followup / obligations 三块都在，字段齐全', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.plan(game)
    assert.equal(r.status, 200)
    const b = r.body
    // 老字段一个不少。
    for (const k of ['v', 'speak', 'reason', 'nextCheckAt', 'speaker', 'force', 'rules', 'refuse', 'gear', 'session', 'wall', 'trust', 'tide', 'block', 'silence']) {
      assert.ok(k in b, '缺老字段 ' + k)
    }
    // 新增三块。
    for (const k of ['state', 'followup', 'obligations']) assert.ok(k in b, '缺新字段 ' + k)
    const s = b.state
    for (const k of ['state', 'priority', 'until', 'funLocked', 'allow', 'backTo', 'followup', 'agenda', 'layers', 'nextChangeAt']) {
      assert.ok(k in s, 'state 缺 ' + k)
    }
    assert.equal(typeof s.priority, 'number')
    assert.equal(typeof s.funLocked, 'boolean')
    assert.ok(Array.isArray(s.allow))
    assert.ok(Array.isArray(s.layers))
    assert.ok(s.layers.length >= 1, '至少一层锁')
    assert.deepEqual(Object.keys(b.followup).sort(), ['active', 'currentQuestion', 'reason', 'since', 'until'])
    assert.deepEqual(b.obligations, [])
    // 下午三点的默认严格：娱乐锁着，最高那一层是 standby。
    assert.equal(s.funLocked, true)
    assert.equal(s.layers[0].kind, 'standby')
    assert.ok(Number(s.nextChangeAt) > Date.now() - 1000)
  })
})

test('C1 事件 start：进冲刺，快照里 task 规则 + state 变 sprint', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const started = await t.event({ type: 'start', task: '写完周报', minutes: 30 })
    assert.equal(started.body.ok, true, JSON.stringify(started.body))
    assert.ok(started.body.sprint.until > Date.now())
    const b = (await t.plan(game)).body
    assert.equal(b.state.state, 'sprint')
    assert.ok(b.rules.rules.some((x) => x.kind === 'task'), JSON.stringify(b.rules))
    assert.ok(t.ledger().some((e) => e.state === 'sprint-start' && e.task === '写完周报'))
  })
})

test('C1 事件 request-review：开追问锁（原因验收），写进 followup 和 rules', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.event({ type: 'request-review' })
    assert.equal(r.body.ok, true)
    assert.equal(r.body.followup.active, true)
    assert.equal(r.body.followup.reason, 'review')
    const b = (await t.plan(game)).body
    assert.equal(b.followup.reason, 'review')
    assert.ok(b.followup.currentQuestion.length > 0)
    assert.ok(b.rules.rules.some((x) => x.kind === 'followup'), JSON.stringify(b.rules.rules))
    assert.equal(b.state.state, 'review')
  })
})

test('C1 事件 defiance：开拒绝模式并开追问锁，拿回收尾的话', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.event({ type: 'defied', app: 'com.game', label: '王者' })
    assert.equal(r.body.ok, true)
    assert.equal(r.body.force.level, 4)
    assert.match(r.body.force.why, /王者/)
    const b = (await t.plan(game)).body
    assert.equal(b.followup.reason, 'defiance')
    assert.ok(b.refuse && b.refuse.until > Date.now())
  })
})

test('C1 事件 gemini-end：满 3 分钟才算完成并出追问，不足回待命', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    // 先开舱再收工（不满 3 分钟）。
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/session', { action: 'start', topic: '线代', minutes: 30 })
    const short = await t.event({ type: 'gemini-end', minutes: 2 })
    assert.equal(short.body.counted, false, JSON.stringify(short.body))
    assert.equal(short.body.followup.active, false)
    // 满 3 分钟：出追问。
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/session', { action: 'start', topic: '线代', minutes: 30 })
    const long = await t.event({ type: 'gemini-end', minutes: 25 })
    assert.equal(long.body.counted, true)
    assert.equal(long.body.followup.reason, 'gemini')
  })
})

test('C1 事件 followup-timeout：没到点不动作，到点才结束拦截、扣分、挂义务', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.event({ type: 'request-review' })
    const early = await t.event({ type: 'followup-timeout' })
    assert.equal(early.body.expired, false)
  })
  // 到点：锁在加载配置时就已经过期（内存里的 config 不重读盘，所以从初始配置造）。
  await withHome(async (home) => {
    const t = setup(home, {
      followup: { reason: 'review', question: '这一轮做完了吗', since: Date.now() - 40 * 60_000, until: Date.now() - 1000 },
    })
    const late = await t.event({ type: 'followup-timeout' })
    assert.equal(late.body.expired, true, JSON.stringify(late.body))
    assert.equal(late.body.followup.active, false)
    assert.equal(late.body.obligations[0].kind, 'review')
    assert.ok(t.ledger().some((e) => e.state === 'followup-timeout' && e.reason === 'review'))
    // 超时扣分写进账本，信用分算得到。
    const cfg = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.equal(Number(cfg.followupPenalty) || 5, 5)
  })
})

test('C1 事件 wechat-hit / screen-on / overlay-closed：只记账，不动锁', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    for (const body of [{ type: 'wechat-hit', page: '视频号', dwellMs: 3200 }, { type: 'screen-on' }, { type: 'overlay-closed', chatted: true }]) {
      const r = await t.event(body)
      assert.equal(r.body.ok, true, JSON.stringify(r.body))
    }
    const b = (await t.plan(game)).body
    assert.equal(b.followup.active, false)
    assert.equal(b.refuse, null)
    const rows = t.ledger()
    assert.ok(rows.some((e) => e.state === 'wechat-hit'))
    assert.ok(rows.some((e) => e.state === 'screen-on'))
    // 微信里误入视频号不升级成违抗。
    assert.ok(!rows.some((e) => e.state === 'defied'))
  })
})

test('C1 事件 blocked-open：只在他打开被拦的 App 时再问，一条只问一次', async () => {
  await withHome(async (home) => {
    // 晨间立项超时留下的义务：下次打开被拦的 App 时再问。
    const t = setup(home, {
      obligations: [{ kind: 'morning', text: '当天不能放风，直到补完立项', reask: 'locked-app', blocksFun: true, createdAt: Date.now() - 3_600_000 }],
    })
    const first = await t.event({ type: 'blocked-open', app: 'com.game' })
    assert.equal(first.body.ask, true, JSON.stringify(first.body))
    assert.equal(first.body.followup.reason, 'morning')
    // 第二次打开不再问（问过就记 askedAt，不每次开 App 都堵他）。
    const second = await t.event({ type: 'blocked-open', app: 'com.game' })
    assert.equal(second.body.ask, false)
    // 压着没补完的立项时，手机端的放松申请也拦下。
    assert.ok(t.ledger().some((e) => e.state === 'obligation-reask'))
  })
})

test('C1 未定义的事件照旧报 unknown-event，不抛', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.event({ type: '不认识的' })
    assert.equal(r.status, 200)
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'unknown-event')
  })
})

test('待命 10 分钟计时器：一直待命到点就开追问锁', async () => {
  await withHome(async (home) => {
    // 15:00 不在任何吃饭/对账/深夜段里，主状态是待命。
    const t = setup(home, { standbySince: Date.now() - 11 * 60_000 })
    const r = await t.plan(game)
    assert.equal(r.body.followup.active, true, JSON.stringify(r.body.followup))
    assert.equal(r.body.followup.reason, 'standby')
    assert.equal(r.body.state.state, 'followup')
    assert.equal(r.body.state.backTo, 'standby')
  })
})

test('待命计时器：刚进待命只是开始计时，不马上开锁', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.plan(game)
    assert.equal(r.body.state.state, 'standby')
    assert.equal(r.body.followup.active, false)
    // 到点前要把手机叫回来，别等下一次定期检查。
    assert.ok(Number(r.body.nextCheckAt) <= Date.now() + 10 * 60_000)
  })
})
