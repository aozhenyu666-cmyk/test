// dsh-go-memory · Lorebook 条件触发检索（M1.2）
//
// 参考 SillyTavern World Info / Lorebook：高价值规则、陷阱卡、领域知识平时不占满上下文，
// 只有在当前对话文本 / 历史滑窗命中 keyword / regex triggers，或手机状态命中 scene_tags 时，
// 才按优先级注入有限槽位（默认 ≤ 3 条）。

import { clarity, isExpired, normalizeText, publicFact, visibleScopes } from './model.js'

const MAX_TRIGGERS = 12
const MAX_PATTERN_LEN = 120
const MAX_SCENE_TAGS = 8
const REGEX_LITERAL_RE = /^\/(.+)\/([imsu]*)$/

/** 规范化触发词列表：支持普通关键词与 `/regex/flags`（带非法正则与超长防护）。 */
export function normalizeTriggers(raw) {
  const list = Array.isArray(raw) ? raw : typeof raw === 'string' && raw.trim() ? [raw] : []
  const seen = new Set()
  const out = []
  for (const item of list) {
    if (out.length >= MAX_TRIGGERS) break
    const rawText = typeof item === 'string'
      ? item
      : item && typeof item === 'object'
        ? String(item.pattern ?? item.keyword ?? '')
        : ''
    const cleaned = normalizeText(rawText).slice(0, MAX_PATTERN_LEN)
    if (!cleaned || seen.has(cleaned)) continue
    const lit = REGEX_LITERAL_RE.exec(cleaned)
    if (lit) {
      try {
        // g 标志会导致有状态 lastIndex 漂移，统一去掉。
        const flags = lit[2].replace(/g/g, '')
        new RegExp(lit[1], flags)
      } catch {
        continue
      }
    }
    seen.add(cleaned)
    out.push(cleaned)
  }
  return out
}

/** 规范化场景标签：如 `scene:video_learning`、`scene:commute`、`app:bilibili`。 */
export function normalizeSceneTags(raw) {
  const list = Array.isArray(raw) ? raw : typeof raw === 'string' && raw.trim() ? [raw] : []
  const seen = new Set()
  const out = []
  for (const item of list) {
    if (out.length >= MAX_SCENE_TAGS) break
    const tag = normalizeText(item).toLowerCase().slice(0, 40)
    if (!tag || seen.has(tag)) continue
    seen.add(tag)
    out.push(tag)
  }
  return out
}

function testTrigger(pattern, hayLower, hayRaw) {
  const lit = REGEX_LITERAL_RE.exec(pattern)
  if (lit) {
    try {
      const re = new RegExp(lit[1], lit[2].replace(/g/g, '') || 'i')
      return re.test(hayRaw)
    } catch {
      return false
    }
  }
  return hayLower.includes(pattern.toLowerCase())
}

/**
 * 扫描命中的 Lorebook 条目，按 场景命中 > 触发词命中 > pinned/重要度 > 清晰度 排序，限额返回。
 * @returns {{items: object[], matchedIds: string[], reasons: Record<string, string[]>}}
 */
export function matchLorebook(doc, input = {}, now = Date.now()) {
  const scopes = visibleScopes(input.scope)
  const limit = Math.max(1, Math.min(8, Math.round(Number(input.limit) || 3)))
  const exclude = new Set(Array.isArray(input.excludeIds) ? input.excludeIds : [])
  const activeScenes = new Set(normalizeSceneTags(input.sceneTags))
  const hayRaw = [normalizeText(input.query), normalizeText(input.historyText)].filter(Boolean).join('\n')
  const hayLower = hayRaw.toLowerCase()

  const candidates = []
  for (const f of (doc && Array.isArray(doc.facts) ? doc.facts : [])) {
    if (!f || f.status !== 'active' || isExpired(f, now)) continue
    if (!scopes.includes(f.scope) || exclude.has(f.id)) continue
    const triggers = normalizeTriggers(f.triggers)
    const sceneTags = normalizeSceneTags(f.sceneTags)
    if (!triggers.length && !sceneTags.length) continue

    const sceneHits = []
    for (const st of sceneTags) {
      if (activeScenes.has(st)) sceneHits.push('scene:' + st)
    }
    const triggerHits = []
    if (hayRaw) {
      for (const pat of triggers) {
        if (testTrigger(pat, hayLower, hayRaw)) triggerHits.push('kw:' + pat)
      }
    }
    if (!sceneHits.length && !triggerHits.length) continue

    const score =
      sceneHits.length * 2.5 +
      triggerHits.length * 1.5 +
      (f.pinned ? 1.5 : 0) +
      (Number(f.importance) || 0) * 0.4 +
      clarity(f, now) * 0.3
    candidates.push({ fact: f, score, why: [...sceneHits, ...triggerHits] })
  }

  candidates.sort((a, b) => b.score - a.score || (b.fact.updatedAt || 0) - (a.fact.updatedAt || 0))
  const picked = candidates.slice(0, limit)
  const reasons = {}
  for (const c of picked) reasons[c.fact.id] = c.why
  return {
    items: picked.map((c) => publicFact(c.fact)),
    matchedIds: picked.map((c) => c.fact.id),
    reasons,
  }
}
