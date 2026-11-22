// dsh-go-companion · 强度表与每天硬干预总上限（2026-11-19 · H1）
//
// 三档强度：gentle（温和）/ standard（标准，默认 = 现值）/ strict（严格）。
// 规则：
//   1. 改严立即生效，清空待生效的改松；
//   2. 改松不当天生效，挂进 pendingIntensity，到次日（dayString 跨天）自动落地；
//   3. 姐妹工具只能改严，不能改松；
//   4. 每天硬干预（锁 + 接管 + 自动拉 Gemini）达上限后，只保留深夜锁和契约逾期锁，其余改为只提醒，
//      并在晚报里写明「今天硬干预已经用满」。

import { dayString } from './daily.js'
import { DEFAULT_TZ, startOfDay } from './tz.js'

export const INTENSITY_TIERS = Object.freeze(['gentle', 'standard', 'strict'])
export const DEFAULT_INTENSITY = 'standard'
export const INTENSITY_RANK = Object.freeze({ gentle: 0, standard: 1, strict: 2 })

export const INTENSITY_TABLE = Object.freeze({
  gentle: Object.freeze({
    partnerMinGapMin: 15,
    maxBallsPerHour: 3,
    boutGapPushMin: 10,
    boutGapAutoOpenMin: 20,
    switchWindowMin: 10,
    switchDice: 4,
    switchLock: 7,
    funQuotaMin: 30,
    gameQuotaMin: 60,
    takeoverMaxMin: 30,
    takeoverMaxPerDay: 2,
    geminiAutoMaxPerDay: 4,
    hardInterventionDailyCap: 8,
    emergencyDisarmDailyCap: 3,
  }),
  standard: Object.freeze({
    partnerMinGapMin: 8,
    maxBallsPerHour: 4,
    boutGapPushMin: 5,
    boutGapAutoOpenMin: 10,
    switchWindowMin: 10,
    switchDice: 3,
    switchLock: 5,
    funQuotaMin: 0,
    gameQuotaMin: 60,
    takeoverMaxMin: 45,
    takeoverMaxPerDay: 3,
    geminiAutoMaxPerDay: 6,
    hardInterventionDailyCap: 12,
    emergencyDisarmDailyCap: 3,
  }),
  strict: Object.freeze({
    partnerMinGapMin: 5,
    maxBallsPerHour: 6,
    boutGapPushMin: 3,
    boutGapAutoOpenMin: 6,
    switchWindowMin: 15,
    switchDice: 3,
    switchLock: 4,
    funQuotaMin: 0,
    gameQuotaMin: 45,
    takeoverMaxMin: 60,
    takeoverMaxPerDay: 4,
    geminiAutoMaxPerDay: 8,
    hardInterventionDailyCap: 20,
    emergencyDisarmDailyCap: 2,
  }),
})

export function isValidIntensity(raw) {
  return typeof raw === 'string' && Object.prototype.hasOwnProperty.call(INTENSITY_TABLE, raw.trim())
}

export function normalizeIntensity(raw, fallback = DEFAULT_INTENSITY) {
  const k = typeof raw === 'string' ? raw.trim() : ''
  return Object.prototype.hasOwnProperty.call(INTENSITY_TABLE, k) ? k : fallback
}

export function thresholdsForIntensity(tier = DEFAULT_INTENSITY) {
  return INTENSITY_TABLE[normalizeIntensity(tier)]
}

function shiftYmd(ymdStr, deltaDays = 1) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(ymdStr || ''))
  if (!m) return ''
  const dt = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + deltaDays))
  return dt.toISOString().slice(0, 10)
}

export function cleanPendingIntensity(raw) {
  if (!raw || typeof raw !== 'object') return null
  if (!isValidIntensity(raw.tier)) return null
  const tier = normalizeIntensity(raw.tier)
  const effectiveDay = String(raw.effectiveDay || '').trim()
  const requestedDay = String(raw.requestedDay || '').trim()
  const effectiveAt = Math.max(0, Number(raw.effectiveAt) || 0)
  if (!effectiveDay && !effectiveAt) return null
  return { tier, requestedDay, effectiveDay, effectiveAt }
}

/** 跨天检查：挂着的改松到点后自动生效。 */
export function resolveIntensityState(configOrState, { now = Date.now(), tz = DEFAULT_TZ } = {}) {
  const s = configOrState && typeof configOrState === 'object' ? configOrState : {}
  const cur = normalizeIntensity(s.intensity)
  const pending = cleanPendingIntensity(s.pendingIntensity)
  if (!pending) {
    return { intensity: cur, pendingIntensity: null, promoted: false, thresholds: thresholdsForIntensity(cur) }
  }
  const today = dayString(now, tz)
  const dueByDay = Boolean(pending.effectiveDay && today && today >= pending.effectiveDay)
  const dueByTs = Boolean(pending.effectiveAt > 0 && now >= pending.effectiveAt)
  if (dueByDay || dueByTs) {
    return {
      intensity: pending.tier,
      pendingIntensity: null,
      promoted: pending.tier !== cur,
      thresholds: thresholdsForIntensity(pending.tier),
    }
  }
  return { intensity: cur, pendingIntensity: pending, promoted: false, thresholds: thresholdsForIntensity(cur) }
}

/** 切换强度档：改严立即生效，改松排到次日；allowRelax=false 时禁止改松（姐妹工具用）。 */
export function applyIntensityChange(configOrState, targetRaw, { now = Date.now(), tz = DEFAULT_TZ, allowRelax = true } = {}) {
  const resolved = resolveIntensityState(configOrState, { now, tz })
  if (!isValidIntensity(targetRaw)) {
    return {
      ok: false,
      error: 'invalid-intensity',
      intensity: resolved.intensity,
      pendingIntensity: resolved.pendingIntensity,
      thresholds: resolved.thresholds,
      applied: 'rejected',
    }
  }
  const cur = resolved.intensity
  const target = normalizeIntensity(targetRaw)
  const curRank = INTENSITY_RANK[cur]
  const targetRank = INTENSITY_RANK[target]

  if (targetRank === curRank) {
    return {
      ok: true,
      intensity: cur,
      pendingIntensity: null,
      thresholds: thresholdsForIntensity(cur),
      applied: 'unchanged',
    }
  }

  if (targetRank > curRank) {
    return {
      ok: true,
      intensity: target,
      pendingIntensity: null,
      thresholds: thresholdsForIntensity(target),
      applied: 'immediate',
    }
  }

  // 改松
  if (!allowRelax) {
    return {
      ok: false,
      error: 'relax-forbidden',
      intensity: cur,
      pendingIntensity: resolved.pendingIntensity,
      thresholds: resolved.thresholds,
      applied: 'rejected',
    }
  }

  const today = dayString(now, tz)
  const effectiveDay = shiftYmd(today, 1)
  const effectiveAt = startOfDay(now, tz) + 86_400_000
  const pendingIntensity = { tier: target, requestedDay: today, effectiveDay, effectiveAt }
  return {
    ok: true,
    intensity: cur,
    pendingIntensity,
    thresholds: resolved.thresholds,
    applied: 'pending',
  }
}

// ---- 每天硬干预总上限（锁 + 接管 + 自动拉 Gemini） ----

export function normalizeHardInterventions(raw, day) {
  const d = String(day || '')
  const s = raw && typeof raw === 'object' ? raw : null
  if (!s || String(s.day || '') !== d) {
    return { v: 1, day: d, count: 0, items: [] }
  }
  const items = Array.isArray(s.items) ? s.items.filter(Boolean).slice(-60) : []
  return {
    v: 1,
    day: d,
    count: Math.max(items.length, Math.max(0, Math.round(Number(s.count) || 0))),
    items,
  }
}

export function hardInterventionStatus(rawStore, { day, tier = DEFAULT_INTENSITY, cap } = {}) {
  const s = normalizeHardInterventions(rawStore, day)
  const resolvedCap = Number.isFinite(Number(cap)) && Number(cap) > 0
    ? Math.round(Number(cap))
    : thresholdsForIntensity(tier).hardInterventionDailyCap
  return {
    day: s.day,
    count: s.count,
    cap: resolvedCap,
    capped: s.count >= resolvedCap,
    left: Math.max(0, resolvedCap - s.count),
  }
}

/** 记录一次硬干预；支持 dedupeKey（同一事件或同一冷却窗不重复扣减）。 */
export function recordHardIntervention(rawStore, {
  kind = 'lock',
  source = '',
  app = '',
  why = '',
  day = '',
  now = Date.now(),
  tier = DEFAULT_INTENSITY,
  cap,
  dedupeKey = '',
  dedupeWindowMs = 10 * 60_000,
} = {}) {
  const s = normalizeHardInterventions(rawStore, day)
  const resolvedCap = Number.isFinite(Number(cap)) && Number(cap) > 0
    ? Math.round(Number(cap))
    : thresholdsForIntensity(tier).hardInterventionDailyCap

  if (dedupeKey) {
    const recentDup = s.items.some((it) => it.dedupeKey === dedupeKey && now - (Number(it.at) || 0) < dedupeWindowMs)
    if (recentDup) {
      return {
        state: s,
        recorded: false,
        duplicate: true,
        count: s.count,
        cap: resolvedCap,
        capped: s.count >= resolvedCap,
        left: Math.max(0, resolvedCap - s.count),
      }
    }
  }

  const item = {
    kind: String(kind || 'lock'),
    source: String(source || '').slice(0, 40),
    app: String(app || '').slice(0, 120),
    why: String(why || '').slice(0, 80),
    at: now,
    ...(dedupeKey ? { dedupeKey } : {}),
  }
  const items = [...s.items, item].slice(-60)
  const next = { v: 1, day: s.day, count: s.count + 1, items }
  return {
    state: next,
    recorded: true,
    duplicate: false,
    count: next.count,
    cap: resolvedCap,
    capped: next.count >= resolvedCap,
    left: Math.max(0, resolvedCap - next.count),
  }
}

/** 结合 14 天自律等级的生效强度：自律达标往松挪一档（绝不超过一档、不低于 gentle），不达标立即恢复。 */
export function resolveEffectiveIntensity(configOrState, { autonomy = null, now = Date.now(), tz = DEFAULT_TZ } = {}) {
  const base = resolveIntensityState(configOrState, { now, tz })
  const baseRank = INTENSITY_RANK[base.intensity]
  const unlocked = Boolean(autonomy?.unlockedRelaxation)
  const effectiveRank = unlocked ? Math.max(0, baseRank - 1) : baseRank
  const effectiveIntensity = INTENSITY_TIERS[effectiveRank] || base.intensity
  const autonomyShift = effectiveRank - baseRank
  return {
    ...base,
    effectiveIntensity,
    autonomyShift,
    thresholds: thresholdsForIntensity(effectiveIntensity),
  }
}

/** 上限用满后，只保留深夜锁和契约逾期锁。 */
export function isLockExemptFromDailyCap({ wallKind = null, contractOverdue = false } = {}) {
  return wallKind === 'night' || Boolean(contractOverdue)
}
