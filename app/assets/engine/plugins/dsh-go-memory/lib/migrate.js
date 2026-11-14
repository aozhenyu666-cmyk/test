// 大肥鱼本地记忆 · 一次性迁移
//
// 老版本把长期记忆存在 dsh-fish-memory 的 `<会话id>.json` 里（PC 上）。
// 手机上第一次打开本地记忆时，把这些旧条目搬进权威库，避免「换了权威就失忆」。
//
// 只读旧文件，不删不改：迁移出问题还能回到旧库。
// 旧库的 importance 是 0~1 的小数，这里按 0/1/2 三档换算（Muse 的模型）。

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { remember } from './model.js'

const SKIP = new Set(['memory.json', 'fish-memory.sqlite', 'fish-memory.sqlite-wal', 'fish-memory.sqlite-shm', 'export', '销毁前'])

export function importFishMemory(doc, options = {}) {
  const home = String(options.home || '').trim()
  const now = Number(options.now) || Date.now()
  const dir = join(home, 'fish-memory')
  const result = { ok: true, files: 0, added: 0, skipped: 0, failed: 0, sources: [] }
  if (!existsSync(dir)) return result

  let names = []
  try {
    names = readdirSync(dir)
  } catch {
    return result
  }
  for (const name of names) {
    if (!name.endsWith('.json') || SKIP.has(name)) continue
    const file = join(dir, name)
    try {
      if (!statSync(file).isFile()) continue
      const raw = JSON.parse(readFileSync(file, 'utf8'))
      const memories = Array.isArray(raw && raw.memories) ? raw.memories : []
      if (!memories.length) continue
      result.files += 1
      for (const m of memories) {
        const text = String((m && m.text) || '').replace(/\s+/g, ' ').trim()
        if (!text) {
          result.skipped += 1
          continue
        }
        const createdAt = Date.parse(m.createdAt || m.at || '') || now
        const importance = m.importance === undefined || m.importance === null ? 1
          : Number(m.importance) >= 0.85 ? 2 : Number(m.importance) >= 0.4 ? 1 : 0
        const tags = Array.isArray(m.tags) ? m.tags.slice(0, 8) : []
        const r = remember(doc, {
          text,
          importance,
          pinned: m.pinned === true || m.firstMet === true,
          tags: [...new Set([...tags, '迁移'])],
          key: typeof m.key === 'string' ? m.key : '',
          scope: 'global',
          source: 'fish-memory',
        }, Math.min(now, Math.max(0, createdAt)))
        if (r.ok) result.added += 1
        else result.skipped += 1
      }
      result.sources.push(name)
    } catch {
      result.failed += 1
    }
  }
  return result
}
