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
      l2_fallback_offline: true,
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
      l2_fallback_offline: true,
    }
  }

  if (parsed.pass) {
    return { ...parsed, failures: 0, anchorHint: '', judgedBy: 'l2-model', l2_fallback_offline: false }
  }
  const nextFailures = Number(failures) + 1
  return {
    ...parsed,
    failures: nextFailures,
    anchorHint: failureAnchorHint(nextFailures, { sourceText: sourceContext, anchors }),
    judgedBy: 'l2-model',
    l2_fallback_offline: false,
  }
}

// ---------------------------------------------------------------- V3 羽毛球六拍认知引擎 (Dual-Track + Foul Detection + Scaffold)

export const STROKE_ORDER = Object.freeze([
  'stroke_1_recall',
  'stroke_2_condense',
  'stroke_3_question',
  'stroke_4_predict',
  'stroke_5_associate',
  'stroke_6_systematize',
])

export const FOUL_CODES = Object.freeze([
  'foul:copied_verbatim',
  'foul:abstract_impression',
  'foul:vague_modifier',
  'foul:unfalsifiable_question',
  'foul:tautology_bet',
  'foul:out_of_bounds_daydream',
  'foul:weak_link_relation',
])

export const STROKES = Object.freeze({
  stroke_1_recall: {
    id: 'stroke_1_recall',
    name: '第一拍·闭卷复述',
    dice: 1,
    minChars: 10,
    ask: '脱离原文，用自己的话复述刚看到的核心事实与条件变化。',
    schema: { requiredFields: ['observation'], closedBook: true },
  },
  stroke_2_condense: {
    id: 'stroke_2_condense',
    name: '第二拍·极简浓缩',
    dice: 1,
    minChars: 8,
    ask: '去掉形容词与猜测，一句话钉死主干命题与关键数字。',
    schema: { requiredFields: ['coreThesis'], forbidVague: true },
  },
  stroke_3_question: {
    id: 'stroke_3_question',
    name: '第三拍·追问反转',
    dice: 4,
    minChars: 10,
    ask: '提出一个可证伪的反转问题：若哪个前置条件不成立，结论立刻推翻？',
    schema: { requiredFields: ['unknownVar', 'falsifyCondition'], requireQuestion: true },
  },
  stroke_4_predict: {
    id: 'stroke_4_predict',
    name: '第四拍·两段式预判推演',
    dice: 2,
    minChars: 10,
    ask: 'Phase A 先押注可观测结果（不含同义反复），Phase B 再对照实际结果归因。',
    phases: ['A', 'B'],
    schema: { requiredFields: ['hypothesis', 'observableOutcome'], twoPhase: true },
  },
  stroke_5_associate: {
    id: 'stroke_5_associate',
    name: '第五拍·边界受限联想',
    dice: 3,
    minChars: 12,
    ask: '只准在同构机制或历史已验证案例里类比，说清共同因果结构与边界差异。',
    schema: { requiredFields: ['targetConcept', 'relationMechanism'], bounded: true },
  },
  stroke_6_systematize: {
    id: 'stroke_6_systematize',
    name: '第六拍·入网立规联结',
    dice: 5,
    minChars: 12,
    ask: '将本轮结论收敛为可执行规则，并双向联结 ≤3 篇已有笔记 [[WikiLink]]。',
    schema: { requiredFields: ['ruleOrPrinciple', 'linkedNotes'], maxCandidates: 3, bidirectional: true },
  },
})

const ABSTRACT_IMPRESSION_RE = /(?:很有道理|非常有道理|收获很大|很有启发|太厉害了|非常震撼|感觉很棒|写得真好|挺有意思|学到了不少)/
const VAGUE_MODIFIER_RE = /(?:大概|也许|可能|差不多|某种程度|基本|比较|一般般|还行|稍微|有点)/g
const FALSIFY_MARKER_RE = /(?:如果|若|一旦|假设|什么条件|怎么证明|能否证伪|哪一步|反例|推翻|否则|还是|究竟|为何)/
const TAUTOLOGY_BET_RE = /(?:做了就会变好|只要做了就能完成|不拖延就能成功|赢了就赢|有效果就是有效|认真学就能学会|努力就会成功|没问题就是正常)/
const DAYDREAM_RE = /(?:财务自由|一夜暴富|彻底逆袭|外星人|穿越|直接无敌|全宇宙|明年上市|躺着赚钱)/
const WEAK_LINK_RE = /(?:感觉有点像|都有道理|莫名觉得像|顺口一提|大概是一回事|说不上来但很像)/
const MECHANISM_LINK_RE = /(?:因为|机制|结构|因果|差异|反比|正比|对应|条件|约束|前提|导致)/

function maxVerbatimOverlapChars(source, answer) {
  const s = String(source || '').replace(/\s+/g, '')
  const a = String(answer || '').replace(/\s+/g, '')
  if (s.length < 12 || a.length < 12) return 0
  let longest = 0
  for (let i = 0; i <= a.length - 12; i++) {
    const sub = a.slice(i, i + 12)
    if (s.includes(sub)) {
      let len = 12
      while (i + len < a.length && s.includes(a.slice(i, i + len + 1))) len++
      if (len > longest) longest = len
    }
  }
  return longest
}

export function detectStrokeFoul({
  stroke = 'stroke_1_recall',
  phase = 'A',
  answer = '',
  sourceContext = '',
  linkedNotes = [],
} = {}) {
  const text = String(answer || '').trim()
  const s = String(stroke || 'stroke_1_recall')

  if (s === 'stroke_1_recall' && sourceContext) {
    const aNorm = text.replace(/\s+/g, '')
    const overlap = maxVerbatimOverlapChars(sourceContext, text)
    if (overlap >= 18 || (aNorm.length >= 12 && overlap >= Math.floor(aNorm.length * 0.78))) {
      return { foulCode: 'foul:copied_verbatim', directive: '犯规（照抄原文）：请闭卷用自己的语言重构核心事实，勿原句搬运。' }
    }
  }

  const anchorsNow = concreteNouns(text)
  const hasDigit = /\d/.test(text)
  const hasMechanismTerm = /(?:对比|因为|由于|实验|小时|分钟|%|题目|规则|公式|条件|机制|样本|因果|变量|模型|数据)/.test(text)
  if (ABSTRACT_IMPRESSION_RE.test(text) && !hasDigit && !hasMechanismTerm) {
    return { foulCode: 'foul:abstract_impression', directive: '犯规（空洞感想）：禁止感叹或抒情评价，请给出具体对象与客观指标。' }
  }

  const vagueMatches = text.match(VAGUE_MODIFIER_RE) || []
  if ((s === 'stroke_2_condense' && vagueMatches.length >= 1 && !hasDigit) || vagueMatches.length >= 2) {
    return { foulCode: 'foul:vague_modifier', directive: '犯规（模糊修饰词）：去掉「大概/可能/差不多」，钉死具体数值或主干结论。' }
  }

  if (s === 'stroke_3_question') {
    if (!/[？?]/.test(text) || !FALSIFY_MARKER_RE.test(text)) {
      return { foulCode: 'foul:unfalsifiable_question', directive: '犯规（不可证伪提问）：必须提出具体反证条件或哪一步条件失效会推翻结论。' }
    }
  }

  if (s === 'stroke_4_predict') {
    if (DAYDREAM_RE.test(text)) {
      return { foulCode: 'foul:out_of_bounds_daydream', directive: '犯规（越界空想）：预判必须限定在当前局/本任务可观测结果内。' }
    }
    if (TAUTOLOGY_BET_RE.test(text) || (String(phase).toUpperCase() === 'A' && countChars(text) < 12 && !hasDigit)) {
      return { foulCode: 'foul:tautology_bet', directive: '犯规（同义反复押注）：Phase A 押注必须给出可证伪的时间/数量/错误边界。' }
    }
  }

  if (s === 'stroke_5_associate') {
    if (DAYDREAM_RE.test(text)) {
      return { foulCode: 'foul:out_of_bounds_daydream', directive: '犯规（越界空想）：联想必须收束在与当前任务同构的真实机制内。' }
    }
    if (WEAK_LINK_RE.test(text) || !MECHANISM_LINK_RE.test(text)) {
      return { foulCode: 'foul:weak_link_relation', directive: '犯规（弱联系牵强类比）：必须写明共同因果机制或关键边界差异。' }
    }
  }

  if (s === 'stroke_6_systematize') {
    const hasLinkText = /\[\[[^\]]+\]\]/.test(text)
    if (!hasLinkText && (!Array.isArray(linkedNotes) || linkedNotes.length === 0)) {
      return { foulCode: 'foul:weak_link_relation', directive: '犯规（未形成知识联结）：第 6 拍须明确关联已有笔记 [[WikiLink]]。' }
    }
  }

  return { foulCode: '', directive: '' }
}

export function buildStrokePlan({
  track = 'multi_shuttle_drill',
  strokes = null,
  topic = '',
  anchors = [],
  candidateNotes = [],
} = {}) {
  const normalizedTrack = track === 'department_match' ? 'department_match' : 'multi_shuttle_drill'
  const selectedIds = Array.isArray(strokes) && strokes.length
    ? strokes.filter((id) => Boolean(STROKES[id]))
    : normalizedTrack === 'department_match'
      ? [...STROKE_ORDER]
      : ['stroke_1_recall', 'stroke_2_condense', 'stroke_4_predict']
  const cleanTopic = String(topic || '').trim().slice(0, 40)
  const hint = Array.isArray(anchors) && anchors.length ? `（锚点：${anchors.slice(0, 2).join(' / ')}）` : ''
  const slots = selectedIds.map((id, idx) => {
    const meta = STROKES[id]
    return {
      order: idx + 1,
      stroke: meta.id,
      slot: meta.id,
      dice: meta.dice,
      name: meta.name,
      track: normalizedTrack,
      schema: meta.schema,
      prompt: (cleanTopic ? `围绕「${cleanTopic}」：` : '') + meta.ask + hint,
      ...(meta.phases ? { phases: meta.phases } : {}),
      ...(id === 'stroke_6_systematize' && Array.isArray(candidateNotes)
        ? { candidateNotes: candidateNotes.slice(0, 3) }
        : {}),
    }
  })
  return { track: normalizedTrack, slots }
}

/**
 * V3 羽毛球六拍闭环质检：
 * 1) 先跑 L1 确定性犯规检查（照抄/空洞/模糊/不可证伪/同义反复/越界/弱联结）；
 * 2) retries === 2（或 >=2）时注入 2-3 个具体观察锚点 scaffold_hints；
 * 3) 离线或 >8s 超时走 l2_fallback_offline=true 降级放行。
 */
export async function evaluateStroke({
  stroke = 'stroke_1_recall',
  phase = 'A',
  track = 'multi_shuttle_drill',
  prompt = '',
  answer = '',
  sourceContext = '',
  anchors = [],
  retries = 0,
  linkedNotes = [],
  stream = null,
  provider = '',
  model = '',
  timeoutMs = 8_000,
} = {}) {
  const meta = STROKES[stroke] || STROKES.stroke_1_recall
  const normalizedRetries = Math.max(0, Math.round(Number(retries) || 0))
  const scaffoldHints = normalizedRetries === 2
    ? extractObservationAnchors(sourceContext, anchors, 3)
    : []

  const foul = detectStrokeFoul({ stroke: meta.id, phase, answer, sourceContext, linkedNotes })
  if (foul.foulCode) {
    return {
      pass: false,
      code: foul.foulCode,
      foulCode: foul.foulCode,
      stroke: meta.id,
      phase,
      track,
      directive: foul.directive,
      retries: normalizedRetries + 1,
      scaffold_hints: normalizedRetries === 2
        ? (scaffoldHints.length >= 2 ? scaffoldHints : extractObservationAnchors(sourceContext, ['核心对象', '约束条件', '变化量'], 3))
        : [],
      judgedBy: 'code-foul',
      l2_fallback_offline: false,
    }
  }

  const base = await evaluateSixDiceSlot({
    dice: meta.dice,
    prompt: prompt || meta.ask,
    answer,
    sourceContext,
    anchors,
    failures: normalizedRetries,
    stream,
    provider,
    model,
    timeoutMs,
  })

  if (!base.pass) {
    return {
      ...base,
      stroke: meta.id,
      phase,
      track,
      foulCode: '',
      retries: normalizedRetries + 1,
      scaffold_hints: normalizedRetries === 2
        ? (scaffoldHints.length >= 2 ? scaffoldHints : extractObservationAnchors(sourceContext, ['核心对象', '约束条件', '变化量'], 3))
        : [],
    }
  }

  return {
    ...base,
    stroke: meta.id,
    phase,
    track,
    foulCode: '',
    retries: 0,
    scaffold_hints: [],
  }
}
