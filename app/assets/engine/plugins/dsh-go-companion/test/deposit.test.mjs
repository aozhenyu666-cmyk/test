// 第五轮·每日四部门提交（纯函数）：字数、复读、按天复归、齐没齐。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEPARTMENTS, DEPT_DUTY, MIN_REPORT, MIN_THOUGHT, SLOT_ASKS, SLOT_MAX_CHARS, SLOT_MAX_PER_DEPT,
  appendSlot, auditEntry, behaviorReport, charCount, completeOf, depositLines, depositOf, deptGap,
  gapText, missingText, normalizeEntry, reconcileEntry, slotAsk, slotsView, statusOf, withEntry,
} from '../lib/deposit.js'

const DAY = '2026-11-09'
const long = (n, ch = '字') => ch.repeat(n)

test('四个部门，顺序固定：信息收集 → 谋划思考 → 行为管理 → 统筹规划', () => {
  assert.deepEqual(DEPARTMENTS.map((d) => d.key), ['intel', 'thinking', 'behavior', 'coord'])
  assert.deepEqual(DEPARTMENTS.map((d) => d.label), ['信息收集', '谋划思考', '行为管理', '统筹规划'])
  for (const d of DEPARTMENTS) assert.ok(d.hint.length > 0, d.key + ' 缺提示')
})

test('字数按去掉空白和标点后的字数算', () => {
  assert.equal(charCount('你好，世界！'), 4)
  assert.equal(charCount('  你好   世界  '), 4)
  assert.equal(charCount(''), 0)
  assert.equal(charCount(null), 0)
})

test('汇报不够 200 字、想法不够 80 字，都退回并说清还差多少', () => {
  const a = auditEntry({ report: long(10), thought: long(5) })
  assert.equal(a.ok, false)
  assert.equal(a.problems.length, 2)
  assert.ok(a.problems[0].includes(String(MIN_REPORT - 10)), a.problems[0])
  assert.ok(a.problems[1].includes(String(MIN_THOUGHT - 5)), a.problems[1])
})

test('刚好到线就收（边界不多算一个）', () => {
  assert.equal(auditEntry({ report: long(MIN_REPORT), thought: long(MIN_THOUGHT) }).ok, true)
  assert.equal(auditEntry({ report: long(MIN_REPORT - 1), thought: long(MIN_THOUGHT) }).ok, false)
})

test('想法把汇报原样贴一遍，不算想法', () => {
  const a = auditEntry({ report: long(MIN_REPORT, '铺'), thought: long(MIN_REPORT, '铺') })
  assert.equal(a.ok, false)
  assert.ok(a.problems.some((p) => p.includes('同一段话')), a.problems.join('|'))
})

test('normalizeEntry 收齐才给 entry，带提交时间；脏输入不抛', () => {
  const ok = normalizeEntry({ report: long(MIN_REPORT), thought: long(MIN_THOUGHT) }, 1234)
  assert.equal(ok.ok, true)
  assert.equal(ok.entry.at, 1234)
  assert.equal(normalizeEntry({ report: '', thought: '' }, 1).ok, false)
  assert.equal(normalizeEntry(null, 1).ok, false)
  // 超长截断，不无限膨胀
  const huge = normalizeEntry({ report: long(9000), thought: long(4000) }, 1)
  assert.equal(huge.ok, true)
  assert.ok(huge.entry.report.length <= 6000)
  assert.ok(huge.entry.thought.length <= 3000)
})

test('depositOf 只认当天：昨天的交过也不算今天', () => {
  const state = withEntry(depositOf({}, DAY), 'intel', { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 1 })
  assert.equal(statusOf(depositOf(state, DAY)).done, 1)
  assert.equal(statusOf(depositOf(state, '2026-11-10')).done, 0)
  // 脏数据不抛
  assert.equal(statusOf(depositOf({ day: DAY, entries: 5 }, DAY)).done, 0)
})

test('交齐四个才 completeOf；缺的写进 missingText', () => {
  let state = depositOf({}, DAY)
  assert.equal(completeOf(state), false)
  assert.equal(missingText(state), '今天还缺 4 段：信息收集、谋划思考、行为管理、统筹规划。')
  for (const d of DEPARTMENTS) {
    state = withEntry(state, d.key, { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 1 })
  }
  assert.equal(completeOf(state), true)
  assert.equal(missingText(state), '')
  const s = statusOf(state)
  assert.equal(s.done, 4)
  assert.equal(s.total, 4)
  assert.deepEqual(s.missing, [])
})

test('statusOf 把每段的字数和下限都给界面，不用前端自己算', () => {
  const state = withEntry(depositOf({}, DAY), 'intel', { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 7 })
  const item = statusOf(state).items.find((i) => i.key === 'intel')
  assert.equal(item.done, true)
  assert.equal(item.reportChars, MIN_REPORT)
  assert.equal(item.thoughtChars, MIN_THOUGHT)
  assert.equal(item.minReport, MIN_REPORT)
  assert.equal(item.minThought, MIN_THOUGHT)
  assert.equal(item.at, 7)
})

test('人设里那几句：缺的要点名催，交齐了就不再催', () => {
  let state = depositOf({}, DAY)
  const busy = depositLines(state, false).join('\n')
  assert.ok(busy.includes('信息收集'), busy)
  assert.ok(busy.includes('统筹规划'), busy)
  assert.ok(busy.includes('200'), busy)
  // 晚上对账时间催得更硬：出现「对账」这句强的，普通时段说明后果
  assert.ok(depositLines(state, true).join('\n').includes('先把这四段要到手'))
  assert.ok(busy.includes('放风额度'), busy)
  for (const d of DEPARTMENTS) {
    state = withEntry(state, d.key, { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 1 })
  }
  const done = depositLines(state, true).join('\n')
  assert.ok(done.includes('交齐了'), done)
  assert.ok(!done.includes('缺'), done)
})

// ------------------------------------------------------------------ A4：四部职责表

test('A4 四部职责表：每部都写清记什么、证据从哪来；只有行为管理是墙自动填', () => {
  assert.deepEqual(Object.keys(DEPT_DUTY).sort(), DEPARTMENTS.map((d) => d.key).sort())
  for (const d of DEPARTMENTS) {
    const duty = DEPT_DUTY[d.key]
    assert.ok(duty.records.length > 0, d.key + ' 缺「记什么」')
    assert.ok(duty.evidence.length > 0, d.key + ' 缺「证据从哪来」')
    assert.equal(typeof duty.auto, 'boolean', d.key + ' 的 auto 不是布尔')
  }
  // 自动汇报只准有行为管理这一部——多一个就等于替他编汇报。
  assert.equal(DEPARTMENTS.filter((d) => DEPT_DUTY[d.key].auto).length, 1)
  assert.equal(DEPT_DUTY.behavior.auto, true)
})

test('A4 statusOf 把每部的职责、证据来源、是否自动汇报、今天记了几条都给界面', () => {
  const s = statusOf(depositOf({}, DAY))
  for (const item of s.items) {
    assert.ok(item.duty.length > 0, item.key + ' 缺 duty')
    assert.ok(item.evidence.length > 0, item.key + ' 缺 evidence')
    assert.equal(typeof item.autoReport, 'boolean', item.key + ' 的 autoReport 不是布尔')
    assert.equal(item.slots, 0)
  }
  assert.equal(s.items.find((i) => i.key === 'behavior').autoReport, true)
  assert.equal(s.items.find((i) => i.key === 'intel').autoReport, false)
})

// ------------------------------------------------------------------ A4：日间记录槽

test('A4 日间记录槽：记一条就进对应部，带时间戳和关联任务', () => {
  const r = appendSlot(depositOf({}, DAY), { key: 'intel', text: '读了 Karpathy 的访谈', at: 42, taskId: 't2' })
  assert.equal(r.ok, true)
  assert.ok(r.text.includes('信息收集'), r.text)
  assert.ok(r.text.includes('第 1 条'), r.text)
  assert.equal(r.state.slots.intel.length, 1)
  assert.equal(r.state.slots.intel[0].at, 42)
  assert.equal(r.state.slots.intel[0].taskId, 't2')
  assert.equal(r.state.slots.intel[0].text, '读了 Karpathy 的访谈')
  // 没挂任务时值是 undefined（落盘时整键消失），不留一个空字符串。
  const r2 = appendSlot(depositOf({}, DAY), { key: 'coord', text: '把下午那块挪到晚上', at: 1 })
  assert.equal(r2.state.slots.coord[0].taskId, undefined)
})

test('A4 记录槽按部分开存：往信息收集写，谋划思考还是 0 条', () => {
  let state = depositOf({}, DAY)
  state = appendSlot(state, { key: 'intel', text: '看了一篇预训练的论文', at: 1 }).state
  state = appendSlot(state, { key: 'intel', text: '又看了一篇', at: 2 }).state
  const view = slotsView(state)
  assert.equal(view.find((v) => v.key === 'intel').count, 2)
  assert.equal(view.find((v) => v.key === 'thinking').count, 0)
  assert.equal(view.length, 4)
  assert.deepEqual(view.map((v) => v.key), ['intel', 'thinking', 'behavior', 'coord'])
})

test('A4 记录槽的三种退回：没这个部、空记录、超 200 字', () => {
  const state = depositOf({}, DAY)
  const noDept = appendSlot(state, { key: 'nope', text: '随便记一句' })
  assert.equal(noDept.ok, false)
  assert.ok(noDept.text.includes('没有这个部'), noDept.text)
  assert.ok(noDept.text.includes('信息收集'), noDept.text)
  const blank = appendSlot(state, { key: 'intel', text: '   \n  ' })
  assert.equal(blank.ok, false)
  assert.ok(blank.text.includes('得有字'), blank.text)
  const tooLong = appendSlot(state, { key: 'intel', text: 'a'.repeat(SLOT_MAX_CHARS + 1) })
  assert.equal(tooLong.ok, false)
  assert.ok(tooLong.text.includes(String(SLOT_MAX_CHARS)), tooLong.text)
  assert.ok(tooLong.text.includes('拆成两条'), tooLong.text)
  // 刚好 200 字收下，不多算一个。
  assert.equal(appendSlot(state, { key: 'intel', text: 'a'.repeat(SLOT_MAX_CHARS) }).ok, true)
})

test('A4 每部一天最多 20 条，第 21 条退回并让他晚上直接写那一段', () => {
  let state = depositOf({}, DAY)
  for (let i = 0; i < SLOT_MAX_PER_DEPT; i += 1) {
    const r = appendSlot(state, { key: 'intel', text: '第 ' + i + ' 条记录', at: i })
    assert.equal(r.ok, true, '第 ' + i + ' 条被拒了：' + r.text)
    state = r.state
  }
  assert.equal(slotsView(state).find((v) => v.key === 'intel').count, SLOT_MAX_PER_DEPT)
  const over = appendSlot(state, { key: 'intel', text: '再来一条', at: 99 })
  assert.equal(over.ok, false)
  assert.ok(over.text.includes('记满了'), over.text)
  assert.ok(over.text.includes(String(SLOT_MAX_PER_DEPT)), over.text)
  // 别的部不受影响。
  assert.equal(appendSlot(state, { key: 'thinking', text: '还能记', at: 99 }).ok, true)
})

test('A4 记录槽按天清空；交过的段落不会把槽一起清掉', () => {
  let state = appendSlot(depositOf({}, DAY), { key: 'coord', text: '挪了两次块', at: 5 }).state
  assert.equal(slotsView(depositOf(state, DAY)).find((v) => v.key === 'coord').count, 1)
  // 跨天：槽归零，不留昨天的。
  assert.equal(slotsView(depositOf(state, '2026-11-10')).find((v) => v.key === 'coord').count, 0)
  // 交一段之后槽还在（槽是证据，提交不该吃掉它）。
  state = withEntry(state, 'coord', { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 6 })
  assert.equal(slotsView(state).find((v) => v.key === 'coord').count, 1)
})

// ------------------------------------------------------------------ A4：行为管理部自动汇报

test('A4 行为管理的汇报栏由墙生成：只摆遥测里的数字，按用得多的排前面', () => {
  const r = behaviorReport({
    usage: [{ label: '抖音', minutes: 30 }, { label: '微信', minutes: 10 }, { label: '浏览器', minutes: 0 }],
    verdicts: [{ satisfied: true }, { satisfied: false }],
  })
  assert.ok(r.startsWith('〔墙生成，不能改〕'), r)
  assert.ok(r.includes('抖音 30 分钟、微信 10 分钟'), r)
  // 0 分钟的不列——摆上去等于凑数。
  assert.ok(!r.includes('浏览器'), r)
  assert.ok(r.includes('过 1 次、没过 1 次'), r)
})

test('A4 行为管理没有遥测就直说没有，不编', () => {
  const empty = behaviorReport()
  assert.ok(empty.includes('今天没有 App 使用遥测'), empty)
  assert.ok(empty.includes('今天没有裁决记录'), empty)
  // 脏输入不抛。
  assert.ok(behaviorReport({ usage: 5, verdicts: 'x' }).includes('〔墙生成，不能改〕'))
})

test('A4 行为管理最多列 6 个 App，不把整份遥测铺上去', () => {
  const many = behaviorReport({ usage: Array.from({ length: 10 }, (_, i) => ({ label: 'A' + i, minutes: i + 1 })) })
  assert.equal((many.match(/分钟/g) || []).length, 6, many)
  // 排前 6 的是 10..5 分钟那几个。
  assert.ok(many.includes('A9 10 分钟'), many)
  assert.ok(!many.includes('A0 '), many)
})

test('A4 自动汇报免 200 字下限（那不是他写的），但想法栏的下限照旧', () => {
  const auto = auditEntry({ report: '', thought: long(MIN_THOUGHT) }, { auto: true })
  assert.equal(auto.ok, true)
  // 想法栏是他自己写的，还是要够 80 字。
  const thin = auditEntry({ report: '', thought: long(10) }, { auto: true })
  assert.equal(thin.ok, false)
  assert.ok(thin.problems.some((p) => p.includes('想法')), thin.problems.join('|'))
  // 同样的空汇报，非 auto 照样退回。
  assert.equal(auditEntry({ report: '', thought: long(MIN_THOUGHT) }).ok, false)
})

test('A4 提交行为管理时，用户交的 report 整个不看，只用墙那一份', () => {
  const wall = '〔墙生成，不能改〕\n手机：抖音 30 分钟。'
  const norm = normalizeEntry({ report: '我自己瞎写的汇报内容，一个字都不该留下', thought: long(MIN_THOUGHT) }, 99, { auto: wall })
  assert.equal(norm.ok, true)
  assert.ok(norm.entry.report.includes('墙生成'), norm.entry.report)
  assert.ok(!norm.entry.report.includes('瞎写'), norm.entry.report)
  assert.equal(norm.entry.auto, true)
  assert.equal(norm.entry.at, 99)
  // 非 auto 的段落不带这个标记。
  assert.equal(normalizeEntry({ report: long(MIN_REPORT), thought: long(MIN_THOUGHT) }, 1).entry.auto, undefined)
})

// ------------------------------------------------------------------ A4：晚间对照

test('A4 晚间对照：汇报里的事能在今天的记录槽里找到，就算对上了', () => {
  const state = appendSlot(depositOf({}, DAY), {
    key: 'intel', text: '今天读了 Karpathy 的访谈，他说预训练边际收益在下降', at: 1,
  }).state
  const rec = reconcileEntry(state, 'intel', '今天读了 Karpathy 的访谈，预训练边际收益确实在下降，这印证了我原来的判断。')
  assert.equal(rec.ok, true)
  assert.equal(rec.unsupported, false)
  assert.ok(rec.why.includes('第 1 条记录'), rec.why)
})

test('A4 晚间对照：记录里找不到对应就标无据并退回，并说清是几条里找不到', () => {
  let state = depositOf({}, DAY)
  state = appendSlot(state, { key: 'intel', text: '去菜市场买了排骨', at: 1 }).state
  state = appendSlot(state, { key: 'intel', text: '下午睡了两个小时', at: 2 }).state
  const rec = reconcileEntry(state, 'intel', long(MIN_REPORT, '凑'))
  assert.equal(rec.ok, false)
  assert.equal(rec.unsupported, true)
  assert.ok(rec.why.includes('2 条记录'), rec.why)
  assert.ok(rec.why.includes('找不到对应'), rec.why)
})

test('A4 晚间对照：记录槽和证据来源都空着，这一段直接算无据', () => {
  const rec = reconcileEntry(depositOf({}, DAY), 'thinking', long(MIN_REPORT, '编'))
  assert.equal(rec.unsupported, true)
  assert.ok(rec.why.includes('既没有日间记录'), rec.why)
  assert.ok(rec.why.includes('没有可对照的证据来源'), rec.why)
  assert.ok(rec.why.includes('谋划思考'), rec.why)
  // 没有这个部时也不抛。
  assert.equal(reconcileEntry(depositOf({}, DAY), 'nope', 'x').unsupported, true)
})

test('A4 晚间对照：记录槽空但有证据来源（今天的笔记、当天的时间表）就不算无据', () => {
  // 规格写的是「记录槽或证据来源」——他不爱记槽，但笔记和计划都在，那不是编。
  const state = depositOf({}, DAY)
  const rec = reconcileEntry(state, 'intel', '今天读了 Karpathy 的访谈，预训练边际收益在下降', {
    evidence: ['Karpathy 访谈：预训练边际收益在下降，转向推理侧算力'],
  })
  assert.equal(rec.ok, true, rec.why)
  assert.equal(rec.unsupported, false)
  assert.ok(rec.why.includes('证据来源'), rec.why)
  // 证据来源里也没有对得上的，照样无据。
  const no = reconcileEntry(state, 'intel', '今天读了 Karpathy 的访谈，预训练边际收益在下降', { evidence: ['去菜市场买了排骨'] })
  assert.equal(no.unsupported, true)
  assert.ok(no.why.includes('1 条记录和证据来源'), no.why)
})

test('A4 晚间对照：行为管理部不对照记录槽——它的证据是遥测', () => {
  const rec = reconcileEntry(depositOf({}, DAY), 'behavior', '随便一段')
  // 不拦（unsupported=false），但也谈不上「对上」。
  assert.equal(rec.unsupported, false)
  assert.equal(rec.ok, false)
  assert.ok(rec.why.includes('由墙生成'), rec.why)
  assert.ok(rec.why.includes('遥测'), rec.why)
})

// ------------------------------------------------------------------ A4：晨间有任务、晚上没记录

test('A4 晨间排进某部的任务，晚上那个部一条记录都没有，要点名说出来', () => {
  const state = depositOf({}, DAY)
  const tasks = [{ dept: '信息收集部', goal: '读三篇论文' }, { dept: '谋划思考', goal: '想清楚要不要换方向' }]
  const gap = deptGap(statusOf(state), tasks)
  // 「信息收集部」和「信息收集」两种写法都认。
  assert.deepEqual(gap.map((g) => g.key).sort(), ['intel', 'thinking'])
  assert.ok(gapText(gap).includes('信息收集'), gapText(gap))
  assert.ok(gapText(gap).includes('谋划思考'), gapText(gap))
  assert.ok(gapText(gap).includes('现在就让他在面板上记一条'), gapText(gap))
})

test('A4 记过的部不算缺口；没挂部的任务也不算', () => {
  let state = depositOf({}, DAY)
  const tasks = [{ dept: '信息收集部', goal: 'a' }, { goal: '没挂部的事' }, { dept: '不存在的部', goal: 'b' }]
  state = appendSlot(state, { key: 'intel', text: '读了论文', at: 1 }).state
  assert.deepEqual(deptGap(statusOf(state), tasks), [])
  // 空输入都不抛。
  assert.deepEqual(deptGap(statusOf(state), null), [])
  assert.equal(gapText([]), '')
  assert.equal(gapText(null), '')
})

test('A4 缺口的提醒接进人设：有计划没记录时多说一句', () => {
  const state = depositOf({}, DAY)
  const withTasks = depositLines(state, false, { tasks: [{ dept: '信息收集部', goal: '读论文' }] }).join('\n')
  assert.ok(withTasks.includes('一条日间记录都没有'), withTasks)
  // 没传 tasks 时不多这一句（老调用点不受影响）。
  assert.ok(!depositLines(state, false).join('\n').includes('一条日间记录都没有'))
  // 交齐了就不再提缺口。
  let done = state
  for (const d of DEPARTMENTS) done = withEntry(done, d.key, { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 1 })
  assert.ok(!depositLines(done, true, { tasks: [{ dept: '信息收集部', goal: '读论文' }] }).join('\n').includes('一条日间记录都没有'))
})

// ------------------------------------------------------------------ A4：块结束时那一句

test('A4 块结束问「这一块记到哪个部」：同一块说法不变，不同块换着说', () => {
  const one = slotAsk(DAY, 420)
  assert.equal(typeof one, 'string')
  assert.ok(one.length > 0)
  assert.ok(SLOT_ASKS.includes(one), one)
  // 同一块重算还是同一句（不然同一块问两遍会变卦）。
  assert.equal(slotAsk(DAY, 420), one)
  // 不同块要换着来，不能一天到晚同一句。
  const seen = new Set()
  for (let i = 0; i < 480; i += 30) seen.add(slotAsk(DAY, i))
  assert.ok(seen.size >= 2, '16 个块只有一种说法：' + [...seen].join('|'))
  // 哪天也要影响选择，但永远落在表里。
  for (const day of ['2026-11-09', '2026-11-10', '2026-12-31']) {
    assert.ok(SLOT_ASKS.includes(slotAsk(day, 600)), day)
  }
  // 没传 index 也不抛。
  assert.ok(SLOT_ASKS.includes(slotAsk(DAY)))
})
