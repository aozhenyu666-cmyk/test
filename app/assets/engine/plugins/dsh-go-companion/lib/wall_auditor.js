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

import { readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'

export const WALL_LABEL = '〔墙〕'
/** 一份笔记要长多少才算"写了东西"：大约一两百个汉字。改个错别字、加个空行都不够。 */
export const GROWTH_MIN_BYTES = 300
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
 * @returns {{verdict:'done'|'partial'|'miss', pass:boolean, data:string, missing:string}}
 */
export function auditTask(task, ctx) {
  const evidence = String(task?.evidence || '')
  const type = evidenceType(evidence)
  const since = Number(ctx.since) || 0
  if (type === 'file') {
    const cands = ctx.baseline?.path ? [ctx.baseline.path, ...pathCandidates(evidence, ctx.notesDir)] : pathCandidates(evidence, ctx.notesDir)
    const path = cands.find((p) => ctx.stat(p))
    if (!path) return miss('文件 ' + pathIn(evidence) + ' 不存在', '把 ' + pathIn(evidence) + ' 写出来')
    const st = ctx.stat(path)
    const before = Number(ctx.baseline?.size) || 0
    const now = Number(st.size) || 0
    const grew = now - before
    const data = '文件 ' + shortPath(path) + '：立项时 ' + before + ' 字节，现在 ' + now + ' 字节（' + signed(grew) + '）'
    if (Number(st.mtimeMs) < since) return miss(data + '，立项后没动过', '立项后在这份文件里写东西')
    if (grew >= GROWTH_MIN_BYTES) return { verdict: 'done', pass: true, data, missing: '' }
    if (grew > 0) return { verdict: 'partial', pass: false, data, missing: '还差至少 ' + (GROWTH_MIN_BYTES - grew) + ' 字节的实质内容' }
    return miss(data, '在这份文件里写实质内容（至少 ' + GROWTH_MIN_BYTES + ' 字节）')
  }
  if (type === 'screenshot') {
    const shots = (ctx.screenshots || []).filter((s) => Number(s.mtimeMs) >= since && Number(s.mtimeMs) <= Number(ctx.now))
    const data = '立项后新截图 ' + shots.length + ' 张'
    if (shots.length) return { verdict: 'done', pass: true, data, missing: '' }
    return miss(data, '截一张能看出完成情况的图（立项之后截的才算）')
  }
  if (type === 'app') {
    const need = Number((evidence.match(MIN_RE) || [])[1]) || 1
    const words = evidence.replace(MIN_RE, ' ').split(/[\s，,、]+/).filter((w) => w.length >= 2)
    const row = (ctx.usage || []).find((u) => {
      const label = String(u?.label || u?.app || '')
      return label && words.some((w) => label.includes(w) || w.includes(label))
    })
    if (!row) return miss('使用记录里没有「' + words.join(' ') + '」', '去用「' + words.join(' ') + '」' + need + ' 分钟')
    const got = Math.round(Number(row.minutes) || 0)
    const data = String(row.label || row.app) + ' 今天 ' + got + ' 分钟（要求 ' + need + '）'
    if (got >= need) return { verdict: 'done', pass: true, data, missing: '' }
    if (got > 0) return { verdict: 'partial', pass: false, data, missing: '还差 ' + (need - got) + ' 分钟' }
    return miss(data, '去用够 ' + need + ' 分钟')
  }
  return miss('完成物「' + (evidence || '空') + '」墙查不了', HOW)
}

function miss(data, missing) {
  return { verdict: 'miss', pass: false, data, missing }
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

/** 列出 since 之后的截图（只看文件名像图片的）。读不到的目录跳过。 */
export function listScreenshots(dirs = SCREENSHOT_DIRS, since = 0) {
  const out = []
  for (const dir of dirs) {
    let names = []
    try {
      names = readdirSync(dir)
    } catch {
      continue
    }
    for (const name of names) {
      if (!/\.(png|jpe?g|webp)$/i.test(name)) continue
      const st = statOf(join(dir, name))
      if (st && st.mtimeMs >= since) out.push({ name, mtimeMs: st.mtimeMs })
    }
  }
  return out
}
