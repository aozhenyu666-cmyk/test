import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { cleanPhone } from '../lib/phone.js'
import {
  CONTEXT_EXEMPT_MS,
  TIER_AMBIGUOUS,
  TIER_BLACKLIST,
  TIER_CONTEXTUAL,
  TIER_EXEMPT,
  classifyAppTier,
  detectDegradedEscape,
  evaluateContextualExemption,
} from '../lib/app_governance.js'
import {
  DAILY_EMERGENCY_PASS_CAP,
  EMERGENCY_PASS_MS,
  buildAgentHandoffPrompt,
  evaluateElasticBuffer,
  extractAgentRecap,
  grantEmergencyPass,
  isAppLeased,
  isEmergencyPassActive,
  issueAppLease,
  resolveExternalAgent,
  revokeAppLease,
} from '../lib/external_agents.js'

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
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-p2-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try { rmSync(home, { recursive: true, force: true }) } catch {}
  }
}

test('M3.1: phone schema 规范化 screenText <=1500、mediaPlaying 与 60 分钟切换流', () => {
  const longText = '屏幕元素'.repeat(600)
  const p = cleanPhone({
    app: 'tv.danmaku.bili',
    mediaPlaying: true,
    screenText: longText,
    switches: {
      in90s: 2,
      in15m: 5,
      in60m: 12,
      distinct60m: 6,
      recent60m: [
        { pkg: 'md.obsidian', agoMs: 25 * 60_000 },
        { pkg: 'tv.danmaku.bili', agoMs: 5 * 60_000 },
      ],
    },
  })
  assert.equal(p.mediaPlaying, true)
  assert.ok(p.screenText.length <= 1500)
  assert.equal(p.switches.in60m, 12)
  assert.equal(p.switches.distinct60m, 6)
  assert.equal(p.switches.recent60m.length, 2)
})

test('M3.2: Tier 0-3 分层、Tier 1 90s 语境豁免与收回及 Tier 2 degraded_escape 检测', () => {
  assert.equal(classifyAppTier('com.android.calculator2').tier, TIER_EXEMPT)
  assert.equal(classifyAppTier('com.android.camera').tier, TIER_EXEMPT)
  assert.equal(classifyAppTier('com.miui.gallery').tier, TIER_CONTEXTUAL)
  assert.equal(classifyAppTier('com.android.chrome').tier, TIER_AMBIGUOUS)
  assert.equal(classifyAppTier('com.ss.android.ugc.aweme').tier, TIER_BLACKLIST)

  const t0 = 1_700_000_000_000
  const okCtx = evaluateContextualExemption({}, { pkg: 'com.miui.gallery', recentScreenshotAt: t0 - 15_000 }, t0)
  assert.equal(okCtx.exempt, true)
  assert.equal(okCtx.until, t0 + CONTEXT_EXEMPT_MS)

  const feedCut = evaluateContextualExemption(okCtx.state, {
    pkg: 'com.tencent.mm',
    screenText: '发现页 朋友圈 点赞 128 推荐视频',
  }, t0 + 10_000)
  assert.equal(feedCut.exempt, false)
  assert.equal(feedCut.reason, 'leisure-feed-detected')

  const expired = evaluateContextualExemption(okCtx.state, { pkg: 'com.miui.gallery' }, t0 + CONTEXT_EXEMPT_MS + 100)
  assert.equal(expired.exempt, false)
  assert.equal(expired.reclaimed, true)

  const dw1 = detectDegradedEscape({}, { phone: { app: 'com.android.chrome' }, now: t0 })
  assert.equal(dw1.degraded, false)
  const dw2 = detectDegradedEscape(dw1.nextState, { phone: { app: 'com.android.chrome' }, now: t0 + 130_000 })
  assert.equal(dw2.degraded, true)
  assert.equal(dw2.reason, 'tier2-dwell')

  const hop = detectDegradedEscape({}, {
    phone: {
      app: 'com.android.chrome',
      switches: {
        recent60m: [
          { app: 'com.android.chrome', agoMs: 10_000 },
          { app: 'com.miui.home', agoMs: 30_000 },
          { app: 'com.tencent.mm', agoMs: 60_000 },
          { app: 'tv.danmaku.bili', agoMs: 120_000 },
        ],
      },
    },
    now: t0,
  })
  assert.equal(hop.degraded, true)
  assert.equal(hop.reason, 'rapid-hopping')
})

test('M3.3: 外部 Agent 适配、Scoped App Lease、3 分钟 Emergency Pass 与弹性缓冲降级端到端验证', async () => {
  const gpt = resolveExternalAgent('chatgpt')
  assert.equal(gpt.packages[0], 'com.openai.chatgpt')
  const prompt = buildAgentHandoffPrompt({ agentId: 'chatgpt', topic: '核对分布式事务前提', minutes: 20 })
  assert.match(prompt, /ChatGPT/)
  assert.equal(extractAgentRecap('好的\n〔复盘〕完成：确认了两阶段提交假设 卡点：缺压测数据'), '完成：确认了两阶段提交假设 卡点：缺压测数据')

  const t0 = 1_700_000_000_000
  const leased = issueAppLease({}, { agentId: 'chatgpt', ttlMs: 25 * 60_000, topic: '架构拆解', now: t0 })
  assert.equal(leased.ok, true)
  assert.equal(isAppLeased(leased.state, 'com.openai.chatgpt', t0 + 5_000), true)
  assert.equal(isAppLeased(leased.state, 'tv.danmaku.bili', t0 + 5_000), false)
  assert.equal(isAppLeased(leased.state, 'com.openai.chatgpt', t0 + 26 * 60_000), false)
  assert.equal(revokeAppLease(leased.state).hadLease, true)

  const ep1 = grantEmergencyPass({}, { reason: '取验证码查快递', day: '2026-11-14', now: t0 })
  assert.equal(ep1.ok, true)
  assert.equal(isEmergencyPassActive(ep1.state, t0 + EMERGENCY_PASS_MS - 1000), true)
  assert.equal(isEmergencyPassActive(ep1.state, t0 + EMERGENCY_PASS_MS + 1000), false)
  let capState = ep1.state
  for (let i = 1; i < DAILY_EMERGENCY_PASS_CAP; i += 1) {
    capState = grantEmergencyPass(capState, { reason: `紧急${i}`, day: '2026-11-14', now: t0 + i * 1000 }).state
  }
  const epBlocked = grantEmergencyPass(capState, { reason: '超出次数', day: '2026-11-14', now: t0 + 5000 })
  assert.equal(epBlocked.ok, false)
  assert.equal(epBlocked.error, 'daily-cap-reached')

  const elastic = evaluateElasticBuffer({ plannedAt: t0, startedAt: 0, bufferMin: 18, now: t0 + 19 * 60_000 })
  assert.equal(elastic.exceeded, true)
  assert.equal(elastic.classification, 'scheduling_failure')
  assert.equal(elastic.fallbackBout.protocolId, 'micro_landing_3m')
  assert.equal(elastic.fallbackBout.minutes, 3)

  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12, refuseUntil: Date.now() + 3600_000,
    }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    const h = host({ 'session-sis': sis, 'session-big': big }, 'free')

    const leaseRes = await call(h.handler(), 'POST', '/dsh-go-companion/v1/lease', {
      action: 'issue', agentId: 'chatgpt', topic: '排查故障', ttlMs: 15 * 60_000,
    })
    assert.equal(leaseRes.status, 200)
    assert.ok(leaseRes.body.lease.packages.includes('com.openai.chatgpt'))

    const planDuringLease = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', {
      roll: 100, timeZone: 'Asia/Shanghai',
      phone: { app: 'tv.danmaku.bili', minutes: 12, mediaPlaying: true, screenOn: true },
    })
    assert.ok(planDuringLease.body.session.allow.includes('com.openai.chatgpt'))
    assert.equal(planDuringLease.body.media, 'pause')

    const epRes = await call(h.handler(), 'POST', '/dsh-go-companion/v1/emergency-pass', {
      reason: '需要打开健康码支付',
    })
    assert.equal(epRes.status, 200)
    assert.equal(epRes.body.ok, true)

    const planDuringEp = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', {
      roll: 100, timeZone: 'Asia/Shanghai',
      phone: { app: 'tv.danmaku.bili', minutes: 12, mediaPlaying: true, screenOn: true },
    })
    assert.equal(planDuringEp.body.force.level, 0)
    assert.equal(planDuringEp.body.force.release, true)
    assert.equal(planDuringEp.body.emergencyPass.active, true)
    assert.equal(planDuringEp.body.wall.locked, false)

    const bufRes = await call(h.handler(), 'POST', '/dsh-go-companion/v1/elastic-check', {
      plannedAt: Date.now() - 21 * 60_000, startedAt: 0, bufferMin: 20,
    })
    assert.equal(bufRes.body.exceeded, true)
    assert.equal(bufRes.body.classification, 'scheduling_failure')

    const ledgerText = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    assert.match(ledgerText, /"state":"external-lease-issue"/)
    assert.match(ledgerText, /"state":"emergency-pass-grant"/)
    assert.match(ledgerText, /"state":"scheduling-failure"/)
  })
})
