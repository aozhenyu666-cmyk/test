// dsh-go-companion · 挣来的娱乐（第九轮第四项）
//
// 把「今天能不能玩」从监狱改成交换：每通过一局的验收，当天多一段放风额度；
// 额度用完之前，非工作时段的娱乐不受默认管束。
//
// 三条边界，都是这一层的存在理由：
//   1. 只松「默认」那一条。E1/E2/L 系列管的是当下这一局里的敷衍，照旧——挣来的额度不是免罪符。
//   2. 只在非工作时段生效。工作时段照旧按墙的规则走，不然「挣额度」等于把白天让出去。
//   3. 一天一个账，不跨天累加、不存负值。
//
// 纯函数：时间、设定、日志都由调用方给。

/** 每通过一局默认给多少分钟。设定页可改（config.earnMinPerBout）。 */
export const EARN_PER_PASS = 15
/** 一次最多给多少分钟：他改成 600 也不会变成「玩一整天」。 */
export const EARN_MAX_PER_PASS = 60
/** 一天最多挣多少分钟：再多也不该把一整天买下来。 */
export const EARN_MAX_PER_DAY = 240

/** 今天的空账。 */
export function empty(day) {
  return { day: Number(day) || 0, earned: 0, spent: 0 }
}

/** 每局给几分钟：设定里的数，没写用默认，越界夹住。 */
export function rateOf(config) {
  const n = Math.round(Number(config?.earnMinPerBout))
  if (!Number.isFinite(n) || n <= 0) return EARN_PER_PASS
  return Math.max(1, Math.min(EARN_MAX_PER_PASS, n))
}

/** 换天了就换一张空账：昨天没玩掉的额度不带进今天。 */
export function ofDay(state, day) {
  const d = Number(day) || 0
  const s = state && typeof state === 'object' ? state : null
  if (!s || Number(s.day) !== d) return empty(d)
  return {
    day: d,
    earned: Math.max(0, Math.min(EARN_MAX_PER_DAY, Math.round(Number(s.earned) || 0))),
    spent: Math.max(0, Math.round(Number(s.spent) || 0)),
  }
}

/** 还剩几分钟能玩。 */
export function leftOf(state) {
  const s = state && typeof state === 'object' ? state : null
  if (!s) return 0
  return Math.max(0, Math.round(Number(s.earned) || 0) - Math.round(Number(s.spent) || 0))
}

/** 通过一局：记一笔额度。到上限就不再加。 */
export function addForPass(state, config, now, day) {
  const s = ofDay(state, day)
  const add = rateOf(config)
  const earned = Math.min(EARN_MAX_PER_DAY, s.earned + add)
  return { ...s, earned, lastAt: Number(now) || 0, lastAdd: earned > s.earned ? add : 0 }
}

/**
 * 花掉几分钟。返回花完之后的账和实际花掉的分钟数（不够就花光为止）。
 * @returns {{state:object, spent:number}}
 */
export function spend(state, minutes) {
  const s = state && typeof state === 'object' ? state : empty(0)
  const want = Math.max(0, Math.round(Number(minutes) || 0))
  const can = Math.min(want, leftOf(s))
  return { state: { ...s, spent: s.spent + can }, spent: can }
}

/**
 * 现在这段娱乐算不算「挣来的」。
 * 非工作时段 + 还有额度，两条都成立才算——工作时段不给这个口子。
 * @param {{state:object, settings:object, kind:string, min:number, inWork:function}} input
 *        inWork：判断某个分钟在不在工作时段（settings.js 的 inWorkWindow）
 * @returns {boolean}
 */
export function covers(input) {
  const left = leftOf(input?.state)
  if (left <= 0) return false
  // 判不了工作时段就不松：宁可少放一次，不能把白天让出去。
  if (typeof input?.inWork !== 'function') return false
  if (input.inWork(input.settings, input.kind, Number(input.min) || 0)) return false
  return true
}

/** 面板/日报上看的那一句。 */
export function lineOf(state) {
  const left = leftOf(state)
  const s = state || {}
  return left > 0
    ? '今天挣到 ' + Math.round(Number(s.earned) || 0) + ' 分钟放风，还剩 ' + left + ' 分钟'
    : '今天还没有挣到的放风（每通过一局 +' + EARN_PER_PASS + ' 分钟）'
}
