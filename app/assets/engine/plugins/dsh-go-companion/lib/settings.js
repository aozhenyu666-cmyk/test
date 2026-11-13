// dsh-go-companion · W4 设定（第八轮）
//
// 设定分两种力度：**改紧立即生效，改松走冷却期**（沿用墙的「待生效」）。
// 判断"松/紧"靠一根统一的轴：looseness() 把每项设定折算成"越大越松"的数，
// 任何一项变大就算改松。新增设定项只要在里面加一行，不用改判断逻辑。
//
// 纯函数：now / config / weekday 都由调用方给。

import { weekdayOf } from './tz.js'

/** 三种日子：工作日 / 休息日 / 外出日。周末默认休息日；外出日要显式指定日期。 */
export const DAY_WORKDAY = 'workday'
export const DAY_RESTDAY = 'restday'
export const DAY_OUTING = 'outing'

/** 默认固定工作时段（分钟）。外出日不排固定时段——那天在路上。 */
export const DEFAULT_WINDOWS = Object.freeze({
  workday: [[9 * 60, 12 * 60], [14 * 60, 18 * 60]],
  restday: [[10 * 60, 12 * 60]],
  outing: [],
})

/** 改松的冷却期：一天。当天改松、当晚生效，第二天才知道代价。 */
export const COOL_MS = 24 * 60 * 60_000

export const DEFAULT_SETTINGS = Object.freeze({
  windows: DEFAULT_WINDOWS,
  minBouts: 4,
  minThinking: 1,
  // 各局型默认预算（分钟）。键就是协议 id（design/execute/outing/rest/study），
  // thinking 留给还没落地的思维局（J5）。
  budget: Object.freeze({ study: 25, execute: 30, design: 30, outing: 40, rest: 20, thinking: 25 }),
  // P4 全部阈值。施工书第 9 节三个待定项先按偏严初值。
  escalate: Object.freeze({
    E1Count: 3,
    E1WindowMs: 30 * 60_000,
    cellMin: 10,
    cellMinStep: 10,
    L1WorkMin: 15,
    L1RestMin: 30,
    L2Min: 20,
    L3Min: 60,
  }),
  voice: Object.freeze({ provider: '', voiceId: '' }),
  judgeModel: '',
})

function int(v, lo, hi, dflt) {
  const n = Math.round(Number(v))
  if (!Number.isFinite(n)) return dflt
  return Math.max(lo, Math.min(hi, n))
}

/** [[from,to], ...] 收成 [{from,to}]，丢掉坏的、按开始时间排序。 */
function windows(list, dflt) {
  const src = Array.isArray(list) ? list : dflt
  const out = []
  for (const w of src) {
    const from = int(Array.isArray(w) ? w[0] : w?.from, 0, 1439, NaN)
    const to = int(Array.isArray(w) ? w[1] : w?.to, 0, 1439, NaN)
    if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) continue
    out.push({ from, to })
  }
  return out.sort((a, b) => a.from - b.from)
}

/**
 * 读设定并补全默认值。幂等：既收 config（设定在 config.day 下），
 * 也收已经规整过的设定对象本身——applySettings 的挂起项就是这么回喂的。
 */
export function settingsOf(config) {
  const c = (config && typeof config.day === 'object' && config.day) || config || {}
  const d = DEFAULT_SETTINGS
  const w = (c.windows && typeof c.windows === 'object' && c.windows) || {}
  const b = (c.budget && typeof c.budget === 'object' && c.budget) || {}
  const e = (c.escalate && typeof c.escalate === 'object' && c.escalate) || {}
  const v = (c.voice && typeof c.voice === 'object' && c.voice) || {}
  return {
    windows: {
      workday: windows(w.workday, d.windows.workday),
      restday: windows(w.restday, d.windows.restday),
      outing: windows(w.outing, d.windows.outing),
    },
    minBouts: int(c.minBouts, 0, 12, d.minBouts),
    minThinking: int(c.minThinking, 0, 6, d.minThinking),
    budget: {
      study: int(b.study, 5, 180, d.budget.study),
      execute: int(b.execute, 5, 180, d.budget.execute),
      design: int(b.design, 5, 180, d.budget.design),
      outing: int(b.outing, 5, 180, d.budget.outing),
      rest: int(b.rest, 5, 180, d.budget.rest),
      thinking: int(b.thinking, 5, 180, d.budget.thinking),
    },
    escalate: {
      E1Count: int(e.E1Count, 1, 10, d.escalate.E1Count),
      E1WindowMs: int(e.E1WindowMs, 60_000, 6 * 60 * 60_000, d.escalate.E1WindowMs),
      cellMin: int(e.cellMin, 1, 120, d.escalate.cellMin),
      cellMinStep: int(e.cellMinStep, 0, 60, d.escalate.cellMinStep),
      L1WorkMin: int(e.L1WorkMin, 1, 240, d.escalate.L1WorkMin),
      L1RestMin: int(e.L1RestMin, 1, 240, d.escalate.L1RestMin),
      L2Min: int(e.L2Min, 1, 240, d.escalate.L2Min),
      L3Min: int(e.L3Min, 10, 600, d.escalate.L3Min),
    },
    voice: {
      provider: String(v.provider || d.voice.provider).slice(0, 40),
      voiceId: String(v.voiceId || d.voice.voiceId).slice(0, 80),
    },
    judgeModel: String(c.judgeModel || d.judgeModel).slice(0, 120),
  }
}

/**
 * 这根轴：每一项折算成"越大越松"。
 * 顺序固定，两条设定比大小就按同一个下标挨个看。
 */
export function looseness(s) {
  const win = (list) => list.reduce((n, w) => n + (w.to - w.from), 0)
  return [
    -s.minBouts, // 局数下限越低越松
    -s.minThinking,
    s.budget.study,
    s.budget.execute,
    s.budget.design,
    s.budget.outing,
    s.budget.rest,
    s.budget.thinking,
    s.escalate.E1Count, // 容许更多次敷衍才算升级
    s.escalate.E1WindowMs, // 窗口更长更松
    -s.escalate.cellMin, // 审讯舱更短更松
    -s.escalate.cellMinStep,
    s.escalate.L1WorkMin,
    s.escalate.L1RestMin,
    s.escalate.L2Min,
    s.escalate.L3Min,
    win(s.windows.workday), // 工作时段越宽越松
    win(s.windows.restday),
    win(s.windows.outing),
  ]
}

/** next 比 prev 松（任一项变大）。改紧和不变都不算。 */
export function loosens(next, prev) {
  const a = looseness(prev)
  const b = looseness(next)
  return b.some((n, i) => n > a[i])
}

/**
 * 一次改设定的结算。
 * @param {{prev:object, next:object, now:number, pending?:object|null, coolMs?:number}} input
 * @returns {{settings:object, pending:object|null, pendingAt:number, applied:'immediate'|'pending'|'pending-due'}}
 */
export function applySettings(input) {
  const now = Number(input.now) || 0
  const coolMs = Number.isFinite(Number(input.coolMs)) ? Math.max(0, Number(input.coolMs)) : COOL_MS
  const prev = settingsOf(input.prev)
  const pending = input.pending && typeof input.pending === 'object' ? input.pending : null

  // 冷却期到点：挂着的松绑这次生效。
  if (pending && Number(pending.at) <= now) {
    return { settings: settingsOf(pending.settings), pending: null, pendingAt: 0, applied: 'pending-due' }
  }
  const next = settingsOf(input.next)
  if (!loosens(next, prev)) {
    return { settings: next, pending, pendingAt: pending ? Number(pending.at) || 0 : 0, applied: 'immediate' }
  }
  // 改松：先挂着，手上这套继续用。
  const at = now + coolMs
  return { settings: prev, pending: { settings: next, at }, pendingAt: at, applied: 'pending' }
}

/** 今天是哪种日子。外出日要显式给日期（config.day.outingDays = ['2026-11-13', ...]）。 */
export function dayKindOf(now, tz, config) {
  const c = (config && typeof config.day === 'object' && config.day) || {}
  const outing = Array.isArray(c.outingDays) ? c.outingDays : []
  if (outing.length) {
    const d = new Date(Number(now) || 0)
    // 用目标时区的日期串比对，不能用本机的。
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(d)
    if (outing.includes(parts)) return DAY_OUTING
  }
  const wd = weekdayOf(Number(now) || 0, tz)
  return wd === 0 || wd === 6 ? DAY_RESTDAY : DAY_WORKDAY
}

/** 这一天的固定工作时段的副本。 */
export function windowsOf(settings, kind) {
  const list = settings.windows[kind] || []
  return list.map((w) => ({ ...w }))
}

/** 某个时刻在不在工作时段里。 */
export function inWorkWindow(settings, kind, min) {
  return windowsOf(settings, kind).some((w) => min >= w.from && min < w.to)
}

/** 下一个工作时段开始的绝对时刻；今天没有了就返回今天结束（调用方据此顺延到明天）。 */
export function nextWindowAt(settings, kind, min, midnight) {
  const list = windowsOf(settings, kind)
  const hit = list.find((w) => w.from > min)
  return hit ? midnight + hit.from * 60_000 : midnight + 86_400_000
}
