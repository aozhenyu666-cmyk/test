// dsh-go-companion · 自由口径三层主线引擎 (Horizons: 今日 / 中期战役 / 长期主航道)
// 不被周报月报日历格律绑架：
// - 长期 (long): 终局主航道（如“考公上岸”“核心岗位就业落地”），与 Obsidian 长期规划.md 双向同步；
// - 中期 (medium): 不固定天数的阶段战役（3–14+ 天，如“资料分析 200 题”“简历与模拟面 15 场”）；
// - 今日 (short): 今日作战单（关联 parentMediumId / parentLongId，做完自动回涨上层进展）。

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

const YMD_RE = /^\d{4}-\d{2}-\d{2}$/
/** 截止日（日历）：只认 YYYY-MM-DD，其他一律当没有。 */
export function cleanDue(v) {
  const s = String(v || '').trim()
  return YMD_RE.test(s) ? s : ''
}

// ---------------------------------------------------------------- 预留时间节点（2026-11-20 · P0）
//
// 一条长期 / 中期目标下面挂若干节点：到哪天、交什么、预计几局几分钟。
// 节点的执行记录（哪一局、几分钟、结果）由局结束时自动写回，不靠手填；work.json 每天清空，这里是唯一的历史。
// 有节点的目标，进度 = 达成的节点数 / 节点总数（自动算，不再手动 +1）。

export const NODE_STATUSES = ['planned', 'active', 'passed', 'slipped']
export const NODE_OUTCOMES = ['passed', 'done', 'partial', 'escalated', 'overdue_locked']
export const MAX_NODES = 16
export const MAX_NODE_LOG = 30

const clampInt = (v, lo, hi, d) => {
  const n = Math.round(Number(v))
  return Number.isFinite(n) ? Math.max(lo, Math.min(hi, n)) : d
}

export function cleanNode(raw) {
  if (!raw || typeof raw !== 'object') return null
  const id = String(raw.id || '').trim().slice(0, 40)
  const title = String(raw.title || '').trim().slice(0, 80)
  if (!id || !title) return null
  const budgetBouts = clampInt(raw.budgetBouts, 1, 30, 3)
  const log = (Array.isArray(raw.executionLog) ? raw.executionLog : [])
    .filter((e) => e && typeof e === 'object' && String(e.workId || '').trim())
    .map((e) => ({
      workId: String(e.workId).trim().slice(0, 64),
      day: String(e.day || '').slice(0, 10),
      minutes: clampInt(e.minutes, 0, 600, 0),
      outcome: NODE_OUTCOMES.includes(e.outcome) ? e.outcome : 'done',
      summary: String(e.summary || '').replace(/\s+/g, ' ').trim().slice(0, 160),
      at: Number(e.at) || 0,
    }))
    .slice(-MAX_NODE_LOG)
  return {
    id,
    title,
    targetDate: cleanDue(raw.targetDate),
    deliverable: String(raw.deliverable || '').trim().slice(0, 200),
    budgetBouts,
    budgetMinutes: clampInt(raw.budgetMinutes, 0, 6000, budgetBouts * 45),
    spentMinutes: clampInt(raw.spentMinutes, 0, 100000, 0),
    defaultApps: (Array.isArray(raw.defaultApps) ? raw.defaultApps : []).map((x) => String(x || '').trim()).filter(Boolean).slice(0, 12),
    status: NODE_STATUSES.includes(raw.status) ? raw.status : 'planned',
    lastBreakpoint: String(raw.lastBreakpoint || '').trim().slice(0, 200),
    executionLog: log,
    reviewNote: String(raw.reviewNote || '').trim().slice(0, 240),
    createdAt: Number(raw.createdAt) || Date.now(),
  }
}

/** 有节点时进度由节点推导：达成几个 / 一共几个。 */
export function withDerivedProgress(item) {
  const nodes = Array.isArray(item.nodes) ? item.nodes : []
  if (!nodes.length) return item
  const passed = nodes.filter((n) => n.status === 'passed').length
  return { ...item, targetSteps: nodes.length, doneSteps: passed, status: item.status === 'archived' ? 'archived' : (passed >= nodes.length ? 'done' : 'active') }
}

export function cleanHorizons(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const normItem = (it, defaultHorizon) => {
    if (!it || typeof it !== 'object') return null
    const id = String(it.id || '').trim()
    const title = String(it.title || '').trim().slice(0, 100)
    if (!id || !title) return null
    return {
      id,
      horizon: String(it.horizon || defaultHorizon),
      title,
      note: String(it.note || '').trim().slice(0, 240),
      parentId: String(it.parentId || '').trim(),
      spanHint: String(it.spanHint || (defaultHorizon === 'long' ? '长期主航道' : '阶段战役')).slice(0, 32),
      targetSteps: Math.max(1, Math.min(100, Number(it.targetSteps) || 5)),
      doneSteps: Math.max(0, Number(it.doneSteps) || 0),
      status: it.status === 'done' || it.status === 'archived' ? it.status : 'active',
      due: cleanDue(it.due),
      createdAt: Number(it.createdAt) || Date.now(),
      updatedAt: Number(it.updatedAt) || Date.now(),
      nodes: (Array.isArray(it.nodes) ? it.nodes : []).map(cleanNode).filter(Boolean).slice(0, MAX_NODES),
    }
  }
  return {
    long: (Array.isArray(s.long) ? s.long : []).map((x) => normItem(x, 'long')).filter(Boolean).map(withDerivedProgress),
    medium: (Array.isArray(s.medium) ? s.medium : []).map((x) => normItem(x, 'medium')).filter(Boolean).map(withDerivedProgress),
  }
}

/** 找一条目标（长期 / 中期都找）。 */
export function findHorizon(state, id) {
  const t = String(id || '')
  return [...(state?.long || []), ...(state?.medium || [])].find((x) => x.id === t) || null
}

/** 把一条目标换成 next（next = fn(旧的)），返回新的整张表。 */
export function mapHorizon(stateRaw, id, fn) {
  const s = cleanHorizons(stateRaw)
  const t = String(id || '')
  const map = (list) => list.map((x) => (x.id === t ? withDerivedProgress(fn(x)) : x))
  return { long: map(s.long), medium: map(s.medium) }
}

export function syncLongGoalsFromMarkdown(horizonsRaw, mdPath) {
  const state = cleanHorizons(horizonsRaw)
  if (!mdPath || !existsSync(mdPath)) return state
  try {
    const content = readFileSync(mdPath, 'utf8')
    const lines = content.split(/\r?\n/)
    const existingTitles = new Set(state.long.map((x) => x.title))
    for (const line of lines) {
      const m = line.match(/^\s*(?:[-*]|\d+\.)\s+(?:\[[ xX]\]\s*)?(.+)$/)
      if (!m) continue
      const title = m[1].replace(/（.*?）/g, '').trim().slice(0, 100)
      if (!title || title.startsWith('#') || existingTitles.has(title)) continue
      existingTitles.add(title)
      const isDone = /^\s*(?:[-*]|\d+\.)\s+\[[xX]\]/.test(line)
      const dueM = m[1].match(/截止\s*(\d{4}-\d{2}-\d{2})/)
      state.long.push({
        due: dueM ? dueM[1] : '',
        id: `long-${state.long.length + 1}-${Date.now().toString(36).slice(-4)}`,
        horizon: 'long',
        title,
        note: '同步自 长期规划.md',
        parentId: '',
        spanHint: '长期主航道',
        targetSteps: 10,
        doneSteps: isDone ? 10 : 0,
        status: isDone ? 'done' : 'active',
        createdAt: Date.now(),
        updatedAt: Date.now(),
      })
    }
  } catch {
    // ignore read error
  }
  return state
}

export function mirrorHorizonsToMarkdown(stateRaw, mdPath) {
  if (!mdPath) return
  try {
    const s = cleanHorizons(stateRaw)
    mkdirSync(dirname(mdPath), { recursive: true })
    const lines = ['# 长期规划与中期战役（DSH Go 三层主线双向镜像）', '']
    lines.push('## 一、长期主航道（终局方向）')
    if (!s.long.length) lines.push('- （暂无，请在主控台「三层主线 · 长期」添加你的就业/考公核心目标）')
    for (const item of s.long) {
      const mark = item.status === 'done' ? 'x' : ' '
      lines.push(`- [${mark}] ${item.title}（${item.spanHint} · 进度 ${item.doneSteps}/${item.targetSteps}${item.due ? ` · 截止 ${item.due}` : ''}）${item.note ? ` — ${item.note}` : ''}`)
    }
    lines.push('', '## 二、中期战役（不定期阶段攻坚）')
    if (!s.medium.length) lines.push('- （暂无阶段战役）')
    for (const item of s.medium) {
      const mark = item.status === 'done' ? 'x' : ' '
      lines.push(`- [${mark}] ${item.title}（${item.spanHint} · 进度 ${item.doneSteps}/${item.targetSteps}${item.due ? ` · 截止 ${item.due}` : ''}）${item.note ? ` — ${item.note}` : ''}`)
    }
    writeFileSync(mdPath, lines.join('\n') + '\n', 'utf8')
  } catch {
    // ignore write error
  }
}

export function upsertHorizonItem(stateRaw, payload = {}, now = Date.now()) {
  const s = cleanHorizons(stateRaw)
  const bucket = payload.horizon === 'long' ? 'long' : 'medium'
  const title = String(payload.title || '').trim().slice(0, 100)
  if (!title && !payload.id) return { ok: false, error: '请填写目标标题', state: s }

  const list = [...s[bucket]]
  const idx = payload.id ? list.findIndex((x) => x.id === String(payload.id)) : -1
  if (idx >= 0) {
    const prev = list[idx]
    // 有节点的目标：进度由节点推导，老的「+1」不再动它。
    if (payload.bumpStep && Array.isArray(prev.nodes) && prev.nodes.length) {
      return { ok: true, item: prev, state: s }
    }
    const nextDone = payload.bumpStep
      ? Math.min(prev.targetSteps, prev.doneSteps + 1)
      : (payload.doneSteps !== undefined ? Math.max(0, Number(payload.doneSteps) || 0) : prev.doneSteps)
    const nextStatus = payload.status
      || (nextDone >= prev.targetSteps ? 'done' : prev.status)
    list[idx] = {
      ...prev,
      ...(title ? { title } : {}),
      ...(payload.note !== undefined ? { note: String(payload.note).trim().slice(0, 240) } : {}),
      ...(payload.spanHint !== undefined ? { spanHint: String(payload.spanHint).trim().slice(0, 32) } : {}),
      ...(payload.parentId !== undefined ? { parentId: String(payload.parentId).trim() } : {}),
      ...(payload.targetSteps !== undefined ? { targetSteps: Math.max(1, Math.min(100, Number(payload.targetSteps) || 5)) } : {}),
      ...(payload.due !== undefined ? { due: cleanDue(payload.due) } : {}),
      doneSteps: nextDone,
      status: nextStatus,
      updatedAt: now,
    }
    const next = { ...s, [bucket]: list }
    return { ok: true, item: list[idx], state: next }
  }

  const item = {
    id: `${bucket === 'long' ? 'L' : 'M'}-${now.toString(36).slice(-5)}-${Math.floor(Math.random() * 90 + 10)}`,
    horizon: bucket,
    title,
    note: String(payload.note || '').trim().slice(0, 240),
    parentId: String(payload.parentId || '').trim(),
    spanHint: String(payload.spanHint || (bucket === 'long' ? '长期主航道' : '阶段战役')).slice(0, 32),
    targetSteps: Math.max(1, Math.min(100, Number(payload.targetSteps) || (bucket === 'long' ? 10 : 5))),
    doneSteps: 0,
    status: 'active',
    due: cleanDue(payload.due),
    createdAt: now,
    updatedAt: now,
    nodes: [],
  }
  list.unshift(item)
  return { ok: true, item, state: { ...s, [bucket]: list.slice(0, 40) } }
}

export function deleteHorizonItem(stateRaw, id) {
  const s = cleanHorizons(stateRaw)
  const target = String(id || '').trim()
  return {
    long: s.long.filter((x) => x.id !== target),
    medium: s.medium.filter((x) => x.id !== target),
  }
}

export function archivePathInKb(kbDir) {
  return kbDir ? join(kbDir, '长期规划.md') : ''
}
