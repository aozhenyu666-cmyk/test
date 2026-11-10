// 档位（闲时 / 忙时）、聊天里直接管他（dafeiyu_control）、Gemini Live 搭桥（去：背景；回：读回复盘）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { existsSync, readFileSync } from 'node:fs'
import { loadWallMemory, noteDone, notePromised } from '../lib/wall_auditor.js'
import { markReviewed, notePrediction, parseCase, predictionScore, renderCase, updateCase, wallPersonaText } from '../lib/wall_desk.js'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-desk-'))
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
const WALL = 'session-wall'
function setup(home, extra = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, wallSessionId: WALL, quietStart: 12, quietEnd: 12, ...extra }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const wall = agentOf(WALL)
  const h = host({ [SIS]: sis, [BIG]: big, [WALL]: wall })
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const request = (id) => call(h.handler(), 'POST', '/dsh-go-companion/v1/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 30000 })
  const say = (sid, text) => h.emit(sid, 'user/message', { id: 'u-' + Math.random(), content: [{ type: 'text', text }], source: { kind: 'user' } })
  return { sis, big, wall, h, plan, request, say }
}


// ---------------------------------------------------------------- 纯函数

test('墙的人设：经理、不先开口、三格、不能用嘴开门；数据在最后', () => {
  const text = wallPersonaText({ owner: '阿鱼', meeting: 'morning', brief: {
    yesterday: { done: 1, total: 3 }, rate: { done: 4, total: 10 }, prediction: { n: 3, his: 1.3, wall: 0.3, bias: 1.3 },
    selfInstructions: { week: 2 }, today: { tasks: [] }, cases: [{ name: '就业方向', due: true }], generalDue: true,
  } })
  assert.match(text, /上级、经理/)
  assert.match(text, /从不先开口/)
  assert.match(text, /下一步是什么、反例是什么、什么情况说明我错了/)
  assert.match(text, /不能用嘴开门/)
  assert.match(text, /这一次是：晨间计划/)
  assert.match(text, /昨天：立项 3 件，有证据完成 1 件/)
  assert.match(text, /他平均差 1.3 件，你平均差 0.3 件，他偏乐观/)
  assert.match(text, /本周他主动开口 2 次/)
  assert.match(text, /到期该查的案卷：就业方向/)
  assert.match(text, /总谈已超过一周没开/)
})

test('预测账本：只给对过账的日子打分，他高估自己记成偏乐观', () => {
  let m = loadWallMemory({})
  m = notePromised(m, '2026-11-08', 3)
  m = notePrediction(m, '2026-11-08', { mine: 3, wall: 1, stuck: '下午犯困' })
  m = noteDone(m, '2026-11-08', 1)
  assert.equal(predictionScore(m, '2026-11-09').n, 0, '没对账不打分')
  m = markReviewed(m, '2026-11-08')
  assert.deepEqual(predictionScore(m, '2026-11-09'), { n: 1, his: 2, wall: 0, bias: 2 })
})

test('案卷：现状覆盖、决定追加带日期、下次检查只认 YYYY-MM-DD；读回来一样', () => {
  const a = updateCase(null, { name: '就业方向', summary: '投了 20 家，2 个面试', decision: '先投数据分析岗', next: '2026-11-16' }, '2026-11-09')
  const b = updateCase(parseCase(renderCase(a)), { name: '就业方向', decision: '加投运营岗', next: '下周' }, '2026-11-12')
  assert.equal(b.summary, '投了 20 家，2 个面试', '没给现状就保留')
  assert.equal(b.next, '2026-11-16', '日期格式不对就保留原来的')
  assert.deepEqual(b.log, ['2026-11-09：先投数据分析岗', '2026-11-12：加投运营岗'])
  const back = parseCase(renderCase(b))
  assert.equal(back.name, '就业方向')
  assert.deepEqual(back.log, b.log)
})

// ---------------------------------------------------------------- 走一遍

test('墙的会话：自己的人设和工具；没有任何开关，也不收姐妹那套口令', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan({ app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' })
    const names = Object.keys(t.wall.tools).sort()
    assert.deepEqual(names, ['case_file', 'predict', 'submit_to_wall', 'vault_recent_notes', 'vault_search'])
    const persona = t.wall.contexts.find((c) => typeof c.text === 'function')
    assert.equal(persona.name, 'dafeiyu-wall')
    assert.match(persona.text(), /从不先开口/)
    assert.doesNotMatch(persona.text(), /鱼丸是陪聊搭子/)
    // 对墙说"拒绝模式 / 忙时"不切档：那是姐妹的口令。
    await t.say(WALL, '忙时 60 分钟')
    const cfg = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.notEqual(cfg.gear, 'busy')
  })
})

test('面对墙：他先开口记一笔、开一场会谈；没选墙的会话就说清楚', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan({ app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' })
    const r = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/say', { v: 1, requestId: 'say-wall-0001', text: '今天先把简历改完', role: 'wall', meeting: 'morning', opener: true })
    assert.equal(r.status, 202, JSON.stringify(r.body))
    assert.equal(t.wall.sent.length, 1)
    assert.equal(t.wall.sent[0].content[0].text, '今天先把简历改完')
    assert.equal(t.sis.sent.length, 0, '不会落到妹妹那里')
    const persona = t.wall.contexts.find((c) => typeof c.text === 'function')
    assert.match(persona.text(), /这一次是：晨间计划/)
    const b = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/wall', { action: 'brief' })
    assert.equal(b.body.brief.selfInstructions.week, 1)
    assert.equal(b.body.brief.wallSession, true)
    assert.equal(b.body.brief.generalDue, true, '还没开过总谈')
    // 接着说（不是开场）不再记"主动开口"。
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/say', { v: 1, requestId: 'say-wall-0002', text: '那就两件', role: 'wall' })
    const b2 = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/wall', { action: 'brief' })
    assert.equal(b2.body.brief.selfInstructions.week, 1)
  })
  await withHome(async (home) => {
    const t = setup(home)
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/config', { wallSessionId: null })
    const r = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/say', { v: 1, text: '在吗', role: 'wall' })
    assert.equal(r.status, 409)
    assert.equal(r.body.error.code, 'no-wall-session')
  })
})

test('案卷和预测工具：写进 大肥鱼/墙/案卷/，数据条看得到到期', async () => {
  await withHome(async (home) => {
    const vault = join(home, 'vault')
    mkdirSync(vault, { recursive: true })
    const t = setup(home, { notesPath: vault })
    await t.plan({ app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' })
    const up = t.wall.tools.case_file.execute({ action: 'update', name: '就业方向', summary: '投了 20 家', decision: '先投数据分析岗', next: '2000-01-01' })
    assert.equal(up.ok, true, up.text)
    const file = join(vault, '大肥鱼', '墙', '案卷', '就业方向.md')
    assert.match(readFileSync(file, 'utf8'), /先投数据分析岗/)
    assert.match(t.wall.tools.case_file.execute({ action: 'read', name: '就业方向' }).text, /投了 20 家/)
    assert.match(t.wall.tools.case_file.execute({ action: 'list' }).text, /已到期/)
    assert.equal(t.wall.tools.predict.execute({ mine: 3, wall: 1, stuck: '下午犯困' }).ok, true)
    const b = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/wall', { action: 'brief' })
    assert.deepEqual(b.body.brief.cases.map((c) => [c.name, c.due]), [['就业方向', true]])
    assert.equal(b.body.brief.today.prediction.mine, 3)
  })
})
