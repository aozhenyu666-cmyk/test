/**
 * 大肥鱼 × Gemini Live 窥视常驻与知识灌料总调度核心 (V1 Core)。
 *
 * 设计铁律：
 * 1. 零影子锁：绝不在 unified_arbiter (P0-P6) 之外另造私锁；所有裁决只读 arbiter，冲突时严格让路。
 * 2. 填平「常规锁下仍摸鱼」漏洞：白天 P4 防分心锁生效期间若用户未刷题/未学而是发呆/听歌/切其他 App，主动点火进 Live。
 * 3. 让路闸门：P0 紧急放行、P1 深夜、P1.2 放风、计划模态弹窗、正在刷题或工作学习、已在 Gemini Live 内、静音期内绝不误触。
 * 4. 6 面骰子只是「1 挡火花塞」，后台把设定、屏幕摘录、笔记、错题/FSRS 与【禁聊体系死命令】一起灌进官方 Gemini Live。
 */

import { collectAnchorCandidates, pickWeightedCandidate } from './super_spark.js'
import { DEFAULT_GEMINI_SETTING, RECEIPT_FORMAT, settingLines } from './trigger.js'
import { hm as zoneHm, ymd as zoneYmd, zoneOr } from './tz.js'

export const LIVE_CORE_DEFAULTS = Object.freeze({
  reengageMinDropMs: 90_000,
  reengageMaxDropMs: 20 * 60_000,
  autoCooldownMs: 12 * 60_000,
  idleTriggerMin: 3,
  musicTriggerMin: 5,
  muteDurationMs: 20 * 60_000,
  recentMax: 8,
  quizReliefThreshold: 5,
  yieldLogDedupMs: 10 * 60_000,
  historyLimit: 40,
})

export const LIVE_REASON_LABELS = Object.freeze({
  gaze_ignite_under_lock: '白天防分心窗空转点火',
  live_reengage: 'Live 断线未复盘拉回',
  manual_dice_roll: '手动摇骰灌料',
  dice_spark_live: '走神/听歌触发点火',
  active_study_or_quiz: '正在刷题或学习 · 自动让路',
  quiz_relief_valve: '连刷 5 题触发静音泄压 20m',
  plan_modal_priority: '计划模态优先 · 让路',
  sleeping: '深夜睡眠守护 · 让路',
  break: '合法放风时段 · 让路',
  emergency: '应急放行 · 让路',
  live_running: '已在 Gemini Live 中',
  manual_mute: '手动静音',
  muted: '静音冷却中 · 让路',
  screen_off: '息屏待机 · 让路',
  meal_or_quiet: '用餐或安静时段 · 让路',
  disabled: '总调度已关闭',
  cooldown: '自动点火冷却中',
  standby: '窥视待命',
})

export const LIVE_DICE_FACES = Object.freeze([
  {
    face: 1,
    key: 'gaze_check',
    name: '窥屏现抓',
    roleHint: '先盯住屏幕当下画面，把走神或闲逛当场拉回一件 5 分钟可见产出',
    ask: '不管你刚才在划什么或听什么，先用 1 句话报出你屏幕上现在是什么，以及接下来 5 分钟能交出的最小动作。',
  },
  {
    face: 2,
    key: 'vault_recall',
    name: '笔记翻旧账',
    roleHint: '从 Obsidian 近期笔记或长期主线里抽 1 条，逼他 30 秒口述反例',
    ask: '不准翻原文，30 秒内用自己的话复述这条知识的核心结论，并自己挑出 1 个最容易忽视的反例。',
  },
  {
    face: 3,
    key: 'drill_blitz',
    name: '错题追击战',
    roleHint: '直接从错题本或 FSRS 到期记忆卡抽 1 题，无纸笔口头秒判',
    ask: '不用找笔也不用找桌子，直接口头说清这道抽考题的坑点在哪、正确判定条件是什么。',
  },
  {
    face: 4,
    key: 'copy_supervise',
    name: '微抄写盯盘',
    roleHint: '派 1 个 3 分钟极短手写/抄写或列提纲任务，开着屏幕或摄像头盯着做到',
    ask: '别光在脑子里空转，现在用 3 分钟手写抄录 3 条公式/错题骨架（或在屏幕写 3 句提纲），开着 Live 盯着你写完报交货。',
  },
  {
    face: 5,
    key: 'muscle_debate',
    name: '思维肌肉拆解',
    roleHint: '针对他不愿启动复杂思考的弱点，抛 1 个窄问题逼他口述 2 步因果',
    ask: '只花 2 分钟把「抗拒思考的肌肉」拉上线：针对这道题或这件事，说出 A->B 因果链条里的唯一卡点，不许说“差不多”。',
  },
  {
    face: 6,
    key: 'relay_contract',
    name: '25m 接力立契',
    roleHint: '30 秒破冰聊顺后，顺手拍板下一个 25 分钟微验收单',
    ask: '嘴巴已经张开了，别白聊：顺手拍板接下来 25 分钟——选 A（纯刷题 15 道）还是 B（提炼 1 段笔记/真题）？几点前交货？',
  },
])

function cleanLine(raw, maxLen = 160) {
  return String(raw || '').replace(/\s+/g, ' ').trim().slice(0, maxLen)
}

function cleanIdList(raw, limit = LIVE_CORE_DEFAULTS.recentMax) {
  return [...new Set((Array.isArray(raw) ? raw : []).map((x) => String(x || '').trim()).filter(Boolean))].slice(-limit)
}

function cleanDiceList(raw, limit = 3) {
  return (Array.isArray(raw) ? raw : [])
    .map((x) => Number(x))
    .filter((n) => Number.isInteger(n) && n >= 1 && n <= 6)
    .slice(-limit)
}

function normalizeRecap(raw) {
  if (!raw || typeof raw !== 'object') return null
  const summary = cleanLine(raw.summary, 180)
  const next = cleanLine(raw.next, 100)
  const mode = String(raw.mode || '').trim()
  if (!summary && !next && !mode) return null
  return {
    at: Math.max(0, Number(raw.at) || 0),
    summary,
    next,
    mode,
  }
}

function normalizeLiveState(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  return {
    v: 1,
    enabled: s.enabled !== false,
    autoReengage: s.autoReengage !== false,
    wantScreenShare: s.wantScreenShare !== false,
    autoCooldownMs: Math.max(60_000, Number(s.autoCooldownMs) || LIVE_CORE_DEFAULTS.autoCooldownMs),
    mutedUntil: Math.max(0, Number(s.mutedUntil) || 0),
    lastVoiceActiveAt: Math.max(0, Number(s.lastVoiceActiveAt) || 0),
    lastVoiceDropHandledAt: Math.max(0, Number(s.lastVoiceDropHandledAt) || 0),
    lastIgniteAt: Math.max(0, Number(s.lastIgniteAt) || 0),
    lastQuizReliefAt: Math.max(0, Number(s.lastQuizReliefAt) || 0),
    receiptSinceLastLive: Boolean(s.receiptSinceLastLive),
    recentAnchorIds: cleanIdList(s.recentAnchorIds),
    recentDice: cleanDiceList(s.recentDice),
    lastPhase: String(s.lastPhase || 'standby'),
    lastReason: String(s.lastReason || ''),
    lastPayload: s.lastPayload && typeof s.lastPayload === 'object' ? s.lastPayload : null,
    lastRecap: normalizeRecap(s.lastRecap),
    lastYieldLoggedAt: Math.max(0, Number(s.lastYieldLoggedAt) || 0),
    lastYieldLoggedReason: String(s.lastYieldLoggedReason || ''),
    quizReliefCount: Math.max(0, Number(s.quizReliefCount) || 0),
  }
}

function pickDiceFace({ diceHint = 0, modeHint = '', recentDice = [], rng = Math.random, reason = '' } = {}) {
  const hintNum = Number(diceHint)
  if (Number.isInteger(hintNum) && hintNum >= 1 && hintNum <= 6) {
    return LIVE_DICE_FACES[hintNum - 1]
  }
  const byKey = LIVE_DICE_FACES.find((d) => d.key === String(modeHint || '').trim())
  if (byKey) return byKey
  if (reason === 'gaze_ignite_under_lock') {
    const gazeBias = typeof rng === 'function' ? Number(rng()) : Math.random()
    if (gazeBias < 0.45 && !recentDice.slice(-1).includes(1)) return LIVE_DICE_FACES[0]
  }
  const recentSet = new Set(recentDice.slice(-2))
  let pool = LIVE_DICE_FACES.filter((d) => !recentSet.has(d.face))
  if (!pool.length) pool = [...LIVE_DICE_FACES]
  const rRaw = typeof rng === 'function' ? Number(rng()) : Math.random()
  const r = Number.isFinite(rRaw) ? Math.min(0.999999, Math.max(0, rRaw)) : 0.5
  return pool[Math.floor(r * pool.length)] || LIVE_DICE_FACES[0]
}

/**
 * 拼装发给官方 Gemini Live 的总控灌料剧本（<= 2800 字）。
 */
export function buildLiveCoreBrief({
  dice,
  anchor,
  settingText = '',
  phone = null,
  vaultSnippets = [],
  commitments = [],
  recap = null,
  bridge = false,
  wantScreenShare = true,
  reason = '',
} = {}) {
  const face = dice || LIVE_DICE_FACES[0]
  const setting = settingLines(settingText || DEFAULT_GEMINI_SETTING, 6)
  const frontLabel = cleanLine(phone?.label || phone?.app || '当前桌面/未知界面', 40)
  const frontMin = Math.max(0, Math.round(Number(phone?.minutes) || 0))
  const screenExcerpt = cleanLine(phone?.screenText || '', 240)
  const sceneFlags = [
    phone?.mediaPlaying ? '正在播放音乐/音频' : '',
    phone?.headsetConnected ? '已戴耳机/蓝牙音频接入' : '',
    phone?.fun ? '停留在娱乐/非学习区' : '',
    reason === 'live_reengage' ? '刚才 Live 中途退出未交复盘，现被重新拉回' : '',
    reason === 'gaze_ignite_under_lock' ? '处于白天工作保护锁窗却闲置空转' : '',
  ].filter(Boolean)

  const lines = [
    '【禁聊体系死命令】',
    '严禁陪聊情绪自怨自艾、方法论哲理、找自习室借口或重新讨论怎么搭建体系！',
    '读完背景只回一句「收到，直接开始」，第一秒从本轮骰子脚本与真实考题开考。',
    '',
    '【核心设定（Gemini设定.md）】',
    ...setting.map((s) => `- ${s}`),
    '',
    '【窥视与当下现场（DSH 实时感知）】',
    `- 前台界面：${frontLabel}${frontMin > 0 ? `（已停留 ${frontMin} 分钟）` : ''}`,
    ...(sceneFlags.length ? [`- 现场状态：${sceneFlags.join('；')}`] : []),
    ...(screenExcerpt ? [`- 屏幕摘录：${screenExcerpt}`] : []),
    ...(wantScreenShare
      ? ['- 窥视介入指令：请主动让他点「共享屏幕」或开摄像头让你看见当下画面；一旦看见他切去刷视频/发呆/看券，直接打断拆穿并拉回当下第一步。']
      : ['- 现场介入指令：如看不清他在干嘛，先抛 1 句具体问题核实屏幕内容，别猜着夸他。']),
    '',
    `【本轮骰${face.face}玩法 · ${face.name}】`,
    `- 策略定位：${face.roleHint}`,
    `- 破冰切入：${face.ask}`,
    '',
    '【真实知识弹药】',
    `- 来源与主题：${cleanLine(anchor?.sourceLabel || anchor?.anchorSource || '高频题池', 32)} · ${cleanLine(anchor?.topic || '判断推理口头抽考', 56)}`,
    `- 核心考题/材料：${cleanLine(anchor?.prompt || '用 15 秒拆出“加强论证”里排他对比与解释原因的区别。', 220)}`,
    `- 判分底牌：${cleanLine(anchor?.answerKey || '答对核心条件或举出 1 个准确反例才算过。', 160)}`,
  ]

  const extraSnippets = (Array.isArray(vaultSnippets) ? vaultSnippets : [])
    .map((x) => cleanLine(typeof x === 'string' ? x : (x?.title ? `${x.title}: ${x.snippet || x.summary || ''}` : ''), 110))
    .filter(Boolean)
    .slice(0, 2)
  if (extraSnippets.length) {
    lines.push('- 笔记摘要：' + extraSnippets.join(' | '))
  }

  const commitList = (Array.isArray(commitments) ? commitments : [])
    .map((c) => cleanLine(c, 90))
    .filter(Boolean)
    .slice(0, 2)
  if (commitList.length) {
    lines.push('- 待兑现承诺：' + commitList.join('；'))
  }

  if (recap?.summary) {
    lines.push(`- 上轮连麦复盘：${cleanLine(recap.summary, 120)}${recap.next ? `；原定下一步：${cleanLine(recap.next, 70)}` : ''}`)
  }

  if (bridge) {
    lines.push(
      '',
      '【MCP / 大肥鱼桥工具联动】',
      '- 开聊优先调用 dafeiyu_today 对齐今天验收点与时间账；',
      '- 他口头定下下一步 25 分钟任务或抄写产出后，调用 dafeiyu_add_checkpoints 写入（只增不改小）；',
      '- 结束时先追问 2 个问题拿到他的原话回答，再调用 dafeiyu_submit_receipt 交回执。',
    )
  }

  lines.push(
    '',
    '【三挡节拍（3-5 分钟高强度互动）】',
    '1. 1 挡（0-30s 破冰）：别喊口号，顺着上面的画面或口语钩子先让他开口接 1 句。',
    '2. 2 挡（30s-4m 练肌肉）：立刻切入「真实知识弹药」或「微抄写盯盘」，连续追问 2 轮不许含糊。',
    '3. 3 挡（收口立契）：结束前逼他二选一拍板下一个 25 分钟验收动作，并严格按格式输出：',
    RECEIPT_FORMAT,
  )

  return lines.join('\n').slice(0, 2800)
}

/**
 * 纯函数评估大肥鱼 × Gemini Live 总调度状态。
 */
export function evaluateLiveCore({
  now = Date.now(),
  state = null,
  config = null,
  phone = null,
  arbiter = null,
  planModal = null,
  mealOrQuiet = false,
  inGemini = false,
  runningQuiz = false,
  recentQuizAnswers = 0,
  fgTier = 'other',
  bankState = null,
  questionBank = [],
  reviewCards = [],
  horizons = null,
  vault = null,
  vaultSnippets = [],
  settingText = '',
  commitments = [],
  recap = null,
  bridge = false,
  modeHint = '',
  diceHint = 0,
  force = false,
  rng = Math.random,
} = {}) {
  const ts = Math.max(0, Number(now) || Date.now())
  const st = normalizeLiveState(state)
  const voiceActiveNow = Boolean(phone?.geminiVoiceActive)
  const explicitDropAgoMs = Math.max(0, Number(phone?.liveDroppedAgoMs) || 0)
  const inferredDropAgoMs = (!voiceActiveNow && st.lastVoiceActiveAt > 0) ? Math.max(0, ts - st.lastVoiceActiveAt) : 0
  const dropAgoMs = explicitDropAgoMs > 0 ? explicitDropAgoMs : inferredDropAgoMs

  const nextBase = {
    ...st,
    lastVoiceActiveAt: voiceActiveNow ? ts : st.lastVoiceActiveAt,
    receiptSinceLastLive: voiceActiveNow ? false : st.receiptSinceLastLive,
  }

  const yieldWith = (reason, phase, patchState = null) => ({
    triggered: false,
    action: 'yield',
    reason,
    phase,
    dice: null,
    anchor: null,
    voiceLine: '',
    topic: '',
    brief: '',
    autoClickLive: false,
    requestScreenShare: false,
    pullMode: 'instant',
    nextState: {
      ...nextBase,
      ...(patchState && typeof patchState === 'object' ? patchState : {}),
      lastPhase: phase,
      lastReason: reason,
    },
  })

  if (!force) {
    if (!st.enabled || config?.control === false) return yieldWith('disabled', 'disabled')
    if (arbiter?.priority === 'P0' || config?.emergencyActive) return yieldWith('emergency', 'yielded_emergency')
    if (arbiter?.priority === 'P1') return yieldWith('sleeping', 'yielded_night')
    if (arbiter?.priority === 'P1.2') return yieldWith('break', 'yielded_break')
    if (planModal?.active) return yieldWith('plan_modal_priority', 'yielded_plan_modal')
    if (mealOrQuiet) return yieldWith('meal_or_quiet', 'standby')
    if (voiceActiveNow || inGemini) return yieldWith('live_running', 'live_active')
    if (phone?.screenOn === false) return yieldWith('screen_off', 'standby')

    const quizCount = Math.max(0, Number(recentQuizAnswers) || 0)
    if (quizCount >= LIVE_CORE_DEFAULTS.quizReliefThreshold && st.mutedUntil <= ts) {
      return yieldWith('quiz_relief_valve', 'yielded_quiz_relief', {
        mutedUntil: ts + LIVE_CORE_DEFAULTS.muteDurationMs,
        quizReliefCount: st.quizReliefCount + 1,
        lastQuizReliefAt: ts,
      })
    }

    const fgTierNorm = String(fgTier || '').trim().toLowerCase()
    const idleMin = Math.max(0, Number(phone?.minutes) || 0)
    const mediaPlaying = Boolean(phone?.mediaPlaying)
    const mediaOnlyDrift = mediaPlaying && fgTierNorm !== 'work'
    const activeStudyOrQuiz = Boolean(runningQuiz) || (fgTierNorm === 'work' && idleMin < 10 && !mediaOnlyDrift && !phone?.fun)
    if (activeStudyOrQuiz) return yieldWith('active_study_or_quiz', 'yielded_studying')

    if (st.mutedUntil > ts || Number(config?.muteUntil) > ts) return yieldWith('muted', 'muted')

    const canReengage = Boolean(
      st.autoReengage &&
      st.lastVoiceActiveAt > 0 &&
      st.lastVoiceActiveAt > st.lastVoiceDropHandledAt &&
      !voiceActiveNow &&
      !st.receiptSinceLastLive &&
      dropAgoMs >= LIVE_CORE_DEFAULTS.reengageMinDropMs &&
      dropAgoMs <= LIVE_CORE_DEFAULTS.reengageMaxDropMs,
    )

    if (!canReengage && st.lastIgniteAt > 0 && ts - st.lastIgniteAt < st.autoCooldownMs) {
      return yieldWith('cooldown', 'standby')
    }

    let triggerReason = ''
    if (canReengage) {
      triggerReason = 'live_reengage'
    } else if (
      (arbiter?.priority === 'P4' || arbiter?.source === 'DAY_WORK_WALL' || arbiter?.source === 'BOUT_STRICT') &&
      (idleMin >= LIVE_CORE_DEFAULTS.idleTriggerMin || mediaPlaying || phone?.fun || ['fun', 'game', 'other'].includes(fgTierNorm))
    ) {
      triggerReason = 'gaze_ignite_under_lock'
    } else if (
      phone?.fun ||
      (mediaPlaying && idleMin >= LIVE_CORE_DEFAULTS.musicTriggerMin) ||
      (phone?.headsetConnected && idleMin >= 2) ||
      (['fun', 'game', 'other'].includes(fgTierNorm) && idleMin >= LIVE_CORE_DEFAULTS.idleTriggerMin)
    ) {
      triggerReason = 'dice_spark_live'
    }

    if (!triggerReason) return yieldWith('standby', 'standby')

    return buildTriggeredResult({
      ts,
      nextBase,
      reason: triggerReason,
      action: triggerReason === 'live_reengage' ? 'reengage' : 'ignite',
      bankState,
      questionBank,
      reviewCards,
      horizons,
      vault,
      vaultSnippets,
      settingText,
      phone,
      commitments,
      recap,
      bridge,
      modeHint,
      diceHint,
      rng,
    })
  }

  return buildTriggeredResult({
    ts,
    nextBase,
    reason: 'manual_dice_roll',
    action: 'roll_dice',
    bankState,
    questionBank,
    reviewCards,
    horizons,
    vault,
    vaultSnippets,
    settingText,
    phone,
    commitments,
    recap,
    bridge,
    modeHint,
    diceHint,
    rng,
  })
}

function buildTriggeredResult({
  ts,
  nextBase,
  reason,
  action,
  bankState,
  questionBank,
  reviewCards,
  horizons,
  vault,
  vaultSnippets,
  settingText,
  phone,
  commitments,
  recap,
  bridge,
  modeHint,
  diceHint,
  rng,
}) {
  const mergedSnippets = [
    ...(Array.isArray(vaultSnippets) ? vaultSnippets : []),
    ...(Array.isArray(vault?.recent) ? vault.recent : []),
  ]
  const candidates = collectAnchorCandidates({
    bankState,
    questionBank,
    reviewCards,
    horizons,
    vault,
    vaultSnippets: mergedSnippets,
    now: ts,
  })
  const anchor = pickWeightedCandidate(candidates, nextBase.recentAnchorIds, rng)
  const dice = pickDiceFace({
    diceHint,
    modeHint,
    recentDice: nextBase.recentDice,
    rng,
    reason,
  })
  const brief = buildLiveCoreBrief({
    dice,
    anchor,
    settingText,
    phone,
    vaultSnippets: mergedSnippets,
    commitments,
    recap,
    bridge,
    wantScreenShare: nextBase.wantScreenShare,
    reason,
  })
  const shortAsk = cleanLine(anchor?.prompt || dice.ask, 64)
  const voiceLine = reason === 'live_reengage'
    ? `刚才连麦断了，别直接散掉，回 Live 接一句：${cleanLine(dice.ask, 54)}`
    : `骰${dice.face}${dice.name}。${shortAsk}`
  const topic = `骰${dice.face}·${dice.name}：${cleanLine(anchor?.topic || '高密度拆题', 40)}`
  const payload = {
    id: `live-${ts.toString(36)}-${dice.face}`,
    at: ts,
    reason,
    action,
    dice,
    anchorId: anchor?.id || '',
    anchorSource: anchor?.anchorSource || 'exam_pool',
    topic,
    voiceLine,
    brief,
    autoClickLive: true,
    requestScreenShare: Boolean(nextBase.wantScreenShare),
    pullMode: 'instant',
  }
  const nextState = {
    ...nextBase,
    lastIgniteAt: ts,
    lastVoiceDropHandledAt: reason === 'live_reengage' ? ts : nextBase.lastVoiceDropHandledAt,
    recentAnchorIds: cleanIdList([...nextBase.recentAnchorIds, anchor?.id], LIVE_CORE_DEFAULTS.recentMax),
    recentDice: cleanDiceList([...nextBase.recentDice, dice.face], 3),
    lastPhase: 'ignited',
    lastReason: reason,
    lastPayload: payload,
  }
  return {
    triggered: true,
    action,
    reason,
    phase: 'ignited',
    dice,
    anchor,
    voiceLine,
    topic,
    brief,
    autoClickLive: true,
    requestScreenShare: Boolean(nextBase.wantScreenShare),
    pullMode: 'instant',
    payload,
    nextState,
  }
}

export function reasonLabelOf(reason) {
  const key = String(reason || '').trim()
  return LIVE_REASON_LABELS[key] || key || '未知状态'
}

function normalizeHistoryEntry(entry, idx, tzName) {
  if (!entry || typeof entry !== 'object') return null
  const st = String(entry.state || '').trim()
  const at = Math.max(0, Number(entry.at) || 0)
  if (!at) return null
  const hm = zoneHm(at, tzName)

  if (st === 'live-core-ignite') {
    const face = Number(entry.diceFace) || 0
    const diceObj = face >= 1 && face <= 6 ? LIVE_DICE_FACES[face - 1] : null
    const reason = String(entry.reason || '')
    const action = String(entry.action || 'ignite')
    return {
      id: `lc-ig-${at}-${idx}`,
      at,
      hm,
      kind: 'ignite',
      badge: action === 'reengage' ? '断线拉回' : (action === 'roll_dice' ? '手动摇骰' : '窥视点火'),
      title: diceObj ? `骰${diceObj.face} · ${diceObj.name}` : 'Gemini Live 点火',
      detail: reasonLabelOf(reason) + (entry.anchorId ? ` · 锚点 ${cleanLine(entry.anchorId, 28)}` : ''),
      diceFace: face,
      reason,
    }
  }

  if (st === 'super-spark') {
    const mode = String(entry.mode || '')
    const src = String(entry.anchorSource || '')
    return {
      id: `lc-sp-${at}-${idx}`,
      at,
      hm,
      kind: 'ignite',
      badge: '火花点火',
      title: src === 'vault' ? '笔记记忆抽考' : '错题/待复习火花',
      detail: mode ? reasonLabelOf(mode) : '后台碎片定时破冰',
      diceFace: 0,
      reason: mode || 'super_spark',
    }
  }

  if (st === 'live-core-yield') {
    const reason = String(entry.reason || '')
    return {
      id: `lc-yd-${at}-${idx}`,
      at,
      hm,
      kind: 'yield',
      badge: '自动让路',
      title: reasonLabelOf(reason),
      detail: entry.phase ? `裁决阶段：${cleanLine(entry.phase, 32)}` : '不抢屏、让给当前正事',
      diceFace: 0,
      reason,
    }
  }

  if (st === 'live-core-mute') {
    const mins = Math.max(1, Number(entry.minutes) || 20)
    return {
      id: `lc-mt-${at}-${idx}`,
      at,
      hm,
      kind: 'mute',
      badge: '静音泄压',
      title: `静音 ${mins} 分钟`,
      detail: '期间暂停自动拉起 Gemini Live',
      diceFace: 0,
      reason: 'manual_mute',
    }
  }

  if (st === 'live-core-relay') {
    const mode = String(entry.mode || 'checkpoint_25m')
    const mins = Math.max(5, Number(entry.minutes) || 25)
    const title = mode === 'quiz_only'
      ? `接力进 ${mins}m 纯刷题舱`
      : (mode === 'recap_only' ? '记录本轮 Live 复盘' : `签下 ${mins}m 微验收点`)
    const detail = cleanLine(entry.next || entry.summary || '已完成本轮 Live 交接', 80)
    return {
      id: `lc-rl-${at}-${idx}`,
      at,
      hm,
      kind: 'relay',
      badge: '接力回流',
      title,
      detail,
      diceFace: 0,
      reason: mode,
    }
  }

  if (st === 'reverse-capsule') {
    const mode = String(entry.mode || 'quiz_only')
    const mins = Math.max(5, Number(entry.minutes) || 20)
    const label = mode === 'gemini_only' ? '只留 Gemini 舱' : (mode === 'quiz_only' ? '纯考公刷题舱' : '定制专注舱')
    return {
      id: `lc-cp-${at}-${idx}`,
      at,
      hm,
      kind: 'capsule',
      badge: '反向单开舱',
      title: `${label} · ${mins} 分钟`,
      detail: '严控白名单护航',
      diceFace: 0,
      reason: `capsule_${mode}`,
    }
  }

  if (st === 'checkpoints-by-ai') {
    const n = Math.max(0, Number(entry.added) || 0)
    if (n <= 0) return null
    return {
      id: `lc-ai-${at}-${idx}`,
      at,
      hm,
      kind: 'relay',
      badge: '写入验收点',
      title: `Gemini 写入 ${n} 个验收点`,
      detail: '已同步至今日时间轴与验收锁',
      diceFace: 0,
      reason: 'checkpoints_by_ai',
    }
  }

  return null
}

/**
 * 格式化结构化运行记录（倒序），支持 all / ignite / yield / relay 过滤。
 */
export function formatLiveCoreHistory(
  ledgerEntries = [],
  { limit = LIVE_CORE_DEFAULTS.historyLimit, filter = 'all', tz = 'Asia/Shanghai' } = {},
) {
  const tzName = zoneOr(tz)
  const normFilter = String(filter || 'all').trim().toLowerCase()
  const maxItems = Math.max(1, Math.min(100, Number(limit) || LIVE_CORE_DEFAULTS.historyLimit))
  const list = Array.isArray(ledgerEntries) ? ledgerEntries : []
  const out = []

  for (let i = 0; i < list.length; i += 1) {
    const item = normalizeHistoryEntry(list[i], i, tzName)
    if (!item) continue
    if (normFilter === 'ignite' && item.kind !== 'ignite') continue
    if (normFilter === 'yield' && item.kind !== 'yield' && item.kind !== 'mute') continue
    if (normFilter === 'relay' && item.kind !== 'relay' && item.kind !== 'capsule') continue
    out.push(item)
  }

  out.sort((a, b) => b.at - a.at)
  return out.slice(0, maxItems)
}

/**
 * 构建极简直观的功能图谱（4 大闭环能力 + P0–P6 零冲突梯子 + 今日计数）。
 */
export function buildLiveCoreAtlas({
  now = Date.now(),
  liveCore = null,
  arbiter = null,
  bridgeHealthy = false,
  capabilities = null,
  todayStats = null,
  bankStats = null,
  planState = null,
  history = [],
  tz = 'Asia/Shanghai',
} = {}) {
  const ts = Math.max(0, Number(now) || Date.now())
  const tzName = zoneOr(tz)
  const todayStr = zoneYmd(ts, tzName)
  const lc = liveCore && typeof liveCore === 'object' ? liveCore : {}
  const hist = Array.isArray(history) ? history : []

  let ignites = 0
  let reengages = 0
  let yields = 0
  let relays = 0
  let capsules = 0
  for (const item of hist) {
    if (!item || typeof item !== 'object') continue
    if (item.kind === 'ignite') {
      ignites += 1
      if (item.reason === 'live_reengage') reengages += 1
    } else if (item.kind === 'yield' || item.kind === 'mute') {
      yields += 1
    } else if (item.kind === 'relay') {
      relays += 1
    } else if (item.kind === 'capsule') {
      capsules += 1
    }
  }

  const dailyHist = bankStats?.stats?.dailyHistory?.[todayStr]
  const quizAnsweredToday = Math.max(
    0,
    Number(
      todayStats?.quizAnsweredToday ??
      bankStats?.todayAnswered ??
      dailyHist?.answered ??
      bankStats?.session?.solved ??
      0,
    ) || 0,
  )
  const liveMinutesToday = Math.max(0, Math.round(Number(todayStats?.liveMinutesToday) || 0))

  const todaySeals = (Array.isArray(planState?.items) ? planState.items : []).filter((s) => s && s.day === todayStr && s.status !== 'waived')
  const phase = String(lc.phase || 'standby')
  const activePrio = String(arbiter?.priority || 'P6')

  const igniteStatus = lc.enabled === false
    ? 'off'
    : (lc.muted ? 'yielded' : (phase === 'live_active' || phase === 'ignited' ? 'active' : (phase.startsWith('yielded') ? 'yielded' : 'ready')))
  const locksStatus = arbiter?.active ? 'active' : 'ready'
  const planStatus = activePrio === 'P3' ? 'active' : (todaySeals.length > 0 ? 'ready' : 'ready')
  const quizStatus = lc.reason === 'quiz_relief_valve' ? 'yielded' : (quizAnsweredToday > 0 ? 'active' : 'ready')

  const pipeline = [
    {
      step: 1,
      key: 'sense',
      label: '感知现场',
      status: capabilities?.a11y ? 'ready' : 'needs_setup',
    },
    {
      step: 2,
      key: 'dice',
      label: '骰子灌料',
      status: lc.dice?.face ? 'active' : 'ready',
    },
    {
      step: 3,
      key: 'live',
      label: '官方 Live',
      status: phase === 'live_active' ? 'active' : (bridgeHealthy ? 'ready' : 'fallback'),
    },
    {
      step: 4,
      key: 'relay',
      label: '25m 接力',
      status: relays + capsules > 0 ? 'active' : 'ready',
    },
  ]

  const pillars = [
    {
      id: 'ignite',
      title: '① 窥视点火（1 颗骰子）',
      tagline: '后台整合设定/错题/笔记/屏幕摘录，一键灌进官方 Gemini Live',
      triggerRule: '白天防分心窗空转/听歌/断线未复盘时自动点火',
      yieldRule: '刷题/学习/睡眠/计划模态/连刷 5 题时绝对让路',
      status: igniteStatus,
      statusLabel: igniteStatus === 'active'
        ? (phase === 'live_active' ? 'Live 在线' : '已点火')
        : (igniteStatus === 'yielded' ? '已让路' : (igniteStatus === 'off' ? '已关闭' : '待命中')),
      metricText: `今日点火 ${ignites} 次 · 拉回 ${reengages} 次`,
      route: 'live_core',
    },
    {
      id: 'locks',
      title: '② 零冲突锁仲裁（P0–P6 · 6 只手）',
      tagline: '单一裁判裁决优先级，绝无影子锁互相打架',
      triggerRule: '按 P0 急事 > P1 睡眠 > P2 连麦舱 > P3 逾期验收 > P4 专心局/防分心 > P6 余额 裁决',
      yieldRule: '应急通道、电话/短信/支付/导航永不误锁',
      status: locksStatus,
      statusLabel: arbiter?.active ? `${activePrio} 生效中` : '空闲守望',
      metricText: arbiter?.label ? `当前：${cleanLine(arbiter.label, 24)}` : '当前：暂无强锁',
      route: 'locks',
    },
    {
      id: 'plan',
      title: '③ 计划模态与验收锁（25m 接力）',
      tagline: '先定今日计划再启动验收锁；Live 聊完可 1 键签 25m 微验收点',
      triggerRule: '未定计划时温和拉回，到点未交按节点转 P3 验收锁',
      yieldRule: '已过保底或放风/午休时段不打扰',
      status: planStatus,
      statusLabel: activePrio === 'P3' ? 'P3 逾期严锁' : (todaySeals.length > 0 ? `今日 ${todaySeals.length} 个点` : '待立约'),
      metricText: `今日接力 ${relays} 次 · 单开舱 ${capsules} 次`,
      route: 'plan',
    },
    {
      id: 'quiz',
      title: '④ 刷题账本与记忆回流（10 题 5 分）',
      tagline: '刷题挣余额不被旧债吞噬，错题与 FSRS 到期卡自动灌回下次 Live',
      triggerRule: '锁屏刷题或进入 20m 纯刷题舱直接累积正向额度',
      yieldRule: '连刷 5 题自动触发 20m 静音泄压阀',
      status: quizStatus,
      statusLabel: quizStatus === 'yielded' ? '5 题泄压中' : (quizAnsweredToday > 0 ? '正产出中' : '就绪'),
      metricText: `今日刷题 ${quizAnsweredToday} 题 · Live ${liveMinutesToday}m`,
      route: 'bank',
    },
  ]

  const lockLadder = [
    { priority: 'P0', name: '应急通道 & 安全底线', rule: '3m 应急解禁、电话/短信/支付/导航永不锁', active: activePrio === 'P0' },
    { priority: 'P1', name: '睡眠守护 & 放风窗', rule: '23:00–07:00 睡眠锁或午休/合法放风窗（Live 让路）', active: activePrio === 'P1' || activePrio === 'P1.2' },
    { priority: 'P2', name: 'Gemini 连麦共脑舱', rule: '语音/口头考辩专属时段，只放行共脑应用', active: activePrio === 'P2' },
    { priority: 'P3', name: '计划验收逾期严锁', rule: '到点未交保底转严锁（不可花零散余额绕过）', active: activePrio === 'P3' || activePrio === 'P3.5' },
    { priority: 'P4', name: '专心局 & 白天防分心窗', rule: '常规白名单护航；闲逛发呆时自动点火进 Live', active: activePrio === 'P4' },
    { priority: 'P6', name: '余额门槛 & 雷达软提示', rule: '无强锁时的温和余额计量与频率约束', active: activePrio === 'P5' || activePrio === 'P6' },
  ]

  return {
    pipeline,
    pillars,
    lockLadder,
    todayMetrics: {
      ignites,
      reengages,
      yields,
      relays,
      capsules,
      quizAnsweredToday,
      liveMinutesToday,
    },
  }
}
