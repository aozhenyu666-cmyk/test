// dsh-go-companion · P4 升级阶梯（第八轮）
//
// 铁律：**这些规则全部在代码里，由引擎执行，模型无权减免。**
// 所以本模块不接收任何"语气""态度"输入，只认时间、计数、当前前台应用。
//
// 两串规则各自独立：
//   敷衍 E1/E1′/E2/E3 —— 没认真答的代价
//   沉迷 L1/L2/L3   —— 娱乐与跑偏的代价
//
// 计数一律按当地日号（北京时间零点）重置，不能按 UTC 或开机时长。

import { DAY_WORKDAY, inWorkWindow } from './settings.js'

/** E1 发出后等 Gemini 进前台的宽限。 */
export const CELL_GRACE_MS = 60_000
/** L2 局官核对后等他回话的宽限。 */
export const L2_GRACE_MS = 2 * 60_000

/** 出口只有三个，任何时候都成立。 */
export const EXITS = Object.freeze([
  '答出那条指令并通过判定',
  '紧急应用（电话、短信、地图、支付）任何时候都可用',
  '破窗：沿用已有机制，计入信用分和次日代价',
])

function num(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

function emptyState(day) {
  return {
    day,
    perfunctory: [],
    e1Count: 0,
    e3: false,
    strictTomorrow: false,
    funToday: 0,
    funRun: null,
    l2: null,
  }
}

/** 跨零点就把当天的计数清掉。日号不同＝新的一天。 */
export function dayState(state, day) {
  const s = state && typeof state === 'object' ? state : {}
  const d = num(day)
  if (num(s.day) !== d) return emptyState(d)
  return {
    ...s,
    day: d,
    perfunctory: (Array.isArray(s.perfunctory) ? s.perfunctory : []).filter((t) => num(t) > 0),
    e1Count: num(s.e1Count),
    funToday: num(s.funToday),
  }
}

/** 记一次敷衍，顺手把窗口外的丢掉（不然一天攒下来会永远触发）。 */
export function notePerfunctory(state, input) {
  const day = num(input.day)
  const s = dayState(state, day)
  const now = num(input.now)
  const windowMs = num(input.windowMs) || 30 * 60_000
  const list = [...s.perfunctory, now].filter((t) => now - t <= windowMs)
  return { ...s, perfunctory: list }
}

/** 窗口内敷衍了几次。 */
export function perfunctoryCount(state, now, windowMs) {
  const list = Array.isArray(state?.perfunctory) ? state.perfunctory : []
  const w = num(windowMs) || 30 * 60_000
  return list.filter((t) => num(now) - num(t) <= w).length
}

/**
 * 敷衍升级。第几次触发 E1 决定审讯舱多长。
 * @param {{state:object, now:number, day:number, settings:object}} input
 * @returns {{rule:string, minutes:number, action:object|null, why:string, state:object}}
 */
export function evaluateE(input) {
  const day = num(input.day)
  const now = num(input.now)
  const s = dayState(input.state, day)
  const e = input.settings.escalate
  const count = perfunctoryCount(s, now, e.E1WindowMs)

  if (count < e.E1Count) {
    return { rule: '', minutes: 0, action: null, why: '', state: s }
  }
  // 第一次触发 E1。E2/E3 看的是「当天第几次触发」，不是第几次敷衍。
  const next = s.e1Count + 1
  const minutes = e.cellMin + Math.max(0, next - 1) * e.cellMinStep
  const base = { ...s, e1Count: next, perfunctory: [], e1At: now }
  if (next >= 3) {
    return {
      rule: 'E3',
      minutes,
      action: { type: 'fun-lock-day', untilDayEnd: true },
      why: '当天第 3 次触发 E1',
      state: { ...base, e3: true, strictTomorrow: true },
    }
  }
  return {
    rule: next === 2 ? 'E2' : 'E1',
    minutes,
    action: { type: 'cell', minutes, allowGeminiOnly: true },
    why: next === 2 ? '当天第 2 次触发 E1' : '30 分钟内敷衍了 ' + count + ' 次',
    state: base,
  }
}

/**
 * E1′：审讯舱发出后 60 秒，Gemini 没进前台（或手机上没有 Gemini）→ 全机锁。
 * @param {{now:number, cellAt:number, geminiForeground?:boolean, hasGemini?:boolean}} input
 */
export function evaluateEPrime(input) {
  const at = num(input.cellAt)
  if (!at) return { rule: '', action: null, why: '' }
  if (input.hasGemini === false) return { rule: 'E1′', action: { type: 'all-lock', allowBout: true }, why: '手机上没有 Gemini' }
  if (num(input.now) - at < CELL_GRACE_MS) return { rule: '', action: null, why: '' }
  if (input.geminiForeground) return { rule: '', action: null, why: '' }
  return { rule: 'E1′', action: { type: 'all-lock', allowBout: true }, why: '60 秒内 Gemini 没进前台' }
}

/**
 * 沉迷升级。
 * @param {{state:object, now:number, min:number, day:number, kind:string, settings:object,
 *          foreground?:string|null, funPackage?:boolean, declared?:string[],
 *          funDeltaMs?:number, answeredAt?:number}} input
 * @returns {{rule:string, action:object|null, why:string, state:object}}
 */
export function evaluateL(input) {
  const day = num(input.day)
  const now = num(input.now)
  const s = dayState(input.state, day)
  const e = input.settings.escalate
  const work = inWorkWindow(input.settings, input.kind || DAY_WORKDAY, num(input.min))

  // 娱乐累计：调用方给增量，这里只累加。当天累计到上限就全天锁。
  const funToday = s.funToday + Math.max(0, num(input.funDeltaMs))
  const next = { ...s, funToday }
  if (work && funToday >= e.L3Min * 60_000) {
    return { rule: 'L3', action: { type: 'fun-lock-day', untilDayEnd: true }, why: '当天娱乐累计到了 ' + e.L3Min + ' 分钟', state: next }
  }

  // L1：娱乐应用连续使用到阈值，直接锁并进计划模态。
  const limit = work ? e.L1WorkMin : e.L1RestMin
  if (input.funPackage) {
    const since = num(s.funRun?.since) || now
    const run = { pkg: String(input.foreground || ''), since }
    next.funRun = run
    if (now - since >= limit * 60_000) {
      return {
        rule: 'L1',
        action: { type: 'fun-lock-plan', untilDayEnd: false },
        why: (work ? '工作时段' : '非工作时段') + '娱乐连续用了 ' + limit + ' 分钟',
        state: { ...next, funRun: { pkg: run.pkg, since: now } },
      }
    }
  } else if (next.funRun) {
    next.funRun = null
  }

  // L2：工作时段内，当前局没声明的应用连续用到阈值 → 局官核对；2 分钟不答就锁它。
  if (work && input.foreground && !(Array.isArray(input.declared) && input.declared.includes(input.foreground))) {
    const pkg = String(input.foreground)
    const since = next.l2?.pkg === pkg ? num(next.l2.since) : now
    const askedAt = next.l2?.pkg === pkg ? num(next.l2.askedAt) : 0
    const l2 = { pkg, since, askedAt }
    next.l2 = l2
    if (askedAt > 0 && num(input.answeredAt) > 0) {
      // 他答了：核对结束，重新计时。
      next.l2 = null
      return { rule: '', action: null, why: '', state: next }
    }
    if (askedAt === 0 && now - since >= e.L2Min * 60_000) {
      return {
        rule: 'L2',
        action: { type: 'confirm', pkg, minutes: 2 },
        why: '在局里用了没声明的应用 ' + e.L2Min + ' 分钟',
        state: { ...next, l2: { ...l2, askedAt: now } },
      }
    }
    if (askedAt > 0 && now - askedAt >= L2_GRACE_MS) {
      return {
        rule: 'L2',
        action: { type: 'lock-package', pkg },
        why: '问了 2 分钟没答，锁 ' + pkg,
        state: { ...next, l2: { ...l2, askedAt: 0, since: now } },
      }
    }
  } else if (next.l2) {
    next.l2 = null
  }

  return { rule: '', action: null, why: '', state: next }
}

/** 两条串一起跑：敷衍优先（它更贵），没有就看作弊。 */
export function evaluate(input) {
  const e = evaluateE(input)
  if (e.rule) return { ...e, track: 'E' }
  const l = evaluateL(input)
  return { ...l, track: l.rule ? 'L' : '', minutes: 0 }
}

/** 明天要不要切严格模板（E3 的次日代价）。 */
export function strictTemplate(state) {
  return Boolean(state && state.strictTomorrow)
}
