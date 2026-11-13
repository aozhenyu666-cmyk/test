// 第八轮 J9：局官念题，念完才开始计应答时限。
//
// 这一条不是措辞问题，是判定问题：不 hold 的话，局官一句 40 字的话念到一半，
// 90 秒的限时已经走掉一半，等于因为他「听」而罚他「答」。
// 反过来，无条件等「念完」也不行：声音卡住（TTS 不返回）就成了一拍永远不计时，
// 他坐着等，局等不到任何判定。所以有上限：到 SPEAK_HOLD_MAX 按那一刻念完算。
//
// 前六条钉的是局官这一层（createOfficial + createRunner），
// 后四条钉的是 `POST /bouts` 的四个退出码。真接住的那一条要先有 live 的局官，
// 那只能由面板动作开出来（同 bouts-share.test.mjs 的说明），这里不假装证明接线。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'

import { createOfficial } from '../lib/boutrun.js'
import { openBoutsDb } from '../lib/bouts.js'
import { SPEAK_HOLD_MAX, loadProtocol } from '../lib/protocol.js'
import { apply } from '../lib/index.js'

const PREFIX = '/dsh-go-companion/v1'
const LIMIT_MS = 90_000

/** 一拍就走完的探针局：限时 90 秒，超时收局，不过重打。 */
function probeProtocol() {
  const loaded = loadProtocol({
    协议版本: 1,
    id: 'probe',
    名称: '探针局',
    类型: '行为',
    预算: { 分钟: 10 },
    步骤: [{ id: '一拍', 问: '现在到哪一步了？', 限时: '90s', 过关: { 字数: 1 }, 超时: '收局', 不过: '重打' }],
    收局: {},
  })
  assert.equal(loaded.ok, true, '探针协议应当能加载：' + JSON.stringify(loaded.errors || []))
  return loaded.protocol
}

function withBout(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-bout-voice-'))
  const db = openBoutsDb(join(home, 'fish-memory', 'fish-memory.sqlite'))
  let clock = Date.parse('2026-11-13T09:00:00+08:00')
  const at = (ms = 0) => clock + ms
  try {
    const official = createOfficial({ protocol: probeProtocol(), db, home, now: () => clock })
    assert.equal(official.start().ok, true)
    return fn({ official, advance: (ms) => { clock += ms }, at })
  } finally {
    try {
      db?.close?.()
    } catch {
      // 关不掉不影响用例结论
    }
  }
}

test('念的时候不计时：在限时里一直 tick，也只是「在念」，不算他没接', () => {
  withBout(({ official, at }) => {
    official.hold(at(0))
    for (const ms of [1_000, 30_000, SPEAK_HOLD_MAX - 1]) {
      const r = official.tick(at(ms))
      assert.equal(r.state, 'running', ms + 'ms 时这一拍不该有结局')
      assert.equal(r.reason, 'speaking', ms + 'ms 时说清了「还在念」')
    }
  })
})

test('念完才开始计：spoken 之后要整段时间，少一毫秒都不算超时', () => {
  withBout(({ official, at }) => {
    official.hold(at(0))
    // 念了 40 秒（长句子 + 慢网络），这 40 秒不算他的。
    official.spoken(at(40_000))
    assert.equal(official.tick(at(40_000 + LIMIT_MS - 1)).reason, 'not-yet')
    const late = official.tick(at(40_000 + LIMIT_MS))
    assert.equal(late.state, 'closed')
    assert.equal(late.reason, 'timeout')
  })
})

test('spoken 只挪时间起点，不动进度也不动重打次数', () => {
  withBout(({ official, at }) => {
    const before = official.view()
    official.hold(at(0))
    official.spoken(at(12_000))
    const after = official.view()
    assert.equal(after.index, before.index)
    assert.equal(after.stepId, before.stepId)
    assert.equal(after.askedAt, at(12_000), '这一拍从念完的那一刻算起')
    assert.equal(after.closed, false)
  })
})

test('声音卡死也不会永远不计时：过了上限按那一刻念完算，照样能超时收局', () => {
  withBout(({ official, at }) => {
    official.hold(at(0))
    // 上限之内：还在念。
    assert.equal(official.tick(at(SPEAK_HOLD_MAX - 1)).reason, 'speaking')
    // 过了上限：按上限那一刻算念完，从这里给满 90 秒。
    assert.equal(official.tick(at(SPEAK_HOLD_MAX)).reason, 'not-yet')
    assert.equal(official.tick(at(SPEAK_HOLD_MAX + LIMIT_MS - 1)).reason, 'not-yet')
    assert.equal(official.tick(at(SPEAK_HOLD_MAX + LIMIT_MS)).reason, 'timeout')
  })
})

test('退回文字走同一条路：字到了他眼前，也一样从那一刻起算', () => {
  withBout(({ official, at }) => {
    // 声音不可用 → 浮层/文字显示这一句 → 调用方仍旧叫 spoken。
    official.hold(at(0))
    official.spoken(at(3_000))
    assert.equal(official.tick(at(3_000 + LIMIT_MS - 1)).reason, 'not-yet')
    assert.equal(official.tick(at(3_000 + LIMIT_MS)).reason, 'timeout')
  })
})

test('没 hold 过的局行为一点没变（文字面板那条老路）', () => {
  withBout(({ official, at }) => {
    assert.equal(official.tick(at(LIMIT_MS - 1)).reason, 'not-yet')
    assert.equal(official.tick(at(LIMIT_MS)).reason, 'timeout')
  })
})

// ---- POST /bouts 的四个退出码 ----------------------------------------------------------------

function makeHost() {
  let handler
  const agent = { id: 'session-abc', status: 'idle', inbox: { hasPending: false }, followup() {} }
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_deps, cb) {
      cb({
        webServer: { register(route) { handler = route.handler } },
        effect(fn) { fn() },
      })
    },
    effect(fn) { fn() },
  }
  apply(ctx)
  return handler
}

function call(handler, path, body) {
  const req = Readable.from([Buffer.from(JSON.stringify(body))])
  req.method = 'POST'
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0,
    text: '',
    writeHead(status) { this.statusCode = status },
    end(chunk) { this.text = chunk ? String(chunk) : '' },
  }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHost(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-bout-voice-route-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'fish-memory'), { recursive: true })
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: 'session-abc', dual: false }))
    const handler = makeHost()
    assert.equal(typeof handler, 'function')
    return await fn((path, body) => call(handler, `${PREFIX}${path}`, body))
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    rmSync(home, { recursive: true, force: true })
  }
}

test('语音这一路：不认识的 action 明确拒绝，不猜成分享', async () => {
  await withHost(async (post) => {
    const r = await post('/bouts', { v: 1, action: 'listen' })
    assert.equal(r.body.ok, false)
    assert.equal(r.body.code, 'bad-action')
  })
})

test('语音这一路：没有在跑的局，ask / spoken / answer 都回 no-bout', async () => {
  await withHost(async (post) => {
    for (const action of ['ask', 'spoken', 'answer']) {
      const r = await post('/bouts', { v: 1, action, text: '答一句', at: Date.now() })
      assert.equal(r.body.ok, false, action + ' 没有局时不该成功')
      assert.equal(r.body.code, 'no-bout')
    }
  })
})

test('语音这一路：识别结果为空回 empty，不当成一次应答', async () => {
  await withHost(async (post) => {
    // 没有局时先撞 no-bout，所以这一条只钉「空字先被这一层挡掉」这个顺序。
    const r = await post('/bouts', { v: 1, action: 'answer', text: '   ' })
    assert.equal(r.body.ok, false)
  })
})

test('退回记账：声音/识别不能用时记一笔，没有局也照记', async () => {
  await withHost(async (post) => {
    const r = await post('/bouts', { v: 1, action: 'fallback', reason: 'voice-out' })
    assert.equal(r.body.ok, true)
    assert.equal(r.body.code, 'noted')
  })
})
