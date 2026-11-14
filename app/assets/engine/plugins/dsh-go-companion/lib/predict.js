// dsh-go-companion · 预判账本（猜错要认；判对判错不由猜的人说了算）
//
// 借的是 generative_agents 的证据绑定：一条洞察必须有能映射回数据的编号，代码说了算。
// 落到这里就是：鲸澜可以写 claim、可以写自己的解读，但**不能写 verdict**。
// verdict 只由 settle() 按墙的遥测算出来，verdict_source 固定 WALL_TELEMETRY，
// 模型想自己说一句"果然重演了"是没门的——它写的东西进不了这个字段。
//
// 总纲 §13.3 的另一半：天级意图（"明天会先折腾 UI 周边"）必须翻译成可观测的代理指标，
// 否则没有 evidence 可挂。所以 metric 只能是 METRICS 这五个，翻译不出来的不许落盘。

import { pathCandidates, statOf } from './wall_auditor.js'

/** 判分者只有一个：墙的遥测。模型无权写入。 */
export const VERDICT_SOURCE = 'WALL_TELEMETRY'

/** 可用代理指标。都是 wall_auditor.js 已经在量的东西，不新造采集。 */
export const METRICS = Object.freeze([
  { id: 'file_growth', unit: '字节', arg: 'path', hint: '某个笔记文件在立项后增长了多少字节' },
  { id: 'screenshot_count', unit: '张', arg: null, hint: '立项后新截图张数' },
  { id: 'app_minutes', unit: '分钟', arg: 'app', hint: '某个 App 今天的分钟数' },
  { id: 'fun_minutes', unit: '分钟', arg: null, hint: '娱乐类 App 今天合计分钟数' },
  { id: 'tasks_ratio', unit: '比例', arg: null, hint: '今天定的任务里完成的比例（0–1）' },
])

export const OPS = Object.freeze(['>', '>=', '<', '<='])

const MAX_PENDING = 40
const MAX_METRICS = 3
const MAX_REFS = 5
const DUE_HOURS_MAX = 168

const METRIC_BY_ID = Object.fromEntries(METRICS.map((m) => [m.id, m]))

export function emptyLedger() {
  return { v: 1, day: '', seq: 0, list: [] }
}

export function cleanLedger(raw) {
  const out = emptyLedger()
  if (!raw || typeof raw !== 'object') return out
  if (typeof raw.day === 'string') out.day = raw.day
  out.seq = Number.isFinite(Number(raw.seq)) ? Number(raw.seq) : 0
  out.list = (Array.isArray(raw.list) ? raw.list : []).filter((r) => r && typeof r === 'object' && r.id)
  return out
}

// ---------------------------------------------------------------- 落盘前验真

function num(v) {
  // 注意 Number(null) === 0：不显式挡掉的话，"量不到"会被当成"量到 0"，把人冤枉成没做。
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 验一条预判能不能落盘。这里只做两件事：形状对不对、能不能被遥测判定。
 * 判据本身（阈值定得合不合理）不归我们管——那是他自己的赌注。
 * @returns {{ok:true, value:object} | {ok:false, problems:string[]}}
 */
export function checkClaim(raw, { now, day, stat = statOf, notesDir } = {}) {
  const problems = []
  const at = Number(now) || Date.now()
  const claim = String(raw?.claim ?? '').trim().slice(0, 200)
  if (claim.length < 6) problems.push('claim 太短，写一句能被证伪的话（"明天会先干 A 再干 B"这种）。')

  const dueHours = num(raw?.dueInHours) ?? 20
  if (dueHours < 1 || dueHours > DUE_HOURS_MAX) problems.push('dueInHours 要在 1–' + DUE_HOURS_MAX + ' 之间。')

  const refs = (Array.isArray(raw?.evidenceRefs) ? raw.evidenceRefs : [])
    .map((r) => String(r ?? '').trim().slice(0, 120))
    .filter(Boolean)
  // 硬约束：没有 evidence_refs 的预判不允许落盘。说的是"到时候拿什么撞它"。
  if (!refs.length) problems.push('必须给 evidenceRefs：这条预判到时候拿什么撞（例如 审计/2026-11-10.md、app:刷题、file:大肥鱼/周报.md）。')
  else if (refs.length > MAX_REFS) problems.push('evidenceRefs 最多 ' + MAX_REFS + ' 条。')

  const rawMetrics = Array.isArray(raw?.metrics) ? raw.metrics : []
  if (!rawMetrics.length) problems.push('必须给至少 1 个 metrics——天级意图翻不成代理指标就没法判，等于不许落盘。')
  if (rawMetrics.length > MAX_METRICS) problems.push('metrics 最多 ' + MAX_METRICS + ' 条。')

  const metrics = []
  for (const m of rawMetrics) {
    const spec = METRIC_BY_ID[String(m?.metric ?? '')]
    if (!spec) {
      problems.push('metric「' + String(m?.metric ?? '') + '」不认识。只能用：' + METRICS.map((x) => x.id).join(' / '))
      continue
    }
    const op = String(m?.op ?? '')
    if (!OPS.includes(op)) {
      problems.push('「' + spec.id + '」的 op 要用 ' + OPS.join(' 或 ') + '。')
      continue
    }
    const threshold = num(m?.threshold)
    if (threshold === null) {
      problems.push('「' + spec.id + '」缺 threshold。')
      continue
    }
    const one = { metric: spec.id, op, threshold }
    if (spec.arg === 'path') {
      const wanted = String(m?.path ?? '').trim().slice(0, 200)
      if (!wanted) {
        problems.push('「file_growth」要给 path。')
        continue
      }
      // 路径可能是相对笔记库的（大肥鱼/周报.md），按墙的同一套候选解析，取能查到的那一个。
      const cands = pathCandidates(wanted, notesDir)
      if (!cands.length) {
        problems.push('「file_growth」的 path「' + wanted + '」不像个文件路径。')
        continue
      }
      const hit = cands.find((p) => stat(p)) || cands[0]
      // 立项时先记下现在的字节数，到时候只量增量——和墙审文件的口径一致。
      const st = stat(hit)
      one.path = hit
      one.baseSize = st ? Number(st.size) || 0 : 0
    } else if (spec.arg === 'app') {
      const app = String(m?.app ?? '').trim().slice(0, 40)
      if (!app) {
        problems.push('「app_minutes」要给 app 名字。')
        continue
      }
      one.app = app
    }
    metrics.push(one)
  }

  if (problems.length) return { ok: false, problems }
  return {
    ok: true,
    value: {
      id: '',
      claim,
      createdAt: at,
      dueAt: at + dueHours * 3_600_000,
      day: String(day || ''),
      metrics,
      evidenceRefs: refs,
      verdict: null,
      verdictSource: VERDICT_SOURCE,
      settledAt: 0,
      readings: null,
      revision: null,
    },
  }
}

/** 编号：P-日期-序号。序号按当天已有条数递增，方便人肉对账。 */
export function appendPrediction(ledger, record) {
  const out = cleanLedger(ledger)
  const day = String(record?.day || '')
  const seq = out.day === day ? out.seq + 1 : 1
  out.day = day
  out.seq = seq
  const rec = { ...record, id: 'P-' + (day.replaceAll('-', '') || 'X') + '-' + String(seq).padStart(2, '0') }
  out.list = [...out.list, rec].slice(-MAX_PENDING)
  return { ledger: out, record: rec }
}

// ---------------------------------------------------------------- 判分

/** 量到的当前值。量不到就是 null——不猜、不用旧值顶。 */
function readValue(m, r) {
  if (!r) return null
  if (m.metric === 'file_growth') {
    const cur = num(r.fileSize?.[m.path])
    return cur === null ? null : cur - Number(m.baseSize || 0)
  }
  if (m.metric === 'screenshot_count') return num(r.screenshots)
  if (m.metric === 'app_minutes') {
    const exact = num(r.appMinutes?.[m.app])
    if (exact !== null) return exact
    // App 名字常对不齐（"抖音" vs "抖音极速版"），按墙的同一套规则模糊认一次。
    const want = String(m.app || '')
    for (const [label, v] of Object.entries(r.appMinutes || {})) {
      if (label && want && (label.includes(want) || want.includes(label))) return num(v)
    }
    return null
  }
  if (m.metric === 'fun_minutes') return num(r.funMinutes)
  if (m.metric === 'tasks_ratio') return num(r.tasksRatio)
  return null
}

function holds(value, op, threshold) {
  if (op === '>') return value > threshold
  if (op === '>=') return value >= threshold
  if (op === '<') return value < threshold
  return value <= threshold
}

/**
 * 判一条已到期的预判。全部指标都成立才算说中；任一不成立就是没说中；
 * 有量不到的（墙那天没这个数据）记 unknown——没有证据不算说中，也不冤枉他。
 */
export function judge(record, readings) {
  const seen = []
  let unknown = false
  for (const m of record.metrics || []) {
    const v = readValue(m, readings)
    seen.push({ metric: m.metric, ...(m.path ? { path: m.path } : {}), ...(m.app ? { app: m.app } : {}), value: v, op: m.op, threshold: m.threshold })
    if (v === null) {
      unknown = true
      continue
    }
    if (!holds(v, m.op, m.threshold)) return { verdict: 'miss', readings: seen }
  }
  return { verdict: unknown ? 'unknown' : 'hit', readings: seen }
}

/** 到期待判的条数。用来决定值不值得去扫截图（那是最贵的一步）。 */
export function dueCount(ledger, now) {
  return (ledger?.list || []).filter((r) => !r.verdict && Number(r.dueAt) <= Number(now)).length
}

/**
 * 结算所有到期的预判。verdict / verdictSource / settledAt / readings 只有这里能写。
 * @returns {{ledger:object, settled:object[]}}
 */
export function settle(ledger, readings, now) {
  const out = cleanLedger(ledger)
  const settled = []
  const at = Number(now)
  out.list = out.list.map((r) => {
    if (r.verdict || Number(r.dueAt) > at) return r
    const g = judge(r, readings)
    const next = { ...r, verdict: g.verdict, verdictSource: VERDICT_SOURCE, settledAt: at, readings: g.readings }
    settled.push(next)
    return next
  })
  return { ledger: out, settled }
}

// ---------------------------------------------------------------- 给模型看

const OP_WORD = { '>': '超过', '>=': '不低于', '<': '低于', '<=': '不高于' }

function metricText(m) {
  const spec = METRIC_BY_ID[m.metric]
  // 带上 metric id：他下次自己押的时候要照着这个名字写，不能自造。
  const where = m.path ? ': ' + m.path : m.app ? ': ' + m.app : ''
  return (spec?.hint || m.metric) + '（' + m.metric + where + '）' + OP_WORD[m.op] + ' ' + m.threshold + (spec?.unit || '')
}

/** 拼进 prompt 的几行：你押过的、还欠着的、押错的。 */
export function predictionLines(ledger, now) {
  const list = Array.isArray(ledger?.list) ? ledger.list : []
  if (!list.length) return []
  const at = Number(now)
  const lines = []
  const pending = list.filter((r) => !r.verdict && Number(r.dueAt) > at)
  const fresh = list.filter((r) => r.verdict && r.settledAt && at - Number(r.settledAt) <= 3 * 86_400_000)
  const wrong = fresh.filter((r) => r.verdict === 'miss')
  const right = fresh.filter((r) => r.verdict === 'hit')
  if (pending.length) {
    lines.push('你押过、还没到期的（到点了墙会拿遥测撞它，成不成不由你说）：')
    for (const r of pending.slice(-3)) lines.push('- ' + r.claim + ' ｜判据：' + (r.metrics || []).map(metricText).join('，且'))
  }
  if (right.length) {
    lines.push('撞过的，说中的：')
    for (const r of right.slice(-2)) lines.push('- ' + r.claim)
  }
  if (wrong.length) {
    lines.push('撞过的，说错的——这些是你猜偏了的地方，别装作一直都懂：')
    for (const r of wrong.slice(-3)) lines.push('- ' + r.claim + '（实测：' + readingsText(r.readings) + '）')
  }
  if (!lines.length) return []
  lines.push('想再押一天就往 submit_prediction 交一条：写清 claim、拿什么撞（evidenceRefs）、怎么算说中（metrics）。押错的认账，别急着找理由。')
  return lines
}

function readingsText(readings) {
  const list = Array.isArray(readings) ? readings : []
  return list
    .map((r) => (r.metric === 'file_growth' ? (r.path || '文件') : r.app || r.metric) + ' ' + (r.value === null ? '量不到' : r.value))
    .join('，')
}
