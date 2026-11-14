import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { listEscalations, getDayPlan, openBoutsDb } from '../lib/bouts.js'
import { readLedger } from '../lib/ledger.js'
import { executeCapability } from '../lib/capabilities.js'
import { openStore } from '../../dsh-go-memory/lib/store.js'
import { remember } from '../../dsh-go-memory/lib/model.js'

function agentOf(id) {
  const contexts = []
  const sent = []
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent, contexts, tools,
    followup(m) { sent.push(m) },
    ctx: {
      systemPrompt: { context(def) { contexts.push(def); return () => { def.removed = true } } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents, state = 'standby', llmStream = undefined) {
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
    inject(deps, cb) {
      if (Array.isArray(deps) && deps.includes('llm')) {
        cb(llmStream ? { llm: { stream: llmStream } } : {})
      } else {
        cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } })
      }
    },
    effect(fn) { fn() },
  }
  setStatePin(state)
  apply(ctx)
  return { handler: () => handler }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path.startsWith('/dsh-go-companion/v1') ? path : '/dsh-go-companion/v1' + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-closure-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try { rmSync(home, { recursive: true, force: true }) } catch {}
  }
}

test('PC telemetry eventRoute (pc-activity / pc_heartbeat) persists state and feeds chat telemetry snapshot', async () => {
  await withHome(async (home) => {
    const sis = agentOf('s-sis')
    const strat = agentOf('s-strat')
    const { handler } = host({ 's-sis': sis, 's-strat': strat }, 'standby')
    await call(handler(), 'POST', '/config', { sessionId: 's-sis', strategistSessionId: 's-strat' })

    const now = Date.now()
    const ev = await call(handler(), 'POST', '/event', {
      type: 'pc-activity',
      at: now,
      isAfk: false,
      activeApp: 'Code.exe',
      activeTitle: 'index.js - dafeiyu',
      category: 'work',
      focusMinutes: 42,
      entertainmentMinutes: 3,
      switches: 2,
    })
    assert.equal(ev.status, 200)
    assert.equal(ev.body.ok, true)
    assert.equal(ev.body.pc.activeApp, 'Code.exe')
    assert.equal(ev.body.pc.focusMinutes, 42)

    const saved = JSON.parse(readFileSync(join(home, 'companion', 'pc-heartbeat.json'), 'utf8'))
    assert.equal(saved.activeApp, 'Code.exe')

    const ledger = readLedger(home, 20)
    assert.ok(ledger.some((e) => e.state === 'pc-heartbeat' && e.activeApp === 'Code.exe'))

    const promptText = sis.contexts[0].text()
    assert.match(promptText, /PC:Code\.exe\s+专注42m\s+娱乐3m/)
  })
})

test('/elastic-check records scheduling_failure in escalations DB, deducts earned quota, and enqueues micro_landing_3m in day_plans', async () => {
  await withHome(async (home) => {
    const sis = agentOf('s-sis')
    const strat = agentOf('s-strat')
    const { handler } = host({ 's-sis': sis, 's-strat': strat }, 'standby')
    await call(handler(), 'POST', '/config', { sessionId: 's-sis', strategistSessionId: 's-strat', tzOffsetMin: 480 })

    const now = Date.now()
    // Pre-seed today's earned relax account with 15 min
    const { startOfDay } = await import('../lib/tz.js')
    const today = startOfDay(now, 'Asia/Shanghai')
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'earned.json'), JSON.stringify({ day: today, earned: 15, spent: 0 }), 'utf8')

    const res = await call(handler(), 'POST', '/elastic-check', {
      plannedAt: now - 30 * 60_000,
      startedAt: 0,
      bufferMin: 5,
    })
    assert.equal(res.status, 200)
    assert.equal(res.body.exceeded, true)
    assert.ok(res.body.escalationId)
    assert.equal(res.body.earnedDeducted, 5)
    assert.equal(res.body.fallbackEnqueued, true)

    const earnedAfter = JSON.parse(readFileSync(join(home, 'companion', 'earned.json'), 'utf8'))
    assert.equal(earnedAfter.spent, 5)

    const db = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
    try {
      const escalations = listEscalations(db, { openOnly: false })
      assert.ok(escalations.some((e) => e.reason === 'scheduling_failure' && e.level === 'L1'))
      const { dayString } = await import('../lib/daily.js')
      const plan = getDayPlan(db, dayString(now, 'Asia/Shanghai'))
      assert.ok(plan)
      assert.ok(plan.queue.some((q) => q.protocolId === 'micro_landing_3m'))
    } finally {
      db.close()
    }
  })
})

test('Wall & Bouts six-dice Stage 3 auto-attaches candidate WikiLinks and tracks persistent dice failures', async () => {
  await withHome(async (home) => {
    const vault = join(home, 'notes')
    mkdirSync(vault, { recursive: true })
    writeFileSync(join(vault, '认知失调实验.md'), '# 认知失调实验\n讨论行为与态度不一致时的合理化机制与实验结论。', 'utf8')
    writeFileSync(join(vault, '间隔重复记忆.md'), '# 间隔重复记忆\n关于主动提取比被动重复更有助于长期记忆巩固。', 'utf8')
    writeFileSync(join(vault, '元认知监控.md'), '# 元认知监控\n通过六步提问检测学习中的虚假掌握感。', 'utf8')

    const sis = agentOf('s-sis')
    const strat = agentOf('s-strat')
    const { handler } = host({ 's-sis': sis, 's-strat': strat }, 'standby')
    await call(handler(), 'POST', '/config', { sessionId: 's-sis', strategistSessionId: 's-strat', notesPath: vault })

    const slotsRes = await call(handler(), 'POST', '/bouts', {
      action: 'dice-slots',
      stage: 3,
      query: '认知失调 记忆',
    })
    assert.equal(slotsRes.status, 200)
    assert.equal(slotsRes.body.ok, true)
    const dice5 = slotsRes.body.slots.find((s) => s.dice === 5)
    assert.ok(dice5)
    assert.ok(Array.isArray(dice5.candidateNotes) && dice5.candidateNotes.length >= 1)
    assert.match(dice5.candidateNotes[0].wikilink, /^\[\[.*\]\]$/)

    // Fail once via L1 short answer -> failure count becomes 1
    const failRes = await call(handler(), 'POST', '/wall', {
      action: 'dice-judge',
      dice: 5,
      stage: 3,
      answer: '太短了',
    })
    assert.equal(failRes.status, 200)
    assert.equal(failRes.body.pass, false)
    assert.equal(failRes.body.failures, 1)

    // Pass with concrete linking note -> resets failure counter to 0
    const passRes = await call(handler(), 'POST', '/wall', {
      action: 'dice-judge',
      dice: 5,
      stage: 3,
      answer: '这篇概念直接关联 [[认知失调实验]] 里提到的态度合理化机制，同时和 [[间隔重复记忆]] 的主动提取形成对照。',
      sourceContext: '学习笔记正文中的元认知监控与认知失调对照关系',
    })
    assert.equal(passRes.status, 200)
    assert.equal(passRes.body.pass, true)
    assert.equal(passRes.body.failures, 0)
  })
})

test('Capabilities runtime executor runs all 6 classes (感知/度量/触发/应答/验收/变速)', async () => {
  await withHome(async () => {
    const sis = agentOf('s-sis')
    const strat = agentOf('s-strat')
    const { handler } = host({ 's-sis': sis, 's-strat': strat }, 'standby')
    await call(handler(), 'POST', '/config', { sessionId: 's-sis', strategistSessionId: 's-strat' })

    const sampleByClass = [
      ['前台应用', '感知', { signal: 'AppSwitch' }],
      ['字数', '度量', { text: '专注三分钟' }],
      ['浮层', '触发', {}],
      ['文字', '应答', { prompt: '现在进展如何？' }],
      ['文件增长', '验收', { evidence: 'notes/a.md', text: '已写出完整对照表' }],
      ['缩短步骤', '变速', { mode: 'sprint', minutes: 25 }],
    ]
    for (const [capName, expectedCls, input] of sampleByClass) {
      const r = await call(handler(), 'POST', '/wall', { action: 'capability', name: capName, input })
      assert.equal(r.status, 200)
      assert.equal(r.body.ok, true, `${capName} should succeed`)
      assert.equal(r.body.cls, expectedCls)
    }
    const missing = executeCapability('不存在的能力', {})
    assert.equal(missing.ok, false)
    assert.equal(missing.error, 'unknown-capability')
  })
})

test('Lorebook entries in fish-memory.sqlite are recalled conditionally into chatPersonaText', async () => {
  await withHome(async (home) => {
    const s = openStore({ home })
    remember(s.doc, {
      scope: 'session:s-sis',
      text: '专注红线协议：任何刷短视频借口先按微落地 3 分钟处理。',
      kind: 'rule',
      importance: 2,
      pinned: false,
      triggers: ['专注红线'],
      sceneTags: ['state:standby'],
    }, Date.now())
    s.save()
    s.close()

    const sis = agentOf('s-sis')
    const strat = agentOf('s-strat')
    const { handler } = host({ 's-sis': sis, 's-strat': strat }, 'standby')
    await call(handler(), 'POST', '/config', { sessionId: 's-sis', strategistSessionId: 's-strat' })

    const promptText = sis.contexts[0].text()
    assert.match(promptText, /专注红线协议：任何刷短视频借口先按微落地/)
  })
})
