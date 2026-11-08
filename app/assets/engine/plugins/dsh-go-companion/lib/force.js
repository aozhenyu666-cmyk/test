// dsh-go-companion · 力度（说到做到）
//
// 脾气可以阴晴难测，力度不行：打游戏打一天、它还充耳不闻，那就白做了。
// 所以力度是确定的规则，不掷骰子，只看事实：
//   0 说   正常发球
//   1 追   更勤、更硬（水位已经在管，这一级只是标出来）
//   2 屏   必须上浮层页，不许只发一条能划掉的通知
//   3 锁   把这个娱乐 App 锁住，答过一道题才放行
//   4 赶   直接退回桌面，再锁住
// 严格模式门槛低，温和模式门槛高——但温和也会锁，只是更晚。插件只决定力度，执行在手机（见 /plan 的 force）。

export const LEVELS = ['talk', 'press', 'screen', 'lock', 'home']

/** 两种模式的门槛：连续待多少分钟 / 今天一共多少分钟。 */
export const THRESHOLDS = {
  coach: { screen: 10, lock: 30, home: 60, lockToday: 120, homeToday: 180 },
  partner: { screen: 20, lock: 60, home: 120, lockToday: 180, homeToday: 300 },
}

/**
 * @param {{phone?: object|null, tide?: {level:number, band:string}, mode?: string, noTake?: number, control?: boolean, funToday?: number}} input
 * @returns {{level: number, name: string, why: string}}
 */
export function decideForce({ phone, tide, mode, noTake = 0, control = true, funToday = 0, grace = false, refuse = false } = {}) {
  const t = THRESHOLDS[mode] || THRESHOLDS.partner
  const pick = (level, why) => {
    // 他刚讲通了理由：这段时间最多追着说，不锁。
    if (grace && level > 1) return { level: 1, name: LEVELS[1], why: '他讲了站得住的理由，先松一会儿' }
    const capped = control === false ? Math.min(level, 2) : level
    return { level: capped, name: LEVELS[capped], why: capped < level ? why + '（没给控机权限，只能到浮层）' : why }
  }
  if (!phone?.fun || !phone.screenOn) return pick(0, '')
  // 拒绝模式：一打开就赶，讲理由也不松（宽限不算），只受控机权限限制。
  if (refuse) {
    const level = control === false ? 2 : 4
    return { level, name: LEVELS[level], refuse: true, why: '拒绝模式开着，' + (phone.label || '这个 App') + ' 不许开' + (level < 4 ? '（没给控机权限，只能到浮层）' : '') }
  }
  const dwell = Number(phone.minutes) || 0
  const total = Number(funToday) || 0
  if (dwell >= t.home || total >= t.homeToday) return pick(4, phone.label + ' 连续 ' + dwell + ' 分钟 / 今天 ' + total + ' 分钟')
  // 陷住了还连着两球不接：不再等，直接锁。
  if (dwell >= t.lock || total >= t.lockToday || (tide?.band === 'trap' && noTake >= 2)) {
    return pick(3, phone.label + ' 连续 ' + dwell + ' 分钟' + (noTake >= 2 ? '，连着 ' + noTake + ' 球没接' : ''))
  }
  if (dwell >= t.screen) return pick(2, phone.label + ' 连续 ' + dwell + ' 分钟')
  if (tide?.band === 'sink' || tide?.band === 'trap') return pick(2, '水位' + (tide.label || '在沉'))
  if (tide?.band === 'slide') return pick(1, '在滑')
  return pick(0, '')
}

/**
 * 拼进 prompt：锁 / 赶之前那一球是宣布。做题模式开着就丢一道题；没开就问他为什么还在这里——
 * 他讲得通（真有理由），会松一会儿；讲不通就锁着。
 */
export function forceLines(f, phone, { drill = false, gemini = false } = {}) {
  if (!f || f.level < 3) return []
  const app = phone?.label || '这个 App'
  if (f.refuse) return ['拒绝模式开着：他一打开' + app + '就被你退回桌面锁住了（手机已经在执行）。这一球是通知：一句话说清楚，语气带着你的性子。他求情、讲理由都不松——除非他亲口说"关掉拒绝模式"。']
  const then = drill
    ? '再丢一道题，答过线才放行。'
    : '然后直接问他：为什么现在还在这里？让他说清楚。理由站得住你可以松一会儿，站不住就锁着。'
  const live = gemini ? '能动手的话，可以顺手用 gemini_live（mode=interrogate）开一场审讯连麦，把他交给 Gemini 盯着。' : ''
  if (f.level === 4) return ['你已经决定把他从' + app + '里赶出去（手机马上会退回桌面并锁住它）。这一球是通知，不是商量：说清楚为什么，' + then + live]
  return ['你已经决定把' + app + '锁住（手机马上会锁）。这一球是通知，不是商量：一句话说清楚为什么，' + then + live]
}

/** 拒绝模式默认开多久。 */
export const REFUSE_DEFAULT_MS = 2 * 3_600_000

/**
 * "拒绝模式" / "开启拒绝模式 3 小时" / "拒绝模式 40 分钟" → { until }；"关掉拒绝模式" → { off: true }；别的话 → null。
 * 拒绝模式开着时：娱乐清单里的 App 一打开就退回桌面并锁住，不看待了多久、不等骰子，讲理由也不松——
 * 只有他亲口说关掉，或者到点。
 */
export function refuseSwitch(text, now = Date.now()) {
  const s = String(text == null ? '' : text).replace(/\s+/g, '')
  if (!s || s.length > 30 || !s.includes('拒绝模式')) return null
  if (/(关闭|关掉|关了|退出|结束|解除|取消|停止|停掉|不要|别开?)拒绝模式|拒绝模式(关掉|关了|关闭|结束|解除|取消|停了|先停|退出)/.test(s)) return { off: true }
  const h = /(\d+(?:\.\d+)?)(个)?(小时|钟头)/.exec(s)
  const m = /(\d+)分钟/.exec(s)
  const half = /半(个)?(小时|钟头)/.test(s)
  let ms = REFUSE_DEFAULT_MS
  if (h) ms = Number(h[1]) * 3_600_000
  else if (m) ms = Number(m[1]) * 60_000
  else if (half) ms = 30 * 60_000
  ms = Math.max(5 * 60_000, Math.min(24 * 3_600_000, ms))
  return { until: (Number(now) || Date.now()) + ms }
}
