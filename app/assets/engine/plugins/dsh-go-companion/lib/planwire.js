// dsh-go-companion · 计划的节拍（第八轮 P1/P2 的接线）
//
// dayplan.js 只回答「草案长什么样」，autobout.js 只回答「到点没到点」。这里把两者接成
// 一条时间线：计划什么时候变成真的、哪一局现在该开、开不了记成什么。
//
// 铁律三条。它们同时就是「引擎崩了怎么办」的全部答案——不是靠临场反应，是靠这里写死的规则：
//   1. 过点超过 LATE_SKIP_MIN 的局不补，记成空局。追补会把人连着按在椅子上开三局，那是拼凑。
//   2. 认不出来的局型（思维局还没装）跳过并写明原因，不拿别的局顶替。
//   3. 计划一旦写进文件就只按文件走：进程重启不重新出草案，昨天的不带进今天。
//
// 纯函数：时间、设定、已存的计划都由调用方给。

import { draftOf, planSentence, planTimeout } from './dayplan.js'
import { nextDue } from './autobout.js'
import { dayKindOf, settingsOf } from './settings.js'

/** 过点这么久就记空局，不补开。 */
export const LATE_SKIP_MIN = 20
/** 一次节拍最多处理几局（够排空一整天的积压，又不会死循环）。 */
const MAX_STEPS = 24

/** 今天还没有计划时的空壳。 */
export function emptyPlan(day) {
  return { v: 1, day: Number(day) || 0, items: [], edits: 0, accepted: false, at: 0, talkedAt: 0 }
}

function normItem(raw) {
  if (!raw || typeof raw !== 'object') return null
  const protocolId = String(raw.protocolId || '').trim()
  const minute = Number(raw.minute)
  if (!protocolId || !Number.isFinite(minute)) return null
  const item = {
    id: String(raw.id || protocolId + '-' + minute),
    protocolId,
    title: String(raw.title || protocolId),
    type: String(raw.type || '行为'),
    minute: Math.max(0, Math.min(24 * 60 - 1, Math.round(minute))),
    budget: Math.max(1, Math.min(180, Math.round(Number(raw.budget) || 25))),
  }
  if (raw.why) item.why = String(raw.why).slice(0, 60)
  if (Number(raw.openedAt) > 0) item.openedAt = Number(raw.openedAt)
  if (raw.missed) item.missed = String(raw.missed).slice(0, 60)
  return item
}

/**
 * 取出今天的计划。换天了就换一张空壳——昨天没做完的事不带到今天（它们进的是空局账，不是今天的计划）。
 * @returns {object} plan，带 `rolled` 表示这是刚换天、还没出草案
 */
export function planOf(stored, day) {
  const d = Number(day) || 0
  const s = stored && typeof stored === 'object' ? stored : null
  if (!s || Number(s.day) !== d) {
    const fresh = emptyPlan(d)
    fresh.rolled = true
    return fresh
  }
  const items = (Array.isArray(s.items) ? s.items : []).map(normItem).filter(Boolean)
  return {
    v: 1,
    day: d,
    items,
    edits: Math.max(0, Math.round(Number(s.edits) || 0)),
    accepted: s.accepted === true,
    at: Number(s.at) || 0,
    talkedAt: Number(s.talkedAt) || 0,
  }
}

/** 还没开的局，按时间排。记过空局的不再回到队列里。 */
function queueOf(plan) {
  return plan.items.filter((i) => !i.openedAt && !i.missed).sort((a, b) => a.minute - b.minute)
}

/** 下一次该看的时刻：最早那个还没开的槽位；都过了就是明天。 */
function nextAtOf(plan, day, now) {
  const q = queueOf(plan)
  const abs = q.length ? day + q[0].minute * 60_000 : day + 86_400_000
  return Math.max(Number(now) + 60_000, abs)
}

/**
 * 一次节拍。每分钟调一次就够——它不改任何东西，只把结论交给调用方去落盘/开局。
 *
 * @param {{now:number, day:number, min:number, tz:string, config:object, stored:object|null,
 *          running?:boolean, known?:string[]}} input
 *          known：已装的协议 id。给了就按它挡掉认不出来的局型。
 * @returns {{plan:object, action:'draft'|'wait-confirm'|'confirm'|'open'|'skip'|'hold',
 *            start:string, sentence:string, why:string, nextAt:number}}
 */
export function tick(input) {
  const now = Number(input.now) || 0
  const day = Number(input.day) || 0
  const min = Number(input.min) || 0
  const plan = planOf(input.stored, day)
  const out = { plan, action: 'hold', start: '', sentence: '', why: '', nextAt: now + 60_000 }

  // 1. 换天了还没有草案：出草案。刚出还不算数（10 分钟内他可以改，也可以不理）。
  if (plan.rolled) {
    plan.rolled = false
    const settings = settingsOf(input.config)
    const kind = dayKindOf(now, input.tz, input.config)
    const draft = draftOf({ settings, kind, missed: [], now, min })
    plan.items = draft.items.map(normItem).filter(Boolean)
    plan.edits = 0
    plan.accepted = false
    plan.at = now
    out.action = 'draft'
    out.sentence = planSentence(plan)
    out.nextAt = planTimeout({ at: now }, now).at
    return out
  }

  // 2. 草案还没生效：等到点了再自动生效。他改过（`at` 会往后走）就重新计时。
  if (!plan.accepted) {
    const t = planTimeout({ at: plan.at }, now)
    if (!t.due) {
      out.action = 'wait-confirm'
      out.nextAt = Math.max(now + 60_000, t.at)
      return out
    }
    plan.accepted = true
    out.action = 'confirm'
    out.sentence = planSentence(plan) + '十分钟到了，就按这个来。'
    out.nextAt = now + 60_000
    return out
  }

  // 3. 生效之后：过点太久的先一次性记成空局（不补开、不排队顶上去）。
  const known = Array.isArray(input.known) && input.known.length ? input.known : null
  let steps = 0
  while (steps < MAX_STEPS) {
    const q = queueOf(plan)
    const next = q[0]
    if (!next) break
    const late = min - next.minute
    if (late > LATE_SKIP_MIN) {
      next.missed = '过点 ' + Math.round(late) + ' 分钟，记空局'
      steps += 1
      out.action = 'skip'
      out.why = next.missed
      out.sentence = next.title + '这一局没开成，记成空局，不补。'
      continue
    }
    if (known && !known.includes(next.protocolId)) {
      next.missed = '这一型还没装'
      steps += 1
      out.action = 'skip'
      out.why = next.missed
      out.sentence = next.title + '这一局还没装，先跳过。'
      continue
    }
    // 到点了（含提前不到 20 分钟的迟到）：开。
    const due = nextDue({ running: Boolean(input.running), queue: [{ minute: next.minute }], min })
    if (!due.open) break
    next.openedAt = now
    out.action = 'open'
    out.start = next.protocolId
    out.sentence = next.title + '，' + next.budget + ' 分钟，开始。'
    steps += 1
    break
  }

  out.nextAt = out.action === 'skip' ? now + 60_000 : nextAtOf(plan, day, now)
  return out
}

/**
 * 拼进 prompt 的那几行。计划已经定了就不再问他想做什么——问句是这一层要拦的东西。
 * @returns {string[]} 空数组表示今天还没有计划
 */
export function planLines(plan, { now = 0, min = 0 } = {}) {
  const items = Array.isArray(plan?.items) ? plan.items : []
  if (!items.length) return []
  const left = items.filter((i) => !i.openedAt && !i.missed)
  const done = items.filter((i) => i.openedAt)
  const skipped = items.filter((i) => i.missed)
  const clock = (m) => Math.floor(m / 60) + ':' + String(m % 60).padStart(2, '0')
  const mark = (i) => clock(i.minute) + i.title + (i.openedAt ? '（开过了）' : i.missed ? '（记空局）' : '') +
    (Number(now) && i.minute <= min && !i.openedAt && !i.missed ? '（已过点，等着记空局）' : '')

  const head = plan.accepted
    ? '今天的局已经定了（' + items.length + ' 局，还差 ' + left.length + ' 局没开）。不要再问他今天想做什么，也不要提议换一批。'
    : '今天的草案（十分钟没改就按它生效）：'
  const lines = [head]
  for (const i of items) lines.push('- ' + mark(i) + (i.why ? '：' + i.why : ''))
  if (done.length) lines.push('已经开过 ' + done.length + ' 局。')
  if (skipped.length) lines.push('记了 ' + skipped.length + ' 个空局——空局是账，不是惩罚，别在现场给他补一局。')
  return lines
}

/** 今天离换天还有多久（本地毫秒）：日程节拍用它决定最晚什么时候再醒一次。 */
export function endsIn(day, now) {
  return Math.max(0, (Number(day) || 0) + 86_400_000 - (Number(now) || 0))
}
