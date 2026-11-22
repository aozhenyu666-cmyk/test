// dsh-go-companion · 水位（他陷得多深）
//
// 不是定时器，是一个连续的量。他的意志像水，往阻力最低处流；定时任务是水泵，抽一下停一下。
// 这里要的是一条一直在的水道：陷得越深，开口越勤、语气越硬、手越重；回到清醒就退回去。
//
// 水位只用手边已有的事实算：在娱乐 App 里待了多久、今天一共刷了多久、连着几球没接、
// 连着几球答得糊弄、是不是深夜。全部本地、零模型。

import { funToday } from './phone.js'

/** 四档水位。 */
export const BANDS = [
  { band: 'clear', from: 0, label: '清醒' },
  { band: 'slide', from: 25, label: '在滑' },
  { band: 'sink', from: 50, label: '在沉' },
  { band: 'trap', from: 75, label: '陷住了' },
]

/** 判成"糊弄"的回球：短、带走、套话。连着几次，说明进了凭感觉说话的状态。 */
const VAGUE = new Set(['brief', 'evasive', 'stock'])

function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, n))
}

/** 最近几次回球里，从新往旧连着几次是糊弄。 */
export function vagueStreak(entries) {
  let n = 0
  for (let i = (entries?.length || 0) - 1; i >= 0; i -= 1) {
    const e = entries[i]
    if (!e || e.state !== 'replied') continue
    if (VAGUE.has(String(e.verdict || ''))) n += 1
    else break
  }
  return n
}

/**
 * 算水位。
 *
 * @param {{phone?: object|null, noTake?: number, vague?: number, hour?: number}} input
 * @returns {{level: number, band: string, label: string, why: string[]}}
 */
export function tide({ phone, noTake = 0, vague = 0, hour } = {}) {
  const why = []
  let level = 0
  if (phone?.fun && phone.minutes > 0) {
    // 连续待着最要命：前 10 分钟涨得慢，之后每分钟都在往下陷。
    const dwell = phone.minutes <= 10 ? phone.minutes * 1.5 : 15 + (phone.minutes - 10) * 1.2
    level += clamp(dwell, 0, 55)
    why.push(phone.label + ' 连续 ' + phone.minutes + ' 分钟')
  }
  const total = funToday(phone)
  if (total > 30) {
    level += clamp((total - 30) / 6, 0, 25)
    why.push('今天娱乐类 ' + total + ' 分钟')
  }
  if (noTake > 0) {
    level += clamp(noTake * 8, 0, 20)
    why.push('连着 ' + noTake + ' 球没接')
  }
  if (vague > 0) {
    level += clamp(vague * 7, 0, 20)
    why.push('连着 ' + vague + ' 次答得糊弄')
  }
  const h = Number(hour)
  if (Number.isFinite(h) && (h >= 23 || h < 2) && phone?.fun) {
    level += 10
    why.push('深夜')
  }
  // 屏幕灭了、或者不在娱乐 App 里：水在退，最多留一点余温。
  if (phone && (!phone.screenOn || !phone.fun)) level = Math.min(level, 40)
  level = Math.round(clamp(level, 0, 100))
  let pick = BANDS[0]
  for (const b of BANDS) if (level >= b.from) pick = b
  return { level, band: pick.band, label: pick.label, why }
}

/**
 * 水位决定节奏：清醒时按模式本来的间隔，越陷越勤。乘在 minGap 上。
 * 再乘一个 0.6–1.4 的随机数：间隔不该被他摸出规律（可变间隔比固定间隔难忽视）。
 */
export const BAND_GAP = { clear: 1, slide: 0.5, sink: 0.25, trap: 0.12 }
/** 每小时上限也跟着水位放开：陷住的时候正是该连着打的时候。 */
export const BAND_HOURLY = { clear: 1, slide: 1.5, sink: 2.5, trap: 4 }

export function tideGap(minGapMs, band, roll = Math.random()) {
  const base = Math.max(0, Number(minGapMs) || 0) * (BAND_GAP[band] ?? 1)
  const jitter = 0.6 + clamp(Number(roll) || 0, 0, 1) * 0.8
  return Math.round(Math.max(base > 0 ? 90_000 : 0, base * jitter))
}

export function tideHourly(hourlyMax, band) {
  const n = Math.max(0, Number(hourlyMax) || 0)
  if (!n) return 0
  return Math.round(n * (BAND_HOURLY[band] ?? 1))
}
