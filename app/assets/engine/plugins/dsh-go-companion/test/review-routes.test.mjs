// 复习卡接线（2026-11-21 验收第二批）：/review、锁屏先出到期卡（代码判 / 自判 0.7）、等兑现的无选项卡锁屏不出、
// 学习制卡当天发一半 + 隔天验过的回忆发另一半、聊天工具记的卡不算数。纯规则在 review-cards.test.mjs。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { minuteOfDay } from '../lib/tz.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const TZ = 'Asia/Shanghai'

function agentOf(id) {
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts: [], tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents) {
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  setStatePin('standby')
  apply(ctx)
  return () => handler
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = PREFIX + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-review-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, timeZone: TZ, quietStart: 12, quietEnd: 12, verifyMode: { enabled: false } }))
    writeFileSync(join(home, 'companion', 'bank.json'), JSON.stringify({ 题库版本: 1, id: 'b', 名称: '行测', 题目: [{ id: 'q-1', 题干: '行测题', 选项: ['甲', '乙'], 答案: 'A', 解析: '甲' }] }))
    const agents = { [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) }
    const h = host(agents)
    const post = (path, body) => call(h(), 'POST', path, body).then((r) => r.body)
    const plan = (phone) => call(h(), 'POST', '/plan', { roll: 0, timeZone: TZ, phone })
    return await fn({ home, post, agents, plan })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}

const json = (home, name) => JSON.parse(readFileSync(join(home, 'companion', name), 'utf8'))
const card = (over) => ({
  id: 'rc-x', source: 'gemini_mcp', topic: '资料分析', q: '一张复习卡的题干', a: '答案', options: [], answerIndex: -1, counts: false, sealId: '', day: '',
  createdAt: Date.now() - 20 * 3_600_000, fsrs: { state: 0, due: Date.now() - 60_000, stability: 0, difficulty: 0, reps: 0, lapses: 0, lastReview: 0 }, history: [], ...over,
})
const writeCards = (home, items) => writeFileSync(join(home, 'companion', 'review-cards.json'), JSON.stringify({ v: 1, items }))

test('/review：手动加卡（不算数）、摘要、列表、删卡', async () => {
  await withHome(async ({ home, post }) => {
    const add = await post('/review', { action: 'add', card: { q: '手动加的一张卡', a: '手动的答案', topic: '常识' } })
    assert.equal(add.ok, true, JSON.stringify(add))
    const pool = json(home, 'review-cards.json')
    assert.equal(pool.items.length, 1)
    assert.equal(pool.items[0].counts, false)
    const sum = await post('/review', { action: 'summary' })
    assert.equal(sum.totalCards, 1)
    assert.equal(sum.dueCount, 0, '新卡 12 小时后才到期')
    const list = await post('/review', { action: 'list' })
    assert.equal(list.items[0].q, '手动加的一张卡')
    assert.ok(list.items[0].next.good >= 1)
    const del = await post('/review', { action: 'delete', id: pool.items[0].id })
    assert.equal(del.ok, true)
    assert.equal(json(home, 'review-cards.json').items.length, 0)
  })
})

test('聊天工具 capture_review_card：进池、不算学习制卡、一次最多 3 张', async () => {
  await withHome(async ({ home, agents }) => {
    const tool = agents[SIS].tools.capture_review_card
    assert.ok(tool, '两条鱼手上有 capture_review_card')
    const r = await tool.execute({ cards: [{ q: '聊天里记的第一张', a: '答一' }, { q: '聊天里记的第二张', a: '答二' }] })
    assert.equal(r.ok, true, r.text)
    const items = json(home, 'review-cards.json').items
    assert.equal(items.length, 2)
    assert.ok(items.every((c) => c.source === 'dsh_chat' && c.counts === false))
  })
})

test('锁屏：先出到期的选择卡（代码判），答对算 1 题但不进题库答对数；答错 10 分钟后再问', async () => {
  await withHome(async ({ home, post }) => {
    writeCards(home, [
      card({ id: 'rc-a', q: '哪个是质数', options: ['4', '7'], answerIndex: 1, a: '7' }),
      card({ id: 'rc-b', q: '哪个是偶数', options: ['3', '8'], answerIndex: 1, a: '8', fsrs: { state: 0, due: Date.now() - 30_000 } }),
    ])
    const s = await post('/bank', { action: 'quiz_session' })
    assert.equal(s.question.id, 'rc:rc-a', JSON.stringify(s.question))
    assert.equal(s.question.kind, 'review')
    assert.equal(s.question.answer, undefined, '选择卡不给答案')
    const wrong = await post('/bank', { action: 'quiz_answer', questionId: 'rc:rc-a', answer: 'A' })
    assert.equal(wrong.correct, false)
    assert.equal(wrong.solved, 0)
    assert.equal(wrong.question.id, 'rc:rc-b', '下一题还是到期卡')
    const a = json(home, 'review-cards.json').items.find((x) => x.id === 'rc-a')
    assert.equal(a.fsrs.lapses, 1)
    assert.ok(a.fsrs.due > Date.now() + 9 * 60_000 && a.fsrs.due < Date.now() + 11 * 60_000)
    assert.equal(a.history[0].verified, true)
    const right = await post('/bank', { action: 'quiz_answer', questionId: 'rc:rc-b', answer: 'B' })
    assert.equal(right.correct, true)
    assert.equal(right.solved, 1)
    assert.equal(right.question.id, 'q-1', '到期卡刷完用题库补')
    const st = json(home, 'bank-unlock.json')
    assert.equal(st.stats.correct || 0, 0, '个人卡不进题库答对数')
    assert.equal(st.stats.reviewed, 2)
  })
})

test('锁屏：没选项的卡先藏答案、自判记得算 0.7 题；还在等隔天兑现的无选项卡锁屏不出', async () => {
  await withHome(async ({ home, post }) => {
    writeCards(home, [
      card({ id: 'rc-wait', q: '等兑现的卡', sealId: 'seal-x', counts: true, fsrs: { state: 0, due: Date.now() - 120_000 } }),
      card({ id: 'rc-self', q: '可以自判的卡', a: '标准答案' }),
    ])
    const s = await post('/bank', { action: 'quiz_session' })
    assert.equal(s.question.id, 'rc:rc-self')
    assert.equal(s.question.options.length, 0)
    assert.match(s.question.answer, /标准答案/)
    const r = await post('/bank', { action: 'quiz_answer', questionId: 'rc:rc-self', answer: 'REMEMBER' })
    assert.equal(r.correct, true)
    assert.equal(r.solved, 0.7)
    assert.equal(r.question.id, 'q-1', '等兑现的那张不出')
    const self = json(home, 'review-cards.json').items.find((x) => x.id === 'rc-self')
    assert.equal(self.history[0].verified, false)
  })
})

test('学习制卡：算数的卡绑到今天的点上发第一半；隔天验过的回忆答对够保底发第二半（只一次）', async () => {
  const min = minuteOfDay(Date.now(), TZ)
  if (min > 23 * 60 + 50) return // 快到零点：今天的截止时间造不出来
  await withHome(async ({ home, post }) => {
    const first = await post('/seals', { action: 'list' })
    const today = first.today
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({
      v: 1, items: [{ id: 'seal-s1', day: today, title: '资料分析制卡', kind: 'study_cards', stretch: 2, floor: 1, dueMinute: 23 * 60 + 59, estMinutes: 60, sealedAt: 1, source: 'morning' }],
    }))
    writeCards(home, [
      card({ id: 'rc-c1', q: '算数的一张卡', options: ['x', 'y'], answerIndex: 0, a: 'x', counts: true, day: today, createdAt: Date.now(), fsrs: { state: 0, due: Date.now() + 12 * 3_600_000 } }),
      card({ id: 'rc-c2', q: '不算数的一张卡', counts: false, day: today, createdAt: Date.now(), fsrs: { state: 0, due: Date.now() + 12 * 3_600_000 } }),
    ])
    const r = await post('/seals', { action: 'check' })
    const s = r.items.find((x) => x.id === 'seal-s1')
    assert.equal(s.actual, 1, '只有算数的那张')
    assert.equal(s.status, 'floor')
    assert.equal(s.paidMinutes, 10, '第一半：20 × 0.5')
    const pool = json(home, 'review-cards.json')
    assert.equal(pool.items.find((x) => x.id === 'rc-c1').sealId, 'seal-s1', '绑上了')
    // 12 小时内就去复习页答：不算回忆
    const early = await post('/review', { action: 'answer', id: 'rc-c1', index: 0 })
    assert.equal(early.ok, true)
    assert.equal(json(home, 'seals.json').items[0].deferredPaid, 0)
    // 造成「隔了 13 小时」：卡的创建时间往前挪、清掉刚才那次
    const p2 = json(home, 'review-cards.json')
    p2.items = p2.items.map((c) => (c.id === 'rc-c1' ? { ...c, createdAt: Date.now() - 13 * 3_600_000, history: [], fsrs: { ...c.fsrs, due: Date.now() - 1000 } } : c))
    writeFileSync(join(home, 'companion', 'review-cards.json'), JSON.stringify(p2))
    const ok = await post('/review', { action: 'answer', id: 'rc-c1', index: 0 })
    assert.equal(ok.correct, true)
    assert.match(ok.text, /第二半发了 10 分钟/)
    const sealAfter = json(home, 'seals.json').items[0]
    assert.equal(sealAfter.deferredPaid, 10)
    assert.ok(JSON.stringify(json(home, 'time-ledger.json')).includes('earned_review'))
    // 再答一次不再发
    const again = await post('/review', { action: 'answer', id: 'rc-c1', index: 0 })
    assert.doesNotMatch(again.text, /第二半/)
  })
})

test('体检和快照看得到到期卡', async () => {
  await withHome(async ({ home, post }) => {
    writeCards(home, [card({ id: 'rc-h' })])
    const h = await post('/health', {})
    const it = h.items.find((x) => x.id === 'review')
    assert.ok(it, JSON.stringify(h.items.map((x) => x.id)))
    assert.match(it.label, /1 张复习卡到期/)
  })
})

test('两条鱼抽问：待命亮屏有到期卡 → 候选里有 review-quiz；等兑现的卡不抽；rate_review_card 只改排期（不算验过）', async () => {
  await withHome(async ({ home, plan, agents }) => {
    writeCards(home, [
      card({ id: 'rc-wait', q: '等兑现的卡不该被抽', sealId: 'seal-x', counts: true, fsrs: { state: 0, due: Date.now() - 600_000 } }),
      card({ id: 'rc-q', q: '首鼠两端是什么意思', a: '犹豫不决' }),
    ])
    await plan({ app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 2, screenOn: true })
    const ledger = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l))
    const pick = ledger.filter((e) => e.state === 'speak-pick').pop()
    assert.ok(pick, '有发球仲裁记录')
    assert.ok(pick.candidates.some((c) => (c.reason || c) === 'review-quiz' || JSON.stringify(c).includes('review-quiz')), JSON.stringify(pick.candidates))
    // 抽到了的话：记一次、上下文里有这张卡；不管抽没抽到，都不会是等兑现的那张
    const quiz = ledger.find((e) => e.state === 'review-quiz')
    if (quiz) {
      assert.equal(quiz.cardId, 'rc-q')
      assert.equal(json(home, 'review-quiz.json').n, 1)
    }
    // 她判：只改排期，不算验过，不发分钟
    const tool = agents[SIS].tools.rate_review_card
    const res = await tool.execute({ card_id: 'rc-q', remembered: true })
    assert.equal(res.ok, true, res.text)
    const c = json(home, 'review-cards.json').items.find((x) => x.id === 'rc-q')
    assert.equal(c.history[0].channel, 'sister')
    assert.equal(c.history[0].verified, false)
    assert.ok(c.fsrs.due > Date.now() + 86_400_000 / 2)
    assert.equal((await tool.execute({ card_id: 'nope', remembered: false })).ok, false)
  })
})

test('两条鱼抽问：一天最多 2 次、隔 3 小时', async () => {
  await withHome(async ({ home, plan }) => {
    writeCards(home, [card({ id: 'rc-q2', q: '一张到期卡的题干' })])
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: TZ }).format(new Date())
    writeFileSync(join(home, 'companion', 'review-quiz.json'), JSON.stringify({ day: today, n: 2, lastAt: Date.now() - 5 * 3_600_000 }))
    await plan({ app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 2, screenOn: true })
    const ledger = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    assert.doesNotMatch(ledger, /review-quiz/)
  })
})
