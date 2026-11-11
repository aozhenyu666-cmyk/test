// C1 挂载卡：/mount 路由（面板 + 浮层）、三个工具、注入行、支线兜底。
//
// mount.js 的纯函数另有 mount.test.mjs；这里只证明接线是对的——
// 落盘的卡能不能按规矩改、工具提议有没有出处、注入里有没有这张卡。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply } from '../lib/index.js'
import { appendLedger, readLedger } from '../lib/ledger.js'

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
  apply(ctx)
  return { handler: () => handler }
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
  const home = mkdtempSync(join(tmpdir(), 'companion-mount-'))
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
  const mount = (body) => call(h.handler(), 'POST', '/dsh-go-companion/v1/mount', { timeZone: 'Asia/Shanghai', ...body })
  const read = (query = '') => call(h.handler(), 'GET', '/dsh-go-companion/v1/mount' + query)
  const status = () => call(h.handler(), 'GET', '/dsh-go-companion/v1/status')
  return { sis, big, h, mount, read, status }
}

test('R1 GET /mount：没卡时 status=none，别报错', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.read()
    assert.equal(r.status, 200)
    assert.equal(r.body.ok, true)
    assert.equal(r.body.mount.status, 'none')
    assert.equal(r.body.mount.givens.length, 0)
    assert.equal(r.body.mount.maxGivens, 5)
    assert.deepEqual(r.body.mount.cards, [])
  })
})

test('R2 开卡必须有一句目标', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.mount({ action: 'open', goal: '   ' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'goal-empty')
  })
})

test('R3 开卡后 status=open，目标和上一次动的时间都在', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.mount({ action: 'open', goal: '把长连接的增量同步做出来', topicId: 'sync' })
    assert.equal(r.body.ok, true, JSON.stringify(r.body))
    assert.equal(r.body.mount.status, 'open')
    assert.equal(r.body.mount.goal, '把长连接的增量同步做出来')
    assert.equal(r.body.mount.cards.length, 1)
    assert.equal(r.body.mount.cards[0].status, 'open')
  })
})

test('R4 同时只有一张 open 卡：第二张开之前得先停放或关掉', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '先解决增量同步' })
    const again = await t.mount({ action: 'open', goal: '换个话题看内存' })
    assert.equal(again.body.ok, false)
    assert.equal(again.body.error.code, 'card-open')
  })
})

test('R5 已知条件加到 5 条就满，结论必须引用已知', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '查清楚为什么内存一直涨' })
    // 结论在已知为空时无从推起。
    const noGiven = await t.mount({ action: 'step', text: '所以是订阅没解绑' })
    assert.equal(noGiven.body.ok, false)
    assert.equal(noGiven.body.error.code, 'step-needs-given')
    for (let i = 0; i < 5; i += 1) {
      const one = await t.mount({ action: 'given', text: '第 ' + (i + 1) + ' 条已知' })
      assert.equal(one.body.ok, true, JSON.stringify(one.body))
    }
    const over = await t.mount({ action: 'given', text: '第 6 条已知' })
    assert.equal(over.body.ok, false)
    assert.equal(over.body.error.code, 'givens-full')
    // 引用越界同样拒。
    const bad = await t.mount({ action: 'step', text: '所以是订阅没解绑', from: [9] })
    assert.equal(bad.body.ok, false)
    assert.equal(bad.body.error.code, 'step-needs-given')
    const ok = await t.mount({ action: 'step', text: '所以是订阅没解绑', from: [0, 2] })
    assert.equal(ok.body.ok, true, JSON.stringify(ok.body))
    assert.equal(ok.body.mount.steps.length, 1)
  })
})

test('R6 停放要说一句停在哪；停好之后能恢复', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把增量同步做出来' })
    await t.mount({ action: 'given', text: '服务端已经有版本号了' })
    const noWhy = await t.mount({ action: 'park', where: '  ' })
    assert.equal(noWhy.body.ok, false)
    assert.equal(noWhy.body.error.code, 'park-needs-why')
    const parked = await t.mount({ action: 'park', where: '版本号拿到手了，下一步比大小' })
    assert.equal(parked.body.ok, true)
    assert.equal(parked.body.mount.status, 'parked')
    const id = parked.body.mount.cards[0].id
    const resumed = await t.mount({ action: 'resume', id })
    assert.equal(resumed.body.ok, true, JSON.stringify(resumed.body))
    assert.equal(resumed.body.mount.status, 'open')
    assert.equal(resumed.body.mount.givens.length, 1, '恢复要带回原来那几条已知')
    // 面板要摆的是原话，不是个数：恢复之后第一句还在。
    assert.equal(resumed.body.mount.givens[0].text, '服务端已经有版本号了')
  })
})

test('R7 已有一张 open 时不能恢复第二张', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '第一件事' })
    await t.mount({ action: 'park', where: '停在第一步' })
    const first = (await t.mount({ action: 'status' })).body.mount.cards[0].id
    await t.mount({ action: 'open', goal: '第二件事' })
    const r = await t.mount({ action: 'resume', id: first })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'card-open')
  })
})

test('R8 关掉之后只读：不给再加已知', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把今天这件事收尾' })
    const closed = await t.mount({ action: 'close' })
    assert.equal(closed.body.ok, true)
    assert.equal(closed.body.mount.status, 'closed')
    const r = await t.mount({ action: 'given', text: '再来一条' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.error.code, 'no-card', '关掉的卡不再是 open，落盘视图里它已经不是当前卡')
    const twice = await t.mount({ action: 'close' })
    assert.equal(twice.body.ok, false)
  })
})

test('R9 mount_propose：没有账本行号、行号不存在、不是他的原话，全部拒', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把增量同步做出来' })
    const blank = t.sis.tools.mount_propose.execute({ text: '服务端有版本号', slot: 'given', src: '' })
    assert.equal(blank.ok, false)
    const ghost = t.sis.tools.mount_propose.execute({ text: '服务端有版本号', slot: 'given', src: '9999' })
    assert.equal(ghost.ok, false)
    appendLedger(home, { state: 'say-text', at: Date.now(), text: '服务端已经有版本号了，就不用全量对比' })
    const seq = readLedger(home, 5000).length
    // 他自己没说过这句话——是她总结的。
    const madeUp = t.sis.tools.mount_propose.execute({ text: '所以瓶颈在网络层', slot: 'given', src: String(seq) })
    assert.equal(madeUp.ok, false)
    assert.match(madeUp.text, /原话/)
  })
})

test('R10 mount_propose → accept：提议要带行号，点头才落卡，并且记成 her', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把增量同步做出来' })
    appendLedger(home, { state: 'say-text', at: Date.now(), text: '服务端已经有版本号了，就不用全量对比' })
    const seq = readLedger(home, 5000).length
    const p = t.sis.tools.mount_propose.execute({ text: '服务端已经有版本号了', slot: 'given', src: String(seq) })
    assert.equal(p.ok, true, p.text)
    // 提议还没落卡。
    assert.equal((await t.mount({ action: 'status' })).body.mount.givens.length, 0)
    assert.equal((await t.mount({ action: 'status' })).body.mount.pending.text, '服务端已经有版本号了')
    const acc = await t.mount({ action: 'accept' })
    assert.equal(acc.body.ok, true, JSON.stringify(acc.body))
    assert.equal(acc.body.mount.givens.length, 1)
    assert.equal(acc.body.mount.givens[0].src, String(seq), '她提议的那条要带着账本行号')
    assert.equal(acc.body.mount.pending, null, '接受之后提议要清掉')
    // 脚手架比例：这条是她提议才挂上的。
    assert.equal(acc.body.mount.scaffold.her, 1)
    assert.equal(acc.body.mount.scaffold.user, 0)
  })
})

test('R11 断链检测：连续两轮对不上才亮卡，中间不漂就清零', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把长连接的增量同步做出来' })
    await t.mount({ action: 'given', text: '服务端已经有版本号了' })
    const onTopic = await t.mount({ action: 'seen', text: '那我按版本号算差量，增量同步就能跑' })
    assert.equal(onTopic.body.drift.drift, false)
    assert.equal(onTopic.body.drift.ask, false)
    // 第一轮漂：还不够。
    const first = await t.mount({ action: 'seen', text: '今天中午吃什么' })
    assert.equal(first.body.drift.drift, true)
    assert.equal(first.body.drift.ask, false)
    // 第二轮接着漂：亮卡。
    const second = await t.mount({ action: 'seen', text: '我想打会儿游戏' })
    assert.equal(second.body.drift.ask, true)
    assert.equal(second.body.mount.ask, true)
    // 亮过一次就清零，不每轮念。
    assert.equal(second.body.drift.streak, 0)
  })
})

test('R12 断链检测在没卡时不乱亮', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.mount({ action: 'seen', text: '今天中午吃什么' })
    assert.equal(r.body.ok, true)
    assert.equal(r.body.drift.ask, false)
    assert.equal(r.body.mount.ask, false)
  })
})

test('R13 open 卡进状态层注入：带 [挂载] 标记、≤300 字、总长仍 ≤2400', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把长连接的增量同步做出来，别每次全量对比' })
    await t.mount({ action: 'given', text: '服务端已经有版本号了' })
    await t.mount({ action: 'step', text: '按版本号算差量就行', from: [0] })
    const text = t.sis.contexts[0].text()
    assert.match(text, /\[挂载\] 目标：把长连接的增量同步做出来/)
    assert.match(text, /已知：①服务端已经有版本号了/)
    assert.match(text, /推到：按版本号算差量就行/)
    assert.match(text, /上次动：\d{2}:\d{2}/)
    const line = text.split('\n').find((l) => l.startsWith('[挂载]'))
    assert.ok(line.length <= 300, '[挂载] 那一行不能超过 300 字符，实际 ' + line.length)
    assert.ok(text.length <= 2400, '整段注入不能超过 2400，实际 ' + text.length)
  })
})

test('R14 关掉之后不再进注入', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把增量同步做出来' })
    assert.match(t.sis.contexts[0].text(), /\[挂载\]/)
    await t.mount({ action: 'close' })
    assert.equal(/\[挂载\]/.test(t.sis.contexts[0].text()), false)
  })
})

test('R15 mount_park / mount_close 工具改的是同一张卡', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.mount({ action: 'open', goal: '把增量同步做出来' })
    const parked = t.sis.tools.mount_park.execute({ where: '版本号还没拿到' })
    assert.equal(parked.ok, true, parked.text)
    assert.equal((await t.mount({ action: 'status' })).body.mount.status, 'parked')
    const closed = t.sis.tools.mount_close.execute({})
    assert.equal(closed.ok, true, closed.text)
    assert.equal((await t.mount({ action: 'status' })).body.mount.status, 'closed')
    // 卡关了：再关一次说不通。
    assert.equal(t.sis.tools.mount_close.execute({}).ok, false)
  })
})

test('R16 三个工具进了状态表，任一状态 ≤8 个', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    for (const name of ['mount_propose', 'mount_park', 'mount_close']) {
      assert.ok(t.sis.tools[name], name + ' 要挂在会话上')
      assert.ok(t.big.tools[name], name + ' 两姐妹都要有')
    }
    const { STATE_TABLE, MAX_TOOLS_PER_STATE, toolsFor, ALL_TOOLS } = await import('../lib/machine.js')
    for (const row of STATE_TABLE) assert.ok(row.tools.length <= MAX_TOOLS_PER_STATE, row.id + ' 超了')
    assert.ok(toolsFor('standby').includes('mount_propose'))
    assert.ok(toolsFor('sprint').includes('mount_propose'))
    assert.ok(ALL_TOOLS.includes('mount_park') && ALL_TOOLS.includes('mount_close'))
  })
})

test('R17 /status 的 tracks 没配时兜底成 memory-leak 支线', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = await t.status()
    assert.equal(r.body.tracks.count, 1)
    assert.equal(r.body.tracks.tracks[0].id, 'memory-leak')
    assert.equal(r.body.tracks.tracks[0].blind, '工作记忆漏水')
    assert.equal(r.body.tracks.slots.length, 4)
  })
})

test('R18 显式把这条支线关掉（enabled:false）时不再硬塞兜底', async () => {
  await withHome(async (home) => {
    const t = setup(home, { tracks: [{ id: 'memory-leak', scene: 'a', first: 'b', missed: 'c', blind: 'd', enabled: false }] })
    const r = await t.status()
    assert.equal(r.body.tracks.count, 0)
    assert.equal(r.body.tracks.dropped[0].reason, 'disabled')
  })
})

test('R19 配了自己的支线就以配置为准，兜底不重复塞', async () => {
  await withHome(async (home) => {
    const t = setup(home, { tracks: [{ id: 'own', scene: 'a', first: 'b', missed: 'c', blind: 'd' }] })
    const r = await t.status()
    assert.equal(r.body.tracks.count, 1)
    assert.equal(r.body.tracks.tracks[0].id, 'own')
  })
})
