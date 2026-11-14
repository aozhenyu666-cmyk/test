// dsh-go-companion · 随机器（内容层随机，约束层和判据层绝不随机）
//
// 三层分开看，随机和可预测就不矛盾：
//   内容层（这轮给她哪几张素材）      —— 随机，带防抖，见下
//   约束层（铁律 / 人设卡 / 议程 / 我.md）—— 类型级只读：根本不在 ARMS 里，进不了这里
//   判据层（什么算说中）              —— 在 predict.js，由墙的遥测算，模型无权写
//
// 结构借 SillyTavern 的 Inclusion Group（组内互斥抽 1）+ Sticky/Cooldown（按轮数防抖）；
// 挑谁不靠 Math.random，靠 brain.js 的 Thompson 抽样——它每轮都在学。
// 所以这文件只做三件事：分组抽样、防抖、落快照。

import { betaSample } from './brain.js'
// B3：只给了脸、没给句子时的兜底种子（正式路径由调用方现抽现给，见 samples.js）。
import { SEED } from './samples.js'

/**
 * 常驻只读：没有 probability 字段，不进 GROUPS，手滑改不动。
 * 这一层是全量注入的保底，随机器只能在它之上加一句强调，永远删不掉任何一条。
 */
export const READONLY_BLOCKS = Object.freeze(['铁律', '我.md', 'agendaLines', '判断核心', '人设卡'])

/** 7 个机制，各算一个独立 arm（multiple-play bandit：只给抽中的记账）。 */
export const ARMS = Object.freeze(['innerOS', 'temper', 'samples', 'keywordCard', 'prediction', 'ironSplit', 'depth'])

/** 组内恰好抽 1 个 → 每轮 2~3 个，同组天然互斥。 */
export const GROUPS = Object.freeze({
  G_MOOD: ['innerOS', 'temper'],
  G_STYLE: ['samples', 'keywordCard'],
  G_MIND: ['prediction', 'ironSplit', 'depth'],
})

/** 上轮抽中的继续连着（防抖：别一轮一个样）。 */
export const STICKY_TURNS = 2
/** 刚下去的歇几轮再回来（防抖的另一半）。 */
export const COOLDOWN_TURNS = 3

// ---------------------------------------------------------------- 种子

/** FNV-1a：够短，够稳，不需要 crypto。 */
function hashParts(parts) {
  const s = parts.join('|')
  let h = 0x811c9dc5
  for (let i = 0; i < s.length; i += 1) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h >>> 0
}

/** mulberry32：可注入、结果稳定。 */
export function seededRng(seed) {
  let a = Number(seed) >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** 账本一变，同一种子重放的结果就该变——这不是 bug，是它在学。快照里记下来即可。 */
export function betaVersionOf(arms) {
  const t = arms || {}
  return hashParts(ARMS.map((a) => a + ':' + (Number(t[a]?.win) || 0) + '/' + (Number(t[a]?.lose) || 0))).toString(36)
}

// ---------------------------------------------------------------- 抽样

function armCell(arms, id) {
  const c = arms?.[id]
  return { win: Number(c?.win) || 0, lose: Number(c?.lose) || 0 }
}

/** Thompson：每个候选抽一个 Beta 样本，取最大的。先验 1/1（没证据就是不知道）。 */
function pickByBeta(candidates, arms, rng) {
  let best = candidates[0]
  let bestScore = -1
  for (const id of candidates) {
    const c = armCell(arms, id)
    const s = betaSample(c.win + 1, c.lose + 1, rng)
    if (s > bestScore) {
      best = id
      bestScore = s
    }
  }
  return best
}

/**
 * 一轮的抽样。
 * @param {{date:string, turn:number, arms?:object, state?:{last?:object, streak?:object}, rng?:()=>number}} ctx
 * @returns {{picks:{group:string,arm:string,sampled:number,sticky:boolean}[], state:object, snapshot:object, rng:()=>number}}
 */
export function buildVibe({ date, turn, arms, state, rng } = {}) {
  const t = Math.max(0, Math.round(Number(turn) || 0))
  const last = { ...(state?.last || {}) }
  const streak = { ...(state?.streak || {}) }
  const betaVersion = betaVersionOf(arms)
  const seed = hashParts([String(date || ''), String(t), betaVersion])
  const rand = rng || seededRng(seed)
  const picks = []

  for (const [group, list] of Object.entries(GROUPS)) {
    // Sticky：上轮抽中的、还没连满，就继续它，不再掷一次。
    const stickyArm = list.find((id) => {
      const gap = t - Number(last[id] ?? -99)
      return gap === 1 && Number(streak[id] || 0) < STICKY_TURNS
    })
    // Cooldown：刚下去的歇几轮。全组都在歇就退到最久没抽过的那个——
    // 不能退回"全组随机"，否则 2 个 arm 的组会连着同一个跑三四轮（实测过）。
    const rested = list.filter((id) => t - Number(last[id] ?? -99) > COOLDOWN_TURNS)
    const pool = rested.length
      ? rested
      : list.filter((id) => Number(last[id] ?? -99) === Math.min(...list.map((x) => Number(last[x] ?? -99))))
    const arm = stickyArm || pickByBeta(pool, arms, rand)
    const c = armCell(arms, arm)
    picks.push({ group, arm, sampled: Math.round(betaSample(c.win + 1, c.lose + 1, rand) * 1000) / 1000, sticky: Boolean(stickyArm) })
    streak[arm] = stickyArm ? Number(streak[arm] || 0) + 1 : 1
    last[arm] = t
  }

  return {
    picks,
    state: { last, streak },
    // 记「这一轮到底注入了什么」，排查时不靠猜（§13.2：记录 > 复现）。
    snapshot: { seed, turn: t, picks, betaVersion, at: Date.now() },
    rng: rand,
  }
}

// ---------------------------------------------------------------- 素材池

/** 内心 OS：只写当下处境和情绪底色，不写"我接下来打算怎么回"（§13.6，防自我一致性锁死）。 */
const INNER_OS =
  '这一轮在回复最前面加一行内心 OS，用（）括起来。只写你怎么看这一刻、心里什么底色，' +
  '不要写你打算怎么回——嘴上说的和心里想的可以不一样，那点反差才是你。'

/**
 * 铁律全量照常注入，这里只是这一轮额外多看紧哪一条（§14.1 的 ironSplit）。
 * 任一情况下都不会少掉别的条目，所以不违反「约束层绝不随机」。
 */
const IRON_RULES = [
  '不编他没说过的话、不假装记得没发生的事。',
  '他说「别说了」就停，可以小声嘟囔一句再停。',
  '不替他做决定；要给意见就说「如果是我，我选 X，因为……」。',
  '正事（清单、分析、规划、写东西）归姐姐，你不替他分析。',
  '不知道就说不知道，查不到就说查不到。',
  '答应他的事要真做到；做不到的（比如替他数着打了几把）就别答应。',
]

// ---------------------------------------------------------------- 渲染

/**
 * 把抽中的 arm 渲染成几行。素材由调用方给（它已经有这些数据），池子在上面的 SEED 里。
 *
 * B3：例句和关键词改成**由调用方现抽现给**——`feed.samples` 是这一轮挑中的句子，
 * `feed.keywords` 是已经合并去重的关键词卡。空数组表示这一轮这个 arm 没素材，
 * 那就什么都不注入（不许用空池硬凑一句）。
 * @param {{arm:string}[]} picks
 * @param {{temperLine?:string, predictions?:string[], keywords?:string[],
 *          samples?:string[], carry?:string[], stateLines?:string[], face?:string, roll?:number}} feed
 */
export function renderVibe(picks, feed = {}) {
  const out = []
  for (const p of picks) {
    if (p.arm === 'innerOS') {
      // B2：夜间整理出来的「要带上的事」进这里。空就不加，不硬凑。
      const carry = (feed.carry || []).filter(Boolean)
      out.push(carry.length ? INNER_OS + '（你自己记着的：' + carry.join('；') + '）' : INNER_OS)
    }
    else if (p.arm === 'temper') {
      if (feed.temperLine) out.push(feed.temperLine)
    } else if (p.arm === 'samples') {
      // B3：优先用调用方现抽的句子（已去重、已避开最近用过的）。
      // 只给了 face 没给句子时退回内置种子取两句——这条兜底是既有行为，不能因为接了素材池就没。
      const picked = (feed.samples || []).filter(Boolean).length
        ? (feed.samples || []).filter(Boolean)
        : (SEED[feed.face] || []).slice(0, 2)
      if (picked.length) out.push('这一球的语气参考（学味道，不许照抄）：' + picked.join('　'))
    } else if (p.arm === 'keywordCard') {
      const words = (feed.keywords || []).filter(Boolean)
      if (words.length) out.push('话头合适的话，把他今天定的「' + words[0] + '」拎出来提一句，别硬塞。')
    } else if (p.arm === 'prediction') {
      const lines = feed.predictions || []
      if (lines.length) out.push(...lines)
    } else if (p.arm === 'ironSplit') {
      const rule = IRON_RULES[Math.floor((feed.roll ?? 0) * IRON_RULES.length) % IRON_RULES.length]
      out.push('下面这些照旧全部算数，只是这一轮额外看紧这一条：' + rule)
    } else if (p.arm === 'depth') {
      // §13.5：只把最短最状态化的一行搬到最后，全量留在系统提示尾部保底。
      const short = (feed.stateLines || [])
        .filter((l) => l && l.length <= 60)
        .sort((a, b) => a.length - b.length)[0]
      if (short) out.push('时刻记住这一句：' + short)
    }
  }
  return out
}

/** 被抽中的 arm 各记一次账（§13.1：多个 arm 各自记，不按组合记）。 */
export function pickedArms(picks) {
  return (picks || []).map((p) => p.arm).filter((a) => ARMS.includes(a))
}
