import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fitBudget, DEFAULT_BUDGET, LAYERS, INJECT_OPEN, INJECT_CLOSE, stripInjection } from '../lib/budget.js'

const b = (id, layer, priority, n) => ({ id, layer, priority, text: 'x'.repeat(n) })

test('B14 默认预算：总量 2400，三层 2100/1000/500', () => {
  assert.equal(DEFAULT_BUDGET.total, 2400)
  // 约束层默认 2100 而不是规格写的 900：底稿 826/1004 + 能力 354/390 + 议程 333 已经到 1513/1727，
  // A1 又加了立场块（上限 200），姐姐那边只剩 73 字余量会每轮抛错（约束层永不裁、超了抛）。
  // 理由写在 lib/budget.js 的注释里；总量 2400 这个验收硬指标没动。
  assert.deepEqual(DEFAULT_BUDGET.perLayer, { constraint: 2100, state: 1000, content: 500 })
  assert.deepEqual(LAYERS, ['constraint', 'state', 'content'])
})

test('B1 约束层永不裁：三个约束块全带', () => {
  const out = fitBudget([b('c1', 'constraint', 100, 200), b('c2', 'constraint', 90, 200), b('c3', 'constraint', 80, 200)])
  assert.deepEqual(out.kept, ['c1', 'c2', 'c3'])
  assert.equal(out.dropped.length, 0)
  assert.equal(out.chars, 200 * 3 + 2) // 三块之间两个换行
})

test('B2 约束层超 perLayer.constraint 就抛错，不悄悄截断', () => {
  const blocks = [b('c1', 'constraint', 100, 1400), b('c2', 'constraint', 90, 800)]
  assert.throws(() => fitBudget(blocks), (error) => {
    assert.equal(error.reason, 'constraint-over-budget')
    assert.equal(error.chars, 2200)
    assert.equal(error.budget, 2100)
    assert.deepEqual(error.blocks, ['c1', 'c2'])
    return true
  })
})

test('B3 约束层刚好装满不抛错（边界 2100）', () => {
  const out = fitBudget([b('c1', 'constraint', 100, 2100)])
  assert.deepEqual(out.kept, ['c1'])
})

test('B4 约束层 2101 就抛错（边界 +1）', () => {
  assert.throws(() => fitBudget([b('c1', 'constraint', 100, 2101)]), /约束层超预算/)
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
  // 状态块落地时包首尾标记，标记本身也算预算：900 + 标记 + 那 1 个字 = 仍超不过 1000 上限
  const out = fitBudget([b('s1', 'state', 90, 900), b('s2', 'state', 80, 200)])
  assert.deepEqual(out.kept, ['s1'])
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

test('B12 chars 等于实际文本长度，不是各块相加（含换行与注入标记）', () => {
  const out = fitBudget([b('c1', 'constraint', 100, 10), b('s1', 'state', 50, 20)])
  assert.equal(out.chars, out.text.length)
  assert.equal(out.chars, 10 + 1 + INJECT_OPEN.length + 1 + 20 + 1 + INJECT_CLOSE.length)
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

test('B16 文本按换行拼接：约束块不带标记，注入块首尾都有标记', () => {
  const out = fitBudget([
    { id: 'a', layer: 'constraint', priority: 1, text: '第一块' },
    { id: 'b', layer: 'state', priority: 1, text: '第二块' },
  ])
  assert.equal(out.text, '第一块\n' + INJECT_OPEN + '\n第二块\n' + INJECT_CLOSE)
  // 剥掉注入块之后只剩约束层
  assert.equal(stripInjection(out.text), '第一块')
})
