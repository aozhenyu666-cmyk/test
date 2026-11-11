// P0-3 上下文预算：每轮注入的每一块都必须在一个总预算里装得下，装不下按优先级裁，不半截截断。
// 纯函数：不读配置、不写账本、不碰时间。谁调用谁负责把 kept/dropped/chars 落一条 ctx-budget。
//
// 三层：constraint（铁律/我.md/议程/硬立场）永不裁，超了直接抛错——
// 那是配置问题，必须让人看见，不许悄悄截断；state（信用分/作息/档位…）按 priority 整块装；
// content（随机块/悄悄话/暗思考产物）最后装，剩多少用多少。
export const LAYERS = ['constraint', 'state', 'content']

/**
 * 聊天侧每轮注入的上限。改这里等于改全局默认，逐次覆盖走第二参。
 * 约束层给 1800 而不是规格里写的 900：量出来的底稿本身就有 826（妹妹）/1004（姐姐），
 * 加上能力说明 354/390 和议程 333 已经到 1513/1727，900 装不下 —— 而约束层「永不裁、超了就抛错」，
 * 装不下等于每轮都抛。总量 2400 是验收硬指标，没动。见 docs/dafeiyu/第七轮进度.md 的 P0-3 一节。
 */
export const DEFAULT_BUDGET = {
  total: 2400,
  perLayer: { constraint: 1800, state: 1000, content: 500 },
}

const charsOf = (block) => String(block.text).length
// 同优先级保持传进来的顺序：调用方按语义排好了，别在这里再打乱。
const byPriority = (a, b) => (Number(b.priority) || 0) - (Number(a.priority) || 0)

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
 * @param {Array<{id: string, layer: string, priority?: number, text: string}>} blocks
 * @param {{total?: number, perLayer?: object}} [budget]
 * @returns {{text: string, kept: string[], dropped: Array<{id: string, reason: string}>, chars: number}}
 */
export function fitBudget(blocks, budget) {
  const total = Number(budget?.total) || DEFAULT_BUDGET.total
  const perLayer = { ...DEFAULT_BUDGET.perLayer, ...(budget?.perLayer || {}) }
  const list = (blocks || []).filter((b) => b && LAYERS.includes(b.layer) && String(b.text || '').length)
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

  // 2. 状态层 + 3. 内容层：都按优先级降序整块装；差一个字都整块丢，不做半截截断。
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
