// 三个新增的零 token 模块：
//   体检（health）：检查库里哪些地方会把「好听的故事」当成规则
//   溯源（trace）：沿「导致」找根因与后果，沿「前置」排学习顺序
//   温故（review）：间隔复习，看标题和激活词回想结论
import { REL_TYPES, today } from './model.js';

const DAY = 86400000;
const daysSince = (d) => (d ? Math.floor((Date.now() - new Date(d).getTime()) / DAY) : 999);

// ---------- 体检 ----------
export const HEALTH_RULES = [
  { key: 'noVerify', level: 'high', label: '假设没写验证方式', test: (n) => n.status === '假设' && !String(n.verify || '').trim() },
  { key: 'overconfident', level: 'high', label: '假设却把握 ≥ 85%：可能把故事当规则', test: (n) => n.status === '假设' && (n.confidence ?? 0) >= 85 },
  { key: 'weakVerified', level: 'mid', label: '已验证但把握 < 50%', test: (n) => n.status === '已验证' && n.confidence != null && n.confidence < 50 },
  { key: 'stale', level: 'mid', label: '假设超过 14 天没回填', test: (n) => n.status === '假设' && daysSince(n.checks.at(-1)?.date || n.created) > 14 },
  { key: 'badRel', level: 'high', label: '关系类型不在 7 种之内', test: (n) => n.links.some((l) => !REL_TYPES.includes(l.type)) },
  { key: 'broken', level: 'high', label: '关系指向的节点不存在', test: (n, s) => n.links.some((l) => !s.nodes.has(l.to)) },
  { key: 'orphan', level: 'low', label: '孤立节点：没有任何关系', test: (n, s) => !n.links.length && !s.incoming(n.id).length },
  { key: 'kwCount', level: 'low', label: '激活词不是 3–5 个', test: (n) => n.keywords.length < 3 || n.keywords.length > 5 },
  { key: 'long', level: 'low', label: '正文超过「一句结论 + 3 行说明」', test: (n) => n.note.split('\n').filter((l) => l.trim()).length > 3 || n.claim.length > 80 },
  { key: 'noWhy', level: 'low', label: '有关系没写理由', test: (n) => n.links.some((l) => !String(l.why || '').trim()) },
];
const WEIGHT = { high: 6, mid: 3, low: 1 };

export function healthCheck(store, system) {
  const nodes = store.list(system);
  const issues = [];
  for (const rule of HEALTH_RULES) {
    const hit = nodes.filter((n) => rule.test(n, store));
    if (hit.length) issues.push({ ...rule, nodes: hit });
  }
  // 对立对：双方都没有验证方式 → 分不开
  const pairs = [];
  for (const n of nodes) for (const l of n.links) {
    const o = store.nodes.get(l.to);
    if (l.type === '对立' && o && !(n.verify && o.verify)) pairs.push(n, o);
  }
  if (pairs.length) issues.push({ key: 'rivalNoTest', level: 'high', label: '对立的两个解释缺少区分方法（至少一方没写验证方式）', nodes: [...new Set(pairs)] });
  const penalty = issues.reduce((s, i) => s + WEIGHT[i.level] * i.nodes.length, 0);
  // 每个节点平均扣 10 分即为 0 分
  const score = nodes.length ? Math.max(0, Math.round(100 - (penalty * 10) / nodes.length)) : 100;
  issues.sort((a, b) => WEIGHT[b.level] - WEIGHT[a.level]);
  return { score, issues, total: nodes.length };
}

// ---------- 溯源 ----------
// 返回 {up: 树, down: 树, roots: [节点]}；树节点 {node, why, children}
export function traceCause(store, id, depth = 4) {
  const walk = (cur, dir, d, seen) => {
    if (d >= depth) return [];
    const next = dir === 'up'
      ? store.incoming(cur).filter((l) => l.type === '导致').map((l) => ({ id: l.from, why: l.why }))
      : (store.nodes.get(cur)?.links || []).filter((l) => l.type === '导致').map((l) => ({ id: l.to, why: l.why }));
    return next.filter((x) => store.nodes.has(x.id) && !seen.has(x.id)).map((x) => {
      const s2 = new Set(seen).add(x.id);
      return { node: store.nodes.get(x.id), why: x.why, children: walk(x.id, dir, d + 1, s2) };
    });
  };
  const up = walk(id, 'up', 0, new Set([id]));
  const down = walk(id, 'down', 0, new Set([id]));
  const roots = [];
  const collect = (t) => { for (const c of t) { if (!c.children.length) roots.push(c.node); collect(c.children); } };
  collect(up);
  return { up, down, roots: [...new Map(roots.map((r) => [r.id, r])).values()] };
}

// 最长的一条上游链（用于打包时附上「因果链」）
export function longestChain(tree, self) {
  let best = [];
  const dfs = (t, path) => {
    if (!t.length && path.length > best.length) best = path;
    for (const c of t) dfs(c.children, [c.node.title, ...path]);
  };
  dfs(tree, [self]);
  return best;
}

// 学习路线：本节点的全部前置（递归），按拓扑顺序从最先学的开始
export function learningPath(store, id) {
  const order = [], state = new Map();
  const visit = (cur) => {
    if (state.get(cur) === 2 || state.get(cur) === 1) return; // 1 = 正在访问（环），跳过
    state.set(cur, 1);
    for (const l of store.incoming(cur)) if (l.type === '前置') visit(l.from);
    state.set(cur, 2);
    order.push(cur);
  };
  visit(id);
  return order.map((x) => store.nodes.get(x)).filter(Boolean);
}

// ---------- 温故 ----------
const STEPS = [1, 3, 7, 15, 30, 60];
export function dueReviews(store, limit = 10) {
  const t = today();
  const all = [...store.nodes.values()].filter((n) => n.status !== '已推翻');
  const due = all.filter((n) => {
    const r = store.review[n.id];
    return !r || r.due <= t;
  });
  // 假设优先，然后是从没复习过的，然后按到期日
  due.sort((a, b) => {
    const ra = store.review[a.id], rb = store.review[b.id];
    return (a.status === '假设' ? 0 : 1) - (b.status === '假设' ? 0 : 1) || (ra ? 1 : 0) - (rb ? 1 : 0) || String(ra?.due).localeCompare(String(rb?.due));
  });
  return due.slice(0, limit);
}

export async function gradeReview(store, id, grade) {
  const r = store.review[id] || { step: -1 };
  const step = grade === 'good' ? Math.min(r.step + 1, STEPS.length - 1) : grade === 'fuzzy' ? Math.max(r.step, 0) : 0;
  const days = grade === 'forgot' ? 1 : STEPS[step];
  const due = new Date(Date.now() + days * DAY).toISOString().slice(0, 10);
  store.review[id] = { step, due, last: today(), n: (r.n || 0) + 1 };
  await store.saveReview();
  return days;
}
