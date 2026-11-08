// 档位（闲时 / 忙时）、聊天里直接管他（dafeiyu_control）、Gemini Live 搭桥（去：背景；回：读回复盘）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { existsSync, readFileSync } from 'node:fs'
import { gearSwitch } from '../lib/gear.js'
import { trustScore } from '../lib/trust.js'
import { trackGemini } from '../lib/gemini.js'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-gear-'))
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
  const request = (id) => call(h.handler(), 'POST', '/dsh-go-companion/v1/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 30000 })
  const say = (sid, text) => h.emit(sid, 'user/message', { id: 'u-' + Math.random(), content: [{ type: 'text', text }], source: { kind: 'user' } })
  return { sis, big, h, plan, request, say }
}
const wechat = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }
const game = { app: 'com.game', label: '王者', fun: true, minutes: 0, screenOn: true, local: '15:00' }
const gemini = (minutes) => ({ app: 'com.google.android.apps.bard', label: 'Gemini', minutes, screenOn: true, local: '15:00' })
const text = (agent) => agent.sent[agent.sent.length - 1].content[0].text

test('一句话切档：只认明确说法', () => {
  const now = 1_000_000
  // D5：忙时默认 40 分钟、最长 60 分钟；说 90 分钟也压到 60。
  assert.deepEqual(gearSwitch('忙时 90 分钟', now), { gear: 'busy', until: now + 60 * 60_000, what: '正事' })
  assert.equal(gearSwitch('忙时', now).until, now + 40 * 60_000, '没说时长默认 40 分钟')
  assert.equal(gearSwitch('忙时 5 分钟', now).until, now + 10 * 60_000, '最短 10 分钟')
  assert.equal(gearSwitch('我去做饭了', now).until, now + 40 * 60_000, '做饭这类默认 40 分钟')
  assert.equal(gearSwitch('我去做饭了', now).what, '做饭')
  assert.equal(gearSwitch('开始学习 2 小时', now).until, now + 60 * 60_000, '两小时压到 60 分钟')
  assert.equal(gearSwitch('开始学习', now).until, now + 40 * 60_000)
  assert.equal(gearSwitch('开始学习半小时', now).until, now + 30 * 60_000)
  assert.deepEqual(gearSwitch('忙完了', now), { gear: 'free' })
  assert.deepEqual(gearSwitch('闲时', now), { gear: 'free' })
  assert.equal(gearSwitch('今天上课好无聊', now), null)
  assert.equal(gearSwitch('我觉得做饭这件事其实挺有意思的你说呢，要不要一起研究一下菜谱', now), null, '长句不切')
})

test('忙时：不闲聊、娱乐一开就锁、到点报到（姐姐）、报到过就等下一次', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.say(SIS, '开始学习 1 小时')
    const quiet = await t.plan(wechat)
    assert.equal(quiet.body.speak, false)
    assert.equal(quiet.body.reason, 'busy', JSON.stringify(quiet.body))
    assert.equal(quiet.body.gear, 'busy')
    const lock = await t.plan(game)
    assert.equal(lock.body.force.level, 4, '忙时顺带拒绝模式')
    assert.ok(lock.body.refuse?.until > Date.now() + 50 * 60_000)
    assert.match(t.sis.contexts[0].text(), /他现在在忙：学习/)
  })
  await withHome(async (home) => {
    const now = Date.now()
    const t = setup(home, { gear: 'busy', gearUntil: now + 30 * 60_000, gearSince: now - 30 * 60_000, gearWhat: '写报告' })
    const due = await t.plan(wechat)
    assert.equal(due.body.speak, true, JSON.stringify(due.body))
    assert.equal(due.body.reason, 'checkin')
    assert.equal(due.body.speaker, 'strategist')
    const r = await t.request('req-checkin-01')
    assert.equal(r.status, 202, JSON.stringify(r.body))
    assert.equal(t.big.sent.length, 1, '报到由姐姐来')
    assert.match(text(t.big), /写报告.*报到/)
    assert.match(text(t.big), /30 字以内/)
    await finishTurn(t.h, t.big, 2, '报告写到哪了？')
    const after = await t.plan(wechat)
    assert.equal(after.body.reason, 'busy', '报到过了，等下一次')
  })
})

test('忙完：妹妹来迎接一次；"忙完了"提前收工也关掉拒绝模式', async () => {
  await withHome(async (home) => {
    const now = Date.now()
    const t = setup(home, { gear: 'busy', gearUntil: now - 60_000, gearSince: now - 41 * 60_000, gearWhat: '做饭' })
    const p = await t.plan(wechat)
    assert.equal(p.body.reason, 'busy-done', JSON.stringify(p.body))
    assert.equal(p.body.speaker, 'companion')
    await t.request('req-done-001')
    assert.match(text(t.sis), /刚忙完「做饭」（忙了 40 分钟）/)
    assert.match(text(t.sis), /报到/)
    assert.match(text(t.sis), /不评分、不扣分/)
    await finishTurn(t.h, t.sis, 2, '饭做好了没，香不香')
    const again = await t.plan(wechat)
    assert.notEqual(again.body.reason, 'busy-done', '只迎一次')
  })
  await withHome(async (home) => {
    const t = setup(home)
    await t.say(SIS, '我去做饭了')
    assert.equal((await t.plan(game)).body.force.level, 4)
    await t.say(SIS, '忙完了')
    const free = await t.plan(game)
    assert.equal(free.body.gear, 'free')
    assert.equal(free.body.refuse, null, '忙时带的拒绝模式一起关')
    assert.ok(free.body.force.level < 3)
  })
})

test('聊天里直接管他：dafeiyu_control', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const tool = t.sis.tools.dafeiyu_control
    assert.ok(tool && t.big.tools.dafeiyu_control)
    assert.equal(tool.execute({ action: 'busy', minutes: 45, what: '复习' }).ok, true)
    const p = await t.plan(game)
    assert.equal(p.body.gear, 'busy')
    assert.equal(p.body.force.level, 4)
    tool.execute({ action: 'free' })
    tool.execute({ action: 'refuse', minutes: 30 })
    assert.equal((await t.plan(game)).body.force.level, 4)
    tool.execute({ action: 'refuse_off' })
    assert.ok((await t.plan(game)).body.force.level < 3)
    assert.equal(tool.execute({ action: 'mute', minutes: 20 }).ok, true)
    assert.equal((await t.plan(wechat)).body.reason, 'muted')
    assert.match(t.big.contexts[0].text(), /dafeiyu_control/, '人设里说了有这个')
  })
})

test('忙时必须带时长：工具不给就是 40，给多了压到 60', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const tool = t.sis.tools.dafeiyu_control
    tool.execute({ action: 'busy', what: '复习' })
    const def = (await t.plan(game)).body.refuse
    const left = def.until - Date.now()
    assert.ok(left > 39 * 60_000 && left <= 40 * 60_000 + 5_000, String(left))
    tool.execute({ action: 'free' })
    tool.execute({ action: 'busy', minutes: 300, what: '复习' })
    const capped = (await t.plan(game)).body.refuse
    const left2 = capped.until - Date.now()
    assert.ok(left2 > 59 * 60_000 && left2 <= 60 * 60_000 + 5_000, String(left2))
  })
})

test('Gemini 去：背景写清他是谁、这次干嘛、怎么盯、怎么复盘；送过去的步骤；连麦中跑去玩会被锁', async () => {
  await withHome(async (home) => {
    const t = setup(home, { owner: '阿鱼' })
    const r = t.big.tools.gemini_live.execute({ topic: '复习线性代数第三章', minutes: 40, mode: 'study' })
    assert.equal(r.ok, true)
    assert.match(r.brief, /40 分钟你是阿鱼的语音搭档/)
    assert.match(r.brief, /复习线性代数第三章/)
    assert.match(r.brief, /学习陪练/)
    assert.match(r.brief, /【复盘】/)
    assert.match(r.text, /mcp__phone__clipboard/)
    assert.match(r.text, /mcp__phone__open app=Gemini/)
    const p = await t.plan(game)
    assert.equal(p.body.force.level, 4, '连麦期间去玩：锁')
    assert.ok(p.body.refuse.until >= Date.now() + 39 * 60_000)
    assert.match(t.sis.contexts[0].text(), /他正在跟 Gemini 连麦（复习线性代数第三章/)
    const inside = await t.plan(gemini(3))
    assert.equal(inside.body.speak, false)
    assert.equal(inside.body.reason, 'in-gemini')
    assert.ok(inside.body.nextCheckAt <= Date.now() + 2 * 60_000 + 1000, '两分钟看一次')
  })
})

test('Gemini 连麦中途跑了：妹妹叫他回去（能动手就直接打开 Gemini）', async () => {
  await withHome(async (home) => {
    const now = Date.now()
    const t = setup(home)
    writeFileSync(join(home, 'companion', 'gemini.json'), JSON.stringify({
      session: { topic: '想清楚要不要考研', until: now + 20 * 60_000, startedAt: now - 10 * 60_000 }, inSince: now - 9 * 60_000, lastInAt: now - 3 * 60_000,
    }))
    const p = await t.plan(wechat)
    assert.equal(p.body.reason, 'gemini-left', JSON.stringify(p.body))
    assert.equal(p.body.speaker, 'companion')
    await t.request('req-left-0001')
    assert.match(text(t.sis), /连麦（想清楚要不要考研，还剩约 20 分钟）连到一半跑去微信了/)
    assert.match(text(t.sis), /mcp__phone__open app=Gemini/)
  })
})

test('Gemini 回：出来几分钟后姐姐去后台屏读回那段对话、存进笔记库；两人之后都看得到', async () => {
  await withHome(async (home) => {
    const now = Date.now()
    const vault = join(home, 'vault')
    mkdirSync(vault)
    const t = setup(home, { notesPath: vault })
    // 在 Gemini 里待了 15 分钟，4 分钟前出来
    writeFileSync(join(home, 'companion', 'gemini.json'), JSON.stringify({ inSince: now - 19 * 60_000, lastInAt: now - 4 * 60_000 }))
    const p = await t.plan(wechat)
    assert.equal(p.body.reason, 'gemini-recap', JSON.stringify(p.body))
    assert.equal(p.body.speaker, 'strategist')
    await t.request('req-recap-001')
    const prompt = text(t.big)
    assert.match(prompt, /连麦了约 15 分钟/)
    assert.match(prompt, /background=true/)
    assert.match(prompt, /【复盘】/)
    assert.match(prompt, /gemini_log/)
    assert.match(prompt, /绝不编/)
    await finishTurn(t.h, t.big, 2, '你卡在特征值那步，明天先把习题 3.2 做了。')
    const again = await t.plan(wechat)
    assert.notEqual(again.body.reason, 'gemini-recap', '这一段只读一次')
    const r = t.big.tools.gemini_log.execute({ summary: '过了一遍特征值和特征向量', stuck: '对角化的条件', next: '明早做习题 3.2', quotes: ['我其实没懂为什么要对角化'], topic: '线代' })
    assert.equal(r.ok, true, r.text)
    const file = join(vault, 'Gemini', new Date().toISOString().slice(0, 10) + '.md')
    assert.ok(existsSync(file))
    const md = readFileSync(file, 'utf8')
    assert.match(md, /做了什么：过了一遍特征值/)
    assert.match(md, /> 我其实没懂为什么要对角化/)
    assert.match(t.sis.contexts[0].text(), /最近一次跟 Gemini 连麦.*过了一遍特征值.*下一步：明早做习题 3\.2/, '妹妹也知道')
  })
})

test('trackGemini：短暂进出不算一段；在里面时不复盘', () => {
  const now = 10_000_000
  let r = trackGemini({}, gemini(2), now)
  assert.equal(r.inside, true)
  assert.equal(r.state.inSince, now - 2 * 60_000)
  r = trackGemini(r.state, wechat, now + 4 * 60_000)
  assert.equal(r.recap, false, '只待了 2 分钟')
  const long = trackGemini({}, gemini(10), now)
  assert.equal(trackGemini(long.state, wechat, now + 60_000).recap, false, '刚出来 1 分钟，可能马上回去')
  assert.equal(trackGemini(long.state, wechat, now + 4 * 60_000).recap, true)
})

test('旧的低频默认（每天 6 次、45 分钟）升级成高频默认；他自己改过的不动', async () => {
  await withHome(async (home) => {
    setup(home, { dailyMax: 6, hourlyMax: 3, minGapMs: 45 * 60_000, diceThreshold: 60 })
    const res = await call(host({ [SIS]: agentOf(SIS) }).handler(), 'POST', '/dsh-go-companion/v1/config', {})
    assert.equal(res.body.config.dailyMax, 40)
    assert.equal(res.body.config.minGapMs, 18 * 60_000)
  })
  await withHome(async (home) => {
    setup(home, { dailyMax: 6, hourlyMax: 3, minGapMs: 30 * 60_000, diceThreshold: 60 })
    const res = await call(host({ [SIS]: agentOf(SIS) }).handler(), 'POST', '/dsh-go-companion/v1/config', {})
    assert.equal(res.body.config.dailyMax, 6)
  })
})

test('手机报 tamper：拒绝模式 60 分钟、力度 4，下一次 /plan 也照锁', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const res = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'tamper', what: '强行停止', app: 'com.android.settings' })
    assert.equal(res.body.ok, true)
    assert.ok(res.body.refuse.until > Date.now() + 59 * 60_000, JSON.stringify(res.body))
    assert.equal(res.body.force.level, 4)
    const p = await t.plan(game)
    assert.ok(p.body.refuse?.until > Date.now() + 55 * 60_000, 'tamper 之后拒绝模式还在')
  })
})

test('手机报 breakglass：放风 10 分钟，期间娱乐不再锁', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'tamper', what: '卸载', app: 'com.android.settings' })
    const res = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', {
      type: 'breakglass', minutes: 10, steps: 152, note: '走够步数了，这十分钟回个消息',
    })
    assert.equal(res.body.ok, true)
    assert.ok(res.body.breakUntil > Date.now() + 9 * 60_000, JSON.stringify(res.body))
    const p = await t.plan(game)
    assert.equal(p.body.force.level, 0, '放风期间不锁')
    assert.equal(p.body.refuse, null, '放风盖过拒绝模式')
  })
})

test('破窗每天最多 1 次：第二次被拒，第二天恢复', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const body = { type: 'breakglass', minutes: 10, steps: 160, note: '第一次' }
    assert.equal((await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', body)).body.ok, true)
    const again = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { ...body, note: '第二次' })
    assert.equal(again.body.ok, false)
    assert.equal(again.body.error.code, 'breakglass-used')
  })
  await withHome(async (home) => {
    const t = setup(home)
    // 账本里只有前天的破窗：今天还没用过。
    writeFileSync(
      join(home, 'companion', 'ledger.jsonl'),
      JSON.stringify({ v: 1, state: 'breakglass', at: Date.now() - 2 * 86_400_000, minutes: 10 }) + '\n',
    )
    const res = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'breakglass', minutes: 10, steps: 160, note: '今天第一次' })
    assert.equal(res.body.ok, true)
  })
})

test('破窗扣分：默认 15、可按配置改，账本记下这次扣了多少', async () => {
  const now = Date.now()
  const rows = [{ state: 'breakglass', at: now - 1_000 }]
  assert.equal(trustScore(rows, now).score, 45, '默认扣 15')
  assert.equal(trustScore(rows, now, { weights: { breakglass: -25 } }).score, 35, '可覆盖')
  await withHome(async (home) => {
    const t = setup(home)
    const res = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'breakglass', minutes: 10, steps: 160, note: '配置' })
    assert.equal(res.body.penalty, 15)
    const row = JSON.parse(readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').pop())
    assert.equal(row.state, 'breakglass')
    assert.equal(row.penalty, 15)
    const cfg = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/config', { breakglassPenalty: 25 })
    assert.equal(cfg.body.config.breakglassPenalty, 25)
    // 配置落盘；信用分按落盘的数值覆盖（statusBody 不带 trust，直接验 trustScore 的入参）。
    const saved = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.equal(saved.breakglassPenalty, 25)
    assert.equal(trustScore(rows, Date.now(), { weights: { breakglass: -saved.breakglassPenalty } }).score, 35)
  })
})

test('Gemini 互动舱：/plan 带 session 白名单，两个人格都能开、都能收工', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    assert.ok(t.sis.tools.gemini_live && t.sis.tools.gemini_end, '妹妹两个工具都有')
    assert.ok(t.big.tools.gemini_live && t.big.tools.gemini_end, '姐姐两个工具都有')
    const started = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/session', {
      action: 'start', topic: '线代第三章', minutes: 30, mode: 'study',
    })
    assert.equal(started.body.ok, true, JSON.stringify(started.body))
    assert.equal(started.body.session?.topic, '线代第三章')
    assert.ok(started.body.session.allow.includes('com.google.android.apps.bard'))
    assert.ok(started.body.session.allow.includes('com.google.android.googlequicksearchbox'))
    const inside = await t.plan(gemini(2))
    assert.equal(inside.body.session?.topic, '线代第三章')
    assert.equal(inside.body.session.mode, 'study')
    assert.equal(inside.body.reason, 'in-gemini')
    const ended = t.big.tools.gemini_end.execute({ reason: '讲完了' })
    assert.equal(ended.ok, true, ended.text)
    const after = await t.plan(wechat)
    assert.equal(after.body.session, null)
    assert.equal(t.sis.tools.gemini_end.execute({}).ok, false, '没开的时候收工要拒绝')
  })
})
