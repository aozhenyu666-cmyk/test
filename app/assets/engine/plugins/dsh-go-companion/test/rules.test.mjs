// P0 本地规则快照（C2）与晚间对账一并清算任务（C5）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { existsSync, readFileSync } from 'node:fs'

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

/** 让某个会话"说完"一轮：Desk 认到 user/message 再到 turn/end 才算 generated。 */
async function finishTurn(h, agent, turn, text) {
  const m = agent.sent[agent.sent.length - 1]
  await h.emit(agent.id, 'turn/start', { turn })
  await h.emit(agent.id, 'user/message', { id: m.id, content: m.content, source: m.source, turn })
  await h.emit(agent.id, 'assistant/message', { turn, message: { content: [{ type: 'text', text }] } })
  await h.emit(agent.id, 'turn/end', { turn, reason: { kind: 'completed' } })
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
  const home = mkdtempSync(join(tmpdir(), 'companion-rules-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}


const SIS = 'session-sis'
const BIG = 'session-big'
function setup(home, extra = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, ...extra }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big })
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const say = (sid, text) => h.emit(sid, 'user/message', { id: 'u-' + Math.random(), content: [{ type: 'text', text }], source: { kind: 'user' } })
  return { sis, big, h, plan, say }
}
const at = (local) => ({ app: 'com.game', label: '王者', fun: true, minutes: 0, screenOn: true, local })
const kinds = (body) => (body.rules?.rules || []).map((r) => r.kind).sort()

test('快照：忙时下发 refuse 规则，带到期时间和解锁要求', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.say(SIS, '开始学习 1 小时')
    const r = await t.plan(at('15:00'))
    assert.equal(r.body.rules.v, 1)
    const rule = r.body.rules.rules.find((x) => x.kind === 'refuse')
    assert.ok(rule, JSON.stringify(r.body.rules))
    assert.ok(rule.until > Date.now() + 50 * 60_000 && rule.until <= Date.now() + 61 * 60_000)
    assert.match(rule.why, /忙时/)
    assert.equal(rule.until, r.body.refuse.until, '和合成的 refuse.until 是同一个时间')
  })
})

test('快照：他点「开始」进冲刺后下发 task 规则，到期就是冲刺结束；临时封禁名单带到期时间', async () => {
  await withHome(async (home) => {
    const t = setup(home, { blockExtra: [{ app: 'com.ss.android.ugc.aweme', until: Date.now() + 20 * 60_000 }, { app: 'com.android.dialer', until: Date.now() + 20 * 60_000 }] })
    await t.plan(at('15:00'))
    // 第三轮：没有 start_task_mode 了，冲刺由手机点「开始」报 /event 进来。
    const res = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', {
      type: 'start', task: '写完周报', minutes: 30,
    })
    assert.equal(res.body.ok, true, JSON.stringify(res.body))
    const r = await t.plan(at('15:01'))
    const task = r.body.rules.rules.filter((x) => x.kind === 'task')
    assert.ok(task.length >= 1, JSON.stringify(r.body.rules))
    assert.ok(task.every((x) => x.until <= Date.now() + 31 * 60_000), '冲刺到点就到期，不会拖两小时')
    assert.match(task[0].why, /验收合格/)
    assert.ok(r.body.rules.block['com.ss.android.ugc.aweme'] > Date.now())
    assert.equal(r.body.rules.block['com.android.dialer'], undefined, '电话永不进封禁名单')
  })
})

test('快照：放风期间不下发任何锁', async () => {
  await withHome(async (home) => {
    const t = setup(home, { refuseUntil: Date.now() + 30 * 60_000, breakUntil: Date.now() + 5 * 60_000 })
    const r = await t.plan(at('15:00'))
    assert.deepEqual(r.body.rules.rules, [])
  })
})

test('晚间对账：任务没验收时 21:30 两层锁都在；裁决满意后一起解除（C5）', async () => {
  await withHome(async (home) => {
    // 关掉墙的默认锁，专测 review/task 两层（墙的默认锁另有 wall.test.mjs）。
    const t = setup(home, { wall: { enabled: false } })
    await t.plan(at('21:00'))
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'start', task: '写完周报', minutes: 120 })
    const both = await t.plan(at('21:40'))
    assert.deepEqual([...new Set(kinds(both.body))], ['review', 'task'], JSON.stringify(both.body.rules))
    const review = both.body.rules.rules.find((x) => x.kind === 'review')
    assert.ok(review.until <= Date.now() + 61 * 60_000, '对账门有封顶时间，引擎死了也会按时关')
    // 第三轮：鲸澜来对过账（reviewBall）后，门由裁决关，不再看他回了多少字。
    const file = join(home, 'companion', 'rhythm.json')
    writeFileSync(file, JSON.stringify({ ...JSON.parse(readFileSync(file, 'utf8')), reviewBall: true }))
    // 第四轮：对账交给墙，墙逐件核（今天没立项就直接收），结果作用于明天。
    const verdict = t.big.tools.submit_to_wall.execute({ kind: 'evening' })
    assert.equal(verdict.pass, true, verdict.text)
    assert.match(verdict.text, /^〔墙〕 晚间对账/)
    const after = await t.plan(at('21:45'))
    assert.deepEqual(kinds(after.body), [], JSON.stringify(after.body.rules))
    assert.equal(after.body.refuse, null)
    const ledger = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    assert.match(ledger, /"state":"task-settled"/)
  })
})
