import test from 'node:test'
import assert from 'node:assert/strict'

import { buildBossAuditBrief } from '../lib/wall_auditor.js'

test('buildBossAuditBrief aggregates 7d fulfillment, work vs fun, blocks/emergency, commitments, planBlocks & outputs cold supervisor prompt', () => {
  const now = Date.parse('2026-11-22T20:15:00+08:00')
  const res = buildBossAuditBrief({
    now,
    tz: 'Asia/Shanghai',
    todayStats: {
      buckets: {
        mainlineMin: 40,
        videoStudyMin: 20,
        geminiMin: 15,
        toolTinkerMin: 75,
        funMin: 80,
      },
      passLedger: { emergency: { used: 2, cap: 3 } },
      blockedCount: 4,
    },
    seals: {
      items: [
        { day: '2026-11-20', status: 'floor' },
        { day: '2026-11-21', status: 'below' },
        { day: '2026-11-22', status: 'below' },
        { day: '2026-11-10', status: 'stretch' }, // 超过 7 天窗口应忽略
      ],
    },
    locksOverview: {
      activeLock: {
        active: true,
        priority: 'P3',
        source: 'CONTRACT_OVERDUE_STRICT',
        label: '契约逾期严格锁',
        verifyOverdue: true,
      },
      wallet: { availableMin: 12 },
      recentActions: [
        { action: '临时拦截', target: '抖音', reason: '工作时段禁用' },
      ],
    },
    commitments: [
      { statement: '每晚 22:00 前完成 30 道资料分析', status: 'open' },
      { statement: '已交完的承诺', status: 'done' },
    ],
    planBlocks: {
      yesterday: [
        { title: '昨日申论提纲', status: 'pending' },
        { title: '昨日英语阅读', status: 'done' },
      ],
      today: [
        { title: '上午行测套卷', status: 'done' },
        { title: '下午模拟面试', status: 'skipped' },
        { title: '午休放风', slot: 'lunch', lockMode: 'gentle', isBreak: true, status: 'pending' },
      ],
    },
    recentBreaches: [
      { at: now - 3600_000, kind: 'emergency', reason: '强行开微信买东西' },
      { at: now - 1800_000, kind: 'block', target: 'B站', reason: '撞锁被拦' },
    ],
  })

  assert.equal(res.ok, true)
  assert.equal(res.v, 1)
  assert.equal(res.day, '2026-11-22')
  assert.equal(res.snapshot.todayWorkMin, 75) // 40 + 20 + 15
  assert.equal(res.snapshot.todayFunMin, 80)
  assert.equal(res.snapshot.todayTinkerMin, 75)
  assert.equal(res.snapshot.blockedCount, 4)
  assert.equal(res.snapshot.emergencyPassCount, 2)
  assert.equal(res.snapshot.pendingCommitmentsCount, 1)
  assert.equal(res.snapshot.sealStats7d.total, 3) // 11-10 excluded
  assert.equal(res.snapshot.sealStats7d.done, 1)
  assert.equal(res.snapshot.planBlocksSummary.yesterdayUnfinished, 1)
  assert.equal(res.snapshot.planBlocksSummary.todaySkipped, 1)
  assert.equal(res.snapshot.planBlocksSummary.todayDone, 1)
  assert.equal(res.snapshot.activeLock.verifyOverdue, true)
  // combined: seals(1/3) + non-break blocks(yesterday: 1 done, 1 pending; today: 1 done, 1 skipped) = 3/7 -> 43%
  assert.equal(res.snapshot.fulfillmentRatePct, 43)

  assert.ok(res.keyGaps.length >= 4)
  assert.match(res.headline, /43%/)
  assert.match(res.bossPrompt, /先核查下方客观全局账本|dafeiyu_today/)
  assert.match(res.bossPrompt, /3 个直击全局逃避模式的尖锐追问/)
  assert.match(res.bossPrompt, /每晚 22:00 前完成 30 道资料分析/)
  assert.doesNotMatch(res.bossPrompt, /加油|你可以的|很棒/)
})

test('buildBossAuditBrief handles empty or minimal inputs safely without throwing', () => {
  const res = buildBossAuditBrief({ now: Date.parse('2026-11-22T09:00:00+08:00') })
  assert.equal(res.ok, true)
  assert.equal(res.snapshot.todayWorkMin, 0)
  assert.equal(res.snapshot.todayFunMin, 0)
  assert.equal(res.snapshot.fulfillmentRatePct, null)
  assert.ok(res.bossPrompt.length > 100)
})
