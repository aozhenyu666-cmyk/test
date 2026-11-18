// 2026-11-18 推进器 + 三档。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { driveMode, nextDrive, returnAction, stepTakeover, TAKEOVER_MAX_MS, TAKEOVER_MAX_PER_DAY } from '../lib/driver.js'

const T = Date.parse('2026-11-18T06:00:00Z') // 14:00 北京
const M = 14 * 60

test('档位：清醒 = 伙伴；在滑 / 局空着 = 推手；陷住 / 额度用完还在刷 / 连续偏航 / 契约逾期 = 接管', () => {
  assert.equal(driveMode({ tideBand: 'clear' }).mode, 'partner')
  assert.equal(driveMode({ tideBand: 'slide' }).mode, 'push')
  assert.equal(driveMode({ tideBand: 'clear', boutGap: true }).mode, 'push')
  assert.equal(driveMode({ tideBand: 'trap' }).mode, 'takeover')
  assert.equal(driveMode({ tideBand: 'clear', quotaOut: true, frontTier: 'fun', frontMinutes: 6 }).mode, 'takeover')
  assert.equal(driveMode({ tideBand: 'clear', quotaOut: true, frontTier: 'work', frontMinutes: 6 }).mode, 'partner', '额度用完但在干活不算')
  assert.equal(driveMode({ tideBand: 'clear', leaseDrifts30m: 2 }).mode, 'takeover')
  assert.equal(driveMode({ tideBand: 'clear', contractOverdue: true }).mode, 'takeover')
})

test('接管：开始 → 回到工作 App 满 3 分钟解除；45 分钟上限；一天最多 3 次；刚解除冷却 30 分钟', () => {
  let r = stepTakeover(null, { now: T, day: 'd', mode: 'takeover', why: '陷住了' })
  assert.equal(r.event, 'start')
  r = stepTakeover(r.state, { now: T + 60_000, day: 'd', mode: 'takeover', frontTier: 'fun', frontMinutes: 20 })
  assert.equal(r.event, 'hold')
  r = stepTakeover(r.state, { now: T + 120_000, day: 'd', frontTier: 'work', frontMinutes: 3 })
  assert.equal(r.event, 'end')
  assert.equal(stepTakeover(r.state, { now: T + 5 * 60_000, day: 'd', mode: 'takeover' }).event, null, '冷却中')
  const capped = stepTakeover({ active: false, count: TAKEOVER_MAX_PER_DAY, countDay: 'd' }, { now: T, day: 'd', mode: 'takeover' })
  assert.equal(capped.event, 'capped')
  const long = stepTakeover({ active: true, since: T, count: 1, countDay: 'd' }, { now: T + TAKEOVER_MAX_MS, day: 'd', mode: 'takeover' })
  assert.equal(long.event, 'end')
  const blocked = stepTakeover({ active: true, since: T, count: 1, countDay: 'd' }, { now: T + 1, day: 'd', blocked: true })
  assert.equal(blocked.event, 'end', '紧急解除 / 深夜 / 连麦时让路')
  assert.equal(stepTakeover(null, { now: T, day: 'd', mode: 'takeover', blocked: true }).event, null)
})

test('推进：契约验收点 10 分钟内到点，伙伴档也推', () => {
  const d = nextDrive({ now: T, minuteOfDay: M, mode: 'partner', checkpoints: [{ id: 'c1', title: '三段因果卡', dueAt: T + 8 * 60_000 }] })
  assert.equal(d.kind, 'checkpoint')
  assert.equal(d.role, 'strategist')
  assert.equal(nextDrive({ now: T, minuteOfDay: M, mode: 'partner', checkpoints: [{ id: 'c1', title: 'x', dueAt: T + 8 * 60_000 }], memo: { 'cp:c1': T - 60_000 } }), null, '推过就不再推')
})

test('推进：截止日剩 3 天推一次', () => {
  const d = nextDrive({ now: T, minuteOfDay: M, mode: 'push', deadlines: [{ id: 'g', title: '国考报名', daysLeft: 3 }] })
  assert.equal(d.kind, 'deadline')
  assert.match(d.text, /国考报名/)
  assert.equal(nextDrive({ now: T, minuteOfDay: M, mode: 'push', deadlines: [{ id: 'g', title: 'x', daysLeft: 5 }], boutRunning: true }), null)
})

test('推进：局收了 5 分钟先推，10 分钟还没动就把计划里的下一局提前开', () => {
  const items = [{ id: 'b2', title: '简历投递', minute: M + 60 }]
  const push = nextDrive({ now: T, minuteOfDay: M, mode: 'push', lastBoutEndAt: T - 6 * 60_000, planItems: items })
  assert.equal(push.kind, 'bout-gap')
  const auto = nextDrive({ now: T, minuteOfDay: M, mode: 'push', lastBoutEndAt: T - 11 * 60_000, planItems: items })
  assert.equal(auto.kind, 'auto-open')
  assert.equal(auto.itemId, 'b2')
  const soon = nextDrive({ now: T, minuteOfDay: M, mode: 'push', lastBoutEndAt: T - 11 * 60_000, planItems: [{ id: 'b3', title: 'x', minute: M + 5 }] })
  assert.notEqual(soon?.kind, 'auto-open', '下一局 10 分钟内本来就要开，不抢')
  const none = nextDrive({ now: T, minuteOfDay: M, mode: 'push', lastBoutEndAt: T - 6 * 60_000, planItems: [] })
  assert.match(none.text, /open_bout/, '后面没排局：交给鲸澜现排')
})

test('推进：推手档空档交给鱼丸，伙伴档不推', () => {
  assert.equal(nextDrive({ now: T, minuteOfDay: M, mode: 'push' }).role, 'companion')
  assert.equal(nextDrive({ now: T, minuteOfDay: M, mode: 'partner' }), null)
})

test('回位动作由代码定', () => {
  assert.match(returnAction({ nextItem: { title: '行测', first: '打开粉笔做 5 道' } }), /开「行测」这一局.*粉笔/)
  assert.match(returnAction({}), /Gemini/)
})
