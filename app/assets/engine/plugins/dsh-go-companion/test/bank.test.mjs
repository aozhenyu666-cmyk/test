import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BANK_VERSION, bankStats, cleanQuestion, parseBank, questionOf, splitAnalysis } from '../lib/bank.js'

const Q = {
  题干: '某厂今年产量比去年增长 20%，去年为 500 吨，今年是多少吨？',
  选项: ['A. 520 吨', 'B. 600 吨'],
  答案: 'B',
  解析: '去年产量是基数。\n增长 20% 就是乘以 1.2。\n所以 500 × 1.2 = 600 吨。',
  定位句: '去年为 500 吨',
  题型: '数量关系',
  年份: 2026,
  来源: '行测真题',
}

test('洗一道完整的题：解析拆成有序步骤', () => {
  const r = cleanQuestion(Q, 0)
  assert.equal(r.ok, true, JSON.stringify(r.errors))
  assert.equal(r.question.answer, 'B')
  assert.deepEqual(r.question.analysis, ['去年产量是基数。', '增长 20% 就是乘以 1.2。', '所以 500 × 1.2 = 600 吨。'])
  assert.equal(r.question.locate, '去年为 500 吨')
  assert.equal(r.question.year, 2026)
  assert.equal(r.question.id, 'q1', '没给 id 就按位置编')
})

test('没答案的题不许进库：不许让模型临场补', () => {
  const r = cleanQuestion({ ...Q, 答案: '' })
  assert.equal(r.ok, false)
  assert.equal(r.errors[0].code, 'no-answer')
})

test('没解析的题不许进库：打分要靠参考推理链', () => {
  const r = cleanQuestion({ ...Q, 解析: '' })
  assert.equal(r.ok, false)
  assert.equal(r.errors[0].code, 'no-analysis')
})

test('没题干的题不许进库', () => {
  const r = cleanQuestion({ ...Q, 题干: '   ' })
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.code === 'no-stem'))
})

test('定位句不在题干里：不废掉整道题，只是这一招不可用', () => {
  const r = cleanQuestion({ ...Q, 定位句: '这句话题干里根本没有' })
  assert.equal(r.ok, true, JSON.stringify(r.errors))
  assert.equal(r.question.locate, '')
  assert.equal(r.warnings.length, 1)
  assert.match(r.warnings[0], /定位句不在题干里/)
})

test('选项至少两个；不认识的字段是错误而不是被忽略', () => {
  const bad = cleanQuestion({ ...Q, 选项: ['只有一个'] })
  assert.equal(bad.ok, false)
  assert.ok(bad.errors.some((e) => e.code === 'bad-options'))
  const typo = cleanQuestion({ ...Q, 定位句2: '手滑写错了字段' })
  assert.equal(typo.ok, false)
  assert.ok(typo.errors.some((e) => e.code === 'unknown-key'))
})

test('解析整段没换行也能拆：按句号类标点切', () => {
  assert.deepEqual(splitAnalysis('先看基数。再乘增长率。'), ['先看基数。', '再乘增长率。'])
  assert.deepEqual(splitAnalysis(['一行一步', '第二行']), ['一行一步', '第二行'])
})

test('解析一份题库：JSON 文本也能进', () => {
  const r = parseBank(JSON.stringify({ 题库版本: 1, id: 'xingce', 名称: '行测', 题目: [Q] }))
  assert.equal(r.ok, true, JSON.stringify(r.errors))
  assert.equal(r.bank.id, 'xingce')
  assert.equal(r.bank.questions.length, 1)
  assert.equal(r.bank.version, BANK_VERSION)
})

test('题库版本不对就整份退回', () => {
  const r = parseBank({ 题库版本: 99, 题目: [Q] })
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.code === 'bad-version'))
})

test('题号重复整份退回：不能悄悄盖掉一道题', () => {
  const r = parseBank({ 题目: [{ ...Q, id: 'a' }, { ...Q, id: 'a' }] })
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.code === 'dup-id'))
})

test('空题库和空文本都退回，不假装导入成功', () => {
  assert.equal(parseBank({ 题目: [] }).ok, false)
  assert.equal(parseBank('   ').ok, false)
  assert.equal(parseBank('不是 json').ok, false)
  assert.equal(parseBank([Q]).ok, true, '裸数组也收')
})

test('统计按题型和年份数，取题按 id', () => {
  const r = parseBank({ 题目: [Q, { ...Q, id: 'q2', 题型: '判断推理', 年份: 2025 }] })
  assert.equal(r.ok, true)
  assert.deepEqual(bankStats(r.bank), {
    total: 2,
    byType: { 数量关系: 1, 判断推理: 1 },
    byYear: { 2026: 1, 2025: 1 },
  })
  assert.equal(questionOf(r.bank, 'q2').type, '判断推理')
  assert.equal(questionOf(r.bank, '没有这个'), null)
})
