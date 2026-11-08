// P2 端到端：没接球时换更轻的球、账本记下阶梯级别、说"安静"就真的静音。
//
// 时间全部注入（账本里的 at 自己写），不等真实 5 分钟。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'
import { LIGHTER_TYPES } from '../lib/silence.js'

function makeHost({ agent }) {
  const listeners = new Map()
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(name, fn) {
      if (!listeners.has(name)) listeners.set(name, [])
      listeners.get(name).push(fn)
    },
    inject(_deps, cb) {
      cb({ webServer: { register(route) { handler = route.handler } }, effect(fn) { fn() } })
    },
    effect(fn) {
      fn()
    },
  }
  apply(ctx)
  return { handler, listeners }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0,
    text: '',
    writeHead(status) {
      this.statusCode = status
    },
    end(chunk) {
      this.text = chunk ? String(chunk) : ''
    },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

function seed(home, config = {}) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(
    join(cfgDir, 'config.json'),
    JSON.stringify({ sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0, ...config }),
  )
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.prepare('INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?,?)')
    .run('t1', 'session:session-abc', '面试结果', '她说周五出结果', 'open', 2, Date.now() - 86400000, 0)
  db.close()
}

/** 只写一条"已经发出去但没人回"的账本记录，时间放在过去。 */
function seedSilentServe(home, { requestId, agoMs, serveType }) {
  writeFileSync(
    join(home, 'companion', 'ledger.jsonl'),
    JSON.stringify({
      v: 1,
      requestId,
      sessionId: 'session-abc',
      state: 'generated',
      at: Date.now() - agoMs,
      serveType,
      text: '我猜你在躲申论',
      rallyId: 'rally-old',
      shot: 1,
    }) + '\n',
  )
}

function makeAgent() {
  const sent = []
  return {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    sent,
    followup(msg) {
      sent.push(msg)
    },
  }
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-p2-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
}

test('5 分钟没回：下一球换更轻的，prompt 里认这件事', async () => {
  await withHome(async (home) => {
    seed(home)
    seedSilentServe(home, { requestId: 'old-1', agoMs: 6 * 60_000, serveType: 'provoke' })
    const agent = makeAgent()
    const host = makeHost({ agent })

    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-p2-1',
      eventId: 'evt-p2-1',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))

    const prompt = agent.sent[0].content[0].text
    assert.match(prompt, /没有回音/, '要告诉它上一球没人回')
    assert.match(prompt, /更轻、更具体/, '要换更轻的球')
    assert.doesNotMatch(prompt, /同一句话/, '不该鼓励重发')

    // 类型必须落在轻球里。
    const entry = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l))
      .find((e) => e.requestId === 'req-p2-1' && e.state === 'accepted')
    assert.ok(LIGHTER_TYPES.includes(entry.serveType), '挑出了 ' + entry.serveType)
    assert.equal(entry.silence, 'lighter', '账本要记下这是阶梯第几级')
  })
})

test('连续三次没接（阶梯走完）：停下，不再追着他跑', async () => {
  await withHome(async (home) => {
    seed(home)
    writeFileSync(
      join(home, 'companion', 'ledger.jsonl'),
      [
        { v: 1, requestId: 'old-1', sessionId: 'session-abc', state: 'generated', at: Date.now() - 40 * 60_000, serveType: 'observe' },
        { v: 1, requestId: 'old-2', sessionId: 'session-abc', state: 'generated', at: Date.now() - 35 * 60_000, serveType: 'chat', silence: 'lighter' },
        { v: 1, requestId: 'old-3', sessionId: 'session-abc', state: 'generated', at: Date.now() - 25 * 60_000, serveType: 'chat', silence: 'channel' },
      ]
        .map((e) => JSON.stringify(e))
        .join('\n') + '\n',
    )
    const agent = makeAgent()
    const host = makeHost({ agent })

    const out = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai' })
    assert.equal(out.body.speak, false)
    // 安静时段可能先拦（配置里 quietStart/quietEnd 是 12/12，即不静音）。
    assert.equal(out.body.reason, 'no-take', '安静/额度都不是这次的拦路人：' + JSON.stringify(out.body))
    assert.equal(out.body.silence.stage, 'stop')
  })
})

test('他说“安静一小时”，plan 就不再开口', async () => {
  await withHome(async (home) => {
    seed(home)
    const agent = makeAgent()
    const host = makeHost({ agent })
    const sid = 'session-abc'
    const fire = async (event) => {
      for (const fn of host.listeners.get('session/event') || []) await fn({ id: sid }, event)
    }
    await fire({ type: 'user/message', data: { content: [{ type: 'text', text: '安静一小时' }] } })

    const cfg = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.ok(Number(cfg.muteUntil) > Date.now(), '要写进配置')

    const out = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai' })
    assert.equal(out.body.speak, false)
    assert.equal(out.body.reason, 'muted')
    assert.equal(out.body.nextCheckAt, Number(cfg.muteUntil))

    const muted = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l))
      .find((e) => e.state === 'muted')
    assert.ok(muted, '账本要记下他喊过安静')
    assert.equal(muted.answer, '安静一小时')
  })
})
