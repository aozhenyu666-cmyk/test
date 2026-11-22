// 2026-11-22 Wave 2 阶段 1+2 统一接线集成测试：
// 1. [#18] 默认启用单一裁决器执法 (arbiterEnforce 默认 true，POST /config 可切回 false 再切回 true)
// 2. [4.3] 开局自动带预设 App 放行范围（零手选）：boutsRoute / todayStartRoute + GET /bouts running & queue
// 3. [4.2] 局自动关联今日下一个未交验收点：live.sealId / live.sealTitle + GET /bouts running
// 4. [4.6] 微信等中立 App 工作时段 35m 总量：/plan 下 ≤35m 放行、>=35m 落入 needPass
// 5. [4.5] Gemini 温和折算展示接入：sealsRoute + bridgeSnapshot + todayViewV4

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { WORK_DEFAULT } from '../lib/app_tiers.js'
import { ALL_TOOLS } from '../lib/machine.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'

const ZONES = [
  'Asia/Shanghai', 'Europe/London', 'America/Sao_Paulo', 'America/New_York', 'America/Chicago', 'America/Denver',
  'America/Los_Angeles', 'America/Anchorage', 'Pacific/Honolulu', 'Asia/Tokyo', 'Europe/Berlin', 'Europe/Moscow',
  'Asia/Kolkata', 'Asia/Dubai', 'Australia/Sydney', 'Pacific/Auckland', 'Atlantic/Azores', 'America/Halifax',
]
function minuteOfDayOf(now, tz) {
  const p = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(now))
  return Number(p.find((x) => x.type === 'hour').value) * 60 + Number(p.find((x) => x.type === 'minute').value)
}
const DAY_TZ = ZONES.find((z) => { const m = minuteOfDayOf(Date.now(), z); return m >= 9 * 60 && m < 17 * 60 }) || 'Asia/Shanghai'

function dayStringOf(now, tz) {
  const p = new Intl.DateTimeFormat('en-CA', { timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(now))
  return `${p.find((x) => x.type === 'year').value}-${p.find((x) => x.type === 'month').value}-${p.find((x) => x.type === 'day').value}`
}

function agentOf(id) {
  const tools = {}
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    sent: [],
    contexts: [],
    tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
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
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
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
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn, cfg = {}) {
  const home = mkdtempSync(join(tmpdir(), 'companion-w2s12-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: SIS,
      strategistSessionId: BIG,
      timeZone: DAY_TZ,
      lockTimeZone: false,
      quietStart: 12,
      quietEnd: 12,
      ...cfg,
    }))
    const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    const get = (path) => call(h(), 'GET', path).then((r) => r.body)
    const post = (path, body) => call(h(), 'POST', path, body).then((r) => r.body)
    return await fn({ home, get, post, h })
  } finally {
    setStatePin(null)
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try { rmSync(home, { recursive: true, force: true }) } catch {}
  }
}

test('红线校验：ALL_TOOLS 保持 41 把且 Gemini 仍在 WORK_DEFAULT', () => {
  assert.equal(ALL_TOOLS.length, 41)
  assert.equal(WORK_DEFAULT.includes('com.google.android.apps.bard'), true)
})

test('[#18] arbiterEnforce 默认 true，POST /config 可显式切回影子模式且持久化生效', async () => {
  await withHome(async ({ home, get, post }) => {
    const s0 = await get('/status')
    assert.equal(s0.arbiterEnforce, true)
    assert.equal(s0.config.arbiterEnforce, true)

    const t0 = await get('/today')
    assert.equal(t0.arbiterEnforce, true)

    const seals0 = await post('/seals', { action: 'list' })
    assert.equal(seals0.arbiterEnforce, true)

    const p0 = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'md.obsidian', fun: false, minutes: 1, screenOn: true },
    })
    assert.equal(p0.arbiterEnforce, true)

    const off = await post('/config', { arbiterEnforce: false })
    assert.equal(off.config.arbiterEnforce, false)
    assert.equal((await get('/status')).arbiterEnforce, false)
    assert.equal((await get('/today')).arbiterEnforce, false)
    assert.equal((await post('/seals', { action: 'list' })).arbiterEnforce, false)
    assert.equal((await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'md.obsidian', fun: false, minutes: 1, screenOn: true },
    })).arbiterEnforce, false)

    // 模拟重启：磁盘中显式存有 arbiterEnforce: false 时保持 false
    const hRestart = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    const sRestart = await call(hRestart(), 'GET', '/status')
    assert.equal(sRestart.body.arbiterEnforce, false)

    const on = await call(hRestart(), 'POST', '/config', { arbiterEnforce: true })
    assert.equal(on.body.config.arbiterEnforce, true)
    assert.equal((await call(hRestart(), 'GET', '/status')).body.arbiterEnforce, true)
  }, { verifyMode: { enabled: false } })
})

test('[4.3 & 4.2] 开局零手选自动带预设放行范围 + 自动关联今日下一个未交验收点', async () => {
  await withHome(async ({ home, get, post }) => {
    const today = dayStringOf(Date.now(), DAY_TZ)

    // 预置 3 条今日验收点：1 条已过保底、1 条最早未交、1 条较晚未交
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({
      v: 1,
      items: [
        { id: 's-done', day: today, title: '早间背词已完成', kind: 'app_minutes', stretch: 20, floor: 10, dueMinute: 9 * 60, status: 'floor', actual: 12 },
        { id: 's-late', day: today, title: '晚间申论写作', kind: 'file', stretch: 800, floor: 400, dueMinute: 21 * 60, status: 'open', actual: 0 },
        { id: 's-next', day: today, title: '下午行测刷题 30 道', kind: 'external_quiz', stretch: 30, floor: 15, dueMinute: 16 * 60, status: 'open', actual: 0 },
      ],
    }))

    // 先上报手机已装 App（含学习软件和娱乐软件）
    await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: {
        app: 'md.obsidian',
        fun: false,
        minutes: 1,
        screenOn: true,
        installedApps: [
          { pkg: 'com.fenbi.android.solar', label: '粉笔' },
          { pkg: 'com.youdao.dict', label: '有道词典' },
          { pkg: 'com.ss.android.ugc.aweme', label: '抖音' },
          { pkg: 'com.tencent.tmgp.sgame', label: '王者荣耀' },
        ],
      },
    })

    // 加一条待开局排程进队列，检验 GET /bouts queue 也输出 scopeKey/scopeLabel/allow
    const addedTask = await post('/today/task', { action: 'add', title: '申论大作文写作', minutes: 45, allow: [] })
    assert.equal(addedTask.ok, true)

    const beforeStart = await get('/bouts')
    assert.equal(beforeStart.queue.length >= 1, true)
    assert.equal(beforeStart.queue[0].scopeKey, 'write')
    assert.equal(beforeStart.queue[0].scopeLabel, '写作/笔记')
    assert.equal(beforeStart.queue[0].allow.includes('md.obsidian'), true)

    // 1) 通过 POST /today/start 不手选 allow、不传 sealId 开局
    const started = await post('/today/start', {
      goal: '行测刷题 30 道',
      minutes: 45,
      strict: true,
    })
    assert.equal(started.ok, true)
    assert.equal(started.scopeKey, 'exam')
    assert.equal(started.scopeLabel, '刷题/行测')
    assert.equal(started.allow.includes('com.fenbi.android.solar'), true)
    assert.equal(started.allow.includes('com.youdao.dict'), true)
    assert.equal(started.allow.includes('com.google.android.apps.bard'), true)
    assert.equal(started.allow.includes('com.ss.android.ugc.aweme'), false, '娱乐软件硬过滤')
    assert.equal(started.allow.includes('com.tencent.tmgp.sgame'), false, '游戏软件硬过滤')
    assert.equal(started.sealId, 's-next')
    assert.equal(started.sealTitle, '下午行测刷题 30 道')

    const b1 = await get('/bouts')
    assert.equal(b1.running.scopeKey, 'exam')
    assert.equal(b1.running.scopeLabel, '刷题/行测')
    assert.equal(b1.running.sealId, 's-next')
    assert.equal(b1.running.sealTitle, '下午行测刷题 30 道')
    assert.equal(b1.running.allow.includes('com.fenbi.android.solar'), true)

    await post('/bouts', { action: 'finish' })

    // 2) 通过 POST /bouts open 显式传 sealId（并留空 allow）开共脑局
    const opened = await post('/bouts', {
      action: 'open',
      protocolId: 'study',
      title: '和 Gemini 深度共脑岗位选择',
      sealId: 's-late',
      minutes: 30,
    })
    assert.equal(opened.ok, true)
    assert.equal(opened.scopeKey, 'think')
    assert.equal(opened.scopeLabel, '深度共脑(含Gemini)')
    assert.equal(opened.sealId, 's-late')
    assert.equal(opened.sealTitle, '晚间申论写作')

    const b2 = await get('/bouts')
    assert.equal(b2.running.scopeKey, 'think')
    assert.equal(b2.running.scopeLabel, '深度共脑(含Gemini)')
    assert.equal(b2.running.sealId, 's-late')
    assert.equal(b2.running.sealTitle, '晚间申论写作')

    await post('/bouts', { action: 'finish' })
  }, { verifyMode: { enabled: false } })
})

test('[4.6] 工作时段微信+QQ 共享 35m 总量：/plan 下未超额直接放行，达到/超过 35m 落入 needPass', async () => {
  await withHome(async ({ post }) => {
    // 开一局严格专注（未手动把微信放进 allow）
    const started = await post('/today/start', {
      goal: '行测刷题 30 道',
      minutes: 45,
      strict: true,
    })
    assert.equal(started.ok, true)
    assert.equal(started.allow.includes('com.tencent.mm'), false)

    // 1) 微信 20m + QQ 10m = 30m < 35m -> 自动放行，不落入 needPass
    const pUnder = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: {
        app: 'com.tencent.mm',
        label: '微信',
        fun: false,
        minutes: 2,
        screenOn: true,
        today: [
          { app: 'com.tencent.mm', label: '微信', minutes: 20 },
          { app: 'com.tencent.mobileqq', label: 'QQ', minutes: 10 },
        ],
      },
    })
    assert.ok(pUnder.lock, '/plan 必须返回 lock 裁决')
    assert.equal(pUnder.lock.mode, 'allowlist')
    assert.deepEqual(pUnder.lock.commQuota, { usedMin: 30, limitMin: 35, exceeded: false })
    assert.equal(pUnder.lock.allow.includes('com.tencent.mm'), true)
    assert.equal(pUnder.lock.allow.includes('com.tencent.mobileqq'), true)
    assert.equal(pUnder.lock.needPass.includes('com.tencent.mm'), false)

    // 2) 微信 25m + QQ 10m = 35m >= 35m -> 不再自动放行，落入 needPass
    const pOver = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: {
        app: 'com.tencent.mm',
        label: '微信',
        fun: false,
        minutes: 2,
        screenOn: true,
        today: [
          { app: 'com.tencent.mm', label: '微信', minutes: 25 },
          { app: 'com.tencent.mobileqq', label: 'QQ', minutes: 10 },
        ],
      },
    })
    assert.deepEqual(pOver.lock.commQuota, { usedMin: 35, limitMin: 35, exceeded: true })
    assert.equal(pOver.lock.allow.includes('com.tencent.mm'), false)
    assert.equal(pOver.lock.needPass.includes('com.tencent.mm'), true)
    assert.equal(pOver.lock.needPass.includes('com.tencent.mobileqq'), true)

    await post('/bouts', { action: 'finish' })
  }, { verifyMode: { enabled: false } })
})

test('[4.5] Gemini 温和折算接入 /seals、/today 与 bridgeSnapshot', async () => {
  await withHome(async ({ home, get, post }) => {
    const today = dayStringOf(Date.now(), DAY_TZ)

    // 1) 今日有 1 条未交验收点，且今日 Gemini 已用 25m + 有 1 条 pass 回执
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({
      v: 1,
      items: [
        { id: 's-g1', day: today, title: '申论写作练习', kind: 'file', stretch: 500, floor: 300, dueMinute: 23 * 60, status: 'open', actual: 0 },
      ],
    }))
    writeFileSync(join(home, 'companion', 'gemini-receipts.json'), JSON.stringify({
      v: 1,
      items: [
        { id: 'gr-1', day: today, at: Date.now(), verdict: 'pass', topic: '资料分析' },
      ],
    }))

    await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: {
        app: 'com.google.android.apps.bard',
        label: 'Gemini',
        fun: false,
        minutes: 2,
        screenOn: true,
        geminiMinutesToday: 25,
        today: [{ app: 'com.google.android.apps.bard', label: 'Gemini', minutes: 25 }],
      },
    })

    const sealsBefore = await post('/seals', { action: 'list' })
    assert.equal(sealsBefore.arbiterEnforce, true)
    assert.deepEqual(sealsBefore.geminiAccounting, {
      totalMin: 25,
      withReceiptMin: 20,
      unreceiptedMin: 5,
      receiptCount: 1,
      freeMode: false,
      summaryLine: '今日 Gemini 共 25m（有回执学习约 20m · 无回执 5m）',
    })

    const todayBefore = await get('/today')
    assert.equal(todayBefore.geminiAccounting?.summaryLine, '今日 Gemini 共 25m（有回执学习约 20m · 无回执 5m）')

    // 2) bridgeSnapshot 也附带 geminiAccounting
    let capturedSnap = null
    const oldFetch = globalThis.fetch
    globalThis.fetch = async (_url, init) => {
      capturedSnap = JSON.parse(String(init?.body || '{}')).snapshot
      return { ok: true, status: 200, text: async () => JSON.stringify({ ok: true, commands: [] }) }
    }
    try {
      const bRes = await post('/gemini-bridge', { action: 'config', url: 'http://127.0.0.1:19999', key: 'test-key' })
      assert.equal(bRes.synced, true)
      assert.equal(capturedSnap?.geminiAccounting?.totalMin, 25)
      assert.equal(capturedSnap?.geminiAccounting?.freeMode, false)
      assert.equal(typeof capturedSnap?.verify?.emergency, 'boolean')
      assert.equal(typeof capturedSnap?.verify?.nightLock, 'boolean')
      assert.equal(capturedSnap?.verify?.emergency, false)

      // 授予紧急放行后再同步一次，确认 verify.emergency 立即为 true
      const ep = await post('/emergency-pass', { reason: '桥同步校验' })
      assert.equal(ep.ok, true)
      const bRes2 = await post('/gemini-bridge', { action: 'sync' })
      assert.equal(bRes2.ok, true)
      assert.equal(capturedSnap?.verify?.emergency, true)
      await post('/emergency-pass', { action: 'revoke' })
      await post('/gemini-bridge', { action: 'forget' })
    } finally {
      globalThis.fetch = oldFetch
    }

    // 3) 今日所有验收点全部达保底后，切换为 freeMode = true
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({
      v: 1,
      items: [
        { id: 's-g1', day: today, title: '申论写作练习', kind: 'file', stretch: 500, floor: 300, dueMinute: 23 * 60, status: 'floor', actual: 320 },
      ],
    }))
    const sealsAfter = await post('/seals', { action: 'list' })
    assert.equal(sealsAfter.geminiAccounting.freeMode, true)
    assert.equal(sealsAfter.geminiAccounting.summaryLine, '今日保底已达标 · Gemini 自由探索中（已用 25m）')
  }, { verifyMode: { enabled: false } })
})
