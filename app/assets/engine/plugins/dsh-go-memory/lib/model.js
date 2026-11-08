// 大肥鱼本地记忆 · 纯逻辑层
//
// 这一层不碰文件、不碰 SQL：输入是一份 doc（见 emptyDoc），输出是改过的 doc 和结果。
// 好处是单测直接跑，SQLite / JSON 两种落盘后端共用同一套规则，不会各写一遍。
//
// 参考了两个项目的「规则」，但没有复制任何代码：
//   · dsh-fish-memory（Yuuyuko-uu 插件许可 1.2，允许修改）：事实/偏好/摘要、按会话分库、
//     「同样的事实只留最新一条」、检索按 相关度×（基础+新鲜+重要+情绪） 打分。
//   · Muse（GPL-3.0，只做设计参考）：importance 0/1/2、pinned、expires_at、tombstone、
//     scope 隔离、主动介入要受冷却和额度约束。

import { createHash, randomUUID } from 'node:crypto'

export const SCHEMA_VERSION = 1
export const KINDS = ['fact', 'preference', 'promise', 'event']
export const TOPIC_STATUS = ['open', 'done', 'deferred', 'dropped']
const DAY = 86_400_000

export function emptyDoc() {
  return { v: SCHEMA_VERSION, facts: [], topics: [], episodes: [], tombstones: [], hits: [] }
}

export function newId(prefix) {
  return `${prefix}_${randomUUID().replace(/-/g, '').slice(0, 20)}`
}

export function hashText(text) {
  return createHash('sha256').update(normalizeText(text)).digest('hex').slice(0, 24)
}

export function normalizeText(text) {
  return String(text == null ? '' : text).replace(/\s+/g, ' ').trim()
}

/** scope 只认这三种；别的写法一律当 global，免得写进一个永远搜不到的分区。 */
export function normalizeScope(raw) {
  const s = String(raw == null ? '' : raw).trim()
  if (/^session:[\w.-]{1,128}$/.test(s)) return s
  if (/^workspace:[\w.:~-]{1,200}$/.test(s)) return s
  return 'global'
}

export function scopeOfSession(sid) {
  return sid ? normalizeScope(`session:${sid}`) : 'global'
}

/** 一个会话能看到的范围：自己的 + 全局。 */
export function visibleScopes(scope) {
  const s = normalizeScope(scope)
  return s === 'global' ? ['global'] : [s, 'global']
}

// ── 检索词 ────────────────────────────────────────────────────────────────
// 中文没有空格，按 bigram 切；英文/数字按词切。虚词不参与打分。
const STOP = new Set([
  '的', '了', '是', '在', '我', '你', '他', '她', '它', '们', '和', '就', '也', '都',
  '不', '有', '会', '要', '吗', '呢', '吧', '啊', '这', '那', '个', '一', '上', '下',
  '很', '把', '被', '给', '对', '说', '过', '还', '又', '但', '而', '与', '及', '为',
  '以', '到', '从', '能', '可', '什', '么', '怎', '样', '如', '果', '因', '所', '其',
])
const STOP_BIGRAMS = new Set(['可以', '什么', '怎么', '为什', '因为', '所以', '但是', '如果', '这个', '那个', '就是', '不是', '我们', '你们', '他们', '她们'])

export function tokens(text) {
  const out = new Set()
  const s = String(text || '').toLowerCase()
  for (const w of s.match(/[a-z0-9_]{2,}/g) || []) out.add(w)
  const cjk = s.replace(/[^\u4e00-\u9fff]/g, '')
  if (cjk.length === 1) {
    if (!STOP.has(cjk)) out.add(cjk)
  }
  for (let i = 0; i + 1 < cjk.length; i++) {
    const bg = cjk.slice(i, i + 2)
    if (STOP_BIGRAMS.has(bg)) continue
    out.add(bg)
  }
  return out
}

/** 0..1 的相关度：检索词覆盖 + 原文包含整句的加分。 */
export function relevance(query, text) {
  const q = tokens(query)
  if (!q.size) return 0
  const t = tokens(text)
  let hit = 0
  for (const tok of q) if (t.has(tok)) hit += 1
  const cover = hit / q.size
  const qn = normalizeText(query).toLowerCase()
  const whole = qn.length >= 2 && normalizeText(text).toLowerCase().includes(qn) ? 0.25 : 0
  return Math.min(1, cover * 0.9 + whole)
}

// ── 打分与衰减 ────────────────────────────────────────────────────────────
// 重要度 0/1/2 的分值（Muse 那套），钉住的不参与衰减。
const IMPORTANCE_BASE = [0.15, 0.55, 1.1]

export function isExpired(fact, now) {
  return Boolean(fact.expiresAt && Number(fact.expiresAt) <= now)
}

/** 一条记忆现在还剩多少「清晰度」；钉住 = 1，其余按 45 天半衰。 */
export function clarity(fact, now) {
  if (fact.pinned) return 1
  const last = Number(fact.lastHitAt || fact.updatedAt || fact.createdAt) || now
  const days = Math.max(0, (now - last) / DAY)
  const decay = Math.pow(0.5, days / 45)
  const floor = 0.18
  return Math.max(floor, decay)
}

export function freshness(fact, now) {
  const born = Number(fact.createdAt) || now
  return Math.max(0, 1 - (now - born) / (120 * DAY)) * 0.5
}

export function scoreFact(fact, query, now) {
  const rel = query ? relevance(query, `${fact.text} ${(fact.tags || []).join(' ')}`) : 0
  if (query && rel <= 0) return 0
  const base = IMPORTANCE_BASE[fact.importance] ?? IMPORTANCE_BASE[1]
  const bonus = fact.pinned ? 2 : 0
  const hitBonus = Math.min(0.5, (Number(fact.hits) || 0) * 0.04 + (Number(fact.hitBonus) || 0))
  const p = (base + bonus + freshness(fact, now) + hitBonus) * (query ? 0.25 + rel : 1) * clarity(fact, now)
  return Number.isFinite(p) ? p : 0
}

// ── 事实 ──────────────────────────────────────────────────────────────────
const MAX_TEXT = 500
const MAX_FACTS = 5000

function activeFacts(doc, scopes) {
  return doc.facts.filter((f) => f.status === 'active' && scopes.includes(f.scope))
}

function findTombstone(doc, scope, text) {
  const h = hashText(text)
  return doc.tombstones.find((t) => t.scope === scope && t.kind === 'fact' && t.textHash === h) || null
}

function supersede(doc, old, newFact, now, reason) {
  old.status = 'superseded'
  old.updatedAt = now
  old.supersededBy = newFact.id
  old.supersededReason = reason || 'replaced'
  return old
}

/**
 * 记一条。规则：
 *  · 完全一样的话不重复记（只刷新时间/命中数）；
 *  · 带 key 的「同一件事」只留最新一条，旧的标 superseded（还能查、能复活）；
 *  · replaces 指认具体旧条目，同样 supersede；
 *  · 刚被 forget 掉的原话默认拒绝（tombstone），要写回来得 force。
 */
export function remember(doc, input, now = Date.now()) {
  const text = normalizeText(input && input.text)
  if (!text) return { ok: false, text: '内容不能为空。' }
  if ([...text].length > MAX_TEXT) return { ok: false, text: `内容太长（上限 ${MAX_TEXT} 字）。` }
  const scope = normalizeScope((input && input.scope) || 'global')
  const kind = KINDS.includes(input && input.kind) ? input.kind : 'fact'
  const importance = clampInt(input && input.importance, 0, 2, 1)
  const key = input && input.key ? normalizeText(input.key).slice(0, 80) : ''
  const tags = Array.isArray(input && input.tags)
    ? [...new Set(input.tags.map((t) => normalizeText(t).slice(0, 24)).filter(Boolean))].slice(0, 8)
    : []
  const expiresAt = input && Number.isFinite(Number(input.expiresAt)) && Number(input.expiresAt) > now
    ? Number(input.expiresAt)
    : 0

  const tomb = findTombstone(doc, scope, text)
  if (tomb && !(input && input.force === true)) {
    return { ok: false, text: '这条之前明确让忘掉了（tombstone）。要重新记，请带 force=true。', tombstone: true }
  }

  // 同一句话：只更新元数据，不新增。
  const sameText = doc.facts.find((f) => f.status === 'active' && f.scope === scope && normalizeText(f.text) === text)
  if (sameText) {
    sameText.hits = (Number(sameText.hits) || 0) + 1
    sameText.lastHitAt = now
    sameText.updatedAt = now
    if (importance > (sameText.importance ?? 1)) sameText.importance = importance
    if (input && input.pinned === true) sameText.pinned = true
    if (tomb) doc.tombstones = doc.tombstones.filter((t) => t !== tomb)
    return { ok: true, text: '这条已经记过，已刷新。', fact: publicFact(sameText), duplicate: true }
  }

  const fact = {
    id: newId('fact'),
    scope,
    text,
    kind,
    importance,
    pinned: Boolean(input && input.pinned === true),
    key,
    tags,
    createdAt: now,
    updatedAt: now,
    lastHitAt: 0,
    hits: 0,
    hitBonus: 0,
    expiresAt,
    source: normalizeText((input && input.source) || 'agent').slice(0, 40),
    status: 'active',
    supersededBy: '',
    correctedFrom: '',
  }

  // 先按 replaces（具体指认）处理，再按 key（同一件事）兜底。
  const replacedIds = []
  const replaces = normalizeText(input && input.replaces)
  if (replaces) {
    const target = doc.facts.find((f) => f.status === 'active' && f.scope === scope &&
      (f.id === replaces || normalizeText(f.text).includes(replaces)))
    if (target) {
      target.status === 'active' && supersede(doc, target, fact, now, 'replaces')
      replacedIds.push(target.id)
    }
  }
  if (key) {
    for (const old of doc.facts) {
      if (old.status !== 'active' || old.scope !== scope || old.key !== key) continue
      if (replacedIds.includes(old.id)) continue
      supersede(doc, old, fact, now, 'key')
      replacedIds.push(old.id)
    }
  }
  if (replacedIds.length) fact.correctedFrom = replacedIds.join(',')

  doc.facts.push(fact)
  if (doc.facts.length > MAX_FACTS) pruneFacts(doc, now)

  if (kind === 'event') {
    doc.episodes.push({ id: newId('ep'), scope, text, tags, at: now })
    if (doc.episodes.length > 2000) doc.episodes.splice(0, doc.episodes.length - 2000)
  }

  if (tomb) doc.tombstones = doc.tombstones.filter((t) => t !== tomb)
  return { ok: true, text: '记下了。', fact: publicFact(fact), replaced: replacedIds }
}

function pruneFacts(doc, now) {
  const dropped = doc.facts
    .filter((f) => f.status !== 'active')
    .sort((a, b) => (a.updatedAt || 0) - (b.updatedAt || 0))
    .slice(0, Math.max(0, doc.facts.length - MAX_FACTS))
  if (!dropped.length) return
  const ids = new Set(dropped.map((f) => f.id))
  doc.facts = doc.facts.filter((f) => !ids.has(f.id))
  doc.hits = doc.hits.filter((h) => !ids.has(h.factId))
}

export function search(doc, input = {}, now = Date.now()) {
  const scopes = input.scope === 'all' ? null : visibleScopes(input.scope)
  const limit = clampInt(input.limit, 1, 50, 10)
  const query = normalizeText(input.query)
  const kind = KINDS.includes(input.kind) ? input.kind : ''
  let rows = doc.facts.filter((f) => f.status === 'active' && !isExpired(f, now))
  if (scopes) rows = rows.filter((f) => scopes.includes(f.scope))
  if (kind) rows = rows.filter((f) => f.kind === kind)
  if (query) {
    rows = rows
      .map((f) => ({ f, s: scoreFact(f, query, now) }))
      .filter((x) => x.s > 0)
      .sort((a, b) => b.s - a.s)
      .map((x) => x.f)
  } else {
    rows = rows.slice().sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) ||
      (b.updatedAt || 0) - (a.updatedAt || 0))
  }
  const items = rows.slice(0, limit)
  return { ok: true, items: items.map(publicFact), total: rows.length, text: items.length ? `找到 ${items.length} 条。` : '没有找到。' }
}

export function touchHits(doc, ids, query, now = Date.now()) {
  const set = new Set(ids)
  for (const f of doc.facts) {
    if (!set.has(f.id)) continue
    f.hits = (Number(f.hits) || 0) + 1
    f.lastHitAt = now
    doc.hits.push({ id: newId('hit'), factId: f.id, at: now, query: normalizeText(query).slice(0, 120) })
  }
  if (doc.hits.length > 2000) doc.hits.splice(0, doc.hits.length - 2000)
}

/**
 * 纠正：不改原文，写一条新版本并把旧的标 superseded。
 * 这样「她说过什么」永远不会被悄悄改写，面板里也能看到改过。
 */
export function correct(doc, input = {}, now = Date.now()) {
  const id = normalizeText(input.id)
  const old = doc.facts.find((f) => f.id === id && f.status === 'active')
  if (!old) return { ok: false, text: '没找到这条（可能已被替换或忘掉）。' }
  const text = input.text === undefined ? old.text : normalizeText(input.text)
  if (!text) return { ok: false, text: '纠正后的内容不能为空。' }
  if ([...text].length > MAX_TEXT) return { ok: false, text: `内容太长（上限 ${MAX_TEXT} 字）。` }
  if (text === old.text && input.importance === undefined && input.pinned === undefined) {
    return { ok: false, text: '内容没有变化。' }
  }
  const fact = {
    ...old,
    id: newId('fact'),
    text,
    importance: input.importance === undefined ? old.importance : clampInt(input.importance, 0, 2, 1),
    pinned: input.pinned === undefined ? old.pinned : input.pinned === true,
    createdAt: now,
    updatedAt: now,
    lastHitAt: now,
    hits: 0,
    hitBonus: Number(old.hitBonus) || 0,
    status: 'active',
    supersededBy: '',
    correctedFrom: old.id,
  }
  if (input.key !== undefined) fact.key = normalizeText(input.key).slice(0, 80)
  supersede(doc, old, fact, now, 'corrected')
  doc.facts.push(fact)
  return { ok: true, text: '改好了（旧版本留着，可回溯）。', fact: publicFact(fact), previous: publicFact(old) }
}

/** 忘掉：留下 tombstone（原文哈希），以后不会被同一句话悄悄记回来。 */
export function forget(doc, input = {}, now = Date.now()) {
  const id = normalizeText(input.id)
  const match = normalizeText(input.match)
  let target = id ? doc.facts.find((f) => f.id === id && f.status === 'active') : null
  if (!target && match) {
    target = doc.facts
      .filter((f) => f.status === 'active' && normalizeText(f.text).includes(match))
      .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0] || null
  }
  if (!target) return { ok: false, text: '没找到要忘掉的那条。' }
  target.status = 'forgotten'
  target.updatedAt = now
  target.supersededBy = ''
  target.forgetReason = normalizeText(input.reason || 'user asked').slice(0, 200)
  doc.tombstones.push({
    id: newId('tomb'),
    scope: target.scope,
    kind: 'fact',
    refId: target.id,
    textHash: hashText(target.text),
    reason: target.forgetReason,
    at: now,
  })
  if (doc.tombstones.length > 1000) doc.tombstones.splice(0, doc.tombstones.length - 1000)
  return { ok: true, text: '忘掉了。', fact: publicFact(target) }
}

export function publicFact(f) {
  return {
    id: f.id,
    text: f.text,
    scope: f.scope,
    kind: f.kind,
    importance: f.importance,
    pinned: Boolean(f.pinned),
    key: f.key || '',
    tags: f.tags || [],
    status: f.status,
    createdAt: f.createdAt,
    updatedAt: f.updatedAt,
    expiresAt: f.expiresAt || 0,
    hits: f.hits || 0,
    clarity: Math.round(clarity(f, Date.now()) * 100) / 100,
  }
}

// ── 未完成的话题 ──────────────────────────────────────────────────────────
// 话题不是「记忆」：它带状态和下一次该不该问，主动回合要用它。
export function upsertTopic(doc, input = {}, now = Date.now()) {
  const title = normalizeText(input.title)
  const id = normalizeText(input.id)
  const scope = normalizeScope(input.scope || 'global')
  let topic = id ? doc.topics.find((t) => t.id === id) : null
  if (!topic && title) {
    topic = doc.topics.find((t) => t.status === 'open' && t.scope === scope &&
      normalizeText(t.title).toLowerCase() === title.toLowerCase()) || null
  }
  if (!topic) {
    if (!title) return { ok: false, text: '新话题需要标题。' }
    topic = {
      id: newId('topic'),
      scope,
      title: title.slice(0, 120),
      detail: normalizeText(input.detail).slice(0, 500),
      status: 'open',
      priority: clampInt(input.priority, 0, 2, 1),
      openedAt: now,
      updatedAt: now,
      lastTouchAt: now,
      nextAskAt: 0,
      attempts: 0,
      dueAt: Number(input.dueAt) || 0,
      closedAt: 0,
      reason: '',
    }
    doc.topics.push(topic)
  } else {
    if (title) topic.title = title.slice(0, 120)
    if (input.detail !== undefined) topic.detail = normalizeText(input.detail).slice(0, 500)
    if (input.priority !== undefined) topic.priority = clampInt(input.priority, 0, 2, 1)
    if (input.dueAt !== undefined) topic.dueAt = Number(input.dueAt) || 0
    if (input.status && TOPIC_STATUS.includes(input.status)) {
      topic.status = input.status
      topic.closedAt = input.status === 'open' ? 0 : now
      topic.reason = normalizeText(input.reason).slice(0, 200)
    }
    topic.updatedAt = now
    topic.lastTouchAt = now
  }
  return { ok: true, text: topic.status === 'open' ? '话题记下了。' : '话题状态已更新。', topic: publicTopic(topic) }
}

export function resolveTopic(doc, input = {}, now = Date.now()) {
  const id = normalizeText(input.id)
  const title = normalizeText(input.title)
  const topic = id ? doc.topics.find((t) => t.id === id)
    : doc.topics.filter((t) => t.status === 'open' && normalizeText(t.title).includes(title))[0]
  if (!topic) return { ok: false, text: '没找到这个话题。' }
  const status = TOPIC_STATUS.includes(input.status) ? input.status : 'done'
  topic.status = status
  topic.closedAt = status === 'open' ? 0 : now
  topic.updatedAt = now
  topic.lastTouchAt = now
  topic.reason = normalizeText(input.reason).slice(0, 200)
  return { ok: true, text: status === 'done' ? '话题完成了。' : status === 'deferred' ? '话题先搁着。' : '话题放掉了。', topic: publicTopic(topic) }
}

export function listTopics(doc, input = {}, now = Date.now()) {
  const scopes = input.scope === 'all' ? null : visibleScopes(input.scope)
  const status = TOPIC_STATUS.includes(input.status) ? input.status : 'open'
  let rows = doc.topics.filter((t) => t.status === status)
  if (scopes) rows = rows.filter((t) => scopes.includes(t.scope))
  rows = rows.sort((a, b) => (b.priority || 0) - (a.priority || 0) || (a.openedAt || 0) - (b.openedAt || 0))
  return { ok: true, items: rows.map(publicTopic), text: rows.length ? `${rows.length} 个${status === 'open' ? '未完成' : ''}话题。` : '没有这样的话题。' }
}

export function publicTopic(t) {
  return {
    id: t.id,
    title: t.title,
    detail: t.detail || '',
    scope: t.scope,
    status: t.status,
    priority: t.priority,
    openedAt: t.openedAt,
    updatedAt: t.updatedAt,
    dueAt: t.dueAt || 0,
    attempts: t.attempts || 0,
    reason: t.reason || '',
  }
}

// ── 主动回合要用的「该不该问」 ─────────────────────────────────────────────
// Muse 的 ProactiveTimingEngine 是四个问题；这里只做记忆侧的判断：
// 话题是否到点、是否问过太多次、是否刚聊过。
export function pickTopicToAsk(doc, input = {}, now = Date.now()) {
  const scopes = visibleScopes(input.scope)
  const rawCooldown = Number(input.cooldownMs)
  const cooldown = Number.isFinite(rawCooldown) && rawCooldown >= 0 ? rawCooldown : 6 * 60 * 60_000
  const maxAttempts = clampInt(input.maxAttempts, 1, 10, 3)
  const rows = doc.topics.filter((t) => t.status === 'open' && scopes.includes(t.scope) && !t.closedAt)
    .filter((t) => (t.attempts || 0) < maxAttempts)
    .filter((t) => !t.nextAskAt || Number(t.nextAskAt) <= now)
    .filter((t) => !t.lastTouchAt || now - Number(t.lastTouchAt) >= Math.min(cooldown, 2 * 60 * 60_000))
    .sort((a, b) => (b.priority || 0) - (a.priority || 0) || (a.openedAt || 0) - (b.openedAt || 0))
  return rows[0] ? publicTopic(rows[0]) : null
}

export function markTopicAsked(doc, id, now = Date.now(), nextAfterMs = 24 * 60 * 60_000) {
  const t = doc.topics.find((x) => x.id === id)
  if (!t) return null
  t.attempts = (t.attempts || 0) + 1
  t.lastTouchAt = now
  t.nextAskAt = now + Math.max(60_000, Number(nextAfterMs) || 24 * 60 * 60_000)
  return publicTopic(t)
}

// ── 给模型看的那段注入文本 ─────────────────────────────────────────────────
export function recall(doc, input = {}, now = Date.now()) {
  const scope = normalizeScope(input.scope)
  const query = normalizeText(input.query)
  const limit = clampInt(input.limit, 1, 12, 6)
  const scopes = visibleScopes(scope)

  let facts = activeFacts(doc, scopes).filter((f) => !isExpired(f, now))
  if (query) {
    facts = facts
      .map((f) => ({ f, s: scoreFact(f, query, now) }))
      .filter((x) => x.s >= 0.18)
      .sort((a, b) => b.s - a.s)
      .map((x) => x.f)
  } else {
    facts = facts.slice().sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) ||
      (b.updatedAt || 0) - (a.updatedAt || 0))
  }
  const picked = facts.slice(0, limit)
  touchHits(doc, picked.map((f) => f.id), query, now)

  const topics = doc.topics
    .filter((t) => t.status === 'open' && scopes.includes(t.scope))
    .sort((a, b) => (b.priority || 0) - (a.priority || 0) || (a.openedAt || 0) - (b.openedAt || 0))
    .slice(0, 3)

  const todayStart = startOfDay(now)
  const episodes = doc.episodes
    .filter((e) => scopes.includes(e.scope) && Number(e.at) >= todayStart)
    .sort((a, b) => b.at - a.at)
    .slice(0, 3)

  const lines = []
  if (picked.length) {
    lines.push('【本地记忆】下面这些是手机本地库里的记录（时间由系统给出，别自己推算）：')
    for (const f of picked) {
      const bits = [dateOf(f.createdAt)]
      if (f.pinned) bits.push('钉住')
      if (f.importance === 2) bits.push('重要')
      lines.push(`- ${bits.join(' ')} ${f.text}`)
    }
  }
  if (topics.length) {
    lines.push('【还没聊完的话题】')
    for (const t of topics) {
      const days = Math.max(0, Math.floor((now - t.openedAt) / DAY))
      lines.push(`- ${t.title}${t.detail ? `（${t.detail}）` : ''} · ${days === 0 ? '今天' : `${days} 天前`}打开`)
    }
  }
  if (episodes.length) {
    lines.push('【今天记下的】')
    for (const e of episodes) lines.push(`- ${e.text}`)
  }
  if (lines.length) {
    lines.push('只提相关的，不要逐条复述，也不要说“我记得你说过”；不确定的就先问。')
  }
  return { text: lines.join('\n'), facts: picked.map(publicFact), topics: topics.map(publicTopic), episodes }
}

export function startOfDay(ts) {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export function dateOf(ts) {
  const d = new Date(Number(ts) || 0)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function timeOf(ts) {
  const d = new Date(Number(ts) || 0)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

// ── 统计 / 清理 ───────────────────────────────────────────────────────────
export function stats(doc, input = {}, now = Date.now()) {
  const scopes = input.scope === 'all' ? null : visibleScopes(input.scope)
  const facts = doc.facts.filter((f) => f.status === 'active' && (!scopes || scopes.includes(f.scope)))
  const pinned = facts.filter((f) => f.pinned).length
  const topics = doc.topics.filter((t) => t.status === 'open' && (!scopes || scopes.includes(t.scope))).length
  const today = doc.episodes.filter((e) => Number(e.at) >= startOfDay(now) && (!scopes || scopes.includes(e.scope))).length
  const fuzzy = facts.filter((f) => !f.pinned && clarity(f, now) < 0.5).length
  return { facts: facts.length, pinned, topics, today, fuzzy, revised: doc.facts.filter((f) => f.status === 'superseded').length }
}

/** 每次写盘前跑一遍：过期条目降级、旧痕迹裁掉。 */
export function maintain(doc, now = Date.now()) {
  let changed = false
  for (const f of doc.facts) {
    if (f.status === 'active' && isExpired(f, now)) {
      f.status = 'expired'
      f.updatedAt = now
      changed = true
    }
  }
  if (doc.hits.length > 2000) {
    doc.hits.splice(0, doc.hits.length - 2000)
    changed = true
  }
  if (doc.tombstones.length > 1000) {
    doc.tombstones.splice(0, doc.tombstones.length - 1000)
    changed = true
  }
  return changed
}

export function exportMarkdown(doc, now = Date.now()) {
  const out = []
  out.push('# 大肥鱼本地记忆')
  out.push('')
  out.push(`导出时间：${dateOf(now)} ${timeOf(now)}`)
  out.push('')
  out.push(`## 事实（${doc.facts.filter((f) => f.status === 'active').length} 条有效）`)
  out.push('')
  const facts = doc.facts.filter((f) => f.status === 'active')
    .sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (b.updatedAt || 0) - (a.updatedAt || 0))
  for (const f of facts) {
    out.push(`- ${dateOf(f.createdAt)}${f.pinned ? ' [钉住]' : ''}${f.importance === 2 ? ' [重要]' : ''} ${f.text}`)
  }
  out.push('')
  const open = doc.topics.filter((t) => t.status === 'open')
  out.push(`## 未完成的话题（${open.length}）`)
  out.push('')
  for (const t of open) out.push(`- ${dateOf(t.openedAt)} ${t.title}${t.detail ? ` — ${t.detail}` : ''}`)
  out.push('')
  out.push(`## 共同经历（最近 ${Math.min(50, doc.episodes.length)} 条）`)
  out.push('')
  for (const e of doc.episodes.slice(-50)) out.push(`- ${dateOf(e.at)} ${e.text}`)
  out.push('')
  return out.join('\n')
}

function clampInt(value, min, max, fallback) {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.max(min, Math.min(max, Math.round(n)))
}
