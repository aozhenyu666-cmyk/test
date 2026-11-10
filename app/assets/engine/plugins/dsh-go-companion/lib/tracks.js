// dsh-go-companion · 支线底座
//
// 一条「支线」= 一个练习方向。底座只认四个固定槽位，别的什么都不认：
//   scene   触发场景          —— 什么时候这条支线起作用
//   first   第一反应判断      —— 他嘴上/手上下意识给出的那个判断
//   missed  漏掉的条件或反例  —— 这个方向里最常被跳过的前提，或最典型的反例
//   blind   盲区标签          —— 一句话给这个盲区命名
//
// 约束（第六轮 A，硬要求）：**具体是哪个方向，一个字都不许写进代码**。
// 方向的内容全在配置里（config.tracks，一页 JSON 数组）。所以：
//   · 加第三条支线 → 只改配置，本文件 diff = 0；
//   · 把所有支线关掉（config.tracks = []）→ 本模块全部返回空，主干链路不受影响。
//
// 判定只吃结构化输入，不听他说什么：analyzeTrack 的输入只有客观账本里的字段时间，
// 没有「我觉得」「他说他这次做到了」这种自述。

/** 四个固定槽位，顺序固定。改这个数组等于改协议。 */
export const SLOT_KEYS = Object.freeze(['scene', 'first', 'missed', 'blind'])

/** 槽位 ↔ 中文名。写进 prompt 和 /tracks 的地方都用这个。 */
export const SLOT_LABEL = Object.freeze({
  scene: '触发场景',
  first: '第一反应判断',
  missed: '漏掉的条件或反例',
  blind: '盲区标签',
})

/** 一条支线最多带多少字进 prompt（防止一页配置把上下文挤爆）。 */
const SLOT_MAX = 200

/** 同时生效的支线上限。超了只留前面几条，其余报进 dropped。 */
export const MAX_TRACKS = 6

function text(v, max = SLOT_MAX) {
  if (typeof v !== 'string') return ''
  const s = v.replace(/\s+/g, ' ').trim()
  return s.length <= max ? s : s.slice(0, max - 1) + '…'
}

/**
 * 校验一条支线。四个槽位**全都要有实质内容**才算合格——缺一个就整条丢掉，
 * 不做"缺的先用默认补上"（那会让一条空支线看着像在工作）。
 *
 * @returns {{ ok: true, track: object } | { ok: false, id: string, missing: string[] }}
 */
export function normalizeTrack(raw, index = 0) {
  const id = text(raw && raw.id, 64) || 'track-' + (index + 1)
  const missing = []
  const out = { id }
  for (const key of SLOT_KEYS) {
    const v = text(raw && raw[key])
    if (!v) missing.push(SLOT_LABEL[key])
    out[key] = v
  }
  if (missing.length) return { ok: false, id, missing }
  if (raw && typeof raw.name === 'string' && raw.name.trim()) out.name = text(raw.name, 40)
  // 默认开；显式写 false 才算关掉（写错类型不当成关）。
  out.enabled = !(raw && raw.enabled === false)
  return { ok: true, track: out }
}

/**
 * 把一页配置变成一个可用清单。
 * @param {unknown} raw config.tracks（数组；别的一律当没有）
 * @returns {{ tracks: object[], dropped: {id:string, reason:string, missing?:string[]}[], over: string[] }}
 */
export function normalizeTracks(raw) {
  const list = Array.isArray(raw) ? raw : []
  const tracks = []
  const dropped = []
  for (let i = 0; i < list.length; i += 1) {
    const r = normalizeTrack(list[i], i)
    if (!r.ok) {
      dropped.push({ id: r.id, reason: 'slot-missing', missing: r.missing })
      continue
    }
    if (!r.track.enabled) {
      dropped.push({ id: r.track.id, reason: 'disabled' })
      continue
    }
    tracks.push(r.track)
  }
  const over = tracks.slice(MAX_TRACKS).map((t) => t.id)
  return { tracks: tracks.slice(0, MAX_TRACKS), dropped, over }
}

/**
 * 挑一条支线。跟 drills 一样：最近用过的三条先避开，避不开就放开。
 * @param {object[]} tracks
 * @param {string[]} recentIds 账本里最近用过的（新→旧）
 * @param {() => number} rnd 便于测试注入
 */
export function pickTrack(tracks, recentIds = [], rnd = Math.random) {
  const list = Array.isArray(tracks) ? tracks.filter((t) => t && t.id) : []
  if (!list.length) return null
  const recent = new Set((recentIds || []).slice(0, 3).map(String))
  const pool = list.filter((t) => !recent.has(String(t.id)))
  const use = pool.length ? pool : list
  const i = Math.min(use.length - 1, Math.floor(Math.max(0, Math.min(0.999999, Number(rnd()) || 0)) * use.length))
  return use[i]
}

/**
 * 进 prompt 的几行。没有支线就返回空数组——主干照跑。
 */
export function trackLines(track) {
  if (!track || typeof track !== 'object') return []
  const name = track.name || track.id
  const lines = [
    '这一球走支线「' + String(name).slice(0, 40) + '」：' + track.scene,
    '他这类情境下的第一反应通常是：' + track.first + '。你要拿这一条去撞他这一球实际说的话，别顺着他的自述走。',
    '最常被跳过的是：' + track.missed + '。他答得再顺，只要没碰到这一条，就不算过。',
    '这一条支线的盲区标签是「' + track.blind + '」。指出来的时候直接用这个词，不解释词义。',
  ]
  return lines
}

/**
 * 从账本里取最近用过的支线 id（新→旧），给 pickTrack 避开。
 * 只认 state === 'generated' 且带 track 字段的条目。
 */
export function recentTracks(entries, n = 3) {
  const out = []
  for (let i = (entries?.length || 0) - 1; i >= 0 && out.length < n; i -= 1) {
    const e = entries[i]
    if (e?.state === 'generated' && e.track) out.push(String(e.track))
  }
  return out
}

/**
 * 结构化判定：只吃客观量，不听自述。
 *
 * 三个输入都是**账本字段**，不是模型的话：
 *   sceneHit   这一球有没有真的落到 scene 上（bool）
 *   firstHit   他这一球的第一反应，是否命中配置里记的第一反应（bool）
 *   missedHit  有没有补上 missed 那一条（bool）
 *
 * @returns {{ pass: boolean, blind: string, why: string }}
 */
export function analyzeTrack(track, { sceneHit, firstHit, missedHit } = {}) {
  if (!track) return { pass: false, blind: '', why: 'no-track' }
  const why = []
  if (sceneHit !== true) why.push('场景没对上')
  if (firstHit !== true) why.push('第一反应不是配置里那一种')
  if (missedHit !== true) why.push('漏的条件没补上')
  return { pass: why.length === 0, blind: String(track.blind || ''), why: why.join('；') || '全中' }
}

/** /tracks 路由和 /status 用的可读视图。 */
export function trackStatus(raw, recentIds = []) {
  const n = normalizeTracks(raw)
  return {
    count: n.tracks.length,
    max: MAX_TRACKS,
    recent: (recentIds || []).slice(0, 3).map(String),
    tracks: n.tracks.map((t) => ({ id: t.id, name: t.name || t.id, blind: t.blind })),
    dropped: n.dropped,
    over: n.over,
    slots: SLOT_KEYS.map((k) => ({ key: k, label: SLOT_LABEL[k] })),
  }
}
