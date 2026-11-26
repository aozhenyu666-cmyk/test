import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildAntiPerfunctoryDirective, distillPartnerMemory, pickDualTrackNextStep, sanitizeFactLine } from '../lib/partner_brain.js'
import { buildPrompt } from '../lib/prompt.js'
import { evaluateLiveCore } from '../lib/live_core.js'
import { auditDeliveryAuthenticity, submitDayBlockDelivery } from '../lib/plan_modality.js'

test('PartnerBrain-1: 7000/七千数字口径守卫防止双鱼误读成七千题，正常事实保持原样', () => {
  const numFact = sanitizeFactLine('之前讨论提到了七千这个数')
  assert.match(numFact, /数字待核实/)
  assert.match(numFact, /严禁擅自脑补成题量/)

  const arabicNum = sanitizeFactLine('记了个 7000')
  assert.match(arabicNum, /数字待核实/)

  const normal = sanitizeFactLine('她对芒果过敏')
  assert.equal(normal, '她对芒果过敏')

  const promptText = buildPrompt({
    topic: { kind: 'share', text: '复盘' },
    facts: ['之前提了个7000'],
    replyClass: 'brief',
    perfunctoryStreak: 3,
    energyTier: 'L2',
  })
  assert.match(promptText, /数字待核实/)
  assert.match(promptText, /绝不吃这一套/)
  assert.match(promptText, /已连续敷衍 3 次/)
})

test('PartnerBrain-2: 双轨长期锚点与5分钟微步精炼', () => {
  const res = distillPartnerMemory({
    facts: ['提到了7000'],
    horizons: {
      long: [{ id: 'g1', title: '考公上岸', status: 'active', targetSteps: 10, doneSteps: 3, nodes: [{ id: 'n1', title: '资料分析提速', status: 'active' }] }],
      medium: [],
    },
  })
  assert.match(res.digestLines.join('\n'), /考公上岸/)
  assert.match(res.digestLines.join('\n'), /资料分析提速/)
  assert.ok(res.digestLines.join('\n').length <= 480)
  const step = pickDualTrackNextStep({ horizons: { long: [{ id: 'g1', title: '就业面试', status: 'active', targetSteps: 4, doneSteps: 1 }] } })
  assert.match(step.voiceCue, /5分钟节拍/)
})

test('LiveCore-Pulse: planModal 超时 10m 打破死锁 + 息屏 5 分钟声学节拍钟触发', () => {
  const T_1500 = Date.UTC(2026, 10, 26, 7, 0, 0) // 15:00 Asia/Shanghai
  // 1) planModal 刚弹 <10m 正常让路
  const yieldFresh = evaluateLiveCore({
    now: T_1500,
    planModal: { active: true, shownAt: T_1500 - 2 * 60_000 },
    phone: { app: 'com.bbk.launcher2', minutes: 4, screenOn: true },
  })
  assert.equal(yieldFresh.reason, 'plan_modal_priority')

  // 2) planModal 已冷置 >=10m -> 不再锁死 Live
  const breakModal = evaluateLiveCore({
    now: T_1500,
    planModal: { active: true, shownAt: T_1500 - 11 * 60_000 },
    arbiter: { priority: 'P4', source: 'DAY_WORK_WALL' },
    phone: { app: 'com.bbk.launcher2', minutes: 5, screenOn: true },
  })
  assert.equal(breakModal.triggered, true)
  assert.equal(breakModal.reason, 'gaze_ignite_under_lock')

  // 3) 息屏开启 5 分钟声学节拍钟 -> 到点走 voiceOnly + playChime 破除不碰手机超级降频
  const pulse = evaluateLiveCore({
    now: T_1500,
    state: { enabled: true, alwaysOnLive: true, pulseIntervalMin: 5, lastPulseAt: T_1500 - 6 * 60_000 },
    phone: { screenOn: false, headsetConnected: false, geminiVoiceActive: false },
    horizons: { long: [{ id: 'g1', title: '考公上岸', status: 'active', targetSteps: 5, doneSteps: 2 }] },
  })
  assert.equal(pulse.triggered, true)
  assert.equal(pulse.reason, 'pulse_super_downclock')
  assert.equal(pulse.voiceOnly, true)
  assert.equal(pulse.playChime, true)
  assert.match(pulse.voiceLine, /5分钟节拍响了/)
})

test('DeliveryAudit: 拦截整段粘贴/AI 套话过关，放行拍照或变更要素抽查并给 +5m', () => {
  const blocked = auditDeliveryAuthenticity({
    text: '综上所述，本题解题步骤如下：首先排除A选项，其次分析B选项，最后得出结论。',
  })
  assert.equal(blocked.ok, false)
  assert.equal(blocked.error, 'paste-blocked')

  const dayState = {
    day: '2026-11-26',
    dayBlocks: [{ id: 'blk-1', title: '测试验收', lockMode: 'verify', status: 'running', verifyStatus: 'overdue_locked' }],
  }
  const blockPaste = submitDayBlockDelivery(dayState, {
    blockId: 'blk-1',
    summary: '这是一段直接从剪贴板整段复制进来的十几字文本内容',
    pasteDetected: true,
  })
  assert.equal(blockPaste.ok, false)
  assert.equal(blockPaste.error, 'paste-blocked')

  const passWithSpot = submitDayBlockDelivery(dayState, {
    blockId: 'blk-1',
    summary: '这是一段直接从剪贴板整段复制进来的十几字文本内容',
    pasteDetected: true,
    spotCheckAnswer: '把分母成本翻倍后利润率为负直接否决方案',
  })
  assert.equal(passWithSpot.ok, true)
  assert.equal(passWithSpot.authenticity, 'verified_human')
  assert.equal(passWithSpot.bonusMin, 5)
})

test('AntiPerfunctory: L0 零压态不反击敷衍，L2 态直接给出二选一真实出口', () => {
  const l0 = buildAntiPerfunctoryDirective('brief', { streak: 3, energyTier: 'L0' })
  assert.match(l0, /L0/)
  const l2 = buildAntiPerfunctoryDirective('brief', { streak: 1, energyTier: 'L2' })
  assert.match(l2, /10 秒说出/)
  assert.match(l2, /L0 安心歇/)
})
