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
  } = deps

  const tz = () => zoneOr(getConfig()?.timeZone)
  const mdLongPath = () => archivePathInKb(kbDir(notesDir(), home()))

  const loadRawBank = () => readState(stateFile('bank.json'), null)
  const saveRawBank = (b) => writeJson(stateFile('bank.json'), b)
  const loadBankUnlock = () => cleanBankUnlockState(readState(stateFile('bank-unlock.json'), null))
  const saveBankUnlock = (s) => writeJson(stateFile('bank-unlock.json'), s)

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

  // ---- `/plan` 周期钩子：1 分钟逾期自动转严格锁 + 返回 autonomyLevel ----
  function onPlanTick(now = Date.now()) {
    const cur = loadContract()
    if (!cur) return { contract: null, autonomy: computeAutonomy(now), forceStrictByContract: false }
    const ticked = evaluateContractTick(cur, now)
    if (ticked.changed && ticked.contract) {
      saveContract(ticked.contract)
      if (ticked.newlyEscalated) {
        record({ state: 'checkpoint-overdue-locked', at: now, contractId: ticked.contract.id, title: ticked.contract.title })
        presetRt.strictNow?.(now, `契约验收点超时未交，缓冲 1 分钟已到自动转严格锁`, 'contract')
      }
    }
    return {
      contract: ticked.contract,
      graceWarning: ticked.graceWarning,
      forceStrictByContract: ticked.triggerStrictNow,
      autonomy: computeAutonomy(now),
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

    return {
      v: 1,
      ok: true,
      day: dayString(now, tzName),
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
    skillsPromptFor: (dept) => buildSkillsPromptBlock(dept),
  }
}
