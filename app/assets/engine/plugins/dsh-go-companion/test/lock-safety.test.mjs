
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'
import { cleanPhone } from '../lib/phone.js'
import { isNeverLockPkg } from '../lib/never_lock.js'

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


// 2026-11-18 锁机事故：插件把「当前前台」直接当锁目标，前台是桌面 / DSH 自己时就锁了桌面 / 自己。

test('永不锁：桌面、DSH 自己、系统界面、输入法、NekoBox、急事 App 一律不锁；娱乐 App 照锁', () => {
  for (const p of ['com.bbk.launcher2', 'io.github.ccch1mneyyy.dsh_app.fish', 'io.github.ccch1mneyyy.dsh_app',
    'com.android.systemui', 'com.vivo.upslide', 'com.iqoo.secure', 'moe.nb4a', 'com.sohu.inputmethod.sogou',
    'com.android.dialer', 'com.eg.android.AlipayGphone', 'some.vendor.launcher', '']) {
    assert.equal(isNeverLockPkg(p), true, p)
  }
  for (const p of ['com.tencent.tmgp.sgame', 'tv.danmaku.bili', 'com.ss.android.ugc.aweme']) {
    assert.equal(isNeverLockPkg(p), false, p)
  }
})

const front = (app, label) => cleanPhone({ app, label, minutes: 70, fun: true, screenOn: true, local: '21:10',
  today: [{ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes: 130, fun: true }] })

for (const [app, label] of [['com.bbk.launcher2', '桌面'], ['io.github.ccch1mneyyy.dsh_app.fish', 'DSH'], ['com.android.systemui', '系统界面']]) {
  test('/plan：前台是' + label + '时，即使力度到了锁，也不给锁目标', async () => {
    await withHome(async (home) => {
      seed(home, { mode: 'coach' })
      const host = makeHost({ agent: makeAgent() })
      const out = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai', phone: front(app, label) })
      assert.equal(out.status, 200)
      assert.ok(out.body.force.level >= 3, '这条用例必须真的到了锁的力度：' + JSON.stringify(out.body.force))
      assert.equal(out.body.force.app, undefined, JSON.stringify(out.body.force))
    })
  })
}

test('/plan：测试模式默认开着；live_locks 之后关，手动 /config 可以再开', async () => {
  await withHome(async (home) => {
    seed(home, { mode: 'coach' })
    const host = makeHost({ agent: makeAgent() })
    const a = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai', phone: front('com.tencent.tmgp.sgame', '王者荣耀') })
    assert.equal(a.body.shadow, true)
    assert.equal(a.body.force.app, 'com.tencent.tmgp.sgame', '娱乐 App 照常给锁目标（测试模式只是手机不真锁）')
    await call(host.handler, 'POST', '/dsh-go-companion/v1/config', { lockShadow: false })
    const b = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai', phone: front('com.tencent.tmgp.sgame', '王者荣耀') })
    assert.equal(b.body.shadow, false)
  })
})
