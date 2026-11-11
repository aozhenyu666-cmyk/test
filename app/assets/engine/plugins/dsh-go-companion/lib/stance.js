// dsh-go-companion · 立场账本（R7 A1：两条鱼不被同化）
//
// 问题：她今天说「这件事必须 6 点前做完」，被磨三句就改口「那明天也行」。
// 这里把「她说过什么」变成约束层对象，让改口有代价：
//   hard 立场在 until 之前**没有修改它的工具**——只有三件事能让它变：
//   到期、墙按证据判定完成、用户在晨间窗口重定计划。
//   soft 可以改，但每次改必须带 reason 且引用一条**立场之后**新出现的账本证据；
//   「他说不想做」不算证据（classify 判为 avoid/refuse 的话语不能当来源）。
//
// 结算不由模型写：kept/broken 只由 settleStances() 按墙的遥测算，settleSource 固定。

import { classifyReply } from './classify.js'

/** 判分者只有一个：墙的遥测。模型无权写入。 */
export const SETTLE_SOURCE = 'WALL_TELEMETRY'

/** 每天 hard 立场基准额度（命中率高时放宽到 HARD_TOP，见 hardQuota）。 */
export const HARD_BASE = 3
export const HARD_TOP = 4
export const HARD_FLOOR = 2

/** 每天 soft 立场上限。 */
export const SOFT_MAX = 5

/** 一条立场的原话上限（字）。 */
export const TEXT_MAX = 120

/** until 最多能立多久之后（立场不是许愿，不能立到明年）。 */
export const UNTIL_MAX_MS = 14 * 86_400_000

/** 账本保留条数。立场是短期承诺，不需要无限增长。 */
const MAX_LIST = 60

export const KINDS = ['hard', 'soft']
export const STATUSES = ['held', 'revised', 'expired', 'kept', 'broken']

/** 拒绝/回避类话语不能当证据：那不是新事实，只是他不想做。 */
const NOT_EVIDENCE = new Set(['avoid', 'refuse', 'evasive'])

export function emptyStances() {
  return { v: 1, day: '', seq: 0, list: [] }
}

export function cleanStances(raw) {
  const out = emptyStances()
  if (!raw || typeof raw !== 'object') return out
  if (typeof raw.day === 'string') out.day = raw.day
  out.seq = Number.isFinite(Number(raw.seq)) ? Number(raw.seq) : 0
  out.list = (Array.isArray(raw.list) ? raw.list : [])
    .filter((r) => r && typeof r === 'object' && r.id)
    .map((r) => ({
      ...r,
      // 结算字段一律按记录原样保留，但只认我们写过的来源；模型塞进来的会被 settleStances 覆盖。
      status: STATUSES.includes(r.status) ? r.status : 'held',
    }))
    .slice(-MAX_LIST)
  return out
}

/**
 * 当天 hard 额度随命中率浮动（2–4 条）。命中率高说明她立得准，可以多立几条；
 * 一直立空话就收紧，逼她挑重要的。
 * @param {number|null} hitRate 0–1；没有历史就按基准
 */
export function hardQuota(hitRate) {
  // Number(null) === 0：不显式挡掉的话"没有历史"会被当成"命中率 0"，额度直接砍到最低。
  if (hitRate === null || hitRate === undefined || hitRate === '') return HARD_BASE
  const r = Number(hitRate)
  if (!Number.isFinite(r)) return HARD_BASE
  if (r >= 0.8) return HARD_TOP
  if (r < 0.4) return HARD_FLOOR
  return HARD_BASE
}

const dayOf = (rec) => String(rec?.day || '')
const heldOn = (list, day, kind) => (list || []).filter((r) => dayOf(r) === day && r.kind === kind)

/**
 * 验一条立场能不能立。这里只做代码能负责的事：形状、对象认不认得、额度、证据在不在。
 * 立得对不对不归我们管——那是她自己的判断。
 * @returns {{ok:true, value:object} | {ok:false, code:string, problems:string[]}}
 */
export function checkStance(raw, { now, day, known, ledger, hitRate } = {}) {
  const problems = []
  const at = Number(now) || Date.now()
  const cur = cleanStances(ledger)
  const text = String(raw?.text ?? '').replace(/\s+/g, ' ').trim()
  const kind = String(raw?.kind ?? '').trim().toLowerCase()
  const about = String(raw?.about ?? '').trim().slice(0, 120)

  if (!text) problems.push('text 是空的：把她原话写下来。')
  else if (text.length < 4) problems.push('太短，写清她到底要求什么。')
  else if (text.length > TEXT_MAX) problems.push('一条立场最多 ' + TEXT_MAX + ' 字，长的拆成两条。')

  if (!KINDS.includes(kind)) problems.push('kind 只能是 hard 或 soft（hard = 到期前不能改）。')

  // 立场绑不到具体对象就会变成"我就是觉得"——那没法对账。
  if (!about) problems.push('要给 about：绑到今天的任务 id、规则项或话题 id 上。')
  else if (kind === 'hard') {
    const pool = known instanceof Set ? known : new Set(Array.isArray(known) ? known : [])
    if (!pool.has(about)) {
      problems.push('hard 立场必须绑一个墙认得的东西（今天的任务 / 规则项），「' + about + '」墙不认识。')
    }
  }

  const until = Number(raw?.until)
  if (!Number.isFinite(until)) problems.push('要给 until（到期时刻，毫秒时间戳）。')
  else if (until <= at) problems.push('until 已经过去了，立场要指向还没发生的事。')
  else if (until - at > UNTIL_MAX_MS) problems.push('until 最多 ' + Math.round(UNTIL_MAX_MS / 86_400_000) + ' 天内，别立到看不见的地方。')

  const evidence = (Array.isArray(raw?.evidence) ? raw.evidence : [])
    .map((n) => Number(n))
    .filter((n) => Number.isFinite(n))
  // 硬约束：没有证据的立场不许立。说的是"凭什么这么要求他"。
  if (!evidence.length) problems.push('必须给 evidence：至少一条账本行号，说明你凭什么这么要求。')
  else if (evidence.length > 5) problems.push('evidence 最多 5 条。')

  // 额度：她得挑重要的立，不能顺手立十条。
  const sameDay = heldOn(cur.list, String(day || ''), kind)
  if (kind === 'hard' && sameDay.length >= hardQuota(hitRate)) {
    problems.push('今天已经立了 ' + sameDay.length + ' 条硬立场，额度 ' + hardQuota(hitRate) + ' 条——挑更重要的那条。')
  }
  if (kind === 'soft' && sameDay.length >= SOFT_MAX) {
    problems.push('今天已经立了 ' + sameDay.length + ' 条软立场，额度 ' + SOFT_MAX + ' 条。')
  }

  if (problems.length) {
    const pool = known instanceof Set ? known : new Set(Array.isArray(known) ? known : [])
    const code = !text
      ? 'no-text'
      : text.length > TEXT_MAX
        ? 'bad-text'
        : !KINDS.includes(kind)
          ? 'bad-kind'
          : !about || (kind === 'hard' && !pool.has(about))
            ? 'not-wall-object'
            : !Number.isFinite(until)
              ? 'no-until'
              : !evidence.length
                ? 'no-evidence'
                : 'over-quota'
    return { ok: false, code, problems }
  }
  return {
    ok: true,
    value: {
      id: '',
      by: String(raw?.by ?? '').trim() === 'companion' ? 'companion' : 'strategist',
      at,
      day: String(day || ''),
      text,
      kind,
      about,
      until,
      evidence,
      status: 'held',
      settleSource: '',
      settledAt: 0,
      readings: null,
    },
  }
}

/** 编号：S-日期-序号，方便人肉对账。 */
export function appendStance(ledger, record) {
  const out = cleanStances(ledger)
  const day = String(record?.day || '')
  const seq = out.day === day ? out.seq + 1 : 1
  out.day = day
  out.seq = seq
  const rec = { ...record, id: 'S-' + (day.replaceAll('-', '') || 'X') + '-' + String(seq).padStart(2, '0') }
  out.list = [...out.list, rec].slice(-MAX_LIST)
  return { ledger: out, record: rec }
}

/** 账本行号是不是一条"新出现的、算数的"证据。 */
function usableEvidence(seq, entries, afterSeq) {
  const row = (Array.isArray(entries) ? entries : []).find((e) => Number(e?.seq) === Number(seq))
  if (!row) return 'no-such-row'
  if (Number(seq) <= Number(afterSeq || 0)) return 'not-after'
  // 他说"不想做"不是新事实。判词已经在账本里，直接看；没有判词就看原话分类。
  const verdict = String(row.verdict || row.replyClass || '')
  if (NOT_EVIDENCE.has(verdict)) return 'avoid-or-refuse'
  const said = row.answer ?? row.text
  if (said && NOT_EVIDENCE.has(classifyReply(said))) return 'avoid-or-refuse'
  return ''
}

/**
 * 证据定位：行号直接用；给一句原话就到账本里找最近一条包含它的话，
 * 换成它的行号。模型看得到自己说过什么，看不到账本行号——不给这条路，revise 永远改不了。
 * @returns {number[]} 认出来的行号（认不出的原样保留成 0，好让 reviseStance 报「不存在」）
 */
export function resolveEvidence(evidence, entries) {
  const rows = Array.isArray(entries) ? entries : []
  return (Array.isArray(evidence) ? evidence : []).map((raw) => {
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) return n
    const want = String(raw ?? '').replace(/\s+/g, ' ').trim().slice(0, 60)
    if (want.length < 4) return 0
    for (let i = rows.length - 1; i >= 0; i -= 1) {
      const row = rows[i]
      const said = String(row?.answer ?? row?.text ?? '')
      if (said && said.includes(want)) return Number(row?.seq) || 0
    }
    return 0
  })
}

/**
 * 改口。hard 一律拒绝（她没有这个工具）；soft 要 reason + 一条立场之后新出现的证据。
 * @returns {{ok:true, ledger:object, record:object} | {ok:false, code:string, problems:string[]}}
 */
export function reviseStance(ledger, { id, text, reason, evidence, entries, now } = {}) {
  const out = cleanStances(ledger)
  const at = Number(now) || Date.now()
  const cur = out.list.find((r) => r.id === String(id || ''))
  if (!cur) return { ok: false, code: 'no-stance', problems: ['没有这条立场：' + String(id || '')] }
  if (cur.status !== 'held') return { ok: false, code: 'not-held', problems: ['这条立场已经结算成 ' + cur.status + '，不能改。'] }
  // 规则 2：硬立场在到期前没有修改它的工具。这是整个模块存在的理由，不能有例外。
  if (cur.kind === 'hard') {
    return { ok: false, code: 'stance-locked', problems: ['硬立场到期前不能改。能让它变的只有三件事：到期、墙按证据判定完成、他在晨间窗口重新定计划。'] }
  }

  const problems = []
  const why = String(reason || '').replace(/\s+/g, ' ').trim()
  if (why.length < 4) problems.push('改口必须给 reason：为什么改。')
  // 证据可以给行号，也可以给一句原话——由 resolveEvidence 换成行号。
  const seqs = resolveEvidence(evidence, entries).filter((n) => n > 0)
  if (!seqs.length) problems.push('改口必须引用一条新证据（账本行号或原话），「他说不想做」不算。')
  const afterSeq = Math.max(0, ...(cur.evidence || []))
  for (const s of seqs) {
    const bad = usableEvidence(s, entries, afterSeq)
    if (bad === 'no-such-row') problems.push('第 ' + s + ' 行账本不存在，不能当证据。')
    else if (bad === 'not-after') problems.push('第 ' + s + ' 行在立这条立场之前就有了，不算新证据。')
    else if (bad === 'avoid-or-refuse') problems.push('第 ' + s + ' 行是他不想做/把话带走，不是新事实，不能当证据。')
  }
  if (problems.length) return { ok: false, code: 'stance-cave-attempt', problems }

  const next = {
    ...cur,
    status: 'revised',
    revisedAt: at,
    revisedReason: why,
    revisedEvidence: seqs,
  }
  const body = String(text ?? '').replace(/\s+/g, ' ').trim()
  const fresh = appendStance(
    { ...out, list: out.list.map((r) => (r.id === cur.id ? next : r)) },
    {
      ...cur,
      id: '',
      at,
      text: body ? body.slice(0, TEXT_MAX) : cur.text,
      evidence: [...(cur.evidence || []), ...seqs],
      // 后立的那条承接前一条的到期时间：改口不是把截止日往后推。
      until: cur.until,
      status: 'held',
      settleSource: '',
      settledAt: 0,
      readings: null,
      supersedes: cur.id,
    },
  )
  return { ok: true, ledger: fresh.ledger, record: fresh.record }
}

/**
 * 到期结算。kept/broken 只由墙的遥测算；量不到的记 expired，不冤枉她也不白送。
 * @param {(about:string) => (boolean|null)} isDone 墙按证据判对象做完没有
 */
export function settleStances(ledger, { now, isDone } = {}) {
  const out = cleanStances(ledger)
  const at = Number(now) || Date.now()
  const settled = []
  out.list = out.list.map((r) => {
    if (r.status !== 'held' || Number(r.until) > at) return r
    let done = null
    try {
      const v = typeof isDone === 'function' ? isDone(r.about) : null
      done = v === true ? true : v === false ? false : null
    } catch {
      done = null
    }
    const status = done === true ? 'kept' : done === false && r.kind === 'hard' ? 'broken' : 'expired'
    const next = { ...r, status, settleSource: SETTLE_SOURCE, settledAt: at, readings: { done } }
    settled.push(next)
    return next
  })
  return { ledger: out, settled }
}

/** 命中率：结算过的立场里守住的比例。没有结算过就是 null（不是 0）。 */
export function hitRate(list) {
  const done = (Array.isArray(list) ? list : []).filter((r) => r.status === 'kept' || r.status === 'broken')
  if (!done.length) return null
  return done.filter((r) => r.status === 'kept').length / done.length
}

const KIND_WORD = { hard: '硬', soft: '软' }

/**
 * 当前生效的立场，以结构化短行进约束层（走 P0-3 预算）。
 * @param {(ts:number)=>string} hm 时刻格式化，必须由调用方按用户时区给（P0-2 的 B1 守卫扫描 lib/，不许有 UTC 时刻切片）
 */
export function stanceLines(ledger, now, hm = () => '') {
  const at = Number(now)
  const held = (cleanStances(ledger).list || [])
    .filter((r) => r.status === 'held' && Number(r.until) > at)
    // 硬立场排前面：真到装不下的时候，先保住不能改的那几条。
    .sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'hard' ? -1 : 1))
  if (!held.length) return []
  const lines = ['他跟你磨的时候，这些已经定了（到期前不可更改，改口要有新证据）：']
  for (const r of held) {
    lines.push('[' + (KIND_WORD[r.kind] || '软') + '立场] ' + r.text + ' · ' + hm(r.until) + ' 到期' +
      (r.kind === 'hard' ? ' · 到期前不可更改' : ' · 要改得给新证据'))
  }
  return lines
}

/**
 * 立场块（进约束层的那一块）。约束层永不裁、超了抛错，所以这里自己封顶：
 * 硬立场排前面先占容量（装不下说明底稿该压了，不许在这里哑掉），软立场装到 cap 为止，
 * 被省掉的报条数。返回长度保证 ≤ cap，不会把整轮注入撑破。
 */
export function stanceBlock(ledger, now, hm, { cap = 200 } = {}) {
  const lines = stanceLines(ledger, now, hm)
  if (!lines.length) return ''
  const [header, ...body] = lines
  // 留 40 字给「另有 N 条…未显示」那一行，正文只在 limit 里装。
  const limit = Math.max(0, Number(cap) - 40)
  const out = []
  let n = header.length
  let cut = 0
  for (const line of body) {
    if (n + line.length + 1 > limit) {
      cut += 1
      continue
    }
    out.push(line)
    n += line.length + 1
  }
  if (cut) out.push('（另有 ' + cut + ' 条立场生效中，超注入预算未显示）')
  return [header, ...out].join('\n')
}

/**
 * 改口检测：用户的判词是拒绝/回避，她下一球就撤回或降低要求——这就是"顺着他说"。
 * 纯函数，永远不问模型自己觉不觉得。
 */
const SOFTEN = /(算了|也行|也可以|明天再|不急|不勉强|随你|没关系|那就不|改天|放宽|少一点|不用了|要不先)/
const DEMAND = /(必须|一定|得|要|不许|不能|之前|前完成|今天就)/

export function isYield({ verdict, before, after } = {}) {
  if (!NOT_EVIDENCE.has(String(verdict || ''))) return false
  const a = String(after || '').replace(/\s+/g, ' ').trim()
  const b = String(before || '').replace(/\s+/g, ' ').trim()
  if (!a) return false
  // 她这一句自己就在松口，或者原来那句的要求在这句里没了。
  if (SOFTEN.test(a)) return true
  return Boolean(b && DEMAND.test(b) && !DEMAND.test(a))
}

/**
 * 同意率：最近 windowTurns 轮里她"顺着他说"的比例。
 * rows 是已经由代码标好 yielded 的回球行（state=replied 或 stance-yield）。
 * @returns {{turns:number, yielded:number, noEvidence:number, rate:number}}
 */
export function agreementRate(rows, windowTurns = 20) {
  const n = Math.max(1, Math.round(Number(windowTurns) || 20))
  const list = (Array.isArray(rows) ? rows : []).slice(-n)
  let yielded = 0
  let noEvidence = 0
  for (const r of list) {
    if (!r || r.yielded !== true) continue
    yielded += 1
    if (r.hasEvidence !== true) noEvidence += 1
  }
  return { turns: list.length, yielded, noEvidence, rate: list.length ? yielded / list.length : 0 }
}

/** 超过阈值时往约束层加的一行。没超就不加。 */
export function yieldLine(rate, threshold = 0.7) {
  const r = Number(rate?.rate) || 0
  if (r <= Number(threshold)) return ''
  return '[提醒] 最近 ' + rate.turns + ' 轮你退让了 ' + rate.yielded + ' 次，其中 ' + rate.noEvidence + ' 次没有新证据。'
}

/** 写进 判断.md 的那几行：她的立场事后有多少立对了。 */
export function stanceDigest(list, { max = 5 } = {}) {
  const done = (Array.isArray(list) ? list : []).filter((r) => r.status === 'kept' || r.status === 'broken')
  if (!done.length) return []
  const kept = done.filter((r) => r.status === 'kept')
  const lines = ['## 立场命中（' + kept.length + '/' + done.length + '）', '']
  for (const r of done.slice(-max)) {
    lines.push('- ' + (r.status === 'kept' ? '立对了：' : '没守住：') + r.text + '（' + r.id + '）')
  }
  return lines
}

/**
 * 重启后从账本重建：账本是真源，stances.json 只是缓存。
 * 认 stance-new / stance-revised / stance-settled 三种行。
 */
export function rebuildFromLedger(entries) {
  const out = emptyStances()
  const byId = new Map()
  for (const e of Array.isArray(entries) ? entries : []) {
    const rec = e?.stance
    if (!rec || !rec.id) continue
    byId.set(rec.id, { ...rec })
  }
  const list = [...byId.values()].sort((a, b) => Number(a.at) - Number(b.at))
  out.list = list.slice(-MAX_LIST)
  const last = out.list[out.list.length - 1]
  if (last) {
    out.day = String(last.day || '')
    out.seq = out.list.filter((r) => dayOf(r) === out.day).length
  }
  return out
}
