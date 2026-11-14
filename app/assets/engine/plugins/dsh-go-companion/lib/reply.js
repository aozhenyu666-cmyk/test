// dsh-go-companion · P3 两种「没开口」的区分（第八轮）
//
// 施工书里最容易被糊过去的一条：「没答对」和「没认真答」是两件事，
// 惩罚也必须是两件事——答错只重打，敷衍才走升级。
//
// 度量复用 capabilities.js（那是 J3 就在用的同一套），这里只做分类与重合率。

import { countChars, countConcreteNouns, countTopics, fillerRatio, hasConclusion } from './capabilities.js'

/** 口水话的四个代码判据。 */
export const SHORT_CHARS = 8
export const FILLER_MAX = 0.5
export const OVERLAP_MAX = 0.7
/** 与最近几条比重合。 */
export const RECENT = 5
/** 超过这么多字还没结论句就算「长而无结论」。 */
export const LONG_CHARS = 80

/** 字二元组的 Jaccard 重合率：0 到 1。 */
export function overlap(a, b) {
  const grams = (s) => {
    const t = String(s || '').replace(/\s+/g, '')
    const out = new Set()
    for (let i = 0; i < t.length - 1; i += 1) out.add(t.slice(i, i + 2))
    return out
  }
  const A = grams(a)
  const B = grams(b)
  if (!A.size || !B.size) return 0
  let hit = 0
  for (const g of A) if (B.has(g)) hit += 1
  return hit / (A.size + B.size - hit)
}

/** 和最近几条里最像的那一条的重合率。 */
export function maxOverlap(text, recent) {
  const list = Array.isArray(recent) ? recent.slice(-RECENT) : []
  let best = 0
  for (const r of list) best = Math.max(best, overlap(text, r))
  return best
}

/**
 * 这一句是口水话吗。
 * @returns {{perfunctory:boolean, why:string, metrics:object}}
 */
export function isPerfunctory(text, recent) {
  const raw = String(text || '')
  const metrics = {
    chars: countChars(raw),
    nouns: countConcreteNouns(raw),
    filler: fillerRatio(raw),
    overlap: maxOverlap(raw, recent),
  }
  // 不作答。
  if (!raw.trim()) return { perfunctory: true, why: '没作答', metrics }
  if (metrics.chars < SHORT_CHARS) return { perfunctory: true, why: '不到 ' + SHORT_CHARS + ' 个字', metrics }
  if (metrics.nouns === 0) return { perfunctory: true, why: '一个具体名词都没有', metrics }
  if (metrics.filler > FILLER_MAX) return { perfunctory: true, why: '填充词太多', metrics }
  if (metrics.overlap > OVERLAP_MAX) return { perfunctory: true, why: '和刚才说的是同一句', metrics }
  return { perfunctory: false, why: '', metrics }
}

/** 这句算「混乱」吗（J4 的沟通上限，不是敷衍）。 */
export function isChaos(text) {
  const raw = String(text || '')
  if (countTopics(raw) >= 3) return { chaos: true, why: '说了三件以上' }
  if (countChars(raw) > LONG_CHARS && !hasConclusion(raw)) return { chaos: true, why: '说得长但没结论' }
  return { chaos: false, why: '' }
}

/**
 * 三分类 + 答对，一共四种结果。
 * 「答错」只重打，不罚；「敷衍」记一次走 P4；「混乱」只发压缩指令。
 * @param {{text:string, passed?:boolean|null, timedOut?:boolean, recent?:string[]}} input
 * @returns {{kind:'perfunctory'|'chaos'|'wrong'|'ok', why:string, metrics:object}}
 */
export function classify(input) {
  const text = String(input?.text || '')
  const recent = Array.isArray(input?.recent) ? input.recent : []

  // 超时按敷衍算——沉默比敷衍更贵，不能因为"没说话"就免罚。
  if (input?.timedOut) {
    const m = isPerfunctory(text, recent).metrics
    return { kind: 'perfunctory', why: '超时没答', metrics: m }
  }
  const p = isPerfunctory(text, recent)
  if (p.perfunctory) return { kind: 'perfunctory', why: p.why, metrics: p.metrics }

  const c = isChaos(text)
  if (c.chaos) return { kind: 'chaos', why: c.why, metrics: p.metrics }

  // 认真答了但不符合依据：只重打。
  if (input?.passed === false) return { kind: 'wrong', why: '不符合依据', metrics: p.metrics }
  return { kind: 'ok', why: '', metrics: p.metrics }
}

/** 这一拍该怎么处置。答错和敷衍在这里分岔，账本上也分开记。 */
export function handlingOf(kind) {
  if (kind === 'wrong') return { retry: true, penalize: false, note: '重打本拍，不罚' }
  if (kind === 'perfunctory') return { retry: true, penalize: true, note: '记一次敷衍，走升级' }
  if (kind === 'chaos') return { retry: true, penalize: false, note: '只发压缩指令，让他选一件' }
  return { retry: false, penalize: false, note: '过' }
}
