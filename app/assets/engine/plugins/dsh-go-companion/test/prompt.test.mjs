import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildPrompt } from '../lib/prompt.js'

test('prompt：带话题、类型规则和身份，不要求工具', () => {
  const text = buildPrompt({
    owner: '主人',
    self: '大肥鱼',
    topic: { kind: 'unfinished', text: '面试结果（她说下周出结果）' },
    facts: ['她对芒果过敏', '周三要交稿'],
    episodes: ['一起看了《芙莉莲》'],
  })
  assert.match(text, /大肥鱼/)
  assert.match(text, /主人/)
  assert.match(text, /面试结果/)
  assert.match(text, /愿不愿意接着聊/)
  assert.match(text, /不要调用任何工具/)
  assert.match(text, /她对芒果过敏/)
  assert.match(text, /芙莉莲/)
})

test('prompt：分享型不索取回复', () => {
  const text = buildPrompt({
    topic: { kind: 'share', text: '今天看到一只很像鲸鱼的云' },
    facts: [],
    episodes: [],
  })
  assert.match(text, /分享|吐槽/)
  assert.match(text, /不要求 TA 回复/)
})

test('prompt：背景最多 5 条，不把整库塞进去', () => {
  const facts = Array.from({ length: 12 }, (_, i) => '事实' + i)
  const text = buildPrompt({ topic: { kind: 'share', text: 'x' }, facts, episodes: [] })
  const lines = text.split('\n').filter((l) => l.startsWith('- 事实'))
  assert.equal(lines.length, 5)
})

test('prompt：明确禁止“今天有什么计划”式问候', () => {
  const text = buildPrompt({ topic: { kind: 'share', text: 'x' }, facts: [], episodes: [] })
  assert.match(text, /不要问“今天有什么计划”/)
})
