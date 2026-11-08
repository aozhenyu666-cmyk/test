// dsh-go-companion · Topic Garden
//
// 话题不再只是“从记忆里捞出来的一条”，而是一份独立、可演化的清单：
//   candidate → active → paused → active（重新唤起）→ done
// 记忆库仍是事实/未完成话题的真源，这里只保存“这段关系里话题怎么走”的状态。
//
// 数据文件：$DSH_HOME/companion/topics.json（原子写）。

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

export const GARDEN_VERSION = 1
export const MAX_TOPICS = 60
export const MAX_ATTEMPTS = 2

const DAY = 86_400_000

export function emptyGarden() {
  return { v: GARDEN_VERSION, topics: [], meta: { day: '', discovered: 0, lastKinds: [] } }
}

export function loadGarden(file) {
  try {
    const raw = JSON.parse(readFileSync(file, 'utf8'))
    if (!raw || typeof raw !== 'object' || !Array.isArray(raw.topics)) return emptyGarden()
    return {
      v: GARDEN_VERSION,
      topics: raw.topics.filter((t) => t && typeof t.id === 'string' && typeof t.title === 'string'),
      meta: {
        day: typeof raw.meta?.day === 'string' ? raw.meta.day : '',
        discovered: Number(raw.meta?.discovered) || 0,
        lastKinds: Array.isArray(raw.meta?.lastKinds) ? raw.meta.lastKinds.slice(-4) : [],
      },
    }
  } catch {
    return emptyGarden()
  }
}

export function saveGarden(file, garden) {
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
  const tmp = file + '.' + String(process.pid) + '.tmp'
  writeFileSync(tmp, JSON.stringify(garden, null, 1) + '\n', { encoding: 'utf8', mode: 0o600 })
  renameSync(tmp, file)
}

function normalize(text) {
  return String(text || '')
    .toLowerCase()
    .replace(/[\s\p{P}\p{S}]+/gu, '')
    .slice(0, 120)
}

function bigrams(text) {
  const s = normalize(text)
  const out = new Set()
  for (let i = 0; i + 1 < s.length; i += 1) out.add(s.slice(i, i + 2))
  return out
}

/** 0..1 的相似度；标题很像的合并，免得同一个话题换几种说法反复出现。 */
export function similarity(a, b) {
  const x = bigrams(a)
  const y = bigrams(b)
  if (!x.size || !y.size) return normalize(a) === normalize(b) ? 1 : 0
  let common = 0
  for (const t of x) if (y.has(t)) common += 1
  return common / Math.min(x.size, y.size)
}

function dayKey(now) {
  const d = new Date(now)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function prune(garden, now) {
  const cutoff = now - 30 * DAY
  garden.topics = garden.topics.filter((t) => t.status !== 'done' || Number(t.updatedAt) >= cutoff)
  if (garden.topics.length <= MAX_TOPICS) return
  const keep = garden.topics
    .slice()
    .sort((a, b) => scoreOf(a, now, null) - scoreOf(b, now, null))
    .slice(garden.topics.length - MAX_TOPICS)
  const ids = new Set(keep.map((t) => t.id))
  garden.topics = garden.topics.filter((t) => ids.has(t.id))
}

function scoreOf(t, now, lastKind) {
  const base = t.kind === 'continue' ? 100 : t.kind === 'explore' ? 90 : 80
  const interest = Math.max(0, Math.min(2, Number(t.interest) || 1)) * 8
  const attempts = Math.max(0, Number(t.attempts) || 0) * 15
  const recency = Math.max(0, 14 - (now - (Number(t.lastUsedAt) || 0)) / DAY) * 0.4
  const sameKind = lastKind && lastKind === t.kind ? 8 : 0
  return base + interest + recency - attempts - sameKind
}

function addOrMerge(garden, input, now) {
  const title = String(input.title || '').replace(/\s+/g, ' ').trim().slice(0, 180)
  if (!title) return null
  const sameId = garden.topics.find((t) => t.id === input.id)
  if (sameId) {
    if (sameId.status === 'done') {
      // 结束的话题不会因为记忆里还挂着 open 就复活：只有新的触碰时间或显式 reopen 才算。
      const fresh = input.reopen === true || (Number(input.lastTouchAt) > Number(sameId.updatedAt))
      if (!fresh) return sameId
      sameId.status = 'candidate'
      sameId.attempts = 0
      sameId.avoidStreak = 0
      sameId.nextEligibleAt = 0
    }
    sameId.title = title
    if (input.detail) sameId.detail = String(input.detail).slice(0, 300)
    sameId.interest = Math.max(Number(sameId.interest) || 1, Number(input.interest) || 1)
    sameId.source = input.source || sameId.source
    sameId.updatedAt = now
    if (input.nextEligibleAt) {
      sameId.nextEligibleAt = Math.min(sameId.nextEligibleAt || Infinity, input.nextEligibleAt)
    }
    return sameId
  }
  const existing = garden.topics.find(
    (t) => t.status !== 'done' && similarity(t.title, title) >= 0.7,
  )
  if (existing) {
    existing.title = title
    if (input.detail) existing.detail = String(input.detail).slice(0, 300)
    existing.interest = Math.max(Number(existing.interest) || 1, Number(input.interest) || 1)
    existing.source = input.source || existing.source
    existing.updatedAt = now
    if (input.nextEligibleAt) {
      existing.nextEligibleAt = Math.min(existing.nextEligibleAt || Infinity, input.nextEligibleAt)
    }
    return existing
  }
  const topic = {
    id: String(input.id || `g:${now.toString(36)}:${garden.topics.length}`),
    kind: input.kind === 'share' || input.kind === 'explore' ? input.kind : 'continue',
    title,
    detail: String(input.detail || '').slice(0, 300),
    source: String(input.source || 'garden').slice(0, 40),
    status: 'candidate',
    interest: Math.max(0, Math.min(2, Number(input.interest) || 1)),
    attempts: 0,
    avoidStreak: 0,
    usedCount: 0,
    createdAt: now,
    updatedAt: now,
    lastUsedAt: 0,
    nextEligibleAt: Number(input.nextEligibleAt) || 0,
    lastReplyClass: '',
    lastSentSummary: '',
    lastSummary: '',
    dueAt: Number(input.dueAt) || 0,
  }
  garden.topics.push(topic)
  prune(garden, now)
  return topic
}

/** 把 fish-memory 里的话题与最近经历搬进花园；只读，不改记忆库。 */
export function seedFromMemory(garden, candidates, now) {
  for (const t of candidates.topics || []) {
    addOrMerge(garden, {
      id: 'topic:' + String(t.id),
      kind: 'continue',
      title: String(t.title || ''),
      detail: String(t.detail || ''),
      source: 'memory.topic',
      interest: Number(t.priority) >= 2 ? 2 : 1,
      lastTouchAt: Number(t.last_touch_at) || 0,
    }, now)
  }
  // 钉住/重要事实同样是话题来源：只有事实、还没聊出话题的人，主动开口不该永远沉默。
  for (const f of candidates.facts || []) {
    addOrMerge(garden, {
      id: 'fact:' + String(f.id),
      kind: 'share',
      title: String(f.text || ''),
      source: 'memory.fact',
      interest: Number(f.importance) >= 2 ? 2 : 1,
    }, now)
  }
  for (const e of candidates.episodes || []) {
    addOrMerge(garden, {
      id: 'event:' + String(e.at),
      kind: 'share',
      title: String(e.text || ''),
      source: 'memory.episode',
      interest: 1,
    }, now)
  }
  return garden
}

/** 关系账本里尚未完成的承诺：到期前后都可以自然提一下，但不变成催办。 */
export function seedFromCommitments(garden, commitments, now) {
  for (const c of commitments || []) {
    addOrMerge(garden, {
      id: 'commit:' + String(c.id),
      kind: 'continue',
      title: '约定：' + String(c.statement || ''),
      source: 'relationship.commitment',
      interest: 2,
      dueAt: c.dueAt ? Date.parse(c.dueAt) : 0,
      nextEligibleAt: c.dueAt ? Math.max(0, Date.parse(c.dueAt) - 3_600_000) : 0,
    }, now)
  }
  return garden
}

/**
 * 正常聊天里冒出来的新话题。只在有明确“想/打算/最近/在看…”信号时收，
 * 每天最多 3 条，避免把每句话都变成候选。
 */
export function discoverFromMessage(garden, text, now) {
  const t = String(text || '').replace(/\s+/g, ' ').trim()
  if (t.length < 8 || t.length > 160) return null
  if (!/(想|打算|最近|在看|在玩|喜欢|讨厌|要不要|周末|下次|准备|纠结|考虑)/.test(t)) return null
  const day = dayKey(now)
  if (garden.meta.day !== day) {
    garden.meta.day = day
    garden.meta.discovered = 0
  }
  if (garden.meta.discovered >= 3) return null
  const before = garden.topics.length
  const topic = addOrMerge(garden, {
    kind: 'continue',
    title: t.slice(0, 120),
    source: 'conversation',
    interest: 1,
  }, now)
  if (topic && garden.topics.length > before) {
    garden.meta.discovered += 1
    topic.createdAt = now
    return topic
  }
  return null
}

/** 用户主动提出的、或模型在正常聊天里记下的话题。 */
export function addTopic(garden, input, now) {
  return addOrMerge(garden, { ...input, source: input?.source || 'agent' }, now)
}

/**
 * 选下一个话题。返回 null 表示宁可不说话，也不用空泛问候凑数。
 */
export function nextTopic(garden, options = {}) {
  const now = Number(options.now) || Date.now()
  const cooldownMs = Number(options.cooldownMs) || DAY
  const maxAttempts = Number(options.maxAttempts) || MAX_ATTEMPTS
  const lastTopicId = String(options.lastTopicId || '')
  const lastAt = Number(options.lastAt) || 0
  const recent = Array.isArray(garden.meta.lastKinds) ? garden.meta.lastKinds : []
  const lastKind = recent.at(-1) || ''

  const eligible = garden.topics.filter((t) => {
    if (t.status === 'done') return false
    if (Number(t.nextEligibleAt) > now) return false
    if (Number(t.attempts) >= maxAttempts) return false
    if (t.id === lastTopicId && lastAt > 0 && now - lastAt < cooldownMs && t.lastReplyClass !== 'substantive') return false
    return true
  })
  eligible.sort((a, b) => scoreOf(b, now, lastKind) - scoreOf(a, now, lastKind))
  const pick = eligible[0] || null
  if (!pick) return null
  // 连续两次追问之后，优先换分享/探索，避免变成问卷。
  if (recent.slice(-2).every((k) => k === 'continue') && pick.kind === 'continue') {
    const alt = eligible.find((t) => t.kind !== 'continue' && t.id !== pick.id)
    if (alt) return alt
  }
  return pick
}

export function markUsed(garden, topicId, now) {
  const t = garden.topics.find((x) => x.id === topicId)
  if (!t) return null
  t.status = 'active'
  t.lastUsedAt = now
  t.usedCount = (Number(t.usedCount) || 0) + 1
  t.updatedAt = now
  garden.meta.lastKinds = [...(garden.meta.lastKinds || []), t.kind].slice(-4)
  return t
}

export function markGenerated(garden, topicId, text, now) {
  const t = garden.topics.find((x) => x.id === topicId)
  if (!t) return null
  // AI 单方面说出的话只算“发过”，不算“聊过”；只有实质回应后才升级成 lastSummary。
  t.lastSentSummary = String(text || '').replace(/\s+/g, ' ').slice(0, 200)
  t.updatedAt = now
  return t
}

/**
 * 交流结果 → 话题状态。
 * substantive: 继续，并把刚才那轮记成“聊过”；
 * brief: 换轻一点、2 小时后再试；
 * avoid: 12 小时后再试；连续两次回避改为 24 小时，不消耗无回应额度；
 * refuse: 结束这个话题；
 * none: 有限跟进（第一次 4 小时，第二次后冷却一周）。
 */
export function applyReply(garden, topicId, replyClass, now) {
  const t = garden.topics.find((x) => x.id === topicId)
  if (!t) return null
  t.lastReplyClass = String(replyClass || 'brief')
  t.updatedAt = now
  switch (replyClass) {
    case 'substantive':
      t.status = 'active'
      t.attempts = 0
      t.avoidStreak = 0
      if (t.lastSentSummary) {
        t.lastSummary = t.lastSentSummary
        t.lastSentSummary = ''
      }
      t.nextEligibleAt = now
      break
    case 'refuse':
      t.status = 'done'
      t.attempts = MAX_ATTEMPTS
      t.avoidStreak = 0
      t.nextEligibleAt = 0
      break
    case 'avoid':
      t.status = 'paused'
      t.avoidStreak = (Number(t.avoidStreak) || 0) + 1
      t.nextEligibleAt = now + (t.avoidStreak >= 2 ? DAY : 12 * 3_600_000)
      break
    case 'brief':
      t.status = 'paused'
      t.avoidStreak = 0
      t.nextEligibleAt = now + 2 * 3_600_000
      break
    default:
      t.status = 'paused'
      t.nextEligibleAt = now + 4 * 3_600_000
  }
  return t
}

export function applyNoReply(garden, topicId, now) {
  const t = garden.topics.find((x) => x.id === topicId)
  if (!t) return null
  t.lastReplyClass = 'none'
  const attempts = (Number(t.attempts) || 0) + 1
  if (attempts >= MAX_ATTEMPTS) {
    // Two unanswered tries: cool for a week, then a fresh two-try cycle may begin.
    t.attempts = 0
    t.status = 'paused'
    t.nextEligibleAt = now + 7 * DAY
  } else {
    t.attempts = attempts
    t.status = 'active'
    t.nextEligibleAt = now + 4 * 3_600_000
  }
  t.updatedAt = now
  return t
}

export function closeTopic(garden, topicId, status, now) {
  const t = garden.topics.find((x) => x.id === topicId)
  if (!t) return null
  t.status = status === 'done' ? 'done' : 'paused'
  t.updatedAt = now
  t.nextEligibleAt = status === 'done' ? 0 : now + 6 * 3_600_000
  return t
}

/** 下一次有话题可聊的时间；用于 /plan 告诉 Android 什么时候再来检查。 */
export function nextTopicAt(garden, now, fallbackMs = 6 * 3_600_000) {
  let next = Infinity
  for (const t of garden.topics) {
    if (t.status === 'done') continue
    if (Number(t.attempts) >= MAX_ATTEMPTS) continue
    const at = Number(t.nextEligibleAt) || 0
    if (at > now && at < next) next = at
  }
  return Number.isFinite(next) ? next : now + fallbackMs
}

export function gardenStats(garden, now = Date.now()) {  const counts = { candidate: 0, active: 0, paused: 0, done: 0 }
  for (const t of garden.topics) if (Object.hasOwn(counts, t.status)) counts[t.status] += 1
  const ready = garden.topics.filter(
    (t) => t.status !== 'done' && Number(t.nextEligibleAt) <= now && Number(t.attempts) < MAX_ATTEMPTS,
  ).length
  return { ...counts, total: garden.topics.length, ready }
}

export function publicTopic(t) {
  return {
    id: t.id,
    kind: t.kind,
    title: t.title,
    source: t.source,
    status: t.status,
    interest: t.interest,
    attempts: t.attempts,
    usedCount: t.usedCount,
    lastUsedAt: t.lastUsedAt,
    nextEligibleAt: t.nextEligibleAt,
    lastReplyClass: t.lastReplyClass,
    lastSummary: t.lastSummary || '',
  }
}
