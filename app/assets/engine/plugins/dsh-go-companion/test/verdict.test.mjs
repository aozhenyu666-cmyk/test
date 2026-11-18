// 2026-11-18 验收器：日判由代码按账本算；截止日；周六周报。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dayVerdict, deadlineLines, deadlinesOf, isSaturday, ledgerFacts, missedOn, weekEndingSaturday, weeklyMarkdown } from '../lib/verdict.js'
import { cleanHorizons, upsertHorizonItem } from '../lib/horizons.js'
import { generateEveningReport } from '../lib/analytics_center.js'

test('日判：什么都没交、只是在玩 = 空转，而且理由里写明', () => {
  const v = dayVerdict({ delivered: 0, funMin: 300, focusMin: 10 })
  assert.equal(v.tag, '空转')
  assert.match(v.line, /没有一件通过验收的交付/)
  assert.match(v.line, /娱乐 300 分钟（超过 120）/)
})

test('日判：交够计划、娱乐不超、没错过截止 = 达成；否则部分', () => {
  assert.equal(dayVerdict({ delivered: 2, planned: 2, funMin: 60 }).tag, '达成')
  assert.equal(dayVerdict({ delivered: 2, planned: 3, funMin: 60 }).tag, '部分')
  assert.equal(dayVerdict({ delivered: 2, planned: 2, funMin: 200 }).tag, '部分')
  assert.equal(dayVerdict({ delivered: 2, planned: 2, funMin: 60, missedDeadlines: ['国考报名'] }).tag, '部分')
  assert.equal(dayVerdict({ delivered: 0, receiptsOk: 1 }).tag, '部分', '有一场完整回执的连麦不算空转')
})

test('账本：完整回执、没回执、紧急解除分开数；adb 解除不计', () => {
  const day = (e) => e.at >= 100
  const f = ledgerFacts([
    { state: 'gemini-recap', at: 100, receipt: true },
    { state: 'gemini-recap', at: 101, receipt: false },
    { state: 'disarm', at: 102, counted: true },
    { state: 'disarm', at: 103, counted: false },
    { state: 'gemini-recap', at: 1, receipt: true },
  ], day)
  assert.deepEqual(f, { receiptsOk: 1, receiptsMissing: 1, disarms: 1 })
})

test('截止日：horizons 认 due；剩 3 天这一轮必须提；昨天到期没做完算错过', () => {
  let h = cleanHorizons({})
  h = upsertHorizonItem(h, { horizon: 'medium', title: '国考报名', due: '2026-11-21' }, 1).state
  h = upsertHorizonItem(h, { horizon: 'medium', title: '乱填的日期', due: '下周' }, 2).state
  h = upsertHorizonItem(h, { horizon: 'long', title: '简历终稿', due: '2026-11-17' }, 3).state
  const list = deadlinesOf(h, '2026-11-18')
  assert.equal(list.length, 2)
  assert.match(deadlineLines(h, '2026-11-18').join('\n'), /国考报名 还剩 3 天（2026-11-21）——这一轮必须提一句/)
  assert.deepEqual(missedOn(h, '2026-11-18'), ['简历终稿'])
})

test('周报：跟着周六走；没有晚报的那天按空转写明', () => {
  assert.equal(isSaturday('2026-11-21'), true)
  assert.equal(weekEndingSaturday('2026-11-18'), '2026-11-21')
  const md = weeklyMarkdown({
    '2026-11-16': { verdict: { tag: '达成', line: '今天：达成——交付 2 件。', delivered: 2 }, buckets: { mainlineMin: 120, funMin: 30 }, completedTasks: [{ title: '行测 1 套' }] },
    '2026-11-17': { verdict: { tag: '空转', line: '今天：空转——没有一件通过验收的交付。', delivered: 0 }, buckets: { funMin: 280 } },
  }, '2026-11-21')
  assert.match(md, /达成 1 天 · 部分 0 天 · 空转 1 天 · 没有记录 5 天/)
  assert.match(md, /2026-11-15：（没有晚报——这一天没有任何记录，按空转算）/)
  assert.match(md, /2026-11-16 行测 1 套/)
})

test('晚报：第一行是硬判决', () => {
  const r = generateEveningReport({ day: '2026-11-18', ledger: [], now: Date.parse('2026-11-18T14:00:00Z'), tz: 'Asia/Shanghai' })
  assert.equal(r.verdict.tag, '空转')
  assert.match(r.markdown, /verdict: "空转"/)
  assert.match(r.markdown, /\*\*今天：空转——/)
})
