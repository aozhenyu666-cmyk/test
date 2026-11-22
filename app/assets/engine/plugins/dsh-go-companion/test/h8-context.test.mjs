// H8（2026-11-19）：原话落盘 + 上下文单块上限。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BLOCK_CAPS, capBlockText, fitBudget, DEFAULT_BUDGET } from '../lib/budget.js'
import { buildHandoff, recentDialog } from '../lib/rotate.js'

test('单块上限：按整行保留；首行超长才截；约束层不受影响', () => {
  assert.equal(capBlockText('a\nbb\nccc', 4), 'a\nbb')
  assert.equal(capBlockText('x'.repeat(50), 10), 'x'.repeat(9) + '…')
  assert.equal(capBlockText('短', 100), '短')
  const big = Array.from({ length: 80 }, (_, i) => '第' + i + '行' + '字'.repeat(20)).join('\n')
  const r = fitBudget([
    { id: 'persona', layer: 'constraint', priority: 100, text: '人设' },
    { id: 'handoff', layer: 'content', priority: 80, text: big },
    { id: 'reflect', layer: 'content', priority: 70, text: '要紧：A' },
  ])
  assert.ok(r.kept.includes('handoff'), '交接块被压到上限内，不再整块丢掉')
  assert.ok(r.kept.includes('reflect'), '同层后面的块也装得下')
  const handoffText = r.text.split('〔鱼·注入开始〕').find((x) => x.includes('第0行'))
  assert.ok(handoffText.length <= BLOCK_CAPS.handoff + 20)
  assert.ok(r.chars <= DEFAULT_BUDGET.total)
})

test('交接：原话来自账本里的 human（App 里打的字）和 say-text（浮层），去重；被截时先保住答应过的和原话', () => {
  const entries = [
    { state: 'human', sessionId: 's1', at: 1, text: '我明天九点前改完简历' },
    { state: 'generated', sessionId: 's1', at: 2, text: '行，九点我来验' },
    { state: 'say-text', at: 3, text: '今天好累' },
    { state: 'human', sessionId: 'other', at: 4, text: '别的对话' },
  ]
  const dialog = recentDialog(entries, { sessionId: 's1' })
  assert.deepEqual(dialog.map((d) => d.text), ['我明天九点前改完简历', '行，九点我来验', '今天好累'])
  const md = buildHandoff({ dialog, humanRecent: [{ text: '今天好累' }], commitments: ['九点前改完简历'], facts: Array(6).fill('常驻事实'.repeat(10)) })
  assert.equal(md.split('他：今天好累').length - 1, 1, '同一句不重复')
  const capped = capBlockText(md, BLOCK_CAPS.handoff)
  assert.match(capped, /九点前改完简历/)
  assert.match(capped, /他：我明天九点前改完简历/)
})
