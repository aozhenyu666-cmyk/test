import test from 'node:test'
import assert from 'node:assert/strict'
import {
  applyAppeal,
  buildInterrogationPrompt,
  buildNineGrid,
  computePoolTelemetry,
  defaultV6State,
  deleteStele,
  evaluateWindowMode,
  ingestClipboardOrText,
  normalizeFramework,
  parseContractBlock,
  recordMicroQuizAnswer,
  sampleKnowledgeItems,
  sweepForgetting,
  upsertStele,
} from '../lib/v6_engine.js'

test('动态时间与严格度框架：标准化及双时段窗口判定', () => {
  const fw = normalizeFramework({
    wakeQuizCooldownMin: 12,
    fragmentedWindows: ['12:00 - 13:00'],
    deskMoatWindows: ['19:30-21:30'],
  })
  assert.equal(fw.wakeQuizCooldownMin, 12)
  assert.deepEqual(fw.fragmentedWindows, ['12:00-13:00'])

  const desk = evaluateWindowMode(fw, 20 * 60 + 15)
  assert.equal(desk.mode, 'desk_moat')
  assert.equal(desk.blockMetaChitChat, true)

  const frag = evaluateWindowMode(fw, 12 * 60 + 20)
  assert.equal(frag.mode, 'fragmented_digest')
  assert.equal(frag.allowDigestPush, true)
})

test('剪贴板双向总线：精准识别【今日契约】并切片吸收 Gemini 知识', () => {
  const state = defaultV6State(1_700_000_000_000)

  const contractClip = `
【今日契约】
靶子：做完15道资料分析速算并核对基期误区
时长：25分钟
验收：交出1句核心错误机制总结
`
  const parsed = parseContractBlock(contractClip)
  assert.ok(parsed)
  assert.equal(parsed.durationMin, 25)
  assert.match(parsed.target, /资料分析/)

  const resContract = ingestClipboardOrText(state.pool, contractClip)
  assert.equal(resContract.kind, 'contract')

  const geminiText = `
  1. 削弱论证遇到数据对比，如果没有给总基数，只比较绝对数量差就是偷换概念陷阱。
  2. 每天桌前坐定前先不要调整设置，直接把前十分钟交给第一道具体题。
  `
  const resKnowledge = ingestClipboardOrText(state.pool, geminiText, { source: 'gemini' })
  assert.equal(resKnowledge.kind, 'knowledge')
  assert.equal(resKnowledge.added.length, 2)
  assert.equal(resKnowledge.pool.length, state.pool.length + 2)
})

test('知识海防重复冷却、答对毕业、快速乱点惩罚与遗忘清理', () => {
  const now = 1_700_000_000_000
  const state = defaultV6State(now)
  const picked = sampleKnowledgeItems(state.pool, state.framework, { count: 1, now, markShown: true })
  assert.equal(picked.length, 1)

  // 快速盲点错选触发 antiMashPenalty
  const wrongMash = recordMicroQuizAnswer(state.pool, state.framework, {
    itemId: picked[0].id,
    userTrue: !picked[0].isTrue,
    elapsedMs: 120,
    now,
  })
  assert.equal(wrongMash.correct, false)
  assert.equal(wrongMash.antiMashPenalty, true)

  // 连对两次触发自动毕业
  recordMicroQuizAnswer(state.pool, state.framework, {
    itemId: picked[0].id,
    userTrue: picked[0].isTrue,
    elapsedMs: 1400,
    now: now + 1000,
  })
  const grad = recordMicroQuizAnswer(state.pool, state.framework, {
    itemId: picked[0].id,
    userTrue: picked[0].isTrue,
    elapsedMs: 1200,
    now: now + 2000,
  })
  assert.equal(grad.graduated, true)

  // 遗忘归档：15天前看过且零连对的条目被清理
  const stale = state.pool.find((c) => c.id !== picked[0].id)
  stale.seenCount = 1
  stale.hitStreak = 0
  stale.createdAt = now - 16 * 86_400_000
  stale.lastShownAt = now - 16 * 86_400_000
  const swept = sweepForgetting(state.pool, state.framework, now)
  assert.ok(swept.archivedCount >= 1)

  const tel = computePoolTelemetry(swept.pool, now)
  assert.ok(tel.total > 0)
  assert.ok(tel.dedupeRate72h >= 60 && tel.dedupeRate72h <= 100)
})

test('3刀追问剧本、强关联九宫格与4类严锁申诉额度控制', () => {
  const now = 1_700_000_000_000
  const state = defaultV6State(now)

  const prompt = buildInterrogationPrompt({
    horizons: { long: [{ status: 'active', title: '省考上岸' }] },
    pool: state.pool,
    now,
    energyHint: 'L1',
  })
  assert.match(prompt, /省考上岸/)
  assert.match(prompt, /【今日契约】/)

  const grid = buildNineGrid({ pool: state.pool, stelesCount: state.steles.length, now })
  assert.equal(grid.length, 9)
  assert.equal(grid[8].kind, 'wanshiming')

  const a1 = applyAppeal(state.appeals, state.framework, { kind: 'physio_rest', now, day: '2026-11-26' })
  assert.equal(a1.ok, true)
  const a2 = applyAppeal(a1.usage, state.framework, { kind: 'physio_rest', now, day: '2026-11-26' })
  assert.equal(a2.ok, false)

  const a3 = applyAppeal(a1.usage, state.framework, { kind: 'downgrade_micro_quiz', now, day: '2026-11-26' })
  assert.equal(a3.ok, true)
  const a4 = applyAppeal(a3.usage, state.framework, { kind: 'handoff_gemini_live', now, day: '2026-11-26' })
  assert.equal(a4.ok, true)
  const a5 = applyAppeal(a4.usage, state.framework, { kind: 'swap_item', now, day: '2026-11-26' })
  assert.equal(a5.ok, false)
})

test('《万世铭》碑铭写入与删除', () => {
  const now = 1_700_000_000_000
  const state = defaultV6State(now)
  const added = upsertStele(state.steles, {
    title: '测试碑刻',
    category: '抉择',
    body: '完成 V6 引擎单测校验',
    starred: true,
  }, now)
  assert.equal(added.ok, true)
  assert.equal(added.steles.length, state.steles.length + 1)

  const removed = deleteStele(added.steles, added.item.id)
  assert.equal(removed.ok, true)
  assert.equal(removed.steles.length, state.steles.length)
})
