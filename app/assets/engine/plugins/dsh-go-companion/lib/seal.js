// dsh-go-companion · 封印（验收点 · 2026-11-20 第一批）
//
// 体系里唯一坚硬的地方：冷静的他（前一晚）立约，第二天的他只执行——没有改约的口子。
//   · 封印只追加：拉伸目标 / 保底线落盘后只能往上加，不能调低、不能删（I15）。
//   · 打分只靠代码：题库答对数、笔记文件增量、开工后的截图张数、App 用时；没有「口头」这一类（I17）。
//     外部 App 刷题（粉笔这类）他报数，但不超过「当天截图 ≥1 张 + 该 App 用时 / 0.5 分钟」的合理上限。
//   · 到点（缓冲 1 分钟）没过保底：转严格锁，交够保底就解，最长 60 分钟；紧急解除照常。
//   · 奖励进挣来制：拉伸 ×1.25、保底 ×1.0、未达 0；当天现定 ×0.7；过了点才交够 ×0.5。
//   · 豁免要写理由、占当天一次紧急解除（调用方扣），原始数字永远留着。
// 纯函数：时间、证据事实（题库 / 文件 / 截图 / App 分钟）都由调用方给。

export const SEAL_KINDS = Object.freeze({
  quiz: { label: 'DSH 题库', unit: '题' },
  external_quiz: { label: '外部 App 刷题', unit: '题' },
  file: { label: '笔记 / 文件', unit: '字' },
  screenshots: { label: '截图', unit: '张' },
  app_minutes: { label: 'App 用时', unit: '分钟' },
})

export const SEAL_CUTOFF_MINUTE = 23 * 60
export const SEAL_GRACE_MS = 60_000
export const SEAL_LOCK_MAX_MS = 60 * 60_000
export const SAME_DAY_FACTOR = 0.7
export const LATE_FACTOR = 0.5
export const TIER_FACTOR = { stretch: 1.25, floor: 1.0, below: 0 }
export const MAX_SEALS_PER_DAY = 5
export const EXTERNAL_MIN_PER_ITEM = 0.5
const KEEP_DAYS = 60

const int = (v, d = 0) => (Number.isFinite(Number(v)) ? Math.round(Number(v)) : d)
const str = (v, max) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max)
const YMD = /^\d{4}-\d{2}-\d{2}$/

export function cleanSeal(raw) {
  if (!raw || typeof raw !== 'object') return null
  const id = str(raw.id, 40)
  const title = str(raw.title, 80)
  const day = YMD.test(String(raw.day || '')) ? String(raw.day) : ''
  if (!id || !title || !day || !SEAL_KINDS[raw.kind]) return null
  const stretch = Math.max(1, int(raw.stretch, 1))
  return {
    id,
    day,
    title,
    kind: raw.kind,
    stretch,
    floor: Math.max(1, Math.min(stretch, int(raw.floor, Math.ceil(stretch / 2)))),
    dueMinute: Math.max(0, Math.min(1439, int(raw.dueMinute, 18 * 60))),
    estMinutes: Math.max(10, Math.min(240, int(raw.estMinutes, 45))),
    sealedAt: int(raw.sealedAt, 0),
    sameDay: raw.sameDay === true,
    horizonId: str(raw.horizonId, 64),
    nodeId: str(raw.nodeId, 40),
    path: str(raw.path, 300),
    app: str(raw.app, 120),
    appLabel: str(raw.appLabel, 40),
    baseline: raw.baseline && typeof raw.baseline === 'object'
      ? { at: int(raw.baseline.at, 0), correct: int(raw.baseline.correct, 0), bytes: int(raw.baseline.bytes, -1) }
      : null,
    claimed: Math.max(0, int(raw.claimed, 0)),
    actual: Math.max(0, int(raw.actual, 0)),
    status: ['open', 'floor', 'stretch', 'below', 'waived'].includes(raw.status) ? raw.status : 'open',
    paidMinutes: Math.max(0, int(raw.paidMinutes, 0)),
    lockUntil: int(raw.lockUntil, 0),
    waivedReason: str(raw.waivedReason, 120),
    raisedAt: int(raw.raisedAt, 0),
  }
}

export function cleanSeals(raw, today = '') {
  const items = (Array.isArray(raw?.items) ? raw.items : []).map(cleanSeal).filter(Boolean)
  const keep = today ? items.filter((s) => daysBetween(s.day, today) <= KEEP_DAYS) : items
  return { v: 1, items: keep.slice(-400) }
}

function daysBetween(a, b) {
  return Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86_400_000)
}

export function nextYmd(ymd) {
  return new Date(Date.parse(ymd + 'T00:00:00Z') + 86_400_000).toISOString().slice(0, 10)
}

/**
 * 封一条。target: 'tomorrow'（默认，前一晚立约）| 'today'（当天现定，×0.7）。
 * 过了 23:00 再封明天的，也算当天现定（冷静期已过）。一天最多 5 条。
 */
export function addSeal(stateRaw, input = {}, { now = Date.now(), today, minuteOfDay = 0 } = {}) {
  const s = cleanSeals(stateRaw, today)
  const target = input.target === 'today' ? 'today' : 'tomorrow'
  const day = target === 'today' ? today : nextYmd(today)
  const sameDay = target === 'today' || minuteOfDay >= SEAL_CUTOFF_MINUTE
  if (s.items.filter((x) => x.day === day).length >= MAX_SEALS_PER_DAY) {
    return { ok: false, error: 'too-many', text: '一天最多封 ' + MAX_SEALS_PER_DAY + ' 条：少而硬。', state: s }
  }
  const kind = SEAL_KINDS[input.kind] ? input.kind : ''
  if (!kind) return { ok: false, error: 'bad-kind', text: '要选一种代码验得了的证据：题库 / 外部刷题 / 笔记文件 / 截图 / App 用时。', state: s }
  if ((kind === 'file') && !str(input.path, 300)) return { ok: false, error: 'no-path', text: '笔记类要写清是哪份文件（笔记库里的路径）。', state: s }
  if ((kind === 'app_minutes' || kind === 'external_quiz') && !str(input.app, 120)) return { ok: false, error: 'no-app', text: '要选是哪个 App。', state: s }
  const seal = cleanSeal({
    ...input,
    id: 'seal-' + day.replace(/-/g, '') + '-' + Number(now).toString(36).slice(-5),
    day,
    kind,
    sealedAt: now,
    sameDay,
    status: 'open',
    paidMinutes: 0,
    actual: 0,
    claimed: 0,
    baseline: null,
    lockUntil: 0,
  })
  if (!seal) return { ok: false, error: 'bad-seal', text: '要有标题和拉伸目标。', state: s }
  if (target === 'today' && seal.dueMinute <= minuteOfDay) return { ok: false, error: 'past-due', text: '截止时间已经过了。', state: s }
  return { ok: true, seal, state: { ...s, items: [...s.items, seal] } }
}

/** 加码：只能往上（I15）。往下的请求一律拒绝，原值不动。 */
export function raiseSeal(stateRaw, id, { stretch, floor } = {}, { now = Date.now() } = {}) {
  const s = cleanSeals(stateRaw)
  const cur = s.items.find((x) => x.id === id)
  if (!cur) return { ok: false, error: 'no-seal', state: s }
  const ns = stretch === undefined ? cur.stretch : int(stretch, cur.stretch)
  const nf = floor === undefined ? cur.floor : int(floor, cur.floor)
  if (ns < cur.stretch || nf < cur.floor) {
    return { ok: false, error: 'cannot-lower', text: '封印只能加码，不能调低。', state: s }
  }
  const next = cleanSeal({ ...cur, stretch: ns, floor: Math.min(nf, ns), raisedAt: now })
  return { ok: true, seal: next, state: { ...s, items: s.items.map((x) => (x.id === id ? next : x)) } }
}

/** 外部 App 刷题：他报做了几题（只能报多，不能改少；有上限，见 measure）。 */
export function claimSeal(stateRaw, id, count) {
  const s = cleanSeals(stateRaw)
  const cur = s.items.find((x) => x.id === id)
  if (!cur || cur.kind !== 'external_quiz') return { ok: false, error: 'not-external', state: s }
  const c = Math.max(cur.claimed, Math.max(0, int(count, 0)))
  const next = { ...cur, claimed: c }
  return { ok: true, seal: next, state: { ...s, items: s.items.map((x) => (x.id === id ? next : x)) } }
}

/**
 * 证据 → 做到了多少（只有代码，不读任何一句话）。
 * facts: { bankCorrect, fileBytes: {path: bytes}, screenshotsSince: number, appMinutes: {pkg: min} }
 */
export function measure(seal, facts = {}) {
  const b = seal.baseline || { correct: 0, bytes: -1 }
  const appMin = (pkg) => Math.max(0, int(facts.appMinutes?.[pkg], 0))
  switch (seal.kind) {
    case 'quiz':
      return Math.max(0, int(facts.bankCorrect, 0) - b.correct)
    case 'external_quiz': {
      if (int(facts.screenshotsSince, 0) < 1) return 0
      const plausible = Math.floor(appMin(seal.app) / EXTERNAL_MIN_PER_ITEM)
      return Math.min(seal.claimed, plausible)
    }
    case 'file': {
      const now = int(facts.fileBytes?.[seal.path], -1)
      if (now < 0 || b.bytes < 0) return 0
      return Math.max(0, Math.floor((now - b.bytes) / 3))
    }
    case 'screenshots':
      return Math.max(0, int(facts.screenshotsSince, 0))
    case 'app_minutes':
      return appMin(seal.app)
    default:
      return 0
  }
}

export function tierFor(seal, actual) {
  if (actual >= seal.stretch) return 'stretch'
  if (actual >= seal.floor) return 'floor'
  return 'below'
}

/** 这一条该给多少娱乐分钟（未打折的底数按预计时长 1/3，夹在 10–40）。 */
export function rewardMinutes(seal, tier, { late = false } = {}) {
  const base = Math.max(10, Math.min(40, Math.round(seal.estMinutes / 3)))
  let m = base * (TIER_FACTOR[tier] || 0)
  if (seal.sameDay) m *= SAME_DAY_FACTOR
  if (late) m *= LATE_FACTOR
  return Math.round(m)
}

const RANK = { open: 0, below: 0, floor: 1, stretch: 2 }

/**
 * 每拍一次：量证据 → 档位上去了就补发差额（只升不降、不重复发）→ 到点没过保底就锁（最长 60 分钟）。
 * dueAtOf(seal) → 截止时刻（毫秒）。只看今天的封印。
 * @returns {{state, pays:[{id, minutes, tier, late}], changed:boolean}}
 */
export function tickSeals(stateRaw, facts, { now = Date.now(), today, dueAtOf }) {
  const s = cleanSeals(stateRaw, today)
  const pays = []
  let changed = false
  const items = s.items.map((seal) => {
    if (seal.day !== today || seal.status === 'waived') return seal
    const actual = measure(seal, facts)
    const dueAt = dueAtOf(seal)
    const late = now > dueAt + SEAL_GRACE_MS
    const tier = tierFor(seal, actual)
    let next = actual !== seal.actual ? { ...seal, actual } : seal
    if (RANK[tier] > RANK[next.status] && tier !== 'below') {
      const total = rewardMinutes(next, tier, { late })
      const pay = Math.max(0, total - next.paidMinutes)
      next = { ...next, status: tier, paidMinutes: next.paidMinutes + pay, lockUntil: 0 }
      if (pay > 0) pays.push({ id: next.id, minutes: pay, tier, late, title: next.title })
    } else if (tier === 'below' && late && next.status === 'open') {
      next = { ...next, status: 'below', lockUntil: dueAt + SEAL_GRACE_MS + SEAL_LOCK_MAX_MS }
    }
    if (next !== seal) changed = true
    return next
  })
  return { state: { ...s, items }, pays, changed }
}

/** 现在锁着的那一条（到点没过保底、60 分钟还没到、也没交够）。 */
export function lockingSeal(stateRaw, { now = Date.now(), today }) {
  const s = cleanSeals(stateRaw, today)
  return s.items.find((x) => x.day === today && x.status === 'below' && x.lockUntil > now) || null
}

/** 豁免：要写理由；调用方负责扣一次紧急解除。原始数字不动。 */
export function waiveSeal(stateRaw, id, reason) {
  const s = cleanSeals(stateRaw)
  const cur = s.items.find((x) => x.id === id)
  const why = str(reason, 120)
  if (!cur) return { ok: false, error: 'no-seal', state: s }
  if (why.length < 2) return { ok: false, error: 'no-reason', text: '豁免要写一句理由。', state: s }
  if (cur.status === 'floor' || cur.status === 'stretch') return { ok: false, error: 'already-done', text: '已经过了，不用豁免。', state: s }
  const next = { ...cur, status: 'waived', waivedReason: why, lockUntil: 0 }
  return { ok: true, seal: next, state: { ...s, items: s.items.map((x) => (x.id === id ? next : x)) } }
}

/** 今天开始时记下基线（题库答对数、文件字节），之后的增量才算这一天做的。 */
export function captureBaselines(stateRaw, facts, { now = Date.now(), today }) {
  const s = cleanSeals(stateRaw, today)
  let changed = false
  const items = s.items.map((seal) => {
    if (seal.day !== today || seal.baseline) return seal
    changed = true
    return { ...seal, baseline: { at: now, correct: int(facts.bankCorrect, 0), bytes: int(facts.fileBytes?.[seal.path], -1) } }
  })
  return { state: { ...s, items }, changed }
}

/** 进两姐妹上下文的几行：封印改不了，她们也别替他求情。 */
export function sealPromptLines(stateRaw, { today, hm = (m) => String(m) }) {
  const s = cleanSeals(stateRaw, today)
  const todays = s.items.filter((x) => x.day === today)
  if (!todays.length) return []
  const lines = todays.map((x) => {
    const st = x.status === 'stretch' ? '已过拉伸' : x.status === 'floor' ? '已过保底' : x.status === 'waived' ? '已豁免' : x.status === 'below' ? '到点没过保底（锁着）' : '进行中'
    return '今天的封印「' + x.title + '」：' + x.actual + '/' + x.stretch + SEAL_KINDS[x.kind].unit + '（保底 ' + x.floor + '），' + hm(x.dueMinute) + ' 前，' + st
  })
  lines.push('封印是他前一晚冷静时定的：只能加码，谁都改不了。他想改小、想说「本来只打算做一点」，不要接，也别替他求情——让他去交。')
  return lines
}
