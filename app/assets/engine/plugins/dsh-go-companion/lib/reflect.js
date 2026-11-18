// dsh-go-companion · 思考层（2026-11-18）
//
// 借鉴 Letta 的 sleep-time agent（后台整理主 agent 的记忆）和 Generative Agents 的 reflection：
// 一天两次（13:30 午间、22:15 晚报之后），两条鱼不在聊天里、在后台把这几天的账想一遍：
//   urgent    今天 / 这周最要紧的 1–3 件，每件必须带依据
//   patterns  反复出现的规律（必须带依据）
//   proposals 给体系的调整（改严的 App 分层可以直接执行；其余等他点头）
//   self      她们给自己调参（伙伴档间隔，有上下限）
//   diary     她们自己的一行日常（保持人设一致，让她们不只是他的附属）
//
// 和 nightly.js 的区别：那边只给索引、不读笔记原文；这里**有上限地读**（笔记节选总共 ≤3000 字）。
// 纪律照旧：不占会话（走 llm.stream）、不重试、产物严格 JSON 由代码逐条校验，缺依据的条目直接丢。

export const REFLECT_SLOTS = Object.freeze([{ hour: 13, minute: 30 }, { hour: 22, minute: 15 }])
export const REFLECT_SLOT_SPAN_MIN = 90
export const REFLECT_TIMEOUT_MS = 120_000
export const NOTES_BUDGET = 3000
export const GAP_MIN = 5
export const GAP_MAX = 30
const MAX = 3
const cut = (s, n) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, n)

/** 现在落在哪个槽（0 / 1），不在返回 -1。 */
export function reflectSlot(minuteOfDay) {
  for (let i = 0; i < REFLECT_SLOTS.length; i++) {
    const at = REFLECT_SLOTS[i].hour * 60 + REFLECT_SLOTS[i].minute
    if (minuteOfDay >= at && minuteOfDay < at + REFLECT_SLOT_SPAN_MIN) return i
  }
  return -1
}

/** 这个槽今天跑过没有（看账本 state: reflect, stage: done|discard）。 */
export function reflectDue(entries, { day = '', slot = -1, dayOf = () => '' } = {}) {
  if (slot < 0) return false
  return !(Array.isArray(entries) ? entries : []).some((e) => e?.state === 'reflect' && e.slot === slot &&
    (e.stage === 'done' || e.stage === 'discard' || e.stage === 'start') && dayOf(Number(e.at) || 0) === day)
}

export function reflectSystem(names = { companion: '鱼丸', strategist: '鲸澜' }) {
  return [
    '你是' + names.strategist + '和' + names.companion + '两姐妹的「后台思考」。现在没人在跟你聊天，你在把他这几天的账想清楚。',
    '只输出一个 JSON 对象，不要任何别的字。字段：',
    '{"urgent":[{"what":"一句话","why":"为什么要紧","evidence":"引用下面材料里的原文或数字"}],',
    ' "patterns":[{"what":"反复出现的规律","evidence":"引用原文或数字"}],',
    ' "proposals":[{"kind":"tighten_app|plan|note","app":"包名（tighten_app 才填）","tier":"dual|fun|game（tighten_app 才填）","text":"建议","evidence":"依据"}],',
    ' "self":{"companionGapMin":数字或null,"note":"你们打算怎么调整自己的做法，一句话"},',
    ' "diary":{"companion":"' + names.companion + '今天自己的一件小事（和她的人设一致）","strategist":"' + names.strategist + '今天自己的一件小事"}}',
    '规矩：每类最多 3 条；没有依据的不要写；依据必须能在材料里找到；不要鼓励、不要空话；',
    'companionGapMin 是伙伴档最快几分钟找他一次（' + GAP_MIN + '–' + GAP_MAX + '），他最近接球多就调小，嫌烦就调大，不确定填 null。',
  ].join('\n')
}

export function reflectUser(inp = {}) {
  const sec = (title, arr) => {
    const xs = (Array.isArray(arr) ? arr : []).map((x) => cut(x, 300)).filter(Boolean)
    return xs.length ? '【' + title + '】\n' + xs.map((x) => '- ' + x).join('\n') : ''
  }
  return [
    sec('近 7 天判决', inp.verdicts),
    sec('今天的 App 账', inp.appLedger),
    sec('额度', inp.quota ? [inp.quota] : []),
    sec('截止日', inp.deadlines),
    sec('三层主线', inp.horizons),
    sec('最近的 Gemini 回执', inp.receipts),
    sec('今天的推进与接管', inp.drives),
    sec('他最近说过的话（原话）', inp.said),
    sec('笔记节选（最近改过的）', inp.notes),
    sec('你们上一次的思考', inp.previous),
  ].filter(Boolean).join('\n\n').slice(0, 9000)
}

/** 笔记节选：最近改过的几篇，每篇开头一段，总共 ≤NOTES_BUDGET 字。 */
export function noteExcerpts(files = [], readText = () => '', budget = NOTES_BUDGET) {
  const out = []
  let used = 0
  for (const f of Array.isArray(files) ? files : []) {
    if (used >= budget) break
    const body = cut(readText(f.path), Math.min(700, budget - used))
    if (!body) continue
    out.push(String(f.rel || f.path).slice(0, 60) + '：' + body)
    used += body.length
  }
  return out
}

function parseJson(raw) {
  const s = String(raw || '')
  const a = s.indexOf('{')
  const b = s.lastIndexOf('}')
  if (a < 0 || b <= a) return null
  try {
    return JSON.parse(s.slice(a, b + 1))
  } catch {
    return null
  }
}

/** 逐条校验：缺依据的丢；长度截断；数量封顶。 */
export function parseReflect(raw) {
  const j = parseJson(raw)
  if (!j || typeof j !== 'object') return { ok: false, problems: ['不是 JSON'] }
  let dropped = 0
  const pick = (arr, fn) => (Array.isArray(arr) ? arr : []).map((x) => {
    const v = fn(x)
    if (!v) dropped++
    return v
  }).filter(Boolean).slice(0, MAX)
  const urgent = pick(j.urgent, (x) => (x?.what && x?.evidence ? { what: cut(x.what, 80), why: cut(x.why, 80), evidence: cut(x.evidence, 120) } : null))
  const patterns = pick(j.patterns, (x) => (x?.what && x?.evidence ? { what: cut(x.what, 80), evidence: cut(x.evidence, 120) } : null))
  const proposals = pick(j.proposals, (x) => {
    if (!x?.text || !x?.evidence) return null
    const kind = ['tighten_app', 'plan', 'note'].includes(x.kind) ? x.kind : 'note'
    const out = { kind, text: cut(x.text, 100), evidence: cut(x.evidence, 120) }
    if (kind === 'tighten_app') {
      if (!/^[A-Za-z0-9_.]+$/.test(String(x.app || '')) || !['dual', 'fun', 'game'].includes(x.tier)) return { ...out, kind: 'note' }
      out.app = String(x.app)
      out.tier = x.tier
    }
    return out
  })
  const gap = Number(j.self?.companionGapMin)
  const self = {
    companionGapMin: Number.isFinite(gap) ? Math.max(GAP_MIN, Math.min(GAP_MAX, Math.round(gap))) : null,
    note: cut(j.self?.note, 80),
  }
  const diary = { companion: cut(j.diary?.companion, 80), strategist: cut(j.diary?.strategist, 80) }
  if (!urgent.length && !patterns.length && !proposals.length && !diary.companion && !diary.strategist) {
    return { ok: false, problems: ['什么都没想出来'], dropped }
  }
  return { ok: true, value: { urgent, patterns, proposals, self, diary }, dropped }
}

export async function runReflect({ stream, provider, model, system, user, timeoutMs = REFLECT_TIMEOUT_MS }) {
  if (typeof stream !== 'function' || !provider || !model) return { ok: false, problems: ['没有模型路由'] }
  let timer
  const timeout = new Promise((resolve) => { timer = setTimeout(() => resolve(null), timeoutMs) })
  const run = (async () => {
    let out = ''
    for await (const chunk of stream({ provider, model, system, messages: [{ role: 'user', content: [{ type: 'text', text: user }] }], maxTokens: 1_800, temperature: 0.4 })) {
      if (!chunk) continue
      if (chunk.type === 'text-delta') out += chunk.text
      if (chunk.type === 'finish' && chunk.reason && chunk.reason.kind === 'error') return null
    }
    return out
  })().catch(() => null)
  let raw = null
  try {
    raw = await Promise.race([run, timeout])
  } finally {
    clearTimeout(timer)
  }
  if (raw === null) return { ok: false, problems: ['超时或模型报错'] }
  return parseReflect(raw)
}

export function reflectMarkdown(v, { at = Date.now(), label = '' } = {}) {
  const lines = ['# ' + label + ' · 两条鱼的后台思考', '']
  const sec = (t, arr, fn) => {
    if (!arr?.length) return
    lines.push('## ' + t, '')
    for (const x of arr) lines.push('- ' + fn(x))
    lines.push('')
  }
  sec('要紧的', v.urgent, (x) => x.what + (x.why ? '——' + x.why : '') + '（依据：' + x.evidence + '）')
  sec('规律', v.patterns, (x) => x.what + '（依据：' + x.evidence + '）')
  sec('建议', v.proposals, (x) => (x.kind === 'tighten_app' ? '[改严 ' + x.app + ' → ' + x.tier + '] ' : '') + x.text + '（依据：' + x.evidence + '）')
  if (v.self?.note || v.self?.companionGapMin) lines.push('## 她们给自己的调整', '', '- ' + (v.self.note || '') + (v.self.companionGapMin ? '（伙伴档间隔 → ' + v.self.companionGapMin + ' 分钟）' : ''), '')
  if (v.diary?.companion || v.diary?.strategist) lines.push('## 她们自己的日常', '', '- 鱼丸：' + (v.diary.companion || '—'), '- 鲸澜：' + (v.diary.strategist || '—'), '')
  return lines.join('\n')
}

/** 进每一轮上下文的那几行（按角色挑日常）。 */
export function reflectLines(store, role = 'companion', now = Date.now()) {
  const v = store?.value
  if (!v || now - Number(store.at || 0) > 36 * 3_600_000) return ''
  const lines = ['【你们最近在想的（后台思考，' + new Date(Number(store.at) + 8 * 3_600_000).toISOString().slice(5, 16).replace('T', ' ') + '）】']
  for (const x of v.urgent || []) lines.push('要紧：' + x.what + '（' + x.evidence + '）')
  for (const x of v.patterns || []) lines.push('规律：' + x.what)
  for (const x of (v.proposals || []).filter((p) => p.kind !== 'tighten_app')) lines.push('想跟他提：' + x.text)
  const d = role === 'strategist' ? v.diary?.strategist : v.diary?.companion
  if (d) lines.push('你自己今天的事（可以当聊天素材，别硬塞）：' + d)
  lines.push('他问「你们觉得我最近怎么样」时，拿这些有依据地说。')
  return lines.join('\n')
}
