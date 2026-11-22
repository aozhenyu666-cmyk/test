// dsh-go-companion · P1 计划模态（第八轮）
//
// 计划模态是一个主状态（进 machine.js 的表，优先级仅次于深夜锁）。分工是硬的：
// **候选和规则全在代码里，模型只负责把计划说出来**——所以这个模块没有一句模型调用。
//
// 出口只有一个：一份计划生效。没有「等下再说」，也没有「你自己看着办」。

import { DAY_WORKDAY, inWorkWindow, windowsOf } from './settings.js'

/** 他最多能改几次（接受不算改）。 */
export const PLAN_EDIT_MAX = 2
/** 10 分钟不确认，草案自动生效。 */
export const PLAN_CONFIRM_MS = 10 * 60_000
/** 计划模态进入后 5 秒内必须开口。 */
export const PLAN_SPEAK_MS = 5_000
/** 工作时段内无局的默认上限（分钟）。 */
export const PLAN_IDLE_MIN = 5

/** 候选来源，数字就是优先级。 */
export const SOURCE = Object.freeze({ window: 1, missed: 2, rule: 3, stance: 4, note: 5, carry: 6 })

const CN = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十']

/** 8 → 八；13 → 13。局数不会超过十，超了就用数字，别硬编。 */
export function cn(n) {
  const v = Math.round(Number(n) || 0)
  return v >= 0 && v <= 10 ? CN[v] : String(v)
}

/** 540 → 9:00。 */
export function clock(min) {
  const m = Math.max(0, Math.round(Number(min) || 0))
  const h = Math.floor(m / 60)
  const mm = m % 60
  return h + ':' + (mm < 10 ? '0' + mm : String(mm))
}

/** 工作时段内两个局的间隔。施工书示例是 9:00 / 10:30 / 14:00 / 16:00，约 90 分钟一局。 */
export const SLOT_MIN = 90
/** 一天最多排几局：再多就不是计划，是流水账。 */
export const PLAN_MAX_BOUTS = 6

/** 固定局：工作时段里按 SLOT_MIN 排槽位，局型按 学习→执行→思考→外出 轮着来。 */
const ROTATION = ['study', 'execute', 'thinking', 'outing', 'design']

function fixedBouts(settings, kind) {
  const out = []
  for (const w of windowsOf(settings, kind)) {
    for (let minute = w.from; minute < w.to; minute += SLOT_MIN) {
      const protocolId = ROTATION[out.length % ROTATION.length]
      const budget = settings.budget[protocolId] || 25
      // 时段比预算还短就压到时段剩余长度，别排一个做不完的局。
      const span = w.to - minute
      out.push({
        id: 'w' + minute,
        source: SOURCE.window,
        protocolId,
        type: protocolId === 'thinking' ? '思维' : '行为',
        title: titleOf(protocolId),
        minute,
        budget: Math.max(5, Math.min(budget, span)),
        why: '作息模板里的固定局',
      })
    }
  }
  return out
}

function titleOf(protocolId) {
  const names = { study: '学习局', execute: '执行局', thinking: '思考局', outing: '外出局', design: '设计局', rest: '休息局' }
  return names[protocolId] || protocolId + '局'
}

function asList(v) {
  return Array.isArray(v) ? v.filter((x) => x && typeof x === 'object') : []
}

/**
 * 生成候选，按来源优先级排好。
 * @param {{settings:object, kind:string, missed?:object[], rules?:object[], stances?:object[],
 *          notes?:object[], carry?:object[], min?:number, now?:number}} input
 */
export function candidates(input) {
  const settings = input.settings
  const kind = input.kind || DAY_WORKDAY
  const list = [...fixedBouts(settings, kind)]

  // 2. 昨天空局或未通过的局：排到最前面去补。
  for (const b of asList(input.missed)) {
    list.push({
      id: 'm' + (b.id || list.length),
      source: SOURCE.missed,
      protocolId: String(b.protocolId || 'study'),
      type: String(b.type || '行为'),
      title: titleOf(String(b.protocolId || 'study')),
      minute: Number(b.minute) || 0,
      budget: settings.budget[String(b.protocolId || 'study')] || 25,
      why: b.empty ? '昨天空了一次' : '昨天没过',
    })
  }
  // 3. 到期的「一条规则」复考。
  for (const r of asList(input.rules)) {
    list.push({
      id: 'r' + (r.id || list.length),
      source: SOURCE.rule,
      protocolId: 'thinking',
      type: '思维',
      title: '规则复考',
      minute: Number(r.minute) || 0,
      budget: settings.budget.thinking || 25,
      why: '一条规则到期了，要复考：' + String(r.title || r.id || ''),
    })
  }
  // 4. 立场账本里未到期的硬立场对应的事。
  for (const s of asList(input.stances)) {
    list.push({
      id: 's' + (s.id || list.length),
      source: SOURCE.stance,
      protocolId: 'execute',
      type: '行为',
      title: '硬立场',
      minute: Number(s.minute) || 0,
      budget: settings.budget.execute || 30,
      why: '他自己立的硬立场：' + String(s.title || s.text || s.id || ''),
    })
  }
  // 5. 笔记库里超过 3 天没动的进行中笔记。
  for (const n of asList(input.notes)) {
    list.push({
      id: 'n' + (n.id || list.length),
      source: SOURCE.note,
      protocolId: 'execute',
      type: '行为',
      title: '续笔记',
      minute: Number(n.minute) || 0,
      budget: settings.budget.execute || 30,
      why: String(n.title || n.id || '') + ' 已经 ' + (Number(n.staleDays) || 3) + ' 天没动',
    })
  }
  // 6. 暗思考的 carry。
  for (const c of asList(input.carry)) {
    list.push({
      id: 'c' + (c.id || list.length),
      source: SOURCE.carry,
      protocolId: 'design',
      type: '行为',
      title: '暗思考跟进',
      minute: Number(c.minute) || 0,
      budget: settings.budget.design || 30,
      why: '暗思考挂着的事：' + String(c.title || c.text || c.id || ''),
    })
  }
  return list.sort((a, b) => a.source - b.source || a.minute - b.minute)
}

/**
 * 草案：取够今天的局数，并且保证包含下限要求的思维局。
 * @returns {{items:object[], edits:number, accepted:boolean, at:number, pool:object[]}}
 */
export function draftOf(input) {
  const settings = input.settings
  const kind = input.kind || DAY_WORKDAY
  const pool = candidates({ ...input, settings, kind })
  const target = Math.max(1, kind === DAY_WORKDAY ? settings.minBouts : Math.min(settings.minBouts, 2))
  // 草案排满一天的槽位，下限只是他能删到哪儿。草案正好卡在下限的话，
  // 「删掉一局」永远被拒，这个编辑入口就是死的。
  const take = Math.min(pool.length, Math.max(target, PLAN_MAX_BOUTS))
  let items = pool.slice(0, take)
  // 思维局不够：从剩下的候选里拉一个进来，替掉最后一个非思维局。
  const need = kind === DAY_WORKDAY ? settings.minThinking : 0
  while (countThinking(items) < need) {
    const pick = pool.find((c) => c.type === '思维' && !items.includes(c))
    if (!pick) break
    const dropAt = items.map((c) => c.type === '思维').lastIndexOf(false)
    if (dropAt < 0) break
    items = items.map((c, i) => (i === dropAt ? pick : c))
  }
  items.sort((a, b) => a.minute - b.minute)
  return {
    items,
    edits: 0,
    accepted: false,
    at: Number(input.now) || 0,
    pool: pool.filter((c) => !items.includes(c)),
  }
}

function countThinking(items) {
  return items.filter((c) => c.type === '思维').length
}

/**
 * 改一次草案。他就是在这里最多动两次手。
 * @param {{items:object[], edits:number, pool?:object[]}} draft
 * @param {{op:'accept'|'swap'|'rearrange'|'drop'|'add', id?:string, bout?:object}} edit
 * @returns {{ok:boolean, draft:object, why?:string}}
 */
export function applyEdit(draft, edit, ctx) {
  const settings = ctx.settings
  const kind = ctx.kind || DAY_WORKDAY
  const cur = { items: [...(draft.items || [])], edits: Number(draft.edits) || 0, accepted: false, at: draft.at, pool: [...(draft.pool || [])] }
  const op = edit && edit.op

  if (op === 'accept') {
    cur.accepted = true
    return { ok: true, draft: cur }
  }
  if (op === 'rearrange') {
    // 「重排」不占编辑次数：它是重新出草案，不是改草案。
    const fresh = draftOf({ ...ctx, settings, kind, now: ctx.now })
    return { ok: true, draft: fresh }
  }
  if (cur.edits >= PLAN_EDIT_MAX) {
    return { ok: false, draft, why: '改的次数用完了（最多 ' + PLAN_EDIT_MAX + ' 次），现在只能接受' }
  }

  if (op === 'drop') {
    const at = cur.items.findIndex((c) => c.id === edit.id)
    if (at < 0) return { ok: false, draft, why: '草案里没有这一局' }
    const next = cur.items.filter((_, i) => i !== at)
    if (kind === DAY_WORKDAY && next.length < settings.minBouts) {
      return { ok: false, draft, why: '工作日至少 ' + settings.minBouts + ' 局，删了这一局就不够了' }
    }
    if (countThinking(next) < (kind === DAY_WORKDAY ? settings.minThinking : 0)) {
      return { ok: false, draft, why: '至少要留 ' + settings.minThinking + ' 个思维局' }
    }
    cur.items = next
    cur.edits += 1
    return { ok: true, draft: cur }
  }

  if (op === 'swap') {
    const at = cur.items.findIndex((c) => c.id === edit.id)
    if (at < 0) return { ok: false, draft, why: '草案里没有这一局' }
    const pick = cur.pool[0]
    if (!pick) return { ok: false, draft, why: '没有别的局可以换了' }
    const old = cur.items[at]
    // 换进来的局沿用原来的时段，不然一天的顺序会乱。
    cur.items = cur.items.map((c, i) => (i === at ? { ...pick, minute: old.minute } : c))
    cur.pool = cur.pool.slice(1).concat([{ ...old, minute: 0 }])
    cur.edits += 1
    return { ok: true, draft: cur }
  }

  if (op === 'add') {
    const b = edit.bout && typeof edit.bout === 'object' ? edit.bout : null
    if (!b) return { ok: false, draft, why: '没说加哪一局' }
    const protocolId = String(b.protocolId || 'execute')
    cur.items = [...cur.items, {
      id: 'a' + cur.items.length + '-' + protocolId,
      source: SOURCE.carry,
      protocolId,
      type: protocolId === 'thinking' ? '思维' : '行为',
      title: titleOf(protocolId),
      minute: Number(b.minute) || 0,
      budget: settings.budget[protocolId] || 25,
      why: '他自己加的',
    }].sort((x, y) => x.minute - y.minute)
    cur.edits += 1
    return { ok: true, draft: cur }
  }

  return { ok: false, draft, why: '不认识的改法' }
}

/** 10 分钟不确认就算自动生效。 */
export function planTimeout(draft, now) {
  const at = (Number(draft?.at) || 0) + PLAN_CONFIRM_MS
  const t = Number(now) || 0
  return { due: t >= at, at }
}

/** 计划模态禁止的开放问句：命中就重写。 */
const BANNED = ['你今天想做什么', '你觉得呢', '要不要', '看你']

export function forbidden(text) {
  const s = String(text || '')
  const hit = BANNED.find((b) => s.includes(b))
  if (hit) return hit
  // 任何不含具体局的问句都不行：问号结尾但没提到「局」，算开放问句。
  if (/[？?]/.test(s) && !s.includes('局')) return 'open-question'
  return ''
}

/**
 * 第一句计划。必须是一份计划，不是问题。
 * 形如「今天四局。9:00学习局，25分钟。…昨天学习局空了一次，今天它排第一。」
 */
export function planSentence(draft, opts = {}) {
  const items = Array.isArray(draft?.items) ? draft.items : []
  if (!items.length) return ''
  const head = '今天' + cn(items.length) + '局。'
  const body = items
    .map((c) => clock(c.minute) + c.title + '，' + c.budget + '分钟' + (c.why ? '（' + c.why + '）' : ''))
    .join('；')
  const missed = opts.missedTitle ? '昨天' + opts.missedTitle + '空了一次，今天它排第一。' : ''
  return head + body + '。' + missed
}

/** 命中禁用说法就换成计划本身——重写不是屏蔽，他照样听得到该听的话。 */
export function rewrite(text, draft, opts = {}) {
  if (!forbidden(text)) return String(text || '')
  return planSentence(draft, opts)
}

/**
 * 五个进入条件，任一成立就进。判定全在这里，调用方只给事实。
 * @param {{now:number, kind:string, settings:object, min:number, screenOn?:boolean,
 *          nightEndedAt?:number, wokeAt?:number, inBout?:boolean, queueEmpty?:boolean,
 *          closedAt?:number, noBoutSince?:number, escalateEndedAt?:number, saidRearrange?:boolean}} f
 * @returns {{enter:boolean, reason:string, why?:string}}
 */
export function enterReason(f) {
  const settings = f.settings
  const kind = f.kind || DAY_WORKDAY
  const min = Number(f.min) || 0
  const now = Number(f.now) || 0
  const work = inWorkWindow(settings, kind, min)

  // 1. 深夜锁结束后第一次亮屏。
  const wokeAt = Number(f.wokeAt) || 0
  if (f.screenOn && wokeAt > 0 && wokeAt <= now) {
    return { enter: true, reason: 'wake', why: '深夜锁结束后第一次亮屏' }
  }
  // 2. 工作时段内，当前局收局且队列为空。
  if (work && Number(f.closedAt) > 0 && f.queueEmpty !== false && now - Number(f.closedAt) < 60_000) {
    return { enter: true, reason: 'queue', why: '刚收局，队列空了' }
  }
  // 3. 工作时段内，无局到上限。
  if (work && !f.inBout && Number(f.noBoutSince) > 0 && now - Number(f.noBoutSince) >= PLAN_IDLE_MIN * 60_000) {
    return { enter: true, reason: 'idle', why: '已经 ' + PLAN_IDLE_MIN + ' 分钟没有局了' }
  }
  // 4. 一次升级结束后。
  if (Number(f.escalateEndedAt) > 0 && now - Number(f.escalateEndedAt) < 60_000) {
    return { enter: true, reason: 'escalate', why: '刚过一次升级' }
  }
  // 5. 他说「重排」。
  if (f.saidRearrange) return { enter: true, reason: 'rearrange', why: '他自己说要重排' }

  return { enter: false, reason: '', why: '' }
}
