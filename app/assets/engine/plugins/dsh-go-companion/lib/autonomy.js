// dsh-go-companion · W-3 自律分级与正向泄压阀（autonomyLevel）
// 补齐原全库检索命中数为 0 的核心闭环：
// 战绩达标后主动松绑还权，不再让系统只有 0ms 硬锁与升级惩罚、没有正向泄压出口。

export const AUTONOMY_LEVELS = Object.freeze([
  { level: 'L0', name: '严格护栏', desc: '关键任务或违约恢复期，重点保持防分心拦截' },
  { level: 'L1', name: '契约自治', desc: '正常节奏，按计划验收点动态升降锁定' },
  { level: 'L2', name: '战绩松绑', desc: '今日核心交付已过关，自动放宽常规巡检与宽限窗口' },
  { level: 'L3', name: '高度信赖', desc: '连续达标且低娱乐消耗，进入低打扰高自主区' },
])

/**
 * 综合今日战绩与违纪记录，计算实时 autonomyLevel 与正向松绑建议。
 */
export function evaluateAutonomyLevel({
  completedBouts = 0,
  checkpointsPassed = 0,
  quizSolvedToday = 0,
  focusMinutes = 0,
  funMinutes = 0,
  violationsToday = 0,
  overdueCheckpoints = 0,
} = {}) {
  const done = Number(completedBouts) || 0
  const passed = Number(checkpointsPassed) || 0
  const quiz = Number(quizSolvedToday) || 0
  const focusM = Number(focusMinutes) || 0
  const funM = Number(funMinutes) || 0
  const viol = Number(violationsToday) || 0
  const overdue = Number(overdueCheckpoints) || 0

  // 正向战绩积分（不靠虚无道德口号，只认实物交付与专注投入）
  const meritScore = done * 30 + passed * 35 + quiz * 2 + Math.floor(focusM / 15) * 8
  const penaltyScore = viol * 25 + overdue * 40 + (funM > 90 ? Math.floor((funM - 90) / 15) * 10 : 0)
  const netScore = Math.max(0, Math.min(200, meritScore - penaltyScore))

  let level = 'L1'
  if (overdue > 0 || viol >= 2 || (funM > 120 && done === 0 && passed === 0)) {
    level = 'L0'
  } else if (netScore >= 95 && viol === 0) {
    level = 'L3'
  } else if (netScore >= 50 || done >= 2 || passed >= 1) {
    level = 'L2'
  }

  const meta = AUTONOMY_LEVELS.find((x) => x.level === level) || AUTONOMY_LEVELS[1]
  // 泄压参数：等级越高，给到的宽限和免打扰余地越大，避免高压逼至卸载
  const relaxation = {
    L0: { strictBias: true, recommendedGraceSec: 15, autoRewardLabel: '完成 1 个验收点即可松绑至 L2' },
    L1: { strictBias: false, recommendedGraceSec: 45, autoRewardLabel: '再完结 1 局或过 1 个验收点即松绑' },
    L2: { strictBias: false, recommendedGraceSec: 120, autoRewardLabel: '今日战绩达标 · 已开启正向松绑（常规巡检降频）' },
    L3: { strictBias: false, recommendedGraceSec: 300, autoRewardLabel: '高信赖自治状态 · 免频繁打扰，保留自主推进' },
  }[level]

  return {
    level,
    name: meta.name,
    desc: meta.desc,
    netScore,
    meritScore,
    penaltyScore,
    unlockedRelaxation: level === 'L2' || level === 'L3',
    ...relaxation,
  }
}

function shiftYmd(ymd, deltaDays) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(ymd || ''))
  if (!m) return ''
  const dt = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + Number(deltaDays || 0)))
  return dt.toISOString().slice(0, 10)
}

/**
 * H7：按 14 天滚动窗口计算自律等级（主动开局比、交付率、空转天数、紧急解除次数）。
 * 纪律：降级比升级快；只用来决定是否把强度表往松挪一档（不单独绕过锁）。
 */
export function evaluateAutonomy14d({
  days = [],
  reports = {},
  ledger = [],
  todayStats = null,
  todayDay = '',
  overdueCheckpoints = 0,
  now = Date.now(),
  dayOf = (ms) => new Date(ms).toISOString().slice(0, 10),
} = {}) {
  const anchorDay = /^\d{4}-\d{2}-\d{2}$/.test(String(todayDay || '')) ? String(todayDay) : dayOf(now)
  const windowDates = []
  for (let i = 13; i >= 0; i--) {
    windowDates.push(shiftYmd(anchorDay, -i))
  }
  const dateSet = new Set(windowDates)
  const dayMap = new Map()
  for (const d of Array.isArray(days) ? days : []) {
    if (d?.day && dateSet.has(String(d.day))) dayMap.set(String(d.day), d)
  }

  let activeDays = 0
  let idleDays = 0
  let recentIdleDays3d = 0
  const selfOpenRatios = []
  const deliverRatios = []

  const recent3Set = new Set(windowDates.slice(-3))
  for (const ds of windowDates) {
    const rep = reports?.[ds] || null
    const dg = dayMap.get(ds) || (ds === anchorDay ? todayStats : null)
    const opened = Number(dg?.bouts?.opened ?? dg?.opened ?? 0)
    const selfOpened = Number(dg?.bouts?.self ?? dg?.self ?? 0)
    const passed = Number(dg?.bouts?.passed ?? dg?.passed ?? 0)
    const sr = dg?.selfOpenRatio !== undefined && dg?.selfOpenRatio !== null
      ? Number(dg.selfOpenRatio)
      : (opened > 0 ? selfOpened / opened : null)
    const dr = dg?.deliverRatio !== undefined && dg?.deliverRatio !== null
      ? Number(dg.deliverRatio)
      : (opened > 0 ? passed / opened : null)

    const isIdleByReport = rep?.verdict?.tag === '空转'
    const isActiveByReport = Boolean(rep && rep.verdict?.tag && rep.verdict.tag !== '空转')
    const hasActivity = Boolean(rep || opened > 0 || sr !== null || dr !== null)
    if (hasActivity) activeDays++
    if (isIdleByReport || (!isActiveByReport && opened > 0 && dr !== null && dr === 0 && (rep ? rep.verdict?.tag === '空转' : false))) {
      idleDays++
      if (recent3Set.has(ds)) recentIdleDays3d++
    }
    if (sr !== null && Number.isFinite(sr)) selfOpenRatios.push(sr)
    if (dr !== null && Number.isFinite(dr)) deliverRatios.push(dr)
  }

  const disarms = (Array.isArray(ledger) ? ledger : []).filter((e) => {
    if (!e || e.counted === false) return false
    if (!['disarm', 'emergency-pass-grant', 'breakglass'].includes(e.state)) return false
    const d = dayOf(Number(e.at) || 0)
    return dateSet.has(d)
  }).length

  const avg = (arr) => (arr.length ? Math.round((arr.reduce((s, v) => s + v, 0) / arr.length) * 100) / 100 : 0)
  const selfOpenRatio = avg(selfOpenRatios)
  const deliverRatio = avg(deliverRatios)
  const overdue = Math.max(0, Number(overdueCheckpoints) || 0)

  // 降级比升级快：近 3 天出现 >=2 天空转、14 天累计空转 >=4 天、紧急解除 >=5 次、或当下契约逾期 → 立刻 L0
  let level = 'L1'
  if (overdue > 0 || recentIdleDays3d >= 2 || idleDays >= 4 || disarms >= 5) {
    level = 'L0'
  } else if (activeDays >= 10 && deliverRatio >= 0.8 && selfOpenRatio >= 0.5 && idleDays === 0 && recentIdleDays3d === 0 && disarms <= 2) {
    level = 'L3'
  } else if (activeDays >= 5 && deliverRatio >= 0.65 && selfOpenRatio >= 0.5 && idleDays <= 2 && recentIdleDays3d === 0 && disarms <= 4) {
    level = 'L2'
  }

  const unlockedRelaxation = level === 'L2' || level === 'L3'
  const meta = AUTONOMY_LEVELS.find((x) => x.level === level) || AUTONOMY_LEVELS[1]
  const netScore = Math.max(0, Math.min(100, Math.round(deliverRatio * 55 + selfOpenRatio * 25 + Math.min(20, activeDays * 2) - idleDays * 10 - disarms * 5)))
  const relaxation = {
    L0: { strictBias: true, recommendedGraceSec: 15, autoRewardLabel: '近期存在空转/违约或高频解除：保持当前用户自选强度' },
    L1: { strictBias: false, recommendedGraceSec: 45, autoRewardLabel: '14 天窗口积累中（需 ≥5 天稳定交付与低空转方自动松一档）' },
    L2: { strictBias: false, recommendedGraceSec: 120, autoRewardLabel: '14 天自律达标 · 强度自动往松挪一档' },
    L3: { strictBias: false, recommendedGraceSec: 300, autoRewardLabel: '14 天高信赖自治 · 强度自动往松挪一档' },
  }[level]

  return {
    level,
    name: meta.name,
    desc: meta.desc,
    windowDays: 14,
    netScore,
    unlockedRelaxation,
    shift: unlockedRelaxation ? -1 : 0,
    metrics: {
      activeDays,
      idleDays,
      recentIdleDays3d,
      selfOpenRatio,
      deliverRatio,
      disarms,
      overdueCount: overdue,
    },
    ...relaxation,
  }
}
