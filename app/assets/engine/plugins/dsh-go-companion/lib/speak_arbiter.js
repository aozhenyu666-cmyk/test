// dsh-go-companion · 发球仲裁（2026-11-19 · H3）
//
// 把 planRoute 里 14+ 分支的 if-else 改成「先收集所有候选 → 按 5 级优先级表选出一球」。
//   P1 安全类（night / takeover）：不受总节流限制，持续绕过沉默阶梯和敷衍冷处理
//   P2 硬时间点（checkpoint / deadline / morning-gate / review / task-due / busy-done / apply-window / handoff）：
//      不受总节流限制，只在硬时间点那一刻绕过沉默阶梯一次
//   P3 推进（bout-gap / auto-open / plan-draft / patrol / radar-lock）：受总节流（radar-lock 跟着锁走不另占名额）
//   P4 连麦相关（gemini-recap / gemini-left / gemini-checkpoint / gemini-voice-check）：连麦中只允许这一组
//   P5 陪伴（restless / notes-chase / fill / dice / morning / observe-override / companion / restart-takeover）：受总节流

export const SPEAK_PRIORITY = Object.freeze({
  SAFETY: 1,
  HARD_TIMEPOINT: 2,
  DRIVE: 3,
  GEMINI: 4,
  COMPANION: 5,
})

const P1_REASONS = new Set(['night', 'takeover', 'drive:takeover'])
const P2_REASONS = new Set([
  'checkpoint', 'drive:checkpoint', 'deadline', 'drive:deadline',
  'morning-gate', 'review', 'task-due', 'busy-done', 'apply-window', 'handoff',
])
const P3_REASONS = new Set(['drive', 'drive:bout-gap', 'drive:auto-open', 'bout-gap', 'auto-open', 'plan-draft', 'patrol', 'radar-lock'])
const P4_REASONS = new Set(['gemini-recap', 'gemini-left', 'gemini-checkpoint', 'gemini-voice-check'])

const n = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

export function priorityOfCandidate(candidate = {}) {
  if (Number.isInteger(candidate.priority) && candidate.priority >= 1 && candidate.priority <= 5) {
    return candidate.priority
  }
  const r = String(candidate.reason || '')
  const dk = String(candidate.driveKind || candidate.note?.kind || '')
  if (P1_REASONS.has(r) || (r === 'drive' && dk === 'takeover')) return 1
  if (P2_REASONS.has(r) || (r === 'drive' && (dk === 'checkpoint' || dk === 'deadline'))) return 2
  if (r.startsWith('radar') && candidate.withLock) return 3
  if (P3_REASONS.has(r)) return 3
  if (P4_REASONS.has(r)) return 4
  return 5
}

/**
 * 收集候选并按优先级与总节流选出至多一球。
 * @param {object} input
 * @param {Array<object>} input.candidates 候选球 [{ reason, role, priority?, withLock?, timepointKey?, ... }]
 * @param {number} [input.now]
 * @param {boolean} [input.canTalk] 是否允许开口（非 muted/quiet/disabled）
 * @param {boolean} [input.inGemini] 是否正在 Gemini 连麦前台
 * @param {boolean} [input.emergencyActive] 是否在紧急解除 / 紧急通行期间（I4：不发 P2–P5）
 * @param {boolean} [input.inFlight] 是否有球正在生成
 * @param {boolean} [input.inConversation] 是否正在聊天中
 * @param {{stage?:string}} [input.silence] 沉默阶梯状态
 * @param {boolean} [input.inWithdrawal] 鱼丸是否处于敷衍冷静撤回期
 * @param {boolean} [input.dualOn] 是否双人格
 * @param {number} [input.sentLastHour] 最近一小时已发出主动球数
 * @param {number} [input.maxBallsPerHour] 强度表每小时上限（默认 4）
 * @param {number} [input.lastBallAt] 上一球发出时间戳
 * @param {number} [input.minGapMs] 最小间隔（默认 8 分钟）
 * @param {Array<string>} [input.bypassedTimepoints] 已经绕过过一次的硬时间点 key
 */
export function arbitrateSpeak(input = {}) {
  const now = n(input.now, Date.now())
  const canTalk = input.canTalk !== false
  const inGemini = Boolean(input.inGemini)
  const emergencyActive = Boolean(input.emergencyActive)
  const inFlight = Boolean(input.inFlight)
  const inConversation = Boolean(input.inConversation)
  const silenceStage = String(input.silence?.stage || 'none')
  const inWithdrawal = Boolean(input.inWithdrawal)
  const dualOn = input.dualOn !== false
  const sentLastHour = Math.max(0, Math.round(n(input.sentLastHour)))
  const maxBallsPerHour = Math.max(1, Math.round(n(input.maxBallsPerHour, 4)))
  const lastBallAt = Math.max(0, n(input.lastBallAt))
  const minGapMs = Math.max(0, n(input.minGapMs, 8 * 60_000))
  const bypassed = new Set(Array.isArray(input.bypassedTimepoints) ? input.bypassedTimepoints.map(String) : [])

  const rawCandidates = Array.isArray(input.candidates) ? input.candidates.filter(Boolean) : []
  const normalized = rawCandidates.map((c, idx) => ({
    ...c,
    priority: priorityOfCandidate(c),
    order: idx,
  }))
  // 按优先级升序，同级保持收集先后顺序
  normalized.sort((a, b) => a.priority - b.priority || a.order - b.order)

  if (!canTalk || inFlight || inConversation) {
    return {
      picked: null,
      candidates: normalized.map((c) => ({ reason: c.reason, priority: c.priority, role: c.role || 'companion' })),
      throttled: false,
      skipReason: !canTalk ? 'cannot-talk' : (inFlight ? 'in-flight' : 'in-conversation'),
    }
  }

  let throttled = false
  let skipReason = ''

  for (const c of normalized) {
    const p = c.priority

    // I4：紧急解除期间不发第 2–5 级的球
    if (emergencyActive && p >= 2) {
      if (!skipReason) skipReason = 'emergency-active'
      continue
    }

    // 连麦中只允许第 4 级（且只允许 gemini-voice-check / gemini-checkpoint）
    if (inGemini && p !== 4) {
      if (!skipReason) skipReason = 'in-gemini'
      continue
    }

    // 沉默阶梯 & 敷衍冷处理：只有 P1 持续绕开，P2 在硬时间点那一刻绕开一次
    const isWithdrawnCompanion = inWithdrawal && dualOn && (c.role || 'companion') === 'companion'
    const blockedBySilence = silenceStage === 'stop' || silenceStage === 'wait'
    const withLockBypass = Boolean(c.withLock && p === 3)

    if ((blockedBySilence || isWithdrawnCompanion) && !withLockBypass) {
      if (p === 1) {
        // P1 始终绕过
      } else if (p === 2) {
        const tpKey = String(c.timepointKey || c.reason || '')
        if (tpKey && bypassed.has(tpKey)) {
          if (!skipReason) skipReason = blockedBySilence ? 'silence-' + silenceStage : 'withdrawal'
          continue
        }
      } else {
        if (!skipReason) skipReason = blockedBySilence ? 'silence-' + silenceStage : 'withdrawal'
        continue
      }
    }

    // 总节流：P1 和 P2 不受限制；雷达跟着锁走（withLock）不另占名额
    const exemptFromThrottle = p <= 2 || withLockBypass || c.bypassThrottle === true
    if (!exemptFromThrottle) {
      if (sentLastHour >= maxBallsPerHour) {
        throttled = true
        if (!skipReason) skipReason = 'hourly-cap'
        continue
      }
      if (minGapMs > 0 && lastBallAt > 0 && now - lastBallAt < minGapMs) {
        throttled = true
        if (!skipReason) skipReason = 'min-gap'
        continue
      }
    }

    return {
      picked: c,
      candidates: normalized.map((x) => ({ reason: x.reason, priority: x.priority, role: x.role || 'companion' })),
      throttled: false,
      skipReason: '',
    }
  }

  return {
    picked: null,
    candidates: normalized.map((c) => ({ reason: c.reason, priority: c.priority, role: c.role || 'companion' })),
    throttled,
    skipReason: skipReason || (normalized.length ? 'filtered' : 'no-candidates'),
  }
}
