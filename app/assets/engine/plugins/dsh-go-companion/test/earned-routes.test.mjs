// R9 第四项接线：挣来的放风额度接到真实插件上以后的行为（走 /plan）。
//
// earned.test.mjs 钉的是纯规则；这里钉的是「接线」：
//   - 工作时段的娱乐不松——挣来的额度买不到白天；
//   - 非工作时段有额度：默认锁不推；
//   - 额度为 0：默认锁照墙的时间表回来。
// 墙的时间表由真实时钟决定，所以每条都用同一份墙规则算一遍期望值（和 preset-routes 同一种做法）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { wallDecision, WALL_KIND_DEFAULT } from '../lib/wall.js'
import { minuteOfDay, startOfDay } from '../lib/tz.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const TZ = 'Asia/Shanghai'

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

function host(agents, state) {
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
  setStatePin(state)
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

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-earned-'))
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
      // Windows 上 sqlite 句柄还没放；断言已跑完，别把好测试判成失败。
    }
  }
}

/** 写一份带额度的账。额度按「今天」算，换天就归零。 */
function seedEarned(home, minutes, now = Date.now()) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  const day = startOfDay(now, TZ)
  writeFileSync(join(home, 'companion', 'earned.json'), JSON.stringify({ day, earned: minutes, spent: 0 }))
}

function earnedFile(home) {
  return JSON.parse(readFileSync(join(home, 'companion', 'earned.json'), 'utf8'))
}

/**
 * 把工作时段设成一段「肯定不包含此刻」的 1 分钟窗口：模拟非工作时段。
 * 这样不依赖真实时钟落在哪，covers() 的结论是确定的。
 */
function offWorkWindows(now = Date.now()) {
  const m = (minuteOfDay(now, TZ) + 720) % 1440
  return [[Math.min(m, 1439), Math.min(m, 1439) + 1]]
}

function setup(home, { state = 'standby', minutes = 0, windows } = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  const cfg = { sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12 }
  // 工作日 / 休息日都要设：今天是不是周末由真实时钟决定，只设一种会让这条测试看运气。
  if (windows) cfg.day = { windows: { workday: windows, restday: windows, outing: windows } }
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify(cfg))
  if (minutes > 0) seedEarned(home, minutes)
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big }, state)
  const plan = (phone) => call(h(), 'POST', '/plan', { roll: 0, timeZone: TZ, phone })
  const presets = (body) => call(h(), 'POST', '/presets', body)
  // 「全天默认锁」预案默认是停用的：不打开它，墙本来就不推默认锁，这一组测试就没有对象。
  const lockOn = () => presets({ action: 'set', preset: { id: 'default-lock', 状态: '生效' } })
  return { sis, big, plan, presets, lockOn, h }
}

const at = (app, extra = {}) => ({ app, fun: false, minutes: 0, screenOn: true, ...extra })
const kindsOf = (r) => (r.body.rules?.rules || []).map((x) => x.kind)

test('工作时段有额度也不松：挣来的放风买不到白天', async () => {
  await withHome(async (home) => {
    const t = setup(home, { minutes: 60, windows: [[0, 1440]] })
    await t.lockOn()
    const r = await t.plan(at('com.miui.home'))
    const want = wallDecision({}, Date.now(), TZ)
    assert.equal(kindsOf(r).includes(WALL_KIND_DEFAULT), want.locked && want.kind === WALL_KIND_DEFAULT)
  })
})

test('非工作时段有额度：默认锁不推', async () => {
  await withHome(async (home) => {
    const t = setup(home, { minutes: 60, windows: offWorkWindows() })
    await t.lockOn()
    const r = await t.plan(at('com.miui.home'))
    assert.equal(kindsOf(r).includes(WALL_KIND_DEFAULT), false, '默认锁不该在：' + kindsOf(r).join(','))
  })
})

test('非工作时段没有额度：默认锁照墙的时间表回来', async () => {
  await withHome(async (home) => {
    const t = setup(home, { minutes: 0, windows: offWorkWindows() })
    await t.lockOn()
    const r = await t.plan(at('com.miui.home'))
    const want = wallDecision({}, Date.now(), TZ)
    assert.equal(kindsOf(r).includes(WALL_KIND_DEFAULT), want.locked && want.kind === WALL_KIND_DEFAULT)
  })
})

test('额度用完了：默认锁回来（这条走的是 spent 而不是 earned）', async () => {
  await withHome(async (home) => {
    const t = setup(home, { minutes: 15, windows: offWorkWindows() })
    await t.lockOn()
    // 手工把额度花光：covers 只看 earned - spent。
    const day = startOfDay(Date.now(), TZ)
    writeFileSync(join(home, 'companion', 'earned.json'), JSON.stringify({ day, earned: 15, spent: 15 }))
    const r = await t.plan(at('com.miui.home'))
    const want = wallDecision({}, Date.now(), TZ)
    assert.equal(kindsOf(r).includes(WALL_KIND_DEFAULT), want.locked && want.kind === WALL_KIND_DEFAULT)
  })
})

test('这条接线真的跑到了：/plan 会给账记下这次盘账的时刻', async () => {
  await withHome(async (home) => {
    const t = setup(home, { minutes: 60, windows: offWorkWindows() })
    await t.lockOn()
    await t.plan(at('com.miui.home'))
    const e = earnedFile(home)
    assert.ok(Number(e.spentAt) > 0, 'earnedOpen 没跑到，账上不会有 spentAt')
    assert.equal(e.earned, 60, '盘账不该把额度清零')
  })
})
