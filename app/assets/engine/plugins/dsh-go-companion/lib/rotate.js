// dsh-go-companion · 会话换班（2026-11-18）
//
// 两条鱼各绑一个对话，越聊越长：上下文变重、变贵、变钝。到线（默认 150 轮或 7 天，或他手动点）就换班：
//   1. 插件写一份交接（代码拼，不靠模型自由发挥）：最近说过的原话、答应过的事、没聊完的话题、立场、
//      最近一次 Gemini 回执、昨天的判决。写进 companion/handoff-<角色>.json 和 Obsidian 大肥鱼/会话交接/。
//   2. App 新建一个对话、绑上去（插件不能自己建会话）。
//   3. 新对话头 3 天 / 头 30 轮，每一轮上下文都带这份交接；之后交接留在笔记库里，记忆照常按需召回。
// 旧对话不删，只是不再绑定。

export const ROTATE_MAX_TURNS = 150
export const ROTATE_MAX_DAYS = 7
export const HANDOFF_TTL_MS = 3 * 86_400_000
export const HANDOFF_TTL_TURNS = 30
const TURN_STATES = new Set(['generated', 'human', 'say-text'])

/** 这个对话绑定以来聊了几轮、几天，该不该换班。 */
export function rotationStatus(entries, { sessionId = '', boundAt = 0, now = Date.now(), maxTurns = ROTATE_MAX_TURNS, maxDays = ROTATE_MAX_DAYS } = {}) {
  if (!sessionId) return { sessionId: '', turns: 0, days: 0, due: false, why: '还没绑定对话' }
  let turns = 0
  let first = 0
  for (const e of Array.isArray(entries) ? entries : []) {
    if (!e || !TURN_STATES.has(e.state)) continue
    if (e.sessionId && String(e.sessionId) !== String(sessionId)) continue
    if (!e.sessionId && e.state !== 'say-text') continue
    if (boundAt && Number(e.at) < boundAt) continue
    turns++
    if (!first || Number(e.at) < first) first = Number(e.at)
  }
  const since = boundAt || first || now
  const days = Math.max(0, Math.floor((now - since) / 86_400_000))
  const due = turns >= maxTurns || (days >= maxDays && turns >= 20)
  const why = turns >= maxTurns ? '已经聊了 ' + turns + ' 轮' : (due ? '这个对话已经用了 ' + days + ' 天' : '')
  return { sessionId, turns, days, due, why }
}

/** 最近的原话（他说的、她说的），新的在后。 */
export function recentDialog(entries, { sessionId = '', limit = 16 } = {}) {
  const out = []
  for (const e of Array.isArray(entries) ? entries : []) {
    if (!e || typeof e.text !== 'string' || !e.text.trim()) continue
    if (e.state === 'say-text') out.push({ who: '他', at: Number(e.at) || 0, text: e.text })
    else if (e.state === 'generated' && (!sessionId || String(e.sessionId || '') === String(sessionId))) out.push({ who: '你', at: Number(e.at) || 0, text: e.text })
  }
  return out.slice(-limit)
}

/** 交接（markdown，≤2600 字）。 */
export function buildHandoff({ roleName = '鱼丸', owner = '他', dialog = [], humanRecent = [], commitments = [], topics = [], stance = '', gemini = [], verdictLine = '', facts = [], now = Date.now() } = {}) {
  const day = new Date(now + 8 * 3_600_000).toISOString().slice(0, 10)
  const lines = [
    '# ' + day + ' · ' + roleName + ' 的交接（上一个对话换下来的）',
    '',
    '你刚换到一个新对话。下面是上一个对话里要紧的东西——接着聊，别说"我换了个对话"，像一直在一样。',
    '',
  ]
  const sec = (title, arr) => {
    const xs = (Array.isArray(arr) ? arr : []).map((x) => String(x || '').trim()).filter(Boolean)
    if (!xs.length) return
    lines.push('## ' + title, '')
    for (const x of xs) lines.push('- ' + x.slice(0, 200))
    lines.push('')
  }
  sec('他答应过、还没做完的', commitments)
  sec('没聊完的话题', topics)
  if (String(stance || '').trim()) sec('你们之前定下的立场', String(stance).split('\n'))
  sec('最近的 Gemini 回执', gemini)
  if (verdictLine) sec('昨天的判决', [verdictLine])
  sec('关于' + owner + '（常驻）', facts.slice(0, 6))
  const talk = (Array.isArray(dialog) ? dialog : []).map((d) => d.who + '：' + String(d.text).replace(/\s+/g, ' ').slice(0, 120))
  const extra = (Array.isArray(humanRecent) ? humanRecent : []).map((h) => '他：' + String(h.text || '').slice(0, 120))
  sec('最后几句原话（照原样，别改写）', [...talk, ...extra].slice(-16))
  return lines.join('\n').slice(0, 2600)
}

/** 这份交接还该不该进上下文。 */
export function handoffLive(h, { now = Date.now(), turnsSince = 0 } = {}) {
  if (!h || !h.text) return false
  if (now - Number(h.at || 0) > HANDOFF_TTL_MS) return false
  return turnsSince < HANDOFF_TTL_TURNS
}
