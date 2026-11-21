import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'

import { digestOf, emptyState, KEEP_DIGEST_DAYS, observe, rollDay } from '../lib/episodes.js'
import {
  buildAnalyticsOverview,
  generateEveningReport,
  shouldAutoTriggerEveningReport,
  trackDailyGemini,
  upsertHistoryDay,
} from '../lib/analytics_center.js'
import {
  appendStorylineEvent,
  formatIntradayPromptBlock,
  MAX_PROMPT_CHARS,
  MAX_STORYLINE_ENTRIES,
  normalizeStoryline,
  setPullMode,
} from '../lib/intraday_storyline.js'
import {
  computeUnifiedLockArbiter,
  computeUnifiedTodayTasks,
  resolveActiveSisterSkin,
} from '../lib/unified_arbiter.js'
import { buildPrompt } from '../lib/prompt.js'
import { apply } from '../lib/index.js'

const MIN = 60_000
const HOUR = 3_600_000
const DAY0 = Date.UTC(2026, 10, 17, 16, 0, 0) // 2026-11-18 00:00 Asia/Shanghai
const bj = (dayIndex, h, m = 0) => DAY0 + dayIndex * 86_400_000 + h * HOUR + m * MIN
const dayOf = (ms) => new Date(ms + 8 * HOUR).toISOString().slice(0, 10)
const minuteOf = (ms) => {
  const d = new Date(ms + 8 * HOUR)
  return d.getUTCHours() * 60 + d.getUTCMinutes()
}
const hourOf = (ms) => Math.floor(minuteOf(ms) / 60)

test('episodes.js: KEEP_DIGEST_DAYS=365 and digestOf preserves non-fun focusMin, workByHour, geminiMin, byAppAll across rollDay', () => {
  assert.equal(KEEP_DIGEST_DAYS, 365)
  let s = emptyState()
  const snaps = [
    { now: bj(0, 9, 0), day: '2026-11-18', minute: 540, app: 'md.obsidian', fun: false, minutes: 0, screenOn: true },
    { now: bj(0, 9, 45), day: '2026-11-18', minute: 585, app: 'com.google.android.apps.bard', fun: false, minutes: 0, screenOn: true },
    { now: bj(0, 10, 10), day: '2026-11-18', minute: 610, app: 'com.ss.android.ugc.aweme', fun: true, minutes: 0, screenOn: true },
    { now: bj(0, 10, 25), day: '2026-11-18', minute: 625, app: 'com.miui.home', fun: false, minutes: 0, screenOn: true },
  ]
  for (const x of snaps) s = observe(s, x).state
  s = rollDay(s, { day: '2026-11-18', now: bj(0, 9, 0), hourOf }).state

  const d = digestOf(s, '2026-11-18', { now: bj(0, 10, 30), hourOf })
  assert.equal(d.funMin, 15)
  assert.equal(d.focusMin, 70) // 45m obsidian + 25m gemini
  assert.equal(d.geminiMin, 25)
  assert.equal(d.workByHour[9], 60)
  assert.equal(d.workByHour[10], 10)
  assert.equal(d.funByHour[10], 15)
  assert.equal(d.byAppAll.length, 3)
  assert.equal(d.byAppAll[0].app, 'md.obsidian')
  assert.equal(d.byAppAll[0].min, 45)
  assert.equal(d.byAppAll[0].fun, false)

  const rolled = rollDay(s, { day: '2026-11-19', now: bj(1, 8, 0), hourOf })
  assert.equal(rolled.finalized.focusMin, 70)
  assert.equal(rolled.finalized.geminiMin, 25)
  assert.equal(rolled.state.digests['2026-11-18'].focusMin, 70)
})

test('analytics_center: Gemini tracking, 90d heatmap, 24h timeline & evening report markdown generation', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dsh-v5-analytics-'))
  try {
    let ep = emptyState()
    ep = observe(ep, { now: bj(0, 9, 0), day: '2026-11-18', minute: 540, app: 'md.obsidian', fun: false, screenOn: true }).state
    ep = observe(ep, { now: bj(0, 9, 50), day: '2026-11-18', minute: 590, app: 'com.google.android.apps.bard', fun: false, screenOn: true }).state
    ep = observe(ep, { now: bj(0, 10, 15), day: '2026-11-18', minute: 615, app: 'com.ss.android.ugc.aweme', fun: true, screenOn: true }).state
    ep = observe(ep, { now: bj(0, 10, 30), day: '2026-11-18', minute: 630, app: 'com.miui.home', fun: false, screenOn: true }).state

    const geminiState = {
      recaps: [{ at: bj(0, 10, 16), topic: '资料分析基期', summary: '理清了截位直除边界', next: '做20题', minutes: 25 }],
      pulls: [{ at: bj(0, 9, 49), app: 'com.ss.android.ugc.aweme', minutes: 10 }],
    }
    const gStats = trackDailyGemini({
      phone: { geminiMinutesToday: 28, today: [{ app: 'com.google.android.apps.bard', minutes: 28 }] },
      episodesState: ep,
      geminiState,
      day: '2026-11-18',
      now: bj(0, 21, 35),
      tz: 'Asia/Shanghai',
    })
    assert.equal(gStats.minutesToday, 28)
    assert.equal(gStats.recapsCount, 1)
    assert.ok(gStats.sessionsCount >= 1)

    const hist = upsertHistoryDay(null, {
      day: '2026-11-17',
      buckets: { mainlineMin: 40, videoStudyMin: 20, geminiMin: 10, toolTinkerMin: 5, funMin: 30, totalMin: 105 },
    })

    const overview = buildAnalyticsOverview({
      day: '2026-11-18',
      rangeDays: 30,
      episodesState: ep,
      historyStore: hist,
      usageReport: {
        totalMinutes: 113,
        funMinutes: 15,
        rows: [
          { app: 'md.obsidian', label: 'Obsidian', minutes: 50, fun: false },
          { app: 'com.google.android.apps.bard', label: 'Gemini', minutes: 28, fun: false },
          { app: 'tv.danmaku.bili', label: 'B站', minutes: 20, fun: false },
          { app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 15, fun: true },
        ],
      },
      geminiStats: gStats,
      now: bj(0, 21, 35),
      tz: 'Asia/Shanghai',
    })

    assert.equal(overview.v, 1)
    assert.equal(overview.ok, true)
    assert.equal(overview.buckets.mainlineMin, 50)
    assert.equal(overview.buckets.geminiMin, 28)
    assert.equal(overview.buckets.videoStudyMin, 20)
    assert.equal(overview.buckets.funMin, 15)
    assert.equal(overview.deltas.mainlineDelta, 10)
    assert.equal(overview.deltas.geminiDelta, 18)
    assert.equal(overview.deltas.funDelta, -15)
    assert.equal(overview.historySeries.length, 30)
    assert.equal(overview.heatmap90d.length, 90)
    assert.ok(overview.heatmap90d[89].level >= 2)
    assert.equal(overview.timeline24h.length, 3)
    assert.equal(overview.timeline24h[1].bucket, 'gemini_cobrain')

    assert.equal(shouldAutoTriggerEveningReport(null, { now: bj(0, 21, 29), tz: 'Asia/Shanghai' }), false)
    assert.equal(shouldAutoTriggerEveningReport(null, { now: bj(0, 21, 30), tz: 'Asia/Shanghai' }), true)

    const report = generateEveningReport({
      day: '2026-11-18',
      episodesState: ep,
      historyStore: hist,
      usageReport: {
        totalMinutes: 113,
        funMinutes: 15,
        rows: [
          { app: 'md.obsidian', label: 'Obsidian', minutes: 50, fun: false },
          { app: 'com.google.android.apps.bard', label: 'Gemini', minutes: 28, fun: false },
        ],
      },
      geminiStats: gStats,
      horizons: { medium: [{ id: 'm1', title: '资料分析200题', doneSteps: 2, targetSteps: 5, status: 'active' }] },
      kbDirPath: dir,
      now: bj(0, 21, 35),
      tz: 'Asia/Shanghai',
    })
    assert.ok(report.score > 0 && report.score <= 100)
    assert.match(report.mermaidPie, /pie title 2026-11-18/)
    assert.equal(report.tomorrowDraft.length, 3)
    const mdFile = join(dir, '每日报告', '2026-11-18.md')
    assert.equal(existsSync(mdFile), true)
    assert.match(readFileSync(mdFile, 'utf8'), /资料分析200题/)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('intraday_storyline: 18-entry cap, day reset keeping pullMode, prompt injection <= 450 chars', () => {
  let store = normalizeStoryline(null, { now: bj(0, 8, 0), tz: 'Asia/Shanghai' })
  assert.equal(store.pullMode, 'countdown_3s')
  store = setPullMode(store, 'instant', { now: bj(0, 8, 5), tz: 'Asia/Shanghai' })

  for (let i = 0; i < 22; i += 1) {
    store = appendStorylineEvent(store, {
      kind: 'user_reply',
      actor: 'user',
      summary: `第${i + 1}条连续工作流事件记录：推进资料分析与申论写作复盘验证_${'x'.repeat(80)}`,
      at: bj(0, 9, i),
    }, { now: bj(0, 9, i), tz: 'Asia/Shanghai' })
  }
  assert.equal(store.entries.length, MAX_STORYLINE_ENTRIES)
  assert.ok(store.entries[0].summary.length <= 80)

  const block = formatIntradayPromptBlock(store, bj(0, 12, 0), 'Asia/Shanghai')
  assert.ok(block.startsWith('【今日连续脉络（接着聊，勿断片）】'))
  assert.ok(block.length <= MAX_PROMPT_CHARS)

  // Inject into both strategist and companion buildPrompt
  const pComp = buildPrompt({ role: 'companion', intraday: block, topic: { kind: 'share', text: '测试' } })
  const pStrat = buildPrompt({ role: 'strategist', intraday: block })
  assert.ok(pComp.includes('今日连续脉络'))
  assert.ok(pStrat.includes('今日连续脉络'))

  // Day rollover resets entries but preserves pullMode
  const nextDay = normalizeStoryline(store, { now: bj(1, 8, 0), tz: 'Asia/Shanghai' })
  assert.equal(nextDay.day, '2026-11-19')
  assert.equal(nextDay.entries.length, 0)
  assert.equal(nextDay.pullMode, 'instant')
})

test('unified_arbiter: P0-P6 lock priority, 5-queue deduplication, and sister skin sync', () => {
  const now = bj(0, 14, 0)
  // P0 overrides everything
  const p0 = computeUnifiedLockArbiter({
    emergencyPass: { active: true, until: now + 180_000, reason: '急事通行' },
    geminiSession: { until: now + 600_000, topic: '连麦' },
    contractSession: { strict: true, until: now + 600_000 },
    now,
  })
  assert.equal(p0.priority, 'P0')
  assert.equal(p0.source, 'EMERGENCY_SUSPEND')
  assert.equal(p0.active, false)

  // P0 > P1(NIGHT) > P2(GEMINI) > P3(CONTRACT) > P3.5(TAKEOVER) > P4(BOUT) > P5 > P6
  const p1Night = computeUnifiedLockArbiter({
    wall: { locked: true, kind: 'night' },
    geminiSession: { until: now + 600_000, topic: '连麦' },
    presetStrict: { until: now + 600_000, allow: ['md.obsidian'], why: '严格局' },
    now,
  })
  assert.equal(p1Night.priority, 'P1')
  assert.equal(p1Night.source, 'NIGHT_CURFEW')

  const p2Gem = computeUnifiedLockArbiter({
    geminiSession: { until: now + 600_000, topic: '申论审题', allow: ['com.google.android.apps.bard'] },
    contractSession: { strict: true, until: now + 600_000 },
    now,
  })
  assert.equal(p2Gem.priority, 'P2')
  assert.equal(p2Gem.source, 'GEMINI_LIVE_CELL')
  assert.equal(resolveActiveSisterSkin({ speaker: 'companion', lockArbiter: p2Gem }), 'jinglan-girl')

  const p3Contract = computeUnifiedLockArbiter({
    contractSession: { strict: true, until: now + 600_000, allow: ['md.obsidian'] },
    presetStrict: { until: now + 600_000, allow: ['md.obsidian'] },
    now,
  })
  assert.equal(p3Contract.priority, 'P3')
  assert.equal(p3Contract.source, 'CONTRACT_OVERDUE_STRICT')
  assert.equal(resolveActiveSisterSkin({ speaker: 'companion', lockArbiter: p3Contract }), 'jinglan-girl')

  const p4Bout = computeUnifiedLockArbiter({
    presetStrict: { until: now + 600_000, allow: ['md.obsidian'], why: '严格局' },
    now,
  })
  assert.equal(p4Bout.priority, 'P4')
  assert.equal(p4Bout.source, 'BOUT_STRICT')
  assert.equal(resolveActiveSisterSkin({ speaker: 'companion', lockArbiter: p4Bout }), 'yuwan-girl')

  const p5 = computeUnifiedLockArbiter({
    contractSession: { strict: false, until: now + 600_000, allow: ['tv.danmaku.bili', 'md.obsidian'] },
    now,
  })
  assert.equal(p5.priority, 'P5')
  assert.equal(p5.source, 'CONTRACT_FLEX')

  const p6 = computeUnifiedLockArbiter({
    autonomy: { level: 'L3', unlockedRelaxation: true, autoRewardLabel: '已放宽' },
    now,
  })
  assert.equal(p6.priority, 'P6')
  assert.equal(p6.source, 'AUTONOMY_RELAXED')

  // 5-queue deduplication
  const unified = computeUnifiedTodayTasks({
    planItems: [{ id: 'p1', title: '资料分析200题', minutes: 45 }],
    dailyTasks: [{ id: 't1', task: '资料分析 200 题', minutes: 30, dept: '谋划思考' }, { id: 't2', task: '修改简历', minutes: 40 }],
    boutQueue: [{ id: 'b1', title: '资料分析200题', parentHorizonId: 'm1', minutes: 50 }],
    contract: { id: 'c1', title: '申论提炼', checkpoints: [{ id: 'cp1', title: '申论视频提炼', status: 'pending' }] },
    horizons: { medium: [{ id: 'm1', title: '行测冲刺战役' }], long: [] },
  })
  assert.equal(unified.length, 3)
  const merged = unified.find((x) => x.title.includes('资料分析'))
  assert.deepEqual(merged.sources.sort(), ['bout', 'daily', 'plan'])
  assert.equal(merged.minutes, 50)
  assert.equal(merged.horizonTitle, '行测冲刺战役')
})

function makeHttpHost({ home, agent }) {
  let handler
  const globalTools = {}
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      if (name === 'tools') return { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } }
      return undefined
    },
    on() {},
    inject(_deps, cb) {
      cb({
        webServer: { register(route) { handler = route.handler } },
        tools: { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } },
        effect(fn) { fn() },
      })
    },
    effect(fn) { fn() },
  }
  apply(ctx)
  return { handler, globalTools }
}

function callHttp(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0,
    text: '',
    writeHead(status) { this.statusCode = status },
    end(chunk) { this.text = chunk ? String(chunk) : '' },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

test('HTTP routes (/analytics, /storyline, /today, /plan geminiAutoPull) and V5 AI tools integration', async () => {
  const home = mkdtempSync(join(tmpdir(), 'dsh-v5-http-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    const memDir = join(home, 'fish-memory')
    mkdirSync(memDir, { recursive: true })
    const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
    db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
    db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
    db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
    db.close()

    const cfgDir = join(home, 'companion')
    mkdirSync(cfgDir, { recursive: true })
    writeFileSync(join(cfgDir, 'config.json'), JSON.stringify({
      sessionId: 'sis-1',
      strategistSessionId: 'big-1',
      quietStart: 2,
      quietEnd: 7,
      minGapMs: 0,
      proactiveTools: true,
      gear: 'busy',
      gearWhat: '行测资料分析',
      gearUntil: Date.now() + 60 * 60_000,
    }))

    const agent = { id: 'sis-1', status: 'idle', inbox: { hasPending: false }, followup() {} }
    const { handler, globalTools } = makeHttpHost({ home, agent })

    // 1. Storyline GET/POST
    const st1 = await callHttp(handler, 'POST', '/dsh-go-companion/v1/storyline', {
      action: 'append',
      kind: 'morning_anchor',
      actor: 'user',
      summary: '今日主线：拿下资料分析200题',
    })
    assert.equal(st1.status, 200)
    assert.equal(st1.body.ok, true)
    assert.match(st1.body.promptBlock, /资料分析200题/)

    const stGet = await callHttp(handler, 'GET', '/dsh-go-companion/v1/storyline')
    assert.equal(stGet.status, 200)
    assert.equal(stGet.body.pullMode, 'countdown_3s')

    // 2. /plan triggers geminiAutoPull on >=8m fun dwell during busy work
    // 安静时段（默认 2–7 点，按服务端时钟）里本来就不自动拉 Gemini；关掉它，测试才与 CI 跑的时刻无关。
    await callHttp(handler, 'POST', '/dsh-go-companion/v1/config', { quietStart: 12, quietEnd: 12 })
    const planRes = await callHttp(handler, 'POST', '/dsh-go-companion/v1/plan', {
      v: 1,
      phone: {
        app: 'com.ss.android.ugc.aweme',
        label: '抖音',
        minutes: 8,
        fun: true,
        screenOn: true,
        local: '14:20',
        geminiMinutesToday: 19,
        today: [
          { app: 'md.obsidian', label: 'Obsidian', minutes: 45, fun: false },
          { app: 'com.google.android.apps.bard', label: 'Gemini', minutes: 19, fun: false },
          { app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 8, fun: true },
        ],
      },
    })
    assert.equal(planRes.status, 200)
    assert.equal(planRes.body.geminiMinutesToday, 19)
    assert.ok(planRes.body.lockArbiter?.priority)
    assert.ok(['yuwan-girl', 'jinglan-girl'].includes(planRes.body.activeSisterSkin))
    assert.equal(planRes.body.geminiAutoPull?.triggered, true)
    assert.match(planRes.body.geminiAutoPull?.brief || '', /今日连续脉络/)

    // 3. gentle pullMode suppresses auto pull
    const modeRes = await callHttp(handler, 'POST', '/dsh-go-companion/v1/storyline', {
      action: 'set_gemini_pull_mode',
      pullMode: 'gentle',
    })
    assert.equal(modeRes.body.pullMode, 'gentle')

    // 4. GET /today enriched
    const todayRes = await callHttp(handler, 'GET', '/dsh-go-companion/v1/today')
    assert.equal(todayRes.status, 200)
    assert.equal(todayRes.body.geminiMinutesToday, 19)
    assert.ok(todayRes.body.lockArbiter?.source)
    assert.ok(todayRes.body.activeSisterSkin)

    // 5. GET & POST /analytics
    const anGet = await callHttp(handler, 'GET', '/dsh-go-companion/v1/analytics?rangeDays=7')
    assert.equal(anGet.status, 200)
    assert.equal(anGet.body.ok, true)
    assert.equal(anGet.body.buckets.geminiMin, 19)
    assert.equal(anGet.body.historySeries.length, 7)
    assert.equal(anGet.body.heatmap90d.length, 90)

    const anPost = await callHttp(handler, 'POST', '/dsh-go-companion/v1/analytics', {
      action: 'generate_report',
    })
    assert.equal(anPost.status, 200)
    assert.equal(anPost.body.ok, true)
    assert.ok(anPost.body.report?.markdown)
    assert.match(anPost.body.report?.mermaidPie || '', /pie title/)

    // 6. V5 AI Tools: generate_evening_report & proactive_pull_gemini
    assert.ok(globalTools.generate_evening_report)
    assert.ok(globalTools.proactive_pull_gemini)
    const rptTool = globalTools.generate_evening_report.execute({})
    assert.equal(rptTool.ok, true)
    const pullTool = globalTools.proactive_pull_gemini.execute({ topic: '申论大作文框架', mode: 'think', minutes: 20 })
    assert.equal(pullTool.ok, true)
    assert.equal(pullTool.geminiAutoPull.triggered, true)
    // Next /plan consumes queued pendingAutoPull even in gentle mode
    const planAfterTool = await callHttp(handler, 'POST', '/dsh-go-companion/v1/plan', {
      v: 1,
      phone: { app: 'md.obsidian', minutes: 2, fun: false, screenOn: true, local: '14:25' },
    })
    assert.equal(planAfterTool.body.geminiAutoPull?.triggered, true)
    assert.equal(planAfterTool.body.geminiAutoPull?.reason, 'ai-tool')
    assert.equal(planAfterTool.body.lockArbiter?.source, 'GEMINI_LIVE_CELL')
    assert.equal(planAfterTool.body.activeSisterSkin, 'jinglan-girl')
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})
