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

/**
 * A4：四部各自「记什么、证据从哪来」。写死在这张表里，界面和晚间对照共用一份。
 * 行为管理那一路的证据是墙的遥测——所以它的汇报栏由墙生成，用户只写想法栏。
 */
export const DEPT_DUTY = Object.freeze({
  intel: { records: '今天读了 / 查了 / 问了什么，来源是什么', evidence: '笔记库新增文件、gemini_log', auto: false },
  thinking: { records: '得出了什么判断、否掉了什么选项', evidence: '笔记库、挂载卡里确认过的结论', auto: false },
  behavior: { records: '手机用在哪、守了 / 破了哪条规则', evidence: '墙的遥测（自动填，用户只写想法栏）', auto: true },
  coord: { records: '计划怎么变了、为什么变', evidence: '当天的时间表和重排记录', auto: false },
})

/** 日间记录槽：一条最多 200 字（带时间戳和关联任务）。 */
export const SLOT_MAX_CHARS = 200
/** 一部一天最多记多少条（防刷屏，也别把对账文本撑爆）。 */
export const SLOT_MAX_PER_DEPT = 20

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

/**
 * 一段内容的机械审计。AI 那一层在晚上对账时由鲸澜做。
 * A4：`auto` 是墙生成的那一栏（行为管理），不适用"汇报 ≥200 字"——那不是他写的，字数下限也就没意义。
 */
export function auditEntry(raw, { auto = false } = {}) {
  const problems = []
  const report = String(raw?.report == null ? '' : raw.report)
  const thought = String(raw?.thought == null ? '' : raw.thought)
  const rc = charCount(report)
  const tc = charCount(thought)
  if (!auto && rc < MIN_REPORT) problems.push('汇报内容还差 ' + (MIN_REPORT - rc) + ' 字')
  if (!auto && rc >= MIN_REPORT && isStock(report)) problems.push('这段像套话：没有具体的人、事、数字')
  if (tc < MIN_THOUGHT) problems.push('想法还差 ' + (MIN_THOUGHT - tc) + ' 字')
  if (!auto && tc >= MIN_THOUGHT && isStock(thought)) problems.push('想法这段像套话')
  // 复读：把汇报原样再贴一遍当想法，不叫想法。
  if (rc >= MIN_REPORT && tc >= MIN_THOUGHT && thought.trim() === report.trim()) problems.push('想法和汇报是同一段话')
  return { ok: problems.length === 0, problems, reportChars: rc, thoughtChars: tc }
}

/**
 * 校验并规整一段提交。
 * A4：行为管理部的汇报栏由墙生成——传了 `auto` 就用墙那份，用户交的 `report` 整个忽略（不是覆盖，是不看）。
 * @returns {{ok:boolean, text:string, entry?:{report:string,thought:string,at:number,auto?:boolean}}}
 */
export function normalizeEntry(raw, now, { auto } = {}) {
  const report = clean(auto ? auto : raw?.report, MAX_REPORT)
  const thought = clean(raw?.thought, MAX_THOUGHT)
  const a = auditEntry({ report, thought }, { auto: Boolean(auto) })
  if (!a.ok) return { ok: false, text: '还没交齐：' + a.problems.join('；') }
  return { ok: true, text: '交了。', entry: { report, thought, at: now, ...(auto ? { auto: true } : {}) } }
}

/** 一天的状态：{day, entries:{key:{report,thought,at,audit}}, slots:{key:[{at,text,taskId}]}}。按天清，不累计。 */
export function depositOf(raw, day) {
  const s = raw && typeof raw === 'object' && raw.day === day ? raw : null
  const entries = s && s.entries && typeof s.entries === 'object' ? s.entries : {}
  const slots = s && s.slots && typeof s.slots === 'object' ? s.slots : {}
  return { day, entries, slots }
}

/** 某一段的历史结论（存下来给对账和界面复用，不用每次重算字数）。 */
export function withEntry(state, key, entry) {
  const a = auditEntry(entry, { auto: Boolean(entry?.auto) })
  return {
    day: state.day,
    entries: { ...state.entries, [key]: { ...entry, reportChars: a.reportChars, thoughtChars: a.thoughtChars } },
    slots: state.slots || {},
  }
}

/** 今天交了几段、还缺哪几段、每部记了几条。界面和硬约束都用这一个。 */
export function statusOf(state) {
  const items = DEPARTMENTS.map((d) => {
    const e = state.entries[d.key]
    const a = e ? auditEntry(e, { auto: Boolean(e?.auto) }) : null
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
      // A4：这一部记什么、证据从哪来、汇报栏是不是墙自动填的、今天记了几条。
      duty: DEPT_DUTY[d.key].records,
      evidence: DEPT_DUTY[d.key].evidence,
      autoReport: DEPT_DUTY[d.key].auto,
      slots: Array.isArray(state.slots?.[d.key]) ? state.slots[d.key].length : 0,
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
export function depositLines(state, reviewGate, { tasks = [] } = {}) {
  const s = statusOf(state)
  const gap = deptGap(s, tasks)
  if (!s.missing.length) return ['今天四段部门汇报都交齐了：别再催他交；他问起就说交齐了，然后聊正事。']
  const tail = reviewGate
    ? '现在就是晚上对账：先把这四段要到手，对完再谈别的。'
    : '晚上 21:30 对账时四段没齐，娱乐锁着，今天也没有放风额度。'
  return [
    '今天还缺 ' + s.missing.length + ' 段部门汇报：' + s.missing.join('、') +
      '（每段 200 字汇报 + 80 字自己的想法；套话和把汇报复读一遍当想法都会被退回）。' +
      tail + '该催就催，别替他写，也别替他编。',
    ...(gap.length ? [gapText(gap)] : []),
  ]
}

// ------------------------------------------------------------------ A4：日间记录槽

/** 白天的记录槽：一条 ≤200 字，带时间戳和关联任务。按部累加，不覆盖。 */
export function appendSlot(state, { key, text, at, taskId } = {}) {
  const dept = DEPARTMENTS.find((d) => d.key === key)
  if (!dept) return { ok: false, text: '没有这个部：' + DEPARTMENTS.map((d) => d.label).join(' / ') + '。' }
  const t = String(text == null ? '' : text).replace(/\r\n/g, '\n').trim()
  if (!t) return { ok: false, text: '一条记录得有字。' }
  if (t.length > SLOT_MAX_CHARS) return { ok: false, text: '一条记录最多 ' + SLOT_MAX_CHARS + ' 字，这条 ' + t.length + ' 字。拆成两条记。' }
  const list = Array.isArray(state.slots?.[key]) ? state.slots[key] : []
  if (list.length >= SLOT_MAX_PER_DEPT) return { ok: false, text: dept.label + '部今天记满了（' + SLOT_MAX_PER_DEPT + ' 条），晚上直接写那一段。' }
  const next = {
    day: state.day,
    entries: state.entries || {},
    slots: {
      ...(state.slots || {}),
      [key]: [...list, { at: Number(at) || 0, text: t, taskId: String(taskId || '') || undefined }],
    },
  }
  return { ok: true, state: next, text: '记进' + dept.label + '部了（今天第 ' + (list.length + 1) + ' 条）。' }
}

/** 每部今天记了几条（界面和提示共用）。 */
export function slotsView(state) {
  return DEPARTMENTS.map((d) => ({ key: d.key, label: d.label, count: Array.isArray(state.slots?.[d.key]) ? state.slots[d.key].length : 0 }))
}

/**
 * 行为管理部的汇报栏**由墙生成**，用户不能改（只能写自己的想法栏）。
 * 只摆遥测里有的数字：今天各 App 用了多久、墙判了几次过没过。没有数据就说没有，不编。
 */
export function behaviorReport(ctx = {}) {
  const usage = (Array.isArray(ctx.usage) ? ctx.usage : [])
    .map((u) => ({ label: String(u?.label || u?.app || ''), minutes: Math.round(Number(u?.minutes) || 0) }))
    .filter((u) => u.label && u.minutes > 0)
    .sort((a, b) => b.minutes - a.minutes)
    .slice(0, 6)
  const verdicts = (Array.isArray(ctx.verdicts) ? ctx.verdicts : []).filter((v) => v && typeof v === 'object')
  const pass = verdicts.filter((v) => v.satisfied === true).length
  const fail = verdicts.length - pass
  const rules = (Array.isArray(ctx.rules) ? ctx.rules : []).map(String).filter(Boolean).slice(0, 6)
  const lines = ['〔墙生成，不能改〕']
  lines.push(usage.length ? '手机：' + usage.map((u) => u.label + ' ' + u.minutes + ' 分钟').join('、') + '。' : '手机：今天没有 App 使用遥测。')
  lines.push(verdicts.length ? '墙的判定：过 ' + pass + ' 次、没过 ' + fail + ' 次。' : '墙的判定：今天没有裁决记录。')
  if (rules.length) lines.push('规则：' + rules.join('；') + '。')
  return lines.join('\n')
}

/** 中文没有空格，按二字组算「提到同一件事」。 */
function bigramsOf(text) {
  const t = String(text == null ? '' : text).replace(/[\s\p{P}\p{S}]/gu, '')
  const out = new Set()
  for (let i = 0; i + 1 < t.length; i += 1) out.add(t.slice(i, i + 2))
  return out
}

/**
 * 晚间对照：这一段汇报里提到的事，能不能在今天的记录槽**或证据来源**里找到对应。
 * 找不到就标「无据」并退回（segment 不过）。行为管理那一部由墙生成，对着遥测写，不靠记忆。
 * @param {string[]} [opts.evidence] 证据来源的原文（今天的笔记摘要、当天时间表里的事）。规格写的是「记录槽或证据来源」，
 *   两处都空才是真的无据——只有记录槽空、笔记和计划都在，那是「他不爱记槽」，不是「他在编」。
 * @returns {{ok:boolean, unsupported:boolean, why:string}}
 */
export function reconcileEntry(state, key, report, { evidence = [] } = {}) {
  const dept = DEPARTMENTS.find((d) => d.key === key)
  if (!dept) return { ok: false, unsupported: true, why: '没有这个部' }
  if (DEPT_DUTY[key].auto) return { ok: false, unsupported: false, why: '行为管理部的汇报由墙生成（' + DEPT_DUTY[key].evidence + '），不用对照记录槽' }
  const slots = Array.isArray(state.slots?.[key]) ? state.slots[key] : []
  const pool = [
    ...slots.map((s, i) => ({ text: s.text, from: '第 ' + (i + 1) + ' 条记录' })),
    ...(Array.isArray(evidence) ? evidence : []).filter((e) => e != null && String(e).trim()).map((text) => ({ text: String(text), from: '证据来源' })),
  ]
  if (!pool.length) return { ok: false, unsupported: true, why: '今天' + dept.label + '部既没有日间记录，也没有可对照的证据来源（' + DEPT_DUTY[key].evidence + '），这一段没法对照' }
  const need = bigramsOf(report)
  for (const s of pool) {
    const mine = bigramsOf(s.text)
    let hit = 0
    for (const g of mine) if (need.has(g)) hit += 1
    // 二字组命中 3 个以上（或占这条记录的三成）就算对上了；一条短记录可能根本凑不出 3 个。
    if (hit >= Math.min(3, Math.max(1, Math.ceil(mine.size * 0.3)))) {
      return { ok: true, unsupported: false, why: '对上了' + s.from + '：' + String(s.text).slice(0, 24) }
    }
  }
  return { ok: false, unsupported: true, why: '汇报里提到的事，在今天的 ' + pool.length + ' 条记录和证据来源里找不到对应' }
}

/** 晨间某部有任务、晚上那个部却一条记录都没有：明确说出来（不猜、不替他补）。 */
export function deptGap(status, tasks) {
  const keys = new Set()
  for (const t of Array.isArray(tasks) ? tasks : []) {
    const label = String(t?.dept || '').replace(/部$/, '')
    const d = DEPARTMENTS.find((x) => x.label === label)
    if (d) keys.add(d.key)
  }
  return [...keys]
    .map((key) => {
      const item = status.items.find((i) => i.key === key)
      return item && item.slots === 0 ? { key, label: item.label } : null
    })
    .filter(Boolean)
}

export function gapText(gap) {
  if (!Array.isArray(gap) || !gap.length) return ''
  return '今天这些部在计划里有事，可一条日间记录都没有：' + gap.map((g) => g.label).join('、') +
    '。别让它们晚上凭空写 200 字——现在就让他在面板上记一条。'
}

/**
 * 时间块结束时问的那一句「这一块记到哪个部」。内容层的东西，所以有几种说法，
 * 按「哪一天 + 第几块」定种子挑一句（同一块不会变，不同块换着来）。
 */
export const SLOT_ASKS = Object.freeze([
  '这一块记到哪个部？挑一个，两句话就行。',
  '刚这一块，归信息收集还是谋划思考？顺手记一条。',
  '这一块做完了——哪条记录能对得上它？记一句。',
  '别等晚上回忆。现在给刚才这一块记一条，归哪个部？',
  '这一块落了什么？记到对应的部里，一条就够。',
])

export function slotAsk(day, index = 0) {
  const s = String(day || '') + '#' + (Number(index) || 0)
  let h = 0x811c9dc5
  for (let i = 0; i < s.length; i += 1) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return SLOT_ASKS[(h >>> 0) % SLOT_ASKS.length]
}

export { labelOf }
