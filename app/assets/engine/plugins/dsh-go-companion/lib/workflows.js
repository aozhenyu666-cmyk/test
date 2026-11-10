// dsh-go-companion · 姐妹自建工作流的护栏（第六轮 D）
//
// 她可以自己提议、自己搭新工作流，但护栏必须在**能力层**拦住，不能靠提示词劝。
// 五条硬规矩（任务书 §2.3）：
//   1. 每周最多提议 1 条，同时在跑 ≤3 条，满了先停再加；
//   2. 提议必须写清六项（解决什么问题 + 账本证据 + 什么时候触发 + 对用户的要求 +
//      可数的成功指标 + 7 天后怎么判），缺一项就否决，不做"缺的补默认"；
//   3. 用户点「试」才启用；不点不启用、不重复提；
//   4. 7 天没有可数效果自动停用；转正后每 30 天复核，掉下去同样停；
//   5. 碰不到墙：调墙、力度、信用分、权限、规则文件一律拒；不读私密目录；
//      不新增任何要用户手动做的动作。
//
// 为什么要有上限（原文理由）：为解决"简单流程跑不动"而造的系统，
// 日维护成本必须低于那个简单流程。不设上限的自动扩展会把体系带回"越做越复杂然后停摆"。

import { weekKey } from './wall.js'

/** 每周最多提议几条。 */
export const WORKFLOW_WEEK_MAX = 1

/** 同时在跑（试启用 + 转正）上限。 */
export const WORKFLOW_RUN_MAX = 3

/** 试启用窗口：这么久没有可数效果就自动停。 */
export const TRIAL_MS = 7 * 86_400_000

/** 转正后的复核周期。 */
export const REVIEW_MS = 30 * 86_400_000

/** 提议必须写清的六项。改这个数组等于改协议。 */
export const REQUIRED_FIELDS = ['problem', 'evidence', 'trigger', 'ask', 'metric', 'judge']

export const FIELD_LABEL = {
  problem: '解决什么问题',
  evidence: '账本证据（引哪条、哪个数）',
  trigger: '什么时候触发',
  ask: '对用户的要求',
  metric: '可数的成功指标',
  judge: '7 天后怎么判',
}

/** 工作流能用的能力：白名单，不在里面的一律拒（默认关门，不是默认开门）。 */
export const WORKFLOW_ALLOW = [
  'companion_topic',
  'sister_note',
  'notes_vault',
  'vault_search',
  'vault_recent_notes',
  'kb_write',
  'gemini_log',
]

/**
 * 墙这一侧的能力：明着写出来，是为了让「被拒绝」这条可测、可查，
 * 而不是靠"白名单里没有"这种间接推断。
 */
export const WALL_CAPABILITIES = [
  'submit_to_wall',
  'request_relax',
  'tighten_today',
  'block_apps',
  'grant_break',
  'submit_verdict',
  'daily_plan',
  'daily_review',
  'start_task_mode',
  'end_task_mode',
  'dafeiyu_control',
  'wall',
  'force',
  'credit',
  'permission',
  'rules',
]

const WALL = new Set(WALL_CAPABILITIES)

/** 私密目录：连路径都不许出现，识别到就拒。 */
const PRIVATE_DIR_RE = /(^|[\\/])(Android[\\/]data|data[\\/]data|data[\\/]obb|obb|\.ssh|DCIM|[\\/]private)([\\/]|$)/i

/** 要用户手动做的动作：这句话一说就说明白"不手动交题"那条红线又破了。 */
const MANUAL_RE = /打卡|录入|填表|拍照|签到|截图|手动|记得去|记得点|每天点/

/** 单项内容上限（防止把上下文挤爆）。 */
const FIELD_MAX = 200

/**
 * 能力层闸门。工作流的每一步执行前都要过这里。
 * @returns {{ok:boolean, code?:string, capability?:string}}
 */
export function workflowGuard(capability, args) {
  const name = String(capability || '').trim()
  if (!name) return { ok: false, code: 'no-capability' }
  if (WALL.has(name)) return { ok: false, code: 'wall-forbidden', capability: name }
  const p = args && typeof args === 'object' && typeof args.path === 'string' ? args.path : ''
  if (p && PRIVATE_DIR_RE.test(p)) return { ok: false, code: 'private-path', capability: name }
  if (!WORKFLOW_ALLOW.includes(name)) return { ok: false, code: 'not-allowed', capability: name }
  return { ok: true, capability: name }
}

/**
 * 校验一条提议。缺一项就否决；`uses` 里只要有一条被闸门拒，整条否决。
 * @returns {{ok:boolean, workflow?:object, code?:string, missing?:string[], rejected?:object[]}}
 */
export function normalizeWorkflow(raw, now) {
  if (!raw || typeof raw !== 'object') return { ok: false, code: 'not-an-object' }
  const w = {}
  const missing = []
  for (const key of REQUIRED_FIELDS) {
    const v = typeof raw[key] === 'string' ? raw[key].trim() : ''
    if (!v) missing.push(FIELD_LABEL[key])
    else w[key] = v.length > FIELD_MAX ? v.slice(0, FIELD_MAX) + '…' : v
  }
  if (missing.length) return { ok: false, code: 'field-missing', missing }
  // 不新增任何要用户手动做的动作。
  if (MANUAL_RE.test(w.ask)) return { ok: false, code: 'manual-action' }
  // 能力白名单：一条不过就整条否决，并把拒了哪条报出来。
  const uses = Array.isArray(raw.uses) ? raw.uses.slice(0, 8).map((x) => String(x)) : []
  const rejected = []
  for (const cap of uses) {
    const g = workflowGuard(cap)
    if (!g.ok) rejected.push({ capability: cap, code: g.code })
  }
  if (rejected.length) return { ok: false, code: 'capability-refused', rejected }
  return {
    ok: true,
    workflow: {
      id: String(raw.id || 'w' + Number(now).toString(36)).slice(0, 40),
      ...w,
      uses,
      state: 'proposed',
      at: now,
    },
  }
}

/** 账本里这条工作流的确切效果记录（可数指标，必须带 count > 0）。 */
function effectsOf(entries, id, since) {
  let n = 0
  for (const e of entries || []) {
    if (e?.state !== 'workflow-effect' || e.workflowId !== id) continue
    if (Number(e.at) < since) continue
    if (Number(e.count) > 0) n += 1
  }
  return n
}

/** 这周提了几条（按 ledger 的 workflow-proposed 数，和自然周对齐）。 */
export function proposalsThisWeek(entries, now, tz) {
  const key = weekKey(now, tz)
  let n = 0
  for (const e of entries || []) {
    if (e?.state !== 'workflow-proposed') continue
    if (weekKey(Number(e.at), tz) === key) n += 1
  }
  return n
}

/** 现在还能不能加（配额）。 */
export function workflowQuota(workflows, entries, now, tz) {
  const weekUsed = proposalsThisWeek(entries, now, tz)
  const running = (workflows || []).filter((w) => w?.state === 'trial' || w?.state === 'running').length
  return {
    weekUsed,
    weekMax: WORKFLOW_WEEK_MAX,
    weekOk: weekUsed < WORKFLOW_WEEK_MAX,
    running,
    runningMax: WORKFLOW_RUN_MAX,
    runningOk: running < WORKFLOW_RUN_MAX,
    ok: weekUsed < WORKFLOW_WEEK_MAX && running < WORKFLOW_RUN_MAX,
  }
}

/**
 * 提议一条。配额满了先拒（「满了先停再加」）。
 * @returns {{ok:boolean, code?:string, workflow?:object, quota:object}}
 */
export function workflowPropose(workflows, entries, raw, now, tz) {
  const quota = workflowQuota(workflows, entries, now, tz)
  if (!quota.weekOk) return { ok: false, code: 'week-quota', quota }
  if (!quota.runningOk) return { ok: false, code: 'running-quota', quota }
  const norm = normalizeWorkflow(raw, now)
  if (!norm.ok) return { ok: false, code: norm.code, missing: norm.missing, rejected: norm.rejected, quota }
  // 同一个问题不重复提。
  const dup = (workflows || []).find((w) => w?.problem === norm.workflow.problem && w.state !== 'stopped')
  if (dup) return { ok: false, code: 'duplicate', quota, workflow: dup }
  return { ok: true, workflow: norm.workflow, quota }
}

/**
 * 到期处理：试启用 7 天没可数效果就停；转正的 30 天复核，掉下去同样停。
 * 一次只处理时间到点的，不提前、不猜。
 * @returns {{next:object[], events:object[]}}
 */
export function workflowSweep(workflows, entries, now) {
  const next = []
  const events = []
  for (const w of workflows || []) {
    if (!w || typeof w !== 'object') continue
    if (w.state === 'trial' && Number(w.trialAt) > 0 && now - Number(w.trialAt) >= TRIAL_MS) {
      if (effectsOf(entries, w.id, Number(w.trialAt)) > 0) {
        next.push({ ...w, state: 'running', promotedAt: now })
        events.push({ id: w.id, kind: 'promoted', at: now, reason: 'trial-effect' })
      } else {
        next.push({ ...w, state: 'stopped', stoppedAt: now, stopReason: 'no-effect-7d' })
        events.push({ id: w.id, kind: 'stopped', at: now, reason: 'no-effect-7d' })
      }
      continue
    }
    if (w.state === 'running' && Number(w.promotedAt) > 0 && now - Number(w.promotedAt) >= REVIEW_MS) {
      if (effectsOf(entries, w.id, Number(w.promotedAt)) > 0) {
        next.push({ ...w, promotedAt: now })
        events.push({ id: w.id, kind: 'reviewed', at: now, reason: 'effect-kept' })
      } else {
        next.push({ ...w, state: 'stopped', stoppedAt: now, stopReason: 'no-effect-30d' })
        events.push({ id: w.id, kind: 'stopped', at: now, reason: 'no-effect-30d' })
      }
      continue
    }
    next.push(w)
  }
  return { next, events }
}

/** 用户点「试」/ 手动停：只在合法状态之间走，别的一律不动。 */
export function workflowAct(workflows, id, action, now) {
  const list = workflows || []
  const i = list.findIndex((w) => w?.id === id)
  if (i < 0) return { ok: false, code: 'not-found' }
  const w = list[i]
  const next = list.slice()
  if (action === 'trial') {
    if (w.state !== 'proposed') return { ok: false, code: 'bad-state', state: w.state }
    next[i] = { ...w, state: 'trial', trialAt: now }
    return { ok: true, workflow: next[i], workflows: next }
  }
  if (action === 'stop') {
    if (w.state === 'stopped') return { ok: false, code: 'bad-state', state: w.state }
    next[i] = { ...w, state: 'stopped', stoppedAt: now, stopReason: 'user' }
    return { ok: true, workflow: next[i], workflows: next }
  }
  if (action === 'effect') {
    // 记一次可数效果：不在这里落账本，交给调用方 record，保持单一写入点。
    return { ok: true, workflow: w }
  }
  return { ok: false, code: 'bad-action', action: String(action || '') }
}

/** 面板：几条在跑、配额剩多少、每条还差多久到期。 */
export function workflowStatus(workflows, entries, now, tz) {
  const list = (workflows || []).filter((w) => w && typeof w === 'object')
  const quota = workflowQuota(list, entries, now, tz)
  const items = list.map((w) => {
    let deadline = 0
    if (w.state === 'trial') deadline = Number(w.trialAt) + TRIAL_MS
    else if (w.state === 'running') deadline = Number(w.promotedAt) + REVIEW_MS
    return {
      id: w.id,
      problem: w.problem,
      metric: w.metric,
      state: w.state,
      at: Number(w.at) || 0,
      deadline: deadline || null,
      leftMs: deadline ? Math.max(0, deadline - now) : null,
      stopReason: w.stopReason || null,
    }
  })
  return {
    count: items.length,
    proposed: list.filter((w) => w.state === 'proposed').length,
    trial: list.filter((w) => w.state === 'trial').length,
    running: list.filter((w) => w.state === 'running').length,
    stopped: list.filter((w) => w.state === 'stopped').length,
    quota,
    allow: WORKFLOW_ALLOW.slice(),
    deny: WALL_CAPABILITIES.slice(),
    items,
  }
}
