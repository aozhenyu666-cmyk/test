// 2026-11-21 复习卡池（纯函数）：他学过、聊透过的东西沉成卡，按 FSRS 到期再问。
//
// 四个来源：BrainOS 命题卡（brainos）、Gemini 推卡（gemini_mcp）、Gemini 回执里判过「过」的问答（gemini_receipt）、
// 跟两条鱼聊天时说「记进复习卡」（dsh_chat）。最多 500 张活跃卡，按 externalId 或归一化题干去重。
//
// 防灌水的两条线（施工指令的补丁，他点过头）：
//   · counts：只有回执、BrainOS 命题、带选项的卡算「学习制卡」验收点的张数；聊天随手记的进池但不算数。
//   · 复习有没有「被验过」：锁屏 4 选 1（代码判）、Gemini / BrainOS 报的评分算验过；自己翻答案说「记得」、
//     两条鱼抽问只改排期，不算验过——学习制卡的第二半奖励只认验过的那一次。
//   · 个人卡不算行测题：锁屏刷个人卡算解锁进度，但不进题库答对数（题库类验收点只认行测题）。

import { createFsrsCard, cleanFsrs, reviewFsrsCard, ratingOf, sortDueCards } from './fsrs.js'

export const MAX_CARDS = 500
export const FIRST_DUE_MS = 12 * 3_600_000
export const DEFER_MIN_MS = 12 * 3_600_000
export const MAX_RECEIPT_CARDS = 4
export const CARD_SOURCES = Object.freeze(['brainos', 'gemini_mcp', 'gemini_receipt', 'dsh_chat', 'wrong_bank', 'app'])
/** 算「学习制卡」张数的来源（另外：任何来源带了选项的卡也算）。 */
export const COUNTING_SOURCES = Object.freeze(['brainos', 'gemini_receipt'])
/** 验过的复习渠道：代码判的 4 选 1、Gemini、BrainOS。 */
export const VERIFIED_CHANNELS = Object.freeze(['lockscreen', 'gemini', 'brainos', 'app_choice'])
const DAY = 86_400_000

const str = (v, max) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max)
const int = (v, d = 0) => (Number.isFinite(Number(v)) ? Math.round(Number(v)) : d)
/** 去重用：去掉标点空白、全角转半角、小写。 */
export function normQ(q) {
  return String(q || '')
    .replace(/[！-～]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))
    .replace(/[\s\p{P}\p{S}]/gu, '')
    .toLowerCase()
    .slice(0, 160)
}

function answerIndexOf(input, options) {
  if (!options.length) return -1
  const ai = Number(input?.answerIndex)
  if (Number.isInteger(ai) && ai >= 0 && ai < options.length) return ai
  const a = String(input?.answer || input?.a || '').trim()
  const m = /^[(（]?([A-Fa-f])[)）.、．]?$/.exec(a)
  if (m) {
    const i = m[1].toUpperCase().charCodeAt(0) - 65
    return i < options.length ? i : -1
  }
  const hit = options.findIndex((o) => normQ(o) && normQ(o) === normQ(a))
  return hit
}

export function cleanCard(raw, now = Date.now()) {
  if (!raw || typeof raw !== 'object') return null
  const id = str(raw.id, 40)
  const q = str(raw.q, 400)
  if (!id || q.length < 2) return null
  const options = (Array.isArray(raw.options) ? raw.options : []).map((o) => str(o, 200)).filter(Boolean).slice(0, 6)
  const opts = options.length >= 2 ? options : []
  const answerIndex = opts.length ? int(raw.answerIndex, -1) : -1
  const a = str(raw.a, 600)
  if (!a && !(answerIndex >= 0 && answerIndex < opts.length)) return null
  return {
    id,
    externalId: str(raw.externalId, 80),
    source: CARD_SOURCES.includes(raw.source) ? raw.source : 'app',
    topic: str(raw.topic, 40),
    q,
    a: a || opts[answerIndex],
    hint: str(raw.hint, 200),
    options: answerIndex >= 0 && answerIndex < opts.length ? opts : [],
    answerIndex: answerIndex >= 0 && answerIndex < opts.length ? answerIndex : -1,
    counts: raw.counts === true,
    sealId: str(raw.sealId, 40),
    day: /^\d{4}-\d{2}-\d{2}$/.test(String(raw.day || '')) ? String(raw.day) : '',
    createdAt: int(raw.createdAt, now),
    fsrs: cleanFsrs(raw.fsrs, now),
    history: (Array.isArray(raw.history) ? raw.history : []).slice(-30).map((h) => ({
      at: int(h?.at, 0), rating: Math.max(1, Math.min(4, int(h?.rating, 3))), channel: str(h?.channel, 20), verified: h?.verified === true,
    })),
  }
}

export function cleanPool(raw, now = Date.now()) {
  const items = (Array.isArray(raw?.items) ? raw.items : []).map((x) => cleanCard(x, now)).filter(Boolean)
  return { v: 1, items: items.slice(-MAX_CARDS) }
}

/** 满了先淘汰：已经很稳（稳定度 ≥ 90 天）的里离到期最远的，其次最老的。 */
function evict(items, keep) {
  if (items.length <= keep) return { items, evicted: [] }
  const n = items.length - keep
  const ranked = [...items].sort((a, b) => {
    const ma = a.fsrs.stability >= 90 ? 1 : 0
    const mb = b.fsrs.stability >= 90 ? 1 : 0
    return mb - ma || (ma ? b.fsrs.due - a.fsrs.due : a.createdAt - b.createdAt)
  })
  const drop = new Set(ranked.slice(0, n).map((x) => x.id))
  return { items: items.filter((x) => !drop.has(x.id)), evicted: [...drop] }
}

/**
 * 进池：逐张校验、去重（externalId 或题干），超过 500 张按上面的规则淘汰。
 * opts: { now, day, source, sealId, counts（不给就按来源 / 有无选项判）, max（这次最多收几张） }
 */
export function addCards(poolRaw, inputs = [], opts = {}) {
  const now = opts.now ?? Date.now()
  const pool = cleanPool(poolRaw, now)
  const byExt = new Map(pool.items.filter((x) => x.externalId).map((x) => [x.externalId, x]))
  const byQ = new Map(pool.items.map((x) => [normQ(x.q), x]))
  const added = []
  const duplicate = []
  const skipped = []
  const list = (Array.isArray(inputs) ? inputs : []).slice(0, Math.max(0, opts.max ?? 30))
  list.forEach((input, i) => {
    const q = str(input?.q ?? input?.stem ?? input?.question, 400)
    const options = (Array.isArray(input?.options) ? input.options : []).map((o) => str(o, 200)).filter(Boolean).slice(0, 6)
    const opts2 = options.length >= 2 ? options : []
    const answerIndex = answerIndexOf(input, opts2)
    let a = str(input?.a ?? input?.answer, 600)
    if (opts2.length && answerIndex < 0) {
      skipped.push({ q: q.slice(0, 40), why: '有选项但答案对不上任何一项' })
      return
    }
    if (opts2.length && (!a || /^[(（]?[A-Fa-f][)）.、．]?$/.test(a))) a = opts2[answerIndex]
    if (q.length < 4) return void skipped.push({ q, why: '题干太短' })
    if (!a) return void skipped.push({ q: q.slice(0, 40), why: '没有答案' })
    const externalId = str(input?.externalId ?? input?.external_id, 80)
    const hit = (externalId && byExt.get(externalId)) || byQ.get(normQ(q))
    if (hit) return void duplicate.push(hit.id)
    const source = CARD_SOURCES.includes(input?.source) ? input.source : CARD_SOURCES.includes(opts.source) ? opts.source : 'app'
    const counts = typeof opts.counts === 'boolean' ? opts.counts : COUNTING_SOURCES.includes(source) || opts2.length > 0
    const card = cleanCard({
      id: 'rc-' + Number(now).toString(36).slice(-6) + (pool.items.length + added.length + i).toString(36),
      externalId, source, q, a, options: opts2, answerIndex,
      topic: input?.topic, hint: input?.hint ?? input?.premise,
      counts, sealId: str(input?.sealId ?? input?.checkpoint_id ?? input?.checkpointId ?? opts.sealId, 40), day: opts.day || '',
      createdAt: now, fsrs: createFsrsCard(now, { firstDueMs: FIRST_DUE_MS }), history: [],
    }, now)
    if (!card) return void skipped.push({ q: q.slice(0, 40), why: '格式不对' })
    added.push(card)
    if (externalId) byExt.set(externalId, card)
    byQ.set(normQ(q), card)
  })
  const ev = evict([...pool.items, ...added], MAX_CARDS)
  const kept = new Set(ev.items.map((x) => x.id))
  return { state: { v: 1, items: ev.items }, added: added.filter((x) => kept.has(x.id)), duplicate, skipped, evicted: ev.evicted }
}

/** Gemini 回执里的问答 → 卡：只有判「过」的回执，题干 ≥6 字、答 ≥4 字，一次最多 4 张。 */
export function receiptToCards(receipt) {
  if (!receipt || receipt.verdict !== 'pass') return []
  return (Array.isArray(receipt.qa) ? receipt.qa : [])
    .map((x) => ({ q: str(x?.q, 400), a: str(x?.a, 600) }))
    .filter((x) => x.q.length >= 6 && x.a.length >= 4)
    .slice(0, MAX_RECEIPT_CARDS)
    .map((x) => ({ ...x, topic: str(receipt.topic, 40), source: 'gemini_receipt', sealId: '' }))
}

/** 到期卡（逾期最久、错得多的先）。filter 可以再筛（比如锁屏只要带选项的）。 */
export function dueCards(poolRaw, now = Date.now(), { limit = 10, filter } = {}) {
  const pool = cleanPool(poolRaw, now)
  return sortDueCards(pool.items, now).filter((x) => (filter ? filter(x) : true)).slice(0, limit)
}

/**
 * 复习一次。channel：lockscreen（锁屏 4 选 1，代码判）/ gemini / brainos / app_choice（复习页 4 选 1）算验过；
 * self（自己翻答案）/ sister（两条鱼抽问）只改排期。verified 由这里按渠道和有无选项定，调用方说了不算。
 */
export function rateCard(poolRaw, id, rating, { now = Date.now(), channel = 'self' } = {}) {
  const pool = cleanPool(poolRaw, now)
  const g = ratingOf(rating)
  const cur = pool.items.find((x) => x.id === id)
  if (!cur || !g) return { ok: false, error: cur ? 'bad-rating' : 'no-card', state: pool }
  const ch = str(channel, 20) || 'self'
  const codeJudged = (ch === 'lockscreen' || ch === 'app_choice') && cur.options.length > 0
  const verified = codeJudged || ch === 'gemini' || ch === 'brainos'
  const next = { ...cur, fsrs: reviewFsrsCard(cur.fsrs, g, now), history: [...cur.history, { at: now, rating: g, channel: ch, verified }].slice(-30) }
  return { ok: true, card: next, verified, state: { ...pool, items: pool.items.map((x) => (x.id === id ? next : x)) } }
}

export function deleteCard(poolRaw, id, now = Date.now()) {
  const pool = cleanPool(poolRaw, now)
  if (!pool.items.some((x) => x.id === id)) return { ok: false, state: pool }
  return { ok: true, state: { ...pool, items: pool.items.filter((x) => x.id !== id) } }
}

/** 这张卡的「延迟回忆」结论：创建 12 小时以后的第一次复习——验过且记得 → 'pass'；验过但忘了 / 没验过 → 'fail'；还没到 → ''. */
export function delayedRecall(card) {
  const first = (card?.history || []).find((h) => h.at >= card.createdAt + DEFER_MIN_MS)
  if (!first) return ''
  return first.verified && first.rating >= 3 ? 'pass' : 'fail'
}

/** 学习制卡验收点的第二半：绑在这一点上的卡，延迟回忆通过了几张、还在等几张。 */
export function deferredProgress(poolRaw, sealId, now = Date.now()) {
  const cards = cleanPool(poolRaw, now).items.filter((x) => x.sealId && x.sealId === sealId)
  let passed = 0
  let waiting = 0
  for (const c of cards) {
    const r = delayedRecall(c)
    if (r === 'pass') passed += 1
    else if (!r) waiting += 1
  }
  return { cards: cards.length, passed, waiting }
}

/**
 * 给快照 / 体检 / Gemini 的摘要：今天到期几张、总共几张、近 7 天首次（延迟）回忆通过率、前 5 张到期、错得最多的 3 个主题。
 */
export function reviewSummary(poolRaw, now = Date.now()) {
  const pool = cleanPool(poolRaw, now)
  const due = sortDueCards(pool.items, now)
  const since = now - 7 * DAY
  let tried = 0
  let ok = 0
  for (const c of pool.items) {
    const first = c.history.find((h) => h.at >= c.createdAt + DEFER_MIN_MS)
    if (!first || first.at < since) continue
    tried += 1
    if (first.rating >= 3) ok += 1
  }
  const lapsesByTopic = {}
  for (const c of pool.items) {
    if (!c.fsrs.lapses) continue
    const t = c.topic || '未分类'
    lapsesByTopic[t] = (lapsesByTopic[t] || 0) + c.fsrs.lapses
  }
  return {
    dueCount: due.length,
    totalCards: pool.items.length,
    retention7d: tried ? Math.round((ok / tried) * 100) : null,
    reviewed7d: tried,
    duePreview: due.slice(0, 5).map((c) => ({ id: c.id, topic: c.topic, q: c.q.slice(0, 120), lapses: c.fsrs.lapses, choice: c.options.length > 0 })),
    weakTopics: Object.entries(lapsesByTopic).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([topic, lapses]) => ({ topic, lapses })),
  }
}

/** 周冷数据一行：本周新建 N 张 · 复习 M 次 · 首次回忆通过率 · 逾期没刷 K 张。 */
export function reviewWeekLine(poolRaw, { from, to, now = Date.now() } = {}) {
  const pool = cleanPool(poolRaw, now)
  if (!pool.items.length) return ''
  const made = pool.items.filter((c) => c.createdAt >= from && c.createdAt < to).length
  let reviews = 0
  let tried = 0
  let ok = 0
  for (const c of pool.items) {
    reviews += c.history.filter((h) => h.at >= from && h.at < to).length
    const first = c.history.find((h) => h.at >= c.createdAt + DEFER_MIN_MS)
    if (first && first.at >= from && first.at < to) {
      tried += 1
      if (first.rating >= 3) ok += 1
    }
  }
  const overdue = pool.items.filter((c) => c.fsrs.due < now - DAY).length
  return '复习卡：本周新建 ' + made + ' 张 · 复习 ' + reviews + ' 次 · 首次回忆通过 ' + (tried ? Math.round((ok / tried) * 100) + '%（' + ok + '/' + tried + '）' : '—') + ' · 逾期一天以上没刷 ' + overdue + ' 张'
}

/** Obsidian 镜像的一行：「- [主题] 问 :: 答」。 */
export function mirrorLine(card) {
  const opts = card.options.length ? '（' + card.options.map((o, i) => String.fromCharCode(65 + i) + '. ' + o).join(' ') + '）' : ''
  return '- ' + (card.topic ? '[' + card.topic + '] ' : '') + card.q + opts + ' :: ' + card.a + (card.hint ? '（前提：' + card.hint + '）' : '') + '  `' + card.source + '`'
}
