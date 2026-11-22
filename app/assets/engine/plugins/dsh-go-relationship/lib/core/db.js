import { DatabaseSync } from 'node:sqlite';

function safeJson(value, fallback) {
  if (!value) return fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function json(value) {
  return JSON.stringify(value ?? null);
}

export function openDb(path = ':memory:') {
  const db = new DatabaseSync(path);
  db.exec('PRAGMA foreign_keys = ON;');
  migrate(db);
  return db;
}

export function closeDb(db) {
  db.close();
}

function migrate(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS persona (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      name TEXT NOT NULL,
      values_json TEXT NOT NULL,
      non_negotiables_json TEXT NOT NULL,
      voice_json TEXT NOT NULL,
      own_interests_json TEXT NOT NULL,
      boundaries_json TEXT NOT NULL,
      version INTEGER NOT NULL,
      updated_by TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS user_model (
      id TEXT PRIMARY KEY,
      type TEXT NOT NULL,
      domain TEXT NOT NULL,
      statement TEXT NOT NULL,
      confidence REAL NOT NULL,
      source_episode_ids_json TEXT NOT NULL,
      first_seen TEXT NOT NULL,
      last_confirmed TEXT,
      expires_at TEXT,
      status TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS threads (
      id TEXT PRIMARY KEY,
      title TEXT NOT NULL,
      domain TEXT NOT NULL,
      state TEXT NOT NULL,
      stakes REAL NOT NULL,
      created_at TEXT NOT NULL,
      last_touch_at TEXT NOT NULL,
      next_due_at TEXT,
      next_intent TEXT,
      mandate_level INTEGER NOT NULL,
      mandate_expires_at TEXT,
      deflections INTEGER NOT NULL,
      open_questions_json TEXT NOT NULL,
      outcome TEXT,
      archive_reason TEXT,
      resolved_at TEXT,
      muted_until TEXT,
      exam_date TEXT
    );

    CREATE TABLE IF NOT EXISTS commitments (
      id TEXT PRIMARY KEY,
      owner TEXT NOT NULL,
      thread_id TEXT NOT NULL,
      statement TEXT NOT NULL,
      due_at TEXT NOT NULL,
      status TEXT NOT NULL,
      evidence TEXT,
      follow_up_at TEXT,
      closed_at TEXT,
      created_at TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS episodes (
      id TEXT PRIMARY KEY,
      ts TEXT NOT NULL,
      thread_ids_json TEXT NOT NULL,
      summary TEXT NOT NULL,
      what_agent_did TEXT,
      user_response TEXT,
      outcome TEXT,
      lesson TEXT,
      salience REAL NOT NULL
    );

    CREATE TABLE IF NOT EXISTS interventions (
      id TEXT PRIMARY KEY,
      ts TEXT NOT NULL,
      thread_id TEXT NOT NULL,
      level INTEGER NOT NULL,
      channel TEXT NOT NULL,
      reason TEXT NOT NULL,
      hypothesis TEXT,
      outcome TEXT,
      outcome_at TEXT,
      mandate_id TEXT
    );

    CREATE TABLE IF NOT EXISTS mandates (
      id TEXT PRIMARY KEY,
      thread_id TEXT,
      domain TEXT,
      level INTEGER NOT NULL,
      start_at TEXT NOT NULL,
      end_at TEXT NOT NULL,
      daily_cap INTEGER NOT NULL,
      revoked INTEGER NOT NULL,
      created_at TEXT NOT NULL,
      reason TEXT
    );

    CREATE TABLE IF NOT EXISTS self_corrections (
      id TEXT PRIMARY KEY,
      ts TEXT NOT NULL,
      intervention_id TEXT,
      thread_id TEXT,
      claim TEXT,
      correction TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS meta (
      k TEXT PRIMARY KEY,
      v TEXT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS idx_threads_domain ON threads(domain);
    CREATE INDEX IF NOT EXISTS idx_threads_state ON threads(state);
    CREATE INDEX IF NOT EXISTS idx_commitments_thread ON commitments(thread_id);
    CREATE INDEX IF NOT EXISTS idx_interventions_thread_ts ON interventions(thread_id, ts);
    CREATE INDEX IF NOT EXISTS idx_episodes_ts ON episodes(ts);
  `);
}

export function mapPersona(row) {
  if (!row) return null;
  return {
    name: row.name,
    values: safeJson(row.values_json, []),
    non_negotiables: safeJson(row.non_negotiables_json, []),
    voice: safeJson(row.voice_json, {}),
    own_interests: safeJson(row.own_interests_json, []),
    boundaries: safeJson(row.boundaries_json, []),
    version: row.version,
    updated_by: row.updated_by,
    updated_at: row.updated_at,
  };
}

export function getPersona(db) {
  return mapPersona(db.prepare('SELECT * FROM persona WHERE id = 1').get());
}

export function savePersona(db, persona) {
  db.prepare(`
    INSERT INTO persona (
      id, name, values_json, non_negotiables_json, voice_json,
      own_interests_json, boundaries_json, version, updated_by, updated_at
    ) VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      name = excluded.name,
      values_json = excluded.values_json,
      non_negotiables_json = excluded.non_negotiables_json,
      voice_json = excluded.voice_json,
      own_interests_json = excluded.own_interests_json,
      boundaries_json = excluded.boundaries_json,
      version = excluded.version,
      updated_by = excluded.updated_by,
      updated_at = excluded.updated_at
  `).run(
    persona.name,
    json(persona.values),
    json(persona.non_negotiables),
    json(persona.voice),
    json(persona.own_interests),
    json(persona.boundaries),
    persona.version,
    persona.updated_by,
    persona.updated_at,
  );
}

export function mapThread(row) {
  if (!row) return null;
  return {
    id: row.id,
    title: row.title,
    domain: row.domain,
    state: row.state,
    stakes: row.stakes,
    created_at: row.created_at,
    last_touch_at: row.last_touch_at,
    next_due_at: row.next_due_at,
    next_intent: row.next_intent,
    mandate_level: row.mandate_level,
    mandate_expires_at: row.mandate_expires_at,
    deflections: row.deflections,
    open_questions: safeJson(row.open_questions_json, []),
    outcome: row.outcome,
    archive_reason: row.archive_reason,
    resolved_at: row.resolved_at,
    muted_until: row.muted_until,
    exam_date: row.exam_date,
  };
}

export function insertThread(db, t) {
  db.prepare(`
    INSERT INTO threads (
      id, title, domain, state, stakes, created_at, last_touch_at, next_due_at,
      next_intent, mandate_level, mandate_expires_at, deflections, open_questions_json,
      outcome, archive_reason, resolved_at, muted_until, exam_date
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    t.id,
    t.title,
    t.domain,
    t.state,
    t.stakes,
    t.created_at,
    t.last_touch_at,
    t.next_due_at ?? null,
    t.next_intent ?? null,
    t.mandate_level,
    t.mandate_expires_at ?? null,
    t.deflections,
    json(t.open_questions ?? []),
    t.outcome ?? null,
    t.archive_reason ?? null,
    t.resolved_at ?? null,
    t.muted_until ?? null,
    t.exam_date ?? null,
  );
}

export function updateThread(db, t) {
  db.prepare(`
    UPDATE threads SET
      title = ?, domain = ?, state = ?, stakes = ?, last_touch_at = ?,
      next_due_at = ?, next_intent = ?, mandate_level = ?, mandate_expires_at = ?,
      deflections = ?, open_questions_json = ?, outcome = ?, archive_reason = ?,
      resolved_at = ?, muted_until = ?, exam_date = ?
    WHERE id = ?
  `).run(
    t.title,
    t.domain,
    t.state,
    t.stakes,
    t.last_touch_at,
    t.next_due_at ?? null,
    t.next_intent ?? null,
    t.mandate_level,
    t.mandate_expires_at ?? null,
    t.deflections,
    json(t.open_questions ?? []),
    t.outcome ?? null,
    t.archive_reason ?? null,
    t.resolved_at ?? null,
    t.muted_until ?? null,
    t.exam_date ?? null,
    t.id,
  );
}

export function getThread(db, id) {
  return mapThread(db.prepare('SELECT * FROM threads WHERE id = ?').get(id));
}

export function findLatestThreadByDomain(db, domain) {
  return mapThread(
    db.prepare('SELECT * FROM threads WHERE domain = ? ORDER BY created_at DESC LIMIT 1').get(domain),
  );
}

export function listThreads(db) {
  return db.prepare('SELECT * FROM threads ORDER BY created_at ASC').all().map(mapThread);
}

export function listOpenThreads(db) {
  return db
    .prepare("SELECT * FROM threads WHERE state NOT IN ('archived', 'dropped', 'resolved') ORDER BY created_at ASC")
    .all()
    .map(mapThread);
}

export function mapCommitment(row) {
  if (!row) return null;
  return { ...row };
}

export function insertCommitment(db, c) {
  db.prepare(`
    INSERT INTO commitments (
      id, owner, thread_id, statement, due_at, status, evidence,
      follow_up_at, closed_at, created_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    c.id,
    c.owner,
    c.thread_id,
    c.statement,
    c.due_at,
    c.status,
    c.evidence ?? null,
    c.follow_up_at ?? null,
    c.closed_at ?? null,
    c.created_at,
  );
}

export function updateCommitment(db, c) {
  db.prepare(`
    UPDATE commitments SET
      statement = ?, due_at = ?, status = ?, evidence = ?,
      follow_up_at = ?, closed_at = ?
    WHERE id = ?
  `).run(
    c.statement,
    c.due_at,
    c.status,
    c.evidence ?? null,
    c.follow_up_at ?? null,
    c.closed_at ?? null,
    c.id,
  );
}

export function listCommitments(db, threadId = null) {
  if (threadId) {
    return db.prepare('SELECT * FROM commitments WHERE thread_id = ? ORDER BY created_at ASC').all(threadId);
  }
  return db.prepare('SELECT * FROM commitments ORDER BY created_at ASC').all();
}

export function closeDueCommitments(db, threadId, nowIso, owner = 'user') {
  db.prepare(`
    UPDATE commitments SET status = 'done', evidence = 'reported_progress', closed_at = ?
    WHERE thread_id = ? AND owner = ? AND status = 'pending' AND due_at <= ?
  `).run(nowIso, threadId, owner, nowIso);
}

export function markMissedCommitments(db, nowIso, owner = 'user') {
  db.prepare(`
    UPDATE commitments SET status = 'missed'
    WHERE owner = ? AND status = 'pending' AND due_at < ?
  `).run(owner, nowIso);
}

export function insertEpisode(db, e) {
  db.prepare(`
    INSERT INTO episodes (
      id, ts, thread_ids_json, summary, what_agent_did,
      user_response, outcome, lesson, salience
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    e.id,
    e.ts,
    json(e.thread_ids ?? []),
    e.summary,
    e.what_agent_did ?? null,
    e.user_response ?? null,
    e.outcome ?? null,
    e.lesson ?? null,
    e.salience ?? 0.5,
  );
}

export function listEpisodes(db, threadId = null) {
  const rows = db.prepare('SELECT * FROM episodes ORDER BY ts ASC').all();
  return rows
    .map((row) => ({ ...row, thread_ids: safeJson(row.thread_ids_json, []) }))
    .filter((row) => (threadId ? row.thread_ids.includes(threadId) : true));
}

export function mapIntervention(row) {
  if (!row) return null;
  return { ...row };
}

export function insertIntervention(db, i) {
  db.prepare(`
    INSERT INTO interventions (
      id, ts, thread_id, level, channel, reason, hypothesis,
      outcome, outcome_at, mandate_id
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    i.id,
    i.ts,
    i.thread_id,
    i.level,
    i.channel,
    i.reason,
    i.hypothesis ?? null,
    i.outcome ?? null,
    i.outcome_at ?? null,
    i.mandate_id ?? null,
  );
}

export function updateInterventionOutcome(db, id, outcome, outcomeAt) {
  db.prepare('UPDATE interventions SET outcome = ?, outcome_at = ? WHERE id = ?').run(
    outcome,
    outcomeAt ?? null,
    id,
  );
}

export function getIntervention(db, id) {
  return mapIntervention(db.prepare('SELECT * FROM interventions WHERE id = ?').get(id));
}

export function listInterventions(db, threadId = null) {
  if (threadId) {
    return db
      .prepare('SELECT * FROM interventions WHERE thread_id = ? ORDER BY ts ASC')
      .all(threadId)
      .map(mapIntervention);
  }
  return db.prepare('SELECT * FROM interventions ORDER BY ts ASC').all().map(mapIntervention);
}

export function insertMandate(db, m) {
  if (!Number.isInteger(m.level) || m.level < 1 || m.level > 5) {
    throw new Error('mandate level must be an integer between 1 and 5');
  }
  if (m.level >= 4 && !m.thread_id && !m.domain) {
    throw new Error('level 4/5 mandate requires an explicit thread_id or domain');
  }
  const start = Date.parse(m.start_at);
  const end = Date.parse(m.end_at);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) {
    throw new Error('mandate requires a valid start_at < end_at window');
  }
  if (!m.created_at || !Number.isFinite(Date.parse(m.created_at))) {
    throw new Error('mandate requires a valid created_at');
  }
  if (!Number.isInteger(m.daily_cap) || m.daily_cap < 1 || m.daily_cap > 20) {
    throw new Error('mandate daily_cap must be an integer between 1 and 20');
  }

  db.prepare(`
    INSERT INTO mandates (
      id, thread_id, domain, level, start_at, end_at,
      daily_cap, revoked, created_at, reason
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    m.id,
    m.thread_id ?? null,
    m.domain ?? null,
    m.level,
    m.start_at,
    m.end_at,
    m.daily_cap,
    m.revoked ? 1 : 0,
    m.created_at,
    m.reason ?? null,
  );
}

export function listMandates(db) {
  return db.prepare('SELECT * FROM mandates ORDER BY created_at ASC').all().map((row) => ({
    ...row,
    revoked: Boolean(row.revoked),
  }));
}

export function revokeMandate(db, id, ts) {
  db.prepare('UPDATE mandates SET revoked = 1, end_at = ? WHERE id = ?').run(ts, id);
}

export function insertUserModel(db, u) {
  db.prepare(`
    INSERT INTO user_model (
      id, type, domain, statement, confidence, source_episode_ids_json,
      first_seen, last_confirmed, expires_at, status
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    u.id,
    u.type,
    u.domain,
    u.statement,
    u.confidence,
    json(u.source_episode_ids ?? []),
    u.first_seen,
    u.last_confirmed ?? null,
    u.expires_at ?? null,
    u.status,
  );
}

export function listUserModel(db) {
  return db.prepare('SELECT * FROM user_model ORDER BY first_seen ASC').all().map((row) => ({
    ...row,
    source_episode_ids: safeJson(row.source_episode_ids_json, []),
  }));
}

export function updateUserModelStatus(db, id, status, ts) {
  db.prepare('UPDATE user_model SET status = ?, last_confirmed = ? WHERE id = ?').run(status, ts, id);
}

export function insertSelfCorrection(db, c) {
  db.prepare(`
    INSERT INTO self_corrections (id, ts, intervention_id, thread_id, claim, correction)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(c.id, c.ts, c.intervention_id ?? null, c.thread_id ?? null, c.claim ?? null, c.correction);
}

export function listSelfCorrections(db, threadId = null) {
  const rows = db.prepare('SELECT * FROM self_corrections ORDER BY ts ASC').all();
  return threadId ? rows.filter((row) => row.thread_id === threadId) : rows;
}

export function setMeta(db, key, value) {
  db.prepare(`
    INSERT INTO meta (k, v) VALUES (?, ?)
    ON CONFLICT(k) DO UPDATE SET v = excluded.v
  `).run(key, value);
}

export function getMeta(db, key) {
  const row = db.prepare('SELECT v FROM meta WHERE k = ?').get(key);
  return row ? row.v : null;
}
