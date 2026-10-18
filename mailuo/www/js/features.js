// 三个新增的零 token 模块：
//   体检（health）：检查库里哪些地方会把「好听的故事」当成规则
//   溯源（trace）：沿「导致」找根因与后果，沿「前置」排学习顺序
//   温故（review）：间隔复习，看标题和激活词回想结论
import { today, titleSim, sharedKeywords } from './model.js';

const DAY = 86400000;

// ---------- 体检 ----------
const sinceDate = (date, days) => new Date(Date.parse(date + 'T00:00:00Z') - days * DAY).toISOString().slice(0,10);
const recentChecks = (n, date) => (n.checks || []).filter((c) => c.date >= sinceDate(date,7) && c.date <= date);
const duplicatePair = (a,b,store) => {
  if (a.type !== b.type || store.ancestors(a.id).some((n) => n.id === b.id) || store.ancestors(b.id).some((n) => n.id === a.id)) return false;
  if ([...a.links.filter((l) => l.to === b.id), ...b.links.filter((l) => l.to === a.id)].some((l) => ['对立','类似'].includes(l.type))) return false;
  const sim=titleSim(a.title,b.title), kw=sharedKeywords(a.keywords,b.keywords).length;
  return sim >= .6 || (sim >= .45 && kw >= 1) || (a.system === b.system && kw >= 3);
};

export const HEALTH_RULES = [
  {key:'undecided',level:'high',label:'议而不决',test:(n,s) => (n.type === '问' || (n.type === '论' && n.confidence >= 75)) && !s.descendants(n.id).some((d) => d.type === '规' && !d.paused)},
  {key:'orphanRule',level:'high',label:'师出无名',test:(n,s) => n.type === '规' && (!n.under || !s.nodes.has(n.under))},
  {key:'inverted',level:'high',label:'地基倒挂',test:(n,s) => n.type === '规' && n.confidence > 85 && (s.ancestors(n.id).find((a) => a.type === '论')?.confidence ?? 100) < 70,
    detail:(n,s) => '上游只有 ' + s.ancestors(n.id).find((a) => a.type === '论')?.confidence + '% 把握，规却有 ' + n.confidence + '%'},
  {key:'noSentinel',level:'mid',label:'盲信无哨',test:(n) => n.type === '论' && (n.confidence ?? 50) < 90 && !n.sentinels.some((s) => s.delta !== 0),
    detail:(n) => n.sentinels.length ? '验证哨没有加减分' : '还没有验证哨'},
  {key:'stale',level:'mid',label:'脱离实操',test:(n,s,{today:date}) => n.type === '规' &&
    (recentChecks(n,date).filter((c) => c.kind === '卡壳').length >= 2 || (!n.paused && n.confidence >= 80 && !!n.created && n.created < sinceDate(date,7) && !recentChecks(n,date).some((c) => ['有效','卡壳','回填'].includes(c.kind)))),
    detail:() => '这条规是不是太难执行？建议降级或拆分'},
  {key:'broken',level:'high',label:'断链',test:(n,s) => !!(n.under && !s.nodes.has(n.under)) || n.links.some((l) => !s.nodes.has(l.to))},
  {key:'dupe',level:'low',label:'可能重复',test:(n,s,ctx={}) => (ctx.nodes || s.list()).some((o) => o.id !== n.id && duplicatePair(n,o,s))},
  {key:'kwCount',level:'low',label:'激活词不足',test:(n) => n.keywords.length < 3 || n.keywords.length > 5},
];
const WEIGHT = {high:6,mid:3,low:1};

export function healthCheck(store, system, {today:date=today()} = {}) {
  const nodes=store.list(system), issues=[];
  for (const rule of HEALTH_RULES) {
    const hit=nodes.filter((n) => rule.test(n,store,{today:date,nodes}));
    if (!hit.length) continue;
    const issue={...rule,nodes:hit};
    if (rule.key === 'dupe') {
      issue.pairs=[];
      for (let i=0;i<nodes.length;i++) for (let j=i+1;j<nodes.length;j++) if (duplicatePair(nodes[i],nodes[j],store)) issue.pairs.push([nodes[i],nodes[j]]);
      issue.pairs=issue.pairs.slice(0,10);
    }
    issues.push(issue);
  }
  issues.sort((a,b) => WEIGHT[b.level]-WEIGHT[a.level]);
  const penalty=issues.reduce((sum,i) => sum+WEIGHT[i.level]*i.nodes.length,0);
  return {score:nodes.length ? Math.max(0,Math.round(100-penalty*10/nodes.length)) : 100,issues,total:nodes.length};
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
export const FEEDBACK = { 有效: {rule:3,parent:2}, 卡壳: {rule:-5,parent:-3}, max:95, min:5 };

export function applyFeedback(store, ruleId, kind, {today:date=today(),source='温故',note=''} = {}) {
  const rule=store.nodes.get(ruleId), feedback=FEEDBACK[kind];
  if (!['有效','卡壳'].includes(kind) || !rule || rule.type !== '规' || rule.paused) return [];
  const change=(n,delta,result) => {
    const before=n.confidence ?? 50;
    const confidence=Math.max(FEEDBACK.min,Math.min(FEEDBACK.max,before+delta));
    return {...n,confidence,checks:[...n.checks,{date,kind,source,delta:confidence-before,confidence,result}]};
  };
  const out=[change(rule,feedback.rule,note||'温故：'+kind)];
  const parent=store.ancestors(ruleId).find((n) => n.type === '论');
  if (parent) out.push(change(parent,feedback.parent,'下游【'+rule.title+'】实操'+kind));
  return out;
}

export function dueReviews(store, limit=10, {today:date=today()} = {}) {
  const due=store.list().filter((n) => n.status !== '已推翻' &&
    ((n.type === '规' && !n.paused) || (n.type === '论' && n.sentinels.length)) &&
    (!store.review[n.id] || store.review[n.id].due <= date));
  due.sort((a,b) => (a.type === '规' ? 0 : 1)-(b.type === '规' ? 0 : 1) ||
    (store.review[a.id] ? 1 : 0)-(store.review[b.id] ? 1 : 0) ||
    String(store.review[a.id]?.due || '').localeCompare(String(store.review[b.id]?.due || '')) || a.id.localeCompare(b.id));
  return due.slice(0,limit);
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

// ---------- 本周回顾（不耗 token） ----------
export function weeklyDigest(store, usage, asks) {
  const since = new Date(Date.now() - 6 * DAY).toISOString().slice(0, 10);
  const nodes = store.list();
  const created = nodes.filter((n) => String(n.created || '') >= since);
  const checks = nodes.flatMap((n) => n.checks.filter((c) => String(c.date) >= since).map((c) => ({ n, c })));
  let sent = 0, calls = 0;
  for (const [d, v] of Object.entries(usage || {})) if (d >= since) for (const r of Object.values(v)) { sent += r.sent || 0; calls += r.n || 0; }
  const askN = Object.values(asks || {}).flat().filter((a) => String(a.date) >= since).length;
  return {
    created: created.length, checks: checks.length,
    verified: checks.filter((x) => x.c.status === '已验证').length,
    overturned: checks.filter((x) => x.c.status === '已推翻').length,
    sent, calls, asks: askN,
  };
}
