// R9 第四项：重启协议。坏日（交付率 < 30%）之后不追加码，第二天只要求一个 15 分钟的局。
// 边界：只在换天出草案那一步生效；昨天没开过局、算不出比率、比率够高都不算坏日。

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { RESTART_BELOW, RESTART_MINUTES, planLines, planOf, restartDraft, tick } from '../lib/planwire.js'

// 2026-03-10 00:00 +08，周二，工作日。
const DAY = Date.parse('2026-03-09T16:00:00Z')
const TZ = 'Asia/Shanghai'
const at = (h, m = 0) => DAY + (h * 60 + m) * 60_000
const MIN = (h, m = 0) => h * 60 + m
const base = (over = {}) => ({ now: at(9), day: DAY, min: MIN(9), tz: TZ, config: {}, stored: null, ...over })

test('R1 重启局就是 15 分钟：别的都先不算', () => {
  assert.equal(RESTART_MINUTES, 15)
  const d = restartDraft({ now: at(9), minute: MIN(9) })
  assert.equal(d.items.length, 1, '重启日只排一局')
  assert.equal(d.items[0].budget, RESTART_MINUTES)
  assert.equal(d.restart, true)
})

test('R2 重启局说的是「做成这一局就算回来」，不是加码', () => {
  const d = restartDraft({ now: at(9), minute: MIN(9) })
  assert.ok(d.items[0].why.includes('轨道'), d.items[0].why)
  assert.equal(d.accepted, false, '和别的草案一样要等十分钟')
})

test('R3 换天出草案走重启：只有一局，且能过 normItem', () => {
  const r = tick(base({ restart: true }))
  assert.equal(r.action, 'draft')
  assert.equal(r.plan.items.length, 1)
  assert.equal(r.plan.items[0].budget, RESTART_MINUTES)
  assert.equal(r.plan.items[0].protocolId, 'execute')
  assert.ok(r.sentence.includes('今天'), r.sentence)
})

test('R4 不是坏日就照常排满：两边的草案条数不一样', () => {
  const normal = tick(base())
  const restart = tick(base({ restart: true }))
  assert.equal(normal.action, 'draft')
  assert.ok(normal.plan.items.length > restart.plan.items.length, '正常日要多于重启日')
})

test('R5 重启只管换天那一步：草案已定之后不再塞重启局', () => {
  const first = tick(base({ restart: true }))
  const stored = { ...first.plan, accepted: true, at: at(9) }
  const r = tick(base({ now: at(9), stored, restart: true }))
  assert.notEqual(r.action, 'draft')
  assert.equal(r.plan.items.length, 1, '原有的局没被重启局顶掉')
})

test('R6 阈值是 30%：一天三局做成一局刚好在线，不算坏日', () => {
  assert.equal(RESTART_BELOW, 0.3)
  assert.equal(1 / 3 < RESTART_BELOW, false)
})

test('R7 重启局也进计划行：他能看到今天只有这一件', () => {
  const d = restartDraft({ now: at(9), minute: MIN(9) })
  const lines = planLines({ ...planOf(null, DAY), items: d.items })
  const text = lines.join('\n')
  assert.ok(text.includes('最小的一局'), text)
  assert.ok(text.includes('轨道'), text)
  assert.equal(text.match(/^- \d+:\d+/gm)?.length, 1, '只列这一件：' + text)
})

test('R8 restartDraft 不改传进来的东西：两次调用互不影响', () => {
  const a = restartDraft({ now: at(9), minute: MIN(9) })
  const b = restartDraft({ now: at(10), minute: MIN(10) })
  assert.notEqual(a.items[0].id, b.items[0].id)
  assert.equal(a.items[0].minute, MIN(9))
  assert.equal(b.items[0].minute, MIN(10))
})
