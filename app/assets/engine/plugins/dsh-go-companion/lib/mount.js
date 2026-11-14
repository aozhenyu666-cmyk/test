// C1 工作记忆挂载卡：解决「推导到一半断掉，回头不知道自己在解决什么」。
//
// 一张卡固定三栏：目标（1 条）、已知条件（≤5）、中间结论（≤5）。有一条铁律贯穿全部代码——
// **卡里只能是他自己的话**。她不替他填：`isUserText()` 要求每一条 text 是某条用户消息的子串，
// 或者他在面板上手写（`by:'user'` 且没有 src）。她自己总结的话进不来，代码校验，不靠她自觉。
//
// 纯函数：不读配置、不写账本、不碰时钟。时间由调用方给，注入行的 hh:mm 也由调用方格式化。
// 这样断链检测、上限、停放/恢复全都能在测试里精确摆布。

/** 已知条件的上限。 */
export const MAX_GIVENS = 5
/** 中间结论的上限。 */
export const MAX_STEPS = 5
/** 单条文字上限：卡是给人一眼看完的，不是记事本。 */
export const TEXT_MAX = 120
/** 注入行的硬上限（规格：≤300 字符进状态层）。 */
export const INJECT_MAX = 300
/** 连续这么多轮「漂了」才亮卡——一轮说明不了什么，可能只是聊别的。 */
export const DRIFT_STREAK = 2
/** 新消息与卡的关键词重合低于这个数，算「漂了」。 */
export const DRIFT_MIN_OVERLAP = 2
/** 三种状态，别的都不认。 */
export const MOUNT_STATES = Object.freeze(['open', 'parked', 'closed'])
/** 提议能挂的三栏。 */
export const MOUNT_SLOTS = Object.freeze(['goal', 'given', 'step'])

const CIRCLED = ['①', '②', '③', '④', '⑤']

const plain = (v) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim()
const cut = (v, max = TEXT_MAX) => {
  const s = plain(v)
  return s.length <= max ? s : s.slice(0, max - 1) + '…'
}

/** 中文没有空格，按二字组算「说的是不是同一件事」；英文按词。 */
export function keywordsOf(text) {
  const t = plain(text).toLowerCase()
  const out = new Set()
  const han = t.replace(/[^\p{Script=Han}a-z0-9]+/gu, '')
  for (let i = 0; i + 1 < han.length; i += 1) out.add(han.slice(i, i + 2))
  for (const w of t.match(/[a-z][a-z0-9]{1,}/g) || []) out.add(w)
  return out
}

/** 两段话的关键词重合数。 */
export function overlapOf(a, b) {
  const A = keywordsOf(a)
  const B = keywordsOf(b)
  let n = 0
  for (const k of A) if (B.has(k)) n += 1
  return n
}

/**
 * 这句话是不是他自己说过的。
 *
 * `sources` 是他能拿到的用户原话（消息原文、面板手写）。给了 sources 就必须命中其中一段：
 * 完全相等，或者是其中一段的子串（他可以把半句挂上去，但不能把两句拼成一句新的）。
 * `sources` 为空时只在「面板手写」这条路上放行——调用方负责只在那条路上传空数组。
 *
 * **这里不判长度**：超长的话照样是他的原话，落卡时由 `cut()` 截到 `TEXT_MAX`。
 * 把长度塞进这里会让「他说了 200 字」被当成「他在编话」，那是两件完全不同的事。
 */
export function isUserText(text, sources) {
  const t = plain(text)
  if (t.length < 2) return false
  if (!Array.isArray(sources) || !sources.length) return true
  for (const s of sources) {
    const src = plain(s)
    if (!src) continue
    if (src === t || src.includes(t)) return true
  }
  return false
}

/** 一张空卡。goal 也必须是他的原话。 */
export function newCard({ topicId, goal, at, sources } = {}) {
  const g = cut(goal)
  if (!g) return { ok: false, code: 'goal-empty', text: '开卡要先有一句目标。' }
  if (!isUserText(g, sources)) return { ok: false, code: 'not-user-text', text: '目标得是他自己说的话，你不能替他写。' }
  const when = Number(at) || 0
  return {
    ok: true,
    card: {
      id: 'M-' + (topicId ? cut(topicId, 32) : 'card') + '-' + when,
      topicId: cut(topicId, 32),
      openedAt: when,
      touchedAt: when,
      goal: { text: g, by: 'user', at: when },
      givens: [],
      steps: [],
      status: 'open',
      parkedWhy: '',
    },
  }
}

/** 现在已经有一张 open 卡就不能再开：先停放或关掉。 */
export function openCard(state, opts = {}) {
  const cur = normalizeCard(state?.card)
  if (cur && cur.status === 'open') {
    return { ok: false, code: 'card-open', card: cur, text: '已经有一张开着的卡，先停放或关掉它。' }
  }
  return newCard(opts)
}

/**
 * 挂一条已知条件。`src` 是他那句话在账本里的行号或原话；面板手写时才允许没有 src。
 */
export function withGiven(card, { text, at, src, sources } = {}) {
  const c = normalizeCard(card)
  if (!c) return { ok: false, code: 'no-card', text: '没有卡。' }
  if (c.status !== 'open') return { ok: false, code: 'card-not-open', card: c, text: '这张卡不是开着的。' }
  if (c.givens.length >= MAX_GIVENS) {
    return { ok: false, code: 'givens-full', card: c, text: '已知条件最多 ' + MAX_GIVENS + ' 条，先删一条。' }
  }
  if (!isUserText(text, sources)) {
    return { ok: false, code: 'not-user-text', card: c, text: '已知条件得是他自己说的原话，你不能替他归纳。' }
  }
  const one = { text: cut(text), by: 'user', at: Number(at) || 0 }
  const s = plain(src)
  if (s) one.src = cut(s, 64)
  const next = { ...c, givens: [...c.givens, one], touchedAt: Number(at) || c.touchedAt }
  return { ok: true, card: next }
}

/**
 * 挂一条中间结论。**必须标出由哪几条已知推出**（`from` 是已知的下标），
 * 引用不存在的下标直接拒——结论不许凭空冒出来。
 */
export function withStep(card, { text, at, from, sources } = {}) {
  const c = normalizeCard(card)
  if (!c) return { ok: false, code: 'no-card', text: '没有卡。' }
  if (c.status !== 'open') return { ok: false, code: 'card-not-open', card: c, text: '这张卡不是开着的。' }
  if (c.steps.length >= MAX_STEPS) {
    return { ok: false, code: 'steps-full', card: c, text: '中间结论最多 ' + MAX_STEPS + ' 条，先删一条。' }
  }
  if (!isUserText(text, sources)) {
    return { ok: false, code: 'not-user-text', card: c, text: '结论得是他自己说的原话。' }
  }
  const refs = (Array.isArray(from) ? from : [from])
    .map((v) => Number(v))
    .filter((v) => Number.isInteger(v) && v >= 0 && v < c.givens.length)
  if (!refs.length) {
    return {
      ok: false, code: 'step-needs-given', card: c,
      text: c.givens.length
        ? '这条结论要标出由哪几条已知推出（0–' + (c.givens.length - 1) + '）。'
        : '卡上还没有已知条件，结论无从推起。',
    }
  }
  const next = {
    ...c,
    steps: [...c.steps, { text: cut(text), by: 'user', at: Number(at) || 0, from: [...new Set(refs)] }],
    touchedAt: Number(at) || c.touchedAt,
  }
  return { ok: true, card: next }
}

/** 开新卡前把他停在哪说一句，不许无声息地扔下。 */
export function parkCard(card, { at, where } = {}) {
  const c = normalizeCard(card)
  if (!c) return { ok: false, code: 'no-card', text: '没有卡。' }
  if (c.status !== 'open') return { ok: false, code: 'card-not-open', card: c, text: '只有开着的卡能停放。' }
  const w = cut(where)
  if (!w) return { ok: false, code: 'park-needs-why', card: c, text: '停放要说一句停在哪。' }
  return { ok: true, card: { ...c, status: 'parked', parkedWhy: w, touchedAt: Number(at) || c.touchedAt } }
}

/** 恢复：停着的卡重新打开（同时只有一张 open，调用方保证）。 */
export function resumeCard(card, { at } = {}) {
  const c = normalizeCard(card)
  if (!c) return { ok: false, code: 'no-card', text: '没有卡。' }
  if (c.status !== 'parked') return { ok: false, code: 'card-not-parked', card: c, text: '只有停着的卡能恢复。' }
  return { ok: true, card: { ...c, status: 'open', touchedAt: Number(at) || c.touchedAt } }
}

/** 关掉：停了或开着都行，关掉之后就只读。 */
export function closeCard(card, { at } = {}) {
  const c = normalizeCard(card)
  if (!c) return { ok: false, code: 'no-card', text: '没有卡。' }
  if (c.status === 'closed') return { ok: false, code: 'card-closed', card: c, text: '这张卡已经关了。' }
  return { ok: true, card: { ...c, status: 'closed', touchedAt: Number(at) || c.touchedAt } }
}

/** 脏数据撑得住：认不出来的直接当没有，不抛。 */
export function normalizeCard(raw) {
  if (!raw || typeof raw !== 'object') return null
  const goal = raw.goal && typeof raw.goal === 'object' ? plain(raw.goal.text) : ''
  if (!goal) return null
  const list = (arr, keep) => (Array.isArray(arr) ? arr.map(keep).filter(Boolean) : [])
  const givens = list(raw.givens, (g) => {
    const text = g && typeof g === 'object' ? plain(g.text) : ''
    if (!text) return null
    const one = { text: cut(text), by: 'user', at: Number(g.at) || 0 }
    if (plain(g.src)) one.src = cut(g.src, 64)
    return one
  }).slice(0, MAX_GIVENS)
  const steps = list(raw.steps, (s) => {
    const text = s && typeof s === 'object' ? plain(s.text) : ''
    if (!text) return null
    const from = (Array.isArray(s.from) ? s.from : []).map(Number).filter((v) => Number.isInteger(v) && v >= 0 && v < givens.length)
    return { text: cut(text), by: 'user', at: Number(s.at) || 0, from: [...new Set(from)] }
  }).slice(0, MAX_STEPS)
  return {
    id: plain(raw.id) || 'M-unknown',
    topicId: cut(raw.topicId, 32),
    openedAt: Number(raw.openedAt) || 0,
    touchedAt: Number(raw.touchedAt) || Number(raw.openedAt) || 0,
    goal: { text: cut(goal), by: 'user', at: Number(raw.goal.at) || 0 },
    givens,
    steps,
    status: MOUNT_STATES.includes(raw.status) ? raw.status : 'closed',
    parkedWhy: cut(raw.parkedWhy),
  }
}

/**
 * 断链检测（纯函数）：他这一句话和卡上的东西重合太少，算「漂了」。
 *
 * 只看关键词重合，不听语义——语义要模型，而这里刻意不调模型：
 * 判「他漂了」必须是他能自己核对的事，不能是模型的感觉。
 */
export function driftOf(card, message) {
  const c = normalizeCard(card)
  if (!c || c.status !== 'open') return { drift: false, hit: 0, need: DRIFT_MIN_OVERLAP }
  const all = [c.goal.text, ...c.givens.map((g) => g.text), ...c.steps.map((s) => s.text)].join(' ')
  const hit = overlapOf(message, all)
  return { drift: hit < DRIFT_MIN_OVERLAP, hit, need: DRIFT_MIN_OVERLAP }
}

/** 连续几轮：漂了就加一，没漂清零。 */
export function nextStreak(prev, drifted) {
  return drifted ? Math.max(0, Number(prev) || 0) + 1 : 0
}

/** 到点了才亮卡；连续 `DRIFT_STREAK` 轮才轮到她说这一句。 */
export function shouldShowCard(prev, drifted) {
  return nextStreak(prev, drifted) >= DRIFT_STREAK
}

/**
 * 注入行。规格格式：`[挂载] 目标：… ｜ 已知：①…②… ｜ 推到：… ｜ 上次动：14:20`
 *
 * 硬上限 `INJECT_MAX`。装不下时先丢**最后几条已知**（目标永远留），再截结论——
 * 宁可少一条已知，也不让这一行被预算整块裁掉，那等于卡不存在。
 * `hm` 是调用方按他所在时区格式化好的 hh:mm；不给就不写「上次动」。
 */
export function mountLine(card, { hm } = {}) {
  const c = normalizeCard(card)
  if (!c || c.status !== 'open') return ''
  const head = '[挂载] 目标：' + c.goal.text
  const tail = plain(hm) ? ' ｜ 上次动：' + plain(hm) : ''
  let givens = c.givens.map((g) => g.text)
  const steps = c.steps.map((s) => s.text)
  for (;;) {
    const parts = [head]
    if (givens.length) parts.push('已知：' + givens.map((g, i) => CIRCLED[i] + g).join(''))
    if (steps.length) parts.push('推到：' + steps.join(' → '))
    const line = parts.join(' ｜ ') + tail
    if (line.length <= INJECT_MAX) return line
    if (givens.length) {
      givens = givens.slice(0, -1)
      continue
    }
    if (steps.length) {
      steps.pop()
      continue
    }
    // 只剩目标和「上次动」还超：砍目标文字。
    const room = INJECT_MAX - ('[挂载] 目标：' + tail).length
    return '[挂载] 目标：' + cut(c.goal.text, Math.max(1, room)) + tail
  }
}

/**
 * 提议挂载：把他说过的原话提议挂上去，**必须带账本行号**。
 * 没有出处的提议不受理——「她自己总结他刚才说了什么」正是要防的那件事。
 */
export function propose({ text, slot, at, src, sources } = {}) {
  const key = String(slot || '')
  if (!MOUNT_SLOTS.includes(key)) {
    return { ok: false, code: 'bad-slot', text: '只能往 goal / given / step 里挂。' }
  }
  if (!plain(src)) return { ok: false, code: 'propose-needs-source', text: '提议要带账本行号，没有出处的不提议。' }
  if (!isUserText(text, sources)) {
    return { ok: false, code: 'not-user-text', text: '只能提议他说过的原话，你的总结进不了卡。' }
  }
  return { ok: true, proposal: { text: cut(text), slot: key, by: 'user', at: Number(at) || 0, src: cut(src, 64) } }
}

/**
 * 他确认了提议 → 真正落卡。目标只在卡还没有目标时才能落（卡一开就有目标，
 * 所以这里实际只接 given / step）；step 还要他指明由哪几条已知推出。
 */
export function acceptProposal(card, proposal, { at, from } = {}) {
  const p = proposal && typeof proposal === 'object' ? proposal : null
  if (!p || !plain(p.text)) return { ok: false, code: 'bad-proposal', text: '提议是空的。' }
  if (p.slot === 'given') return withGiven(card, { text: p.text, at, src: p.src, sources: [] })
  if (p.slot === 'step') return withStep(card, { text: p.text, at, from, sources: [] })
  return { ok: false, code: 'bad-slot', text: '卡一开就有目标了，目标不再追加。' }
}

/**
 * 脚手架撤除：数「他自己主动挂的」和「她提议才挂的」，比值是 C 组的核心进步指标。
 *
 * `state:'mount'` 的账本条目里，`by === 'user'` 是他在面板上自己挂的，
 * `by === 'her'` 是她提议之后挂上的。
 */
export function scaffold(entries) {
  const rows = (Array.isArray(entries) ? entries : []).filter((e) => e && e.state === 'mount' && e.action === 'add')
  const user = rows.filter((e) => e.by === 'user').length
  const her = rows.filter((e) => e.by === 'her').length
  const ratio = her ? user / her : user > 0 ? Infinity : 0
  return { user, her, ratio, cap: proposeCap(ratio), hit: user + her }
}

/**
 * 她一天最多提议几次。比值越好，提议越少——目标是他自己学会挂，不是她替他挂。
 * 阈值写死在表里，不调：调参会让「进步」变成「她心情好」。
 */
export function proposeCap(ratio) {
  const r = Number(ratio)
  if (!Number.isFinite(r)) return 0
  if (r >= 2) return 0
  if (r >= 1) return 1
  if (r >= 0.5) return 2
  return 3
}

/**
 * /status 和面板用的一眼视图。
 *
 * `givens`/`steps` 给的是**条目本身**，不是条数：面板要把每一句原话和它的出处摆出来，
 * 只给个数等于让界面自己再去猜内容。条数由界面取 `.length`。
 */
export function mountStatus(card, entries) {
  const c = normalizeCard(card)
  return {
    status: c ? c.status : 'none',
    // 停着 / 关掉的卡也带着目标和原来那几条：恢复之后不用重新问一遍。
    goal: c ? c.goal.text : '',
    givens: c ? c.givens.map((g) => ({ text: g.text, at: g.at, src: g.src || '' })) : [],
    steps: c ? c.steps.map((s) => ({ text: s.text, at: s.at, from: s.from })) : [],
    maxGivens: MAX_GIVENS,
    maxSteps: MAX_STEPS,
    parkedWhy: c ? c.parkedWhy : '',
    scaffold: scaffold(entries),
  }
}

/**
 * 「内存漏水」这条支线的四个槽位。它是 C 组第一条完整支线，所以内容写在这里、
 * 由配置加载（`config.tracks` 为空时用它兜底），`tracks.js` 里一个字都不写。
 */
export const MEMORY_LEAK_TRACK = Object.freeze({
  id: 'memory-leak',
  name: '工作记忆漏水',
  scene: '他推导到一半突然岔开；或者隔一轮回来问「我刚才说到哪了」',
  first: '他以为自己是「忘了细节」，其实是从头就没记下目标、已知条件和推到哪一步',
  missed: '他没意识到：目标 / 已知 / 推到哪，三样缺一样就等于从头开始，重来一遍的代价比挂一张卡大得多',
  blind: '工作记忆漏水',
})
