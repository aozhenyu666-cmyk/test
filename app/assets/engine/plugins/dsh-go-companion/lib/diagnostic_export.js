/**
 * 零自律 72h 实证诊断导出引擎（Diagnostic Export）。
 *
 * 自动汇总手机本地真实运行账本（ledger.jsonl + live_core + 门禁拦截 + 四部门流转 + 题库/验收产出），
 * 生成可直接发给 AI 分析或存入 Obsidian 的结构化 Markdown + JSON 诊断包，用户无需手工记日记。
 */

import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { DEPT_LABELS } from './departments.js'
import { DEFAULT_TZ, hm as zoneHm, ymd as zoneYmd } from './tz.js'

function num(v, d = 0) {
  const n = Number(v)
  return Number.isFinite(n) ? n : d
}

function pct(part, total) {
  if (!total || total <= 0) return '0%'
  return `${Math.round((part / total) * 100)}%`
}

function topEntries(mapObj, limit = 8) {
  return Object.entries(mapObj || {})
    .filter(([, count]) => count > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
}

/**
 * 汇总最近 N 天真实行为与系统运行指标，返回结构化 metrics + Markdown 报告。
 */
export function buildDiagnosticExport({
  now = Date.now(),
  days = 3,
  tz = DEFAULT_TZ,
  ledgerEntries = [],
  liveCoreState = null,
  locksOverview = null,
  bankStats = null,
  planState = null,
  historyStore = null,
  phone = null,
  capabilities = null,
  kbDir = '',
} = {}) {
  const ts = num(now, Date.now())
  const windowDays = Math.max(1, Math.min(14, Math.round(num(days, 3))))
  const since = ts - windowDays * 24 * 3_600_000
  const entries = (Array.isArray(ledgerEntries) ? ledgerEntries : []).filter((e) => e && num(e.at, 0) >= since)

  const liveSessions = []
  let igniteCount = 0
  let earlyBailoutCount = 0
  let headsetWakeCount = 0
  let switchToLiveCount = 0
  let switchToTextCount = 0
  let clipboardFeedCount = 0
  let relayCheckpointCount = 0
  let relayQuizCount = 0
  let relayRecapCount = 0
  let autopilotCount = 0
  const yieldReasons = {}
  const deptCounts = {
    'dept:collect': 0,
    'dept:strategize': 0,
    'dept:dispatch': 0,
    'dept:govern': 0,
  }
  const blockedByApp = {}
  let quizTotal = 0
  let quizCorrect = 0

  for (const e of entries) {
    const st = String(e.state || '')
    if (st === 'live-session-end') {
      liveSessions.push({
        at: num(e.at),
        durationSec: Math.max(0, Math.round(num(e.durationSec))),
        screenOn: e.screenOn !== false,
        headsetConnected: Boolean(e.headsetConnected),
        frontPkg: String(e.frontPkg || ''),
        earlyBailout: Boolean(e.earlyBailout),
      })
    } else if (st === 'live-core-ignite') {
      igniteCount++
      if (e.reason === 'live_early_bailout') earlyBailoutCount++
      if (e.reason === 'headset_live_dropped') headsetWakeCount++
      if (e.hasCustomFeed) clipboardFeedCount++
      if (e.department && deptCounts[e.department] !== undefined) deptCounts[e.department]++
    } else if (st === 'live-core-switch') {
      if (e.mode === 'text') switchToTextCount++
      else switchToLiveCount++
      if (e.hasCustomFeed) clipboardFeedCount++
      if (e.department && deptCounts[e.department] !== undefined) deptCounts[e.department]++
    } else if (st === 'live-core-yield') {
      const r = String(e.reason || 'unknown')
      yieldReasons[r] = (yieldReasons[r] || 0) + 1
    } else if (st === 'live-core-relay') {
      if (e.mode === 'checkpoint_25m') relayCheckpointCount++
      else if (e.mode === 'quiz_only') relayQuizCount++
      else relayRecapCount++
    } else if (st === 'live-core-autopilot') {
      autopilotCount++
    } else if (st === 'department-switch') {
      if (e.to && deptCounts[e.to] !== undefined) deptCounts[e.to]++
    } else if (st === 'bank-quiz-answer' || st === 'quiz-solved') {
      quizTotal++
      if (e.correct !== false && e.ok !== false) quizCorrect++
    } else if (st.includes('intercept') || st.includes('block') || st.includes('lock-hit') || st === 'emergency-exit') {
      const pkg = String(e.pkg || e.app || e.packageName || 'unknown').trim()
      if (pkg) blockedByApp[pkg] = (blockedByApp[pkg] || 0) + 1
    }
  }

  // Also include recent lock hits from locksOverview if available
  const recentHits = Array.isArray(locksOverview?.recentHits) ? locksOverview.recentHits : []
  for (const h of recentHits) {
    const pkg = String(h?.pkg || h?.app || h?.label || '').trim()
    if (pkg) blockedByApp[pkg] = (blockedByApp[pkg] || 0) + 1
  }

  const totalDur = liveSessions.reduce((s, x) => s + x.durationSec, 0)
  const avgDurationSec = liveSessions.length ? Math.round(totalDur / liveSessions.length) : 0
  const maxDurationSec = liveSessions.reduce((m, x) => Math.max(m, x.durationSec), 0)
  const shortDropCount = liveSessions.filter((x) => x.durationSec > 0 && x.durationSec < 45).length
  const screenOffDropCount = liveSessions.filter((x) => !x.screenOn).length
  const headsetDropCount = liveSessions.filter((x) => x.headsetConnected).length

  const seals = Array.isArray(planState?.seals) ? planState.seals : []
  const sealsDone = seals.filter((s) => s?.status === 'done' || s?.passed).length
  const todayDay = zoneYmd(ts, tz)
  const todayHm = zoneHm(ts, tz).replace(':', '')
  const filename = `${todayDay}_${todayHm}_${windowDays * 24}h实证诊断包.md`

  const activeDept = liveCoreState?.lastDepartment || null
  const caps = capabilities && typeof capabilities === 'object' ? capabilities : {
    a11y: Boolean(phone?.a11yConnected),
    shizuku: Boolean(phone?.shizukuReady),
  }
  const dayHist = historyStore?.days && typeof historyStore.days === 'object'
    ? Object.values(historyStore.days).slice(-windowDays)
    : []
  const geminiMinsWindow = dayHist.reduce((s, d) => s + Math.max(0, num(d?.geminiMinutes)), 0)
    || Math.max(0, num(phone?.geminiMinutesToday))

  const metrics = {
    generatedAt: ts,
    day: todayDay,
    windowDays,
    totalLedgerEntries: entries.length,
    live: {
      sessionsRecorded: liveSessions.length,
      avgDurationSec,
      maxDurationSec,
      shortDropCount,
      screenOffDropCount,
      headsetDropCount,
      igniteCount,
      earlyBailoutCount: Math.max(earlyBailoutCount, num(liveCoreState?.earlyBailoutCount)),
      headsetWakeCount,
      switchToLiveCount,
      switchToTextCount,
      clipboardFeedCount,
      relayCheckpointCount,
      relayQuizCount,
      relayRecapCount,
      autopilotCount,
      alwaysOnLive: Boolean(liveCoreState?.alwaysOnLive),
      energyTier: String(liveCoreState?.energyTier || 'L2'),
      headsetKeepalive: liveCoreState?.headsetKeepalive !== false,
      yieldReasons,
    },
    departments: {
      counts: deptCounts,
      active: activeDept ? { id: activeDept.id, label: activeDept.label, nextLabel: activeDept.nextLabel } : null,
    },
    output: {
      quizTotal: Math.max(quizTotal, num(bankStats?.todayAnswered)),
      quizCorrect,
      quizAccuracy: pct(quizCorrect, quizTotal),
      sealsDone,
      sealsTotal: seals.length,
      geminiMinutesWindow: geminiMinsWindow,
      funLeftMin: num(locksOverview?.wallet?.availableMin, 0),
    },
    locks: {
      activePriority: String(locksOverview?.activeLock?.priority || 'P6'),
      activeLabel: String(locksOverview?.activeLock?.label || '正常放行'),
      topBlockedApps: topEntries(blockedByApp, 8),
    },
    capabilities: caps,
  }

  const lines = [
    `# 大肥鱼 × Gemini Live ${windowDays * 24}h 实证诊断包（${todayDay} ${zoneHm(ts, tz)}）`,
    '',
    '> 用途：零自律自动埋点汇总，直接发给 AI 做反事实瓶颈排查（无需手工回忆）。',
    '',
    '## 1. Gemini Live 连麦存活与断线真相',
    `- 常驻护航：\`${metrics.live.alwaysOnLive ? '开启' : '关闭'}\` ｜ 当前能量挡：\`${metrics.live.energyTier}\` ｜ 息屏耳机保活：\`${metrics.live.headsetKeepalive ? '开启' : '关闭'}\``,
    `- 记录到真实连麦会话：\`${metrics.live.sessionsRecorded}\` 次（平均 \`${avgDurationSec}s\`，最长 \`${maxDurationSec}s\`，不足 45s 秒退 \`${shortDropCount}\` 次）`,
    `- 息屏中断：\`${screenOffDropCount}\` 次 ｜ 蓝牙/耳机态中断：\`${headsetDropCount}\` 次 ｜ 耳机唤醒播报：\`${headsetWakeCount}\` 次`,
    `- 主动动作分布：自动/手动点火 \`${igniteCount}\` 次 ｜ 切 Live \`${switchToLiveCount}\` 次 ｜ 切文本 \`${switchToTextCount}\` 次 ｜ 剪贴板直喂 \`${clipboardFeedCount}\` 次 ｜ 全自动托管开局 \`${autopilotCount}\` 次`,
    `- 挂断接力转化：25m 验收点 \`${relayCheckpointCount}\` 次 ｜ 20m 纯刷题舱 \`${relayQuizCount}\` 次 ｜ 仅复盘 \`${relayRecapCount}\` 次`,
    ...(topEntries(yieldReasons, 5).length
      ? [`- 自动让路原因 Top：${topEntries(yieldReasons, 5).map(([k, v]) => `${k}(${v})`).join('、')}`]
      : []),
    '',
    '## 2. 四部门自动齿轮流转',
    `- 当前值班部门：${activeDept ? `**${activeDept.label}**（下一跳：${activeDept.nextLabel}）` : '待自动路由'}`,
    `- 窗口期流转次数：${Object.entries(deptCounts).map(([k, v]) => `${DEPT_LABELS[k] || k}=${v}`).join(' ｜ ')}`,
    '',
    '## 3. 门禁误锁与底层权限体检',
    `- 当前锁裁决：\`${metrics.locks.activePriority}\` · ${metrics.locks.activeLabel}`,
    `- 权限状态：${Object.entries(caps).map(([k, v]) => `${k}=${v ? 'OK' : 'OFF'}`).join(' ｜ ')}`,
    ...(metrics.locks.topBlockedApps.length
      ? [`- 高频拦截/退出应用 Top（排查误锁）：${metrics.locks.topBlockedApps.map(([pkg, c]) => `\`${pkg}\`×${c}`).join('、')}`]
      : ['- 高频拦截应用：近窗口无误锁或高频拦截记录']),
    '',
    '## 4. 实际产出底盘',
    `- 刷题：\`${metrics.output.quizTotal}\` 题（账本正确率 \`${metrics.output.quizAccuracy}\`）`,
    `- 今日验收点：\`${sealsDone} / ${seals.length}\` 完成 ｜ 窗口期 Gemini 沟通 \`${metrics.output.geminiMinutesWindow}\` 分钟 ｜ 可用娱乐余额 \`${metrics.output.funLeftMin}\` 分钟`,
    '',
    '```json',
    JSON.stringify(metrics, null, 2),
    '```',
  ]

  const markdown = lines.join('\n')
  let obsidianPath = ''
  if (kbDir && typeof kbDir === 'string' && kbDir.trim()) {
    try {
      const dir = join(kbDir.trim(), '诊断导出')
      mkdirSync(dir, { recursive: true })
      obsidianPath = join(dir, filename)
      writeFileSync(obsidianPath, markdown, 'utf8')
    } catch {
      obsidianPath = ''
    }
  }

  return {
    ok: true,
    filename,
    obsidianPath,
    metrics,
    markdown,
  }
}
