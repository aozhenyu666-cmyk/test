import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fitBudget, DEFAULT_BUDGET, LAYERS } from '../lib/budget.js'

const b = (id, layer, priority, n) => ({ id, layer, priority, text: 'x'.repeat(n) })

test('B14 默认预算：总量 2400，三层 900/1000/500', () => {
  assert.equal(DEFAULT_BUDGET.total, 2400)
  assert.deepEqual(DEFAULT_BUDGET.perLayer, { constraint: 900, state: 1000, content: 500 })
  assert.deepEqual(LAYERS, ['constraint', 'state', 'content'])
})

test('B1 约束层永不裁：三个约束块全带', () => {
  const out = fitBudget([b('c1', 'constraint', 100, 200), b('c2', 'constraint', 90, 200), b('c3', 'constraint', 80, 200)])
  assert.deepEqual(out.kept, ['c1', 'c2', 'c3'])
  assert.equal(out.dropped.length, 0)
  assert.equal(out.chars, 200 * 3 + 2) // 三块之间两个换行
})

test('B2 约束层超 perLayer.constraint 就抛错，不悄悄截断', () => {
  const blocks = [b('c1', 'constraint', 100, 600), b('c2', 'constraint', 90, 400)]
  assert.throws(() => fitBudget(blocks), (error) => {
    assert.equal(error.reason, 'constraint-over-budget')
    assert.equal(error.chars, 1000)
    assert.equal(error.budget, 900)
    assert.deepEqual(error.blocks, ['c1', 'c2'])
    return true
  })
})

test('B3 约束层刚好装满不抛错（边界 900）', () => {
  const out = fitBudget([b('c1', 'constraint', 100, 900)])
  assert.deepEqual(out.kept, ['c1'])
})

test('B4 约束层 901 就抛错（边界 +1）', () => {
  assert.throws(() => fitBudget([b('c1', 'constraint', 100, 901)]), /约束层超预算/)
})

test('B5 状态层按 priority 降序装，装不下的整块丢', () => {
  const out = fitBudget([
    b('low', 'state', 10, 600),
    b('high', 'state', 90, 600),
    b('mid', 'state', 50, 600),
  ])
  // 状态层上限 1000：只有 high 进得去，mid/low 整块丢
  assert.deepEqual(out.kept, ['high'])
  assert.deepEqual(out.dropped, [{ id: 'mid', reason: 'layer' }, { id: 'low', reason: 'layer' }])
})

test('B6 状态层不做半截截断：差一个字也整块丢', () => {
  const out = fitBudget([b('s1', 'state', 90, 1000), b('s2', 'state', 80, 1)])
  assert.deepEqual(out.kept, ['s1'])
  // 状态层已经 1000 到顶，s2 差的就是那 1 个字：整块丢，不塞半截
  assert.deepEqual(out.dropped, [{ id: 's2', reason: 'layer' }])
})

test('B7 内容层最后装：总量被前面吃完时一条都不带', () => {
  const out = fitBudget([
    { id: 's1', layer: 'state', priority: 90, text: 'x'.repeat(900) },
    { id: 'k1', layer: 'content', priority: 10, text: 'y'.repeat(400) },
  ], { total: 1200 })
  assert.deepEqual(out.kept, ['s1'])
  assert.deepEqual(out.dropped, [{ id: 'k1', reason: 'total' }])
})

test('B8 内容层自己超 500 就连一条都装不下', () => {
  const out = fitBudget([{ id: 'k1', layer: 'content', priority: 10, text: 'y'.repeat(501) }])
  assert.deepEqual(out.kept, [])
  assert.deepEqual(out.dropped, [{ id: 'k1', reason: 'layer' }])
})

test('B9 内容层按优先级抢剩下的预算', () => {
  const out = fitBudget([
    { id: 'k-low', layer: 'content', priority: 1, text: 'y'.repeat(480) },
    { id: 'k-high', layer: 'content', priority: 99, text: 'y'.repeat(480) },
  ])
  assert.deepEqual(out.kept, ['k-high'])
  assert.deepEqual(out.dropped, [{ id: 'k-low', reason: 'layer' }])
})

test('B10 dropped 只说真话：装进去的块不出现在 dropped 里', () => {
  const out = fitBudget([b('c1', 'constraint', 100, 300), b('s1', 'state', 50, 300), b('k1', 'content', 1, 300)])
  const ids = new Set(out.kept)
  for (const d of out.dropped) assert.equal(ids.has(d.id), false)
  assert.deepEqual(out.kept, ['c1', 's1', 'k1'])
})

test('B11 总量硬上限：perLayer 都宽松时总量仍不超', () => {
  const out = fitBudget(
    [b('c1', 'constraint', 100, 500), b('s1', 'state', 90, 400), b('s2', 'state', 80, 400), b('k1', 'content', 1, 400)],
    { total: 1000, perLayer: { constraint: 900, state: 1000, content: 500 } },
  )
  assert.equal(out.chars <= 1000, true)
  assert.deepEqual(out.kept, ['c1', 's1'])
  assert.deepEqual(out.dropped, [{ id: 's2', reason: 'total' }, { id: 'k1', reason: 'total' }])
})

test('B12 chars 等于实际文本长度，不是各块相加（含换行）', () => {
  const out = fitBudget([b('c1', 'constraint', 100, 10), b('s1', 'state', 50, 20)])
  assert.equal(out.chars, out.text.length)
  assert.equal(out.chars, 10 + 20 + 1)
})

test('B13 空输入与脏输入都不炸', () => {
  assert.deepEqual(fitBudget([]), { text: '', kept: [], dropped: [], chars: 0 })
  assert.deepEqual(fitBudget(undefined), { text: '', kept: [], dropped: [], chars: 0 })
  const dirty = [null, undefined, {}, { id: 'n', layer: 'nope', text: 'x' }, { id: 'e', layer: 'state', text: '' }]
  assert.deepEqual(fitBudget(dirty), { text: '', kept: [], dropped: [], chars: 0 })
})

test('B15 同优先级的块保持传入顺序（不打乱调用方的语义）', () => {
  const out = fitBudget([b('a', 'state', 10, 10), b('b', 'state', 10, 10), b('c', 'state', 10, 10)])
  assert.deepEqual(out.kept, ['a', 'b', 'c'])
})

test('B16 文本按换行拼接，块之间不多不少一个换行', () => {
  const out = fitBudget([
    { id: 'a', layer: 'constraint', priority: 1, text: '第一块' },
    { id: 'b', layer: 'state', priority: 1, text: '第二块' },
  ])
  assert.equal(out.text, '第一块\n第二块')
})
