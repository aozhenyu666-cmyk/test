// Gemini 桥：真起一个 HTTP 服务，按 Gemini 会走的顺序走一遍。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { createBridge } from '../server.mjs'
import { createStore } from '../store.mjs'
import { GOOGLE_REDIRECT, LOCKOUT_FAILS } from '../oauth.mjs'

const GOOGLE_SIX = [
  'https://oauth-redirect.googleusercontent.com/r/abc123',
  'https://oauth-redirect.googleusercontent.com/a/abc123',
  'https://oauth-redirect-sandbox.googleusercontent.com/r/abc123',
  'https://oauth-redirect-sandbox.googleusercontent.com/a/abc123',
  'https://oauth-redirect-test.googleusercontent.com/r/abc123',
  'https://oauth-redirect-test.googleusercontent.com/a/abc123',
]

async function withBridge(fn, { vault = false, resultWaitMs = 1500 } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-bridge-'))
  let vaultDir = ''
  if (vault) {
    vaultDir = join(dir, 'vault')
    mkdirSync(join(vaultDir, '学习'), { recursive: true })
    mkdirSync(join(vaultDir, '日记'), { recursive: true })
    writeFileSync(join(vaultDir, '学习', '行测.md'), '# 行测\n资料分析：增长率 = 现期/基期 - 1。今天做了 30 题。')
    writeFileSync(join(vaultDir, '日记', '私事.md'), '这一篇不该被读到')
  }
  const store = createStore(join(dir, 'state.json'))
  const bridge = createBridge({ store, vaultDir, resultWaitMs })
  const server = createServer(bridge.handler)
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  const base = 'http://127.0.0.1:' + server.address().port
  const http = (path, init = {}) => fetch(base + path, { redirect: 'manual', ...init })
  try {
    await fn({ base, http, store })
  } finally {
    server.close()
    rmSync(dir, { recursive: true, force: true })
  }
}

const form = (o) => ({ method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(o).toString() })
const json = (o, headers = {}) => ({ method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(o) })
const pkce = () => {
  const verifier = 'v'.repeat(20) + Math.random().toString(36).slice(2) + 'x'.repeat(30)
  return { verifier, challenge: createHash('sha256').update(verifier).digest('base64url') }
}

/** 注册 + 授权 + 换令牌，返回 access / refresh。 */
async function connect(http, store, { redirect = GOOGLE_SIX[0] } = {}) {
  const reg = await (await http('/register', json({ redirect_uris: GOOGLE_SIX, client_name: 'Gemini', token_endpoint_auth_method: 'none' }))).json()
  const { verifier, challenge } = pkce()
  const q = { response_type: 'code', client_id: reg.client_id, redirect_uri: redirect, code_challenge: challenge, code_challenge_method: 'S256', state: 'st-1' }
  const ok = await http('/authorize', form({ ...q, passphrase: store.state.secret.passphrase }))
  assert.equal(ok.status, 302)
  const loc = new URL(ok.headers.get('location'))
  assert.equal(loc.origin + loc.pathname, redirect)
  assert.equal(loc.searchParams.get('state'), 'st-1')
  const code = loc.searchParams.get('code')
  const tok = await (await http('/token', form({ grant_type: 'authorization_code', code, redirect_uri: redirect, client_id: reg.client_id, code_verifier: verifier }))).json()
  assert.ok(tok.access_token, JSON.stringify(tok))
  return { ...tok, clientId: reg.client_id, code, verifier, redirect }
}

const rpc = (http, token, body) => http('/mcp', json(body, { authorization: 'Bearer ' + token, accept: 'application/json, text/event-stream' }))

test('没令牌：/mcp 回 401，并指向受保护资源元数据；几种 well-known 写法都答', async () => {
  await withBridge(async ({ http }) => {
    const r = await rpc(http, 'nope', { jsonrpc: '2.0', id: 1, method: 'initialize', params: {} })
    assert.equal(r.status, 401)
    assert.match(r.headers.get('www-authenticate'), /resource_metadata=".*\/\.well-known\/oauth-protected-resource"/)
    const prm = await (await http('/.well-known/oauth-protected-resource')).json()
    assert.match(prm.resource, /\/mcp$/)
    for (const p of ['/.well-known/oauth-authorization-server', '/.well-known/openid-configuration', '/.well-known/oauth-protected-resource/mcp']) {
      assert.equal((await http(p)).status, 200, p)
    }
    const asm = await (await http('/.well-known/oauth-authorization-server')).json()
    assert.deepEqual(asm.code_challenge_methods_supported, ['S256'])
    assert.ok(asm.registration_endpoint.endsWith('/register'))
    assert.equal((await http('/mcp')).status, 405)
  })
})

test('动态注册：Google 的 6 个回调全收；别的域名、超过 10 个一律 400', async () => {
  await withBridge(async ({ http }) => {
    const ok = await http('/register', json({ redirect_uris: GOOGLE_SIX, client_name: 'Gemini' }))
    assert.equal(ok.status, 201)
    const body = await ok.json()
    assert.equal(body.redirect_uris.length, 6)
    assert.equal(body.token_endpoint_auth_method, 'none')
    assert.equal((await http('/register', json({ redirect_uris: [...GOOGLE_SIX, 'https://evil.example.com/cb'] }))).status, 400)
    assert.equal((await http('/register', json({ redirect_uris: Array(11).fill(GOOGLE_SIX[0]) }))).status, 400)
    assert.ok(!GOOGLE_REDIRECT.test('https://oauth-redirect.googleusercontent.com.evil.com/r/x'))
    const sec = await (await http('/register', json({ redirect_uris: GOOGLE_SIX, token_endpoint_auth_method: 'client_secret_post' }))).json()
    assert.ok(sec.client_secret)
  })
})

test('授权：口令错拒、错 5 次锁；对了发码；码只能用一次；PKCE 对不上拒；refresh 轮换', async () => {
  await withBridge(async ({ http, store }) => {
    const reg = await (await http('/register', json({ redirect_uris: GOOGLE_SIX }))).json()
    const { verifier, challenge } = pkce()
    const q = { response_type: 'code', client_id: reg.client_id, redirect_uri: GOOGLE_SIX[1], code_challenge: challenge, code_challenge_method: 'S256', state: 's' }
    const page = await http('/authorize?' + new URLSearchParams(q))
    assert.equal(page.status, 200)
    assert.match(await page.text(), /不能解锁、不能加额度/)
    assert.equal((await http('/authorize', form({ ...q, passphrase: 'wrong' }))).status, 401)
    // 没有 PKCE：带错误回调
    const noPkce = await http('/authorize?' + new URLSearchParams({ ...q, code_challenge: '' }))
    assert.equal(noPkce.status, 302)
    assert.match(noPkce.headers.get('location'), /error=invalid_request/)
    const good = await http('/authorize', form({ ...q, passphrase: store.state.secret.passphrase.toUpperCase() }))
    assert.equal(good.status, 302, '口令不分大小写')
    const code = new URL(good.headers.get('location')).searchParams.get('code')
    const badPkce = await http('/token', form({ grant_type: 'authorization_code', code, client_id: reg.client_id, code_verifier: 'x'.repeat(50) }))
    assert.equal(badPkce.status, 400)
    // 那个码已经被消费（PKCE 失败也作废）
    const again = await http('/token', form({ grant_type: 'authorization_code', code, client_id: reg.client_id, code_verifier: verifier }))
    assert.equal(again.status, 400)
    const t = await connect(http, store)
    const r1 = await (await http('/token', form({ grant_type: 'refresh_token', refresh_token: t.refresh_token, client_id: t.clientId }))).json()
    assert.ok(r1.access_token && r1.refresh_token !== t.refresh_token)
    assert.equal((await http('/token', form({ grant_type: 'refresh_token', refresh_token: t.refresh_token, client_id: t.clientId }))).status, 400, '旧 refresh 作废')
    for (let i = 0; i < LOCKOUT_FAILS; i++) await http('/authorize', form({ ...q, passphrase: 'nope' + i }))
    assert.equal((await http('/authorize', form({ ...q, passphrase: store.state.secret.passphrase }))).status, 429, '错太多次锁住')
  })
})

test('MCP：initialize / tools/list / 通知回 202；没同步过时 today 说清楚', async () => {
  await withBridge(async ({ http, store }) => {
    const { access_token: tk } = await connect(http, store)
    const init = await (await rpc(http, tk, { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'gemini' } } })).json()
    assert.equal(init.result.protocolVersion, '2025-06-18')
    assert.match(init.result.instructions, /不迎合/)
    assert.equal((await rpc(http, tk, { jsonrpc: '2.0', method: 'notifications/initialized' })).status, 202)
    const list = await (await rpc(http, tk, { jsonrpc: '2.0', id: 2, method: 'tools/list' })).json()
    const names = list.result.tools.map((t) => t.name)
    assert.deepEqual(names, ['dafeiyu_today', 'dafeiyu_rules', 'dafeiyu_health', 'dafeiyu_week', 'dafeiyu_submit_week_review', 'dafeiyu_start_work', 'dafeiyu_spend_balance', 'dafeiyu_add_checkpoints', 'dafeiyu_submit_receipt', 'dafeiyu_review_due', 'dafeiyu_push_cards', 'dafeiyu_import_questions', 'dafeiyu_propose_rule'])
    assert.ok(!names.some((n) => /unlock|quota|waive|lower|delete/.test(n)), '没有放松类工具')
    assert.equal(list.result.tools[0].annotations.readOnlyHint, true)
    const today = await (await rpc(http, tk, { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'dafeiyu_today', arguments: {} } })).json()
    assert.match(today.result.content[0].text, /还没跟桥同步过/)
    const unknown = await (await rpc(http, tk, { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'dafeiyu_unlock', arguments: {} } })).json()
    assert.equal(unknown.error.code, -32602)
    const batch = await (await rpc(http, tk, [{ jsonrpc: '2.0', id: 5, method: 'ping' }, { jsonrpc: '2.0', method: 'notifications/cancelled' }])).json()
    assert.equal(batch.length, 1)
  })
})

const SNAP = {
  v: 1, at: Date.now(), day: '2026-11-21', hm: '09:40', cold: true, coldCutoff: '10:00',
  timeline: [{ id: 'seal-a', title: '资料分析 30 题', kind: 'quiz', kindLabel: 'DSH 题库', unit: '题', start: '14:00', due: '15:30', stretch: 30, floor: 15, actual: 4, status: 'open', source: 'morning', sameDay: false }],
  tomorrow: [], locking: null, bout: null, balance: { funLeft: 35 },
  covenant: { iron: [{ id: '1.2', title: '封印只能加码' }], rules: [{ id: '1.2', title: '封印只能加码', do: '不接', hardness: '铁律', who: '都' }] },
}

test('手机同步：密钥不对 401；上行快照后 today 能看到时间轴；写工具排队、手机回结果、工具拿到结果', async () => {
  await withBridge(async ({ http, store }) => {
    const { access_token: tk } = await connect(http, store)
    assert.equal((await http('/bridge/sync', json({ snapshot: SNAP }, { authorization: 'Bearer wrong' }))).status, 401)
    const key = store.state.secret.phoneKey
    const sync = (body) => http('/bridge/sync', json(body, { authorization: 'Bearer ' + key })).then((r) => r.json())
    assert.deepEqual((await sync({ snapshot: SNAP })).commands, [])
    const today = await (await rpc(http, tk, { jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'dafeiyu_today', arguments: {} } })).json()
    assert.match(today.result.content[0].text, /\[seal-a\] 14:00–15:30 「资料分析 30 题」DSH 题库 4\/30题/)
    assert.match(today.result.content[0].text, /公约铁律：1\.2 封印只能加码/)
    assert.equal(today.result.structuredContent.snapshot.timeline.length, 1)
    // 「过」却不到 2 问 2 答：工具直接拒
    const thin = await (await rpc(http, tk, { jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'dafeiyu_submit_receipt', arguments: { topic: 't', did: 'd', next: 'n', verdict: 'pass', qa: [{ q: '1', a: '1' }] } } })).json()
    assert.equal(thin.result.isError, true)
    // 正常回执：工具在等，手机这时同步、取走、回结果
    const call = rpc(http, tk, { jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'dafeiyu_submit_receipt', arguments: {
      checkpoint_id: 'seal-a', topic: '资料分析抽查', did: '讲清了增长率', next: '14:00 开局', verdict: 'pass',
      qa: [{ q: '增长率怎么算', a: '现期除以基期减一' }, { q: '基期怎么求', a: '现期除以一加增长率' }] } } })
    await new Promise((r) => setTimeout(r, 120))
    const pulled = await sync({ snapshot: SNAP })
    assert.equal(pulled.commands.length, 1)
    assert.equal(pulled.commands[0].type, 'receipt')
    assert.equal(pulled.commands[0].payload.checkpointId, 'seal-a')
    await sync({ results: [{ id: pulled.commands[0].id, ok: true, text: '回执收到：「资料分析抽查」过，记到验收点 seal-a。' }] })
    const done = await (await call).json()
    assert.match(done.result.content[0].text, /回执收到/)
    assert.deepEqual((await sync({})).commands, [], '确认过的命令不再下发')
    // 手机没回：等满了告诉 Gemini 已排队
    const queued = await (await rpc(http, tk, { jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'dafeiyu_add_checkpoints', arguments: {
      target: 'today', items: [{ title: '申论 1 篇', kind: 'file', path: '学习/申论.md', stretch: 800, due: '20:00' }] } } })).json()
    assert.match(queued.result.content[0].text, /已经交给手机排队/)
    const later = await sync({})
    assert.equal(later.commands[0].type, 'add_checkpoints')
    await sync({ results: [{ id: later.commands[0].id, ok: false, text: '一条都没加上：「申论 1 篇」截止时间已经过了。' }] })
  }, { resultWaitMs: 800 })
})

test('笔记库只读：能搜能读；日记 / 越界不读', async () => {
  await withBridge(async ({ http, store }) => {
    const { access_token: tk } = await connect(http, store)
    const call = async (name, args) => (await (await rpc(http, tk, { jsonrpc: '2.0', id: 9, method: 'tools/call', params: { name, arguments: args } })).json()).result
    const list = await (await rpc(http, tk, { jsonrpc: '2.0', id: 1, method: 'tools/list' })).json()
    assert.equal(list.result.tools.length, 16)
    assert.match((await call('dafeiyu_search_notes', { query: '增长率' })).content[0].text, /学习\/行测\.md/)
    assert.doesNotMatch((await call('dafeiyu_search_notes', { query: '不该被读到' })).content[0].text, /私事/)
    assert.match((await call('dafeiyu_read_note', { path: '学习/行测.md' })).content[0].text, /现期\/基期/)
    assert.equal((await call('dafeiyu_read_note', { path: '日记/私事.md' })).isError, true)
    assert.equal((await call('dafeiyu_read_note', { path: '../state.json' })).isError, true)
    assert.equal((await call('dafeiyu_read_note', { path: '/etc/passwd' })).isError, true)
    assert.match((await call('dafeiyu_recent_notes', { days: 3 })).content[0].text, /学习\/行测\.md/)
  }, { vault: true })
})

test('本机互通接口（8788）：只认本机来源 + 本机密钥；手机密钥不行；公网端口上没有这些路径', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-local-'))
  const store = createStore(join(dir, 'state.json'))
  assert.match(store.state.secret.localKey, /^dfyl_/)
  assert.notEqual(store.state.secret.localKey, store.state.secret.phoneKey)
  const bridge = createBridge({ store, resultWaitMs: 600 })
  const pub = createServer(bridge.handler)
  const loc = createServer(bridge.localHandler)
  await new Promise((r) => pub.listen(0, '127.0.0.1', r))
  await new Promise((r) => loc.listen(0, '127.0.0.1', r))
  const P = 'http://127.0.0.1:' + pub.address().port
  const L = 'http://127.0.0.1:' + loc.address().port
  const auth = (k) => ({ authorization: 'Bearer ' + k })
  const key = store.state.secret.localKey
  try {
    // 公网（funnel）那个端口：没有本机接口
    assert.equal((await fetch(P + '/bridge/local/today', { headers: auth(key) })).status, 404)
    // 本机端口：没密钥 / 手机密钥 → 401
    assert.equal((await fetch(L + '/bridge/local/today')).status, 401)
    assert.equal((await fetch(L + '/bridge/local/today', { headers: auth(store.state.secret.phoneKey) })).status, 401)
    // 来源不是本机 → 403（直接喂 handler 一个假的远端地址）
    const fake = Readable.from([])
    fake.method = 'GET'
    fake.url = '/bridge/local/today'
    fake.headers = auth(key)
    fake.socket = { remoteAddress: '100.101.102.103' }
    const res = { status: 0, writeHead(st) { this.status = st }, end() {} }
    await bridge.localHandler(fake, res)
    assert.equal(res.status, 403)
    // today：没同步过也答
    const t0 = await (await fetch(L + '/bridge/local/today', { headers: auth(key) })).json()
    assert.equal(t0.ok, true)
    assert.equal(t0.phoneOnline, false)
    // 手机同步一次（带复习摘要）
    const sync = (body) => fetch(P + '/bridge/sync', json(body, auth(store.state.secret.phoneKey))).then((r) => r.json())
    await sync({ snapshot: { ...SNAP, review: { dueCount: 1, totalCards: 3, retention7d: 80, reviewed7d: 5, due: [{ id: 'rc-1', topic: '资料分析', q: '截位直除何时截三位', a: '首位相同且次位差小', lapses: 1, options: [], pendingRecall: true }], weakTopics: [] } } })
    const t1 = await (await fetch(L + '/bridge/local/today', { headers: auth(key) })).json()
    assert.equal(t1.phoneOnline, true)
    assert.equal(t1.review.dueCount, 1)
    assert.equal(t1.today.timeline[0].id, 'seal-a')
    assert.match(t1.reviewText, /等隔天回忆/)
    // ingest（async）：立即 202，命令排上
    const ing = await fetch(L + '/bridge/local/ingest?async=1', json({ source: 'brainos_proposition', items: [{ externalId: 'brain-prop-42', topic: '资料分析', q: '【资料分析】什么前提下不能截两位？', a: '首位相同且次位差 ≤2%', premise: '首位相同' }, { q: '' }], reviewed: [{ card_id: 'rc-1', rating: 'good' }] }, auth(key)))
    assert.equal(ing.status, 202)
    const pulled = await sync({})
    const types = pulled.commands.map((c) => c.type)
    assert.deepEqual(types, ['review_ratings', 'push_review_cards'])
    const push = pulled.commands[1].payload
    assert.equal(push.source, 'brainos')
    assert.equal(push.items.length, 1, '空卡被筛掉')
    assert.equal(push.items[0].externalId, 'brain-prop-42')
    assert.equal(push.items[0].hint, '首位相同')
    assert.equal(pulled.commands[0].payload.channel, 'brainos')
    await sync({ results: pulled.commands.map((c) => ({ id: c.id, ok: true, text: 'ok' })) })
    // ingest（同步）：手机回了结果就带回去
    const waiting = fetch(L + '/bridge/local/ingest', json({ items: [{ q: '第二张卡的问题', a: '答案' }] }, auth(key))).then((r) => r.json())
    await new Promise((r) => setTimeout(r, 50))
    const p2 = await sync({})
    await sync({ results: [{ id: p2.commands[0].id, ok: true, text: '收了 1 张复习卡' }] })
    assert.match((await waiting).text, /收了 1 张/)
    // 空的 → 400；import-questions 排 import_questions
    assert.equal((await fetch(L + '/bridge/local/ingest', json({ items: [] }, auth(key)))).status, 400)
    const imp = await fetch(L + '/bridge/local/import-questions?async=1', json({ bankName: '资料分析错题', rawTextOrJson: '1. 题干一\nA. 甲 B. 乙\n答案：A' }, auth(key)))
    assert.equal(imp.status, 202)
    const p3 = await sync({})
    assert.equal(p3.commands[0].type, 'import_questions')
    assert.equal(p3.commands[0].payload.bankName, '资料分析错题')
  } finally {
    pub.close()
    loc.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('老的状态文件没有本机密钥：读的时候补一把，口令和手机密钥不动', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-old-'))
  try {
    writeFileSync(join(dir, 'state.json'), JSON.stringify({ v: 1, secret: { passphrase: 'aaaa-bbbb-cccc-dddd', phoneKey: 'dfy_old' } }))
    const store = createStore(join(dir, 'state.json'))
    assert.equal(store.state.secret.phoneKey, 'dfy_old')
    assert.equal(store.state.secret.passphrase, 'aaaa-bbbb-cccc-dddd')
    assert.match(store.state.secret.localKey, /^dfyl_/)
    assert.equal(createStore(join(dir, 'state.json')).state.secret.localKey, store.state.secret.localKey, '存下来了，重启不换')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('Gemini 的三个新工具：看到期卡（带答案）、推卡、灌题；回执带评分', async () => {
  await withBridge(async ({ http, store }) => {
    const { access_token: tk } = await connect(http, store)
    const sync = (body) => http('/bridge/sync', json(body, { authorization: 'Bearer ' + store.state.secret.phoneKey })).then((r) => r.json())
    const call = (name, args, id = 7) => rpc(http, tk, { jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args } }).then((r) => r.json())
    assert.match((await call('dafeiyu_review_due', {})).result.content[0].text, /还没传复习卡/)
    await sync({ snapshot: { ...SNAP, review: { dueCount: 1, totalCards: 1, retention7d: null, due: [{ id: 'rc-9', q: '哪个是质数', a: '7', options: ['4', '7'], lapses: 0 }], weakTopics: [], weakBank: [{ topic: '数量', wrong: 3 }] } } })
    const due = (await call('dafeiyu_review_due', {})).result.content[0].text
    assert.match(due, /\[rc-9\]哪个是质数（A\. 4 B\. 7） —— 答：7/)
    assert.match(due, /题库错题最多：数量（3 题）/)
    const pushP = call('dafeiyu_push_cards', { cards: [{ q: '问一', a: '答一' }, { q: '', a: 'x' }], checkpoint_id: 'seal-s' })
    await new Promise((r) => setTimeout(r, 50))
    const c1 = await sync({})
    assert.equal(c1.commands[0].type, 'push_review_cards')
    assert.equal(c1.commands[0].payload.items.length, 1)
    assert.equal(c1.commands[0].payload.checkpointId, 'seal-s')
    assert.equal(c1.commands[0].payload.source, 'gemini_mcp')
    await sync({ results: [{ id: c1.commands[0].id, ok: true, text: '收了 1 张复习卡' }] })
    assert.match((await pushP).result.content[0].text, /收了 1 张/)
    const impP = call('dafeiyu_import_questions', { bank_name: '数量错题', questions: [{ stem: '1+1=?', options: ['1', '2'], answer: 'B' }, { stem: '没答案' }] })
    await new Promise((r) => setTimeout(r, 50))
    const c2 = await sync({})
    assert.equal(c2.commands[0].type, 'import_questions')
    assert.equal(c2.commands[0].payload.questions.length, 1)
    await sync({ results: [{ id: c2.commands[0].id, ok: true, text: '题库新增 1 题' }] })
    assert.match((await impP).result.content[0].text, /新增 1 题/)
    const rP = call('dafeiyu_submit_receipt', { topic: '抽查', did: '口头答了两张卡', next: '继续', verdict: 'partial', reviewed_cards: [{ card_id: 'rc-9', rating: 'good' }, { card_id: 'rc-x', rating: 'meh' }] })
    await new Promise((r) => setTimeout(r, 50))
    const c3 = await sync({})
    assert.deepEqual(c3.commands[0].payload.reviewedCards, [{ card_id: 'rc-9', rating: 'good' }])
    await sync({ results: [{ id: c3.commands[0].id, ok: true, text: '回执收到' }] })
    await rP
  })
})
