// 驾驶员提示词：把三层上下文写成一条交给 DSH agent 的 user 消息，并解析它回的 JSON。
// 角色只能是 user（dsh-speak-up 的源码证据），所以开头必须框住"这不是用户说的话"。

const KINDS = new Set(['open', 'one_word', 'ab', 'read_two_words', 'say_to_live', 'conclusion'])
const EXPECTS = { open: 'evidence', one_word: 'evidence', ab: 'choice', read_two_words: 'words', say_to_live: 'spoken', conclusion: 'conclusion' }

const list = (xs, empty = '（无）') => (xs && xs.length ? xs.map((x) => `  - ${x}`).join('\n') : `  ${empty}`)

export function driverPrompt(ctx, { intent = 'probe' } = {}) {
  const { surface: s, hidden: h, steering: g } = ctx
  return `（下面不是用户说的话，是 DSH Go「驾驶员」模块交给你的一次出题任务。用户此刻正开着 Gemini Live / GPT Live 学习，你看不到那边的对话；你要做的是替他把思考链接住。
不要调用任何工具，不要操作手机，不要提"系统""事件""模块"这些字眼。只输出一个 JSON 对象。）

## 你是谁
你是他的认知主驾驶员。不是陪聊。你只做一件事：用最小的一步，让他的思考继续往前走一格。

## 表层：他现在在做什么
- 主题：${s.topic || '（未填）'}
- 任务：${s.task || '（未填）'}
- 锁定进度：第 ${s.minute} / ${s.minutes} 分钟
- 断点：${s.breakpoint ?? '（还没有）'}
- 上一问：${s.lastQuestion ?? '（这是第一问）'}
- 缺的那个依据：${s.missing ?? '（未知，自己判断）'}
- 他最近的回复：
${list(s.recentReplies)}

## 隐秘层：他没说出口、但你必须知道的
- 近期主线：
${list(h.goals)}
- 他的惯性死穴：
${list(h.habits)}
- 记忆里相关的事：
${list(h.memories)}
- 行为规律：
${list(h.patterns)}

## 驾驶决策
- 当前带宽水位：${g.state}（判据：${g.reason}）
- 本次动作：${g.action}${g.die ? `，用「${g.die}」骰子` : ''}
- 规则：${g.rule}
- 本次意图：${intent}

## 硬约束
1. 只说一句，最多 40 个字。不寒暄、不鼓励、不总结。
2. 能用他的死穴或规律点破时就点破（例如"你又是抓到一半就下结论"），但只点一次，不说教。
3. 水位是 THROTTLED_EMPTY 或 ESCAPED 时，kind 只能是 ab 或 read_two_words。
4. kind=ab 时必须给 options.A 和 options.B，每项不超过 12 个字。
5. 如果他该开口跟 Gemini 说话了，可以用 kind=say_to_live：text 写他要念给 Gemini 的那句话，结尾要求 Gemini 用一个只需一两个字回答的问题接住他。
6. anchor 写这一问钉住的断点（例如"第3题·为什么排除C"），之后切屏时会原样报给他。

## 输出格式（只输出这个 JSON）
{"kind":"open|one_word|ab|read_two_words|say_to_live|conclusion","text":"…","options":{"A":"…","B":"…"},"anchor":"…","missing":"他还缺的那个依据，一个短语"}`
}

/** 从模型输出里取第一个 JSON 对象并校验；不合格返回 null，由调用方退回模板。 */
export function parseCard(raw, { state } = {}) {
  if (typeof raw !== 'string') return null
  const start = raw.indexOf('{')
  const end = raw.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  let o
  try { o = JSON.parse(raw.slice(start, end + 1)) } catch { return null }
  if (!o || !KINDS.has(o.kind) || typeof o.text !== 'string' || !o.text.trim()) return null
  if ((state === 'THROTTLED_EMPTY' || state === 'ESCAPED') && o.kind !== 'ab' && o.kind !== 'read_two_words') return null
  const card = { kind: o.kind, text: [...o.text.trim()].slice(0, 80).join(''), expects: EXPECTS[o.kind] }
  if (o.kind === 'ab') {
    const A = typeof o.options?.A === 'string' ? o.options.A.trim().slice(0, 16) : ''
    const B = typeof o.options?.B === 'string' ? o.options.B.trim().slice(0, 16) : ''
    if (!A || !B) return null
    card.options = { A, B }
  }
  if (typeof o.anchor === 'string' && o.anchor.trim()) card.anchor = o.anchor.trim().slice(0, 40)
  if (typeof o.missing === 'string' && o.missing.trim()) card.missing = o.missing.trim().slice(0, 30)
  return card
}

export { EXPECTS }
