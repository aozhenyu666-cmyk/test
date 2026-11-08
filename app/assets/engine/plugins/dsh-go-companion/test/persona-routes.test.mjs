// P3 端到端：人格卡进发球 prompt，说一句就切模式，切换记账、下一球换偏好。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'
import { modeServeOrder } from '../lib/persona.js'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-p3-'))
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

test('人格卡和模式进了发球 prompt', async () => {
  await withHome(async (home) => {
    // 单人格（dual 关）时人格卡进发球；双人格时妹妹用自己的人设，见 dual.test.mjs。
    seed(home, { persona: { voice: '直说不哄', catchphrase: '说人话', avoid: '家里的事' }, mode: 'coach', dual: false })
    const agent = makeAgent()
    const host = makeHost({ agent })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-p3-1',
      eventId: 'evt-p3-1',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))
    const prompt = agent.sent[0].content[0].text
    assert.match(prompt, /你的口吻：直说不哄/)
    assert.match(prompt, /口头禅：说人话/)
    assert.match(prompt, /这些话题不碰：家里的事/)
    assert.match(prompt, /教练模式/)
  })
})

test('说“进入教练模式”就切过去，账本记一笔，下一球按教练偏好挑', async () => {
  await withHome(async (home) => {
    seed(home)
    const agent = makeAgent()
    const host = makeHost({ agent })
    const fire = async (event) => {
      for (const fn of host.listeners.get('session/event') || []) await fn({ id: 'session-abc' }, event)
    }
    await fire({ type: 'user/message', data: { content: [{ type: 'text', text: '进入教练模式 2 小时' }] } })

    const cfg = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.equal(cfg.mode, 'coach')
    assert.ok(Number(cfg.modeUntil) > Date.now(), '2 小时后自己回到伙伴模式')

    const switched = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l))
      .find((e) => e.state === 'mode')
    assert.equal(switched.mode, 'coach')

    // 下一球落在教练的偏好里。
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-p3-2',
      eventId: 'evt-p3-2',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))
    const accepted = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l))
      .find((e) => e.requestId === 'req-p3-2' && e.state === 'accepted')
    // 这次请求没带手机现状：观察球没东西可观察，从教练偏好里去掉它再取前三。
    const coachNoPhone = modeServeOrder('coach').filter((t) => t !== 'observe').slice(0, 3)
    // 教练模式也可能直接出一道思维题。
    assert.ok([...coachNoPhone, 'drill'].includes(accepted.serveType), '教练模式挑出了 ' + accepted.serveType)
  })
})
