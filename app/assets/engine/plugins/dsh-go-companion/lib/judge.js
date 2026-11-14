// dsh-go-companion · 判球
//
// 一球打回来，值不值得继续深入，由大肥鱼自己判：它有人格和记忆，比字数规则懂。
// 只让它输出一行 JSON，判词固定六种：
//   engaged  接住了，有信息、在推进
//   brief    很短、没信息（"嗯""随便"）
//   evasive  把话题带走（"以后再说""不说这个"）
//   stock    套话、复述、正确的废话
//   pushback 真正的反驳 —— 最好的球，要正面接住
//   refuse   明确拒绝继续
// 回击方式 next：deeper 往深一步 / angle 换角度 / callout 点破 / stop 停下
//
// 连不上、超时、输出不是 JSON —— 一律退回本地规则（classify.js），绝不因为判球失败就让一轮卡住。

import { classifyReply } from './classify.js'
import { isStock } from './stock.js'

export const VERDICTS = ['engaged', 'brief', 'evasive', 'stock', 'refuse', 'pushback']
export const NEXT_ACTIONS = ['deeper', 'angle', 'callout', 'stop']

/** 判球是"顺手看一眼"，不该让一轮等太久。任务书给的 6 秒。 */
// 真模型（deepseek-flash 先思考再答）判一次约 3 秒；6 秒在长回复上会超时退回规则。
export const JUDGE_TIMEOUT_MS = 10_000

/** 判词 → 回击方式。判球之外的默认表，模型给出 next 时以模型为准。 */
const NEXT_OF = {
  engaged: 'deeper',
  pushback: 'deeper',
  brief: 'angle',
  evasive: 'angle',
  stock: 'callout',
  refuse: 'stop',
}

/** 判词 → 话题花园认识的四种分类之一（garden.js 只认 substantive/brief/avoid/refuse）。 */
export function gardenClass(verdict) {
  switch (verdict) {
    case 'engaged':
    case 'pushback':
      return 'substantive'
    case 'evasive':
      return 'avoid'
    case 'refuse':
      return 'refuse'
    default:
      return 'brief'
  }
}

export function judgeSystem(mode) {
  const lines = [
    '你在判断对方刚打回来的一球值不值得你继续往深里打。只输出一行 JSON，不要任何解释文字。',
    '判词只能是这六个之一：',
    'engaged 接住了、有信息在推进；brief 很短没信息；evasive 把话题带走；',
    'stock 套话、复述、正确的废话；pushback 真正的反驳；refuse 明确不想聊了。',
    '回击方式只能是这四个之一：deeper 往深一步；angle 换个角度；callout 点破并追一次；stop 停下。',
    '格式：{"verdict":"...","why":"一句话","next":"..."}。why 里引用他的话用「」，不要用双引号。',
    'pushback 是最好的球，往深里接，不要当成冒犯。判成 refuse 要克制：只有对方真的说不想聊才算。',
  ]
  // 教练模式判得严：敷衍和空话不许放过去。
  if (String(mode) === 'coach') {
    lines.push('现在是教练模式：短句、空话、把话题带走，一律不许当成接住了；该点破就点破。')
  }
  return lines.join('\n')
}

/** 把发球原文、这条回球、最近几拍拼成判断题。只带这些，不带无关背景。 */
export function judgeUser({ serve, reply, recent, criteria }) {
  const lines = []
  if (serve) lines.push('你上一球打出去的是：' + String(serve).slice(0, 500))
  // 思维题有合格线：没过线就不是接住了，哪怕字很多。
  if (criteria) {
    lines.push('上一球是一道思维题，合格的回答必须是：' + String(criteria).slice(0, 200) +
      '。没达到就判 brief 或 stock（凭感觉、似是而非、只有感受词都算），why 里写清差在哪。')
  }
  const shots = Array.isArray(recent) ? recent.slice(-3) : []
  if (shots.length) {
    lines.push('再往前几拍（旧→新）：')
    for (const s of shots) {
      const who = s && s.from === 'me' ? '你' : '对方'
      lines.push('- ' + who + '：' + String((s && s.text) || '').slice(0, 200))
    }
  }
  lines.push('对方这一球打回来的是：' + String(reply == null ? '' : reply).slice(0, 1000))
  lines.push('判这一球。只输出 JSON。')
  return lines.join('\n')
}

/** 从模型输出里抠出第一个 JSON 对象；字段不在词表里就当没判出来。 */
export function parseJudge(raw) {
  const text = String(raw == null ? '' : raw)
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  let obj
  try {
    obj = JSON.parse(text.slice(start, end + 1))
  } catch {
    // 真模型常在 why 里用没转义的双引号引用原话（"作业写完了"），整段 JSON 就坏了。
    // 判词和回击是固定词表，按字段抠出来；why 取 "why":" 到 ","next" 之间。
    const body = text.slice(start, end + 1)
    const pick = (k) => (new RegExp('"' + k + '"\\s*:\\s*"([a-z]+)"').exec(body) || [])[1]
    const why = (/"why"\s*:\s*"([\s\S]*?)"\s*,\s*"(?:next|verdict)"/.exec(body) || [])[1]
    obj = { verdict: pick('verdict'), next: pick('next'), why: why || '' }
  }
  if (!obj || typeof obj !== 'object') return null
  const verdict = String(obj.verdict || '').trim().toLowerCase()
  if (!VERDICTS.includes(verdict)) return null
  const next = String(obj.next || '').trim().toLowerCase()
  return {
    verdict,
    why: String(obj.why || '').slice(0, 200),
    next: NEXT_ACTIONS.includes(next) ? next : NEXT_OF[verdict],
  }
}

/**
 * 本地规则兜底。判球连不上时用它，口径和以前一致，只多一条：
 * 命中套话模板的不算接住了（以前 ≥16 字就算实质回答，一句够长的废话能过）。
 *
 * shortcut: 规则只认字面模板（"下一步是什么""加油"这种），
 * 认不出"任何事情都有两面性"这类自己组织的空话——那种要靠模型。判球连不上时就放过它。
 * 真要规则也认得出来，得换成"信息量/具体名词密度"这类打分，不是现在这版该做的事。
 */
export function ruleVerdict(raw) {
  const text = String(raw == null ? '' : raw).replace(/\s+/g, ' ').trim()
  if (text && isStock(text)) return { verdict: 'stock', why: '规则兜底：命中套话模板', next: 'callout' }
  const verdict = {
    substantive: 'engaged',
    brief: 'brief',
    avoid: 'evasive',
    refuse: 'refuse',
  }[classifyReply(text)] || 'brief'
  return { verdict, why: '规则兜底', next: NEXT_OF[verdict] }
}

/**
 * 判一球。`stream` 是 ctx.llm.stream（测试里可以直接给假的），不传就用规则。
 *
 * shortcut: 只有一次调用、一次超时，不重试。判球失败最坏就是退回规则，
 * 不值得为它多花一次模型调用和一个重试窗口；要更准再考虑带记忆的二次判断。
 */
export async function judgeReply({ stream, provider, model, sessionId, serve, reply, recent, mode, criteria, timeoutMs = JUDGE_TIMEOUT_MS }) {
  if (typeof stream !== 'function' || !provider || !model) return { ...ruleVerdict(reply), judged: false }
  const messages = [
    {
      role: 'user',
      content: [{ type: 'text', text: judgeUser({ serve, reply, recent, criteria }) }],
    },
  ]
  let timer
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => resolve(null), Math.max(1, Number(timeoutMs) || JUDGE_TIMEOUT_MS))
  })
  const run = (async () => {
    let out = ''
    for await (const chunk of stream({
      provider,
      model,
      system: judgeSystem(mode),
      messages,
      ...(sessionId ? { sessionId } : {}),
      // 会先思考的模型（deepseek-flash）思考也吃输出额度：200 时长回复只够思考、判词写不出来，退回规则。
      maxTokens: 1200,
      temperature: 0,
    })) {
      if (!chunk) continue
      if (chunk.type === 'text-delta') out += chunk.text
      if (chunk.type === 'finish' && chunk.reason && chunk.reason.kind === 'error') {
        console.error('[companion] judge fallback: model error ' + String(chunk.reason.error?.message || '').slice(0, 200))
        return null
      }
    }
    return out
  })().catch((e) => {
    console.error('[companion] judge fallback: ' + String(e?.message || e).slice(0, 200))
    return null
  })
  let raw = null
  try {
    raw = await Promise.race([run, timeout])
  } finally {
    clearTimeout(timer)
  }
  const parsed = raw ? parseJudge(raw) : null
  // 退回规则时留一行原因：真机上没法单步调，只能看日志。
  if (!parsed) console.error('[companion] judge fallback: ' + (raw === null ? 'timeout/error' : 'unparsable ' + JSON.stringify(String(raw).slice(0, 160))))
  if (!parsed) return { ...ruleVerdict(reply), judged: false }
  return { ...parsed, judged: true }
}
