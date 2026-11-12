// P3 三分类：答错只重打、敷衍走升级、混乱只压缩；口水话四个代码判据。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  FILLER_MAX, LONG_CHARS, OVERLAP_MAX, RECENT, SHORT_CHARS,
  classify, handlingOf, isChaos, isPerfunctory, maxOverlap, overlap,
} from '../lib/reply.js'

test('P3 重合率：一样的句子是 1', () => {
  assert.equal(overlap('投三家简历', '投三家简历'), 1)
})

test('P3 重合率：完全不同的两句接近 0', () => {
  assert.ok(overlap('投三家简历', '今天吃了面条') < 0.3)
})

test('P3 重合率：空串不崩', () => {
  assert.equal(overlap('', '随便'), 0)
  assert.equal(overlap(null, null), 0)
})

test('P3 只跟最近 5 条比', () => {
  const recent = Array.from({ length: 8 }, (_, i) => '第' + i + '句不一样的话')
  recent.push('投三家简历并交截图')
  const old = '投三家简历并交截图'
  // 第 9 条（索引 8）在最近 5 条之内。
  assert.ok(maxOverlap(old, recent) > 0.9)
  assert.equal(RECENT, 5)
})

test('P3 口水话：不到 8 个字', () => {
  const r = isPerfunctory('好的')
  assert.equal(r.perfunctory, true)
  assert.match(r.why, /不到 8 个字/)
  assert.equal(SHORT_CHARS, 8)
})

test('P3 口水话：一个具体名词都没有', () => {
  // 每个汉字都被空格隔开，凑不出 2 字以上的中文串；但字符数够 8。
  const r = isPerfunctory('好 的 是 ok ok ok')
  assert.equal(r.metrics.nouns, 0)
  assert.ok(r.metrics.chars >= SHORT_CHARS)
  assert.equal(r.perfunctory, true)
  assert.match(r.why, /具体名词/)
})

test('P3 口水话：填充词占比过半', () => {
  const r = isPerfunctory('嗯嗯嗯嗯嗯嗯嗯嗯')
  assert.ok(r.metrics.filler > FILLER_MAX)
  assert.equal(r.perfunctory, true)
  assert.match(r.why, /填充词/)
})

test('P3 口水话：和刚才那句一样', () => {
  const r = isPerfunctory('投三家简历并交截图', ['投三家简历并交截图'])
  assert.equal(r.perfunctory, true)
  assert.match(r.why, /同一句/)
  assert.equal(OVERLAP_MAX, 0.7)
})

test('P3 口水话：没作答', () => {
  assert.equal(isPerfunctory('').why, '没作答')
  assert.equal(isPerfunctory('   ').why, '没作答')
})

test('P3 认真答了不算口水话', () => {
  const r = isPerfunctory('投了三家简历，截图在相册里，明天等面试通知')
  assert.equal(r.perfunctory, false)
})

test('P3 混乱：说了三件以上', () => {
  // 分句按句号切，所以三件事要用句号分开。
  const r = isChaos('投简历。写周报。买菜。')
  assert.equal(r.chaos, true)
  assert.match(r.why, /三件以上/)
})

test('P3 混乱：长而无结论', () => {
  const long = '我今天上午看了一下简历还有面试通知'.repeat(5)
  assert.ok(long.length > LONG_CHARS)
  assert.equal(isChaos(long).chaos, true)
  assert.match(isChaos(long).why, /没结论/)
})

test('P3 长但有结论就不算混乱', () => {
  const long = '我今天上午把简历改了一遍，投了三家，其中一家约了面试，其余等通知，' +
    '下午准备复习一下行测的图形推理，晚上再确认一遍投递状态，顺便把明天的资料也整理好。总之今天简历投完了。'
  assert.ok(long.length > LONG_CHARS, '这句要够长，实际 ' + long.length)
  assert.equal(isChaos(long).chaos, false)
})

test('P3 三分类：超时算敷衍，不算答错', () => {
  const r = classify({ text: '', timedOut: true })
  assert.equal(r.kind, 'perfunctory')
  assert.match(r.why, /超时/)
})

test('P3 三分类：口水话算敷衍', () => {
  assert.equal(classify({ text: '好的' }).kind, 'perfunctory')
})

test('P3 三分类：答了但不符合依据算答错', () => {
  const r = classify({ text: '投了三家简历，截图在相册里，明天等面试通知', passed: false })
  assert.equal(r.kind, 'wrong')
})

test('P3 三分类：答对', () => {
  const r = classify({ text: '投了三家简历，截图在相册里，明天等面试通知', passed: true })
  assert.equal(r.kind, 'ok')
})

test('P3 三分类：混乱优先于答错', () => {
  const r = classify({ text: '投简历。写周报。买菜。', passed: false })
  assert.equal(r.kind, 'chaos', '混乱只发压缩指令，不按答错重打')
})

test('P3 处置分岔：答错不罚、敷衍罚', () => {
  assert.deepEqual(handlingOf('wrong'), { retry: true, penalize: false, note: '重打本拍，不罚' })
  assert.deepEqual(handlingOf('perfunctory'), { retry: true, penalize: true, note: '记一次敷衍，走升级' })
  assert.deepEqual(handlingOf('chaos'), { retry: true, penalize: false, note: '只发压缩指令，让他选一件' })
  assert.deepEqual(handlingOf('ok'), { retry: false, penalize: false, note: '过' })
})

test('P3 只有敷衍进升级', () => {
  for (const k of ['wrong', 'chaos', 'ok']) assert.equal(handlingOf(k).penalize, false, k + ' 不该罚')
})
