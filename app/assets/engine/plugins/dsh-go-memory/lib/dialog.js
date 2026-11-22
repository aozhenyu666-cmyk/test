// 大肥鱼本地记忆 · 对话流水
//
// 事实库只装模型想起来要 remember 的东西，而模型经常不记——于是「我们刚才聊了什么」
// 换个会话、过一夜就没了。这里不靠模型：他发出的每一句原话都落一行，
// 每轮召回时带上最近几句，和跟这一句对得上的旧话。
//
// 只记他本人说的（user 来源），不记插件塞进会话的提示（主动发球那种），不记模型的回答。
// 本地 JSONL，0600；超过上限从头裁。

import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { normalizeText, tokens } from './model.js'

export const DIALOG_FILE = 'dialog.jsonl'
export const DIALOG_MAX = 3000
const MAX_TEXT = 300

/** 口头禅一样的词，对上了也说明不了是同一件事。 */
const FILLER = new Set(['今天', '明天', '昨天', '现在', '一下', '知道', '觉得', '感觉', '有点', '还是', '没有', '一个',
  '自己', '时候', '真的', '帮我', '看看', '我想', '想要', '不想', '好像', '这样', '那样', '然后', '已经'])

/** 旧话和这一句对不对得上：去掉口头禅后，至少一个词对上，且占这一句的两成以上。 */
export function overlap(query, text) {
  const q = [...tokens(query)].filter((t) => !FILLER.has(t))
  if (!q.length) return 0
  const t = tokens(text)
  const hit = q.filter((x) => t.has(x)).length
  return hit ? hit / q.length : 0
}

/** 插件塞进会话的话（主动发球、系统提醒）不是他说的。 */
const PLUGIN_RE = /^[（(]这不是.{0,12}说的话|^\[系统|^<system|^（系统/

/**
 * 从引擎的 inbox 事件里认出「他本人说的话」。认不出就返回空串。
 * 消息带 source 时只收 kind=user；不带 source 的按内容兜底排除插件提示。
 */
export function userTextOf(payload) {
  try {
    const m = payload && payload.message ? payload.message : payload
    if (!m) return ''
    if (m.role && m.role !== 'user') return ''
    const kind = m.source && typeof m.source === 'object' ? String(m.source.kind || '') : ''
    if (kind && kind !== 'user') return ''
    let t = ''
    if (typeof m.text === 'string') t = m.text
    else if (Array.isArray(m.content)) t = m.content.filter((p) => p && p.type === 'text' && typeof p.text === 'string').map((p) => p.text).join(' ')
    t = normalizeText(t)
    if (!t || PLUGIN_RE.test(t)) return ''
    return t
  } catch {
    return ''
  }
}

export function dialogPath(dir) {
  return join(dir, DIALOG_FILE)
}

/** 读全部行（坏行跳过）。 */
export function readDialog(dir) {
  const file = dialogPath(dir)
  if (!existsSync(file)) return []
  const out = []
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line.trim()) continue
    try {
      const r = JSON.parse(line)
      if (r && typeof r.text === 'string' && Number(r.at) > 0) out.push(r)
    } catch {}
  }
  return out
}

/** 记一句。同一会话 10 秒内的同一句只记一次（引擎可能对同一条消息发两次事件）。 */
export function appendDialog(dir, { sid = '', text = '', at = Date.now() } = {}, cache = null) {
  const t = normalizeText(text).slice(0, MAX_TEXT)
  if (!t) return false
  const key = sid + '\u0000' + t
  if (cache) {
    const last = cache.get(key)
    if (last && at - last < 10_000) return false
    cache.set(key, at)
    if (cache.size > 200) cache.delete(cache.keys().next().value)
  }
  const file = dialogPath(dir)
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
  appendFileSync(file, JSON.stringify({ at, sid: String(sid || ''), text: t }) + '\n', { encoding: 'utf8', mode: 0o600 })
  return true
}

/** 超过上限就只留最新的 DIALOG_MAX 行（原子重写）。 */
export function trimDialog(dir, max = DIALOG_MAX) {
  const rows = readDialog(dir)
  if (rows.length <= max) return 0
  const keep = rows.slice(rows.length - max)
  const file = dialogPath(dir)
  const tmp = file + '.' + String(process.pid) + '.tmp'
  writeFileSync(tmp, keep.map((r) => JSON.stringify(r)).join('\n') + '\n', { encoding: 'utf8', mode: 0o600 })
  renameSync(tmp, file)
  return rows.length - keep.length
}

// PRoot 里的进程时区多半是 UTC，按北京时间写，和人设里其它时间一个口径。
function partsOf(at, tz) {
  const p = new Intl.DateTimeFormat('en-GB', { timeZone: tz, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    .formatToParts(new Date(at))
  const g = (t) => p.find((x) => x.type === t)?.value || ''
  return { md: g('month') + '/' + g('day'), hm: g('hour') + ':' + g('minute') }
}

function whenOf(at, now, tz = 'Asia/Shanghai') {
  try {
    const a = partsOf(at, tz)
    if (a.md === partsOf(now, tz).md) return '今天 ' + a.hm
    if (a.md === partsOf(now - 86_400_000, tz).md) return '昨天 ' + a.hm
    return a.md + ' ' + a.hm
  } catch {
    return ''
  }
}

/**
 * 召回用的几句：
 *   recent  最近 48 小时里最近的几句（跨会话：跟妹妹说的，姐姐也知道）
 *   related 更早的话里和这一句对得上的几句
 * 刚说的那一句（就是 query 本身）不再重复。
 */
export function dialogLines(rows, { query = '', now = Date.now(), recent = 6, related = 3, tz = 'Asia/Shanghai' } = {}) {
  const q = normalizeText(query)
  const all = rows.filter((r) => Number(r.at) <= now)
  const fresh = all.filter((r) => now - r.at < 48 * 3_600_000)
  let latest = fresh.slice(-recent - 1)
  if (q && latest.length && latest[latest.length - 1].text === q) latest = latest.slice(0, -1)
  latest = latest.slice(-recent)
  const taken = new Set(latest.map((r) => r.at + r.text))
  const older = q
    ? all
      .filter((r) => !taken.has(r.at + r.text) && r.text !== q)
      .map((r) => ({ r, s: overlap(q, r.text) }))
      .filter((x) => x.s >= 0.2)
      .sort((a, b) => b.s - a.s || b.r.at - a.r.at)
      .slice(0, related)
      .map((x) => x.r)
      .sort((a, b) => a.at - b.at)
    : []
  return [...older, ...latest].map((r) => ({ when: whenOf(r.at, now, tz), text: r.text.slice(0, 120) }))
}
