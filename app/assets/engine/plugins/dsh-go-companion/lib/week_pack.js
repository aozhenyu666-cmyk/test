// 2026-11-20 周冷数据包（验收第三批的雏形）。
//
// 超脱的验收者在体系外：一周一次，把「只有数字和事实」的一包交给他 + 体系外的模型（订阅制的 Claude / GPT
// 没有 API，就复制粘贴；连着 Gemini 桥的话 Gemini 用 dafeiyu_week 直接读）。
// 包里没有一句评价，评价是对方的事；末尾附固定提问，要求只回一个【周验收】块、最多 3 条调整，他确认才生效。
//
// 纯函数：账本行、验收点状态由调用方给。

const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)

export function prevYmd(ymd, k = 1) {
  return new Date(Date.parse(ymd + 'T00:00:00Z') - k * 86_400_000).toISOString().slice(0, 10)
}

const EMERGENCY = new Set(['emergency-pass-grant', 'emergency-exit', 'breakglass'])

/**
 * @param {object} p
 * @param {object[]} p.entries 账本行（{state, at, ...}）
 * @param {object} p.seals cleanSeals 之后的 {items}
 * @param {string} p.today YYYY-MM-DD
 * @param {(at:number)=>string} p.dayOf 毫秒 → 当地日期
 * @param {number} [p.days]
 */
export function weekPack({ entries = [], seals = { items: [] }, today, dayOf, days = 7, reviewLine = '' } = {}) {
  const span = Math.max(1, Math.min(14, Math.round(n(days) || 7)))
  const list = []
  for (let i = span - 1; i >= 0; i--) list.push(prevYmd(today, i))
  const inSpan = new Set(list)
  const byDay = Object.fromEntries(list.map((d) => [d, {
    day: d, total: 0, stretch: 0, floor: 0, below: 0, waived: 0, open: 0, live: 0, paid: 0,
    bouts: 0, receipts: 0, receiptsPass: 0, emergencies: 0, takeovers: 0, lowerRefused: 0,
  }]))
  const waivers = []
  for (const s of seals.items || []) {
    const d = byDay[s.day]
    if (!d) continue
    d.total += 1
    if (s.status in d) d[s.status] += 1
    if (s.sameDay) d.live += 1
    d.paid += n(s.paidMinutes)
    if (s.status === 'waived') waivers.push({ day: s.day, title: s.title, reason: s.waivedReason || '' })
  }
  for (const e of entries) {
    if (!e || !e.state || !n(e.at)) continue
    const day = dayOf(n(e.at))
    if (!inSpan.has(day)) continue
    const d = byDay[day]
    if (e.state === 'bout-open') d.bouts += 1
    else if (e.state === 'gemini-recap' && e.receipt === true) {
      d.receipts += 1
      if (e.verdict === 'pass') d.receiptsPass += 1
    } else if (EMERGENCY.has(e.state)) d.emergencies += 1
    else if (e.state === 'takeover-start') d.takeovers += 1
    else if (e.state === 'seal-lower-refused') d.lowerRefused += 1
  }
  const rows = list.map((d) => byDay[d])
  const sum = (k) => rows.reduce((a, r) => a + n(r[k]), 0)
  const judged = sum('stretch') + sum('floor') + sum('below')
  const totals = {
    days: span, from: list[0], to: list[list.length - 1],
    checkpoints: sum('total'), stretch: sum('stretch'), floor: sum('floor'), below: sum('below'), waived: sum('waived'), live: sum('live'),
    passRate: judged ? Math.round(((sum('stretch') + sum('floor')) / judged) * 100) : null,
    paid: sum('paid'), bouts: sum('bouts'), receipts: sum('receipts'), receiptsPass: sum('receiptsPass'),
    emergencies: sum('emergencies'), takeovers: sum('takeovers'), lowerRefused: sum('lowerRefused'),
    emptyDays: rows.filter((r) => r.total === 0).length,
  }
  return { totals, rows, waivers, reviewLine, text: packText(totals, rows, waivers, reviewLine) }
}

export const WEEK_PROMPT = [
  '你是体系外的验收人。下面只有数字和事实，没有他的解释。',
  '请只回一个【周验收】块，格式固定：',
  '【周验收】',
  '判断：一句话（这一周到底是做了还是在演）',
  '调整1：改什么 → 改成什么（只能是：拉伸系数 / 某类验收点的保底 / 某个时段 / 砍掉哪条长线），理由一句',
  '调整2：（可选）',
  '调整3：（可选）',
  '下周盯：一个数字',
  '最多 3 条调整。不要安慰，不要建议「多休息」之类的空话；数字支持不了的结论不要下。',
  '执行率 ≥85% 合格；连续两周 <70% 必须给一条「下调哪一项、调到多少」。调整写进下周的 本周计划.md（周一到周日每天交什么），他点头后下周生效。',
].join('\n')

function packText(t, rows, waivers, reviewLine = '') {
  const lines = ['# 大肥鱼 · 周冷数据（' + t.from + ' ~ ' + t.to + '）', '']
  lines.push('## 总数')
  lines.push('- 验收点 ' + t.checkpoints + ' 个：过拉伸 ' + t.stretch + '，过保底 ' + t.floor + '，没过保底 ' + t.below + '，豁免 ' + t.waived +
    '；判过的里面通过率 ' + (t.passRate === null ? '—' : t.passRate + '%') + '；开工后现加的 ' + t.live + ' 个')
  // 2026-11-22 计划模态：执行率 = 过了保底的 ÷ 判过的（豁免不算分母）。≥85% 合格；连续两周 <70% 说明本周计划定高了。
  lines.push('- 本周执行率 ' + (t.passRate === null ? '—（还没有判过的验收点）' : t.passRate + '%' +
    (t.passRate >= 85 ? '（合格）' : t.passRate < 70 ? '（不到 70%：计划可能定高了）' : '（偏低）')))
  lines.push('- 一个验收点都没有的天：' + t.emptyDays + ' 天')
  lines.push('- 挣到的娱乐分钟（验收点）：' + t.paid + '；开局 ' + t.bouts + ' 次')
  lines.push('- Gemini 回执 ' + t.receipts + ' 条（判「过」' + t.receiptsPass + '）')
  if (reviewLine) lines.push('- ' + reviewLine)
  lines.push('- 紧急解除 / 破窗 ' + t.emergencies + ' 次；被接管 ' + t.takeovers + ' 次；想把封印改小被拒 ' + t.lowerRefused + ' 次')
  lines.push('', '## 每天', '', '| 日期 | 点 | 拉伸 | 保底 | 没过 | 豁免 | 现加 | 分钟 | 局 | 回执(过) | 紧急 | 接管 |', '|---|---|---|---|---|---|---|---|---|---|---|---|')
  for (const r of rows) {
    lines.push('| ' + r.day.slice(5) + ' | ' + r.total + ' | ' + r.stretch + ' | ' + r.floor + ' | ' + r.below + ' | ' + r.waived + ' | ' + r.live + ' | ' +
      r.paid + ' | ' + r.bouts + ' | ' + r.receipts + '(' + r.receiptsPass + ') | ' + r.emergencies + ' | ' + r.takeovers + ' |')
  }
  if (waivers.length) {
    lines.push('', '## 豁免（他写的理由，原话）')
    for (const w of waivers.slice(-10)) lines.push('- ' + w.day.slice(5) + '「' + w.title + '」：' + w.reason)
  }
  lines.push('', '---', '', WEEK_PROMPT)
  return lines.join('\n')
}
