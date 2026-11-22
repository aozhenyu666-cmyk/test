import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DEFAULT_GEMINI_AUTO_CAP,
  emptyGeminiGate,
  evaluateProactiveGeminiTriggers,
  GEMINI_AUTO_COOLDOWN_MS,
  GEMINI_POST_SESSION_COOLDOWN_MS,
  GEMINI_URGENT_COOLDOWN_MS,
  markGeminiSessionEnded,
  requestGemini,
  snoozeGeminiAuto,
} from '../lib/gemini_gate.js'

const cst = (day, hhmm) => Date.parse(`${day}T${hhmm}:00+08:00`)

test('backward compatibility: tiered cooldown, dailyCap=10 default with override, manual unlimited', () => {
  assert.equal(GEMINI_AUTO_COOLDOWN_MS, 20 * 60_000)
  assert.equal(GEMINI_URGENT_COOLDOWN_MS, 10 * 60_000)
  assert.equal(GEMINI_POST_SESSION_COOLDOWN_MS, 10 * 60_000)
  assert.equal(DEFAULT_GEMINI_AUTO_CAP, 10)

  const t0 = cst('2026-11-22', '10:00')
  let g = emptyGeminiGate('2026-11-22')

  // First auto request allowed
  const r1 = requestGemini(g, { source: 'idle-fun-drift', now: t0, day: '2026-11-22', tz: 'Asia/Shanghai' })
  assert.equal(r1.allowed, true)
  assert.equal(r1.cap, 10)
  assert.equal(r1.left, 9)
  g = r1.state

  // Regular auto blocked at 12m (<20m)
  const regularSoon = requestGemini(g, { source: 'drift', now: t0 + 12 * 60_000, day: '2026-11-22' })
  assert.equal(regularSoon.allowed, false)
  assert.equal(regularSoon.reason, 'cooldown')

  // Urgent lock-bounce allowed at 12m (>=10m urgent cooldown!)
  const urgentOk = requestGemini(g, { source: 'lock-bounce', now: t0 + 12 * 60_000, day: '2026-11-22' })
  assert.equal(urgentOk.allowed, true)
  assert.equal(urgentOk.urgent, true)
  g = urgentOk.state

  // Urgent lock-bounce blocked at +5m (<10m urgent cooldown)
  const urgentSoon = requestGemini(g, { source: 'lock-bounce', now: t0 + 17 * 60_000, day: '2026-11-22' })
  assert.equal(urgentSoon.allowed, false)
  assert.equal(urgentSoon.reason, 'cooldown')

  // dailyCap parameter still overrides default cap
  const capped = requestGemini(g, { source: 'drift', now: t0 + 45 * 60_000, day: '2026-11-22', dailyCap: 2 })
  assert.equal(capped.allowed, false)
  assert.equal(capped.reason, 'daily-cap-reached')
})

test('evaluateProactiveGeminiTriggers: Scenario 1 idle-fun-drift (dwell >=10m or >=3 hops/5m), excluded during lunch 12-14', () => {
  const afternoon = cst('2026-11-22', '15:10')
  const lunch = cst('2026-11-22', '12:30')
  const g0 = emptyGeminiGate('2026-11-22')

  // Lunch break 12:00-14:00 must NOT trigger idle-fun-drift
  const inLunch = evaluateProactiveGeminiTriggers(g0, {
    hasActiveBout: false,
    funDwellMin: 20,
    funSwitches5m: 4,
    front: '哔哩哔哩',
  }, { now: lunch, tz: 'Asia/Shanghai' })
  assert.equal(inLunch.triggered, false)
  assert.equal(inLunch.scenario, null)

  // Daytime non-lunch with funDwellMin >= 10 -> triggers idle-fun-drift
  const byDwell = evaluateProactiveGeminiTriggers(g0, {
    hasActiveBout: false,
    funDwellMin: 12,
    front: '抖音',
  }, { now: afternoon, tz: 'Asia/Shanghai' })
  assert.equal(byDwell.triggered, true)
  assert.equal(byDwell.scenario, 'idle-fun-drift')
  assert.equal(byDwell.forceStealScreen, false)
  assert.match(byDwell.brief, /2 分钟/)

  // Daytime non-lunch with >=3 switches in 5m -> triggers idle-fun-drift
  const bySwitches = evaluateProactiveGeminiTriggers(g0, {
    hasActiveBout: false,
    funDwellMin: 2,
    funSwitches5m: 3,
    front: '小红书',
  }, { now: afternoon, tz: 'Asia/Shanghai' })
  assert.equal(bySwitches.triggered, true)
  assert.equal(bySwitches.scenario, 'idle-fun-drift')

  // Active bout -> does NOT trigger idle-fun-drift
  const duringBout = evaluateProactiveGeminiTriggers(g0, {
    hasActiveBout: true,
    funDwellMin: 15,
    funSwitches5m: 5,
  }, { now: afternoon, tz: 'Asia/Shanghai' })
  assert.equal(duringBout.triggered, false)
})

test('evaluateProactiveGeminiTriggers: Scenario 2 lock-bounce (10m urgent cooldown) & Scenario 3 post-bout-or-evening', () => {
  const g0 = emptyGeminiGate('2026-11-22')
  const t1 = cst('2026-11-22', '16:00')

  const bounce = evaluateProactiveGeminiTriggers(g0, {
    lockBounceCount: 2,
    lastBlockedApp: '拼多多',
    lastBlockedWhy: '专注局拦截',
  }, { now: t1, tz: 'Asia/Shanghai' })
  assert.equal(bounce.triggered, true)
  assert.equal(bounce.scenario, 'lock-bounce')
  assert.equal(bounce.packet.urgent, true)
  assert.equal(bounce.packet.cooldownMinutes, 10)
  assert.match(bounce.brief, /30 秒.*卡点诊断/)

  // Post-bout without recap
  const t2 = cst('2026-11-22', '16:30')
  const postBout = evaluateProactiveGeminiTriggers(bounce.state, {
    justFinishedBout: true,
    boutRecapped: false,
  }, { now: t2, tz: 'Asia/Shanghai' })
  assert.equal(postBout.triggered, true)
  assert.equal(postBout.scenario, 'post-bout-or-evening')
  assert.match(postBout.brief, /题库卡|因果卡/)

  // Evening >= 21:30 without closeout
  const nightWindow = cst('2026-11-22', '21:35')
  const evening = evaluateProactiveGeminiTriggers(g0, {
    eveningClosed: false,
  }, { now: nightWindow, tz: 'Asia/Shanghai' })
  assert.equal(evening.triggered, true)
  assert.equal(evening.scenario, 'post-bout-or-evening')

  // Already closed -> no evening trigger
  const closed = evaluateProactiveGeminiTriggers(g0, {
    eveningClosed: true,
  }, { now: nightWindow, tz: 'Asia/Shanghai' })
  assert.equal(closed.triggered, false)
})

test('snoozeGeminiAuto(state, 30, now) defers auto triggers for 30 minutes without blocking manual mode', () => {
  const t0 = cst('2026-11-22', '14:20')
  const g0 = emptyGeminiGate('2026-11-22')
  const snoozed = snoozeGeminiAuto(g0, 30, t0)
  assert.equal(snoozed.snoozeUntil, t0 + 30 * 60_000)

  // Auto trigger blocked during snooze window (even urgent lock-bounce respects user snooze)
  const blocked = evaluateProactiveGeminiTriggers(snoozed, {
    lockBounceCount: 3,
    lastBlockedApp: 'B站',
  }, { now: t0 + 15 * 60_000, tz: 'Asia/Shanghai' })
  assert.equal(blocked.triggered, false)
  assert.equal(blocked.reason, 'snoozed')

  // Manual trigger still allowed during snooze
  const manual = requestGemini(snoozed, {
    mode: 'manual',
    source: 'bridge-card',
    topic: '手动诊断',
    now: t0 + 15 * 60_000,
    day: '2026-11-22',
  })
  assert.equal(manual.allowed, true)

  // After 30m snooze expires, auto trigger succeeds
  const afterSnooze = evaluateProactiveGeminiTriggers(snoozed, {
    lockBounceCount: 2,
    lastBlockedApp: 'B站',
  }, { now: t0 + 30 * 60_000, tz: 'Asia/Shanghai' })
  assert.equal(afterSnooze.triggered, true)
  assert.equal(afterSnooze.scenario, 'lock-bounce')

  // Post-session cooldown still respected
  const ended = markGeminiSessionEnded(afterSnooze.state, { now: t0 + 35 * 60_000, day: '2026-11-22' })
  const postSessionBlocked = evaluateProactiveGeminiTriggers(ended, {
    lockBounceCount: 4,
  }, { now: t0 + 40 * 60_000, tz: 'Asia/Shanghai' })
  assert.equal(postSessionBlocked.triggered, false)
  assert.equal(postSessionBlocked.reason, 'post-session-cooldown')
})
