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

export const GEMINI_AUTO_COOLDOWN_MS = 20 * 60_000
export const GEMINI_POST_SESSION_COOLDOWN_MS = 10 * 60_000
export const DEFAULT_GEMINI_AUTO_CAP = 6

const AUTO_SOURCES = new Set(['drift', 'preset', 'ladder', 'fun-dwell-8m', 'switch-limit'])
const n = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

export function emptyGeminiGate(day = '') {
  return {
    v: 1,
    day: String(day || ''),
    autoCount: 0,
    manualCount: 0,
    lastAutoAt: 0,
    lastManualAt: 0,
    lastSessionEndedAt: 0,
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
    lastManualAt: Math.max(0, n(s.lastManualAt)),
    lastSessionEndedAt: Math.max(0, n(s.lastSessionEndedAt)),
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
  const cooldownMs = Math.max(0, n(opts.cooldownMs, GEMINI_AUTO_COOLDOWN_MS))
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

  const entry = { at: now, source, mode: 'auto', topic: String(opts.topic || '').slice(0, 80) }
  const nextState = {
    ...s,
    autoCount: s.autoCount + 1,
    lastAutoAt: now,
    history: [...s.history, entry].slice(-40),
  }
  return {
    ok: true,
    allowed: true,
    mode: 'auto',
    reason: 'auto-allowed',
    source,
    brief,
    countToday: nextState.autoCount,
    cap: dailyCap,
    left: Math.max(0, dailyCap - nextState.autoCount),
    state: nextState,
  }
}
