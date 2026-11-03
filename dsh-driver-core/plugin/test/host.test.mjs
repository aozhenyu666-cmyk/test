// 用假宿主加载插件入口：路由注册、鉴权、出题回合（followup → session/event）、工具禁用。
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { test } from 'node:test'

const home = mkdtempSync(join(tmpdir(), 'dsh-driver-'))
process.env.DSH_HOME = home
mkdirSync(join(home, 'fish-memory'), { recursive: true })
writeFileSync(join(home, 'fish-memory', 'session-fish.json'), JSON.stringify({ version: 2, memories: [{ text: '鱼片做题总是抓到一半就下结论', tags: ['习惯'], importance: 0.8 }] }))

const { apply } = await import('../lib/index.js')

function fakeHost() {
  const on = {}
  let handler
  let allow = true
  const sessionEvents = []
  const agent = {
    id: 'brain', status: 'idle',
    followup(msg) {
      sessionEvents.push(msg)
      // 模拟 agent loop：认领 → 回一张卡 → 回合结束
      queueMicrotask(() => {
        const emit = (type, data) => on['session/event'].forEach((f) => f({ id: 'brain' }, { type, data }))
        emit('turn/start', { turn: 7 })
        emit('user/message', msg)
        emit('assistant/message', { turn: 7, message: { content: [{ type: 'text', text: '{"kind":"one_word","text":"依据是哪个词？","anchor":"第1题"}' }] } })
        emit('turn/end', { turn: 7, reason: { kind: 'completed' } })
      })
    },
  }
  const ctx = {
    get: (k) => (k === 'agents' ? { get: (id) => (id === 'brain' ? agent : undefined), roots: () => [agent] } : k === 'connection' ? { requestRejection: () => (allow ? undefined : 401) } : undefined),
    on: (ev, f) => { (on[ev] ??= []).push(f) },
    effect: () => {},
    inject: (_deps, f) => f({ effect: (g) => g(), webServer: { register: (r) => { handler = r.handler } } }),
  }
  apply(ctx)
  const call = async (method, path, body) => {
    const req = Readable.from(body ? [Buffer.from(JSON.stringify(body))] : [])
    Object.assign(req, { method, url: path, headers: { 'content-type': 'application/json' } })
    const res = { status: 0, body: '', writeHead(s) { this.status = s }, end(b) { this.body = b ?? '' } }
    await handler(req, res)
    return { status: res.status, body: res.body ? JSON.parse(res.body) : null }
  }
  return { call, on, sessionEvents, setAllow: (v) => { allow = v } }
}

test('插件入口：鉴权、配置、开场出题走真实回合事件、驾驶回合禁用工具', async () => {
  const h = fakeHost()
  h.setAllow(false)
  assert.equal((await h.call('GET', '/dsh-driver-core/v1/status')).status, 401)
  h.setAllow(true)

  const cfg = await h.call('POST', '/dsh-driver-core/v1/config', { brainSessionId: 'brain', memorySessionIds: ['fish'] })
  assert.equal(cfg.body.config.brainSessionId, 'brain')
  await h.call('POST', '/dsh-driver-core/v1/profile', { habits: ['抓到一半就下结论'], goals: ['考公'] })

  const st = await h.call('POST', '/dsh-driver-core/v1/session', { topic: '行测', task: '第1题' })
  assert.equal(st.status, 201)
  const card = st.body.directives[0].card
  assert.equal(card.source, 'llm', '开场题来自 agent 回合')
  assert.equal(card.text, '依据是哪个词？')
  const sent = h.sessionEvents[0]
  assert.equal(sent.role, 'user')
  assert.equal(sent.source.kind, 'dsh-driver-core')
  assert.ok(sent.content[0].text.includes('抓到一半就下结论'), 'fish-memory 与 profile 进了隐秘层')

  const sid = st.body.sessionId
  const sig = await h.call('POST', '/dsh-driver-core/v1/signal', { sessionId: sid, kind: 'foreground', pkg: 'tv.danmaku.bili' })
  assert.equal(sig.body.directives[0].type, 'pullback')
  const poll = await h.call('GET', `/dsh-driver-core/v1/directives?sessionId=${sid}&after=0`)
  assert.equal(poll.body.directives.length, 2)

  const rows = readFileSync(join(home, 'storages', 'dsh-driver-core', 'sessions', `${sid}.jsonl`), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  assert.ok(rows.some((r) => r.step === 'INTERRUPTED_BY_SWITCH'))
  await h.call('POST', '/dsh-driver-core/v1/signal', { sessionId: sid, kind: 'stop', reason: 'test' })
})
