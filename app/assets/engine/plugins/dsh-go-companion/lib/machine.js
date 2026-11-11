// dsh-go-companion · 全天状态机（第三轮 C2）
//
// 第三轮把"什么时候该锁、什么时候该放、谁该说话"收成**一张表**：每个时刻只有一个主状态，
// 进入和退出条件都在 STATE_TABLE 里（改行为就是改表，不用到处找 if）。追问锁是叠在
// 主状态上的临时层（followup.js），结束后回到原主状态。
//
// 优先级（多锁同时生效时放行范围取最严，界面只显示最高那一项）：
//   深夜锁 > 晚间对账 > 其他原因的追问锁 > 冲刺、共脑 > 吃饭时段、放风 > 待命默认锁
//
// 纯函数：时间、配置、手机现状都由调用方给。

import { minuteOfDay, startOfDay } from './tz.js'
import { activeRelax, inNightLock, mealNow, wallConfig } from './wall.js'
import { NIGHT_LOCK_FROM, NIGHT_LOCK_TO, REVIEW_FROM } from './rhythm.js'

/** 冲刺默认两档（用户点「开始」时选的时长）：25 / 45 分钟。 */
export const SPRINT_MIN = 25
export const SPRINT_LONG_MIN = 45
/** 共脑满这么久才算完成；不足回待命，不算过关。 */
export const BRAIN_MIN_MS = 3 * 60_000

/**
 * 进入条件的名字。表里只写名字，判定在这里，一眼能看出"什么算进了这个状态"。
 * 每个条件都只读 facts：{ now, tz, min, day, config, rhythm, followup, session }。
 */
const CONDITIONS = {
  'night-lock': (f) => inNightLock(f.min),
  'evening-followup': (f) => f.followup.active && f.followup.reason === 'evening',
  'evening-gate': (f) => f.rhythm.reviewGate === true,
  'morning-lock': (f) => (f.followup.active && f.followup.reason === 'morning') || f.rhythm.morningGate === true,
  'review-lock': (f) => f.followup.active && f.followup.reason === 'review',
  'followup-other': (f) => f.followup.active && ['defiance', 'standby', 'gemini'].includes(f.followup.reason),
  sprint: (f) => f.sprint.active,
  brain: (f) => f.brain.active,
  meal: (f) => Boolean(mealNow(wallConfig(f.config).meals, f.min)),
  break: (f) => Boolean(f.relax && f.min >= f.relax.from && f.min < f.relax.to),
  always: () => true,
}

/**
 * 主状态表。priority 越大越优先；enter 里任一条件成立就算进入（按 priority 从高到低取第一个）。
 *   back：追问锁结束后回到哪个主状态（只有临时层用）。
 */
export const STATE_TABLE = [
  { id: 'night', priority: 100, enter: ['night-lock'], lock: 'night', back: null,
    agenda: '深夜锁：不主动发球。他还醒着就短、硬地让他去睡。',
    tools: ['notes_vault', 'fish_index', 'dafeiyu_control', 'kb_write'] },
  { id: 'evening', priority: 90, enter: ['evening-followup', 'evening-gate'], lock: 'evening', back: null,
    agenda: '晚间对账：逐项核对今天的完成物，满意才收；满意后今晚的申请窗口才可能开。',
    tools: ['daily_review', 'submit_to_wall', 'submit_verdict', 'request_relax', 'submit_prediction', 'notes_vault', 'fish_index', 'kb_write'] },
  { id: 'morning', priority: 80, enter: ['morning-lock'], lock: 'followup', back: null,
    agenda: '晨间立项：定今天 1–3 件事，每件带完成物；裁决满意且任务校验通过才放。',
    tools: ['daily_plan', 'take_stance', 'notes_vault', 'vault_search', 'fish_index', 'sister_note', 'kb_write', 'dafeiyu_control'] },
  { id: 'review', priority: 80, enter: ['review-lock'], lock: 'followup', back: null,
    agenda: '验收：对照完成物和笔记库追问，满意才放风。',
    tools: ['submit_verdict', 'end_task_mode', 'submit_to_wall', 'notes_vault', 'fish_index', 'vault_recent_notes', 'kb_write', 'dafeiyu_control'] },
  { id: 'followup', priority: 80, enter: ['followup-other'], lock: 'followup',
    agenda: '追问锁：把当前问题问完，证据凑齐交墙（submit_to_wall）。',
    tools: ['submit_verdict', 'submit_to_wall', 'revise_stance', 'notes_vault', 'fish_index', 'grant_break', 'vault_search', 'kb_write'] },
  { id: 'sprint', priority: 70, enter: ['sprint'], lock: 'sprint', back: null,
    agenda: '冲刺：少打扰，最多 20 分钟看一眼进展；到点或他点「申请验收」进验收。',
    tools: ['start_task_mode', 'submit_to_wall', 'end_task_mode', 'block_apps', 'notes_vault', 'fish_index', 'sister_note', 'gemini_live'] },
  { id: 'brain', priority: 70, enter: ['brain'], lock: 'brain', back: null,
    agenda: '共脑：在 Gemini 舱里，不插嘴。结束满 3 分钟才会出现追问。',
    tools: ['gemini_end', 'gemini_log', 'gemini_live', 'schedule_gemini_live', 'notes_vault', 'fish_index'] },
  { id: 'meal', priority: 60, enter: ['meal'], lock: 'open', back: null,
    agenda: '吃饭时段：娱乐开放，纯陪聊。',
    tools: ['notes_vault', 'fish_index', 'sister_note', 'grant_break', 'vault_search', 'vault_recent_notes', 'tighten_today', 'workflow_propose'] },
  { id: 'break', priority: 60, enter: ['break'], lock: 'open', back: null,
    agenda: '放风：纯陪聊，到点回待命。',
    tools: ['notes_vault', 'fish_index', 'sister_note', 'grant_break', 'vault_search', 'vault_recent_notes', 'workflow_propose', 'dafeiyu_control'] },
  { id: 'standby', priority: 50, enter: ['always'], lock: 'standby', back: null,
    agenda: '待命：帮他挑下一件事、拆出第一步；10 分钟没动就开追问锁。',
    tools: ['start_task_mode', 'submit_to_wall', 'end_task_mode', 'block_apps', 'notes_vault', 'fish_index', 'revise_stance', 'gemini_live'] },
]

/** 每个主状态最多挂几个工具（规格：任一状态 ≤8）。 */
export const MAX_TOOLS_PER_STATE = 8

/** 全部工具的并集：任一工具至少在一个状态下可达，测试拿它核对覆盖率。 */
export const ALL_TOOLS = [...new Set(STATE_TABLE.flatMap((row) => row.tools || []))]

/** 这个主状态该挂哪几个工具。给 mountOne 与 fish_index 用；未知状态按待命算。 */
export function toolsFor(state) {
  const row = STATE_TABLE.find((r) => r.id === state) || STATE_TABLE.find((r) => r.id === 'standby')
  return [...(row?.tools || [])]
}

/** 当前状态下调不了的工具，以及它什么时候能用（fish_index 原样说给模型听）。 */
export function toolsElsewhere(state) {
  const here = new Set(toolsFor(state))
  return ALL_TOOLS.filter((name) => !here.has(name))
    .map((name) => ({ name, when: STATE_TABLE.filter((r) => r.tools?.includes(name)).map((r) => r.id) }))
}

/** 锁层怎么说话：界面只显示最高那一项的理由。 */
const LOCK_WHY = {
  night: '深夜锁：现在是休息时间，娱乐锁到早上 7 点。',
  evening: '晚间对账：把今天的账对完才解锁。',
  followup: '追问锁：把这一个问题说清楚。',
  sprint: '冲刺中：娱乐锁着，通知收进防波堤。',
  brain: '共脑：只放行 Gemini。',
  standby: '默认严格：现在不是吃饭/放风时段，娱乐锁着。',
}

/** 每层锁的优先级（和 STATE_TABLE 的 priority 对齐；open 层不下发成锁）。 */
export const LAYER_PRIORITY = { night: 100, evening: 90, followup: 80, sprint: 70, brain: 70, standby: 50 }

function factsOf(input) {
  const now = Number(input?.now) || 0
  const tz = input?.tz
  const config = input?.config || {}
  const day = Number(input?.day) || startOfDay(now, tz)
  const min = Number.isFinite(input?.min) ? Number(input.min) : minuteOfDay(now, tz)
  const rhythm = input?.rhythm || {}
  const followup = input?.followup || { active: false, reason: '', until: 0, since: 0 }
  const session = input?.session && typeof input.session === 'object' ? input.session : null
  const relax = activeRelax(config, now, tz)
  const seg = relax ? relax.segments.find((s) => min >= s.from && min < s.to) : null
  return {
    now, tz, config, day, min, rhythm, followup, session,
    relax: seg || null,
    sprint: {
      active: config.gear === 'busy' && Number(config.gearUntil) > now,
      until: Number(config.gearUntil) || 0,
    },
    brain: {
      active: Boolean(session && Number(session.until) > now),
      until: session && Number(session.until) > now ? Number(session.until) : 0,
    },
    midnight: day,
    endOfDay: day + 86_400_000,
  }
}

/** 这一层什么时候自己到期（引擎挂了手机也按这个松手）。 */
function untilOf(id, f) {
  if (id === 'night') return f.midnight + NIGHT_LOCK_TO * 60_000
  if (id === 'evening') {
    if (f.followup.active && f.followup.reason === 'evening') return Number(f.followup.until) || 0
    const since = Number(f.rhythm?.state?.reviewSince) || f.now
    return since + 60 * 60_000
  }
  if (id === 'morning') {
    if (f.followup.active && f.followup.reason === 'morning') return Number(f.followup.until) || 0
    const since = Number(f.rhythm?.state?.morningSince) || f.now
    return since + 30 * 60_000
  }
  if (id === 'review' || id === 'followup') return Number(f.followup.until) || 0
  if (id === 'sprint') return f.sprint.until
  if (id === 'brain') return f.brain.until
  if (id === 'meal') {
    const m = mealNow(wallConfig(f.config).meals, f.min)
    return m ? f.midnight + m.to * 60_000 : 0
  }
  if (id === 'break' && f.relax) return f.midnight + f.relax.to * 60_000
  // 待命默认锁：锁到下一段吃饭开始 / 深夜锁开始 / 今天结束。
  const marks = []
  for (const m of wallConfig(f.config).meals) if (m.from > f.min) marks.push(m.from)
  if (NIGHT_LOCK_FROM > f.min) marks.push(NIGHT_LOCK_FROM)
  return marks.length ? f.midnight + Math.min(...marks) * 60_000 : f.endOfDay
}

/** 主状态里"娱乐开不开"，以及只放行哪些包。 */
function lockOf(id, f) {
  if (id === 'meal' || id === 'break') return { funLocked: false, allow: [] }
  if (id === 'brain') return { funLocked: true, allow: Array.isArray(f.config.geminiPackages) ? [...f.config.geminiPackages] : [] }
  return { funLocked: true, allow: [] }
}

/**
 * 算这一刻的全天状态。
 * @returns {{
 *   state:string, priority:number, until:number, funLocked:boolean, allow:string[],
 *   backTo:string, followup:object, agenda:string,
 *   layers:{kind:string, priority:number, funLocked:boolean, until:number, why:string, reason?:string}[],
 *   nextChangeAt:number
 * }}
 */
export function resolveState(input) {
  const f = factsOf(input)
  const rows = [...STATE_TABLE].sort((a, b) => b.priority - a.priority)
  const hit = rows.find((r) => r.enter.some((c) => CONDITIONS[c] && CONDITIONS[c](f))) || STATE_TABLE[STATE_TABLE.length - 1]
  const lock = lockOf(hit.id, f)
  const layers = []
  // 追问锁是临时层：主状态已经被它占了（followup-other），这里把底层状态记成 backTo。
  const overlayOther = hit.id === 'followup' && f.followup.active
  const backTo = overlayOther ? baseState(f) : hit.id
  const kind = hit.lock === 'followup' ? 'followup' : hit.lock
  if (kind !== 'open' && kind !== 'standby') {
    layers.push({ kind, priority: hit.priority, funLocked: true, until: untilOf(hit.id, f), why: LOCK_WHY[kind] || '', ...(kind === 'followup' ? { reason: f.followup.reason } : {}) })
  }
  // 并存的更高层（裁判只看 layers[0]）：追问锁叠在冲刺/共脑/吃饭之上时也要算进来。
  for (const extra of EXTRA_LAYERS) {
    if (extra === hit.lock) continue
    if (!CONDITIONS[extra] || !CONDITIONS[extra](f)) continue
    const k = extra === 'brain' ? 'brain' : extra === 'sprint' ? 'sprint' : extra
    if (layers.some((l) => l.kind === k)) continue
    layers.push({ kind: k, priority: extra === 'sprint' ? 70 : extra === 'brain' ? 70 : 80, funLocked: true, until: untilOf(extra, f), why: LOCK_WHY[k] || '' })
  }
  if (!layers.length && kind === 'standby') {
    layers.push({ kind: 'standby', priority: 50, funLocked: true, until: untilOf('standby', f), why: LOCK_WHY.standby })
  }
  layers.sort((a, b) => b.priority - a.priority)
  const top = layers[0] || null
  return {
    state: hit.id,
    priority: hit.priority,
    until: untilOf(hit.id, f),
    funLocked: lock.funLocked || (top ? top.funLocked : false),
    allow: lock.allow,
    backTo,
    followup: f.followup,
    agenda: hit.agenda,
    layers,
    nextChangeAt: nextChange(f, layers),
  }
}

/** 追问锁期间底层本来是什么主状态（结束后回去）。 */
function baseState(f) {
  const rows = [...STATE_TABLE].sort((a, b) => b.priority - a.priority).filter((r) => r.id !== 'followup')
  const hit = rows.find((r) => r.enter.some((c) => CONDITIONS[c] && CONDITIONS[c](f)))
  return hit ? hit.id : 'standby'
}

const EXTRA_LAYERS = ['sprint', 'brain']

/**
 * 下一次"换状态"的时刻：手机和面板据此安排下一次问 /plan，不用一直轮询。
 * 取所有还未来临的边界里最近的一个。
 */
export function nextChange(f, layers = []) {
  const marks = []
  const w = wallConfig(f.config)
  for (const m of w.meals) {
    if (m.from * 60_000 + f.midnight > f.now) marks.push(f.midnight + m.from * 60_000)
    if (m.to * 60_000 + f.midnight > f.now) marks.push(f.midnight + m.to * 60_000)
  }
  marks.push(f.midnight + REVIEW_FROM * 60_000)
  marks.push(f.midnight + NIGHT_LOCK_FROM * 60_000)
  marks.push(f.midnight + NIGHT_LOCK_TO * 60_000)
  if (f.sprint.active) marks.push(f.sprint.until)
  if (f.brain.active) marks.push(f.brain.until)
  if (f.relax) marks.push(f.midnight + f.relax.to * 60_000)
  if (f.followup.active) marks.push(Number(f.followup.until) || 0)
  for (const l of layers) if (Number(l.until) > f.now) marks.push(Number(l.until))
  marks.push(f.endOfDay)
  const future = marks.filter((m) => Number.isFinite(m) && m > f.now)
  return future.length ? Math.min(...future) : f.endOfDay
}

/** 主状态 -> 拼进人设的一句话（两姐妹按主状态切议程）。 */
export function agendaLine(state) {
  const row = STATE_TABLE.find((r) => r.id === state)
  return row ? row.agenda : ''
}
