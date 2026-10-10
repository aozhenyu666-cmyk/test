// 两个模型接口：split（便宜模型：拆分 / 激活词 / 查重）与 think（旗舰模型：提问 / 延展 / 类比）。
// 支持 OpenAI 兼容格式（DeepSeek、通义、Kimi、OpenRouter…）和 Anthropic Messages 格式。
import { REL_TYPES, TYPES, STATUSES, today } from './model.js';

export const PRESETS = {
  deepseek: { label: 'DeepSeek', format: 'openai', base: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  qwen: { label: '通义千问', format: 'openai', base: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  kimi: { label: 'Kimi', format: 'openai', base: 'https://api.moonshot.cn/v1', model: 'kimi-latest' },
  openrouter: { label: 'OpenRouter', format: 'openai', base: 'https://openrouter.ai/api/v1', model: 'anthropic/claude-opus-5-5' },
  claude: { label: 'Claude', format: 'anthropic', base: 'https://api.anthropic.com', model: 'claude-opus-5-5' },
  claudeHaiku: { label: 'Claude Haiku', format: 'anthropic', base: 'https://api.anthropic.com', model: 'claude-haiku-5-5' },
  custom: { label: '自定义', format: 'openai', base: '', model: '' },
};

const DEFAULTS = {
  split: { ...PRESETS.deepseek, preset: 'deepseek', key: '' },
  think: { ...PRESETS.claude, preset: 'claude', key: '' },
  budget: 1500,
  theme: 'auto',
};

export function getConfig() {
  try {
    const c = JSON.parse(localStorage.getItem('mailuo.config') || '{}');
    return { ...DEFAULTS, ...c, split: { ...DEFAULTS.split, ...c.split }, think: { ...DEFAULTS.think, ...c.think } };
  } catch { return structuredClone(DEFAULTS); }
}
export function setConfig(c) { localStorage.setItem('mailuo.config', JSON.stringify(c)); }

// ---------- token 估算与用量账本 ----------
// 中文按 1 字 ≈ 1 token、其他字符按 3.5 字符 ≈ 1 token 估算（偏保守，宁多勿少）
export function estimateTokens(s) {
  s = String(s || '');
  const cjk = (s.match(/[⺀-鿿豈-﫿＀-￯　-〿]/g) || []).length;
  return Math.ceil(cjk + (s.length - cjk) / 3.5);
}

export function usage() {
  try { return JSON.parse(localStorage.getItem('mailuo.usage') || '{}'); } catch { return {}; }
}
function record(role, sent, recv) {
  const u = usage();
  const d = (u[today()] ||= {});
  const r = (d[role] ||= { sent: 0, recv: 0, n: 0 });
  r.sent += sent; r.recv += recv; r.n += 1;
  const keys = Object.keys(u).sort();
  while (keys.length > 60) delete u[keys.shift()];
  localStorage.setItem('mailuo.usage', JSON.stringify(u));
}

// ---------- 调用 ----------
const NEEDS_FALLBACK = /^claude-(opus-5-5|opus-5|fable-5-1|sonnet-5-5)$/;

export async function callModel(role, system, user, { signal, maxTokens = 4000 } = {}) {
  const cfg = getConfig()[role];
  if (!cfg.base || !cfg.model) throw new Error('请先在「设置」里填写' + (role === 'split' ? '拆分' : '推演') + '模型的地址和模型名');
  if (!cfg.key) throw new Error('请先在「设置」里填写' + (role === 'split' ? '拆分' : '推演') + '模型的密钥');
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new Error('请求超时（90 秒）')), 90000);
  signal?.addEventListener('abort', () => ctrl.abort(signal.reason));
  let res, data;
  try {
    if (cfg.format === 'anthropic') {
      const url = cfg.base.replace(/\/+$/, '').replace(/\/v1\/messages$/, '') + '/v1/messages';
      const body = { model: cfg.model, max_tokens: maxTokens, system, messages: [{ role: 'user', content: user }] };
      const headers = {
        'content-type': 'application/json', 'x-api-key': cfg.key, 'anthropic-version': '2023-06-01',
        'anthropic-dangerous-direct-browser-access': 'true',
      };
      // 旗舰模型拒答时由服务端自动换模型重答
      if (NEEDS_FALLBACK.test(cfg.model) && /api\.anthropic\.com/.test(url)) {
        body.fallbacks = 'default';
        headers['anthropic-beta'] = 'server-side-fallback-2026-07-01';
      }
      res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body), signal: ctrl.signal });
      data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error('模型返回 ' + res.status + '：' + (data.error?.message || JSON.stringify(data).slice(0, 200)));
      if (data.stop_reason === 'refusal') throw new Error('模型拒绝回答' + (data.stop_details?.explanation ? '：' + data.stop_details.explanation : ''));
      const text = (data.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('');
      const sent = data.usage?.input_tokens ?? estimateTokens(system + user);
      record(role, sent, data.usage?.output_tokens ?? estimateTokens(text));
      return { text, sent, recv: data.usage?.output_tokens };
    }
    const base = cfg.base.replace(/\/+$/, '');
    const url = /\/chat\/completions$/.test(base) ? base : base + '/chat/completions';
    res = await fetch(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer ' + cfg.key },
      body: JSON.stringify({
        model: cfg.model, max_tokens: maxTokens, temperature: role === 'split' ? 0.2 : 0.6,
        messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
      }),
      signal: ctrl.signal,
    });
    data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error('模型返回 ' + res.status + '：' + (data.error?.message || JSON.stringify(data).slice(0, 200)));
    const text = data.choices?.[0]?.message?.content || '';
    const sent = data.usage?.prompt_tokens ?? estimateTokens(system + user);
    record(role, sent, data.usage?.completion_tokens ?? estimateTokens(text));
    return { text, sent, recv: data.usage?.completion_tokens };
  } catch (e) {
    if (e.name === 'AbortError' || ctrl.signal.aborted) throw ctrl.signal.reason instanceof Error ? ctrl.signal.reason : new Error('已取消');
    if (e instanceof TypeError) throw new Error('连不上模型接口（网络或跨域限制）：' + e.message);
    throw e;
  } finally { clearTimeout(timer); }
}

// ---------- 提示词 A：拆分 ----------
export const PROMPT_SPLIT = `你是结构拆分器。把下面的材料拆成节点和关系，只输出 JSON，不要任何其他文字。
规则：
1. 每个节点只含一句结论，最多 12 个节点。
2. 每个节点给 3-5 个激活词，选最能唤起相关知识的专业词。
3. 关系类型只能是：属于、导致、支撑、反例、对立、前置、类似。每条关系附一句理由。
4. 材料中没有证据的推断，status 设为"假设"，并给出 verify（一句验证方式）。
5. 已有节点列表见下方；含义相同的，用 merge_into 指向已有 id，不新建。
6. type 只能是：概念、论断、规则、问题、案例。confidence 为 0-100 的整数。
格式：
{"nodes":[{"tmp_id","title","type","claim","keywords","status","confidence","verify","merge_into"}],
 "links":[{"from","to","type","why"}]}
links 的 from / to 填 tmp_id 或已有节点 id。`;

export function splitUserMessage(existing, input) {
  const list = existing.length ? existing.map((n) => n.id + ' ' + n.title).join('\n') : '（无）';
  return '已有节点：\n' + list + '\n\n材料：\n' + input;
}

export function extractJson(text) {
  const t = String(text).replace(/```(?:json)?/gi, '');
  const a = t.indexOf('{'), b = t.lastIndexOf('}');
  if (a < 0 || b <= a) throw new Error('模型没有返回 JSON');
  return JSON.parse(t.slice(a, b + 1));
}

// 把模型输出规范成候选卡片
export function normalizeSplit(raw) {
  const nodes = (raw.nodes || []).slice(0, 12).map((n, i) => ({
    tmp_id: String(n.tmp_id ?? n.id ?? 't' + (i + 1)),
    title: String(n.title || '').trim().slice(0, 40),
    type: TYPES.includes(n.type) ? n.type : '论断',
    claim: String(n.claim || n.title || '').trim(),
    keywords: (Array.isArray(n.keywords) ? n.keywords : String(n.keywords || '').split(/[,，、]/)).map((k) => String(k).trim()).filter(Boolean).slice(0, 5),
    status: STATUSES.includes(n.status) ? n.status : '假设',
    confidence: clampConf(n.confidence),
    verify: String(n.verify || ''),
    merge_into: n.merge_into ? String(n.merge_into) : '',
  })).filter((n) => n.title);
  const links = (raw.links || []).map((l) => ({
    from: String(l.from ?? ''), to: String(l.to ?? ''), type: String(l.type || ''), why: String(l.why || ''),
  })).filter((l) => l.from && l.to && REL_TYPES.includes(l.type) && l.from !== l.to);
  return { nodes, links };
}
function clampConf(v) {
  const n = parseInt(String(v ?? '').replace('%', ''), 10);
  return Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 50;
}

// ---------- 提示词 B：推演 ----------
export const PROMPT_THINK = `下面是我的知识结构片段：路径、节点、邻居和激活词。请先用激活词定位相关知识，再回答问题。
回答前在内部完成：列出要点、判断上下游与并列关系、找出唯一核心。不要输出过程。
只输出：
- 核心判断：一句话
- 结构：上游 → 下游（最多 5 个节点）
- 另一种可能：一个竞争解释
- 如果我错了，最可能错在哪
- 把握：几成
- 验证：一个本周能做的小动作
- 建议新增：最多 3 个节点或关系（标题 + 一句结论 + 关系类型）
禁止逐条分析每个要点。
「建议新增」每条单独一行，格式：关系类型｜标题｜一句结论。关系类型是新节点对当前节点的关系，只能用：属于、导致、支撑、反例、对立、前置、类似。`;

const SECTIONS = [
  ['core', /^核心判断/], ['structure', /^结构/], ['alt', /^另一种可能/], ['wrong', /^如果我错了/],
  ['confidence', /^把握/], ['verify', /^验证/], ['suggest', /^建议新增/],
];

export function parseAnswer(text) {
  const out = { raw: text, suggest: [] };
  let cur = null;
  const buf = {};
  for (const rawLine of String(text).split('\n')) {
    const line = rawLine.replace(/^\s*(?:[-*•]|\d+[.、)])\s*/, '').replace(/\*\*/g, '').trim();
    if (!line) continue;
    const sec = SECTIONS.find(([, re]) => re.test(line));
    if (sec && /^[^：:]{0,16}[：:]/.test(line)) {
      cur = sec[0];
      buf[cur] = [line.replace(/^[^：:]*[：:]\s*/, '')].filter(Boolean);
    } else if (sec && line.length < 16) { cur = sec[0]; buf[cur] = []; }
    else if (cur) buf[cur].push(line);
  }
  for (const [k] of SECTIONS) if (k !== 'suggest') out[k] = (buf[k] || []).join(' ').trim();
  const pct = /(\d{1,3})\s*%/.exec(out.confidence || '') || /([一二三四五六七八九十\d])\s*成/.exec(out.confidence || '');
  if (pct) {
    const cn = '零一二三四五六七八九十'.indexOf(pct[1]);
    out.confidencePct = pct[0].includes('%') ? +pct[1] : (cn >= 0 ? cn : +pct[1]) * 10;
  }
  out.chain = (out.structure || '').split(/\s*(?:→|->|⟶|＞|>)\s*/).map((s) => s.trim()).filter(Boolean).slice(0, 6);
  for (const line of buf.suggest || []) {
    const s = parseSuggestion(line);
    if (s) out.suggest.push(s);
  }
  out.suggest = out.suggest.slice(0, 3);
  out.ok = !!out.core;
  return out;
}

function parseSuggestion(line) {
  if (/^(无|没有|暂无)/.test(line)) return null;
  const parts = line.split(/\s*[｜|]\s*/).filter(Boolean);
  let type, title, claim;
  if (parts.length >= 3) {
    [type, title, claim] = [parts[0], parts[1], parts.slice(2).join('，')];
    if (!REL_TYPES.includes(type.replace(/[[\]【】（）()]/g, ''))) {
      const t = parts.find((p) => REL_TYPES.includes(p.replace(/[[\]【】（）()]/g, '')));
      if (t) { type = t; [title, claim] = parts.filter((p) => p !== t); }
    }
  } else {
    const m = /^(.+?)[：:](.+)$/.exec(line);
    if (!m) return null;
    title = m[1]; claim = m[2];
    type = REL_TYPES.find((t) => line.includes(t)) || '类似';
    claim = claim.replace(/[（(][^）)]*[）)]\s*$/, '');
  }
  type = (type || '').replace(/[[\]【】（）()]/g, '').trim();
  if (!REL_TYPES.includes(type)) type = REL_TYPES.find((t) => line.includes(t)) || '类似';
  title = String(title || '').replace(/^(标题|节点)[：:]/, '').trim().slice(0, 40);
  claim = String(claim || '').replace(/^(结论)[：:]/, '').trim();
  return title ? { type, title, claim } : null;
}
