// dsh-go-companion · V4 模块化总装桥接器（防臃肿设计：不往 9000 行 index.js 裸堆代码）
// 聚合：
// - bank_unlock.js (导入题库 + 10 题解锁 5 分钟)
// - autonomy.js (W-3 正向泄压自律等级 autonomyLevel)
// - horizons.js (自由口径三层主线 + 长期规划.md 双向镜像)
// - plan_modality.js (AI 协同计划模态 + 本机 App 检查 + 视频高强度提炼验收 + 1 分钟逾期转严格锁)
// - telemetry_charts.js (Today 真实战果与耗时分布图表)
// - dept_skills.js (四部门防骗红旗 / 反沉没成本 / 三段因果链可执行 Skill)

import { join } from 'node:path'
import { cleanBankUnlockState, judgeQuizAnswer, parseBankSmart, pickNextQuizQuestion } from './bank_unlock.js'
import { evaluateAutonomyLevel } from './autonomy.js'
import {
  archivePathInKb,
  cleanHorizons,
  deleteHorizonItem,
  mirrorHorizonsToMarkdown,
  syncLongGoalsFromMarkdown,
  upsertHorizonItem,
} from './horizons.js'
import {
  AI_PROMPT_SCRIPTS,
  cleanPlanContract,
  COMMON_PLAN_PRESETS,
  evaluateCheckpointSubmission,
  evaluateContractTick,
  inspectScenarioApps,
  OVERDUE_GRACE_MS,
  resolveContractSessionAllow,
} from './plan_modality.js'
import { buildTodayCharts } from './telemetry_charts.js'
import {
  buildSkillsPromptBlock,
  compileCausalChainCard,
  runMainlineAlignmentAudit,
  runRedFlagScamAudit,
} from './dept_skills.js'
import {
  buildAnalyticsOverview,
  generateEveningReport,
  normalizeHistoryStore,
  normalizeReportsStore,
  shouldAutoTriggerEveningReport,
  trackDailyGemini,
  upsertEveningReport,
  upsertHistoryDay,
} from './analytics_center.js'
import {
  appendStorylineEvent,
  formatIntradayPromptBlock,
  markAutoPulled,
  normalizeStoryline,
  setPullMode,
} from './intraday_storyline.js'
import {
  computeUnifiedLockArbiter,
  computeUnifiedTodayTasks,
  resolveActiveSisterSkin,
} from './unified_arbiter.js'
import { geminiBrief, geminiMode } from './gemini.js'
import { deadlineLines, isSaturday, upcoming as upcomingDeadlinesOf, weekEndingSaturday, weeklyMarkdown } from './verdict.js'
import { mkdirSync as mkdirSyncV, writeFileSync as writeFileSyncV } from 'node:fs'
import { isEmergencyPassActive } from './external_agents.js'

export function createV4Hub(deps) {
  const {
    stateFile,
    readState,
    writeJson,
    home,
    notesDir,
    kbDir,
    getConfig,
    getLastPhone,
    getLastRolledDice,
    appsOf,
    emergencyPackages,
    usageReportView,
    readLedger,
    record,
    boutsGetRoute,
    planDayRoute,
    handoffRoute,
    dayString,
    zoneOr,
    presetRt,
    grantTempPass,
    toolOutput,
    loadEpisodes = () => null,
    loadGemini = () => ({}),
    saveGemini = () => {},
    loadExternal = () => ({}),
    loadDailyToday = () => ({ tasks: [] }),
    getGeminiSession = () => null,
    getAiWorkSession = () => null,
    getTaskLease = () => null,
    getWallDecision = () => ({ locked: false, kind: null }),
    getSpeaker = () => 'companion',
    getCommitments = () => [],
    getPersonaAbout = () => [],
    getGeminiExtras = () => ({}),
    getGearWhat = () => '',
  } = deps

  const tz = () => zoneOr(getConfig()?.timeZone)
  const kbPathNow = () => kbDir(notesDir(), home())
  const mdLongPath = () => archivePathInKb(kbPathNow())

  const loadRawBank = () => readState(stateFile('bank.json'), null)
  const saveRawBank = (b) => writeJson(stateFile('bank.json'), b)
  const loadBankUnlock = () => cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null))
  const saveBankUnlock = (s) => writeJson(stateFile('bank-unlock.json'), s)

  const loadHistoryStore = () => normalizeHistoryStore(readState(stateFile('history-365.json'), null))
  const saveHistoryStore = (s) => writeJson(stateFile('history-365.json'), normalizeHistoryStore(s))
  const loadReportsStore = () => normalizeReportsStore(readState(stateFile('evening-reports.json'), null))
  const saveReportsStore = (s) => writeJson(stateFile('evening-reports.json'), normalizeReportsStore(s))
  const loadStorylineStore = (now = Date.now()) =>
    normalizeStoryline(readState(stateFile('intraday-storyline.json'), null), { now, tz: tz() })
  const saveStorylineStore = (s) => writeJson(stateFile('intraday-storyline.json'), s)

  let pendingAutoPull = null
  let lastHistorySyncAt = 0
  let lastHistorySyncDay = ''

  function recordStoryline(event, now = Date.now()) {
    const next = appendStorylineEvent(loadStorylineStore(now), event, { now, tz: tz() })
    saveStorylineStore(next)
    return next
  }

  function getStorylinePromptBlock(now = Date.now()) {
    return formatIntradayPromptBlock(loadStorylineStore(now), now, tz())
  }

  function buildProactiveGeminiBrief({ topic = '', mode = 'think', minutes = 20, now = Date.now() } = {}) {
    const storyBlock = getStorylinePromptBlock(now)
    const base = geminiBrief({
      owner: String(getConfig()?.owner || '他'),
      topic,
      minutes,
      mode: geminiMode(mode),
      about: typeof getPersonaAbout === 'function' ? getPersonaAbout(now) : [],
      commitments: typeof getCommitments === 'function' ? getCommitments(now) : [],
      recap: (loadGemini()?.recaps || []).slice(-1)[0] || null,
      gear: typeof getGearWhat === 'function' ? getGearWhat() : null,
      ...(typeof getGeminiExtras === 'function' ? getGeminiExtras(now) : {}),
    })
    if (!storyBlock) return base
    return `${base}\n${storyBlock}`.slice(0, 3200)
  }

  const loadHorizonsSynced = () => {
    const raw = readState(stateFile('horizons.json'), null)
    return syncLongGoalsFromMarkdown(raw, mdLongPath())
  }
  const saveHorizonsSynced = (next) => {
    const clean = cleanHorizons(next)
    writeJson(stateFile('horizons.json'), clean)
    mirrorHorizonsToMarkdown(clean, mdLongPath())
    return clean
  }

  const loadContract = () => cleanPlanContract(readState(stateFile('plan-contract.json'), null))
  const saveContract = (c) => writeJson(stateFile('plan-contract.json'), c)

  function computeAutonomy(now = Date.now()) {
    const tzName = tz()
    const day = dayString(now, tzName)
    const ledger = readLedger(home(), 300)
    const todayEntries = ledger.filter((e) => e && dayString(Number(e.at) || 0, tzName) === day)
    const completedBouts = todayEntries.filter((e) => e.state === 'bout-close' || e.state === 'bout-done').length
    const checkpointsPassed = todayEntries.filter((e) => e.state === 'checkpoint-passed').length
    const violationsToday = todayEntries.filter((e) => e.state === 'defied' || e.state === 'emergency-exit').length
    const bankSt = loadBankUnlock()
    const contract = loadContract()
    const overdueCheckpoints = (contract?.checkpoints || []).filter((c) => c.status === 'overdue_locked').length
    const rep = usageReportView(now, tzName)
    const funMin = Number(rep.funMinutes) || 0
    const focusMin = Math.max(0, (Number(rep.totalMinutes) || 0) - funMin)
    return evaluateAutonomyLevel({
      completedBouts,
      checkpointsPassed,
      quizSolvedToday: bankSt.stats.correct,
      focusMinutes: focusMin,
      funMinutes: funMin,
      violationsToday,
      overdueCheckpoints,
    })
  }

  // ---- HTTP 路由分发 ----
  function horizonsRoute(body = {}) {
    const now = Date.now()
    const action = String(body?.action || 'list')
    const cur = loadHorizonsSynced()
    if (action === 'list') {
      return { v: 1, ok: true, horizons: cur }
    }
    if (action === 'delete') {
      const next = saveHorizonsSynced(deleteHorizonItem(cur, body?.id))
      return { v: 1, ok: true, horizons: next }
    }
    if (action === 'push_to_today') {
      const all = [...cur.medium, ...cur.long]
      const target = all.find((x) => x.id === String(body?.id || ''))
      if (!target) return { v: 1, ok: false, error: '未找到对应主线条目' }
      const existing = planDayRoute({ action: 'list' })?.plan?.items || []
      const newTask = {
        id: `hz-${now.toString(36).slice(-5)}`,
        title: `${target.title}${body?.subtask ? `：${String(body.subtask).slice(0, 40)}` : ''}`.slice(0, 80),
        protocolId: 'execute',
        minutes: Math.max(15, Math.min(180, Number(body?.minutes) || 45)),
        parentHorizonId: target.id,
        strict: true,
      }
      planDayRoute({ action: 'set', items: [...existing, newTask], by: 'horizon' })
      record({ state: 'horizon-push-today', at: now, horizonId: target.id, title: newTask.title })
      return { v: 1, ok: true, pushed: newTask, horizons: cur }
    }
    // upsert / bumpStep
    const up = upsertHorizonItem(cur, body, now)
    if (!up.ok) return { v: 1, ok: false, error: up.error, horizons: cur }
    const next = saveHorizonsSynced(up.state)
    record({ state: 'horizon-upsert', at: now, horizon: up.item.horizon, id: up.item.id, title: up.item.title })
    return { v: 1, ok: true, item: up.item, horizons: next }
  }

  function bankRoute(body = {}) {
    const now = Date.now()
    const action = String(body?.action || 'status')
    const rawBank = loadRawBank()
    const st = loadBankUnlock()

    if (action === 'status' || action === 'quiz_session') {
      const preview = pickNextQuizQuestion(rawBank, st)
      if (preview.state) saveBankUnlock(preview.state)
      return {
        v: 1,
        ok: true,
        config: st.config,
        stats: st.stats,
        wrongCount: st.wrongIds.length,
        bankName: preview.bankName || String(rawBank?.名称 || '未导入'),
        bankTotal: preview.bankTotal,
        solved: preview.solved,
        required: preview.required,
        unlockMinutes: preview.unlockMinutes,
        question: preview.question,
      }
    }

    if (action === 'set_config') {
      const next = {
        ...st,
        config: {
          requiredCount: Math.max(1, Math.min(30, Number(body?.requiredCount) || st.config.requiredCount)),
          unlockMinutes: Math.max(1, Math.min(30, Number(body?.unlockMinutes) || st.config.unlockMinutes)),
        },
      }
      saveBankUnlock(next)
      return { v: 1, ok: true, config: next.config }
    }

    if (action === 'import') {
      const parsed = parseBankSmart(body?.raw ?? body?.text ?? body?.bank, {
        name: body?.name || '备考题库',
        id: body?.id || `bank-${now.toString(36).slice(-4)}`,
      })
      if (!parsed.ok) return { v: 1, ok: false, error: parsed.error }
      const merge = Boolean(body?.append && rawBank && Array.isArray(rawBank.题目))
      const mergedBank = merge
        ? {
            ...parsed.rawBank,
            名称: body?.name || rawBank.名称 || parsed.rawBank.名称,
            题目: [
              ...rawBank.题目,
              ...parsed.rawBank.题目.map((q, idx) => ({ ...q, id: `${q.id || 'q'}-m${rawBank.题目.length + idx + 1}` })),
            ],
          }
        : parsed.rawBank
      saveRawBank(mergedBank)
      saveBankUnlock({ ...st, session: { solved: 0, seenIds: [], currentId: '' }, wrongIds: merge ? st.wrongIds : [] })
      record({ state: 'bank-imported', at: now, name: mergedBank.名称, total: mergedBank.题目.length })
      return {
        v: 1,
        ok: true,
        importedCount: parsed.count,
        bankTotal: mergedBank.题目.length,
        bankName: mergedBank.名称,
        warnings: parsed.warnings,
      }
    }

    if (action === 'quiz_answer') {
      const judged = judgeQuizAnswer(rawBank, st, {
        questionId: body?.questionId,
        answer: body?.answer,
        now,
      })
      if (!judged.ok) return { v: 1, ok: false, error: judged.error }
      saveBankUnlock(judged.state)
      if (judged.unlocked) {
        try {
          grantTempPass?.(judged.unlockUntil, `刷题达标 ${judged.solved} 题（${judged.unlockMinutes}m）`)
        } catch {
          // ignore
        }
        record({
          state: 'quiz-unlock',
          at: now,
          solved: judged.solved,
          unlockMinutes: judged.unlockMinutes,
          until: judged.unlockUntil,
        })
        recordStoryline({
          kind: 'quiz_unlock',
          actor: 'user',
          summary: `锁屏答对 ${judged.solved} 题解锁 ${judged.unlockMinutes}m 通行`,
          at: now,
        }, now)
      }
      return { v: 1, ...judged }
    }

    return { v: 1, ok: false, error: `未知 action: ${action}` }
  }

  function contractSessionView(now = Date.now(), { escalatedOnly = false } = {}) {
    const cur = loadContract()
    if (!cur) return null
    const ticked = evaluateContractTick(cur, now)
    const c = ticked.contract || cur
    if (ticked.changed && ticked.contract) saveContract(ticked.contract)
    const openCps = (c.checkpoints || []).filter((cp) => cp.status !== 'passed')
    if (!openCps.length) return null
    const escalated = Boolean(ticked.triggerStrictNow || c.strictEscalated)
    if (escalatedOnly && !escalated) return null

    const emerg = typeof emergencyPackages === 'function' ? emergencyPackages(appsOf()) : []
    const funSet = new Set((appsOf() || []).filter((a) => a?.role === 'fun').map((a) => String(a.app || '')))
    const baseAllow = resolveContractSessionAllow(c, { escalated, isFunApp: (pkg) => funSet.has(pkg) })
    const allow = [...new Set([...baseAllow, ...emerg])]
    const nextDue = openCps.reduce((min, cp) => (cp.dueAt > 0 && cp.dueAt < min ? cp.dueAt : min), now + 45 * 60_000)

    return {
      allow,
      until: escalated ? now + 30 * 60_000 : Math.max(now + 60_000, nextDue + OVERDUE_GRACE_MS),
      topic: c.title,
      why: escalated
        ? `契约「${c.title}」逾期未过验收，已进入严格锁定（保留笔记工具提交验收）`
        : `契约护航「${c.title}」（到点缓冲 1 分钟未验收将自动严锁）`,
      strict: escalated,
      graceMs: escalated ? 0 : 25_000,
      mode: escalated ? 'ai-work' : 'task-lease',
    }
  }

  function autonomyGapScale(autonomy) {
    const lvl = autonomy?.level || 'L1'
    if (lvl === 'L3') return 1.8
    if (lvl === 'L2') return 1.4
    if (lvl === 'L0') return 0.85
    return 1.0
  }

  function planContractRoute(body = {}) {
    const now = Date.now()
    const action = String(body?.action || 'status')
    if (action === 'presets') {
      return { v: 1, ok: true, presets: COMMON_PLAN_PRESETS, scripts: AI_PROMPT_SCRIPTS }
    }
    if (action === 'check_apps') {
      return {
        v: 1,
        ...inspectScenarioApps({
          scenario: body?.scenario || '',
          declaredApps: appsOf(),
          phoneUsage: getLastPhone()?.today || [],
          requestedAllow: body?.allowedApps || [],
        }),
      }
    }
    if (action === 'status') {
      const cur = loadContract()
      const ticked = evaluateContractTick(cur, now)
      if (ticked.changed && ticked.contract) saveContract(ticked.contract)
      return {
        v: 1,
        ok: true,
        contract: ticked.contract,
        graceWarning: ticked.graceWarning,
        triggerStrictNow: ticked.triggerStrictNow,
        graceSecDefault: Math.round(OVERDUE_GRACE_MS / 1000),
        presets: COMMON_PLAN_PRESETS,
        scripts: AI_PROMPT_SCRIPTS,
      }
    }
    if (action === 'set') {
      const preset = body?.presetId ? COMMON_PLAN_PRESETS.find((p) => p.id === body.presetId) : null
      const base = body?.contract && typeof body.contract === 'object' ? body.contract : body
      const mins = Math.max(5, Math.min(240, Number(base?.minutes || preset?.minutes || 45)))
      const rawCps = Array.isArray(base?.checkpoints) && base.checkpoints.length
        ? base.checkpoints
        : [{
            id: `cp-${now.toString(36).slice(-4)}`,
            title: String(base?.checkpointTitle || preset?.checkpointTitle || base?.title || '提交本段交付并验收'),
            mode: (base?.mode || preset?.mode) === 'video_extract' ? 'video_extract' : 'standard',
            expectedDeliverable: String(base?.expectedDeliverable || preset?.expectedDeliverable || '提交结构化提炼'),
            dueAt: now + mins * 60_000,
            graceMs: OVERDUE_GRACE_MS,
          }]
      const next = cleanPlanContract({
        ...preset,
        ...base,
        title: base?.title || preset?.title || 'AI 协同契约计划',
        scenario: base?.scenario ?? preset?.scenario ?? '',
        allowedApps: Array.isArray(base?.allowedApps) && base.allowedApps.length ? base.allowedApps : (preset?.allowedApps || ['md.obsidian', 'com.tencent.mm']),
        checkpoints: rawCps,
        id: base?.id || `contract-${now.toString(36).slice(-5)}`,
        createdAt: now,
        updatedAt: now,
      })
      if (!next) return { v: 1, ok: false, error: '契约格式无效，至少提供 title 和 checkpoints' }
      saveContract(next)
      record({ state: 'plan-contract-set', at: now, id: next.id, title: next.title, checkpoints: next.checkpoints.length })
      recordStoryline({
        kind: 'contract_set',
        actor: 'user',
        summary: `签下契约「${next.title}」（${next.checkpoints.length}个验收点）`,
        at: now,
      }, now)
      return { v: 1, ok: true, contract: next }
    }
    if (action === 'verify') {
      const cur = loadContract()
      const res = evaluateCheckpointSubmission(cur, {
        checkpointId: body?.checkpointId,
        summary: body?.summary || '',
        aiConfirmed: Boolean(body?.aiConfirmed),
        now,
      })
      if (res.contract) saveContract(res.contract)
      if (res.passed && res.checkpoint) {
        record({ state: 'checkpoint-passed', at: now, id: res.checkpoint.id, title: res.checkpoint.title })
        recordStoryline({
          kind: 'contract_verify',
          actor: 'strategist',
          summary: `验收通过「${res.checkpoint.title}」`,
          at: now,
        }, now)
        if (!res.contract?.strictEscalated) {
          try {
            presetRt?.strictOff?.(now, 'contract-passed')
          } catch {
            // ignore
          }
        }
        if (res.contract?.horizonId) {
          horizonsRoute({ action: 'upsert', id: res.contract.horizonId, bumpStep: true })
        }
      }
      return { v: 1, ...res }
    }
    if (action === 'clear') {
      saveContract(null)
      try {
        presetRt?.strictOff?.(now, 'contract-cleared')
      } catch {
        // ignore
      }
      return { v: 1, ok: true, contract: null }
    }
    return { v: 1, ok: false, error: `未知 action: ${action}` }
  }

  function skillsAuditRoute(body = {}) {
    const kind = String(body?.kind || 'redflag')
    if (kind === 'redflag') {
      return { v: 1, ...runRedFlagScamAudit(body?.text || '', { amount: body?.amount, platformGuarantee: body?.platformGuarantee }) }
    }
    if (kind === 'mainline') {
      const charts = buildTodayCharts({ usageReport: usageReportView(), ledger: readLedger(home(), 200), tz: tz() })
      return {
        v: 1,
        ...runMainlineAlignmentAudit(body?.text || '', {
          longGoals: loadHorizonsSynced().long,
          toolTinkerMinToday: charts.buckets.toolTinkerMin,
        }),
      }
    }
    if (kind === 'causal') {
      return { v: 1, ...compileCausalChainCard(body) }
    }
    return { v: 1, ok: false, error: 'unknown audit kind' }
  }

  function getGeminiStats(day, now = Date.now()) {
    const tzName = tz()
    return trackDailyGemini({
      phone: getLastPhone?.(),
      episodesState: loadEpisodes?.(),
      geminiState: loadGemini?.(),
      historyStore: loadHistoryStore(),
      day: day || dayString(now, tzName),
      now,
      tz: tzName,
    })
  }

  function computeLockArbiter(now = Date.now(), overrides = {}) {
    const ext = loadExternal?.() || {}
    const emergencyPass = isEmergencyPassActive(ext, now) ? ext.emergencyPass : null
    const geminiSession = overrides.geminiSession !== undefined ? overrides.geminiSession : getGeminiSession?.(now)
    const aiWorkSession = overrides.aiWorkSession !== undefined ? overrides.aiWorkSession : getAiWorkSession?.(now)
    const contractSession = overrides.contractSession !== undefined ? overrides.contractSession : contractSessionView(now)
    const taskLease = overrides.taskLease !== undefined ? overrides.taskLease : getTaskLease?.(now)
    const presetStrict = overrides.presetStrict !== undefined ? overrides.presetStrict : presetRt?.sessionView?.(now)
    const wall = overrides.wall !== undefined ? overrides.wall : getWallDecision?.(now)
    const autonomy = overrides.autonomy || computeAutonomy(now)
    return computeUnifiedLockArbiter({
      emergencyPass,
      geminiSession,
      aiWorkSession,
      contractSession,
      taskLease,
      presetStrict,
      wall,
      autonomy,
      now,
    })
  }

  function getUnifiedTasks(now = Date.now()) {
    const bouts = boutsGetRoute()
    const plan = planDayRoute({ action: 'list' })
    const daily = loadDailyToday?.(now) || { tasks: [] }
    return computeUnifiedTodayTasks({
      planItems: Array.isArray(plan?.plan?.items) ? plan.plan.items : [],
      dailyTasks: Array.isArray(daily?.tasks) ? daily.tasks : [],
      boutQueue: Array.isArray(bouts?.queue) ? bouts.queue : [],
      contract: loadContract(),
      horizons: loadHorizonsSynced(),
    })
  }

  function buildReportInputs(targetDay, now = Date.now()) {
    const tzName = tz()
    const episodesState = loadEpisodes?.() || null
    const historyStore = loadHistoryStore()
    const reportsStore = loadReportsStore()
    const usageReport = usageReportView(now, tzName)
    const ledger = readLedger(home(), 500)
    const contract = loadContract()
    const horizons = loadHorizonsSynced()
    const bankSt = loadBankUnlock()
    const rawBank = loadRawBank()
    const bankStats = {
      ...bankSt.stats,
      bankTotal: Array.isArray(rawBank?.题目) ? rawBank.题目.length : 0,
      requiredCount: bankSt.config.requiredCount,
      unlockMinutes: bankSt.config.unlockMinutes,
    }
    const geminiStats = getGeminiStats(targetDay, now)
    const lockArbiter = computeLockArbiter(now)
    return {
      day: targetDay,
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
      kbDirPath: kbPathNow(),
      storyline: loadStorylineStore(now),
      planned: (() => {
        try {
          const unified = getUnifiedTasks(now)
          const list = Array.isArray(unified?.tasks) ? unified.tasks : (Array.isArray(unified) ? unified : [])
          return list.length
        } catch {
          return 0
        }
      })(),
      now,
      tz: tzName,
    }
  }

  /** 2026-11-18 验收器：周六晚报出来后，把这一周汇总写进 大肥鱼/周报/<周六>.md。 */
  function writeWeeklyIfSaturday(targetDay) {
    if (!isSaturday(targetDay)) return ''
    const kb = kbPathNow()
    if (!kb) return ''
    try {
      const dir = join(kb, '周报')
      mkdirSyncV(dir, { recursive: true })
      const file = join(dir, `${weekEndingSaturday(targetDay)}.md`)
      writeFileSyncV(file, weeklyMarkdown(loadReportsStore()?.reports || {}, targetDay), 'utf8')
      return file
    } catch {
      return ''
    }
  }

  /** 最近的截止日（今天起 14 天内）。 */
  function upcomingDeadlines(now = Date.now()) {
    return upcomingDeadlinesOf(loadHorizonsSynced(), dayString(now, tz()), 14)
  }

  /** 进两姐妹每一轮上下文：截止日 + 昨天的判决（早上那几轮最该提）。 */
  function verifierPromptLines(now = Date.now()) {
    const tzName = tz()
    const today = dayString(now, tzName)
    const lines = deadlineLines(loadHorizonsSynced(), today)
    const y = loadReportsStore()?.reports?.[shiftYmdLocal(today, -1)]
    if (y?.verdict?.line) lines.push('昨天的判决（代码按账本算的，不许替他找补）：' + y.verdict.line.replace(/^今天：/, ''))
    return lines
  }

  function shiftYmdLocal(ymd, delta) {
    const t = Date.parse(ymd + 'T00:00:00Z') + delta * 86_400_000
    return new Date(t).toISOString().slice(0, 10)
  }

  function syncTodayHistory(now = Date.now()) {
    const tzName = tz()
    const today = dayString(now, tzName)
    if (lastHistorySyncDay === today && now - lastHistorySyncAt < 60_000) return
    lastHistorySyncDay = today
    lastHistorySyncAt = now
    const inputs = buildReportInputs(today, now)
    const overview = buildAnalyticsOverview({ ...inputs, rangeDays: 7 })
    const nextHist = upsertHistoryDay(inputs.historyStore, {
      day: today,
      buckets: overview.buckets,
      topApps: overview.topApps,
      geminiStats: overview.geminiStats,
      updatedAt: now,
    })
    saveHistoryStore(nextHist)
  }

  function generateAndSaveEveningReport(day, now = Date.now()) {
    const tzName = tz()
    const targetDay = day && /^\d{4}-\d{2}-\d{2}$/.test(String(day)) ? String(day) : dayString(now, tzName)
    const inputs = buildReportInputs(targetDay, now)
    const report = generateEveningReport(inputs)
    const nextReports = upsertEveningReport(inputs.reportsStore, report)
    saveReportsStore(nextReports)
    const weeklyPath = writeWeeklyIfSaturday(targetDay)
    record({ state: 'evening-report-generated', at: now, day: targetDay, score: report.score, verdict: report.verdict?.tag, path: report.path || undefined, weekly: weeklyPath || undefined })
    recordStoryline({
      kind: 'evening_report',
      actor: 'strategist',
      summary: `生成 ${targetDay} 晚间总报告（${report.verdict?.tag || ''} · 战力 ${report.score}/100）`,
      at: now,
    }, now)
    return report
  }

  function analyticsRoute(body = {}) {
    const now = Date.now()
    const tzName = tz()
    const action = String(body?.action || 'overview')
    const targetDay = body?.day && /^\d{4}-\d{2}-\d{2}$/.test(String(body.day)) ? String(body.day) : dayString(now, tzName)
    const rangeDays = Number(body?.rangeDays) || 7

    if (action === 'generate_report') {
      const report = generateAndSaveEveningReport(targetDay, now)
      const inputs = buildReportInputs(targetDay, now)
      const overview = buildAnalyticsOverview({ ...inputs, rangeDays })
      return { v: 1, ok: true, report, overview }
    }

    if (targetDay === dayString(now, tzName)) syncTodayHistory(now)
    const inputs = buildReportInputs(targetDay, now)
    return buildAnalyticsOverview({ ...inputs, rangeDays })
  }

  function storylineRoute(body = {}) {
    const now = Date.now()
    const tzName = tz()
    const action = String(body?.action || 'get')
    let store = loadStorylineStore(now)
    if (action === 'set_gemini_pull_mode') {
      store = setPullMode(store, body?.pullMode, { now, tz: tzName })
      saveStorylineStore(store)
      record({ state: 'gemini-pull-mode', at: now, pullMode: store.pullMode })
    } else if (action === 'append') {
      store = appendStorylineEvent(store, {
        kind: body?.kind || 'note',
        actor: body?.actor || 'user',
        summary: body?.summary || '',
        at: body?.at || now,
      }, { now, tz: tzName })
      saveStorylineStore(store)
    }
    return {
      v: 1,
      ok: true,
      pullMode: store.pullMode,
      storyline: store,
      promptBlock: formatIntradayPromptBlock(store, now, tzName),
    }
  }

  // ---- `/plan` 周期钩子：1 分钟逾期自动转严格锁 + 365 天归档 + 21:30 晚间总报 ----
  function onPlanTick(now = Date.now()) {
    const cur = loadContract()
    let ticked = { contract: null, graceWarning: null, triggerStrictNow: false, changed: false, newlyEscalated: false }
    if (cur) {
      ticked = evaluateContractTick(cur, now)
      if (ticked.changed && ticked.contract) {
        saveContract(ticked.contract)
        if (ticked.newlyEscalated) {
          record({ state: 'checkpoint-overdue-locked', at: now, contractId: ticked.contract.id, title: ticked.contract.title })
          presetRt.strictNow?.(now, `契约验收点超时未交，缓冲 1 分钟已到自动转严格锁`, 'contract')
          recordStoryline({
            kind: 'contract_overdue',
            actor: 'strategist',
            summary: `契约「${ticked.contract.title}」逾期转严格锁`,
            at: now,
          }, now)
        }
      }
    }
    try {
      syncTodayHistory(now)
    } catch {
      // ignore sync error
    }
    let eveningReportGenerated = null
    try {
      const tzName = tz()
      if (shouldAutoTriggerEveningReport(loadReportsStore(), { now, tz: tzName })) {
        eveningReportGenerated = generateAndSaveEveningReport(dayString(now, tzName), now)
      }
    } catch {
      // ignore report generation error
    }
    return {
      contract: ticked.contract,
      graceWarning: ticked.graceWarning,
      forceStrictByContract: ticked.triggerStrictNow,
      autonomy: computeAutonomy(now),
      eveningReportGenerated,
    }
  }

  // ---- 升级后的 Today 视图 ----
  function todayViewV4(now = Date.now()) {
    const tzName = tz()
    const bouts = boutsGetRoute()
    const plan = planDayRoute({ action: 'list' })
    const rep = usageReportView(now, tzName)
    const handoff = handoffRoute({ writeSnapshot: false })
    const queue = Array.isArray(bouts.queue) ? bouts.queue : []
    const tasks = queue.slice(0, 3)
    const funMin = Number(rep.funMinutes) || 0
    const totalMin = Number(rep.totalMinutes) || 0
    const focusMin = Math.max(0, totalMin - funMin)
    const contractTick = onPlanTick(now)
    const bankSt = loadBankUnlock()
    const rawBank = loadRawBank()
    const horizons = loadHorizonsSynced()
    const charts = buildTodayCharts({
      usageReport: rep,
      ledger: readLedger(home(), 500),
      contract: contractTick.contract,
      bankStats: {
        ...bankSt.stats,
        bankTotal: Array.isArray(rawBank?.题目) ? rawBank.题目.length : 0,
        requiredCount: bankSt.config.requiredCount,
        unlockMinutes: bankSt.config.unlockMinutes,
      },
      autonomy: contractTick.autonomy,
      now,
      tz: tzName,
    })

    const headState = bouts.running
      ? `正在专注「${bouts.running.title}」${bouts.running.strict ? '（严格锁）' : ''}`
      : (tasks.length ? `今日主线 ${tasks.length}/${queue.length} 项待推进` : '当前空闲 · 可一键开启契约专注')

    const todayStr = dayString(now, tzName)
    const geminiStats = getGeminiStats(todayStr, now)
    const lockArbiter = computeLockArbiter(now, { autonomy: contractTick.autonomy })
    const activeSisterSkin = resolveActiveSisterSkin({ speaker: getSpeaker?.() || 'companion', lockArbiter })
    const unifiedTasks = getUnifiedTasks(now)

    return {
      v: 1,
      ok: true,
      day: todayStr,
      brief: {
        line: `今日专注约 ${focusMin}m · 娱乐 ${funMin}m · ${headState}`,
        focusMin,
        funMin,
        totalMin,
        hasCognitiveOs: Boolean(handoff.hasCognitiveOs),
        modelsCount: Number(handoff.modelsCount) || 0,
      },
      running: bouts.running,
      strict: Boolean(bouts.strict || contractTick.forceStrictByContract),
      tasks,
      unifiedTasks,
      allQueueCount: queue.length,
      defaultAllow: Array.isArray(getConfig()?.defaultAllow) && getConfig().defaultAllow.length ? getConfig().defaultAllow : ['md.obsidian', 'com.tencent.mm'],
      recentApps: Array.isArray(plan.recent) ? plan.recent : [],
      lastDice: Number(getLastRolledDice?.()) || 0,
      heartbeat: (!bouts.running && queue.length === 0) ? 'HEARTBEAT_OK' : 'ACTIVE',
      // V4 新增富数据：图表、三层主线、自律分级、计划契约与题库概况
      charts,
      horizons,
      autonomy: contractTick.autonomy,
      contract: contractTick.contract,
      graceWarning: contractTick.graceWarning,
      presets: COMMON_PLAN_PRESETS,
      promptScripts: AI_PROMPT_SCRIPTS,
      geminiMinutesToday: geminiStats.minutesToday,
      geminiStats,
      lockArbiter,
      activeSisterSkin,
      bankSummary: {
        bankName: String(rawBank?.名称 || '未导入'),
        bankTotal: Array.isArray(rawBank?.题目) ? rawBank.题目.length : 0,
        requiredCount: bankSt.config.requiredCount,
        unlockMinutes: bankSt.config.unlockMinutes,
        wrongCount: bankSt.wrongIds.length,
        solved: bankSt.session.solved,
      },
    }
  }

  // ---- 交给 AI 的 5 把结构化工具 ----
  const globalTools = [
    {
      name: 'plan_check_apps',
      description: '进入计划模态时首先调用：根据用户接下来要干的事（看视频提炼、刷题、写简历等），核验本机现有 App 是否足够、推荐允许白名单，减少瞎猜。',
      parameters: {
        type: 'object',
        properties: {
          scenario: { type: 'string', description: '用户本次计划情景（如：看 B 站视频学资料分析并用 Obsidian 提炼）' },
          allowedApps: { type: 'array', items: { type: 'string' }, description: '已考虑放行的包名' },
        },
        required: ['scenario'],
      },
      output: toolOutput,
      execute: (args = {}) => {
        const res = planContractRoute({ action: 'check_apps', ...args })
        return {
          ok: true,
          text: (res.sufficient ? '本机 App 充足。' : `注意缺口：${res.gaps.join('；')}。`) +
            ` 建议白名单：${res.recommendedAllow.map((x) => x.label).join('、') || '暂无'}。`,
        }
      },
    },
    {
      name: 'plan_set_contract',
      description: '帮用户建立「AI 协同计划契约」：支持直接传 presetId（如 video_extract_study / exam_drill_sprint / job_resume_apply / rest_recover_contract）或自定义标题、允许 App、验收点；到点缓冲 1 分钟未交付自动转严格锁。',
      parameters: {
        type: 'object',
        properties: {
          presetId: {
            type: 'string',
            enum: ['video_extract_study', 'exam_drill_sprint', 'job_resume_apply', 'anti_scam_decision_gate', 'rest_recover_contract'],
            description: '可选预设剧本ID，传了自动填充常用参数',
          },
          title: { type: 'string', description: '计划契约总目标' },
          scenario: { type: 'string', description: '使用场景与产出物描述' },
          allowedApps: { type: 'array', items: { type: 'string' }, description: '契约期内允许使用的 App 包名' },
          minutes: { type: 'integer', minimum: 5, maximum: 240, description: '距离首个验收点截止分钟数' },
          checkpointTitle: { type: 'string', description: '首个具体物理验收点标题' },
          mode: { type: 'string', enum: ['standard', 'video_extract'], description: 'video_extract=看视频提炼高强度问答验收' },
          expectedDeliverable: { type: 'string', description: '需提交的内容（如：压缩笔记+过2个机制追问）' },
        },
      },
      output: toolOutput,
      execute: (args = {}) => {
        const out = planContractRoute({
          action: 'set',
          presetId: args.presetId,
          title: args.title,
          scenario: args.scenario,
          allowedApps: Array.isArray(args.allowedApps) ? args.allowedApps : undefined,
          minutes: args.minutes,
          checkpointTitle: args.checkpointTitle,
          mode: args.mode,
          expectedDeliverable: args.expectedDeliverable,
          strictOnOverdue: true,
        })
        if (!out.ok) return { ok: false, text: out.error }
        const firstCp = out.contract.checkpoints?.[0]
        return {
          ok: true,
          text: `✓ 已签下契约「${out.contract.title}」，验收点「${firstCp?.title || '交付'}」将于设定时间到期（逾期给予 1 分钟缓冲，仍未交自动转严格锁）。`,
        }
      },
    },
    {
      name: 'plan_verify_checkpoint',
      description: '对用户发来的看视频压缩提炼或成果进行高强度验收：传入 aiConfirmed=true 即可正式通过验收点，并自动触发正向松绑 (autonomyLevel)。',
      parameters: {
        type: 'object',
        properties: {
          checkpointId: { type: 'string' },
          summary: { type: 'string', description: '用户提交的压缩提炼内容或回答要点' },
          aiConfirmed: { type: 'boolean', description: '高强度追问是否真懂并同意通过' },
        },
        required: ['summary', 'aiConfirmed'],
      },
      output: toolOutput,
      execute: (args = {}) => {
        const res = planContractRoute({ action: 'verify', ...args })
        if (res.passed) {
          return { ok: true, text: `✓ 验收点「${res.checkpoint.title}」高强度验收通过！已完成正反馈松绑计算。` }
        }
        return { ok: Boolean(res.ok), text: res.verdictNote || res.error || (res.probes || []).join('\n') }
      },
    },
    {
      name: 'audit_decision_redflag',
      description: '信息收集部 & 谋划思考部联合实战 Skill：消费付款前做红旗防骗一票否决（杜绝 80 元冲动踩坑），或审计新坑是否偏离考公/就业主线，或把心得压成三段因果链。',
      parameters: {
        type: 'object',
        properties: {
          kind: { type: 'string', enum: ['redflag', 'mainline', 'causal'] },
          text: { type: 'string', description: '交易描述、计划动议或待提炼要点' },
          amount: { type: 'number' },
          trigger: { type: 'string' },
          mechanism: { type: 'string' },
          rule: { type: 'string' },
        },
        required: ['kind'],
      },
      output: toolOutput,
      execute: (args = {}) => {
        const res = skillsAuditRoute(args)
        if (!res.ok) return { ok: false, text: res.error }
        if (args.kind === 'causal') return { ok: true, text: `✓ 已提炼因果链卡片：\n${res.card.markdown}` }
        return { ok: true, text: res.recommendation || res.advice }
      },
    },
    {
      name: 'bank_import_questions',
      description: '帮用户把考公行测/网课资料/纯文本题目直接清洗并导入锁屏题库（之后锁屏答对 10 题解锁 5 分钟）。',
      parameters: {
        type: 'object',
        properties: {
          name: { type: 'string' },
          rawText: { type: 'string', description: '原始题目文本或 JSON，每题含题干+答案（可选选项与解析）' },
          append: { type: 'boolean' },
        },
        required: ['rawText'],
      },
      output: toolOutput,
      execute: (args = {}) => {
        const out = bankRoute({ action: 'import', name: args.name || '备考真题库', raw: args.rawText, append: args.append !== false })
        if (!out.ok) return { ok: false, text: `导入失败：${out.error}` }
        return { ok: true, text: `✓ 成功导入 ${out.importedCount} 道题到「${out.bankName}」（当前库共 ${out.bankTotal} 题）。锁屏答对 10 题可解锁 5 分钟。` }
      },
    },
    {
      name: 'generate_evening_report',
      description: '生成或刷新指定日期（默认今天）的「晚间 AI 可视化总报告」，包含 5 桶耗时分布、Mermaid 饼图、战果复盘与明日三件套草案，并自动写入 Obsidian 每日报告目录。',
      parameters: {
        type: 'object',
        properties: {
          day: { type: 'string', description: '可选 YYYY-MM-DD，默认今天' },
        },
      },
      output: toolOutput,
      execute: (args = {}) => {
        const res = analyticsRoute({ action: 'generate_report', day: args?.day })
        const r = res.report
        return {
          ok: true,
          text: `✓ 已生成 ${r.day} 晚间可视化总报告（战力 ${r.score}/100）：${r.headline}${r.path ? `\n已同步写入：${r.path}` : ''}`,
          report: r,
        }
      },
    },
    {
      name: 'proactive_pull_gemini',
      description: '主动越权唤起 Gemini Live（自动带入今日连续叙事脉络与背景简报），下发立即触发指令 geminiAutoPull 并打开 Gemini 共脑舱。',
      parameters: {
        type: 'object',
        properties: {
          topic: { type: 'string', description: '本次连麦议题或审讯靶点' },
          mode: { type: 'string', enum: ['study', 'think', 'interrogate'] },
          minutes: { type: 'integer', minimum: 5, maximum: 180 },
          preferVoice: { type: 'boolean' },
        },
        required: ['topic'],
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tzName = tz()
        const topic = String(args?.topic || '').trim().slice(0, 120) || '推进今日主线卡点'
        const mode = geminiMode(args?.mode)
        const minutes = Math.max(5, Math.min(180, Math.round(Number(args?.minutes) || 20)))
        const preferVoice = args?.preferVoice !== false
        const brief = buildProactiveGeminiBrief({ topic, mode, minutes, now })
        const g = loadGemini?.() || {}
        saveGemini?.({
          ...g,
          session: { topic, mode, minutes, startedAt: now, until: now + minutes * 60_000, by: 'proactive_pull_gemini' },
        })
        const nextStory = markAutoPulled(loadStorylineStore(now), {
          now,
          tz: tzName,
          summary: `主动拉起 Gemini「${topic.slice(0, 40)}」`,
        })
        saveStorylineStore(nextStory)
        record({ state: 'gemini-auto-pull', at: now, topic, mode, minutes, by: 'tool' })
        pendingAutoPull = {
          triggered: true,
          at: now,
          topic,
          mode,
          minutes,
          pullMode: nextStory.pullMode,
          preferVoice,
          brief,
          reason: 'ai-tool',
        }
        return {
          ok: true,
          text: `⚡ 已准备越权唤起 Gemini Live（${topic}，${minutes}m）。背景简报已生成并写入触发队列：\n${brief}`,
          brief,
          geminiAutoPull: pendingAutoPull,
        }
      },
    },
  ]

  return {
    globalTools,
    todayViewV4,
    onPlanTick,
    contractSessionView,
    computeAutonomy,
    autonomyGapScale,
    horizonsRoute,
    bankRoute,
    planContractRoute,
    skillsAuditRoute,
    analyticsRoute,
    storylineRoute,
    computeLockArbiter,
    getGeminiStats,
    getUnifiedTasks,
    recordStoryline,
    getStorylinePromptBlock,
    getPullMode: (now = Date.now()) => loadStorylineStore(now).pullMode,
    getLastPullAt: (now = Date.now()) => loadStorylineStore(now).lastPullAt,
    markStoryAutoPulled: (now = Date.now(), summary = '') => {
      const next = markAutoPulled(loadStorylineStore(now), { now, tz: tz(), summary })
      saveStorylineStore(next)
      return next
    },
    buildProactiveGeminiBrief,
    upcomingDeadlines,
    verifierPromptLines,
    writeWeeklyIfSaturday,
    consumePendingAutoPull: () => {
      const p = pendingAutoPull
      pendingAutoPull = null
      return p
    },
    skillsPromptFor: (dept) => buildSkillsPromptBlock(dept),
  }
}
