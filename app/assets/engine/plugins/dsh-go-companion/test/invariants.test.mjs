// 2026-11-19 Wave 1（H1–H4）核心不变式与单元测试：
//   I3  任意时刻至少存在一个脱身出口
//   I4  紧急解除期间：lock.mode == open；不接管、不自动拉 Gemini、不发 P2–P5 球
//   I5  发球仲裁每次最多选一球；除 P1–P2 外每小时不超过强度表上限
//   I6  两次自动拉起 Gemini 至少间隔 20 分钟；连麦中及结束后 10 分钟内不拉；达上限不拉
//   I8  时间账剩余额度在 /today、/analytics、/plan（lock.quota）、晚报、姐妹上下文完全一致
//   I10 每天硬干预次数 ≤ 强度表上限；超出后只剩深夜锁和契约逾期锁，晚报写明「今天硬干预已经用满」
//   I11 改松强度档不会当天生效，跨天（dayString 滚到次日）后生效；姐妹工具只能改严
//   I12 假时钟在 01:20、03:40、08:30、14:10、22:10 北京时间均成立

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'

import { apply } from '../lib/index.js'
import {
  INTENSITY_TABLE,
  applyIntensityChange,
  hardInterventionStatus,
  isLockExemptFromDailyCap,
  recordHardIntervention,
  resolveIntensityState,
  thresholdsForIntensity,
} from '../lib/intensity.js'
import {
  emptyPassLedger,
  isEmergencyActive,
  passLedgerSummary,
  requestPass,
} from '../lib/pass_ledger.js'
import { SPEAK_PRIORITY, arbitrateSpeak, priorityOfCandidate } from '../lib/speak_arbiter.js'
import {
  GEMINI_AUTO_COOLDOWN_MS,
  GEMINI_POST_SESSION_COOLDOWN_MS,
  emptyGeminiGate,
  markGeminiSessionEnded,
  requestGemini,
} from '../lib/gemini_gate.js'
import { appendTimeEntry, computeTimeLedger, emptyTimeLedger } from '../lib/time_ledger.js'
import { generateEveningReport } from '../lib/analytics_center.js'
import { cleanPhone } from '../lib/phone.js'
import { returnAction } from '../lib/driver.js'
import { trustScore } from '../lib/trust.js'

const TZ = 'Asia/Shanghai'

function cstMs(ymd, hm) {
  return Date.parse(`${ymd}T${hm}:00+08:00`)
}

function makeHost({ agent, strategistAgent } = {}) {
  let handler
  const globalTools = {}
  const ctx = {
    get(name) {
      if (name === 'agents') {
        const list = [agent, strategistAgent].filter(Boolean)
        return {
          get: (id) => list.find((a) => a.id === id),
          roots: () => list,
          list: () => list,
        }
      }
      if (name === 'tools') return { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: id === strategistAgent?.id ? strategistAgent : agent }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_deps, cb) {
      cb({ webServer: { register(route) { handler = route.handler } }, effect(fn) { fn() } })
    },
    effect(fn) {
      fn()
    },
  }
  apply(ctx)
  return { handler, globalTools }
}

function makeAgent(id = 'session-sis') {
  const contexts = []
  const tools = {}
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    contexts,
    tools,
    followup() {},
    ctx: {
      systemPrompt: { context(def) { contexts.push(def); return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => {} } },
    },
  }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0,
    text: '',
    writeHead(status) { this.statusCode = status },
    end(chunk) { this.text = chunk ? String(chunk) : '' },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

function seedHome(home, cfg = {}) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(join(cfgDir, 'config.json'), JSON.stringify({
    sessionId: 'session-sis',
    strategistSessionId: 'session-big',
    quietStart: 2,
    quietEnd: 7,
    minGapMs: 0,
    ...cfg,
  }))
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.close()
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-inv-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    try { rmSync(home, { recursive: true, force: true }) } catch {}
  }
}

// ---------------------------------------------------------------- H1 + I10 + I11

test('H1 强度表数值精确对齐 & I11 改严立即生效、改松次日生效、姐妹工具只能改严', async () => {
  assert.equal(INTENSITY_TABLE.gentle.partnerMinGapMin, 15)
  assert.equal(INTENSITY_TABLE.gentle.maxBallsPerHour, 3)
  assert.equal(INTENSITY_TABLE.gentle.hardInterventionDailyCap, 8)
  assert.equal(INTENSITY_TABLE.standard.partnerMinGapMin, 8)
  assert.equal(INTENSITY_TABLE.standard.maxBallsPerHour, 4)
  assert.equal(INTENSITY_TABLE.standard.hardInterventionDailyCap, 12)
  assert.equal(INTENSITY_TABLE.strict.partnerMinGapMin, 5)
  assert.equal(INTENSITY_TABLE.strict.maxBallsPerHour, 6)
  assert.equal(INTENSITY_TABLE.strict.hardInterventionDailyCap, 20)
  assert.equal(INTENSITY_TABLE.strict.emergencyDisarmDailyCap, 2)

  const t1 = cstMs('2026-11-19', '14:10')
  const t2SameDay = cstMs('2026-11-19', '23:55')
  const t3NextDay = cstMs('2026-11-20', '00:05')

  // 改严立即生效
  const tighten = applyIntensityChange({ intensity: 'standard' }, 'strict', { now: t1, tz: TZ })
  assert.equal(tighten.ok, true)
  assert.equal(tighten.applied, 'immediate')
  assert.equal(tighten.intensity, 'strict')
  assert.equal(tighten.pendingIntensity, null)

  // 改松挂起，当天不生效
  const relax = applyIntensityChange({ intensity: 'strict' }, 'gentle', { now: t1, tz: TZ })
  assert.equal(relax.ok, true)
  assert.equal(relax.applied, 'pending')
  assert.equal(relax.intensity, 'strict')
  assert.equal(relax.pendingIntensity.tier, 'gentle')
  assert.equal(relax.pendingIntensity.effectiveDay, '2026-11-20')

  const sameDay = resolveIntensityState({ intensity: relax.intensity, pendingIntensity: relax.pendingIntensity }, { now: t2SameDay, tz: TZ })
  assert.equal(sameDay.intensity, 'strict', 'I11: 当天 23:55 仍维持 strict')
  assert.equal(sameDay.promoted, false)

  const nextDay = resolveIntensityState({ intensity: relax.intensity, pendingIntensity: relax.pendingIntensity }, { now: t3NextDay, tz: TZ })
  assert.equal(nextDay.intensity, 'gentle', 'I11: 跨天次日生效')
  assert.equal(nextDay.pendingIntensity, null)
  assert.equal(nextDay.promoted, true)

  // 姐妹工具禁止改松
  const forbid = applyIntensityChange({ intensity: 'strict' }, 'standard', { now: t1, tz: TZ, allowRelax: false })
  assert.equal(forbid.ok, false)
  assert.equal(forbid.error, 'relax-forbidden')

  // HTTP & 姐妹工具端到端
  await withHome(async (home) => {
    seedHome(home)
    const sis = makeAgent('session-sis')
    const big = makeAgent('session-big')
    const host = makeHost({ agent: sis, strategistAgent: big })

    const r1 = await call(host.handler, 'POST', '/dsh-go-companion/v1/intensity', { intensity: 'strict' })
    assert.equal(r1.status, 200)
    assert.equal(r1.body.intensity, 'strict')
    assert.equal(r1.body.emergencyDisarmDailyCap, 2)

    const r2 = await call(host.handler, 'POST', '/dsh-go-companion/v1/intensity', { intensity: 'gentle' })
    assert.equal(r2.status, 200)
    assert.equal(r2.body.applied, 'pending')
    assert.equal(r2.body.intensity, 'strict', 'I11: HTTP 改松当天不生效')
    assert.equal(r2.body.pendingIntensity?.tier, 'gentle')

    // 姐妹工具只能改严
    const toolRes = host.globalTools.intensity_tighten.execute({ tier: 'gentle', reason: '想偷懒' })
    assert.equal(toolRes.ok, false)
  })
})

test('I10 每天硬干预达上限后，只剩深夜锁和契约逾期锁，其余降级为温和提醒并写入晚报', async () => {
  let store = null
  for (let i = 0; i < 8; i++) {
    const r = recordHardIntervention(store, { kind: 'lock', source: 'radar', day: '2026-11-19', tier: 'gentle', now: i + 1 })
    store = r.state
  }
  const st = hardInterventionStatus(store, { day: '2026-11-19', tier: 'gentle' })
  assert.equal(st.count, 8)
  assert.equal(st.cap, 8)
  assert.equal(st.capped, true)
  assert.equal(isLockExemptFromDailyCap({ wallKind: 'night', contractOverdue: false }), true)
  assert.equal(isLockExemptFromDailyCap({ wallKind: 'default', contractOverdue: true }), true)
  assert.equal(isLockExemptFromDailyCap({ wallKind: 'default', contractOverdue: false }), false)

  const report = generateEveningReport({
    day: '2026-11-19',
    hardInterventions: st,
    now: cstMs('2026-11-19', '21:35'),
    tz: TZ,
  })
  assert.ok(report.causalHighlights.some((h) => h.includes('今天硬干预已经用满')), '晚报必须写明硬干预用满')

  // /plan 集成校验：硬干预满额时，白天娱乐雷达不再落强锁（force.level === 0），仅提醒
  await withHome(async (home) => {
    seedHome(home, { mode: 'coach', intensity: 'gentle' })
    const today = new Date().toISOString().slice(0, 10)
    writeFileSync(join(home, 'companion', 'hard-interventions.json'), JSON.stringify({
      v: 1,
      day: today,
      count: 8,
      items: Array.from({ length: 8 }, (_, i) => ({ kind: 'lock', source: 'radar', at: Date.now() - i * 1000 })),
    }))
    const host = makeHost({ agent: makeAgent() })
    const p = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', {
      v: 1,
      timeZone: TZ,
      phone: cleanPhone({
        app: 'com.tencent.tmgp.sgame',
        label: '王者荣耀',
        minutes: 25,
        fun: true,
        screenOn: true,
        local: '14:10',
        today: [{ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes: 25, fun: true }],
      }),
    })
    assert.equal(p.body.hardInterventions.capped, true)
    assert.equal(p.body.force.level, 0, 'I10: 达到硬干预日上限后降级为只提醒')
    assert.equal(p.body.force.app, undefined)
  })
})

// ---------------------------------------------------------------- H2 + I3 + I4

test('H2 统一放行账 & 幂等 disarmId 同步 & 共享每日上限 & 破窗不扣信用分', async () => {
  const now = cstMs('2026-11-19', '14:10')
  let pl = emptyPassLedger('2026-11-19')

  // 1) 本地 disarm + 幂等重复同步
  const d1 = requestPass(pl, 'emergency', {
    source: 'disarm', disarmId: 'disarm-2026-11-19-1', reason: '取快递验证码', cap: 3, now, day: '2026-11-19',
  })
  assert.equal(d1.ok, true)
  assert.equal(d1.countToday, 1)
  pl = d1.state

  const d1Dup = requestPass(pl, 'emergency', {
    source: 'disarm', disarmId: 'disarm-2026-11-19-1', reason: '取快递验证码', cap: 3, now: now + 5000, day: '2026-11-19',
  })
  assert.equal(d1Dup.ok, true)
  assert.equal(d1Dup.idempotent, true)
  assert.equal(d1Dup.countToday, 1, '重复同步同一个 disarmId 绝不重复计数')

  // 2) Gemini 卡延后 10 分钟（gemini-defer）共用计数
  const g1 = requestPass(pl, 'emergency', {
    source: 'gemini-defer', disarmId: 'disarm-2026-11-19-2', reason: 'Gemini 正事延后 10m', cap: 3, now: now + 60_000, day: '2026-11-19',
  })
  assert.equal(g1.ok, true)
  assert.equal(g1.countToday, 2)
  pl = g1.state

  // 3) 破窗（breakglass）共用第 3 次且不扣信用分
  const b1 = requestPass(pl, 'emergency', {
    source: 'breakglass', reason: '紧急处理事务', cap: 3, now: now + 120_000, day: '2026-11-19',
  })
  assert.equal(b1.ok, true)
  assert.equal(b1.countToday, 3)
  assert.equal(b1.left, 0)
  assert.equal(b1.penalty, 0)
  pl = b1.state

  // 4) 第 4 次被任何应急入口拦回
  const over = requestPass(pl, 'emergency', {
    source: 'emergency-pass', reason: '还想再开一次', cap: 3, now: now + 180_000, day: '2026-11-19',
  })
  assert.equal(over.ok, false)
  assert.equal(over.error, 'daily-cap-reached')

  // 5) adb 不计次
  const adb = requestPass(pl, 'emergency', {
    source: 'adb', counted: false, reason: 'adb 调试', cap: 3, now: now + 190_000, day: '2026-11-19',
  })
  assert.equal(adb.ok, true)
  assert.equal(adb.countToday, 3)

  // 6) 纯游戏通行证硬拒；第 9 张通行证需要 whyAgain
  const gameLease = requestPass(pl, 'lease', { app: 'com.tencent.tmgp.sgame', label: '王者荣耀', now, day: '2026-11-19' })
  assert.equal(gameLease.ok, false)
  assert.equal(gameLease.error, 'pure-game-forbidden')

  let leasePl = pl
  for (let i = 1; i <= 8; i++) {
    const l = requestPass(leasePl, 'lease', { app: 'com.android.chrome', label: 'Chrome', goal: '查资料' + i, now: now + i * 1000, day: '2026-11-19' })
    assert.equal(l.ok, true)
    leasePl = l.state
  }
  const ninthNoReason = requestPass(leasePl, 'lease', { app: 'com.android.chrome', goal: '查资料9', now: now + 10_000, day: '2026-11-19' })
  assert.equal(ninthNoReason.ok, false)
  assert.equal(ninthNoReason.error, 'why-again-required')
  const ninthOk = requestPass(leasePl, 'lease', { app: 'com.android.chrome', goal: '查资料9', whyAgain: '继续核对报名简章', now: now + 11_000, day: '2026-11-19' })
  assert.equal(ninthOk.ok, true)

  // 7) 破窗不扣 trustScore
  assert.equal(trustScore([{ state: 'breakglass', at: now }], now + 1000).score, 60)

  // HTTP 端到端测试：disarm 幂等 + I4（emergencyActive 期间 lock.mode === 'open'、不接管、不自动拉 Gemini、不发 P2–P5）
  await withHome(async (home) => {
    seedHome(home, { mode: 'coach', gear: 'busy', gearWhat: '刷题', gearUntil: Date.now() + 3600_000 })
    const host = makeHost({ agent: makeAgent('session-sis'), strategistAgent: makeAgent('session-big') })

    const dis1 = await call(host.handler, 'POST', '/dsh-go-companion/v1/event', {
      type: 'disarm', disarmId: 'disarm-e2e-1', reason: '接电话回消息', until: Date.now() + 10 * 60_000,
    })
    assert.equal(dis1.body.ok, true)
    assert.equal(dis1.body.countToday, 1)

    const disDup = await call(host.handler, 'POST', '/dsh-go-companion/v1/event', {
      type: 'disarm', disarmId: 'disarm-e2e-1', reason: '接电话回消息', until: Date.now() + 10 * 60_000,
    })
    assert.equal(disDup.body.ok, true)
    assert.equal(disDup.body.idempotent, true)
    assert.equal(disDup.body.countToday, 1)

    // I4 检查：紧急解除期间请求 /plan
    const p = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', {
      v: 1,
      timeZone: TZ,
      roll: 0,
      phone: cleanPhone({
        app: 'com.ss.android.ugc.aweme',
        label: '抖音',
        minutes: 150,
        fun: true,
        screenOn: true,
        local: '14:10',
        today: [{ app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 200, fun: true }],
      }),
    })
    assert.equal(p.status, 200)
    assert.equal(p.body.lock.mode, 'open', 'I4: 紧急解除期间 lock.mode 必须为 open')
    assert.equal(p.body.drive?.takeover, null, 'I4: 紧急解除期间不接管')
    assert.equal(p.body.geminiAutoPull, undefined, 'I4: 紧急解除期间不自动拉 Gemini')
    assert.equal(p.body.speak, false, 'I4: 紧急解除期间不发 P2–P5 主动球')
  })
})

test('I3 任意时刻至少存在一个脱身出口（紧急解除剩余次数 / 锁带 until / 回位动作）', () => {
  // 1) 紧急解除有剩余次数
  const pl = emptyPassLedger('2026-11-19')
  const sum = passLedgerSummary(pl, { day: '2026-11-19', cap: 3 })
  assert.ok(sum.emergency.left > 0)
  // 2) 即使次数用满，有回位动作（returnAction 永不为空）
  assert.ok(returnAction({}).length > 0)
  assert.ok(returnAction({ workApp: 'Obsidian' }).length > 0)
})

// ---------------------------------------------------------------- H3 + I5 + I6

test('I5 发球仲裁：5 级优先级表 + 总节流（P1/P2 豁免，P3–P5 受限，连麦中只许 P4）', () => {
  const now = cstMs('2026-11-19', '14:10')

  // 1) 每次最多选一球，按最高优先级挑出 P1 > P2 > P3 > P4 > P5
  const pick1 = arbitrateSpeak({
    candidates: [
      { reason: 'restless', role: 'companion' },
      { reason: 'patrol', role: 'strategist' },
      { reason: 'morning-gate', role: 'strategist' },
      { reason: 'night', role: 'strategist' },
    ],
    now,
    sentLastHour: 10,
    maxBallsPerHour: 4,
    lastBallAt: now - 60_000,
    minGapMs: 8 * 60_000,
  })
  assert.equal(pick1.picked.reason, 'night')
  assert.equal(pick1.picked.priority, 1)

  // 2) 小时上限已满：P3/P5 被拦，P2 豁免
  const throttled = arbitrateSpeak({
    candidates: [
      { reason: 'patrol', role: 'strategist' },
      { reason: 'restless', role: 'companion' },
    ],
    now,
    sentLastHour: 4,
    maxBallsPerHour: 4,
    lastBallAt: now - 20 * 60_000,
    minGapMs: 8 * 60_000,
  })
  assert.equal(throttled.picked, null)
  assert.equal(throttled.throttled, true)

  const p2Exempt = arbitrateSpeak({
    candidates: [
      { reason: 'patrol', role: 'strategist' },
      { reason: 'review', role: 'strategist', timepointKey: 'review:2026-11-19' },
    ],
    now,
    sentLastHour: 4,
    maxBallsPerHour: 4,
    lastBallAt: now - 60_000,
    minGapMs: 8 * 60_000,
    silence: { stage: 'stop' },
  })
  assert.equal(p2Exempt.picked.reason, 'review', 'P2 硬时间点豁免总节流且绕过沉默一次')

  // 3) 连麦中只允许 P4
  const inGem = arbitrateSpeak({
    candidates: [
      { reason: 'night', role: 'strategist' },
      { reason: 'review', role: 'strategist' },
      { reason: 'gemini-checkpoint', role: 'strategist' },
    ],
    now,
    inGemini: true,
    sentLastHour: 0,
    maxBallsPerHour: 4,
  })
  assert.equal(inGem.picked.reason, 'gemini-checkpoint')
  assert.equal(inGem.picked.priority, SPEAK_PRIORITY.GEMINI)

  // 4) 雷达跟着锁走不另占名额
  const radarLock = arbitrateSpeak({
    candidates: [{ reason: 'radar-game-binge', role: 'companion', withLock: true }],
    now,
    sentLastHour: 4,
    maxBallsPerHour: 4,
  })
  assert.equal(priorityOfCandidate({ reason: 'radar-game-binge', withLock: true }), 3)
  assert.equal(radarLock.picked.reason, 'radar-game-binge')
})

test('I6 Gemini 闸门：自动拉起 20m 冷却 + 每日上限 + 连麦中/结束后 10m 禁止 + 手动不受限', () => {
  const now = cstMs('2026-11-19', '14:10')
  let g = emptyGeminiGate('2026-11-19')

  // 第 1 次自动拉起成功
  const r1 = requestGemini(g, { source: 'drift', topic: '别刷了', now, day: '2026-11-19', dailyCap: 6 })
  assert.equal(r1.allowed, true)
  assert.equal(r1.countToday, 1)
  g = r1.state

  // 10 分钟后（未满 20m 冷却）被拒
  const tooSoon = requestGemini(g, { source: 'fun-dwell-8m', topic: '又刷了', now: now + 10 * 60_000, day: '2026-11-19', dailyCap: 6 })
  assert.equal(tooSoon.allowed, false)
  assert.equal(tooSoon.reason, 'cooldown')

  // 手动点击不受 20m 冷却和每日上限限制，只记账
  const manual = requestGemini(g, { source: 'dice', mode: 'manual', topic: '手动点骰子连 Gemini', now: now + 10 * 60_000, day: '2026-11-19', dailyCap: 6 })
  assert.equal(manual.allowed, true)
  assert.equal(manual.countToday, 1, '手动不占自动上限')
  g = manual.state

  // 满 20m 冷却，但刚结束连麦 5m（< 10m）被拒
  const ended = markGeminiSessionEnded(g, { now: now + 18 * 60_000, day: '2026-11-19' })
  const postBlocked = requestGemini(ended, { source: 'switch-limit', topic: '切太多', now: now + 23 * 60_000, day: '2026-11-19', dailyCap: 6 })
  assert.equal(postBlocked.allowed, false)
  assert.equal(postBlocked.reason, 'post-session-cooldown')

  // 超过 20m 自动冷却且超过 10m 结束后冷却，允许
  const ok2 = requestGemini(ended, {
    source: 'switch-limit',
    topic: '切太多',
    now: now + GEMINI_AUTO_COOLDOWN_MS + GEMINI_POST_SESSION_COOLDOWN_MS,
    day: '2026-11-19',
    dailyCap: 6,
  })
  assert.equal(ok2.allowed, true)
  assert.equal(ok2.countToday, 2)

  // 连麦中拒绝自动拉起
  const inSess = requestGemini(ok2.state, {
    source: 'drift',
    now: now + 60 * 60_000,
    day: '2026-11-19',
    sessionActive: true,
  })
  assert.equal(inSess.allowed, false)
  assert.equal(inSess.reason, 'session-active')
})

// ---------------------------------------------------------------- H4 + I8

test('H4 & I8 时间账单一真相：扣罚只扣挣来的、绝不扣基础；四处显示剩余额度完全一致', async () => {
  // 纯函数校验：基础 120，挣来 15+5=20，扣罚 30（只扣完挣来的 20，基础 120 纹丝不动）
  let tl = emptyTimeLedger('2026-11-19')
  tl = appendTimeEntry(tl, { kind: 'earned_bout', minutes: 15, day: '2026-11-19' })
  tl = appendTimeEntry(tl, { kind: 'earned_bank', minutes: 5, day: '2026-11-19' })
  tl = appendTimeEntry(tl, { kind: 'penalty', minutes: 30, day: '2026-11-19' })
  const summary = computeTimeLedger({
    state: tl,
    day: '2026-11-19',
    today: [{ app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 40 }],
    tierOf: () => 'fun',
    funQuota: 120,
    gameQuota: 60,
  })
  assert.equal(summary.earned, 20)
  assert.equal(summary.penalty, 30)
  assert.equal(summary.earnedNet, 0, '扣罚扣光挣来的')
  assert.equal(summary.funLeft, 120 - 40 + 30, '基础 120 + 游戏换算 30 - 已用 40 = 110，基础额度绝不被扣')

  // 四处一致性（/today, /analytics, /plan lock.quota, 晚报）
  await withHome(async (home) => {
    seedHome(home, { intensity: 'standard' })
    const host = makeHost({ agent: makeAgent('session-sis'), strategistAgent: makeAgent('session-big') })
    const phone = cleanPhone({
      app: 'com.ss.android.ugc.aweme',
      label: '抖音',
      minutes: 10,
      fun: true,
      screenOn: true,
      local: '14:10',
      today: [
        { app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 50, fun: true },
        { app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes: 20, fun: true },
      ],
    })
    const planRes = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: TZ, phone })
    const todayRes = await call(host.handler, 'GET', '/dsh-go-companion/v1/today')
    const analyticsRes = await call(host.handler, 'GET', '/dsh-go-companion/v1/analytics?rangeDays=7')
    const reportRes = await call(host.handler, 'POST', '/dsh-go-companion/v1/analytics', { action: 'generate_report' })

    const funLeftPlan = planRes.body.timeLedger.funLeft
    const funLeftLock = planRes.body.lock.quota.funLeft
    const funLeftToday = todayRes.body.timeLedger.funLeft
    const funLeftAnalytics = analyticsRes.body.timeLedger.funLeft
    const funLeftReport = reportRes.body.report.timeLedger.funLeft

    assert.equal(funLeftPlan, funLeftLock, 'I8: /plan 与 lock.quota 一致')
    assert.equal(funLeftToday, funLeftPlan, 'I8: /today 与 /plan 一致')
    assert.equal(funLeftAnalytics, funLeftPlan, 'I8: /analytics 与 /plan 一致')
    assert.equal(funLeftReport, funLeftPlan, 'I8: 晚报与 /plan 一致')
  })
})

// ---------------------------------------------------------------- I12 假时钟跨时段不变式

test('I12 假时钟在 01:20、03:40、08:30、14:10、22:10 北京时间全量不变式成立', () => {
  const slots = ['01:20', '03:40', '08:30', '14:10', '22:10']
  for (const hm of slots) {
    const now = cstMs('2026-11-19', hm)
    const isNight = hm === '01:20' || hm === '03:40'

    // I4 & I5: 紧急解除下，arbitrateSpeak 不发 P2-P5
    const arbEmergency = arbitrateSpeak({
      candidates: [
        { reason: 'morning-gate', role: 'strategist' },
        { reason: 'patrol', role: 'strategist' },
        { reason: 'restless', role: 'companion' },
      ],
      now,
      emergencyActive: true,
    })
    assert.equal(arbEmergency.picked, null, `${hm} 紧急解除不发 P2-P5`)

    // I5: 任意时刻最多选一球，且超上限拦住 P3-P5
    const arbCap = arbitrateSpeak({
      candidates: [{ reason: 'patrol', role: 'strategist' }],
      now,
      sentLastHour: 4,
      maxBallsPerHour: 4,
    })
    assert.equal(arbCap.picked, null, `${hm} 超每小时上限拦住 P3`)

    // I6: 深夜时段或紧急期间自动拉起 Gemini 均被拒绝
    const gem = requestGemini(emptyGeminiGate('2026-11-19'), {
      source: 'drift',
      now,
      day: '2026-11-19',
      isNight,
      isQuiet: isNight,
    })
    if (isNight) assert.equal(gem.allowed, false, `${hm} 深夜禁止自动拉起 Gemini`)
    else assert.equal(gem.allowed, true, `${hm} 白天首次自动拉起 Gemini 允许`)

    // I11: 无论几点改松，effectiveDay 总是次日 2026-11-20
    const relax = applyIntensityChange({ intensity: 'strict' }, 'gentle', { now, tz: TZ })
    assert.equal(relax.intensity, 'strict', `${hm} 改松当天不生效`)
    assert.equal(relax.pendingIntensity.effectiveDay, '2026-11-20', `${hm} 生效日为次日`)

    // I10: 硬干预满额时仅深夜锁与契约逾期锁豁免
    assert.equal(isLockExemptFromDailyCap({ wallKind: isNight ? 'night' : 'default', contractOverdue: false }), isNight)
  }
})
