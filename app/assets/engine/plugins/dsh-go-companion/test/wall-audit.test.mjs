// 档位（闲时 / 忙时）、聊天里直接管他（dafeiyu_control）、Gemini Live 搭桥（去：背景；回：读回复盘）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { existsSync, readFileSync } from 'node:fs'
import {
  GROWTH_MIN_BYTES, auditTask, baselineFor, checkMorningTasks, evidenceType, loadWallMemory, noteDone, notePromised,
  recentRate, statOf, wallText,
} from '../lib/wall_auditor.js'

function agentOf(id) {
  const contexts = []
  const sent = []
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent, contexts, tools,
    followup(m) { sent.push(m) },
    ctx: {
      systemPrompt: { context(def) { contexts.push(def); return () => { def.removed = true } } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents) {
  let handler
  const listeners = new Map()
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(name, fn) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn) },
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  apply(ctx)
  const emit = async (sid, type, data) => { for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, { type, data }) }
  return { handler: () => handler, emit }
}

/** 让某个会话"说完"一轮：Desk 认到 user/message 再到 turn/end 才算 generated。 */
async function finishTurn(h, agent, turn, text) {
  const m = agent.sent[agent.sent.length - 1]
  await h.emit(agent.id, 'turn/start', { turn })
  await h.emit(agent.id, 'user/message', { id: m.id, content: m.content, source: m.source, turn })
  await h.emit(agent.id, 'assistant/message', { turn, message: { content: [{ type: 'text', text }] } })
  await h.emit(agent.id, 'turn/end', { turn, reason: { kind: 'completed' } })
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-wall-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}


const SIS = 'session-sis'
const BIG = 'session-big'
function setup(home, extra = {}) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, ...extra }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big })
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const request = (id) => call(h.handler(), 'POST', '/dsh-go-companion/v1/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 30000 })
  const say = (sid, text) => h.emit(sid, 'user/message', { id: 'u-' + Math.random(), content: [{ type: 'text', text }], source: { kind: 'user' } })
  return { sis, big, h, plan, request, say }
}

// ---------------------------------------------------------------- 纯函数

test('完成物分三种：文件路径 / 截图 / 某 App N 分钟；空话墙查不了', () => {
  assert.equal(evidenceType('大肥鱼/周报.md'), 'file')
  assert.equal(evidenceType('/sdcard/Documents/a.pdf'), 'file')
  assert.equal(evidenceType('做完截图'), 'screenshot')
  assert.equal(evidenceType('粉笔 40分钟'), 'app')
  assert.equal(evidenceType('40分钟'), null, '只写时长不写 App 查不了')
  assert.equal(evidenceType('把底层方法论想清楚'), null)
  assert.equal(evidenceType(''), null)
})

test('晨间立项：1–3 件，每件的完成物都要查得到', () => {
  assert.equal(checkMorningTasks([]).ok, false)
  const vague = checkMorningTasks([{ task: '学习', evidence: '认真学' }])
  assert.equal(vague.ok, false)
  assert.match(vague.problems.join(), /墙查不了/)
  const four = checkMorningTasks([1, 2, 3, 4].map((i) => ({ task: '事' + i, evidence: '截图' })))
  assert.equal(four.ok, false)
  const ok = checkMorningTasks([{ task: '周报', evidence: '大肥鱼/周报.md' }, { task: '刷题', evidence: '粉笔 40分钟' }])
  assert.equal(ok.ok, true)
  assert.deepEqual(ok.tasks.map((t) => t.type), ['file', 'app'])
})

test('文件证据量的是立项后长了多少：改个字不算，写够才算', () => {
  const dir = mkdtempSync(join(tmpdir(), 'wall-file-'))
  try {
    const f = join(dir, '周报.md')
    writeFileSync(f, 'x'.repeat(5000))
    const task = { task: '周报', evidence: '周报.md' }
    const base = baselineFor([task], { notesDir: dir, stat: statOf })
    assert.equal(base['周报'].size, 5000, '立项时已经有 5000 字节')
    const ctx = { now: Date.now() + 1000, since: Date.now() - 1000, notesDir: dir, stat: statOf, baseline: base['周报'] }
    // 加一行：长了，但不够。
    writeFileSync(f, 'x'.repeat(5000) + '\n改了一个字')
    const small = auditTask(task, ctx)
    assert.equal(small.pass, false)
    assert.equal(small.verdict, 'partial')
    assert.match(small.missing, /还差至少/)
    // 写够了。
    writeFileSync(f, 'x'.repeat(5000 + GROWTH_MIN_BYTES))
    const big = auditTask(task, ctx)
    assert.equal(big.pass, true, big.data)
    assert.match(big.data, /立项时 5000 字节/)
    // 文件不存在：没有。
    assert.equal(auditTask({ task: '简历', evidence: '简历.md' }, { ...ctx, baseline: undefined }).verdict, 'miss')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test('截图证据只认立项之后截的；App 证据按今天的使用时长', () => {
  const since = 1_000_000
  const shot = { task: '刷题', evidence: '截图' }
  assert.equal(auditTask(shot, { now: since + 5000, since, screenshots: [{ name: 'a.png', mtimeMs: since - 1 }] }).pass, false, '立项前的截图不算')
  assert.equal(auditTask(shot, { now: since + 5000, since, screenshots: [{ name: 'b.png', mtimeMs: since + 10 }] }).pass, true)
  const app = { task: '刷题', evidence: '粉笔 40分钟' }
  const short = auditTask(app, { now: 0, since: 0, usage: [{ label: '粉笔', minutes: 25 }] })
  assert.equal(short.verdict, 'partial')
  assert.match(short.missing, /还差 15 分钟/)
  assert.equal(auditTask(app, { now: 0, since: 0, usage: [{ label: '粉笔', minutes: 41 }] }).pass, true)
  assert.equal(auditTask(app, { now: 0, since: 0, usage: [] }).verdict, 'miss')
})

test('墙的话：固定格式，不过时说清还缺什么，带近 14 天完成率', () => {
  const text = wallText({ title: '验收', data: ['周报：+20 字节'], pass: false, missing: ['还差至少 280 字节'], rate: { done: 3, total: 10 } })
  assert.match(text, /^〔墙〕 验收/)
  assert.match(text, /结论：不过。/)
  assert.match(text, /还缺：还差至少 280 字节/)
  assert.match(text, /定了 10 件，有证据完成 3 件（30%）/)
  assert.doesNotMatch(wallText({ title: '验收', data: [], pass: true, missing: ['x'] }), /还缺/)
})

test('言行账：14 天窗口内的立项数和有证据完成数', () => {
  let m = loadWallMemory({})
  m = notePromised(m, '2026-11-01', 3)
  m = noteDone(m, '2026-11-01', 1)
  m = notePromised(m, '2026-11-09', 2)
  m = noteDone(m, '2026-11-09', 2)
  m = notePromised(m, '2026-10-01', 5) // 窗口外
  assert.deepEqual(recentRate(m, '2026-11-09'), { done: 3, total: 5 })
})

// ---------------------------------------------------------------- 走一遍路由

test('冲刺验收：时长不够墙打回、够了才过；过了才放风，裁决由墙落账', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const study = (minutes) => ({ app: 'com.fenbi', label: '粉笔', minutes: 1, screenOn: true, local: '15:00', today: [{ app: 'com.fenbi', label: '粉笔', minutes }] })
    await t.plan(study(0))
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'start', task: '刷题', minutes: 45 })
    const tool = t.sis.tools.submit_to_wall
    // 文件类的临时完成物不收：没有立项时的底数，量不出长了多少。
    const file = tool.execute({ kind: 'review', evidence: '大肥鱼/刷题.md' })
    assert.equal(file.pass, false)
    assert.match(file.text, /文件类要在早上立项时登记/)
    await t.plan(study(10))
    const short = tool.execute({ kind: 'review', evidence: '粉笔 30分钟' })
    assert.equal(short.pass, false, short.text)
    assert.match(short.text, /还差 20 分钟/)
    await t.plan(study(32))
    const ok = tool.execute({ kind: 'review', evidence: '粉笔 30分钟' })
    assert.equal(ok.pass, true, ok.text)
    const rows = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
    assert.ok(rows.some((r) => r.state === 'verdict' && r.kind === 'review' && r.satisfied === true && r.by === 'wall'))
    assert.ok(rows.some((r) => r.state === 'verdict' && r.kind === 'review' && r.satisfied === false && r.by === 'wall'), '打回也落账')
    const cfg = JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    assert.ok(Number(cfg.breakUntil) > Date.now(), '过了才放风')
  })
})

test('违抗追问：他点「开始」就由墙当场关掉，不用聊；没开始之前交不过', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan({ app: 'com.game', label: '王者', fun: true, minutes: 1, screenOn: true, local: '15:00' })
    // 关窗后又打开游戏：插件开一把违抗追问锁。
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'defied', app: 'com.game', label: '王者' })
    const snap = await t.plan({ app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:01' })
    assert.equal(snap.body.followup?.reason, 'defiance', JSON.stringify(snap.body.followup))
    // 没开始之前交：墙不收。
    assert.equal(t.big.tools.submit_to_wall.execute({ kind: 'defiance' }).pass, false)
    await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/event', { type: 'start', task: '写周报', minutes: 25 })
    const rows = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
    assert.ok(rows.some((r) => r.state === 'wall-audit' && r.kind === 'defiance' && r.pass === false), '开始之前交被打回')
    assert.ok(rows.some((r) => r.state === 'wall-audit' && r.kind === 'defiance' && r.pass === true && r.by === 'start'), '开始那一下墙关锁')
    assert.ok(rows.some((r) => r.state === 'verdict' && r.kind === 'defiance' && r.satisfied === true && r.by === 'wall'))
  })
})
