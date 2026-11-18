import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { judgeQuizAnswer, parseBankSmart, pickNextQuizQuestion } from '../lib/bank_unlock.js'
import { evaluateAutonomyLevel } from '../lib/autonomy.js'
import { cleanHorizons, mirrorHorizonsToMarkdown, syncLongGoalsFromMarkdown, upsertHorizonItem } from '../lib/horizons.js'
import {
  AI_PROMPT_SCRIPTS,
  COMMON_PLAN_PRESETS,
  evaluateCheckpointSubmission,
  evaluateContractTick,
  inspectScenarioApps,
  OVERDUE_GRACE_MS,
  resolveContractSessionAllow,
} from '../lib/plan_modality.js'
import { createV4Hub } from '../lib/v4_hub.js'
import { compileCausalChainCard, runMainlineAlignmentAudit, runRedFlagScamAudit } from '../lib/dept_skills.js'
import { buildTodayCharts } from '../lib/telemetry_charts.js'

test('parseBankSmart + 10-question quiz unlock grants 5-minute pass', () => {
  const sampleText = Array.from({ length: 10 }, (_, i) => [
    `${i + 1}. 资料分析第${i + 1}题：增长率计算核心公式是？`,
    'A. 现期/基期',
    'B. (现期-基期)/基期',
    'C. 基期-现期',
    'D. 现期*基期',
    '答案：B',
    '解析：同比增长率 = 增长量 / 基期量。',
  ].join('\n')).join('\n\n')

  const parsed = parseBankSmart(sampleText, { name: '行测真题测试库' })
  assert.equal(parsed.ok, true)
  assert.equal(parsed.count, 10)

  let state = { config: { requiredCount: 10, unlockMinutes: 5 } }
  const now = 1_700_000_000_000
  for (let i = 0; i < 10; i += 1) {
    const picked = pickNextQuizQuestion(parsed.rawBank, state)
    assert.ok(picked.question?.id)
    state = picked.state
    const res = judgeQuizAnswer(parsed.rawBank, state, { questionId: picked.question.id, answer: 'b', now })
    assert.equal(res.ok, true)
    assert.equal(res.correct, true)
    state = res.state
    if (i < 9) {
      assert.equal(res.unlocked, false)
    } else {
      assert.equal(res.unlocked, true)
      assert.equal(res.unlockMinutes, 5)
      assert.equal(res.unlockUntil, now + 5 * 60_000)
    }
  }
})

test('autonomyLevel provides L0 strict pressure and L2/L3 positive release valve', () => {
  const overdueState = evaluateAutonomyLevel({ overdueCheckpoints: 1, violationsToday: 2 })
  assert.equal(overdueState.level, 'L0')
  assert.equal(overdueState.unlockedRelaxation, false)

  const earnedState = evaluateAutonomyLevel({
    completedBouts: 2,
    checkpointsPassed: 1,
    quizSolvedToday: 10,
    focusMinutes: 120,
    funMinutes: 15,
    violationsToday: 0,
  })
  assert.equal(earnedState.level, 'L3')
  assert.equal(earnedState.unlockedRelaxation, true)
})

test('plan_modality checks scenario apps, waits 1 minute grace, then auto-escalates to strict', () => {
  const scenario = inspectScenarioApps({
    scenario: '看 B站 课程并用 Obsidian 提炼笔记',
    declaredApps: [
      { app: 'tv.danmaku.bili', label: '哔哩哔哩', role: 'work' },
      { app: 'md.obsidian', label: 'Obsidian', role: 'work' },
    ],
  })
  assert.equal(scenario.sufficient, true)
  assert.equal(scenario.recommendedAllow.some((a) => a.app === 'tv.danmaku.bili'), true)

  const t0 = 1_700_000_000_000
  const dueAt = t0 + 30 * 60_000
  const contract = {
    id: 'c1',
    title: '申论视频提炼',
    strictOnOverdue: true,
    checkpoints: [{ id: 'cp1', title: '提交压缩提炼', dueAt, graceMs: OVERDUE_GRACE_MS, status: 'pending' }],
  }

  // 到点前 5 分钟：正常柔性观察
  const before = evaluateContractTick(contract, dueAt - 5 * 60_000)
  assert.equal(before.triggerStrictNow, false)
  assert.equal(before.graceWarning, null)

  // 逾期 30 秒：处于 1 分钟缓冲倒计时提醒，尚不强锁
  const inGrace = evaluateContractTick(contract, dueAt + 30_000)
  assert.equal(inGrace.triggerStrictNow, false)
  assert.equal(inGrace.graceWarning?.remainSec, 30)

  // 逾期 61 秒（超过 1 分钟）：自动升级为严格锁！
  const pastGrace = evaluateContractTick(inGrace.contract, dueAt + 61_000)
  assert.equal(pastGrace.triggerStrictNow, true)
  assert.equal(pastGrace.newlyEscalated, true)
  assert.equal(pastGrace.contract.checkpoints[0].status, 'overdue_locked')

  // 高强度视频提炼验收：敷衍短文本退回，含因果结构过关并解除严格态
  const badSub = evaluateCheckpointSubmission(pastGrace.contract, { checkpointId: 'cp1', summary: '看完了', aiConfirmed: false })
  assert.equal(badSub.passed, false)
  assert.equal(badSub.needsInteractive, true)

  const goodSub = evaluateCheckpointSubmission(pastGrace.contract, {
    checkpointId: 'cp1',
    summary: '因为材料主体是基层执法，所以对策必须拆成制度约束→激励闭环→群众监督三步，核心结论是不能一刀切。',
    aiConfirmed: true,
    now: dueAt + 90_000,
  })
  assert.equal(goodSub.passed, true)
  assert.equal(goodSub.contract.strictEscalated, false)
})

test('horizons bidirectional Markdown sync + dept_skills red-flag audit + today charts', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-v4-'))
  try {
    const mdPath = join(dir, '长期规划.md')
    writeFileSync(mdPath, '# 长期规划\n- [ ] 拿下省考上岸\n', 'utf8')
    let h = syncLongGoalsFromMarkdown(cleanHorizons(null), mdPath)
    assert.equal(h.long.length, 1)
    assert.equal(h.long[0].title, '拿下省考上岸')

    const up = upsertHorizonItem(h, { horizon: 'medium', title: '行测资料分析 200 题', targetSteps: 5 })
    assert.equal(up.ok, true)
    mirrorHorizonsToMarkdown(up.state, mdPath)

    // 红旗防骗拦截：防止 80 元私聊定金骗局
    const scam = runRedFlagScamAudit('对方让我私聊微信先付定金80元，不退不换，马上涨价', { amount: 80, platformGuarantee: false })
    assert.equal(scam.verdict, 'BLOCK_RED_FLAG')
    assert.ok(scam.redFlags.length >= 2)

    // 反沉没成本审计：折腾工具告警
    const align = runMainlineAlignmentAudit('我想再重写一套软件UI配置提示词', { longGoals: h.long, toolTinkerMinToday: 60 })
    assert.equal(align.isToolEscapeRisk, true)

    // 因果链提炼
    const card = compileCausalChainCard({
      trigger: '看到低价私聊交易',
      mechanism: '绕开担保平台后违约成本为零',
      rule: '凡私下转账且不退不换，一律零秒关闭聊天框',
    })
    assert.equal(card.ok, true)

    // 今日图表汇总
    const charts = buildTodayCharts({
      usageReport: {
        rows: [
          { app: 'md.obsidian', label: 'Obsidian', minutes: 50, fun: false },
          { app: 'tv.danmaku.bili', label: '哔哩哔哩', minutes: 40, fun: false },
          { app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 20, fun: true },
        ],
        funMinutes: 20,
      },
      ledger: [{ state: 'bout-close', at: Date.now(), title: '行测套卷一', minutes: 45 }],
    })
    assert.equal(charts.buckets.mainlineMin, 50)
    assert.equal(charts.buckets.videoStudyMin, 40)
    assert.equal(charts.buckets.funMin, 20)
    assert.equal(charts.sevenDayTrend.length, 7)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('plan presets + AI prompt scripts + contractSessionView strict escalation loop', () => {
  assert.ok(COMMON_PLAN_PRESETS.length >= 5)
  assert.ok(AI_PROMPT_SCRIPTS.length >= 6)
  for (const p of COMMON_PLAN_PRESETS) {
    assert.ok(p.id && p.title && p.copyPrompt && p.minutes > 0)
  }

  const videoAllowFlex = resolveContractSessionAllow({ allowedApps: ['tv.danmaku.bili', 'md.obsidian'] }, { escalated: false })
  assert.deepEqual(videoAllowFlex, ['tv.danmaku.bili', 'md.obsidian'])

  const videoAllowEscalated = resolveContractSessionAllow({ allowedApps: ['tv.danmaku.bili', 'md.obsidian'] }, { escalated: true })
  assert.deepEqual(videoAllowEscalated, ['md.obsidian'])

  // 完整 V4Hub 契约联动闭环测试
  const store = new Map()
  let strictOffCalled = false
  const hub = createV4Hub({
    stateFile: (n) => n,
    readState: (k, d) => store.get(k) ?? d,
    writeJson: (k, v) => store.set(k, v),
    home: () => '',
    notesDir: () => '',
    kbDir: () => '',
    getConfig: () => ({ timeZone: 'Asia/Shanghai', defaultAllow: ['md.obsidian'] }),
    getLastPhone: () => ({ today: [] }),
    getLastRolledDice: () => 0,
    appsOf: () => [{ app: 'tv.danmaku.bili', label: '哔哩哔哩', role: 'fun' }, { app: 'md.obsidian', label: 'Obsidian', role: 'work' }],
    emergencyPackages: () => ['com.android.phone'],
    usageReportView: () => ({ rows: [], funMinutes: 0, totalMinutes: 60 }),
    readLedger: () => [],
    record: () => {},
    boutsGetRoute: () => ({ running: null, queue: [] }),
    planDayRoute: () => ({ plan: { items: [] }, recent: [] }),
    handoffRoute: () => ({ hasCognitiveOs: false, modelsCount: 0 }),
    dayString: () => '2026-11-18',
    zoneOr: (z) => z || 'Asia/Shanghai',
    presetRt: { strictNow: () => {}, strictOff: () => { strictOffCalled = true } },
    grantTempPass: () => {},
    toolOutput: {},
  })

  const t0 = Date.now()
  const created = hub.planContractRoute({ action: 'set', presetId: 'video_extract_study' })
  assert.equal(created.ok, true)

  // 契约期内：柔性护航，放行哔哩哔哩
  const flexSess = hub.contractSessionView(t0 + 10_000, { escalatedOnly: false })
  assert.equal(flexSess?.strict, false)
  assert.equal(flexSess?.allow.includes('tv.danmaku.bili'), true)

  // 逾期超过 1 分钟：升级为严格锁，且自动剥离视频软件、保留 Obsidian 提交验收
  const due = created.contract.checkpoints[0].dueAt
  const escSess = hub.contractSessionView(due + OVERDUE_GRACE_MS + 5_000, { escalatedOnly: true })
  assert.equal(escSess?.strict, true)
  assert.equal(escSess?.allow.includes('md.obsidian'), true)
  assert.equal(escSess?.allow.includes('tv.danmaku.bili'), false)

  // 提交高强度因果提炼通过后：自动解除严格态
  const verified = hub.planContractRoute({
    action: 'verify',
    checkpointId: created.contract.checkpoints[0].id,
    summary: '因为题干考察增量比较，所以核心结论是现期增长量对比基期更可靠，下次优先看柱状趋势。',
    aiConfirmed: true,
  })
  assert.equal(verified.passed, true)
  assert.equal(strictOffCalled, true)
  assert.equal(hub.contractSessionView(due + OVERDUE_GRACE_MS + 6_000), null)
})
