import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { LIVE_CORE_DEFAULTS, LIVE_DICE_FACES, buildLiveCoreAtlas, buildLiveCoreBrief, buildSisterFeed, evaluateLiveCore, formatLiveCoreHistory } from '../lib/live_core.js'

const TZ = 'Asia/Shanghai'
const SIS = 'session-sis'
const BIG = 'session-big'

function agentOf(id) {
  const sent = []
  const tools = {}
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    sent,
    tools,
    followup(m) { sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents, state = 'brain') {
  let handler
  const ctx = {
    get(n) {
      if (n === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (n === 'connection') return { requestRejection: () => undefined }
      if (n === 'sessionController') return { resolveAgent: async ({ sessionId }) => ({ agent: agents[sessionId || SIS] }) }
      if (n === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    tools: { register() { return () => {} } },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(f) { f() } }) },
    effect(f) { f() },
  }
  setStatePin(state)
  apply(ctx)
  return () => handler
}

async function call(handler, method, path, body) {
  const raw = body === undefined ? '' : JSON.stringify(body)
  const req = Readable.from(raw ? [Buffer.from(raw)] : [])
  req.method = method
  req.url = `/dsh-go-companion/v1${path}`
  req.headers = raw ? { 'content-type': 'application/json' } : {}
  let statusCode = 0
  let out = ''
  const res = {
    writeHead(s) { statusCode = s },
    end(s) { out = String(s || '') },
  }
  await handler(req, res)
  return { status: statusCode, body: out ? JSON.parse(out) : null }
}

async function withHome(fn) {
  const root = mkdtempSync(join(tmpdir(), 'companion-livecore-'))
  const companionDir = join(root, 'companion')
  mkdirSync(join(root, 'plugins', 'dsh-go-companion'), { recursive: true })
  mkdirSync(join(root, 'fish-memory'), { recursive: true })
  mkdirSync(companionDir, { recursive: true })
  writeFileSync(join(companionDir, 'config.json'), JSON.stringify({
    sessionId: SIS,
    strategistSessionId: BIG,
    timeZone: TZ,
  }))
  const prevHome = process.env.DSH_HOME
  const prevTz = process.env.TZ
  process.env.DSH_HOME = root
  process.env.TZ = TZ
  try {
    await fn(root, companionDir)
  } finally {
    if (prevHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = prevHome
    if (prevTz === undefined) delete process.env.TZ
    else process.env.TZ = prevTz
    try {
      rmSync(root, { recursive: true, force: true })
    } catch {
      // Windows SQLite handle cleanup grace
    }
  }
}

test('LiveCore-1: 严格让路闸门在 P0/P1/P1.2/计划模态/刷题学习/已连麦时全部静默让路且零影子锁', () => {
  const now = 1_700_100_000_000
  const basePhone = { app: 'com.bbk.launcher2', label: '桌面', minutes: 6, screenOn: true, fun: false }

  const rEmergency = evaluateLiveCore({ now, phone: basePhone, arbiter: { priority: 'P0', source: 'EMERGENCY_SUSPEND' } })
  assert.equal(rEmergency.triggered, false)
  assert.equal(rEmergency.reason, 'emergency')

  const rNight = evaluateLiveCore({ now, phone: basePhone, arbiter: { priority: 'P1', source: 'NIGHT_CURFEW' } })
  assert.equal(rNight.triggered, false)
  assert.equal(rNight.reason, 'sleeping')

  const rBreak = evaluateLiveCore({ now, phone: basePhone, arbiter: { priority: 'P1.2', source: 'SISTER_BREAK' } })
  assert.equal(rBreak.triggered, false)
  assert.equal(rBreak.reason, 'break')

  const rModal = evaluateLiveCore({ now, phone: basePhone, arbiter: { priority: 'P4', source: 'DAY_WORK_WALL' }, planModal: { active: true } })
  assert.equal(rModal.triggered, false)
  assert.equal(rModal.reason, 'plan_modal_priority')

  const rQuiz = evaluateLiveCore({ now, phone: basePhone, arbiter: { priority: 'P4', source: 'DAY_WORK_WALL' }, runningQuiz: true })
  assert.equal(rQuiz.triggered, false)
  assert.equal(rQuiz.reason, 'active_study_or_quiz')

  const rWork = evaluateLiveCore({ now, phone: { app: 'com.fenbi.android.solar', label: '粉笔', minutes: 4, screenOn: true }, fgTier: 'work', arbiter: { priority: 'P4', source: 'DAY_WORK_WALL' } })
  assert.equal(rWork.triggered, false)
  assert.equal(rWork.reason, 'active_study_or_quiz')

  const rLive = evaluateLiveCore({ now, phone: { ...basePhone, geminiVoiceActive: true }, inGemini: true })
  assert.equal(rLive.triggered, false)
  assert.equal(rLive.reason, 'live_running')
  assert.equal(rLive.nextState.lastVoiceActiveAt, now)
})

test('LiveCore-2: 白天 18 点前常规锁下闲逛或听歌未学习 -> 点火 gaze_ignite_under_lock 并推 geminiAutoPull', async (tc) => {
  const T_1500 = Date.UTC(2026, 10, 24, 7, 0, 0) // 15:00 Asia/Shanghai
  tc.mock.timers.enable({ apis: ['Date'], now: T_1500 })
  try {
    await withHome(async () => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      await call(h(), 'POST', '/plan-modality', {
        action: 'confirm_plan_modal',
        blocks: [{ id: 'b1', slot: 'evening', startMin: 19 * 60, endMin: 19 * 60 + 25, title: '夜考复盘', deliverable: '写15字摘要', lockMode: 'verify' }],
      })

      const planRes = (await call(h(), 'POST', '/plan', {
        timeZone: TZ,
        phone: {
          app: 'com.bbk.launcher2',
          label: '桌面',
          minutes: 4,
          screenOn: true,
          mediaPlaying: true,
          screenText: '桌面第 2 页 · 微信未读 1',
        },
      })).body

      assert.equal(planRes.liveCore.triggered, true)
      assert.equal(planRes.liveCore.reason, 'gaze_ignite_under_lock')
      assert.equal(planRes.geminiAutoPull?.triggered, true)
      assert.equal(planRes.geminiAutoPull?.reason, 'gaze_ignite_under_lock')
      assert.equal(planRes.geminiAutoPull?.autoClickLive, true)
      assert.equal(planRes.geminiAutoPull?.requestScreenShare, true)
      assert.match(planRes.geminiAutoPull?.brief || '', /【禁聊体系死命令】/)
      assert.match(planRes.geminiAutoPull?.brief || '', /【复盘】/)
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('LiveCore-3: Live 中途断开 2 分钟无回执自动重拉 live_reengage，但切回刷题学习时静默让路', () => {
  const t0 = 1_700_200_000_000
  const s1 = evaluateLiveCore({
    now: t0,
    phone: { app: 'com.google.android.apps.bard', label: 'Gemini', minutes: 2, screenOn: true, geminiVoiceActive: true },
    inGemini: true,
  })
  assert.equal(s1.reason, 'live_running')

  // 2 分钟后退到桌面且未交复盘 -> 触发 live_reengage
  const tDrop = t0 + LIVE_CORE_DEFAULTS.reengageMinDropMs + 30_000
  const sDrop = evaluateLiveCore({
    now: tDrop,
    state: s1.nextState,
    phone: { app: 'com.bbk.launcher2', label: '桌面', minutes: 2, screenOn: true, geminiVoiceActive: false },
    inGemini: false,
  })
  assert.equal(sDrop.triggered, true)
  assert.equal(sDrop.reason, 'live_reengage')
  assert.equal(sDrop.action, 'reengage')

  // 若退出 Live 后直接去刷题，则严守让路闸门不打扰
  const sStudyAfterDrop = evaluateLiveCore({
    now: tDrop,
    state: s1.nextState,
    phone: { app: 'com.fenbi.android.solar', label: '粉笔', minutes: 1, screenOn: true, geminiVoiceActive: false },
    fgTier: 'work',
    runningQuiz: true,
  })
  assert.equal(sStudyAfterDrop.triggered, false)
  assert.equal(sStudyAfterDrop.reason, 'active_study_or_quiz')
})

test('LiveCore-4: 6 面骰子与笔记/错题/设定/MCP/屏幕共享融合剧本 + GET/POST /live-core 全流程', async (tc) => {
  assert.equal(LIVE_DICE_FACES.length, 6)
  const brief = buildLiveCoreBrief({
    dice: LIVE_DICE_FACES[3], // 微抄写盯盘
    anchor: {
      sourceLabel: '错题本',
      topic: '判断推理·削弱论证',
      prompt: '某产品称喝黑咖啡可防脂肪肝，指出最直接削弱项',
      answerKey: '另有他因：常喝者运动与作息更自律',
    },
    settingText: '# Gemini 设定\n- 严禁温柔敷衍，直接追问漏洞。',
    phone: { label: '哔哩哔哩', app: 'tv.danmaku.bili', minutes: 7, screenText: '首页推荐 · 搞笑视频合集', mediaPlaying: true, headsetConnected: true },
    vaultSnippets: [{ title: '模块A画像', snippet: '低带宽需立刻降维为具体抄写或小题' }],
    commitments: ['晚8点前完成判断推理20题'],
    recap: { summary: '刚拆完加强论证', next: '立刻练削弱论证' },
    bridge: true,
    wantScreenShare: true,
  })
  assert.ok(brief.startsWith('【禁聊体系死命令】'))
  assert.ok(brief.includes('严禁温柔敷衍'))
  assert.ok(brief.includes('共享屏幕'))
  assert.ok(brief.includes('微抄写盯盘'))
  assert.ok(brief.includes('错题本'))
  assert.ok(brief.includes('模块A画像'))
  assert.ok(brief.includes('dafeiyu_today'))
  assert.ok(brief.includes('dafeiyu_submit_receipt'))
  assert.ok(brief.includes('【复盘】'))
  assert.ok(brief.length <= 2800)

  const T_1430 = Date.UTC(2026, 10, 24, 6, 30, 0)
  tc.mock.timers.enable({ apis: ['Date'], now: T_1430 })
  try {
    await withHome(async () => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      const cfg = (await call(h(), 'POST', '/live-core', {
        action: 'set_config',
        wantScreenShare: true,
        autoReengage: true,
        autoCooldownMin: 15,
      })).body
      assert.equal(cfg.ok, true)
      assert.equal(cfg.wantScreenShare, true)
      assert.equal(cfg.autoCooldownMs, 15 * 60_000)

      const rolled = (await call(h(), 'POST', '/live-core', { action: 'roll_dice', diceHint: 6 })).body
      assert.equal(rolled.ok, true)
      assert.equal(rolled.dice.face, 6)
      assert.equal(rolled.payload.requestScreenShare, true)
      assert.match(rolled.payload.brief, /25m 接力立契/)

      const got = (await call(h(), 'GET', '/live-core')).body
      assert.equal(got.ok, true)
      assert.equal(got.dice.face, 6)

      const today = (await call(h(), 'GET', '/today')).body
      assert.equal(today.liveCore?.dice?.face, 6)

      const muted = (await call(h(), 'POST', '/live-core', { action: 'mute', minutes: 20 })).body
      assert.equal(muted.ok, true)
      assert.equal(muted.muted, true)
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('LiveCore-5: 连刷 5 题泄压阀 + 历史记录过滤 + 功能图谱 4 支柱 + P0-P6 梯子', () => {
  const now = Date.UTC(2026, 10, 24, 7, 30, 0) // 15:30 Asia/Shanghai
  const relief = evaluateLiveCore({
    now,
    phone: { app: 'com.bbk.launcher2', label: '桌面', minutes: 4, screenOn: true, fun: false },
    arbiter: { priority: 'P4', source: 'DAY_WORK_WALL' },
    recentQuizAnswers: 5,
  })
  assert.equal(relief.triggered, false)
  assert.equal(relief.reason, 'quiz_relief_valve')
  assert.equal(relief.phase, 'yielded_quiz_relief')
  assert.equal(relief.nextState.mutedUntil, now + LIVE_CORE_DEFAULTS.muteDurationMs)
  assert.equal(relief.nextState.quizReliefCount, 1)

  const sampleLedger = [
    { state: 'live-core-ignite', at: now - 50_000, action: 'ignite', reason: 'gaze_ignite_under_lock', diceFace: 2, anchorId: 'v-1' },
    { state: 'live-core-yield', at: now - 30_000, reason: 'quiz_relief_valve', phase: 'yielded_quiz_relief' },
    { state: 'live-core-mute', at: now - 20_000, minutes: 20 },
    { state: 'live-core-relay', at: now - 10_000, mode: 'checkpoint_25m', next: '完成判断推理 15 题', minutes: 25 },
    { state: 'reverse-capsule', at: now - 5_000, mode: 'quiz_only', minutes: 20 },
  ]
  const allHist = formatLiveCoreHistory(sampleLedger, { tz: TZ })
  assert.equal(allHist.length, 5)
  assert.equal(allHist[0].kind, 'capsule')
  const onlyIgnite = formatLiveCoreHistory(sampleLedger, { filter: 'ignite', tz: TZ })
  assert.equal(onlyIgnite.length, 1)
  assert.match(onlyIgnite[0].title, /笔记翻旧账/)
  const onlyYield = formatLiveCoreHistory(sampleLedger, { filter: 'yield', tz: TZ })
  assert.equal(onlyYield.length, 2)
  assert.match(onlyYield[1].title, /连刷 5 题/)
  const onlyRelay = formatLiveCoreHistory(sampleLedger, { filter: 'relay', tz: TZ })
  assert.equal(onlyRelay.length, 2)

  const atlas = buildLiveCoreAtlas({
    now,
    liveCore: { enabled: true, phase: 'ignited', dice: LIVE_DICE_FACES[1] },
    arbiter: { active: true, priority: 'P4', label: '白天防分心锁' },
    bridgeHealthy: true,
    capabilities: { a11y: true, shizuku: false },
    todayStats: { quizAnsweredToday: 8, liveMinutesToday: 14 },
    history: allHist,
    tz: TZ,
  })
  assert.equal(atlas.pipeline.length, 4)
  assert.equal(atlas.pillars.length, 4)
  assert.deepEqual(atlas.pillars.map((p) => p.id), ['ignite', 'locks', 'plan', 'quiz'])
  assert.equal(atlas.lockLadder.length, 6)
  assert.equal(atlas.todayMetrics.ignites, 1)
  assert.equal(atlas.todayMetrics.yields, 2)
  assert.equal(atlas.todayMetrics.relays, 1)
  assert.equal(atlas.todayMetrics.capsules, 1)
  assert.equal(atlas.todayMetrics.quizAnsweredToday, 8)
})

test('LiveCore-6: POST /live-core commit_relay (25m 验收点 + 20m 纯刷题舱) 与 GET /live-core 返回完整图谱和记录', async (tc) => {
  const T_1500 = Date.UTC(2026, 10, 24, 7, 0, 0)
  tc.mock.timers.enable({ apis: ['Date'], now: T_1500 })
  try {
    await withHome(async () => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      await call(h(), 'POST', '/live-core', { action: 'roll_dice', diceHint: 1 })

      const cp = (await call(h(), 'POST', '/live-core', {
        action: 'commit_relay',
        mode: 'checkpoint_25m',
        minutes: 25,
        summary: '拆清削弱论证他因漏洞',
        next: '刷 15 道削弱真题并复盘',
      })).body
      assert.equal(cp.ok, true)
      assert.equal(cp.relay.mode, 'checkpoint_25m')
      assert.equal(cp.relay.checkpointResult.ok, true)
      assert.equal(cp.relay.checkpointResult.added.length, 1)

      const quizCap = (await call(h(), 'POST', '/live-core', {
        action: 'commit_relay',
        mode: 'quiz_only',
        minutes: 20,
        next: 'Pure 20m Quiz Relay',
      })).body
      assert.equal(quizCap.ok, true)
      assert.equal(quizCap.relay.mode, 'quiz_only')
      assert.equal(quizCap.relay.capsuleResult.ok, true)
      assert.equal(quizCap.relay.capsuleResult.mode, 'quiz_only')

      const atlasRes = (await call(h(), 'GET', '/live-core?filter=relay')).body
      assert.equal(atlasRes.ok, true)
      assert.equal(atlasRes.atlas.pillars.length, 4)
      assert.ok(atlasRes.history.length >= 2)
      assert.equal(atlasRes.atlas.todayMetrics.relays, 2)
      assert.equal(atlasRes.atlas.todayMetrics.capsules, 1)
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('LiveCore-7: alwaysOnLive 严格防秒退（15s 触发 live_early_bailout + urgent）与文本保护窗 switch_mode', async (tc) => {
  const T_1510 = Date.UTC(2026, 10, 24, 7, 10, 0)
  tc.mock.timers.enable({ apis: ['Date'], now: T_1510 })
  try {
    const bailout = evaluateLiveCore({
      now: T_1510,
      state: {
        enabled: true,
        alwaysOnLive: true,
        keeperStrict: true,
        lastVoiceActiveAt: T_1510 - 16_000,
        lastVoiceDropHandledAt: 0,
        receiptSinceLastLive: false,
      },
      phone: { app: 'com.bbk.launcher2', label: '桌面', minutes: 1, screenOn: true, geminiVoiceActive: false, lastLiveDurationSec: 18 },
      recentQuizzes: [{ topic: '数量关系', stem: '工程问题合作效率差计算', wrong: true }],
      experiences: ['昨天拆穿了自己刷视频逃避推导的老毛病'],
    })
    assert.equal(bailout.triggered, true)
    assert.equal(bailout.reason, 'live_early_bailout')
    assert.equal(bailout.urgent, true)
    assert.equal(bailout.nextState.earlyBailoutCount, 1)
    assert.match(bailout.brief, /双鱼实时喂料/)
    assert.match(bailout.brief, /工程问题合作效率差计算/)
    assert.match(bailout.brief, /昨天拆穿了自己刷视频逃避推导/)

    const feed = buildSisterFeed({
      recentQuizzes: [{ topic: '判断推理', stem: '论证削弱之另有他因', wrong: true }],
      experiences: ['晨间立誓先啃硬骨头'],
    })
    assert.equal(feed.quizzes.length, 1)
    assert.equal(feed.experiences.length, 1)

    await withHome(async () => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      const cfg = (await call(h(), 'POST', '/live-core', { action: 'set_config', alwaysOnLive: true, keeperStrict: true })).body
      assert.equal(cfg.alwaysOnLive, true)

      const toText = (await call(h(), 'POST', '/live-core', { action: 'switch_mode', mode: 'text', minutes: 15 })).body
      assert.equal(toText.ok, true)
      assert.equal(toText.preferredMode, 'text')
      assert.equal(toText.textGraceActive, true)
      assert.equal(toText.payload.autoClickLive, false)
      assert.equal(toText.payload.requestScreenShare, false)
      assert.match(toText.payload.brief, /Gemini 文本模式衔接与 MCP 沉淀剧本/)

      const blockedDuringGrace = evaluateLiveCore({
        now: T_1510 + 10_000,
        state: toText.liveCore,
        inGemini: true,
        phone: { app: 'com.google.android.apps.bard', label: 'Gemini', minutes: 2, screenOn: true, geminiVoiceActive: false },
      })
      assert.equal(blockedDuringGrace.triggered, false)
      assert.equal(blockedDuringGrace.reason, 'text_mode_grace')
      assert.equal(blockedDuringGrace.phase, 'text_mode')

      const toLive = (await call(h(), 'POST', '/live-core', { action: 'switch_mode', mode: 'live' })).body
      assert.equal(toLive.ok, true)
      assert.equal(toLive.preferredMode, 'live')
      assert.equal(toLive.textGraceActive, false)
      assert.equal(toLive.payload.autoClickLive, true)
      assert.equal(toLive.payload.requestScreenShare, true)
    })
  } finally {
    tc.mock.timers.reset()
  }
})
