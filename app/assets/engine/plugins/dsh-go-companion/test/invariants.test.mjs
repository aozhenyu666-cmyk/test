// 2026-11-19 Wave 1 + Wave 2（H1–H7）核心不变式（I1–I12）与单元测试：
//   I1  系统底座在任何模式/任何组合下都不会出现在 lock 的锁目标里，也不会被 decideApp 判为锁
//   I2  只要 lock.mode != open，why 和 unlockHow 都不为空
//   I3  任意时刻至少存在一个脱身出口
//   I4  紧急解除期间：lock.mode == open；不接管、不自动拉 Gemini、不发 P2–P5 球
//   I5  发球仲裁每次最多选一球；除 P1–P2 外每小时不超过强度表上限
//   I6  两次自动拉起 Gemini 至少间隔 20 分钟；连麦中及结束后 10 分钟内不拉；达上限不拉
//   I7  同一个工作包在 workStore 里只有一份；五个旧视图各自统计的「今天几件」相等
//   I8  时间账剩余额度在 /today、/analytics、/plan（lock.quota）、晚报、姐妹上下文完全一致
//   I9  硬日判为「空转」时，投入分 <= 39
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
  resolveEffectiveIntensity,
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
import { isBasePkg } from '../lib/app_tiers.js'
import { createLockBus, pruneExpiredRequests, upsertLockRequest } from '../lib/lock_bus.js'
import { computeUnifiedLockArbiter, LOCK_PRIORITIES } from '../lib/unified_arbiter.js'
import { buildLockDecision, decideApp } from '../lib/lock_decision.js'
import { migrateLegacyToWorkStore, mirrorWorkStoreToLegacy, upsertWorkItem } from '../lib/work_store.js'
import { evaluateAutonomy14d } from '../lib/autonomy.js'

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
    // 北京时间的「今天」：UTC 日期在北京 0:00–8:00 会停在前一天，CI 在这个时段跑会误判。
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
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
    // 深夜锁（0:30–7:00，按服务端时钟）本来就不受硬干预上限约束（isLockExemptFromDailyCap）。
    // 测试只改了手机上报的本地时间，CI 若恰在北京深夜跑，服务端会判深夜锁——这时锁是对的，原因必须是深夜。
    if (p.body.lockArbiter?.source === 'NIGHT_CURFEW') {
      assert.equal(p.body.lock?.priority, 'P1', 'I10: 满额时还在锁，只能是深夜锁')
    } else {
      assert.equal(p.body.force.level, 0, 'I10: 达到硬干预日上限后降级为只提醒')
      assert.equal(p.body.force.app, undefined)
    }
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

test('H4 & I8 时间账单一真相（挣来制）：扣罚先扣挣来的再扣结转、不扣保底；四处显示余额完全一致', async () => {
  // 挣来制：昨天结转 50 + 保底 0 + 今天挣 15+5=20 - 扣罚 30 = 40；刷了 40 分钟抖音 → 余额 0。
  let tl = emptyTimeLedger('2026-11-19')
  tl = appendTimeEntry(tl, { kind: 'earned_bout', minutes: 15, day: '2026-11-19' })
  tl = appendTimeEntry(tl, { kind: 'earned_bank', minutes: 5, day: '2026-11-19' })
  tl = appendTimeEntry(tl, { kind: 'penalty', minutes: 30, day: '2026-11-19' })
  const summary = computeTimeLedger({
    state: tl,
    day: '2026-11-19',
    today: [{ app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 40 }],
    tierOf: () => 'fun',
    funQuota: 0,
    gameQuota: 60,
    carry: 50,
  })
  assert.equal(summary.earned, 20)
  assert.equal(summary.penalty, 30)
  assert.equal(summary.earnedNet, 0, '扣罚扣光挣来的')
  assert.equal(summary.penaltyApplied, 30, '挣来的不够扣，剩下 10 从结转里扣')
  assert.equal(summary.funLeft, 0, '50 + 20 - 30 - 40 = 0')
  assert.equal(summary.funOut, true)
  // 保底不扣：保底 30、没挣、没结转、扣罚 100 → 仍有 30。
  const floor = computeTimeLedger({ day: '2026-11-19', funQuota: 30, penalty: 100 })
  assert.equal(floor.funLeft, 30)
  // 游戏从同一个余额花，另有每日上限。
  const g = computeTimeLedger({ day: '2026-11-19', carry: 200, gameQuota: 60, tierOf: () => 'game', today: [{ app: 'g', minutes: 50 }] })
  assert.equal(g.funLeft, 150)
  assert.equal(g.gameLeft, 10, '游戏今天上限 60，已玩 50')
  // 结转有上限（CARRY_CAP_MIN = 300）。
  assert.equal(computeTimeLedger({ day: '2026-11-19', carry: 5000 }).carry, 300)

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

    // I1 & I2: 任意时刻任意锁模式都不锁底座，且非 open 必有非空 why / unlockHow
    const dec = buildLockDecision({
      arbiter: computeUnifiedLockArbiter({
        wall: isNight ? { locked: true, kind: 'night', why: '深夜睡眠锁' } : { locked: false },
        takeoverSession: hm === '14:10' ? { allow: ['md.obsidian'], until: now + 600_000, why: '接管回位' } : null,
        now,
      }),
      installed: [
        { pkg: 'com.bbk.launcher2', label: '桌面' },
        { pkg: 'com.ss.android.ugc.aweme', label: '抖音' },
      ],
      quota: { funOut: true, gameOut: true, line: '额度已用完' },
      now,
    })
    assert.equal(decideApp(dec, 'com.bbk.launcher2', isBasePkg).lock, false, `${hm} 底座永不锁`)
    if (dec.mode !== 'open') {
      assert.ok(String(dec.why || '').trim().length > 0, `${hm} why 不能为空`)
      assert.ok(String(dec.unlockHow || '').trim().length > 0, `${hm} unlockHow 不能为空`)
    }

    // I9: 任何时段空转晚报投入分 <= 39
    const r = generateEveningReport({
      day: '2026-11-19',
      usageReport: { totalMinutes: 180, funMinutes: 0, rows: [{ app: 'md.obsidian', minutes: 180, fun: false }] },
      ledger: [],
      now,
      tz: TZ,
    })
    assert.equal(r.verdict.tag, '空转')
    assert.ok(r.score <= 39, `${hm} 空转投入分 ${r.score} 必须 <= 39`)
  }
})

// ---------------------------------------------------------------- H5 + I1 + I2（锁总线、P2.5 接管、arbiterEnforce 开关）

test('H5 lock_bus：过期自动修剪、来源撤销防幽灵锁、P2.5 接管优先级与 HTTP arbiterEnforce 开关', async () => {
  const now = cstMs('2026-11-19', '14:10')

  // 1) lock_bus 自动修剪与来源撤销
  let raw = null
  const bus = createLockBus({ load: () => raw, save: (s) => { raw = s } })
  bus.upsert({ id: 'req-radar', source: 'radar', kind: 'deny', apps: ['com.ss.android.ugc.aweme'], until: now + 60_000, why: '刷太久' }, now)
  bus.upsert({ id: 'req-expired', source: 'switch', kind: 'deny', apps: ['tv.danmaku.bili'], until: now - 10, why: '已过期' }, now)
  assert.equal(bus.list(now).length, 1, '写入后读取自动修剪过期锁')
  bus.revokeBySource('radar', { now })
  assert.equal(bus.list(now).length, 0, 'revokeBySource 彻底清除幽灵锁')

  // 2) P1 NIGHT_CURFEW 仅次于 P0；P3.5 TAKEOVER_STRICT 位于 P3 CONTRACT_OVERDUE_STRICT 与 P4 BOUT_STRICT 之间
  assert.ok(LOCK_PRIORITIES.indexOf('EMERGENCY_SUSPEND') < LOCK_PRIORITIES.indexOf('NIGHT_CURFEW'))
  assert.ok(LOCK_PRIORITIES.indexOf('NIGHT_CURFEW') < LOCK_PRIORITIES.indexOf('GEMINI_LIVE_CELL'))
  assert.ok(LOCK_PRIORITIES.indexOf('CONTRACT_OVERDUE_STRICT') < LOCK_PRIORITIES.indexOf('TAKEOVER_STRICT'))
  assert.ok(LOCK_PRIORITIES.indexOf('TAKEOVER_STRICT') < LOCK_PRIORITIES.indexOf('BOUT_STRICT'))

  const p3OverP35 = computeUnifiedLockArbiter({
    contractSession: { strict: true, until: now + 600_000, why: '契约逾期' },
    takeoverSession: { until: now + 600_000, why: '接管回位' },
    now,
  })
  assert.equal(p3OverP35.priority, 'P3')

  const p35OverP4 = computeUnifiedLockArbiter({
    takeoverSession: { until: now + 600_000, allow: ['md.obsidian'], why: '系统接管强制回位' },
    presetStrict: { until: now + 600_000, allow: ['md.obsidian'], why: '普通严格局' },
    now,
  })
  assert.equal(p35OverP4.priority, 'P3.5')
  assert.equal(p35OverP4.source, 'TAKEOVER_STRICT')

  // 3) I1 & I2：所有系统底座永不被锁，所有非 open 锁皆带 why 与 unlockHow
  const baseApps = [
    'com.bbk.launcher2',
    'io.github.ccch1mneyyy.dsh_app.fish',
    'com.android.systemui',
    'com.baidu.input',
    'moe.nb4a',
    'com.android.dialer',
    'com.android.providers.media.module',
    'com.android.documentsui',
  ]
  const installed = [
    ...baseApps.map((pkg) => ({ pkg, label: pkg })),
    { pkg: 'com.ss.android.ugc.aweme', label: '抖音' },
    { pkg: 'md.obsidian', label: 'Obsidian' },
    { pkg: 'com.android.chrome', label: 'Chrome' },
  ]
  const modes = [
    { priority: 'P1', source: 'NIGHT_CURFEW', label: '深夜', why: '深夜睡', unlockHow: '早7点开', allow: [] },
    { priority: 'P2', source: 'GEMINI_LIVE_CELL', label: '连麦', why: '连麦中', unlockHow: '完成连麦', allow: [] },
    { priority: 'P3', source: 'CONTRACT_OVERDUE_STRICT', label: '契约', why: '契约逾期', unlockHow: '交验收', allow: ['md.obsidian'] },
    { priority: 'P3.5', source: 'TAKEOVER_STRICT', label: '接管', why: '接管中', unlockHow: '回正事', allow: ['md.obsidian'] },
    { priority: 'P4', source: 'BOUT_STRICT', label: '严格局', why: '专注局', unlockHow: '完成局', allow: ['md.obsidian'] },
    { priority: 'P5', source: 'CONTRACT_FLEX', label: '护航', why: '契约护航', unlockHow: '提交验收', allow: ['md.obsidian'] },
    { priority: 'P6', source: 'AUTONOMY_RELAXED', label: '自由', why: '自由态', unlockHow: '保持节奏', allow: ['*'] },
  ]
  for (const arb of modes) {
    const dec = buildLockDecision({
      arbiter: arb,
      installed,
      quota: { funOut: true, gameOut: true, line: '今日娱乐额度已用完' },
      radarDeny: [{ app: 'com.bbk.launcher2', until: now + 60_000, why: '尝试锁桌面' }, { app: 'com.ss.android.ugc.aweme', until: now + 60_000, why: '抖音临时锁' }],
      now,
    })
    assert.notEqual(dec.mode, 'open')
    assert.ok(String(dec.why).trim().length > 0, `${arb.priority} why 非空`)
    assert.ok(String(dec.unlockHow).trim().length > 0, `${arb.priority} unlockHow 非空`)
    for (const bp of baseApps) {
      assert.equal(dec.deny.some((d) => d.app === bp), false, `I1: ${arb.priority} deny 不含 ${bp}`)
      assert.equal(dec.needPass.includes(bp), false, `I1: ${arb.priority} needPass 不含 ${bp}`)
      assert.equal(decideApp(dec, bp, isBasePkg).lock, false, `I1: ${arb.priority} decideApp 不锁 ${bp}`)
    }
  }

  // 4) HTTP arbiterEnforce 默认 false，POST /config 可开关并在 /status /plan /today 一致曝光；紧急解除清除锁总线
  await withHome(async (home) => {
    seedHome(home)
    const sis = makeAgent('session-sis')
    const host = makeHost({ agent: sis, strategistAgent: makeAgent('session-big') })

    const s0 = await call(host.handler, 'GET', '/dsh-go-companion/v1/status')
    assert.equal(s0.body.arbiterEnforce, false)
    assert.equal(s0.body.config.arbiterEnforce, false)

    const cfgSet = await call(host.handler, 'POST', '/dsh-go-companion/v1/config', { arbiterEnforce: true })
    assert.equal(cfgSet.body.config.arbiterEnforce, true)

    const t1 = await call(host.handler, 'GET', '/dsh-go-companion/v1/today')
    assert.equal(t1.body.arbiterEnforce, true)

    // 先触发一次临时封禁，再发 disarm 必须撤销 lockBus 幽灵锁
    await sis.tools.block_apps.execute({ apps: ['com.ss.android.ugc.aweme'], hours: 2, reason: '防走神' })
    const busBefore = JSON.parse(readFileSync(join(home, 'companion', 'lock-requests.json'), 'utf8'))
    assert.ok(busBefore.requests.some((r) => r.apps.includes('com.ss.android.ugc.aweme')))

    await call(host.handler, 'POST', '/dsh-go-companion/v1/event', {
      type: 'disarm', disarmId: 'disarm-ghost-clear', reason: '临时急事', until: Date.now() + 600_000,
    })
    const busAfter = JSON.parse(readFileSync(join(home, 'companion', 'lock-requests.json'), 'utf8'))
    assert.equal(busAfter.requests.some((r) => r.source === 'sister' || r.source === 'radar'), false, '紧急解除撤销锁总线上的拦截请求')
  })
})

// ---------------------------------------------------------------- H6 + I7（任务单表 work.json + 五视图等数 + 主线自动回涨）

test('H6 & I7 work_store：一次性迁移去重、五视图今天几件相等、push_to_today 完局主线 +1', async () => {
  const now = cstMs('2026-11-19', '14:10')

  // 1) work_store 单元测试：同一工作包去重合并，只留一份
  const migrated = migrateLegacyToWorkStore({
    planItems: [{ id: 'p1', title: '资料分析 200 题', protocolId: 'execute', minute: 600, budget: 45, need: ['md.obsidian'] }],
    dailyTasks: [{ id: 'd1', task: '资料分析200题', evidence: '完成错题卡', due: 720, minutes: 45, dept: '谋划思考' }, { id: 'd2', task: '修改简历', evidence: '更新2段', due: 900, minutes: 30, dept: '执行' }],
    boutQueue: [{ id: 'b1', title: '资料分析 200 题', protocolId: 'execute', minutes: 50 }],
    day: '2026-11-19',
    now,
  })
  assert.equal(migrated.items.length, 2, 'I7: 同名任务合并去重只剩 2 份')
  const mirrored = mirrorWorkStoreToLegacy(migrated)
  assert.equal(mirrored.planItems.length, 2)
  assert.equal(mirrored.dailyTasks.length, 2)
  assert.equal(mirrored.boutQueue.length, 2)
  assert.equal(mirrored.unifiedTasks.length, 2)
  assert.equal(mirrored.totalCount, 2)

  // 2) HTTP 集成：push_to_today 产生 source='horizon' 条目并被所有视图一致计数；过关自动 doneSteps + 1
  await withHome(async (home) => {
    seedHome(home)
    const host = makeHost({ agent: makeAgent('session-sis'), strategistAgent: makeAgent('session-big') })

    const hz = await call(host.handler, 'POST', '/dsh-go-companion/v1/horizons', {
      action: 'upsert', horizon: 'medium', title: '行测冲刺战役', targetSteps: 5,
    })
    const hzId = hz.body.item.id
    assert.equal(hz.body.item.doneSteps, 0)

    const pushed = await call(host.handler, 'POST', '/dsh-go-companion/v1/horizons', {
      action: 'push_to_today', id: hzId, subtask: '资料分析 20 题', minutes: 45,
    })
    assert.equal(pushed.body.ok, true)

    await call(host.handler, 'POST', '/dsh-go-companion/v1/today/task', {
      title: '修改简历第二段', minutes: 30, allow: ['md.obsidian'],
    })

    const wsDisk = JSON.parse(readFileSync(join(home, 'companion', 'work.json'), 'utf8'))
    assert.equal(wsDisk.items.length, 2)
    assert.equal(wsDisk.items.find((x) => x.links?.horizonId === hzId)?.source, 'horizon')

    // 五视图统计的「今天几件」全部相等
    const planDay = await call(host.handler, 'GET', '/dsh-go-companion/v1/plan/day')
    const bouts = await call(host.handler, 'GET', '/dsh-go-companion/v1/bouts')
    const today = await call(host.handler, 'GET', '/dsh-go-companion/v1/today')
    assert.equal(planDay.body.plan.items.length, 2)
    assert.equal(planDay.body.numbers.planned, 2)
    assert.equal(bouts.body.queue.length, 2)
    assert.equal(today.body.unifiedTasks.length, 2)
    assert.equal(today.body.allQueueCount, 2)

    // 完成带 horizonId 的工作包：使用 micro-landing-3m 协议（开局并回答通过，校验 horizon doneSteps + 1）
    const hWork = wsDisk.items.find((x) => x.links?.horizonId === hzId)
    const started = await call(host.handler, 'POST', '/dsh-go-companion/v1/bouts', {
      action: 'start',
      id: hWork.id,
      protocolId: 'micro-landing-3m',
      allow: ['md.obsidian'],
      packet: { goal: hWork.title, first: '打开题本写第一题', deliverable: '完成20题错因分析', acceptKind: 'evidence', acceptWhat: '有具体结果和错因' },
    })
    assert.equal(started.body.ok, true)
    for (let step = 0; step < 4; step++) {
      const a = await call(host.handler, 'POST', '/dsh-go-companion/v1/bouts', {
        action: 'answer', text: `第${step + 1}步交付：已经完成 20 题，正确率 85%，错因在于基期比重找错分母。`,
      })
      if (a.body?.state === 'closed') break
    }
    const hzAfter = await call(host.handler, 'GET', '/dsh-go-companion/v1/horizons')
    const updatedItem = hzAfter.body.horizons.medium.find((m) => m.id === hzId)
    assert.equal(updatedItem.doneSteps, 1, '完成带 links.horizonId 的工作包后主线进度自动 +1')
  })
})

// ---------------------------------------------------------------- H7 + I9（评价统一、空转 <=39、14 天自律、信用分仅限通行证）

test('H7 & I9 评价统一：硬日判为主、空转投入分<=39、14天自律仅挪强度一档且快速降级、信用分仅限通行证', async () => {
  const now = cstMs('2026-11-19', '22:10')

  // 1) I9：即使专心 180 分钟（rawScore > 39），只要硬日判为「空转」，投入分必须 <= 39 且标签为「投入分」
  const emptyReport = generateEveningReport({
    day: '2026-11-19',
    usageReport: {
      totalMinutes: 200,
      funMinutes: 0,
      rows: [{ app: 'md.obsidian', label: 'Obsidian', minutes: 180, fun: false }],
    },
    ledger: [],
    now,
    tz: TZ,
  })
  assert.equal(emptyReport.verdict.tag, '空转')
  assert.equal(emptyReport.scoreLabel, '投入分')
  assert.ok(emptyReport.score <= 39, `I9: 空转时投入分 ${emptyReport.score} 必须 <= 39`)
  assert.doesNotMatch(emptyReport.headline, /战力/)
  assert.match(emptyReport.headline, /投入 \d+\/100/)

  // 2) H7 14 天滚动自律窗口：连续多日稳态达标才能 L2/L3 往松挪一档；一旦连续空转/逾期立刻降级恢复
  const goodDays = []
  const goodReports = {}
  for (let i = 1; i <= 7; i++) {
    const d = `2026-11-${String(12 + i).padStart(2, '0')}`
    goodDays.push({ day: d, bouts: { opened: 2, passed: 2, self: 2 }, selfOpenRatio: 1, deliverRatio: 1 })
    goodReports[d] = { verdict: { tag: '达成' } }
  }
  const earned = evaluateAutonomy14d({ days: goodDays, reports: goodReports, ledger: [], todayDay: '2026-11-19', now })
  assert.equal(earned.unlockedRelaxation, true)
  assert.equal(earned.shift, -1)

  const strictShifted = resolveEffectiveIntensity({ intensity: 'strict' }, { autonomy: earned, now, tz: TZ })
  assert.equal(strictShifted.intensity, 'strict')
  assert.equal(strictShifted.effectiveIntensity, 'standard', '自律达标将严格往松挪一档到标准')
  assert.equal(strictShifted.autonomyShift, -1)

  const gentleFloor = resolveEffectiveIntensity({ intensity: 'gentle' }, { autonomy: earned, now, tz: TZ })
  assert.equal(gentleFloor.effectiveIntensity, 'gentle', '不低于用户选择档位的更松边界')
  assert.equal(gentleFloor.autonomyShift, 0)

  // 降级比升级快：最近出现 2 天空转 -> 立即撤销松绑并降级
  const degradedReports = { ...goodReports, '2026-11-18': { verdict: { tag: '空转' } }, '2026-11-19': { verdict: { tag: '空转' } } }
  const downgraded = evaluateAutonomy14d({ days: goodDays, reports: degradedReports, ledger: [], todayDay: '2026-11-19', now })
  assert.equal(downgraded.level, 'L0')
  assert.equal(downgraded.unlockedRelaxation, false)
  assert.equal(downgraded.shift, 0)

  const reverted = resolveEffectiveIntensity({ intensity: 'strict' }, { autonomy: downgraded, now, tz: TZ })
  assert.equal(reverted.effectiveIntensity, 'strict', '不达标立即恢复用户自选严格档')
  assert.equal(reverted.autonomyShift, 0)

  // 3) 信用分仅限通行证审批 + HTTP /today & /analytics 返回投入分 <=39 与 effectiveIntensity
  await withHome(async (home) => {
    seedHome(home, { intensity: 'strict' })
    const host = makeHost({ agent: makeAgent('session-sis'), strategistAgent: makeAgent('session-big') })

    // 写入多次违抗把信用分拉低 (< 35)，测试 grant_task_lease 拒绝无 whyAgain，但 /plan 不因此额外加锁或阻断
    const ledPath = join(home, 'companion', 'ledger.jsonl')
    const baseAt = Date.now() - 1000
    const lowTrustRows = [1, 2, 3].map((i) => JSON.stringify({ state: 'defied', at: baseAt + i }))
    writeFileSync(ledPath, lowTrustRows.join('\n') + '\n')

    const noWhy = host.globalTools.grant_task_lease.execute({ app: 'com.android.chrome', taskGoal: '查资料' })
    assert.equal(noWhy.ok, false, '低信用分未填 whyAgain 拒绝直接批通行证')
    const withWhy = host.globalTools.grant_task_lease.execute({ app: 'com.android.chrome', taskGoal: '查资料', whyAgain: '确需检索报名表' })
    assert.equal(withWhy.ok, true, '填了 whyAgain 后可批')

    const today = await call(host.handler, 'GET', '/dsh-go-companion/v1/today')
    assert.equal(today.body.effectiveIntensity, 'strict')
    assert.equal(today.body.autonomyShift, 0)
    assert.equal(today.body.scoreLabel, '投入分')
    assert.equal(today.body.verdict.tag, '空转')
    assert.ok(today.body.score <= 39)
  })
})

// ---------------------------------------------------------------- 挣来制：跨天结转（2026-11-19）

test('挣来制：没花完的余额第二天接着用；每日保底 30 分钟（不白给 120）', async () => {
  await withHome(async (home) => {
    seedHome(home, { intensity: 'standard' })
    const day = new Date(Date.now() + 8 * 3_600_000).toISOString().slice(0, 10)
    const yesterday = new Date(Date.now() + 8 * 3_600_000 - 86_400_000).toISOString().slice(0, 10)
    // 昨天最后算出的余额是 45 分钟。
    writeFileSync(join(home, 'companion', 'fun-wallet.json'), JSON.stringify({ v: 1, day: yesterday, carryIn: 0, lastLeft: 45 }))
    const host = makeHost({ agent: makeAgent('session-sis'), strategistAgent: makeAgent('session-big') })
    const phone = cleanPhone({ app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '10:00', today: [] })
    const plan = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: TZ, phone })
    assert.equal(plan.body.timeLedger.carry, 45, '昨天的 45 分钟带进今天')
    assert.equal(plan.body.timeLedger.funQuota, 30, '默认无条件保底 30 分钟（4.1 温和留油）')
    assert.equal(plan.body.timeLedger.funLeft, 75)
    const w = JSON.parse(readFileSync(join(home, 'companion', 'fun-wallet.json'), 'utf8'))
    assert.equal(w.day, day)
    assert.equal(w.carryIn, 45)
  })
})

test('挣来制：一局挣多少 = 实际分钟（不超计划）的 1/3，夹在 10–40', async () => {
  const { rewardFor } = await import('../lib/earned.js')
  const t0 = 1_000_000_000
  assert.equal(rewardFor({ budgetMin: 45, openedAt: t0, now: t0 + 45 * 60_000 }), 15)
  assert.equal(rewardFor({ budgetMin: 90, openedAt: t0, now: t0 + 95 * 60_000 }), 30, '超时做不多给')
  assert.equal(rewardFor({ budgetMin: 90, openedAt: t0, now: t0 + 6 * 60_000 }), 10, '开 90 分钟局、6 分钟交差只给 10')
  assert.equal(rewardFor({ budgetMin: 180, openedAt: t0, now: t0 + 180 * 60_000 }), 40, '一局最多 40')
})
