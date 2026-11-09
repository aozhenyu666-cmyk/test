// 第二轮·墙的工具与 /wall 路由：放松申请（窗口+额度）、收紧、status。
// 第四轮 R4-5 的 /sisters 也用同一套 harness：姐妹页只读，且不能有放松入口。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'

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
  const wall = (body) => call(h.handler(), 'POST', '/dsh-go-companion/v1/wall', { timeZone: 'Asia/Shanghai', ...body })
  const sisters = () => call(h.handler(), 'POST', '/dsh-go-companion/v1/sisters', { timeZone: 'Asia/Shanghai' })
  return { sis, big, h, plan, say, wall, sisters }
}

const openWindow = () => ({ day: 1, openAt: Date.now() - 60_000, closeAt: Date.now() + 10 * 60_000, notified: true, used: false })

test('/wall status：默认白天锁着，带本周额度', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.wall({ action: 'status' })
    assert.equal(r.body.ok, true)
    assert.equal(typeof r.body.wall.locked, 'boolean')
    assert.equal(r.body.wall.relaxRemaining, 3)
  })
})

test('/wall relax：窗口外拒绝', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.wall({ action: 'relax', reason: '明天想打会游戏' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'no-window')
  })
})

test('/wall relax：窗口内批给明天，额度减一，同窗口第二次被拒', async () => {
  await withHome(async (home) => {
    const t = setup(home, { applyWindow: openWindow() })
    const r = await t.wall({ action: 'relax', atHour: 20, minutes: 60, reason: '明天周末想玩一小时' })
    assert.equal(r.body.ok, true, JSON.stringify(r.body))
    assert.equal(r.body.wall.applyWindowOpen, true)
    // 额度按「目标那一天」所在周扣：周日夜跨零点申请明天会落到下一周，所以看落盘的周用量，别依赖真实日期。
    const cfg = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.equal(cfg.relaxWeek.used, 1)
    const again = await t.wall({ action: 'relax', reason: '再来一段' })
    assert.equal(again.body.ok, false)
    assert.equal(again.body.error.code, 'window-used')
  })
})

test('/wall relax：没理由不批', async () => {
  await withHome(async (home) => {
    const t = setup(home, { applyWindow: openWindow() })
    const r = await t.wall({ action: 'relax', reason: '' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'no-reason')
  })
})

test('/wall tighten：立即锁今天，status 显示收紧', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.wall({ action: 'tighten', reason: '今天状态不好' })
    assert.equal(r.body.ok, true)
    assert.equal(r.body.wall.tightenToday, true)
    assert.equal(r.body.wall.locked, true)
  })
})

test('第四轮 R4-2：聊天里放不了——request_relax 和手机端 /wall 一样卡在申请窗口，grant_break 不存在', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan({ app: 'com.game', fun: true, screenOn: true, local: '15:00' })
    for (const role of [t.big, t.sis]) assert.equal(role.tools.grant_break, undefined, 'grant_break 不给')
    // 下午窗口没开：姐妹替他提也批不下来，和手机端同一个口子。
    const viaTool = t.big.tools.request_relax.execute({ atHour: 20, minutes: 60, reason: '周末想放松一下' })
    assert.equal(viaTool.ok, false, viaTool.text)
    const r = await t.wall({ action: 'relax', atHour: 20, minutes: 60, reason: '周末想放松一下' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'no-window')
  })
})

test('第四轮 R4-5：/sisters 给两张角色卡、记忆、最近对话和今日审计', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan({ app: 'com.game', fun: true, screenOn: true, local: '15:00' })
    const r = await t.sisters()
    assert.equal(r.status, 200)
    assert.equal(r.body.ok, true, JSON.stringify(r.body))
    // 两张卡，妹妹在前姐姐在后，名字跟着 config 走
    assert.deepEqual(r.body.cards.map((c) => c.role), ['companion', 'strategist'])
    assert.equal(r.body.cards[0].name, '鱼丸')
    assert.equal(r.body.cards[1].name, '鲸澜')
    // 每张卡都有人设，且是给用户看的那一句（不带写给模型的抬头）
    for (const c of r.body.cards) {
      assert.ok(c.persona.length > 0, c.role + ' 缺人设')
      assert.ok(!c.persona.includes('你的设定'))
    }
    // 审计面板：字段齐全，没审过时计数为 0 而不是缺失
    assert.equal(typeof r.body.audit.promised, 'number')
    assert.equal(typeof r.body.audit.ratePct, 'number')
    assert.equal(r.body.audit.rateDays, 14)
    // 记忆和最近对话是数组（今天还没聊过就是空的）
    assert.ok(Array.isArray(r.body.memory))
    assert.ok(Array.isArray(r.body.timeline))
  })
})

test('第四轮 R4-5：/sisters 只读——返回体里没有任何放松/解锁入口', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.sisters()
    const flat = JSON.stringify(r.body)
    // 页面上不该出现能松开规则的字段或动作名。
    for (const bad of ['relax', 'tighten', 'unlock', 'grant_break', 'openUntil']) {
      assert.equal(flat.includes('"' + bad + '"'), false, '/sisters 不该带 ' + bad)
    }
    // 墙只以审计面板的形式出现，不是角色卡。
    assert.equal(r.body.cards.some((c) => c.role === 'wall'), false)
  })
})
