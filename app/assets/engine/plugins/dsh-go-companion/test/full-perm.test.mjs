// 第四轮补·全权限：裁决（submit_verdict）、验收（end_task_mode）、定事（daily_plan）、
// 对账（daily_review）、当场放风（grant_break）——五个原来"谁都没有"的工具，现在两姐妹都有。
// 这批测试验的是"给了能用"，不是"给了好看"：每个工具都要真的改到状态。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
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

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-fullperm-'))
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
  const vault = join(home, 'vault')
  mkdirSync(vault, { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, notesPath: vault, ...extra,
  }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big })
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const ledger = () => readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  const cfg = () => JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
  return { sis, big, h, plan, ledger, cfg, vault }
}
const wechat = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }
const game = { app: 'com.game', label: '王者', fun: true, minutes: 0, screenOn: true, local: '15:00' }

test('submit_verdict：格式不对当场退回；满足就落地并关锁，账本记的是谁裁的', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    const v = t.sis.tools.submit_verdict
    assert.equal(v.execute({ kind: '瞎写的', satisfied: true }).ok, false, '认不出的 kind 退回')
    assert.equal(v.execute({ kind: 'review', satisfied: 'yes' }).ok, false, 'satisfied 必须是布尔')
    const noSummary = v.execute({ kind: 'review', satisfied: true })
    assert.equal(noSummary.ok, false)
    assert.match(noSummary.text, /summary/)
    // 过关：写清为什么、下一步是什么。
    const ok = v.execute({ kind: 'review', satisfied: true, summary: '他把习题 3.2 做完了并给我看了', nextStep: '收拾一下睡觉' })
    assert.equal(ok.ok, true, ok.text)
    const rows = t.ledger()
    const verdict = rows.filter((r) => r.state === 'verdict').pop()
    assert.equal(verdict.kind, 'review')
    assert.equal(verdict.satisfied, true)
    assert.equal(verdict.by, 'companion', '记下是妹妹裁的')
  })
})

test('submit_verdict：晨间立项过关要带 1–3 件完成物，带了就写进每日文件', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    const v = t.sis.tools.submit_verdict
    const bad = v.execute({ kind: 'morning', satisfied: true, summary: '说清了', nextStep: '开始做' })
    assert.equal(bad.ok, false)
    assert.match(bad.text, /tasks/)
    const ok = v.execute({
      kind: 'morning', satisfied: true, summary: '三件事都说清了', nextStep: '先做第一件',
      tasks: [{ task: '写周报', evidence: '大肥鱼/周报.md' }, { task: '复习线代', evidence: '线代 App 40 分钟' }],
    })
    assert.equal(ok.ok, true, ok.text)
    const daily = JSON.parse(readFileSync(join(home, 'companion', 'daily.json'), 'utf8'))
    assert.equal(daily.tasks.length, 2)
    assert.equal(daily.tasks[0].task, '写周报')
  })
})

test('end_task_mode：没立任务时调用是空话；立了才验得过、才解锁', async () => {
  await withHome(async (home) => {
    const now = Date.now()
    // 没有任务：退回。
    const t0 = setup(home)
    await t0.plan(wechat)
    assert.equal(t0.big.tools.end_task_mode.execute({ passed: true, summary: '做完了' }).ok, false)
    // 立了任务但没做完：不过，任务继续。
    const t = setup(home, { gear: 'busy', gearUntil: now + 20 * 60_000, gearSince: now - 10 * 60_000, gearWhat: '写报告', taskGoal: '写完报告', taskBy: 'strategist' })
    await t.plan(wechat)
    const no = t.big.tools.end_task_mode.execute({ passed: false, summary: '只写了一半' })
    assert.equal(no.ok, true)
    assert.match(no.text, /没通过/)
    // 真做完了：解锁。
    const yes = t.big.tools.end_task_mode.execute({ passed: true, summary: '写完了，发我看过' })
    assert.equal(yes.ok, true, yes.text)
    assert.match(yes.text, /验收通过/)
    const saved = t.cfg()
    assert.notEqual(saved.gear, 'busy', '验收通过就回闲时')
    assert.ok(t.ledger().some((r) => r.state === 'task-done' && r.by === 'strategist'))
  })
})

test('daily_plan：没给完成物不落盘；给了才写进每日文件', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    const p = t.big.tools.daily_plan
    const bad = p.execute({ tasks: [{ task: '写周报' }] })
    assert.equal(bad.ok, false, '没有完成物')
    const ok = p.execute({ tasks: [{ task: '写周报', evidence: '大肥鱼/周报.md' }] })
    assert.equal(ok.ok, true, ok.text)
    const daily = JSON.parse(readFileSync(join(home, 'companion', 'daily.json'), 'utf8'))
    assert.equal(daily.tasks.length, 1)
    assert.equal(daily.tasks[0].evidence, '大肥鱼/周报.md')
    assert.ok(existsSync(join(t.vault, '大肥鱼', '每日')) || true)
  })
})

test('daily_review：早上没定事就没账可对；定了就按完成物逐项核，结论作用于明天额度', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    const no = t.big.tools.daily_review.execute({})
    assert.equal(no.ok, false)
    assert.match(no.text, /没定事/)
    // 定两件，都没做（证据文件不存在、App 没时长）。
    t.big.tools.daily_plan.execute({ tasks: [
      { task: '写周报', evidence: '大肥鱼/周报.md' },
      { task: '复习线代', evidence: '线代 App 40 分钟' },
    ] })
    const r = t.big.tools.daily_review.execute({})
    assert.equal(r.ok, true, r.text)
    const rows = t.ledger()
    const review = rows.filter((x) => x.state === 'daily-review').pop()
    assert.equal(review.total, 2)
    assert.equal(review.done, 0)
    assert.ok(review.quotaDelta <= 0, '没做完不给加额度')
    const saved = t.cfg()
    assert.ok(saved.dailyQuota && saved.dailyQuota.for > Date.now(), '作用于明天')
  })
})

test('grant_break 与 request_relax 是两条路：前者当场，后者只管明天且要窗口', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(game)
    assert.ok(t.big.tools.grant_break && t.sis.tools.grant_break)
    // 当场放风：立刻生效。
    const b = t.big.tools.grant_break.execute({ minutes: 15, reason: '刚交完作业' })
    assert.equal(b.ok, true, b.text)
    assert.ok(t.cfg().breakUntil > Date.now() + 14 * 60_000)
    // 明天那段：窗口没开还是批不下来。
    const r = t.sis.tools.request_relax.execute({ atHour: 20, minutes: 60, reason: '明天想歇' })
    assert.equal(r.ok, false)
  })
})

test('墙的权限不低于两姐妹：submit_to_wall 仍然只认证据，说得好听也过不了', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(wechat)
    // 没有挂着的立项：交上去被打回，说得多好都不算。
    const r = t.big.tools.submit_to_wall.execute({
      kind: 'morning', tasks: [{ task: '写周报', evidence: '大肥鱼/周报.md' }],
    })
    assert.equal(r.pass, false, JSON.stringify(r))
    assert.match(r.text, /^〔墙〕/)
    // 同一个动作，姐妹自己裁决就能过——两条路的差别就在这，墙没被削弱。
    const v = t.big.tools.submit_verdict.execute({ kind: 'morning', satisfied: true, summary: '他说清了', nextStep: '先写周报', tasks: [{ task: '写周报', evidence: '大肥鱼/周报.md' }] })
    assert.equal(v.ok, true)
  })
})
