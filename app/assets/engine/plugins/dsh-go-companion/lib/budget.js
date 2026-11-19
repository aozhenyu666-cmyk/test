// P0-3 + H8（C10）统一上下文预算：每轮注入的所有上下文块统一收编进同一张预算表（层、优先级、单块上限），
// 并在总预算与分层预算内确定性裁决。纯函数：不读配置、不写账本、不碰时间。
//
// 三层：constraint（铁律/我.md/议程/硬立场）永不裁，超了直接抛错——
// 那是配置问题，必须让人看见，不许悄悄截断；state（信用分/作息/档位/同日脉络…）按单块上限收敛后按 priority 整块装；
// content（交接/验收器/思考层/四部Skill/知识库/随机块…）最后装，按单块上限收敛后剩多少装多少。
export const LAYERS = ['constraint', 'state', 'content']

/**
 * H8（C10）全局统一上下文块预算表：所有注入块的唯一真相源（layer / priority / maxChars）。
 * 新增上下文块必须在此登记显式单块上限，防止 handoff / reflect / verifier / v4-skills 等失控挤空其它块。
 */
export const CONTEXT_BLOCK_TABLE = Object.freeze({
  // 1. 约束层（永不静默截断；整层超 perLayer.constraint 直接抛错）
  persona: { layer: 'constraint', priority: 100, maxChars: 1200 },
  refuse: { layer: 'constraint', priority: 95, maxChars: 240 },
  capability: { layer: 'constraint', priority: 90, maxChars: 450 },
  stance: { layer: 'constraint', priority: 87, maxChars: 240 },
  plan: { layer: 'constraint', priority: 86, maxChars: 300 },
  agenda: { layer: 'constraint', priority: 85, maxChars: 380 },

  // 2. 状态层（运行时关键事实快照）
  'telemetry-contradiction': { layer: 'state', priority: 72, maxChars: 240 },
  withdrawal: { layer: 'state', priority: 70, maxChars: 160 },
  mount: { layer: 'state', priority: 65, maxChars: 340 },
  trust: { layer: 'state', priority: 60, maxChars: 220 },
  'casual-short': { layer: 'state', priority: 59, maxChars: 160 },
  emotion: { layer: 'state', priority: 58, maxChars: 260 },
  'intraday-storyline': { layer: 'state', priority: 57, maxChars: 360 },
  rhythm: { layer: 'state', priority: 55, maxChars: 240 },
  relationship: { layer: 'state', priority: 52, maxChars: 320 },
  gear: { layer: 'state', priority: 50, maxChars: 220 },
  gemini: { layer: 'state', priority: 45, maxChars: 280 },
  deposit: { layer: 'state', priority: 40, maxChars: 360 },
  'life-slice': { layer: 'state', priority: 38, maxChars: 200 },

  // 3. 内容层（交接 / 验收器 / 思考层 / 四部 Skill / 知识库 / 素材）
  handoff: { layer: 'content', priority: 80, maxChars: 640 },
  verifier: { layer: 'content', priority: 72, maxChars: 300 },
  reflect: { layer: 'content', priority: 70, maxChars: 380 },
  'v4-skills': { layer: 'content', priority: 66, maxChars: 300 },
  vibe: { layer: 'content', priority: 20, maxChars: 260 },
  'inner-os': { layer: 'content', priority: 19, maxChars: 220 },
  'slot-ask': { layer: 'content', priority: 18, maxChars: 200 },
  nightly: { layer: 'content', priority: 16, maxChars: 360 },
  'vault-pick': { layer: 'content', priority: 14, maxChars: 320 },
  'kb-self': { layer: 'content', priority: 12, maxChars: 820 },
  clues: { layer: 'content', priority: 12, maxChars: 260 },
  'spontaneous-tool': { layer: 'content', priority: 11, maxChars: 200 },
  'kb-cog': { layer: 'content', priority: 10, maxChars: 850 },
  notes: { layer: 'content', priority: 10, maxChars: 360 },
  telemetry: { layer: 'content', priority: 9, maxChars: 380 },
  'kb-judge': { layer: 'content', priority: 8, maxChars: 720 },
  'audit-cot': { layer: 'content', priority: 8, maxChars: 280 },
  lorebook: { layer: 'content', priority: 7, maxChars: 420 },
  'kb-rule': { layer: 'content', priority: 5, maxChars: 160 },
})

export const BLOCK_CAPS = Object.freeze(
  Object.fromEntries(Object.entries(CONTEXT_BLOCK_TABLE).map(([id, spec]) => [id, spec.maxChars])),
)

export const DEFAULT_BUDGET = {
  total: 5600,
  perLayer: { constraint: 1800, state: 2400, content: 1800 },
  perBlock: BLOCK_CAPS,
}

const charsOf = (block) => String(block.text).length
// 同优先级保持传进来的顺序：调用方按语义排好了，别在这里再打乱。
const byPriority = (a, b) => (Number(b.priority) || 0) - (Number(a.priority) || 0)

/**
 * 状态层/内容层的单块确定性封顶：优先在 60% 以上的最近换行处收口，保证单块字符数严格 <= maxChars。
 */
export function clampBlockText(text, maxChars) {
  const s = String(text || '')
  const cap = Number(maxChars)
  if (!Number.isFinite(cap) || cap <= 0 || s.length <= cap) return s
  if (cap <= 2) return s.slice(0, cap)
  const slice = s.slice(0, cap - 1)
  const nl = slice.lastIndexOf('\n')
  if (nl >= Math.floor((cap - 1) * 0.6)) return slice.slice(0, nl).trimEnd() + '…'
  return slice.trimEnd() + '…'
}

/**
 * 注入块的边界标记。借 dsh-soul 的做法：自己注入的运行时快照要能认出来，
 * 免得写回历史 / 写进记忆 / 导出知识库时把自己的话当成他说的话（自我污染）。
 * 约束层（人设底稿、能力说明、议程）不打标记——那本来就是人设的一部分。
 */
export const INJECT_OPEN = '〔鱼·注入开始〕'
export const INJECT_CLOSE = '〔鱼·注入结束〕'

const MARK_RE = new RegExp('\\n?' + INJECT_OPEN + '[\\s\\S]*?' + INJECT_CLOSE, 'g')

/** 把注入块整段剥掉：出口（账本/记忆/导出）用这个，别让标记和运行时快照流出去。 */
export function stripInjection(text) {
  return String(text || '').replace(MARK_RE, '')
}

/**
 * @param {Array<{id: string, layer?: string, priority?: number, text: string}>} blocks
 * @param {{total?: number, perLayer?: object, perBlock?: Record<string, number>}} [budget]
 * @returns {{text: string, kept: string[], dropped: Array<{id: string, reason: string}>, chars: number}}
 */
export function fitBudget(blocks, budget) {
  const total = Number(budget?.total) || DEFAULT_BUDGET.total
  const perLayer = { ...DEFAULT_BUDGET.perLayer, ...(budget?.perLayer || {}) }
  const perBlock = { ...BLOCK_CAPS, ...(budget?.perBlock || {}) }
  const list = (blocks || [])
    .map((b) => {
      if (!b || typeof b !== 'object') return null
      const spec = b.id ? CONTEXT_BLOCK_TABLE[b.id] : null
      const layerName = b.layer || spec?.layer || ''
      if (!LAYERS.includes(layerName)) return null
      const rawText = String(b.text || '')
      if (!rawText.length) return null
      const priority = b.priority !== undefined ? b.priority : (spec?.priority ?? 0)
      const blockCap = layerName === 'constraint' ? 0 : (Number(perBlock[b.id]) || 0)
      const text = blockCap > 0 ? clampBlockText(rawText, blockCap) : rawText
      return text.length ? { ...b, layer: layerName, priority, text } : null
    })
    .filter(Boolean)
  const layer = (id) => list.filter((b) => b.layer === id)

  const kept = []
  const dropped = []
  let used = 0

  // 1. 约束层：全装。装不下就是配置错了，抛错让人看见（调用方落账本）。
  const constraint = layer('constraint')
  const constraintChars = constraint.reduce((n, b) => n + charsOf(b), 0)
  if (constraintChars > perLayer.constraint) {
    const error = new Error('约束层超预算：' + constraintChars + ' > ' + perLayer.constraint + '（' +
      constraint.map((b) => b.id).join(', ') + '）')
    error.reason = 'constraint-over-budget'
    error.chars = constraintChars
    error.budget = perLayer.constraint
    error.blocks = constraint.map((b) => b.id)
    throw error
  }
  for (const block of constraint) kept.push(block)
  used = constraintChars

  // 2. 状态层 + 3. 内容层：先受单块上限约束，再按优先级降序整块装；差一个字都整块丢，不做半截乱断。
  // 这两层是"运行时快照"，装进去时包上首尾标记，出口处能整段剥掉。
  for (const [name, cap] of [['state', perLayer.state], ['content', perLayer.content]]) {
    let inLayer = 0
    for (const block of layer(name).sort(byPriority)) {
      const n = charsOf(block) + INJECT_OPEN.length + INJECT_CLOSE.length + 1
      const reason = used + n > total ? 'total' : inLayer + n > cap ? 'layer' : ''
      if (reason) {
        dropped.push({ id: block.id, reason })
        continue
      }
      kept.push({ ...block, text: INJECT_OPEN + '\n' + block.text + '\n' + INJECT_CLOSE })
      used += n
      inLayer += n
    }
  }

  const text = kept.map((b) => b.text).join('\n')
  return { text, kept: kept.map((b) => b.id), dropped, chars: text.length }
}
