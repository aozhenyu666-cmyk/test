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
