// dsh-go-companion · 统一时间账（2026-11-19 · H4）
//
// 把额度（fun_base / game_base）、挣来的（earned_bout / earned_bank / break）、
// 扣罚（penalty，只扣挣来的部分，绝不扣基础额度）、用掉的（used_fun / used_game / used_dual_outside_pass）、
// 换算（converted_game_to_fun，2:1）收在同一本账。
// 中枢（/today）、分析页（/analytics）、锁页（/plan lock.quota）、晚报、姐妹上下文全部取这同一个输出。

import { FUN_QUOTA_MIN, GAME_QUOTA_MIN, GAME_TO_FUN_RATE } from './quota.js'

export const TIME_ENTRY_KINDS = Object.freeze([
  'fun_base',
  'game_base',
  'earned_bout',
  'earned_bank',
  'break',
  'penalty',
  'used_fun',
  'used_game',
  'used_dual_outside_pass',
  'converted_game_to_fun',
])

const n = (v, d = 0) => {
  const num = Number(v)
  return Number.isFinite(num) ? num : d
}
const posInt = (v) => Math.max(0, Math.round(n(v, 0)))

export function emptyTimeLedger(day = '') {
  return {
    v: 1,
    day: String(day || ''),
    entries: [],
  }
}

export function normalizeTimeLedger(raw, day = '') {
  const d = String(day || '')
  const s = raw && typeof raw === 'object' ? raw : null
  if (!s || (d && String(s.day || '') !== d)) {
    return emptyTimeLedger(d)
  }
  const entries = (Array.isArray(s.entries) ? s.entries : [])
    .filter((e) => e && TIME_ENTRY_KINDS.includes(String(e.kind || '')))
    .slice(-200)
  return {
    v: 1,
    day: String(s.day || d),
    entries,
  }
}

export function appendTimeEntry(rawState, { kind, minutes = 0, reason = '', at = Date.now(), day = '' } = {}) {
  const d = String(day || new Date(at).toISOString().slice(0, 10))
  const s = normalizeTimeLedger(rawState, d)
  if (!TIME_ENTRY_KINDS.includes(String(kind || ''))) return s
  const mins = posInt(minutes)
  if (mins <= 0 && !['fun_base', 'game_base'].includes(kind)) return s
  const entry = {
    kind: String(kind),
    minutes: mins,
    reason: String(reason || '').slice(0, 80),
    at: n(at, Date.now()),
  }
  return {
    ...s,
    entries: [...s.entries, entry].slice(-200),
  }
}

/**
 * 统一时间账单一真相计算函数。
 * 扣罚（penalty）只从挣来的池子（earnedBout + earnedBank + earnedBreak）里扣，绝不扣基础额度（funQuota / gameQuota）。
 */
export function computeTimeLedger({
  state = null,
  day = '',
  today = [],
  tierOf = () => 'other',
  passMinutes = 0,
  funQuota = FUN_QUOTA_MIN,
  gameQuota = GAME_QUOTA_MIN,
  earnedBout = 0,
  earnedBank = 0,
  earnedBreak = 0,
  penalty = 0,
} = {}) {
  const s = normalizeTimeLedger(state, day)
  let sumBout = posInt(earnedBout)
  let sumBank = posInt(earnedBank)
  let sumBreak = posInt(earnedBreak)
  let sumPenalty = posInt(penalty)
  let extraFunUsed = 0
  let extraGameUsed = 0
  let extraDualUsed = 0

  for (const e of s.entries) {
    const m = posInt(e.minutes)
    if (e.kind === 'earned_bout') sumBout += m
    else if (e.kind === 'earned_bank') sumBank += m
    else if (e.kind === 'break') sumBreak += m
    else if (e.kind === 'penalty') sumPenalty += m
    else if (e.kind === 'used_fun') extraFunUsed += m
    else if (e.kind === 'used_game') extraGameUsed += m
    else if (e.kind === 'used_dual_outside_pass') extraDualUsed += m
  }

  // 若外层同时传了显式增量与账本记录（比如 earned.json 兼容），避免调用方传错时重复加：
  // 约定：调用方若直接用 time-ledger.json 记账，则不重复传 earnedBout/earnedBank/penalty；
  // 若某测试直接传了 earnedBout/penalty（且账本里还没有），上面已兼容。
  let phoneFun = 0
  let phoneGame = 0
  let phoneDual = 0
  for (const row of Array.isArray(today) ? today : []) {
    if (!row?.app) continue
    const t = tierOf(String(row.app), String(row.label || ''))
    const m = posInt(row.minutes)
    if (t === 'fun') phoneFun += m
    else if (t === 'game') phoneGame += m
    else if (t === 'dual') phoneDual += m
  }

  const baseFun = posInt(funQuota)
  const baseGame = posInt(gameQuota)
  const dualCounted = Math.max(0, phoneDual - posInt(passMinutes)) + extraDualUsed
  const funUsed = phoneFun + extraFunUsed + dualCounted
  const gameUsed = phoneGame + extraGameUsed

  const earned = sumBout + sumBank + sumBreak
  const penaltyApplied = Math.min(earned, sumPenalty)
  const earnedNet = Math.max(0, earned - sumPenalty)

  const funCap = baseFun + earnedNet
  const gameRemain = Math.max(0, baseGame - gameUsed)
  const maxConvertible = Math.floor(gameRemain / GAME_TO_FUN_RATE)
  const funAvail = funCap + maxConvertible
  const funLeft = Math.max(0, funAvail - funUsed)
  const converted = Math.max(0, Math.min(funUsed - funCap, maxConvertible))
  const gameLeft = Math.max(0, gameRemain - converted * GAME_TO_FUN_RATE)

  const line = formatTimeLedgerLine({
    funLeft,
    gameLeft,
    funUsed,
    gameUsed,
    converted,
    earned,
    earnedNet,
    penalty: sumPenalty,
  })

  return {
    day: s.day || String(day || ''),
    funQuota: baseFun,
    gameQuota: baseGame,
    earnedBout: sumBout,
    earnedBank: sumBank,
    earnedBreak: sumBreak,
    earned,
    penalty: sumPenalty,
    penaltyApplied,
    earnedNet,
    passMinutes: posInt(passMinutes),
    funUsed,
    dualCounted,
    gameUsed,
    converted,
    funLeft,
    gameLeft,
    funOut: funUsed >= funAvail,
    gameOut: gameLeft <= 0,
    line,
  }
}

export function formatTimeLedgerLine({ funLeft, gameLeft, funUsed, gameUsed, converted, earned, earnedNet, penalty }) {
  const parts = []
  if (earned > 0) {
    parts.push(`含挣来的 ${earned}m` + (penalty > 0 ? `，扣罚 ${penalty}m 后净 +${earnedNet}m` : ''))
  } else if (penalty > 0) {
    parts.push(`今日扣罚 ${penalty}m，基础额度不受影响`)
  }
  return '今日娱乐已用 ' + funUsed + ' 分钟、还剩 ' + funLeft + ' 分钟' +
    (parts.length ? '（' + parts.join('；') + '）' : '') +
    '；游戏已用 ' + gameUsed + '、还剩 ' + gameLeft + ' 分钟' +
    (converted ? '（已有 ' + converted * GAME_TO_FUN_RATE + ' 分钟游戏额度换成娱乐）' : '')
}
