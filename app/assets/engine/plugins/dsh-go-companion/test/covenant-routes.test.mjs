// 公约接线：/covenant 首次写草稿、他改严立即生效、改松等 3 天、鱼的提议批了才写进文件、进上下文。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const TZ = 'Asia/Shanghai'

function agentOf(id) {
  const tools = {}
  const a = {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts: [], tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context(def) { this.contexts.push(def); return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
  a.ctx.systemPrompt.context = (def) => { a.contexts.push(def); return () => {} }
  return a
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
  const home = mkdtempSync(join(tmpdir(), 'companion-covenant-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, timeZone: TZ, quietStart: 12, quietEnd: 12 }))
    const agents = { [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) }
    const h = host(agents)
    const cov = (body) => call(h(), 'POST', '/covenant', body).then((r) => r.body)
    return await fn({ home, cov, h, agents })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}

const json = (home, name) => JSON.parse(readFileSync(join(home, 'companion', name), 'utf8'))

test('第一次：写草稿进 公约.md，生效版就是草稿', async () => {
  await withHome(async ({ home, cov }) => {
    const r = await cov({ action: 'list' })
    assert.equal(r.ok, true)
    assert.ok(r.rules.length >= 20)
    assert.ok(r.rules.some((x) => x.id === '1.2' && x.hardness === '铁律'))
    const text = readFileSync(r.path, 'utf8')
    assert.match(text, /# 大肥鱼公约/)
    assert.match(text, /### 1\.1 急事永远放行/)
  })
})

test('他在手机上加一条、改默认的字：立即；删铁律：等 3 天', async () => {
  await withHome(async ({ cov }) => {
    await cov({ action: 'list' })
    const add = await cov({ action: 'save', rule: { chapter: 2, title: '他说明天再做', do: '问他今天能做的最小一步是什么', who: '都', hardness: '默认', scenes: ['待命'] } })
    assert.equal(add.ok, true)
    assert.ok(add.rules.some((x) => x.id === add.ruleId && x.title === '他说明天再做'))
    const r27 = add.rules.find((x) => x.id === '2.7')
    const edit = await cov({ action: 'save', rule: { ...r27, do: '一句话：几点睡、明天第一件事。' } })
    assert.equal(edit.rules.find((x) => x.id === '2.7').do, '一句话：几点睡、明天第一件事。')
    assert.equal(edit.waiting, false)
    const del = await cov({ action: 'remove', id: '1.2' })
    assert.equal(del.waiting, true)
    assert.ok(del.rules.some((x) => x.id === '1.2'), '铁律删了也还在生效，等 3 天')
    assert.ok(del.pending['1.2'])
  })
})

test('鱼提议：工具只能提，批了才写进文件并生效', async () => {
  await withHome(async ({ home, cov, h }) => {
    await cov({ action: 'list' })
    // 模拟鲸澜调用工具：直接走 /covenant 看不到工具，这里用 covenant.json 塞一条提议（工具的行为在 covenant.test 里钉）
    const st = JSON.parse(readFileSync(join(home, 'companion', 'covenant.json'), 'utf8'))
    st.proposals = [{ id: 'cp-1', by: 'strategist', at: Date.now(), status: 'open', why: '连着三晚同一个借口',
      rule: { chapter: 2, title: '他找同一个借口', do: '把借口原话念给他，问今天的最小一步', who: '鲸澜', hardness: '默认', scenes: ['深夜'] } }]
    writeFileSync(join(home, 'companion', 'covenant.json'), JSON.stringify(st))
    const l = await cov({ action: 'list' })
    assert.equal(l.proposals.length, 1)
    assert.ok(!l.rules.some((x) => x.title === '他找同一个借口'), '没批不生效')
    const ok = await cov({ action: 'approve', id: 'cp-1' })
    assert.equal(ok.ok, true)
    assert.ok(ok.rules.some((x) => x.id === ok.ruleId && x.title === '他找同一个借口'))
    assert.match(readFileSync(ok.path, 'utf8'), /他找同一个借口/)
    assert.equal(ok.proposals.length, 0)
    const again = await cov({ action: 'approve', id: 'cp-1' })
    assert.equal(again.ok, false)
  })
})

test('进上下文：铁律在；鲸澜有 propose_covenant，提了等批', async () => {
  await withHome(async ({ cov, agents }) => {
    await cov({ action: 'list' })
    const big = agents[BIG]
    assert.ok(big.contexts.length, '人设注入挂上了')
    const text = big.contexts.map((c) => (typeof c.text === 'function' ? c.text() : c.text)).join('\n')
    assert.match(text, /【公约】/)
    assert.match(text, /1\.2 封印只能加码/)
    const tool = big.tools.propose_covenant
    assert.ok(tool, '有提议工具')
    const r = tool.execute({ chapter: 2, title: '他说下周再说', do: '问今天能做的一步', hardness: '默认', why: '这周说了四次' })
    assert.equal(r.ok, true)
    const l = await cov({ action: 'list' })
    assert.equal(l.proposals.length, 1)
    assert.equal(l.proposals[0].by, 'strategist')
    assert.ok(!big.tools.loosen_covenant && !Object.keys(big.tools).some((k) => /covenant/.test(k) && k !== 'propose_covenant'), '没有改 / 删公约的工具')
  })
})
