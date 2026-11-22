import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'

import {
  MAX_WALL_BOSS_HISTORY,
  WALL_BOSS_AUDIT_TRIGGER_TEXT,
  WALL_BOSS_PERSONA_TEXT,
  appendWallBossMessage,
  buildWallBossLedgerText,
  isWallBossSession,
  normalizeWallBossHistory,
  validateWallSessionId,
} from '../lib/wall_boss_session.js'
import { mergeConfig, readConfig } from '../lib/runtime_io.js'
import { apply, setStatePin } from '../lib/index.js'

function mockHttpCall(handler, method, path, body = undefined) {
  const payload = body === undefined ? [] : [Buffer.from(JSON.stringify(body))]
  const req = Readable.from(payload)
  req.method = method
  req.url = '/dsh-go-companion/v1' + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: JSON.parse(res.text || 'null') }))
}

function makeFakeAgent(id) {
  const registeredTools = []
  const promptContexts = []
  const sentMessages = []
  const disposedTools = []
  const disposedContexts = []
  return {
    id,
    status: 'idle',
    config: { provider: 'mock', model: 'mock-1' },
    registeredTools,
    promptContexts,
    sentMessages,
    disposedTools,
    disposedContexts,
    followup(msg) {
      sentMessages.push(msg)
      return Promise.resolve()
    },
    ctx: {
      tools: {
        register(tool) {
          registeredTools.push(tool)
          return () => { disposedTools.push(tool.name) }
        },
      },
      systemPrompt: {
        context(entry) {
          promptContexts.push(entry)
          return () => { disposedContexts.push(entry.name) }
        },
      },
    },
  }
}

test('[Boss-Wall Path B] pure helpers validate session isolation, format ledger & bound history', () => {
  assert.equal(validateWallSessionId('', { sessionId: 's1' }).ok, false)
  assert.equal(validateWallSessionId('s1', { sessionId: 's1', strategistSessionId: 's2' }).error, 'same-as-companion')
  assert.equal(validateWallSessionId('s2', { sessionId: 's1', strategistSessionId: 's2' }).error, 'same-as-strategist')
  assert.deepEqual(validateWallSessionId('  w3  ', { sessionId: 's1', strategistSessionId: 's2' }), { ok: true, sessionId: 'w3' })

  assert.equal(isWallBossSession('w3', { wallSessionId: 'w3' }), true)
  assert.equal(isWallBossSession('s1', { wallSessionId: 'w3' }), false)

  const ledger = buildWallBossLedgerText(
    {
      day: '2026-11-22',
      headline: '7日兑现 40% · 正事 30m / 娱乐 65m',
      keyGaps: ['娱乐反超正事'],
      snapshot: {
        day: '2026-11-22',
        fulfillmentRatePct: 40,
        fulfillmentDone: 2,
        fulfillmentTotal: 5,
        todayWorkMin: 30,
        todayFunMin: 65,
        pendingCommitmentsCount: 1,
        pendingCommitments: [{ title: '晚 10 点前完成资料分析 30 题' }],
      },
    },
    {
      hands: [{ id: 'DAY_WORK_WALL', name: '工作时段默认防分心锁', isWinner: true, remainingMin: 45 }],
      effectiveDecision: { mode: 'allowlist' },
      lastIntercept: { label: '抖音', handName: '工作时段默认防分心锁', reason: '工作时段禁用' },
    },
  )
  assert.match(ledger, /\[老板全局事实账本·实时只读\]/)
  assert.match(ledger, /40%（2\/5）/)
  assert.match(ledger, /工作时段默认防分心锁/)
  assert.match(ledger, /晚 10 点前完成资料分析 30 题/)

  let hist = []
  for (let i = 0; i < MAX_WALL_BOSS_HISTORY + 10; i++) {
    hist = appendWallBossMessage(hist, { id: `m-${i}`, role: i % 2 === 0 ? 'user' : 'assistant', text: `msg ${i}`, at: 1000 + i })
  }
  assert.equal(hist.length, MAX_WALL_BOSS_HISTORY)
  assert.equal(hist[0].id, 'm-10')
  // Duplicate id ignored
  const dup = appendWallBossMessage(hist, { id: 'm-10', role: 'user', text: 'dup' })
  assert.equal(dup.length, MAX_WALL_BOSS_HISTORY)
  assert.equal(normalizeWallBossHistory('bad').length, 0)

  const mergedSame = mergeConfig({ sessionId: 's1', strategistSessionId: 's2' }, { wallSessionId: 's1' })
  assert.equal(mergedSame.wallSessionId, undefined)
  const mergedOk = mergeConfig({ sessionId: 's1', strategistSessionId: 's2' }, { wallSessionId: 'w3' })
  assert.equal(mergedOk.wallSessionId, 'w3')
  const mergedClear = mergeConfig(mergedOk, { wallSessionId: null })
  assert.equal(mergedClear.wallSessionId, undefined)
})

test('[Boss-Wall Path B] mounts isolated 3rd wallSessionId with read-only tools & complete sister isolation', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-wall-boss-b-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = join(dir, 'home')
  mkdirSync(join(dir, 'home', 'companion'), { recursive: true })
  writeFileSync(
    join(dir, 'home', 'companion', 'config.json'),
    JSON.stringify({
      sessionId: 'sis-1',
      strategistSessionId: 'strat-1',
      dual: true,
      quietStart: 12,
      quietEnd: 12,
      timeZone: 'Asia/Shanghai',
    }),
  )

  let handler = null
  const sessionEventListeners = []
  const agents = {
    'sis-1': makeFakeAgent('sis-1'),
    'strat-1': makeFakeAgent('strat-1'),
    'wall-boss-1': makeFakeAgent('wall-boss-1'),
  }
  const cleanups = []
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id], cold: false }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(eventName, fn) {
      if (eventName === 'session/event') sessionEventListeners.push(fn)
    },
    inject(_d, cb) {
      cb({
        webServer: { register(r) { handler = r.handler } },
        effect(fn) {
          const ret = fn()
          if (typeof ret === 'function') cleanups.push(ret)
        },
      })
    },
    effect(fn) {
      const ret = fn()
      if (typeof ret === 'function') cleanups.push(ret)
    },
  }

  try {
    setStatePin('standby')
    apply(ctx)

    // 1. Collision refused
    const badSis = await mockHttpCall(handler, 'POST', '/wall/boss-chat', { action: 'bind', sessionId: 'sis-1' })
    assert.equal(badSis.body.ok, false)
    assert.equal(badSis.body.error, 'same-as-companion')
    const badStrat = await mockHttpCall(handler, 'POST', '/wall/boss-chat', { action: 'bind', sessionId: 'strat-1' })
    assert.equal(badStrat.body.ok, false)
    assert.equal(badStrat.body.error, 'same-as-strategist')

    // 2. Bind 3rd independent wallSessionId
    const bound = await mockHttpCall(handler, 'POST', '/wall/boss-chat', { action: 'bind', sessionId: 'wall-boss-1' })
    assert.equal(bound.body.ok, true)
    assert.equal(bound.body.bound, true)
    assert.equal(bound.body.wallSessionId, 'wall-boss-1')
    assert.equal(readConfig().wallSessionId, 'wall-boss-1')

    // Verify mounted contexts & tools on wall-boss-1 vs sisters
    const wbAgent = agents['wall-boss-1']
    const wbCtxNames = wbAgent.promptContexts.map((c) => c.name)
    assert.deepEqual(wbCtxNames, ['dafeiyu-wall-boss', 'dafeiyu-wall-boss-ledger'])
    assert.equal(wbAgent.promptContexts[0].text(), WALL_BOSS_PERSONA_TEXT)
    assert.match(wbAgent.promptContexts[1].text(), /\[老板全局事实账本·实时只读\]/)

    const wbToolNames = wbAgent.registeredTools.map((t) => t.name)
    assert.deepEqual(wbToolNames, ['wall_boss_brief', 'wall_locks_truth', 'wall_vault_search', 'wall_vault_recent'])
    const briefToolRes = wbAgent.registeredTools[0].execute({})
    assert.equal(briefToolRes.ok, true)
    assert.match(briefToolRes.text, /老板全局账本/)
    const locksToolRes = wbAgent.registeredTools[1].execute({})
    assert.equal(locksToolRes.ok, true)
    assert.match(locksToolRes.text, /\[锁机实时真相·只读\]/)

    // Sisters keep their original personas untouched
    assert.ok(agents['sis-1'].promptContexts.some((c) => c.name === 'dafeiyu-persona'))
    assert.ok(!agents['sis-1'].promptContexts.some((c) => c.name === 'dafeiyu-wall-boss'))
    assert.ok(agents['strat-1'].promptContexts.some((c) => c.name === 'dafeiyu-strategist'))
    assert.ok(!agents['strat-1'].promptContexts.some((c) => c.name === 'dafeiyu-wall-boss'))

    // 3. Trigger audit_now & verify turn lifecycle writes assistant reply
    const auditRes = await mockHttpCall(handler, 'POST', '/wall/boss-chat', { action: 'audit_now' })
    assert.equal(auditRes.body.ok, true)
    assert.equal(auditRes.body.pending, true)
    assert.equal(wbAgent.sentMessages.length, 1)
    assert.equal(wbAgent.sentMessages[0].content[0].text, WALL_BOSS_AUDIT_TRIGGER_TEXT)
    assert.match(wbAgent.sentMessages[0].source.rpcId, /^dsh-go-companion-wallboss-/)

    const msgId = wbAgent.sentMessages[0].id
    for (const fn of sessionEventListeners) {
      await fn({ id: 'wall-boss-1' }, { type: 'turn/start', data: { turn: 1 } })
      await fn({ id: 'wall-boss-1' }, { type: 'user/message', data: { id: msgId, content: [{ type: 'text', text: WALL_BOSS_AUDIT_TRIGGER_TEXT }], source: wbAgent.sentMessages[0].source } })
      await fn({ id: 'wall-boss-1' }, { type: 'assistant/message', data: { turn: 1, message: { content: [{ type: 'text', text: '1. 定性：近7天没有实质交付。\n2. 追问：你在用什么替代硬骨头？\n3. 止损：今天交 20 题截图。' }] } } })
      await fn({ id: 'wall-boss-1' }, { type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } })
    }

    const afterAudit = await mockHttpCall(handler, 'GET', '/wall/boss-chat')
    assert.equal(afterAudit.body.messages.length, 2)
    assert.equal(afterAudit.body.messages[0].role, 'user')
    assert.equal(afterAudit.body.messages[1].role, 'assistant')
    assert.match(afterAudit.body.messages[1].text, /没有实质交付/)

    // 4. Sister isolation test: saying sister trigger phrases inside wall-boss-1 must NOT mute or activate refuse mode!
    for (const fn of sessionEventListeners) {
      await fn({ id: 'wall-boss-1' }, { type: 'user/message', data: { id: 'ext-u-1', content: [{ type: 'text', text: '今天别找我，拒绝模式 2 小时' }], source: { kind: 'user' } } })
    }
    const cfgAfter = readConfig()
    assert.equal(Number(cfgAfter.muteUntil || 0), 0, '主管墙话术绝不触发姐妹静音')
    assert.equal(Number(cfgAfter.refuseUntil || 0), 0, '主管墙话术绝不触发姐妹拒绝模式')

    const ledgerText = readFileSync(join(dir, 'home', 'companion', 'ledger.jsonl'), 'utf8')
    assert.doesNotMatch(ledgerText, /"state":"muted"/)
    assert.doesNotMatch(ledgerText, /"state":"refuse"/)

    // 5. Clear & unbind
    const cleared = await mockHttpCall(handler, 'POST', '/wall/boss-chat', { action: 'clear' })
    assert.equal(cleared.body.messages.length, 0)
    const unbound = await mockHttpCall(handler, 'POST', '/wall/boss-chat', { action: 'unbind' })
    assert.equal(unbound.body.bound, false)
    assert.equal(unbound.body.wallSessionId, null)
  } finally {
    for (const fn of cleanups) {
      try { fn() } catch {}
    }
    setStatePin(null)
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(dir, { recursive: true, force: true })
  }
})
