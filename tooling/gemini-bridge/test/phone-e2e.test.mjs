// 两头真接：插件（手机引擎里那份代码）↔ 桥（电脑上那份）↔ 模拟 Gemini 调 MCP 工具。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { createBridge } from '../server.mjs'
import { createStore } from '../store.mjs'
import { apply, setStatePin } from '../../../app/assets/engine/plugins/dsh-go-companion/lib/index.js'
import { minuteOfDay } from '../../../app/assets/engine/plugins/dsh-go-companion/lib/tz.js'

const TZ = 'Asia/Shanghai'
const PREFIX = '/dsh-go-companion/v1'

function plugin() {
  let handler
  const agents = {}
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => [], list: () => [] }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async () => ({ agent: null }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  setStatePin('standby')
  apply(ctx)
  return (path, body) => {
    const req = Readable.from([Buffer.from(JSON.stringify(body))])
    req.method = 'POST'
    req.url = PREFIX + path
    req.headers = { 'content-type': 'application/json' }
    const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
    return Promise.resolve(handler(req, res)).then(() => JSON.parse(res.text || 'null'))
  }
}

test('手机配好桥 → 快照上去；Gemini 交回执、加验收点、提规矩 → 手机按规矩处理、结果回到 Gemini', async () => {
  const min = minuteOfDay(Date.now(), TZ)
  if (min > 1380) return // 23:00 以后今天加不了晚一点的点
  const dir = mkdtempSync(join(tmpdir(), 'dfy-bridge-e2e-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = join(dir, 'home')
  mkdirSync(join(dir, 'home', 'companion'), { recursive: true })
  writeFileSync(join(dir, 'home', 'companion', 'config.json'), JSON.stringify({ timeZone: TZ, quietStart: 12, quietEnd: 12 }))
  const store = createStore(join(dir, 'bridge.json'))
  const bridge = createBridge({ store, resultWaitMs: 5000, allowLocalRedirect: true })
  const server = createServer(bridge.handler)
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  const base = 'http://127.0.0.1:' + server.address().port
  try {
    const call = plugin()
    // 手机上先有一个 gemini_receipt 类的验收点
    const pd = await call('/seals', { action: 'plan-day', seals: [{ title: 'Gemini 抽背资料分析', kind: 'gemini_receipt', stretch: 1, dueMinute: 1439 }] })
    assert.ok(pd.added?.length, JSON.stringify(pd).slice(0, 300))
    const sealId = pd.added[0].id
    const cfg = await call('/gemini-bridge', { action: 'config', url: base, key: store.state.secret.phoneKey })
    assert.equal(cfg.synced, true, JSON.stringify(cfg))
    assert.equal(cfg.healthy, true)
    assert.equal(store.state.snapshot.timeline[0].id, sealId)
    // 错的密钥：说清楚
    const bad = await call('/gemini-bridge', { action: 'config', url: base, key: 'wrong' })
    assert.equal(bad.synced, false)
    assert.match(bad.lastError, /密钥不对/)
    await call('/gemini-bridge', { action: 'config', url: base, key: store.state.secret.phoneKey })

    // 模拟 Gemini：走完 OAuth 拿令牌
    const reg = await (await fetch(base + '/register', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ redirect_uris: ['http://127.0.0.1/cb'] }) })).json()
    const verifier = 'x'.repeat(48)
    const challenge = createHash('sha256').update(verifier).digest('base64url')
    const auth = await fetch(base + '/authorize', { method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ response_type: 'code', client_id: reg.client_id, redirect_uri: 'http://127.0.0.1/cb', code_challenge: challenge, code_challenge_method: 'S256', passphrase: store.state.secret.passphrase }) })
    const code = new URL(auth.headers.get('location')).searchParams.get('code')
    const tok = await (await fetch(base + '/token', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'authorization_code', code, client_id: reg.client_id, code_verifier: verifier }) })).json()
    const tool = async (name, args) => (await (await fetch(base + '/mcp', { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + tok.access_token },
      body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name, arguments: args } }) })).json()).result

    const today = await tool('dafeiyu_today', {})
    assert.match(today.content[0].text, /Gemini 抽背资料分析/)
    assert.match(today.content[0].text, /公约铁律/)
    const week = await tool('dafeiyu_week', {})
    assert.match(week.content[0].text, /周冷数据/)
    assert.match(week.content[0].text, /【周验收】/)

    // Gemini 交回执：工具在等，手机这边同步一次就把它处理了
    const pending = tool('dafeiyu_submit_receipt', { checkpoint_id: sealId, topic: '资料分析抽背', did: '讲清了增长率和基期', next: '下午开刷 30 题', verdict: 'pass',
      qa: [{ q: '增长率怎么算', a: '现期除以基期再减一' }, { q: '基期怎么求', a: '现期除以一加增长率' }] })
    await new Promise((r) => setTimeout(r, 150))
    await call('/gemini-bridge', { action: 'sync' })
    const rec = await pending
    assert.match(rec.content[0].text, /回执收到：「资料分析抽背」过/)
    assert.match(rec.content[0].text, /现在 1\/1条/)
    const seals = JSON.parse(readFileSync(join(dir, 'home', 'companion', 'seals.json'), 'utf8'))
    assert.equal(seals.items.find((x) => x.id === sealId).status, 'stretch', '回执算进验收点，过了拉伸')
    const ledger = readFileSync(join(dir, 'home', 'companion', 'ledger.jsonl'), 'utf8')
    assert.match(ledger, /"state":"gemini-recap".*"via":"gemini-mcp"/)

    // Gemini 加验收点：一条合格、一条不合格
    const addP = tool('dafeiyu_add_checkpoints', { target: 'today', items: [
      { title: '申论小作文', kind: 'bout_minutes', stretch: 40, due: '23:50' },
      { title: '没路径的笔记', kind: 'file', stretch: 300, due: '23:50' },
    ] })
    await new Promise((r) => setTimeout(r, 150))
    await call('/gemini-bridge', { action: 'sync' })
    const added = await addP
    assert.match(added.content[0].text, /加上了：「申论小作文」/)
    assert.match(added.content[0].text, /没加的：「没路径的笔记」/)
    const seals2 = JSON.parse(readFileSync(join(dir, 'home', 'companion', 'seals.json'), 'utf8'))
    assert.equal(seals2.items.find((x) => x.title === '申论小作文').source, 'gemini')

    // Gemini 提规矩：进提议，等他批
    const propP = tool('dafeiyu_propose_rule', { chapter: 2, title: '他说「就看一集」', do: '问他看完第一件事做什么，写成验收点', hardness: '默认', why: '这周三次' })
    await new Promise((r) => setTimeout(r, 150))
    await call('/gemini-bridge', { action: 'sync' })
    assert.match((await propP).content[0].text, /等他在手机「公约」页批/)
    const cov = await call('/covenant', { action: 'list' })
    assert.equal(cov.proposals.length, 1)
    assert.equal(cov.proposals[0].by, 'gemini')
    // Gemini 交周验收：进「等他决定」，不改任何数字
    const wrP = tool('dafeiyu_submit_week_review', { verdict: '这周在演', adjustments: ['题库类保底 10 → 15', '晚上 8 点后不排新点'], watch: '没过保底的个数' })
    await new Promise((r) => setTimeout(r, 150))
    await call('/gemini-bridge', { action: 'sync' })
    assert.match((await wrP).content[0].text, /等他在手机上逐条决定/)
    const sl = await call('/seals', { action: 'list' })
    assert.equal(sl.weekReview.by, 'gemini')
    assert.equal(sl.weekReview.adjustments.length, 2)
    const st = JSON.parse(readFileSync(join(dir, 'home', 'companion', 'gemini-bridge.json'), 'utf8'))
    assert.equal(st.applied, 4)
  } finally {
    server.close()
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(dir, { recursive: true, force: true })
  }
})
