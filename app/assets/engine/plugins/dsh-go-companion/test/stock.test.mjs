import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isStock, similarity, STOCK_TEMPLATES } from '../lib/stock.js'

test('模板原句就是套话', () => {
  for (const t of STOCK_TEMPLATES) assert.equal(isStock(t), true, t)
})

test('模板加语气词、标点仍然算', () => {
  assert.equal(isStock('你在做什么呢？'), true)
  assert.equal(isStock('下一步是什么呀'), true)
  assert.equal(isStock('依据是什么？'), true)
  assert.equal(isStock('你可以的！'), true)
})

test('有自己判断的句子不算套话', () => {
  const ok = [
    '你在 B 站已经 20 分钟了，我猜你在躲下午那份申论。',
    '你昨天说行测靠刷题量，我不同意，量不解决审题。',
    '现在说出今天最该做的一件事，就一件。',
    '上周你说想换工作，后来怎么样了？我觉得你没动。',
  ]
  for (const t of ok) assert.equal(isStock(t), false, t)
})

test('太短的回复不会被包含判据误杀', () => {
  assert.equal(isStock('你'), false)
  assert.equal(isStock('好的'), false)
  assert.equal(isStock('嗯'), false)
})

test('相似度：整句相等 1，无关接近 0', () => {
  assert.equal(similarity('加油', '加油'), 1)
  assert.equal(similarity('你在做什么', '你在做什么'), 1)
  assert.ok(similarity('面试还剩两天', '你在做什么') < 0.2)
})
