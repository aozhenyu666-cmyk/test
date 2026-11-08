/**
 * cap-ledger 的视图构造器 + 预算校验（纯函数，零依赖）。
 *
 * 判据与 examples/plugins/dsh-go-partner/lib/views.mjs 同一套（那份已在真机跑过），
 * 这里只换数据形状。三条硬约束：
 *   · 面板 ≤ 32 KiB / 120 节点 / 8 层；状态条 text ≤ 24 字 / ≤ 2 KiB；
 *   · 状态条的 emit 数据是 { text, icon?, tone? }，不是组件树 —— 所以它有单独一条判据；
 *   · 工具卡只用白名单 8 个组件（本包不注册工具，校验器留着给别的插件复用）。
 */
import { describeEntry, summarize } from './ledger.mjs'

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
const TONES = new Set(['good', 'warn', 'bad', 'info', 'muted', 'success', 'warning', 'danger', 'error', 'note', 'secondary'])

const ICONS = new Set('bolt star sparkle heart flag bookmark pin bell clock alarm calendar todo note document folder search code terminal globe chart lightbulb book puzzle robot chat mail translate image camera mic link summary wrench bug travel school home play refresh archive team wallet palette coin money speaker read copy quote share edit write save paste add remove delete clear fork regenerate reply select-text qr download downloads external info warning check done success error question pending running tip list table panel sidebar menu more expand collapse file folder-open workspace storage pdf zip slides article text diff gallery audio music video video-file play-video pause stop replay generate-image generate-video diagram plugin bot agent skill model tool workflow goal plan job automation command trajectory web web-search browser history time today timezone schedule repeat reminder user account group chats mention invite announcement reaction poll notification send community forum feedback wechat qq telegram discord phone computer cloud server power restart retry theme color text-size pet featured badge favorite settings'.split(/\s+/))

// ── 校验器 ──────────────────────────────────────────────────────────────────
function countNodes(node, depth = 1, acc = { n: 0, maxDepth: 0 }) {
  if (!node || typeof node !== 'object' || Array.isArray(node)) return acc
  acc.n += 1
  acc.maxDepth = Math.max(acc.maxDepth, depth)
  const kids = [...(Array.isArray(node.children) ? node.children : []), ...(Array.isArray(node.items) ? node.items : [])]
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
      if (!V1_TYPES.has(node.type) && !V2_TYPES.has(node.type)) issues.push({ path, problem: '不认识的组件 type="' + node.type + '"' })
      if (budget === 'toolCard' && !TOOLCARD_TYPES.has(node.type)) issues.push({ path, problem: '工具卡不能用 "' + node.type + '"' })
    } else {
      issues.push({ path, problem: 'type 不能写绑定' })
    }
  } else if (path === 'root') {
    issues.push({ path, problem: '根节点缺 type' })
  }
  if (typeof node.icon === 'string' && !ICONS.has(node.icon)) issues.push({ path, problem: '不认识的图标 "' + node.icon + '"' })
  if (node.tone !== undefined && !TONES.has(node.tone)) issues.push({ path, problem: '不认识的 tone "' + node.tone + '"' })
  if (node.type === 'text' && typeof node.text !== 'string') issues.push({ path, problem: 'text 缺 text' })
  if (node.type === 'metric' && node.value === undefined) issues.push({ path, problem: 'metric 缺 value' })
  if (node.type === 'list' && Array.isArray(node.items) && node.items.length > 50) issues.push({ path, problem: 'list ' + node.items.length + ' 行 > 50' })
  if (node.type === 'keyValue' && Array.isArray(node.items) && node.items.length > 24) issues.push({ path, problem: 'keyValue ' + node.items.length + ' 项 > 24' })
  if (node.type === 'actions') {
    const n = (node.items || []).length
    if (n > 4) issues.push({ path, problem: 'actions ' + n + ' 个 > 4' })
    if ((node.items || []).filter((x) => x.style === 'primary').length > 1) issues.push({ path, problem: 'actions 最多一个 primary' })
  }
  const kids = []
  if (Array.isArray(node.children)) node.children.forEach((c, i) => kids.push({ c, p: path + '.children[' + i + ']' }))
  if (Array.isArray(node.items)) node.items.forEach((c, i) => { if (c && typeof c === 'object' && typeof c.type === 'string') kids.push({ c, p: path + '.items[' + i + ']' }) })
  for (const k of kids) checkNode(k.c, k.p, issues, budget)
}

/**
 * 校验一棵视图（或状态条数据）能否被 App 完整画出来。
 * @param {unknown} view 组件树，或状态条数据 { text, icon?, tone? }
 * @param {'toolCard'|'panel'|'strip'} [budget]
 */
export function validate(view, budget = 'panel') {
  const b = BUDGET[budget] ?? BUDGET.panel
  const issues = []

  // 状态条的 emit 数据不是组件树 —— 单独一条判据（text ≤ 24 字 / ≤ 2 KiB）
  if (budget === 'strip' && view !== null && typeof view === 'object' && !Array.isArray(view) && view.type === undefined) {
    const label = typeof view.text === 'string' ? view.text : undefined
    if (label === undefined) issues.push({ path: 'root', problem: '状态条缺 text' })
    else if ([...label].length > 24) issues.push({ path: 'root.text', problem: '状态条 text ' + [...label].length + ' 字 > 24' })
    if (view.icon !== undefined && (typeof view.icon !== 'string' || !ICONS.has(view.icon))) issues.push({ path: 'root.icon', problem: '不认识的图标' })
    if (view.tone !== undefined && !TONES.has(view.tone)) issues.push({ path: 'root.tone', problem: '不认识的 tone' })
    const stripBytes = Buffer.byteLength(JSON.stringify(view), 'utf8')
    if (stripBytes > b.bytes) issues.push({ path: 'root', problem: '状态条 ' + stripBytes + 'B > ' + b.bytes + 'B' })
    return { ok: issues.length === 0, bytes: stripBytes, nodes: 1, depth: 1, budget: b, issues }
  }

  checkNode(view, 'root', issues, budget)
  const bytes = Buffer.byteLength(JSON.stringify(view), 'utf8')
  const { n, maxDepth } = countNodes(view)
  if (bytes > b.bytes) issues.push({ path: 'root', problem: '视图 ' + bytes + 'B 超过 ' + b.bytes + 'B' })
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
  if (d < 60000) return '刚刚'
  if (d < 3600000) return Math.max(1, Math.round(d / 60000)) + ' 分钟前'
  if (d < 86400000) return Math.max(1, Math.round(d / 3600000)) + ' 小时前'
  return Math.max(1, Math.round(d / 86400000)) + ' 天前'
}

// ── 面板 ────────────────────────────────────────────────────────────────────
/**
 * 台账面板。四项状态**逐条写出来**，不许只显示插件名。
 * @param {{ ledger: object, now?: number }} s
 */
export function ledgerPanel(s = {}) {
  const ledger = s.ledger !== null && typeof s.ledger === 'object' ? s.ledger : { v: 2, plugins: {} }
  const now = Number.isFinite(s.now) ? s.now : Date.now()
  const plugins = ledger.plugins !== null && typeof ledger.plugins === 'object' ? ledger.plugins : {}
  const names = Object.keys(plugins).sort()
  const sum = summarize(ledger)

  const children = [
    {
      type: 'row',
      children: [
        { type: 'metric', label: '登记', value: String(sum.total), caption: '个插件' },
        {
          type: 'metric',
          label: '能用',
          value: String(sum.healthy),
          ...(sum.total > 0 && sum.healthy === sum.total ? { tone: 'good' } : {}),
          caption: sum.total === 0 ? '还没有记录' : Math.round((sum.healthy / sum.total) * 100) + '%',
        },
        {
          type: 'metric',
          label: '上次失败',
          value: String(sum.failing),
          ...(sum.failing > 0 ? { tone: 'warn' } : {}),
          caption: sum.failing > 0 ? '看下面原因' : '都没有',
        },
      ],
    },
  ]

  if (names.length === 0) {
    children.push({
      type: 'callout',
      tone: 'info',
      text: '还没有任何记录。插件第一次跑起来、工具注册成功之后，这里就会出现它。',
    })
    children.push({
      type: 'actions',
      items: [{ label: '刷新', action: 'ledger.refresh', icon: 'refresh', style: 'primary' }],
    })
    return { v: 1, type: 'stack', title: '能力台账 · 空', children }
  }

  const failing = []
  for (const name of names.slice(0, 20)) {
    const entry = plugins[name]
    const d = describeEntry(entry)
    children.push({ type: 'text', text: '**' + clamp(name, 40) + '**', tone: 'muted' })
    children.push({
      type: 'keyValue',
      items: [
        ['installed（已安装）', d.installed],
        ['loaded（已加载）', d.loaded],
        ['toolRegistered（工具已注册）', d.toolRegistered],
        ['lastRunOk（最近一次）', d.lastRunOk],
      ],
    })
    if (entry.lastRunOk === false) {
      failing.push({
        title: clamp(name, 30) + ' · ' + String(entry.lastRunCode === null ? 'E_UNKNOWN' : entry.lastRunCode),
        subtitle: clamp(String(entry.lastRunMessage === null ? '（没有原因）' : entry.lastRunMessage), 60) + ' · ' + relTime(entry.lastRunAt, now),
        trailing: '失败 ' + entry.fails + '/' + entry.runs,
        icon: 'warning',
        tone: 'warn',
      })
    } else {
      children.push({
        type: 'text',
        text: '跑过 ' + entry.runs + ' 次 · 失败 ' + entry.fails + (entry.lastRunAt === null ? '' : ' · 最近 ' + relTime(entry.lastRunAt, now)),
        tone: 'muted',
      })
    }
  }

  // 失败原因单独一页：埋在 keyValue 里等于没说
  if (failing.length > 0) {
    children.push({ type: 'text', text: '**上次为什么失败**（' + failing.length + '）', tone: 'muted' })
    children.push({ type: 'list', items: failing.slice(0, 20) })
  }

  if (names.length > 20) {
    children.push({ type: 'text', text: '还有 ' + (names.length - 20) + ' 个没显示（面板有预算上限）', tone: 'muted' })
  }

  children.push({
    type: 'actions',
    items: [
      { label: '刷新', action: 'ledger.refresh', icon: 'refresh', style: 'primary' },
      { label: '台账原文', action: 'ledger.copy', icon: 'copy' },
    ],
  })

  return { v: 1, type: 'stack', title: '能力台账 · ' + sum.healthy + '/' + sum.total + ' 能用', children }
}

// ── 状态条 ──────────────────────────────────────────────────────────────────
/**
 * 输入框上方一行：能用 3/4 / 1 个上次失败 / 台账 空。一眼能看出有没有事。
 * @param {{ ledger: object }} s
 * @returns {{ text: string, icon: string, tone: string }}
 */
export function stripData(s = {}) {
  const ledger = s.ledger !== null && typeof s.ledger === 'object' ? s.ledger : { v: 2, plugins: {} }
  const sum = summarize(ledger)

  if (sum.total === 0) return { text: '台账 · 空', icon: 'list', tone: 'muted' }
  if (sum.failing > 0) return { text: clamp(sum.failing + ' 个上次失败', 24), icon: 'warning', tone: 'warn' }
  if (sum.healthy === sum.total) return { text: clamp('能用 ' + sum.healthy + '/' + sum.total, 24), icon: 'check', tone: 'good' }
  return { text: clamp('能用 ' + sum.healthy + '/' + sum.total, 24), icon: 'list', tone: 'muted' }
}

// ── 自测 ────────────────────────────────────────────────────────────────────
function selftest() {
  const now = Date.now()
  const ledger = {
    v: 2,
    plugins: {
      'dsh-go-bili': { plugin: 'dsh-go-bili', installed: true, loaded: true, toolRegistered: true, lastRunOk: true, lastRunAt: now, runs: 5, fails: 1 },
      'dsh-go-broken': { plugin: 'dsh-go-broken', installed: true, loaded: true, toolRegistered: false, lastRunOk: false, lastRunAt: now - 60000, lastRunCode: 'E_REGISTER', lastRunMessage: '工具注册失败：schema 被拒', runs: 2, fails: 2 },
    },
  }
  const cases = [
    ['面板（两条）', ledgerPanel({ ledger, now }), 'panel'],
    ['面板（空）', ledgerPanel({ ledger: { v: 2, plugins: {} }, now }), 'panel'],
    ['状态条', stripData({ ledger }), 'strip'],
    ['状态条（空）', stripData({ ledger: { v: 2, plugins: {} } }), 'strip'],
  ]
  let failed = 0
  for (const [name, view, budget] of cases) {
    const r = validate(view, budget)
    const head = name.padEnd(14) + ' ' + String(r.bytes).padStart(6) + 'B/' + r.budget.bytes + 'B  节点 ' + r.nodes + '/' + r.budget.nodes
    if (r.ok) {
      console.log('  ✓ ' + head)
    } else {
      failed += 1
      console.log('  ✗ ' + head)
      for (const i of r.issues) console.log('      → ' + i.path + '：' + i.problem)
    }
  }
  console.log('')
  console.log(failed === 0 ? '  全部视图在预算内' : '  ' + failed + ' 个视图不合格')
  process.exit(failed === 0 ? 0 : 1)
}

if (process.argv[1] && process.argv[1].endsWith('views.mjs')) selftest()
