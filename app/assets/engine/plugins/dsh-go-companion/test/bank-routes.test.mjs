// 题库导入接线：大题库分块传（引擎收请求上限 16 KB），收齐了拼起来解析；小的一次传；报告没进的题。

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
  const home = mkdtempSync(join(tmpdir(), 'companion-bank-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, timeZone: TZ, quietStart: 12, quietEnd: 12 }))
    const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    const bank = (body) => call(h(), 'POST', '/bank', body)
    return await fn({ home, bank, h })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}


const BIG_BANK = Array.from({ length: 200 }, (_, i) => `${i + 1}. 第 ${i + 1} 题：下列关于宏观经济政策的说法哪一项是正确的？请仔细阅读每个选项。\nA. 甲选项内容比较长\nB. 乙选项内容比较长\nC. 丙选项内容比较长\nD. 丁选项内容比较长`).join('\n') +
  '\n\n参考答案\n' + Array.from({ length: 40 }, (_, k) => `${k * 5 + 1}-${k * 5 + 5} ABCDA`).join('\n')

test('大题库：一次传超过 16 KB 被拒；分块传收齐了拼起来，200 题全进', async () => {
  await withHome(async ({ bank }) => {
    assert.ok(Buffer.byteLength(BIG_BANK) > 16 * 1024, '夹具要大于 16 KB')
    const once = await bank({ action: 'import', raw: BIG_BANK, name: '大' })
    assert.equal(once.status, 413, '一次传会被引擎拒')
    const begin = (await bank({ action: 'import-begin', uploadId: 'up1' })).body
    assert.equal(begin.ok, true)
    const CH = 3000
    let n = 0
    for (let i = 0; i < BIG_BANK.length; i += CH) {
      const r = await bank({ action: 'import-chunk', uploadId: 'up1', index: n++, text: BIG_BANK.slice(i, i + CH) })
      assert.equal(r.status, 200)
      assert.equal(r.body.ok, true)
    }
    const fin = (await bank({ action: 'import-finish', uploadId: 'up1', name: '行测真题' })).body
    assert.equal(fin.ok, true, JSON.stringify(fin).slice(0, 300))
    assert.equal(fin.importedCount, 200)
    assert.equal(fin.bankTotal, 200)
    assert.equal(fin.format, 'text+key')
    const st = (await bank({ action: 'status' })).body
    assert.equal(st.bankTotal, 200)
    assert.equal(st.bankName, '行测真题')
    // 再追加一份小的：编号接着排，总数相加
    const more = (await bank({ action: 'import', raw: '1. 追加题\nA. 甲\nB. 乙\n答案：A', append: true })).body
    assert.equal(more.bankTotal, 201)
    // 收完临时目录被清掉；重复 finish 说清楚
    const again = (await bank({ action: 'import-finish', uploadId: 'up1' })).body
    assert.equal(again.ok, false)
  })
})

test('没进的题逐题报出来', async () => {
  await withHome(async ({ bank }) => {
    const r = (await bank({ action: 'import', raw: '1. 有答案\nA. 甲\nB. 乙\n答案：A\n2. 没答案\nA. 甲\nB. 乙' })).body
    assert.equal(r.ok, true)
    assert.equal(r.importedCount, 1)
    assert.equal(r.skippedCount, 1)
    assert.match(r.warnings[0], /第 2 题缺答案/)
  })
})
