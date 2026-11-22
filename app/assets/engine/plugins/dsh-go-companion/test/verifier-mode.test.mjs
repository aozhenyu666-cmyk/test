// 超级验收者的底层设定：时段里默认验收、花余额要理由、模板（Obsidian 可改）、到点自动套、连续两天失败进恢复日。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_TEMPLATES, checkSpend, currentWindow, dayOutcomes, defaultVerify, parseTemplates, recoveryStep, renderTemplates,
  shouldApplyTemplate, templateSeals, verifyPromptLines,
} from '../lib/verifier_mode.js'

const WIN = [[540, 720], [840, 1080]]
const NOW = 1_800_000_000_000

test('默认验收：时段里开、时段外不开；吃饭 / 放风 / 紧急 / 深夜 / 花余额 / 关了都让路；开工时段外也开', () => {
  assert.deepEqual(currentWindow(WIN, 600), { from: 540, to: 720 })
  assert.equal(currentWindow(WIN, 780), null)
  const base = { now: NOW, windows: WIN, minuteOfDay: 600 }
  assert.deepEqual(defaultVerify(base), { active: true, untilMinute: 720, reason: 'window' })
  assert.equal(defaultVerify({ ...base, minuteOfDay: 780 }).why, 'outside')
  for (const k of ['night', 'emergency', 'onBreak', 'meal', 'relaxOpen']) assert.equal(defaultVerify({ ...base, [k]: true }).active, false, k)
  assert.equal(defaultVerify({ ...base, enabled: false }).why, 'off')
  assert.equal(defaultVerify({ ...base, spendUntil: NOW + 60_000 }).why, 'spend')
  assert.equal(defaultVerify({ ...base, spendUntil: NOW - 1 }).active, true, '花完了回来')
  const st = defaultVerify({ ...base, minuteOfDay: 780, startUntil: NOW + 90 * 60_000 })
  assert.deepEqual(st, { active: true, untilMinute: 870, reason: 'start' })
})

test('花余额：要理由、要有余额、5–60 分钟、不超过余额', () => {
  assert.equal(checkSpend({ minutes: 20, reason: '', balance: 60 }).error, 'no-reason')
  assert.equal(checkSpend({ minutes: 20, reason: '看一集纪录片放松', balance: 3 }).error, 'no-balance')
  assert.equal(checkSpend({ minutes: 2, reason: '看一集纪录片放松', balance: 60 }).error, 'too-short')
  assert.equal(checkSpend({ minutes: 50, reason: '看一集纪录片放松', balance: 30 }).minutes, 30)
  assert.equal(checkSpend({ minutes: 90, reason: '看一集纪录片放松', balance: 200 }).minutes, 60)
})

test('模板：默认的渲染成 md 再读回来不变；他改的那一节生效，写坏的那一节用默认', () => {
  const md = renderTemplates(DEFAULT_TEMPLATES)
  assert.match(md, /## 工作日（09:30 自动套）/)
  assert.match(md, /- 09:30–11:00 行测一套（上午） · 题库 20 题 · 保底 10/)
  assert.match(md, /- 90 分钟内 冲刺：行测 30 题 · 题库 30 题 · 保底 15/)
  const back = parseTemplates(md)
  assert.deepEqual(back.workday.items.map((x) => [x.title, x.kind, x.stretch, x.floor, x.startMinute, x.dueMinute]),
    DEFAULT_TEMPLATES.workday.items.map((x) => [x.title, x.kind, x.stretch, x.floor, x.startMinute, x.dueMinute]))
  assert.equal(back.sprint.items[0].relative, 90)
  const edited = md
    .replace('## 工作日（09:30 自动套）', '## 工作日（08:45 自动套）')
    .replace('- 14:30–16:00 行测一套（下午） · 题库 20 题 · 保底 10', '- 14:00–15:00 申论小作文 · 笔记 学习/申论.md 800 字 · 保底 400\n- 16:00–17:00 投递 · 截图 3 张 · 保底 1')
    .replace(/## 休息日[\s\S]*?## 恢复日/, '## 休息日（10:00 自动套）\n- 乱写的一行\n\n## 恢复日')
  const t = parseTemplates(edited)
  assert.equal(t.workday.applyAt, 8 * 60 + 45)
  assert.deepEqual(t.workday.items.map((x) => x.kind), ['quiz', 'file', 'screenshots'])
  assert.equal(t.workday.items[1].path, '学习/申论.md')
  assert.equal(t.restday.items[0].title, '行测一套', '写坏的一节用默认')
})

test('套模板：到点、今天没点、今天没套过才套；过了截止的跳过；冲刺从现在起算', () => {
  const base = { today: '2026-11-21', minuteOfDay: 575, hasSealsToday: false, appliedDay: '', applyAt: 570 }
  assert.equal(shouldApplyTemplate(base), true)
  assert.equal(shouldApplyTemplate({ ...base, minuteOfDay: 560 }), false)
  assert.equal(shouldApplyTemplate({ ...base, hasSealsToday: true }), false)
  assert.equal(shouldApplyTemplate({ ...base, appliedDay: '2026-11-21' }), false)
  assert.equal(shouldApplyTemplate({ ...base, enabled: false }), false)
  const late = templateSeals(DEFAULT_TEMPLATES.workday, { minuteOfDay: 11 * 60 + 5 })
  assert.deepEqual(late.map((x) => x.title), ['行测一套（下午）'])
  const sp = templateSeals(DEFAULT_TEMPLATES.sprint, { minuteOfDay: 600 })
  assert.equal(sp[0].startMinute, 600)
  assert.equal(sp[0].dueMinute, 690)
})

test('熔断：连续两天没过保底进恢复日，一天一次；没验收点的天跳过；连续两天交上退出', () => {
  const S = (day, status) => ({ day, status })
  const seals = { items: [S('d1', 'below'), S('d1', 'stretch'), S('d2', 'below'), S('d3', 'floor'), S('d4', 'waived'), S('d4', 'stretch'), S('d5', 'open')] }
  const o = dayOutcomes(seals, ['d1', 'd2', 'd3', 'd4', 'd5', 'd6'])
  assert.deepEqual(o, { d1: 'failed', d2: 'failed', d3: 'passed', d4: 'passed', d5: 'none', d6: 'none' })
  const enter = recoveryStep(null, o, ['d6', 'd2', 'd1'], 'd7')
  assert.equal(enter.entered, true)
  assert.equal(enter.state.since, 'd7')
  assert.equal(recoveryStep(enter.state, o, ['d6', 'd2', 'd1'], 'd7').changed, false, '同一天不重复判')
  const stay = recoveryStep({ ...enter.state, checkedDay: 'd7' }, { ...o, d7: 'passed' }, ['d7', 'd2'], 'd8')
  assert.equal(stay.state.active, true, '只交上一天还在恢复日')
  const exit = recoveryStep({ ...stay.state }, { ...o, d7: 'passed', d8: 'passed' }, ['d8', 'd7'], 'd9')
  assert.equal(exit.exited, true)
  assert.equal(recoveryStep(null, o, ['d3', 'd2', 'd1'], 'd4').state.active, false, '最近一天过了不进')
})

test('进上下文的话：默认验收、恢复日、正在花余额', () => {
  const l = verifyPromptLines({ verify: { active: true }, recovery: { active: true }, spend: '15:20', untilHm: '12:00' })
  assert.match(l.join('\n'), /默认验收中（到 12:00）/)
  assert.match(l.join('\n'), /恢复日/)
  assert.match(l.join('\n'), /别打断/)
})

import {
  DAYTIME_SPEND_CAP_MIN, DEFAULT_BASE_FUN_MIN, EVENING_CAP_DEFAULT, LUNCH_BREAK_WINDOW,
  applyNodCardOverrides, buildNodCard, classifySpendWindow, eveningCapFor, eveningOf, eveningStep, setEveningCap,
} from '../lib/verifier_mode.js'
import { addSeals } from '../lib/seal.js'

test('晚上：18:00 起算这一晚；过了零点还算前一晚；7:00 以后不算', () => {
  assert.deepEqual(eveningOf('D2', 'D1', 19 * 60), { evening: true, key: 'D2', beforeMidnight: true })
  assert.deepEqual(eveningOf('D2', 'D1', 20), { evening: true, key: 'D1', beforeMidnight: false })
  assert.equal(eveningOf('D2', 'D1', 9 * 60).evening, false)
})

test('晚间用量：18:00 时的今天用量当起点；零点手机清零后接着加；用完那一拍 hit 一次', () => {
  let s = eveningStep(null, { key: 'D1', beforeMidnight: true, funToday: 40, cap: 90, now: 1 }).state
  assert.equal(s.used, 0, '白天用的 40 分钟不算晚上的')
  let r = eveningStep(s, { key: 'D1', beforeMidnight: true, funToday: 100, cap: 90, now: 2 })
  assert.equal(r.state.used, 60)
  assert.equal(r.hit, false)
  r = eveningStep(r.state, { key: 'D1', beforeMidnight: false, funToday: 25, cap: 90, now: 3 })
  assert.equal(r.state.used, 85, '零点后接着加')
  r = eveningStep(r.state, { key: 'D1', beforeMidnight: false, funToday: 31, cap: 90, now: 4 })
  assert.equal(r.hit, true)
  assert.equal(r.state.capHitAt, 4)
  assert.equal(eveningStep(r.state, { key: 'D1', beforeMidnight: false, funToday: 40, cap: 90, now: 5 }).hit, false, '只 hit 一次')
  const fresh = eveningStep(r.state, { key: 'D2', beforeMidnight: true, funToday: 10, cap: 90, now: 6 }).state
  assert.equal(fresh.capHitAt, 0, '新的一晚重新算')
})

test('上限：默认 120；调低立即；调高明天起；夹在 30–180；保底默认 30', () => {
  assert.equal(EVENING_CAP_DEFAULT, 120)
  assert.equal(DEFAULT_BASE_FUN_MIN, 30)
  assert.equal(eveningCapFor(null, 'D1'), EVENING_CAP_DEFAULT)
  const low = setEveningCap(null, 60, { today: 'D1', tomorrow: 'D2' })
  assert.equal(low.immediate, true)
  assert.equal(eveningCapFor(low.value, 'D1'), 60)
  const high = setEveningCap({ minutes: 60 }, 150, { today: 'D1', tomorrow: 'D2' })
  assert.equal(high.immediate, false)
  assert.equal(eveningCapFor(high.value, 'D1'), 60, '今晚不变')
  assert.equal(eveningCapFor(high.value, 'D2'), 150, '明晚起')
  assert.equal(eveningCapFor({ minutes: 999 }, 'D1'), 180)
  assert.equal(setEveningCap(null, 0, { today: 'D1', tomorrow: 'D2' }).ok, false)
})

test('4.9 早上点头卡：今天已有验收点则返回 needed:false；否则生成可微调条目并安全喂给 addSeals', () => {
  const today = '2026-11-21'
  const skip = buildNodCard({
    template: DEFAULT_TEMPLATES.workday,
    existingSeals: [{ id: 's1', day: today, title: '已有' }],
    minuteOfDay: 8 * 60,
    today,
  })
  assert.equal(skip.needed, false)
  assert.deepEqual(skip.items, [])

  const nod = buildNodCard({
    template: DEFAULT_TEMPLATES.workday,
    existingSeals: [{ id: 'old', day: '2026-11-20', title: '昨天的' }],
    minuteOfDay: 8 * 60 + 30,
    today,
  })
  assert.equal(nod.needed, true)
  assert.equal(nod.items.length, 2)
  assert.equal(nod.items[0].kind, 'quiz')

  // 微调：修改第一条 stretch/floor/时间窗（floor 超出 stretch 会被夹回；第二条不保留）
  const edited = applyNodCardOverrides(nod.items, [
    { id: nod.items[0].id, stretch: 15, floor: 99, startMinute: 9 * 60, dueMinute: 10 * 60 + 30, kind: 'screenshots', title: '恶意改类型' },
    { id: nod.items[1].id, keep: false },
  ])
  assert.equal(edited.length, 1)
  assert.equal(edited[0].kind, 'quiz', '不允许修改 kind')
  assert.equal(edited[0].title, nod.items[0].title, '不允许修改 title')
  assert.equal(edited[0].stretch, 15)
  assert.equal(edited[0].floor, 15, 'floor 不得超过 stretch')
  assert.equal(edited[0].startMinute, 9 * 60)
  assert.equal(edited[0].dueMinute, 10 * 60 + 30)

  const added = addSeals(null, edited, { now: NOW, today, minuteOfDay: 8 * 60 + 30, by: 'template' })
  assert.equal(added.added.length, 1)
  assert.equal(added.skipped.length, 0)
})

test('分时花余额窗口 classifySpendWindow 与白天/午休常量', () => {
  assert.equal(DAYTIME_SPEND_CAP_MIN, 30)
  assert.deepEqual(LUNCH_BREAK_WINDOW, [12 * 60, 14 * 60])
  assert.equal(classifySpendWindow(0), 'night')
  assert.equal(classifySpendWindow(6 * 60 + 59), 'night')
  assert.equal(classifySpendWindow(7 * 60), 'daytime')
  assert.equal(classifySpendWindow(11 * 60 + 59), 'daytime')
  assert.equal(classifySpendWindow(12 * 60), 'lunch')
  assert.equal(classifySpendWindow(13 * 60 + 59), 'lunch')
  assert.equal(classifySpendWindow(14 * 60), 'daytime')
  assert.equal(classifySpendWindow(17 * 60 + 59), 'daytime')
  assert.equal(classifySpendWindow(18 * 60), 'evening')
  assert.equal(classifySpendWindow(22 * 60 + 59), 'evening')
  assert.equal(classifySpendWindow(23 * 60), 'night')
  assert.equal(classifySpendWindow(23 * 60 + 59), 'night')
})
