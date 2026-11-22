// 2026-11-21 【周验收】回收：体系外的模型（粘贴回来的 Claude / GPT，或经桥的 Gemini）给的最多 3 条调整，
// 他逐条点「采纳」才生效。生效 = 写进笔记库 大肥鱼/周验收/、进鲸澜接下来 7 天的上下文（她排验收点时照着办）。
// 不自动改任何数字——改数字的权力在他。纯函数。

const clip = (v, m) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, m)
export const REVIEW_TTL_MS = 7 * 86_400_000
export const MAX_ADJUSTMENTS = 3

/**
 * 解析【周验收】块。容错：全角 / 半角冒号、有没有「【周验收】」头、调整前面带不带编号。
 * @returns {{ok:boolean, verdict?:string, adjustments?:string[], watch?:string, error?:string}}
 */
export function parseWeekReview(text) {
  const raw = String(text || '')
  const at = raw.indexOf('【周验收】')
  const body = at >= 0 ? raw.slice(at + 5) : raw
  let verdict = ''
  let watch = ''
  const adjustments = []
  for (const line0 of body.split(/\r?\n/)) {
    const line = line0.replace(/^[\s>*\-•]+/, '').trim()
    if (!line) continue
    let m = /^判断\s*[:：]\s*(.+)$/.exec(line)
    if (m) {
      verdict = clip(m[1], 200)
      continue
    }
    m = /^调整\s*[1-9一二三]?\s*[:：]\s*(.+)$/.exec(line)
    if (m) {
      const t = clip(m[1], 240)
      if (t && !/^[（(]?可选[)）]?$/.test(t) && adjustments.length < MAX_ADJUSTMENTS) adjustments.push(t)
      continue
    }
    m = /^下周盯\s*[:：]\s*(.+)$/.exec(line)
    if (m) watch = clip(m[1], 120)
  }
  if (!verdict && !adjustments.length) return { ok: false, error: 'no-block', text: '没找到【周验收】块（要有「判断：」或「调整1：」）。' }
  return { ok: true, verdict, adjustments, watch }
}

export function cleanReview(raw) {
  if (!raw || typeof raw !== 'object') return null
  const adjustments = (Array.isArray(raw.adjustments) ? raw.adjustments : []).slice(0, MAX_ADJUSTMENTS)
    .map((a) => ({ text: clip(a?.text ?? a, 240), accepted: a?.accepted === true }))
    .filter((a) => a.text)
  return {
    id: clip(raw.id, 30) || 'wr-' + Number(raw.at || 0).toString(36),
    at: Number(raw.at) || 0,
    by: ['paste', 'gemini'].includes(raw.by) ? raw.by : 'paste',
    verdict: clip(raw.verdict, 200),
    watch: clip(raw.watch, 120),
    adjustments,
    status: ['pending', 'decided'].includes(raw.status) ? raw.status : 'pending',
    decidedAt: Number(raw.decidedAt) || 0,
  }
}

/** 收一份：同时最多一份在等他决定（新的顶掉旧的未决的）。 */
export function addReview(state, parsed, { by = 'paste', now = Date.now() } = {}) {
  const list = (Array.isArray(state?.items) ? state.items : []).map(cleanReview).filter(Boolean).filter((r) => r.status === 'decided')
  const r = cleanReview({ ...parsed, adjustments: parsed.adjustments.map((t) => ({ text: t })), by, at: now, status: 'pending', id: 'wr-' + now.toString(36) })
  return { review: r, state: { v: 1, items: [...list, r].slice(-20) } }
}

/** 他逐条决定：accepted 是采纳的下标。 */
export function decideReview(state, id, accepted = [], { now = Date.now() } = {}) {
  const items = (Array.isArray(state?.items) ? state.items : []).map(cleanReview).filter(Boolean)
  const cur = items.find((r) => r.id === id)
  if (!cur || cur.status !== 'pending') return { ok: false, error: 'no-review', state: { v: 1, items } }
  const set = new Set((Array.isArray(accepted) ? accepted : []).map(Number))
  const next = { ...cur, status: 'decided', decidedAt: now, adjustments: cur.adjustments.map((a, i) => ({ ...a, accepted: set.has(i) })) }
  return { ok: true, review: next, state: { v: 1, items: items.map((r) => (r.id === id ? next : r)) } }
}

/** 进鲸澜上下文的几行：最近 7 天内决定过的那一份里采纳的调整 + 下周盯什么。 */
export function reviewPromptLines(state, now = Date.now()) {
  const items = (Array.isArray(state?.items) ? state.items : []).map(cleanReview).filter(Boolean)
  const r = items.filter((x) => x.status === 'decided' && now - x.decidedAt < REVIEW_TTL_MS).sort((a, b) => b.decidedAt - a.decidedAt)[0]
  if (!r) return []
  const acc = r.adjustments.filter((a) => a.accepted)
  if (!acc.length && !r.watch) return []
  const lines = ['这周他采纳的周验收调整（排验收点时照着办，别再跟他商量）：', ...acc.map((a, i) => (i + 1) + '. ' + a.text)]
  if (r.watch) lines.push('这周盯：' + r.watch)
  return lines
}

export function reviewMarkdown(r, fmt = (t) => new Date(t).toISOString().slice(0, 10)) {
  return [
    '# 周验收 · ' + fmt(r.at) + '（' + (r.by === 'gemini' ? 'Gemini' : '粘贴回来的') + '）',
    '',
    '- 判断：' + (r.verdict || '—'),
    ...r.adjustments.map((a, i) => '- 调整' + (i + 1) + '：' + a.text + (r.status === 'decided' ? (a.accepted ? '（采纳）' : '（没采纳）') : '（待定）')),
    '- 下周盯：' + (r.watch || '—'),
    '',
  ].join('\n')
}
