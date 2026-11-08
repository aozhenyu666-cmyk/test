// P1：回合（rally）端到端 —— 发球、回球、判球都写进账本，拍数从账本重建。
//
// 假宿主和 routes.test.mjs 一样，只是这里给 llm 一个桩：判球能拿到"模型输出"，
// 由此验证"模型判出来的结果是它，写进账本的就是它"。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'

function makeHost({ home, agent, judge }) {
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
        // 判球要模型：桩一个 stream，吐 judge 指定的 JSON。
        ...(judge === undefined ? {} : { llm: { stream: () => judge() } }),
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

function seed(home, config = {}) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(join(cfgDir, 'config.json'), JSON.stringify({ sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0, ...config }))
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.prepare('INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?,?)')
    .run('t1', 'session:session-abc', '面试结果', '她说周五出结果', 'open', 2, Date.now() - 86400000, 0)
  db.close()
}

function ledger(home) {
  return readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l))
}

/** 跑一轮：请求 → 认领 → 模型出文字 → 人的回球。 */
async function oneRound({ handler, listeners, agent, requestId, reply, serve }) {
  const sid = 'session-abc'
  const fire = async (event) => {
    for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, event)
  }
  const req = await call(handler, 'POST', '/dsh-go-companion/v1/request', {
    v: 1,
    requestId,
    eventId: 'evt-' + requestId,
    kind: 'random_chat',
    payload: {},
    deadlineMs: 30000,
  })
  assert.equal(req.body?.state, 'accepted', '请求应当被接受：' + JSON.stringify(req.body))
  const turn = Number(String(requestId).replace(/\D/g, '').slice(-4)) + 10
  await fire({ type: 'turn/start', data: { turn } })
  // 认领：DSH 把插件发的那条消息认领进 turn 时，带的是它的 messageId。
  await fire({ type: 'user/message', data: { id: agent.sent.at(-1).id } })
  await fire({ type: 'assistant/message', data: { turn, message: { content: [{ type: 'text', text: serve }] } } })
  await fire({ type: 'turn/end', data: { turn, reason: { kind: 'completed' } } })
  await fire({ type: 'user/message', data: { content: [{ type: 'text', text: reply }] } })
  return req.body
}

/** 记下插件发出去的消息，测试要拿它的 id 去认领。
 *  config 是判球要用的模型：真实环境里跟当前会话自己的选择走。 */
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

test('一球一个回合：发球第一拍、回球第二拍，判词进账本', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-rally-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home)
    const agent = makeAgent()
    const serve = '我猜你在躲下午那份申论'
    const host = makeHost({
      home,
      agent,
      judge: async function* () {
        yield { type: 'text-delta', text: '{"verdict":"pushback","why":"他给了反例","next":"deeper"}' }
      },
    })
    await oneRound({ ...host, agent, requestId: 'req-00000001', reply: '不对，我是在做行测', serve })

    const entries = ledger(home)
    const generated = entries.find((e) => e.state === 'generated')
    const replied = entries.find((e) => e.state === 'replied')

    assert.ok(generated.rallyId, '发球要带 rallyId')
    assert.equal(generated.shot, 1, '发球是第一拍')
    assert.ok(generated.serveType, '发球要带类型')

    assert.equal(replied.rallyId, generated.rallyId, '回球挂在同一个回合上')
    assert.equal(replied.shot, 2, '回球是第二拍')
    assert.equal(replied.verdict, 'pushback', '模型判出来的判词')
    assert.equal(replied.next, 'deeper')
    assert.equal(replied.judged, true, '这一球真的判过，不是兜底')
    assert.equal(replied.replyClass, 'substantive', 'pushback 落成 substantive')
    assert.equal(replied.answer, '不对，我是在做行测', '账本留下这一球回了什么')
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})

test('判球拿不到模型时退回规则，照样记账', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-rally-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home)
    const agent = makeAgent()
    // 不给 llm：宿主没有模型服务
    const host = makeHost({ home, agent })
    await oneRound({ ...host, agent, requestId: 'req-00000002', reply: '不想聊这个', serve: '面试怎么样' })

    const replied = ledger(home).find((e) => e.state === 'replied')
    assert.equal(replied.judged, false, '这一球是兜底判的')
    assert.equal(replied.verdict, 'refuse')
    assert.equal(replied.next, 'stop')
    assert.equal(replied.replyClass, 'refuse')
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})

test('连着两轮：第二轮回球是第四拍，回合不重置', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-rally-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home)
    const agent = makeAgent()
    const host = makeHost({
      home,
      agent,
      judge: async function* () {
        yield { type: 'text-delta', text: '{"verdict":"engaged","why":"有细节","next":"deeper"}' }
      },
    })
    await oneRound({ ...host, agent, requestId: 'req-00000011', reply: '因为周五才出，我还在等', serve: '我猜你在躲申论' })
    await oneRound({ ...host, agent, requestId: 'req-00000012', reply: '昨天问过了，还没出', serve: '我猜你在躲申论' })

    const shots = ledger(home).filter((e) => e.state === 'generated' || e.state === 'replied')
    const rallyIds = new Set(shots.map((e) => e.rallyId))
    assert.equal(rallyIds.size, 1, '两轮在同一个回合里')
    assert.deepEqual(
      shots.map((e) => e.shot),
      [1, 2, 3, 4],
      '拍数一路往上数',
    )
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})

test('发球 prompt 要求先亮观点，并带上这一球的类型', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-rally-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home, { dual: false })
    const sent = []
    const agent = {
      id: 'session-abc',
      status: 'idle',
      inbox: { hasPending: false },
      followup(msg) {
        sent.push(msg)
      },
    }
    const host = makeHost({ home, agent })
    await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-00000021',
      eventId: 'evt-00000021',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(sent.length, 1)
    const prompt = sent[0].content[0].text
    assert.match(prompt, /先亮出你自己的判断/)
    assert.match(prompt, /他可以直接反驳你/)
    assert.match(prompt, /这一球的类型：/)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})

test('套话记进账本：下一球把这句原样打回去', async () => {
  const home = mkdtempSync(join(tmpdir(), 'companion-rally-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home)
    const agent = makeAgent()
    const host = makeHost({
      home,
      agent,
      judge: async function* () {
        yield { type: 'text-delta', text: '{"verdict":"engaged","why":"有细节","next":"deeper"}' }
      },
    })
    // 第一球是填空：模板原句加个语气词也要认出来。
    await oneRound({ ...host, agent, requestId: 'req-00000031', reply: '嗯', serve: '你在做什么呢？' })
    // 第二球的 prompt 里必须出现上一球的原句，换写法重来。
    await oneRound({ ...host, agent, requestId: 'req-00000032', reply: '在改简历', serve: '我猜你在躲申论' })

    const entries = ledger(home)
    const generated = entries.filter((e) => e.state === 'generated')
    assert.equal(generated[0].stock, true, '模板句要标成套话')
    assert.equal(generated[1].stock, undefined, '不是套话就不写这个字段')

    const prompt = agent.sent.at(-1).content[0].text
    assert.match(prompt, /上一球你说的是/)
    assert.match(prompt, /你在做什么呢/)
    assert.match(prompt, /必须换个写法/)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
})
