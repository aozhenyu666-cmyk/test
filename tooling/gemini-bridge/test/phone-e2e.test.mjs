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
    const health = await tool('dafeiyu_health', {})
    assert.match(health.content[0].text, /体检/)
    const week = await tool('dafeiyu_week', {})
    assert.match(week.content[0].text, /周冷数据/)
    assert.match(week.content[0].text, /【周验收】/)
    const boss = await tool('dafeiyu_boss_brief', {})
    assert.ok(!boss.isError)
    assert.equal(boss.structuredContent.synced, true)
    assert.equal(boss.structuredContent.source, 'engine')
    assert.match(boss.content[0].text, /老板全局审视/)

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
    // Gemini：他说「开始了」→ 手机进默认验收（时段外也算）
    const swP = tool('dafeiyu_start_work', { hours: 1 })
    await new Promise((r) => setTimeout(r, 150))
    await call('/gemini-bridge', { action: 'sync' })
    assert.match((await swP).content[0].text, /开工：默认验收到/)
    const v = (await call('/seals', { action: 'list' })).verify
    assert.ok(v.startUntil > Date.now(), '开工计时')
    const st = JSON.parse(readFileSync(join(dir, 'home', 'companion', 'gemini-bridge.json'), 'utf8'))
    assert.equal(st.applied, 5)
  } finally {
    server.close()
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(dir, { recursive: true, force: true })
    } catch {
      // Windows SQLite handle cleanup grace
    }
  }
})

test('复习卡和题库互通：回执问答自动成卡、Gemini 推卡、BrainOS 从本机口推命题卡和导题、Gemini 判的评分改排期', async () => {
  const min = minuteOfDay(Date.now(), TZ)
  if (min > 1380) return
  const dir = mkdtempSync(join(tmpdir(), 'dfy-bridge-review-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = join(dir, 'home')
  mkdirSync(join(dir, 'home', 'companion'), { recursive: true })
  writeFileSync(join(dir, 'home', 'companion', 'config.json'), JSON.stringify({ timeZone: TZ, quietStart: 12, quietEnd: 12 }))
  writeFileSync(join(dir, 'home', 'companion', 'bank.json'), JSON.stringify({ 题库版本: 1, id: 'b', 名称: '行测', 题目: [{ id: 'q-1', 题干: '已有的一道题', 选项: ['甲', '乙'], 答案: 'A', 解析: '甲' }] }))
  const store = createStore(join(dir, 'bridge.json'))
  const bridge = createBridge({ store, resultWaitMs: 5000 })
  const server = createServer(bridge.handler)
  const local = createServer(bridge.localHandler)
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  await new Promise((r) => local.listen(0, '127.0.0.1', r))
  const base = 'http://127.0.0.1:' + server.address().port
  const L = 'http://127.0.0.1:' + local.address().port
  const lk = { 'content-type': 'application/json', authorization: 'Bearer ' + store.state.secret.localKey }
  const cards = () => JSON.parse(readFileSync(join(dir, 'home', 'companion', 'review-cards.json'), 'utf8')).items
  try {
    const call = plugin()
    const cfg = await call('/gemini-bridge', { action: 'config', url: base, key: store.state.secret.phoneKey })
    assert.equal(cfg.synced, true, JSON.stringify(cfg))
    const tool = (name, args) => import('../mcp.mjs').then((m) => m.callTool(bridge.ctx, name, args))
    const roundTrip = async (p) => {
      await new Promise((r) => setTimeout(r, 120))
      await call('/gemini-bridge', { action: 'sync' })
      return p
    }

    // 1. 判「过」的回执：两组像样的问答自动成卡（算学习制卡）
    const rec = await roundTrip(tool('dafeiyu_submit_receipt', { topic: '资料分析', did: '讲清了', next: '刷题', verdict: 'pass',
      qa: [{ q: '增长率怎么算出来', a: '现期除以基期再减一' }, { q: '基期量怎么求出来', a: '现期除以一加增长率' }] }))
    assert.match(rec.content[0].text, /问答收成 2 张复习卡/)
    assert.equal(cards().filter((c) => c.source === 'gemini_receipt' && c.counts).length, 2)

    // 2. Gemini 推卡：一张选择卡（算数）、一张问答卡（不算数）
    const push = await roundTrip(tool('dafeiyu_push_cards', { cards: [
      { topic: '数量', q: '下面哪个是质数', a: '7', options: ['4', '7', '9', '15'], answer: 'B' },
      { topic: '言语', q: '「首鼠两端」是什么意思', a: '犹豫不决、动摇不定' },
    ] }))
    assert.match(push.content[0].text, /收了 2 张复习卡（其中 1 张算学习制卡/)
    const choice = cards().find((c) => c.q === '下面哪个是质数')
    assert.equal(choice.answerIndex, 1)

    // 3. BrainOS 走本机口推命题卡（同步等结果），重复推不生双卡
    const prop = { externalId: 'brain-prop-42', topic: '资料分析', q: '【资料分析】什么前提下不能截两位？', a: '选项首位相同且次位差 ≤2% 时要截三位', premise: '首位相同且次位差 ≤2%' }
    const ingP = fetch(L + '/bridge/local/ingest', { method: 'POST', headers: lk, body: JSON.stringify({ source: 'brainos_proposition', items: [prop] }) }).then((r) => r.json())
    const ing = await roundTrip(ingP)
    assert.match(ing.text, /收了 1 张/)
    const again = await roundTrip(fetch(L + '/bridge/local/ingest', { method: 'POST', headers: lk, body: JSON.stringify({ source: 'brainos_proposition', items: [prop] }) }).then((r) => r.json()))
    assert.match(again.text, /1 张已经有了/)
    const bc = cards().filter((c) => c.externalId === 'brain-prop-42')
    assert.equal(bc.length, 1)
    assert.equal(bc[0].source, 'brainos')
    assert.equal(bc[0].counts, true)
    assert.match(bc[0].hint, /首位相同/)

    // 4. BrainOS / Gemini 导题：按题干去重
    const imp = await roundTrip(fetch(L + '/bridge/local/import-questions', { method: 'POST', headers: lk, body: JSON.stringify({
      questions: [{ stem: '已有的一道题', options: ['甲', '乙'], answer: 'A' }, { stem: '新的一道题：2+3=?', options: ['4', '5'], answer: 'B', analysis: '加法' }] }) }).then((r) => r.json()))
    assert.match(imp.text, /题库新增 1 题（现在一共 2 题）/, JSON.stringify(imp))
    const gimp = await roundTrip(tool('dafeiyu_import_questions', { questions: [{ stem: '新的一道题：2+3=?', options: ['4', '5'], answer: 'B' }] }))
    assert.equal(gimp.isError, true, '全是重复的：一题没进')

    // 5. 快照里看得到卡池；本机 today 也是
    await call('/gemini-bridge', { action: 'sync' })
    const due = await tool('dafeiyu_review_due', {})
    assert.match(due.content[0].text, /复习卡 5 张/)
    const lt = await (await fetch(L + '/bridge/local/today', { headers: lk })).json()
    assert.equal(lt.review.totalCards, 5)

    // 6. Gemini 当场考了一张、交回执带评分 → 手机改排期（验过）
    const rated = await roundTrip(tool('dafeiyu_submit_receipt', { topic: '抽查', did: '口头答了', next: '继续', verdict: 'partial', reviewed_cards: [{ card_id: choice.id, rating: 'good' }] }))
    assert.match(rated.content[0].text, /复习卡排期改了 1 张/)
    const after = cards().find((c) => c.id === choice.id)
    assert.equal(after.history.length, 1)
    assert.equal(after.history[0].channel, 'gemini')
    assert.equal(after.history[0].verified, true)
    // 笔记库没连：卡照样在手机里（镜像只是附带）
  } finally {
    server.close()
    local.close()
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(dir, { recursive: true, force: true })
  }
})

test('Live Core 与 PC Gemini 桥双向互通：快照含 liveCore、dafeiyu_submit_receipt 消除重拉、dafeiyu_live_relay 在手机落地', async () => {
  const min = minuteOfDay(Date.now(), TZ)
  if (min > 1420) return
  const dir = mkdtempSync(join(tmpdir(), 'dfy-bridge-livecore-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = join(dir, 'home')
  mkdirSync(join(dir, 'home', 'companion'), { recursive: true })
  writeFileSync(join(dir, 'home', 'companion', 'config.json'), JSON.stringify({ timeZone: TZ, quietStart: 12, quietEnd: 12 }))
  const store = createStore(join(dir, 'bridge.json'))
  const bridge = createBridge({ store, resultWaitMs: 5000 })
  const server = createServer(bridge.handler)
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  const base = 'http://127.0.0.1:' + server.address().port
  try {
    const call = plugin()
    await call('/live-core', { action: 'roll_dice', diceHint: 3 })
    const cfg = await call('/gemini-bridge', { action: 'config', url: base, key: store.state.secret.phoneKey })
    assert.equal(cfg.synced, true, JSON.stringify(cfg))
    assert.equal(store.state.snapshot.liveCore?.dice?.face, 3)

    const tool = (name, args) => import('../mcp.mjs').then((m) => m.callTool(bridge.ctx, name, args))
    const stRes = await tool('dafeiyu_live_status', {})
    assert.ok(!stRes.isError)
    assert.match(stRes.content[0].text, /错题追击战/)

    // 提交 MCP 回执后应把 receiptSinceLastLive 置为 true 并记录 lastRecap
    const recP = tool('dafeiyu_submit_receipt', {
      topic: 'Live 抽考错题',
      did: '口述排除法错因',
      next: '进入刷题巩固',
      verdict: 'pass',
      qa: [{ q: '排他对比怎么破', a: '找是否另有他因' }, { q: '削弱强弱排序', a: '直接否定前提强于质疑样本' }],
    })
    await new Promise((r) => setTimeout(r, 120))
    await call('/gemini-bridge', { action: 'sync' })
    const rec = await recP
    assert.ok(!rec.isError)
    const liveCoreDisk1 = JSON.parse(readFileSync(join(dir, 'home', 'companion', 'live-core.json'), 'utf8'))
    assert.equal(liveCoreDisk1.receiptSinceLastLive, true)
    assert.equal(liveCoreDisk1.lastRecap?.mode, 'mcp_receipt')

    // 通过 dafeiyu_live_relay 在手机落地 20m quiz_only 单开舱
    const relP = tool('dafeiyu_live_relay', {
      mode: 'quiz_only',
      minutes: 20,
      summary: '错题追击结束',
      next: 'MCP 遥控切纯考公刷题舱',
    })
    await new Promise((r) => setTimeout(r, 120))
    await call('/gemini-bridge', { action: 'sync' })
    const rel = await relP
    assert.ok(!rel.isError)
    assert.match(rel.content[0].text, /纯考公刷题舱/)
    const ledgerText = readFileSync(join(dir, 'home', 'companion', 'ledger.jsonl'), 'utf8')
    assert.match(ledgerText, /"state":"live-core-relay"/)
    assert.match(ledgerText, /"mode":"quiz_only"/)
  } finally {
    server.close()
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(dir, { recursive: true, force: true })
    } catch {
      // Windows SQLite handle cleanup grace
    }
  }
})
