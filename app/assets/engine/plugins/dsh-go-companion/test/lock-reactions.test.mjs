// 2026-11-22 锁的连锁反应：他真机上「开了局锁不住」的三个原因各钉一条，再把几条锁叠在一起时谁说了算钉住。
//   1. 硬干预上限用满后，他自己开的局被降成「只提醒」→ 现在只降系统的打扰，局照锁。
//   2. 引擎重启后局被收成空局、锁跟着没了 → 局锁落盘，重启后照锁，到点才解。
//   3. 温和局 1 分钟就被预案升严格、严格锁固定 6 小时 → 温和不自动升；时长按他选的分钟，能延长，严格不能缩短 / 改温和。
//   另：余额用完（不在干活、不在默认验收）进 Gemini 模态；晚间娱乐用系统使用记录兜底，不再少记。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { buildLockDecision, decideApp } from '../lib/lock_decision.js'
import { eveningStep } from '../lib/verifier_mode.js'
import { NIGHT_LOCK_FROM, NIGHT_LOCK_TO } from '../lib/rhythm.js'
import { minuteOfDay } from '../lib/tz.js'
import { computeUnifiedLockArbiter } from '../lib/unified_arbiter.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const TZ = 'Asia/Shanghai'
const BILI = 'tv.danmaku.bili'
const GEMINI = 'com.google.android.apps.bard'

function agentOf(id) {
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts: [], tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

/** 起一个插件进程（同一个 DSH_HOME 再起一次 = 引擎重启）。 */
function boot() {
  let handler
  const agents = { [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) }
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
  const call = (method, path, body) => {
    const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
    req.method = method
    req.url = PREFIX + path
    req.headers = { 'content-type': 'application/json' }
    const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
    return Promise.resolve(handler(req, res)).then(() => (res.text ? JSON.parse(res.text) : null))
  }
  return { call, agents }
}

const installed = [
  { pkg: BILI, label: '哔哩哔哩' }, { pkg: 'com.ss.android.ugc.aweme', label: '抖音' }, { pkg: 'md.obsidian', label: 'Obsidian' },
  { pkg: 'com.tencent.mm', label: '微信' }, { pkg: GEMINI, label: 'Gemini' }, { pkg: 'com.android.chrome', label: 'Chrome' },
]
const phone = (app, extra = {}) => ({ app, label: app, fun: app === BILI, minutes: 2, screenOn: true, installedApps: installed, today: [], ...extra })

async function withHome(fn, cfg = {}) {
  const home = mkdtempSync(join(tmpdir(), 'companion-lockrx-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    // 默认验收关掉、没有饭点：只看局 / 上限 / 余额这几条锁本身（时段相关的另有用例）。
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: SIS, strategistSessionId: BIG, timeZone: TZ, quietStart: 12, quietEnd: 12, lockShadow: false,
      verifyMode: { enabled: false }, wall: { meals: [] }, ...cfg,
    }))
    return await fn({ home })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}
const plan = (call, p) => call('POST', '/plan', { roll: 0, timeZone: TZ, phone: p })
const locked = (r, pkg) => decideApp(r.lock, pkg).lock || Boolean(r.session && !r.session.allow.includes(pkg))
const json = (home, name) => JSON.parse(readFileSync(join(home, 'companion', name), 'utf8'))
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: TZ }).format(new Date())
/** 睡觉时间（0–8 点）里深夜锁盖过局：这几条局的用例在那几个钟点只核「锁着」，不核「本局 App 放行」。 */
const isNight = () => {
  const m = minuteOfDay(Date.now(), TZ)
  return m >= NIGHT_LOCK_FROM && m < NIGHT_LOCK_TO
}

test('严格局：B 站锁住；引擎重启后照锁（局锁落盘），GET /bouts 还看得到这一局；到点前收局才解', async () => {
  await withHome(async ({ home }) => {
    let e = boot()
    const o = await e.call('POST', '/bouts', { action: 'open', protocolId: 'execute', title: '刷行测', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, strict: true, minutes: 30 })
    assert.equal(o.ok, true, JSON.stringify(o))
    assert.equal(o.mode, 'strict')
    const r1 = await plan(e.call, phone(BILI))
    assert.equal(r1.session?.kind, 'strict')
    assert.ok(locked(r1, BILI), 'B 站被锁')
    if (!isNight()) assert.ok(!locked(r1, 'md.obsidian'), '本局 App 放行')
    assert.ok(r1.session.until <= Date.now() + 30 * 60_000 + 5000, '到点（30 分钟）解，不是 6 小时')

    // 引擎重启：同一个 home 再起一次
    e = boot()
    const r2 = await plan(e.call, phone(BILI))
    assert.equal(r2.session?.kind, 'strict', '重启后锁还在')
    assert.ok(locked(r2, BILI))
    const b = await e.call('GET', '/bouts')
    assert.equal(b.running?.interrupted, true, '重启后局的记录中断了，但局锁照样显示')
    assert.equal(b.strict, true)
    // 收局 = 把锁也收了
    const c = await e.call('POST', '/bouts', { action: 'finish' })
    assert.equal(c.ok, true, JSON.stringify(c))
    const r3 = await plan(e.call, phone(BILI))
    assert.notEqual(r3.session?.kind, 'strict')
    assert.equal(json(home, 'bout-lock.json'), null)
  })
})

test('硬干预上限用满：系统不再主动打扰，但他自己开的严格局 / 温和局照锁', async () => {
  await withHome(async ({ home }) => {
    writeFileSync(join(home, 'companion', 'hard-interventions.json'), JSON.stringify({
      v: 1, day: today(), count: 40, items: Array.from({ length: 40 }, (_, i) => ({ kind: 'lock', at: Date.now() - i * 60_000 })),
    }))
    const e = boot()
    await e.call('POST', '/bouts', { action: 'open', protocolId: 'execute', title: '刷题', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, strict: true })
    const r = await plan(e.call, phone(BILI))
    assert.notEqual(r.lockArbiter?.source, 'HARD_CAP_DOWNGRADE', JSON.stringify(r.lockArbiter))
    assert.equal(r.lockArbiter?.source, isNight() ? 'NIGHT_CURFEW' : 'BOUT_STRICT')
    assert.ok(locked(r, BILI), '上限用满了，严格局照样锁 B 站')
    await e.call('POST', '/bouts', { action: 'finish' })
    // 温和局同样
    await e.call('POST', '/bouts', { action: 'open', protocolId: 'execute', title: '整理笔记', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] } })
    const g = await plan(e.call, phone(BILI))
    assert.equal(g.lock?.mode, 'allowlist')
    assert.ok(locked(g, BILI), '温和局：娱乐锁着')
    if (!isNight()) {
      assert.ok(!locked(g, GEMINI), '温和局：Gemini 能用')
      assert.ok(!locked(g, 'md.obsidian'))
    }
  })
})

test('时长：能延长；严格局不能缩短、不能中途改温和；温和局能缩短、能改严格', async () => {
  await withHome(async () => {
    const e = boot()
    await e.call('POST', '/bouts', { action: 'open', protocolId: 'execute', title: '刷题', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, strict: true, minutes: 30 })
    const longer = await e.call('POST', '/bouts', { action: 'adjust', minutes: 60 })
    assert.equal(longer.ok, true, longer.toast)
    assert.equal(longer.minutes, 60)
    const shorter = await e.call('POST', '/bouts', { action: 'adjust', minutes: 20 })
    assert.equal(shorter.ok, false)
    assert.match(shorter.toast, /只能延长/)
    const relax = await e.call('POST', '/bouts', { action: 'relax' })
    assert.equal(relax.ok, false)
    await e.call('POST', '/bouts', { action: 'finish' })
    await e.call('POST', '/bouts', { action: 'open', protocolId: 'execute', title: '整理', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, minutes: 45 })
    const gShort = await e.call('POST', '/bouts', { action: 'adjust', minutes: 25 })
    assert.equal(gShort.ok, true, gShort.toast)
    const up = await e.call('POST', '/bouts', { action: 'strict' })
    assert.equal(up.ok, true, up.toast)
    const r = await plan(e.call, phone(BILI))
    assert.equal(r.session?.kind, 'strict')
    assert.equal(await e.call('POST', '/bouts', { action: 'adjust' }).then((x) => x.ok), false, '没给分钟数')
  })
})

test('破窗是出口：严格局锁一起收掉', async () => {
  await withHome(async ({ home }) => {
    const e = boot()
    await e.call('POST', '/bouts', { action: 'open', protocolId: 'execute', title: '刷题', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, strict: true })
    const b = await e.call('POST', '/event', { type: 'breakglass', minutes: 10 })
    assert.equal(b.ok, true, JSON.stringify(b))
    assert.equal(json(home, 'bout-lock.json'), null)
  })
})

test('鱼开的局也走局锁（不再另开 AI 专时）：严格 + 他说的分钟', async () => {
  await withHome(async ({ home }) => {
    const e = boot()
    const tool = e.agents[BIG].tools.open_bout || e.agents[SIS].tools.open_bout
    assert.ok(tool, '有 open_bout')
    const r = await tool.execute({
      protocol: 'execute', goal: '资料分析 20 题', first: '打开 Obsidian 写错题', deliverable: '错题.md 写满 300 字',
      accept_kind: 'evidence', accept_what: '错题.md 文件变长', need: ['md.obsidian'], minutes: 40, why: '他说开始',
    })
    assert.equal(r.ok, true, r.text)
    const bl = json(home, 'bout-lock.json')
    assert.equal(bl.mode, 'strict')
    assert.equal(bl.minutes, 40)
    const cfg = json(home, 'config.json')
    assert.ok(!cfg.aiWorkSession, '不再另开一份 AI 专时锁')
  })
})

test('Gemini 模态：余额用完、不在干活 → 只剩学习、Gemini、NekoBox、通讯；B 站和浏览器锁', () => {
  const tiers = { [BILI]: 'fun', 'com.android.chrome': 'other', 'md.obsidian': 'work', [GEMINI]: 'other', 'com.tencent.mm': 'other' }
  const d = buildLockDecision({
    arbiter: null, work: { active: false }, installed, tierOf: (p) => tiers[p] || 'other',
    quota: { funLeft: 0, funOut: true, gameOut: false, line: '娱乐余额 0' }, geminiModeOnFunOut: true,
  })
  assert.equal(d.mode, 'allowlist')
  assert.equal(d.geminiMode, true)
  assert.equal(decideApp(d, BILI).lock, true)
  assert.equal(decideApp(d, 'com.android.chrome').lock, true)
  assert.equal(decideApp(d, GEMINI).lock, false)
  assert.equal(decideApp(d, 'md.obsidian').lock, false)
  assert.equal(decideApp(d, 'com.tencent.mm').lock, false, '通讯不锁（不成砖）')
  // 余额还有：自由态，什么都不锁
  const free = buildLockDecision({ installed, tierOf: (p) => tiers[p] || 'other', quota: { funLeft: 20, funOut: false }, geminiModeOnFunOut: true })
  assert.equal(free.mode, 'free')
  assert.equal(decideApp(free, BILI).lock, false)
  // 开关关掉：退回旧行为（只锁娱乐层）
  const old = buildLockDecision({ installed, tierOf: (p) => tiers[p] || 'other', quota: { funLeft: 0, funOut: true }, geminiModeOnFunOut: false })
  assert.equal(old.mode, 'free')
  assert.equal(decideApp(old, BILI).lock, true)
  assert.equal(decideApp(old, 'com.android.chrome').lock, false)
})

test('晚间娱乐：引擎 6 点后醒晚了也不少记（系统使用记录兜底，取大的）', () => {
  // 18:00 前已经玩了 20；18:05 开始又玩 8 分钟；引擎 18:13 才第一次醒——起点抓成 28。
  const first = eveningStep(null, { key: '2026-11-22', beforeMidnight: true, funToday: 28, cap: 90, now: 1, slotUsed: 8 })
  assert.equal(first.state.used, 8, '以前这里是 0')
  const later = eveningStep(first.state, { key: '2026-11-22', beforeMidnight: true, funToday: 30, cap: 90, now: 2, slotUsed: 10 })
  assert.equal(later.state.used, 10, '玩了 10 分钟就记 10')
  // 没有系统记录时照旧按差值
  const noSlot = eveningStep(null, { key: 'k', beforeMidnight: true, funToday: 5, cap: 90, now: 1 })
  assert.equal(noSlot.state.used, 0)
})

test('睡觉时间（0–8 点）盖过一切局：严格局、温和局、默认验收都排在它后面', () => {
  assert.equal(NIGHT_LOCK_FROM, 0)
  assert.equal(NIGHT_LOCK_TO, 8 * 60)
  const a = computeUnifiedLockArbiter({
    wall: { locked: true, kind: 'night', why: '深夜' },
    presetStrict: { allow: ['md.obsidian'], until: Date.now() + 3_600_000, why: '严格局' },
    now: Date.now(),
  })
  assert.equal(a.source, 'NIGHT_CURFEW')
  assert.equal(a.priority, 'P1')
})
