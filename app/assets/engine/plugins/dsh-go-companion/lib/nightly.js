// dsh-go-companion · 夜间整理（第七轮 B2）
//
// 暗思考分两层，别混：
//   1. muse.js —— 只看手机的客观变化，**纯函数、绝不调模型**，那四条硬边界一条都没动。
//   2. 本文件 —— 每天两次把「索引」交给模型，让它整理出关联 / 缺口 / 要带上的事。
//
// 产物**不是消息**：它是给墙、给下一轮注入、给 C 组出题用的素材。所以：
//   · 走 `llm.stream`（和判球同一条通道），不进任何会话——不会以一条用户消息的样子
//     出现在他和她的聊天记录里，也不占会话上下文。
//   · 本文件不 import chat/desk，不产生任何要发出去的话（有源码扫描测试守着）。
//
// 三条硬约束（都有测试）：
//   · 输入只有索引和结构化数据：id、标题、条数、文件名。笔记原文一个字都不进去。
//   · 产物必须是严格 JSON，代码逐条校验；对象 id / 账本行号解析不到的条目直接丢，不重试。
//   · 每条产物带 `until`（默认 3 天），被用过一次续期，没被用过到期删。

import { summarize } from './ledger.js'
import { minuteOfDay, startOfDay } from './tz.js'

/** 每天两次：午间、睡前。小时窗口按当地时区。 */
export const NIGHTLY_SLOTS = Object.freeze([13, 22])
export const NIGHTLY_SLOT_SPAN = 2

export const NIGHTLY_MAX_PER_DAY = 2

/** 每类产物最多几条。多出来的丢，不排队。 */
export const NIGHTLY_ITEMS_MAX = 3

/** 产物默认活 3 天；被用过一次续期。 */
export const NIGHTLY_TTL_MS = 3 * 86_400_000

export const NIGHTLY_TIMEOUT_MS = 90_000

/** 一次整理最多带多少个索引对象进 prompt。 */
export const NIGHTLY_OBJECTS_MAX = 24

export const WHY_MAX = 40
export const MISSING_MAX = 40
export const CARRY_MAX = 60
export const LABEL_MAX = 60

const KINDS = Object.freeze(['links', 'gaps', 'carry'])

function clean(raw) {
  return String(raw == null ? '' : raw).replace(/\s+/g, ' ').trim()
}

function cut(raw, max) {
  const s = clean(raw)
  return s.length > max ? s.slice(0, max) : s
}

// ---------------------------------------------------------------- 该不该跑

/** 现在落在哪个槽位：0 午间、1 睡前、null 都不在。 */
export function nightlySlot(now, tz) {
  const hour = localHour(now, tz)
  for (let i = 0; i < NIGHTLY_SLOTS.length; i += 1) {
    const a = NIGHTLY_SLOTS[i]
    if (hour >= a && hour < a + NIGHTLY_SLOT_SPAN) return i
  }
  return null
}

function localHour(now, tz) {
  // 时区口径统一走 tz.js：自己算夏令时迟早会算错（P0-2 的教训）。
  return Math.floor(minuteOfDay(Number(now) || 0, tz) / 60)
}

/**
 * 该不该跑一次夜间整理。
 * 用户正在对话时不跑（他人在，整理没意义，还占一次模型调用）。
 * @returns {{due:boolean, slot:number|null, why:string}}
 */
export function nightlyDue(entries, now, tz, { busy = false, maxPerDay = NIGHTLY_MAX_PER_DAY } = {}) {
  const at = Number(now) || 0
  const slot = nightlySlot(at, tz)
  if (busy) return { due: false, slot, why: 'in-conversation' }
  if (slot === null) return { due: false, slot: null, why: 'no-slot' }
  const dayStart = startOfDay(at, tz)
  let count = 0
  let sameSlot = false
  for (const e of entries || []) {
    if (e?.state !== 'nightly') continue
    if (Number(e.at) < dayStart) continue
    count += 1
    if (Number(e.slot) === slot) sameSlot = true
  }
  // 每日上限优先于槽位：跑满两次就是跑满，别把原因说成"这个槽跑过了"。
  if (count >= maxPerDay) return { due: false, slot, why: 'daily-cap' }
  if (sameSlot) return { due: false, slot, why: 'ran-slot' }
  return { due: true, slot, why: '' }
}

// ---------------------------------------------------------------- 输入：只有索引

/**
 * 把当下能被引用的东西编成一张 id 表。模型只能引这里面的 id，引别的当解析不到。
 * **只给 id、类型、标题/文件名，不给原文**（立场她自己说过的话算"列表"，那本来就在账本里）。
 */
export function nightlyInputs({ entries, candidates, stances, notes, tz } = {}, now) {
  const at = Number(now) || 0
  const objects = []
  const seen = new Set()
  const push = (id, kind, label) => {
    if (!id || seen.has(id) || objects.length >= NIGHTLY_OBJECTS_MAX) return
    seen.add(id)
    objects.push({ id, kind, label: cut(label, LABEL_MAX) })
  }
  for (const t of candidates?.topics || []) {
    if (!t?.id) continue
    push('topic:' + String(t.id), 'topic', t.title || t.detail || '')
  }
  for (const f of candidates?.facts || []) {
    if (!f?.id) continue
    push('fact:' + String(f.id), 'fact', f.text || '')
  }
  for (const e of candidates?.episodes || []) {
    if (!Number.isFinite(Number(e?.at))) continue
    push('event:' + String(Number(e.at)), 'event', e.text || '')
  }
  for (const s of stances?.list || []) {
    if (!s?.id || s.status !== 'held') continue
    push('stance:' + String(s.id), 'stance', s.text || '')
  }
  for (const n of notes || []) {
    const name = cut(n, LABEL_MAX)
    if (name) push('note:' + name, 'note', name)
  }
  const dayStart = startOfDay(at, tz)
  const sum = summarize(entries || [], dayStart)
  return {
    at,
    dayStart,
    counts: sum.counts,
    objects,
    // carry 的 evidence 是账本行号：上限就是这次交给模型看的那几百行。
    ledgerLines: (entries || []).length,
  }
}

// ---------------------------------------------------------------- prompt

/** 系统提示：定身份 + 定出口。它是整理，不是聊天。 */
export function nightlySystem() {
  return [
    '你是大肥鱼的暗思考层。你在她不在场的时候整理她的记录，产物给她自己下一轮用，不是发给谁的消息。',
    '你手里**没有工具**，也不会把你的输出发出去：这是纯粹的一次整理，不是一轮对话。',
    '所以只用下面给的索引（id + 标题 + 条数），不要假装查了笔记原文，也不要编原文。',
    '只输出一个 JSON 对象，不要解释、不要前后缀、不要说要说的话。',
  ].join('\n')
}

/** 用户提示：把索引和要什么讲清楚。 */
export function nightlyUser(inputs) {
  const lines = []
  const c = inputs?.counts || {}
  lines.push('今天账本：发了 ' + Number(c.generated || 0) + ' 条、他接了 ' + Number(c.replied || 0) +
    ' 次、没接 ' + Number(c.none || 0) + ' 次、生成失败 ' + Number(c.failed || 0) + ' 次。')
  const objs = Array.isArray(inputs?.objects) ? inputs.objects : []
  if (!objs.length) lines.push('索引是空的——那就三类都输出空数组。')
  else {
    lines.push('能引用的对象（只能引这些 id，引别的当不存在）：')
    for (const o of objs) lines.push('- ' + o.id + ' ｜' + o.kind + ' ｜' + o.label)
  }
  lines.push('')
  lines.push('整理三件事，每类最多 ' + NIGHTLY_ITEMS_MAX + ' 条：')
  lines.push('1. links：两个对象之间有什么真实的关联（a、b 都要是上面的 id，why ≤' + WHY_MAX + ' 字）。')
  lines.push('2. gaps：某个对象上他没交代清楚的地方（about 是上面的 id，missing ≤' + MISSING_MAX + ' 字）。')
  lines.push('3. carry：值得她下一轮自己记着的一件事（text ≤' + CARRY_MAX + ' 字，evidence 给账本行号，从 1 数）。')
  lines.push('账本行号指的是今天账本的第几条（从 1 开始，最多到 ' + (Array.isArray(inputs?.ledgerLines) ? inputs.ledgerLines : 0) + '）。')
  lines.push('没有把握就少写或者写空数组——宁缺毋滥，编出来的关联比没有更糟。')
  lines.push('输出格式（只输出这个）：')
  lines.push('{"links":[{"a":"id","b":"id","why":"…"}],"gaps":[{"about":"id","missing":"…"}],"carry":[{"text":"…","evidence":[1]}]}')
  return lines.join('\n')
}

// ---------------------------------------------------------------- 校验

/**
 * 抠出并校验 JSON。整段不是 JSON 才算失败；单条不合格就丢掉那一条（不重试）。
 * @returns {{ok:true, value:{links:object[],gaps:object[],carry:object[]}, dropped:number} | {ok:false, problems:string[]}}
 */
export function parseNightly(raw) {
  const text = String(raw == null ? '' : raw)
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start < 0 || end <= start) return { ok: false, problems: ['不是一个 JSON 对象'] }
  let obj
  try {
    obj = JSON.parse(text.slice(start, end + 1))
  } catch (e) {
    return { ok: false, problems: ['JSON 解析失败：' + String(e?.message || e).slice(0, 80)] }
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return { ok: false, problems: ['顶层不是对象'] }
  let dropped = 0
  const out = { links: [], gaps: [], carry: [] }
  for (const l of Array.isArray(obj.links) ? obj.links : []) {
    const a = clean(l?.a)
    const b = clean(l?.b)
    const why = cut(l?.why, WHY_MAX)
    if (!a || !b || a === b || !why) { dropped += 1; continue }
    if (out.links.length >= NIGHTLY_ITEMS_MAX) { dropped += 1; continue }
    out.links.push({ a: cut(a, 80), b: cut(b, 80), why })
  }
  for (const g of Array.isArray(obj.gaps) ? obj.gaps : []) {
    const about = clean(g?.about)
    const missing = cut(g?.missing, MISSING_MAX)
    if (!about || !missing) { dropped += 1; continue }
    if (out.gaps.length >= NIGHTLY_ITEMS_MAX) { dropped += 1; continue }
    out.gaps.push({ about: cut(about, 80), missing })
  }
  for (const k of Array.isArray(obj.carry) ? obj.carry : []) {
    const t = cut(k?.text, CARRY_MAX)
    const ev = (Array.isArray(k?.evidence) ? k.evidence : []).filter((x) => clean(x))
    if (!t || !ev.length) { dropped += 1; continue }
    if (out.carry.length >= NIGHTLY_ITEMS_MAX) { dropped += 1; continue }
    out.carry.push({ text: t, evidence: ev.slice(0, 5) })
  }
  if (!out.links.length && !out.gaps.length && !out.carry.length) {
    return { ok: false, problems: ['一条合格的都没有（丢弃 ' + dropped + ' 条）'] }
  }
  return { ok: true, value: out, dropped }
}

/**
 * 证据绑定：照 predict.js 的口径，代码说了算。
 * - links/gaps 的对象 id 必须在 inputs.objects 里，不在就丢。
 * - carry 的 evidence 必须是存在的账本行号（1 起），或者一句能在账本里对上的原话。
 * @returns {{links:object[], gaps:object[], carry:object[], dropped:number}}
 */
export function resolveNightly(parsed, { objects, entries } = {}) {
  const known = new Map()
  for (const o of objects || []) known.set(String(o.id), o)
  const rows = Array.isArray(entries) ? entries : []
  const lineOf = (raw) => {
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0 && n <= rows.length) return Math.round(n)
    const want = clean(raw)
    if (want.length < 4) return 0
    for (let i = rows.length - 1; i >= 0; i -= 1) {
      const said = clean(rows[i]?.text ?? rows[i]?.answer ?? rows[i]?.summary)
      if (said && said.includes(want)) return i + 1
    }
    return 0
  }
  const out = { links: [], gaps: [], carry: [], dropped: 0 }
  const label = (id) => known.get(String(id))?.label || ''
  for (const l of parsed?.links || []) {
    const a = known.get(String(l.a))
    const b = known.get(String(l.b))
    if (!a || !b) { out.dropped += 1; continue }
    out.links.push({ a: a.id, b: b.id, aLabel: a.label, bLabel: b.label, why: l.why })
  }
  for (const g of parsed?.gaps || []) {
    const about = known.get(String(g.about))
    if (!about) { out.dropped += 1; continue }
    out.gaps.push({ about: about.id, aboutLabel: label(g.about), missing: g.missing })
  }
  for (const k of parsed?.carry || []) {
    const lines = []
    for (const raw of k.evidence || []) {
      const n = lineOf(raw)
      if (n > 0) lines.push(n)
    }
    const uniq = [...new Set(lines)]
    if (!uniq.length) { out.dropped += 1; continue }
    out.carry.push({ text: k.text, evidence: uniq.slice(0, 5) })
  }
  return out
}

// ---------------------------------------------------------------- 存放与衰减

export function emptyNightly() {
  return { v: 1, items: [] }
}

export function cleanNightly(raw) {
  const out = emptyNightly()
  if (!raw || typeof raw !== 'object') return out
  out.items = (Array.isArray(raw.items) ? raw.items : [])
    .filter((i) => i && typeof i === 'object' && KINDS.includes(String(i.kind)) && Number(i.until) > 0)
  return out
}

function keyOf(item) {
  if (item.kind === 'links') return 'links|' + item.a + '|' + item.b
  if (item.kind === 'gaps') return 'gaps|' + item.about + '|' + item.missing
  return 'carry|' + item.text
}

/**
 * 并进存放。同内容的刷新而不是再存一条；每类最多留 NIGHTLY_ITEMS_MAX 条活着的（新的优先）。
 * @returns {{store:object, added:number}}
 */
export function mergeNightly(store, resolved, now, { ttl = NIGHTLY_TTL_MS, day = '' } = {}) {
  const at = Number(now) || 0
  const out = cleanNightly(store)
  const live = out.items.filter((i) => Number(i.until) > at)
  const byKey = new Map(live.map((i) => [keyOf(i), i]))
  const fresh = []
  for (const kind of KINDS) {
    for (const src of resolved?.[kind] || []) {
      const item = { kind, ...src, madeAt: at, until: at + ttl, usedAt: 0, used: 0, day: String(day || '') }
      const k = keyOf(item)
      const old = byKey.get(k)
      if (old) {
        old.until = at + ttl
        continue
      }
      byKey.set(k, item)
      fresh.push(item)
    }
  }
  // 每类封顶：活着的按 madeAt 留着最新的 NIGHTLY_ITEMS_MAX 条。
  for (const kind of KINDS) {
    const ofKind = [...byKey.values()].filter((i) => i.kind === kind).sort((a, b) => Number(b.madeAt) - Number(a.madeAt))
    for (const extra of ofKind.slice(NIGHTLY_ITEMS_MAX)) byKey.delete(keyOf(extra))
  }
  out.items = [...byKey.values()].sort((a, b) => Number(a.madeAt) - Number(b.madeAt))
  return { store: out, added: fresh.length }
}

/** 被用过一次续期。 */
export function touchNightly(store, ids, now, { ttl = NIGHTLY_TTL_MS } = {}) {
  const at = Number(now) || 0
  const want = new Set((Array.isArray(ids) ? ids : []).map((x) => String(x)))
  if (!want.size) return cleanNightly(store)
  const out = cleanNightly(store)
  out.items = out.items.map((i) => {
    if (!want.has(keyOf(i)) && !want.has(String(i.a || '')) && !want.has(String(i.about || ''))) return i
    return { ...i, usedAt: at, used: Number(i.used || 0) + 1, until: at + ttl }
  })
  return out
}

/**
 * 调一次模型做整理。走 `llm.stream`（判球同一条通道），**不占会话、不进聊天记录**。
 * 一次调用、一次超时，不重试：整理不是急事，这一轮不成明天还有一轮。
 * @returns {Promise<{ok:true, value:object, dropped:number} | {ok:false, problems:string[]}>}
 */
export async function tidyNightly({ stream, provider, model, inputs, timeoutMs = NIGHTLY_TIMEOUT_MS }) {
  if (typeof stream !== 'function' || !provider || !model) {
    return { ok: false, problems: ['没有模型路由（provider/model 或 llm 服务缺失）'] }
  }
  const messages = [{ role: 'user', content: [{ type: 'text', text: nightlyUser(inputs) }] }]
  let timer
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => resolve(null), Math.max(1, Number(timeoutMs) || NIGHTLY_TIMEOUT_MS))
  })
  const run = (async () => {
    let out = ''
    for await (const chunk of stream({
      provider,
      model,
      system: nightlySystem(),
      messages,
      // 整理要出结构化 JSON，思考型模型的思考也吃额度，给宽一点。
      maxTokens: 1_600,
      temperature: 0,
    })) {
      if (!chunk) continue
      if (chunk.type === 'text-delta') out += chunk.text
      if (chunk.type === 'finish' && chunk.reason && chunk.reason.kind === 'error') return null
    }
    return out
  })().catch(() => null)
  let raw = null
  try {
    raw = await Promise.race([run, timeout])
  } finally {
    clearTimeout(timer)
  }
  if (raw === null) return { ok: false, problems: ['超时或模型报错'] }
  return parseNightly(raw)
}

/** 还活着的产物，按类分开。 */
export function nightlyLive(store, now) {
  const at = Number(now) || 0
  const out = { links: [], gaps: [], carry: [] }
  for (const i of cleanNightly(store).items) {
    if (Number(i.until) <= at) continue
    if (out[i.kind]) out[i.kind].push(i)
  }
  return out
}

// ---------------------------------------------------------------- 三个去向

/** → B3 的 keywordCard：关联两头的东西，就是这轮能拎出来提的。 */
export function linkWords(store, now, max = 3) {
  const words = []
  for (const l of nightlyLive(store, now).links) {
    for (const w of [l.aLabel, l.bLabel]) {
      const s = clean(w)
      if (s && !words.includes(s)) words.push(s)
    }
  }
  return words.slice(0, Math.max(0, max))
}

/** → C 组出题：这些地方他没交代清楚，题往这儿出。 */
export function gapTexts(store, now, max = 3) {
  return nightlyLive(store, now).gaps
    .map((g) => (g.aboutLabel ? g.aboutLabel + '：' : '') + g.missing)
    .filter(Boolean)
    .slice(0, Math.max(0, max))
}

/** → B3 的 innerOS arm：她下一轮自己心里有数的事。 */
export function carryTexts(store, now, max = 3) {
  return nightlyLive(store, now).carry.map((k) => k.text).filter(Boolean).slice(0, Math.max(0, max))
}

/**
 * 进注入的一行。**是给她自己看的背景，不是要说出去的话**，所以写成"心里有数"的口径。
 * 一行封顶，别把预算吃掉。空就返回空字符串（那一行不占）。
 */
export function nightlyLines(store, now, { cap = 240 } = {}) {
  const live = nightlyLive(store, now)
  const parts = []
  const links = live.links.map((l) => (l.aLabel || l.a) + ' ↔ ' + (l.bLabel || l.b))
  if (links.length) parts.push('关联：' + links.join('；'))
  const gaps = live.gaps.map((g) => (g.aboutLabel ? g.aboutLabel + '：' : '') + g.missing)
  if (gaps.length) parts.push('没交代清楚的：' + gaps.join('；'))
  const carry = live.carry.map((k) => k.text)
  if (carry.length) parts.push('你自己记着：' + carry.join('；'))
  if (!parts.length) return ''
  const line = '（这是你自己整理的底稿，不是要你现在说出去的话，合适的时候用）' + parts.join('。')
  return line.length > cap ? line.slice(0, cap) : line
}

/** 面板和验收用。 */
export function nightlyStatus(store, entries, now, tz) {
  const at = Number(now) || 0
  const dayStart = startOfDay(at, tz)
  let today = 0
  let last = 0
  for (const e of entries || []) {
    if (e?.state !== 'nightly') continue
    if (Number(e.at) < dayStart) continue
    today += 1
    if (Number(e.at) >= last) last = Number(e.at)
  }
  const live = nightlyLive(store, at)
  return {
    today,
    max: NIGHTLY_MAX_PER_DAY,
    slots: NIGHTLY_SLOTS,
    lastAt: last,
    live: { links: live.links.length, gaps: live.gaps.length, carry: live.carry.length },
  }
}
