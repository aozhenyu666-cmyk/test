import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  ALL_AUDIT_FIELDS,
  V3_AUDIT_FIELDS,
  antiSycophancyRewriteHint,
  detectSycophancy,
  detectTelemetryContradiction,
  stripStrategistAudit,
  telemetryContradictionHint,
} from '../lib/strategist_audit.js'
import {
  WITHDRAWAL_COOLDOWN_MS,
  checkWithdrawalRelease,
  emptyWithdrawalState,
  filterWithdrawalOutput,
  isInWithdrawal,
  recordReplyOutcome,
} from '../lib/withdrawal.js'

test('W01 stripStrategistAudit 兼容提取 V3 四字段 JSON 并完整剥离标签', () => {
  const raw = '<strategist_audit>{"surface_claim":"没摸鱼","unverified_assumption":"自述等于事实","telemetry_contradiction":"B站45m","core_question_or_call":"交出学习截图"}</strategist_audit>\n别拿自述顶账，B站刚记录了 45 分钟。'
  const out = stripStrategistAudit(raw)
  assert.equal(out.hadAudit, true)
  assert.equal(out.cleanText, '别拿自述顶账，B站刚记录了 45 分钟。')
  for (const f of V3_AUDIT_FIELDS) {
    assert.ok(out.audit[f], `missing field ${f}`)
  }
  assert.ok(ALL_AUDIT_FIELDS.includes('surface_claim'))
})

test('W02 stripStrategistAudit 对未闭合标签残段也强行剥离防泄漏到 UI/TTS', () => {
  const raw = '先看证据再说。\n<strategist_audit>{"surface_claim":"半截JSON"'
  const out = stripStrategistAudit(raw)
  assert.equal(out.hadAudit, true)
  assert.equal(out.cleanText, '先看证据再说。')
})

test('W03 detectSycophancy 拦截「你说得对」及温柔安抚套话', () => {
  for (const s of [
    '确实你说得对，直接推进。',
    '我理解你的感受，先歇会。',
    '这确实不容易。',
    '不用太苛责自己，明天再算。',
    '没关系的，慢慢来别急。',
    '抱抱你。',
  ]) {
    const hit = detectSycophancy(s)
    assert.equal(hit.sycophantic, true, `expected sycophancy on: ${s}`)
  }
})

test('W04 detectSycophancy 对含真实反证/代价前提条件的追问放行', () => {
  const hit = detectSycophancy('我理解你的感受，但是你刚才前提里忽略了 B 站 40 分钟的代价和反证，必须先核验。')
  assert.equal(hit.sycophantic, false)
})

test('W05 detectTelemetryContradiction 利用 Block B 真实 App 时长识破「一直在学习/没摸鱼」假自述', () => {
  const hit = detectTelemetryContradiction('我今天一直在学习，根本没看手机', {
    lines: [
      '[状态] standby',
      '[局] 无进行中的局',
      '[设备] 前台:B站 12m[娱乐] 今日娱乐46m/正事10m 30m切4次',
      '[护栏] 正常监控',
    ],
  })
  assert.equal(hit.contradicted, true)
  assert.equal(hit.actualMinutes, 46)
  const hint = telemetryContradictionHint(hit)
  assert.match(hint, /46 分钟/)
  assert.match(antiSycophancyRewriteHint(hit), /SYS-002/)
})

test('W06 detectTelemetryContradiction 对诚实汇报或极短误触不误报矛盾', () => {
  const clean = detectTelemetryContradiction('刚才刷了20分钟B站我现在回去做题', { funMinutesToday: 20 })
  assert.equal(clean.contradicted, false)
  const tiny = detectTelemetryContradiction('我今天一直在学习', { funMinutesToday: 4 })
  assert.equal(tiny.contradicted, false)
})

test('W07 连续 2 次敷衍评价触发 45 分钟冷静撤回期 (withdrawal)', () => {
  const t0 = 1_700_000_000_000
  const r1 = recordReplyOutcome(emptyWithdrawalState(), { verdict: 'brief', now: t0 })
  assert.equal(r1.entered, false)
  assert.equal(isInWithdrawal(r1.state, t0 + 1000), false)

  const r2 = recordReplyOutcome(r1.state, { verdict: 'evasive', now: t0 + 5000 })
  assert.equal(r2.entered, true)
  assert.equal(r2.state.streak, 2)
  assert.equal(r2.state.personaCooldownUntil, t0 + 5000 + WITHDRAWAL_COOLDOWN_MS)
  assert.equal(isInWithdrawal(r2.state, t0 + 6000), true)
})

test('W08 withdrawal 期 filterWithdrawalOutput 拦截情绪安抚废话并保留短客观提醒', () => {
  const blocked = filterWithdrawalOutput('我理解你很累，抱抱你，没关系的我们聊聊天好不好')
  assert.match(blocked, /你真动手了再叫我/)
  const objShort = filterWithdrawalOutput('冷却还剩 32 分钟，交墙可立刻解冻。')
  assert.equal(objShort, '冷却还剩 32 分钟，交墙可立刻解冻。')
})

test('W09 withdrawal 期在真实交墙 pass (wallPassed=true) 或合法缓冲 (legalBuffer=true) 时立即解冻', () => {
  const t0 = 1_700_000_000_000
  let s = recordReplyOutcome(emptyWithdrawalState(), { verdict: 'brief', now: t0 }).state
  s = recordReplyOutcome(s, { verdict: 'stock', now: t0 + 1000 }).state
  assert.equal(isInWithdrawal(s, t0 + 2000), true)

  const byWall = checkWithdrawalRelease(s, { wallPassed: true, now: t0 + 3000 })
  assert.equal(byWall.released, true)
  assert.equal(byWall.reason, 'wall-pass')
  assert.equal(isInWithdrawal(byWall.state, t0 + 3000), false)

  const byBuffer = checkWithdrawalRelease(s, { legalBuffer: true, now: t0 + 3000 })
  assert.equal(byBuffer.released, true)
  assert.equal(byBuffer.reason, 'legal-buffer')
})

test('W10 withdrawal 期满 45 分钟超时自动恢复且打开目标工作 App 也立即解除', () => {
  const t0 = 1_700_000_000_000
  let s = recordReplyOutcome(emptyWithdrawalState(), { verdict: 'brief', now: t0 }).state
  s = recordReplyOutcome(s, { verdict: 'brief', now: t0 + 1000 }).state

  const byApp = checkWithdrawalRelease(s, { isTargetApp: true, now: t0 + 10_000 })
  assert.equal(byApp.released, true)
  assert.equal(byApp.reason, 'target-app')

  const byTimeout = checkWithdrawalRelease(s, { now: t0 + 1000 + WITHDRAWAL_COOLDOWN_MS })
  assert.equal(byTimeout.released, true)
  assert.equal(byTimeout.reason, 'timeout')
})
