import test from 'node:test'
import assert from 'node:assert/strict'

import {
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
  DAILY_SCHEDULING_FAILURE_FLOOR_CAP,
  EMERGENCY_PASS_MS,
  activeAppLease,
  checkEmergencyPassPullBack,
  emptyExternalState,
  evaluateDailySchedulingFloor,
  evaluateElasticBuffer,
  evaluateExternalRecapFallback,
  grantEmergencyPass,
  isAppLeased,
  isEmergencyPassActive,
  issueAppLease,
  revokeAppLease,
} from '../lib/external_agents.js'

const DECLARED = [
  { app: 'md.obsidian', label: 'Obsidian', role: 'work' },
  { app: 'tv.danmaku.bili', label: 'B站', role: 'fun' },
  { app: 'com.phone.emergency', label: '紧急电话', role: 'emergency' },
]

test('Tier classification keeps Tier 0 baseline & emergency never blocked, Tier 3 blacklisted', () => {
  assert.equal(classifyAppTier('com.android.calculator', DECLARED).tier, TIER_EXEMPT)
  assert.equal(classifyAppTier('com.phone.emergency', DECLARED).tier, TIER_EXEMPT)
  assert.equal(classifyAppTier('md.obsidian', DECLARED).tier, TIER_CONTEXTUAL)
  assert.equal(classifyAppTier('com.miui.gallery', DECLARED).tier, TIER_CONTEXTUAL)
  assert.equal(classifyAppTier('com.android.chrome', DECLARED).tier, TIER_AMBIGUOUS)
  assert.equal(classifyAppTier('tv.danmaku.bili', DECLARED).tier, TIER_BLACKLIST)
})

test('Split-screen with visible work window silently grants Tier 0/1 contextual exemption', () => {
  const now = 1_700_000_000_000
  const splitRes = evaluateContextualExemption({}, {
    pkg: 'com.android.chrome',
    splitScreenVisible: true,
    workVisible: true,
    declaredApps: DECLARED,
  }, now)
  assert.equal(splitRes.exempt, true)
  assert.equal(splitRes.reason, 'split-screen-work')

  // Split-screen cannot exempt a Tier 3 entertainment/game app
  const funSplit = evaluateContextualExemption({}, {
    pkg: 'tv.danmaku.bili',
    splitScreenVisible: true,
    workVisible: true,
    declaredApps: DECLARED,
  }, now)
  assert.equal(funSplit.exempt, false)
  assert.equal(funSplit.reason, 'not-tier1')
})

test('Tier 1 contextual exemption allows screenshot/share/quick-return <=90s and revokes on >90s or feed', () => {
  const t0 = 1_700_000_000_000
  const byShare = evaluateContextualExemption({}, {
    pkg: 'com.tencent.mm',
    recentShareIntent: true,
    declaredApps: DECLARED,
  }, t0)
  assert.equal(byShare.exempt, true)
  assert.equal(byShare.reason, 'share-intent')

  const within90s = evaluateContextualExemption({}, {
    pkg: 'com.tencent.mm',
    leftWorkAt: t0 - 60_000,
    declaredApps: DECLARED,
  }, t0)
  assert.equal(within90s.exempt, true)
  assert.equal(within90s.reason, 'quick-return')

  const after90s = evaluateContextualExemption(within90s.state, {
    pkg: 'com.tencent.mm',
    declaredApps: DECLARED,
  }, t0 + 91_000)
  assert.equal(after90s.exempt, false)
  assert.equal(after90s.reclaimed, true)

  const feedReclaim = evaluateContextualExemption(byShare.state, {
    pkg: 'com.tencent.mm',
    screenText: '朋友圈 推荐视频 点赞 128',
    declaredApps: DECLARED,
  }, t0 + 5_000)
  assert.equal(feedReclaim.exempt, false)
  assert.equal(feedReclaim.reclaimed, true)
  assert.equal(feedReclaim.reason, 'leisure-feed-detected')
})

test('degraded_escape fires on >120s gallery/files dwell without exemption and on >=4 distinct non-Tier-0 hops in 180s', () => {
  const t0 = 1_700_000_000_000
  const s1 = detectDegradedEscape({}, { phone: { app: 'com.miui.gallery' }, declaredApps: DECLARED, now: t0, contextExempt: false })
  assert.equal(s1.degraded, false)

  // Still exempt while Tier 1 context exempt holds
  const sExempt = detectDegradedEscape(s1.nextState, { phone: { app: 'com.miui.gallery' }, declaredApps: DECLARED, now: t0 + 130_000, contextExempt: true })
  assert.equal(sExempt.degraded, false)

  // Unexempt gallery/files dwell > 120s triggers degraded_escape
  const sDwell = detectDegradedEscape(s1.nextState, { phone: { app: 'com.miui.gallery' }, declaredApps: DECLARED, now: t0 + 125_000, contextExempt: false })
  assert.equal(sDwell.degraded, true)
  assert.equal(sDwell.reason, 'gallery-or-files-dwell')

  // Rapid hopping across >=4 distinct non-Tier-0 apps within 180s
  const hops = detectDegradedEscape({}, {
    phone: {
      app: 'com.android.chrome',
      switches: {
        recent: [
          { app: 'com.android.chrome', agoMs: 20_000 },
          { app: 'com.miui.gallery', agoMs: 50_000 },
          { app: 'com.tencent.mm', agoMs: 90_000 },
          { app: 'tv.danmaku.bili', agoMs: 140_000 },
          { app: 'com.android.calculator', agoMs: 10_000 },
        ],
      },
    },
    declaredApps: DECLARED,
    now: t0,
  })
  assert.equal(hops.degraded, true)
  assert.equal(hops.reason, 'rapid-hopping')
  assert.equal(hops.distinctApps, 4)
})

test('Scoped App Lease + 3m Emergency Pass expiry triggers mandatory pull-back + daily cap', () => {
  const t0 = 1_700_000_000_000
  const issued = issueAppLease(emptyExternalState(), { agentId: 'gemini_live', topic: '资料卡', ttlMs: 10 * 60_000, now: t0 })
  assert.equal(issued.ok, true)
  assert.equal(isAppLeased(issued.state, 'com.google.android.apps.bard', t0 + 1000), true)
  assert.equal(activeAppLease(issued.state, t0 + 11 * 60_000), null)
  const rev = revokeAppLease(issued.state, 'done')
  assert.equal(rev.hadLease, true)

  let st = emptyExternalState()
  const ep = grantEmergencyPass(st, { reason: '取快递验证码', day: '2026-11-14', now: t0 })
  assert.equal(ep.ok, true)
  assert.equal(ep.until, t0 + EMERGENCY_PASS_MS)
  assert.equal(isEmergencyPassActive(ep.state, t0 + EMERGENCY_PASS_MS - 1000), true)

  // Before expiry: no pull-back
  const pre = checkEmergencyPassPullBack(ep.state, { now: t0 + EMERGENCY_PASS_MS - 1000, pkg: 'com.tencent.mm' })
  assert.equal(pre.pullBack, false)

  // At/after 3m expiry: pull-back triggers once
  const expired = checkEmergencyPassPullBack(ep.state, { now: t0 + EMERGENCY_PASS_MS, pkg: 'com.tencent.mm' })
  assert.equal(expired.pullBack, true)
  assert.equal(expired.reason, 'emergency-pass-expired')
  const secondPoll = checkEmergencyPassPullBack(expired.state, { now: t0 + EMERGENCY_PASS_MS + 5000, pkg: 'com.tencent.mm' })
  assert.equal(secondPoll.pullBack, false)

  // Daily cap enforced
  st = expired.state
  st = grantEmergencyPass(st, { reason: '第2次', day: '2026-11-14', now: t0 + 10_000 }).state
  st = grantEmergencyPass(st, { reason: '第3次', day: '2026-11-14', now: t0 + 20_000 }).state
  const fourth = grantEmergencyPass(st, { reason: '第4次', day: '2026-11-14', now: t0 + 30_000 })
  assert.equal(fourth.ok, false)
  assert.equal(fourth.error, 'daily-cap-reached')
  assert.equal(fourth.cap, DAILY_EMERGENCY_PASS_CAP)
})

test('External capture failure falls back to <=30s recap prompt/recorder', () => {
  const awaiting = evaluateExternalRecapFallback({ captureFailed: true, spokenOrTextRecap: '' })
  assert.equal(awaiting.ok, false)
  assert.equal(awaiting.mode, 'awaiting_30s_recap')
  assert.equal(awaiting.maxSeconds, 30)

  const spoken = evaluateExternalRecapFallback({
    captureFailed: true,
    spokenOrTextRecap: '完成了图形推理 15 题的错因分类，卡点在数量规律变体，下一步做 1 组 3 题抽检。',
    durationSec: 45,
  })
  assert.equal(spoken.ok, true)
  assert.equal(spoken.mode, 'spoken_fallback_30s')
  assert.equal(spoken.durationSec, 30)
  assert.equal(spoken.degraded, true)
})

test('15-20m elastic buffer triggers scheduling_failure -> micro_landing_3m and 3x/day enters low_power_floor', () => {
  const t0 = 1_700_000_000_000
  const inBuffer = evaluateElasticBuffer({ plannedAt: t0, startedAt: 0, bufferMin: 20, now: t0 + 15 * 60_000 })
  assert.equal(inBuffer.exceeded, false)

  const overdue = evaluateElasticBuffer({ plannedAt: t0, startedAt: 0, bufferMin: 20, now: t0 + 20 * 60_000 })
  assert.equal(overdue.exceeded, true)
  assert.equal(overdue.classification, 'scheduling_failure')
  assert.equal(overdue.fallbackBout.protocolId, 'micro_landing_3m')
  assert.equal(overdue.fallbackBout.minutes, 3)

  const floorBefore = evaluateDailySchedulingFloor({ failureCountToday: 2 })
  assert.equal(floorBefore.lowPowerFloor, false)
  assert.equal(floorBefore.mode, 'normal')

  const floorTripped = evaluateDailySchedulingFloor({ failureCountToday: DAILY_SCHEDULING_FAILURE_FLOOR_CAP })
  assert.equal(floorTripped.lowPowerFloor, true)
  assert.equal(floorTripped.mode, 'low_power_floor')
  assert.equal(floorTripped.preserveOnly, 'micro_landing_3m')
  assert.equal(floorTripped.fallbackBout.protocolId, 'micro_landing_3m')
})
