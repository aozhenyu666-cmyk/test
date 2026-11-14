import { test } from 'node:test'
import assert from 'node:assert/strict'

import { evaluateSixDiceSlot, parseL2Judge } from '../lib/six_dice.js'
import { auditSixDiceSlot, auditStrokeSlot } from '../lib/wall_auditor.js'
import {
  CORE_TOKEN_LIMIT,
  MAX_LOREBOOK_SLOTS,
  MAX_TELEMETRY_LINES,
  buildCoreMemoryBlocks,
  buildTelemetrySnapshot,
} from '../lib/memory_blocks.js'

const delay = (ms) => new Promise((r) => setTimeout(r, ms))

test('L01 L1 规则预筛拦截过短文本且不消耗 L2 判官调用', async () => {
  let called = 0
  const res = await evaluateSixDiceSlot({
    dice: 1,
    answer: '知道了',
    stream: async function* () {
      called++
      yield { type: 'text-delta', text: '{"pass":true}' }
    },
    provider: 'p',
    model: 'm',
  })
  assert.equal(res.pass, false)
  assert.equal(res.code, 'l2:too-short')
  assert.equal(called, 0)
})

test('L02 L1 规则预筛拦截高填充词敷衍答复 (l2:filler)', async () => {
  const res = await evaluateSixDiceSlot({
    dice: 2,
    answer: '嗯嗯啊啊呃呃那个就是然后这个嗯啊那个就是',
  })
  assert.equal(res.pass, false)
  assert.equal(res.code, 'l2:filler')
})

test('L03 L1 规则预筛拦截无具体对象的空话 (l2:vague)', async () => {
  const res = await evaluateSixDiceSlot({
    dice: 1,
    answer: '问题，事情，东西，情况，方法，意义，影响，结果',
  })
  assert.equal(res.pass, false)
  assert.equal(res.code, 'l2:vague')
})

test('L04 离线无模型服务时 L2 自动走降级放行且标记 l2_fallback_offline=true', async () => {
  const res = await auditSixDiceSlot({
    dice: 1,
    answer: '高数错题第4道显示洛必达法则适用前未检查分母极限条件。',
  })
  assert.equal(res.pass, true)
  assert.equal(res.judgedBy, 'code-fallback')
  assert.equal(res.l2_fallback_offline, true)
})

test('L05 L2 判官超时时走降级放行并明确写入 l2_fallback_offline=true', async () => {
  const res = await auditStrokeSlot({
    stroke: 'stroke_2_condense',
    answer: '洛必达法则使用前必须先验证 0/0 或 ∞/∞ 未定式条件，否则导数比不成立。',
    timeoutMs: 120,
  }, {
    provider: 'p',
    model: 'm',
    stream: async function* () {
      await delay(400)
      yield { type: 'text-delta', text: '{"pass":false}' }
    },
  })
  assert.equal(res.pass, true)
  assert.equal(res.judgedBy, 'timeout-fallback')
  assert.equal(res.l2_fallback_offline, true)
})

test('L06 L2 判官返回脏乱非JSON文本时走 fallback 标记 l2_fallback_offline=true', async () => {
  const res = await auditSixDiceSlot({
    dice: 4,
    answer: '如果初始样本均值本就存在偏差，结论里的提升幅度就会失真。',
  }, {
    provider: 'p',
    model: 'm',
    stream: async function* () {
      yield { type: 'text-delta', text: '无法给出 JSON 格式回应' }
    },
  })
  assert.equal(res.pass, true)
  assert.equal(res.judgedBy, 'timeout-fallback')
  assert.equal(res.l2_fallback_offline, true)
})

test('L07 L2 模型判官识别同义反复与无证据并给出失败计数与锚点提示', async () => {
  const res = await evaluateSixDiceSlot({
    dice: 2,
    answer: '如果出现误差就必须纠正误差否则还是误差。',
    sourceContext: '矩阵特征值分解在病态条件数 > 1e6 时会产生数值震荡。',
    anchors: ['病态条件数>1e6', '数值震荡'],
    failures: 1,
    provider: 'p',
    model: 'm',
    stream: async function* () {
      yield { type: 'text-delta', text: '{"pass":false,"code":"l2:circular","slot":"dice_2","directive":"存在同义反复，补出条件数边界。"}' }
    },
  })
  assert.equal(res.pass, false)
  assert.equal(res.code, 'l2:circular')
  assert.equal(res.failures, 2)
  assert.equal(res.judgedBy, 'l2-model')
  assert.equal(res.l2_fallback_offline, false)
  assert.match(res.anchorHint, /病态条件数>1e6/)
})

test('L08 L2 模型判官在线通过时清零 failures 且 l2_fallback_offline=false', async () => {
  const res = await evaluateSixDiceSlot({
    dice: 6,
    answer: '若不是算法收敛慢，另一种解释是学习率衰减步长设太早锁死了梯度更新。',
    failures: 1,
    provider: 'p',
    model: 'm',
    stream: async function* () {
      yield { type: 'text-delta', text: '```json\n{"pass":true,"code":"l2:pass","slot":"dice_6","directive":"替代机制成立"}\n```' }
    },
  })
  assert.equal(res.pass, true)
  assert.equal(res.failures, 0)
  assert.equal(res.judgedBy, 'l2-model')
  assert.equal(res.l2_fallback_offline, false)
})

test('L09 parseL2Judge 兼容 markdown 代码块并裁剪超长指令', () => {
  const parsed = parseL2Judge('结果如下：```json\n{"pass":false,"code":"l2:no-evidence","directive":"' + '字'.repeat(80) + '"}\n```', 'dice_5')
  assert.equal(parsed.pass, false)
  assert.equal(parsed.code, 'l2:no-evidence')
  assert.equal(parsed.slot, 'dice_5')
  assert.ok(parsed.directive.length <= 60)
})

test('L10 三块核心内存预算严格 <= 1800 tokens、Telemetry <= 5 行、Lorebook <= 3 条且含全部五项关键遥测', () => {
  const snap = buildTelemetrySnapshot({
    state: 'sprint',
    department: 'dept:govern',
    bout: { protocolId: 'study', department: 'dept:govern', leftMin: 18 },
    phone: { app: 'com.apple.notes', label: '笔记', minutes: 12, work: true, funMinutesToday: 8, workMinutesToday: 42 },
    switches30m: 3,
    bufferLeftMin: 15,
    minsSinceLastWallPass: 48,
    credit: 82,
  })
  assert.ok(snap.length <= MAX_TELEMETRY_LINES)
  const joined = snap.join('\n')
  assert.match(joined, /30m切3次/)
  assert.match(joined, /缓冲余15m/)
  assert.match(joined, /距上次墙过关48m/)
  assert.match(joined, /今日娱乐8m/)

  const built = buildCoreMemoryBlocks({
    persona: '长文本'.repeat(400),
    selfDigest: '铁律'.repeat(300),
    telemetry: { state: 'sprint', switches30m: 2, bufferLeftMin: 10, minsSinceLastWallPass: 20 },
    lorebook: ['记忆1'.repeat(60), '记忆2'.repeat(60), '记忆3'.repeat(60), '记忆4'.repeat(60)],
  })
  assert.ok(built.tokens <= CORE_TOKEN_LIMIT)
  assert.equal(built.withinBudget, true)
  assert.ok(built.lorebookCount <= MAX_LOREBOOK_SLOTS)
  assert.ok(built.telemetryLines <= MAX_TELEMETRY_LINES)
})
