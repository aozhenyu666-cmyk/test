// dsh-go-companion · 统一时间账（2026-11-19 · H4）
//
// 2026-11-19 挣来制：娱乐 / 游戏都从一个余额里花，余额 = 昨天结转 + 保底（标准档 0）+ 今天挣来的 - 扣罚。
// 挣：局验收通过（按局长 1/3，10–40 分钟）、题库（10 题 5 分钟）、破窗；扣：违约扣罚。没花完的跨天结转（≤600）。
// 中枢（/today）、分析页（/analytics）、锁页（/plan lock.quota）、晚报、姐妹上下文全部取这同一个输出。

import { GAME_QUOTA_MIN } from './quota.js'

export const TIME_ENTRY_KINDS = Object.freeze([
  'fun_base',
  'game_base',
  'earned_bout',
  'earned_bank',
  'earned_seal',
  // 2026-11-21 学习制卡的第二半（隔天复习答对才发），记账上和验收点奖励算一处。
  'earned_review',
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

/** 余额跨天结转的上限：再多也不能把一整天买下来。 */
export const CARRY_CAP_MIN = 600

/**
 * 统一时间账单一真相计算函数（2026-11-19 改为「挣来制」）。
 *
 * 娱乐不再是每天白给的 120 分钟：可用余额 = 昨天结转（carry）+ 每日保底（funQuota，标准档 0）
 * + 今天挣来的（局验收 / 题库 / 破窗）- 扣罚。娱乐和游戏都从这一个余额里花；
 * 游戏另有每日上限（gameQuota，不是白给的额度）。没花完的余额明天接着用（上限 CARRY_CAP_MIN）。
 * 扣罚只扣结转和挣来的，不扣保底。
 */
export function computeTimeLedger({
  state = null,
  day = '',
  today = [],
  tierOf = () => 'other',
  passMinutes = 0,
  funQuota = 0,
  gameQuota = GAME_QUOTA_MIN,
  carry = 0,
  carryCap = CARRY_CAP_MIN,
  earnedBout = 0,
  earnedBank = 0,
  earnedBreak = 0,
  penalty = 0,
} = {}) {
  const s = normalizeTimeLedger(state, day)
  let sumBout = posInt(earnedBout)
  let sumBank = posInt(earnedBank)
  let sumBreak = posInt(earnedBreak)
  let sumSeal = 0
  let sumPenalty = posInt(penalty)
  let extraFunUsed = 0
  let extraGameUsed = 0
  let extraDualUsed = 0

  for (const e of s.entries) {
    const m = posInt(e.minutes)
    if (e.kind === 'earned_bout') sumBout += m
    else if (e.kind === 'earned_bank') sumBank += m
    else if (e.kind === 'earned_seal' || e.kind === 'earned_review') sumSeal += m
    else if (e.kind === 'break') sumBreak += m
    else if (e.kind === 'penalty') sumPenalty += m
    else if (e.kind === 'used_fun') extraFunUsed += m
    else if (e.kind === 'used_game') extraGameUsed += m
    else if (e.kind === 'used_dual_outside_pass') extraDualUsed += m
  }

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
  const gameCap = posInt(gameQuota)
  const carryIn = Math.min(posInt(carryCap) || CARRY_CAP_MIN, posInt(carry))
  const dualCounted = Math.max(0, phoneDual - posInt(passMinutes)) + extraDualUsed
  const funUsed = phoneFun + extraFunUsed + dualCounted
  const gameUsed = phoneGame + extraGameUsed

  const earned = sumBout + sumBank + sumBreak + sumSeal
  // 扣罚先扣今天挣来的，再扣结转；保底不扣。
  const penaltyApplied = Math.min(earned + carryIn, sumPenalty)
  const earnedNet = Math.max(0, earned - sumPenalty)
  const avail = carryIn + baseFun + earned - penaltyApplied
  const balance = Math.max(0, avail - funUsed - gameUsed)
  const gameLeft = Math.min(Math.max(0, gameCap - gameUsed), balance)
  const funLeft = balance
  const carryOut = Math.min(posInt(carryCap) || CARRY_CAP_MIN, balance)

  const line = formatTimeLedgerLine({
    funLeft,
    gameLeft,
    funUsed,
    gameUsed,
    converted: 0,
    earned,
    earnedNet,
    penalty: sumPenalty,
    carry: carryIn,
    base: baseFun,
  })

  return {
    day: s.day || String(day || ''),
    funQuota: baseFun,
    gameQuota: gameCap,
    carry: carryIn,
    carryOut,
    carryCap: posInt(carryCap) || CARRY_CAP_MIN,
    earnedBout: sumBout,
    earnedBank: sumBank,
    earnedBreak: sumBreak,
    earnedSeal: sumSeal,
    earned,
    penalty: sumPenalty,
    penaltyApplied,
    earnedNet,
    passMinutes: posInt(passMinutes),
    funUsed,
    dualCounted,
    gameUsed,
    converted: 0,
    balance,
    funLeft,
    gameLeft,
    funOut: funLeft <= 0,
    gameOut: gameLeft <= 0,
    line,
  }
}

export function formatTimeLedgerLine({ funLeft, gameLeft, funUsed, gameUsed, earned, penalty, carry = 0, base = 0 }) {
  const src = []
  if (carry > 0) src.push(`昨天结转 ${carry}m`)
  if (base > 0) src.push(`保底 ${base}m`)
  if (earned > 0) src.push(`今天挣 ${earned}m`)
  if (penalty > 0) src.push(`扣罚 ${penalty}m`)
  return '娱乐余额 ' + funLeft + ' 分钟（' + (src.length ? src.join('、') : '今天还没挣到——过一局验收就有') + '）' +
    '；今天已用娱乐 ' + funUsed + '、游戏 ' + gameUsed + ' 分钟' +
    (gameLeft < funLeft ? '；游戏今天还能玩 ' + gameLeft + ' 分钟' : '')
}
