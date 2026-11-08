import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'

function makeHost({ home, agent }) {
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
      cb({
        webServer: {
          register(route) {
            handler = route.handler
          },
        },
        effect(fn) {
          fn()
        },
      })
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

function seed(home) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.prepare('INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?,?)')
    .run('t1', 'session:session-abc', '面试结果', '她说周五出结果', 'open', 2, Date.now() - 86400000, 0)
  db.close()
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(join(cfgDir, 'config.json'), JSON.stringify({ sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0, dual: false, proactiveTools: false }))
}

test('路由：request → status → 回执账本', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-routes-'))
  const sent = []
  const agent = {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    followup(msg) {
      sent.push(msg)
    },
  }
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home)
    const { handler, listeners } = makeHost({ home, agent })
    assert.equal(typeof handler, 'function')

    const req = await call(handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-00000001',
      eventId: 'evt-00000001',
      kind: 'random_chat',
      payload: { streak: '0' },
      deadlineMs: 30000,
    })
    assert.equal(req.status, 202)
    assert.equal(req.body.state, 'accepted')
    assert.equal(req.body.topicId, 'topic:t1')
    assert.equal(sent.length, 1)
    assert.match(sent[0].content[0].text, /面试结果/)
    assert.match(sent[0].content[0].text, /不要调用任何工具/)

    const status = await call(handler, 'GET', '/dsh-go-companion/v1/status')
    assert.equal(status.status, 200)
    assert.equal(status.body.sessionId, 'session-abc')
    assert.equal(status.body.counters.accepted, 1)
    assert.equal(status.body.memory.exists, true)

    // 那一球还在路上（接下了、没生成完）：不能又说开口，否则手机切 App 时会连发两球。
    const busy = await call(handler, 'POST', '/dsh-go-companion/v1/plan', { roll: 0 })
    assert.equal(busy.body.speak, false)
    assert.equal(busy.body.reason, 'in-flight')

    // Simulate the agent turn and the person's substantive reply.
    const sid = 'session-abc'
    const fire = async (event) => {
      for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, event)
    }
    await fire({ type: 'turn/start', data: { turn: 7 } })
    await fire({ type: 'user/message', data: { id: sent[0].id } })
    await fire({ type: 'assistant/message', data: { turn: 7, message: { content: [{ type: 'text', text: '面试结果出了吗？' }] } } })
    await fire({ type: 'turn/end', data: { turn: 7, reason: { kind: 'completed' } } })
    // 这一球生成完了，不在路上了：/plan 可以照常回答（minGap 为 0、roll 0）。
    const plan = await call(handler, 'POST', '/dsh-go-companion/v1/plan', { roll: 0 })
    assert.equal(plan.status, 200)
    assert.notEqual(plan.body.reason, 'in-flight')
    await fire({ type: 'user/message', data: { id: 'user-reply', content: [{ type: 'text', text: '因为周五才出，我还在等' }] } })
    const after = await call(handler, 'GET', '/dsh-go-companion/v1/result?requestId=req-00000001&waitMs=0')
    assert.equal(after.body.state, 'generated')
    assert.equal(after.body.text, '面试结果出了吗？')

    const final = await call(handler, 'GET', '/dsh-go-companion/v1/status')
    assert.equal(final.body.today.generated, 1)
    assert.equal(final.body.today.replied, 1)
    assert.equal(final.body.today.substantive, 1)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})

test('路由：自己注入的提示不算人的回复', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-routes-'))
  const sent = []
  const agent = {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    followup(msg) {
      sent.push(msg)
    },
  }
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home)
    const { handler, listeners } = makeHost({ home, agent })
    const req = await call(handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-00000003',
      eventId: 'evt-00000003',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(req.body.state, 'accepted')

    const sid = 'session-abc'
    // 判球是 async 的（要等模型，或 6 秒超时后退回规则），所以发事件要等它跑完。
    const fire = async (event) => {
      for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, event)
    }
    await fire({ type: 'turn/start', data: { turn: 9 } })
    await fire({ type: 'user/message', data: { id: sent[0].id } })
    await fire({ type: 'assistant/message', data: { turn: 9, message: { content: [{ type: 'text', text: '面试结果出了吗？' }] } } })
    await fire({ type: 'turn/end', data: { turn: 9, reason: { kind: 'completed' } } })
    const done = await call(handler, 'GET', '/dsh-go-companion/v1/result?requestId=req-00000003&waitMs=0')
    assert.equal(done.body.state, 'generated')

    // 下一轮自己注入的提示里带着“别问错”，会命中 REFUSE。
    await fire({
      type: 'user/message',
      data: {
        content: [{ type: 'text', text: '（这不是主人说的话，是 DSH Go 手机端发起的一次主动交流。背景（只是让你别问错，不要逐条复述）：）' }],
        source: { kind: 'dsh-go-companion' },
      },
    })

    // 别的插件注入的消息同样不是人打的字：speak-up 也标了自己的来源。
    await fire({
      type: 'user/message',
      data: {
        content: [{ type: 'text', text: '我刚看到窗外的云有点奇怪，别问了' }],
        source: { kind: 'speak-up' },
      },
    })

    const untouched = await call(handler, 'GET', '/dsh-go-companion/v1/status')
    assert.equal(untouched.body.today.replied, 0)
    assert.equal(untouched.body.today.substantive, 0)

    // 真正的回复仍然能被认出来。
    await fire({ type: 'user/message', data: { content: [{ type: 'text', text: '因为周五才出，我还在等' }] } })
    const after = await call(handler, 'GET', '/dsh-go-companion/v1/status')
    assert.equal(after.body.today.replied, 1)
    assert.equal(after.body.today.substantive, 1)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})

test('路由：没有会话时明确拒绝', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-routes-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    const agent = { id: 'session-abc', status: 'idle', inbox: { hasPending: false }, followup() {} }
    const { handler } = makeHost({ home, agent })
    const r = await call(handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-00000002',
      eventId: 'evt-00000002',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(r.status, 409)
    assert.equal(r.body.error.code, 'no-session')
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})
