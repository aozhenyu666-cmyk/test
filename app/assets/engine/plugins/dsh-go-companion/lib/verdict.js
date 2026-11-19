// dsh-go-companion · 验收器内核：日判、截止日、周六周报
//
// 原则：判决由代码按账本算，不让模型说软话。
//   空转：今天没有一件通过验收的交付，也没有一场有完整回执的 Gemini 连麦。
//   达成：交付 ≥ 计划数（至少 1），娱乐 ≤ 120 分钟，没有错过截止日。
//   部分：其余。
// 判决和理由都是一句一句的事实，进晚报第一行、进 Obsidian、进第二天早上两姐妹的上下文。

export const VERDICT_IDLE = '空转'
export const VERDICT_PARTIAL = '部分'
export const VERDICT_DONE = '达成'
export const FUN_CAP_MIN = 120

const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)

export function dayVerdict({
  delivered = 0,
  planned = 0,
  receiptsOk = 0,
  receiptsMissing = 0,
  funMin = 0,
  focusMin = 0,
  disarms = 0,
  violations = 0,
  missedDeadlines = [],
} = {}) {
  const d = n(delivered)
  const ok = n(receiptsOk)
  const fun = Math.round(n(funMin))
  const missed = Array.isArray(missedDeadlines) ? missedDeadlines.filter(Boolean) : []
  const target = Math.max(1, n(planned))
  let tag = VERDICT_PARTIAL
  if (d === 0 && ok === 0) tag = VERDICT_IDLE
  else if (d >= target && fun <= FUN_CAP_MIN && missed.length === 0) tag = VERDICT_DONE

  const reasons = []
  reasons.push(d === 0 ? '没有一件通过验收的交付' : `通过验收的交付 ${d} 件（计划 ${n(planned) || '未定'}）`)
  reasons.push(`主线 ${Math.round(n(focusMin))} 分钟，娱乐 ${fun} 分钟${fun > FUN_CAP_MIN ? `（超过 ${FUN_CAP_MIN}）` : ''}`)
  if (ok || n(receiptsMissing)) reasons.push(`Gemini 连麦有回执 ${ok} 场${n(receiptsMissing) ? `，没回执 ${n(receiptsMissing)} 场（记无产出）` : ''}`)
  if (n(disarms)) reasons.push(`紧急解除 ${n(disarms)} 次`)
  if (n(violations)) reasons.push(`违约 / 硬闯 ${n(violations)} 次`)
  for (const m of missed.slice(0, 3)) reasons.push(`错过截止：${m}`)
  const line = `今天：${tag}——` + reasons.join('；') + '。'
  return { tag, line, reasons, delivered: d, receiptsOk: ok, funMin: fun }
}

/** 从账本里数当天的回执、紧急解除（含 disarm / 紧急通行 / 破窗，按 disarmId 去重）。 */
export function ledgerFacts(entries, inDay) {
  let receiptsOk = 0
  let receiptsMissing = 0
  let disarms = 0
  const seenDisarmIds = new Set()
  for (const e of Array.isArray(entries) ? entries : []) {
    if (!e || !inDay(e)) continue
    if (e.state === 'gemini-recap') {
      if (e.receipt === true) receiptsOk++
      else receiptsMissing++
    }
    if ((e.state === 'disarm' || e.state === 'emergency-pass-grant' || e.state === 'breakglass') && e.counted !== false) {
      if (e.disarmId) {
        if (seenDisarmIds.has(String(e.disarmId))) continue
        seenDisarmIds.add(String(e.disarmId))
      }
      disarms++
    }
  }
  return { receiptsOk, receiptsMissing, disarms }
}

// ---------------------------------------------------------------- 截止日

const YMD = /^\d{4}-\d{2}-\d{2}$/

function daysBetween(fromYmd, toYmd) {
  const a = Date.parse(fromYmd + 'T00:00:00Z')
  const b = Date.parse(toYmd + 'T00:00:00Z')
  return Math.round((b - a) / 86_400_000)
}

/** 三层主线里带 due 的、还没做完的，按剩余天数排（今天 = 0；负数 = 已过）。 */
export function deadlinesOf(horizons, todayYmd) {
  const out = []
  for (const bucket of ['long', 'medium']) {
    for (const it of Array.isArray(horizons?.[bucket]) ? horizons[bucket] : []) {
      if (!it || !YMD.test(String(it.due || '')) || it.status === 'done' || it.status === 'archived') continue
      out.push({ id: it.id, title: it.title, due: it.due, daysLeft: daysBetween(todayYmd, it.due), horizon: bucket })
    }
  }
  return out.sort((a, b) => a.daysLeft - b.daysLeft)
}

export function upcoming(horizons, todayYmd, withinDays = 14) {
  return deadlinesOf(horizons, todayYmd).filter((d) => d.daysLeft >= 0 && d.daysLeft <= withinDays)
}

/** 就在今天过期（due = 昨天）且没做完的：算「错过」。 */
export function missedOn(horizons, todayYmd) {
  return deadlinesOf(horizons, todayYmd).filter((d) => d.daysLeft === -1).map((d) => d.title)
}

/** 进两姐妹上下文的一行：7 / 3 / 1 / 0 天这几个点明说，其余只列最近两个。 */
export function deadlineLines(horizons, todayYmd) {
  const list = upcoming(horizons, todayYmd, 30)
  const lines = []
  for (const d of list.slice(0, 3)) {
    const urgent = [0, 1, 3, 7].includes(d.daysLeft)
    const when = d.daysLeft === 0 ? '就是今天' : `还剩 ${d.daysLeft} 天（${d.due}）`
    lines.push(`截止日：${d.title} ${when}${urgent ? '——这一轮必须提一句，并问他今天为它做了什么' : ''}`)
  }
  return lines
}

// ---------------------------------------------------------------- 周六周报

/** 周报跟着周六走：给一个日期，返回它所在这一周（周日起、周六止）的周六。 */
export function weekEndingSaturday(ymd) {
  const t = Date.parse(ymd + 'T00:00:00Z')
  const dow = new Date(t).getUTCDay() // 0 = 周日 … 6 = 周六
  const sat = t + (6 - dow) * 86_400_000
  return new Date(sat).toISOString().slice(0, 10)
}

export function isSaturday(ymd) {
  return new Date(Date.parse(ymd + 'T00:00:00Z')).getUTCDay() === 6
}

/** 把这一周（截至周六）的晚报汇总成一页周报。reports: {day: report} */
export function weeklyMarkdown(reports, saturdayYmd) {
  const sat = Date.parse(saturdayYmd + 'T00:00:00Z')
  const days = []
  for (let i = 6; i >= 0; i--) days.push(new Date(sat - i * 86_400_000).toISOString().slice(0, 10))
  const rows = days.map((d) => ({ day: d, r: reports?.[d] || null }))
  const count = (tag) => rows.filter((x) => x.r?.verdict?.tag === tag).length
  const idle = count(VERDICT_IDLE)
  const partial = count(VERDICT_PARTIAL)
  const done = count(VERDICT_DONE)
  const missing = rows.filter((x) => !x.r).length
  const sum = (k) => rows.reduce((s, x) => s + n(x.r?.buckets?.[k]), 0)
  const delivered = rows.reduce((s, x) => s + n(x.r?.verdict?.delivered), 0)
  const lines = [
    `# ${days[0]} ~ ${saturdayYmd} · 周报`,
    '',
    `> 达成 ${done} 天 · 部分 ${partial} 天 · 空转 ${idle} 天${missing ? ` · 没有记录 ${missing} 天` : ''}`,
    `> 交付 ${delivered} 件 · 主线 ${sum('mainlineMin')} 分钟 · Gemini ${sum('geminiMin')} 分钟 · 娱乐 ${sum('funMin')} 分钟`,
    '',
    '## 每天',
    '',
  ]
  for (const x of rows) {
    lines.push(`- ${x.day}：${x.r?.verdict?.line || '（没有晚报——这一天没有任何记录，按空转算）'}`)
  }
  lines.push('', '## 这一周做成了什么', '')
  const doneList = rows.flatMap((x) => (Array.isArray(x.r?.completedTasks) ? x.r.completedTasks.map((t) => `${x.day} ${t.title}`) : []))
  if (doneList.length) for (const t of doneList.slice(0, 30)) lines.push(`- ${t}`)
  else lines.push('- （这一周没有一件通过验收的交付）')
  lines.push('')
  return lines.join('\n')
}
