// dsh-go-companion · FWK-003 六骰子分级思维引擎 + L2 无状态语义判官（M2）
//
// 对齐 aozhenyu666-cmyk/mouhua-thinking 的 frameworks/thinking/FWK-003-six-dice.md：
//   6 骰动作：
//     1. 精简总结 / 事实提取（现在真正知道什么？）
//     2. 预判推演（如果继续发展，下一步最可能发生什么？）
//     3. 联想 / 比较（有没有类似情况或可比较对象？）
//     4. 提问（哪个未知最可能改变结论？）
//     5. 依据与结论（当前判断依据是什么，能推出什么？）
//     6. 替代解释（如果第一解释错了，还有什么解释？）
//
// 分阶可控释放（禁止对日常问题机械走完 6 步，禁止 AI 替用户抢答）：
//   Stage 1 (入门防走神)：2–3 槽位，主攻 骰6(替代解释) 与 骰1(事实提取)
//   Stage 2 (主线防偏航)：骰2(预判推演) 与 骰4(关键提问)
//   Stage 3 (高阶防自欺)：骰3(联想/比较) 与 骰5(依据与结论)
//
// L2 判定使用无状态独立判官调用，只输出严格 JSON：{"pass","code","slot","directive"}；
// 同一槽位连续 2 次不通过时，只从原文提取 2–3 个观察锚点作提示（不剧透答案）。

import { concreteNouns, countChars, fillerRatio } from './capabilities.js'

export const SIX_DICE = Object.freeze({
  1: { id: 'dice_1', name: '事实提取', ask: '不看原话，用一句说清刚才这条内容到底交待了哪一个事实？', minChars: 8 },
  2: { id: 'dice_2', name: '预判推演', ask: '顺着当前这一步往下走，接下来最可能发生什么结果或坑？', minChars: 10 },
  3: { id: 'dice_3', name: '联想比较', ask: '这跟你做过的哪一类旧题/旧案例最像？关键差别在哪？', minChars: 10 },
  4: { id: 'dice_4', name: '关键提问', ask: '整段里哪一个还没弄清的未知条件，一旦变了结论就会翻盘？', minChars: 8 },
  5: { id: 'dice_5', name: '依据与结论', ask: '你刚才下的判断依据了哪条硬事实？如果只靠它，能稳推出什么？', minChars: 10 },
  6: { id: 'dice_6', name: '替代解释', ask: '别急着认第一个结论：如果最顺嘴那个解释错了，还能怎么解释？', minChars: 10 },
})

export const STAGES = Object.freeze({
  1: { id: 1, name: 'Stage 1 · 入门防走神', dice: [1, 6], maxSlots: 3 },
  2: { id: 2, name: 'Stage 2 · 主线防偏航', dice: [2, 4], maxSlots: 2 },
  3: { id: 3, name: 'Stage 3 · 高阶防自欺', dice: [3, 5], maxSlots: 2 },
})

export const L2_CODES = Object.freeze([
  'l2:pass', 'l2:offtopic', 'l2:vague', 'l2:circular', 'l2:no-evidence', 'l2:too-short', 'l2:filler',
])

/**
 * 为当前阶段构造受控思考槽位（默认 1–3 个；light 模式只给 1 个，防止填满六栏）。
 * @returns {Array<{slot:string, dice:number, name:string, prompt:string, stage:number}>}
 */
export function slotsForStage({ stage = 1, light = false, topic = '', anchors = [] } = {}) {
  const s = STAGES[stage] || STAGES[1]
  const diceOrder = light ? [s.dice[0]] : stage === 1 ? [6, 1] : [...s.dice]
  const cleanTopic = String(topic || '').trim().slice(0, 40)
  const hint = Array.isArray(anchors) && anchors.length ? `（锚点参考：${anchors.slice(0, 2).join(' / ')}）` : ''
  return diceOrder.slice(0, s.maxSlots).map((d) => {
    const meta = SIX_DICE[d]
    const prefix = cleanTopic ? `围绕「${cleanTopic}」：` : ''
    return {
      slot: meta.id,
      dice: d,
      name: meta.name,
      stage: s.id,
      prompt: (prefix + meta.ask + hint).slice(0, 100),
    }
  })
}

/**
 * 从原文/视频片段提取 2–3 个具体观察锚点（第二次连续失败时给出，帮助观察而不剧透结论）。
 */
export function extractObservationAnchors(sourceText, customAnchors = [], limit = 3) {
  const cap = Math.max(2, Math.min(3, Number(limit) || 3))
  const explicit = (Array.isArray(customAnchors) ? customAnchors : [])
    .map((x) => String(x || '').trim().slice(0, 24))
    .filter(Boolean)
  if (explicit.length >= 2) return explicit.slice(0, cap)
  const fromSource = concreteNouns(String(sourceText || ''))
    .filter((w) => w.length >= 2 && w.length <= 12)
  const merged = [...new Set([...explicit, ...fromSource])]
  return merged.slice(0, cap)
}

/** 第二次连续未过关时生成的锚点提示（严格不给最终答案）。 */
export function failureAnchorHint(failures, { sourceText = '', anchors = [] } = {}) {
  if (Number(failures) < 2) return ''
  const picked = extractObservationAnchors(sourceText, anchors, 3)
  if (!picked.length) return '先别猜结论，退回去盯紧原文里的具体数字、主体和条件变化再说一遍。'
  return `连续两次没踩到点上（先不给答案）。重点盯住这 ${picked.length} 个观察锚点再重答：${picked.map((a) => `「${a}」`).join('、')}。`
}

export function l2JudgeSystem() {
  return [
    '你是无状态思维质检判官，只判定用户这一拍是否完成了指定思维动作（不闲聊、不替他答题、不给答案）。',
    '只输出一行合法 JSON，格式：{"pass":true|false,"code":"l2:pass|l2:offtopic|l2:vague|l2:circular|l2:no-evidence","slot":"dice_X","directive":"一句≤35字纠偏指令"}。',
    '规则：套话、同义反复（circular）、完全跑题（offtopic）、只有感受没有依据（no-evidence/vague）必须判 false。',
  ].join('\n')
}

export function l2JudgeUser({ slot = 'dice_1', prompt = '', answer = '', sourceContext = '' } = {}) {
  const lines = [`槽位：${slot}`, `题目：${String(prompt || '').slice(0, 160)}`]
  if (sourceContext) lines.push(`材料摘要（判据参照）：${String(sourceContext).slice(0, 400)}`)
  lines.push(`用户回答：${String(answer || '').slice(0, 600)}`)
  lines.push('请输出严格 JSON：')
  return lines.join('\n')
}

/** 解析 L2 模型输出的 JSON；兼容代码块与脏文本。 */
export function parseL2Judge(raw, fallbackSlot = 'dice_1') {
  const text = String(raw == null ? '' : raw)
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  let obj = null
  try {
    obj = JSON.parse(text.slice(start, end + 1))
  } catch {
    const passHit = /"pass"\s*:\s*(true|false)/i.exec(text)
    const codeHit = /"code"\s*:\s*"([^"]+)"/.exec(text)
    const dirHit = /"directive"\s*:\s*"([^"]*)"/.exec(text)
    if (!passHit) return null
    obj = {
      pass: passHit[1].toLowerCase() === 'true',
      code: codeHit ? codeHit[1] : undefined,
      directive: dirHit ? dirHit[1] : '',
    }
  }
  if (!obj || typeof obj !== 'object' || typeof obj.pass !== 'boolean') return null
  const pass = Boolean(obj.pass)
  const codeRaw = String(obj.code || (pass ? 'l2:pass' : 'l2:vague')).trim()
  const code = L2_CODES.includes(codeRaw) ? codeRaw : pass ? 'l2:pass' : 'l2:vague'
  return {
    pass,
    code: pass ? 'l2:pass' : code,
    slot: String(obj.slot || fallbackSlot).slice(0, 24),
    directive: String(obj.directive || (pass ? '通过' : '补出具体依据再答一次')).slice(0, 60),
  }
}

/**
 * L1 代码硬拦截 + L2 无状态语义判定（连续第 2 次失败自动附带 2–3 个观察锚点）。
 */
export async function evaluateSixDiceSlot({
  dice = 1,
  prompt = '',
  answer = '',
  sourceContext = '',
  anchors = [],
  failures = 0,
  stream = null,
  provider = '',
  model = '',
  timeoutMs = 8_000,
} = {}) {
  const meta = SIX_DICE[dice] || SIX_DICE[1]
  const text = String(answer || '').trim()
  const failCountAfter = (failedCode, directive) => {
    const nextFailures = Number(failures) + 1
    const anchorHint = failureAnchorHint(nextFailures, { sourceText: sourceContext, anchors })
    return {
      pass: false,
      code: failedCode,
      slot: meta.id,
      directive,
      failures: nextFailures,
      anchorHint,
      judgedBy: 'code',
    }
  }

  // L1 代码检查：短于底线、高填充词、完全无具体名词直接打回，不浪费判官额度。
  if (countChars(text) < meta.minChars) {
    return failCountAfter('l2:too-short', `太短（至少 ${meta.minChars} 字），把「${meta.name}」说具体。`)
  }
  if (fillerRatio(text) >= 0.5) {
    return failCountAfter('l2:filler', '口水填充词太多，直接说核心判断。')
  }
  if (!concreteNouns(text).length) {
    return failCountAfter('l2:vague', '缺少具体对象或事实名词，指明具体是哪件事。')
  }

  if (typeof stream !== 'function' || !provider || !model) {
    return {
      pass: true,
      code: 'l2:pass',
      slot: meta.id,
      directive: '通过（L1规则已过，离线降级放行）',
      failures: 0,
      anchorHint: '',
      judgedBy: 'code-fallback',
    }
  }

  let timer
  const timeout = new Promise((resolve) => {
    timer = setTimeout(() => resolve(null), Math.max(100, Number(timeoutMs) || 8_000))
  })
  const call = (async () => {
    let out = ''
    for await (const chunk of stream({
      provider,
      model,
      system: l2JudgeSystem(),
      messages: [{ role: 'user', content: [{ type: 'text', text: l2JudgeUser({ slot: meta.id, prompt: prompt || meta.ask, answer: text, sourceContext }) }] }],
      maxTokens: 250,
      temperature: 0,
    })) {
      if (chunk?.type === 'text-delta') out += chunk.text
      if (chunk?.type === 'finish' && chunk.reason?.kind === 'error') return null
    }
    return out
  })().catch(() => null)

  let raw = null
  try {
    raw = await Promise.race([call, timeout])
  } finally {
    clearTimeout(timer)
  }

  const parsed = raw ? parseL2Judge(raw, meta.id) : null
  if (!parsed) {
    return {
      pass: true,
      code: 'l2:pass',
      slot: meta.id,
      directive: '判官超时/不可解析，L1已过暂放行',
      failures: 0,
      anchorHint: '',
      judgedBy: 'timeout-fallback',
    }
  }

  if (parsed.pass) {
    return { ...parsed, failures: 0, anchorHint: '', judgedBy: 'l2-model' }
  }
  const nextFailures = Number(failures) + 1
  return {
    ...parsed,
    failures: nextFailures,
    anchorHint: failureAnchorHint(nextFailures, { sourceText: sourceContext, anchors }),
    judgedBy: 'l2-model',
  }
}
