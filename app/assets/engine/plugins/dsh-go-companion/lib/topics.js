// dsh-go-companion · 话题池（只读复用现有 SQLite）
//
// 不新建记忆库：从 dsh-go-memory 的 fish-memory.sqlite 里读
//   topics（未完成的事）、facts（钉住/重要）、episodes（最近共同经历）。
// 所有 SQL 都是固定文本 + 参数绑定，不拼接任何外部输入。

import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'

const require = createRequire(import.meta.url)
let DatabaseSync = null
try {
  ;({ DatabaseSync } = require('node:sqlite'))
} catch {
  DatabaseSync = null
}

export const BACKGROUND_LIMIT = 5

export function openMemoryDb(path) {
  if (!DatabaseSync || !path || !existsSync(path)) return null
  try {
    return new DatabaseSync(path)
  } catch {
    return null
  }
}

const TOPICS_SQL =
  "SELECT id, title, detail, priority, opened_at, last_touch_at, next_ask_at FROM topics " +
  "WHERE status = 'open' AND scope IN (?, 'global') ORDER BY priority DESC, opened_at ASC LIMIT 30"

const FACTS_SQL =
  'SELECT id, text, importance, pinned FROM facts ' +
  "WHERE status = 'active' AND (pinned = 1 OR importance = 2) AND scope IN (?, 'global') " +
  'ORDER BY pinned DESC, importance DESC, updated_at DESC LIMIT 8'

const EPISODES_SQL =
  'SELECT text, at FROM episodes WHERE at >= ? AND scope IN (?, ?) ORDER BY at DESC LIMIT 5'

/**
 * @param {import('node:sqlite').DatabaseSync|null} db
 * @param {{sessionId?: string, now: number}} input
 */
export function loadCandidates(db, input) {
  if (!db) return { topics: [], facts: [], episodes: [] }
  const now = Number(input.now) || Date.now()
  const session = input.sessionId ? 'session:' + input.sessionId : 'global'
  const since = now - 48 * 3_600_000
  try {
    return {
      topics: db.prepare(TOPICS_SQL).all(session),
      facts: db.prepare(FACTS_SQL).all(session),
      episodes: db.prepare(EPISODES_SQL).all(since, session, 'global'),
    }
  } catch {
    return { topics: [], facts: [], episodes: [] }
  }
}

/**
 * 话题选择：未完成话题优先，其次最近经历（分享/吐槽）。
 * 同一个话题在 cooldownMs 内不重复；下次可问时间没到的不选。
 */
export function chooseTopic(candidates, options = {}) {
  const now = Number(options.now) || Date.now()
  const lastTopicId = String(options.lastTopicId || '')
  const lastAt = Number(options.lastAt) || 0
  const cooldownMs = Number(options.cooldownMs) || 24 * 3_600_000
  const lastRecent = lastTopicId && lastAt > 0 && now - lastAt < cooldownMs

  const scored = []
  for (const t of candidates.topics || []) {
    if (t.next_ask_at && Number(t.next_ask_at) > now) continue
    const id = 'topic:' + String(t.id)
    if (lastRecent && id === lastTopicId) continue
    const ageDays = Math.max(0, (now - (Number(t.opened_at) || now)) / 86_400_000)
    const priority = Number(t.priority) || 1
    const detail = t.detail ? '（' + String(t.detail).slice(0, 120) + '）' : ''
    scored.push({
      id,
      kind: 'unfinished',
      text: String(t.title || '').slice(0, 160) + detail,
      priority,
      at: Number(t.opened_at) || now,
      score: 100 + priority * 10 + Math.min(30, ageDays),
    })
  }
  for (const e of candidates.episodes || []) {
    const id = 'event:' + String(e.at)
    if (lastRecent && id === lastTopicId) continue
    const hoursOld = Math.max(0, (now - (Number(e.at) || now)) / 3_600_000)
    scored.push({
      id,
      kind: 'share',
      text: String(e.text || '').slice(0, 160),
      priority: 1,
      at: Number(e.at) || now,
      score: 40 + Math.max(0, 48 - hoursOld) * 0.2,
    })
  }
  scored.sort((a, b) => b.score - a.score || a.at - b.at)
  return scored[0] || null
}

/** 给 prompt 的背景事实：最多 5 条，按钉住/重要度。 */
export function backgroundFacts(candidates, limit = 5) {
  return (candidates.facts || [])
    .map((f) => String(f.text || '').trim())
    .filter(Boolean)
    .slice(0, Math.max(0, limit))
}
