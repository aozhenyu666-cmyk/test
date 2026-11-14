// dsh-go-companion · 模式发现（行为大脑 K2）
//
// 每晚在最近 14 天的日摘要和片段上跑一组探测器。探测器是代码，不是模型：
// 数字必须准、可复核。模型（鲸澜）只在模式卡上写解释，而且解释要引用这里给出的证据编号。
//
// 每个候选都带：类型、一句话标题、统计数字、证据（哪几天、哪几段）、等级。
//   confirmed：过了门槛（默认 14 天里至少 4 天，强度是基线的 2 倍以上）
//   watch    ：出现了但还没过门槛（至少 2 天），先观察
//
// 这一版四个探测器：时段习惯、前因链、绕路、逃避。以后加探测器就在 DETECTORS 里加一个函数。

export const DEFAULTS = Object.freeze({
  lookbackDays: 14,
  minDays: 4,
  watchDays: 2,
  strength: 2,
  hourMinutes: 15,
  causeRatio: 0.6,
  causeMinCount: 4,
  detourRatio: 0.5,
  detourMinCount: 4,
  avoidMinOpened: 4,
  avoidBadRatio: 0.5,
})

const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)
const hh = (h) => String(h).padStart(2, '0') + ':00'

/** 最近 N 天的摘要（按日期排序）。 */
function recentDigests(digests, today, lookbackDays) {
  const days = Object.keys(digests || {}).filter((d) => d < today).sort().slice(-lookbackDays)
  return days.map((d) => digests[d]).filter(Boolean)
}

// ---------------------------------------------------------------- 探测器

/**
 * 时段习惯：某个小时里反复刷娱乐。相邻的小时合成一个时段。
 * 基线 = 有数据的那些天里，每小时娱乐分钟的平均值。
 */
function hourHabit({ digests, opts }) {
  const n = digests.length
  if (!n) return []
  let total = 0
  for (const d of digests) for (const m of d.funByHour || []) total += num(m)
  const baseline = total / (n * 24)
  const hits = []
  for (let h = 0; h < 24; h += 1) {
    const days = digests.filter((d) => num(d.funByHour?.[h]) >= opts.hourMinutes)
    if (days.length < opts.watchDays) continue
    const avg = days.reduce((s, d) => s + num(d.funByHour[h]), 0) / days.length
    hits.push({ h, days: days.map((d) => d.day), avg })
  }
  // 相邻小时合并。
  const groups = []
  for (const x of hits) {
    const g = groups[groups.length - 1]
    if (g && g.to === x.h) {
      g.to = x.h + 1
      g.items.push(x)
    } else groups.push({ from: x.h, to: x.h + 1, items: [x] })
  }
  return groups.map((g) => {
    const days = [...new Set(g.items.flatMap((x) => x.days))].sort()
    const avg = Math.round(g.items.reduce((s, x) => s + x.avg, 0) / g.items.length)
    const ratio = baseline > 0 ? avg / baseline : Infinity
    const strongDays = Math.min(...g.items.map((x) => x.days.length))
    const confirmed = strongDays >= opts.minDays && ratio >= opts.strength
    return {
      key: 'hour:' + g.from + '-' + g.to,
      type: '时段习惯',
      title: hh(g.from) + '–' + hh(g.to) + ' 刷娱乐：最近 ' + n + ' 天里 ' + strongDays + ' 天，平均每小时 ' + avg + ' 分钟',
      stats: { from: g.from, to: g.to, days: strongDays, ofDays: n, avgMin: avg, baselineMin: Math.round(baseline * 10) / 10, ratio: Number.isFinite(ratio) ? Math.round(ratio * 10) / 10 : null },
      evidence: { days },
      level: confirmed ? 'confirmed' : 'watch',
    }
  })
}

/**
 * 前因链：某件事之后常接着刷娱乐。
 * 「刚醒」的机会 = 有数据的天数；「刚收局」的机会 = 收局次数。
 */
function causeChain({ digests, segs, opts }) {
  const out = []
  const days = new Set(digests.map((d) => d.day))
  const inRange = segs.filter((s) => days.has(s.day))
  const closes = digests.reduce((s, d) => s + num(d.bouts?.done) + num(d.bouts?.bad), 0)
  for (const [cause, chances, unit] of [['刚醒', digests.length, '天'], ['刚收局', closes, '次收局']]) {
    const hit = inRange.filter((s) => s.fun && s.cause === cause)
    // 同一天同一前因只算一次（醒来只有一次；收局后连刷两个 App 算一次）。
    const keyOf = (s) => (cause === '刚醒' ? s.day : s.day + '@' + Math.floor(num(s.start) / 600_000))
    const events = [...new Map(hit.map((s) => [keyOf(s), s])).values()]
    if (events.length < opts.watchDays || !chances) continue
    const ratio = events.length / chances
    const confirmed = events.length >= opts.causeMinCount && ratio >= opts.causeRatio
    out.push({
      key: 'cause:' + cause,
      type: '前因链',
      title: cause + '就刷娱乐：' + chances + ' ' + unit + '里 ' + events.length + ' 次',
      stats: { cause, count: events.length, chances, ratio: Math.round(ratio * 100) / 100 },
      evidence: { days: [...new Set(events.map((s) => s.day))].sort(), segs: events.slice(-10).map((s) => s.id) },
      level: confirmed ? 'confirmed' : 'watch',
    })
  }
  return out
}

/** 绕路：被锁之后 5 分钟内又打开了娱乐。 */
function detour({ digests, segs, opts }) {
  const days = new Set(digests.map((d) => d.day))
  const locks = digests.reduce((s, d) => s + num(d.locks), 0)
  const hit = segs.filter((s) => days.has(s.day) && s.fun && s.cause === '刚被锁')
  if (hit.length < opts.watchDays || !locks) return []
  const ratio = hit.length / locks
  const confirmed = hit.length >= opts.detourMinCount && ratio >= opts.detourRatio
  return [{
    key: 'detour',
    type: '绕路',
    title: '被锁之后换个 App 接着刷：' + locks + ' 次被锁里 ' + hit.length + ' 次',
    stats: { count: hit.length, locks, ratio: Math.round(ratio * 100) / 100, apps: [...new Set(hit.map((s) => s.app))].slice(0, 5) },
    evidence: { days: [...new Set(hit.map((s) => s.day))].sort(), segs: hit.slice(-10).map((s) => s.id) },
    level: confirmed ? 'confirmed' : 'watch',
  }]
}

/** 逃避：某一种局反复空、反复被升级，明显比别的局差。 */
function avoidance({ digests, opts }) {
  const by = {}
  for (const d of digests) {
    for (const [p, row] of Object.entries(d.bouts?.byProtocol || {})) {
      const t = by[p] || { opened: 0, bad: 0, days: [] }
      t.opened += num(row.opened)
      t.bad += num(row.bad)
      if (num(row.bad)) t.days.push(d.day)
      by[p] = t
    }
  }
  const out = []
  for (const [p, t] of Object.entries(by)) {
    if (t.opened < opts.watchDays) continue
    const rate = t.bad / t.opened
    let otherOpened = 0
    let otherBad = 0
    for (const [q, u] of Object.entries(by)) if (q !== p) { otherOpened += u.opened; otherBad += u.bad }
    const otherRate = otherOpened ? otherBad / otherOpened : 0
    if (rate < opts.avoidBadRatio / 2) continue
    const confirmed = t.opened >= opts.avoidMinOpened && rate >= opts.avoidBadRatio && (otherRate === 0 || rate >= opts.strength * otherRate)
    out.push({
      key: 'avoid:' + p,
      type: '逃避',
      title: p + ' 局空得多：开了 ' + t.opened + ' 次，' + t.bad + ' 次没做成（其他局 ' + Math.round(otherRate * 100) + '%）',
      stats: { protocol: p, opened: t.opened, bad: t.bad, rate: Math.round(rate * 100) / 100, otherRate: Math.round(otherRate * 100) / 100 },
      evidence: { days: [...new Set(t.days)].sort() },
      level: confirmed ? 'confirmed' : 'watch',
    })
  }
  return out
}

export const DETECTORS = Object.freeze({ 时段习惯: hourHabit, 前因链: causeChain, 绕路: detour, 逃避: avoidance })

/**
 * 跑全部探测器。
 * @param {{digests:object, segs:object[], today:string, opts?:object}} input
 * @returns {object[]} 候选列表
 */
export function detect(input) {
  const opts = { ...DEFAULTS, ...(input.opts || {}) }
  const digests = recentDigests(input.digests, input.today, opts.lookbackDays)
  const segs = Array.isArray(input.segs) ? input.segs : []
  const out = []
  for (const fn of Object.values(DETECTORS)) out.push(...fn({ digests, segs, opts }))
  return out
}
