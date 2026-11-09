// 姐妹双人格：妹妹（陪伴）主动找他、陪他闹；姐姐（谋士）管正事、往前推两步。各占一个会话，各挂各的人设。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { buildPrompt } from '../lib/prompt.js'
import { companionChatText, strategistChatText, roleNames } from '../lib/roles.js'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-dual-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try { return await fn(home) } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

test('妹妹的人设：是朋友不是助手，不碰正事、正事甩给姐姐', () => {
  const t = companionChatText({})
  assert.match(t, /你是鱼丸/)
  assert.match(t, /不是助手、不是客服、不是工具/)
  assert.match(t, /不碰正事/)
  assert.match(t, /去问鲸澜/)
  assert.doesNotMatch(t, /\{姐姐\}|\{妹妹\}/, '占位符都替换掉了')
})

test('姐姐的人设：谋士，往前推两步、摆几条路、只问一个关键问题、落到第一步', () => {
  const t = strategistChatText({}, ['你的底线（绝不做）：不撒谎'])
  assert.match(t, /你是鲸澜/)
  assert.match(t, /往前推两步/)
  assert.match(t, /2–3 条路/)
  assert.match(t, /只问这一个/)
  assert.match(t, /检验点/)
  assert.match(t, /不撒谎/, '关系库人物卡并进姐姐')
})

test('名字和人设可改', () => {
  assert.deepEqual(roleNames({ companionName: '鱼丸', strategistName: '鲸姐' }), { companion: '鱼丸', strategist: '鲸姐' })
  assert.match(companionChatText({ companionName: '鱼丸', strategistName: '鲸姐' }), /去问鲸姐/)
  assert.match(companionChatText({ companionCard: '只会说喵' }), /只会说喵/)
})

test('主动发球由妹妹说：不讲道理、不劝做事，带着自己的态度', () => {
  const p = buildPrompt({ role: 'companion', serveType: 'observe', names: { companion: '鱼丸', strategist: '鲸澜' }, owner: '他' })
  assert.match(p, /你是鱼丸——他的朋友，不是助手/)
  assert.match(p, /别劝他"该去做事了"/)
  assert.match(p, /吃醋/)
  assert.doesNotMatch(p, /思维教练/)
})

test('两个会话各挂各的人设；/say 带 role=strategist 就发给姐姐', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12 }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    const h = host({ 'session-sis': sis, 'session-big': big })
    // 启动时就挂上
    assert.equal(sis.contexts.length, 1)
    assert.equal(big.contexts.length, 1)
    assert.match(sis.contexts[0].text(), /你是鱼丸/)
    assert.match(big.contexts[0].text(), /你是鲸澜，.*谋士/)
    const a = await call(h.handler(), 'POST', '/dsh-go-companion/v1/say', { v: 1, requestId: 'say-to-sister-1', text: '在吗' })
    assert.equal(a.status, 202)
    assert.equal(sis.sent.length, 1)
    const b = await call(h.handler(), 'POST', '/dsh-go-companion/v1/say', { v: 1, requestId: 'say-to-big-001', text: '我该考研还是工作', role: 'strategist' })
    assert.equal(b.status, 202, JSON.stringify(b.body))
    assert.equal(big.sent.length, 1)
    assert.equal(big.sent[0].content[0].text, '我该考研还是工作')
  })
})

test('关掉双人格（dual:false）：回到单人格大肥鱼，姐姐的会话不挂人设', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', dual: false }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    host({ 'session-sis': sis, 'session-big': big })
    assert.match(sis.contexts[0].text(), /你是大肥鱼，.*身边的伙伴/)
    assert.equal(big.contexts.length, 0)
  })
})

test('姐妹传话：妹妹递急话 → 姐姐每轮看得到 → /plan 让姐姐主动开口 → 球进姐姐的会话、带着这句话', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12 }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    const h = host({ 'session-sis': sis, 'session-big': big })
    assert.ok(sis.tools.sister_note && big.tools.sister_note, '两个会话各有自己的传话工具')
    const r = sis.tools.sister_note.execute({ text: '他说找工作好烦，快去管管', urgent: true })
    assert.equal(r.ok, true)
    assert.match(big.contexts[0].text(), /鱼丸刚跟你递话.*找工作好烦/)
    assert.doesNotMatch(sis.contexts[0].text(), /姐妹之间的悄悄话/, '自己递的话不出现在自己这边')
    const plan = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai' })
    assert.equal(plan.body.speak, true, JSON.stringify(plan.body))
    assert.equal(plan.body.reason, 'strategist-handoff')
    assert.equal(plan.body.speaker, 'strategist')
    const req = await call(h.handler(), 'POST', '/dsh-go-companion/v1/request', { v: 1, requestId: 'req-big-0001', eventId: 'evt-big-0001', kind: 'random_chat', payload: {}, deadlineMs: 30000 })
    assert.equal(req.status, 202, JSON.stringify(req.body))
    assert.equal(big.sent.length, 1, '球进了姐姐的会话')
    assert.equal(sis.sent.length, 0)
    assert.match(big.sent[0].content[0].text, /妹妹鱼丸刚跟你递话："他说找工作好烦/)
    assert.match(big.sent[0].content[0].text, /不要上网查资料/)
    await finishTurn(h, big, 3, '工作的事别一个人扛。先告诉我：现在投了几家？')
    const res = await call(h.handler(), 'GET', '/dsh-go-companion/v1/result?requestId=req-big-0001&waitMs=0')
    assert.equal(res.body.state, 'generated')
    assert.match(res.body.text, /^〔鲸澜〕工作的事/, '标上是谁说的，浮层据此显示名字')
    // 姐姐刚来找过他：浮层里他直接回，默认回给姐姐
    const say = await call(h.handler(), 'POST', '/dsh-go-companion/v1/say', { v: 1, requestId: 'say-reply-0001', text: '投了二十家' })
    assert.equal(say.status, 202)
    assert.equal(big.sent.length, 2, '回给了姐姐')
    // 递过一次的急话不再触发
    const again = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai' })
    assert.notEqual(again.body.reason, 'strategist-handoff')
  })
})

test('晚上 21:30 以后（当地）姐姐对一次账，一天一次', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12 }))
    const h = host({ 'session-sis': agentOf('session-sis'), 'session-big': agentOf('session-big') })
    const phone = { app: 'com.tencent.mm', minutes: 1, screenOn: true, local: '21:40' }
    const plan = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
    assert.equal(plan.body.reason, 'strategist-review', JSON.stringify(plan.body))
    const day = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: { ...phone, local: '16:00' } })
    assert.notEqual(day.body.speaker, 'strategist', '白天不对账')
  })
})

test('主动找他时可以动手（他给了高权限），关掉就不许', () => {
  const on = buildPrompt({ role: 'companion', allowTools: true, names: { companion: '鱼丸', strategist: '鲸澜' } })
  assert.match(on, /需要时可以动手/)
  assert.match(on, /不可逆/)
  const off = buildPrompt({ role: 'companion', allowTools: false, names: { companion: '鱼丸', strategist: '鲸澜' } })
  assert.match(off, /不要调用任何工具/)
})

test('活人感：妹妹有固定的小日常，姐姐不是每次都查资料、回复长短随事情变', () => {
  assert.match(companionChatText({}), /仓鼠/)
  assert.match(companionChatText({}), /share: true/, '记他的事要两个人都看得到')
  const big = strategistChatText({})
  assert.match(big, /不查，直接说/)
  assert.match(big, /小事一两句/)
  assert.match(big, /跑步/)
})

test('上一版存进配置的默认名字（小肥鱼/大肥鱼）升级后换成鱼丸/鲸澜', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', companionName: '小肥鱼', strategistName: '大肥鱼' }))
    const sis = agentOf('session-sis')
    host({ 'session-sis': sis, 'session-big': agentOf('session-big') })
    assert.match(sis.contexts[0].text(), /你是鱼丸/)
  })
})

test('浮层里他刚回了一句：/plan 马上来问也不再发球（不把正在聊的窗口顶掉）', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12 }))
    const sis = agentOf('session-sis')
    const h = host({ 'session-sis': sis, 'session-big': agentOf('session-big') })
    // 晚上对账时段：不聊的时候这一问一定开口（姐姐对账），用来确认是"正在聊"把它压住的
    const phone = { app: 'com.tencent.mm', minutes: 1, screenOn: true, local: '21:40' }
    // 他发了一句；user/message 事件还没回来，手机的 /plan 就先到了
    const say = await call(h.handler(), 'POST', '/dsh-go-companion/v1/say', { v: 1, requestId: 'say-race-0001', text: '哈哈哈' })
    assert.equal(say.status, 202)
    const plan = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
    assert.equal(plan.body.speak, false, JSON.stringify(plan.body))
    assert.equal(plan.body.reason, 'in-conversation')
  })
})

test('拒绝模式：一句话开关；开着时娱乐 App 一打开就赶回桌面锁住，讲理由也不松', async () => {
  const { refuseSwitch, decideForce } = await import('../lib/force.js')
  const now = 1_000_000
  assert.equal(refuseSwitch('拒绝模式', now).until, now + 2 * 3_600_000)
  assert.equal(refuseSwitch('开启拒绝模式 3 小时', now).until, now + 3 * 3_600_000)
  assert.equal(refuseSwitch('拒绝模式40分钟', now).until, now + 40 * 60_000)
  assert.deepEqual(refuseSwitch('关掉拒绝模式', now), { off: true })
  assert.deepEqual(refuseSwitch('拒绝模式结束', now), { off: true })
  assert.equal(refuseSwitch('今天好累', now), null)
  const game = { app: 'com.game', label: '王者', fun: true, screenOn: true, minutes: 0 }
  const f = decideForce({ phone: game, mode: 'partner', refuse: true, grace: true })
  assert.equal(f.level, 4)
  assert.equal(f.refuse, true)
  assert.equal(decideForce({ phone: game, mode: 'partner', refuse: true, control: false }).level, 2, '没给控机权限只能到浮层')
  assert.equal(decideForce({ phone: { ...game, fun: false }, refuse: true }).level, 0, '不在清单里的 App 不管')
  assert.equal(decideForce({ phone: game, mode: 'partner' }).level, 0, '没开拒绝模式：刚打开不锁')

  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12 }))
    const sis = agentOf('session-sis')
    const h = host({ 'session-sis': sis, 'session-big': agentOf('session-big') })
    await h.emit('session-sis', 'user/message', { id: 'u1', content: [{ type: 'text', text: '开启拒绝模式 1 小时' }], source: { kind: 'user' } })
    const plan = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: { ...game, local: '15:00' } })
    assert.equal(plan.body.force.level, 4, JSON.stringify(plan.body.force))
    assert.equal(plan.body.force.release, false)
    assert.ok(plan.body.refuse && plan.body.refuse.until > Date.now() + 50 * 60_000, '手机拿到到期时间，自己在切 App 那一刻锁')
    assert.match(sis.contexts[0].text(), /拒绝模式开着/, '她知道拒绝模式开着，求情不松')
    await h.emit('session-sis', 'user/message', { id: 'u2', content: [{ type: 'text', text: '关掉拒绝模式' }], source: { kind: 'user' } })
    const off = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: { ...game, local: '15:00' } })
    assert.equal(off.body.refuse, null)
    assert.ok(off.body.force.level < 3)
  })
})

test('能力：两姐妹都知道自己能看屏幕、动手机、翻相册、读笔记库；他说了笔记库路径就记下', async () => {
  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12 }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    host({ 'session-sis': sis, 'session-big': big })
    const t = sis.contexts[0].text()
    assert.match(t, /mcp__phone__screen/)
    assert.match(t, /mcp__phone__intent/)
    assert.match(t, /\/sdcard\/DCIM/)
    assert.doesNotMatch(t, /按回桌面/)
    // 第四轮：两人工具一致，站他这边；过关交给墙。
    assert.match(t, /submit_to_wall/, '妹妹知道怎么交给墙')
    assert.match(t, /站他这边/, '妹妹是盟友，不是监工')
    // 第四轮补：用户把放风的口子也给了她们——当场用 grant_break，明天用 request_relax。
    assert.match(t, /grant_break/, '妹妹知道自己能当场放一段')
    assert.match(t, /submit_verdict/, '妹妹知道自己能裁决')
    assert.match(t, /submit_to_wall/, '墙那条路也还在')
    assert.match(big.contexts[0].text(), /读他的笔记和日程再推演/)
    assert.ok(sis.tools.notes_vault && big.tools.notes_vault)
    assert.equal(sis.tools.notes_vault.execute({ path: 'Documents/x' }).ok, false, '要绝对路径')
    assert.equal(sis.tools.notes_vault.execute({ path: join(home, 'nope') }).ok, false, '看不到的文件夹不收')
    const vault = join(home, 'vault')
    mkdirSync(vault)
    const r = sis.tools.notes_vault.execute({ path: vault + '/' })
    assert.equal(r.ok, true, r.text)
    assert.match(big.contexts[0].text(), new RegExp('笔记库在 ' + vault.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')), '姐姐也看得到')
    assert.match(big.contexts[0].text(), /鲸澜\//, '姐姐可以把计划写进笔记库')
  })
})
