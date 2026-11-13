// dsh-go-companion · 能力库（第八轮 J2 依赖 + J3 第一版）
//
// 引擎认得的全部积木，每个是一个具名能力。分六类：感知 / 度量 / 触发 / 应答 / 验收 / 变速。
//
// 这里只做三件事：
// 1. 登记名字与类别——协议里写了不认识的名字，加载时直接拒绝并生成「缺能力」提议。
// 2. 把「度量」这一类真正实现成纯函数（零模型）：字数、话题数、具体名词数、有无结论句、
//    问句数、应答延迟、填充词比例、与他近 5 条的重合率。
// 3. 把「墙的能力」单独列出来并禁止协议声明（锁、额度、信用分、规则、立场结算）。
//
// 其余五类（感知 / 触发 / 应答 / 验收 / 变速）在这一版只是登记：名字、类别、执行形态。
// 真正把它们接到原生展示层 / 语音层 / HUD / 墙上去执行的是第 2 批（J4 行为局、W1 局界面），
// 参数形状校验也到那时才有东西可校验。不在这里假装能执行——本机没有手机，硬说能跑是假证据。

export const CLASSES = ['感知', '度量', '触发', '应答', '验收', '变速']

/**
 * 墙的能力：协议一律不许声明。
 * 升级与锁由引擎按 P4 执行，协议只能调用「升级」这一个出口。
 */
export const WALL_CAPABILITIES = [
  '锁', '全机锁', '应用锁', '额度', '信用分', '规则', '立场结算', '扣分', '减额度',
]

const STOPWORDS = new Set(['然后', '就是', '那个', '这个', '因为', '所以', '但是', '而且', '还有', '一个', '我们', '他们'])
const ABSTRACT = new Set(['问题', '事情', '东西', '情况', '方面', '过程', '方法', '意义', '价值', '影响', '结果', '状态'])
const CONCLUSION_WORDS = ['所以', '因此', '总之', '结论', '可见', '这说明', '总结']
const FILLERS = ['嗯', '啊', '呃', '那个', '就是', '然后', '这个']
/**
 * 模糊词：说了等于没说。
 * 用一条「长的排前面」的交替正则一次扫完：这样「大概就是」算 1 次，
 * 不会被拆成「大概」再算一次——同一处含糊不重复罚。
 */
const VAGUE_WORDS = ['那个东西', '某种程度上', '大概就是', '大概', '差不多', '基本上', '之类的', '什么的', '反正', '怎么说']
/** 显式因果/对比连接词。只认写出来的，认不出「隐含的因果」——那是模型的事，不是数出来的。 */
const CAUSAL_WORDS = ['因为', '所以', '因此', '由于', '导致', '前提是', '漏洞在于', '漏洞是', '否则', '但是', '相比', '对比', '反过来']

/** 去掉空白后的字符数。 */
export function countChars(text) {
  return String(text || '').replace(/\s+/g, '').length
}

/** 按标点切成小句，返回非空小句数。 */
export function splitClauses(text) {
  return String(text || '')
    .split(/[。！？；;!?\n]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}

export function countTopics(text) {
  return splitClauses(text).length
}

/**
 * 具体名词的粗判：先把中文串按连接词切开，再去掉停用词、抽象词，2 字以上去重。
 * 「哈希表和红黑树」要能数出 2 个，所以不能只按整段中文切。
 * 保序：混乱提示要按他说的先后列出 A、B、C。
 */
export function concreteNouns(text) {
  const seen = new Set()
  const runs = String(text || '').match(/[\u4e00-\u9fa5]{2,}/g) || []
  for (const run of runs) {
    for (const token of run.split(/[和与及跟、]|还有|以及/)) {
      if (token.length < 2) continue
      if (STOPWORDS.has(token) || ABSTRACT.has(token)) continue
      seen.add(token)
    }
  }
  return [...seen]
}

export function countConcreteNouns(text) {
  return concreteNouns(text).length
}

/** 最后一句里有没有结论性的词。 */
export function hasConclusion(text) {
  const clauses = splitClauses(text)
  const last = clauses.length ? clauses[clauses.length - 1] : String(text || '')
  return CONCLUSION_WORDS.some((w) => last.includes(w))
}

export function countQuestions(text) {
  return (String(text || '').match(/[？?]/g) || []).length
}

/** 应答延迟（毫秒）；负数按 0 算。 */
export function answerDelay(input = {}) {
  const asked = Number(input.askedAt) || 0
  const answered = Number(input.answeredAt) || 0
  return Math.max(0, answered - asked)
}

/** 填充词占非空白字符的比例（0~1）。 */
export function fillerRatio(text) {
  const raw = String(text || '')
  const total = countChars(raw)
  if (!total) return 0
  let hits = 0
  for (const f of FILLERS) hits += raw.split(f).length - 1
  return hits / total
}

/**
 * words 里任意一个词出现了几次。长词优先，互不吃掉——
 * 「大概就是」只算 1 次，不会同时算上「大概」。
 */
function countAny(text, words) {
  const raw = String(text || '')
  if (!raw || !words.length) return 0
  const pattern = [...words].sort((a, b) => b.length - a.length).join('|')
  return (raw.match(new RegExp(pattern, 'g')) || []).length
}

/** 模糊词出现次数。0 才是「说清楚了」。 */
export function countVague(text) {
  return countAny(text, VAGUE_WORDS)
}

/** 显式因果/对比连接词出现次数。看不出隐含因果，只认写出来的词。 */
export function countCausal(text) {
  return countAny(text, CAUSAL_WORDS)
}

function bigrams(text) {
  const clean = String(text || '').replace(/[\s\p{P}]/gu, '')
  const out = new Set()
  for (let i = 0; i < clean.length - 1; i += 1) out.add(clean.slice(i, i + 2))
  return out
}

/** 与最近几条的最大重合率（0~1）：字符二元组 Jaccard。 */
export function overlapWithRecent(text, recent = []) {
  const mine = bigrams(text)
  if (!mine.size) return 0
  let best = 0
  for (const other of (Array.isArray(recent) ? recent : []).slice(0, 5)) {
    const theirs = bigrams(other)
    if (!theirs.size) continue
    let inter = 0
    for (const g of mine) if (theirs.has(g)) inter += 1
    const union = mine.size + theirs.size - inter
    if (union > 0) best = Math.max(best, inter / union)
  }
  return best
}

/** 度量类能力的实现表。键就是协议里能写的条件名。 */
const METRICS = {
  字数: (input) => countChars(input.text),
  话题数: (input) => countTopics(input.text),
  具体名词数: (input) => countConcreteNouns(input.text),
  有无结论句: (input) => hasConclusion(input.text),
  问句数: (input) => countQuestions(input.text),
  应答延迟: (input) => answerDelay(input),
  填充词比例: (input) => fillerRatio(input.text),
  与他近5条的重合率: (input) => overlapWithRecent(input.text, input.recent),
  模糊词数: (input) => countVague(input.text),
  因果连接词数: (input) => countCausal(input.text),
}

/** 每一项：{ 类, run? }。run 只有度量类有——它是纯函数，零模型。 */
export const CAPABILITIES = new Map()

function reg(name, className, extra = {}) {
  CAPABILITIES.set(name, { name, class: className, ...extra })
}

// 感知（13）：计时是施工书 §3 J2 示例里写的名字，别名照收——协议作者照文档抄不该失败。
for (const name of ['计时', '局计时', '步骤计时', '无局计时', '前台应用', '本局内各应用时长', '切屏次数', '今日累计',
  '静止', '走路', '骑车', '亮屏', '充电']) {
  reg(name, '感知', { kind: 'sense' })
}

// 度量（8）：纯函数，零模型
for (const [name, run] of Object.entries(METRICS)) {
  reg(name, '度量', { kind: 'metric', run })
}

// 触发（11）：语音问是默认通道
for (const name of ['语音问', '浮层', '盖屏', '通知', '震动', '锁屏问题', '二选一', '倒计时条', '留空填词',
  '挖词回想', '先答后看']) {
  reg(name, '触发', { kind: 'trigger', default: name === '语音问' })
}

// 应答（7）：语音答是默认通道。拍照是施工书示例里写的名字，与手写拍照并列收下。
for (const name of ['语音答', '文字', '手写拍照', '拍照', '文件', '外部提交', 'Gemini Live 复盘']) {
  reg(name, '应答', { kind: 'answer', default: name === '语音答' })
}

// 验收（8）
for (const name of ['度量判定', '照片时间', '识图提取', '文件增长', '答案比对', '原文比对', '记录比对', '模型判定']) {
  reg(name, '验收', { kind: 'verify' })
}

// 变速（5）：降速三项、增速两项
for (const name of ['切手写', '缩短步骤', '单步限时加倍', '拉起 Gemini Live', '步骤间隔减半']) {
  reg(name, '变速', { kind: 'pace' })
}

export function capabilityNames() {
  return [...CAPABILITIES.keys()]
}

export function capabilityOf(name) {
  return CAPABILITIES.get(String(name)) || null
}

export function hasCapability(name) {
  return CAPABILITIES.has(String(name))
}

export function isWallCapability(name) {
  return WALL_CAPABILITIES.includes(String(name))
}

/**
 * 度量能力求值。不认识的名字返回 null（调用方按加载期错误处理）。
 */
export function measure(name, input = {}) {
  const cap = CAPABILITIES.get(String(name))
  if (!cap || typeof cap.run !== 'function') return null
  try {
    return cap.run(input)
  } catch {
    return null
  }
}

/**
 * 缺能力提议：协议用了库里没有的能力时产出的文档，交给执行 AI 去实现。
 * 同一次加载里同一个名字只提议一次。
 */
export function missingCapabilityProposal(names, protocolId) {
  const list = [...new Set((Array.isArray(names) ? names : []).map(String))]
  if (!list.length) return null
  return {
    kind: 'missing-capability',
    protocolId: String(protocolId || ''),
    at: new Date(0).toISOString(),
    capabilities: list.map((name) => ({
      name,
      class: null,
      why: '协议要求这块能力，能力库里没有',
      need: ['登记进 lib/capabilities.js', '写执行侧（原生 / 语音 / HUD / 墙）', '补至少 3 条测试'],
    })),
    proposal: '为 ' + list.join('、') + ' 补能力实现，然后把协议重新加载一次。',
  }
}
