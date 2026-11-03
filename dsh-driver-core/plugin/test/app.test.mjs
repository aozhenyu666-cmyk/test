// 和「思维驾驶员」APP 配对：配对码、令牌、请求门、出题、报告、"驾驶员就用这个会话"。
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { test } from 'node:test'
import { appRejection, newCode, Pairing } from '../lib/pairing.js'

test('配对码：只用一次、会过期、猜错 5 次锁 1 分钟、令牌只存哈希', () => {
  let clock = 0
  const dir = mkdtempSync(join(tmpdir(), 'pair-'))
  const file = join(dir, 'pairing.json')
  const p = new Pairing({ file, now: () => clock })
  assert.match(newCode(), /^[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$/)

  const shown = p.issueCode(3080)
  assert.match(shown, /^3080:[A-Z0-9]{4}-[A-Z0-9]{4}$/)
  const code = shown.split(':')[1]
  for (let i = 0; i < 5; i++) assert.equal(p.redeem('WRONG-CODE').status, 403)
  assert.equal(p.redeem(code).status, 429, '锁住时连对的也不收')
  clock += 61_000
  const ok = p.redeem(code.toLowerCase().replace('-', ' '), 'Pixel')
  assert.ok(ok.token, '大小写和分隔符不敏感')
  assert.equal(p.redeem(code).status, 403, '只能用一次')
  assert.equal(p.check(ok.token), true)
  assert.equal(p.check('x'.repeat(64)), false)
  assert.ok(!readFileSync(file, 'utf8').includes(ok.token), '不存令牌原文')

  const c2 = p.issueCode(3080).split(':')[1]
  clock += 11 * 60_000
  assert.equal(p.redeem(c2).error, 'code-expired')
  p.revokeAll()
  assert.equal(p.check(ok.token), false)
})

test('请求门：浏览器（带 Origin）和外部 Host 一律拒绝；没令牌 401', () => {
  const p = { check: (t) => t === 'a'.repeat(64) }
  const req = (headers) => ({ headers: { host: '127.0.0.1:3080', ...headers } })
  assert.equal(appRejection(req({ origin: 'https://evil.example' }), p), 403)
  assert.equal(appRejection(req({ host: 'evil.example:3080' }), p), 403)
  assert.equal(appRejection(req({}), p), 401)
  assert.equal(appRejection(req({ authorization: 'Bearer nope' }), p), 401)
  assert.equal(appRejection(req({ authorization: `Bearer ${'a'.repeat(64)}` }), p), undefined)
  assert.equal(appRejection(req({}), p, { needToken: false }), undefined, '配对本身不要令牌')
})

const home = mkdtempSync(join(tmpdir(), 'dsh-app-'))
process.env.DSH_HOME = home
mkdirSync(join(home, 'fish-memory'), { recursive: true })
writeFileSync(join(home, 'fish-memory', 'session-main.json'), JSON.stringify({
  version: 2, memories: [{ text: '鱼片做行测图形推理时总是只看一个规律就选', tags: ['习惯'], importance: 0.8 }],
}))
const { apply } = await import('../lib/index.js')

function fakeHost() {
  const on = {}
  let handler
  const prompts = []
  const emit = (sid, type, data) => on['session/event'].forEach((f) => f({ id: sid }, { type, data }))
  const agent = {
    id: 'brain', status: 'idle',
    followup(msg) {
      prompts.push(msg.content[0].text)
      queueMicrotask(() => {
        emit('brain', 'turn/start', { turn: 3 })
        emit('brain', 'user/message', msg)
        emit('brain', 'assistant/message', { turn: 3, message: { content: [{ type: 'text', text: '{"kind":"ab","text":"又只看了一个规律。黑点数量更像？","options":{"A":"每格递增","B":"没规律"},"anchor":"第3题·黑点数量"}' }] } })
        emit('brain', 'turn/end', { turn: 3, reason: { kind: 'completed' } })
      })
    },
  }
  const ctx = {
    // 引擎 cookie 门对 APP 一律说"没登录"：/app/* 不能依赖它
    get: (k) => (k === 'agents' ? { get: (id) => (id === 'brain' ? agent : undefined), roots: () => [agent] } : k === 'connection' ? { requestRejection: () => 401 } : undefined),
    on: (ev, f) => { (on[ev] ??= []).push(f) },
    effect: () => {},
    inject: (_d, f) => f({ effect: (g) => g(), webServer: { register: (r) => { handler = r.handler } } }),
  }
  apply(ctx)
  const call = async (method, path, body, headers = {}) => {
    const req = Readable.from(body ? [Buffer.from(JSON.stringify(body))] : [])
    Object.assign(req, { method, url: path, headers: { host: '127.0.0.1:3080', 'content-type': 'application/json', ...headers } })
    const res = { status: 0, body: '', writeHead(s) { this.status = s }, end(b) { this.body = b ?? '' } }
    await handler(req, res)
    return { status: res.status, body: res.body ? JSON.parse(res.body) : null }
  }
  return { call, emit, prompts }
}

test('APP 全流程：配对 → ping → 认领出题会话 → 出题带上 fish-memory 和死穴 → 报告落盘', async () => {
  const h = fakeHost()
  const P = '/dsh-driver-core/v1'
  assert.equal((await h.call('GET', `${P}/status`)).status, 401, '原来的路由仍然要引擎 cookie')
  assert.equal((await h.call('GET', `${P}/app/ping`)).status, 401)

  const code = new Pairing().issueCode(3080).split(':')[1]
  assert.equal((await h.call('POST', `${P}/app/pair`, { code }, { origin: 'https://evil.example' })).status, 403)
  const paired = await h.call('POST', `${P}/app/pair`, { code, device: 'test' })
  assert.equal(paired.status, 200)
  const auth = { authorization: `Bearer ${paired.body.token}` }

  let ping = await h.call('GET', `${P}/app/ping`, null, auth)
  assert.equal(ping.body.brain, 'none')
  assert.equal(ping.body.memories, 1, '没指定记忆来源时读本机全部 fish-memory')
  let r = await h.call('POST', `${P}/app/compose`, { intent: 'probe' }, auth)
  assert.equal(r.body.card, null)
  assert.equal(r.body.why, 'no-brain-session')

  // 在 brain 会话里说一句认领
  h.emit('brain', 'user/message', { id: 'u1', role: 'user', content: [{ type: 'text', text: '好的，驾驶员就用这个会话吧' }] })
  ping = await h.call('GET', `${P}/app/ping`, null, auth)
  assert.equal(ping.body.brain, 'configured')

  r = await h.call('POST', `${P}/app/compose`, {
    intent: 'dice', cog: { state: 'THROTTLED_EMPTY', reason: 'filler' }, die: '预判',
    topic: '行测图形推理', task: '第3题', minute: 6, minutes: 15, breakpoint: '第3题·黑点数量',
    recentReplies: ['嗯'], asked: ['对称之外，黑点数量怎么变？'], habits: ['抓到一半就下结论'], goals: ['12 月省考'],
    patterns: ['切走最常发生在第 7 分钟前后'], unresolved: ['第7题：没接上'],
  }, auth)
  assert.equal(r.status, 200)
  assert.equal(r.body.card.kind, 'ab')
  assert.deepEqual(r.body.card.options, { A: '每格递增', B: '没规律' })
  const prompt = h.prompts.at(-1)
  for (const s of ['只看一个规律就选', '抓到一半就下结论', 'THROTTLED_EMPTY', '预判', '第3题·黑点数量', '对称之外', '第7题：没接上']) assert.ok(prompt.includes(s), s)

  r = await h.call('POST', `${P}/app/report`, { topic: '行测', task: '第3题', reason: 'completed', conclusion: '选D', summary: { grounded: 3, responded: 5 }, unresolved: ['第7题'] }, auth)
  assert.equal(r.status, 200)
  const rows = readFileSync(join(home, 'storages', 'dsh-driver-core', 'reports.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  assert.equal(rows.at(-1).summary.grounded, 3)
  assert.equal(rows.at(-1).conclusion, '选D')
})
