/**
 * 记忆模块 —— 读真实存储，写自己的叠加层
 *
 * 数据来源（**只读**）：
 *   · ~/.dsh/fish-memory/session-*.json      —— dsh-fish-memory 的会话记忆（含 policy / memories / traces）
 *   · ~/.dsh/surfcore/characters/<id>/memory/memory-card.json —— 跨会话记忆卡（records[]）
 *
 * 写的地方只有一处：~/.dsh/partner/memory-corrections.json
 *   用户「纠正」= 隐藏 / 置顶 / 改一句话。那是**叠加层**，不是改写别人的库 ——
 *   两个上游插件各自还在维护自己的文件，谁都不该被第三方悄悄改。
 *
 * 隐私边界（写死在这，不是文档里的君子协定）：
 *   · 只读上面几个 JSON。**不读屏幕、不抓窗口标题、不截屏、不读剪贴板。**
 *   · 只认 /^session-[A-Za-z0-9._-]+\.json$/ 这一种文件名，别的一律不碰。
 */
import fs from 'node:fs'
import path from 'node:path'

import { dshHome, partnerDir } from './config.mjs'

const CLARITY_RANK = { vivid: 4, clear: 3, fuzzy: 2, trace: 1 }

export const correctionsPath = () => path.join(partnerDir(), 'memory-corrections.json')

function readJson(p) {
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''))
  } catch {
    return null
  }
}

function writeJson(p, value) {
  fs.mkdirSync(path.dirname(p), { recursive: true })
  const tmp = p + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(value, null, 2), 'utf8')
  fs.renameSync(tmp, p)
}
// ── 存储发现 ────────────────────────────────────────────────────────────────
export function fishMemoryDir() { return path.join(dshHome(), 'fish-memory') }

/** 所有会话记忆文件，最近改过的在前。**只认 session-*.json**，别的一律不碰。 */
export function listStreams() {
  const dir = fishMemoryDir()
  let names = []
  try {
    names = fs.readdirSync(dir)
  } catch {
    return []
  }
  const out = []
  for (const n of names) {
    if (!/^session-[A-Za-z0-9._-]+\.json$/.test(n)) continue // 排除 .bak / 诊断日志 / 别的插件的文件
    const file = path.join(dir, n)
    let st = null
    try { st = fs.statSync(file) } catch { continue }
    out.push({ file, name: n, sessionId: n.slice('session-'.length, -'.json'.length), mtime: st.mtimeMs, size: st.size })
  }
  return out.sort((a, b) => b.mtime - a.mtime)
}

export function surfcoreCardPath() {
  const dir = path.join(dshHome(), 'surfcore', 'characters')
  const cur = readJson(path.join(dir, 'current.json'))
  if (cur && cur.id) {
    const charDir = cur.dir || path.join(dir, String(cur.id))
    return path.join(charDir, 'memory', 'memory-card.json')
  }
  return null
}

// ── 适配层：两种上游形状 → 一套记录 ─────────────────────────────────────────
/**
 * fish-memory 的形状（实测 1.4.3）：
 *   { version, policy:{baseHalfLifeDays,…}, memories:[{id,text,at,createdAt,category,importance,emotion,tags,source,reinforcedAt,lastReinforcedDay,事实键}], traces, deleted, purgeEpoch }
 * surfcore 记忆卡：{ records:[{id,title,text,tags,importance(0-100),clarity,pinned,anchor,createdAt,recallCount,decaySteps,isInitial,firstMet}] }
 *
 * 两者都兜住；都不认识就如实返回 shape:'unknown' —— **不猜、不编**。
 */
export function adaptStream(raw) {
  if (!raw || typeof raw !== 'object') return { shape: 'unknown', records: [], policy: null, problems: ['文件不是 JSON 对象'] }
  const problems = []
  let list = null
  let shape = 'unknown'
  if (Array.isArray(raw.memories)) { list = raw.memories; shape = 'fish-memory' }
  else if (Array.isArray(raw.records)) { list = raw.records; shape = 'surfcore-card' }
  else if (Array.isArray(raw.items)) { list = raw.items; shape = 'items' }
  if (!list) {
    problems.push('既没有 memories[] 也没有 records[]：上游换了形状，面板会显示这条，不会瞎猜')
    return { shape: 'unknown', records: [], policy: raw.policy || null, problems, version: raw.version }
  }
  const policy = raw.policy && typeof raw.policy === 'object' ? raw.policy : null
  const records = list.map((m, i) => normalizeRecord(m, i, shape)).filter(Boolean)
  return { shape, records, policy, problems, version: raw.version, deleted: Array.isArray(raw.deleted) ? raw.deleted.length : 0, traces: Array.isArray(raw.traces) ? raw.traces.length : 0 }
}

function asTime(v) {
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) return v
  if (typeof v === 'string' && v) {
    const t = Date.parse(v)
    if (Number.isFinite(t)) return t
  }
  return 0
}

/** importance 在不同上游是 0–1 或 0–100：统一成 0–100，且**记住原始值**，不丢。 */
function normalImportance(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return { value: null, scale: 'none', raw: v === undefined ? null : v }
  if (n <= 1) return { value: Math.round(n * 100), scale: '0-1', raw: n }
  return { value: Math.round(n), scale: '0-100', raw: n }
}

function normalizeRecord(m, i, shape) {
  if (!m || typeof m !== 'object') return null
  const text = String(m.text === undefined || m.text === null ? (m.content || '') : m.text).trim()
  const title = String(m.title || '').trim()
  if (!text && !title) return null
  const at = asTime(m.createdAt) || asTime(m.at) || asTime(m.time)
  const imp = normalImportance(m.importance)
  const tags = Array.isArray(m.tags) ? m.tags.map(String) : []
  const fact = m['事实键'] !== undefined ? m['事实键'] : (m.factKey !== undefined ? m.factKey : m.fact)
  return {
    id: String(m.id || shape + '-' + i),
    text: text || title,
    title,
    at,
    importance: imp.value,
    importanceRaw: imp.raw,
    importanceScale: imp.scale,
    category: String(m.category || m.kind || ''),
    emotion: m.emotion === null || m.emotion === undefined ? null : String(m.emotion),
    tags,
    fact: fact === null || fact === undefined ? '' : String(fact),
    source: String(m.source || m.platform || ''),
    pinned: Boolean(m.pinned || m.anchor),
    isInitial: Boolean(m.isInitial),
    firstMet: Boolean(m.firstMet),
    reinforcedAt: asTime(m.reinforcedAt),
    recallCount: Number(m.recallCount) || 0,
    clarityRaw: typeof m.clarity === 'string' ? m.clarity : '',
    decaySteps: Number(m.decaySteps) || 0,
  }
}
// ── 衰减：算出来的，不写回上游 ──────────────────────────────────────────────
/**
 * 半衰期取自上游 policy.baseHalfLifeDays（实测 30 天）；没有就用 30。
 * 强化过（reinforcedAt）就从强化那一刻重新算。这是「保鲜度」，用来排序和显示，
 * **不改文件** —— 上游自己在维护它自己的衰减。
 */
export function vitality(rec, now, baseHalfLifeDays) {
  const t = now === undefined ? Date.now() : now
  const half = Math.max(0.5, Number(baseHalfLifeDays) || 30)
  const anchor = rec.reinforcedAt || rec.at
  if (!anchor) return { score: 0.5, halfLifeDays: half, unknown: true }
  const days = Math.max(0, (t - anchor) / 86400000)
  return { score: Math.pow(0.5, days / half), halfLifeDays: half, unknown: false, ageDays: days }
}

/** 保鲜度 → 清晰档（上游自己给 clarity 时以它为准）。 */
export function clarityOf(rec, v) {
  if (rec.clarityRaw && CLARITY_RANK[rec.clarityRaw]) return rec.clarityRaw
  const s = v.score
  if (s >= 0.7) return 'vivid'
  if (s >= 0.45) return 'clear'
  if (s >= 0.2) return 'fuzzy'
  return 'trace'
}

/** 一条记录的身份：正文（截断）+ 事实键。两把钥匙，命中任意一把就算同一条。 */
export function keysOfRecord(rec) {
  const out = ['text:' + String(rec.text || '').slice(0, 60)]
  if (rec.fact) out.push('fact:' + rec.fact)
  return out
}

// ── 纠正叠加层（本插件唯一会写的文件）──────────────────────────────────────
export function loadCorrections() {
  const j = readJson(correctionsPath())
  const out = { hide: [], pin: [], text: {}, note: {}, updatedAt: 0 }
  if (!j || typeof j !== 'object') return out
  if (Array.isArray(j.hide)) out.hide = j.hide.map(String)
  if (Array.isArray(j.pin)) out.pin = j.pin.map(String)
  if (j.text && typeof j.text === 'object') for (const k of Object.keys(j.text)) if (typeof j.text[k] === 'string') out.text[k] = j.text[k]
  if (j.note && typeof j.note === 'object') for (const k of Object.keys(j.note)) if (typeof j.note[k] === 'string') out.note[k] = j.note[k]
  out.updatedAt = Number(j.updatedAt) || 0
  return out
}

export function saveCorrections(c) {
  const value = { version: 1, hide: c.hide, pin: c.pin, text: c.text, note: c.note, updatedAt: Date.now() }
  writeJson(correctionsPath(), value)
  return value
}

/** 对一条记忆做纠正动作。action: hide | show | pin | unpin | rewrite | note */
export function correct(recId, action, value, store) {
  const id = String(recId || '').trim()
  if (!id) throw new Error('缺少记忆 id')
  const c = store || loadCorrections()
  const drop = (arr) => arr.filter((x) => x !== id)
  const act = String(action)
  if (act === 'hide') c.hide = Array.from(new Set(drop(c.hide).concat([id])))
  else if (act === 'show') c.hide = drop(c.hide)
  else if (act === 'pin') c.pin = Array.from(new Set(c.pin.concat([id])))
  else if (act === 'unpin') c.pin = drop(c.pin)
  else if (act === 'rewrite') {
    const t = String(value || '').trim()
    if (!t) throw new Error('改写内容不能为空')
    c.text[id] = t.slice(0, 2000)
  } else if (act === 'note') {
    const t = String(value || '').trim()
    if (t) c.note[id] = t.slice(0, 500)
    else delete c.note[id]
  } else throw new Error('不认识的动作：' + act)
  return saveCorrections(c)
}
// ── 读一个会话（或最近改动的那个）───────────────────────────────────────────
/**
 * @param {string} [sessionId]
 * @returns {{ok, reason, file, shape, records, policy, problems, streams, sessionId}}
 */
export function readMemory(sessionId) {
  const streams = listStreams()
  if (!streams.length) {
    return { ok: false, reason: 'no-fish-memory', streams: [], records: [], problems: [], shape: 'none' }
  }
  let pick = null
  let fellBack = false
  if (sessionId) {
    const want = String(sessionId).replace(/^session-/, '')
    const hit = streams.filter((s) => s.sessionId === want)
    if (hit.length) pick = hit[0]
  }
  if (!pick) {
    pick = streams[0]
    fellBack = Boolean(sessionId)
  }
  const raw = readJson(pick.file)
  if (!raw) {
    return { ok: false, reason: 'unreadable', file: pick.file, streams, records: [], problems: ['文件读不出来（可能正在写入）'], shape: 'unknown' }
  }
  const adapted = adaptStream(raw)
  return {
    ok: adapted.records.length > 0 || adapted.shape !== 'unknown',
    reason: adapted.shape === 'unknown' ? 'shape' : (adapted.records.length ? '' : 'empty'),
    file: pick.file,
    sessionId: pick.sessionId,
    fellBackTo: fellBack ? pick.sessionId : '',
    streams,
    shape: adapted.shape,
    policy: adapted.policy,
    problems: adapted.problems,
    deleted: adapted.deleted,
    traces: adapted.traces,
    version: adapted.version,
    records: adapted.records,
  }
}

/**
 * 长期记忆：叠加 surfcore 的跨会话记忆卡（有的话），去重合一。
 * partner 的纠正层在这里生效（隐藏 / 置顶 / 改写），**原记录 id 不变**。
 */
export function loadLongTerm(cfg, sessionId) {
  const primary = readMemory(sessionId)
  const card = surfcoreCardPath()
  const cardRaw = card ? readJson(card) : null
  const cardAdapted = cardRaw ? adaptStream(cardRaw) : null
  const corr = loadCorrections()
  const hidden = new Set(corr.hide)
  const pinnedIds = new Set(corr.pin)
  const baseHalf = primary.policy && primary.policy.baseHalfLifeDays !== undefined ? primary.policy.baseHalfLifeDays : (cardAdapted && cardAdapted.policy ? cardAdapted.policy.baseHalfLifeDays : undefined)

  const merged = []
  const seen = new Set()
  // 一条记录可能有**两个**身份：事实键 和 正文。只按一个去重会漏 ——
  // 上游 A 带事实键、上游 B 同内容不带，按「有 fact 就用 fact」比就把同一条记两遍。
  const keysOf = keysOfRecord
  const push = (rec, origin) => {
    const keys = keysOf(rec)
    if (keys.some((k) => seen.has(k))) return
    for (const k of keys) seen.add(k)
    const v = vitality(rec, Date.now(), baseHalf)
    merged.push({
      ...rec,
      origin,
      clarity: clarityOf(rec, v),
      vitality: Math.round(v.score * 1000) / 1000,
      text: corr.text[rec.id] === undefined ? rec.text : corr.text[rec.id],
      rewritten: corr.text[rec.id] !== undefined,
      note: corr.note[rec.id] || '',
      hidden: hidden.has(rec.id),
      pinned: rec.pinned || pinnedIds.has(rec.id),
    })
  }
  const fishOrigin = primary.shape === 'fish-memory' ? 'fish-memory' : primary.shape
  for (const r of primary.records) push(r, fishOrigin)
  for (const r of (cardAdapted ? cardAdapted.records : [])) push(r, 'surfcore-card')

  const visible = merged.filter((r) => !r.hidden)
  const now = Date.now()
  const shortHours = Number(cfg && cfg.memory ? cfg.memory.shortTermHours : 24) || 24
  const shortTerm = visible.filter((r) => r.at && now - r.at <= shortHours * 3600000)
  const facts = visible.filter((r) => r.fact)
  return {
    primary,
    // everything 是**没过滤**的全集（含被藏起来的）；all 才是可见的。
    // 没有 everything 就说不清「藏了几条」，下游还会把藏起来的当成不存在。
    everything: merged,
    keysOf,
    card: card ? { file: card, shape: cardAdapted ? cardAdapted.shape : 'none', count: cardAdapted ? cardAdapted.records.length : 0, problems: cardAdapted ? cardAdapted.problems : [] } : null,
    all: visible,
    hiddenCount: merged.length - visible.length,
    shortTerm,
    longTerm: visible,
    facts,
    corrections: { count: corr.hide.length + corr.pin.length + Object.keys(corr.text).length + Object.keys(corr.note).length, at: corr.updatedAt, file: correctionsPath() },
  }
}
// ── 重要事实提取（确定性规则，不是模型猜的）────────────────────────────────
/**
 * 「重要」的判据全部可复述：
 *   · 上游标了 事实键（记忆的稳定键）→ 一定算
 *   · importance ≥ 70（0–100）
 *   · 置顶 / 初次相遇
 *   · tags 里带 owner / 偏好 / 约定 / 红线 这类词
 * 不满足就不算。宁可少，不可编。
 */
const FACT_TAG = /owner|主人|偏好|喜欢|讨厌|约定|承诺|红线|禁忌|习惯|名字|生日|称呼/i

export function extractFacts(longTerm) {
  const out = []
  for (const r of longTerm) {
    const hitTag = (r.tags || []).some((t) => FACT_TAG.test(String(t)))
    const strong = Boolean(r.fact) || (r.importance !== null && r.importance >= 70) || r.pinned || r.firstMet || hitTag
    if (!strong) continue
    const why = []
    if (r.fact) why.push('事实键 ' + r.fact)
    if (r.importance !== null && r.importance >= 70) why.push('重要度 ' + r.importance)
    if (r.pinned) why.push('置顶')
    if (r.firstMet) why.push('初次相遇')
    if (hitTag) why.push('标签命中')
    out.push({ ...r, why: why.join('、') })
  }
  return out.sort((a, b) => (b.importance === null ? 0 : b.importance) - (a.importance === null ? 0 : a.importance))
}

// ── 检索 ────────────────────────────────────────────────────────────────────
function tokens(s) {
  const t = String(s || '').toLowerCase()
  const out = new Set()
  for (const w of t.split(/[^a-z0-9\u4e00-\u9fff]+/)) if (w.length >= 2) out.add(w)
  // 中文没有空格：按 2-gram 兜住
  const cjk = t.replace(/[^\u4e00-\u9fff]+/g, ' ')
  for (const seg of cjk.split(/\s+/)) for (let i = 0; i + 1 < seg.length; i += 1) out.add(seg.slice(i, i + 2))
  return out
}

/**
 * 检索：字面命中 + 重要度 + 保鲜度 + 置顶加成。**纯本地、不发网络、不调模型。**
 * 分数写进结果里，面板上能看见为什么是这一条 —— 不解释的检索等于黑箱。
 */
export function recall(query, records, opts) {
  const o = opts || {}
  const q0 = String(query || '').trim()
  const limit = Math.max(1, Number(o.limit) || 6)
  const floor = Number.isFinite(Number(o.floor)) ? Number(o.floor) : 0.12
  const q = tokens(q0)
  const now = Date.now()
  const scored = (records || []).map((r) => {
    const hay = tokens(r.text + ' ' + (r.title || '') + ' ' + (r.tags || []).join(' '))
    let hit = 0
    for (const t of q) if (hay.has(t)) hit += 1
    const lexical = q.size ? hit / q.size : 0
    const imp = (r.importance === null || r.importance === undefined ? 50 : r.importance) / 100
    const vit = typeof r.vitality === 'number' ? r.vitality : vitality(r, now).score
    const recency = r.at ? Math.pow(0.5, Math.max(0, now - r.at) / (7 * 86400000)) : 0.3
    const pin = r.pinned ? 0.15 : 0
    const score = 0.55 * lexical + 0.2 * imp + 0.15 * vit + 0.1 * recency + pin
    return { ...r, score: Math.round(score * 1000) / 1000, hitTokens: hit, lexical: Math.round(lexical * 1000) / 1000 }
  })
  return scored
    .filter((r) => (q0 ? r.lexical > 0 : true))
    .filter((r) => r.score >= floor)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
}

/** 统计：给人格面板的「角色状态」和记忆面板的标题用。全部派生。 */
export function memoryStats(lt) {
  const all = (lt && lt.all) || []
  const ats = all.map((r) => r.at).filter(Boolean).sort((a, b) => a - b)
  return {
    total: all.length,
    shortTerm: ((lt && lt.shortTerm) || []).length,
    facts: ((lt && lt.facts) || []).length,
    hidden: (lt && lt.hiddenCount) || 0,
    firstAt: ats.length ? ats[0] : 0,
    lastAt: ats.length ? ats[ats.length - 1] : 0,
    streams: (lt && lt.primary && lt.primary.streams ? lt.primary.streams.length : 0),
    shape: (lt && lt.primary && lt.primary.shape) || 'none',
  }
}
// ── partner 自己那份记忆库（唯一可写的记忆存储）──────────────────────────────
/**
 * 为什么要有自己的库：
 *   fish-memory 和 surfcore 各自维护自己的文件，第三方**不该**往里写 —— 一个写者原则。
 *   但「提取重要事实」「用户纠正」需要一个可写的落点，所以 partner 存自己的一份，
 *   形状跟上游对齐（同样的字段名），读的时候一起合并、各自标 origin。
 * 隐私：只存用户/模型**明确说出来的**内容，不采集屏幕。
 */
export const ownStorePath = () => path.join(partnerDir(), 'memory-own.json')

export function readOwn() {
  const j = readJson(ownStorePath())
  if (!j || !Array.isArray(j.memories)) return { version: 1, memories: [] }
  return { version: Number(j.version) || 1, memories: j.memories }
}

/**
 * 追加一条自己的记忆。去重按 事实键 或 正文前 60 字 —— 同一件事说两遍不该存两条。
 * @returns {{ok, id, duplicate, count}}
 */
export function appendOwn(input) {
  const text = String((input && input.text) || '').trim()
  if (!text) throw new Error('记忆内容不能为空')
  if (text.length > 2000) throw new Error('单条记忆最多 2000 字')
  const store = readOwn()
  const fact = String((input && input.fact) || '').trim()
  const key = fact ? 'fact:' + fact : 'text:' + text.slice(0, 60)
  const hit = store.memories.filter((m) => (m.fact ? 'fact:' + m.fact : 'text:' + String(m.text || '').slice(0, 60)) === key)[0]
  if (hit) {
    hit.reinforcedAt = Date.now()
    hit.recallCount = (Number(hit.recallCount) || 0) + 1
    writeJson(ownStorePath(), store)
    return { ok: true, id: String(hit.id), duplicate: true, count: store.memories.length }
  }
  const now = Date.now()
  const rec = {
    id: 'own-' + now.toString(36) + '-' + Math.random().toString(36).slice(2, 6),
    text,
    createdAt: new Date(now).toISOString(),
    at: now,
    importance: input && input.importance !== undefined ? Number(input.importance) : 60,
    tags: Array.isArray(input && input.tags) ? input.tags.map(String).slice(0, 8) : [],
    source: 'partner',
    fact,
    reinforcedAt: null,
  }
  store.memories.push(rec)
  if (store.memories.length > 500) store.memories = store.memories.slice(-500) // 有界：别让它无限长
  writeJson(ownStorePath(), store)
  return { ok: true, id: rec.id, duplicate: false, count: store.memories.length }
}

/**
 * 长期记忆的完整视图 = 上游两份（只读）+ partner 自己那份（可写）+ 纠正叠加层。
 */
export function loadLongTermFull(cfg, sessionId) {
  const base = loadLongTerm(cfg, sessionId)
  const own = readOwn()
  const corr = loadCorrections()
  const hidden = new Set(corr.hide)
  const pinnedIds = new Set(corr.pin)
  // ⚠️ 必须从 **everything**（未过滤全集）起，不能用 all（可见集）：
  //    否则被藏起来的那条根本不在池子里，hiddenCount 永远是 0，
  //    用户会看到「我已经藏了 3 条」和「藏起 0 条」同时成立。
  const merged = (base.everything || base.all).slice()
  const seen = new Set()
  for (const r of merged) for (const k of keysOfRecord(r)) seen.add(k)
  for (const raw of own.memories) {
    const rec = normalizeRecord(raw, merged.length, 'partner-own')
    if (!rec) continue
    const keys = keysOfRecord(rec)
    if (keys.some((k) => seen.has(k))) continue
    for (const k of keys) seen.add(k)
    const v = vitality(rec, Date.now(), base.primary && base.primary.policy ? base.primary.policy.baseHalfLifeDays : undefined)
    merged.push({
      ...rec,
      origin: 'partner-own',
      clarity: clarityOf(rec, v),
      vitality: Math.round(v.score * 1000) / 1000,
      text: corr.text[rec.id] === undefined ? rec.text : corr.text[rec.id],
      rewritten: corr.text[rec.id] !== undefined,
      note: corr.note[rec.id] || '',
      hidden: hidden.has(rec.id),
      pinned: rec.pinned || pinnedIds.has(rec.id),
    })
  }
  const visible = merged.filter((r) => !r.hidden)
  const now = Date.now()
  const shortHours = Number(cfg && cfg.memory ? cfg.memory.shortTermHours : 24) || 24
  const shortTerm = visible.filter((r) => r.at && now - r.at <= shortHours * 3600000)
  return {
    ...base,
    all: visible,
    hiddenCount: merged.length - visible.length,
    shortTerm,
    longTerm: visible,
    facts: visible.filter((r) => r.fact),
    own: { count: own.memories.length, file: ownStorePath() },
  }
}
