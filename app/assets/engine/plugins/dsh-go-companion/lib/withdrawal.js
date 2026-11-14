// dsh-go-companion · 反敷衍冷处理状态机（M1.3 withdrawal 状态）
//
// 连续 2 次 brief / evasive / stock 回复，且期间未切换至目标 App 或产生实质行动：
//   - 写入 personaCooldownUntil = now + 45 分钟
//   - 鱼丸停止哄人/催促/主动发球，切换到 withdrawal（极简冷淡退场）
// 解除条件（任一满足立即清零）：
//   - 到达冷却时间上限（now >= personaCooldownUntil）
//   - 手机切换进入目标应用（work / 局内 allow/need App）或出现有效实质交互 / 开局

export const WITHDRAWAL_STREAK = 2
export const WITHDRAWAL_COOLDOWN_MS = 45 * 60_000
export const PERFUNCTORY_VERDICTS = new Set(['brief', 'evasive', 'stock'])
export const SUBSTANTIVE_VERDICTS = new Set(['engaged', 'pushback'])

export function emptyWithdrawalState() {
  return {
    v: 1,
    streak: 0,
    lastVerdictAt: 0,
    personaCooldownUntil: 0,
    triggeredAt: 0,
    releasedAt: 0,
    releaseReason: '',
  }
}

export function cleanWithdrawalState(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  return {
    v: 1,
    streak: Math.max(0, Math.round(Number(s.streak) || 0)),
    lastVerdictAt: Math.max(0, Number(s.lastVerdictAt) || 0),
    personaCooldownUntil: Math.max(0, Number(s.personaCooldownUntil) || 0),
    triggeredAt: Math.max(0, Number(s.triggeredAt) || 0),
    releasedAt: Math.max(0, Number(s.releasedAt) || 0),
    releaseReason: typeof s.releaseReason === 'string' ? s.releaseReason.slice(0, 60) : '',
  }
}

/** 当前是否处于 withdrawal 冷处理冷却期。 */
export function isInWithdrawal(rawState, now = Date.now()) {
  const s = cleanWithdrawalState(rawState)
  return s.personaCooldownUntil > Number(now)
}

/**
 * 判球结果写入：连续 2 次 brief/evasive/stock 且没有使用目标 App -> 进 45 分钟 withdrawal。
 * @returns {{state:object, entered:boolean, released:boolean, reason:string}}
 */
export function recordReplyOutcome(rawState, { verdict = '', hadTargetAppUse = false, now = Date.now(), cooldownMs = WITHDRAWAL_COOLDOWN_MS } = {}) {
  const cur = cleanWithdrawalState(rawState)
  const at = Number(now) || Date.now()
  const v = String(verdict || '').toLowerCase()

  if (SUBSTANTIVE_VERDICTS.has(v)) {
    const wasActive = cur.personaCooldownUntil > at
    return {
      state: {
        ...cur,
        streak: 0,
        lastVerdictAt: at,
        personaCooldownUntil: 0,
        releasedAt: wasActive ? at : cur.releasedAt,
        releaseReason: wasActive ? 'substantive-reply' : cur.releaseReason,
      },
      entered: false,
      released: wasActive,
      reason: wasActive ? 'substantive-reply' : '',
    }
  }

  if (hadTargetAppUse) {
    const wasActive = cur.personaCooldownUntil > at
    return {
      state: {
        ...cur,
        streak: 0,
        lastVerdictAt: at,
        personaCooldownUntil: 0,
        releasedAt: wasActive ? at : cur.releasedAt,
        releaseReason: wasActive ? 'target-app-used' : cur.releaseReason,
      },
      entered: false,
      released: wasActive,
      reason: wasActive ? 'target-app-used' : '',
    }
  }

  if (!PERFUNCTORY_VERDICTS.has(v)) {
    return { state: cur, entered: false, released: false, reason: '' }
  }

  const nextStreak = cur.streak + 1
  if (nextStreak >= WITHDRAWAL_STREAK) {
    const until = at + Math.max(60_000, Number(cooldownMs) || WITHDRAWAL_COOLDOWN_MS)
    return {
      state: {
        ...cur,
        streak: nextStreak,
        lastVerdictAt: at,
        personaCooldownUntil: until,
        triggeredAt: at,
        releaseReason: '',
      },
      entered: cur.personaCooldownUntil <= at,
      released: false,
      reason: 'consecutive-perfunctory',
    }
  }

  return {
    state: { ...cur, streak: nextStreak, lastVerdictAt: at },
    entered: false,
    released: false,
    reason: '',
  }
}

/**
 * 手机切 App 或开局等真实动作时检查是否满足立刻解除条件（或超时自然解除）。
 * @returns {{state:object, released:boolean, reason:string}}
 */
export function checkWithdrawalRelease(rawState, { isTargetApp = false, actionHappened = false, now = Date.now() } = {}) {
  const cur = cleanWithdrawalState(rawState)
  const at = Number(now) || Date.now()
  if (cur.personaCooldownUntil <= 0) {
    // 非冷却期内若进入了目标 App，也顺便清掉之前的 1 次敷衍计数，避免旧敷衍跨工作时段累积。
    if (isTargetApp && cur.streak > 0) return { state: { ...cur, streak: 0 }, released: false, reason: '' }
    return { state: cur, released: false, reason: '' }
  }
  let releaseReason = ''
  if (at >= cur.personaCooldownUntil) releaseReason = 'timeout'
  else if (isTargetApp) releaseReason = 'target-app'
  else if (actionHappened) releaseReason = 'action-started'

  if (!releaseReason) return { state: cur, released: false, reason: '' }
  return {
    state: {
      ...cur,
      streak: 0,
      personaCooldownUntil: 0,
      releasedAt: at,
      releaseReason,
    },
    released: true,
    reason: releaseReason,
  }
}

/** withdrawal 期的极简冷淡退场回复：不追问、不讲道理、不情绪安抚。 */
export function withdrawalReply(names = { companion: '鱼丸' }) {
  return '行，我去忙别的了。你真动手了再叫我。'
}
