// 第六轮 B：三个审查锚点。
//   1. 早 / 晚 / 周末到点没结论，墙得自己补一条"缺席"，否则静默失效查不出来；
//   2. 补记只记账，绝不下发锁（§3.3 不加锁和力度档）；
//   3. 周末那次按"审这一周"独立实现，不与晚间"审今天"合并；
//   4. 面板要能一眼看出哪一次漏了。
//
// 阈值的分段（11:00 / 12:30 / 23:40、周日 / 周一）用纯函数钉死；
// 集成层只断言恒成立的不变量，不依赖"跑测试时几点"。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { absentAnchors, closeGate, trackRhythm } from '../lib/rhythm.js'
import { weekdayOf } from '../lib/tz.js'

const TZ = 'Asia/Shanghai'
const SUN = Date.parse('2026-11-08T20:30:00+08:00')
const MON = Date.parse('2026-11-09T20:30:00+08:00')

test('weekdayOf：周日本地日历日算 0，周二算 2', () => {
  assert.equal(weekdayOf(SUN, TZ), 0)
  assert.equal(weekdayOf(Date.parse('2026-11-10T12:00:00+08:00'), TZ), 2)
})

test('到点判定：11:00 什么都不补，12:30 只补早上', () => {
  assert.deepEqual(absentAnchors(11 * 60, 2, []), [])
  assert.deepEqual(absentAnchors(12 * 60 + 30, 2, []).map((a) => a.kind), ['morning'])
})

test('到点判定：23:40 早、晚都要补；已经记过的不重复补', () => {
  assert.deepEqual(absentAnchors(23 * 60 + 40, 2, []).map((a) => a.kind), ['morning', 'evening'])
  assert.deepEqual(absentAnchors(23 * 60 + 40, 2, ['morning', 'evening']), [])
  assert.deepEqual(absentAnchors(23 * 60 + 40, 2, ['morning']).map((a) => a.kind), ['evening'])
})

test('周末那次只周日晚补，周一同一时刻不补', () => {
  assert.ok(absentAnchors(23 * 60 + 40, 0, ['morning', 'evening']).some((a) => a.kind === 'week'), '周日晚没补周末审查')
  assert.deepEqual(absentAnchors(23 * 60 + 40, 1, ['morning', 'evening']), [], '周一不该补周末审查')
  // 周日晚但还没到点。
  assert.deepEqual(absentAnchors(20 * 60 + 30, 0, ['morning', 'evening']), [])
})

test('作息：周日 20:30 开周门，周一同一时刻不开；关掉后当天不再开', () => {
  const day = Date.parse('2026-11-08T00:00:00+08:00')
  const sun = trackRhythm({}, { min: 20 * 60 + 30, day, now: SUN, screenOn: true, reviewedToday: false, weekday: 0 })
  assert.equal(sun.openWeek, true)
  assert.equal(sun.weekReviewGate, true)

  const mon = trackRhythm({}, { min: 20 * 60 + 30, day: Date.parse('2026-11-09T00:00:00+08:00'), now: MON, screenOn: true, reviewedToday: false, weekday: 1 })
  assert.equal(mon.openWeek, false)
  assert.equal(mon.weekReviewGate, false)

  const closed = closeGate(sun.state, 'week')
  assert.equal(closed.closed, true)
  const again = trackRhythm(closed.state, { min: 21 * 60, day, now: SUN + 30 * 60_000, screenOn: true, reviewedToday: false, weekday: 0 })
  assert.equal(again.weekReviewGate, false, '关过一次后当周不该再开')
})

// ---------------------------------------------------------------- 接线（真跑一遍 /plan）

function agentOf(id) {
  const contexts = []
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts, tools,
    followup(m) { this.sent.push(m) },
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
  return { handler: () => handler }
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
  const home = mkdtempSync(join(tmpdir(), 'companion-anchors-'))
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
  const h = host({ [SIS]: sis, [BIG]: big })
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: TZ, phone })
  const status = () => call(h.handler(), 'GET', '/dsh-go-companion/v1/status')
  const ledger = () => {
    try {
      return readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l))
    } catch { return [] }
  }
  const config = () => JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
  return { sis, big, plan, status, ledger, config }
}

const phone = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }

test('面板上三次审查各有一条位置，缺的那次是 null 而不是没有这个键', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(phone)
    const st = (await t.status()).body
    for (const k of ['morning', 'evening', 'week']) {
      assert.ok(k in st.anchors, '/status.anchors 少了 ' + k)
      const v = st.anchors[k]
      // 没审过是 null；跑了补记就是带 absent 的记录。两种都合法，别的形状不合法。
      if (v !== null) {
        assert.equal(typeof v.at, 'number')
        assert.equal(typeof v.pass, 'boolean')
        assert.equal(typeof v.absent, 'boolean')
      }
    }
  })
})

test('补缺席绝不下发锁：config.followup 不会被打开', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    // 挑一个当地已经过 12:00 的时刻跑，让它尽量真的补一条；补不补都不影响这条断言。
    await t.plan({ ...phone, local: '12:30' })
    await t.plan({ ...phone, local: '23:40' })
    assert.notEqual(t.config().followup?.active, true, '缺席记录把追问锁打开了')
    // 账本里冒出来的墙记录只可能是这三种锚点，不许混进别的 kind。
    const kinds = [...new Set(t.ledger().filter((r) => r.state === 'wall-audit').map((r) => r.kind))]
    for (const k of kinds) assert.ok(['morning', 'evening', 'week'].includes(k), '意外的 kind：' + k)
  })
})

test('week 是登记过的审查种类；门没开时交不上去，但不会被当成未知 kind', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan(phone)
    const tool = t.big.tools.submit_to_wall
    assert.ok(tool, '姐姐没有 submit_to_wall')
    assert.ok(tool.parameters.properties.kind.enum.includes('week'), 'kind 枚举里没有 week')
    const res = tool.execute({ kind: 'week' })
    assert.equal(res.ok, true, res.text)
    assert.equal(res.pass, false)
    assert.doesNotMatch(res.text, /kind 只能是/, 'week 被当成未知 kind 了')
    assert.match(res.text, /现在没有挂着的「周度审查」/, '门没开时的拒绝理由不对：' + res.text)
  })
})
