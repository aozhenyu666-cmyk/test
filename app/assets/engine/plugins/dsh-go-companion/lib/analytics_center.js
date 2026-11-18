// dsh-go-companion · 365 天统计中心 + Gemini 沟通追踪 + 晚间 AI 可视化总报告引擎 (V5)
//
// 1. history-365.json & evening-reports.json 持久化，解决原 episodes.js 跨天丢正事的失忆痛点；
// 2. trackDailyGemini 聚合 Android UsageStats + episodes 片段 + gemini.json 连麦复盘；
// 3. buildAnalyticsOverview 输出 5 桶分布、昨日涨跌、24h 时间轴切片、7/30/90 天柱图与 90 天热力格；
// 4. generateEveningReport 每晚 21:30 自动生成（并支持手动触发），同步写入 Obsidian <kbDir>/每日报告/YYYY-MM-DD.md。

import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { dayString } from './daily.js'
import { digestOf, segsOf } from './episodes.js'
import { GEMINI_PACKAGES } from './gemini.js'
import { classifyUsageBucket } from './telemetry_charts.js'
import { DEFAULT_TZ, hm as zoneHm, minuteOfDay, startOfDay } from './tz.js'

export const HISTORY_VERSION = 1
export const MAX_HISTORY_DAYS = 365
export const EVENING_REPORT_HOUR = 21
export const EVENING_REPORT_MINUTE = 30

const isPlain = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

const FRIENDLY_LABELS = {
  'com.google.android.apps.bard': 'Gemini',
  'com.google.android.googlequicksearchbox': 'Google/Gemini',
  'md.obsidian': 'Obsidian',
  'tv.danmaku.bili': 'B站',
  'com.ss.android.ugc.aweme': '抖音',
  'com.tencent.mm': '微信',
  'com.zhihu.android': '知乎',
  'com.xingin.xhs': '小红书',
  'com.fenbi.android.solar': '粉笔',
  'cn.wps.moffice_eng': 'WPS Office',
}

function friendlyLabel(pkg, fallback = '') {
  const s = String(fallback || '').trim()
  if (s && s !== pkg) return s
  return FRIENDLY_LABELS[pkg] || String(pkg || '')
}

export function classifyAnalyticsBucket(row = {}) {
  const pkg = String(row.app || '').trim()
  if (GEMINI_PACKAGES.includes(pkg)) return 'gemini_cobrain'
  return classifyUsageBucket(row)
}

export function shiftYmd(ymdStr, deltaDays) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(ymdStr || ''))
  if (!m) return ''
  const dt = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + Number(deltaDays || 0)))
  return dt.toISOString().slice(0, 10)
}

function dayStartMsInTz(ymdStr, tz = DEFAULT_TZ) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(ymdStr || ''))
  if (!m) return startOfDay(Date.now(), tz)
  let probe = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12, 0, 0)
  const actual = dayString(probe, tz)
  if (actual < ymdStr) probe += 12 * 3_600_000
  else if (actual > ymdStr) probe -= 12 * 3_600_000
  return startOfDay(probe, tz)
}

export function normalizeHistoryStore(raw) {
  const s = isPlain(raw) ? raw : {}
  const days = {}
  if (isPlain(s.days)) {
    const keys = Object.keys(s.days).sort().slice(-MAX_HISTORY_DAYS)
    for (const k of keys) {
      if (isPlain(s.days[k])) days[k] = { ...s.days[k], day: k }
    }
  }
  return { v: HISTORY_VERSION, days }
}

export function normalizeReportsStore(raw) {
  const s = isPlain(raw) ? raw : {}
  const reports = {}
  if (isPlain(s.reports)) {
    const keys = Object.keys(s.reports).sort().slice(-MAX_HISTORY_DAYS)
    for (const k of keys) {
      if (isPlain(s.reports[k])) reports[k] = { ...s.reports[k], day: k }
    }
  }
  return { v: HISTORY_VERSION, reports }
}

export function upsertHistoryDay(rawStore, dayRecord) {
  const store = normalizeHistoryStore(rawStore)
  const day = String(dayRecord?.day || '').trim()
  if (!day) return store
  store.days[day] = { ...(store.days[day] || {}), ...dayRecord, day }
  const keys = Object.keys(store.days).sort()
  for (const old of keys.slice(0, Math.max(0, keys.length - MAX_HISTORY_DAYS))) {
    delete store.days[old]
  }
  return store
}

export function upsertEveningReport(rawStore, report) {
  const store = normalizeReportsStore(rawStore)
  const day = String(report?.day || '').trim()
  if (!day) return store
  store.reports[day] = { ...report, day }
  const keys = Object.keys(store.reports).sort()
  for (const old of keys.slice(0, Math.max(0, keys.length - MAX_HISTORY_DAYS))) {
    delete store.reports[old]
  }
  return store
}

export function trackDailyGemini({
  phone = null,
  episodesState = null,
  geminiState = null,
  historyStore = null,
  day = '',
  now = Date.now(),
  tz = DEFAULT_TZ,
} = {}) {
  const targetDay = day || dayString(now, tz)
  const todayDay = dayString(now, tz)
  const isToday = targetDay === todayDay

  const phoneTodayRows = Array.isArray(phone?.today) ? phone.today : []
  const phoneRowsGemMin = isToday
    ? phoneTodayRows
        .filter((r) => GEMINI_PACKAGES.includes(String(r?.app || '')))
        .reduce((s, r) => s + Math.max(0, num(r.minutes)), 0)
    : 0
  const phoneReportedGemMin = isToday ? Math.max(0, num(phone?.geminiMinutesToday)) : 0

  let epGemMin = 0
  let epGemSegCount = 0
  if (episodesState) {
    const segs = segsOf(episodesState, targetDay, isToday ? now : undefined)
    for (const seg of segs) {
      if (GEMINI_PACKAGES.includes(String(seg?.app || ''))) {
        epGemSegCount += 1
        epGemMin += Math.max(0, (num(seg.end) - num(seg.start)) / 60_000)
      }
    }
    const digGem = num(episodesState?.digests?.[targetDay]?.geminiMin)
    epGemMin = Math.max(epGemMin, digGem)
  }

  const g = isPlain(geminiState) ? geminiState : {}
  const allRecaps = Array.isArray(g.recaps) ? g.recaps : []
  const dayRecaps = allRecaps.filter((r) => r && dayString(num(r.at), tz) === targetDay)
  const recapMinToday = dayRecaps.reduce((s, r) => s + Math.max(0, num(r.minutes)), 0)
  const dayPulls = (Array.isArray(g.pulls) ? g.pulls : []).filter((p) => p && dayString(num(p.at), tz) === targetDay)

  let activeSessionMin = 0
  let hasTodayActiveSession = false
  if (isPlain(g.session) && num(g.session.startedAt) > 0 && dayString(num(g.session.startedAt), tz) === targetDay) {
    hasTodayActiveSession = true
    const endAt = Math.min(now, num(g.session.until) || now)
    activeSessionMin = Math.max(0, Math.round((endAt - num(g.session.startedAt)) / 60_000))
  }

  const histDay = historyStore?.days?.[targetDay]
  const histGemMin = num(histDay?.buckets?.geminiMin ?? histDay?.geminiMin)

  const minutesToday = Math.round(
    Math.max(phoneReportedGemMin, phoneRowsGemMin, epGemMin, recapMinToday, activeSessionMin, histGemMin),
  )

  const sessionsCount = Math.max(
    dayRecaps.length + (hasTodayActiveSession ? 1 : 0),
    dayPulls.length,
    epGemSegCount,
    num(histDay?.geminiStats?.sessionsCount),
  )

  const recentRecaps = [...allRecaps]
    .slice(-5)
    .reverse()
    .map((r) => {
      const at = num(r.at, now)
      return {
        at,
        hm: zoneHm(at, tz),
        day: dayString(at, tz),
        topic: String(r.topic || '').slice(0, 80),
        summary: String(r.summary || '').slice(0, 240),
        stuck: String(r.stuck || '').slice(0, 120),
        next: String(r.next || '').slice(0, 120),
        minutes: Math.max(0, Math.round(num(r.minutes))),
      }
    })

  return {
    minutesToday,
    sessionsCount,
    recapsCount: dayRecaps.length,
    recentRecaps,
  }
}

function summarizeFromRows(rows, funFallback = 0, geminiFallback = 0) {
  let mainlineMin = 0
  let videoStudyMin = 0
  let geminiMin = 0
  let toolTinkerMin = 0
  let countedFun = 0
  const byApp = new Map()

  for (const r of Array.isArray(rows) ? rows : []) {
    const app = String(r?.app || '').trim()
    const mins = Math.max(0, Math.round(num(r?.minutes ?? r?.min)))
    if (!app || mins <= 0) continue
    const label = friendlyLabel(app, r?.label)
    const bucket = classifyAnalyticsBucket({ app, label, fun: Boolean(r?.fun), inStudySession: Boolean(r?.inStudySession) })
    if (bucket === 'mainline') mainlineMin += mins
    else if (bucket === 'video_study') videoStudyMin += mins
    else if (bucket === 'gemini_cobrain') geminiMin += mins
    else if (bucket === 'tool_tinker') toolTinkerMin += mins
    else countedFun += mins

    const prev = byApp.get(app)
    if (prev) {
      prev.minutes += mins
    } else {
      byApp.set(app, { app, label, minutes: mins, bucket, fun: bucket === 'fun' || Boolean(r?.fun) })
    }
  }

  geminiMin = Math.max(geminiMin, Math.round(num(geminiFallback)))
  const funMin = Math.max(countedFun, Math.round(num(funFallback)))
  const totalMin = mainlineMin + videoStudyMin + geminiMin + toolTinkerMin + funMin
  const topApps = [...byApp.values()].sort((a, b) => b.minutes - a.minutes).slice(0, 10)
  return {
    buckets: {
      mainlineMin,
      videoStudyMin,
      geminiMin,
      toolTinkerMin,
      funMin,
      totalMin,
      tinkerWarning: toolTinkerMin >= 45 && toolTinkerMin > mainlineMin,
    },
    topApps,
  }
}

function resolveDaySummary(targetDay, { todayDay, episodesState, historyStore, usageReport, geminiStats, now, tz }) {
  const isToday = targetDay === todayDay
  const hist = historyStore?.days?.[targetDay] || null
  const dig = episodesState?.digests?.[targetDay] || (
    episodesState && isToday
      ? digestOf(episodesState, targetDay, { now, hourOf: (ms) => Math.floor(minuteOfDay(ms, tz) / 60) })
      : null
  )

  if (isToday) {
    const usageRows = Array.isArray(usageReport?.topApps)
      ? usageReport.topApps
      : (Array.isArray(usageReport?.rows) ? usageReport.rows : [])
    if (usageRows.length > 0 || num(usageReport?.funMinutes) > 0 || num(geminiStats?.minutesToday) > 0) {
      const fromUsage = summarizeFromRows(usageRows, num(usageReport?.funMinutes), num(geminiStats?.minutesToday))
      // 若 episodes 记录到的正事/娱乐更多（例如仅跑了 segments 测试），取较大稳健值
      if (dig) {
        fromUsage.buckets.geminiMin = Math.max(fromUsage.buckets.geminiMin, num(dig.geminiMin))
        fromUsage.buckets.funMin = Math.max(fromUsage.buckets.funMin, num(dig.funMin))
        if (fromUsage.buckets.mainlineMin + fromUsage.buckets.videoStudyMin + fromUsage.buckets.toolTinkerMin === 0 && num(dig.focusMin) > 0) {
          fromUsage.buckets.mainlineMin = Math.max(0, num(dig.focusMin) - num(dig.geminiMin))
        }
        fromUsage.buckets.totalMin = Math.max(
          num(usageReport?.totalMinutes),
          fromUsage.buckets.mainlineMin +
            fromUsage.buckets.videoStudyMin +
            fromUsage.buckets.geminiMin +
            fromUsage.buckets.toolTinkerMin +
            fromUsage.buckets.funMin,
        )
        fromUsage.buckets.tinkerWarning =
          fromUsage.buckets.toolTinkerMin >= 45 && fromUsage.buckets.toolTinkerMin > fromUsage.buckets.mainlineMin
        if (!fromUsage.topApps.length && Array.isArray(dig.byAppAll)) {
          return {
            buckets: fromUsage.buckets,
            topApps: dig.byAppAll.map((a) => ({
              app: a.app,
              label: friendlyLabel(a.app),
              minutes: num(a.min),
              bucket: classifyAnalyticsBucket({ app: a.app, fun: a.fun }),
              fun: Boolean(a.fun),
            })),
          }
        }
      }
      return fromUsage
    }
  }

  if (hist?.buckets) {
    const b = hist.buckets
    const mainlineMin = num(b.mainlineMin)
    const videoStudyMin = num(b.videoStudyMin)
    const geminiMin = Math.max(num(b.geminiMin), num(dig?.geminiMin))
    const toolTinkerMin = num(b.toolTinkerMin)
    const funMin = Math.max(num(b.funMin), num(dig?.funMin))
    return {
      buckets: {
        mainlineMin,
        videoStudyMin,
        geminiMin,
        toolTinkerMin,
        funMin,
        totalMin: Math.max(num(b.totalMin), mainlineMin + videoStudyMin + geminiMin + toolTinkerMin + funMin),
        tinkerWarning: toolTinkerMin >= 45 && toolTinkerMin > mainlineMin,
      },
      topApps: Array.isArray(hist.topApps) ? hist.topApps : [],
    }
  }

  if (dig) {
    if (Array.isArray(dig.byAppAll) && dig.byAppAll.length > 0) {
      return summarizeFromRows(
        dig.byAppAll.map((a) => ({ app: a.app, minutes: num(a.min), fun: Boolean(a.fun) })),
        num(dig.funMin),
        num(dig.geminiMin),
      )
    }
    const geminiMin = num(dig.geminiMin)
    const mainlineMin = Math.max(0, num(dig.focusMin) - geminiMin)
    const funMin = num(dig.funMin)
    return {
      buckets: {
        mainlineMin,
        videoStudyMin: 0,
        geminiMin,
        toolTinkerMin: 0,
        funMin,
        totalMin: mainlineMin + geminiMin + funMin,
        tinkerWarning: false,
      },
      topApps: Array.isArray(dig.topFun)
        ? dig.topFun.map((x) => ({ app: x.app, label: friendlyLabel(x.app), minutes: num(x.min), bucket: 'fun', fun: true }))
        : [],
    }
  }

  return {
    buckets: {
      mainlineMin: 0,
      videoStudyMin: 0,
      geminiMin: 0,
      toolTinkerMin: 0,
      funMin: 0,
      totalMin: 0,
      tinkerWarning: false,
    },
    topApps: [],
  }
}

export function buildTimeline24h(episodesState, day, { now = Date.now(), tz = DEFAULT_TZ, appsMap = [] } = {}) {
  if (!episodesState) return []
  const todayDay = dayString(now, tz)
  const segs = segsOf(episodesState, day, day === todayDay ? now : undefined)
  if (!segs.length) return []
  const dayStart = dayStartMsInTz(day, tz)
  const labelMap = new Map()
  for (const a of Array.isArray(appsMap) ? appsMap : []) {
    if (a?.app && a?.label) labelMap.set(String(a.app), String(a.label))
    else if (a?.pkg && a?.label) labelMap.set(String(a.pkg), String(a.label))
  }

  return segs
    .map((seg) => {
      const sMs = num(seg.start)
      const eMs = Math.max(sMs, num(seg.end))
      const startMin = Math.max(0, Math.min(1440, Math.floor((sMs - dayStart) / 60_000)))
      const endMin = Math.max(startMin, Math.min(1440, Math.ceil((eMs - dayStart) / 60_000)))
      const durationMin = Math.max(1, Math.round((eMs - sMs) / 60_000))
      const app = String(seg.app || '')
      const label = friendlyLabel(app, labelMap.get(app))
      const bucket = classifyAnalyticsBucket({ app, label, fun: Boolean(seg.fun) })
      return {
        startMin,
        endMin,
        durationMin,
        app,
        label,
        bucket,
        locked: seg.effect === '被锁',
      }
    })
    .filter((x) => x.app)
    .sort((a, b) => a.startMin - b.startMin)
}

function heatLevel(effectiveFocusMin) {
  if (effectiveFocusMin <= 0) return 0
  if (effectiveFocusMin < 30) return 1
  if (effectiveFocusMin < 90) return 2
  if (effectiveFocusMin < 180) return 3
  return 4
}

export function buildAnalyticsOverview({
  day,
  rangeDays = 7,
  episodesState = null,
  historyStore = null,
  reportsStore = null,
  usageReport = null,
  ledger = [],
  contract = null,
  horizons = null,
  bankStats = null,
  geminiStats = null,
  lockArbiter = null,
  kbDirPath = '',
  now = Date.now(),
  tz = DEFAULT_TZ,
} = {}) {
  const todayDay = dayString(now, tz)
  const targetDay = day && /^\d{4}-\d{2}-\d{2}$/.test(String(day)) ? String(day) : todayDay
  const resolvedGemini = geminiStats || trackDailyGemini({
    episodesState,
    historyStore,
    day: targetDay,
    now,
    tz,
  })

  const cur = resolveDaySummary(targetDay, {
    todayDay,
    episodesState,
    historyStore,
    usageReport,
    geminiStats: resolvedGemini,
    now,
    tz,
  })

  const prevDay = shiftYmd(targetDay, -1)
  const prev = prevDay
    ? resolveDaySummary(prevDay, {
        todayDay,
        episodesState,
        historyStore,
        usageReport: null,
        geminiStats: null,
        now,
        tz,
      })
    : null
  const hasPrev = Boolean(
    prevDay &&
      (historyStore?.days?.[prevDay] || episodesState?.digests?.[prevDay]),
  )

  const deltas = {
    mainlineDelta: hasPrev ? cur.buckets.mainlineMin - prev.buckets.mainlineMin : 0,
    funDelta: hasPrev ? cur.buckets.funMin - prev.buckets.funMin : 0,
    geminiDelta: hasPrev ? cur.buckets.geminiMin - prev.buckets.geminiMin : 0,
  }

  const span = Math.max(7, Math.min(90, Math.round(num(rangeDays, 7)) || 7))
  const historySeries = []
  for (let i = span - 1; i >= 0; i -= 1) {
    const ds = shiftYmd(targetDay, -i)
    const sum = ds === targetDay
      ? cur
      : resolveDaySummary(ds, {
          todayDay,
          episodesState,
          historyStore,
          usageReport: ds === todayDay ? usageReport : null,
          geminiStats: ds === todayDay ? resolvedGemini : null,
          now,
          tz,
        })
    const b = sum.buckets
    historySeries.push({
      day: ds,
      shortDay: ds.slice(5),
      mainlineMin: b.mainlineMin,
      videoStudyMin: b.videoStudyMin,
      geminiMin: b.geminiMin,
      toolTinkerMin: b.toolTinkerMin,
      funMin: b.funMin,
      focusMin: b.mainlineMin + b.videoStudyMin + b.geminiMin,
      totalMin: b.totalMin,
    })
  }

  const heatmap90d = []
  for (let i = 89; i >= 0; i -= 1) {
    const ds = shiftYmd(targetDay, -i)
    const sum = ds === targetDay
      ? cur
      : resolveDaySummary(ds, {
          todayDay,
          episodesState,
          historyStore,
          usageReport: ds === todayDay ? usageReport : null,
          geminiStats: ds === todayDay ? resolvedGemini : null,
          now,
          tz,
        })
    const b = sum.buckets
    const focusMin = b.mainlineMin + b.videoStudyMin
    heatmap90d.push({
      day: ds,
      focusMin,
      geminiMin: b.geminiMin,
      funMin: b.funMin,
      level: heatLevel(focusMin + b.geminiMin),
    })
  }

  const timeline24h = buildTimeline24h(episodesState, targetDay, { now, tz })
  const eveningReport = reportsStore?.reports?.[targetDay] || null

  return {
    v: 1,
    ok: true,
    day: targetDay,
    buckets: cur.buckets,
    deltas,
    geminiStats: resolvedGemini,
    timeline24h,
    historySeries,
    heatmap90d,
    topApps: cur.topApps,
    eveningReport,
    lockArbiter: lockArbiter || null,
  }
}

export function shouldAutoTriggerEveningReport(reportsStore, { now = Date.now(), tz = DEFAULT_TZ } = {}) {
  const mod = minuteOfDay(now, tz)
  if (mod < EVENING_REPORT_HOUR * 60 + EVENING_REPORT_MINUTE) return false
  const day = dayString(now, tz)
  return !reportsStore?.reports?.[day]
}

export function generateEveningReport({
  day,
  episodesState = null,
  historyStore = null,
  reportsStore = null,
  usageReport = null,
  ledger = [],
  contract = null,
  horizons = null,
  bankStats = null,
  geminiStats = null,
  lockArbiter = null,
  kbDirPath = '',
  storyline = null,
  now = Date.now(),
  tz = DEFAULT_TZ,
} = {}) {
  const targetDay = day && /^\d{4}-\d{2}-\d{2}$/.test(String(day)) ? String(day) : dayString(now, tz)
  const overview = buildAnalyticsOverview({
    day: targetDay,
    rangeDays: 7,
    episodesState,
    historyStore,
    reportsStore,
    usageReport,
    ledger,
    contract,
    horizons,
    bankStats,
    geminiStats,
    lockArbiter,
    kbDirPath,
    now,
    tz,
  })
  const b = overview.buckets
  const gStats = overview.geminiStats || { minutesToday: b.geminiMin, sessionsCount: 0, recapsCount: 0, recentRecaps: [] }

  // 收集当日完成事件与违纪事件
  const completedTasks = []
  const seen = new Set()
  for (const e of Array.isArray(ledger) ? ledger : []) {
    if (!e || dayString(num(e.at), tz) !== targetDay) continue
    if (['bout-close', 'bout-done', 'checkpoint-passed', 'quiz-unlock'].includes(e.state)) {
      const title = String(e.title || e.goal || e.note || e.state).slice(0, 60)
      const k = `${e.state}:${title}`
      if (seen.has(k)) continue
      seen.add(k)
      completedTasks.push({
        kind: e.state,
        title,
        minutes: Math.max(0, Math.round(num(e.minutes))),
        at: zoneHm(num(e.at, now), tz),
      })
    }
  }
  if (Array.isArray(contract?.checkpoints)) {
    for (const cp of contract.checkpoints) {
      if (cp?.status === 'passed' && dayString(num(cp.verifiedAt), tz) === targetDay) {
        const title = `契约验收：${cp.title}`
        if (!seen.has(`cp:${title}`)) {
          seen.add(`cp:${title}`)
          completedTasks.push({
            kind: 'checkpoint-passed',
            title,
            minutes: 0,
            at: zoneHm(num(cp.verifiedAt, now), tz),
          })
        }
      }
    }
  }

  const violationsToday = (Array.isArray(ledger) ? ledger : []).filter(
    (e) => e && dayString(num(e.at), tz) === targetDay && ['defied', 'emergency-exit', 'checkpoint-overdue-locked'].includes(e.state),
  ).length

  const focusTotal = b.mainlineMin + b.videoStudyMin + b.geminiMin
  const focusPts = Math.min(50, Math.round(focusTotal / 3.6))
  const taskPts = Math.min(30, completedTasks.length * 10)
  const ratioBonus = focusTotal > 0 ? Math.round(20 * (focusTotal / Math.max(1, focusTotal + b.funMin))) : 0
  const penalty = Math.min(30, violationsToday * 8 + (b.tinkerWarning ? 6 : 0))
  const score = Math.max(0, Math.min(100, focusPts + taskPts + ratioBonus - penalty))

  const verdictTag =
    score >= 80 ? '主线推进扎实' :
    score >= 60 ? '稳步推进中' :
    score >= 40 ? '有推进但存在分心损耗' :
    '主线投入偏低，明日需尽早开局'

  const headline = `${targetDay} · ${verdictTag}（战力 ${score}/100 · 主线与共脑 ${focusTotal}m / 娱乐 ${b.funMin}m）`

  const pieSlices = [
    ['主线攻坚', b.mainlineMin],
    ['Gemini 共脑', b.geminiMin],
    ['视频提炼', b.videoStudyMin],
    ['工具折腾', b.toolTinkerMin],
    ['纯娱乐', b.funMin],
  ].filter(([, v]) => v > 0)
  if (!pieSlices.length) pieSlices.push(['待记录', 1])

  const mermaidPie = [
    '```mermaid',
    `pie title ${targetDay} 时间投入分布（分钟）`,
    ...pieSlices.map(([label, v]) => `    "${label}" : ${v}`),
    '```',
  ].join('\n')

  const causalHighlights = []
  causalHighlights.push(
    `主线与视频学习合计 ${b.mainlineMin + b.videoStudyMin} 分钟，Gemini 深度共脑 ${gStats.minutesToday} 分钟（${gStats.sessionsCount} 场），纯娱乐 ${b.funMin} 分钟。`,
  )
  if (b.tinkerWarning) {
    causalHighlights.push(`工具折腾（${b.toolTinkerMin}m）已超过主线投入（${b.mainlineMin}m），注意防“以改配置替代做正事”。`)
  }
  if (bankStats && num(bankStats.correct) > 0) {
    causalHighlights.push(`锁屏题库今日累计答对 ${num(bankStats.correct)} 题，完成 ${num(bankStats.unlocksTotal)} 次知识换通行。`)
  }
  if (gStats.recentRecaps?.length) {
    const latest = gStats.recentRecaps[0]
    causalHighlights.push(`最近一次 Gemini 复盘：${latest.summary}${latest.next ? ` → 下一步：${latest.next}` : ''}`)
  }
  if (violationsToday > 0) {
    causalHighlights.push(`今日发生 ${violationsToday} 次违约或紧急退出记录，明日建议先以 25m 短契约热身立稳锚点。`)
  }
  if (Array.isArray(storyline?.entries) && storyline.entries.length > 0) {
    causalHighlights.push(`今日叙事链已沉淀 ${storyline.entries.length} 个连续事件锚点，明日开场将无缝接续。`)
  }

  const tomorrowDraft = []
  for (const m of Array.isArray(horizons?.medium) ? horizons.medium : []) {
    if (m?.status !== 'done' && tomorrowDraft.length < 2) {
      tomorrowDraft.push({
        title: `推进中期战役「${m.title}」（${num(m.doneSteps)}/${num(m.targetSteps)} 阶）`,
        minutes: 45,
        category: 'campaign',
      })
    }
  }
  for (const cp of Array.isArray(contract?.checkpoints) ? contract.checkpoints : []) {
    if (cp?.status !== 'passed' && tomorrowDraft.length < 3) {
      tomorrowDraft.push({
        title: `完成待验收交付：${cp.title}`,
        minutes: 30,
        category: 'contract',
      })
    }
  }
  const fallbacks = [
    { title: '晨间第一局：45m 主线高浓度攻坚并输出三段因果卡', minutes: 45, category: 'mainline' },
    { title: '15m Gemini Live 语音抽查与错题复盘', minutes: 15, category: 'gemini' },
    { title: '整理核心产出落入 Obsidian 笔记库并完成晚间对账', minutes: 25, category: 'review' },
  ]
  for (const fb of fallbacks) {
    if (tomorrowDraft.length >= 3) break
    tomorrowDraft.push(fb)
  }

  const markdown = [
    '---',
    `title: "${targetDay} 晚间 AI 可视化总报告"`,
    `day: "${targetDay}"`,
    `score: ${score}`,
    'tags:',
    '  - 每日报告',
    '  - DSH-Go',
    '---',
    '',
    `# ${targetDay} · 大肥鱼晚间 AI 可视化总报告`,
    '',
    `> **战力总评：${score}/100** · ${headline}`,
    '',
    '## 一、时间投入分布图',
    '',
    mermaidPie,
    '',
    '| 分类维度 | 今日时长 (m) | 较昨日变化 |',
    '| :--- | :---: | :---: |',
    `| 主线攻坚 | ${b.mainlineMin} | ${deltasText(overview.deltas.mainlineDelta)} |`,
    `| Gemini 共脑 | ${b.geminiMin} | ${deltasText(overview.deltas.geminiDelta)} |`,
    `| 视频提炼 | ${b.videoStudyMin} | — |`,
    `| 工具折腾 | ${b.toolTinkerMin}${b.tinkerWarning ? ' (预警)' : ''} | — |`,
    `| 纯娱乐 | ${b.funMin} | ${deltasText(overview.deltas.funDelta)} |`,
    '',
    '## 二、今日战果与验收清单',
    '',
    ...(completedTasks.length
      ? completedTasks.map((t) => `- [x] \`${t.at}\` **${t.title}**${t.minutes > 0 ? `（${t.minutes}m）` : ''}`)
      : ['- （今日暂无已归档验收局记录）']),
    '',
    '## 三、因果复盘要点（鲸澜主笔 × 鱼丸批注）',
    '',
    ...causalHighlights.map((h) => `- ${h}`),
    '',
    '## 四、明日三件套作战草案',
    '',
    ...tomorrowDraft.map((t, idx) => `${idx + 1}. [ ] **${t.title}**（预计 ${t.minutes}m）`),
    '',
  ].join('\n')

  let wrotePath = ''
  if (kbDirPath) {
    try {
      const reportDir = join(kbDirPath, '每日报告')
      mkdirSync(reportDir, { recursive: true })
      wrotePath = join(reportDir, `${targetDay}.md`)
      writeFileSync(wrotePath, markdown, 'utf8')
    } catch {
      wrotePath = ''
    }
  }

  return {
    day: targetDay,
    generatedAt: now,
    score,
    headline,
    markdown,
    mermaidPie,
    completedTasks,
    causalHighlights,
    tomorrowDraft,
    buckets: b,
    path: wrotePath,
  }
}

function deltasText(n) {
  const v = Math.round(num(n))
  if (v > 0) return `+${v}m`
  if (v < 0) return `${v}m`
  return '0m'
}
