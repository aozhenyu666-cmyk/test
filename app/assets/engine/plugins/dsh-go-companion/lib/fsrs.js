// 2026-11-21 FSRS（纯函数，零依赖）：个人复习卡的排期。
//
// 用 FSRS-4.5 的默认 17 个权重（open-spaced-repetition 公开参数），目标留存 0.9。
// 只做长期排期：答错（Again）10 分钟后再问；其余按稳定度给天数，且 Hard ≤ Good ≤ Easy 严格单调。
// 卡的状态：0 新卡 / 1 学习中 / 2 复习 / 3 重学。rating：1 Again / 2 Hard / 3 Good / 4 Easy。

export const FSRS_W = Object.freeze([0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755])
export const REQUEST_RETENTION = 0.9
export const MAX_INTERVAL_DAYS = 3650
export const AGAIN_DELAY_MS = 10 * 60_000
const DECAY = -0.5
const FACTOR = 19 / 81
const DAY = 86_400_000
export const RATINGS = Object.freeze({ again: 1, hard: 2, good: 3, easy: 4 })

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v))
const n = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

/** 'good' / 3 / '3' → 3；认不出来 → 0。 */
export function ratingOf(v) {
  if (typeof v === 'string' && RATINGS[v.trim().toLowerCase()]) return RATINGS[v.trim().toLowerCase()]
  const r = Math.round(n(v, 0))
  return r >= 1 && r <= 4 ? r : 0
}

/** 新卡。firstDueMs：第一次什么时候问（个人卡默认隔 12 小时再问——当场刷不算回忆）。 */
export function createFsrsCard(now = Date.now(), { firstDueMs = 0 } = {}) {
  return { state: 0, due: now + Math.max(0, firstDueMs), stability: 0, difficulty: 0, elapsedDays: 0, scheduledDays: 0, reps: 0, lapses: 0, lastReview: 0 }
}

export function cleanFsrs(raw, now = Date.now()) {
  const f = raw && typeof raw === 'object' ? raw : {}
  return {
    state: clamp(Math.round(n(f.state, 0)), 0, 3),
    due: n(f.due, now),
    stability: Math.max(0, n(f.stability, 0)),
    difficulty: clamp(n(f.difficulty, 0), 0, 10),
    elapsedDays: Math.max(0, n(f.elapsedDays, 0)),
    scheduledDays: Math.max(0, n(f.scheduledDays, 0)),
    reps: Math.max(0, Math.round(n(f.reps, 0))),
    lapses: Math.max(0, Math.round(n(f.lapses, 0))),
    lastReview: Math.max(0, n(f.lastReview, 0)),
  }
}

const initStability = (g) => Math.max(0.1, FSRS_W[g - 1])
const initDifficulty = (g) => clamp(FSRS_W[4] - (g - 3) * FSRS_W[5], 1, 10)
function nextDifficulty(d, g) {
  const next = d - FSRS_W[6] * (g - 3)
  return clamp(FSRS_W[7] * initDifficulty(3) + (1 - FSRS_W[7]) * next, 1, 10)
}
/** 隔了 t 天、稳定度 s 时还记得的概率。 */
export function retrievability(elapsedDays, stability) {
  if (stability <= 0) return 0
  return Math.pow(1 + FACTOR * Math.max(0, elapsedDays) / stability, DECAY)
}
function recallStability(d, s, r, g) {
  const hard = g === 2 ? FSRS_W[15] : 1
  const easy = g === 4 ? FSRS_W[16] : 1
  return s * (1 + Math.exp(FSRS_W[8]) * (11 - d) * Math.pow(s, -FSRS_W[9]) * (Math.exp(FSRS_W[10] * (1 - r)) - 1) * hard * easy)
}
function forgetStability(d, s, r) {
  return Math.min(s, FSRS_W[11] * Math.pow(d, -FSRS_W[12]) * (Math.pow(s + 1, FSRS_W[13]) - 1) * Math.exp(FSRS_W[14] * (1 - r)))
}
/** 稳定度 → 间隔天数（留存 0.9 时间隔≈稳定度）。 */
export function intervalDays(stability) {
  const raw = (stability / FACTOR) * (Math.pow(REQUEST_RETENTION, 1 / DECAY) - 1)
  return clamp(Math.round(raw), 1, MAX_INTERVAL_DAYS)
}

/** 四个评分各自会得到的（稳定度、难度、间隔天数），Hard ≤ Good ≤ Easy 严格单调。 */
function preview(card, now) {
  const c = cleanFsrs(card, now)
  const isNew = c.state === 0 || c.stability <= 0
  const elapsed = isNew || !c.lastReview ? 0 : Math.max(0, (now - c.lastReview) / DAY)
  const r = isNew ? 1 : retrievability(elapsed, c.stability)
  const out = {}
  for (const g of [1, 2, 3, 4]) {
    const d = isNew ? initDifficulty(g) : nextDifficulty(c.difficulty, g)
    const s = isNew ? initStability(g) : g === 1 ? forgetStability(c.difficulty, c.stability, r) : recallStability(c.difficulty, c.stability, r, g)
    out[g] = { d, s: Math.max(0.1, s), days: g === 1 ? 0 : intervalDays(s) }
  }
  out[2].days = Math.min(out[2].days, out[3].days)
  out[3].days = Math.max(out[3].days, out[2].days + 1)
  out[4].days = Math.max(out[4].days, out[3].days + 1)
  return { c, elapsed, out }
}

/** 预览：四个评分各自下次什么时候问（天；Again 是 0 = 10 分钟后）。 */
export function previewIntervals(card, now = Date.now()) {
  const { out } = preview(card, now)
  return { again: 0, hard: out[2].days, good: out[3].days, easy: out[4].days }
}

/** 复习一次：确定性地更新稳定度、难度、下次时间、次数、遗忘次数、状态。 */
export function reviewFsrsCard(card, rating, now = Date.now()) {
  const g = ratingOf(rating)
  const { c, elapsed, out } = preview(card, now)
  if (!g) return c
  const p = out[g]
  const again = g === 1
  return {
    state: again ? (c.state === 0 ? 1 : 3) : 2,
    due: again ? now + AGAIN_DELAY_MS : now + p.days * DAY,
    stability: Math.round(p.s * 10000) / 10000,
    difficulty: Math.round(p.d * 10000) / 10000,
    elapsedDays: Math.round(elapsed * 100) / 100,
    scheduledDays: p.days,
    reps: c.reps + 1,
    lapses: c.lapses + (again ? 1 : 0),
    lastReview: now,
  }
}

export const isCardDue = (card, now = Date.now()) => cleanFsrs(card?.fsrs ?? card, now).due <= now

/** 到期卡排序：逾期最久的先，同样逾期的错得多的先。 */
export function sortDueCards(cards, now = Date.now()) {
  return (Array.isArray(cards) ? cards : [])
    .filter((x) => isCardDue(x, now))
    .sort((a, b) => {
      const fa = cleanFsrs(a.fsrs ?? a, now)
      const fb = cleanFsrs(b.fsrs ?? b, now)
      return fa.due - fb.due || fb.lapses - fa.lapses
    })
}
