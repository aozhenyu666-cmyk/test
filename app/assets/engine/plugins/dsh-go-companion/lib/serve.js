// dsh-go-companion · 发球类型
//
// 每次发球从六种里挑一种，最近三次用过的不再挑；同一类球连着砸会让人烦，
// 也是"重发同一句话"的根源之一。掷骰子走调用方传进来的 roll（0–100），纯本地、不花模型。

/** 六种球，顺序就是兜底顺序：前面更具体，后面更轻。 */
export const SERVE_TYPES = ['observe', 'memory', 'provoke', 'challenge', 'chat', 'review']

export const SERVE_LABEL = {
  observe: '观察',
  memory: '记忆回调',
  provoke: '观点挑衅',
  challenge: '小挑战',
  chat: '闲聊',
  review: '复盘',
}

/** 最近几次用过的不再挑。三次是任务书给的口径。 */
export const SERVE_AVOID_LAST = 3

function recentTypes(entries, window) {
  const out = []
  for (let i = entries.length - 1; i >= 0 && out.length < window; i -= 1) {
    const e = entries[i]
    if (!e || e.state !== 'generated') continue
    const t = String(e.serveType || '')
    if (SERVE_TYPES.includes(t)) out.push(t)
  }
  return out
}

/**
 * 挑这一球的类型。roll 越大越靠后（和 diceThreshold 同一个 0–100 口径）。
 * 六种都用过了（理论上不会，因为只排最近三次）就退回全部可选。
 *
 * `only` 限定范围（漠视之后只发更轻的球），`weights` 是每类的权重：
 * 被漠视的降、接住的升（见 silence.js）。权重靠"多放几张签"实现，
 * 不动掷骰本身的口径，同一个 roll 结果仍然稳定。
 */
export function pickServeType(entries, roll, { avoidLast = SERVE_AVOID_LAST, only, weights } = {}) {
  const pool = Array.isArray(only) && only.length ? SERVE_TYPES.filter((t) => only.includes(t)) : SERVE_TYPES
  const blocked = new Set(recentTypes(Array.isArray(entries) ? entries : [], avoidLast))
  let candidates = pool.filter((t) => !blocked.has(t))
  if (!candidates.length) candidates = pool.slice()
  const tickets = []
  for (const t of candidates) {
    const n = weights && Number(weights[t]) > 1 ? Math.round(Number(weights[t])) : 1
    for (let i = 0; i < n; i += 1) tickets.push(t)
  }
  const r = Math.min(99.999, Math.max(0, Number(roll) || 0))
  return tickets[Math.floor((r / 100) * tickets.length)]
}
