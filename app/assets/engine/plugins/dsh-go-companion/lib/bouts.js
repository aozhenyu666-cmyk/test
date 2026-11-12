// dsh-go-companion · 局的数据（第八轮 J1）
//
// 八张表全都加在 fish-memory.sqlite 里，走 dsh-go-memory 的迁移机制
// （store.js 的 DDL 每次开库全跑一遍，CREATE TABLE IF NOT EXISTS 幂等）。
// 这里也留一份同样的 DDL：记忆插件还没开过库（新装、刚升级）时，局要能自己把表建出来，
// 否则第一次开局会因为没有表直接失败。两处写法由 test/bouts.test.mjs 里的比对用例守着。
//
// 规矩：
// - 所有写入只走这个文件；上层不要自己写 SQL。
// - 每一次写入都同时追加一行账本（companion/ledger.jsonl）。账本与库冲突时以账本为准，
//   rebuildFromLedger() 按账本重放把八张表重建回来。
// - 引擎内只有一个局处于进行中：靠 bouts 上的部分唯一索引保证，不靠调用方自觉。
//
// SQL 全是固定文本 + 参数绑定，不拼接任何外部输入。

import { createRequire } from 'node:module'
import { existsSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { appendLedger, readLedger } from './ledger.js'

const require = createRequire(import.meta.url)
let DatabaseSync = null
try {
  ;({ DatabaseSync } = require('node:sqlite'))
} catch {
  DatabaseSync = null
}

/** 局表 DDL。必须与 dsh-go-memory/lib/store.js 的局表部分逐条相同（测试会比对）。 */
export const BOUT_DDL = [
  'CREATE TABLE IF NOT EXISTS bouts (id TEXT PRIMARY KEY, protocol_id TEXT NOT NULL, opened_by TEXT, ' +
    'planned_at INTEGER, opened_at INTEGER, closed_at INTEGER, close_reason TEXT, status TEXT NOT NULL, department TEXT)',
  'CREATE TABLE IF NOT EXISTS beats (id TEXT PRIMARY KEY, bout_id TEXT NOT NULL, step_id TEXT, asked_at INTEGER, ' +
    'channel TEXT, answer TEXT, metrics TEXT, verdict TEXT, retries INTEGER)',
  'CREATE TABLE IF NOT EXISTS evidence (id TEXT PRIMARY KEY, bout_id TEXT, beat_id TEXT, source_type TEXT, ' +
    'ref TEXT, content_hash TEXT)',
  'CREATE TABLE IF NOT EXISTS deliverables (id TEXT PRIMARY KEY, bout_id TEXT, beat_id TEXT, form TEXT, ' +
    'ref TEXT, text TEXT)',
  'CREATE TABLE IF NOT EXISTS verdicts (id TEXT PRIMARY KEY, bout_id TEXT, beat_id TEXT, layer TEXT, ' +
    'result TEXT, score REAL, quote TEXT)',
  'CREATE TABLE IF NOT EXISTS escalations (id TEXT PRIMARY KEY, reason TEXT, level TEXT, started_at INTEGER, ' +
    'ended_at INTEGER, ended_by TEXT)',
  'CREATE TABLE IF NOT EXISTS protocols (id TEXT PRIMARY KEY, version INTEGER, source TEXT, status TEXT, ' +
    'file_hash TEXT, installed_at INTEGER)',
  'CREATE TABLE IF NOT EXISTS day_plans (id TEXT PRIMARY KEY, day TEXT NOT NULL, template TEXT, queue TEXT, ' +
    'edits TEXT, activation TEXT, created_at INTEGER, effective_at INTEGER)',
  'CREATE INDEX IF NOT EXISTS beats_bout ON beats(bout_id, asked_at)',
  'CREATE INDEX IF NOT EXISTS evidence_bout ON evidence(bout_id)',
  'CREATE INDEX IF NOT EXISTS deliverables_bout ON deliverables(bout_id)',
  'CREATE INDEX IF NOT EXISTS verdicts_bout ON verdicts(bout_id)',
  'CREATE INDEX IF NOT EXISTS escalations_started ON escalations(started_at)',
  'CREATE INDEX IF NOT EXISTS day_plans_day ON day_plans(day)',
  "CREATE UNIQUE INDEX IF NOT EXISTS bouts_one_running ON bouts(status) WHERE status = 'running'",
]

/** 八张表；重建与清库按这个顺序删（子表先删）。 */
export const BOUT_TABLES = ['beats', 'evidence', 'deliverables', 'verdicts', 'escalations', 'protocols', 'day_plans', 'bouts']

export const RUNNING = 'running'
export const CLOSED = 'closed'
/** 收局原因：J1 只定名字，具体判定在协议层。 */
export const CLOSE_REASONS = ['done', 'timeout', 'abandoned', 'escalated', 'replaced']

let seq = 0

function nextId(prefix, now) {
  seq += 1
  return prefix + ':' + String(now) + ':' + String(seq)
}

function jsonText(value) {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return ''
  }
}

function parseJson(text, fallback) {
  if (!text) return fallback
  try {
    return JSON.parse(text)
  } catch {
    return fallback
  }
}

function num(value, fallback = 0) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

/** 建表 + 与记忆库一致的 WAL 设置。可重复调用。 */
export function ensureBoutSchema(db) {
  db.prepare('PRAGMA journal_mode = WAL').run()
  db.prepare('PRAGMA synchronous = NORMAL').run()
  for (const sql of BOUT_DDL) db.prepare(sql).run()
}

/**
 * 打开（必要时创建）局的库。拿不到 node:sqlite 或文件不可写时返回 null，调用方按“没有库”降级。
 * @param {string} file fish-memory.sqlite 的路径
 */
export function openBoutsDb(file) {
  if (!DatabaseSync || !file) return null
  try {
    mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
    const db = new DatabaseSync(file)
    ensureBoutSchema(db)
    return db
  } catch {
    return null
  }
}

function append(home, entry) {
  if (home) appendLedger(home, entry)
}

/**
 * 开局。已有局在进行中时拒绝（bouts_one_running 索引会抛错，这里翻成 {ok:false,error:'busy'}）。
 * @returns {{ok:true,id:string}|{ok:false,error:string}}
 */
export function openBout(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const protocolId = String(input.protocolId || '').trim()
  if (!protocolId) return { ok: false, error: 'no-protocol' }
  const now = num(input.now, Date.now())
  const id = String(input.id || nextId('bout', now))
  try {
    db.prepare(
      'INSERT INTO bouts (id, protocol_id, opened_by, planned_at, opened_at, closed_at, close_reason, status, department) ' +
        'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
    ).run(id, protocolId, String(input.openedBy || 'engine'), num(input.plannedAt), now, 0, '', RUNNING,
      String(input.department || ''))
  } catch (error) {
    const text = String((error && error.message) || error)
    if (/UNIQUE|constraint/i.test(text)) return { ok: false, error: 'busy' }
    return { ok: false, error: 'failed' }
  }
  append(input.home, {
    at: now, state: 'bout-open', boutId: id, protocolId, openedBy: String(input.openedBy || 'engine'),
    plannedAt: num(input.plannedAt), department: String(input.department || ''),
  })
  return { ok: true, id }
}

/** 收局。重复收局返回 {ok:false,error:'not-running'}。 */
export function closeBout(db, id, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const now = num(input.now, Date.now())
  const reason = String(input.reason || 'done')
  if (!CLOSE_REASONS.includes(reason)) return { ok: false, error: 'bad-reason' }
  const r = db.prepare("UPDATE bouts SET status = ?, closed_at = ?, close_reason = ? WHERE id = ? AND status = 'running'")
    .run(CLOSED, now, reason, String(id))
  if (Number(r.changes) !== 1) return { ok: false, error: 'not-running' }
  append(input.home, { at: now, state: 'bout-close', boutId: String(id), reason })
  return { ok: true }
}

/** 改一局的非状态字段（收局/状态只能走 closeBout，免得绕过索引）。 */
const BOUT_COLUMNS = {
  protocolId: 'protocol_id',
  openedBy: 'opened_by',
  plannedAt: 'planned_at',
  department: 'department',
}

export function updateBout(db, id, patch = {}, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const sets = []
  const values = []
  for (const [key, column] of Object.entries(BOUT_COLUMNS)) {
    if (patch[key] === undefined) continue
    sets.push(column + ' = ?')
    values.push(typeof patch[key] === 'number' ? num(patch[key]) : String(patch[key]))
  }
  if (!sets.length) return { ok: false, error: 'nothing-to-update' }
  values.push(String(id))
  const r = db.prepare('UPDATE bouts SET ' + sets.join(', ') + ' WHERE id = ?').run(...values)
  if (Number(r.changes) !== 1) return { ok: false, error: 'not-found' }
  append(input.home, { at: num(input.now, Date.now()), state: 'bout-update', boutId: String(id), patch })
  return { ok: true }
}

/** 删一局连同它的拍、依据、产出物、判定。 */
export function deleteBout(db, id, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const key = String(id)
  const row = db.prepare('SELECT id FROM bouts WHERE id = ?').get(key)
  if (!row) return { ok: false, error: 'not-found' }
  db.prepare('DELETE FROM beats WHERE bout_id = ?').run(key)
  db.prepare('DELETE FROM evidence WHERE bout_id = ?').run(key)
  db.prepare('DELETE FROM deliverables WHERE bout_id = ?').run(key)
  db.prepare('DELETE FROM verdicts WHERE bout_id = ?').run(key)
  db.prepare('DELETE FROM bouts WHERE id = ?').run(key)
  append(input.home, { at: num(input.now, Date.now()), state: 'bout-delete', boutId: key })
  return { ok: true }
}

function boutRow(r) {
  return {
    id: r.id,
    protocolId: r.protocol_id,
    openedBy: r.opened_by || '',
    plannedAt: num(r.planned_at),
    openedAt: num(r.opened_at),
    closedAt: num(r.closed_at),
    closeReason: r.close_reason || '',
    status: r.status,
    department: r.department || '',
  }
}

export function getBout(db, id) {
  if (!db) return null
  const r = db.prepare('SELECT * FROM bouts WHERE id = ?').get(String(id))
  return r ? boutRow(r) : null
}

/** 进行中的那一局（最多一个）。 */
export function runningBout(db) {
  if (!db) return null
  const r = db.prepare("SELECT * FROM bouts WHERE status = 'running' LIMIT 1").get()
  return r ? boutRow(r) : null
}

/**
 * 列局。status 过滤；day 给了就只列那一天开的局（按 openedAt 落在 [dayStart, dayStart+24h)）。
 */
export function listBouts(db, query = {}) {
  if (!db) return []
  const limit = Math.max(1, Math.min(500, num(query.limit, 50)))
  const status = String(query.status || '')
  const dayStart = query.dayStart === undefined ? null : num(query.dayStart)
  let sql = 'SELECT * FROM bouts'
  const where = []
  const args = []
  if (status) {
    where.push('status = ?')
    args.push(status)
  }
  if (dayStart !== null) {
    where.push('opened_at >= ? AND opened_at < ?')
    args.push(dayStart, dayStart + 86_400_000)
  }
  if (where.length) sql += ' WHERE ' + where.join(' AND ')
  sql += ' ORDER BY opened_at DESC LIMIT ?'
  args.push(limit)
  return db.prepare(sql).all(...args).map(boutRow)
}

/** 一拍。 */
export function addBeat(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const boutId = String(input.boutId || '')
  if (!boutId) return { ok: false, error: 'no-bout' }
  const now = num(input.now, Date.now())
  const id = String(input.id || nextId('beat', now))
  db.prepare(
    'INSERT INTO beats (id, bout_id, step_id, asked_at, channel, answer, metrics, verdict, retries) ' +
      'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
  ).run(id, boutId, String(input.stepId || ''), now, String(input.channel || 'voice'), String(input.answer || ''),
    jsonText(input.metrics), String(input.verdict || ''), num(input.retries))
  append(input.home, {
    at: now, state: 'beat', boutId, beatId: id, stepId: String(input.stepId || ''),
    channel: String(input.channel || 'voice'), answer: String(input.answer || ''), metrics: input.metrics,
    verdict: String(input.verdict || ''), retries: num(input.retries),
  })
  return { ok: true, id }
}

function beatRow(r) {
  return {
    id: r.id,
    boutId: r.bout_id,
    stepId: r.step_id || '',
    askedAt: num(r.asked_at),
    channel: r.channel || '',
    answer: r.answer || '',
    metrics: parseJson(r.metrics, {}),
    verdict: r.verdict || '',
    retries: num(r.retries),
  }
}

export function listBeats(db, boutId) {
  if (!db) return []
  return db.prepare('SELECT * FROM beats WHERE bout_id = ? ORDER BY asked_at ASC, id ASC').all(String(boutId))
    .map(beatRow)
}

/** 判错依据。 */
export function addEvidence(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const now = num(input.now, Date.now())
  const sourceType = String(input.sourceType || '')
  if (!['parse', 'quote', 'record'].includes(sourceType)) return { ok: false, error: 'bad-source-type' }
  const id = String(input.id || nextId('ev', now))
  db.prepare('INSERT INTO evidence (id, bout_id, beat_id, source_type, ref, content_hash) VALUES (?, ?, ?, ?, ?, ?)')
    .run(id, String(input.boutId || ''), String(input.beatId || ''), sourceType, String(input.ref || ''),
      String(input.contentHash || ''))
  append(input.home, {
    at: now, state: 'evidence', boutId: String(input.boutId || ''), beatId: String(input.beatId || ''),
    evidenceId: id, sourceType, ref: String(input.ref || ''), contentHash: String(input.contentHash || ''),
  })
  return { ok: true, id }
}

export function listEvidence(db, boutId) {
  if (!db) return []
  return db.prepare('SELECT * FROM evidence WHERE bout_id = ? ORDER BY id ASC').all(String(boutId))
    .map((r) => ({ id: r.id, boutId: r.bout_id || '', beatId: r.beat_id || '', sourceType: r.source_type || '',
      ref: r.ref || '', contentHash: r.content_hash || '' }))
}

/** 产出物。 */
export const DELIVERABLE_FORMS = ['text', 'transcript', 'photo', 'file', 'external']

export function addDeliverable(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const now = num(input.now, Date.now())
  const form = String(input.form || '')
  if (!DELIVERABLE_FORMS.includes(form)) return { ok: false, error: 'bad-form' }
  const id = String(input.id || nextId('dv', now))
  db.prepare('INSERT INTO deliverables (id, bout_id, beat_id, form, ref, text) VALUES (?, ?, ?, ?, ?, ?)')
    .run(id, String(input.boutId || ''), String(input.beatId || ''), form, String(input.ref || ''), String(input.text || ''))
  append(input.home, {
    at: now, state: 'deliverable', boutId: String(input.boutId || ''), beatId: String(input.beatId || ''),
    deliverableId: id, form, ref: String(input.ref || ''), text: String(input.text || ''),
  })
  return { ok: true, id }
}

export function listDeliverables(db, boutId) {
  if (!db) return []
  return db.prepare('SELECT * FROM deliverables WHERE bout_id = ? ORDER BY id ASC').all(String(boutId))
    .map((r) => ({ id: r.id, boutId: r.bout_id || '', beatId: r.beat_id || '', form: r.form || '',
      ref: r.ref || '', text: r.text || '' }))
}

/** 判定。层只能是代码 / 模型 / 墙。 */
export const VERDICT_LAYERS = ['code', 'model', 'wall']

export function addVerdict(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const now = num(input.now, Date.now())
  const layer = String(input.layer || '')
  if (!VERDICT_LAYERS.includes(layer)) return { ok: false, error: 'bad-layer' }
  const result = String(input.result || '')
  if (!result) return { ok: false, error: 'no-result' }
  const id = String(input.id || nextId('vd', now))
  db.prepare('INSERT INTO verdicts (id, bout_id, beat_id, layer, result, score, quote) VALUES (?, ?, ?, ?, ?, ?, ?)')
    .run(id, String(input.boutId || ''), String(input.beatId || ''), layer, result, num(input.score),
      String(input.quote || ''))
  append(input.home, {
    at: now, state: 'verdict', boutId: String(input.boutId || ''), beatId: String(input.beatId || ''),
    verdictId: id, layer, result, score: num(input.score), quote: String(input.quote || ''),
  })
  return { ok: true, id }
}

export function listVerdicts(db, boutId) {
  if (!db) return []
  return db.prepare('SELECT * FROM verdicts WHERE bout_id = ? ORDER BY id ASC').all(String(boutId))
    .map((r) => ({ id: r.id, boutId: r.bout_id || '', beatId: r.beat_id || '', layer: r.layer || '',
      result: r.result || '', score: Number(r.score) || 0, quote: r.quote || '' }))
}

/** 一次升级：开一次、关一次。 */
export function startEscalation(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const now = num(input.now, Date.now())
  const reason = String(input.reason || '')
  if (!reason) return { ok: false, error: 'no-reason' }
  const id = String(input.id || nextId('esc', now))
  db.prepare('INSERT INTO escalations (id, reason, level, started_at, ended_at, ended_by) VALUES (?, ?, ?, ?, 0, ?)')
    .run(id, reason, String(input.level || 'L1'), now, '')
  append(input.home, { at: now, state: 'escalation-open', escalationId: id, reason, level: String(input.level || 'L1') })
  return { ok: true, id }
}

export function endEscalation(db, id, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const now = num(input.now, Date.now())
  const endedBy = String(input.endedBy || '')
  if (!endedBy) return { ok: false, error: 'no-ended-by' }
  const r = db.prepare('UPDATE escalations SET ended_at = ?, ended_by = ? WHERE id = ? AND ended_at = 0')
    .run(now, endedBy, String(id))
  if (Number(r.changes) !== 1) return { ok: false, error: 'not-open' }
  append(input.home, { at: now, state: 'escalation-close', escalationId: String(id), endedBy })
  return { ok: true }
}

export function listEscalations(db, query = {}) {
  if (!db) return []
  const limit = Math.max(1, Math.min(500, num(query.limit, 50)))
  return db.prepare('SELECT * FROM escalations ORDER BY started_at DESC LIMIT ?').all(limit)
    .map((r) => ({ id: r.id, reason: r.reason || '', level: r.level || '', startedAt: num(r.started_at),
      endedAt: num(r.ended_at), endedBy: r.ended_by || '' }))
}

/** 已装协议。 */
export const PROTOCOL_STATUS = ['builtin', 'trial', 'official', 'retired']

export function upsertProtocol(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const id = String(input.id || '').trim()
  if (!id) return { ok: false, error: 'no-id' }
  const status = String(input.status || 'builtin')
  if (!PROTOCOL_STATUS.includes(status)) return { ok: false, error: 'bad-status' }
  const now = num(input.now, Date.now())
  db.prepare(
    'INSERT INTO protocols (id, version, source, status, file_hash, installed_at) VALUES (?, ?, ?, ?, ?, ?) ' +
      'ON CONFLICT(id) DO UPDATE SET version = excluded.version, source = excluded.source, ' +
      'status = excluded.status, file_hash = excluded.file_hash',
  ).run(id, num(input.version, 1), String(input.source || ''), status, String(input.fileHash || ''), now)
  append(input.home, {
    at: now, state: 'protocol', protocolId: id, version: num(input.version, 1), source: String(input.source || ''),
    status, fileHash: String(input.fileHash || ''),
  })
  return { ok: true }
}

export function listProtocols(db, query = {}) {
  if (!db) return []
  const status = String(query.status || '')
  const sql = status
    ? 'SELECT * FROM protocols WHERE status = ? ORDER BY id ASC'
    : 'SELECT * FROM protocols ORDER BY id ASC'
  const rows = status ? db.prepare(sql).all(status) : db.prepare(sql).all()
  return rows.map((r) => ({ id: r.id, version: num(r.version, 1), source: r.source || '', status: r.status || '',
    fileHash: r.file_hash || '', installedAt: num(r.installed_at) }))
}

/** 一天的计划。 */
export const ACTIVATIONS = ['confirmed', 'auto']

export function saveDayPlan(db, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const day = String(input.day || '').trim()
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return { ok: false, error: 'bad-day' }
  const activation = String(input.activation || 'auto')
  if (!ACTIVATIONS.includes(activation)) return { ok: false, error: 'bad-activation' }
  const now = num(input.now, Date.now())
  const id = String(input.id || 'plan:' + day)
  db.prepare(
    'INSERT INTO day_plans (id, day, template, queue, edits, activation, created_at, effective_at) ' +
      'VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET template = excluded.template, ' +
      'queue = excluded.queue, edits = excluded.edits, activation = excluded.activation, ' +
      'effective_at = excluded.effective_at',
  ).run(id, day, String(input.template || ''), jsonText(input.queue ?? []), jsonText(input.edits ?? []), activation,
    num(input.createdAt, now), num(input.effectiveAt))
  append(input.home, {
    at: now, state: 'day-plan', planId: id, day, template: String(input.template || ''),
    queue: input.queue ?? [], edits: input.edits ?? [], activation, effectiveAt: num(input.effectiveAt),
  })
  return { ok: true, id }
}

export function getDayPlan(db, day) {
  if (!db) return null
  const r = db.prepare('SELECT * FROM day_plans WHERE day = ? ORDER BY created_at DESC LIMIT 1').get(String(day))
  if (!r) return null
  return {
    id: r.id, day: r.day, template: r.template || '', queue: parseJson(r.queue, []), edits: parseJson(r.edits, []),
    activation: r.activation || '', createdAt: num(r.created_at), effectiveAt: num(r.effective_at),
  }
}

/**
 * 把第七轮 A2 的时间块数据（companion/daily.json 的 {day, tasks, plan.blocks, review}）迁进 day_plans。
 * 只在当天还没有计划行时写；返回 {ok, day, blocks, skipped}。
 * 不删旧文件：daily.json 还是 A2 的权威，这里只是让局能读到同一份时间块。
 */
export function migrateLegacyDayPlan(db, raw, input = {}) {
  if (!db) return { ok: false, error: 'no-db' }
  const state = raw && typeof raw === 'object' ? raw : {}
  const day = String(state.day || input.day || '')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return { ok: false, error: 'bad-day' }
  const plan = state.plan && typeof state.plan === 'object' ? state.plan : {}
  const blocks = Array.isArray(plan.blocks) ? plan.blocks : []
  if (getDayPlan(db, day)) return { ok: true, day, blocks: 0, skipped: true }
  const r = saveDayPlan(db, {
    day,
    template: 'a2-daily',
    queue: blocks,
    edits: Array.isArray(plan.reschedules) ? plan.reschedules : plan.review ? [plan.review] : [],
    activation: 'auto',
    now: num(input.now, Date.now()),
    home: input.home,
  })
  if (!r.ok) return { ok: false, error: r.error }
  return { ok: true, day, blocks: blocks.length, skipped: false }
}

/**
 * 从账本重建八张表。账本是权威：先清空，再按 at 顺序重放。
 * @param {import('node:sqlite').DatabaseSync} db
 * @param {object[]} entries ledger.jsonl 的行
 */
export function rebuildFromLedger(db, entries) {
  if (!db) return { ok: false, error: 'no-db' }
  const lines = Array.isArray(entries) ? entries.filter((e) => e && typeof e === 'object') : []
  const known = new Set([
    'bout-open', 'bout-close', 'bout-update', 'bout-delete', 'beat', 'evidence', 'deliverable', 'verdict',
    'escalation-open', 'escalation-close', 'protocol', 'day-plan',
  ])
  const todo = lines.filter((e) => known.has(String(e.state || '')))
  for (const table of BOUT_TABLES) db.prepare('DELETE FROM ' + table).run()
  const counts = {}
  for (const e of todo) {
    const at = num(e.at, 0)
    const state = String(e.state)
    counts[state] = (counts[state] || 0) + 1
    if (state === 'bout-open') {
      db.prepare(
        'INSERT OR REPLACE INTO bouts (id, protocol_id, opened_by, planned_at, opened_at, closed_at, close_reason, status, department) ' +
          'VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?)',
      ).run(String(e.boutId), String(e.protocolId || ''), String(e.openedBy || ''), num(e.plannedAt), at, '',
        RUNNING, String(e.department || ''))
    } else if (state === 'bout-close') {
      db.prepare('UPDATE bouts SET status = ?, closed_at = ?, close_reason = ? WHERE id = ?')
        .run(CLOSED, at, String(e.reason || 'done'), String(e.boutId))
    } else if (state === 'bout-update') {
      const patch = e.patch && typeof e.patch === 'object' ? e.patch : {}
      const sets = []
      const values = []
      for (const [key, column] of Object.entries(BOUT_COLUMNS)) {
        if (patch[key] === undefined) continue
        sets.push(column + ' = ?')
        values.push(typeof patch[key] === 'number' ? num(patch[key]) : String(patch[key]))
      }
      if (sets.length) {
        values.push(String(e.boutId))
        db.prepare('UPDATE bouts SET ' + sets.join(', ') + ' WHERE id = ?').run(...values)
      }
    } else if (state === 'bout-delete') {
      const key = String(e.boutId)
      db.prepare('DELETE FROM beats WHERE bout_id = ?').run(key)
      db.prepare('DELETE FROM evidence WHERE bout_id = ?').run(key)
      db.prepare('DELETE FROM deliverables WHERE bout_id = ?').run(key)
      db.prepare('DELETE FROM verdicts WHERE bout_id = ?').run(key)
      db.prepare('DELETE FROM bouts WHERE id = ?').run(key)
    } else if (state === 'beat') {
      db.prepare(
        'INSERT OR REPLACE INTO beats (id, bout_id, step_id, asked_at, channel, answer, metrics, verdict, retries) ' +
          'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)',
      ).run(String(e.beatId), String(e.boutId || ''), String(e.stepId || ''), at, String(e.channel || ''),
        String(e.answer || ''), jsonText(e.metrics), String(e.verdict || ''), num(e.retries))
    } else if (state === 'evidence') {
      db.prepare('INSERT OR REPLACE INTO evidence (id, bout_id, beat_id, source_type, ref, content_hash) VALUES (?, ?, ?, ?, ?, ?)')
        .run(String(e.evidenceId), String(e.boutId || ''), String(e.beatId || ''), String(e.sourceType || ''),
          String(e.ref || ''), String(e.contentHash || ''))
    } else if (state === 'deliverable') {
      db.prepare('INSERT OR REPLACE INTO deliverables (id, bout_id, beat_id, form, ref, text) VALUES (?, ?, ?, ?, ?, ?)')
        .run(String(e.deliverableId), String(e.boutId || ''), String(e.beatId || ''), String(e.form || ''),
          String(e.ref || ''), String(e.text || ''))
    } else if (state === 'verdict') {
      db.prepare('INSERT OR REPLACE INTO verdicts (id, bout_id, beat_id, layer, result, score, quote) VALUES (?, ?, ?, ?, ?, ?, ?)')
        .run(String(e.verdictId), String(e.boutId || ''), String(e.beatId || ''), String(e.layer || ''),
          String(e.result || ''), num(e.score), String(e.quote || ''))
    } else if (state === 'escalation-open') {
      db.prepare('INSERT OR REPLACE INTO escalations (id, reason, level, started_at, ended_at, ended_by) VALUES (?, ?, ?, ?, 0, ?)')
        .run(String(e.escalationId), String(e.reason || ''), String(e.level || ''), at, '')
    } else if (state === 'escalation-close') {
      db.prepare('UPDATE escalations SET ended_at = ?, ended_by = ? WHERE id = ?')
        .run(at, String(e.endedBy || ''), String(e.escalationId))
    } else if (state === 'protocol') {
      db.prepare(
        'INSERT OR REPLACE INTO protocols (id, version, source, status, file_hash, installed_at) VALUES (?, ?, ?, ?, ?, ?)',
      ).run(String(e.protocolId), num(e.version, 1), String(e.source || ''), String(e.status || ''),
        String(e.fileHash || ''), at)
    } else if (state === 'day-plan') {
      db.prepare(
        'INSERT OR REPLACE INTO day_plans (id, day, template, queue, edits, activation, created_at, effective_at) ' +
          'VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
      ).run(String(e.planId), String(e.day || ''), String(e.template || ''), jsonText(e.queue ?? []),
        jsonText(e.edits ?? []), String(e.activation || 'auto'), at, num(e.effectiveAt))
    }
  }
  return { ok: true, replayed: todo.length, counts }
}

/**
 * 从磁盘上的账本重建：读 ledger.jsonl，重放。返回与 rebuildFromLedger 相同。
 * 账本被压缩过（超 2MB 留最后 500 行）时只重建留下的部分——这是账本的已知上限。
 */
export function rebuildFromLedgerFile(db, home) {
  const entries = readLedger(home, Number.MAX_SAFE_INTEGER)
  return rebuildFromLedger(db, entries)
}

/** 库存在与否（不创建）。 */
export function boutsDbExists(file) {
  return Boolean(file) && existsSync(file)
}
