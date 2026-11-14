// dsh-go-companion · 漠视阶梯
//
// 同一个发球不允许原样再发。没接球时按阶梯换打法，每一级都换内容、换角度：
//   5 分钟   换一个更轻、更具体的球（观点挑衅 → 小挑战 / 闲聊）
//   15 分钟  换渠道（浮层、语音念出来）。渠道是手机侧执行的，这里只把
//            stage='channel' 写进账本当意图。
//            B5 起这条接上了：index.js 的 compose 读到 stage==='channel' 就下发
//            extra.voice，手机用系统 TTS 念出来（VoiceOut.kt），念没念出来由手机
//            回报 voice-out 记账。总开关关着 / 安静时段 / 勿扰 / 媒体音量为 0 /
//            连麦舱内都会降级成文字，插件不假装它念了。
//   连续 3 次没接（阶梯走完）  本轮停下，歇 3 小时再开始，把"几点、哪类球被漠视了"记进账本，下次拿它当素材。
//
// shortcut: 只看账本里这条发球有没有回球，不区分"没看到"和"看到不理"——
// 两者对阶梯的选择其实一样（都要换更轻的球）。真要分开，得让 App 把
// displayed/dismissed 的 surface 一起报上来，账本现在没这个字段。

import { startOfDay } from './tz.js'

/** 阶梯：到点换到哪一级。 */
export const LADDER = [
  { atMs: 5 * 60_000, stage: 'lighter' },
  { atMs: 15 * 60_000, stage: 'channel' },
]

/**
 * 连续这么多次没接，本轮就停下，不再追：原球 + 轻球 + 换渠道那一球，三次。
 * 以前是 2，可阶梯每一级都要发一球，2 次就停意味着 15 分钟换渠道那一级在正常时序下永远走不到。
 */
export const NO_TAKE_LIMIT = 3

/** 停下之后歇多久重新开始：最后一球过去这么久，旧账清零，按新的一轮算。 */
export const REST_MS = 3 * 3_600_000

/** "更轻"的球：小挑战和闲聊。观点挑衅、复盘这些更重，没接球时先不用。 */
export const LIGHTER_TYPES = ['challenge', 'chat']

/** 这一球有没有被接：同一 requestId 后来出现过 replied。 */
function answeredBy(entries, requestId) {
  for (const e of entries) if (e && e.requestId === requestId && e.state === 'replied') return true
  return false
}

/** 账本里的发球，新→旧。 */
function serves(entries, sessionId) {
  const out = []
  for (let i = entries.length - 1; i >= 0; i -= 1) {
    const e = entries[i]
    if (!e || e.state !== 'generated' || !e.requestId) continue
    if (sessionId && e.sessionId && e.sessionId !== sessionId) continue
    out.push(e)
  }
  return out
}

/** 某一球发的时候走到阶梯哪一级（generated 那条没带时，看同一 requestId 的其他条）。 */
function stageOf(entries, serve) {
  if (typeof serve.silence === 'string') return serve.silence
  for (const e of entries) if (e && e.requestId === serve.requestId && typeof e.silence === 'string') return e.silence
  return ''
}

/**
 * 现在该按哪一级走。
 *
 * 时间从这一串没接的球里**第一球**算起：5 分钟换轻球、15 分钟换渠道，每一级只走一次；
 * 错过了（比如锁屏一直发不出去）就直接跳到够得着的最高一级。
 *
 * @returns {{stage: 'none'|'wait'|'lighter'|'channel'|'stop', sinceMs: number, noTake: number, serveType: string, nextAt?: number}}
 *   none    最近这一球被接了（或歇够了），正常发球
 *   wait    还没到下一级；nextAt 是下一级的时间
 *   lighter 到 5 分钟了，换更轻的球
 *   channel 到 15 分钟了，换渠道
 *   stop    阶梯走完了 / 连续 NO_TAKE_LIMIT 次没接，停下
 */
export function silenceState(entries, { sessionId, now, ladder = LADDER, limit = NO_TAKE_LIMIT, restMs = REST_MS } = {}) {
  const at = Number(now) || Date.now()
  const all = Array.isArray(entries) ? entries : []
  const list = serves(all, sessionId)
  if (!list.length) return { stage: 'none', sinceMs: 0, noTake: 0, serveType: '' }
  // 他在这一球之后说过话（浮层、App 里、跟另一位）就不算没接：以前只认判球配上的 replied，
  // 引擎一重启配对就丢，他刚聊完她就说"你一小时没理我"。
  let human = 0
  for (const e of all) if (e && (e.state === 'human' || e.state === 'said') && Number(e.at) > human) human = Number(e.at)
  const answered = (entries, requestId) => {
    if (answeredBy(entries, requestId)) return true
    const s = list.find((x) => x.requestId === requestId)
    return Boolean(s) && human > Number(s.at)
  }

  const last = list[0]
  const serveType = String(last.serveType || '')
  const sinceLast = Math.max(0, at - (Number(last.at) || 0))
  // 最后这一球已经被接了：阶梯到此为止，别拿旧账继续催。
  if (answered(all, last.requestId)) return { stage: 'none', sinceMs: sinceLast, noTake: 0, serveType }
  // 歇够了：旧账清零。否则一旦停下就再也没有新球，也就永远等不到"接住"来解锁。
  if (sinceLast >= restMs) return { stage: 'none', sinceMs: sinceLast, noTake: 0, serveType }

  // 一条接一条往回数：连着几条没接（中间隔了一个歇息期的不算同一串）。
  const streak = []
  for (const s of list) {
    if (answered(all, s.requestId)) break
    if (streak.length && Number(streak[streak.length - 1].at) - Number(s.at) >= restMs) break
    streak.push(s)
  }
  const noTake = streak.length
  const first = streak[streak.length - 1]
  const sinceMs = Math.max(0, at - (Number(first.at) || 0))
  // 停下时 nextAt 是歇够的时间：到点旧账清零，重新开始。
  const restAt = (Number(last.at) || at) + restMs
  if (noTake >= limit) return { stage: 'stop', sinceMs, noTake, serveType, nextAt: restAt }

  // 这一串里已经走过的最高一级；下一级只能比它高。
  let used = -1
  for (const s of streak) {
    const i = ladder.findIndex((l) => l.stage === stageOf(all, s))
    if (i > used) used = i
  }
  if (used >= ladder.length - 1) return { stage: 'stop', sinceMs, noTake, serveType, nextAt: restAt }
  for (let i = ladder.length - 1; i > used; i -= 1) {
    if (sinceMs >= ladder[i].atMs) return { stage: ladder[i].stage, sinceMs, noTake, serveType }
  }
  return { stage: 'wait', sinceMs, noTake, serveType, nextAt: (Number(first.at) || at) + ladder[used + 1].atMs }
}

/** 被漠视的那类球少发、接住的类型多发。返回 type -> 权重（最小 1）。 */
export function serveWeights(entries) {
  const weight = {}
  for (const t of [...LIGHTER_TYPES, 'observe', 'memory', 'provoke', 'review']) weight[t] = 1
  const taken = new Set()
  for (const e of Array.isArray(entries) ? entries : []) {
    if (!e || e.state !== 'replied') continue
    if (e.requestId) taken.add(e.requestId)
  }
  for (const e of Array.isArray(entries) ? entries : []) {
    if (!e || e.state !== 'generated') continue
    const t = String(e.serveType || '')
    if (!(t in weight)) continue
    if (e.requestId && taken.has(e.requestId)) weight[t] += 1
    else weight[t] = Math.max(1, weight[t] - 1)
  }
  // 被漠视到 0 的类型给 1：少发，不是永不发。
  for (const t of Object.keys(weight)) weight[t] = Math.max(1, weight[t])
  return weight
}

const MUTE_HOUR = /安静(个|一|1)?(小时|钟头)|安静(一)?会|别烦我|闭嘴/
const MUTE_DAY = /(今天|今日)(就)?(别|不要|不用)(找|叫|烦|打扰)我/
const PUNCT = /[，。！？、；：""''《》（）()[\]{}<>·…—～~!?,.;:'"`\s]/g

/** 他说"安静一小时""今天别找我"就照做。返回应该静音到的时间（0 = 没说）。 */
function mutePhrase(text) {
  const s = String(text == null ? '' : text).replace(PUNCT, '')
  if (!s || s.length > 24) return null
  for (const re of [MUTE_DAY, MUTE_HOUR]) {
    const m = re.exec(s)
    if (!m) continue
    // 去掉这句命令本身，剩下的只能是"吧/了/哦"这种语气词。
    // "安静一小时的歌单我整理好了"不算在喊安静，只是刚好说了这几个字。
    if (s.length - m[0].length > 3) continue
    return re
  }
  return null
}

/**
 * 他说"安静一小时""今天别找我"就照做。返回应该静音到的时间（0 = 没说）。
 *
 * shortcut: 只认这两类说法，用正则在插件里直接判，不花模型、不占工具。
 * 说法要更多（"我先忙"、"晚点说"）就往 MUTE_HOUR/MUTE_DAY 里加。
 */
export function mutedUntil(text, now, { tz } = {}) {
  const re = mutePhrase(text)
  if (!re) return 0
  const at = Number(now) || Date.now()
  if (re === MUTE_DAY) return startOfDay(at, tz) + 86_400_000
  return at + 3_600_000
}
