// Two-way incremental sync between IndexedDB and the three Notion databases.
//
// Each local record carries _sync = { notionId, localAt, remoteAt }:
//   localAt  = the record's updatedAt when it was last synced  → local is dirty if updatedAt > localAt
//   remoteAt = the page's last_edited_time when last synced    → remote changed if newer than remoteAt
// Order: pull (entities first, so relations resolve) then push. When both sides changed,
// the later edit wins.

import * as db from '../store/db.js';
import { notion } from './api.js';
import { getSettings, updateSettings } from './settings.js';
import {
  entitiesDbBody, interactionsDbBody, casesDbBody,
  entityToProps, interactionToProps, caseToProps,
  pageToEntity, pageToInteraction, pageToCase, CASES_V2_PROPS,
} from '../core/notion-schema.js';
import { newEntity, newInteraction, newCase, uid } from '../core/models.js';
import { analyze } from '../core/scoring.js';

// Notion allows ~3 requests/second per integration.
let lastCall = 0;
async function call(method, path, body) {
  const wait = lastCall + 350 - Date.now();
  if (wait > 0) await new Promise((r) => setTimeout(r, wait));
  lastCall = Date.now();
  return notion(method, path, body);
}

/** Accept a raw ID, a dashed UUID, or a full Notion URL. */
export function normalizeNotionId(s) {
  const m = String(s || '').replace(/-/g, '').match(/[0-9a-f]{32}(?!.*[0-9a-f]{32})/i);
  return m ? m[0] : '';
}

export async function initDatabases(parentPageInput) {
  const parent = normalizeNotionId(parentPageInput);
  if (!parent) throw new Error('请填写 Notion 父页面的链接或 ID');
  const ent = await call('POST', 'databases', entitiesDbBody(parent));
  const ints = await call('POST', 'databases', interactionsDbBody(parent, ent.id));
  const cases = await call('POST', 'databases', casesDbBody(parent, ent.id));
  updateSettings({ notion: { parentPageId: parent, entitiesDb: ent.id, interactionsDb: ints.id, casesDb: cases.id } });
  return { entitiesDb: ent.id, interactionsDb: ints.id, casesDb: cases.id };
}

async function queryAll(dbId, sinceIso) {
  const pages = [];
  let cursor;
  do {
    const body = { page_size: 100 };
    if (cursor) body.start_cursor = cursor;
    if (sinceIso) body.filter = { timestamp: 'last_edited_time', last_edited_time: { on_or_after: sinceIso } };
    const data = await call('POST', `databases/${dbId}/query`, body);
    pages.push(...data.results);
    cursor = data.has_more ? data.next_cursor : undefined;
  } while (cursor);
  return pages;
}

const STORE_DEFS = {
  entities: { dbKey: 'entitiesDb', fromPage: pageToEntity, factory: newEntity, prefix: 'ent' },
  interactions: { dbKey: 'interactionsDb', fromPage: pageToInteraction, factory: newInteraction, prefix: 'int' },
  cases: { dbKey: 'casesDb', fromPage: pageToCase, factory: newCase, prefix: 'case' },
};

async function pull(store, dbId, ctx) {
  const def = STORE_DEFS[store];
  const metaKey = `notion.pull.${store}`;
  const since = await db.getMeta(metaKey);
  // Notion rounds last_edited_time to the minute, so look back a little.
  const sinceIso = since ? new Date(since - 120000).toISOString() : null;
  const startedAt = Date.now();
  const pages = await queryAll(dbId, sinceIso);

  const locals = await db.all(store, { includeDeleted: true });
  const byNotion = new Map(locals.filter((r) => r._sync?.notionId).map((r) => [r._sync.notionId, r]));
  const byId = new Map(locals.map((r) => [r.id, r]));

  let n = 0;
  for (const page of pages) {
    const remoteAt = Date.parse(page.last_edited_time);
    const local = byNotion.get(page.id) || null;
    const fields = def.fromPage(page);
    const sameId = !local && fields.id ? byId.get(fields.id) : null;
    const target = local || sameId;

    if (target && remoteAt <= (target._sync?.remoteAt || 0)) continue; // no remote change
    const localDirty = target && target.updatedAt > (target._sync?.localAt || 0);
    if (localDirty && target.updatedAt > remoteAt) continue; // local edit is newer; push will win

    const { entityPageId, id: _ignored, ...rest } = fields;
    if (store !== 'entities') {
      const ent = entityPageId ? ctx.entityByNotion.get(entityPageId) : null;
      if (store === 'interactions' && !ent && !target?.entityId) continue; // orphan row
      rest.entityId = ent ? ent.id : target?.entityId ?? null;
    }
    const now = Date.now();
    const rec = {
      ...(target || def.factory()),
      ...rest,
      id: target?.id || fields.id || uid(def.prefix),
      deleted: false,
      updatedAt: now,
      _sync: { notionId: page.id, localAt: now, remoteAt },
    };
    if (store === 'entities') rec.contract = { ...(target?.contract || {}), ...rest.contract };
    await db.put(store, rec, { touch: false });
    if (store === 'entities') ctx.entityByNotion.set(page.id, rec);
    n++;
  }
  await db.setMeta(metaKey, startedAt);
  return n;
}

async function push(store, dbId, ctx) {
  const records = await db.all(store, { includeDeleted: true });
  const dirty = records.filter((r) => !r._sync?.notionId || r.updatedAt > (r._sync.localAt || 0));
  let pushed = 0;
  let archived = 0;

  for (const r of dirty) {
    if (r.deleted) {
      if (r._sync?.notionId) {
        await call('PATCH', `pages/${r._sync.notionId}`, { archived: true }).catch(() => {});
        archived++;
      }
      await db.purge(store, r.id);
      continue;
    }

    let properties;
    if (store === 'entities') {
      const its = ctx.interactionsByEntity.get(r.id) || [];
      properties = entityToProps(r, analyze(r, its, Date.now(), ctx.prefs));
    } else {
      const ent = r.entityId ? ctx.entityById.get(r.entityId) : null;
      const entPage = ent?._sync?.notionId || null;
      properties = store === 'interactions' ? interactionToProps(r, entPage) : caseToProps(r, entPage);
    }

    let page;
    if (r._sync?.notionId) {
      try {
        page = await call('PATCH', `pages/${r._sync.notionId}`, { properties });
      } catch (err) {
        if (err.status !== 404) throw err; // page was deleted in Notion → recreate below
      }
    }
    if (!page) page = await call('POST', 'pages', { parent: { database_id: dbId }, properties });

    const saved = { ...r, _sync: { notionId: page.id, localAt: r.updatedAt, remoteAt: Date.parse(page.last_edited_time) } };
    await db.put(store, saved, { touch: false });
    if (store === 'entities') ctx.entityById.set(saved.id, saved);
    pushed++;
  }
  return { pushed, archived };
}

/** Run a full sync. onProgress(text) is called with human-readable steps. */
export async function syncAll(onProgress = () => {}) {
  const { notion: cfg, prefs } = getSettings();
  const ids = { entities: cfg.entitiesDb, interactions: cfg.interactionsDb, cases: cfg.casesDb };
  if (!ids.entities || !ids.interactions || !ids.cases) throw new Error('请先在设置页填写或初始化三张 Notion 数据库');

  const ctx = { prefs, entityByNotion: new Map(), entityById: new Map(), interactionsByEntity: new Map() };
  const stats = { pulled: 0, pushed: 0, archived: 0 };

  const refreshEntityMaps = async () => {
    const ents = await db.all('entities', { includeDeleted: true });
    ctx.entityByNotion = new Map(ents.filter((e) => e._sync?.notionId).map((e) => [e._sync.notionId, e]));
    ctx.entityById = new Map(ents.map((e) => [e.id, e]));
  };

  // Databases created by v1 lack the outcome/rehearsal columns; adding columns is idempotent.
  if (!(await db.getMeta(`notion.schema.v2.${ids.cases}`))) {
    onProgress('更新案例库字段…');
    await call('PATCH', `databases/${ids.cases}`, { properties: CASES_V2_PROPS });
    await db.setMeta(`notion.schema.v2.${ids.cases}`, Date.now());
  }

  await refreshEntityMaps();
  for (const store of ['entities', 'interactions', 'cases']) {
    onProgress(`拉取 ${store}…`);
    stats.pulled += await pull(store, ids[store], ctx);
  }

  const its = await db.all('interactions');
  ctx.interactionsByEntity = new Map();
  for (const it of its) {
    if (!ctx.interactionsByEntity.has(it.entityId)) ctx.interactionsByEntity.set(it.entityId, []);
    ctx.interactionsByEntity.get(it.entityId).push(it);
  }

  await refreshEntityMaps();
  for (const store of ['entities', 'interactions', 'cases']) {
    onProgress(`推送 ${store}…`);
    const r = await push(store, ids[store], ctx);
    stats.pushed += r.pushed;
    stats.archived += r.archived;
  }

  await db.setMeta('notion.lastSyncAt', Date.now());
  onProgress('同步完成');
  return stats;
}

export function lastSyncAt() {
  return db.getMeta('notion.lastSyncAt');
}
