// 运行与推进日志。只认五个状态，不拿"聊了几轮"充当有效思考：
//   GENERATED 已生成 → DISPLAYED 手机上真的弹出/播报了 → REPLIED 收到回复 → ADVANCED 确实推进了
//   RESUMED：这张卡曾因切屏被冻结为断点，后来被接回并答上了
// 每一步只能在前一步之后发生；跳步不补写，而是如实记一个 flag。
//
// 另按三级数"有没有在思考"（点 A/B 是为了把人拉回来，不算思考）：
//   醒 awake      卡亮出后 awakeMs 内有任何对话迹象，或回复了这张卡
//   回 responded  ADVANCED 的任何一种（点了 A/B、念了两个词、对 Live 开了口……）
//   想 grounded   ADVANCED 且说出了依据 / 命中关键词 / 结论带依据 —— 主指标

export const STEP = Object.freeze({
  GENERATED: 'GENERATED',
  DISPLAYED: 'DISPLAYED',
  REPLIED: 'REPLIED',
  ADVANCED: 'ADVANCED',
  RESUMED: 'RESUMED',
})

const GROUNDED = new Set(['basis_given', 'substantive', 'conclusion_grounded'])

export class Telemetry {
  /** 这个推进理由算不算"有依据的思考"。 */
  static isGrounded(why) {
    return GROUNDED.has(why)
  }

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

  /** 卡亮出后有对话迹象。inWindow=false 时只有回复本身才记（迟到的回复也说明醒过）。 */
  awake(cardId, at, inWindow = true) {
    const it = this.items.get(cardId)
    if (!it || it.awakeAt || !it.displayedAt || !inWindow) return false
    it.awakeAt = at
    this.#log({ at, step: 'AWAKE', card: cardId })
    return true
  }

  replied(cardId, at, reply) {
    const it = this.items.get(cardId)
    if (!it || it.repliedAt) return false
    if (!it.displayedAt) it.flags.push('displayed_unproven') // 有回复却没有可见证明：不补写 DISPLAYED
    it.repliedAt = at
    if (!it.awakeAt) it.awakeAt = at
    this.#log({ at, step: STEP.REPLIED, card: cardId, text: reply.text, latencyMs: reply.latencyMs, score: reply.features?.score })
    return true
  }

  advanced(cardId, at, why) {
    const it = this.items.get(cardId)
    if (!it || !it.repliedAt || it.advancedAt) return false
    it.advancedAt = at
    it.advancedWhy = why
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
      awake: n('awakeAt'),
      responded: advanced,
      grounded: all.filter((x) => GROUNDED.has(x.advancedWhy)).length,
    }
  }
}
