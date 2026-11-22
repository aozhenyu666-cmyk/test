// 2026-11-18 当下任务卡：只许白名单组件和动作。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cardLive, cleanCard, takeoverCard, toggleStep, CARD_TTL_MS } from '../lib/card.js'

const now = Date.parse('2026-11-18T06:00:00Z')
const appOk = (p) => ['com.hpbr.bosszhipin', 'com.android.chrome'].includes(p)

test('卡：字段截断、步骤 ≤6、倒计时 ≤180 分钟；按钮只认白名单动作，打开 App 只许工作 / 双用途', () => {
  const r = cleanCard({
    title: '简历投递局', goal: '投出 3 家', minutes: 999,
    steps: ['打开 BOSS 直聘', '筛上海后端', '改简历第二段', '投 3 家', '截图', '交验收', '多余的'],
    buttons: [{ action: 'open_app', app: 'com.hpbr.bosszhipin', label: '打开 BOSS' }, { action: 'open_app', app: 'com.ss.android.ugc.aweme' }, { action: 'rm_rf' }],
  }, { now, by: 'strategist', appOk })
  assert.equal(r.ok, true)
  assert.equal(r.card.steps.length, 6)
  assert.equal(r.card.timerUntil, now + 180 * 60_000)
  assert.deepEqual(r.card.buttons, [{ label: '打开 BOSS', action: 'open_app', app: 'com.hpbr.bosszhipin' }])
  assert.equal(r.problems.length, 2)
  assert.equal(cleanCard({ steps: ['x'] }).ok, false, '没标题不收')
})

test('勾步骤、过期', () => {
  const { card } = cleanCard({ title: 't', steps: ['a', 'b'] }, { now })
  assert.equal(toggleStep(card, 1).steps[1].done, true)
  assert.equal(cardLive(card, now + 1000), true)
  assert.equal(cardLive(card, now + CARD_TTL_MS + 1), false)
})

test('接管卡由代码生成：回位动作 + 开下一局 / 连 Gemini', () => {
  const c = takeoverCard({ why: '陷住了', action: '开「行测」这一局', now })
  assert.equal(c.title, '回位动作')
  assert.deepEqual(c.buttons.map((b) => b.action), ['start_next_bout', 'connect_gemini'])
})
