// The seven dimensions come from one place (dims.mjs): the panel, the model context and the tool
// output must never disagree about what 「心疼」 is called or which key holds it.
import { DIM_LABEL, DIM_VISIBLE_MIN, dimsOf } from './dims.mjs'

/**
 * dsh-go 原生视图构造器 + 预算校验器 —— R4 人格/记忆/情绪面板
 *
 * 为什么要这一层：
 *   dsh-go 的插件视图是**一棵 JSON 组件树**，有硬预算（面板 32KiB/120 节点/8 层；
 *   状态条 2KiB/8 节点/3 层；工具卡 8KiB/40 节点/6 层）。JSON 写错、组件名写错、
 *   超预算的后果不是报错，是**整块降级成「插件界面有误」或灰色占位** —— 很难查。
 *   所以：**先构造 + 本地校验，再交给 App 渲染**。
 *
 * 用法：
 *   import { moodPanel, memoryPanel, purposePanel, moodStrip, rememberCard } from './views.mjs'
 *   moodPanel(state, cfg)            → 面板视图（placement: chat.panel / session.tab）
 *   moodStrip(state)                 → 状态条视图（emit 的数据）
 *   rememberCard(args, value)        → 工具卡视图（presentationMeta）
 *   validate(view, 'panel')          → { ok, issues[] }   —— 与 App 插件详情页同一套判据
 *   node views.mjs --selftest        → 用假数据跑一遍全部视图 + 预算
 *
 * ⚠️ 三条铁律（踩过就知道多重要）：
 *   1. 工具卡只用最稳的 8 个组件（stack/row/text/metric/chart/compare/steps/callout）——
 *      它写进对话记录，一年后打开老对话也要能画。
 *   2. 面板可以用 list/keyValue/table/actions/options/confirm，但**根节点别放 v2 独有组件**
 *      （emptyState/gallery），老 App 会整块画成占位。
 *   3. 任何值都可以写绑定 { "$": "data.x.y" }，但 `type` 不能绑定。
 */

// ── 预算（照抄 docs/plugins/components.md §上限）─────────────────────────────
export const BUDGET = {
  toolCard: { bytes: 8 * 1024, nodes: 40, depth: 6 },
  panel: { bytes: 32 * 1024, nodes: 120, depth: 8 },
  strip: { bytes: 2 * 1024, nodes: 8, depth: 3 },
}

// ── v1 的 16 个组件（前 10 个 = 模型 dsh-ui 卡也认；后 6 个 = 插件视图独有）────
const V1_TYPES = new Set([
  'stack', 'row', 'text', 'callout', 'steps', 'compare', 'metric', 'chart', 'progress',
  'list', 'keyValue', 'table', 'actions', 'image', 'options', 'confirm',
])
// v2 才有的（面板可用，但**别放根节点**）—— 老 App 画灰色占位
const V2_TYPES = new Set(['emptyState', 'gallery', 'grid', 'card', 'spacer', 'divider'])

// 工具卡白名单：只用最稳的 8 个
const TOOLCARD_TYPES = new Set(['stack', 'row', 'text', 'metric', 'chart', 'compare', 'steps', 'callout'])

const ICONS = new Set(`bolt star sparkle heart flag bookmark pin bell clock alarm calendar todo note document folder search code terminal globe chart lightbulb book puzzle robot chat mail translate image camera mic link summary wrench bug travel school home play refresh archive team wallet palette coin money speaker read copy quote share edit write save paste add remove delete clear fork regenerate reply select-text qr download downloads external info warning check done success error question pending running tip list table panel sidebar menu more expand collapse file folder-open workspace storage pdf zip slides article text diff gallery audio music video video-file play-video pause stop replay generate-image generate-video diagram plugin bot agent skill model tool workflow goal plan job automation command trajectory web web-search browser history time today timezone schedule repeat reminder user account group chats mention invite announcement reaction poll notification send community forum feedback wechat qq telegram discord phone computer cloud server power restart retry theme color text-size pet featured badge favorite settings`.split(/\s+/))

// ── 校验器 ───────────────────────────────────────────────────────────────────
function countNodes(node, depth = 1, acc = { n: 0, maxDepth: 0, bad: [] }) {
  if (!node || typeof node !== 'object' || Array.isArray(node)) { acc.bad.push('节点不是对象'); return acc }
  acc.n += 1
  acc.maxDepth = Math.max(acc.maxDepth, depth)
  const children = []
  if (Array.isArray(node.children)) children.push(...node.children.map((c, i) => ({ c, where: `children[${i}]` })))
  if (Array.isArray(node.items)) children.push(...node.items.map((c, i) => ({ c, where: `items[${i}]` })))
  for (const { c, where } of children) {
    if (!c || typeof c !== 'object' || Array.isArray(c)) { acc.bad.push(`${where} 不是对象`); continue }
    // items 里既可能是子组件也可能是数据行（list/table/compare/steps/gallery）
    if (typeof c.type !== 'string') continue
    countNodes(c, depth + 1, acc)
  }
  return acc
}

function checkNode(node, path, issues, budget) {
  if (!node || typeof node !== 'object' || Array.isArray(node)) { issues.push({ path, problem: '不是对象' }); return }
  if (node.type !== undefined) {
    if (typeof node.type === 'string') {
      const known = V1_TYPES.has(node.type) || V2_TYPES.has(node.type)
      if (!known) issues.push({ path, problem: `不认识的组件 type="${node.type}"（会画成灰色占位）` })
      if (budget === 'toolCard' && !TOOLCARD_TYPES.has(node.type)) {
        issues.push({ path, problem: `工具卡不能用 "${node.type}"（只能用 stack/row/text/metric/chart/compare/steps/callout）` })
      }
    } else {
      issues.push({ path, problem: 'type 不能写绑定' })
    }
  } else if (path === 'root') {
    issues.push({ path, problem: '根节点缺 type' })
  }
  if (typeof node.icon === 'string' && !ICONS.has(node.icon)) issues.push({ path, problem: `不认识的图标 "${node.icon}"（会画默认图标）` })
  if (node.tone !== undefined && !['good', 'warn', 'bad', 'info', 'muted', 'success', 'warning', 'danger', 'error', 'note', 'secondary'].includes(node.tone)) {
    issues.push({ path, problem: `不认识的 tone "${node.tone}"` })
  }
  // 关键必填属性
  if (node.type === 'text' && typeof node.text !== 'string' && !node.text?.$) issues.push({ path, problem: 'text 组件缺 text' })
  if (node.type === 'metric' && node.value === undefined) issues.push({ path, problem: 'metric 缺 value' })
  if (node.type === 'progress' && node.value === undefined) issues.push({ path, problem: 'progress 缺 value' })
  if (node.type === 'chart') {
    const total = (node.series || []).reduce((s, x) => s + (Array.isArray(x.values) ? x.values.length : 0), 0)
    if (total > 60) issues.push({ path, problem: `chart 数据点 ${total} > 60` })
    if ((node.series || []).length > 6) issues.push({ path, problem: `chart 组数 ${node.series.length} > 6` })
  }
  if (node.type === 'list' && Array.isArray(node.items) && node.items.length > 50) issues.push({ path, problem: `list ${node.items.length} 行 > 50` })
  if (node.type === 'keyValue' && Array.isArray(node.items) && node.items.length > 24) issues.push({ path, problem: `keyValue ${node.items.length} 项 > 24` })
  if (node.type === 'table') {
    if (Array.isArray(node.columns) && node.columns.length > 8) issues.push({ path, problem: `table ${node.columns.length} 列 > 8` })
    if (Array.isArray(node.rows) && node.rows.length > 50) issues.push({ path, problem: `table ${node.rows.length} 行 > 50` })
  }
  if (node.type === 'actions') {
    const n = (node.items || []).length
    if (n > 4) issues.push({ path, problem: `actions ${n} 个 > 4` })
    if ((node.items || []).filter((x) => x.style === 'primary').length > 1) issues.push({ path, problem: 'actions 最多一个 primary' })
  }
  if (node.type === 'options' && Array.isArray(node.options) && node.options.length > 8) issues.push({ path, problem: `options ${node.options.length} 个 > 8` })
  if (node.type === 'steps' && Array.isArray(node.items) && node.items.length > 12) issues.push({ path, problem: `steps ${node.items.length} 项 > 12` })
  if (node.type === 'compare') {
    const n = (node.items || []).length
    if (n < 2 || n > 4) issues.push({ path, problem: `compare 需要 2–4 项，当前 ${n}` })
  }
  if (node.type === 'image' && typeof node.src === 'string'
    && !node.src.startsWith('asset:') && !node.src.startsWith('data:image/')) {
    issues.push({ path, problem: 'image.src 只收 asset: 或 data:image/…' })
  }
  // 递归
  const kids = []
  if (Array.isArray(node.children)) node.children.forEach((c, i) => kids.push({ c, p: `${path}.children[${i}]` }))
  if (Array.isArray(node.items)) node.items.forEach((c, i) => { if (c && typeof c === 'object' && typeof c.type === 'string') kids.push({ c, p: `${path}.items[${i}]` }) })
  for (const k of kids) checkNode(k.c, k.p, issues, budget)
}

export function validate(view, budget = 'panel') {
  const b = BUDGET[budget] || BUDGET.panel
  const issues = []
  checkNode(view, 'root', issues, budget)
  const json = JSON.stringify(view)
  const bytes = Buffer.byteLength(json, 'utf8')
  const { n, maxDepth } = countNodes(view)
  if (bytes > b.bytes) issues.push({ path: 'root', problem: `视图 ${bytes}B 超过 ${b.bytes}B 上限（整块降级）` })
  if (n > b.nodes) issues.push({ path: 'root', problem: `节点 ${n} > ${b.nodes}` })
  if (maxDepth > b.depth) issues.push({ path: 'root', problem: `层数 ${maxDepth} > ${b.depth}` })
  return { ok: issues.length === 0, bytes, nodes: n, depth: maxDepth, budget: b, issues }
}

// ── 语义小工具 ──────────────────────────────────────────────────────────────
/** 心情 0–100 → 五档（阈值照抄 dsh-feiyu-mood README）*/
export function moodBand(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return { label: '不知道', icon: 'question', tone: 'muted' }
  if (n >= 80) return { label: '欢喜', icon: 'sparkle', tone: 'good' }
  if (n >= 62) return { label: '开心', icon: 'heart', tone: 'good' }
  if (n >= 42) return { label: '一般', icon: 'chat', tone: 'info' }
  if (n >= 25) return { label: '心疼', icon: 'warning', tone: 'warn' }
  return { label: '难过', icon: 'warning', tone: 'bad' }
}

// ⚠️ 2026-11-01 修正（对着真实 state.json 复核）：上游 dsh-feiyu-mood 的七维是
//   happy / calm / sweet / heartache / worry / sad / jealous。
//   这里原来写成 steady / tender —— 那两个键上游**从来不发**，于是「踏实」永远 0、
//   「心疼」被安到 tender 上，真机上表现为「明明心疼 7，面板一根柱子都没有」。


function relTime(ts, now = Date.now()) {
  const t = Number(ts)
  if (!t) return '时间未知'
  const d = now - t
  if (d < 0) return '待会儿'
  if (d < 60000) return '刚刚'
  if (d < 3600000) return `${Math.max(1, Math.round(d / 60000))} 分钟前`
  if (d < 86400000) return `${Math.max(1, Math.round(d / 3600000))} 小时前`
  return `${Math.max(1, Math.round(d / 86400000))} 天前`
}

const CLARITY_LABEL = { vivid: '清晰', clear: '清楚', fuzzy: '模糊', trace: '只剩印象' }
const CLARITY_TONE = { vivid: 'good', clear: 'info', fuzzy: 'muted', trace: 'warn' }

// ── 面板 1：心情 ────────────────────────────────────────────────────────────
/**
 * @param {object} s  state.json 里的一份 mood：{ mood, dims, timeline:[{at, delta, reason}] }
 * @param {object} c  所属 slot：{ label, selfName, ownerName, moodBase }
 */
export function moodPanel(s = {}, c = {}) {
  const mood = Number(s.mood ?? s.value ?? c.moodBase ?? 70)
  const band = moodBand(mood)
  const dims = s.dims || s.emotions || {}
  const tl = Array.isArray(s.timeline) ? s.timeline.slice(-6).reverse() : []
  const dimRows = Object.entries(DIM_LABEL)
    .map(([k, label]) => ({ label, value: Math.round((Number(dims[k]) || 0) * 10) / 10 }))
    .filter((r) => r.value !== 0)
  const last = tl[0]

  const children = [
    // 绑定对不上时，警告必须在**最上面** —— 底下所有数字都要按「可能不是这份」来读
    ...(c.bindingWarning ? [{ type: 'callout', tone: 'warn', text: String(c.bindingWarning).slice(0, 400) }] : []),
    {
      type: 'row',
      children: [
        { type: 'metric', label: '此刻心情', value: Math.round(mood), tone: band.tone, caption: band.label },
        { type: 'metric', label: '更新时间', value: last ? relTime(last.at) : '还没判过', caption: c.ownerName ? `对 ${c.ownerName}` : undefined },
      ],
    },
  ]

  if (dimRows.length) {
    children.push({
      type: 'chart',
      kind: 'bar',
      categories: dimRows.map((r) => r.label),
      series: [{ name: '强度', values: dimRows.map((r) => r.value) }],
      caption: '七根情绪各自还剩多少（会自己衰减回 0）',
    })
  } else if (!tl.length) {
    // 只有在「七维都归零 **且** 时间线也空」时才说没波动 —— 否则会和下面的
    // 「最近几次」自相矛盾（真机上踩到过：七维已衰减到 0，但历史记录还在）
    children.push({ type: 'callout', tone: 'info', text: '这几轮没有情绪波动 —— 无变化不记账，是设计不是坏。' })
  }

  if (tl.length) {
    children.push({ type: 'text', text: '**最近几次**', tone: 'muted' })
    children.push({
      type: 'list',
      items: tl.map((e) => ({
        title: String(e.reason || e.purpose || '（没写理由）').slice(0, 60),
        subtitle: relTime(e.at),
        trailing: `${Number(e.delta) >= 0 ? '+' : ''}${Number(e.delta) || 0}`,
        tone: Number(e.delta) > 0 ? 'good' : Number(e.delta) < 0 ? 'warn' : undefined,
      })),
    })
  }

  children.push({
    type: 'keyValue',
    items: [
      ['基线', String(c.moodBase ?? 70)],
      ['衰减半衰期', `${c.decayHalfHours ?? 3} 小时`],
      ['告诉它的方式', c.injectMode === 'off' ? '不告诉它' : c.injectMode === 'state' ? '只说心情' : '说刚才那件事'],
      ['可信度门槛', String(c.injectConfidence ?? 60)],
    ],
  })

  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'mood.refresh', icon: 'refresh', style: 'primary' },
      { label: '完整记录', action: 'mood.timeline', icon: 'time' },
    ],
  })

  return { v: 1, type: 'stack', title: `心情 ${Math.round(mood)} · ${band.label}`, children }
}

// ── 面板 2：记忆 ────────────────────────────────────────────────────────────
/**
 * @param {object} s { records:[{ id, text, title, clarity, importance, createdAt, tags, pinned, isInitial, firstMet }],
 *                     status:{ chars, warnChars, percent, level, counts } }
 */
export function memoryPanel(s = {}) {
  const records = Array.isArray(s.records) ? s.records : []
  const st = s.status || {}
  const children = []

  if (!records.length) {
    children.push({ type: 'callout', tone: 'info', text: '还没有记忆。聊几句、说过一件明确的事，它就会自己记下来。' })
    return { v: 1, type: 'stack', title: '记忆 · 空', children }
  }

  // 容量：**只在真的读到数字时**才画进度条。
  // 读不到就画一条「0 字」是错的 —— 用户会以为记忆是空的（真机上踩到过：
  // fish-memory 的字段名在各版本间不稳定，status 可能根本不存在）。
  const hasCap = Number.isFinite(Number(st.chars)) && Number(st.chars) > 0
  const percent = Number(st.percent ?? 0)
  if (hasCap) {
    children.push({
      type: 'progress',
      value: Math.min(100, Math.round(percent)),
      max: 100,
      label: '记忆卡容量',
      tone: percent >= 90 ? 'bad' : percent >= 80 ? 'warn' : 'good',
      caption: st.warnChars ? `${st.chars} / ${st.warnChars} 字` : `${st.chars} 字`,
    })
  }

  // 分组：初次相遇 > 永不遗忘 > 最初 > 和主人 > 最近 > 正在模糊
  const groups = [
    ['初次相遇', records.filter((r) => r.firstMet)],
    ['永远不会忘的', records.filter((r) => r.pinned && !r.firstMet)],
    ['最初的日子', records.filter((r) => r.isInitial && !r.pinned)],
    ['和主人的记忆', records.filter((r) => (r.tags || []).includes('owner') && !r.isInitial && !r.pinned)],
    ['正在模糊的事', records.filter((r) => r.clarity === 'fuzzy' || r.clarity === 'trace')],
  ]
  const rest = records.filter((r) => !groups.some(([, g]) => g.includes(r)))

  const rowOf = (r) => ({
    title: String(r.title || r.text || '').slice(0, 40),
    subtitle: `${CLARITY_LABEL[r.clarity] || '清楚'} · 重要 ${r.importance ?? '?'}`,
    trailing: relTime(Date.parse(r.createdAt) || 0),
    icon: r.pinned ? 'pin' : r.isInitial ? 'sparkle' : 'note',
    tone: CLARITY_TONE[r.clarity],
    action: `memory.open`, // 点开看原文（action 会让行带箭头）
  })

  for (const [name, list] of groups) {
    if (!list.length) continue
    children.push({ type: 'text', text: `**${name}**（${list.length}）`, tone: 'muted' })
    children.push({ type: 'list', items: list.slice(0, 12).map(rowOf) })
  }
  if (rest.length) {
    children.push({ type: 'text', text: `**最近记得的**（${rest.length}）`, tone: 'muted' })
    children.push({ type: 'list', items: rest.slice(0, 20).map(rowOf) })
  }

  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'memory.refresh', icon: 'refresh', style: 'primary' },
      { label: '全量存档', action: 'memory.full', icon: 'archive' },
      { label: '导出', action: 'memory.export', icon: 'download' },
    ],
  })

  const c = st.counts || {}
  return {
    v: 1,
    type: 'stack',
    title: `记忆 ${records.length} 条 · ${st.percent ?? 0}%`,
    children,
    // 注：不写成 keyValue 计数块，面板已经很挤；计数放进 title 就够
    _counts: c,
  }
}

// ── 面板 3：目的（主动找我）──────────────────────────────────────────────────
/**
 * @param {object} s purpose-outreach.json：{ countToday, dailyMax, nextFireAt, drives, goals, pending, history, lastOutcome }
 */
export function purposePanel(s = {}) {
  const children = []
  const drives = s.drives || {}
  const NEED = { play: '想玩', learn: '想学', install: '想装', connect: '想陪', care: '关心', share: '分享', identity: '自我', create: '创作', consume: '体验' }
  const top = Object.entries(NEED)
    .map(([k, label]) => ({ label, value: Math.round((Number(drives[k]) || 0) * 100) }))
    .sort((a, b) => b.value - a.value)
    .slice(0, 5)

  const next = Number(s.nextFireAt) || 0
  children.push({
    type: 'row',
    children: [
      { type: 'metric', label: '今天说过', value: `${s.countToday ?? 0}/${s.dailyMax ?? 2}`, caption: '主动找你的次数' },
      { type: 'metric', label: '下次', value: next ? relTime(next).replace('前', '后') : '未排', caption: s.lastOutcome || '' },
    ],
  })

  if (top.length && top.some((t) => t.value > 0)) {
    children.push({
      type: 'chart',
      kind: 'hbar',
      categories: top.map((t) => t.label),
      series: [{ name: '现在最想要', values: top.map((t) => t.value) }],
      unit: '%',
      caption: '这几个念头会随时间回落到默认值，也会因为看到的东西被推高',
    })
  }

  const goals = Array.isArray(s.goals) ? s.goals.slice(0, 4) : []
  if (goals.length) {
    children.push({ type: 'text', text: '**它惦记着的事**', tone: 'muted' })
    children.push({
      type: 'list',
      items: goals.map((g) => ({
        title: String(g.intent || g.text || '').slice(0, 50),
        subtitle: `${g.ownerLabel || g.owner || '自己'} · ${g.action || '?'} · 把握 ${Math.round((Number(g.confidence) || 0) * 100)}%`,
        trailing: (Number(g.risk) || 0) >= 0.5 ? '风险高' : '',
        tone: (Number(g.risk) || 0) >= 0.5 ? 'warn' : undefined,
      })),
    })
  } else {
    children.push({ type: 'callout', tone: 'info', text: '还没形成想做的事 —— 素材不够时它宁可不硬找话。' })
  }

  const pending = Array.isArray(s.pending) ? s.pending.slice(-3) : []
  if (pending.length) {
    children.push({ type: 'text', text: '**没说完的**', tone: 'muted' })
    children.push({ type: 'list', items: pending.map((p) => ({ title: String(p.text || '').slice(0, 50), icon: 'clock', trailing: relTime(p.createdAt) })) })
  }

  const actions = {
    type: 'actions',
    items: [
      { label: '现在说一句', action: 'purpose.run', icon: 'send', style: 'primary' },
      { label: '先看看', action: 'purpose.preview', icon: 'search' },
    ],
  }
  children.push(actions)

  return { v: 1, type: 'stack', title: `目的 · 今天 ${s.countToday ?? 0}/${s.dailyMax ?? 2}`, children }
}

/**
 * 人格与目的 (硬化轮): who she is right now, what she is after, and what she would rather talk
 * about — read from partner's own files, never a fixed paragraph.
 *
 * @param {object} o
 * @param {object|null} o.persona  loadPersona() 的结果：{ title, text, source, missing, unreadable }
 * @param {object} o.cfg          partner 配置（persona.style / persona.topics / 三个模块开关）
 * @param {object|null} o.role    roleState()：{ band, label, ... }
 * @param {object|null} o.purpose purpose-outreach.json（可为 null：那时只画身份与偏好）
 */
export function identityPanel(o = {}) {
  const cfg = o.cfg || {}
  const pcfg = cfg.persona || {}
  const persona = o.persona || null
  const role = o.role || null
  const children = []

  // 1. 身份：读到什么就说什么，读不到如实报是哪种读不到（missing / unreadable / empty）
  if (persona && persona.text) {
    children.push({
      type: 'list',
      items: [
        {
          title: String(persona.title || '她').slice(0, 40),
          subtitle: `来自 ${persona.source || '（未知来源）'} · ${String(persona.text).length} 字`,
          icon: 'pet',
        },
      ],
    })
    children.push({ type: 'text', text: String(persona.text).slice(0, 600), tone: 'muted' })
  } else if (persona && persona.missing) {
    children.push({
      type: 'callout',
      tone: 'warn',
      text: `没找到人格文件（找过：${persona.missing}）。她不会用人造人设顶上 —— 放一份 persona.md 就生效。`,
    })
  } else if (persona && persona.unreadable) {
    children.push({ type: 'callout', tone: 'warn', text: `人格文件读不了：${String(persona.unreadable).slice(0, 120)}` })
  } else {
    children.push({ type: 'callout', tone: 'info', text: '人格文件是空的。默认不编，宁可没有。' })
  }

  // 2. 角色状态
  if (role && role.label) {
    children.push({
      type: 'row',
      children: [
        { type: 'metric', label: '状态', value: String(role.label), caption: role.hint || '' },
        { type: 'metric', label: '记忆', value: String(role.memories ?? 0), caption: '条长期记忆' },
      ],
    })
  }

  // 3. 目标与主动话题偏好：都是配置里的，不是写死的
  const goals = Array.isArray(o.purpose?.goals) ? o.purpose.goals.slice(0, 4) : []
  if (goals.length) {
    children.push({ type: 'text', text: '**她想做的事**', tone: 'muted' })
    children.push({
      type: 'list',
      items: goals.map((g) => ({
        title: String(g.intent || g.text || '').slice(0, 50),
        subtitle: `把握 ${Math.round((Number(g.confidence) || 0) * 100)}%`,
      })),
    })
  }

  const topics = Array.isArray(pcfg.topics) ? pcfg.topics.filter((t) => String(t).trim()) : []
  children.push({ type: 'text', text: '**主动话题偏好**', tone: 'muted' })
  if (topics.length) {
    children.push({ type: 'list', items: topics.slice(0, 8).map((t) => ({ title: String(t).slice(0, 40), icon: 'chat' })) })
  } else {
    children.push({
      type: 'callout',
      tone: 'info',
      text: '还没设偏好 —— 在 ~/.dsh/partner/config.json 的 persona.topics 里加几条，她主动开口时会偏向这些。',
    })
  }

  const style = String(pcfg.style || '').trim()
  if (style) {
    children.push({ type: 'text', text: '**表达风格**', tone: 'muted' })
    children.push({ type: 'text', text: style.slice(0, 300), tone: 'muted' })
  }

  // 4. 开关状态：三个模块互相独立，这里如实显示谁开着
  const on = [
    ['人格', cfg.persona?.enabled !== false],
    ['记忆', cfg.memory?.enabled !== false],
    ['情绪', cfg.mood?.enabled !== false],
    ['工具', cfg.tools?.enabled !== false],
  ]
  children.push({
    type: 'list',
    items: on.map(([label, enabled]) => ({
      title: String(label),
      trailing: enabled ? '开' : '关',
      tone: enabled ? undefined : 'muted',
    })),
  })

  children.push({
    type: 'actions',
    items: [{ label: '刷新', action: 'identity.refresh', icon: 'refresh' }],
  })

  return { v: 1, type: 'stack', title: '人格 · 记忆 · 情绪', children }
}

// ── 状态条（emit 的 data，2KiB / 8 节点 / 3 层）──────────────────────────────
export function moodStrip(s = {}, c = {}) {
  const mood = Number(s.mood ?? c.moodBase ?? 70)
  const band = moodBand(mood)
  // 状态条 text ≤ 24 字
  return { type: 'stack', children: [{ type: 'text', text: `${band.label} ${Math.round(mood)}` }] }
}

export function stripData(s = {}, c = {}) {
  const mood = Number(s.mood ?? c.moodBase ?? 70)
  const band = moodBand(mood)
  return { text: `${band.label} ${Math.round(mood)}`, icon: band.icon, tone: band.tone }
}

// ── 工具卡：remember 的结果（只用最稳的 8 个组件）───────────────────────────
export function rememberCard(args = {}, value = {}) {
  const okFlag = value && value.ok !== false
  const text = String(args.text || '').slice(0, 200)
  const children = [
    {
      type: 'callout',
      tone: okFlag ? 'good' : 'warn',
      text: okFlag ? '记住了' : `没记住：${String(value?.message || '原因不明')}`,
    },
    { type: 'text', text },
  ]
  const facts = []
  if (args.importance !== undefined) facts.push(`重要度 ${args.importance}`)
  if (Array.isArray(args.tags) && args.tags.length) facts.push(`标签 ${args.tags.slice(0, 5).join('、')}`)
  if (args['事实键']) facts.push(`事实键 \`${args['事实键']}\``)
  if (args['取代']) facts.push(`取代了「${String(args['取代']).slice(0, 30)}」`)
  // ⚠️ 工具卡白名单只有 8 个组件（stack/row/text/metric/chart/compare/steps/callout），**没有 keyValue**
  //    compare 要求 2–4 张卡（不是 2–4 行），一两条事实用它反而画不出来 → 用一行 text + 分隔符最稳
  if (facts.length) children.push({ type: 'text', text: facts.join(' · '), tone: 'muted' })
  return { v: 1, type: 'stack', title: text.slice(0, 30) || '记忆', children }
}

// ── 自测 ────────────────────────────────────────────────────────────────────
function selftest() {
  const now = Date.now()
  const fakeMood = {
    mood: 72.4,
    dims: { happy: 0.6, steady: 0.3, sweet: 0.2, tender: 0.1, worry: 0, sad: 0, jealous: 0.05 },
    timeline: [
      { at: now - 60000, delta: 6, reason: '被夸了一句，心里飘了一下' },
      { at: now - 3600000, delta: -3, reason: '他说在忙，有点小失落' },
      { at: now - 7200000, delta: 0, reason: '' },
    ],
  }
  const fakeCfg = { label: '大肥鱼', selfName: '大肥鱼', ownerName: '小明', moodBase: 70, decayHalfHours: 3, injectMode: 'event', injectConfidence: 60 }
  const fakeMem = {
    records: [
      { id: 'a', text: '我最怕吵，写代码时别打断我', clarity: 'vivid', importance: 82, createdAt: new Date(now - 86400000).toISOString(), tags: ['owner', '偏好'], pinned: true, firstMet: true },
      { id: 'b', text: '答应过周末一起看那部片子', clarity: 'clear', importance: 70, createdAt: new Date(now - 3 * 86400000).toISOString(), tags: ['owner'] },
      { id: 'c', text: '随口提过想学吉他', clarity: 'fuzzy', importance: 35, createdAt: new Date(now - 20 * 86400000).toISOString(), tags: [] },
      { id: 'd', text: '第一次聊天是在一个下雨的晚上', clarity: 'vivid', importance: 100, createdAt: new Date(now - 40 * 86400000).toISOString(), tags: ['owner'], isInitial: true },
    ],
    status: { chars: 1840, warnChars: 60000, percent: 3.1, level: 0, counts: { total: 4, owner: 3 } },
  }
  const fakePurpose = {
    countToday: 1, dailyMax: 2, nextFireAt: now + 5400000, lastOutcome: 'sent',
    drives: { connect: 0.65, play: 0.28, learn: 0.12, care: 0.22, share: 0.2 },
    goals: [
      { intent: '想知道他面试后来怎么样了', ownerLabel: '主人', action: 'ask', confidence: 0.78, risk: 0.05 },
      { intent: '看到「养鱼游戏」，想拉他一起玩', ownerLabel: '群友', action: 'invite', confidence: 0.49, risk: 0.1 },
    ],
    pending: [{ text: '面试结果出来再问一次', createdAt: now - 86400000 }],
  }

  const cases = [
    ['心情面板', moodPanel(fakeMood, fakeCfg), 'panel'],
    ['记忆面板', memoryPanel(fakeMem), 'panel'],
    ['目的面板', purposePanel(fakePurpose), 'panel'],
    ['状态条视图', moodStrip(fakeMood, fakeCfg), 'strip'],
    ['remember 工具卡', rememberCard({ text: '我最怕吵，写代码时别打断我', importance: 0.8, tags: ['偏好', '工作'], 事实键: '她.工作习惯' }, { ok: true }), 'toolCard'],
    ['recall 工具卡', recallCard({ query: '散热' }, { ok: true, query: '散热', count: 1, total: 4, reason: '', items: [{ id: 'a', text: '今天想试试新的散热硅脂', score: 0.83, clarity: 'vivid', origin: 'fish-memory', at: new Date(now).toISOString() }] }), 'toolCard'],
    ['mood 工具卡', moodCard({}, { ok: true, reason: '', mood: 69.9, stored: 69.8, band: '开心', dims: [{ key: 'heartache', label: '心疼', value: 7 }, { key: 'worry', label: '担心', value: 5 }], changes: [{ at: new Date(now).toISOString(), delta: -2, reason: '他说被领导骂了' }] }), 'toolCard'],
    ['correct 工具卡', correctCard({ action: 'hide' }, { ok: true, message: '这条藏起来了。' }), 'toolCard'],
    ['人格面板', personaPanel({ ok: true, from: 'surfcore', file: 'C:/x/.dsh/surfcore/characters/c1/persona.md', text: '你是「大肥鱼」。\n\n【形象与性格】\n贪吃。\n\n【聊天节奏】\n短句。', body: '…', identity: '## 自我身份\n', identityFrom: 'surfcore', names: { selfName: '大肥鱼', ownerName: '鱼片' }, style: '短句为主', topics: ['吃了什么', '在追的番'] }, { daysTogether: 21, memoryCount: 4, facts: 2, firstMetAt: now - 21 * 86400000, mood: { value: 70, label: '开心', tone: 'good' } }), 'panel'],
    ['记忆分段面板', memorySectionsPanel({ primary: { file: 'C:/x/.dsh/fish-memory/session-s1.json', shape: 'fish-memory', records: new Array(4).fill({}), problems: [] }, card: { file: 'C:/x/.dsh/surfcore/characters/c1/memory/memory-card.json', shape: 'surfcore-card', count: 1, problems: [] }, own: { count: 0, file: 'C:/x/.dsh/partner/memory-own.json' }, all: fakeMem.records.concat([{ id: 'x', text: '今天想试试新的散热硅脂', clarity: 'vivid', importance: 30, at: now - 3600000, origin: 'fish-memory', tags: [], pinned: false }]), shortTerm: [{ id: 'x', text: '今天想试试新的散热硅脂', clarity: 'vivid', importance: 30, at: now - 3600000, origin: 'fish-memory', tags: [], pinned: false }], facts: fakeMem.records.slice(0, 2), hiddenCount: 1, corrections: { count: 2, file: 'C:/x/.dsh/partner/memory-corrections.json' } }, { memory: { shortTermHours: 24, panelLimit: 40 } }), 'panel'],
    ['记忆单条面板', memoryRowPanel({ ...fakeMem.records[0], origin: 'fish-memory', note: '她说过两次', rewritten: false }, null), 'panel'],
    ['情绪状态面板', moodStatePanel({ ok: true, reason: '', slot: { id: '__default__' }, matched: true, keys: ['__default__'], mood: { mood: 69.8, saidAt: [now] }, current: { value: 70, from: 69.8, hours: 2, applied: true }, dims: [{ key: 'heartache', label: '心疼', value: 7 }, { key: 'worry', label: '担心', value: 5 }, { key: 'sad', label: '难过', value: 4 }], timeline: [{ at: now - 60000, mood: 68, delta: -2, reason: '他说被领导当众骂了', conf: 88 }], cfg: { moodBase: 70, decayHalfHours: 12, injectMode: 'event', injectConfidence: 55 } }, { mood: { timelineLimit: 8 } }), 'panel'],
  ]

  let failed = 0
  const paint = (c, s) => (process.stdout.isTTY ? `\u001b[${c}m${s}\u001b[0m` : s)
  for (const [name, view, budget] of cases) {
    // 去掉内部字段（下划线开头）再校验，避免被当成未知属性
    const clean = JSON.parse(JSON.stringify(view, (k, v) => (k.startsWith('_') ? undefined : v)))
    const r = validate(clean, budget)
    const head = `${name.padEnd(16)} ${String(r.bytes).padStart(6)}B / ${r.budget.bytes}B  节点 ${String(r.nodes).padStart(3)}/${r.budget.nodes}  层 ${r.depth}/${r.budget.depth}`
    if (r.ok) console.log('  ' + paint(32, '✓') + ' ' + head)
    else {
      failed += 1
      console.log('  ' + paint(31, '✗') + ' ' + head)
      for (const i of r.issues) console.log('      → ' + i.path + '：' + i.problem)
    }
  }
  console.log('')
  console.log(failed ? paint(31, `  ${failed} 个视图不合格`) : paint(32, '  全部视图在预算内，可以交给 App 渲染'))
  process.exit(failed ? 1 : 0)
}

if (process.argv[1] && process.argv[1].endsWith('views.mjs')) selftest()

// ═══════════════════════════════════════════════════════════════════════════
// R4 追加：人格面板 + 四张工具卡 + 记忆面板的「短期/长期/事实」分段
// ═══════════════════════════════════════════════════════════════════════════

export function relTimeOf(ts, now) {
  return relTime(ts, now)
}

/** 人格面板：真实读到的文件 / 身份 / 风格 / 话题 / 角色状态。读不到就明说读不到。 */
export function personaPanel(p, state) {
  const s = state || {}
  const children = []
  if (!p || (!p.ok && !p.identity)) {
    children.push({
      type: 'callout',
      tone: 'warn',
      text: '没找到人格卡。找过这些位置：' + ((p && p.tried) || []).slice(0, 3).join(' · ') + '。放一份 persona.md 到 ~/.dsh/partner/ 就会出现在这里。',
    })
    children.push({ type: 'text', text: '**不会**用默认人设顶上 —— 编一个性格给你看，比空白更糟。', tone: 'muted' })
    return { v: 1, type: 'stack', title: '人格 · 没有卡', children }
  }
  const name = (p.names && p.names.selfName) || ''
  const owner = (p.names && p.names.ownerName) || ''
  const kv = []
  if (name) kv.push(['她叫', name])
  if (owner) kv.push(['你叫', owner])
  kv.push(['人格卡', shortPath(p.file)])
  kv.push(['来源', p.from === 'partner' ? '~/.dsh/partner/persona.md' : p.from === 'surfcore' ? 'surfcore 当前角色' : '配置指定'])
  if (p.identityFrom) kv.push(['身份卡', p.identityFrom])
  kv.push(['字数', String((p.text || '').length)])
  children.push({ type: 'keyValue', items: kv })

  if (!p.ok) {
    children.push({ type: 'callout', tone: 'warn', text: p.reason === 'empty-persona' ? '人格卡是空文件。' : '人格卡读不出来。' })
  }

  // 角色状态：全部从记忆和心情派生，不另存一份
  const bits = []
  if (s.daysTogether !== null && s.daysTogether !== undefined) bits.push({ type: 'metric', label: '认识', value: s.daysTogether + ' 天', caption: s.firstMetAt ? relTime(s.firstMetAt).replace('前', '前第一次') : '' })
  if (s.memoryCount !== undefined) bits.push({ type: 'metric', label: '记得', value: s.memoryCount + ' 条', caption: s.facts ? '其中 ' + s.facts + ' 条重要' : '' })
  if (s.mood) bits.push({ type: 'metric', label: '此刻', value: Math.round(s.mood.value), tone: s.mood.tone, caption: s.mood.label })
  if (bits.length) children.push({ type: 'row', children: bits })

  if (p.style && p.style.trim()) {
    children.push({ type: 'text', text: '**表达风格**', tone: 'muted' })
    children.push({ type: 'text', text: p.style.trim().slice(0, 600) })
  }
  if (p.topics && p.topics.length) {
    children.push({ type: 'text', text: '**主动开口时偏爱**', tone: 'muted' })
    children.push({ type: 'list', items: p.topics.slice(0, 8).map((t) => ({ title: String(t).slice(0, 40), icon: 'chat' })) })
  }
  // 正文摘录：只给前几节标题 + 一小段，全文去网页/文件里看
  if (p.text) {
    const heads = (p.text.match(/^【[^】]{1,16}】|^##\s+.{1,20}/gm) || []).slice(0, 8).map((h) => h.replace(/^##\s+/, ''))
    if (heads.length) {
      children.push({ type: 'text', text: '**人格卡分节**', tone: 'muted' })
      children.push({ type: 'list', items: heads.map((h) => ({ title: h, icon: 'document' })) })
    }
    children.push({ type: 'text', text: p.text.replace(/\s+/g, ' ').slice(0, 300) + '…', tone: 'muted' })
  }

  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'persona.refresh', icon: 'refresh', style: 'primary' },
      { label: '看全文', action: 'persona.open', icon: 'document' },
    ],
  })
  return { v: 1, type: 'stack', title: '人格 · ' + (name || '未命名'), children }
}

function shortPath(p) {
  const s = String(p || '')
  const i = s.indexOf('.dsh')
  return i >= 0 ? '~' + s.slice(i + 4).replace(/\\/g, '/') : s
}

// ── 四张工具卡（只用工具卡白名单的 8 个组件）────────────────────────────────

/** partner_recall 的结果卡 */
export function recallCard(args, value) {
  const v = value || {}
  const q = String((args && args.query) || v.query || '').slice(0, 40)
  if (v.ok === false) {
    return { v: 1, type: 'stack', title: '没查到', children: [{ type: 'callout', tone: 'warn', text: String(v.reason || '原因不明').slice(0, 200) }] }
  }
  const items = Array.isArray(v.items) ? v.items : []
  const children = []
  if (!items.length) {
    children.push({ type: 'callout', tone: 'info', text: '记忆里没有关于「' + q + '」的内容。本地一共 ' + (v.total || 0) + ' 条。' })
  } else {
    children.push({ type: 'text', text: '找到 ' + items.length + ' 条（本地共 ' + (v.total || 0) + ' 条）', tone: 'muted' })
    for (const it of items.slice(0, 5)) {
      children.push({ type: 'text', text: '· ' + String(it.text || '').slice(0, 120) })
      const bits = ['匹配 ' + Math.round((Number(it.score) || 0) * 100) + '%']
      if (it.clarity) bits.push(CLARITY_LABEL[it.clarity] || it.clarity)
      if (it.origin) bits.push(String(it.origin).split(':')[0])
      children.push({ type: 'text', text: bits.join(' · '), tone: 'muted' })
    }
  }
  return { v: 1, type: 'stack', title: '记忆检索 · ' + (q || '（空）'), children }
}

/** partner_correct 的结果卡 */
export function correctCard(args, value) {
  const v = value || {}
  const act = String((args && args.action) || v.action || '')
  const label = { hide: '藏起来', show: '放回来', pin: '钉住', unpin: '取消钉住', rewrite: '改写', note: '写备注' }[act] || act
  return {
    v: 1,
    type: 'stack',
    title: '记忆 · ' + label,
    children: [
      { type: 'callout', tone: v.ok === false ? 'warn' : 'good', text: String(v.message || '').slice(0, 200) },
      { type: 'text', text: '只改了 partner 自己的纠正层；上游记忆文件一个字没动。', tone: 'muted' },
    ],
  }
}

/** partner_mood 的结果卡 */
export function moodCard(args, value) {
  const v = value || {}
  if (v.ok === false) {
    return { v: 1, type: 'stack', title: '心情 · 读不到', children: [{ type: 'callout', tone: 'warn', text: '原因：' + String(v.reason || '未知') }] }
  }
  const dims = (Array.isArray(v.dims) ? v.dims : []).filter((d) => Number(d.value) > 0).slice(0, 7)
  const children = [
    { type: 'metric', label: '此刻', value: Math.round(Number(v.mood) || 0), caption: String(v.band || ''), tone: moodBand(v.mood).tone },
  ]
  if (dims.length) {
    children.push({
      type: 'chart',
      kind: 'bar',
      categories: dims.map((d) => String(d.label)),
      series: [{ name: '强度', values: dims.map((d) => Math.round(Number(d.value) * 10) / 10) }],
    })
  }
  const changes = Array.isArray(v.changes) ? v.changes : []
  for (const c of changes.slice(0, 3)) {
    children.push({ type: 'text', text: (Number(c.delta) >= 0 ? '+' : '') + Number(c.delta) + ' ' + String(c.reason || '').slice(0, 80), tone: 'muted' })
  }
  if (v.reason) children.push({ type: 'callout', tone: 'warn', text: String(v.reason).slice(0, 200) })
  return { v: 1, type: 'stack', title: '心情 · ' + String(v.band || '') + ' ' + Math.round(Number(v.mood) || 0), children }
}

/** 通用失败卡：工具没干活时也得说人话，不能空白。 */
export function errorCard(title, message) {
  return { v: 1, type: 'stack', title: String(title || '出错了').slice(0, 30), children: [{ type: 'callout', tone: 'warn', text: String(message || '原因不明').slice(0, 200) }] }
}

// ── 记忆面板（真实数据版）：短期 / 长期 / 重要事实 / 纠正 ────────────────────
/**
 * @param {object} lt   loadLongTermFull(...) 的返回值
 * @param {object} cfg  partner 配置
 */
export function memorySectionsPanel(lt, cfg) {
  const all = (lt && lt.all) || []
  const children = []
  const primary = (lt && lt.primary) || {}

  // 数据来源必须写在最上面：出问题时第一件事就是「这数字是从哪个文件来的」
  const src = []
  if (primary.file) src.push(shortPath(primary.file) + '（' + (primary.shape || '?') + '，' + (primary.records || []).length + ' 条）')
  if (lt && lt.card && lt.card.file) src.push(shortPath(lt.card.file) + '（' + lt.card.shape + '，' + lt.card.count + ' 条）')
  if (lt && lt.own && lt.own.count) src.push(shortPath(lt.own.file) + '（partner 自己 ' + lt.own.count + ' 条）')
  if (!src.length) {
    children.push({
      type: 'callout',
      tone: 'warn',
      text: '没有找到任何记忆文件。装上 dsh-fish-memory 并聊几句，或让模型调 partner_remember 记一条，这里就会有东西。',
    })
    children.push({ type: 'text', text: '找过：~/.dsh/fish-memory/session-*.json。不会去读屏幕、窗口标题或剪贴板。', tone: 'muted' })
    return { v: 1, type: 'stack', title: '记忆 · 空', children }
  }
  children.push({ type: 'keyValue', items: [['数据来自', src.join('\n')]] })
  if (primary.problems && primary.problems.length) {
    children.push({ type: 'callout', tone: 'warn', text: primary.problems.join('；').slice(0, 300) })
  }
  if (primary.fellBackTo) {
    children.push({ type: 'callout', tone: 'info', text: '这个会话还没有自己的记忆文件，显示的是最近改过的 ' + String(primary.fellBackTo).slice(0, 8) + '…。' })
  }

  const shortHours = Number(cfg && cfg.memory ? cfg.memory.shortTermHours : 24) || 24
  const short = (lt && lt.shortTerm) || []
  const facts = ((lt && lt.facts) || []).filter((r) => r.fact || (r.importance !== null && r.importance >= 70) || r.pinned || r.firstMet).slice(0, 8)
  const limit = Math.max(5, Number(cfg && cfg.memory ? cfg.memory.panelLimit : 40) || 40)

  const rowOf = (r) => ({
    title: String(r.text || r.title || '').replace(/\s+/g, ' ').slice(0, 44),
    subtitle: [CLARITY_LABEL[r.clarity] || '清楚', r.importance === null ? null : '重要 ' + r.importance, r.origin ? String(r.origin).split(':')[0] : null, r.pinned ? '钉住' : null, r.rewritten ? '你改过' : null].filter(Boolean).join(' · '),
    trailing: relTime(r.at || 0),
    icon: r.pinned ? 'pin' : r.fact ? 'bookmark' : 'note',
    tone: CLARITY_TONE[r.clarity],
    action: { id: 'memory.row', args: { id: String(r.id).slice(0, 60) } },
  })

  if (short.length) {
    children.push({ type: 'text', text: '**短期（' + shortHours + ' 小时内，' + short.length + ' 条）**', tone: 'muted' })
    children.push({ type: 'list', items: short.slice(0, 8).map(rowOf) })
  }
  if (facts.length) {
    children.push({ type: 'text', text: '**重要事实（' + facts.length + ' 条）**', tone: 'muted' })
    children.push({ type: 'list', items: facts.map((r) => ({ ...rowOf(r), subtitle: (r.why || r.fact || '重要') + ' · ' + (CLARITY_LABEL[r.clarity] || '') })) })
  }
  const rest = all.filter((r) => !short.includes(r) && !facts.includes(r)).slice(0, limit)
  if (rest.length) {
    children.push({ type: 'text', text: '**长期（' + (all.length - short.length) + ' 条，下面是最久的 ' + rest.length + ' 条）**', tone: 'muted' })
    children.push({ type: 'list', items: rest.map(rowOf) })
  }

  const corr = (lt && lt.corrections) || {}
  const kv = []
  kv.push(['短期窗口', shortHours + ' 小时'])
  kv.push(['清晰度', '按上游 policy 的半衰期算，只看不改'])
  if (corr.count) kv.push(['你的纠正', corr.count + ' 处（' + shortPath(corr.file) + '）'])
  if (lt && lt.hiddenCount) kv.push(['已藏起', lt.hiddenCount + ' 条'])
  children.push({ type: 'keyValue', items: kv })

  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'memory.refresh', icon: 'refresh', style: 'primary' },
      { label: '藏起的', action: 'memory.hidden', icon: 'archive' },
      { label: '来源', action: 'memory.sources', icon: 'folder' },
    ],
  })
  return { v: 1, type: 'stack', title: '记忆 ' + all.length + ' 条 · 短期 ' + short.length, children }
}

/** 单条记忆详情（点列表行进来的） */
export function memoryRowPanel(rec, lt) {
  if (!rec) {
    return { v: 1, type: 'stack', title: '记忆 · 找不到', children: [{ type: 'callout', tone: 'warn', text: '这条记忆可能已经被上游删掉了，刷新一下。' }] }
  }
  const kv = [
    ['清晰度', CLARITY_LABEL[rec.clarity] || rec.clarity || '未知'],
    ['重要度', rec.importance === null ? '上游没给' : String(rec.importance)],
    ['来源', String(rec.origin || '')],
    ['记住于', relTime(rec.at || 0)],
  ]
  if (rec.fact) kv.push(['事实键', rec.fact])
  if (rec.tags && rec.tags.length) kv.push(['标签', rec.tags.join('、')])
  if (rec.note) kv.push(['你的备注', rec.note])
  if (rec.rewritten) kv.push(['被改写过', '是（原文仍在上游文件里）'])
  return {
    v: 1,
    type: 'stack',
    title: '记忆 · ' + String(rec.text).slice(0, 16),
    children: [
      { type: 'text', text: String(rec.text).slice(0, 1500) },
      { type: 'keyValue', items: kv },
      {
        type: 'actions',
        items: [
          { label: rec.pinned ? '取消钉住' : '钉住', action: rec.pinned ? 'memory.unpin' : 'memory.pin', icon: 'pin', style: 'primary' },
          { label: '藏起来', action: 'memory.hide', icon: 'archive' },
          { label: '返回', action: 'memory.refresh', icon: 'refresh' },
        ],
      },
      { type: 'text', text: '改这里是改 partner 的纠正层，上游文件不动 —— 想彻底删，去网页版设置里操作。', tone: 'muted' },
    ],
  }
}

// ── 情绪面板（真实数据版）：接 readMood() 的快照 ────────────────────────────
/**
 * @param {object} snap readMood() 的返回
 * @param {object} cfg  partner 配置
 */
export function moodStatePanel(snap, cfg) {
  if (!snap || !snap.ok) {
    const why = {
      'no-feiyu-mood': '没装 dsh-feiyu-mood，或者它的目录不在 ~/.dsh/feiyu-mood。',
      'no-state': '装是装了，但一次都没判过。确认「每轮判断心情」开着，并且真的聊过一轮。',
      'no-moods': 'state.json 里一份记录都没有：同上，聊一轮就会有。',
      'slot-mismatch': '设置里的 slot id 与 state.json 的键对不上，而且不止一份记录 —— 不猜，直说。到「鱼的心情」设置页重选一次会话。',
      'no-slot': '设置里没有任何一份心情配置。',
    }[snap && snap.reason] || '心情读不出来。'
    return {
      v: 1,
      type: 'stack',
      title: '心情 · 没有数据',
      children: [
        { type: 'callout', tone: 'warn', text: why },
        { type: 'text', text: '数据源：~/.dsh/feiyu-mood/state.json。本插件只读，不改它。', tone: 'muted' },
      ],
    }
  }
  const now = snap.current.value
  const band = moodBand(now)
  const children = []
  if (snap.matched === false) {
    children.push({
      type: 'callout',
      tone: 'warn',
      text: '设置里的会话与 state.json 的键对不上（设置：' + String(snap.slot && snap.slot.id) + '，记录：' + String((snap.keys || [])[0] || '').slice(0, 8) + '…）。现在显示的是唯一那份记录 —— 新对话可能不会进这一份。到「鱼的心情」设置页重选一次会话。',
    })
  }
  children.push({
    type: 'row',
    children: [
      { type: 'metric', label: '此刻', value: Math.round(now), tone: band.tone, caption: band.label },
      {
        type: 'metric',
        label: '记的值',
        value: Math.round(Number(snap.mood.mood) || 0),
        caption: snap.current.applied ? '已按 ' + snap.current.halfHours + ' 小时衰减' : '还没到衰减点',
      },
    ],
  })
  // 门槛 1（不是 >0）：上游的衰减残渣是 1.7e-7，用 >0 会画出七根 0 长的柱子。
  const dims = (snap.dims || []).filter((d) => d.value >= 1)
  if (dims.length) {
    children.push({
      type: 'chart',
      kind: 'bar',
      categories: dims.map((d) => d.label),
      series: [{ name: '强度', values: dims.map((d) => d.value) }],
      caption: '七根情绪各自还剩多少（上游自己会衰减回 0）',
    })
  } else {
    children.push({ type: 'callout', tone: 'info', text: '这几轮没有情绪波动 —— 无变化不记账，是设计不是坏。' })
  }
  const tl = (snap.timeline || []).slice(0, Number(cfg && cfg.mood ? cfg.mood.timelineLimit : 8) || 8)
  if (tl.length) {
    children.push({ type: 'text', text: '**最近几次**', tone: 'muted' })
    children.push({
      type: 'list',
      items: tl.map((e) => ({
        title: String(e.reason || '（没写理由）').slice(0, 60),
        subtitle: [relTime(e.at), e.conf === null ? null : '可信 ' + e.conf, e.mood ? '值 ' + e.mood : null].filter(Boolean).join(' · '),
        trailing: (e.delta >= 0 ? '+' : '') + e.delta,
        tone: e.delta > 0 ? 'good' : e.delta < 0 ? 'warn' : undefined,
      })),
    })
  }
  const c = snap.cfg || {}
  children.push({
    type: 'keyValue',
    items: [
      ['基线', String(c.moodBase === undefined ? 70 : c.moodBase)],
      ['衰减半衰期', (c.decayHalfHours === undefined ? 12 : c.decayHalfHours) + ' 小时'],
      ['告诉她吗', c.injectMode === 'off' ? '不告诉' : c.injectMode === 'state' ? '只说心情' : '说她刚才那件事'],
      ['可信度门槛', String(c.injectConfidence === undefined ? 60 : c.injectConfidence)],
      ['今天提过', ((snap.mood.saidAt || []).length ? (snap.mood.saidAt || []).length + ' 次（累计）' : '还没提')],
      ['数据源', '~/.dsh/feiyu-mood/state.json（只读）'],
    ],
  })
  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'mood.refresh', icon: 'refresh', style: 'primary' },
      { label: '来源', action: 'mood.sources', icon: 'folder' },
    ],
  })
  return { v: 1, type: 'stack', title: '心情 ' + Math.round(now) + ' · ' + band.label, children }
}
