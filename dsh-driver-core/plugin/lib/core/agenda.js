// 跨会话思考议程：没接上的断点、没补上的依据、没把握的结论，存成待办；下次开场主动从里面挑题。
// 纯规则，不调模型。落盘由 store.js 负责（agenda.json）。
//
// 入账口径参考 Mika MemoryExtractor 的原话校验：结论类待办必须带你的原话，原话不在你说过的话里就不收。

import { jaccard, terms } from './similar.js'

const SOURCES = new Set(['unresumed', 'missing', 'conclusion_doubt'])
const MAX_SKIPS = 3

export class Agenda {
  /** @param {Array} items 从 agenda.json 读回的条目 */
  constructor(items = [], { max = 30, newId } = {}) {
    this.max = max
    let n = 0
    this.newId = newId ?? (() => `a${Date.now().toString(36)}${(n++).toString(36)}`)
    this.items = (Array.isArray(items) ? items : []).filter((x) => x && typeof x.text === 'string' && x.id)
  }

  open() {
    return this.items.filter((x) => x.status === 'open')
  }

  /**
   * @param {{text:string, anchor?:string, source:string, quote?:string, missing?:string, at:number}} item
   * @param {{utterances?: string[]}} [check] 这次会话里你说过的话，用来校验 quote
   * @returns {object|null} 新加或被合并的那条；不合格返回 null
   */
  add(item, { utterances = [] } = {}) {
    if (!item || !SOURCES.has(item.source) || typeof item.text !== 'string' || !item.text.trim()) return null
    if (item.source === 'conclusion_doubt') {
      const q = String(item.quote ?? '').trim()
      if (!q || !utterances.some((u) => String(u).includes(q))) return null
    }
    const anchor = String(item.anchor ?? item.text).slice(0, 40)
    // 同一个断点只留一条：再遇到就刷新时间、累计次数
    const same = this.open().find((x) => jaccard(x.anchor, anchor) >= 0.8)
    if (same) {
      same.lastSeenAt = item.at
      same.touches = (same.touches ?? 1) + 1
      if (item.missing) same.missing = item.missing
      return same
    }
    const row = {
      id: this.newId(), text: item.text.slice(0, 80), anchor, source: item.source,
      quote: item.quote ? String(item.quote).slice(0, 120) : undefined, missing: item.missing,
      createdAt: item.at, lastSeenAt: item.at, touches: 1, skips: 0, status: 'open',
    }
    this.items.push(row)
    this.#trim()
    return row
  }

  /** 按这次的主题/任务挑最相关的一条 open 待办；都不相关返回 null。 */
  match({ topic = '', task = '' } = {}, minScore = 0.15) {
    const q = terms(`${topic} ${task}`)
    if (!q.size) return null
    let best = null
    for (const x of this.open()) {
      const t = terms(`${x.anchor} ${x.text}`)
      let inter = 0
      for (const w of q) if (t.has(w)) inter++
      const score = inter / q.size // 查询词被覆盖了多少
      if (score >= minScore && (!best || score > best.score || (score === best.score && x.lastSeenAt > best.x.lastSeenAt))) best = { x, score }
    }
    return best?.x ?? null
  }

  done(id, at) { return this.#set(id, 'done', at) }

  /** 开场被跳过：连续 3 次就不再纠缠。 */
  skip(id, at) {
    const x = this.items.find((i) => i.id === id && i.status === 'open')
    if (!x) return null
    x.skips = (x.skips ?? 0) + 1
    x.lastSeenAt = at
    if (x.skips >= MAX_SKIPS) x.status = 'dropped'
    return x
  }

  toJSON() { return this.items }

  #set(id, status, at) {
    const x = this.items.find((i) => i.id === id && i.status === 'open')
    if (!x) return null
    x.status = status
    x.closedAt = at
    return x
  }

  #trim() {
    // 超上限先丢已关闭的，再丢最旧的 open
    while (this.items.length > this.max) {
      const closed = this.items.findIndex((x) => x.status !== 'open')
      if (closed >= 0) { this.items.splice(closed, 1); continue }
      let oldest = 0
      this.items.forEach((x, i) => { if (x.lastSeenAt < this.items[oldest].lastSeenAt) oldest = i })
      this.items.splice(oldest, 1)
    }
  }
}
