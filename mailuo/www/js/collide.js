// 录入 V2「碰撞引擎」：新材料先撞击旧体系（调整把握度 / 补断头链），最后才新建节点。
// 本文件只做纯逻辑（提示词、节点库摘要、模型输出规范化），界面在 app.js（见 docs/v2/tasks/T03、T04）。
import { REL_TYPES, STATUSES, MOUNT_RULES, normalizeType, normalizeSentinels, titleSim } from './model.js';
import { estimateTokens } from './llm.js';

export const PROMPT_COLLIDE = `你是首席参谋的「碰撞引擎」。我给你「节点库摘要」（我已有的 问 / 论 / 规）和一段「新材料」。
先用新材料撞击旧体系，再考虑新建。只输出 JSON，不要任何其他文字。

按优先级处理：
1. 调整（adjust）：新材料命中了某条「论」或「规」的验证哨，或直接支持 / 削弱它 → 提议调整把握度。
   from 填摘要里的当前把握，to 填建议值，reason 一句话说明，hit 填命中的验证哨原文（没有命中具体哨就留空）。
   若下调后该「论」低于 60%，把挂在它下面、把握 ≥ 80 的「规」的 id 放进 pause_rules。
2. 补链：新材料能作为已有「问 / 论」下游缺失的「论 / 规」→ 新建节点，under 填那个已有节点的 id。
3. 新建：只有完全属于新维度的内容才新建。整次最多 6 个新节点。

每个新节点必须填好槽位草稿：
- 问：pillars（2–3 条决定成败的客观约束 / 规律）
- 论：confidence、blindspot（一句话：什么情况下这条论断会失效）、sentinels（2 条：一条加分、一条扣分；delta 为 -30 到 +30 的整数）
- 规：confidence、claim 写唯一具体动作、triggers（1–3 个触发场景，写成具体的念头或情境）、cost（一句话：不执行的最坏代价）；规必须挂在某个 论 或 问 下面
挂载规则：论 挂在 问 / 论 下；规 挂在 论 / 问 下；问 不挂，或挂在 问 下。under 填已有节点 id 或本次新节点的 tmp_id。
每个新节点给 3–5 个激活词（keywords），选最能唤起相关专业知识的词。材料里没有证据的推断，status 填「假设」。

格式：
{"adjust":[{"target":"n-0007","from":85,"to":65,"reason":"…","hit":"…","pause_rules":[]}],
 "nodes":[{"tmp_id":"t1","type":"问|论|规","title":"…","claim":"…","under":"n-0003","keywords":["…"],"status":"假设","confidence":60,
   "pillars":[],"blindspot":"","sentinels":[{"when":"…","delta":10}],"triggers":[],"cost":""}],
 "links":[{"from":"t1","to":"n-0004","type":"对立","why":"…"}]}
links 只放挂载以外的横向关系，类型只能是：属于、导致、支撑、反例、对立、前置、类似。没有就给空数组。`;

// ---------- 节点库摘要：只发和材料相关的节点 + 它们的上下级，控制 token ----------
function bigrams(s) {
  const t = String(s).replace(/\s+/g, '');
  const out = new Set();
  for (let i = 0; i < t.length - 1; i++) out.add(t.slice(i, i + 2));
  return out;
}

// 节点和材料的相关度：激活词 / 触发场景命中最重要，其次是标题和结论的字面重合
export function relevance(n, material, matGrams = bigrams(material)) {
  let score = 0;
  for (const k of n.keywords) if (k && material.includes(k)) score += 3;
  for (const t of [...n.triggers, ...n.sentinels.map((s) => s.when)]) {
    const g = bigrams(t);
    let hit = 0;
    for (const x of g) if (matGrams.has(x)) hit++;
    if (g.size && hit / g.size >= 0.4) score += 2;
  }
  const tg = bigrams(n.title + n.claim);
  let hit = 0;
  for (const x of tg) if (matGrams.has(x)) hit++;
  if (tg.size) score += (hit / tg.size) * 4;
  return score;
}

export function digestLine(n) {
  const conf = n.confidence != null && n.type !== '问' ? ' ' + n.confidence + '%' : '';
  const parts = [n.id + ' ' + n.type + conf + ' ' + n.title + (n.paused ? '（暂停）' : '')];
  if (n.under) parts.push('挂 ' + n.under);
  if (n.type === '问' && n.pillars.length) parts.push('支柱：' + n.pillars.join('；'));
  if (n.type === '论' && n.sentinels.length) parts.push('哨：' + n.sentinels.map((s) => (s.delta > 0 ? '+' : '') + s.delta + ' ' + s.when).join('；'));
  if (n.type === '规' && n.triggers.length) parts.push('触发：' + n.triggers.join('；'));
  return parts.join('｜');
}

// 返回 {text, ids, tokens, total}；库小于 maxNodes 时全发，否则按相关度挑，再补上它们的上级链和直接下级
export function libraryDigest(store, material, { system, maxNodes = 40 } = {}) {
  const all = store.list(system);
  let picked;
  if (all.length <= maxNodes) picked = all;
  else {
    const grams = bigrams(material);
    const ranked = all.map((n) => [n, relevance(n, material, grams)]).filter(([, s]) => s > 0).sort((a, b) => b[1] - a[1]);
    const set = new Map();
    for (const [n] of ranked) {
      if (set.size >= maxNodes) break;
      for (const x of [...store.ancestors(n.id).reverse(), n, ...store.children(n.id)]) {
        if (set.size >= maxNodes) break;
        set.set(x.id, x);
      }
    }
    picked = [...set.values()];
  }
  // 按链条顺序排：先问，再论，再规，便于模型看出上下游
  const order = { 问: 0, 论: 1, 规: 2 };
  picked.sort((a, b) => order[a.type] - order[b.type] || a.id.localeCompare(b.id));
  const text = picked.length ? picked.map(digestLine).join('\n') : '（空库）';
  return { text, ids: picked.map((n) => n.id), tokens: estimateTokens(text), total: all.length };
}

export function collideUserMessage(digest, material) {
  return '节点库摘要（id 类型 把握 标题｜挂载｜槽位）：\n' + digest.text + '\n\n新材料：\n' + material;
}

// ---------- 规范化模型输出：校验 id、挂载规则、数值范围 ----------
const clamp = (v, lo, hi, dflt) => {
  const n = parseInt(String(v ?? '').replace('%', ''), 10);
  return Number.isFinite(n) ? Math.max(lo, Math.min(hi, n)) : dflt;
};
const strList = (v, max) => (Array.isArray(v) ? v : v ? String(v).split(/[、,，;；\n]/) : []).map((x) => String(x).trim()).filter(Boolean).slice(0, max);

export function normalizeCollide(raw, store) {
  raw = raw || {};
  // 1) 调整提案：目标必须存在且是 论 / 规；from 以库里的真实值为准；无变化的丢掉
  const adjust = [];
  const seen = new Set();
  for (const a of Array.isArray(raw.adjust) ? raw.adjust : []) {
    const t = store.nodes.get(String(a?.target || ''));
    if (!t || t.type === '问' || seen.has(t.id)) continue;
    const from = t.confidence ?? 50;
    const to = clamp(a.to, 0, 100, from);
    if (to === from) continue;
    seen.add(t.id);
    const below = new Set(store.descendants(t.id).filter((d) => d.type === '规').map((d) => d.id));
    adjust.push({
      target: t.id, from, to, reason: String(a.reason || '').trim(), hit: String(a.hit || '').trim(),
      pause_rules: strList(a.pause_rules, 20).filter((id) => below.has(id)),
    });
  }

  // 2) 新节点：类型 / 槽位规范化，tmp_id 去重
  const nodes = [];
  const tmpIds = new Set();
  for (const [i, n] of (Array.isArray(raw.nodes) ? raw.nodes : []).slice(0, 6).entries()) {
    const title = String(n?.title || '').trim().slice(0, 40);
    if (!title) continue;
    let tmp = String(n.tmp_id ?? 't' + (i + 1));
    if (tmpIds.has(tmp) || store.nodes.has(tmp)) tmp = 't' + (i + 1) + '_' + i;
    tmpIds.add(tmp);
    const type = normalizeType(n.type);
    nodes.push({
      tmp_id: tmp, type, title, claim: String(n.claim || title).trim(),
      under: String(n.under || '').trim(),
      keywords: strList(n.keywords, 5),
      status: STATUSES.includes(n.status) ? n.status : '假设',
      confidence: type === '问' ? null : clamp(n.confidence, 0, 100, 50),
      pillars: type === '问' ? strList(n.pillars, 3) : [],
      blindspot: type === '论' ? String(n.blindspot || '').trim() : '',
      sentinels: type === '论' ? normalizeSentinels(n.sentinels).slice(0, 4).map((s) => ({ ...s, delta: Math.max(-30, Math.min(30, s.delta)) })) : [],
      triggers: type === '规' ? strList(n.triggers, 3) : [],
      cost: type === '规' ? String(n.cost || '').trim() : '',
      verify: '',
      merge_into: '',
    });
  }
  // 挂载目标校验：必须是已有节点或本次 tmp_id，且符合挂载规则；不合规就清空（进「未挂载」）
  const typeOf = (ref) => store.nodes.get(ref)?.type || nodes.find((x) => x.tmp_id === ref)?.type;
  for (const n of nodes) {
    const pt = n.under && n.under !== n.tmp_id ? typeOf(n.under) : null;
    n.under_ok = !!pt && MOUNT_RULES[n.type].includes(pt);
    if (!n.under_ok) n.under = '';
  }
  // 发现和已有节点几乎同名的，标出来让你决定是否合并
  for (const n of nodes) {
    let best = null;
    for (const o of store.nodes.values()) {
      if (o.type !== n.type) continue;
      const sim = titleSim(n.title, o.title);
      if (sim >= 0.6 && (!best || sim > best.sim)) best = { id: o.id, sim };
    }
    n.dupe_of = best ? best.id : '';
  }

  // 3) 横向关系：两端必须能解析，类型只能是 7 种
  const ok = (ref) => store.nodes.has(ref) || tmpIds.has(ref);
  const links = (Array.isArray(raw.links) ? raw.links : []).map((l) => ({
    from: String(l?.from || ''), to: String(l?.to || ''), type: String(l?.type || ''), why: String(l?.why || '').trim(),
  })).filter((l) => l.from !== l.to && ok(l.from) && ok(l.to) && REL_TYPES.includes(l.type));

  return { adjust, nodes, links };
}

// 调整提案应用后的下游影响：把握低于 60 的论，下面把握 ≥ 80 的规建议暂停（模型没给时的兜底）
export function suggestPauses(store, targetId, to) {
  const t = store.nodes.get(targetId);
  if (!t || t.type !== '论' || to >= 60) return [];
  return store.descendants(targetId).filter((d) => d.type === '规' && (d.confidence ?? 0) >= 80 && !d.paused).map((d) => d.id);
}

// ---------- 沙盘推演：先穷举可行路径，再收敛 ----------
// 这里的立场：穷举本身不是答案。每条路径必须写出「会死在哪」，这句话才是可比较的判定依据；
// 写不出具体失败点的路径直接丢掉。同时把淘汰过的路径记在问下面，避免下次重复探索。
export const PROMPT_OPTIONS = `你是首席参谋的「沙盘推演」。我给你一个「问」（痛点或课题）和它下面已有的论断。
你的任务不是给建议，是先把可行路径摊开，再标出每条路会死在哪。只输出 JSON，不要任何其他文字。

规则：
1. 给 3–5 条互斥的路径。互斥指选了 A 就不必走 B，不是同一条路线的细节变体。
2. 必须覆盖三类，各至少一条：
   - 「现成」：已有的工具、开源项目、成熟范式。写具体名字，不要写「类似工具」。
   - 「最小验证」：用最低成本先把核心假设验一遍的做法。
   - 「自建」：自己动手，说明技术边界在哪。
3. 每条必须写 fatal：如果这条路彻底失败，最可能死在哪个环节。要具体到可观察的现象，
   不能写「效果不好」「难度太大」「时间不够」这类废话。写不出具体失败点的路径，说明还没想清楚，不要输出。
4. test 是最小验证动作：一周内能做完、能得出「行 / 不行」结论的动作。
5. 不要排序，不要写推荐，不要说「建议选择」。选择权在我。

格式：
{"paths":[{"title":"不超过 20 字","kind":"现成 / 最小验证 / 自建","hypothesis":"这条路径成立的前提，一句话","fatal":"最可能死在哪，要具体可观察","test":"一周内能做完的验证动作","cost":"投入量级，如 半天 / 两周 / 持续"}]}`;

export function optionsUserMessage(store, questionId) {
  const q = store.nodes.get(questionId);
  if (!q) return '';
  const kids = store.children(questionId);
  const lines = ['问：' + q.title, '说明：' + (q.claim || '—')];
  if (q.pillars?.length) lines.push('破局支柱（决定成败的客观约束）：' + q.pillars.join('；'));
  if (kids.length) lines.push('已有的论断：' + kids.map((k) => k.type + '「' + k.title + '」' + (k.confidence != null ? ' ' + k.confidence + '%' : '')).join('、'));
  if (q.discarded?.length) lines.push('已经淘汰过的路径（不要重复推荐）：' + q.discarded.map((d) => d.title + (d.fatal ? '（死在：' + d.fatal + '）' : '')).join('；'));
  return lines.join('\n');
}

const OPTION_KINDS = ['现成', '最小验证', '自建'];

export function normalizeOptions(raw, store, questionId) {
  const q = store.nodes.get(questionId);
  const before = (q?.discarded || []).map((d) => d.title);
  const paths = [];
  const seen = [];
  for (const [i, p] of (Array.isArray(raw?.paths) ? raw.paths : []).slice(0, 6).entries()) {
    const title = String(p?.title || '').trim().slice(0, 40);
    if (!title) continue;
    if (seen.some((t) => titleSim(t, title) >= 0.7)) continue;
    seen.push(title);
    const fatal = String(p?.fatal || '').trim();
    paths.push({
      key: 'p' + (i + 1),
      title,
      kind: OPTION_KINDS.includes(p?.kind) ? p.kind : '其它',
      hypothesis: String(p?.hypothesis || '').trim(),
      fatal,
      test: String(p?.test || '').trim(),
      cost: String(p?.cost || '').trim(),
      noFatal: !fatal,
      seenBefore: before.find((t) => titleSim(t, title) >= 0.6) || '',
    });
  }
  return paths;
}

// 选中的路径 → 一个「论」草案：fatal 直接变成扣分哨，test 变成加分哨
// （没有哨的论会进体检的「盲信无哨」，所以这里必须给出加减分）
export function optionToNode(path, question) {
  const sentinels = [];
  if (path.fatal) sentinels.push({ when: path.fatal.slice(0, 60), delta: -20 });
  if (path.test) sentinels.push({ when: '按「' + path.test.slice(0, 50) + '」验证通过', delta: 15 });
  return {
    type: '论',
    title: path.title,
    claim: path.hypothesis || path.title,
    under: question.id,
    confidence: 55,
    status: '假设',
    keywords: [],
    blindspot: path.fatal || '',
    sentinels,
    source: '沙盘推演',
  };
}

// ---------- 整理挂载：v1 升级上来的「论 / 规」大多没挂载，而且库里往往还没有「问」 ----------
export const PROMPT_MOUNT = `你是首席参谋的「整理挂载」助手。下面是我知识库里还没挂载的「论 / 规」，以及已有的「问 / 论」。
请把每个未挂载节点挂到最合适的父节点下，只输出 JSON，不要任何其他文字。
规则：
1. 挂载规则：论 挂在 问 / 论 下；规 挂在 论 / 问 下。under 填已有节点 id，或本次新建「问」的 tmp_id。
2. 如果没有合适的父节点，可以新建「问」作为根（整次最多 3 个），每个问写 title、claim（一句话说明这个课题）、pillars（2–3 条决定成败的客观约束）。
3. 实在不适合挂到任何地方的节点，不要放进 mounts。reason 用一句话说明为什么挂在那里。
格式：
{"questions":[{"tmp_id":"q1","title":"…","claim":"…","pillars":["…"]}],
 "mounts":[{"id":"n-0001","under":"q1","reason":"…"}]}`;

export function mountUserMessage(store, system) {
  const loose = store.unmounted(system);
  const parents = store.list(system).filter((n) => n.type === '问' || (n.type === '论' && n.under && store.nodes.has(n.under)));
  return '未挂载的节点：\n' + (loose.map(digestLine).join('\n') || '（无）') + '\n\n已有的问 / 论（可作为父节点）：\n' + (parents.map(digestLine).join('\n') || '（无）');
}

// 校验整理挂载的输出：父节点必须存在（或是本次新建的问），并符合挂载规则、不成环
export function normalizeMounts(raw, store, system) {
  raw = raw || {};
  const loose = new Set(store.unmounted(system).map((n) => n.id));
  const questions = (Array.isArray(raw.questions) ? raw.questions : []).slice(0, 3).map((q, i) => ({
    tmp_id: String(q?.tmp_id || 'q' + (i + 1)),
    title: String(q?.title || '').trim().slice(0, 40),
    claim: String(q?.claim || q?.title || '').trim(),
    pillars: strList(q?.pillars, 3),
  })).filter((q) => q.title && !store.nodes.has(q.tmp_id));
  const qIds = new Set(questions.map((q) => q.tmp_id));
  const seen = new Set();
  const mounts = [];
  for (const m of Array.isArray(raw.mounts) ? raw.mounts : []) {
    const id = String(m?.id || ''), under = String(m?.under || '');
    if (!loose.has(id) || seen.has(id)) continue;
    const child = store.nodes.get(id);
    const parentType = qIds.has(under) ? '问' : store.nodes.get(under)?.type;
    if (!parentType || !MOUNT_RULES[child.type].includes(parentType)) continue;
    if (!qIds.has(under) && store.canMount(id, under)) continue;
    seen.add(id);
    mounts.push({ id, under, reason: String(m.reason || '').trim() });
  }
  // 只保留真的被用到的新「问」
  const used = new Set(mounts.map((m) => m.under));
  return { questions: questions.filter((q) => used.has(q.tmp_id)), mounts };
}
