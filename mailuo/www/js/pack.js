// 上下文打包：只发相关子结构（路径 + 节点 + 一跳邻居 + 激活词），不发整库。
// 超出预算时依次：① 砍邻居说明（只留标题和关系）② 砍远层路径 ③ 去掉低优先级邻居 ④ 激活词只留本节点的。
// 节点本身和问题永不砍。
import { estimateTokens } from './llm.js';
import { findAnalogies } from './model.js';

// 邻居优先级：数字越小越重要，越晚被砍（上级 / 下级规是 V2 挂载链条里的邻居）
const PRIORITY = { 对立: 1, 上级: 1.5, 'in导致': 2, 下级规: 2.5, 反例: 3, 支撑: 4, 下级: 4.5, 'out导致': 5, 前置: 6, 类似: 7, 属于: 8 };

// 挂载链路径：问 › 论 › 规；没挂载的节点退回「体系 › 标题」
export function chainPath(store, n) {
  const chain = [...store.ancestors(n.id).reverse(), n].map((x) => x.title);
  return chain.length > 1 ? chain : [n.system, n.title];
}

const signed = (d) => (d > 0 ? '+' : d < 0 ? '−' : '±') + Math.abs(d);

// 节点正文：按类型带上推演槽位
export function nodeText(n) {
  const meta = [n.type, n.type === '论' ? n.status : '', n.confidence != null && n.type !== '问' ? '把握' + n.confidence + '%' : '', n.paused ? '暂停中' : '']
    .filter(Boolean).join('｜');
  const lines = ['【节点】' + n.title + '（' + meta + '）', (n.type === '规' ? '动作：' : '') + n.claim];
  if (n.note) lines.push(n.note);
  if (n.type === '问' && n.pillars.length) lines.push('破局支柱：' + n.pillars.join('；'));
  if (n.type === '论') {
    if (n.blindspot) lines.push('反方盲区：' + n.blindspot);
    if (n.sentinels.length) lines.push('验证哨：' + n.sentinels.map((x) => signed(x.delta) + ' ' + x.when).join('；'));
    else if (n.verify) lines.push('验证方式：' + n.verify);
  }
  if (n.type === '规') {
    if (n.triggers.length) lines.push('触发：' + n.triggers.join('；'));
    if (n.cost) lines.push('破戒代价：' + n.cost);
  }
  return lines.join('\n');
}

function neighborLabel(r) {
  const t = r.type, out = r.dir === 'out';
  switch (t) {
    case '导致': return out ? '下游·本节点导致' : '上游·导致本节点';
    case '支撑': return out ? '本节点支撑' : '支撑本节点';
    case '反例': return out ? '本节点削弱' : '反例·削弱本节点';
    case '对立': return '对立·竞争解释';
    case '前置': return out ? '本节点是它的前置' : '前置·先要有';
    case '类似': return '类似';
    case '属于': return out ? '本节点属于' : '下属';
    default: return t;
  }
}

export function buildPack(store, id, { question = '', mode = 'ask', removed = new Set(), budget = 1500, chain = null } = {}) {
  const n = store.nodes.get(id);
  if (!n) return null;
  const items = [];
  const path = chainPath(store, n);
  items.push({ key: 'path', kind: '路径', full: '【路径】' + path.join(' › '), short: '【路径】… › ' + path.slice(-2).join(' › '), fixed: false });
  items.push({ key: 'node', kind: '节点', full: nodeText(n), fixed: true });

  // 邻居：挂载链上的上级、直接下级，加上横向关系；按优先级排好再放进去
  const nbs = [];
  const seen = new Set();
  const parent = n.under ? store.nodes.get(n.under) : null;
  if (parent) {
    seen.add('mount' + parent.id);
    nbs.push({ key: 'nb:up:' + parent.id, label: '上级·' + parent.type, prio: PRIORITY.上级, other: parent });
  }
  for (const c of store.children(id)) {
    seen.add('mount' + c.id);
    nbs.push({ key: 'nb:down:' + c.id, label: '下级·' + c.type + (c.paused ? '（暂停）' : ''), prio: c.type === '规' ? PRIORITY.下级规 : PRIORITY.下级, other: c });
  }
  const rels = store.relations(id).filter((r) => r.other);
  for (const r of rels) {
    const k = r.dir + r.type + r.otherId;
    if (seen.has(k)) continue;
    seen.add(k);
    nbs.push({ key: 'nb:' + k, label: neighborLabel(r), prio: PRIORITY[r.type] ?? PRIORITY[r.dir + r.type] ?? 9, other: r.other, rel: r.type });
  }
  nbs.sort((a, b) => a.prio - b.prio);
  for (const x of nbs) {
    items.push({
      key: x.key, kind: '邻居', rel: x.rel, prio: x.prio,
      full: '- ' + x.label + '｜' + x.other.title + '：' + x.other.claim,
      short: '- ' + x.label + '｜' + x.other.title,
      otherId: x.other.id,
    });
  }

  // 溯源链：作为补充上下文（只发标题）
  if (chain?.length) items.push({ key: 'chain', kind: '因果链', full: '【因果链】' + chain.join(' → '), short: '【因果链】' + chain.slice(-3).join(' → ') });

  // 类比候选
  if (mode === 'analogy') {
    const an = findAnalogies(id, store, 4);
    for (const a of an) {
      items.push({
        key: 'an:' + a.node.id, kind: '类比候选', prio: 7,
        full: '- 类比候选｜' + a.node.title + '：' + a.node.claim + '（' + a.reasons.join('；') + '）',
        short: '- 类比候选｜' + a.node.title + '（' + a.reasons[0] + '）', otherId: a.node.id,
      });
    }
  }

  // 激活词合集：本节点 + 涉及的邻居；规的触发场景也算（它们就是调用这条规的扳机）
  const own = [...(n.keywords || []), ...(n.type === '规' ? n.triggers : [])];
  const involved = items.filter((i) => i.otherId).map((i) => store.nodes.get(i.otherId)).filter(Boolean);
  const all = [...new Set([...own, ...involved.flatMap((x) => [...(x.keywords || []), ...(x.type === '规' ? x.triggers : [])])])];
  items.push({ key: 'kw', kind: '激活词', full: '【激活词】' + all.join('、'), short: '【激活词】' + own.join('、') });

  // 对立：自动要求区分
  const rivals = rels.filter((r) => r.type === '对立' && r.other).map((r) => r.other.title);
  if (rivals.length) {
    items.push({ key: 'rival', kind: '对立提示', fixed: true, full: '【要求】本节点与「' + [...new Set(rivals)].join('」「') + '」是对立关系。请说明怎样区分这两个解释。' });
  }
  items.push({ key: 'q', kind: '问题', fixed: true, full: '问题：' + (question || '（未填写）') });

  // 应用用户手动删减
  for (const it of items) it.state = removed.has(it.key) && !it.fixed ? 'removed' : 'full';
  fit(items, budget);
  const text = items.filter((i) => i.state !== 'removed' && i.state !== 'dropped')
    .map((i) => (i.state === 'short' ? i.short : i.full)).join('\n');
  const headerIdx = items.findIndex((i) => i.kind === '邻居' || i.kind === '类比候选');
  // 邻居前加一个小标题
  const finalText = headerIdx >= 0 && items.some((i) => (i.kind === '邻居' || i.kind === '类比候选') && (i.state === 'full' || i.state === 'short'))
    ? text.replace(/^- /m, '【邻居】\n- ') : text;
  return { items, text: finalText, tokens: estimateTokens(finalText), budget };
}

function cost(items) {
  return estimateTokens(items.filter((i) => i.state === 'full' || i.state === 'short').map((i) => (i.state === 'short' ? i.short : i.full)).join('\n'));
}

function fit(items, budget) {
  const nbs = items.filter((i) => (i.kind === '邻居' || i.kind === '类比候选') && i.state === 'full').sort((a, b) => b.prio - a.prio);
  // ① 邻居说明
  for (const it of nbs) { if (cost(items) <= budget) return; it.state = 'short'; }
  // ② 远层路径
  const p = items.find((i) => i.key === 'path' && i.state === 'full');
  if (p && cost(items) > budget) p.state = 'short';
  const ch = items.find((i) => i.key === 'chain' && i.state === 'full');
  if (ch && cost(items) > budget) ch.state = 'short';
  // ③ 低优先级邻居整条去掉（对立留到最后）
  for (const it of nbs) { if (cost(items) <= budget) return; it.state = 'dropped'; }
  // ④ 激活词只留本节点
  const kw = items.find((i) => i.key === 'kw' && i.state === 'full');
  if (kw && cost(items) > budget) kw.state = 'short';
}

export const MODE_QUESTIONS = {
  ask: '',
  extend: '请沿着这个节点延展：它的上游根因和下游后果还缺什么？给出 3 个最值得补进结构的新节点或关系。',
  analogy: '下面列出的类比候选与本节点结构相似。逐个判断类比是否成立（成立 / 不成立 + 一句理由），成立的说明可以从对方借用什么推演。',
};

// 如果把整库都发出去要多少 token（用来对比打包省了多少）
export function libraryTokens(store) {
  return estimateTokens(store.list().map((n) => {
    const rels = n.links.map((l) => l.type + '→' + (store.nodes.get(l.to)?.title || l.to)).join('；');
    return store.pathOf(n).join(' › ') + '（' + n.type + '｜' + n.status + '）' + n.claim + ' ' + n.note + ' 激活词：' + n.keywords.join('、') + (n.verify ? ' 验证：' + n.verify : '') + (rels ? ' 关系：' + rels : '');
  }).join('\n'));
}

// 体系大纲（Markdown），方便整体复制到聊天 App
export function systemOutline(store, system) {
  const lines = ['# ' + system], seen = new Set();
  const walk = (n, depth) => {
    if (seen.has(n.id)) return;
    seen.add(n.id);
    const rels = n.links.map((l) => l.type + '→' + (store.nodes.get(l.to)?.title || l.to)).join('，');
    lines.push('  '.repeat(depth) + '- **[' + n.type + (n.type !== '问' && n.confidence != null ? ' ' + n.confidence + '%' : '') + '] ' + n.title + '**：' + n.claim
      + (n.keywords.length ? ' 〔' + n.keywords.join('、') + '〕' : '') + (rels ? ' ｛' + rels + '｝' : ''));
    for (const child of store.children(n.id)) if (child.system === system) walk(child, depth + 1);
  };
  store.chainRoots(system).forEach((n) => walk(n, 0));
  const rest = store.list(system).filter((n) => !seen.has(n.id));
  if (rest.length) { lines.push('', '## 未挂载'); rest.forEach((n) => walk(n, 0)); }
  return lines.join('\n');
}
