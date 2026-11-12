// dsh-go-companion · 情绪池（R7 B4）
//
// 结构借 dsh-longing 的状态机（多维数值、事件推动、半衰期回落、余怨跨天），维度是自己的。
// 四根轴各自回落，不是一条线：
//   耐心 70 / 3 小时    在意 60 / 12 小时    火气 15 / 90 分钟    兴致 50 / 4 小时
//
// **情绪是账本的纯函数**：不给它单独的状态文件。账本里已经有判球（replied/verdict）、
// 违抗（defied）、立场结算（stance-settled）这些事件，进程重启、账本窗口滑动都算得回来，
// 也不会出现"状态文件和账本各记一半"那种对不上的账。
//
// 硬边界（test/emotion.test.mjs 守着）：
//   1. 情绪不改任何锁、额度、判定——本文件不被 wall*.js / force.js / machine.js import；
//   2. 连发每小时硬上限 BURST_HOURLY_CAP 条，安静时段是 0 条；
//   3. 情绪只产出「档位标签 + 长度/条数/间隔」，一个字都不自己写——不给人格卡里
//      「能说多狠」的上限之外的空间。
//   4. 念出来的只有语速和音高两个旋钮（voiceOf），且都取在 TONE_PLAN 已有的档位门槛上，
//      不另开一张表：情绪能改"怎么听"，不能改"说什么"。

import { inQuiet } from './plan.js'

/** 四根轴。base 是"日常值"，不是 0：回落回日常，不回落成空。 */
export const DIM_META = {
  patience: { label: '耐心', base: 70, halfMs: 3 * 3_600_000, lo: '炸了', hi: '很有耐心' },
  care: { label: '在意', base: 60, halfMs: 12 * 3_600_000, lo: '懒得管', hi: '很上心' },
  anger: { label: '火气', base: 15, halfMs: 90 * 60_000, lo: '平静', hi: '生气' },
  interest: { label: '兴致', base: 50, halfMs: 4 * 3_600_000, lo: '没劲', hi: '想聊' },
}

export const DIMS = Object.keys(DIM_META)

/**
 * 事件表（判定来源 → 数值变化）。dims 里的正负号就是"往哪边推"。
 * 同一件事连着来会递减（applyEvent 的 repeat），不然他敷衍三次她就该炸了。
 */
export const EVENTS = {
  brief: { note: '敷衍', dims: { patience: -8, anger: 10 } },
  // 连续三次敷衍：额外挂一条 6 小时余怨（余怨期间火气回不到平静）。
  // keepStreak：它不改连击的标签——「连着敷衍」和「敷衍」是同一件事的第三下，
  // 换掉标签就说不清"敷衍了几次"了，状态层那一行也拼不出来。
  brief3: { note: '连着敷衍', dims: { anger: 15 }, grudgeMs: 6 * 3_600_000, keepStreak: true },
  engaged: { note: '认真回', dims: { patience: 6, interest: 8, anger: -10 } },
  kept: { note: '守住立场', dims: { care: 10 } },
  defiance: { note: '违抗', dims: { anger: 20 }, grudgeMs: 24 * 3_600_000 },
  ignored: { note: '被晾着', dims: { interest: -5 } },
}

/** 连发：每小时最多这么多次"一轮多条"。安静时段是 0。 */
export const BURST_HOURLY_CAP = 12

/** 余怨期间火气回不到这个值以下——"余怨"就是"还在气，只是不说"。 */
export const GRUDGE_ANGER = 45

/** 连着这么多次敷衍算"连着敷衍"。 */
export const BRIEF_STREAK = 3

/** 发出去这么久没回音才算"被晾着"（和 silence.js 的 lighter 门槛一致）。 */
export const IGNORED_AFTER_MS = 5 * 60_000

/** 同类连发的递减下限：连着第 4 次和第 5 次的力度一样，不会衰减到没有。 */
export const REPEAT_FLOOR = 0.25

const clamp = (n, lo = 0, hi = 100) => Math.max(lo, Math.min(hi, n))
const round = (n) => Math.round(n * 100) / 100

/** 四根轴都在日常值上，没有余怨。 */
export function emptyEmotion(now = Date.now()) {
  const out = { v: 1, at: Number(now) || 0, grudgeUntil: 0, streak: { id: '', count: 0 } }
  for (const id of DIMS) out[id] = DIM_META[id].base
  return out
}

function normalize(raw, now) {
  const out = emptyEmotion(now)
  if (!raw || typeof raw !== 'object') return out
  for (const id of DIMS) if (Number.isFinite(Number(raw[id]))) out[id] = clamp(Number(raw[id]))
  if (Number.isFinite(Number(raw.at))) out.at = Number(raw.at)
  if (Number.isFinite(Number(raw.grudgeUntil))) out.grudgeUntil = Number(raw.grudgeUntil)
  if (raw.streak && typeof raw.streak === 'object' && EVENTS[raw.streak.id]) {
    out.streak = { id: String(raw.streak.id), count: Math.max(0, Number(raw.streak.count) || 0) }
  }
  return out
}

/**
 * 按时长回落：每个维度各自往自己的日常值靠，半衰期到了就走完一半距离。
 * shortcut: 一次跳多久都只按半衰期公式算一遍，不切片积分——回落是平滑的衰减，
 * 中间怎么切结果一样（解析解），不需要循环。
 */
export function decayOf(raw, now = Date.now()) {
  const cur = normalize(raw, now)
  // 时间不倒退：now 比 at 还早（时钟被改回去 / 账本里有未来的记录）时按 0 时长算，
  // 而且不把 at 挪回去——挪回去等于回头再衰减一遍。
  const at = Math.max(cur.at, Number(now) || 0)
  const dt = Math.max(0, at - cur.at)
  const out = { ...cur, at, streak: { ...cur.streak } }
  // 余怨还没过：火气这一根轴的"日常值"临时抬高，所以它回不到平静。
  const angry = cur.grudgeUntil > at
  for (const id of DIMS) {
    const meta = DIM_META[id]
    const base = id === 'anger' && angry ? Math.max(meta.base, GRUDGE_ANGER) : meta.base
    out[id] = clamp(base + (cur[id] - base) * Math.pow(0.5, dt / meta.halfMs))
  }
  return out
}

/**
 * 推一个事件。repeat 是"同一件事连着第几次"（0 = 第一次），力度按 0.5^repeat 递减。
 * 返回新的情绪对象，不改入参。
 */
export function applyEvent(raw, id, { now = Date.now(), repeat } = {}) {
  const ev = EVENTS[id]
  if (!ev) throw new Error('未知的情绪事件：' + String(id))
  const cur = decayOf(raw, now)
  const n = Number.isFinite(Number(repeat)) ? Math.max(0, Number(repeat)) : (cur.streak.id === id ? cur.streak.count : 0)
  const factor = Math.max(REPEAT_FLOOR, Math.pow(0.5, n))
  const out = { ...cur, at: Number(now) || cur.at, streak: ev.keepStreak ? { ...cur.streak } : { id, count: n + 1 } }
  for (const [dim, delta] of Object.entries(ev.dims)) out[dim] = clamp(cur[dim] + delta * factor)
  if (ev.grudgeMs) out.grudgeUntil = Math.max(cur.grudgeUntil || 0, out.at + ev.grudgeMs)
  return out
}

/** 账本里哪些条算"被晾着"：发出去了、到现在没人接、并且不是在安静时段发的。 */
function ignoredOf(entries, { now, quietStart, quietEnd, tz }) {
  const answered = new Set()
  for (const e of entries) {
    if (!e) continue
    if (e.state === 'replied' && e.requestId) answered.add(e.requestId)
    if ((e.state === 'human' || e.state === 'said') && Number(e.at) > 0) answered.add('#human')
  }
  let lastHuman = 0
  for (const e of entries) if (e && (e.state === 'human' || e.state === 'said')) lastHuman = Math.max(lastHuman, Number(e.at) || 0)
  const out = []
  for (const e of entries) {
    if (!e || e.state !== 'generated' || !e.requestId) continue
    if (answered.has(e.requestId)) continue
    const at = Number(e.at) || 0
    if (lastHuman > at) continue
    if (now - at < IGNORED_AFTER_MS) continue
    // 睡眠窗里发出去的那一球，他没回不算"被晾着"——那是她在不该发的时候发的。
    if (inQuiet(at, quietStart, quietEnd, tz)) continue
    out.push(at)
  }
  return out
}

/** 账本 → 事件序列（按时间正序）。判据全在代码里，不问模型。 */
export function eventsOf(entries, opts = {}) {
  const now = Number(opts.now) || Date.now()
  const list = (Array.isArray(entries) ? entries : []).filter(Boolean)
  const raw = []
  for (const e of list) {
    const at = Number(e.at) || 0
    if (e.state === 'replied') {
      const v = String(e.verdict || '')
      if (v === 'brief' || v === 'stock') raw.push({ id: 'brief', at })
      else if (v === 'engaged' || v === 'pushback') raw.push({ id: 'engaged', at })
    } else if (e.state === 'defied') {
      raw.push({ id: 'defiance', at })
    } else if (e.state === 'stance-settled' && e.stance && e.stance.status === 'kept' && e.stance.kind === 'hard') {
      raw.push({ id: 'kept', at })
    }
  }
  for (const at of ignoredOf(list, { now, ...opts })) raw.push({ id: 'ignored', at })
  // 连击必须在时间正序上数：账本是"新→旧"追加的，按行序数会把第三次敷衍
  // 算到最早那一球头上，余怨也就挂错了地方。
  raw.sort((a, b) => a.at - b.at)
  const out = []
  let streak = 0
  for (const ev of raw) {
    out.push(ev)
    if (ev.id === 'brief') {
      streak += 1
      // 连到第三次的那一刻补一条：余怨只挂一次，不让第 6、9 次接着挂。
      if (streak === BRIEF_STREAK) out.push({ id: 'brief3', at: ev.at })
    } else if (ev.id === 'engaged') {
      streak = 0
    }
  }
  return out
}

/**
 * 从账本重建这一刻的情绪。
 *
 * @param {Array<object>} entries 账本（旧的在前）
 * @param {{now?: number, tz?: string, quietStart?: number, quietEnd?: number}} [opts]
 */
export function emotionOf(entries, opts = {}) {
  const now = Number(opts.now) || Date.now()
  let cur = emptyEmotion(now)
  const run = {}
  for (const ev of eventsOf(entries, { ...opts, now })) {
    run[ev.id] = (run[ev.id] || 0) + 1
    cur = applyEvent({ ...cur, at: Number(ev.at) || now }, ev.id, { now: ev.at || now, repeat: run[ev.id] - 1 })
    // 换了一件事就重新起算递减：隔一天再敷衍，力度要回到满格。
    for (const id of Object.keys(run)) if (id !== ev.id) run[id] = 0
  }
  return decayOf(cur, now)
}

/**
 * 进状态层的一行：只报"现在是什么状态"，不下结论、不带原话。
 * 形如：`[情绪] 火气 62（敷衍了 3 次）· 耐心 31`
 */
export function emotionLine(raw, now = Date.now()) {
  const e = decayOf(raw, now)
  const anger = Math.round(e.anger)
  const patience = Math.round(e.patience)
  const streak = e.streak || { id: '', count: 0 }
  const ev = EVENTS[streak.id]
  const note = ev && streak.count >= 2 ? '（' + ev.note + '了 ' + streak.count + ' 次）' : ''
  const grudge = e.grudgeUntil > Number(now) ? '（余怨还没过去）' : ''
  return '[情绪] 火气 ' + anger + note + grudge + ' · 耐心 ' + patience
}

/** 档位：情绪只在这几个标签里动，具体怎么说还是人设卡的事。 */
export const TONES = ['calm', 'flat', 'poke', 'eager', 'hot', 'blaze']

/** 每条上限 / 连发间隔的档位表。火气高且耐心低 → 条数多、每条短、间隔 3–8 秒。 */
const TONE_PLAN = {
  blaze: { burst: 3, maxChars: 40, delayMs: [3_000, 5_000], channel: 'voice' },
  hot: { burst: 2, maxChars: 60, delayMs: [4_000, 7_000], channel: 'voice' },
  poke: { burst: 2, maxChars: 60, delayMs: [5_000, 8_000], channel: 'text' },
  eager: { burst: 2, maxChars: 90, delayMs: [6_000, 9_000], channel: 'voice' },
  flat: { burst: 1, maxChars: 40, delayMs: [0, 0], channel: 'text' },
  calm: { burst: 1, maxChars: 120, delayMs: [0, 0], channel: 'text' },
}

/** 情绪 → 这一轮怎么发。档位由数值决定，不看调用方心情。 */
export function toneOf(raw, now = Date.now()) {
  const e = decayOf(raw, now)
  if (e.anger >= 60 && e.patience <= 40) return 'blaze'
  if (e.anger >= 60) return 'hot'
  if (e.interest <= 25) return 'flat'
  if (e.interest >= 75 && e.anger < 40) return 'eager'
  if (e.patience <= 45) return 'poke'
  return 'calm'
}

/**
 * 这一轮怎么发：条数、每条长度、间隔、渠道、档位。
 *
 * burst 受两道闸：安静时段直接 0；一小时内已经发过的条数从 BURST_HOURLY_CAP 里扣。
 * channel 只出 text / voice——浮层是手机按力度和静音决定的，不归情绪管（所以调用方
 * 若给了 overlay 意图，以调用方为准）。
 */
export function behaviorOf(raw, { now = Date.now(), quiet = false, sentLastHour = 0, cap = BURST_HOURLY_CAP } = {}) {
  const tone = toneOf(raw, now)
  const plan = TONE_PLAN[tone]
  const left = Math.max(0, Number(cap) - Math.max(0, Number(sentLastHour) || 0))
  // 安静时段一条都不发：不是"少发"，是 0。
  const burst = quiet ? 0 : Math.min(plan.burst, left)
  return {
    tone,
    burst,
    maxChars: plan.maxChars,
    delayMs: [...plan.delayMs],
    // 安静时段即便档位想要语音也降级成文字；burst 为 0 时渠道没有意义，照给 text。
    channel: burst === 0 ? 'text' : plan.channel,
  }
}

/** 念出来的单条上限：再长没人听，也会盖过她本来那句话。 */
export const VOICE_MAX_CHARS = 60

/**
 * 这一条念不念、用什么调子（R7 B5）。返回 null = 不念，手机一句都不出声。
 *
 * 三道闸全在代码里，不看调用方心情：
 *   1. 总开关 `enabled`（默认关）——不点开就只是浮层；
 *   2. `quiet` 安静时段——一条都不念，跟 burst=0 同理；
 *   3. 渠道：档位要念（`channel === 'voice'`，就是 hot/blaze/eager 三档），
 *      或者漠视阶梯走到了"换渠道"那一级（`stage === 'channel'`，它等这个实现等了四轮）。
 *
 * 调子只有两个旋钮，且都取在 [toneOf] 已有的档位门槛上——火气高（>=60，就是 hot）语速 +15%，
 * 兴致低（<=25，就是 flat）音高 −10%。不按数值连续插值：TTS 的语速/音高是可听的整数档，
 * 连续插值的听感差不出来，测试也没法说清"62 分和 65 分该差多少"。
 *
 * maxChars 取「档位自己那条的字数」和 60 里小的那个：eager 档允许 90 字的文字，
 * 但一条念 60 字以上就不是说话了。
 */
export function voiceOf(raw, { now = Date.now(), enabled = false, quiet = false, channel = 'text', stage = 'none' } = {}) {
  if (!enabled || quiet) return null
  if (channel !== 'voice' && stage !== 'channel') return null
  const e = decayOf(raw, now)
  const tone = toneOf(e, now)
  return {
    rate: e.anger >= 60 ? 1.15 : 1,
    pitch: e.interest <= 25 ? 0.9 : 1,
    maxChars: Math.min(TONE_PLAN[tone].maxChars, VOICE_MAX_CHARS),
  }
}
