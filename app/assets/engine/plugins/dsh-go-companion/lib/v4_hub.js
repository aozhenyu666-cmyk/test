// dsh-go-companion · V4 模块化总装桥接器（防臃肿设计：不往 9000 行 index.js 裸堆代码）
// 聚合：
// - bank_unlock.js (导入题库 + 10 题解锁 5 分钟)
// - autonomy.js (W-3 正向泄压自律等级 autonomyLevel)
// - horizons.js (自由口径三层主线 + 长期规划.md 双向镜像)
// - plan_modality.js (AI 协同计划模态 + 本机 App 检查 + 视频高强度提炼验收 + 1 分钟逾期转严格锁)
// - telemetry_charts.js (Today 真实战果与耗时分布图表)
// - dept_skills.js (四部门防骗红旗 / 反沉没成本 / 三段因果链可执行 Skill)

import { join } from 'node:path'
import {
  build7dQuizStats,
  buildPracticeSession,
  cleanBankUnlockState,
  creditReviewAnswer,
  getWrongBookList,
  judgeQuizAnswer,
  pickNextQuizQuestion,
  recordPracticeAnswer,
  reviewCapFor,
  SELF_JUDGED_WEIGHT,
  toggleQuestionStar,
} from './bank_unlock.js'
import { buildImportPreview, detectQuestionAnomalies, GEMINI_IMPORT_PROMPT_TEMPLATE, normalizeImages, normalizeQuestionKind, parseBankAny } from './bank_import.js'
import { answerLockReviewCard } from './review_cards.js'
import { evaluateAutonomy14d, evaluateAutonomyLevel } from './autonomy.js'
import {
  archivePathInKb,
  cleanHorizons,
  deleteHorizonItem,
  mirrorHorizonsToMarkdown,
  syncLongGoalsFromMarkdown,
  upsertHorizonItem,
  cleanNode,
  findHorizon,
  mapHorizon,
  MAX_NODES,
} from './horizons.js'
import { commitBoutOutcomeToNode, compileBoutFromNode, dossierSummary } from './plan_compiler.js'
import {
  AI_PROMPT_SCRIPTS,
  applyDayPlanAction,
  cleanDayPlanState,
  cleanPlanContract,
  COMMON_PLAN_PRESETS,
  computeDayPlanStats,
  DAYBLOCK_LOCK_CAP_MS,
  evaluateCheckpointSubmission,
  evaluateContractTick,
  evaluateDayBlocksTick,
  inspectScenarioApps,
  issueBossDirectiveBlock,
  judgeDayBlockDelivery,
  OVERDUE_GRACE_MS,
  resolveContractSessionAllow,
  submitDayBlockDelivery,
  summarizeDayBlocksVerify,
} from './plan_modality.js'
import { minuteOfDay as tzMinuteOfDay, startOfDay as tzStartOfDay } from './tz.js'
import { buildTodayCharts } from './telemetry_charts.js'
import {
  buildSkillsPromptBlock,
  compileCausalChainCard,
  runMainlineAlignmentAudit,
  runRedFlagScamAudit,
} from './dept_skills.js'
import {
  buildAnalyticsOverview,
  computeDailyEvaluation,
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
import { mkdirSync as mkdirSyncV, writeFileSync as writeFileSyncV, readdirSync as readdirSyncV, readFileSync as readFileSyncV, rmSync as rmSyncV, statSync as statSyncV } from 'node:fs'
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
    // 2026-11-21 个人复习卡（FSRS）接锁屏：{ next(now) → 卡 | null, get(id) → 卡 | null, rate(id, rating, channel, now) }。
    reviewCards = null,
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
    getAppLedger = () => [],
    getQuotaLine = () => '',
    getGearWhat = () => '',
    getIntensityInfo = () => ({ intensity: 'standard', effectiveIntensity: 'standard', autonomyShift: 0, pendingIntensity: null, emergencyDisarmDailyCap: 3, hardInterventions: { count: 0, cap: 12, capped: false, left: 12 } }),
    getPassLedgerSummary = () => null,
    getTimeLedger = () => null,
    onBankUnlock = null,
    requestGeminiGate = null,
    getEmergencyPassActive = null,
    getWorkStore = null,
    upsertWorkItemNow = null,
    tierOf = () => 'other',
    getSealSession = () => null,
    getVerifySession = () => null,
    getSealLines = () => [],
    markWorkStatusNow = null,
    getArbiterEnforce = () => true,
    getGeminiAccounting = null,
    getLockRequests = () => [],
    getTodayBrainStats = null,
    onContractClear = null,
    snoozeGeminiAutoNow = null,
    onDayPlanMutated = null,
    onDayBlockPassed = null,
    getSealsNow = null,
  } = deps

  const tz = () => zoneOr(getConfig()?.timeZone)
  const kbPathNow = () => kbDir(notesDir(), home())
  const mdLongPath = () => archivePathInKb(kbPathNow())

  const loadRawBank = () => readState(stateFile('bank.json'), null)
  const saveRawBank = (b) => writeJson(stateFile('bank.json'), b)
  const loadBankUnlock = () => cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null))
  const saveBankUnlock = (s) => writeJson(stateFile('bank-unlock.json'), s)

  const CATALOG_FILE = 'bank-catalog.json'
  const cleanCatalogQuestion = (q, fallbackId) => {
    if (!q || typeof q !== 'object') return null
    const stem = String(q.题干 ?? q.stem ?? '').trim()
    if (!stem) return null
    const opts = Array.isArray(q.选项)
      ? q.选项.map((x) => String(x ?? '').trim()).filter(Boolean)
      : (Array.isArray(q.options) ? q.options.map((x) => String(x ?? '').trim()).filter(Boolean) : [])
    const type = String(q.题型 ?? q.type ?? '').trim()
    const rawAns = String(q.答案 ?? q.answer ?? '').trim()
    const kind = normalizeQuestionKind(q.kind ?? q.形态, opts, type, rawAns)
    const answer = kind === 'card' ? (rawAns || '已阅读掌握') : rawAns
    if (!answer) return null
    const rawAna = Array.isArray(q.解析) ? q.解析.join('；') : String(q.解析 ?? q.analysis ?? '').trim()
    const analysis = rawAna || (kind === 'card' ? '（知识展示卡）巩固理解核心规律即可' : ('（题库没给解析）参考答案：' + answer))
    const images = normalizeImages(q.images ?? q.图片)
    const out = {
      id: String(q.id || fallbackId).trim() || fallbackId,
      题干: stem,
      ...(opts.length >= 2 ? { 选项: opts } : {}),
      答案: answer,
      解析: analysis,
      kind,
    }
    if (type) out.题型 = type
    if (q.定位句) out.定位句 = String(q.定位句).trim()
    if (q.来源) out.来源 = String(q.来源).trim()
    if (Number.isInteger(Number(q.年份)) && Number(q.年份) > 0) out.年份 = Number(q.年份)
    if (images.length) out.images = images
    return out
  }

  const cleanCatalogBank = (b, idx = 0, now = Date.now()) => {
    if (!b || typeof b !== 'object') return null
    const id = String(b.id || `bank-${idx + 1}`).trim()
    const name = String(b.name || b.名称 || '未命名题库').trim().slice(0, 60)
    const rawQs = Array.isArray(b.questions) ? b.questions : (Array.isArray(b.题目) ? b.题目 : [])
    const questions = []
    const seenIds = new Set()
    rawQs.forEach((q, qi) => {
      const cq = cleanCatalogQuestion(q, `${id}:q-${qi + 1}`)
      if (!cq) return
      if (seenIds.has(cq.id)) cq.id = `${id}:q-${qi + 1}-${seenIds.size + 1}`
      seenIds.add(cq.id)
      questions.push(cq)
    })
    return {
      id,
      name,
      importedAt: Number(b.importedAt) || now,
      sourceFormat: String(b.sourceFormat || 'unknown'),
      enabled: b.enabled !== false,
      questions,
    }
  }

  const syncCatalogToLegacyBankJson = (catalog) => {
    const banks = Array.isArray(catalog?.banks) ? catalog.banks : []
    const enabledBanks = banks.filter((b) => b && b.enabled !== false)
    const mergedName = enabledBanks.length === 1
      ? enabledBanks[0].name
      : (enabledBanks.length > 1 ? `${enabledBanks[0].name} 等 ${enabledBanks.length} 套` : '未导入题库')
    const mergedQuestions = []
    const usedIds = new Set()
    for (const b of enabledBanks) {
      for (let i = 0; i < b.questions.length; i += 1) {
        const q = b.questions[i]
        let qid = String(q.id || `${b.id}:q-${i + 1}`)
        if (usedIds.has(qid)) qid = `${b.id}:${qid}`
        while (usedIds.has(qid)) qid = `${qid}-${usedIds.size + 1}`
        usedIds.add(qid)
        const item = {
          id: qid,
          题干: q.题干,
          ...(Array.isArray(q.选项) && q.选项.length >= 2 ? { 选项: q.选项 } : {}),
          答案: q.答案,
          解析: q.解析,
          kind: q.kind || normalizeQuestionKind(q.kind, q.选项, q.题型, q.答案),
          ...(q.定位句 ? { 定位句: q.定位句 } : {}),
          ...(q.题型 ? { 题型: q.题型 } : {}),
          ...(q.来源 ? { 来源: q.来源 } : {}),
          ...(q.年份 ? { 年份: q.年份 } : {}),
          ...(Array.isArray(q.images) && q.images.length ? { images: q.images } : {}),
        }
        mergedQuestions.push(item)
      }
    }
    saveRawBank({
      题库版本: 1,
      id: 'catalog-merged',
      名称: mergedName,
      题目: mergedQuestions,
    })
  }

  const saveBankCatalog = (catalog) => {
    const clean = {
      v: 2,
      banks: (Array.isArray(catalog?.banks) ? catalog.banks : []).map((b, i) => cleanCatalogBank(b, i)).filter(Boolean),
    }
    writeJson(stateFile(CATALOG_FILE), clean)
    syncCatalogToLegacyBankJson(clean)
    return clean
  }

  const loadBankCatalog = (now = Date.now()) => {
    const stored = readState(stateFile(CATALOG_FILE), null)
    if (stored && typeof stored === 'object' && Array.isArray(stored.banks)) {
      return {
        v: 2,
        banks: stored.banks.map((b, i) => cleanCatalogBank(b, i, now)).filter(Boolean),
      }
    }
    const legacy = loadRawBank()
    if (legacy && Array.isArray(legacy.题目) && legacy.题目.length > 0) {
      const migrated = cleanCatalogBank({
        id: 'legacy-default',
        name: String(legacy.名称 || '默认题库').slice(0, 60),
        importedAt: now,
        sourceFormat: 'legacy',
        enabled: true,
        questions: legacy.题目.map((q, i) => ({ ...q, id: String(q?.id || `q-${i + 1}`) })),
      }, 0, now)
      const catalog = { v: 2, banks: migrated ? [migrated] : [] }
      writeJson(stateFile(CATALOG_FILE), catalog)
      return catalog
    }
    return { v: 2, banks: [] }
  }

  const summarizeBank = (bank, st) => {
    const qStats = st?.questionStats || {}
    const kindCounts = { mcq: 0, card: 0, short_qa: 0 }
    let seenQuestions = 0
    let answered = 0
    let correct = 0
    let anomalyCount = 0
    for (const q of bank.questions) {
      const kind = normalizeQuestionKind(q.kind, q.选项, q.题型, q.答案)
      kindCounts[kind] = (kindCounts[kind] || 0) + 1
      const qs = qStats[q.id]
      if (qs && qs.seenCount > 0) {
        seenQuestions += 1
        answered += qs.seenCount
        correct += qs.correctCount
      }
      if (detectQuestionAnomalies(q).length > 0) anomalyCount += 1
    }
    const total = bank.questions.length
    return {
      id: bank.id,
      name: bank.name,
      importedAt: bank.importedAt,
      sourceFormat: bank.sourceFormat,
      enabled: bank.enabled !== false,
      total,
      kindCounts,
      seenCount: seenQuestions,
      coveragePct: total > 0 ? Math.round((seenQuestions * 100) / total) : 0,
      answered,
      correct,
      accuracyPct: answered > 0 ? Math.round((correct * 100) / answered) : null,
      anomalyCount,
    }
  }

  const summarizeCatalog = (catalog, st) => (Array.isArray(catalog?.banks) ? catalog.banks : []).map((b) => summarizeBank(b, st))

  const countEnabledQuestions = (catalog) =>
    (Array.isArray(catalog?.banks) ? catalog.banks : [])
      .filter((b) => b && b.enabled !== false)
      .reduce((s, b) => s + b.questions.length, 0)

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

  const DAY_PLAN_ACTIONS = new Set([
    'get_day_plan',
    'set_energy',
    'generate_default',
    'upsert_block',
    'delete_block',
    'set_block_status',
    'move_carryover',
    'set_day_plan',
  ])

  function loadDayPlan(now = Date.now()) {
    const tzName = tz()
    const today = dayString(now, tzName)
    const raw = readState(stateFile('plan-daybox.json'), null)
    if (raw && typeof raw === 'object' && raw.day && raw.day !== today && Array.isArray(raw.dayBlocks)) {
      // 跨天自动将昨日未完成的非休息时间块带入今日准备态待选列表
      const carried = raw.dayBlocks
        .filter((b) => b && !b.isBreak && b.status !== 'done')
        .map((b, i) => ({
          id: `carry-${String(b.id || i + 1)}`,
          title: String(b.title || ''),
          minutes: Math.max(10, Math.min(180, (Number(b.endMin) || 0) - (Number(b.startMin) || 0) || 45)),
          lockMode: b.lockMode || 'strict',
          deliverable: b.deliverable || '',
          allowedApps: Array.isArray(b.allowedApps) ? b.allowedApps : ['md.obsidian', 'com.tencent.mm'],
          fromDay: raw.day,
          moved: false,
        }))
        .filter((c) => c.title)
      return cleanDayPlanState({ energyLevel: raw.energyLevel || 'mid', carryoverItems: carried, dayBlocks: [] }, { day: today, now })
    }
    return cleanDayPlanState(raw, { day: today, now })
  }

  function saveDayPlan(state, now = Date.now()) {
    const today = dayString(now, tz())
    const clean = cleanDayPlanState(state, { day: today, now })
    writeJson(stateFile('plan-daybox.json'), { ...clean, updatedAt: now })
    return clean
  }

  function funBudgetMinNow(now = Date.now()) {
    try {
      const tl = getTimeLedger?.(now, dayString(now, tz()))
      if (tl && Number.isFinite(Number(tl.funLeft))) return Math.max(0, Math.round(Number(tl.funLeft)))
    } catch {}
    return 30
  }

  function computeAutonomy(now = Date.now()) {
    const tzName = tz()
    const day = dayString(now, tzName)
    const ledger = readLedger(home(), 1500)
    const reports = loadReportsStore()?.reports || {}
    const brainView = typeof getTodayBrainStats === 'function' ? (getTodayBrainStats(now) || {}) : {}
    const digests = Array.isArray(brainView.digests) ? brainView.digests : []
    const contract = loadContract()
    const overdueContractCps = (contract?.checkpoints || []).filter((c) => c.status === 'overdue_locked').length
    const overdueDayBlocks = (loadDayPlan(now)?.dayBlocks || []).filter((b) => b.verifyStatus === 'overdue_locked').length
    const overdueCheckpoints = overdueContractCps + overdueDayBlocks
    return evaluateAutonomy14d({
      days: digests,
      reports,
      ledger,
      todayStats: brainView.today || null,
      todayDay: day,
      overdueCheckpoints,
      now,
      dayOf: (ms) => dayString(ms, tzName),
    })
  }

  function dayBlockTickNow(now = Date.now(), sealsOpt = null) {
    const tzName = tz()
    const dayStartMs = tzStartOfDay(now, tzName)
    const min = tzMinuteOfDay(now, tzName)
    const cur = loadDayPlan(now)
    const seals = Array.isArray(sealsOpt) ? sealsOpt : (typeof getSealsNow === 'function' ? (getSealsNow(now) || []) : [])
    const res = evaluateDayBlocksTick(cur, { now, dayStartMs, minuteOfDay: min, seals })
    if (res.changed) {
      saveDayPlan(res.state, now)
    }
    for (const blk of res.newlyEscalated) {
      record({ state: 'dayblock-overdue-locked', at: now, blockId: blk.id, title: blk.title, lockedByBoss: Boolean(blk.lockedByBoss) })
      recordStoryline({
        kind: 'verify_overdue',
        actor: blk.lockedByBoss ? 'wall_boss' : 'strategist',
        summary: `验收锁触发：「${blk.title}」到点超 1m 未交货`,
        at: now,
      }, now)
    }
    for (const blk of res.newlyBreached) {
      record({ state: 'breach', at: now, blockId: blk.id, title: blk.title, reason: '计划验收锁满 60 分钟仍未交付（封顶记违约）' })
      recordStoryline({
        kind: 'verify_breach',
        actor: 'wall_boss',
        summary: `违约记账：「${blk.title}」超 60m 未交货`,
        at: now,
      }, now)
    }
    for (const p of res.newlyPassed) {
      record({ state: 'dayblock-auto-passed', at: now, blockId: p.block.id, title: p.block.title })
      try {
        markWorkStatusNow?.({ id: p.block.id, title: p.block.title, status: 'passed', now })
      } catch {}
    }
    return res
  }

  function getDayVerifySummary(now = Date.now(), sealsOpt = null) {
    const res = dayBlockTickNow(now, sealsOpt)
    return {
      ...res.verifySummary,
      graceWarning: res.graceWarning,
      activeVerifyBlock: res.activeVerifyBlock,
      activeStrictBlock: res.activeStrictBlock,
      overdueVerifyBlocks: res.overdueVerifyBlocks,
      dayBlocks: res.state.dayBlocks,
    }
  }

  function dayBlockOverdueSessionView(now = Date.now()) {
    const tick = dayBlockTickNow(now)
    const list = tick.overdueVerifyBlocks || []
    if (!list.length) return null
    const sorted = [...list].sort((a, b) => (Number(Boolean(b.lockedByBoss)) - Number(Boolean(a.lockedByBoss))) || (a.endMin - b.endMin))
    const blk = sorted[0]
    const funOrGame = new Set(
      (appsOf() || [])
        .filter((a) => a?.role === 'fun' || a?.role === 'game')
        .map((a) => String(a.app || '')),
    )
    const blockAllow = (Array.isArray(blk.allowedApps) ? blk.allowedApps : []).filter((p) => p && !funOrGame.has(p))
    const emerg = typeof emergencyPackages === 'function' ? emergencyPackages(appsOf()) : []
    const allow = [...new Set(['md.obsidian', ...blockAllow, ...emerg])]
    const statusNote = blk.verifyStatus === 'submitted'
      ? '已提交待主管墙终审（PASS 前继续锁定）'
      : (blk.verifyStatus === 'rejected'
          ? `已被主管驳回（${blk.judgeNote || '请补充物理证据后重交'}）`
          : '已超 1 分钟缓冲未提交交付')
    return {
      allow,
      until: Number(blk.overdueLockUntil) > now ? Number(blk.overdueLockUntil) : (now + DAYBLOCK_LOCK_CAP_MS),
      topic: blk.title,
      blockId: blk.id,
      why: `${blk.lockedByBoss ? '主管硬指令' : '验收块'}「${blk.title}」${statusNote}，仅保留工作工具交货`,
      strict: true,
      graceMs: 0,
      mode: 'ai-work',
      by: blk.lockedByBoss ? 'wall_boss' : 'dayblock',
    }
  }

  function dayBlockActiveSessionView(now = Date.now()) {
    const tick = dayBlockTickNow(now)
    const blk = tick.activeVerifyBlock || tick.activeStrictBlock
    if (!blk) return null
    const tzName = tz()
    const day0 = tzStartOfDay(now, tzName)
    const dueAt = day0 + blk.endMin * 60_000
    const isVerify = blk.lockMode === 'verify' || blk.lockedByBoss === true
    const inGrace = isVerify && now >= dueAt && now < dueAt + OVERDUE_GRACE_MS
    const until = inGrace ? (dueAt + OVERDUE_GRACE_MS) : dueAt
    if (until <= now) return null
    const gameSet = new Set(
      (appsOf() || [])
        .filter((a) => a?.role === 'game')
        .map((a) => String(a.app || '')),
    )
    const blockAllow = (Array.isArray(blk.allowedApps) ? blk.allowedApps : []).filter((p) => p && !gameSet.has(p))
    const emerg = typeof emergencyPackages === 'function' ? emergencyPackages(appsOf()) : []
    const allow = [...new Set([...blockAllow, ...emerg])]
    return {
      allow,
      until,
      topic: blk.title,
      blockId: blk.id,
      verifyGuard: isVerify,
      lockedByBoss: Boolean(blk.lockedByBoss),
      why: isVerify
        ? (inGrace
            ? `验收块「${blk.title}」已到点，最后 1 分钟缓冲期请立即提交：${blk.deliverable}`
            : `验收块「${blk.title}」到点自动护航中（结束未交转 P3 验收锁：${blk.deliverable}）`)
        : `专注块「${blk.title}」到点自动接管中（放行本块声明白名单）`,
      strict: true,
      graceMs: 15_000,
      mode: 'ai-work',
      by: 'dayblock_active',
    }
  }

  function submitDelivery(body = {}, now = Date.now()) {
    const cur = loadDayPlan(now)
    const res = submitDayBlockDelivery(cur, {
      blockId: body?.blockId ?? body?.id ?? '',
      summary: body?.summary ?? body?.submissionText ?? '',
      evidenceUrl: body?.evidenceUrl ?? '',
      now,
    })
    if (!res.ok) return { v: 1, ...res }
    const saved = saveDayPlan(res.state, now)
    record({ state: 'dayblock-delivery-submitted', at: now, blockId: res.block.id, title: res.block.title })
    recordStoryline({
      kind: 'delivery_submitted',
      actor: 'user',
      summary: `提交验收交付「${res.block.title}」`,
      at: now,
    }, now)
    onDayPlanMutated?.(now)
    const verifySummary = summarizeDayBlocksVerify(saved.dayBlocks, { minuteOfDay: tzMinuteOfDay(now, tz()) })
    return {
      v: 1,
      ok: true,
      block: res.block,
      dayPlan: {
        ...saved,
        stats: computeDayPlanStats(saved.dayBlocks, { funBudgetMin: funBudgetMinNow(now), energyLevel: saved.energyLevel }),
        verifySummary,
      },
      verifySummary,
    }
  }

  function judgeDelivery(body = {}, now = Date.now()) {
    const tzName = tz()
    const cur = loadDayPlan(now)
    const res = judgeDayBlockDelivery(cur, {
      blockId: body?.blockId ?? body?.id ?? '',
      verdict: body?.verdict ?? '',
      note: body?.note ?? '',
      judgedBy: body?.judgedBy || 'wall_boss',
      now,
      dayStartMs: tzStartOfDay(now, tzName),
      allowAutoFromReceipt: Boolean(body?.allowAutoFromReceipt),
    })
    if (!res.ok) return { v: 1, ...res }
    const saved = saveDayPlan(res.state, now)
    if (res.verdict === 'pass') {
      record({
        state: 'dayblock-judged-pass',
        at: now,
        blockId: res.block.id,
        title: res.block.title,
        judgedBy: res.block.judgedBy,
        rewardMin: res.rewardMin,
      })
      recordStoryline({
        kind: 'delivery_passed',
        actor: res.block.judgedBy === 'wall_boss' ? 'wall_boss' : 'strategist',
        summary: `验收通过「${res.block.title}」${res.rewardMin > 0 ? `（+${res.rewardMin}m 余额）` : ''}`,
        at: now,
      }, now)
      if (res.rewardMin > 0) {
        try {
          onDayBlockPassed?.({ block: res.block, rewardMin: res.rewardMin, now })
        } catch {}
      }
      try {
        markWorkStatusNow?.({ id: res.block.id, title: res.block.title, status: 'passed', now })
      } catch {}
    } else {
      record({
        state: 'dayblock-judged-reject',
        at: now,
        blockId: res.block.id,
        title: res.block.title,
        judgedBy: res.block.judgedBy,
        note: res.block.judgeNote,
      })
      recordStoryline({
        kind: 'delivery_rejected',
        actor: 'wall_boss',
        summary: `验收驳回「${res.block.title}」：${res.block.judgeNote}`,
        at: now,
      }, now)
    }
    onDayPlanMutated?.(now)
    const verifySummary = summarizeDayBlocksVerify(saved.dayBlocks, { minuteOfDay: tzMinuteOfDay(now, tzName) })
    return {
      v: 1,
      ...res,
      dayPlan: {
        ...saved,
        stats: computeDayPlanStats(saved.dayBlocks, { funBudgetMin: funBudgetMinNow(now), energyLevel: saved.energyLevel }),
        verifySummary,
      },
      verifySummary,
    }
  }

  function issueDirective(body = {}, now = Date.now()) {
    const tzName = tz()
    const cur = loadDayPlan(now)
    const res = issueBossDirectiveBlock(cur, {
      title: body?.title ?? '',
      deliverable: body?.deliverable ?? '',
      startMin: body?.startMin,
      endMin: body?.endMin,
      durationMin: body?.durationMin,
      allowedApps: body?.allowedApps,
      now,
      minuteOfDay: tzMinuteOfDay(now, tzName),
    })
    if (!res.ok) return { v: 1, ...res }
    const saved = saveDayPlan(res.state, now)
    record({
      state: 'wall-boss-directive-issued',
      at: now,
      blockId: res.block.id,
      title: res.block.title,
      endMin: res.block.endMin,
    })
    recordStoryline({
      kind: 'boss_directive',
      actor: 'wall_boss',
      summary: `主管下达止损硬指令「${res.block.title}」`,
      at: now,
    }, now)
    onDayPlanMutated?.(now)
    const verifySummary = summarizeDayBlocksVerify(saved.dayBlocks, { minuteOfDay: tzMinuteOfDay(now, tzName) })
    return {
      v: 1,
      ...res,
      dayPlan: {
        ...saved,
        stats: computeDayPlanStats(saved.dayBlocks, { funBudgetMin: funBudgetMinNow(now), energyLevel: saved.energyLevel }),
        verifySummary,
      },
      verifySummary,
    }
  }

  // ---- 预留时间节点（2026-11-20 · P0）----
  // dossier       一条目标的战役档案：目标 + 节点 + 总账 + 预警
  // node_upsert   新建 / 改一个节点（不传 id = 新建）
  // node_delete   删一个节点
  // node_status   改节点状态（passed 达成 / active / planned / slipped），可带一句复盘
  // node_extend   预留日子往后挪几天（调盘选项之一，默认 3 天）
  // node_breakpoint 写一句断点（下一局从哪接）
  // node_start    从节点编译一局放进今天（返回那一条，手机拿它开局）
  // node_commit   局结束写回节点（插件自己调；按 workId 幂等）
  function nodeRoute(action, body, cur, now) {
    const hId = String(body?.horizonId || body?.id || '')
    const h = findHorizon(cur, hId)
    if (!h) return { ok: false, error: 'no-horizon', text: '没找到这条目标' }
    const today = dayString(now, tz())
    if (action === 'dossier') {
      return { ok: true, horizon: h, summary: dossierSummary(h, today), today }
    }
    const nodeId = String(body?.nodeId || body?.node?.id || '')
    const node = nodeId ? h.nodes.find((n) => n.id === nodeId) : null
    const save = (state, extra = {}) => {
      const next = saveHorizonsSynced(state)
      const hz = findHorizon(next, hId)
      return { ok: true, horizon: hz, summary: dossierSummary(hz, today), ...extra }
    }
    if (action === 'node_upsert') {
      const p = body?.node && typeof body.node === 'object' ? body.node : {}
      if (!node && h.nodes.length >= MAX_NODES) return { ok: false, error: 'too-many', text: `一条目标最多 ${MAX_NODES} 个节点` }
      const id = node ? node.id : `N-${now.toString(36).slice(-5)}${Math.floor(Math.random() * 90 + 10)}`
      const merged = cleanNode({ ...(node || {}), ...p, id, createdAt: node?.createdAt || now,
        // 下面三项只能由局写回，不接受直接改。
        spentMinutes: node?.spentMinutes || 0, executionLog: node?.executionLog || [] })
      if (!merged) return { ok: false, error: 'bad-node', text: '节点要有标题' }
      const state = mapHorizon(cur, hId, (hz) => ({
        ...hz,
        updatedAt: now,
        nodes: node ? hz.nodes.map((n) => (n.id === id ? merged : n)) : [...hz.nodes, merged],
      }))
      record({ state: node ? 'horizon-node-edit' : 'horizon-node-add', at: now, horizonId: hId, nodeId: id, title: merged.title })
      return save(state, { node: merged })
    }
    if (!node) return { ok: false, error: 'no-node', text: '没找到这个节点' }
    if (action === 'node_delete') {
      const state = mapHorizon(cur, hId, (hz) => ({ ...hz, updatedAt: now, nodes: hz.nodes.filter((n) => n.id !== nodeId) }))
      record({ state: 'horizon-node-delete', at: now, horizonId: hId, nodeId })
      return save(state)
    }
    const patchNode = (fn, rec) => {
      const state = mapHorizon(cur, hId, (hz) => ({ ...hz, updatedAt: now, nodes: hz.nodes.map((n) => (n.id === nodeId ? cleanNode(fn(n)) : n)) }))
      if (rec) record({ at: now, horizonId: hId, nodeId, ...rec })
      return save(state)
    }
    if (action === 'node_status') {
      const st = String(body?.status || '')
      if (!['planned', 'active', 'passed', 'slipped'].includes(st)) return { ok: false, error: 'bad-status' }
      return patchNode((n) => ({ ...n, status: st, ...(body?.reviewNote !== undefined ? { reviewNote: body.reviewNote } : {}) }),
        { state: 'horizon-node-status', status: st })
    }
    if (action === 'node_extend') {
      const days = Math.max(1, Math.min(30, Math.round(Number(body?.days) || 3)))
      const base = node.targetDate || today
      const t = Date.parse(base + 'T00:00:00Z') + days * 86_400_000
      const nextDate = new Date(t).toISOString().slice(0, 10)
      return patchNode((n) => ({ ...n, targetDate: nextDate, status: n.status === 'slipped' ? 'active' : n.status,
        reviewNote: String(body?.reviewNote || n.reviewNote || '').trim() }), { state: 'horizon-node-extend', from: base, to: nextDate })
    }
    if (action === 'node_breakpoint') {
      return patchNode((n) => ({ ...n, lastBreakpoint: String(body?.breakpoint || '') }), null)
    }
    if (action === 'node_start') {
      const cfg = getConfig() || {}
      const fallbackApps = Array.isArray(cfg.defaultAllow) && cfg.defaultAllow.length ? cfg.defaultAllow : ['md.obsidian']
      const c = compileBoutFromNode({ horizon: h, node, tierOf, fallbackApps, minutes: body?.minutes, now })
      if (!c.ok) return { ok: false, error: 'compile', text: c.error }
      if (typeof upsertWorkItemNow === 'function') upsertWorkItemNow(c.item, { now, mirror: true })
      const state = node.status === 'planned'
        ? mapHorizon(cur, hId, (hz) => ({ ...hz, nodes: hz.nodes.map((n) => (n.id === nodeId ? { ...n, status: 'active' } : n)) }))
        : cur
      record({ state: 'horizon-node-start', at: now, horizonId: hId, nodeId, workId: c.item.id, dropped: c.droppedApps })
      return save(state, { item: c.item, droppedApps: c.droppedApps })
    }
    if (action === 'node_commit') {
      const r = commitBoutOutcomeToNode(cur, {
        horizonId: hId, nodeId, workId: body?.workId, outcome: body?.outcome, minutes: body?.minutes,
        summary: body?.summary, breakpoint: body?.breakpoint, day: today, now,
      })
      if (!r.ok) return { ok: false, error: r.error }
      if (!r.changed) return { ok: true, changed: false, alert: r.alert }
      record({ state: 'horizon-node-log', at: now, horizonId: hId, nodeId, workId: String(body?.workId || ''), outcome: body?.outcome, minutes: body?.minutes })
      return save(r.state, { changed: true, alert: r.alert })
    }
    return { ok: false, error: 'bad-action' }
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
    if (action.startsWith('node_') || action === 'dossier') {
      const r = nodeRoute(action, body, cur, now)
      return { v: 1, ...r }
    }
    if (action === 'push_to_today') {
      const all = [...cur.medium, ...cur.long]
      const target = all.find((x) => x.id === String(body?.id || ''))
      if (!target) return { v: 1, ok: false, error: '未找到对应主线条目' }
      const defaultApps = Array.isArray(getConfig()?.defaultAllow) && getConfig().defaultAllow.length
        ? getConfig().defaultAllow
        : ['md.obsidian', 'com.tencent.mm']
      const apps = Array.isArray(body?.apps) && body.apps.length ? body.apps : defaultApps
      const newTask = {
        id: `hz-${now.toString(36).slice(-5)}`,
        source: 'horizon',
        title: `${target.title}${body?.subtask ? `：${String(body.subtask).slice(0, 40)}` : ''}`.slice(0, 80),
        protocolId: 'execute',
        minutes: Math.max(15, Math.min(180, Number(body?.minutes) || 45)),
        apps,
        need: apps,
        allow: apps,
        parentHorizonId: target.id,
        links: { horizonId: target.id, contractCpId: '' },
        strict: true,
      }
      if (typeof upsertWorkItemNow === 'function') {
        upsertWorkItemNow(newTask, { now, mirror: true })
      } else {
        const existing = planDayRoute({ action: 'list' })?.plan?.items || []
        planDayRoute({ action: 'set', items: [...existing, newTask], by: 'horizon' })
      }
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
    const catalog = loadBankCatalog(now)
    const st = loadBankUnlock()

    if (action === 'gemini_template') {
      return { v: 1, ok: true, template: GEMINI_IMPORT_PROMPT_TEMPLATE }
    }

    if (action === 'list_banks') {
      const banks = summarizeCatalog(catalog, st)
      const enabledBanks = catalog.banks.filter((b) => b.enabled !== false)
      const bankTotal = countEnabledQuestions(catalog)
      const bankName = enabledBanks.length === 1
        ? enabledBanks[0].name
        : (enabledBanks.length > 1 ? `${enabledBanks[0].name} 等 ${enabledBanks.length} 套` : '未导入')
      return {
        v: 1,
        ok: true,
        banks,
        bankName,
        bankTotal,
        wrongCount: (st.wrongBookIds?.length ? st.wrongBookIds : st.wrongIds).length,
        config: st.config,
        stats: st.stats,
        stats7d: build7dQuizStats(st, now, tz(), catalog),
      }
    }

    if (action === 'get_bank') {
      const bankId = String(body?.bankId || body?.id || '').trim()
      const target = catalog.banks.find((b) => b.id === bankId)
      if (!target) return { v: 1, ok: false, error: '找不到指定题库' }
      const wrongSet = new Set(st.wrongIds)
      const qStats = st.questionStats || {}
      const questions = target.questions.map((q, i) => {
        const qs = qStats[q.id] || qStats[`${target.id}:${q.id}`] || null
        const kind = normalizeQuestionKind(q.kind, q.选项, q.题型, q.答案)
        return {
          id: q.id,
          index: i + 1,
          kind,
          stem: String(q.题干 || ''),
          options: Array.isArray(q.选项) ? q.选项 : [],
          answer: String(q.答案 || ''),
          analysis: Array.isArray(q.解析) ? q.解析.join('\n') : String(q.解析 || ''),
          type: String(q.题型 || ''),
          images: Array.isArray(q.images) ? q.images : [],
          inWrongPool: wrongSet.has(q.id),
          anomalyReasons: detectQuestionAnomalies(q),
          stats: {
            seenCount: Number(qs?.seenCount || 0),
            correctCount: Number(qs?.correctCount || 0),
            wrongCount: Number(qs?.wrongCount || 0),
            lastSeenAt: Number(qs?.lastSeenAt || 0),
          },
        }
      })
      return {
        v: 1,
        ok: true,
        bank: summarizeBank(target, st),
        questions,
      }
    }

    if (action === 'toggle_bank') {
      const bankId = String(body?.bankId || body?.id || '').trim()
      const target = catalog.banks.find((b) => b.id === bankId)
      if (!target) return { v: 1, ok: false, error: '找不到指定题库' }
      target.enabled = body?.enabled === undefined ? !target.enabled : Boolean(body.enabled)
      const saved = saveBankCatalog(catalog)
      if (!target.enabled && target.questions.some((q) => q.id === st.session.currentId)) {
        saveBankUnlock({ ...st, session: { ...st.session, currentId: '' } })
      }
      const bankTotal = countEnabledQuestions(saved)
      return {
        v: 1,
        ok: true,
        bank: summarizeBank(target, st),
        banks: summarizeCatalog(saved, st),
        bankTotal,
      }
    }

    if (action === 'delete_bank') {
      const bankId = String(body?.bankId || body?.id || '').trim()
      const idx = catalog.banks.findIndex((b) => b.id === bankId)
      if (idx < 0) return { v: 1, ok: false, error: '找不到指定题库' }
      const [removed] = catalog.banks.splice(idx, 1)
      const removedIds = new Set(removed.questions.map((q) => q.id))
      const saved = saveBankCatalog(catalog)
      const nextSt = {
        ...st,
        wrongIds: st.wrongIds.filter((id) => !removedIds.has(id)),
        session: {
          ...st.session,
          seenIds: st.session.seenIds.filter((id) => !removedIds.has(id)),
          currentId: removedIds.has(st.session.currentId) ? '' : st.session.currentId,
        },
        lastQuestionId: removedIds.has(st.lastQuestionId) ? '' : st.lastQuestionId,
      }
      saveBankUnlock(nextSt)
      return {
        v: 1,
        ok: true,
        deletedBankId: bankId,
        banks: summarizeCatalog(saved, nextSt),
        bankTotal: countEnabledQuestions(saved),
      }
    }

    if (action === 'delete_question') {
      const bankId = String(body?.bankId || body?.id || '').trim()
      const questionId = String(body?.questionId || '').trim()
      const target = catalog.banks.find((b) => b.id === bankId)
      if (!target) return { v: 1, ok: false, error: '找不到指定题库' }
      const qIdx = target.questions.findIndex((q) => q.id === questionId)
      if (qIdx < 0) return { v: 1, ok: false, error: '找不到指定题目' }
      target.questions.splice(qIdx, 1)
      const saved = saveBankCatalog(catalog)
      const nextSt = {
        ...st,
        wrongIds: st.wrongIds.filter((id) => id !== questionId),
        session: {
          ...st.session,
          seenIds: st.session.seenIds.filter((id) => id !== questionId),
          currentId: st.session.currentId === questionId ? '' : st.session.currentId,
        },
        lastQuestionId: st.lastQuestionId === questionId ? '' : st.lastQuestionId,
      }
      saveBankUnlock(nextSt)
      const updated = saved.banks.find((b) => b.id === bankId)
      return {
        v: 1,
        ok: true,
        bankId,
        questionId,
        bank: updated ? summarizeBank(updated, nextSt) : null,
        banks: summarizeCatalog(saved, nextSt),
        bankTotal: countEnabledQuestions(saved),
      }
    }

    if (action === 'practice_session') {
      return {
        v: 1,
        ...buildPracticeSession(catalog, st, {
          bankId: body?.bankId ?? body?.id ?? '',
          kind: body?.kind ?? '',
          scope: body?.scope ?? 'all',
          limit: body?.limit ?? 20,
          excludeIds: body?.excludeIds ?? [],
          now,
        }),
      }
    }

    if (action === 'submit_practice_answer') {
      const r = recordPracticeAnswer(catalog, st, {
        questionId: body?.questionId,
        answer: body?.answer ?? '',
        rating: body?.rating,
        elapsedMs: body?.elapsedMs ?? 0,
        now,
        tz: tz(),
      })
      if (r.ok && r.state) saveBankUnlock(r.state)
      return { v: 1, ...r, state: undefined }
    }

    if (action === 'toggle_star') {
      const r = toggleQuestionStar(st, body?.questionId, body?.starred)
      if (r.ok && r.state) saveBankUnlock(r.state)
      return { v: 1, ...r, state: undefined }
    }

    if (action === 'stats_7d') {
      return { v: 1, ok: true, ...build7dQuizStats(st, now, tz(), catalog) }
    }

    if (action === 'wrong_book_list') {
      return {
        v: 1,
        ...getWrongBookList(catalog, st, {
          bankId: body?.bankId ?? '',
          kind: body?.kind ?? '',
          limit: body?.limit ?? 200,
          now,
        }),
      }
    }

    if (action === 'status' || action === 'quiz_session') {
      const card = serveReviewCard(st, now, catalog)
      if (card) return { v: 1, ok: true, banks: summarizeCatalog(catalog, st), stats7d: build7dQuizStats(st, now, tz(), catalog), ...card.payload }
      const preview = pickNextQuizQuestion(catalog, st)
      if (preview.state) saveBankUnlock(preview.state)
      return {
        v: 1,
        ok: true,
        config: st.config,
        stats: st.stats,
        stats7d: build7dQuizStats(preview.state || st, now, tz(), catalog),
        wrongCount: (st.wrongBookIds?.length ? st.wrongBookIds : st.wrongIds).length,
        bankName: preview.bankName || '未导入',
        bankTotal: preview.bankTotal,
        banks: summarizeCatalog(catalog, st),
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

    // 2026-11-21：引擎收请求的上限是 16 KB，一份题库动辄几百 KB——手机分块传（import-begin / import-chunk / import-finish），
    // 收齐了拼起来再解析。小的照旧一次 import。
    const uploadDir = () => stateFile('bank-upload')
    const cleanUploads = () => {
      try {
        for (const f of readdirSyncV(uploadDir())) {
          const p = join(uploadDir(), f)
          if (now - statSyncV(p).mtimeMs > 3_600_000) rmSyncV(p, { recursive: true, force: true })
        }
      } catch {}
    }
    const safeId = (v) => String(v || '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40)
    if (action === 'import-begin') {
      cleanUploads()
      const id = safeId(body?.uploadId) || 'u' + now.toString(36)
      mkdirSyncV(join(uploadDir(), id), { recursive: true })
      return { v: 1, ok: true, uploadId: id }
    }
    if (action === 'import-chunk') {
      const id = safeId(body?.uploadId)
      const idx = Math.max(0, Math.min(9999, Number(body?.index) || 0))
      if (!id) return { v: 1, ok: false, error: '缺 uploadId' }
      try {
        mkdirSyncV(join(uploadDir(), id), { recursive: true })
        writeFileSyncV(join(uploadDir(), id, String(idx).padStart(5, '0') + '.part'), String(body?.text ?? ''), 'utf8')
      } catch (e) {
        return { v: 1, ok: false, error: '写临时文件失败：' + String(e?.message || e).slice(0, 80) }
      }
      return { v: 1, ok: true, index: idx }
    }
    const importText = (raw, opts = {}) => {
      const requestedName = String(opts.name || '').trim().slice(0, 60)
      const requestedBankId = String(opts.bankId || opts.id || '').trim()
      const targetForAppend = opts.append && catalog.banks.length > 0
        ? (
            (requestedBankId && catalog.banks.find((b) => b.id === requestedBankId)) ||
            (requestedName && catalog.banks.find((b) => b.name === requestedName)) ||
            (!requestedName ? catalog.banks[catalog.banks.length - 1] : null)
          )
        : null
      const merge = Boolean(targetForAppend)
      const offset = merge ? targetForAppend.questions.length : 0
      const previewOnly = Boolean(opts.previewOnly)
      const uploadId = opts.uploadId ? String(opts.uploadId) : undefined
      const parsed = parseBankAny(raw, { offset })
      if (!parsed.ok) {
        return {
          v: 1,
          ok: false,
          count: 0,
          error: parsed.error,
          sample: parsed.sample || '',
          skipped: parsed.skipped.slice(0, 30),
          skippedCount: parsed.skipped.length,
          format: parsed.format,
          ...(uploadId ? { uploadId } : {}),
        }
      }
      if (previewOnly) {
        const preview = buildImportPreview(parsed)
        return {
          v: 1,
          ok: true,
          previewOnly: true,
          ...(uploadId ? { uploadId } : {}),
          format: parsed.format,
          count: preview.total,
          total: preview.total,
          issueCount: preview.issueCount,
          skippedCount: parsed.skipped?.length || 0,
          skipped: (parsed.skipped || []).slice(0, 30),
          items: preview.items,
        }
      }
      // 追加时按题干去重（Gemini / BrainOS 反复推同一批题不生双份）。
      if (merge) {
        const norm = (t) => String(t || '').replace(/[\s\p{P}\p{S}]/gu, '').toLowerCase().slice(0, 120)
        const seen = new Set(targetForAppend.questions.map((q) => norm(q.题干)))
        const fresh = []
        for (const q of parsed.questions) {
          const k = norm(q.题干)
          if (k && seen.has(k)) parsed.skipped.push({ n: fresh.length + 1, why: '题库里已经有了（题干重复）' })
          else {
            seen.add(k)
            fresh.push(q)
          }
        }
        if (!fresh.length) {
          return {
            v: 1,
            ok: true,
            importedCount: 0,
            bankId: targetForAppend.id,
            bankTotal: countEnabledQuestions(catalog),
            bankName: targetForAppend.name,
            format: parsed.format,
            skipped: parsed.skipped.slice(0, 30),
            skippedCount: parsed.skipped.length,
            warnings: ['全部 ' + parsed.questions.length + ' 题题库里都有了'],
          }
        }
        parsed.questions = fresh
      }

      let activeBank = null
      if (merge) {
        const usedIds = new Set(targetForAppend.questions.map((q) => q.id))
        const scoped = parsed.questions.map((q, idx) => {
          let qid = `${targetForAppend.id}:q-${targetForAppend.questions.length + idx + 1}`
          while (usedIds.has(qid)) qid = `${targetForAppend.id}:q-${usedIds.size + 1}`
          usedIds.add(qid)
          return cleanCatalogQuestion({ ...q, id: qid }, qid)
        }).filter(Boolean)
        targetForAppend.questions.push(...scoped)
        targetForAppend.importedAt = now
        activeBank = targetForAppend
      } else {
        const existingOverwrite = opts.overwrite
          ? catalog.banks.find((b) => (requestedBankId && b.id === requestedBankId) || (requestedName && b.name === requestedName))
          : null
        const baseId = requestedBankId || `bank-${now.toString(36)}-${catalog.banks.length + 1}`
        let bankId = existingOverwrite ? existingOverwrite.id : baseId
        if (!existingOverwrite) {
          let seq = 1
          while (catalog.banks.some((b) => b.id === bankId)) {
            bankId = `${baseId}-${seq++}`
          }
        }
        const name = requestedName || '备考题库'
        const scoped = parsed.questions.map((q, idx) => {
          const rawQid = String(q.id || `q-${idx + 1}`)
          const qid = rawQid.startsWith(`${bankId}:`) ? rawQid : `${bankId}:${rawQid}`
          return cleanCatalogQuestion({ ...q, id: qid }, qid)
        }).filter(Boolean)
        const bankEntry = {
          id: bankId,
          name,
          importedAt: now,
          sourceFormat: parsed.format,
          enabled: true,
          questions: scoped,
        }
        if (opts.replaceAll === true) {
          catalog.banks = [bankEntry]
        } else if (existingOverwrite) {
          const i = catalog.banks.indexOf(existingOverwrite)
          catalog.banks[i] = bankEntry
        } else {
          catalog.banks.push(bankEntry)
        }
        activeBank = bankEntry
      }

      const saved = saveBankCatalog(catalog)
      const totalEnabled = countEnabledQuestions(saved)
      saveBankUnlock({
        ...st,
        session: { solved: 0, reviewDone: 0, currentCard: '', seenIds: [], currentId: '' },
        wrongIds: opts.replaceAll === true ? [] : st.wrongIds,
      })
      record({ state: 'bank-imported', at: now, name: activeBank.name, total: totalEnabled, added: parsed.questions.length, skipped: parsed.skipped.length, format: parsed.format })
      return {
        v: 1,
        ok: true,
        importedCount: parsed.questions.length,
        bankId: activeBank.id,
        bankTotal: totalEnabled,
        bankName: activeBank.name,
        banks: summarizeCatalog(saved, st),
        format: parsed.format,
        skipped: parsed.skipped.slice(0, 30),
        skippedCount: parsed.skipped.length,
        warnings: parsed.skipped.length ? ['有 ' + parsed.skipped.length + ' 道没进：' + parsed.skipped.slice(0, 5).map((x) => '第 ' + x.n + ' 题' + x.why).join('、')] : [],
      }
    }
    if (action === 'import-finish') {
      const id = safeId(body?.uploadId)
      const dir = join(uploadDir(), id)
      const previewOnly = Boolean(body?.previewOnly)
      let raw = ''
      try {
        raw = readdirSyncV(dir).filter((f) => f.endsWith('.part')).sort().map((f) => readFileSyncV(join(dir, f), 'utf8')).join('')
      } catch {
        return { v: 1, ok: false, error: '没找到上传的内容（超过一小时被清掉了？重新导一次）' }
      } finally {
        if (!previewOnly) {
          try {
            rmSyncV(dir, { recursive: true, force: true })
          } catch {}
        }
      }
      return importText(raw, { append: body?.append, name: body?.name, id: body?.id, bankId: body?.bankId, overwrite: body?.overwrite, replaceAll: body?.replaceAll, previewOnly, uploadId: id })
    }
    if (action === 'import' || action === 'preview') {
      const raw = body?.raw ?? body?.text ?? body?.bank
      const previewOnly = action === 'preview' || Boolean(body?.previewOnly)
      return importText(raw, { append: body?.append, name: body?.name, id: body?.id, bankId: body?.bankId, overwrite: body?.overwrite, replaceAll: body?.replaceAll, previewOnly })
    }

    if (action === 'quiz_answer' && String(body?.questionId || '').startsWith('rc:') && reviewCards) {
      return answerReviewCard(st, String(body.questionId).slice(3), String(body?.answer || ''), now)
    }

    if (action === 'quiz_answer') {
      const judged = judgeQuizAnswer(catalog, st, {
        questionId: body?.questionId,
        answer: body?.answer,
        now,
      })
      if (!judged.ok) return { v: 1, ok: false, error: judged.error }
      if (judged.duplicate) return { v: 1, ...judged, state: undefined }
      saveBankUnlock(judged.state)
      let bankRes = null
      if (judged.unlocked) bankRes = unlockByQuiz(judged, now)
      if (judged.unlocked && bankRes?.earnedOnly) {
        const card = serveReviewCard(judged.state, now, catalog)
        let nextQuestion = card ? card.payload.question : null
        if (!nextQuestion) {
          const preview = pickNextQuizQuestion(loadBankCatalog(now), judged.state)
          if (preview.state) saveBankUnlock(preview.state)
          nextQuestion = preview.question
        }
        return {
          v: 1,
          ...judged,
          state: undefined,
          unlocked: false,
          roundComplete: true,
          earnedOnly: true,
          needSpend: true,
          earnedAdded: bankRes.earnedAdded,
          dailyBankEarned: bankRes.dailyBankEarned,
          dailyBankCap: bankRes.dailyBankCap,
          capped: bankRes.capped,
          question: nextQuestion,
        }
      }
      // 下一题：这一轮的复习卡名额还没用完、又有到期卡，先出卡。
      if (!judged.unlocked) {
        const card = serveReviewCard(judged.state, now, catalog)
        if (card) return { v: 1, ...judged, question: card.payload.question, state: undefined }
      }
      return { v: 1, ...judged, state: undefined }
    }

    return { v: 1, ok: false, error: `未知 action: ${action}` }
  }

  /** 锁屏这一轮该先出的到期复习卡（名额 = 要求题数的一半）；出了就记在 session 里，答完才换。 */
  function serveReviewCard(st, now, catalogArg = null) {
    if (!reviewCards) return null
    const cap = reviewCapFor(st.config.requiredCount)
    let card = null
    try {
      if (st.session.currentCard) card = reviewCards.get(st.session.currentCard)
      if (!card && st.session.reviewDone < cap) card = reviewCards.next(now)
    } catch {
      card = null
    }
    if (!card) return null
    if (card.id !== st.session.currentCard) saveBankUnlock({ ...st, session: { ...st.session, currentCard: card.id } })
    const catalog = catalogArg || loadBankCatalog(now)
    const enabledBanks = catalog.banks.filter((b) => b.enabled !== false)
    const bankName = enabledBanks.length === 1
      ? enabledBanks[0].name
      : (enabledBanks.length > 1 ? `${enabledBanks[0].name} 等 ${enabledBanks.length} 套` : '个人复习卡')
    const choice = card.options.length > 0
    return {
      card,
      payload: {
        config: st.config,
        stats: st.stats,
        wrongCount: st.wrongIds.length,
        bankName,
        bankTotal: countEnabledQuestions(catalog),
        solved: st.session.solved,
        required: st.config.requiredCount,
        unlockMinutes: st.config.unlockMinutes,
        question: {
          id: 'rc:' + card.id,
          kind: 'review',
          stem: (card.topic ? '【复习 · ' + card.topic + '】' : '【复习】') + card.q,
          options: choice ? card.options : [],
          // 没有选项的卡：先藏答案，他点「看答案」再显示、自己判「记得 / 忘了」（只算 0.7 题）。
          answer: choice ? undefined : card.a + (card.hint ? '（前提：' + card.hint + '）' : ''),
          type: '复习卡',
          selfWeight: SELF_JUDGED_WEIGHT,
        },
      },
    }
  }

  /** 锁屏答一张复习卡：4 选 1 代码判（验过）；无选项的 REMEMBER / FORGOT 自判（只改排期，算 0.7 题）。 */
  function answerReviewCard(st, cardId, answer, now) {
    const r = answerLockReviewCard(reviewCards, st, { cardId, answer, now, selfWeight: SELF_JUDGED_WEIGHT })
    if (!r.ok) {
      if (r.unlockState) saveBankUnlock(r.unlockState)
      return { v: 1, ok: false, error: r.error }
    }
    if (r.duplicate) {
      return {
        v: 1,
        ok: true,
        duplicate: true,
        ignored: true,
        review: true,
        correct: false,
        unlocked: false,
        solved: r.solved,
        required: r.required,
        unlockMinutes: r.unlockMinutes,
      }
    }
    saveBankUnlock(r.unlockState)
    let bankRes = null
    if (r.unlocked) bankRes = unlockByQuiz(r, now)
    const needNext = !r.unlocked || Boolean(bankRes?.earnedOnly)
    const catalog = loadBankCatalog(now)
    const next = needNext ? serveReviewCard(r.unlockState, now, catalog) : null
    let question = next ? next.payload.question : null
    let bankTotal = countEnabledQuestions(catalog)
    if (needNext && !next) {
      const preview = pickNextQuizQuestion(catalog, r.unlockState)
      if (preview.state) saveBankUnlock(preview.state)
      question = preview.question
      bankTotal = preview.bankTotal
    }
    if (r.unlocked && bankRes?.earnedOnly) {
      return {
        v: 1,
        ok: true,
        review: true,
        correct: r.correct,
        selfJudged: r.selfJudged,
        weight: r.weight,
        unlocked: false,
        roundComplete: true,
        earnedOnly: true,
        needSpend: true,
        earnedAdded: bankRes.earnedAdded,
        dailyBankEarned: bankRes.dailyBankEarned,
        dailyBankCap: bankRes.dailyBankCap,
        capped: bankRes.capped,
        unlockUntil: r.unlockUntil,
        unlockMinutes: r.unlockMinutes,
        correctAnswer: r.correctAnswer,
        explain: r.explain,
        solved: r.solved,
        required: r.required,
        bankTotal,
        wrongCount: st.wrongIds.length,
        question,
      }
    }
    return {
      v: 1, ok: true, review: true, correct: r.correct, selfJudged: r.selfJudged, weight: r.weight,
      unlocked: r.unlocked, unlockUntil: r.unlockUntil, unlockMinutes: r.unlockMinutes,
      correctAnswer: r.correctAnswer,
      explain: r.explain,
      solved: r.solved, required: r.required, bankTotal, wrongCount: st.wrongIds.length, question,
    }
  }

  function unlockByQuiz(judged, now) {
    let bankRes = null
    try {
      bankRes = onBankUnlock?.({ minutes: judged.unlockMinutes, solved: judged.solved, until: judged.unlockUntil, now }) || null
    } catch {
      // ignore
    }
    record({
      state: 'quiz-unlock',
      at: now,
      solved: judged.solved,
      unlockMinutes: judged.unlockMinutes,
      earnedAdded: bankRes?.earnedAdded,
      until: judged.unlockUntil,
    })
    recordStoryline({
      kind: 'quiz_unlock',
      actor: 'user',
      summary: bankRes?.earnedOnly
        ? `锁屏答对 ${judged.solved} 题挣得 ${bankRes.earnedAdded}m 余额`
        : `锁屏答对 ${judged.solved} 题解锁 ${judged.unlockMinutes}m 通行`,
      at: now,
    }, now)
    return bankRes
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
    if (DAY_PLAN_ACTIONS.has(action)) {
      const dayPlanCur = loadDayPlan(now)
      const res = applyDayPlanAction(dayPlanCur, { ...body, action }, {
        now,
        day: dayString(now, tz()),
        funBudgetMin: funBudgetMinNow(now),
      })
      if (res.ok && action !== 'get_day_plan') {
        saveDayPlan(res, now)
        try { onDayPlanMutated?.(now) } catch {}
      }
      const verifySummary = summarizeDayBlocksVerify(res.dayBlocks, { minuteOfDay: tzMinuteOfDay(now, tz()) })
      return {
        v: 1,
        ...res,
        verifySummary,
        dayPlan: {
          v: 1,
          day: res.day,
          energyLevel: res.energyLevel,
          carryoverItems: res.carryoverItems,
          dayBlocks: res.dayBlocks,
          stats: res.stats,
          verifySummary,
          updatedAt: res.updatedAt,
        },
      }
    }
    if (action === 'submit_delivery') {
      return submitDelivery(body, now)
    }
    if (action === 'judge_delivery') {
      return judgeDelivery(body, now)
    }
    if (action === 'issue_directive') {
      return issueDirective(body, now)
    }
    if (action === 'status') {
      const cur = loadContract()
      const ticked = evaluateContractTick(cur, now)
      if (ticked.changed && ticked.contract) saveContract(ticked.contract)
      const dayTick = dayBlockTickNow(now)
      const dayPlanCur = dayTick.state
      const dayPlanStats = computeDayPlanStats(dayPlanCur.dayBlocks, {
        funBudgetMin: funBudgetMinNow(now),
        energyLevel: dayPlanCur.energyLevel,
      })
      return {
        v: 1,
        ok: true,
        contract: ticked.contract,
        graceWarning: ticked.graceWarning || dayTick.graceWarning,
        blockGraceWarning: dayTick.graceWarning,
        triggerStrictNow: Boolean(ticked.triggerStrictNow || (dayTick.overdueVerifyBlocks && dayTick.overdueVerifyBlocks.length)),
        graceSecDefault: Math.round(OVERDUE_GRACE_MS / 1000),
        presets: COMMON_PLAN_PRESETS,
        scripts: AI_PROMPT_SCRIPTS,
        verifySummary: dayTick.verifySummary,
        dayPlan: {
          ...dayPlanCur,
          stats: dayPlanStats,
          verifySummary: dayTick.verifySummary,
        },
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
      if (typeof upsertWorkItemNow === 'function') {
        for (const cp of next.checkpoints || []) {
          upsertWorkItemNow({
            id: cp.id || next.id,
            source: 'contract',
            title: cp.title || next.title,
            due: cp.dueAt || undefined,
            minutes: mins,
            apps: next.allowedApps,
            packet: { goal: cp.title || next.title, deliverable: cp.expectedDeliverable },
            status: 'pending',
            links: { horizonId: next.horizonId || '', contractCpId: cp.id || '' },
          }, { now, mirror: false })
        }
      }
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
            onContractClear?.(now, 'contract-passed')
          } catch {
            // ignore
          }
        }
        try {
          markWorkStatusNow?.({ id: res.checkpoint.id, title: res.checkpoint.title, status: 'passed', now })
        } catch {}
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
        onContractClear?.(now, 'contract-cleared')
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
    const customEm = typeof getEmergencyPassActive === 'function' ? getEmergencyPassActive(now) : null
    const emergencyPass = customEm || (isEmergencyPassActive(ext, now) ? ext.emergencyPass : null)
    const geminiSession = overrides.geminiSession !== undefined ? overrides.geminiSession : getGeminiSession?.(now)
    const aiWorkSession = overrides.aiWorkSession !== undefined ? overrides.aiWorkSession : getAiWorkSession?.(now)
    // 计划验收块/主管硬指令逾期 + 封印到点没过保底 + 契约逾期 共走 P3 核心「验收锁」通道。
    const contractSession = overrides.contractSession !== undefined
      ? overrides.contractSession
      : (dayBlockOverdueSessionView(now) || contractSessionView(now) || getSealSession(now))
    const taskLease = overrides.taskLease !== undefined ? overrides.taskLease : getTaskLease?.(now)
    // 手动专注严格局 / 到点自动接管的时间块 / 默认工作时段防分心 共走 P4。
    const presetStrict = overrides.presetStrict !== undefined
      ? overrides.presetStrict
      : (presetRt?.sessionView?.(now) || dayBlockActiveSessionView(now) || getVerifySession(now))
    const wall = overrides.wall !== undefined ? overrides.wall : getWallDecision?.(now)
    const autonomy = overrides.autonomy || computeAutonomy(now)
    const lockRequests = overrides.lockRequests !== undefined ? overrides.lockRequests : (getLockRequests?.(now) || [])
    return computeUnifiedLockArbiter({
      emergencyPass,
      geminiSession,
      aiWorkSession,
      takeoverSession: overrides.takeoverSession,
      contractSession,
      taskLease,
      presetStrict,
      wall,
      autonomy,
      lockRequests,
      now,
    })
  }

  function getUnifiedTasks(now = Date.now()) {
    const ws = typeof getWorkStore === 'function' ? getWorkStore(now) : null
    if (ws && Array.isArray(ws.unifiedTasks)) {
      return ws.unifiedTasks
    }
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
    const timeLedger = getTimeLedger?.(now, targetDay) || null
    const intensityInfo = getIntensityInfo?.(now) || null
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
      timeLedger,
      hardInterventions: intensityInfo?.hardInterventions || null,
      kbDirPath: kbPathNow(),
      storyline: loadStorylineStore(now),
      appLedger: (() => {
        try {
          return targetDay === dayString(now, tzName) ? getAppLedger(now) : []
        } catch {
          return []
        }
      })(),
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
    try {
      lines.push(...getSealLines(now))
    } catch {}
    const y = loadReportsStore()?.reports?.[shiftYmdLocal(today, -1)]
    if (y?.verdict?.line) lines.push('昨天的判决（代码按账本算的，不许替他找补）：' + y.verdict.line.replace(/^今天：/, ''))
    const tlLine = getTimeLedger?.(now)?.line
    const q = tlLine || getQuotaLine()
    if (q) lines.push(q + '。用到 90 / 110 分钟时提一句，不要每轮都说。')
    if (Array.isArray(y?.appLedger) && y.appLedger.length) {
      lines.push('昨天的 App 账：' + y.appLedger.slice(0, 6).join('；'))
      lines.push('早上那一轮（鲸澜）：双用途 App 偏航 ≥2 次、或某个 App 用时明显不对的，用 app_tier 改严并一句话告诉他；想放松要先问他。')
    }
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
      summary: `生成 ${targetDay} 晚间总报告（${report.verdict?.tag || ''} · 投入 ${report.score}/100）`,
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
      const overview = buildAnalyticsOverview({
        ...inputs,
        todayVerdict: report.verdict,
        todayScore: report.score,
        rangeDays,
      })
      return { v: 1, ok: true, report, overview }
    }

    if (targetDay === dayString(now, tzName)) syncTodayHistory(now)
    const inputs = buildReportInputs(targetDay, now)
    const pre = buildAnalyticsOverview({ ...inputs, rangeDays })
    const evalOut = computeDailyEvaluation({
      targetDay,
      buckets: pre.buckets,
      ledger: inputs.ledger,
      contract: inputs.contract,
      horizons: inputs.horizons,
      planned: inputs.planned,
      tz: tzName,
      now,
    })
    return {
      ...pre,
      verdict: pre.eveningReport?.verdict || evalOut.verdict,
      score: pre.eveningReport ? pre.eveningReport.score : evalOut.score,
      scoreLabel: '投入分',
    }
  }

  function storylineRoute(body = {}) {
    const now = Date.now()
    const tzName = tz()
    const action = String(body?.action || 'get')
    let store = loadStorylineStore(now)
    if (action === 'snooze_gemini') {
      const mins = Math.max(5, Math.min(180, Number(body?.minutes) || 30))
      const snoozeRes = typeof snoozeGeminiAutoNow === 'function'
        ? snoozeGeminiAutoNow(mins, now)
        : { ok: true, snoozeUntil: now + mins * 60_000 }
      record({ state: 'gemini-snooze', at: now, minutes: mins })
      return {
        v: 1,
        ok: true,
        minutes: mins,
        snoozeUntil: snoozeRes?.snoozeUntil || (now + mins * 60_000),
        pullMode: store.pullMode,
        storyline: store,
        promptBlock: formatIntradayPromptBlock(store, now, tzName),
      }
    }
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
    const dayBlockTick = dayBlockTickNow(now)
    return {
      contract: ticked.contract,
      graceWarning: ticked.graceWarning || dayBlockTick.graceWarning,
      dayBlockGraceWarning: dayBlockTick.graceWarning,
      forceStrictByContract: Boolean(ticked.triggerStrictNow || (dayBlockTick.overdueVerifyBlocks && dayBlockTick.overdueVerifyBlocks.length)),
      forceStrictByDayBlock: Boolean(dayBlockTick.overdueVerifyBlocks && dayBlockTick.overdueVerifyBlocks.length),
      verifySummary: dayBlockTick.verifySummary,
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
    const geminiAccounting = typeof getGeminiAccounting === 'function' ? getGeminiAccounting(now, tzName) : null
    const lockArbiter = computeLockArbiter(now, { autonomy: contractTick.autonomy })
    const activeSisterSkin = resolveActiveSisterSkin({ speaker: getSpeaker?.() || 'companion', lockArbiter })
    const unifiedTasks = getUnifiedTasks(now)
    const intensityInfo = getIntensityInfo?.(now, contractTick.autonomy) || {
      intensity: 'standard',
      effectiveIntensity: 'standard',
      autonomyShift: 0,
      pendingIntensity: null,
      emergencyDisarmDailyCap: 3,
      hardInterventions: { count: 0, cap: 12, capped: false, left: 12 },
    }
    const passLedger = getPassLedgerSummary?.(now) || null
    const timeLedger = getTimeLedger?.(now, todayStr) || null
    const ledgerRecent = readLedger(home(), 500)
    const evalToday = computeDailyEvaluation({
      targetDay: todayStr,
      buckets: charts.buckets,
      ledger: ledgerRecent,
      contract: contractTick.contract,
      horizons,
      planned: unifiedTasks.length,
      tz: tzName,
      now,
    })

    return {
      v: 1,
      ok: true,
      day: todayStr,
      arbiterEnforce: getArbiterEnforce?.() !== false,
      ...(geminiAccounting ? { geminiAccounting } : {}),
      intensity: intensityInfo.intensity,
      effectiveIntensity: intensityInfo.effectiveIntensity || intensityInfo.intensity,
      autonomyShift: Number(intensityInfo.autonomyShift) || 0,
      pendingIntensity: intensityInfo.pendingIntensity || null,
      emergencyDisarmDailyCap: intensityInfo.emergencyDisarmDailyCap,
      hardInterventions: intensityInfo.hardInterventions,
      verdict: evalToday.verdict,
      score: evalToday.score,
      scoreLabel: '投入分',
      ...(passLedger ? { passLedger } : {}),
      ...(timeLedger ? { timeLedger } : {}),
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
      tasks: tasks.length ? tasks : unifiedTasks.slice(0, 3),
      unifiedTasks,
      allQueueCount: unifiedTasks.length,
      defaultAllow: Array.isArray(getConfig()?.defaultAllow) && getConfig().defaultAllow.length ? getConfig().defaultAllow : ['md.obsidian', 'com.tencent.mm'],
      recentApps: Array.isArray(plan.recent) ? plan.recent : [],
      lastDice: Number(getLastRolledDice?.()) || 0,
      heartbeat: (!bouts.running && unifiedTasks.length === 0) ? 'HEARTBEAT_OK' : 'ACTIVE',
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
          text: `✓ 已生成 ${r.day} 晚间可视化总报告（投入 ${r.score}/100）：${r.headline}${r.path ? `\n已同步写入：${r.path}` : ''}`,
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
        try {
          requestGeminiGate?.({ source: 'ai-tool', mode: 'manual', topic, geminiMode: mode, minutes, brief, now })
        } catch {
          // ignore gate log error
        }
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
    loadDayPlan,
    dayBlockTickNow,
    dayBlockOverdueSessionView,
    dayBlockActiveSessionView,
    submitDelivery,
    judgeDelivery,
    issueDirective,
    getDayVerifySummary,
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
