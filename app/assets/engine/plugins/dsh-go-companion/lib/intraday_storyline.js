// dsh-go-companion · 同日连续叙事工作记忆总线 (Intraday Storyline V5)
//
// 借鉴 Letta / MemGPT 分层工作记忆 + Project AIRI 日内连续心跳线：
// 按自然日维护最多 18 条同天关键事件锚点，跨发球、聊天、双姐妹、Gemini 共脑共享。
// 换天自动清空当日事件链，但保留用户设置的 pullMode（主动拉起 Gemini 档位）。

import { dayString } from './daily.js'
import { DEFAULT_TZ, hm as zoneHm } from './tz.js'

export const STORYLINE_VERSION = 1
export const MAX_STORYLINE_ENTRIES = 18
export const MAX_SUMMARY_CHARS = 80
export const MAX_PROMPT_CHARS = 450
export const DEFAULT_PULL_MODE = 'countdown_3s'
export const PULL_MODES = Object.freeze(['countdown_3s', 'instant', 'gentle'])

function cleanText(raw, max) {
  const s = String(raw == null ? '' : raw).replace(/\s+/g, ' ').trim()
  return s.length > max ? s.slice(0, max) : s
}

export function normalizePullMode(mode) {
  const m = String(mode || '').trim()
  return PULL_MODES.includes(m) ? m : DEFAULT_PULL_MODE
}

export function emptyStoryline(day, pullMode = DEFAULT_PULL_MODE) {
  return {
    v: STORYLINE_VERSION,
    day: String(day || ''),
    pullMode: normalizePullMode(pullMode),
    lastPullAt: 0,
    entries: [],
  }
}

export function normalizeStoryline(raw, { now = Date.now(), tz = DEFAULT_TZ } = {}) {
  const day = dayString(now, tz)
  const s = raw && typeof raw === 'object' ? raw : {}
  const pullMode = normalizePullMode(s.pullMode)
  const lastPullAt = Number(s.lastPullAt) || 0
  if (s.day !== day || !Array.isArray(s.entries)) {
    return { ...emptyStoryline(day, pullMode), lastPullAt }
  }
  const entries = s.entries
    .filter((e) => e && typeof e === 'object' && cleanText(e.summary, MAX_SUMMARY_CHARS))
    .map((e) => ({
      at: Number(e.at) || now,
      hm: cleanText(e.hm, 8) || zoneHm(Number(e.at) || now, tz),
      kind: cleanText(e.kind || 'event', 32),
      actor: cleanText(e.actor || 'system', 24),
      summary: cleanText(e.summary, MAX_SUMMARY_CHARS),
    }))
    .slice(-MAX_STORYLINE_ENTRIES)
  return {
    v: STORYLINE_VERSION,
    day,
    pullMode,
    lastPullAt,
    entries,
  }
}

export function appendStorylineEvent(raw, event = {}, { now = Date.now(), tz = DEFAULT_TZ } = {}) {
  const store = normalizeStoryline(raw, { now, tz })
  const summary = cleanText(event.summary, MAX_SUMMARY_CHARS)
  if (!summary) return store
  const at = Number(event.at) || now
  const kind = cleanText(event.kind || 'event', 32)
  const actor = cleanText(event.actor || 'system', 24)
  const hm = cleanText(event.hm, 8) || zoneHm(at, tz)
  const last = store.entries[store.entries.length - 1]
  if (last && last.kind === kind && last.summary === summary && Math.abs(at - last.at) < 60_000) {
    return store
  }
  const nextEntries = [...store.entries, { at, hm, kind, actor, summary }].slice(-MAX_STORYLINE_ENTRIES)
  return {
    ...store,
    entries: nextEntries,
  }
}

export function setPullMode(raw, pullMode, { now = Date.now(), tz = DEFAULT_TZ } = {}) {
  const store = normalizeStoryline(raw, { now, tz })
  return {
    ...store,
    pullMode: normalizePullMode(pullMode),
  }
}

export function markAutoPulled(raw, { now = Date.now(), tz = DEFAULT_TZ, summary = '' } = {}) {
  let store = normalizeStoryline(raw, { now, tz })
  store = { ...store, lastPullAt: now }
  if (summary) {
    store = appendStorylineEvent(store, { kind: 'gemini_auto_pull', actor: 'system', summary, at: now }, { now, tz })
  }
  return store
}

const ACTOR_TAG = {
  user: '我',
  companion: '鱼丸',
  strategist: '鲸澜',
  gemini: 'Gemini',
  system: '线',
}

export function formatIntradayPromptBlock(rawStory, now = Date.now(), tz = DEFAULT_TZ) {
  const store = normalizeStoryline(rawStory, { now, tz })
  if (!store.entries.length) return ''
  const header = '【今日连续脉络（接着聊，勿断片）】'
  const lines = store.entries.map((e) => {
    const tag = ACTOR_TAG[e.actor] || e.actor
    return `[${e.hm} ${tag}] ${e.summary}`
  })
  // 尽量保留早间锚点与最近事件，同时严控总长 <= 450 字符。
  const picked = []
  let total = header.length
  const anchor = lines.length > 1 ? lines[0] : null
  const rest = anchor ? lines.slice(1) : lines
  const keptTail = []
  const budgetAfterAnchor = anchor ? MAX_PROMPT_CHARS - header.length - 1 - anchor.length : MAX_PROMPT_CHARS - header.length
  for (let i = rest.length - 1; i >= 0; i -= 1) {
    const line = rest[i]
    const cost = 1 + line.length
    if (keptTail.reduce((s, x) => s + 1 + x.length, 0) + cost <= budgetAfterAnchor) {
      keptTail.unshift(line)
    } else {
      break
    }
  }
  if (anchor && total + 1 + anchor.length <= MAX_PROMPT_CHARS) {
    picked.push(anchor)
    total += 1 + anchor.length
  }
  for (const line of keptTail) {
    if (total + 1 + line.length <= MAX_PROMPT_CHARS) {
      picked.push(line)
      total += 1 + line.length
    }
  }
  if (!picked.length && lines.length) {
    const last = lines[lines.length - 1]
    return `${header}\n${last}`.slice(0, MAX_PROMPT_CHARS)
  }
  return `${header}\n${picked.join('\n')}`.slice(0, MAX_PROMPT_CHARS)
}
