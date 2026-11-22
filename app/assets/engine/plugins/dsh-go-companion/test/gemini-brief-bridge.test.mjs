// Gemini 桥连着时，给 Gemini 的背景里写清「用工具」；没连时一个字都不多。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { geminiBrief, GEMINI_MODES } from '../lib/gemini.js'
import { buildStandardGeminiBrief } from '../lib/gemini_gate.js'

test('bridge=true：先看今天、写验收点、交回执（2 问 2 答才判过）', () => {
  const b = geminiBrief({ topic: '资料分析', mode: 'study', bridge: true })
  assert.match(b, /dafeiyu_today/)
  assert.match(b, /dafeiyu_add_checkpoints/)
  assert.match(b, /dafeiyu_submit_receipt/)
  assert.match(b, /至少问 2 个问题/)
  const off = geminiBrief({ topic: '资料分析', mode: 'study' })
  assert.doesNotMatch(off, /dafeiyu_/)
  assert.match(buildStandardGeminiBrief({ topic: 't', bridge: true }), /dafeiyu_today/)
})

test('plan 口吻改成定时间轴：每件写清交什么、几点开始、几点前、保底', () => {
  assert.match(GEMINI_MODES.plan, /时间轴/)
  assert.match(GEMINI_MODES.plan, /几点开始/)
})
