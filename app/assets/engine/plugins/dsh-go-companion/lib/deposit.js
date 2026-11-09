// dsh-go-companion · 每日四部门提交（第五轮）
//
// 他要每天交四段：信息收集、谋划思考、行为管理、统筹规划。每段两栏——汇报内容 + 自己的想法。
// 字数不到、套话、复读，当场退回；交齐了今天才算完。
//
// 硬约束落在两个已有的地方，不新造闹钟也不新造锁：
//   1. 晚上 21:30 的对账门（rhythm.js 的 reviewGate）要求四段齐了才关；
//   2. 没交齐的当天不给放风额度（relaxRemaining 归 0）。
//
// 审计分两层：这一层是规则（字数、套话、复读），立刻给结论；晚上对账时鲸澜会读原文再评一次——
// 她本来就是 AI，"AI 负责审计"由她兑现，不必在这里再造一条模型调用链。

import { isStock } from './stock.js'

/** 四个部门。key 落盘用，label 显示用；顺序就是界面上的顺序。 */
export const DEPARTMENTS = Object.freeze([
  { key: 'intel', label: '信息收集', hint: '今天收到了什么情报：看到了什么、谁说了什么、什么变了。' },
  { key: 'thinking', label: '谋划思考', hint: '由上面那些情报，你想清楚了什么、打算怎么走。' },
  { key: 'behavior', label: '行为管理', hint: '今天做得怎么样：说到的做到没有，哪里又跑了，下一步怎么管住。' },
  { key: 'coord', label: '统筹规划', hint: '手上的事怎么排：先做哪个、卡在哪、要谁配合。' },
])

/** 每段的下限。汇报内容要成段，想法可以短一点，但不能只有一个词。 */
export const MIN_REPORT = 200
export const MIN_THOUGHT = 80
/** 一段最多收这么多字，免得整本书塞进来（对账时还要读）。 */
const MAX_REPORT = 6000
const MAX_THOUGHT = 3000

const labelOf = (key) => (DEPARTMENTS.find((d) => d.key === key) || {}).label || key

const clean = (s, max) => String(s == null ? '' : s).replace(/\r\n/g, '\n').trim().slice(0, max)

/** 去掉空白和标点后的字数——「字数要求」按这个算，不然满屏空格也算数。 */
export function charCount(text) {
  return String(text == null ? '' : text).replace(/[\s\p{P}\p{S}]/gu, '').length
}

/** 一段内容的机械审计。AI 那一层在晚上对账时由鲸澜做。 */
export function auditEntry(raw) {
  const problems = []
  const report = String(raw?.report == null ? '' : raw.report)
  const thought = String(raw?.thought == null ? '' : raw.thought)
  const rc = charCount(report)
  const tc = charCount(thought)
  if (rc < MIN_REPORT) problems.push('汇报内容还差 ' + (MIN_REPORT - rc) + ' 字')
  if (tc < MIN_THOUGHT) problems.push('想法还差 ' + (MIN_THOUGHT - tc) + ' 字')
  if (rc >= MIN_REPORT && isStock(report)) problems.push('这段像套话：没有具体的人、事、数字')
  if (tc >= MIN_THOUGHT && isStock(thought)) problems.push('想法这段像套话')
  // 复读：把汇报原样再贴一遍当想法，不叫想法。
  if (rc >= MIN_REPORT && tc >= MIN_THOUGHT && thought.trim() === report.trim()) problems.push('想法和汇报是同一段话')
  return { ok: problems.length === 0, problems, reportChars: rc, thoughtChars: tc }
}

/**
 * 校验并规整一段提交。
 * @returns {{ok:boolean, text:string, entry?:{report:string,thought:string,at:number}}}
 */
export function normalizeEntry(raw, now) {
  const report = clean(raw?.report, MAX_REPORT)
  const thought = clean(raw?.thought, MAX_THOUGHT)
  const a = auditEntry({ report, thought })
  if (!a.ok) return { ok: false, text: '还没交齐：' + a.problems.join('；') }
  return { ok: true, text: '交了。', entry: { report, thought, at: now } }
}

/** 一天的状态：{day, entries:{key:{report,thought,at,audit}}}。按天清，不累计。 */
export function depositOf(raw, day) {
  const s = raw && typeof raw === 'object' && raw.day === day ? raw : null
  const entries = s && s.entries && typeof s.entries === 'object' ? s.entries : {}
  return { day, entries }
}

/** 某一段的历史结论（存下来给对账和界面复用，不用每次重算字数）。 */
export function withEntry(state, key, entry) {
  const a = auditEntry(entry)
  return {
    day: state.day,
    entries: { ...state.entries, [key]: { ...entry, reportChars: a.reportChars, thoughtChars: a.thoughtChars } },
  }
}

/** 今天交了几段、还缺哪几段。界面和硬约束都用这一个。 */
export function statusOf(state) {
  const items = DEPARTMENTS.map((d) => {
    const e = state.entries[d.key]
    const a = e ? auditEntry(e) : null
    return {
      key: d.key,
      label: d.label,
      hint: d.hint,
      done: Boolean(e && a && a.ok),
      at: Number(e?.at) || 0,
      reportChars: a ? a.reportChars : 0,
      thoughtChars: a ? a.thoughtChars : 0,
      minReport: MIN_REPORT,
      minThought: MIN_THOUGHT,
    }
  })
  const done = items.filter((i) => i.done).length
  return { day: state.day, items, done, total: DEPARTMENTS.length, missing: items.filter((i) => !i.done).map((i) => i.label) }
}

/** 四段齐了没有。硬约束只看这一个。 */
export function completeOf(state) {
  return statusOf(state).done === DEPARTMENTS.length
}

/** 一句话说清今天还差什么（给墙和提醒用）。 */
export function missingText(state) {
  const s = statusOf(state)
  if (!s.missing.length) return ''
  return '今天还缺 ' + s.missing.length + ' 段：' + s.missing.join('、') + '。'
}

/**
 * 她嘴里的那几句。接在 rhythmLines 后面：交齐了就别再念叨，没交齐就催，
 * 到了晚上对账时间（21:30 的 reviewGate）催得更硬——这就是「每天固定时间叫我」。
 */
export function depositLines(state, reviewGate) {
  const s = statusOf(state)
  if (!s.missing.length) return ['今天四段部门汇报都交齐了：别再催他交；他问起就说交齐了，然后聊正事。']
  const tail = reviewGate
    ? '现在就是晚上对账：先把这四段要到手，对完再谈别的。'
    : '晚上 21:30 对账时四段没齐，娱乐锁着，今天也没有放风额度。'
  return [
    '今天还缺 ' + s.missing.length + ' 段部门汇报：' + s.missing.join('、') +
      '（每段 200 字汇报 + 80 字自己的想法；套话和把汇报复读一遍当想法都会被退回）。' +
      tail + '该催就催，别替他写，也别替他编。',
  ]
}

export { labelOf }
