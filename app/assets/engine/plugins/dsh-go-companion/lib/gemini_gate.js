// dsh-go-companion · Gemini 闸门（2026-11-19 · H3）
//
// 所有 7 路拉起 Gemini 的入口统一经过 requestGemini(...)：
//   自动拉起（drift / preset / ladder / fun-dwell-8m / switch-limit）：
//     - 两次之间至少间隔 20 分钟；
//     - 每天上限从强度表读（温和 4 / 标准 6 / 严格 8）；
//     - 连麦中不拉；连麦结束后 10 分钟内不拉；
//     - 深夜、安静时段、紧急解除期间不拉；达到硬干预日上限不拉。
//   用户主动点击（骰子连 Gemini / 任务卡按钮 / 手动工具）：
//     - 不受冷却和上限限制，只记账。
//   简报统一经 trigger.js 设定 + 当下事实 + 回执拼装。

import { geminiBrief, geminiMode as normGeminiMode } from './gemini.js'
import { contextLines, loadGeminiSetting } from './trigger.js'
import { DEFAULT_TZ, minuteOfDay as zoneMinuteOfDay, ymd as zoneYmd } from './tz.js'

export const GEMINI_AUTO_COOLDOWN_MS = 20 * 60_000
export const GEMINI_URGENT_COOLDOWN_MS = 10 * 60_000
export const GEMINI_POST_SESSION_COOLDOWN_MS = 10 * 60_000
export const GEMINI_DEFAULT_SNOOZE_MIN = 30
export const DEFAULT_GEMINI_AUTO_CAP = 10

const AUTO_SOURCES = new Set([
  'drift',
  'preset',
  'ladder',
  'fun-dwell-8m',
  'switch-limit',
  'cold-start',
  'followup',
  'bout-switch',
  'idle-fun-drift',
  'lock-bounce',
  'post-bout-or-evening',
])
const URGENT_SOURCES = new Set(['lock-bounce'])
const n = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

export function emptyGeminiGate(day = '') {
  return {
    v: 1,
    day: String(day || ''),
    autoCount: 0,
    manualCount: 0,
    lastAutoAt: 0,
    lastUrgentAutoAt: 0,
    lastManualAt: 0,
    lastSessionEndedAt: 0,
    snoozeUntil: 0,
    history: [],
  }
}

export function normalizeGeminiGate(raw, day = '') {
  const d = String(day || '')
  const s = raw && typeof raw === 'object' ? raw : null
  if (!s || (d && String(s.day || '') !== d)) {
    return emptyGeminiGate(d)
  }
  return {
    v: 1,
    day: String(s.day || d),
    autoCount: Math.max(0, Math.round(n(s.autoCount))),
    manualCount: Math.max(0, Math.round(n(s.manualCount))),
    lastAutoAt: Math.max(0, n(s.lastAutoAt)),
    lastUrgentAutoAt: Math.max(0, n(s.lastUrgentAutoAt)),
    lastManualAt: Math.max(0, n(s.lastManualAt)),
    lastSessionEndedAt: Math.max(0, n(s.lastSessionEndedAt)),
    snoozeUntil: Math.max(0, n(s.snoozeUntil)),
    history: Array.isArray(s.history) ? s.history.filter(Boolean).slice(-40) : [],
  }
}

export function markGeminiSessionEnded(rawState, { now = Date.now(), day = '' } = {}) {
  const d = String(day || new Date(now).toISOString().slice(0, 10))
  const s = normalizeGeminiGate(rawState, d)
  return {
    ...s,
    lastSessionEndedAt: Math.max(s.lastSessionEndedAt, now),
  }
}

/** 尊重用户主动延后：延后自动触发 N 分钟（默认 30m），手动拉起不受限。 */
export function snoozeGeminiAuto(rawState, minutesOrOpts = GEMINI_DEFAULT_SNOOZE_MIN, nowArg = Date.now()) {
  const isObj = minutesOrOpts && typeof minutesOrOpts === 'object'
  const minutes = Math.max(5, Math.min(240, Math.round(n(isObj ? minutesOrOpts.minutes : minutesOrOpts, GEMINI_DEFAULT_SNOOZE_MIN))))
  const now = n(isObj ? minutesOrOpts.now : nowArg, Date.now())
  const tz = String((isObj && minutesOrOpts.tz) || DEFAULT_TZ)
  const fallbackDay = rawState && typeof rawState === 'object' && rawState.day ? String(rawState.day) : zoneYmd(now, tz)
  const day = String((isObj && minutesOrOpts.day) || fallbackDay)
  const s = normalizeGeminiGate(rawState, day)
  const snoozeUntil = Math.max(s.snoozeUntil, now + minutes * 60_000)
  return {
    ...s,
    snoozeUntil,
  }
}

/** 用 trigger.js 设定/事实/回执标准化的简报拼装器。 */
export function buildStandardGeminiBrief({
  owner = '他',
  topic = '',
  minutes = 15,
  mode = 'think',
  kbDirPath = '',
  setting = '',
  front = '',
  frontMinutes = 0,
  bout = null,
  funMinutesToday = 0,
  deadlines = [],
  dice = null,
  context = null,
  about = [],
  commitments = [],
  recap = null,
  gear = null,
  bridge = false,
} = {}) {
  const resolvedSetting = setting || loadGeminiSetting(kbDirPath)
  const resolvedContext = Array.isArray(context) && context.length
    ? context
    : contextLines({ front, frontMinutes, bout, funMinutesToday, deadlines, dice })
  return geminiBrief({
    owner,
    topic,
    minutes,
    mode: normGeminiMode(mode),
    about,
    commitments,
    recap,
    gear,
    setting: resolvedSetting,
    context: resolvedContext,
    bridge,
  })
}

/**
 * 统一 Gemini 拉起闸门。
 * @param {object} rawState gemini-gate 状态
 * @param {object} opts
 * @returns {{ok:boolean, allowed:boolean, mode:'auto'|'manual', reason:string, source:string, brief:string, countToday:number, cap:number, left:number, state:object}}
 */
export function requestGemini(rawState, opts = {}) {
  const now = n(opts.now, Date.now())
  const day = String(opts.day || new Date(now).toISOString().slice(0, 10))
  const s = normalizeGeminiGate(rawState, day)
  const source = String(opts.source || 'manual').slice(0, 32)
  const mode = opts.mode === 'manual' || opts.mode === 'auto'
    ? opts.mode
    : (AUTO_SOURCES.has(source) ? 'auto' : 'manual')
  const dailyCap = Math.max(1, Math.round(n(opts.dailyCap, DEFAULT_GEMINI_AUTO_CAP)))
  const isUrgent = opts.urgent === true || URGENT_SOURCES.has(source)
  const defaultCooldown = isUrgent ? GEMINI_URGENT_COOLDOWN_MS : GEMINI_AUTO_COOLDOWN_MS
  const cooldownMs = Math.max(0, n(opts.cooldownMs, defaultCooldown))
  const postSessionCooldownMs = Math.max(0, n(opts.postSessionCooldownMs, GEMINI_POST_SESSION_COOLDOWN_MS))

  const brief = typeof opts.brief === 'string' && opts.brief.trim()
    ? opts.brief.trim()
    : buildStandardGeminiBrief({
        owner: opts.owner,
        topic: opts.topic,
        minutes: opts.minutes || 15,
        mode: opts.geminiMode || 'think',
        kbDirPath: opts.kbDirPath,
        setting: opts.setting,
        front: opts.front,
        frontMinutes: opts.frontMinutes,
        bout: opts.bout,
        funMinutesToday: opts.funMinutesToday,
        deadlines: opts.deadlines,
        dice: opts.dice,
        context: opts.context,
        about: opts.about,
        commitments: opts.commitments,
        recap: opts.recap,
        gear: opts.gear,
        bridge: opts.bridge === true,
      })

  if (mode === 'manual') {
    const entry = { at: now, source, mode: 'manual', topic: String(opts.topic || '').slice(0, 80) }
    const nextState = {
      ...s,
      manualCount: s.manualCount + 1,
      lastManualAt: now,
      history: [...s.history, entry].slice(-40),
    }
    return {
      ok: true,
      allowed: true,
      mode: 'manual',
      reason: 'manual-allowed',
      source,
      brief,
      countToday: nextState.autoCount,
      manualCountToday: nextState.manualCount,
      cap: dailyCap,
      left: Math.max(0, dailyCap - nextState.autoCount),
      state: nextState,
    }
  }

  // 自动拉起闸门检查
  const reject = (reason) => ({
    ok: false,
    allowed: false,
    mode: 'auto',
    reason,
    source,
    brief: '',
    countToday: s.autoCount,
    cap: dailyCap,
    left: Math.max(0, dailyCap - s.autoCount),
    state: s,
  })

  if (opts.emergencyActive) return reject('emergency-active')
  if (opts.isNight || opts.isQuiet) return reject('night-or-quiet')
  if (opts.pullMode === 'gentle') return reject('gentle-mode')
  if (opts.hardCapped) return reject('hard-cap-reached')
  if (opts.sessionActive) return reject('session-active')
  if (s.snoozeUntil > now) return { ...reject('snoozed'), snoozeUntil: s.snoozeUntil }

  const lastEnd = Math.max(n(opts.lastSessionEndedAt), s.lastSessionEndedAt)
  if (lastEnd > 0 && now - lastEnd < postSessionCooldownMs) {
    return reject('post-session-cooldown')
  }

  const lastAuto = Math.max(n(opts.lastAutoAt), s.lastAutoAt)
  if (lastAuto > 0 && now - lastAuto < cooldownMs) {
    return reject('cooldown')
  }

  if (s.autoCount >= dailyCap) {
    return reject('daily-cap-reached')
  }

  const entry = { at: now, source, mode: 'auto', urgent: isUrgent, topic: String(opts.topic || '').slice(0, 80) }
  const nextState = {
    ...s,
    autoCount: s.autoCount + 1,
    lastAutoAt: now,
    ...(isUrgent ? { lastUrgentAutoAt: now } : {}),
    history: [...s.history, entry].slice(-40),
  }
  return {
    ok: true,
    allowed: true,
    mode: 'auto',
    urgent: isUrgent,
    cooldownMs,
    reason: 'auto-allowed',
    source,
    brief,
    countToday: nextState.autoCount,
    cap: dailyCap,
    left: Math.max(0, dailyCap - nextState.autoCount),
    state: nextState,
  }
}

/**
 * Gemini 主动触发矩阵评估器（打破“自己玩自己”，纯函数）。
 * 覆盖 3 大核心现场触发场景：
 *   1) idle-fun-drift：白天非午休、无活跃局且连续娱乐停留 >=10m 或 5m 内碎跳 >=3 次 → 2 分钟破冰定第一步；
 *   2) lock-bounce：短时连续撞锁被拦 >=2 次 → 30 秒卡点诊断（10m 急卡点冷却）；
 *   3) post-bout-or-evening：刚收局未复盘 或 >=21:30 未做今日收口 → 今日思考压成明天题库卡/因果卡。
 *
 * 绝不强抢屏：返回非强抢标识 `forceStealScreen: false` 与 `canSnooze: true`，严格过分级冷却、snooze 与日上限闸门。
 */
export function evaluateProactiveGeminiTriggers(gateState, signals = {}, opts = {}) {
  const sig = signals && typeof signals === 'object' ? signals : {}
  const opt = opts && typeof opts === 'object' ? opts : {}
  const now = n(opt.now ?? sig.now, Date.now())
  const tz = String(opt.tz || sig.tz || DEFAULT_TZ)
  const day = String(opt.day || sig.day || zoneYmd(now, tz))
  const mod = Number.isFinite(Number(sig.minuteOfDay))
    ? Math.max(0, Math.min(1439, Math.round(Number(sig.minuteOfDay))))
    : zoneMinuteOfDay(now, tz)

  const isLunchBreak = sig.isLunchBreak !== undefined ? Boolean(sig.isLunchBreak) : (mod >= 12 * 60 && mod < 14 * 60)
  const isNight = opt.isNight !== undefined
    ? Boolean(opt.isNight)
    : (sig.isNight !== undefined ? Boolean(sig.isNight) : (mod < 7 * 60 || mod >= 23 * 60 + 30))
  const isDaytime = sig.isDaytime !== undefined ? Boolean(sig.isDaytime) : (mod >= 7 * 60 && mod < 21 * 60 + 30)

  const hasActiveBout = Boolean(sig.hasActiveBout ?? sig.activeBout)
  const funDwellMin = Math.max(0, n(sig.funDwellMin ?? sig.continuousFunMin, 0))
  const funSwitches5m = Math.max(0, n(sig.funSwitches5m ?? sig.fragmentHops5m ?? sig.recentFunSwitches, 0))
  const lockBounceCount = Math.max(0, Math.round(n(sig.lockBounceCount ?? sig.recentLockBlocks ?? sig.blockedCount, 0)))
  const justFinishedBout = Boolean(sig.justFinishedBout ?? sig.boutJustEnded)
  const boutRecapped = Boolean(sig.boutRecapped ?? sig.recapped)
  const eveningClosed = Boolean(sig.eveningClosed ?? sig.todayClosed)

  const matched = []

  if (lockBounceCount >= 2) {
    const appName = String(sig.lastBlockedApp || sig.front || '受限应用').slice(0, 40)
    const why = String(sig.lastBlockedWhy || '门禁规则拦截').slice(0, 80)
    const topic = `30 秒卡点诊断：刚连续撞锁 ${lockBounceCount} 次（${appName}）`
    const brief = buildStandardGeminiBrief({
      owner: opt.owner ?? sig.owner,
      topic,
      minutes: 10,
      mode: 'think',
      kbDirPath: opt.kbDirPath ?? sig.kbDirPath,
      setting: opt.setting ?? sig.setting,
      front: appName,
      frontMinutes: n(sig.frontMinutes, 0),
      bout: sig.bout || null,
      funMinutesToday: n(sig.funMinutesToday, 0),
      deadlines: sig.deadlines || [],
      about: opt.about || sig.about || [],
      commitments: opt.commitments || sig.commitments || [],
      bridge: Boolean(opt.bridge ?? sig.bridge),
      context: [
        `触发场景：连续撞锁卡点（${lockBounceCount} 次被拦，原因：${why}）`,
        '请用 30 秒做卡点诊断：1) 你现在真要打开它做什么具体动作？2) 是不是在逃避当下最难的那一步？3) 给出一个 2 分钟最小下一步或合规处理方案。',
      ],
    })
    matched.push({
      scenario: 'lock-bounce',
      source: 'lock-bounce',
      urgent: true,
      cooldownMs: GEMINI_URGENT_COOLDOWN_MS,
      title: '30 秒卡点诊断',
      subtitle: `连续撞锁 ${lockBounceCount} 次 · 拉 Gemini 30 秒拆卡点`,
      geminiMode: 'think',
      minutes: 10,
      topic,
      brief,
    })
  }

  if (isDaytime && !isNight && !isLunchBreak && !hasActiveBout && (funDwellMin >= 10 || funSwitches5m >= 3)) {
    const triggerDetail = funDwellMin >= 10
      ? `无活跃专注局且连续娱乐停留 ${Math.round(funDwellMin)} 分钟`
      : `无活跃专注局且 5 分钟内切娱乐/碎片跳转 ${Math.round(funSwitches5m)} 次`
    const topic = '拉 Gemini 聊 2 分钟破冰、定下今天第一步'
    const brief = buildStandardGeminiBrief({
      owner: opt.owner ?? sig.owner,
      topic,
      minutes: 10,
      mode: 'plan',
      kbDirPath: opt.kbDirPath ?? sig.kbDirPath,
      setting: opt.setting ?? sig.setting,
      front: sig.front || '',
      frontMinutes: funDwellMin,
      funMinutesToday: n(sig.funMinutesToday, funDwellMin),
      deadlines: sig.deadlines || [],
      about: opt.about || sig.about || [],
      commitments: opt.commitments || sig.commitments || [],
      bridge: Boolean(opt.bridge ?? sig.bridge),
      context: [
        `触发场景：白天闲置漂移（${triggerDetail}）`,
        '规则：2 分钟内破冰，不责备不讲大道理，直接帮他定死接下来 25 分钟的第一步、交付物和允许 App，让他立刻开干。',
      ],
    })
    matched.push({
      scenario: 'idle-fun-drift',
      source: 'idle-fun-drift',
      urgent: false,
      cooldownMs: GEMINI_AUTO_COOLDOWN_MS,
      title: '2 分钟破冰定第一步',
      subtitle: triggerDetail,
      geminiMode: 'plan',
      minutes: 10,
      topic,
      brief,
    })
  }

  if ((justFinishedBout && !boutRecapped) || (mod >= 21 * 60 + 30 && !eveningClosed)) {
    const byEvening = mod >= 21 * 60 + 30 && !eveningClosed
    const triggerDetail = justFinishedBout && !boutRecapped
      ? '刚收完一局且未触发复盘'
      : '已到 21:30 晚间收口窗口且今日未收口'
    const topic = '让 Gemini 把今日思考压成明天题库卡/因果卡'
    const brief = buildStandardGeminiBrief({
      owner: opt.owner ?? sig.owner,
      topic,
      minutes: 15,
      mode: 'study',
      kbDirPath: opt.kbDirPath ?? sig.kbDirPath,
      setting: opt.setting ?? sig.setting,
      front: sig.front || '',
      bout: sig.bout || null,
      funMinutesToday: n(sig.funMinutesToday, 0),
      deadlines: sig.deadlines || [],
      about: opt.about || sig.about || [],
      commitments: opt.commitments || sig.commitments || [],
      bridge: Boolean(opt.bridge ?? sig.bridge),
      context: [
        `触发场景：${byEvening ? '晚间收口压卡' : '收局复盘压卡'}（${triggerDetail}）`,
        '任务：逼他用自己的话讲清今天学到/卡住的核心机制，压成 1–3 张「明天可直接刷的题库卡（选择/知识卡/小问题）」或「触发→机制→行动」因果卡，顺手定明早第一块。',
      ],
    })
    matched.push({
      scenario: 'post-bout-or-evening',
      source: 'post-bout-or-evening',
      urgent: false,
      cooldownMs: GEMINI_AUTO_COOLDOWN_MS,
      title: '把今日思考压成明天题库卡/因果卡',
      subtitle: triggerDetail,
      geminiMode: 'study',
      minutes: 15,
      topic,
      brief,
    })
  }

  const normalized = normalizeGeminiGate(gateState, day)
  if (!matched.length) {
    return {
      ok: true,
      triggered: false,
      allowed: false,
      scenario: null,
      reason: 'no-trigger-matched',
      matchedScenarios: [],
      packet: null,
      brief: '',
      state: normalized,
      forceStealScreen: false,
      canSnooze: true,
    }
  }

  const chosen = matched[0]
  const dryRun = opt.commit === false || opt.dryRun === true
  const gateInput = {
    ...opt,
    now,
    day,
    source: chosen.source,
    mode: 'auto',
    urgent: chosen.urgent,
    cooldownMs: opt.cooldownMs ?? chosen.cooldownMs,
    dailyCap: opt.dailyCap ?? DEFAULT_GEMINI_AUTO_CAP,
    topic: chosen.topic,
    geminiMode: chosen.geminiMode,
    minutes: chosen.minutes,
    brief: chosen.brief,
  }
  const gateRes = requestGemini(normalized, gateInput)
  const packet = {
    scenario: chosen.scenario,
    source: chosen.source,
    title: chosen.title,
    subtitle: chosen.subtitle,
    topic: chosen.topic,
    geminiMode: chosen.geminiMode,
    minutes: chosen.minutes,
    urgent: chosen.urgent,
    cooldownMinutes: Math.round(chosen.cooldownMs / 60_000),
    brief: chosen.brief,
    nonIntrusive: true,
    forceStealScreen: false,
    canSnooze: true,
    snoozeMinutes: GEMINI_DEFAULT_SNOOZE_MIN,
  }

  return {
    ok: gateRes.allowed,
    triggered: gateRes.allowed,
    allowed: gateRes.allowed,
    scenario: chosen.scenario,
    reason: gateRes.reason,
    matchedScenarios: matched.map((m) => m.scenario),
    packet: gateRes.allowed ? packet : { ...packet, blockedReason: gateRes.reason },
    brief: gateRes.allowed ? chosen.brief : '',
    countToday: gateRes.countToday,
    cap: gateRes.cap,
    left: gateRes.left,
    snoozeUntil: gateRes.snoozeUntil || normalized.snoozeUntil,
    state: dryRun ? normalized : gateRes.state,
    forceStealScreen: false,
    canSnooze: true,
  }
}
