// B2 端到端：夜间整理的产物真的进了发球 prompt 和面板，而不是只在一个纯函数里自转。
//
// 这里起的是真的插件（apply(ctx) + 真的 HTTP handler），状态文件是真的落在临时的 DSH_HOME 里。
// 只断三件在真机上能看到的事：
//   1. nightly.json 里活着的那一行，会出现在下一球的 prompt 里（内容层，可裁）。
//   2. /status 报的 nightly.live 和文件一致（面板看得见）。
//   3. 没有 llm 服务时，/plan 照样 200（整理失败不许把发球拖死）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'

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
    writeHead(status) { this.statusCode = status },
    end(chunk) { this.text = chunk ? String(chunk) : '' },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

function makeAgent() {
  const sent = []
  const contexts = []
  return {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    sent,
    contexts,
    followup(msg) { sent.push(msg) },
    // 人格注入挂在会话自己的 systemPrompt.context 上（每轮取一次）。
    ctx: {
      tools: { register: () => () => {} },
      systemPrompt: {
        context(spec) {
          contexts.push(spec)
          return () => {}
        },
      },
    },
  }
}

/** 一个不吵的 home：空记忆库 + 一条配置。视角全开会让别的球型先出去。 */
function seed(home) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  // 注意：不能开 dual:false —— 那会让 chatPersonaText 提前返回单人格文本，
  // 整块注入（vibe / emotion / nightly）都不执行。这是既有行为，B2 不动它。
  writeFileSync(join(cfgDir, 'config.json'), JSON.stringify({
    sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0, proactiveTools: false,
  }))
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.close()
}

/** 手动写一份「已经整理好」的产物，模拟昨晚跑过一轮。 */
function seedNightly(home, now) {
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(join(cfgDir, 'nightly.json'), JSON.stringify({
    v: 1,
    items: [
      { kind: 'links', a: 'topic:t1', b: 'fact:f1', aLabel: '面试结果', bLabel: '他不吃香菜', why: '都是这周的事', madeAt: now - 1000, until: now + 86400000, usedAt: 0, used: 0, day: '' },
      { kind: 'gaps', about: 'topic:t1', aboutLabel: '面试结果', missing: '没说几点出结果', madeAt: now - 1000, until: now + 86400000, usedAt: 0, used: 0, day: '' },
      { kind: 'carry', text: '他说周五出面试结果', evidence: [1], madeAt: now - 1000, until: now + 86400000, usedAt: 0, used: 0, day: '' },
    ],
  }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-nightly-'))
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

test('I1 整理出来的底稿进了每一轮聊天的人设注入（而且要写明不是现在说出去的话）', async () => {
  await withHome(async (home) => {
    seed(home)
    seedNightly(home, Date.now())
    const agent = makeAgent()
    const host = makeHost({ agent })
    // 先问一次 /plan：人格与工具是在这一轮里挂到会话上的。
    await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', {
      v: 1, timeZone: 'Asia/Shanghai', sessionId: 'session-abc',
      phone: { app: 'com.tencent.mm', minutes: 30, screenOn: true, local: '13:10' },
    })
    const persona = agent.contexts.find((c) => c.name === 'dafeiyu-persona')
    assert.ok(persona, '人设注入没挂上')
    const prompt = persona.text()
    assert.match(prompt, /你自己整理的底稿/)
    assert.match(prompt, /不是要你现在说出去的话/)
    // 三类各来一条，说明三个去向的素材都进得去
    assert.match(prompt, /面试结果 ↔ 他不吃香菜/)
    assert.match(prompt, /没说几点出结果/)
    assert.match(prompt, /他说周五出面试结果/)
  })
})

test('I2 /status 报的 nightly.live 和文件一致；过期的算死', async () => {
  await withHome(async (home) => {
    seed(home)
    seedNightly(home, Date.now())
    const agent = makeAgent()
    const host = makeHost({ agent })
    const ok = await call(host.handler, 'GET', '/dsh-go-companion/v1/status')
    assert.equal(ok.status, 200)
    assert.deepEqual(ok.body.nightly.live, { links: 1, gaps: 1, carry: 1 })

    // 全部过期 → 一条都不算活着，注入那一行也就该消失
    const cfgDir = join(home, 'companion')
    writeFileSync(join(cfgDir, 'nightly.json'), JSON.stringify({
      v: 1,
      items: [
        { kind: 'links', a: 'topic:t1', b: 'fact:f1', aLabel: '面试结果', bLabel: '他不吃香菜', why: 'x', madeAt: 1, until: 2, usedAt: 0, used: 0, day: '' },
      ],
    }))
    const dead = await call(host.handler, 'GET', '/dsh-go-companion/v1/status')
    assert.deepEqual(dead.body.nightly.live, { links: 0, gaps: 0, carry: 0 })
  })
})

test('I3 没有 llm 服务时 /plan 照样 200（整理失败不许把发球拖死）', async () => {
  await withHome(async (home) => {
    seed(home)
    const agent = makeAgent()
    const host = makeHost({ agent })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', {
      v: 1, timeZone: 'Asia/Shanghai', sessionId: 'session-abc',
      phone: { app: 'com.tencent.mm', minutes: 30, screenOn: true, local: '13:10' },
    })
    assert.equal(res.status, 200)
    assert.equal(res.body.v, 1)
    assert.ok(res.body.plan !== null)
  })
})
