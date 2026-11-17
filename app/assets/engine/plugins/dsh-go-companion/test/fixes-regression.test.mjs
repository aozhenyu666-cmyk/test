// 本次真实问题回归测试：
// 1. 局插件 listBeats 缺失修复 & GET /bouts + POST /bouts (open/start/strict/close/finish) 完整可用
// 2. 空 allow 局不再触发全机锁；strictNow 无 App 时拒绝而非锁死全机
// 3. Gemini 训练模式永不自锁 Gemini，不被 ladder E1′ 劫持；gemini_end / dispatch_control 彻底解锁
// 4. phone.today 保留至多 24 项（含高打开次数的 0 分钟 App）；behavior_radar 自主触发并唤醒发球

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply, setStatePin } from '../lib/index.js'
import { GEMINI_PACKAGES } from '../lib/gemini.js'
import { evalBehaviorRadar } from '../lib/behavior_radar.js'
import { cleanPhone } from '../lib/phone.js'
import { createPresetRuntime } from '../lib/presetwire.js'

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
  const globalTools = {}
  const ctx = {
    get(n) {
      if (n === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (n === 'connection') return { requestRejection: () => undefined }
      if (n === 'sessionController') return { resolveAgent: async ({ sessionId }) => ({ agent: agents[sessionId || SIS] }) }
      if (n === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    tools: { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(f) { f() } }) },
    effect(f) { f() },
  }
  setStatePin(state)
  apply(ctx)
  return { handler: () => handler, globalTools }
}

function call(handler, method, path, body) {
  const url = path.startsWith('/') ? '/dsh-go-companion/v1' + path : '/dsh-go-companion/v1/' + path
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = url
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0, text: '',
    writeHead(s) { this.statusCode = s },
    end(c) { this.text = c ? String(c) : '' },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-fixes-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {
      // Windows 下部分测试持有 sqlite 句柄时忽略临时目录清理错误
    }
  }
}

function setup(home, cfg = {}) {
  mkdirSync(join(home, 'fish-memory'), { recursive: true })
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: SIS,
    strategistSessionId: BIG,
    dual: true,
    quietStart: 12,
    quietEnd: 12,
    minGapMs: 0,
    diceThreshold: 100,
    ...cfg,
  }))
  const db = new DatabaseSync(join(home, 'fish-memory', 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.close()

  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big })
  return { h, sis, big, plan: (phone) => call(h.handler(), 'POST', '/plan', { phone }) }
}

test('GET /bouts 与 POST /bouts 支持开局/切严格/收局，且 listBeats 已导入不会 ReferenceError', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const opened = await call(t.h.handler(), 'POST', '/bouts', {
      action: 'start',
      protocolId: 'execute',
      title: '写周报',
      allow: ['md.obsidian'],
      strict: true,
    })
    assert.equal(opened.status, 200)
    assert.equal(opened.body.ok, true, JSON.stringify(opened.body))

    const status = await call(t.h.handler(), 'GET', '/bouts')
    assert.equal(status.status, 200)
    assert.equal(status.body.ok, true)
    assert.ok(status.body.running, 'GET /bouts 应返回 running 局')
    assert.equal(status.body.strict, true, 'strict=true 开局后应处于严格模式')
    assert.ok(Array.isArray(status.body.protocols) && status.body.protocols.length > 0)
    assert.ok(status.body.snapshot, 'boutsSnapshot 不应再因缺 listBeats 崩溃')

    const closed = await call(t.h.handler(), 'POST', '/bouts', { action: 'finish' })
    assert.equal(closed.status, 200)
    assert.equal(closed.body.ok, true)
  })
})

test('presetwire：未声明 App 的局绝不下发 allow=[] 的全机锁', async () => {
  let doc = {}
  const blocks = []
  let bout = { id: 'b-empty', kind: 'execute', openedAt: Date.now() }
  const rt = createPresetRuntime({
    load: () => doc,
    save: (d) => { doc = d },
    record: () => {},
    runningBout: () => bout,
    ignore: () => [],
    day: () => '2026-11-17',
    minute: () => 600,
    funToday: () => 0,
    apply: {
      block: (app, until, why) => blocks.push({ app, until, why }),
      allLock: () => {},
      tool: () => {},
      plan: () => {},
      tighten: () => {},
    },
  })
  const noAppsStrict = rt.strictNow(Date.now(), '手动严格', 'panel')
  assert.equal(noAppsStrict.ok, false, '没有声明任何 App 时不应启动全机锁')
  assert.equal(rt.sessionView(Date.now()), null)

  // 即使触发 off-dwell 停留娱乐，也只封娱乐 App，不生成 allow=[] 全机 session
  const now = Date.now()
  rt.tick({ app: 'com.ss.android.ugc.aweme', fun: true, minutes: 3, switches: { recent: [] } }, now)
  assert.equal(rt.sessionView(now), null)
  assert.ok(blocks.some((b) => b.app === 'com.ss.android.ugc.aweme'))
})

test('Gemini 训练模式永不自锁 Gemini，且 unlock_focus / unlock_gemini / gemini_end 可完整解锁', async () => {
  await withHome(async (home) => {
    // 先预置脏的阶梯 all-lock 记录，验证主动开 gemini_live 会清空它
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'ladder.json'), JSON.stringify({
      cell: 'all-lock',
      cellUntil: Date.now() + 3_600_000,
      tickAt: Date.now(),
    }))
    const t = setup(home, { ladderOk: true })
    const startRes = t.sis.tools.gemini_live.execute({ topic: '线性代数复盘', minutes: 30, mode: 'study' })
    assert.equal(startRes.ok, true)

    // 1) 在 think 态下，gemini_end 可完整清掉 Gemini + ladder + strict
    const endRes = t.sis.tools.gemini_end.execute({ reason: '学完了' })
    assert.equal(endRes.ok, true)

    // 2) 重开 gemini_live，再发 /plan（非 Gemini 前台），ladderE1Prime 不得劫持为 all-lock
    const startAgain = t.sis.tools.gemini_live.execute({ topic: '线性代数复盘', minutes: 30, mode: 'study' })
    assert.equal(startAgain.ok, true)
    const p1 = await t.plan({
      app: 'com.tencent.mm', label: '微信', minutes: 2, fun: false, screenOn: true, local: '10:30',
      today: [{ app: 'com.tencent.mm', label: '微信', minutes: 2 }],
    })
    assert.ok(p1.body.session, '应存在 Gemini session')
    assert.ok(p1.body.session.allow.includes(GEMINI_PACKAGES[0]), 'allow 必须包含 Gemini 本体')
    assert.notEqual(p1.body.session.cell, 'all-lock')

    const dc = t.h.globalTools.dispatch_control || t.big.tools.dispatch_control
    assert.ok(dc, '应注册 dispatch_control 工具')
    // 小鱼在任意状态下都可用 unlock_gemini 直接关闭误锁与 Gemini 连麦舱
    const ug = dc.execute({ action: 'unlock_gemini', why: '用户让小鱼关掉' }, { sessionId: SIS })
    assert.equal(ug.ok, true, ug.text)

    // 再开一个 strict bout + focus_lock，由大鱼用 dispatch_control(unlock_focus) 一并解掉
    await call(t.h.handler(), 'POST', '/bouts', { action: 'open', protocolId: 'execute', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, strict: true })
    const u = dc.execute({ action: 'unlock_focus', why: '用户收工' }, { sessionId: BIG })
    assert.equal(u.ok, true, u.text)
    // 小鱼调 unlock_focus 仍按非对称权限被拒绝
    assert.equal(dc.execute({ action: 'unlock_focus' }, { sessionId: SIS }).ok, false)
    const after = await t.plan({ app: 'md.obsidian', label: 'Obsidian', minutes: 1, fun: false, screenOn: true, local: '10:40' })
    assert.equal(after.body.session, null, '所有 session 锁应当已清零')
    assert.equal(after.body.clearWorkSession, true, '应当下发 clearWorkSession 让手机释放工作锁')
  })
})

test('phone.today 保留 24 项高打开记录，行为雷达在累计娱乐超 60 分钟与上午未立项时一定触发发球', async () => {
  const rawToday = Array.from({ length: 24 }, (_, i) => ({
    app: `com.example.app${i}`,
    label: `应用${i}`,
    minutes: i === 23 ? 0 : i + 1,
    opens: i === 23 ? 5 : 1,
    fun: i === 0,
  }))
  const cleaned = cleanPhone({ app: 'md.obsidian', label: 'Obsidian', minutes: 2, screenOn: true, local: '09:30', today: rawToday })
  assert.equal(cleaned.today.length, 24)

  const budgetHit = evalBehaviorRadar({
    phone: { app: 'tv.danmaku.bili', label: 'B站', minutes: 2, fun: true, screenOn: true },
    funTotalToday: 65,
    now: Date.UTC(2026, 10, 17, 6, 0, 0), // 北京时间 14:00
    runningBout: null,
  })
  assert.equal(budgetHit.triggered, true)
  assert.equal(budgetHit.kind, 'daily-fun-budget')

  const morningHit = evalBehaviorRadar({
    phone: { app: 'com.tencent.mm', label: '微信', minutes: 7, fun: false, screenOn: true, switches: { in15m: 3 } },
    now: Date.UTC(2026, 10, 17, 1, 30, 0), // 北京时间 09:30
    morningPlanned: false,
    runningBout: null,
  })
  assert.equal(morningHit.triggered, true)
  assert.equal(morningHit.kind, 'unplanned-nudge')
  assert.equal(morningHit.forceLevel, 0)

  await withHome(async (home) => {
    const t = setup(home, { diceThreshold: 0 }) // 即使骰子为 0，一定触发器仍须置 speak=true
    const p = await t.plan({
      app: 'com.miHoYo.Yuanshen',
      label: '原神',
      minutes: 6,
      fun: true,
      screenOn: true,
      local: '14:30',
      today: [{ app: 'com.miHoYo.Yuanshen', label: '原神', minutes: 6, fun: true }],
    })
    assert.equal(p.body.speak, true, '游戏连玩行为雷达应当绕过 dice=0 必定发球')
    assert.equal(p.body.behaviorRadar?.kind, 'game-binge')
  })
})
