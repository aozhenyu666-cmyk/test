import { test } from 'node:test'
import assert from 'node:assert/strict'
import { MORNING_CAP_MS, MORNING_FROM, trackRhythm } from '../lib/rhythm.js'

const NOW = Date.parse('2026-11-08T08:00:00+08:00')
const tick = (min, extra = {}) => trackRhythm({}, { min, day: 1, now: NOW, screenOn: true, reviewedToday: false, ...extra })

test('早上对线：5 点后第一次亮屏就开门，不再卡 10 点', () => {
  assert.equal(MORNING_FROM, 5 * 60)
  assert.equal(tick(4 * 60 + 59).openMorning, false, '5 点前不算')
  assert.equal(tick(5 * 60).openMorning, true)
  assert.equal(tick(10 * 60).openMorning, true, '10 点以后也算')
  assert.equal(tick(11 * 60).openMorning, true)
  assert.equal(tick(15 * 60).openMorning, true, '睡到下午，第一屏也算')
  assert.equal(tick(20 * 60).openMorning, true)
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
