// 存储后端：一个仓库 = 一组按路径存放的文本文件（Markdown + YAML 头）。
// 三种实现共用同一接口：list() / read(path) / write(path, text) / remove(path)
//   IdbVault  浏览器内置（IndexedDB），默认；可导出为 Obsidian 仓库 zip
//   FsVault   桌面 Chrome/Edge 的本地文件夹（File System Access API），可直接选 Obsidian 仓库
//   CapVault  Android（Capacitor）手机「文档/脉络」文件夹

const DB_NAME = 'mailuo';
let dbPromise = null;

function db() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        req.result.createObjectStore('files');
        req.result.createObjectStore('kv');
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

function tx(store, mode, fn) {
  return db().then((d) => new Promise((resolve, reject) => {
    const t = d.transaction(store, mode);
    const s = t.objectStore(store);
    const out = fn(s);
    t.oncomplete = () => resolve(out && 'result' in out ? out.result : out);
    t.onerror = () => reject(t.error);
  }));
}

export const kv = {
  get: (k) => tx('kv', 'readonly', (s) => s.get(k)),
  set: (k, v) => tx('kv', 'readwrite', (s) => s.put(v, k)),
};

export class IdbVault {
  constructor() { this.kind = 'idb'; this.label = '浏览器内置存储'; }
  async ready() { return true; }
  list() { return tx('files', 'readonly', (s) => s.getAllKeys()); }
  read(path) { return tx('files', 'readonly', (s) => s.get(path)).then((v) => v ?? null); }
  write(path, text) { return tx('files', 'readwrite', (s) => s.put(text, path)); }
  remove(path) { return tx('files', 'readwrite', (s) => s.delete(path)); }
}

export class FsVault {
  constructor(handle) { this.kind = 'fs'; this.handle = handle; this.label = '本地文件夹：' + handle.name; }
  static supported() { return typeof window.showDirectoryPicker === 'function'; }
  static async pick() {
    const handle = await window.showDirectoryPicker({ id: 'mailuo', mode: 'readwrite' });
    await kv.set('fsHandle', handle);
    return new FsVault(handle);
  }
  static async restore() {
    const handle = await kv.get('fsHandle');
    return handle ? new FsVault(handle) : null;
  }
  async permitted() { return (await this.handle.queryPermission({ mode: 'readwrite' })) === 'granted'; }
  async ready() {
    if (await this.permitted()) return true;
    return (await this.handle.requestPermission({ mode: 'readwrite' })) === 'granted';
  }
  async dir(parts, create) {
    let d = this.handle;
    for (const p of parts) d = await d.getDirectoryHandle(p, { create });
    return d;
  }
  async list() {
    const out = [];
    const walk = async (d, prefix) => {
      for await (const [name, h] of d.entries()) {
        if (name === '.obsidian' || name === '.trash' || name === '.git') continue;
        if (h.kind === 'directory') await walk(h, prefix + name + '/');
        else if (name.endsWith('.md') || prefix.startsWith('.mailuo/')) out.push(prefix + name);
      }
    };
    await walk(this.handle, '');
    return out;
  }
  async read(path) {
    const parts = path.split('/');
    try {
      const d = await this.dir(parts.slice(0, -1), false);
      const f = await (await d.getFileHandle(parts.at(-1))).getFile();
      return await f.text();
    } catch { return null; }
  }
  async write(path, text) {
    const parts = path.split('/');
    const d = await this.dir(parts.slice(0, -1), true);
    const w = await (await d.getFileHandle(parts.at(-1), { create: true })).createWritable();
    await w.write(text);
    await w.close();
  }
  async remove(path) {
    const parts = path.split('/');
    try {
      const d = await this.dir(parts.slice(0, -1), false);
      await d.removeEntry(parts.at(-1));
    } catch { /* 已不存在 */ }
  }
}

export class CapVault {
  constructor(fs, root = '脉络') {
    this.kind = 'cap'; this.fs = fs; this.root = root;
    this.label = '手机「文档/' + root + '」文件夹';
  }
  static available() { return !!window.Capacitor?.isNativePlatform?.(); }
  // vendor/capacitor.js 提供 registerPlugin；原生端的 Filesystem 插件由它代理
  static plugin() { return window.Capacitor.Plugins?.Filesystem || window.Capacitor.registerPlugin('Filesystem'); }
  opts(path) { return { path: this.root + (path ? '/' + path : ''), directory: 'DOCUMENTS' }; }
  async ready() {
    try { await this.fs.requestPermissions?.(); } catch { /* Android 11+ 不需要 */ }
    try { await this.fs.mkdir({ ...this.opts(''), recursive: true }); } catch { /* 已存在 */ }
    return true;
  }
  async list() {
    const out = [];
    const walk = async (rel) => {
      let res;
      try { res = await this.fs.readdir(this.opts(rel)); } catch { return; }
      for (const f of res.files) {
        const name = typeof f === 'string' ? f : f.name;
        const isDir = typeof f === 'string' ? !name.includes('.') : f.type === 'directory';
        if (name === '.obsidian' || name === '.trash') continue;
        const p = rel ? rel + '/' + name : name;
        if (isDir) await walk(p);
        else if (name.endsWith('.md') || rel.startsWith('.mailuo')) out.push(p);
      }
    };
    await walk('');
    return out;
  }
  async read(path) {
    try { return (await this.fs.readFile({ ...this.opts(path), encoding: 'utf8' })).data; } catch { return null; }
  }
  async write(path, text) {
    await this.fs.writeFile({ ...this.opts(path), data: text, encoding: 'utf8', recursive: true });
  }
  async remove(path) {
    try { await this.fs.deleteFile(this.opts(path)); } catch { /* 已不存在 */ }
  }
}

export async function openVault() {
  if (CapVault.available()) return new CapVault(CapVault.plugin());
  if (localStorage.getItem('mailuo.vault') === 'fs' && FsVault.supported()) {
    const v = await FsVault.restore();
    if (v) return v;
  }
  return new IdbVault();
}

// 整个仓库打包成 zip（Obsidian 可直接解压打开）
export async function exportZip(vault) {
  const { zipSync, strToU8 } = await import('../vendor/fflate.mjs');
  const files = {};
  for (const p of await vault.list()) {
    const t = await vault.read(p);
    if (t != null) files[p] = strToU8(t);
  }
  return zipSync(files, { level: 6 });
}

export async function importZip(vault, buf) {
  const { unzipSync, strFromU8 } = await import('../vendor/fflate.mjs');
  const entries = unzipSync(new Uint8Array(buf));
  const names = Object.keys(entries).filter((n) => !n.endsWith('/'));
  // 压缩包外层若只有一个文件夹（如「脉络/」），去掉这层
  const tops = new Set(names.map((n) => n.split('/')[0]));
  const strip = tops.size === 1 && names.every((n) => n.includes('/')) ? [...tops][0].length + 1 : 0;
  let n = 0;
  for (const name of names) {
    const rel = name.slice(strip);
    if (rel.startsWith('.obsidian/') || !(rel.endsWith('.md') || rel.startsWith('.mailuo/'))) continue;
    await vault.write(rel, strFromU8(entries[name]));
    n++;
  }
  return n;
}
