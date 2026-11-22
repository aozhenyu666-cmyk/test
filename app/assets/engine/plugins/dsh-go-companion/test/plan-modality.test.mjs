import test from 'node:test'
import assert from 'node:assert/strict'

import {
  AI_PROMPT_SCRIPTS,
  applyDayPlanAction,
  BLOCK_LOCK_MODES,
  BLOCK_STATUSES,
  buildDayPlanGeminiPrompt,
  buildDefaultDayPlan,
  cleanDayBlock,
  cleanPlanContract,
  COMMON_PLAN_PRESETS,
  computeDayPlanStats,
  detectCurrentBlockDeviation,
  ENERGY_LEVELS,
  evaluateCheckpointSubmission,
  evaluateContractTick,
  inspectScenarioApps,
  LUNCH_BREAK_WINDOW,
  OVERDUE_GRACE_MS,
  promoteCarryoverToBlock,
  removeDayBlock,
  resolveContractSessionAllow,
  setDayBlockStatus,
  upsertDayBlock,
} from '../lib/plan_modality.js'

test('backward compatibility: existing contract, presets, scripts, and 1m grace escalation remain intact', () => {
  assert.equal(OVERDUE_GRACE_MS, 60_000)
  assert.ok(COMMON_PLAN_PRESETS.length >= 5)
  assert.ok(AI_PROMPT_SCRIPTS.length >= 6)
  const c = cleanPlanContract({
    id: 'c-legacy',
    title: '网课提炼',
    allowedApps: ['tv.danmaku.bili', 'md.obsidian'],
    checkpoints: [{ id: 'cp1', title: '交因果卡', dueAt: 1_700_000_060_000 }],
  })
  assert.equal(c.energyLevel, 'mid')
  assert.deepEqual(c.dayBlocks, [])
  assert.deepEqual(c.carryoverItems, [])
  assert.deepEqual(resolveContractSessionAllow(c, { escalated: true }), ['md.obsidian'])

  const due = 1_700_000_060_000
  const grace = evaluateContractTick(c, due + 20_000)
  assert.equal(grace.graceWarning?.remainSec, 40)
  assert.equal(grace.triggerStrictNow, false)

  const esc = evaluateContractTick(grace.contract, due + 65_000)
  assert.equal(esc.triggerStrictNow, true)

  const passed = evaluateCheckpointSubmission(esc.contract, {
    checkpointId: 'cp1',
    summary: '因为题干给出了增长量比较，所以优先比较分子与分母增速，结论是现期量决定方向。',
    aiConfirmed: true,
    now: due + 70_000,
  })
  assert.equal(passed.passed, true)
  assert.equal(passed.contract.strictEscalated, false)

  const probe = inspectScenarioApps({
    scenario: '用 B站 看网课并做笔记',
    declaredApps: [{ app: 'tv.danmaku.bili', label: 'B站', role: 'work' }],
  })
  assert.equal(probe.recommendedAllow.some((x) => x.app === 'tv.danmaku.bili'), true)
})

test('energy levels generate tailored default dayBlocks with automatic 12:00-14:00 lunch break', () => {
  assert.deepEqual(ENERGY_LEVELS, ['low', 'mid', 'high'])
  assert.deepEqual(BLOCK_LOCK_MODES, ['gentle', 'strict', 'verify'])
  assert.deepEqual(BLOCK_STATUSES, ['pending', 'running', 'done', 'skipped'])

  const lowPlan = buildDefaultDayPlan({ energyLevel: 'low', funBudgetMin: 30 })
  const midPlan = buildDefaultDayPlan({ energyLevel: 'mid', funBudgetMin: 30 })
  const highPlan = buildDefaultDayPlan({ energyLevel: 'high', funBudgetMin: 30 })

  assert.ok(lowPlan.stats.totalFocusMin < midPlan.stats.totalFocusMin)
  assert.ok(midPlan.stats.totalFocusMin < highPlan.stats.totalFocusMin)

  for (const plan of [lowPlan, midPlan, highPlan]) {
    const lunch = plan.dayBlocks.find((b) => b.slot === 'lunch')
    assert.ok(lunch, '必须自动包含午休块')
    assert.equal(lunch.startMin, LUNCH_BREAK_WINDOW.startMin)
    assert.equal(lunch.endMin, LUNCH_BREAK_WINDOW.endMin)
    assert.equal(lunch.isBreak, true)
    assert.equal(lunch.lockMode, 'gentle')
    assert.equal(plan.stats.lunchBreakMin, 120)
  }
})

test('dayBlocks CRUD, status transition, carryover promotion, and stats work cleanly', () => {
  let blocks = buildDefaultDayPlan({ energyLevel: 'mid' }).dayBlocks
  const initialCount = blocks.length

  // Add custom evening block
  const added = upsertDayBlock(blocks, {
    title: '晚间申论提纲整理',
    startMin: 20 * 60 + 30,
    durationMin: 40,
    lockMode: 'verify',
    deliverable: '提交 3 段论点框架',
    allowedApps: ['md.obsidian'],
  })
  assert.equal(added.ok, true)
  assert.equal(added.dayBlocks.length, initialCount + 1)
  assert.equal(added.block.slot, 'evening')
  assert.equal(added.block.endMin, 20 * 60 + 70)
  blocks = added.dayBlocks

  // Edit duration & lock mode
  const edited = upsertDayBlock(blocks, {
    id: added.block.id,
    durationMin: 60,
    lockMode: 'strict',
  })
  assert.equal(edited.ok, true)
  assert.equal(edited.block.endMin - edited.block.startMin, 60)
  assert.equal(edited.block.lockMode, 'strict')
  blocks = edited.dayBlocks

  // Set running and ensure only one running block at a time
  const firstNonBreak = blocks.find((b) => !b.isBreak)
  blocks = setDayBlockStatus(blocks, firstNonBreak.id, 'running').dayBlocks
  blocks = setDayBlockStatus(blocks, added.block.id, 'running').dayBlocks
  assert.equal(blocks.filter((b) => b.status === 'running').length, 1)
  assert.equal(blocks.find((b) => b.id === added.block.id).status, 'running')

  // Mark done and compute stats
  blocks = setDayBlockStatus(blocks, added.block.id, 'done').dayBlocks
  const stats = computeDayPlanStats(blocks, { funBudgetMin: 45, energyLevel: 'mid' })
  assert.equal(stats.completedFocusMin, 60)
  assert.equal(stats.doneCount, 1)
  assert.equal(stats.funBudgetMin, 45)
  assert.ok(stats.focusToFunRatio > 1)

  // Remove block
  const removed = removeDayBlock(blocks, added.block.id)
  assert.equal(removed.ok, true)
  assert.equal(removed.dayBlocks.length, initialCount)

  // Carryover promotion avoids 12:00-14:00 lunch window when promoted into morning/afternoon
  const promoted = promoteCarryoverToBlock({
    energyLevel: 'mid',
    carryoverItems: [{ id: 'y1', title: '昨晚未完的行测资料 20 题', minutes: 40 }],
    dayBlocks: blocks,
  }, 'y1')
  assert.equal(promoted.ok, true)
  assert.equal(promoted.carryoverItems[0].moved, true)
  assert.ok(promoted.block.title.includes('行测资料 20 题'))
  assert.ok(!(promoted.block.startMin >= 720 && promoted.block.startMin < 840))
})

test('watch mode deviation detection & applyDayPlanAction + Gemini prompt packaging', () => {
  const blk = cleanDayBlock({
    id: 'b1',
    title: '上午刷题块',
    startMin: 9 * 60,
    endMin: 10 * 60,
    allowedApps: ['md.obsidian'],
    status: 'pending',
  })
  const late = detectCurrentBlockDeviation([blk], {
    minuteOfDay: 9 * 60 + 12,
    currentApp: 'md.obsidian',
    currentAppFun: false,
    boutRunning: false,
  })
  assert.equal(late.activeBlock?.id, 'b1')
  assert.equal(late.isLateStart, true)
  assert.equal(late.isOffTrack, false)
  assert.equal(late.remainMin, 48)

  const off = detectCurrentBlockDeviation([blk], {
    minuteOfDay: 9 * 60 + 15,
    currentApp: 'com.ss.android.ugc.aweme',
    currentAppFun: true,
    boutRunning: false,
  })
  assert.equal(off.isOffTrack, true)
  assert.match(off.warningText, /偏航警告/)

  // Action reducer end-to-end
  let st = applyDayPlanAction(null, { action: 'set_energy', energyLevel: 'high' })
  assert.equal(st.energyLevel, 'high')
  st = applyDayPlanAction(st, { action: 'generate_default', energyLevel: 'high' }, { funBudgetMin: 30 })
  assert.equal(st.stats.focusCount, 5)
  assert.equal(st.stats.lunchBreakMin, 120)

  const prompt = buildDayPlanGeminiPrompt({
    energyLevel: 'low',
    carryoverItems: [{ id: 'c1', title: '投递 3 家后端岗位' }],
    dayBlocks: st.dayBlocks,
    funBudgetMin: 30,
  })
  assert.match(prompt, /低电量保底/)
  assert.match(prompt, /投递 3 家后端岗位/)
  assert.match(prompt, /1–3 件硬骨头/)
})
