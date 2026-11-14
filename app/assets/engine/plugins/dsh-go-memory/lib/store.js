// 大肥鱼本地记忆 · 落盘层
//
// 权威数据放在手机上：优先 SQLite（node:sqlite，Node 22.13+ 自带），
// 老旧运行时拿不到 node:sqlite 时自动退到同目录的 JSON（原子写），
// 两条路的 doc 形状完全一样，上层逻辑不用分支。
//
// 为什么整份快照写：一个人的长期记忆通常几千条以内，写一次几十毫秒；
// 换来的是「不会有一半写入」的强一致，比零碎的增量 SQL 更好维护。

import { createRequire } from 'node:module'
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, writeSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { emptyDoc, SCHEMA_VERSION } from './model.js'

const require = createRequire(import.meta.url)
let DatabaseSync = null
try {
  ;({ DatabaseSync } = require('node:sqlite'))
} catch {
  DatabaseSync = null
}

// 全部是固定 SQL，不接受任何拼接；参数一律走 prepared statement 的占位符。
// 导出只给测试用：dsh-go-companion 的 bouts.js 有一份同样的局表 DDL（它要在记忆插件还没
// 开过库时也能自己建表），两处写法必须一致，测试拿这份来比对，防止改一处忘一处。
export const DDL = [
  'CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)',
  'CREATE TABLE IF NOT EXISTS facts (id TEXT PRIMARY KEY, scope TEXT NOT NULL, text TEXT NOT NULL, kind TEXT, ' +
    'importance INTEGER, pinned INTEGER, key TEXT, tags TEXT, created_at INTEGER, updated_at INTEGER, ' +
    'last_hit_at INTEGER, hits INTEGER, hit_bonus REAL, expires_at INTEGER, source TEXT, status TEXT, ' +
    'superseded_by TEXT, superseded_reason TEXT, corrected_from TEXT, forget_reason TEXT)',
  'CREATE TABLE IF NOT EXISTS topics (id TEXT PRIMARY KEY, scope TEXT NOT NULL, title TEXT NOT NULL, detail TEXT, ' +
    'status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, ' +
    'next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)',
  'CREATE TABLE IF NOT EXISTS episodes (id TEXT PRIMARY KEY, scope TEXT NOT NULL, text TEXT NOT NULL, tags TEXT, at INTEGER)',
  'CREATE TABLE IF NOT EXISTS tombstones (id TEXT PRIMARY KEY, scope TEXT NOT NULL, kind TEXT, ref_id TEXT, ' +
    'text_hash TEXT, reason TEXT, at INTEGER)',
  'CREATE TABLE IF NOT EXISTS hits (id TEXT PRIMARY KEY, fact_id TEXT NOT NULL, at INTEGER, query TEXT)',
  'CREATE INDEX IF NOT EXISTS facts_scope ON facts(scope, status)',
  'CREATE INDEX IF NOT EXISTS topics_scope ON topics(scope, status)',
  'CREATE INDEX IF NOT EXISTS episodes_scope ON episodes(scope, at)',
  // 局（第八轮 J1）。同一个库里加表，走的就是这里的迁移机制：每次开库把 DDL 全跑一遍，
  // CREATE TABLE IF NOT EXISTS 幂等，所以老库补表不需要改 schema 版本，也不需要搬数据。
  // 所有写入只走 dsh-go-companion/lib/bouts.js；这里只负责建表。
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
  // 引擎内只有一个局处于进行中（J2 第 4 条）：交给数据库保证，不靠调用方自觉。
  "CREATE UNIQUE INDEX IF NOT EXISTS bouts_one_running ON bouts(status) WHERE status = 'running'",
]

const INSERT_FACT = 'INSERT INTO facts (id, scope, text, kind, importance, pinned, key, tags, triggers, scene_tags, created_at, updated_at, ' +
  'last_hit_at, hits, hit_bonus, expires_at, source, status, superseded_by, superseded_reason, corrected_from, forget_reason) ' +
  'VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'

const INSERT_TOPIC = 'INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, updated_at, ' +
  'last_touch_at, next_ask_at, attempts, due_at, closed_at, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'

const CLEAR_ALL = ['DELETE FROM facts', 'DELETE FROM topics', 'DELETE FROM episodes', 'DELETE FROM tombstones', 'DELETE FROM hits']

function factRow(f) {
  return [f.id, f.scope, f.text, f.kind || 'fact', num(f.importance, 1), f.pinned ? 1 : 0, f.key || '',
    JSON.stringify(f.tags || []), JSON.stringify(f.triggers || []), JSON.stringify(f.sceneTags || []),
    num(f.createdAt), num(f.updatedAt), num(f.lastHitAt), num(f.hits),
    Number(f.hitBonus) || 0, num(f.expiresAt), f.source || '', f.status || 'active', f.supersededBy || '',
    f.supersededReason || '', f.correctedFrom || '', f.forgetReason || '']
}

function topicRow(t) {
  return [t.id, t.scope, t.title, t.detail || '', t.status || 'open', num(t.priority, 1), num(t.openedAt),
    num(t.updatedAt), num(t.lastTouchAt), num(t.nextAskAt), num(t.attempts), num(t.dueAt), num(t.closedAt), t.reason || '']
}

function fromFactRow(r) {
  return {
    id: r.id, scope: r.scope, text: r.text, kind: r.kind || 'fact', importance: Number(r.importance) || 0,
    pinned: Number(r.pinned) === 1, key: r.key || '', tags: parseTags(r.tags),
    triggers: parseTags(r.triggers), sceneTags: parseTags(r.scene_tags),
    createdAt: Number(r.created_at) || 0, updatedAt: Number(r.updated_at) || 0,
    lastHitAt: Number(r.last_hit_at) || 0, hits: Number(r.hits) || 0, hitBonus: Number(r.hit_bonus) || 0,
    expiresAt: Number(r.expires_at) || 0, source: r.source || '', status: r.status || 'active',
    supersededBy: r.superseded_by || '', supersededReason: r.superseded_reason || '',
    correctedFrom: r.corrected_from || '', forgetReason: r.forget_reason || '',
  }
}

function fromTopicRow(r) {
  return {
    id: r.id, scope: r.scope, title: r.title, detail: r.detail || '', status: r.status || 'open',
    priority: Number(r.priority) || 0, openedAt: Number(r.opened_at) || 0, updatedAt: Number(r.updated_at) || 0,
    lastTouchAt: Number(r.last_touch_at) || 0, nextAskAt: Number(r.next_ask_at) || 0,
    attempts: Number(r.attempts) || 0, dueAt: Number(r.due_at) || 0, closedAt: Number(r.closed_at) || 0,
    reason: r.reason || '',
  }
}

function num(v, fallback = 0) {
  const n = Number(v)
  return Number.isFinite(n) ? Math.round(n) : fallback
}

function parseTags(raw) {
  try {
    const v = JSON.parse(raw || '[]')
    return Array.isArray(v) ? v.map(String) : []
  } catch {
    return []
  }
}

function ensureDir(file) {
  mkdirSync(dirname(file), { recursive: true, mode: 0o700 })
}

/** 原子写：先写临时文件、fsync、再 rename；权限 0600。 */
export function writeJsonAtomic(file, value) {
  ensureDir(file)
  const tmp = file + '.' + String(process.pid) + '.tmp'
  const body = JSON.stringify(value)
  const fd = openSync(tmp, 'w', 0o600)
  try {
    writeSync(fd, body, null, 'utf8')
    fsyncSync(fd)
  } finally {
    closeSync(fd)
  }
  renameSync(tmp, file)
}

function readJson(file) {
  if (!existsSync(file)) return null
  try {
    const raw = JSON.parse(readFileSync(file, 'utf8'))
    if (!raw || typeof raw !== 'object') return null
    return raw
  } catch {
    return null
  }
}

function normalizeDoc(raw) {
  const doc = emptyDoc()
  if (!raw || typeof raw !== 'object') return doc
  if (Array.isArray(raw.facts)) doc.facts = raw.facts.filter((f) => f && f.id && f.text).map((f) => ({ ...f }))
  if (Array.isArray(raw.topics)) doc.topics = raw.topics.filter((t) => t && t.id && t.title)
  if (Array.isArray(raw.episodes)) doc.episodes = raw.episodes.filter((e) => e && e.id)
  if (Array.isArray(raw.tombstones)) doc.tombstones = raw.tombstones.filter((t) => t && t.id)
  if (Array.isArray(raw.hits)) doc.hits = raw.hits.filter((h) => h && h.id)
  if (Number(raw.v) !== SCHEMA_VERSION) doc.v = SCHEMA_VERSION
  return doc
}

function ftsText(parts) {
  const raw = parts.filter(Boolean).join(' ')
  const extra = []
  const cjk = raw.replace(/[^\u4e00-\u9fff]/g, '')
  for (let i = 0; i + 1 < cjk.length && extra.length < 400; i += 1) {
    extra.push(cjk.slice(i, i + 2))
  }
  return extra.length ? raw + ' ' + extra.join(' ') : raw
}

function ftsMatchQuery(query) {
  const s = String(query || '').toLowerCase()
  const terms = []
  for (const w of s.match(/[a-z0-9_]{2,}/g) || []) terms.push(w)
  const cjk = s.replace(/[^\u4e00-\u9fff]/g, '')
  if (cjk.length === 1) terms.push(cjk)
  for (let i = 0; i + 1 < cjk.length && terms.length < 24; i += 1) {
    terms.push(cjk.slice(i, i + 2))
  }
  if (!terms.length) return ''
  return [...new Set(terms)].map((t) => '"' + t.replace(/"/g, '') + '"').join(' OR ')
}

function ensureColumn(db, table, col, type) {
  const cols = db.prepare(`PRAGMA table_info(${table})`).all()
  if (!cols.some((c) => c.name === col)) {
    db.prepare(`ALTER TABLE ${table} ADD COLUMN ${col} ${type}`).run()
  }
}

class SqliteBackend {
  constructor(file) {
    ensureDir(file)
    this.file = file
    this.kind = 'sqlite'
    this.hasFts5 = false
    try {
      const db = this.open()
      db.close()
    } catch (error) {
      // 不是数据库 / 没有权限：不留下任何打开的文件句柄，调用方会降级到 JSON。
      throw error
    }
  }

  /**
   * 每次操作开一次、用完就关。
   * 手机上一份记忆的写入频率很低（一轮最多几次），换来的是：进程被杀、
   * 面板刷新、外部工具读文件时都不会撞上长连接锁；测试和打包脚本也能直接删目录。
   */
  open() {
    const db = new DatabaseSync(this.file)
    try {
      db.prepare('PRAGMA journal_mode = WAL').run()
      db.prepare('PRAGMA synchronous = NORMAL').run()
      for (const sql of DDL) db.prepare(sql).run()
      ensureColumn(db, 'facts', 'triggers', 'TEXT')
      ensureColumn(db, 'facts', 'scene_tags', 'TEXT')
      ensureColumn(db, 'episodes', 'scene_tags', 'TEXT')
      try {
        db.prepare(
          "CREATE VIRTUAL TABLE IF NOT EXISTS facts_fts USING fts5(id UNINDEXED, scope UNINDEXED, body, tokenize='unicode61')",
        ).run()
        db.prepare(
          "CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(id UNINDEXED, scope UNINDEXED, body, tokenize='unicode61')",
        ).run()
        this.hasFts5 = true
      } catch {
        this.hasFts5 = false
      }
      db.prepare('INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)').run('schema', String(SCHEMA_VERSION))
      return db
    } catch (error) {
      try { db.close() } catch {}
      throw error
    }
  }

  load() {
    const doc = emptyDoc()
    const db = this.open()
    try {
      doc.facts = db.prepare('SELECT * FROM facts').all().map(fromFactRow)
      doc.topics = db.prepare('SELECT * FROM topics').all().map(fromTopicRow)
      doc.episodes = db.prepare('SELECT * FROM episodes').all().map((r) => ({
        id: r.id, scope: r.scope, text: r.text, tags: parseTags(r.tags),
        sceneTags: parseTags(r.scene_tags), at: Number(r.at) || 0,
      }))
      doc.tombstones = db.prepare('SELECT * FROM tombstones').all().map((r) => ({
        id: r.id, scope: r.scope, kind: r.kind || 'fact', refId: r.ref_id || '',
        textHash: r.text_hash || '', reason: r.reason || '', at: Number(r.at) || 0,
      }))
      doc.hits = db.prepare('SELECT * FROM hits ORDER BY at DESC LIMIT 2000').all().map((r) => ({
        id: r.id, factId: r.fact_id, at: Number(r.at) || 0, query: r.query || '',
      }))
    } finally {
      db.close()
    }
    return doc
  }

  save(doc) {
    const db = this.open()
    try {
      db.prepare('BEGIN IMMEDIATE').run()
      try {
        for (const sql of CLEAR_ALL) db.prepare(sql).run()
        if (this.hasFts5) {
          db.prepare('DELETE FROM facts_fts').run()
          db.prepare('DELETE FROM episodes_fts').run()
        }
        const factStmt = db.prepare(INSERT_FACT)
        const factFtsStmt = this.hasFts5 ? db.prepare('INSERT INTO facts_fts (id, scope, body) VALUES (?, ?, ?)') : null
        for (const f of doc.facts) {
          factStmt.run(...factRow(f))
          if (factFtsStmt && f.status === 'active') {
            factFtsStmt.run(f.id, f.scope, ftsText([f.text, ...(f.tags || []), ...(f.triggers || []), ...(f.sceneTags || [])]))
          }
        }
        const topicStmt = db.prepare(INSERT_TOPIC)
        for (const t of doc.topics) topicStmt.run(...topicRow(t))
        const epStmt = db.prepare('INSERT INTO episodes (id, scope, text, tags, scene_tags, at) VALUES (?, ?, ?, ?, ?, ?)')
        const epFtsStmt = this.hasFts5 ? db.prepare('INSERT INTO episodes_fts (id, scope, body) VALUES (?, ?, ?)') : null
        for (const e of doc.episodes) {
          epStmt.run(e.id, e.scope, e.text, JSON.stringify(e.tags || []), JSON.stringify(e.sceneTags || []), num(e.at))
          if (epFtsStmt) epFtsStmt.run(e.id, e.scope, ftsText([e.text, ...(e.tags || []), ...(e.sceneTags || [])]))
        }
        const tombStmt = db.prepare('INSERT INTO tombstones (id, scope, kind, ref_id, text_hash, reason, at) VALUES (?, ?, ?, ?, ?, ?, ?)')
        for (const t of doc.tombstones) tombStmt.run(t.id, t.scope, t.kind || 'fact', t.refId || '', t.textHash || '', t.reason || '', num(t.at))
        const hitStmt = db.prepare('INSERT INTO hits (id, fact_id, at, query) VALUES (?, ?, ?, ?)')
        for (const h of doc.hits) hitStmt.run(h.id, h.factId, num(h.at), h.query || '')
        db.prepare('COMMIT').run()
      } catch (error) {
        try { db.prepare('ROLLBACK').run() } catch {}
        throw error
      }
    } finally {
      db.close()
    }
  }

  ftsSearch(query, { scopes = null, limit = 20 } = {}) {
    if (!this.hasFts5) return []
    const match = ftsMatchQuery(query)
    if (!match) return []
    const cap = Math.max(1, Math.min(50, Number(limit) || 20))
    let db = null
    try {
      db = this.open()
      const rows = db.prepare(
        'SELECT id, scope, bm25(facts_fts) AS rank FROM facts_fts WHERE facts_fts MATCH ? ORDER BY rank LIMIT ?',
      ).all(match, cap * 2)
      return rows
        .filter((r) => !scopes || scopes.includes(r.scope))
        .slice(0, cap)
        .map((r) => ({ id: String(r.id), rank: Number(r.rank) || 0 }))
    } catch {
      return []
    } finally {
      try { db?.close?.() } catch {}
    }
  }

  close() {}
}

class JsonBackend {
  constructor(file) {
    this.kind = 'json'
    this.file = file
  }

  load() {
    return normalizeDoc(readJson(this.file))
  }

  save(doc) {
    writeJsonAtomic(this.file, doc)
  }

  close() {}
}

/**
 * 打开记忆库。永远返回一个可用的 store（最坏情况退到 JSON），
 * `store.kind` 会告诉面板现在用的是哪条路。
 */
export function openStore(options = {}) {
  const home = String(options.home || '').trim()
  const dir = join(home, 'fish-memory')
  const sqliteFile = join(dir, 'fish-memory.sqlite')
  const jsonFile = join(dir, 'memory.json')
  let backend = null
  let note = ''
  if (options.json === true) {
    note = '按调用方要求使用 JSON'
  } else if (DatabaseSync) {
    try {
      backend = new SqliteBackend(sqliteFile)
    } catch (error) {
      note = 'SQLite 打不开，改用 JSON：' + String((error && error.message) || error)
    }
  } else {
    note = '这个 Node 没有 node:sqlite，改用 JSON'
  }
  if (!backend) backend = new JsonBackend(jsonFile)
  let doc = null
  try {
    doc = backend.load()
  } catch (error) {
    // 库坏了：留一份坏文件，别让整个插件挂掉。
    try { renameSync(backend.file, backend.file + '.broken-' + String(Date.now())) } catch {}
    if (backend.kind === 'sqlite') {
      backend.close()
      backend = new JsonBackend(jsonFile)
      doc = backend.load()
      note = 'SQLite 读坏了，已改用 JSON：' + String((error && error.message) || error)
    } else {
      doc = emptyDoc()
      note = 'JSON 读坏了，已新建：' + String((error && error.message) || error)
    }
  }
  return new MemoryStore(backend, doc, { note, dir, sqliteFile, jsonFile })
}

export class MemoryStore {
  constructor(backend, doc, info) {
    this.backend = backend
    this.doc = normalizeDoc(doc)
    this.kind = backend.kind
    this.note = info.note || ''
    this.dir = info.dir
    this.file = backend.file
    this.saves = 0
    this.lastSaveAt = 0
    this.lastError = ''
  }

  /** 改完 doc 调一次。整份快照写，失败不吞（调用方决定要不要提示）。 */
  save() {
    try {
      this.backend.save(this.doc)
      this.saves += 1
      this.lastSaveAt = Date.now()
      this.lastError = ''
      return true
    } catch (error) {
      this.lastError = String((error && error.message) || error)
      return false
    }
  }

  ftsSearch(query, opts) {
    if (typeof this.backend.ftsSearch === 'function') return this.backend.ftsSearch(query, opts)
    return []
  }

  close() {
    try { this.backend.close() } catch {}
  }
}
