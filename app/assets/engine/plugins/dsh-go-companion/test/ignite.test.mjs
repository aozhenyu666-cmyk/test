// 2026-11-20 主动点火：Gemini 常驻、冷启动点火、追问改道 Gemini、聊完落地、局里叫 Gemini 来判。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync, appendFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { ignite, nextPlanItem, nextDrive, CHAT_PACKAGES } from '../lib/driver.js'
import { isNeverLockPkg } from '../lib/never_lock.js'
import { GEMINI_MODES } from '../lib/gemini.js'
import { apply } from '../lib/index.js'

const base = { now: 10_000_000_000, minuteOfDay: 10 * 60, frontApp: 'com.bbk.launcher2', frontTier: 'base', frontMinutes: 12, idleMin: 10, intensity: 'standard' }

test('常驻：Gemini 双包与 Shizuku 永不锁', () => {
  assert.equal(isNeverLockPkg('com.google.android.apps.bard'), true)
  assert.equal(isNeverLockPkg('com.google.android.googlequicksearchbox'), true)
  assert.equal(isNeverLockPkg('moe.shizuku.privileged.api'), true)
  assert.equal(isNeverLockPkg('moe.nb4a'), true, 'NekoBox 本来就是常驻')
  assert.ok(GEMINI_MODES.plan && GEMINI_MODES.receipt)
})

test('冷启动：有没定时间的局就开它；一条都没排就拉 Gemini（plan）；排了但都在后面不点', () => {
  assert.equal(ignite({ ...base, planItems: [{ id: 'a', title: '刷题' }] }).action, 'open-bout')
  const g = ignite({ ...base, planItems: [] })
  assert.equal(g.action, 'gemini')
  assert.equal(g.mode, 'plan')
  assert.equal(ignite({ ...base, planItems: [{ id: 'b', title: '下午那局', minute: 15 * 60 }] }), null)
  assert.equal(ignite({ ...base, planItems: [{ id: 'c', title: '到点', minute: 10 * 60 + 5 }] }).action, 'open-bout')
})

test('冷启动不点：额度优先（娱乐 / 游戏里花余额）、聊天 App、早上 15 分钟、开过局、待得不够、被挡、刚点过', () => {
  const items = { planItems: [{ id: 'a', title: '刷题' }] }
  assert.equal(ignite({ ...base, ...items, frontTier: 'fun', frontApp: 'tv.danmaku.bili' }), null)
  assert.equal(ignite({ ...base, ...items, frontTier: 'game' }), null)
  for (const app of CHAT_PACKAGES) assert.equal(ignite({ ...base, ...items, frontApp: app, frontTier: 'dual', frontMinutes: 30 }), null, app)
  assert.equal(ignite({ ...base, ...items, firstScreenAt: base.now - 5 * 60_000 }), null)
  assert.equal(ignite({ ...base, ...items, openedToday: true }), null)
  assert.equal(ignite({ ...base, ...items, frontMinutes: 4 }), null)
  assert.equal(ignite({ ...base, ...items, blocked: true }), null)
  assert.equal(ignite({ ...base, ...items, lastIgniteAt: base.now - 10 * 60_000 }), null)
  assert.equal(ignite({ ...base, ...items, minuteOfDay: 8 * 60 }), null, '工作时段外')
  assert.equal(ignite({ ...base, ...items, intensity: 'gentle' }).action, 'hint', '温和档只提示不抢屏')
})

test('推进器：没定时间的局也算下一局；昨天的收局时间不再当成「上一局」', () => {
  assert.equal(nextPlanItem([{ id: 't', minute: 900 }, { id: 'u' }], 600).id, 't')
  assert.equal(nextPlanItem([{ id: 'u' }], 600).id, 'u')
  const dayStart = 9_999_000_000
  const d = nextDrive({ now: dayStart + 2 * 3_600_000, minuteOfDay: 11 * 60, mode: 'push', lastBoutEndAt: dayStart - 3_600_000, dayStartMs: dayStart, planItems: [{ id: 'u', title: '今日主线' }] })
  assert.notEqual(d?.kind, 'auto-open', '昨天收的局不算，今天的冷启动交给 ignite')
  const d2 = nextDrive({ now: dayStart + 2 * 3_600_000, minuteOfDay: 11 * 60, mode: 'push', lastBoutEndAt: dayStart + 3_600_000, dayStartMs: dayStart, planItems: [{ id: 'u', title: '今日主线' }], boutGapAutoMs: 10 * 60_000, boutGapPushMs: 5 * 60_000 })
  assert.equal(d2.kind, 'auto-open', '今天收了一局、空了一小时：没定时间的局也会被开')
})

// ---------------------------------------------------------------- 真引擎

function host() {
  const agent = { id: 's1', status: 'idle', inbox: { hasPending: false }, config: { provider: 't', model: 't' }, followup() {} }
  let handler
  apply({
    get(n) {
      if (n === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (n === 'connection') return { requestRejection: () => undefined }
      if (n === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (n === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(f) { f() } }) },
    effect(f) { f() },
  })
  return handler
}
function call(h, path, body, method = 'POST') {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method; req.url = '/dsh-go-companion/v1' + path; req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(h(req, res)).then(() => JSON.parse(res.text))
}
async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'ignite-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 's1', quietStart: 12, quietEnd: 12 }))
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = old
    try { rmSync(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 }) } catch {}
  }
}
const cfg = (home) => JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
const ledger = (home) => readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))

test('/plan 下发的永不锁名单里有 Gemini 双包与 Shizuku', async () => {
  await withHome(async () => {
    const h = host()
    const p = await call(h, '/plan', { v: 1, timeZone: 'Asia/Shanghai', phone: { app: 'com.bbk.launcher2', minutes: 1, screenOn: true, local: '10:00' } })
    assert.ok(p.neverLock.includes('com.google.android.apps.bard'))
    assert.ok(p.neverLock.includes('com.google.android.googlequicksearchbox'))
    assert.ok(p.neverLock.includes('moe.shizuku.privileged.api'))
  })
})

test('连麦结束：先等回执，不立刻落追问锁；有回执 → 结，下一步进今日主线；没回执 10 分钟 → 落锁', async () => {
  await withHome(async (home) => {
    const h = host()
    const e = await call(h, '/event', { type: 'gemini-end', minutes: 8 })
    assert.equal(e.awaitingReceipt, true)
    assert.equal(cfg(home).followup, null, '没有立刻落追问锁')
    assert.equal(cfg(home).followupGemini.reason, 'gemini')
    // 10 分钟没回执：下一次 /plan 落锁
  })
})

const phone = { app: 'com.bbk.launcher2', minutes: 1, screenOn: true, local: '10:00' }

test('等回执：10 分钟没回执，下一次 /plan 落原来的追问锁', async () => {
  await withHome(async (home) => {
    const h = host()
    await call(h, '/event', { type: 'gemini-end', minutes: 8 })
    // 让「10 分钟」过去：插件 config 在内存里，用同一条路把截止时间改到过去。
    await call(h, '/event', { type: 'gemini-end', minutes: 0 })
    const c = cfg(home)
    assert.ok(c.followupGemini)
    c.followupGemini.until = Date.now() - 1
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify(c))
    const h2 = host()
    await call(h2, '/plan', { v: 1, timeZone: 'Asia/Shanghai', phone })
    assert.equal(cfg(home).followupGemini, null)
    assert.equal(cfg(home).followup?.reason, 'gemini', '兜底：落原来的追问锁')
    assert.ok(ledger(home).some((e) => e.state === 'followup-gemini-fallback'))
  })
})

test('等回执：回执到了 → 结，不落锁', async () => {
  await withHome(async (home) => {
    const h = host()
    await call(h, '/event', { type: 'gemini-end', minutes: 8 })
    appendFileSync(join(home, 'companion', 'ledger.jsonl'), JSON.stringify({ state: 'gemini-recap', at: Date.now(), receipt: true }) + '\n')
    await call(h, '/plan', { v: 1, timeZone: 'Asia/Shanghai', phone })
    assert.equal(cfg(home).followupGemini, null)
    assert.equal(cfg(home).followup, null)
    assert.ok(ledger(home).some((e) => e.state === 'followup-gemini-answered'))
  })
})

test('违抗：先拉 Gemini 带同一个问题（审讯口吻），不先落追问锁', async () => {
  await withHome(async (home) => {
    const h = host()
    await call(h, '/event', { type: 'defied', app: 'tv.danmaku.bili', label: '哔哩哔哩' })
    const c = cfg(home)
    if (!c.followupGemini) {
      // 闸门不让拉（比如测试时正好是深夜 / 安静时段）：必须兜底落原来的追问锁，不能两头都没有。
      assert.equal(c.followup?.reason, 'defiance')
      return
    }
    assert.equal(c.followup, null)
    const p = await call(h, '/plan', { v: 1, timeZone: 'Asia/Shanghai', phone })
    assert.equal(p.geminiAutoPull?.reason, 'followup-' + c.followupGemini.reason)
    assert.equal(p.speak, false, '这一拍拉了 Gemini，就不再开口')
  })
})

test('局里叫 Gemini 来判：带上这一局的目标，think 口吻；没局时说没有局', async () => {
  await withHome(async () => {
    const h = host()
    const none = await call(h, '/bouts', { action: 'ask-gemini' })
    assert.equal(none.ok, false)
    const open = await call(h, '/bouts', { action: 'open', protocolId: 'execute', title: '写周报', minutes: 30 })
    assert.equal(open.ok, true, JSON.stringify(open))
    const r = await call(h, '/bouts', { action: 'ask-gemini' })
    assert.equal(r.ok, true)
    assert.match(r.topic, /写周报/)
    assert.match(r.brief, /共脑|想清楚/)
  })
})
