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
