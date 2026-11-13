// 档位（闲时 / 忙时）、聊天里直接管他（dafeiyu_control）、Gemini Live 搭桥（去：背景；回：读回复盘）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { existsSync, readFileSync } from 'node:fs'
import { gearSwitch } from '../lib/gear.js'
import { trustScore } from '../lib/trust.js'
import { trackGemini } from '../lib/gemini.js'
import { STATE_TABLE, MAX_TOOLS_PER_STATE, ALL_TOOLS } from '../lib/machine.js'

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

function host(agents, state = 'standby') {
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
  setStatePin(state)
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
  // R7 P0-3：`state` 钉主状态，不能摊进 config.json（那是真配置）。
  const { state = 'standby', ...rest } = extra
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, ...rest }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big }, state)
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

test('忙时：不闲聊、娱乐一开就锁、不到点不催进度（B1：每 25 分钟报到已撤）', async () => {
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
    // B1：忙时中途（已经忙了 30 分钟）不再发问进度的球——催人的话是套话的来源。
    // 进度只在 A2 的时间块边界由代码问一次；这里有块没块都不该冒出一球。
    const now = Date.now()
    const t = setup(home, { gear: 'busy', gearUntil: now + 30 * 60_000, gearSince: now - 30 * 60_000, gearWhat: '写报告' })
    const mid = await t.plan(wechat)
    assert.equal(mid.body.speak, false, JSON.stringify(mid.body))
    assert.notEqual(mid.body.reason, 'checkin', '不再有 checkin 这一球')
    assert.equal(t.big.sent.length + t.sis.sent.length, 0, '忙时中途一句都不发')
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

test('第四轮 R4-2 + R7 P0-3：两姐妹逐状态工具完全一致；那些能力没被砍，只是按主状态收起', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    // P0-3 起工具按主状态挂子集（每态 ≤8 个）。R4-2 的「两边权限一样」照旧成立，
    // 只是要逐状态核：任何状态下两姐妹挂出来的必须是同一套，且每个能力至少在一个状态下挂得出来
    // ——挂不出来的就等于被砍了，那是不许的。
    const want = ['grant_break', 'end_task_mode', 'submit_verdict', 'daily_plan', 'daily_review',
      'submit_to_wall', 'dafeiyu_control', 'start_task_mode', 'block_apps', 'tighten_today', 'request_relax',
      'notes_vault', 'vault_search', 'vault_recent_notes', 'gemini_live', 'gemini_end', 'gemini_log', 'schedule_gemini_live']
    const seen = new Set()
    for (const state of STATE_TABLE.map((row) => row.id)) {
      setStatePin(state)
      const sis = Object.keys(t.sis.tools).sort()
      assert.deepEqual(sis, Object.keys(t.big.tools).sort(), state + ' 下两姐妹的工具不一致')
      assert.ok(sis.length <= MAX_TOOLS_PER_STATE, state + ' 挂了 ' + sis.length + ' 个，超过 ' + MAX_TOOLS_PER_STATE)
      for (const name of sis) seen.add(name)
    }
    for (const name of want) assert.ok(ALL_TOOLS.includes(name), '权限被砍了：' + name)
    for (const name of want) assert.ok(seen.has(name), '任何状态下都没挂出 ' + name + '，等于砍了')
    // dafeiyu_control 还是只管收紧那一组：放松走 grant_break / request_relax，不在这个开关里。
    setStatePin('morning')
    assert.deepEqual(t.big.tools.dafeiyu_control.parameters.properties.action.enum.sort(), ['busy', 'mute', 'refuse', 'strict'])
  })
})

test('第四轮 R4-1：没挂着的东西交不了墙；墙的话以〔墙〕开头，审查进账本', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const tool = t.big.tools.submit_to_wall
    // 下午没有晨间门，也没有追问锁：交立项、交连麦回流都被打回，说得再好也没用。
    const morning = tool.execute({ kind: 'morning', tasks: [{ task: '改简历', evidence: '大肥鱼/简历.md' }] })
    assert.equal(morning.pass, false)
    assert.match(morning.text, /^〔墙〕/)
    assert.match(morning.text, /现在没有挂着的「晨间立项」/)
    const gemini = tool.execute({ kind: 'gemini', next_step: '我觉得我们应该先优化一下底层方法论' })
    assert.equal(gemini.pass, false)
    const rows = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
    assert.equal(rows.filter((r) => r.state === 'wall-audit' && r.pass === false).length, 2)
    assert.ok(!rows.some((r) => r.state === 'verdict'), '没过关就不落裁决')
  })
})

test('Gemini 去：背景写清他是谁、这次干嘛、怎么盯、怎么复盘；送过去的步骤；连麦中跑去玩会被锁', async () => {
  await withHome(async (home) => {
    const t = setup(home, { owner: '阿鱼', state: 'brain' })
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
    const t = setup(home, { notesPath: vault, state: 'brain' })
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
    const t = setup(home, { state: 'brain' })
    // 第三轮 C4：两人工具一致，连麦开舱两边都有。
    assert.ok(t.sis.tools.gemini_live && t.sis.tools.gemini_end, '妹妹也能开舱')
    assert.ok(t.sis.tools.gemini_log, '妹妹还能存复盘')
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
    assert.equal(t.big.tools.gemini_end.execute({}).ok, false, '没开的时候收工要拒绝')
  })
})

// ---------------------------------------------------------------- P5 必答项接线
//
// 规则在 autobout.js（missingAnswers），这里验的是接线：工具真的会挡住、真的点名缺哪条，
// 答了的真的进提示——不然问完就丢，等于没问。

test('P5 排局前先问必答项：缺项工具点名，不排', async () => {
  await withHome(async (home) => {
    const t = setup(home, { state: 'standby' })
    const tool = t.sis.tools.start_task_mode
    assert.ok(tool, '待命状态下要挂 start_task_mode')

    const r1 = tool.execute({ mode: 'task', reason: '他说接下来两小时刷题', goal: '做完 40 题' })
    assert.equal(r1.ok, false)
    assert.equal(r1.code, 'field-missing')
    assert.deepEqual(r1.missing, ['domain', 'breakAt'])
    assert.match(r1.text, /今天刷哪些领域/)
    assert.match(r1.text, /什么时候休息/)
    assert.doesNotMatch(r1.text, /这一段要交出什么/, '目标已经给了，不该再问一遍')
  })
})

test('P5 三项齐了就排上，领域和休息时段进提示', async () => {
  await withHome(async (home) => {
    const t = setup(home, { state: 'standby' })
    const r = t.sis.tools.start_task_mode.execute({
      mode: 'task', reason: '他说接下来两小时刷题', goal: '做完 40 题',
      domain: '线性代数第三章', breakAt: '做满 50 分钟歇 10 分钟',
    })
    assert.equal(r.ok, true, JSON.stringify(r))
    assert.match(r.text, /做完 40 题/)

    const config = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.equal(config.gear, 'busy')
    assert.equal(config.taskDomain, '线性代数第三章')
    assert.equal(config.taskBreakAt, '做满 50 分钟歇 10 分钟')
  })
})

test('P5 拒绝模式不用必答项：它不排局，只锁娱乐', async () => {
  await withHome(async (home) => {
    const t = setup(home, { state: 'standby' })
    const r = t.sis.tools.start_task_mode.execute({ mode: 'refuse', reason: '他说别让我玩', minutes: 30 })
    assert.equal(r.ok, true, JSON.stringify(r))
  })
})
