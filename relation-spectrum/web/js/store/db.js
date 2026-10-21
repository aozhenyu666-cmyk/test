// IndexedDB storage. Offline-first: everything lives here; Notion is an optional mirror.

const DB_NAME = 'relation-spectrum';
const DB_VERSION = 1;
export const STORES = ['entities', 'interactions', 'cases'];

let dbPromise = null;

function open() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      for (const name of STORES) {
        if (!db.objectStoreNames.contains(name)) {
          const s = db.createObjectStore(name, { keyPath: 'id' });
          if (name === 'interactions') s.createIndex('entityId', 'entityId');
        }
      }
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta');
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

function tx(store, mode, fn) {
  return open().then(
    (db) =>
      new Promise((resolve, reject) => {
        const t = db.transaction(store, mode);
        const s = t.objectStore(store);
        let result;
        Promise.resolve(fn(s)).then((r) => (result = r));
        t.oncomplete = () => resolve(result);
        t.onerror = () => reject(t.error);
        t.onabort = () => reject(t.error);
      }),
  );
}

const req2p = (r) => new Promise((resolve, reject) => {
  r.onsuccess = () => resolve(r.result);
  r.onerror = () => reject(r.error);
});

/** All live (not deleted) records of a store. Pass { includeDeleted: true } for sync. */
export async function all(store, { includeDeleted = false } = {}) {
  const rows = await tx(store, 'readonly', (s) => req2p(s.getAll()));
  return includeDeleted ? rows : rows.filter((r) => !r.deleted);
}

export function get(store, id) {
  return tx(store, 'readonly', (s) => req2p(s.get(id)));
}

/** Save a record, bumping updatedAt unless { touch: false } (used by sync). */
export function put(store, record, { touch = true } = {}) {
  const rec = touch ? { ...record, updatedAt: Date.now() } : record;
  return tx(store, 'readwrite', (s) => req2p(s.put(rec))).then(() => rec);
}

export function putMany(store, records) {
  return tx(store, 'readwrite', (s) => Promise.all(records.map((r) => req2p(s.put(r)))));
}

/** Soft delete so the deletion can be synced to Notion. */
export async function remove(store, id) {
  const rec = await get(store, id);
  if (!rec) return;
  if (!rec._sync?.notionId) return tx(store, 'readwrite', (s) => req2p(s.delete(id)));
  return put(store, { ...rec, deleted: true });
}

export function purge(store, id) {
  return tx(store, 'readwrite', (s) => req2p(s.delete(id)));
}

export async function interactionsOf(entityId) {
  const rows = await tx('interactions', 'readonly', (s) => req2p(s.index('entityId').getAll(entityId)));
  return rows.filter((r) => !r.deleted);
}

export function getMeta(key) {
  return tx('meta', 'readonly', (s) => req2p(s.get(key)));
}

export function setMeta(key, value) {
  return tx('meta', 'readwrite', (s) => req2p(s.put(value, key)));
}

export async function exportAll() {
  const out = { version: 1, exportedAt: new Date().toISOString() };
  for (const name of STORES) out[name] = await all(name, { includeDeleted: true });
  return out;
}

export async function importAll(data, { replace = false } = {}) {
  for (const name of STORES) {
    if (!Array.isArray(data[name])) continue;
    if (replace) await tx(name, 'readwrite', (s) => req2p(s.clear()));
    await putMany(name, data[name]);
  }
}

export async function clearAll() {
  for (const name of [...STORES, 'meta']) await tx(name, 'readwrite', (s) => req2p(s.clear()));
}
