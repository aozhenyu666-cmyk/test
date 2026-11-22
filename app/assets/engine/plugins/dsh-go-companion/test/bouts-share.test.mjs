// 第八轮 W3：分享到局（`POST /bouts`）。
//
// 这里钉的是「接不住就别答应」：没有在跑的局时必须明确回 no-bout，手机那边才会把这段分享
// 退回「新对话」。回一个空成功就等于把用户分享进来的东西悄悄吞了——这是这个功能最坏的失败方式。
//
// 真正接住的那一条（库里有局、进程里也接着）走的是 answerBout → official.answer → boutrun，
// 要先有一个 live 的局官，而那只能由面板动作 startBout 开出来，不是这个壳能摆的桩。
// 所以这里只证明两条退出路径，不假装证明过接线。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { openBout, openBoutsDb } from '../lib/bouts.js'

const PREFIX = '/dsh-go-companion/v1'

function makeHost({ agent }) {
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
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
  return { handler }
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

/** 库和配置都摆好，`runningBout` 决定库里有没有一局正在跑。 */
function seed(home, { runningBout = false } = {}) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(
    join(home, 'companion', 'config.json'),
    JSON.stringify({ sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0, dual: false, proactiveTools: false }),
  )
  const db = openBoutsDb(join(memDir, 'fish-memory.sqlite'))
  try {
    if (runningBout) assert.equal(openBout(db, { protocolId: 'execute' }).ok, true)
  } finally {
    db?.close?.()
  }
}

async function withHost({ runningBout = false } = {}, fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-bouts-share-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    seed(home, { runningBout })
    const agent = { id: 'session-abc', status: 'idle', inbox: { hasPending: false }, followup() {} }
    const { handler } = makeHost({ agent })
    assert.equal(typeof handler, 'function')
    return await fn(handler)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

test('分享进局：没有在跑的局，明确回 no-bout（分享要能退回新对话）', async () => {
  await withHost({}, async (handler) => {
    const r = await call(handler, 'POST', `${PREFIX}/bouts`, { v: 1, text: '这段是刚看到的' })
    assert.equal(r.status, 200)
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'no-bout')
  })
})

test('分享进局：库里有局但进程没接住（引擎重启过），也是 no-bout', async () => {
  await withHost({ runningBout: true }, async (handler) => {
    const r = await call(handler, 'POST', `${PREFIX}/bouts`, { v: 1, text: '这段是刚看到的' })
    assert.equal(r.status, 200)
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'no-bout')
  })
})

test('分享进局：空文字回 empty，不碰库', async () => {
  await withHost({ runningBout: true }, async (handler) => {
    const blank = await call(handler, 'POST', `${PREFIX}/bouts`, { v: 1, text: '   ' })
    assert.equal(blank.body.ok, false)
    assert.equal(blank.body.code, 'empty')
    const missing = await call(handler, 'POST', `${PREFIX}/bouts`, { v: 1 })
    assert.equal(missing.body.code, 'empty')
  })
})
