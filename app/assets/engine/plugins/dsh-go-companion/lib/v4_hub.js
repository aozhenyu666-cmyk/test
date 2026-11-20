// dsh-go-companion · V4 模块化总装桥接器（防臃肿设计：不往 9000 行 index.js 裸堆代码）
// 聚合：
// - bank_unlock.js (导入题库 + 10 题解锁 5 分钟)
// - autonomy.js (W-3 正向泄压自律等级 autonomyLevel)
// - horizons.js (自由口径三层主线 + 长期规划.md 双向镜像)
// - plan_modality.js (AI 协同计划模态 + 本机 App 检查 + 视频高强度提炼验收 + 1 分钟逾期转严格锁)
// - telemetry_charts.js (Today 真实战果与耗时分布图表)
// - dept_skills.js (四部门防骗红旗 / 反沉没成本 / 三段因果链可执行 Skill)

import { join } from 'node:path'
import { cleanBankUnlockState, judgeQuizAnswer, pickNextQuizQuestion } from './bank_unlock.js'
import { parseBankAny } from './bank_import.js'
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
    getArbiterEnforce = () => false,
    getLockRequests = () => [],
    getTodayBrainStats = null,
    onContractClear = null,
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
    const ledger = readLedger(home(), 1500)
    const reports = loadReportsStore()?.reports || {}
    const brainView = typeof getTodayBrainStats === 'function' ? (getTodayBrainStats(now) || {}) : {}
    const digests = Array.isArray(brainView.digests) ? brainView.digests : []
    const contract = loadContract()
    const overdueCheckpoints = (contract?.checkpoints || []).filter((c) => c.status === 'overdue_locked').length
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
    const importText = (raw, opts) => {
      const merge = Boolean(opts.append && rawBank && Array.isArray(rawBank.题目))
      const offset = merge ? rawBank.题目.length : 0
      const parsed = parseBankAny(raw, { offset })
      if (!parsed.ok) return { v: 1, ok: false, error: parsed.error, skipped: parsed.skipped.slice(0, 30), skippedCount: parsed.skipped.length, format: parsed.format }
      const name = String(opts.name || (merge ? rawBank.名称 : '') || '备考题库').slice(0, 60)
      const mergedBank = {
        题库版本: 1,
        id: merge ? (rawBank.id || 'custom-bank') : (opts.id || `bank-${now.toString(36).slice(-4)}`),
        名称: name,
        题目: merge ? [...rawBank.题目, ...parsed.questions] : parsed.questions,
      }
      saveRawBank(mergedBank)
      saveBankUnlock({ ...st, session: { solved: 0, seenIds: [], currentId: '' }, wrongIds: merge ? st.wrongIds : [] })
      record({ state: 'bank-imported', at: now, name: mergedBank.名称, total: mergedBank.题目.length, added: parsed.questions.length, skipped: parsed.skipped.length, format: parsed.format })
      return {
        v: 1,
        ok: true,
        importedCount: parsed.questions.length,
        bankTotal: mergedBank.题目.length,
        bankName: mergedBank.名称,
        format: parsed.format,
        skipped: parsed.skipped.slice(0, 30),
        skippedCount: parsed.skipped.length,
        warnings: parsed.skipped.length ? ['有 ' + parsed.skipped.length + ' 道没进：' + parsed.skipped.slice(0, 5).map((x) => '第 ' + x.n + ' 题' + x.why).join('、')] : [],
      }
    }
    if (action === 'import-finish') {
      const id = safeId(body?.uploadId)
      const dir = join(uploadDir(), id)
      let raw = ''
      try {
        raw = readdirSyncV(dir).filter((f) => f.endsWith('.part')).sort().map((f) => readFileSyncV(join(dir, f), 'utf8')).join('')
      } catch {
        return { v: 1, ok: false, error: '没找到上传的内容（超过一小时被清掉了？重新导一次）' }
      } finally {
        try {
          rmSyncV(dir, { recursive: true, force: true })
        } catch {}
      }
      return importText(raw, { append: body?.append, name: body?.name, id: body?.id })
    }
    if (action === 'import') {
      const raw = body?.raw ?? body?.text ?? body?.bank
      return importText(raw, { append: body?.append, name: body?.name, id: body?.id })
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
        try {
          onBankUnlock?.({ minutes: judged.unlockMinutes, solved: judged.solved, until: judged.unlockUntil, now })
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
    // 封印到点没过保底的锁和契约逾期是同一类（同一档、同一条通道）。
    const contractSession = overrides.contractSession !== undefined ? overrides.contractSession : (contractSessionView(now) || getSealSession(now))
    const taskLease = overrides.taskLease !== undefined ? overrides.taskLease : getTaskLease?.(now)
    // 默认验收（工作时段里什么都没开时）和专注严格局同一档，排在它后面。
    const presetStrict = overrides.presetStrict !== undefined ? overrides.presetStrict : (presetRt?.sessionView?.(now) || getVerifySession(now))
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
      arbiterEnforce: Boolean(getArbiterEnforce?.()),
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
