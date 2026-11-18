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
