// dsh-go-companion · 预案（第九轮 A 组）
//
// 预案 = 何时（事件 + 计数）+ 条件（JSON Logic）+ 动作 + 解除出口。全部是数据：AI 能起草、能启用、能收紧，
// 代码来执行。引擎启动时**不再按内置名单锁任何 App**——锁只来自生效中的预案。
//
// 这个文件是纯函数：不读盘、不碰手机、不调模型。index.js 负责把状态存进 presets.json、
// 把每次 /plan 的手机快照喂进 observe()/match()、把 plan() 给出的动作落到手机。
//
// 状态：停用 / 影子 / 生效。
//   影子：照常匹配、照常记账「本应执行什么」，但一个动作都不做。新预案默认先跑影子，
//         一条写错的规则不会第一次触发就把他锁死。
//
// 改动的门槛（AI 权限表，第九轮 §9）：
//   收紧（停用→影子→生效、次数变少、窗口变长、多加动作）：谁都可以，立即生效。
//   放宽（反过来，或者改了条件——条件是否更松代码判断不了，一律按放宽算）：
//     AI 不能做（needs-user）；他自己改要过 2 小时冷却期，期间可以撤销。
//   AI 新建的预案一律从影子开始；影子满 24 小时 AI 才能把它转成生效（他自己随时可以）。

import { validate as validateLogic, test as testLogic } from './jsonlogic.js'

export const PRESETS_VERSION = 1

export const STATUS = Object.freeze(['停用', '影子', '生效'])
const STATUS_RANK = { 停用: 0, 影子: 1, 生效: 2 }

/** 放宽的冷却期。 */
export const LOOSEN_COOLDOWN_MS = 2 * 3_600_000
/** AI 新建的预案至少跑这么久影子，AI 才能把它转成生效。 */
export const SHADOW_MIN_MS = 24 * 3_600_000
/** AI 立的预案 30 天后自动失效（日落），除非他续期。 */
export const SUNSET_MS = 30 * 86_400_000
/** 每条预案保留的历史版本数（回退用）。 */
export const HISTORY_KEEP = 20
/** 「直到：本局结束」时，万一一直收不到收局，最多挂这么久。 */
export const BOUT_END_CAP_MS = 6 * 3_600_000

/**
 * 事件。builtin=true 的只给内置预案用（它们背后是另一套已有逻辑），AI 和他新建的预案不能用。
 * params：这个事件在「何时」里认的参数。
 */
export const EVENTS = Object.freeze({
  切出: { params: ['窗口', '次数', '仅局内'], about: '从本局允许的 App 切到别的 App' },
  偏离停留: { params: ['分钟', '仅局内'], about: '在本局不允许的 App 里连续待着' },
  敷衍: { params: ['窗口', '次数', '仅局内'], about: '一条回答被判成敷衍（口水话 / 不作答 / 超时）' },
  局开始: { params: [], about: '一局开始' },
  局结束: { params: [], about: '一局结束' },
  到点: { params: ['时刻'], about: '每天到某个时刻（北京时间）' },
  常驻: { params: [], about: '一直在（只给内置预案）', builtin: true },
  巡检: { params: [], about: '每次巡检（只给内置预案）', builtin: true },
})

/** 动作。builtin=true 的只给内置预案用。 */
export const ACTIONS = Object.freeze({
  严格模式: { about: '只放行指定的 App，其余立即锁，无宽限' },
  锁: { about: '锁住一个 App 一段时间' },
  全机锁: { about: '只放行局页和紧急应用' },
  开工具: { about: '拉起一个工具（目前只有 gemini）' },
  进入计划: { about: '进入计划模态' },
  说: { about: '在锁屏页和账本里写一句话' },
  记账: { about: '只记一笔' },
  墙收紧: { about: '墙当天收紧：娱乐锁到零点' },
  阶梯: { about: '第八轮的升级阶梯（E1–E3 / L1–L3）', builtin: true },
  墙默认锁: { about: '第二轮的全天默认锁（吃饭时段开放）', builtin: true },
  深夜锁: { about: '深夜锁（由作息执行）', builtin: true },
})

/** 解除出口：只能从这里选。 */
export const EXITS = Object.freeze(['本局结束', '到点', '答出指令', '破窗', '用户确认'])

/** 条件里能读的变量根名。 */
export const VARS = Object.freeze(['bout', 'time', 'today', 'front', 'fired'])

/** 工具：目前只认 gemini。 */
export const TOOLS = Object.freeze(['gemini'])

/** 「说」的字数上限：锁屏页上一行说得完。 */
export const SAY_MAX = 40

/** 桌面与系统界面：算不上「切出」。和手机侧 ProactiveRuntime.NEUTRAL 是同一张表。 */
export const NEUTRAL_PACKAGES = Object.freeze([
  'android', 'com.android.systemui', 'com.miui.home', 'com.google.android.apps.nexuslauncher',
  'com.android.launcher3', 'com.oppo.launcher', 'com.huawei.android.launcher',
  'com.sec.android.app.launcher', 'com.vivo.launcher', 'com.bbk.launcher2',
])

// ---------------------------------------------------------------- 内置预案

/**
 * 内置预案。`fixed: true` 的不能停用也不能改（深夜锁由作息执行，这里只用来解释）；
 * 其余可以停用、可以改阈值，规则和他新建的一样走收紧 / 放宽的门槛。
 */
export const BUILTINS = Object.freeze([
  {
    id: 'default-lock',
    名称: '全天默认锁',
    状态: '停用',
    何时: { 事件: '常驻' },
    动作: [{ 墙默认锁: {} }],
    解除: ['到点', '破窗'],
    说明: '第二轮起的「默认严格」：吃饭时段以外娱乐全锁。第九轮起默认停用，锁只来自触发的预案。',
  },
  {
    id: 'night-lock',
    名称: '深夜锁',
    状态: '生效',
    何时: { 事件: '常驻' },
    动作: [{ 深夜锁: {} }],
    解除: ['到点', '破窗'],
    fixed: true,
    说明: '0:30 到 7:00 娱乐锁着（由作息执行，这一轮不能在这里停用）。',
  },
  {
    id: 'ladder-perfunctory',
    名称: '敷衍升级阶梯',
    状态: '生效',
    何时: { 事件: '敷衍' },
    动作: [{ 阶梯: { 轨: '敷衍' } }],
    解除: ['答出指令', '到点', '破窗'],
    说明: '第八轮 E1–E3：30 分钟敷衍 3 次开 Gemini 审讯舱，60 秒不进全机锁，当天第 2、3 次加码。',
  },
  {
    id: 'ladder-immersion',
    名称: '沉迷升级阶梯',
    状态: '生效',
    何时: { 事件: '巡检' },
    动作: [{ 阶梯: { 轨: '沉迷' } }],
    解除: ['答出指令', '到点', '破窗'],
    说明: '第八轮 L1–L3：娱乐连续用太久直接锁并进计划模态，局内用没声明的 App 先核对再锁，当天累计超限全锁。',
  },
  {
    id: 'switch-storm',
    名称: '局内频繁切出',
    状态: '生效',
    何时: { 事件: '切出', 窗口: '10m', 次数: 3, 仅局内: true },
    动作: [{ 严格模式: { 放行: '本局应用', 直到: '本局结束' } }, { 说: { 话: '十分钟切出三次。只剩本局应用。' } }],
    解除: ['本局结束', '破窗'],
    说明: '局里 10 分钟内从允许的 App 切出去 3 次，就只剩这一局允许的 App。',
  },
  {
    id: 'off-dwell',
    名称: '局内偏离停留',
    状态: '生效',
    何时: { 事件: '偏离停留', 分钟: 1, 仅局内: true },
    动作: [{ 严格模式: { 放行: '本局应用', 直到: '本局结束' } }, { 说: { 话: '在别的 App 里待了一分钟。只剩本局应用。' } }],
    解除: ['本局结束', '破窗'],
    说明: '局里在不允许的 App 里连续待满 1 分钟，就只剩这一局允许的 App。',
  },
])

const BUILTIN_IDS = new Set(BUILTINS.map((b) => b.id))

// ---------------------------------------------------------------- 小工具

const isPlain = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const num = (v, d = 0) => (Number.isFinite(Number(v)) ? Number(v) : d)

/** '10m' / '90s' / '2h' / 数字（分钟）→ 毫秒；认不出返回 null。 */
export function parseDuration(value) {
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) return Math.round(value * 60_000)
  const m = /^\s*(\d+(?:\.\d+)?)\s*(s|m|h|秒|分钟|分|小时)?\s*$/.exec(String(value ?? ''))
  if (!m) return null
  const n = Number(m[1])
  if (!(n > 0)) return null
  const unit = m[2] || 'm'
  if (unit === 's' || unit === '秒') return Math.round(n * 1000)
  if (unit === 'h' || unit === '小时') return Math.round(n * 3_600_000)
  return Math.round(n * 60_000)
}

/** 'HH:MM' → 一天中的第几分钟；认不出返回 null。 */
export function parseClock(value) {
  const m = /^\s*(\d{1,2}):(\d{2})\s*$/.exec(String(value ?? ''))
  if (!m) return null
  const h = Number(m[1])
  const min = Number(m[2])
  if (h > 23 || min > 59) return null
  return h * 60 + min
}

/** 一个动作对象 {名字: 参数} → {type, params}。 */
function actionOf(raw) {
  if (!isPlain(raw)) return null
  const keys = Object.keys(raw)
  if (keys.length !== 1) return null
  const type = keys[0]
  return { type, params: isPlain(raw[type]) ? raw[type] : {} }
}

const fmtMin = (ms) => {
  const m = Math.round(ms / 60_000)
  if (m >= 60 && m % 60 === 0) return m / 60 + ' 小时'
  return m + ' 分钟'
}

// ---------------------------------------------------------------- 校验

/**
 * 校验一条预案（他、AI、内置都过这一条）。
 * @param {unknown} raw
 * @param {{builtin?: boolean}} [opts]  builtin=true 才允许用只给内置的事件和动作
 * @returns {{ok:boolean, errors:{code:string, at:string, message:string}[], preset?:object}}
 */
export function validatePreset(raw, opts = {}) {
  const errors = []
  const err = (code, at, message) => errors.push({ code, at, message })
  if (!isPlain(raw)) return { ok: false, errors: [{ code: 'not-object', at: '', message: '预案必须是对象' }] }
  const builtin = opts.builtin === true

  const id = String(raw.id ?? '').trim()
  if (!/^[a-z0-9][a-z0-9-]{1,47}$/.test(id)) err('bad-id', 'id', 'id 只能是 2–48 位小写字母、数字和连字符')
  const name = String(raw['名称'] ?? '').trim()
  if (!name || name.length > 20) err('bad-name', '名称', '名称必填，最多 20 字')
  const status = String(raw['状态'] ?? '影子')
  if (!STATUS.includes(status)) err('bad-status', '状态', '状态只能是：' + STATUS.join(' / '))

  const when = isPlain(raw['何时']) ? raw['何时'] : null
  if (!when) err('no-when', '何时', '必须写明何时')
  const event = when ? String(when['事件'] ?? '') : ''
  const spec = EVENTS[event]
  if (when && !spec) err('unknown-event', '何时.事件', '不认识的事件：' + event + '。可用：' + Object.keys(EVENTS).filter((k) => !EVENTS[k].builtin).join(' / '))
  if (spec && spec.builtin && !builtin) err('builtin-event', '何时.事件', '事件「' + event + '」只给内置预案用')
  const norm = { event, windowMs: 0, count: 1, dwellMs: 0, atMin: null, inBout: false }
  if (spec && when) {
    for (const key of Object.keys(when)) {
      if (key !== '事件' && !spec.params.includes(key)) err('unknown-param', '何时.' + key, '事件「' + event + '」不认这个参数：' + key)
    }
    if (spec.params.includes('窗口')) {
      const w = when['窗口'] === undefined ? 10 * 60_000 : parseDuration(when['窗口'])
      if (w === null || w < 60_000 || w > 120 * 60_000) err('bad-window', '何时.窗口', '窗口要在 1 到 120 分钟之间')
      else norm.windowMs = w
    }
    if (spec.params.includes('次数')) {
      const c = when['次数'] === undefined ? 1 : num(when['次数'], NaN)
      if (!Number.isInteger(c) || c < 1 || c > 20) err('bad-count', '何时.次数', '次数要是 1 到 20 的整数')
      else norm.count = c
    }
    if (spec.params.includes('分钟')) {
      const d = when['分钟'] === undefined ? null : parseDuration(when['分钟'])
      if (d === null || d < 30_000 || d > 240 * 60_000) err('bad-minutes', '何时.分钟', '分钟要在 0.5 到 240 之间')
      else norm.dwellMs = d
    }
    if (spec.params.includes('时刻')) {
      const t = parseClock(when['时刻'])
      if (t === null) err('bad-clock', '何时.时刻', '时刻写成 HH:MM（北京时间）')
      else norm.atMin = t
    }
    if (spec.params.includes('仅局内')) norm.inBout = when['仅局内'] === true
  }

  const cond = raw['条件']
  if (cond !== undefined && cond !== null) {
    const v = validateLogic(cond, { vars: [...VARS] })
    for (const e of v.errors) err(e.code, e.at, e.message)
  }

  const rawActions = Array.isArray(raw['动作']) ? raw['动作'] : null
  const actions = []
  if (!rawActions || !rawActions.length) err('no-action', '动作', '至少要有一个动作')
  else if (rawActions.length > 6) err('too-many-actions', '动作', '最多 6 个动作')
  ;(rawActions || []).forEach((a, i) => {
    const at = '动作[' + i + ']'
    const act = actionOf(a)
    if (!act) return err('bad-action', at, '一个动作写成 {名字: 参数}')
    const aspec = ACTIONS[act.type]
    if (!aspec) return err('unknown-action', at, '不认识的动作：' + act.type)
    if (aspec.builtin && !builtin) return err('builtin-action', at, '动作「' + act.type + '」只给内置预案用')
    const p = act.params
    if (act.type === '严格模式') {
      const allow = p['放行'] ?? '本局应用'
      if (!(allow === '本局应用' || (Array.isArray(allow) && allow.length <= 20 && allow.every((x) => typeof x === 'string' && /^[A-Za-z0-9_.]+$/.test(x))))) {
        err('bad-allow', at + '.放行', '放行写「本局应用」或包名列表（最多 20 个）')
      }
      const until = p['直到'] ?? '本局结束'
      if (until !== '本局结束') {
        const d = parseDuration(until)
        if (d === null || d < 60_000 || d > 6 * 3_600_000) err('bad-until', at + '.直到', '直到写「本局结束」或 1 分钟到 6 小时')
      }
    } else if (act.type === '锁') {
      const app = p['应用'] ?? '当前'
      if (!(app === '当前' || (typeof app === 'string' && /^[A-Za-z0-9_.]+$/.test(app)))) err('bad-app', at + '.应用', '应用写「当前」或一个包名')
      const d = parseDuration(p['时长'] ?? '10m')
      if (d === null || d < 60_000 || d > 6 * 3_600_000) err('bad-duration', at + '.时长', '时长 1 分钟到 6 小时')
    } else if (act.type === '全机锁' || act.type === '进入计划') {
      const d = parseDuration(p['时长'] ?? '10m')
      if (d === null || d < 60_000 || d > 2 * 3_600_000) err('bad-duration', at + '.时长', '时长 1 分钟到 2 小时')
    } else if (act.type === '开工具') {
      if (!TOOLS.includes(String(p['工具'] ?? 'gemini'))) err('unknown-tool', at + '.工具', '工具目前只有：' + TOOLS.join(' / '))
      const d = parseDuration(p['时长'] ?? '10m')
      if (d === null || d < 60_000 || d > 60 * 60_000) err('bad-duration', at + '.时长', '时长 1 到 60 分钟')
    } else if (act.type === '说') {
      const text = String(p['话'] ?? '').trim()
      if (!text || text.length > SAY_MAX) err('bad-say', at + '.话', '话必填，最多 ' + SAY_MAX + ' 字')
    } else if (act.type === '阶梯') {
      if (!['敷衍', '沉迷'].includes(String(p['轨'] ?? ''))) err('bad-track', at + '.轨', '轨只能是 敷衍 / 沉迷')
    }
    actions.push(act)
  })

  const exits = Array.isArray(raw['解除']) ? raw['解除'].map(String) : []
  if (!exits.length) err('no-exit', '解除', '必须写明怎么解除')
  for (const e of exits) if (!EXITS.includes(e)) err('bad-exit', '解除', '解除只能从这些里选：' + EXITS.join(' / '))
  // 破窗永远是出口之一：他任何时候都能付代价离开，不能写一条连破窗都解不开的预案。
  const exitList = exits.includes('破窗') ? exits : [...exits, '破窗']

  // 依据：这条预案是冲着哪张模式卡立的（行为大脑 K3）。可以不写；写了就得是 M-xx。
  const basis = raw['依据'] === undefined || raw['依据'] === null || raw['依据'] === '' ? '' : String(raw['依据'])
  if (basis && !/^M-\d{2,4}$/.test(basis)) err('bad-basis', '依据', '依据写模式卡编号，比如 M-03')

  if (errors.length) return { ok: false, errors }
  return {
    ok: true,
    errors: [],
    preset: {
      id,
      name,
      status,
      when: norm,
      cond: cond === undefined ? null : cond,
      actions,
      exits: exitList,
      note: String(raw['说明'] ?? '').slice(0, 120),
      fixed: builtin && raw.fixed === true,
      builtin,
      basis,
      source: String(raw['来源'] ?? (builtin ? '内置' : '')).slice(0, 20),
    },
  }
}

/** 预案对象 → 写回去的原始形状（存盘、给 AI 看、回退都用这个）。 */
export function rawOf(p) {
  const when = { 事件: p.when.event }
  const spec = EVENTS[p.when.event] || { params: [] }
  if (spec.params.includes('窗口')) when['窗口'] = Math.round(p.when.windowMs / 60_000) + 'm'
  if (spec.params.includes('次数')) when['次数'] = p.when.count
  if (spec.params.includes('分钟')) when['分钟'] = p.when.dwellMs / 60_000
  if (spec.params.includes('时刻') && p.when.atMin !== null) {
    when['时刻'] = String(Math.floor(p.when.atMin / 60)).padStart(2, '0') + ':' + String(p.when.atMin % 60).padStart(2, '0')
  }
  if (spec.params.includes('仅局内')) when['仅局内'] = p.when.inBout
  const out = {
    id: p.id,
    名称: p.name,
    状态: p.status,
    何时: when,
    动作: p.actions.map((a) => ({ [a.type]: { ...a.params } })),
    解除: [...p.exits],
  }
  if (p.cond !== null && p.cond !== undefined) out['条件'] = p.cond
  if (p.note) out['说明'] = p.note
  if (p.source) out['来源'] = p.source
  if (p.basis) out['依据'] = p.basis
  return out
}

// ---------------------------------------------------------------- 文档（存盘的那一份）

/** 一份空文档。 */
export function emptyDoc() {
  return { v: PRESETS_VERSION, custom: {}, overrides: {}, pending: [], history: {}, rt: {}, obs: {}, strict: null, boutApps: {}, versions: {} }
}

/** 读回来的文档缺什么补什么；坏的字段丢掉，不让一个坏文件拖垮执法。 */
export function normalizeDoc(raw) {
  const d = emptyDoc()
  if (!isPlain(raw)) return d
  for (const key of ['custom', 'overrides', 'history', 'rt', 'obs', 'boutApps', 'versions']) if (isPlain(raw[key])) d[key] = { ...raw[key] }
  if (Array.isArray(raw.pending)) d.pending = raw.pending.filter(isPlain).slice(-50)
  if (isPlain(raw.strict)) d.strict = { ...raw.strict }
  // 最后一次改动预案的时刻（A5 热生效的观察窗用它，见 presetwire.js）。
  if (Number(raw.revAt) > 0) d.revAt = Number(raw.revAt)
  return d
}

/** 内置预案（套上他改过的那部分）+ 新建的预案。坏的条目跳过。 */
export function allPresets(doc) {
  const d = normalizeDoc(doc)
  const out = []
  for (const b of BUILTINS) {
    const ov = isPlain(d.overrides[b.id]) ? d.overrides[b.id] : null
    const raw = ov && !b.fixed ? { ...b, ...ov, id: b.id, fixed: b.fixed } : b
    const v = validatePreset(raw, { builtin: true })
    const fallback = validatePreset(b, { builtin: true })
    const p = v.ok ? v.preset : fallback.preset
    out.push({ ...p, version: num(d.versions[b.id], 1) })
  }
  for (const [id, raw] of Object.entries(d.custom)) {
    if (BUILTIN_IDS.has(id)) continue
    const v = validatePreset({ ...raw, id })
    if (v.ok) {
      out.push({ ...v.preset, version: num(d.versions[id], 1), createdAt: num(raw.createdAt), shadowSince: num(raw.shadowSince),
        by: String(raw.by || ''), sunsetAt: num(raw.sunsetAt) })
    }
  }
  return out
}

export function presetOf(doc, id) {
  return allPresets(doc).find((p) => p.id === id) || null
}

/** 这一条此刻是否生效（影子不算）。 */
export function isActive(doc, id) {
  const p = presetOf(doc, id)
  return Boolean(p && p.status === '生效')
}

// ---------------------------------------------------------------- 改动的门槛

const actionKey = (a) => a.type + ':' + JSON.stringify(a.params)

/**
 * 一次改动是收紧还是放宽。
 * 条件变了、解除出口变多、动作变少、事件换了：代码判断不了是不是更松，一律按放宽算。
 * @returns {'tighten'|'loosen'|'same'}
 */
export function classifyChange(prev, next) {
  if (!prev) return STATUS_RANK[next.status] > 0 ? 'tighten' : 'same'
  let tighter = false
  let looser = false
  const sp = STATUS_RANK[prev.status]
  const sn = STATUS_RANK[next.status]
  if (sn > sp) tighter = true
  if (sn < sp) looser = true
  if (prev.when.event !== next.when.event || prev.when.inBout !== next.when.inBout) {
    // 仅局内 从 true 变 false = 范围变大 = 收紧；反过来放宽。换了事件算放宽。
    if (prev.when.event !== next.when.event) looser = true
    else if (next.when.inBout) looser = true
    else tighter = true
  }
  if (next.when.count < prev.when.count) tighter = true
  if (next.when.count > prev.when.count) looser = true
  if (next.when.windowMs > prev.when.windowMs) tighter = true
  if (next.when.windowMs < prev.when.windowMs) looser = true
  if (next.when.dwellMs < prev.when.dwellMs) tighter = true
  if (next.when.dwellMs > prev.when.dwellMs) looser = true
  if (prev.when.atMin !== next.when.atMin) looser = true
  if (JSON.stringify(prev.cond) !== JSON.stringify(next.cond)) looser = true
  const pa = new Set(prev.actions.map(actionKey))
  const na = new Set(next.actions.map(actionKey))
  for (const k of pa) if (!na.has(k)) looser = true
  for (const k of na) if (!pa.has(k)) tighter = true
  for (const e of next.exits) if (!prev.exits.includes(e)) looser = true
  for (const e of prev.exits) if (!next.exits.includes(e)) tighter = true
  if (looser) return 'loosen'
  if (tighter) return 'tighten'
  return 'same'
}

function writePreset(d, preset, raw, now, by) {
  const id = preset.id
  const prevVersion = num(d.versions[id], preset.builtin ? 1 : 0)
  const hist = Array.isArray(d.history[id]) ? d.history[id] : []
  const prev = presetOf(d, id)
  if (prev) hist.push({ version: prevVersion || 1, at: now, raw: rawOf(prev) })
  d.history[id] = hist.slice(-HISTORY_KEEP)
  d.versions[id] = (prevVersion || (prev ? 1 : 0)) + 1
  if (preset.builtin) {
    const ov = { ...rawOf(preset) }
    delete ov.id
    d.overrides[id] = ov
  } else {
    const keep = isPlain(d.custom[id]) ? d.custom[id] : {}
    const shadowSince = preset.status === '影子'
      ? (prev && prev.status === '影子' ? num(keep.shadowSince, now) : now)
      : num(keep.shadowSince)
    // AI 立的才有日落；他自己立的不会自动失效。续期只改 sunsetAt，不在这里动。
    const owner = keep.by || by
    const sunsetAt = num(keep.sunsetAt) || (owner === 'ai' ? now + SUNSET_MS : 0)
    d.custom[id] = { ...raw, id, createdAt: num(keep.createdAt, now), shadowSince, by: owner, sunsetAt }
  }
}

/**
 * 改一条预案（新建、改阈值、改状态都走这里）。
 * @param {object} doc
 * @param {{raw:object, by:'ai'|'user', now:number}} input
 * @returns {{ok:boolean, code?:string, message:string, doc:object, kind?:string, pendingId?:string, effectiveAt?:number}}
 */
export function proposeChange(doc, input) {
  const d = normalizeDoc(doc)
  const now = num(input.now, Date.now())
  const by = input.by === 'user' ? 'user' : 'ai'
  const raw = isPlain(input.raw) ? { ...input.raw } : null
  if (!raw) return { ok: false, code: 'not-object', message: '预案必须是对象', doc: d }
  const id = String(raw.id ?? '').trim()
  const prev = presetOf(d, id)
  const builtin = BUILTIN_IDS.has(id)
  if (prev?.fixed) return { ok: false, code: 'fixed', message: '「' + prev.name + '」这一轮不能改', doc: d }

  let rawNext = raw
  if (builtin) {
    // 内置预案只能改状态和阈值：动作、事件、解除出口沿用原样，免得改成别的东西。
    const base = BUILTINS.find((b) => b.id === id)
    const cur = prev ? rawOf(prev) : base
    const when = isPlain(raw['何时']) ? { ...cur['何时'], ...raw['何时'], 事件: base['何时']['事件'] } : cur['何时']
    rawNext = { ...base, ...cur, 状态: raw['状态'] ?? cur['状态'], 何时: when, 动作: base['动作'], 解除: base['解除'] }
  }
  // AI 新建：一律从影子开始，她写的「生效」不算数。
  let note = ''
  if (!prev && by === 'ai' && String(rawNext['状态'] ?? '影子') === '生效') {
    rawNext = { ...rawNext, 状态: '影子' }
    note = '新预案先跑影子。'
  }
  const v = validatePreset(rawNext, { builtin })
  if (!v.ok) return { ok: false, code: 'invalid', message: v.errors.map((e) => e.at + '：' + e.message).join('；'), doc: d, errors: v.errors }
  const next = v.preset

  // AI 想把影子转成生效：影子要跑满 24 小时。
  if (by === 'ai' && prev && !builtin && prev.status === '影子' && next.status === '生效') {
    const since = num(d.custom[id]?.shadowSince, now)
    if (now - since < SHADOW_MIN_MS) {
      return { ok: false, code: 'shadow-too-short', message: '影子还没跑满 24 小时，现在不能转生效（他自己可以）', doc: d }
    }
  }

  const kind = classifyChange(prev, next)
  if (kind === 'same' && prev) return { ok: true, kind, message: '没有变化', doc: d }
  if (kind === 'loosen') {
    if (by === 'ai') return { ok: false, code: 'needs-user', message: '这是放宽：只有他自己能改，而且要等 2 小时冷却期', doc: d }
    const pendingId = 'pend-' + id + '-' + now
    const effectiveAt = now + LOOSEN_COOLDOWN_MS
    d.pending = [...d.pending.filter((x) => x.presetId !== id), { id: pendingId, presetId: id, raw: rawOf(next), at: now, effectiveAt, by }]
    return { ok: true, kind, message: '放宽要等 2 小时冷却期，期间可以撤销', doc: d, pendingId, effectiveAt }
  }
  writePreset(d, next, rawOf(next), now, by)
  // 收紧时，同一条上挂着的放宽申请作废：不能先收紧再等冷却期过后悄悄松回去。
  d.pending = d.pending.filter((x) => x.presetId !== id)
  return { ok: true, kind, message: (note + (prev ? '已生效。' : '已建好。')).trim(), doc: d }
}

/** 撤销一条还在冷却期里的放宽。 */
export function cancelPending(doc, pendingId) {
  const d = normalizeDoc(doc)
  const before = d.pending.length
  d.pending = d.pending.filter((x) => x.id !== pendingId)
  return { ok: d.pending.length < before, doc: d }
}

/** 到期的放宽落地。 */
export function settlePending(doc, now) {
  const d = normalizeDoc(doc)
  const due = d.pending.filter((x) => num(x.effectiveAt) <= now)
  if (!due.length) return { doc: d, applied: [] }
  const applied = []
  for (const x of due) {
    const builtin = BUILTIN_IDS.has(x.presetId)
    const v = validatePreset({ ...x.raw, id: x.presetId }, { builtin })
    if (v.ok) {
      writePreset(d, v.preset, rawOf(v.preset), now, x.by)
      applied.push(x.presetId)
    }
  }
  d.pending = d.pending.filter((x) => num(x.effectiveAt) > now)
  return { doc: d, applied }
}

/**
 * 日落：AI 立的预案到了 30 天还没续期，自动停用。这不是「放宽」——是这条规则本来就只立了 30 天。
 * @returns {{doc:object, expired:string[]}}
 */
export function settleSunset(doc, now) {
  const d = normalizeDoc(doc)
  const expired = []
  for (const p of allPresets(d)) {
    if (p.builtin || !p.sunsetAt || p.sunsetAt > now || p.status === '停用') continue
    writePreset(d, { ...p, status: '停用' }, rawOf({ ...p, status: '停用' }), now, p.by || 'ai')
    expired.push(p.id)
  }
  return { doc: d, expired }
}

/**
 * 续期：再给 30 天。这一版只有他能续；等实验评估（K5）接上以后，评估有效的才允许鲸澜自己续。
 */
export function renew(doc, id, now, by) {
  const d = normalizeDoc(doc)
  const raw = d.custom[id]
  if (!isPlain(raw) || !num(raw.sunsetAt)) return { ok: false, code: 'no-sunset', message: '这条预案没有日落', doc: d }
  if (by !== 'user') return { ok: false, code: 'needs-user', message: '续期现在只有他能做', doc: d }
  d.custom[id] = { ...raw, sunsetAt: now + SUNSET_MS }
  return { ok: true, message: '续了 30 天', doc: d, sunsetAt: now + SUNSET_MS }
}

/** 某条预案的历史版本（新的在前）。 */
export function historyOf(doc, id) {
  const d = normalizeDoc(doc)
  return (Array.isArray(d.history[id]) ? [...d.history[id]] : []).reverse()
}

// ---------------------------------------------------------------- 本局的 App

/** 记下一局的必需应用和允许应用。 */
export function setBoutApps(doc, boutId, apps) {
  const d = normalizeDoc(doc)
  const clean = (list) => (Array.isArray(list) ? list : [])
    .map((x) => String(x).trim()).filter((x) => /^[A-Za-z0-9_.]+$/.test(x)).slice(0, 20)
  const need = clean(apps?.need)
  const allow = clean(apps?.allow)
  if (!boutId) return { ok: false, doc: d }
  d.boutApps[boutId] = { need, allow }
  // 只留最近 30 局的，不让这个表一直长。
  const keys = Object.keys(d.boutApps)
  if (keys.length > 30) for (const k of keys.slice(0, keys.length - 30)) delete d.boutApps[k]
  return { ok: true, doc: d, apps: { need, allow } }
}

export function boutAppsOf(doc, boutId) {
  const d = normalizeDoc(doc)
  const a = boutId ? d.boutApps[boutId] : null
  return a ? { need: [...(a.need || [])], allow: [...(a.allow || [])] } : { need: [], allow: [] }
}

/** 这一局声明过 App 没有。 */
export function declared(apps) {
  return Boolean(apps && (apps.need?.length || apps.allow?.length))
}

// ---------------------------------------------------------------- 观察：手机快照 → 事件

/**
 * 前台这个 App 算不算「出了本局」。
 * 声明过 App 的局：不在 need ∪ allow 里就算；没声明的局：只有娱乐类才算（没声明就不知道他该在哪）。
 * 桌面、系统界面、DSH 自己、紧急应用永远不算。
 */
export function isOff(app, ctx) {
  if (!app) return false
  if (NEUTRAL_PACKAGES.includes(app)) return false
  if ((ctx.ignore || []).includes(app)) return false
  const apps = ctx.apps || { need: [], allow: [] }
  if (declared(apps)) return ![...apps.need, ...apps.allow].includes(app)
  return ctx.fun === true
}

/**
 * 两次 /plan 之间发生了什么。
 * @param {object} obs   上一次的观察 {front, off, offSince, boutId, day, clockFired}
 * @param {{now:number, front:string|null, fun:boolean, minutes:number, bout:{id:string}|null,
 *          apps:{need:string[],allow:string[]}, ignore:string[], day:string, minute:number}} ctx
 * @returns {{events:object[], obs:object}}
 */
export function observe(obs, ctx) {
  const prev = isPlain(obs) ? obs : {}
  const now = num(ctx.now)
  const events = []
  const boutId = ctx.bout?.id || ''
  if (boutId !== (prev.boutId || '')) {
    if (prev.boutId) events.push({ type: '局结束', boutId: prev.boutId, at: now })
    if (boutId) events.push({ type: '局开始', boutId, at: now })
  }
  const front = ctx.front || null
  const off = isOff(front, ctx)
  let offSince = num(prev.offSince)
  // 手机报的包名序列（ctx.recent）：把两次 /plan 之间切走又切回来的那些补上。
  // 只算水位线（prev.switchedAt）之后的新切换，同一段绝不会数两遍。
  const recent = Array.isArray(ctx.recent) && ctx.recent.length ? ctx.recent : null
  let switchedAt = num(prev.switchedAt)
  if (recent) {
    const fresh = recent
      .map((r) => ({ app: String(r?.app || ''), at: now - Math.max(0, num(r?.agoMs)) }))
      .filter((r) => r.app && r.at > switchedAt)
      .sort((a, b) => a.at - b.at)
    for (const r of fresh) if (isOff(r.app, ctx)) events.push({ type: '切出', app: r.app, at: r.at })
    if (fresh.length) switchedAt = fresh[fresh.length - 1].at
  }
  if (off) {
    const fresh = !prev.off || prev.front !== front
    if (fresh) {
      // 有序列就以序列为准：同一个切换在这里再报一次就是重复计数。
      if (!recent) events.push({ type: '切出', app: front, at: now })
      // 手机报的「这个 App 已经开了几分钟」比我们上次看见它更早时，以手机为准。
      offSince = now - Math.max(0, num(ctx.minutes)) * 60_000
    }
    events.push({ type: '偏离停留', app: front, dwellMs: Math.max(0, now - offSince), since: offSince, at: now })
  } else {
    offSince = 0
  }
  events.push({ type: '巡检', at: now })
  events.push({ type: '常驻', at: now })
  const day = String(ctx.day || '')
  const clockFired = prev.day === day && isPlain(prev.clockFired) ? { ...prev.clockFired } : {}
  events.push({ type: '到点', minute: num(ctx.minute), day, at: now })
  return { events, obs: { front, off, offSince, boutId, day, clockFired, switchedAt } }
}

// ---------------------------------------------------------------- 匹配

/** 「何时」的一句话说明，用在锁屏页和账本里。 */
export function describeWhen(when) {
  const scope = when.inBout ? '局里' : ''
  switch (when.event) {
    case '切出': return scope + fmtMin(when.windowMs) + '内切出 ' + when.count + ' 次'
    case '偏离停留': return scope + '在不允许的 App 里待满 ' + fmtMin(when.dwellMs)
    case '敷衍': return scope + fmtMin(when.windowMs) + '内敷衍 ' + when.count + ' 次'
    case '局开始': return '一局开始'
    case '局结束': return '一局结束'
    case '到点': {
      const t = when.atMin ?? 0
      return '每天 ' + String(Math.floor(t / 60)).padStart(2, '0') + ':' + String(t % 60).padStart(2, '0')
    }
    default: return when.event
  }
}

/** 锁屏页那一句：哪条预案、为什么、怎么解除。 */
export function explain(preset) {
  return '预案「' + preset.name + '」：' + describeWhen(preset.when) + '。解除：' + preset.exits.join(' / ')
}

/**
 * 一批事件过一遍所有预案。
 * 只匹配「通用事件」：常驻 / 巡检 / 阶梯 这些内置的由 index.js 按 isActive() 自己处理，这里跳过。
 * @param {object} doc
 * @param {object[]} events
 * @param {{now:number, bout:object|null, data:object}} ctx  data 是给条件用的变量
 * @returns {{fires:{presetId:string, name:string, shadow:boolean, actions:object[], why:string, event:object}[],
 *            doc:object, errors:object[]}}
 */
export function match(doc, events, ctx) {
  const d = normalizeDoc(doc)
  const now = num(ctx.now)
  const fires = []
  const errors = []
  const boutId = ctx.bout?.id || ''
  for (const p of allPresets(d)) {
    if (p.status === '停用') continue
    if (EVENTS[p.when.event]?.builtin) continue
    if (p.actions.some((a) => ACTIONS[a.type]?.builtin)) continue
    const rt = isPlain(d.rt[p.id]) ? { ...d.rt[p.id] } : { hits: [], fired: '' }
    rt.hits = Array.isArray(rt.hits) ? rt.hits.filter((t) => now - num(t) <= (p.when.windowMs || 0)) : []
    let hit = null
    for (const ev of events) {
      if (ev.type !== p.when.event) continue
      if (p.when.inBout && !boutId) continue
      if (ev.type === '切出' || ev.type === '敷衍') {
        rt.hits.push(num(ev.at, now))
        if (rt.hits.length >= p.when.count) {
          hit = ev
          rt.hits = []
        }
      } else if (ev.type === '偏离停留') {
        const key = boutId + '@' + num(ev.since)
        if (num(ev.dwellMs) >= p.when.dwellMs && rt.fired !== key) {
          hit = ev
          rt.fired = key
        }
      } else if (ev.type === '到点') {
        const key = String(ev.day)
        if (p.when.atMin !== null && num(ev.minute) >= p.when.atMin && rt.fired !== key) {
          hit = ev
          rt.fired = key
        }
      } else {
        hit = ev
      }
      if (hit) break
    }
    d.rt[p.id] = rt
    if (!hit) continue
    const t = testLogic(p.cond, ctx.data || {})
    if (!t.ok) {
      errors.push({ presetId: p.id, error: t.error })
      continue
    }
    if (!t.value) continue
    fires.push({ presetId: p.id, name: p.name, shadow: p.status === '影子', actions: p.actions, why: explain(p), exits: p.exits, event: hit })
  }
  return { fires, doc: d, errors }
}

// ---------------------------------------------------------------- 动作 → 手机能执行的形状

/**
 * 把一次触发翻译成具体操作。index.js 按 op 落到手机（session / block / 墙收紧 / 计划模态 / Gemini）。
 * @param {object} fire
 * @param {{now:number, bout:{id:string}|null, apps:{need:string[],allow:string[]}, front:string|null}} ctx
 * @returns {object[]}
 */
export function plan(fire, ctx) {
  const now = num(ctx.now)
  const ops = []
  for (const a of fire.actions) {
    const p = a.params || {}
    switch (a.type) {
      case '严格模式': {
        const allowSpec = p['放行'] ?? '本局应用'
        const apps = ctx.apps || { need: [], allow: [] }
        const allow = allowSpec === '本局应用' ? [...new Set([...apps.need, ...apps.allow])] : [...allowSpec]
        const untilSpec = p['直到'] ?? '本局结束'
        const byBout = untilSpec === '本局结束'
        const until = byBout ? now + BOUT_END_CAP_MS : now + (parseDuration(untilSpec) || 30 * 60_000)
        ops.push({ op: 'strict', allow, until, boutId: byBout ? ctx.bout?.id || '' : '', why: fire.why, presetId: fire.presetId })
        break
      }
      case '锁': {
        const app = (p['应用'] ?? '当前') === '当前' ? ctx.front : p['应用']
        if (app) ops.push({ op: 'block', app, until: now + (parseDuration(p['时长'] ?? '10m') || 600_000), why: fire.why })
        break
      }
      case '全机锁':
        ops.push({ op: 'all-lock', until: now + (parseDuration(p['时长'] ?? '10m') || 600_000), why: fire.why })
        break
      case '开工具':
        ops.push({ op: 'tool', tool: String(p['工具'] ?? 'gemini'), until: now + (parseDuration(p['时长'] ?? '10m') || 600_000),
          topic: String(p['话题'] ?? fire.name).slice(0, 100), why: fire.why })
        break
      case '进入计划':
        ops.push({ op: 'plan', until: now + (parseDuration(p['时长'] ?? '10m') || 600_000), why: fire.why })
        break
      case '说':
        ops.push({ op: 'say', text: String(p['话'] ?? '').slice(0, SAY_MAX) })
        break
      case '墙收紧':
        ops.push({ op: 'tighten', why: fire.why })
        break
      case '记账':
        ops.push({ op: 'log' })
        break
      default:
        break
    }
  }
  // 「说」并进严格模式 / 锁的那句解释里：锁屏页只有一行，先说发生了什么，再说哪条预案。
  const say = ops.filter((o) => o.op === 'say').map((o) => o.text).join(' ')
  if (say) for (const o of ops) if (o.why) o.why = say + ' ' + o.why
  return ops
}

// ---------------------------------------------------------------- 严格模式

/** 进严格模式。已经在严格模式里：放行名单取交集（只会更严），到期取更晚。 */
export function enterStrict(doc, op, now) {
  const d = normalizeDoc(doc)
  const cur = strictView(d, now, op.boutId || null)
  if (cur) {
    const allow = cur.allow.filter((x) => op.allow.includes(x))
    d.strict = { ...cur, allow, until: Math.max(cur.until, op.until), why: op.why, presetId: op.presetId, at: now }
  } else {
    d.strict = { allow: [...op.allow], until: op.until, boutId: op.boutId || '', why: op.why, presetId: op.presetId, at: now }
  }
  return d
}

/**
 * 此刻的严格模式；没有返回 null。
 * 绑了局的（直到：本局结束）：那一局收了就结束，不等 until。
 * @param {string|null} runningBoutId  现在在跑的局；null = 没有局
 */
export function strictView(doc, now, runningBoutId) {
  const s = normalizeDoc(doc).strict
  if (!s || !(num(s.until) > now)) return null
  if (s.boutId && s.boutId !== (runningBoutId || '')) return null
  return { allow: Array.isArray(s.allow) ? [...s.allow] : [], until: num(s.until), boutId: String(s.boutId || ''),
    why: String(s.why || ''), presetId: String(s.presetId || ''), at: num(s.at) }
}

/** 收掉严格模式（破窗、局结束时由调用方决定要不要调）。 */
export function clearStrict(doc) {
  const d = normalizeDoc(doc)
  d.strict = null
  return d
}
