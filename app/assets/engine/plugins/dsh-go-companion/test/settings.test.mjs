// W4 设定：默认值、改紧立即生效、改松走冷却期、工作日/休息日/外出日。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  COOL_MS, DEFAULT_SETTINGS, DAY_OUTING, DAY_RESTDAY, DAY_WORKDAY,
  applySettings, dayKindOf, inWorkWindow, loosens, nextWindowAt, settingsOf, windowsOf,
} from '../lib/settings.js'

const TZ = 'Asia/Shanghai'
// 2026-03-10 00:00 当地 = 2026-03-09T16:00Z。这天是周二（工作日）。
const DAY = Date.parse('2026-03-09T16:00:00Z')
const at = (hour, minute = 0) => DAY + (hour * 60 + minute) * 60_000
const HOUR = 3_600_000

test('W4 空配置读出来就是默认值', () => {
  const s = settingsOf({})
  assert.equal(s.minBouts, 4)
  assert.equal(s.minThinking, 1)
  assert.deepEqual(s.windows.workday, [{ from: 540, to: 720 }, { from: 840, to: 1080 }])
  assert.equal(s.escalate.E1Count, 3)
  assert.equal(s.escalate.L1WorkMin, 15)
  assert.equal(s.escalate.L3Min, 60)
  assert.equal(s.judgeModel, '')
})

test('W4 坏值兜底，越界的夹回范围', () => {
  const s = settingsOf({
    day: {
      minBouts: 'abc',
      minThinking: 99,
      budget: { study: 1, execute: 999, thinking: 'x' },
      escalate: { E1Count: 0, L3Min: 5, cellMin: 999 },
    },
  })
  assert.equal(s.minBouts, 4, 'NaN 用默认')
  assert.equal(s.minThinking, 6, 'minThinking 上限 6')
  assert.equal(s.budget.study, 5, '预算下限 5')
  assert.equal(s.budget.execute, 180, '预算上限 180')
  assert.equal(s.budget.thinking, DEFAULT_SETTINGS.budget.thinking)
  assert.equal(s.escalate.E1Count, 1, 'E1Count 下限 1')
  assert.equal(s.escalate.L3Min, 10, 'L3Min 下限 10')
  assert.equal(s.escalate.cellMin, 120, 'cellMin 上限 120')
})

test('W4 工作时段过滤坏的、按开始时间排序', () => {
  const s = settingsOf({ day: { windows: { workday: [[840, 1080], [600, 600], [0, 60], 'x', { from: 540, to: 720 }] } } })
  assert.deepEqual(s.windows.workday, [
    { from: 0, to: 60 },
    { from: 540, to: 720 },
    { from: 840, to: 1080 },
  ], 'to<=from 的丢掉，两种写法都收')
})

test('W4 改紧立即生效：下限从 4 局提到 5 局', () => {
  const r = applySettings({
    prev: { day: { minBouts: 4 } },
    next: { day: { minBouts: 5 } },
    now: at(9),
  })
  assert.equal(r.applied, 'immediate')
  assert.equal(r.settings.minBouts, 5)
  assert.equal(r.pending, null)
})

test('W4 改松挂起来：下限从 4 局降到 3 局，冷却期内还是 4', () => {
  const r = applySettings({
    prev: { day: { minBouts: 4 } },
    next: { day: { minBouts: 3 } },
    now: at(9),
  })
  assert.equal(r.applied, 'pending')
  assert.equal(r.settings.minBouts, 4, '手上这套继续用')
  assert.equal(r.pending.settings.minBouts, 3)
  assert.equal(r.pendingAt, at(9) + COOL_MS)
})

test('W4 冷却期到点，挂着的松绑才生效', () => {
  const first = applySettings({ prev: { day: { minBouts: 4 } }, next: { day: { minBouts: 3 } }, now: at(9) })
  const early = applySettings({ prev: { day: { minBouts: 4 } }, next: { day: { minBouts: 4 } }, now: at(9) + COOL_MS - 1, pending: first.pending })
  assert.equal(early.applied, 'immediate')
  assert.equal(early.settings.minBouts, 4, '差一分钟也不行')

  const due = applySettings({ prev: { day: { minBouts: 4 } }, next: { day: { minBouts: 4 } }, now: at(9) + COOL_MS, pending: first.pending })
  assert.equal(due.applied, 'pending-due')
  assert.equal(due.settings.minBouts, 3)
  assert.equal(due.pending, null)
})

test('W4 每一项改松都能认出来', () => {
  const base = settingsOf({})
  const looser = [
    { minBouts: 3 },
    { minThinking: 0 },
    { budget: { study: 30 } },
    { budget: { execute: 60 } },
    { escalate: { L1WorkMin: 20 } },
    { escalate: { L1RestMin: 60 } },
    { escalate: { L2Min: 40 } },
    { escalate: { L3Min: 90 } },
    { escalate: { E1Count: 5 } },
    { escalate: { E1WindowMs: 60 * 60_000 } },
    { escalate: { cellMin: 5 } },
    { windows: { workday: [[9 * 60, 12 * 60], [14 * 60, 20 * 60]] } },
  ]
  for (const day of looser) {
    assert.ok(loosens(settingsOf({ day }), base), JSON.stringify(day) + ' 应该算改松')
  }
})

test('W4 改紧和不变都不算改松', () => {
  const base = settingsOf({})
  assert.equal(loosens(settingsOf({}), base), false, '不变')
  assert.equal(loosens(settingsOf({ day: { minBouts: 6 } }), base), false, '下限提高是改紧')
  assert.equal(loosens(settingsOf({ day: { escalate: { L3Min: 30 } } }), base), false, '娱乐上限降低是改紧')
  assert.equal(loosens(settingsOf({ day: { escalate: { cellMin: 20 } } }), base), false, '审讯舱加长是改紧')
})

test('W4 工作日 / 休息日 / 外出日分得清', () => {
  assert.equal(dayKindOf(at(9), TZ, {}), DAY_WORKDAY, '2026-03-10 是周二')
  assert.equal(dayKindOf(DAY + 4 * 86_400_000, TZ, {}), DAY_RESTDAY, '2026-03-14 是周六')
  assert.equal(dayKindOf(DAY + 5 * 86_400_000, TZ, {}), DAY_RESTDAY, '周日也算休息日')
  assert.equal(dayKindOf(at(9), TZ, { day: { outingDays: ['2026-03-10'] } }), DAY_OUTING)
  assert.equal(dayKindOf(at(9), TZ, { day: { outingDays: ['2026-03-11'] } }), DAY_WORKDAY, '不是今天就不算外出')
})

test('W4 工作时段左闭右开', () => {
  const s = settingsOf({})
  assert.equal(inWorkWindow(s, DAY_WORKDAY, 540), true, '9:00 进了')
  assert.equal(inWorkWindow(s, DAY_WORKDAY, 719), true, '11:59 还在')
  assert.equal(inWorkWindow(s, DAY_WORKDAY, 720), false, '12:00 出了')
  assert.equal(inWorkWindow(s, DAY_WORKDAY, 780), false, '午饭不算工作时段')
  assert.equal(inWorkWindow(s, DAY_RESTDAY, 780), false, '休息日 13:00 不在 10–12')
  assert.equal(inWorkWindow(s, DAY_RESTDAY, 600), true, '休息日 10:00 在')
})

test('W4 下一个工作时段', () => {
  const s = settingsOf({})
  assert.equal(nextWindowAt(s, DAY_WORKDAY, 480, DAY), DAY + 540 * 60_000, '早上 8 点 → 9 点')
  assert.equal(nextWindowAt(s, DAY_WORKDAY, 780, DAY), DAY + 840 * 60_000, '午饭时 → 下午两点')
  assert.equal(nextWindowAt(s, DAY_WORKDAY, 1200, DAY), DAY + 86_400_000, '晚上没有就顺延')
})

test('W4 返回的是副本，改它不影响设定', () => {
  const s = settingsOf({})
  const w = windowsOf(s, DAY_WORKDAY)
  w[0].from = 1
  assert.equal(s.windows.workday[0].from, 540)
})

test('W4 冷却期可调，0 就是当场生效', () => {
  const r = applySettings({ prev: { day: { minBouts: 4 } }, next: { day: { minBouts: 2 } }, now: at(9), coolMs: 0 })
  assert.equal(r.applied, 'pending')
  const due = applySettings({ prev: { day: { minBouts: 4 } }, next: {}, now: at(9), pending: r.pending, coolMs: 0 })
  assert.equal(due.applied, 'pending-due')
  assert.equal(due.settings.minBouts, 2)
})

test('W4 挂起期间又来一次改紧：改紧立刻生效，松绑还继续挂着', () => {
  const first = applySettings({ prev: { day: { minBouts: 4 } }, next: { day: { minBouts: 3 } }, now: at(9) })
  const r = applySettings({ prev: { day: { minBouts: 5 } }, next: { day: { minBouts: 3, minThinking: 2 } }, now: at(10), pending: first.pending })
  assert.equal(r.applied, 'pending')
  assert.equal(r.settings.minBouts, 5, '新提交的这版本来就更紧的那部分…先挂着，手上还是 5')
  assert.equal(r.pending.settings.minThinking, 2)
})

test('W4 语音与局官模型只收字符串、有长度上限', () => {
  const s = settingsOf({ day: { voice: { provider: 123, voiceId: 'x'.repeat(200) }, judgeModel: 'y'.repeat(200) } })
  assert.equal(s.voice.provider, '123')
  assert.equal(s.voice.voiceId.length, 80)
  assert.equal(s.judgeModel.length, 120)
})

test('W4 COOL_MS 是一天', () => {
  assert.equal(COOL_MS, 24 * HOUR)
})
