// 知识库：体系 → 层级（最多 3 层）→ 节点。一个节点 = 一个 Markdown 文件。
// 文件布局（即一个 Obsidian 仓库）：
//   思维问题/思维问题.md               体系文件（kind: system，记录层级列表）
//   思维问题/加工层/第一反应停止.md     节点文件（层级 = 子文件夹）
//   .mailuo/review.json               温故进度（App 自用，Obsidian 忽略）
import { load as yamlLoad } from '../vendor/js-yaml.mjs';

export const TYPES = ['概念', '论断', '规则', '问题', '案例'];
export const STATUSES = ['假设', '已验证', '已推翻'];
export const REL_TYPES = ['属于', '导致', '支撑', '反例', '对立', '前置', '类似'];
export const SYMMETRIC = new Set(['对立', '类似']);
export const REL_HINT = {
  属于: 'A 是 B 的一部分', 导致: 'A 引起 B', 支撑: 'A 是 B 的证据', 反例: 'A 削弱 B',
  对立: 'A 与 B 互相竞争', 前置: '做 B 之前要先有 A', 类似: 'A 与 B 结构相像',
};
// 关系视图里的分组：方向 out = 本节点是起点，in = 本节点是终点
export const REL_VIEW = [
  { key: 'up', label: '上游', hint: '导致它的', match: (l, dir) => l.type === '导致' && dir === 'in' },
  { key: 'down', label: '下游', hint: '它导致的', match: (l, dir) => l.type === '导致' && dir === 'out' },
  { key: 'support', label: '支撑', hint: '证据', match: (l) => l.type === '支撑' },
  { key: 'counter', label: '反例', hint: '削弱', match: (l) => l.type === '反例' },
  { key: 'rival', label: '对立', hint: '竞争解释', match: (l) => l.type === '对立' },
  { key: 'pre', label: '前置', hint: '学习顺序', match: (l) => l.type === '前置' },
  { key: 'like', label: '类似', hint: '可类比', match: (l) => l.type === '类似' },
  { key: 'part', label: '属于', hint: '组成', match: (l) => l.type === '属于' },
];

const FIELD_ORDER = ['id', 'title', 'type', 'system', 'parent', 'keywords', 'status', 'confidence', 'verify', 'source', 'links', 'checks', 'created', 'updated'];

export const today = () => new Date().toISOString().slice(0, 10);

// ---------- YAML 输出（固定字段顺序，关系写成一行一个 flow map，与 Obsidian Properties 兼容） ----------
function q(v) {
  if (v == null) return '""';
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  const s = String(v);
  if (s === '' || /^[\s\-?:,[\]{}#&*!|>'"%@`]/.test(s) || /[:#,[\]{}"\n]|\s$/.test(s) ||
      /^(true|false|null|yes|no|~|[-+]?[\d.]+)$/i.test(s)) return JSON.stringify(s);
  return s;
}
const flowList = (a) => '[' + (a || []).map(q).join(', ') + ']';
const flowMap = (o) => '{' + Object.entries(o).filter(([, v]) => v !== undefined && v !== '').map(([k, v]) => k + ': ' + q(v)).join(', ') + '}';

export function serializeNode(n, store) {
  const lines = ['---'];
  const extra = n.extra || {};
  for (const k of FIELD_ORDER) {
    const v = n[k];
    if (k === 'keywords') lines.push('keywords: ' + flowList(v));
    else if (k === 'links' || k === 'checks') {
      if (!v || !v.length) continue;
      lines.push(k + ':');
      for (const item of v) lines.push('  - ' + flowMap(item));
    } else if (v === undefined || v === null || v === '') {
      if (k === 'verify' && n.status === '假设') lines.push('verify: ""');
    } else lines.push(k + ': ' + q(v));
  }
  for (const [k, v] of Object.entries(extra)) lines.push(k + ': ' + JSON.stringify(v));
  lines.push('---');
  lines.push(n.claim || '');
  if (n.note) lines.push('', n.note);
  const rels = (n.links || []).map((l) => {
    const t = store?.nodes.get(l.to);
    return '- ' + l.type + ' [[' + (t ? t.title : l.to) + ']]' + (l.why ? ' — ' + l.why : '');
  });
  if (rels.length) lines.push('', '## 关系', ...rels);
  return lines.join('\n') + '\n';
}

export function splitFrontmatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text || '');
  if (!m) return { meta: {}, body: text || '' };
  let meta = {};
  try { meta = yamlLoad(m[1]) || {}; } catch (e) { meta = { __error: String(e.message || e) }; }
  return { meta, body: m[2] };
}

export function parseNode(text, path) {
  const { meta, body } = splitFrontmatter(text);
  if (!meta.id || meta.kind === 'system') return null;
  const main = body.split(/\n##\s*关系\s*\n/)[0].trim();
  const paras = main.split(/\n\s*\n/);
  const n = { path, extra: {} };
  for (const [k, v] of Object.entries(meta)) {
    if (FIELD_ORDER.includes(k)) n[k] = v; else if (k !== '__error') n.extra[k] = v;
  }
  n.id = String(n.id);
  n.title = String(n.title ?? path.split('/').at(-1).replace(/\.md$/, ''));
  n.keywords = Array.isArray(n.keywords) ? n.keywords.map(String) : String(n.keywords || '').split(/[,，、]\s*/).filter(Boolean);
  n.links = Array.isArray(n.links) ? n.links.filter((l) => l && l.to).map((l) => ({ type: String(l.type || ''), to: String(l.to), why: l.why ? String(l.why) : '' })) : [];
  n.checks = Array.isArray(n.checks) ? n.checks : [];
  n.confidence = n.confidence == null || n.confidence === '' ? null : parseInt(String(n.confidence), 10);
  n.parent = n.parent ? String(n.parent) : '';
  n.system = n.system ? String(n.system) : path.split('/')[0];
  n.claim = (paras[0] || '').trim();
  n.note = paras.slice(1).join('\n\n').trim();
  if (meta.__error) n.yamlError = meta.__error;
  n._savedTitle = n.title;
  return n;
}

export const safeName = (s) => String(s).replace(/[\\/:*?"<>|#^[\]]/g, '·').replace(/\s+/g, ' ').trim().slice(0, 60) || '未命名';

// ---------- 知识库（内存索引 + 写回仓库） ----------
export class Store {
  constructor(vault) {
    this.vault = vault;
    this.nodes = new Map();
    this.systems = new Map(); // name -> {name, levels: [], desc, created}
    this.review = {};
    this.listeners = new Set();
  }
  on(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit() { for (const fn of this.listeners) fn(); }

  async load() {
    this.nodes.clear(); this.systems.clear();
    const paths = await this.vault.list();
    for (const p of paths) {
      if (!p.endsWith('.md')) continue;
      const text = await this.vault.read(p);
      const { meta, body } = splitFrontmatter(text);
      if (meta.kind === 'system') {
        this.systems.set(String(meta.name), {
          name: String(meta.name), levels: (meta.levels || []).map(String),
          desc: body.split(/\n##/)[0].trim(), created: meta.created, path: p,
        });
        continue;
      }
      const n = parseNode(text, p);
      if (n) this.nodes.set(n.id, n);
    }
    // 只有节点、没有体系文件的文件夹，也补成体系
    for (const n of this.nodes.values()) {
      const s = this.ensureSystemSync(n.system);
      if (n.parent) for (const lv of prefixes(n.parent)) if (!s.levels.includes(lv)) s.levels.push(lv);
    }
    try { this.review = JSON.parse(await this.vault.read('.mailuo/review.json') || '{}'); } catch { this.review = {}; }
    this.emit();
  }

  ensureSystemSync(name) {
    if (!this.systems.has(name)) this.systems.set(name, { name, levels: [], desc: '', created: today(), path: name + '/' + name + '.md' });
    return this.systems.get(name);
  }

  // ---- 查询 ----
  list(system) { return [...this.nodes.values()].filter((n) => !system || n.system === system); }
  byTitle(title, system) {
    const t = String(title).trim();
    return this.list(system).find((n) => n.title === t) || this.list().find((n) => n.title === t);
  }
  incoming(id) {
    const out = [];
    for (const n of this.nodes.values()) for (const l of n.links) if (l.to === id) out.push({ ...l, from: n.id });
    return out;
  }
  // 本节点的全部关系（含方向）：[{dir, type, why, other}]
  relations(id) {
    const n = this.nodes.get(id);
    if (!n) return [];
    const rels = n.links.map((l) => ({ dir: 'out', type: l.type, why: l.why, other: this.nodes.get(l.to), otherId: l.to }));
    for (const l of this.incoming(id)) {
      // 对立 / 类似是对称关系：双方都写了时只算一条
      if (SYMMETRIC.has(l.type) && n.links.some((o) => o.to === l.from && o.type === l.type)) continue;
      rels.push({ dir: 'in', type: l.type, why: l.why, other: this.nodes.get(l.from), otherId: l.from });
    }
    return rels;
  }
  pathOf(n) { return [n.system, ...(n.parent ? n.parent.split('/') : []), n.title]; }
  nextId() {
    let max = 0;
    for (const id of this.nodes.keys()) { const m = /^n-(\d+)$/.exec(id); if (m) max = Math.max(max, +m[1]); }
    return 'n-' + String(max + 1).padStart(4, '0');
  }

  // ---- 写入 ----
  filePath(n) {
    return [safeName(n.system), ...(n.parent ? n.parent.split('/').map(safeName) : []), safeName(n.title) + '.md'].join('/');
  }

  async saveNode(n, { touch = true } = {}) {
    if (touch) n.updated = today();
    if (!n.created) n.created = today();
    const old = this.nodes.get(n.id);
    const oldTitle = n._savedTitle;
    const oldPath = n.path;
    let path = this.filePath(n);
    // 同名文件（不同节点）时加 id 避免覆盖
    for (const o of this.nodes.values()) if (o.id !== n.id && o.path === path) path = path.replace(/\.md$/, ' (' + n.id + ').md');
    n.path = path;
    this.nodes.set(n.id, n);
    const s = this.ensureSystemSync(n.system);
    let sysDirty = !(await this.vault.read(s.path));
    if (n.parent) for (const lv of prefixes(n.parent)) if (!s.levels.includes(lv)) { s.levels.push(lv); sysDirty = true; }
    await this.vault.write(path, serializeNode(n, this));
    n._savedTitle = n.title;
    if (oldPath && oldPath !== path) await this.vault.remove(oldPath);
    // 改名后，指向它的节点的双链要跟着改
    if (oldTitle && oldTitle !== n.title) {
      for (const l of this.incoming(n.id)) {
        const src = this.nodes.get(l.from);
        if (src) await this.vault.write(src.path, serializeNode(src, this));
      }
      sysDirty = true;
    }
    if (sysDirty || !old) await this.saveSystem(s);
    this.emit();
    return n;
  }

  async deleteNode(id) {
    const n = this.nodes.get(id);
    if (!n) return;
    this.nodes.delete(id);
    await this.vault.remove(n.path);
    for (const src of this.nodes.values()) {
      if (src.links.some((l) => l.to === id)) {
        src.links = src.links.filter((l) => l.to !== id);
        await this.vault.write(src.path, serializeNode(src, this));
      }
    }
    await this.saveSystem(this.systems.get(n.system));
    this.emit();
  }

  async addLink(fromId, link) {
    const n = this.nodes.get(fromId);
    if (!n || !REL_TYPES.includes(link.type) || link.to === fromId) return false;
    if (n.links.some((l) => l.to === link.to && l.type === link.type)) return false;
    n.links.push({ type: link.type, to: link.to, why: link.why || '' });
    await this.saveNode(n);
    return true;
  }

  async removeLink(fromId, to, type) {
    const n = this.nodes.get(fromId);
    if (!n) return;
    n.links = n.links.filter((l) => !(l.to === to && l.type === type));
    await this.saveNode(n);
  }

  async saveSystem(s) {
    if (!s) return;
    s.levels = [...new Set(s.levels)];
    const nodes = this.list(s.name);
    const lines = ['---', 'kind: system', 'name: ' + q(s.name), 'levels: ' + flowList(s.levels), 'created: ' + q(s.created || today()), '---'];
    lines.push(s.desc || '「' + s.name + '」知识体系。由脉络 App 维护，可在 Obsidian 中直接打开。');
    lines.push('', '## 结构');
    const render = (parent, depth) => {
      for (const n of nodes.filter((x) => x.parent === parent)) lines.push('  '.repeat(depth) + '- [[' + n.title + ']]');
      for (const lv of s.levels.filter((l) => parentOf(l) === parent)) {
        lines.push('  '.repeat(depth) + '- ' + lv.split('/').at(-1));
        render(lv, depth + 1);
      }
    };
    render('', 0);
    s.path = safeName(s.name) + '/' + safeName(s.name) + '.md';
    await this.vault.write(s.path, lines.join('\n') + '\n');
  }

  async addSystem(name) {
    name = name.trim();
    if (!name) return null;
    const s = this.ensureSystemSync(name);
    await this.saveSystem(s);
    this.emit();
    return s;
  }

  async addLevel(system, path) {
    const s = this.ensureSystemSync(system);
    if (path.split('/').length > 3) throw new Error('层级最多 3 层');
    for (const lv of prefixes(path)) if (!s.levels.includes(lv)) s.levels.push(lv);
    await this.saveSystem(s);
    this.emit();
  }

  async removeLevel(system, path) {
    const s = this.systems.get(system);
    if (!s) return;
    if (this.list(system).some((n) => n.parent === path || n.parent.startsWith(path + '/'))) throw new Error('层级下还有节点，先移走再删');
    s.levels = s.levels.filter((l) => l !== path && !l.startsWith(path + '/'));
    await this.saveSystem(s);
    this.emit();
  }

  async moveNode(id, system, parent) {
    const n = this.nodes.get(id);
    if (!n) return;
    n.system = system; n.parent = parent || '';
    await this.saveNode(n);
  }

  async saveReview() {
    await this.vault.write('.mailuo/review.json', JSON.stringify(this.review, null, 1));
  }
}

export function parentOf(level) { const i = level.lastIndexOf('/'); return i < 0 ? '' : level.slice(0, i); }
export function prefixes(level) {
  const parts = level.split('/');
  return parts.map((_, i) => parts.slice(0, i + 1).join('/'));
}

// ---------- 相似度：查重 & 类比 ----------
function bigrams(s) {
  const t = String(s).replace(/\s+/g, '').toLowerCase();
  const out = new Set();
  if (t.length < 2) { if (t) out.add(t); return out; }
  for (let i = 0; i < t.length - 1; i++) out.add(t.slice(i, i + 2));
  return out;
}
export function titleSim(a, b) {
  const A = bigrams(a), B = bigrams(b);
  if (!A.size || !B.size) return 0;
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  return (2 * inter) / (A.size + B.size);
}
export function sharedKeywords(a, b) {
  const B = new Set((b || []).map((k) => k.toLowerCase()));
  return (a || []).filter((k) => B.has(k.toLowerCase()));
}

// 与已有节点比较，找出最可能重复的一个
export function findDuplicate(cand, store) {
  let best = null;
  for (const n of store.nodes.values()) {
    const sim = titleSim(cand.title, n.title);
    const kw = sharedKeywords(cand.keywords, n.keywords);
    const contains = cand.title && n.title && (cand.title.includes(n.title) || n.title.includes(cand.title));
    const score = Math.max(sim, contains ? 0.8 : 0) + kw.length * 0.2;
    if ((sim >= 0.5 || contains || kw.length >= 2) && (!best || score > best.score)) {
      best = { node: n, score, sim, kw, reason: kw.length >= 2 ? '激活词重合 ' + kw.length + ' 个：' + kw.join('、') : '标题相近' };
    }
  }
  return best;
}

// 类比候选：规则 1 激活词重合 ≥ 2；规则 2 关系形状相同（同类关系指向同一节点或同一层级的结果）
export function findAnalogies(id, store, limit = 5) {
  const n = store.nodes.get(id);
  if (!n) return [];
  const linked = new Set([...n.links.map((l) => l.to), ...store.incoming(id).map((l) => l.from)]);
  const sig = (node) => {
    const out = new Map();
    for (const l of node.links) {
      const t = store.nodes.get(l.to);
      if (!t) continue;
      out.set(l.type + '→' + l.to, '都「' + l.type + '」指向「' + t.title + '」');
      out.set(l.type + '→@' + t.system + '/' + t.parent, '都「' + l.type + '」指向「' + (t.parent ? t.parent.split('/').at(-1) : t.system) + '」类结果');
    }
    return out;
  };
  const mine = sig(n);
  const res = [];
  for (const o of store.nodes.values()) {
    if (o.id === id || linked.has(o.id)) continue;
    const reasons = [];
    const kw = sharedKeywords(n.keywords, o.keywords);
    if (kw.length >= 2) reasons.push('激活词重合：' + kw.join('、'));
    const theirs = sig(o);
    for (const [k, why] of mine) if (theirs.has(k)) { reasons.push('关系形状相同：' + why); break; }
    if (reasons.length) res.push({ node: o, reasons, score: kw.length + (reasons.length > 1 ? 2 : 1) });
  }
  return res.sort((a, b) => b.score - a.score).slice(0, limit);
}
