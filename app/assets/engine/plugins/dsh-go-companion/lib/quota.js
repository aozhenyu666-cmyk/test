// dsh-go-companion · 自由态额度（2026-11-18）
//
// 每天：娱乐 120 分钟（+ 每通过一局挣来的分钟），游戏另给 60 分钟。
// 游戏没用完的可以 2:1 换成娱乐（2 分钟游戏额度 → 1 分钟娱乐）；娱乐用超时自动去换，换光了才算娱乐用完。
// 双用途 App 在通行证（任务租约）以外的时间也算进娱乐。
// 深夜锁不吃额度；干活时娱乐本来就锁，不存在额度问题。

export const FUN_QUOTA_MIN = 120
export const GAME_QUOTA_MIN = 60
export const GAME_TO_FUN_RATE = 2

const n = (v) => Math.max(0, Math.round(Number(v) || 0))

/**
 * @param {object} p
 * @param {Array<{app:string,label?:string,minutes:number}>} p.today 手机报的今天每个 App 用了几分钟
 * @param {(pkg:string,label:string)=>string} p.tierOf
 * @param {number} p.earnedMin 今天挣来的分钟
 * @param {number} p.passMinutes 今天通行证覆盖的分钟（双用途在这段时间里不算娱乐）
 */
export function computeQuota({ today = [], tierOf = () => 'other', earnedMin = 0, passMinutes = 0,
  funQuota = FUN_QUOTA_MIN, gameQuota = GAME_QUOTA_MIN } = {}) {
  let fun = 0
  let game = 0
  let dual = 0
  const byTier = { fun: [], game: [], dual: [] }
  for (const row of Array.isArray(today) ? today : []) {
    if (!row?.app) continue
    const t = tierOf(String(row.app), String(row.label || ''))
    const m = n(row.minutes)
    if (t === 'fun') { fun += m; byTier.fun.push(row.app) }
    else if (t === 'game') { game += m; byTier.game.push(row.app) }
    else if (t === 'dual') { dual += m; byTier.dual.push(row.app) }
  }
  const dualCounted = Math.max(0, dual - n(passMinutes))
  const funUsed = fun + dualCounted
  const funCap = n(funQuota) + n(earnedMin)
  const gameRemain = Math.max(0, n(gameQuota) - game)
  const funAvail = funCap + Math.floor(gameRemain / GAME_TO_FUN_RATE)
  const funLeft = Math.max(0, funAvail - funUsed)
  const converted = Math.max(0, Math.min(funUsed - funCap, Math.floor(gameRemain / GAME_TO_FUN_RATE)))
  const gameLeft = Math.max(0, gameRemain - converted * GAME_TO_FUN_RATE)
  return {
    funQuota: n(funQuota),
    gameQuota: n(gameQuota),
    earned: n(earnedMin),
    funUsed,
    dualCounted,
    gameUsed: game,
    converted,
    funLeft,
    gameLeft,
    funOut: funUsed >= funAvail,
    gameOut: gameLeft <= 0,
    line: quotaLine({ funLeft, gameLeft, funUsed, gameUsed: game, converted, earned: n(earnedMin) }),
  }
}

export function quotaLine({ funLeft, gameLeft, funUsed, gameUsed, converted, earned }) {
  return '今日娱乐已用 ' + funUsed + ' 分钟、还剩 ' + funLeft + ' 分钟' +
    (earned ? '（含挣来的 ' + earned + '）' : '') +
    '；游戏已用 ' + gameUsed + '、还剩 ' + gameLeft + ' 分钟' +
    (converted ? '（已有 ' + converted * GAME_TO_FUN_RATE + ' 分钟游戏额度换成娱乐）' : '')
}

/** 该提醒了吗：用到 90 / 110 分钟各一次（返回要提醒的那一档，没有就 0）。 */
export function quotaNudgeAt(q, alreadyAt = 0) {
  for (const mark of [110, 90]) if (q.funUsed >= mark && alreadyAt < mark) return mark
  return 0
}
