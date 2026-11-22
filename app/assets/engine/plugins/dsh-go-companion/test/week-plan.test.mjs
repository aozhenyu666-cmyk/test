// 2026-11-22 计划模态第一步：本周计划（大肥鱼/本周计划.md）决定每天自动套的验收点；上周的没换就顺延；周冷数据带执行率。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseWeekPlan, renderWeekPlan, weekPlanToday, parseTemplateItem, DEFAULT_TEMPLATES } from '../lib/verifier_mode.js'
import { weekPack } from '../lib/week_pack.js'

const PLAN = [
  '# 本周计划（2026-11-23 起）',
  '',
  '## 周一（09:00 自动套）',
  '- 09:00–10:30 资料分析 · 题库 30 题 · 保底 15',
  '- 14:00–15:00 申论一篇 · 笔记 学习/申论.md 800 字 · 保底 400',
  '## 周二',
  '- 写坏的一行',
  '- 10:00–11:00 言语 · 题库 20 题 · 保底 10',
  '## 周日',
  '',
].join('\n')

test('解析：周一到周日分节、自动套钟点、写坏的行跳过、没写的天没有', () => {
  const p = parseWeekPlan(PLAN)
  assert.equal(p.start, '2026-11-23')
  assert.equal(p.days[1].applyAt, 9 * 60)
  assert.equal(p.days[1].items.length, 2)
  assert.equal(p.days[1].items[0].kind, 'quiz')
  assert.equal(p.days[1].items[0].floor, 15)
  assert.equal(p.days[1].items[1].kind, 'file')
  assert.equal(p.days[2].items.length, 1, '写坏的那行跳过，其余照收')
  assert.equal(p.days[0].items.length, 0)
  assert.equal(p.days[3], undefined)
  assert.equal(parseTemplateItem('- 乱写'), null)
})

test('今天用哪一节：写了就用（没写钟点用模板的）；没写那天退回模板；起始日不是这周一 = 过期', () => {
  const p = parseWeekPlan(PLAN)
  const mon = weekPlanToday(p, { weekday: 1, monday: '2026-11-23', fallbackApplyAt: 570 })
  assert.equal(mon.stale, false)
  assert.equal(mon.section.applyAt, 9 * 60)
  const tue = weekPlanToday(p, { weekday: 2, monday: '2026-11-23', fallbackApplyAt: 570 })
  assert.equal(tue.section.applyAt, 570, '没写钟点用模板的')
  assert.equal(weekPlanToday(p, { weekday: 0, monday: '2026-11-23' }).section, null, '周日空着：退回模板')
  assert.equal(weekPlanToday(p, { weekday: 3, monday: '2026-11-23' }).section, null)
  assert.equal(weekPlanToday(p, { weekday: 1, monday: '2026-11-30' }).stale, true, '下周了还是这份')
})

test('生成 / 顺延：缺的天按模板补（工作日 / 休息日）；写过的天原样保留，日期换成这周一', () => {
  const fresh = parseWeekPlan(renderWeekPlan('2026-11-23'))
  assert.equal(fresh.start, '2026-11-23')
  assert.equal(fresh.days[1].items.length, DEFAULT_TEMPLATES.workday.items.length)
  assert.equal(fresh.days[6].items.length, DEFAULT_TEMPLATES.restday.items.length)
  const rolled = parseWeekPlan(renderWeekPlan('2026-11-30', parseWeekPlan(PLAN).days))
  assert.equal(rolled.start, '2026-11-30')
  assert.equal(rolled.days[1].items[0].title, '资料分析')
  assert.equal(rolled.days[1].applyAt, 9 * 60)
  assert.equal(rolled.days[0].items.length, DEFAULT_TEMPLATES.restday.items.length, '空着的周日补上模板')
})

test('周冷数据：带执行率和「连续两周 <70% 要给下调」的要求', () => {
  const today = '2026-11-23'
  const seals = { items: [
    { day: today, status: 'floor', paidMinutes: 10 }, { day: today, status: 'below', paidMinutes: 0 },
    { day: today, status: 'below', paidMinutes: 0 }, { day: today, status: 'waived', paidMinutes: 0 },
  ] }
  const p = weekPack({ entries: [], seals, today, dayOf: () => today, days: 7 })
  assert.match(p.text, /本周执行率 33%（不到 70%：计划可能定高了）/)
  assert.match(p.text, /连续两周 <70% 必须给一条/)
  const none = weekPack({ entries: [], seals: { items: [] }, today, dayOf: () => today })
  assert.match(none.text, /本周执行率 —/)
})
