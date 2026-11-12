import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  INTENSITY, JUDGE, MIN_QUOTE, MOVES, RULE_RETEST_MS, archiveRule, codeMajority, faultStep,
  judgeAnswer, missingFor, movesOf, ready, route, rulesDue, scoreSheet, usableMoves, validateThinking,
} from '../lib/thinking.js'

const DAY = 86_400_000
const NOW = Date.parse('2026-11-12T09:00:00+08:00')

const Q = {
  id: 'q1',
  stem: '某厂今年产量比去年增长 20%，去年为 500 吨，今年是多少吨？',
  options: ['A. 520 吨', 'B. 600 吨'],
  answer: 'B',
  analysis: ['去年产量是基数。', '增长 20% 就是乘以 1.2。', '所以 500 × 1.2 = 600 吨。'],
  locate: '去年为 500 吨',
  type: '数量关系',
}

const judge = (move, answer, extra = {}) => judgeAnswer({ move, question: Q, answer, ...extra })

// ---- 招式表与强度 ----------------------------------------------------------------

test('六招写死在引擎里，只有复述交给模型判', () => {
  assert.deepEqual([...MOVES], ['先答后看', '定位', '复述', '续接', '找茬', '一条规则'])
  assert.deepEqual(Object.values(JUDGE).filter((x) => x === 'model'), ['model'])
  assert.equal(JUDGE['复述'], 'model')
})

test('强度按施工书叠：低两招、中四招、高再加找茬', () => {
  assert.deepEqual(movesOf('低').moves, ['先答后看', '定位'])
  assert.deepEqual(movesOf('中').moves, ['先答后看', '定位', '复述', '续接'])
  assert.deepEqual(movesOf('高').moves, ['先答后看', '定位', '复述', '续接', '找茬'])
  assert.equal(movesOf('很高').ok, false, '认不出的强度不猜一个默认的')
  assert.deepEqual(movesOf('很高').list, Object.keys(INTENSITY))
})

test('代码判的招式必须占多数', () => {
  assert.equal(codeMajority(INTENSITY.低).ok, true)
  assert.equal(codeMajority(INTENSITY.高).ok, true)
  const bad = codeMajority(['复述'])
  assert.equal(bad.ok, false)
  assert.equal(bad.code, 0)
  assert.equal(bad.model, 1)
  assert.equal(codeMajority(['复述', '没有这一招']).ok, false)
})

// ---- 依据前置检查 ----------------------------------------------------------------

test('缺依据的招对这道题不可用，不带着缺口上场', () => {
  assert.deepEqual(missingFor(Q, '先答后看'), [])
  assert.deepEqual(missingFor(Q, '定位'), [])
  const noLocate = { ...Q, locate: '' }
  assert.deepEqual(missingFor(noLocate, '定位'), ['定位句'])
  assert.deepEqual(missingFor(Q, '续接'), ['他的记录'])
  assert.deepEqual(missingFor(Q, '续接', { record: '上一步算的是去年' }), [])
  assert.deepEqual(missingFor(Q, '找茬'), ['埋的错'])
  assert.deepEqual(missingFor(Q, '一条规则'), ['一道答错的题'])
  assert.deepEqual(missingFor(Q, '一条规则', { wrong: true }), [])
  assert.deepEqual(missingFor({ ...Q, analysis: [] }, '复述'), ['解析'])
  assert.match(missingFor(Q, '没这一招')[0], /招式不存在/)
})

test('usableMoves 只列依据齐全的招：一条规则要先有一道答错的题', () => {
  assert.deepEqual(usableMoves(Q), ['先答后看', '定位', '复述'])
  assert.deepEqual(usableMoves(Q, { wrong: true }), ['先答后看', '定位', '复述', '一条规则'])
  assert.equal(ready(Q, '定位').ok, true)
  assert.equal(ready({ ...Q, locate: '' }, '定位').ok, false)
})

test('找茬的埋错位置从解析里认出来', () => {
  assert.equal(faultStep(Q), null)
  const marked = { ...Q, analysis: ['去年产量是基数。', '增长 20% 就是乘以 1.2[错]。', '所以是 600 吨。'] }
  assert.equal(faultStep(marked), 2)
})

// ---- 先答后看 -------------------------------------------------------------------

test('先答后看：对上答案算过', () => {
  assert.equal(judge('先答后看', 'B').verdict, 'pass')
  assert.equal(judge('先答后看', '选B吧').verdict, 'pass', '选择题写「选B」也算')
})

test('先答后看：答「1」不能算对上「12」', () => {
  const num = { ...Q, answer: '12' }
  assert.equal(judgeAnswer({ move: '先答后看', question: num, answer: '1' }).verdict, 'wrong')
  assert.equal(judgeAnswer({ move: '先答后看', question: num, answer: '12' }).verdict, 'pass')
})

test('先答后看：答案不一致是答错，不是敷衍', () => {
  const r = judge('先答后看', 'A')
  assert.equal(r.verdict, 'wrong')
  assert.equal(r.judgedBy, 'code')
})

test('没作答是敷衍，不是答错——两条路必须分开', () => {
  assert.equal(judge('先答后看', '').verdict, 'perfunctory')
  assert.equal(judge('先答后看', '嗯嗯').verdict, 'perfunctory')
  const filler = judge('先答后看', '就是那个')
  assert.equal(filler.verdict, 'perfunctory')
  assert.equal(filler.why, '填充词太多')
})

// ---- 定位 -----------------------------------------------------------------------

test('定位：引出那句原文算过', () => {
  const r = judge('定位', '去年为 500 吨')
  assert.equal(r.verdict, 'pass')
  assert.equal(r.judgedBy, 'code')
})

test('定位：引错了是答错', () => {
  assert.equal(judge('定位', '今年产量比去年增长').verdict, 'wrong')
})

test('定位：引得太短直接算答错，防止引一个「的」蒙过去', () => {
  assert.ok('去年为'.length < MIN_QUOTE)
  const r = judge('定位', '去年为')
  assert.equal(r.verdict, 'wrong')
  assert.match(r.why, /引得太短/)
})

test('定位：没有定位句的题这一招不该用', () => {
  const r = judgeAnswer({ move: '定位', question: { ...Q, locate: '' }, answer: '去年为 500 吨' })
  assert.equal(r.verdict, 'perfunctory')
  assert.match(r.why, /没有定位句/)
})

// ---- 复述 -----------------------------------------------------------------------

test('复述：说清了交给模型判，代码给的是 pending', () => {
  const r = judge('复述', '这题问的是今年的产量')
  assert.equal(r.verdict, 'pending')
  assert.equal(r.judgedBy, 'model')
})

test('复述：太长或者没说题目里的东西，代码直接判错，不用等模型', () => {
  assert.equal(judge('复述', '这题问的是今年的产量'.repeat(8)).verdict, 'wrong')
  assert.equal(judge('复述', '唔').verdict, 'wrong', '短不等于敷衍，只是没说到题目里的东西')
  assert.equal(judge('复述', '').verdict, 'perfunctory')
})

// ---- 续接 -----------------------------------------------------------------------

test('续接：想起了上一步并说出下一步才算过', () => {
  const record = '哈希表和红黑树是两回事'
  const r = judge('续接', '哈希表，下一步算它的位置', { record })
  assert.equal(r.verdict, 'pass')
  assert.equal(r.judgedBy, 'code')
})

test('续接：只想起上一步、没说下一步，还是答错', () => {
  const r = judge('续接', '哈希表', { record: '哈希表和红黑树是两回事' })
  assert.equal(r.verdict, 'wrong')
  assert.match(r.why, /没说出下一步/)
})

test('续接：什么都没想起来是答错', () => {
  assert.equal(judge('续接', '不知道了，接着学', { record: '哈希表和红黑树是两回事' }).verdict, 'wrong')
})

test('续接：没有他的记录就判不了，宁可说判不了也不瞎猜', () => {
  const r = judge('续接', '哈希表，下一步算它', { record: '' })
  assert.equal(r.verdict, 'perfunctory')
  assert.match(r.why, /判不了/)
})

// ---- 找茬 -----------------------------------------------------------------------

const FAULTY = { ...Q, analysis: ['去年产量是基数。', '增长 20% 就是乘以 1.2[错]。', '所以是 600 吨。'] }

test('找茬：说出第几步错了算过', () => {
  assert.equal(judgeAnswer({ move: '找茬', question: FAULTY, answer: '第2步' }).verdict, 'pass')
  assert.equal(judgeAnswer({ move: '找茬', question: FAULTY, answer: '2' }).verdict, 'pass')
})

test('找茬：引出埋错的那句也算过', () => {
  assert.equal(judgeAnswer({ move: '找茬', question: FAULTY, answer: '增长 20% 就是乘以 1.2' }).verdict, 'pass')
})

test('找茬：指错了步是答错', () => {
  assert.equal(judgeAnswer({ move: '找茬', question: FAULTY, answer: '第3步' }).verdict, 'wrong')
})

test('找茬：题目没埋错就判不了，不许临时编一个错', () => {
  const r = judgeAnswer({ move: '找茬', question: Q, answer: '第1步' })
  assert.equal(r.verdict, 'perfunctory')
  assert.match(r.why, /没有埋错/)
})

// ---- 一条规则 -------------------------------------------------------------------

test('一条规则：写下来算过，太长不算一条', () => {
  assert.equal(judge('一条规则', '比例题先找基数再乘增长率').verdict, 'pass')
  assert.equal(judge('一条规则', '对').verdict, 'wrong')
  assert.equal(judge('一条规则', '这条规则'.padEnd(60, '长')).verdict, 'wrong')
})

test('一条规则：存档后 1 天、3 天各复考一次', () => {
  const r = archiveRule({ text: '比例题先找基数', questionId: 'q1', now: NOW })
  assert.equal(r.ok, true)
  assert.deepEqual(r.rule.due, [NOW + DAY, NOW + 3 * DAY])
  assert.deepEqual(r.rule.due, RULE_RETEST_MS.map((ms) => NOW + ms))
  assert.equal(archiveRule({ text: '  ' }).ok, false)
})

test('一条规则：到点的才考，考过的同一档不再考', () => {
  const r = archiveRule({ text: '比例题先找基数', questionId: 'q1', now: NOW })
  assert.equal(rulesDue([r.rule], NOW).length, 0)
  assert.equal(rulesDue([r.rule], NOW + DAY).length, 1)
  assert.equal(rulesDue([r.rule], NOW + DAY).length, 1, '同一时间点只考一次是调用方去重，这里如实报')
  const done = { ...r.rule, passed: [0] }
  assert.deepEqual(rulesDue([done], NOW + DAY), [])
  assert.equal(rulesDue([done], NOW + 3 * DAY).length, 1, '第二档还没考')
})

// ---- 分流与打分 -----------------------------------------------------------------

test('分流：答错只是重打，敷衍才升级', () => {
  assert.deepEqual(route('pass'), { action: 'next', verdict: 'pass', escalate: false })
  assert.deepEqual(route('wrong'), { action: 'retry', verdict: 'wrong', escalate: false })
  assert.deepEqual(route('pending'), { action: 'ask-model', verdict: 'pending', escalate: false })
  assert.deepEqual(route('perfunctory'), { action: 'escalate', verdict: 'perfunctory', escalate: true })
})

test('打分：逐拍对照，给出每题那条参考推理链', () => {
  const s = scoreSheet({
    graded: [
      { questionId: 'q1', move: '先答后看', verdict: 'pass', judgedBy: 'code' },
      { questionId: 'q1', move: '定位', verdict: 'wrong', judgedBy: 'code' },
      { questionId: 'q1', move: '复述', verdict: 'pass', judgedBy: 'model' },
      { questionId: 'q1', move: '续接', verdict: 'perfunctory', judgedBy: 'code' },
    ],
    questions: [Q],
  })
  assert.equal(s.total, 4)
  assert.equal(s.pass, 2)
  assert.equal(s.wrong, 1)
  assert.equal(s.perfunctory, 1)
  assert.equal(s.code, 3)
  assert.equal(s.model, 1)
  assert.equal(s.score, 50)
  assert.deepEqual(s.rows[0].chain, Q.analysis, '参考推理链跟着每一拍')
  assert.equal(scoreSheet().score, 0)
})

// ---- 协议 -----------------------------------------------------------------------

test('思维局协议只认那四样，四样齐了就能装', () => {
  const r = validateThinking({ 协议版本: 1, id: 'xingce', 名称: '行测', 类型: '思维', 题库: 'xingce-2026', 招式: ['先答后看', '定位'], 强度: '低', 题数: 10 })
  assert.equal(r.ok, true, JSON.stringify(r.errors))
})

test('思维局协议：招式不在强度里、模型判占多数、题数不是正数，都拒', () => {
  const base = { id: 'x', 名称: 'x', 类型: '思维', 题库: 'b', 强度: '低', 题数: 5 }
  assert.ok(validateThinking({ ...base, 招式: ['先答后看', '定位', '复述'] }).errors.some((e) => e.code === 'move-out-of-intensity'))
  assert.ok(validateThinking({ ...base, 强度: '高', 招式: ['复述'] }).errors.some((e) => e.code === 'model-majority'))
  assert.ok(validateThinking({ ...base, 招式: ['先答后看'], 题数: 0 }).errors.some((e) => e.code === 'bad-count'))
  assert.ok(validateThinking({ ...base, 招式: ['没有这一招'] }).errors.some((e) => e.code === 'bad-move'))
  assert.ok(validateThinking({ ...base, 招式: [], 题数: 5 }).errors.some((e) => e.code === 'no-moves'))
})

test('思维局协议：不许混进行为局的字段，类型必须是思维', () => {
  const base = { id: 'x', 名称: 'x', 类型: '思维', 题库: 'b', 招式: ['先答后看'], 强度: '低', 题数: 5 }
  assert.ok(validateThinking({ ...base, 步骤: [] }).errors.some((e) => e.code === 'unknown-key'))
  assert.ok(validateThinking({ ...base, 类型: '行为' }).errors.some((e) => e.code === 'bad-type'))
  assert.ok(validateThinking(null).errors.length > 0)
})

test('思维局协议：不许声明墙的能力（升级和锁由引擎定）', () => {
  const r = validateThinking({ id: 'x', 名称: 'x', 类型: '思维', 题库: 'b', 招式: ['先答后看'], 强度: '低', 题数: 5, 需要能力: ['锁'] })
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.code === 'unknown-key'))
})
