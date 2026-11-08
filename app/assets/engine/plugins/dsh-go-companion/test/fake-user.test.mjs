// P1-5：假用户脚本 —— 敷衍型、套话型、反驳型各打一轮，断言判球和回击方式。
//
// 这里的"模型"是脚本化的桩：它按人设给出真实模型该给的那行 JSON。
// 断言的是接线本身 —— 判词、回击方式、话题分类确实按模型说的落进账本，
// 而且判球看得到自己上一球打出去的原句（看不到就没法判）。
// 真实模型判得准不准，是 P5 拿 promptfoo 做前后对比的事，不是这里能证明的。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'

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

/** 假宿主；`sees` 收下每次判球真正发给模型的东西。 */
function makeHost({ agent, judge, sees }) {
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
        llm: {
          stream(args) {
            sees.push(args)
            return judge()
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

function seed(home) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(join(cfgDir, 'config.json'), JSON.stringify({ sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0 }))
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

/** 一个人设打一轮：发球 → 回球，返回账本里那条 replied。 */
async function playOne({ persona, serve }) {
  const home = mkdtempSync(join(tmpdir(), 'companion-fake-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home)
    const agent = makeAgent()
    const sees = []
    const host = makeHost({
      agent,
      sees,
      judge: async function* () {
        yield { type: 'text-delta', text: JSON.stringify(persona.model) }
      },
    })
    const sid = 'session-abc'
    const fire = async (event) => {
      for (const fn of host.listeners.get('session/event') || []) await fn({ id: sid }, event)
    }
    const req = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-' + persona.name,
      eventId: 'evt-' + persona.name,
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(req.body?.state, 'accepted', persona.name + ' 的请求应当被接受：' + JSON.stringify(req.body))
    await fire({ type: 'turn/start', data: { turn: 1 } })
    await fire({ type: 'user/message', data: { id: agent.sent.at(-1).id } })
    await fire({ type: 'assistant/message', data: { turn: 1, message: { content: [{ type: 'text', text: serve }] } } })
    await fire({ type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } })
    await fire({ type: 'user/message', data: { content: [{ type: 'text', text: persona.reply }] } })

    const replied = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l))
      .find((e) => e.state === 'replied')
    return { replied, sees, serve }
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
}

const PERSONAS = [
  {
    // 一直敷衍：回一个字，什么都不说。
    name: 'brief',
    reply: '嗯',
    model: { verdict: 'brief', why: '只回了一个字', next: 'angle' },
    want: { verdict: 'brief', next: 'angle', replyClass: 'brief' },
  },
  {
    // 一直套话：字够多，所以以前按 ≥16 字就算实质回答，正是要修的那个洞。
    name: 'stock',
    reply: '任何事情都有两面性，我们要辩证地看待这个问题，不能只看到一面',
    model: { verdict: 'stock', why: '正确但没用的话', next: 'callout' },
    want: { verdict: 'stock', next: 'callout', replyClass: 'brief' },
  },
  {
    // 真反驳：给反例，正面顶回来 —— 最好的球，要往深里接。
    name: 'pushback',
    reply: '不对，我十点就睡了，是你自己瞎猜',
    model: { verdict: 'pushback', why: '拿事实顶回来了', next: 'deeper' },
    want: { verdict: 'pushback', next: 'deeper', replyClass: 'substantive' },
  },
]

for (const persona of PERSONAS) {
  test('假用户·' + persona.name + '：判球和回击方式都按模型说的落账', async () => {
    const serve = '我猜你昨晚又熬到两点'
    const { replied, sees } = await playOne({ persona, serve })

    assert.ok(replied, '这一轮要有回球记录')
    assert.equal(replied.verdict, persona.want.verdict)
    assert.equal(replied.next, persona.want.next)
    assert.equal(replied.replyClass, persona.want.replyClass, '话题花园只认四种分类')
    assert.equal(replied.judged, true, '这一球是模型判的，不是兜底')
    assert.equal(replied.why, persona.model.why)

    // 判球必须看得到自己上一球的原句，否则它判的是空气。
    assert.equal(sees.length, 1, '一条回球只判一次')
    const asked = sees[0].messages[0].content[0].text
    assert.match(asked, /你上一球打出去的是：/)
    assert.ok(asked.includes(serve), '判球的输入里要有发球原句')
    assert.ok(asked.includes(persona.reply), '判球的输入里要有这条回球')
    assert.equal(sees[0].temperature, 0, '判球不要随机性')
  })
}
