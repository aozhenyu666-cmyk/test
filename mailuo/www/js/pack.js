// 上下文打包：只发相关子结构（路径 + 节点 + 一跳邻居 + 激活词），不发整库。
// 超出预算时依次：① 砍邻居说明（只留标题和关系）② 砍远层路径 ③ 去掉低优先级邻居 ④ 激活词只留本节点的。
// 节点本身和问题永不砍。
import { estimateTokens } from './llm.js';
import { findAnalogies } from './model.js';

// 邻居优先级：数字越小越重要，越晚被砍
const PRIORITY = { 对立: 1, 'in导致': 2, 反例: 3, 支撑: 4, 'out导致': 5, 前置: 6, 类似: 7, 属于: 8 };

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
  const path = store.pathOf(n);
  items.push({ key: 'path', kind: '路径', full: '【路径】' + path.join(' › '), short: '【路径】… › ' + path.slice(-2).join(' › '), fixed: false });
  const meta = [n.type, n.status, n.confidence != null ? '把握' + n.confidence + '%' : ''].filter(Boolean).join('｜');
  let nodeText = '【节点】' + n.title + '（' + meta + '）\n' + n.claim;
  if (n.note) nodeText += '\n' + n.note;
  if (n.verify) nodeText += '\n验证方式：' + n.verify;
  items.push({ key: 'node', kind: '节点', full: nodeText, fixed: true });

  const rels = store.relations(id).filter((r) => r.other);
  const seen = new Set();
  rels.sort((a, b) => (PRIORITY[a.type] ?? PRIORITY[a.dir + a.type] ?? 9) - (PRIORITY[b.type] ?? PRIORITY[b.dir + b.type] ?? 9));
  for (const r of rels) {
    const k = r.dir + r.type + r.otherId;
    if (seen.has(k)) continue;
    seen.add(k);
    const label = neighborLabel(r);
    items.push({
      key: 'nb:' + k, kind: '邻居', rel: r.type, prio: PRIORITY[r.type] ?? PRIORITY[r.dir + r.type] ?? 9,
      full: '- ' + label + '｜' + r.other.title + '：' + r.other.claim,
      short: '- ' + label + '｜' + r.other.title,
      otherId: r.otherId,
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

  // 激活词合集：本节点 + 涉及的邻居
  const own = n.keywords || [];
  const involved = items.filter((i) => i.otherId).map((i) => store.nodes.get(i.otherId)).filter(Boolean);
  const all = [...new Set([...own, ...involved.flatMap((x) => x.keywords || [])])];
  items.push({ key: 'kw', kind: '激活词', full: '【激活词】' + all.join('、'), short: '【激活词】' + own.join('、') });

  // 对立：自动要求区分
  const rivals = rels.filter((r) => r.type === '对立').map((r) => r.other.title);
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
