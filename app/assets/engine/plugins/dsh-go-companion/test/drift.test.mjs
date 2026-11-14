// R4-6 走神强拉：忙时他在娱乐 App 里坐着 → 直接开一场审讯式 Gemini Live。
// 边界照文档：只在白天、忙时；深夜 / 吃饭 / 放风 / 一天两次之外都不拉。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { driftPull } from '../lib/gemini.js'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-drift-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

const SIS = 'session-sis'
function setup(home, extra = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, quietStart: 12, quietEnd: 12, ...extra }))
  const sis = agentOf(SIS)
  const h = host({ [SIS]: sis })
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const due = () => call(h.handler(), 'POST', '/dsh-go-companion/v1/gemini/due', {})
  return { sis, h, plan, due }
}

/** 忙时到 30 分钟后、今天已经忙了 10 分钟。 */
const busy = (extra = {}) => {
  const now = Date.now()
  return { gear: 'busy', gearUntil: now + 30 * 60_000, gearSince: now - 10 * 60_000, gearWhat: '写作业', ...extra }
}
/** 正在抖音刷了 12 分钟（15:00，不是吃饭时段）。 */
const tiktok = (minutes = 12) => ({ app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes, screenOn: true, local: '15:00' })
const wechat = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }
/** 手机是等闹钟响（`at` 之后）才去取 launch 的，测试照这个来。 */
const settle = () => new Promise((r) => setTimeout(r, 600))

test('driftPull：只认忙时 + 娱乐 App + 坐够分钟数；深夜、吃饭、放风、连麦中、额度用完都不拉', () => {
  const now = 10_000_000
  const day = now - 3_600_000
  const base = { phone: tiktok(), now, day, gear: 'busy', busyWhat: '写作业' }
  const r = driftPull(base)
  assert.equal(r.mode, 'interrogate', '用现成的审讯模式')
  assert.equal(r.dwell, 12)
  assert.match(r.topic, /为什么刷抖音 12 分钟/)
  assert.match(r.topic, /再回去接着写作业/)
  assert.equal(r.minutes, 15)

  assert.equal(driftPull({ ...base, gear: 'free' }), null, '闲时不拉：走神只在他自己说在做正事时算')
  assert.equal(driftPull({ ...base, phone: wechat }), null, '微信不算娱乐')
  assert.equal(driftPull({ ...base, phone: tiktok(4) }), null, '坐 4 分钟还没到阈值')
  assert.equal(driftPull({ ...base, dwellMin: 3 }).dwell, 12, '阈值可配')
  assert.equal(driftPull({ ...base, phone: tiktok(3), dwellMin: 3 }), null, '阈值最低 5 分钟')
  assert.equal(driftPull({ ...base, night: 'late' }), null, '深夜不拉')
  assert.equal(driftPull({ ...base, meal: { name: '晚饭' } }), null, '吃饭不拉')
  assert.equal(driftPull({ ...base, onBreak: true }), null, '放风不拉')
  assert.equal(driftPull({ ...base, session: { topic: '线代', until: now + 60_000 } }), null, '已经开着连麦不拉')
  // 一天两次：前两次在今天，第三次不拉；昨天的不算。
  const two = [{ at: day + 1_000 }, { at: now - 1_000 }]
  assert.equal(driftPull({ ...base, pulls: two }), null, '今天已经拉够两次')
  assert.ok(driftPull({ ...base, pulls: [{ at: day - 5_000 }] }), '昨天的次数不算今天的')
})

test('忙时刷抖音 12 分钟：/plan 约一场连麦，到点取到的简报是审讯', async () => {
  await withHome(async (home) => {
    const t = setup(home, busy())
    const p = await t.plan(tiktok())
    assert.equal(p.body.reason, 'gemini-drift', JSON.stringify(p.body))
    assert.equal(p.body.speak, false, '拉起连麦，这一轮不另外说话')
    const at = p.body.geminiSchedule?.at
    assert.ok(at > Date.now(), '排在一瞬间之后，手机据此 arm 闹钟')
    assert.match(p.body.geminiSchedule.topic, /为什么刷抖音/)
    assert.equal(p.body.session, null, '舱要等手机来取 launch 才开')
    // 手机到点来取：背景简报、主题、模式都对，并且这一场已经开好了。
    await settle()
    const d = await t.due()
    assert.match(d.body.launch.brief, /审讯/)
    assert.match(d.body.launch.brief, /为什么刷抖音 12 分钟/)
    assert.equal(d.body.launch.mode, 'interrogate')
    const p2 = await t.plan(tiktok())
    assert.ok(p2.body.session, '取过 launch 就连麦舱开着：只许 Gemini')
    assert.notEqual(p2.body.reason, 'gemini-drift', '同一段不重复拉')
    const rows = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
    assert.ok(rows.some((r) => r.state === 'gemini-drift' && r.app === 'com.ss.android.ugc.aweme' && r.minutes === 12))
    assert.ok(rows.some((r) => r.state === 'gemini-schedule-fired' && r.mode === 'interrogate'))
  })
})

test('不该拉的都不拉：闲时、没坐够、深夜、吃饭、放风、管控关掉', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    // 闲时刷抖音：这条只在他自己说在做正事时管。
    assert.equal((await t.plan(tiktok(20))).body.geminiSchedule, null)
  })
  await withHome(async (home) => {
    const t = setup(home, busy())
    assert.equal((await t.plan(tiktok(4))).body.geminiSchedule, null, '坐 4 分钟')
  })
  await withHome(async (home) => {
    const t = setup(home, busy({ gearUntil: Date.now() + 30 * 60_000 }))
    // 23:40：深夜阶段（只催不拉）。
    assert.equal((await t.plan({ ...tiktok(20), local: '23:40' })).body.geminiSchedule, null, '深夜不拉')
    // 18:30 晚饭时段。
    assert.equal((await t.plan({ ...tiktok(20), local: '18:30' })).body.geminiSchedule, null, '吃饭不拉')
  })
  await withHome(async (home) => {
    const t = setup(home, busy({ breakUntil: Date.now() + 10 * 60_000 }))
    assert.equal((await t.plan(tiktok())).body.geminiSchedule, null, '放风中不拉')
  })
  await withHome(async (home) => {
    const t = setup(home, busy({ control: false }))
    assert.equal((await t.plan(tiktok())).body.geminiSchedule, null, '他自己关掉管控就不强拉')
  })
})

test('一天两次：第二次还拉，第三次不拉；连麦结束后还能再拉一次', async () => {
  await withHome(async (home) => {
    const t = setup(home, busy())
    assert.equal((await t.plan(tiktok())).body.reason, 'gemini-drift')
    await settle()
    await t.due()
    // 这一场 15 分钟，结束后他接着刷：第二次拉。
    const g = JSON.parse(readFileSync(join(home, 'companion', 'gemini.json'), 'utf8'))
    writeFileSync(join(home, 'companion', 'gemini.json'), JSON.stringify({ ...g, session: { ...g.session, until: Date.now() - 60_000 } }))
    assert.equal((await t.plan(tiktok(20))).body.reason, 'gemini-drift', '第二次')
    await settle()
    await t.due()
    const g2 = JSON.parse(readFileSync(join(home, 'companion', 'gemini.json'), 'utf8'))
    writeFileSync(join(home, 'companion', 'gemini.json'), JSON.stringify({ ...g2, session: { ...g2.session, until: Date.now() - 60_000 } }))
    const third = await t.plan(tiktok(30))
    assert.notEqual(third.body.reason, 'gemini-drift', '今天拉到两次为止，第三次不拉')
    assert.equal(third.body.geminiSchedule, null)
    assert.ok(existsSync(join(home, 'companion', 'gemini.json')))
  })
})
