// 2026-11-18 会话换班。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildHandoff, handoffLive, recentDialog, rotationStatus } from '../lib/rotate.js'

const now = Date.parse('2026-11-18T08:00:00Z')

test('轮数：只数这个对话绑定以来的；150 轮或 7 天（且聊过 20 轮）就该换班', () => {
  const entries = []
  for (let i = 0; i < 160; i++) entries.push({ state: i % 2 ? 'generated' : 'human', sessionId: 's1', at: now - (160 - i) * 60_000 })
  entries.push({ state: 'generated', sessionId: 's2', at: now })
  const st = rotationStatus(entries, { sessionId: 's1', now })
  assert.equal(st.turns, 160)
  assert.equal(st.due, true)
  const fresh = rotationStatus(entries, { sessionId: 's1', boundAt: now - 30 * 60_000, now })
  assert.equal(fresh.due, false, '刚换过：从绑定那一刻起算')
  const old = rotationStatus([...Array(25)].map((_, i) => ({ state: 'human', sessionId: 's3', at: now - 8 * 86_400_000 + i })), { sessionId: 's3', now })
  assert.equal(old.due, true, '用了 8 天')
  assert.equal(rotationStatus([], { sessionId: '' }).due, false)
})

test('交接：带答应过的、没聊完的、最后几句原话；有长度上限', () => {
  const dialog = recentDialog([
    { state: 'say-text', at: 1, text: '我明天一定把简历改完' },
    { state: 'generated', sessionId: 's1', at: 2, text: '行，你说的，明早九点我来验' },
    { state: 'generated', sessionId: 'other', at: 3, text: '别的对话' },
  ], { sessionId: 's1' })
  assert.equal(dialog.length, 2)
  const md = buildHandoff({ roleName: '鱼丸', dialog, commitments: ['明早九点前改完简历'], topics: ['芝麻生病了'], verdictLine: '昨天的判决：空转', now })
  assert.match(md, /鱼丸 的交接/)
  assert.match(md, /明早九点前改完简历/)
  assert.match(md, /芝麻生病了/)
  assert.match(md, /他：我明天一定把简历改完/)
  assert.ok(buildHandoff({ dialog: Array(200).fill({ who: '他', text: 'x'.repeat(200) }) }).length <= 2600)
})

test('交接只在新对话头 3 天 / 30 轮里带', () => {
  assert.equal(handoffLive({ text: 'x', at: now }, { now: now + 86_400_000, turnsSince: 3 }), true)
  assert.equal(handoffLive({ text: 'x', at: now }, { now: now + 4 * 86_400_000, turnsSince: 3 }), false)
  assert.equal(handoffLive({ text: 'x', at: now }, { now, turnsSince: 31 }), false)
})
