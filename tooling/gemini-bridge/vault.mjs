// 大肥鱼 · Gemini 桥：电脑上的笔记库，只读。
//
// 和手机上的规矩一样：日记 / 私密目录连读都不读；只读 .md；单文件最多读 24 KB；最多扫 1500 个文件。
// 路径一律相对笔记库根，越界（..、绝对路径）直接拒。

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { isAbsolute, join, normalize, relative, sep } from 'node:path'

export const PRIVATE_DIRS = ['日记', 'Diary', 'diary', 'Journal', 'journal', '私密', 'Private', 'private', '.obsidian', '.trash', '.git']
const READ_BYTES = 24_000
const SCAN_LIMIT = 1500
const DEPTH = 6

function isPrivate(rel) {
  return rel.split(/[\\/]/).some((p) => PRIVATE_DIRS.includes(p) || p.startsWith('.'))
}

export function walk(root) {
  const out = []
  const go = (dir, depth) => {
    if (depth > DEPTH || out.length >= SCAN_LIMIT) return
    let names = []
    try {
      names = readdirSync(dir)
    } catch {
      return
    }
    for (const name of names) {
      if (out.length >= SCAN_LIMIT) return
      if (name.startsWith('.') || PRIVATE_DIRS.includes(name)) continue
      const p = join(dir, name)
      let st
      try {
        st = statSync(p)
      } catch {
        continue
      }
      if (st.isDirectory()) go(p, depth + 1)
      else if (name.endsWith('.md')) out.push({ path: p, rel: relative(root, p).split(sep).join('/'), mtime: st.mtimeMs, size: st.size })
    }
  }
  go(root, 0)
  return out
}

/** 笔记库里的一个相对路径 → 绝对路径；越界、私密、不是 .md 一律 null。 */
export function resolveNote(root, rel) {
  const r = String(rel || '').replace(/^[\\/]+/, '')
  if (!r || isAbsolute(r) || !r.endsWith('.md')) return null
  const abs = normalize(join(root, r))
  const back = relative(root, abs)
  if (back.startsWith('..') || isAbsolute(back) || isPrivate(back)) return null
  return abs
}

export function readNote(root, rel) {
  const abs = resolveNote(root, rel)
  if (!abs) return { ok: false, error: '这个路径不能读（不在笔记库里、是私密目录、或不是 .md）。' }
  try {
    const text = readFileSync(abs, 'utf8')
    return { ok: true, rel: String(rel), text: text.slice(0, READ_BYTES), truncated: text.length > READ_BYTES }
  } catch {
    return { ok: false, error: '读不到这个文件。' }
  }
}

/** 文件名 / 路径命中优先，其次正文命中；最近改过的在前。 */
export function searchNotes(root, query, { limit = 8 } = {}) {
  const q = String(query || '').trim().toLowerCase()
  if (!q) return []
  const tokens = q.split(/\s+/).filter((t) => t.length >= 1)
  const out = []
  for (const f of walk(root)) {
    let text = ''
    try {
      text = readFileSync(f.path, 'utf8').slice(0, READ_BYTES)
    } catch {
      continue
    }
    const lower = text.toLowerCase()
    const pathHit = f.rel.toLowerCase().includes(q)
    const all = tokens.every((t) => lower.includes(t) || f.rel.toLowerCase().includes(t))
    if (!pathHit && !all) continue
    const at = Math.max(0, lower.indexOf(tokens[0]))
    const snippet = text.slice(Math.max(0, at - 80), at + 220).replace(/\s+/g, ' ').trim()
    out.push({ rel: f.rel, mtime: f.mtime, snippet, score: (pathHit ? 5 : 0) + tokens.filter((t) => lower.includes(t)).length })
  }
  return out.sort((a, b) => b.score - a.score || b.mtime - a.mtime).slice(0, Math.max(1, Math.min(20, limit)))
}

export function recentNotes(root, { limit = 10, days = 7, now = Date.now() } = {}) {
  return walk(root)
    .filter((f) => now - f.mtime <= days * 86_400_000)
    .sort((a, b) => b.mtime - a.mtime)
    .slice(0, Math.max(1, Math.min(30, limit)))
    .map((f) => ({ rel: f.rel, mtime: f.mtime }))
}

const INBOX_REL = '大肥鱼/收件箱.md'
const INBOX_SNIPPET_CHARS = 2400

function parseInboxLine(body, fallbackDay) {
  let day = fallbackDay
  let time = ''
  let tag = ''
  let rest = body.trim()
  // 去掉可选任务复选框 `- [ ] ` / `- [x] `
  rest = rest.replace(/^\[[ xX]\]\s+/, '')
  const dateM = /^(\d{4}-\d{2}-\d{2})\s+(.*)$/.exec(rest)
  if (dateM) {
    day = dateM[1]
    rest = dateM[2].trim()
  }
  const timeFirst = /^(\d{1,2}:\d{2}(?::\d{2})?)\s+(.*)$/.exec(rest)
  if (timeFirst) {
    time = timeFirst[1]
    rest = timeFirst[2].trim()
  }
  const tagM = /^(?:\[([^\]]+)\]|【([^】]+)】)\s*(.*)$/.exec(rest)
  if (tagM) {
    tag = (tagM[1] || tagM[2] || '').trim()
    rest = tagM[3].trim()
  }
  if (!time) {
    const timeAfter = /^(\d{1,2}:\d{2}(?::\d{2})?)\s+(.*)$/.exec(rest)
    if (timeAfter) {
      time = timeAfter[1]
      rest = timeAfter[2].trim()
    }
  }
  return { day, time, tag, text: rest || body.trim() }
}

/** 安全读取电脑端「大肥鱼/收件箱.md」近期速记条目（只读，走 resolveNote 越界与私密目录防护）。 */
export function readInbox(root, { limit = 20 } = {}) {
  if (!root || typeof root !== 'string' || !root.trim()) {
    return { ok: false, exists: false, entries: [], raw: '', error: '未配置笔记库路径。' }
  }
  const abs = resolveNote(root, INBOX_REL)
  if (!abs) return { ok: false, exists: false, entries: [], raw: '', error: '收件箱路径不能读（不在笔记库里或受限）。' }
  let text
  try {
    text = readFileSync(abs, 'utf8')
  } catch (e) {
    if (e && (e.code === 'ENOENT' || e.code === 'ENOTDIR')) {
      return { ok: true, exists: false, entries: [], raw: '' }
    }
    return { ok: false, exists: false, entries: [], raw: '', error: '读不到收件箱文件。' }
  }
  const clean = text.replace(/^\uFEFF/, '')
  const lines = clean.split(/\r?\n/)
  const cap = Math.max(1, Math.min(50, Math.floor(Number(limit) || 20)))
  const all = []
  let currentDay = ''
  let headerIdx = -1
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const dayM = /^(?:#{1,6}\s*)?(\d{4}-\d{2}-\d{2})(?:\s.*)?$/.exec(line.trim())
    if (dayM) {
      currentDay = dayM[1]
      headerIdx = i
      continue
    }
    const itemM = /^\s*[-*]\s+(.+)$/.exec(line)
    if (!itemM) continue
    const body = itemM[1].trim()
    if (!body) continue
    const parsed = parseInboxLine(body, currentDay)
    all.push({ ...parsed, raw: line.trim(), _line: i, _header: headerIdx })
  }
  const entries = all.slice(-cap).map(({ _line, _header, ...item }) => item)
  let rawSnippet = ''
  if (all.length > 0) {
    const first = all[Math.max(0, all.length - cap)]
    const startLine = first._header >= 0 ? first._header : first._line
    rawSnippet = lines.slice(startLine).join('\n').trim()
    if (rawSnippet.length > INBOX_SNIPPET_CHARS) rawSnippet = rawSnippet.slice(-INBOX_SNIPPET_CHARS).trim()
  } else {
    rawSnippet = clean.trim().slice(-INBOX_SNIPPET_CHARS)
  }
  return { ok: true, exists: true, entries, total: all.length, rawSnippet }
}
