// 第五轮·每日四部门提交（纯函数）：字数、复读、按天复归、齐没齐。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEPARTMENTS, MIN_REPORT, MIN_THOUGHT, auditEntry, charCount, completeOf, depositLines, depositOf,
  missingText, normalizeEntry, statusOf, withEntry,
} from '../lib/deposit.js'

const DAY = '2026-11-09'
const long = (n, ch = '字') => ch.repeat(n)

test('四个部门，顺序固定：信息收集 → 谋划思考 → 行为管理 → 统筹规划', () => {
  assert.deepEqual(DEPARTMENTS.map((d) => d.key), ['intel', 'thinking', 'behavior', 'coord'])
  assert.deepEqual(DEPARTMENTS.map((d) => d.label), ['信息收集', '谋划思考', '行为管理', '统筹规划'])
  for (const d of DEPARTMENTS) assert.ok(d.hint.length > 0, d.key + ' 缺提示')
})

test('字数按去掉空白和标点后的字数算', () => {
  assert.equal(charCount('你好，世界！'), 4)
  assert.equal(charCount('  你好   世界  '), 4)
  assert.equal(charCount(''), 0)
  assert.equal(charCount(null), 0)
})

test('汇报不够 200 字、想法不够 80 字，都退回并说清还差多少', () => {
  const a = auditEntry({ report: long(10), thought: long(5) })
  assert.equal(a.ok, false)
  assert.equal(a.problems.length, 2)
  assert.ok(a.problems[0].includes(String(MIN_REPORT - 10)), a.problems[0])
  assert.ok(a.problems[1].includes(String(MIN_THOUGHT - 5)), a.problems[1])
})

test('刚好到线就收（边界不多算一个）', () => {
  assert.equal(auditEntry({ report: long(MIN_REPORT), thought: long(MIN_THOUGHT) }).ok, true)
  assert.equal(auditEntry({ report: long(MIN_REPORT - 1), thought: long(MIN_THOUGHT) }).ok, false)
})

test('想法把汇报原样贴一遍，不算想法', () => {
  const a = auditEntry({ report: long(MIN_REPORT, '铺'), thought: long(MIN_REPORT, '铺') })
  assert.equal(a.ok, false)
  assert.ok(a.problems.some((p) => p.includes('同一段话')), a.problems.join('|'))
})

test('normalizeEntry 收齐才给 entry，带提交时间；脏输入不抛', () => {
  const ok = normalizeEntry({ report: long(MIN_REPORT), thought: long(MIN_THOUGHT) }, 1234)
  assert.equal(ok.ok, true)
  assert.equal(ok.entry.at, 1234)
  assert.equal(normalizeEntry({ report: '', thought: '' }, 1).ok, false)
  assert.equal(normalizeEntry(null, 1).ok, false)
  // 超长截断，不无限膨胀
  const huge = normalizeEntry({ report: long(9000), thought: long(4000) }, 1)
  assert.equal(huge.ok, true)
  assert.ok(huge.entry.report.length <= 6000)
  assert.ok(huge.entry.thought.length <= 3000)
})

test('depositOf 只认当天：昨天的交过也不算今天', () => {
  const state = withEntry(depositOf({}, DAY), 'intel', { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 1 })
  assert.equal(statusOf(depositOf(state, DAY)).done, 1)
  assert.equal(statusOf(depositOf(state, '2026-11-10')).done, 0)
  // 脏数据不抛
  assert.equal(statusOf(depositOf({ day: DAY, entries: 5 }, DAY)).done, 0)
})

test('交齐四个才 completeOf；缺的写进 missingText', () => {
  let state = depositOf({}, DAY)
  assert.equal(completeOf(state), false)
  assert.equal(missingText(state), '今天还缺 4 段：信息收集、谋划思考、行为管理、统筹规划。')
  for (const d of DEPARTMENTS) {
    state = withEntry(state, d.key, { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 1 })
  }
  assert.equal(completeOf(state), true)
  assert.equal(missingText(state), '')
  const s = statusOf(state)
  assert.equal(s.done, 4)
  assert.equal(s.total, 4)
  assert.deepEqual(s.missing, [])
})

test('statusOf 把每段的字数和下限都给界面，不用前端自己算', () => {
  const state = withEntry(depositOf({}, DAY), 'intel', { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 7 })
  const item = statusOf(state).items.find((i) => i.key === 'intel')
  assert.equal(item.done, true)
  assert.equal(item.reportChars, MIN_REPORT)
  assert.equal(item.thoughtChars, MIN_THOUGHT)
  assert.equal(item.minReport, MIN_REPORT)
  assert.equal(item.minThought, MIN_THOUGHT)
  assert.equal(item.at, 7)
})

test('人设里那几句：缺的要点名催，交齐了就不再催', () => {
  let state = depositOf({}, DAY)
  const busy = depositLines(state, false).join('\n')
  assert.ok(busy.includes('信息收集'), busy)
  assert.ok(busy.includes('统筹规划'), busy)
  assert.ok(busy.includes('200'), busy)
  // 晚上对账时间催得更硬：出现「对账」这句强的，普通时段说明后果
  assert.ok(depositLines(state, true).join('\n').includes('先把这四段要到手'))
  assert.ok(busy.includes('放风额度'), busy)
  for (const d of DEPARTMENTS) {
    state = withEntry(state, d.key, { report: long(MIN_REPORT), thought: long(MIN_THOUGHT), at: 1 })
  }
  const done = depositLines(state, true).join('\n')
  assert.ok(done.includes('交齐了'), done)
  assert.ok(!done.includes('缺'), done)
})
