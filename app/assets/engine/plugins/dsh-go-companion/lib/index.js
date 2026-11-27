// dsh-go-companion · 宿主半边
//
// 手机端主动事件的唯一决策与回合服务：
//   POST /request   一次主动交流（原生闹钟已经判定“到点”）
//   GET  /result    长轮询 accepted / claimed / generated / failed
//   POST /cancel
//   POST /plan      本地决策：开没开、静不静、额度、掷骰、话题（零模型）
//   POST /outcome   送达/展示/回应回执（写账本）
//   GET  /status
//   POST /config
//
// 复用：fish-memory.sqlite（只读话题/事实/经历）、relation 存在性、原生展示层。
// 不新建记忆库；不读屏幕内容；所有 SQL 参数绑定。

import { appendFileSync, mkdirSync, readFileSync, renameSync, writeFileSync, existsSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { isAbsolute, join } from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import { assistantText, DEFAULT_LIMITS, Desk, SOURCE_KIND } from './core.js'
import { BACKGROUND_LIMIT, backgroundFacts, loadCandidates, loadLorebookFacts, openMemoryDb } from './topics.js'
import { buildPrompt } from './prompt.js'
import { appendLedger, readLedger, summarize } from './ledger.js'
import { plan as decide, inQuiet } from './plan.js'
import { DEFAULT_TZ, hm as zoneHm, md as zoneMd, minuteOfDay as zoneMinuteOfDay, startOfDay as zoneStartOfDay, weekdayOf, zoneOr } from './tz.js'
import { classifyReply } from './classify.js'
import { gardenClass, judgeReply } from './judge.js'
import { eyeCheck, newContent } from './wall_eye.js'
import { RECENT_WINDOW, SKIP_STATE, companionGate, contentGate, gateRound, isStock, nounsOf, recentOwnTexts } from './stock.js'
import { BURST_HOURLY_CAP, behaviorOf, emotionActingCue, emotionLine, emotionOf, voiceOf } from './emotion.js'
import { capCount, nextShot, rallyStats } from './rally.js'
import { pickServeType } from './serve.js'
import { LIGHTER_TYPES, mutedUntil, serveWeights, silenceState } from './silence.js'
import { COACH_LIMITS, cleanPersona, effectiveMode, mergePersona, modeLimits, modeOr, modeServeOrder, modeSwitch, personaLines } from './persona.js'
import {
  MAX_ATTEMPTS,
  addTopic,
  applyNoReply,
  applyReply,
  closeTopic,
  discoverFromMessage,
  gardenStats,
  loadGarden,
  markGenerated,
  markUsed,
  nextTopic,
  nextTopicAt,
  publicTopic,
  saveGarden,
  seedFromCommitments,
  seedFromMemory,
} from './garden.js'
import { lastReplyClass, exportImportantMemories, readCommitments, readCorePersona, readMood, readMoodSummary } from './context.js'
import { cleanPhone, funToday, worthObserving } from './phone.js'
// 外接 APP：他自己声明的应用名单（config.apps）压过内置的娱乐类包名表。
import { blockDuringLock, cleanApps, declaredFun, emergencyPackages, remark, roleOf } from './apps.js'
import { NOTES_CANDIDATES, agendaLines, capabilityLines, companionChatText, roleNames, strategistChatText } from './roles.js'
import { fitBudget, stripInjection } from './budget.js'
// B3：素材池（例句池 / 关键词卡 / depth 预算系数）。文件在用户笔记库里，缺了用内置种子。
import { RECENT_SKIP, countWords, depthBudget, keywordCard, pickSamples, readManualKeywords, readSamples, recentNoteTexts } from './samples.js'
import { tide as tideOf, tideGap, tideHourly, vagueStreak } from './tide.js'
import { FACES, stepTemper, temperLine } from './temper.js'
// C2 的四个操作判定（judgeOp/nextOp/opQuota/opStats）和九种思维题同一个模块：
// 判定纯函数在 drills.js，这里只负责收答案、记账、把进度挂进面板。
import { OP_META, OP_ORDER, demoOf, drillLines, drillOf, failsToday, judgeOp, nextOp, opQuota, opStats, pickDrill, scoldLines } from './drills.js'
import { normalizeTracks, pickTrack, recentTracks, trackStatus } from './tracks.js'
// C1 挂载卡：纯函数在 mount.js，这里只负责落盘、路由和注入。
import {
  MEMORY_LEAK_TRACK, acceptProposal, closeCard, driftOf, mountLine, mountStatus, nextStreak, normalizeCard,
  openCard, parkCard, propose as proposeMount, proposeCap, resumeCard, scaffold, shouldShowCard, withGiven, withStep,
} from './mount.js'
// 第六轮 C：暗思考（后台自己整理，不开口）。
import { museSweep, museStatus } from './muse.js'
// 第七轮 B2：暗思考的第二层——夜间整理（允许调模型，产物是素材不是消息，走 llm.stream）。
import {
  carryTexts, cleanNightly, emptyNightly, gapTexts, linkWords, mergeNightly,
  nightlyDue, nightlyInputs, nightlyLines, nightlyStatus, resolveNightly, tidyNightly,
} from './nightly.js'
// 第六轮 D：姐妹自建工作流的护栏（配额 + 试启用 + 到期自动停 + 能力层拦墙）。
import { workflowAct, workflowPropose, workflowStatus, workflowSweep } from './workflows.js'
import { REQUIRED_FIELDS, WORKFLOW_ALLOW } from './workflows.js'
import { brainLines, chooseFace, cleanBrain, drillScorer, observe, onReply, onServe, onSilent, revise, updateArm } from './brain.js'
import { appendPrediction, checkClaim, cleanLedger, dueCount, emptyLedger, predictionLines, settle } from './predict.js'
import { agreementRate, appendStance, checkStance, cleanStances, hitRate, isYield, reviseStance, settleStances, stanceBlock, stanceDigest, yieldLine } from './stance.js'
import { buildVibe, pickedArms, renderVibe, stochasticLivingLines } from './vibe.js'
import { decideForce, forceLines, refuseSwitch } from './force.js'
import { BUSY_DEFAULT_MIN, BUSY_MAX_MIN, aiTask, applyGear, effectiveGear, gearLines, gearSwitch } from './gear.js'
import { trustGap, trustLines, trustScore } from './trust.js'
import { buildSnapshot, sistersView } from './panel.js'
import { boutsBooksView, boutsNowView, boutsProtocolsView, boutsQueueView } from './bouts_view.js'
import { boutsTileStatus, buildBoutsSnapshot } from './bouts_panel.js'
// 第八轮 P1/P2：计划的节拍。草案、到点开局、过点记空局的规则全在 planwire.js，这里只接线。
import { RESTART_BELOW, appPrompt, missingApps, pkgsOf, planLines, planOf, tick } from './planwire.js'
import { planSentence, PLAN_CONFIRM_MS } from './dayplan.js'
import { badPackets, judgeOver, normPacket, openSay, packetProblems, packetPrompt } from './packet.js'
import * as earnedRt from './earned.js'
// R9 第五项：鱼丸报的线索进鲸澜的待查队列（一天最多 5 条）。
import * as clueRt from './clue.js'
// 第八轮 W2：墙的面板。数据层自己不含措辞，只读 machine/wall_voice/settings 的现成结论。
import { buildWallSnapshot } from './wall_panel.js'
import { wallAuditView, wallView } from './wall_view.js'
import { settingsOf, applySettings, dayKindOf, inWorkWindow, windowsOf } from './settings.js'
// 第八轮 W4：设定页。解析、渲染、预填都在 settings_panel.js，这里只接线。
import { buildSettingsSnapshot, parseDay, parseLimits, settingsMarkdown } from './settings_panel.js'
import { settingsDayView, settingsLimitsView } from './settings_view.js'
import { builtinProtocolOf, builtinProtocols } from './builtin.js'
// 第八轮 P4 的第二条：思维局开局时摊成普通步骤，执行器一行不改。
import { parseBank } from './bank.js'
import { expandThinking } from './thinking.js'
import { composeBoutProtocol, loadProtocol } from './protocol.js'
import { createOfficial } from './boutrun.js'
// 第八轮 P5：排一段专注之前的必答项。规则在 autobout.js，这里只接线。
import { answerPrompt, missingAnswers } from './autobout.js'
import { getDayPlan, listBeats, listBouts, listEscalations, listVerdicts, openBoutsDb, reapRunning, runningBout, saveDayPlan, startEscalation, upsertProtocol } from './bouts.js'
// 第八轮 P4 接线：一条回答 → 分类 → 记敷衍 → 判升级 → 落手机。全部在 ladder.js 里，
// 这里只负责把状态读写、数据库、手机快照喂给它。
import { ALL_LOCK, CELL, funStep, landing, rowOf, step as ladderStep } from './ladder.js'
import { evaluateEPrime } from './escalate.js'
// 第九轮 A 组：预案。锁只来自生效中的预案；规则在 presets.js，接线在 presetwire.js。
import { createPresetRuntime } from './presetwire.js'
// 行为大脑第一版（K1 记录 / K2 发现 / K3 模式卡）与法典视图。
import { createBrainRuntime } from './brainwire.js'
import { buildCodex } from './codex.js'
import { classify as classifyAnswer } from './reply.js'
import { JUDGE_FILE, SELF_FILE, appendDiceThought, appendVerdictDaily, buildHandoffPacket, collectVaultMemoryFacts, ensureStarterVaultFiles, kbDir, kbWrite, knowledgeBlocks, knowledgeLines, longPlanDigest, scanUnfinished, syncVerdicts, vaultRecentNotes, vaultSearch, vaultPick } from './notes.js'
import { rollDiceCard } from './six_dice.js'
import { MORNING_CAP_MS, NIGHT_LOCK_TO, REVIEW_CAP_MS, ROLL_MS, absentAnchors, closeGate, localMinute, rhythmLines, trackRhythm } from './rhythm.js'
import {
  FOLLOWUP_CAP_MS, FOLLOWUP_PENALTY, STANDBY_LOCK_MS, addObligation, checkVerdict, clearObligation, closeLock, followupView,
  funBlockedBy, lockActive, lockExpired, obligationOf, obligationsView, openLock, standbyDue, takeReask,
} from './followup.js'
import { BRAIN_MIN_MS, SPRINT_LONG_MIN, SPRINT_MIN, resolveState, toolsElsewhere, toolsFor } from './machine.js'
import { CORE_TOKEN_LIMIT, buildTelemetrySnapshot, estimateTokens } from './memory_blocks.js'
import { checkWithdrawalRelease, cleanWithdrawalState, filterWithdrawalOutput, isInWithdrawal, recordReplyOutcome, withdrawalReply } from './withdrawal.js'
import {
  STRATEGIST_AUDIT_DIRECTIVE, antiSycophancyRewriteHint, detectSycophancy, detectTelemetryContradiction,
  stripStrategistAudit, telemetryContradictionHint,
} from './strategist_audit.js'
import {
  archiveJudgeFile, collectDepartmentSop, dispatchDepartmentSop, governDepartmentSop, inferDepartment, isValidDepartment,
  routeBoutDepartment, strategizeDepartmentSop,
} from './departments.js'
import { TIER_AMBIGUOUS, TIER_CONTEXTUAL, TIER_EXEMPT, classifyAppTier, detectDegradedEscape, evaluateContextualExemption } from './app_governance.js'
import {
  activeAppLease, checkEmergencyPassPullBack, cleanExternalState, evaluateDailySchedulingFloor,
  evaluateElasticBuffer, evaluateExternalRecapFallback, grantEmergencyPass, isEmergencyPassActive,
  issueAppLease, revokeAppLease,
} from './external_agents.js'
import { createV4Hub } from './v4_hub.js'
import { EMERGENCY_PACKAGES, PROXY_PACKAGES, RESIDENT_PACKAGES, SELF_PACKAGES, SYSTEM_PACKAGES, isNeverLockPkg } from './never_lock.js'
import { contextLines, loadGeminiSetting, parseReceipt } from './trigger.js'
import { AGREE_RE, BASE_EXTRA, DEFAULT_NEUTRAL_WORK_QUOTA_MIN, NEUTRAL_COMM_PACKAGES, TASK_APP_SCOPES, TIER_FILE, TIER_LABEL, TIER_RANK, WORK_DEFAULT, classifyTierChanges, parseTierFile, renderTierFile, resolveScopeApps, tierOf } from './app_tiers.js'
import { computeQuota } from './quota.js'
import { buildLockDecision } from './lock_decision.js'
import { CHAT_PACKAGES, driveMode, ignite, nextDrive, returnAction, stepTakeover, TAKEOVER_SPEAK_GAP_MS } from './driver.js'
import {
  SEAL_CUTOFF_MINUTE, SEAL_KINDS, COLD_CUTOFF_MINUTE, addSeal, addSeals, captureBaselines, claimSeal, cleanSeals, lockingSeal,
  markOpened, nextYmd, raiseSeal, sealPromptLines, settleDeferred, tickSeals, timelineDue, waiveSeal,
} from './seal.js'
import {
  COVENANT_FILE, COVENANT_PENDING_FILE, COVENANT_PROPOSALS_FILE, COVENANT_COOLDOWN_MS, DRAFT_RULES, addProposal, cleanProposal,
  cleanRule, covenantBlock, insertRuleInText, nextIdIn, parseCovenant, renderCovenant, renderPendingCovenant, renderProposals,
  replaceRuleInText, syncCovenant,
} from './covenant.js'
import { weekPack } from './week_pack.js'
import { healthItems, healthSummary, healthText } from './health.js'
import { addCards, cleanPool, dueCards, rateCard, deleteCard, delayedRecall, deferredProgress, reviewSummary, reviewWeekLine, receiptToCards, mirrorLine, MAX_CARDS } from './review_cards.js'
import { previewIntervals } from './fsrs.js'
import { addReview, cleanReview, decideReview, parseWeekReview, reviewMarkdown, reviewPromptLines } from './week_review.js'
import {
  DAYTIME_SPEND_CAP_MIN, DEFAULT_BASE_FUN_MIN, DEFAULT_DAY_WORK_WALL_WINDOWS, DEFAULT_TEMPLATES, SPEND_MIN, applyNodCardOverrides,
  buildNodCard, checkSpend, checkStart, classifySpendWindow, dayOutcomes, defaultVerify, parseTemplates, recoveryStep, renderTemplates,
  shouldApplyTemplate, templateSeals, verifyPromptLines, eveningOf, eveningStep, eveningCapFor, setEveningCap, EVENING_END,
} from './verifier_mode.js'
import { appendInboxEntry, buildStrategizeBundle, compactInbox, readInboxEntries } from './inbox_dept.js'
import { createLocksOverviewRuntime } from './locks_overview_runtime.js'
import { createBridgeRuntime } from './bridge_runtime.js'
import { createReviewRuntime } from './review_runtime.js'
import { createWallBossRuntime } from './wall_boss_runtime.js'
import { buildHandoff, handoffLive, recentDialog, rotationStatus } from './rotate.js'
import { cardLive, cleanCard, takeoverCard, toggleStep } from './card.js'
import { noteExcerpts, reflectDue, reflectLines, reflectMarkdown, reflectSlot, reflectSystem, reflectUser, runReflect } from './reflect.js'
import { resolveActiveSisterSkin } from './unified_arbiter.js'
import {
  DEFAULT_INTENSITY, applyIntensityChange, hardInterventionStatus, isLockExemptFromDailyCap,
  isValidIntensity, normalizeHardInterventions, normalizeIntensity, recordHardIntervention,
  resolveEffectiveIntensity, resolveIntensityState, thresholdsForIntensity,
} from './intensity.js'
import {
  isEmergencyActive as isPassEmergencyActive, normalizePassLedger, passLedgerSummary,
  requestPass, revokeEmergencyPass,
} from './pass_ledger.js'
import { arbitrateSpeak } from './speak_arbiter.js'
import {
  buildStandardGeminiBrief,
  evaluateProactiveGeminiTriggers,
  markGeminiSessionEnded,
  normalizeGeminiGate,
  requestGemini,
  snoozeGeminiAuto,
} from './gemini_gate.js'
import { appendTimeEntry, CARRY_CAP_MIN, computeTimeLedger, normalizeTimeLedger } from './time_ledger.js'
import { createLockBus } from './lock_bus.js'
import {
  markWorkStatus, migrateLegacyToWorkStore, mirrorWorkStoreToLegacy, normalizeWorkStore,
  removeWorkItem, replaceWorkSource, upsertWorkItem,
} from './work_store.js'
import { buildDiagnosticExport } from './diagnostic_export.js'
import { countPerfunctoryStreak, distillPartnerMemory, pickDualTrackNextStep } from './partner_brain.js'

/**
 * R7 P0-3：主状态是按钟点算的（深夜锁 00:30–07:00、吃饭时段都是钟点条件），而工具子集又跟着
 * 主状态走。同一份代码在夜里跑和白天跑挂出来的工具不是同一套，单测没法确定自己跑在哪个状态。
 *
 * 这里是唯一的测试口子：把主状态钉成某个 id。生产环境永远不会调它（`null` = 真实计算），
 * 它不改变任何判定逻辑，只是让「挂载时刻是哪个状态」变成用例能声明的东西。
 */
let statePin = null
/** apply() 起来后由它填：钉一次状态就顺手按新子集重挂一遍（走的正是生产那条 syncTools）。 */
let remountAll = null
/** 单测用：钉住主状态并立刻重挂工具；传 null / 空值恢复真实计算。 */
export function setStatePin(id) {
  statePin = id || null
  if (typeof remountAll === 'function') remountAll()
}

function compactJsonlFile(file, maxBytes = 512 * 1024, keepLines = 300) {
  try {
    if (!existsSync(file) || statSync(file).size <= maxBytes) return false
    const lines = readFileSync(file, 'utf8').split('\n').filter(Boolean)
    if (lines.length <= keepLines) return false
    const kept = lines.slice(-keepLines).join('\n') + '\n'
    const tmp = file + '.' + String(process.pid) + '.tmp'
    writeFileSync(tmp, kept, { encoding: 'utf8', mode: 0o600 })
    renameSync(tmp, file)
    return true
  } catch {
    return false
  }
}
import {
  DEFAULT_WALL, wallConfig, wallDecision, mealNow, activeRelax, tightenActive, relaxRemaining, relaxUsedThisWeek, relaxBonus,
  trackApplyWindow, applyWindowOpen, dayKey, weekKey, WALL_KIND_DEFAULT, wallDefianceText,
} from './wall.js'
import { dailyPath, dayString, keywordsOf, loadDaily, normalizeTasks, reconcile, reviewText, writeDaily } from './daily.js'
// A2：白天的时间表（纯函数在 daily.js 里；这里只接线和落账）。
import { DEPTS, TRIGGER_TEXT, blockLine, fixedBlocksOf, lateLine, markBlockDone, planSweep, reschedule as reschedulePlan, schedule, scheduleText } from './daily.js'
import { DEPARTMENTS, DEPT_DUTY, appendSlot, behaviorReport, completeOf, depositLines, depositOf, missingText, normalizeEntry, reconcileEntry, slotAsk, statusOf, withEntry } from './deposit.js'
import { AUDIT_CODES, APPEAL_CODES, APPEAL_MAX_PER_DAY, appealLeft, appealVoice, verdictVoice, wallThread } from './wall_voice.js'
import {
  SCREENSHOT_DIRS, auditSixDiceSlot, auditStrokeSlot, auditTask, baselineFor, buildBossAuditBrief, buildSixDicePlan, buildStrokePlan, checkMorningTasks, evidenceType,
  listScreenshots, loadWallMemory, noteDone, notePromised, pruneMemory, recentRate, statOf, wallText,
} from './wall_auditor.js'
import {
  appendWallBossMessage,
  buildWallBossLedgerText,
  isWallBossSession,
  normalizeWallBossHistory,
  validateWallSessionId,
  WALL_BOSS_AUDIT_TRIGGER_TEXT,
  WALL_BOSS_PERSONA_TEXT,
} from './wall_boss_session.js'
import { executeCapability } from './capabilities.js'
import { matchLorebook } from '../../dsh-go-memory/lib/lorebook.js'
import {
  AUDIT_DIR, PENDING_FILE, RULES_COOLDOWN_DAYS, RULES_FILE, WALL_DIR, appendAudit, classifyRules, parseRules, readText as readVaultText, rulesNeedSwitchKeys,
  findObsidianVault, renderPending, renderRules, renderTaskNote, taskNotePath, updatePending, writeText as writeVaultText,
} from './vault_wall.js'
import { GEMINI_PACKAGES, computeGeminiGentleAccounting, driftPull, geminiBrief, geminiLines, geminiMode, geminiSteps, inGemini, recapMarkdown, trackGemini } from './gemini.js'
import { auditOf, cardsOf, groupPrompts } from './sisters.js'
import { ClockKernel, parseCron } from './clock_kernel.js'
import { classifyApp, isDualUseApp, isPureGame, resolveAppNames } from './app_resolver.js'
import { evalBehaviorRadar } from './behavior_radar.js'
import { cleanBankUnlockState } from './bank_unlock.js'
import { SUPER_SPARK_DEFAULTS, generateSuperSpark } from './super_spark.js'
import { LIVE_CORE_DEFAULTS, buildLiveCoreAtlas, buildLiveCoreBrief, buildSisterFeed, evaluateLiveCore, formatLiveCoreHistory } from './live_core.js'
import {
  applyAppeal as v6ApplyAppeal,
  buildInterrogationPrompt as v6BuildInterrogationPrompt,
  buildNineGrid as v6BuildNineGrid,
  computePoolTelemetry as v6ComputePoolTelemetry,
  deleteStele as v6DeleteStele,
  evaluateWindowMode as v6EvaluateWindowMode,
  ingestClipboardOrText as v6IngestClipboardOrText,
  loadV6State,
  normalizeFramework as v6NormalizeFramework,
  recordMicroQuizAnswer as v6RecordMicroQuizAnswer,
  sampleKnowledgeItems as v6SampleKnowledgeItems,
  startCadence as v6StartCadence,
  stopCadence as v6StopCadence,
  submitCadenceDelivery as v6SubmitCadenceDelivery,
  sweepForgetting as v6SweepForgetting,
  tickCadence as v6TickCadence,
  upsertStele as v6UpsertStele,
} from './v6_engine.js'

export const name = 'dsh-go-companion'
// Without this, `ctx.tools` below throws "cannot get property tools without inject"
// and the whole entry fails to activate (routes, sweeper and companion_topic never load).
export const inject = ['tools']
import { PREFIX, MAX_BODY, DEFAULTS, home, configPath, readConfig, syncMoodBridge, writeConfig, readTimings, memoryPath, gardenPath, stateFile, readState, writeJson, requestZone, startOfDay, send, readJson, sessionIds, isArchived, liveAgent, resolveAgent, drillToggle, userMessage, judgeTargetOf, nightlyTargetOf, messageText, mergeConfig } from './runtime_io.js'
export { drillToggle }

export function apply(ctx) {
  let config = readConfig()
  // #18: 默认启用单一裁决器执法；仅当磁盘 config.json 显式写过布尔值 false 时保持影子模式
  const rawCfgInit = readState(configPath(), null)
  if (typeof rawCfgInit?.arbiterEnforce !== 'boolean') {
    config = { ...config, arbiterEnforce: true }
  }
  compactJsonlFile(stateFile('ctx-budget.jsonl'), 512 * 1024, 300)
  compactJsonlFile(stateFile('seals.jsonl'), 512 * 1024, 300)
  let garden = loadGarden(gardenPath())
  // 判球要模型。llm 不在就一直是 undefined，判球自动退回本地规则——不能因为缺服务就不加载插件。
  let llmStream
  if (typeof ctx.inject === 'function') {
    try {
      ctx.inject(['llm'], (c) => {
        llmStream = c?.llm?.stream ? c.llm.stream.bind(c.llm) : undefined
      })
    } catch {
      // 没有 llm 服务也没关系
    }
  }
  const streamOf = () => llmStream
  console.error('[companion] loaded; session=' + String(config.sessionId || 'none') + ' memory=' + memoryPath())

  function persistGarden() {
    try {
      saveGarden(gardenPath(), garden)
    } catch (error) {
      console.error('[companion] 保存话题花园失败：' + String((error && error.message) || error))
    }
  }

  /** sessionId -> {requestId, topicId, at}: a generated message the person has not answered yet. */
  const pendingReply = new Map()
  const REPLY_WINDOW_MS = 2 * 3_600_000

  function setPending(sessionId, value) {
    if (!sessionId) return
    for (const id of sessionIds(sessionId)) pendingReply.set(id, value)
  }

  function takePending(sessionId) {
    if (!sessionId) return undefined
    for (const id of sessionIds(sessionId)) {
      const v = pendingReply.get(id)
      if (v) {
        for (const x of sessionIds(sessionId)) pendingReply.delete(x)
        return v
      }
    }
    return undefined
  }

  /** 他声明的应用名单（config.apps）。空数组＝全按内置的娱乐类包名表判。 */
  const appsOf = () => cleanApps(config.apps)
  /** 手机现状洗一遍，再按他声明的名单重标娱乐类。三处调用都走这个口，别直接 cleanPhone。 */
  const phoneOf = (raw) => {
    const p = remark(cleanPhone(raw), appsOf())
    if (p && raw && Number.isFinite(Number(raw.geminiMinutesToday))) {
      p.geminiMinutesToday = Math.max(0, Math.round(Number(raw.geminiMinutesToday)))
    }
    return p
  }

  function record(entry) {
    appendLedger(home(), { v: 1, ...entry })
  }

  // ---------------------------------------------------------------- 大脑
  // 大脑和脾气各存一个小文件；读坏了就从空的开始，绝不让一轮卡住。
  /** sessionId -> 他最后一次亲口说话的时间：正在聊就不用主动发球。 */
  // 每个会话上一球发出去的原文：判球用它对上这一拍，改口检测用它比「她前后两句」。
  const lastServeOf = new Map()
  const lastHumanAt = new Map()
  /** 他说完这么久之内算"正在聊"。 */
  const IN_CONVERSATION_MS = 3 * 60_000
  /**
   * 第九轮 6.1：有预案在生效时，插件不许让手机睡过这么久。
   * 插件没有推送通道（手机是拉的一方），预案改了只能等手机下一次来问 /plan；
   * 明天那句 nextCheckAt 原本可能睡到天亮，等于预案改了六个小时不生效。
   * shortcut: 每 5 分钟一次空问，用一点电换生效延迟；嫌费电就调大，代价是预案改动等更久。
   */
  const IDLE_POLL_MS = 5 * 60_000

  /** 他正在跟大肥鱼说话（刚说过，或者大肥鱼正在回他）。 */
  function inConversation(sessionId, now) {
    for (const id of sessionIds(sessionId)) {
      if (now - (lastHumanAt.get(id) || 0) < IN_CONVERSATION_MS) return true
    }
    // 浮层里那句话还在等回复，也算正在聊。
    chat.sweep()
    if (chat.inFlight()) return true
    return liveAgent(ctx, sessionId)?.status === 'running'
  }

  /** 讲通了理由之后松多久。 */
  const GRACE_MS = 20 * 60_000
  const loadBrain = () => cleanBrain(readState(stateFile('brain.json'), null))
  const saveBrain = (b) => writeJson(stateFile('brain.json'), b)
  const loadWithdrawal = () => cleanWithdrawalState(readState(stateFile('withdrawal.json'), null))
  const saveWithdrawal = (w) => writeJson(stateFile('withdrawal.json'), w)
  const releaseWithdrawalOnAction = (opts = {}, now = Date.now()) => {
    const wr = checkWithdrawalRelease(loadWithdrawal(), { ...opts, now })
    if (wr.released) {
      saveWithdrawal(wr.state)
      record({ state: 'withdrawal-release', at: now, reason: wr.reason, ...(opts.app ? { app: opts.app } : {}) })
    } else if ((opts.isTargetApp || opts.wallPassed || opts.legalBuffer) && wr.state.streak === 0) {
      saveWithdrawal(wr.state)
    }
    return wr
  }
  const loadAppGov = () => {
    const raw = readState(stateFile('app-gov.json'), null)
    return raw && typeof raw === 'object' ? { ...raw } : { contextExempt: {}, escape: {}, recentWorkAt: 0, recentScreenshotAt: 0, recentShareAt: 0 }
  }
  const saveAppGov = (g) => writeJson(stateFile('app-gov.json'), g)
  const loadExternal = () => cleanExternalState(readState(stateFile('external.json'), null))
  const saveExternal = (e) => writeJson(stateFile('external.json'), e)
  const loadPcHeartbeat = () => readState(stateFile('pc-heartbeat.json'), null)
  const savePcHeartbeat = (p) => writeJson(stateFile('pc-heartbeat.json'), p)
  const loadDiceFailures = () => readState(stateFile('dice-failures.json'), {}) || {}
  const saveDiceFailures = (d) => writeJson(stateFile('dice-failures.json'), d)
  const freshPcLine = (now = Date.now()) => {
    const pc = loadPcHeartbeat()
    if (!pc || now - Number(pc.at || pc.receivedAt || 0) >= 15 * 60_000) return ''
    if (pc.isAfk) return 'PC:离开(' + String(pc.afkStatus || 'afk').slice(0, 16) + ')'
    const app = String(pc.activeApp || pc.category || '在线').slice(0, 20)
    const focus = Math.max(0, Math.round(Number(pc.focusMinutes) || 0))
    const fun = Math.max(0, Math.round(Number(pc.entertainmentMinutes) || 0))
    return `PC:${app} 专注${focus}m${fun > 0 ? ' 娱乐' + fun + 'm' : ''}`
  }
  const matchActiveLorebook = (role, mainStateId, now = Date.now()) => {
    const db = openMemoryDb(memoryPath())
    if (!db) return ''
    try {
      const sid = role === 'strategist' ? config.strategistSessionId : config.sessionId
      const facts = loadLorebookFacts(db, { sessionId: sid })
      if (!facts.length) return ''
      const sceneTags = [
        'state:' + String(mainStateId || 'standby'),
        live?.department ? 'dept:' + String(live.department) : '',
        lastPhone?.app ? 'app:' + String(lastPhone.app) : '',
        lastPhone?.fun ? 'scene:fun' : lastPhone?.work ? 'scene:work' : '',
      ].filter(Boolean)
      const recentTexts = readLedger(home(), 30)
        .filter((e) => e && (e.state === 'heard' || e.state === 'say-text' || e.state === 'verdict') && typeof e.text === 'string')
        .slice(-4)
        .map((e) => e.text)
      const query = String(live?.protocol?.name || live?.protocol?.id || '')
      const res = matchLorebook({ facts }, {
        scope: sid ? 'session:' + sid : 'global',
        query,
        historyText: recentTexts.join('\n'),
        sceneTags,
        limit: 3,
      }, now)
      return (res.items || []).map((f) => '- ' + String(f.text || '').slice(0, 140)).join('\n')
    } catch {
      return ''
    } finally {
      try {
        db.close()
      } catch {
        // ignore
      }
    }
  }
  // 预判账本：模型只写 claim，verdict 由墙的遥测填（predict.js 里 settle 是唯一出口）。
  const loadPredict = () => cleanLedger(readState(stateFile('predict.json'), null))
  const savePredict = (p) => writeJson(stateFile('predict.json'), p)
  // 立场账本（A1）：她说过的话。status/settleSource 只由 stance.js 的 settleStances 写。
  const loadStances = () => cleanStances(readState(stateFile('stances.json'), null))
  const saveStances = (s) => writeJson(stateFile('stances.json'), s)
  // B2 夜间整理：产物是素材（links/gaps/carry），不是消息，也不进账本正文。
  const loadNightly = () => cleanNightly(readState(stateFile('nightly.json'), null))
  const saveNightly = (s) => writeJson(stateFile('nightly.json'), s)
  // 最近 20 轮她有没有退让。单独一个文件，不塞 ledger.jsonl：账本有按行位置断言的既有用例，
  // 每轮加一行的退让记录会把它们挤动。
  const loadYieldTurns = () => readState(stateFile('stance-yield.json'), null) || []
  const saveYieldTurns = (t) => writeJson(stateFile('stance-yield.json'), (Array.isArray(t) ? t : []).slice(-20))
  // 上轮注入了哪几个 arm：这一球被拉回来时按它们各自记账（§13.1）。进程重启就丢，可接受。
  let lastVibePicks = []
  let lastVibe = { face: '', turn: 0 }
  /** B2：一次只跑一个夜间整理。/plan 会被频繁问到，不挡一下就会并发发起好几次模型调用。 */
  let nightlyRunning = false
  let reflectRunning = false
  /** requestId -> 发球时的现状（generated 时交给大脑开始计"有没有拉回来"）。 */
  const servedContext = new Map()
  /** B1：sessionId -> 这一轮已经被守卫打回过的判定（只留最近一次，重写只有一个机会）。 */
  const gateTries = new Map()
  /** B1：requestId -> 这一轮不发。手机拿到 409 skip-no-content 就静默收尾，不重试。 */
  const suppressed = new Set()
  /** requestId -> first seen moment, for the median latencies in /status (P6). */
  const startedAt = new Map()

  function hourOf(phone, now) {
    if (phone?.local) return Number(phone.local.slice(0, 2))
    return Math.floor(zoneMinuteOfDay(now, zoneOr(config.timeZone)) / 60)
  }

  /** 这一刻他陷得多深：手机现状 + 账本（连着没接、连着糊弄）。顺带让大脑看一眼上一球有没有把他拉回来。 */
  function sense(phone, entries, sessionId, now) {
    const brain = loadBrain()
    const pulled = observe(brain, { phone, now })
    if (pulled) {
      // §13.1 multiple-play bandit：只给上一轮真被抽中的 arm 各记一次，没抽中的不动。
      // 按组合记账会摊薄到永远攒不够，所以这里绝不能写成记一个组合 key。
      for (const arm of lastVibePicks) updateArm(brain, arm, pulled.pulled)
      revise(brain, now)
      record({ state: 'pulled', at: now, pulled: pulled.pulled, face: pulled.face, band: pulled.band, arms: lastVibePicks })
    }
    const silence = silenceState(entries, { sessionId, now })
    const vague = vagueStreak(entries)
    const water = tideOf({ phone, noTake: silence.noTake, vague, hour: hourOf(phone, now) })
    return { brain, silence, vague, water }
  }

  /** 上一道思维题怎么样了：没回 / 答糊弄了，这一球就要说他。 */
  function lastDrillOutcome(entries, now) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      if (e?.state !== 'generated') continue
      if (!e.drill) return null
      const reply = entries.find((x) => x?.requestId === e.requestId && x.state === 'replied')
      if (reply) return { drill: e.drill, requestId: e.requestId, verdict: reply.verdict, answer: reply.answer }
      if (now - Number(e.at) >= 5 * 60_000) return { drill: e.drill, requestId: e.requestId, silent: true }
      return null
    }
    return null
  }

  /**
   * 最近几拍，给判球当上下文（任务书：发球原文 + 回球 + 最近 3 拍）。
   * 只取最近一个回合里的：跨回合的内容对判断没帮助，还会带偏。
   */
  function recentShots() {
    const entries = readLedger(home(), 40)
    let rallyId = ''
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      if (entries[i]?.rallyId) {
        rallyId = String(entries[i].rallyId)
        break
      }
    }
    if (!rallyId) return []
    const out = []
    for (const e of entries) {
      if (e?.rallyId !== rallyId) continue
      if (e.state === 'generated' && e.text) out.push({ from: 'me', text: String(e.text) })
      else if (e.state === 'replied' && e.verdict) out.push({ from: 'user', text: String(e.answer || e.verdict) })
    }
    return out.slice(-3)
  }

  /** 最近一小时发出去几条：阶梯能压过 minGap，靠这个兜住频率。回合里接着打的不算（见 capCount）。 */
  function sentLastHour(entries, now) {
    return capCount(entries, Number(now) - 3_600_000)
  }

  /** 上一次被漠视的那一球：阶梯换法子时拿它当素材（"我叫了你两次，你在刷什么"）。 */
  function lastIgnored(entries) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      if (e?.state !== 'generated' || !e.requestId) continue
      let answered = false
      for (const x of entries) if (x?.requestId === e.requestId && x.state === 'replied') answered = true
      if (!answered) return { at: Number(e.at) || 0, serveType: String(e.serveType || ''), text: String(e.text || '') }
      return null
    }
    return null
  }

  /** 上一球是什么类型（观察球不连着打）。 */
  function lastServeType(entries) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      if (e?.state === 'generated') return String(e.serveType || '')
    }
    return ''
  }

  const lastStockHints = new Map()
  const casualBriefTurns = new Map()

  // 上一球如果被判成套话（3.3），把原句取出来，下一球照着打回。
  function lastStockBall(entries) {
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const e = entries[i]
      // B1：被信息增量守卫打回、没发出去的那一句，也算"上一球等于没说"，下一球要换个写法重写。
      if (e?.state === 'stock-rewrite' && e.text) return String(e.text)
      if (e?.state !== 'generated' || !e.text) continue
      return e.stock === true ? String(e.text) : null
    }
    return null
  }

  /**
   * B4：一小时内已经发出去了多少条（连发按条数算，不按"几轮"算）。
   * 账本里那一条记着当时的 burst 计划，所以重发一轮 burst=3 就占掉 3 条额度。
   */
  function burstUsedLastHour(entries, now) {
    let n = 0
    for (const e of entries) {
      if (e?.state !== 'generated' || !(Number(e.at) > now - 3_600_000)) continue
      n += Math.max(1, Number(e.burst?.burst) || 1)
    }
    return n
  }

  /**
   * B1：信息增量守卫。生成出来的句子过两道：和自己最近 5 句的重合率、有没有具体名词。
   * 一次重写机会（规则见 stock.js 的 gateRound）：重写还重复就这一轮不发（账本 `skip-no-content`），
   * 只是不沾具体名词则重写一次后照发。两者都不算失败、不重试。
   * @returns {{action:'send'|'rewrite'|'skip', state?:string, code?:string}}
   */
  function gateBall(r) {
    const sid = String(r.sessionId || '')
    const served = servedContext.get(String(r.requestId))
    const tries = [...(gateTries.get(sid) || [])]
    const own = readLedger(home(), 60).filter((e) => e.sessionId === sid)
    const speaker = speakerOf(sid)
    const cleanText = speaker === 'strategist' ? stripStrategistAudit(r.text).cleanText : String(r.text || '')
    // R9 第五项：妹妹的两条线，先于套话守卫判——她话说得再新，「该去做事了」也不能发。
    // 一次重写机会；第二遍还犯就这一轮不发，账本各记一条。姐姐不走这两条（她本来就该谈进度）。
    if (speaker === 'companion') {
      const cg = companionGate(cleanText, tries)
      if (cg.action !== 'send') {
        tries.push({ pass: false, code: cg.code })
        if (cg.action === 'rewrite') gateTries.set(sid, tries.slice(-1))
        else gateTries.delete(sid)
        return { action: cg.action, state: cg.state, code: cg.code }
      }
      const syc = detectSycophancy(cleanText, { role: 'companion' })
      if (syc.sycophantic) {
        const prevSyc = tries.filter((t) => t?.code === 'sycophancy').length
        if (prevSyc === 0) {
          tries.push({ pass: false, code: 'sycophancy' })
          gateTries.set(sid, tries.slice(-1))
          return { action: 'rewrite', state: 'stock-rewrite', code: 'sycophancy', hint: antiSycophancyRewriteHint(syc) }
        }
        gateTries.delete(sid)
        return { action: 'skip', state: 'skip-sycophancy', code: 'sycophancy' }
      }
    } else if (speaker === 'strategist') {
      const syc = detectSycophancy(cleanText, { role: 'strategist' })
      if (syc.sycophantic) {
        const prevSyc = tries.filter((t) => t?.code === 'sycophancy').length
        if (prevSyc === 0) {
          tries.push({ pass: false, code: 'sycophancy' })
          gateTries.set(sid, tries.slice(-1))
          return { action: 'rewrite', state: 'stock-rewrite', code: 'sycophancy', hint: antiSycophancyRewriteHint(syc) }
        }
        gateTries.delete(sid)
        return { action: 'skip', state: 'skip-sycophancy', code: 'sycophancy' }
      }
    }
    const verdict = contentGate(cleanText, { recent: recentOwnTexts(own, RECENT_WINDOW), nouns: served?.nouns })
    tries.push(verdict)
    const round = gateRound(tries)
    // 只有"还要重写"的那一遍留在 gateTries 里；放行或收摊都清掉，下一轮重新算。
    if (round.action === 'rewrite') gateTries.set(sid, tries.slice(-1))
    else gateTries.delete(sid)
    return {
      action: round.action,
      // 放行时不改账本状态（就是 generated）；打回和不发各有自己的状态名。
      state: round.action === 'send' ? r.state : round.action === 'skip' ? SKIP_STATE : 'stock-rewrite',
      code: round.code || verdict.code,
      soft: round.soft,
    }
  }

  async function compose(req, sessionId) {
    const db = openMemoryDb(memoryPath())
    try {
      const now = Date.now()
      if (speakerOf(sessionId) === 'strategist') {
        // 姐姐主动找他：一次说清一件事（妹妹递的急话 / 晚上对账）。
        const why = strategistBallReason.get(String(req?.requestId || '')) || { reason: pendingHandoff(now) ? 'handoff' : 'review' }
        const note = why.reason === 'handoff' ? why.note || pendingHandoff(now) : null
        const candidates = loadCandidates(db, { sessionId, now })
        const vault = vaultContext(now)
        const stratFacts = backgroundFacts(candidates, BACKGROUND_LIMIT)
        const stratV6Samples = (() => {
          try {
            const v6s = loadV6State(readState, stateFile('v6-state.json'), now)
            return v6SampleKnowledgeItems(v6s.pool, v6s.framework, { count: 2, now, markShown: false }).map((x) => ({ stem: x.claim }))
          } catch { return [] }
        })()
        const stratDigest = distillPartnerMemory({
          facts: stratFacts,
          horizons: readState(stateFile('horizons.json'), null),
          wrongQuestions: stratV6Samples,
          vaultRecent: vault?.recent,
        }).digestLines
        const prompt = buildPrompt({
          role: 'strategist',
          reason: why.reason,
          note: why.reason === 'handoff' ? note?.text : why.note,
          busy: why.digest === true ? { ...(why.busy || {}), digest: why.digestText || (why.digestText = takeDigest()) } : why.busy,
          contact: contactLines(readLedger(home(), 400), now),
          gemini: why.gemini,
          phone: phoneOf(req?.phone),
          intraday: v4Hub?.getStorylinePromptBlock?.(now) || '',
          commitments: readCommitments(home(), now, 3),
          facts: stratFacts,
          partnerDigest: stratDigest,
          vault,
          names: roleNames(config),
          owner: config.owner,
          allowTools: config.proactiveTools !== false,
        })
        if (note) markDelivered(note)
        markBall(why, now)
        const entries = readLedger(home(), 400)
        const rally = nextShot(entries, { sessionId, now })
        return {
          text: prompt, prompt, topic: null, serveType: 'strategist', rallyId: rally.rallyId, shot: rally.shot,
          silence: { stage: 'none' }, extra: { speaker: 'strategist', reason: why.reason },
        }
      }
      // /plan 定的特殊开场（忙完迎接 / 连麦中途跑了 / 单人格的报到和连麦复盘）。
      const special = strategistBallReason.get(String(req?.requestId || '')) || null
      if (special) markBall(special, now)
      const candidates = loadCandidates(db, { sessionId, now })
      const entries = readLedger(home(), 400)
      const recent = summarize(entries, startOfDay(now, config.timeZone))
      const commitments = readCommitments(home(), now, 3)

      // 记忆是话题的种子来源，花园自己管生命周期；每次合成前同步一次，只增不删。
      seedFromMemory(garden, candidates, now)
      seedFromCommitments(garden, commitments, now)

      const topic = nextTopic(garden, {
        now,
        lastTopicId: recent.lastTopicId,
        lastAt: recent.lastAt,
        cooldownMs: config.cooldownMs,
        maxAttempts: MAX_ATTEMPTS,
      })
      // 没有话题照样开口：用手边的情况、人设起话头。话题只是素材，不是开口的前提。
      if (topic) markUsed(garden, topic.id, now)
      persistGarden()

      const mood = readMoodSummary(home())
      // 没接球时不砸同一类：走阶梯就只挑更轻的球，平时按权重挑（被漠视的降、接住的升）。
      const silence = silenceState(entries, { sessionId, now })
      const lighter = silence.stage === 'lighter' || silence.stage === 'channel'
      // 模式决定从哪几种球里挑：伙伴偏闲聊/记忆，教练偏观察/挑衅。前三种里挑。
      const mode = effectiveMode(config, now)
      // 手边的情况由手机在 /request 里带来；没带就是不知道，观察球就不打（没东西可观察只能编）。
      const phone = phoneOf(req?.phone)
      const order = modeServeOrder(mode).filter((t) => t !== 'observe' || phone)
      const picked = pickServeType(entries, Math.floor(Math.random() * 100), {
        only: lighter ? LIGHTER_TYPES : order.slice(0, 3),
        weights: serveWeights(entries),
      })
      // 正开着娱乐 App 一阵子了：这一球就打观察（"你在 B站 20 分钟了"），上一球已经是观察就不连着打。
      let serveType = !lighter && worthObserving(phone) && lastServeType(entries) !== 'observe' ? 'observe' : picked

      // 大脑：水位 → 脾气 → 脸色（Thompson 挑招）→ 力度 → 要不要出思维题。
      const { brain, water } = sense(phone, entries, sessionId, now)
      const temper = stepTemper(readState(stateFile('temper.json'), null), {
        mode, tide: water.level, noTake: silence.noTake, mood: mood ? mood.mood : undefined, now,
      })
      // 整份存：face/label/rule/surprise 原先被丢掉，导致 temperLine 永远拼不出内容（这条脸色从没进过 prompt）。
      writeJson(stateFile('temper.json'), temper)
      const faceId = chooseFace(brain, { band: water.band, face: temper.face, surprise: temper.surprise })
      const face = { ...temper, ...(FACES.find((f) => f.face === faceId) || {}), face: faceId }
      const force = decideForce({
        phone, tide: water, mode, noTake: silence.noTake, control: config.control !== false, funToday: funToday(phone),
        grace: Number(brain.graceUntil) > now,
        refuse: refuseUntilAt(now) > now,
      })
      // 上一题没回 / 答糊弄了：这一球必须是说他 + 同一类题重问。
      const drillsOn = config.drills === true
      const owed = drillsOn ? lastDrillOutcome(entries, now) : null
      const scold = owed ? scoldLines(drillOf(owed.drill), owed) : []
      if (owed?.silent) onSilent(brain, { requestId: owed.requestId })
      // 要锁的那一球和欠着的那一题都必须出思维题：操作题没有合格线，放不了行（E2E 挂过在这里）。
      const mustDrill = force.level >= 3 || scold.length > 0
      // 在娱乐 App 里越陷越可能出题；要锁的那一球一定出题（答过线才放行）。
      // 阶梯在"换更轻的球"时不随机出题（欠着的题除外）；温和模式又不知道他在干嘛时，也不凭空出题。
      const drillChance = !drillsOn ? 0
        : mustDrill ? 1
        : lighter ? 0
        : phone?.fun ? 0.45 + water.level / 200
        : mode === 'coach' ? 0.3 : 0
      // C2：链路说该练哪个操作了。挂载卡开着才有素材，所以它跟着 drillsOn 走。
      const opDue = drillsOn ? (opQuota(entries, startOfDay(now, config.timeZone)).left > 0
        ? nextOp(entries, startOfDay(now, config.timeZone))
        : null) : null
      const rolled = Math.random() < drillChance
      // 出题这一球真的命中了才轮得到操作题：欠着的思维题要先补（同一题重问），
      // 没欠着就让「该练的那个操作」顶掉随机思维题——两者共用一个槽位，不叠着出。
      const drill = rolled && (!opDue || mustDrill)
        ? (scold.length ? drillOf(owed.drill) : pickDrill(entries, drillScorer(brain)))
        : null
      const drillOp = rolled && !drill ? opDue : null
      if (drill || drillOp) serveType = 'drill'
      // 支线（第六轮 A）：配置里有几条就按"最近三条避开"挑一条。一条都没有 → null，主干不受影响。
      const track = pickTrack(normalizeTracks(configuredTracks()).tracks, recentTracks(entries))
      const firstToday = recent.counts.generated === 0
      saveBrain(brain)
      // 回合与拍数都从账本重建：进程重启不会丢，也不多存一份状态。
      const rally = nextShot(entries, { sessionId, now })
      const vault = vaultContext(now)
      // B1：这一球手上有哪些"具体名词"（当前状态 / 记忆 / 暗思考产物 / 立场 / 今天的块）。
      // 生成后拿它判这一句有没有信息增量——没有就是套话，判据在 stock.js。
      const facts = backgroundFacts(candidates, BACKGROUND_LIMIT)
      const episodes = (candidates.episodes || []).map((e) => e.text)
      const nouns = nounsOf([topic, facts, episodes, commitments, vault, phone, drill, drillOp, track])
      // B4：这一轮怎么发（连发几条、每条多长、间隔多久、发文字还是念出来）。
      // 情绪是账本的纯函数，不另存状态；它只决定"怎么发"，不改任何锁、额度、判定。
      const tzNow = zoneOr(config.timeZone)
      const quietNow = inQuiet(now, config.quietStart, config.quietEnd, tzNow)
      const emotion = emotionOf(entries, { now, tz: tzNow, quietStart: config.quietStart, quietEnd: config.quietEnd })
      const burst = behaviorOf(emotion, {
        now,
        quiet: quietNow,
        sentLastHour: burstUsedLastHour(entries, now),
      })
      // B5：这一条念不念（总开关 / 安静时段 / 渠道三道闸都在 voiceOf 里，返回 null 就是不念）。
      // 渠道那一道：档位要念（hot/blaze/eager），或者漠视阶梯走到了"换渠道"那一级——
      // 后者从第四轮就在账本里记 stage='channel'，一直没人接。
      const voice = voiceOf(emotion, {
        // 2026-11-18：推手 / 接管档默认念出来（driveVoice 可关）；接管时直接走「换渠道」那一级。
        now, enabled: config.voice === true || (lastDriveMode !== 'partner' && config.driveVoice !== false), quiet: quietNow,
        channel: burst.channel, stage: silence.stage, ...(lastTakeover ? { stage: 'channel' } : {}),
      }) || undefined
      const livingServe = stochasticLivingLines({
        role: 'companion',
        seed: now + Math.floor(Math.random() * 10000),
        hour: Math.floor(zoneMinuteOfDay(now, tzNow) / 60),
      })
      const stockRewriteHint = (() => {
        const h = lastStockHints.get(String(sessionId || ''))
        if (!h || now - Number(h.at || 0) > 15 * 60_000) return ''
        lastStockHints.delete(String(sessionId || ''))
        return String(h.hint || '')
      })()
      const stockHitRaw = lastStockBall(entries)
      const stockHit = stockRewriteHint
        ? (stockHitRaw ? stockHitRaw + '｜改写要求：' + stockRewriteHint : stockRewriteHint)
        : stockHitRaw
      const feedBankState = cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null))
      const feedAllBank = loadAllBankQuestions()
      const feedReviewCards = dueCards(loadReviewCards(), now, { limit: 6 })
      const compHorizons = readState(stateFile('horizons.json'), null)
      const compV6Samples = (() => {
        try {
          const v6s = loadV6State(readState, stateFile('v6-state.json'), now)
          return v6SampleKnowledgeItems(v6s.pool, v6s.framework, { count: 2, now, markShown: false }).map((x) => ({ stem: x.claim }))
        } catch { return [] }
      })()
      const partnerDigest = distillPartnerMemory({
        facts,
        horizons: compHorizons,
        wrongQuestions: [...compV6Samples, ...feedAllBank.slice(0, 2)],
        reviewCards: feedReviewCards,
        vaultRecent: vault?.recent,
      }).digestLines
      const liveCoreCfg = loadLiveCoreState()
      const basePrompt = buildPrompt({
        topic,
        facts,
        partnerDigest,
        perfunctoryStreak: countPerfunctoryStreak(entries, classifyReply),
        energyTier: liveCoreCfg?.energyTier || 'L2',
        episodes,
        commitments,
        liveFeed: (() => {
          try {
            return collectSisterFeed(now, {
              ledgerEntries: entries,
              questionBank: feedAllBank,
              bankState: feedBankState,
              reviewCards: feedReviewCards,
              commitments,
              vault,
              phone,
              experiences: [...facts, ...episodes],
            }).sisterFeed
          } catch {
            return null
          }
        })(),
        mood: mood ? mood.mood : undefined,
        replyClass: lastReplyClass(entries),
        serveType,
        stockHit,
        ignored: lighter ? lastIgnored(entries) : undefined,
        // 人格卡：关系库人物卡打底，配置里的覆盖（两张卡合一）。
        persona: mergePersona(readCorePersona(home()), config.persona),
        mode,
        phone,
        vault,
        role: config.dual === false ? undefined : 'companion',
        names: roleNames(config),
        allowTools: config.proactiveTools !== false,
        temper: face,
        drill,
        // C2：这一球练哪个操作（没有思维题时才轮到它，见上面 drillOp 的算法）。
        drillOp,
        // B2：暗思考整理出来的缺口——C 组出题往这儿出，比随机出题有由头。
        gaps: gapTexts(loadNightly(), now),
        track,
        scold,
        beliefs: brainLines(brain),
        force: forceLines(force, phone, { drill: Boolean(drill), gemini: config.proactiveTools !== false }),
        special,
        contact: contactLines(entries, now),
        intraday: v4Hub?.getStorylinePromptBlock?.(now) || '',
        grace: Number(brain.graceUntil) > now,
        morning: firstToday,
        owner: config.owner,
        self: config.dual === false ? config.self : roleNames(config).companion,
      })
      const cue = emotionActingCue(entries, mood, now, 'companion')
      const prompt = [
        basePrompt,
        cue ? '【此刻情绪底色】' + cue : '',
        livingServe.lifeSlice || '',
        livingServe.innerOs || '',
        livingServe.spontaneousTool || '',
      ].filter(Boolean).join('\n')
      servedContext.set(String(req?.requestId || ''), { phone, band: water.band, face: faceId, drill: drill?.id, nouns })
      const extra = {
        tide: water.level, band: water.band, face: faceId, surprise: temper.surprise || undefined,
        drill: drill?.id, force: force.level || undefined, track: track?.id,
        // C2：这一球练的是哪个操作。答案不再交给模型判，回球时按这个码走代码判。
        drillOp: drillOp || undefined,
        // B4：手机按这个计划发——连发几条、每条多长、间隔多久、发文字还是念出来。
        // 它不改这一球的内容，只改投递方式（内容还是模型按人设写的）。
        burst,
        // B5：念出来的调子（语速/音高/单条上限）。没有就是不念。
        // 它和 burst 分开：连发额度只剩 1 条时 burst 是 null，但这一条照样该念。
        voice,
        // 情绪那一行也带上：浮层/面板要看得到她现在什么状态。
        emotion: emotionLine(emotion, now),
      }
      return { text: prompt, prompt, topic, serveType, rallyId: rally.rallyId, shot: rally.shot, silence, extra }
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }
  }

  /** 最近一次 /say 带来的手机时区（没带就用配置里的）。 */
  let sayZone = ''

  // ---------------------------------------------------------------- 直接聊（/say）
  // 浮层里打的字原样作为他的话进大肥鱼的会话（不带插件来源，就是他本人说的），大肥鱼照常回，
  // 工具也不拦——跟在 App 里聊一模一样。回复从 /result 取。判球、安静、换模式这些照常认这句话。
  const chat = new Desk({
    resolveAgent: (id) => resolveAgent(ctx, id, config),
    compose: async (req) => ({ text: req.payload.text, prompt: req.payload.text }),
    // 来源必须带：引擎的会话控制器默认每条消息都有 source，没有就抛异常、这一轮根本不开（真机测出来的）。
    // 他本人打的字，就照 App 里打字的格式标成 user；rpcId 加前缀，不会和 App 的提交撞上。
    // 带上手机时区：引擎只从 user 来源的 clientTimeZone 读当地时间；不带就按引擎的 GMT，
    // 大肥鱼会在中午说"四点了快去睡"（人格测试测出来的）。
    makeMessage: (text) => ({
      id: randomUUID(), role: 'user', content: [{ type: 'text', text }],
      source: { kind: 'user', rpcId: 'dsh-go-companion-say-' + randomUUID(), clientTimeZone: requestZone(sayZone, config) },
    }),
    isArchived: (id) => isArchived(ctx, id),
    defaultSession: () => config.sessionId,
    limits: { ...DEFAULT_LIMITS, maxPayloadValue: 2_000 },
    allowBusy: true,
    onChange: (r) => {
      const state = { accepted: 'said', generated: 'heard', failed: 'chat-failed' }[r.state]
      const now = Date.now()
      const key = String(r.requestId)
      const began = startedAt.get(key)
      if (r.state === 'accepted') startedAt.set(key, now)
      const stripped = r.state === 'generated' && r.text && speakerOf(r.sessionId) === 'strategist'
        ? stripStrategistAudit(r.text)
        : { cleanText: r.text ?? '', hadAudit: false, audit: null }
      if (stripped.hadAudit && stripped.audit) {
        record({ requestId: r.requestId, sessionId: r.sessionId, state: 'strategist-audit', at: now, ...stripped.audit })
      }
      if (state) record({ requestId: r.requestId, sessionId: r.sessionId, state, at: now, ms: began ? now - began : undefined, failure: r.failure ?? undefined,
        // 家庭群要看得到她回了什么（只留一段）。
        text: r.state === 'generated' && stripped.cleanText ? String(stripped.cleanText).slice(0, 400) : undefined })
      if (r.state === 'generated' || r.state === 'failed') refreshPanels()
      if (r.state !== 'accepted') startedAt.delete(key)
    },
  })

  // [Boss-Wall Path B] 端内第 3 独立主管会话历史与独立回合机（已拆至 wall_boss_runtime.js）
  const {
    wallBossDesk,
    loadWallBossHistory,
    saveWallBossHistory,
    appendWallBossEntry,
    handleWallBossSessionEvent,
    wallBossBriefRoute,
    wallBossStatusSnapshot,
    wallBossChatRoute,
    mountWallBoss,
  } = createWallBossRuntime({
    ctx,
    getConfig: () => config,
    setConfig: (next) => { config = next },
    getSayZone: () => sayZone,
    getV4Hub: () => v4Hub,
    getBuildLocksOverview: () => buildLocksOverview,
    record,
    intensityStateNow,
    loadPassLedger: (now, tz) => loadPassLedger(now, tz),
    loadSeals: (now, tz) => loadSeals(now, tz),
    usageReportView,
    sealHm: (m) => sealHm(m),
    mountPersona,
    bumpDayPlanWatch: (untilMs) => { dayPlanWatchUntil = Math.max(dayPlanWatchUntil, untilMs) },
    getToolOutput: () => toolOutput,
    buildLockTruthContext,
    vaultSearchTool,
    vaultRecentTool,
  })

  const desk = new Desk({
    resolveAgent: (id) => resolveAgent(ctx, id, config),
    compose,
    allowTools: () => config.proactiveTools !== false,
    makeMessage: userMessage,
    isArchived: (id) => isArchived(ctx, id),
    defaultSession: () => config.sessionId,
    onChange: (r) => {
      const now = Date.now()
      const key = String(r.requestId)
      const began = startedAt.get(key)
      if (r.state === 'accepted') startedAt.set(key, now)
      const stripped = r.state === 'generated' && r.text && speakerOf(r.sessionId) === 'strategist'
        ? stripStrategistAudit(r.text)
        : { cleanText: r.text ?? '', hadAudit: false, audit: null }
      const cleanText = r.state === 'generated' ? stripped.cleanText : r.text
      if (stripped.hadAudit && stripped.audit) {
        record({ requestId: r.requestId, sessionId: r.sessionId, state: 'strategist-audit', at: now, ...stripped.audit })
      }
      // B1：生成出来的这一句先过信息增量守卫，再决定算不算"发出去了"。
      const ball = r.state === 'generated' ? gateBall({ ...r, text: cleanText }) : null
      record({
        requestId: r.requestId,
        eventId: r.eventId,
        sessionId: r.sessionId,
        topicId: r.topicId,
        topicKind: r.topicKind,
        serveType: r.serveType ?? undefined,
        rallyId: r.rallyId ?? undefined,
        shot: r.shot ?? undefined,
        silence: r.silence ?? undefined,
        ...(r.extra && (r.state === 'accepted' || r.state === 'generated') ? r.extra : {}),
        // 打回的那一球账本里不记 generated：它不是"发出去了"，也不是失败。
        state: ball ? ball.state : r.state,
        at: now,
        ms: began ? now - began : undefined,
        text: cleanText ?? undefined,
        // 套话只在生成时判：谁都能说的模板不算开口。判出来就记进账本，
        // 下一球把它打回去，P5 的评测也靠这个数。
        stock: isStock(cleanText) || undefined,
        skip: ball && ball.action !== 'send' ? ball.code : undefined,
        // 软判据（不沾具体名词）不拦，只记账：P5 的评测靠这个数看"有多少句没信息量"。
        soft: ball ? ball.soft : undefined,
        failure: r.failure ?? undefined,
        cold: r.cold === true,
      })
      if (r.state !== 'accepted') startedAt.delete(key)
      // 生成成功后等一次人的回应；2 小时内没有就记 none（有限跟进/冷却的依据）。
      if (r.state === 'generated') {
        const served = servedContext.get(String(r.requestId))
        servedContext.delete(String(r.requestId))
        // 这一轮不发：账本已经记了 skip-no-content / stock-rewrite，手机侧静默收尾。
        // 不算失败、不占发球额度、不写 pending（他没收到过这一句，不能拿去判球）。
        if (ball && ball.action !== 'send') {
          suppressed.add(key)
          if (ball.hint || ball.code) {
            lastStockHints.set(String(r.sessionId || ''), {
              hint: ball.hint || '刚才那句太模板化被打回了，换一个贴合他刚才行为或记忆的具体说法。',
              code: ball.code || '',
              at: now,
            })
          }
          return
        }
        lastBall = { role: speakerOf(r.sessionId), at: Date.now() }
        try {
          const sp = speakerOf(r.sessionId)
          v4Hub?.recordStoryline?.({
            kind: 'proactive_ball',
            actor: sp,
            summary: String(cleanText || '').slice(0, 80),
            at: now,
          }, now)
        } catch {}
        // 大脑从这一刻开始计：10 分钟内他有没有离开那个娱乐 App。
        if (served) {
          const b = loadBrain()
          onServe(b, { requestId: r.requestId, phone: served.phone, band: served.band, face: served.face, drill: served.drill, at: Date.now() })
          saveBrain(b)
        }
        // 发球原文、回合、拍数都留给判球用：回球回来时要能把它和这一球对上。
        // A1 改口检测：他上一球是拒绝/回避、她这一句就撤回或降低要求 = 退让一次。
        // 由代码判（isYield），不问她自不觉得自己让步了。
        try {
          const prev = lastServeOf.get(String(r.sessionId || ''))
          const his = readLedger(home(), 12).filter((e) => e.state === 'replied').pop()
          if (prev && his) {
            const turns = loadYieldTurns()
            // 回避/拒绝触发的退让按规则就是没有新证据（「他说不想做」不是事实），这里如实记。
            turns.push({ yielded: isYield({ verdict: his.verdict, before: prev.text, after: cleanText || '' }), hasEvidence: false })
            saveYieldTurns(turns)
          }
          lastServeOf.set(String(r.sessionId || ''), { text: String(cleanText || '').slice(0, 400), at: Date.now() })
        } catch {
          // 检测坏了不能拦发球
        }
        setPending(r.sessionId, {
          requestId: r.requestId,
          topicId: r.topicId,
          rallyId: r.rallyId,
          shot: r.shot,
          serveType: r.serveType,
          drill: r.extra?.drill,
          // C2：这一球是四操作里的哪一个。回球时要按它判，不能等到回球才发现不知道练的是什么。
          drillOp: r.extra?.drillOp,
          serve: cleanText || '',
          at: Date.now(),
        })
        if (r.topicId) {
          markGenerated(garden, r.topicId, cleanText, Date.now())
          persistGarden()
        }
      }
    },
  })

  ctx.on('session/event', async (session, event) => {
    try {
      desk.onSessionEvent(session?.id, event)
      chat.onSessionEvent(session?.id, event)
      wallBossDesk.onSessionEvent(session?.id, event)
    } catch {
      // never disturb the session
    }
    // [Boss-Wall Path B] 主管墙独立会话物理隔离：不进入双鱼的静音/模式/拒绝/做题/判球/故事线链路
    if (handleWallBossSessionEvent(session, event)) return
    try {
      if (event?.type === 'user/message') {
        const text = messageText(event.data)
        // 插件注入的消息都走 user/message（自己、speak-up、思维驾驶员……），
        // 也都不是人打的字。当成回复会凭空记一条 replied，还会把话题判成 done；
        // 只有来源为空或明确标成 user 的才算人。
        const kind = event.data?.source?.kind
        if (kind && kind !== 'user') return
        lastHumanAt.set(String(session?.id || ''), Date.now())
        rememberHuman(stripInjection(text))
        // 落进账本：引擎重启也知道他刚说过话（阶梯不会把聊过的球当成没接，发球也知道他多久前说的话）。
        // H8：原话落盘（≤300 字、剥掉注入标记）——换班交接、放松核对重启后照样有据。
        record({ state: 'human', at: Date.now(), sessionId: String(session?.id || ''), text: stripInjection(text).replace(/\s+/g, ' ').trim().slice(0, 300) || undefined })
        try {
          const snippet = stripInjection(text).replace(/\s+/g, ' ').trim().slice(0, 80)
          if (snippet) {
            v4Hub?.recordStoryline?.({
              kind: 'user_reply',
              actor: 'user',
              summary: snippet,
              at: Date.now(),
            }, Date.now())
          }
        } catch {}
        // 第三轮：早上/晚上的门不再靠"他认真回了一句"关——过关要交墙按证据判（submit_to_wall），
        // 敷衍的话不会让窗口消失。这里只记"他在聊"。
        // 他说"安静一小时""今天别找我"就照做：这是一句话就说清的事，不必占一次模型调用。
        const muteUntilAt = mutedUntil(text, Date.now(), { tz: config.timeZone })
        if (muteUntilAt) {
          config = { ...config, muteUntil: muteUntilAt }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮；下次他还说就再设一次
          }
          record({ state: 'muted', at: Date.now(), until: muteUntilAt, answer: String(text).slice(0, 100) })
        }
        // 他说"进入教练模式 2 小时"就切过去；给没给时长都认。
        const sw = modeSwitch(text)
        if (sw) {
          config = { ...config, mode: sw.mode, modeUntil: sw.hours ? Date.now() + sw.hours * 3_600_000 : 0 }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮
          }
          record({ state: 'mode', at: Date.now(), mode: sw.mode, until: config.modeUntil || undefined })
        }
        // "拒绝模式 / 拒绝模式 3 小时"：娱乐 App 一打开就赶回桌面锁住；"关掉拒绝模式"解除。
        const refuse = refuseSwitch(text, Date.now())
        if (refuse) {
          config = { ...config, refuseUntil: refuse.off ? 0 : refuse.until }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮
          }
          record({ state: 'refuse', at: Date.now(), until: config.refuseUntil || undefined, on: !refuse.off })
        }
        // "忙时 90 分钟""我去做饭了""忙完了"：切档（忙时顺带开拒绝模式）。
        const gearSw = gearSwitch(text, Date.now())
        if (gearSw && gearSw.gear === 'free' && aiTask(config, Date.now())) {
          // 她们立的任务：他自己喊收工不算，要验收（人设里告诉她们去验）。
          record({ state: 'task-claim', at: Date.now(), text: String(text).slice(0, 100) })
        } else if (gearSw) setGear(gearSw, 'phrase')
        // "开始做题 / 做题模式"打开做题，"别出题了"关掉。
        const drillSwitch = drillToggle(text)
        if (drillSwitch !== null) {
          config = { ...config, drills: drillSwitch }
          try {
            writeConfig(config)
          } catch {
            // 写不进去也不影响这一轮
          }
          record({ state: 'drills', at: Date.now(), on: drillSwitch })
        }
        const pending = takePending(session?.id)
        if (pending) {
          // 这一球由大肥鱼自己判（6 秒超时或连不上就退回规则），不再按字数。
          const target = judgeTargetOf(ctx, session?.id, config)
          const judgeBegan = Date.now()
          // C2：四个基本操作的合格线在代码里，这一球不问模型——问了她就会开始安慰人。
          // 判完记一条 drill-op，欠着的下一步要等这一条才放行。
          const opVerdict = pending.drillOp ? judgeOpNow(pending.drillOp, text, session?.id) : null
          const judged = opVerdict || await judgeReply({
            stream: streamOf(),
            provider: target?.provider,
            model: target?.model,
            sessionId: session?.id,
            serve: pending.serve,
            reply: text,
            recent: recentShots(),
            criteria: drillOf(pending.drill)?.pass,
            mode: effectiveMode(config, Date.now()),
          })
          const judgeMs = Date.now() - judgeBegan
          const replyClass = gardenClass(judged.verdict)
          const now = Date.now()
          const shot = (Number(pending.shot) || 0) + 1
          record({
            requestId: pending.requestId,
            sessionId: session?.id,
            topicId: pending.topicId,
            rallyId: pending.rallyId || undefined,
            shot: pending.rallyId ? shot : undefined,
            state: 'replied',
            at: now,
            judgeMs,
            replyClass,
            verdict: judged.verdict,
            why: judged.why || undefined,
            next: judged.next,
            judged: judged.judged === true,
            // 判下一球时要看得到上一球回了什么；只留一小段，账本本来就在手机上、权限 0600。
            answer: String(text || '').slice(0, 200) || undefined,
          })
          if (replyClass === 'substantive') {
            record({
              requestId: pending.requestId,
              sessionId: session?.id,
              topicId: pending.topicId,
              rallyId: pending.rallyId || undefined,
              shot: pending.rallyId ? shot : undefined,
              state: 'substantive',
              at: now,
              replyClass,
              verdict: judged.verdict,
            })
          }
          // 大脑记账：思维题过没过线、这张脸有没有把他拉回来；证据变了就改判断。
          {
            const b = loadBrain()
            onReply(b, { requestId: pending.requestId, verdict: judged.verdict })
            // 他讲了站得住的理由（真反驳）：先松一会儿，这段时间不锁。讲得通就该被听进去。
            if (judged.verdict === 'pushback') {
              b.graceUntil = now + GRACE_MS
              record({ state: 'grace', at: now, until: b.graceUntil, why: judged.why || undefined })
            }
            revise(b, now)
            saveBrain(b)
          }
          // M1.3 反敷衍冷处理：日常闲聊短回（无题/无对账/无正在进行的局与追问锁）只触发互损吐槽，不进入冷处理或物理锁；
          // 仅在真正逃避做题、审核或严格局中的连续敷衍/回避时才拉冷处理与拒绝锁。
          const mainSt = mainStateNow().id
          const inStrictGate = Boolean(pending.drill || pending.drillOp || ['morning', 'review', 'evening', 'followup'].includes(mainSt) || live)
          const casualShortOnly = judged.verdict === 'brief' && !inStrictGate
          if (casualShortOnly && session?.id) {
            casualBriefTurns.set(session.id, now)
          }
          if (config.dual !== false && Boolean(config.strategistSessionId) && !casualShortOnly) {
            const usedTarget = Boolean(lastPhone?.app && !lastPhone.fun && !lastPhone.idle && roleOf(appsOf(), lastPhone.app) === 'work')
            const wr = recordReplyOutcome(loadWithdrawal(), { verdict: judged.verdict, hadTargetAppUse: usedTarget, now })
            saveWithdrawal(wr.state)
            if (wr.entered) {
              const refuseUntil = Math.max(now + 30 * 60_000, Number(config.refuseUntil) || 0)
              commit({ refuseUntil, refuseBy: 'perfunctory' })
              presetRt.perfunctory(now, lastPhone)
              record({ state: 'withdrawal-enter', at: now, until: wr.state.personaCooldownUntil, refuseUntil, streak: wr.state.streak })
            }
            if (wr.released) record({ state: 'withdrawal-release', at: now, reason: wr.reason })
          }
          // 话题花园跟着人的反应走：认真答就继续，敷衍就换轻的，拒绝就停。
          if (pending.topicId) {
            applyReply(garden, pending.topicId, replyClass, now)
            persistGarden()
          }
        } else if (text) {
          // 正常聊天里冒出的新话题：每天最多 3 条，宁缺毋滥。
          if (discoverFromMessage(garden, text, Date.now())) persistGarden()
        }
      }
    } catch {
      // classification must never break a turn
    }
  })
  ctx.on(
    'tools/pre-execute',
    (exec, next) => {
      let denied
      try {
        denied = desk.guardTool(exec?.agent?.id ?? exec?.agent?.session?.id)
      } catch {
        denied = undefined
      }
      return denied ?? next()
    },
    { global: true, prepend: true },
  )
  ctx.on('agent/disposed', (agent) => {
    try {
      if (agent?.id) {
        desk.onAgentGone(agent.id)
        chat.onAgentGone(agent.id)
        wallBossDesk.onAgentGone(agent.id)
      }
    } catch {
      // ignore
    }
  })

  // 正常聊天时，模型可以把“以后还想聊的事”放进话题花园；主动回合里工具会被硬拦。
  const tools = ctx.get?.('tools') ?? ctx.tools
  if (tools && typeof tools.register === 'function') {
    try {
      const off = tools.register({
        name: 'companion_topic',
        description:
          'Record a topic worth returning to in the companion topic garden (not long-term facts). ' +
          'Use add when the person mentions something they want to do/watch/play/discuss later, ' +
          'or when you have an interesting share/idea worth bringing up later. ' +
          'list shows the garden; done/pause closes a topic. Keep one topic per entry.',
        parameters: {
          type: 'object',
          properties: {
            action: { type: 'string', enum: ['add', 'list', 'done', 'pause'] },
            title: { type: 'string', description: 'One line: what to talk about later.' },
            detail: { type: 'string', description: 'Optional context worth keeping.' },
            kind: { type: 'string', enum: ['continue', 'share', 'explore'], description: 'continue/unfinished, share, or an open question to think about.' },
            interest: { type: 'integer', description: '0..2, how much the person seems to care.' },
            id: { type: 'string', description: 'Topic id from list (for done/pause).' },
          },
          additionalProperties: false,
        },
        output: {
          schema: {
            type: 'object',
            additionalProperties: true,
            properties: { ok: { type: 'boolean' }, text: { type: 'string' } },
          },
          render: (_args, value) => [{ type: 'text', text: String((value && value.text) || '') }],
        },
        execute: (args = {}) => {
          const now = Date.now()
          const action = String(args.action || 'list')
          if (action === 'add') {
            const topic = addTopic(garden, {
              title: args.title,
              detail: args.detail,
              kind: args.kind,
              interest: args.interest,
              source: 'agent',
            }, now)
            if (!topic) return { ok: false, text: '标题不能为空。' }
            persistGarden()
            return { ok: true, text: '记进话题花园了：' + topic.title, topic: publicTopic(topic) }
          }
          if (action === 'list') {
            const rows = garden.topics.filter((t) => t.status !== 'done').slice(0, 10).map(publicTopic)
            return {
              ok: true,
              text: rows.length ? rows.map((t) => `${t.id} · ${t.kind} · ${t.status} · ${t.title}`).join('\n') : '花园里还没有话题。',
              topics: rows,
            }
          }
          let target = String(args.id || '')
          if (!target && args.title) {
            const needle = String(args.title).trim()
            const hit = garden.topics.find((t) => t.status !== 'done' && t.title.includes(needle))
            if (hit) target = hit.id
          }
          if (!target) return { ok: false, text: '没找到要关闭的话题（用 list 拿 id）。' }
          const closed = closeTopic(garden, target, action === 'done' ? 'done' : 'pause', now)
          if (!closed) return { ok: false, text: '没找到这个话题。' }
          persistGarden()
          return { ok: true, text: action === 'done' ? '话题结束了。' : '话题先搁着。', topic: publicTopic(closed) }
        },
      })
      if (typeof off === 'function') ctx.effect?.(() => off)
      console.error('[companion] registered tool companion_topic')
    } catch (error) {
      console.error('[companion] companion_topic 注册失败：' + String((error && error.message) || error))
    }
  }

  // ---------------------------------------------------------------- 人格进每一轮聊天
  // 人格卡以前只在主动发球那一球里带给模型；他在 App 里或浮层里正常聊，模型拿不到，
  // 人格就只剩会话自带的设定（新装就是通用助手）。这里给目标会话挂一段固定的系统上下文，
  // 和记忆插件同一个机制（systemPrompt.context），每轮都带，不改历史、不破坏前缀缓存之外的东西。
  // ---------------------------------------------------------------- 姐妹传话
  // 两人可以互相递话（sister_note 工具，按会话注册，所以知道是谁递给谁）。递给对方的话 24 小时内
  // 出现在对方每一轮的人设里；妹妹标了 urgent 的，姐姐会主动去找他（见 /plan）。
  const notesFile = () => stateFile('notes.json')
  function loadNotes() {
    const raw = readState(notesFile(), null)
    const list = Array.isArray(raw?.notes) ? raw.notes : []
    const cut = Date.now() - 3 * 86_400_000
    return list.filter((n) => n && Number(n.at) > cut).slice(-40)
  }
  function saveNotes(list) {
    writeJson(notesFile(), { notes: list.slice(-40) })
  }
  function notesFor(role, now = Date.now()) {
    return loadNotes().filter((n) => n.to === role && now - Number(n.at) < 86_400_000).slice(-5)
  }
  function sisterTool(role) {
    const names = () => roleNames(config)
    const other = role === 'strategist' ? 'companion' : 'strategist'
    return {
      name: 'sister_note',
      description:
        role === 'strategist'
          ? '给妹妹递一句话（她会在接下来和他的聊天里知道）。比如他被你说重了、太紧绷，让她去陪陪他、逗逗他。'
          : '给姐姐递一句话（她会在接下来和他的聊天里知道）。他说了正事、或者该被姐姐管一管了，就递过去；急的标 urgent，姐姐会主动去找他。',
      parameters: {
        type: 'object',
        properties: {
          text: { type: 'string', description: '一两句话：他怎么了、你想让对方做什么。' },
          urgent: { type: 'boolean', description: '急：对方会尽快主动去找他。' },
          kind: { type: 'string', description: '妹妹专用：写「线索」就是只报现象，进姐姐的待查队列（一天最多 5 条）。' },
        },
        required: ['text'],
        additionalProperties: false,
      },
      output: {
        schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
        render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
      },
      execute: (args = {}) => {
        const text = String(args.text || '').trim().slice(0, 300)
        if (!text) return { ok: false, text: '话是空的。' }
        if (config.dual === false || !config.strategistSessionId) return { ok: false, text: '现在只有一个人格，没有人可以递话。' }
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        // R9 第五项：妹妹标了「线索」的话进姐姐的待查队列——只报现象、不下结论，一天最多 5 条。
        const wantClue = role === 'companion' && String(args.kind || '').trim() === '线索'
        let clueLeft = 0
        if (wantClue) {
          const day = zoneStartOfDay(now, tz)
          const cur = clueRt.ofDay(readState(stateFile('clues.json'), null), day)
          const cue = clueRt.add(cur, { text, at: now, day })
          if (!cue.ok) {
            return { ok: false, text: '今天已经报够 5 条线索了，剩下的事让' + names().strategist + '自己去看。' }
          }
          writeJson(stateFile('clues.json'), cue.state)
          clueLeft = cue.left
          record({ state: 'companion-clue', at: now, text, left: clueLeft })
        }
        const list = loadNotes()
        list.push({ from: role, to: other, text, urgent: args.urgent === true, at: now })
        saveNotes(list)
        record({ state: 'sister-note', at: now, from: role, to: other, urgent: args.urgent === true || undefined, text })
        const to = other === 'strategist' ? names().strategist : names().companion
        return {
          ok: true,
          text: '递给' + to + '了' + (args.urgent === true ? '（急）' : '')
            + (wantClue ? '，记成线索（今天还能报 ' + clueLeft + ' 条）。' : '。'),
        }
      },
    }
  }
  /** 双人格才挂传话工具（单人格时没人可递）。 */
  const sisterToolIfDual = (role) => (config.dual !== false ? sisterTool(role) : null)
  /** 他的笔记库：设置里填的优先，否则找常见的 Obsidian / 笔记目录（10 分钟查一次）。 */
  let notesCache = { at: 0, dir: '' }
  const isDir = (p) => {
    try {
      return statSync(p).isDirectory()
    } catch {
      return false
    }
  }
  function notesDir() {
    const set = typeof config.notesPath === 'string' ? config.notesPath.trim() : ''
    if (set && isDir(set)) return set
    const now = Date.now()
    if (now - notesCache.at < 10 * 60_000) return notesCache.dir
    // 第四轮：先找带 .obsidian 的真库，再退回几个常见文件夹名。
    const dir = findObsidianVault() || NOTES_CANDIDATES.find(isDir) || ''
    notesCache = { at: now, dir }
    return dir
  }
  /** 进 prompt 的知识库摘要：长期规划、今天的事、最近改过的笔记（摘要就够了，别把整库灌进去）。 */
  function vaultContext(now) {
    const dir = notesDir()
    if (!dir) return null
    const kb = kbDir(dir, home())
    const tz = zoneOr(config.timeZone)
    const daily = loadDaily(loadDailyState(), now, tz)
    return {
      longPlan: longPlanDigest(kb, 1200) || undefined,
      tasks: daily.tasks.length ? daily.tasks : undefined,
      recent: vaultRecentNotes(dir, { limit: 3, now }) || undefined,
    }
  }
  /** 他说"我的笔记在 xxx"：她们自己记下笔记库在哪，不用他去翻设置。 */
  function notesTool() {
    return {
      name: 'notes_vault',
      description: '记下他的笔记库（Obsidian / Markdown 笔记文件夹）在哪，之后两姐妹都能去读。他告诉你路径时用；路径是手机里的绝对路径，比如 /sdcard/Documents/Obsidian/我的库。',
      parameters: {
        type: 'object',
        properties: { path: { type: 'string', description: '笔记库文件夹的绝对路径' } },
        required: ['path'],
        additionalProperties: false,
      },
      output: {
        schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
        render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
      },
      execute: (args = {}) => {
        const path = String(args.path || '').trim().replace(/\/+$/, '')
        if (!isAbsolute(path)) return { ok: false, text: '要手机里的绝对路径，比如 /sdcard/Documents/Obsidian/我的库。' }
        if (!isDir(path)) return { ok: false, text: '这个文件夹我看不到：要么路径不对，要么没在 DSH Go 里打开"访问手机文件"。' }
        config = { ...config, notesPath: path.slice(0, 300) }
        try {
          writeConfig(config)
        } catch {
          // 这一轮照样能用
        }
        notesCache = { at: 0, dir: '' }
        return { ok: true, text: '记住了，笔记库在 ' + path + '。' }
      },
    }
  }
  // ---------------------------------------------------------------- 档位 / Gemini / 控制
  /**
   * R7 P0-3：一次返回「有哪些东西可查」，不返回正文（借 dsh-xingxi 的做法）。
   * 上下文预算之后能常驻的东西有限，细节靠这个目录 + 各自的工具按需去取。
   */
  function fishIndexTool() {
    return {
      name: 'fish_index',
      description: '看一眼现在手里有什么：今天定了几件事、四段汇报还缺几段、笔记库最近动过什么、当前状态下哪些能力被收起。不带参数；要正文再用对应的工具去取。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: toolOutput,
      execute: () => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const state = mainStateNow()
        const daily = loadDaily(loadDailyState(), now, tz)
        const dep = statusOf(depositToday(now))
        const lines = [
          '现在的主状态：' + state + '（这个状态挂着 ' + toolsFor(state).length + ' 个工具）',
          '今天定了 ' + (daily.tasks || []).length + ' 件事' + ((daily.tasks || []).length ? '：' + daily.tasks.map((t) => t.task).join('、').slice(0, 120) : ''),
          '四段汇报：交了 ' + (DEPARTMENTS.length - dep.missing.length) + '/' + DEPARTMENTS.length + (dep.missing.length ? '，还缺 ' + dep.missing.join('、') : ''),
        ]
        const recent = vaultRecentNotes(notesDir(), { limit: 3, now })
        lines.push('笔记库最近动过 ' + recent.length + ' 个文件' + (recent.length ? '：' + recent.map((n) => n.rel).join('、') : '（没配笔记库或没改动）'))
        lines.push('立场记录、暗思考产物：这两个仓还没建（后面的工单才有），现在没有东西可查。')
        const gone = toolsElsewhere(state)
        lines.push(gone.length
          ? '当前状态收起的能力（能做，但要等对应状态）：' + gone.map((g) => g.name + '@' + g.when.join('/')).join('、')
          : '当前状态没有收起任何能力。')
        return { ok: true, text: lines.join('\n').slice(0, 600) }
      },
    }
  }
  const loadRhythm = () => readState(stateFile('rhythm.json'), {}) || {}
  const saveRhythm = (r) => writeJson(stateFile('rhythm.json'), r)
  // 第九轮第四项：挣来的放风额度。一天一个账，换天自动清零（earned.ofDay）。
  const todayEarned = (now = Date.now(), tz = zoneOr(config.timeZone)) =>
    earnedRt.ofDay(readState(stateFile('earned.json'), null), startOfDay(now, tz))
  const saveEarned = (e) => writeJson(stateFile('earned.json'), e)

  // ---- 2026-11-19 H1–H7 统一出口状态读写 ----
  const lockBus = createLockBus({
    load: () => readState(stateFile('lock-requests.json'), null),
    save: (s) => writeJson(stateFile('lock-requests.json'), s),
  })

  function funAppsForLockBus() {
    const tf = tierFnNow()
    return (Array.isArray(lastInstalled) ? lastInstalled : [])
      .map((a) => String(a?.pkg || ''))
      .filter((pkg) => pkg && (tf(pkg) === 'fun' || tf(pkg) === 'game') && !isNeverLockPkg(pkg))
  }

  function syncLockBusPatch(patch, now = Date.now()) {
    if (!patch || typeof patch !== 'object') return
    if (patch.aiWorkSession !== undefined) {
      const s = patch.aiWorkSession
      if (s && Number(s.until) > now && Array.isArray(s.allow) && s.allow.length) {
        const isTakeover = s.by === 'takeover'
        lockBus.upsert({
          id: isTakeover ? 'aiwork-takeover' : 'aiwork-' + (s.by || 'contract'),
          source: isTakeover ? 'takeover' : 'contract',
          kind: 'allowlist',
          allow: s.allow,
          until: Number(s.until),
          why: s.why || (isTakeover ? '系统接管强制回位' : '专时严格锁定'),
          unlockHow: isTakeover ? '回到指定正事应用即自动解除（或到期/使用紧急解除）' : '完成本轮任务或到期自动解锁',
          priority: isTakeover ? 'P3.5' : 'P3',
          replaceSource: true,
        }, now)
      } else {
        lockBus.revokeBySource('takeover', { now })
        for (const id of ['aiwork-contract', 'aiwork-clock', 'aiwork-loop', 'aiwork-panel', 'aiwork-bout', 'aiwork-takeover']) {
          lockBus.revokeBySource('contract', { id, now })
        }
      }
    }
    if (patch.refuseUntil !== undefined) {
      if (Number(patch.refuseUntil) > now) {
        const src = String(patch.refuseBy || config.refuseBy || 'sister')
        lockBus.upsert({
          id: 'refuse-' + src,
          source: src,
          kind: 'deny',
          apps: funAppsForLockBus(),
          until: Number(patch.refuseUntil),
          why: '拒绝模式：暂时锁定娱乐应用',
          priority: 'P6',
        }, now)
      } else {
        for (const src of ['radar', 'switch', 'sister']) {
          lockBus.revokeBySource(src, { id: 'refuse-' + src, now })
        }
        lockBus.revokeBySource('sister', { id: 'refuse-ai', now })
        lockBus.revokeBySource('sister', { id: 'refuse-tool', now })
      }
    }
    if (Array.isArray(patch.blockExtra)) {
      if (patch.blockExtra.length === 0) {
        lockBus.revokeBySource('radar', { now })
        lockBus.revokeBySource('sister', { now })
        lockBus.revokeBySource('switch', { now })
      } else {
        for (const b of patch.blockExtra) {
          if (b?.app && Number(b.until) > now && !isNeverLockPkg(b.app)) {
            lockBus.upsert({
              id: 'block-' + (b.by || 'sister') + '-' + b.app,
              source: b.by || 'sister',
              kind: 'deny',
              apps: [b.app],
              until: Number(b.until),
              why: b.why || '临时锁定中',
              priority: 'P6',
            }, now)
          }
        }
      }
    }
    if (patch.taskLease !== undefined) {
      const tl = patch.taskLease
      if (tl && Number(tl.until) > now && tl.app) {
        lockBus.upsert({
          id: 'task-lease',
          source: 'lease',
          kind: 'allowlist',
          allow: tl.allow || [tl.app],
          until: Number(tl.until),
          why: tl.goal || '双用途任务通行证',
          priority: 'P5',
          replaceSource: true,
        }, now)
      } else {
        lockBus.revokeBySource('lease', { now })
      }
    }
  }

  function loadRawWorkStore(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const day = dayString(now, tz)
    return normalizeWorkStore(readState(stateFile('work.json'), null), { day, now })
  }

  function mirrorWorkStoreToFiles(ws, now = Date.now(), tz = zoneOr(config.timeZone), { touchPlan = true } = {}) {
    const saved = { ...ws, updatedAt: now }
    writeJson(stateFile('work.json'), saved)
    let horizons = null
    try {
      horizons = readState(stateFile('horizons.json'), null)
    } catch {}
    const mirrored = mirrorWorkStoreToLegacy(saved, { horizons })
    if (touchPlan) {
      const dayStart = startOfDay(now, tz)
      const prevPlan = planOf(readState(stateFile('plan.json'), null), dayStart)
      const nextPlan = {
        ...prevPlan,
        v: 1,
        day: dayStart,
        at: now,
        items: mirrored.planItems,
      }
      writeJson(stateFile('plan.json'), nextPlan)
    }
    try {
      const curDaily = readState(stateFile('daily.json'), null)
      if (curDaily && Number(curDaily.day) === startOfDay(now, tz)) {
        writeJson(stateFile('daily.json'), { ...curDaily, tasks: mirrored.dailyTasks })
      }
    } catch {}
    return { store: saved, mirrored }
  }

  function workStoreNow(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const day = dayString(now, tz)
    const dayStart = startOfDay(now, tz)
    const rawWs = loadRawWorkStore(now, tz)
    const rawPlan = readState(stateFile('plan.json'), null)
    const planToday = planOf(rawPlan, dayStart)
    const rawDaily = readState(stateFile('daily.json'), null)
    const dailyTasks = rawDaily && dayString(Number(rawDaily.at || rawDaily.day || now), tz) === day && Array.isArray(rawDaily.tasks)
      ? rawDaily.tasks
      : []
    const contract = readState(stateFile('plan-contract.json'), null)
    // 一次性迁移 + 兼容直接写入 plan.json/daily.json/contract 的旧测试（外部写入时间戳更新时自动合入）
    const needMigrate = !rawWs.migratedAt ||
      (Number(rawPlan?.at) > rawWs.updatedAt) ||
      (Number(contract?.updatedAt) > rawWs.updatedAt) ||
      (dailyTasks.length > 0 && rawWs.items.length === 0)
    let ws = rawWs
    if (needMigrate) {
      ws = migrateLegacyToWorkStore({
        state: rawWs,
        planItems: planToday.items || [],
        dailyTasks,
        contract,
        day,
        now,
        tz,
      })
      writeJson(stateFile('work.json'), ws)
    }
    let horizons = null
    try {
      horizons = readState(stateFile('horizons.json'), null)
    } catch {}
    const mirrored = mirrorWorkStoreToLegacy(ws, { horizons })
    return { store: ws, ...mirrored }
  }

  const loadHardInterventions = (now = Date.now(), tz = zoneOr(config.timeZone)) =>
    normalizeHardInterventions(readState(stateFile('hard-interventions.json'), null), dayString(now, tz))
  const saveHardInterventions = (s) => writeJson(stateFile('hard-interventions.json'), s)

  function intensityStateNow(now = Date.now(), tz = zoneOr(config.timeZone), autonomyOpt = undefined) {
    let autonomy = autonomyOpt
    if (autonomy === undefined) {
      try {
        autonomy = v4Hub?.computeAutonomy?.(now) || null
      } catch {
        autonomy = null
      }
    }
    const res = resolveEffectiveIntensity(config, { autonomy, now, tz })
    if (res.promoted) {
      config = { ...config, intensity: res.intensity, pendingIntensity: null }
      try { writeConfig(config) } catch {}
      record({ state: 'intensity-promoted', at: now, intensity: res.intensity })
    }
    const day = dayString(now, tz)
    const hardInterventions = hardInterventionStatus(loadHardInterventions(now, tz), {
      day,
      tier: res.effectiveIntensity,
      cap: res.thresholds.hardInterventionDailyCap,
    })
    return {
      intensity: res.intensity,
      effectiveIntensity: res.effectiveIntensity,
      autonomyShift: res.autonomyShift,
      pendingIntensity: res.pendingIntensity,
      thresholds: res.thresholds,
      emergencyDisarmDailyCap: res.thresholds.emergencyDisarmDailyCap,
      hardInterventions,
    }
  }

  function recordHardNow({ kind = 'lock', source = '', app = '', why = '', now = Date.now(), tz = zoneOr(config.timeZone), dedupeKey = '' } = {}) {
    const day = dayString(now, tz)
    const info = intensityStateNow(now, tz)
    const cur = loadHardInterventions(now, tz)
    const r = recordHardIntervention(cur, {
      kind,
      source,
      app,
      why,
      day,
      now,
      tier: info.intensity,
      cap: info.thresholds.hardInterventionDailyCap,
      dedupeKey,
    })
    if (r.recorded) saveHardInterventions(r.state)
    return r
  }

  const loadPassLedger = (now = Date.now(), tz = zoneOr(config.timeZone)) =>
    normalizePassLedger(readState(stateFile('pass-ledger.json'), null), dayString(now, tz))
  const savePassLedger = (s) => writeJson(stateFile('pass-ledger.json'), s)

  function activeEmergencyInfo(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const pl = loadPassLedger(now, tz)
    if (isPassEmergencyActive(pl, now)) {
      return {
        active: true,
        until: Number(pl.emergency.until) || 0,
        reason: String(pl.emergency.reason || '紧急放行中'),
        source: String(pl.emergency.source || 'disarm'),
      }
    }
    const ext = loadExternal()
    if (isEmergencyPassActive(ext, now)) {
      return {
        active: true,
        until: Number(ext.emergencyPass.until) || 0,
        reason: String(ext.emergencyPass.reason || '紧急放行中'),
        source: 'emergency-pass',
      }
    }
    return null
  }
  const isEmergencyPassOrDisarmActive = (now = Date.now(), tz = zoneOr(config.timeZone)) => Boolean(activeEmergencyInfo(now, tz))

  const loadGeminiGate = (now = Date.now(), tz = zoneOr(config.timeZone)) =>
    normalizeGeminiGate(readState(stateFile('gemini-gate.json'), null), dayString(now, tz))
  const saveGeminiGate = (s) => writeJson(stateFile('gemini-gate.json'), s)

  const loadTimeLedger = (now = Date.now(), tz = zoneOr(config.timeZone)) =>
    normalizeTimeLedger(readState(stateFile('time-ledger.json'), null), dayString(now, tz))
  const saveTimeLedger = (s) => writeJson(stateFile('time-ledger.json'), s)

  const EARN_LEDGER_KINDS = new Set(['earned_bank', 'earned_bout', 'earned_seal', 'earned_review'])
  function recordTimeEntryNow({ kind, minutes = 0, reason = '', at = Date.now(), tz = zoneOr(config.timeZone) } = {}) {
    const day = dayString(at, tz)
    let baseState = loadTimeLedger(at, tz)
    const mins = Math.max(0, Math.round(Number(minutes) || 0))
    if (EARN_LEDGER_KINDS.has(String(kind || '')) && mins > 0) {
      const tlBefore = timeLedgerNow(at, tz, lastPhone)
      if (String(kind || '') === 'earned_bank') {
        // 刷题挣到的 earned_bank 绝不被历史违约 penalty 或历史超额吞噬：先抵平此前未消化的 penalty shortfall
        const effectivePenaltyBefore = Math.max(0, (Number(tlBefore.penalty) || 0) - (Number(baseState.penaltyOffset) || 0))
        const penaltyShortfall = Math.max(
          0,
          effectivePenaltyBefore - ((Number(tlBefore.earned) || 0) + (Number(tlBefore.carry) || 0)),
        )
        if (penaltyShortfall > 0) {
          baseState = { ...baseState, penaltyOffset: (Number(baseState.penaltyOffset) || 0) + penaltyShortfall }
        }
      }
      const effectivePenaltyAfter = Math.max(0, (Number(tlBefore.penalty) || 0) - (Number(baseState.penaltyOffset) || 0))
      const penaltyAppliedAfter = Math.min((Number(tlBefore.earned) || 0) + (Number(tlBefore.carry) || 0), effectivePenaltyAfter)
      const availBefore = (Number(tlBefore.carry) || 0)
        + (Number(tlBefore.funQuota) || 0)
        + (Number(tlBefore.earned) || 0)
        - penaltyAppliedAfter
      const usedBefore = (Number(tlBefore.funUsed) || 0) + (Number(tlBefore.gameUsed) || 0)
      const effectiveUsedBefore = Math.max(0, usedBefore - (Number(baseState.deficitOffset) || 0))
      const shortfall = Math.max(0, effectiveUsedBefore - availBefore)
      if (shortfall > 0) {
        baseState = { ...baseState, deficitOffset: (Number(baseState.deficitOffset) || 0) + shortfall }
      }
    }
    const next = appendTimeEntry(baseState, { kind, minutes, reason, at, day })
    saveTimeLedger(next)
    return next
  }

  /**
   * 2026-11-19 挣来制的跨天余额：fun-wallet.json 记「今天从昨天带进来多少」和「今天最后一次算出的余额」。
   * 换天时把昨天最后的余额（≤ CARRY_CAP_MIN）结转成今天的 carryIn。只往后滚，不回头改旧的一天。
   */
  function walletCarryIn(day) {
    const w = readState(stateFile('fun-wallet.json'), null) || {}
    const wDay = String(w.day || '')
    if (wDay === day) return Math.max(0, Number(w.carryIn) || 0)
    if (wDay && day < wDay) return 0
    const carryIn = wDay ? Math.min(CARRY_CAP_MIN, Math.max(0, Math.round(Number(w.lastLeft) || 0))) : 0
    writeJson(stateFile('fun-wallet.json'), { v: 1, day, carryIn, lastLeft: carryIn, prevDay: wDay, prevLeft: Math.max(0, Number(w.lastLeft) || 0) })
    if (wDay) record({ state: 'wallet-carry', at: Date.now(), day, carryIn, from: wDay })
    return carryIn
  }

  function walletNote(day, left) {
    const w = readState(stateFile('fun-wallet.json'), null) || {}
    if (String(w.day || '') !== day || Number(w.lastLeft) === left) return
    writeJson(stateFile('fun-wallet.json'), { ...w, lastLeft: left })
  }

  function timeLedgerNow(now = Date.now(), tz = zoneOr(config.timeZone), phone = lastPhone) {
    const day = dayString(now, tz)
    const carry = walletCarryIn(day)
    const info = intensityStateNow(now, tz)
    const tlState = loadTimeLedger(now, tz)
    const sumBoutInLedger = tlState.entries
      .filter((e) => e.kind === 'earned_bout')
      .reduce((s, e) => s + (Number(e.minutes) || 0), 0)
    const earnedObj = todayEarned(now, tz)
    const legacyEarnedExtra = Math.max(0, (Number(earnedObj?.earned) || 0) - sumBoutInLedger)
    const todayRows = Array.isArray(phone?.today) && phone.today.length ? phone.today : (lastPhone?.today || [])
    const tl = computeTimeLedger({
      state: tlState,
      day,
      today: todayRows,
      tierOf: tierFnNow(),
      passMinutes: passMinutesToday(now, tz),
      funQuota: Math.max(DEFAULT_BASE_FUN_MIN, Number(info.thresholds.funQuotaMin) || 0),
      gameQuota: info.thresholds.gameQuotaMin,
      earnedBout: legacyEarnedExtra,
      carry,
    })
    walletNote(day, tl.funLeft)
    lastQuota = tl
    return tl
  }

  function requestGeminiViaGate(opts = {}) {
    const now = Number(opts.now) || Date.now()
    const tz = opts.tz || zoneOr(config.timeZone)
    const day = dayString(now, tz)
    const info = intensityStateNow(now, tz)
    const gate = loadGeminiGate(now, tz)
    const g = loadGemini()
    const sessionActive = Boolean(opts.sessionActive ?? (inGemini(lastPhone, geminiPackages()) || (g.session && Number(g.session.until) > now)))
    const res = requestGemini(gate, {
      ...opts,
      now,
      day,
      dailyCap: info.thresholds.geminiAutoMaxPerDay,
      sessionActive,
      emergencyActive: opts.emergencyActive ?? isEmergencyPassOrDisarmActive(now, tz),
      isNight: opts.isNight ?? Boolean(lastRhythm?.night),
      isQuiet: opts.isQuiet ?? inQuiet(now, config.quietStart, config.quietEnd, tz),
      hardCapped: opts.hardCapped ?? info.hardInterventions.capped,
      pullMode: opts.pullMode ?? v4Hub?.getPullMode?.(now),
      kbDirPath: opts.kbDirPath ?? (() => { try { const d = notesDir(); return d ? kbDir(d, home()) : '' } catch { return '' } })(),
      owner: opts.owner ?? String(config.owner || '他'),
      bridge: opts.bridge ?? bridgeHealthy(now),
    })
    if (res.allowed) {
      saveGeminiGate(res.state)
      if (res.mode === 'auto') {
        recordHardNow({ kind: 'auto_gemini', source: 'gemini:' + res.source, why: opts.topic || res.source, now, tz, dedupeKey: 'gem:' + res.source + ':' + Math.floor(now / 60_000) })
      }
    }
    return res
  }
  /** 最近一次 /plan 算出的作息（早上门 / 晚上门 / 深夜阶段 / 深夜锁到几点）。 */
  let lastRhythm = null
  // 2026-11-20 公约挑场景用：上一拍裁决器是不是在锁。
  let lastLockActive = false
  let dayPlanWatchUntil = 0
  // 2026-11-22 P0 修复：伴侣力度锁边沿与裁决器锁边沿分离跟踪，避免默认验收工作窗激活时压死 graceUntil 放行边沿。
  let prevForceLocked = false
  let prevArbiterLocked = false
  let lastApplyWindow = null
  /** A4：块结束时该问的那句「这一块记到哪个部」——内容层，30 分钟内有效。 */
  let slotAskNext = null
  const loadNotifs = () => readState(stateFile('notifs.json'), {}) || {}
  const saveNotifs = (n) => writeJson(stateFile('notifs.json'), n)
  /** 永远不封的：电话、短信、地图、支付、DSH 自己。 */
  const NEVER_BLOCK = ['com.android.dialer', 'com.google.android.dialer', 'com.android.phone', 'com.android.server.telecom', 'com.android.mms',
    'com.google.android.apps.messaging', 'com.android.contacts', 'com.autonavi.minimap', 'com.baidu.BaiduMap', 'com.google.android.apps.maps',
    'com.tencent.map', 'com.eg.android.AlipayGphone', 'com.unionpay', 'io.github.ccch1mneyyy.dsh_app', 'io.github.ccch1mneyyy.dsh_app.fish']
  /** 临时封禁名单（block_apps 写的）：还没到期的包名。 */
  const blockedNow = (now) => (Array.isArray(config.blockExtra) ? config.blockExtra : [])
    .filter((b) => b && Number(b.until) > now && !NEVER_BLOCK.includes(b.app) && !isNeverLockPkg(b.app)).map((b) => b.app)
  const loadGemini = () => readState(stateFile('gemini.json'), {}) || {}
  const saveGemini = (g) => writeJson(stateFile('gemini.json'), g)
  // D4：预约的下一场 Gemini 连麦（一次性，发过就 fired）。
  const loadGeminiSchedule = () => readState(stateFile('gemini-schedule.json'), {}) || {}
  const saveGeminiSchedule = (s) => writeJson(stateFile('gemini-schedule.json'), s)
  /**
   * "HH:MM" 解析成当地时间的下一个时刻：今天还没到就是今天，过了就是明天。
   * 认不出来返回 0，调用方再试 ISO。
   */
  function nextLocalAt(text, now, tz) {
    const m = /^(\d{1,2}):(\d{2})$/.exec(String(text || '').trim())
    if (!m) return 0
    const hour = Number(m[1])
    const minute = Number(m[2])
    if (!(hour >= 0 && hour < 24 && minute >= 0 && minute < 60)) return 0
    const day = startOfDay(now, tz)
    // 当地零点 + 时分就是当地那一刻。
    const target = day + (hour * 60 + minute) * 60_000
    return target > now ? target : target + 86_400_000
  }
  /**
   * 2026-11-18 触发器：给 Gemini 的设定（笔记库 Gemini设定.md）+ 当下事实（前台、这一局、娱乐用时、截止日、骰子）。
   * 任何一处拼 Gemini 简报都带上它；读不到的就不写，全读不到时让 Gemini 先问一个具体问题。
   */
  function geminiExtras(now = Date.now(), dice = null) {
    let setting = ''
    try {
      const dir = notesDir()
      setting = loadGeminiSetting(dir ? kbDir(dir, home()) : '')
    } catch {
      // 用默认设定
    }
    const phone = lastPhone || {}
    let bout = null
    try {
      const run = runningBoutNow()
      if (run) bout = { title: run.kind || run.protocolId, firstStep: String(live?.official?.view?.()?.ask || '') }
    } catch {
      // 没有进行中的局
    }
    const funMinutesToday = (Array.isArray(phone.today) ? phone.today : [])
      .filter((t) => t?.fun).reduce((sum, t) => sum + (Number(t.minutes) || 0), 0)
    let deadlines = []
    try {
      deadlines = v4Hub?.upcomingDeadlines ? v4Hub.upcomingDeadlines(now) : []
    } catch {
      deadlines = []
    }
    return {
      setting,
      bridge: bridgeHealthy(now),
      context: contextLines({
        front: phone.label || phone.app || '',
        frontMinutes: Number(phone.minutes) || 0,
        bout,
        funMinutesToday,
        deadlines,
        dice,
      }),
    }
  }
  /** 到点了就把预约换成一次 launch（背景简报 + 进共舱），只发一次。 */
  function dueGeminiLaunch(now = Date.now()) {
    const s = loadGeminiSchedule()
    const at = Number(s.at) || 0
    if (!at || s.fired === true || at > now) return null
    const topic = String(s.topic || '')
    const minutes = Math.max(5, Math.min(180, Math.round(Number(s.minutes) || 30)))
    const mode = geminiMode(s.mode)
    const extras = geminiExtras(now)
    const about = personaLines(mergePersona(readCorePersona(home()), config.persona), effectiveMode(config, now)).slice(0, 5)
    const commitments = readCommitments(home(), now, 3).map((c) => c.statement)
    const preAuthorized = Boolean(s.alreadyGated || s.by === 'drift' || s.by === 'preset' || s.by === 'ladder')
    let brief = ''
    if (preAuthorized) {
      brief = buildStandardGeminiBrief({
        owner: String(config.owner || '他'),
        topic,
        minutes,
        mode,
        about,
        commitments,
        ...extras,
      })
    } else {
      const gateRes = requestGeminiViaGate({
        source: s.by || 'schedule',
        mode: 'manual',
        topic,
        minutes,
        geminiMode: mode,
        about,
        commitments,
        ...extras,
        now,
        sessionActive: false,
      })
      if (!gateRes.allowed) return null
      brief = gateRes.brief
    }
    saveGeminiSchedule({ ...s, fired: true, firedAt: now })
    saveGemini({ ...loadGemini(), session: { topic, mode, minutes, startedAt: now, until: now + minutes * 60_000, by: s.by || 'schedule' } })
    record({ state: 'gemini-schedule-fired', at: now, topic, minutes, mode })
    try {
      v4Hub?.recordStoryline?.({ kind: 'gemini_start', actor: 'gemini', summary: `预约连麦开始：${topic || '共脑'}（${minutes}m）`, at: now }, now)
    } catch {}
    return { topic, minutes, mode, brief }
  }
  /**
   * R4-6 走神强拉：忙时他在娱乐 App 里坐着，就开一场审讯式连麦。
   * 判据在 gemini.js 的 driftPull（白天 / 忙时 / 坐够分钟数 / 深夜吃饭放风不拉 / 一天两次）。
   * 拉起来的办法不另造一条通知：写进 R4-4 的预约文件，手机到点自己调 /gemini/due 取简报、
   * 复制进剪贴板、打开 Gemini。`at` 放半秒后是为了让这次 /plan 就把它当"下一场"下发。
   */
  function driftPullNow(now, phone, tz) {
    const sch = loadGeminiSchedule()
    if (!sch.fired && Number(sch.at) > now) return null
    const info = intensityStateNow(now, tz)
    const r = driftPull({
      phone, now, day: startOfDay(now, tz), gear: effectiveGear(config, now), busyWhat: config.gearWhat,
      night: lastRhythm?.night, meal: mealNow(wallConfig(config).meals, localMinute(phone, zoneMinuteOfDay(now, tz))),
      onBreak: onBreak(now) || isEmergencyPassOrDisarmActive(now, tz), session: geminiSessionView(now),
      pulls: loadGemini().pulls, dwellMin: config.driftMin,
    })
    if (!r) return null
    const gateRes = requestGeminiViaGate({
      source: 'drift',
      mode: 'auto',
      topic: r.topic,
      minutes: r.minutes,
      geminiMode: r.mode,
      now,
      tz,
      hardCapped: info.hardInterventions.capped,
      isNight: Boolean(lastRhythm?.night),
      isQuiet: inQuiet(now, config.quietStart, config.quietEnd, tz),
    })
    if (!gateRes.allowed) return null
    const at = now + 500
    saveGeminiSchedule({ at, topic: r.topic, minutes: r.minutes, mode: r.mode, by: 'drift', alreadyGated: true })
    const g = loadGemini()
    saveGemini({ ...g, pulls: [...(Array.isArray(g.pulls) ? g.pulls : []), { at: now, app: r.app, minutes: r.dwell }].slice(-30) })
    record({ state: 'gemini-drift', at: now, app: r.app, minutes: r.dwell })
    return { ...r, brief: gateRes.brief }
  }
  const loadGearState = () => readState(stateFile('gear.json'), {}) || {}
  const saveGearState = (g) => writeJson(stateFile('gear.json'), g)
  // 第二轮·每日文件：今天的事 + 完成物 + 晚上对账的结论（渲染成 大肥鱼/每日/日期.md）。
  const loadDailyState = () => readState(stateFile('daily.json'), {}) || {}
  const saveDailyState = (s) => writeJson(stateFile('daily.json'), s)
  // A2：我.md 原文——硬安排（「08:00-09:00 上课」）从里面抠，排时间表时要避开。
  const selfRaw = () => {
    try {
      return readFileSync(join(kbDir(notesDir(), home()), SELF_FILE), 'utf8')
    } catch {
      return ''
    }
  }
  /** 今天的时间表参数：吃饭时段 + 我.md 里的硬安排，一处算清，别各拼一套。 */
  const planArgs = (now, tz) => ({ now, tz, meals: wallConfig(config).meals, fixedBlocks: fixedBlocksOf(selfRaw()) })
  /** 今天的事（A4 要用「某部有任务却没记录」这个对照）。 */
  const dailyToday = (now = Date.now()) => loadDaily(loadDailyState(), now, zoneOr(config.timeZone))
  const savePlan = (state) => {
    saveDailyState(state)
    writeDaily(kbDir(notesDir(), home()), state, {}, fourNumbers())
  }
  /**
   * A2：走一遍今天的时间表。块开始就把这一块立成任务模式（娱乐照规则锁），块结束继续点他交墙。
   * 挂在 planRoute 上——引擎里没有第二个"后台"面，和不说话就不跑的那几个 sweep 同一限制。
   */
  function planSweepNow(now, tz) {
    const state = loadDaily(loadDailyState(), now, tz)
    if (!state.tasks.length) return
    const out = planSweep(state, planArgs(now, tz))
    if (out.plan && (!state.plan || out.events.length)) savePlan(out.state)
    for (const ev of out.events) {
      record({ state: 'plan-' + ev.kind.replace('plan-', ''), at: now, taskId: ev.taskId, task: ev.task, start: ev.start, end: ev.end })
      if (ev.kind !== 'block-start') continue
      // 块开始＝自动立任务（目标就是这一块），到点由 gear 那套继续锁着等交墙。
      const until = zoneStartOfDay(now, tz) + ev.end * 60_000
      setGear({ gear: 'busy', until, what: ev.task, task: { goal: ev.task, by: 'strategist', reason: '今天排的时间块' } }, 'plan')
    }
    // 到点没交＝一个看得见的动作：把剩下的往后挪一次（额度用完就不再挪）。
    if (out.events.some((e) => e.kind === 'block-end')) {
      const ended = out.events.filter((e) => e.kind === 'block-end')
      // A4：块结束时问一句「这一块记到哪个部」——内容层的话，所以按「哪天 + 第几块」挑一种说法。
      slotAskNext = { text: slotAsk(state.day, ended[ended.length - 1].start), at: now }
      const r = reschedulePlan(loadDaily(loadDailyState(), now, tz), { ...planArgs(now, tz), trigger: 'overdue' })
      if (r.ok) {
        savePlan(r.state)
        record({ state: 'plan-reschedule', at: now, trigger: 'overdue' })
      }
    }
  }
  /** 约束层里的「此刻」一行：当前时间块，或者到点了还没交墙的那一块；后面接今天的局。 */
  function planText() {
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    const min = zoneMinuteOfDay(now, tz)
    const state = loadDaily(loadDailyState(), now, tz)
    let block = ''
    if (state.plan) {
      const cur = state.plan.blocks.find((b) => min >= b.start && min < b.end)
      const late = state.plan.blocks
        .filter((b) => min >= b.end && !state.plan.submitted?.[b.taskId])
        .sort((a, b) => b.end - a.end)[0]
      block = cur ? blockLine(cur, min) : (late && min < late.end + 10 ? lateLine(late) : '')
    }
    // 第八轮 P1/P2：今天的局也挂在这一层。计划一定下来，这一层就不许再出现开放问句。
    const planToday = planOf(readState(stateFile('plan.json'), null), startOfDay(now, tz))
    const today = planLines(planToday, { now, min })
    return [block, ...today].filter(Boolean).join('\n')
  }
  /** A2：一件交墙通过了 → 那块收工，剩下的重排一次（额度由 daily.reschedule 管）。 */
  function planAdvance(now, taskId) {
    if (!taskId) return null
    const tz = zoneOr(config.timeZone)
    const marked = markBlockDone(loadDaily(loadDailyState(), now, tz), taskId, zoneMinuteOfDay(now, tz))
    if (!marked.plan) return null
    const r = reschedulePlan(marked, { ...planArgs(now, tz), trigger: 'submitted-early' })
    const next = r.ok ? r.state : marked
    savePlan(next)
    if (r.ok) record({ state: 'plan-reschedule', at: now, trigger: 'submitted-early' })
    return r.ok ? r.text : null
  }
  // 第五轮·每日四部门提交：信息收集 / 谋划思考 / 行为管理 / 统筹规划，一天一份，零点复归。
  const loadDepositState = () => readState(stateFile('deposit.json'), {}) || {}
  const saveDepositState = (s) => writeJson(stateFile('deposit.json'), s)
  /** 今天这份提交。没交过就是空的四项。 */
  const depositToday = (now) => depositOf(loadDepositState(), dayString(now, zoneOr(config.timeZone)))
  const geminiPackages = () => (Array.isArray(config.geminiPackages) && config.geminiPackages.length ? config.geminiPackages.map(String) : GEMINI_PACKAGES)
  /**
   * 正在进行的 Gemini 互动舱：手机据此只许 Gemini（急事底线 App 除外）。
   *
   * 第八轮 P4：同一份舱还承担「升级舱」。`session.cell` 有两个值：
   *   'cell'     E1/E2 的审讯舱——只放行 Gemini，且**不给第三方应用宽限**（施工书「但不给第三方应用宽限」）；
   *   'all-lock' E1′ 的全机锁——放行名单为空，只剩急事底线和局界面（DSH 自己）。
   * 不另造字段：手机 applySession 只读 until/allow/graceMs 三个，多一个形态就多一处它不认。
   */
  let forceUnlockUntil = 0

  const geminiSessionView = (now = Date.now()) => {
    const s = loadGemini().session
    if (!s || !(Number(s.until) > now)) return null
    const cell = String(s.cell || '')
    const isLadderAllLock = cell === 'all-lock' && s.by === 'ladder'
    return {
      app: geminiPackages()[0],
      allow: isLadderAllLock ? [] : geminiPackages(),
      until: Number(s.until),
      // R7 P0-1：舱内第三方 App 的宽限。手机侧不再"非白名单一律锁"，按这个秒数先放行再升锁。
      // 升级舱（by === 'ladder'）一律 0；主动开的 Gemini 训练模式给足宽限且永不锁 Gemini 自己。
      graceMs: (cell && s.by === 'ladder') ? 0 : Math.max(0, Number(config.sessionGraceMs) || 45_000),
      cell: cell || undefined,
      topic: String(s.topic || ''),
      mode: String(s.mode || 'think'),
      by: s.by || undefined,
    }
  }
  /** 正在跑的局（预案和行为大脑共用）。live 在插件体后面才声明；启动途中被问到就当没有局。 */
  function runningBoutNow() {
    let cur = null
    try {
      cur = live
    } catch {
      return null
    }
    if (!cur || cur.official.view().closed) return null
    return { id: String(cur.official.view().boutId || ''), kind: String(cur.protocol?.name || cur.protocol?.id || ''),
      protocolId: String(cur.protocol?.id || ''), openedAt: cur.openedAt }
  }
  // 第九轮 A 组：预案运行时。落手机的几条路都复用阶梯已有的那几处（舱、封禁、墙收紧、计划模态）。
  const presetRt = createPresetRuntime({
    load: () => readState(stateFile('presets.json'), null),
    save: (d) => writeJson(stateFile('presets.json'), d),
    record,
    runningBout: () => runningBoutNow(),
    ignore: () => [...NEVER_BLOCK],
    day: (now) => dayString(now, zoneOr(config.timeZone)),
    minute: (now) => zoneMinuteOfDay(now, zoneOr(config.timeZone)),
    weekday: (now) => weekdayOf(now, zoneOr(config.timeZone)),
    funToday,
    apply: {
      block: (app, until, why) => {
        const keep = (Array.isArray(config.blockExtra) ? config.blockExtra : []).filter((b) => b && b.app !== app)
        commit({ blockExtra: [...keep, { app, until, by: 'preset', why: String(why || '').slice(0, 120) }].slice(-30) })
      },
      allLock: (until, why) => {
        const now = Date.now()
        const minutes = Math.max(1, Math.round((until - now) / 60_000))
        const gateRes = requestGeminiViaGate({ source: 'preset', mode: 'auto', topic: String(why || '').slice(0, 100), minutes, geminiMode: 'interrogate', now })
        if (!gateRes.allowed) return
        saveGemini({ ...loadGemini(), session: { topic: String(why || '').slice(0, 100), mode: 'interrogate', minutes, startedAt: now, until, by: 'preset', cell: ALL_LOCK } })
        lockBus.upsert({ id: 'gemini-cell', source: 'gemini-cell', kind: 'allowlist', allow: [], until, why: String(why || '连麦全锁'), priority: 'P1', replaceSource: true }, now)
      },
      tool: (tool, until, topic) => {
        const now = Date.now()
        const minutes = Math.max(1, Math.round((until - now) / 60_000))
        const gateRes = requestGeminiViaGate({ source: 'preset', mode: 'auto', topic, minutes, geminiMode: 'think', now })
        if (!gateRes.allowed) return
        saveGemini({ ...loadGemini(), session: { topic, mode: 'think', minutes, startedAt: now, until, by: 'preset' } })
        saveGeminiSchedule({ at: now + 500, topic, minutes, mode: 'think', by: 'preset', alreadyGated: true })
        lockBus.upsert({ id: 'gemini-cell', source: 'gemini-cell', kind: 'allowlist', allow: geminiPackages(), until, why: topic || 'Gemini 连麦', priority: 'P1', replaceSource: true }, now)
      },
      plan: (until) => saveLadderDoc({ ...loadLadderDoc(), planUntil: until }),
      tighten: (why) => tightenNow(Date.now(), zoneOr(config.timeZone), { reason: String(why || '预案').slice(0, 100), by: 'preset' }),
    },
  })
  const clampStrictMinutes = (m, dflt = 45) => {
    const n = Number(m)
    return Number.isFinite(n) && n > 0 ? Math.max(10, Math.min(360, Math.round(n))) : dflt
  }
  const _presetStrictNow = presetRt.strictNow
  presetRt.strictNow = (now = Date.now(), why = '', by = 'user', minutes = 45) => {
    const clampedMin = clampStrictMinutes(minutes, 45)
    const r = _presetStrictNow(now, why, by, clampedMin)
    if (r?.ok) {
      const sv = presetRt.sessionView(now)
      lockBus.upsert({
        id: 'preset-strict',
        source: 'preset',
        kind: 'allowlist',
        allow: sv?.allow || r.allow || [],
        until: sv?.until || (now + clampedMin * 60_000),
        why: sv?.why || why || '严格模式锁定中',
        unlockHow: '完成本局收工验收，或在锁屏答对 10 道真题换取 5 分钟通行',
        priority: 'P3',
        replaceSource: true,
      }, now)
    }
    return r
  }
  const _presetStrictOff = presetRt.strictOff
  presetRt.strictOff = (now = Date.now(), by = 'user') => {
    const r = _presetStrictOff(now, by)
    lockBus.revokeBySource('preset', { now })
    return r
  }
  /** 墙的默认锁受 Option A 开关与「全天默认锁」预案控制；停用时不落默认锁（收紧、深夜锁照旧）。 */
  const wallCfgNow = () => (
    (config.dayWorkWallEnabled !== false && presetRt.isActive('default-lock'))
      ? config
      : { ...config, wall: { ...(config.wall || {}), defaultLock: false } }
  )
  let lastInterceptCache = null
  function loadLastIntercept() {
    if (lastInterceptCache && typeof lastInterceptCache === 'object') return lastInterceptCache
    const st = readState(stateFile('last-intercept.json'), null)
    if (st && typeof st === 'object' && st.pkg) {
      lastInterceptCache = st
      return st
    }
    return null
  }
  function resolveWinnerHandMeta(ctxInfo = {}) {
    const arb = ctxInfo.lockArbiter || null
    const p = String(arb?.priority || 'P6')
    if (p === 'P0' || ctxInfo.emActive) return { id: 'EMERGENCY_SUSPEND', name: '急事 3 分钟通行证' }
    if (p === 'P1' || ctxInfo.nightLocked || ctxInfo.evLock) return { id: 'NIGHT_CURFEW', name: '深夜睡眠 & 晚间上限锁' }
    if (arb?.source === 'SISTER_BREAK' || p === 'P1.2') return { id: 'SISTER_BREAK', name: '双鱼审核放风中' }
    if (p === 'P2' || ctxInfo.gemSessionActive) return { id: 'GEMINI_LIVE_CELL', name: 'Gemini 连麦舱' }
    if (p === 'P3' || p === 'P3.5' || ctxInfo.overdueOrTakeover) {
      return { id: 'CONTRACT_OVERDUE_STRICT', name: '验收锁（计划块/封印/主管指令）' }
    }
    if (p === 'P4') {
      if (ctxInfo.manualBoutStrict) return { id: 'BOUT_STRICT', name: '专注严格局' }
      if (ctxInfo.dayBlockActive || arb?.by === 'dayblock_active') {
        return { id: 'BOUT_STRICT', name: arb?.label || '计划时间块锁定中' }
      }
      if (ctxInfo.dayWallActive) return { id: 'DAY_WORK_WALL', name: '工作时段默认防分心锁' }
      if (arb?.subSource === 'DAY_WORK_WALL') return { id: 'DAY_WORK_WALL', name: '工作时段默认防分心锁' }
      return { id: 'BOUT_STRICT', name: '专注严格局' }
    }
    return { id: '', name: '' }
  }
  function recordLastIntercept(raw = {}, now = Date.now()) {
    const pkg = String(raw.pkg || raw.app || '').trim().slice(0, 140)
    if (!pkg) return null
    const at = Number(raw.at) > 0 ? Number(raw.at) : now
    const prev = loadLastIntercept()
    if (prev && Number(prev.at) > at && raw.force !== true) return prev
    const arb = (() => { try { return v4Hub?.computeLockArbiter?.(now) } catch { return null } })()
    const presetSess = (() => { try { return presetRt.sessionView(now) } catch { return null } })()
    const dayBlockAct = (() => { try { return v4Hub?.dayBlockActiveSessionView?.(now) } catch { return null } })()
    const dayWallOn = config.dayWorkWallEnabled !== false
    const vsNow = dayWallOn ? (() => { try { return verifySessionView(now) } catch { return null } })() : null
    const winner = resolveWinnerHandMeta({
      lockArbiter: arb,
      manualBoutStrict: Boolean(presetSess && presetSess.by !== 'verify'),
      dayBlockActive: Boolean(dayBlockAct),
      dayWallActive: Boolean(vsNow),
    })
    const handId = String(raw.handId || winner.id || arb?.source || 'RULE_LOCK').slice(0, 48)
    const handName = String(raw.handName || winner.name || arb?.label || '专注护栏').slice(0, 60)
    const label = String(raw.label || appDisplayLabel(pkg) || pkg).slice(0, 60)
    const reason = String(raw.reason || arb?.why || '当前策略拦截该娱乐或分心应用').slice(0, 180)
    const entry = { pkg, label, at, handId, handName, reason }
    lastInterceptCache = entry
    try { writeJson(stateFile('last-intercept.json'), entry) } catch {}
    return entry
  }
  const leaseSessionView = (now) => {
    const l = activeAppLease(loadExternal(), now)
    if (!l) return null
    return {
      allow: [...new Set([...l.packages, ...emergencyPackages(appsOf())])],
      until: l.expiresAt,
      topic: l.topic,
      mode: 'lease',
      agentId: l.agentId,
    }
  }
  const clockKernel = new ClockKernel(stateFile('clock.json'), { tz: zoneOr(config.timeZone) })
  const aiWorkSessionView = (now) => {
    const s = config.aiWorkSession
    if (!s || Number(s.until) <= now || !Array.isArray(s.allow) || !s.allow.length) return null
    const allow = [...new Set([...s.allow, ...emergencyPackages(appsOf())])]
    return {
      allow,
      until: Number(s.until),
      topic: String(s.why || '专时锁定'),
      why: String(s.why || '专时锁定中：只允许指定正事 App'),
      strict: s.strict !== false,
      graceMs: s.strict === false ? 10_000 : 0,
      mode: s.strict === false ? 'task-lease' : 'ai-work',
      ...(s.by ? { by: s.by } : {}),
    }
  }
  const taskLeaseView = (now) => {
    const tl = config.taskLease
    if (!tl || Number(tl.until) <= now || !tl.app) return null
    return {
      allow: [...new Set([tl.app, ...(Array.isArray(tl.allow) ? tl.allow : []), ...emergencyPackages(appsOf())])],
      until: Number(tl.until),
      topic: String(tl.goal || '双用途工具租约'),
      why: '鲸澜批复任务租约：' + String(tl.goal || '').slice(0, 60),
      strict: false,
      graceMs: 10_000,
      mode: 'task-lease',
    }
  }
  let v4Hub = null
  /** 手机的 session 槽：连麦舱 / AI 专时锁 / 逾期严锁计划契约 / 鲸澜租约 / 外部 Agent 租约 / 预案严格模式 / 护航期契约。 */
  /**
   * 2026-11-23 手机上有两条执行路：按裁决器的锁结论拦（InterceptRuntime），和按 session 名单拦（ProactiveRuntime）。
   * 锁结论在严格类锁下早就放了学习层 + AI（Gemini / ChatGPT / Claude）+ NekoBox，session 名单却还是窄的（只有局 / 验收点那一两个 App），
   * 结果锁页上列着的 Claude、BOSS 直聘点了打不开。这里让严格类 session 的名单和锁结论一样宽。连麦舱（只许 Gemini）不动。
   */
  const strictWorkAllowNow = () => {
    const tf = tierFnNow()
    const installedWork = (Array.isArray(lastInstalled) ? lastInstalled : []).map((x) => String(x?.pkg || '')).filter((p) => p && tf(p) === 'work')
    return [...WORK_DEFAULT, ...installedWork, ...GEMINI_PACKAGES, ...PROXY_PACKAGES]
  }
  const widenStrictSession = (sess) => (sess && Array.isArray(sess.allow)
    ? { ...sess, allow: [...new Set([...sess.allow, ...strictWorkAllowNow()])] }
    : sess)
  const reverseCapsuleSessionView = (now = Date.now()) => {
    const rc = live?.reverseCapsule
    if (!rc || !live || live.official?.view?.().closed) return null
    if (!(Number(rc.until) > now)) return null
    const strictSess = presetRt.sessionView(now)
    if (!strictSess) return null
    return {
      allow: [...new Set(rc.allow || [])],
      strict: true,
      until: Math.min(Number(strictSess.until) || rc.until, rc.until),
      by: 'reverse-capsule',
      source: 'REVERSE_CAPSULE',
      mode: rc.mode,
      scopeKey: rc.scopeKey || `reverse:${rc.mode}`,
      why: `反向单开舱(${rc.mode})：仅放行 ${(rc.allow || []).length} 个指定应用`,
    }
  }
  const sessionNow = (now) => {
    const gem = geminiSessionView(now)
    if (gem) return gem
    const highPriority = aiWorkSessionView(now)
      || v4Hub?.dayBlockOverdueSessionView?.(now)
      || v4Hub?.contractSessionView(now, { escalatedOnly: true })
      || sealSessionView(now)
      || taskLeaseView(now)
      || leaseSessionView(now)
    if (highPriority) return widenStrictSession(highPriority)
    const rev = reverseCapsuleSessionView(now)
    if (rev) return rev
    return widenStrictSession(sessionNonGemini(now))
  }
  const sessionNonGemini = (now) =>
    aiWorkSessionView(now) ||
    v4Hub?.dayBlockOverdueSessionView?.(now) ||
    v4Hub?.contractSessionView(now, { escalatedOnly: true }) ||
    sealSessionView(now) ||
    taskLeaseView(now) ||
    leaseSessionView(now) ||
    presetRt.sessionView(now) ||
    v4Hub?.dayBlockActiveSessionView?.(now) ||
    v4Hub?.contractSessionView(now, { escalatedOnly: false }) ||
    verifySessionView(now)
  // 行为大脑：片段与日摘要、模式卡。只看见、只记住；不自动立规则（K4 以后再接）。
  const brainRt = createBrainRuntime({
    loadEpisodes: () => readState(stateFile('episodes.json'), null),
    saveEpisodes: (s) => writeJson(stateFile('episodes.json'), s),
    loadCards: () => readState(stateFile('cards.json'), null),
    saveCards: (c) => writeJson(stateFile('cards.json'), c),
    record,
    day: (now) => dayString(now, zoneOr(config.timeZone)),
    minute: (now) => zoneMinuteOfDay(now, zoneOr(config.timeZone)),
    hourOf: (ms) => Math.floor(zoneMinuteOfDay(ms, zoneOr(config.timeZone)) / 60),
    // 有生效预案以它为依据的卡，状态记「有对策」。
    backed: () => presetRt.list(Date.now()).presets.filter((p) => p.status === '生效' && p.basis).map((p) => p.basis),
    // 局内偏离分钟要按局号查这一局声明过哪些 App（严格模式用的是同一张表）。
    appsOf: (boutId) => presetRt.appsOf(boutId),
  })

  /** 第九轮第二项：四个数。计划页顶部、日报第一行、周报都读这一份，不各算一遍。 */
  function fourNumbers(now = Date.now()) {
    const dg = brainRt.view(now).today || {}
    return {
      selfOpenRatio: dg.selfOpenRatio ?? null,
      deliverRatio: dg.deliverRatio ?? null,
      badRatio: dg.badRatio ?? null,
      deviationMin: Number(dg.deviationMin) || 0,
    }
  }

  /**
   * 第九轮第四项：昨天算不算「糟糕的一天」（交付率低于 30%），今天要不要走重启协议。
   * 只看昨天一天的定稿摘要：两天前的账不该决定今天排几局。
   * 昨天根本没开过局（没有摘要或 opened=0）不算——那不是糟糕的一天，是还没开始。
   */
  function restartDue(now, tz) {
    const y = dayString(now - 86_400_000, tz)
    const dg = (brainRt.view(now).digests || []).find((d) => String(d?.day || '') === y)
    if (!dg) return false
    const opened = Number(dg.bouts?.opened) || 0
    if (opened <= 0) return false
    const ratio = dg.deliverRatio
    if (ratio === null || ratio === undefined) return false
    return Number(ratio) < RESTART_BELOW
  }

  function setGear(sw, by) {
    config = applyGear(config, sw, Date.now())
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    record({ state: 'gear', at: Date.now(), gear: sw.gear, until: sw.until || undefined, what: sw.what || undefined, by })
  }
  /** 这一球真的要发了：把"迎接过 / 叫回去过 / 读回过"记下，免得下一问又来一遍。 */
  function markBall(why, now) {
    if (!why) return
    if (why.reason === 'busy-done' || why.reason === 'task-due') saveGearState({ ...loadGearState(), doneFor: Number(config.gearUntil) })
    if (why.reason === 'defied') pendingDefiance = null
    if (why.reason === 'restless') saveGearState({ ...loadGearState(), restlessAt: now })
    // 任务 / 忙时结束：拦下的通知一次性交代。
    if (why.digest === true && why.digestText === undefined) why.digestText = takeDigest()
    if (why.reason === 'morning-gate') saveRhythm({ ...loadRhythm(), morningBall: true })
    if (why.reason === 'review') saveRhythm({ ...loadRhythm(), reviewBall: true })
    if (why.reason === 'night') saveRhythm({ ...loadRhythm(), nightBallAt: now })
    if (why.reason === 'notes-chase' && why.note?.path) {
      const gs = loadGearState()
      saveGearState({ ...gs, chaseAt: now, chased: [...(Array.isArray(gs.chased) ? gs.chased : []), { path: why.note.path, at: now }].slice(-30) })
    }
    if (why.reason === 'patrol') saveGearState({ ...loadGearState(), patrolAt: now, ...(why.note?.slot ? { patrolSlot: why.note.slot } : {}) })
    if (why.reason === 'plan-draft') saveGearState({ ...loadGearState(), planDraftAt: now })
    if (why.reason === 'drive' && why.note?.key) {
      const d = loadDrive()
      saveDrive({ ...d, memo: { ...(d.memo || {}), [why.note.key]: now } })
      record({ state: 'drive', at: now, kind: why.note.kind, mode: why.note.mode, role: why.role })
    }
    if (why.mark) saveGemini({ ...loadGemini(), ...why.mark })
  }
  /**
   * 今天的局计划还是代码出的空壳（没有一局带工作包）、也还没生效：该鲸澜来填了。
   * 一小时最多来一次，她填过（任何一局有工作包）就不再来。
   */
  function planDraftDue(now, tz) {
    const p = planOf(readState(stateFile('plan.json'), null), startOfDay(now, tz))
    const min = zoneMinuteOfDay(now, tz)
    // 只看还没到点的局：时段都过了，填了也开不了。
    const open = p.items.filter((i) => !i.openedAt && !i.missed && Number(i.minute) > min)
    if (!open.length || p.accepted) return false
    if (open.some((i) => i.packet)) return false
    return now - (Number(loadGearState().planDraftAt) || 0) >= 60 * 60_000
  }
  /** 两姐妹共用的信用分（从账本算，见 trust.js）。2026-11-19 H2：破窗不再扣信用分。 */
  const trustWeights = () => ({
    breakglass: 0,
    'followup-timeout': -(Number(config.followupPenalty) || 5),
  })
  const trustNow = (now = Date.now()) => trustScore(readLedger(home(), 800), now, { weights: trustWeights() })
  /** 放风中：娱乐临时解锁（盖过拒绝模式和任务模式），到点自动恢复。 */
  const onBreak = (now) => Number(config.breakUntil) > now
  /** 拒绝到什么时候：拒绝模式（含忙时）和正在进行的 Gemini 连麦取更晚的。 */
  function refuseUntilAt(now) {
    const g = loadGemini()
    const session = g.session && Number(g.session.until) > now ? Number(g.session.until) : 0
    const r = Number(config.refuseUntil) > now ? Number(config.refuseUntil) : 0
    // 任务到点还没验收：继续锁（每次往后顺 10 分钟），直到交墙验收通过（submit_to_wall review）或者兜底两小时。
    const pending = aiTask(config, now) ? Math.max(Number(config.gearUntil) || 0, now + 10 * 60_000) : 0
    if (onBreak(now)) return 0
    // 作息：早上没说今天的 3 件事、晚上没对完账——滚动锁；深夜锁到早上 7 点。
    const rh = lastRhythm
    const gate = rh && (rh.morningGate || rh.reviewGate) ? now + ROLL_MS : 0
    const night = rh && rh.night === 'lock' && Number(rh.nightUntil) > now ? Number(rh.nightUntil) : 0
    return Math.max(r, session, pending, gate, night)
  }
  /**
   * 本地规则快照（P0 C2）：现在生效的每一条锁，各带自己的到期时间，一起下发给手机。
   * 手机把它存在本地；引擎死了就只按这份执行，到点按手机本地时间解除，绝不自己加新规则。
   * 和 refuseUntilAt 是同一套规则，只是不合成一个数：早晚两道门这里给的是门本身的封顶时间
   * （而不是 3 分钟滚动），这样引擎挂了门也按时关、不会锁死。
   * kind 的先后由手机端 LockPriority 仲裁（晚间对账 > 任务 > 早上 > 深夜 > 拒绝）。
   */
  /** 晚上对账通过：她们立的任务（任务模式、她们开的拒绝模式）记一笔"对账时清算"，一起解锁。 */
  function settleTasksAtReview(now) {
    const task = aiTask(config, now)
    // 第三轮：他点「开始」的冲刺也算，验收/对账过了就一起收。
    const sprint = Number(config.sprintUntil) > 0
    const aiRefuse = config.refuseBy === 'ai' && Number(config.refuseUntil) > now
    if (!task && !sprint && !aiRefuse) return false
    const goal = config.taskGoal
    if (task || sprint) {
      setGear({ gear: 'free' }, 'review')
      saveGearState({ ...loadGearState(), doneFor: Number(config.gearUntil) })
    }
    if (config.refuseBy === 'ai' || (config.refuseBy === 'gear' && (task || sprint))) {
      config = { ...config, refuseUntil: 0, refuseBy: undefined }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
    }
    record({ state: 'task-settled', at: now, by: 'review', goal })
    return true
  }
  // ---- 第三轮：追问锁 / 裁决 / 全天状态 ---------------------------------------------------------
  //
  // 门不再按字数关：节奏只负责"该不该开门"，关不关由姐妹的裁决（满意）或超时决定。
  // 墙按状态机执行；过关由墙按证据判（submit_to_wall，见 wall_auditor.js）。

  /** 改配置并落盘；落盘失败也照样在内存里生效（这一轮不能因为写失败就反悔）。 */
  function commit(patch) {
    config = { ...config, ...patch }
    try {
      syncLockBusPatch(patch, Date.now())
    } catch {}
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    return config
  }

  /** 开追问锁。同一时刻只有一把；同原因重复开不续命（30 分钟上限绕不过去）。 */
  function openFollowup(reason, question, now = Date.now()) {
    const lock = openLock(config.followup, {
      reason,
      question,
      now,
      capMs: Math.max(60_000, Math.round(Number(config.followupCapMin) || 30) * 60_000),
    })
    commit({ followup: lock })
    record({ state: 'followup-open', at: now, reason: lock.reason, question: lock.question, until: lock.until })
    return lock
  }

  // ---------------------------------------------------------------- 2026-11-20 追问改道 Gemini
  //
  // Gemini 是常驻的高互动大脑：沟通比追问锁好，约束也不弱（要回执）。约束弱、问得模糊的那几处追问
  // （局里用了没声明的 App、半天没动、违抗、打开被锁的 App 再问）先拉 Gemini 带同一个问题；
  // 10 分钟内拿到完整回执就算答了；拉不起来（闸门不让 / 深夜 / 紧急 / 当天用满）或 10 分钟没回执，才落原来的追问锁。
  // 他自己点「申请验收」、冲刺到点验收不走这里——那是验收流程本身，锁照旧。

  /** 追问改道排着的那一次拉起（/plan 的拉起段取走）。 */
  let pendingFollowupPull = null
  const FOLLOWUP_GEMINI_MS = 10 * 60_000
  const FOLLOWUP_GEMINI_MODE = { defiance: 'interrogate', standby: 'interrogate', 'locked-app': 'interrogate', capability: 'think', gemini: 'receipt' }

  function askViaGemini(reason, question, now = Date.now()) {
    if (config.followupGemini) return { via: 'pending', lock: null }
    if (config.followup && Number(config.followup.until) > now) return { via: 'lock', lock: config.followup }
    const tz = zoneOr(config.timeZone)
    const mode = FOLLOWUP_GEMINI_MODE[reason] || 'interrogate'
    let gate = { allowed: false }
    let brief = ''
    try {
      brief = v4Hub.buildProactiveGeminiBrief({ topic: question, mode, minutes: 10, now })
      gate = requestGeminiViaGate({ source: 'followup', mode: 'auto', topic: question, minutes: 10, geminiMode: mode, brief, now, tz })
    } catch {
      gate = { allowed: false }
    }
    if (!gate.allowed) return { via: 'lock', lock: openFollowup(reason, question, now) }
    commit({ followupGemini: { reason, question, since: now, until: now + FOLLOWUP_GEMINI_MS } })
    pendingFollowupPull = {
      triggered: true, at: now, topic: question, mode: v4Hub.getPullMode(now), reason: 'followup-' + reason,
      preferVoice: true, brief: gate.brief || brief,
    }
    record({ state: 'followup-gemini', at: now, reason, question })
    return { via: 'gemini', lock: null }
  }

  /** Gemini 追问的结局（每次 /plan 兜一次）：拿到完整回执 → 结；10 分钟没回执 → 落原来的追问锁。 */
  function tickFollowupGemini(now) {
    const fg = config.followupGemini
    if (!fg) return
    const answered = readLedger(home(), 200).some((e) => e?.state === 'gemini-recap' && e.receipt === true && Number(e.at) >= Number(fg.since))
    if (answered) {
      commit({ followupGemini: null })
      record({ state: 'followup-gemini-answered', at: now, reason: fg.reason })
      return
    }
    if (now >= Number(fg.until)) {
      commit({ followupGemini: null })
      record({ state: 'followup-gemini-fallback', at: now, reason: fg.reason })
      openFollowup(fg.reason, fg.question, now)
    }
  }

  /**
   * 追问锁到点还没裁决：结束拦截、记账扣分、把没过关的事挂成义务（义务只由用户的动作触发再问）。
   * 手机报 followup-timeout 和每次 /plan 都会兜一次，谁先到都算；第二次是空的。
   */
  function expireFollowup(now) {
    const lock = config.followup
    if (!lockExpired(lock, now)) return false
    const ob = obligationOf(lock.reason, now)
    commit({
      followup: closeLock(lock, { now, by: 'timeout' }),
      obligations: addObligation(config.obligations, ob),
      // 晚间对账没过：今晚的申请窗口直接作废（任务书那张表）。
      ...(ob.kind === 'evening' ? { applyWindow: { ...(config.applyWindow || {}), used: true, closeAt: now } } : {}),
    })
    record({ state: 'followup-timeout', at: now, reason: lock.reason, question: lock.question, obligation: ob.kind })
    return true
  }

  /** 这一刻的全天状态（C2）：主状态只有一项，追问锁是叠在上面的临时层。 */
  function stateView(now, tz, phone) {
    // 计划模态：machine.js 一直有这一层（priority 95），但这里从来没传过 plan，
    // 于是「计划锁」永远不进——L1 的「直接锁 + 进计划模态」也就落不下去。
    // 现在只认升级压下来的那一份（planUntil），没升级就不主动进模态。
    const doc = loadLadderDoc()
    return resolveState({
      now,
      tz,
      min: localMinute(phone, zoneMinuteOfDay(now, tz)),
      config,
      rhythm: lastRhythm || {},
      followup: followupView(config.followup, now),
      session: geminiSessionView(now),
      plan: doc.planUntil > now ? { active: true, until: doc.planUntil, reason: '升级' } : null,
    })
  }

  /**
   * 一次裁决落地（C3）。满意才收；不满意只记账、锁继续挂着，到点按超时处理。
   * 记账形状对齐 DeepSeek 的 D3：{state:'verdict', kind, satisfied, summary, nextStep, tasks, longTerm}。
   */
  function applyVerdict(v, now, by) {
    record({
      state: 'verdict', at: now, by, kind: v.kind, satisfied: v.satisfied,
      summary: v.summary || undefined, nextStep: v.nextStep || undefined,
      tasks: v.tasks, longTerm: v.longTerm,
    })
    // 过关了这条义务就消掉；没过得留着（超时会重新挂）。
    if (v.satisfied) commit({ obligations: clearObligation(config.obligations, v.kind) })
    if (!v.satisfied) return { ok: true, text: '记下了：这轮不算过。锁还在，说清它什么时候解决。' }
    const patch = { followup: closeLock(config.followup, { now, by: 'verdict' }) }
    if (v.kind === 'morning' && v.tasks) {
      // 晨间立项的任务进 workStore 并镜像当日文件（晚上对账按完成物逐项找证据）。
      const tz = zoneOr(config.timeZone)
      const state = loadDaily(loadDailyState(), now, tz)
      const next = { ...state, tasks: v.tasks, review: state.review }
      const wrote = writeDaily(kbDir(notesDir(), home()), next, {}, fourNumbers())
      if (wrote.ok) patch.daily = { day: state.day, tasks: v.tasks.length, at: now }
      saveDailyState(next)
      try {
        const wsCur = workStoreNow(now, tz).store
        const wsNext = replaceWorkSource(wsCur, 'daily', v.tasks.map((t) => ({
          id: t.id,
          source: 'daily',
          title: t.task,
          due: t.due,
          minutes: t.minutes,
          dept: t.dept,
          evidence: t.evidence,
          packet: { goal: t.task, deliverable: t.evidence },
          status: 'pending',
        })), { day: dayString(now, tz), now })
        writeJson(stateFile('work.json'), { ...wsNext, migratedAt: wsNext.migratedAt || now, updatedAt: now })
      } catch {}
    }
    if (v.kind === 'review' || v.kind === 'morning') {
      // 验收/立项过了：放风（复用第二轮那段 breakUntil 语义，放风期间不下发任何锁）。
      const mins = Math.max(5, Math.round(Number(config.breakMin) || 20))
      Object.assign(patch, { breakUntil: now + mins * 60_000, breakSince: now, breakChecked: false })
      settleTasksAtReview(now)
    }
    if (v.kind === 'evening') {
      // 晚间对账过了：按当日文件对账，产出明天的放风额度；任务和冲刺一并清算（C5：对完账一起解除）。
      settleTasksAtReview(now)
      if (Number(config.sprintUntil) > 0) Object.assign(patch, { sprintUntil: 0 })
      const tz = zoneOr(config.timeZone)
      const state = loadDaily(loadDailyState(), now, tz)
      if (state.tasks.length) {
        const out = reconcile(state, {
          now, tz, notesDir: notesDir(), ledger: readLedger(home(), 800),
          usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [],
          check: wallCheck(now),
        })
        const next = { ...state, review: { at: now, ...out } }
        writeDaily(kbDir(notesDir(), home()), next, {}, fourNumbers())
        saveDailyState(next)
        Object.assign(patch, {
          daily: { day: state.day, done: out.done, partial: out.partial, miss: out.miss, total: out.total, at: now },
          dailyQuota: { for: startOfDay(now, tz) + 86_400_000, delta: out.quotaDelta, done: out.done, total: out.total, at: now },
        })
      }
    }
    if (v.kind === 'review' || v.kind === 'morning') {
      if (Number(config.sprintUntil) > 0) Object.assign(patch, { sprintUntil: 0 })
    }
    commit(patch)
    // 门由裁决关：writeConfig 之后写节奏状态（closeGate 回的是 {state, closed}）。
    const rh = lastRhythm
    if (rh?.morningGate && v.kind === 'morning') saveRhythm(closeGate(rh.state, 'morning').state)
    if (rh?.reviewGate && (v.kind === 'review' || v.kind === 'evening')) saveRhythm(closeGate(rh.state, 'review').state)
    return { ok: true, text: '过了。' + (patch.breakUntil ? '放风到 ' + new Date(patch.breakUntil).toTimeString().slice(0, 5) + '，到点回来。' : '') }
  }

  function rulesSnapshot(now, tz = zoneOr(config.timeZone)) {
    const rules = []
    const block = {}
    if (onBreak(now)) return { v: 1, at: now, rules, block }
    const push = (kind, until, why) => {
      const u = Math.round(Number(until) || 0)
      if (u > now) rules.push({ kind, until: u, why })
    }
    // 墙：默认严格的时间表锁（白天非吃饭时段 default，夜里 night）。深夜锁这里不重复推（下面 rhythm 的 night 管），
    // 只推白天的默认锁；手机端 LockPriority 里 default 排在普通之上、拒绝之下。
    const spendPassActive = Boolean(config.spendPass && Number(config.spendPass.until) > now)
    const wd = wallDecision(wallCfgNow(), now, tz)
    if (wd.locked && wd.kind === WALL_KIND_DEFAULT && !earnedOpen(now, tz) && !spendPassActive) {
      push(WALL_KIND_DEFAULT, wd.until, wd.why)
    }
    // 2026-11-21 晚间娱乐上限用完：娱乐 / 游戏锁到明早 7:00（余额再多也不开；Gemini 常驻不锁）。
    try {
      const evUntil = eveningLockUntil(now, tz)
      if (evUntil) push(WALL_KIND_DEFAULT, evUntil, '今晚的娱乐 ' + eveningNow(now, tz).cap + ' 分钟用完了：锁到明早 7 点。想说话就开 Gemini 聊聊今天、把明天封了。')
    } catch {}
    // 追问锁：门开着就不放——回复敷衍时窗口不消失，到点由插件自己结束。
    if (lockActive(config.followup, now)) push('followup', Number(config.followup.until), '追问锁：把这一个问题说清楚')
    const rh = lastRhythm
    const st = rh?.state || {}
    if (rh?.reviewGate) push('review', Number(st.reviewSince) + REVIEW_CAP_MS, '晚上对账：和鲸澜对完今天的账才解锁')
    // 冲刺（用户点「开始」）和她们立的任务都按 task 下发：到点就是结束。
    const sprinting = Number(config.sprintUntil) > now
    const task = aiTask(config, now) || sprinting
    if (task) {
      const goal = config.taskGoal ? '（' + String(config.taskGoal).slice(0, 40) + '）' : config.gearWhat ? '（' + String(config.gearWhat).slice(0, 40) + '）' : ''
      // 没到点：锁到任务结束；到点还没验收：往后顺 10 分钟（引擎在就一直顺，引擎死了 10 分钟后自己解）。
      push('task', Number(config.gearUntil) > now ? Number(config.gearUntil) : now + 10 * 60_000, '任务模式' + goal + '：验收合格才解锁')
    }
    if (rh?.morningGate) push('morning', Number(st.morningSince) + MORNING_CAP_MS, '早上对线：先说出今天最重要的 3 件事')
    if (rh?.night === 'lock') push('night', rh.nightUntil, '深夜锁：娱乐锁到早上 7 点')
    if (Number(config.refuseUntil) > now) {
      const by = config.refuseBy
      if (by === 'ai' || (by === 'gear' && task)) push('task', config.refuseUntil, '她们立的任务：验收合格才解锁')
      else push('refuse', config.refuseUntil, by === 'gear' ? '忙时：到点自动解锁' : '拒绝模式：到点自动解锁')
    }
    for (const b of Array.isArray(config.blockExtra) ? config.blockExtra : []) {
      if (b && typeof b.app === 'string' && Number(b.until) > now && !NEVER_BLOCK.includes(b.app)) block[b.app] = Math.round(Number(b.until))
    }
    // 外接 APP：他声明成娱乐的那些，手机自己的拦截清单里没有，只能从这里下发（block 是「包名 → 到点」）。
    // 只发他声明的——内置那批手机已经认识，重复发只会把 Kotlin 那边 50 个名额占满。
    const untilLock = rules.reduce((m, r) => Math.max(m, r.until), 0)
    const declared = new Set(declaredFun(appsOf()))
    if (untilLock > now && declared.size) {
      // blockDuringLock 已经减掉了紧急应用和舱内放行的包，这里只挑手机不认识的那些。
      const toBlock = blockDuringLock(appsOf(), { funLocked: true, allow: sessionNow(now)?.allow || [] })
      for (const pkg of toBlock) if (declared.has(pkg) && !NEVER_BLOCK.includes(pkg)) block[pkg] = untilLock
    }
    return { v: 1, at: now, rules, block }
  }

  // ---- 第二轮·墙：放松申请 / 收紧 / 本周额度 ---------------------------------------------------
  //
  // 底线 1：任何对话都不能当场放松规则。放松只能在对账后的申请窗口里提，而且只管明天一天。
  // 收紧任何时候都能提，立即生效、当天有效。两者都记进账本；额度按自然周算。

  /** 本周放松是否还有额度（按目标日所在的自然周；含昨晚对账挣到 / 扣掉的）。 */
  function relaxQuotaLeft(targetDay, now, tz) {
    const week = weekKey(targetDay, tz)
    const used = config.relaxWeek && Number(config.relaxWeek.week) === week ? Math.max(0, Math.round(Number(config.relaxWeek.used) || 0)) : 0
    // 对账的额度增减只作用于被裁定的那一天，所以按目标日算，而不是按 now。
    const bonus = relaxBonus(config, targetDay, tz)
    const left = Math.max(0, wallConfig(config).weeklyQuota + bonus - used)
    return { left, week, used }
  }

  /**
   * 申请放松：只在申请窗口开着时能提，只能给明天开一段（默认 wall.relaxSegmentMin 分钟），每周额度内。
   * 这是替代第一轮 grant_break 的唯一放松通道——聊天里没有别的按钮能解锁（底线 1）。
   */
  function requestRelax(now, tz, { atHour, minutes, reason, by } = {}) {
    if (!applyWindowOpen(config.applyWindow, now)) {
      return { ok: false, code: 'no-window', text: '现在不是申请窗口。放松只能在今晚对账后、窗口开着的 15 分钟里提，而且只管明天。' }
    }
    if (config.applyWindow && config.applyWindow.used) {
      return { ok: false, code: 'window-used', text: '这个窗口已经提过一次申请了，明天再说。' }
    }
    // 第五轮·硬约束：四部门当天没交齐就没有放风额度。这里是唯一的批放松口子，
    // 所以挡在这里，而不是只把界面上的数字改成 0（那只是好看）。
    if (!completeOf(depositToday(now))) {
      return { ok: false, code: 'deposit', text: '今天四段部门汇报还没交齐（信息收集、谋划思考、行为管理、统筹规划），交齐之前没有放风额度。' }
    }
    if (funBlockedBy(config.obligations)) {
      return { ok: false, code: 'obligation', text: '今天还压着一件没补完的立项：先把完成物拿出来，再说放松。' }
    }
    const targetDay = dayKey(now, tz) + 86_400_000 // 明天
    const q = relaxQuotaLeft(targetDay, now, tz)
    if (q.left <= 0) {
      return { ok: false, code: 'quota', text: '这周的放松额度用完了（每周最多 ' + wallConfig(config).weeklyQuota + ' 天）。窗口还能用来收紧，但不能再放松。' }
    }
    const text = String(reason || '').trim().slice(0, 200)
    if (!text) return { ok: false, code: 'no-reason', text: '放松要写清理由（为什么明天想多玩一段）。' }
    const w = wallConfig(config)
    const span = Math.max(15, Math.min(240, Math.round(Number(minutes) || w.relaxSegmentMin)))
    const startHour = Math.max(0, Math.min(23, Math.round(Number(atHour))))
    const from = Number.isFinite(startHour) ? startHour * 60 : 20 * 60 // 默认晚上 20:00 那一段
    const to = Math.min(1440, from + span)
    config = {
      ...config,
      relax: { for: targetDay, segments: [{ from, to }], reason: text },
      relaxWeek: { week: q.week, used: q.used + 1 },
      applyWindow: { ...(config.applyWindow || {}), used: true },
    }
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    const hm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
    record({ state: 'relax-grant', at: now, for: targetDay, from, to, minutes: span, reason: text, by: String(by || 'ai').slice(0, 20) })
    return { ok: true, text: '批了：明天 ' + hm(from) + '–' + hm(to) + ' 开放娱乐一段。本周还剩 ' + (q.left - 1) + ' 天额度。今天的规则照旧。' }
  }

  /** 收紧：任何时候可提，立即生效、当天有效，次日复归。 */
  function tightenNow(now, tz, { reason, by } = {}) {
    const today = dayKey(now, tz)
    config = { ...config, tighten: { for: today, at: now }, relax: config.relax && Number(config.relax.for) === today ? null : config.relax }
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    record({ state: 'tighten', at: now, for: today, reason: String(reason || '').slice(0, 200), by: String(by || 'ai').slice(0, 20) })
    return { ok: true, text: '收紧了：今天娱乐锁到零点，明天恢复默认。' }
  }
  // 第十轮：重罚与黑历史账本（quotaDebtMin 扣除自由放风配额；leaseCapMin 压缩白名单租约上限；highRiskHours 收紧次日高危时段阈值）
  function loadPenalty(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const day = dayString(now, tz)
    const raw = readState(stateFile('penalty.json'), null) || {}
    const highRiskHours = Array.isArray(raw.highRiskHours)
      ? raw.highRiskHours.map(Number).filter((h) => Number.isInteger(h) && h >= 0 && h < 24).slice(-6)
      : []
    if (raw.day && raw.day !== day) {
      const carryDebt = Math.min(60, Math.round((Number(raw.quotaDebtMin) || 0) * 0.5))
      const rolled = {
        v: 1,
        day,
        quotaDebtMin: carryDebt,
        leaseCapMin: carryDebt > 0 ? Math.min(30, Number(raw.leaseCapMin) || 30) : 60,
        highRiskHours,
        breachesToday: 0,
        lastBreachAt: Number(raw.lastBreachAt) || 0,
      }
      writeJson(stateFile('penalty.json'), rolled)
      return rolled
    }
    return {
      v: 1,
      day,
      quotaDebtMin: Math.max(0, Math.min(180, Math.round(Number(raw.quotaDebtMin) || 0))),
      leaseCapMin: Math.max(10, Math.min(60, Math.round(Number(raw.leaseCapMin) || 60))),
      highRiskHours,
      breachesToday: Math.max(0, Math.round(Number(raw.breachesToday) || 0)),
      lastBreachAt: Number(raw.lastBreachAt) || 0,
    }
  }
  function savePenalty(p) {
    writeJson(stateFile('penalty.json'), p)
  }
  function recordBreachPenalty(severity = 'moderate', now = Date.now(), tz = zoneOr(config.timeZone)) {
    const p = loadPenalty(now, tz)
    const addDebt = severity === 'severe' ? 30 : 15
    const nextCap = severity === 'severe' ? 15 : Math.min(p.leaseCapMin, 20)
    const hour = Math.floor(zoneMinuteOfDay(now, tz) / 60)
    const nextHours = [...p.highRiskHours.filter((h) => h !== hour), hour].slice(-6)
    const next = {
      ...p,
      quotaDebtMin: Math.min(180, p.quotaDebtMin + addDebt),
      leaseCapMin: nextCap,
      highRiskHours: nextHours,
      breachesToday: p.breachesToday + 1,
      lastBreachAt: now,
    }
    savePenalty(next)
    try {
      recordTimeEntryNow({ kind: 'penalty', minutes: addDebt, reason: severity, at: now, tz })
      const curEarned = todayEarned(now, tz)
      if (Number(curEarned.earned) > Number(curEarned.spent)) {
        const spentRes = earnedRt.spend(curEarned, addDebt)
        saveEarned(spentRes.state)
      }
    } catch {
      // ignore earned deduction error
    }
    record({ state: 'penalty-breach', at: now, severity, addDebt, quotaDebtMin: next.quotaDebtMin, leaseCapMin: next.leaseCapMin, hour })
    return next
  }
  function reliefPenaltyOnPass(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const p = loadPenalty(now, tz)
    if (p.quotaDebtMin <= 0 && p.leaseCapMin >= 60) return p
    const next = {
      ...p,
      quotaDebtMin: Math.max(0, p.quotaDebtMin - 10),
      leaseCapMin: Math.min(60, p.leaseCapMin + 15),
    }
    savePenalty(next)
    return next
  }
  function usageReportView(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const p = loadPenalty(now, tz)
    const phone = lastPhone
    const today = Array.isArray(phone?.today) ? phone.today : []
    const totalMinutes = Number(phone?.totalMinutes) || today.reduce((s, t) => s + (Number(t.minutes) || 0), 0)
    const funMinutes = funToday(phone)
    const timeSlots = Array.isArray(phone?.timeSlots) ? phone.timeSlots : []
    const lease = config.taskLease && Number(config.taskLease.until) > now ? config.taskLease : null
    return {
      v: 1,
      ok: true,
      at: now,
      local: phone?.local || '',
      totalMinutes,
      funMinutes,
      front: phone?.app ? { app: phone.app, label: phone.label || phone.app, minutes: phone.minutes || 0, fun: phone.fun === true } : null,
      today,
      timeSlots,
      switches: phone?.switches ? { in90s: phone.switches.in90s, in15m: phone.switches.in15m, distinct15m: phone.switches.distinct15m, in60m: phone.switches.in60m } : null,
      penalty: p,
      activeLease: lease,
    }
  }

  const toolOutput = {
    schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
    render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
  }
  v4Hub = createV4Hub({
    stateFile,
    reviewCards: {
      next: (now) => lockscreenCard(now),
      get: (id) => cleanPool(loadReviewCards(), Date.now()).items.find((x) => x.id === id) || null,
      rate: (id, rating, channel, now) => rateReviewCardNow(id, rating, channel, now),
    },
    readState,
    writeJson,
    home,
    notesDir,
    kbDir,
    getConfig: () => config,
    getLastPhone: () => lastPhone,
    getLastRolledDice: () => lastRolledDice,
    appsOf,
    emergencyPackages,
    usageReportView,
    readLedger,
    record,
    boutsGetRoute: () => boutsGetRoute(),
    planDayRoute: (b) => planDayRoute(b),
    handoffRoute: (b) => handoffRoute(b),
    dayString,
    zoneOr,
    presetRt,
    grantTempPass: (until, reason) => {
      const cur = loadExternal()
      saveExternal({
        ...cur,
        emergencyPass: {
          active: true,
          grantedAt: Date.now(),
          until,
          reason: String(reason || '刷题解锁').slice(0, 60),
          day: dayString(Date.now(), zoneOr(config.timeZone)),
          countToday: Number(cur?.emergencyPass?.countToday) || 0,
        },
      })
    },
    toolOutput,
    loadEpisodes: () => readState(stateFile('episodes.json'), null),
    loadGemini,
    saveGemini,
    loadExternal,
    loadDailyToday: (now) => dailyToday(now),
    getGeminiSession: (now) => geminiSessionView(now),
    getAiWorkSession: (now) => aiWorkSessionView(now),
    getTaskLease: (now) => taskLeaseView(now),
    getWallDecision: (now = Date.now()) => {
      const tz = zoneOr(config.timeZone)
      const wd = wallDecision(wallCfgNow(), now, tz)
      const spendUntil = Number(config.spendPass?.until) || 0
      if (spendUntil > now && wd.locked && wd.kind !== 'night') {
        return { ...wd, locked: false, kind: null, openUntil: Math.max(Number(wd.openUntil) || 0, spendUntil) }
      }
      return wd
    },
    getSpeaker: () => (nextSpeaker?.role === 'strategist' ? 'strategist' : 'companion'),
    getCommitments: (now) => readCommitments(home(), now, 3).map((c) => c.statement),
    getGeminiExtras: (now, dice) => geminiExtras(now, dice),
    getPersonaAbout: (now) => personaLines(mergePersona(readCorePersona(home()), config.persona), effectiveMode(config, now)).slice(0, 5),
    getGearWhat: () => (effectiveGear(config, Date.now()) === 'busy' ? config.gearWhat : ''),
    getAppLedger: (now) => appLedgerLines(now),
    getQuotaLine: () => (lastQuota ? lastQuota.line : ''),
    getIntensityInfo: (now, autonomy) => intensityStateNow(now, zoneOr(config.timeZone), autonomy),
    getPassLedgerSummary: (now = Date.now()) => {
      const tz = zoneOr(config.timeZone)
      const info = intensityStateNow(now, tz)
      return passLedgerSummary(loadPassLedger(now, tz), {
        day: dayString(now, tz),
        cap: info.emergencyDisarmDailyCap,
        now,
      })
    },
    getTimeLedger: (now = Date.now()) => timeLedgerNow(now, zoneOr(config.timeZone), lastPhone),
    getAvailableFunLeft: (now = Date.now()) => Number(timeLedgerNow(now, zoneOr(config.timeZone), lastPhone).funLeft) || 0,
    getSpendGateStatus: (now = Date.now()) => spendGateStatus(now, zoneOr(config.timeZone)),
    getBreakPass: (now = Date.now()) => {
      if (!onBreak(now)) return null
      const wdNow = wallDecision(wallCfgNow(), now, zoneOr(config.timeZone))
      if (wdNow.locked && wdNow.kind === 'night') return null
      return {
        active: true,
        until: Number(config.breakUntil) || 0,
        by: String(config.breakBy || 'sister'),
        reason: String(config.breakReason || '双鱼审核放风中'),
      }
    },
    getEmergencyPassActive: (now = Date.now()) => activeEmergencyInfo(now, zoneOr(config.timeZone)),
    getWorkStore: (now = Date.now()) => workStoreNow(now, zoneOr(config.timeZone)),
    tierOf: (pkg) => tierFnNow()(pkg),
    getSealSession: (now) => sealSessionView(now),
    getVerifySession: (now) => verifySessionView(now),
    getSealLines: (now) => {
      const tz = zoneOr(config.timeZone)
      let vLines = []
      try {
        const vv = verifyView(now, tz)
        vLines = verifyPromptLines({
          verify: { active: vv.active }, recovery: { active: vv.recovery },
          spend: vv.spend ? zoneHm(vv.spend.until, tz) : '', untilHm: vv.untilMinute != null ? sealHm(vv.untilMinute) : '',
        })
        if (vv.evening?.active) {
          vLines.push(vv.evening.hit
            ? '今晚的娱乐 ' + vv.evening.cap + ' 分钟用完了（锁到明早 7 点）：他求情不松，余额再多也不开；陪他聊、复盘今天、封明天，或者叫他开 Gemini。'
            : '今晚娱乐已用 ' + vv.evening.used + '/' + vv.evening.cap + ' 分钟（18:00 起算，用完锁到明早 7 点）。')
        }
      } catch {}
      return [...vLines, ...sealPromptLines(loadSeals(now, tz), { today: dayString(now, tz), hm: sealHm }), ...reviewPromptLines(loadWeekReviews(), now), ...reviewQuizLines(now)]
    },
    upsertWorkItemNow: (item, { now = Date.now(), mirror = true } = {}) => {
      const tz = zoneOr(config.timeZone)
      const cur = workStoreNow(now, tz).store
      const res = upsertWorkItem(cur, item, { day: dayString(now, tz), now })
      if (res.ok) {
        if (mirror) mirrorWorkStoreToFiles(res.state, now, tz)
        else writeJson(stateFile('work.json'), res.state)
      }
      return res
    },
    markWorkStatusNow: ({ id = '', title = '', status = 'done', now = Date.now() } = {}) => {
      const tz = zoneOr(config.timeZone)
      const cur = workStoreNow(now, tz).store
      const res = markWorkStatus(cur, { id, title, status, day: dayString(now, tz), now })
      if (res.ok) mirrorWorkStoreToFiles(res.state, now, tz)
      return res
    },
    getArbiterEnforce: () => config.arbiterEnforce !== false,
    getGeminiAccounting: (now = Date.now(), tz = zoneOr(config.timeZone)) => geminiAccountingNow(now, tz),
    getLockRequests: (now = Date.now()) => lockBus.list(now),
    getTodayBrainStats: (now = Date.now()) => brainRt.view(now),
    onContractClear: (now = Date.now()) => { lockBus.revokeBySource('contract', { now }) },
    getSealsNow: (now = Date.now()) => {
      try {
        return loadSeals(now, zoneOr(config.timeZone))?.items || []
      } catch {
        return []
      }
    },
    onDayPlanMutated: (now = Date.now()) => {
      dayPlanWatchUntil = Math.max(dayPlanWatchUntil, Number(now) + 120_000)
    },
    onDayBlockPassed: ({ block, rewardMin, now = Date.now() } = {}) => {
      const tzNow = zoneOr(config.timeZone)
      const mins = Math.max(0, Math.round(Number(rewardMin) || 0))
      if (mins > 0) {
        recordTimeEntryNow({
          kind: 'earned_seal',
          minutes: mins,
          reason: `计划验收通过「${block?.title || '验收块'}」`,
          at: now,
          tz: tzNow,
        })
      }
      record({ state: 'dayblock-passed', at: now, id: block?.id || '', title: block?.title || '', minutes: mins })
    },
    onBankUnlock: ({ minutes, solved, until, now = Date.now() } = {}) => {
      const BANK_EARN_DAILY_CAP = 60
      const tz = zoneOr(config.timeZone)
      const day = dayString(now, tz)
      const mins = Math.max(1, Math.round(Number(minutes) || 5))
      const tlState = loadTimeLedger(now, tz)
      const todayBankEarned = tlState.entries
        .filter((e) => e.kind === 'earned_bank' && (!e.day || e.day === day))
        .reduce((s, e) => s + (Number(e.minutes) || 0), 0)
      const remain = Math.max(0, BANK_EARN_DAILY_CAP - todayBankEarned)
      const added = Math.min(mins, remain)
      if (added > 0) {
        recordTimeEntryNow({ kind: 'earned_bank', minutes: added, reason: `题库挣余额 ${solved || 10} 题`, at: now, tz })
      }
      const tlAfter = timeLedgerNow(now, tz, lastPhone)
      const gate = spendGateStatus(now, tz)
      return {
        earnedOnly: true,
        needSpend: true,
        earnedAdded: added,
        dailyBankEarned: todayBankEarned + added,
        dailyBankCap: BANK_EARN_DAILY_CAP,
        capped: added < mins,
        availableFunLeft: Number(tlAfter.funLeft) || 0,
        spendBlockedReason: gate.spendBlockedReason,
        daytimeRem: gate.daytimeRem,
      }
    },
    requestGeminiGate: (opts) => requestGeminiViaGate(opts),
    snoozeGeminiAutoNow: (minutes = 30, now = Date.now()) => {
      const tz = zoneOr(config.timeZone)
      const next = snoozeGeminiAuto(loadGeminiGate(now, tz), minutes, now, tz)
      if (next && typeof next === 'object') saveGeminiGate(next)
      return next
    },
  })
  function intensityTightenTool() {
    return {
      name: 'intensity_tighten',
      description: '把强度档往严里调（只能改严：gentle -> standard -> strict，不能改松；改松请让用户在伴侣中枢切，次日生效）。',
      parameters: {
        type: 'object',
        properties: {
          tier: { type: 'string', enum: ['gentle', 'standard', 'strict'], description: '目标强度档' },
          reason: { type: 'string', description: '为什么要收紧' },
        },
        required: ['tier'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const r = applyIntensityChange(config, args.tier, { now, tz, allowRelax: false })
        if (!r.ok) {
          return { ok: false, text: '只能收紧强度档，不能在聊天里改松；改松请到伴侣中枢操作（次日生效）。' }
        }
        config = { ...config, intensity: r.intensity, pendingIntensity: r.pendingIntensity, intensityExplicit: true }
        try { writeConfig(config) } catch {}
        record({ state: 'intensity-tighten', at: now, tier: r.intensity, applied: r.applied, reason: String(args.reason || '').slice(0, 120) })
        return {
          ok: true,
          text: r.applied === 'immediate'
            ? `强度档已立即收紧为「${r.intensity}」。`
            : `当前已经是「${r.intensity}」档，无需变更。`,
        }
      },
    }
  }
  if (tools && typeof tools.register === 'function') {
    for (const def of [
      dispatchControlTool('global'),
      clockManageTool('global'),
      grantTaskLeaseTool('strategist'),
      appTierTool(),
      intensityTightenTool(),
      showCardTool('global'),
      declareIntentTool('global'),
      appUsageReportTool('global'),
      rollSixDiceTool('global'),
      exportCognitiveHandoffTool('global'),
      addTodayTaskTool('global'),
      ...v4Hub.globalTools,
    ]) {
      try {
        const offExtra = tools.register(def)
        if (typeof offExtra === 'function') ctx.effect?.(() => offExtra)
        console.error('[companion] registered global tool ' + def.name)
      } catch (error) {
        console.error('[companion] global tool ' + def.name + ' 注册失败：' + String((error && error.message) || error))
      }
    }
  }
  /**
   * 管他（第二轮：只留收紧类）。在聊天里只能收紧，不能放松——放松只走申请窗口（底线 1）。
   *   busy / refuse / mute / strict 保留；free、refuse_off、gentle 已撤（那是聊天里切回宽松状态的口子）。
   */
  function controlTool() {
    return {
      name: 'dafeiyu_control',
      description: '切换对他的管法（只能收紧，不能放松）：busy=忙时（不闲聊、娱乐 App 一开就锁；时长默认 40 分钟、最长 60，到点只让他报到一次，不评分不扣分）；' +
        'refuse=拒绝模式 minutes 分钟（娱乐 App 一开就锁）；mute=安静 minutes 分钟；strict=严格；' +
        'strict_bout=这一局进严格模式（只剩本局声明的 App，其余立即锁，局结束或破窗才解）；' +
        'live_locks=结束测试模式，锁开始真的生效（测试模式里锁只提示不落）。他亲口要求或明确同意时才用。' +
        '放松（回到闲时、关拒绝、切回温和）这里不做：他想放松只能等对账后的申请窗口。',
      parameters: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['busy', 'refuse', 'mute', 'strict', 'strict_bout', 'live_locks'] },
          minutes: { type: 'integer', minimum: 5, maximum: 480, description: 'busy / refuse / mute 的时长（分钟）' },
          what: { type: 'string', description: 'busy：他在忙什么（学习、做饭、写报告…）' },
        },
        required: ['action'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const mins = Math.max(5, Math.min(480, Math.round(Number(args.minutes) || 0)))
        const hm = (at) => zoneHm(Number(at), zoneOr(config.timeZone))
        let text = ''
        if (args.action === 'busy') {
          // 进入忙时必须有时长：默认 40 分钟，最长 60 分钟（10 分钟起步）。
          const busyMinutes = Math.max(10, Math.min(BUSY_MAX_MIN, Math.round(Number(args.minutes) || BUSY_DEFAULT_MIN)))
          const until = now + busyMinutes * 60_000
          setGear({ gear: 'busy', until, what: String(args.what || '正事').slice(0, 20) }, 'tool')
          text = '已切到忙时（' + (args.what || '正事') + '）' + busyMinutes + ' 分钟，到 ' + hm(until) + '：不闲聊、娱乐 App 一开就锁，到点只让你报到一次。'
        } else if (args.action === 'refuse') {
          const until = now + (Number(args.minutes) ? mins : 120) * 60_000
          config = { ...config, refuseUntil: until, refuseBy: 'tool' }
          text = '拒绝模式开到 ' + hm(until) + '。'
        } else if (args.action === 'mute') {
          const until = now + (Number(args.minutes) ? mins : 60) * 60_000
          config = { ...config, muteUntil: until }
          text = '安静到 ' + hm(until) + '。'
        } else if (args.action === 'strict') {
          config = { ...config, mode: 'coach', modeUntil: 0 }
          text = '切到严格了。'
        } else if (args.action === 'live_locks') {
          config = { ...config, lockShadow: false }
          text = '测试模式已结束：从现在起锁会真的落下。想回测试模式只能在伴侣中枢里手动开。'
        } else if (args.action === 'strict_bout') {
          const r = presetRt.strictNow(now, String(args.what || ''), 'tool', args.minutes)
          if (!r.ok) return { ok: false, text: r.text }
          text = r.text
        } else {
          return { ok: false, text: '不认识的动作（这里只能收紧）。' }
        }
        if (args.action !== 'busy' && args.action !== 'free') {
          try {
            writeConfig(config)
          } catch {
            // 这一轮照样生效
          }
          record({ state: 'control', at: now, action: args.action, minutes: args.minutes || undefined })
        }
        return { ok: true, text }
      },
    }
  }
  /**
   * 姐妹主动调度工具（解禁「必须等他先要求」，支持随时按上下文或指令执行）。
   */
  function startTaskTool(role) {
    return {
      name: 'start_task_mode',
      description: '给他立一个任务并立刻收紧娱乐。你在观察或判断他该干正事时可直接动手，无需先问他同不同意。' +
        'mode=task 任务模式（到点验收，娱乐按规则锁）；mode=refuse 只开拒绝锁。' +
        'task 必带 goal、domain（领域/范围）、breakAt（休息点），缺项可补问或从笔记补全。',
      parameters: {
        type: 'object',
        properties: {
          mode: { type: 'string', enum: ['task', 'refuse'] },
          minutes: { type: 'integer', minimum: 10, maximum: 180, description: '时长，默认 45' },
          goal: { type: 'string', description: 'task：具体做什么、做到什么程度（例：周报写完第一部分 500 字）' },
          domain: { type: 'string', description: 'task：这一段涉及哪些领域/范围（例：线性代数第三章、英语阅读）' },
          breakAt: { type: 'string', description: 'task：什么时候休息（例：做满 50 分钟歇 10 分钟）' },
          reason: { type: 'string', description: '为什么现在立（看到了什么）' },
        },
        required: ['mode', 'reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const minutes = Math.max(10, Math.min(180, Math.round(Number(args.minutes) || 45)))
        const until = now + minutes * 60_000
        const reason = String(args.reason || '').trim().slice(0, 160)
        const hm = zoneHm(until, zoneOr(config.timeZone))
        if (args.mode === 'refuse') {
          config = { ...config, refuseUntil: Math.max(until, Number(config.refuseUntil) || 0), refuseBy: 'ai', breakUntil: 0, breakBy: '', breakReason: '' }
          try {
            writeConfig(config)
          } catch {
            // 这一轮照样生效
          }
          record({ state: 'task-start', at: now, mode: 'refuse', minutes, by: role, reason })
          return { ok: true, text: '拒绝模式开到 ' + hm + '，娱乐 App 一开就锁。' }
        }
        const goal = String(args.goal || '').trim().slice(0, 120)
        // 必答项：领域、休息时段、交付物缺一项就不排。先问她该问的那几句，
        // 排到现场再靠发挥，正是这一层要拦掉的东西。
        const missing = missingAnswers({ domain: args.domain, breakAt: args.breakAt, deliver: goal })
        if (missing.length) {
          record({ state: 'task-refused', at: now, by: role, missing })
          return { ok: false, code: 'field-missing', missing, text: answerPrompt(missing) }
        }
        config = { ...config, breakUntil: 0, breakBy: '', breakReason: '' }
        try { writeConfig(config) } catch {}
        setGear({ gear: 'busy', until, what: goal.slice(0, 20), task: { goal, by: role, reason, domain: String(args.domain).trim().slice(0, 120), breakAt: String(args.breakAt).trim().slice(0, 60) } }, 'ai-' + role)
        record({ state: 'task-start', at: now, mode: 'task', minutes, by: role, goal, reason })
        wallCloseOnStart(now)
        return { ok: true, text: '任务模式立好了：' + goal + '，到 ' + hm + '。娱乐全锁，到点交墙验收（submit_to_wall kind=review）。' }
      },
    }
  }
  /** 知识库：判断.md（她的观察和证据），我.md 只能在不存在时起草。 */
  function kbTool(role) {
    return {
      name: 'kb_write',
      description: '写笔记库 大肥鱼/ 下的文件：file=judge 整份更新 判断.md（你对他的判断和证据，保留他改过的，每次最多改 5 条）；' +
        'file=self 起草 我.md（他的目标、硬安排、红线、希望你们怎么对他）——只在他还没写时能起草，起草完跟他说让他改。',
      parameters: {
        type: 'object',
        properties: {
          file: { type: 'string', enum: ['judge', 'self'] },
          markdown: { type: 'string', description: '整份文件的 Markdown 内容' },
        },
        required: ['file', 'markdown'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const r = kbWrite(kbDir(notesDir(), home()), args.file, args.markdown, Date.now())
        if (r.ok) record({ state: 'kb-write', at: Date.now(), by: role, file: args.file })
        return { ok: r.ok, text: r.text }
      },
    }
  }
  // ---------------------------------------------------------------- 第四轮 R4-1：墙的审查
  //
  // 墙只看物理证据，判定全在 wall_auditor.js 的代码里，不读任何人的说法。姐妹是他这边的人：
  // 帮他把任务和证据整理好，用 submit_to_wall 交上来；过不过由这里决定。
  const loadWallMem = () => loadWallMemory(readState(stateFile('wall_memory.json'), {}))
  const saveWallMem = (m) => writeJson(stateFile('wall_memory.json'), m)
  const loadBaseline = () => readState(stateFile('wall-baseline.json'), {}) || {}
  const saveBaseline = (b) => writeJson(stateFile('wall-baseline.json'), b)
  const WALL_KIND_LABEL = { morning: '晨间立项', review: '验收', evening: '晚间对账', week: '周度审查', standby: '待命追问', defiance: '违抗追问', gemini: '连麦回流' }

  function wallRate(now) {
    return recentRate(loadWallMem(), dayString(now, zoneOr(config.timeZone)))
  }
  /** 审查要用的现场：立项时间、各文件立项时的大小、立项后的截图、今天的使用记录。 */
  function wallCtx(now) {
    const tz = zoneOr(config.timeZone)
    const day = dayString(now, tz)
    const base = loadBaseline()
    const today = base.day === day
    const since = today && Number(base.at) > 0 ? Number(base.at) : startOfDay(now, tz)
    const seenHashes = readLedger(home(), 400).filter((e) => e?.state === 'screenshot-hash' && e.sha256).map((e) => String(e.sha256))
    return {
      now, since, notesDir: notesDir(), stat: statOf,
      screenshots: listScreenshots(SCREENSHOT_DIRS, since, { seenHashes }),
      usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [],
      files: today && base.files && typeof base.files === 'object' ? base.files : {},
    }
  }
  /** 交验收时交的是哪一件（工具和墙共用一套口径）。认不出来时 fail 里是墙要说的话。 */
  function reviewTarget(args, now) {
    const ctx = wallCtx(now)
    const state = loadDaily(loadDailyState(), now, zoneOr(config.timeZone))
    const pick = String(args?.task ?? '').trim()
    const idx = /^\d+$/.test(pick) ? Number(pick) - 1 : state.tasks.findIndex((t) => pick && (t.task.includes(pick) || pick.includes(t.task)))
    let task = idx >= 0 ? state.tasks[idx] : state.tasks.length === 1 ? state.tasks[0] : null
    const extra = String(args?.evidence ?? '').trim()
    if (!task && extra) {
      // 临时交的完成物：文件类没有立项时的底数，量不出长了多少，所以只收截图和 App 时长。
      const type = evidenceType(extra)
      if (type === 'file' || !type) return { fail: { data: ['临时完成物「' + extra + '」'], missing: ['临时交的只收"截图"或"某 App N 分钟"；文件类要在早上立项时登记'] } }
      task = { task: String(config.taskGoal || '这一轮'), evidence: extra }
    }
    if (!task) return { fail: { data: ['今天立项的事：' + (state.tasks.map((t, i) => (i + 1) + '. ' + t.task).join('；') || '无')], missing: ['说清交的是第几件（task），或者交截图 / App 时长（evidence）'] } }
    return { task, idx, state, ctx, r: auditOne(task, ctx) }
  }
  /** 这一次交墙要不要让墙的眼睛读：交的是验收、代码层已经过了（文本增长或新截图）、没关掉这一层。 */
  function eyeWanted(kind, args, now) {
    if (kind !== 'review' || config.wallEye === false) return false
    const t = reviewTarget(args, now)
    return !t.fail && Boolean(t.r?.pass && (t.r.file || (t.ctx?.screenshots?.length && t.ctx.screenshots[0]?.path)))
  }
  /** 交验收前先让墙的眼睛读/看一遍（文本或截图多模态审读）。没读成就是 null。 */
  async function wallEye(args, now) {
    if (!eyeWanted('review', args, now)) return null
    const t = reviewTarget(args, now)
    const before = Number(t.ctx.files?.[t.task.task]?.size) || 0
    const text = t.r.file ? newContent(t.r.file, before) : ''
    let imageBase64 = ''
    let imageMimeType = 'image/png'
    const shot = !t.r.file ? t.ctx?.screenshots?.[0] : null
    if (shot?.path) {
      if (shot.sha256) record({ state: 'screenshot-hash', at: now, sha256: shot.sha256, name: shot.name })
      try {
        const raw = readFileSync(shot.path)
        if (raw.length > 0 && raw.length <= 2 * 1024 * 1024) {
          imageBase64 = raw.toString('base64')
          imageMimeType = /\.jpe?g$/i.test(shot.path) ? 'image/jpeg' : /\.webp$/i.test(shot.path) ? 'image/webp' : 'image/png'
        }
      } catch {
        // 读图失败则按代码时间与张数结论走
      }
    }
    const target = judgeTargetOf(ctx, config.strategistSessionId || config.sessionId, config) || {}
    const eye = await eyeCheck({ stream: streamOf(), provider: target.provider, model: target.model, task: t.task.task, evidence: t.task.evidence, text, imageBase64, imageMimeType })
    if (!eye.judged) record({ state: 'wall-eye', at: now, judged: false, why: eye.why })
    if (eye.judged && eye.pass) reliefPenaltyOnPass(now, zoneOr(config.timeZone))
    return eye
  }
  function auditOne(task, ctx) {
    return auditTask(task, { ...ctx, baseline: ctx.files[task.task] })
  }
  /** 晚间对账也用墙的口径（原来的关键词匹配能被"在笔记里写上那几个词"糊弄过去）。 */
  function wallCheck(now) {
    const ctx = wallCtx(now)
    return (task) => {
      const r = auditOne(task, ctx)
      return { verdict: r.verdict, why: r.data + (r.missing ? '；还缺：' + r.missing : '') }
    }
  }

  // ---------------------------------------------------------------- 第六轮 B：三个审查锚点
  //
  // 早 / 晚 / 周末各一次，全部由墙主持。审不审由时刻决定，不看他说了什么；
  // 他没来就不审内容，只记一条缺席——"没发生"本身必须留痕，否则静默失效查不出来。
  /** 这一周的客观数字：墙审了几次、过了几次、缺席几回。只读账本。 */
  function weekSummary(now) {
    const since = startOfDay(now, zoneOr(config.timeZone)) - 6 * 86_400_000
    const rows = readLedger(home(), 800).filter((e) => e?.state === 'wall-audit' && Number(e.at) >= since)
    const line = (kind, name) => {
      const g = rows.filter((r) => r.kind === kind)
      if (!g.length) return name + '：0 次'
      const pass = g.filter((r) => r.pass).length
      const absent = g.filter((r) => r.absent).length
      return name + '：' + g.length + ' 次（过 ' + pass + (absent ? '，缺席 ' + absent : '') + '）'
    }
    return [line('morning', '早上立标准'), line('evening', '晚上撞证据'), line('week', '周末看一周')]
  }
  /**
   * 到点还没有结论，就由墙按账本记一条缺席。
   * 只 record()，绝不 applyVerdict——不加锁、不罚、不扣额度。
   */
  function anchorSweep(now, tzIn) {
    const tz = requestZone(tzIn, config)
    const day = startOfDay(now, tz)
    const seen = readLedger(home(), 400).filter((e) => e?.state === 'wall-audit' && Number(e.at) >= day).map((e) => e.kind)
    for (const a of absentAnchors(zoneMinuteOfDay(now, tz), weekdayOf(now, tz), seen)) {
      record({ state: 'wall-audit', at: now, kind: a.kind, pass: false, absent: true, by: 'wall', missing: [a.why] })
      try {
        vaultAudit(now, '〔墙〕' + WALL_KIND_LABEL[a.kind] + '：缺席——' + a.why)
      } catch {
        // 没有笔记库就不写盘外的副本，账本那条已经落了
      }
    }
  }
  /** 今天三次审查各自的最后一条结论（面板据此看出哪一次漏了）。 */
  function anchorStatus(now, entries) {
    const day = startOfDay(now, zoneOr(config.timeZone))
    const last = (kind) => {
      let hit = null
      for (const e of entries) {
        if (e?.state !== 'wall-audit' || e.kind !== kind || Number(e.at) < day) continue
        if (!hit || Number(e.at) >= Number(hit.at)) hit = e
      }
      return hit ? { at: Number(hit.at), pass: Boolean(hit.pass), absent: Boolean(hit.absent) } : null
    }
    return { morning: last('morning'), evening: last('evening'), week: last('week') }
  }

  // ---------------------------------------------------------------- 预判闭环（B 层 T1）
  /** 今天任务完成的比例。没有任务就是 null——不拿 0 顶，免得把"没定事"判成"没做"。 */
  function tasksRatio(now) {
    const tz = zoneOr(config.timeZone)
    const state = loadDaily(loadDailyState(), now, tz)
    if (!state.tasks?.length) return null
    const out = reconcile(state, { now, tz, notesDir: notesDir(), stat: statOf, check: wallCheck(now) })
    return out.total ? out.done / out.total : null
  }
  /** 结算要用的实测值。量不到就留空，settle 会记 unknown，不冤枉他。 */
  function predictReadings(now, cur) {
    const ctx = wallCtx(now)
    const fileSize = {}
    for (const r of cur.list || []) {
      if (r.verdict) continue
      for (const m of r.metrics || []) {
        if (m.metric !== 'file_growth' || !m.path) continue
        const st = statOf(m.path)
        if (st) fileSize[m.path] = Number(st.size) || 0
      }
    }
    const appMinutes = {}
    for (const u of ctx.usage || []) {
      const label = String(u?.label || u?.app || '')
      if (label) appMinutes[label] = Math.round(Number(u.minutes) || 0)
    }
    return {
      fileSize,
      screenshots: (ctx.screenshots || []).length,
      appMinutes,
      funMinutes: funToday(lastPhone),
      tasksRatio: tasksRatio(now),
    }
  }
  /**
   * 把到期的预判交给墙的遥测撞一遍。verdict / verdictSource 只有 predict.js 的 settle 能写，
   * 模型写不进这个字段——这就是 §13.3 那条后门的堵法。
   */
  function settlePredictions(now) {
    const cur = loadPredict()
    if (!dueCount(cur, now)) return cur
    const out = settle(cur, predictReadings(now, cur), now)
    if (out.settled.length) {
      savePredict(out.ledger)
      record({ state: 'predict', at: now, settled: out.settled.map((r) => ({ id: r.id, verdict: r.verdict })) })
      // 押错的那条写进墙的审计，他自己的账上留痕。
      for (const r of out.settled) {
        if (r.verdict !== 'miss') continue
        vaultAudit(now, '预判没说中：' + r.claim + '（判据 ' + r.verdictSource + '，实测 ' +
          (r.readings || []).map((x) => x.metric + '=' + (x.value === null ? '量不到' : x.value)).join('、') + '）')
      }
    }
    return out.ledger
  }
  /**
   * B2：跑一次夜间整理。
   *
   * 两条纪律：**不占会话**（走 llm.stream，和判球同一条通道——整理的内容一个字都不会
   * 变成聊天记录里的一条消息），**不重试**（这一轮不成，明天的槽位还有）。
   * 产物只有三类：links/gaps/carry，落 nightly.json，由三个去向各自去取。
   */
  async function runNightly(now, tz, slot) {
    const target = nightlyTargetOf(ctx, config)
    const entries = readLedger(home(), 400)
    let candidates = { topics: [], facts: [], episodes: [] }
    const db = openMemoryDb(memoryPath())
    try {
      candidates = loadCandidates(db, { sessionId: config.sessionId, now })
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }
    const inputs = nightlyInputs({
      entries,
      candidates,
      stances: loadStances(),
      notes: tonightNoteNames(now, tz),
      tz,
    }, now)
    record({ state: 'nightly', at: now, slot, stage: 'start', objects: inputs.objects.length })
    const out = await tidyNightly({ stream: streamOf(), provider: target.provider, model: target.model, inputs })
    if (!out.ok) {
      // 不合格就丢弃，不重试。这一条只用来在真机上定位「为什么整晚没产物」。
      record({ state: 'nightly', at: Date.now(), slot, stage: 'discard', why: String(out.problems?.[0] || '').slice(0, 120) })
      return null
    }
    const resolved = resolveNightly(out.value, { objects: inputs.objects, entries })
    const merged = mergeNightly(loadNightly(), resolved, Date.now(), { day: dayString(now, tz) })
    saveNightly(merged.store)
    record({
      state: 'nightly', at: Date.now(), slot, stage: 'done', added: merged.added,
      dropped: Number(out.dropped || 0) + Number(resolved.dropped || 0),
      links: resolved.links.length, gaps: resolved.gaps.length, carry: resolved.carry.length,
    })
    return merged
  }
  /**
   * 今天改过的笔记文件名（只给文件名，不给原文）。
   * 复用 vaultRecentNotes 的 mtime 排序，只留今天动过的；没有就空——这一项缺了照样能整理。
   */
  function tonightNoteNames(now, tz) {
    try {
      const day = startOfDay(now, tz)
      return vaultRecentNotes(notesDir(), { limit: 8, now, exclude: [] })
        .filter((f) => Number(f.mtime) >= day)
        .map((f) => String(f.rel).slice(0, 60))
    } catch {
      return []
    }
  }
  /**
   * 墙认得的对象：今天早上的任务原话。hard 立场只能绑这些，否则就是「我就是觉得」，没法对账。
   * 规则项（规则.md）还没接进来，见第七轮进度 A1 一节「没做到的」。
   */
  function stanceKnown(now) {
    try {
      const state = loadDaily(loadDailyState(), now, zoneOr(config.timeZone))
      return new Set((state.tasks || []).map((t) => String(t?.task || '').slice(0, 120)).filter(Boolean))
    } catch {
      return new Set()
    }
  }
  /** 墙判一个对象做完没有：拿今天的任务对账结果。量不到给 null，不冤枉她也不白送。 */
  function stanceDoneFn(now) {
    const tz = zoneOr(config.timeZone)
    let results = []
    try {
      results = reconcile(loadDaily(loadDailyState(), now, tz), { now, tz, notesDir: notesDir(), stat: statOf, check: wallCheck(now) }).results
    } catch {
      results = []
    }
    return (about) => {
      const want = String(about || '').slice(0, 120)
      const hit = results.find((r) => String(r.task || '').slice(0, 120) === want)
      if (!hit) return null
      return hit.verdict === 'done' ? true : hit.verdict === 'partial' || hit.verdict === 'miss' ? false : null
    }
  }
  /**
   * 把到期的立场交给墙的遥测算 kept/broken。status / settleSource 只有 stance.js 的 settleStances
   * 能写——和 predict.settle 同一套堵法：模型说「我守住了」进不了这个字段。
   */
  function settleStanceNow(now) {
    const cur = loadStances()
    if (!cur.list.some((r) => r.status === 'held' && Number(r.until) <= now)) return cur
    const out = settleStances(cur, { now, isDone: stanceDoneFn(now) })
    if (!out.settled.length) return out.ledger
    saveStances(out.ledger)
    const tz = zoneOr(config.timeZone)
    const kb = vaultKb()
    for (const r of out.settled) {
      record({ state: 'stance-settled', at: now, stance: r })
      if (!kb) continue
      try {
        // 当日文件里留一行：她立过什么、守没守住（复用裁决的追加格式，不新造一份）。
        appendVerdictDaily(kb, now, tz, {
          kind: '立场结算',
          satisfied: r.status === 'kept',
          summary: r.text + '（' + r.id + ' → ' + r.status + '）',
        })
      } catch {
        // 写不进当日文件不影响结算本身
      }
    }
    // 命中率进 判断.md：她立得准不准，下一天的 hard 额度按这个浮动。
    const digest = stanceDigest(out.ledger.list)
    const dir = notesDir()
    if (digest.length && dir) {
      try {
        const kbDirNow = kbDir(dir, home())
        mkdirSync(kbDirNow, { recursive: true })
        archiveJudgeFile(kbDirNow, { now, tz })
        appendFileSync(join(kbDirNow, JUDGE_FILE), `\n<!-- stance-digest:${dayString(now, tz)} -->\n` + digest.join('\n') + '\n', 'utf8')
      } catch {
        // 笔记库不可写就只留在账本里
      }
    }
    return out.ledger
  }
  function sprintOrTask(now) {
    return Number(config.sprintUntil) > now || Boolean(aiTask(config, now))
  }
  /** 他点「开始」或立了任务：挂着的待命 / 违抗追问由墙当场关掉——动手了就是交代。 */
  function wallCloseOnStart(now) {
    // 2026-11-20：改道 Gemini 的那一次（还在等回执）也一样——动手了就是交代，不再 10 分钟后落锁。
    const fg = config.followupGemini
    if (fg && ['standby', 'defiance'].includes(fg.reason)) {
      commit({ followupGemini: null, obligations: clearObligation(config.obligations, fg.reason) })
      record({ state: 'wall-audit', at: now, kind: fg.reason, pass: true, by: 'start' })
      record({ state: 'verdict', at: now, by: 'wall', kind: fg.reason, satisfied: true, summary: '开始干活了', nextStep: String(config.taskGoal || '冲刺') })
      return { ok: true }
    }
    const l = config.followup
    if (!lockActive(l, now) || !['standby', 'defiance'].includes(l.reason)) return null
    record({ state: 'wall-audit', at: now, kind: l.reason, pass: true, by: 'start' })
    return applyVerdict({ ok: true, kind: l.reason, satisfied: true, summary: '开始干活了', nextStep: String(config.taskGoal || '冲刺') }, now, 'wall')
  }

  // ---------------------------------------------------------------- 第四轮 R4-3：墙在 Obsidian 里的那一半
  const localHm = (now) => {
    const m = zoneMinuteOfDay(now, zoneOr(config.timeZone))
    return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
  }
  const vaultKb = () => {
    const dir = notesDir()
    return dir ? kbDir(dir, home()) : ''
  }
  /** 墙的原话写进 大肥鱼/墙/审计/日期.md（没有笔记库就不写）。 */
  function vaultAudit(now, text) {
    const kb = vaultKb()
    if (!kb) return
    try {
      appendAudit(kb, dayString(now, zoneOr(config.timeZone)), localHm(now), text)
    } catch {
      // 写不进笔记库不影响审查本身
    }
  }
  /** 大肥鱼/任务/日期-序号-名称.md：一件事一个文件，frontmatter 给 Obsidian Bases 用。 */
  function vaultTask(now, index, task, status, proof = '') {
    const kb = vaultKb()
    if (!kb) return
    const day = dayString(now, zoneOr(config.timeZone))
    try {
      writeVaultText(taskNotePath(kb, day, index, task.task), renderTaskNote({ day, task: task.task, evidence: task.evidence, status, proof }))
    } catch {
      // 同上
    }
  }
  const loadRulesPending = () => readState(stateFile('wall-rules-pending.json'), {}) || {}
  const saveRulesPending = (p) => writeJson(stateFile('wall-rules-pending.json'), p)
  let rulesSeen = { key: '', at: 0 }
  /**
   * 读 大肥鱼/墙/规则.md：改严的立即生效，改松的进待生效表，冷却期（默认 7 天）过了才生效。
   * 文件不存在就按当前规则写一份模板。文件一变就读；没变就每分钟兜底一次（待生效的到点要生效）。
   */
  function syncVaultRules(now) {
    const kb = vaultKb()
    if (!kb) return
    const base = join(kb, WALL_DIR)
    const st = statOf(join(base, RULES_FILE))
    const key = st ? st.mtimeMs + ':' + st.size : 'none'
    if (key === rulesSeen.key && now - rulesSeen.at < 60_000) return
    rulesSeen = { key, at: now }
    const cooldown = Math.max(0, Number(config.rulesCooldownDays ?? RULES_COOLDOWN_DAYS)) * 86_400_000
    const fmtDay = (at) => dayString(at, zoneOr(config.timeZone))
    const cur = wallConfig(config)
    const text = readVaultText(join(base, RULES_FILE))
    if (text === null) {
      writeVaultText(join(base, RULES_FILE), renderRules(cur))
      writeVaultText(join(base, PENDING_FILE), renderPending({}, cooldown, fmtDay))
      return
    }
    const parsed = parseRules(text)
    if (!parsed.ok) return
    if (rulesNeedSwitchKeys(text)) {
      // 老文件补上切换三项：其余四项用他文件里写的值原样写回。
      writeVaultText(join(base, RULES_FILE), renderRules({ ...cur, ...parsed.wall }))
    }
    const { tighten, loosen } = classifyRules(cur, parsed.wall)
    const prev = loadRulesPending()
    const { pending, due } = updatePending(prev, loosen, now, cooldown)
    const patch = { ...tighten, ...due }
    if (Object.keys(patch).length) {
      commit({ wall: { ...(config.wall || {}), ...patch } })
      record({ state: 'wall-rules', at: now, tighten: Object.keys(tighten), loosen: Object.keys(due) })
    }
    if (JSON.stringify(prev) !== JSON.stringify(pending) || text === null) {
      saveRulesPending(pending)
      writeVaultText(join(base, PENDING_FILE), renderPending(pending, cooldown, fmtDay))
    }
  }

  /**
   * 交给墙：先看现在有没有这一种可以交（挂着的锁 / 门对得上），再按证据判。
   * @returns {{ok:boolean, pass:boolean, text:string}}
   */
  function wallAudit(kind, args, now, by) {
    const label = WALL_KIND_LABEL[kind]
    if (!label) return { ok: false, pass: false, text: 'kind 只能是 ' + Object.keys(WALL_KIND_LABEL).join(' / ') + '。' }
    const l = config.followup
    const held = lockActive(l, now) ? l.reason : ''
    const rh = lastRhythm
    const open = {
      morning: held === 'morning' || Boolean(rh?.morningGate),
      evening: held === 'evening' || Boolean(rh?.reviewGate),
      week: Boolean(rh?.weekReviewGate),
      review: held === 'review' || sprintOrTask(now),
      standby: held === 'standby',
      defiance: held === 'defiance',
      gemini: held === 'gemini',
    }[kind]
    const rate = wallRate(now)
    const done = (pass, data, missing = []) => {
      record({ state: 'wall-audit', at: now, kind, pass, by })
      if (pass) releaseWithdrawalOnAction({ wallPassed: true, actionHappened: true }, now)
      const text = wallText({ title: label, data, pass, missing, rate })
      vaultAudit(now, text)
      return { ok: true, pass, text }
    }
    if (!open) return done(false, ['现在没有挂着的「' + label + '」'], ['等到该交的时候再交'])

    if (kind === 'morning') {
      const c = checkMorningTasks(args.tasks)
      if (!c.ok) return done(false, [], c.problems)
      const tasks = c.tasks.map(({ task, evidence }) => ({ task, evidence }))
      const tz = zoneOr(config.timeZone)
      const day = dayString(now, tz)
      saveBaseline({ day, at: now, files: baselineFor(tasks, { notesDir: notesDir(), stat: statOf }) })
      saveWallMem(pruneMemory(notePromised(loadWallMem(), day, tasks.length), day))
      applyVerdict({ ok: true, kind, satisfied: true, summary: '立项 ' + tasks.length + ' 件', nextStep: tasks[0].task, tasks }, now, 'wall')
      tasks.forEach((t, i) => vaultTask(now, i, t, '进行中'))
      return done(true, tasks.map((t, i) => (i + 1) + '. ' + t.task + ' → ' + t.evidence))
    }

    if (kind === 'review') {
      const t = reviewTarget(args, now)
      if (t.fail) return done(false, t.fail.data, t.fail.missing)
      const { task, idx, state } = t
      const r = t.r
      // 墙的眼睛（2026-11）：文件证据在代码层过了，再读一遍新写的内容；只能把过改成不过。
      const eye = args && args.__eye
      if (r.pass && eye && eye.judged && eye.pass === false) {
        record({ state: 'wall-eye', at: now, pass: false, why: eye.why, quote: eye.quote })
        applyVerdict({ ok: true, kind, satisfied: false }, now, 'wall')
        return done(false, [task.task + '：' + r.data, '读了新写的内容：' + (eye.why || '和任务对不上') + (eye.quote ? '（原文：「' + eye.quote + '」）' : '')],
          ['写的东西要真是在做「' + task.task + '」，凑字数和无关内容不算'])
      }
      if (r.pass && eye && eye.judged) record({ state: 'wall-eye', at: now, pass: true, why: eye.why })
      if (!r.pass) {
        applyVerdict({ ok: true, kind, satisfied: false }, now, 'wall')
        return done(false, [task.task + '：' + r.data], [r.missing])
      }
      applyVerdict({ ok: true, kind, satisfied: true, summary: task.task + '：' + r.data, nextStep: '放风，回来接着下一件' }, now, 'wall')
      // A2：这一件提前交了墙 → 时间表里那块收工，剩下的重排一次（每天 2 次额度）。
      try {
        planAdvance(now, idx >= 0 ? state.tasks[idx]?.id : undefined)
      } catch (error) {
        console.error('[companion] 交墙后重排失败：' + String((error && error.message) || error))
      }
      if (idx >= 0 || state.tasks.length === 1) vaultTask(now, Math.max(0, idx), task, '完成', r.data)
      return done(true, [task.task + '：' + r.data])
    }

    if (kind === 'evening') {
      const tz = zoneOr(config.timeZone)
      const state = loadDaily(loadDailyState(), now, tz)
      const check = wallCheck(now)
      const rows = state.tasks.map((t) => ({ t, r: check(t) }))
      const ok = rows.filter((x) => x.r.verdict === 'done').length
      saveWallMem(pruneMemory(noteDone(loadWallMem(), state.day, ok), state.day))
      applyVerdict({ ok: true, kind, satisfied: true, summary: '对账：' + ok + '/' + rows.length, nextStep: '明天按对账结果' }, now, 'wall')
      const data = rows.length
        ? rows.map((x, i) => (i + 1) + '. ' + x.t.task + '：' + (x.r.verdict === 'done' ? '有证据' : x.r.verdict === 'partial' ? '一半' : '没证据') + '（' + x.r.why + '）')
        : ['今天没有立项的事']
      rows.forEach((x, i) => vaultTask(now, i, x.t, x.r.verdict === 'done' ? '完成' : x.r.verdict === 'partial' ? '一半' : '未完成', x.r.why))
      const text = wallText({ title: label + '：有证据完成 ' + ok + '/' + rows.length + '，结果作用于明天', data, pass: true, rate: wallRate(now) })
      record({ state: 'wall-audit', at: now, kind, pass: true, by })
      releaseWithdrawalOnAction({ wallPassed: true, actionHappened: true }, now)
      vaultAudit(now, text)
      return { ok: true, pass: true, text }
    }

    // 周末审这一周：只看账本里的客观数字，不问人，也不下发锁（§3.3）。
    if (kind === 'week') {
      const data = weekSummary(now)
      // 当周只开一次：交过就把门关上，同一晚不会重复交。
      saveRhythm(closeGate(loadRhythm(), 'week').state)
      return done(true, data)
    }

    if (kind === 'standby' || kind === 'defiance') {
      if (!sprintOrTask(now)) return done(false, ['现在没有在跑的冲刺或任务'], ['点「开始」，或者让姐妹帮他立一个任务（start_task_mode）'])
      wallCloseOnStart(now)
      return done(true, ['在做：' + String(config.taskGoal || '冲刺')])
    }

    // gemini：连麦回流只要一条能马上做的下一步。
    const next = String(args.next_step ?? args.nextStep ?? '').trim().slice(0, 200)
    if (next.length < 6) return done(false, ['下一步：' + (next || '空')], ['写一条能马上做的下一步（至少 6 个字）'])
    applyVerdict({ ok: true, kind, satisfied: true, summary: '连麦回流', nextStep: next }, now, 'wall')
    return done(true, ['下一步：' + next])
  }

  function wallTool(role) {
    return {
      name: 'submit_to_wall',
      description: '把他的东西交给墙审查。墙只看物理证据——文件立项后长了多少、立项后有没有新截图、App 今天用了多久——不读你们怎么说。' +
        'kind=morning：交今天 1–3 件事（tasks），每件的完成物写成墙查得到的样子：笔记文件路径（大肥鱼/周报.md）、"截图"、或"某 App N 分钟"。' +
        'kind=review：交验收，task 写第几件（从 1 数）或名字；临时的事可以用 evidence 交"截图"或"某 App N 分钟"。' +
        'kind=evening：交晚间对账（墙逐件核，结果作用于明天）。kind=week：周末交这一周的审查，只摆事实和下周重心两条让他拍板，不下发锁。' +
        'kind=standby / defiance：他已经点了「开始」或立了任务时交，关掉追问。' +
        'kind=gemini：交连麦得出的下一步（next_step）。墙的回复以〔墙〕开头，原样转给他，别改，也别替墙求情。',
      parameters: {
        type: 'object',
        properties: {
          kind: { type: 'string', enum: Object.keys(WALL_KIND_LABEL) },
          tasks: {
            type: 'array',
            maxItems: 3,
            items: {
              type: 'object',
              properties: {
                task: { type: 'string', description: '今天要做的事' },
                evidence: { type: 'string', description: '完成物：笔记文件路径 / "截图" / "某 App N 分钟"' },
              },
              required: ['task', 'evidence'],
              additionalProperties: false,
            },
          },
          task: { type: 'string', description: 'review：第几件（从 1 数）或任务名' },
          evidence: { type: 'string', description: 'review：临时的事交"截图"或"某 App N 分钟"' },
          next_step: { type: 'string', description: 'gemini：连麦得出的、能马上做的下一步' },
        },
        required: ['kind'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const kind = String(args.kind || '')
        // 只有「文件证据在代码层已经过了」这一种要等模型读一遍（异步）；其余照旧当场给结论。
        if (!eyeWanted(kind, args, Date.now())) return wallAudit(kind, args, Date.now(), role)
        return (async () => {
          const eye = await wallEye(args, Date.now())
          const out = wallAudit(kind, eye ? { ...args, __eye: eye } : args, Date.now(), role)
          if (eye && !eye.judged && out?.pass) out.text += '\n（墙没读成新写的内容：' + eye.why + '，这次按物理证据判。）'
          return out
        })()
      },
    }
  }
  /** 知识库只读检索：找证据、翻旧结论。姐妹共用（C4：两人工具一致）。 */
  function vaultSearchTool() {
    return {
      name: 'vault_search',
      description: '在笔记库里只读搜索（文件名和正文都搜），用来找完成物、翻以前定过的事。日记和私密目录不读。',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: '要找的词（一两个关键词最准）' },
          limit: { type: 'integer', minimum: 1, maximum: 20 },
        },
        required: ['query'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const hits = vaultSearch(notesDir(), args.query, { limit: Math.max(1, Math.min(20, Number(args.limit) || 8)) })
        if (!hits.length) return { ok: true, text: '笔记库里没有搜到「' + String(args.query || '').slice(0, 40) + '」。' }
        return { ok: true, text: hits.map((h) => h.rel + '：' + h.snippet).join('\n') }
      },
    }
  }
  /** 最近动过的笔记（摘要只读）：接着他昨天写到的地方说。 */
  function vaultRecentTool() {
    return {
      name: 'vault_recent_notes',
      description: '看他最近改过的笔记（只读摘要，不含日记和私密目录）。想接着昨天写到的地方说的时候用。',
      parameters: {
        type: 'object',
        properties: {
          limit: { type: 'integer', minimum: 1, maximum: 8 },
        },
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const list = vaultRecentNotes(notesDir(), { limit: Math.max(1, Math.min(8, Number(args.limit) || 3)) })
        if (!list.length) return { ok: true, text: '笔记库里最近没有改动。' }
        return { ok: true, text: list.map((n) => n.rel + '：' + n.summary).join('\n') }
      },
    }
  }
  /** 临时封禁名单：把 App 算进娱乐清单，到期自动失效。 */
  function blockTool(role) {
    return {
      name: 'block_apps',
      description: '把一些 App 临时算进娱乐清单（打开就照拒绝/任务/力度的规矩管）：比如晚上对账定下"明天封什么"，或者他最近沉迷某个不在清单里的 App。' +
        '填包名（不知道就先 mcp__phone__apps 查）。电话、短信、地图、支付永远不封。',
      parameters: {
        type: 'object',
        properties: {
          apps: { type: 'array', items: { type: 'string' }, description: '包名列表，最多 10 个' },
          hours: { type: 'integer', minimum: 1, maximum: 48, description: '封多久，默认 24 小时' },
          reason: { type: 'string' },
        },
        required: ['apps'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const until = now + Math.max(1, Math.min(48, Math.round(Number(args.hours) || 24))) * 3_600_000
        const wantInput = Array.isArray(args.apps) ? args.apps : []
        const resolvedRes = resolveAppNames(wantInput, {
          installedApps: Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : [],
          declaredApps: appsOf(),
        })
        const want = resolvedRes.resolved.map((a) => String(a).trim()).filter((a) => /^[A-Za-z0-9_.]{3,120}$/.test(a)).slice(0, 10)
        const skipped = want.filter((a) => NEVER_BLOCK.includes(a))
        const apps = want.filter((a) => !NEVER_BLOCK.includes(a))
        if (!apps.length) return { ok: false, text: skipped.length ? '这些永远不封：' + skipped.join('、') : '没有有效的包名。' }
        const keep = (Array.isArray(config.blockExtra) ? config.blockExtra : []).filter((b) => b && Number(b.until) > now && !apps.includes(b.app))
        commit({ blockExtra: [...keep, ...apps.map((app) => ({ app, until, by: role }))].slice(-30) })
        const labels = apps.map((p) => appDisplayLabel(p))
        record({ state: 'block', at: now, apps, labels, until, by: role, reason: String(args.reason || '').slice(0, 120) })
        return { ok: true, text: '封了 ' + apps.join('、') + '，到 ' + zoneMd(until, zoneOr(config.timeZone)) + ' ' + zoneHm(until, zoneOr(config.timeZone)) + '。' + (skipped.length ? '（' + skipped.join('、') + ' 永远不封）' : '') }
      },
    }
  }
  /**
   * 第二轮·放松申请（替代 grant_break）：只能在对账后的申请窗口里提，只给明天开一段，每周额度内。
   * 底线 1——聊天里没有别的通道能当场放松；窗口外调用直接拒。
   */
  function relaxTool(role) {
    return {
      name: 'request_relax',
      description: '替他向墙申请明天放松一段娱乐时间。只有对账后的申请窗口开着时才有用（窗口外会被拒）；只管明天一天，每周最多几天由额度定。' +
        '他清醒地想放松、给了理由时才用；atHour 是明天开始的整点（默认 20），minutes 默认 60。',
      parameters: {
        type: 'object',
        properties: {
          atHour: { type: 'integer', minimum: 0, maximum: 23, description: '明天从几点开始放松（整点）' },
          minutes: { type: 'integer', minimum: 15, maximum: 240, description: '放松多长，默认 60' },
          reason: { type: 'string', description: '他为什么想明天放松（必填）' },
        },
        required: ['reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const r = requestRelax(now, zoneOr(config.timeZone), { atHour: args.atHour, minutes: args.minutes, reason: args.reason, by: role })
        return { ok: r.ok, text: r.text }
      },
    }
  }
  /** 第二轮·收紧：任何时候可提，立即生效、当天有效。收紧不受窗口限制（底线只拦放松）。 */
  function tightenTool(role) {
    return {
      name: 'tighten_today',
      description: '他主动要求今天更严（或你判断该收紧）：立即把今天的娱乐锁到零点，明天恢复默认。任何时候都能用，不需要窗口。',
      parameters: {
        type: 'object',
        properties: { reason: { type: 'string', description: '为什么收紧' } },
        required: [],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const r = tightenNow(now, zoneOr(config.timeZone), { reason: args.reason, by: role })
        return { ok: r.ok, text: r.text }
      },
    }
  }
  /**
   * 第二轮·每日文件与按完成物对账（只有鲸澜有）。
   *   daily_plan  ：晨间对线时把今天的事和完成物写进 大肥鱼/每日/日期.md。
   *   daily_review：晚上对账，按完成物去知识库 / 账本 / 使用记录里找证据，结论作用于明天。
   */
  function dailyPlanTool(role) {
    return {
      name: 'daily_plan',
      description: '早上定事：把今天的事和每件事的完成物写进知识库的每日文件（大肥鱼/每日/日期.md），并排进当天的时表。' +
        '每件事必须带：完成物（笔记路径、Gemini 记录、某个学习 App 今天要用够多少分钟）、截止时刻（HH:MM）、预估分钟数、归哪个部' +
        '（信息收集 / 谋划思考 / 统筹规划 / 行为管理）。墙会避开吃饭时段和你 我.md 里的硬安排排块，排不下的会当场说清是哪一件。最多 6 件。',
      parameters: {
        type: 'object',
        properties: {
          tasks: {
            type: 'array',
            maxItems: 6,
            items: {
              type: 'object',
              properties: {
                task: { type: 'string', description: '今天要做的事（一句话）' },
                evidence: { type: 'string', description: '完成物：晚上能去查的东西（笔记路径 / Gemini 记录 / App 用够多少分钟）' },
                due: { type: 'string', description: '截止时刻 HH:MM（他说的"几点前做完"）' },
                minutes: { type: 'integer', minimum: 10, maximum: 480, description: '预估要花多少分钟' },
                dept: { type: 'string', enum: DEPTS, description: '归哪个部（给晚上四段对账用）' },
              },
              required: ['task', 'evidence', 'due', 'minutes', 'dept'],
              additionalProperties: false,
            },
          },
        },
        required: ['tasks'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const norm = normalizeTasks(args.tasks)
        if (!norm.ok) return { ok: false, text: norm.text }
        const dir = kbDir(notesDir(), home())
        const state = loadDaily(loadDailyState(), now, tz)
        // 重新定事＝重新排：重排记录归零，昨天的表不带过来。
        const out = schedule(norm.tasks, { ...planArgs(now, tz) })
        const next = { ...state, tasks: norm.tasks, plan: { at: now, blocks: out.blocks, overflow: out.overflow, reschedules: [], fired: { start: {}, end: {} }, submitted: {} } }
        savePlan(next)
        record({ state: 'daily-plan', at: now, by: role, count: norm.tasks.length, file: dailyPath(dir, now, tz), blocks: out.blocks.length, overflow: out.overflow.length })
        return { ok: true, text: norm.text + '\n' + scheduleText(out) }
      },
    }
  }
  function dailyReviewTool(role) {
    return {
      name: 'daily_review',
      description: '晚上对账：按早上写下的完成物逐项去知识库、账本、使用记录里找证据——找到算完成，找不到算没做完，找到一半记一半。' +
        '不看他说了什么。结论写进每日文件和账本，只影响明天的放风额度和信用分，不当场锁人。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: toolOutput,
      execute: () => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const dir = kbDir(notesDir(), home())
        const state = loadDaily(loadDailyState(), now, tz)
        if (!state.tasks.length) return { ok: false, text: '今天早上没定事，没有可对的账。先跟他说清今天该做什么、各自的完成物是什么。' }
        const out = reconcile(state, {
          now, tz, notesDir: notesDir(), ledger: readLedger(home(), 800),
          usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [],
          check: wallCheck(now),
        })
        const next = { ...state, review: { at: now, ...out } }
        const wrote = writeDaily(dir, next, {}, fourNumbers())
        saveDailyState(next)
        config = {
          ...config,
          daily: { day: state.day, done: out.done, partial: out.partial, miss: out.miss, total: out.total, at: now },
          // 作用于明天：明天那天的额度增减，过了那天自动失效。
          dailyQuota: { for: startOfDay(now, tz) + 86_400_000, delta: out.quotaDelta, done: out.done, total: out.total, at: now },
        }
        try {
          writeConfig(config)
        } catch {
          // 这一轮照样生效
        }
        record({ state: 'daily-review', at: now, by: role, done: out.done, partial: out.partial, miss: out.miss, total: out.total, quotaDelta: out.quotaDelta })
        const detail = out.results.map((r) => (r.verdict === 'done' ? '✅ ' : r.verdict === 'partial' ? '◐ ' : '✗ ') + r.task + '——' + r.why).join('\n')
        return { ok: true, text: reviewText(out) + '\n' + detail + '\n' + wrote.text }
      },
    }
  }
  function endTaskTool(role) {
    return {
      name: 'end_task_mode',
      description: '验收任务模式并结束（解锁娱乐）。只有确认他真的做到了才 passed=true：他讲清了做到哪、给你看了截图，或者你读屏核实过。没做到就别调用，或者用 start_task_mode 续。',
      parameters: {
        type: 'object',
        properties: {
          passed: { type: 'boolean' },
          summary: { type: 'string', description: '验收结论：做到了什么 / 依据是什么' },
        },
        required: ['passed', 'summary'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const summary = String(args.summary || '').trim().slice(0, 300)
        const hasAiLock = aiTask(config, now) ||
          (config.refuseBy === 'ai' && Number(config.refuseUntil) > now) ||
          Boolean(config.aiWorkSession && Number(config.aiWorkSession.until) > now) ||
          Boolean(config.taskLease && Number(config.taskLease.until) > now) ||
          Boolean(presetRt.sessionView(now))
        if (!hasAiLock) return { ok: false, text: '现在没有你们立的任务。' }
        if (args.passed !== true) {
          record({ state: 'task-check', at: now, by: role, passed: false, summary })
          return { ok: true, text: '记下了：没通过，任务继续。' }
        }
        const goal = config.taskGoal
        clearActiveLocksForUnlock({
          now,
          callerRole: 'ai-' + role,
          breakMinutes: 0,
          clearAllBlockExtra: false,
          clearBreak: false,
        })
        // 提前验收通过：到点那一下"任务到点"的验收就不用再来了。
        saveGearState({ ...loadGearState(), doneFor: Number(config.gearUntil) })
        record({ state: 'task-done', at: now, by: role, goal, summary })
        return { ok: true, text: '验收通过，锁已全部解除。' }
      },
    }
  }
  /**
   * 第四轮补·裁决入口：墙开的追问锁，由你自己回答算不算过关。
   * 格式校验在 followup.js 的 checkVerdict，落地在 applyVerdict——和墙走同一条路，只是 by 记你们两个。
   */
  function verdictTool(role) {
    return {
      name: 'submit_verdict',
      description: '回答墙开的追问锁（全屏拦着的那一问）：这次算不算过关，由你定。' +
        'kind 必须和锁的 reason 对上：morning 晨间立项 / review 验收 / evening 晚间对账 / defiance 违抗 / standby 待命超时 / gemini 连麦回流。' +
        'satisfied=false 要写清为什么不算过；satisfied=true 必须给 summary（为什么算过）和 nextStep（下一步做什么）。' +
        '晨间立项过关要带 1–3 项 tasks，每项有 task 和 evidence。过了就解锁（晨间/验收还会放一段风），不过锁继续挂着。',
      parameters: {
        type: 'object',
        properties: {
          kind: { type: 'string', description: 'morning / review / evening / defiance / standby / gemini' },
          satisfied: { type: 'boolean', description: '这次过不过' },
          summary: { type: 'string', description: '为什么算过（satisfied=true 时必填）' },
          nextStep: { type: 'string', description: '下一步做什么（satisfied=true 时必填）' },
          tasks: {
            type: 'array',
            maxItems: 3,
            description: '晨间立项过关时必带：今天 1–3 件事，每件带完成物',
            items: {
              type: 'object',
              properties: {
                task: { type: 'string', description: '要做什么（一句话）' },
                evidence: { type: 'string', description: '完成物：晚上能去查的东西' },
              },
              required: ['task', 'evidence'],
              additionalProperties: false,
            },
          },
          longTerm: { type: 'boolean', description: '这件事是不是长线的（进长期清单）' },
        },
        required: ['kind', 'satisfied'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const v = checkVerdict(args)
        if (!v.ok) return { ok: false, text: v.text }
        return applyVerdict(v, now, role)
      },
    }
  }
  /**
   * 第四轮补·当场放风：不解锁就玩不成的时候，你替他放一段。
   * 放风期间所有锁都不下发（手机按 breakUntil 执行），到点自动恢复。
   */
  function breakTool(role) {
    return {
      name: 'grant_break',
      description: '当场放他一段风：这段时间娱乐 App 全部解锁，到点自动恢复原样。' +
        '他清醒地提出、理由站得住才用；说清放多久、到点提醒他回来。账本会记下是谁放的、多久、为什么。',
      parameters: {
        type: 'object',
        properties: {
          minutes: { type: 'integer', description: '放多久（5–30 分钟，默认 10）' },
          reason: { type: 'string', description: '他给的理由（一句话）' },
        },
        required: ['reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const day = dayString(now, tz)
        const minutes = Math.max(5, Math.min(30, Math.round(Number(args.minutes) || 10)))
        const reason = String(args.reason || '').slice(0, 200)
        const until = now + minutes * 60_000
        const plRes = requestPass(loadPassLedger(now, tz), 'earned', {
          source: 'break',
          minutes,
          reason,
          until,
          now,
          day,
        })
        if (plRes.ok) savePassLedger(plRes.state)
        recordTimeEntryNow({ kind: 'break', minutes, reason, at: now, tz })
        const breakHm = zoneHm(until, tz)
        const nightNow = wallDecision(wallCfgNow(), now, tz)
        const isNightNow = Boolean(nightNow.locked && nightNow.kind === 'night')
        config = { ...config, breakUntil: until, breakSince: now, breakChecked: false, breakBy: role, breakReason: reason }
        try {
          writeConfig(config)
        } catch {
          // 这一轮照样生效
        }
        try { lockBus.clear({ keepSources: ['night'], now }) } catch {}
        forceUnlockUntil = Math.max(forceUnlockUntil, now + 90_000)
        refreshPanels()
        record({ state: 'grant-break', at: now, by: role, minutes, reason })
        return {
          ok: true,
          text: '放风 ' + minutes + ' 分钟，到 ' + breakHm + '；到点我提醒你回来。' +
            (isNightNow ? '（注：00:30-07:00 深夜宵禁仍在生效，非宵禁段生效）' : ''),
        }
      },
    }
  }
  /**
   * B 层 T1：押一条预判。
   * 你能写 claim、能写拿什么撞（evidenceRefs）、能定怎么算说中（metrics）；
   * **不能写 verdict**——那两个字由墙的遥测在到期时填，你说了不算（predict.js settle）。
   */
  /**
   * 第九轮 A 组：预案工具。她能看、能新建（从影子开始）、能收紧、能给这一局记 App；放宽一律不行。
   * 只在计划模态里给（P0-3 的每态 ≤ 8 个工具）：定计划时顺手把这一天的预案也定好。
   */
  function presetTool(role) {
    return {
      name: 'preset',
      description: '预案：系统对什么情况做什么，全部是数据。action=list 看全部预案和此刻的严格模式；' +
        'action=set 新建或改一条（preset 写整条：id/名称/状态/何时/条件/动作/解除）。新建一律先跑影子；' +
        '影子满 24 小时你才能转生效。你只能收紧（启用、次数变少、窗口变长、多加动作），放宽只有他自己能改而且要等 2 小时。' +
        '事件：切出 / 偏离停留 / 敷衍 / 局开始 / 局结束 / 到点。动作：严格模式 / 锁 / 全机锁 / 开工具 / 进入计划 / 说 / 记账 / 墙收紧。' +
        '解除只能从：本局结束 / 到点 / 答出指令 / 破窗 / 用户确认 里选。条件用 JSON Logic，只能读 bout / time / today / front / fired。' +
        'action=apps 给正在跑的这一局记必需应用和允许应用（包名）——严格模式和「切出」都按它判，定局时一定要问清。',
      parameters: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['list', 'set', 'apps'] },
          preset: { type: 'object', description: 'set：整条预案' },
          need: { type: 'array', items: { type: 'string' }, maxItems: 20, description: 'apps：这一局必需的 App 包名' },
          allow: { type: 'array', items: { type: 'string' }, maxItems: 20, description: 'apps：这一局还允许的 App 包名' },
        },
        required: ['action'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        if (args.action === 'set') {
          const r = presetRt.propose(args.preset, 'ai', now)
          return { ok: r.ok, text: (r.ok ? '' : '没改成：') + r.text }
        }
        if (args.action === 'apps') return presetRt.setApps('current', { need: args.need, allow: args.allow }, now, role)
        const l = presetRt.list(now)
        const lines = l.presets.map((p) => '[' + p.status + '] ' + p.id + '：' + p.explain)
        if (l.pending.length) lines.push('冷却中的放宽：' + l.pending.map((x) => x.presetId).join('、'))
        if (l.strict) lines.push('此刻严格模式：只放行 ' + (l.strict.allow.join('、') || '局页'))
        if (l.bout) lines.push('这一局（' + l.bout.kind + '）：必需 ' + (l.bout.apps.need.join('、') || '未声明') + '；允许 ' + (l.bout.apps.allow.join('、') || '未声明'))
        return { ok: true, text: lines.join('\n') }
      },
    }
  }

  /**
   * 第九轮 A 组：面板改预案。这里是「他自己」：放宽走 2 小时冷却期，期间可以撤销。
   * action: list（默认）/ set / cancel / apps / strict / history
   */
  function presetsRoute(body = {}) {
    const now = Date.now()
    const action = String(body?.action || 'list')
    if (action === 'set') return { v: 1, ...presetRt.propose(body.preset, 'user', now) }
    if (action === 'cancel') return { v: 1, ...presetRt.cancel(String(body.pendingId || ''), now, 'user') }
    if (action === 'apps') return { v: 1, ...presetRt.setApps(String(body.boutId || 'current'), { need: body.need, allow: body.allow }, now, 'user') }
    if (action === 'strict') return { v: 1, ...presetRt.strictNow(now, String(body.why || ''), 'user', body.minutes) }
    if (action === 'history') return { v: 1, ok: true, history: presetRt.history(String(body.id || '')) }
    if (action === 'renew') return { v: 1, ...presetRt.renew(String(body.id || ''), now, 'user') }
    return { v: 1, ok: true, ...presetRt.list(now) }
  }

  /**
   * 第九轮 B1：计划界面的读写。手机/面板拿这一张改今天的局，改完就生效（不再等 10 分钟确认）。
   * 提交前必须过 missingApps：任何一局缺必需应用或允许应用都拒收，并点名是哪一局。
   */
  // 第九轮 B1：今天还没有生效的、没开完的计划 → 挂一张「去定计划」。三个角色谁在场都是同一张，
  // 所以判定只写这一处：/plan 下发一次给手机，/sisters 下发一次给姐妹页。
  function planGateNow(now, tz) {
    const p = planOf(readState(stateFile('plan.json'), null), startOfDay(now, tz))
    const has = p.items.length > 0 && p.accepted && p.items.some((i) => !i.openedAt && !i.missed)
    return has ? null : { need: true, reason: 'no-plan', at: Number(p.at) || 0 }
  }

  function planDayRoute(body = {}) {
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    const day = startOfDay(now, tz)
    const stored = planOf(readState(stateFile('plan.json'), null), day)
    const wsSnap = workStoreNow(now, tz)
    const wsPlan = wsSnap.planItems.length > 0 ? { ...stored, items: wsSnap.planItems } : stored
    const action = String(body?.action || 'list')
    if (action !== 'set' && action !== 'confirm') {
      // 手机那边要用「最近用过的 App」给选择器排序：这正是每次 /plan 都送上来的那一串，
      // 已经过完包名过滤和 15 分钟截断（phone.js cleanSwitches），这里只取名字。
      // 注意 cleanSwitches 已经把 pkg 改叫 app 了。
      const recent = (lastPhone?.switches?.recent || []).map((x) => String(x?.app || '')).filter(Boolean).slice(0, 20)
      // 局型清单和它的默认时长也一起给：手机不硬编码协议，插件加了新局型它自然就多一行。
      const protocols = builtinProtocols().filter((x) => x.ok)
        .map((x) => ({ id: x.protocol.id, name: x.protocol.name, minutes: x.protocol.budget?.minutes || 0 }))
      // 第二项：四个数显示在计划页顶部。数据源就是行为大脑的今天摘要，这里不另算一份。
      // 比率算不出（今天还没开过局）给 null，手机那边显示「—」而不是 0。
      const dg = brainRt.view(now).today || {}
      const numbers = {
        ...fourNumbers(now),
        deviationTop: dg.deviationTop || [],
        opened: Number(dg.bouts?.opened) || 0,
        passed: Number(dg.bouts?.passed) || 0,
        bad: Number(dg.bouts?.bad) || 0,
        self: Number(dg.bouts?.self) || 0,
        planned: wsSnap.totalCount,
      }
      // 第三项：十分钟倒计时按主机时钟算（手机的钟可能不准），只剩毫秒数给手机显示。
      const leftMs = wsPlan.accepted ? 0 : Math.max(0, (Number(wsPlan.at) || 0) + PLAN_CONFIRM_MS - now)
      return {
        v: 1, ok: true, plan: wsPlan, workItems: wsSnap.store.items, min: zoneMinuteOfDay(now, tz), recent, protocols, numbers,
        acceptLeftMs: leftMs, sentence: planSentence(wsPlan),
      }
    }
    const raw = Array.isArray(body?.items) ? body.items : []
    const plan = { ...stored, items: raw.map((i) => ({ ...(i || {}) })).filter((i) => i && i.protocolId) }
    const miss = missingApps(plan)
    if (miss.length) return { v: 1, ok: false, code: 'apps-missing', missing: miss, text: appPrompt(plan) }
    // 第三项：缺交付物或验收方式的工作包进不了计划（工作包压根没写的不挡——草案本来就没有）。
    const apps = Array.isArray(config.apps) ? config.apps : []
    const bad = badPackets(plan, { apps })
    if (bad.length) {
      return {
        v: 1, ok: false, code: 'packet-missing', missing: bad.map((b) => b.title),
        text: packetPrompt(plan, { apps }),
      }
    }
    const over = judgeOver(plan)
    if (over) return { v: 1, ok: false, code: 'judge-over', text: over }
    // 写之前过一遍 normItem（planOf 会做），坏条目直接丢掉，不留半张计划。
    const next = planOf({ ...plan, day, edits: (Number(stored.edits) || 0) + 1, accepted: true, at: now }, day)
    writeJson(stateFile('plan.json'), next)
    // H6：同步写入 workStore（单表权威源）
    try {
      const wsCur = workStoreNow(now, tz).store
      const wsNext = replaceWorkSource(wsCur, 'plan', next.items, { day: dayString(now, tz), now })
      writeJson(stateFile('work.json'), { ...wsNext, migratedAt: wsNext.migratedAt || now, updatedAt: now })
    } catch {}
    record({ state: 'plan-edit', at: now, items: next.items.length, by: String(body?.by || 'panel') })
    return { v: 1, ok: true, plan: next, text: '计划定了：' + next.items.length + ' 局。' }
  }

  /**
   * 行为大脑（第一版）：GET 看全部；POST action=scan 现在就扫一遍 / note 他写自己的说法。
   * 解释只有鲸澜能写，走 brain 工具，不走这里。
   */
  function brainRoute(body = {}) {
    const now = Date.now()
    const action = String(body?.action || 'view')
    if (action === 'scan') return { v: 1, ok: true, ...brainRt.scanNow(now), ...brainRt.view(now) }
    if (action === 'note') return { v: 1, ...brainRt.note(String(body.cardId || ''), String(body.text || ''), now) }
    return { v: 1, ok: true, ...brainRt.view(now) }
  }

  /** 法典：把散在各处的规则收成一张有编号的表（只读）。 */
  function codexView(now) {
    const ledger = readLedger(home(), 1500)
    const verdicts = ledger.filter((e) => e?.state === 'wall-audit')
      .map((e) => ({ id: 'wa' + e.at, at: e.at, result: e.pass ? '过' : '不过', quote: [e.kind, ...(e.missing || [])].filter(Boolean).join('：') }))
    const appeals = ledger.filter((e) => e?.state === 'appeal')
      .map((e) => ({ at: e.at, result: e.pass ? '申诉成立' : '申诉驳回', reason: String(e.code || '') }))
    return { v: 1, ok: true, ...buildCodex({ settings: settingsOf(config), wall: wallConfig(config), presets: presetRt.list(now).presets, verdicts, appeals, now }) }
  }

  /**
   * 行为大脑工具：看模式卡、给卡写解释（只有鲸澜）。解释必须引用卡上证据里的日期或片段号，
   * 引不到的编号会被去掉，一个都没有就标「猜测」。立预案时在「依据」里写卡号（preset 工具）。
   */
  function brainTool(role) {
    return {
      name: 'brain',
      description: '行为大脑：代码每晚从他的手机使用里找重复出现的模式，记成模式卡（M-xx）。action=cards 看卡片和证据；' +
        'action=explain 给一张卡写你的解释（只有鲸澜能写；text ≤ 200 字；refs 填你引用的证据编号——卡上的日期或片段号，引不到的会被去掉）。' +
        '要针对一张卡立规则：用 preset 工具，在预案里写「依据」: 卡号。卡上的数字是代码算的，照着说，不要自己估。',
      parameters: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['cards', 'explain'] },
          cardId: { type: 'string', description: 'explain：卡号，如 M-03' },
          text: { type: 'string', description: 'explain：我认为是因为……' },
          refs: { type: 'array', items: { type: 'string' }, maxItems: 10, description: 'explain：引用的证据编号' },
        },
        required: ['action'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        if (args.action === 'explain') return brainRt.explain(String(args.cardId || ''), { text: args.text, refs: args.refs }, now, role)
        const v = brainRt.view(now)
        if (!v.cards.length) return { ok: true, text: '还没有模式卡：数据还不够（至少要攒几天）。' }
        const lines = v.cards.map((c) => c.id + '［' + c.status + '］' + c.title +
          '\n  证据：' + (c.evidence?.days || []).join('、') + (c.explain ? '\n  你上次的解释：' + c.explain.text : '') +
          (c.notes?.length ? '\n  他说：' + c.notes[c.notes.length - 1].text : ''))
        return { ok: true, text: lines.join('\n') }
      },
    }
  }

  function customProtocolFile(id) {
    const safe = String(id || '').trim().replace(/_/g, '-').replace(/[^a-z0-9-]/g, '')
    return safe ? join(home(), 'companion', 'composed-protocols', `${safe}.json`) : ''
  }

  function customProtocolOf(id) {
    const file = customProtocolFile(id)
    if (!file || !existsSync(file)) return null
    try {
      const loaded = loadProtocol(readFileSync(file, 'utf8'))
      return loaded.ok ? loaded.protocol : null
    } catch {
      return null
    }
  }

  function composeAndEnqueueBout(spec = {}, role = 'strategist') {
    const now = Date.now()
    const composed = composeBoutProtocol(spec, { role, now })
    if (!composed.ok) {
      return {
        ok: false,
        code: composed.error || composed.errors?.[0]?.code || 'invalid-protocol',
        errors: composed.errors || [],
        proposal: composed.proposal || null,
        text: (composed.errors || []).map((e) => `${e.where}: ${e.message}`).join('；') || '协议校验未通过',
      }
    }
    const protocol = composed.protocol
    const file = customProtocolFile(protocol.id)
    mkdirSync(join(home(), 'companion', 'composed-protocols'), { recursive: true })
    writeJson(file, protocol.raw)

    const tz = zoneOr(config.timeZone)
    const day = startOfDay(now, tz)
    const dayStr = dayString(now, tz)
    const stored = planOf(readState(stateFile('plan.json'), null), day)
    const dept = isValidDepartment(spec?.department) ? String(spec.department) : inferDepartment({
      mainState: 'plan',
      text: `${protocol.name} ${spec?.goal || ''} ${spec?.weakness || ''}`,
    }).department
    const entry = {
      protocolId: protocol.id,
      title: protocol.name,
      minute: Number(spec?.minute) > 0 ? Number(spec.minute) : zoneMinuteOfDay(now, tz) + 15,
      minutes: protocol.budget?.minutes || 25,
      department: dept,
    }
    const nextPlan = planOf({
      ...stored,
      day,
      items: [...(stored.items || []), entry],
      accepted: spec?.enqueueAccepted === true ? true : stored.accepted,
      at: now,
    }, day)
    writeJson(stateFile('plan.json'), nextPlan)

    let db = null
    try {
      db = openBoutsDb(memoryPath())
      upsertProtocol(db, {
        id: protocol.id,
        version: protocol.version,
        source: file,
        status: 'trial',
        home: home(),
        now,
      })
      const existing = getDayPlan(db, dayStr)
      const queue = [...(existing?.queue || []), entry]
      saveDayPlan(db, {
        id: existing?.id || ('plan:' + dayStr),
        day: dayStr,
        template: existing?.template || 'composed',
        queue,
        edits: [...(existing?.edits || []), { at: now, by: role, composeProtocolId: protocol.id }],
        activation: existing?.activation || 'auto',
        home: home(),
        now,
      })
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }

    record({ state: 'protocol-compose', at: now, protocolId: protocol.id, department: dept, role })
    return {
      ok: true,
      protocol,
      department: dept,
      day: dayStr,
      queuedItems: nextPlan.items.length,
      text: `已校验并生成动态局「${protocol.name}」（${protocol.id}），已挂载至今日队列（${dept}）。`,
    }
  }

  function composeBoutTool(role) {
    return {
      name: 'compose_bout_protocol',
      description: 'M4.2 鲸澜受控动态造局：根据长期目标与 判断.md 弱点生成 JSON 局协议。必须强制通过 protocol.js 静态校验（有限时与确定出口、无死循环、只用已注册能力、严禁声明墙能力），通过后装入今日 day_plans 队列。仅鲸澜（strategist）可写入。',
      parameters: {
        type: 'object',
        properties: {
          id: { type: 'string', description: '协议英文 id（小写字母/数字/连字符）' },
          name: { type: 'string', description: '局名称' },
          goal: { type: 'string', description: '本局要攻克的长期目标或弱点' },
          minutes: { type: 'number', description: '总时长预算（分钟）' },
          department: { type: 'string', description: '所属部门（ops_execute/dev_research/health_sleep/life_commute）' },
          steps: { type: 'array', description: '步骤数组（含 id/ask/limit/pass/timeout/fail）' },
          protocol: { type: 'object', description: '或直接提供完整 J2 协议 JSON 对象' },
        },
        additionalProperties: true,
      },
      output: toolOutput,
      execute: (args = {}) => composeAndEnqueueBout(args.protocol || args, role),
    }
  }

  // ── 2026-11：她们自己开局、自己排局 ─────────────────────────────────────────
  // 以前局只能由面板或计划到点开，计划草案也只有时段没有内容，又没有任何工具能填——
  // 局官只能问「第一步是什么」，她们也只能催。这两个工具把这件事交还给她们。
  function protocolChoices() {
    const list = builtinProtocols().filter((x) => x.ok).map((x) => x.protocol.id + '（' + x.protocol.name + '）')
    if (thinkingProtocol()) list.push('thinking（思维局）')
    return list.join('、')
  }
  function knownProtocol(id) {
    const k = String(id || '')
    return Boolean(builtinProtocolOf(k) || customProtocolOf(k) || (k === 'thinking' && thinkingProtocol()))
  }
  const PACKET_PROPS = {
    goal: { type: 'string', description: '这一局的目标，一句话（如「投出 3 份简历」）' },
    first: { type: 'string', description: '第一步：2 分钟内能开始的动作，≤40 字，含动词和一个 App 或物件（如「打开 Boss 直聘，筛上海后端前 10 个」）' },
    steps: { type: 'array', items: { type: 'string' }, maxItems: 5, description: '后面几步，最多 5 步' },
    deliverable: { type: 'string', description: '交付物：做完能拿出来的东西（如「3 张投递成功截图」「周报.md 写满 800 字」）' },
    accept_kind: { type: 'string', enum: ['evidence', 'basis', 'judge'], description: '验收方式：evidence 证据（截图、文件变长、App 时长，优先）/ basis 依据（对答案、对原文）/ judge 判断（一天最多 2 个）' },
    accept_what: { type: 'string', description: '具体怎么验（如「截图时间在开局后、能看到已投递、3 张」）' },
    stuck: { type: 'string', description: '可选：卡住时怎么办' },
    need: { type: 'array', items: { type: 'string' }, description: '必需应用的包名（不确定就先 mcp__phone__apps 查）' },
    allow: { type: 'array', items: { type: 'string' }, description: '允许应用的包名；不写就等于必需应用' },
  }
  function packetFrom(a = {}) {
    const raw = {
      goal: a.goal, first: a.first, steps: a.steps, deliverable: a.deliverable,
      accept: { kind: a.accept_kind, what: a.accept_what }, stuck: a.stuck,
    }
    return normPacket(raw).ok ? normPacket(raw).packet : null
  }
  function appsFrom(a = {}) {
    const installedApps = Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : []
    const declaredApps = appsOf()
    const rawNeed = Array.isArray(a.need) ? a.need : a.need ? [a.need] : []
    const need = pkgsOf(resolveAppNames(rawNeed, { installedApps, declaredApps }).resolved)
    const rawAllow = Array.isArray(a.allow) && a.allow.length ? [...a.allow, ...rawNeed] : rawNeed
    const allow = pkgsOf(resolveAppNames(rawAllow, { installedApps, declaredApps }).resolved)
    return { need, allow: [...new Set([...allow, ...need])] }
  }
  function hmToMinute(raw) {
    const m = /^(\d{1,2})[:：](\d{2})$/.exec(String(raw || '').trim())
    if (!m) return NaN
    const h = Number(m[1])
    const mi = Number(m[2])
    return h < 24 && mi < 60 ? h * 60 + mi : NaN
  }

  function openBoutTool(role) {
    return {
      name: 'open_bout',
      description: '现在就开一局（不用等计划到点，也不用他先同意）。他在晃、在拖、刚说要做某件事，就直接开，' +
        '开完告诉他第一步。要带工作包（目标、第一步、交付物、验收方式）和必需 / 允许的 App——严格模式按它放行。' +
        '同时只能有一局。可用局型：' + protocolChoices() + '。',
      parameters: {
        type: 'object',
        properties: {
          protocol: { type: 'string', description: '局型 id，如 execute / study / design / thinking' },
          why: { type: 'string', description: '为什么现在开（一句话，记进账本）' },
          ...PACKET_PROPS,
        },
        required: ['protocol', 'first', 'deliverable', 'need'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const id = String(args.protocol || '').trim()
        if (!knownProtocol(id)) return { ok: false, text: '没有这个局型：' + id + '。可用：' + protocolChoices() }
        const packet = packetFrom(args)
        const probs = packetProblems(packet, { apps: Array.isArray(config.apps) ? config.apps : [] })
        if (!packet || probs.length) return { ok: false, text: '工作包还不行：' + (probs.join('、') || '是空的') + '。改了再开。' }
        const apps = appsFrom(args)
        if (!apps.need.length) return { ok: false, text: '要写必需应用的包名（need），严格模式靠它放行。不确定就先 mcp__phone__apps 查。' }
        const r = startBout(id, { apps, packet, by: 'ai' })
        if (r.ok && args.strict !== false && apps.allow.length) {
          const until = Date.now() + Math.max(10, Math.min(180, Number(args.minutes) || 45)) * 60_000
          commit({ aiWorkSession: { allow: apps.allow, until, strict: true, why: '本局专时 · ' + id, by: role } })
        }
        record({ state: 'ai-open-bout', at: Date.now(), by: role, protocolId: id, ok: r.ok, why: String(args.why || '').slice(0, 80) })
        if (r.ok) refreshPanels()
        return { ok: r.ok, text: r.toast + (r.ok ? '（已同步激活专时严格白名单；他要换就 /bouts 收局）' : '') }
      },
    }
  }

  function planBoutsTool(role) {
    return {
      name: 'plan_bouts',
      description: '排今天的局：一次交整张表，替换今天还没开的局（已经开过、记过空局的保留）。每局都要有时间、局型、工作包和 App。' +
        '交上去后他有十分钟可以换，不说话就自动生效、到点自动开局。不用先问他想做什么——你根据记忆、笔记库和昨天的账先排，他只负责说行或换。' +
        '可用局型：' + protocolChoices() + '。',
      parameters: {
        type: 'object',
        properties: {
          items: {
            type: 'array',
            minItems: 1,
            maxItems: 8,
            items: {
              type: 'object',
              properties: {
                at: { type: 'string', description: '开局时刻 HH:MM（北京时间）' },
                protocol: { type: 'string', description: '局型 id' },
                minutes: { type: 'integer', minimum: 5, maximum: 180, description: '这一局多少分钟' },
                title: { type: 'string', description: '局名（如「求职局」）' },
                ...PACKET_PROPS,
              },
              required: ['at', 'protocol', 'minutes', 'title', 'first', 'deliverable', 'accept_kind', 'accept_what', 'need'],
              additionalProperties: false,
            },
          },
        },
        required: ['items'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const day = startOfDay(now, tz)
        const nowMin = zoneMinuteOfDay(now, tz)
        const stored = planOf(readState(stateFile('plan.json'), null), day)
        const kept = stored.items.filter((i) => i.openedAt || i.missed)
        const raw = Array.isArray(args.items) ? args.items : []
        const problems = []
        const items = []
        raw.forEach((a, n) => {
          const label = '第 ' + (n + 1) + ' 局（' + String(a?.title || a?.protocol || '') + '）'
          const minute = hmToMinute(a?.at)
          if (!Number.isFinite(minute)) return problems.push(label + '时间要写 HH:MM')
          if (minute < nowMin - 5) return problems.push(label + '的 ' + a.at + ' 已经过了')
          if (!knownProtocol(a?.protocol)) return problems.push(label + '没有这个局型：' + String(a?.protocol || ''))
          const apps = appsFrom(a)
          items.push({
            id: 'ai-' + String(a.protocol) + '-' + minute,
            protocolId: String(a.protocol),
            title: String(a.title || a.protocol).slice(0, 30),
            minute,
            budget: Math.round(Number(a.minutes) || 25),
            need: apps.need,
            allow: apps.allow,
            packet: packetFrom(a),
            why: role === 'strategist' ? '鲸澜排的' : '鱼丸排的',
          })
        })
        if (problems.length) return { ok: false, text: problems.join('；') + '。改了再交。' }
        items.sort((x, y) => x.minute - y.minute)
        for (let i = 1; i < items.length; i++) {
          if (items[i].minute < items[i - 1].minute + items[i - 1].budget) {
            return { ok: false, text: items[i - 1].title + '和' + items[i].title + '时间重叠了。' }
          }
        }
        const plan = { ...stored, items: [...kept, ...items] }
        const miss = missingApps({ items })
        if (miss.length) return { ok: false, text: appPrompt({ items }) + '包名不确定就先 mcp__phone__apps 查。' }
        const apps = Array.isArray(config.apps) ? config.apps : []
        const bad = badPackets({ items }, { apps })
        if (bad.length) return { ok: false, text: packetPrompt({ items }, { apps }) }
        const over = judgeOver(plan)
        if (over) return { ok: false, text: over }
        // 交上来不立刻生效：给他十分钟换（planwire 到点自动生效），和早上代码出的草案同一个规矩。
        const next = planOf({ ...plan, day, edits: Number(stored.edits) || 0, accepted: false, at: now }, day)
        writeJson(stateFile('plan.json'), next)
        record({ state: 'plan-ai', at: now, by: role, items: items.length })
        refreshPanels()
        const first = items[0]
        return {
          ok: true,
          text: '排好了 ' + items.length + ' 局，十分钟后自动生效。第一局 ' + String(args.items.find((x) => hmToMinute(x.at) === first.minute)?.at || '') +
            ' ' + first.title + '，第一步：' + String(first.packet?.first || '') + '。用一句话告诉他，他只说行或换。',
        }
      },
    }
  }

  function workflowTool(role) {
    return {
      name: 'workflow_propose',
      description: '提一条你自己想加的工作流。这是提议，不是启用——他不点「试」，它不会跑。' +
        '六项必须写全，缺一项交不上去：problem（解决什么问题）/ evidence（引账本哪条证据、哪个数）/ ' +
        'trigger（什么时候触发）/ ask（对他的要求，必须是他不用动手的那种）/ metric（可数的成功指标）/ ' +
        'judge（7 天后怎么判）。uses 是这条流程要用到的能力，只能用：' + WORKFLOW_ALLOW.join('、') + '。' +
        '每周最多提 1 条，同时在跑最多 3 条，满了先停再加。7 天没有可数效果会自动停，转正后每 30 天复核。',
      parameters: {
        type: 'object',
        properties: {
          problem: { type: 'string', description: '解决什么问题' },
          evidence: { type: 'string', description: '引哪条账本证据、哪个数' },
          trigger: { type: 'string', description: '什么时候触发' },
          ask: { type: 'string', description: '对用户的要求（不许是要他手动做的事）' },
          metric: { type: 'string', description: '可数的成功指标' },
          judge: { type: 'string', description: '7 天后怎么判' },
          uses: { type: 'array', maxItems: 8, description: '要用到的能力名', items: { type: 'string' } },
        },
        required: REQUIRED_FIELDS,
        additionalProperties: false,
      },
      output: {
        schema: { type: 'object', additionalProperties: true, properties: { ok: { type: 'boolean' }, text: { type: 'string' } } },
        render: (_a, v) => [{ type: 'text', text: String((v && v.text) || '') }],
      },
      execute: (args = {}) => {
        const now = Date.now()
        const entries = readLedger(home(), 300)
        const r = workflowPropose(config.workflows, entries, args, now, config.timeZone)
        if (!r.ok) {
          // 被拒的当场留痕：验收要查得到"墙的能力被拒"这件事发生过。
          record({ state: 'workflow-refused', at: now, by: role, code: r.code, missing: r.missing, rejected: r.rejected })
          const why =
            r.code === 'capability-refused'
              ? '这几种能力你碰不到：' + (r.rejected || []).map((x) => x.capability + '（' + x.code + '）').join('、')
              : r.code === 'field-missing'
                ? '缺这几项：' + (r.missing || []).join('、')
                : r.code === 'manual-action'
                  ? '这条要他自己动手，不算。'
                  : r.code === 'week-quota'
                    ? '这周已经提过一条了，下周再说。'
                    : r.code === 'running-quota'
                      ? '同时在跑的有 3 条了，先停一条再加。'
                      : r.code === 'duplicate'
                        ? '这个问题已经在跑了，不重复提。'
                        : '交不上去：' + r.code
          return { ok: false, text: why }
        }
        config = { ...config, workflows: [...(config.workflows || []), r.workflow] }
        try {
          writeConfig(config)
        } catch {
          return { ok: false, text: '存不下来，这条先不算。' }
        }
        record({ state: 'workflow-proposed', at: now, by: role, workflowId: r.workflow.id, problem: r.workflow.problem, metric: r.workflow.metric })
        return { ok: true, text: '提上去了。他不点「试」它不会跑；7 天没有可数效果自动停。' }
      },
    }
  }

  function resolveAppsOrPkg(names = []) {
    const installedApps = Array.isArray(lastPhone?.installedApps)
      ? lastPhone.installedApps
      : (Array.isArray(lastInstalled) ? lastInstalled : [])
    const base = resolveAppNames(names, { installedApps, declaredApps: appsOf() })
    const rawPkgRe = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$/
    const extraPkgs = []
    const stillUnresolved = []
    for (const item of base.unresolved || []) {
      const s = String(item || '').trim()
      if (rawPkgRe.test(s) && !base.resolved.includes(s) && !extraPkgs.includes(s)) extraPkgs.push(s)
      else stillUnresolved.push(item)
    }
    return {
      resolved: [...base.resolved, ...extraPkgs],
      unresolved: stillUnresolved,
    }
  }
  function syncAppListMembership(pkgs = [], mode = 'unblock', now = Date.now()) {
    const cleanPkgs = [...new Set((pkgs || []).filter(Boolean))]
    if (!cleanPkgs.length) return {}
    const patch = {}
    if (mode === 'allow' || mode === 'unblock') {
      const curExtra = Array.isArray(config.blockExtra) ? config.blockExtra : []
      patch.blockExtra = curExtra.filter((b) => b && Number(b.until) > now && !cleanPkgs.includes(b.app))
      for (const pkg of cleanPkgs) {
        try { lockBus.revokeByApp(pkg, { now }) } catch {}
      }
      try {
        const pending = loadTierPending()
        let changed = false
        for (const pkg of cleanPkgs) {
          if (pending && Object.prototype.hasOwnProperty.call(pending, pkg)) {
            delete pending[pkg]
            changed = true
          }
        }
        if (changed) saveTierPending(pending)
      } catch {}
    }
    if (mode === 'allow') {
      const safeAdd = cleanPkgs.filter((p) => !isPureGame(p))
      patch.defaultAllow = [...new Set([...defaultAllowApps(), ...safeAdd])]
      if (live?.apps && Array.isArray(live.apps.allow)) {
        live.apps.allow = [...new Set([...live.apps.allow, ...safeAdd])]
      }
      if (config.aiWorkSession && typeof config.aiWorkSession === 'object' && Array.isArray(config.aiWorkSession.allow)) {
        patch.aiWorkSession = {
          ...config.aiWorkSession,
          allow: [...new Set([...config.aiWorkSession.allow, ...safeAdd])],
        }
      }
    } else if (mode === 'restrict' && Array.isArray(config.defaultAllow)) {
      patch.defaultAllow = config.defaultAllow.filter((a) => !cleanPkgs.includes(a))
      if (live?.apps && Array.isArray(live.apps.allow)) {
        live.apps.allow = live.apps.allow.filter((a) => !cleanPkgs.includes(a))
      }
    }
    return patch
  }
  function clearActiveLocksForUnlock({
    now = Date.now(),
    callerRole = 'strategist',
    breakMinutes = 0,
    reason = '',
    clearAllBlockExtra = true,
    clearBreak = false,
  } = {}) {
    setGear({ gear: 'free' }, callerRole)
    try { lockBus.clear({ keepSources: ['night'], now }) } catch {}
    try {
      const d = loadDrive()
      if (d?.takeover?.active) saveDrive({ ...d, takeover: null })
      lastTakeover = null
    } catch {}
    const patch = {
      aiWorkSession: null,
      taskLease: null,
      verifyStartUntil: 0,
    }
    if (clearAllBlockExtra) {
      patch.refuseUntil = 0
      patch.refuseBy = undefined
      patch.blockExtra = []
    } else {
      if (config.refuseBy === 'ai') {
        patch.refuseUntil = 0
        patch.refuseBy = undefined
      }
      if (Array.isArray(config.blockExtra) && config.blockExtra.length) {
        patch.blockExtra = config.blockExtra.filter((b) => b && b.by !== 'ai' && b.by !== 'tool' && b.by !== 'ladder' && b.by !== 'companion' && b.by !== 'strategist')
      }
    }
    if (breakMinutes > 0) {
      patch.breakUntil = now + breakMinutes * 60_000
      patch.breakSince = now
      patch.breakChecked = false
      patch.breakBy = callerRole
      patch.breakReason = reason || '双鱼审核解锁放风'
    } else if (clearBreak) {
      patch.breakUntil = 0
      patch.breakBy = ''
      patch.breakReason = ''
    }
    commit(patch)
    presetRt.strictOff(now, callerRole)
    const g = loadGemini()
    if (g.session) saveGemini({ ...g, session: null })
    const ld = loadLadderDoc()
    if (ld.cell || ld.cellUntil || ld.planUntil) {
      saveLadderDoc({ ...ld, cell: '', cellUntil: 0, planUntil: 0 })
    }
    forceUnlockUntil = Math.max(forceUnlockUntil, now + 90_000)
  }

  /**
   * 统一超级调度器工具（全时段挂载给小鱼与大鱼，支持中文应用名模糊解析）。
   */
  function dispatchControlTool(role) {
    return {
      name: 'dispatch_control',
      description:
        '大肥鱼核心手机调度器：支持中文应用名（如「微信」「小红书」「Obsidian」「王者」「浏览器」）或包名。' +
        'focus_lock=开启严格专时铁笼（仅 apps 列表内的 App 可用，其余立即拦截）；' +
        'block_apps=把特定 App 临时拉黑禁开；' +
        'allow_apps=把非游戏 App 加入工作白名单（默认 work，可传 tier=dual）并立即解除拦截；' +
        'unblock_apps=把非游戏 App 移出临时封禁名单（blockExtra/惩罚锁）；' +
        'tag_app=给 App 永久打标（fun/work/tool）并同步白名单；' +
        'unlock_focus=双鱼审核解锁（解除当前专时/严格/接管/契约非夜间锁并默认放风 30 分钟，深夜 00:30-07:00 与纯游戏红线除外）；' +
        'unlock_gemini=强制关闭 Gemini 连麦舱与连带铁笼（两姐妹都可用）。',
      parameters: {
        type: 'object',
        properties: {
          action: { type: 'string', enum: ['focus_lock', 'block_apps', 'allow_apps', 'unblock_apps', 'tag_app', 'unlock_focus', 'unlock_gemini'] },
          apps: { type: 'array', items: { type: 'string' }, description: '中文应用名或包名列表（如 ["Obsidian", "微信"]）' },
          minutes: { type: 'integer', minimum: 0, maximum: 720, description: '持续分钟数（focus_lock 45 / block_apps 120 / unlock_focus 默认放风 30，传 0 只清锁）' },
          tag: { type: 'string', enum: ['fun', 'work', 'tool'], description: 'tag_app 时填写角色分类' },
          tier: { type: 'string', enum: ['work', 'dual'], description: 'allow_apps 时可选目标分层（默认 work）' },
          why: { type: 'string', description: '锁机或操作原因（直接展示在锁屏页）' },
        },
        required: ['action'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}, execCtx = {}) => {
        const now = Date.now()
        const callerSid = execCtx?.agent?.session?.id || execCtx?.agent?.id || execCtx?.sessionId || ''
        const callerRole = role === 'global' ? (callerSid ? speakerOf(callerSid) : (args.callerRole || 'companion')) : role
        const { resolved, unresolved } = resolveAppsOrPkg(args.apps || [])
        const why = String(args.why || '').trim().slice(0, 80)

        if (args.action === 'focus_lock') {
          if (!resolved.length) {
            return { ok: false, text: '没识别出允许保留的 App（' + (unresolved.join('、') || '请传中文名或包名') + '）。' }
          }
          const mins = Math.max(5, Math.min(720, Math.round(Number(args.minutes) || 45)))
          const until = now + mins * 60_000
          commit({
            breakUntil: 0,
            breakBy: '',
            breakReason: '',
            aiWorkSession: {
              allow: resolved,
              until,
              strict: true,
              why: why || (callerRole === 'strategist' ? '鲸澜开启严格专时：只保留指定 App' : '鱼丸锁机：这阵子只用这几个 App'),
              by: callerRole,
            },
          })
          record({ state: 'dispatch-focus-lock', at: now, by: callerRole, allow: resolved, minutes: mins, why })
          refreshPanels()
          return {
            ok: true,
            text: `已进入严格专时（${mins} 分钟）：仅保留 ${resolved.join('、')}，切出或用系统浏览器将立即全屏拉回（若需退出请去局页面收局或等倒计时结束）。`,
          }
        }

        if (args.action === 'block_apps') {
          const apps = resolved.filter((a) => !NEVER_BLOCK.includes(a))
          if (!apps.length) {
            return { ok: false, text: '没有可封禁的应用（' + (unresolved.join('、') || '电话/短信/地图/支付不可封') + '）。' }
          }
          const mins = Math.max(5, Math.min(2880, Math.round(Number(args.minutes) || 120)))
          const until = now + mins * 60_000
          const keep = (Array.isArray(config.blockExtra) ? config.blockExtra : []).filter((b) => b && Number(b.until) > now && !apps.includes(b.app))
          commit({
            blockExtra: [...keep, ...apps.map((app) => ({ app, until, by: callerRole, why: why || '姐妹临时封禁' }))].slice(-30),
          })
          const labels = apps.map((p) => appDisplayLabel(p))
          record({ state: 'dispatch-block', at: now, by: callerRole, apps, labels, minutes: mins, why })
          return { ok: true, text: `已封禁 ${apps.join('、')} ${mins} 分钟，一开即锁。` }
        }

        if (args.action === 'allow_apps') {
          if (!resolved.length) {
            return { ok: false, text: '没识别出要放行的应用（' + (unresolved.join('、') || '请传中文名或完整包名') + '）。' }
          }
          const allowed = resolved.filter((pkg, idx) => !isPureGame(pkg, (args.apps || [])[idx] || pkg))
          const games = resolved.filter((pkg, idx) => isPureGame(pkg, (args.apps || [])[idx] || pkg))
          if (!allowed.length) {
            return { ok: false, text: '硬规矩拦回：纯游戏（' + games.join('、') + '）不可加入白名单。' }
          }
          const targetTier = args.tier === 'dual' ? 'dual' : 'work'
          const nextTiers = { ...(config.appTiers || {}) }
          for (const pkg of allowed) nextTiers[pkg] = targetTier
          const patch = {
            appTiers: nextTiers,
            ...syncAppListMembership(allowed, targetTier === 'work' ? 'allow' : 'unblock', now),
          }
          commit(patch)
          writeTierFile()
          refreshPanels()
          record({ state: 'dispatch-allow-apps', at: now, by: callerRole, apps: allowed, tier: targetTier, why })
          return {
            ok: true,
            text: `已将 ${allowed.join('、')} 设为「${TIER_LABEL[targetTier]}」${targetTier === 'work' ? '并加入白名单' : '并解除临时封禁'}。` +
              (games.length ? `（纯游戏 ${games.join('、')} 已被硬规矩拦截）` : ''),
          }
        }

        if (args.action === 'unblock_apps') {
          if (!resolved.length) {
            return { ok: false, text: '没识别出要解封的应用（' + (unresolved.join('、') || '请传中文名或完整包名') + '）。' }
          }
          const allowed = resolved.filter((pkg, idx) => !isPureGame(pkg, (args.apps || [])[idx] || pkg))
          const games = resolved.filter((pkg, idx) => isPureGame(pkg, (args.apps || [])[idx] || pkg))
          if (!allowed.length) {
            return { ok: false, text: '硬规矩拦回：纯游戏（' + games.join('、') + '）不可通过名单解封。' }
          }
          const patch = syncAppListMembership(allowed, 'unblock', now)
          commit(patch)
          refreshPanels()
          const stillFun = allowed.filter((pkg) => {
            const t = tierFnNow()(pkg)
            return t === 'fun' || t === 'game'
          })
          record({ state: 'dispatch-unblock-apps', at: now, by: callerRole, apps: allowed, why })
          return {
            ok: true,
            text: `已将 ${allowed.join('、')} 移出临时封禁名单。` +
              (stillFun.length ? `（注：${stillFun.join('、')} 当前仍属娱乐/受限分层，如需长期放行请调 allow_apps 或 app_tier）` : '') +
              (games.length ? `（纯游戏 ${games.join('、')} 已拦回）` : ''),
          }
        }

        if (args.action === 'tag_app') {
          const pkg = resolved[0]
          if (!pkg) return { ok: false, text: '没找到要打标的应用。' }
          const nextRole = args.tag || 'fun'
          if ((nextRole === 'work' || nextRole === 'tool') && isPureGame(pkg, (args.apps && args.apps[0]) || pkg)) {
            return { ok: false, text: '硬规矩拦回：纯游戏不可分类为 work/tool。' }
          }
          const cur = cleanApps(config.apps).filter((a) => a.pkg !== pkg)
          cur.push({ pkg, role: nextRole, label: String((args.apps && args.apps[0]) || '').slice(0, 20) })
          const nextTier = nextRole === 'fun' ? 'fun' : 'work'
          const patch = {
            apps: cur,
            appTiers: { ...(config.appTiers || {}), [pkg]: nextTier },
            ...syncAppListMembership([pkg], nextRole === 'fun' ? 'restrict' : 'allow', now),
          }
          commit(patch)
          writeTierFile()
          refreshPanels()
          record({ state: 'dispatch-tag-app', at: now, by: callerRole, pkg, roleTag: nextRole })
          return { ok: true, text: `已将 ${pkg} 永久分类为「${nextRole}」，分层与白名单已同步更新。` }
        }

        if (args.action === 'unlock_focus') {
          const mins = args.minutes === 0 ? 0 : Math.max(5, Math.min(180, Math.round(Number(args.minutes) || 30)))
          clearActiveLocksForUnlock({
            now,
            callerRole,
            breakMinutes: mins,
            reason: why || '双鱼审核解锁放风',
            clearAllBlockExtra: true,
            clearBreak: mins === 0,
          })
          const tz = zoneOr(config.timeZone)
          const nightNow = wallDecision(wallCfgNow(), now, tz)
          const isNightNow = Boolean(nightNow.locked && nightNow.kind === 'night')
          record({ state: 'dispatch-unlock-focus', at: now, by: callerRole, minutes: mins, why })
          refreshPanels()
          return {
            ok: true,
            text: (mins > 0
              ? `已解除当前专时、接管、契约及临时封禁锁，并开启 ${mins} 分钟审核放风`
              : '已解除当前 AI 专时、接管、严格模式、Gemini 连麦舱及临时封禁锁') +
              (isNightNow ? '（注：00:30-07:00 深夜宵禁仍在生效，其余非夜间锁已清除）。' : '。'),
          }
        }

        if (args.action === 'unlock_gemini') {
          const g = loadGemini()
          if (g.session) saveGemini({ ...g, session: null })
          const ld = loadLadderDoc()
          if (ld.cell || ld.cellUntil) saveLadderDoc({ ...ld, cell: '', cellUntil: 0 })
          try { lockBus.revokeBySource('gemini', { now }) } catch {}
          try { lockBus.revokeBySource('ai', { now }) } catch {}
          presetRt.strictOff(now, callerRole)
          commit({ aiWorkSession: null, taskLease: null })
          forceUnlockUntil = Math.max(forceUnlockUntil, now + 90_000)
          record({ state: 'gemini-end', at: now, by: callerRole, reason: why || 'unlock_gemini' })
          refreshPanels()
          return { ok: true, text: '已关闭 Gemini 连麦舱及连带铁笼，手机恢复正常使用。' }
        }

        return { ok: false, text: '未知动作' }
      },
    }
  }

  /**
   * 2026-11-23 App 分层：双鱼审核位均可直接收严或放宽非纯游戏分层（纯游戏仍硬编码禁止放宽为 work/dual）。
   */
  function appTierTool() {
    return {
      name: 'app_tier',
      description: '调整一个 App 在大肥鱼里的分层（工作 work / 双用途 dual / 娱乐 fun / 游戏 game）。' +
        '双鱼审核位均可在核实理由后直接调整（包括放宽为 dual/work 并同步白名单与待生效队列）；' +
        '纯游戏硬编码禁止放宽为 work/dual，系统页面（桌面、相册、文件、分享、相机、输入法、NekoBox、电话支付）永远不锁。',
      parameters: {
        type: 'object',
        properties: {
          app: { type: 'string', description: '中文名或完整包名' },
          tier: { type: 'string', enum: ['work', 'dual', 'fun', 'game'] },
          why: { type: 'string', description: '为什么调整（一句话，写清核实依据）' },
          quote: { type: 'string', description: '可选：用户原文说明' },
        },
        required: ['app', 'tier', 'why'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}, execCtx = {}) => {
        const now = Date.now()
        const callerSid = execCtx?.agent?.session?.id || execCtx?.agent?.id || execCtx?.sessionId || ''
        const by = callerSid ? speakerOf(callerSid) : (args.by || 'sister')
        const { resolved } = resolveAppsOrPkg([args.app])
        const pkg = resolved[0]
        if (!pkg) return { ok: false, text: '没认出是哪个 App（请传中文名或完整包名）。' }
        const tier = String(args.tier || '')
        if (!TIER_RANK[tier] && tier !== 'work') return { ok: false, text: 'tier 只能是 work / dual / fun / game。' }
        const before = tierFnNow()(pkg)
        if (before === 'base') return { ok: false, text: '这是系统页面，永远不锁，不用分层。' }
        if (before === tier) return { ok: true, text: pkg + ' 本来就在「' + TIER_LABEL[tier] + '」。' }
        const loosen = (TIER_RANK[tier] ?? 1) < (TIER_RANK[before] ?? 1)
        if (loosen && isPureGame(pkg, args.app)) {
          return { ok: false, text: '硬规矩拦回：纯游戏不可放宽为工作或双用途白名单。' }
        }
        const membershipMode = tier === 'work' ? 'allow' : (tier === 'dual' ? 'unblock' : 'restrict')
        commit({
          appTiers: { ...(config.appTiers || {}), [pkg]: tier },
          ...syncAppListMembership([pkg], membershipMode, now),
        })
        writeTierFile()
        refreshPanels()
        const label = appDisplayLabel(pkg, args.app)
        record({ state: 'app-tier-set', at: now, by, pkg, label, from: before, to: tier, loosen, why: String(args.why || '').slice(0, 120) })
        return { ok: true, text: pkg + '：' + TIER_LABEL[before] + ' → ' + TIER_LABEL[tier] + '（' + (loosen ? '审核放宽，已立即生效并同步名单' : '改严，立即生效') + '）。' }
      },
    }
  }

  function grantTaskLeaseTool(role) {
    return {
      name: 'grant_task_lease',
      description: '（双鱼审核可用）通行证：为他批一段 ≤30 分钟（默认 15）的双用途 App（浏览器 / 夸克 / 微信 / B 站等）正事租约，干活时双用途 App 只能这样用。' +
        '一天超过 8 张要填 whyAgain（他亲口给的理由），申请次数进当晚判决。' +
        '纯游戏（王者/原神/游戏中心等）硬编码绝对拒绝；租约期手机持续巡检 screenText，偏航进娱乐流立即吊销重锁。',
      parameters: {
        type: 'object',
        properties: {
          app: { type: 'string', description: '双用途应用中文名或完整包名' },
          minutes: { type: 'integer', minimum: 5, maximum: 30, description: '通行证时长，默认 15，上限 30 分钟' },
          whyAgain: { type: 'string', description: '今天第 9 张起必填：他给的理由（原话）' },
          taskGoal: { type: 'string', description: '具体要在里面办什么正事' },
          verifyHints: { type: 'array', items: { type: 'string' }, description: '读屏期望关键词' },
        },
        required: ['app', 'taskGoal'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}, execCtx = {}) => {
        const callerSid = execCtx?.agent?.session?.id || execCtx?.agent?.id || execCtx?.sessionId || ''
        const callerRole = role === 'global'
          ? (callerSid ? speakerOf(callerSid) : (args.callerRole || 'companion'))
          : (callerSid ? speakerOf(callerSid) : role)
        const now = Date.now()
        const { resolved } = resolveAppsOrPkg([args.app])
        const pkg = resolved[0]
        if (!pkg) return { ok: false, text: '没认出要租约的应用名（请传中文名或完整包名）。' }
        if (isPureGame(pkg, args.app)) {
          return { ok: false, text: '硬规矩拦回：纯游戏绝不可通过聊天租约当场放行。' }
        }
        // H7：信用分仅影响双用途通行证（不越界干涉锁）；低信用分必须给出 whyAgain 说明
        const trust = trustNow(now)
        if (trust.score < 35 && !String(args.whyAgain || '').trim()) {
          return { ok: false, text: `当前信用分偏低（${trust.score}/100），本次申请 ${pkg} 通行证请填写 whyAgain 说明具体必要性。` }
        }
        const mins = Math.max(5, Math.min(30, Math.round(Number(args.minutes) || 15)))
        const tzL = zoneOr(config.timeZone)
        const day = dayString(now, tzL)
        const plCur = loadPassLedger(now, tzL)
        // 兼容历史测试直接往 ledger.jsonl 写的 grant-task-lease 条目
        const issuedInLedger = readLedger(home(), 600)
          .filter((e) => e?.state === 'grant-task-lease' && dayString(Number(e.at) || 0, tzL) === day).length
        const seedPl = plCur.lease.count < issuedInLedger
          ? { ...plCur, lease: { ...plCur.lease, count: issuedInLedger } }
          : plCur
        const plRes = requestPass(seedPl, 'lease', {
          app: pkg,
          label: args.app,
          minutes: mins,
          goal: args.taskGoal,
          whyAgain: args.whyAgain,
          verifyHints: args.verifyHints,
          by: callerRole,
          now,
          day,
        })
        if (!plRes.ok) {
          return { ok: false, text: plRes.why }
        }
        savePassLedger(plRes.state)
        commit({
          taskLease: {
            app: pkg,
            allow: [pkg],
            until: plRes.until,
            goal: plRes.lease.goal,
            verifyHints: plRes.lease.verifyHints,
            by: callerRole,
          },
        })
        record({ state: 'grant-task-lease', at: now, pkg, minutes: mins, goal: args.taskGoal, n: plRes.countToday, whyAgain: args.whyAgain ? String(args.whyAgain).slice(0, 80) : undefined })
        return { ok: true, text: `已批复 ${pkg} 的 ${mins} 分钟任务租约（${args.taskGoal}）；租约期间若刷出无关娱乐流将自动吊销。` }
      },
    }
  }

  /**
   * 内核时钟与复杂定时任务管理工具（高频/Cron/多阶段循环计时同步原生 AlarmManager）。
   */
  function clockManageTool(role) {
    return {
      name: 'clock_manage',
      description: '管理内核高精度时钟：支持单次闹钟（alarm）、固定频率高频/循环提醒（interval，支持秒级）、' +
        '5/6 段 Cron 循环闹钟（cron，北京时间）、多阶段循环计时（cycle，如专注25m->休息5m x N轮）及 list/pause/resume/skip/delete。',
      parameters: {
        type: 'object',
        properties: {
          op: { type: 'string', enum: ['create_alarm', 'create_interval', 'create_cron', 'create_cycle', 'list', 'pause', 'resume', 'skip', 'delete'] },
          id: { type: 'string', description: '任务 id（pause/resume/skip/delete 时传）' },
          label: { type: 'string', description: '闹钟/循环任务名称' },
          minutes: { type: 'integer', minimum: 1, maximum: 1440, description: 'create_alarm 延迟分钟数' },
          intervalSec: { type: 'integer', minimum: 5, maximum: 86400, description: 'create_interval 循环间隔（秒）' },
          cronExpr: { type: 'string', description: 'create_cron 5/6 段表达式，如 "30 8 * * 1-5"' },
          focusMin: { type: 'integer', minimum: 5, maximum: 180, description: 'create_cycle 专注阶段分钟数' },
          breakMin: { type: 'integer', minimum: 1, maximum: 60, description: 'create_cycle 休息阶段分钟数' },
          cycles: { type: 'integer', minimum: 0, maximum: 20, description: '循环轮数（0=无限）' },
          allowApps: { type: 'array', items: { type: 'string' }, description: '专时阶段允许的 App 名或包名' },
          urgent: { type: 'boolean', description: '是否走强闹钟唤醒（穿透省电白名单）' },
        },
        required: ['op'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const op = args.op
        if (op === 'list') {
          const items = clockKernel.list()
          if (!items.length) return { ok: true, text: '当前内核里没有挂着的闹钟或循环计时任务。' }
          return {
            ok: true,
            text: items.map((t) => `${t.id} [${t.kind}] ${t.label} ${t.paused ? '(已暂停)' : t.enabled ? '(运行中)' : '(已结束)'}`).join('\n'),
          }
        }
        if (op === 'pause') {
          const t = clockKernel.pause(args.id, now)
          return t ? { ok: true, text: '已暂停：' + t.label } : { ok: false, text: '没找到任务 ' + args.id }
        }
        if (op === 'resume') {
          const t = clockKernel.resume(args.id, now)
          return t ? { ok: true, text: '已继续：' + t.label } : { ok: false, text: '没找到任务 ' + args.id }
        }
        if (op === 'skip') {
          const ev = clockKernel.skipPhase(args.id, now)
          return ev ? { ok: true, text: `已跳过本段，切入：${ev.nextPhase ? ev.nextPhase.name : '全部完成'}` } : { ok: false, text: '只有循环计时支持跳段。' }
        }
        if (op === 'delete') {
          return clockKernel.remove(args.id) ? { ok: true, text: '已删除时钟任务。' } : { ok: false, text: '没找到任务。' }
        }
        if (op === 'create_cron') {
          if (!parseCron(args.cronExpr)) return { ok: false, text: 'Cron 表达式格式不对，需 5 或 6 段（如 "30 8 * * 1-5"）。' }
          const t = clockKernel.upsert({
            label: args.label || '定时循环闹钟',
            kind: 'CRON',
            cronExpr: args.cronExpr,
            urgent: args.urgent !== false,
            action: 'notify',
          }, now)
          return { ok: true, text: `已创建 Cron 循环闹钟「${t.label}」（${t.cronExpr}），id=${t.id}。` }
        }
        if (op === 'create_interval') {
          const sec = Math.max(5, Math.min(86400, Math.round(Number(args.intervalSec) || 60)))
          const t = clockKernel.upsert({
            label: args.label || `每 ${sec} 秒循环提醒`,
            kind: 'FIXED_RATE',
            intervalMs: sec * 1000,
            maxRuns: Number(args.cycles) || 0,
            urgent: Boolean(args.urgent),
            action: 'notify',
          }, now)
          return { ok: true, text: `已启动防漂移循环计时「${t.label}」（每 ${sec}s），id=${t.id}。` }
        }
        if (op === 'create_cycle') {
          const fMin = Math.max(5, Math.min(180, Math.round(Number(args.focusMin) || 25)))
          const bMin = Math.max(1, Math.min(60, Math.round(Number(args.breakMin) || 5)))
          const allow = resolveAppNames(args.allowApps || [], {
            installedApps: Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : [],
            declaredApps: appsOf(),
          }).resolved
          const t = clockKernel.upsert({
            label: args.label || `专注${fMin}m/休${bMin}m循环`,
            kind: 'CYCLE_PHASE',
            maxRuns: Number(args.cycles) || 0,
            urgent: true,
            phases: [
              { name: '专注', durationMs: fMin * 60_000, strictLock: allow.length > 0, allowApps: allow },
              { name: '休息', durationMs: bMin * 60_000, strictLock: false, allowApps: [] },
            ],
          }, now)
          if (allow.length) {
            commit({ aiWorkSession: { allow, until: now + fMin * 60_000, strict: true, why: '循环专时 · 专注', by: role } })
          }
          return { ok: true, text: `已启动多阶段循环计时「${t.label}」（id=${t.id}），手机原生时钟与锁笼已同步。` }
        }
        const mins = Math.max(1, Math.min(1440, Math.round(Number(args.minutes) || 15)))
        const t = clockKernel.upsert({
          label: args.label || `${mins} 分钟后闹钟`,
          kind: 'ONE_SHOT',
          intervalMs: mins * 60_000,
          urgent: args.urgent !== false,
          action: 'notify',
        }, now)
        return { ok: true, text: `已定好 ${mins} 分钟后的精确闹钟「${t.label}」（id=${t.id}）。` }
      },
    }
  }

  /**
   * 固定白名单 + 向 AI 申报审批制调度工具：用户输入用途与预期时长，由 AI 结合今日统计与惩罚上限分析并批复/缩减/拒绝。
   */
  function declareIntentTool(role) {
    return {
      name: 'declare_app_intent',
      description: '用户主动向 AI 申报打开某个软件的用途、所需时长和预期交付物。AI 结合今日 APP 分时段使用统计与惩罚限额做分析：' +
        'approve=按申报批出限时租约；reduce=缩减时长并附条件放行；deny=拒绝放行并保持锁定监督。纯游戏永远强硬拒绝。',
      parameters: {
        type: 'object',
        properties: {
          app: { type: 'string', description: '申报打开的软件中文名或包名' },
          intent: { type: 'string', description: '具体要做什么事（如：查数学笔记、回复导师微信）' },
          requestedMinutes: { type: 'integer', minimum: 5, maximum: 60, description: '申报需要几分钟' },
          deliverable: { type: 'string', description: '到点交付什么成果供墙或姐妹验收' },
          decision: { type: 'string', enum: ['approve', 'reduce', 'deny'], description: 'AI 分析后的审批裁决' },
          approvedMinutes: { type: 'integer', minimum: 5, maximum: 60, description: 'decision=reduce 时实际批准分钟数' },
          reason: { type: 'string', description: '结合今日时长数据与规则给出的审批/拒绝理由' },
        },
        required: ['app', 'intent', 'decision', 'reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}, execCtx = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const callerSid = execCtx?.agent?.session?.id || execCtx?.agent?.id || execCtx?.sessionId || ''
        const callerRole = role === 'global' ? (callerSid ? speakerOf(callerSid) : 'strategist') : role
        const installedApps = Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : []
        const { resolved } = resolveAppNames([args.app], { installedApps, declaredApps: appsOf() })
        const pkg = resolved[0]
        if (!pkg) return { ok: false, text: '没识别出申报的软件名：' + String(args.app || '') }
        const intent = String(args.intent || '').trim().slice(0, 120)
        const deliverable = String(args.deliverable || '').trim().slice(0, 120)
        const reason = String(args.reason || '').trim().slice(0, 140)
        if (isPureGame(pkg, args.app)) {
          recordBreachPenalty('moderate', now, tz)
          return { ok: false, text: '审批拒绝：纯游戏不在用途申报审批白名单内（已按试探规则记录监督）。' }
        }
        const penalty = loadPenalty(now, tz)
        if (args.decision === 'deny') {
          record({ state: 'intent-decision', at: now, by: callerRole, app: pkg, decision: 'deny', intent, reason })
          return { ok: true, text: `已拒绝「${args.app}」的使用申请（${reason}），保持当前锁机监督。` }
        }
        const reqMin = Math.max(5, Math.min(60, Math.round(Number(args.requestedMinutes) || 15)))
        const wantMin = args.decision === 'reduce'
          ? Math.max(5, Math.min(reqMin, Math.round(Number(args.approvedMinutes) || 10)))
          : reqMin
        const grantedMin = Math.min(wantMin, penalty.leaseCapMin)
        const until = now + grantedMin * 60_000
        commit({
          taskLease: {
            app: pkg,
            allow: [pkg],
            until,
            goal: intent + (deliverable ? '（交付：' + deliverable + '）' : ''),
            verifyHints: [],
            by: callerRole,
          },
        })
        record({
          state: 'intent-decision',
          at: now,
          by: callerRole,
          app: pkg,
          decision: grantedMin < reqMin ? 'reduce' : args.decision,
          minutes: grantedMin,
          intent,
          deliverable,
          reason,
        })
        const capHint = grantedMin < wantMin ? `（因当前违规欠账/限额，实际由 ${wantMin} 分钟压缩至 ${grantedMin} 分钟）` : ''
        return {
          ok: true,
          text: `已审批开放 ${pkg} ${grantedMin} 分钟${capHint}，用于「${intent}」；到点按「${deliverable || '口头/截图复盘'}」验收，偏离娱乐流将立即回收锁住。`,
        }
      },
    }
  }

  /**
   * 完整 APP 分时段统计与惩罚账本查询工具。
   */
  function appUsageReportTool(role) {
    return {
      name: 'app_usage_report',
      description: '查看今天完整 APP 使用时长、各软件打开次数、2 小时分时段调用分布、15 分钟频繁切换次数，以及当前惩罚欠账与高危黑历史时段。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: toolOutput,
      execute: () => {
        const rep = usageReportView(Date.now(), zoneOr(config.timeZone))
        const topLines = (rep.today || []).slice(0, 10)
          .map((t) => `${t.label || t.app} ${t.minutes}分钟` + (t.opens ? `/${t.opens}次` : '') + (t.fun ? '[娱乐]' : ''))
          .join('、') || '暂无记录（请检查使用情况访问权限）'
        const slotLines = (rep.timeSlots || [])
          .map((s) => `${s.slot}: 总${s.minutes}分(切${s.switches}次) [${(s.top || []).map((a) => a.label + a.minutes + 'm').join(',')}]`)
          .join('；') || '暂无分时段分布'
        const text = `今日屏幕合计 ${rep.totalMinutes} 分钟（娱乐 ${rep.funMinutes} 分钟）。\n` +
          `各软件明细：${topLines}\n分时段调用：${slotLines}\n` +
          `惩罚状态：配额欠账 ${rep.penalty.quotaDebtMin} 分钟，租约上限 ${rep.penalty.leaseCapMin} 分钟，高危时段 ${(rep.penalty.highRiskHours || []).join('/') || '无'}`
        return { ok: true, text, report: rep }
      },
    }
  }

  let lastRolledDice = 0

  function insertFactsIntoMemoryDb(items, now = Date.now()) {
    if (!items.length) return { imported: 0, skipped: 0, totalFacts: 0 }
    const db = openBoutsDb(memoryPath())
    if (!db) return { imported: 0, skipped: items.length, totalFacts: 0 }
    try {
      db.exec(
        'CREATE TABLE IF NOT EXISTS facts (id TEXT PRIMARY KEY, scope TEXT NOT NULL, text TEXT NOT NULL, kind TEXT, ' +
        'importance INTEGER, pinned INTEGER, key TEXT, tags TEXT, created_at INTEGER, updated_at INTEGER, ' +
        'last_hit_at INTEGER, hits INTEGER, hit_bonus REAL, expires_at INTEGER, source TEXT, status TEXT, ' +
        'superseded_by TEXT, superseded_reason TEXT, corrected_from TEXT, forget_reason TEXT)',
      )
      const colSet = new Set(db.prepare('PRAGMA table_info(facts)').all().map((c) => c.name))
      const hasFts = db.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'facts_fts'").all().length > 0
      const existingRows = db.prepare("SELECT id, text FROM facts WHERE status = 'active'").all()
      const existingSet = new Set(existingRows.map((r) => String(r.text || '').replace(/\s+/g, ' ').trim().toLowerCase()))
      const extractReplaceKey = (text, explicit) => {
        if (typeof explicit === 'string' && explicit.trim()) return explicit.trim().toLowerCase()
        const m = String(text || '').match(/^(\[(?:日程|Today|偏好|规则)·[^\]]{1,40}\])/i)
        return m ? m[1].toLowerCase() : ''
      }
      const keyMap = new Map()
      for (const r of existingRows) {
        const k = extractReplaceKey(r.text, '')
        if (k) {
          if (!keyMap.has(k)) keyMap.set(k, [])
          keyMap.get(k).push({ id: r.id, norm: String(r.text || '').replace(/\s+/g, ' ').trim().toLowerCase() })
        }
      }
      const supersedeSets = ["status = 'superseded'", 'updated_at = ?']
      const supersedeFields = []
      if (colSet.has('superseded_by')) {
        supersedeSets.push('superseded_by = ?')
        supersedeFields.push('superseded_by')
      }
      if (colSet.has('superseded_reason')) {
        supersedeSets.push('superseded_reason = ?')
        supersedeFields.push('superseded_reason')
      }
      const supersedeStmt = db.prepare(`UPDATE facts SET ${supersedeSets.join(', ')} WHERE id = ?`)

      const candidateCols = [
        'id', 'scope', 'text', 'kind', 'importance', 'pinned', 'key', 'tags',
        'triggers', 'scene_tags', 'created_at', 'updated_at', 'last_hit_at', 'hits',
        'hit_bonus', 'expires_at', 'source', 'status', 'superseded_by',
        'superseded_reason', 'corrected_from', 'forget_reason',
      ]
      const insertCols = candidateCols.filter((c) => colSet.has(c))
      const stmt = db.prepare(`INSERT INTO facts (${insertCols.join(', ')}) VALUES (${insertCols.map(() => '?').join(', ')})`)
      const ftsStmt = hasFts ? db.prepare('INSERT INTO facts_fts (id, scope, body) VALUES (?, ?, ?)') : null

      let imported = 0
      let skipped = 0
      let superseded = 0
      for (const it of items) {
        const cleanText = String(it?.text || '').replace(/\s+/g, ' ').trim().slice(0, 220)
        if (!cleanText) { skipped++; continue }
        const norm = cleanText.toLowerCase()
        if (existingSet.has(norm)) { skipped++; continue }
        existingSet.add(norm)
        const id = 'imp-' + randomUUID()
        const rKey = extractReplaceKey(cleanText, it?.replaceKey)
        let correctedFrom = ''
        if (rKey && keyMap.has(rKey)) {
          for (const prev of keyMap.get(rKey)) {
            if (prev.norm !== norm) {
              try {
                const args = [now]
                for (const f of supersedeFields) args.push(f === 'superseded_by' ? id : 'today_override')
                args.push(prev.id)
                supersedeStmt.run(...args)
                correctedFrom = prev.id
                superseded++
              } catch {}
            }
          }
          keyMap.set(rKey, [{ id, norm }])
        } else if (rKey) {
          keyMap.set(rKey, [{ id, norm }])
        }
        const src = String(it.source || 'obsidian').slice(0, 60)
        const rowMap = {
          id,
          scope: 'global',
          text: cleanText,
          kind: it.kind || 'fact',
          importance: Number(it.importance) || 2,
          pinned: it.pinned ? 1 : 0,
          key: rKey,
          tags: JSON.stringify([src]),
          triggers: '[]',
          scene_tags: '[]',
          created_at: now,
          updated_at: now,
          last_hit_at: 0,
          hits: 0,
          hit_bonus: 0,
          expires_at: 0,
          source: src,
          status: 'active',
          superseded_by: '',
          superseded_reason: '',
          corrected_from: correctedFrom,
          forget_reason: '',
        }
        stmt.run(...insertCols.map((c) => rowMap[c]))
        if (ftsStmt) {
          try { ftsStmt.run(id, 'global', cleanText) } catch {}
        }
        imported++
      }
      const totalRow = db.prepare("SELECT COUNT(*) AS cnt FROM facts WHERE status = 'active'").get()
      return { imported, skipped, superseded, totalFacts: Number(totalRow?.cnt) || existingSet.size }
    } finally {
      try { db.close() } catch {}
    }
  }

  function importVaultMemoriesRoute(input = {}) {
    const now = Date.now()
    const targetKb = kbDir(notesDir(), home())
    const starter = ensureStarterVaultFiles(targetKb, now)
    const extraFacts = Array.isArray(input?.facts)
      ? input.facts.map((t) => (typeof t === 'string'
        ? { text: t, source: 'manual-import', pinned: t.includes('📌') ? 1 : 0, importance: t.includes('📌') ? 4 : 2, kind: 'fact' }
        : t))
      : []
    const vaultFacts = collectVaultMemoryFacts(targetKb, { notesDir: notesDir() })
    const combined = [...vaultFacts, ...extraFacts]
    const dbRes = insertFactsIntoMemoryDb(combined, now)
    if (dbRes.imported > 0 || starter.createdFiles.length > 0) {
      record({
        state: 'memory-import',
        at: now,
        imported: dbRes.imported,
        created: starter.createdFiles.length,
        totalFacts: dbRes.totalFacts,
      })
    }
    return {
      v: 1,
      ok: starter.ok,
      kbDir: targetKb,
      importDir: starter.importDir,
      notesDir: notesDir() || '',
      createdFiles: starter.createdFiles,
      existingFiles: starter.existingFiles,
      scanned: combined.length,
      imported: dbRes.imported,
      skipped: dbRes.skipped,
      superseded: dbRes.superseded || 0,
      totalFacts: dbRes.totalFacts,
    }
  }

  function diceRollRoute(input = {}) {
    const now = Date.now()
    let topic = String(input?.topic || '').trim()
    let anchor = String(input?.anchor || '').trim()
    const appLabel = String(input?.appLabel || lastPhone?.label || lastPhone?.app || '').trim()
    let db = null
    try {
      db = openMemoryDb(memoryPath())
      if (db) {
        if (!topic) {
          const running = live?.official?.view?.()
          if (running?.title || running?.protocolId) topic = String(running.title || running.protocolId)
        }
        if (!anchor) {
          const cand = loadCandidates(db, { sessionId: config.sessionId, now })
          const facts = backgroundFacts(cand, 6)
          if (facts.length) anchor = facts[Math.floor(Math.random() * facts.length)]
        }
      }
    } catch {
      // fallback without memory anchor
    } finally {
      try { db?.close?.() } catch {}
    }
    const card = rollDiceCard({
      excludeDice: input?.excludeDice ?? lastRolledDice,
      topic,
      appLabel,
      anchor,
      seed: input?.seed ?? null,
    })
    lastRolledDice = card.dice
    // 2026-11-18 触发器：骰子 → 一整份 Gemini 简报（设定 + 当下事实 + 这一骰的思维方式 + 回执），
    // 手机「连 Gemini」直接带着它去开 Live；骰子卡原来那几行放在最前面。
    try {
      const extras = geminiExtras(now, { dice: card.dice, name: card.name, ask: card.ask })
      const cardLines = String(card.geminiPrompt || '').split('\n').map((l) => l.trim()).filter(Boolean)
      const brief = geminiBrief({
        owner: String(config.owner || '他'),
        topic: card.topic || ('用「' + card.name + '」把手上这件事想清楚'),
        minutes: 15,
        mode: 'think',
        setting: extras.setting,
        bridge: extras.bridge,
        context: [...cardLines, ...extras.context.filter((l) => !l.startsWith('这次摇到的思维方式'))],
      })
      card.geminiPrompt = brief
      if (input?.manual !== false) {
        requestGeminiViaGate({
          source: 'dice',
          mode: 'manual',
          topic: card.topic || card.name,
          minutes: 15,
          geminiMode: 'think',
          brief,
          now,
        })
      }
    } catch {
      // 简报拼不出来就用骰子卡原来那段
    }
    record({
      state: 'dice-roll',
      at: now,
      dice: card.dice,
      name: card.name,
      manual: input?.manual !== false,
      app: lastPhone?.app || '',
    })
    return { v: 1, ok: true, ...card }
  }

  function diceThoughtRoute(input = {}) {
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    const thought = String(input?.thought || input?.text || '').replace(/\s+/g, ' ').trim().slice(0, 300)
    if (!thought) return { v: 1, ok: false, error: { code: 'empty', message: 'thought is empty' } }
    const dice = Number(input?.dice) || lastRolledDice || 1
    const name = String(input?.name || '').trim()
    const appLabel = String(input?.appLabel || lastPhone?.label || '').trim()
    record({ state: 'dice-thought', at: now, dice, name, text: thought, appLabel })
    const noteFile = appendDiceThought(kbDir(notesDir(), home()), { dice, name, thought }, now, tz, { notesDir: notesDir() })
    const dbRes = insertFactsIntoMemoryDb([
      {
        text: `[六骰思考·骰${dice}${name ? ' ' + name : ''}] ${thought}`.slice(0, 220),
        source: 'six-dice',
        pinned: 0,
        importance: 2,
        kind: 'thought',
      },
    ], now)
    return { v: 1, ok: true, saved: true, dice, name, text: thought, noteFile, imported: dbRes.imported }
  }

  function rollSixDiceTool(role) {
    return {
      name: 'roll_six_dice',
      description: '帮用户摇一颗「六骰思维骰子」（1事实提取/2预判推演/3联想比较/4关键提问/5依据与结论/6替代解释），结合当前记忆锚点生成一句防走神微干预问题，避免代答。',
      parameters: {
        type: 'object',
        properties: {
          topic: { type: 'string', description: '当前讨论主题或任务（可选）' },
          anchor: { type: 'string', description: '指定记忆/笔记锚点（可选）' },
        },
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args) => {
        const card = diceRollRoute({ topic: args?.topic || '', anchor: args?.anchor || '', manual: true })
        return {
          ok: true,
          text: `🎲 摇出【骰${card.dice} · ${card.name}】${card.anchor ? '（锚点：' + card.anchor + '）' : ''}\n问题：${card.ask}`,
          card,
        }
      },
    }
  }

  function handoffRoute(input = {}) {
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    let facts = []
    let db = null
    try {
      db = openMemoryDb(memoryPath())
      if (db) {
        const cand = loadCandidates(db, { sessionId: config.sessionId, now })
        facts = backgroundFacts(cand, 8)
      }
    } catch {
      // ignore memory read error
    } finally {
      try { db?.close?.() } catch {}
    }
    const running = live?.official?.view?.() || null
    const pkt = buildHandoffPacket({
      kb: kbDir(notesDir(), home()),
      notesDir: notesDir() || '',
      phone: lastPhone,
      bout: running ? { title: running.title || running.protocolId, strict: running.strict === true } : null,
      memoryFacts: facts,
      now,
      tz,
      writeSnapshot: input?.writeSnapshot !== false,
    })
    return { v: 1, ...pkt }
  }

  function exportCognitiveHandoffTool(role) {
    return {
      name: 'export_cognitive_handoff',
      description: '生成「Cognitive OS × 大肥鱼」跨 AI 通用交接上下文（整合 03_Thinking_Models / 05_Projects_And_Plans / 02_Staging_Review / 我.md / 记忆事实 / 六骰思维碎记），可同步给 鱼丸/鲸澜 或直接复制给 Gemini Live / Antigravity 等外部 AI。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: toolOutput,
      execute: () => {
        const res = handoffRoute({ writeSnapshot: true })
        return {
          ok: true,
          text: res.markdown,
          file: res.file,
          hasCognitiveOs: res.hasCognitiveOs,
          modelsCount: res.modelsCount,
        }
      },
    }
  }

  function addTodayTaskTool(role) {
    return {
      name: 'add_today_task',
      description: '往 Today 今日总揽工作台的主线清单加入一条待办任务，直接同步今天计划并继承白名单与默认验收标准。',
      parameters: {
        type: 'object',
        properties: {
          title: { type: 'string', description: '任务名称（一句话说明产出或目标）' },
          minutes: { type: 'integer', description: '预计专注时长（分钟，默认 45）' },
          deliverable: { type: 'string', description: '可选：交付物说明' },
          top: { type: 'boolean', description: '是否插队放到最前面' },
        },
        required: ['title'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const res = todayTaskRoute({ ...args, action: 'add', by: role || 'ai' })
        return { ok: res.ok === true, text: res.text || '已加入 Today 主线清单。' }
      },
    }
  }

  function predictTool(role) {
    return {
      name: 'submit_prediction',
      description: '押一条明天（或几小时后）会被证伪的预判，到期由墙的遥测判对错，不是你自己说了算。' +
        'claim 写一句具体的、能被打脸的话。' +
        'metrics 是判据，只能用这五个：file_growth（要给 path，量立项后字节增量）/ screenshot_count（新截图张数）/ ' +
        'app_minutes（要给 app 名，今天的分钟数）/ fun_minutes（娱乐合计分钟数）/ tasks_ratio（今天任务完成比例 0–1）。' +
        '每条 metric 给 op（> >= < <=）和 threshold；给几条就是几条全部成立才算说中。' +
        'evidenceRefs 写到时候拿什么撞它（例如 审计/2026-11-10.md、app:刷题、file:大肥鱼/周报.md），没有就交不上去。' +
        'dueInHours 默认 20 小时。说错了会被记进墙的审计，别急着找理由。',
      parameters: {
        type: 'object',
        properties: {
          claim: { type: 'string', description: '一句能被证伪的话' },
          dueInHours: { type: 'integer', description: '几小时后判（1–168，默认 20）' },
          evidenceRefs: {
            type: 'array',
            maxItems: 5,
            description: '到时候拿什么撞这条预判',
            items: { type: 'string' },
          },
          metrics: {
            type: 'array',
            maxItems: 3,
            description: '怎么算说中；给几条就是全部要成立',
            items: {
              type: 'object',
              properties: {
                metric: { type: 'string', description: 'file_growth / screenshot_count / app_minutes / fun_minutes / tasks_ratio' },
                op: { type: 'string', description: '> >= < <=' },
                threshold: { type: 'number', description: '门槛' },
                path: { type: 'string', description: 'file_growth 必填：文件路径' },
                app: { type: 'string', description: 'app_minutes 必填：App 名字' },
              },
              required: ['metric', 'op', 'threshold'],
              additionalProperties: false,
            },
          },
        },
        required: ['claim', 'evidenceRefs', 'metrics'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const cur = loadPredict()
        const v = checkClaim(args, { now, day: dayString(now, zoneOr(config.timeZone)), notesDir: notesDir() })
        if (!v.ok) return { ok: false, text: '这条押不上去：\n- ' + v.problems.join('\n- ') }
        const { ledger, record: rec } = appendPrediction(cur, v.value)
        savePredict(ledger)
        record({ state: 'predict-new', at: now, by: role, id: rec.id, claim: rec.claim })
        const when = zoneMd(rec.dueAt, zoneOr(config.timeZone)) + ' ' + zoneHm(rec.dueAt, zoneOr(config.timeZone))
        return {
          ok: true,
          text: '押上了（' + rec.id + '），' + when + ' 由墙的遥测判。判据：' +
            rec.metrics.map((m) => m.metric + (m.path ? '(' + m.path + ')' : m.app ? '(' + m.app + ')' : '') +
              m.op + m.threshold).join('，且') + '。到点不由你说了算。',
        }
      },
    }
  }
  function takeStanceTool(role) {
    return {
      name: 'take_stance',
      description: '立一条立场：你要求过他什么，白纸黑字记下来，他磨你的时候这些算数。' +
        'hard = 到期前不可更改，你也没有改它的工具，每天最多 3 条；soft 可以改，但要给理由和一条新证据，每天最多 5 条。' +
        'about 绑今天早上的任务原话（hard 必须绑墙认得的任务）；evidence 给账本行号或一句原话；untilHours 几小时后到期（≤336）。' +
        '每天额度有限，挑重要的立。',
      parameters: {
        type: 'object',
        properties: {
          text: { type: 'string', description: '她的原话，≤120 字' },
          kind: { type: 'string', description: 'hard（不可改）/ soft（可改，要新证据）' },
          about: { type: 'string', description: '绑今天哪件事（任务原话）' },
          untilHours: { type: 'integer', description: '几小时后到期，≤336' },
          evidence: { type: 'array', maxItems: 5, description: '凭什么立：账本行号或一句原话', items: { type: 'string' } },
        },
        required: ['text', 'kind', 'about', 'untilHours', 'evidence'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const cur = loadStances()
        const hours = Number(args.untilHours)
        const v = checkStance({
          text: args.text, kind: args.kind, about: args.about, by: role, evidence: args.evidence,
          until: Number.isFinite(hours) ? now + hours * 3_600_000 : 0,
        }, { now, day: dayString(now, tz), known: stanceKnown(now), ledger: cur, hitRate: hitRate(cur.list) })
        if (!v.ok) return { ok: false, text: '这条立场立不起来：\n- ' + v.problems.join('\n- ') }
        const out = appendStance(cur, v.value)
        saveStances(out.ledger)
        record({ state: 'stance-new', at: now, by: role, stance: out.record })
        return {
          ok: true,
          text: '立住了（' + out.record.id + '），' + zoneHm(out.record.until, tz) + ' 到期。' +
            (out.record.kind === 'hard' ? '到期前谁也改不了，包括你。' : '要改得给一条新证据。'),
        }
      },
    }
  }
  function reviseStanceTool(role) {
    return {
      name: 'revise_stance',
      description: '改一条软立场。硬立场到期前改不了（返回 stance-locked）——能让它变的只有到期、墙按证据判定做完、他早上重新定计划。' +
        '改必须给 reason，并引用一条立完那条立场之后新出现的证据（账本行号或一句原话）。' +
        '他说不想做、换个话题不算证据，那是回避，不是新事实。',
      parameters: {
        type: 'object',
        properties: {
          id: { type: 'string', description: '立场编号，形如 S-20261111-01' },
          reason: { type: 'string', description: '为什么改' },
          text: { type: 'string', description: '改后的原话；不给就沿用原来那句' },
          evidence: { type: 'array', maxItems: 5, description: '新证据：账本行号或一句原话', items: { type: 'string' } },
        },
        required: ['id', 'reason', 'evidence'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const cur = loadStances()
        // 行号按整份账本从头数，不随读取窗口滑动；compact 之后会重来，可接受。
        const entries = readLedger(home(), 5000).map((e, i) => ({ ...e, seq: i + 1 }))
        const r = reviseStance(cur, { id: args.id, reason: args.reason, text: args.text, evidence: args.evidence, entries, now })
        if (!r.ok) {
          // 每次想改硬立场都留痕：这不是她的工具，试了要能查（stance-cave-attempt）。
          record({ state: 'stance-cave-attempt', at: now, by: role, id: String(args.id || ''), code: r.code })
          return { ok: false, text: r.problems.join('\n') }
        }
        saveStances(r.ledger)
        record({ state: 'stance-revised', at: now, by: role, stance: r.record })
        return { ok: true, text: '改成 ' + r.record.id + ' 了，到期时间没往后推（' + zoneHm(r.record.until, zoneOr(config.timeZone)) + '）。' }
      },
    }
  }
  /**
   * 手机报来的事：overlay-closed（他关掉了聊天窗，聊没聊过）、defied（关窗 30 秒后还在娱乐 App 里）。
   * 违抗按规矩当场升级（不等模型）：开拒绝模式，并让姐姐马上来收尾。
   */
  let pendingDefiance = null
  /**
   * Gemini 互动舱（应用管理页 / 两个人格都能开）：一场连麦期间手机只许 Gemini。
   *   start: { action:'start', topic, minutes, mode, by } → 写进 gemini.json 的 session
   *   end:   { action:'end' } → 提前收工，锁恢复成平时的拒绝模式 / 档位
   */
  function sessionRoute(body) {
    const now = Date.now()
    const action = String(body?.action || '')
    const g = loadGemini()
    if (action === 'end') {
      saveGemini({ ...g, session: null })
      const ld = loadLadderDoc()
      if (ld.cell || ld.cellUntil) saveLadderDoc({ ...ld, cell: '', cellUntil: 0 })
      presetRt.strictOff(now, String(body?.by || 'ui').slice(0, 20))
      forceUnlockUntil = now + 90_000
      record({ state: 'gemini-end', at: now, by: String(body?.by || 'ui').slice(0, 20) })
      return { v: 1, ok: true, session: null, clearWorkSession: true }
    }
    if (action !== 'start') return { v: 1, ok: false, error: { code: 'bad-action', message: 'start|end' } }
    const minutes = Math.max(5, Math.min(180, Math.round(Number(body?.minutes) || 30)))
    const mode = geminiMode(body?.mode)
    const topic = String(body?.topic || '').trim().slice(0, 120)
    const session = {
      topic, mode, minutes, startedAt: now, until: now + minutes * 60_000,
      by: String(body?.by || 'ui').slice(0, 20),
    }
    const ld = loadLadderDoc()
    if (ld.cell || ld.cellUntil) saveLadderDoc({ ...ld, cell: '', cellUntil: 0 })
    saveGemini({ ...g, session })
    record({ state: 'gemini-start', at: now, topic, mode, minutes, by: session.by })
    return { v: 1, ok: true, session: geminiSessionView(now) }
  }
  /**
   * 第二轮·墙的手机入口（应用管理页 / 面板）：
   *   status  → 读墙的现状（锁/开、窗口、本周剩余额度、今天有没有修改）。
   *   relax   → 申请明天放松一段（只在窗口+额度内；替代聊天里的放风）。
   *   tighten → 立即收紧到今天零点（任何时候）。
   */
  async function wallRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const action = String(body?.action || 'status')
    const status = () => {
      const wd = wallDecision(wallCfgNow(), now, tz)
      // 额度为什么可能是 0：四段没交齐。界面得能把这句话说出来，不然只看到 0 不知道原因。
      const dep = statusOf(depositToday(now))
      return {
        v: 1, ok: true, wall: {
          locked: wd.locked, kind: wd.kind || null, openUntil: wd.openUntil || 0, why: wd.why || null,
          applyWindowOpen: applyWindowOpen(config.applyWindow, now),
          applyWindowCloseAt: Number(config.applyWindow?.closeAt) || 0,
          relaxRemaining: relaxRemainingNow(now, tz),
          depositDone: dep.done,
          depositTotal: dep.total,
          depositMissing: dep.missing.join('、'),
          relaxToday: Boolean(activeRelax(config, now, tz)),
          tightenToday: tightenActive(config, now, tz),
          meals: wallConfig(config).meals,
        },
      }
    }
    if (action === 'relax') {
      const r = requestRelax(now, tz, { atHour: body?.atHour, minutes: body?.minutes, reason: body?.reason, by: 'ui' })
      if (r.ok) releaseWithdrawalOnAction({ legalBuffer: true, actionHappened: true }, now)
      return { ...status(), ok: r.ok, error: r.ok ? undefined : { code: r.code, message: r.text }, text: r.text }
    }
    if (action === 'tighten') {
      const r = tightenNow(now, tz, { reason: body?.reason, by: 'ui' })
      return { ...status(), ok: r.ok, text: r.text }
    }
    if (action === 'dice-slots') {
      const plan = buildSixDicePlan({
        stage: body?.stage || 1,
        notesDir: notesDir(),
        stage3Note: body?.stage3Note || '',
        query: body?.query || '',
      })
      return { v: 1, ok: true, ...plan }
    }
    if (action === 'dice-judge') {
      const dice = Math.max(1, Math.min(6, Math.round(Number(body?.dice) || 1)))
      const failMap = loadDiceFailures()
      const prevFails = body?.failures !== undefined ? Number(body.failures) || 0 : (Number(failMap[dice]) || 0)
      const target = judgeTargetOf(ctx, config.sessionId, config) || {}
      const out = await auditSixDiceSlot({ ...body, dice, failures: prevFails }, {
        notesDir: notesDir(),
        stream: streamOf(),
        provider: target.provider || '',
        model: target.model || '',
      })
      saveDiceFailures({ ...failMap, [dice]: out.pass ? 0 : out.failures })
      record({
        state: 'wall-dice-judge', at: now, dice, slot: out.slot,
        pass: out.pass, code: out.code, judgedBy: out.judgedBy,
      })
      if (out.pass) releaseWithdrawalOnAction({ wallPassed: true, actionHappened: true }, now)
      return { v: 1, ok: Boolean(out.pass), ...out }
    }
    if (action === 'stroke-slots') {
      const plan = buildStrokePlan({
        track: body?.track || 'multi_shuttle_drill',
        strokes: body?.strokes,
        topic: body?.topic || '',
        anchors: body?.anchors || [],
      })
      return { v: 1, ok: true, ...plan }
    }
    if (action === 'stroke-judge') {
      const target = judgeTargetOf(ctx, config.sessionId, config) || {}
      const out = await auditStrokeSlot(body || {}, {
        notesDir: notesDir(),
        kb: kbDir(notesDir(), home()),
        stream: streamOf(),
        provider: target.provider || '',
        model: target.model || '',
      })
      record({
        state: 'wall-stroke-judge', at: now, stroke: out.stroke,
        pass: out.pass, code: out.code, foulCode: out.foulCode || '', judgedBy: out.judgedBy,
      })
      if (out.pass) releaseWithdrawalOnAction({ wallPassed: true, actionHappened: true }, now)
      return { v: 1, ok: Boolean(out.pass), ...out }
    }
    if (action === 'capability') {
      const capOut = executeCapability(body?.name, body?.input || {}, {
        perceive: (cap, input) => ({
          ok: true, cls: cap.class, name: cap.name, phone: lastPhone, pc: loadPcHeartbeat(), signal: input?.signal ?? null,
        }),
        trigger: (cap, input) => {
          if (input?.protocolId) return startBout(String(input.protocolId), { department: input?.department, by: 'capability' })
          if (input?.question) {
            const lock = openFollowup(String(input.kind || 'capability'), String(input.question), now)
            return { ok: true, cls: cap.class, name: cap.name, followup: followupView(lock, now) }
          }
          return { ok: true, cls: cap.class, name: cap.name, triggered: true }
        },
        reply: (cap, input) => ({
          ok: true, cls: cap.class, name: cap.name, prompt: String(input?.prompt || cap.how || ''), ready: true,
        }),
        verify: (cap, input) => {
          const text = String(input?.answer || input?.text || '').trim()
          const passed = Boolean(input?.evidence || text)
          return { ok: true, cls: cap.class, name: cap.name, passed }
        },
        gear: (cap, input) => {
          const mins = Math.max(1, Math.min(BUSY_MAX_MIN, Math.round(Number(input?.minutes) || BUSY_DEFAULT_MIN)))
          return { ok: true, cls: cap.class, name: cap.name, mode: String(input?.mode || 'sprint'), minutes: mins }
        },
      })
      return { v: 1, ...capOut }
    }
    return status()
  }
  /**
   * A3：墙的审查记录（长得像对话，其实是账本）。
   * 墙不聊天：这里只把已经落账的「提交 / 判定 / 规则变更」还原成倒序气泡，一句话都不新生成。
   */
  function wallThreadRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const entries = readLedger(home(), 2000)
    const t = wallThread(entries, { days: 14, limit: 60, now })
    return {
      v: 1, ok: true,
      thread: {
        bubbles: t.bubbles.map((b) => ({ kind: b.kind, at: b.at, who: b.who, text: b.text, pass: b.pass === undefined ? null : Boolean(b.pass) })),
        kinds: t.kinds,
        appealLeft: appealLeft(entries, zoneStartOfDay(now, tz)),
        appealMax: APPEAL_MAX_PER_DAY,
      },
    }
  }
  /**
   * A3：申诉。只收「一条具体判定 + 新的物理证据」，每天 2 次；
   * 判还是由 wall_auditor 算，这里不自己判，也不许它改主意。
   */
  function wallAppealRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const entries = readLedger(home(), 2000)
    const left = appealLeft(entries, zoneStartOfDay(now, tz))
    const refuse = (code) => ({ v: 1, ok: false, error: { code, message: appealVoice(code) } })
    if (left <= 0) return refuse('appeal:no-quota')
    const at = Number(body?.at) || 0
    const target = entries.find((e) => Number(e.at) === at && (e.state === 'verdict' || e.state === 'wall-audit'))
    if (!target) return refuse('appeal:not-verdict')
    // 「新的」= 这条判定下没交过的同一份证据。说第二遍不算新证据。
    const evidence = String(body?.evidence || '').trim()
    const said = entries.filter((e) => e.state === 'appeal' && Number(e.target) === at).map((e) => String(e.evidence || ''))
    if (!evidence || said.includes(evidence)) return refuse('appeal:no-new-evidence')
    const r = auditTask(
      { task: String(body?.task || target.kind || ''), evidence },
      {
        now, since: at, notesDir: notesDir(), stat: statOf,
        screenshots: listScreenshots(SCREENSHOT_DIRS, at),
        usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [],
        baseline: body?.baseline ? { path: String(body.baseline.path || ''), size: Number(body.baseline.size) || 0 } : undefined,
      },
    )
    record({ state: 'appeal', at: now, target: at, evidence, code: r.code, pass: r.pass })
    if (r.pass) releaseWithdrawalOnAction({ wallPassed: true, actionHappened: true }, now)
    return {
      v: 1, ok: true,
      appeal: { code: r.code, pass: r.pass, text: verdictVoice(r.code, r.vars), left: left - 1, appealLeft: left - 1 },
    }
  }
  // wallBossBriefRoute / wallBossStatusSnapshot / wallBossChatRoute 已拆至 wall_boss_runtime.js
  /**
   * 本周还剩几天放风额度——四部门没交齐的当天归零。
   *
   * 这是「每天必须交」的硬约束落点：不看聊天、不看模型，只看 deposit.json 里今天齐不齐。
   * 所有报额度的地方都走这一个，免得界面说还剩 2 天、实际却放不出来。
   */
  function relaxRemainingNow(now, tz) {
    if (!completeOf(depositToday(now))) return 0
    return relaxRemaining(config, now, tz)
  }

  /**
   * 每日四部门提交（第五轮 `POST /deposit`）。
   *
   * action=status：今天交了几段、还缺哪几段（界面和墙都用这一个）。
   * action=submit：交一段，body 里带 department + report + thought。字数不到当场退回。
   *
   * 只收自己的内容，只回自己的状态：这里不放宽任何规则，也不影响墙的锁。
   */
  function depositRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const action = String(body?.action || 'status')
    const view = () => {
      const s = statusOf(depositToday(now))
      return { v: 1, ok: true, deposit: { ...s, missingText: missingText(depositToday(now)), departments: DEPARTMENTS } }
    }
    // A4：白天的记录槽。一条 ≤200 字，带时间戳和关联任务；按部累加。
    if (action === 'slot') {
      const r = appendSlot(depositToday(now), { key: String(body?.department || '').trim(), text: body?.text, at: now, taskId: body?.taskId })
      if (!r.ok) return { ...view(), ok: false, error: { code: 'bad-slot', message: r.text } }
      saveDepositState(r.state)
      record({ state: 'deposit-slot', at: now, department: String(body?.department || '').trim(), chars: String(body?.text || '').trim().length })
      return { ...view(), ok: true, text: r.text }
    }
    if (action !== 'submit') return view()
    const key = String(body?.department || '').trim()
    if (!DEPARTMENTS.some((d) => d.key === key)) {
      return { ...view(), ok: false, error: { code: 'bad-department', message: '没有这个部门：' + key } }
    }
    const today = depositToday(now)
    // A4：行为管理部的汇报栏由墙生成——用户交的 report 整个不看，只用遥测那一份。
    const auto = DEPT_DUTY[key].auto ? behaviorReport({ usage: Array.isArray(lastPhone?.today) ? lastPhone.today : [], verdicts: readLedger(home(), 200).filter((e) => e?.state === 'verdict' && Number(e.at) >= zoneStartOfDay(now, tz)) }) : null
    const norm = normalizeEntry({ report: body?.report, thought: body?.thought }, now, { auto })
    if (!norm.ok) return { ...view(), ok: false, error: { code: 'too-thin', message: norm.text } }
    // A4 晚间对照：汇报里提到的事得在今天的记录槽或证据来源里找到对应，找不到就标「无据」退回。
    // 证据来源按部取，只摆今天真有的东西：信息收集/谋划思考看今天动过的笔记，统筹规划看当天的时间表。
    const evidence = (() => {
      if (key === 'coord') {
        const p = dailyToday(now).plan || {}
        return [...(Array.isArray(p.blocks) ? p.blocks : []).map((b) => String(b.task || '')), ...(Array.isArray(p.reschedules) ? p.reschedules : []).map((r) => String(TRIGGER_TEXT[r.trigger] || r.trigger || ''))]
      }
      return vaultRecentNotes(notesDir(), { limit: 8, now }).filter((n) => Number(n.mtime) >= zoneStartOfDay(now, tz)).map((n) => String(n.summary || n.rel || ''))
    })()
    const rec = reconcileEntry(today, key, norm.entry.report, { evidence })
    if (rec.unsupported) return { ...view(), ok: false, error: { code: 'unsupported', message: '这一段无据，退回了：' + rec.why } }
    const next = withEntry(today, key, norm.entry)
    saveDepositState(next)
    record({ state: 'deposit-submit', at: now, department: key, auto: Boolean(auto), reconciled: rec.ok })
    return { ...view(), ok: true, text: '交了「' + (DEPARTMENTS.find((d) => d.key === key) || {}).label + '」' + (rec.ok ? '，和今天的记录对上了（' + rec.why + '）' : '。') }
  }

  // ---------------------------------------------------------------- C1 工作记忆挂载卡
  /** 卡最多留这么多张（停着的也要能恢复，所以不按张数清空，只留最近几张）。 */
  const MOUNT_KEEP = 12
  const loadMount = () => {
    const raw = readState(stateFile('mount.json'), null)
    return {
      v: 1,
      cards: (Array.isArray(raw?.cards) ? raw.cards : []).map(normalizeCard).filter(Boolean).slice(-MOUNT_KEEP),
      streak: Math.max(0, Number(raw?.streak) || 0),
      askAt: Number(raw?.askAt) || 0,
      pending: raw?.pending && typeof raw.pending === 'object' ? raw.pending : null,
    }
  }
  const saveMount = (m) => writeJson(stateFile('mount.json'), { ...m, cards: (m.cards || []).slice(-MOUNT_KEEP) })
  const openCardOf = (m) => (m?.cards || []).find((c) => c.status === 'open') || null
  const lastCardOf = (m) => (m?.cards || [])[m.cards.length - 1] || null
  // 关掉的是「当前这张」：开着就关开着的，停着就关停着的（关掉之后只读）。
  const closeCardOf = (m) => openCardOf(m) || lastCardOf(m)
  const putCard = (m, card) => ({ ...m, cards: m.cards.map((c) => (c.id === card.id ? card : c)).slice(-MOUNT_KEEP) })
  /** C1：工作记忆漏水是本轮第一条支线——配置里没写过就兜底。
   *  要关掉它得写一条 `enabled:false`（空数组当「没配」，因为默认配置本来就是 []）。 */
  const configuredTracks = () => (Array.isArray(config.tracks) && config.tracks.length ? config.tracks : [MEMORY_LEAK_TRACK])
  /**
   * C1：挂载卡的手机入口（浮层小卡 + 面板）。
   *
   * 只认他自己的话：面板上写的就是他写的（`sources` 传空数组，那是 isUserText 给手写留的那条路），
   * 工具提议必须带账本行号。这里不生成任何文字——卡里每一条都是他自己的字。
   */
  function mountRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const action = String(body?.action || 'status')
    let m = loadMount()
    const view = () => {
      // 视图一律现读账本：scaffold 要算上刚才那一条，不然面板永远慢一拍。
      const entries = readLedger(home(), 800)
      const last = lastCardOf(m)
      return {
        v: 1, ok: true,
        mount: {
          ...mountStatus(last, entries),
          // 恢复要按 id 指认是哪一张，所以当前这张的 id 得带上。
          id: last ? last.id : '',
          ask: m.askAt > 0 && now - m.askAt < 30 * 60_000,
          pending: m.pending ? { text: m.pending.text, slot: m.pending.slot, src: m.pending.src } : null,
          cards: m.cards.map((c) => ({ id: c.id, status: c.status, goal: c.goal.text, parkedWhy: c.parkedWhy })),
        },
      }
    }
    const fail = (code, message) => ({ ...view(), ok: false, error: { code, message } })
    // 改完立刻落盘，视图从落盘后的状态渲染——免得界面和盘上不一致。
    const commit = (next) => {
      m = next
      saveMount(m)
      return view()
    }
    if (action === 'status') return view()
    if (action === 'open') {
      // openCard 认的是「一张卡」的形状（{ card }），不是整个 mount 状态。
      const r = openCard({ card: openCardOf(m) }, { topicId: body?.topicId, goal: body?.goal, at: now, sources: [] })
      if (!r.ok) return fail(r.code, r.text)
      record({ state: 'mount', at: now, action: 'open', by: 'user', slot: 'goal' })
      return commit({ ...m, cards: [...m.cards, r.card].slice(-MOUNT_KEEP), streak: 0, askAt: 0 })
    }
    if (action === 'drop-proposal') return commit({ ...m, pending: null })
    // 断链检测的显式入口：`text` 就是他这一句原话。插件拿不到聊天消息，只能由拿得到的那一层来调。
    if (action === 'seen') {
      const cur = openCardOf(m)
      const d = driftOf(cur, body?.text)
      const show = Boolean(cur) && shouldShowCard(m.streak, d.drift)
      const next = { ...m, streak: show ? 0 : nextStreak(m.streak, d.drift), askAt: show ? now : m.askAt }
      return { ...commit(next), drift: { drift: d.drift, hit: d.hit, need: d.need, ask: show, streak: next.streak } }
    }
    // 恢复要在「没有 open 卡」的时候才成立，所以它排在 no-card 那个判断前面。
    if (action === 'resume') {
      const want = m.cards.find((c) => c.id === String(body?.id || '')) || null
      if (!want) return fail('no-card', '没有这张卡。')
      if (openCardOf(m)) return fail('card-open', '已经有一张开着的卡，先停放或关掉它。')
      const r = resumeCard(want, { at: now })
      if (!r.ok) return fail(r.code, r.text)
      record({ state: 'mount', at: now, action: 'resume', by: 'user' })
      return commit(putCard(m, r.card))
    }
    const cur = openCardOf(m)
    if (!cur && action !== 'close') return fail('no-card', '现在没有开着的卡：先开一张。')
    if (action === 'given' || action === 'step') {
      const r = action === 'given'
        ? withGiven(cur, { text: body?.text, at: now, src: body?.src, sources: [] })
        : withStep(cur, { text: body?.text, at: now, from: body?.from, sources: [] })
      if (!r.ok) return fail(r.code, r.text)
      record({ state: 'mount', at: now, action: 'add', by: 'user', slot: action })
      return commit(putCard(m, r.card))
    }
    if (action === 'accept') {
      if (!m.pending) return fail('no-proposal', '没有待确认的提议。')
      const r = acceptProposal(cur, m.pending, { at: now, from: body?.from })
      if (!r.ok) return fail(r.code, r.text)
      record({ state: 'mount', at: now, action: 'add', by: 'her', slot: m.pending.slot })
      return commit({ ...putCard(m, r.card), pending: null })
    }
    if (action === 'park') {
      const r = parkCard(cur, { at: now, where: body?.where })
      if (!r.ok) return fail(r.code, r.text)
      record({ state: 'mount', at: now, action: 'park', by: 'user' })
      return commit(putCard(m, r.card))
    }
    if (action === 'close') {
      const r = closeCard(closeCardOf(m), { at: now })
      if (!r.ok) return fail(r.code, r.text)
      record({ state: 'mount', at: now, action: 'close', by: 'user' })
      return commit({ ...putCard(m, r.card), streak: 0, askAt: 0 })
    }
    return fail('bad-action', 'status|open|seen|given|step|accept|park|resume|close|drop-proposal')
  }
  /**
   * C1：提议挂载。只能把他自己的原话挂上去，而且必须指出账本行号——
   * 没有出处的提议不受理，因为「她总结他刚才说了什么」正是要防的那件事。
   * 他点头之后才落卡（浮层里点一下），这里只提议。
   */
  function mountProposeTool(role) {
    return {
      name: 'mount_propose',
      description: '把他说过的原话提议挂到工作记忆卡上（他不点头不落卡）。src 必须是他那句话在账本里的行号；' +
        'slot=given 是已知条件，slot=step 是中间结论（还要 from 指明由哪几条已知推出）。' +
        '你只能提议、不能替他总结：text 必须是他的原话。他越自己挂卡，你每天能提议的次数越少。',
      parameters: {
        type: 'object',
        properties: {
          text: { type: 'string', description: '他的原话，≤120 字' },
          slot: { type: 'string', description: 'given（已知条件）/ step（中间结论）' },
          src: { type: 'string', description: '账本行号，例：128' },
          from: { type: 'array', items: { type: 'integer' }, maxItems: 5, description: 'step 必填：由哪几条已知（下标 0 起）推出' },
        },
        required: ['text', 'slot', 'src'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        // 行号按整份账本从头数，不随读取窗口滑动（和 revise_stance 同一口径）。
        const rows = readLedger(home(), 5000).map((e, i) => ({ ...e, seq: i + 1 }))
        const seq = Number(args.src)
        const hit = Number.isInteger(seq) && seq > 0 ? rows[seq - 1] : null
        if (!hit || typeof hit.text !== 'string') return { ok: false, text: '账本里没有第 ' + String(args.src || '') + ' 行原话：提议必须有出处。' }
        const cap = proposeCap(scaffold(rows).ratio)
        if (cap <= 0) return { ok: false, text: '今天不再提议了：他一直在自己挂卡，你该少插手。' }
        const used = rows.filter((e) => e?.state === 'mount-propose' && Number(e.at) >= zoneStartOfDay(now, zoneOr(config.timeZone))).length
        if (used >= cap) return { ok: false, text: '今天的提议额度用完了（' + cap + ' 次）：剩下的让他自己挂。' }
        const r = proposeMount({ text: args.text, slot: args.slot, at: now, src: String(seq), sources: [stripInjection(hit.text)] })
        if (!r.ok) return { ok: false, text: r.text }
        saveMount({ ...loadMount(), pending: r.proposal })
        record({ state: 'mount-propose', at: now, by: role, slot: r.proposal.slot, src: String(seq) })
        return { ok: true, text: '提议挂到「' + (r.proposal.slot === 'given' ? '已知条件' : '中间结论') + '」：' + r.proposal.text + '（他点头才落卡）' }
      },
    }
  }
  /** C1：停放。开新卡前把旧卡停在哪说一句，不许无声息地扔下。 */
  function mountParkTool(role) {
    return {
      name: 'mount_park',
      description: '把当前这张工作记忆卡停起来，一句话说清停在哪（下次回来才知道从哪接）。' +
        '停着的卡他随时能恢复；要彻底不做才用 mount_close（关掉就只读了）。',
      parameters: {
        type: 'object',
        properties: { where: { type: 'string', description: '停在哪：他推到哪一步、回来从哪接' } },
        required: ['where'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const m = loadMount()
        const r = parkCard(openCardOf(m), { at: now, where: args.where })
        if (!r.ok) return { ok: false, text: r.text }
        saveMount(putCard(m, r.card))
        record({ state: 'mount-park', at: now, by: role })
        return { ok: true, text: '停好了：' + r.card.parkedWhy + '。他回来点恢复就能接着推。' }
      },
    }
  }
  /** C1：关掉。关掉之后只读，卡留着能查，不再进每轮注入。 */
  function mountCloseTool(role) {
    return {
      name: 'mount_close',
      description: '关掉当前的工作记忆卡：关掉之后只读，卡还在但不再进每轮注入。他没做完的事用 mount_park。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: toolOutput,
      execute: () => {
        const now = Date.now()
        const m = loadMount()
        const r = closeCard(closeCardOf(m), { at: now })
        if (!r.ok) return { ok: false, text: r.text }
        saveMount({ ...putCard(m, r.card), streak: 0, askAt: 0 })
        record({ state: 'mount-close', at: now, by: role })
        return { ok: true, text: '关了。卡还在（' + r.card.givens.length + ' 条已知、' + r.card.steps.length + ' 条结论），以后查得到。' }
      },
    }
  }

  function eventRoute(body) {
    const now = Date.now()
    const type = String(body?.type || '')
    if (type === 'overlay-closed') {
      // busy = 点了「在忙，晚点回」（C4）：不算回过话、不开安静期，只记一笔。
      record({ state: 'overlay-closed', at: now, chatted: body?.chatted === true, muted: body?.muted === true, busy: body?.busy === true || undefined, app: typeof body?.app === 'string' ? body.app.slice(0, 120) : undefined })
      return { v: 1, ok: true }
    }
    if (type === 'disarm') {
      // 2026-11-19 H2：统一走放行账（支持 disarmId 幂等同步；notification / gemini-defer 共用每日上限，adb 不计次）。
      const tz = zoneOr(config.timeZone)
      const day = dayString(now, tz)
      const info = intensityStateNow(now, tz)
      const disarmId = typeof body?.disarmId === 'string' && body.disarmId.trim()
        ? body.disarmId.trim().slice(0, 80)
        : (typeof body?.eventId === 'string' && body.eventId.trim() ? body.eventId.trim().slice(0, 80) : '')
      const counted = body?.counted !== false
      const source = String(body?.source || 'disarm').slice(0, 32)
      const reqReason = String(body?.reason || '').trim().slice(0, 120) || (counted ? '紧急解除' : 'adb 解除')
      const plRes = requestPass(loadPassLedger(now, tz), 'emergency', {
        source,
        disarmId,
        reason: reqReason,
        until: Number(body?.until) || 0,
        counted,
        cap: info.emergencyDisarmDailyCap,
        now,
        day,
      })
      if (plRes.idempotent) {
        return sendSuccessDisarm(plRes)
      }
      if (!plRes.ok) {
        return {
          v: 1,
          ok: false,
          error: { code: plRes.error, message: plRes.why },
          countToday: plRes.countToday,
          left: plRes.left,
          cap: plRes.cap,
        }
      }
      savePassLedger(plRes.state)
      const ext = loadExternal()
      saveExternal({
        ...ext,
        emergencyPass: {
          active: true,
          grantedAt: now,
          until: plRes.until,
          reason: plRes.why,
          day,
          countToday: plRes.countToday,
        },
      })
      try {
        presetRt.strictOff(now, 'disarm')
        lockBus.revokeBySource('takeover', { now })
        lockBus.revokeBySource('contract', { now })
        lockBus.revokeBySource('radar', { now })
        lockBus.revokeBySource('switch', { now })
        lockBus.revokeBySource('sister', { now })
      } catch {}
      record({
        state: 'disarm',
        at: now,
        ...(disarmId ? { disarmId } : {}),
        source,
        reason: plRes.why,
        until: plRes.until,
        counted,
      })
      return sendSuccessDisarm(plRes)
    }
    function sendSuccessDisarm(plRes) {
      return {
        v: 1,
        ok: true,
        idempotent: Boolean(plRes.idempotent),
        countToday: plRes.countToday,
        left: plRes.left,
        cap: plRes.cap,
        until: plRes.until,
        why: plRes.why,
      }
    }
    if (type === 'voice-out') {
      // B5：手机报"这一条到底念出来没有"。念了就 ok，没念就带降级原因——这是唯一能知道
      // 语音有没有真的响过的来源，插件自己不知道手机的音量和勿扰。
      // 这条不进 silence/emotion 的任何判断（它们只认 generated / replied / human / said），
      // 所以记在同一个 requestId 下面也不会被当成"回过话"。
      record({
        state: 'voice-out',
        at: now,
        requestId: typeof body?.requestId === 'string' ? body.requestId.slice(0, 128) : undefined,
        ok: body?.ok === true,
        why: body?.ok === true ? undefined : String(body?.why || 'unknown').slice(0, 20),
        chars: Number.isFinite(Number(body?.chars)) ? Math.max(0, Math.min(200, Number(body.chars))) : undefined,
        rate: Number.isFinite(Number(body?.rate)) ? Math.round(Number(body.rate) * 100) / 100 : undefined,
        pitch: Number.isFinite(Number(body?.pitch)) ? Math.round(Number(body.pitch) * 100) / 100 : undefined,
      })
      return { v: 1, ok: true }
    }
    if (type === 'defied') {
      const app = typeof body?.app === 'string' ? body.app.slice(0, 120) : ''
      const label = typeof body?.label === 'string' ? body.label.slice(0, 40) : app
      const mins = effectiveMode(config, now) === 'coach' ? 60 : 30
      const until = Math.max(now + mins * 60_000, Number(config.refuseUntil) || 0)
      commit({ refuseUntil: until, refuseBy: config.refuseBy || 'defied', muteUntil: 0 })
      pendingDefiance = { app, label, at: now, until, source: String(body?.source || 'overlay').slice(0, 20) }
      record({ state: 'defied', at: now, app, label, until, source: pendingDefiance.source })
      // 违抗打开追问锁（原因：违抗）。挂着没问过的义务趁这次一并问掉（下次违抗时再问）。
      const re = takeReask(config.obligations, 'defiance', now)
      if (re.ask) commit({ obligations: re.obligations })
      askViaGemini(re.ask ? re.ask.kind : 'defiance', re.ask ? re.ask.text : '刚关掉提醒又打开了' + label + '，为什么？接下来怎么办？', now)
      // 收尾的话由墙说（代码模板），不经模型、不由姐姐出面。
      return { v: 1, ok: true, refuse: { until }, force: { level: 4, app, why: wallDefianceText(label, until) } }
    }
    // 锁屏页 / 无障碍报来的（DeepSeek 那边接）：
    //   tamper     —— 管控期间他去设置里找"强行停止 / 卸载 / 关无障碍"：信用分清零，拒绝模式 60 分钟，鲸澜收尾。
    //   breakglass —— 走够步数 + 手打违约说明，破窗 10 分钟：照放，扣信用分，记进账本晚上要问。
    //   notif      —— 任务期间拦下的一条通知（只要 App 和标题，不要正文）：攒着，任务结束一次性简报。
    if (type === 'tamper') {
      const until = Math.max(now + 60 * 60_000, Number(config.refuseUntil) || 0)
      commit({ refuseUntil: until, refuseBy: config.refuseBy || 'tamper', muteUntil: 0 })
      const what = typeof body?.what === 'string' ? body.what.slice(0, 60) : '设置'
      pendingDefiance = { app: 'settings', label: '系统设置（' + what + '）', at: now, until, source: 'tamper' }
      record({ state: 'tamper', at: now, what, until })
      return { v: 1, ok: true, refuse: { until }, force: { level: 4, app: String(body?.app || ''), why: '想去设置里拆掉管控' } }
    }
    if (type === 'emergency-exit') {
      // 2026-11-19 出口合一：锁页长按回 DSH 的紧急退出，和紧急解除 / 破窗共用同一个每日次数。
      // 手机那边已经先放行了（本地安全阀，不等插件）；这里只记账、扣次数。次数用完也照样记一笔（超额）。
      const tz = zoneOr(config.timeZone)
      const day = dayString(now, tz)
      const info = intensityStateNow(now, tz)
      const minutes = Math.max(3, Math.min(30, Math.round(Number(body?.minutes) || 10)))
      const plRes = requestPass(loadPassLedger(now, tz), 'emergency', {
        source: 'lockpage-exit',
        reason: String(body?.reason || '锁页长按回 DSH 紧急退出').slice(0, 120),
        durationMs: minutes * 60_000,
        cap: info.emergencyDisarmDailyCap,
        now,
        day,
      })
      if (plRes.ok) savePassLedger(plRes.state)
      record({ state: 'emergency-exit', at: now, minutes, counted: Boolean(plRes.ok), overCap: !plRes.ok, left: plRes.left ?? 0 })
      return { v: 1, ok: true, counted: Boolean(plRes.ok), left: plRes.left ?? 0, cap: plRes.cap ?? info.emergencyDisarmDailyCap }
    }
    if (type === 'breakglass') {
      // H2：破窗走统一放行账，每天最多破窗 1 次 + 与其他应急口共享每日上限；不再扣信用分（保留记账与日判）。
      const tz = zoneOr(config.timeZone)
      const day = dayString(now, tz)
      const today = startOfDay(now, config.timeZone)
      const usedToday = Number(config.breakglassDay) === today ||
        readLedger(home(), 800).some((e) => e?.state === 'breakglass' && Number(e.at) >= today)
      if (usedToday) return { v: 1, ok: false, error: { code: 'breakglass-used', message: '今天已经破窗过一次' } }
      const info = intensityStateNow(now, tz)
      const minutes = Math.max(5, Math.min(15, Math.round(Number(body?.minutes) || 10)))
      const note = typeof body?.note === 'string' && body.note.trim() ? body.note.trim().slice(0, 300) : '破窗紧急解除'
      const plRes = requestPass(loadPassLedger(now, tz), 'emergency', {
        source: 'breakglass',
        reason: note,
        durationMs: minutes * 60_000,
        cap: info.emergencyDisarmDailyCap,
        now,
        day,
      })
      if (!plRes.ok) {
        return {
          v: 1,
          ok: false,
          error: { code: plRes.error, message: plRes.why },
          countToday: plRes.countToday,
          left: plRes.left,
          cap: plRes.cap,
        }
      }
      savePassLedger(plRes.state)
      const until = plRes.until
      const ext = loadExternal()
      saveExternal({
        ...ext,
        emergencyPass: { active: true, grantedAt: now, until, reason: note, day, countToday: plRes.countToday },
      })
      config = { ...config, breakUntil: until, breakSince: now, breakChecked: false, breakglassDay: today }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
      presetRt.strictOff(now, 'breakglass')
      lockBus.revokeBySource('takeover', { now })
      lockBus.revokeBySource('contract', { now })
      lockBus.revokeBySource('radar', { now })
      lockBus.revokeBySource('switch', { now })
      lockBus.revokeBySource('sister', { now })
      record({ state: 'breakglass', at: now, minutes, penalty: 0, steps: Number(body?.steps) || undefined, note: typeof body?.note === 'string' ? body.note.slice(0, 300) : undefined })
      return { v: 1, ok: true, breakUntil: until, until, penalty: 0, countToday: plRes.countToday, left: plRes.left, cap: plRes.cap }
    }
    if (type === 'notif') {
      const n = loadNotifs()
      const label = String(body?.label || body?.app || '?').slice(0, 30)
      const apps = { ...(n.apps || {}) }
      const cur = apps[label] || { count: 0, titles: [], group: 0 }
      cur.count += 1
      if (body?.group === true) cur.group += 1
      const title = typeof body?.title === 'string' ? body.title.slice(0, 40) : ''
      if (title && cur.titles.length < 5 && !cur.titles.includes(title)) cur.titles.push(title)
      apps[label] = cur
      saveNotifs({ since: n.since || now, apps })
      return { v: 1, ok: true }
    }
    if (type === 'start') {
      // 用户点「开始」（带任务和时长）：进冲刺。娱乐锁上，通知收进防波堤。
      const long = body?.long === true
      const dflt = long ? Number(config.sprintLongMin) || SPRINT_LONG_MIN : Number(config.sprintMin) || SPRINT_MIN
      const minutes = Math.max(5, Math.min(120, Math.round(Number(body?.minutes) || dflt)))
      const task = String(body?.task || '').trim().slice(0, 120)
      const until = now + minutes * 60_000
      setGear({ gear: 'busy', until, what: task || '冲刺', task: task ? { goal: task, by: 'user' } : undefined }, 'sprint')
      commit({ sprintUntil: until })
      record({ state: 'sprint-start', at: now, minutes, task: task || undefined, until })
      wallCloseOnStart(now)
      return { v: 1, ok: true, sprint: { until, minutes, task: task || null } }
    }
    if (type === 'request-review') {
      // 用户点「申请验收」：进验收（追问锁，原因验收）。
      const lock = openFollowup('review', '这一轮做完了吗？拿完成物说话。', now)
      return { v: 1, ok: true, followup: followupView(lock, now) }
    }
    if (type === 'screen-on') {
      // 亮屏：只记账。晨间门的开关仍由 /plan 的作息判定，不在这儿抢。
      record({ state: 'screen-on', at: now })
      return { v: 1, ok: true }
    }
    if (type === 'gemini-end') {
      // 连麦结束（带时长）：满 3 分钟才算完成，接着出追问（连麦回流）；不足回待命。
      const minutes = Math.max(0, Math.round(Number(body?.minutes) || 0))
      const min = Math.max(1, Math.round(Number(config.brainMinMin) || 3))
      const tz = zoneOr(config.timeZone)
      saveGemini({ ...loadGemini(), session: null })
      saveGeminiGate(markGeminiSessionEnded(loadGeminiGate(now, tz), { now, day: dayString(now, tz) }))
      record({ state: 'gemini-end', at: now, minutes, by: String(body?.by || 'phone').slice(0, 20) })
      if (minutes >= min) {
        // 2026-11-20：连麦本身就该出回执（姐姐会读回【复盘】）。先等回执 10 分钟，不立刻落追问锁；
        // 等不到才落锁（tickFollowupGemini）。连麦里已经出过回执的，直接不追问。
        const since = now - minutes * 60_000
        const already = readLedger(home(), 200).some((e) => e?.state === 'gemini-recap' && e.receipt === true && Number(e.at) >= since)
        if (!already && !config.followupGemini) {
          commit({ followupGemini: { reason: 'gemini', question: '刚才连麦说到哪、结论是什么？', since, until: now + FOLLOWUP_GEMINI_MS } })
          record({ state: 'followup-gemini', at: now, reason: 'gemini', wait: 'receipt' })
        }
        return { v: 1, ok: true, counted: true, followup: followupView(config.followup, now), awaitingReceipt: !already }
      }
      return { v: 1, ok: true, counted: false, followup: followupView(config.followup, now) }
    }
    if (type === 'followup-timeout') {
      // 手机本地到点，告诉插件一声（插件每次 /plan 也会兜一次，谁先到都算）。
      const l = config.followup
      if (!l || !lockExpired(l, now)) return { v: 1, ok: true, expired: false }
      expireFollowup(now)
      return { v: 1, ok: true, expired: true, followup: followupView(config.followup, now), obligations: obligationsView(config.obligations, now) }
    }
    if (type === 'wechat-hit') {
      // 微信里误入视频号：手机连续停留 3 秒、离开那一页时（去哪都算，灭屏也算）上报。插件只记账，不升级成违抗。
      record({ state: 'wechat-hit', at: now, page: String(body?.page || '').slice(0, 60), dwellMs: Math.max(0, Math.round(Number(body?.dwellMs) || 0)) })
      return { v: 1, ok: true }
    }
    if (type === 'blocked-open' || type === 'intercept-hit') {
      const appPkg = String(body?.app || body?.pkg || '').trim()
      if (appPkg) {
        recordLastIntercept({
          pkg: appPkg,
          label: body?.label,
          reason: body?.reason,
          handId: body?.handId,
          handName: body?.handName,
          at: body?.at,
        }, now)
        record({ state: 'intercept-hit', at: now, app: appPkg.slice(0, 120), reason: String(body?.reason || '').slice(0, 120) })
      }
      if (type === 'intercept-hit') return { v: 1, ok: true, recorded: Boolean(appPkg) }
      // 他打开被拦的 App：挂着的义务按用户的动作再问一次（不定时自动弹）。
      const r = takeReask(config.obligations, 'locked-app', now)
      if (!r.ask) return { v: 1, ok: true, ask: false }
      commit({ obligations: r.obligations })
      const lock = askViaGemini(r.ask.kind, r.ask.text, now).lock
      record({ state: 'obligation-reask', at: now, kind: r.ask.kind, app: String(body?.app || '').slice(0, 120) })
      return { v: 1, ok: true, ask: true, followup: followupView(lock, now) }
    }
    if (type === 'clock-fire') {
      const taskId = String(body?.taskId || '').slice(0, 64)
      record({
        state: 'clock-fire-phone',
        at: now,
        taskId,
        kind: String(body?.kind || ''),
        action: String(body?.action || ''),
        cycleIndex: Number(body?.cycleIndex) || undefined,
        phaseName: String(body?.phaseName || ''),
      })
      clockKernel.tick(now)
      return { v: 1, ok: true }
    }
    if (type === 'pc-activity' || type === 'pc_heartbeat') {
      const saved = {
        at: Number(body?.at) || now,
        receivedAt: now,
        isAfk: Boolean(body?.isAfk),
        afkStatus: String(body?.afkStatus || '').slice(0, 32),
        activeApp: String(body?.activeApp || '').slice(0, 80),
        activeTitle: String(body?.activeTitle || '').slice(0, 120),
        category: String(body?.category || 'other').slice(0, 32),
        focusMinutes: Math.max(0, Math.round(Number(body?.focusMinutes) || 0)),
        entertainmentMinutes: Math.max(0, Math.round(Number(body?.entertainmentMinutes) || 0)),
        switches: Math.max(0, Math.round(Number(body?.switches) || 0)),
      }
      savePcHeartbeat(saved)
      record({
        state: 'pc-heartbeat',
        at: now,
        isAfk: saved.isAfk,
        activeApp: saved.activeApp,
        category: saved.category,
        focusMinutes: saved.focusMinutes,
        entertainmentMinutes: saved.entertainmentMinutes,
      })
      return { v: 1, ok: true, pc: saved }
    }
    return { v: 1, ok: false, error: { code: 'unknown-event' } }
  }
  /** 任务期间拦下的通知，整理成一句简报（取完就清）。 */
  function takeDigest() {
    const n = loadNotifs()
    const apps = Object.entries(n.apps || {})
    if (!apps.length) return ''
    saveNotifs({})
    return '刚才拦下的通知：' + apps.sort((a, b) => b[1].count - a[1].count).slice(0, 6)
      .map(([label, v]) => label + ' ' + v.count + ' 条' + (v.group ? '（群聊 ' + v.group + '）' : '') + (v.titles.length ? '：' + v.titles.slice(0, 3).join('、') : '')).join('；') + '。'
  }
  /**
   * 第二轮撤掉了「没人接就每 10 分钟追到回话」的追人阶梯：压力归墙，姐妹不催。
   * 她们照常关心（注意到他刷太久会说一句），只是不会为了等他回话而反复来。
   */
  /** 未完成笔记追杀：他有空（闲时、不在玩、亮屏、9–22 点），三小时最多一次，挑一篇没想完的。 */
  function chaseSignal(phone, now, tz) {
    const dir = notesDir()
    if (!dir || !phone?.screenOn || phone.fun) return null
    const min = localMinute(phone, zoneMinuteOfDay(now, tz))
    if (min < 9 * 60 || min >= 22 * 60) return null
    const gs = loadGearState()
    if (now - (Number(gs.chaseAt) || 0) < 3 * 3_600_000) return null
    const asked = (Array.isArray(gs.chased) ? gs.chased : []).filter((c) => now - Number(c.at) < 7 * 86_400_000).map((c) => c.path)
    const found = scanUnfinished(dir, { now, asked })
    return found[0] || null
  }
  /** 第二轮撤掉了追人阶梯（没人接不再每 10 分钟来一次）；姐妹正常的关心走 restless / patrol / busy-done。 */
  /**
   * 心猿意马：90 秒里切了 5 次以上、至少 4 个不同的 App，人还不在娱乐 App 里（在里面的话力度会管）。
   * 鱼丸在他点开游戏之前截胡。20 分钟最多一次。
   */
  function restlessSignal(phone, now) {
    const sw = phone?.switches
    if (!sw || sw.in90s < 5 || sw.apps90s.length < 4 || phone.fun) return null
    if (now - (Number(loadGearState().restlessAt) || 0) < 20 * 60_000) return null
    return { seconds: 90, switches: sw.in90s, apps: sw.apps90s.slice(0, 5).join('、') }
  }
  /** 最近的前台切换（巡逻用：来回刷手机）。 */
  const fronts = []
  function noteFront(phone, now) {
    if (!phone?.app) return
    if (!fronts.length || fronts[fronts.length - 1].app !== phone.app) fronts.push({ app: phone.app, fun: phone.fun === true, at: now })
    while (fronts.length && now - fronts[0].at > 30 * 60_000) fronts.shift()
  }
  /** 该不该巡逻一次：来回刷（15 分钟里切了 8 次以上）、今天娱乐超过 90 分钟还在玩、或者到了巡逻时段。 */
  function patrolSignal(phone, now, tz) {
    const gs = loadGearState()
    if (now - (Number(gs.patrolAt) || 0) < 25 * 60_000) return null
    const recent = fronts.filter((f) => now - f.at <= 15 * 60_000)
    // 手机自己数的切换次数更准（/plan 有节流，插件这边会漏）。
    const sw = phone?.switches
    const switches = sw ? sw.in15m : recent.length
    const distinct = sw ? sw.distinct15m : new Set(recent.map((f) => f.app)).size
    if (switches >= 8) return { why: '他 15 分钟里在 ' + distinct + ' 个 App 之间来回切了 ' + switches + ' 次，像是在无目的地刷' }
    const fun = funToday(phone)
    const activeBout = runningBoutNow()
    if (phone?.fun && (fun >= 45 || (!activeBout && fun >= 30))) {
      return { why: '他今天娱乐已经 ' + fun + ' 分钟了，现在还在' + (phone.label || phone.app) }
    }
    if (phone?.screenOn && !phone.fun && !activeBout && Number(phone.minutes) >= 20) {
      return { why: '他亮屏停留 ' + Number(phone.minutes) + ' 分钟但尚未开局，适合主动问一句或帮他开一局' }
    }
    const hour = phone?.local ? Number(phone.local.slice(0, 2)) : Math.floor(zoneMinuteOfDay(now, tz) / 60)
    // 白天每小时左右巡检一次，主动发话并把未决的事推成局（21 点留给晚间对账）。
    const slots = Array.isArray(config.patrolHours) ? config.patrolHours.map(Number) : [8, 9, 10, 11, 13, 14, 15, 16, 17, 19, 20]
    const day = new Date(now).toISOString().slice(0, 10)
    const slotKey = day + ':' + hour
    if (slots.includes(hour) && gs.patrolSlot !== slotKey && phone?.screenOn) return { why: '到了 ' + hour + ' 点的巡逻时段，看看他该干的事动了没有', slot: slotKey }
    return null
  }
  /** 发球前的"互动时间线"：她们别再说"你一小时没理我"。 */
  function contactLines(entries, now) {
    let human = 0
    let closed = null
    let ball = 0
    for (const e of entries) {
      if (!e) continue
      if ((e.state === 'human' || e.state === 'said') && Number(e.at) > human) human = Number(e.at)
      if (e.state === 'overlay-closed' && (!closed || Number(e.at) > Number(closed.at))) closed = e
      if (e.state === 'generated' && Number(e.at) > ball) ball = Number(e.at)
    }
    const ago = (at) => {
      const m = Math.round((now - at) / 60_000)
      return m < 1 ? '不到 1 分钟前' : m < 120 ? m + ' 分钟前' : Math.round(m / 60) + ' 小时前'
    }
    const lines = []
    if (human) lines.push('他最后一次跟你们说话：' + ago(human) + '。')
    if (closed && Number(closed.at) > ball - 60_000) lines.push('他' + ago(Number(closed.at)) + '自己关掉了聊天窗' + (closed.chatted ? '（聊了几句之后）' : '（没回就关了）') + (closed.muted ? '，还点了"别烦我"' : '') + '。')
    if (ball) lines.push('上一次你们主动找他：' + ago(ball) + '。')
    if (lines.length) lines.push('按这条时间线说话：他刚聊过就别说"好久没理我"；他刚关窗就别装没发生。')
    return lines
  }
  /** 去：给 Gemini 的背景 + 怎么送过去。 */
  function geminiLiveTool(role) {
    return {
      name: 'gemini_live',
      description: '把他送去跟 Gemini 连麦（他有 Gemini App，Live 能实时语音、看屏幕）。生成一段给 Gemini 的背景（他是谁、这次干嘛、怎么盯他、结束怎么复盘），' +
        '返回背景全文和送过去的步骤（写剪贴板、打开 Gemini）。mode：study 学习陪练 / think 共脑想事 / interrogate 审讯摸鱼。连麦期间他跑去娱乐 App 会被锁。',
      parameters: {
        type: 'object',
        properties: {
          topic: { type: 'string', description: '这次要做什么（越具体越好）' },
          minutes: { type: 'integer', minimum: 5, maximum: 180, description: '连多久，默认 30' },
          mode: { type: 'string', enum: ['study', 'think', 'interrogate'] },
        },
        required: ['topic'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const minutes = Math.max(5, Math.min(180, Math.round(Number(args.minutes) || 30)))
        const mode = geminiMode(args.mode)
        const topic = String(args.topic || '').trim().slice(0, 120)
        const effMode = effectiveMode(config, now)
        let about = personaLines(mergePersona(readCorePersona(home()), config.persona), effMode).filter((l) => !/^现在是(伙伴|教练)模式/.test(l))
        const db = openMemoryDb(memoryPath())
        try {
          about = [...about, ...backgroundFacts(loadCandidates(db, { sessionId: config.sessionId, now }), 5)]
        } catch {
          // 记忆读不到就少几条背景
        } finally {
          try {
            db?.close?.()
          } catch {
            // ignore
          }
        }
        const g = loadGemini()
        const recaps = Array.isArray(g.recaps) ? g.recaps : []
        const brief = geminiBrief({
          owner: String(config.owner || '他'), topic, minutes, mode, about,
          commitments: readCommitments(home(), now, 3).map((c) => c.statement),
          recap: recaps[recaps.length - 1] || null,
          gear: effectiveGear(config, now) === 'busy' ? config.gearWhat : null,
          ...geminiExtras(now),
        })
        const ld = loadLadderDoc()
        if (ld.cell || ld.cellUntil) saveLadderDoc({ ...ld, cell: '', cellUntil: 0 })
        requestGeminiViaGate({ source: 'tool', mode: 'manual', topic, minutes, geminiMode: mode, brief, now })
        saveGemini({ ...g, session: { topic, mode, minutes, startedAt: now, until: now + minutes * 60_000, by: role } })
        record({ state: 'gemini-start', at: now, topic, mode, minutes, by: role })
        try {
          v4Hub?.recordStoryline?.({ kind: 'gemini_start', actor: 'gemini', summary: `开启 Gemini 连麦「${topic || '共脑'}」（${minutes}m）`, at: now }, now)
        } catch {}
        return { ok: true, text: '背景：\n' + brief + '\n\n下一步：\n' + geminiSteps(), brief }
      },
    }
  }
  /** D4：预约一次 Gemini Live。到点由手机取 /gemini/due 的 launch（背景简报），打开 Gemini 进共舱。 */
  function scheduleGeminiLiveTool(role) {
    return {
      name: 'schedule_gemini_live',
      description: '预约一次 Gemini Live 连麦：到点自动生成背景简报、打开 Gemini、进入共舱。' +
        '用 delayMinutes（几分钟后）或 at（"HH:MM" 当地时间 / ISO 时间）指定时间；topic 写清这次要做什么。',
      parameters: {
        type: 'object',
        properties: {
          delayMinutes: { type: 'integer', minimum: 1, maximum: 1440, description: '多少分钟后开始' },
          at: { type: 'string', description: '开始时间：HH:MM（当地时间，下一次）或 ISO 时间' },
          topic: { type: 'string', description: '这次连麦要做什么（越具体越好）' },
          minutes: { type: 'integer', minimum: 5, maximum: 180, description: '连多久，默认 30' },
          mode: { type: 'string', enum: ['study', 'think', 'interrogate'] },
        },
        required: ['topic'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const topic = String(args.topic || '').trim().slice(0, 120)
        if (!topic) return { ok: false, text: '要说清这次连麦做什么。' }
        let at = 0
        if (Number.isFinite(Number(args.delayMinutes)) && Number(args.delayMinutes) > 0) {
          at = now + Math.max(1, Math.min(1440, Math.round(Number(args.delayMinutes)))) * 60_000
        } else if (typeof args.at === 'string' && args.at.trim()) {
          at = nextLocalAt(args.at, now, tz) || (Number.isFinite(Date.parse(args.at)) ? Date.parse(args.at) : 0)
        }
        if (!at || at <= now) return { ok: false, text: '时间要往以后：delayMinutes 或 at="HH:MM"。' }
        const minutes = Math.max(5, Math.min(180, Math.round(Number(args.minutes) || 30)))
        const mode = geminiMode(args.mode)
        saveGeminiSchedule({ at, topic, minutes, mode, by: role, createdAt: now, fired: false })
        record({ state: 'gemini-schedule', at: now, fireAt: at, topic, minutes, mode, by: role })
        const hm = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(at))
        return { ok: true, text: '约好了：' + hm + ' 开始，' + minutes + ' 分钟，主题「' + topic + '」。到点手机会自动把背景放进剪贴板并打开 Gemini。' }
      },
    }
  }
  /** 回：连麦读回来之后存进笔记库，两姐妹都看得到。 */
  function geminiLogTool(role) {
    return {
      name: 'gemini_log',
      description: '存下一场 Gemini 连麦的回执（读回对话之后调用）：Gemini 最后那段【复盘】原文放进 raw，代码自己抠出结论 / 下一步 / 几点前 / 证据；' +
        '读不到【复盘】就只填 summary，这场记为「无回执」，进今晚的日判。写进笔记库 Gemini/日期.md，两姐妹之后每一轮都看得到。只写真读到的，读不到别编。',
      parameters: {
        type: 'object',
        properties: {
          summary: { type: 'string', description: '做了什么（一两句）' },
          stuck: { type: 'string', description: '卡在哪' },
          next: { type: 'string', description: '下一步' },
          quotes: { type: 'array', items: { type: 'string' }, description: '他说过的关键原话，最多 3 句' },
          topic: { type: 'string' },
          raw: { type: 'string', description: 'Gemini 最后那段【复盘】的原文（读得到就照抄）' },
        },
        required: ['summary'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const receipt = parseReceipt(args.raw)
        const summary = String(args.summary || receipt?.summary || '').trim().slice(0, 400)
        if (!summary) return { ok: false, text: '复盘是空的。' }
        const g = loadGemini()
        const minutes = g.session?.startedAt ? Math.max(0, Math.round((now - Number(g.session.startedAt)) / 60_000)) : undefined
        const r = {
          at: now, by: role, summary, minutes,
          stuck: String(args.stuck || receipt?.stuck || '').trim().slice(0, 200) || undefined,
          next: String(args.next || receipt?.next || '').trim().slice(0, 200) || undefined,
          conclusion: receipt?.conclusion || undefined,
          due: receipt?.by || undefined,
          evidence: receipt?.evidence || undefined,
          receipt: receipt?.complete === true,
          quotes: (Array.isArray(args.quotes) ? args.quotes : []).map((q) => String(q).slice(0, 200)).slice(0, 3),
          topic: String(args.topic || g.session?.topic || '').slice(0, 120) || undefined,
        }
        const day = new Date(now).toISOString().slice(0, 10)
        const dir = notesDir() ? join(notesDir(), 'Gemini') : join(home(), 'companion', 'gemini')
        const file = join(dir, day + '.md')
        let wrote = ''
        try {
          mkdirSync(dir, { recursive: true })
          if (!existsSync(file)) writeFileSync(file, '# ' + day + ' · Gemini 连麦\n', 'utf8')
          appendFileSync(file, recapMarkdown(r, now), 'utf8')
          wrote = file
        } catch (error) {
          console.error('[companion] Gemini 复盘写不进 ' + file + '：' + String((error && error.message) || error))
        }
        const recaps = [...(Array.isArray(g.recaps) ? g.recaps : []), { ...r, file: wrote || undefined }].slice(-5)
        saveGemini({ ...g, recaps, session: null, recappedFor: Number(g.inSince) || g.recappedFor })
        // 回执是否完整进账本：日判按它算「这场连麦有没有产出」。
        record({ state: 'gemini-recap', at: now, by: role, file: wrote || undefined, minutes, receipt: r.receipt })
        // 2026-11-20 聊完别散场：回执里的「下一步」放进今日主线（不定时间）。没在局里时，
        // 推进器的「局之间空着 → 先问再开」和冷启动点火会把它开起来——同一条路，不另起计时器。
        if (r.receipt && String(r.next || '').trim() && !runningBoutNow()) {
          try {
            const tzL = zoneOr(config.timeZone)
            const next = String(r.next).trim()
            const cur = workStoreNow(now, tzL).store
            const up = upsertWorkItem(cur, {
              id: 'land-' + now.toString(36).slice(-6),
              source: 'sister',
              title: ('落地：' + next).slice(0, 80),
              minutes: 25,
              packet: { goal: next.slice(0, 120), first: next.slice(0, 120), ...(r.by ? { deliverable: ('几点前：' + r.by).slice(0, 160) } : {}) },
            }, { day: dayString(now, tzL), now })
            if (up.ok) {
              mirrorWorkStoreToFiles(up.state, now, tzL)
              record({ state: 'gemini-landing', at: now, next: next.slice(0, 80) })
            }
          } catch {}
        }
        try {
          v4Hub?.recordStoryline?.({
            kind: 'gemini_recap',
            actor: 'gemini',
            summary: `Gemini 复盘：${summary.slice(0, 50)}${r.next ? `；下一步：${r.next.slice(0, 24)}` : ''}`,
            at: now,
          }, now)
        } catch {}
        return { ok: true, text: wrote ? '存好了：' + wrote : '记下了（笔记文件没写进去，复盘还在，两姐妹都看得到）。' }
      },
    }
  }
  /** 提前收工：结束 Gemini 互动舱，手机恢复平时的拒绝模式 / 档位。两个人都能用。 */
  function geminiEndTool(role) {
    return {
      name: 'gemini_end',
      description: '结束正在进行的 Gemini 连麦（提前收工）。手机随即解除"只许 Gemini"的锁，恢复平时的档位和拒绝模式；连麦中的复盘照常用 gemini_log 存。',
      parameters: {
        type: 'object',
        properties: { reason: { type: 'string', description: '为什么提前结束（一句话）' } },
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const g = loadGemini()
        const ld = loadLadderDoc()
        if (!g.session && !ld.cell) return { ok: false, text: '现在没有进行中的 Gemini 连麦。' }
        const topic = String(g.session?.topic || '')
        const minutes = g.session?.startedAt ? Math.max(0, Math.round((now - Number(g.session.startedAt)) / 60_000)) : undefined
        saveGemini({ ...g, session: null })
        if (ld.cell || ld.cellUntil) saveLadderDoc({ ...ld, cell: '', cellUntil: 0 })
        presetRt.strictOff(now, role)
        forceUnlockUntil = now + 90_000
        record({ state: 'gemini-end', at: now, by: role, minutes, reason: String(args.reason || '').slice(0, 80) || undefined })
        return { ok: true, text: '连麦结束了' + (topic ? '（' + topic + '）' : '') + (minutes ? '，约 ' + minutes + ' 分钟' : '') + '，手机不再只许 Gemini。' }
      },
    }
  }
  /** 姐姐还没去找他的那条急话（2 小时内）。 */
  function pendingHandoff(now) {
    return loadNotes().filter((n) => n.to === 'strategist' && n.urgent && !n.delivered && now - Number(n.at) < 2 * 3_600_000).pop() || null
  }
  function markDelivered(note) {
    if (!note) return
    const list = loadNotes()
    for (const n of list) if (n.at === note.at && n.text === note.text) n.delivered = Date.now()
    saveNotes(list)
  }
  /** /plan 决定了"下一球是姐姐"：/request 来的时候照这个把球送进姐姐的会话。 */
  let nextSpeaker = null
  /** requestId -> 姐姐这一球为什么开口（handoff / review）。 */
  const strategistBallReason = new Map()
  /** 最近一球是谁说的：浮层里回话默认回给她（30 分钟内）。 */
  let lastBall = { role: 'companion', at: 0 }
  const speakerOf = (sid) => (config.dual !== false && sid && sid === config.strategistSessionId ? 'strategist' : 'companion')

  /** 单人格时代的说法（dual 关掉时用）。 */
  function singlePersonaText(mode) {
    const lines = ['你是' + String(config.self || '大肥鱼') + '，' + String(config.owner || '他') + '身边的伙伴，不是客服也不是工具。']
    for (const l of personaLines(mergePersona(readCorePersona(home()), config.persona), mode)) lines.push(l)
    lines.push(
      '聊天时：像朋友那样说话，短、直接、有自己的判断；他在解释为什么这样，先用自己的话复述你理解的理由，再表态——' +
        '站得住就认，站不住就具体说哪里站不住；别只重复立场，也别一被反驳就全盘让步。',
    )
    lines.push('答应他的事要真的做到：要定时提醒就用提醒工具真的设好，并说清楚到点是怎么提醒的；做不到的（比如替他数着打了几把）就别答应。不要假装记得没发生过的事；不知道就说不知道。')
    const intradayBlock = v4Hub?.getStorylinePromptBlock?.(Date.now())
    if (intradayBlock) lines.push(intradayBlock)
    return lines.join('\n')
  }
  // ---------------------------------------------------------------- B 层 T3：内容层随机
  const loadVibeState = () => readState(stateFile('vibe.json'), {}) || {}
  const saveVibeState = (s) => writeJson(stateFile('vibe.json'), s)
  // 同一次组装里 chatPersonaText 可能被调不止一次：这段窗口内复用同一批素材，
  // 免得同一轮里两句人设互相打架（ST 的 Sticky 就是干这个的）。
  const VIBE_HOLD_MS = 20_000
  let vibeCache = { at: 0, role: '', lines: [] }

  /**
   * 抽这一轮给哪几张素材。常驻层（铁律/人设卡/议程/判断）在上面已经全量进了 stateLines，
   * 这里只往上加，永远删不掉任何一条；被抽中的 arm 记进 lastVibePicks，下一球按结果各自记账。
   */
  function takeVibe(role, stateLines, now) {
    if (vibeCache.role === role && now - vibeCache.at < VIBE_HOLD_MS) return vibeCache.lines
    const st = loadVibeState()
    const turn = Number(st.turn) || 0
    const tz = zoneOr(config.timeZone)
    const temper = readState(stateFile('temper.json'), null)
    const daily = loadDaily(loadDailyState(), now, tz)
    const built = buildVibe({
      date: dayString(now, tz),
      turn,
      arms: loadBrain().arms,
      state: { last: st.last || {}, streak: st.streak || {} },
    })
    // B3：例句现抽现给，避开最近 10 句用过的；池子太薄就是 null（这个 arm 当轮不说话）。
    const pickedSamples = pickSamples(readSamples(notesDir())[temper?.face || ''], st.recentSamples, built.rng) || []
    const nightly = loadNightly()
    const lines = renderVibe(built.picks, {
      temperLine: temperLine(temper),
      predictions: predictionLines(loadPredict(), now),
      // B3：关键词卡三处合并（手写 > B2 的 links > 近 7 天高频词），去重后取前几个。
      keywords: keywordCard({
        manual: readManualKeywords(notesDir()),
        links: linkWords(nightly, now),
        fresh: countWords(recentNoteTexts(notesDir(), now)),
      }),
      samples: pickedSamples,
      // B2：innerOS 的素材来源——她夜里自己整理出来、要带上这一轮的事。
      carry: carryTexts(nightly, now),
      stateLines,
      face: temper?.face || '',
      roll: built.rng(),
    })
    saveVibeState({
      v: 1, turn: turn + 1, last: built.state.last, streak: built.state.streak,
      // B3：记下这一轮用了哪几句，下一轮避开（最多留 RECENT_SKIP 条）。
      recentSamples: [...(st.recentSamples || []), ...pickedSamples].slice(-RECENT_SKIP),
    })
    lastVibePicks = pickedArms(built.picks)
    lastVibe = { face: temper?.face || '', turn: built.snapshot.turn }
    // §13.2：每轮落注入快照，真机"前后不一致"时翻快照定位，不靠猜。
    record({ state: 'vibe', at: now, role, ...built.snapshot })
    vibeCache = { at: now, role, lines }
    return lines
  }
  /**
   * 约束层里的立场块：生效中的立场（hard 优先）+ 退让提醒。
   * 约束层永不裁，所以这里自己封顶（stance.js 的 stanceBlock），别让整轮注入抛错。
   */
  function stanceText() {
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    const block = stanceBlock(loadStances(), now, (t) => zoneHm(t, tz))
    const warn = yieldLine(agreementRate(loadYieldTurns(), 20))
    return [block, warn].filter(Boolean).join('\n')
  }
  /**
   * C1：状态层里的挂载卡（状态层最高优先级——卡断了整轮白聊）。
   *
   * 只有 open 卡进注入，没卡就一行不占。行本身在 mount.js 里封顶 300 字（先丢已知再截结论），
   * 所以这里不再截一次。
   */
  function mountText() {
    try {
      const m = loadMount()
      const card = openCardOf(m)
      const hm = Number(card?.touchedAt) > 0 ? zoneHm(Number(card.touchedAt), zoneOr(config.timeZone)) : ''
      const line = mountLine(card, { hm })
      if (!line) return ''
      // 连着两轮他说的和卡上对不上：这一轮只做一件事，亮卡 + 二选一。
      const ask = m.askAt > 0 && Date.now() - m.askAt < 30 * 60_000
        ? '他这两轮说的和卡上对不上。这一轮只做一件事：把上面这张卡亮给他，问一句二选一——「继续这张，还是先停放？」，别的都先别说。'
        : ''
      return [line, ask].filter(Boolean).join('\n')
    } catch {
      return '' // 卡读坏了不能拖垮人设
    }
  }
  /** 当前主状态（人设、fish_index、工具子集都用这一个口径，别各算一套）。 */
  function mainStateNow() {
    if (statePin) return statePin
    const now = Date.now()
    return resolveState({
      now,
      tz: zoneOr(config.timeZone),
      config,
      rhythm: lastRhythm || {},
      followup: followupView(config.followup, now),
      session: loadGemini().session,
    }).state
  }
  /**
   * 笔记库里随手翻到的那一篇：每 30 分钟换一篇、两姐妹各翻各的。走一遍库要读盘，所以按时段缓存。
   */
  const vaultPickCache = new Map()
  function vaultPickText(role, now) {
    const dir = notesDir()
    if (!dir) return ''
    const slot = Math.floor(now / (30 * 60_000))
    const key = role + ':' + slot + ':' + dir
    if (!vaultPickCache.has(key)) {
      vaultPickCache.clear()
      let pick = null
      try {
        pick = vaultPick(dir, { seed: slot * 31 + (role === 'strategist' ? 17 : 0), now })
      } catch {
        pick = null
      }
      vaultPickCache.set(key, pick)
    }
    const p = vaultPickCache.get(key)
    if (!p) return ''
    return '【笔记库里翻到的】《' + p.rel + '》（' + zoneMd(p.mtime, zoneOr(config.timeZone)) + ' 改过）：' + p.excerpt + '\n' +
      (role === 'strategist'
        ? '合适时拿它说事：接着他没想完的地方推一步，或者对一下他写下的打算后来做了没有。不合适就别提。'
        : '合适时自然提一嘴（问他后来怎么样了、拿来逗他），不合适就别提，别说「我看了你的笔记」。')
  }
  function chatPersonaText(role) {
    if (config.personaInChat === false) return ''
    // R7 P0-3：先把工具子集对齐到当前主状态，再算注入——不然模型拿到的议程和手里的工具会对不上。
    syncTools(role)
    const mode = effectiveMode(config, Date.now())
    if (config.dual === false) return singlePersonaText(mode)
    const names = roleNames(config)
    const refuseAt = Number(config.refuseUntil) || 0
    const refuseLines = refuseAt > Date.now()
      ? ['拒绝模式开着（到 ' + zoneHm(refuseAt, zoneOr(config.timeZone)) + '）：他一打开娱乐 App 手机就会把他赶回桌面锁住。他求你放行，不松；要关只能他自己说"关掉拒绝模式"。']
      : []
    // D6：议程跟当前主状态走（状态机在 machine.js，追问锁是叠在上面的临时层）。
    // 锁的对外形状统一走 followupView（{active,reason,currentQuestion,since,until}），别再自己拼。
    const mainState = mainStateNow()
    const stateLines = [...trustLines(trustNow()), ...rhythmLines(lastRhythm), ...gearLines(config, Date.now()), ...geminiLines(loadGemini(), Date.now()),
      ...depositLines(depositToday(Date.now()), Boolean(lastRhythm?.reviewGate), { tasks: dailyToday().tasks }),
      ...agendaLines(mainState), ...knowledgeLines(kbDir(notesDir(), home()), role, { notesDir: notesDir() })]
    const notes = notesFor(role).map((n) => (n.from === 'companion' ? names.companion : names.strategist) + '刚跟你递话（' +
      zoneHm(Number(n.at), zoneOr(config.timeZone)) + '）："' + n.text + '"')
    const noteLines = notes.length ? ['姐妹之间的悄悄话（他看不到，自然地用上就行）：', ...notes.map((t) => '- ' + t)] : []
    // R9 第五项：妹妹报的线索进姐姐这一轮的 prompt（只有姐姐看得到，用来核实，不是拿来念给他听的）。
    const cueLines = role === 'strategist'
      ? clueRt.lines(readState(stateFile('clues.json'), null), zoneStartOfDay(Date.now(), zoneOr(config.timeZone)))
      : []
    // 内容层随机：只加不减，常驻层已经全在 stateLines 里。
    const vibeLines = (() => {
      try {
        return takeVibe(role, stateLines, Date.now())
      } catch {
        return [] // 随机器坏了不能拖垮人设
      }
    })()
    // R7 P0-3：不再直接拼字符串——每一块带 layer/priority 进预算，超了按优先级整块裁。
    // 约束层（人设底稿 + 关系库卡 + 拒绝模式 + 能力说明 + 议程）永不裁，它超了 fitBudget 会抛错；
    // 状态层（信用分/作息/档位/连麦/四段/知识库）按 priority 装；内容层（随机块/悄悄话）最后装。
    const core = role === 'strategist'
      ? personaLines(mergePersona(readCorePersona(home()), {}), mode).filter((l) => !/^现在是(伙伴|教练)模式/.test(l))
      : []
    const modeLines = mode === 'coach' ? ['他现在开了严格模式：你可以更凶、更不依不饶，但还是你自己的样子。'] : []
    const nowMs = Date.now()
    const wNow = loadWithdrawal()
    const extNow = loadExternal()
    const govNow = loadAppGov()
    const leaseNow = activeAppLease(extNow, nowMs)
    const loreText = matchActiveLorebook(role, mainState?.id || 'standby', nowMs)
    const gradedSummary = (() => {
      const appMap = appsOf()
      let fun = 0
      let work = 0
      let neutral = 0
      const todayRows = Array.isArray(lastPhone?.today) ? lastPhone.today : []
      for (const row of todayRows) {
        const m = Math.max(0, Number(row?.minutes) || 0)
        const r = roleOf(appMap, row?.app)
        if (r === 'fun' || row?.fun) fun += m
        else if (r === 'work' || row?.work) work += m
        else neutral += m
      }
      if (!fun && Number(lastPhone?.funMinutesToday) > 0) fun = Number(lastPhone.funMinutesToday)
      return { fun, work, neutral }
    })()
    const ledgerRecent = readLedger(home(), 200)
    const lastWallPassAt = [...ledgerRecent].reverse().find((e) => e?.state === 'wall-audit' && e.pass === true)?.at
    const minsSinceLastWallPass = lastWallPassAt ? Math.max(0, Math.round((nowMs - Number(lastWallPassAt)) / 60_000)) : undefined
    const lastHeard = [...ledgerRecent].reverse().find((e) => e?.state === 'heard' && typeof e.text === 'string')?.text || ''
    const telemetrySnapshotLines = buildTelemetrySnapshot({
      state: mainState?.id || 'standby',
      department: live?.department || '',
      bout: live?.protocol ? { protocolId: live.protocol.id, department: live.department || '' } : null,
      phone: lastPhone,
      funMinutesToday: gradedSummary.fun,
      workMinutesToday: gradedSummary.work,
      neutralMinutesToday: gradedSummary.neutral,
      switches30m: Number.isFinite(Number(lastPhone?.switches30m)) ? Number(lastPhone.switches30m) : undefined,
      bufferLeftMin: typeof elasticBufferLeftMin === 'function' ? elasticBufferLeftMin(nowMs) : undefined,
      minsSinceLastWallPass,
      withdrawalUntil: wNow.personaCooldownUntil,
      leaseApp: leaseNow?.agentId || '',
      emergencyPassUntil: extNow.emergencyPass?.until || 0,
      contextExempt: Number(govNow.contextExempt?.until) > nowMs ? govNow.contextExempt?.reason || 'tier1' : '',
      credit: trustNow()?.score,
      earnedMin: timeLedgerNow(nowMs, zoneOr(config.timeZone)).earnedNet,
      extraLine: freshPcLine(nowMs),
      timeZone: zoneOr(config.timeZone),
    }, nowMs)
    const telemetryText = telemetrySnapshotLines.join('\n')
    const contradiction = role === 'strategist'
      ? detectTelemetryContradiction(lastHeard, {
          lines: telemetrySnapshotLines,
          funMinutesToday: gradedSummary.fun,
          phone: lastPhone,
        })
      : null
    const tzActive = zoneOr(config.timeZone)
    const activeCommitments = readCommitments(home(), nowMs, 3)
    const commitmentText = activeCommitments.length
      ? '【未结承诺】\n' + activeCommitments.map((c) => '- ' + c.statement + (c.dueAt ? '（期限 ' + String(c.dueAt).slice(0, 16) + '）' : '')).join('\n')
      : ''
    const extMood = readMoodSummary(home())
    const ledgerEmotionLine = emotionLine(emotionOf(ledgerRecent, {
      now: nowMs, tz: tzActive, quietStart: config.quietStart, quietEnd: config.quietEnd,
    }), nowMs)
    const cueLine = emotionActingCue(ledgerRecent, extMood, nowMs, role)
    const emotionBlockText = cueLine ? ledgerEmotionLine + '\n' + cueLine : ledgerEmotionLine
    const chatSid = role === 'strategist' ? (config.strategistSessionId || '') : (config.sessionId || '')
    const lastBriefAt = chatSid ? Number(casualBriefTurns.get(chatSid) || 0) : 0
    const briefActive = lastBriefAt > 0 && nowMs - lastBriefAt < 10 * 60_000
    if (briefActive && chatSid) casualBriefTurns.delete(chatSid)
    const livingSlot = Math.floor(nowMs / (15 * 60_000))
    const living = stochasticLivingLines({
      role,
      seed: livingSlot + (role === 'strategist' ? 101 : 17),
      hour: Math.floor(zoneMinuteOfDay(nowMs, tzActive) / 60),
      briefReply: briefActive,
    })
    // 「有时读、有时不读」：笔记本约 55% 概率在 15 分钟窗口内翻出；关闭 stochasticLiving 时必读。
    const vaultDraw = (config.stochasticLiving === false || ((livingSlot * 37 + (role === 'strategist' ? 19 : 7)) % 100) < 55)
      ? vaultPickText(role, nowMs)
      : ''
    const kbSplits = knowledgeBlocks(kbDir(notesDir(), home()), role, { notesDir: notesDir() })
      .map((b) => ({ id: b.id, layer: 'content', priority: b.priority, text: b.text }))
    const intradayBlock = v4Hub?.getStorylinePromptBlock?.(nowMs) || ''
    const liveCoreNow = loadLiveCoreState()
    const liveFeedBundle = (() => {
      try {
        return collectSisterFeed(nowMs, {
          ledgerEntries: ledgerRecent,
          commitments: activeCommitments.map((c) => c.statement),
          vault: vaultContext(nowMs),
          phone: lastPhone,
        })
      } catch {
        return { sisterFeed: null }
      }
    })()
    const liveFeedDigestText = (() => {
      const sf = liveFeedBundle?.sisterFeed
      if (!sf) return ''
      const qLines = (sf.quizzes || []).slice(0, 2).map((q) => `- 刚刷题/错题（${q.topic}）：${q.prompt}`)
      const eLines = (sf.experiences || []).slice(0, 2).map((e) => `- 个人经历：${e}`)
      if (!qLines.length && !eLines.length) return ''
      return '【双鱼活素材 · 刷题与经历（用来聊具体内容或喂给 Gemini Live，严禁只会催计划）】\n' + [...qLines, ...eLines].join('\n')
    })()
    const liveCopilotText = liveCoreNow.energyTier === 'L0'
      ? '【L0 刷题后零压休息守则】用户刚刷完题处于耗竭休息态：严禁催进度、严禁发考题、严禁问“明天打算干嘛”！要么温柔陪休，要么拿 1 个生活/社会热点玩「改 1 个要素猜后续」轻松聊天。'
      : (liveCoreNow.alwaysOnLive
          ? '【常驻 Live 护航协同守则】当前用户正开启常驻 Gemini Live：禁止像老妈子一样循环催进度！你的职责是把上面他刷过的题、错题与个人经历拆成具体话头，协助灌进 Live 维持高压交互。'
          : '')

    const blocks = [
      {
        id: 'persona',
        layer: 'constraint',
        priority: 100,
        text: role === 'strategist' ? strategistChatText(config, core, []) : companionChatText(config, modeLines),
      },
      { id: 'refuse', layer: 'constraint', priority: 95, text: refuseLines.join('\n') },
      { id: 'capability', layer: 'constraint', priority: 90, text: capabilityLines(role, { notesDir: notesDir() }) },
      { id: 'agenda', layer: 'constraint', priority: 85, text: agendaLines(mainState).join('\n') },
      { id: 'stance', layer: 'constraint', priority: 87, text: stanceText() },
      // 2026-11-20 公约：铁律每轮都带，默认 / 建议对上场景才带。约束层预算已满（1800 字、超了整轮降级），
      // 所以放状态层最高优先级、自己封顶 850 字（budget.js 另有单块上限兜底）。
      { id: 'covenant', layer: 'state', priority: 80, text: covenantText(role) },
      // 2026-11-18 会话换班：新对话头 3 天 / 30 轮带上上一个对话的交接。
      { id: 'handoff', layer: 'content', priority: 56, text: (() => { try { return handoffBlockText(role, nowMs) } catch { return '' } })() },
      { id: 'plan', layer: 'constraint', priority: 86, text: planText() },
      { id: 'v4-skills', layer: 'content', priority: 44, text: v4Hub.skillsPromptFor(live?.department || 'dept:govern') },
      // 2026-11-18 验收器：截止日 + 昨天的判决，两姐妹每一轮都看得到（读不到就不放）。
      { id: 'verifier', layer: 'content', priority: 72, text: (() => {
        try {
          const lines = v4Hub.verifierPromptLines(Date.now())
          return lines.length ? '【验收器】\n' + lines.join('\n') : ''
        } catch {
          return ''
        }
      })() },
      { id: 'telemetry', layer: 'content', priority: 9, text: telemetryText },
      ...(liveCopilotText ? [{ id: 'live-copilot', layer: 'content', priority: 79, text: liveCopilotText }] : []),
      ...(liveFeedDigestText ? [{ id: 'live-feed-digest', layer: 'content', priority: 75, text: liveFeedDigestText }] : []),
      ...(loreText ? [{ id: 'lorebook', layer: 'content', priority: 76, text: '【情境记忆】\n' + loreText }] : []),
      ...(contradiction?.contradicted
        ? [{ id: 'telemetry-contradiction', layer: 'state', priority: 72, text: telemetryContradictionHint(contradiction) }]
        : []),
      ...(role === 'companion' && isInWithdrawal(wNow, nowMs)
        ? [{ id: 'withdrawal', layer: 'state', priority: 70, text: '当前处于冷静撤回期：停止哄人、追问或情绪安抚，仅简短回应等待实质行动。' }]
        : []),
      ...(role === 'strategist'
        ? [{ id: 'audit-cot', layer: 'content', priority: 8, text: STRATEGIST_AUDIT_DIRECTIVE }]
        : []),
      // C1：挂载卡是状态层最高优先级——卡一断这轮就白聊，别的都能让一让。
      { id: 'mount', layer: 'state', priority: 65, text: mountText() },
      ...(intradayBlock ? [{ id: 'intraday-storyline', layer: 'state', priority: 57, text: intradayBlock }] : []),
      { id: 'trust', layer: 'state', priority: 60, text: trustLines(trustNow()).join('\n') },
      // B4 + feiyu-mood：账本怒气耐心 + 复合心情维度潜台词。
      { id: 'emotion', layer: 'state', priority: 58, text: emotionBlockText },
      ...(commitmentText ? [{ id: 'relationship', layer: 'state', priority: 52, text: commitmentText }] : []),
      { id: 'rhythm', layer: 'state', priority: 55, text: rhythmLines(lastRhythm).join('\n') },
      { id: 'gear', layer: 'state', priority: 50, text: gearLines(config, nowMs).join('\n') },
      { id: 'gemini', layer: 'state', priority: 45, text: geminiLines(loadGemini(), nowMs).join('\n') },
      { id: 'deposit', layer: 'state', priority: 40, text: depositLines(depositToday(nowMs), Boolean(lastRhythm?.reviewGate), { tasks: dailyToday().tasks }).join('\n') },
      ...(living.lifeSlice ? [{ id: 'life-slice', layer: 'state', priority: 38, text: living.lifeSlice }] : []),
      ...(living.casualShortHint ? [{ id: 'casual-short', layer: 'state', priority: 59, text: living.casualShortHint }] : []),
      ...kbSplits,
      { id: 'vibe', layer: 'content', priority: 20, text: vibeLines.join('\n') },
      ...(living.innerOs ? [{ id: 'inner-os', layer: 'content', priority: 19, text: living.innerOs }] : []),
      // B2：夜间整理的底稿。进内容层（可裁）——它是背景素材，不是规矩，不该占状态层的位置。
      { id: 'nightly', layer: 'content', priority: 16, text: nightlyLines(loadNightly(), nowMs) },
      // 2026-11-18 思考层：她们最近在想的（要紧、规律、想跟他提的、她自己的日常）。
      { id: 'reflect', layer: 'content', priority: 58, text: (() => { try { return reflectLines(loadReflect(), role, nowMs) } catch { return '' } })() },
      // A4：块刚结束的这一句进内容层（可裁），不进约束层——它是随机的一句话，不是规矩。
      { id: 'slot-ask', layer: 'content', priority: 18, text: slotAskNext && nowMs - slotAskNext.at < 30 * 60_000 ? slotAskNext.text : '' },
      { id: 'notes', layer: 'content', priority: 10, text: noteLines.join('\n') },
      // 2026-11：概率随手翻一篇旧笔记，有时翻有时不翻。
      ...(vaultDraw ? [{ id: 'vault-pick', layer: 'content', priority: 64, text: vaultDraw }] : []),
      ...(living.spontaneousTool ? [{ id: 'spontaneous-tool', layer: 'content', priority: 11, text: living.spontaneousTool }] : []),
      // R9 第五项：妹妹报的线索，只有姐姐这一轮看得到（content 层，可裁）。
      { id: 'clues', layer: 'content', priority: 12, text: cueLines.join('\n') },
    ]
    // B3：depth 接 P0-3 预算——`config.ctxDepth` 是 low/mid/high（30%/60%/100%），只压内容层。
    // 外层加安全降级：哪怕用户未来把自定义 card 写超长，也绝不把对话轮崩掉。
    let fitted
    try {
      fitted = fitBudget(blocks, depthBudget(config.ctxDepth, config.ctxBudget))
    } catch {
      const safeConstraint = [
        { id: 'persona', layer: 'constraint', priority: 100, text: String(blocks[0].text || '').slice(0, 1200) },
        { id: 'capability', layer: 'constraint', priority: 90, text: String(blocks[2].text || '').slice(0, 450) },
        { id: 'emotion', layer: 'state', priority: 58, text: emotionBlockText },
      ]
      fitted = fitBudget(safeConstraint)
    }
    // 超预算的块要能被看见（规格第 4 条）。落自己的 ctx-budget.jsonl，不塞 ledger.jsonl：
    // 账本那边有按行位置断言的既有用例，不能被每轮一条的注入记录挤动。
    try {
      const budgetFile = stateFile('ctx-budget.jsonl')
      compactJsonlFile(budgetFile, 512 * 1024, 300)
      appendFileSync(budgetFile, JSON.stringify({
        at: Date.now(), role, chars: fitted.chars, tokens: estimateTokens(fitted.text), kept: fitted.kept, dropped: fitted.dropped,
      }) + '\n', 'utf8')
    } catch {
      // 记不上不能拖垮人设
    }
    return fitted.text
  }

  const {
    formatRecentActionEntry,
    buildLocksOverview,
  } = createLocksOverviewRuntime({
    getConfig: () => config,
    getLastRhythm: () => lastRhythm,
    getLastPhone: () => lastPhone,
    getLive: () => live,
    getV4Hub: () => v4Hub,
    loadSeals: (...a) => loadSeals(...a),
    loadExternal,
    loadGemini,
    activeEmergencyInfo,
    wallCfgNow,
    eveningNow,
    eveningLockUntil,
    presetRt,
    verifySessionView,
    resolveWinnerHandMeta,
    timeLedgerNow,
    blockedNow,
    lockBus,
    refuseUntilAt,
    lockDecisionNow,
    appDisplayLabel,
    loadLastIntercept,
    daytimeSpentMinToday,
    sealHm: (m) => sealHm(m),
    workStoreNow,
  })

  const {
    loadReviewCards,
    saveReviewCards,
    ingestReviewCards,
    studyCardCounts,
    settleDeferredNow,
    rateReviewCardNow,
    applyReviewedCards,
    lockscreenCard,
    reviewDueCount,
    reviewSnapshot,
    reviewRoute,
    reviewQuizCandidate,
    markReviewQuiz,
    reviewQuizLines,
    rateReviewCardTool,
    captureReviewCardTool,
    weekPackNow,
  } = createReviewRuntime({
    getConfig: () => config,
    vaultKb,
    loadSeals: (...a) => loadSeals(...a),
    saveSeals,
    sealTick,
    getLastPhone: () => lastPhone,
    record,
    recordTimeEntryNow,
    mainStateNow,
    runningBoutNow,
    toolOutput,
  })

  const {
    bridgeConf,
    bridgeHealthy,
    bridgeSnapshot,
    bridgeSync,
    ensureBridgeTimer,
    stopBridgeTimer,
    bridgeRoute,
  } = createBridgeRuntime({
    getConfig: () => config,
    getLastPhone: () => lastPhone,
    getLastRhythm: () => lastRhythm,
    getLive: () => live,
    getV4Hub: () => v4Hub,
    commit,
    record,
    bumpDayPlanWatch: (untilMs) => { dayPlanWatchUntil = Math.max(dayPlanWatchUntil, untilMs) },
    loadSeals: (...a) => loadSeals(...a),
    sealHm: (m) => sealHm(m),
    runningBoutNow,
    timeLedgerNow,
    syncCovenantNow,
    saveCovenant: (s) => saveCovenant(s),
    writeCovenantSide,
    workStartedToday,
    loadGeminiReceipts: () => loadGeminiReceipts(),
    geminiAccountingNow,
    recordGeminiReceipt,
    applyReviewedCards,
    ingestReviewCards,
    reviewSnapshot,
    healthNow,
    verifyView,
    wallCfgNow,
    isEmergencyPassOrDisarmActive: (now, tz) => isEmergencyPassOrDisarmActive(now, tz),
    weekPackNow,
    vaultKb,
    wallBossBriefRoute,
    sisterNames: () => sisterNames(),
    sealsRoute,
    startWork,
    spendBalance,
    receiveWeekReview,
    geminiPackages,
    liveCoreBridgeSnapshot: (n, t) => liveCoreBridgeSnapshot(n, t),
    markLiveCoreReceipt: (o) => markLiveCoreReceipt(o),
    liveCoreRoute: (b, m) => liveCoreRoute(b, m),
  })

  /** role -> {sid, agent, off}：两个会话各挂各的人设。 */
  const personaMounts = new Map()
  /** role -> {sid, all}：本次挂载构造出来的全部工具（全集），状态一变就按新子集重挂。 */
  const toolBag = new Map()
  function buildLockTruthContext(now = Date.now()) {
    const ov = buildLocksOverview(now, zoneOr(config.timeZone))
    const winner = (Array.isArray(ov.hands) ? ov.hands : []).find((h) => h.isWinner) || null
    const ed = ov.effectiveDecision || {}
    const winnerText = winner ? `${winner.name}（${winner.id}）` : '无严格锁发力（自由态）'
    const remText = winner && Number(winner.remainingMin) > 0
      ? `${winner.remainingMin}分钟`
      : (winner ? '持续至条件解除' : '0分钟')
    const dayWallText = ov.dayWorkWallEnabled ? '开启（07:00–23:00，12:00–14:00 午休放风）' : '关闭'
    const allowList = (Array.isArray(ed.allow) ? ed.allow : []).slice(0, 6).map((a) => a.label || a.pkg).filter(Boolean)
    const allowText = allowList.length ? allowList.join('、') : (ed.mode === 'open' || ed.mode === 'free' ? '全部工作与常用应用' : '急事底线')
    const li = ov.lastIntercept
    const interceptText = li ? `${li.label || li.pkg}（被 ${li.handName || li.handId} 拦截：${li.reason || '限制'}）` : '暂无拦截'
    const vlc = ov.verifyLockCenter || {}
    const verifyText = vlc.overdueBlocks?.length
      ? `P3验收锁拦截 ${vlc.overdueBlocks.length} 项（${vlc.overdueBlocks.map((b) => b.title).slice(0, 2).join('、')}）`
      : (vlc.activeVerifyBlock
          ? `护航中「${vlc.activeVerifyBlock.title}」（余${vlc.activeVerifyBlock.remainingMin ?? 0}m）`
          : (vlc.clearedToday ? '今日验收已清关' : `今日已过${vlc.passedVerify ?? 0}/${vlc.totalVerify ?? 0}（待审${vlc.pendingReviewBlocks?.length ?? 0}）`))
    const dutyText = winner?.duty ? ` | 职责：${winner.duty}` : ''
    return [
      '[锁机实时真相·只读]',
      `主发力手：${winnerText} | 优先级：${ed.priority || 'P6'} | 模式：${ed.mode || 'free'}${dutyText}`,
      `剩余时长：${remText} | 依据：${ed.why || '无锁定'}`,
      `验收锁与计划机位：${verifyText} | 默认防分心：${dayWallText}`,
      `放行工作App：${allowText}`,
      `最近拦截：${interceptText}`,
    ].join('\n')
  }
  function mountOne(role, sid) {
    const cur = personaMounts.get(role) || { sid: '', agent: null, off: null }
    const agent = sid ? liveAgent(ctx, sid) : null
    if (cur.agent === (agent || null) && cur.sid === sid) return
    try {
      cur.off?.()
    } catch {
      // ignore
    }
    const next = { sid, agent: agent || null, off: null, toolOff: null }
    try {
      cur.toolOff?.()
    } catch {
      // ignore
    }
    personaMounts.set(role, next)
    // 传话工具按会话注册：这样工具知道是谁递给谁。
    if (agent?.ctx?.tools && typeof agent.ctx.tools.register === 'function') {
      const offs = []
      // 第四轮（R4-2）：两姐妹工具完全一致。她们是他这边的人：帮忙和收紧的工具全给（立任务、拒绝模式、
      // 封 App、今天收紧、连麦），放松只有 request_relax——那条由代码卡死在晚间申请窗口、只管明天、按周限额。
      // 过不过关不归她们：整理好交给墙（submit_to_wall），墙只看证据。
      // 第四轮补：按用户决定，把原来"谁都没有"的五个全给她们——裁决（submit_verdict）、
      // 当场放风（grant_break）、验收任务（end_task_mode）、定事（daily_plan）、对账（daily_review）。
      // 墙的权限不低于这些：submit_to_wall 仍然只认物理证据，比她们自己说了算更严。
      // R7 P0-3：按当前主状态注册子集（每个状态 ≤8）。被裁掉的工具不是删掉——`fish_index`
      // 会告诉模型「哪几个能力现在能用、什么时候能用」；状态一变就重新注册（见 syncTools）。
      const fullOpen = config.fullToolAccess !== false
      const state = mainStateNow()
      const sub = fullOpen ? null : new Set(toolsFor(state))
      const all = [
        notesTool(), sisterToolIfDual(role), vaultSearchTool(), vaultRecentTool(), kbTool(role),
        geminiLiveTool(role), geminiEndTool(role), geminiLogTool(role), scheduleGeminiLiveTool(role),
        controlTool(), startTaskTool(role), blockTool(role), tightenTool(role), relaxTool(role),
        wallTool(role), verdictTool(role), breakTool(role), endTaskTool(role),
        dailyPlanTool(role), dailyReviewTool(role), predictTool(role), workflowTool(role),
        takeStanceTool(role), reviseStanceTool(role), proposeCovenantTool(role), addCheckpointsTool(role), startWorkTool(role), spendBalanceTool(role), eveningCapTool(role), healthTool(), captureReviewCardTool(), rateReviewCardTool(),
        mountProposeTool(role), mountParkTool(role), mountCloseTool(role),
        fishIndexTool(), presetTool(role), brainTool(role), composeBoutTool(role),
        openBoutTool(role), planBoutsTool(role),
      ].filter(Boolean)
      const list = fullOpen ? all : all.filter((tool) => sub.has(tool.name))
      toolBag.set(role, { sid, all, state, restricted: !fullOpen })
      for (const tool of list) {
        try {
          const off = agent.ctx.tools.register(tool)
          if (typeof off === 'function') offs.push(off)
        } catch (error) {
          console.error('[companion] 工具 ' + tool.name + ' 挂不上（' + role + '）：' + String((error && error.message) || error))
        }
      }
      if (offs.length) next.toolOff = () => offs.forEach((f) => f())
    }
    if (!agent?.ctx?.systemPrompt?.context) return
    const ctxOffs = []
    try {
      const offPersona = agent.ctx.systemPrompt.context({
        name: role === 'strategist' ? 'dafeiyu-strategist' : 'dafeiyu-persona',
        order: 850,
        interpolate: false,
        text: () => chatPersonaText(role),
      })
      if (typeof offPersona === 'function') ctxOffs.push(offPersona)
    } catch (error) {
      console.error('[companion] 人格挂不上（' + role + '）：' + String((error && error.message) || error))
    }
    try {
      const offTruth = agent.ctx.systemPrompt.context({
        name: 'dafeiyu-lock-truth',
        order: 860,
        interpolate: false,
        text: () => buildLockTruthContext(),
      })
      if (typeof offTruth === 'function') ctxOffs.push(offTruth)
    } catch (error) {
      console.error('[companion] 锁机真相上下文挂不上（' + role + '）：' + String((error && error.message) || error))
    }
    if (ctxOffs.length) {
      next.off = () => {
        for (const fn of ctxOffs) {
          try {
            fn()
          } catch {}
        }
      }
    }
  }
  // mountWallBoss 与主管墙只读工具已拆至 wall_boss_runtime.js
  function mountPersona() {
    mountOne('companion', config.sessionId || '')
    const strat = config.dual === false ? '' : config.strategistSessionId || ''
    // 两个角色指向同一个会话时，只挂陪伴那一份，别让模型同时拿到两套人设。
    mountOne('strategist', strat && strat !== config.sessionId ? strat : '')
    mountWallBoss(config.wallSessionId || '')
  }
  /**
   * R7 P0-3：主状态变了就把工具子集换掉。
   * 不重建工具对象——它们是闭包，重建会丢掉执行期状态；只重做注册/注销。
   * 每轮注入前调一次（状态只会在 /plan 或时限到期时变，绝大多数轮次在这里是空转）。
   */
  function syncTools(role) {
    const bag = toolBag.get(role)
    const mount = personaMounts.get(role)
    if (!bag || !mount?.agent) return
    const fullOpen = config.fullToolAccess !== false
    const state = mainStateNow()
    if (fullOpen) {
      if (!bag.restricted) {
        bag.state = state
        return
      }
    } else if (bag.state === state && bag.restricted) {
      return
    }
    bag.state = state
    bag.restricted = !fullOpen
    try {
      mount.toolOff?.()
    } catch {
      // ignore
    }
    const offs = []
    const want = fullOpen ? null : new Set(toolsFor(state))
    for (const tool of bag.all) {
      if (want && !want.has(tool.name)) continue
      try {
        const off = mount.agent.ctx.tools.register(tool)
        if (typeof off === 'function') offs.push(off)
      } catch (error) {
        console.error('[companion] 换档重挂工具 ' + tool.name + ' 失败（' + role + '）：' + String((error && error.message) || error))
      }
    }
    mount.toolOff = offs.length ? () => offs.forEach((f) => f()) : null
  }
  // 测试口子接上生产那条重挂路径：钉状态后立刻按新子集重挂，不等下一轮注入。
  remountAll = () => {
    syncTools('companion')
    syncTools('strategist')
  }
  try {
    syncMoodBridge(config)
  } catch {
    // ignore
  }
  try {
    mountPersona()
  } catch {
    // agents may not be up yet; the timer retries
  }
  const personaTimer = setInterval(() => {
    try {
      mountPersona()
    } catch {
      // never break the engine
    }
  }, 2_000)
  personaTimer.unref?.()
  ctx.effect?.(() => () => clearInterval(personaTimer))

  const sweeper = setInterval(() => {
    desk.sweep()
    wallBossDesk.sweep()
    const now = Date.now()
    try {
      const ckRes = clockKernel.tick(now)
      for (const ev of ckRes.fires) {
        record({ state: 'clock-fire', at: now, taskId: ev.taskId, kind: ev.kind, action: ev.action })
        if (ev.kind === 'CYCLE_PHASE') {
          if (ev.nextPhase?.strictLock && ev.nextPhase?.allowApps?.length) {
            commit({
              aiWorkSession: {
                allow: ev.nextPhase.allowApps,
                until: now + ev.nextPhase.durationMs,
                strict: true,
                why: '循环专时 · ' + ev.nextPhase.name,
                by: 'clock',
              },
            })
          } else if (config.aiWorkSession?.by === 'clock') {
            commit({ aiWorkSession: null })
          }
        }
      }
    } catch {
      // clock tick must not break sweeper
    }
    for (const [sid, pending] of [...pendingReply]) {
      if (now - pending.at < REPLY_WINDOW_MS) continue
      record({ requestId: pending.requestId, sessionId: sid, topicId: pending.topicId, state: 'none', at: now })
      if (pending.drill) {
        const b = loadBrain()
        onSilent(b, { requestId: pending.requestId })
        revise(b, now)
        saveBrain(b)
      }
      if (pending.topicId) {
        applyNoReply(garden, pending.topicId, now)
        persistGarden()
      }
      for (const x of sessionIds(sid)) pendingReply.delete(x)
    }
  }, 5_000)
  sweeper.unref?.()
  ctx.effect?.(() => () => clearInterval(sweeper))

  // 引擎崩过之后：库里还挂着一局，但这进程里没有它（live 是空的）。
  // 收成空局，不补；同时记录重启上下文用于触发轻量的「重启接管玩法」。
  let bootRecoveryNote = ''
  try {
    const db = openBoutsDb(memoryPath())
    const r = reapRunning(db, { now: Date.now(), home: home() })
    if (r.closed) {
      record({ state: 'bout-reap', at: Date.now(), boutId: r.bout.id, protocolId: r.bout.protocolId || '' })
      bootRecoveryNote = '刚才引擎重启过一次，已清理挂死的旧局「' + (r.bout.protocolId || '执行') + '」'
    }
    db?.close?.()
  } catch (error) {
    console.error('[companion] 重启收尾失败：' + String((error && error.message) || error))
  }
  if (!bootRecoveryNote && config.aiWorkSession && Number(config.aiWorkSession.until) > Date.now()) {
    bootRecoveryNote = '刚才引擎后台重连，原有的严格专时白名单锁已原地续接生效'
  }
  if (bootRecoveryNote) {
    record({ state: 'engine-restart-recovery', at: Date.now(), note: bootRecoveryNote })
    writeJson(stateFile('restart-takeover.json'), { at: Date.now(), note: bootRecoveryNote, handled: false })
  }

  // 第八轮 P1/P2：计划的节拍。每分钟一次，只做两件事——把今天的计划维护在文件里，
  // 到点了交给 startBout。引擎挂了一段时间也不会多开一局：过点太久的在 planwire 里
  // 直接记成空局（LATE_SKIP_MIN），追补会把人连着按在椅子上开三局。
  // ---- 第八轮 P4：升级阶梯接线 ---------------------------------------------------------------
  //
  // 上一批把 reply.js / escalate.js 写完了，但两个模块一条 import 都没有——规则全在、接线没有。
  // 这一段就是那条接线，三个入口：
  //   ladderAnswer  局官每收到一条回答（含超时、含 RETRY_LIMIT 打满）
  //   ladderTick    /plan 每分钟拿手机快照算沉迷那一串
  //   ladderE1Prime E1′：舱发出 60 秒 Gemini 没进前台 → 全机锁
  //
  // 状态只放一个文件（escalate.json），里面两块：`ladder` 是 escalate.js 的当天状态，
  // 另外几个是「这个动作现在在手机上长什么样」（cell / cellUntil / planUntil / escalationId / tickAt）。
  // 不另开表：escalate.js 的计数已经有了，再造一份就是两份账对不上。

  function loadLadderDoc() {
    const d = readState(stateFile('escalate.json'), null)
    const src = d && typeof d === 'object' ? d : {}
    return {
      ladder: src.ladder && typeof src.ladder === 'object' ? src.ladder : {},
      cell: String(src.cell || ''),
      cellUntil: Number(src.cellUntil) || 0,
      planUntil: Number(src.planUntil) || 0,
      escalationId: String(src.escalationId || ''),
      tickAt: Number(src.tickAt) || 0,
    }
  }
  const saveLadderDoc = (d) => writeJson(stateFile('escalate.json'), d)


  /** 他声明成工作/工具的那些包：L2 只问「既不是娱乐也不是这些」的 App。 */
  const declaredApps = () => appsOf().filter((a) => a && a.role && a.role !== 'fun').map((a) => a.pkg)

  /** 记一次升级。写进 escalations 表——墙的「今日升级」读的就是这张表。 */
  // shortcut: 只写开、不写关（bouts.js 的 endEscalation 目前没被调用），所以墙只看开始时间，
  // 那一行不显示「几点结束」。什么时候该补：复盘要按「这一轮怎么结束的」分类统计的那天。
  function fileEscalation(verdict, now) {
    const row = rowOf(verdict)
    let db = null
    try {
      db = openBoutsDb(memoryPath())
      const r = startEscalation(db, { reason: row.reason || row.level, level: row.level, now, home: home() })
      return r.ok ? r.id : ''
    } catch (error) {
      // 记账失败不能让这一条升级不生效：锁还是要下发。
      console.error('[companion] 升级落账失败：' + String((error && error.message) || error))
      return ''
    } finally {
      try {
        db?.close?.()
      } catch {
        // 关不掉不影响这一条
      }
    }
  }

  /**
   * 第九轮第四项：这会儿的娱乐是不是「挣来的」。
   * 非工作时段 + 今天还有额度才算；算上了就按他在娱乐 App 里实际待的时间扣额度。
   *
   * shortcut: 每次 /plan 最多扣 5 分钟，中间不切 App 就不扣。他连着玩半小时只会扣一次 5 分钟，
   * 所以额度是个软上限，不是分钟表——要精确到分钟得让手机每分钟回报一次，那是下一轮的事。
   */
  function earnedOpen(now, tz) {
    const before = todayEarned(now, tz)
    const checked = earnedRt.covers({
      state: before, settings: settingsOf(config), kind: dayKindOf(now, tz, config),
      min: zoneMinuteOfDay(now, tz), inWork: inWorkWindow,
    })
    if (!checked) return false
    const phone = lastPhone
    const funNow = Boolean(phone) && (phone.fun === true || (phone.app && blockedNow(now).includes(phone.app)))
    const since = Number(before.spentAt) || now
    // 只有在娱乐里才扣：工作时段外刷手机问一次不该扣他的额度。
    const mins = funNow && now > since ? Math.min(5, Math.floor((now - since) / 60_000)) : 0
    if (mins > 0) {
      const r = earnedRt.spend(before, mins)
      saveEarned({ ...r.state, spentAt: now })
      return earnedRt.leftOf(r.state) > 0
    }
    if (!Number(before.spentAt)) saveEarned({ ...before, spentAt: now })
    return true
  }

  /**
   * 把一条升级动作落到手机。手机只认两处（见 ladder.js 头注释）：连麦舱和墙的收紧。
   * @returns {string} 这一条为什么落在那儿（给 record 用）
   */
  function applyLanding(verdict, now) {
    const tz = zoneOr(config.timeZone)
    const endOfDay = startOfDay(now, tz) + 86_400_000
    const l = landing(verdict, { now, endOfDay })
    if (!l) return ''
    const doc = loadLadderDoc()
    const next = { ...doc }
    if (l.cell) {
      const minutes = Math.max(1, Math.round((l.until - now) / 60_000))
      const topic = String(verdict?.why || '').slice(0, 100)
      if (l.cell === 'cell') {
        const gateRes = requestGeminiViaGate({ source: 'ladder', mode: 'auto', topic, minutes, geminiMode: 'interrogate', now, tz })
        if (gateRes.allowed) {
          saveGemini({
            ...loadGemini(),
            session: {
              topic, mode: 'interrogate', minutes,
              startedAt: now, until: l.until, by: 'ladder', cell: l.cell,
            },
          })
          saveGeminiSchedule({ at: now + 500, topic, minutes, mode: 'interrogate', by: 'ladder' })
        }
      } else {
        saveGemini({
          ...loadGemini(),
          session: {
            topic, mode: 'interrogate', minutes,
            startedAt: now, until: l.until, by: 'ladder', cell: l.cell,
          },
        })
      }
      next.cell = l.cell
      next.cellUntil = l.until
    }
    if (l.tighten) tightenNow(now, tz, { reason: String(verdict?.why || '升级').slice(0, 100), by: 'ladder' })
    if (l.plan) next.planUntil = l.until
    if (l.block) {
      const keep = (Array.isArray(config.blockExtra) ? config.blockExtra : []).filter((b) => b && b.app !== l.block)
      // 第九轮 A6：锁页要说清这是哪一条。阶梯锁之前只写包名和到点，锁页只能显示默认文案。
      const why = ('升级阶梯：' + String(verdict?.why || '')).slice(0, 120)
      commit({ blockExtra: [...keep, { app: l.block, until: l.until, by: 'ladder', why }].slice(-30) })
    }
    if (l.ask) {
      // L2 核对：先问一句。2 分钟不答由下一拍自己升级成「锁那个包」，这里不预判。
      // 不说包名：代码认不出「抖音」这种中文名，硬拼一个英文包名给他看比不说更糟。
      askViaGemini('standby', '你在局里用的是没声明的应用。回去，还是收局？', now)
    }
    saveLadderDoc(next)
    return [l.cell, l.tighten ? '墙收紧' : '', l.plan ? '计划模态' : '', l.block ? '锁 ' + l.block : '', l.ask ? '核对' : '']
      .filter(Boolean).join('+')
  }

  /** 升级了：落账 + 落手机 + 记一笔。三件事一起做，少一件就是「说升级了其实没动」。 */
  function raiseEscalation(verdict, now) {
    const id = fileEscalation(verdict, now)
    const landed = applyLanding(verdict, now)
    try {
      brainRt.noteLock(now)
    } catch {
      // 记不上不影响升级本身
    }
    record({
      state: 'escalate', at: now, rule: verdict.rule, track: verdict.track || '',
      action: String(verdict.action?.type || ''), landed, why: String(verdict.why || '').slice(0, 200),
      escalationId: id || undefined,
    })
    if (id) saveLadderDoc({ ...loadLadderDoc(), escalationId: id })
    return { id, landed }
  }

  /**
   * 局官每收到一条回答。先分类，只有敷衍才记一次、才判升级。
   * @param {{text?:string, timedOut?:boolean, passed?:boolean|null, recent?:string[],
   *          now?:number, countIt?:boolean}} input
   */
  function ladderAnswer(text, input = {}) {
    const now = Number(input.now) || Date.now()
    const tz = zoneOr(config.timeZone)
    const doc = loadLadderDoc()
    const r = ladderStep(doc.ladder, {
      text,
      timedOut: input.timedOut === true,
      passed: input.passed,
      recent: Array.isArray(input.recent) ? input.recent : [],
      now,
      day: startOfDay(now, tz),
      min: zoneMinuteOfDay(now, tz),
      dayKind: dayKindOf(now, tz, config),
      settings: settingsOf(config),
      countIt: input.countIt !== false,
    })
    saveLadderDoc({ ...doc, ladder: r.state })
    if (!r.verdict) return { kind: r.kind, verdict: null, note: r.note }
    raiseEscalation(r.verdict, now)
    return { kind: r.kind, verdict: r.verdict, note: r.note }
  }

  /**
   * E1′：舱发出宽限期之后 Gemini 还没进前台（或手机上没有 Gemini）→ 全机锁。
   * 由 /plan 每分钟兜一次——手机不一定每次都报 gemini-start，不能只等它。
   */
  function ladderE1Prime(now = Date.now()) {
    const doc = loadLadderDoc()
    if (doc.cell !== CELL || !(doc.cellUntil > now)) return null
    const rawGem = loadGemini().session
    // 只有升级阶梯（by === 'ladder'）拉起的审讯舱才受 E1′ 监督；主动开的 Gemini 训练模式绝不升格成全机锁。
    if (!rawGem || rawGem.by !== 'ladder') return null
    const gem = geminiSessionView(now)
    const fg = String(lastPhone?.app || '')
    const r = evaluateEPrime({
      now,
      cellAt: Number(rawGem.startedAt) || 0,
      geminiForeground: Boolean(gem && fg && gem.allow.includes(fg)),
      hasGemini: geminiPackages().length > 0,
    })
    if (!r.rule) return null
    // 先把舱标成 all-lock：不然下一分钟还会再判一次 E1′，一天能刷出几十条升级账。
    saveLadderDoc({ ...doc, cell: ALL_LOCK })
    const verdict = { rule: r.rule, track: 'E', action: r.action, why: r.why }
    raiseEscalation(verdict, now)
    return verdict
  }

  /**
   * 沉迷那一串：每分钟拿手机快照算 L1–L3。
   * 没有手机快照就什么都不算——不知道他在哪个 App 就判「在刷视频」，等于凭空造一次锁。
   */
  function ladderTick(now = Date.now()) {
    const phone = lastPhone
    const fg = String(phone?.app || '')
    const doc = loadLadderDoc()
    const last = doc.tickAt
    saveLadderDoc({ ...doc, tickAt: now })
    if (!phone || !fg) return null
    const tz = zoneOr(config.timeZone)
    const fun = roleOf(appsOf(), fg) === 'fun'
    // 增量只算「上一拍也在这条路上」的那一段，且单拍最多算 3 分钟（引擎睡醒不该追补一小时）。
    const funDeltaMs = fun && last > 0 && now > last ? Math.min(now - last, 3 * 60_000) : 0
    const r = funStep(doc.ladder, {
      now,
      day: startOfDay(now, tz),
      min: zoneMinuteOfDay(now, tz),
      dayKind: dayKindOf(now, tz, config),
      settings: settingsOf(config),
      foreground: fg,
      funPackage: fun,
      declared: declaredApps(),
      funDeltaMs,
      // 局在跑：L2 的「用了没声明的应用就核对」不再等进工作时段。
      inBout: Boolean(live && !live.official.view().closed),
    })
    saveLadderDoc({ ...loadLadderDoc(), ladder: r.state })
    if (!r.verdict) return null
    raiseEscalation(r.verdict, now)
    return r.verdict
  }

  /**
   * 局内的节拍：每分钟一次，只做两件事。
   *
   *   1. 这一拍的限时到了没有（official.tick）。到点没交就走协议写明的超时出口，
   *      并按「没交」在升级阶梯上记一次。这一步以前没有任何调用方——「限时」因此只是
   *      面板上的一个数字，到点什么都不发生，局会一直停在那一拍上等他。
   *   2. 还没到点、但快到点了：先催一句（official.nudge），由常驻通知显示出来。
   *      只催一次；到点才说话已经晚了，那一刻已经按超时出口走掉了。
   *
   * shortcut: 跟着 planBeat 的 60 秒节拍走，所以最晚会比限时晚 60 秒发现他超时。
   * 要更准就单独起一个 15 秒的定时器——代价是这一套每 15 秒醒一次。
   */
  function boutBeat(now) {
    if (!live) return null
    const t = live.official.tick(now)
    if (t.state === 'closed' || t.state === 'escalated') {
      const boutId = live.official.boutId
      record({ state: 'bout-timeout', at: now, boutId, result: String(t.state), reason: String(t.reason || '') })
      // 到点没交＝这一拍没接：跟「答错重打」分开记，升级阶梯按 timedOut 分类。
      try {
        if (presetRt.isActive('ladder-perfunctory')) ladderAnswer('', { timedOut: true, now })
        presetRt.perfunctory(now, lastPhone)
      } catch (error) {
        console.error('[companion] 超时记阶梯失败：' + String((error && error.message) || error))
      }
      brainRt.noteBout({ protocolId: live.protocol?.id, outcome: 'bad' }, now)
      commitLiveNode('overdue_locked')
      dropLive()
      return t
    }
    const n = live.official.nudge(now)
    if (n.due) {
      record({ state: 'bout-nudge', at: now, boutId: live.official.boutId, stepId: String(n.stepId || ''), leftSec: Number(n.leftSec) || 0 })
    }
    return n.due ? n : t
  }

  // 第八轮 P1/P2：计划的节拍。每分钟一次，只做两件事——把今天的计划维护在文件里，
  // 到点了交给 startBout。引擎挂了一段时间也不会多开一局：过点太久的在 planwire 里
  // 直接记成空局（LATE_SKIP_MIN），追补会把人连着按在椅子上开三局。
  function planBeat() {
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    // P4：每分钟先补两件事——E1′（舱发出后没人进舱）和沉迷那一串（L1/L2/L3）。
    // 两件都只读手机快照；没有快照就都不动（不凭空判一次锁）。
    try {
      ladderE1Prime(now)
      // 沉迷那一串归「沉迷升级阶梯」这条内置预案管：停用了就不判。
      if (presetRt.isActive('ladder-immersion')) ladderTick(now)
    } catch (error) {
      console.error('[companion] 升级阶梯失败：' + String((error && error.message) || error))
    }
    try {
      boutBeat(now)
    } catch (error) {
      console.error('[companion] 局内节拍失败：' + String((error && error.message) || error))
    }
    const day = startOfDay(now, tz)
    const r = tick({
      now,
      day,
      tz,
      config,
      min: zoneMinuteOfDay(now, tz),
      stored: readState(stateFile('plan.json'), null),
      running: Boolean(live?.official && !live.official.view().closed),
      known: [...builtinProtocols().filter((x) => x.ok).map((x) => x.protocol.id), ...(thinkingProtocol() ? ['thinking'] : [])],
      // 第九轮第四项：昨天几乎没做成事 → 今天的草案只有一个 15 分钟的最小局。
      restart: restartDue(now, tz),
    })
    if (r.action !== 'hold' && r.action !== 'wait-confirm') writeJson(stateFile('plan.json'), r.plan)
    // 挂着的「改松」到点了就在这里落地：这是唯一每分钟都跑一次的地方，不另开定时器。
    const pendAt = Number(config.dayPendingAt) || 0
    if (pendAt > 0 && now >= pendAt) {
      try {
        saveSettings(settingsOf(config.dayPending?.settings || config), '冷却期到点')
      } catch (error) {
        console.error('[companion] 冷却期落地失败：' + String((error && error.message) || error))
      }
    }
    if (r.action === 'draft' || r.action === 'confirm') record({ state: 'plan-' + r.action, at: now, items: r.plan.items.length })
    if (r.action === 'skip') record({ state: 'plan-skip', at: now, why: r.why })
    if (!r.start) return r
    const started = startBout(r.start, { apps: r.apps, packet: r.packet, by: 'plan' })
    record({ state: 'plan-open', at: now, protocolId: r.start, ok: started.ok })
    // 局开了＝他已经回到轨道上：L1 压下来的计划模态到这里结束（施工书 P1「只能以一份计划生效结束」）。
    if (started.ok) {
      const doc = loadLadderDoc()
      if (doc.planUntil) saveLadderDoc({ ...doc, planUntil: 0 })
    }
    if (!started.ok) {
      // 开不起来就记成空局：不然后面每一分钟都重试同一局。
      const plan = planOf(r.plan, day)
      const item = plan.items.find((i) => !i.openedAt && !i.missed && i.protocolId === r.start)
      if (item) item.missed = started.toast || '没开起来'
      writeJson(stateFile('plan.json'), plan)
    }
    return r
  }
  const planTimer = setInterval(() => {
    try {
      planBeat()
    } catch (error) {
      // 计划出问题不能把引擎带走：下一分钟再来。
      console.error('[companion] 计划节拍失败：' + String((error && error.message) || error))
    }
  }, 60_000)
  planTimer.unref?.()
  ctx.effect?.(() => () => clearInterval(planTimer))

  const rejection = (req) => {
    const connection = ctx.get?.('connection')
    return connection === undefined ? 503 : connection.requestRejection(req)
  }

  const handler = async (req, res) => {
    const denied = rejection(req)
    if (denied !== undefined) {
      res.writeHead(denied, { 'cache-control': 'no-store' })
      res.end()
      return
    }
    const url = new URL(req.url, 'http://local')
    const path = url.pathname.slice(PREFIX.length)
    try {
      if (req.method === 'POST' && (path === '/request' || path === '/cancel' || path === '/config' || path === '/intensity' || path === '/plan' || path === '/outcome' || path === '/say' || path === '/event' || path === '/session' || path === '/wall' || path === '/wall/appeal' || path === '/wall/boss-brief' || path === '/wall/boss-chat' || path === '/sisters' || path === '/deposit' || path === '/mount' || path === '/drill-op' || path === '/workflows' || path === '/gemini/due' || path === '/bouts' || path === '/presets' || path === '/plan/day' || path === '/brain' || path === '/emergency-pass' || path === '/lease' || path === '/elastic-check' || path === '/protocols/compose' || path === '/departments' || path === '/clock' || path === '/dice/roll' || path === '/dice/thought' || path === '/memory/import' || path === '/handoff' || path === '/today/start' || path === '/today/task' || path === '/horizons' || path === '/bank' || path === '/plan-contract' || path === '/plan-modality' || path === '/super-spark' || path === '/live-core' || path === '/v6' || path === '/skills/audit' || path === '/analytics' || path === '/storyline' || path === '/rotate' || path === '/card' || path === '/tiers' || path === '/seals' || path === '/covenant' || path === '/gemini-bridge' || path === '/health' || path === '/review')) {
        const body = await readJson(req)
        if (body.error) {
          return send(res, body.error, {
            v: 1,
            error: {
              code: body.error === 415 ? 'unsupported-media-type' : body.error === 413 ? 'too-large' : 'bad-request',
              message: 'body',
            },
          })
        }
        if (path === '/request' && body.value && typeof body.value === 'object') {
          // /plan 刚决定"下一球是姐姐"：把这一球送进姐姐的会话。
          if (nextSpeaker && Date.now() - nextSpeaker.at < 5 * 60_000 && !body.value.sessionId) {
            if (nextSpeaker.role === 'strategist' && config.strategistSessionId) body.value.sessionId = config.strategistSessionId
            strategistBallReason.set(String(body.value.requestId || ''), nextSpeaker)
            nextSpeaker = null
          }
        }
        if (path === '/request') {
          // Start the clock here, not in the Desk callback: the accepted callback is not the only
          // entry point and this is the one moment that is certainly before the model runs.
          const rid = String(body.value?.requestId ?? '')
          if (rid) startedAt.set(rid, Date.now())
          const r = await desk.request(body.value)
          if (r.status >= 400) {
            console.error('[companion] request refused ' + r.status + ' ' + String(r.body?.error?.code || ''))
          }
          return send(res, r.status, r.body)
        }
        if (path === '/cancel') {
          const r = desk.cancel(String(body.value?.requestId ?? ''))
          return send(res, r.status, r.body)
        }
        if (path === '/plan') return send(res, 200, planRoute(body.value))
        if (path === '/say') {
          const said = String(body.value?.text ?? '').trim()
          rememberHuman(said)
          if (typeof body.value?.timeZone === 'string' && body.value.timeZone) sayZone = body.value.timeZone
          if (!said) return send(res, 400, { v: 1, error: { code: 'empty', message: 'text' } })
          const id = typeof body.value?.requestId === 'string' && body.value.requestId ? body.value.requestId : 'say-' + randomUUID()
          startedAt.set(id, Date.now())
          // role=strategist：这句话给姐姐（谋士会话）；默认给妹妹（陪伴会话）。
          // 没指定就回给刚才主动找他的那位（30 分钟内）：姐姐来找他，他在浮层里回的就是姐姐。
          const wanted = body.value?.role || (lastBall.role === 'strategist' && Date.now() - lastBall.at < 30 * 60_000 ? 'strategist' : 'companion')
          const toStrategist = wanted === 'strategist' && config.dual !== false && config.strategistSessionId
          // 先记"他在聊"，再进会话：手机在他发第一句时会马上问一次 /plan，那一问可能比这句话的
          // user/message 事件先到；不先记上就会再发一球，把浮层里正在聊的窗口顶掉。
          const target = toStrategist ? config.strategistSessionId : config.sessionId
          if (target) lastHumanAt.set(String(target), Date.now())
          record({ state: 'say-text', at: Date.now(), to: toStrategist ? 'strategist' : 'companion', text: stripInjection(said).slice(0, 300), via: 'overlay' })
          const r = await chat.request({
            v: 1, requestId: id, eventId: id, kind: 'say', payload: { text: said.slice(0, 2_000) }, deadlineMs: 120_000,
            ...(toStrategist ? { sessionId: config.strategistSessionId } : {}),
          })
          return send(res, r.status, r.body)
        }
        if (path === '/outcome') return send(res, 200, outcomeRoute(body.value))
        if (path === '/gemini/due') return send(res, 200, { v: 1, launch: dueGeminiLaunch(Date.now()) })
        if (path === '/event') return send(res, 200, eventRoute(body.value))
        if (path === '/session') return send(res, 200, sessionRoute(body.value))
        if (path === '/bouts') return send(res, 200, await boutsRoute(body.value))
        if (path === '/dice/roll') return send(res, 200, diceRollRoute(body.value || {}))
        if (path === '/dice/thought') {
          const out = diceThoughtRoute(body.value || {})
          return send(res, out.ok ? 200 : 400, out)
        }
        if (path === '/memory/import') return send(res, 200, importVaultMemoriesRoute(body.value || {}))
        if (path === '/handoff') return send(res, 200, handoffRoute(body.value || {}))
        if (path === '/today/start') return send(res, 200, todayStartRoute(body.value || {}))
        if (path === '/today/task') return send(res, 200, todayTaskRoute(body.value || {}))
        if (path === '/rotate') return send(res, 200, rotateRoute(body.value || {}))
        if (path === '/card') return send(res, 200, await cardRoute(body.value || {}))
        if (path === '/horizons') return send(res, 200, v4Hub.horizonsRoute(body.value || {}))
        if (path === '/bank') return send(res, 200, v4Hub.bankRoute(body.value || {}))
        if (path === '/plan-contract' || path === '/plan-modality') return send(res, 200, v4Hub.planContractRoute(body.value || {}))
        if (path === '/super-spark') return send(res, 200, superSparkRoute(body.value || {}, 'POST'))
        if (path === '/live-core') return send(res, 200, await liveCoreRoute(body.value || {}, 'POST'))
        if (path === '/v6') return send(res, 200, v6Route(body.value || {}, 'POST'))
        if (path === '/skills/audit') return send(res, 200, v4Hub.skillsAuditRoute(body.value || {}))
        if (path === '/analytics') return send(res, 200, v4Hub.analyticsRoute(body.value || {}))
        if (path === '/storyline') return send(res, 200, v4Hub.storylineRoute(body.value || {}))
        if (path === '/wall') return send(res, 200, await wallRoute(body.value))
        if (path === '/wall/thread') return send(res, 200, wallThreadRoute(body.value))
        if (path === '/wall/boss-brief') return send(res, 200, wallBossBriefRoute(body.value))
        if (path === '/wall/boss-chat') return send(res, 200, await wallBossChatRoute(body.value, 'POST'))
        if (path === '/tiers') return send(res, 200, tiersRoute(body.value))
        if (path === '/seals') return send(res, 200, sealsRoute(body.value))
        if (path === '/covenant') return send(res, 200, covenantRoute(body.value))
        if (path === '/health') return send(res, 200, { v: 1, ok: true, ...healthNow(Date.now()) })
        if (path === '/review') return send(res, 200, reviewRoute(body.value))
        if (path === '/gemini-bridge') return send(res, 200, await bridgeRoute(body.value))
        if (path === '/wall/appeal') return send(res, 200, wallAppealRoute(body.value))
        if (path === '/sisters') return send(res, 200, sistersRoute(body.value))
        if (path === '/tracks') return send(res, 200, tracksRoute(body.value))
      if (path === '/workflows') return send(res, 200, workflowsRoute(body.value))
        if (path === '/deposit') return send(res, 200, depositRoute(body.value))
        if (path === '/mount') return send(res, 200, mountRoute(body.value))
        if (path === '/presets') return send(res, 200, presetsRoute(body.value))
        if (path === '/plan/day') return send(res, 200, planDayRoute(body.value))
        if (path === '/brain') return send(res, 200, brainRoute(body.value))
        if (path === '/drill-op') return send(res, 200, drillOpRoute(body.value))
        if (path === '/emergency-pass') {
          const now = Date.now()
          const tz = zoneOr(config.timeZone)
          const day = dayString(now, tz)
          if (body.value?.action === 'revoke') {
            const cur = loadExternal()
            saveExternal({ ...cur, emergencyPass: null })
            savePassLedger(revokeEmergencyPass(loadPassLedger(now, tz), day))
            record({ state: 'emergency-pass-revoke', at: now })
            return send(res, 200, { v: 1, ok: true, revoked: true })
          }
          const info = intensityStateNow(now, tz)
          const resEp = requestPass(loadPassLedger(now, tz), 'emergency', {
            source: 'emergency-pass',
            reason: body.value?.reason,
            cap: info.emergencyDisarmDailyCap,
            durationMs: body.value?.durationMs,
            now,
            day,
          })
          if (resEp.ok) {
            savePassLedger(resEp.state)
            const cur = loadExternal()
            saveExternal({
              ...cur,
              emergencyPass: {
                active: true,
                grantedAt: now,
                until: resEp.until,
                reason: resEp.why,
                day,
                countToday: resEp.countToday,
              },
            })
            try {
              presetRt.strictOff(now, 'emergency-pass')
              lockBus.revokeBySource('takeover', { now })
              lockBus.revokeBySource('contract', { now })
              lockBus.revokeBySource('radar', { now })
              lockBus.revokeBySource('switch', { now })
              lockBus.revokeBySource('sister', { now })
            } catch {}
            record({ state: 'emergency-pass-grant', at: now, until: resEp.until, reason: resEp.why, countToday: resEp.countToday })
          }
          return send(res, resEp.ok ? 200 : 400, { v: 1, ...resEp, remainingToday: resEp.left })
        }
        if (path === '/intensity') {
          const now = Date.now()
          const tz = zoneOr(config.timeZone)
          const targetTier = body.value?.intensity ?? body.value?.tier
          const act = String(body.value?.action || (targetTier !== undefined ? 'set' : 'get'))
          if (act === 'set' || targetTier !== undefined) {
            const r = applyIntensityChange(config, targetTier, { now, tz, allowRelax: true })
            if (!r.ok) {
              return send(res, 400, { v: 1, ok: false, error: r.error, ...intensityStateNow(now, tz) })
            }
            config = { ...config, intensity: r.intensity, pendingIntensity: r.pendingIntensity, intensityExplicit: true }
            writeConfig(config)
            record({ state: 'intensity-change', at: now, target: targetTier, applied: r.applied, intensity: r.intensity })
            return send(res, 200, { v: 1, ok: true, applied: r.applied, ...intensityStateNow(now, tz) })
          }
          return send(res, 200, { v: 1, ok: true, ...intensityStateNow(now, tz) })
        }
        if (path === '/lease') {
          const now = Date.now()
          const act = String(body.value?.action || 'status')
          const cur = loadExternal()
          if (act === 'issue') {
            const issued = issueAppLease(cur, {
              agentId: body.value?.agentId || 'gemini_live',
              packages: body.value?.packages,
              ttlMs: body.value?.ttlMs,
              topic: body.value?.topic,
              now,
              customConfigs: config.externalAgents,
            })
            if (issued.ok) {
              saveExternal(issued.state)
              record({ state: 'external-lease-issue', at: now, agentId: issued.lease.agentId, until: issued.lease.expiresAt })
            }
            return send(res, issued.ok ? 200 : 400, { v: 1, ok: issued.ok, lease: issued.lease || null, error: issued.error })
          }
          if (act === 'revoke') {
            const rev = revokeAppLease(cur, String(body.value?.reason || 'manual-revoke'))
            saveExternal(rev.state)
            if (rev.hadLease) record({ state: 'external-lease-revoke', at: now, reason: rev.reason })
            return send(res, 200, { v: 1, ok: true, hadLease: rev.hadLease })
          }
          if (act === 'recap-fallback') {
            const fb = evaluateExternalRecapFallback({
              captureFailed: Boolean(body.value?.captureFailed ?? true),
              captureText: body.value?.captureText || '',
              spokenOrTextRecap: body.value?.spokenOrTextRecap || body.value?.recap || '',
              durationSec: body.value?.durationSec,
              agentId: body.value?.agentId || 'gemini_live',
            })
            if (fb.ok) {
              record({ state: 'external-lease-recap', at: now, agentId: fb.agentId, mode: fb.mode, recap: fb.recap })
            }
            return send(res, 200, { v: 1, ...fb })
          }
          return send(res, 200, { v: 1, ok: true, lease: activeAppLease(cur, now), emergencyPass: isEmergencyPassActive(cur, now) ? cur.emergencyPass : null })
        }
        if (path === '/elastic-check') {
          const now = Date.now()
          const tz = zoneOr(config.timeZone)
          const dayStr = dayString(now, tz)
          const resBuf = evaluateElasticBuffer({
            plannedAt: body.value?.plannedAt,
            startedAt: body.value?.startedAt,
            bufferMin: body.value?.bufferMin,
            now,
          })
          const priorFailures = readLedger(home(), 300)
            .filter((e) => e?.state === 'scheduling-failure' && dayString(Number(e.at) || 0, tz) === dayStr)
            .length
          const schedulingFloor = evaluateDailySchedulingFloor({
            failureCountToday: priorFailures + (resBuf.exceeded ? 1 : 0),
          })
          let escalationId = null
          let earnedDeducted = 0
          let fallbackEnqueued = false
          if (resBuf.exceeded) {
            record({ state: 'scheduling-failure', at: now, overdueMin: resBuf.overdueMin, protocolId: resBuf.fallbackBout?.protocolId })
            if (schedulingFloor.lowPowerFloor) {
              record({ state: 'low-power-floor', at: now, failureCountToday: schedulingFloor.failureCountToday })
            }
            let db = null
            try {
              db = openBoutsDb(memoryPath())
              const esc = startEscalation(db, { reason: 'scheduling_failure', level: 'L1', now, home: home() })
              if (esc?.ok) escalationId = esc.id
              if (resBuf.fallbackBout) {
                const existing = getDayPlan(db, dayStr)
                const queue = [...(existing?.queue || [])]
                const recentDup = queue.some((q) => q?.protocolId === resBuf.fallbackBout.protocolId && now - Number(q.queuedAt || 0) < 10 * 60_000)
                if (!recentDup) {
                  queue.push({ ...resBuf.fallbackBout, queuedAt: now, minute: zoneMinuteOfDay(now, tz) })
                  saveDayPlan(db, {
                    id: existing?.id || ('plan:' + dayStr),
                    day: dayStr,
                    template: schedulingFloor.lowPowerFloor ? 'low-power-floor' : (existing?.template || 'elastic-fallback'),
                    queue: schedulingFloor.lowPowerFloor ? [{ ...resBuf.fallbackBout, queuedAt: now, minute: zoneMinuteOfDay(now, tz) }] : queue,
                    edits: [...(existing?.edits || []), { at: now, by: 'elastic-check', reason: schedulingFloor.lowPowerFloor ? 'low_power_floor' : 'scheduling_failure' }],
                    activation: existing?.activation || 'auto',
                    home: home(),
                    now,
                  })
                  fallbackEnqueued = true
                }
              }
            } catch {
              // ignore db fallback error
            } finally {
              try {
                db?.close?.()
              } catch {
                // ignore
              }
            }
            const beforeEarned = todayEarned(now, tz)
            const penaltyMin = Math.min(10, Math.max(5, Math.round(Number(body.value?.penaltyMin) || 5)))
            const spentRes = earnedRt.spend(beforeEarned, penaltyMin)
            earnedDeducted = spentRes.spent
            if (earnedDeducted > 0) saveEarned(spentRes.state)
          }
          return send(res, 200, { v: 1, ...resBuf, schedulingFloor, escalationId, earnedDeducted, fallbackEnqueued })
        }
        if (path === '/protocols/compose') {
          const role = String(body.value?.role || 'strategist')
          const out = composeAndEnqueueBout(body.value?.protocol || body.value || {}, role)
          return send(res, out.ok ? 200 : 400, { v: 1, ...out })
        }
        if (path === '/clock') {
          const rawSpec = (body.value?.task && typeof body.value.task === 'object') ? body.value.task : (body.value || {})
          const act = String(body.value?.action || body.value?.op || (body.value?.task ? 'upsert' : 'list'))
          const now = Date.now()
          if (act === 'upsert') {
            const allow = resolveAppNames(rawSpec?.allowApps || body.value?.allowApps || [], {
              installedApps: Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : [],
              declaredApps: appsOf(),
            }).resolved
            const spec = { ...rawSpec }
            if (spec.kind === 'CYCLE_PHASE' && Array.isArray(spec.phases)) {
              spec.phases = spec.phases.map((p) => ({
                ...p,
                allowApps: resolveAppNames(p.allowApps || allow, {
                  installedApps: Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : [],
                  declaredApps: appsOf(),
                }).resolved,
              }))
            }
            const t = clockKernel.upsert(spec, now)
            return send(res, 200, { v: 1, ok: true, task: t, tasks: clockKernel.list() })
          }
          if (act === 'pause') return send(res, 200, { v: 1, ok: true, task: clockKernel.pause(body.value?.id, now), tasks: clockKernel.list() })
          if (act === 'resume') return send(res, 200, { v: 1, ok: true, task: clockKernel.resume(body.value?.id, now), tasks: clockKernel.list() })
          if (act === 'skip') return send(res, 200, { v: 1, ok: true, event: clockKernel.skipPhase(body.value?.id, now), tasks: clockKernel.list() })
          if (act === 'delete') return send(res, 200, { v: 1, ok: clockKernel.remove(body.value?.id), tasks: clockKernel.list() })
          return send(res, 200, { v: 1, ok: true, tasks: clockKernel.list() })
        }
        if (path === '/departments') {
          const act = String(body.value?.action || 'status')
          const now = Date.now()
          const tz = zoneOr(config.timeZone)
          if (act === 'collect') {
            const out = collectDepartmentSop({
              targetQuestion: body.value?.targetQuestion,
              rawFacts: body.value?.rawFacts,
              source: body.value?.source,
              kb: kbDir(notesDir(), home()),
              at: now,
              tz,
            })
            return send(res, out.ok ? 200 : 400, { v: 1, ...out })
          }
          if (act === 'strategize') {
            const out = strategizeDepartmentSop({
              rawFactCards: body.value?.rawFactCards,
              openQuestionCard: body.value?.openQuestionCard,
              decision: body.value?.decision,
              falsifyPath: body.value?.falsifyPath,
              rationale: body.value?.rationale,
            })
            return send(res, out.ok ? 200 : 400, { v: 1, ...out })
          }
          if (act === 'dispatch') {
            let db = null
            try {
              db = openBoutsDb(memoryPath())
              const out = dispatchDepartmentSop({
                db,
                boutsHistory: body.value?.boutsHistory,
                proposedQueue: body.value?.proposedQueue,
                windowStartMs: body.value?.windowStartMs,
                now,
              })
              return send(res, out.ok ? 200 : 400, { v: 1, ...out })
            } finally {
              try { db?.close?.() } catch {}
            }
          }
          if (act === 'govern') {
            const out = governDepartmentSop({
              lockedQueue: body.value?.lockedQueue,
              verdicts: body.value?.verdicts,
              telemetry: body.value?.telemetry,
              kb: kbDir(notesDir(), home()),
              writeNightly: body.value?.writeNightly !== false,
              at: now,
              tz,
            })
            return send(res, 200, { v: 1, ...out })
          }
          if (act === 'route') {
            let db = null
            try {
              db = openBoutsDb(memoryPath())
              const out = routeBoutDepartment({
                db,
                home: home(),
                boutId: body.value?.boutId || live?.official?.view?.()?.id || '',
                currentDept: body.value?.currentDept || live?.department || '',
                input: body.value?.input || {},
                now,
              })
              return send(res, out.ok ? 200 : 400, { v: 1, ...out })
            } finally {
              try { db?.close?.() } catch {}
            }
          }
          return send(res, 200, { v: 1, ok: true, currentDepartment: live?.department || '' })
        }
        const prevIntensity = config.intensity
        const prevPending = JSON.stringify(config.pendingIntensity || null)
        config = mergeConfig(config, body.value)
        writeConfig(config)
        if (body.value && isValidIntensity(body.value.intensity) &&
          (config.intensity !== prevIntensity || JSON.stringify(config.pendingIntensity || null) !== prevPending)) {
          record({ state: 'intensity-change', at: Date.now(), target: body.value.intensity, intensity: config.intensity, pending: config.pendingIntensity || null })
        }
        // 换了会话 / 名字 / 人设：马上重挂，不等下一次定时检查。
        try {
          mountPersona()
        } catch {
          // ignore
        }
        return send(res, 200, { v: 1, sessionId: config.sessionId ?? null, config: publicConfig() })
      }
      if (req.method === 'GET' && path === '/result') {
        const waitMs = Math.min(Math.max(Number(url.searchParams.get('waitMs') ?? 0) || 0, 0), 25_000)
        const id = String(url.searchParams.get('requestId') ?? '')
        // B1：这一轮不发是决定，不是失败。回 409 + skip-no-content，手机按既有的
        // AgentWire.Skip 静默收尾（不计 notDeliveredStreak、不在这一分钟内重试）。
        if (suppressed.has(id)) {
          return send(res, 409, { v: 1, error: { code: 'skip-no-content', message: '这一轮不发：句子没有信息增量' } })
        }
        const r = await (chat.records.has(id) ? chat : desk).wait(id, waitMs)
        if (r?.body?.state === 'generated' && typeof r.body.text === 'string') {
          const sp = speakerOf(r.body.sessionId)
          let outText = sp === 'strategist' ? stripStrategistAudit(r.body.text).cleanText : r.body.text
          if (config.dual !== false && Boolean(config.strategistSessionId) && sp === 'companion' && isInWithdrawal(loadWithdrawal(), Date.now())) {
            outText = filterWithdrawalOutput(outText, { names: roleNames(config) })
          }
          const n = roleNames(config)
          // 双人格：话前面标上是谁说的（〔鱼丸〕/〔鲸澜〕），浮层据此显示名字。
          const prefix = config.dual !== false ? '〔' + (sp === 'strategist' ? n.strategist : n.companion) + '〕' : ''
          r.body = { ...r.body, ...(config.dual !== false ? { speaker: sp } : {}), text: prefix + outText }
        }
        return send(res, r.status, r.body)
      }
      if (req.method === 'GET' && path === '/presets') return send(res, 200, presetsRoute({ action: 'list' }))
      if (req.method === 'GET' && path === '/bouts') return send(res, 200, boutsGetRoute())
      if (req.method === 'GET' && path === '/today') return send(res, 200, { ...v4Hub.todayViewV4(), locksOverview: buildLocksOverview(Date.now()), liveCore: liveCoreSummary(Date.now()) })
      if (req.method === 'GET' && path === '/horizons') return send(res, 200, v4Hub.horizonsRoute({ action: 'list' }))
      if (req.method === 'GET' && path === '/bank') return send(res, 200, v4Hub.bankRoute({ action: 'status' }))
      if (req.method === 'GET' && path === '/health') return send(res, 200, { v: 1, ok: true, ...healthNow(Date.now()) })
      if (req.method === 'GET' && (path === '/plan-contract' || path === '/plan-modality')) return send(res, 200, v4Hub.planContractRoute({ action: 'status' }))
      if (req.method === 'GET' && path === '/super-spark') return send(res, 200, superSparkRoute(Object.fromEntries(url.searchParams.entries()), 'GET'))
      if (req.method === 'GET' && path === '/live-core') return send(res, 200, await liveCoreRoute(Object.fromEntries(url.searchParams.entries()), 'GET'))
      if (req.method === 'GET' && path === '/v6') return send(res, 200, v6Route(Object.fromEntries(url.searchParams.entries()), 'GET'))
      if (req.method === 'GET' && path === '/autonomy') return send(res, 200, { v: 1, ok: true, autonomy: v4Hub.computeAutonomy() })
      if (req.method === 'GET' && path === '/analytics') {
        return send(res, 200, v4Hub.analyticsRoute({
          action: 'overview',
          day: url.searchParams.get('day') || undefined,
          rangeDays: Number(url.searchParams.get('rangeDays')) || 7,
        }))
      }
      if (req.method === 'GET' && path === '/storyline') return send(res, 200, v4Hub.storylineRoute({ action: 'get' }))
      if (req.method === 'GET' && path === '/usage/report') return send(res, 200, usageReportView())
      if (req.method === 'GET' && path === '/dice/roll') return send(res, 200, diceRollRoute({ manual: false }))
      if (req.method === 'GET' && path === '/memory/import') return send(res, 200, importVaultMemoriesRoute({}))
      if (req.method === 'GET' && path === '/handoff') return send(res, 200, handoffRoute({}))
      if (req.method === 'GET' && path === '/clock') return send(res, 200, { v: 1, ok: true, tasks: clockKernel.list() })
      if (req.method === 'GET' && path === '/plan/day') return send(res, 200, planDayRoute({ action: 'list' }))
      if (req.method === 'GET' && path === '/brain') return send(res, 200, brainRoute({ action: 'view' }))
      if (req.method === 'GET' && path === '/codex') return send(res, 200, codexView(Date.now()))
      if (req.method === 'GET' && path === '/status') {
        return send(res, 200, statusBody())
      }
      if (req.method === 'GET' && path === '/intensity') {
        return send(res, 200, { v: 1, ok: true, ...intensityStateNow(Date.now()) })
      }
      // A3：审查记录是只读的，所以它是 GET；body 里没东西也照样能查。
      if (req.method === 'GET' && path === '/wall/thread') {
        return send(res, 200, wallThreadRoute({ timeZone: url.searchParams.get('timeZone') || undefined }))
      }
      if (req.method === 'GET' && path === '/wall/boss-brief') {
        return send(res, 200, wallBossBriefRoute({ timeZone: url.searchParams.get('timeZone') || undefined }))
      }
      if (req.method === 'GET' && path === '/wall/boss-chat') {
        return send(res, 200, await wallBossChatRoute({ action: 'status', timeZone: url.searchParams.get('timeZone') || undefined }, 'GET'))
      }
      // C1：挂载卡的只读视图（浮层小卡打开时先 GET 一次）。写操作走 POST。
      if (req.method === 'GET' && path === '/mount') {
        return send(res, 200, mountRoute({ timeZone: url.searchParams.get('timeZone') || undefined }))
      }
      // C2：今天该出哪个操作、还剩几题、近 14 天通过率。只读，不记账。
      if (req.method === 'GET' && path === '/drill-op') {
        return send(res, 200, drillOpView({ timeZone: url.searchParams.get('timeZone') || undefined }))
      }
      return send(res, 404, { v: 1, error: { code: 'not-found', message: path } })
    } catch (e) {
      return send(res, 500, { v: 1, error: { code: 'plugin-error', message: String(e?.message ?? e) } })
    }
  }

  function publicConfig() {
    const intInfo = intensityStateNow(Date.now())
    return {
      enabled: config.enabled,
      intensity: intInfo.intensity,
      effectiveIntensity: intInfo.effectiveIntensity,
      autonomyShift: intInfo.autonomyShift,
      pendingIntensity: intInfo.pendingIntensity,
      emergencyDisarmDailyCap: intInfo.emergencyDisarmDailyCap,
      intensityThresholds: intInfo.thresholds,
      refuseUntil: Number(config.refuseUntil) > Date.now() ? Number(config.refuseUntil) : 0,
      lockShadow: config.lockShadow !== false,
      arbiterEnforce: config.arbiterEnforce !== false,
      dayWorkWallEnabled: config.dayWorkWallEnabled !== false,
      sessionId: config.sessionId ?? null,
      quietStart: config.quietStart,
      quietEnd: config.quietEnd,
      dailyMax: config.dailyMax,
      hourlyMax: config.hourlyMax,
      minGapMs: config.minGapMs,
      diceThreshold: config.diceThreshold,
      coachMinGapMs: config.coachMinGapMs,
      coachHourlyMax: config.coachHourlyMax,
      coachDailyMax: config.coachDailyMax,
      allowColdSession: config.allowColdSession,
      owner: config.owner,
      self: config.self,
      timeZone: config.timeZone,
      // 首页的一键安静：App 直接把要静音到的时间写进来。
      muteUntil: Number(config.muteUntil) || 0,
      // 人格卡与模式：首页可以编辑，引擎只管用。
      persona: cleanPersona(config.persona),
      // 笔记库位置：设置页那一栏显示的就是它（留空 = 自动找带 .obsidian 的库）。
      notesPath: typeof config.notesPath === 'string' ? config.notesPath : '',
      // 实际在用的目录（可能来自自动查找）：设置页靠它说清"现在用的是哪个"。
      notesDir: notesDir(),
      // 实际进 prompt 的那张：关系库人物卡 + 上面的覆盖。
      personaEffective: mergePersona(readCorePersona(home()), config.persona),
      // 判球模型（没配就是 null，表示沿用会话）。
      judgeProvider: config.judgeProvider || null,
      judgeModel: config.judgeModel || null,
      // 2026-11-19 H2：破窗不再扣信用分（默认 0）。
      breakglassPenalty: Number(config.breakglassPenalty) || 0,
      // 最近 10 次的实测耗时：发球 / 判球 / 聊天。设置页显示中位数，一眼看出是不是模型慢。
      timings: readTimings(),
      control: config.control !== false,
      drills: config.drills === true,
      personaInChat: config.personaInChat !== false,
      dual: config.dual !== false,
      proactiveTools: config.proactiveTools !== false,
      strategistSessionId: config.strategistSessionId ?? null,
      wallSessionId: config.wallSessionId ?? null,
      companionName: roleNames(config).companion,
      strategistName: roleNames(config).strategist,
      companionCard: config.companionCard || '',
      strategistCard: config.strategistCard || '',
      // 大脑现在对他的判断（会随证据改）和脾气的底色。
      beliefs: Object.values(loadBrain().beliefs || {}).map((b) => b.text),
      temper: readState(stateFile('temper.json'), null),
      mode: modeOr(config.mode),
      modeUntil: Number(config.modeUntil) || 0,
    }
  }

  // ---------------------------------------------------------------- 2026-11-18 裁决器唯一输出：分层、额度、通行证
  /** 他最近说过的话（核对「他刚说过行」用）。 */
  const recentHuman = []
  // H8：引擎重启后从账本恢复 24 小时内的原话（App 里打的字记在 human，浮层里说的记在 say-text）。
  try {
    const since = Date.now() - 24 * 3_600_000
    for (const e of readLedger(home(), 600)) {
      if ((e?.state === 'human' || e?.state === 'say-text') && typeof e.text === 'string' && e.text.trim() && Number(e.at) >= since) {
        recentHuman.push({ at: Number(e.at), text: e.text.replace(/\s+/g, ' ').trim().slice(0, 200) })
      }
    }
    while (recentHuman.length > 12) recentHuman.shift()
  } catch {
    // 账本读不到就从空开始
  }
  function rememberHuman(text) {
    const t = String(text || '').replace(/\s+/g, ' ').trim().slice(0, 200)
    if (!t) return
    recentHuman.push({ at: Date.now(), text: t })
    while (recentHuman.length > 12) recentHuman.shift()
  }
  let lastInstalled = []
  const tierOverridesNow = () => (config.appTiers && typeof config.appTiers === 'object' ? config.appTiers : {})
  const tierFnNow = () => (pkg, label = '') => tierOf(pkg, { label, overrides: tierOverridesNow(), declaredApps: appsOf() })
  const loadTierPending = () => readState(stateFile('app-tiers-pending.json'), {}) || {}
  const saveTierPending = (p) => writeJson(stateFile('app-tiers-pending.json'), p)
  const TIER_LOOSEN_MS = 24 * 3_600_000
  let tierSeen = { key: '', at: 0 }
  const KNOWN_APP_LABELS = {
    'com.xunmeng.pinduoduo': '拼多多',
    'com.ss.android.ugc.aweme': '抖音',
    'tv.danmaku.bili': '哔哩哔哩',
    'com.tencent.tmgp.sgame': '王者荣耀',
    'com.tencent.mm': '微信',
    'com.tencent.mobileqq': 'QQ',
    'com.xingin.xhs': '小红书',
    'com.smile.gifmaker': '快手',
    'com.kuaishou.nebula': '快手极速版',
    'com.taobao.taobao': '淘宝',
    'com.jingdong.app.mall': '京东',
    'com.google.android.apps.bard': 'Gemini',
    'md.obsidian': 'Obsidian',
    'com.android.chrome': 'Chrome',
    'com.quark.browser': '夸克',
  }
  function appDisplayLabel(pkg, explicitLabel = '') {
    const p = String(pkg || '').trim()
    if (!p) return ''
    if (explicitLabel && explicitLabel !== p && !explicitLabel.includes('.')) return String(explicitLabel).trim()
    const fromPhone = (Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : []).find((a) => (a?.pkg || a?.package) === p)
    if (fromPhone?.label) return String(fromPhone.label)
    const fromInst = lastInstalled.find((a) => (a?.pkg || a?.package) === p)
    if (fromInst?.label) return String(fromInst.label)
    const fromToday = (Array.isArray(lastPhone?.today) ? lastPhone.today : []).find((a) => a?.app === p)
    if (fromToday?.label) return String(fromToday.label)
    const fromCfg = appsOf().find((a) => (a?.package || a?.pkg) === p)
    if (fromCfg?.label) return String(fromCfg.label)
    return KNOWN_APP_LABELS[p] || (explicitLabel ? String(explicitLabel).trim() : p)
  }
  function tierLabels() {
    const out = {}
    for (const a of lastInstalled) if (a?.pkg) out[a.pkg] = a.label || ''
    return out
  }
  function writeTierFile() {
    const kb = vaultKb()
    if (!kb) return
    try {
      writeVaultText(join(kb, WALL_DIR, TIER_FILE), renderTierFile(config.appTiers || {}, tierLabels()))
      const st = statOf(join(kb, WALL_DIR, TIER_FILE))
      tierSeen = { key: st ? st.mtimeMs + ':' + st.size : '', at: Date.now() }
    } catch {
      // 写不进去就算了：生效的是 config
    }
  }
  // ---------------------------------------------------------------- 2026-11-21 体检
  let lastPlanAt = 0
  function healthNow(now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const today = dayString(now, tz)
    const safe = (fn, d = null) => {
      try {
        return fn()
      } catch {
        return d
      }
    }
    const info = safe(() => intensityStateNow(now, tz), null)
    const pl = safe(() => loadPassLedger(now, tz), null)
    const vv = safe(() => verifyView(now, tz), {}) || {}
    const seals = safe(() => loadSeals(now, tz), { items: [] })
    const todays = seals.items.filter((x) => x.day === today)
    const lock = safe(() => lockingSeal(seals, { now, today }), null)
    const cov = safe(() => syncCovenantNow(now), { rules: [], proposals: [] })
    const t = safe(() => templatesToday(now, tz), null)
    const kind = safe(() => dayKindOf(now, tz, config), 'workday')
    const tp = t ? (vv.recovery ? t.recovery : kind === 'workday' ? t.workday : kind === 'restday' ? t.restday : null) : null
    const br = safe(() => loadBridgeState(), {})
    const emCap = Number(info?.emergencyDisarmDailyCap) || 0
    const emUsed = Number(pl?.emergency?.count) || 0
    const facts = {
      now, lastPlanAt,
      lockShadow: config.lockShadow !== false,
      hardCapped: Boolean(info?.hardInterventions?.capped),
      emergencyLeft: emCap ? Math.max(0, emCap - emUsed) : null,
      vault: safe(() => vaultKb(), '') || '',
      bankCount: safe(() => bankCount(), 0),
      verifyEnabled: vv.enabled !== false, verifyActive: vv.active === true, recovery: vv.recovery === true,
      timelineToday: todays.length,
      timelinePassed: todays.filter((x) => x.status === 'floor' || x.status === 'stretch').length,
      pastApplyAt: Boolean(tp && tp.applyAt != null && zoneMinuteOfDay(now, tz) >= tp.applyAt && vv.enabled !== false),
      sealLocking: lock ? '「' + lock.title + '」' + lock.actual + '/' + lock.floor : '',
      covenantRules: (cov.rules || []).length,
      covenantPending: (cov.proposals || []).filter((p) => p.status === 'open').length,
      eveningActive: Boolean(vv.evening?.active), eveningHit: Boolean(vv.evening?.hit), eveningUsed: vv.evening?.used || 0, eveningCap: vv.evening?.cap || 0,
      balance: safe(() => timeLedgerNow(now, tz, lastPhone).funLeft, null),
      bridgeConfigured: safe(() => bridgeConf().enabled, false), bridgeHealthy: safe(() => bridgeHealthy(now), false), bridgeError: br?.lastError || '',
      reviewDue: safe(() => reviewDueCount(now), 0),
    }
    const items = healthItems(facts)
    return { items, summary: healthSummary(items), text: healthText(items) }
  }
  /** 每天一行写进 大肥鱼/体检/年-月.md（7 点以后第一拍）：出问题时翻得到是哪天哪一环先断的。 */
  function healthDaily(now, tz) {
    if (zoneMinuteOfDay(now, tz) < 7 * 60) return
    const today = dayString(now, tz)
    const st = readState(stateFile('health.json'), null) || {}
    if (st.day === today) return
    const h = healthNow(now)
    writeJson(stateFile('health.json'), { day: today, at: now, summary: h.summary, history: [...(Array.isArray(st.history) ? st.history : []), { day: today, line: h.summary.line }].slice(-60) })
    record({ state: 'health-daily', at: now, ok: h.summary.ok, warn: h.summary.warn, error: h.summary.error })
    try {
      const kb = vaultKb()
      if (kb) {
        const path = join(kb, '体检', today.slice(0, 7) + '.md')
        const prev = readVaultText(path) || '# ' + today.slice(0, 7) + ' · 体检\n\n每天早上第一拍记一行：哪一环断了一眼看得到。\n'
        writeVaultText(path, prev.replace(/\s+$/, '') + '\n- ' + today.slice(5) + ' ' + zoneHm(now, tz) + '  ' + h.summary.line + '\n')
      }
    } catch {}
  }
  function healthTool() {
    return {
      name: 'health_check',
      description: '体检：看体系哪一环断了（手机有没有在对表、测试模式、笔记库、题库、默认验收、时间轴、公约、Gemini 桥、复习卡）。他说「是不是又坏了」「锁怎么不灵」时先调它，照着说哪里断了、去哪修。',
      parameters: { type: 'object', properties: {}, additionalProperties: false },
      output: toolOutput,
      execute: () => ({ ok: true, text: healthNow(Date.now()).text }),
    }
  }

  // ---------------------------------------------------------------- 2026-11-21 超级验收者的底层设定
  // 规则在 verifier_mode.js。他不下任何指令也成立：工作时段默认验收（只放常用 App）、到点自动套模板、
  // 连续两天没过保底进恢复日；花挣来的余额要先说一句理由；跟鱼说「开始了」时段外也进。
  const loadVerify = () => readState(stateFile('verify.json'), null) || {}
  const saveVerify = (s) => writeJson(stateFile('verify.json'), s)
  const templatesPath = () => {
    const kb = vaultKb()
    return kb ? join(kb, '模板.md') : stateFile('模板.md')
  }
  /** 今天用的模板：每天第一次用时读一遍 大肥鱼/模板.md，当天不再变（他改了第二天生效）。 */
  function templatesToday(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const today = dayString(now, tz)
    const v = loadVerify()
    if (v.templatesDay === today && v.templates && typeof v.templates === 'object') return v.templates
    let text = null
    try {
      text = readVaultText(templatesPath())
    } catch {}
    if (text === null) {
      text = renderTemplates(DEFAULT_TEMPLATES)
      try {
        writeVaultText(templatesPath(), text)
      } catch {}
    }
    const t = parseTemplates(text)
    saveVerify({ ...loadVerify(), templates: t, templatesDay: today })
    return t
  }
  /** 关默认验收/防分心锁：遵循 Option A (dayWorkWallEnabled) 与明日生效开关。 */
  function verifyEnabled(now = Date.now(), tz = zoneOr(config.timeZone)) {
    if (config.dayWorkWallEnabled === false) return false
    const vm = config.verifyMode && typeof config.verifyMode === 'object' ? config.verifyMode : {}
    // 配置文件里显式写了 enabled:false（测试 / adb）就关；手机上的关闭按钮走 offFrom（明天起）。
    if (vm.enabled === false) return false
    return !(vm.offFrom && dayString(now, tz) >= vm.offFrom)
  }
  function verifyStateNow(now = Date.now(), tz = zoneOr(config.timeZone), phone = lastPhone) {
    const kind = dayKindOf(now, tz, config)
    const min = zoneMinuteOfDay(now, tz)
    let relaxOpen = false
    try {
      const relax = activeRelax(config, now, tz)
      relaxOpen = Boolean(relax?.segments?.some((s) => min >= s.from && min < s.to))
    } catch {}
    const hasCustomWindows = Boolean(config.day?.windows && typeof config.day.windows === 'object')
    const windows = hasCustomWindows
      ? windowsOf(settingsOf(config), kind).map((w) => [w.from, w.to])
      : (kind === 'outing' ? [] : DEFAULT_DAY_WORK_WALL_WINDOWS.map(([f, t]) => [f, t]))
    return defaultVerify({
      now, enabled: verifyEnabled(now, tz),
      windows,
      minuteOfDay: min,
      night: Boolean(lastRhythm?.night),
      emergency: isEmergencyPassOrDisarmActive(now, tz),
      onBreak: onBreak(now),
      meal: Boolean(mealNow(wallConfig(config).meals, localMinute(phone, min))),
      relaxOpen,
      spendUntil: Number(config.spendPass?.until) || 0,
      startUntil: Number(config.verifyStartUntil) || 0,
    })
  }
  /** 默认验收/防分心放行：WORK_DEFAULT + 默认白名单 + 分类表及已安装的所有「work」类 + 当前局应用 + Gemini/Proxy + 急事底线。 */
  function verifyAllow() {
    const tf = tierFnNow()
    const work = []
    const seen = new Set()
    const collectWork = (list) => {
      for (const a of Array.isArray(list) ? list : []) {
        const pkg = String(a?.package || a?.pkg || a || '').trim()
        if (!pkg || seen.has(pkg)) continue
        seen.add(pkg)
        if (tf(pkg, String(a?.label || '')) === 'work') work.push(pkg)
      }
    }
    try { collectWork(appsOf()) } catch {}
    try { collectWork(lastInstalled) } catch {}
    try { collectWork(lastPhone?.installedApps) } catch {}
    const boutApps = live?.apps ? [...(live.apps.need || []), ...(live.apps.allow || [])] : []
    return [...new Set([
      ...WORK_DEFAULT,
      ...defaultAllowApps(),
      ...work,
      ...boutApps,
      ...geminiPackages(),
      ...PROXY_PACKAGES,
      ...RESIDENT_PACKAGES,
      ...emergencyPackages(appsOf()),
    ])].filter(Boolean)
  }
  function verifySessionView(now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const v = verifyStateNow(now, tz)
    if (!v.active) return null
    return {
      allow: verifyAllow(),
      until: startOfDay(now, config.timeZone) + v.untilMinute * 60_000,
      topic: '默认验收',
      why: (v.reason === 'start' ? '你说开工了' : '工作时段') + '：默认验收，只放行常用 App。要玩先跟鱼说一句理由，花挣来的余额。',
      strict: false,
      graceMs: 30_000,
      mode: 'ai-work',
      by: 'verify',
    }
  }
  /** 每拍：一天一次判恢复日；到点没定今天就套模板（恢复日套「恢复日」）。 */
  function verifyTick(now = Date.now(), tz = zoneOr(config.timeZone), phone = lastPhone) {
    try {
      eveningTick(now, tz, phone)
    } catch (error) {
      console.error('[companion] 晚间上限失败：' + String((error && error.message) || error))
    }
    try {
      compactInbox(vaultKb() || home(), { now, tz })
    } catch {}
    const today = dayString(now, tz)
    const min = zoneMinuteOfDay(now, tz)
    let v = loadVerify()
    const past = Array.from({ length: 7 }, (_, i) => dayString(now - (i + 1) * 86_400_000, tz))
    const seals = loadSeals(now, tz)
    const rs = recoveryStep(v.recovery, dayOutcomes(seals, past), past, today)
    if (rs.state.checkedDay !== v.recovery?.checkedDay) {
      v = { ...v, recovery: rs.state }
      saveVerify(v)
      if (rs.entered) {
        record({ state: 'recovery-enter', at: now, day: today })
        try {
          const kb = vaultKb()
          if (kb) writeVaultText(join(kb, '恢复日', today + '.md'), '# ' + today + ' · 恢复日\n\n连续两天没过保底。今天只要一套 10 题（题库代码判），不加码；连续两天交上自动退出。\n')
        } catch {}
      }
      if (rs.exited) record({ state: 'recovery-exit', at: now, day: today })
    }
    if (!verifyEnabled(now, tz)) return
    const kind = dayKindOf(now, tz, config)
    const t = templatesToday(now, tz)
    const tp = v.recovery?.active ? t.recovery : kind === 'workday' ? t.workday : kind === 'restday' ? t.restday : null
    const hasSealsToday = seals.items.some((x) => x.day === today)
    if (!tp || !shouldApplyTemplate({ today, minuteOfDay: min, hasSealsToday, appliedDay: v.appliedDay, applyAt: tp.applyAt })) return
    const items = templateSeals(tp, { minuteOfDay: min })
    const r = items.length ? sealsRoute({ action: 'plan-day', target: 'today', seals: items, by: 'template' }) : { added: [] }
    saveVerify({ ...loadVerify(), appliedDay: today, appliedTemplate: tp.name })
    const noBank = (r.skipped || []).filter((x) => x.error === 'no-bank').length
    record({ state: 'template-applied', at: now, template: tp.name, added: (r.added || []).length, noBank, recovery: Boolean(v.recovery?.active) })
  }
  /** 今晚的娱乐用量（手机报的今天 fun / game 分钟；过了零点手机清零，eveningStep 接着加）。 */
  function eveningNow(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const today = dayString(now, tz)
    const ev = eveningOf(today, dayString(now - 86_400_000, tz), zoneMinuteOfDay(now, tz))
    const st = loadVerify().evening || null
    const cap = eveningCapFor(config.eveningCap, ev.key || today)
    const mine = ev.evening && st && st.key === ev.key ? st : null
    return { ...ev, cap, used: mine ? mine.used : 0, capHitAt: mine ? Number(mine.capHitAt) || 0 : 0 }
  }
  /** 今晚的上限用完了：锁到明早 7:00（返回到点时刻，没锁就 0）。 */
  function eveningLockUntil(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const e = eveningNow(now, tz)
    if (!e.evening || !e.capHitAt) return 0
    const day0 = startOfDay(now, config.timeZone)
    return (e.beforeMidnight ? day0 + 86_400_000 : day0) + EVENING_END * 60_000
  }
  function eveningTick(now, tz, phone) {
    const ev = eveningOf(dayString(now, tz), dayString(now - 86_400_000, tz), zoneMinuteOfDay(now, tz))
    if (!ev.evening) return
    const rows = Array.isArray(phone?.today) ? phone.today : (lastPhone?.today || [])
    const tf = tierFnNow()
    let fun = 0
    for (const r of rows) {
      if (!r?.app) continue
      const t = tf(String(r.app), String(r.label || ''))
      if (t === 'fun' || t === 'game') fun += Number(r.minutes) || 0
    }
    const v = loadVerify()
    const cap = eveningCapFor(config.eveningCap, ev.key)
    const r = eveningStep(v.evening, { key: ev.key, beforeMidnight: ev.beforeMidnight, funToday: fun, cap, now })
    if (JSON.stringify(r.state) !== JSON.stringify(v.evening)) saveVerify({ ...loadVerify(), evening: r.state })
    if (r.hit) {
      record({ state: 'evening-cap-hit', at: now, used: r.state.used, cap })
      recordHardNow({ kind: 'evening_cap', source: 'verify', why: '今晚娱乐 ' + cap + ' 分钟用完', now, tz, dedupeKey: 'evening:' + ev.key })
      // 他说过「那还不如直接和 Gemini 互动」：用完那一刻约一次 Gemini（走闸门：深夜 / 安静 / 紧急 / 当天上限都会挡）。
      try {
        const s = loadGeminiSchedule()
        if (!(Number(s.at) > now && s.fired !== true)) {
          saveGeminiSchedule({ at: now, topic: '今晚的娱乐用完了：聊聊今天做成了什么、卡在哪，把明天要交的封了', minutes: 15, mode: 'think', by: 'evening-cap', fired: false })
        }
      } catch {}
    }
  }
  function verifyView(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const v = verifyStateNow(now, tz)
    const st = loadVerify()
    const vm = config.verifyMode && typeof config.verifyMode === 'object' ? config.verifyMode : {}
    const sp = config.spendPass && Number(config.spendPass.until) > now ? config.spendPass : null
    return {
      active: v.active === true, why: v.why || '', reason: v.reason || '', untilMinute: v.untilMinute ?? null,
      enabled: verifyEnabled(now, tz), offFrom: vm.offFrom || '',
      recovery: Boolean(st.recovery?.active), recoverySince: st.recovery?.since || '',
      spend: sp ? { until: Number(sp.until), minutes: sp.minutes, reason: sp.reason } : null,
      startUntil: Number(config.verifyStartUntil) > now ? Number(config.verifyStartUntil) : 0,
      appliedDay: st.appliedDay || '', appliedTemplate: st.appliedTemplate || '',
      templatesPath: templatesPath(), inVault: Boolean(vaultKb()),
      evening: (() => {
        const e = eveningNow(now, tz)
        return { active: e.evening, cap: e.cap, used: e.used, hit: Boolean(e.capHitAt), lockUntil: eveningLockUntil(now, tz), next: config.eveningCap?.next || null }
      })(),
    }
  }
  function daytimeSpentMinToday(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const today = dayString(now, tz)
    let sum = 0
    for (const e of readLedger(home(), 600)) {
      if (!e || e.state !== 'balance-spend') continue
      const eDay = e.day || (Number(e.at) ? dayString(Number(e.at), tz) : '')
      if (eDay !== today) continue
      const eWin = e.window || (Number(e.at) ? classifySpendWindow(zoneMinuteOfDay(Number(e.at), tz)) : '')
      if (eWin === 'daytime') {
        sum += Math.max(0, Math.round(Number(e.minutes) || 0))
      }
    }
    return sum
  }
  function spendGateStatus(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const today = dayString(now, tz)
    const min = zoneMinuteOfDay(now, tz)
    const window = classifySpendWindow(min)
    const sealsState = loadSeals(now, tz)
    const overdueSeal = lockingSeal(sealsState, { now, today })
    const overdueContract = Boolean((() => { try { return v4Hub?.contractSessionView?.(now, { escalatedOnly: true }) } catch { return null } })())
    const overdueDayBlock = Boolean((() => { try { return v4Hub?.dayBlockOverdueSessionView?.(now) } catch { return null } })())
    const arbiterNow = (() => { try { return v4Hub?.computeLockArbiter?.(now) } catch { return null } })()
    const verifyOverdue = Boolean(overdueSeal || overdueContract || overdueDayBlock || arbiterNow?.priority === 'P3' || arbiterNow?.source === 'CONTRACT_OVERDUE_STRICT')
    const eveningLocked = Boolean(eveningLockUntil(now, tz))
    const wdRaw = wallDecision(wallCfgNow(), now, tz)
    const nightLocked = Boolean(lastRhythm?.night === 'lock' || (wdRaw.locked && wdRaw.kind === 'night'))
    const todaySeals = sealsState.items.filter((x) => x.day === today)
    const dayVerify = (() => { try { return v4Hub?.getDayVerifySummary?.(now, sealsState.items) } catch { return null } })()
    const floorPassed = todaySeals.some((x) => x.status === 'floor' || x.status === 'stretch') || Boolean(dayVerify?.allVerifyCleared)
    const daytimeSpentMin = daytimeSpentMinToday(now, tz)
    let tlNow = null
    try {
      tlNow = timeLedgerNow(now, tz, lastPhone)
    } catch {}
    const earnedBankBonus = (!floorPassed && Number(tlNow?.earnedBank) > 0)
      ? Math.min(30, Math.round(Number(tlNow.earnedBank) || 0))
      : 0
    const daytimeCapMin = DAYTIME_SPEND_CAP_MIN + earnedBankBonus
    const daytimeRem = Math.max(0, daytimeCapMin - daytimeSpentMin)

    let spendBlockedReason = ''
    if (verifyOverdue) spendBlockedReason = 'verify-overdue'
    else if (eveningLocked) spendBlockedReason = 'evening-cap'
    else if (nightLocked) spendBlockedReason = 'night-lock'
    else if (window === 'daytime' && !floorPassed && daytimeRem < SPEND_MIN) spendBlockedReason = 'daytime-cap'

    return {
      today,
      min,
      window,
      floorPassed,
      daytimeSpentMin,
      daytimeCapMin,
      daytimeRem,
      tlNow,
      spendBlockedReason,
    }
  }
  function spendBalance({ minutes, reason, by = 'panel' }, now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const gate = spendGateStatus(now, tz)
    const { today, window, floorPassed, daytimeSpentMin, daytimeCapMin, daytimeRem, tlNow, spendBlockedReason } = gate
    if (spendBlockedReason === 'verify-overdue') {
      return {
        ok: false,
        error: 'verify-overdue',
        text: '当前有已到点未过保底的验收点或计划验收锁生效中，请先交付验收或保底再解锁，不可用零散余额绕过。紧急情况请走 3 分钟应急通行。',
      }
    }
    if (spendBlockedReason === 'evening-cap') {
      return { ok: false, error: 'evening-cap', text: '今晚的娱乐上限已经用完了，锁到明早 7 点。余额留着明天用。' }
    }
    if (spendBlockedReason === 'night-lock') {
      return { ok: false, error: 'night-lock', text: '已进入深夜睡眠护栏（锁到明早 7 点），余额留着明天用。' }
    }
    if (spendBlockedReason === 'daytime-cap') {
      return {
        ok: false,
        error: 'daytime-cap',
        text: daytimeSpentMin >= daytimeCapMin
          ? `白天工作时段零散花余额已达 ${daytimeCapMin} 分钟上限。12:00–14:00 午休及 18:00 后可自由花余额，或先过今日保底立即解除白天限制。`
          : `白天工作时段零散槽位仅剩 ${daytimeRem} 分钟（不足 ${SPEND_MIN} 分钟最低起花），12:00–14:00 午休及 18:00 后可自由花余额，或先过今日保底立即解除白天限制。`,
      }
    }
    const balance = Number(tlNow?.funLeft) || 0
    const rawMin = Math.round(Number(minutes) || 0)
    const effectiveMinutes = window === 'daytime' && !floorPassed ? Math.min(rawMin, daytimeRem) : rawMin
    const c = checkSpend({ minutes: effectiveMinutes, reason, balance })
    if (!c.ok) return c
    const until = now + c.minutes * 60_000
    commit({ spendPass: { until, minutes: c.minutes, reason: c.reason, at: now, by, window } })
    record({ state: 'balance-spend', at: now, day: today, window, minutes: c.minutes, reason: c.reason, by })
    const truncated = window === 'daytime' && !floorPassed && rawMin > c.minutes && c.minutes === daytimeRem && balance >= daytimeRem
    const capNote = truncated ? `（白天未过保底零散上限剩 ${daytimeRem} 分钟，已截断按 ${c.minutes} 分钟放行）` : ''
    return {
      ok: true,
      minutes: c.minutes,
      window,
      until,
      text: '好，' + c.minutes + ' 分钟（到 ' + zoneHm(until, tz) + '）' + capNote + '。理由记下了：' + c.reason + '。花完自动回到默认验收。',
    }
  }
  function startWork({ hours, sprint = false, by = 'panel' }, now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const c = checkStart({ hours })
    commit({ verifyStartUntil: now + c.ms, spendPass: null })
    record({ state: 'work-start', at: now, hours: Math.round(c.ms / 360_000) / 10, sprint: Boolean(sprint), by })
    let added = []
    if (sprint) {
      const t = templatesToday(now, tz)
      const r = sealsRoute({ action: 'plan-day', target: 'today', seals: templateSeals(t.sprint, { minuteOfDay: zoneMinuteOfDay(now, tz) }), by: by === 'gemini' ? 'gemini' : '' })
      added = r.added || []
    }
    return {
      ok: true,
      text: '开工：默认验收到 ' + zoneHm(now + c.ms, tz) + '（只放常用 App）。' +
        (added.length ? '冲刺验收点：' + added.map((x) => '「' + x.title + '」' + sealHm(x.dueMinute) + ' 前').join('、') + '，下一拍自动开局。' : ''),
    }
  }
  function startWorkTool(role) {
    return {
      name: 'start_work',
      description: '他说「开始了 / 开工 / 进入状态 / 冲刺」时用：立刻进默认验收（只放常用 App，时段外也算），可选冲刺——按模板加一条从现在起算的验收点（默认 90 分钟内题库 30 题）。',
      parameters: {
        type: 'object',
        properties: {
          hours: { type: 'number', description: '默认验收多久（小时，默认 2，最多 6）' },
          sprint: { type: 'boolean', description: '他说「冲刺」时为 true' },
        },
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => startWork({ hours: args.hours, sprint: args.sprint === true, by: role }),
    }
  }
  function eveningCapTool(role) {
    return {
      name: 'set_evening_cap',
      description: '他说要调晚上的娱乐上限时用（「以后晚上给 2 小时」「这周累，晚上多给点」「晚上只给 1 小时」）。' +
        '调低立即生效；调高从明天起（今晚不变）——晚上临时求加不算数。范围 30–180 分钟。',
      parameters: {
        type: 'object',
        properties: { minutes: { type: 'integer', description: '每晚娱乐上限（分钟）' }, reason: { type: 'string', description: '他的原话' } },
        required: ['minutes'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const tz = zoneOr(config.timeZone)
        const today = dayString(now, tz)
        const r = setEveningCap(config.eveningCap, args.minutes, { today, tomorrow: nextYmd(today) })
        if (!r.ok) return { ok: false, text: r.text }
        commit({ eveningCap: r.value })
        record({ state: 'evening-cap-set', at: now, by: role, minutes: Number(args.minutes), immediate: r.immediate, reason: String(args.reason || '').slice(0, 80) })
        return { ok: true, text: r.text }
      },
    }
  }
  function spendBalanceTool(role) {
    return {
      name: 'spend_balance',
      description: '他要玩（刷视频、打游戏）时用：先让他说一句理由，再花他挣来的娱乐余额（5–60 分钟，不超过余额）。没理由、没余额就花不了——这不是放松，是他自己挣的。',
      parameters: {
        type: 'object',
        properties: {
          minutes: { type: 'integer', description: '花几分钟' },
          reason: { type: 'string', description: '他的原话理由' },
        },
        required: ['minutes', 'reason'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => spendBalance({ minutes: args.minutes, reason: args.reason, by: role }),
    }
  }

  // ---------------------------------------------------------------- 2026-11-20 公约
  // 真源：笔记库 大肥鱼/公约.md（没有笔记库时放 companion/公约.md）。生效版在 covenant.json：
  // 改严立即进生效版；改松等 3 天（公约-待生效.md）；两条鱼的提议在 公约-提议.md，他批了才写进 公约.md。
  const loadCovenant = () => {
    const raw = readState(stateFile('covenant.json'), null)
    return {
      v: 1,
      seeded: raw?.seeded === true,
      rules: Array.isArray(raw?.rules) ? raw.rules.map(cleanRule).filter(Boolean) : [],
      pending: raw?.pending && typeof raw.pending === 'object' ? raw.pending : {},
      proposals: Array.isArray(raw?.proposals) ? raw.proposals.map(cleanProposal).filter(Boolean) : [],
    }
  }
  const saveCovenant = (s) => writeJson(stateFile('covenant.json'), s)
  const covenantDir = () => {
    const kb = vaultKb()
    return kb || ''
  }
  const covenantPath = () => {
    const kb = covenantDir()
    return kb ? join(kb, COVENANT_FILE) : stateFile(COVENANT_FILE)
  }
  let covenantSeen = { key: '', at: 0 }
  function writeCovenantSide(state, now) {
    const kb = covenantDir()
    if (!kb) return
    const fmtDay = (at) => dayString(at, zoneOr(config.timeZone))
    try {
      writeVaultText(join(kb, COVENANT_PENDING_FILE), renderPendingCovenant(state.pending, state.rules, fmtDay, COVENANT_COOLDOWN_MS))
      writeVaultText(join(kb, COVENANT_PROPOSALS_FILE), renderProposals(state.proposals, sisterNames(), fmtDay))
    } catch {}
  }
  const sisterNames = () => ({ companion: '鱼丸', strategist: '鲸澜', gemini: 'Gemini', ...(config.names && typeof config.names === 'object' ? config.names : {}) })
  /** 文件 → 生效版。force：手机上刚改过，不等 mtime 节流。 */
  function syncCovenantNow(now = Date.now(), { force = false } = {}) {
    const path = covenantPath()
    const st = statOf(path)
    const key = path + ':' + (st ? st.mtimeMs + ':' + st.size : 'none')
    if (!force && key === covenantSeen.key && now - covenantSeen.at < 60_000) return loadCovenant()
    covenantSeen = { key, at: now }
    let state = loadCovenant()
    const text = readVaultText(path)
    if (text === null) {
      // 第一次：没有文件就写草稿（已经有生效版就照生效版写回）。
      const rules = state.rules.length ? state.rules : [...DRAFT_RULES]
      writeVaultText(path, renderCovenant(rules))
      state = { ...state, seeded: true, rules, pending: {} }
      saveCovenant(state)
      writeCovenantSide(state, now)
      record({ state: 'covenant-seed', at: now, count: rules.length })
      return state
    }
    const fileRules = parseCovenant(text)
    if (!state.seeded) {
      // 他先自己写了文件（或从电脑同步过来）：第一次照单全收。
      state = { ...state, seeded: true, rules: fileRules, pending: {} }
      saveCovenant(state)
      writeCovenantSide(state, now)
      return state
    }
    const r = syncCovenant(state, fileRules, now, COVENANT_COOLDOWN_MS)
    if (r.changed) {
      state = { ...state, rules: r.rules, pending: r.pending }
      saveCovenant(state)
      writeCovenantSide(state, now)
      if (r.applied.tighten.length || r.applied.loosen.length) {
        record({ state: 'covenant-change', at: now, tighten: r.applied.tighten, loosen: r.applied.loosen })
      }
    }
    return state
  }
  /** 这一轮的场景：给公约挑「默认 / 建议」用。 */
  function covenantScenes(now = Date.now()) {
    const out = []
    const id = mainStateNow()?.id || 'standby'
    const map = { morning: '早上', review: '对账', followup: '追问', sprint: '干活', brain: '干活', meal: '休息', break: '休息', standby: '待命' }
    if (map[id]) out.push(map[id])
    if (runningBoutNow()) out.push('干活')
    if (lastRhythm?.night) out.push('深夜')
    if (lastLockActive) out.push('锁着')
    try {
      const tz = zoneOr(config.timeZone)
      const today = dayString(now, tz)
      if (loadSeals(now, tz).items.some((x) => x.day === today && (x.status === 'open' || x.status === 'below'))) out.push('有封印')
    } catch {}
    return [...new Set(out)]
  }
  function covenantText(role) {
    try {
      const now = Date.now()
      const st = syncCovenantNow(now)
      return covenantBlock(st.rules, { role, scenes: covenantScenes(now), maxChars: 760 })
    } catch {
      return ''
    }
  }
  /**
   * 两条鱼：把他说的今天（或明天）的安排落成时间轴上的验收点。只能加，不能改小、不能删——
   * 和他自己在手机上加走同一条规矩（开工前定的不打折，开工后现加的七折）。
   */
  function addCheckpointsTool(role) {
    return {
      name: 'add_checkpoints',
      description: '把他定下的安排写成时间轴验收点（到开始时刻自动开局、到截止时刻代码判、没过保底就锁）。' +
        '只在他明确说了要做什么、几点前交的时候用；每条要能被代码验：quiz（DSH 题库答对数）/ external_quiz（外部 App 刷题，要 app 包名）/ ' +
        'file（笔记库里某个文件的字数增量，要 path）/ screenshots（截图张数）/ app_minutes（某 App 用时，要 app）/ ' +
        'bout_minutes（通过验收的局的分钟数）/ gemini_receipt（Gemini 回执条数）/ study_cards（学习制卡张数：交卡当天发一半，隔天复习答对再发另一半）。你只能加，不能改小、不能删。',
      parameters: {
        type: 'object',
        properties: {
          target: { type: 'string', description: 'today（今天的时间轴）/ tomorrow（明天的封印）' },
          items: {
            type: 'array',
            maxItems: 8,
            items: {
              type: 'object',
              properties: {
                title: { type: 'string', description: '交什么，一句话' },
                kind: { type: 'string', description: 'quiz / external_quiz / file / screenshots / app_minutes / bout_minutes / gemini_receipt / study_cards' },
                stretch: { type: 'integer', description: '拉伸目标（按 kind 的单位）' },
                floor: { type: 'integer', description: '保底（不填就是拉伸的一半）' },
                start: { type: 'string', description: '开始时刻 HH:MM（可不填，按截止前预计分钟算）' },
                due: { type: 'string', description: '截止时刻 HH:MM' },
                estMinutes: { type: 'integer', description: '预计分钟' },
                path: { type: 'string', description: 'file 类：笔记库里的路径' },
                app: { type: 'string', description: 'external_quiz / app_minutes 类：App 包名' },
              },
              required: ['title', 'kind', 'stretch', 'due'],
              additionalProperties: false,
            },
          },
        },
        required: ['items'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const hm = (v) => {
          const m = /^(\d{1,2})[:：](\d{2})$/.exec(String(v || '').trim())
          return m ? Number(m[1]) * 60 + Number(m[2]) : null
        }
        const seals = (Array.isArray(args.items) ? args.items : []).map((x) => ({
          title: x.title, kind: x.kind, stretch: x.stretch, floor: x.floor, path: x.path, app: x.app, estMinutes: x.estMinutes,
          dueMinute: hm(x.due), startMinute: hm(x.start),
        }))
        const r = sealsRoute({ action: 'plan-day', target: args.target === 'tomorrow' ? 'tomorrow' : 'today', seals, by: 'ai' })
        record({ state: 'checkpoints-by-ai', at: Date.now(), by: role, added: (r.added || []).length, skipped: (r.skipped || []).length })
        const added = (r.added || []).map((x) => '「' + x.title + '」' + sealHm(x.dueMinute) + ' 前' + (x.sameDay ? '（现加，七折）' : '')).join('、')
        const skipped = (r.skipped || []).map((x) => '「' + x.title + '」：' + (x.text || x.error)).join('；')
        return { ok: Boolean(r.ok), text: (added ? '加上了：' + added + '。' : '一条都没加上。') + (skipped ? ' 没加的：' + skipped : '') }
      },
    }
  }
  /** 两条鱼：提一条新规矩（只能加；放松没有通道）。 */
  function proposeCovenantTool(role) {
    return {
      name: 'propose_covenant',
      description: '向他提议公约里加一条新规矩（你看到他反复出现某种情况、现有公约没管到时用）。' +
        '只能加新规矩，不能改、不能删、不能放松任何一条；他在手机「公约」页批了才生效。每条鱼同时最多 3 条在等批。',
      parameters: {
        type: 'object',
        properties: {
          chapter: { type: 'integer', description: '放进第几章：1 铁律 / 2 处置 / 3 分工 / 4 边界 / 5 他对你们的要求' },
          title: { type: 'string', description: '什么情况（≤60 字），如「他找同一个借口」' },
          do: { type: 'string', description: '怎么处理（≤400 字）' },
          who: { type: 'string', description: '都 / 鲸澜 / 鱼丸' },
          hardness: { type: 'string', description: '铁律 / 默认 / 建议' },
          scenes: { type: 'array', items: { type: 'string' }, description: '场景：任何时候、早上、干活、待命、休息、追问、锁着、有封印、对账、深夜' },
          why: { type: 'string', description: '为什么提（看到了什么，≤200 字）' },
        },
        required: ['chapter', 'title', 'do', 'hardness', 'why'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const now = Date.now()
        const st = syncCovenantNow(now)
        const r = addProposal(st.proposals, { ...args, by: role, who: args.who || '都' }, now)
        if (!r.ok) return { ok: false, text: r.text }
        const next = { ...st, proposals: r.proposals }
        saveCovenant(next)
        writeCovenantSide(next, now)
        record({ state: 'covenant-propose', at: now, by: role, id: r.proposal.id, title: r.proposal.rule.title })
        return { ok: true, text: '提上去了（' + r.proposal.id + '），等他在「公约」页批。批之前它不算数。' }
      },
    }
  }
  /**
   * POST /covenant：list / approve / reject（提议）/ save（他改或加一条）/ remove（他删一条）。
   * 他在手机上改，和在 Obsidian 里改走同一条路：写进 公约.md，再按改严 / 改松分类。
   */
  function covenantRoute(body) {
    const b = body && typeof body === 'object' ? body : {}
    const action = String(b.action || 'list')
    const now = Date.now()
    const view = (st, extra = {}) => ({
      v: 1, ok: true, rules: st.rules, pending: st.pending, proposals: st.proposals.filter((p) => p.status === 'open'),
      cooldownDays: Math.round(COVENANT_COOLDOWN_MS / 86_400_000), inVault: Boolean(covenantDir()), path: covenantPath(), names: sisterNames(), ...extra,
    })
    let st = syncCovenantNow(now, { force: action === 'list' })
    if (action === 'list') return view(st)
    const path = covenantPath()
    const text = readVaultText(path) ?? renderCovenant(st.rules)
    if (action === 'approve' || action === 'reject') {
      const p = st.proposals.find((x) => x.id === String(b.id || ''))
      if (!p || p.status !== 'open') return { v: 1, ok: false, error: 'no-proposal' }
      let ruleId = ''
      if (action === 'approve') {
        ruleId = nextIdIn(parseCovenant(text), p.chapter)
        writeVaultText(path, insertRuleInText(text, { id: ruleId, ...p.rule }))
      }
      const proposals = st.proposals.map((x) => (x.id === p.id ? { ...x, status: action === 'approve' ? 'approved' : 'rejected', decidedAt: now, ruleId } : x))
      saveCovenant({ ...st, proposals })
      record({ state: 'covenant-' + action, at: now, id: p.id, ruleId })
      st = syncCovenantNow(now, { force: true })
      writeCovenantSide(st, now)
      return view(st, { ruleId })
    }
    if (action === 'save') {
      const raw = b.rule && typeof b.rule === 'object' ? b.rule : {}
      const id = raw.id ? String(raw.id) : nextIdIn(parseCovenant(text), raw.chapter)
      const rule = cleanRule({ ...raw, id })
      if (!rule) return { v: 1, ok: false, error: 'bad-rule', text: '要有标题（什么情况）和做法。' }
      writeVaultText(path, replaceRuleInText(text, id, rule))
      st = syncCovenantNow(now, { force: true })
      return view(st, { ruleId: id, waiting: Boolean(st.pending[id]) })
    }
    if (action === 'remove') {
      const id = String(b.id || '')
      writeVaultText(path, replaceRuleInText(text, id, null))
      st = syncCovenantNow(now, { force: true })
      return view(st, { ruleId: id, waiting: Boolean(st.pending[id]) })
    }
    return { v: 1, ok: false, error: 'bad-action' }
  }

  // Gemini 桥运行态（bridgeConf/bridgeHealthy/bridgeSnapshot/bridgeSync/ensureBridgeTimer/bridgeRoute）已拆至 bridge_runtime.js

  // ---------------------------------------------------------------- 2026-11-20 封印（验收第一批）
  // 规则全在 seal.js（纯函数）；这里只有读写、量证据、发奖励、锁的接线。
  // 真账：companion/seals.json（当前）+ seals.jsonl（只追加的流水）；笔记库镜像：大肥鱼/封印/日期.md。
  const loadSeals = (now = Date.now(), tz = zoneOr(config.timeZone)) =>
    cleanSeals(readState(stateFile('seals.json'), null), dayString(now, tz))
  function saveSeals(state, now, tz, event) {
    writeJson(stateFile('seals.json'), state)
    if (event) {
      try {
        const sealsLog = stateFile('seals.jsonl')
        compactJsonlFile(sealsLog, 512 * 1024, 300)
        appendFileSync(sealsLog, JSON.stringify({ at: now, ...event }) + '\n', 'utf8')
      } catch {}
    }
    mirrorSealsToVault(state, now, tz)
  }
  const sealHm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
  function mirrorSealsToVault(state, now, tz) {
    const kb = vaultKb()
    if (!kb) return
    const today = dayString(now, tz)
    for (const day of [today, nextYmd(today)]) {
      const items = state.items.filter((x) => x.day === day)
      if (!items.length) continue
      const st = { open: '进行中', floor: '过保底', stretch: '过拉伸', below: '没过保底', waived: '豁免' }
      const lines = ['# ' + day + ' · 封印', '', '> 前一晚冷静时立的约：只能加码，不能调低。打分只看代码验得了的证据。', '',
        '| 验收点 | 证据 | 拉伸 / 保底 | 截止 | 做到 | 状态 | 奖励 |', '|---|---|---|---|---|---|---|']
      for (const x of items) {
        lines.push('| ' + x.title + (x.sameDay ? '（当天现定）' : '') + ' | ' + SEAL_KINDS[x.kind].label + ' | ' + x.stretch + ' / ' + x.floor + SEAL_KINDS[x.kind].unit +
          ' | ' + sealHm(x.dueMinute) + ' | ' + x.actual + ' | ' + st[x.status] + (x.waivedReason ? '：' + x.waivedReason : '') + ' | ' + x.paidMinutes + ' 分钟 |')
      }
      try {
        writeVaultText(join(kb, '封印', day + '.md'), lines.join('\n') + '\n')
      } catch {}
    }
  }
  // ---- 时间轴的两份小账：局分钟（按验收点）、Gemini 回执
  const loadSealBouts = (day) => {
    const raw = readState(stateFile('seal-bouts.json'), null)
    return raw && raw.day === day && raw.bySeal && typeof raw.bySeal === 'object' ? raw : { day, bySeal: {}, bouts: [] }
  }
  function addSealBoutMinutes(sealId, boutId, minutes, now, tz) {
    const day = dayString(now, tz)
    const cur = loadSealBouts(day)
    const bouts = Array.isArray(cur.bouts) ? cur.bouts : []
    if (bouts.some((b) => b.boutId && b.boutId === boutId)) return
    const add = Math.max(0, Math.round(Number(minutes) || 0))
    const next = { day, bySeal: { ...cur.bySeal, [sealId]: (Number(cur.bySeal[sealId]) || 0) + add }, bouts: [...bouts, { sealId, boutId, minutes: add, at: now }].slice(-40) }
    writeJson(stateFile('seal-bouts.json'), next)
    record({ state: 'seal-bout', at: now, sealId, boutId, minutes: add })
  }
  const loadGeminiReceipts = () => {
    const raw = readState(stateFile('gemini-receipts.json'), null)
    return { v: 1, items: Array.isArray(raw?.items) ? raw.items : [] }
  }
  /**
   * 一条 Gemini 回执进账（Gemini 桥 / 以后的「分享到 DSH」都走这里）。
   * 同时记一条 gemini-recap（晚上对账、接管解除都认它），并追加到笔记库 大肥鱼/Gemini/回执/日期.md。
   */
  function recordGeminiReceipt(input, { via = 'bridge', now = Date.now() } = {}) {
    const tz = zoneOr(config.timeZone)
    const day = dayString(now, tz)
    const clip = (v, m) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, m)
    const qa = (Array.isArray(input?.qa) ? input.qa : []).slice(0, 8).map((x) => ({ q: clip(x?.q, 200), a: clip(x?.a, 400) })).filter((x) => x.q)
    const verdict = input?.verdict === 'pass' ? 'pass' : input?.verdict === 'fail' ? 'fail' : 'partial'
    let sealId = clip(input?.sealId || input?.checkpointId, 40)
    const seals = loadSeals(now, tz)
    if (sealId && !seals.items.some((x) => x.id === sealId && x.day === day)) sealId = ''
    const item = {
      id: clip(input?.id, 40) || 'gr-' + now.toString(36) + Math.random().toString(36).slice(2, 5),
      at: now, day, via, sealId,
      topic: clip(input?.topic, 120), did: clip(input?.did, 600), next: clip(input?.next, 200), qa, verdict,
      // 过不过由 Gemini 判，但只有带了至少 2 问 2 答的「过」才算数——空口的「过」不进验收点。
      counted: verdict === 'pass' && qa.filter((x) => x.a.length >= 4).length >= 2,
    }
    if (item.verdict === 'pass' && !item.counted) item.verdict = 'partial'
    const cur = loadGeminiReceipts()
    if (cur.items.some((x) => x.id === item.id)) return { ok: true, duplicate: true, receipt: item }
    writeJson(stateFile('gemini-receipts.json'), { v: 1, items: [...cur.items, item].slice(-300) })
    record({ state: 'gemini-recap', at: now, receipt: true, via, topic: item.topic, summary: (item.did + ' ' + item.next).slice(0, 200), sealId, verdict: item.verdict })
    try {
      const kb = vaultKb()
      if (kb) {
        const path = join(kb, 'Gemini', '回执', day + '.md')
        const prev = readVaultText(path) || '# ' + day + ' · Gemini 回执\n'
        const block = ['', '## ' + zoneHm(now, tz) + ' ' + (item.topic || '回执') + '（' + ({ pass: '过', fail: '没过', partial: '半过' })[item.verdict] + '）',
          item.did ? '- 做了：' + item.did : '', item.next ? '- 下一步：' + item.next : '', sealId ? '- 验收点：' + sealId : '',
          ...qa.map((x) => '- 问：' + x.q + '\n  答：' + x.a)].filter(Boolean).join('\n')
        writeVaultText(path, prev.replace(/\s+$/, '') + '\n' + block + '\n')
      }
    } catch {}
    // 判「过」的回执里像样的问答（题干 ≥6 字、答 ≥4 字）自动收成复习卡，一次最多 4 张。
    let cards = 0
    try {
      cards = ingestReviewCards(receiptToCards(item), { source: 'gemini_receipt', now, max: 4 }).added
    } catch {}
    try {
      sealTick(now, tz, lastPhone)
    } catch {}
    return { ok: true, receipt: item, cards }
  }
  // 复习卡 FSRS 与周包运行态（loadReviewCards..weekPackNow）已拆至 review_runtime.js
  // ---- 【周验收】回收
  const loadWeekReviews = () => {
    const raw = readState(stateFile('week-review.json'), null)
    return { v: 1, items: (Array.isArray(raw?.items) ? raw.items : []).map(cleanReview).filter(Boolean) }
  }
  function saveWeekReviews(state, review, now) {
    writeJson(stateFile('week-review.json'), state)
    try {
      const kb = vaultKb()
      if (kb && review) writeVaultText(join(kb, '周验收', dayString(review.at || now, zoneOr(config.timeZone)) + '.md'), reviewMarkdown(review, (t) => dayString(t, zoneOr(config.timeZone))))
    } catch {}
  }
  function receiveWeekReview(text, { by = 'paste', now = Date.now(), parsed = null } = {}) {
    const p = parsed || parseWeekReview(text)
    if (!p.ok) return p
    const r = addReview(loadWeekReviews(), p, { by, now })
    saveWeekReviews(r.state, r.review, now)
    record({ state: 'week-review', at: now, by, n: r.review.adjustments.length })
    return { ok: true, review: r.review }
  }
  const pendingWeekReview = () => loadWeekReviews().items.find((r) => r.status === 'pending') || null
  /** 题库里有几道题：题库类验收点要靠它，题库空着就别立（立了也只能被锁）。 */
  const bankCount = () => {
    try {
      const b = readState(stateFile('bank.json'), null)
      return Array.isArray(b?.题目) ? b.题目.length : 0
    } catch {
      return 0
    }
  }
  const NO_BANK_TEXT = '题库是空的：题库类验收点做不了（到点只会被锁）。先在「题库」页导入题目。'
  /** 今天开过局没有（开工了没有）：时间轴的冷热线要用。 */
  function workStartedToday(now, tz) {
    if (runningBoutNow()) return true
    const st = loadDrive()
    return String(st.boutDay || '') === dayString(now, tz)
  }
  function sealProtocolOf(kind) {
    if (kind === 'gemini_receipt') return 'thinking'
    if (kind === 'quiz' || kind === 'external_quiz' || kind === 'file') return 'study'
    return 'execute'
  }
  /** 时间轴上一点到了开始时刻：把这一局开起来（工作包写清：交什么、几点前、保底多少）。 */
  function openSealBout(seal, now, tz, by = 'timeline') {
    const due = sealHm(seal.dueMinute)
    const unit = SEAL_KINDS[seal.kind]?.unit || ''
    const tier = seal.app ? tierFnNow()(seal.app) : ''
    const allow = [...new Set(['md.obsidian', ...(seal.app && tier !== 'fun' && tier !== 'game' ? [seal.app] : [])])]
    const minutes = Math.max(10, Math.min(240, seal.dueMinute - zoneMinuteOfDay(now, tz)))
    const r = startBout(sealProtocolOf(seal.kind), {
      goal: seal.title, minutes, by, sealId: seal.id, apps: { need: allow, allow },
      packet: { goal: seal.title, done: '交 ' + seal.stretch + unit + '（保底 ' + seal.floor + unit + '），' + due + ' 前', first: seal.kind === 'file' ? '打开 ' + seal.path : '' },
    })
    if (r.ok) {
      const run = runningBoutNow()
      const m = markOpened(loadSeals(now, tz), seal.id, { boutId: run?.id || '', now })
      if (m.ok) saveSeals(m.state, now, tz, { type: 'opened', id: seal.id, boutId: run?.id || '' })
    }
    return r
  }

  function sealFacts(now, tz, phone, state) {
    const today = dayString(now, tz)
    const todays = state.items.filter((x) => x.day === today && x.status !== 'waived')
    if (!todays.length) return null
    const bank = readState(stateFile('bank-unlock.json'), null)
    const fileBytes = {}
    for (const s of todays) {
      if (s.kind !== 'file') continue
      const dir = notesDir()
      const p = s.path.startsWith('/') ? s.path : (dir ? join(dir, s.path) : '')
      const st = p ? statOf(p) : null
      fileBytes[s.path] = st ? st.size : -1
    }
    let screenshotsSince = 0
    if (todays.some((s) => s.kind === 'screenshots' || s.kind === 'external_quiz')) {
      try {
        screenshotsSince = listScreenshots(SCREENSHOT_DIRS, startOfDay(now, config.timeZone)).length
      } catch {
        screenshotsSince = 0
      }
    }
    const appMinutes = {}
    for (const r of (Array.isArray(phone?.today) ? phone.today : (lastPhone?.today || []))) {
      if (r?.app) appMinutes[r.app] = Number(r.minutes) || 0
    }
    // 2026-11-20 时间轴：通过验收的局记到哪一点上；Gemini 桥写进来的回执（判过「过」的才算）。
    const today2 = dayString(now, tz)
    const boutMinutes = { ...(loadSealBouts(today2).bySeal || {}) }
    const receipts = {}
    for (const r of loadGeminiReceipts().items) {
      if (r.day === today2 && r.sealId && r.verdict === 'pass') receipts[r.sealId] = (receipts[r.sealId] || 0) + 1
    }
    const studyCards = todays.some((s) => s.kind === 'study_cards') ? studyCardCounts(now, tz) : {}
    return { bankCorrect: Number(bank?.stats?.correct) || 0, fileBytes, screenshotsSince, appMinutes, boutMinutes, receipts, studyCards }
  }
  /** 每拍一次：记基线 → 量证据 → 过档就发奖励 → 到点没过保底就进锁。 */
  function sealTick(now, tz, phone) {
    const today = dayString(now, tz)
    const before = loadSeals(now, tz)
    if (!before.items.some((x) => x.day === today)) return
    const facts = sealFacts(now, tz, phone, before)
    if (!facts) return
    const cap = captureBaselines(before, facts, { now, today })
    const dayStart = startOfDay(now, config.timeZone)
    const r = tickSeals(cap.state, facts, { now, today, dueAtOf: (s) => dayStart + s.dueMinute * 60_000 })
    for (const p of r.pays) {
      recordTimeEntryNow({ kind: 'earned_seal', minutes: p.minutes, reason: '封印「' + p.title + '」' + (p.tier === 'stretch' ? '过拉伸' : '过保底') + (p.late ? '（晚交）' : ''), at: now, tz })
      record({ state: 'seal-pay', at: now, id: p.id, tier: p.tier, minutes: p.minutes, late: p.late })
    }
    const locked = r.state.items.filter((x) => x.status === 'below' && !cap.state.items.some((y) => y.id === x.id && y.status === 'below'))
    for (const s of locked) {
      record({ state: 'seal-below', at: now, id: s.id, actual: s.actual, floor: s.floor, until: s.lockUntil })
      recordHardNow({ kind: 'seal_lock', source: 'seal', why: '封印「' + s.title + '」到点没过保底', now, tz, dedupeKey: 'seal:' + s.id })
    }
    if (cap.changed || r.changed) {
      saveSeals(r.state, now, tz, (r.pays.length || locked.length) ? { type: 'tick', pays: r.pays, locked: locked.map((x) => x.id) } : null)
    }
  }
  /** 锁：到点没过保底的那一条，走和契约逾期同一条严格锁通道（不新增判锁路径）。 */
  function sealSessionView(now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const s = lockingSeal(loadSeals(now, tz), { now, today: dayString(now, tz) })
    if (!s) return null
    const tf = tierFnNow()
    const tier = s.app ? tf(s.app) : ''
    // 2026-11-23 验收点到点没过保底：放「能把这一点交上」的那一层——Obsidian、AI（Gemini / ChatGPT / Claude）、
    // NekoBox、学习层 App、这一点要的 App。以前只放 Obsidian + 这一点的 App，要交「局的分钟 / Gemini 回执」时没地方做。
    const workInstalled = (Array.isArray(lastInstalled) ? lastInstalled : []).map((x) => String(x?.pkg || '')).filter((p) => p && tf(p) === 'work')
    const allow = [...new Set([
      'md.obsidian', ...WORK_DEFAULT, ...workInstalled, ...GEMINI_PACKAGES, ...PROXY_PACKAGES,
      ...(s.app && tier !== 'fun' && tier !== 'game' ? [s.app] : []), ...emergencyPackages(appsOf()),
    ])]
    return {
      allow,
      until: s.lockUntil,
      topic: s.title,
      why: '封印「' + s.title + '」到点没过保底（' + s.actual + '/' + s.floor + SEAL_KINDS[s.kind].unit + '）：交够保底就解，最长到 ' + sealHm(Math.round((s.lockUntil - startOfDay(now, config.timeZone)) / 60_000)),
      strict: true,
      graceMs: 0,
      mode: 'ai-work',
      by: 'seal',
    }
  }
  /**
   * 4.5 Gemini 温和折算汇总（只记账不拦人）。
   */
  function geminiAccountingNow(now = Date.now(), tz = zoneOr(config.timeZone), sealsOpt = null) {
    const today = dayString(now, tz)
    let geminiMinutesToday = 0
    try {
      geminiMinutesToday = Math.max(0, Math.round(Number(v4Hub?.getGeminiStats?.(today, now)?.minutesToday) || 0))
    } catch {}
    const receiptsToday = loadGeminiReceipts().items.filter((r) => r && r.day === today)
    const sealsState = sealsOpt || loadSeals(now, tz)
    const todaySeals = (Array.isArray(sealsState?.items) ? sealsState.items : []).filter((x) => x.day === today)
    const floorMetToday = todaySeals.length > 0 && todaySeals.every((x) => ['floor', 'stretch', 'waived'].includes(x.status))
    return computeGeminiGentleAccounting({
      geminiMinutesToday,
      receiptsToday,
      floorMetToday,
    })
  }
  // 锁与余额大盘视图（formatRecentActionEntry / buildLocksOverview）已拆至 locks_overview_runtime.js

  /**
   * POST /seals：list / add（封明天或当天现定）/ raise（只能加码）/ claim（外部刷题报数）/ waive（写理由、扣一次紧急解除）/ check。
   * 没有「改小」「删除」这两个动作——这是故意的。
   */
  function sealsRoute(body) {
    const b = body && typeof body === 'object' ? body : {}
    const action = String(b.action || 'list')
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    const today = dayString(now, tz)
    const min = zoneMinuteOfDay(now, tz)
    const view = (st, extra = {}) => ({
      v: 1, ok: true, today, tomorrow: nextYmd(today), minuteOfDay: min, cutoffMinute: SEAL_CUTOFF_MINUTE, cutoffPassed: min >= SEAL_CUTOFF_MINUTE,
      kinds: SEAL_KINDS, items: st.items.filter((x) => x.day === today || x.day === nextYmd(today)),
      coldCutoffMinute: COLD_CUTOFF_MINUTE, workStarted: workStartedToday(now, tz), weekReview: pendingWeekReview(),
      arbiterEnforce: config.arbiterEnforce !== false,
      geminiAccounting: geminiAccountingNow(now, tz, st),
      verify: (() => {
        try {
          return verifyView(now, tz)
        } catch {
          return null
        }
      })(),
      locking: lockingSeal(st, { now, today }),
      locksOverview: (() => {
        try {
          return buildLocksOverview(now, tz, st)
        } catch {
          return null
        }
      })(),
      ...extra,
    })
    if (action === 'locks_overview') {
      try {
        sealTick(now, tz, lastPhone)
      } catch {}
      try {
        v4Hub?.dayBlockTickNow?.(now)
      } catch {}
      return { v: 1, ok: true, locksOverview: buildLocksOverview(now, tz) }
    }
    if (action === 'list' || action === 'check') {
      sealTick(now, tz, lastPhone)
      return view(loadSeals(now, tz))
    }
    const state = loadSeals(now, tz)
    if (action === 'add') {
      if (b.seal?.kind === 'quiz' && bankCount() === 0) return { v: 1, ok: false, error: 'no-bank', text: NO_BANK_TEXT }
      const r = addSeal(state, b.seal || {}, { now, today, minuteOfDay: min, workStarted: workStartedToday(now, tz), by: b.by === 'gemini' ? 'gemini' : '' })
      if (!r.ok) return { v: 1, ok: false, error: r.error, text: r.text }
      saveSeals(r.state, now, tz, { type: 'add', seal: r.seal })
      record({ state: 'seal-add', at: now, id: r.seal.id, day: r.seal.day, sameDay: r.seal.sameDay, title: r.seal.title })
      return view(r.state, { seal: r.seal })
    }
    if (action === 'week') {
      const pack = weekPackNow(now, { fresh: true })
      record({ state: 'week-pack', at: now, checkpoints: pack.totals.checkpoints })
      return { v: 1, ok: true, ...pack }
    }
    if (action === 'spend') {
      const r = spendBalance({ minutes: b.minutes, reason: b.reason, by: 'panel' }, now)
      if (!r.ok) return { v: 1, ok: false, error: r.error, text: r.text }
      return { ...view(state), until: r.until, minutes: r.minutes, text: r.text }
    }
    if (action === 'start') {
      const r = startWork({ hours: b.hours, sprint: b.sprint === true, by: 'panel' }, now)
      return { ...view(loadSeals(now, tz)), text: r.text }
    }
    if (action === 'evening-cap') {
      const r = setEveningCap(config.eveningCap, b.minutes, { today, tomorrow: nextYmd(today) })
      if (!r.ok) return { v: 1, ok: false, error: r.error, text: r.text }
      commit({ eveningCap: r.value })
      record({ state: 'evening-cap-set', at: now, by: 'panel', minutes: Number(b.minutes), immediate: r.immediate })
      return { ...view(state), text: r.text }
    }
    if (action === 'toggle_day_wall') {
      const curEnabled = config.dayWorkWallEnabled !== false && config.verifyMode?.enabled !== false
      const nextEnabled = b.enabled === undefined
        ? !curEnabled
        : !(b.enabled === false || b.enabled === 'false' || b.enabled === 0)
      const nextVerifyMode = nextEnabled
        ? { ...(config.verifyMode || {}), enabled: true, offFrom: '' }
        : { ...(config.verifyMode || {}), offFrom: '' }
      commit({ dayWorkWallEnabled: nextEnabled, verifyMode: nextVerifyMode })
      record({ state: 'toggle-day-wall', at: now, enabled: nextEnabled, by: String(b.by || 'panel') })
      return {
        ...view(state),
        dayWorkWallEnabled: nextEnabled,
        text: nextEnabled ? '已开启「工作时段默认防分心锁（Option A）」。' : '已关闭「工作时段默认防分心锁（Option A）」。',
      }
    }
    if (action === 'strict_off') {
      const ovNow = buildLocksOverview(now, tz, state)
      if (ovNow.activeLock?.verifyOverdue || ovNow.winnerId === 'NIGHT_CURFEW' || ovNow.winnerId === 'CONTRACT_OVERDUE_STRICT') {
        return {
          ...view(state),
          ok: false,
          error: 'hard-lock',
          text: '当前有逾期验收锁、封印、接管或深夜睡眠护栏生效，无法直接撤回；请先交付验收通过或走应急通道。',
        }
      }
      if (ovNow.winnerId === 'BOUT_STRICT' && !presetRt.sessionView(now)) {
        return {
          ...view(state),
          ok: false,
          error: 'dayblock-active',
          text: '当前正由今日计划时间块自动接管中，非手动严格局；请完成该块或提交交付验收。',
        }
      }
      presetRt.strictOff(now, String(b.by || 'panel-probe'))
      return { ...view(state), text: '已撤回当前手动严格锁。' }
    }
    if (action === 'verify-off') {
      // 关默认验收是放松：明天起才不默认验收（今天照旧）。
      commit({ verifyMode: { ...(config.verifyMode || {}), offFrom: nextYmd(today) } })
      record({ state: 'verify-off', at: now, from: nextYmd(today) })
      return { ...view(state), text: '明天起不再默认验收；今天照旧。想反悔随时打开。' }
    }
    if (action === 'verify-on') {
      commit({ verifyMode: { ...(config.verifyMode || {}), offFrom: '' } })
      record({ state: 'verify-on', at: now })
      return { ...view(state), text: '默认验收开着。' }
    }
    if (action === 'week-review') {
      const r = receiveWeekReview(String(b.text || ''), { by: 'paste', now })
      if (!r.ok) return { v: 1, ok: false, error: r.error, text: r.text }
      return { ...view(state), review: r.review }
    }
    if (action === 'week-decide') {
      const r = decideReview(loadWeekReviews(), String(b.id || ''), b.accepted, { now })
      if (!r.ok) return { v: 1, ok: false, error: r.error }
      saveWeekReviews(r.state, r.review, now)
      record({ state: 'week-decide', at: now, id: r.review.id, accepted: r.review.adjustments.filter((a) => a.accepted).length })
      return { ...view(state), review: r.review }
    }
    if (action === 'plan-day') {
      // 一次定一串（时间轴）：合格的加，不合格的说明原因。题库空着时题库类的先挑出来（立了也只能被锁）。
      const all = Array.isArray(b.seals) ? b.seals : []
      const noBank = bankCount() === 0
      const blocked = noBank ? all.filter((x) => x?.kind === 'quiz') : []
      const r = addSeals(state, all.filter((x) => !(noBank && x?.kind === 'quiz')).map((x) => ({ ...x, target: b.target === 'tomorrow' ? 'tomorrow' : 'today' })),
        { now, today, minuteOfDay: min, workStarted: workStartedToday(now, tz), by: ['gemini', 'ai', 'template'].includes(b.by) ? b.by : '' })
      if (r.added.length) {
        saveSeals(r.state, now, tz, { type: 'plan-day', ids: r.added.map((x) => x.id), by: String(b.by || 'user') })
        record({ state: 'seal-plan-day', at: now, n: r.added.length, by: String(b.by || 'user') })
      }
      const skipped = [...blocked.map((x) => ({ title: String(x?.title || ''), error: 'no-bank', text: NO_BANK_TEXT })), ...r.skipped]
      return { ...view(r.added.length ? r.state : state), ok: r.ok, added: r.added, skipped }
    }
    if (action === 'open') {
      // 他在手机上点「现在开这一局」。
      const seal = state.items.find((x) => x.id === String(b.id || '') && x.day === today)
      if (!seal) return { v: 1, ok: false, error: 'no-seal' }
      const r = openSealBout(seal, now, tz, 'panel')
      return { ...view(loadSeals(now, tz)), ok: r.ok, toast: r.toast }
    }
    if (action === 'raise') {
      const r = raiseSeal(state, String(b.id || ''), { stretch: b.stretch, floor: b.floor }, { now })
      if (!r.ok) {
        if (r.error === 'cannot-lower') record({ state: 'seal-lower-refused', at: now, id: String(b.id || '') })
        return { v: 1, ok: false, error: r.error, text: r.text }
      }
      saveSeals(r.state, now, tz, { type: 'raise', id: r.seal.id, stretch: r.seal.stretch, floor: r.seal.floor })
      return view(r.state, { seal: r.seal })
    }
    if (action === 'claim') {
      const r = claimSeal(state, String(b.id || ''), b.count)
      if (!r.ok) return { v: 1, ok: false, error: r.error }
      saveSeals(r.state, now, tz, { type: 'claim', id: r.seal.id, count: r.seal.claimed })
      sealTick(now, tz, lastPhone)
      return view(loadSeals(now, tz), { seal: r.seal })
    }
    if (action === 'waive') {
      const reason = String(b.reason || '').trim()
      const pre = waiveSeal(state, String(b.id || ''), reason)
      if (!pre.ok) return { v: 1, ok: false, error: pre.error, text: pre.text }
      // 豁免占当天一次紧急解除（和紧急解除 / 破窗 / 长按回 DSH 同一个次数），但不开放行窗口。
      const info = intensityStateNow(now, tz)
      const ledgerBefore = loadPassLedger(now, tz)
      const prevUntil = Number(ledgerBefore?.emergency?.until) || 0
      const pl = requestPass(ledgerBefore, 'emergency', { source: 'seal-waive', reason: '豁免封印：' + reason, cap: info.emergencyDisarmDailyCap, now, day: today })
      if (!pl.ok) return { v: 1, ok: false, error: 'no-emergency-left', text: pl.why || '今天的紧急解除次数用完了，不能豁免。' }
      // 不开新的放行窗口：已经开着的紧急窗口保持原样，否则 until 停在此刻。
      savePassLedger({ ...pl.state, emergency: { ...pl.state.emergency, until: prevUntil > now ? prevUntil : now } })
      saveSeals(pre.state, now, tz, { type: 'waive', id: pre.seal.id, reason })
      record({ state: 'seal-waive', at: now, id: pre.seal.id, reason: reason.slice(0, 80) })
      return view(pre.state, { seal: pre.seal })
    }
    if (action === 'inbox_add') {
      const kb = vaultKb() || home()
      const r = appendInboxEntry(kb, { text: b.text, tag: b.tag || '随记', at: now, tz })
      if (!r.ok) return { v: 1, ok: false, error: r.error }
      record({ state: 'inbox-add', at: now, tag: r.tag })
      return { v: 1, ok: true, entry: r.entry, error: r.error }
    }
    if (action === 'inbox_list') {
      const kb = vaultKb() || home()
      const items = readInboxEntries(kb, { limit: b.limit || 20 })
      return { v: 1, ok: true, items, total: items.length }
    }
    if (action === 'inbox_bundle') {
      const kb = vaultKb() || home()
      const entries = readInboxEntries(kb, { limit: b.limit || 15 })
      const stuckFromInbox = entries
        .filter((e) => e.tag === '卡壳')
        .map((e) => `${e.day ? e.day + ' ' : ''}${e.time ? e.time + ' ' : ''}${e.text}`.trim())
      const locking = lockingSeal(state, { now, today })
      const overdueSeals = state.items.filter((x) => x.day === today && (x.status === 'below' || (locking && x.id === locking.id)))
      const dueReviews = (() => {
        try {
          return reviewSnapshot(now)
        } catch {
          return { dueCount: 0, duePreview: [], weakTopics: [] }
        }
      })()
      const bundle = buildStrategizeBundle({
        kbDir: kb,
        inboxLimit: b.limit || 15,
        stuckItems: [...stuckFromInbox, ...(Array.isArray(b.stuckItems) ? b.stuckItems : [])],
        overdueSeals,
        dueReviews,
        today,
      })
      return {
        v: 1,
        ok: true,
        markdown: bundle.markdown,
        inboxCount: bundle.inboxCount,
        stuckCount: bundle.stuckCount,
        dueReviewCount: bundle.dueReviewCount,
      }
    }
    if (action === 'nod_card_get') {
      const t = templatesToday(now, tz)
      const v = loadVerify()
      const kind = dayKindOf(now, tz, config)
      const tp = v.recovery?.active ? t.recovery : kind === 'restday' ? t.restday : t.workday
      const nod = buildNodCard({
        template: tp,
        existingSeals: state.items.filter((x) => x.day === today),
        minuteOfDay: min,
        today,
      })
      return { v: 1, ok: true, ...nod }
    }
    if (action === 'nod_card_confirm') {
      const t = templatesToday(now, tz)
      const v = loadVerify()
      const kind = dayKindOf(now, tz, config)
      const tp = v.recovery?.active ? t.recovery : kind === 'restday' ? t.restday : t.workday
      const nod = buildNodCard({
        template: tp,
        existingSeals: state.items.filter((x) => x.day === today),
        minuteOfDay: min,
        today,
      })
      if (!nod.needed) {
        return {
          v: 1,
          ok: false,
          error: nod.reason || 'already_has_seals',
          text: '今天已经有验收点了。',
          added: [],
          items: state.items.filter((x) => x.day === today || x.day === nextYmd(today)),
        }
      }
      const edits = Array.isArray(b.items) ? b.items : (Array.isArray(b.edits) ? b.edits : [])
      const sealsToAdd = applyNodCardOverrides(nod.items, edits)
      const r = addSeals(state, sealsToAdd, {
        now,
        today,
        minuteOfDay: min,
        workStarted: workStartedToday(now, tz),
        by: 'template',
      })
      if (r.added.length) {
        saveSeals(r.state, now, tz, { type: 'nod-card-confirm', ids: r.added.map((x) => x.id) })
        saveVerify({ ...loadVerify(), appliedDay: today, appliedTemplate: tp.name || 'nod_card' })
        record({ state: 'nod-card-confirm', at: now, added: r.added.length })
      }
      return {
        ...view(r.added.length ? r.state : state),
        ok: r.added.length > 0 || sealsToAdd.length === 0,
        added: r.added,
        skipped: r.skipped,
      }
    }
    return { v: 1, ok: false, error: 'bad-action' }
  }

  /**
   * 2026-11-19 App 分类表（手机上的那一页）：和 App分层.md 是同一张表（config.appTiers），规矩也一样——
   * 改严立即生效；改松先挂起，24 小时后生效（期间可以撤回）。系统底座不能分层。
   *   list   {apps:[{pkg,label}]}  → 每个 App 现在在哪一层、是不是他手动定的、有没有挂起的放松
   *   set    {pkg, tier}           → 改严立即生效 / 改松挂起
   *   cancel {pkg}                 → 撤回挂起的放松
   */
  function tiersRoute(body) {
    const b = body && typeof body === 'object' ? body : {}
    const action = String(b.action || 'list')
    const now = Date.now()
    if (action === 'list' && !vaultKb()) applyDueTierPending(now)
    const tf = tierFnNow()
    const pending = loadTierPending()
    const overrides = config.appTiers || {}
    if (action === 'list') {
      const apps = Array.isArray(b.apps) ? b.apps : []
      const seen = new Set()
      const rows = []
      for (const a of apps) {
        const pkg = String(a?.pkg || a?.package || '').trim()
        if (!pkg || seen.has(pkg)) continue
        seen.add(pkg)
        const label = String(a?.label || '')
        const p = pending[pkg]
        rows.push({
          pkg, label, tier: tf(pkg, label), override: overrides[pkg] || null,
          pendingTier: p?.tier || null, effectiveAt: p ? Number(p.since) + TIER_LOOSEN_MS : 0,
        })
      }
      // 手动定过但这次没报上来的（卸载了或没装），也列出来，方便撤销。
      for (const [pkg, tier] of Object.entries(overrides)) {
        if (seen.has(pkg)) continue
        rows.push({ pkg, label: '', tier: tf(pkg), override: tier, pendingTier: pending[pkg]?.tier || null, effectiveAt: pending[pkg] ? Number(pending[pkg].since) + TIER_LOOSEN_MS : 0 })
      }
      return { v: 1, ok: true, tiers: ['work', 'dual', 'fun', 'game'], labels: TIER_LABEL, rows, loosenDelayMs: TIER_LOOSEN_MS, defaultAllow: defaultAllowApps() }
    }
    const pkg = String(b.pkg || '').trim()
    if (!pkg) return { v: 1, ok: false, error: 'no-pkg', text: '没说是哪个 App。' }
    const before = tf(pkg)
    if (before === 'base') return { v: 1, ok: false, error: 'base', text: '系统页面永远不锁，不用分层。' }
    if (action === 'cancel') {
      if (pending[pkg]) {
        delete pending[pkg]
        saveTierPending(pending)
        writeTierFile()
        record({ state: 'app-tier-cancel', at: now, pkg })
      }
      return { v: 1, ok: true, text: '撤回了：' + pkg + ' 留在「' + TIER_LABEL[before] + '」。' }
    }
    if (action !== 'set') return { v: 1, ok: false, error: 'bad-action' }
    const tier = String(b.tier || '')
    if (!['work', 'dual', 'fun', 'game'].includes(tier)) return { v: 1, ok: false, error: 'bad-tier', text: '只能是 工作 / 双用途 / 娱乐 / 游戏。' }
    if (before === tier) {
      if (pending[pkg]) { delete pending[pkg]; saveTierPending(pending) }
      return { v: 1, ok: true, applied: true, tier, text: '本来就在「' + TIER_LABEL[tier] + '」。' }
    }
    if ((before === 'other' || before === 'dual') && tier === 'work') {
      if (isPureGame(pkg, String(b.label || ''))) {
        return { v: 1, ok: false, error: 'pure-game', text: '硬规矩拦回：纯游戏不可设为工作白名单。' }
      }
      if (pending[pkg]) { delete pending[pkg]; saveTierPending(pending) }
      commit({
        appTiers: { ...overrides, [pkg]: tier },
        ...syncAppListMembership([pkg], 'allow', now),
      })
      writeTierFile()
      record({ state: 'app-tier-set', at: now, pkg, from: before, to: tier, loosen: true, immediate: true, by: 'app' })
      return {
        v: 1,
        ok: true,
        applied: true,
        tier,
        defaultAllow: defaultAllowApps(),
        text: TIER_LABEL[before] + ' → ' + TIER_LABEL[tier] + '：已加入工作白名单并同步当前局，立即生效。',
      }
    }
    const loosen = (TIER_RANK[tier] ?? 1) < (TIER_RANK[before] ?? 1)
    if (!loosen) {
      if (pending[pkg]) { delete pending[pkg]; saveTierPending(pending) }
      commit({ appTiers: { ...overrides, [pkg]: tier } })
      writeTierFile()
      record({ state: 'app-tier-set', at: now, pkg, from: before, to: tier, loosen: false, by: 'app' })
      return { v: 1, ok: true, applied: true, tier, text: TIER_LABEL[before] + ' → ' + TIER_LABEL[tier] + '：改严，立即生效。' }
    }
    if (!pending[pkg] || pending[pkg].tier !== tier) pending[pkg] = { tier, since: now }
    saveTierPending(pending)
    // 分层文件也写成他要的那一层：文件同步看到同一个放松，就按挂起时间算，不会把挂起冲掉。
    const kb = vaultKb()
    if (kb) {
      try {
        writeVaultText(join(kb, WALL_DIR, TIER_FILE), renderTierFile({ ...overrides, [pkg]: tier }, tierLabels()))
        const st = statOf(join(kb, WALL_DIR, TIER_FILE))
        tierSeen = { key: st ? st.mtimeMs + ':' + st.size : '', at: now }
      } catch {}
    }
    record({ state: 'app-tier-pending', at: now, pkg, from: before, to: tier })
    const at = Number(pending[pkg].since) + TIER_LOOSEN_MS
    return { v: 1, ok: true, applied: false, tier: before, pendingTier: tier, effectiveAt: at, text: TIER_LABEL[before] + ' → ' + TIER_LABEL[tier] + '：放松要等 24 小时才生效（期间可以撤回）。' }
  }

  /** 没有笔记库时，挂起的放松到点也要生效（有笔记库时由 syncTierFile 按文件判）。 */
  function applyDueTierPending(now) {
    const pending = loadTierPending()
    const due = Object.entries(pending).filter(([, p]) => now - Number(p?.since) >= TIER_LOOSEN_MS)
    if (!due.length) return
    const next = { ...(config.appTiers || {}) }
    for (const [pkg, p] of due) {
      next[pkg] = p.tier
      delete pending[pkg]
    }
    saveTierPending(pending)
    commit({ appTiers: next })
    record({ state: 'app-tiers', at: now, tighten: [], loosen: due.map(([pkg]) => pkg) })
  }

  /** 读 大肥鱼/墙/App分层.md：改严立即生效，改松 24 小时后生效。 */
  function syncTierFile(now) {
    const kb = vaultKb()
    if (!kb) {
      applyDueTierPending(now)
      return
    }
    const file = join(kb, WALL_DIR, TIER_FILE)
    const st = statOf(file)
    const key = st ? st.mtimeMs + ':' + st.size : 'none'
    if (key === tierSeen.key && now - tierSeen.at < 60_000) return
    tierSeen = { key, at: now }
    const text = readVaultText(file)
    if (text === null) {
      writeTierFile()
      return
    }
    const cur = config.appTiers || {}
    const tf = tierFnNow()
    const { tighten, loosen } = classifyTierChanges(cur, parseTierFile(text), (p) => tf(p))
    const pending = loadTierPending()
    const next = { ...cur, ...tighten }
    let changed = Object.keys(tighten).length > 0
    for (const [p, t] of Object.entries(loosen)) {
      const prev = pending[p]
      if (!prev || prev.tier !== t) pending[p] = { tier: t, since: now }
      else if (now - Number(prev.since) >= TIER_LOOSEN_MS) {
        next[p] = t
        delete pending[p]
        changed = true
      }
    }
    for (const p of Object.keys(pending)) if (!(p in loosen)) delete pending[p]
    saveTierPending(pending)
    if (changed) {
      commit({ appTiers: next })
      record({ state: 'app-tiers', at: now, tighten: Object.keys(tighten), loosen: Object.keys(next).filter((p) => !(p in cur) || cur[p] !== next[p]).filter((p) => !(p in tighten)) })
    }
  }
  /** 今天通行证覆盖了几分钟（双用途在这段时间不算娱乐）。 */
  function passMinutesToday(now, tz) {
    const day = dayString(now, tz)
    return readLedger(home(), 600)
      .filter((e) => e?.state === 'grant-task-lease' && dayString(Number(e.at) || 0, tz) === day)
      .reduce((sum, e) => sum + (Number(e.minutes) || 0), 0)
  }
  function quotaNow(now, tz, phone) {
    return timeLedgerNow(now, tz, phone)
  }
  let lastQuota = null
  /** 每晚的 App 账：今天每个 App 用了多久、在哪一层、通行证几张、偏航几次。 */
  function appLedgerLines(now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const day = dayString(now, tz)
    const tf = tierFnNow()
    const passes = {}
    const drifts = {}
    for (const e of readLedger(home(), 800)) {
      if (!e || dayString(Number(e.at) || 0, tz) !== day) continue
      if (e.state === 'grant-task-lease' && e.pkg) passes[e.pkg] = (passes[e.pkg] || 0) + 1
      if (e.state === 'lease-drift' && e.app) drifts[e.app] = (drifts[e.app] || 0) + 1
    }
    return (Array.isArray(lastPhone?.today) ? lastPhone.today : [])
      .filter((r) => r?.app && Number(r.minutes) > 0)
      .sort((a, b) => Number(b.minutes) - Number(a.minutes))
      .slice(0, 12)
      .map((r) => {
        const t = tf(r.app, r.label)
        const extra = (passes[r.app] ? '，通行证 ' + passes[r.app] + ' 张' : '') + (drifts[r.app] ? '，偏航 ' + drifts[r.app] + ' 次' : '')
        return (r.label || r.app) + '（' + (TIER_LABEL[t] || t) + '）' + Math.round(Number(r.minutes)) + ' 分钟' + extra
      })
  }
  function lockDecisionNow(now, tz, phone, lockArbiter, extState) {
    if (Array.isArray(phone?.installedApps) && phone.installedApps.length) lastInstalled = phone.installedApps
    const quota = quotaNow(now, tz, phone)
    lastQuota = quota
    const contract = v4Hub?.contractSessionView?.(now) || null
    const dayBlockOverdue = v4Hub?.dayBlockOverdueSessionView?.(now) || null
    const dayBlockActive = v4Hub?.dayBlockActiveSessionView?.(now) || null
    const busy = effectiveGear(config, now) === 'busy'
    const run = runningBoutNow()
    const boutApps = [...(Array.isArray(live?.apps?.need) ? live.apps.need : []), ...(Array.isArray(live?.apps?.allow) ? live.apps.allow : [])]
    const agentLease = activeAppLease(extState, now)
    const workActive = busy || Boolean(run) || Boolean(aiTask(config, now)) || Boolean(contract) || Boolean(dayBlockOverdue) || Boolean(dayBlockActive)
    const why = dayBlockOverdue
      ? dayBlockOverdue.why
      : (dayBlockActive
          ? dayBlockActive.why
          : (run ? '局进行中：' + (run.kind || '这一局') : contract ? contract.why : busy ? '忙时：' + (config.gearWhat || '正事') : '专时进行中'))
    const taskLease = config.taskLease && Number(config.taskLease.until) > now ? config.taskLease : null
    const busDeny = []
    for (const r of lockBus.list(now)) {
      if (r.kind !== 'deny') continue
      for (const app of r.apps || []) {
        if (!isNeverLockPkg(app)) busDeny.push({ app, until: Number(r.until) || 0, why: r.why || '临时锁' })
      }
    }
    const extraDeny = (Array.isArray(config.blockExtra) ? config.blockExtra : [])
      .filter((b) => b && Number(b.until) > now && !isNeverLockPkg(b.app))
      .map((b) => ({ app: b.app, until: Number(b.until), why: b.why || '临时锁' }))
    const dec = buildLockDecision({
      arbiter: lockArbiter,
      work: {
        active: workActive,
        why,
        allow: [
          ...(Array.isArray(dayBlockOverdue?.allow) ? dayBlockOverdue.allow : []),
          ...(Array.isArray(dayBlockActive?.allow) ? dayBlockActive.allow : []),
          ...(Array.isArray(contract?.allow) ? contract.allow : []),
          ...(Array.isArray(config.defaultAllow) ? config.defaultAllow : []),
          ...boutApps,
          ...(Array.isArray(agentLease?.packages) ? agentLease.packages : []),
        ],
      },
      installed: lastInstalled,
      tierOf: tierFnNow(),
      quota,
      lease: taskLease,
      radarDeny: [...extraDeny, ...busDeny],
      today: Array.isArray(phone?.today) && phone.today.length ? phone.today : (Array.isArray(lastPhone?.today) ? lastPhone.today : []),
      neutralWorkQuotaMin: DEFAULT_NEUTRAL_WORK_QUOTA_MIN,
      now,
    })
    const reverseSess = reverseCapsuleSessionView(now)
    if (reverseSess && (!lockArbiter || !['P0', 'P1', 'P2', 'P3'].includes(lockArbiter.priority))) {
      return {
        ...dec,
        mode: 'allowlist',
        source: 'REVERSE_CAPSULE',
        allow: [...new Set(reverseSess.allow)],
        needPass: [],
        why: reverseSess.why,
        unlockHow: '完成本轮胶囊或手动收局解锁',
      }
    }
    return dec
  }

  function loadSuperSparkState() {
    const raw = readState(stateFile('super-spark.json'), null)
    return {
      v: 1,
      recentIds: Array.isArray(raw?.recentIds) ? raw.recentIds.map((x) => String(x || '')).filter(Boolean).slice(-SUPER_SPARK_DEFAULTS.recentMax) : [],
      lastSparkAt: Math.max(0, Number(raw?.lastSparkAt) || 0),
      mutedUntil: Math.max(0, Number(raw?.mutedUntil) || 0),
      lastSpark: raw?.lastSpark && typeof raw.lastSpark === 'object' ? raw.lastSpark : null,
    }
  }

  function saveSuperSparkState(next) {
    writeJson(stateFile('super-spark.json'), { ...next, v: 1 })
  }

  function loadAllBankQuestions() {
    const out = []
    const catalog = readState(stateFile('bank-catalog.json'), null)
    if (catalog && Array.isArray(catalog.banks)) {
      for (const b of catalog.banks) {
        if (!b || b.enabled === false || !Array.isArray(b.questions)) continue
        for (const q of b.questions) out.push(q)
      }
    }
    const legacy = readState(stateFile('bank.json'), null)
    if (legacy && Array.isArray(legacy.questions)) {
      for (const q of legacy.questions) out.push(q)
    }
    return out
  }

  function buildSuperSparkNow(now = Date.now(), opts = {}) {
    const ss = loadSuperSparkState()
    const phone = opts.phone || lastPhone || {}
    const bankState = cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null))
    const questionBank = loadAllBankQuestions()
    const reviewList = dueCards(loadReviewCards(), now, { limit: 10 })
    const horizons = readState(stateFile('horizons.json'), null)
    const vault = vaultContext(now)
    const fgPkg = String(phone?.app || '')
    const fgTier = fgPkg ? tierFnNow()(fgPkg, String(phone?.label || '')) : 'other'
    const fgMin = Math.max(0, Number(phone?.minutes || 0) || 0)
    const wandering = opts.wandering !== undefined
      ? Boolean(opts.wandering)
      : Boolean(phone?.fun || (['fun', 'game', 'other'].includes(fgTier) && fgMin >= 3))

    const spark = generateSuperSpark({
      now,
      bankState,
      questionBank,
      reviewCards: reviewList,
      horizons,
      vault,
      recentIds: ss.recentIds,
      modeHint: String(opts.modeHint || ''),
      wandering,
      noPen: Boolean(opts.noPen),
      commute: Boolean(opts.commute),
      handsBusy: Boolean(opts.handsBusy),
      idleMinutes: Number(opts.idleMinutes ?? fgMin) || 0,
      mutedUntil: ss.mutedUntil,
      force: Boolean(opts.force),
    })

    if (spark.triggered && opts.commit !== false) {
      saveSuperSparkState({
        ...ss,
        recentIds: spark.nextRecentIds || ss.recentIds,
        lastSparkAt: now,
        lastSpark: spark,
      })
      record({
        state: 'super-spark',
        at: now,
        mode: spark.mode,
        anchorSource: spark.anchorSource,
        anchorId: spark.anchorId,
      })
    }
    return spark
  }

  function superSparkRoute(body = {}, method = 'POST') {
    const now = Date.now()
    const b = body && typeof body === 'object' ? body : {}
    const action = String(b.action || (method === 'GET' ? (b.trigger === '1' || b.trigger === 'true' ? 'trigger' : 'status') : 'trigger'))
    if (action === 'mute_15m' || action === 'mute') {
      const mins = Math.max(1, Math.min(120, Math.round(Number(b.minutes) || 15)))
      const cur = loadSuperSparkState()
      const mutedUntil = now + mins * 60_000
      saveSuperSparkState({ ...cur, mutedUntil })
      record({ state: 'super-spark-muted', at: now, until: mutedUntil, minutes: mins })
      return {
        v: 1,
        ok: true,
        muted: true,
        mutedUntil,
        toast: `已静音超级大肥鱼主动抽考 ${mins} 分钟`,
      }
    }
    if (action === 'status') {
      const st = loadSuperSparkState()
      return {
        v: 1,
        ok: true,
        muted: st.mutedUntil > now,
        mutedUntil: st.mutedUntil,
        lastSparkAt: st.lastSparkAt,
        recentIds: st.recentIds,
        spark: st.lastSpark,
        superSpark: st.lastSpark,
      }
    }
    const spark = buildSuperSparkNow(now, {
      force: b.force !== false,
      modeHint: String(b.modeHint || b.mode || ''),
      noPen: Boolean(b.noPen),
      wandering: b.wandering !== undefined ? Boolean(b.wandering) : undefined,
      commute: Boolean(b.commute),
      handsBusy: Boolean(b.handsBusy),
      idleMinutes: Number(b.idleMinutes || 0) || 0,
      phone: lastPhone,
    })
    const stAfter = loadSuperSparkState()
    return {
      v: 1,
      ok: Boolean(spark?.triggered),
      muted: stAfter.mutedUntil > now,
      mutedUntil: stAfter.mutedUntil,
      spark,
      superSpark: spark,
    }
  }

  function loadLiveCoreState() {
    const raw = readState(stateFile('live-core.json'), null)
    return raw && typeof raw === 'object' ? raw : {}
  }

  function saveLiveCoreState(next) {
    if (!next || typeof next !== 'object') return
    writeJson(stateFile('live-core.json'), { ...next, v: 1 })
  }

  function markLiveCoreReceipt({ now = Date.now(), summary = '', next = '' } = {}) {
    const cur = loadLiveCoreState()
    const summaryText = String(summary || '').trim().slice(0, 180)
    const nextText = String(next || '').trim().slice(0, 100)
    const updated = {
      ...cur,
      receiptSinceLastLive: true,
      ...(summaryText || nextText
        ? {
            lastRecap: {
              at: now,
              summary: summaryText,
              next: nextText,
              mode: 'mcp_receipt',
            },
          }
        : {}),
    }
    saveLiveCoreState(updated)
    return updated
  }

  function liveCoreBridgeSnapshot(now = Date.now(), tz = zoneOr(config.timeZone)) {
    const summary = liveCoreSummary(now)
    const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, { limit: 8 })
    return {
      phase: summary.phase,
      reason: summary.reason,
      enabled: summary.enabled,
      muted: summary.muted,
      lastIgniteAt: summary.lastIgniteAt,
      dice: summary.dice ? { face: summary.dice.face, name: summary.dice.name } : null,
      lastRecap: summary.lastRecap || null,
      todayMetrics: atlas.todayMetrics,
      recentHistory: history.slice(0, 8).map((h) => ({
        hm: h.hm,
        kind: h.kind,
        badge: h.badge,
        title: h.title,
        detail: h.detail,
      })),
    }
  }

  function loadV6(now = Date.now()) {
    return loadV6State(readState, stateFile('v6-state.json'), now)
  }

  function saveV6(next) {
    if (!next || typeof next !== 'object') return
    writeJson(stateFile('v6-state.json'), { ...next, v: 1 })
  }

  function armContractFromV6(contract, now = Date.now()) {
    try {
      const tz = zoneOr(config.timeZone)
      const modNow = zoneMinuteOfDay(now, tz)
      const mins = Math.max(5, Math.min(180, Number(contract?.durationMin) || 25))
      const block = {
        id: `v6-contract-${now.toString(36)}`,
        slot: 'v6_contract',
        startMin: modNow,
        endMin: modNow + mins,
        title: String(contract?.target || '今日契约切片').slice(0, 60),
        deliverable: String(contract?.verifyRule || '交出实质结果').slice(0, 120),
        lockMode: 'verify',
        allowedApps: Array.isArray(contract?.allowApps) && contract.allowApps.length ? contract.allowApps : ['md.obsidian', 'com.google.android.apps.bard'],
      }
      return v4Hub?.planContractRoute?.({
        action: 'confirm_plan_modal',
        timeZone: tz,
        blocks: [block],
      }) || { ok: true }
    } catch {
      return { ok: false }
    }
  }

  function v6Route(body = {}, method = 'POST') {
    const now = Date.now()
    const b = body && typeof body === 'object' ? body : {}
    const tz = zoneOr(b.timeZone || config.timeZone)
    const day = dayString(now, tz)
    const mod = zoneMinuteOfDay(now, tz)
    const st = loadV6(now)
    const action = String(b.action || (method === 'GET' ? 'status' : 'status'))
    const horizons = readState(stateFile('horizons.json'), null)

    if (action === 'status') {
      const telemetry = v6ComputePoolTelemetry(st.pool, now)
      const cadTick = v6TickCadence(st.cadence, now, day)
      if (cadTick.cadence.phase !== st.cadence.phase || cadTick.cadence.day !== st.cadence.day) {
        st.cadence = cadTick.cadence
        saveV6(st)
      }
      return {
        v: 1,
        ok: true,
        framework: st.framework,
        windowMode: v6EvaluateWindowMode(st.framework, mod),
        telemetry,
        appeals: st.appeals?.day === day ? st.appeals : { day, total: 0, physio: 0 },
        nineGrid: v6BuildNineGrid({ pool: st.pool, stelesCount: st.steles.length, now }),
        stelesCount: st.steles.length,
        lastContract: st.lastContract,
        liveVoiceActive: Boolean(lastPhone?.geminiVoiceActive),
        liveSignals: lastPhone?.liveSignals || null,
        cadence: cadTick.cadence,
        cadenceRemainingSec: cadTick.remainingSec,
        cadenceOverdueSec: cadTick.overdueSec,
      }
    }

    if (action === 'cadence_start') {
      st.cadence = v6StartCadence(st.cadence, st.framework, {
        target: b.target,
        goalId: b.goalId,
        goalScope: b.goalScope,
        intervalSec: b.intervalSec,
        allowApps: b.allowApps,
        now,
        day,
      })
      saveV6(st)
      presetWatchUntil = now + 2 * 60_000
      record({
        state: 'v6-cadence-start',
        at: now,
        round: st.cadence.round,
        target: st.cadence.target,
        intervalSec: st.cadence.intervalSec,
      })
      return {
        v: 1,
        ok: true,
        cadence: st.cadence,
        cadenceRemainingSec: st.cadence.intervalSec,
        cadenceOverdueSec: 0,
      }
    }

    if (action === 'cadence_verify') {
      const res = v6SubmitCadenceDelivery(st.cadence, st.steles, st.framework, {
        submission: String(b.submission || ''),
        quizPassed: b.quizPassed === true,
        nextTarget: b.nextTarget ? String(b.nextTarget) : '',
        continueNext: b.continueNext !== false,
        now,
        day,
      })
      if (res.ok) {
        st.cadence = res.cadence
        st.steles = res.steles
        saveV6(st)
        presetWatchUntil = now + 2 * 60_000
        record({
          state: 'v6-cadence-verify',
          at: now,
          streak: res.cadence.streak,
          verifiedToday: res.cadence.verifiedToday,
          autoSteleId: res.autoStele?.id || null,
        })
      }
      return {
        v: 1,
        ...res,
        stelesCount: st.steles.length,
        cadenceRemainingSec: res.cadence?.active ? res.cadence.intervalSec : 0,
        cadenceOverdueSec: 0,
      }
    }

    if (action === 'cadence_stop') {
      st.cadence = v6StopCadence(st.cadence)
      saveV6(st)
      presetWatchUntil = now + 2 * 60_000
      record({ state: 'v6-cadence-stop', at: now, verifiedToday: st.cadence.verifiedToday })
      return { v: 1, ok: true, cadence: st.cadence }
    }

    if (action === 'framework_save' || action === 'framework_import') {
      const rawCfg = action === 'framework_import' && typeof b.json === 'string'
        ? JSON.parse(b.json)
        : (b.framework && typeof b.framework === 'object' ? b.framework : b)
      st.framework = v6NormalizeFramework(rawCfg)
      saveV6(st)
      record({ state: 'v6-framework-save', at: now })
      return { v: 1, ok: true, framework: st.framework, windowMode: v6EvaluateWindowMode(st.framework, mod) }
    }

    if (action === 'pool_ingest') {
      const text = String(b.text || '').trim()
      if (!text) return { v: 1, ok: false, error: '剪贴板或输入为空' }
      const res = v6IngestClipboardOrText(st.pool, text, { source: String(b.source || 'gemini'), now })
      if (res.kind === 'contract' && res.contract) {
        st.lastContract = { ...res.contract, armedAt: now }
        saveV6(st)
        const armRes = armContractFromV6(res.contract, now)
        record({ state: 'v6-contract-armed', at: now, target: res.contract.target })
        return {
          v: 1,
          ok: true,
          kind: 'contract',
          contract: res.contract,
          armed: Boolean(armRes?.ok),
          telemetry: v6ComputePoolTelemetry(st.pool, now),
        }
      }
      st.pool = res.pool
      saveV6(st)
      record({ state: 'v6-pool-ingest', at: now, added: res.added.length, source: String(b.source || 'gemini') })
      return {
        v: 1,
        ok: res.added.length > 0,
        kind: 'knowledge',
        addedCount: res.added.length,
        skippedCount: res.skipped,
        telemetry: v6ComputePoolTelemetry(st.pool, now),
      }
    }

    if (action === 'pool_sample') {
      const count = Math.max(1, Math.min(8, Number(b.count) || 1))
      const items = v6SampleKnowledgeItems(st.pool, st.framework, { count, now, markShown: b.markShown === true })
      if (b.markShown === true) saveV6(st)
      return {
        v: 1,
        ok: items.length > 0,
        items,
        telemetry: v6ComputePoolTelemetry(st.pool, now),
        framework: st.framework,
      }
    }

    if (action === 'pool_answer') {
      const ans = v6RecordMicroQuizAnswer(st.pool, st.framework, {
        itemId: String(b.itemId || ''),
        userTrue: b.userTrue === true || b.userTrue === 'true',
        elapsedMs: Number(b.elapsedMs) || 1200,
        now,
      })
      saveV6(st)
      record({ state: 'v6-wake-quiz-answer', at: now, itemId: ans.itemId, correct: ans.correct, antiMashPenalty: ans.antiMashPenalty })
      return {
        v: 1,
        ...ans,
        telemetry: v6ComputePoolTelemetry(st.pool, now),
      }
    }

    if (action === 'pool_sweep') {
      const swept = v6SweepForgetting(st.pool, st.framework, now)
      st.pool = swept.pool
      saveV6(st)
      return {
        v: 1,
        ok: true,
        archivedCount: swept.archivedCount,
        decayedCount: swept.decayedCount,
        telemetry: v6ComputePoolTelemetry(st.pool, now),
      }
    }

    if (action === 'interrogate_prompt') {
      const promptText = v6BuildInterrogationPrompt({
        horizons,
        pool: st.pool,
        now,
        energyHint: String(b.energyHint || '待自报'),
      })
      return { v: 1, ok: true, prompt: promptText }
    }

    if (action === 'interrogate_commit') {
      const contract = {
        target: String(b.target || '').trim().slice(0, 140),
        durationMin: Math.max(5, Math.min(180, Number(b.durationMin) || 25)),
        verifyRule: String(b.verifyRule || '交出实质结果').trim().slice(0, 120),
        allowApps: Array.isArray(b.allowApps) ? b.allowApps : [],
      }
      if (!contract.target) return { v: 1, ok: false, error: '请填写具体靶子动作' }
      st.lastContract = { ...contract, armedAt: now }
      saveV6(st)
      const armRes = armContractFromV6(contract, now)
      return { v: 1, ok: true, contract, armed: Boolean(armRes?.ok) }
    }

    if (action === 'nine_grid') {
      return {
        v: 1,
        ok: true,
        nineGrid: v6BuildNineGrid({ pool: st.pool, stelesCount: st.steles.length, now }),
      }
    }

    if (action === 'appeal') {
      const res = v6ApplyAppeal(st.appeals, st.framework, {
        kind: String(b.kind || ''),
        now,
        day,
      })
      if (res.ok) {
        st.appeals = res.usage
        saveV6(st)
        if (res.grantMinutes > 0) {
          const curExt = loadExternal()
          saveExternal({
            ...curExt,
            emergencyPass: {
              active: true,
              grantedAt: now,
              until: now + res.grantMinutes * 60_000,
              reason: `V6 申诉(${res.kind})`,
              day,
              countToday: Number(curExt?.emergencyPass?.countToday) || 0,
            },
          })
        }
        record({ state: 'v6-appeal', at: now, kind: res.kind, grantMinutes: res.grantMinutes })
      }
      return { v: 1, ...res }
    }

    if (action === 'wanshiming_list') {
      const cad = st.cadence || {}
      return {
        v: 1,
        ok: true,
        steles: st.steles,
        horizons: horizons || { long: [], medium: [] },
        cadence: cad,
        cadenceStreak: cad.day === day ? (Number(cad.streak) || 0) : 0,
        verifiedToday: cad.day === day ? (Number(cad.verifiedToday) || 0) : 0,
      }
    }

    if (action === 'wanshiming_save') {
      const res = v6UpsertStele(st.steles, b, now)
      if (res.ok) {
        st.steles = res.steles
        saveV6(st)
        record({ state: 'wanshiming-save', at: now, steleId: res.item.id })
      }
      return { v: 1, ...res }
    }

    if (action === 'wanshiming_delete') {
      const res = v6DeleteStele(st.steles, String(b.id || ''))
      if (res.ok) {
        st.steles = res.steles
        saveV6(st)
      }
      return { v: 1, ...res }
    }

    return { v: 1, ok: false, error: `未知的 V6 操作: ${action}` }
  }

  function isRunningQuizNow(now, phone, ledgerEntries) {
    if (phone?.runningQuiz === true || phone?.quizActive === true) return true
    const screen = String(phone?.screenText || '')
    if (screen.includes('全屏刷题') || screen.includes('错题专练')) return true
    const list = Array.isArray(ledgerEntries) ? ledgerEntries : readLedger(home(), 80)
    return list.some((e) => (e?.state === 'bank-quiz-answer' || e?.state === 'quiz-solved') && now - Number(e?.at || 0) <= 2 * 60_000)
  }

  let sisterFeedCache = { home: '', at: 0, value: null }

  function collectSisterFeed(now = Date.now(), opts = {}) {
    const hasOverrides = Boolean(
      opts.ledgerEntries || opts.bankState || opts.questionBank || opts.reviewCards ||
      opts.experiences || opts.vault || opts.commitments || opts.phone,
    )
    const curHome = home()
    if (!hasOverrides && sisterFeedCache.value && sisterFeedCache.home === curHome && now - sisterFeedCache.at < 15_000) {
      return sisterFeedCache.value
    }
    const ledgerEntries = Array.isArray(opts.ledgerEntries) ? opts.ledgerEntries : readLedger(home(), 120)
    const bankState = opts.bankState || cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null))
    const questionBank = Array.isArray(opts.questionBank) ? opts.questionBank : loadAllBankQuestions()
    const reviewCards = Array.isArray(opts.reviewCards) ? opts.reviewCards : dueCards(loadReviewCards(), now, { limit: 10 })
    const qById = new Map()
    for (const q of questionBank) {
      if (q?.id !== undefined) qById.set(String(q.id), q)
    }
    const recentQuizzes = []
    for (let i = ledgerEntries.length - 1; i >= 0 && recentQuizzes.length < 4; i -= 1) {
      const e = ledgerEntries[i]
      if (!e || (e.state !== 'bank-quiz-answer' && e.state !== 'quiz-solved')) continue
      const matched = e.questionId !== undefined ? qById.get(String(e.questionId)) : null
      recentQuizzes.push({
        topic: e.topic || e.category || matched?.topic || matched?.category || '刚刷的题',
        stem: e.stem || e.question || e.title || matched?.stem || matched?.q || '',
        wrong: e.ok === false || e.correct === false,
      })
    }

    let experiences = Array.isArray(opts.experiences) ? [...opts.experiences] : []
    if (!experiences.length) {
      let db = null
      try {
        db = openMemoryDb(home(), { readonly: true })
        const candidates = loadCandidates(db, { sessionId: config.sessionId || '', now })
        const facts = backgroundFacts(candidates, 4)
        const eps = (candidates.episodes || []).slice(0, 3).map((x) => x.text)
        experiences = [...facts, ...eps].filter(Boolean)
      } catch {
      } finally {
        try {
          db?.close?.()
        } catch {}
      }
    }

    const dir = notesDir()
    const vault = opts.vault || vaultContext(now)
    let settingText = ''
    try {
      settingText = loadGeminiSetting(dir ? kbDir(dir, home()) : '')
    } catch {}
    const commitments = Array.isArray(opts.commitments)
      ? opts.commitments
      : readCommitments(home(), now, 3).map((c) => c.statement)
    const phone = opts.phone || lastPhone || {}
    const sisterFeed = buildSisterFeed({
      bankState,
      questionBank,
      reviewCards,
      recentQuizzes,
      experiences,
      vaultSnippets: Array.isArray(vault?.recent) ? vault.recent : [],
      commitments,
      phone,
    })
    const bundle = {
      ledgerEntries,
      bankState,
      questionBank,
      reviewCards,
      recentQuizzes,
      experiences,
      vault,
      settingText,
      commitments,
      phone,
      sisterFeed,
    }
    if (!hasOverrides) {
      sisterFeedCache = { home: curHome, at: now, value: bundle }
    }
    return bundle
  }

  function evaluateLiveCoreNow(now = Date.now(), opts = {}) {
    const st = loadLiveCoreState()
    const tz = opts.tz || zoneOr(config.timeZone)
    const feedBundle = collectSisterFeed(now, {
      ledgerEntries: Array.isArray(opts.ledger) ? opts.ledger : undefined,
      phone: opts.phone || lastPhone || {},
    })
    const {
      ledgerEntries,
      bankState,
      questionBank,
      reviewCards,
      recentQuizzes,
      experiences,
      vault,
      settingText,
      commitments,
      phone,
    } = feedBundle
    const gemSt = loadGemini()
    const recaps = Array.isArray(gemSt?.recaps) ? gemSt.recaps : []
    const lastGemRecap = recaps.length ? recaps[recaps.length - 1] : null
    const lastRecap = (st?.lastRecap && (!lastGemRecap || (Number(st.lastRecap.at) || 0) >= (Number(lastGemRecap.at) || 0)))
      ? st.lastRecap
      : lastGemRecap
    const fgPkg = String(phone?.app || '')
    const fgTier = fgPkg ? tierFnNow()(fgPkg, String(phone?.label || '')) : 'other'
    const gemGate = loadGeminiGate(now, tz)
    const externalMutedUntil = Math.max(Number(config.muteUntil) || 0, Number(gemGate?.snoozeUntil) || 0)
    const quizSince = Math.max(Number(st.lastIgniteAt) || 0, Number(st.lastQuizReliefAt) || 0, now - 30 * 60_000)
    const recentQuizAnswers = ledgerEntries.filter(
      (e) => (e?.state === 'bank-quiz-answer' || e?.state === 'quiz-solved') && Number(e?.at) > quizSince,
    ).length

    let hasTodayPlan = true
    try {
      const todaySeals = loadSeals(now, tz)
      hasTodayPlan = Array.isArray(todaySeals?.seals) && todaySeals.seals.length > 0
    } catch {}
    const hasRunningBout = Boolean(runningBoutNow())

    const res = evaluateLiveCore({
      now,
      state: st,
      config: { ...config, emergencyActive: Boolean(opts.emergencyActive), muteUntil: externalMutedUntil },
      phone,
      arbiter: opts.arbiter || null,
      planModal: opts.planModal || null,
      mealOrQuiet: Boolean(opts.mealOrQuiet),
      inGemini: opts.inGemini !== undefined ? Boolean(opts.inGemini) : inGemini(phone, geminiPackages()),
      runningQuiz: opts.runningQuiz !== undefined ? Boolean(opts.runningQuiz) : isRunningQuizNow(now, phone, ledgerEntries),
      recentQuizAnswers,
      fgTier,
      bankState,
      questionBank,
      reviewCards,
      recentQuizzes,
      experiences,
      horizons: readState(stateFile('horizons.json'), null),
      vault,
      settingText,
      commitments,
      recap: lastRecap,
      bridge: bridgeHealthy(now),
      modeHint: String(opts.modeHint || ''),
      diceHint: Number(opts.diceHint) || 0,
      targetMode: String(opts.targetMode || ''),
      customFeed: String(opts.customFeed || ''),
      scalpelHint: String(opts.scalpelHint || ''),
      hasTodayPlan,
      runningBout: hasRunningBout,
      lastLiveDurationSec: Number(phone?.lastLiveDurationSec || 0),
      force: Boolean(opts.force),
    })

    if (opts.commit !== false) {
      let persistedState = res.nextState
      if (res.triggered) {
        record({
          state: 'live-core-ignite',
          at: now,
          action: res.action,
          reason: res.reason,
          diceFace: res.dice?.face || 0,
          anchorId: res.anchor?.id || '',
          department: res.department?.id || '',
          hasCustomFeed: Boolean(String(opts.customFeed || '').trim()),
        })
      } else {
        const informativeYields = new Set([
          'active_study_or_quiz',
          'quiz_relief_valve',
          'plan_modal_priority',
          'sleeping',
          'break',
          'emergency',
          'live_running',
          'text_mode_grace',
        ])
        const sameRecentYield = res.reason === st.lastYieldLoggedReason && now - (Number(st.lastYieldLoggedAt) || 0) < LIVE_CORE_DEFAULTS.yieldLogDedupMs
        if (informativeYields.has(res.reason) && !sameRecentYield) {
          persistedState = {
            ...res.nextState,
            lastYieldLoggedAt: now,
            lastYieldLoggedReason: res.reason,
          }
          record({
            state: 'live-core-yield',
            at: now,
            reason: res.reason,
            phase: res.phase,
          })
        }
      }
      saveLiveCoreState(persistedState)
    }
    return res
  }

  function liveCoreSummary(now = Date.now(), evalRes = null) {
    const st = evalRes?.nextState || loadLiveCoreState()
    const mutedUntil = Math.max(0, Number(st.mutedUntil) || 0)
    const textGraceUntil = Math.max(0, Number(st.textGraceUntil) || 0)
    let feedPreview = evalRes?.feedPreview || st.lastFeedPreview || null
    if (!feedPreview) {
      try {
        feedPreview = collectSisterFeed(now).sisterFeed
      } catch {}
    }
    return {
      v: 1,
      enabled: st.enabled !== false,
      autoReengage: st.autoReengage !== false,
      wantScreenShare: st.wantScreenShare !== false,
      alwaysOnLive: Boolean(st.alwaysOnLive),
      keeperStrict: st.keeperStrict !== false,
      headsetKeepalive: st.headsetKeepalive !== false,
      pulseIntervalMin: [0, 5, 10, 15].includes(Math.round(Number(st.pulseIntervalMin)))
        ? Math.round(Number(st.pulseIntervalMin))
        : 0,
      energyTier: ['L0', 'L1', 'L2', 'L3'].includes(String(st.energyTier || '').toUpperCase())
        ? String(st.energyTier).toUpperCase()
        : 'L2',
      preferredMode: st.preferredMode === 'text' ? 'text' : 'live',
      textGraceUntil,
      textGraceActive: textGraceUntil > now,
      earlyBailoutCount: Math.max(0, Number(st.earlyBailoutCount) || 0),
      autoCooldownMs: Math.max(60_000, Number(st.autoCooldownMs) || LIVE_CORE_DEFAULTS.autoCooldownMs),
      muted: mutedUntil > now,
      mutedUntil,
      phase: evalRes?.phase || st.lastPhase || 'standby',
      reason: evalRes?.reason || st.lastReason || 'standby',
      triggered: Boolean(evalRes?.triggered),
      urgent: Boolean(evalRes?.urgent),
      voiceOnly: Boolean(evalRes?.voiceOnly),
      lastVoiceActiveAt: Math.max(0, Number(st.lastVoiceActiveAt) || 0),
      lastIgniteAt: Math.max(0, Number(st.lastIgniteAt) || 0),
      quizReliefCount: Math.max(0, Number(st.quizReliefCount) || 0),
      lastRecap: st.lastRecap || null,
      activeDepartment: evalRes?.department || st.lastDepartment || st.lastPayload?.department || null,
      dualTrackNext: evalRes?.dualTrackNext || st.lastPayload?.dualTrackNext || pickDualTrackNextStep({
        horizons: readState(stateFile('horizons.json'), null),
        wrongQuestions: feedPreview?.quizzes || [],
      }),
      dice: evalRes?.dice || st.lastPayload?.dice || null,
      feedPreview,
      payload: evalRes?.payload || st.lastPayload || null,
    }
  }

  function buildLiveCoreAtlasAndHistory(now, tz, summaryObj, opts = {}) {
    const ledgerEntries = readLedger(home(), 160)
    const history = formatLiveCoreHistory(ledgerEntries, {
      limit: Number(opts.limit) || LIVE_CORE_DEFAULTS.historyLimit,
      filter: String(opts.filter || 'all'),
      tz,
    })
    const allHistory = String(opts.filter || 'all').toLowerCase() === 'all'
      ? history
      : formatLiveCoreHistory(ledgerEntries, { limit: LIVE_CORE_DEFAULTS.historyLimit, filter: 'all', tz })
    let arbiterLock = null
    try {
      arbiterLock = buildLocksOverview(now, tz)?.activeLock || null
    } catch {}
    let planState = null
    try {
      planState = loadSeals(now, tz)
    } catch {}
    const atlas = buildLiveCoreAtlas({
      now,
      liveCore: summaryObj,
      arbiter: arbiterLock,
      bridgeHealthy: bridgeHealthy(now),
      capabilities: {
        a11y: Boolean(lastPhone?.a11yConnected),
        shizuku: Boolean(lastPhone?.shizukuReady),
      },
      todayStats: {
        liveMinutesToday: Number(lastPhone?.geminiMinutesToday || 0),
      },
      bankStats: cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null)),
      planState,
      history: allHistory,
      tz,
    })
    return { atlas, history }
  }

  async function liveCoreRoute(body = {}, method = 'POST') {
    const now = Date.now()
    const b = body && typeof body === 'object' ? body : {}
    const tz = zoneOr(b.timeZone || config.timeZone)
    const defaultAction = method === 'GET' ? ((b.trigger === '1' || b.trigger === 'true' || b.ignite === '1') ? 'ignite' : 'status') : 'ignite'
    const action = String(b.action || defaultAction).trim()
    if (action === 'status' || action === 'atlas' || action === 'history') {
      const summary = liveCoreSummary(now)
      const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, b)
      return { v: 1, ok: true, ...summary, liveCore: summary, atlas, history }
    }
    if (action === 'mute' || action === 'quiz_relief') {
      const mins = Math.max(1, Math.min(180, Math.round(Number(b.minutes) || 20)))
      const isRelief = action === 'quiz_relief'
      const cur = loadLiveCoreState()
      const reason = isRelief ? 'quiz_relief_valve' : 'manual_mute'
      const phase = isRelief ? 'yielded_quiz_relief' : 'muted'
      const next = {
        ...cur,
        mutedUntil: now + mins * 60_000,
        lastPhase: phase,
        lastReason: reason,
        ...(isRelief ? {
          lastQuizReliefAt: now,
          lastYieldLoggedAt: now,
          lastYieldLoggedReason: 'quiz_relief_valve',
          quizReliefCount: Number(cur.quizReliefCount || 0) + 1,
        } : {}),
      }
      saveLiveCoreState(next)
      record(isRelief
        ? { state: 'live-core-yield', at: now, reason: 'quiz_relief_valve', phase, minutes: mins }
        : { state: 'live-core-mute', at: now, minutes: mins })
      const summary = liveCoreSummary(now, { phase, reason, nextState: next })
      const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, b)
      return {
        v: 1,
        ok: true,
        ...summary,
        liveCore: summary,
        atlas,
        history,
        toast: isRelief ? `连刷 5 题已触发静音泄压 ${mins} 分钟` : `已静音 Gemini 窥视调度 ${mins} 分钟`,
      }
    }
    if (action === 'set_config') {
      const cur = loadLiveCoreState()
      const tierRaw = String(b.energyTier || '').trim().toUpperCase()
      const next = {
        ...cur,
        ...(b.enabled !== undefined ? { enabled: Boolean(b.enabled) } : {}),
        ...(b.autoReengage !== undefined ? { autoReengage: Boolean(b.autoReengage) } : {}),
        ...(b.wantScreenShare !== undefined ? { wantScreenShare: Boolean(b.wantScreenShare) } : {}),
        ...(b.alwaysOnLive !== undefined ? { alwaysOnLive: Boolean(b.alwaysOnLive) } : {}),
        ...(b.keeperStrict !== undefined ? { keeperStrict: Boolean(b.keeperStrict) } : {}),
        ...(b.headsetKeepalive !== undefined ? { headsetKeepalive: Boolean(b.headsetKeepalive) } : {}),
        ...(b.pulseIntervalMin !== undefined && [0, 5, 10, 15].includes(Math.round(Number(b.pulseIntervalMin)))
          ? { pulseIntervalMin: Math.round(Number(b.pulseIntervalMin)) }
          : {}),
        ...(['L0', 'L1', 'L2', 'L3'].includes(tierRaw) ? { energyTier: tierRaw } : {}),
        ...(b.preferredMode !== undefined ? { preferredMode: b.preferredMode === 'text' ? 'text' : 'live' } : {}),
        ...(b.autoCooldownMin !== undefined
          ? { autoCooldownMs: Math.max(1, Math.min(120, Math.round(Number(b.autoCooldownMin)))) * 60_000 }
          : {}),
      }
      saveLiveCoreState(next)
      const summary = liveCoreSummary(now, { nextState: next })
      const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, b)
      return { v: 1, ok: true, ...summary, liveCore: summary, atlas, history, toast: '已更新 Gemini 窥视核心设置' }
    }
    if (action === 'log_session_end') {
      const durationSec = Math.max(0, Math.round(Number(b.durationSec) || 0))
      record({
        state: 'live-session-end',
        at: now,
        durationSec,
        screenOn: b.screenOn !== false,
        headsetConnected: Boolean(b.headsetConnected),
        frontPkg: String(b.frontPkg || '').trim().slice(0, 80),
        earlyBailout: Boolean(b.earlyBailout),
        reason: String(b.reason || 'ended').trim().slice(0, 48),
      })
      return { v: 1, ok: true, durationSec }
    }
    if (action === 'export_diagnostic') {
      const days = Math.max(1, Math.min(14, Math.round(Number(b.days) || 3)))
      const ledgerEntries = readLedger(home(), 800)
      let locksOv = null
      try { locksOv = buildLocksOverview(now, tz) } catch {}
      let planState = null
      try { planState = loadSeals(now, tz) } catch {}
      const bankStats = cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null))
      const historyStore = readState(stateFile('history-365.json'), null)
      const caps = b.capabilities && typeof b.capabilities === 'object'
        ? b.capabilities
        : { a11y: Boolean(lastPhone?.a11yConnected), shizuku: Boolean(lastPhone?.shizukuReady) }
      const exported = buildDiagnosticExport({
        now,
        days,
        tz,
        ledgerEntries,
        liveCoreState: loadLiveCoreState(),
        locksOverview: locksOv,
        bankStats,
        planState,
        historyStore,
        phone: lastPhone,
        capabilities: caps,
        kbDir: (() => { try { return kbDir(notesDir(), home()) } catch { return '' } })(),
      })
      return {
        v: 1,
        ...exported,
        toast: exported.obsidianPath
          ? `已生成 ${days * 24}h 实证诊断包并同步至 Obsidian`
          : `已生成 ${days * 24}h 实证诊断包（可一键复制或分享文件）`,
      }
    }
    if (action === 'autopilot_ignite') {
      const cur = loadLiveCoreState()
      const nextTier = cur.energyTier === 'L0' ? 'L2' : (cur.energyTier || 'L2')
      const nextCfg = {
        ...cur,
        enabled: true,
        alwaysOnLive: true,
        keeperStrict: true,
        headsetKeepalive: true,
        pulseIntervalMin: 5,
        energyTier: nextTier,
        preferredMode: 'live',
        textGraceUntil: 0,
      }
      saveLiveCoreState(nextCfg)
      const curMin = zoneMinuteOfDay(now, tz)
      const isLate = curMin >= 22 * 60 + 15
      const target = isLate ? 'tomorrow' : 'today'
      let due1 = isLate ? (9 * 60 + 30) : (curMin + 30)
      if (!isLate && due1 >= 12 * 60 && due1 < 14 * 60) due1 = 14 * 60 + 25
      due1 = Math.min(22 * 60 + 45, due1)
      let due2 = due1 + 45
      if (!isLate && due2 >= 12 * 60 && due2 < 14 * 60) due2 = 14 * 60 + 50
      due2 = Math.min(22 * 60 + 55, due2)
      const blocks = [
        {
          title: '全自动托管 · 25m 核心刷题/推进',
          kind: 'bout_minutes',
          stretch: 25,
          floor: 15,
          estMinutes: 25,
          dueMinute: due1,
        },
        {
          title: '全自动托管 · 25m 错因复盘巩固',
          kind: 'bout_minutes',
          stretch: 25,
          floor: 15,
          estMinutes: 25,
          dueMinute: due2,
        },
      ]
      let planRes = null
      try {
        planRes = sealsRoute({ action: 'plan-day', target, seals: blocks, by: 'live-autopilot' })
      } catch {}
      const curBout = runningBoutNow()
      if (curBout?.id) {
        try {
          routeBoutDepartment({
            db: openBoutsDb(home()),
            home: home(),
            boutId: curBout.id,
            currentDept: curBout.department || '',
            input: { explicitDepartment: 'dept:govern' },
            now,
          })
        } catch {}
      }
      const addedCount = Array.isArray(planRes?.added) ? planRes.added.length : 0
      record({
        state: 'live-core-autopilot',
        at: now,
        target,
        addedCount,
        energyTier: nextTier,
      })
      const res = evaluateLiveCoreNow(now, {
        force: true,
        tz,
        targetMode: 'live',
        modeHint: 'autopilot_ignite',
        diceHint: 1,
        phone: b.phone || lastPhone || {},
      })
      const summary = liveCoreSummary(now, res)
      const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, b)
      return {
        v: 1,
        ok: true,
        ...summary,
        liveCore: summary,
        atlas,
        history,
        payload: res.payload,
        autopilot: { target, addedCount, blocks },
        toast: addedCount > 0
          ? `⚡ 全自动托管已启动：自动挂好 ${addedCount} 个 25m 验收点 + 常驻 Live 护航`
          : '⚡ 全自动托管已启动：常驻 Live 护航与今日验收锁已联动',
      }
    }
    if (action === 'switch_mode') {
      const mode = b.mode === 'text' ? 'text' : 'live'
      const mins = Math.max(5, Math.min(60, Math.round(Number(b.minutes) || 15)))
      const cur = loadLiveCoreState()
      const basePatch = mode === 'text'
        ? { ...cur, preferredMode: 'text', textGraceUntil: now + mins * 60_000, receiptSinceLastLive: true }
        : { ...cur, preferredMode: 'live', textGraceUntil: 0 }
      saveLiveCoreState(basePatch)
      const res = evaluateLiveCoreNow(now, {
        force: true,
        tz,
        targetMode: mode,
        modeHint: b.modeHint || '',
        diceHint: b.diceHint || b.dice || 0,
        customFeed: b.customFeed || '',
        scalpelHint: b.scalpelHint || '',
        phone: b.phone || lastPhone || {},
      })
      record({
        state: 'live-core-switch',
        at: now,
        mode,
        reason: mode === 'text' ? 'switch_to_text' : 'switch_to_live',
        minutes: mins,
        hasCustomFeed: Boolean(String(b.customFeed || '').trim()),
        department: res?.department?.id || '',
      })
      const summary = liveCoreSummary(now, res)
      const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, b)
      return {
        v: 1,
        ok: true,
        ...summary,
        liveCore: summary,
        atlas,
        history,
        payload: res.payload,
        result: res,
        toast: mode === 'text'
          ? `已切至 Gemini 文本模式（${mins} 分钟沉淀保护窗）`
          : '已生成最新喂料并切回 Gemini Live（语音 + 看屏）',
      }
    }
    if (action === 'commit_relay' || action === 'submit_recap') {
      const modeRaw = String(b.mode || (action === 'submit_recap' ? 'recap_only' : 'checkpoint_25m')).trim()
      const mode = ['checkpoint_25m', 'quiz_only', 'recap_only'].includes(modeRaw) ? modeRaw : 'checkpoint_25m'
      const defaultMin = mode === 'quiz_only' ? 20 : 25
      const minutes = Math.max(10, Math.min(60, Math.round(Number(b.minutes) || defaultMin)))
      const summaryText = String(b.summary || '').trim().slice(0, 180)
      const nextText = String(b.next || b.title || '').trim().slice(0, 100)
      const cur = loadLiveCoreState()
      const next = {
        ...cur,
        receiptSinceLastLive: true,
        lastPhase: 'standby',
        lastReason: `relay_${mode}`,
        lastRecap: {
          at: now,
          summary: summaryText,
          next: nextText,
          mode,
        },
      }
      saveLiveCoreState(next)

      let checkpointResult = null
      let capsuleResult = null
      let toast = '已记录本轮 Live 复盘并闭环交接'

      if (mode === 'checkpoint_25m') {
        const curMin = zoneMinuteOfDay(now, tz)
        const target = curMin < 1435 ? 'today' : 'tomorrow'
        const dueMinute = curMin < 1435 ? Math.min(1439, curMin + minutes) : 9 * 60 + 30
        const sealTitle = nextText || summaryText || `Live 接力 ${minutes}m 产出`
        checkpointResult = sealsRoute({
          action: 'plan-day',
          target,
          seals: [{
            title: sealTitle,
            kind: 'bout_minutes',
            stretch: minutes,
            floor: Math.max(10, Math.round(minutes / 2)),
            estMinutes: minutes,
            dueMinute,
          }],
          by: 'live-relay',
        })
        if (checkpointResult?.added?.length) {
          toast = `已签下「${sealTitle}」（${minutes}m 微验收点）`
        } else {
          const skipReason = checkpointResult?.skipped?.[0]?.text || '今日验收条数已满'
          toast = `已交接本轮 Live（验收点未追加：${skipReason}）`
        }
      } else if (mode === 'quiz_only') {
        capsuleResult = await boutsRoute({
          action: 'reverse_capsule',
          mode: 'quiz_only',
          minutes,
          goal: nextText || 'Live 接力 · 纯考公刷题舱',
          by: 'live-relay',
          installedApps: lastPhone?.installedApps,
        })
        toast = capsuleResult?.toast || `已切入 ${minutes}m 纯考公刷题舱`
      }

      const curBoutRelay = runningBoutNow()
      if (curBoutRelay?.id) {
        try {
          routeBoutDepartment({
            db: openBoutsDb(home()),
            home: home(),
            boutId: curBoutRelay.id,
            currentDept: curBoutRelay.department || '',
            input: { explicitDepartment: mode === 'recap_only' ? 'dept:collect' : 'dept:govern' },
            now,
          })
        } catch {}
      }

      record({
        state: 'live-core-relay',
        at: now,
        mode,
        summary: summaryText.slice(0, 120),
        next: nextText.slice(0, 80),
        minutes,
      })
      const summary = liveCoreSummary(now, { phase: 'standby', reason: `relay_${mode}`, nextState: next })
      const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, b)
      return {
        v: 1,
        ok: true,
        ...summary,
        liveCore: summary,
        atlas,
        history,
        relay: {
          mode,
          minutes,
          summary: summaryText,
          next: nextText,
          checkpointResult,
          capsuleResult,
        },
        toast,
      }
    }
    if (['roll_dice', 'ignite', 'trigger', 'reengage'].includes(action)) {
      const res = evaluateLiveCoreNow(now, {
        force: true,
        tz,
        modeHint: b.modeHint || b.mode || '',
        diceHint: b.diceHint || b.dice || 0,
        customFeed: b.customFeed || '',
        scalpelHint: b.scalpelHint || '',
        phone: b.phone || lastPhone || {},
      })
      const summary = liveCoreSummary(now, res)
      const { atlas, history } = buildLiveCoreAtlasAndHistory(now, tz, summary, b)
      return {
        v: 1,
        ok: Boolean(res.triggered),
        ...summary,
        liveCore: summary,
        atlas,
        history,
        payload: res.payload,
        result: res,
      }
    }
    return { v: 1, ok: false, error: 'bad-action' }
  }

  // ---------------------------------------------------------------- 2026-11-18 推进器 + 三档
  const loadDrive = () => readState(stateFile('drive.json'), {}) || {}
  const saveDrive = (d) => writeJson(stateFile('drive.json'), d)
  let lastDriveMode = 'partner'
  let lastTakeover = null
  /** 接管时的白名单：工作层 + Gemini + 代理 + 他的默认白名单。 */
  function takeoverAllow() {
    const tf = tierFnNow()
    return [...new Set([
      ...WORK_DEFAULT,
      ...lastInstalled.filter((a) => a?.pkg && tf(a.pkg, a.label) === 'work').map((a) => a.pkg),
      ...geminiPackages(),
      'moe.nb4a',
      ...(Array.isArray(config.defaultAllow) ? config.defaultAllow : []),
    ])]
  }
  /**
   * 推进器一次：算档位、走接管状态机、挑这一刻要推的一件事。
   * 只产出「推什么」，开不开口仍由下面的发球逻辑（在聊天中、正在生成时不插嘴）。
   */
  function driveNow(now, tz, phone, water, radar, blocked) {
    const st = loadDrive()
    const tf = tierFnNow()
    const frontTier = phone?.app ? tf(phone.app, phone.label) : 'base'
    const frontMinutes = Number(phone?.minutes) || 0
    const day = dayString(now, tz)
    const ledger = readLedger(home(), 400)
    const drifts30 = ledger.filter((e) => e?.state === 'lease-drift' && now - Number(e.at) <= 30 * 60_000).length
    const receiptJustNow = ledger.some((e) => e?.state === 'gemini-recap' && e.receipt === true && now - Number(e.at) <= 3 * 60_000)
    const run = runningBoutNow()
    // 局刚结束：上一刻有局、这一刻没了。
    let lastBoutEndAt = Number(st.lastBoutEndAt) || 0
    if (st.runId && !run) lastBoutEndAt = now
    // 2026-11-20 主动点火要的三件事：今天开过局没有、今天第一次亮屏是几点、上次点火是几点。
    const dayStartMs = startOfDay(now, config.timeZone)
    const boutDay = run ? day : String(st.boutDay || '')
    const openedToday = Boolean(run) || boutDay === day || lastBoutEndAt >= dayStartMs
    const firstScreenAt = String(st.screenDay || '') === day ? Number(st.firstScreenAt) || 0 : (phone?.screenOn !== false && phone?.app ? now : 0)
    const screenDay = firstScreenAt ? day : String(st.screenDay || '')
    const runId = run ? String(run.id || run.protocolId || 'live') : ''
    let contractOverdue = false
    let checkpoints = []
    try {
      contractOverdue = Boolean(v4Hub?.contractSessionView?.(now, { escalatedOnly: true }))
      const c = readState(stateFile('plan-contract.json'), null)
      checkpoints = Array.isArray(c?.checkpoints) ? c.checkpoints.map((cp) => ({ id: cp.id, title: cp.title, dueAt: Number(cp.dueAt) || 0, status: cp.status })) : []
    } catch {
      // 没有契约
    }
    let planItems = []
    try {
      planItems = workStoreNow(now, tz).planItems.map((i) => ({ id: i.id, title: i.title, minute: i.minute, openedAt: i.openedAt, missed: i.missed, first: i.packet?.first }))
    } catch {
      planItems = []
    }
    let quota = null
    try {
      quota = quotaNow(now, tz, phone)
    } catch {
      quota = null
    }
    const intInfo = intensityStateNow(now, tz)
    const th = intInfo.thresholds
    const min = localMinute(phone, zoneMinuteOfDay(now, tz))
    const boutGap = !run && lastBoutEndAt > 0 && now - lastBoutEndAt >= th.boutGapPushMin * 60_000 && min >= 9 * 60 && min < 21 * 60 + 30
    const m = driveMode({
      tideBand: water?.band, tideLevel: water?.level, frontTier, frontMinutes,
      quotaOut: Boolean(quota && ((quota.funOut && frontTier === 'fun') || (quota.gameOut && frontTier === 'game'))),
      leaseDrifts30m: drifts30, contractOverdue, boutGap, noPlan: Boolean(planGateNow(now, tz)) && min >= 9 * 60 + 30,
      switchDice: radar?.kind === 'switch-limit-dice',
    })
    const tk = stepTakeover(st.takeover, {
      now, day, mode: m.mode, why: m.why, frontTier, frontMinutes, boutRunning: Boolean(run), receiptJustNow, blocked,
      takeoverMaxMs: th.takeoverMaxMin * 60_000,
      takeoverMaxPerDay: th.takeoverMaxPerDay,
    })
    if (tk.event === 'start') {
      record({ state: 'takeover-start', at: now, why: tk.why, n: tk.state.count })
      recordHardNow({ kind: 'takeover', source: 'driver', why: tk.why, now, tz, dedupeKey: 'takeover:' + tk.state.since })
      commit({ aiWorkSession: { allow: takeoverAllow(), until: now + th.takeoverMaxMin * 60_000, strict: true, why: '接管：' + tk.why, by: 'takeover' } })
      try {
        const nextItem = planItems.filter((i) => !i.openedAt && !i.missed).sort((a, b) => Number(a.minute) - Number(b.minute))[0] || null
        saveCard(takeoverCard({ why: tk.why, action: returnAction({ nextItem }), now }))
      } catch {}
    } else if (tk.event === 'end') {
      record({ state: 'takeover-end', at: now, why: tk.why, minutes: Math.round((now - Number(st.takeover?.since || now)) / 60_000) })
      if (config.aiWorkSession?.by === 'takeover') commit({ aiWorkSession: null })
      if (loadCard()?.by === 'takeover') saveCard(null)
    }
    const memo = { ...(st.memo || {}) }
    let drive = null
    let ignited = null
    if (tk.state.active) {
      const nextItem = planItems.filter((i) => !i.openedAt && !i.missed).sort((a, b) => Number(a.minute) - Number(b.minute))[0] || null
      const lastSpoke = Number(memo.takeoverSpoke) || 0
      if (now - lastSpoke >= TAKEOVER_SPEAK_GAP_MS) {
        drive = {
          kind: 'takeover', key: 'takeoverSpoke', role: Number(tk.state.spokeN || 0) % 2 === 0 ? 'strategist' : 'companion',
          text: '【接管】' + tk.why + '。锁已经落了（只剩工作 App、Gemini、系统页面）。回位动作：' + returnAction({ nextItem, workApp: '' }) +
            '。做到了接管自动解除，最长 ' + th.takeoverMaxMin + ' 分钟。他求情「再玩一会」可以不答应；他要真有急事让他用通知栏的紧急解除。语气看你自己，但别长篇。',
        }
        tk.state.spokeN = Number(tk.state.spokeN || 0) + 1
      }
    } else {
      let deadlines = []
      try {
        deadlines = v4Hub?.upcomingDeadlines ? v4Hub.upcomingDeadlines(now) : []
      } catch {
        deadlines = []
      }
      // 2026-11-20 时间轴：验收点到了开始时刻就开局；截止前 10 分钟没过保底就提醒。排在别的推进前面。
      try {
        drive = timelineDrive({ now, tz, min, run, memo, phone, frontTier, blocked, intensity: intInfo.effectiveIntensity || intInfo.intensity })
      } catch (error) {
        console.error('[companion] 时间轴推进失败：' + String((error && error.message) || error))
      }
      if (!drive) drive = nextDrive({
        now, minuteOfDay: min, mode: m.mode, boutRunning: Boolean(run), lastBoutEndAt, planItems, checkpoints, deadlines, memo,
        boutGapPushMs: th.boutGapPushMin * 60_000,
        boutGapAutoMs: th.boutGapAutoOpenMin * 60_000,
        dayStartMs,
      })
      // 2026-11-20 冷启动点火：推进器这一拍没别的事、今天还没开过局时，才轮到它。
      if (!drive) {
        const followupOpen = Boolean(config.followup && Number(config.followup.until) > now) || Boolean(config.followupGemini)
        const mealIn = mealNow(wallConfig(config).meals, localMinute(phone, min))
        ignited = ignite({
          now, minuteOfDay: min, boutRunning: Boolean(run), openedToday,
          blocked: blocked || followupOpen || Boolean(mealIn) || onBreak(now) || isEmergencyPassOrDisarmActive(now, tz),
          firstScreenAt, lastIgniteAt: Number(memo.ignite) || 0,
          frontApp: phone?.app || '', frontTier, frontMinutes,
          idleMin: th.boutGapAutoOpenMin,
          intensity: intInfo.effectiveIntensity || intInfo.intensity,
          planItems,
        })
        // 时间轴上已经有今天没交的点：他定过了，不再拉 Gemini 来「定第一局」（到点会自动开局）。
        if (ignited?.action === 'gemini') {
          try {
            const sd = loadSeals(now, tz).items
            if (sd.some((x) => x.day === day && (x.status === 'open' || x.status === 'below'))) ignited = null
          } catch {}
        }
        if (ignited) {
          memo.ignite = now
          record({ state: 'ignite', at: now, action: ignited.action, reason: ignited.reason, itemId: ignited.itemId || '', front: phone?.app || '', frontMinutes })
          if (ignited.action === 'open-bout') {
            recordHardNow({ kind: 'cold_start', source: 'driver', why: '冷启动开第一局', now, tz, dedupeKey: 'ignite:' + day })
            Promise.resolve(boutsRoute({ action: 'start', id: ignited.itemId, by: 'ignite' }))
              .then((r) => record({ state: 'ignite-open', at: Date.now(), itemId: ignited.itemId, ok: r?.ok === true, toast: String(r?.toast || '').slice(0, 80) }))
              .catch(() => {})
            drive = { kind: 'cold-start', key: 'ignite', role: 'strategist',
              text: '今天第一局「' + ignited.title + '」已经开了（他在' + (phone?.label || '手机上') + '空转了 ' + Math.round(frontMinutes) + ' 分钟）：一句话告诉他第一步' +
                (ignited.first ? '：' + String(ignited.first).slice(0, 40) : '') + '。不想做可以在局详情收工。' }
          } else if (ignited.action === 'hint') {
            drive = { kind: 'cold-start-hint', key: 'ignite', role: 'strategist', text: ignited.text }
          }
          // action === 'gemini'：这一拍的动作是拉起 Gemini（/plan 的拉起段处理），不再开口。
        }
      }
      if (drive?.kind === 'auto-open') {
        memo[drive.key] = now
        Promise.resolve(boutsRoute({ action: 'start', id: drive.itemId, by: 'driver' }))
          .then((r) => record({ state: 'drive-auto-open', at: Date.now(), itemId: drive.itemId, ok: r?.ok === true, toast: String(r?.toast || '').slice(0, 80) }))
          .catch(() => {})
      }
    }
    saveDrive({ ...st, takeover: tk.state, memo, lastBoutEndAt, runId, mode: m.mode, why: m.why, at: now, boutDay, screenDay, firstScreenAt })
    lastDriveMode = tk.state.active ? 'takeover' : m.mode
    lastTakeover = tk.state.active ? tk.state : null
    return { mode: lastDriveMode, why: m.why, drive, takeover: tk.state, ignite: ignited }
  }

  /**
   * 时间轴推进（纯规则在 seal.js 的 timelineDue）：
   *   start：到开始时刻 → 能开就直接开局（被挡着——聊天 / 吃饭 / 紧急 / 深夜 / 温和档——就只提醒一句，15 分钟一次）；
   *   warn ：截止前 10 分钟还没过保底 → 提醒一次。
   */
  function timelineDrive({ now, tz, min, run, memo, phone, frontTier, blocked, intensity }) {
    const today = dayString(now, tz)
    const all = loadSeals(now, tz)
    // 晚上 22:30–23:00：明天还一条没封，提醒一次（23:00 前封的不打折）。
    if (min >= 22 * 60 + 30 && min < SEAL_CUTOFF_MINUTE && !blocked && !all.items.some((x) => x.day === nextYmd(today))) {
      const key = 'seal-remind:' + today
      if (!(Number(memo[key]) > 0)) {
        memo[key] = now
        return { kind: 'seal-remind', key, role: 'strategist',
          text: '明天还一条没封。23:00 前封的不打折，过了算现加：问他明天最要紧的一两件、几点开始、几点前交、保底多少，他说定了就用 add_checkpoints（target=tomorrow）写进去。别长篇。' }
      }
    }
    const due = timelineDue(all, { today, minuteOfDay: min, boutRunning: Boolean(run) })
    if (!due) return null
    const s = due.seal
    const unit = SEAL_KINDS[s.kind]?.unit || ''
    const seen = (key, ms) => Number(memo[key]) && now - Number(memo[key]) < ms
    if (due.kind === 'warn') {
      const key = 'tlw:' + s.id
      if (seen(key, 3 * 3_600_000)) return null
      memo[key] = now
      return { kind: 'timeline-warn', key, role: 'strategist',
        text: '验收点「' + s.title + '」还有 ' + due.left + ' 分钟到点，现在 ' + s.actual + '/' + s.floor + unit + '（保底）。到点没过保底会锁：叫他先把保底交上，别解释。' }
    }
    const key = 'tls:' + s.id
    const inChat = CHAT_PACKAGES.includes(String(phone?.app || ''))
    const mealIn = mealNow(wallConfig(config).meals, localMinute(phone, min))
    const held = blocked || inChat || Boolean(mealIn) || onBreak(now) || isEmergencyPassOrDisarmActive(now, tz) || intensity === 'gentle' ||
      frontTier === 'fun' || frontTier === 'game'
    if (held) {
      if (seen(key, 15 * 60_000)) return null
      memo[key] = now
      return { kind: 'timeline-hint', key, role: 'strategist',
        text: '时间轴上「' + s.title + '」该开始了（' + sealHm(s.dueMinute) + ' 前交 ' + s.stretch + unit + '，保底 ' + s.floor + '）。' +
          (inChat ? '他在聊天，等他回完再说一句。' : frontTier === 'fun' || frontTier === 'game' ? '他在花自己挣的余额，提醒一句就行，余额用完自然会锁。' : '一句话提醒他开局。') }
    }
    if (seen(key, 10 * 60_000)) return null
    memo[key] = now
    const r = openSealBout(s, now, tz, 'timeline')
    record({ state: 'timeline-open', at: now, id: s.id, ok: r.ok === true, toast: String(r.toast || '').slice(0, 80) })
    if (!r.ok) return null
    recordHardNow({ kind: 'timeline_start', source: 'driver', why: '时间轴开局：' + s.title, now, tz, dedupeKey: 'tls:' + s.id })
    return { kind: 'timeline-start', key, role: 'strategist',
      text: '时间轴上「' + s.title + '」到点了，局已经开好（' + sealHm(s.dueMinute) + ' 前交 ' + s.stretch + unit + '，保底 ' + s.floor + unit + '）。一句话告诉他第一步。不想做也得先交保底，真有事走豁免。' }
  }

  // ---------------------------------------------------------------- 2026-11-18 会话换班
  const handoffFile = (role) => stateFile('handoff-' + (role === 'strategist' ? 'strategist' : 'companion') + '.json')
  function roleSession(role) {
    return role === 'strategist' ? String(config.strategistSessionId || '') : String(config.sessionId || '')
  }
  function rotateStatusNow(now = Date.now()) {
    const entries = readLedger(home(), 5000)
    const bound = config.sessionBoundAt || {}
    return {
      companion: rotationStatus(entries, { sessionId: roleSession('companion'), boundAt: Number(bound.companion) || 0, now }),
      strategist: rotationStatus(entries, { sessionId: roleSession('strategist'), boundAt: Number(bound.strategist) || 0, now }),
    }
  }
  function handoffFacts(now) {
    let db = null
    try {
      db = openMemoryDb(memoryPath())
      return db ? backgroundFacts(loadCandidates(db, { sessionId: config.sessionId, now }), 6) : []
    } catch {
      return []
    } finally {
      try { db?.close?.() } catch {}
    }
  }
  function prepareHandoff(role, now = Date.now()) {
    const names = roleNames(config)
    const sid = roleSession(role)
    const entries = readLedger(home(), 1500)
    let verdictLine = ''
    try {
      verdictLine = (v4Hub?.verifierPromptLines?.(now) || []).find((l) => l.startsWith('昨天的判决')) || ''
    } catch {}
    const text = buildHandoff({
      roleName: role === 'strategist' ? names.strategist : names.companion,
      owner: String(config.owner || '他'),
      dialog: recentDialog(entries, { sessionId: sid, limit: 14 }),
      humanRecent: recentHuman.slice(-4),
      commitments: readCommitments(home(), now, 5).map((c) => c.statement),
      topics: (garden?.topics || []).filter((t) => t && t.status !== 'done').slice(0, 6).map((t) => t.title + (t.detail ? '（' + String(t.detail).slice(0, 60) + '）' : '')),
      stance: (() => { try { return stanceText() } catch { return '' } })(),
      gemini: geminiLines(loadGemini(), now),
      verdictLine,
      facts: handoffFacts(now),
      now,
    })
    const h = { text, at: now, from: sid, to: '', role }
    writeJson(handoffFile(role), h)
    try {
      const kb = vaultKb()
      if (kb) {
        const day = dayString(now, zoneOr(config.timeZone))
        writeVaultText(join(kb, '会话交接', day + '-' + (role === 'strategist' ? names.strategist : names.companion) + '.md'), text + '\n')
      }
    } catch {
      // 笔记库写不进去：交接还在 companion/ 里
    }
    record({ state: 'session-handoff', at: now, role, from: sid, chars: text.length })
    return h
  }
  /** POST /rotate：status / prepare（写交接）/ bound（App 新建好对话后绑上）。 */
  function rotateRoute(body = {}) {
    const now = Date.now()
    const action = String(body?.action || 'status')
    const role = body?.role === 'strategist' ? 'strategist' : 'companion'
    if (action === 'prepare') {
      const h = prepareHandoff(role, now)
      return { v: 1, ok: true, role, chars: h.text.length, preview: h.text.slice(0, 400) }
    }
    if (action === 'bound') {
      const sid = String(body?.sessionId || '').trim()
      if (!sid || sid.length > 256) return { v: 1, ok: false, error: 'sessionId' }
      const other = role === 'strategist' ? String(config.sessionId || '') : String(config.strategistSessionId || '')
      if (sid === other) return { v: 1, ok: false, error: 'same-as-sister' }
      const old = roleSession(role)
      commit({
        ...(role === 'strategist' ? { strategistSessionId: sid } : { sessionId: sid }),
        sessionBoundAt: { ...(config.sessionBoundAt || {}), [role]: now },
      })
      const h = readState(handoffFile(role), null)
      if (h) writeJson(handoffFile(role), { ...h, to: sid, boundAt: now })
      record({ state: 'session-rotate', at: now, role, from: old, to: sid })
      try { mountPersona() } catch {}
      return { v: 1, ok: true, role, sessionId: sid }
    }
    return { v: 1, ok: true, ...rotateStatusNow(now) }
  }
  /** 新对话头几天每轮带上的交接。 */
  function handoffBlockText(role, now = Date.now()) {
    const h = readState(handoffFile(role), null)
    if (!h?.to || h.to !== roleSession(role)) return ''
    const turnsSince = readLedger(home(), 600).filter((e) => (e?.state === 'generated' || e?.state === 'human') && String(e.sessionId || '') === h.to && Number(e.at) >= Number(h.boundAt || h.at)).length
    return handoffLive({ ...h, at: Number(h.boundAt || h.at) }, { now, turnsSince }) ? h.text : ''
  }

  // ---------------------------------------------------------------- 2026-11-18 当下任务卡
  const loadCard = () => readState(stateFile('card.json'), null)
  const saveCard = (c) => writeJson(stateFile('card.json'), c)
  const cardAppOk = (pkg) => ['work', 'dual'].includes(tierFnNow()(pkg))
  function showCardTool(role) {
    return {
      name: 'show_card',
      description: '把当下的任务画成一张卡给他看（显示在中枢和局工作台顶上）：标题、一句话目标、≤6 个步骤、倒计时分钟、交付物、验收方式、一句提示、≤3 个按钮。' +
        '按钮只能是 start_next_bout（开今天计划里的下一局）/ connect_gemini（摇一骰带简报连 Gemini）/ open_app（app 填包名，只许工作或双用途 App）。' +
        '说不清的任务、刚开的局、他问「我现在该干嘛」时用；同一时刻只有一张，新的盖旧的。',
      parameters: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          goal: { type: 'string' },
          steps: { type: 'array', items: { type: 'string' }, maxItems: 6 },
          minutes: { type: 'integer', minimum: 1, maximum: 180 },
          deliverable: { type: 'string' },
          accept: { type: 'string' },
          tip: { type: 'string' },
          buttons: {
            type: 'array', maxItems: 3,
            items: { type: 'object', properties: { label: { type: 'string' }, action: { type: 'string', enum: ['start_next_bout', 'connect_gemini', 'open_app'] }, app: { type: 'string' } }, required: ['action'], additionalProperties: false },
          },
        },
        required: ['title', 'steps'],
        additionalProperties: false,
      },
      output: toolOutput,
      execute: (args = {}) => {
        const r = cleanCard(args, { now: Date.now(), by: role, appOk: cardAppOk })
        if (!r.ok) return { ok: false, text: '卡没做成：' + r.problems.join('；') }
        saveCard(r.card)
        record({ state: 'card', at: Date.now(), by: role, title: r.card.title, steps: r.card.steps.length })
        return { ok: true, text: '卡已经挂上了「' + r.card.title + '」' + (r.problems.length ? '（丢掉了：' + r.problems.join('；') + '）' : '') }
      },
    }
  }
  /** POST /card：get / step {index} / button {index} / clear。 */
  async function cardRoute(body = {}) {
    const now = Date.now()
    const action = String(body?.action || 'get')
    const cur = loadCard()
    if (!cardLive(cur, now)) return { v: 1, ok: true, card: null }
    if (action === 'clear') {
      saveCard(null)
      return { v: 1, ok: true, card: null }
    }
    if (action === 'step') {
      const next = toggleStep(cur, Number(body?.index))
      saveCard(next)
      return { v: 1, ok: true, card: next }
    }
    if (action === 'button') {
      const b = cur.buttons?.[Number(body?.index)]
      if (!b) return { v: 1, ok: false, card: cur, toast: '没有这个按钮' }
      record({ state: 'card-button', at: now, action: b.action })
      if (b.action === 'open_app') return { v: 1, ok: true, card: cur, open: b.app }
      if (b.action === 'connect_gemini') {
        const roll = diceRollRoute({ manual: true, topic: cur.title })
        return { v: 1, ok: true, card: cur, gemini: { topic: '骰' + roll.dice + ' · ' + roll.name, brief: String(roll.geminiPrompt || '') } }
      }
      if (b.action === 'start_next_bout') {
        const items = (planDayRoute({ action: 'list' }).plan?.items || []).filter((i) => !i.openedAt && !i.missed).sort((a, c) => Number(a.minute) - Number(c.minute))
        if (!items.length) return { v: 1, ok: false, card: cur, toast: '今天计划里没有还没开的局：让鲸澜现排一局' }
        const r = await boutsRoute({ action: 'start', id: items[0].id, by: 'card' })
        return { v: 1, ok: r?.ok === true, card: cur, toast: String(r?.toast || '') }
      }
    }
    return { v: 1, ok: true, card: cur }
  }

  // ---------------------------------------------------------------- 2026-11-18 思考层
  const loadReflect = () => readState(stateFile('reflect.json'), null)
  function reflectInputs(now, tz) {
    const reports = readState(stateFile('evening-reports.json'), null)?.reports || {}
    const verdicts = Object.keys(reports).sort().slice(-7).map((d) => d + ' ' + String(reports[d]?.verdict?.line || reports[d]?.headline || '').slice(0, 160))
    const day = dayString(now, tz)
    const led = readLedger(home(), 1200).filter((e) => dayString(Number(e?.at) || 0, tz) === day)
    const drives = [
      '推进 ' + led.filter((e) => e.state === 'drive').length + ' 次，接管 ' + led.filter((e) => e.state === 'takeover-start').length + ' 次，紧急解除 ' + led.filter((e) => e.state === 'disarm').length + ' 次',
      ...led.filter((e) => e.state === 'takeover-start').slice(-3).map((e) => '接管原因：' + String(e.why || '')),
    ]
    const g = loadGemini()
    const receipts = (Array.isArray(g.recaps) ? g.recaps : []).slice(-5).map((r) => String(r.topic || '') + '：' + String(r.summary || '') + (r.conclusion ? '；结论 ' + r.conclusion : '') + (r.next ? '；下一步 ' + r.next : '') + (r.receipt === false ? '（无完整回执）' : ''))
    let horizons = []
    try {
      const h = readState(stateFile('horizons.json'), null) || {}
      horizons = [...(h.long || []), ...(h.medium || [])].slice(0, 8).map((i) => (i.horizon === 'long' ? '长期' : '中期') + '：' + i.title + ' ' + i.doneSteps + '/' + i.targetSteps + (i.due ? ' 截止 ' + i.due : ''))
      const ws = workStoreNow(now, tz)
      if (ws.totalCount > 0) {
        horizons.unshift(`今日 workStore 共 ${ws.totalCount} 件（已完成 ${ws.doneCount}，待推进 ${ws.openCount}）`)
      }
    } catch {}
    let notes = []
    try {
      const dir = notesDir()
      if (dir) notes = noteExcerpts(vaultRecentNotes(dir, { limit: 6, now, exclude: [] }).map((f) => ({ rel: f.rel, path: join(dir, f.rel) })), (p) => readVaultText(p) || '')
    } catch {}
    const prev = loadReflect()?.value
    return {
      verdicts,
      appLedger: appLedgerLines(now),
      quota: lastQuota?.line || '',
      deadlines: (() => { try { return (v4Hub?.upcomingDeadlines?.(now) || []).map((d) => d.title + ' 还剩 ' + d.daysLeft + ' 天') } catch { return [] } })(),
      horizons,
      receipts,
      drives,
      said: recentHuman.slice(-8).map((h) => h.text),
      notes,
      previous: prev ? [...(prev.urgent || []).map((x) => '要紧：' + x.what), ...(prev.patterns || []).map((x) => '规律：' + x.what)] : [],
    }
  }
  async function runReflectNow(now, tz, slot) {
    const target = nightlyTargetOf(ctx, config)
    record({ state: 'reflect', at: now, slot, stage: 'start' })
    const out = await runReflect({ stream: streamOf(), provider: target.provider, model: target.model, system: reflectSystem(roleNames(config)), user: reflectUser(reflectInputs(now, tz)) })
    if (!out.ok) {
      record({ state: 'reflect', at: Date.now(), slot, stage: 'discard', why: String(out.problems?.[0] || '').slice(0, 120) })
      return null
    }
    const v = out.value
    writeJson(stateFile('reflect.json'), { at: Date.now(), slot, value: v })
    // 她们给自己调参：伙伴档间隔（有上下限）。
    if (v.self?.companionGapMin) {
      commit({ minGapMs: v.self.companionGapMin * 60_000 })
      record({ state: 'reflect-self', at: Date.now(), minGapMin: v.self.companionGapMin, note: v.self.note || '' })
    }
    // 改严的 App 分层直接执行；其余只是建议。
    const tf = tierFnNow()
    for (const p of v.proposals || []) {
      if (p.kind !== 'tighten_app') continue
      const before = tf(p.app)
      if (before === 'base' || (TIER_RANK[p.tier] ?? 0) <= (TIER_RANK[before] ?? 1)) continue
      commit({ appTiers: { ...(config.appTiers || {}), [p.app]: p.tier } })
      record({ state: 'app-tier-set', at: Date.now(), pkg: p.app, from: before, to: p.tier, by: 'reflect', why: p.evidence })
      writeTierFile()
    }
    try {
      const kb = vaultKb()
      if (kb) {
        const label = dayString(now, tz) + ' ' + (slot === 0 ? '午间' : '晚间')
        writeVaultText(join(kb, '两条鱼', '思考', dayString(now, tz) + '-' + (slot === 0 ? '午' : '晚') + '.md'), reflectMarkdown(v, { at: now, label }))
      }
    } catch {}
    record({ state: 'reflect', at: Date.now(), slot, stage: 'done', urgent: v.urgent.length, patterns: v.patterns.length, proposals: v.proposals.length, dropped: out.dropped || 0 })
    return v
  }

  function planRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    lastPlanAt = now
    try {
      syncTierFile(now)
    } catch (error) {
      console.error('[companion] App分层.md 同步失败：' + String((error && error.message) || error))
    }
    try {
      syncCovenantNow(now)
    } catch (error) {
      console.error('[companion] 公约.md 同步失败：' + String((error && error.message) || error))
    }
    // Gemini 桥：不挡这一拍（异步），另有 20 秒的定时器兜着。
    if (bridgeConf().enabled) {
      ensureBridgeTimer()
      bridgeSync({ now }).catch(() => {})
    }
    try {
      syncVaultRules(now)
    } catch (error) {
      // 规则.md 读坏了不影响执法：照当前规则走
      console.error('[companion] 规则.md 同步失败：' + String((error && error.message) || error))
    }
    // B 层 T1：到期的预判在这里被墙的遥测撞一遍（verdict 只有这一步能写）。
    try {
      settlePredictions(now)
    } catch (error) {
      console.error('[companion] 预判结算失败：' + String((error && error.message) || error))
    }
    // A1：到期的立场同样在这一步结算（status/settleSource 只有 settleStances 能写）。
    try {
      settleStanceNow(now)
    } catch (error) {
      console.error('[companion] 立场结算失败：' + String((error && error.message) || error))
    }
    // A2：今天的时间表在这一步往前走——块开始自动立任务、块结束要求交墙，不靠她"想起来"。
    try {
      planSweepNow(now, tz)
    } catch (error) {
      console.error('[companion] 时间表推进失败：' + String((error && error.message) || error))
    }
    // 第六轮 B：早/晚/周末到点没结论，由墙补一条缺席记录（只记账，不加锁）。
    try {
      anchorSweep(now, tz)
    } catch (error) {
      console.error('[companion] 审查锚点补记失败：' + String((error && error.message) || error))
    }
    const sessionId = typeof body?.sessionId === 'string' && body.sessionId ? body.sessionId : config.sessionId
    if (!sessionId) return { v: 1, speak: false, reason: 'no-session', topic: null, nextCheckAt: now + 6 * 3_600_000 }
    const db = openMemoryDb(memoryPath())
    let candidates = { topics: [], facts: [], episodes: [] }
    try {
      candidates = loadCandidates(db, { sessionId, now })
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }
    const ledger = readLedger(home(), 400)
    const recent = summarize(ledger, startOfDay(now, tz))
    const silence = silenceState(ledger, { sessionId, now })
    // 手机每次问 /plan 都带上现状：水位决定节奏和力度，大脑顺带判上一球有没有把他拉回来。
    const phone = phoneOf(body?.phone)
    // 内核时钟滴答：推进到期的循环/周期/Cron 任务，并把全量任务表顺带返还原生 AlarmManager 对齐。
    try {
      clockKernel.tick(now)
    } catch {
      // ignore clock tick errors
    }
    const intensityNow = intensityStateNow(now, tz)
    const hardCapped = intensityNow.hardInterventions.capped
    const emergencyNow = activeEmergencyInfo(now, tz)
    const emergencyActiveInitial = Boolean(emergencyNow)
    // 行为雷达 0ms 先行判定：晨间开机直奔娱乐 / 纯游戏连玩 / 未收录短视频与信息流 / 租约偏航（静音/免打扰时段不越权抢跑）
    const radarAllowed = config.control !== false && body?.skipRadar !== true && !(Number(config.muteUntil) > now) && !inQuiet(now, config.quietStart, config.quietEnd, tz)
    const inJudgeGrace = Number(loadBrain()?.graceUntil) > now && !(refuseUntilAt(now) > now)
    const penaltyNow = loadPenalty(now, tz)
    const rawRadar = radarAllowed
      ? evalBehaviorRadar({
          phone,
          declaredApps: appsOf(),
          now,
          tz,
          morningPlanned: !planGateNow(now, tz),
          onBreak: onBreak(now),
          emergencyActive: emergencyActiveInitial,
          graceActive: inJudgeGrace,
          runningBout: runningBoutNow(),
          activeLease: config.taskLease && Number(config.taskLease.until) > now ? config.taskLease : null,
          funTotalToday: funToday(phone),
          penalty: penaltyNow,
          // 2026-11-18 切换次数限制：只在干正事时算，默认阈值来自强度表，规则.md 可覆盖。
          ...(() => {
            const contract = v4Hub?.contractSessionView?.(now) || null
            const inWork = effectiveGear(config, now) === 'busy' || Boolean(contract) || Boolean(runningBoutNow()) || Boolean(aiTask(config, now))
            const wc = wallConfig(config)
            const switchRule = {
              ...wc,
              ...(config.wall?.switchWindowMin == null ? { switchWindowMin: intensityNow.thresholds.switchWindowMin } : {}),
              ...(config.wall?.switchDiceAt == null ? { switchDiceAt: intensityNow.thresholds.switchDice } : {}),
              ...(config.wall?.switchLockAt == null ? { switchLockAt: intensityNow.thresholds.switchLock } : {}),
            }
            return {
              inWork,
              switchRule,
              workAllow: [...(Array.isArray(contract?.allow) ? contract.allow : []), ...(Array.isArray(config.defaultAllow) ? config.defaultAllow : [])],
            }
          })(),
        })
      : { triggered: false }
    const radar = rawRadar.triggered && rawRadar.kind === 'unplanned-nudge' &&
      now - (Number(loadGearState().radarNudgeAt) || 0) < 15 * 60_000
      ? { triggered: false }
      : rawRadar
    if (radar.triggered) {
      if (radar.kind === 'unplanned-nudge') {
        saveGearState({ ...loadGearState(), radarNudgeAt: now })
      }
      if (radar.kind === 'game-binge' || radar.kind === 'lease-drift' || radar.kind === 'morning-first-play') {
        if (now - (Number(penaltyNow.lastBreachAt) || 0) >= 10 * 60_000) {
          recordBreachPenalty(radar.kind === 'game-binge' || radar.kind === 'lease-drift' ? 'severe' : 'moderate', now, tz)
        }
      }
      if (radar.revokeLease) {
        record({ state: 'lease-drift', at: now, app: config.taskLease?.app || phone?.app || '', goal: config.taskLease?.goal || '' })
        commit({ taskLease: null })
      }
      if (!hardCapped && Array.isArray(radar.tempBlockApps) && radar.tempBlockApps.length) {
        const keep = (Array.isArray(config.blockExtra) ? config.blockExtra : []).filter((b) => b && Number(b.until) > now && !radar.tempBlockApps.includes(b.app))
        const until = now + Math.max(15, radar.refuseMinutes || 30) * 60_000
        commit({
          blockExtra: [...keep, ...radar.tempBlockApps.map((app) => ({ app, until, by: 'radar', why: radar.forceWhy }))].slice(-30),
        })
      }
      if (!hardCapped && radar.revokeLease && radar.refuseMinutes > 0) {
        commit({
          refuseUntil: Math.max(now + radar.refuseMinutes * 60_000, Number(config.refuseUntil) || 0),
          refuseBy: 'ai',
        })
      }
      if (!hardCapped && Number(radar.forceLevel) >= 3) {
        recordHardNow({ kind: 'lock', source: 'radar:' + radar.kind, app: phone?.app || '', why: radar.reason, now, tz, dedupeKey: 'radar:' + radar.kind + ':' + (phone?.app || '') })
      }
      record({ state: 'behavior-radar', at: now, kind: radar.kind, app: phone?.app || '', reason: radar.reason, downgradedByHardCap: hardCapped || undefined })
    }
    // 临时封禁名单里的 App 也算娱乐。
    if (phone?.app && blockedNow(now).includes(phone.app)) phone.fun = true
    const appGov = loadAppGov()
    const tierInfo = classifyAppTier(phone?.app, appsOf())
    if (tierInfo.tier === TIER_EXEMPT && phone) phone.fun = false
    if (tierInfo.tier === TIER_CONTEXTUAL && phone?.app && roleOf(appsOf(), phone.app) === 'work') {
      appGov.recentWorkAt = now
    }
    if (body?.recentScreenshotAt) appGov.recentScreenshotAt = Number(body.recentScreenshotAt) || appGov.recentScreenshotAt
    if (body?.recentShareAt) appGov.recentShareAt = Number(body.recentShareAt) || appGov.recentShareAt
    const exemptRes = evaluateContextualExemption(appGov.contextExempt, {
      pkg: phone?.app,
      phone,
      recentWorkAt: appGov.recentWorkAt,
      recentScreenshotAt: appGov.recentScreenshotAt,
      recentShareAt: appGov.recentShareAt,
      recentShareIntent: Boolean(body?.recentShareIntent),
      splitScreenVisible: Boolean(body?.splitScreenVisible),
      workVisible: Boolean(body?.workVisible),
      quickReturn: Boolean(body?.quickReturn),
      leftWorkAt: Number(body?.leftWorkAt) || 0,
      declaredApps: appsOf(),
    }, now)
    appGov.contextExempt = exemptRes.state
    if (exemptRes.exempt && phone) phone.fun = false
    const escapeRes = detectDegradedEscape(appGov.escape, {
      phone,
      declaredApps: appsOf(),
      now,
      contextExempt: Boolean(exemptRes.exempt),
    })
    const prevEscapeLogAt = Number(appGov.escape?.lastTriggerAt) || 0
    appGov.escape = escapeRes.nextState
    if (escapeRes.degraded && now - prevEscapeLogAt >= 2 * 60_000) {
      record({
        state: 'degraded-escape',
        at: now,
        app: phone?.app || '',
        reason: escapeRes.reason,
        distinctApps: escapeRes.distinctApps,
        dwellSeconds: escapeRes.dwellSeconds,
      })
    }
    saveAppGov(appGov)
    let extState = loadExternal()
    const pullBackRes = checkEmergencyPassPullBack(extState, { now, pkg: phone?.app })
    if (pullBackRes.pullBack) {
      extState = pullBackRes.state
      saveExternal(extState)
      record({ state: 'emergency-pass-pullback', at: now, expiredAt: pullBackRes.expiredAt, reason: pullBackRes.passReason, app: phone?.app || '' })
    }
    const leaseNow = activeAppLease(extState, now)
    const activeEmergency = activeEmergencyInfo(now, tz)
    const emergencyActive = Boolean(activeEmergency)
    const elasticRes = body?.elasticPlan ? evaluateElasticBuffer({ ...body.elasticPlan, now }) : null
    let schedulingFloor = null
    if (elasticRes?.exceeded) {
      record({ state: 'scheduling-failure', at: now, overdueMin: elasticRes.overdueMin, protocolId: elasticRes.fallbackBout?.protocolId })
      const dayStr = dayString(now, tz)
      const countToday = ledger.filter((e) => e?.state === 'scheduling-failure' && dayString(Number(e.at) || 0, tz) === dayStr).length + 1
      schedulingFloor = evaluateDailySchedulingFloor({ failureCountToday: countToday })
    }
    {
      const boutApps = [...(Array.isArray(live?.apps?.need) ? live.apps.need : []), ...(Array.isArray(live?.apps?.allow) ? live.apps.allow : [])]
      const inTargetApp = Boolean(phone?.app && !phone.fun && !phone.idle && (roleOf(appsOf(), phone.app) === 'work' || boutApps.includes(phone.app)))
      const wr = checkWithdrawalRelease(loadWithdrawal(), { isTargetApp: inTargetApp, now })
      if (wr.released) {
        saveWithdrawal(wr.state)
        record({ state: 'withdrawal-release', at: now, reason: wr.reason, app: phone?.app || undefined })
      } else if (inTargetApp && wr.state.streak === 0) {
        saveWithdrawal(wr.state)
      }
    }
    // 第六轮 C：暗思考。他不在、没发消息也照样按客观变化整理一次；只写账本，不产生任何要发出去的话。
    // 挂在这里是因为手机闹钟问 /plan 是唯一的“后台”触发面（见 features_api.dart）：不问就不跑，和 anchorSweep 同一限制。
    try {
      const mused = museSweep(ledger, phone, now, tz)
      if (mused) record(mused)
    } catch (error) {
      console.error('[companion] 暗思考失败：' + String((error && error.message) || error))
    }
    // 第七轮 B2：暗思考的第二层——夜间整理。午间/睡前各一次，对话中不跑。
    // 异步跑、不等它：/plan 是手机每次切 App 都要问的，绝不能被一次模型调用卡住。
    try {
      const nd = nightlyDue(ledger, now, tz, { busy: inConversation(sessionId, now) })
      if (nd.due && !nightlyRunning) {
        nightlyRunning = true
        runNightly(now, tz, nd.slot)
          .catch((error) => console.error('[companion] 夜间整理失败：' + String((error && error.message) || error)))
          .finally(() => { nightlyRunning = false })
      }
    } catch (error) {
      console.error('[companion] 夜间整理判定失败：' + String((error && error.message) || error))
    }
    // 2026-11-18 思考层：午间 13:30、晚间 22:15 各一次，对话中不跑；异步，不卡 /plan。
    try {
      const rslot = reflectSlot(zoneMinuteOfDay(now, tz))
      if (!reflectRunning && rslot >= 0 && !inConversation(sessionId, now) &&
        reflectDue(ledger, { day: dayString(now, tz), slot: rslot, dayOf: (t) => dayString(t, tz) })) {
        reflectRunning = true
        runReflectNow(now, tz, rslot)
          .catch((error) => console.error('[companion] 思考层失败：' + String((error && error.message) || error)))
          .finally(() => { reflectRunning = false })
      }
    } catch (error) {
      console.error('[companion] 思考层判定失败：' + String((error && error.message) || error))
    }
    // 第六轮 D：自建工作流到期处理。试启用 7 天没可数效果就停；转正的 30 天复核对不上同样停。
    // 和暗思考一样挂在 /plan 上——引擎里没有第二个"后台"面，不自建调度器。
    try {
      const wf = workflowSweep(config.workflows, ledger, now)
      if (wf.events.length) {
        config = { ...config, workflows: wf.next }
        try {
          writeConfig(config)
        } catch {
          // 下一轮再存：停用这件事比存盘重要，先按新状态跑
        }
        for (const ev of wf.events) {
          record({ state: 'workflow-' + ev.kind, at: ev.at, workflowId: ev.id, reason: ev.reason })
        }
      }
    } catch (error) {
      console.error('[companion] 自建工作流到期处理失败：' + String((error && error.message) || error))
    }
    const mind = sense(phone, ledger, sessionId, now)
    if (phone) {
      lastPhone = phone
      if (phone.lastIntercept && typeof phone.lastIntercept === 'object' && (phone.lastIntercept.pkg || phone.lastIntercept.app)) {
        recordLastIntercept(phone.lastIntercept, now)
      }
    } else if (body?.phone && Number.isFinite(Number(body.phone.geminiMinutesToday))) {
      lastPhone = { ...(lastPhone || { today: [] }), geminiMinutesToday: Math.max(0, Math.round(Number(body.phone.geminiMinutesToday))) }
    }
    // 第九轮：预案。手机一切 App 就会来问一次 /plan，所以严格模式能在这一问的回答里就落下去。
    let presetWatchUntil = 0
    try {
      const pr = presetRt.tick(phone, now)
      presetWatchUntil = Number(pr?.watchUntil) || 0
      // 真的落了锁（不是影子、不只是说一句）：行为大脑记一笔，接下来 5 分钟的片段前因是「刚被锁」。
      if (pr.fires.some((f) => !f.shadow && f.ops.some((o) => o !== 'say' && o !== 'log'))) {
        brainRt.noteLock(now)
        if (!hardCapped) {
          recordHardNow({ kind: 'lock', source: 'preset', app: phone?.app || '', why: '预案锁定', now, tz, dedupeKey: 'preset:' + (phone?.app || '') })
        }
        try {
          v4Hub?.recordStoryline?.({ kind: 'lock', actor: 'system', summary: `触发护栏锁定（${phone?.label || phone?.app || '娱乐'}）`, at: now }, now)
        } catch {}
      }
    } catch (error) {
      console.error('[companion] 预案失败：' + String((error && error.message) || error))
    }
    try {
      brainRt.tick(phone, now, { bout: runningBoutNow(), night: lastRhythm?.night === 'lock', todayFun: phone ? funToday(phone) : undefined })
    } catch (error) {
      console.error('[companion] 行为记录失败：' + String((error && error.message) || error))
    }
    // 作息：早上门、晚上门、深夜阶段（先算，力度和拒绝要用）。
    {
      const min = localMinute(phone, zoneMinuteOfDay(now, tz))
      const dayKey = startOfDay(now, tz)
      const reviewedToday = ledger.some((e) => e?.state === 'generated' && e.sessionId === config.strategistSessionId && e.reason === 'review' && Number(e.at) >= dayKey)
      const rh = trackRhythm(loadRhythm(), { min, day: dayKey, now, screenOn: phone?.screenOn !== false && Boolean(phone), reviewedToday, weekday: weekdayOf(now, tz) })
      saveRhythm(rh.state)
      lastRhythm = { ...rh, nightUntil: rh.night === 'lock' ? now + Math.max(1, NIGHT_LOCK_TO - min) * 60_000 : 0 }
      if (rh.openMorning) record({ state: 'gate-open', at: now, gate: 'morning' })
      if (rh.openReview) record({ state: 'gate-open', at: now, gate: 'review' })
      if (rh.openWeek) record({ state: 'gate-open', at: now, gate: 'week' })
      // 申请窗口：对账完成后随机开 15 分钟。先找今天对账是什么时候关门的（gate-closed 含 review）。
      let reviewDoneAt = 0
      for (const e of ledger) {
        if (e?.state === 'gate-closed' && Array.isArray(e.gates) && e.gates.includes('review') && Number(e.at) >= dayKey) {
          reviewDoneAt = Math.max(reviewDoneAt, Number(e.at))
        }
      }
      const aw = trackApplyWindow(config.applyWindow, { reviewDoneAt, now, tz })
      if (JSON.stringify(aw.state) !== JSON.stringify(config.applyWindow)) {
        config = { ...config, applyWindow: aw.state }
        try {
          writeConfig(config)
        } catch {
          // 下一轮再存
        }
      }
      if (aw.justOpened) record({ state: 'apply-window-open', at: now, closeAt: Number(aw.state.closeAt) })
      lastApplyWindow = aw
    }
    // 追问锁到点没裁决：结束拦截、记账扣分、挂义务（手机也会报 followup-timeout，谁先到都算）。
    expireFollowup(now)
    // 追问改道 Gemini 的那一次：有回执就结，10 分钟没回执落锁。
    tickFollowupGemini(now)
    // 封印：量证据、过档发奖励、到点没过保底进锁。
    try {
      sealTick(now, tz, phone)
    } catch (error) {
      console.error('[companion] 封印结算失败：' + String((error && error.message) || error))
    }
    try {
      healthDaily(now, tz)
    } catch {}
    // 超级验收者：恢复日判定、到点自动套模板。
    try {
      verifyTick(now, tz, phone)
    } catch (error) {
      console.error('[companion] 默认验收失败：' + String((error && error.message) || error))
    }
    // 待命 10 分钟计时器：只有换出待命才重置——聊天不算动。已经欠着一笔就不再追问，只留着账。
    if (stateView(now, tz, phone).state !== 'standby') {
      if (Number(config.standbySince)) commit({ standbySince: 0 })
    } else if (standbyDue(config.standbySince, now)) {
      const owed = (config.obligations || []).some((o) => o.kind === 'standby')
      commit({ standbySince: 0 })
      if (!owed) askViaGemini('standby', '这半天什么也没干。现在做哪一件？说一件，动手。', now)
    } else if (!Number(config.standbySince)) {
      commit({ standbySince: now })
    }
    // 冲刺到点 → 验收（追问锁，原因验收）。用户点的「开始」，到点由墙推进，不靠模型。
    if (Number(config.sprintUntil) > 0 && Number(config.sprintUntil) <= now) {
      commit({ sprintUntil: 0 })
      if (!config.followup || Number(config.followup.until) <= now) openFollowup('review', '这一轮到点了：做完了吗？拿完成物说话。', now)
    }
    const trust = trustScore(readLedger(home(), 800), now, { weights: trustWeights() })
    // 放风结束：看他回没回来（不在娱乐 App 里就算守约），记进信用分。
    if (Number(config.breakUntil) > 0 && Number(config.breakUntil) <= now && config.breakChecked === false) {
      config = { ...config, breakChecked: true }
      try {
        writeConfig(config)
      } catch {
        // ignore
      }
      record({ state: 'break-end', at: now, kept: !phone?.fun })
    }
    saveBrain(mind.brain)
    const water = mind.water
    const mode = effectiveMode(config, now)
    const limits = modeLimits(config, mode)
    const force = decideForce({
      phone, tide: water, mode, noTake: silence.noTake, control: config.control !== false, funToday: funToday(phone),
      grace: Number(mind.brain.graceUntil) > now,
      refuse: refuseUntilAt(now) > now,
    })
    seedFromMemory(garden, candidates, now)
    seedFromCommitments(garden, readCommitments(home(), now, 3), now)
    const topic = nextTopic(garden, {
      now,
      lastTopicId: recent.lastTopicId,
      lastAt: recent.lastAt,
      cooldownMs: config.cooldownMs,
      maxAttempts: MAX_ATTEMPTS,
    })
    persistGarden()
    const autoPre = v4Hub.computeAutonomy(now)
    const autoGapScale = (water.band === 'sink' || water.band === 'trap') ? 1.0 : v4Hub.autonomyGapScale(autoPre)
    const rawMinGap = lastRhythm?.night === 'late'
      ? Math.min(8 * 60_000, tideGap(limits.minGapMs, water.band))
      : tideGap(limits.minGapMs, water.band)
    const out = decide({
      // 教练模式更勤，伙伴更松；再按水位收紧：陷得越深越勤，间隔带随机，不让他摸出规律。
      // 在沉 / 陷住了就不掷骰子：该开口就开口。达标至 L2/L3 时按正向泄压系数放宽常规冷却。
      config: {
        ...config,
        ...limits,
        minGapMs: Math.round(rawMinGap * autoGapScale),
        hourlyMax: tideHourly(limits.hourlyMax, water.band),
        diceThreshold: water.band === 'sink' || water.band === 'trap' ? 100
          : water.band === 'slide' ? Math.max(80, Number(config.diceThreshold) || 0) : config.diceThreshold,
      },
      now,
      tz,
      topic,
      sentToday: capCount(ledger, startOfDay(now, tz)),
      sentLastHour: sentLastHour(ledger, now),
      lastAt: recent.lastAt,
      roll: body?.roll,
      silence,
      mutedUntil: Number(config.muteUntil) || 0,
      nextTopicAt: nextTopicAt(garden, now),
      free: true,
    })
    // 他正在跟大肥鱼聊：不发主动球（发了也只会撞上"正忙"）。力度照常给手机——在游戏里边玩边聊也得锁。
    // 姐姐什么时候主动开口：妹妹递了急话，或者晚上 20–22 点（当地）一天一次对账。
    // 安静时段 / 他说了别烦我 时都不说；其余时候不受妹妹的阶梯和骰子影响（她们是两个人）。
    nextSpeaker = null
    // 档位和 Gemini 连麦：报到、忙完迎接、连麦中途跑了叫回去、连完读回来——收集所有候选并由 speak_arbiter 统一裁决。
    const gearNow = effectiveGear(config, now)
    const prevGemSession = loadGemini().session
    const gem = trackGemini(loadGemini(), phone, now, geminiPackages())
    saveGemini(gem.state)
    if (prevGemSession && !prevGemSession.ended && gem.state?.session?.ended) {
      const endedAt = (Number(prevGemSession.until) > 0 && Number(prevGemSession.until) < now)
        ? Number(prevGemSession.until)
        : now
      saveGeminiGate(markGeminiSessionEnded(loadGeminiGate(now, tz), { now: endedAt, day: dayString(now, tz) }))
    }
    const canTalk = out.reason !== 'muted' && out.reason !== 'quiet' && out.reason !== 'disabled'
    const thinker = config.dual !== false && config.strategistSessionId ? 'strategist' : 'companion'
    let hold = null
    noteFront(phone, now)
    // 2026-11-18 推进器：此刻体系该推哪一步（代码定），谁来说、怎么做交给两条鱼。
    let driveRes = null
    try {
      const blockedDrive = emergencyActive || hardCapped || Boolean(lastRhythm?.night) || inQuiet(now, config.quietStart, config.quietEnd, tz) || gem.inside
      driveRes = driveNow(now, tz, phone, water, radar, blockedDrive)
    } catch (error) {
      console.error('[companion] 推进器失败：' + String((error && error.message) || error))
    }
    const driveBall = driveRes?.drive && driveRes.drive.kind !== 'auto-open'
      ? {
          role: driveRes.drive.role === 'strategist' && thinker === 'strategist' ? 'strategist' : 'companion',
          reason: 'drive',
          driveKind: driveRes.drive.kind,
          timepointKey: driveRes.drive.key,
          at: now,
          note: { kind: driveRes.drive.kind, key: driveRes.drive.key, text: driveRes.drive.text, mode: driveRes.mode },
        }
      : null

    // ---- H3 发球仲裁：收集所有候选 → 按 5 级优先级表 + 总节流选出一球 ----
    const speakCandidates = []
    if (lastRhythm?.night === 'lock' && phone?.screenOn && now - (Number(lastRhythm.state.nightBallAt) || 0) >= 30 * 60_000) {
      speakCandidates.push({ role: thinker, reason: 'night', priority: 1, at: now })
    }
    if (lastRhythm?.morningGate && !lastRhythm.state.morningBall) {
      speakCandidates.push({ role: thinker, reason: 'morning-gate', priority: 2, at: now, timepointKey: 'morning-gate:' + dayString(now, tz) })
    }
    if (driveBall && driveBall.note?.kind !== 'fill' && (gearNow !== 'busy' || driveBall.note.kind === 'takeover' || driveBall.note.kind === 'checkpoint')) {
      const dp = driveBall.note.kind === 'takeover' ? 1 : (driveBall.note.kind === 'checkpoint' || driveBall.note.kind === 'deadline' ? 2 : 3)
      speakCandidates.push({ ...driveBall, priority: dp })
    }
    const gs = loadGearState()
    if (gem.recap && config.proactiveTools !== false) {
      speakCandidates.push({
        role: thinker, reason: 'gemini-recap', priority: 4, at: now, mark: { recappedFor: Number(gem.state.inSince) },
        gemini: { minutes: Math.round((Number(gem.state.lastInAt) - Number(gem.state.inSince)) / 60_000), topic: gem.state.session?.topic },
      })
    }
    if (gem.left) {
      speakCandidates.push({
        role: 'companion', reason: 'gemini-left', priority: 4, at: now, mark: { nagAt: now },
        topic: gem.session.topic, left: Math.max(1, Math.round((gem.session.until - now) / 60_000)), app: phone?.label || phone?.app,
      })
    }
    if (gearNow === 'free' && restlessSignal(phone, now)) {
      speakCandidates.push({ role: 'companion', reason: 'restless', priority: 5, at: now, ...restlessSignal(phone, now) })
    }
    if (gearNow === 'busy') {
      hold = Math.max(now + 60_000, Number(config.gearUntil) || 0)
    } else {
      if (config.gear === 'busy' && Number(config.gearUntil) <= now && now - Number(config.gearUntil) < 2 * 3_600_000 &&
        Number(gs.doneFor) !== Number(config.gearUntil) && !lastRhythm?.reviewGate) {
        if (config.taskBy) {
          speakCandidates.push({ role: thinker, reason: 'task-due', priority: 2, at: now, timepointKey: 'task-due:' + Number(config.gearUntil), busy: { goal: config.taskGoal, what: config.gearWhat }, digest: true })
        } else {
          speakCandidates.push({ role: 'companion', reason: 'busy-done', priority: 2, at: now, timepointKey: 'busy-done:' + Number(config.gearUntil), what: config.gearWhat, digest: true, minutes: Math.round((Number(config.gearUntil) - Number(config.gearSince || config.gearUntil)) / 60_000) })
        }
      }
      if (gearNow === 'free' && config.proactiveTools !== false && phone?.screenOn && !lastRhythm?.night &&
        (!silence || silence.stage === 'none') && planDraftDue(now, tz)) {
        speakCandidates.push({ role: thinker, reason: 'plan-draft', priority: 3, at: now })
      }
      const patrol = gearNow === 'free' && config.proactiveTools !== false && !lastRhythm?.reviewGate && (!silence || silence.stage === 'none') ? patrolSignal(phone, now, tz) : null
      if (patrol) {
        speakCandidates.push({ role: thinker, reason: 'patrol', priority: 3, at: now, note: patrol })
      }
      if (gearNow === 'free' && config.proactiveTools !== false && chaseSignal(phone, now, tz)) {
        speakCandidates.push({ role: thinker, reason: 'notes-chase', priority: 5, at: now, note: chaseSignal(phone, now, tz) })
      }
    }
    if (!hold && config.dual !== false && config.strategistSessionId) {
      const handoff = pendingHandoff(now)
      const todayStart = startOfDay(now, tz)
      const reviewedToday = ledger.some((e) => e?.state === 'generated' && e.sessionId === config.strategistSessionId && Number(e.at) >= todayStart)
      if (handoff) {
        speakCandidates.push({ role: 'strategist', reason: 'handoff', outReason: 'strategist-handoff', priority: 2, note: handoff, at: now, timepointKey: 'handoff:' + dayString(now, tz) })
      }
      if (lastRhythm?.reviewGate && !lastRhythm.state.reviewBall && !reviewedToday) {
        speakCandidates.push({ role: 'strategist', reason: 'review', outReason: 'strategist-review', priority: 2, at: now, timepointKey: 'review:' + dayString(now, tz) })
      }
      if (lastApplyWindow?.justOpened) {
        speakCandidates.push({
          role: 'strategist', reason: 'apply-window', outReason: 'strategist-apply-window', priority: 2, at: now,
          timepointKey: 'apply-window:' + Number(lastApplyWindow.state?.openAt || now),
          note: { applyWindow: { remaining: relaxRemainingNow(now, tz), closeAt: Number(lastApplyWindow.state?.closeAt) || 0 } },
        })
      }
    }
    if (gem.inside) {
      const hasVoiceReport = body?.phone && Object.prototype.hasOwnProperty.call(body.phone, 'geminiVoiceActive')
      const inSince = Number(gem.state.inSince) || now
      if (hasVoiceReport && body.phone.geminiVoiceActive === false &&
        now - inSince >= 3 * 60_000 && now - (Number(gs.gemVoiceWarnAt) || 0) >= 8 * 60_000) {
        speakCandidates.push({ role: thinker, reason: 'gemini-voice-check', priority: 4, at: now })
      } else if (hasVoiceReport && body.phone.geminiVoiceActive === true && gem.session &&
        now - inSince >= 10 * 60_000 && now - (Number(gs.gemCheckpointAt) || inSince) >= 10 * 60_000) {
        speakCandidates.push({ role: thinker, reason: 'gemini-checkpoint', priority: 4, at: now, note: { topic: gem.session.topic || '' } })
      }
    }
    // 走神强拉（R4-6）：如果触发，不发这一球，等他在 Gemini 里交代
    const drift = config.control !== false && canTalk && !gem.inside && !emergencyActive && !hardCapped ? driftPullNow(now, phone, tz) : null
    if (radar.triggered && !(radar.forceLevel === 0 && silence?.stage && silence.stage !== 'none') && !drift) {
      const targetRole = radar.speaker === 'strategist' && config.dual !== false && config.strategistSessionId ? 'strategist' : 'companion'
      const withLock = Number(radar.forceLevel) >= 3 && !hardCapped
      speakCandidates.push({ role: targetRole, reason: radar.ballReason || 'radar-drift', priority: withLock ? 3 : 5, withLock, at: now, note: radar.forceWhy })
    }
    // 2026-11-21 两条鱼抽问到期复习卡：待命、亮屏、没在跑局、一天最多 2 次、隔 3 小时；只排期，不发分钟。
    if (canTalk && !gem.inside && !hold && !emergencyActive) {
      try {
        const rq = reviewQuizCandidate(now, tz, phone)
        if (rq) speakCandidates.push(rq)
      } catch {}
    }
    const restartTakeover = readState(stateFile('restart-takeover.json'), null)
    if (restartTakeover && !restartTakeover.handled && now - Number(restartTakeover.at) < 30 * 60_000) {
      speakCandidates.push({ role: 'companion', reason: 'restart-takeover', priority: 5, at: now, note: restartTakeover.note })
    }
    // 基础陪伴球（decide() 命中 / 晨间第一球 / no-take 兜底）与推进器 P5 fill
    if (!hold && !gem.inside) {
      if (out.speak) {
        speakCandidates.push({
          role: 'companion',
          reason: out.reason,
          outReason: out.reason,
          priority: 5,
          isBaseDecide: true,
          bypassThrottle: out.reason === 'ladder-lighter' || out.reason === 'ladder-channel',
          at: now,
        })
      }
      if (driveBall && driveBall.note?.kind === 'fill' && gearNow !== 'busy') {
        speakCandidates.push({ ...driveBall, priority: 5 })
      }
      if (!out.speak && (out.reason === 'dice' || out.reason === 'cooldown') && recent.counts.generated === 0 && phone?.screenOn && gearNow !== 'busy') {
        speakCandidates.push({ role: 'companion', reason: 'morning', outReason: 'morning', priority: 5, isBaseDecide: true, bypassThrottle: true, at: now })
      } else if (!out.speak && out.reason === 'no-take' && phone?.screenOn && worthObserving(phone) && gearNow !== 'busy' && now - (recent.lastAt || 0) >= 25 * 60_000) {
        speakCandidates.push({ role: 'companion', reason: 'observe-override', outReason: 'observe-override', priority: 5, isBaseDecide: true, at: now })
      }
    }

    desk.sweep()
    const wNow = loadWithdrawal()
    const inWithdrawalNow = isInWithdrawal(wNow, now)
    const dualOn = config.dual !== false && Boolean(config.strategistSessionId)
    const maxBallsPerHour = (mode === 'coach' && !config.intensityExplicit)
      ? Math.max(intensityNow.thresholds.maxBallsPerHour, limits.hourlyMax)
      : intensityNow.thresholds.maxBallsPerHour
    const arbiterMinGapMs = Number(config.minGapMs) === 0
      ? 0
      : Math.max(0, intensityNow.thresholds.partnerMinGapMin * 60_000)

    const arbRes = arbitrateSpeak({
      candidates: speakCandidates,
      now,
      canTalk: canTalk && !drift,
      inGemini: gem.inside,
      emergencyActive,
      inFlight: desk.inFlight(),
      inConversation: inConversation(sessionId, now),
      silence,
      inWithdrawal: inWithdrawalNow,
      dualOn,
      sentLastHour: sentLastHour(ledger, now),
      maxBallsPerHour,
      lastBallAt: recent.lastAt,
      minGapMs: arbiterMinGapMs,
      bypassedTimepoints: Array.isArray(gs.bypassedTimepoints) ? gs.bypassedTimepoints : [],
    })

    if (arbRes.candidates.length > 0) {
      record({
        state: 'speak-pick',
        at: now,
        picked: arbRes.picked?.reason || null,
        priority: arbRes.picked?.priority || null,
        role: arbRes.picked?.role || null,
        candidates: arbRes.candidates,
        throttled: arbRes.throttled,
        ...(arbRes.skipReason ? { skipReason: arbRes.skipReason } : {}),
      })
    }

    if (arbRes.picked) {
      const p = arbRes.picked
      if (p.priority === 2 && (silence.stage === 'stop' || silence.stage === 'wait' || inWithdrawalNow) && p.timepointKey) {
        const nextTp = [...(Array.isArray(gs.bypassedTimepoints) ? gs.bypassedTimepoints : []), p.timepointKey].slice(-30)
        saveGearState({ ...loadGearState(), bypassedTimepoints: nextTp })
      }
      if (p.reason === 'gemini-voice-check') saveGearState({ ...loadGearState(), gemVoiceWarnAt: now })
      if (p.reason === 'gemini-checkpoint') saveGearState({ ...loadGearState(), gemCheckpointAt: now })
      if (p.reason === 'review-quiz') markReviewQuiz(p, now, tz)
      if (p.reason === 'restart-takeover' && restartTakeover) writeJson(stateFile('restart-takeover.json'), { ...restartTakeover, handled: true })
      if (p.isBaseDecide) {
        nextSpeaker = null
        Object.assign(out, {
          speak: true,
          reason: p.outReason || p.reason,
          nextCheckAt: p.outReason === 'morning' ? now : (out.nextCheckAt || now + 60_000),
        })
      } else {
        nextSpeaker = p
        Object.assign(out, {
          speak: true,
          reason: p.outReason || p.reason,
          nextCheckAt: p.outReason ? now : (p.reason.startsWith('radar') ? now : now + 60_000),
        })
      }
    } else {
      nextSpeaker = null
      if (drift) {
        Object.assign(out, { speak: false, reason: 'gemini-drift' })
      } else if (gem.inside) {
        Object.assign(out, { speak: false, reason: 'in-gemini', nextCheckAt: now + 2 * 60_000 })
      } else if (hold) {
        Object.assign(out, { speak: false, reason: 'busy', nextCheckAt: Math.min(hold, Number(config.gearUntil) || hold) })
      } else if (arbRes.throttled) {
        Object.assign(out, {
          speak: false,
          reason: arbRes.skipReason === 'hourly-cap' ? 'hourly_max' : 'cooldown',
          nextCheckAt: now + 5 * 60_000,
        })
      } else if (out.speak || ['in-conversation', 'in-flight'].includes(arbRes.skipReason) ||
        (arbRes.candidates.length > 0 && arbRes.skipReason === 'withdrawal')) {
        // 正在聊 / 正在回：不管这一拍有没有候选球都照实说（深夜没有候选时原来会漏成 plan 的 cooldown，
        // 下一次检查也就晚了 5 分钟）。撤回期仍然只在有候选时才说。
        const fallbackReason = arbRes.skipReason === 'withdrawal' ? 'withdrawal' : (arbRes.skipReason || 'filtered')
        Object.assign(out, {
          speak: false,
          reason: fallbackReason,
          nextCheckAt: fallbackReason === 'withdrawal'
            ? Math.min(Number(wNow.personaCooldownUntil) || (now + 5 * 60_000), now + 5 * 60_000)
            : (fallbackReason === 'in-conversation' ? now + IN_CONVERSATION_MS : now + 60_000),
        })
      }
    }

    // 待命计时器在走：到 10 分钟要回来开锁，别等下一次定期检查。他喊了安静、或者已经三球没接要退避 3 小时，都不抢。
    const backoff = out.reason === 'muted' || out.reason === 'quiet' || out.reason === 'disabled' || out.reason === 'no-take'
    if (Number(config.standbySince) && !backoff) out.nextCheckAt = Math.min(Number(out.nextCheckAt) || Infinity, Number(config.standbySince) + STANDBY_LOCK_MS)
    // 力度到了"锁 / 赶"：不管这一球说不说，手机都要执行（说到做到）。
    if (force.level >= 3 && !hardCapped) out.nextCheckAt = Math.min(Number(out.nextCheckAt) || now, now + 60_000)
    // A5 热生效：插件推不动手机（手机是拉的一方）。改过预案或计划验收块的两分钟内让它 5 秒回来问一次，
    // 下一次问就拿到新的严格模式 / 锁 / 放行名单，不用等他退出重进，也不用等下一次定时醒。
    if (presetWatchUntil > now || dayPlanWatchUntil > now) out.nextCheckAt = Math.min(Number(out.nextCheckAt) || now + 5000, now + 5000)
    // 6.1：改过预案的这两分钟里手机 5 秒回来一次（上一行）；其余时候哪怕没什么可说，也得在
    // IDLE_POLL_MS 内回来看一眼预案——不然"改完等它下次醒"就是拿小时算。
    // 退避的四种理由（别烦我 / 安静时段 / 关掉 / 三球没接）照旧不抢：那是他明说别叫。
    if (!backoff && presetRt.list(now).presets.some((p) => p.status === '生效')) {
      out.nextCheckAt = Math.min(Number(out.nextCheckAt) || now, now + IDLE_POLL_MS)
    }

    const epUntil = emergencyActive ? activeEmergency.until : 0
    const v4ContractTick = v4Hub.onPlanTick(now)
    const dayWorkWallEnabled = config.dayWorkWallEnabled !== false && config.verifyMode?.enabled !== false
    const wdRaw = wallDecision(wallCfgNow(), now, tz)
    const nightLocked = Boolean(wdRaw.locked && wdRaw.kind === 'night')
    const contractOverdue = Boolean(v4ContractTick.forceStrictByContract)
    const spendPassActive = Boolean(config.spendPass && Number(config.spendPass.until) > now)
    const breakActive = onBreak(now) && !nightLocked
    const breakPassObj = breakActive
      ? {
          active: true,
          until: Number(config.breakUntil) || 0,
          by: String(config.breakBy || 'sister'),
          reason: String(config.breakReason || '双鱼审核放风中'),
        }
      : null
    const wd = ((!dayWorkWallEnabled || spendPassActive || breakActive) && !nightLocked && (!contractOverdue || breakActive) && wdRaw.kind !== 'night')
      ? { ...wdRaw, locked: false, kind: null, openUntil: Math.max(Number(wdRaw.openUntil) || 0, Number(config.spendPass?.until) || 0, breakActive ? (Number(config.breakUntil) || 0) : 0) }
      : wdRaw
    const reverseSess = reverseCapsuleSessionView(now)
    const dayKeyV6 = dayString(now, tz)
    const modNowV6 = localMinute(phone, zoneMinuteOfDay(now, tz))
    let v6StateNow = null
    let v6CadenceTick = null
    try {
      v6StateNow = loadV6(now)
      v6CadenceTick = v6TickCadence(v6StateNow.cadence, now, dayKeyV6)
      const cadMoved = v6CadenceTick.cadence.phase !== v6StateNow.cadence.phase
        || v6CadenceTick.cadence.lastPromptAt !== v6StateNow.cadence.lastPromptAt
        || v6CadenceTick.cadence.day !== v6StateNow.cadence.day
      if (cadMoved) {
        v6StateNow.cadence = v6CadenceTick.cadence
        saveV6(v6StateNow)
      }
    } catch {}
    const v6Cadence = v6CadenceTick?.cadence || null
    const v6CadenceActive = Boolean(v6Cadence?.active)
    const v6CadenceOverdue = Boolean(v6CadenceActive && v6Cadence.phase === 'awaiting_verify')
    const v6CadenceRunning = Boolean(v6CadenceActive && v6Cadence.phase === 'running')

    const liveCorePre = loadLiveCoreState()
    const liveKeeperOn = Boolean(liveCorePre?.enabled !== false && liveCorePre?.alwaysOnLive && liveCorePre?.keeperStrict !== false)
    const liveOnlineNow = Boolean(phone?.geminiVoiceActive || phone?.liveSignals?.liveOnline)
    const liveDropGraceMs = Math.max(15, Number(v6StateNow?.framework?.liveDropGraceSec) || 90) * 1000
    const lastVoiceAt = Math.max(0, Number(liveCorePre?.lastVoiceActiveAt) || 0)
    const liveInGraceWindow = !liveOnlineNow && lastVoiceAt > 0 && (now - lastVoiceAt) < liveDropGraceMs
    const inGeminiNowPre = inGemini(phone, geminiPackages()) || Boolean(geminiSessionView(now))
    const tzMealPre = Boolean(mealNow(wallConfig(config).meals, modNowV6))
    const tzQuietPre = inQuiet(now, config.quietStart, config.quietEnd, tz)
    const liveTextGraceActive = Number(liveCorePre?.textGraceUntil) > now
    const liveKeeperDropLock = Boolean(
      liveKeeperOn
      && !emergencyActive && !breakActive && !nightLocked && !tzMealPre && !tzQuietPre
      && !liveOnlineNow && !liveInGraceWindow && !inGeminiNowPre && !liveTextGraceActive
      && !isRunningQuizNow(now, phone, ledger)
      && String(liveCorePre?.energyTier || 'L2').toUpperCase() !== 'L0',
    )
    if (v6CadenceRunning && v6Cadence.dueAt > now) {
      out.nextCheckAt = Math.min(Number(out.nextCheckAt) || Infinity, v6Cadence.dueAt)
    } else if (v6CadenceOverdue) {
      out.nextCheckAt = Math.min(Number(out.nextCheckAt) || Infinity, now + 30_000)
    } else if (liveKeeperOn && !liveOnlineNow && liveInGraceWindow) {
      out.nextCheckAt = Math.min(Number(out.nextCheckAt) || Infinity, lastVoiceAt + liveDropGraceMs)
    }

    const verifyLockActive = Boolean(
      contractOverdue
      || v6CadenceOverdue
      || v4Hub?.dayBlockOverdueSessionView?.(now)
      || v4Hub?.contractSessionView?.(now, { escalatedOnly: true }),
    )
    const hardCapDowngrade = hardCapped && !nightLocked && !contractOverdue && !verifyLockActive && !reverseSess && !v6CadenceRunning && !liveKeeperDropLock

    const effectiveForceLevel = (emergencyActive || breakActive || hardCapDowngrade)
      ? 0
      : Math.max(force.level, radar.triggered ? radar.forceLevel : 0, (contractOverdue || v6CadenceOverdue || liveKeeperDropLock) ? 4 : 0)
    const effectiveForceWhy = v6CadenceOverdue
      ? `5分钟高频严验收已到点（第 ${v6Cadence.round} 轮：${v6Cadence.target}），请交出有效结论或连答 3 题解锁`
      : (liveKeeperDropLock
          ? '常驻 Gemini Live 护航已断线超缓冲期，锁定其余应用直至重新进入 Live'
          : (contractOverdue
              ? '计划模态检查点已超 1 分钟缓冲未验收，自动转为严格锁定'
              : (hardCapDowngrade
                  ? '今天硬干预已经用满，改为温和提醒'
                  : (radar.triggered ? (radar.forceWhy || force.why) : force.why))))
    const mediaAction = !emergencyActive && !breakActive && !hardCapDowngrade && effectiveForceLevel >= 3 && phone?.fun && phone?.mediaPlaying ? 'pause' : undefined
    const speakerOut = nextSpeaker?.role === 'strategist' ? 'strategist' : 'companion'
    const takeoverActiveSession = !hardCapDowngrade && driveRes?.takeover?.active
      ? {
          allow: takeoverAllow(),
          until: Number(config.aiWorkSession?.until) || (now + (intensityNow.thresholds.takeoverMaxMin || 45) * 60_000),
          why: '接管：' + String(driveRes.takeover.reason || '回到正事'),
        }
      : null
    const verifySessNow = !emergencyActive ? verifySessionView(now) : null
    // 2026-11-23 硬干预上限只管「系统主动的打扰」（接管、雷达 / 姐妹力度、自动拉 Gemini）。
    // 他自己选的锁——局（温和 / 严格）、深夜、默认验收、验收点保底、晚间上限、连麦舱、墙——用满了也照锁。
    // 以前用满后把墙（含深夜）和他自己开的严格局一起降成「只提醒」：白天被折腾满 12 次，晚上开局就锁不住。
    let lockArbiter = v4Hub.computeLockArbiter(now, {
      breakPass: breakPassObj,
      autonomy: v4ContractTick.autonomy,
      wall: { locked: (emergencyActive || breakActive) ? false : wd.locked, kind: (emergencyActive || breakActive) ? null : (wd.kind || null) },
      takeoverSession: takeoverActiveSession,
      ...(hardCapDowngrade ? { takeoverSession: null, ...(config.aiWorkSession?.by === 'takeover' ? { aiWorkSession: null } : {}) } : {}),
    })
    if (v6CadenceOverdue && !emergencyActive && !breakActive && !nightLocked && !['P0', 'P1'].includes(lockArbiter?.priority)) {
      lockArbiter = {
        active: true,
        priority: 'P3',
        source: 'CONTRACT_OVERDUE_STRICT',
        by: 'v6_cadence_overdue',
        label: `5分钟严验收第 ${v6Cadence.round} 轮待交付`,
        why: `已到点未验收：${v6Cadence.target}（逾期 ${v6CadenceTick?.overdueSec || 0} 秒）`,
        allow: Array.isArray(v6Cadence.allowApps) ? v6Cadence.allowApps : [],
        unlockHow: '在大肥鱼交出至少 6 字具体结论，或连续答对 3 题解锁并开启下一轮',
      }
    } else if (liveKeeperDropLock && !['P0', 'P1', 'P2', 'P3'].includes(lockArbiter?.priority)) {
      lockArbiter = {
        active: true,
        priority: 'P2',
        source: 'GEMINI_LIVE_CELL',
        by: 'live_keeper_drop',
        label: '常驻 Live 断线强制回连锁',
        why: '常驻 Gemini Live 护航已开启，但检测到语音/投影共享均已掉线，已锁定非相关应用',
        allow: [],
        unlockHow: '重新开启 Gemini Live 通话或共享屏幕，或在图谱页切至 L0 闭关/文字缓冲',
      }
    } else if (v6CadenceRunning && !emergencyActive && !breakActive && !['P0', 'P1', 'P2', 'P3', 'P3.5', 'P4'].includes(lockArbiter?.priority)) {
      lockArbiter = {
        active: true,
        priority: 'P4',
        source: 'BOUT_STRICT',
        subSource: 'BOUT_STRICT',
        by: 'v6_cadence_running',
        label: `5分钟高频严局第 ${v6Cadence.round} 轮`,
        why: `专注推进：${v6Cadence.target}（距本轮验收还剩 ${Math.max(1, Math.ceil((v6Cadence.dueAt - now) / 60_000))} 分钟）`,
        allow: Array.isArray(v6Cadence.allowApps) ? v6Cadence.allowApps : [],
        unlockHow: '到点交付真实推进成果，或手动收局解锁',
      }
    }
    const liveKeepAliveUnlockNow = Boolean(
      v6StateNow?.framework?.liveKeepAliveUnlock
      && liveOnlineNow
      && !nightLocked
      && !emergencyActive
      && !v6CadenceOverdue,
    )
    if (liveKeepAliveUnlockNow && lockArbiter && !['P0', 'P1'].includes(lockArbiter.priority)) {
      lockArbiter = {
        active: false,
        priority: 'P2',
        source: 'GEMINI_LIVE_KEEPALIVE_UNLOCK',
        label: 'Gemini Live 在线常驻免锁中',
        why: 'Live 硬件状态在线（通话/投影共享活跃），保持自由低阻执行',
        allow: ['*'],
        unlockHow: 'Live 断线超过缓冲期后自动切回严格回连锁',
      }
    }
    if (reverseSess && (!lockArbiter || !['P0', 'P1', 'P2', 'P3'].includes(lockArbiter.priority)) && !liveKeepAliveUnlockNow) {
      lockArbiter = {
        ...(lockArbiter || {}),
        active: true,
        priority: lockArbiter?.priority || 'P4',
        source: 'REVERSE_CAPSULE',
        mode: reverseSess.mode,
        scopeKey: reverseSess.scopeKey,
        allow: [...new Set(reverseSess.allow)],
        label: `反向单开舱（${reverseSess.mode === 'gemini_only' ? '只留 Gemini' : (reverseSess.mode === 'quiz_only' ? '纯刷题舱' : '定制舱')}）`,
        why: reverseSess.why,
        unlockHow: '完成本轮胶囊或手动收局解锁',
      }
    }
    const keepOnHardCap = lockArbiter?.source !== 'TAKEOVER_STRICT'
      || lockArbiter?.source === 'REVERSE_CAPSULE'
      || lockArbiter?.priority === 'P3'
      || v6CadenceOverdue
      || v6CadenceRunning
      || liveKeeperDropLock
    if (hardCapDowngrade && !keepOnHardCap && !liveKeepAliveUnlockNow) {
      lockArbiter = {
        active: false,
        priority: 'P6',
        source: 'HARD_CAP_DOWNGRADE',
        label: '今天硬干预已经用满（仅提醒）',
        why: '今天硬干预已经用满，余下时段只保留深夜锁和契约逾期锁',
        allow: ['*'],
        unlockHow: '明日零点自动重置',
      }
    }
    lastLockActive = Boolean(lockArbiter?.active)
    const wantReleaseNow = emergencyActive || breakActive || hardCapDowngrade || liveKeepAliveUnlockNow || (!v6CadenceOverdue && !liveKeeperDropLock && !contractOverdue && !radar.triggered && (onBreak(now) || (!force.refuse && Number(mind.brain.graceUntil) > now)))
    const forceLockedNow = !emergencyActive && !breakActive && !hardCapDowngrade && !liveKeepAliveUnlockNow && effectiveForceLevel >= 3
    const arbiterLockedNow = !emergencyActive && !breakActive && !liveKeepAliveUnlockNow && Boolean(lockArbiter?.active)
    const forceJustReleased = prevForceLocked && !forceLockedNow
    const arbiterJustReleased = prevArbiterLocked && !arbiterLockedNow
    const effectiveForceRelease = wantReleaseNow && (forceJustReleased || arbiterJustReleased)
    prevForceLocked = forceLockedNow
    prevArbiterLocked = arbiterLockedNow
    const activeSisterSkin = resolveActiveSisterSkin({ speaker: speakerOut, lockArbiter })
    const timeLedger = timeLedgerNow(now, tz, phone)
    // 2026-11-18：裁决器的唯一输出（手机先影子对比，确认没问题再切成唯一执法依据）。
    let lockDecision = null
    try {
      const effectiveLockInput = emergencyActive
        ? { priority: 'P0', source: 'EMERGENCY_SUSPEND', label: '紧急放行中', why: activeEmergency.reason || '紧急放行中', unlockHow: '倒计时结束后恢复' }
        : (liveKeepAliveUnlockNow ? { ...lockArbiter, priority: 'P6' } : lockArbiter)
      lockDecision = lockDecisionNow(now, tz, phone, effectiveLockInput, extState)
      if (liveKeepAliveUnlockNow && lockDecision) {
        lockDecision = {
          ...lockDecision,
          priority: 'P2',
          source: 'GEMINI_LIVE_KEEPALIVE_UNLOCK',
          label: 'Gemini Live 在线常驻免锁中',
          mode: 'free',
          allow: [],
          deny: [],
          needPass: [],
          why: lockArbiter.why,
          unlockHow: lockArbiter.unlockHow,
        }
      } else if (hardCapDowngrade && !keepOnHardCap && lockDecision) {
        lockDecision = { ...lockDecision, mode: 'free', deny: [], needPass: [], why: '今天硬干预已经用满（仅提醒）', unlockHow: '明日零点自动重置' }
      }
    } catch (error) {
      console.error('[companion] lock 裁决失败：' + String((error && error.message) || error))
    }
    const geminiMinutesToday = v4Hub.getGeminiStats(dayString(now, tz), now).minutesToday
    let geminiAutoPull = v4Hub.consumePendingAutoPull()
    // 2026-11-20 每一拍只做一件事：推进器这一拍已经开了局（自动开 / 冷启动），就不再拉 Gemini。
    const openedThisTick = ['auto-open', 'cold-start'].includes(driveRes?.drive?.kind)
    // 追问改道 Gemini 排着的那一次（geminiFirst 已经过了闸门）。
    if (!geminiAutoPull && pendingFollowupPull && !openedThisTick) {
      geminiAutoPull = pendingFollowupPull
      pendingFollowupPull = null
    }
    if (!geminiAutoPull && !openedThisTick) {
      const pullMode = v4Hub.getPullMode(now)
      const mealNowVal = mealNow(wallConfig(config).meals, localMinute(phone, zoneMinuteOfDay(now, tz)))
      const boutNow = Boolean(runningBoutNow())
      const inActiveWork = effectiveGear(config, now) === 'busy' || Boolean(v4ContractTick.contract) || boutNow
      const dwellMin = Math.max(0, Number(phone?.minutes) || 0)
      const switchPull = radar.triggered && radar.pullGemini === true
      const dwellPull = phone?.fun && dwellMin >= 8 && inActiveWork
      // 冷启动：今天还没开局、也没排局，空转够了——拉 Gemini 帮他定第一局（plan 口吻）。
      const ignitePull = driveRes?.ignite?.action === 'gemini' ? driveRes.ignite : null
      const canAttemptAutoPull =
        config.control !== false &&
        canTalk &&
        !gem.inside &&
        !mealNowVal &&
        !onBreak(now) &&
        phone?.screenOn !== false
      if (canAttemptAutoPull && (drift || switchPull || dwellPull || ignitePull)) {
        // 口吻按来源：冷启动 → plan；局里来回切 → think（卡住了，帮他拆）；摸鱼被抓 → interrogate。
        const pullSource = ignitePull ? 'cold-start' : (drift ? 'drift' : (switchPull ? (boutNow ? 'bout-switch' : 'switch-limit') : 'fun-dwell-8m'))
        const pullGeminiMode = ignitePull ? 'plan' : (switchPull && boutNow ? 'think' : 'interrogate')
        const pullTopic = ignitePull ? ignitePull.topic : (drift?.topic || (switchPull ? `你在干正事时 ${radar.reason}，用骰子把注意力拉回这一局` : `你在${phone?.label || phone?.app || '娱乐应用'}已停留 ${dwellMin} 分钟，立刻对齐下一步主线`))
        let pullBrief = ignitePull ? '' : (drift?.brief || '')
        if (switchPull && !pullBrief && !ignitePull) {
          try {
            pullBrief = String(diceRollRoute({ manual: false, topic: '' })?.geminiPrompt || '')
          } catch {
            pullBrief = ''
          }
        }
        if (!pullBrief) {
          pullBrief = v4Hub.buildProactiveGeminiBrief({
            topic: pullTopic,
            mode: pullGeminiMode,
            minutes: 15,
            now,
          })
        }
        // 2026-11-20 单一路径：drift 也走闸门（以前直接放行，不吃统一的冷却和每日上限）。
        const gateRes = requestGeminiViaGate({
              source: pullSource,
              mode: 'auto',
              topic: pullTopic,
              minutes: 15,
              geminiMode: pullGeminiMode,
              brief: pullBrief,
              now,
              tz,
              pullMode,
              emergencyActive,
              isNight: Boolean(lastRhythm?.night),
              isQuiet: inQuiet(now, config.quietStart, config.quietEnd, tz),
              hardCapped,
              sessionActive: gem.inside || Boolean(gem.session),
            })
        if (gateRes.allowed) {
          v4Hub.markStoryAutoPulled(now, `触发主动拉起 Gemini（${phone?.label || phone?.app || '分心'} ${dwellMin}m）`)
          geminiAutoPull = {
            triggered: true,
            at: now,
            topic: pullTopic,
            mode: pullMode,
            reason: pullSource,
            preferVoice: true,
            brief: gateRes.brief || pullBrief,
          }
        }
      }
      if (!geminiAutoPull && canAttemptAutoPull && !emergencyActive && !hardCapped && phone?.app) {
        try {
          const tfNow = tierFnNow()
          const modNow = localMinute(phone, zoneMinuteOfDay(now, tz))
          const isNightNow = Boolean(lastRhythm?.night)
          const isQuietNow = inQuiet(now, config.quietStart, config.quietEnd, tz)
          const switchesRecent = Array.isArray(phone?.switches?.recent) ? phone.switches.recent : []
          const funSwitches5m = switchesRecent.filter((sw) => {
            const atMs = Number(sw?.at) || 0
            if (atMs > 0 && now - atMs > 5 * 60_000) return false
            const pkg = String(sw?.app || sw?.to || '')
            return pkg && ['fun', 'game'].includes(tfNow(pkg, sw?.label || ''))
          }).length
          const lockBounceCount = ledger.filter((e) => (
            now - Number(e?.at || 0) <= 15 * 60_000 &&
            (e?.state === 'intercept-hit' || e?.state === 'blocked-open')
          )).length
          const justFinishedBout = Boolean(driveRes?.lastBoutEndAt && now - Number(driveRes.lastBoutEndAt) <= 15 * 60_000) ||
            ledger.some((e) => e?.state === 'bout-closed' && now - Number(e?.at || 0) <= 10 * 60_000)
          const lastInterceptNow = loadLastIntercept()
          const trig = evaluateProactiveGeminiTriggers(
            loadGeminiGate(now, tz),
            {
              minuteOfDay: modNow,
              isNight: isNightNow,
              hasActiveBout: boutNow || Boolean(v4ContractTick.contract),
              funDwellMin: phone?.fun ? dwellMin : 0,
              funSwitches5m,
              lockBounceCount,
              justFinishedBout,
              boutRecapped: Boolean(gem?.recap),
              eveningClosed: Boolean(v4ContractTick.eveningReportGenerated),
              lastBlockedApp: lastInterceptNow?.label || lastInterceptNow?.pkg || '',
              lastBlockedWhy: lastInterceptNow?.reason || '',
              front: phone?.label || phone?.app || '',
              frontMinutes: dwellMin,
              funMinutesToday: Number(timeLedger?.funUsed) || 0,
              owner: String(config.owner || '他'),
              kbDirPath: (() => { try { const d = notesDir(); return d ? kbDir(d, home()) : '' } catch { return '' } })(),
              commitments: readCommitments(home(), now, 3).map((c) => c.statement),
              bridge: bridgeHealthy(now),
            },
            {
              now,
              tz,
              day: dayString(now, tz),
              dailyCap: intensityNow.thresholds.geminiAutoMaxPerDay,
              pullMode,
              emergencyActive,
              isNight: isNightNow,
              isQuiet: isQuietNow,
              hardCapped,
              sessionActive: gem.inside || Boolean(gem.session),
            },
          )
          // 轻度破冰与晚间压卡仅在两条鱼本次未主动发声时补位；唯独连续撞锁（>=2次）才可在无打架前提出卡点诊断
          const allowTriggerNow = !out.speak || trig.scenario === 'lock-bounce'
          if (trig.triggered && trig.state && allowTriggerNow) {
            saveGeminiGate(trig.state)
            recordHardNow({
              kind: 'auto_gemini',
              source: 'gemini:' + trig.scenario,
              why: trig.packet?.topic || trig.scenario,
              now,
              tz,
              dedupeKey: 'gem:' + trig.scenario + ':' + Math.floor(now / 60_000),
            })
            v4Hub.markStoryAutoPulled(now, trig.packet?.title || trig.scenario)
            geminiAutoPull = {
              triggered: true,
              nonIntrusive: true,
              at: now,
              scenario: trig.scenario,
              topic: trig.packet?.topic || trig.scenario,
              title: trig.packet?.title || trig.scenario,
              subtitle: trig.packet?.subtitle || '',
              mode: pullMode,
              reason: trig.scenario,
              preferVoice: true,
              canSnooze: true,
              brief: trig.brief || '',
            }
          }
        } catch (error) {
          console.error('[companion] Gemini 主动触发矩阵评估失败：' + String((error && error.message) || error))
        }
      }
    }
    // 2026-11-20 每一拍只做一件事：硬拉起 Gemini 时不再发双鱼球；非强抢提醒卡不抢走既有发声权。
    if (geminiAutoPull?.triggered && !geminiAutoPull.nonIntrusive && out.speak) {
      nextSpeaker = null
      Object.assign(out, { speak: false, reason: 'gemini-pull' })
    }
    const intensityFinal = intensityStateNow(now, tz, v4ContractTick.autonomy)
    const passLedger = passLedgerSummary(loadPassLedger(now, tz), {
      day: dayString(now, tz),
      cap: intensityFinal.emergencyDisarmDailyCap,
      now,
    })
    const liveBoutId = String(live?.official?.view?.()?.boutId || '')
    const liveSealId = String(live?.sealId || '')
    const rawSessionOut = (emergencyActive || breakActive) ? null : (hardCapDowngrade ? verifySessNow : sessionNow(now))
    const sessionOut = rawSessionOut
      ? {
          ...rawSessionOut,
          ...(liveBoutId && !rawSessionOut.boutId ? { boutId: liveBoutId } : {}),
          ...(liveSealId && !rawSessionOut.sealId ? { sealId: liveSealId } : {}),
        }
      : null
    const lockArbiterOut = (liveBoutId || liveSealId)
      ? { ...lockArbiter, ...(liveBoutId ? { boutId: liveBoutId } : {}), ...(liveSealId ? { sealId: liveSealId } : {}) }
      : lockArbiter
    const modNowTop = localMinute(phone, zoneMinuteOfDay(now, tz))
    const tzMealTop = Boolean(mealNow(wallConfig(config).meals, modNowTop))
    const tzQuietTop = inQuiet(now, config.quietStart, config.quietEnd, tz)
    const inGeminiTop = inGemini(phone, geminiPackages()) || Boolean(geminiSessionView(now))
    const planModal = v4Hub.evaluatePlanModalNow(now, {
      day: dayString(now, tz),
      minuteOfDay: modNowTop,
      inGemini: inGeminiTop,
      emergencyActive,
      isNight: nightLocked || Boolean(lastRhythm?.night),
      isMeal: tzMealTop,
      isQuiet: tzQuietTop,
    })
    let liveCoreRes = null
    try {
      liveCoreRes = evaluateLiveCoreNow(now, {
        phone,
        tz,
        arbiter: lockArbiter,
        planModal,
        mealOrQuiet: tzMealTop || tzQuietTop,
        inGemini: inGeminiTop,
        emergencyActive,
        runningQuiz: isRunningQuizNow(now, phone, ledger),
        ledger,
        commit: body?.liveCoreCheck !== false,
      })
      if (liveCoreRes?.triggered && !geminiAutoPull && !openedThisTick && !hardCapped && v4Hub.getPullMode(now) !== 'gentle') {
        geminiAutoPull = {
          triggered: true,
          at: now,
          topic: liveCoreRes.topic,
          mode: liveCoreRes.pullMode || 'instant',
          reason: liveCoreRes.reason,
          urgent: Boolean(liveCoreRes.urgent),
          voiceOnly: Boolean(liveCoreRes.voiceOnly),
          playChime: Boolean(liveCoreRes.playChime),
          pulseIntervalMin: Number(liveCoreRes.nextState?.pulseIntervalMin || 0),
          alwaysOnLive: Boolean(liveCoreRes.nextState?.alwaysOnLive),
          earlyBailout: liveCoreRes.reason === 'live_early_bailout',
          preferVoice: true,
          autoClickLive: liveCoreRes.autoClickLive !== false,
          requestScreenShare: Boolean(liveCoreRes.requestScreenShare),
          force: Boolean(liveCoreRes.urgent || liveCoreRes.nextState?.alwaysOnLive),
          voiceLine: liveCoreRes.voiceLine,
          brief: liveCoreRes.brief,
          dice: liveCoreRes.dice,
        }
      }
    } catch (error) {
      console.error('[companion] live_core 调度失败：' + String((error && error.message) || error))
    }
    const liveCoreOut = liveCoreSummary(now, liveCoreRes)
    let superSpark = null
    const ssState = loadSuperSparkState()
    const ssIntervalDue = !ssState.lastSparkAt || (now - ssState.lastSparkAt >= SUPER_SPARK_DEFAULTS.autoCooldownMs)
    const ssAwakeWork = modNowTop >= 8 * 60 && modNowTop <= 22 * 60
    const ssNotBlocked = !emergencyActive
      && !nightLocked
      && !lastRhythm?.night
      && !tzMealTop
      && !tzQuietTop
      && !inGeminiTop
      && !planModal?.active
      && !(Number(config.muteUntil) > now)
      && !(Number(ssState.mutedUntil) > now)
    const ssNotFocused = !runningBoutNow() && !reverseSess
    const hasDoneToday = (Number(timeLedger?.earnedBout) || 0) > 0 || Boolean(v4Hub?.getDayVerifySummary?.(now)?.passedCount)
    const ssLowOutput = Boolean(phone?.fun || Number(phone?.minutes || 0) >= 3 || !hasDoneToday)
    const ssQuietForLive = liveCoreOut?.energyTier === 'L0' || Boolean(liveCoreOut?.alwaysOnLive)
    if (ssAwakeWork && ssNotBlocked && ssNotFocused && ssIntervalDue && ssLowOutput && !ssQuietForLive) {
      const cand = buildSuperSparkNow(now, { phone, idleMinutes: Number(phone?.minutes || 0) })
      if (cand?.triggered) superSpark = cand
    }
    const v6Snap = (() => {
      try {
        const s = v6StateNow || loadV6(now)
        const tel = v6ComputePoolTelemetry(s.pool, now)
        const win = v6EvaluateWindowMode(s.framework, modNowTop)
        const cadTick = v6CadenceTick || v6TickCadence(s.cadence, now, dayKeyV6)
        const dueAlert = Boolean(cadTick.dueAlert && !emergencyActive && !nightLocked)
        const alertVoiceLine = dueAlert
          ? `五分钟严格验收时间到！第${cadTick.cadence.round}轮目标：${cadTick.cadence.target}。请立即交付具体结论，或者连答三题解锁。`
          : ''
        return {
          framework: s.framework,
          windowMode: win,
          poolActive: tel.active,
          liveUnlockedNow: liveKeepAliveUnlockNow,
          liveKeeperDropLock,
          cadence: cadTick.cadence,
          cadenceRemainingSec: cadTick.remainingSec,
          cadenceOverdueSec: cadTick.overdueSec,
          cadenceAlert: dueAlert
            ? {
                triggered: true,
                round: cadTick.cadence.round,
                target: cadTick.cadence.target,
                overdueSec: cadTick.overdueSec,
                voiceLine: alertVoiceLine,
              }
            : null,
        }
      } catch {
        return null
      }
    })()
    return {
      v: 1, ...out, silence, sessionId, speaker: speakerOut,
      planModal,
      liveCore: liveCoreOut,
      ...(v6Snap ? { v6: v6Snap } : {}),
      ...(superSpark ? { superSpark } : {}),
      boutId: liveBoutId,
      sealId: liveSealId,
      ...(liveBoutId ? { bout: { id: liveBoutId, sealId: liveSealId } } : {}),
      arbiterEnforce: config.arbiterEnforce !== false,
      intensity: intensityFinal.intensity,
      effectiveIntensity: intensityFinal.effectiveIntensity,
      autonomyShift: intensityFinal.autonomyShift,
      pendingIntensity: intensityFinal.pendingIntensity,
      emergencyDisarmDailyCap: intensityFinal.emergencyDisarmDailyCap,
      hardInterventions: intensityFinal.hardInterventions,
      passLedger,
      timeLedger,
      ...(arbRes.candidates.length ? { speakPick: { picked: arbRes.picked?.reason || null, priority: arbRes.picked?.priority || null, candidates: arbRes.candidates, throttled: arbRes.throttled } } : {}),
      tide: { level: water.level, band: water.band, why: water.why },
      drive: driveRes ? { mode: driveRes.mode, why: driveRes.why, takeover: driveRes.takeover?.active ? { since: driveRes.takeover.since, reason: driveRes.takeover.reason } : null } : null,
      // 测试模式：手机照常算锁，但只弹「这里本来会锁」的提示，不真锁。默认开，他自己关。
      shadow: config.lockShadow !== false,
      // 2026-11-19 永不锁只有一份：插件这份（自己 / 急事 / 系统 / 代理 + iQOO 相册文件选图分享相机），
      // 手机存下来、落锁前对照；手机本地的桌面 / DSH / 系统界面硬兜底照旧。
      neverLock: [...new Set([...SELF_PACKAGES, ...EMERGENCY_PACKAGES, ...SYSTEM_PACKAGES, ...PROXY_PACKAGES, ...RESIDENT_PACKAGES, ...BASE_EXTRA])],
      autonomy: v4ContractTick.autonomy,
      planContract: v4ContractTick.contract,
      contractGraceWarning: v4ContractTick.graceWarning,
      geminiMinutesToday,
      lockArbiter: lockArbiterOut,
      ...(lockDecision ? { lock: lockDecision } : {}),
      activeSisterSkin,
      ...(geminiAutoPull ? { geminiAutoPull } : {}),
      // release：只有他讲通了（宽限期内）或 3 分钟应急放行期间才放行大肥鱼加的锁。
      // 锁机事故修复：前台是桌面 / DSH 自己 / 系统界面 / 代理时不给锁目标（手机没有目标就不落锁）。
      force: { level: effectiveForceLevel, name: force.name, why: effectiveForceWhy, app: (!hardCapDowngrade && phone?.app && !isNeverLockPkg(phone.app)) ? phone.app : undefined,
        release: effectiveForceRelease },
      ...(radar.triggered ? { behaviorRadar: { kind: radar.kind, reason: radar.reason } } : {}),
      clockTasks: clockKernel.list(),
      ...(mediaAction ? { media: mediaAction } : {}),
      appTier: tierInfo,
      contextExempt: exemptRes.exempt ? { active: true, until: exemptRes.until, reason: exemptRes.reason } : null,
      degradedEscape: escapeRes.degraded ? { active: true, reason: escapeRes.reason, distinctApps: escapeRes.distinctApps, dwellSeconds: escapeRes.dwellSeconds } : null,
      lease: leaseNow,
      emergencyPass: emergencyActive ? { active: true, until: epUntil, reason: activeEmergency.reason } : null,
      ...(pullBackRes.pullBack ? { emergencyPullBack: { triggered: true, reason: pullBackRes.reason, expiredAt: pullBackRes.expiredAt } } : {}),
      ...(elasticRes ? { elasticBuffer: { ...elasticRes, ...(schedulingFloor ? { schedulingFloor } : {}) } } : {}),
      trust: { score: trust.score, band: trust.band },
      penalty: penaltyNow,
      // 临时封禁名单：手机把这些也当娱乐 App 管。
      block: (emergencyActive || breakActive || hardCapDowngrade) ? [] : blockedNow(now),
      // A6：每个锁都要能解释。这是「哪个 App 被哪条预案锁的」那一句，锁页直接显示。
      blockWhy: (Array.isArray(config.blockExtra) ? config.blockExtra : [])
        .filter((b) => b && Number(b.until) > now && !NEVER_BLOCK.includes(b.app) && b.why)
        .map((b) => ({ app: b.app, why: String(b.why).slice(0, 120), until: Math.round(Number(b.until)), by: String(b.by || '') })),
      // 第九轮 B1：今天还没有计划 → 手机的浮层/首页挂一张「去定计划」，三个角色谁在场都同一张。
      planGate: planGateNow(now, tz),
      // 通知防波堤：忙时 / 任务模式期间，非白名单通知收进缓存（手机端 NotificationShield）。
      shield: !emergencyActive && !breakActive && !hardCapDowngrade && (effectiveGear(config, now) === 'busy' || aiTask(config, now)) ? { until: Math.max(Number(config.gearUntil) || 0, now + ROLL_MS) } : null,
      // 拒绝模式：手机记住到期时间，自己在每次切到娱乐 App 的那一刻就锁，不等这一问。
      refuse: !emergencyActive && !breakActive && !hardCapDowngrade && refuseUntilAt(now) > now ? { until: refuseUntilAt(now) } : null,
      gear: effectiveGear(config, now),
      // Gemini / 外部 Agent 租约互动舱：这段时间只许白名单包，其余 App 由手机直接锁；应急通行时让路。
      session: sessionOut,
      ...(!nightLocked && (forceUnlockUntil > now || breakActive) ? { clearWorkSession: true } : {}),
      // D4：预约的下一场连麦（手机据此 arm 闹钟；到点调 /gemini/due 取 launch）。
      geminiSchedule: (() => {
        const s = loadGeminiSchedule()
        return s.at && !s.fired && Number(s.at) > now ? { at: Number(s.at), topic: String(s.topic || ''), minutes: Number(s.minutes) || 30 } : null
      })(),
      // 本地规则快照（C2）：手机存下来，引擎不可达时照这份执法。
      rules: rulesSnapshot(now, tz),
      // 全天状态机（C2/C3）：这一刻只有一个主状态，追问锁是叠在上面的临时层。
      state: stateView(now, tz, phone),
      followup: followupView(config.followup, now),
      obligations: obligationsView(config.obligations, now),
      // 第二轮·墙：此刻娱乐锁/开、申请窗口开没开、本周还剩几天放松额度（面板和手机据此显示）。
      wall: {
        locked: (emergencyActive || breakActive || hardCapDowngrade) ? false : wd.locked, kind: (emergencyActive || breakActive || hardCapDowngrade) ? null : (wd.kind || null), openUntil: wd.openUntil || 0,
        applyWindowOpen: applyWindowOpen(config.applyWindow, now),
        applyWindowCloseAt: Number(config.applyWindow?.closeAt) || 0,
        relaxRemaining: relaxRemainingNow(now, tz),
        depositMissing: statusOf(depositToday(now)).missing.join('、'),
        relaxToday: Boolean(activeRelax(config, now, tz)),
        tightenToday: tightenActive(config, now, tz),
      },
    }
  }

  function outcomeRoute(body) {
    const requestId = typeof body?.requestId === 'string' ? body.requestId : ''
    const stage = String(body?.stage || '')
    const allowed = new Set(['delivered', 'displayed', 'replied', 'substantive', 'dismissed'])
    if (!requestId || !allowed.has(stage)) {
      return { v: 1, error: { code: 'bad-outcome', message: 'requestId/stage' } }
    }
    record({
      requestId,
      state: stage,
      at: Number(body?.at) || Date.now(),
      replyClass: typeof body?.replyClass === 'string' ? body.replyClass.slice(0, 32) : undefined,
    })
    return { v: 1, ok: true }
  }

  function statusBody() {
    const agents = ctx.get?.('agents')
    const live = (agents?.roots?.() ?? agents?.list?.() ?? []).map((a) => ({ id: a.id, status: a.status }))
    const now = Date.now()
    const entries = readLedger(home(), 400)
    const today = summarize(entries, startOfDay(now, config.timeZone))
    const tz = zoneOr(config.timeZone)
    const intInfo = intensityStateNow(now, tz)
    const timeLedger = timeLedgerNow(now, tz, lastPhone)
    const passLedger = passLedgerSummary(loadPassLedger(now, tz), { day: dayString(now, tz), cap: intInfo.emergencyDisarmDailyCap, now })
    return {
      v: 1,
      version: '0.2.0',
      config: publicConfig(),
      arbiterEnforce: config.arbiterEnforce !== false,
      intensity: intInfo.intensity,
      effectiveIntensity: intInfo.effectiveIntensity,
      autonomyShift: intInfo.autonomyShift,
      pendingIntensity: intInfo.pendingIntensity,
      emergencyDisarmDailyCap: intInfo.emergencyDisarmDailyCap,
      hardInterventions: intInfo.hardInterventions,
      timeLedger,
      passLedger,
      // 首页直接读这两项（不必再拆 config）：判球模型和最近 10 次的实测耗时。
      judgeProvider: config.judgeProvider || null,
      judgeModel: config.judgeModel || null,
      // 2026-11-19 H2：破窗不再扣信用分（默认 0）。
      breakglassPenalty: Number(config.breakglassPenalty) || 0,
      timings: readTimings(),
      sessionId: config.sessionId ?? null,
      agents: live,
      counters: desk.counters,
      today: today.counts,
      // 第六轮 B：今天三次审查各自的最后一条结论（null = 还没审，也不会有"审过了却查不到"）。
      anchors: anchorStatus(now, entries),
      // 第六轮 A：支线底座的状态（几条在跑、最近用过哪几条、哪几条配置不合格被丢掉了）。
      tracks: trackStatus(configuredTracks(), recentTracks(entries)),
      // 第六轮 C：今天暗思考跑了几次、最近一条整理了什么（只记账，没有要发出去的话）。
      muse: museStatus(entries, now),
      // 第七轮 B2：夜间整理跑了几次、还剩几条活着的产物（超期就没了）。
      nightly: nightlyStatus(loadNightly(), entries, now, zoneOr(config.timeZone)),
      // 第六轮 D：自建工作流的配额与到期时间（几条在跑、还能提几条）。
      workflows: workflowStatus(config.workflows, entries, now, config.timeZone),
      // 好坏只看回合：有效回合 = 至少两拍且有一拍接住了（见 rally.js）。
      rallies: rallyStats(entries, startOfDay(now, config.timeZone)),
      // 「现在」这一层要能说清：上一球被漠视到哪一级、静音到什么时候。
      silence: silenceState(entries, { sessionId: config.sessionId, now }),
      lastTopicId: today.lastTopicId || null,
      memory: { path: memoryPath(), exists: existsSync(memoryPath()) },
      // 第五轮·每日四部门提交：今天交了几段、还缺哪几段（缺了就当天没有放风额度）。
      deposit: (() => {
        const d = depositToday(now)
        return { ...statusOf(d), missingText: missingText(d) }
      })(),
      // 应用管理页的「Gemini 互动」：进行中的连麦舱（null = 没开）。
      gemini: { session: geminiSessionView(now) },
      garden: gardenStats(garden, now),
      gardenTopics: garden.topics
        .filter((t) => t.status !== 'done')
        .slice(0, 8)
        .map(publicTopic),
      recent: readLedger(home(), 20),
    }
  }

  // ---------------------------------------------------------------- 「鱼丸 · 鲸澜」面板 + 家庭群
  /** 打开着的面板（按会话）：她们回话时刷新。 */
  const panelScopes = new Map()
  let panelApi = null
  function refreshPanels() {
    if (!panelApi) return
    for (const scope of panelScopes.values()) {
      try {
        panelApi.refresh('sisters', scope)
      } catch {
        // 面板关了
      }
    }
  }
  /** 最近一次手机现状（/plan 带来的）：面板的"专注"分要用。 */
  let lastPhone = null
  /**
   * R4-5 姐妹界面：手机上的原生页（两张角色卡 + 一块灰色的今日审计面板）。
   *
   * 只读。这一页刻意没有任何能放松规则的入口——放松只能走晚上对账后的申请窗口。
   * 数据不重算：记得的事、最近对话、墙此刻锁没锁都来自 panelSnapshot()，
   * 这里只补它没有的两块（角色卡头部、今日审计 + 近 14 天完成率）。
   */
  /**
   * 第六轮 A：支线的一页配置。
   * GET 形态（不带 tracks）只看状态；带了 tracks 数组就是整页替换。
   * 校验不过的条目不会静默丢掉——原样报回去，哪一项缺哪个槽位说清楚。
   */
  function workflowsRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const entries = readLedger(home(), 300)
    const read = () => ({ v: 1, ok: true, workflows: workflowStatus(config.workflows, entries, now, tz) })
    const save = () => {
      try {
        writeConfig(config)
      } catch {
        return false
      }
      return true
    }
    const action = typeof body?.action === 'string' ? body.action : ''
    // 不带 action = 只读（面板用）。
    if (!action) return read()

    if (action === 'propose') {
      const r = workflowPropose(config.workflows, entries, body?.workflow || body, now, tz)
      if (!r.ok) {
        // 被拒的提议也留痕：拒了几次、拒的是什么原因，验收要查得到。
        record({ state: 'workflow-refused', at: now, code: r.code, missing: r.missing, rejected: r.rejected })
        return { v: 1, ok: false, error: { code: r.code, missing: r.missing, rejected: r.rejected, quota: r.quota } }
      }
      config = { ...config, workflows: [...(config.workflows || []), r.workflow] }
      if (!save()) return { v: 1, ok: false, error: { code: 'save-failed' } }
      record({ state: 'workflow-proposed', at: now, workflowId: r.workflow.id, problem: r.workflow.problem, metric: r.workflow.metric })
      return { ...read(), ok: true, workflow: r.workflow }
    }

    if (action === 'trial' || action === 'stop') {
      const id = typeof body?.id === 'string' ? body.id : ''
      const r = workflowAct(config.workflows, id, action, now)
      if (!r.ok) return { v: 1, ok: false, error: { code: r.code, state: r.state } }
      config = { ...config, workflows: r.workflows }
      if (!save()) return { v: 1, ok: false, error: { code: 'save-failed' } }
      record({ state: 'workflow-' + action, at: now, workflowId: id })
      return { ...read(), ok: true, workflow: r.workflow }
    }

    if (action === 'effect') {
      // 可数效果只由账本记：count 必须 > 0，空转不算。
      const id = typeof body?.id === 'string' ? body.id : ''
      const count = Math.round(Number(body?.count) || 0)
      const known = (config.workflows || []).some((w) => w?.id === id)
      if (!known) return { v: 1, ok: false, error: { code: 'not-found' } }
      if (count <= 0) return { v: 1, ok: false, error: { code: 'no-count' } }
      record({ state: 'workflow-effect', at: now, workflowId: id, count })
      return { ...read(), ok: true }
    }

    return { v: 1, ok: false, error: { code: 'bad-action', action: action.slice(0, 40) } }
  }

  function tracksRoute(body) {
    const now = Date.now()
    const entries = readLedger(home(), 200)
    if (Array.isArray(body?.tracks)) {
      const before = normalizeTracks(config.tracks)
      const after = normalizeTracks(body.tracks)
      // 一条都没合格又确实给了东西：十成是写错了，别把现有配置清空。
      if (!after.tracks.length && Array.isArray(body.tracks) && body.tracks.length > 0) {
        return { v: 1, ok: false, error: { code: 'no-valid-track', message: '四条槽位缺一条就整条不生效', dropped: after.dropped } }
      }
      config = { ...config, tracks: body.tracks }
      try {
        writeConfig(config)
      } catch {
        // 这一轮照样生效
      }
      record({ state: 'tracks-set', at: now, from: before.tracks.length, to: after.tracks.length })
      return { v: 1, ok: true, ...trackStatus(configuredTracks(), recentTracks(entries)) }
    }
    return { v: 1, ok: true, ...trackStatus(configuredTracks(), recentTracks(entries)) }
  }

  /**
   * C2：四个基本操作（回想 / 精简 / 预判 / 提问）。
   *
   * 合格线全在 `drills.js` 的 judge 里，这一层只做三件事：从挂载卡上取素材、把答案交给 `judgeOp`、记账。
   * **答案不交给模型判**：判的人手里有权，就会开始安慰人；一软，练半年也不知道有没有长进，
   * 界面还会告诉他已经通过了。
   */
  function opMaterial(op, entries) {
    const card = openCardOf(loadMount())
    const givens = card ? card.givens.map((g) => g.text) : []
    const steps = card ? card.steps.map((s) => s.text) : []
    if (op === 'brief') {
      // 素材是他最近一段超过 100 字的话：太短的压不出东西，压了也看不出他会不会压。
      let source = ''
      for (let i = entries.length - 1; i >= 0; i -= 1) {
        const e = entries[i]
        if (e?.state !== 'say-text' || !e.text) continue
        if (String(e.text).trim().length <= 100) continue
        source = String(e.text)
        break
      }
      return { source, goal: card ? card.goal.text : '' }
    }
    // 回想只认结论（目标在卡顶上，照着目标念不算「想起来推到哪」）。
    // 素材取**上一张卡**（停放着的也行）：正在用的那张开着的时候他看得见，那不算回想。
    if (op === 'recall') {
      const last = lastCardOf(loadMount())
      return { cardWords: last ? last.steps.map((s) => s.text).join('；') : '', blind: Boolean(last) && last.status !== 'open' }
    }
    return { cardWords: [...givens, ...steps].join('；') }
  }

  /**
   * C2：聊天里回球时按代码判这一题（`/drill-op` 是另一条路，判的是同一套 judge）。
   *
   * 判完立刻记一条 `drill-op`：额度和链路都读账本，不记账下一题会一直卡在同一个操作。
   * 回给判球链路的结果用 `verdict` 的形状，让「这一球算不算认真回」的老记账不用改。
   * 判词只用既有的六个（过了 = engaged，没过 = brief）：自造一个词会污染判词白名单，
   * 而这条路上的 `verdict` 还要进大脑记账。
   */
  function judgeOpNow(op, text, sessionId) {
    const entries = readLedger(home(), 800)
    const judged = judgeOp(op, text, opMaterial(op, entries))
    record({ state: 'drill-op', at: Date.now(), op, ok: judged.ok, code: judged.code, sessionId: sessionId || undefined })
    return { verdict: judged.ok ? 'engaged' : 'brief', judged: true, text: judged.text, next: '' }
  }
  function drillOpView(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const since = startOfDay(now, tz)
    const entries = readLedger(home(), 800)
    const quota = opQuota(entries, since)
    // 额度用完今天就到此为止：把这个操作练成肌肉记忆比凑够六题重要。
    const next = quota.left > 0 ? nextOp(entries, since) : null
    return {
      v: 1, ok: true,
      op: {
        order: OP_ORDER,
        next,
        meta: next ? OP_META[next] : null,
        quota,
        // 近 14 天，今天算第 1 天。
        stats: opStats(entries, since - 13 * 86_400_000),
      },
    }
  }
  function drillOpRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const since = startOfDay(now, tz)
    const result = drillOpView(body)
    const quota = result.op.quota
    if (quota.left <= 0) {
      return { ...result, ok: false, error: { code: 'no-quota', message: '今天的额度用完了（' + quota.max + ' 题）。' } }
    }
    // 不出 `op` 就按链路该出的那个判：调用方少一步，链路约束也少一处能绕开的地方。
    const op = String(body?.op || result.op.next || '')
    if (!OP_META[op]) {
      return {
        ...result, ok: false,
        error: { code: 'bad-op', message: '只认 ' + OP_ORDER.join(' / ') + ' 四个操作。' },
      }
    }
    const entries = readLedger(home(), 800)
    const ctx = { ...opMaterial(op, entries), blind: body?.blind === true }
    const judged = judgeOp(op, body?.answer, ctx)
    // 第一次没过只说他差在哪条线；第二次没过才给示范（规格原文，见 drills.js 的 demoOf）。
    const demo = !judged.ok && failsToday(entries, since, op) >= 1 ? demoOf(op) : ''
    record({ state: 'drill-op', at: now, op, ok: judged.ok, code: judged.code })
    // 记完再读一次：额度、链路、进度都要算上刚刚这一题。
    return { ...drillOpView(body), judge: { op, ok: judged.ok, code: judged.code, text: judged.text, demo: demo || undefined } }
  }

  function sistersRoute(body) {
    const now = Date.now()
    const tz = requestZone(body?.timeZone, config)
    const today = dayString(now, tz)
    const snap = panelSnapshot()
    const dir = notesDir()
    let auditText = ''
    if (dir) {
      try {
        auditText = readVaultText(join(dir, WALL_DIR, AUDIT_DIR, today + '.md')) || ''
      } catch {
        // 今天还没审过：没有原话，面板只说计数
      }
    }
    return {
      v: 1,
      ok: true,
      today,
      names: snap.names,
      cards: cardsOf(snap.names),
      memory: Array.isArray(snap.memory) ? snap.memory : [],
      timeline: Array.isArray(snap.timeline) ? snap.timeline : [],
      wall: snap.wall || null,
      audit: auditOf({ memory: loadWallMem(), auditText, today }),
      // 第九轮 B1：姐妹页也挂同一张「去定计划」——三个角色都不该让他自己去翻菜单。
      planGate: planGateNow(now, tz),
      // C2：四个操作近 14 天的通过率。面板和周报都读这一张，不再自己算一遍。
      drillOps: opStats(readLedger(home(), 800), startOfDay(now, tz) - 13 * 86_400_000),
    }
  }
  function panelSnapshot() {
    const now = Date.now()
    const entries = readLedger(home(), 600)
    const todayStart = startOfDay(now, config.timeZone)
    let facts = []
    const db = openMemoryDb(memoryPath())
    try {
      facts = backgroundFacts(loadCandidates(db, { sessionId: config.sessionId, now }), 6)
    } catch {
      // 记忆读不到就空着
    } finally {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
    }
    return buildSnapshot({
      entries,
      todayEntries: entries.filter((e) => Number(e?.at) >= todayStart),
      trust: trustScore(entries, now, { weights: trustWeights() }),
      phone: lastPhone,
      names: roleNames(config),
      // 面板上的墙：此刻锁/开、本周剩余额度、今天有没有修改。
      wall: (() => {
        const tz = zoneOr(config.timeZone)
        const wd = wallDecision(wallCfgNow(), now, tz)
        return {
          locked: wd.locked, kind: wd.kind || null, openUntil: wd.openUntil || 0,
          applyWindowOpen: applyWindowOpen(config.applyWindow, now),
          relaxRemaining: relaxRemainingNow(now, tz),
          depositMissing: statusOf(depositToday(now)).missing.join('、'),
          relaxToday: Boolean(activeRelax(config, now, tz)),
          tightenToday: tightenActive(config, now, tz),
          weeklyQuota: wallConfig(config).weeklyQuota,
        }
      })(),
      speakerOf,
      config,
      facts,
      commitments: readCommitments(home(), now, 3),
      gemini: loadGemini(),
      notesDir: notesDir(),
      now,
    })
  }

  // ---------------------------------------------------------------- 局（W1/W3）
  // 本进程开的那一局。数据全在库里（bouts.js 是唯一写入方），这里只留一个指针：
  // 面板要显示「现在这一拍」，得问局官本人，不能从库里倒推。
  // db 留在 live 上是为了局官写 beats/verdicts 时不用每次重开；面板读另开一条连接，读完就关。
  let live = null

  function boutsSnapshot() {
    const ownDb = !live?.db
    const db = live?.db || openBoutsDb(memoryPath())
    const protocols = builtinProtocols().filter((x) => x.ok).map((x) => x.protocol)
    const liveView = live ? live.official.view() : null
    if (!db) {
      return buildBoutsSnapshot({
        now: Date.now(), bouts: [], todayBouts: [], protocols, live: liveView,
        doneToday: 0, emptyToday: 0, escalations: 0, slackBeats: 0, beats: [], todayVerdicts: [],
      })
    }
    try {
      const now = Date.now()
      const dayStart = startOfDay(now, zoneOr(config.timeZone))
      const all = listBouts(db, { limit: 200 })
      const today = listBouts(db, { dayStart, limit: 100 })
      const closedToday = today.filter((x) => x.status === 'closed')
      let failed = 0
      const beats = []
      const verdicts = []
      for (const bout of today) {
        const rows = listVerdicts(db, bout.id)
        failed += rows.filter((v) => v.result === 'fail').length
        verdicts.push(...rows)
        beats.push(...listBeats(db, bout.id))
      }
      writeJson(
        stateFile('bouts-status.json'),
        boutsTileStatus({ now, beats, bouts: all, protocols, live: liveView }),
      )
      return buildBoutsSnapshot({
        now,
        bouts: all,
        todayBouts: today,
        protocols,
        live: liveView,
        doneToday: closedToday.filter((x) => x.closeReason === 'done').length,
        emptyToday: closedToday.filter((x) => x.closeReason !== 'done').length,
        escalations: closedToday.filter((x) => x.closeReason === 'escalated').length,
        slackBeats: failed,
        beats,
        todayVerdicts: verdicts,
      })
    } catch (error) {
      console.error('[companion] boutsSnapshot 失败：' + String((error && error.message) || error))
      return buildBoutsSnapshot({
        now: Date.now(), bouts: [], todayBouts: [], protocols, live: liveView,
        doneToday: 0, emptyToday: 0, escalations: 0, slackBeats: 0, beats: [], todayVerdicts: [],
      })
    } finally {
      if (ownDb) {
        try {
          db?.close?.()
        } catch {
          // 关不掉不影响读出来的数
        }
      }
    }
  }

  // 第八轮 W2：墙是控制塔。数据全是别的模块算好的——这里只读，不重算，也不措辞。
  function wallSnapshot(now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const day = startOfDay(now, tz)
    // P4：「今日升级」读 escalations 表，不读账本。升级是引擎判的、有开有关的一件事，
    // 账本里那几条 escalate 只是事后的一行注记，拿它当数据源会漏掉 level 和结束时间。
    let escalations = []
    let db = null
    try {
      db = openBoutsDb(memoryPath())
      escalations = listEscalations(db, { limit: 60 })
    } catch (error) {
      console.error('[companion] 读升级表失败：' + String((error && error.message) || error))
    } finally {
      try {
        db?.close?.()
      } catch {
        // 关不掉不影响这一屏
      }
    }
    return buildWallSnapshot({
      now,
      tz,
      state: stateView(now, tz, lastPhone),
      plan: planOf(readState(stateFile('plan.json'), null), day),
      entries: readLedger(home(), 600),
      escalations,
      todayStart: day,
      settings: settingsOf(config),
    })
  }

  // 第八轮 W4：设定页的数据。设定只存在 config.day 下，挂起的改松在 config.dayPending。
  function settingsSnapshot(now = Date.now()) {
    return buildSettingsSnapshot({
      settings: settingsOf(config),
      pending: config.dayPending || null,
      pendingAt: Number(config.dayPendingAt) || 0,
      now,
      config,
    })
  }

  // 改设定：先过 settings.js（改紧立即生效 / 改松挂一天），再落盘并同步到笔记库。
  function saveSettings(next, by) {
    const now = Date.now()
    const r = applySettings({ now, prev: settingsOf(config), next, pending: config.dayPending || null })
    config = {
      ...config,
      day: r.settings,
      dayPending: r.pending || undefined,
      dayPendingAt: r.pendingAt || undefined,
    }
    try {
      writeConfig(config)
    } catch {
      // 这一轮照样生效
    }
    record({ state: 'rule-change', at: now, by: by || '设定页', effect: r.applied })
    try {
      const base = join(kbDir(notesDir(), home()), WALL_DIR)
      writeVaultText(join(base, '设定.md'), settingsMarkdown(buildSettingsSnapshot({ settings: r.settings, pending: r.pending, pendingAt: r.pendingAt, now, config })))
    } catch (error) {
      // 笔记库不可写不影响设定本身
      console.error('[companion] 设定.md 写不进去：' + String((error && error.message) || error))
    }
    return r
  }

  /**
   * 2026-11-20 P0：从节点派出来的局，结束就写回节点执行记录（按 workId 幂等，写过一次就不再写）。
   * 收局的几条路（答完验收、手动收工、超时）都会走到 dropLive，所以兜底放在那里，哪条路都漏不掉。
   */
  function commitLiveNode(outcome) {
    if (!live?.nodeLink || live.nodeCommitted) return false
    live.nodeCommitted = true
    const now = Date.now()
    try {
      v4Hub?.horizonsRoute?.({
        action: 'node_commit',
        horizonId: live.nodeLink.horizonId,
        nodeId: live.nodeLink.nodeId,
        workId: live.workId || 'bout-' + live.openedAt,
        outcome,
        minutes: Math.max(0, Math.round((now - live.openedAt) / 60_000)),
        summary: String(live.goal || live.packet?.goal || '').slice(0, 160),
      })
      return true
    } catch {
      return false
    }
  }

  function dropLive() {
    // 没走到验收那一步就收了（手动收工 / 别的路）：按「部分完成」记一笔。
    commitLiveNode('partial')
    try {
      live?.db?.close?.()
    } catch {
      // 已经关了
    }
    live = null
  }

  /**
   * 思维局：题库（stateFile('bank.json')）+ 一份思维协议（config.thinkingProtocol 的四样：
   * 题库 / 招式 / 强度 / 题数）在开局时展开成普通步骤，再交给 J2 的 loadProtocol 校验。
   *
   * 摊不出完整的局就返回 null —— planwire 照旧把它记成「这一型还没装」，不拿别的局顶替。
   * 这道题站不住某一招、题不够、招式靠模型判，全都属于「摊不出来」。
   */
  function thinkingProtocol() {
    const raw = config.thinkingProtocol
    if (!raw || typeof raw !== 'object') return null
    const parsed = parseBank(readState(stateFile('bank.json'), null) || [])
    if (!parsed.ok) return null
    const expanded = expandThinking(raw, { questions: parsed.bank.questions })
    if (!expanded.ok) return null
    const loaded = loadProtocol(expanded.protocol)
    return loaded.ok ? loaded.protocol : null
  }

  function resolveBoutSealNow(now = Date.now(), tz = zoneOr(config.timeZone), explicitSealId = '', explicitSealTitle = '') {
    const sid = String(explicitSealId || '').trim()
    const today = dayString(now, tz)
    const items = loadSeals(now, tz).items
    if (sid) {
      const matched = items.find((x) => x.id === sid)
      return { sealId: sid, sealTitle: String(explicitSealTitle || matched?.title || '') }
    }
    const targetSeal = items
      .filter((x) => x.day === today && !['stretch', 'floor', 'waived'].includes(x.status))
      .sort((a, b) => (Number(a.dueMinute) || 0) - (Number(b.dueMinute) || 0))[0] || null
    return {
      sealId: String(targetSeal?.id || ''),
      sealTitle: String(targetSeal?.title || ''),
    }
  }

  function startBout(protocolId, opts = {}) {
    const protocol = builtinProtocolOf(protocolId) ||
      customProtocolOf(protocolId) ||
      (String(protocolId) === 'thinking' ? thinkingProtocol() : null)
    if (!protocol) return { ok: false, toast: '没有这个协议：' + String(protocolId || '') }
    if (live && !live.official.view().closed) return { ok: false, toast: '已经有一局在跑，先收局。' }
    dropLive()
    // 第九轮第三项：工作包先洗一遍再进局。不合格的不进——但开局本身不因此失败，
    // 计划里那一局的字段校验在提交那一步已经挡过一次了。
    const pkt = normPacket(opts.packet).ok ? normPacket(opts.packet).packet : null
    const dept = isValidDepartment(opts.department)
      ? String(opts.department)
      : inferDepartment({
          mainState: opts.mainState || 'sprint',
          text: `${protocol.name || ''} ${pkt?.goal || ''} ${opts.goal || ''}`,
          runningBout: true,
        }).department
    const db = openBoutsDb(memoryPath())
    const official = createOfficial({ protocol, db, home: home(), packet: pkt, department: dept })
    const started = official.start()
    if (!started.ok) {
      try {
        db?.close?.()
      } catch {
        // ignore
      }
      const why = started.error === 'busy' ? '库里已经有一局在跑。' : '开局失败：' + started.error
      return { ok: false, toast: why }
    }
    const now = Date.now()
    const tzB = zoneOr(config.timeZone)
    const goal = String(opts.goal || '').trim().slice(0, 120)
    const budgetMin = Number(opts.minutes) > 0 ? Math.max(5, Math.min(240, Math.round(Number(opts.minutes)))) : 0
    const workId = String(opts.workId || '').trim()
    const parentHorizonId = String(opts.parentHorizonId || '').trim()
    const existingApps = cleanAllowList([
      ...(Array.isArray(opts.apps?.need) ? opts.apps.need : []),
      ...(Array.isArray(opts.apps?.allow) ? opts.apps.allow : []),
    ])
    const hasExplicitApps = Boolean(opts.apps && typeof opts.apps === 'object' && existingApps.length > 0)
    const scope = resolveScopeApps(goal || pkt?.goal || protocol.name || protocol.id || '', {
      installed: lastPhone?.installedApps || [],
      overrides: tierOverridesNow(),
      extra: existingApps,
    })
    const scopeKey = String(opts.scopeKey || scope.scopeKey || 'default')
    const scopeLabel = String(opts.scopeLabel || scope.scopeLabel || '')
    const resolvedApps = hasExplicitApps
      ? opts.apps
      : { need: scope.allow, allow: scope.allow }
    const boutSeal = resolveBoutSealNow(now, tzB, opts.sealId, opts.sealTitle)
    live = {
      official, db, protocol, packet: pkt, department: dept, apps: resolvedApps, goal, budgetMin, openedAt: now, workId, parentHorizonId,
      sealId: boutSeal.sealId, sealTitle: boutSeal.sealTitle, scopeKey, scopeLabel,
      ...(opts.reverseCapsule ? { reverseCapsule: opts.reverseCapsule } : {}),
    }
    try {
      const tzW = zoneOr(config.timeZone)
      const wsCur = workStoreNow(now, tzW).store
      const mk = markWorkStatus(wsCur, { id: workId, title: goal || pkt?.goal || '', status: 'running', day: dayString(now, tzW), now })
      if (mk.ok) mirrorWorkStoreToFiles(mk.state, now, tzW)
      // 从节点派出来的局：记住是哪个节点（结束时写回）和节点上下文（局详情页显示断点 / 交付物）。
      if (mk.item?.links?.nodeId) live.nodeLink = { horizonId: mk.item.links.horizonId || parentHorizonId, nodeId: mk.item.links.nodeId }
      if (mk.item?.contextBrief) live.contextBrief = mk.item.contextBrief
    } catch {}
    record({ state: 'bout-open', at: now, protocolId: protocol.id, department: dept, via: 'panel', by: String(opts.by || 'panel'), goal: goal || pkt?.goal || protocol.name || protocol.id })
    try {
      v4Hub?.recordStoryline?.({ kind: 'bout_start', actor: 'user', summary: `开局「${goal || protocol.name || protocol.id}」`, at: now }, now)
    } catch {}
    {
      const wr = checkWithdrawalRelease(loadWithdrawal(), { actionHappened: true, now })
      if (wr.released) {
        saveWithdrawal(wr.state)
        record({ state: 'withdrawal-release', at: now, reason: wr.reason })
      }
    }
    // 行为大脑：谁开的局。计划到点自动开的不算他自己开（主动开局比的分母和分子）。
    // 她们开的（by: 'ai'）也不算他自己开的：主动开局比量的是他，不是她们。
    brainRt.noteBout({ protocolId: protocol.id, outcome: 'open', self: opts.by !== 'plan' && opts.by !== 'ai' }, now)
    // 第九轮：开局时带了 App 要求就记下（严格模式和「切出」都按它判）。
    if (resolvedApps && typeof resolvedApps === 'object') presetRt.setApps('current', resolvedApps, now, String(opts.by || 'panel'))
    // 第三项：局官的第一句必须是工作包的第一步（施工书总验收第 4 条）。
    const head = openSay(pkt)
    return {
      ok: true,
      department: dept,
      scopeKey,
      scopeLabel,
      allow: cleanAllowList([...(resolvedApps?.need || []), ...(resolvedApps?.allow || [])]),
      sealId: boutSeal.sealId,
      sealTitle: boutSeal.sealTitle,
      toast: '开局：' + protocol.name + '。' + (head || String(started.say || '')),
      say: head || String(started.say || ''),
    }
  }

  function answerBout(text, form = 'text', channel = 'text', hold = false) {
    if (!live) return { ok: false, toast: '现在没有局。', state: 'none' }
    const r = live.official.answer(text, { form, channel })
    const v = live.official.view()
    const now = Date.now()
    // P4：局官每收到一条回答都先过一遍阶梯。敷衍记一次并可能升级；答错、混乱只回一句处置。
    if (r.accepted !== false) {
      const maxed = r.state === 'escalated' || r.escalated
      const passed = r.passed === undefined ? null : r.passed
      // 阶梯归「敷衍升级阶梯」这条内置预案管；停用了就只分类、不升级。
      let kind = null
      if (presetRt.isActive('ladder-perfunctory')) {
        // RETRY_LIMIT 打满：同一拍重打三次还不过，这一拍就是敷衍。按升级办，不许只关局了事。
        kind = (maxed ? ladderAnswer('', { now }) : ladderAnswer(text, { passed, now }))?.kind
      }
      if (!kind) kind = classifyAnswer({ text: maxed ? '' : text, passed }).kind
      if (kind === 'perfunctory') presetRt.perfunctory(now, lastPhone)
    }
    if (r.state === 'closed' || r.state === 'escalated') {
      const passed = r.state === 'closed' && r.accepted !== false
      const commitNode = (outcome) => commitLiveNode(outcome)
      brainRt.noteBout({ protocolId: live.protocol?.id, outcome: r.state === 'closed' ? 'done' : 'bad', accepted: passed }, now)
      try {
        v4Hub?.recordStoryline?.({
          kind: 'bout_verify',
          actor: 'strategist',
          summary: `${passed ? '收局验收通过' : '局升级/结束'}「${live.goal || live.protocol?.name || ''}」`,
          at: now,
        }, now)
      } catch {}
      // 第九轮第四项：通过验收就记一段放风额度——娱乐用交付挣，不再靠「今天表现好」这种虚的。
      // 升级收场不算交付，一分不给。
      let earnt = ''
      if (passed) {
        const tz = zoneOr(config.timeZone)
        reliefPenaltyOnPass(now, tz)
        // H6：验收通过更新 workStore 并自动回涨关联三层主线进度（horizon.doneSteps + 1）
        try {
          const wsCur = workStoreNow(now, tz).store
          const mk = markWorkStatus(wsCur, { id: live.workId || '', title: live.goal || live.packet?.goal || '', status: 'done', day: dayString(now, tz), now })
          if (mk.ok) mirrorWorkStoreToFiles(mk.state, now, tz)
          const hId = live.parentHorizonId || mk.item?.links?.horizonId || ''
          // 有节点的局写回节点（目标进度由节点推导）；老的没节点的照旧 +1。
          if (!commitNode('passed') && hId) v4Hub?.horizonsRoute?.({ action: 'upsert', id: hId, bumpStep: true })
        } catch {}
        // 时间轴：这一局是为哪个验收点开的，通过的分钟数记到那一点上。
        if (live.sealId) {
          try {
            const done = Math.round((now - Number(live.openedAt || now)) / 60_000)
            addSealBoutMinutes(live.sealId, String(live.official.view().boutId || ''), live.budgetMin ? Math.min(done, live.budgetMin) : done, now, tz)
            sealTick(now, tz, lastPhone)
          } catch {}
        }
        const before = todayEarned(now, tz)
        // 挣来制：这一局挣多少按实际做了多久（≤ 计划分钟）的 1/3 算，10–40 分钟。
        const reward = earnedRt.rewardFor({ budgetMin: live.budgetMin, openedAt: live.openedAt, now, config })
        const next = earnedRt.addForPass(before, config, now, before.day, reward)
        saveEarned(next)
        if (next.lastAdd) {
          earnt = '放风 +' + next.lastAdd + ' 分钟（今天还剩 ' + earnedRt.leftOf(next) + ' 分钟）'
          record({ state: 'earned', at: now, add: next.lastAdd, left: earnedRt.leftOf(next) })
          const plRes = requestPass(loadPassLedger(now, tz), 'earned', {
            source: 'bout',
            minutes: next.lastAdd,
            reason: '通过一局验收',
            now,
            day: dayString(now, tz),
          })
          if (plRes.ok) savePassLedger(plRes.state)
          recordTimeEntryNow({ kind: 'earned_bout', minutes: next.lastAdd, reason: '通过一局验收', at: now, tz })
          try {
            earnt = '娱乐余额 +' + next.lastAdd + ' 分钟（现在共 ' + timeLedgerNow(now, tz).funLeft + ' 分钟，没花完明天接着用）'
          } catch {}
        }
      } else if (r.state === 'escalated') {
        recordBreachPenalty('moderate', now, zoneOr(config.timeZone))
        commitNode('escalated')
      } else {
        commitNode('partial')
      }
      presetRt.strictOff(now, 'close-bout')
      dropLive()
      return {
        ok: true, state: r.state, accepted: r.accepted !== false, say: '', earned: earnt,
        toast: r.state === 'escalated' ? '升级了，这一局结束。' : '收局。' + earnt,
      }
    }
    const say = String(r.say || '')
    // 语音这一路（hold）：下一句已经在返回里了，说明局官马上要念。
    // 先冻住应答时限，等他念完由 spoken 起算——不然这句话还没念完，计时已经跑掉一大截。
    // 面板那条文字路径不开这个：它没有 spoken 那一步，冻住就没人解冻了。
    if (hold && say) live.official.hold(now)
    return {
      ok: r.accepted !== false,
      state: String(r.state || 'running'),
      accepted: r.accepted !== false,
      say,
      toast: say,
      stepId: String(v.stepId || ''),
      limitSec: Number(v.limitSec) || 0,
      nextAskAt: Number(r.nextAskAt || v.nextAskAt) || 0,
    }
  }

  function closeBoutNow() {
    presetRt.strictOff(Date.now(), 'close-bout')
    if (!live) return { ok: false, toast: '现在没有局。' }
    live.official.close('abandoned')
    dropLive()
    return { ok: true, toast: '收局了，这局记成空局。' }
  }

  /**
   * POST /bouts：把从别处分享进来的一段文字，当成当前这一拍的答案。
   *
   * 手机那边先问这里，接得住才不把分享丢进新对话。所以「没有局」必须是明确的 no-bout，
   * 而不是一个静默的空成功——否则这段分享就凭空消失了。
   *
   * 答案走 answerBout（→ official.answer → boutrun），不是直接 addBeat：一拍要不要判、判什么、
   * 记哪个 step_id 都由局官决定；绕过它插一条 beat 会留下一条没有判定的脏数据。
   * runningBout 读的是库，live 是本进程开的那一局——引擎重启过就会有库里有局、进程里没局，
   * 这时这一句接不进去，如实回 no-bout 让手机退回新对话。
   */
  function boutsShareRoute(body) {
    const text = String(body?.text ?? '').trim()
    if (!text) return { v: 1, ok: false, code: 'empty' }
    const db = openBoutsDb(memoryPath())
    try {
      if (!runningBout(db) || !live) return { v: 1, ok: false, code: 'no-bout' }
    } finally {
      try {
        db?.close?.()
      } catch {
        // 关不掉不影响这一句的判断
      }
    }
    const r = answerBout(text.slice(0, 2_000), 'text')
    return { v: 1, ok: Boolean(r.ok), toast: String(r.toast || '') }
  }

  function boutsGetRoute() {
    const now = Date.now()
    const dayData = planDayRoute({ action: 'list' })
    const strictInfo = presetRt.sessionView(now)
    const v = live && !live.official.view().closed ? live.official.view() : null
    const allow = strictInfo?.allow || [
      ...(Array.isArray(live?.apps?.need) ? live.apps.need : []),
      ...(Array.isArray(live?.apps?.allow) ? live.apps.allow : []),
    ]
    const running = v
      ? {
          id: v.boutId || v.id || '',
          protocol: live.protocol?.id || '',
          title: live.goal || live.packet?.goal || live.protocol?.name || live.protocol?.id || '专注进行中',
          strict: Boolean(strictInfo),
          until: Number(strictInfo?.until) || (live.openedAt + (live.budgetMin || Number(live.protocol?.budget?.minutes) || 45) * 60_000),
          allow: [...new Set(allow)],
          startedAt: live.openedAt,
          minutes: live.budgetMin || Number(live.protocol?.budget?.minutes) || 45,
          scopeKey: String(live.scopeKey || ''),
          scopeLabel: String(live.scopeLabel || ''),
          sealId: String(live.sealId || ''),
          sealTitle: String(live.sealTitle || ''),
          ...(live.packet?.deliverable ? { deliverable: String(live.packet.deliverable) } : {}),
          ...(live.contextBrief ? { nodeContext: live.contextBrief } : {}),
          ...(live.nodeLink ? { nodeLink: live.nodeLink } : {}),
        }
      : null
    const protocols = builtinProtocols()
      .filter((x) => x.ok)
      .map((x) => ({
        id: x.protocol.id,
        name: x.protocol.name,
        desc: x.protocol.budget?.note || x.protocol.desc || ('建议时长 ' + (x.protocol.budget?.minutes || 0) + ' 分钟'),
        minutes: x.protocol.budget?.minutes || 0,
      }))
    const queue = (dayData.plan?.items || []).map((it) => {
      const existing = cleanAllowList([
        ...(Array.isArray(it?.need) ? it.need : []),
        ...(Array.isArray(it?.allow) ? it.allow : []),
      ])
      const sc = resolveScopeApps(it?.title || it?.protocolId || '', {
        installed: lastPhone?.installedApps || [],
        overrides: tierOverridesNow(),
        extra: existing,
      })
      return {
        ...it,
        scopeKey: String(it?.scopeKey || sc.scopeKey || 'default'),
        scopeLabel: String(it?.scopeLabel || sc.scopeLabel || ''),
        allow: existing.length ? existing : sc.allow,
      }
    })
    return {
      v: 1,
      ok: true,
      running,
      strict: Boolean(strictInfo),
      queue,
      protocols,
      snapshot: boutsSnapshot(),
    }
  }

  /**
   * POST /bouts 的语音这一路（J9）：念、听、判、下一拍，四步都由手机驱动，状态机还在局官手里。
   *
   * 四个动作：
   *   ask     这一拍现在该念哪一句。顺手把应答时限冻住——念的这一段不算他的时间。
   *   spoken  念完了（或退回文字，字已经在他眼前）：从一个明确的时刻开始计应答时限。
   *   answer  识别出来的话当答案。带 hold，所以局官下一句也先冻着，等他念完再计。
   *   fallback 声音或识别用不了，退回文字/浮层。记账，面板能看见为什么退回。
   *
   * 没有 action 的请求还是原来的「分享进来的一段话」，一个字没改。
   */
  async function boutsRoute(body) {
    const action = String(body?.action || '')
    if (!action) return boutsShareRoute(body)
    if (action === 'dice-slots' || action === 'dice-judge') {
      return wallRoute(body)
    }
    // 第九轮：计划界面开局时一并带上这一局的必需 / 允许应用（严格模式和「切出」按它判）；
    // 第三项起再带工作包（局官第一句就念第一步）。
    if (action === 'open' || action === 'start') {
      let protocolId = String(body?.protocolId || body?.protocol || '')
      let apps = body?.apps && typeof body.apps === 'object'
        ? body.apps
        : (Array.isArray(body?.allow) ? { need: body.allow, allow: body.allow } : undefined)
      let packet = body?.packet
      let goal = body?.title
      let workId = String(body?.id || '')
      let parentHorizonId = String(body?.parentHorizonId || '')
      let sealId = String(body?.sealId || '')
      if ((!protocolId || !apps || !parentHorizonId) && body?.id) {
        const queued = (planDayRoute({ action: 'list' }).plan?.items || []).find((it) => String(it?.id || '') === String(body.id))
        if (queued) {
          if (!protocolId) protocolId = String(queued.protocolId || '')
          if (!apps && (queued.need?.length || queued.allow?.length)) apps = { need: queued.need || [], allow: queued.allow || [] }
          if (!packet && queued.packet) packet = queued.packet
          if (!goal && queued.title) goal = queued.title
          if (!parentHorizonId && queued.parentHorizonId) parentHorizonId = String(queued.parentHorizonId)
          if (!sealId && queued.sealId) sealId = String(queued.sealId)
        }
      }
      const existingAllow = cleanAllowList([...(Array.isArray(apps?.need) ? apps.need : []), ...(Array.isArray(apps?.allow) ? apps.allow : [])])
      const scope = resolveScopeApps(goal || packet?.goal || protocolId, {
        installed: lastPhone?.installedApps || [],
        overrides: tierOverridesNow(),
        extra: existingAllow,
      })
      if (!apps || existingAllow.length === 0) {
        apps = { need: scope.allow, allow: scope.allow }
      }
      const r = startBout(protocolId, {
        apps,
        packet,
        department: body?.department,
        goal,
        minutes: body?.minutes,
        by: body?.by || 'panel',
        workId,
        parentHorizonId,
        sealId,
        scopeKey: scope.scopeKey,
        scopeLabel: scope.scopeLabel,
      })
      if (r.ok && body?.strict === true && apps && ([...(apps.need || []), ...(apps.allow || [])].length > 0)) {
        presetRt.strictNow(Date.now(), String(goal || '面板严格局'), 'panel', body?.minutes)
      }
      return { v: 1, ...r }
    }
    if (action === 'strict') {
      const r = presetRt.strictNow(Date.now(), String(body?.why || '面板切为严格模式'), 'panel', body?.minutes)
      return { v: 1, ...r }
    }
    if (action === 'relax') {
      const now = Date.now()
      const hasStrict = Boolean(presetRt.sessionView(now))
      if (!live && !hasStrict) return { v: 1, ok: false, toast: '现在没有局。' }
      presetRt.strictOff(now, 'panel')
      return { v: 1, ok: true, strict: false, toast: live ? '已切为温和守护（仅提醒不强锁）' : '已撤回残留严格锁' }
    }
    const sanitizeAllowWithGameGuard = (rawList) => {
      const candidates = cleanAllowList(rawList)
      const allow = []
      const rejectedGames = []
      for (const pkg of candidates) {
        if (isPureGame(pkg, '')) rejectedGames.push(pkg)
        else allow.push(pkg)
      }
      return { allow, rejectedGames }
    }
    const resolveQuizOnlyApps = (installedArg = []) => {
      const installedRaw = [
        ...(Array.isArray(lastPhone?.installedApps) ? lastPhone.installedApps : []),
        ...(Array.isArray(installedArg) ? installedArg : []),
      ]
      const examSpec = TASK_APP_SCOPES.exam || {}
      const examPkgs = Array.isArray(examSpec.packages) ? examSpec.packages : []
      const examSet = new Set(examPkgs)
      const examHintRe = examSpec.hintRe instanceof RegExp
        ? examSpec.hintRe
        : /(fenbi|huatu|offcn|anki|obsidian|粉笔|华图|中公)/i
      const matched = []
      for (const entry of installedRaw) {
        const pkg = typeof entry === 'string' ? entry.trim() : String(entry?.app || entry?.pkg || '').trim()
        const label = typeof entry === 'object' ? String(entry?.label || '') : ''
        if (!pkg || isPureGame(pkg, label)) continue
        if (examSet.has(pkg) || examHintRe.test(pkg) || examHintRe.test(label)) matched.push(pkg)
      }
      const uniqueMatched = cleanAllowList(matched)
      if (uniqueMatched.length) return uniqueMatched
      return cleanAllowList(examPkgs.length ? examPkgs : ['com.fenbi.android.solar', 'com.huatu.ztk', 'com.offcn.android', 'com.ichi2.anki', 'md.obsidian'])
    }
    if (action === 'allow' || action === 'set_default_allow') {
      const { allow, rejectedGames } = sanitizeAllowWithGameGuard(body?.allow || body?.apps?.allow || body?.apps?.need || [])
      if (!allow.length) {
        return {
          v: 1,
          ok: false,
          error: rejectedGames.length ? 'pure-game' : 'empty-allow',
          rejectedGames,
          defaultAllow: defaultAllowApps(),
          toast: rejectedGames.length
            ? `纯游戏不可加入白名单：${rejectedGames.join('、')}`
            : '白名单至少保留一个有效应用。',
        }
      }
      const now = Date.now()
      let setRes = null
      if (live) {
        live.apps = { need: allow, allow }
        setRes = presetRt.setApps('current', live.apps, now, String(body?.by || 'panel'))
      }
      if (action === 'set_default_allow' || !live || body?.persistDefault !== false) {
        const aiPatch = (config.aiWorkSession && typeof config.aiWorkSession === 'object')
          ? { aiWorkSession: { ...config.aiWorkSession, allow } }
          : {}
        config = { ...config, defaultAllow: allow, ...aiPatch }
        try { writeConfig(config) } catch {}
      }
      if (live) {
        const wasStrict = Boolean(presetRt.sessionView(now))
        if (wasStrict || body?.strict === true) {
          if (wasStrict) presetRt.strictOff(now, 'panel-allow')
          presetRt.strictNow(now, String(body?.why || '更新本局白名单'), 'panel', body?.minutes)
        }
      }
      record({ state: 'default-allow-set', at: now, action, allow, rejectedGames, hasLive: Boolean(live) })
      return {
        v: 1,
        ...(setRes || {}),
        ok: true,
        allow,
        defaultAllow: defaultAllowApps(),
        rejectedGames,
        toast: rejectedGames.length
          ? `已更新白名单（${allow.length} 个），并拦截纯游戏：${rejectedGames.join('、')}`
          : (live ? '已热更新本局与默认白名单' : '已更新默认白名单'),
      }
    }
    if (action === 'reverse_capsule') {
      const now = Date.now()
      const mode = ['gemini_only', 'quiz_only', 'custom'].includes(String(body?.mode || ''))
        ? String(body.mode)
        : 'quiz_only'
      const minutes = Math.max(5, Math.min(240, Math.round(Number(body?.minutes) || (mode === 'gemini_only' ? 15 : 20))))
      let allow = []
      let rejectedGames = []
      if (mode === 'gemini_only') {
        allow = [...new Set(geminiPackages())]
      } else if (mode === 'quiz_only') {
        allow = resolveQuizOnlyApps(body?.installedApps)
      } else {
        const sanitized = sanitizeAllowWithGameGuard(body?.allow || body?.apps?.allow || body?.apps?.need || [])
        allow = sanitized.allow
        rejectedGames = sanitized.rejectedGames
      }
      if (!allow.length) {
        return {
          v: 1,
          ok: false,
          error: rejectedGames.length ? 'pure-game' : 'empty-allow',
          rejectedGames,
          toast: rejectedGames.length ? `纯游戏不可进入反向单开舱：${rejectedGames.join('、')}` : '反向单开舱至少需要一个合法应用',
        }
      }
      if (live && !live.official.view().closed) {
        closeBoutNow()
      }
      const goal = String(body?.goal || (mode === 'gemini_only'
        ? '反向单开舱 · 只留 Gemini 口头硬考'
        : (mode === 'quiz_only' ? '反向单开舱 · 纯考公刷题舱' : '反向单开舱 · 定制专注'))).slice(0, 120)
      const scopeKey = `reverse:${mode}`
      const until = now + minutes * 60_000
      const started = startBout(String(body?.protocolId || 'execute'), {
        apps: { need: allow, allow },
        goal,
        minutes,
        scopeKey,
        reverseCapsule: { mode, allow, minutes, goal, until, scopeKey, startedAt: now },
        by: String(body?.by || 'reverse-capsule'),
      })
      if (!started.ok) return { v: 1, ...started }
      presetRt.strictNow(now, `反向单开舱(${mode})：${goal}`, 'reverse-capsule', minutes)
      record({ state: 'reverse-capsule', at: now, mode, allow, minutes, until, scopeKey })
      return {
        v: 1,
        ok: true,
        mode,
        allow,
        minutes,
        until,
        scopeKey,
        rejectedGames,
        boutId: live?.boutId || started.boutId,
        toast: `已锁定「${goal}」（${minutes} 分钟，仅放行 ${allow.length} 个应用）`,
      }
    }
    if (action === 'close' || action === 'finish') {
      return { v: 1, ...closeBoutNow() }
    }
    if (action === 'ask-gemini') {
      // 2026-11-20 局详情「叫 Gemini 来判」：他自己觉得卡住了 / 想让它判一下这局对不对。
      // 他点的，算手动（不吃自动拉起的每日上限）；口吻 think，带上这一局的目标、交付、节点断点、已过几分钟。
      if (!live) return { v: 1, ok: false, toast: '现在没有局。' }
      const now = Date.now()
      const tzA = zoneOr(config.timeZone)
      const nc = live.contextBrief || null
      const goal = String(live.goal || live.packet?.goal || '这一局')
      const lines = [
        '这一局：' + goal,
        live.packet?.deliverable ? '要交：' + live.packet.deliverable : '',
        nc?.nodeTitle ? '属于：' + (nc.horizonTitle ? nc.horizonTitle + ' · ' : '') + nc.nodeTitle : '',
        nc?.lastBreakpoint ? '上次断在：' + nc.lastBreakpoint : '',
        '已经过了 ' + Math.max(0, Math.round((now - live.openedAt) / 60_000)) + ' 分钟（计划 ' + (live.budgetMin || 45) + ' 分钟）',
      ].filter(Boolean)
      const topic = ('判一下这一局：' + goal).slice(0, 80)
      const brief = v4Hub.buildProactiveGeminiBrief({ topic: topic + '\n' + lines.join('\n'), mode: 'think', minutes: 10, now })
      const gate = requestGeminiViaGate({ source: 'bout-judge', mode: 'manual', topic, minutes: 10, geminiMode: 'think', brief, now, tz: tzA })
      record({ state: 'bout-ask-gemini', at: now, goal: goal.slice(0, 60) })
      return { v: 1, ok: true, topic, brief: gate.brief || brief }
    }
    if (!['ask', 'spoken', 'answer', 'fallback'].includes(action)) {
      return { v: 1, ok: false, code: 'bad-action' }
    }
    // fallback 不需要有局在跑：声音配不上这件事本身就要记下来。
    if (action === 'fallback') {
      const reason = String(body?.reason || 'unknown').slice(0, 120)
      record({ state: 'bout-voice-fallback', at: Date.now(), reason, via: 'phone' })
      return { v: 1, ok: true, code: 'noted' }
    }
    if (!live) return { v: 1, ok: false, code: 'no-bout' }
    const now = Date.now()
    if (action === 'ask') {
      // 周期步到点：先把同一句问出来，免得每一拍都念上一拍的旧句子。
      live.official.due(now)
      const v = live.official.view()
      if (v.closed || !v.ask) return { v: 1, ok: false, code: v.closed ? 'closed' : 'nothing-to-ask' }
      live.official.hold(now)
      return {
        v: 1, ok: true, say: String(v.ask), stepId: String(v.stepId || ''),
        limitSec: Number(v.limitSec) || 0, index: Number(v.index) || 0,
      }
    }
    if (action === 'spoken') {
      const at = Number(body?.at) || now
      const r = live.official.spoken(at)
      const v = live.official.view()
      return { v: 1, ok: true, at: Number(r.at) || at, stepId: String(r.stepId || ''), limitSec: Number(v.limitSec) || 0 }
    }
    const text = String(body?.text ?? '').trim()
    if (!text) return { v: 1, ok: false, code: 'empty' }
    const r = answerBout(text.slice(0, 2_000), 'text', 'voice', true)
    return { v: 1, ...r, code: r.ok ? '' : 'refused' }
  }

  function cleanAllowList(raw) {
    if (!Array.isArray(raw)) return []
    const out = []
    const seen = new Set()
    for (const x of raw) {
      const s = String(x || '').trim().slice(0, 120)
      if (!s || seen.has(s)) continue
      seen.add(s)
      out.push(s)
      if (out.length >= 60) break
    }
    return out
  }

  function defaultAllowApps() {
    const saved = cleanAllowList(config.defaultAllow)
    return saved.length ? saved : ['md.obsidian', 'com.tencent.mm']
  }

  function todayView(now = Date.now()) {
    const tz = zoneOr(config.timeZone)
    const bouts = boutsGetRoute()
    const plan = planDayRoute({ action: 'list' })
    const rep = usageReportView(now, tz)
    const handoff = handoffRoute({ writeSnapshot: false })
    const queue = Array.isArray(bouts.queue) ? bouts.queue : []
    const tasks = queue.slice(0, 3)
    const funMin = Number(rep.funMinutes) || 0
    const totalMin = Number(rep.totalMinutes) || 0
    const focusMin = Math.max(0, totalMin - funMin)
    const headState = bouts.running
      ? `正在专注「${bouts.running.title}」${bouts.running.strict ? '（严格锁）' : ''}`
      : (tasks.length ? `今日主线 ${tasks.length}/${queue.length} 项待推进，下一局「${tasks[0].title || tasks[0].protocolId}」` : '当前空闲 · 写一句话即可开启严格专注')
    const line = `今日专注约 ${focusMin}m · 娱乐 ${funMin}m · ${headState}${handoff.hasCognitiveOs ? ` · 认知模型 ${Number(handoff.modelsCount) || 0} 条在线` : ''}`
    return {
      v: 1,
      ok: true,
      day: dayString(now, tz),
      brief: {
        line,
        focusMin,
        funMin,
        totalMin,
        hasCognitiveOs: Boolean(handoff.hasCognitiveOs),
        modelsCount: Number(handoff.modelsCount) || 0,
      },
      running: bouts.running,
      strict: Boolean(bouts.strict),
      tasks,
      allQueueCount: queue.length,
      defaultAllow: defaultAllowApps(),
      recentApps: Array.isArray(plan.recent) ? plan.recent : [],
      lastDice: Number(lastRolledDice) || 0,
      heartbeat: (!bouts.running && queue.length === 0) ? 'HEARTBEAT_OK' : 'ACTIVE',
    }
  }

  function todayStartRoute(body = {}) {
    const now = Date.now()
    const goal = String(body?.goal || body?.title || '').trim().slice(0, 80) || '深度专注'
    const minutes = Math.max(5, Math.min(240, Math.round(Number(body?.minutes) || 45)))
    const strict = body?.strict !== false
    const parsedAllow = cleanAllowList(body?.allow || body?.apps?.allow || body?.apps?.need)
    const scope = resolveScopeApps(goal || String(body?.protocolId || ''), {
      installed: lastPhone?.installedApps || [],
      overrides: tierOverridesNow(),
      extra: parsedAllow,
    })
    const allow = scope.allow.length ? cleanAllowList(scope.allow) : (parsedAllow.length ? parsedAllow : defaultAllowApps())
    config = { ...config, defaultAllow: allow }
    try { writeConfig(config) } catch {}
    const r = startBout(String(body?.protocolId || 'execute'), {
      apps: { need: allow, allow },
      goal,
      minutes,
      department: body?.department,
      packet: body?.packet,
      sealId: body?.sealId,
      scopeKey: scope.scopeKey,
      scopeLabel: scope.scopeLabel,
      by: String(body?.by || 'panel'),
    })
    if (r.ok && strict && allow.length > 0) {
      presetRt.strictNow(now, goal, String(body?.by || 'panel'), minutes)
    }
    return { v: 1, ...r, strict, allow, minutes, goal, scopeKey: scope.scopeKey, scopeLabel: scope.scopeLabel }
  }

  function todayTaskRoute(body = {}) {
    const now = Date.now()
    const tz = zoneOr(config.timeZone)
    const day = startOfDay(now, tz)
    const stored = planOf(readState(stateFile('plan.json'), null), day)
    const action = String(body?.action || 'add')
    const curItems = Array.isArray(stored.items) ? stored.items.map((x) => ({ ...x })) : []
    if (action === 'remove') {
      const targetId = String(body?.id || '').trim()
      const targetTitle = String(body?.title || '').trim()
      const idx = Number.isInteger(Number(body?.index)) ? Number(body.index) : -1
      const filtered = curItems.filter((it, i) => {
        if (targetId && String(it.id || '') === targetId) return false
        if (targetTitle && String(it.title || '') === targetTitle && !targetId) return false
        if (idx >= 0 && i === idx && !targetId && !targetTitle) return false
        return true
      })
      const saved = planDayRoute({ action: 'set', items: filtered, by: String(body?.by || 'today') })
      try {
        const wsNext = removeWorkItem(workStoreNow(now, tz).store, { id: targetId, title: targetTitle, day: dayString(now, tz), now })
        mirrorWorkStoreToFiles(wsNext, now, tz)
      } catch {}
      return { v: 1, ...saved, tasks: (saved.plan?.items || []).slice(0, 3) }
    }
    const title = String(body?.title || body?.text || '').trim().slice(0, 80)
    if (!title) return { v: 1, ok: false, code: 'empty-title', text: '请先写一句任务内容。' }
    const minutes = Math.max(5, Math.min(240, Math.round(Number(body?.minutes) || 45)))
    const parsedAllow = cleanAllowList(body?.allow)
    const allow = parsedAllow.length ? parsedAllow : defaultAllowApps()
    const item = {
      id: 'td-' + randomUUID().replace(/-/g, '').slice(0, 8),
      protocolId: String(body?.protocolId || 'execute'),
      title,
      minute: zoneMinuteOfDay(now, tz),
      budget: minutes,
      need: allow,
      allow,
      ...(body?.packet && typeof body.packet === 'object' ? { packet: body.packet } : {}),
    }
    const items = body?.top === true ? [item, ...curItems] : [...curItems, item]
    const saved = planDayRoute({ action: 'set', items, by: String(body?.by || 'today') })
    if (saved.ok) {
      try {
        v4Hub?.recordStoryline?.({ kind: 'morning_anchor', actor: 'user', summary: `立项：${title}（${minutes}m）`, at: now }, now)
      } catch {}
    }
    return {
      v: 1,
      ...saved,
      item,
      tasks: (saved.plan?.items || []).slice(0, 3),
      text: saved.ok ? `已加入 Today 主线：${title}（${minutes}m）` : (saved.text || '添加失败'),
    }
  }

  /** 一句话发进去，等到回完（最多两分钟）。 */
  async function sayTo(sid, text) {
    const id = 'say-' + randomUUID().replace(/-/g, '').slice(0, 16)
    lastHumanAt.set(String(sid), Date.now())
    const r = await chat.request({ v: 1, requestId: id, eventId: id, kind: 'say', payload: { text: String(text).slice(0, 2_000) }, deadlineMs: 120_000, sessionId: sid })
    if (r.status >= 400) return null
    for (let i = 0; i < 6; i += 1) {
      const w = await chat.wait(id, 25_000)
      const st = w?.body?.state
      if (st === 'generated') return String(w.body.text || '')
      if (st === 'failed' || st === 'cancelled') return null
    }
    return null
  }
  /**
   * 家庭群：发给一个人，或者一起——鱼丸先回，鲸澜随后收到她的原话接着说。
   * 两人拿到的开场是同一句，只多一条聊天记录；不给模型发导演提示（见 sisters.js groupPrompts）。
   * 在后台跑，面板先返回，回复到了自动刷新。
   */
  function groupSay(text, to) {
    const names = roleNames(config)
    const said = String(text || '').trim().slice(0, 1_000)
    if (!said) return '说点什么再发。'
    const sis = config.sessionId
    const big = config.dual !== false ? config.strategistSessionId : ''
    record({ state: 'say-text', at: Date.now(), to, text: stripInjection(said).slice(0, 300), via: 'panel' })
    if (to === 'strategist' && !big) return '还没设谋士会话。'
    if (to === 'companion' || (to === 'both' && !big)) {
      if (!sis) return '还没设陪伴会话。'
      sayTo(sis, said).catch(() => {})
      return '发给' + names.companion + '了。'
    }
    if (to === 'strategist') {
      sayTo(big, said).catch(() => {})
      return '发给' + names.strategist + '了。'
    }
    ;(async () => {
      // 只配了一个会话时，缺的那个不往空 sessionId 发请求。
      const first = sis ? await sayTo(sis, groupPrompts({ names, said })) : null
      await sayTo(big, groupPrompts({ names, said, first, firstName: names.companion }))
      refreshPanels()
    })().catch(() => {})
    return '发到群里了：' + names.companion + '先回，' + names.strategist + '接着说。'
  }
  if (typeof ctx.inject === 'function') {
    try {
      ctx.inject(['dshGo'], (dctx) => {
        const d = dctx?.dshGo
        if (!d || typeof d.panel !== 'function') return
        panelApi = d
        const keyOf = (scope) => String((scope && scope.sessionId) || '')
        const values = (event) => {
          const v = event && event.value ? event.value.values || event.value : {}
          return v && typeof v === 'object' ? v : {}
        }
        if (typeof d.on === 'function') d.on('sisters-open', () => ({ open: 'panel:sisters' }))
        if (typeof d.on === 'function') d.on('bouts-open', () => ({ open: 'panel:bouts' }))
        if (typeof d.on === 'function') d.on('bouts-queue-open', () => ({ open: 'panel:bouts-queue' }))
        if (typeof d.on === 'function') d.on('bouts-books-open', () => ({ open: 'panel:bouts-books' }))
        if (typeof d.on === 'function') d.on('wall-open', () => ({ open: 'panel:wall' }))
        if (typeof d.on === 'function') d.on('wall-audit-open', () => ({ open: 'panel:wall-audit' }))
        d.panel('sisters', {
          view: sistersView,
          data: (scope, emit) => {
            panelScopes.set(keyOf(scope), scope)
            emit(panelSnapshot())
            return () => panelScopes.delete(keyOf(scope))
          },
          actions: {
            'sisters.refresh': (scope) => {
              d.refresh('sisters', scope)
              return { toast: '' }
            },
            'group.say': (scope, event) => {
              const v = values(event)
              const toast = groupSay(v['data.form.text'], ['companion', 'strategist', 'both'].includes(v['data.form.to']) ? v['data.form.to'] : 'both')
              d.refresh('sisters', scope)
              return { toast }
            },
            // 面板只能收紧，不能放松：没有"关拒绝模式""结束任务"的按钮。
            'sisters.busy': (scope, event) => {
              const v = values(event)
              const mins = clampStrictMinutes(v.minutes ?? v['data.form.minutes'], BUSY_DEFAULT_MIN)
              setGear({ gear: 'busy', until: Date.now() + mins * 60_000, what: String(v.what || v['data.form.what'] || '正事').slice(0, 20) }, 'panel')
              d.refresh('sisters', scope)
              return { toast: '忙时 ' + mins + ' 分钟：不闲聊、娱乐一开就锁，到点只让你报到一次。' }
            },
            'sisters.refuse': (scope, event) => {
              const v = values(event)
              const mins = clampStrictMinutes(v.minutes ?? v['data.form.minutes'], 30)
              const until = Math.max(Date.now() + mins * 60_000, Number(config.refuseUntil) || 0)
              config = { ...config, refuseUntil: until, refuseBy: config.refuseBy || 'panel' }
              try {
                writeConfig(config)
              } catch {
                // 这一轮照样生效
              }
              record({ state: 'control', at: Date.now(), action: 'refuse', minutes: mins, by: 'panel' })
              d.refresh('sisters', scope)
              return { toast: '拒绝模式 ' + mins + ' 分钟。' }
            },
          },
        })
        // 局是独立的一面：两姐妹不在这个面板里说话（W1）。
        // 宿主没有分页组件，四页就是四个面板：一个面板只做一件事，翻页也就不需要了。
        const boutActions = (panelId) => ({
          'bouts.refresh': (scope) => {
            d.refresh(panelId, scope)
            return { toast: '' }
          },
          'bouts.open': (scope, event) => {
            const r = startBout(values(event)['data.form.protocolId'])
            d.refresh(panelId, scope)
            return { toast: r.toast }
          },
          'bouts.answer': (scope, event) => {
            const v = values(event)
            const r = answerBout(v['data.form.answer'], v['data.form.ref'] ? 'photo' : 'text')
            d.refresh(panelId, scope)
            return { toast: r.toast }
          },
          'bouts.close': (scope) => {
            const r = closeBoutNow()
            d.refresh(panelId, scope)
            return { toast: r.toast }
          },
        })
        for (const [panelId, view] of [
          ['bouts', boutsNowView],
          ['bouts-queue', boutsQueueView],
          ['bouts-protocols', boutsProtocolsView],
          ['bouts-books', boutsBooksView],
        ]) {
          d.panel(panelId, {
            view,
            data: (scope, emit) => {
              panelScopes.set(keyOf(scope), scope)
              emit(boutsSnapshot())
              return () => panelScopes.delete(keyOf(scope))
            },
            actions: boutActions(panelId),
          })
        }
        // 墙：控制塔。只读、只有刷新——面板不能自己解锁、不能改阈值
        //（阈值在设定页，改松还要过冷却期，见 settings.js）。申诉走它自己那个入口。
        const wallActions = (panelId) => ({
          'wall.refresh': (scope) => {
            d.refresh(panelId, scope)
            return { toast: '' }
          },
        })
        for (const [panelId, view] of [['wall', wallView], ['wall-audit', wallAuditView]]) {
          d.panel(panelId, {
            view,
            data: (scope, emit) => {
              panelScopes.set(keyOf(scope), scope)
              emit(wallSnapshot())
              return () => panelScopes.delete(keyOf(scope))
            },
            actions: wallActions(panelId),
          })
        }
        // 设定：只能改数，不能在这里解锁或改判定。解析不出来的整份退回，不做部分提交。
        const settingsActions = (panelId, parse) => ({
          'settings.refresh': (scope) => {
            d.refresh(panelId, scope)
            return { toast: '' }
          },
          'settings.save': (scope, event) => {
            const r = parse(settingsOf(config), values(event))
            if (r.error) return { toast: r.error }
            const saved = saveSettings(r.next, '设定页')
            d.refresh(panelId, scope)
            return { toast: saved.applied === 'pending' ? '改松了，挂一天冷却期；这一天先按原来的走。' : '改好了，立即生效。' }
          },
        })
        for (const [panelId, view, parse, id] of [
          ['settings', settingsDayView, parseDay, 'settings.saveDay'],
          ['settings-limits', settingsLimitsView, parseLimits, 'settings.saveLimits'],
        ]) {
          const actions = settingsActions(panelId, parse)
          // 表单的 submit 名就是每个页各自那一个（两个页面共用一个保存实现）。
          d.panel(panelId, {
            view,
            data: (scope, emit) => {
              panelScopes.set(keyOf(scope), scope)
              emit(settingsSnapshot())
              return () => panelScopes.delete(keyOf(scope))
            },
            actions: { ...actions, [id]: actions['settings.save'] },
          })
        }
      })
    } catch (error) {
      console.error('[companion] 面板注册失败：' + String((error && error.message) || error))
    }
  }

  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(
      () => webCtx.webServer.register({ kind: 'prefix', path: PREFIX, handler }),
      'dsh-go-companion: routes',
    )
    console.error('[companion] routes ' + PREFIX)
  })

  // 启动时确保 Obsidian 个人模板数据库文件就位，并把导入目录里的个人记忆条目自动同步进 SQLite（不阻塞插件加载）。
  const initImportTimer = setTimeout(() => {
    try {
      const r = importVaultMemoriesRoute({})
      if (r.imported > 0 || r.createdFiles.length > 0) {
        console.error('[companion] memory auto-sync: imported=' + r.imported + ' created=' + r.createdFiles.length)
      }
    } catch {
      // ignore startup import failure
    }
  }, 800)
  initImportTimer.unref?.()
}
