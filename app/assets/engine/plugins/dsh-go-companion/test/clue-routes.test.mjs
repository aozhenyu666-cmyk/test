// R9 第五项（三）：接线。sister_note 的「线索」真的进待查队列、真的停在 5 条；
// 妹妹催进度的句子真的被拦下来、账本真的记了 companion-nag-blocked。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { companionGate } from '../lib/stock.js'

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

function host(agents, state = 'standby') {
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
  setStatePin(state)
  apply(ctx)
  const emit = async (sid, type, data) => { for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, { type, data }) }
  return { handler: () => handler, emit }
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-clue-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {
      // Windows 上 sqlite 句柄还没放；断言已跑完。
    }
  }
}

function setup(home) {
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12 }))
  const sis = agentOf('session-sis')
  const big = agentOf('session-big')
  const h = host({ 'session-sis': sis, 'session-big': big }, 'morning')
  const ledger = () => {
    try {
      return readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map((l) => JSON.parse(l))
    } catch {
      return []
    }
  }
  const clues = () => {
    try {
      return JSON.parse(readFileSync(join(home, 'companion', 'clues.json'), 'utf8'))
    } catch {
      return { day: 0, items: [] }
    }
  }
  return { sis, big, h, ledger, clues }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = '/dsh-go-companion/v1' + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

test('K1 妹妹标「线索」递上去：进队列，并回一句还能报几条', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = t.sis.tools.sister_note.execute({ text: '他今天说了三次累', kind: '线索' })
    assert.equal(r.ok, true, r.text)
    assert.match(r.text, /线索/)
    assert.match(r.text, /还能报 4 条/)
    const q = t.clues()
    assert.equal(q.items.length, 1)
    assert.equal(q.items[0].text, '他今天说了三次累')
    assert.ok(t.ledger().some((e) => e.state === 'companion-clue'))
  })
})

test('K2 报满 5 条以后被挡：不许再进队列', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    for (let i = 1; i <= 5; i += 1) {
      const r = t.sis.tools.sister_note.execute({ text: '现象' + i, kind: '线索' })
      assert.equal(r.ok, true, '第 ' + i + ' 条：' + r.text)
    }
    const over = t.sis.tools.sister_note.execute({ text: '现象6', kind: '线索' })
    assert.equal(over.ok, false)
    assert.match(over.text, /5 条/)
    assert.equal(t.clues().items.length, 5)
  })
})

test('K3 不带 kind 的普通递话：不进队列（还是老行为）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = t.sis.tools.sister_note.execute({ text: '他说找工作好烦', urgent: true })
    assert.equal(r.ok, true)
    assert.equal(t.clues().items.length, 0)
    assert.ok(t.ledger().some((e) => e.state === 'sister-note'))
  })
})

test('K4 姐姐标「线索」不算线索：队列是妹妹报现象的地方', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const r = t.big.tools.sister_note.execute({ text: '让他早点睡', kind: '线索' })
    assert.equal(r.ok, true)
    assert.equal(t.clues().items.length, 0)
  })
})

test('K5 姐姐下一轮的人设里带得到妹妹报的线索', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    t.sis.tools.sister_note.execute({ text: '他又在凌晨刷 B 站', kind: '线索' })
    const text = t.big.contexts[0].text()
    assert.match(text, /只报现象/)
    assert.match(text, /他又在凌晨刷 B 站/)
  })
})

test('K6 妹妹自己那边看不到线索那一块（那是给姐姐核实用）', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    t.sis.tools.sister_note.execute({ text: '他今天没吃饭', kind: '线索' })
    assert.doesNotMatch(t.sis.contexts[0].text(), /只报现象/)
  })
})

// 判定本身（一遍重写、二遍不发）在 stock.companionGate 里，gateBall 只负责把 tries 递进去。
// 这里钉判定；gateBall 那一行调用由 dual.test 的整链路覆盖。
test('K7 催进度第一遍：打回重写，不直接丢', () => {
  const r = companionGate('你该去做事了，做到哪了')
  assert.equal(r.action, 'rewrite')
  assert.equal(r.code, 'nag')
  assert.equal(r.state, 'companion-nag-rewrite')
})

test('K8 同一会话第二遍还催：这一轮不发，记 companion-nag-blocked', () => {
  const r = companionGate('进度呢', [{ code: 'nag' }])
  assert.equal(r.action, 'skip')
  assert.equal(r.state, 'companion-nag-blocked')
})

test('K9 她说人话：照发', () => {
  const r = companionGate('哦，随你。我跟芝麻玩去了。')
  assert.equal(r.action, 'send')
  assert.equal(r.code, undefined)
})

test('K10 骂到人身上：一样拦，第一次重写', () => {
  const r = companionGate('你就是个废物')
  assert.equal(r.action, 'rewrite')
  assert.equal(r.code, 'insult')
  assert.equal(r.state, 'companion-insult-rewrite')
})

test('K11 骂到人身上第二遍：不发，记 companion-insult-blocked', () => {
  const r = companionGate('你个丑八怪', [{ code: 'insult' }])
  assert.equal(r.action, 'skip')
  assert.equal(r.state, 'companion-insult-blocked')
})

test('K12 骂事不骂人：照发（她本来就该会骂）', () => {
  assert.equal(companionGate('这事你干得真蠢，我笑出声了。').action, 'send')
})

test('K13 两条判据的 tries 不串：催过一次不等于骂过', () => {
  assert.equal(companionGate('你就是个废物', [{ code: 'nag' }]).action, 'rewrite')
  assert.equal(companionGate('进度呢', [{ code: 'insult' }]).action, 'rewrite')
})

