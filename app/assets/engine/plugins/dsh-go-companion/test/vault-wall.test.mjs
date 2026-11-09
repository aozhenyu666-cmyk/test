// 档位（闲时 / 忙时）、聊天里直接管他（dafeiyu_control）、Gemini Live 搭桥（去：背景；回：读回复盘）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { existsSync, readFileSync } from 'node:fs'
import { readdirSync } from 'node:fs'
import { classifyRules, mealOf, parseRules, renderRules, renderTaskNote, updatePending } from '../lib/vault_wall.js'
import { wallConfig } from '../lib/wall.js'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-vault-'))
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

test('规则.md 往返：模板读回来和当前规则一样；吃饭时段格式不对的那段忽略', () => {
  const cur = wallConfig({})
  const back = parseRules(renderRules(cur))
  assert.equal(back.ok, true)
  assert.deepEqual(classifyRules(cur, back.wall), { tighten: {}, loosen: {} }, '原样读回不算改动')
  assert.deepEqual(mealOf('11:30-12:30 午饭'), { from: 690, to: 750, name: '午饭' })
  assert.equal(mealOf('中午随便吃'), null)
  const list = parseRules('---\n吃饭时段:\n  - "12:00-12:30 午饭"\n  - 乱写\n---\n')
  assert.equal(list.wall.meals.length, 1, 'YAML 块列表也认，乱写的那段丢掉')
  assert.equal(parseRules('没有规则区').ok, false)
})

test('改严立即生效、改松要等：吃饭多出一分钟就算改松', () => {
  const cur = wallConfig({})
  const shorter = cur.meals.map((m) => ({ ...m, to: m.to - 10 }))
  assert.deepEqual(Object.keys(classifyRules(cur, { meals: shorter }).tighten), ['meals'])
  const extra = [...cur.meals, { from: 15 * 60, to: 16 * 60, name: '下午茶' }]
  assert.deepEqual(Object.keys(classifyRules(cur, { meals: extra }).loosen), ['meals'])
  // 砍掉晚饭但加了下午茶：多出了时间，整项算改松。
  const swap = [cur.meals[0], cur.meals[1], { from: 15 * 60, to: 16 * 60, name: '下午茶' }]
  assert.deepEqual(Object.keys(classifyRules(cur, { meals: swap }).loosen), ['meals'])
  const r = classifyRules(cur, { eveningOpen: true, weeklyQuota: 1, relaxSegmentMin: 90 })
  assert.deepEqual(Object.keys(r.tighten), ['weeklyQuota'])
  assert.deepEqual(Object.keys(r.loosen).sort(), ['eveningOpen', 'relaxSegmentMin'])
})

test('待生效：值没变就接着计时，值变了重新起算，到期才交出去', () => {
  const day = 86_400_000
  const a = updatePending({}, { weeklyQuota: 5 }, 0, 7 * day)
  assert.deepEqual(a.due, {})
  const b = updatePending(a.pending, { weeklyQuota: 5 }, 6 * day, 7 * day)
  assert.equal(b.pending.weeklyQuota.since, 0, '同一个值接着计时')
  const c = updatePending(b.pending, { weeklyQuota: 6 }, 6 * day, 7 * day)
  assert.equal(c.pending.weeklyQuota.since, 6 * day, '改了值重新起算')
  const d = updatePending(a.pending, { weeklyQuota: 5 }, 7 * day, 7 * day)
  assert.deepEqual(d.due, { weeklyQuota: 5 })
  assert.deepEqual(updatePending(a.pending, {}, day, 7 * day).pending, {}, '改回去就撤销')
})

test('任务笔记：frontmatter 属性给 Obsidian Bases 用', () => {
  const md = renderTaskNote({ day: '2026-11-09', task: '周报', evidence: '大肥鱼/周报.md', status: '完成', proof: '+420 字节' })
  assert.match(md, /^---\n状态: "完成"\n日期: 2026-11-09\n完成物: "大肥鱼\/周报.md"\n证据: "\+420 字节"/)
  assert.match(md, /# 周报/)
})

// ---------------------------------------------------------------- 走一遍

test('笔记库里的规则：生成模板，改严立即生效，改松进待生效，改回去就撤销', async () => {
  await withHome(async (home) => {
    const vault = join(home, 'vault')
    mkdirSync(vault, { recursive: true })
    const t = setup(home, { notesPath: vault })
    const idle = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }
    await t.plan(idle)
    const rules = join(vault, '大肥鱼', '墙', '规则.md')
    const pending = join(vault, '大肥鱼', '墙', '待生效.md')
    assert.ok(existsSync(rules), '规则.md 模板写好了')
    assert.match(readFileSync(rules, 'utf8'), /每周放松天数: 3/)
    const cfg = () => JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
    const edit = (from, to) => writeFileSync(rules, readFileSync(rules, 'utf8').replace(from, to))

    edit('每周放松天数: 3', '每周放松天数: 1')
    await t.plan(idle)
    assert.equal(cfg().wall.weeklyQuota, 1, '改严立即生效')

    edit('每周放松天数: 1', '每周放松天数: 5')
    edit('对账后到深夜锁前娱乐: 锁', '对账后到深夜锁前娱乐: 开')
    await t.plan(idle)
    assert.equal(cfg().wall.weeklyQuota, 1, '改松不立即生效')
    assert.notEqual(cfg().wall.eveningOpen, true)
    const p = readFileSync(pending, 'utf8')
    assert.match(p, /每周放松天数\*\* → 5/)
    assert.match(p, /对账后到深夜锁前娱乐\*\* → 开/)

    edit('每周放松天数: 5', '每周放松天数: 1')
    await t.plan(idle)
    const q = readFileSync(pending, 'utf8')
    assert.doesNotMatch(q, /每周放松天数/, '改回去就撤销')
    assert.match(q, /对账后到深夜锁前娱乐/, '另一项还在等')
  })
})

test('冷却期设成 0 天：改松也立即生效（这是用户自己的选择）', async () => {
  await withHome(async (home) => {
    const vault = join(home, 'vault')
    mkdirSync(vault, { recursive: true })
    const t = setup(home, { notesPath: vault, rulesCooldownDays: 0 })
    const idle = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }
    await t.plan(idle)
    const rules = join(vault, '大肥鱼', '墙', '规则.md')
    writeFileSync(rules, readFileSync(rules, 'utf8').replace('每周放松天数: 3', '每周放松天数: 4'))
    await t.plan(idle)
    assert.equal(JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8')).wall.weeklyQuota, 4)
  })
})

test('墙的每次审查都写进 大肥鱼/墙/审计/日期.md', async () => {
  await withHome(async (home) => {
    const vault = join(home, 'vault')
    mkdirSync(vault, { recursive: true })
    const t = setup(home, { notesPath: vault })
    await t.plan({ app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' })
    t.big.tools.submit_to_wall.execute({ kind: 'gemini', next_step: '短' })
    const dir = join(vault, '大肥鱼', '墙', '审计')
    const files = readdirSync(dir)
    assert.equal(files.length, 1)
    const text = readFileSync(join(dir, files[0]), 'utf8')
    assert.match(text, /^# 墙的审计 · \d{4}-\d{2}-\d{2}/)
    assert.match(text, /## \d{2}:\d{2}\n\n〔墙〕 连麦回流/)
  })
})
