// dsh-go-companion · 锁总线（2026-11-19 · H5）
//
// 所有写锁处统一通过 lockBus 提交或撤销锁请求，存入 lock-requests.json：
//   { id, source, kind: 'allowlist'|'deny'|'open', apps, allow, until, why, unlockHow, priority }
//
// 防幽灵锁（Anti-Ghost-Lock Safeguard）：
//   1. 每次读取（normalizeLockBus / activeRequests / list）自动修剪 until <= now 的过期请求；
//   2. 任何解封动作（strictOff / 契约验收或清空 / 接管结束 / 紧急解除 / refuseUntil=0 / aiWorkSession=null）
//      必须调用 revokeBySource(...) 或 revokeByApp(...) 从总线移除对应锁请求。

export const LOCK_BUS_SOURCES = Object.freeze([
  'radar',
  'contract',
  'preset',
  'wall',
  'takeover',
  'switch',
  'sister',
  'night',
  'gemini-cell',
  'lease',
])

const SOURCE_ALIAS = Object.freeze({
  tool: 'sister',
  ai: 'sister',
  ladder: 'radar',
  defied: 'sister',
  tamper: 'sister',
  perfunctory: 'sister',
  withdrawal: 'sister',
  'switch-limit': 'switch',
  panel: 'preset',
  user: 'preset',
  clock: 'contract',
  bout: 'contract',
  loop: 'contract',
  emergency: 'lease',
  'emergency-pass': 'lease',
})

const VALID_KINDS = new Set(['allowlist', 'deny', 'open'])
const DEFAULT_PRIORITY_BY_SOURCE = Object.freeze({
  night: 'P1',
  'gemini-cell': 'P2',
  contract: 'P3',
  takeover: 'P3.5',
  preset: 'P4',
  wall: 'P4',
  lease: 'P5',
  radar: 'P6',
  switch: 'P6',
  sister: 'P6',
})

const cleanStr = (v, max = 160) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max)
const cleanList = (arr) =>
  [...new Set((Array.isArray(arr) ? arr : []).map((x) => cleanStr(x, 120)).filter(Boolean))].slice(0, 40)

export function normalizeLockSource(raw) {
  const s = cleanStr(raw, 32).toLowerCase()
  if (LOCK_BUS_SOURCES.includes(s)) return s
  if (SOURCE_ALIAS[s]) return SOURCE_ALIAS[s]
  return 'sister'
}

export function normalizeLockRequest(raw, now = Date.now()) {
  if (!raw || typeof raw !== 'object') return null
  const source = normalizeLockSource(raw.source || raw.by)
  const kind = VALID_KINDS.has(raw.kind) ? raw.kind : (Array.isArray(raw.allow) && raw.allow.length ? 'allowlist' : 'deny')
  const until = Number(raw.until) || 0
  if (until > 0 && until <= now) return null
  const apps = cleanList(raw.apps)
  const allow = cleanList(raw.allow)
  const priority = cleanStr(raw.priority || DEFAULT_PRIORITY_BY_SOURCE[source] || 'P6', 8)
  const id = cleanStr(raw.id || `${source}:${kind}:${apps[0] || allow[0] || 'global'}`, 96)
  const why = cleanStr(raw.why, 200) || (kind === 'open' ? '临时放行' : '规则锁定中')
  const unlockHow = cleanStr(raw.unlockHow, 200) || (
    kind === 'open'
      ? '倒计时结束后自动恢复'
      : (until > 0 ? '到期自动解除，或回到正事应用 / 使用紧急解除' : '完成当前主线交付或按条件解锁')
  )
  return {
    id,
    source,
    kind,
    apps,
    allow,
    until,
    why,
    unlockHow,
    priority,
    createdAt: Number(raw.createdAt) || now,
    updatedAt: Number(raw.updatedAt) || now,
  }
}

export function pruneExpiredRequests(raw, now = Date.now()) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const seen = new Map()
  for (const r of Array.isArray(s.requests) ? s.requests : []) {
    const clean = normalizeLockRequest(r, now)
    if (!clean) continue
    seen.set(clean.id, clean)
  }
  return {
    v: 1,
    updatedAt: Number(s.updatedAt) || now,
    requests: [...seen.values()].slice(-60),
  }
}

export function upsertLockRequest(raw, req, now = Date.now()) {
  const base = pruneExpiredRequests(raw, now)
  const clean = normalizeLockRequest(req, now)
  if (!clean) return { ok: false, state: base, request: null }
  const nextList = base.requests.filter((r) => {
    if (r.id === clean.id) return false
    if (req?.replaceSource && r.source === clean.source) return false
    return true
  })
  nextList.push({ ...clean, updatedAt: now })
  return {
    ok: true,
    request: clean,
    state: {
      v: 1,
      updatedAt: now,
      requests: nextList.slice(-60),
    },
  }
}

export function revokeLockBySource(raw, source, { id = '', now = Date.now() } = {}) {
  const base = pruneExpiredRequests(raw, now)
  const normSrc = normalizeLockSource(source)
  const cleanId = cleanStr(id, 96)
  const nextList = base.requests.filter((r) => {
    if (cleanId && r.id === cleanId) return false
    if (!cleanId && r.source === normSrc) return false
    return true
  })
  return {
    v: 1,
    updatedAt: now,
    requests: nextList,
  }
}

export function revokeLockByApp(raw, appPkg, { now = Date.now() } = {}) {
  const pkg = cleanStr(appPkg, 120)
  const base = pruneExpiredRequests(raw, now)
  if (!pkg) return base
  const nextList = []
  for (const r of base.requests) {
    if (r.kind === 'deny' && r.apps.includes(pkg)) {
      const rest = r.apps.filter((p) => p !== pkg)
      if (rest.length > 0) nextList.push({ ...r, apps: rest, updatedAt: now })
    } else {
      nextList.push(r)
    }
  }
  return { v: 1, updatedAt: now, requests: nextList }
}

export function clearLockBus(raw, { keepSources = [], now = Date.now() } = {}) {
  const keep = new Set((Array.isArray(keepSources) ? keepSources : []).map((s) => normalizeLockSource(s)))
  const base = pruneExpiredRequests(raw, now)
  return {
    v: 1,
    updatedAt: now,
    requests: base.requests.filter((r) => keep.has(r.source)),
  }
}

export function activeLockRequests(raw, now = Date.now()) {
  return pruneExpiredRequests(raw, now).requests
}

export function createLockBus({ load = () => null, save = () => {} } = {}) {
  const readPruned = (now = Date.now()) => {
    const raw = load()
    const pruned = pruneExpiredRequests(raw, now)
    if (Array.isArray(raw?.requests) && raw.requests.length !== pruned.requests.length) {
      save(pruned)
    }
    return pruned
  }
  return {
    list: (now = Date.now()) => readPruned(now).requests,
    snapshot: (now = Date.now()) => readPruned(now),
    upsert: (req, now = Date.now()) => {
      const res = upsertLockRequest(readPruned(now), req, now)
      if (res.ok) save(res.state)
      return res
    },
    revokeBySource: (source, opts = {}) => {
      const now = Number(opts.now) || Date.now()
      const next = revokeLockBySource(readPruned(now), source, { ...opts, now })
      save(next)
      return next
    },
    revokeByApp: (appPkg, opts = {}) => {
      const now = Number(opts.now) || Date.now()
      const next = revokeLockByApp(readPruned(now), appPkg, { now })
      save(next)
      return next
    },
    clear: (opts = {}) => {
      const now = Number(opts.now) || Date.now()
      const next = clearLockBus(readPruned(now), { ...opts, now })
      save(next)
      return next
    },
  }
}
