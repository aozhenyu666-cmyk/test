import { test } from 'node:test'
import assert from 'node:assert/strict'
import { classifyReply, isSubstantive } from '../lib/classify.js'

test('实质回答：有信息、有依据、在推进', () => {
  assert.equal(classifyReply('因为图形是对称的，所以选 D'), 'substantive')
  assert.equal(classifyReply('我刚看完第三章，明天把笔记整理一下'), 'substantive')
  assert.equal(classifyReply('面试约在周五下午两点，地点还没定'), 'substantive')
  assert.equal(isSubstantive('第 3 题排除 C，因为左右对称'), true)
})

test('简短回应：嗯/随便/还行', () => {
  for (const t of ['嗯', '哦哦', '好', '行', '随便', '还行', '哈哈', '……', 'ok']) {
    assert.equal(classifyReply(t), 'brief', t)
  }
})

test('回避：把话题带走', () => {
  assert.equal(classifyReply('换个话题吧'), 'avoid')
  assert.equal(classifyReply('以后再说'), 'avoid')
  assert.equal(classifyReply('不知道'), 'avoid')
})

test('拒绝：明确不让继续', () => {
  assert.equal(classifyReply('不想聊这个'), 'refuse')
  assert.equal(classifyReply('别问了'), 'refuse')
  assert.equal(classifyReply('烦不烦，别管我'), 'refuse')
})

test('拒绝只认“冲着这次聊天说的”，说别的事不算拒绝', () => {
  assert.equal(classifyReply('今天停止刷视频了，准备做第三题'), 'substantive')
  assert.equal(classifyReply('做够了就去背单词'), 'brief')
})

test('带自己去向的“别管”不是拒绝', () => {
  // 只有 13 字，长度上限拦不住，靠 SELF_ACTION 放过。
  assert.equal(classifyReply('别管这道题了，我先做第三题'), 'substantive')
  // 真拒绝不汇报下一步，仍然要判成拒绝。
  assert.equal(classifyReply('别管我'), 'refuse')
})

test('空消息按简短处理，不崩溃', () => {
  assert.equal(classifyReply(''), 'brief')
  assert.equal(classifyReply(null), 'brief')
})
