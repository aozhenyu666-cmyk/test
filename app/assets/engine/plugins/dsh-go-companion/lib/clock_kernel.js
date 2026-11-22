// dsh-go-companion · 内核时钟与复杂定时任务调度器（零外部依赖，与 Kotlin KernelClockEngine 同构）
//
// 支持五类原语：
//   - ONE_SHOT    ：精确单次闹钟
//   - FIXED_RATE  ：锚定原点补偿漂移的高频/定频循环计时（anchor + k * intervalMs）
//   - FIXED_DELAY ：完成/触发后固定间隔循环
//   - CRON        ：5/6 段标准 Cron 循环闹钟（固定 Asia/Shanghai 北京时间）
//   - CYCLE_PHASE ：多阶段循环计时器（专注 -> 休息 x N 轮，支持暂停剩时/继续/跳阶段）

import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { DEFAULT_TZ } from './tz.js'

export const CLOCK_KINDS = Object.freeze(['ONE_SHOT', 'FIXED_RATE', 'FIXED_DELAY', 'CRON', 'CYCLE_PHASE'])

const FIELD_BOUNDS = {
  second: [0, 59],
  minute: [0, 59],
  hour: [0, 23],
  dom: [1, 31],
  month: [1, 12],
  dow: [0, 7],
}

function parseField(token, min, max, isDow = false) {
  const trimmed = String(token || '').trim()
  if (!trimmed) return null
  const mapVal = (v) => (isDow && v === 7 ? 0 : v)
  const wildcard = trimmed === '*' || trimmed === '?'
  if (wildcard) {
    const set = new Set()
    for (let i = min; i <= max; i += 1) set.add(mapVal(i))
    return { values: set, wildcard: true }
  }
  const values = new Set()
  for (const part of trimmed.split(',')) {
    const p = part.trim()
    if (!p) return null
    const slashIdx = p.indexOf('/')
    const rangeStr = slashIdx >= 0 ? p.slice(0, slashIdx) : p
    const step = slashIdx >= 0 ? Number(p.slice(slashIdx + 1)) : 1
    if (!Number.isInteger(step) || step <= 0) return null
    let start, end
    if (rangeStr === '*' || rangeStr === '?') {
      start = min
      end = max
    } else if (rangeStr.includes('-')) {
      const segs = rangeStr.split('-')
      if (segs.length !== 2) return null
      start = Number(segs[0])
      end = Number(segs[1])
      if (!Number.isInteger(start) || !Number.isInteger(end) || start > end || start < min || end > max) return null
    } else {
      const single = Number(rangeStr)
      if (!Number.isInteger(single) || single < min || single > max) return null
      start = single
      end = slashIdx >= 0 ? max : single
    }
    for (let v = start; v <= end; v += step) values.add(mapVal(v))
  }
  return values.size > 0 ? { values, wildcard: false } : null
}

/** 解析 5/6 段 Cron 表达式。 */
export function parseCron(expr) {
  const parts = String(expr || '').trim().split(/\s+/).filter(Boolean)
  if (parts.length !== 5 && parts.length !== 6) return null
  const off = parts.length === 6 ? 1 : 0
  const seconds = off ? parseField(parts[0], ...FIELD_BOUNDS.second) : { values: new Set([0]), wildcard: false }
  const minutes = parseField(parts[off], ...FIELD_BOUNDS.minute)
  const hours = parseField(parts[off + 1], ...FIELD_BOUNDS.hour)
  const dom = parseField(parts[off + 2], ...FIELD_BOUNDS.dom)
  const month = parseField(parts[off + 3], ...FIELD_BOUNDS.month)
  const dow = parseField(parts[off + 4], ...FIELD_BOUNDS.dow, true)
  if (!seconds || !minutes || !hours || !dom || !month || !dow) return null
  return { seconds: seconds.values, minutes: minutes.values, hours: hours.values, dom, month: month.values, dow }
}

/** 按指定时区求 afterWall 之后的下一次触发 UTC 毫秒（默认 Asia/Shanghai，恒定 +08:00 零夏令时误差）。 */
export function nextCronAt(expr, afterWall = Date.now(), tz = DEFAULT_TZ) {
  const parsed = typeof expr === 'string' ? parseCron(expr) : expr
  if (!parsed) return 0
  const fmt = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    weekday: 'short',
    hourCycle: 'h23',
  })
  const dowMap = { Sun: 0, Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6 }
  const partsOf = (ms) => {
    const out = {}
    for (const p of fmt.formatToParts(ms)) out[p.type] = p.value
    return {
      year: Number(out.year),
      month: Number(out.month),
      day: Number(out.day),
      hour: Number(out.hour) % 24,
      minute: Number(out.minute),
      second: Number(out.second),
      dow: dowMap[out.weekday] ?? 0,
    }
  }

  let cur = Math.floor(afterWall / 1000) * 1000 + 1000
  const limit = cur + 366 * 2 * 86_400_000
  let guard = 0
  while (cur <= limit && guard++ < 200_000) {
    const p = partsOf(cur)
    if (!parsed.month.has(p.month)) {
      cur += (24 - p.hour) * 3_600_000 - p.minute * 60_000 - p.second * 1000
      continue
    }
    const domHit = parsed.dom.values.has(p.day)
    const dowHit = parsed.dow.values.has(p.dow)
    const dayOk = (parsed.dom.wildcard && parsed.dow.wildcard)
      ? true
      : parsed.dom.wildcard ? dowHit
      : parsed.dow.wildcard ? domHit
      : (domHit || dowHit)
    if (!dayOk) {
      cur += (24 - p.hour) * 3_600_000 - p.minute * 60_000 - p.second * 1000
      continue
    }
    if (!parsed.hours.has(p.hour)) {
      cur += (60 - p.minute) * 60_000 - p.second * 1000
      continue
    }
    if (!parsed.minutes.has(p.minute)) {
      cur += (60 - p.second) * 1000
      continue
    }
    if (!parsed.seconds.has(p.second)) {
      cur += 1000
      continue
    }
    return cur
  }
  return 0
}

function normPhases(raw) {
  if (!Array.isArray(raw)) return []
  return raw.slice(0, 12).map((p, idx) => ({
    name: String(p?.name || '阶段' + (idx + 1)).slice(0, 24),
    durationMs: Math.max(1_000, Math.min(12 * 3_600_000, Math.round(Number(p?.durationMs) || 60_000))),
    strictLock: Boolean(p?.strictLock),
    allowApps: Array.isArray(p?.allowApps) ? p.allowApps.map((x) => String(x || '').trim()).filter(Boolean).slice(0, 10) : [],
  }))
}

export class ClockKernel {
  constructor(filePath, { tz = DEFAULT_TZ } = {}) {
    this.filePath = filePath || ''
    this.tz = tz
    this.tasks = new Map()
    if (this.filePath && existsSync(this.filePath)) {
      this._load()
    }
  }

  _load() {
    try {
      const raw = JSON.parse(readFileSync(this.filePath, 'utf8'))
      const arr = Array.isArray(raw?.tasks) ? raw.tasks : []
      for (const t of arr) {
        if (t && typeof t === 'object' && t.id) this.tasks.set(String(t.id), t)
      }
    } catch {
      // ignore corrupted clock file
    }
  }

  _save() {
    if (!this.filePath) return
    try {
      mkdirSync(dirname(this.filePath), { recursive: true })
      const tmp = this.filePath + '.' + process.pid + '.tmp'
      writeFileSync(tmp, JSON.stringify({ v: 1, tasks: [...this.tasks.values()] }, null, 2), 'utf8')
      renameSync(tmp, this.filePath)
    } catch {
      // ignore disk failure
    }
  }

  upsert(spec, now = Date.now()) {
    const id = String(spec?.id || 'clk-' + now + '-' + Math.random().toString(36).slice(2, 6)).slice(0, 64)
    const kind = CLOCK_KINDS.includes(spec?.kind) ? spec.kind : 'ONE_SHOT'
    const intervalMs = Math.max(0, Math.round(Number(spec?.intervalMs) || 0))
    const phases = normPhases(spec?.phases)
    const prev = this.tasks.get(id)
    const anchorWall = Number(spec?.anchorWall) || prev?.anchorWall || now
    let nextFireWall = Number(spec?.nextFireWall) || 0
    if (nextFireWall <= 0) {
      if (kind === 'CRON') nextFireWall = nextCronAt(spec?.cronExpr, now, this.tz)
      else if (kind === 'CYCLE_PHASE' && phases.length) nextFireWall = now + phases[0].durationMs
      else nextFireWall = now + Math.max(1_000, intervalMs || 60_000)
    }
    const task = {
      id,
      label: String(spec?.label || id).slice(0, 60),
      kind,
      enabled: kind === 'CRON' ? Boolean(nextFireWall > 0 && spec?.enabled !== false) : (kind === 'CYCLE_PHASE' ? phases.length > 0 && spec?.enabled !== false : spec?.enabled !== false),
      paused: Boolean(spec?.paused),
      urgent: Boolean(spec?.urgent),
      source: 'plugin',
      action: String(spec?.action || 'notify').slice(0, 40),
      payload: spec?.payload && typeof spec.payload === 'object' ? { ...spec.payload } : {},
      misfirePolicy: String(spec?.misfirePolicy || 'SKIP_TO_NEXT'),
      anchorWall,
      nextFireWall,
      intervalMs,
      cronExpr: String(spec?.cronExpr || '').trim(),
      phases,
      phaseIndex: Math.max(0, Math.min(Math.max(0, phases.length - 1), Number(spec?.phaseIndex) || 0)),
      cycleIndex: Math.max(1, Number(spec?.cycleIndex) || 1),
      maxRuns: Math.max(0, Number(spec?.maxRuns) || 0),
      runCount: Math.max(0, Number(spec?.runCount) || prev?.runCount || 0),
      pausedRemainingMs: Math.max(0, Number(spec?.pausedRemainingMs) || 0),
      lastFiredWall: Number(spec?.lastFiredWall) || prev?.lastFiredWall || 0,
      createdAtWall: prev?.createdAtWall || now,
    }
    this.tasks.set(id, task)
    this._save()
    return task
  }

  pause(id, now = Date.now()) {
    const t = this.tasks.get(id)
    if (!t || t.paused) return t || null
    const rem = Math.max(1_000, Number(t.nextFireWall || 0) - now)
    const next = { ...t, paused: true, pausedRemainingMs: rem }
    this.tasks.set(id, next)
    this._save()
    return next
  }

  resume(id, now = Date.now()) {
    const t = this.tasks.get(id)
    if (!t || !t.paused) return t || null
    const nextFireWall = t.kind === 'CRON'
      ? nextCronAt(t.cronExpr, now, this.tz)
      : now + Math.max(1_000, Number(t.pausedRemainingMs) || Number(t.intervalMs) || 60_000)
    const next = { ...t, paused: false, pausedRemainingMs: 0, nextFireWall }
    this.tasks.set(id, next)
    this._save()
    return next
  }

  skipPhase(id, now = Date.now()) {
    const t = this.tasks.get(id)
    if (!t || t.kind !== 'CYCLE_PHASE' || !t.phases.length) return null
    const ev = this._advanceCycle(t, now)
    this._save()
    return ev
  }

  remove(id) {
    const ok = this.tasks.delete(id)
    if (ok) this._save()
    return ok
  }

  list() {
    return [...this.tasks.values()].sort((a, b) => {
      const aw = a.enabled && !a.paused ? a.nextFireWall : Number.MAX_SAFE_INTEGER
      const bw = b.enabled && !b.paused ? b.nextFireWall : Number.MAX_SAFE_INTEGER
      return aw - bw
    })
  }

  _advanceCycle(t, now) {
    const cur = t.phases[t.phaseIndex] || t.phases[0]
    const lastIdx = t.phases.length - 1
    const nextPhaseIdx = t.phaseIndex >= lastIdx ? 0 : t.phaseIndex + 1
    const nextCycleIdx = t.phaseIndex >= lastIdx ? t.cycleIndex + 1 : t.cycleIndex
    const completedAll = t.phaseIndex >= lastIdx && t.maxRuns > 0 && t.cycleIndex >= t.maxRuns
    const nextPhase = completedAll ? null : t.phases[nextPhaseIdx]
    const nextFireWall = nextPhase ? now + nextPhase.durationMs : 0
    const updated = {
      ...t,
      enabled: !completedAll,
      phaseIndex: completedAll ? t.phaseIndex : nextPhaseIdx,
      cycleIndex: completedAll ? t.cycleIndex : nextCycleIdx,
      runCount: t.runCount + 1,
      lastFiredWall: now,
      nextFireWall,
    }
    this.tasks.set(t.id, updated)
    return {
      taskId: t.id,
      label: t.label,
      kind: t.kind,
      action: t.action,
      completedPhase: cur?.name || '',
      nextPhase,
      cycleIndex: t.cycleIndex,
      finished: completedAll,
      payload: t.payload,
    }
  }

  /** 推进到期的时钟任务并返回本次触发事件与最近下次检查时间。 */
  tick(now = Date.now()) {
    const fires = []
    let changed = false
    for (const t of this.tasks.values()) {
      if (!t.enabled || t.paused || !t.nextFireWall || t.nextFireWall > now) continue
      changed = true
      if (t.kind === 'ONE_SHOT') {
        this.tasks.set(t.id, { ...t, enabled: false, runCount: t.runCount + 1, lastFiredWall: now, nextFireWall: 0 })
        fires.push({ taskId: t.id, label: t.label, kind: t.kind, action: t.action, finished: true, payload: t.payload })
      } else if (t.kind === 'FIXED_RATE') {
        const interval = Math.max(1_000, t.intervalMs)
        const anchor = t.anchorWall || t.nextFireWall
        const steps = Math.max(0, Math.floor((now - anchor) / interval)) + 1
        const nextFireWall = anchor + steps * interval
        const runCount = t.runCount + 1
        const hitMax = t.maxRuns > 0 && runCount >= t.maxRuns
        this.tasks.set(t.id, { ...t, enabled: !hitMax, runCount, lastFiredWall: now, nextFireWall: hitMax ? 0 : nextFireWall })
        fires.push({ taskId: t.id, label: t.label, kind: t.kind, action: t.action, runCount, finished: hitMax, payload: t.payload })
      } else if (t.kind === 'FIXED_DELAY') {
        const interval = Math.max(1_000, t.intervalMs)
        const runCount = t.runCount + 1
        const hitMax = t.maxRuns > 0 && runCount >= t.maxRuns
        this.tasks.set(t.id, { ...t, enabled: !hitMax, runCount, lastFiredWall: now, nextFireWall: hitMax ? 0 : now + interval })
        fires.push({ taskId: t.id, label: t.label, kind: t.kind, action: t.action, runCount, finished: hitMax, payload: t.payload })
      } else if (t.kind === 'CRON') {
        const nextFireWall = nextCronAt(t.cronExpr, now, this.tz)
        const runCount = t.runCount + 1
        const hitMax = !nextFireWall || (t.maxRuns > 0 && runCount >= t.maxRuns)
        this.tasks.set(t.id, { ...t, enabled: !hitMax, runCount, lastFiredWall: now, nextFireWall: hitMax ? 0 : nextFireWall })
        fires.push({ taskId: t.id, label: t.label, kind: t.kind, action: t.action, runCount, finished: hitMax, payload: t.payload })
      } else if (t.kind === 'CYCLE_PHASE') {
        fires.push(this._advanceCycle(t, now))
      }
    }
    if (changed) this._save()
    let nextAt = 0
    for (const t of this.tasks.values()) {
      if (t.enabled && !t.paused && t.nextFireWall > now && (!nextAt || t.nextFireWall < nextAt)) {
        nextAt = t.nextFireWall
      }
    }
    return { fires, nextAt }
  }
}
