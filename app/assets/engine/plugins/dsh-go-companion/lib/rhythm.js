// dsh-go-companion · 作息（早上对线、深夜管控、晚上对账）
//
// 早上：5 点到中午之间第一次亮屏，娱乐先锁着（社交不锁），鲸澜要他说出今天最重要的 3 件事和各自几点前做完；
//       他认真回一句就"通电"。最多锁 30 分钟（兜底）。10 点不再是上限；中午以后算白天，不再问"今天的 3 件事"。
// 深夜：23:30 起她们更勤地催他睡；0:30 以后娱乐锁到早上 7 点（安静时段她们不说话，但锁照样在）。
// 晚上：21:30 鲸澜对账（今天做了什么、手机花在哪、明天封什么），没对完娱乐锁着，最多 60 分钟。
// 时间都按他手机的当地时间。

export const MORNING_FROM = 5 * 60
export const MORNING_TO = 12 * 60
export const MORNING_CAP_MS = 30 * 60_000
export const LATE_FROM = 23 * 60 + 30
export const NIGHT_LOCK_FROM = 30
export const NIGHT_LOCK_TO = 7 * 60
export const REVIEW_FROM = 21 * 60 + 30
export const REVIEW_CAP_MS = 60 * 60_000
/** 锁是滚动的：每次 /plan 往后顺这么久，门一关最多晚这么久解锁。 */
export const ROLL_MS = 3 * 60_000

/** 当地几点几分（分钟数）：手机带来的优先，没有就按时区算。 */
export function localMinute(phone, fallback) {
  if (phone?.local && /^\d{2}:\d{2}$/.test(phone.local)) return Number(phone.local.slice(0, 2)) * 60 + Number(phone.local.slice(3, 5))
  return fallback
}

/** 深夜阶段：late（催）/ lock（锁）/ null。 */
export function nightPhase(min) {
  if (min >= LATE_FROM || min < NIGHT_LOCK_FROM) return 'late'
  if (min >= NIGHT_LOCK_FROM && min < NIGHT_LOCK_TO) return 'lock'
  return null
}

/**
 * 每次 /plan 更新一次作息状态（存在 companion/rhythm.json）。
 * @returns {{state:object, morningGate:boolean, reviewGate:boolean, night:string|null, openMorning:boolean, openReview:boolean}}
 */
export function trackRhythm(state, { min, day, now, screenOn, reviewedToday }) {
  const s = { ...(state || {}) }
  let openMorning = false
  let openReview = false
  // 5 点到中午的第一次亮屏都算早上（不再卡 10 点）；一天只开一次。
  // 中午以后由忙时/对账/巡逻接手，不把下午第一屏当成"晨间对线"。
  if (min >= MORNING_FROM && min < MORNING_TO && screenOn && s.morningDay !== day) {
    Object.assign(s, { morningDay: day, morningSince: now, morningDone: false, morningBall: false })
    openMorning = true
  }
  if (min >= REVIEW_FROM && min < LATE_FROM && s.reviewDay !== day && !reviewedToday) {
    Object.assign(s, { reviewDay: day, reviewSince: now, reviewDone: false, reviewBall: false })
    openReview = true
  }
  const morningGate = s.morningDay === day && !s.morningDone && now - Number(s.morningSince) < MORNING_CAP_MS
  const reviewGate = s.reviewDay === day && !s.reviewDone && now - Number(s.reviewSince) < REVIEW_CAP_MS && min >= REVIEW_FROM
  return { state: s, morningGate, reviewGate, night: nightPhase(min), openMorning, openReview }
}

/**
 * 关掉某道门。第三轮由裁决（submit_verdict）或超时调用，不再按他说了多少字。
 * @returns {{state:object, closed:boolean}}
 */
export function closeGate(state, gate) {
  const s = { ...(state || {}) }
  if (gate === 'morning' && s.morningDay && !s.morningDone) {
    s.morningDone = true
    return { state: s, closed: true }
  }
  if (gate === 'review' && s.reviewDay && !s.reviewDone) {
    s.reviewDone = true
    return { state: s, closed: true }
  }
  return { state: s, closed: false }
}

/** 拼进两人的聊天人设。 */
export function rhythmLines(r) {
  if (!r) return []
  const lines = []
  if (r.morningGate) lines.push('现在是早上的对线：他得先说出今天最重要的 3 件事、各自几点前做完，说完娱乐才解锁。他想绕过去就把他拉回来。')
  if (r.reviewGate) lines.push('现在是晚上对账时间：今天做了什么、手机时间花在哪、明天封什么。对完之前娱乐锁着。')
  if (r.night === 'late') lines.push('已经过了 23:30：催他放下手机去睡，每句话都往"睡觉"上引，别跟他聊兴奋的话题。')
  if (r.night === 'lock') lines.push('现在是深夜（0:30 以后）：娱乐锁到早上 7 点。他还醒着就让他交出手机去睡，短、硬、不聊别的。')
  return lines
}
