// 墙规则引擎（第二轮）：默认锁时间表、吃饭开放、深夜锁、放风段、收紧、申请窗口、每周额度。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  wallDecision, wallConfig, activeRelax, tightenActive, relaxRemaining, relaxUsedThisWeek,
  trackApplyWindow, applyWindowOpen, dayKey, weekKey, WALL_KIND_NIGHT, WALL_KIND_DEFAULT,
} from '../lib/wall.js'

const TZ = 'Asia/Shanghai'
// 某个周三：2026-11-11 本地零点对应的 UTC ms。用当地时刻推。
function at(hhmm, dayShift = 0) {
  // 以 2026-11-11 00:00 Asia/Shanghai = 2026-11-10T16:00:00Z 为基准。
  const base = Date.UTC(2026, 10, 10, 16, 0, 0)
  const [h, m] = hhmm.split(':').map(Number)
  return base + dayShift * 86_400_000 + (h * 60 + m) * 60_000
}

test('默认锁：白天非吃饭时段锁着，到点开放', () => {
  const cfg = {}
  const d = wallDecision(cfg, at('15:00'), TZ)
  assert.equal(d.locked, true)
  assert.equal(d.kind, WALL_KIND_DEFAULT)
  // 15:00 的默认锁一直到 18:00 晚饭开放。
  assert.equal(d.until, at('18:00'))
  assert.match(d.why, /默认严格/)
})

test('吃饭时段开放，开放到这段结束', () => {
  const d = wallDecision({}, at('12:00'), TZ)
  assert.equal(d.locked, false)
  assert.equal(d.openUntil, at('12:30'))
})

test('深夜锁优先，锁到早上 7 点', () => {
  const d = wallDecision({}, at('01:00'), TZ)
  assert.equal(d.locked, true)
  assert.equal(d.kind, WALL_KIND_NIGHT)
  assert.equal(d.until, at('07:00'))
})

test('墙关掉：不加默认锁', () => {
  const d = wallDecision({ wall: { enabled: false } }, at('15:00'), TZ)
  assert.equal(d.locked, false)
  assert.equal(d.kind, null)
})

test('收紧：当天锁到零点，盖过吃饭时段', () => {
  const cfg = { tighten: { for: dayKey(at('12:00'), TZ), at: at('09:00') } }
  const d = wallDecision(cfg, at('12:00'), TZ)
  assert.equal(d.locked, true)
  assert.equal(d.until, at('24:00'.replace('24', '0'), 1)) // 次日零点
  assert.match(d.why, /收紧/)
})

test('收紧次日复归（daykey 不同就失效）', () => {
  const cfg = { tighten: { for: dayKey(at('12:00'), TZ) } }
  assert.equal(tightenActive(cfg, at('12:00', 1), TZ), false)
})

test('放风段：今天申请到的那一段开放，零点复归', () => {
  const today = dayKey(at('15:00'), TZ)
  const cfg = { relax: { for: today, segments: [{ from: 15 * 60, to: 16 * 60 }], reason: '测试' } }
  // 段内开放
  const inSeg = wallDecision(cfg, at('15:30'), TZ)
  assert.equal(inSeg.locked, false)
  assert.equal(inSeg.openUntil, at('16:00'))
  // 段外仍锁
  assert.equal(wallDecision(cfg, at('16:30'), TZ).locked, true)
  // 明天失效
  assert.equal(activeRelax(cfg, at('15:30', 1), TZ), null)
})

test('每周额度：用量按自然周算，跨周重置', () => {
  const now = at('22:00')
  const cfg = { wall: { weeklyQuota: 3 }, relaxWeek: { week: weekKey(now, TZ), used: 2 } }
  assert.equal(relaxUsedThisWeek(cfg, now, TZ), 2)
  assert.equal(relaxRemaining(cfg, now, TZ), 1)
  // 下一周重置
  assert.equal(relaxRemaining(cfg, at('22:00', 7), TZ), 3)
})

test('wallConfig 补默认值、过滤非法吃饭时段', () => {
  const w = wallConfig({ wall: { meals: [{ from: 60, to: 30 }, { from: 480, to: 510, name: '早' }], eveningOpen: true } })
  assert.equal(w.meals.length, 1)
  assert.equal(w.meals[0].from, 480)
  assert.equal(w.eveningOpen, true)
})

test('申请窗口：对账完成后随机开 15 分钟，只通知一次', () => {
  const now0 = at('21:50')
  const reviewDoneAt = at('21:45')
  // rand=0 → 窗口在对账完成那一刻就开。
  let r = trackApplyWindow(undefined, { reviewDoneAt, now: now0, tz: TZ, rand: () => 0 })
  assert.equal(r.open, true)
  assert.equal(r.justOpened, true)
  // 再调一次：还开着，但不再 justOpened。
  r = trackApplyWindow(r.state, { reviewDoneAt, now: at('21:55'), tz: TZ, rand: () => 0 })
  assert.equal(r.open, true)
  assert.equal(r.justOpened, false)
  // 15 分钟后关闭。
  r = trackApplyWindow(r.state, { reviewDoneAt, now: at('22:05'), tz: TZ, rand: () => 0 })
  assert.equal(r.open, false)
  assert.equal(applyWindowOpen(r.state, at('22:05')), false)
})

test('申请窗口：对账没完成就不排期', () => {
  const r = trackApplyWindow(undefined, { reviewDoneAt: 0, now: at('21:50'), tz: TZ })
  assert.equal(r.open, false)
})
