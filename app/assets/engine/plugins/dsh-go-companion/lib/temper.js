// dsh-go-companion · 脾气（阴晴难测）
//
// 温和 / 严格不是两个开关，是一根轴上的位置（edge 0 = 软到底，1 = 硬到底）：
//   - 他选的模式给一个"底色"（温和 0.25，严格 0.7）；
//   - 水位往上推：陷得越深越硬；
//   - 再叠一个会自己游走的随机量（均值回归的随机游走），所以同样的情况下，今天和昨天的脸色不一样；
//   - 偶尔翻脸：一成多的概率这一球突然换到另一头（严格时突然软一下，温和时突然冷一下）。
// 他摸不出规律，才不会提前把这一球"消音"。但底线不随脾气变：该锁就锁（见 force.js）。

/** 一张"脸"：这一球怎么说话。从软到硬。 */
export const FACES = [
  { face: 'coax', at: 0.0, label: '哄', rule: '软一点，像在旁边陪着；可以撒个娇、递个台阶，但要把他从里面拽出来一点。' },
  { face: 'tease', at: 0.25, label: '逗', rule: '逗他、损他一句，带点幽默，让他想回嘴。' },
  { face: 'cool', at: 0.45, label: '冷', rule: '话少、平、不带情绪，像在等他自己意识到。一句就够。' },
  { face: 'jab', at: 0.62, label: '怼', rule: '直接戳穿，带点不客气，点出他现在在干什么、在躲什么。' },
  { face: 'scold', at: 0.8, label: '训', rule: '严厉，不留情面。说清楚他哪里在糊弄、代价是什么、现在必须做什么。不要骂人，要骂到点上。' },
]

/** 翻脸的概率。 */
export const SURPRISE_P = 0.13

export const MODE_BASE = { partner: 0.25, coach: 0.7 }

function clamp(n, lo, hi) {
  return Math.max(lo, Math.min(hi, n))
}

/** 标准正态（Box–Muller）。rng 可注入，测试里给固定序列。 */
function normal(rng) {
  const u = Math.max(1e-9, rng())
  const v = rng()
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v)
}

export function faceOf(edge) {
  let pick = FACES[0]
  for (const f of FACES) if (edge >= f.at) pick = f
  return pick
}

/**
 * 走一步脾气。
 *
 * @param {{edge?: number, at?: number}} prev  上一次的状态（存在 companion/temper.json）
 * @param {{mode: string, tide: number, noTake?: number, mood?: number, now: number, rng?: () => number}} input
 * @returns {{edge: number, at: number, face: string, label: string, rule: string, surprise: boolean}}
 */
export function stepTemper(prev, { mode, tide = 0, noTake = 0, mood, now, rng = Math.random }) {
  const base = MODE_BASE[mode] ?? MODE_BASE.partner
  // 心情低（心疼/担心他）时不会更凶，反而更沉；心情好时更爱逗。只是微调。
  const moodTilt = Number.isFinite(Number(mood)) ? (70 - Number(mood)) / 400 : 0
  const target = clamp(base + (Number(tide) / 100) * 0.45 + Math.min(3, noTake) * 0.05 + moodTilt, 0, 1)
  const last = Number.isFinite(Number(prev?.edge)) ? Number(prev.edge) : target
  const dtMin = clamp((Number(now) - (Number(prev?.at) || Number(now))) / 60_000, 0, 240)
  // 均值回归：每 20 分钟往目标靠一大半；噪声跟着时间放大，隔得越久越"换了个人"。
  const pull = 1 - Math.exp(-dtMin / 20)
  const noise = normal(rng) * 0.12 * Math.sqrt(Math.max(1, dtMin) / 10)
  const edge = clamp(last + (target - last) * pull + noise, 0, 1)
  let shown = edge
  const surprise = rng() < SURPRISE_P
  if (surprise) shown = edge >= 0.5 ? clamp(edge - 0.5, 0, 0.3) : clamp(edge + 0.45, 0.55, 1)
  const f = faceOf(shown)
  // 存的是没翻脸的那个值：翻脸只是这一球，底色不跟着跳。
  return { edge: Math.round(edge * 1000) / 1000, at: Number(now), face: f.face, label: f.label, rule: f.rule, surprise }
}

/** 拼进 prompt 的一行。 */
export function temperLine(t) {
  if (!t || !t.rule) return ''
  const turn = t.surprise ? '（这一球你突然换了个态度，别解释为什么）' : ''
  return '这一球的脸色：' + t.label + '。' + t.rule + turn
}
