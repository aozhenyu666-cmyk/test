import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'

import { apply, setStatePin } from '../lib/index.js'
import { builtinProtocolOf, brokenBuiltins } from '../lib/builtin.js'
import { composeBoutProtocol } from '../lib/protocol.js'
import { openBoutsDb, listProtocols, getDayPlan } from '../lib/bouts.js'

const withHome = async (fn) => {
  const home = mkdtempSync(join(tmpdir(), 'companion-p3-'))
  const prev = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    await fn(home)
  } finally {
    setStatePin(null)
    if (prev === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = prev
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {
      // ignore locked temp sqlite on windows
    }
  }
}

const agentOf = (id) => {
  const contexts = []
  const sent = []
  const tools = {}
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    sent,
    contexts,
    tools,
    followup(m) { sent.push(m) },
    ctx: {
      systemPrompt: {
        context(entry) {
          contexts.push(entry)
          return () => { entry.removed = true }
        },
      },
      tools: {
        register(tool) {
          tools[tool.name] = tool
          return () => { delete tools[tool.name] }
        },
      },
    },
  }
}

const host = (agents = {}, state = 'plan') => {
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
  return { handler: () => handler }
}

const call = (handler, method, url, body) =>
  new Promise((resolve, reject) => {
    const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
    req.method = method
    req.url = url
    req.headers = { 'content-type': 'application/json' }
    let status = 200
    let text = ''
    const res = {
      statusCode: 200,
      setHeader() {},
      writeHead(s) { status = s },
      end(chunk) {
        if (chunk) text += chunk
        try {
          resolve({ status: status || res.statusCode, body: text ? JSON.parse(text) : {} })
        } catch (err) {
          reject(err)
        }
      },
    }
    Promise.resolve(handler(req, res)).catch(reject)
  })

test('M4.2: 新增三套标杆/兜底协议通过 J2 校验、支持别名查询且已登记 Android 资产清单', () => {
  assert.deepEqual(brokenBuiltins(), [])
  const v = builtinProtocolOf('video_slice_dice')
  const c = builtinProtocolOf('commute_anti_daydream')
  const m = builtinProtocolOf('micro_landing_3m')
  assert.equal(v?.id, 'video-slice-dice')
  assert.equal(c?.id, 'commute-anti-daydream')
  assert.equal(m?.id, 'micro-landing-3m')
  assert.ok(v.needs.includes('留空填词'))
  assert.ok(c.needs.includes('拉起 Gemini Live'))
  assert.equal(m.budget.minutes, 5)

  const manifestKt = readFileSync(
    join(import.meta.dirname, '..', '..', '..', '..', '..', 'android', 'app', 'src', 'main', 'kotlin', 'io', 'github', 'ccch1mneyyy', 'dsh_app', 'runtime', 'BuiltinPluginGuest.kt'),
    'utf8',
  )
  assert.ok(manifestKt.includes('"lib/protocols/video_slice_dice.json"'))
  assert.ok(manifestKt.includes('"lib/protocols/commute_anti_daydream.json"'))
  assert.ok(manifestKt.includes('"lib/protocols/micro_landing_3m.json"'))
})

test('M4.2: composeBoutProtocol 强制执行权限隔离、能力白名单、禁止墙越权及防死循环检查', () => {
  const deniedRole = composeBoutProtocol({ id: 'sis-hack', name: '姐妹越权局', goal: '试一下' }, { role: 'sister' })
  assert.equal(deniedRole.ok, false)
  assert.equal(deniedRole.error, 'only-strategist-can-compose')

  const wallInject = composeBoutProtocol({
    id: 'bad-wall',
    name: '越权局',
    needs: ['全机锁', '信用分'],
    steps: [{ id: 's1', ask: '说一句？', limit: '2m', timeout: '收局' }],
  }, { role: 'strategist' })
  assert.equal(wallInject.ok, false)
  assert.ok(wallInject.errors.some((e) => e.code === 'wall-capability'))

  const missingCap = composeBoutProtocol({
    id: 'bad-cap',
    name: '缺能力局',
    needs: ['不存在的读心术'],
    steps: [{ id: 's1', ask: '说一句？', limit: '2m', timeout: '收局' }],
  }, { role: 'strategist' })
  assert.equal(missingCap.ok, false)
  assert.ok(missingCap.errors.some((e) => e.code === 'missing-capability'))
  assert.equal(missingCap.proposal?.kind, 'missing-capability')

  const loopTimeout = composeBoutProtocol({
    id: 'bad-loop',
    name: '死循环局',
    steps: [{ id: 's1', ask: '死锁在这步？', limit: '2m', timeout: '重打' }],
  }, { role: 'strategist' })
  assert.equal(loopTimeout.ok, false)
  assert.ok(loopTimeout.errors.some((e) => e.code === 'no-exit'))

  const valid = composeBoutProtocol({
    id: 'cache-drill',
    name: '缓存排查专局',
    goal: '修好缓存穿透漏洞',
    minutes: 30,
  }, { role: 'strategist' })
  assert.equal(valid.ok, true)
  assert.equal(valid.protocol.id, 'cache-drill')
  assert.equal(valid.protocol.steps.length, 2)
})

test('M4.2: /protocols/compose 与 compose_bout_protocol 工具端到端入库 day_plans 并可立即开局', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12,
    }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    const h = host({ 'session-sis': sis, 'session-big': big }, 'plan')
    assert.ok(big.tools.compose_bout_protocol, 'plan 状态下应当挂载 compose_bout_protocol 工具')

    const okViaBig = await big.tools.compose_bout_protocol.execute({ id: 'test-1', name: '正常', goal: '写代码' })
    assert.equal(okViaBig.ok, true)
    const viaSis = await sis.tools.compose_bout_protocol.execute({ id: 'test-sis', name: '越权', goal: '试一下' })
    assert.equal(viaSis.ok, false)
    assert.equal(viaSis.code, 'only-strategist-can-compose')

    const res = await call(h.handler(), 'POST', '/dsh-go-companion/v1/protocols/compose', {
      id: 'weakness-drill',
      name: '短时弱项专攻局',
      goal: '补齐 SQLite 事务边界测试',
      minutes: 20,
      department: 'dept:strategize',
    })
    assert.equal(res.status, 200)
    assert.equal(res.body.ok, true)
    assert.equal(res.body.protocol.id, 'weakness-drill')

    const openRes = await call(h.handler(), 'POST', '/dsh-go-companion/v1/bouts', {
      action: 'open',
      protocolId: 'weakness-drill',
      department: 'dept:strategize',
    })
    assert.equal(openRes.status, 200)
    assert.equal(openRes.body.ok, true, openRes.body.toast)

    const db = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
    try {
      const protos = listProtocols(db)
      assert.ok(protos.some((p) => p.id === 'weakness-drill' && p.status === 'trial'))
      const dp = getDayPlan(db, res.body.day)
      assert.ok(dp?.queue?.some((q) => q.protocolId === 'weakness-drill' && q.department === 'dept:strategize'))
    } finally {
      db.close()
    }
  })
})
