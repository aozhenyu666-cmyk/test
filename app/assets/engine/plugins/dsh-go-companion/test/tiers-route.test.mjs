// 2026-11-19 App 分类表（手机页）：和 App分层.md 同一张表；改严立即生效、改松挂起 24 小时、系统底座不能分层。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'

function host() {
  const agent = { id: 's1', status: 'idle', inbox: { hasPending: false }, config: { provider: 't', model: 't' }, followup() {} }
  let handler
  apply({
    get(n) {
      if (n === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (n === 'connection') return { requestRejection: () => undefined }
      if (n === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (n === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(f) { f() } }) },
    effect(f) { f() },
  })
  return handler
}
function call(h, body) {
  const req = Readable.from([Buffer.from(JSON.stringify(body))])
  req.method = 'POST'; req.url = '/dsh-go-companion/v1/tiers'; req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(h(req, res)).then(() => JSON.parse(res.text))
}
async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'tiers-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 's1', quietStart: 12, quietEnd: 12 }))
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

test('分类表：列出每个 App 在哪一层；改严立即生效', async () => {
  await withHome(async (home) => {
    const h = host()
    const list = await call(h, { action: 'list', apps: [{ pkg: 'tv.danmaku.bili', label: '哔哩哔哩' }, { pkg: 'md.obsidian', label: 'Obsidian' }, { pkg: 'com.bbk.launcher2', label: '桌面' }] })
    const by = Object.fromEntries(list.rows.map((r) => [r.pkg, r.tier]))
    assert.equal(by['md.obsidian'], 'work')
    assert.equal(by['com.bbk.launcher2'], 'base')
    const r = await call(h, { action: 'set', pkg: 'com.tencent.mm', tier: 'fun' })
    assert.equal(r.ok, true)
    assert.equal(r.applied, true)
    const cfg = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.equal(cfg.appTiers['com.tencent.mm'], 'fun')
  })
})

test('分类表：改松挂起 24 小时，可撤回；系统底座不能分层', async () => {
  await withHome(async (home) => {
    const h = host()
    await call(h, { action: 'set', pkg: 'com.tencent.mm', tier: 'game' })
    const r = await call(h, { action: 'set', pkg: 'com.tencent.mm', tier: 'work' })
    assert.equal(r.applied, false)
    assert.equal(r.pendingTier, 'work')
    assert.ok(r.effectiveAt > Date.now() + 23 * 3_600_000)
    const list = await call(h, { action: 'list', apps: [{ pkg: 'com.tencent.mm' }] })
    assert.equal(list.rows[0].tier, 'game', '还没到 24 小时，仍按游戏管')
    assert.equal(list.rows[0].pendingTier, 'work')
    const c = await call(h, { action: 'cancel', pkg: 'com.tencent.mm' })
    assert.equal(c.ok, true)
    const after = await call(h, { action: 'list', apps: [{ pkg: 'com.tencent.mm' }] })
    assert.equal(after.rows[0].pendingTier, null)
    const base = await call(h, { action: 'set', pkg: 'com.bbk.launcher2', tier: 'fun' })
    assert.equal(base.ok, false)
    assert.equal(base.error, 'base')
  })
})

test('分类表：没有笔记库时，挂起满 24 小时的放松也会生效', async () => {
  await withHome(async (home) => {
    const h = host()
    await call(h, { action: 'set', pkg: 'com.tencent.mm', tier: 'game' })
    await call(h, { action: 'set', pkg: 'com.tencent.mm', tier: 'dual' })
    const pf = join(home, 'companion', 'app-tiers-pending.json')
    const p = JSON.parse(readFileSync(pf, 'utf8'))
    p['com.tencent.mm'].since = Date.now() - 25 * 3_600_000
    writeFileSync(pf, JSON.stringify(p))
    const list = await call(h, { action: 'list', apps: [{ pkg: 'com.tencent.mm' }] })
    assert.equal(list.rows[0].tier, 'dual')
    assert.equal(list.rows[0].pendingTier, null)
  })
})

test('出口合一：锁页长按回 DSH 和紧急解除共用每日次数', async () => {
  await withHome(async () => {
    const h = host()
    const ev = (body) => {
      const req = Readable.from([Buffer.from(JSON.stringify(body))])
      req.method = 'POST'; req.url = '/dsh-go-companion/v1/event'; req.headers = { 'content-type': 'application/json' }
      const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
      return Promise.resolve(h(req, res)).then(() => JSON.parse(res.text))
    }
    const a = await ev({ type: 'emergency-exit', minutes: 10 })
    assert.equal(a.counted, true)
    const left1 = a.left
    const b = await ev({ type: 'disarm', reason: '急事要回消息', disarmId: 'd-1' })
    assert.equal(b.ok, true)
    const c = await ev({ type: 'emergency-exit', minutes: 10 })
    assert.equal(c.left, left1 - 2, '长按退出、紧急解除、再长按：同一个次数里扣了 3 次')
  })
})
