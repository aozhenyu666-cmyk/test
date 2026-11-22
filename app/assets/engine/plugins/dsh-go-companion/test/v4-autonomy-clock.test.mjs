import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { ClockKernel, nextCronAt, parseCron } from '../lib/clock_kernel.js'
import { classifyApp, isDualUseApp, isPureGame, resolveAppNames } from '../lib/app_resolver.js'
import { evalBehaviorRadar } from '../lib/behavior_radar.js'
import { detectSycophancy } from '../lib/strategist_audit.js'

function agentOf(id) {
  const contexts = []
  const tools = {}
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    contexts,
    tools,
    followup() {},
    ctx: {
      systemPrompt: { context(def) { contexts.push(def); return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents, state = 'standby') {
  let handler
  const globalTools = {}
  const listeners = new Map()
  const ctx = {
    tools: { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } },
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      if (name === 'tools') return { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } }
      return undefined
    },
    on(name, fn) {
      if (!listeners.has(name)) listeners.set(name, [])
      listeners.get(name).push(fn)
    },
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  setStatePin(state)
  apply(ctx)
  const emit = async (sid, type, data) => {
    for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, { type, data })
  }
  return { handler: () => handler, emit, globalTools }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-v4-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 })
  }
}

test('ClockKernel: 5/6 段 Cron、防漂移高频计时与多阶段番茄循环', () => {
  const cron5 = parseCron('30 8 * * 1-5')
  assert.ok(cron5)
  const monMorning = new Date('2026-11-16T08:25:00+08:00').getTime()
  const nextMon = nextCronAt(cron5, monMorning, 'Asia/Shanghai')
  assert.equal(nextMon, new Date('2026-11-16T08:30:00+08:00').getTime())

  const dir = mkdtempSync(join(tmpdir(), 'clk-v4-'))
  try {
    const file = join(dir, 'clock.json')
    const t0 = 1_700_000_000_000
    const ck = new ClockKernel(file)
    ck.upsert({ id: 'iv5', label: '5秒心率', kind: 'FIXED_RATE', intervalMs: 5000, anchorWall: t0, nextFireWall: t0 + 5000 }, t0)
    const r1 = ck.tick(t0 + 5700)
    assert.equal(r1.fires.length, 1)
    assert.equal(ck.tasks.get('iv5').nextFireWall, t0 + 10000, '漂移补偿必须保持锚点')

    ck.upsert({
      id: 'cyc1',
      label: '专注休息循环',
      kind: 'CYCLE_PHASE',
      maxRuns: 2,
      phases: [
        { name: '专注', durationMs: 25 * 60_000, strictLock: true, allowApps: ['md.obsidian'] },
        { name: '休息', durationMs: 5 * 60_000, strictLock: false, allowApps: [] },
      ],
    }, t0)
    const p = ck.pause('cyc1', t0 + 10 * 60_000)
    assert.equal(p.paused, true)
    assert.equal(p.pausedRemainingMs, 15 * 60_000)
    assert.equal(ck.tick(t0 + 30 * 60_000).fires.filter((f) => f.taskId === 'cyc1').length, 0)
    ck.resume('cyc1', t0 + 30 * 60_000)
    const rPhase = ck.tick(t0 + 45 * 60_000)
    const cycFires = rPhase.fires.filter((f) => f.taskId === 'cyc1')
    assert.equal(cycFires.length, 1)
    assert.equal(cycFires[0].nextPhase.name, '休息')

    const reloaded = new ClockKernel(file)
    assert.equal(reloaded.list().length, 2)
  } finally {
    rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 50 })
  }
})

test('app_resolver: 中文别名与手机安装列表模糊解析', () => {
  const installed = [
    { pkg: 'com.tencent.tmgp.sgame', label: '王者荣耀', bypass: false },
    { pkg: 'md.obsidian', label: 'Obsidian', bypass: false },
    { pkg: 'com.xiaomi.novel.custom', label: '小米免费小说阅读', bypass: true },
  ]
  const res = resolveAppNames(['王者', 'Obsidian', 'B站'], { installedApps: installed })
  assert.ok(res.resolved.includes('com.tencent.tmgp.sgame'))
  assert.ok(res.resolved.includes('md.obsidian'))
  assert.ok(res.resolved.includes('tv.danmaku.bili'))
  assert.equal(isPureGame('com.tencent.tmgp.sgame', '王者荣耀'), true)
  assert.equal(isDualUseApp('tv.danmaku.bili'), true)
  assert.equal(classifyApp('com.xiaomi.novel.custom', { label: '小米免费小说阅读', bypass: true }), 'fun')
})

test('evalBehaviorRadar: 早起未立项即玩 & 未收录信息流连刷 & 双用途租约偏航', () => {
  const tMorning = new Date('2026-11-16T08:15:00+08:00').getTime()
  const r1 = evalBehaviorRadar({
    phone: { app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes: 4, fun: true },
    now: tMorning,
    morningPlanned: false,
  })
  assert.equal(r1.triggered, true)
  assert.equal(r1.kind, 'morning-first-play')
  assert.equal(r1.forceLevel, 4)

  const tNoon = new Date('2026-11-16T14:00:00+08:00').getTime()
  const r2 = evalBehaviorRadar({
    phone: {
      app: 'com.vendor.shortvideo.feed',
      label: '某某短视频',
      minutes: 13,
      fun: false,
      installedApps: [{ pkg: 'com.vendor.shortvideo.feed', label: '某某短视频', bypass: true }],
    },
    now: tNoon,
    morningPlanned: true,
  })
  assert.equal(r2.triggered, true)
  assert.equal(r2.kind, 'feed-drift')

  const r3 = evalBehaviorRadar({
    phone: {
      app: 'tv.danmaku.bili',
      label: 'B站',
      minutes: 6,
      screenText: '正在进入直播间 送礼物 打赏',
    },
    now: tNoon,
    morningPlanned: true,
    activeLease: { app: 'tv.danmaku.bili', until: tNoon + 600_000, verifyHints: ['线性代数'] },
  })
  assert.equal(r3.triggered, true)
  assert.equal(r3.revokeLease, true)
  assert.equal(r3.kind, 'lease-drift')
})

test('dispatch_control / grant_task_lease 非对称权限 + 敷衍触发真实 refuseUntil', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: 'sis', strategistSessionId: 'big', quietStart: 12, quietEnd: 12,
    }))
    const sis = agentOf('sis')
    const big = agentOf('big')
    const h = host({ sis, big }, 'standby')

    assert.ok(h.globalTools.dispatch_control, '全局注册 core 调度工具')
    assert.ok(h.globalTools.clock_manage, '全局注册 clock 工具')
    assert.ok(h.globalTools.grant_task_lease, '全局注册租约工具（运行时校验角色）')
    assert.equal(sis.tools.grant_task_lease, undefined, '会话工具预算保持不变')

    // 小鱼调用 grant_task_lease 必须被 runtime 拦截；解锁必须被拒；小鱼锁定必须立刻生效并同步给 /plan session
    const sisCtx = { agent: { session: { id: 'sis' } } }
    const bigCtx = { agent: { session: { id: 'big' } } }
    assert.equal(h.globalTools.grant_task_lease.execute({ app: 'B站', minutes: 10, taskGoal: '摸鱼' }, sisCtx).ok, false)
    assert.equal(h.globalTools.dispatch_control.execute({ action: 'unlock_focus' }, sisCtx).ok, false)
    const lockRes = h.globalTools.dispatch_control.execute({ action: 'focus_lock', apps: ['Obsidian'], minutes: 30, why: '写完方案' }, sisCtx)
    assert.equal(lockRes.ok, true)

    const p1 = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', skipRadar: true })
    assert.equal(p1.status, 200)
    assert.equal(p1.body.session.strict, true)
    assert.ok(p1.body.session.allow.includes('md.obsidian'))

    // 纯游戏硬编码不可批租约
    const gameLease = h.globalTools.grant_task_lease.execute({ app: '王者荣耀', minutes: 10, taskGoal: '打一局' }, bigCtx)
    assert.equal(gameLease.ok, false)

    // 双用途 App 允许 ≤20m 租约
    const biliLease = h.globalTools.grant_task_lease.execute({ app: 'B站', minutes: 15, taskGoal: '看高数课', verifyHints: ['高数'] }, bigCtx)
    assert.equal(biliLease.ok, true)

    // 时钟工具 + /clock 路由一致性
    const clk = h.globalTools.clock_manage.execute({ op: 'create_cycle', label: '番茄时钟', focusMin: 25, breakMin: 5, cycles: 2, allowApps: ['Obsidian'] }, sisCtx)
    assert.equal(clk.ok, true)
    const clkGet = await call(h.handler(), 'GET', '/dsh-go-companion/v1/clock')
    assert.equal(clkGet.status, 200)
    assert.ok(clkGet.body.tasks.some((t) => t.label === '番茄时钟'))

    // 小鱼空泛吹捧被 detectSycophancy 拦下
    assert.equal(detectSycophancy('哥哥太棒了！', { role: 'companion' }).sycophantic, true)
    assert.equal(detectSycophancy('你把第三步写进了笔记，这点比昨天强；但 B 站那 20 分钟还是得扣。', { role: 'companion' }).sycophantic, false)
  })
})
