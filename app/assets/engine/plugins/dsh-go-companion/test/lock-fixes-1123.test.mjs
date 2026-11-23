// 2026-11-23 他真机上的两条：硬干预用满后自己开的严格局锁不住；验收点锁着时 Claude / Gemini 打不开。
// 另：手机两条执行路（锁结论 / session 名单）说法要一致——严格类 session 的名单至少包含 AI 和学习层。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { minuteOfDay } from '../lib/tz.js'

const TZ = 'Asia/Shanghai'
const BILI = 'tv.danmaku.bili'
const GEMINI = 'com.google.android.apps.bard'
const CLAUDE = 'com.anthropic.claude'
const installed = [{ pkg: BILI, label: '哔哩哔哩' }, { pkg: 'md.obsidian', label: 'Obsidian' }, { pkg: GEMINI, label: 'Gemini' }, { pkg: CLAUDE, label: 'Claude' }]

function boot() {
  let handler
  const ctx = {
    get: (n) => (n === 'agents' ? { get: () => null, roots: () => [], list: () => [] } : n === 'connection' ? { requestRejection: () => undefined }
      : n === 'sessionController' ? { resolveAgent: async () => ({ agent: null }) } : n === 'workspaceRegistry' ? { archivedSessionIds: [] } : undefined),
    on() {}, inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) }, effect(fn) { fn() },
  }
  setStatePin('standby')
  apply(ctx)
  return (method, path, body) => {
    const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
    req.method = method
    req.url = '/dsh-go-companion/v1' + path
    req.headers = { 'content-type': 'application/json' }
    const res = { text: '', writeHead() {}, end(c) { this.text = c ? String(c) : '' } }
    return Promise.resolve(handler(req, res)).then(() => JSON.parse(res.text || 'null'))
  }
}
async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'lockfix-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'sis', timeZone: TZ, quietStart: 12, quietEnd: 12, lockShadow: false, verifyMode: { enabled: false }, wall: { meals: [] } }))
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}
const phone = (app) => ({ app, label: app, fun: app === BILI, minutes: 2, screenOn: true, installedApps: installed, today: [] })
const plan = (c, app) => c('POST', '/plan', { roll: 0, timeZone: TZ, phone: phone(app) })
/** 两条执行路任一条会锁就算锁。 */
const locked = (r, pkg) => {
  const s = r.session
  if (s && !(s.allow || []).includes(pkg)) return true
  const l = r.lock || {}
  if (l.mode === 'allowlist') return !(l.allow || []).includes(pkg)
  return (l.deny || []).some((d) => d.app === pkg)
}
const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: TZ }).format(new Date())
const isNight = () => {
  const m = minuteOfDay(Date.now(), TZ)
  return m >= 30 && m < 7 * 60
}

test('硬干预用满：系统不再主动打扰，但他自己开的严格局照锁（不再降成 HARD_CAP_DOWNGRADE）', async () => {
  await withHome(async (home) => {
    writeFileSync(join(home, 'companion', 'hard-interventions.json'), JSON.stringify({
      v: 1, day: today(), count: 40, items: Array.from({ length: 40 }, (_, i) => ({ kind: 'lock', at: Date.now() - i * 60_000 })),
    }))
    const c = boot()
    await c('POST', '/bouts', { action: 'open', protocolId: 'execute', title: '刷题', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, strict: true, minutes: 30 })
    const r = await plan(c, BILI)
    assert.notEqual(r.lockArbiter?.source, 'HARD_CAP_DOWNGRADE', JSON.stringify(r.lockArbiter))
    assert.ok(locked(r, BILI), '上限用满了，严格局照样锁 B 站')
    if (!isNight()) assert.ok(!locked(r, GEMINI), '严格局里 Gemini 能用')
  })
})

test('验收点到点没过保底：锁，但 Obsidian、Claude、Gemini 两条执行路都放（不然交不了差）', async () => {
  const min = minuteOfDay(Date.now(), TZ)
  if (min < 3) return
  await withHome(async (home) => {
    const c = boot()
    writeFileSync(join(home, 'companion', 'bank.json'), JSON.stringify({ 题库版本: 1, 题目: [{ id: 'q', 题干: 't', 选项: ['a', 'b'], 答案: 'A' }] }))
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({ v: 1, items: [{
      id: 's1', day: today(), title: '局 15 分钟', kind: 'bout_minutes', stretch: 15, floor: 10, dueMinute: min - 2, estMinutes: 45, sealedAt: Date.now() - 3_600_000, source: 'night',
    }] }))
    await c('POST', '/seals', { action: 'list' })
    await plan(c, 'md.obsidian')
    const r = await plan(c, BILI)
    assert.ok(r.session, '验收点锁走 session')
    assert.ok(locked(r, BILI))
    for (const p of ['md.obsidian', CLAUDE, GEMINI]) assert.ok(r.session.allow.includes(p), p + ' 在 session 名单里')
    if (!isNight()) for (const p of ['md.obsidian', CLAUDE, GEMINI]) assert.ok(!locked(r, p), p + ' 要能开')
  })
})
