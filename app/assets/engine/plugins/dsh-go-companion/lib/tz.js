// dsh-go-companion · 时区
//
// 引擎是用 `TZ=GMT` 启动的，但「安静时段」「过零点重算日上限」都必须按**手机时区**算，
// 否则真机上两个窗口都偏 8 小时。所以这里所有函数都要求显式传时区，
// 不读进程时区——不然又会被 TZ 悄悄带偏。

export const DEFAULT_TZ = 'Asia/Shanghai'

const formatters = new Map()

function formatter(tz) {
  const hit = formatters.get(tz)
  if (hit) return hit
  const made = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    hour12: false,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
  formatters.set(tz, made)
  return made
}

/** 合法时区名就用它，非法或空就用默认；用户传什么都不会把插件打崩。 */
export function zoneOr(input) {
  const tz = typeof input === 'string' ? input.trim() : ''
  if (!tz) return DEFAULT_TZ
  try {
    formatter(tz)
    return tz
  } catch {
    return DEFAULT_TZ
  }
}

function parts(now, tz) {
  const out = {}
  for (const p of formatter(zoneOr(tz)).formatToParts(new Date(now))) out[p.type] = p.value
  // 某些时区会给出 24 点，归一到 0
  return {
    y: Number(out.year),
    mo: Number(out.month),
    d: Number(out.day),
    h: Number(out.hour) % 24,
    mi: Number(out.minute),
    s: Number(out.second),
  }
}

/** 目标时区下的「今天第几分钟」，0–1439。 */
export function minuteOfDay(now, tz) {
  const p = parts(now, tz)
  return p.h * 60 + p.mi
}

const pad2 = (n) => String(n).padStart(2, '0')

/**
 * 目标时区下的 `HH:MM`。R7 P0-2：提示词里所有时刻都走这里。
 * 原来各处是把 ISO 串切片再拼上时区名（UTC），给模型的是 UTC 时刻，
 * 跟用户看的北京时间差 8 小时，这是她「说错时间」的直接来源。
 */
export function hm(now, tz = DEFAULT_TZ) {
  const p = parts(now, tz)
  return pad2(p.h) + ':' + pad2(p.mi)
}

/** 目标时区下的 `MM-DD`（连麦复盘那种「11-11 21:00」）。 */
export function md(now, tz = DEFAULT_TZ) {
  const p = parts(now, tz)
  return pad2(p.mo) + '-' + pad2(p.d)
}

/** 目标时区下的 `YYYY-MM-DD`。 */
export function ymd(now, tz = DEFAULT_TZ) {
  const p = parts(now, tz)
  return p.y + '-' + pad2(p.mo) + '-' + pad2(p.d)
}

// 该时刻目标时区相对 UTC 的偏移（毫秒）。秒级精度足够，调用方都会先归整到秒。
function offsetAt(now, tz) {
  const p = parts(now, tz)
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - Math.floor(now / 1000) * 1000
}

// shortcut: 用「本地零点 + n 小时」推时刻，有夏令时的时区在切换当天会差 1 小时；
// 默认的 Asia/Shanghai 无夏令时，真要支持夏令时区再换成逐日校准。
export function startOfDay(now, tz) {
  const p = parts(now, tz)
  return Date.UTC(p.y, p.mo - 1, p.d) - offsetAt(now, tz)
}

/**
 * 目标时区下的「日号」：1970-01-01 起的天数，按日历年月日算。
 * 不能用 startOfDay()/86400000 —— 那是「当地零点的瞬时」，UTC+8 会落到前一天的 UTC 日号上，
 * 拿它算周界会整体错一天（周一只好算作上一周）。
 */
export function dayNumber(now, tz) {
  const p = parts(now, tz)
  return Math.floor(Date.UTC(p.y, p.mo - 1, p.d) / 86_400_000)
}

/** 目标时区下「下一个（或今天还没到的）hour 点整」。 */
export function atLocalHour(now, hour, tz) {
  const h = Math.min(23, Math.max(0, Math.round(Number(hour) || 0)))
  const t = startOfDay(now, tz) + h * 3_600_000
  return t <= now ? t + 86_400_000 : t
}

/** 目标时区下的星期：0=周日 … 6=周六（dayNumber 的 0 是 1970-01-01，周四）。 */
export function weekdayOf(now, tz) {
  return ((dayNumber(now, tz) + 4) % 7 + 7) % 7
}
