// 驾驶员上下文综合：每次开口前组装三层——表层 / 隐秘历史 / 驾驶决策。
// 只做挑选和压缩，不调模型。所有输入都是已经读好的数据，方便测试。

import { COG } from './bandwidth.js'
import { clip, normalize } from './text.js'

const HIDDEN_TAGS = ['习惯', '死穴', '逃避', '惯性', '目标', '主线', '能量', '状态', '考公', '行测', '申论', '求职', '项目']

/**
 * 从 fish-memory 等记忆里挑和"这次在做的事"最相关的几条。
 * @param {Array<{text:string, at?:number|string, tags?:string[], category?:string, importance?:number}>} memories
 */
export function pickMemories(memories, { topic = '', task = '' } = {}, limit = 5) {
  const words = [...new Set(`${topic} ${task}`.split(/[\s,，、/|]+/).map(normalize).filter((w) => w.length >= 2))]
  const scored = (memories ?? []).map((m) => {
    const t = normalize(m.text)
    const tags = (m.tags ?? []).concat(m.category ?? [])
    let s = (m.importance ?? 0.5)
    if (tags.some((x) => HIDDEN_TAGS.some((h) => String(x).includes(h)))) s += 1
    if (HIDDEN_TAGS.some((h) => t.includes(h))) s += 0.5
    s += words.filter((w) => t.includes(w)).length
    return { m, s }
  })
  return scored.filter((x) => x.s >= 1).sort((a, b) => b.s - a.s).slice(0, limit).map((x) => x.m)
}

/** 从跨会话统计里读出规律（只说有样本支撑的）。 */
export function patternsOf(history = {}) {
  const out = []
  const esc = history.escapeMinutes ?? {}
  const total = Object.values(esc).reduce((a, b) => a + b, 0)
  if (total >= 3) {
    const [peak] = Object.entries(esc).sort((a, b) => b[1] - a[1])
    out.push(`过去 ${history.sessions ?? '若干'} 次锁定里，切走最常发生在第 ${peak[0]} 分钟前后（${peak[1]}/${total} 次）`)
  }
  const dice = Object.entries(history.dice ?? {}).filter(([, v]) => v.tries >= 3)
  if (dice.length) {
    const [best, v] = dice.sort((a, b) => b[1].advanced / b[1].tries - a[1].advanced / a[1].tries)[0]
    out.push(`对他最管用的降维骰子是「${best}」（${v.advanced}/${v.tries} 次推进）`)
  }
  if (history.medianLatencyMs) out.push(`他平时回驾驶员的中位耗时约 ${Math.round(history.medianLatencyMs / 1000)} 秒`)
  return out
}

const STEERING = {
  [COG.ACTIVE_FLOW]: {
    action: 'push',
    rule: '他在推进。追问下一步或要一个依据，一句话，问题要具体到能用一句话答。',
  },
  [COG.HIGH_SPEED_SKIMMING]: {
    action: 'brake',
    rule: '他回得快但没依据，是限速前兆。踩刹车：点出"刚才滑过去了"，结合他的老毛病说漏了什么，只要求他说出一个词作为依据。',
  },
  [COG.THROTTLED_EMPTY]: {
    action: 'dice',
    rule: '他已经限速/脑子空。严禁开放式提问。用指定的骰子，把问题压成"看屏幕念两个词"或"A/B 二选一"，三秒能完成。',
  },
  [COG.ESCAPED]: {
    action: 'pullback',
    rule: '他切走了。直接报出断点，不要求重想，只要一个 A/B 或一个词。',
  },
}

/**
 * @param {object} s
 * @param {{topic:string, task:string, minute:number, minutes:number}} s.session
 * @param {{text:string, anchor?:string}|null} s.lastCard
 * @param {Array<{text:string}>} s.recentReplies
 * @param {{state:string, reason:string}} s.cog
 * @param {string|undefined} s.die THROTTLED 时选好的骰子
 * @param {Array} s.memories fish-memory 等的记忆
 * @param {{goals?:string[], habits?:string[]}} s.profile 你自己写的主线和死穴
 * @param {object} s.history 跨会话统计
 */
export function synthesize(s) {
  const surface = {
    topic: s.session.topic,
    task: s.session.task,
    minute: s.session.minute,
    minutes: s.session.minutes,
    breakpoint: s.lastCard?.anchor ?? null,
    lastQuestion: s.lastCard ? clip(s.lastCard.text, 80) : null,
    recentReplies: (s.recentReplies ?? []).slice(-3).map((r) => clip(r.text, 40)),
    missing: s.lastCard?.missing ?? null,
  }
  const hidden = {
    goals: (s.profile?.goals ?? []).slice(0, 3),
    habits: (s.profile?.habits ?? []).slice(0, 4),
    memories: pickMemories(s.memories, s.session).map((m) => clip(m.text, 60)),
    patterns: patternsOf(s.history),
  }
  const st = STEERING[s.cog.state]
  const steering = { state: s.cog.state, reason: s.cog.reason, action: st.action, rule: st.rule, die: s.die ?? null }
  return { surface, hidden, steering }
}
