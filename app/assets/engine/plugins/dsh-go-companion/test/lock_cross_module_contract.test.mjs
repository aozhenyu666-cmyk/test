// Cross-module lock contract tests covering Tasks 1–4 of locks-core:
// 1) P3/P3.5/P4 strict locks ALWAYS allow WORK_DEFAULT + ofTier('work') + GEMINI + PROXY;
// 2) Flexible custom duration (30m) + immediate ghost-lock cleanup on closeBoutNow;
// 3) Option A Daytime Work Wall (07:00–23:00 excl. 12:00–14:00 & meals) + toggle_day_wall;
// 4) Dual-fish read-only lock-truth context matches buildLocksOverview().winnerId with zero persona pollution;
// 5) /event intercept-hit & /seals strict_off respect hard rules (overdue seals & night curfew cannot be bypassed).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { WORK_DEFAULT } from '../lib/app_tiers.js'
import { buildLockDecision } from '../lib/lock_decision.js'
import { GEMINI_PACKAGES } from '../lib/gemini.js'
import { PROXY_PACKAGES } from '../lib/never_lock.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const TZ = 'Asia/Shanghai'

function agentOf(id) {
  const contexts = []
  const tools = {}
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    sent: [],
    contexts,
    tools,
    followup(m) {
      this.sent.push(m)
    },
    ctx: {
      systemPrompt: {
        context(def) {
          contexts.push(def)
          return () => {
            def.removed = true
          }
        },
      },
      tools: {
        register(def) {
          tools[def.name] = def
          return () => {
            delete tools[def.name]
          }
        },
      },
    },
  }
}

function host(agents) {
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) {
      cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } })
    },
    effect(fn) {
      fn()
    },
  }
  setStatePin('standby')
  apply(ctx)
  return () => handler
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = PREFIX + path
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0,
    text: '',
    writeHead(s) {
      this.statusCode = s
    },
    end(c) {
      this.text = c ? String(c) : ''
    },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

async function withHome(fn, extraConfig = {}) {
  const home = mkdtempSync(join(tmpdir(), 'locks-contract-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(
    join(home, 'companion', 'config.json'),
    JSON.stringify({
      sessionId: SIS,
      strategistSessionId: BIG,
      quietStart: 12,
      quietEnd: 12,
      timeZone: TZ,
      ...extraConfig,
    }),
  )
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {
      // Windows sqlite lock cleanup tolerance
    }
  }
}

test('Contract 1: P3 / P3.5 / P4 strict locks ALWAYS allow WORK_DEFAULT + ofTier(work) + GEMINI + PROXY', () => {
  const installed = [
    { pkg: 'md.obsidian', label: 'Obsidian' },
    { pkg: 'com.custom.studywork', label: '自定学习工具' },
    { pkg: 'com.ss.android.ugc.aweme', label: '抖音' },
  ]
  const tierOf = (pkg) => {
    if (pkg === 'com.custom.studywork') return 'work'
    if (pkg === 'com.ss.android.ugc.aweme') return 'fun'
    return ''
  }

  for (const [priority, source] of [
    ['P3', 'CONTRACT_OVERDUE_STRICT'],
    ['P3.5', 'TAKEOVER_STRICT'],
    ['P4', 'BOUT_STRICT'],
  ]) {
    const dec = buildLockDecision({
      arbiter: { priority, source, label: '严锁', why: '测试严锁', unlockHow: '交验收', allow: [] },
      installed,
      tierOf,
      now: 1_800_000_000_000,
    })
    assert.equal(dec.mode, 'allowlist', `${priority} 应为 allowlist`)
    for (const pkg of [...WORK_DEFAULT, 'com.custom.studywork', ...GEMINI_PACKAGES, ...PROXY_PACKAGES]) {
      assert.equal(dec.allow.includes(pkg), true, `${priority}/${source} 必须放行 ${pkg}`)
    }
    assert.equal(dec.allow.includes('com.ss.android.ugc.aweme'), false, `${priority} 不应放行娱乐 App`)
  }
})

test('Contract 2: 自定义严格锁时长 30m 精确生效，且收局后 preset-strict 幽灵锁立即清零', async (tc) => {
  // 2026-11-23 10:00:00 Asia/Shanghai = 02:00:00 UTC
  const WORK_10AM = Date.UTC(2026, 10, 23, 2, 0, 0)
  tc.mock.timers.enable({ apis: ['Date'], now: WORK_10AM })
  try {
    await withHome(async () => {
      const sis = agentOf(SIS)
      const big = agentOf(BIG)
      const h = host({ [SIS]: sis, [BIG]: big })

      const started = await call(h(), 'POST', '/bouts', {
        action: 'open',
        protocolId: 'execute',
        title: '写契约单测',
        minutes: 30,
        strict: true,
        apps: { need: ['com.custom.only'], allow: ['com.custom.only'] },
      })
      assert.equal(started.body.ok, true)

      const ovDuring = (await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview
      assert.equal(ovDuring.winnerId, 'BOUT_STRICT')
      const boutHand = ovDuring.hands.find((x) => x.id === 'BOUT_STRICT')
      assert.ok(boutHand)
      assert.equal(boutHand.active, true)
      assert.equal(boutHand.isWinner, true)
      assert.ok(boutHand.remainingMin >= 29 && boutHand.remainingMin <= 30, `30m 自定义时长不得硬编码 6h: ${boutHand.remainingMin}`)
      assert.ok(boutHand.until <= Date.now() + 31 * 60_000)

      // 同时检验 /plan 下的 lock.allow 全量保留 WORK_DEFAULT + GEMINI + PROXY
      const planDuring = (await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: {
          app: 'md.obsidian',
          label: 'Obsidian',
          fun: false,
          minutes: 1,
          screenOn: true,
          installedApps: [
            { pkg: 'md.obsidian', label: 'Obsidian' },
            { pkg: 'com.custom.workpkg', label: '自定工作台', tier: 'work' },
          ],
        },
      })).body
      for (const pkg of [...WORK_DEFAULT, ...GEMINI_PACKAGES, ...PROXY_PACKAGES]) {
        assert.ok(planDuring.lock.allow.includes(pkg), `严格局 /plan lock.allow 必须放行 ${pkg}`)
      }

      // 收局 → preset-strict 必须立刻清零
      const closed = await call(h(), 'POST', '/bouts', { action: 'close' })
      assert.equal(closed.body.ok, true)

      const ovAfter = (await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview
      const boutHandAfter = ovAfter.hands.find((x) => x.id === 'BOUT_STRICT')
      assert.equal(boutHandAfter.active, false, '收局后 BOUT_STRICT 不许留存幽灵锁')
      assert.notEqual(ovAfter.winnerId, 'BOUT_STRICT')
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('Contract 3: Option A 白天默认防分心、午休 12:00-14:00 自动放风、toggle_day_wall 关闭后无幽灵锁', async (tc) => {
  const WORK_10AM = Date.UTC(2026, 10, 23, 2, 0, 0) // 10:00 Asia/Shanghai
  const LUNCH_1230 = Date.UTC(2026, 10, 23, 4, 30, 0) // 12:30 Asia/Shanghai

  tc.mock.timers.enable({ apis: ['Date'], now: WORK_10AM })
  try {
    await withHome(async () => {
      const sis = agentOf(SIS)
      const big = agentOf(BIG)
      const h = host({ [SIS]: sis, [BIG]: big })

      // 1) 白天默认开启 Option A：工作 App 放行、娱乐 App 拦截
      const ovDay = (await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview
      assert.equal(ovDay.dayWorkWallEnabled, true)
      assert.equal(ovDay.winnerId, 'DAY_WORK_WALL')
      assert.equal(ovDay.hands.length, 6)

      const pDay = (await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: {
          app: 'com.ss.android.ugc.aweme',
          label: '抖音',
          fun: true,
          minutes: 1,
          screenOn: true,
          installedApps: [
            { pkg: 'md.obsidian', label: 'Obsidian' },
            { pkg: 'com.ss.android.ugc.aweme', label: '抖音' },
          ],
        },
      })).body
      assert.equal(pDay.lock.mode, 'allowlist')
      assert.ok(pDay.lock.allow.includes('md.obsidian'))
      assert.ok(pDay.session?.allow.includes('md.obsidian'))
      assert.equal(pDay.lock.allow.includes('com.ss.android.ugc.aweme'), false)

      // 2) 切到午休 12:30：自动休眠放风（无严格锁）
      tc.mock.timers.setTime(LUNCH_1230)
      const pLunch = (await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 1, screenOn: true },
      })).body
      assert.equal(pLunch.lockArbiter.active, false, '午休 12:00-14:00 Option A 必须自动休眠放风')
      assert.equal(pLunch.lock.mode, 'free')

      // 3) 回到白天 10:00 后通过 toggle_day_wall 关掉 Option A → 干净变为 free/open 无幽灵锁
      tc.mock.timers.setTime(WORK_10AM)
      const toggled = await call(h(), 'POST', '/seals', { action: 'toggle_day_wall', enabled: false })
      assert.equal(toggled.body.ok, true)
      assert.equal(toggled.body.dayWorkWallEnabled, false)
      assert.equal(toggled.body.locksOverview.dayWorkWallEnabled, false)
      assert.equal(toggled.body.locksOverview.winnerId, '')
      assert.equal(toggled.body.locksOverview.hands.find((x) => x.id === 'DAY_WORK_WALL').active, false)

      const pOff = (await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 1, screenOn: true },
      })).body
      assert.equal(pOff.session, null, '关闭 Option A 后 verifySessionView 必须干净返回 null')
      assert.equal(pOff.lockArbiter.active, false)
      assert.ok(['free', 'open'].includes(pOff.lock.mode), '无其他锁时 mode 必须干净变为 open/free')
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('Contract 4 & 5: 双鱼真相上下文与探针 winnerId/lastIntercept 完全一致且零人格污染，strict_off 不绕过逾期封印', async (tc) => {
  const WORK_10AM = Date.UTC(2026, 10, 23, 2, 0, 0)
  tc.mock.timers.enable({ apis: ['Date'], now: WORK_10AM })
  try {
    await withHome(async (home) => {
      const sis = agentOf(SIS)
      const big = agentOf(BIG)
      const h = host({ [SIS]: sis, [BIG]: big })

      assert.equal(sis.contexts.length, 2)
      assert.equal(big.contexts.length, 2)
      const sisTruth = sis.contexts.find((c) => c.name === 'dafeiyu-lock-truth')
      const bigTruth = big.contexts.find((c) => c.name === 'dafeiyu-lock-truth')
      assert.ok(sisTruth && bigTruth)
      assert.equal(sisTruth.order, 860)
      assert.equal(bigTruth.order, 860)

      // 验证原 Persona 文案在开锁/拦截前后一字不改
      const personaBefore = sis.contexts[0].text()

      // 触发手机拦截上报 /event
      const hitRes = await call(h(), 'POST', '/event', {
        type: 'intercept-hit',
        app: 'com.ss.android.ugc.aweme',
        label: '抖音',
        reason: '工作时段拦截娱乐 App',
      })
      assert.equal(hitRes.body.ok, true)

      const ov = (await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview
      const truthLines = sisTruth.text().split('\n')
      assert.ok(truthLines.length <= 6, `真相上下文不得超过 6 行，实际 ${truthLines.length}`)
      const joined = truthLines.join('\n')
      assert.ok(joined.includes(ov.winnerId), `真相上下文必须包含一致的 winnerId=${ov.winnerId}`)
      assert.match(joined, /抖音/)
      assert.equal(sis.contexts[0].text(), personaBefore, '原 Persona 文案绝对不能被污染或篡改')

      // 逾期封印激活时 strict_off 禁止撤回绕过
      const today = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
      writeFileSync(
        join(home, 'companion', 'seals.json'),
        JSON.stringify({
          v: 1,
          items: [
            {
              id: 's-overdue-lock',
              day: today,
              title: '不可绕过的逾期封印',
              kind: 'screenshots',
              stretch: 4,
              floor: 2,
              dueMinute: 1,
              status: 'below',
              actual: 0,
              lockUntil: Date.now() + 3_600_000,
            },
          ],
        }),
      )
      const strictOffBlocked = await call(h(), 'POST', '/seals', { action: 'strict_off' })
      assert.equal(strictOffBlocked.body.ok, false)
      assert.equal(strictOffBlocked.body.error, 'hard-lock')
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('Contract 6 (Merged HTTP): /bank 专练抽取去重、错题连对 2 次毕业、星标收藏与 7 日统计端到端闭环', async (tc) => {
  const WORK_10AM = Date.UTC(2026, 10, 23, 2, 0, 0)
  tc.mock.timers.enable({ apis: ['Date'], now: WORK_10AM })
  try {
    await withHome(async () => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
      const imp = await call(h(), 'POST', '/bank', {
        action: 'import',
        name: '行测混合题库',
        raw: JSON.stringify({
          题库版本: 1,
          id: 'xingce-mix',
          名称: '行测混合题库',
          题目: [
            { id: 'q1', kind: 'mcq', 题型: '常识', 题干: '第一题题干', 选项: ['选A', '选B', '选C', '选D'], 答案: 'B', 解析: '解析1' },
            { id: 'q2', kind: 'mcq', 题型: '言语', 题干: '第二题题干', 选项: ['选A', '选B', '选C', '选D'], 答案: 'C', 解析: '解析2' },
            { id: 'q3', kind: 'card', 题型: '知识卡', 题干: '核心规律展示卡', 答案: '记住边界条件', 解析: '解析3' },
            { id: 'q4', kind: 'short_qa', 题型: '小问题', 题干: '为什么需要先抓关键变量？', 答案: '反事实剃刀原则', 解析: '解析4' },
          ],
        }),
      })
      assert.equal(imp.body.ok, true)
      assert.equal(imp.body.importedCount, 4)

      const s1 = await call(h(), 'POST', '/bank', { action: 'practice_session', bankId: imp.body.bankId, limit: 10 })
      assert.equal(s1.body.ok, true)
      assert.equal(s1.body.questions.length, 4)
      const qIds = s1.body.questions.map((x) => x.id)
      assert.equal(new Set(qIds).size, 4, 'practice_session 返回题单不得重复')

      const q1Id = s1.body.questions.find((x) => x.stem.includes('第一题题干')).id
      const q3Id = s1.body.questions.find((x) => x.stem.includes('核心规律展示卡')).id

      // 收藏 q3 并筛选 starred
      const stStar = await call(h(), 'POST', '/bank', { action: 'toggle_star', questionId: q3Id, starred: true })
      assert.equal(stStar.body.ok, true)
      assert.equal(stStar.body.starred, true)
      const starredSess = await call(h(), 'POST', '/bank', { action: 'practice_session', scope: 'starred', limit: 10 })
      assert.equal(starredSess.body.questions.length, 1)
      assert.equal(starredSess.body.questions[0].id, q3Id)

      // 答错 q1 → 自动进入错题本
      const wrongRes = await call(h(), 'POST', '/bank', { action: 'submit_practice_answer', questionId: q1Id, answer: 'A', elapsedMs: 3200 })
      assert.equal(wrongRes.body.ok, true)
      assert.equal(wrongRes.body.correct, false)
      assert.equal(wrongRes.body.inWrongBook, true)
      const wb1 = await call(h(), 'POST', '/bank', { action: 'wrong_book_list' })
      assert.equal(wb1.body.items.some((q) => q.id === q1Id), true)

      // 连续答对第 1 次 → 仍保留在错题本（streak=1）
      const pass1 = await call(h(), 'POST', '/bank', { action: 'submit_practice_answer', questionId: q1Id, answer: 'B', elapsedMs: 2100 })
      assert.equal(pass1.body.correct, true)
      assert.equal(pass1.body.streakCorrect, 1)
      assert.equal(pass1.body.inWrongBook, true)

      // 连续答对第 2 次 → 自动毕业移出错题本
      const pass2 = await call(h(), 'POST', '/bank', { action: 'submit_practice_answer', questionId: q1Id, answer: 'B', elapsedMs: 1800 })
      assert.equal(pass2.body.correct, true)
      assert.equal(pass2.body.streakCorrect, 2)
      assert.equal(pass2.body.graduatedFromWrongBook, true)
      assert.equal(pass2.body.inWrongBook, false)

      const stats = await call(h(), 'POST', '/bank', { action: 'stats_7d' })
      assert.equal(stats.body.ok, true)
      assert.equal(stats.body.todayAnswered, 3)
      assert.equal(stats.body.starredCount, 1)
      assert.equal(stats.body.wrongBookCount, 0)
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('Contract 7 (Merged HTTP): /plan-contract 日计划时间盒 + /plan 主动 Gemini 矩阵与 snooze + GET /wall/boss-brief', async (tc) => {
  const WORK_10AM = Date.UTC(2026, 10, 23, 2, 0, 0) // 北京时间 10:00
  tc.mock.timers.enable({ apis: ['Date'], now: WORK_10AM })
  try {
    await withHome(async () => {
      const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })

      // 1) /plan-contract 准备 → 构建 → 观看时间盒操作
      const eRes = await call(h(), 'POST', '/plan-contract', { action: 'set_energy', energyLevel: 'high' })
      assert.equal(eRes.body.ok, true)
      assert.equal(eRes.body.dayPlan.energyLevel, 'high')

      const gRes = await call(h(), 'POST', '/plan-contract', { action: 'generate_default', energyLevel: 'high' })
      assert.equal(gRes.body.ok, true)
      assert.ok(gRes.body.dayPlan.dayBlocks.some((b) => b.slot === 'lunch' && b.isBreak), '必须含 12:00-14:00 午休放风块')

      const upRes = await call(h(), 'POST', '/plan-contract', {
        action: 'upsert_block',
        block: { id: 'blk-focus-1', startMin: 570, endMin: 615, title: '主线申论套卷', deliverable: '写完并核对要点', lockMode: 'strict', allowedApps: ['md.obsidian'] },
      })
      assert.equal(upRes.body.ok, true)
      const stRes = await call(h(), 'GET', '/plan-contract')
      assert.equal(stRes.body.dayPlan.dayBlocks.some((b) => b.id === 'blk-focus-1'), true)

      // 2) 关闭自动点火说话/只测 Gemini 触发矩阵：连续撞锁 2 次 → lock-bounce (10m 急冷却)
      await call(h(), 'POST', '/config', { control: true, minGapMs: 0 })
      await call(h(), 'POST', '/event', { type: 'intercept-hit', app: 'com.ss.android.ugc.aweme', label: '抖音', reason: '工作时段拦截娱乐 App' })
      await call(h(), 'POST', '/event', { type: 'intercept-hit', app: 'com.ss.android.ugc.aweme', label: '抖音', reason: '工作时段拦截娱乐 App' })
      const pBounce = await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 1, screenOn: true },
      })
      assert.equal(pBounce.body.geminiAutoPull?.triggered, true)
      assert.equal(pBounce.body.geminiAutoPull?.reason, 'lock-bounce')

      // 3) 用户点 snooze_gemini 30 分钟 → 在延后窗内即便长时间娱乐也不再弹自动 Gemini
      const snz = await call(h(), 'POST', '/storyline', { action: 'snooze_gemini', minutes: 30 })
      assert.equal(snz.body.ok, true)
      assert.ok(snz.body.snoozeUntil > WORK_10AM)
      tc.mock.timers.setTime(WORK_10AM + 15 * 60_000)
      const pSnoozed = await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 12, screenOn: true },
      })
      assert.equal(pSnoozed.body.geminiAutoPull, undefined, 'snooze 期间不得自动拉起 Gemini')

      // 4) GET /wall/boss-brief 真实聚合全局账本数据
      const boss = await call(h(), 'GET', '/wall/boss-brief')
      assert.equal(boss.body.ok, true)
      assert.ok(boss.body.snapshot.blockedCount >= 2, `期望读到真实撞锁计数 >=2，实际 ${boss.body.snapshot.blockedCount}`)
      assert.ok(boss.body.snapshot.planBlocksSummary.todayTotal >= 1)
      assert.match(boss.body.bossPrompt, /冷血直属主管|老板全局审视/)
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('Contract 8 (Closed-Loop 1A+2A+3A + 过量法则): verify块到点自动接管→逾期60s升P3验收锁→防空过→主管终审解锁与双鱼6只手职责边界', async (tc) => {
  // 2026-11-23 09:30 Asia/Shanghai = 01:30 UTC (minuteOfDay = 570)
  const T_0930 = Date.UTC(2026, 10, 23, 1, 30, 0)
  tc.mock.timers.enable({ apis: ['Date'], now: T_0930 })
  try {
    await withHome(async () => {
      const sis = agentOf(SIS)
      const big = agentOf(BIG)
      const h = host({ [SIS]: sis, [BIG]: big })
      const personaBefore = sis.contexts[0].text()
      const sisTruth = sis.contexts.find((c) => c.name === 'dafeiyu-lock-truth')

      // 关掉白天工作墙以隔离测试计划时间块自身的 P4 自动接管与 P3 验收锁
      await call(h(), 'POST', '/seals', { action: 'toggle_day_wall', enabled: false })
      await call(h(), 'POST', '/config', { control: true, minGapMs: 0 })

      // 1A 验证：已过点的普通 strict 块不得误触发 P3 验收锁
      await call(h(), 'POST', '/plan-contract', {
        action: 'upsert_block',
        block: { id: 'blk-strict-past', startMin: 550, endMin: 565, title: '已结束普通严格块', lockMode: 'strict', allowedApps: ['md.obsidian'] },
      })
      const pStrictPast = (await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 1, screenOn: true },
      })).body
      assert.notEqual(pStrictPast.lockArbiter.priority, 'P3', 'strict 块过期绝不能触发 P3 验收锁')

      // 建立一个即将开始的 verify 块 (09:40-10:00 = 580-600)
      await call(h(), 'POST', '/plan-contract', {
        action: 'upsert_block',
        block: { id: 'blk-verify-1', startMin: 580, endMin: 600, title: '资料分析20题硬验收', deliverable: '交20题正确率与3条错因', lockMode: 'verify', allowedApps: ['md.obsidian'] },
      })

      // 2A 验证：09:45 未手动点开干，发动机心跳自动激活 P4 白名单接管
      tc.mock.timers.setTime(T_0930 + 15 * 60_000) // 09:45 (585m)
      const pActive = (await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 1, screenOn: true },
      })).body
      assert.equal(pActive.lockArbiter.priority, 'P4')
      assert.equal(pActive.lock.mode, 'allowlist')
      assert.ok(pActive.lock.allow.includes('md.obsidian'))

      const ovActive = (await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview
      assert.equal(ovActive.hands.length, 6)
      assert.equal(ovActive.winnerId, 'BOUT_STRICT')
      for (const hand of ovActive.hands) {
        assert.ok(String(hand.duty || '').trim().length > 0, `hand ${hand.id} 必须声明清晰 duty`)
      }

      // 禁止 verify 块直接用 set_block_status=done 伪过关
      const fakeDone = await call(h(), 'POST', '/plan-contract', { action: 'set_block_status', blockId: 'blk-verify-1', status: 'done' })
      assert.equal(fakeDone.body.ok, false)

      // 1A 逾期 60s 升级 P3：10:02 (endMin + 2m) 触发 CONTRACT_OVERDUE_STRICT，禁止 strict_off 与 spend
      tc.mock.timers.setTime(T_0930 + 32 * 60_000) // 10:02 (602m)
      const pOverdue = (await call(h(), 'POST', '/plan', {
        roll: 0,
        timeZone: TZ,
        phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 1, screenOn: true },
      })).body
      assert.equal(pOverdue.lockArbiter.priority, 'P3')
      assert.equal(pOverdue.lockArbiter.source, 'CONTRACT_OVERDUE_STRICT')
      assert.ok(pOverdue.lock.allow.includes('md.obsidian'))

      const ovOverdue = (await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview
      assert.equal(ovOverdue.winnerId, 'CONTRACT_OVERDUE_STRICT')
      assert.equal(ovOverdue.verifyLockCenter.overdueCount, 1)
      assert.equal(ovOverdue.verifyLockCenter.overdueBlocks[0].id, 'blk-verify-1')

      const strictOffBlocked = await call(h(), 'POST', '/seals', { action: 'strict_off' })
      assert.equal(strictOffBlocked.body.ok, false)

      const spendBlocked = await call(h(), 'POST', '/seals', { action: 'spend', minutes: 10, reason: '我想摸鱼刷一下视频休息' })
      assert.equal(spendBlocked.body.ok, false)

      // 主管通过路由下达硬指令 → 不可删除/不可跳过
      const dirRes = await call(h(), 'POST', '/wall/boss-chat', {
        action: 'issue_directive',
        title: '主管追问错题归纳',
        deliverable: '提交不少于12字错因条文',
        durationMin: 45,
      })
      assert.equal(dirRes.body.ok, true)
      const bossBlockId = dirRes.body.block.id
      assert.equal(dirRes.body.block.lockedByBoss, true)
      assert.equal((await call(h(), 'POST', '/plan-contract', { action: 'delete_block', blockId: bossBlockId })).body.ok, false)
      assert.equal((await call(h(), 'POST', '/plan-contract', { action: 'set_block_status', blockId: bossBlockId, status: 'skipped' })).body.ok, false)

      // 3A 防空过：无交付时 pass 被拒；提交 <12 字被拒
      const passEmpty = await call(h(), 'POST', '/wall/boss-chat', { action: 'judge_delivery', blockId: 'blk-verify-1', verdict: 'pass', note: '空口过' })
      assert.equal(passEmpty.body.ok, false)
      assert.equal(passEmpty.body.error, 'no-delivery')

      const subShort = await call(h(), 'POST', '/plan-contract', { action: 'submit_delivery', blockId: 'blk-verify-1', summary: '太短了' })
      assert.equal(subShort.body.ok, false)

      // 提交 >=12 字真实交付 → 仍保持 P3 直到终审；reject 仍维持 P3
      const subOk = await call(h(), 'POST', '/plan-contract', {
        action: 'submit_delivery',
        blockId: 'blk-verify-1',
        summary: '已完成20道资料分析真题正确率80%并附3条速算误差复盘',
      })
      assert.equal(subOk.body.ok, true)
      assert.equal(subOk.body.block.verifyStatus, 'submitted')

      const rejRes = await call(h(), 'POST', '/wall/boss-chat', {
        action: 'judge_delivery',
        blockId: 'blk-verify-1',
        verdict: 'reject',
        note: '请补全3条具体错题编号',
      })
      assert.equal(rejRes.body.ok, true)
      assert.equal(rejRes.body.verdict, 'reject')
      assert.equal((await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview.winnerId, 'CONTRACT_OVERDUE_STRICT')

      // 重交并通过两块验收（普通验收块 + 主管硬指令）→ P3 解除 + 入账奖励 + verifyClearedToday=true
      await call(h(), 'POST', '/plan-contract', {
        action: 'submit_delivery',
        blockId: 'blk-verify-1',
        summary: '第3/8/15题基期量错算已按截位规则重算并写入错题本完毕',
      })
      const pass1 = await call(h(), 'POST', '/wall/boss-chat', {
        action: 'judge_delivery',
        blockId: 'blk-verify-1',
        verdict: 'pass',
        note: '物理定位到具体题号，放行',
      })
      assert.equal(pass1.body.ok, true)
      assert.ok(pass1.body.rewardMin > 0)

      await call(h(), 'POST', '/plan-contract', {
        action: 'submit_delivery',
        blockId: bossBlockId,
        summary: '已完成主管硬指令全部错因归纳并附带Obsidian笔记增量',
      })
      const pass2 = await call(h(), 'POST', '/wall/boss-chat', {
        action: 'judge_delivery',
        blockId: bossBlockId,
        verdict: 'pass',
        note: '双块均已核验通过',
      })
      assert.equal(pass2.body.ok, true)

      const ovCleared = (await call(h(), 'POST', '/seals', { action: 'locks_overview' })).body.locksOverview
      assert.equal(ovCleared.verifyClearedToday, true)
      assert.equal(ovCleared.verifyLockCenter.clearedToday, true)
      assert.notEqual(ovCleared.winnerId, 'CONTRACT_OVERDUE_STRICT')

      // 验证双鱼真相上下文 <= 6 行且原人设主干/权限隔离未被篡改或污染
      const truthLines = sisTruth.text().split('\n')
      assert.ok(truthLines.length <= 6, `真相上下文不得超6行，实际 ${truthLines.length}`)
      assert.equal(sis.contexts.filter((c) => c.name === 'dafeiyu-lock-truth').length, 1)
      const curPersonaText = sis.contexts[0].text()
      assert.ok(curPersonaText.startsWith('你是鱼丸，主人的好朋友（他姐姐鲸澜的妹妹）'))
      assert.doesNotMatch(curPersonaText, /冷血直属主管|dafeiyu-wall-boss/)
      assert.ok(personaBefore.startsWith('你是鱼丸'))
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('Contract 9 (Path 1 + Path 2 + Tri-Node Snapshot): 自动开局哨兵跨夜保留→托管冲刺轨防删改→打回自适应2x25m缩块→Re-brief→Bridge快照透传', async (tc) => {
  // 2026-11-22 23:55 Asia/Shanghai = 2026-11-22 15:55 UTC
  const T_22_2355 = Date.UTC(2026, 10, 22, 15, 55, 0)
  // 2026-11-23 09:15 Asia/Shanghai = 2026-11-23 01:15 UTC (minuteOfDay = 555)
  const T_23_0915 = Date.UTC(2026, 10, 23, 1, 15, 0)
  const origFetch = globalThis.fetch
  tc.mock.timers.enable({ apis: ['Date'], now: T_22_2355 })
  try {
    await withHome(async () => {
      const sis = agentOf(SIS)
      const big = agentOf(BIG)
      const h = host({ [SIS]: sis, [BIG]: big })

      // 关掉白天工作墙避免干扰今日计划块自身 P3/托管 测试
      await call(h(), 'POST', '/seals', { action: 'toggle_day_wall', enabled: false })
      await call(h(), 'POST', '/config', { control: true, minGapMs: 0 })

      // 1) 昨日开启自动哨兵策略 + 留一条未完成块跨夜
      const polRes = await call(h(), 'POST', '/plan-contract', {
        action: 'set_custody_policy',
        autoKickoffEnabled: true,
        adaptiveSplitEnabled: true,
      })
      assert.equal(polRes.body.ok, true)
      assert.equal(polRes.body.dayPlan.custodyPolicy.autoKickoffEnabled, true)

      const yestRes = await call(h(), 'POST', '/plan-contract', {
        action: 'upsert_block',
        block: {
          id: 'blk-yest-carry',
          startMin: 900,
          endMin: 945,
          title: '昨日遗留资料分析20题',
          deliverable: '提交20题笔记',
          lockMode: 'gentle',
          status: 'pending',
        },
      })
      assert.equal(yestRes.body.ok, true)

      // 2) 跨夜到次日 09:15：策略保留 + 昨日未完自动入列 + 自动哨兵钉入 25m 硬验收块（仅触发 1 次）
      tc.mock.timers.setTime(T_23_0915)
      const st0915 = (await call(h(), 'POST', '/plan-contract', { action: 'status' })).body
      assert.equal(st0915.ok, true)
      assert.equal(st0915.dayPlan.custodyPolicy.autoKickoffEnabled, true, 'custodyPolicy 必须跨夜保留')
      assert.ok(
        st0915.dayPlan.carryoverItems.some((c) => String(c.title).includes('昨日遗留资料分析20题')),
        '跨夜自动带入昨日未完任务',
      )
      const kickoffBlks = st0915.dayPlan.dayBlocks.filter((b) => b.source === 'auto_kickoff')
      assert.equal(kickoffBlks.length, 1, '09:15 无 verify 块时应自动钉入 1 个 25m 破冰验收块')
      assert.equal(kickoffBlks[0].lockMode, 'verify')
      assert.equal(kickoffBlks[0].lockedByBoss, true)
      assert.equal(kickoffBlks[0].endMin - kickoffBlks[0].startMin, 25)
      assert.ok(kickoffBlks[0].title.includes('昨日遗留资料分析20题'))
      assert.ok(st0915.dayPlan.firedKickoffSlots.includes('09:15'))

      // 同槽位不重复触发
      tc.mock.timers.setTime(T_23_0915 + 5 * 60_000)
      const st0920 = (await call(h(), 'POST', '/plan-contract', { action: 'status' })).body
      assert.equal(st0920.dayPlan.dayBlocks.filter((b) => b.source === 'auto_kickoff').length, 1)

      // 3) 签发路径 2 串联托管冲刺轨 + 防删改/防降级契约
      const cusRes = await call(h(), 'POST', '/wall/boss-chat', {
        action: 'issue_sprint_custody',
        title: '上午双连冲刺轨',
        blocks: [
          {
            title: '第一棒 资料分析25m',
            deliverable: '交20题截图与3条错因',
            durationMin: 25,
            allowedApps: ['md.obsidian', 'com.tencent.mm'],
          },
          {
            title: '第二棒 申论大作文50m',
            deliverable: '交三段提纲写进Obsidian',
            durationMin: 50,
            allowedApps: ['md.obsidian', 'tv.danmaku.bili', 'com.tencent.mm'],
          },
        ],
      })
      assert.equal(cusRes.body.ok, true)
      assert.equal(cusRes.body.custody.active, true)
      assert.match(String(cusRes.body.custody.untilHm || ''), /^\d{2}:\d{2}$/)
      assert.equal(cusRes.body.blocks.length, 2)
      const blk1 = cusRes.body.blocks[0]
      const blk2 = cusRes.body.blocks[1]

      const delGuarded = await call(h(), 'POST', '/plan-contract', { action: 'delete_block', blockId: blk1.id })
      assert.equal(delGuarded.body.ok, false)
      assert.equal(delGuarded.body.error, 'custody-locked')

      const genGuarded = await call(h(), 'POST', '/plan-contract', { action: 'generate_default', energyLevel: 'low' })
      assert.equal(genGuarded.body.ok, false)
      assert.equal(genGuarded.body.error, 'custody-locked')

      const downGuarded = await call(h(), 'POST', '/plan-contract', {
        action: 'upsert_block',
        block: { ...blk2, lockMode: 'gentle' },
      })
      assert.equal(downGuarded.body.ok, false)
      assert.equal(downGuarded.body.error, 'custody-locked')

      // 4) 第一棒提交交付后被主管打回 -> 第二棒 50m 大块自动拆为 2x25m 并收窄白名单
      await call(h(), 'POST', '/plan-contract', {
        action: 'submit_delivery',
        blockId: blk1.id,
        summary: '只做了一半题目，先把半成品思路放上来审一下',
      })
      const rejRes = await call(h(), 'POST', '/wall/boss-chat', {
        action: 'judge_delivery',
        blockId: blk1.id,
        verdict: 'reject',
        note: '缺完整题量与错因步骤，打回并缩紧下一段节奏',
      })
      assert.equal(rejRes.body.ok, true)
      assert.equal(rejRes.body.adapted, true)

      const afterAdapt = (await call(h(), 'POST', '/plan-contract', { action: 'status' })).body
      assert.ok(afterAdapt.dayPlan.adaptiveLog.length >= 1)
      const splitChildren = afterAdapt.dayPlan.dayBlocks.filter((b) => b.adaptedFrom === blk2.id && !b.isBreak)
      assert.equal(splitChildren.length, 2, '第二棒 50m 大块应被自动拆成 2 段短块')
      assert.equal(splitChildren[0].endMin - splitChildren[0].startMin, 25)
      assert.equal(splitChildren[0].lockMode, 'verify')
      assert.deepEqual(splitChildren[0].allowedApps, ['md.obsidian'])

      // 5) 第一棒到点逾期 -> P3 验收锁 + bridgeSnapshot 透传 dayblock P3 与 custody
      const pastBlk1EndMs = T_23_0915 + ((blk1.endMin - 555) * 60_000) + 75_000
      tc.mock.timers.setTime(pastBlk1EndMs)

      let capturedSync = null
      globalThis.fetch = async (url, init) => {
        if (String(url).startsWith('http://127.0.0.1:8787/bridge/sync')) {
          capturedSync = JSON.parse(String(init?.body || '{}'))
          return new Response(JSON.stringify({ ok: true, commands: [] }), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          })
        }
        return origFetch(url, init)
      }
      const syncRes = await call(h(), 'POST', '/gemini-bridge', {
        action: 'config',
        url: 'http://127.0.0.1:8787',
        key: 'dfy_contract9_test',
      })
      assert.equal(syncRes.body.ok, true)
      assert.equal(syncRes.body.synced, true)
      assert.ok(capturedSync?.snapshot, '配置桥后应立即推送完整 bridgeSnapshot')
      assert.equal(capturedSync.snapshot.locking?.kind, 'dayblock')
      assert.equal(capturedSync.snapshot.custody?.active, true)
      assert.match(String(capturedSync.snapshot.custody?.untilHm || ''), /^\d{2}:\d{2}$/)
      assert.ok((capturedSync.snapshot.verifyLockCenter?.overdueCount || 0) >= 1)
      await call(h(), 'POST', '/gemini-bridge', { action: 'forget' })

      // 6) 受控 Re-brief：短理由拦截，>=6字理由允许重签
      const badRebrief = await call(h(), 'POST', '/plan-contract', { action: 'rebrief_custody', reason: '不想干了' })
      assert.equal(badRebrief.body.ok, false)
      const okRebrief = await call(h(), 'POST', '/plan-contract', {
        action: 'rebrief_custody',
        reason: '突发临时外出办事需切为25m保底重签轨',
        downgradeLowEnergy: true,
      })
      assert.equal(okRebrief.body.ok, true)
      assert.ok((okRebrief.body.custody?.rebriefCount || 0) >= 1)
      assert.equal(okRebrief.body.custody?.active, true)
    })
  } finally {
    globalThis.fetch = origFetch
    tc.mock.timers.reset()
  }
})
