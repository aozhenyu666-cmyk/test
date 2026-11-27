/**
 * 双鱼三层省 Token 混合记忆路由器（Layer 0 稳定底稿 + Layer 1 精准命中 + Layer 2 随机涌现抽卡）
 *
 * 解决痛点：
 * - 既不要把全量 SQLite / 笔记塞进上下文狂烧 Token；
 * - 也不要死板检索导致每次都念叨同一两条；
 * - 精准度来自「近 4h 固执申报 + 场景/关键词命中 + 钉住事实」，随机涌现来自「15 分钟确定性窗口 LCG 加权抽卡」，
 *   并给出完全透明的 Token 账单与命中解释（供姐妹页 X 光卡片实时查看）。
 */

import { estimateTokens } from './memory_blocks.js'

export const MEMORY_BUDGET_DEFAULT = 900

function clean(raw, max = 140) {
  return String(raw == null ? '' : raw).replace(/\s+/g, ' ').trim().slice(0, max)
}

function tokenizeQuery(raw = '') {
  const s = clean(raw, 300).toLowerCase()
  if (!s) return []
  const chunks = s.split(/[\s,，。！？!?:：/|、；;()[\]{}]+/).filter((t) => t.length >= 2)
  const out = new Set(chunks)
  // Extract 2-char CJK bi-grams for Chinese partial matching without external segmentation libs
  const cjkOnly = s.replace(/[^\u4e00-\u9fff]/g, '')
  for (let i = 0; i < cjkOnly.length - 1 && out.size < 28; i += 1) {
    out.add(cjkOnly.slice(i, i + 2))
  }
  return [...out]
}

function makeSeededRng(seedNum) {
  let state = (Number(seedNum) >>> 0) || 1
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    return state / 4294967296
  }
}

function normalizeCandidateItem(raw, fallbackKind = 'fact') {
  if (!raw) return null
  if (typeof raw === 'string') {
    const text = clean(raw, 150)
    return text ? { id: `str-${text.slice(0, 24)}`, text, kind: fallbackKind, weight: 1.0, at: 0, tags: [] } : null
  }
  const text = clean(raw.text || raw.claim || raw.fact || raw.summary || '', 150)
  if (!text) return null
  return {
    id: clean(raw.id || `${fallbackKind}-${text.slice(0, 20)}`, 64),
    text,
    kind: clean(raw.kind || fallbackKind, 24),
    category: clean(raw.category || '', 24),
    pinned: Boolean(raw.pinned),
    importance: Number(raw.importance) || 2,
    weight: Number(raw.weight) || 1.0,
    at: Number(raw.at || raw.updatedAt || raw.updated_at || raw.createdAt || raw.created_at || 0),
    tags: Array.isArray(raw.tags) ? raw.tags.map((t) => clean(t, 24)).filter(Boolean) : [],
    seenCount: Number(raw.seenCount || raw.hits || 0),
  }
}

/**
 * 装配并解释三层混合记忆：
 * - Layer 0：人设底稿压缩（<= 240 tokens）
 * - Layer 1：精准命中 Top-3（最近申报 + Lorebook + SQLite 关键词/重要度）
 * - Layer 2：随机涌现 1~2 条（15 分钟窗口稳定种子 + 排除近期已抽 ID，兼顾省缓存与活人感）
 */
export function routeHybridMemory(input = {}, now = Date.now()) {
  const budgetTokens = Math.max(360, Math.min(2000, Number(input.budgetTokens) || MEMORY_BUDGET_DEFAULT))
  const layer0Cap = Math.min(240, Math.round(budgetTokens * 0.28))
  const layer1Cap = Math.min(380, Math.round(budgetTokens * 0.42))
  const layer2Cap = Math.max(120, budgetTokens - layer0Cap - layer1Cap)

  const personaSummary = clean(
    input.personaSummary || '鱼丸陪在场不端架子、鲸澜只抓逻辑漏洞与长线主干；严禁老妈子说教或空洞套话。',
    180,
  )
  let layer0Text = `【设定底稿】${personaSummary}`
  while (estimateTokens(layer0Text) > layer0Cap && layer0Text.length > 40) {
    layer0Text = layer0Text.slice(0, Math.floor(layer0Text.length * 0.8))
  }
  const layer0Tokens = estimateTokens(layer0Text)

  const stagingList = (Array.isArray(input.stagingItems) ? input.stagingItems : [])
    .map((x) => normalizeCandidateItem(x, 'staging'))
    .filter(Boolean)
  const loreList = (Array.isArray(input.lorebookHits) ? input.lorebookHits : [])
    .map((x) => normalizeCandidateItem(x, 'lorebook'))
    .filter(Boolean)
  const factList = (Array.isArray(input.sqliteFacts) ? input.sqliteFacts : [])
    .map((x) => normalizeCandidateItem(x, 'fact'))
    .filter(Boolean)
  const episodeList = (Array.isArray(input.episodes) ? input.episodes : [])
    .map((x) => normalizeCandidateItem(x, 'episode'))
    .filter(Boolean)
  const poolList = (Array.isArray(input.knowledgePool) ? input.knowledgePool : [])
    .filter((x) => x && x.status !== 'archived' && x.status !== 'graduated')
    .map((x) => normalizeCandidateItem({ ...x, text: x.claim }, 'knowledge'))
    .filter(Boolean)

  // Estimate cost of stuffing ALL available memory naively vs gated injection
  const allNaiveText = [...stagingList, ...loreList, ...factList, ...episodeList, ...poolList]
    .map((x) => x.text)
    .join('\n')
  const fullPoolTokensEstimate = Math.max(
    layer0Tokens,
    layer0Tokens + estimateTokens(allNaiveText),
  )

  const queryTerms = tokenizeQuery(input.queryText || '')
  const sceneSet = new Set(
    (Array.isArray(input.sceneTags) ? input.sceneTags : [])
      .map((s) => clean(s, 40).toLowerCase())
      .filter(Boolean),
  )

  const scoredPrecise = []
  const seenTextNorm = new Set()

  const scoreAndPush = (item, baseBoost, baseReason) => {
    const norm = item.text.toLowerCase()
    if (!norm || seenTextNorm.has(norm)) return
    seenTextNorm.add(norm)
    const hay = `${norm} ${item.tags.join(' ').toLowerCase()} ${item.category.toLowerCase()}`
    let kwHits = 0
    for (const term of queryTerms) {
      if (hay.includes(term)) kwHits += 1
    }
    let sceneHits = 0
    for (const tag of item.tags) {
      if (sceneSet.has(tag.toLowerCase())) sceneHits += 1
    }
    const ageMs = item.at > 0 ? Math.max(0, now - item.at) : Infinity
    const recencyBoost = ageMs <= 4 * 3_600_000 ? 2.4 : (ageMs <= 24 * 3_600_000 ? 1.1 : 0)
    const pinBoost = item.pinned ? 1.6 : 0
    const impBoost = Math.min(4, item.importance) * 0.35
    const score = baseBoost + recencyBoost + pinBoost + impBoost + kwHits * 1.4 + sceneHits * 1.8

    const reasons = [baseReason]
    if (ageMs <= 4 * 3_600_000) reasons.push('近4h即时态')
    if (kwHits > 0) reasons.push('关键词命中')
    if (sceneHits > 0) reasons.push('场景匹配')
    if (item.pinned) reasons.push('钉住核心')

    scoredPrecise.push({
      id: item.id,
      text: item.text,
      kind: item.kind,
      category: item.category,
      score: Number(score.toFixed(2)),
      reasons: reasons.slice(0, 3),
    })
  }

  for (const s of stagingList) scoreAndPush(s, 2.8, '临时申报库')
  for (const l of loreList) scoreAndPush(l, 2.4, 'Lorebook触发')
  for (const f of factList) scoreAndPush(f, 1.0, f.pinned ? '核心记忆' : 'SQLite记忆')

  scoredPrecise.sort((a, b) => b.score - a.score)

  const preciseHits = []
  let l1Tokens = 0
  for (const cand of scoredPrecise) {
    if (preciseHits.length >= 3) break
    const line = `- [${cand.reasons[0]}] ${cand.text}`
    const cost = estimateTokens(line)
    if (l1Tokens + cost > layer1Cap && preciseHits.length > 0) continue
    preciseHits.push(cand)
    l1Tokens += cost
  }

  const chosenTextSet = new Set(preciseHits.map((x) => x.text.toLowerCase()))
  const recentSet = new Set((Array.isArray(input.recentDrawnIds) ? input.recentDrawnIds : []).map((x) => String(x)))

  const sparkCandidatesRaw = [...episodeList, ...factList, ...poolList]
    .filter((c) => !chosenTextSet.has(c.text.toLowerCase()))
  const dedupSpark = []
  const sparkNormSet = new Set()
  for (const c of sparkCandidatesRaw) {
    const n = c.text.toLowerCase()
    if (sparkNormSet.has(n)) continue
    sparkNormSet.add(n)
    dedupSpark.push(c)
  }

  let eligibleSparks = dedupSpark.filter((c) => !recentSet.has(c.id))
  if (!eligibleSparks.length) eligibleSparks = dedupSpark

  const defaultSeed = Math.floor(now / (15 * 60_000)) + 13
  const rng = makeSeededRng(input.seed !== undefined && input.seed !== null ? input.seed : defaultSeed)

  const randomSparks = []
  let l2Tokens = 0
  const remaining = [...eligibleSparks]
  while (randomSparks.length < 2 && remaining.length > 0) {
    let totalW = 0
    const weights = remaining.map((c) => {
      const freshBonus = Number(c.seenCount || 0) === 0 ? 1.45 : 1.0
      const w = Math.max(0.25, Number(c.weight || 1.0) * freshBonus)
      totalW += w
      return w
    })
    let roll = rng() * totalW
    let idx = 0
    for (let i = 0; i < weights.length; i += 1) {
      roll -= weights[i]
      if (roll <= 0) {
        idx = i
        break
      }
    }
    const picked = remaining.splice(idx, 1)[0]
    const line = `- [随机唤起·${picked.kind}] ${picked.text}`
    const cost = estimateTokens(line)
    if (l2Tokens + cost > layer2Cap && randomSparks.length > 0) break
    randomSparks.push({
      id: picked.id,
      text: picked.text,
      kind: picked.kind,
      reason: picked.kind === 'episode' ? '共同经历随机想起' : (picked.kind === 'knowledge' ? '知识海随机抽卡' : '旧记忆偶然闪回'),
    })
    l2Tokens += cost
  }

  const sections = [layer0Text]
  if (preciseHits.length) {
    sections.push('【精准上下文（近态+核心）】\n' + preciseHits.map((x) => `- [${x.reasons.join('·')}] ${x.text}`).join('\n'))
  }
  if (randomSparks.length) {
    sections.push('【随机涌现（省Token活人记忆抽卡）】\n' + randomSparks.map((x) => `- [${x.reason}] ${x.text}`).join('\n'))
  }

  let promptBlock = sections.join('\n\n')
  while (estimateTokens(promptBlock) > budgetTokens && randomSparks.length > 0) {
    randomSparks.pop()
    const nextSections = [layer0Text]
    if (preciseHits.length) {
      nextSections.push('【精准上下文（近态+核心）】\n' + preciseHits.map((x) => `- [${x.reasons.join('·')}] ${x.text}`).join('\n'))
    }
    if (randomSparks.length) {
      nextSections.push('【随机涌现（省Token活人记忆抽卡）】\n' + randomSparks.map((x) => `- [${x.reason}] ${x.text}`).join('\n'))
    }
    promptBlock = nextSections.join('\n\n')
  }

  const injectedTokens = estimateTokens(promptBlock)
  const savedTokens = Math.max(0, fullPoolTokensEstimate - injectedTokens)
  const savedPct = fullPoolTokensEstimate > 0
    ? Math.max(0, Math.min(99, Math.round((savedTokens / fullPoolTokensEstimate) * 100)))
    : 0

  return {
    promptBlock,
    injectedTokens,
    budgetTokens,
    fullPoolTokensEstimate,
    savedTokens,
    savedPct,
    drawnIds: randomSparks.map((x) => x.id),
    explain: {
      layer0Tokens,
      injectedTokens,
      budgetTokens,
      fullPoolTokensEstimate,
      savedTokens,
      savedPct,
      preciseHits,
      randomSparks,
      stagingCount: stagingList.length,
      totalPoolCount: stagingList.length + loreList.length + factList.length + episodeList.length + poolList.length,
      updatedAt: now,
    },
  }
}
