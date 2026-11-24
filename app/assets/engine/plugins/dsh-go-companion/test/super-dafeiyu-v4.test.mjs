import { test } from 'node:test'
import assert from 'node:assert/strict'
import { appendFileSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { computeTimeLedger } from '../lib/time_ledger.js'
import {
  applyDayPlanAction,
  cleanCustodyPolicy,
  evaluateMandatoryPlanModal,
  parseNegotiatePlanText,
} from '../lib/plan_modality.js'
import {
  FALLBACK_EXAM_DRILLS,
  SPARK_MODES,
  buildControlledGeminiBrief,
  generateSuperSpark,
} from '../lib/super_spark.js'
import { GEMINI_PACKAGES } from '../lib/gemini.js'
import { COMPANION_CARD, STRATEGIST_CARD } from '../lib/roles.js'
import { DEFAULT_GEMINI_SETTING } from '../lib/trigger.js'

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
  const root = mkdtempSync(join(tmpdir(), 'companion-v4-'))
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
      // Windows SQLite file handles may linger briefly after test teardown
    }
  }
}

test('P0-1: penaltyOffset + deficitOffset 保证违约与超额场景刷题仍净增余额、放宽白天上限并返回准确拦截原因', async (tc) => {
  // 纯函数检查：computeTimeLedger 支持 penaltyOffset
  const tlCheck = computeTimeLedger({
    state: {
      day: '2026-11-24',
      penaltyOffset: 25,
      deficitOffset: 10,
      entries: [
        { kind: 'penalty', minutes: 30, day: '2026-11-24' },
        { kind: 'earned_bank', minutes: 10, day: '2026-11-24' },
      ],
    },
    day: '2026-11-24',
    funQuota: 30,
    today: [{ app: 'com.ss.android.ugc.aweme', minutes: 40 }],
    tierOf: (pkg) => (pkg.includes('aweme') ? 'fun' : 'other'),
  })
  assert.equal(tlCheck.penaltyOffset, 25)
  assert.equal(tlCheck.effectivePenalty, 5)
  assert.equal(tlCheck.penaltyApplied, 5)
  assert.equal(tlCheck.funLeft, 5)

  const T_1500 = Date.UTC(2026, 10, 24, 7, 0, 0) // 15:00 Asia/Shanghai
  tc.mock.timers.enable({ apis: ['Date'], now: T_1500 })
  try {
    await withHome(async (_root, companionDir) => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      // 注入 30m 历史 penalty（触发历史 penalty + 40m funUsed 欠账）
      writeFileSync(join(companionDir, 'time-ledger.json'), JSON.stringify({
        v: 1,
        day: '2026-11-24',
        deficitOffset: 0,
        penaltyOffset: 0,
        entries: [
          { kind: 'penalty', minutes: 30, reason: '测试历史违约扣罚', at: T_1500 - 60_000, day: '2026-11-24' },
        ],
      }))

      // 确认今日计划以防 auto_kickoff 干涉
      await call(h(), 'POST', '/plan-modality', {
        action: 'confirm_plan_modal',
        blocks: [{
          id: 'blk-future-p1',
          slot: 'evening',
          startMin: 20 * 60,
          endMin: 20 * 60 + 40,
          title: '晚间复盘',
          deliverable: '提交 12 字复盘总结',
          lockMode: 'verify',
        }],
      })

      // 模拟白天看娱乐 40 分钟（超出默认 30m 且欠账）
      await call(h(), 'POST', '/plan', {
        timeZone: TZ,
        phone: {
          app: 'com.ss.android.ugc.aweme',
          label: '抖音',
          minutes: 5,
          fun: true,
          screenOn: true,
          today: [{ app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 40, fun: true }],
        },
      })

      // 导入 20 题并做满一轮专练（10题），验证第1..9题透传与第10题严格净增 +5m
      const questions = Array.from({ length: 20 }, (_, i) => ({
        id: `pq-${i + 1}`,
        kind: 'mcq',
        stem: `行测测试第 ${i + 1} 题`,
        options: ['A正确', 'B错误', 'C错误', 'D错误'],
        answer: 'A',
        explain: '选 A',
      }))
      await call(h(), 'POST', '/bank', {
        action: 'import',
        name: '综合行测',
        text: JSON.stringify({ name: '综合行测', questions }),
      })

      const sess = (await call(h(), 'POST', '/bank', { action: 'practice_session', limit: 20 })).body
      assert.equal(sess.required, 10)
      assert.equal(sess.practiceSolved, 0)

      let lastAns = null
      for (let i = 0; i < 10; i++) {
        const res = (await call(h(), 'POST', '/bank', {
          action: 'submit_practice_answer',
          questionId: sess.questions[i].id,
          answer: 'A',
        })).body
        assert.equal(res.ok, true)
        assert.equal(res.required, 10)
        assert.equal(res.practiceSolved, i + 1)
        lastAns = res
      }
      assert.equal(lastAns.roundComplete, true)
      assert.equal(lastAns.earnedAdded, 5)
      assert.equal(lastAns.availableFunLeft, 5, '历史 penalty=30 与 funUsed=40 均不得吞掉刷题挣到的 5m')
      assert.equal(lastAns.spendBlockedReason, '')

      // 制造白天已花掉 30 分钟零散额度，验证因为 tl.earnedBank > 0 白天上限被放宽（5m 仍可花）
      appendFileSync(join(companionDir, 'ledger.jsonl'), JSON.stringify({
        state: 'balance-spend',
        at: T_1500 - 120_000,
        day: '2026-11-24',
        window: 'daytime',
        minutes: 30,
        reason: '早先花满默认30m',
      }) + '\n')
      const sp = (await call(h(), 'POST', '/seals', {
        action: 'spend',
        minutes: 5,
        reason: '刷满十题奖励放宽白天限额',
      })).body
      assert.equal(sp.ok, true, JSON.stringify(sp))
      assert.equal(sp.minutes, 5)

      // 逾期 verify 块时，quiz_answer / status 返回 spendBlockedReason: 'verify-overdue'
      await call(h(), 'POST', '/plan-modality', {
        action: 'upsert_block',
        block: {
          id: 'blk-overdue-check',
          slot: 'afternoon',
          startMin: 14 * 60,
          endMin: 14 * 60 + 25,
          title: '已逾期验收块',
          deliverable: '提交 ≥12 字',
          lockMode: 'verify',
          verifyStatus: 'armed',
        },
      })
      const qSess = (await call(h(), 'POST', '/bank', { action: 'quiz_session' })).body
      assert.equal(qSess.spendBlockedReason, 'verify-overdue')
      const qAns = (await call(h(), 'POST', '/bank', {
        action: 'quiz_answer',
        questionId: qSess.question.id,
        answer: 'A',
      })).body
      assert.equal(qAns.ok, true)
      assert.equal(qAns.spendBlockedReason, 'verify-overdue')
      assert.equal(typeof qAns.earnedAdded, 'number')
      assert.equal(typeof qAns.practiceSolved, 'number')
      assert.equal(qAns.required, 10)

      // 注入到期复习卡并答题，确认 answerReviewCard 同样透传 5 个字段
      await call(h(), 'POST', '/review', {
        action: 'add',
        cards: [{ q: '申论归纳概括要留心什么？', a: '紧扣材料高频动词与主体抓手', options: ['紧扣材料高频动词与主体抓手', '自由散文式抒情', '背诵模板套话', '忽视材料直接写对策'], correctIndex: 0, topic: '申论' }],
      })
      const rcSess = (await call(h(), 'POST', '/bank', { action: 'quiz_session' })).body
      if (rcSess.question?.id?.startsWith('rc:')) {
        const rcAns = (await call(h(), 'POST', '/bank', {
          action: 'quiz_answer',
          questionId: rcSess.question.id,
          answer: 'A',
        })).body
        assert.equal(rcAns.ok, true)
        assert.equal(rcAns.spendBlockedReason, 'verify-overdue')
        assert.equal(typeof rcAns.availableFunLeft, 'number')
        assert.equal(typeof rcAns.earnedAdded, 'number')
        assert.equal(typeof rcAns.practiceSolved, 'number')
        assert.equal(rcAns.required, 10)
      }
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('P0-2: 强制计划模态 → 沟通解析/3次延后钉死 P3 验收锁 + /plan-modality + HARD_CAP_DOWNGRADE 不降级', async (tc) => {
  // 纯函数检查默认策略、自然语言解析与模态评估
  const pol = cleanCustodyPolicy({})
  assert.equal(pol.autoKickoffEnabled, true)
  assert.equal(pol.mandatoryPlanModalEnabled, true)

  const nl = parseNegotiatePlanText('我今天没带笔，下午判断推理 40 分钟，晚上申论复盘 1 小时', {
    minuteOfDay: 10 * 60,
    now: Date.UTC(2026, 10, 24, 2, 0, 0),
  })
  assert.equal(nl.ok, true)
  assert.equal(nl.noPen, true)
  assert.equal(nl.blocks.length, 2)
  assert.equal(nl.blocks[0].lockMode, 'verify')
  assert.equal(nl.blocks[0].verifyStatus, 'armed')
  assert.ok(nl.blocks[0].allowedApps.includes('com.fenbi.android.solar'))

  const T_0930 = Date.UTC(2026, 10, 24, 1, 30, 0) // 09:30 Asia/Shanghai
  tc.mock.timers.enable({ apis: ['Date'], now: T_0930 })
  try {
    await withHome(async (_root, companionDir) => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      // 1) 工作清醒时段首次 /plan 弹出 planModal，并且不会在首次被 auto_kickoff 抢跑吞掉
      const p1 = (await call(h(), 'POST', '/plan', { timeZone: TZ })).body
      assert.equal(p1.planModal?.active, true)
      assert.ok(p1.planModal.draftBlocks.length >= 2)
      assert.equal(p1.planModal.snoozeLeft, 2)

      // 2) 通过 /plan-modality 连续延后 2 次 → 第 3 次强制钉入 25m verify 起手块
      const sn1 = (await call(h(), 'POST', '/plan-modality', { action: 'snooze_plan_modal' })).body
      assert.equal(sn1.ok, true)
      assert.equal(sn1.autoArmed, false)
      assert.equal(sn1.snoozeLeft, 1)
      const modWhileSnoozed = (await call(h(), 'GET', '/plan-modality')).body
      assert.equal(modWhileSnoozed.planModal.active, false)
      assert.equal(modWhileSnoozed.planModal.reason, 'snoozed')

      const sn2 = (await call(h(), 'POST', '/plan-modality', { action: 'snooze_plan_modal' })).body
      assert.equal(sn2.snoozeLeft, 0)
      assert.equal(sn2.mustConfirm, true)

      const sn3 = (await call(h(), 'POST', '/plan-modality', { action: 'snooze_plan_modal' })).body
      assert.equal(sn3.ok, true)
      assert.equal(sn3.autoArmed, true)
      assert.equal(sn3.armedVerifyCount, 1)
      assert.equal(sn3.block.lockMode, 'verify')
      assert.equal(sn3.block.verifyStatus, 'armed')
      assert.equal(sn3.block.endMin - sn3.block.startMin, 25)

      // 3) 即使当天硬干预次上限写满（hardCapped = true），verify 锁 + planModal + REVERSE_CAPSULE 绝不被降级
      writeFileSync(join(companionDir, 'hard-interventions.json'), JSON.stringify({
        v: 1,
        day: '2026-11-24',
        count: 14,
        items: Array.from({ length: 14 }, (_, i) => ({
          kind: 'takeover',
          at: T_0930 - 10_000 - i * 1000,
          dedupeKey: `tk-${i}`,
        })),
      }))

      // 验证逾期触发 P3（09:30 + 27m = 09:57，超过 09:30..09:55 + 60s）
      tc.mock.timers.setTime(T_0930 + 27 * 60_000)
      const pOverdue = (await call(h(), 'POST', '/plan', { timeZone: TZ })).body
      assert.equal(pOverdue.hardInterventions?.capped, true)
      assert.equal(pOverdue.lockArbiter.priority, 'P3')
      assert.notEqual(pOverdue.lockArbiter.source, 'HARD_CAP_DOWNGRADE')
      assert.notEqual(pOverdue.lock?.mode, 'free')

      // verify 块清零回到当日未确认态，planModal 在 hardCapped 时仍正常触发
      const modEval = evaluateMandatoryPlanModal({}, { now: T_0930, day: '2026-11-24', minuteOfDay: 600 })
      assert.equal(modEval.active, true)
    })

    // 单独验证 negotiate_plan_modal 直接解析落盘并激活 P3 验收锁 + hardCapped 下 planModal 与 REVERSE_CAPSULE 不降级
    tc.mock.timers.setTime(T_0930)
    await withHome(async (_root, companionDir) => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      writeFileSync(join(companionDir, 'hard-interventions.json'), JSON.stringify({
        v: 1,
        day: '2026-11-24',
        count: 14,
        items: Array.from({ length: 14 }, (_, i) => ({
          kind: 'takeover',
          at: T_0930 - 10_000 - i * 1000,
          dedupeKey: `tk-${i}`,
        })),
      }))

      const pCapModal = (await call(h(), 'POST', '/plan', { timeZone: TZ })).body
      assert.equal(pCapModal.hardInterventions?.capped, true)
      assert.equal(pCapModal.planModal?.active, true, 'hardCapped 绝不能降级屏蔽强制计划模态')

      const neg = (await call(h(), 'POST', '/plan-modality', {
        action: 'negotiate_plan_modal',
        text: '今天没带笔，现在开始判断推理 30 分钟',
      })).body
      assert.equal(neg.ok, true)
      assert.equal(neg.committed, true)
      assert.equal(neg.armedVerifyCount, 1)
      assert.ok(neg.reply.includes('判断推理'))
      assert.equal(neg.planModal.active, false)

      const rcCap = (await call(h(), 'POST', '/bouts', {
        action: 'reverse_capsule',
        mode: 'gemini_only',
        minutes: 15,
      })).body
      assert.equal(rcCap.ok, true)
      const pCapRev = (await call(h(), 'POST', '/plan', { timeZone: TZ })).body
      assert.equal(pCapRev.lockArbiter.source, 'REVERSE_CAPSULE', 'hardCapped 绝不降级 REVERSE_CAPSULE')
      await call(h(), 'POST', '/bouts', { action: 'close' })
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('P0-3: 无局可改默认白名单 + 纯游戏硬拦截 + other->work 立即生效 + reverse_capsule 窄舱隔离', async () => {
  await withHome(async () => {
    const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    // 上报已安装应用（含考公学习、纯游戏、其他未知应用）
    await call(h(), 'POST', '/plan', {
      timeZone: TZ,
      phone: {
        app: 'com.tencent.mm',
        label: '微信',
        minutes: 1,
        screenOn: true,
        installedApps: [
          { app: 'com.fenbi.android.solar', label: '粉笔' },
          { app: 'com.huatu.ztk', label: '华图在线' },
          { app: 'md.obsidian', label: 'Obsidian' },
          { app: 'com.ss.android.ugc.aweme', label: '抖音' },
          { app: 'com.miHoYo.Yuanshen', label: '原神' },
          { app: 'com.example.flashcards', label: '自制记忆卡' },
        ],
      },
    })

    // 1) 无局时 action='allow' 与 action='set_default_allow' 均可更新默认白名单且拦截纯游戏
    const allowRes = (await call(h(), 'POST', '/bouts', {
      action: 'allow',
      allow: ['md.obsidian', 'com.fenbi.android.solar', 'com.miHoYo.Yuanshen'],
    })).body
    assert.equal(allowRes.ok, true)
    assert.deepEqual(allowRes.allow, ['md.obsidian', 'com.fenbi.android.solar'])
    assert.deepEqual(allowRes.rejectedGames, ['com.miHoYo.Yuanshen'])
    assert.ok(allowRes.defaultAllow.includes('com.fenbi.android.solar'))

    const gameOnly = (await call(h(), 'POST', '/bouts', {
      action: 'set_default_allow',
      allow: ['com.miHoYo.Yuanshen'],
    })).body
    assert.equal(gameOnly.ok, false)
    assert.equal(gameOnly.error, 'pure-game')

    // 2) tiersRoute: list 返回 defaultAllow; other->work 立即生效; fun->work 仍需 24h 挂起
    const tList = (await call(h(), 'POST', '/tiers', { action: 'list' })).body
    assert.equal(tList.ok, true)
    assert.ok(Array.isArray(tList.defaultAllow))
    assert.ok(tList.defaultAllow.includes('com.fenbi.android.solar'))

    const otherToWork = (await call(h(), 'POST', '/tiers', {
      action: 'set',
      pkg: 'com.example.flashcards',
      tier: 'work',
    })).body
    assert.equal(otherToWork.ok, true)
    assert.equal(otherToWork.applied, true)
    assert.ok(otherToWork.defaultAllow.includes('com.example.flashcards'))

    const funToWork = (await call(h(), 'POST', '/tiers', {
      action: 'set',
      pkg: 'com.ss.android.ugc.aweme',
      tier: 'work',
    })).body
    assert.equal(funToWork.ok, true)
    assert.equal(funToWork.applied, false)
    assert.equal(funToWork.pendingTier, 'work')

    // 3) 反向单开舱：gemini_only 严格只放 Gemini 包，绝不混入 Obsidian/微信/抖音
    const rcGem = (await call(h(), 'POST', '/bouts', {
      action: 'reverse_capsule',
      mode: 'gemini_only',
      minutes: 15,
    })).body
    assert.equal(rcGem.ok, true)
    assert.equal(rcGem.scopeKey, 'reverse:gemini_only')
    assert.deepEqual(rcGem.allow, GEMINI_PACKAGES)

    // 先确认当日 modal 以排除其他干扰，检视 /plan 下发 session 与 lockDecision 与 lockArbiter
    await call(h(), 'POST', '/plan-modality', {
      action: 'confirm_plan_modal',
      blocks: [{ id: 'b1', slot: 'evening', startMin: 21 * 60, endMin: 21 * 60 + 25, title: '复盘', deliverable: '交12字复盘', lockMode: 'verify' }],
    })
    const planGem = (await call(h(), 'POST', '/plan', { timeZone: TZ })).body
    assert.equal(planGem.lockArbiter.source, 'REVERSE_CAPSULE')
    assert.equal(planGem.lock.source, 'REVERSE_CAPSULE')
    assert.deepEqual(planGem.session.allow, GEMINI_PACKAGES)
    assert.ok(!planGem.session.allow.includes('md.obsidian'))
    assert.ok(!planGem.session.allow.includes('com.ss.android.ugc.aweme'))

    // 4) 一键切换至 quiz_only 反向刷题舱：只保留考公刷题/笔记 App
    const rcQuiz = (await call(h(), 'POST', '/bouts', {
      action: 'reverse_capsule',
      mode: 'quiz_only',
      minutes: 20,
    })).body
    assert.equal(rcQuiz.ok, true, JSON.stringify(rcQuiz))
    assert.equal(rcQuiz.scopeKey, 'reverse:quiz_only')
    assert.ok(rcQuiz.allow.includes('com.fenbi.android.solar'))
    assert.ok(rcQuiz.allow.includes('com.huatu.ztk'))
    assert.ok(rcQuiz.allow.includes('md.obsidian'))
    assert.ok(!rcQuiz.allow.includes('com.ss.android.ugc.aweme'))
    assert.ok(!rcQuiz.allow.includes('com.miHoYo.Yuanshen'))

    // 5) custom 模式拒绝纯游戏并支持白名单定制
    const rcCustomBad = (await call(h(), 'POST', '/bouts', {
      action: 'reverse_capsule',
      mode: 'custom',
      allow: ['com.miHoYo.Yuanshen'],
    })).body
    assert.equal(rcCustomBad.ok, false)
    assert.equal(rcCustomBad.error, 'pure-game')

    const rcCustom = (await call(h(), 'POST', '/bouts', {
      action: 'reverse_capsule',
      mode: 'custom',
      minutes: 25,
      allow: ['md.obsidian', 'com.miHoYo.Yuanshen'],
      goal: '定制无干扰写作舱',
    })).body
    assert.equal(rcCustom.ok, true)
    assert.equal(rcCustom.scopeKey, 'reverse:custom')
    assert.deepEqual(rcCustom.allow, ['md.obsidian'])
    assert.deepEqual(rcCustom.rejectedGames, ['com.miHoYo.Yuanshen'])

    await call(h(), 'POST', '/bouts', { action: 'close' })
  })
})

test('P1-4: super_spark 四源融合/去重/4模式/受控Gemini剧本 + /super-spark + /plan 注入 + 人设反说教校验', async (tc) => {
  // 1) 四源融合 + 最近 8 条去重 + 4 种模式字段完整性
  const recentIds = FALLBACK_EXAM_DRILLS.slice(0, 8).map((d) => d.id)
  const sp1 = generateSuperSpark({
    now: 1_700_000_000_000,
    bankState: {
      wrongBookIds: ['wb-q-1'],
      wrongQuestions: [{ id: 'wb-q-1', topic: '判断推理·真假话', stem: '甲乙丙三人只有一人说真话…', answerText: '找矛盾' }],
    },
    reviewCards: [{ id: 'card-1', front: '三段论至少出现两次的词是什么？', back: '中项至少周延一次', topic: '逻辑' }],
    horizons: { long: [{ id: 'h1', title: '行测稳定72分', nextStep: '判断推理每天20题' }] },
    recentIds,
    modeHint: 'contrast_push',
    rng: () => 0.01, // 优先命中高权重 wrong_book
  })
  assert.equal(sp1.triggered, true)
  assert.equal(sp1.mode, 'contrast_push')
  assert.equal(sp1.anchorSource, 'wrong_book')
  assert.ok(!recentIds.includes(sp1.anchorId))
  assert.equal(sp1.optionAMode, 'quiz_only')
  assert.equal(sp1.optionAMinutes, 20)
  assert.equal(sp1.optionBMode, 'gemini_only')
  assert.equal(sp1.optionBMinutes, 15)
  assert.ok(sp1.geminiBrief.startsWith('【禁聊体系死命令】'))
  assert.ok(sp1.geminiBrief.includes('严禁陪聊学习方法'))
  assert.ok(sp1.geminiBrief.includes('【复盘】'))

  const spCommute = generateSuperSpark({
    now: 1_700_000_060_000,
    commute: true,
    handsBusy: true,
    recentIds: sp1.nextRecentIds,
    rng: () => 0.9,
  })
  assert.equal(spCommute.mode, 'commute_audio')
  assert.equal(spCommute.autoClickLive, true)
  assert.ok(spCommute.voiceLine.length >= 10)

  const spRescue = generateSuperSpark({
    now: 1_700_000_120_000,
    noPen: true,
    modeHint: 'tactical_rescue',
  })
  assert.equal(spRescue.mode, 'tactical_rescue')
  assert.ok(spRescue.title.includes('没笔'))

  const briefCheck = buildControlledGeminiBrief({ topic: '判断推理', prompt: '题干如下…', answerKey: '拆矛盾' }, { noPen: true })
  assert.ok(briefCheck.startsWith('【禁聊体系死命令】'))
  assert.ok(briefCheck.includes('【复盘】'))

  // 人设提示词约束检查
  assert.ok(STRATEGIST_CARD.length < 1100, `STRATEGIST_CARD length ${STRATEGIST_CARD.length} must stay < 1100`)
  assert.ok(COMPANION_CARD.includes('无纸笔'))
  assert.ok(DEFAULT_GEMINI_SETTING.includes('禁聊体系铁律'))
  assert.ok(DEFAULT_GEMINI_SETTING.includes('验收器'))

  // 2) HTTP 路由 GET/POST /super-spark + /plan 自动注入 + mute_15m 拦截
  const T_1530 = Date.UTC(2026, 10, 24, 7, 30, 0) // 15:30 Asia/Shanghai
  tc.mock.timers.enable({ apis: ['Date'], now: T_1530 })
  try {
    await withHome(async () => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      // 先确认今日计划模态，让 /plan 不被 planModal 盖住从而触发 superSpark
      await call(h(), 'POST', '/plan-modality', {
        action: 'confirm_plan_modal',
        blocks: [{ id: 'blk-s', slot: 'evening', startMin: 20 * 60, endMin: 20 * 60 + 35, title: '申论练习', deliverable: '交200字大纲', lockMode: 'verify' }],
      })

      const pSpark = (await call(h(), 'POST', '/plan', {
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 6, fun: true, screenOn: true },
      })).body
      assert.equal(pSpark.superSpark?.triggered, true)
      assert.ok(SPARK_MODES.includes(pSpark.superSpark.mode))

      const triggered = (await call(h(), 'POST', '/super-spark', {
        action: 'trigger',
        modeHint: 'commute_audio',
      })).body
      assert.equal(triggered.ok, true)
      assert.equal(triggered.spark.mode, 'commute_audio')
      assert.equal(triggered.spark.autoClickLive, true)

      const got = (await call(h(), 'GET', '/super-spark')).body
      assert.equal(got.ok, true)
      assert.equal(got.spark.id, triggered.spark.id)

      const muted = (await call(h(), 'POST', '/super-spark', { action: 'mute_15m' })).body
      assert.equal(muted.ok, true)
      assert.equal(muted.muted, true)

      // 静音期内快进 10m 调 /plan 不再重复弹窗
      tc.mock.timers.setTime(T_1530 + 10 * 60_000)
      const pMuted = (await call(h(), 'POST', '/plan', {
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 12, fun: true, screenOn: true },
      })).body
      assert.equal(pMuted.superSpark, undefined)
    })
  } finally {
    tc.mock.timers.reset()
  }
})
