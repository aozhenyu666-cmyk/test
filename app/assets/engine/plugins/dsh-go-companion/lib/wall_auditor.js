// 第四轮 R4-1：墙的审查。
//
// 墙不是第三个人格，也不是一个能被说服的模型：它只看物理证据，判定全在这里的代码里。
// 姐妹是他的盟友，可以帮他把任务和证据整理好交上来（submit_to_wall），但过不过只由这里说了算。
// 说得再好、扯得再远，这里一个字都不读——它只读文件大小、截图时间、使用时长。
//
// 三种证据：
//   file        笔记 / 文件路径：立项后这份文件实打实长了 GROWTH_MIN_BYTES 以上
//   screenshot  完成物写了"截图"：立项后手机里新产生了截图
//   app         完成物写了"某 App N 分钟"：今天这个 App 用够了 N 分钟
//
// 纯函数为主：文件状态、截图列表、使用记录都由调用方传进来，方便单测；
// 真实读盘的两个小工具（statOf / listScreenshots）放在文件末尾。

import { createHash } from 'node:crypto'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { buildStrokePlan, evaluateSixDiceSlot, evaluateStroke, slotsForStage } from './six_dice.js'
import { candidateNotesForLinking, writeBidirectionalWikiLinks } from './notes.js'
import { DEFAULT_TZ, hm as zoneHm, ymd as zoneYmd, zoneOr } from './tz.js'

export const WALL_LABEL = '〔墙〕'
/** 一份笔记要长多少才算"写了东西"：大约一两百个汉字。改个错别字、加个空行都不够。 */
export const GROWTH_MIN_BYTES = 300
/**
 * A3：墙能给出的每一个判定码。`auditTask` 每加一条分支，都必须同时加进这张表——
 * `wall_voice.js` 靠它保证「每个码都有一句固定话」，测试遍历这张表，缺一条就失败。
 * 命名是 `证据类型:原因`，因为同一个「没过」在文件和 App 上要说的话不一样。
 */
export const AUDIT_CODES = Object.freeze([
  'file:pass', 'file:short', 'file:no-file', 'file:stale', 'file:grew0',
  'screenshot:pass', 'screenshot:none',
  'app:pass', 'app:short', 'app:not-found', 'app:zero',
  'none:unverifiable',
])
/** 墙审查时报的完成率窗口。 */
export const RATE_DAYS = 14
/** 手机里截图的常见位置。 */
export const SCREENSHOT_DIRS = ['/sdcard/Pictures/Screenshots', '/sdcard/DCIM/Screenshots']
/** 他在审查里扯别的时，墙只回这一句。 */
export const WALL_OFFTOPIC = '本通道只收证据，不讨论新想法、不讨论规则。交出完成物，或者回去做。'

const SHOT_RE = /截图|截屏|screenshot/i
const MIN_RE = /(\d+)\s*分钟/

function looksLikePath(s) {
  return /[\\/]/.test(s) || /\.(md|txt|pdf|docx?|png|jpe?g|xlsx?|pptx?)$/i.test(s)
}

/** 从完成物里抠出那段路径（去掉引号书名号，取第一个像路径的词）。 */
export function pathIn(evidence) {
  const s = String(evidence || '').replace(/[`"'《》「」]/g, ' ')
  return s.split(/\s+/).find((w) => looksLikePath(w)) || ''
}

/** 完成物属于哪一种可查的证据；写得没法查就是 null。 */
export function evidenceType(evidence) {
  const s = String(evidence || '').trim()
  if (!s) return null
  if (pathIn(s)) return 'file'
  if (SHOT_RE.test(s)) return 'screenshot'
  if (MIN_RE.test(s) && s.replace(MIN_RE, '').replace(/\s+/g, '').length >= 2) return 'app'
  return null
}

const HOW =
  '完成物要写成墙查得到的样子：笔记文件路径（如 大肥鱼/周报.md）、"截图"、或"某 App N 分钟"（如 刷题App 40分钟）。'

/**
 * 晨间立项：1–3 件事，每件的完成物都必须是墙查得到的类型。
 * @returns {{ok:boolean, tasks?:{task:string,evidence:string,type:string}[], problems:string[]}}
 */
export function checkMorningTasks(raw) {
  const list = (Array.isArray(raw) ? raw : [])
    .map((t) => ({ task: String(t?.task ?? '').trim().slice(0, 80), evidence: String(t?.evidence ?? '').trim().slice(0, 120) }))
    .filter((t) => t.task)
  const problems = []
  if (list.length < 1 || list.length > 3) problems.push('今天要定 1–3 件事，现在是 ' + list.length + ' 件。')
  for (const t of list) {
    if (!evidenceType(t.evidence)) problems.push('「' + t.task + '」的完成物「' + (t.evidence || '空') + '」墙查不了。')
  }
  if (problems.length) return { ok: false, problems: [...problems, HOW] }
  return { ok: true, tasks: list.map((t) => ({ ...t, type: evidenceType(t.evidence) })), problems: [] }
}

/** 完成物里的路径可能是绝对路径，也可能相对笔记库；按顺序给出候选。 */
export function pathCandidates(evidence, notesDir) {
  const raw = pathIn(evidence)
  if (!raw) return []
  const out = []
  if (raw.startsWith('/')) out.push(raw)
  if (notesDir) out.push(join(notesDir, raw.replace(/^\.?[\\/]/, '')))
  if (!raw.startsWith('/')) out.push(raw)
  return [...new Set(out)]
}

/**
 * 立项时给每个文件类完成物记下当时的大小（文件还不存在就记 0）。
 * @param stat (path) => {size, mtimeMs} | null
 * @returns {{[taskName:string]: {path:string, size:number}}}
 */
export function baselineFor(tasks, { notesDir, stat }) {
  const out = {}
  for (const t of tasks || []) {
    if (evidenceType(t.evidence) !== 'file') continue
    const cands = pathCandidates(t.evidence, notesDir)
    const hit = cands.find((p) => stat(p))
    const path = hit || cands[0]
    out[t.task] = { path, size: hit ? Number(stat(hit).size) || 0 : 0 }
  }
  return out
}

/**
 * 审一件事。
 * ctx: { now, since（立项时间）, notesDir, stat, screenshots: [{name, mtimeMs}], usage: [{label|app, minutes}],
 *        baseline: {path, size} | undefined }
 * A3：多返回 `code`（`AUDIT_CODES` 里的一个）和 `vars`（模板要用到的数字），
 * 让 `wall_voice.js` 能说出「要求 X，实测 Y，差 Z」那种冷话，而不用去解析 `data` 里的中文。
 * @returns {{verdict:'done'|'partial'|'miss', pass:boolean, code:string, vars:object, data:string, missing:string}}
 */
export function auditTask(task, ctx) {
  const evidence = String(task?.evidence || '')
  const type = evidenceType(evidence)
  const since = Number(ctx.since) || 0
  if (type === 'file') {
    const cands = ctx.baseline?.path ? [ctx.baseline.path, ...pathCandidates(evidence, ctx.notesDir)] : pathCandidates(evidence, ctx.notesDir)
    const path = cands.find((p) => ctx.stat(p))
    if (!path) return miss('file:no-file', { path: pathIn(evidence), min: GROWTH_MIN_BYTES }, '文件 ' + pathIn(evidence) + ' 不存在', '把 ' + pathIn(evidence) + ' 写出来')
    const st = ctx.stat(path)
    const before = Number(ctx.baseline?.size) || 0
    const now = Number(st.size) || 0
    const grew = now - before
    const data = '文件 ' + shortPath(path) + '：立项时 ' + before + ' 字节，现在 ' + now + ' 字节（' + signed(grew) + '）'
    const vars = { path: shortPath(path), min: GROWTH_MIN_BYTES, before, now, grew, diff: GROWTH_MIN_BYTES - grew }
    if (Number(st.mtimeMs) < since) return miss('file:stale', vars, data + '，立项后没动过', '立项后在这份文件里写东西')
    // file：给墙的眼睛（wall_eye.js）读新写的那段用，模板不读它。
    if (grew >= GROWTH_MIN_BYTES) return { verdict: 'done', pass: true, code: 'file:pass', vars, data, missing: '', file: path }
    if (grew > 0) return { verdict: 'partial', pass: false, code: 'file:short', vars, data, missing: '还差至少 ' + (GROWTH_MIN_BYTES - grew) + ' 字节的实质内容' }
    return miss('file:grew0', vars, data, '在这份文件里写实质内容（至少 ' + GROWTH_MIN_BYTES + ' 字节）')
  }
  if (type === 'screenshot') {
    const shots = (ctx.screenshots || []).filter((s) => Number(s.mtimeMs) >= since && Number(s.mtimeMs) <= Number(ctx.now))
    const data = '立项后新截图 ' + shots.length + ' 张'
    const vars = { n: shots.length }
    if (shots.length) return { verdict: 'done', pass: true, code: 'screenshot:pass', vars, data, missing: '' }
    return miss('screenshot:none', vars, data, '截一张能看出完成情况的图（立项之后截的才算）')
  }
  if (type === 'app') {
    const need = Number((evidence.match(MIN_RE) || [])[1]) || 1
    const words = evidence.replace(MIN_RE, ' ').split(/[\s，,、]+/).filter((w) => w.length >= 2)
    const row = (ctx.usage || []).find((u) => {
      const label = String(u?.label || u?.app || '')
      return label && words.some((w) => label.includes(w) || w.includes(label))
    })
    const label = row ? String(row.label || row.app) : words.join(' ')
    if (!row) return miss('app:not-found', { label, need, got: 0, diff: need }, '使用记录里没有「' + words.join(' ') + '」', '去用「' + words.join(' ') + '」' + need + ' 分钟')
    const got = Math.round(Number(row.minutes) || 0)
    const data = String(row.label || row.app) + ' 今天 ' + got + ' 分钟（要求 ' + need + '）'
    const vars = { label, need, got, diff: need - got }
    if (got >= need) return { verdict: 'done', pass: true, code: 'app:pass', vars, data, missing: '' }
    if (got > 0) return { verdict: 'partial', pass: false, code: 'app:short', vars, data, missing: '还差 ' + (need - got) + ' 分钟' }
    return miss('app:zero', vars, data, '去用够 ' + need + ' 分钟')
  }
  return miss('none:unverifiable', { evidence: evidence || '空' }, '完成物「' + (evidence || '空') + '」墙查不了', HOW)
}

function miss(code, vars, data, missing) {
  return { verdict: 'miss', pass: false, code, vars, data, missing }
}

function signed(n) {
  return (n >= 0 ? '+' : '') + n
}

function shortPath(p) {
  return String(p).split(/[\\/]/).slice(-2).join('/')
}

/**
 * 墙说的话：固定格式，没有语气。
 * @param {{title:string, data:string[], pass:boolean, missing?:string[], rate?:{done:number,total:number}|null}} r
 */
export function wallText(r) {
  const lines = [WALL_LABEL + ' ' + r.title]
  lines.push('数据：' + (r.data && r.data.length ? r.data.join('；') : '无'))
  lines.push('结论：' + (r.pass ? '过。' : '不过。'))
  const missing = (r.missing || []).filter(Boolean)
  if (!r.pass && missing.length) lines.push('还缺：' + missing.join('；'))
  if (r.rate && r.rate.total > 0) {
    lines.push('近 ' + RATE_DAYS + ' 天：定了 ' + r.rate.total + ' 件，有证据完成 ' + r.rate.done + ' 件（' + Math.round((r.rate.done / r.rate.total) * 100) + '%）。')
  }
  return lines.join('\n')
}

// ---------------------------------------------------------------- 言行账：立了几件、有证据完成几件

/** wall_memory.json：{ days: { 'YYYY-MM-DD': { promised, done } } } */
export function loadWallMemory(raw) {
  const days = raw && typeof raw === 'object' && raw.days && typeof raw.days === 'object' ? raw.days : {}
  return { days: { ...days } }
}

export function notePromised(mem, day, n) {
  const cur = mem.days[day] || { promised: 0, done: 0 }
  return { days: { ...mem.days, [day]: { ...cur, promised: Math.max(0, Math.round(Number(n) || 0)) } } }
}

export function noteDone(mem, day, n) {
  const cur = mem.days[day] || { promised: 0, done: 0 }
  return { days: { ...mem.days, [day]: { ...cur, done: Math.max(0, Math.round(Number(n) || 0)) } } }
}

/** 截至 today（含）往前 RATE_DAYS 天的合计。day 是 YYYY-MM-DD，按字符串比较即可。 */
export function recentRate(mem, today, days = RATE_DAYS) {
  const end = Date.parse(today + 'T00:00:00Z')
  const start = end - (days - 1) * 86_400_000
  let done = 0
  let total = 0
  for (const [day, v] of Object.entries(mem.days || {})) {
    const t = Date.parse(day + 'T00:00:00Z')
    if (!Number.isFinite(t) || t < start || t > end) continue
    total += Number(v?.promised) || 0
    done += Number(v?.done) || 0
  }
  return { done, total }
}

/** 只保留最近 60 天，免得文件无限长。 */
export function pruneMemory(mem, today, keepDays = 60) {
  const end = Date.parse(today + 'T00:00:00Z')
  const days = {}
  for (const [day, v] of Object.entries(mem.days || {})) {
    const t = Date.parse(day + 'T00:00:00Z')
    if (Number.isFinite(t) && end - t < keepDays * 86_400_000) days[day] = v
  }
  return { days }
}

// ---------------------------------------------------------------- 真实读盘

export function statOf(path) {
  try {
    const st = statSync(path)
    return st.isFile() ? { size: st.size, mtimeMs: st.mtimeMs } : null
  } catch {
    return null
  }
}

/** 列出 since 之后的截图（只看文件名像图片的，附带 SHA-256 去重避免同一张旧图重复混关）。读不到的目录跳过。 */
export function listScreenshots(dirs = SCREENSHOT_DIRS, since = 0, { seenHashes = null } = {}) {
  const out = []
  const localSeen = new Set(Array.isArray(seenHashes) ? seenHashes : [])
  for (const dir of dirs) {
    let names = []
    try {
      names = readdirSync(dir)
    } catch {
      continue
    }
    for (const name of names) {
      if (!/\.(png|jpe?g|webp)$/i.test(name)) continue
      const fullPath = join(dir, name)
      const st = statOf(fullPath)
      if (!st || st.mtimeMs < since || st.size <= 0) continue
      let sha256 = ''
      try {
        sha256 = createHash('sha256').update(readFileSync(fullPath)).digest('hex')
      } catch {
        sha256 = ''
      }
      if (sha256 && localSeen.has(sha256)) continue
      if (sha256) localSeen.add(sha256)
      out.push({ name, mtimeMs: st.mtimeMs, path: fullPath, ...(sha256 ? { sha256 } : {}) })
    }
  }
  return out
}

// ---------------------------------------------------------------- 六面骰 L2 渐进验证桥接

/** 生成某阶段的六面骰题槽；Stage 3 且未禁用联结时自动附上最多 3 篇 Obsidian WikiLink 候选笔记。 */
export function buildSixDicePlan({ stage = 1, notesDir = '', stage3Note = '', query = '' } = {}) {
  const plan = slotsForStage({ stage: Number(stage) || 1 })
  const linkSlot = plan.find((s) => s.dice === 5 || s.id === 'linking')
  const candidateNotes = linkSlot && notesDir
    ? candidateNotesForLinking(notesDir, { stage3Note, query, limit: 3 })
    : []
  return {
    stage: Number(stage) || 1,
    slots: plan.map((s) => s.dice === 5 && candidateNotes.length
      ? { ...s, candidateNotes }
      : s),
    candidateNotes,
  }
}

/** 运行单个六面骰槽的 L1 代码初筛 + L2 无状态语义复核；Stage 3 骰 5 自动附 Obsidian WikiLink 候选。 */
export async function auditSixDiceSlot(input = {}, deps = {}) {
  const dice = Math.max(1, Math.min(6, Math.round(Number(input.dice) || 1)))
  const notesDir = String(deps.notesDir || input.notesDir || '')
  const candidateNotes = Array.isArray(input.candidateNotes)
    ? input.candidateNotes
    : (dice === 5 && notesDir
        ? candidateNotesForLinking(notesDir, {
            stage3Note: input.stage3Note || '',
            query: input.query || input.sourceContext || input.answer || '',
            limit: 3,
          })
        : [])
  const sourceContext = candidateNotes.length && !String(input.sourceContext || '').includes('[[')
    ? [
        String(input.sourceContext || '').trim(),
        '候选关联笔记：' + candidateNotes.map((n) => n.wikilink + (n.snippet ? `（${n.snippet.slice(0, 40)}）` : '')).join('；'),
      ].filter(Boolean).join('\n')
    : String(input.sourceContext || '')
  const res = await evaluateSixDiceSlot({
    dice,
    prompt: input.prompt || '',
    answer: input.answer || '',
    sourceContext,
    anchors: Array.isArray(input.anchors) ? input.anchors : [],
    failures: Number(input.failures) || 0,
    stream: deps.stream || null,
    provider: deps.provider || '',
    model: deps.model || '',
    timeoutMs: input.timeoutMs || deps.timeoutMs || 8_000,
  })
  return { ...res, l2_fallback_offline: Boolean(res?.l2_fallback_offline), candidateNotes }
}

/** 运行 V3 六拍认知槽（含双轨计划、确定性犯规拦截、L2 离线降级标记及第六拍双向 WikiLink 回写）。 */
export async function auditStrokeSlot(input = {}, deps = {}) {
  const stroke = String(input.stroke || 'stroke_1_recall')
  const notesDir = String(deps.notesDir || input.notesDir || '')
  const candidateNotes = Array.isArray(input.candidateNotes)
    ? input.candidateNotes.slice(0, 3)
    : (stroke === 'stroke_6_systematize' && notesDir
        ? candidateNotesForLinking(notesDir, {
            stage3Note: input.stage3Note || '',
            query: input.query || input.sourceContext || input.answer || '',
            limit: 3,
          })
        : [])
  const res = await evaluateStroke({
    stroke,
    phase: input.phase || 'A',
    track: input.track || 'multi_shuttle_drill',
    prompt: input.prompt || '',
    answer: input.answer || '',
    sourceContext: String(input.sourceContext || ''),
    anchors: Array.isArray(input.anchors) ? input.anchors : [],
    retries: Number(input.retries ?? input.failures) || 0,
    linkedNotes: candidateNotes,
    stream: deps.stream || null,
    provider: deps.provider || '',
    model: deps.model || '',
    timeoutMs: input.timeoutMs || deps.timeoutMs || 8_000,
  })
  let wikiWriteback = null
  if (res.pass && stroke === 'stroke_6_systematize' && notesDir && input.writeBackLinks !== false && input.noteTitle) {
    wikiWriteback = writeBidirectionalWikiLinks({
      notesDir,
      kb: deps.kb || input.kb || '',
      noteTitle: input.noteTitle,
      noteSlug: input.noteSlug || '',
      body: input.answer || '',
      candidateNotes,
    })
  }
  return { ...res, l2_fallback_offline: Boolean(res?.l2_fallback_offline), candidateNotes, wikiWriteback }
}

export { buildStrokePlan }

// ---------------------------------------------------------------- 老板/主管全局审视接口（纯函数）

function numVal(v, d = 0) {
  return Number.isFinite(Number(v)) ? Number(v) : d
}

function shiftYmdStr(ymdStr, deltaDays) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(ymdStr || ''))
  if (!m) return ''
  const dt = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + Number(deltaDays || 0)))
  return dt.toISOString().slice(0, 10)
}

/**
 * 「老板 / 主管型墙」宏观全局审视简报生成器（为未来独立墙 AI 与 Gemini Bridge 审视铺路）。
 * 汇总近 7 天计划/封印兑现率、今日工作 vs 娱乐真实时长、被拦与紧急通行次数、未兑现承诺、昨日与今日计划块跳票情况，
 * 输出结构化快照 + 冷血无寒暄全局质询 Prompt（强制先查事实再提 3 个直击逃避模式的尖锐追问）。
 */
export function buildBossAuditBrief({
  now = Date.now(),
  tz = DEFAULT_TZ,
  todayStats = {},
  weekStats = {},
  locksOverview = null,
  seals = null,
  commitments = [],
  planBlocks = [],
  recentBreaches = [],
} = {}) {
  const ts = numVal(now, Date.now())
  const tzName = zoneOr(tz)
  const today = zoneYmd(ts, tzName)
  const yesterday = shiftYmdStr(today, -1)
  const sevenDaysAgo = shiftYmdStr(today, -6)

  // 1. 今日真实工作 vs 娱乐 vs 折腾工具时长
  const t = todayStats && typeof todayStats === 'object' ? todayStats : {}
  const buckets = (t.buckets && typeof t.buckets === 'object' ? t.buckets : null)
    || (t.charts?.buckets && typeof t.charts.buckets === 'object' ? t.charts.buckets : {})
  const briefObj = t.brief && typeof t.brief === 'object' ? t.brief : {}
  const mainlineMin = Math.max(0, Math.round(numVal(buckets.mainlineMin ?? t.mainlineMin, 0)))
  const videoStudyMin = Math.max(0, Math.round(numVal(buckets.videoStudyMin ?? t.videoStudyMin, 0)))
  const geminiMin = Math.max(0, Math.round(numVal(buckets.geminiMin ?? t.geminiMin ?? t.geminiMinutesToday, 0)))
  const tinkerMin = Math.max(0, Math.round(numVal(buckets.toolTinkerMin ?? t.toolTinkerMin, 0)))
  const workMin = Math.max(
    0,
    Math.round(
      mainlineMin + videoStudyMin + geminiMin > 0
        ? (mainlineMin + videoStudyMin + geminiMin)
        : numVal(t.workMin ?? t.focusMin ?? briefObj.focusMin, 0),
    ),
  )
  const funMin = Math.max(0, Math.round(numVal(buckets.funMin ?? t.funMin ?? briefObj.funMin, 0)))

  // 2. 近 7 天封印与计划兑现率
  const rawSeals = Array.isArray(seals) ? seals : (Array.isArray(seals?.items) ? seals.items : [])
  const recentSeals = rawSeals.filter((s) => {
    if (!s || typeof s !== 'object') return false
    const d = String(s.day || '')
    return !d || (d >= sevenDaysAgo && d <= today)
  })
  const sealDone = recentSeals.filter((s) => s.status === 'floor' || s.status === 'stretch').length
  const sealMissed = recentSeals.filter((s) => s.status === 'below').length
  const sealWaived = recentSeals.filter((s) => s.status === 'waived').length
  const sealTotal = recentSeals.length

  const w = weekStats && typeof weekStats === 'object' ? weekStats : {}
  const weekSealDone = sealTotal > 0 ? sealDone : Math.max(0, Math.round(numVal(w.sealDone ?? w.done, 0)))
  const weekSealTotal = sealTotal > 0 ? sealTotal : Math.max(0, Math.round(numVal(w.sealTotal ?? w.total, 0)))

  // 3. 昨日与今日计划块完成 / 跳票统计
  const rawBlocks = Array.isArray(planBlocks)
    ? planBlocks
    : [
        ...((Array.isArray(planBlocks?.yesterday) ? planBlocks.yesterday : []).map((b) => ({ ...b, day: b?.day || yesterday }))),
        ...((Array.isArray(planBlocks?.today) ? planBlocks.today : []).map((b) => ({ ...b, day: b?.day || today }))),
      ]
  const nonBreakBlocks = rawBlocks.filter((b) => b && typeof b === 'object' && !b.isBreak && !(b.slot === 'lunch' && b.lockMode === 'gentle'))
  const todayBlocks = nonBreakBlocks.filter((b) => !b.day || b.day === today)
  const yesterdayBlocks = nonBreakBlocks.filter((b) => b.day === yesterday)
  const blocksDone = nonBreakBlocks.filter((b) => b.status === 'done').length
  const blocksSkipped = nonBreakBlocks.filter((b) => b.status === 'skipped').length
  const todayBlocksDone = todayBlocks.filter((b) => b.status === 'done').length
  const todayBlocksSkipped = todayBlocks.filter((b) => b.status === 'skipped').length
  const yesterdayBlocksUnfinished = yesterdayBlocks.filter((b) => b.status !== 'done').length

  const combinedDone = weekSealDone + blocksDone + Math.max(0, Math.round(numVal(w.planDone, 0)))
  const combinedTotal = weekSealTotal + nonBreakBlocks.length + Math.max(0, Math.round(numVal(w.planTotal, 0)))
  const fulfillmentRatePct = combinedTotal > 0
    ? Math.round((combinedDone / combinedTotal) * 100)
    : (Number.isFinite(Number(w.fulfillmentRatePct ?? w.fulfillmentRate))
        ? Math.max(0, Math.min(100, Math.round(Number(w.fulfillmentRatePct ?? w.fulfillmentRate))))
        : null)

  // 4. 被拦、违约与紧急通行次数
  const breaches = Array.isArray(recentBreaches) ? recentBreaches.filter(Boolean) : []
  const locks = locksOverview && typeof locksOverview === 'object' ? locksOverview : {}
  const activeLock = locks.activeLock && typeof locks.activeLock === 'object' ? locks.activeLock : {}
  const wallet = locks.wallet && typeof locks.wallet === 'object' ? locks.wallet : {}
  const recentActions = Array.isArray(locks.recentActions) ? locks.recentActions : []

  const blockedFromActions = recentActions.filter((a) => /拦|封|deny|block/i.test(String(a?.action || ''))).length
  const blockedFromBreaches = breaches.filter((b) => /block|deny|bounce|intercept|被拦/i.test(String(b?.kind || b?.type || b?.state || ''))).length
  const blockedCount = Math.max(Math.round(numVal(t.blockedCount ?? w.blockedCount, 0)), blockedFromActions + blockedFromBreaches)

  const emergencyFromPassLedger = numVal(t.passLedger?.emergency?.used ?? t.disarms, 0)
  const emergencyFromBreaches = breaches.filter((b) => /emergency|disarm|breakglass|紧急|破窗/i.test(String(b?.kind || b?.type || b?.state || ''))).length
  const emergencyPassCount = Math.max(Math.round(emergencyFromPassLedger), emergencyFromBreaches, Math.round(numVal(t.emergencyUsedToday, 0)))

  const breachCount = Math.max(breaches.length, Math.round(numVal(t.violationsToday ?? w.breachCount, 0)))
  const breachSummaries = breaches.slice(-5).reverse().map((b) => {
    if (typeof b === 'string') return b.slice(0, 80)
    const atTxt = Number(b.at) > 0 ? zoneHm(Number(b.at), tzName) : ''
    const what = String(b.reason || b.why || b.target || b.app || b.kind || b.state || '越界/违约').slice(0, 60)
    return (atTxt ? `[${atTxt}] ` : '') + what
  })

  // 5. 未兑现承诺
  const pendingCommitments = (Array.isArray(commitments) ? commitments : [])
    .map((c) => {
      if (typeof c === 'string') return { title: c.trim(), done: false }
      if (!c || typeof c !== 'object') return null
      const status = String(c.status || '').toLowerCase()
      const done = c.done === true || status === 'done' || status === 'fulfilled' || status === 'passed'
      const title = String(c.statement || c.title || c.text || '').trim().slice(0, 80)
      return title ? { title, done, due: String(c.due || c.deadline || '').slice(0, 20) } : null
    })
    .filter((c) => c && !c.done)

  // 6. 提炼结构性全局偏差（keyGaps）
  const keyGaps = []
  if (fulfillmentRatePct !== null && fulfillmentRatePct < 60) {
    keyGaps.push(`近 7 天计划/封印兑现率仅 ${fulfillmentRatePct}%（${combinedDone}/${combinedTotal}），存在系统性立而不做`)
  }
  if (funMin > 0 && (funMin >= workMin || (funMin >= 45 && workMin < 60))) {
    keyGaps.push(`今日纯娱乐 ${funMin}m 已逼近或反超工作推进 ${workMin}m`)
  }
  if (tinkerMin >= 40 && tinkerMin > mainlineMin) {
    keyGaps.push(`工具折腾 ${tinkerMin}m 超过主线硬输出 ${mainlineMin}m（典型伪忙碌逃避）`)
  }
  if (emergencyPassCount >= 2 || blockedCount >= 3 || breachCount >= 2) {
    keyGaps.push(`近期撞锁被拦 ${blockedCount} 次、紧急/破窗通行 ${emergencyPassCount} 次、违约记录 ${breachCount} 条`)
  }
  if (pendingCommitments.length > 0) {
    keyGaps.push(`仍有 ${pendingCommitments.length} 项未兑现承诺悬空（首项：${pendingCommitments[0].title}）`)
  }
  if (blocksSkipped > 0 || yesterdayBlocksUnfinished > 0) {
    keyGaps.push(`计划块跳票/未收口：昨日未完 ${yesterdayBlocksUnfinished} 块，累计主动跳过 ${blocksSkipped} 块`)
  }
  if (activeLock.verifyOverdue) {
    keyGaps.push(`当前仍处于逾期严锁态（${activeLock.label || activeLock.source || '逾期未交付'}）`)
  }
  if (!keyGaps.length) {
    keyGaps.push('当前全局未见明显失血缺口，重点盯防晚间松懈与次日首位硬块兑现')
  }

  const rateText = fulfillmentRatePct === null ? '暂无足够样本' : `${fulfillmentRatePct}%（${combinedDone}/${combinedTotal}）`
  const headline = `7日兑现 ${rateText} · 今日正事 ${workMin}m / 娱乐 ${funMin}m · 拦/急 ${blockedCount}/${emergencyPassCount} · 悬空承诺 ${pendingCommitments.length}`

  const snapshot = {
    day: today,
    fulfillmentRatePct,
    fulfillmentDone: combinedDone,
    fulfillmentTotal: combinedTotal,
    sealStats7d: { done: weekSealDone, total: weekSealTotal, missed: sealMissed, waived: sealWaived },
    todayWorkMin: workMin,
    todayMainlineMin: mainlineMin,
    todayVideoStudyMin: videoStudyMin,
    todayGeminiMin: geminiMin,
    todayTinkerMin: tinkerMin,
    todayFunMin: funMin,
    blockedCount,
    emergencyPassCount,
    breachCount,
    breachSummaries,
    pendingCommitmentsCount: pendingCommitments.length,
    pendingCommitments: pendingCommitments.slice(0, 5),
    planBlocksSummary: {
      total: nonBreakBlocks.length,
      done: blocksDone,
      skipped: blocksSkipped,
      todayTotal: todayBlocks.length,
      todayDone: todayBlocksDone,
      todaySkipped: todayBlocksSkipped,
      yesterdayUnfinished: yesterdayBlocksUnfinished,
    },
    activeLock: {
      active: Boolean(activeLock.active),
      priority: String(activeLock.priority || 'P6'),
      label: String(activeLock.label || '未锁定'),
      verifyOverdue: Boolean(activeLock.verifyOverdue),
    },
    availableFunMin: wallet.availableMin !== undefined ? Math.round(numVal(wallet.availableMin, 0)) : null,
  }

  const promptLines = [
    '【角色指令：冷血直属主管 / 老板全局审视，零寒暄、零安慰、严禁只盯单一微小时段】',
    '规则：先核查下方客观全局账本（若连着 dafeiyu 工具先调 dafeiyu_today / dafeiyu_wall 交叉验真），再基于整体逃避模式发问；不接受「明天一定」「差不多」「状态不好」等虚词。',
    '',
    `[全局事实快照 · ${today}]`,
    `- 近 7 天计划/封印兑现率：${rateText}${sealMissed > 0 ? `（含未过保底 ${sealMissed} 条）` : ''}`,
    `- 今日真实时间投向：主线与学习 ${workMin}m（主线 ${mainlineMin}m / 网课 ${videoStudyMin}m / Gemini ${geminiMin}m） vs 纯娱乐 ${funMin}m vs 折腾工具 ${tinkerMin}m`,
    `- 门禁与破戒：被拦 ${blockedCount} 次 · 紧急/破窗通行 ${emergencyPassCount} 次 · 违约记录 ${breachCount} 条 · 当前锁态：${snapshot.activeLock.active ? `${snapshot.activeLock.priority} ${snapshot.activeLock.label}` : '未锁'}`,
    `- 计划块执行：今日完成 ${todayBlocksDone}/${todayBlocks.length}（跳过 ${todayBlocksSkipped}），昨日遗留未完 ${yesterdayBlocksUnfinished} 块`,
    `- 未兑现承诺（${pendingCommitments.length} 项）：${pendingCommitments.length ? pendingCommitments.slice(0, 4).map((c) => c.title).join('；') : '无'}`,
    ...(breachSummaries.length ? [`- 近期异常/破窗摘要：${breachSummaries.join('；')}`] : []),
    `- 系统判出的结构缺口：${keyGaps.join('；')}`,
    '',
    '[强制输出要求]',
    '1. 先用不超过 2 句话直接给出你对当下全局态势的老板定性（只讲事实与代价）；',
    '2. 紧接着提出 **3 个直击全局逃避模式的尖锐追问**（分别指向：为什么承诺/计划块反复跳票、是否在用娱乐/工具折腾回避核心硬骨头、为什么出现多次撞锁/紧急通行）；',
    '3. 最后给出 1 条未来 24 小时唯一可验、不可讨价还价的止损硬指令。',
  ]

  return {
    ok: true,
    v: 1,
    generatedAt: ts,
    day: today,
    snapshot,
    headline,
    keyGaps,
    bossPrompt: promptLines.join('\n'),
  }
}
