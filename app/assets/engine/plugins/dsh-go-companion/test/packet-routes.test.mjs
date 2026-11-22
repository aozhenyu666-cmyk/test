// 第九轮第三项：接线（验收前置 + 开局带工作包）。
//
// packet.test.mjs 钉的是纯规则；这里钉的是「真的挡住了 / 真的带上了」：
//   - 缺交付物或验收方式的工作包，/plan/day 拒收并点名；
//   - 都写全了就定得下来，工作包跟着计划存盘；
//   - 「判断」类一天最多两个，第三个拒收；
//   - 计划到点自动开局把工作包带上，局官第一句就是工作包的第一步；
//   - 面板 /bouts 开局带工作包，第一句也是第一步；
//   - 没有工作包的局，行为跟以前一模一样。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { minuteOfDay } from '../lib/tz.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-packet-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    // Windows：这一局开过 sqlite，句柄没放就 EPERM。断言已经跑完，收尾失败不算测试失败。
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {
      // 交给系统清理临时目录
    }
  }
}

function setup(home, extra = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, ...extra,
  }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big }, 'standby')
  const plan = (phone) => call(h(), 'POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone })
  const day = (body) => call(h(), 'POST', '/plan/day', body)
  const get = () => call(h(), 'GET', '/plan/day')
  const bouts = (body) => call(h(), 'POST', '/bouts', body)
  const presets = (body) => call(h(), 'POST', '/presets', body)
  const ledger = () => readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  return { sis, big, plan, day, get, bouts, presets, ledger, h }
}

const at = (app, extra = {}) => ({ app, fun: false, minutes: 0, screenOn: true, ...extra })

const PKT = {
  goal: '投出 3 份简历',
  first: '打开浏览器，看前 10 个岗位',
  steps: ['选 3 个', '改简历', '投', '截图'],
  deliverable: '3 张投递成功截图',
  accept: { kind: 'evidence', what: '截图时间在开局后，识图读到已投递，3 张' },
}

const item = (over = {}) => ({
  protocolId: 'study', title: '求职局', minute: 9 * 60, budget: 60,
  need: ['com.android.browser'], allow: ['com.android.browser'], ...over,
})

// ---------------------------------------------------------------- 验收前置

test('A1 工作包缺交付物：交不上去，点名是哪一局', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item({ packet: { ...PKT, deliverable: '' } })] })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'packet-missing')
    assert.deepEqual(r.body.missing, ['求职局'])
    assert.match(r.body.text, /缺交付物/)
  })
})

test('A2 工作包缺验收方式：同样交不上去', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item({ packet: { ...PKT, accept: { kind: '', what: '' } } })] })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'packet-missing')
    assert.match(r.body.text, /缺验收方式/)
  })
})

test('A3 第一步不是动作：交不上去，原因说得具体', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item({ packet: { ...PKT, first: '把简历弄好点' } })] })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'packet-missing')
    assert.match(r.body.text, /动词|应用|东西/)
  })
})

test('A4 工作包写全了就定得下来，字段原样存住', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item({ packet: PKT })] })
    assert.equal(r.body.ok, true, r.body.text)
    const p = r.body.plan.items[0].packet
    assert.equal(p.goal, '投出 3 份简历')
    assert.equal(p.first, '打开浏览器，看前 10 个岗位')
    assert.equal(p.deliverable, '3 张投递成功截图')
    assert.equal(p.accept.kind, 'evidence')
    assert.equal(p.steps.length, 4)
  })
})

test('A5 没有工作包的局照旧交得上去（草案本来就没有工作包）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item()] })
    assert.equal(r.body.ok, true, r.body.text)
    assert.equal(r.body.plan.items[0].packet, undefined)
  })
})

test('A6 两局里有一局不合格：整份都不收，只点名坏的那一局', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({
      action: 'set',
      items: [item({ title: '求职局', packet: PKT }), item({ title: '复习局', minute: 11 * 60, packet: { ...PKT, accept: { kind: 'evidence', what: '' } } })],
    })
    assert.equal(r.body.ok, false)
    assert.deepEqual(r.body.missing, ['复习局'])
  })
})

test('A7 缺应用优先报：既缺应用又缺工作包时，先按应用拒（老规则在前面）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item({ need: [], packet: { ...PKT, deliverable: '' } })] })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'apps-missing')
  })
})

test('A8 判断类验收一天最多两个：第三个拒收', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const judge = { ...PKT, accept: { kind: 'judge', what: '照事先写好的标准打分' } }
    const r = await t.day({
      action: 'set',
      items: [
        item({ title: '甲', minute: 9 * 60, packet: judge }),
        item({ title: '乙', minute: 11 * 60, packet: judge }),
        item({ title: '丙', minute: 14 * 60, packet: judge }),
      ],
    })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'judge-over')
    assert.match(r.body.text, /最多 2 个/)
  })
})

test('A9 两个判断类 + 一个证据类：刚好可以', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const judge = { ...PKT, accept: { kind: 'judge', what: '照标准打分' } }
    const r = await t.day({
      action: 'set',
      items: [
        item({ title: '甲', minute: 9 * 60, packet: judge }),
        item({ title: '乙', minute: 11 * 60, packet: judge }),
        item({ title: '丙', minute: 14 * 60, packet: PKT }),
      ],
    })
    assert.equal(r.body.ok, true, r.body.text)
  })
})

test('A10 拒收之后盘上没有半张计划：再读还是空的', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.day({ action: 'set', items: [item({ packet: { ...PKT, deliverable: '' } })] })
    const g = await t.get()
    assert.equal(g.body.ok, true)
    assert.equal(g.body.plan.items.length, 0)
  })
})

test('A11 GET 带着工作包一起读回来，手机能接着改', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.day({ action: 'set', items: [item({ packet: PKT })] })
    const g = await t.get()
    assert.equal(g.body.plan.items[0].packet.first, PKT.first)
    assert.equal(g.body.plan.items[0].packet.accept.kind, 'evidence')
  })
})

test('A12 GET 带十分钟倒计时：交上去生效了就是 0', async (tc) => {
  tc.mock.timers.enable({ apis: ['Date', 'setInterval'], now: Date.UTC(2026, 10, 14, 1, 0, 0) })
  try {
    await withHome(async (home) => {
      const t = setup(home)
      await t.day({ action: 'set', items: [item({ packet: PKT })] })
      const g = await t.get()
      assert.equal(g.body.acceptLeftMs, 0, '计划界面交上去就生效，不留倒计时')
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('A13 GET 带一句计划全文（鲸澜和手机看同一句）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.day({ action: 'set', items: [item({ packet: PKT })] })
    const g = await t.get()
    assert.equal(typeof g.body.sentence, 'string')
    assert.ok(g.body.sentence.length > 0)
  })
})

test('A14 脏工作包不炸：数组当 packet 就当没写，照旧能交', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item({ packet: ['乱写'] })] })
    assert.equal(r.body.ok, true, r.body.text)
  })
})

test('A15 工作包字段过长会被截住，不会把计划文件撑坏', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.day({ action: 'set', items: [item({ packet: { ...PKT, goal: '目'.repeat(300) } })] })
    assert.equal(r.body.ok, true, r.body.text)
    assert.equal(r.body.plan.items[0].packet.goal.length, 60)
  })
})

// ---------------------------------------------------------------- 开局带工作包

test('A16 计划到点自动开局：工作包跟着开局走，局官第一句是第一步', async (tc) => {
  tc.mock.timers.enable({ apis: ['Date', 'setInterval'], now: Date.UTC(2026, 10, 14, 1, 0, 0) })
  try {
    await withHome(async (home) => {
      const t = setup(home)
      const r = await t.day({ action: 'set', items: [item({ minute: minuteOfDay(Date.now(), 'Asia/Shanghai'), packet: PKT })] })
      assert.equal(r.body.ok, true, r.body.text)
      tc.mock.timers.tick(60_000)
      const open = t.ledger().find((e) => e.state === 'plan-open')
      assert.ok(open && open.ok, '到点自己开')
      // 局官第一句：念工作包的第一步，不是协议原本那句。
      const ask = await t.bouts({ action: 'ask' })
      assert.equal(ask.body.ok, true, JSON.stringify(ask.body))
      assert.ok(ask.body.say.includes('第一步做了吗'), ask.body.say)
      assert.ok(ask.body.say.includes(PKT.first), ask.body.say)
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('A17 自动开局的那一局也带上 App 要求（老行为不能丢）', async (tc) => {
  tc.mock.timers.enable({ apis: ['Date', 'setInterval'], now: Date.UTC(2026, 10, 14, 1, 0, 0) })
  try {
    await withHome(async (home) => {
      const t = setup(home)
      await t.day({ action: 'set', items: [item({ minute: minuteOfDay(Date.now(), 'Asia/Shanghai'), packet: PKT })] })
      tc.mock.timers.tick(60_000)
      const apps = (await t.presets({ action: 'list' })).body.bout?.apps
      assert.deepEqual(apps.need, ['com.android.browser'])
    })
  } finally {
    tc.mock.timers.reset()
  }
})

test('A18 面板 /bouts 开局带工作包：返回的 say 就是第一步', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.bouts({ action: 'open', protocolId: 'study', apps: { need: ['com.android.browser'], allow: ['com.android.browser'] }, packet: PKT })
    assert.equal(r.body.ok, true, JSON.stringify(r.body))
    assert.ok(r.body.say.startsWith('第一步：' + PKT.first), r.body.say)
    assert.match(r.body.say, /交付物：3 张投递成功截图/)
  })
})

test('A19 面板开局不带工作包：还是协议原本那句话（老局不变）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.bouts({ action: 'open', protocolId: 'study' })
    assert.equal(r.body.ok, true)
    assert.ok(!r.body.say.includes('第一步：'), r.body.say)
  })
})

test('A20 工作包只认第一步和交付物之外的东西也不炸：只有目标也能开局', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    // 没有 first：openSay 返回空串，退回协议原本那句，不能开局失败。
    const r = await t.bouts({ action: 'open', protocolId: 'study', packet: { goal: '投简历' } })
    assert.equal(r.body.ok, true)
    assert.ok(!r.body.say.startsWith('第一步：'), r.body.say)
  })
})
