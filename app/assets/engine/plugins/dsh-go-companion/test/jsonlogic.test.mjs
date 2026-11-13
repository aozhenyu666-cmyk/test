import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { evaluate, test as check, validate, OPERATORS, MAX_DEPTH } from '../lib/jsonlogic.js'

test('var 取嵌套路径，取不到用默认值', () => {
  assert.equal(evaluate({ var: 'bout.kind' }, { bout: { kind: '题局' } }), '题局')
  assert.equal(evaluate({ var: ['bout.minutes', 0] }, { bout: {} }), 0)
  assert.equal(evaluate({ var: 'nope' }, {}), null)
})

test('var 不走原型链', () => {
  assert.equal(evaluate({ var: 'constructor' }, {}), null)
  assert.equal(evaluate({ var: 'a.__proto__' }, { a: {} }), null)
})

test('比较运算', () => {
  assert.equal(evaluate({ '<': [1, 2] }, {}), true)
  assert.equal(evaluate({ '<=': [2, 2] }, {}), true)
  assert.equal(evaluate({ '>': [3, 2] }, {}), true)
  assert.equal(evaluate({ '>=': [1, 2] }, {}), false)
  assert.equal(evaluate({ '<': [1, 5, 10] }, {}), true, '三参数 between')
  assert.equal(evaluate({ '<': [1, 15, 10] }, {}), false)
})

test('== 数字与数字串按数值比，!= 反之', () => {
  assert.equal(evaluate({ '==': [3, '3'] }, {}), true)
  assert.equal(evaluate({ '==': ['a', 'a'] }, {}), true)
  assert.equal(evaluate({ '!=': [3, 4] }, {}), true)
})

test('and / or 短路，返回最后算到的值', () => {
  assert.equal(evaluate({ and: [true, false, { var: 'x.y' }] }, {}), false)
  assert.equal(evaluate({ or: [false, 0, 'yes'] }, {}), 'yes')
})

test('! 与 in（数组和字符串）', () => {
  assert.equal(evaluate({ '!': [[]] }, {}), true)
  assert.equal(evaluate({ in: ['题局', ['题局', '思维局']] }, {}), true)
  assert.equal(evaluate({ in: ['刷', '刷题'] }, {}), true)
  assert.equal(evaluate({ in: ['x', 3] }, {}), false)
})

test('if 多分支与 else', () => {
  const rule = { if: [{ '<': [{ var: 'h' }, 12] }, '上午', { '<': [{ var: 'h' }, 18] }, '下午', '晚上'] }
  assert.equal(evaluate(rule, { h: 9 }), '上午')
  assert.equal(evaluate(rule, { h: 15 }), '下午')
  assert.equal(evaluate(rule, { h: 20 }), '晚上')
})

test('算术与 max/min', () => {
  assert.equal(evaluate({ '+': [1, 2, 3] }, {}), 6)
  assert.equal(evaluate({ '-': [5, 2] }, {}), 3)
  assert.equal(evaluate({ '-': [5] }, {}), -5)
  assert.equal(evaluate({ max: [1, 9, 3] }, {}), 9)
  assert.equal(evaluate({ min: [4, 2] }, {}), 2)
})

test('不认识的运算符：evaluate 抛错，test 判假并带错', () => {
  assert.throws(() => evaluate({ eval: ['1'] }, {}), /unknown-operator/)
  const r = check({ eval: ['1'] }, {})
  assert.equal(r.ok, false)
  assert.equal(r.value, false)
})

test('test：没有条件就是真', () => {
  assert.deepEqual(check(null, {}), { ok: true, value: true })
  assert.deepEqual(check(undefined, {}), { ok: true, value: true })
})

test('validate：不认识的运算符拒绝', () => {
  const v = validate({ and: [{ '==': [1, 1] }, { system: ['rm'] }] })
  assert.equal(v.ok, false)
  assert.equal(v.errors[0].code, 'unknown-operator')
})

test('validate：变量白名单外的拒绝', () => {
  const v = validate({ '==': [{ var: 'chat.text' }, 'x'] }, { vars: ['bout', 'time'] })
  assert.equal(v.ok, false)
  assert.equal(v.errors[0].code, 'var-not-allowed')
  assert.equal(validate({ '==': [{ var: 'bout.kind' }, 'x'] }, { vars: ['bout'] }).ok, true)
})

test('validate：一个节点两个键拒绝', () => {
  assert.equal(validate({ '==': [1, 1], '<': [1, 2] }).errors[0].code, 'bad-node')
})

test('validate：var 必须是非空字符串路径', () => {
  assert.equal(validate({ var: [3] }).errors[0].code, 'bad-var')
  assert.equal(validate({ var: '' }).errors[0].code, 'bad-var')
})

test('validate：嵌套过深拒绝', () => {
  let rule = { '==': [1, 1] }
  for (let i = 0; i < MAX_DEPTH + 2; i += 1) rule = { '!': [rule] }
  assert.ok(validate(rule).errors.some((e) => e.code === 'too-deep'))
})

test('validate：节点过多拒绝', () => {
  const rule = { and: Array.from({ length: 300 }, () => true) }
  assert.ok(validate(rule).errors.some((e) => e.code === 'too-large'))
})

test('validate：null 条件合法', () => {
  assert.equal(validate(null).ok, true)
})

test('源码里没有 eval / Function 构造', () => {
  const src = readFileSync(new URL('../lib/jsonlogic.js', import.meta.url), 'utf8')
  const code = src.split('\n').filter((l) => !l.trim().startsWith('//')).join('\n')
  assert.equal(/\beval\s*\(/.test(code), false)
  assert.equal(/new\s+Function\s*\(/.test(code), false)
})

test('白名单里的每个运算符都能算', () => {
  const samples = {
    var: [{ var: 'a' }, { a: 1 }, 1], '==': [{ '==': [1, 1] }, {}, true], '!=': [{ '!=': [1, 2] }, {}, true],
    '<': [{ '<': [1, 2] }, {}, true], '<=': [{ '<=': [2, 2] }, {}, true], '>': [{ '>': [2, 1] }, {}, true],
    '>=': [{ '>=': [2, 2] }, {}, true], and: [{ and: [true, true] }, {}, true], or: [{ or: [false, true] }, {}, true],
    '!': [{ '!': [false] }, {}, true], in: [{ in: [1, [1]] }, {}, true], if: [{ if: [true, 1, 2] }, {}, 1],
    '+': [{ '+': [1, 1] }, {}, 2], '-': [{ '-': [3, 1] }, {}, 2], max: [{ max: [1, 2] }, {}, 2], min: [{ min: [1, 2] }, {}, 1],
  }
  for (const op of OPERATORS) {
    assert.ok(samples[op], '缺 ' + op + ' 的样例')
    const [rule, data, want] = samples[op]
    assert.deepEqual(evaluate(rule, data), want, op)
  }
})
