// dsh-go-companion · 模式卡（行为大脑 K3）
//
// 一个模式一张卡，是大脑的长期记忆单位。卡上的东西分三种来源：
//   代码写的：标题、统计、证据（来自 patterns.js）、状态
//   鲸澜写的：解释——「我认为是因为……」，必须引用这张卡证据里的编号（日期或片段号）
//   他写的  ：他的说法——对这张卡的反驳或补充
//
// 状态：观察中 → 已确认 → 有对策 → 已消退 → 复发
//   观察中：出现了但没过门槛
//   已确认：过了门槛
//   有对策：有一条生效中的预案以这张卡为依据（由调用方告诉我们）
//   已消退：已确认或有对策之后，连续 7 天没再出现
//   复发  ：已消退之后又过了门槛
//
// 鲸澜的解释改了，旧的不删，记进历史（被取代），能回看她是怎么想明白的。

export const CARDS_VERSION = 1
export const STATUS = Object.freeze(['观察中', '已确认', '有对策', '已消退', '复发'])
/** 已确认的卡连续这么多天没出现，算消退。 */
export const FADE_DAYS = 7
/** 解释的字数上限。 */
export const EXPLAIN_MAX = 200
export const NOTE_MAX = 300
/** 注入给鲸澜的索引总长上限。 */
export const INDEX_MAX = 300

const isPlain = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

export function emptyCards() {
  return { v: CARDS_VERSION, cards: {}, seq: 0 }
}

export function normalizeCards(raw) {
  const c = emptyCards()
  if (!isPlain(raw)) return c
  if (isPlain(raw.cards)) c.cards = { ...raw.cards }
  c.seq = num(raw.seq)
  return c
}

/** 两个日期字符串之间差几天。 */
function daysBetween(a, b) {
  return Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86_400_000)
}

const isLive = (status) => status === '已确认' || status === '有对策' || status === '复发'

/**
 * 用今晚的候选更新卡片。
 * @param {object} raw
 * @param {object[]} candidates  patterns.detect() 的输出
 * @param {{day:string, now:number, backed?:string[]}} ctx  backed = 有生效预案作依据的卡号
 * @returns {{cards:object, created:string[], changed:{id:string, from:string, to:string}[]}}
 */
export function update(raw, candidates, ctx) {
  const c = normalizeCards(raw)
  const created = []
  const changed = []
  const backed = new Set(ctx.backed || [])
  const seen = new Set()
  const byKey = new Map(Object.values(c.cards).map((x) => [x.key, x]))
  for (const cand of candidates || []) {
    seen.add(cand.key)
    let card = byKey.get(cand.key)
    if (!card) {
      c.seq += 1
      card = {
        id: 'M-' + String(c.seq).padStart(2, '0'), key: cand.key, type: cand.type, status: '观察中',
        createdDay: ctx.day, firstSeen: cand.evidence?.days?.[0] || ctx.day, explain: null, history: [], notes: [],
      }
      c.cards[card.id] = card
      byKey.set(card.key, card)
      created.push(card.id)
    } else {
      card = { ...card }
      c.cards[card.id] = card
    }
    card.title = cand.title
    card.stats = cand.stats
    card.evidence = cand.evidence
    card.lastSeen = ctx.day
    const before = card.status
    if (cand.level === 'confirmed') {
      if (before === '观察中') card.status = '已确认'
      else if (before === '已消退') card.status = '复发'
    }
    if (isLive(card.status) && backed.has(card.id)) card.status = '有对策'
    if (card.status === '有对策' && !backed.has(card.id)) card.status = '已确认'
    if (card.status !== before) changed.push({ id: card.id, from: before, to: card.status })
  }
  // 今晚没出现的卡：活着的超过 7 天没出现就算消退。
  for (const card of Object.values(c.cards)) {
    if (seen.has(card.key)) continue
    if (!isLive(card.status) || !card.lastSeen) continue
    if (daysBetween(card.lastSeen, ctx.day) >= FADE_DAYS) {
      const before = card.status
      c.cards[card.id] = { ...card, status: '已消退', fadedDay: ctx.day }
      changed.push({ id: card.id, from: before, to: '已消退' })
    }
  }
  return { cards: c, created, changed }
}

/** 一张卡能被引用的证据编号：日期和片段号。 */
export function refsOf(card) {
  return new Set([...(card?.evidence?.days || []), ...(card?.evidence?.segs || [])])
}

/**
 * 鲸澜写解释。引用了卡上没有的编号：那些编号剥掉并报回去；一个有效引用都没有就标「猜测」。
 * @returns {{ok:boolean, cards:object, dropped:string[], guess:boolean, text:string}}
 */
export function explain(raw, id, input, now) {
  const c = normalizeCards(raw)
  const card = c.cards[id]
  if (!card) return { ok: false, cards: c, dropped: [], guess: false, text: '没有这张卡：' + id }
  const text = String(input?.text || '').trim().slice(0, EXPLAIN_MAX)
  if (!text) return { ok: false, cards: c, dropped: [], guess: false, text: '解释是空的' }
  const valid = refsOf(card)
  const refs = (Array.isArray(input?.refs) ? input.refs : []).map(String)
  const kept = refs.filter((r) => valid.has(r))
  const dropped = refs.filter((r) => !valid.has(r))
  const guess = kept.length === 0
  const next = { ...card, history: [...(card.history || [])] }
  if (card.explain) next.history.push({ ...card.explain, supersededAt: num(now) })
  next.history = next.history.slice(-10)
  next.explain = { text, refs: kept, guess, at: num(now) }
  c.cards[id] = next
  return { ok: true, cards: c, dropped, guess, text: guess ? '记下了，但没有引用到证据，标成「猜测」。' : '记下了。' }
}

/** 他写的说法。最多留最近 10 条。 */
export function note(raw, id, text, now) {
  const c = normalizeCards(raw)
  const card = c.cards[id]
  const t = String(text || '').trim().slice(0, NOTE_MAX)
  if (!card || !t) return { ok: false, cards: c }
  c.cards[id] = { ...card, notes: [...(card.notes || []), { text: t, at: num(now) }].slice(-10), answered: false }
  return { ok: true, cards: c }
}

/** 给鲸澜看的一行行索引：只放活着的卡，超长截断。 */
export function index(raw) {
  const c = normalizeCards(raw)
  const live = Object.values(c.cards).filter((x) => x.status !== '已消退' && x.status !== '观察中')
  const lines = []
  let total = 0
  for (const card of live.sort((a, b) => String(b.lastSeen).localeCompare(String(a.lastSeen)))) {
    const line = card.id + '［' + card.status + '］' + card.title + (card.notes?.some((n) => n) && card.answered === false ? '（他有话说）' : '')
    if (total + line.length > INDEX_MAX) break
    lines.push(line)
    total += line.length
  }
  return lines
}

export function list(raw) {
  const c = normalizeCards(raw)
  return Object.values(c.cards).sort((a, b) => String(a.id).localeCompare(String(b.id)))
}
