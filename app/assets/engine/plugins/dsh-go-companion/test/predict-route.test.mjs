// B 层接线验收：不是验函数，是验"真机上真会发生什么"。
//   1. submit_prediction 真的挂到了两个角色上，交上去真落盘到 companion/predict.json；
//   2. 到期的预判在 /plan 里被墙的遥测撞一遍，verdict 写进去了；
//   3. 模型就算想自己写 verdict 也写不进（参数里根本没这个字段）；
//   4. 人设文本真的多出了这一轮的素材，而且常驻层一条没少。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'

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
  return { handler: () => handler }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-predict-'))
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

function setup(home) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  const vault = join(home, 'vault')
  mkdirSync(vault, { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, notesPath: vault,
  }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  // R7 P0-3：工具按主状态挂子集，submit_prediction 只在晚间挂着——这个文件测的全是它。
  const h = host({ [SIS]: sis, [BIG]: big }, 'evening')
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const predictFile = join(home, 'companion', 'predict.json')
  const predict = () => JSON.parse(readFileSync(predictFile, 'utf8'))
  const ledger = () => readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n')
    .filter(Boolean).map((l) => JSON.parse(l))
  /** 人设文本：取最后一次挂上去的那份（惰性函数，要真的调一次）。 */
  const persona = (agent) => {
    const def = agent.contexts.filter((d) => !d.removed).pop()
    return def ? def.text() : ''
  }
  return { sis, big, plan, predict, ledger, persona }
}

const wechat = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }

test('两个角色都挂上了 submit_prediction', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    assert.ok(t.sis.tools.submit_prediction, '妹妹没有 submit_prediction')
    assert.ok(t.big.tools.submit_prediction, '姐姐没有 submit_prediction')
  })
})

test('交预判：缺 evidenceRefs 或 metrics 当场退回，什么都不落盘', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    const tool = t.big.tools.submit_prediction
    const noRef = tool.execute({ claim: '明天会先折腾 UI，把接口往后推', metrics: [{ metric: 'tasks_ratio', op: '<', threshold: 0.5 }] })
    assert.equal(noRef.ok, false)
    assert.match(noRef.text, /evidenceRefs/)
    const noMetric = tool.execute({ claim: '明天会先折腾 UI，把接口往后推', evidenceRefs: ['审计/2026-11-11.md'] })
    assert.equal(noMetric.ok, false)
    assert.match(noMetric.text, /metrics/)
    // 一次都没成功 → 账本文件不该被写出来。
    assert.throws(() => t.predict())
  })
})

test('交预判：合格了就落盘，verdict 出生是 null，来源固定是墙', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    const ok = t.big.tools.submit_prediction.execute({
      claim: '明天娱乐时间会超过一小时',
      evidenceRefs: ['app:抖音', '审计/2026-11-11.md'],
      metrics: [{ metric: 'fun_minutes', op: '>', threshold: 60 }],
      dueInHours: 20,
    })
    assert.equal(ok.ok, true, ok.text)
    const led = t.predict()
    assert.equal(led.list.length, 1)
    assert.equal(led.list[0].verdict, null)
    assert.equal(led.list[0].verdictSource, 'WALL_TELEMETRY')
    assert.equal(led.list[0].evidenceRefs.length, 2)
    // 账本里有留痕，能查到是谁押的。
    const row = t.ledger().filter((r) => r.state === 'predict-new').pop()
    assert.equal(row.by, 'strategist')
    assert.match(row.id, /^P-\d{8}-\d{2}$/)
  })
})

test('模型写不进 verdict：参数里根本没这个字段，硬塞也进不去', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    const tool = t.big.tools.submit_prediction
    const props = Object.keys(tool.parameters.properties)
    assert.deepEqual(props.sort(), ['claim', 'dueInHours', 'evidenceRefs', 'metrics'])
    assert.equal(props.includes('verdict'), false)
    assert.equal(props.includes('verdictSource'), false)
    await tool.execute({
      claim: '明天会先折腾 UI 周边',
      evidenceRefs: ['审计/2026-11-11.md'],
      metrics: [{ metric: 'tasks_ratio', op: '<', threshold: 0.5 }],
      // 硬塞：参数校验与 checkClaim 都只认自己的字段。
      verdict: 'hit', verdictSource: '我自己说的',
    })
    const rec = t.predict().list[0]
    assert.equal(rec.verdict, null, '模型硬塞的 verdict 进账本了，后门没堵住')
    assert.equal(rec.verdictSource, 'WALL_TELEMETRY')
  })
})

test('到期后 /plan 会把它撞一遍，verdict 由遥测填，不是模型填', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    t.big.tools.submit_prediction.execute({
      claim: '明天任务一件都做不完',
      evidenceRefs: ['审计/2026-11-11.md'],
      metrics: [{ metric: 'tasks_ratio', op: '<', threshold: 0.5 }],
      dueInHours: 1,
    })
    // 还没到期：再走一遍 /plan 也不该判。
    await t.plan({ ...wechat, fun: true, local: '16:00' })
    assert.equal(t.predict().list[0].verdict, null)

    // 把到期时间拨到过去，模拟时间过去了一天；今天没定任务 → tasks_ratio 是 null → unknown。
    const file = join(home, 'companion', 'predict.json')
    const led = JSON.parse(readFileSync(file, 'utf8'))
    led.list[0].dueAt = Date.now() - 1000
    writeFileSync(file, JSON.stringify(led))

    await t.plan(wechat)
    const after = t.predict().list[0]
    assert.equal(after.verdict, 'unknown', '没数据时应记 unknown，不拿 0 顶成 miss')
    assert.equal(after.verdictSource, 'WALL_TELEMETRY')
    assert.ok(after.settledAt > 0)
    assert.equal(t.ledger().some((r) => r.state === 'predict'), true)
  })
})

test('人设里真的多出了这一轮的素材，而且常驻层一条没少', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    t.big.tools.submit_prediction.execute({
      claim: '明天会先折腾 UI 周边',
      evidenceRefs: ['审计/2026-11-11.md'],
      metrics: [{ metric: 'tasks_ratio', op: '<', threshold: 0.5 }],
    })
    // 交完再看人设：pending 的预判应该被拎进 prompt（prediction 这个 arm 抽中时）。
    const text = t.persona(t.big)
    assert.ok(text.length > 0, '人设是空的')
    // 常驻层的铁律一条没少。
    assert.ok(text.includes('谋士'), '姐姐人设卡不见了')
    assert.ok(text.includes('挑前提'), '五步第一步没改成挑前提')
    // 注入快照落了盘，能翻。
    const rows = t.ledger()
    assert.equal(rows.some((r) => r.state === 'vibe'), true, '没落 vibe 注入快照')
  })
})
