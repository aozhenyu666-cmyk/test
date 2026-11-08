/**
 * bili-peek 的视图构造器 + 预算校验器（纯函数，零依赖，可 node --test）。
 *
 * 为什么单独一层：
 *   dsh-go 的插件视图是一棵 JSON 组件树，有硬预算（工具卡 8KiB/40 节点/6 层；
 *   面板 32KiB/120 节点/8 层；状态条 2KiB/8 节点/3 层）。写错、超预算的后果不是
 *   报错，是**整块降级成「插件界面有误」** —— 很难查。所以先构造 + 本地校验。
 *
 * 与 dsh-go-partner 的 views.mjs 同一套判据（BUDGET / validate），因为那份已经在
 * 真机上跑过；这里只把「视频信息」换成新的数据形状，不改校验规则。
 *
 * ⚠️ 工具卡只用最稳的 8 个组件（stack/row/text/metric/chart/compare/steps/callout）——
 *    它写进对话记录，一年后打开老对话也要能画。
 */

/** 预算：与 docs/plugins/components.md §上限 一致。 */
export const BUDGET = {
  toolCard: { bytes: 8 * 1024, nodes: 40, depth: 6 },
  panel: { bytes: 32 * 1024, nodes: 120, depth: 8 },
  strip: { bytes: 2 * 1024, nodes: 8, depth: 3 },
}

const V1_TYPES = new Set([
  'stack', 'row', 'text', 'callout', 'steps', 'compare', 'metric', 'chart', 'progress',
  'list', 'keyValue', 'table', 'actions', 'image', 'options', 'confirm',
])
const V2_TYPES = new Set(['emptyState', 'gallery', 'grid', 'card', 'spacer', 'divider'])
const TOOLCARD_TYPES = new Set(['stack', 'row', 'text', 'metric', 'chart', 'compare', 'steps', 'callout'])

// 图标白名单（162 个，照抄 docs/plugins/slots.md）。写错只会画默认图标，但 check 会报。
const ICONS = new Set('bolt star sparkle heart flag bookmark pin bell clock alarm calendar todo note document folder search code terminal globe chart lightbulb book puzzle robot chat mail translate image camera mic link summary wrench bug travel school home play refresh archive team wallet palette coin money speaker read copy quote share edit write save paste add remove delete clear fork regenerate reply select-text qr download downloads external info warning check done success error question pending running tip list table panel sidebar menu more expand collapse file folder-open workspace storage pdf zip slides article text diff gallery audio music video video-file play-video pause stop replay generate-image generate-video diagram plugin bot agent skill model tool workflow goal plan job automation command trajectory web web-search browser history time today timezone schedule repeat reminder user account group chats mention invite announcement reaction poll notification send community forum feedback wechat qq telegram discord phone computer cloud server power restart retry theme color text-size pet featured badge favorite settings'.split(/\s+/))

const TONES = new Set(['good', 'warn', 'bad', 'info', 'muted', 'success', 'warning', 'danger', 'error', 'note', 'secondary'])

// ── 校验器（与 partner/views.mjs 同一套判据）─────────────────────────────────
function countNodes(node, depth = 1, acc = { n: 0, maxDepth: 0 }) {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return acc
  acc.n += 1
  acc.maxDepth = Math.max(acc.maxDepth, depth)
  const kids = []
  if (Array.isArray(node.children)) kids.push(...node.children)
  if (Array.isArray(node.items)) kids.push(...node.items)
  for (const c of kids) {
    if (!c || typeof c !== 'object' || Array.isArray(c)) continue
    if (typeof c.type !== 'string') continue
    countNodes(c, depth + 1, acc)
  }
  return acc
}

function checkNode(node, path, issues, budget) {
  if (!node || typeof node !== 'object' || Array.isArray(node)) {
    issues.push({ path, problem: '不是对象' })
    return
  }
  if (node.type !== undefined) {
    if (typeof node.type === 'string') {
      const known = V1_TYPES.has(node.type) || V2_TYPES.has(node.type)
      if (!known) issues.push({ path, problem: '不认识的组件 type="' + node.type + '"（会画成灰色占位）' })
      if (budget === 'toolCard' && !TOOLCARD_TYPES.has(node.type)) {
        issues.push({ path, problem: '工具卡不能用 "' + node.type + '"（只能用 stack/row/text/metric/chart/compare/steps/callout）' })
      }
    } else {
      issues.push({ path, problem: 'type 不能写绑定' })
    }
  } else if (path === 'root') {
    issues.push({ path, problem: '根节点缺 type' })
  }
  if (typeof node.icon === 'string' && !ICONS.has(node.icon)) issues.push({ path, problem: '不认识的图标 "' + node.icon + '"' })
  if (node.tone !== undefined && !TONES.has(node.tone)) issues.push({ path, problem: '不认识的 tone "' + node.tone + '"' })
  if (node.type === 'text' && typeof node.text !== 'string') issues.push({ path, problem: 'text 组件缺 text' })
  if (node.type === 'metric' && node.value === undefined) issues.push({ path, problem: 'metric 缺 value' })
  if (node.type === 'chart') {
    const points = (node.series || []).reduce((s, x) => s + (Array.isArray(x.values) ? x.values.length : 0), 0)
    if (points > 60) issues.push({ path, problem: 'chart 数据点 ' + points + ' > 60' })
    if ((node.series || []).length > 6) issues.push({ path, problem: 'chart 组数 ' + node.series.length + ' > 6' })
  }
  if (node.type === 'list' && Array.isArray(node.items) && node.items.length > 50) issues.push({ path, problem: 'list ' + node.items.length + ' 行 > 50' })
  if (node.type === 'keyValue' && Array.isArray(node.items) && node.items.length > 24) issues.push({ path, problem: 'keyValue ' + node.items.length + ' 项 > 24' })
  if (node.type === 'actions') {
    const n = (node.items || []).length
    if (n > 4) issues.push({ path, problem: 'actions ' + n + ' 个 > 4' })
    if ((node.items || []).filter((x) => x.style === 'primary').length > 1) issues.push({ path, problem: 'actions 最多一个 primary' })
  }
  if (node.type === 'steps' && Array.isArray(node.items) && node.items.length > 12) issues.push({ path, problem: 'steps ' + node.items.length + ' 项 > 12' })
  const kids = []
  if (Array.isArray(node.children)) node.children.forEach((c, i) => kids.push({ c, p: path + '.children[' + i + ']' }))
  if (Array.isArray(node.items)) node.items.forEach((c, i) => { if (c && typeof c === 'object' && typeof c.type === 'string') kids.push({ c, p: path + '.items[' + i + ']' }) })
  for (const k of kids) checkNode(k.c, k.p, issues, budget)
}

/**
 * 校验一棵视图是否会被 App 完整画出来。
 * @param {unknown} view
 * @param {'toolCard'|'panel'|'strip'} [budget]
 * @returns {{ ok: boolean, bytes: number, nodes: number, depth: number, budget: object, issues: {path: string, problem: string}[] }}
 */
export function validate(view, budget = 'panel') {
  const b = BUDGET[budget] ?? BUDGET.panel
  const issues = []
  // 状态条的 emit 数据**不是组件树**，是 { text, icon?, tone? }（见 slots.md）。
  // 所以走单独一条判据：text ≤ 24 字、整体 ≤ 2KiB、tone/icon 要认识。
  if (budget === 'strip' && view !== null && typeof view === 'object' && !Array.isArray(view) && view.type === undefined) {
    const text = typeof view.text === 'string' ? view.text : undefined
    if (text === undefined) issues.push({ path: 'root', problem: '状态条缺 text（整条降级成空）' })
    else if ([...text].length > 24) issues.push({ path: 'root.text', problem: '状态条 text ' + [...text].length + ' 字 > 24 字（会被截断）' })
    if (view.icon !== undefined && (typeof view.icon !== 'string' || !ICONS.has(view.icon))) issues.push({ path: 'root.icon', problem: '不认识的图标 "' + String(view.icon) + '"' })
    if (view.tone !== undefined && !TONES.has(view.tone)) issues.push({ path: 'root.tone', problem: '不认识的 tone "' + String(view.tone) + '"' })
    const stripBytes = Buffer.byteLength(JSON.stringify(view), 'utf8')
    if (stripBytes > b.bytes) issues.push({ path: 'root', problem: '状态条 ' + stripBytes + 'B 超过 ' + b.bytes + 'B 上限' })
    return { ok: issues.length === 0, bytes: stripBytes, nodes: 1, depth: 1, budget: b, issues }
  }
  checkNode(view, 'root', issues, budget)
  const bytes = Buffer.byteLength(JSON.stringify(view), 'utf8')
  const { n, maxDepth } = countNodes(view)
  if (bytes > b.bytes) issues.push({ path: 'root', problem: '视图 ' + bytes + 'B 超过 ' + b.bytes + 'B 上限（整块降级）' })
  if (n > b.nodes) issues.push({ path: 'root', problem: '节点 ' + n + ' > ' + b.nodes })
  if (maxDepth > b.depth) issues.push({ path: 'root', problem: '层数 ' + maxDepth + ' > ' + b.depth })
  return { ok: issues.length === 0, bytes, nodes: n, depth: maxDepth, budget: b, issues }
}

// ── 小工具 ──────────────────────────────────────────────────────────────────
const clamp = (s, n) => (typeof s === 'string' ? s.slice(0, n) : '')

function relTime(ms, now = Date.now()) {
  const t = Number(ms)
  if (!Number.isFinite(t) || t <= 0) return '时间未知'
  const d = now - t
  if (d < 0) return '刚刚'
  if (d < 60000) return '刚刚'
  if (d < 3600000) return Math.max(1, Math.round(d / 60000)) + ' 分钟前'
  if (d < 86400000) return Math.max(1, Math.round(d / 3600000)) + ' 小时前'
  return Math.max(1, Math.round(d / 86400000)) + ' 天前'
}

/** ISO 时间 → 2026-10-30（不给就用 '—'，不写 null）。 */
function day(iso) {
  if (typeof iso !== 'string') return '—'
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return '—'
  return new Date(t).toISOString().slice(0, 10)
}

const compactNum = (v) => {
  const n = Number(v)
  if (!Number.isFinite(n)) return '?'
  if (n >= 100000000) return trim(n / 100000000) + '亿'
  if (n >= 10000) return trim(n / 10000) + '万'
  return String(n)
}
const trim = (x) => {
  const r = Math.round(x * 10) / 10
  return Number.isInteger(r) ? String(r) : r.toFixed(1)
}
const hhmmss = (v) => {
  const n = Number(v)
  if (!Number.isFinite(n) || n < 0) return '?'
  const s = Math.round(n)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (x) => String(x).padStart(2, '0')
  return h > 0 ? h + ':' + pad(m) + ':' + pad(sec) : m + ':' + pad(sec)
}

// ── 工具卡：B站视频信息（只用白名单 8 个组件）────────────────────────────────

/**
 * peek_bili_video 的结果 → 原生卡片。
 * 失败也画卡（写清错误码 + 下一步），**不留一张只有名字的空卡**。
 *
 * @param {object} args 工具入参
 * @param {object} value 工具返回值（ok:true / ok:false，见 bili.mjs）
 * @returns {object} dsh-go 视图
 */
export function peekCard(args = {}, value = {}) {
  const okFlag = value && value.ok === true

  if (!okFlag) {
    const code = String((value && value.code) || 'E_INTERNAL')
    const children = [
      { type: 'callout', tone: 'warn', text: clamp(code + ' · ' + String((value && value.message) || '查询失败'), 200) },
    ]
    const hint = clamp(String((value && value.hint) || ''), 200)
    if (hint !== '') children.push({ type: 'text', text: hint, tone: 'muted' })
    const raw = args && (args.id ?? args.input)
    const tried = clamp(raw === undefined || raw === null ? '' : String(raw), 80)
    if (tried !== '') children.push({ type: 'text', text: '查的是：`' + tried + '`', tone: 'muted' })
    return { v: 1, type: 'stack', title: clamp('没查到 · ' + code, 60), children }
  }

  const stats = (value && value.stats) || {}
  const children = []

  // 第 1 行：三个关键数字（播放 / 点赞 / 时长）
  const metrics = [
    { type: 'metric', label: '播放', value: compactNum(stats.view), caption: '次' },
    { type: 'metric', label: '点赞', value: compactNum(stats.like), caption: '次' },
  ]
  const duration = Number(value && value.duration)
  if (Number.isFinite(duration) && duration > 0) {
    metrics.push({ type: 'metric', label: '时长', value: hhmmss(duration) })
  }
  children.push({ type: 'row', children: metrics })

  // 第 2 行：作者 + 发布时间
  const facts = []
  if (typeof value.author === 'string' && value.author !== '') facts.push('UP：' + clamp(value.author, 40))
  if (typeof value.pubdate === 'string') facts.push('发布于 ' + day(value.pubdate))
  if (facts.length > 0) children.push({ type: 'text', text: facts.join(' · '), tone: 'muted' })

  // 简介：截断，空了就明说没有
  const desc = typeof value.desc === 'string' ? value.desc.trim() : ''
  children.push({
    type: 'text',
    text: desc === '' ? '（这个视频没有简介）' : clamp(desc.replace(/\s+/g, ' '), 180),
  })

  // 分 P 多于一条时，用一行文字说清选了哪个 —— 别用 list（工具卡白名单里没有）
  const pages = Array.isArray(value.pages) ? value.pages : []
  if (pages.length > 1) {
    const cur = value.currentPage
    const head = cur && cur.index ? 'P' + cur.index + '「' + clamp(cur.title, 30) + '」' : '共 ' + pages.length + ' P'
    children.push({ type: 'text', text: head + ' · 共 ' + pages.length + ' P', tone: 'muted' })
  }

  children.push({ type: 'text', text: 'B站公开数据 · ' + clamp(String(value.bvid || ''), 20), tone: 'muted' })

  return {
    v: 1,
    type: 'stack',
    title: clamp(String(value.title || value.bvid || 'B站视频'), 40),
    children,
  }
}

// ── 面板：最近的查询记录 + 能力台账 ──────────────────────────────────────────

/**
 * @param {object} s
 *   s.recent: [{ bvid, title, author, view, ok, code, at }]  最近查过的（新的在前）
 *   s.ledger: { installed, loaded, toolRegistered, lastRunOk, lastRunCode, lastRunAt, lastRunMessage, runs, fails }
 *   s.hasBridge: 宿主有没有 dsh-go 桥接（没有时面板仍然画得出来）
 * @returns {object} 面板视图
 */
export function peekPanel(s = {}) {
  const recent = Array.isArray(s.recent) ? s.recent : []
  const ledger = s.ledger && typeof s.ledger === 'object' ? s.ledger : {}
  const children = []

  // ① 台账：四个状态必须分开写，不能只显示插件名
  children.push({ type: 'text', text: '**能力台账**', tone: 'muted' })
  children.push({
    type: 'keyValue',
    items: [
      ['插件', 'dsh-go-bili-peek'],
      ['installed（已安装）', ledger.installed === true ? '是' : '否'],
      ['loaded（已加载）', ledger.loaded === true ? '是' : '否'],
      ['toolRegistered（工具已注册）', ledger.toolRegistered === true ? 'peek_bili_video' : '否'],
      ['lastRunOk（最近一次）', lastRunLabel(ledger)],
    ],
  })

  if (ledger.lastRunOk === false && typeof ledger.lastRunMessage === 'string' && ledger.lastRunMessage !== '') {
    children.push({ type: 'callout', tone: 'warn', text: clamp('上次失败原因：' + ledger.lastRunMessage, 300) })
  }

  // ② 计数
  const runs = Number(ledger.runs) || 0
  const fails = Number(ledger.fails) || 0
  children.push({
    type: 'row',
    children: [
      { type: 'metric', label: '查过', value: String(runs), caption: '次调用' },
      { type: 'metric', label: '失败', value: String(fails), caption: runs > 0 ? '成功率 ' + Math.round(((runs - fails) / runs) * 100) + '%' : '还没有记录' },
    ],
  })

  // ③ 最近记录
  if (recent.length === 0) {
    children.push({ type: 'callout', tone: 'info', text: '还没查过。让 Agent 念一个 BV 号或贴一条 B站链接，它就会自己调 peek_bili_video。' })
  } else {
    children.push({ type: 'text', text: '**最近查的**（' + recent.length + '）', tone: 'muted' })
    children.push({
      type: 'list',
      items: recent.slice(0, 20).map((r) => ({
        title: clamp(String(r.title || r.bvid || ''), 40) || '（没有标题）',
        subtitle: [
          r.ok ? '播放 ' + compactNum(r.view) : String(r.code || '失败'),
          typeof r.author === 'string' && r.author !== '' ? clamp(r.author, 20) : '',
          relTime(r.at),
        ].filter((x) => x !== '').join(' · '),
        icon: r.ok ? 'video' : 'warning',
        tone: r.ok ? undefined : 'warn',
      })),
    })
  }

  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'peek.refresh', icon: 'refresh', style: 'primary' },
      { label: '台账原文', action: 'peek.ledger', icon: 'list' },
    ],
  })

  return { v: 1, type: 'stack', title: 'B站速览 · ' + runs + ' 次', children }
}

function lastRunLabel(ledger) {
  if (ledger.lastRunAt === undefined || ledger.lastRunAt === null) return '还没跑过'
  const when = relTime(ledger.lastRunAt)
  if (ledger.lastRunOk === true) return '成功 · ' + when
  if (ledger.lastRunOk === false) return '失败（' + String(ledger.lastRunCode || 'E_INTERNAL') + '）· ' + when
  return '还没跑过'
}

// ── 状态条（emit 的数据，2KiB / 8 节点 / 3 层 / text ≤ 24 字）─────────────────

/**
 * 输入框上方那一行。**没有桥接 / 没有记录时也要给一句人话**，不能是空白。
 * @param {object} s 同 peekPanel
 * @returns {{ text: string, icon?: string, tone?: string }}
 */
export function stripData(s = {}) {
  const ledger = s.ledger && typeof s.ledger === 'object' ? s.ledger : {}
  const recent = Array.isArray(s.recent) ? s.recent : []
  const last = recent[0]

  if (last !== undefined) {
    const okFlag = last.ok === true
    const text = okFlag
      ? clamp((String(last.title || last.bvid || '') || '?'), 14) + ' ' + compactNum(last.view)
      : '上次没查到 ' + String(last.code || '')
    return { text: clamp(text, 24), icon: okFlag ? 'video' : 'warning', tone: okFlag ? 'good' : 'warn' }
  }

  if (ledger.toolRegistered === true) return { text: 'B站速览 · 待命', icon: 'video', tone: 'muted' }
  return { text: 'B站速览 · 未就绪', icon: 'question', tone: 'muted' }
}

// ── 自测 ────────────────────────────────────────────────────────────────────
function selftest() {
  const now = Date.now()
  const okValue = {
    ok: true, code: 'OK', bvid: 'BV1uv411q7Mv', title: '爆肝98小时！在MC中还原糖调小镇',
    author: '落忆', desc: '相关游戏：Minecraft、しゅがてん！', duration: 314,
    pubdate: new Date(now - 86400000).toISOString(),
    stats: { view: 5576839, like: 277783, danmaku: 128787 },
    pages: [{ index: 1, title: '正片', duration: 314 }, { index: 2, title: '花絮', duration: 90 }],
    currentPage: { index: 1, title: '正片', duration: 314 },
    fetchedAt: now,
  }
  const failValue = { ok: false, code: 'E_TIMEOUT', message: '请求超时：超过 12000 ms', hint: '过一会儿再试一次。' }
  const ledger = { installed: true, loaded: true, toolRegistered: true, lastRunOk: true, lastRunAt: now, runs: 3, fails: 1 }
  const recent = [{ bvid: 'BV1uv411q7Mv', title: '爆肝98小时', author: '落忆', view: 5576839, ok: true, at: now }]

  const cases = [
    ['成功工具卡', peekCard({ id: 'BV1uv411q7Mv' }, okValue), 'toolCard'],
    ['失败工具卡', peekCard({ id: 'BVbad' }, failValue), 'toolCard'],
    ['空结果工具卡', peekCard({}, {}), 'toolCard'],
    ['面板（有记录）', peekPanel({ recent, ledger }), 'panel'],
    ['面板（空）', peekPanel({}), 'panel'],
    ['状态条数据', stripData({ recent, ledger }), 'strip'],
    ['状态条（空）', stripData({}), 'strip'],
  ]

  let failed = 0
  for (const [name, view, budget] of cases) {
    const r = validate(view, budget)
    const head = name.padEnd(16) + ' ' + String(r.bytes).padStart(6) + 'B/' + r.budget.bytes + 'B  节点 ' + r.nodes + '/' + r.budget.nodes + '  层 ' + r.depth + '/' + r.budget.depth
    if (r.ok) console.log('  ✓ ' + head)
    else {
      failed += 1
      console.log('  ✗ ' + head)
      for (const i of r.issues) console.log('      → ' + i.path + '：' + i.problem)
    }
  }
  // 状态条还要额外查 text ≤ 24 字
  const strip = stripData({ recent, ledger })
  if ([...strip.text].length > 24) {
    failed += 1
    console.log('  ✗ 状态条 text 超 24 字：' + strip.text)
  }
  console.log('')
  console.log(failed === 0 ? '  全部视图在预算内，可以交给 App 渲染' : '  ' + failed + ' 个视图不合格')
  process.exit(failed === 0 ? 0 : 1)
}

if (process.argv[1] && process.argv[1].endsWith('views.mjs')) selftest()
