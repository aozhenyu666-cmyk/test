// dsh-go-companion · 任务单表（2026-11-19 · H6）
//
// 将五套旧任务队列（plan.json / daily.json / bouts queue / plan-contract.json / horizons 推送）
// 收编进唯一权威表 work.json：
//   { id, source: 'plan'|'daily'|'contract'|'horizon'|'bout'|'sister',
//     title, minute?, due?, packet, apps, status, links: { horizonId, contractCpId } }
//
// 纪律：
// - 同一工作包按归一化标题/ID 在 workStore 中只有一份；
// - 从五套旧文件做一次性自动迁移（并可增量对账兼容直接写旧文件的测试）；
// - 所有写入通过 workStore.upsert / upsertBatch，再镜像回写旧视图（保留回滚兼容）；
// - 推进器、日判（计划数）、四个数、晚报、思考层、Today/Bouts 全部以 workStore 为唯一统计口径。

import { dayString } from './daily.js'
import { DEFAULT_TZ } from './tz.js'

export const WORK_SOURCES = Object.freeze(['plan', 'daily', 'contract', 'horizon', 'bout', 'sister'])
export const WORK_STATUSES = Object.freeze(['pending', 'running', 'done', 'passed', 'overdue_locked', 'missed'])

const STATUS_RANK = Object.freeze({
  passed: 5,
  done: 5,
  running: 4,
  overdue_locked: 3,
  missed: 2,
  pending: 1,
})

const n = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)
const cleanStr = (v, max = 160) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max)

export function normWorkKey(title) {
  return String(title || '')
    .trim()
    .toLowerCase()
    .replace(/[\s\-_·.,，。！？!?:：()（）【】[\]]+/g, '')
}

export function normalizeWorkApps(rawApps, rawNeed, rawAllow) {
  const list = []
  const push = (x) => {
    const s = cleanStr(x, 120)
    if (s && !list.includes(s)) list.push(s)
  }
  if (Array.isArray(rawApps)) {
    for (const x of rawApps) push(x)
  } else if (rawApps && typeof rawApps === 'object') {
    for (const x of Array.isArray(rawApps.need) ? rawApps.need : []) push(x)
    for (const x of Array.isArray(rawApps.allow) ? rawApps.allow : []) push(x)
  }
  for (const x of Array.isArray(rawNeed) ? rawNeed : []) push(x)
  for (const x of Array.isArray(rawAllow) ? rawAllow : []) push(x)
  return list.slice(0, 30)
}

export function normalizeWorkPacket(rawPacket, item = {}) {
  if (!rawPacket || typeof rawPacket !== 'object') return null
  const p = rawPacket
  const goal = cleanStr(p.goal || item.goal || item.title, 120)
  const deliverable = cleanStr(p.deliverable || p.done || item.evidence, 160)
  const first = cleanStr(p.first, 120)
  const acceptKind = cleanStr(p.acceptKind || p.accept?.kind, 40)
  const acceptWhat = cleanStr(p.acceptWhat || p.accept?.what, 160)
  if (!goal && !deliverable && !first && !acceptKind && !acceptWhat && !p.accept) return null
  return {
    ...p,
    ...(goal ? { goal } : {}),
    ...(deliverable ? { deliverable } : {}),
    ...(acceptKind ? { acceptKind } : {}),
    ...(acceptWhat ? { acceptWhat } : {}),
    ...(first ? { first } : {}),
    ...(p.done ? { done: cleanStr(p.done, 160) } : {}),
  }
}

export function normalizeWorkItem(raw, now = Date.now()) {
  if (!raw || typeof raw !== 'object') return null
  const title = cleanStr(raw.title || raw.task || raw.protocolId, 120)
  if (!title) return null
  const source = WORK_SOURCES.includes(raw.source) ? raw.source : 'plan'
  const status = WORK_STATUSES.includes(raw.status)
    ? raw.status
    : (raw.done === true ? 'done' : (raw.openedAt ? 'running' : (raw.missed ? 'overdue_locked' : 'pending')))
  const linksRaw = raw.links && typeof raw.links === 'object' ? raw.links : {}
  const horizonId = cleanStr(linksRaw.horizonId || raw.parentHorizonId, 64)
  const contractCpId = cleanStr(linksRaw.contractCpId || raw.contractCpId, 64)
  const nodeId = cleanStr(linksRaw.nodeId || raw.nodeId, 40)
  const cb = raw.contextBrief && typeof raw.contextBrief === 'object' ? raw.contextBrief : null
  const contextBrief = cb
    ? {
        horizonTitle: cleanStr(cb.horizonTitle, 100),
        nodeTitle: cleanStr(cb.nodeTitle, 80),
        targetDate: cleanStr(cb.targetDate, 10),
        deliverable: cleanStr(cb.deliverable, 200),
        lastBreakpoint: cleanStr(cb.lastBreakpoint, 200),
      }
    : null
  const minute = Number.isFinite(Number(raw.minute)) ? Math.max(0, Math.min(1439, Math.round(Number(raw.minute)))) : undefined
  const due = raw.due !== undefined && raw.due !== null && raw.due !== '' ? raw.due : (Number(raw.dueAt) > 0 ? Number(raw.dueAt) : undefined)
  const apps = normalizeWorkApps(raw.apps, raw.need, raw.allow)
  const packet = normalizeWorkPacket(raw.packet, raw)
  const id = cleanStr(raw.id, 64) || `${source}-${normWorkKey(title).slice(0, 12) || 'item'}`
  return {
    id,
    source,
    title,
    ...(minute !== undefined ? { minute } : {}),
    ...(due !== undefined ? { due } : {}),
    packet,
    apps,
    status,
    links: {
      horizonId,
      contractCpId,
      ...(nodeId ? { nodeId } : {}),
    },
    ...(contextBrief ? { contextBrief } : {}),
    protocolId: cleanStr(raw.protocolId || 'execute', 40),
    minutes: Math.max(5, Math.min(480, Math.round(n(raw.minutes ?? raw.budget, 45)))),
    dept: cleanStr(raw.dept || raw.department, 16),
    evidence: cleanStr(raw.evidence || packet?.deliverable || '', 160),
    openedAt: n(raw.openedAt, 0) || undefined,
    doneAt: n(raw.doneAt, 0) || undefined,
    strict: raw.strict !== false,
    updatedAt: n(raw.updatedAt, now),
  }
}

export function emptyWorkStore(day = '', now = Date.now()) {
  return {
    v: 1,
    day: String(day || ''),
    migratedAt: 0,
    updatedAt: now,
    items: [],
  }
}

export function normalizeWorkStore(raw, { day = '', now = Date.now() } = {}) {
  const d = String(day || '')
  const s = raw && typeof raw === 'object' ? raw : null
  if (!s || (d && String(s.day || '') !== d)) {
    return emptyWorkStore(d, now)
  }
  const byKey = new Map()
  for (const r of Array.isArray(s.items) ? s.items : []) {
    const it = normalizeWorkItem(r, now)
    if (!it) continue
    const k = normWorkKey(it.title) || it.id
    const prev = byKey.get(k)
    byKey.set(k, prev ? mergeWorkItems(prev, it, now) : it)
  }
  return {
    v: 1,
    day: d || String(s.day || ''),
    migratedAt: n(s.migratedAt, 0),
    updatedAt: n(s.updatedAt, now),
    items: [...byKey.values()],
  }
}

function mergeWorkItems(prev, next, now = Date.now()) {
  const prevRank = STATUS_RANK[prev.status] || 1
  const nextRank = STATUS_RANK[next.status] || 1
  const apps = normalizeWorkApps([...(prev.apps || []), ...(next.apps || [])])
  const links = {
    horizonId: next.links?.horizonId || prev.links?.horizonId || '',
    contractCpId: next.links?.contractCpId || prev.links?.contractCpId || '',
    ...((next.links?.nodeId || prev.links?.nodeId) ? { nodeId: next.links?.nodeId || prev.links?.nodeId } : {}),
  }
  return {
    ...prev,
    ...next,
    id: prev.id || next.id,
    source: prev.source === 'horizon' ? 'horizon' : (next.source || prev.source),
    status: nextRank >= prevRank ? next.status : prev.status,
    minute: next.minute !== undefined ? next.minute : prev.minute,
    due: next.due !== undefined ? next.due : prev.due,
    minutes: Math.max(n(prev.minutes, 0), n(next.minutes, 0)) || 45,
    apps,
    links,
    packet: next.packet || prev.packet || null,
    ...((next.contextBrief || prev.contextBrief) ? { contextBrief: next.contextBrief || prev.contextBrief } : {}),
    dept: next.dept || prev.dept || '',
    evidence: next.evidence || prev.evidence || '',
    openedAt: next.openedAt || prev.openedAt,
    doneAt: next.doneAt || prev.doneAt,
    updatedAt: now,
  }
}

export function upsertWorkItem(rawState, rawItem, { day = '', now = Date.now() } = {}) {
  const state = normalizeWorkStore(rawState, { day, now })
  const item = normalizeWorkItem(rawItem, now)
  if (!item) return { ok: false, state, item: null }
  const key = normWorkKey(item.title) || item.id
  const idx = state.items.findIndex((x) => x.id === item.id || (key && normWorkKey(x.title) === key))
  const nextItems = [...state.items]
  let saved
  if (idx >= 0) {
    saved = mergeWorkItems(nextItems[idx], item, now)
    nextItems[idx] = saved
  } else {
    saved = item
    nextItems.push(saved)
  }
  return {
    ok: true,
    item: saved,
    state: {
      ...state,
      updatedAt: now,
      items: nextItems,
    },
  }
}

export function removeWorkItem(rawState, { id = '', title = '', day = '', now = Date.now() } = {}) {
  const state = normalizeWorkStore(rawState, { day, now })
  const targetId = cleanStr(id, 64)
  const targetKey = normWorkKey(title)
  const items = state.items.filter((x) => {
    if (targetId && x.id === targetId) return false
    if (targetKey && normWorkKey(x.title) === targetKey && !targetId) return false
    return true
  })
  return {
    ...state,
    updatedAt: now,
    items,
  }
}

export function markWorkStatus(rawState, { id = '', title = '', status = 'done', day = '', now = Date.now() } = {}) {
  const state = normalizeWorkStore(rawState, { day, now })
  if (!WORK_STATUSES.includes(status)) return { ok: false, state, item: null }
  const targetId = cleanStr(id, 64)
  const targetKey = normWorkKey(title)
  let updatedItem = null
  const items = state.items.map((x) => {
    const hit = (targetId && x.id === targetId) || (targetKey && normWorkKey(x.title) === targetKey)
    if (!hit) return x
    updatedItem = {
      ...x,
      status,
      ...(status === 'running' && !x.openedAt ? { openedAt: now } : {}),
      ...(status === 'done' || status === 'passed' ? { doneAt: now } : {}),
      updatedAt: now,
    }
    return updatedItem
  })
  return {
    ok: Boolean(updatedItem),
    item: updatedItem,
    state: {
      ...state,
      updatedAt: now,
      items,
    },
  }
}

export function replaceWorkSource(rawState, source, rawItems = [], { day = '', now = Date.now(), preserveSources = [] } = {}) {
  const state = normalizeWorkStore(rawState, { day, now })
  const src = WORK_SOURCES.includes(source) ? source : 'plan'
  const keepSet = new Set(preserveSources)
  // Keep items from other sources unless matched by incoming batch
  let nextState = {
    ...state,
    updatedAt: now,
    items: state.items.filter((x) => x.source !== src || keepSet.has(x.source)),
  }
  for (const r of Array.isArray(rawItems) ? rawItems : []) {
    const inferredSource = r?.parentHorizonId || r?.source === 'horizon' ? 'horizon' : src
    const res = upsertWorkItem(nextState, { ...r, source: r?.source || inferredSource }, { day, now })
    nextState = res.state
  }
  return nextState
}

/** 从五套旧视图一次性自动迁移到 work.json，或同步尚未合入的旧条目。 */
export function migrateLegacyToWorkStore({
  state = null,
  planItems = [],
  dailyTasks = [],
  boutQueue = [],
  contract = null,
  day = '',
  now = Date.now(),
  tz = DEFAULT_TZ,
} = {}) {
  const targetDay = day || dayString(now, tz)
  let next = normalizeWorkStore(state, { day: targetDay, now })

  for (const it of Array.isArray(planItems) ? planItems : []) {
    if (!it) continue
    next = upsertWorkItem(next, {
      id: it.id,
      source: it.parentHorizonId ? 'horizon' : (it.source || 'plan'),
      title: it.title || it.protocolId,
      minute: it.minute,
      due: it.due,
      packet: it.packet,
      apps: it.apps || [...(Array.isArray(it.need) ? it.need : []), ...(Array.isArray(it.allow) ? it.allow : [])],
      status: it.status || (it.doneAt ? 'done' : (it.openedAt ? 'running' : (it.missed ? 'overdue_locked' : 'pending'))),
      links: { horizonId: it.parentHorizonId || it.links?.horizonId || '', contractCpId: '' },
      protocolId: it.protocolId,
      minutes: it.budget ?? it.minutes,
      dept: it.department || it.dept,
      openedAt: it.openedAt,
      doneAt: it.doneAt,
      strict: it.strict,
    }, { day: targetDay, now }).state
  }

  for (const it of Array.isArray(boutQueue) ? boutQueue : []) {
    if (!it) continue
    next = upsertWorkItem(next, {
      id: it.id,
      source: it.parentHorizonId ? 'horizon' : 'bout',
      title: it.title || it.protocolId,
      minute: it.minute,
      packet: it.packet,
      apps: it.apps || [...(Array.isArray(it.need) ? it.need : []), ...(Array.isArray(it.allow) ? it.allow : [])],
      status: it.status || (it.openedAt ? 'running' : 'pending'),
      links: { horizonId: it.parentHorizonId || '', contractCpId: '' },
      protocolId: it.protocolId,
      minutes: it.budget ?? it.minutes,
      dept: it.department || it.dept,
      openedAt: it.openedAt,
    }, { day: targetDay, now }).state
  }

  for (const t of Array.isArray(dailyTasks) ? dailyTasks : []) {
    if (!t) continue
    next = upsertWorkItem(next, {
      id: t.id,
      source: 'daily',
      title: t.task || t.title,
      due: t.due,
      minutes: t.minutes,
      dept: t.dept,
      evidence: t.evidence,
      packet: t.evidence ? { goal: t.task || t.title, deliverable: t.evidence } : null,
      status: t.done ? 'done' : 'pending',
    }, { day: targetDay, now }).state
  }

  if (contract && typeof contract === 'object') {
    const cps = Array.isArray(contract.checkpoints) ? contract.checkpoints : []
    for (const cp of cps) {
      if (!cp) continue
      next = upsertWorkItem(next, {
        id: cp.id || contract.id,
        source: 'contract',
        title: cp.title || contract.title,
        due: cp.dueAt || undefined,
        minutes: 30,
        apps: contract.allowedApps || [],
        packet: cp.expectedDeliverable ? { goal: cp.title || contract.title, deliverable: cp.expectedDeliverable } : null,
        status: cp.status === 'passed' ? 'passed' : (cp.status === 'overdue_locked' ? 'overdue_locked' : 'pending'),
        links: { horizonId: contract.horizonId || '', contractCpId: cp.id || '' },
      }, { day: targetDay, now }).state
    }
  }

  return {
    ...next,
    migratedAt: next.migratedAt || now,
    updatedAt: now,
  }
}

/** 从 workStore 派生旧视图（planItems / dailyTasks / boutQueue），保证可回滚兼容。 */
export function mirrorWorkStoreToLegacy(rawState, { horizons = null } = {}) {
  const items = Array.isArray(rawState?.items) ? rawState.items : []
  const horizonMap = new Map()
  for (const h of [...(horizons?.long || []), ...(horizons?.medium || [])]) {
    if (h?.id) horizonMap.set(String(h.id), String(h.title || ''))
  }

  const planItems = items
    .filter((x) => ['plan', 'horizon', 'bout', 'sister', 'daily', 'contract'].includes(x.source))
    .map((x) => ({
      id: x.id,
      source: x.source,
      protocolId: x.protocolId || 'execute',
      title: x.title,
      minute: x.minute ?? 540,
      budget: x.minutes || 45,
      minutes: x.minutes || 45,
      department: x.dept || '',
      need: Array.isArray(x.apps) ? x.apps : [],
      allow: Array.isArray(x.apps) ? x.apps : [],
      ...(x.packet ? { packet: x.packet } : {}),
      ...(x.links?.horizonId ? { parentHorizonId: x.links.horizonId, horizonTitle: horizonMap.get(x.links.horizonId) || '' } : {}),
      ...(x.links?.contractCpId ? { contractCpId: x.links.contractCpId } : {}),
      status: x.status,
      ...(x.openedAt ? { openedAt: x.openedAt } : {}),
      ...(x.doneAt ? { doneAt: x.doneAt } : {}),
      ...(x.status === 'overdue_locked' || x.status === 'missed' ? { missed: true } : {}),
    }))

  const dailyTasks = items.map((x) => ({
    id: x.id,
    task: x.title,
    evidence: x.evidence || x.packet?.deliverable || '完成并验收',
    due: typeof x.due === 'number' && x.due < 1440 ? x.due : (x.minute ?? 1080),
    minutes: x.minutes || 30,
    dept: x.dept || '执行',
    done: x.status === 'done' || x.status === 'passed',
  }))

  const boutQueue = planItems

  const unifiedTasks = items.map((x) => ({
    id: x.id,
    title: x.title,
    source: x.source,
    sources: [x.source],
    minutes: x.minutes || 45,
    dept: x.dept || '',
    protocolId: x.protocolId || 'execute',
    parentHorizonId: x.links?.horizonId || '',
    horizonTitle: x.links?.horizonId ? (horizonMap.get(x.links.horizonId) || '') : '',
    status: x.status,
    dueAt: typeof x.due === 'number' && x.due > 10_000 ? x.due : 0,
    allowApps: Array.isArray(x.apps) ? x.apps : [],
  }))

  return {
    planItems,
    dailyTasks,
    boutQueue,
    unifiedTasks,
    totalCount: items.length,
    doneCount: items.filter((x) => x.status === 'done' || x.status === 'passed').length,
    openCount: items.filter((x) => x.status === 'pending' || x.status === 'running').length,
  }
}
