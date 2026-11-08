import { test } from 'node:test'
import assert from 'node:assert/strict'
import { MORNING_CAP_MS, MORNING_FROM, trackRhythm } from '../lib/rhythm.js'

const NOW = Date.parse('2026-11-08T08:00:00+08:00')
const tick = (min, extra = {}) => trackRhythm({}, { min, day: 1, now: NOW, screenOn: true, reviewedToday: false, ...extra })

test('早上对线：深夜锁结束（7 点）后第一次亮屏才开门', () => {
  assert.equal(MORNING_FROM, 7 * 60)
  assert.equal(tick(6 * 60 + 59).openMorning, false, '7 点前还在深夜锁里，不算')
  assert.equal(tick(7 * 60 + 1).openMorning, true, '7:01 第一次亮屏开')
  assert.equal(tick(10 * 60).openMorning, true, '10 点以后也算')
  assert.equal(tick(11 * 60 + 59).openMorning, true)
})

test('中午以后不再开早上那扇门，交给忙时/对账', () => {
  assert.equal(tick(12 * 60).openMorning, false)
  assert.equal(tick(15 * 60).openMorning, false)
  assert.equal(tick(21 * 60).openMorning, false)
})

test('早上对线：亮屏才开，同一天只开一次', () => {
  assert.equal(tick(9 * 60, { screenOn: false }).openMorning, false)
  const first = tick(9 * 60)
  assert.equal(first.openMorning, true)
  assert.equal(first.morningGate, true)
  const again = trackRhythm(first.state, { min: 9 * 60 + 5, day: 1, now: NOW + 5 * 60_000, screenOn: true, reviewedToday: false })
  assert.equal(again.openMorning, false)
  assert.equal(again.morningGate, true, '门还开着，等他说 3 件事')
})

test('早上对线：30 分钟兜底，过了就自动关', () => {
  const opened = tick(9 * 60)
  const later = trackRhythm(opened.state, {
    min: 9 * 60 + 31,
    day: 1,
    now: NOW + MORNING_CAP_MS + 60_000,
    screenOn: true,
    reviewedToday: false,
  })
  assert.equal(later.openMorning, false)
  assert.equal(later.morningGate, false)
})

test('一天只开一次：已经对过线就不再开', () => {
  const opened = tick(9 * 60)
  const done = { ...opened.state, morningDone: true }
  const again = trackRhythm(done, { min: 15 * 60, day: 1, now: NOW + 6 * 60 * 60_000, screenOn: true, reviewedToday: false })
  assert.equal(again.openMorning, false)
  assert.equal(again.morningGate, false)
})
