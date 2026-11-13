// dsh-go-companion · 行为片段与日摘要（行为大脑 K1）
//
// 每次 /plan 带来一份手机快照。这里把零散的快照压成「片段」：一段连续的行为——
// 哪个 App、几点到几点、当时在什么处境、因为什么开始、结束后发生了什么。
// 每天再压成一份日摘要。后面的模式发现（patterns.js）只读这两样，不读原始快照。
//
// 只记包名、时长、处境。**不记屏幕上的任何文字。**
// 纯函数：状态由调用方存取，时间、北京时间的日期和分钟由调用方给。

export const EPISODES_VERSION = 1

/** 同一 App 离开不到这么久又回来，算同一段。 */
export const MERGE_GAP_MS = 2 * 60_000
/**
 * 两次快照隔太久，上一段按最后看见 + 1 分钟收尾，不凭空延长。
 * 手机每次切 App、每次亮屏都会来问 /plan，所以「很久没报」通常是他一直待在同一个 App 里——
 * 这正是要记下来的长时间刷。门槛因此放得很宽：90 分钟没有任何报告才当成断了。
 */
export const STALE_GAP_MS = 90 * 60_000
/** 前因的时间窗。 */
export const WAKE_WINDOW_MS = 15 * 60_000
export const AFTER_BOUT_MS = 10 * 60_000
export const AFTER_LOCK_MS = 5 * 60_000
/** 一天里早于这个分钟数的第一次亮屏不算「醒来」（夜里起来看一眼）。 */
export const WAKE_EARLIEST_MIN = 5 * 60
/** 片段与摘要保留多久。 */
export const KEEP_SEG_DAYS = 21
export const KEEP_DIGEST_DAYS = 60
export const MAX_SEGS = 4000

/** 桌面与系统界面：不算一段行为。和 presets.js 的 NEUTRAL_PACKAGES 是同一张表。 */
const NEUTRAL = new Set([
  'android', 'com.android.systemui', 'com.miui.home', 'com.google.android.apps.nexuslauncher',
  'com.android.launcher3', 'com.oppo.launcher', 'com.huawei.android.launcher',
  'com.sec.android.app.launcher', 'com.vivo.launcher', 'com.bbk.launcher2',
])

const isPlain = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

export function emptyState() {
  return { v: EPISODES_VERSION, open: null, segs: [], digests: {}, marks: {}, bouts: {}, seq: 0 }
}

export function normalizeState(raw) {
  const s = emptyState()
  if (!isPlain(raw)) return s
  if (isPlain(raw.open)) s.open = { ...raw.open }
  if (Array.isArray(raw.segs)) s.segs = raw.segs.filter(isPlain).slice(-MAX_SEGS)
  for (const k of ['digests', 'marks', 'bouts']) if (isPlain(raw[k])) s[k] = { ...raw[k] }
  s.seq = num(raw.seq)
  return s
}

/** 这一刻算什么处境。 */
function contextOf(snap) {
  if (snap.night) return '深夜'
  if (snap.bout?.id) return '局内'
  return '无局'
}

/** 这一段因为什么开始。按最近发生的那件事算，只取一个。 */
function causeOf(state, snap, start) {
  const m = state.marks
  const candidates = []
  const firstSeen = num(m.firstSeen?.[snap.day])
  if (firstSeen && start - firstSeen <= WAKE_WINDOW_MS && num(m.firstSeenMin?.[snap.day], 0) >= WAKE_EARLIEST_MIN) {
    candidates.push({ cause: '刚醒', at: firstSeen })
  }
  if (num(m.lastBoutEndAt) && start - num(m.lastBoutEndAt) <= AFTER_BOUT_MS && start >= num(m.lastBoutEndAt)) {
    candidates.push({ cause: '刚收局', at: num(m.lastBoutEndAt) })
  }
  if (num(m.lastLockAt) && start - num(m.lastLockAt) <= AFTER_LOCK_MS && start >= num(m.lastLockAt)) {
    candidates.push({ cause: '刚被锁', at: num(m.lastLockAt) })
  }
  if (!candidates.length) return ''
  candidates.sort((a, b) => b.at - a.at)
  return candidates[0].cause
}

function closeOpen(state, end) {
  const o = state.open
  if (!o) return null
  const finish = Math.max(num(o.start), Math.min(num(end), num(o.lastSeen) + STALE_GAP_MS))
  const seg = {
    id: o.id, day: o.day, app: o.app, fun: o.fun === true, start: num(o.start), end: finish,
    min: Math.round((finish - num(o.start)) / 6_000) / 10, context: o.context, cause: o.cause,
    effect: o.locked ? '被锁' : '', boutId: o.boutId || '',
  }
  state.segs.push(seg)
  state.open = null
  state.marks.lastClosed = { id: seg.id, end: seg.end, fun: seg.fun, app: seg.app }
  return seg
}

/** 上一段结束后紧跟着发生了什么，回填到那一段的 effect 上（只填一次）。 */
function backfillEffect(state, effect, at) {
  const last = state.marks.lastClosed
  if (!last || at - num(last.end) > AFTER_LOCK_MS) return
  const seg = [...state.segs].reverse().find((s) => s.id === last.id)
  if (seg && !seg.effect) seg.effect = effect
}

/**
 * 喂一份快照。
 * @param {object} raw
 * @param {{now:number, day:string, minute:number, app?:string|null, fun?:boolean, minutes?:number,
 *          screenOn?:boolean, bout?:{id:string, protocolId?:string}|null, night?:boolean,
 *          todayFun?:number}} snap
 * @returns {{state:object, closed:object[], opened:object|null}}
 */
export function observe(raw, snap) {
  const state = normalizeState(raw)
  const now = num(snap.now)
  const day = String(snap.day || '')
  const closed = []
  let opened = null

  // 当天第一次看见手机：用来判「刚醒」。
  if (snap.screenOn !== false && day) {
    state.marks.firstSeen = isPlain(state.marks.firstSeen) ? { ...state.marks.firstSeen } : {}
    state.marks.firstSeenMin = isPlain(state.marks.firstSeenMin) ? { ...state.marks.firstSeenMin } : {}
    if (!state.marks.firstSeen[day]) {
      state.marks.firstSeen[day] = now
      state.marks.firstSeenMin[day] = num(snap.minute)
    }
  }
  if (typeof snap.todayFun === 'number' && day) {
    state.marks.todayFun = isPlain(state.marks.todayFun) ? { ...state.marks.todayFun } : {}
    state.marks.todayFun[day] = Math.max(num(state.marks.todayFun[day]), snap.todayFun)
  }

  // 局开始 / 结束。
  const boutId = snap.bout?.id || ''
  if (boutId !== (state.marks.boutId || '')) {
    if (state.marks.boutId) state.marks.lastBoutEndAt = now
    if (boutId) backfillEffect(state, '开局', now)
    state.marks.boutId = boutId
  }

  // 太久没报：上一段按最后看见收尾。
  if (state.open && now - num(state.open.lastSeen) > STALE_GAP_MS) {
    const s = closeOpen(state, num(state.open.lastSeen) + 60_000)
    if (s) closed.push(s)
  }

  const app = snap.screenOn === false ? null : snap.app || null
  const counted = app && !NEUTRAL.has(app)
  if (!counted) {
    if (state.open) {
      const s = closeOpen(state, now)
      if (s) closed.push(s)
    }
  } else if (state.open && state.open.app === app) {
    state.open.lastSeen = now
    if (snap.fun === true) state.open.fun = true
  } else {
    if (state.open) {
      const s = closeOpen(state, now)
      if (s) closed.push(s)
    }
    const last = state.marks.lastClosed
    // 同一个 App 离开不到 2 分钟又回来：接着那一段，不另起（中间切去回了个微信也算）。
    let resume = -1
    for (let i = state.segs.length - 1; i >= 0; i -= 1) {
      const x = state.segs[i]
      if (now - num(x.end) > MERGE_GAP_MS) break
      if (x.app === app) {
        resume = i
        break
      }
    }
    if (resume >= 0) {
      const seg = state.segs.splice(resume, 1)[0]
      state.open = { id: seg.id, day: seg.day, app, fun: seg.fun || snap.fun === true, start: seg.start, lastSeen: now,
        context: seg.context, cause: seg.cause, boutId: seg.boutId, locked: seg.effect === '被锁' }
    } else {
      if (snap.fun === true && last?.fun) backfillEffect(state, '换娱乐', now)
      const reported = Math.max(0, num(snap.minutes)) * 60_000
      const floor = last ? num(last.end) : now - reported
      const start = Math.max(floor, now - reported)
      state.seq += 1
      state.open = {
        id: 'S' + day.replace(/-/g, '') + '-' + state.seq, day, app, fun: snap.fun === true,
        start, lastSeen: now, context: contextOf(snap), cause: causeOf(state, snap, start), boutId,
      }
      opened = { ...state.open }
    }
  }
  return { state, closed, opened }
}

/** 被锁了：正开着的那一段记「被锁」，接下来 5 分钟里开始的片段前因是「刚被锁」。 */
export function noteLock(raw, at) {
  const state = normalizeState(raw)
  state.marks.lastLockAt = num(at)
  if (state.open) state.open.locked = true
  const day = String(state.open?.day || state.marks.lastDay || '')
  state.marks.locks = isPlain(state.marks.locks) ? { ...state.marks.locks } : {}
  if (day) state.marks.locks[day] = num(state.marks.locks[day]) + 1
  return state
}

/**
 * 记一局。outcome：open（开了）/ done（正常收局）/ bad（升级、超时、空局）。
 * self=true 表示他自己开的（面板开局），否则是计划或系统开的。
 */
export function noteBout(raw, { day, protocolId, outcome, self = false }) {
  const state = normalizeState(raw)
  const d = String(day || '')
  const p = String(protocolId || 'unknown')
  if (!d) return state
  const days = isPlain(state.bouts[d]) ? { ...state.bouts[d] } : {}
  const row = isPlain(days[p]) ? { ...days[p] } : { opened: 0, self: 0, done: 0, bad: 0 }
  if (outcome === 'open') {
    row.opened += 1
    if (self) row.self += 1
  } else if (outcome === 'done') row.done += 1
  else if (outcome === 'bad') row.bad += 1
  days[p] = row
  state.bouts[d] = days
  return state
}

/** 某一天的片段（含还开着的那一段，按现在截断）。 */
export function segsOf(state, day, now) {
  const s = normalizeState(state)
  const out = s.segs.filter((x) => x.day === day)
  if (s.open && s.open.day === day && now) {
    out.push({ ...s.open, end: Math.max(num(s.open.start), Math.min(now, num(s.open.lastSeen))), effect: '' })
  }
  return out
}

/**
 * 一天的摘要。娱乐分钟优先用手机自己报的当天累计（更准），片段只用来分小时。
 * @param {object} state
 * @param {string} day
 * @param {{now?:number, hourOf:(ms:number)=>number}} opts  hourOf 把时间戳换成北京时间的小时
 */
export function digestOf(state, day, opts) {
  const s = normalizeState(state)
  const segs = segsOf(s, day, opts.now)
  const funByHour = Array(24).fill(0)
  const apps = {}
  let segFun = 0
  for (const seg of segs) {
    if (!seg.fun) continue
    const minutes = Math.max(0, (num(seg.end) - num(seg.start)) / 60_000)
    segFun += minutes
    apps[seg.app] = (apps[seg.app] || 0) + minutes
    // 跨小时的片段按分钟拆到各个小时。
    let t = num(seg.start)
    while (t < num(seg.end)) {
      const h = opts.hourOf(t)
      const next = Math.min(num(seg.end), t + (60 - new Date(t).getUTCMinutes()) * 60_000 - new Date(t).getUTCSeconds() * 1000)
      const step = Math.max(1_000, next - t)
      if (h >= 0 && h < 24) funByHour[h] += step / 60_000
      t += step
    }
  }
  const bouts = isPlain(s.bouts[day]) ? s.bouts[day] : {}
  const tally = { opened: 0, self: 0, done: 0, bad: 0 }
  for (const row of Object.values(bouts)) for (const k of Object.keys(tally)) tally[k] += num(row[k])
  const top = Object.entries(apps).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([app, m]) => ({ app, min: Math.round(m) }))
  return {
    day,
    funMin: Math.round(Math.max(segFun, num(s.marks.todayFun?.[day]))),
    funByHour: funByHour.map((m) => Math.round(m)),
    topFun: top,
    segs: segs.length,
    funSegs: segs.filter((x) => x.fun).length,
    locks: num(s.marks.locks?.[day]),
    bouts: { ...tally, byProtocol: { ...bouts } },
    // 四个数里能从这里算的三个；交付率要等验收接进来（第三项）。
    selfOpenRatio: tally.opened ? Math.round((tally.self / tally.opened) * 100) / 100 : null,
    badRatio: tally.opened ? Math.round((tally.bad / tally.opened) * 100) / 100 : null,
  }
}

/** 换天时把昨天的摘要定稿；顺手清掉太旧的片段和摘要。 */
export function rollDay(raw, { day, now, hourOf, dayMs = 86_400_000 }) {
  const state = normalizeState(raw)
  const prev = state.marks.lastDay
  state.marks.lastDay = day
  if (!prev || prev === day) return { state, finalized: null }
  const finalized = digestOf(state, prev, { now, hourOf })
  state.digests[prev] = finalized
  const segCut = num(now) - KEEP_SEG_DAYS * dayMs
  state.segs = state.segs.filter((x) => num(x.end) >= segCut).slice(-MAX_SEGS)
  const keys = Object.keys(state.digests).sort()
  for (const k of keys.slice(0, Math.max(0, keys.length - KEEP_DIGEST_DAYS))) delete state.digests[k]
  for (const k of ['firstSeen', 'firstSeenMin', 'todayFun', 'locks']) {
    if (!isPlain(state.marks[k])) continue
    const ks = Object.keys(state.marks[k]).sort()
    for (const x of ks.slice(0, Math.max(0, ks.length - KEEP_DIGEST_DAYS))) delete state.marks[k][x]
  }
  const bk = Object.keys(state.bouts).sort()
  for (const x of bk.slice(0, Math.max(0, bk.length - KEEP_DIGEST_DAYS))) delete state.bouts[x]
  return { state, finalized }
}
