// 封印接线（验收第一批）：/seals 的立约、只能加码、到点没过保底进严格锁、交够解锁并发奖励、豁免扣紧急次数。
// 纯规则在 seal.test.mjs；这里钉的是插件里的读写、锁通道和账。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { minuteOfDay } from '../lib/tz.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const TZ = 'Asia/Shanghai'

function agentOf(id) {
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts: [], tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents) {
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  setStatePin('standby')
  apply(ctx)
  return () => handler
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = PREFIX + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-seal-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, timeZone: TZ, quietStart: 12, quietEnd: 12 }))
    const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    const seals = (body) => call(h(), 'POST', '/seals', body).then((r) => r.body)
    return await fn({ home, seals, h })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}

const json = (home, name) => JSON.parse(readFileSync(join(home, 'companion', name), 'utf8'))
const todayOf = (r) => r.today

test('封明天：落盘、可加码、调低被拒并记账', async () => {
  await withHome(async ({ home, seals }) => {
    const r = await seals({ action: 'add', seal: { title: '行测资料分析', kind: 'quiz', stretch: 40, dueMinute: 20 * 60 } })
    assert.equal(r.ok, true, JSON.stringify(r))
    assert.equal(r.seal.day, r.tomorrow)
    assert.equal(r.seal.floor, 20, '保底默认拉伸的一半')
    assert.equal(r.seal.sameDay, r.cutoffPassed, '23:00 以后封的算当天现定')
    const low = await seals({ action: 'raise', id: r.seal.id, stretch: 30 })
    assert.equal(low.ok, false)
    assert.equal(low.error, 'cannot-lower')
    const up = await seals({ action: 'raise', id: r.seal.id, stretch: 50, floor: 25 })
    assert.equal(up.ok, true)
    const file = json(home, 'seals.json')
    assert.equal(file.items[0].stretch, 50)
    const ledger = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    assert.match(ledger, /seal-lower-refused/)
    // 没有删除 / 改小的动作
    const del = await seals({ action: 'delete', id: r.seal.id })
    assert.equal(del.ok, false)
  })
})

test('到点没过保底 → 严格锁；交够保底 → 解锁并按晚交发奖励', async () => {
  const min = minuteOfDay(Date.now(), TZ)
  if (min < 3) return // 零点刚过：今天没有「已经过去的截止时间」可造，跳过这一拍
  await withHome(async ({ home, seals, h }) => {
    const first = await seals({ action: 'list' })
    const today = todayOf(first)
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({
      v: 1,
      items: [{ id: 'seal-t1', day: today, title: '题库 20 题', kind: 'quiz', stretch: 20, floor: 10, dueMinute: min - 2, estMinutes: 60, sealedAt: 1 }],
    }))
    const r = await seals({ action: 'list' })
    const s = r.items.find((x) => x.id === 'seal-t1')
    assert.equal(s.status, 'below')
    assert.ok(r.locking && r.locking.id === 'seal-t1', '应该锁着')
    const plan = await call(h(), 'POST', '/plan', { roll: 0, timeZone: TZ, phone: { app: 'com.ss.android.ugc.aweme', fun: true, minutes: 0, screenOn: true } })
    assert.equal(plan.status, 200)
    assert.equal(plan.body.lockArbiter?.source, 'CONTRACT_OVERDUE_STRICT', JSON.stringify(plan.body.lockArbiter).slice(0, 200))
    assert.equal(plan.body.lockArbiter?.label, '封印没过保底')
    assert.ok(plan.body.lockArbiter.allow.includes('md.obsidian'))
    assert.ok(!plan.body.lockArbiter.allow.includes('com.ss.android.ugc.aweme'))
    // 题库答对数涨 12 → 过保底，锁解，晚交 ×0.5：round(60/3)=20 × 1 × 0.5 = 10
    writeFileSync(join(home, 'companion', 'bank-unlock.json'), JSON.stringify({ stats: { correct: 12 } }))
    const after = await seals({ action: 'check' })
    const s2 = after.items.find((x) => x.id === 'seal-t1')
    assert.equal(s2.status, 'floor')
    assert.equal(after.locking, null)
    assert.equal(s2.paidMinutes, 10)
    const tl = json(home, 'time-ledger.json')
    assert.ok(JSON.stringify(tl).includes('earned_seal'), '奖励记进时间账')
  })
})

test('豁免：要理由、扣一次紧急解除、不开放行窗口', async () => {
  await withHome(async ({ home, seals }) => {
    const r0 = await seals({ action: 'list' })
    const min = minuteOfDay(Date.now(), TZ)
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({
      v: 1,
      items: [{ id: 'seal-w', day: r0.today, title: '笔记', kind: 'screenshots', stretch: 2, floor: 1, dueMinute: Math.min(1439, min + 30), sealedAt: 1 }],
    }))
    const bad = await seals({ action: 'waive', id: 'seal-w', reason: '' })
    assert.equal(bad.ok, false)
    const ok = await seals({ action: 'waive', id: 'seal-w', reason: '发烧去医院' })
    assert.equal(ok.ok, true, JSON.stringify(ok))
    assert.equal(ok.items.find((x) => x.id === 'seal-w').status, 'waived')
    const pl = json(home, 'pass-ledger.json')
    assert.equal(pl.emergency.count, 1)
    assert.ok(Number(pl.emergency.until) <= Date.now(), '豁免不开放行窗口')
  })
})

test('时间轴：到开始时刻 /plan 自动开这一局（被挡着就只提醒）；plan-day 一次加一串', async () => {
  const min = minuteOfDay(Date.now(), TZ)
  if (min < 3 || min > 1300) return // 零点附近 / 太晚没有可造的时段
  await withHome(async ({ home, seals, h }) => {
    const r0 = await seals({ action: 'list' })
    const pd = await seals({ action: 'plan-day', seals: [
      { title: '时间轴第一点', kind: 'quiz', stretch: 20, startMinute: min - 1, dueMinute: min + 60 },
      { title: '时间轴第二点', kind: 'bout_minutes', stretch: 60, dueMinute: Math.min(1439, min + 120) },
      { title: '坏的', kind: 'file', stretch: 5, dueMinute: min + 30 },
    ] })
    assert.equal(pd.added.length, 2)
    assert.equal(pd.skipped[0].error, 'no-path')
    const id = pd.added[0].id
    await call(h(), 'POST', '/plan', { roll: 0, timeZone: TZ, phone: { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 12, screenOn: true } })
    const st = json(home, 'seals.json')
    const s = st.items.find((x) => x.id === id)
    const drive = json(home, 'drive.json')
    assert.ok(s.openedAt > 0 || Number(drive.memo?.['tls:' + id]) > 0, '到点要么开了局，要么提醒过')
    if (s.openedAt > 0) {
      const ledger = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      assert.match(ledger, /"state":"timeline-open"/)
      assert.match(ledger, /"state":"bout-open"/)
    }
    assert.equal(r0.ok, true)
  })
})

