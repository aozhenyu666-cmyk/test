// 2026-11-21 超级验收者的「底层设定」（纯函数）。
//
// 用户原话：「哪怕我不下任何指令，它也会进入这种模式，甚至是比较严格的，比如说只能用几个常用的 App」。
// 所以不再等他每天给指令：
//   1. 默认验收：工作时段（设置里的工作日 / 休息日时段）里，没吃饭、没在放风、没紧急、不是深夜——
//      就按「干活中」处理：只放行常用 App（局的默认白名单 + 永不锁的系统底座）。
//      娱乐照样能花挣来的余额，但要先说一句理由（spend），说了才开一段窗口。
//   2. 默认模板：到了套用时刻（工作日 9:30 / 休息日 10:00）今天还一条验收点都没有，就自动套模板。
//      模板的核心是「做一套行测题」（DSH 题库代码判），不是写分析。模板写在笔记库 大肥鱼/模板.md，
//      他改了第二天生效（冷静期就是这一晚）。
//   3. 熔断：连续 2 天没过保底 → 恢复日：只套一条「行测 10 题」，不加码；连续 2 天交上自动退出。
//   4. 一句话开工：他跟鱼说「开始了」→ 时段外也进默认验收到他说的时长（start_work）。

import { SEAL_KINDS } from './seal.js'

export const SPEND_MIN = 5
export const SPEND_MAX = 60
export const START_MAX_HOURS = 6
export const RECOVERY_FAIL_DAYS = 2
export const RECOVERY_EXIT_DAYS = 2

const hm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
const toMin = (s) => {
  const m = /^(\d{1,2})[:：](\d{2})$/.exec(String(s || '').trim())
  return m ? Number(m[1]) * 60 + Number(m[2]) : null
}

// ---------------------------------------------------------------- 默认验收

/** 现在落在哪个工作时段里（[from, to]），不在就 null。 */
export function currentWindow(windows, minuteOfDay) {
  for (const w of Array.isArray(windows) ? windows : []) {
    const from = Array.isArray(w) ? w[0] : w?.from
    const to = Array.isArray(w) ? w[1] : w?.to
    if (Number.isFinite(from) && Number.isFinite(to) && minuteOfDay >= from && minuteOfDay < to) return { from, to }
  }
  return null
}

/**
 * 此刻要不要默认验收。
 * @returns {{active:true, untilMinute:number, reason:'window'|'start'}|{active:false, why:string}}
 */
export function defaultVerify(p = {}) {
  const now = Number(p.now) || 0
  if (p.enabled === false) return { active: false, why: 'off' }
  if (p.night) return { active: false, why: 'night' }
  if (p.emergency) return { active: false, why: 'emergency' }
  if (p.onBreak) return { active: false, why: 'break' }
  if (p.meal) return { active: false, why: 'meal' }
  if (p.relaxOpen) return { active: false, why: 'relax' }
  if (Number(p.spendUntil) > now) return { active: false, why: 'spend' }
  const min = Number(p.minuteOfDay) || 0
  if (Number(p.startUntil) > now) {
    const untilMinute = Math.min(1439, min + Math.ceil((Number(p.startUntil) - now) / 60_000))
    return { active: true, untilMinute, reason: 'start' }
  }
  const w = currentWindow(p.windows, min)
  if (!w) return { active: false, why: 'outside' }
  return { active: true, untilMinute: w.to, reason: 'window' }
}

/** 花余额：说一句理由、不超过余额、5–60 分钟。 */
export function checkSpend({ minutes, reason, balance }) {
  const m = Math.round(Number(minutes) || 0)
  const why = String(reason || '').replace(/\s+/g, ' ').trim()
  const bal = Math.max(0, Math.floor(Number(balance) || 0))
  if (why.length < 4) return { ok: false, error: 'no-reason', text: '要先说一句理由（花在什么上、为什么现在）。' }
  if (bal < SPEND_MIN) return { ok: false, error: 'no-balance', text: '余额只有 ' + bal + ' 分钟：先交一个验收点或过一局挣一点。' }
  if (m < SPEND_MIN) return { ok: false, error: 'too-short', text: '最少 ' + SPEND_MIN + ' 分钟。' }
  return { ok: true, minutes: Math.min(m, bal, SPEND_MAX), reason: why.slice(0, 120) }
}

/** 开工：时段外也进默认验收（最多 6 小时）。 */
export function checkStart({ hours }) {
  const h = Number(hours)
  const ms = Math.round((Number.isFinite(h) && h > 0 ? Math.min(START_MAX_HOURS, h) : 2) * 3_600_000)
  return { ok: true, ms }
}

// ---------------------------------------------------------------- 模板（大肥鱼/模板.md）

/** 证据写法 → kind：题库 20 题 / 局 60 分钟 / 截图 3 张 / Gemini 回执 1 条 / 笔记 学习/行测.md 500 字 / App 包名 30 分钟。 */
function parseEvidence(s) {
  const t = String(s || '').trim()
  let m = /^题库\s*(\d+)\s*题$/.exec(t)
  if (m) return { kind: 'quiz', stretch: Number(m[1]) }
  m = /^局\s*(\d+)\s*分钟$/.exec(t)
  if (m) return { kind: 'bout_minutes', stretch: Number(m[1]) }
  m = /^截图\s*(\d+)\s*张$/.exec(t)
  if (m) return { kind: 'screenshots', stretch: Number(m[1]) }
  m = /^Gemini\s*回执\s*(\d+)\s*条$/i.exec(t)
  if (m) return { kind: 'gemini_receipt', stretch: Number(m[1]) }
  m = /^笔记\s+(\S+\.md)\s*(\d+)\s*字$/.exec(t)
  if (m) return { kind: 'file', path: m[1], stretch: Number(m[2]) }
  m = /^外部刷题\s+([\w.]+)\s*(\d+)\s*题$/.exec(t)
  if (m) return { kind: 'external_quiz', app: m[1], stretch: Number(m[2]) }
  m = /^App\s+([\w.]+)\s*(\d+)\s*分钟$/i.exec(t)
  if (m) return { kind: 'app_minutes', app: m[1], stretch: Number(m[2]) }
  return null
}

function renderEvidence(it) {
  switch (it.kind) {
    case 'quiz': return '题库 ' + it.stretch + ' 题'
    case 'bout_minutes': return '局 ' + it.stretch + ' 分钟'
    case 'screenshots': return '截图 ' + it.stretch + ' 张'
    case 'gemini_receipt': return 'Gemini 回执 ' + it.stretch + ' 条'
    case 'file': return '笔记 ' + it.path + ' ' + it.stretch + ' 字'
    case 'external_quiz': return '外部刷题 ' + it.app + ' ' + it.stretch + ' 题'
    case 'app_minutes': return 'App ' + it.app + ' ' + it.stretch + ' 分钟'
    default: return ''
  }
}

const KEYS = { 工作日: 'workday', 休息日: 'restday', 恢复日: 'recovery', 冲刺: 'sprint' }

export const DEFAULT_TEMPLATES = Object.freeze({
  workday: { name: '工作日', applyAt: 9 * 60 + 30, items: [
    { title: '行测一套（上午）', kind: 'quiz', stretch: 20, floor: 10, startMinute: 9 * 60 + 30, dueMinute: 11 * 60, estMinutes: 90 },
    { title: '行测一套（下午）', kind: 'quiz', stretch: 20, floor: 10, startMinute: 14 * 60 + 30, dueMinute: 16 * 60, estMinutes: 90 },
  ] },
  restday: { name: '休息日', applyAt: 10 * 60, items: [
    { title: '行测一套', kind: 'quiz', stretch: 20, floor: 10, startMinute: 10 * 60, dueMinute: 11 * 60 + 30, estMinutes: 90 },
  ] },
  recovery: { name: '恢复日', applyAt: 9 * 60 + 30, items: [
    { title: '恢复日：行测 10 题', kind: 'quiz', stretch: 10, floor: 10, startMinute: 9 * 60 + 30, dueMinute: 12 * 60, estMinutes: 40 },
  ] },
  sprint: { name: '冲刺', applyAt: null, items: [
    { title: '冲刺：行测 30 题', kind: 'quiz', stretch: 30, floor: 15, startMinute: null, dueMinute: null, estMinutes: 60, relative: 90 },
  ] },
})

export const TEMPLATE_HEAD = [
  '# 大肥鱼 · 模板',
  '',
  '> 没定今天的时间轴时，到点自动套「工作日 / 休息日」；连续 2 天没过保底套「恢复日」；跟鱼说「冲刺」套「冲刺」（从现在起算）。',
  '> 一行一个验收点：`- 开始–截止 标题 · 证据 · 保底 N`。证据写法：题库 20 题 / 局 60 分钟 / 截图 3 张 / Gemini 回执 1 条 /',
  '> 笔记 学习/行测.md 500 字 / 外部刷题 包名 30 题 / App 包名 30 分钟。「冲刺」里写「90 分钟内」代替钟点。',
  '> 改了第二天生效（今天已经套过的不变）。',
  '',
].join('\n')

export function renderTemplates(t) {
  const out = [TEMPLATE_HEAD]
  for (const [key, label] of [['workday', '工作日'], ['restday', '休息日'], ['recovery', '恢复日'], ['sprint', '冲刺']]) {
    const tp = t[key]
    if (!tp) continue
    out.push('## ' + label + (tp.applyAt != null ? '（' + hm(tp.applyAt) + ' 自动套）' : ''), '')
    for (const it of tp.items) {
      const when = it.relative ? it.relative + ' 分钟内' : (it.startMinute != null ? hm(it.startMinute) + '–' : '') + hm(it.dueMinute)
      out.push('- ' + when + ' ' + it.title + ' · ' + renderEvidence(it) + ' · 保底 ' + it.floor)
    }
    out.push('')
  }
  return out.join('\n')
}

/** 一行验收点：`- 开始–截止 标题 · 证据 · 保底 N`（模板和本周计划共用）。认不出就 null。 */
export function parseTemplateItem(line) {
  const m = /^-\s*(?:(\d{1,2}[:：]\d{2})\s*[–\-~～至]\s*)?(\d{1,2}[:：]\d{2}|\d{1,3}\s*分钟内)\s+(.+?)\s*·\s*(.+?)\s*·\s*保底\s*(\d+)\s*$/.exec(String(line || '').trim())
  if (!m) return null
  const ev = parseEvidence(m[4])
  if (!ev) return null
  const rel = /^(\d{1,3})\s*分钟内$/.exec(m[2])
  const item = {
    title: m[3].slice(0, 80), ...ev, floor: Math.max(1, Math.min(ev.stretch, Number(m[5]))),
    startMinute: m[1] ? toMin(m[1]) : null, dueMinute: rel ? null : toMin(m[2]), estMinutes: 60,
    ...(rel ? { relative: Number(rel[1]) } : {}),
  }
  if (item.startMinute != null && item.dueMinute != null) item.estMinutes = Math.max(10, Math.min(240, item.dueMinute - item.startMinute))
  return item
}

/** 解析 大肥鱼/模板.md；某一节写坏了就用默认的那一节（不因为一处手误整份作废）。 */
export function parseTemplates(text) {
  const t = JSON.parse(JSON.stringify(DEFAULT_TEMPLATES))
  const seen = {}
  let key = ''
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.trim()
    const h = /^##\s*(工作日|休息日|恢复日|冲刺)(?:\s*[（(]\s*(\d{1,2}[:：]\d{2})\s*自动套\s*[)）])?/.exec(line)
    if (h) {
      key = KEYS[h[1]]
      seen[key] = { name: h[1], applyAt: h[2] ? toMin(h[2]) : t[key].applyAt, items: [] }
      continue
    }
    if (/^#/.test(line)) {
      key = ''
      continue
    }
    if (!key || !line.startsWith('-')) continue
    const item = parseTemplateItem(line)
    if (item && seen[key].items.length < 8) seen[key].items.push(item)
  }
  for (const [k, v] of Object.entries(seen)) if (v.items.length) t[k] = v
  return t
}

/** 模板 → addSeals 的输入（相对时间的从 now 起算）。已经过了截止的那一条跳过。 */
export function templateSeals(tp, { minuteOfDay }) {
  const out = []
  for (const it of tp?.items || []) {
    let start = it.startMinute
    let due = it.dueMinute
    if (it.relative) {
      start = minuteOfDay
      due = Math.min(1439, minuteOfDay + it.relative)
    }
    if (due == null || due <= minuteOfDay) continue
    if (!SEAL_KINDS[it.kind]) continue
    out.push({
      title: it.title, kind: it.kind, stretch: it.stretch, floor: it.floor, startMinute: start, dueMinute: due,
      estMinutes: it.estMinutes, path: it.path, app: it.app, target: 'today',
    })
  }
  return out
}

/** 到点该不该自动套：到了套用时刻、今天一条验收点都没有、今天还没套过。 */
export function shouldApplyTemplate({ today, minuteOfDay, hasSealsToday, appliedDay, applyAt, enabled = true }) {
  if (!enabled || applyAt == null) return false
  return minuteOfDay >= applyAt && !hasSealsToday && appliedDay !== today
}

// ---------------------------------------------------------------- 熔断（恢复日）

/**
 * 每一天的结论：failed（有一条没过保底）/ passed（全都过了或豁免）/ none（那天没有验收点，或者有没判的）。
 * @param {{items:object[]}} seals
 */
export function dayOutcomes(seals, days) {
  const out = {}
  for (const d of days) {
    const list = (seals?.items || []).filter((x) => x.day === d)
    if (!list.length) {
      out[d] = 'none'
      continue
    }
    if (list.some((x) => x.status === 'below')) out[d] = 'failed'
    else if (list.every((x) => x.status === 'floor' || x.status === 'stretch' || x.status === 'waived')) out[d] = 'passed'
    else out[d] = 'none' // 还有没判的（引擎那天没开着之类）：不算他失败，也不算过
  }
  return out
}

/**
 * 今天是不是恢复日。看昨天往前：连续 2 个「failed」→ 进；进了以后连续 2 个「passed」→ 出。没有验收点的天跳过（不算断）。
 * @param {object} prev {active, since, exitedAt}
 * @param {string[]} pastDays 昨天、前天……（新到旧）
 */
export function recoveryStep(prev, outcomes, pastDays, today) {
  const s = { active: false, since: '', checkedDay: '', ...(prev || {}) }
  if (s.checkedDay === today) return { state: s, changed: false }
  const seq = pastDays.map((d) => outcomes[d]).filter((o) => o && o !== 'none')
  let next = { ...s, checkedDay: today }
  if (s.active) {
    const passedRun = seq.slice(0, RECOVERY_EXIT_DAYS).filter((o) => o === 'passed').length
    if (passedRun >= RECOVERY_EXIT_DAYS) next = { ...next, active: false, exitedDay: today }
  } else {
    const failedRun = seq.slice(0, RECOVERY_FAIL_DAYS).filter((o) => o === 'failed').length
    if (seq.length >= RECOVERY_FAIL_DAYS && failedRun >= RECOVERY_FAIL_DAYS) next = { ...next, active: true, since: today }
  }
  return { state: next, changed: next.active !== s.active, entered: next.active && !s.active, exited: !next.active && s.active }
}

export function verifyPromptLines({ verify, recovery, spend, untilHm }) {
  const lines = []
  if (verify?.active) lines.push('默认验收中（到 ' + untilHm + '）：只放行常用 App。他要玩，先让他说一句理由再用 spend_balance 花挣来的余额；没余额就让他先交一个验收点。')
  if (recovery?.active) lines.push('今天是恢复日（连续两天没过保底触发）：只要求一套 10 题，别加码、别说教；先问清楚这两天卡在哪，再陪他把这 10 题做完。')
  if (spend) lines.push('他正在花挣来的余额（到 ' + spend + '），理由他说过了，别打断。')
  return lines
}

// ---------------------------------------------------------------- 晚间娱乐上限（2026-11-21）
// 他原话：「很多时候从晚上 7 点一直玩到凌晨 1 点……晚上固定给 1~2 个小时娱乐」。
// 挣来的余额能一直攒，晚上不封顶就会一口气花掉。所以 18:00 到深夜锁之间，娱乐 / 游戏加起来最多 cap 分钟，
// 不管余额还剩多少；用完锁到第二天 7:00，提一句「要不要跟 Gemini 聊聊今天、把明天封了」。
// 调上限：调低立即生效；调高从明天起（晚上 10 点说「今天好累多给点」不算数）。

export const EVENING_FROM = 18 * 60
export const EVENING_END = 8 * 60
export const EVENING_CAP_DEFAULT = 120
export const DEFAULT_BASE_FUN_MIN = 30
export const EVENING_CAP_MIN = 30
export const EVENING_CAP_MAX = 180

/** 现在算不算「晚上」，算的话属于哪一晚（过了零点还算前一晚）。 */
export function eveningOf(today, yesterday, minuteOfDay, from = EVENING_FROM) {
  if (minuteOfDay >= from) return { evening: true, key: today, beforeMidnight: true }
  if (minuteOfDay < EVENING_END) return { evening: true, key: yesterday, beforeMidnight: false }
  return { evening: false, key: '', beforeMidnight: false }
}

/**
 * 每拍记一次今晚用了多少（funToday = 手机报的今天娱乐 + 游戏分钟，零点清零）。
 * @returns {{state, hit:boolean}} hit：这一拍刚好用完
 */
export function eveningStep(prev, { key, beforeMidnight, funToday, cap, now, slotUsed = null }) {
  const f = Math.max(0, Math.round(Number(funToday) || 0))
  let s = prev && prev.key === key ? { ...prev } : { key, base: beforeMidnight ? f : 0, usedBefore: 0, used: 0, capHitAt: 0 }
  if (beforeMidnight) {
    // 手机重启 / 跨天之类让今天的数变小：重新起算，不倒扣
    if (f < s.base) s.base = f
    // 2026-11-22：起点是 18:00 后引擎第一次醒来时抓的；引擎晚醒，中间玩的就被当成「6 点前的」扣掉了。
    // 系统使用记录按 2 小时一段给（18–20、20–22、22–24），有它就取两者里大的那个，只会多记不会少记。
    const sl = Number.isFinite(Number(slotUsed)) && slotUsed !== null ? Math.max(0, Math.round(Number(slotUsed))) : 0
    s.used = Math.max(f - s.base, sl)
    s.usedBefore = s.used
  } else {
    s.used = s.usedBefore + f
  }
  let hit = false
  if (!s.capHitAt && s.used >= cap) {
    s.capHitAt = now
    hit = true
  }
  return { state: s, hit }
}

/** 今晚的上限：config.eveningCap = {minutes, next:{minutes, from}}（调高的那个从 from 那天起）。 */
export function eveningCapFor(raw, eveningKey) {
  const c = raw && typeof raw === 'object' ? raw : {}
  const clamp = (m) => Math.max(EVENING_CAP_MIN, Math.min(EVENING_CAP_MAX, Math.round(Number(m) || EVENING_CAP_DEFAULT)))
  if (c.next && c.next.from && eveningKey >= c.next.from) return clamp(c.next.minutes)
  return clamp(c.minutes ?? EVENING_CAP_DEFAULT)
}

/** 改上限：调低立即；调高明天起。返回新的 config.eveningCap。 */
export function setEveningCap(raw, minutes, { today, tomorrow }) {
  const cur = eveningCapFor(raw, today)
  const want = Math.max(EVENING_CAP_MIN, Math.min(EVENING_CAP_MAX, Math.round(Number(minutes) || 0)))
  if (!Number(minutes)) return { ok: false, error: 'bad-minutes', text: '说个分钟数（' + EVENING_CAP_MIN + '–' + EVENING_CAP_MAX + '）。' }
  if (want <= cur) return { ok: true, value: { minutes: want }, immediate: true, text: '今晚起娱乐上限 ' + want + ' 分钟。' }
  return { ok: true, value: { minutes: cur, next: { minutes: want, from: tomorrow } }, immediate: false, text: '调高要等到明天：明晚起 ' + want + ' 分钟，今晚还是 ' + cur + ' 分钟。' }
}

// ---------------------------------------------------------------- 4.9 早上点头卡（纯函数）

/**
 * 生成早上点头卡预览条目。
 * 如果当天已有验收点则直接返回 { needed: false, ... }；
 * 否则从当日模板生成可微调的条目列表。
 */
export function buildNodCard({ template = DEFAULT_TEMPLATES.workday, existingSeals = [], minuteOfDay = 0, today = '' } = {}) {
  const sealList = Array.isArray(existingSeals)
    ? existingSeals
    : (Array.isArray(existingSeals?.items) ? existingSeals.items : [])
  const todaySeals = today ? sealList.filter((s) => s && s.day === today) : sealList.filter(Boolean)
  if (todaySeals.length > 0) {
    return {
      needed: false,
      reason: 'already_has_seals',
      today: String(today || ''),
      existingCount: todaySeals.length,
      items: [],
    }
  }
  const tp = Array.isArray(template) ? { name: '今日模板', items: template } : (template || DEFAULT_TEMPLATES.workday)
  const seals = templateSeals(tp, { minuteOfDay: Math.max(0, Math.round(Number(minuteOfDay) || 0)) })
  const items = seals.map((it, idx) => ({
    id: String(it.id || `nod-${idx + 1}`),
    title: it.title,
    kind: it.kind,
    stretch: it.stretch,
    floor: it.floor,
    startMinute: it.startMinute ?? null,
    dueMinute: it.dueMinute,
    estMinutes: it.estMinutes,
    path: it.path || '',
    app: it.app || '',
  }))
  return {
    needed: items.length > 0,
    reason: items.length > 0 ? 'ready' : 'all_due_passed',
    today: String(today || ''),
    templateName: String(tp?.name || ''),
    existingCount: 0,
    items,
  }
}

/**
 * 应用用户在早上点头卡上的微调：
 * 仅允许调整 stretch / floor（保证 floor >= 1 且 floor <= stretch）与保留/微调时间窗，
 * 返回标准化后可直接喂给 addSeals 的条目列表。
 */
export function applyNodCardOverrides(baseItems = [], edits = []) {
  const list = Array.isArray(baseItems) ? baseItems : []
  const editMap = new Map()
  if (Array.isArray(edits)) {
    edits.forEach((e, idx) => {
      if (!e || typeof e !== 'object') return
      const key = e.id != null ? String(e.id) : (list[idx]?.id != null ? String(list[idx].id) : String(idx))
      editMap.set(key, e)
    })
  } else if (edits && typeof edits === 'object') {
    for (const [k, v] of Object.entries(edits)) {
      if (v && typeof v === 'object') editMap.set(String(k), v)
    }
  }

  const out = []
  list.forEach((base, idx) => {
    if (!base || typeof base !== 'object') return
    if (!SEAL_KINDS[base.kind]) return
    const key = base.id != null ? String(base.id) : String(idx)
    const edit = editMap.get(key) || editMap.get(String(idx)) || {}
    if (edit.keep === false || edit.enabled === false || edit.remove === true) return

    const rawStretch = edit.stretch !== undefined ? Number(edit.stretch) : Number(base.stretch)
    const stretch = Math.max(1, Math.round(Number.isFinite(rawStretch) && rawStretch > 0 ? rawStretch : (Number(base.stretch) || 1)))
    const rawFloor = edit.floor !== undefined ? Number(edit.floor) : Number(base.floor)
    const fallbackFloor = Math.max(1, Math.min(stretch, Math.round(Number(base.floor) || Math.ceil(stretch / 2))))
    const floor = Math.max(1, Math.min(stretch, Number.isFinite(rawFloor) ? Math.round(rawFloor) : fallbackFloor))

    const rawDue = edit.dueMinute !== undefined && edit.dueMinute !== null ? Number(edit.dueMinute) : Number(base.dueMinute)
    const dueMinute = Math.max(1, Math.min(1439, Number.isFinite(rawDue) ? Math.round(rawDue) : (Number(base.dueMinute) || 18 * 60)))

    let startMinute = base.startMinute ?? null
    if (edit.startMinute === null) {
      startMinute = null
    } else if (edit.startMinute !== undefined) {
      const rawStart = Number(edit.startMinute)
      if (Number.isFinite(rawStart)) startMinute = Math.max(0, Math.min(dueMinute - 1, Math.round(rawStart)))
    }
    if (startMinute !== null && startMinute >= dueMinute) {
      startMinute = Math.max(0, dueMinute - 10)
    }

    const baseEst = Math.max(10, Math.min(240, Math.round(Number(base.estMinutes) || 60)))
    const estMinutes = startMinute !== null
      ? Math.max(10, Math.min(240, dueMinute - startMinute))
      : baseEst

    out.push({
      title: String(base.title || '').slice(0, 80),
      kind: base.kind,
      stretch,
      floor,
      startMinute,
      dueMinute,
      estMinutes,
      ...(base.path ? { path: String(base.path) } : {}),
      ...(base.app ? { app: String(base.app) } : {}),
      target: 'today',
    })
  })
  return out
}

// ---------------------------------------------------------------- 2026-11-22 本周计划（计划模态第一步）
// 大肥鱼/本周计划.md：周日晚周验收时和 Gemini 一起定，按周一到周日分节，一行一个验收点（格式和模板一样）。
// 到点自动套的就是今天那一节；没写今天那一节就退回模板。本周中只加不减（改了第二天生效，和模板一样）。
// 到了新的一周还没换：照抄上周那份顺延（标题日期换成这周一），计划不断档；体检会提醒该做周验收了。

export const WEEKDAY_NAMES = Object.freeze(['周日', '周一', '周二', '周三', '周四', '周五', '周六'])
const WEEKDAY_RE = /^##\s*(?:周|星期)([一二三四五六日天])(?:\s*[（(]\s*(\d{1,2}[:：]\d{2})\s*自动套\s*[)）])?/
const WD_NUM = { 日: 0, 天: 0, 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6 }

export const WEEK_PLAN_HEAD = (start) => [
  '# 本周计划（' + start + ' 起）',
  '',
  '> 周日晚做周验收时和 Gemini 一起定：长期规划（长期规划.md）→ 这周每天交什么。到点自动套今天那一节；没写的那天用 模板.md。',
  '> 一行一个验收点：`- 开始–截止 标题 · 证据 · 保底 N`（证据写法同 模板.md）。本周中只加不减；改了第二天生效。',
  '',
].join('\n')

/** 解析本周计划：{ start: 'YYYY-MM-DD' | '', days: { 0..6: { name, applyAt, items } } }。 */
export function parseWeekPlan(text) {
  const out = { start: '', days: {} }
  let wd = -1
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.trim()
    const head = /^#\s*本周计划\s*[（(]\s*(\d{4}-\d{2}-\d{2})/.exec(line)
    if (head) {
      out.start = head[1]
      continue
    }
    const h = WEEKDAY_RE.exec(line)
    if (h) {
      wd = WD_NUM[h[1]]
      out.days[wd] = { name: '本周·' + WEEKDAY_NAMES[wd], applyAt: h[2] ? toMin(h[2]) : null, items: [] }
      continue
    }
    if (/^#/.test(line)) {
      wd = -1
      continue
    }
    if (wd < 0 || !line.startsWith('-')) continue
    const item = parseTemplateItem(line)
    if (item && out.days[wd].items.length < 8) out.days[wd].items.push(item)
  }
  return out
}

/** 写出本周计划：days 缺的那天按模板（周一到周五工作日、周六周日休息日）补。 */
export function renderWeekPlan(start, days = {}, templates = DEFAULT_TEMPLATES) {
  const out = [WEEK_PLAN_HEAD(start)]
  for (const wd of [1, 2, 3, 4, 5, 6, 0]) {
    const base = wd >= 1 && wd <= 5 ? templates.workday : templates.restday
    const d = days[wd] && days[wd].items.length ? days[wd] : { applyAt: base.applyAt, items: base.items }
    out.push('## ' + WEEKDAY_NAMES[wd] + (d.applyAt != null ? '（' + hm(d.applyAt) + ' 自动套）' : ''), '')
    for (const it of d.items) {
      const when = it.relative ? it.relative + ' 分钟内' : (it.startMinute != null ? hm(it.startMinute) + '–' : '') + hm(it.dueMinute)
      out.push('- ' + when + ' ' + it.title + ' · ' + renderEvidence(it) + ' · 保底 ' + it.floor)
    }
    out.push('')
  }
  return out.join('\n')
}

/**
 * 今天用哪一节：本周计划写了今天那一节（有验收点）就用它（自动套的钟点没写就用模板的）；否则 null（调用方退回模板）。
 * stale：计划的起始日不是这周一（上周的没换），调用方会顺延重写。
 */
export function weekPlanToday(plan, { weekday, monday, fallbackApplyAt = null }) {
  const stale = Boolean(plan.start) && plan.start !== monday
  const d = plan.days[weekday]
  if (!d || !d.items.length) return { section: null, stale }
  return { section: { ...d, applyAt: d.applyAt != null ? d.applyAt : fallbackApplyAt }, stale }
}
