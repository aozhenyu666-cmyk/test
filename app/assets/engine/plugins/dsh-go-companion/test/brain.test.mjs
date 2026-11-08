// 大脑：水位、脾气（阴晴难测但有底色）、Thompson 挑招、拉回来没有、弱项多练、改判断、力度说到做到。
//
// 随机全部可注入；路由测试里的随机只断言"落在允许的范围里"。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'
import { cleanPhone } from '../lib/phone.js'
import { tide, tideGap, vagueStreak } from '../lib/tide.js'
import { stepTemper, faceOf, FACES } from '../lib/temper.js'
import { DRILLS, drillLines, pickDrill, scoldLines } from '../lib/drills.js'
import { emptyBrain, chooseFace, drillScorer, observe, onReply, onServe, revise, brainLines, betaSample } from '../lib/brain.js'
import { decideForce, forceLines } from '../lib/force.js'
import { drillToggle } from '../lib/index.js'
import { judgeUser } from '../lib/judge.js'

function makeHost({ agent }) {
  const listeners = new Map()
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(name, fn) {
      if (!listeners.has(name)) listeners.set(name, [])
      listeners.get(name).push(fn)
    },
    inject(_deps, cb) {
      cb({ webServer: { register(route) { handler = route.handler } }, effect(fn) { fn() } })
    },
    effect(fn) {
      fn()
    },
  }
  apply(ctx)
  return { handler, listeners }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0,
    text: '',
    writeHead(status) {
      this.statusCode = status
    },
    end(chunk) {
      this.text = chunk ? String(chunk) : ''
    },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

function seed(home, config = {}) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(
    join(cfgDir, 'config.json'),
    JSON.stringify({ sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0, ...config }),
  )
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.prepare('INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?,?)')
    .run('t1', 'session:session-abc', '面试结果', '她说周五出结果', 'open', 2, Date.now() - 86400000, 0)
  db.close()
}

/** 只写一条"已经发出去但没人回"的账本记录，时间放在过去。 */
function seedSilentServe(home, { requestId, agoMs, serveType }) {
  writeFileSync(
    join(home, 'companion', 'ledger.jsonl'),
    JSON.stringify({
      v: 1,
      requestId,
      sessionId: 'session-abc',
      state: 'generated',
      at: Date.now() - agoMs,
      serveType,
      text: '我猜你在躲申论',
      rallyId: 'rally-old',
      shot: 1,
    }) + '\n',
  )
}

function makeAgent() {
  const sent = []
  return {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    sent,
    followup(msg) {
      sent.push(msg)
    },
  }
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-p2-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
}

function ledgerOf(home) {
  return readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l))
}


/** 固定序列的随机数，测试可重复。 */
function seq(...xs) {
  let i = 0
  return () => xs[i++ % xs.length]
}

const T = 1_800_000_000_000
const deep = (minutes, extra = {}) =>
  cleanPhone({ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes, fun: true, screenOn: true, local: '21:10',
    today: [{ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes, fun: true }], ...extra })

// ---------------------------------------------------------------- 水位

test('水位：越陷越深；不在娱乐 App 里水就退', () => {
  assert.equal(tide({}).band, 'clear')
  assert.equal(tide({ phone: deep(5) }).band, 'clear')
  assert.equal(tide({ phone: deep(25) }).band, 'slide')
  assert.equal(tide({ phone: deep(45) }).band, 'sink')
  assert.equal(tide({ phone: deep(45), noTake: 2, vague: 2 }).band, 'trap')
  const off = tide({ phone: cleanPhone({ app: 'com.tencent.mm', minutes: 90, today: [{ app: 'com.tencent.tmgp.sgame', minutes: 300, fun: true }] }) })
  assert.ok(off.level <= 40, '在微信里，水位封顶：' + off.level)
})

test('连着几次答得糊弄：从新往旧数，认真答一次就断', () => {
  const e = [{ state: 'replied', verdict: 'brief' }, { state: 'replied', verdict: 'engaged' }, { state: 'replied', verdict: 'stock' }, { state: 'replied', verdict: 'evasive' }]
  assert.equal(vagueStreak(e), 2)
})

test('节奏：陷住时间隔缩到零头，而且带随机，摸不出规律', () => {
  const clear = tideGap(45 * 60_000, 'clear', 0.5)
  const trap = tideGap(45 * 60_000, 'trap', 0.5)
  assert.equal(clear, 45 * 60_000)
  assert.ok(trap < 6 * 60_000, '陷住了 ' + trap)
  assert.notEqual(tideGap(45 * 60_000, 'sink', 0), tideGap(45 * 60_000, 'sink', 1))
})

// ---------------------------------------------------------------- 脾气

test('脾气：温和底色软、严格底色硬，水位往硬里推', () => {
  const calm = () => 0.5 // 噪声 ≈ 0 的那一档
  const noSurprise = seq(0.5, 0.25, 0.99)
  const warm = stepTemper(null, { mode: 'partner', tide: 0, now: T, rng: noSurprise })
  const strict = stepTemper(null, { mode: 'coach', tide: 0, now: T, rng: seq(0.5, 0.25, 0.99) })
  const warmDeep = stepTemper(null, { mode: 'partner', tide: 90, now: T, rng: seq(0.5, 0.25, 0.99) })
  assert.ok(warm.edge < strict.edge)
  assert.ok(warmDeep.edge > warm.edge + 0.3, '陷住了温和也会变硬')
  assert.equal(typeof calm, 'function')
})

test('脾气：同样的情况，换一串随机数就是另一张脸（阴晴难测）', () => {
  const faces = new Set()
  for (let i = 0; i < 40; i += 1) {
    const t = stepTemper({ edge: 0.5, at: T - 30 * 60_000 }, { mode: 'partner', tide: 50, now: T })
    faces.add(t.face)
  }
  assert.ok(faces.size >= 3, '40 次只出现了 ' + [...faces].join('/'))
})

test('脾气：翻脸只翻这一球，底色不跟着跳', () => {
  // 第三个随机数 < 0.13 → 翻脸
  const t = stepTemper({ edge: 0.85, at: T }, { mode: 'coach', tide: 80, now: T, rng: seq(0.5, 0.25, 0.01) })
  assert.equal(t.surprise, true)
  assert.ok(['coax', 'tease'].includes(t.face), '严格时突然软下来：' + t.face)
  assert.ok(t.edge > 0.7, '存下来的底色还是硬的：' + t.edge)
  assert.equal(faceOf(0.9).face, 'scold')
})

// ---------------------------------------------------------------- 大脑

test('拉回来了：发球后 10 分钟内离开了那个娱乐 App，这张脸记一分', () => {
  const b = emptyBrain()
  onServe(b, { requestId: 'r1', phone: deep(30), band: 'slide', face: 'jab', at: T })
  assert.equal(observe(b, { phone: deep(31), now: T + 10_000 }), null, '30 秒内不判')
  const r = observe(b, { phone: cleanPhone({ app: 'com.tencent.mm', minutes: 1 }), now: T + 4 * 60_000 })
  assert.deepEqual(r, { pulled: true, face: 'jab', band: 'slide' })
  assert.equal(b.faces['slide:jab'].win, 1)
})

test('没拉回来：10 分钟后还在里面，这张脸记零分', () => {
  const b = emptyBrain()
  onServe(b, { requestId: 'r1', phone: deep(30), band: 'sink', face: 'coax', at: T })
  const r = observe(b, { phone: deep(42), now: T + 11 * 60_000 })
  assert.equal(r.pulled, false)
  assert.equal(b.faces['sink:coax'].lose, 1)
})

test('Thompson 挑招：哪张脸真管用，它就越来越常挑那张', () => {
  const b = emptyBrain()
  b.faces['sink:jab'] = { win: 9, lose: 1 }
  b.faces['sink:cool'] = { win: 0, lose: 10 }
  b.faces['sink:scold'] = { win: 1, lose: 9 }
  let jab = 0
  for (let i = 0; i < 200; i += 1) if (chooseFace(b, { band: 'sink', face: 'jab' }) === 'jab') jab += 1
  assert.ok(jab > 150, 'jab 只被挑了 ' + jab)
  assert.equal(chooseFace(b, { band: 'sink', face: 'coax', surprise: true }), 'coax', '翻脸那一球不改')
})

test('弱项多练：答不上来的方法抽样分更高', () => {
  const b = emptyBrain()
  b.drills.bet = { win: 0, lose: 8 }
  b.drills.thesis = { win: 8, lose: 0 }
  // 9 种题均匀挑的话，每种约 1/9（300 次约 33 次）；弱项要明显多，强项要明显少。
  let bet = 0
  let thesis = 0
  for (let i = 0; i < 300; i += 1) {
    const id = pickDrill([], drillScorer(b)).id
    if (id === 'bet') bet += 1
    if (id === 'thesis') thesis += 1
  }
  assert.ok(bet > 90, '下注只被挑了 ' + bet)
  assert.ok(thesis < 10, '答得好的"一句话论点"被挑了 ' + thesis)
  const recent = [{ state: 'generated', drill: 'bet' }]
  assert.notEqual(pickDrill(recent, drillScorer(b)).id, 'bet', '刚出过的不连着出')
})

test('思维题按合格线记分：糊弄算没过，认真算过', () => {
  const b = emptyBrain()
  onServe(b, { requestId: 'q1', phone: null, band: 'clear', face: 'cool', drill: 'number', at: T })
  onReply(b, { requestId: 'q1', verdict: 'brief' })
  onServe(b, { requestId: 'q2', phone: null, band: 'clear', face: 'cool', drill: 'number', at: T })
  onReply(b, { requestId: 'q2', verdict: 'engaged' })
  assert.deepEqual(b.drills.number, { win: 1, lose: 1 })
})

test('判断随证据改：翻了就记下来，下一球可以承认自己看错了', () => {
  const b = emptyBrain()
  b.faces['sink:coax'] = { win: 4, lose: 1 }
  b.faces['sink:scold'] = { win: 1, lose: 4 }
  revise(b, T)
  assert.match(b.beliefs['best:sink'].text, /"哄"最能把他拉回来/)
  b.faces['sink:scold'] = { win: 12, lose: 2 }
  b.faces['sink:coax'] = { win: 4, lose: 9 }
  revise(b, T + 1)
  assert.match(b.beliefs['best:sink'].text, /"训"/)
  assert.match(b.revision.from, /哄/)
  assert.match(brainLines(b).join('\n'), /之前以为.*哄.*现在看是.*训/)
})

test('证据不够不下判断：宁可不说，不假装了解他', () => {
  const b = emptyBrain()
  b.faces['slide:jab'] = { win: 2, lose: 0 }
  revise(b, T)
  assert.deepEqual(b.beliefs, {})
  assert.ok(betaSample(1, 1) >= 0 && betaSample(1, 1) <= 1)
})

// ---------------------------------------------------------------- 思维题与说他

test('思维题：方法、合格线进 prompt；答糊弄 / 不回都要被说', () => {
  const d = DRILLS.find((x) => x.id === 'counter')
  assert.match(drillLines(d).join('\n'), /合格的回答必须是：一个具体的反例/)
  assert.match(scoldLines(d, { verdict: 'brief', answer: '也不一定吧' }).join('\n'), /也不一定吧.*没过线/s)
  assert.match(scoldLines(d, { silent: true }).join('\n'), /沉默不是答案/)
  assert.equal(scoldLines(d, { verdict: 'engaged' }).length, 0, '答过线就不说他')
  assert.match(judgeUser({ serve: 'x', reply: 'y', criteria: d.pass }), /合格的回答必须是/)
})

// ---------------------------------------------------------------- 力度

test('力度：不掷骰子；严格比温和先锁，温和也会锁', () => {
  const f = (minutes, mode, extra = {}) => decideForce({ phone: deep(minutes), tide: tide({ phone: deep(minutes) }), mode, ...extra })
  assert.equal(f(5, 'coach').name, 'talk')
  assert.equal(f(35, 'coach').name, 'lock')
  assert.equal(f(35, 'partner').name, 'screen')
  assert.equal(f(65, 'partner').name, 'lock')
  assert.equal(f(70, 'coach').name, 'home')
  assert.equal(f(130, 'partner').name, 'home', '打一天游戏，温和也会把他赶出去')
  assert.equal(f(130, 'partner', { control: false }).name, 'screen', '没给控机权限只能到浮层')
  assert.equal(decideForce({ phone: cleanPhone({ app: 'com.tencent.mm', minutes: 200 }), mode: 'coach' }).name, 'talk')
})

// ---------------------------------------------------------------- 路由

test('/plan：打了 70 分钟游戏，严格模式回 force=home，一分钟内再看', async () => {
  await withHome(async (home) => {
    seed(home, { mode: 'coach' })
    const host = makeHost({ agent: makeAgent() })
    const out = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai', phone: deep(70) })
    assert.equal(out.body.force.name, 'home', JSON.stringify(out.body))
    assert.equal(out.body.force.app, 'com.tencent.tmgp.sgame')
    assert.ok(['sink', 'trap'].includes(out.body.tide.band))
    assert.ok(out.body.nextCheckAt <= Date.now() + 61_000)
  })
})

test('/plan：一早还没开过口、屏幕亮着，不等骰子直接开球', async () => {
  await withHome(async (home) => {
    seed(home, { diceThreshold: 0 })
    const host = makeHost({ agent: makeAgent() })
    const phone = cleanPhone({ app: 'com.android.launcher', minutes: 0, screenOn: true, local: '08:05' })
    const out = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai', phone, roll: 99 })
    assert.equal(out.body.speak, true, JSON.stringify(out.body))
    // 5–10 点的第一次亮屏现在由"早上开门"接手：问今天最重要的 3 件事。
    assert.equal(out.body.reason, 'morning-gate')
  })
})

test('/request（做题模式开着）：上一道题答得糊弄，这一球先说他、同一类题再问一次；账本记下脸色和水位', async () => {
  await withHome(async (home) => {
    seed(home, { drills: true })
    const now = Date.now()
    writeFileSync(
      join(home, 'companion', 'ledger.jsonl'),
      [
        { v: 1, requestId: 'q-old', sessionId: 'session-abc', state: 'generated', at: now - 3 * 60_000, serveType: 'drill', drill: 'number', text: '你打了多久？给个数', rallyId: 'rally-q', shot: 1 },
        { v: 1, requestId: 'q-old', sessionId: 'session-abc', state: 'replied', at: now - 2 * 60_000, verdict: 'brief', answer: '挺久的吧', rallyId: 'rally-q', shot: 2 },
      ].map((e) => JSON.stringify(e)).join('\n') + '\n',
    )
    const agent = makeAgent()
    const host = makeHost({ agent })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1, requestId: 'req-scold-1', eventId: 'evt-scold-1', kind: 'random_chat', payload: {}, phone: deep(40), deadlineMs: 30000,
    })
    assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))
    const prompt = agent.sent[0].content[0].text
    assert.match(prompt, /挺久的吧.*没过线/s)
    assert.match(prompt, /给个数字/)
    assert.match(prompt, /这一球的脸色：/)
    const accepted = ledgerOf(home).find((e) => e.requestId === 'req-scold-1' && e.state === 'accepted')
    assert.equal(accepted.serveType, 'drill')
    assert.equal(accepted.drill, 'number')
    assert.ok(Number.isFinite(accepted.tide) && accepted.band && accepted.face, JSON.stringify(accepted))
  })
})

// ---------------------------------------------------------------- 真聊天

test('做题默认关：在游戏里也不出题，锁之前问他为什么，而不是丢题', async () => {
  await withHome(async (home) => {
    seed(home, { mode: 'coach' })
    const agent = makeAgent()
    const host = makeHost({ agent })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1, requestId: 'req-chat-1', eventId: 'evt-chat-1', kind: 'random_chat', payload: {}, phone: deep(40), deadlineMs: 30000,
    })
    assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))
    const prompt = agent.sent[0].content[0].text
    assert.doesNotMatch(prompt, /思维题/)
    assert.match(prompt, /为什么现在还在这里/)
    assert.match(prompt, /讲得通就放他一马|先把理由弄懂/, '回话那一轮要先听懂他（妹妹的说法：讲得通就放他一马）')
    const accepted = ledgerOf(home).find((e) => e.requestId === 'req-chat-1' && e.state === 'accepted')
    assert.notEqual(accepted.serveType, 'drill')
  })
})

test('做题开关：一句话开、一句话关；普通聊天不误判', () => {
  assert.equal(drillToggle('开始做题'), true)
  assert.equal(drillToggle('做题模式'), true)
  assert.equal(drillToggle('别出题了'), false)
  assert.equal(drillToggle('关掉做题'), false)
  assert.equal(drillToggle('我今天做题做到很晚，脑子都木了'), null)
  assert.equal(drillToggle('在打游戏'), null)
})

test('讲通了理由（真反驳）：先松 20 分钟，不锁', () => {
  const f = decideForce({ phone: deep(90), tide: tide({ phone: deep(90) }), mode: 'coach', grace: true })
  assert.equal(f.name, 'press')
  assert.match(forceLines({ level: 3 }, deep(40)).join(''), /为什么现在还在这里/)
  assert.match(forceLines({ level: 3 }, deep(40), { drill: true }).join(''), /丢一道题/)
})

/** 带 followup 回调的假会话：能模拟"大肥鱼回了一句"。 */
function makeTalkingAgent() {
  const sent = []
  const agent = {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    sent,
    followup(msg) {
      sent.push(msg)
    },
  }
  return agent
}

test('/say：他在浮层里打的字原样进会话（来源标成他本人），大肥鱼的回复从 /result 拿回来', async () => {
  await withHome(async (home) => {
    seed(home)
    const agent = makeTalkingAgent()
    const host = makeHost({ agent })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/say', { v: 1, requestId: 'say-00000001', text: '我打这局是因为作业写完了' })
    assert.equal(res.status, 202, JSON.stringify(res.body))
    assert.equal(agent.sent.length, 1)
    assert.equal(agent.sent[0].content[0].text, '我打这局是因为作业写完了', '原话，不加任何指令')
    assert.equal(agent.sent[0].source?.kind, 'user', '来源标成他本人：引擎要求每条消息都有 source')

    const emit = async (type, data) => {
      for (const fn of host.listeners.get('session/event') || []) await fn({ id: 'session-abc' }, { type, data })
    }
    await emit('turn/start', { turn: 7 })
    await emit('user/message', { id: agent.sent[0].id, content: agent.sent[0].content, turn: 7 })
    await emit('assistant/message', { turn: 7, message: { content: [{ type: 'text', text: '作业写完了那行，这局打完就收。' }] } })
    await emit('turn/end', { turn: 7, reason: { kind: 'completed' } })

    const out = await call(host.handler, 'GET', '/dsh-go-companion/v1/result?requestId=say-00000001&waitMs=0')
    assert.equal(out.body?.state, 'generated', JSON.stringify(out.body))
    assert.match(out.body.text, /作业写完了那行/)
    const states = ledgerOf(home).map((e) => e.state)
    assert.ok(states.includes('said') && states.includes('heard'), states.join(','))
    assert.ok(!states.includes('generated'), '聊天不算主动发球，不占额度、不进阶梯')
  })
})

test('/say：空话不收', async () => {
  await withHome(async (home) => {
    seed(home)
    const host = makeHost({ agent: makeTalkingAgent() })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/say', { v: 1, text: '   ' })
    assert.equal(res.status, 400)
  })
})
