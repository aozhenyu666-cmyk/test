// 2026-11-22 阶段 1+2 温和 JS 纯逻辑单元测试（4.3 / 4.5 / 4.6）
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_NEUTRAL_WORK_QUOTA_MIN,
  NEUTRAL_COMM_PACKAGES,
  TASK_APP_SCOPES,
  WORK_DEFAULT,
  isBasePkg,
  resolveScopeApps,
  tierOf,
} from '../lib/app_tiers.js'
import { buildLockDecision, decideApp } from '../lib/lock_decision.js'
import { computeGeminiGentleAccounting } from '../lib/gemini.js'

const tf = (p, l) => tierOf(p, { label: l })
const base = (p) => isBasePkg(p)

const sampleInstalled = [
  { pkg: 'com.fenbi.android.solar', label: '粉笔' },
  { pkg: 'com.ichi2.anki', label: 'Anki' },
  { pkg: 'com.youdao.dict', label: '有道词典' },
  { pkg: 'md.obsidian', label: 'Obsidian' },
  { pkg: 'cn.wps.moffice_eng', label: 'WPS Office' },
  { pkg: 'com.baidu.netdisk', label: '百度网盘' },
  { pkg: 'com.google.android.apps.bard', label: 'Gemini' },
  { pkg: 'com.tencent.mm', label: '微信' },
  { pkg: 'com.tencent.mobileqq', label: 'QQ' },
  { pkg: 'com.ss.android.ugc.aweme', label: '抖音' },
  { pkg: 'com.tencent.tmgp.sgame', label: '王者荣耀' },
  { pkg: 'com.custom.quizgame', label: '刷题王者游戏' },
]

test('[4.3] WORK_DEFAULT 保留 Gemini；常量与 TASK_APP_SCOPES 完整导出', () => {
  assert.equal(WORK_DEFAULT.includes('com.google.android.apps.bard'), true)
  assert.deepEqual(NEUTRAL_COMM_PACKAGES, ['com.tencent.mm', 'com.tencent.mobileqq'])
  assert.equal(DEFAULT_NEUTRAL_WORK_QUOTA_MIN, 35)
  for (const k of ['exam', 'write', 'think', 'default']) {
    assert.ok(TASK_APP_SCOPES[k], `缺少 TASK_APP_SCOPES.${k}`)
    assert.ok(Array.isArray(TASK_APP_SCOPES[k].packages))
  }
})

test('[4.3] resolveScopeApps 按任务标题/类型归类并硬过滤 fun/game', () => {
  const exam = resolveScopeApps('行测刷题 30 道', {
    installed: sampleInstalled,
    extra: ['com.ss.android.ugc.aweme', 'com.tencent.tmgp.sgame', 'com.custom.study'],
  })
  assert.equal(exam.scopeKey, 'exam')
  assert.equal(exam.scopeLabel, '刷题/行测')
  assert.ok(exam.allow.includes('com.fenbi.android.solar'))
  assert.ok(exam.allow.includes('com.ichi2.anki'))
  assert.ok(exam.allow.includes('com.youdao.dict'))
  assert.ok(exam.allow.includes('com.google.android.apps.bard'))
  assert.ok(exam.allow.includes('com.custom.study'))
  assert.equal(exam.allow.includes('com.ss.android.ugc.aweme'), false, '娱乐 App 永远加不进')
  assert.equal(exam.allow.includes('com.tencent.tmgp.sgame'), false, '游戏 App 永远加不进')
  assert.equal(exam.allow.includes('com.custom.quizgame'), false, '带游戏关键词的伪刷题 App 被硬过滤')

  const write = resolveScopeApps('申论大作文写作与笔记整理', {
    installed: sampleInstalled,
    overrides: { 'com.baidu.netdisk': 'fun' },
  })
  assert.equal(write.scopeKey, 'write')
  assert.equal(write.scopeLabel, '写作/笔记')
  assert.ok(write.allow.includes('md.obsidian'))
  assert.ok(write.allow.includes('cn.wps.moffice_eng'))
  assert.equal(write.allow.includes('com.baidu.netdisk'), false, '被 overrides 标成 fun 的网盘也必须硬过滤')

  const think = resolveScopeApps('和 Gemini 深度共脑岗位选择', { installed: sampleInstalled })
  assert.equal(think.scopeKey, 'think')
  assert.equal(think.scopeLabel, '深度共脑(含Gemini)')
  assert.ok(think.allow.includes('com.google.android.apps.bard'))
  assert.ok(think.allow.includes('md.obsidian'))

  const def = resolveScopeApps('杂项整理', { installed: sampleInstalled })
  assert.equal(def.scopeKey, 'default')
  assert.equal(def.scopeLabel, '常规学习')
  assert.ok(def.allow.includes('com.google.android.apps.bard'))
})

test('[4.6] buildLockDecision：P4/P5 干活态下微信+QQ 共享 35m 总量放行，超额后落入 needPass', () => {
  const arb = (priority, source) => ({ priority, source, label: priority, why: '专注中', allow: ['md.obsidian'] })

  // 1) 未超 35m（微信 15 + QQ 10 = 25m）-> P5 干活态自动放行
  const underP5 = buildLockDecision({
    arbiter: arb('P5', 'CONTRACT_FLEX'),
    work: { active: true, allow: ['md.obsidian'] },
    installed: sampleInstalled,
    tierOf: tf,
    today: [
      { app: 'com.tencent.mm', minutes: 15 },
      { app: 'com.tencent.mobileqq', minutes: 10 },
    ],
  })
  assert.deepEqual(underP5.commQuota, { usedMin: 25, limitMin: 35, exceeded: false })
  assert.equal(decideApp(underP5, 'com.tencent.mm', base).lock, false)
  assert.equal(decideApp(underP5, 'com.tencent.mobileqq', base).lock, false)
  assert.equal(underP5.needPass.includes('com.tencent.mm'), false)

  // 2) 未超 35m -> P4 BOUT_STRICT 同样自动放行
  const underP4 = buildLockDecision({
    arbiter: arb('P4', 'BOUT_STRICT'),
    installed: sampleInstalled,
    tierOf: tf,
    today: [{ app: 'com.tencent.mm', minutes: 34 }],
  })
  assert.equal(underP4.commQuota.exceeded, false)
  assert.equal(decideApp(underP4, 'com.tencent.mm', base).lock, false)
  assert.equal(decideApp(underP4, 'com.tencent.mobileqq', base).lock, false)

  // 3) 达到或超过 35m（20 + 15 = 35m）-> 不再自动放行，落入 needPass
  const overP4 = buildLockDecision({
    arbiter: arb('P4', 'BOUT_STRICT'),
    installed: sampleInstalled,
    tierOf: tf,
    today: [
      { app: 'com.tencent.mm', minutes: 20 },
      { app: 'com.tencent.mobileqq', minutes: 15 },
    ],
  })
  assert.deepEqual(overP4.commQuota, { usedMin: 35, limitMin: 35, exceeded: true })
  const wxOver = decideApp(overP4, 'com.tencent.mm', base)
  assert.equal(wxOver.lock, true)
  assert.equal(wxOver.pass, true)
  assert.equal(overP4.needPass.includes('com.tencent.mobileqq'), true)

  // 4) P4 超额后若有通行证 lease 仍放行
  const withLease = buildLockDecision({
    arbiter: arb('P4', 'BOUT_STRICT'),
    installed: sampleInstalled,
    tierOf: tf,
    today: [{ app: 'com.tencent.mm', minutes: 40 }],
    lease: { app: 'com.tencent.mm', until: Date.now() + 300_000, goal: '回工作消息' },
  })
  assert.equal(decideApp(withLease, 'com.tencent.mm', base).lock, false)
  assert.equal(decideApp(withLease, 'com.tencent.mobileqq', base).pass, true)
})

test('[4.6] 中立通讯放行受 radarDeny / overrides(fun|game) / 严格层级(P1-P3.5) 约束', () => {
  const now = Date.now()
  const arb = (priority, source) => ({ priority, source, label: priority, why: '严格', allow: [] })

  // radarDeny 临时黑名单拦截微信，不影响 QQ
  const denied = buildLockDecision({
    arbiter: arb('P5', 'CONTRACT_FLEX'),
    work: { active: true },
    installed: sampleInstalled,
    tierOf: tf,
    today: [{ app: 'com.tencent.mm', minutes: 5 }],
    radarDeny: [{ app: 'com.tencent.mm', until: now + 60_000, why: '临时封禁' }],
    now,
  })
  assert.equal(denied.allow.includes('com.tencent.mm'), false)
  assert.equal(denied.allow.includes('com.tencent.mobileqq'), true)

  // overrides 把微信标成 fun：绝不自动放行，也不给通行证提示
  const overFun = buildLockDecision({
    arbiter: arb('P5', 'CONTRACT_FLEX'),
    work: { active: true },
    installed: sampleInstalled,
    tierOf: (p, l) => tierOf(p, { label: l, overrides: { 'com.tencent.mm': 'fun' } }),
    today: [],
  })
  assert.equal(overFun.allow.includes('com.tencent.mm'), false)
  assert.equal(overFun.needPass.includes('com.tencent.mm'), false)
  assert.equal(decideApp(overFun, 'com.tencent.mm', base).lock, true)

  // P1 / P2 / P3 / P3.5 不走通讯自动放行
  for (const [p, s] of [['P1', 'NIGHT_CURFEW'], ['P2', 'GEMINI_LIVE_CELL'], ['P3', 'CONTRACT_OVERDUE_STRICT'], ['P3.5', 'TAKEOVER_STRICT']]) {
    const d = buildLockDecision({
      arbiter: arb(p, s),
      installed: sampleInstalled,
      tierOf: tf,
      today: [],
    })
    assert.equal(d.allow.includes('com.tencent.mm'), false, `${p} 不应自动放行微信`)
  }
})

test('[4.5] computeGeminiGentleAccounting 温和记录与保底达标自由模式', () => {
  const r1 = computeGeminiGentleAccounting({
    geminiMinutesToday: 25,
    receiptsToday: [{ verdict: 'pass' }],
    floorMetToday: false,
  })
  assert.deepEqual(r1, {
    totalMin: 25,
    withReceiptMin: 20,
    unreceiptedMin: 5,
    receiptCount: 1,
    freeMode: false,
    summaryLine: '今日 Gemini 共 25m（有回执学习约 20m · 无回执 5m）',
  })

  const r2 = computeGeminiGentleAccounting({
    geminiMinutesToday: 25,
    receiptsToday: [{ verdict: 'pass' }, { verdict: 'partial' }, { verdict: 'miss' }],
    floorMetToday: false,
  })
  assert.equal(r2.withReceiptMin, 25, '不超过 totalMin')
  assert.equal(r2.unreceiptedMin, 0)
  assert.equal(r2.receiptCount, 2)

  const r3 = computeGeminiGentleAccounting({
    geminiMinutesToday: 25,
    receiptsToday: [],
    floorMetToday: true,
  })
  assert.equal(r3.freeMode, true)
  assert.equal(r3.summaryLine, '今日保底已达标 · Gemini 自由探索中（已用 25m）')

  const r4 = computeGeminiGentleAccounting({ geminiMinutesToday: -5, receiptsToday: null })
  assert.equal(r4.totalMin, 0)
  assert.equal(r4.withReceiptMin, 0)
  assert.equal(r4.unreceiptedMin, 0)
})
