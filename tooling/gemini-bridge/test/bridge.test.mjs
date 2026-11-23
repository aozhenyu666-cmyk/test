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
import { readInbox } from '../vault.mjs'
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
    assert.match(init.result.instructions, /dafeiyu_read_inbox/)
    assert.equal((await rpc(http, tk, { jsonrpc: '2.0', method: 'notifications/initialized' })).status, 202)
    const list = await (await rpc(http, tk, { jsonrpc: '2.0', id: 2, method: 'tools/list' })).json()
    const names = list.result.tools.map((t) => t.name)
    assert.deepEqual(names, ['dafeiyu_today', 'dafeiyu_rules', 'dafeiyu_health', 'dafeiyu_week', 'dafeiyu_submit_week_review', 'dafeiyu_start_work', 'dafeiyu_spend_balance', 'dafeiyu_add_checkpoints', 'dafeiyu_submit_receipt', 'dafeiyu_review_due', 'dafeiyu_read_inbox', 'dafeiyu_push_cards', 'dafeiyu_import_questions', 'dafeiyu_propose_rule', 'dafeiyu_boss_brief', 'dafeiyu_issue_sprint_custody'])
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
    assert.equal(list.result.tools.length, 19)
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

test('vault.readInbox：不存在优雅返回；按日期分段与条目解析并支持 limit', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-inbox-'))
  try {
    const emptyRes = readInbox(dir)
    assert.deepEqual(emptyRes, { ok: true, exists: false, entries: [], raw: '' })
    assert.equal(readInbox('').ok, false)

    mkdirSync(join(dir, '大肥鱼'), { recursive: true })
    writeFileSync(join(dir, '大肥鱼', '收件箱.md'), '\uFEFF# 大肥鱼 · 收件箱\n\n## 2026-11-20\n- 21:05 [随记] 资料分析百化分老忘\n- 22:10 [卡壳] 数量关系行程问题列不出式\n\n## 2026-11-21\n- 09:30 [灵感] 选项差距大时直接截两位\n- 没有时间和标签的纯碎片\n')

    const all = readInbox(dir, { limit: 10 })
    assert.equal(all.ok, true)
    assert.equal(all.exists, true)
    assert.equal(all.total, 4)
    assert.equal(all.entries.length, 4)
    assert.deepEqual(all.entries[0], { day: '2026-11-20', time: '21:05', tag: '随记', text: '资料分析百化分老忘', raw: '- 21:05 [随记] 资料分析百化分老忘' })
    assert.deepEqual(all.entries[1], { day: '2026-11-20', time: '22:10', tag: '卡壳', text: '数量关系行程问题列不出式', raw: '- 22:10 [卡壳] 数量关系行程问题列不出式' })
    assert.equal(all.entries[2].day, '2026-11-21')
    assert.equal(all.entries[2].tag, '灵感')
    assert.equal(all.entries[3].day, '2026-11-21')
    assert.equal(all.entries[3].text, '没有时间和标签的纯碎片')
    assert.match(all.rawSnippet, /2026-11-21/)

    const recent2 = readInbox(dir, { limit: 2 })
    assert.equal(recent2.ok, true)
    assert.equal(recent2.total, 4)
    assert.equal(recent2.entries.length, 2)
    assert.equal(recent2.entries[0].text, '选项差距大时直接截两位')
    assert.equal(recent2.entries[1].text, '没有时间和标签的纯碎片')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('四部门 dafeiyu_read_inbox（MCP）与 GET /bridge/local/inbox（本机接口）', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-dept-'))
  const vaultDir = join(dir, 'vault')
  mkdirSync(join(vaultDir, '大肥鱼'), { recursive: true })
  writeFileSync(join(vaultDir, '大肥鱼', '收件箱.md'), '## 2026-11-21\n- 08:40 [随记] 早上背了两页申论金句\n- 10:15 [卡壳] 基期倍数公式老跟比重混淆\n- 11:00 [灵感] 错题按题型归类进题库\n')

  const store = createStore(join(dir, 'state.json'))
  const bridge = createBridge({ store, vaultDir, resultWaitMs: 600 })
  const pub = createServer(bridge.handler)
  const loc = createServer(bridge.localHandler)
  await new Promise((r) => pub.listen(0, '127.0.0.1', r))
  await new Promise((r) => loc.listen(0, '127.0.0.1', r))
  const P = 'http://127.0.0.1:' + pub.address().port
  const L = 'http://127.0.0.1:' + loc.address().port
  const http = (path, init = {}) => fetch(P + path, { redirect: 'manual', ...init })
  const auth = (k) => ({ authorization: 'Bearer ' + k })
  const key = store.state.secret.localKey
  try {
    // 1. 鉴权校验：公网 404、无密钥/手机密钥 401、非本机 IP 403
    assert.equal((await fetch(P + '/bridge/local/inbox', { headers: auth(key) })).status, 404)
    assert.equal((await fetch(L + '/bridge/local/inbox')).status, 401)
    assert.equal((await fetch(L + '/bridge/local/inbox', { headers: auth(store.state.secret.phoneKey) })).status, 401)
    const fake = Readable.from([])
    fake.method = 'GET'
    fake.url = '/bridge/local/inbox'
    fake.headers = auth(key)
    fake.socket = { remoteAddress: '100.101.102.103' }
    const fakeRes = { status: 0, writeHead(st) { this.status = st }, end() {} }
    await bridge.localHandler(fake, fakeRes)
    assert.equal(fakeRes.status, 403)

    // 2. 手机同步快照（含手机收件箱条数、卡壳、未过保底验收点、到期复习摘要）
    const syncBody = {
      snapshot: {
        ...SNAP,
        timeline: [
          ...SNAP.timeline,
          { id: 'seal-low', title: '申论大作文提纲', kind: 'file', kindLabel: '笔记字数', unit: '字', start: '08:00', due: '09:30', stretch: 600, floor: 300, actual: 80, status: 'below', source: 'morning', sameDay: false },
        ],
        locking: { id: 'seal-low', title: '申论大作文提纲', actual: 80, floor: 300, until: '12:00' },
        phoneInbox: { count: 2, items: [{ day: '2026-11-21', time: '10:20', tag: '卡壳', text: '大作文总论点立意不稳' }] },
        stuck: ['材料三的数据没看懂'],
        review: { dueCount: 2, totalCards: 5, retention7d: 75, reviewed7d: 4, due: [{ id: 'rc-2', topic: '资料分析', q: '基期倍数怎么列式', a: 'A/B * (1+b)/(1+a)', lapses: 1 }], weakTopics: [{ topic: '资料分析', lapses: 2 }], weakBank: [{ topic: '数量关系', wrong: 4 }] },
      },
    }
    const syncRes = await fetch(P + '/bridge/sync', json(syncBody, auth(store.state.secret.phoneKey)))
    assert.equal(syncRes.status, 200)

    // 3. GET /bridge/local/inbox 验证（带 limit=2）
    const localRes = await fetch(L + '/bridge/local/inbox?limit=2', { headers: auth(key) })
    assert.equal(localRes.status, 200)
    const localBody = await localRes.json()
    assert.equal(localBody.ok, true)
    assert.equal(localBody.vaultConfigured, true)
    assert.equal(localBody.vaultInbox.total, 3)
    assert.equal(localBody.vaultInbox.entries.length, 2)
    assert.equal(localBody.phone.inboxCount, 2)
    assert.ok(localBody.phone.stuck.some((s) => s.includes('材料三的数据没看懂')))
    assert.ok(localBody.phone.stuck.some((s) => s.includes('大作文总论点立意不稳')))
    assert.equal(localBody.phone.belowFloor.length, 1)
    assert.equal(localBody.phone.belowFloor[0].id, 'seal-low')
    assert.equal(localBody.phone.review.dueCount, 2)
    assert.match(localBody.inboxText, /基期倍数公式老跟比重混淆/)

    // 4. MCP dafeiyu_read_inbox 调用验证
    const { access_token: tk } = await connect(http, store)
    const mcpRes = await (await rpc(http, tk, {
      jsonrpc: '2.0',
      id: 11,
      method: 'tools/call',
      params: { name: 'dafeiyu_read_inbox', arguments: { limit: 2 } },
    })).json()
    const toolResult = mcpRes.result
    assert.ok(!toolResult.isError)
    assert.match(toolResult.content[0].text, /电脑端收件箱/)
    assert.match(toolResult.content[0].text, /基期倍数公式老跟比重混淆/)
    assert.match(toolResult.content[0].text, /手机收件箱 2 条/)
    assert.match(toolResult.content[0].text, /申论大作文提纲/)
    assert.match(toolResult.content[0].text, /今日到期 2 张/)
    assert.equal(toolResult.structuredContent.vaultInbox.entries.length, 2)
    assert.equal(toolResult.structuredContent.phone.belowFloor[0].id, 'seal-low')
    assert.equal(toolResult.structuredContent.phone.review.dueCount, 2)
  } finally {
    pub.close()
    loc.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('[Boss-Wall Path A] dafeiyu_boss_brief（未同步/兜底合成/引擎账本/Obsidian防私密）与 GET /bridge/local/boss-brief', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-boss-a-'))
  const vaultDir = join(dir, 'vault')
  mkdirSync(join(vaultDir, '学习'), { recursive: true })
  mkdirSync(join(vaultDir, '日记'), { recursive: true })
  writeFileSync(join(vaultDir, '学习', '行测.md'), '# 行测\n增长率资料分析速算方法。')
  writeFileSync(join(vaultDir, '日记', '私事.md'), '绝对不能暴露在 boss_brief 里的日记')

  const store = createStore(join(dir, 'state.json'))
  const bridge = createBridge({ store, vaultDir, resultWaitMs: 600 })
  const pub = createServer(bridge.handler)
  const loc = createServer(bridge.localHandler)
  await new Promise((r) => pub.listen(0, '127.0.0.1', r))
  await new Promise((r) => loc.listen(0, '127.0.0.1', r))
  const P = 'http://127.0.0.1:' + pub.address().port
  const L = 'http://127.0.0.1:' + loc.address().port
  const http = (path, init = {}) => fetch(P + path, { redirect: 'manual', ...init })
  const auth = (k) => ({ authorization: 'Bearer ' + k })
  const key = store.state.secret.localKey
  try {
    const { access_token: tk } = await connect(http, store)
    const call = (args = {}, id = 21) =>
      rpc(http, tk, { jsonrpc: '2.0', id, method: 'tools/call', params: { name: 'dafeiyu_boss_brief', arguments: args } }).then((r) => r.json())

    // 1. 未同步前明确提示，不泄露私密日记
    const unsynced = (await call()).result
    assert.ok(!unsynced.isError)
    assert.match(unsynced.content[0].text, /还没跟桥同步过/)
    assert.equal(unsynced.structuredContent.synced, false)
    assert.ok(unsynced.structuredContent.recentNotes.some((n) => n.rel === '学习/行测.md'))
    assert.ok(!unsynced.structuredContent.recentNotes.some((n) => n.rel.includes('私事')))
    assert.doesNotMatch(unsynced.content[0].text, /私事\.md/)

    // 2. 仅有基础快照、无 bossBrief 字段时自动合成兜底
    await fetch(P + '/bridge/sync', json({ snapshot: SNAP }, auth(store.state.secret.phoneKey)))
    const synth = (await call({ days: 3 })).result
    assert.equal(synth.structuredContent.synced, true)
    assert.equal(synth.structuredContent.source, 'synthesized')
    assert.match(synth.content[0].text, /今日时间轴 0\/1/)

    // 3. 带入引擎 bossBrief 完整快照
    const richSnap = {
      ...SNAP,
      bossBrief: {
        day: '2026-11-22',
        headline: '7日兑现 35%（3/8） · 今日正事 20m / 娱乐 70m · 拦/急 4/2 · 悬空承诺 2',
        keyGaps: ['近 7 天计划/封印兑现率仅 35%', '今日纯娱乐 70m 已逼近或反超工作推进 20m'],
        snapshot: { fulfillmentRatePct: 35, todayWorkMin: 20, todayFunMin: 70, blockedCount: 4, emergencyPassCount: 2 },
        bossPrompt: '【角色指令：冷血直属主管 / 老板全局审视】\n1. 定性；\n2. 3 个直击全局逃避模式的尖锐追问；',
      },
    }
    await fetch(P + '/bridge/sync', json({ snapshot: richSnap }, auth(store.state.secret.phoneKey)))
    const rich = (await call()).result
    assert.equal(rich.structuredContent.source, 'engine')
    assert.equal(rich.structuredContent.brief.snapshot.fulfillmentRatePct, 35)
    assert.match(rich.content[0].text, /35%/)
    assert.match(rich.content[0].text, /学习\/行测\.md/)
    assert.doesNotMatch(rich.content[0].text, /私事\.md/)

    // 4. GET /bridge/local/boss-brief 校验
    assert.equal((await fetch(L + '/bridge/local/boss-brief')).status, 401)
    assert.equal((await fetch(L + '/bridge/local/boss-brief', { headers: auth(store.state.secret.phoneKey) })).status, 401)
    const locRes = await fetch(L + '/bridge/local/boss-brief?days=5', { headers: auth(key) })
    assert.equal(locRes.status, 200)
    const locBody = await locRes.json()
    assert.equal(locBody.ok, true)
    assert.equal(locBody.source, 'engine')
    assert.match(locBody.bossBriefText, /7日兑现 35%/)
  } finally {
    pub.close()
    loc.close()
    rmSync(dir, { recursive: true, force: true })
  }
})

test('dafeiyu_issue_sprint_custody 签发托管冲刺轨；dafeiyu_today 与 /bridge/local/today 透传 dayblock P3 锁与 custody', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'dfy-custody-bridge-'))
  const store = createStore(join(dir, 'state.json'))
  const bridge = createBridge({ store, resultWaitMs: 600 })
  const pub = createServer(bridge.handler)
  const loc = createServer(bridge.localHandler)
  await new Promise((r) => pub.listen(0, '127.0.0.1', r))
  await new Promise((r) => loc.listen(0, '127.0.0.1', r))
  const P = 'http://127.0.0.1:' + pub.address().port
  const L = 'http://127.0.0.1:' + loc.address().port
  const http = (path, init = {}) => fetch(P + path, { redirect: 'manual', ...init })
  const auth = (k) => ({ authorization: 'Bearer ' + k })
  try {
    const { access_token: tk } = await connect(http, store)
    const sync = (body) => http('/bridge/sync', json(body, auth(store.state.secret.phoneKey))).then((r) => r.json())
    const call = (name, args, id = 31) =>
      rpc(http, tk, { jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args } }).then((r) => r.json())

    // 1. 同步含 dayblock P3 锁 + 托管冲刺轨的快照
    await sync({
      snapshot: {
        ...SNAP,
        locking: {
          kind: 'dayblock',
          id: 'blk-v1',
          title: '行测30题',
          actual: 0,
          floor: 1,
          until: '11:00',
          lockedByBoss: true,
        },
        custody: {
          active: true,
          id: 'cus-1',
          title: '早间双飞轮',
          issuedBy: 'gemini',
          untilHm: '11:30',
          rebriefCount: 0,
        },
        verifyLockCenter: {
          totalVerify: 2,
          passedVerify: 0,
          overdueCount: 1,
          pendingReviewCount: 0,
          overdueBlocks: [{ id: 'blk-v1', title: '行测30题', dueHm: '10:00', lockedByBoss: true }],
          pendingReviewBlocks: [],
        },
      },
    })

    const todayRes = (await call('dafeiyu_today', {})).result
    assert.ok(!todayRes.isError)
    const todayStr = todayRes.content[0].text
    assert.match(todayStr, /计划验收块「行测30题」/)
    assert.match(todayStr, /托管冲刺轨中：「早间双飞轮」（至 11:30/)
    assert.match(todayStr, /验收锁中枢：已过 0\/2/)

    const locToday = await (await fetch(L + '/bridge/local/today', { headers: auth(store.state.secret.localKey) })).json()
    assert.equal(locToday.ok, true)
    assert.equal(locToday.custody.title, '早间双飞轮')
    assert.equal(locToday.verifyLockCenter.overdueCount, 1)

    // 2. dafeiyu_issue_sprint_custody 入队并回传手机结果
    const emptyRes = (await call('dafeiyu_issue_sprint_custody', { title: '无块测试' })).result
    assert.equal(emptyRes.isError, true)

    const issueP = call('dafeiyu_issue_sprint_custody', {
      title: '下午考公连冲',
      arm_auto_kickoff: true,
      blocks: [
        { title: '第一棒 资料25m', deliverable: '完成20题真题', duration_min: 25 },
        { title: '第二棒 错因25m', deliverable: '写入Obsidian错题本', duration_min: 25 },
      ],
    })
    await new Promise((r) => setTimeout(r, 50))
    const pulled = await sync({})
    assert.equal(pulled.commands.length, 1)
    assert.equal(pulled.commands[0].type, 'issue_sprint_custody')
    assert.equal(pulled.commands[0].payload.title, '下午考公连冲')
    assert.equal(pulled.commands[0].payload.armAutoKickoff, true)
    assert.equal(pulled.commands[0].payload.blocks.length, 2)

    await sync({ results: [{ id: pulled.commands[0].id, ok: true, text: '已签发「下午考公连冲」' }] })
    const doneRes = (await issueP).result
    assert.ok(!doneRes.isError)
    assert.match(doneRes.content[0].text, /已签发「下午考公连冲」/)
  } finally {
    pub.close()
    loc.close()
    rmSync(dir, { recursive: true, force: true })
  }
})
