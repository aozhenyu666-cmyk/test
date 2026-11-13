// dsh-go-companion · 「墙」面板的数据（第八轮 W2）
//
// 墙从背景板升级成控制塔：此刻谁在管、娱乐锁没锁、锁到几点、为什么，今天的局排到哪儿，
// 今天的升级记录，审查记录与申诉，以及现在生效的阈值。
//
// 这一层不生成任何一句话的措辞：
//   锁的理由来自 machine.js（layers[].why，本来就是模板）；
//   审查记录来自 wall_voice.js 的 wallThread（第七轮 A3 的模板）；
//   阈值原样来自 settings.js。
// 没发生的事就写「今天没有」——面板上一个编出来的升级记录比空着更坏。

import { hm } from './tz.js'
import { wallThread } from './wall_voice.js'

/** 主状态的中文名。界面只显示这一个，别再各处自己翻译。 */
const STATE_ZH = {
  night: '深夜',
  plan: '计划',
  evening: '对账',
  morning: '立项',
  review: '验收',
  followup: '追问',
  sprint: '冲刺',
  brain: '共脑',
  meal: '吃饭',
  break: '放风',
  standby: '待命',
}

/** 锁层的中文名（追问锁叠上来时界面仍要看得见底下那层）。 */
const LAYER_ZH = {
  night: '深夜锁',
  plan: '计划锁',
  evening: '对账锁',
  followup: '追问锁',
  sprint: '冲刺锁',
  brain: '共脑锁',
  standby: '默认锁',
}

/** 升级记录只认这几种账（P3/P4 落账时写的就是这些）。 */
const ESCALATE_STATES = new Set(['escalate'])

function clock(at, tz) {
  return Number(at) > 0 ? hm(Number(at), tz) : ''
}

/**
 * 今天的升级记录：一条一行，按最新在上。
 *
 * 两种来源都认：bouts.js `listEscalations` 的表行（`{level, reason, startedAt}`）和账本条目
 * （`{state:'escalate', rule, why, at}`）。前者是权威，后者是老数据。字段名不同不是笔误——
 * 表那边是代码判的升级，账本那边是事后的一行注记。
 * @param {object[]} rows
 * @param {{todayStart:number, tz:string, limit?:number}} opts
 */
export function escalateRows(rows, { todayStart = 0, tz = '', limit = 12 } = {}) {
  const out = []
  for (const e of Array.isArray(rows) ? rows : []) {
    // 非对象（null / 数字 / 字符串）不是记录，是坏输入：不能因此拼出一行空记录。
    if (!e || typeof e !== 'object') continue
    // 账本条目要过 state 过滤；表行没有 state，直接收。
    if (e.state !== undefined && !ESCALATE_STATES.has(e.state)) continue
    const at = Number(e.at ?? e.startedAt) || 0
    if (at < Number(todayStart)) continue
    const rule = String(e.rule || e.code || e.level || '').trim()
    const why = String(e.why || e.reason || '').trim()
    const action = String(e.action || e.landed || '').trim()
    const ended = Number(e.endedAt) > 0 ? '（' + clock(e.endedAt, tz) + ' 结束·' + String(e.endedBy || '') + '）' : ''
    const head = clock(at, tz) + ' ' + (rule || '（没说哪条）')
    out.push(head + (action ? ' · ' + action : '') + (why ? ' · ' + why : '') + ended)
  }
  return out.sort().reverse().slice(0, Math.max(1, limit))
}

/**
 * 拼墙面板数据。
 * @param {{
 *   now?:number, tz?:string, state?:object, plan?:object, entries?:object[],
 *   todayStart?:number, settings?:object, auditDays?:number, auditLimit?:number
 * }} input
 *   state：machine.js resolveState 的结果（原样传进来，不在这里重算）
 *   plan：planwire.js 的计划（没有就传 null）
 */
export function buildWallSnapshot(input = {}) {
  const src = input && typeof input === 'object' ? input : {}
  const now = Number(src.now) || Date.now()
  const tz = src.tz || 'Asia/Shanghai'
  const st = src.state && typeof src.state === 'object' ? src.state : {}
  const layers = (Array.isArray(st.layers) ? st.layers : []).filter((l) => l && typeof l === 'object')
  input = src

  const locked = st.funLocked === true
  const top = layers[0] || null
  const lock = {
    locked,
    headline: locked ? '娱乐锁着' : '娱乐开着',
    until: locked && top && Number(top.until) > now ? clock(top.until, tz) : '',
    why: top ? String(top.why || '') : '',
    tone: locked ? 'bad' : 'good',
  }
  lock.caption = locked
    ? (lock.until ? '锁到 ' + lock.until : '锁到今天结束') + (lock.why ? ' · ' + lock.why : '')
    : '这一段是放开的，到点自动收回。'

  const layerRows = layers.map((l) => {
    const name = LAYER_ZH[l.kind] || String(l.kind || '锁')
    const at = Number(l.until) > now ? '锁到 ' + clock(l.until, tz) : '没设到期'
    return name + ' · ' + at + (l.why ? ' · ' + l.why : '')
  })

  // 今天的局：排了几局、开了几局、几个空局，下一局几点。
  const items = Array.isArray(input.plan?.items) ? input.plan.items : []
  const opened = items.filter((i) => i.openedAt)
  const missed = items.filter((i) => i.missed)
  const left = items.filter((i) => !i.openedAt && !i.missed).sort((a, b) => a.minute - b.minute)
  const plan = {
    headline: items.length
      ? '今天 ' + items.length + ' 局：开过 ' + opened.length + '，空 ' + missed.length + '，还差 ' + left.length
      : '今天的局还没定',
    rows: items.map((i) => {
      const at = Math.floor(i.minute / 60) + ':' + String(i.minute % 60).padStart(2, '0')
      const mark = i.openedAt ? '开过了' : i.missed ? '空局：' + i.missed : '待开'
      return at + ' ' + i.title + ' · ' + mark
    }),
    note: items.length
      ? (left.length ? '下一局 ' + (Math.floor(left[0].minute / 60) + ':' + String(left[0].minute % 60).padStart(2, '0')) : '今天的局排完了。')
      : '没有计划的时候不开局，也不许临时拼一局。',
    accepted: input.plan?.accepted === true,
  }

  // 升级记录：有 escalations 表就只读表（权威），没有才退回账本。
  const escalations = escalateRows(
    Array.isArray(input.escalations) ? input.escalations : input.entries,
    { todayStart: input.todayStart, tz },
  )
  const escalate = {
    rows: escalations,
    note: escalations.length ? '今天升级了 ' + escalations.length + ' 次。' : '今天没有升级。',
  }

  // 审查记录与申诉：句子全来自 wall_voice.js 的模板。
  const thread = wallThread(input.entries, { days: input.auditDays || 14, limit: input.auditLimit || 40, now })
  const bubbles = (thread.bubbles || []).map((b) => clock(b.at, tz) + ' ' + b.who + '：' + b.text)
  const audit = {
    rows: bubbles,
    note: bubbles.length ? '共 ' + bubbles.length + ' 条，最新的在最上面。' : '这半个月还没有审查记录。',
  }

  const s = input.settings || {}
  const e = s.escalate || {}
  const ruleRows = []
  // 键名照抄 settings.js（E1Count / E1WindowMs / L1WorkMin / ...）：大小写不一样就等于读不到。
  if (e.E1Count) ruleRows.push('E1：' + Math.round((Number(e.E1WindowMs) || 0) / 60_000) + ' 分钟内敷衍 ' + e.E1Count + ' 次')
  if (e.L1WorkMin) ruleRows.push('L1：工作时段娱乐连续 ' + e.L1WorkMin + ' 分钟')
  if (e.L1RestMin) ruleRows.push('L1：非工作时段娱乐连续 ' + e.L1RestMin + ' 分钟')
  if (e.L3Min) ruleRows.push('L3：每天娱乐累计 ' + e.L3Min + ' 分钟')
  if (s.minBouts) ruleRows.push('每天最少 ' + s.minBouts + ' 局（至少 ' + (s.minThinking || 0) + ' 个思维局）')

  return {
    at: now,
    state: {
      id: String(st.state || ''),
      label: STATE_ZH[st.state] || String(st.state || '不知道'),
      agenda: String(st.agenda || ''),
    },
    lock,
    layers: layerRows,
    layersNote: layerRows.length ? '同时生效的有 ' + layerRows.length + ' 层，界面只显示最高的那一条。' : '现在没有锁层。',
    plan,
    escalate,
    audit,
    rules: {
      rows: ruleRows,
      note: ruleRows.length ? '改紧立即生效；改松挂一天冷却期。' : '还没读到阈值。',
    },
  }
}
