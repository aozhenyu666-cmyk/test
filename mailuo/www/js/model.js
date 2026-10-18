// 知识库 V2：每个体系里是「问 → 论 → 规」的挂载链条（字段 under = 挂在哪个节点下面）。
// 一个节点 = 一个 Markdown 文件；推演槽位（支柱 / 盲区 / 验证哨 / 触发场景 / 代价）都在 YAML 头里。
// v1 的「层级」(parent) 仍会读写，界面改造见 docs/v2/tasks/T01。
// 文件布局（即一个 Obsidian 仓库）：
//   思维问题/思维问题.md               体系文件（kind: system，记录层级列表）
//   思维问题/加工层/第一反应停止.md     节点文件（层级 = 子文件夹）
//   .mailuo/review.json               温故进度（App 自用，Obsidian 忽略）
import { load as yamlLoad, CORE_SCHEMA } from '../vendor/js-yaml.mjs';

// V2 只有三种节点：问（痛点 / 课题）→ 论（诊断 / 假设）→ 规（行动 / 铁律）
export const TYPES = ['问', '论', '规'];
// v1 的五种类型读入时自动换成 V2；概念 / 案例 并入「论」，原类型记在 legacy_type
export const LEGACY_TYPES = { 问题: '问', 论断: '论', 规则: '规', 概念: '论', 案例: '论' };
export function normalizeType(t) {
  const s = String(t || '').trim();
  return TYPES.includes(s) ? s : LEGACY_TYPES[s] || '论';
}
// 每种节点卡片内部的推演槽位（AI 起草，你确认）
export const SLOTS = {
  问: [{ key: 'pillars', label: '破局支柱', hint: '决定这个问题成败的 2–3 个客观约束 / 规律', kind: 'list' }],
  论: [
    { key: 'blindspot', label: '反方盲区', hint: '这条论断在什么情况下会失效', kind: 'text' },
    { key: 'sentinels', label: '验证哨', hint: '看到什么具体现象就给把握加分 / 扣分', kind: 'sentinels' },
  ],
  规: [
    { key: 'triggers', label: '触发场景', hint: '在什么念头 / 情境下立刻调用这条规', kind: 'list' },
    { key: 'cost', label: '破戒代价', hint: '不按这条规做，最坏的连锁反应', kind: 'text' },
  ],
};
// 挂载规则：问 可挂在 问 下（子问题）；论 挂在 问 / 论 下；规 挂在 论 / 问 下
export const MOUNT_RULES = { 问: ['问'], 论: ['问', '论'], 规: ['论', '问'] };
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

const FIELD_ORDER = ['id', 'title', 'type', 'legacy_type', 'system', 'under', 'parent', 'keywords', 'status', 'confidence', 'verify',
  'pillars', 'blindspot', 'sentinels', 'triggers', 'cost', 'paused', 'source', 'links', 'checks', 'created', 'updated'];
const LIST_FIELDS = new Set(['keywords', 'pillars', 'triggers']);
const MAP_LIST_FIELDS = new Set(['links', 'checks', 'sentinels']);

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
    else if (LIST_FIELDS.has(k)) { if (v && v.length) lines.push(k + ': ' + flowList(v)); }
    else if (MAP_LIST_FIELDS.has(k)) {
      if (!v || !v.length) continue;
      lines.push(k + ':');
      for (const item of v) lines.push('  - ' + flowMap(item));
    } else if (k === 'paused') { if (v) lines.push('paused: true'); }
    else if (v === undefined || v === null || v === '') {
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

// 万一遇到 Date（例如旧版本写坏的文件），统一转回 YYYY-MM-DD
function fixDates(v) {
  if (v instanceof Date) return isNaN(v) ? '' : v.toISOString().slice(0, 10);
  if (typeof v === 'string' && /^[A-Z][a-z]{2} [A-Z][a-z]{2} \d{2} \d{4} /.test(v)) { const d = new Date(v); return isNaN(d) ? v : d.toISOString().slice(0, 10); }
  if (Array.isArray(v)) return v.map(fixDates);
  if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, fixDates(x)]));
  return v;
}

// 这个文件是否由脉络管理（节点文件或体系文件）
export function isMailuoFile(path, text) {
  if (path.startsWith('.mailuo/')) return true;
  const { meta } = splitFrontmatter(text);
  return meta.kind === 'system' || !!parseNode(text, path);
}

export function splitFrontmatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text || '');
  if (!m) return { meta: {}, body: text || '' };
  let meta = {};
  // CORE_SCHEMA：不把 2026-10-10 解析成 Date，日期保持字符串原样写回
  try { meta = fixDates(yamlLoad(m[1], { schema: CORE_SCHEMA }) || {}); } catch (e) { meta = { __error: String(e.message || e) }; }
  return { meta, body: m[2] };
}

export function parseNode(text, path) {
  const { meta, body } = splitFrontmatter(text);
  // 仓库里可能还有你自己的其他笔记：只有带 id 且带脉络字段（激活词 / 状态 / 关系）的才算节点
  if (!meta.id || meta.kind === 'system' || !('keywords' in meta || 'status' in meta || 'links' in meta)) return null;
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
  // ---- V2 字段 ----
  const t = String(n.type ?? '');
  n.type = normalizeType(t);
  if (!TYPES.includes(t) && t && !n.legacy_type && (t === '概念' || t === '案例')) n.legacy_type = t;
  n.under = n.under ? String(n.under) : '';
  const strList = (v) => (Array.isArray(v) ? v : v ? [v] : []).map((x) => String(x).trim()).filter(Boolean);
  n.pillars = strList(n.pillars);
  n.triggers = strList(n.triggers);
  n.blindspot = n.blindspot ? String(n.blindspot) : '';
  n.cost = n.cost ? String(n.cost) : '';
  n.paused = n.paused === true || n.paused === 'true';
  n.sentinels = normalizeSentinels(n.sentinels);
  // v1 的「验证方式」没有加减分：迁移成一条 delta 为 0 的验证哨（待你补上加减分）
  if (!n.sentinels.length && n.verify && n.type === '论') n.sentinels = [{ when: String(n.verify), delta: 0 }];
  n.claim = (paras[0] || '').trim();
  n.note = paras.slice(1).join('\n\n').trim();
  if (meta.__error) n.yamlError = meta.__error;
  n._savedTitle = n.title;
  n._savedSystem = n.system;
  return n;
}

export function normalizeSentinels(v) {
  return (Array.isArray(v) ? v : []).map((x) => {
    if (typeof x === 'string') return { when: x.trim(), delta: 0 };
    const d = parseInt(String(x?.delta ?? 0).replace(/[^-+\d]/g, ''), 10);
    return { when: String(x?.when || '').trim(), delta: Number.isFinite(d) ? Math.max(-50, Math.min(50, d)) : 0 };
  }).filter((x) => x.when);
}

// 补齐缺失字段（代码里直接构造的节点不一定字段齐全）；原地修改并返回
export function ensureShape(n) {
  const b = blankNode();
  for (const k of ['keywords', 'pillars', 'triggers', 'links', 'checks']) if (!Array.isArray(n[k])) n[k] = [];
  for (const k of ['under', 'parent', 'blindspot', 'cost', 'verify', 'claim', 'note']) if (n[k] == null) n[k] = b[k];
  n.type = normalizeType(n.type);
  n.sentinels = normalizeSentinels(n.sentinels);
  n.paused = !!n.paused;
  if (!n.extra) n.extra = {};
  return n;
}

// 新建节点的默认字段（各任务新建节点时都用它，保证字段齐全）
export function blankNode(over = {}) {
  return {
    id: '', title: '', type: '论', system: '', under: '', parent: '', keywords: [], status: '假设', confidence: 50, verify: '',
    pillars: [], blindspot: '', sentinels: [], triggers: [], cost: '', paused: false,
    source: '自己', claim: '', note: '', links: [], checks: [], extra: {}, ...over,
  };
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
    // 先读进新的表，读完再替换：重新读取期间界面不会看到空库
    const nodes = new Map(), systems = new Map();
    const paths = (await this.vault.list()).filter((p) => p.endsWith('.md'));
    // 分批并发读取（安卓文件接口每次调用都有往返开销）
    const texts = [];
    for (let i = 0; i < paths.length; i += 16) {
      texts.push(...await Promise.all(paths.slice(i, i + 16).map((p) => this.vault.read(p))));
    }
    paths.forEach((p, i) => {
      const text = texts[i];
      const { meta, body } = splitFrontmatter(text);
      if (meta.kind === 'system') {
        systems.set(String(meta.name), {
          name: String(meta.name), levels: (meta.levels || []).map(String),
          desc: body.split(/\n##/)[0].trim(), created: meta.created, path: p,
        });
        return;
      }
      const n = parseNode(text, p);
      if (n) nodes.set(n.id, n);
    });
    this.nodes = nodes;
    this.systems = systems;
    // 只有节点、没有体系文件的文件夹，也补成体系
    for (const n of this.nodes.values()) {
      const s = this.ensureSystemSync(n.system);
      if (n.parent) for (const lv of prefixes(n.parent)) if (!s.levels.includes(lv)) s.levels.push(lv);
    }
    try { this.review = JSON.parse(await this.vault.read('.mailuo/review.json') || '{}'); } catch { this.review = {}; }
    this.emit();
  }

  fingerprint() {
    return [...this.nodes.values()].map((n) => n.id + n.title + n.updated + n.links.length + n.claim.length).join('|') + '#' + this.systems.size;
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
    return [safeName(n.system), safeName(n.title) + '.md'].join('/');
  }

  async saveNode(n, { touch = true } = {}) {
    ensureShape(n);
    if (touch) n.updated = today();
    if (!n.created) n.created = today();
    const old = this.nodes.get(n.id);
    const oldTitle = n._savedTitle;
    const oldSystem = n._savedSystem;
    const oldPath = n.path;
    let path = this.filePath(n);
    if (path === safeName(n.system) + "/" + safeName(n.system) + ".md") path = path.replace(/\.md$/, " (" + n.id + ").md");
    // 同名文件（不同节点）时加 id 避免覆盖
    for (const o of this.nodes.values()) if (o.id !== n.id && o.path === path) path = path.replace(/\.md$/, ' (' + n.id + ').md');
    n.path = path;
    this.nodes.set(n.id, n);
    const s = this.ensureSystemSync(n.system);
    await this.vault.write(path, serializeNode(n, this));
    n._savedTitle = n.title;
    n._savedSystem = n.system;
    // 换了体系：旧体系文件里的目录也要去掉它
    if (oldSystem && oldSystem !== n.system) await this.saveSystem(this.systems.get(oldSystem));
    if (oldPath && oldPath !== path) await this.vault.remove(oldPath);
    // 改名后，指向它的节点的双链要跟着改
    if (oldTitle && oldTitle !== n.title) {
      for (const l of this.incoming(n.id)) {
        const src = this.nodes.get(l.from);
        if (src) await this.vault.write(src.path, serializeNode(src, this));
      }
    }
    await this.saveSystem(s);
    this.emit();
    return n;
  }

  // ---------- V2：问 → 论 → 规 挂载链条 ----------
  children(id) {
    const order = { 问: 0, 论: 1, 规: 2 };
    return [...this.nodes.values()].filter((n) => n.under === id)
      .sort((a, b) => order[a.type] - order[b.type] || (b.confidence ?? 0) - (a.confidence ?? 0) || a.title.localeCompare(b.title, 'zh'));
  }
  // 从近到远的上级链（防环）
  ancestors(id) {
    const out = [], seen = new Set([id]);
    let cur = this.nodes.get(id);
    while (cur && cur.under && !seen.has(cur.under) && this.nodes.has(cur.under)) {
      seen.add(cur.under);
      cur = this.nodes.get(cur.under);
      out.push(cur);
    }
    return out;
  }
  // 全部后代（广度优先，防环）
  descendants(id) {
    const out = [], seen = new Set([id]), queue = [id];
    while (queue.length) for (const c of this.children(queue.shift())) if (!seen.has(c.id)) { seen.add(c.id); out.push(c); queue.push(c.id); }
    return out;
  }
  // 链条视图的根：没挂在任何（存在的）节点下面的「问」
  chainRoots(system) {
    return this.list(system).filter((n) => n.type === '问' && !(n.under && this.nodes.has(n.under)));
  }
  // 还没挂载的「论 / 规」：链条视图里放在「未挂载」区
  unmounted(system) {
    return this.list(system).filter((n) => n.type !== '问' && !(n.under && this.nodes.has(n.under)));
  }
  // 能不能把 childId 挂到 parentId 下面；能挂返回 ''，否则返回原因
  canMount(childId, parentId) {
    const c = typeof childId === 'object' ? childId : this.nodes.get(childId);
    if (!c) return '节点不存在';
    childId = c.id;
    if (!parentId) return '';
    const p = this.nodes.get(parentId);
    if (!p) return '目标节点不存在';
    if (childId === parentId) return '不能挂在自己下面';
    if (!MOUNT_RULES[c.type].includes(p.type)) return '「' + c.type + '」只能挂在「' + MOUNT_RULES[c.type].join(' / ') + '」下面';
    if (childId && this.descendants(childId).some((d) => d.id === parentId)) return '不能挂到自己的下级下面（会成环）';
    return '';
  }
  async mount(childId, parentId) {
    const err = this.canMount(childId, parentId);
    if (err) throw new Error(err);
    const c = this.nodes.get(childId);
    c.under = parentId || '';
    await this.saveNode(c);
  }

  // 合并两个节点：保留 keepId，把 dropId 的激活词、结论、验证记录和关系都并过来，再删掉 dropId
  async mergeNodes(keepId, dropId) {
    const keep = this.nodes.get(keepId), drop = this.nodes.get(dropId);
    if (!keep || !drop || keepId === dropId) return;
    ensureShape(keep); ensureShape(drop);
    keep.keywords = [...new Set([...keep.keywords, ...drop.keywords])].slice(0, 5);
    if (drop.claim && drop.claim !== keep.claim && !keep.note.includes(drop.claim) && keep.note.split('\n').filter(Boolean).length < 3) {
      keep.note = (keep.note ? keep.note + '\n' : '') + '补充：' + drop.claim;
    }
    keep.checks = [...keep.checks, ...drop.checks];
    keep.pillars = [...new Set([...keep.pillars, ...drop.pillars])];
    keep.triggers = [...new Set([...keep.triggers, ...drop.triggers])];
    keep.sentinels = [...keep.sentinels, ...drop.sentinels.filter((s) => !keep.sentinels.some((k) => k.when === s.when))];
    if (!keep.blindspot) keep.blindspot = drop.blindspot;
    if (!keep.cost) keep.cost = drop.cost;
    if (!keep.under && drop.under && drop.under !== keepId) keep.under = drop.under;
    if (!keep.verify && drop.verify) keep.verify = drop.verify;
    for (const l of drop.links) {
      if (l.to === keepId || keep.links.some((x) => x.to === l.to && x.type === l.type)) continue;
      keep.links.push({ ...l });
    }
    // 指向被合并节点的关系 / 挂载改指向保留的节点
    const touched = new Set();
    for (const n of this.nodes.values()) {
      if (n.id === dropId) continue;
      let changed = false;
      if (n.under === dropId) { n.under = n.id !== keepId && MOUNT_RULES[n.type].includes(keep.type) ? keepId : ''; changed = true; }
      for (const l of n.links) if (l.to === dropId) { l.to = keepId; changed = true; }
      if (changed) {
        const seen = new Set();
        n.links = n.links.filter((l) => l.to !== n.id && !seen.has(l.type + l.to) && seen.add(l.type + l.to));
        touched.add(n);
      }
    }
    this.nodes.delete(dropId);
    await this.vault.remove(drop.path);
    delete this.review[dropId];
    await this.saveNode(keep);
    for (const n of touched) if (n !== keep) await this.vault.write(n.path, serializeNode(n, this));
    await this.saveSystem(this.systems.get(drop.system));
    this.emit();
  }

  async deleteNode(id) {
    const n = this.nodes.get(id);
    if (!n) return;
    this.nodes.delete(id);
    await this.vault.remove(n.path);
    const grand = this.nodes.get(n.under);
    for (const src of this.nodes.values()) {
      let changed = false;
      if (src.links.some((l) => l.to === id)) { src.links = src.links.filter((l) => l.to !== id); changed = true; }
      // 下级节点上移一层（挂到被删节点的上级；类型不允许就变成未挂载）
      if (src.under === id) { src.under = grand && MOUNT_RULES[src.type].includes(grand.type) ? grand.id : ''; changed = true; }
      if (changed) await this.vault.write(src.path, serializeNode(src, this));
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
    s.levels = [];
    const nodes = this.list(s.name);
    const lines = ['---', 'kind: system', 'name: ' + q(s.name), 'levels: ' + flowList(s.levels), 'created: ' + q(s.created || today()), '---'];
    lines.push(s.desc || '「' + s.name + '」知识体系。由脉络 App 维护，可在 Obsidian 中直接打开。');
    lines.push('', '## 结构');
    const seen = new Set();
    const render = (n, depth) => {
      if (seen.has(n.id)) return;
      seen.add(n.id);
      lines.push('  '.repeat(depth) + '- [' + n.type + '] [[' + n.title + ']]');
      for (const child of this.children(n.id)) if (child.system === s.name) render(child, depth + 1);
    };
    for (const n of this.chainRoots(s.name)) render(n, 0);
    if (nodes.some((n) => !seen.has(n.id))) {
      lines.push('', '## 未挂载');
      for (const n of nodes) if (!seen.has(n.id)) render(n, 0);
    }
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
    if (normalizeType(cand.type) !== n.type || cand.under === n.id) continue;
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

// 应用批阅通过的调整，返回副本，由调用方保存；驳回、待定和失效目标均不改库。
export function applyAdjust(store, proposal, { date = today() } = {}) {
  if (proposal.state !== 'accepted') return [];
  const target = store.nodes.get(proposal.target);
  if (!target || target.type === '问') return [];
  const requested = Number(proposal.to_final ?? proposal.to);
  if (!Number.isFinite(requested)) return [];
  const to = Math.max(0, Math.min(100, requested));
  const result = [{ ...target, confidence: to, checks: [...target.checks, {
    date, kind: '调整', source: '录入', from: target.confidence ?? 50, to, result: proposal.reason || '',
  }] }];
  const descendants = new Set(store.descendants(target.id).filter((n) => n.type === '规').map((n) => n.id));
  for (const id of new Set(proposal.pause_selected ?? proposal.pause_rules ?? [])) {
    const n = store.nodes.get(id);
    if (!n || n.paused || !descendants.has(id)) continue;
    result.push({ ...n, paused: true, checks: [...n.checks, {
      date, kind: '暂停', source: '录入', result: '上游【' + target.title + '】降到 ' + to + '%',
    }] });
  }
  return result;
}
