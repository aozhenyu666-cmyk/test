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
  const deposit = (body) => call(h.handler(), 'POST', '/dsh-go-companion/v1/deposit', { timeZone: 'Asia/Shanghai', ...body })
  const status = () => call(h.handler(), 'GET', '/dsh-go-companion/v1/status')
  // A4：白天的记录槽。晚间的汇报要能对得上今天记的东西，所以这里先记再交。
  const note = (department, text) => deposit({ action: 'slot', department, text })
  // 四段交齐（硬约束：交齐才有放风额度）。每段正文故意不一样，免得被判成「复读」；
  // A4 起正文还要和当天的一条记录对得上，否则会被「无据」退回——所以每段先记一条。
  const fillAll = async () => {
    for (const key of ['intel', 'thinking', 'behavior', 'coord']) {
      await note(key, '关于 ' + key + ' 的一条日间记录')
      await deposit({ action: 'submit', department: key, report: '字'.repeat(200) + '，关于 ' + key, thought: '想'.repeat(80) + key })
    }
  }
  return { sis, big, h, plan, say, wall, sisters, deposit, note, status, fillAll }
}

const openWindow = () => ({ day: 1, openAt: Date.now() - 60_000, closeAt: Date.now() + 10 * 60_000, notified: true, used: false })

test('/wall status：默认白天锁着，带本周额度', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.wall({ action: 'status' })
    assert.equal(r.body.ok, true)
    assert.equal(typeof r.body.wall.locked, 'boolean')
    // 第五轮起：四部门当天没交齐，放风额度就是 0（默认每周 3 天，交齐才恢复，见下面那条硬约束用例）。
    assert.equal(r.body.wall.relaxRemaining, 0)
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
    // 第五轮硬约束：四段交齐才批放松。
    await t.fillAll()
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
    await t.fillAll()
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

test('第四轮补：request_relax 仍卡在申请窗口；grant_break 现在给了，当场放得开', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan({ app: 'com.game', fun: true, screenOn: true, local: '15:00' })
    // 明天那段还是只能走窗口：下午窗口没开，姐妹替他提也批不下来。
    const viaTool = t.big.tools.request_relax.execute({ atHour: 20, minutes: 60, reason: '周末想放松一下' })
    assert.equal(viaTool.ok, false, viaTool.text)
    const r = await t.wall({ action: 'relax', atHour: 20, minutes: 60, reason: '周末想放松一下' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'no-window')
    // 当场那段：grant_break 给了，两边都能放，放完 config 里 breakUntil 在、锁不下发。
    for (const role of [t.big, t.sis]) assert.ok(role.tools.grant_break, 'grant_break 给两姐妹')
    const broke = t.sis.tools.grant_break.execute({ minutes: 10, reason: '刚考完试想歇一会儿' })
    assert.equal(broke.ok, true, broke.text)
    const saved = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.ok(saved.breakUntil > Date.now() + 9 * 60_000, JSON.stringify(saved.breakUntil))
    // 放风期间再刷游戏：不锁。
    const p = await t.plan({ app: 'com.game', fun: true, screenOn: true, local: '15:00' })
    assert.equal(p.body.refuse, null, '放风盖过拒绝模式')
    assert.ok(p.body.force.level < 3, '放风期间不锁')
    // 上限 30 分钟，超了压回来。
    const long = t.sis.tools.grant_break.execute({ minutes: 999, reason: '再玩一会儿' })
    const saved2 = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.ok(saved2.breakUntil <= Date.now() + 30 * 60_000 + 5_000, '单次最多 30 分钟')
    assert.equal(long.ok, true)
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

test('第四轮 R4-3：设置页的「笔记库位置」读得到也写得进', async () => {
  await withHome(async (home) => {
    const vault = join(home, 'vault')
    mkdirSync(vault, { recursive: true })
    const t = setup(home, { notesPath: vault })
    // 读：/status 的 config 里要带上填的和实际在用的目录，设置页才知道现在指到哪。
    const got = await call(t.h.handler(), 'GET', '/dsh-go-companion/v1/status')
    assert.equal(got.status, 200, JSON.stringify(got.body))
    assert.equal(got.body.config.notesPath, vault)
    assert.equal(got.body.config.notesDir, vault)
    // 写：设置页那一栏走的就是 POST /config。
    const set = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/config', { notesPath: vault })
    assert.equal(set.body.config.notesPath, vault)
    // 清掉 = 交回自动查找，不是记住一个空目录。
    const cleared = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/config', { notesPath: '' })
    assert.equal(cleared.body.config.notesPath, '')
  })
})

const report = (n = 200) => '字'.repeat(n)

test('第五轮 /deposit：一开始四段全缺，交一段就少一段', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const first = await t.deposit({})
    assert.equal(first.body.ok, true)
    assert.equal(first.body.deposit.done, 0)
    assert.equal(first.body.deposit.total, 4)
    assert.deepEqual(first.body.deposit.missing, ['信息收集', '谋划思考', '行为管理', '统筹规划'])
    // 只传一段，别的照旧。A4 起先记一条记录，不然会被「无据」退回。
    await t.note('intel', '关于 intel 的一条日间记录')
    const one = await t.deposit({ action: 'submit', department: 'intel', report: report() + '，关于 intel', thought: '想'.repeat(80) })
    assert.equal(one.body.ok, true, JSON.stringify(one.body))
    assert.equal(one.body.deposit.done, 1)
    assert.deepEqual(one.body.deposit.missing, ['谋划思考', '行为管理', '统筹规划'])
  })
})

test('第五轮 /deposit：字数不够当场退回，不落盘', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const thin = await t.deposit({ action: 'submit', department: 'intel', report: '太短', thought: '短' })
    assert.equal(thin.body.ok, false)
    assert.equal(thin.body.error.code, 'too-thin')
    assert.equal(thin.body.deposit.done, 0)
    // 没有这个部门也退回
    const bad = await t.deposit({ action: 'submit', department: 'nope', report: report(), thought: '想'.repeat(80) })
    assert.equal(bad.body.ok, false)
    assert.equal(bad.body.error.code, 'bad-department')
  })
})

test('第五轮 硬约束：四段没交齐，当天没有放风额度；交齐后恢复', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    // 默认每周 3 天，第一天没用过 → 没这条约束时是 3
    const before = await t.wall({ action: 'status' })
    assert.equal(before.body.wall.relaxRemaining, 0, '没交齐就该是 0')
    for (const key of ['intel', 'thinking', 'behavior', 'coord']) {
      // A4：每段正文要先有一条当天记录对得上（见「晚间对照」）。
      await t.note(key, '关于 ' + key + ' 的一条日间记录')
      const r = await t.deposit({ action: 'submit', department: key, report: report() + '，关于 ' + key, thought: '想'.repeat(80) + key })
      assert.equal(r.body.ok, true, JSON.stringify(r.body))
    }
    const after = await t.wall({ action: 'status' })
    assert.equal(after.body.wall.relaxRemaining, 3, JSON.stringify(after.body.wall))
  })
})

test('第五轮 硬约束：窗口开着也没用——四段没交齐，放松申请被挡', async () => {
  await withHome(async (home) => {
    const t = setup(home, { applyWindow: openWindow() })
    const blocked = await t.wall({ action: 'relax', atHour: 20, minutes: 60, reason: '明天想玩一小时' })
    assert.equal(blocked.body.ok, false, JSON.stringify(blocked.body))
    assert.equal(blocked.body.error.code, 'deposit')
    // 同一轮里交齐了就能批，说明挡的是「没交」，不是别的。
    await t.fillAll()
    const ok = await t.wall({ action: 'relax', atHour: 20, minutes: 60, reason: '明天想玩一小时' })
    assert.equal(ok.body.ok, true, JSON.stringify(ok.body))
  })
})

test('第五轮 /status 带四部门提交摘要，界面一眼看到缺哪几段', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const s = await t.status()
    assert.equal(s.status, 200)
    assert.equal(s.body.deposit.total, 4)
    assert.equal(s.body.deposit.done, 0)
    assert.equal(s.body.deposit.items.length, 4)
    assert.ok(s.body.deposit.missingText.includes('信息收集'))
  })
})

test('第五轮 墙的界面：额度为 0 时要说清是四段没交齐，交齐后这句消失', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const before = await t.wall({ action: 'status' })
    assert.equal(before.body.wall.relaxRemaining, 0)
    assert.equal(before.body.wall.depositDone, 0)
    assert.equal(before.body.wall.depositTotal, 4)
    // 界面直接把这几段摆出来，不让他只看到一个 0。
    assert.ok(before.body.wall.depositMissing.includes('信息收集'), JSON.stringify(before.body.wall))
    assert.ok(before.body.wall.depositMissing.includes('统筹规划'), JSON.stringify(before.body.wall))
    await t.fillAll()
    const after = await t.wall({ action: 'status' })
    assert.equal(after.body.wall.relaxRemaining, 3)
    assert.equal(after.body.wall.depositDone, 4)
    assert.equal(after.body.wall.depositMissing, '')
  })
})
