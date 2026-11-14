// R9 第五项（二）：鱼丸的线索队列。
//
// 她看见的现象进鲸澜的待查队列，只报现象、不下结论，一天最多 5 条。
// 上限是这一层的存在理由：线报多了就不是「看见」，是又一种催。

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { CLUE_MAX_PER_DAY, add, empty, leftOf, lines, ofDay } from '../lib/clue.js'

// 2026-03-10 00:00 +08，周二。
const DAY = Date.parse('2026-03-09T16:00:00Z')

test('C1 空队列：一条也没有，还能报 5 条', () => {
  const s = empty(DAY)
  assert.equal(s.items.length, 0)
  assert.equal(leftOf(s, DAY), CLUE_MAX_PER_DAY)
  assert.equal(CLUE_MAX_PER_DAY, 5)
})

test('C2 递一条：进去了，还能报 4 条', () => {
  const r = add(empty(DAY), { text: '他今天说了三次累', at: 1, day: DAY })
  assert.equal(r.ok, true)
  assert.equal(r.state.items.length, 1)
  assert.equal(r.state.items[0].text, '他今天说了三次累')
  assert.equal(r.left, 4)
})

test('C3 第 5 条能进，第 6 条被挡', () => {
  let s = empty(DAY)
  for (let i = 1; i <= 5; i += 1) {
    const r = add(s, { text: '线索' + i, day: DAY })
    assert.equal(r.ok, true, '第 ' + i + ' 条该能进')
    s = r.state
  }
  const over = add(s, { text: '线索6', day: DAY })
  assert.equal(over.ok, false)
  assert.equal(over.reason, 'full')
  assert.equal(over.state.items.length, 5, '被挡的不许偷偷写进去')
  assert.equal(over.left, 0)
})

test('C4 换天了就换一条空队列：昨天没核实的带不到今天', () => {
  const r = add(empty(DAY), { text: '他又熬夜', day: DAY })
  const next = ofDay(r.state, DAY + 86_400_000)
  assert.equal(next.items.length, 0)
  assert.equal(leftOf(r.state, DAY + 86_400_000), CLUE_MAX_PER_DAY)
})

test('C5 空话不收', () => {
  assert.equal(add(empty(DAY), { text: '   ', day: DAY }).ok, false)
  assert.equal(add(empty(DAY), { text: '', day: DAY }).reason, 'empty')
  assert.equal(add(empty(DAY), {}, DAY).ok, false)
})

test('C6 一条线索太长就截断，不整条丢', () => {
  const r = add(empty(DAY), { text: '他'.repeat(500), day: DAY })
  assert.equal(r.ok, true)
  assert.equal(r.state.items[0].text.length, 200)
})

test('C7 脏状态不崩：items 里有 null / 非字符串', () => {
  const dirty = { day: DAY, items: [null, { text: 42 }, { text: '他吃了泡面', at: 7 }, 'xxx'] }
  const s = ofDay(dirty, DAY)
  assert.equal(s.items.length, 1)
  assert.equal(s.items[0].text, '他吃了泡面')
})

test('C8 给姐姐看的那几行：有线索才出标题，标题说的是「只报现象」', () => {
  assert.deepEqual(lines(empty(DAY), DAY), [])
  const r = add(empty(DAY), { text: '他又在凌晨刷 B 站', day: DAY })
  const l = lines(r.state, DAY)
  assert.ok(l[0].includes('只报现象'), l[0])
  assert.ok(l[1].includes('他又在凌晨刷 B 站'), l[1])
  assert.ok(l.some((x) => x.startsWith('- ')), '每条一行')
})

test('C9 add 不改传进来的状态（纯函数）', () => {
  const base = add(empty(DAY), { text: '他今天没吃饭', day: DAY }).state
  const before = JSON.stringify(base)
  add(base, { text: '他又熬夜', day: DAY })
  assert.equal(JSON.stringify(base), before)
})
