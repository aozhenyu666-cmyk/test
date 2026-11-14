import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  buildSettingsSnapshot, formatWindows, parseDay, parseLimits, parseWindows, settingsMarkdown,
} from '../lib/settings_panel.js'
import { DEFAULT_SETTINGS, settingsOf } from '../lib/settings.js'

const f = (k, val) => ({ ['data.form.' + k]: val })

test('时段：常见的几种写法都认得', () => {
  assert.deepEqual(parseWindows('9:00-12:00，14:00-18:00').windows, [
    { from: 540, to: 720 }, { from: 840, to: 1080 },
  ])
  assert.deepEqual(parseWindows('9-12').windows, [{ from: 540, to: 720 }])
  assert.deepEqual(parseWindows('9：00—12：00').windows, [{ from: 540, to: 720 }])
  assert.deepEqual(parseWindows('9:00 到 12:00').windows, [{ from: 540, to: 720 }])
  assert.deepEqual(parseWindows('10:30-11:15').windows, [{ from: 630, to: 675 }])
})

test('时段：空串就是没有固定时段，不是错', () => {
  assert.deepEqual(parseWindows('').windows, [])
  assert.deepEqual(parseWindows('   ').windows, [])
  assert.deepEqual(parseWindows(null).windows, [])
})

test('时段：看不出来的整份拒掉，并说清是哪一段', () => {
  assert.ok(parseWindows('早上九点').error.includes('早上九点'))
  assert.ok(parseWindows('12:00-9:00').error.includes('结束比开始早'))
  assert.ok(parseWindows('25:00-26:00').error.includes('超出'))
  assert.ok(parseWindows('9:00-12:00，11:00-13:00').error.includes('重叠'))
})

test('时段排好序再交出去', () => {
  assert.deepEqual(parseWindows('14:00-18:00，9:00-12:00').windows, [
    { from: 540, to: 720 }, { from: 840, to: 1080 },
  ])
})

test('时段反向格式化回去是能再解析的同一份', () => {
  const list = parseWindows('9:00-12:00，14:00-18:00').windows
  assert.equal(formatWindows(list), '9:00-12:00，14:00-18:00')
  assert.deepEqual(parseWindows(formatWindows(list)).windows, list)
  assert.equal(formatWindows([]), '')
})

test('作息页：改紧立即生效那一路能出完整一份设定', () => {
  const r = parseDay(settingsOf({}), {
    ...f('workday', '8:00-11:30，13:30-17:30'),
    ...f('restday', '10:00-12:00'),
    ...f('outing', ''),
    ...f('minBouts', '5'),
    ...f('minThinking', '2'),
    ...f('budget.study', '35'),
    ...f('budget.execute', '40'),
    ...f('budget.design', '30'),
    ...f('budget.outing', '40'),
    ...f('budget.rest', '20'),
    ...f('budget.thinking', '25'),
  })
  assert.equal(r.error, undefined)
  assert.deepEqual(r.next.windows.workday, [{ from: 480, to: 690 }, { from: 810, to: 1050 }])
  assert.deepEqual(r.next.windows.outing, [])
  assert.equal(r.next.minBouts, 5)
  assert.equal(r.next.minThinking, 2)
  assert.equal(r.next.budget.study, 35)
})

test('作息页：错一格就整份退回，不交半份坏设定', () => {
  const bad = {
    ...f('workday', '9:00-12:00'), ...f('restday', '乱写'), ...f('outing', ''),
    ...f('minBouts', '4'), ...f('minThinking', '1'),
    ...f('budget.study', '25'), ...f('budget.execute', '30'), ...f('budget.design', '30'),
    ...f('budget.outing', '40'), ...f('budget.rest', '20'), ...f('budget.thinking', '25'),
  }
  const r = parseDay(settingsOf({}), bad)
  assert.ok(r.error.startsWith('休息日'), r.error)
  assert.equal(r.next, undefined)
})

test('作息页：数字越界有说法，不是默默夹到边界', () => {
  const base = {
    ...f('workday', '9:00-12:00'), ...f('restday', ''), ...f('outing', ''),
    ...f('minBouts', '4'), ...f('minThinking', '1'),
    ...f('budget.study', '25'), ...f('budget.execute', '30'), ...f('budget.design', '30'),
    ...f('budget.outing', '40'), ...f('budget.rest', '20'), ...f('budget.thinking', '25'),
  }
  assert.ok(parseDay(settingsOf({}), { ...base, ...f('minBouts', '99') }).error.includes('0–12'))
  assert.ok(parseDay(settingsOf({}), { ...base, ...f('minBouts', 'abc') }).error.includes('要填数字'))
  assert.ok(parseDay(settingsOf({}), { ...base, ...f('budget.study', '1') }).error.includes('5–180'))
})

test('阈值页：分钟填进去，毫秒存下来', () => {
  const r = parseLimits(settingsOf({}), {
    ...f('E1Count', '2'), ...f('E1WindowMin', '20'), ...f('cellMin', '15'),
    ...f('L1WorkMin', '10'), ...f('L1RestMin', '40'), ...f('L2Min', '20'), ...f('L3Min', '90'),
  })
  assert.equal(r.error, undefined)
  assert.equal(r.next.escalate.E1Count, 2)
  assert.equal(r.next.escalate.E1WindowMs, 20 * 60_000)
  assert.equal(r.next.escalate.cellMin, 15)
  assert.equal(r.next.escalate.L1WorkMin, 10)
  assert.equal(r.next.escalate.L1RestMin, 40)
  assert.equal(r.next.escalate.L3Min, 90)
})

test('阈值页：错一格整份退回', () => {
  const r = parseLimits(settingsOf({}), {
    ...f('E1Count', '0'), ...f('E1WindowMin', '20'), ...f('cellMin', '15'),
    ...f('L1WorkMin', '10'), ...f('L1RestMin', '40'), ...f('L2Min', '20'), ...f('L3Min', '90'),
  })
  assert.ok(r.error.startsWith('E1 敷衍次数'), r.error)
})

test('快照：现在的值都摆出来，表单预填的是现在的值不是空白', () => {
  const s = buildSettingsSnapshot({ settings: DEFAULT_SETTINGS, now: 0 })
  assert.ok(s.rows.some((r) => r.startsWith('工作日：9:00-12:00')), s.rows.join(' / '))
  assert.ok(s.rows.some((r) => r.includes('没有固定时段')), '外出日默认没有时段，要说出来')
  assert.ok(s.budgets.some((b) => b.includes('学习 25 分钟')))
  assert.ok(s.limits.some((l) => l.includes('30 分钟内敷衍 3 次')))
  assert.equal(s.form.workday, '9:00-12:00，14:00-18:00')
  assert.equal(s.form.minBouts, '4')
  assert.equal(s.form['budget.study'], '25')
  assert.equal(s.form.E1WindowMin, '30')
  // 预填值必须能原样交回去：不然打开设定页点保存就会把自己的设定改坏。
  const rt = parseDay(settingsOf({}), Object.fromEntries(Object.entries(s.form).map(([k, v]) => ['data.form.' + k, v])))
  assert.equal(rt.error, undefined)
  assert.deepEqual(rt.next.windows, settingsOf({}).windows)
  assert.equal(rt.next.minBouts, 4)
  const rt2 = parseLimits(settingsOf({}), Object.fromEntries(Object.entries(s.form).map(([k, v]) => ['data.form.' + k, v])))
  assert.equal(rt2.error, undefined)
  assert.deepEqual(rt2.next.escalate, settingsOf({}).escalate)
})

test('快照：挂着改松的话写清什么时候生效', () => {
  const DAY = 86_400_000
  const s = buildSettingsSnapshot({ settings: DEFAULT_SETTINGS, pending: { settings: DEFAULT_SETTINGS, at: DAY }, pendingAt: DAY, now: 0 })
  assert.equal(s.pending.on, true)
  assert.ok(s.pending.text.includes('挂到'), s.pending.text)
  const none = buildSettingsSnapshot({ settings: DEFAULT_SETTINGS, now: 0 })
  assert.equal(none.pending.on, false)
  assert.equal(none.pending.text, '没有挂着的改动。')
})

test('快照：脏输入不炸', () => {
  for (const bad of [undefined, null, {}, { settings: 'x' }, { settings: {}, pending: 'x' }]) {
    const s = buildSettingsSnapshot(bad)
    assert.ok(Array.isArray(s.rows))
    assert.equal(typeof s.form.minBouts, 'string')
  }
})

test('设定.md：两页的数都在，且带一句「改文件不改设定」', () => {
  const s = buildSettingsSnapshot({ settings: DEFAULT_SETTINGS, now: 0 })
  const md = settingsMarkdown(s)
  assert.ok(md.startsWith('# 墙的设定'))
  assert.ok(md.includes('工作日：9:00-12:00'))
  assert.ok(md.includes('每天最少局数'))
  assert.ok(md.includes('各局型默认预算'))
  assert.ok(md.includes('升级阈值'))
  assert.ok(md.includes('改它不会改设定'), '得说清这份文件是回显，不是开关')
  assert.ok(md.length < 8000)
})

test('设定.md：没挂改动就不写那一行', () => {
  const md = settingsMarkdown(buildSettingsSnapshot({ settings: DEFAULT_SETTINGS, now: 0 }))
  assert.equal(md.includes('挂到'), false)
})

test('两个页面的字段和解析函数对得上（少一个就是永远存不进去的框）', () => {
  const s = buildSettingsSnapshot({ settings: DEFAULT_SETTINGS, now: 0 })
  const dayKeys = ['workday', 'restday', 'outing', 'minBouts', 'minThinking',
    'budget.study', 'budget.execute', 'budget.design', 'budget.outing', 'budget.rest', 'budget.thinking']
  const limitKeys = ['E1Count', 'E1WindowMin', 'cellMin', 'L1WorkMin', 'L1RestMin', 'L2Min', 'L3Min']
  for (const k of [...dayKeys, ...limitKeys]) assert.notEqual(s.form[k], undefined, '快照缺了 ' + k)
})
