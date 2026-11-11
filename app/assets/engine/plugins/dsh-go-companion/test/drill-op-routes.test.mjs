// C2 四个基本操作：/drill-op 路由。
//
// drills-ops.test.mjs 钉的是判定本身；这里钉的是**接线**：素材从哪来、答案进哪去、额度怎么算、
// 面板能不能读到进度。最容易错的三处：链路的「下一个」被记账顺序带歪、GET 顺手记了一笔、
// 精简拿不到素材却判了个「过」。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { appendLedger, readLedger } from '../lib/ledger.js'

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
    // 聊天路径要发 session/event 才走得到：记住监听器，测试里用 fire 发。
    on(name, fn) {
      if (!listeners.has(name)) listeners.set(name, [])
      listeners.get(name).push(fn)
    },
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  apply(ctx)
  return {
    handler: () => handler,
    fire: async (type, data) => {
      for (const fn of listeners.get('session/event') || []) await fn({ id: SIS }, { type, data })
    },
  }
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
  const home = mkdtempSync(join(tmpdir(), 'companion-op-'))
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

const SIS = 'session-sis'
const BIG = 'session-big'

function setup(home, extra = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, ...extra }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big })
  const submit = (body) => call(h.handler(), 'POST', '/dsh-go-companion/v1/drill-op', { timeZone: 'Asia/Shanghai', ...body })
  const read = (query = '') => call(h.handler(), 'GET', '/dsh-go-companion/v1/drill-op' + query)
  const mount = (body) => call(h.handler(), 'POST', '/dsh-go-companion/v1/mount', { timeZone: 'Asia/Shanghai', ...body })
  const sisters = () => call(h.handler(), 'POST', '/dsh-go-companion/v1/sisters', { timeZone: 'Asia/Shanghai' })
  return { sis, big, h, submit, read, mount, sisters }
}

/** 开一张卡，挂一条已知、一条结论：回想和提问都要拿它当素材。 */
async function withCard(t) {
  await t.mount({ action: 'open', goal: '把增量同步做出来' })
  await t.mount({ action: 'given', text: '服务端已经有版本号了' })
  await t.mount({ action: 'step', text: '所以是订阅没解绑', from: [0] })
}

test('K1 空账本：从第一步（回想）开始，额度满、进度全是 0', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.read()
    assert.equal(r.status, 200)
    assert.equal(r.body.ok, true)
    assert.equal(r.body.op.next, 'recall')
    assert.equal(r.body.op.meta.name, '回想')
    assert.deepEqual(r.body.op.order, ['recall', 'brief', 'predict', 'ask'])
    assert.equal(r.body.op.quota.used, 0)
    assert.equal(r.body.op.quota.left, 6)
    for (const op of ['recall', 'brief', 'predict', 'ask']) {
      assert.equal(r.body.op.stats[op].asked, 0)
      assert.equal(r.body.op.stats[op].rate, 0, '没练过不是满分')
    }
  })
})

test('K2 GET 只读：读十次也不记账（额度不能被看一眼就少一次）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    for (let i = 0; i < 10; i += 1) await t.read()
    assert.equal((await t.read()).body.op.quota.used, 0)
  })
})

test('K3 回想：没遮住卡就不算，而且如实说原因', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await withCard(t)
    const r = await t.submit({ op: 'recall', answer: '订阅没解绑' })
    assert.equal(r.body.judge.ok, false)
    assert.equal(r.body.judge.code, 'not-blind')
  })
})

test('K4 回想：遮住了、说得出卡上那条 → 过，下一个变成精简', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await withCard(t)
    const r = await t.submit({ op: 'recall', answer: '卡在订阅没解绑', blind: true })
    assert.equal(r.body.judge.ok, true, r.body.judge.text)
    assert.equal(r.body.op.next, 'brief')
    assert.equal(r.body.op.quota.used, 1)
  })
})

test('K5 链路不跳步：回想没过，下一个还是回想（不会跳到精简）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await withCard(t)
    await t.submit({ op: 'recall', answer: '随便说点什么', blind: true })
    assert.equal((await t.read()).body.op.next, 'recall')
  })
})

test('K6 不出 op 就按链路该出的那个判', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await withCard(t)
    const r = await t.submit({ answer: '卡在订阅没解绑', blind: true })
    assert.equal(r.body.judge.op, 'recall')
  })
})

test('K7 不认识的操作如实拒，不挑一个替它判', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.submit({ op: 'nope', answer: 'x' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'bad-op')
    // 没判过的题不记账。
    assert.equal(r.body.op.quota.used, 0)
  })
})

test('K8 一天合计 ≤ 6 题：用完就拒，并说清是用完了', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    for (let i = 0; i < 6; i += 1) await t.submit({ op: 'predict', answer: '不是断言' })
    assert.equal((await t.read()).body.op.quota.left, 0)
    const over = await t.submit({ op: 'predict', answer: '我猜 3 分钟' })
    assert.equal(over.body.ok, false)
    assert.equal(over.body.error.code, 'no-quota')
  })
})

test('K9 精简：他最近没有超过 100 字的话 → 明说没有素材，不判过', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把增量同步做出来' })
    appendLedger(home, { state: 'say-text', at: Date.now(), text: '太短了' })
    const r = await t.submit({ op: 'brief', answer: '增量同步先拉变化的部分' })
    assert.equal(r.body.judge.ok, false)
    assert.equal(r.body.judge.code, 'no-source')
  })
})

test('K10 精简：素材取自他最近那段长话，目标取自挂载卡', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把增量同步做出来' })
    appendLedger(home, {
      state: 'say-text', at: Date.now(),
      text: '这段时间我一直在琢磨增量同步这件事，服务端已经有版本号了，但是客户端每次还是全量拉一遍，' +
        '所以我怀疑是订阅没解绑，也可能是本地没有存lastVersion，反正每次打开都要等十几秒，' +
        '改了半天也没见好，抓包看请求头倒是有个 etag，可返回的还是整个列表。',
    })
    const ok = await t.submit({ op: 'brief', answer: '增量同步只拉变化的部分，先查订阅和本地版本号' })
    assert.equal(ok.body.judge.ok, true, ok.body.judge.text)
    const bad = await t.submit({ op: 'brief', answer: '今天天气不错适合出去走走看看' })
    assert.equal(bad.body.judge.code, 'no-goal-word')
  })
})

test('K11 提问：素材是卡上的已知 + 结论，咬住了才过', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await withCard(t)
    const ok = await t.submit({ op: 'ask', answer: '订阅没解绑的话，版本号该存到哪一层？' })
    assert.equal(ok.body.judge.ok, true, ok.body.judge.text)
    const bad = await t.submit({ op: 'ask', answer: '下一步做什么？' })
    assert.equal(bad.body.judge.code, 'no-card-word')
  })
})

test('K12 预判：不依赖挂载卡，只看有没有可核对的断言', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    assert.equal((await t.submit({ op: 'predict', answer: '我猜 3 分钟就够' })).body.judge.ok, true)
    assert.equal((await t.submit({ op: 'predict', answer: '感觉应该还行吧' })).body.judge.code, 'no-assertion')
  })
})

test('K13 面板能读到四个操作的近 14 天通过率', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await withCard(t)
    await t.submit({ op: 'recall', answer: '卡在订阅没解绑', blind: true })
    await t.submit({ op: 'predict', answer: '不是断言' })
    const s = await t.sisters()
    assert.equal(s.status, 200)
    const ops = s.body.drillOps
    assert.equal(ops.recall.asked, 1)
    assert.equal(ops.recall.passed, 1)
    assert.equal(ops.recall.rate, 1)
    assert.equal(ops.predict.asked, 1)
    assert.equal(ops.predict.passed, 0)
    assert.equal(ops.predict.rate, 0)
    assert.equal(ops.brief.asked, 0)
  })
})

test('K14 记账的形状：一条 drill-op 只带 op / ok / code，不存答案原文', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.submit({ op: 'predict', answer: '我猜 3 分钟，这句不该进账本' })
    const rows = readLedger(home, 50).filter((e) => e?.state === 'drill-op')
    assert.equal(rows.length, 1)
    assert.equal(rows[0].op, 'predict')
    assert.equal(rows[0].ok, true)
    assert.equal(rows[0].code, 'pass')
    assert.equal(rows[0].answer, undefined, '答案原文不落账本')
  })
})

/** 把 Math.random 定成 0：0 一定小于任何 drillChance，出题这一球必中。 */
async function withForcedDrill(fn) {
  const real = Math.random
  Math.random = () => 0
  try {
    return await fn()
  } finally {
    Math.random = real
  }
}

/** 走一遍真的发球：请求 → 认领 → 她开口 → 回合结束 → 他回球。 */
async function serveThenReply(t, answer) {
  const req = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/request', {
    v: 1, requestId: 'req-op-1', eventId: 'evt-op-1', kind: 'random_chat', payload: {}, deadlineMs: 30000,
  })
  assert.equal(req.body?.state, 'accepted', '请求应当被接受：' + JSON.stringify(req.body))
  const turn = 11
  await t.h.fire('turn/start', { turn })
  assert.equal(t.sis.sent.length, 1, '只该发一条：' + JSON.stringify(t.sis.sent))
  await t.h.fire('user/message', { id: t.sis.sent.at(-1).id })
  await t.h.fire('assistant/message', { turn, message: { content: [{ type: 'text', text: '不看东西，说出上次推到哪。' }] } })
  await t.h.fire('turn/end', { turn, reason: { kind: 'completed' } })
  // 她这一句得先真的生成出来，回球才有东西可回——不然这条用例测的是空气。
  const st = await call(t.h.handler(), 'GET', '/dsh-go-companion/v1/result?requestId=req-op-1&waitMs=0')
  assert.equal(st.body?.state, 'generated', '发球要真的生成出来：' + JSON.stringify(st.body))
  await t.h.fire('user/message', { content: [{ type: 'text', text: answer }] })
  return { served: req.body }
}

test('K15 聊天里回球：这一题由代码判，答得短也算过——不问模型', async () => {
  await withHome(async (home) => {
    const t = setup(home, { drills: true, mode: 'coach', modeUntil: Date.now() + 3_600_000 })
    await withCard(t)
    // 卡停放起来：他手里没有可看的东西，回想才算数。
    const parked = await t.mount({ action: 'park', where: '停在订阅没解绑这一段' })
    assert.equal(parked.body.ok, true, '停放要真的停下去：' + JSON.stringify(parked.body))
    const served = await withForcedDrill(() => serveThenReply(t, '停在订阅没解绑'))
    assert.equal(served.served.extra?.drillOp, 'recall', '链路第一步是回想：' + JSON.stringify(served.served))

    const rows = readLedger(home, 50)
    const op = rows.filter((e) => e?.state === 'drill-op').pop()
    assert.ok(op, '聊天里回的答案也要落一条 drill-op：' + JSON.stringify(rows.slice(-5)))
    assert.equal(op.op, 'recall')
    assert.equal(op.ok, true, '遮住卡、说得出结论 → 过：' + JSON.stringify(op))
    assert.equal(op.code, 'pass')
    const replied = rows.filter((e) => e?.state === 'replied').pop()
    assert.equal(replied.verdict, 'engaged', '过了就是接住了')
    assert.equal(replied.replyClass, 'substantive')
    const nxt = await t.read('?timeZone=Asia/Shanghai')
    assert.equal(nxt.body.op.next, 'brief', '过了这一步才轮到下一步')
  })
})

test('K16 卡还开着就不算回想：判不过，链路也不往前推', async () => {
  await withHome(async (home) => {
    const t = setup(home, { drills: true, mode: 'coach', modeUntil: Date.now() + 3_600_000 })
    await withCard(t)
    // 卡开着 = 他看得见，照着念谁都答得上。
    await withForcedDrill(() => serveThenReply(t, '停在订阅没解绑'))
    const rows = readLedger(home, 50)
    const op = rows.filter((e) => e?.state === 'drill-op').pop()
    assert.ok(op, '不遮住也要落一条 drill-op：' + JSON.stringify(rows.slice(-5)))
    assert.equal(op.ok, false)
    assert.equal(op.code, 'not-blind', '没遮住就是没遮住：' + JSON.stringify(op))
    const still = await t.read('?timeZone=Asia/Shanghai')
    assert.equal(still.body.op.next, 'recall', '没过就一直停在这一步')
  })
})

test('K17 第一次没过只说差在哪条线，不给示范', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.submit({ op: 'predict', answer: '感觉应该还行吧' })
    assert.equal(r.body.judge.ok, false)
    assert.equal(r.body.judge.code, 'no-assertion')
    assert.equal(r.body.judge.text.includes('示范'), false, '第一次不给示范：' + r.body.judge.text)
    assert.equal(r.body.judge.demo, undefined, '第一次不许带示范字段')
  })
})

test('K18 第二次没过才给一个示范', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const first = await t.submit({ op: 'predict', answer: '感觉应该还行吧' })
    assert.equal(first.body.judge.demo, undefined)
    const second = await t.submit({ op: 'predict', answer: '还是不太确定' })
    assert.equal(second.body.judge.ok, false)
    assert.ok(second.body.judge.demo, '第二次要给出示范：' + JSON.stringify(second.body.judge))
    assert.ok(second.body.judge.demo.includes('示范'), '示范要说得出来是示范')
    // 过了就不给示范——那是废话。
    const third = await t.submit({ op: 'predict', answer: '我猜 20 分钟能跑通' })
    assert.equal(third.body.judge.ok, true)
    assert.equal(third.body.judge.demo, undefined)
  })
})
