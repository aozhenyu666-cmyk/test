// dsh-go-companion · 墙（第二轮）
//
// 第二轮把「陪你聊」和「管住你」分开：压力来自用户清醒时定下的规则，由**墙**执行；两姐妹（鱼丸、鲸澜）
// 只是平级的陪聊搭子，墙不聊天、不谈判。这个模块就是墙的规则引擎——纯函数，跑在插件里（插件决定、
// 手机执行），不经过模型。它把一张**内部规则表**（存在 config，用户改表不必重新调试代码）算成
// 「现在娱乐锁不锁、锁到几点、为什么」，交给 index.js 并进本地规则快照下发给手机。
//
// 默认就是严格状态：一天里大部分时间娱乐锁着，只有吃饭时段开放，夜里由深夜锁接管。放松只能提前一天、
// 在对账后的申请窗口里找鲸澜要，只管一天，零点复归；每周最多 3 天。收紧任何时候都能立即生效。
//
// 墙只认两样东西：config 里的规则表，和「生效中的修改记录」（relax / tighten，都带当天 daykey）。
// 聊天里的任何话都改不动墙——这是底线 1。

import { dayNumber, hm, minuteOfDay, startOfDay } from './tz.js'
import { NIGHT_LOCK_FROM, NIGHT_LOCK_TO } from './rhythm.js'

/** 墙规则的锁类型（和 index.js 下发给手机的 kind、手机端 LockPriority 对齐）。 */
export const WALL_KIND_NIGHT = 'night'
export const WALL_KIND_DEFAULT = 'default'

/** 一次放松最多开一段娱乐，默认 60 分钟（待定项之一，可配 config.relaxSegmentMin）。 */
export const RELAX_SEGMENT_MIN = 60
/** 每周放松额度（天）。设限是因为零点复归会被「每天都申请」绕过。 */
export const RELAX_WEEKLY_QUOTA = 3
/** 申请窗口：对账完成后这段时间内随机打开，开放这么久。 */
export const APPLY_WINDOW_SPAN_MS = 60 * 60_000
export const APPLY_WINDOW_OPEN_MS = 15 * 60_000

/** 默认吃饭时段（分钟 of day）。待用户定；给一组能用的默认值。 */
export const DEFAULT_MEALS = [
  { from: 7 * 60, to: 7 * 60 + 40, name: '早饭' },
  { from: 11 * 60 + 30, to: 12 * 60 + 30, name: '午饭' },
  { from: 18 * 60, to: 19 * 60, name: '晚饭' },
]

/** 墙的默认规则表。整张表都在 config.wall 下，用户改表不必动代码。 */
export const DEFAULT_WALL = Object.freeze({
  // 墙总开关。关掉＝完全不执行默认锁（只剩作息/任务/拒绝那几道，退回第一轮行为）。
  enabled: true,
  // 吃饭时段：这几段里娱乐开放，其余默认锁着。
  meals: DEFAULT_MEALS,
  // 晚间对账后到深夜锁前：true=开放，false=继续锁（待定项，默认严格＝锁）。
  eveningOpen: false,
  // 一次放松开多长（分钟）。
  relaxSegmentMin: RELAX_SEGMENT_MIN,
  // 每周放松额度（天）。
  weeklyQuota: RELAX_WEEKLY_QUOTA,
})

/** 当地日号（当天零点的 ms），既当 daykey 又当「哪一天」。 */
export function dayKey(now, tz) {
  return startOfDay(now, tz)
}

/** 自然周号（周一为界）。1970-01-01 是周四，+3 把周一对齐成一周的开头。 */
export function weekKey(now, tz) {
  const days = dayNumber(now, tz)
  return Math.floor((days + 3) / 7)
}

/** config.wall 读出来并补全默认值。 */
export function wallConfig(config) {
  const w = (config && typeof config.wall === 'object' && config.wall) || {}
  const meals = Array.isArray(w.meals) ? normalizeMeals(w.meals) : DEFAULT_WALL.meals
  return {
    enabled: w.enabled !== false,
    // 第九轮：「其余时间默认锁」单独一个开关，由「全天默认锁」这条预案决定。
    // 关掉它只去掉默认锁；收紧、放风、吃饭、深夜锁照旧。
    defaultLock: w.defaultLock !== false,
    meals,
    eveningOpen: w.eveningOpen === true,
    relaxSegmentMin: clampInt(w.relaxSegmentMin, 15, 240, RELAX_SEGMENT_MIN),
    weeklyQuota: clampInt(w.weeklyQuota, 0, 7, RELAX_WEEKLY_QUOTA),
  }
}

function clampInt(v, lo, hi, dflt) {
  const n = Math.round(Number(v))
  if (!Number.isFinite(n)) return dflt
  return Math.max(lo, Math.min(hi, n))
}

function normalizeMeals(raw) {
  return raw
    .map((m) => ({ from: Math.round(Number(m?.from)), to: Math.round(Number(m?.to)), name: String(m?.name || '吃饭').slice(0, 8) }))
    .filter((m) => Number.isFinite(m.from) && Number.isFinite(m.to) && m.from >= 0 && m.to <= 1440 && m.from < m.to)
    .sort((a, b) => a.from - b.from)
    .slice(0, 8)
}

/** 现在是不是深夜锁时段（0:30–7:00）。 */
export function inNightLock(min) {
  return min >= NIGHT_LOCK_FROM && min < NIGHT_LOCK_TO
}

/** 现在在哪个吃饭时段里（返回那段），不在就 null。 */
export function mealNow(meals, min) {
  return meals.find((m) => min >= m.from && min < m.to) || null
}

/** 今天生效的放松（只在 relax.for === 今天 时算数；零点自动失效）。 */
export function activeRelax(config, now, tz) {
  const r = config && config.relax
  if (!r || Number(r.for) !== dayKey(now, tz)) return null
  const segments = (Array.isArray(r.segments) ? r.segments : [])
    .map((s) => ({ from: Math.round(Number(s?.from)), to: Math.round(Number(s?.to)) }))
    .filter((s) => Number.isFinite(s.from) && Number.isFinite(s.to) && s.from < s.to)
  if (!segments.length) return null
  return { for: Number(r.for), segments, reason: typeof r.reason === 'string' ? r.reason : '' }
}

/** 今天的收紧是否生效（收紧当天有效，次日复归）。 */
export function tightenActive(config, now, tz) {
  const t = config && config.tighten
  return Boolean(t && Number(t.for) === dayKey(now, tz))
}

/** 这周已经用掉几天放松额度。 */
export function relaxUsedThisWeek(config, now, tz) {
  const q = config && config.relaxWeek
  if (!q || Number(q.week) !== weekKey(now, tz)) return 0
  return Math.max(0, Math.round(Number(q.used) || 0))
}

/**
 * 对账结果带来的当天额度增减（昨日对账 → 今天的额度）：全做完 +1、一件没做完 -1、一半 0。
 * 只在 dailyQuota.for === 今天 时算数，过期自动失效（不累计）。
 */
export function relaxBonus(config, now, tz) {
  const q = config && config.dailyQuota
  if (!q || Number(q.for) !== dayKey(now, tz)) return 0
  const d = Math.round(Number(q.delta) || 0)
  return Math.max(-2, Math.min(2, d))
}

/** 本周还剩几天放松额度（含昨晚上对账挣到 / 扣掉的）。 */
export function relaxRemaining(config, now, tz) {
  const w = wallConfig(config)
  return Math.max(0, w.weeklyQuota + relaxBonus(config, now, tz) - relaxUsedThisWeek(config, now, tz))
}

/**
 * 墙此刻的判断：娱乐锁不锁、锁到几点、为什么。只看规则表 + 生效中的修改记录，不看模型、不看聊天。
 *
 * 返回 { locked, kind, until, why, openUntil }：
 *   locked=false 时娱乐开放，openUntil 是开放到几点（吃饭时段结束 / 放风段结束）。
 *   locked=true  时 kind/until/why 用来下发给手机并让墙的提示模板说话。
 * 深夜锁、作息门、任务、拒绝由各自的逻辑单独下发（优先级更高）；这里只管「默认锁 + 吃饭开放 + 放风」。
 */
export function wallDecision(config, now, tz) {
  const w = wallConfig(config)
  const min = minuteOfDay(now, tz)
  const midnight = dayKey(now, tz)
  const endOfDay = midnight + 24 * 60 * 60_000

  // 深夜锁优先级最高，墙这里也先判它（index.js 另有 night 门，这里给同一结论，取更严）。
  if (inNightLock(min)) {
    const until = midnight + NIGHT_LOCK_TO * 60_000
    return { locked: true, kind: WALL_KIND_NIGHT, until, why: wallWhy(WALL_KIND_NIGHT, { until, tz }), openUntil: 0 }
  }

  // 墙关掉：不加默认锁（退回第一轮，只剩别的门）。
  if (!w.enabled) return { locked: false, kind: null, until: 0, why: null, openUntil: 0 }

  // 收紧：当天一律锁到零点（盖过吃饭时段和放风）。
  if (tightenActive(config, now, tz)) {
    return { locked: true, kind: WALL_KIND_DEFAULT, until: endOfDay, why: wallWhy(WALL_KIND_DEFAULT, { tightened: true }), openUntil: 0 }
  }

  // 放风段（今天申请到的）：在段里就开放，开放到段尾。
  const relax = activeRelax(config, now, tz)
  if (relax) {
    const seg = relax.segments.find((s) => min >= s.from && min < s.to)
    if (seg) return { locked: false, kind: null, until: 0, why: null, openUntil: midnight + seg.to * 60_000 }
  }

  // 吃饭时段：开放到这段结束。
  const meal = mealNow(w.meals, min)
  if (meal) return { locked: false, kind: null, until: 0, why: null, openUntil: midnight + meal.to * 60_000 }

  // 晚间对账后到深夜锁前：配置成开放就不加默认锁（留给别的门处理）。
  if (w.eveningOpen && min >= NIGHT_LOCK_TO) {
    // 仅当已过对账时间附近才算「晚间之后」；对账门本身由 rhythm 处理，这里只负责不额外上默认锁。
    // 简化：晚间开放时，默认锁只到下一段吃饭 / 深夜锁之前仍然成立于白天，傍晚放开。
  }

  // 第九轮：默认锁归预案管。预案没生效，其余时间就不锁（收紧在上面已经判过，不受影响）。
  if (!w.defaultLock) return { locked: false, kind: null, until: 0, why: null, openUntil: 0 }

  // 其余时间：默认锁。until 取「下一次开放或下一道更高优先级门」的边界，手机据此离线也能按时松。
  const until = nextBoundary(w, min, midnight, endOfDay)
  return { locked: true, kind: WALL_KIND_DEFAULT, until, why: wallWhy(WALL_KIND_DEFAULT, {}), openUntil: 0 }
}

/** 默认锁一直锁到：下一段吃饭开始、或深夜锁开始、或今天结束——取最近的那个。 */
function nextBoundary(w, min, midnight, endOfDay) {
  const marks = []
  for (const m of w.meals) if (m.from > min) marks.push(m.from)
  if (NIGHT_LOCK_FROM > min) marks.push(NIGHT_LOCK_FROM)
  // 深夜锁在 0:30，今天已过就用明天 0:30（= 今天 23:59 之后由明天的墙接管）。
  const nextMin = marks.length ? Math.min(...marks) : null
  if (nextMin == null) return endOfDay
  return midnight + nextMin * 60_000
}

/**
 * 墙的提示模板：代码按规则数据生成，不经模型。只说三件事——现在是什么规则、为什么、什么时候结束/怎么解。
 * 想改规则只能等申请窗口找鲸澜；这句话也由墙说，不由姐妹出面。
 */
export function wallWhy(kind, { until, tz, tightened } = {}) {
  if (kind === WALL_KIND_NIGHT) {
    return '深夜锁：现在是休息时间，娱乐锁到早上 7 点。想调整白天的规则，等明天对账后的申请窗口找鲸澜。'
  }
  if (tightened) {
    return '今天收紧了：娱乐锁到明天零点。收紧当天不放松；明天恢复默认规则。'
  }
  return '默认严格：现在不是吃饭/放风时段，娱乐锁着。到点自动开放；想多开一段，等今晚对账后的申请窗口找鲸澜（只能提前一天申请）。'
}

/**
 * 违抗的收尾话：由墙按模板说，不经模型、不由姐姐出面（第二轮）。
 * 只说他做了什么、现在是什么规则、到什么时候结束。
 */
export function wallDefianceText(label, until) {
  const who = String(label || '').slice(0, 40) || '娱乐 App'
  const at = Number(until) > 0 ? hm(Number(until)) : '到点'
  return '关掉提醒还在用' + who + '：墙按规则当场升级，娱乐锁到 ' + at + '。这是你清醒时定下的规则，聊天里改不了。'
}

/**
 * 申请窗口的状态机。对账完成后在 APPLY_WINDOW_SPAN_MS 内随机选一个时刻打开，开放 APPLY_WINDOW_OPEN_MS。
 * 纯函数：给定「对账何时完成」和「窗口状态」，算出此刻窗口开没开、要不要通知。
 *
 * @param {object} state config.applyWindow：{ day, openAt, closeAt, notified, used }
 * @returns {{state, open, justOpened }}
 */
export function trackApplyWindow(state, { reviewDoneAt, now, tz, rand = Math.random }) {
  const s = { ...(state || {}) }
  const today = dayKey(now, tz)
  // 新的一天：清掉旧窗口。
  if (Number(s.day) !== today) {
    if (!reviewDoneAt) return { state: { day: today }, open: false, justOpened: false }
    Object.assign(s, { day: today, openAt: 0, closeAt: 0, notified: false, used: false })
  }
  // 对账还没完成：窗口不排期。
  if (!reviewDoneAt) return { state: s, open: false, justOpened: false }
  // 还没排期：在对账完成后的时段里随机挑一个打开时刻。
  if (!Number(s.openAt)) {
    const offset = Math.floor((Number(rand()) || 0) * APPLY_WINDOW_SPAN_MS)
    s.openAt = Number(reviewDoneAt) + offset
    s.closeAt = s.openAt + APPLY_WINDOW_OPEN_MS
    s.notified = false
  }
  const open = now >= Number(s.openAt) && now < Number(s.closeAt)
  const justOpened = open && !s.notified
  if (justOpened) s.notified = true
  return { state: s, open, justOpened }
}

/** 窗口此刻开着吗（只读，不改状态）。 */
export function applyWindowOpen(state, now) {
  const s = state || {}
  return Number(s.openAt) > 0 && now >= Number(s.openAt) && now < Number(s.closeAt)
}
