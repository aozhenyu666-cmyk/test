// dsh-go-companion · 大脑（从他身上学，并改自己的判断）
//
// 借的是移动健康里"即时自适应干预"（JITAI，代表是 HeartSteps）的做法：每次出手都是一次小实验，
// 用 Thompson 抽样挑招，再看"近端结果"给分。我们的近端结果是最硬的那个：
//   这一球发出去之后 10 分钟内，他有没有离开那个娱乐 App（或者认真回了这一球）。
// 拉回来了 = 1，没拉回来 = 0。按水位分档记账：他"在滑"时管用的招，"陷住了"时未必管用。
//
// Thompson 抽样本身带随机：他摸不出规律，同时它在稳定地往"真管用"的方向偏。
// 思维题反过来：他越答不上来的方法分越高——弱项要多练。
//
// 判断（beliefs）不是写死的，每次从证据重算；某条判断翻了，就记下来，下一球可以跟他说
// "我之前以为……，这几次看下来不对"。状态存在 companion/brain.json，全部本地。

import { BANDS } from './tide.js'
import { FACES } from './temper.js'
import { DRILLS, drillOf } from './drills.js'

/** 发球后多久内离开娱乐 App 算"拉回来了"。 */
export const PULL_WINDOW_MS = 10 * 60_000
/** 至少等这么久再判：刚发出去一秒就切走，可能是巧合。 */
export const PULL_MIN_MS = 30_000
const ENGAGED = new Set(['engaged', 'pushback'])
const VAGUE = new Set(['brief', 'evasive', 'stock'])

export function emptyBrain() {
  return { v: 1, faces: {}, drills: {}, hours: {}, arms: {}, pending: null, served: {}, beliefs: {}, revision: null }
}

export function cleanBrain(raw) {
  const b = emptyBrain()
  if (!raw || typeof raw !== 'object') return b
  for (const k of ['faces', 'drills', 'hours', 'arms', 'served', 'beliefs']) {
    if (raw[k] && typeof raw[k] === 'object' && !Array.isArray(raw[k])) b[k] = raw[k]
  }
  if (raw.pending && typeof raw.pending === 'object') b.pending = raw.pending
  if (raw.revision && typeof raw.revision === 'object') b.revision = raw.revision
  if (Number.isFinite(Number(raw.graceUntil))) b.graceUntil = Number(raw.graceUntil)
  return b
}

// ---------------------------------------------------------------- 抽样

function normal(rng) {
  const u = Math.max(1e-9, rng())
  const v = rng()
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v)
}

/** Gamma(k, 1)，Marsaglia–Tsang。 */
function gamma(k, rng) {
  if (k < 1) return gamma(k + 1, rng) * Math.pow(Math.max(1e-9, rng()), 1 / k)
  const d = k - 1 / 3
  const c = 1 / Math.sqrt(9 * d)
  for (let i = 0; i < 64; i += 1) {
    const x = normal(rng)
    const v = Math.pow(1 + c * x, 3)
    if (v <= 0) continue
    const u = Math.max(1e-9, rng())
    if (Math.log(u) < 0.5 * x * x + d - d * v + d * Math.log(v)) return d * v
  }
  return d
}

export function betaSample(a, b, rng = Math.random) {
  const x = gamma(Math.max(0.01, a), rng)
  const y = gamma(Math.max(0.01, b), rng)
  return x / (x + y || 1)
}

function cell(table, key) {
  const c = table[key]
  return { win: Number(c?.win) || 0, lose: Number(c?.lose) || 0 }
}

function bump(table, key, win) {
  const c = cell(table, key)
  if (win) c.win += 1
  else c.lose += 1
  table[key] = c
}

// ---------------------------------------------------------------- 挑招

/**
 * 在脾气给的那张脸附近（相邻一档以内）用 Thompson 抽样挑最可能把他拉回来的那张。
 * 脾气定性格、大脑定这一次——翻脸那一球不改，那是脾气自己的事。
 */
export function chooseFace(brain, { band, face, surprise }, rng = Math.random) {
  if (surprise) return face
  const i = FACES.findIndex((f) => f.face === face)
  if (i < 0) return face
  const near = FACES.slice(Math.max(0, i - 1), i + 2).map((f) => f.face)
  let best = face
  let bestScore = -1
  for (const f of near) {
    const c = cell(brain.faces, band + ':' + f)
    // 先验偏向脾气给的那张：它多一票。
    const prior = f === face ? 2 : 1
    const s = betaSample(c.win + prior, c.lose + 1, rng)
    if (s > bestScore) {
      best = f
      bestScore = s
    }
  }
  return best
}

/** 思维题的抽样分：答不上来的越多分越高（弱项多练），也带随机。 */
export function drillScorer(brain, rng = Math.random) {
  return (id) => {
    const c = cell(brain.drills, id)
    return betaSample(c.lose + 1, c.win + 1, rng)
  }
}

// ---------------------------------------------------------------- 记账

/**
 * 记一个 arm 的账（§13.1 multiple-play bandit）：一轮抽 2~3 个 arm，只给被抽中的各记一次，
 * 没抽中的不动。这样 7 个 arm 各自攒自己的样本，不会像按组合记账那样摊薄到永远攒不够。
 * 能用哪些 arm 由 vibe.js 的 ARMS 决定；这里不 import 它，免得循环依赖。
 */
export function updateArm(brain, arm, win) {
  const id = String(arm || '')
  if (!id) return brain
  bump(brain.arms, id, win)
  return brain
}

/** 这一球发出去了：记下当时在哪个 App、水位、用了哪张脸、哪道题。 */
export function onServe(brain, { requestId, phone, band, face, drill, at }) {
  const id = String(requestId || '')
  if (!id) return brain
  if (drill) {
    brain.served[id] = { drill, at: Number(at) }
    const keys = Object.keys(brain.served)
    if (keys.length > 20) for (const k of keys.slice(0, keys.length - 20)) delete brain.served[k]
  }
  // "拉回来"只对正在娱乐 App 里的那一球有意义。
  brain.pending = phone?.fun && phone.app ? { requestId: id, app: phone.app, band, face, at: Number(at) } : null
  return brain
}

/**
 * 手机又报来一次现状（/plan、/request 都会带）：看上一球有没有把他拉回来；顺带记"几点容易滑"。
 * 返回这次判出来的结果（null = 还没到判的时候）。
 */
export function observe(brain, { phone, now }) {
  const at = Number(now)
  if (phone) {
    const h = phone.local ? Number(phone.local.slice(0, 2)) : NaN
    if (Number.isFinite(h)) {
      const k = String(h)
      const c = brain.hours[k] || { fun: 0, n: 0 }
      c.n += 1
      if (phone.fun) c.fun += 1
      brain.hours[k] = c
    }
  }
  const p = brain.pending
  if (!p || !phone) return null
  const since = at - Number(p.at)
  if (since < PULL_MIN_MS) return null
  const left = !phone.screenOn || phone.app !== p.app
  if (left && since <= PULL_WINDOW_MS) {
    bump(brain.faces, p.band + ':' + p.face, true)
    brain.pending = null
    return { pulled: true, face: p.face, band: p.band }
  }
  if (since > PULL_WINDOW_MS) {
    bump(brain.faces, p.band + ':' + p.face, left)
    brain.pending = null
    return { pulled: left, face: p.face, band: p.band }
  }
  return null
}

/** 他回了这一球：思维题按过没过线记；认真回也算把他拉回来了。 */
export function onReply(brain, { requestId, verdict }) {
  const id = String(requestId || '')
  const v = String(verdict || '')
  const s = brain.served[id]
  if (s?.drill && drillOf(s.drill)) {
    if (ENGAGED.has(v)) bump(brain.drills, s.drill, true)
    else if (VAGUE.has(v)) bump(brain.drills, s.drill, false)
  }
  const p = brain.pending
  if (p && p.requestId === id && ENGAGED.has(v)) {
    bump(brain.faces, p.band + ':' + p.face, true)
    brain.pending = null
  }
  return brain
}

/** 一道题这么久没人回：算没答上来（沉默不是答案）。 */
export function onSilent(brain, { requestId }) {
  const s = brain.served[String(requestId || '')]
  if (s?.drill && drillOf(s.drill) && !s.silent) {
    bump(brain.drills, s.drill, false)
    s.silent = true
  }
  return brain
}

// ---------------------------------------------------------------- 判断

const BAND_LABEL = Object.fromEntries(BANDS.map((b) => [b.band, b.label]))
const FACE_LABEL = Object.fromEntries(FACES.map((f) => [f.face, f.label]))
const DRILL_NAME = Object.fromEntries(DRILLS.map((d) => [d.id, d.name]))

function rate(c) {
  const n = c.win + c.lose
  return n ? c.win / n : 0
}

/**
 * 从证据重算判断。每条有一个 key（同一件事的判断），值翻了就是"改了主意"。
 * 证据不够（每格至少 4 次）不下判断——宁可不说，不假装了解他。
 */
export function deriveBeliefs(brain) {
  const out = {}
  for (const b of BANDS) {
    const rows = FACES.map((f) => ({ f: f.face, c: cell(brain.faces, b.band + ':' + f.face) }))
      .filter((r) => r.c.win + r.c.lose >= 4)
    if (rows.length >= 1) {
      rows.sort((x, y) => rate(y.c) - rate(x.c))
      const top = rows[0]
      if (rate(top.c) >= 0.4) {
        out['best:' + b.band] = {
          text: '他' + BAND_LABEL[b.band] + '的时候，"' + FACE_LABEL[top.f] + '"最能把他拉回来（' +
            (top.c.win + top.c.lose) + ' 次里 ' + top.c.win + ' 次）',
          value: top.f,
        }
      }
      const worst = rows[rows.length - 1]
      if (rows.length >= 2 && rate(worst.c) <= 0.2) {
        out['useless:' + b.band] = {
          text: '他' + BAND_LABEL[b.band] + '的时候，"' + FACE_LABEL[worst.f] + '"基本没用',
          value: worst.f,
        }
      }
    }
  }
  for (const d of DRILLS) {
    const c = cell(brain.drills, d.id)
    const n = c.win + c.lose
    if (n >= 3 && c.lose / n >= 0.6) out['weak:' + d.id] = { text: '"' + d.name + '"他经常答不上来，是弱项', value: 'weak' }
    else if (n >= 3 && c.win / n >= 0.7) out['weak:' + d.id] = { text: '"' + d.name + '"他已经答得不错了', value: 'ok' }
  }
  let peak = null
  for (const [h, c] of Object.entries(brain.hours)) {
    if (c.n < 5) continue
    const r = c.fun / c.n
    if (r >= 0.5 && (!peak || r > peak.r)) peak = { h, r }
  }
  if (peak) out.hour = { text: peak.h + ' 点前后他最容易滑进去', value: peak.h }
  return out
}

/** 重算判断；有翻转就记一条 revision（下一球可以说出来，说一次就清掉）。 */
export function revise(brain, now) {
  const next = deriveBeliefs(brain)
  const prev = brain.beliefs || {}
  for (const [k, b] of Object.entries(next)) {
    const old = prev[k]
    if (old && old.value !== b.value) {
      brain.revision = { at: Number(now), from: old.text, to: b.text }
    }
  }
  brain.beliefs = next
  return brain
}

/** 拼进 prompt 的几行：你对他的判断（会随证据改）。 */
export function brainLines(brain) {
  const lines = []
  const list = Object.values(brain?.beliefs || {}).map((b) => b.text)
  if (list.length) {
    lines.push('你从过去的回合里对他形成的判断（证据变了你就改，不要死守）：')
    for (const t of list.slice(0, 5)) lines.push('- ' + t)
  }
  if (brain?.revision) {
    lines.push('你最近改了一个判断：之前以为"' + brain.revision.from + '"，现在看是"' + brain.revision.to +
      '"。合适的话可以跟他直说你看错了，这比装作一直都懂更可信。')
  }
  return lines
}
