// dsh-go-companion · 统一放行账（2026-11-19 · H2）
//
// 把七个分散的放行口收编成三类，全部走 requestPass(state, kind, opts)：
//   1. emergency（应急）：本地 disarm、插件 /emergency-pass、破窗 breakglass、Gemini 卡「延后 10 分钟」
//      - 共用每天次数上限（标准/温和 3 次，严格 2 次）；每次默认 10 分钟；必须写理由；
//      - 幂等同步：按 disarmId（eventId）去重，本地离线解除后补报不重复计数；
//      - breakglass 不扣信用分（保留记账与日判）。
//   2. earned（挣来的时间）：每通过一局 +15、题库答对 10 题 +5、grant_break 放风
//   3. lease（通行证）：grant_task_lease（仅双用途 App，纯游戏硬拒；默认 15 分钟上限 30；第 9 张起要理由）

import { isPureGame } from './app_resolver.js'

export const EMERGENCY_PASS_DURATION_MS = 10 * 60_000
export const DEFAULT_EMERGENCY_CAP = 3
export const DEFAULT_LEASE_MIN = 15
export const MAX_LEASE_MIN = 30
export const MIN_LEASE_MIN = 5
export const LEASE_REASON_THRESHOLD = 8

const n = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

export function emptyPassLedger(day = '') {
  return {
    v: 1,
    day: String(day || ''),
    emergency: {
      count: 0,
      until: 0,
      grantedAt: 0,
      reason: '',
      source: '',
      disarmIds: [],
      breakglassUsed: false,
      events: [],
    },
    earned: {
      count: 0,
      minutes: 0,
      boutMinutes: 0,
      bankMinutes: 0,
      breakMinutes: 0,
      breakUntil: 0,
      events: [],
    },
    lease: {
      count: 0,
      active: null,
      events: [],
    },
  }
}

export function normalizePassLedger(raw, day = '') {
  const d = String(day || '')
  const s = raw && typeof raw === 'object' ? raw : null
  if (!s || (d && String(s.day || '') !== d)) {
    return emptyPassLedger(d)
  }
  const em = s.emergency && typeof s.emergency === 'object' ? s.emergency : {}
  const ea = s.earned && typeof s.earned === 'object' ? s.earned : {}
  const le = s.lease && typeof s.lease === 'object' ? s.lease : {}
  return {
    v: 1,
    day: String(s.day || d),
    emergency: {
      count: Math.max(0, Math.round(n(em.count))),
      until: Math.max(0, n(em.until)),
      grantedAt: Math.max(0, n(em.grantedAt)),
      reason: String(em.reason || '').slice(0, 120),
      source: String(em.source || '').slice(0, 32),
      disarmIds: Array.isArray(em.disarmIds) ? [...new Set(em.disarmIds.map(String).filter(Boolean))].slice(-40) : [],
      breakglassUsed: Boolean(em.breakglassUsed),
      events: Array.isArray(em.events) ? em.events.filter(Boolean).slice(-40) : [],
    },
    earned: {
      count: Math.max(0, Math.round(n(ea.count))),
      minutes: Math.max(0, Math.round(n(ea.minutes))),
      boutMinutes: Math.max(0, Math.round(n(ea.boutMinutes))),
      bankMinutes: Math.max(0, Math.round(n(ea.bankMinutes))),
      breakMinutes: Math.max(0, Math.round(n(ea.breakMinutes))),
      breakUntil: Math.max(0, n(ea.breakUntil)),
      events: Array.isArray(ea.events) ? ea.events.filter(Boolean).slice(-60) : [],
    },
    lease: {
      count: Math.max(0, Math.round(n(le.count))),
      active: le.active && typeof le.active === 'object' ? le.active : null,
      events: Array.isArray(le.events) ? le.events.filter(Boolean).slice(-60) : [],
    },
  }
}

export function isEmergencyActive(rawState, now = Date.now()) {
  const em = rawState?.emergency
  return Boolean(em && n(em.until) > now)
}

export function revokeEmergencyPass(rawState, day = '') {
  const s = normalizePassLedger(rawState, day)
  return {
    ...s,
    emergency: {
      ...s.emergency,
      until: 0,
    },
  }
}

/**
 * 统一放行请求入口。
 * @param {object} rawState pass-ledger 状态
 * @param {'emergency'|'earned'|'lease'} kind
 * @param {object} opts
 * @returns {{ok:boolean, until:number, why:string, countToday:number, left:number, cap?:number, state:object, idempotent?:boolean, error?:string}}
 */
export function requestPass(rawState, kind, opts = {}) {
  const now = n(opts.now, Date.now())
  const day = String(opts.day || new Date(now).toISOString().slice(0, 10))
  const s = normalizePassLedger(rawState, day)

  if (kind === 'emergency') {
    const cap = Math.max(1, Math.min(10, Math.round(n(opts.cap, DEFAULT_EMERGENCY_CAP))))
    const source = String(opts.source || 'emergency-pass').slice(0, 32)
    const disarmId = String(opts.disarmId || opts.eventId || '').trim().slice(0, 80)
    const counted = opts.counted !== false
    const cleanReason = String(opts.reason || opts.note || '').trim().slice(0, 120)

    // 幂等校验：同一个 disarmId 重复同步绝不重复扣减次数
    if (disarmId && s.emergency.disarmIds.includes(disarmId)) {
      const existing = s.emergency.events.find((e) => e.disarmId === disarmId)
      return {
        ok: true,
        idempotent: true,
        until: existing?.until || s.emergency.until || 0,
        why: existing?.reason || cleanReason || '已同步的紧急解除',
        countToday: s.emergency.count,
        left: Math.max(0, cap - s.emergency.count),
        cap,
        source,
        state: s,
      }
    }

    if (counted && !cleanReason) {
      return {
        ok: false,
        error: 'reason-required',
        until: 0,
        why: '必须写一句理由才能紧急解除',
        countToday: s.emergency.count,
        left: Math.max(0, cap - s.emergency.count),
        cap,
        source,
        state: s,
      }
    }

    if (source === 'breakglass' && s.emergency.breakglassUsed) {
      return {
        ok: false,
        error: 'breakglass-used',
        until: 0,
        why: '今天已经破窗过一次',
        countToday: s.emergency.count,
        left: Math.max(0, cap - s.emergency.count),
        cap,
        source,
        state: s,
      }
    }

    if (counted && s.emergency.count >= cap) {
      return {
        ok: false,
        error: 'daily-cap-reached',
        until: 0,
        why: `今天紧急解除次数已用完（${s.emergency.count}/${cap}）`,
        countToday: s.emergency.count,
        left: 0,
        cap,
        source,
        state: s,
      }
    }

    const durationMs = opts.durationMs != null
      ? Math.max(60_000, Math.min(15 * 60_000, Math.round(n(opts.durationMs, EMERGENCY_PASS_DURATION_MS))))
      : EMERGENCY_PASS_DURATION_MS
    const reqUntil = n(opts.until) > now ? Math.min(now + 15 * 60_000, n(opts.until)) : (now + durationMs)
    const nextCount = counted ? s.emergency.count + 1 : s.emergency.count
    const nextDisarmIds = disarmId ? [...s.emergency.disarmIds, disarmId].slice(-40) : s.emergency.disarmIds
    const event = {
      source,
      reason: cleanReason || (counted ? '紧急解除' : 'adb 解除'),
      at: now,
      until: reqUntil,
      counted,
      ...(disarmId ? { disarmId } : {}),
    }
    const nextState = {
      ...s,
      emergency: {
        count: nextCount,
        until: Math.max(s.emergency.until, reqUntil),
        grantedAt: now,
        reason: event.reason,
        source,
        disarmIds: nextDisarmIds,
        breakglassUsed: s.emergency.breakglassUsed || source === 'breakglass',
        events: [...s.emergency.events, event].slice(-40),
      },
    }
    return {
      ok: true,
      idempotent: false,
      until: nextState.emergency.until,
      why: event.reason,
      countToday: nextCount,
      left: Math.max(0, cap - nextCount),
      cap,
      source,
      penalty: 0,
      state: nextState,
    }
  }

  if (kind === 'earned') {
    const source = String(opts.source || 'bout').slice(0, 32)
    const minutes = Math.max(1, Math.min(60, Math.round(n(opts.minutes, source === 'bank' ? 5 : source === 'break' ? 10 : 15))))
    const reason = String(opts.reason || (source === 'bank' ? '题库解锁' : source === 'break' ? '当场放风' : '通过一局验收')).slice(0, 120)
    const until = source === 'break' || source === 'bank'
      ? Math.max(n(opts.until), now + minutes * 60_000)
      : 0
    const nextCount = s.earned.count + 1
    const nextMinutes = s.earned.minutes + (source === 'break' ? 0 : minutes)
    const nextState = {
      ...s,
      earned: {
        count: nextCount,
        minutes: nextMinutes,
        boutMinutes: s.earned.boutMinutes + (source === 'bout' ? minutes : 0),
        bankMinutes: s.earned.bankMinutes + (source === 'bank' ? minutes : 0),
        breakMinutes: s.earned.breakMinutes + (source === 'break' ? minutes : 0),
        breakUntil: until > 0 ? Math.max(s.earned.breakUntil, until) : s.earned.breakUntil,
        events: [...s.earned.events, { source, minutes, reason, at: now, until }].slice(-60),
      },
    }
    return {
      ok: true,
      until,
      why: reason,
      minutes,
      countToday: nextCount,
      left: nextMinutes,
      state: nextState,
    }
  }

  if (kind === 'lease') {
    const app = String(opts.app || '').trim()
    const appLabel = String(opts.label || app)
    if (!app) {
      return { ok: false, error: 'app-required', until: 0, why: '缺少应用包名', countToday: s.lease.count, left: Math.max(0, LEASE_REASON_THRESHOLD - s.lease.count), state: s }
    }
    if (isPureGame(app, appLabel)) {
      return { ok: false, error: 'pure-game-forbidden', until: 0, why: '硬规矩拦回：纯游戏绝不可通过通行证放行', countToday: s.lease.count, left: Math.max(0, LEASE_REASON_THRESHOLD - s.lease.count), state: s }
    }
    const whyAgain = String(opts.whyAgain || '').trim()
    if (s.lease.count >= LEASE_REASON_THRESHOLD && whyAgain.length < 4) {
      return {
        ok: false,
        error: 'why-again-required',
        until: 0,
        why: `今天已经批了 ${s.lease.count} 张通行证：第 ${LEASE_REASON_THRESHOLD + 1} 张起要他先说清理由（填 whyAgain）`,
        countToday: s.lease.count,
        left: 0,
        state: s,
      }
    }
    const mins = Math.max(MIN_LEASE_MIN, Math.min(MAX_LEASE_MIN, Math.round(n(opts.minutes, DEFAULT_LEASE_MIN))))
    const until = now + mins * 60_000
    const goal = String(opts.goal || opts.taskGoal || '双用途工具使用').trim().slice(0, 80)
    const verifyHints = Array.isArray(opts.verifyHints) ? opts.verifyHints.map(String).slice(0, 6) : []
    const lease = {
      app,
      allow: [app],
      until,
      minutes: mins,
      goal,
      verifyHints,
      by: String(opts.by || 'strategist'),
      issuedAt: now,
    }
    const nextCount = s.lease.count + 1
    const nextState = {
      ...s,
      lease: {
        count: nextCount,
        active: lease,
        events: [...s.lease.events, { app, minutes: mins, goal, at: now, until, whyAgain: whyAgain ? whyAgain.slice(0, 80) : undefined }].slice(-60),
      },
    }
    return {
      ok: true,
      until,
      why: goal,
      minutes: mins,
      countToday: nextCount,
      left: Math.max(0, LEASE_REASON_THRESHOLD - nextCount),
      lease,
      state: nextState,
    }
  }

  return { ok: false, error: 'unknown-pass-kind', until: 0, why: `未知放行类别: ${kind}`, countToday: 0, left: 0, state: s }
}

export function passLedgerSummary(rawState, { day = '', cap = DEFAULT_EMERGENCY_CAP, now = Date.now() } = {}) {
  const s = normalizePassLedger(rawState, day)
  const resolvedCap = Math.max(1, Math.round(n(cap, DEFAULT_EMERGENCY_CAP)))
  const emActive = isEmergencyActive(s, now)
  const leaseActive = Boolean(s.lease.active && n(s.lease.active.until) > now)
  return {
    day: s.day,
    emergency: {
      used: s.emergency.count,
      cap: resolvedCap,
      left: Math.max(0, resolvedCap - s.emergency.count),
      active: emActive,
      until: emActive ? s.emergency.until : 0,
      reason: emActive ? s.emergency.reason : '',
      source: emActive ? s.emergency.source : '',
      disarmIds: s.emergency.disarmIds,
      breakglassUsed: s.emergency.breakglassUsed,
    },
    earned: {
      count: s.earned.count,
      minutes: s.earned.minutes,
      boutMinutes: s.earned.boutMinutes,
      bankMinutes: s.earned.bankMinutes,
      breakMinutes: s.earned.breakMinutes,
      breakActive: n(s.earned.breakUntil) > now,
      breakUntil: n(s.earned.breakUntil) > now ? s.earned.breakUntil : 0,
    },
    lease: {
      count: s.lease.count,
      active: leaseActive,
      until: leaseActive ? s.lease.active.until : 0,
      app: leaseActive ? s.lease.active.app : '',
    },
  }
}
