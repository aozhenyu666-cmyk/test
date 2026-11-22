// dsh-go-companion · Today 真实战果表 & 多维投入统计图表汇总模块 (V4)
// 采借 GitHub ActivityWatch × Super Productivity 范式：
// 把后台 UsageReader 与 bouts 记录聚合为可读图表：
// - 主线攻坚 (mainline) / 视频学习提炼 (video_study) / 工具折腾预警 (tool_tinker) / 纯娱乐 (fun)
// - 今日已完成局与验收列表
// - 近 7 日投入趋势柱状图

import { dayString } from './daily.js'

const VIDEO_STUDY_RE = /(bili|youtube|mooc|xuetang|icourse|zhihu|study|anki|baicizhan|fenbi|huatu|offcn)/i
const TOOL_TINKER_RE = /(termux|vscode|mt管理器|github|terminal|android studio|via|quark|settings|filemanager|fileexplorer|documentsui|dsh)/i
const MAINLINE_WORK_RE = /(obsidian|notion|wps|word|office|excel|pdf|reader|knotes|xmind|flomo)/i

export function classifyUsageBucket(row = {}) {
  const pkg = String(row.app || '').toLowerCase()
  const label = String(row.label || '').toLowerCase()
  const sig = `${pkg} ${label}`
  if (row.fun) {
    if (VIDEO_STUDY_RE.test(sig) && row.inStudySession) return 'video_study'
    return 'fun'
  }
  if (MAINLINE_WORK_RE.test(sig)) return 'mainline'
  if (VIDEO_STUDY_RE.test(sig)) return 'video_study'
  if (TOOL_TINKER_RE.test(sig)) return 'tool_tinker'
  return 'mainline'
}

export function buildTodayCharts({
  usageReport = {},
  ledger = [],
  contract = null,
  bankStats = null,
  autonomy = null,
  now = Date.now(),
  tz = 'Asia/Shanghai',
} = {}) {
  const day = dayString(now, tz)
  const apps = Array.isArray(usageReport.topApps)
    ? usageReport.topApps
    : (Array.isArray(usageReport.rows) ? usageReport.rows : [])

  let mainlineMin = 0
  let videoStudyMin = 0
  let toolTinkerMin = 0
  let funMin = Number(usageReport.funMinutes) || 0

  const topApps = []
  let countedFun = 0
  for (const r of apps) {
    const mins = Math.max(0, Number(r?.minutes) || 0)
    if (mins <= 0) continue
    const bucket = classifyUsageBucket(r)
    if (bucket === 'mainline') mainlineMin += mins
    else if (bucket === 'video_study') videoStudyMin += mins
    else if (bucket === 'tool_tinker') toolTinkerMin += mins
    else countedFun += mins
    topApps.push({
      app: String(r.app || ''),
      label: String(r.label || r.app || ''),
      minutes: mins,
      bucket,
      fun: Boolean(r.fun),
    })
  }
  if (funMin === 0 && countedFun > 0) funMin = countedFun

  // 今日已完成/收尾记录（从账本汇总，一目了然今天干了什么）
  const completedItems = []
  const seenKey = new Set()
  for (let i = ledger.length - 1; i >= 0; i -= 1) {
    const e = ledger[i]
    if (!e || dayString(Number(e.at) || 0, tz) !== day) continue
    if (['bout-close', 'bout-done', 'bout-empty', 'checkpoint-passed', 'quiz-unlock', 'emergency-exit'].includes(e.state)) {
      const title = String(e.title || e.goal || e.note || e.state).slice(0, 60)
      const k = `${e.state}:${e.at}`
      if (seenKey.has(k)) continue
      seenKey.add(k)
      completedItems.push({
        id: String(e.boutId || e.at),
        kind: e.state,
        title,
        minutes: Math.max(0, Number(e.minutes) || 0),
        passed: e.state !== 'bout-empty' && e.state !== 'emergency-exit',
        at: Number(e.at) || 0,
      })
      if (completedItems.length >= 12) break
    }
  }

  // 从已通过的契约检查点补入展示
  if (Array.isArray(contract?.checkpoints)) {
    for (const cp of contract.checkpoints) {
      if (cp.status === 'passed' && dayString(cp.verifiedAt || 0, tz) === day) {
        completedItems.unshift({
          id: cp.id,
          kind: 'checkpoint-passed',
          title: `验收通过：${cp.title}`,
          minutes: 0,
          passed: true,
          at: cp.verifiedAt || now,
        })
      }
    }
  }

  // 近 7 日趋势柱状图（按账本每日专注/偏离统计）
  const sevenDayTrend = []
  for (let d = 6; d >= 0; d -= 1) {
    const stamp = now - d * 86_400_000
    const ds = dayString(stamp, tz)
    let boutsDone = 0
    let focusEstimateMin = 0
    let deviations = 0
    for (const e of ledger) {
      if (!e || dayString(Number(e.at) || 0, tz) !== ds) continue
      if (e.state === 'bout-close' || e.state === 'bout-done' || e.state === 'checkpoint-passed') {
        boutsDone += 1
        focusEstimateMin += Number(e.minutes) || 25
      }
      if (e.state === 'defied' || e.state === 'emergency-exit' || e.state === 'degraded-escape') {
        deviations += 1
      }
    }
    if (ds === day) {
      focusEstimateMin = Math.max(focusEstimateMin, mainlineMin + videoStudyMin)
    }
    sevenDayTrend.push({
      day: ds.slice(5),
      focusMinutes: focusEstimateMin,
      boutsDone,
      deviations,
    })
  }

  const totalFocus = mainlineMin + videoStudyMin
  return {
    day,
    buckets: {
      mainlineMin,
      videoStudyMin,
      toolTinkerMin,
      funMin,
      totalFocusMin: totalFocus,
      tinkerWarning: toolTinkerMin >= 45 && toolTinkerMin > mainlineMin,
    },
    topApps: topApps.slice(0, 6),
    completedItems: completedItems.slice(0, 10),
    sevenDayTrend,
    quizStats: bankStats || { answered: 0, correct: 0, unlocksTotal: 0 },
    autonomy: autonomy || null,
  }
}
