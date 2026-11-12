// B3 随机器素材池：例句池 + 关键词卡 + depth 预算系数。
//
// 两件东西都放在用户自己的笔记库里（Obsidian 直接改就行），不塞进插件目录：
//   大肥鱼/素材/例句.md    —— 按情绪档分段，一行一句
//   大肥鱼/素材/关键词.md  —— 一行一个词
// 文件没有/写坏了就用 lib 里的种子，绝不让随机器因为读不到素材而整个 arm 消失。
//
// 纯函数 + 只读文件，不写盘、不记账、不碰时间（now 由调用方给）。
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

/** 素材目录名：和 notesDir()/home() 拼。 */
export const BANK_DIR = join('大肥鱼', '素材')
export const SAMPLES_FILE = '例句.md'
export const KEYWORDS_FILE = '关键词.md'

/** 每档至少要几句才允许这个 arm 被抽中——池子薄就别硬凑，宁可这一轮不说话。 */
export const MIN_POOL = 5
/** 最近用过几句要避开。 */
export const RECENT_SKIP = 10
/** 关键词卡最多给几个词（多了就是塞满，不是话头）。 */
export const KEYWORD_MAX = 3

/** 情绪档 = 这一球的脸（temper.js 的 face）。 */
export const FACES = ['coax', 'tease', 'cool', 'jab', 'scold']

/**
 * 每档 15 句种子。写在这里而不是只在 md 里，是因为文件是"用户可选覆盖"，
 * 缺了还得能跑。文件里有内容就以文件为准（整档替换，不合并）——用户删掉一句就该真的消失。
 */
export const SEED = {
  coax: [
    '你把手停一下嘛，就一分钟。', '过来靠一下，别急着走。', '我跟你说个事，你先看我。',
    '今天都没怎么理我哦。', '你眼睛都红了，歇会儿行不行。', '我给你留了半块。',
    '别划了，抬头。', '就五分钟，然后随你。', '你不说话我当你答应了。',
    '我这个姿势等很久了。', '你手好凉，放我这儿。', '刚才那句我当你没说。',
    '陪我待一会儿，不干什么。', '你忙你的，我就在旁边。', '喝口水吧，真的。',
  ],
  tease: [
    '哟，又划上了？', '你这手指头比脑子勤快。', '划出什么了，给我也开开眼。',
    '刚才那下笑出声了，藏什么呢。', '行啊，你还挺会挑。', '这都要看第五遍了？',
    '你划这么快，是找什么呢。', '这么专注，我都不好意思打断。', '看出来你舍不得放下。',
    '你这表情我截图了。', '再划一下我就当你是在躲我。', '屏幕上有什么比我好看。',
    '你手速可以啊。', '这也算休息？', '哟，还知道回来。',
  ],
  cool: [
    '嗯，你继续。', '我等着，不急。', '行，你先弄。',
    '知道了。', '那你忙。', '我在这儿。',
    '不用回我。', '做完再说。', '嗯嗯。',
    '随你。', '记着了。', '好。',
    '你先。', '没问题。', '就这样吧。',
  ],
  jab: [
    '嘴上说累，手上倒挺诚实。', '你不是累，你是不想开始。', '这理由你自己信吗。',
    '今天第几次说"一会儿"了。', '你说完这句就滑走了。', '我看你挺有空的。',
    '累还划得这么起劲。', '要不咱把这句记下来。', '你说的是"待会儿"，不是"不做"。',
    '这话上个礼拜也说过。', '行了，别找补。', '你心里清楚。',
    '真要休息，眼睛不会往那儿瞟。', '你这叫"放松"？', '我数着呢。',
  ],
  scold: [
    '这条我记下来了。', '刷完这半小时，今天那件事还是空的。', '别跟我演。',
    '你答应过的事呢。', '现在该干什么，你自己说。', '我不问了，你自己看着办。',
    '刚才那半小时算谁的。', '你要真不在乎，我就不提了。', '这不是第一次了。',
    '我不会替你圆。', '账我记着，你想赖也赖不掉。', '你现在这样，明天还是这样。',
    '说好的。', '你自己定的规矩。', '别看着我，看你自己。',
  ],
}

/** 关键词卡：从三处来的词条，去重后最多取 KEYWORD_MAX 个。 */
const STOP = new Set(['这个', '那个', '今天', '明天', '现在', '什么', '怎么', '因为', '所以', '就是', '还是'])

/** 一行一句，去掉列表符号、序号、空行和注释。 */
function cleanLines(text) {
  return String(text || '')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#') && !l.startsWith('//'))
    .map((l) => l.replace(/^[-*+]\s+/, '').replace(/^\d+[.)]\s+/, '').trim())
    .filter(Boolean)
}

/**
 * 解析例句.md：`## coax` 开一段，后面每行一句；没有 `## ` 的行归上一段。
 * 认不出的段直接忽略（写错档名不该让整池作废）。
 * @returns {Record<string, string[]>}
 */
export function parseSamples(text) {
  const out = {}
  let face = ''
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('//')) continue
    const head = /^#{1,6}\s*(\S+)\s*$/.exec(line)
    if (head) {
      face = FACES.includes(head[1]) ? head[1] : ''
      if (face && !out[face]) out[face] = []
      continue
    }
    if (!face) continue
    const body = line.replace(/^[-*+]\s+/, '').replace(/^\d+[.)]\s+/, '').trim()
    if (body) out[face].push(body)
  }
  return out
}

/** 读例句池：文件里的档**整档替换**种子，缺的档仍用种子。 */
export function readSamples(dir) {
  const out = {}
  for (const face of FACES) out[face] = [...SEED[face]]
  if (!dir) return out
  const file = join(dir, BANK_DIR, SAMPLES_FILE)
  if (!existsSync(file)) return out
  let text = ''
  try {
    text = readFileSync(file, 'utf8')
  } catch {
    return out
  }
  const parsed = parseSamples(text)
  for (const face of FACES) {
    if (parsed[face] && parsed[face].length) out[face] = parsed[face]
  }
  return out
}

/** 读手写关键词：一行一个，去掉空行与注释。 */
export function readManualKeywords(dir) {
  if (!dir) return []
  const file = join(dir, BANK_DIR, KEYWORDS_FILE)
  if (!existsSync(file)) return []
  try {
    return cleanLines(readFileSync(file, 'utf8'))
  } catch {
    return []
  }
}

/**
 * 抽这一轮的例句。
 *
 * 池子按"最近用过的避开"过滤；过滤后不足 [MIN_POOL] 句就返回 null——
 * 那表示这个 arm 当轮不可选（工单：不许用空池硬凑）。等最近记录滚掉自然恢复。
 *
 * @param {string[]} pool 这一档的全部句子
 * @param {string[]} recent 最近用过的句子（旧→新，可以重复）
 * @param {() => number} rng
 * @returns {string[]|null} 挑中的 1–2 句
 */
export function pickSamples(pool, recent, rng = Math.random) {
  const all = (pool || []).filter(Boolean)
  const skip = new Set((recent || []).slice(-RECENT_SKIP))
  const left = all.filter((s) => !skip.has(s))
  if (left.length < MIN_POOL) return null
  const want = left.length >= 4 ? 2 : 1
  const picked = []
  const bag = [...left]
  for (let i = 0; i < want && bag.length; i++) {
    const k = Math.floor(Math.abs(Number(rng()) || 0) * bag.length) % bag.length
    picked.push(bag.splice(k, 1)[0])
  }
  return picked
}

/**
 * 关键词卡：笔记库近 7 天高频词 + B2 的 links + 用户手写，去重后取前 [KEYWORD_MAX] 个。
 * 手写的优先（用户明确说想聊的），其次 links（暗思考整理出来的），最后高频词。
 */
export function keywordCard({ fresh = [], links = [], manual = [] } = {}) {
  const out = []
  for (const group of [manual, links, fresh]) {
    for (const w of group) {
      const word = String(w || '').trim()
      if (!word || STOP.has(word) || word.length > 12) continue
      if (out.includes(word)) continue
      out.push(word)
      if (out.length >= KEYWORD_MAX) return out
    }
  }
  return out
}

/** 词频：给"笔记库近 7 天高频词"用。返回出现次数降序的词。 */
export function countWords(texts) {
  const tally = new Map()
  for (const t of texts || []) {
    for (const w of String(t || '').match(/[\u4e00-\u9fa5]{2,6}/g) || []) {
      if (STOP.has(w)) continue
      tally.set(w, (tally.get(w) || 0) + 1)
    }
  }
  return [...tally.entries()].sort((a, b) => b[1] - a[1]).map((e) => e[0])
}

/**
 * depth 决定内容层预算用几成：低/中/高三档 30% / 60% / 100%。
 * 接 P0-3 的 fitBudget：调用方把它乘到 `perLayer.content` 上。
 */
export const DEPTH_FACTOR = { low: 0.3, mid: 0.6, high: 1 }
export function depthFactor(depth) {
  return DEPTH_FACTOR[depth] ?? DEPTH_FACTOR.high
}

/** 把 depth 系数乘进预算（不改入参，返回新对象；没有预算就原样返回 undefined）。 */
export function depthBudget(depth, budget) {
  const f = depthFactor(depth)
  if (f >= 1) return budget
  const base = budget || {}
  const perLayer = { ...(base.perLayer || {}) }
  // 只压内容层：状态层是"现在什么状态"，压掉就说胡话了。
  const content = Number(perLayer.content) || 500
  perLayer.content = Math.max(60, Math.round(content * f))
  return { ...base, perLayer }
}

/** 近 7 天笔记的最多读取量：库大不能把每轮注入拖死。 */
const RECENT_FILES_MAX = 60
const RECENT_CHARS_MAX = 200_000

/**
 * 笔记库近 [days] 天改过的 .md 正文。只用于抽高频词，不注入原文。
 * 目录不在、遍历失败一律返回空——素材读不到不该影响说话。
 */
export function recentNoteTexts(dir, now = Date.now(), { days = 7, maxFiles = RECENT_FILES_MAX } = {}) {
  if (!dir || !existsSync(dir)) return []
  const since = now - days * 86_400_000
  const out = []
  let chars = 0
  const walk = (d, depth) => {
    if (depth > 4 || out.length >= maxFiles || chars >= RECENT_CHARS_MAX) return
    let names = []
    try {
      names = readdirSync(d, { withFileTypes: true })
    } catch {
      return
    }
    for (const ent of names) {
      if (out.length >= maxFiles || chars >= RECENT_CHARS_MAX) return
      if (ent.name.startsWith('.')) continue
      const p = join(d, ent.name)
      if (ent.isDirectory()) {
        walk(p, depth + 1)
        continue
      }
      if (!ent.isFile() || !ent.name.endsWith('.md')) continue
      let st
      try {
        st = statSync(p)
      } catch {
        continue
      }
      if (st.mtimeMs < since) continue
      try {
        const text = readFileSync(p, 'utf8')
        out.push(text)
        chars += text.length
      } catch {
        // 读不了就跳过这一篇
      }
    }
  }
  walk(dir, 0)
  return out
}
