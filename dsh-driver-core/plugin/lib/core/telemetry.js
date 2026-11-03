// 运行与推进日志。只认五个状态，不拿"聊了几轮"充当有效思考：
//   GENERATED 已生成 → DISPLAYED 手机上真的弹出/播报了 → REPLIED 收到回复 → ADVANCED 确实推进了
//   RESUMED：这张卡曾因切屏被冻结为断点，后来被接回并答上了
// 每一步只能在前一步之后发生；跳步不补写，而是如实记一个 flag。

export const STEP = Object.freeze({
  GENERATED: 'GENERATED',
  DISPLAYED: 'DISPLAYED',
  REPLIED: 'REPLIED',
  ADVANCED: 'ADVANCED',
  RESUMED: 'RESUMED',
})

export class Telemetry {
  /** @param {{append?: (row: object) => void}} sink 每条记录落盘（JSONL）；测试里可省略 */
  constructor(sink = {}) {
    this.items = new Map()
    this.rows = []
    this.append = sink.append ?? (() => {})
  }

  #log(row) {
    this.rows.push(row)
    try { this.append(row) } catch { /* 写盘失败不影响驾驶 */ }
  }

  generated(card, at) {
    if (this.items.has(card.id)) return
    this.items.set(card.id, { id: card.id, intent: card.intent, kind: card.kind, source: card.source, generatedAt: at, flags: [] })
    this.#log({ at, step: STEP.GENERATED, card: card.id, intent: card.intent, kind: card.kind, source: card.source, text: card.text })
  }

  displayed(cardId, at, via) {
    const it = this.items.get(cardId)
    if (!it || it.displayedAt) return false
    it.displayedAt = at
    this.#log({ at, step: STEP.DISPLAYED, card: cardId, via })
    return true
  }

  replied(cardId, at, reply) {
    const it = this.items.get(cardId)
    if (!it || it.repliedAt) return false
    if (!it.displayedAt) it.flags.push('displayed_unproven') // 有回复却没有可见证明：不补写 DISPLAYED
    it.repliedAt = at
    this.#log({ at, step: STEP.REPLIED, card: cardId, text: reply.text, latencyMs: reply.latencyMs, score: reply.features?.score })
    return true
  }

  advanced(cardId, at, why) {
    const it = this.items.get(cardId)
    if (!it || !it.repliedAt || it.advancedAt) return false
    it.advancedAt = at
    this.#log({ at, step: STEP.ADVANCED, card: cardId, why })
    return true
  }

  interrupted(cardId, at, reason) {
    const it = this.items.get(cardId)
    if (!it) return
    it.interrupted = (it.interrupted ?? 0) + 1
    this.#log({ at, step: 'INTERRUPTED_BY_SWITCH', card: cardId, reason })
  }

  resumed(cardId, at) {
    const it = this.items.get(cardId)
    if (!it || !it.interrupted || it.resumedAt) return false
    it.resumedAt = at
    this.#log({ at, step: STEP.RESUMED, card: cardId })
    return true
  }

  note(at, kind, data = {}) {
    this.#log({ at, step: 'NOTE', kind, ...data })
  }

  summary() {
    const all = [...this.items.values()]
    const n = (k) => all.filter((x) => x[k] != null).length
    const generated = all.length
    const advanced = n('advancedAt')
    return {
      generated,
      displayed: n('displayedAt'),
      replied: n('repliedAt'),
      advanced,
      resumed: n('resumedAt'),
      interruptions: all.reduce((s, x) => s + (x.interrupted ?? 0), 0),
      displayedUnproven: all.filter((x) => x.flags.includes('displayed_unproven')).length,
      advanceRate: generated ? Math.round((advanced / generated) * 100) / 100 : 0,
    }
  }
}
