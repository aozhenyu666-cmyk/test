// dsh-go-companion · 追问锁、裁决与待办义务（第三轮 C3）
//
// 第三轮把"过关"从聊天里搬出来：**墙**开追问锁，两姐妹只能提交裁决（submit_verdict）。
// 裁决只有格式，没有内容判断——满意不满意由姐妹定，那是用户的决定；墙只校验格式，
// 不合格按"不满意"处理并把原因回给姐妹。
//
// 追问锁是叠在主状态上的临时层：单次最长 30 分钟（可配），到点墙自己结束全屏拦截、
// 回到原主状态、账本记"超时"、扣分，并把没过关的事挂成待办义务。义务不再定时自动弹，
// 只由用户的动作触发（下次打开被拦的 App / 下次违抗）。
//
// 纯函数：不读文件、不取系统时间，now、cap、penalty 都由调用方给。

/** 追问锁单次上限：30 分钟（config.followupCapMin 可改）。 */
export const FOLLOWUP_CAP_MS = 30 * 60_000
/** 超时扣多少信用分（config.followupPenalty 可改）。 */
export const FOLLOWUP_PENALTY = 5
/** 待命默认锁的计时：10 分钟，聊天不重置。 */
export const STANDBY_LOCK_MS = 10 * 60_000

/** 待命一直没动：到点了，该开追问锁了（只有换出待命才重置，聊天不算动）。 */
export function standbyDue(since, now) {
  const s = Number(since) || 0
  return s > 0 && now - s >= STANDBY_LOCK_MS
}

/** 裁决 kind：和账本、笔记库同步用的字符串。DeepSeek 的 D3 按 state:'verdict' 扫。 */
export const VERDICT_KINDS = ['morning', 'review', 'defiance', 'standby', 'gemini', 'evening']

/** 姐妹可能照着文档里的中文写 kind，这里统一收。 */
const KIND_ALIAS = {
  晨间立项: 'morning', 晨间: 'morning', morning: 'morning',
  验收: 'review', review: 'review',
  违抗: 'defiance', defiance: 'defiance',
  待命超时: 'standby', 待命: 'standby', standby: 'standby',
  连麦回流: 'gemini', 连麦: 'gemini', gemini: 'gemini',
  晚间对账: 'evening', 对账: 'evening', evening: 'evening',
}

/** 追问锁的 reason 就是裁决的 kind（同一个词，两处对齐）。 */
export function normalizeKind(v) {
  const s = String(v == null ? '' : v).trim()
  if (!s) return ''
  if (VERDICT_KINDS.includes(s)) return s
  return KIND_ALIAS[s] || KIND_ALIAS[s.toLowerCase()] || ''
}

const textOf = (v, max) => String(v == null ? '' : v).trim().slice(0, max)

/**
 * 晨间立项的 tasks：1–3 项，每项都要有名称和完成物。
 * 沿用 daily.js 的字段名（task / evidence），晚上对账按完成物逐项找证据。
 */
function cleanTasks(raw) {
  if (!Array.isArray(raw)) return null
  const list = raw
    .map((t) => ({ task: textOf(t?.task ?? t?.name, 80), evidence: textOf(t?.evidence ?? t?.done ?? t?.完成物, 120) }))
    .filter((t) => t.task && t.evidence)
  if (list.length < 1 || list.length > 3) return null
  return list
}

/**
 * 校验一次裁决。墙只校验格式：
 *   kind 必须是六种之一；satisfied 必须是布尔；
 *   满意时必须给 summary 和 nextStep；晨间立项还要带 1–3 项 tasks（每项有名称和完成物）。
 * 不合格 → { ok:false, satisfied:false, code, text }，调用方按"不满意"处理并把 text 回给姐妹。
 */
export function checkVerdict(args) {
  const a = args && typeof args === 'object' ? args : {}
  const kind = normalizeKind(a.kind)
  if (!kind) {
    return { ok: false, satisfied: false, code: 'bad-kind', text: 'kind 认不出来。只能是 ' + VERDICT_KINDS.join(' / ') + ' 之一（晨间立项、验收、违抗、待命超时、连麦回流、晚间对账）。' }
  }
  if (typeof a.satisfied !== 'boolean') {
    return { ok: false, satisfied: false, kind, code: 'bad-satisfied', text: 'satisfied 必须是 true 或 false（不能省略、不能用字符串）。' }
  }
  const summary = textOf(a.summary, 300)
  const nextStep = textOf(a.nextStep ?? a.next_step, 200)
  if (a.satisfied === true) {
    if (!summary) return { ok: false, satisfied: false, kind, code: 'no-summary', text: '满意时必须写 summary：为什么算过关。' }
    if (!nextStep) return { ok: false, satisfied: false, kind, code: 'no-next', text: '满意时必须写 nextStep：下一步做什么。' }
  }
  let tasks = null
  if (kind === 'morning') {
    tasks = cleanTasks(a.tasks)
    if (a.satisfied === true && !tasks) {
      return { ok: false, satisfied: false, kind, code: 'bad-tasks', text: '晨间立项要带 1–3 项 tasks，每项都有名称（task）和完成物（evidence）。' }
    }
  }
  return {
    ok: true,
    kind,
    satisfied: a.satisfied,
    summary,
    nextStep,
    tasks: tasks || undefined,
    longTerm: a.longTerm === true || a.long_term === true || undefined,
  }
}

/**
 * 开追问锁。同一时刻只有一把：换原因就重新计时（新的一层盖上旧的）；
 * 同原因重复开保持原来的 since，30 分钟上限不被"每问一句就续命"绕过去。
 *
 * @param {object|null} lock 现有锁 {reason, question, since, until}
 * @returns {{reason, question, since, until}}
 */
export function openLock(lock, { reason, question, now, capMs = FOLLOWUP_CAP_MS } = {}) {
  const at = Number(now) || 0
  const cap = Math.max(60_000, Number(capMs) || FOLLOWUP_CAP_MS)
  const r = normalizeKind(reason) || String(reason || 'standby')
  const q = textOf(question, 300)
  const cur = lock && typeof lock === 'object' ? lock : null
  if (cur && Number(cur.until) > at && cur.reason === r) {
    return { ...cur, question: q || cur.question || '' }
  }
  return { reason: r, question: q, since: at, until: at + cap }
}

export function lockActive(lock, now) {
  return Boolean(lock && Number(lock.until) > Number(now) && Number(lock.since) > 0)
}

/** 到点还没裁决：该结束拦截、记账、挂义务了。 */
export function lockExpired(lock, now) {
  return Boolean(lock && Number(lock.until) > 0 && Number(lock.until) <= Number(now))
}

/** 关锁：盖上 closedAt，保留 lastReason 给快照和"结束后回原主状态"用。 */
export function closeLock(lock, { now, by = 'verdict' } = {}) {
  if (!lock) return null
  return { ...lock, until: 0, closedAt: Number(now) || 0, closedBy: String(by).slice(0, 20) }
}

/**
 * 超时后挂什么义务（任务书那张表，写成数据）。
 *   reask: 'locked-app' 下次打开被拦的 App 时再问；'defiance' 下次违抗时再问；'none' 不再追问。
 */
export const OBLIGATION_TABLE = {
  morning: { text: '当天不能放风，直到补完立项', reask: 'locked-app', blocksFun: true },
  review: { text: '这一轮不放风', reask: 'none' },
  evening: { text: '今晚不开申请窗口', reask: 'none', closeApplyWindow: true },
  defiance: { text: '只记账扣分', reask: 'defiance' },
  standby: { text: '只记账扣分', reask: 'defiance' },
  gemini: { text: '只记账扣分', reask: 'defiance' },
}

/** 超时挂的义务。同原因只留最新一条（不堆一摞一样的）。 */
export function obligationOf(reason, now) {
  const kind = normalizeKind(reason) || 'standby'
  const spec = OBLIGATION_TABLE[kind] || OBLIGATION_TABLE.standby
  return { kind, text: spec.text, reask: spec.reask, blocksFun: spec.blocksFun === true, createdAt: Number(now) || 0 }
}

export function addObligation(list, ob) {
  const rest = (Array.isArray(list) ? list : []).filter((o) => o && o.kind !== ob.kind)
  return [...rest, ob].slice(-12)
}

/** 过关了：这条义务消掉。 */
export function clearObligation(list, kind) {
  const k = normalizeKind(kind) || kind
  return (Array.isArray(list) ? list : []).filter((o) => o && o.kind !== k)
}

/** 今天还压着一件没补完的立项：放风不发放。 */
export function funBlockedBy(list) {
  return (Array.isArray(list) ? list : []).some((o) => o && o.blocksFun === true)
}

/**
 * 由用户的动作触发再问：只在有匹配义务、且没被问过的时候开锁。
 * 不定时自动弹出，避免变成追着人跑。
 *
 * @param {'locked-app'|'defiance'} trigger
 * @returns {{ask:object|null, obligations:array}}
 */
export function takeReask(list, trigger, now) {
  const all = Array.isArray(list) ? list : []
  const want = String(trigger || '')
  if (want !== 'locked-app' && want !== 'defiance') return { ask: null, obligations: all }
  const idx = all.findIndex((o) => o && o.reask === want && !o.askedAt)
  if (idx < 0) return { ask: null, obligations: all }
  const ask = all[idx]
  // 问过就记上：一条义务只问一次，不会每次开 App 都堵他。
  const next = all.map((o, i) => (i === idx ? { ...o, askedAt: Number(now) || 1 } : o))
  return { ask, obligations: next }
}

/** 快照里的义务：只给手机看的字段，账本细节不外泄。 */
export function obligationsView(list, now) {
  return (Array.isArray(list) ? list : [])
    .filter((o) => o && o.kind)
    .map((o) => ({
      kind: o.kind,
      text: String(o.text || ''),
      reask: o.reask || 'none',
      createdAt: Number(o.createdAt) || 0,
      ageMs: Math.max(0, (Number(now) || 0) - (Number(o.createdAt) || 0)),
    }))
}

/** 追问锁进快照的形状（和 config 里存的是同一份，不另存）。 */
export function followupView(lock, now) {
  const l = lock && typeof lock === 'object' ? lock : null
  const active = lockActive(l, now)
  return {
    active,
    reason: active ? l.reason : '',
    currentQuestion: active ? String(l.question || '') : '',
    since: active ? Number(l.since) : 0,
    until: active ? Number(l.until) : 0,
  }
}
