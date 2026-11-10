// dsh-go-companion · 暗思考（第六轮 C）
//
// 后台独立的一层，按固定节奏自己跑：他不在、他没发消息，也照样整理当下的**客观变化**，
// 产出「现场小结 / 下一步建议」，只进三次审查的素材。
//
// 四条硬边界（任务书 §2.2 写死的，别省）：
//   1. 不是"他说一句它才想一句" —— 不依赖任何用户消息，挂在与审查同一处被手机闹钟驱动的
//      planRoute 上（跟 anchorSweep 同一条路，不自建调度器）。
//   2. 不是另一个提醒 —— **只整理、不开口**。本文件不返回也不产生任何要发出去的话；
//      写进账本的只有 state:'muse'，那个 state 不在 summarize 的计数里，也不参与发球判定。
//   3. 不碰隐私红线 —— 手里只有合法存量：前台包名 / 在用时 / 切屏次数 / 亮屏 / 充电 / 时间。
//      **屏幕上的字一个都不进来**，这里也不接受任何文本字段。
//   4. 没新变化就不产出、也不打扰 —— 没有实质变化就直接跳过，不写空账。
//
// 「整理」是纯函数，不调模型：判据层不许模型自己写结论（红线 §3.10）。

/** 固定节奏：两次暗思考之间至少隔这么久。 */
export const MUSE_MIN_GAP_MS = 45 * 60_000

/** 每天上限：不许空转刷存在感。 */
export const MUSE_MAX_PER_DAY = 12

/** 今日时长涨了多少才算"有变化"。 */
const MINUTES_STEP = 5

/** 切屏涨了多少次才算"有变化"。 */
const SWITCH_STEP = 3

function int(v, max) {
  const n = Math.round(Number(v) || 0)
  return n > 0 ? Math.min(max, n) : 0
}

/**
 * 客观快照。只按白名单取字段——**任何文本内容都不进来**。
 * @returns {object|null} 手机什么都不知道就返回 null（不凭空造一条）
 */
export function museSignal(phone, now, tz) {
  if (!phone || typeof phone !== 'object') return null
  const today = []
  if (Array.isArray(phone.today)) {
    for (const t of phone.today.slice(0, 6)) {
      if (!t || typeof t !== 'object' || !t.app) continue
      today.push({ app: String(t.app).slice(0, 80), minutes: int(t.minutes, 1440) })
    }
  }
  const app = typeof phone.app === 'string' && phone.app ? String(phone.app).slice(0, 80) : ''
  const local = typeof phone.local === 'string' && /^\d{2}:\d{2}$/.test(phone.local) ? phone.local : ''
  if (!app && !today.length) return null
  const switches = phone.switches && typeof phone.switches === 'object'
    ? { m15: int(phone.switches.m15 ?? phone.switches.in15, 500), h1: int(phone.switches.h1 ?? phone.switches.in60, 500) }
    : { m15: 0, h1: 0 }
  return {
    at: now,
    local,
    app,
    minutes: int(phone.minutes, 1440),
    fun: phone.fun === true,
    screenOn: phone.screenOn !== false,
    charging: phone.charging === true,
    switches,
    today,
  }
}

function minutesOfApp(sig, app) {
  for (const t of sig.today || []) if (t.app === app) return t.minutes
  return 0
}

/**
 * 两次快照之间有没有实质变化。五条都是客观量，没有一条来自自述。
 * @returns {string[]} 变了哪些（空数组 = 没变化，不产出）
 */
export function museChanges(prev, next) {
  if (!next) return []
  if (!prev) return ['first'] // 今天第一次：本身就是一个可查的起点
  const why = []
  if (String(prev.app || '') !== String(next.app || '')) why.push('app')
  if (Boolean(prev.screenOn) !== Boolean(next.screenOn)) why.push('screen')
  // 任一 App 今日时长涨够一档
  for (const t of next.today || []) {
    if (t.minutes - minutesOfApp(prev, t.app) >= MINUTES_STEP) {
      why.push('minutes')
      break
    }
  }
  // 切屏变多（走神前兆的那两个窗口）
  if ((next.switches?.m15 || 0) - (prev.switches?.m15 || 0) >= SWITCH_STEP) why.push('switches')
  // 跨整点：时段推进本身是客观变化
  if (prev.local && next.local && prev.local.slice(0, 2) !== next.local.slice(0, 2)) why.push('hour')
  return why
}

/** 把一组变化说成人话。只陈述客观量，不评价人。 */
export function museDigest(next, why, { prev, tz } = {}) {
  if (!next) return null
  const parts = []
  const screen = next.screenOn ? '亮屏' : '熄屏'
  const charge = next.charging ? '、在充电' : ''
  if (next.app) parts.push('前台是 ' + next.app + (next.minutes ? '（已用 ' + next.minutes + ' 分钟）' : ''))
  parts.push(screen + charge)
  if (next.switches?.m15) parts.push('15 分钟内切了 ' + next.switches.m15 + ' 次')
  const summary = (next.local ? next.local + ' · ' : '') + parts.join('；')

  const nexts = []
  if (why.includes('app') && next.app) nexts.push('换了前台：' + next.app + '，审查时可以问这段时间拿到了什么。')
  if (why.includes('minutes')) {
    const pushed = (next.today || [])
      .map((t) => ({ app: t.app, gain: t.minutes - minutesOfApp(prev || { today: [] }, t.app) }))
      .filter((x) => x.gain >= MINUTES_STEP)
      .sort((a, b) => b.gain - a.gain)[0]
    if (pushed) nexts.push(pushed.app + ' 今天多用 ' + pushed.gain + ' 分钟，审查时按这个对账。')
  }
  if (why.includes('switches')) nexts.push('切屏变密，审查时把"是不是在躲一件事"作为一个待验假设。')
  if (why.includes('screen')) nexts.push(next.screenOn ? '屏幕亮了，可以按"开始做事"来看这一段。' : '屏幕熄了，这一段不产出投入。')
  if (why.includes('hour')) nexts.push('跨了一个整点，把上一个小时的客观量结一下。')
  if (!nexts.length) nexts.push('只记录了现场，没有值得提的推断。')
  return { summary, next: nexts.join(' '), why: why.slice() }
}

/**
 * 现在该不该跑一次。
 * @returns {{ due: boolean, prev: object|null, why: string }}
 */
export function museDue(entries, now, tz, { minGap = MUSE_MIN_GAP_MS, maxPerDay = MUSE_MAX_PER_DAY } = {}) {
  const dayStart = (() => {
    // 与 index.js 的 startOfDay 对齐：用当地零点那一天的开头，这里只做"当天"的粗判
    const d = new Date(now)
    return now - ((d.getHours() * 60 + d.getMinutes()) * 60 + d.getSeconds()) * 1000 - d.getMilliseconds()
  })()
  let last = null
  let count = 0
  for (const e of entries || []) {
    if (e?.state !== 'muse') continue
    if (Number(e.at) >= dayStart) count += 1
    if (!last || Number(e.at) >= Number(last.at)) last = e
  }
  if (count >= maxPerDay) return { due: false, prev: last?.signal || null, why: 'daily-cap' }
  if (last && now - Number(last.at) < minGap) return { due: false, prev: last?.signal || null, why: 'too-soon' }
  return { due: true, prev: last?.signal || null, why: '' }
}

/**
 * 一次暗思考。**只返回要写进账本的那一条**，不产生任何要发出去的话。
 * @returns {object|null} null = 没变化 / 不该跑 / 手机什么都没报
 */
export function museSweep(entries, phone, now, tz, opts = {}) {
  const gate = museDue(entries, now, tz, opts)
  if (!gate.due) return null
  const signal = museSignal(phone, now, tz)
  if (!signal) return null
  const why = museChanges(gate.prev, signal)
  if (!why.length) return null
  const digest = museDigest(signal, why, { prev: gate.prev, tz })
  if (!digest) return null
  return {
    state: 'muse',
    at: now,
    why: digest.why,
    summary: digest.summary.slice(0, 400),
    next: digest.next.slice(0, 400),
    signal,
  }
}

/** 今天暗思考跑了几次、最近一条说了什么（面板和验收用）。 */
export function museStatus(entries, now) {
  const d = new Date(now)
  const dayStart = now - ((d.getHours() * 60 + d.getMinutes()) * 60 + d.getSeconds()) * 1000 - d.getMilliseconds()
  let count = 0
  let last = null
  for (const e of entries || []) {
    if (e?.state !== 'muse' || Number(e.at) < dayStart) continue
    count += 1
    if (!last || Number(e.at) >= Number(last.at)) last = e
  }
  return {
    today: count,
    max: MUSE_MAX_PER_DAY,
    minGapMs: MUSE_MIN_GAP_MS,
    last: last ? { at: Number(last.at), summary: String(last.summary || ''), next: String(last.next || ''), why: last.why || [] } : null,
  }
}
