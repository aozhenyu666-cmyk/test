// dsh-go-companion · P2 全天主动（第八轮）
//
// 计划生效后规则就反过来了：**他不再开局，局来开他**。
// 所以这里没有"要不要开"的判断，只有"什么时候开"和"他能拿什么挡"：
// 能说「换」（每天有额度），**不能说「不开」**。
//
// 纯函数：时间、队列、设置都由调用方给。

import { DAY_WORKDAY, inWorkWindow } from './settings.js'

/** 每天换局的额度。 */
export const SWAP_QUOTA = 3
/** 非工作时段无局上限（分钟）。 */
export const OFFHOUR_IDLE_MIN = 30
/** 小鱼提议后多久不理就自动开休息局。 */
export const OFFHOUR_GRACE_MS = 10 * 60_000

function asQueue(v) {
  return Array.isArray(v) ? v.filter((b) => b && typeof b === 'object') : []
}

/**
 * 到点该开哪一局。已经在局里就不开新的——同时只跑一局。
 * @param {{min:number, queue:object[], running?:object|null}} input
 * @returns {{open:object|null, why:string}}
 */
export function nextDue(input) {
  if (input.running) return { open: null, why: 'already-running' }
  const q = asQueue(input.queue)
  if (!q.length) return { open: null, why: 'queue-empty' }
  const min = Number(input.min) || 0
  // 队列是排好序的：第一个到点的就是它。过点的也算到点（补开不如现在开）。
  const hit = q.find((b) => Number(b.minute) <= min)
  return hit ? { open: hit, why: 'due' } : { open: null, why: 'not-yet' }
}

/** 换局额度：按当地日号重置。 */
export function swapQuota(state, day) {
  const s = state && typeof state === 'object' ? state : {}
  const sameDay = Number(s.swapDay) === Number(day)
  const used = sameDay ? Number(s.swapUsed) || 0 : 0
  return { used, left: Math.max(0, SWAP_QUOTA - used), day: Number(day) || 0 }
}

/**
 * 换一局：把当前这局挪到队列末尾，换成最早的那一局。
 * @returns {{ok:boolean, queue:object[], state:object, bout?:object, why?:string}}
 */
export function swapBout(input) {
  const day = Number(input.day) || 0
  const q = asQueue(input.queue)
  const q1 = swapQuota(input.state, day)
  if (!q.length) return { ok: false, queue: q, state: input.state || {}, why: '今天没有局可以换' }
  if (q1.left <= 0) {
    return { ok: false, queue: q, state: input.state || {}, why: '今天换局的机会用完了（每天 ' + SWAP_QUOTA + ' 次）' }
  }
  const [head, ...rest] = q
  const next = rest[0] || null
  const queue = next ? [next, ...rest.slice(1), { ...head, minute: Number(next.minute) }] : [head]
  return {
    ok: Boolean(next),
    queue,
    bout: next || head,
    state: { ...(input.state || {}), swapDay: day, swapUsed: q1.used + 1 },
    why: next ? '' : '队列里只有这一局，换不了',
  }
}

/**
 * 排一段专注之前必须问清的三件事。缺一项就不排——不问清就排出来的「接下来两小时刷题」
 * 到了现场只能靠发挥，而发挥正是这一层要拦掉的东西。
 *
 * 三项不是随便挑的：领域决定这一局里哪些 App 算在任务里（同一 App 在不同任务里意义不同），
 * 休息时段决定什么时候该停（不写就没人叫停），交付物决定这一局怎么算完（没它就是一串没有终点的拍）。
 */
export const REQUIRED_ANSWERS = ['domain', 'breakAt', 'deliver']
const ANSWER_LABEL = { domain: '今天刷哪些领域', breakAt: '什么时候休息', deliver: '这一段要交出什么' }

/** 还没答的必答项。空数组＝可以排。 */
export function missingAnswers(input) {
  const a = input && typeof input === 'object' ? input : {}
  return REQUIRED_ANSWERS.filter((k) => !String(a[k] == null ? '' : a[k]).trim())
}

/** 缺项时叫她去问的那一句。都答了返回空串。 */
export function answerPrompt(missing) {
  const list = (Array.isArray(missing) ? missing : []).map((k) => ANSWER_LABEL[k]).filter(Boolean)
  if (!list.length) return ''
  return '先问清：' + list.join('、') + '。缺一项不排。'
}

/** 「不开」不许说。命中就给他一条别的路，而不是拦住不回应。 */
const NO_OPEN = ['不开', '不想开', '今天不开了', '不做了', '免了', '跳过这局']

export function refuseOpen(text) {
  const s = String(text || '')
  const hit = NO_OPEN.find((w) => s.includes(w))
  if (!hit) return ''
  return '这局不能不开。可以说「换」，每天有 ' + SWAP_QUOTA + ' 次机会。'
}

/**
 * 非工作时段：无局满 30 分钟由小鱼提议一局，提议后 10 分钟不理就自动开休息局。
 * @param {{now:number, min:number, kind:string, settings:object, running?:object|null,
 *          noBoutSince?:number, proposedAt?:number}} input
 * @returns {{act:'none'|'propose'|'auto-open', protocolId?:string, why?:string, nextCheckAt:number}}
 */
export function offhour(input) {
  const now = Number(input.now) || 0
  const min = Number(input.min) || 0
  const kind = input.kind || DAY_WORKDAY
  const idleFrom = Number(input.noBoutSince) || 0
  const proposedAt = Number(input.proposedAt) || 0

  // 工作时段由 P1/P2 的到点开局管，这里只管非工作时段。
  if (inWorkWindow(input.settings, kind, min)) return { act: 'none', why: 'work-hours', nextCheckAt: now + 5 * 60_000 }
  if (input.running) return { act: 'none', why: 'already-running', nextCheckAt: now + 5 * 60_000 }
  // 已经提议过：10 分钟不理就自动开。
  if (proposedAt > 0) {
    if (now - proposedAt < OFFHOUR_GRACE_MS) return { act: 'none', why: 'waiting', nextCheckAt: proposedAt + OFFHOUR_GRACE_MS }
    return { act: 'auto-open', protocolId: 'rest', why: '提议了 10 分钟没理，自动开休息局', nextCheckAt: now + 30 * 60_000 }
  }
  if (idleFrom > 0 && now - idleFrom >= OFFHOUR_IDLE_MIN * 60_000) {
    return { act: 'propose', protocolId: 'rest', why: '已经 ' + OFFHOUR_IDLE_MIN + ' 分钟没有局了', nextCheckAt: now + OFFHOUR_GRACE_MS }
  }
  const wait = idleFrom > 0 ? idleFrom + OFFHOUR_IDLE_MIN * 60_000 : now + 30 * 60_000
  return { act: 'none', why: 'not-idle-enough', nextCheckAt: wait }
}
