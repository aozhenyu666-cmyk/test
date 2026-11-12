import { test } from 'node:test'
import assert from 'node:assert/strict'

import { LATE_SKIP_MIN, emptyPlan, endsIn, planLines, planOf, tick } from '../lib/planwire.js'

// 2026-03-10 00:00 +08，周二，工作日。
const DAY = Date.parse('2026-03-09T16:00:00Z')
const TZ = 'Asia/Shanghai'
const at = (h, m = 0) => DAY + (h * 60 + m) * 60_000
const MIN = (h, m = 0) => h * 60 + m
const base = (over = {}) => ({ now: at(9), day: DAY, min: MIN(9), tz: TZ, config: {}, stored: null, ...over })

test('第一天没有计划：出草案，草案里不止下限那么多局', () => {
  const r = tick(base())
  assert.equal(r.action, 'draft')
  assert.ok(r.plan.items.length >= 4, '草案至少排到最少局数')
  assert.ok(r.plan.items.length > 4, '草案要比下限多，删局才有得删')
  assert.ok(r.sentence.includes('今天'), '第一句是计划：' + r.sentence)
  assert.equal(r.plan.accepted, false)
})

test('草案十分钟没改就自动生效', () => {
  const first = tick(base())
  const stored = { ...first.plan, at: at(9) }
  const early = tick(base({ now: at(9, 5), stored }))
  assert.equal(early.action, 'wait-confirm')
  const late = tick(base({ now: at(9, 11), min: MIN(9, 11), stored }))
  assert.equal(late.action, 'confirm')
  assert.equal(late.plan.accepted, true)
  assert.ok(late.sentence.includes('十分钟'), late.sentence)
})

test('生效后到点就开，开过的不再开第二次', () => {
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const r = tick(base({ now: at(9), stored }))
  assert.equal(r.action, 'open')
  assert.ok(['study', 'execute', 'design', 'outing', 'rest', 'thinking'].includes(r.start))
  assert.ok(r.sentence.includes('开始'), r.sentence)
  // 落盘再跑一次：这一局已经算开过，不该再开同一条。
  const again = tick(base({ now: at(9), stored: r.plan }))
  assert.notEqual(again.action, 'open')
})

test('过点太久记空局，不补开——这是崩溃之后不拼凑的那条线', () => {
  const draft = tick(base())
  // 引擎停了两小时，回来时第一局已经过点 120 分钟。
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const r = tick(base({ now: at(11), min: MIN(11), stored }))
  assert.notEqual(r.action, 'open', '过点太久不该补开')
  assert.equal(r.action, 'skip')
  assert.ok(r.why.includes('空局'), r.why)
  const missed = r.plan.items.filter((i) => i.missed)
  assert.ok(missed.length >= 1)
  // 记过空局的那一局不会再回来开。
  for (const i of missed) assert.equal(i.openedAt, undefined)
})

test('一次节拍把整段积压一次性记成空局，不会在后面的节拍里接着补', () => {
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const first = tick(base({ now: at(17, 30), min: MIN(17, 30), stored }))
  assert.equal(first.action, 'skip')
  // 第二拍：积压已经全部记成空局，队列里没有过点的了。
  const second = tick(base({ now: at(17, 31), min: MIN(17, 31), stored: first.plan }))
  assert.notEqual(second.action, 'open')
  const left = first.plan.items.filter((i) => !i.openedAt && !i.missed)
  assert.equal(left.length, 0, '过点的都该记成空局')
})

test('迟到不超过 LATE_SKIP_MIN 照开（过点一会儿不算空局）', () => {
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const first = stored.items[0]
  const r = tick(base({ now: at(9, 5), min: first.minute + LATE_SKIP_MIN - 1, stored }))
  assert.equal(r.action, 'open')
})

test('认不出来的局型跳过并说明，不拿别的局顶替', () => {
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const first = stored.items[0]
  // 只装了学习局：草案第一局是执行局的话也得跳过。
  const r = tick(base({ now: DAY + first.minute * 60_000, min: first.minute, stored, known: ['study'] }))
  if (first.protocolId === 'study') {
    assert.equal(r.action, 'open')
  } else {
    assert.equal(r.action, 'skip')
    assert.ok(r.why.includes('还没装'), r.why)
    assert.equal(r.start, '')
  }
})

test('有一局在跑就不开新的', () => {
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const r = tick(base({ now: at(9), stored, running: true }))
  assert.notEqual(r.action, 'open')
})

test('换天不带昨天的计划：昨天没做完的进空局账，不进今天的计划', () => {
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const tomorrow = tick(base({ now: DAY + 86_400_000 + 9 * 3_600_000, day: DAY + 86_400_000, stored }))
  assert.equal(tomorrow.action, 'draft')
  assert.equal(tomorrow.plan.items.some((i) => i.missed), false, '新一天的项目不该带着昨天的空局')
  assert.equal(tomorrow.plan.day, DAY + 86_400_000)
})

test('坏数据不炸：随便什么东西当计划都退回空壳', () => {
  for (const bad of [null, undefined, 'x', 42, [], { day: 'x' }, { day: DAY, items: 'nope' }]) {
    const p = planOf(bad, DAY)
    assert.equal(typeof p, 'object')
    assert.ok(Array.isArray(p.items))
  }
  const p = planOf({ day: DAY, items: [{}, { protocolId: 'study' }, null, { protocolId: 'study', minute: -5 }] }, DAY)
  assert.equal(p.items.length, 1, '缺字段的候选直接丢掉，不当成 study@0')
})

test('计划进 prompt：定了就不再问他想做什么', () => {
  const draft = tick(base())
  const lines = planLines(draft.plan.items.length ? { ...draft.plan, accepted: true } : emptyPlan(DAY), { now: at(10), min: MIN(10) })
  assert.ok(lines.length > 1)
  assert.ok(lines[0].includes('不要再问他'), lines[0])
  assert.ok(lines[0].includes('还差'))
})

test('还没计划时 prompt 里一个字都不写', () => {
  assert.deepEqual(planLines(null), [])
  assert.deepEqual(planLines(emptyPlan(DAY)), [])
})

test('空局在 prompt 里写明是账不是惩罚', () => {
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const skipped = tick(base({ now: at(17, 30), min: MIN(17, 30), stored }))
  const lines = planLines(skipped.plan, { now: at(17, 31), min: MIN(17, 31) })
  assert.ok(lines.some((l) => l.includes('空局是账')), lines.join('\n'))
})

test('nextAt 永远在未来，不会把定时器打成立即执行', () => {
  const r = tick(base())
  assert.ok(r.nextAt > r.plan.at)
  const draft = tick(base())
  const stored = { ...draft.plan, accepted: true, at: at(9) }
  const hold = tick(base({ now: at(9), stored }))
  assert.ok(hold.nextAt > at(9), 'hold 时也给一个未来的时刻')
})

test('endsIn 是天亮前的剩余毫秒', () => {
  assert.equal(endsIn(DAY, DAY), 86_400_000)
  assert.equal(endsIn(DAY, DAY + 86_400_000), 0)
  assert.equal(endsIn(0, DAY), 0)
})
