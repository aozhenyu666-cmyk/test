// dsh-go-companion · 每日文件与按完成物对账（第二轮）
//
// 早上定事：鲸澜在晨间对线时把「今天的事 + 每件的完成物」写进 大肥鱼/每日/日期.md。
// 白天不催：鲸澜白天不追问进度（追人阶梯已撤）。
// 晚上对账：按早上写下的完成物，去知识库、账本和使用记录里**找证据**——找到算完成，找不到算没完成，
//   找到一半记一半。结论写进当日文件和账本，作用于**明天**的放风额度和信用分，不当场锁人。
//
// 机器状态放 companion/daily.json（按天一份），md 是写给人看的（Obsidian 里能直接翻）。
// 对账只看证据，不听说法：光说「做完了」不算。

import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { minuteOfDay, startOfDay } from './tz.js'
import { LATE_FROM, MORNING_FROM } from './rhythm.js'

export const DAILY_DIR = '每日'
export const DAILY_MAX_TASKS = 6
// A2：白天的排程窗口。起点和早上那道门对齐（7:00），终点在深夜催睡（23:30）之前——
// 深夜锁（0:30–7:00）落在窗口外，所以"不排进深夜锁"是窗口本身保证的，不靠额外判断。
export const SCHEDULE_FROM = MORNING_FROM
export const SCHEDULE_TO = LATE_FROM
export const MIN_BLOCK_MIN = 10
export const MAX_BLOCK_MIN = 480
/** 每天最多重排几次。额度是"坚定性"的一半：允许改，但不许一直改。 */
export const MAX_RESCHEDULE_PER_DAY = 2
/** 什么算"可观测事件"。聊天里说"我想晚点做"不在这里面，所以它不触发重排。 */
export const RESCHEDULE_TRIGGERS = ['submitted-early', 'overdue']
export const TRIGGER_TEXT = { 'submitted-early': '有一件提前交了墙', overdue: '有一件到点没交' }
/** 四大部（A4 复用这张表；这里只用来校验晨间每件事归哪个部）。 */
export const DEPTS = ['信息收集', '谋划思考', '统筹规划', '行为管理']
/** 一天最多扫多少个文件找证据（别把手机扫卡）。 */
const EVIDENCE_SCAN_LIMIT = 200
const EVIDENCE_DEPTH = 4
/** 关键词命中比例到这个数就算做完（做了一半记一半）。 */
const DONE_RATIO = 0.6

/** 当地日期串 YYYY-MM-DD（按手机时区，不是进程时区）。 */
export function dayString(now, tz) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(now))
}

/** 每日文件路径：<知识库>/大肥鱼/每日/YYYY-MM-DD.md */
export function dailyPath(kbDir, now, tz) {
  return join(kbDir, DAILY_DIR, dayString(now, tz) + '.md')
}

const clean = (s, max) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, max)

/** 分钟数 → HH:MM（排程全用"当天第几分钟"，不碰时区）。 */
export function hhmm(min) {
  const m = Math.round(Number(min))
  if (!Number.isFinite(m) || m < 0) return '--:--'
  const h = Math.floor(m / 60) % 24
  return String(h).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
}

/** HH:MM → 分钟数。认不出来就是 null（不拿 NaN 去比大小）。 */
export function clockMin(s) {
  const m = /^\s*(\d{1,2}):(\d{2})\s*$/.exec(String(s == null ? '' : s))
  if (!m) return null
  const h = Number(m[1])
  const mi = Number(m[2])
  return h <= 23 && mi <= 59 ? h * 60 + mi : null
}

/**
 * 校验并规整早上定的事：每件都要带看得见的完成物、截止时刻、预估分钟数、归哪个部。
 * 后三样是 A2 加的：没有它们就排不出时间表，晚上也无从对照"这块到底用了多久"。
 * @returns {{ok:boolean, text:string, tasks?:{id:string,task:string,evidence:string,due:number,minutes:number,dept:string}[]}}
 */
export function normalizeTasks(raw) {
  const list = (Array.isArray(raw) ? raw : [])
    .map((t) => ({
      task: clean(t?.task, 120),
      evidence: clean(t?.evidence, 160),
      due: clockMin(t?.due),
      minutes: Math.round(Number(t?.minutes) || 0),
      dept: clean(t?.dept, 8),
    }))
    .filter((t) => t.task)
    .slice(0, DAILY_MAX_TASKS)
    .map((t, i) => ({ id: 't' + (i + 1), ...t }))
  if (!list.length) return { ok: false, text: '今天的事是空的。说一件事，也要带完成物。' }
  const noEvidence = list.filter((t) => !t.evidence)
  if (noEvidence.length) {
    return { ok: false, text: '每件事都要带一个看得见的完成物（笔记一篇、Gemini 记录、某个 App 的使用时长）：' + noEvidence.map((t) => '「' + t.task + '」').join('、') + ' 还没写。' }
  }
  const lack = []
  for (const t of list) {
    const miss = []
    if (t.due == null) miss.push('截止时刻（HH:MM）')
    if (!(t.minutes >= MIN_BLOCK_MIN && t.minutes <= MAX_BLOCK_MIN)) miss.push('预估分钟数（' + MIN_BLOCK_MIN + '–' + MAX_BLOCK_MIN + '）')
    if (!DEPTS.includes(t.dept)) miss.push('归哪个部（' + DEPTS.join(' / ') + '）')
    if (miss.length) lack.push('「' + t.task + '」差' + miss.join('、'))
  }
  if (lack.length) return { ok: false, text: '排不进时间表的先补齐：' + lack.join('；') + '。' }
  return { ok: true, text: '定了 ' + list.length + ' 件事。', tasks: list }
}

/** 一天的机器状态：{day, tasks, plan, review}。存在 stateFile('daily.json') 里，按天清。 */
export function loadDaily(raw, now, tz) {
  const day = dayString(now, tz)
  const s = raw && typeof raw === 'object' && raw.day === day ? raw : null
  return {
    day,
    tasks: Array.isArray(s?.tasks) ? s.tasks : [],
    plan: s?.plan && typeof s.plan === 'object' && Array.isArray(s.plan.blocks) ? s.plan : null,
    review: s?.review && typeof s.review === 'object' ? s.review : null,
  }
}

/** 渲染 每日/日期.md（写给人看的；机器状态在 daily.json）。 */
export function renderDaily(state, names = {}, numbers = null) {
  const who = names.strategist || '鲸澜'
  const lines = ['# ' + state.day + ' · 今日', '', '> 早上的事和完成物、晚上的对账，都由' + who + '写。对账只看证据。', '']
  // 第九轮第二项：四个数放在正文最前面（日报第一行）。传入才写，老调用点不受影响。
  if (numbers) {
    const pct = (v) => (v == null ? '—' : Math.round(v * 100) + '%')
    lines.push('> 主动开局比 ' + pct(numbers.selfOpenRatio) + ' · 交付率 ' + pct(numbers.deliverRatio) +
      ' · 空局率 ' + pct(numbers.badRatio) + ' · 局内偏离 ' + (Number(numbers.deviationMin) || 0) + ' 分钟', '')
  }
  lines.push('## 今天的事（早上定）', '')
  if (!state.tasks.length) {
    lines.push('- （还没定）')
  } else {
    state.tasks.forEach((t, i) => {
      lines.push(i + 1 + '. **' + t.task + '**  ')
      lines.push('   完成物：' + t.evidence)
      // A2 的三个新字段：老数据（这轮之前定的）没有，就不写这一行。
      if (t.due != null && t.minutes != null) lines.push('   截止 ' + hhmm(t.due) + ' · 估 ' + t.minutes + ' 分钟 · ' + (t.dept || '未归类') + '部')
    })
  }
  lines.push('', '## 时间表（白天按块走）', '')
  const plan = state.plan
  if (!plan || !plan.blocks.length) {
    lines.push('- （还没排）')
  } else {
    for (const b of plan.blocks) {
      lines.push('- ' + hhmm(b.start) + '–' + hhmm(b.end) + ' **' + b.task + '**（' + (b.dept || '未归类') + '部，估 ' + b.minutes + ' 分）')
    }
    for (const o of Array.isArray(plan.overflow) ? plan.overflow : []) lines.push('- 排不下：' + o.task + '——' + o.why)
    // 重排是追加：旧的记录一直留着，看得到"这一天改了几次、为什么改"。
    for (const r of Array.isArray(plan.reschedules) ? plan.reschedules : []) {
      lines.push('- 重排 ' + hhmm(r.atMin) + ' · ' + (TRIGGER_TEXT[r.trigger] || r.trigger))
    }
  }
  lines.push('', '## 对账（晚上）', '')
  const r = state.review
  if (!r) {
    lines.push('- （待对账）')
  } else {
    lines.push('- 结论：做完 ' + r.done + ' 件、做了一半 ' + r.partial + ' 件、没做完 ' + r.miss + ' 件（共 ' + r.total + ' 件）。')
    if (r.quotaDelta) lines.push('- 明天放风额度：' + (r.quotaDelta > 0 ? '+' + r.quotaDelta + ' 天' : r.quotaDelta + ' 天'))
    for (const x of Array.isArray(r.results) ? r.results : []) {
      lines.push('  - ' + (x.verdict === 'done' ? '✅ ' : x.verdict === 'partial' ? '◐ ' : '✗ ') + x.task + '——' + x.why)
    }
  }
  lines.push('')
  return lines.join('\n')
}

/** 写每日文件（md）。返回 {ok, file, text}。 */
export function writeDaily(kbDir, state, names = {}, numbers = null) {
  const file = join(kbDir, DAILY_DIR, state.day + '.md')
  try {
    mkdirSync(join(kbDir, DAILY_DIR), { recursive: true })
    writeFileSync(file, renderDaily(state, names, numbers), 'utf8')
    return { ok: true, file, text: '写进每日文件了：' + file }
  } catch (error) {
    return { ok: false, file: '', text: '每日文件写不进去：' + String((error && error.message) || error) }
  }
}

/** 从完成物里抠关键词：去掉标点，留长度 ≥2 的词，最多 4 个。 */
export function keywordsOf(evidence) {
  const words = String(evidence || '')
    .replace(/[，。、；：""''（）()【】\[\]《》<>,.;:!?~`|\\/]+/g, ' ')
    .split(/\s+/)
    .filter((w) => w.length >= 2)
  const seen = []
  for (const w of words) if (!seen.includes(w)) seen.push(w)
  return seen.slice(0, 4)
}

/** 完成物里像是在说文件路径吗（含 / 或以 .md 结尾）。 */
function looksLikePath(evidence) {
  return /[\\/]/.test(evidence) || /\.(md|txt|pdf|docx?|png|jpe?g)$/i.test(evidence)
}

function todayStart(now, tz) {
  return startOfDay(now, tz)
}

/** 扫知识库里今天动过的 md（跳过 大肥鱼/每日 自己，免得对账文件自己给自己作证）。 */
function todaysNotes(notesDir, since) {
  const out = []
  if (!notesDir) return out
  let seen = 0
  const skip = new Set([DAILY_DIR])
  const walk = (dir, depth) => {
    if (depth > EVIDENCE_DEPTH || seen > EVIDENCE_SCAN_LIMIT) return
    let names = []
    try {
      names = readdirSync(dir)
    } catch {
      return
    }
    for (const name of names) {
      if (seen > EVIDENCE_SCAN_LIMIT) return
      if (name.startsWith('.')) continue
      const path = join(dir, name)
      let st
      try {
        st = statSync(path)
      } catch {
        continue
      }
      if (st.isDirectory()) {
        if (skip.has(name)) continue
        walk(path, depth + 1)
        continue
      }
      if (!name.endsWith('.md') || st.mtimeMs < since) continue
      seen += 1
      let text = ''
      try {
        text = readFileSync(path, 'utf8').slice(0, 20_000)
      } catch {
        continue
      }
      out.push({ path, rel: relative(notesDir, path), text })
    }
  }
  walk(notesDir, 0)
  return out
}

/**
 * 单件事情的证据核对。只看三样东西，且都要有实物：
 *   1. 知识库文件（路径直接命中，或今天改过的 md 里有这个完成物的关键词）
 *   2. 账本（今天这条 Gemini 连麦复盘的题目/小结里有这些关键词）
 *   3. 使用记录（完成物里点到的 App，今天用到了要求的时间）
 * @returns {{verdict:'done'|'partial'|'miss', why:string}}
 */
export function checkEvidence(item, ctx) {
  const evidence = String(item?.evidence || '')
  const words = keywordsOf(evidence)
  const since = todayStart(ctx.now, ctx.tz)
  const notes = ctx.notes || []

  // 1a. 路径：直接说了哪个文件。
  if (looksLikePath(evidence)) {
    const raw = evidence.replace(/[`"'《》]/g, '').split(/\s+/).find((w) => /[\\/.]/.test(w)) || ''
    const candidates = []
    if (raw) {
      candidates.push(raw) // 绝对路径直接用（Android /sdcard/...、相对路径不存在就换下一个）
      if (ctx.notesDir) candidates.push(join(ctx.notesDir, raw.replace(/^\.?[\\/]/, '')))
      for (const n of notes) if (raw && (n.rel.includes(raw) || raw.includes(n.rel))) candidates.push(n.path)
    }
    for (const p of candidates) {
      try {
        if (p && existsSync(p) && statSync(p).mtimeMs >= since) return { verdict: 'done', why: '找到了这个文件，今天改过：' + p.split(/[\\/]/).slice(-2).join('/') }
      } catch {
        // 这个候选读不到，换下一个
      }
    }
  }

  // 1b. 今天动过的笔记里有没有这些关键词。
  let hit = 0
  if (words.length) {
    for (const n of notes) {
      const text = n.text || ''
      const nHit = words.filter((w) => text.includes(w)).length
      if (nHit > hit) hit = nHit
      if (nHit / words.length >= DONE_RATIO) return { verdict: 'done', why: '今天的笔记里有「' + words.filter((w) => text.includes(w)).slice(0, 2).join('、') + '」：' + n.rel }
    }
  }

  // 2. 账本：今天的 Gemini 连麦复盘。
  const recaps = (ctx.ledger || []).filter((e) => e?.state === 'gemini-recap' && Number(e.at) >= since)
  for (const e of recaps) {
    const text = String(e.summary || '') + ' ' + String(e.topic || '')
    if (words.length && words.some((w) => text.includes(w))) return { verdict: 'done', why: '今天有一条 Gemini 复盘对得上：' + String(e.summary || '').slice(0, 40) }
  }

  // 3. 使用记录：完成物点到的 App，今天用够了要求的时间。
  const usage = Array.isArray(ctx.usage) ? ctx.usage : []
  for (const u of usage) {
    const label = String(u?.label || u?.app || '')
    if (!label || !words.some((w) => label.includes(w) || w.includes(label))) continue
    const need = Number((evidence.match(/(\d+)\s*分钟/) || [])[1]) || 1
    const got = Math.round(Number(u?.minutes) || 0)
    if (got >= need) return { verdict: 'done', why: label + ' 今天用了 ' + got + ' 分钟（要求 ' + need + '）' }
    if (got > 0) return { verdict: 'partial', why: label + ' 今天只用了 ' + got + ' 分钟（要求 ' + need + '）' }
  }

  // 关键词只命中一部分：记一半。
  if (words.length && hit > 0 && hit / words.length >= 0.3) {
    return { verdict: 'partial', why: '只找到一部分痕迹（' + hit + '/' + words.length + ' 个关键词）' }
  }
  return { verdict: 'miss', why: '知识库、账本、使用记录里都没找到能对上这个完成物的东西' }
}

/**
 * 对账：逐件核对。
 * @param {{day:string,tasks:{task:string,evidence:string}[]}} state
 * @returns {{done:number, partial:number, miss:number, total:number, results:{task:string,evidence:string,verdict:string,why:string}[], quotaDelta:number}}
 */
export function reconcile(state, ctx) {
  const tasks = Array.isArray(state?.tasks) ? state.tasks : []
  // ctx.check：第四轮起由墙按物理证据判（wall_auditor.js）；没给就用原来的核对。
  const check = typeof ctx.check === 'function' ? ctx.check : null
  const notes = check ? [] : ctx.notes || todaysNotes(ctx.notesDir, todayStart(ctx.now, ctx.tz))
  const results = tasks.map((t) => {
    const r = check ? check(t) : checkEvidence(t, { ...ctx, notes })
    return { task: t.task, evidence: t.evidence, verdict: r.verdict, why: r.why }
  })
  const done = results.filter((r) => r.verdict === 'done').length
  const partial = results.filter((r) => r.verdict === 'partial').length
  const miss = results.filter((r) => r.verdict === 'miss').length
  // 作用于明天：全做完 +1 天放风额度，一件没做完 -1 天（不少于 0），一半就记一半（不加不减）。
  const total = results.length
  const quotaDelta = !total ? 0 : done === total ? 1 : done === 0 && partial === 0 ? -1 : 0
  return { done, partial, miss, total, results, quotaDelta }
}

/** 对账结论的一句人话。 */
export function reviewText(out) {
  if (!out || !out.total) return '今天早上没定事，没有可对的账。'
  const head = '对完了：做完 ' + out.done + ' 件、一半 ' + out.partial + ' 件、没做完 ' + out.miss + ' 件。'
  const tail = out.quotaDelta > 0 ? '明天多 1 天放风额度。' : out.quotaDelta < 0 ? '明天少 1 天放风额度。' : '明天额度不变。'
  return head + tail
}

// ---- A2：白天的时间表 ----------------------------------------------------------------------
//
// 早上定的事只写到「做什么、几点前、要多久、归哪个部」；排进时间块是这里的事——纯函数，不碰盘、不碰模型。
// 贪心找第一个装得下的空档，装不下就明确说是哪一件、为什么，绝不静默丢掉。
// 重排有额度（每天 2 次），而且必须由一个看得见的动作触发；聊天里说「我想晚点做」不算。

/** 从 我.md 正文里抠硬安排：一行里带「08:00-09:00」就算一段（半角/全角连字符、到、至都认）。 */
export function fixedBlocksOf(text) {
  const re = /(\d{1,2}):(\d{2})\s*(?:-|–|—|~|～|到|至)\s*(\d{1,2}):(\d{2})/g
  const out = []
  for (const line of String(text == null ? '' : text).split(/\r?\n/)) {
    re.lastIndex = 0
    const m = re.exec(line)
    if (!m) continue
    const from = Number(m[1]) * 60 + Number(m[2])
    const to = Number(m[3]) * 60 + Number(m[4])
    if (!(to > from) || from > 24 * 60 || to > 24 * 60) continue
    // 名字取时刻之后那一段（「08:00-10:00 上课」→「上课」）；写在前面就取前面。
    const after = line.slice(m.index + m[0].length).replace(/^[\s,，。;；:：\-–—]+/, '')
    const before = line.slice(0, m.index).replace(/^[\s>#*\-–—\d.、)]+/, '')
    out.push({ from, to, name: clean(after || before, 40) || '硬安排' })
  }
  return out
}

/** 当天还能用的空档（分钟区间）；busy 已裁进窗口。 */
function freeGaps(from, to, busy) {
  const gaps = []
  let t = from
  for (const b of busy) {
    if (b.to <= t) continue
    if (b.from > t) gaps.push({ from: t, to: b.from })
    t = Math.max(t, b.to)
  }
  if (t < to) gaps.push({ from: t, to })
  return gaps
}

/**
 * 把任务贪心排进当天可用时间：避开吃饭和我.md 里的硬安排，不越 23:30（深夜锁在窗口外）。
 * @param {{id:string,task:string,due:number,minutes:number,dept:string}[]} tasks
 * @param {{now:number, tz:string, meals?:{from:number,to:number}[], fixedBlocks?:{from:number,to:number}[]}} ctx
 * @returns {{blocks:{taskId:string,task:string,dept:string,minutes:number,start:number,end:number}[], overflow:{taskId:string,task:string,why:string}[], freeMin:number}}
 */
export function schedule(tasks, { now, tz, meals = [], fixedBlocks = [] } = {}) {
  const nowMin = minuteOfDay(now, tz)
  const from = Math.min(Math.max(nowMin, SCHEDULE_FROM), SCHEDULE_TO)
  const busy = []
  for (const m of Array.isArray(meals) ? meals : []) if (m && Number(m.to) > Number(m.from)) busy.push({ from: Number(m.from), to: Number(m.to) })
  for (const b of Array.isArray(fixedBlocks) ? fixedBlocks : []) if (b && Number(b.to) > Number(b.from)) busy.push({ from: Number(b.from), to: Number(b.to) })
  busy.sort((a, b) => a.from - b.from)
  const gaps = freeGaps(from, SCHEDULE_TO, busy)
  const blocks = []
  const overflow = []
  // 截止早的先排：他写「几点前做完」就是在定优先级。
  const order = (Array.isArray(tasks) ? tasks : []).slice()
    .sort((a, b) => (Number(a.due) || SCHEDULE_TO) - (Number(b.due) || SCHEDULE_TO))
  for (const t of order) {
    const need = Math.max(MIN_BLOCK_MIN, Math.round(Number(t.minutes) || 0))
    const due = Math.min(Number(t.due) || SCHEDULE_TO, SCHEDULE_TO)
    let placed = null
    for (let i = 0; i < gaps.length; i++) {
      const g = gaps[i]
      if (g.to - g.from < need) continue
      // 空档按起点升序：这一个起得太晚，后面的只会更晚，直接判定排不下。
      if (g.from + need > due) break
      placed = { taskId: t.id, task: t.task, dept: t.dept || '未归类', minutes: need, start: g.from, end: g.from + need }
      gaps[i] = { from: g.from + need, to: g.to }
      break
    }
    if (placed) {
      blocks.push(placed)
      continue
    }
    const freeTotal = gaps.reduce((s, g) => s + (g.to - g.from), 0)
    overflow.push({
      taskId: t.id,
      task: t.task,
      why: due < from + need
        ? '截止 ' + hhmm(due) + ' 前放不下 ' + need + ' 分钟'
        : '今天（' + hhmm(from) + ' 以后）没有连续 ' + need + ' 分钟的空档，剩下的加起来才 ' + freeTotal + ' 分钟',
    })
  }
  blocks.sort((a, b) => a.start - b.start)
  return { blocks, overflow, freeMin: gaps.reduce((s, g) => s + (g.to - g.from), 0) }
}

/**
 * 走一遍时间表：该开始的块开一次，该收尾的块收一次，各自只触发一次。
 * 纯函数——不写盘、不发消息，只回新状态和事件；落账和开任务模式由 index.js 的 planRoute 做。
 */
export function planSweep(state, { now, tz, meals = [], fixedBlocks = [] } = {}) {
  const s = state || {}
  if (!Array.isArray(s.tasks) || !s.tasks.length) return { state: s, plan: null, current: null, late: null, events: [] }
  const nowMin = minuteOfDay(now, tz)
  const events = []
  let plan = s.plan && Array.isArray(s.plan.blocks) ? s.plan : null
  if (!plan) {
    const out = schedule(s.tasks, { now, tz, meals, fixedBlocks })
    plan = { at: now, blocks: out.blocks, overflow: out.overflow, reschedules: [], fired: { start: {}, end: {} }, submitted: {} }
    events.push({ kind: 'plan-made', at: now, blocks: out.blocks.length, overflow: out.overflow.length })
  }
  const start = { ...(plan.fired?.start || {}) }
  const end = { ...(plan.fired?.end || {}) }
  for (const b of plan.blocks) {
    if (start[b.taskId] === undefined) {
      if (nowMin >= b.start && nowMin < b.end) {
        start[b.taskId] = nowMin
        events.push({ kind: 'block-start', at: now, taskId: b.taskId, task: b.task, dept: b.dept, start: b.start, end: b.end })
      } else if (nowMin >= b.end) {
        // 整段都错过了：不补记「开始」，免得账本说没发生过的事。
        start[b.taskId] = -1
      }
    }
    if (end[b.taskId] === undefined && nowMin >= b.end) {
      end[b.taskId] = nowMin
      events.push({ kind: 'block-end', at: now, taskId: b.taskId, task: b.task, dept: b.dept, start: b.start, end: b.end })
    }
  }
  const next = { ...s, plan: { ...plan, fired: { start, end } } }
  const current = plan.blocks.find((b) => nowMin >= b.start && nowMin < b.end) || null
  // 到点了还没交墙：最近收尾的那一块，10 分钟内继续点（和 gear 那套「每次往后顺 10 分钟」对齐）。
  const ended = plan.blocks.filter((b) => nowMin >= b.end && !plan.submitted?.[b.taskId]).sort((a, b) => b.end - a.end)[0] || null
  const late = ended && nowMin < ended.end + 10 ? ended : null
  return { state: next, plan: next.plan, current, late, events }
}

/** 这一件交墙过了：时间表里对应那块收工，之后不再算「到点没交」。 */
export function markBlockDone(state, taskId, atMin) {
  const s = state || {}
  if (!s.plan || !taskId) return s
  const plan = s.plan
  return {
    ...s,
    plan: {
      ...plan,
      fired: { start: { ...(plan.fired?.start || {}) }, end: { ...(plan.fired?.end || {}), [taskId]: atMin } },
      submitted: { ...(plan.submitted || {}), [taskId]: true },
    },
  }
}

/**
 * 重排：只有 RESCHEDULE_TRIGGERS 里的事件能触发，每天 MAX_RESCHEDULE_PER_DAY 次。
 * 已经收工的块不再排；重排记录**追加**，不覆盖——晚上看得到这一天改了几次、为什么改。
 */
export function reschedule(state, { now, tz, trigger, meals = [], fixedBlocks = [] } = {}) {
  const s = state || {}
  if (!Array.isArray(s.tasks) || !s.tasks.length) return { ok: false, text: '今天还没定事，没什么可重排的。' }
  if (!RESCHEDULE_TRIGGERS.includes(trigger)) {
    return { ok: false, text: '聊天里说「我想晚点做」不算重排。要重排得有一个看得见的动作：一件提前交了墙，或者到点没交。' }
  }
  const plan = s.plan && Array.isArray(s.plan.blocks) ? s.plan : { blocks: [], overflow: [], reschedules: [], fired: { start: {}, end: {} }, submitted: {} }
  const used = Array.isArray(plan.reschedules) ? plan.reschedules : []
  if (used.length >= MAX_RESCHEDULE_PER_DAY) {
    return { ok: false, text: '今天的重排用完了（' + MAX_RESCHEDULE_PER_DAY + ' 次）。剩下的照原表走，做不完的晚上照实说。' }
  }
  // 只排除"真交过墙"的块：块收尾提醒过 ≠ 做完了，到点没交的正是要往后挪的那几件。
  const doneIds = new Set(Object.keys(plan.submitted || {}))
  const left = s.tasks.filter((t) => !doneIds.has(t.id))
  if (!left.length) return { ok: false, text: '今天的块都收工了，没有可重排的。' }
  const out = schedule(left, { now, tz, meals, fixedBlocks })
  const next = {
    ...s,
    plan: {
      at: now,
      blocks: out.blocks,
      overflow: out.overflow,
      reschedules: [...used, { at: now, atMin: minuteOfDay(now, tz), trigger }],
      fired: { start: {}, end: {} },
      submitted: {},
    },
  }
  return {
    ok: true,
    state: next,
    plan: next.plan,
    text: '重排了（今天第 ' + (used.length + 1) + '/' + MAX_RESCHEDULE_PER_DAY + ' 次，因为' + (TRIGGER_TEXT[trigger] || trigger) + '）：\n' + scheduleText(out),
  }
}

/** 当前时间块一行，进约束层。 */
export function blockLine(block, nowMin) {
  if (!block) return ''
  const left = Math.max(0, Math.round(block.end - nowMin))
  return '[此刻] ' + hhmm(block.start) + '–' + hhmm(block.end) + ' ' + block.task + '（' + (block.dept || '未归类') + '部）· 还剩 ' + left + ' 分钟'
}

/** 到点了还没交墙的一行。 */
export function lateLine(block) {
  if (!block) return ''
  return '[此刻] ' + hhmm(block.start) + '–' + hhmm(block.end) + ' ' + block.task + ' 到点了 · 该交墙了（submit_to_wall kind=review）'
}

/** 时间表的人话（daily_plan 的回话用它）。 */
export function scheduleText(out) {
  const lines = ((out && out.blocks) || []).map((b) => '- ' + hhmm(b.start) + '–' + hhmm(b.end) + ' ' + b.task + '（' + b.dept + '部，估 ' + b.minutes + ' 分）')
  for (const o of (out && out.overflow) || []) lines.push('- 排不下：' + o.task + '（' + o.why + '）')
  return lines.join('\n') || '今天没有能排的块。'
}
