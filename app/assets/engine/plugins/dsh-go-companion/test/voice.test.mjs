// R7 B5 语音：念不念、用什么调子、念没念出来记不记得住。
//
// 这里断三件事：
//   1. voiceOf 的三道闸（总开关 / 安静时段 / 渠道）各一条，外加阶梯换渠道那一级真的能念；
//   2. 语速和音高各两条边界（60/59、25/26），以及 60 字的封顶不涨也不缩；
//   3. 手机回报的 voice-out 真的进了账本，而且不会被 silence / emotion 当成"他回过话了"。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'
import { VOICE_MAX_CHARS, emptyEmotion, emotionOf, voiceOf } from '../lib/emotion.js'
import { silenceState } from '../lib/silence.js'

const NOW = Date.UTC(2026, 4, 6, 6, 0, 0)

/** 四根轴的日常值：耐心 70 / 在意 60 / 火气 15 / 兴致 50。 */
function mood(patch = {}) {
  return { ...emptyEmotion(NOW), ...patch }
}

// ---- 三道闸 -------------------------------------------------------------------------------

test('V1 总开关关着就不念：哪怕档位要念、安静时段也开着', () => {
  const out = voiceOf(mood({ anger: 80, patience: 20 }), { now: NOW, enabled: false, channel: 'voice' })
  assert.equal(out, null)
})

test('V2 安静时段一条都不念（和 burst=0 同理）', () => {
  const out = voiceOf(mood({ anger: 80, patience: 20 }), { now: NOW, enabled: true, quiet: true, channel: 'voice' })
  assert.equal(out, null)
})

test('V3 渠道是文字、阶梯也没走到换渠道：不念', () => {
  const out = voiceOf(mood(), { now: NOW, enabled: true, channel: 'text', stage: 'none' })
  assert.equal(out, null)
})

test('V4 档位要念（channel=voice）：念，且默认不打调速速/音高', () => {
  const out = voiceOf(mood(), { now: NOW, enabled: true, channel: 'voice', stage: 'none' })
  assert.deepEqual(out, { rate: 1, pitch: 1, maxChars: 60 })
})

test('V5 阶梯走到 channel 那一级：档位是文字也照样念（它等了四轮）', () => {
  const out = voiceOf(mood(), { now: NOW, enabled: true, channel: 'text', stage: 'channel' })
  assert.ok(out, 'stage=channel 必须能念')
  assert.equal(out.rate, 1)
})

test('V6 阶梯的其它级别（lighter/stop/none）不算换渠道', () => {
  for (const stage of ['none', 'wait', 'lighter', 'stop']) {
    assert.equal(voiceOf(mood(), { now: NOW, enabled: true, channel: 'text', stage }), null, stage)
  }
})

// ---- 语速 / 音高 ---------------------------------------------------------------------------

test('V7 火气到 60 语速 +15%，59 不动', () => {
  assert.equal(voiceOf(mood({ anger: 60 }), { now: NOW, enabled: true, channel: 'voice' }).rate, 1.15)
  assert.equal(voiceOf(mood({ anger: 59 }), { now: NOW, enabled: true, channel: 'voice' }).rate, 1)
})

test('V8 兴致到 25 音高 −10%，26 不动', () => {
  assert.equal(voiceOf(mood({ interest: 25 }), { now: NOW, enabled: true, channel: 'voice' }).pitch, 0.9)
  assert.equal(voiceOf(mood({ interest: 26 }), { now: NOW, enabled: true, channel: 'voice' }).pitch, 1)
})

test('V9 两个旋钮互不影响：火气高不加音高，兴致低不加语速', () => {
  const hot = voiceOf(mood({ anger: 90, interest: 80 }), { now: NOW, enabled: true, channel: 'voice' })
  assert.equal(hot.rate, 1.15)
  assert.equal(hot.pitch, 1)
  const flat = voiceOf(mood({ anger: 10, interest: 10 }), { now: NOW, enabled: true, channel: 'voice' })
  assert.equal(flat.rate, 1)
  assert.equal(flat.pitch, 0.9)
})

// ---- 单条上限 -----------------------------------------------------------------------------

test('V10 单条封顶 60 字：eager 档放着 90 字的文字也不涨到 90', () => {
  assert.equal(VOICE_MAX_CHARS, 60)
  // eager = 兴致高且不生气，档位表给 90 字
  const eager = voiceOf(mood({ anger: 10, interest: 80 }), { now: NOW, enabled: true, channel: 'voice' })
  assert.equal(eager.maxChars, 60, '90 字的档位要钳到 60')
})

test('V11 比 60 更严的档位照它的来：blaze 档只给 40', () => {
  const blaze = voiceOf(mood({ anger: 80, patience: 20 }), { now: NOW, enabled: true, channel: 'voice' })
  assert.equal(blaze.maxChars, 40)
})

test('V12 返不返回都是新对象，不改调用方那份情绪', () => {
  const raw = mood({ anger: 80 })
  const before = JSON.stringify(raw)
  voiceOf(raw, { now: NOW, enabled: true, channel: 'voice' })
  assert.equal(JSON.stringify(raw), before)
})

// ---- 接线：index.js 真把阶梯的 stage 交给了 voiceOf -----------------------------------------

test('V13 compose 里 voiceOf 拿到的是漠视阶梯那一刻的 stage（不是写死的 none）', () => {
  const src = readFileSync(new URL('../lib/index.js', import.meta.url), 'utf8')
  assert.match(src, /voiceOf\(emotion, \{/, '要按这一轮的情绪算')
  assert.match(src, /stage: silence\.stage/, '阶梯的级别要传进去，否则换渠道那一级永远接不上')
  assert.match(src, /enabled: config\.voice === true/, '总开关要认配置里的布尔')
})

// ---- 手机回报 voice-out --------------------------------------------------------------------

function makeHost({ agent }) {
  const listeners = new Map()
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: () => agent, roots: () => [agent], list: () => [agent] }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async () => ({ agent }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(name, fn) {
      if (!listeners.has(name)) listeners.set(name, [])
      listeners.get(name).push(fn)
    },
    inject(_deps, cb) {
      cb({ webServer: { register(route) { handler = route.handler } }, effect(fn) { fn() } })
    },
    effect(fn) {
      fn()
    },
  }
  apply(ctx)
  return { handler, listeners }
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0,
    text: '',
    writeHead(status) {
      this.statusCode = status
    },
    end(chunk) {
      this.text = chunk ? String(chunk) : ''
    },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

function makeAgent() {
  return {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    followup() {},
  }
}

function seed(home) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(join(cfgDir, 'config.json'), JSON.stringify({
    sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0,
  }))
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.close()
}

function ledgerOf(home) {
  try {
    return readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l))
  } catch {
    return []
  }
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-voice-'))
  const oldHome = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (oldHome === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = oldHome
    rmSync(home, { recursive: true, force: true })
  }
}

test('V14 念出来了：账本记一条 voice-out，ok 为 true、没有降级原因', async () => {
  await withHome(async (home) => {
    seed(home)
    const host = makeHost({ agent: makeAgent() })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/event', {
      v: 1, type: 'voice-out', requestId: 'req-voice-1', ok: true, chars: 18, rate: 1.15, pitch: 1,
    })
    assert.equal(res.status, 200)
    assert.equal(res.body.ok, true)
    const entry = ledgerOf(home).find((e) => e.state === 'voice-out')
    assert.ok(entry, '账本里要有 voice-out')
    assert.equal(entry.requestId, 'req-voice-1')
    assert.equal(entry.ok, true)
    assert.equal(entry.why, undefined, '念出来了就不该有降级原因')
    assert.equal(entry.chars, 18)
    assert.equal(entry.rate, 1.15)
  })
})

test('V15 没念出来：降级原因照记，缺省是 unknown', async () => {
  await withHome(async (home) => {
    seed(home)
    const host = makeHost({ agent: makeAgent() })
    await call(host.handler, 'POST', '/dsh-go-companion/v1/event', {
      v: 1, type: 'voice-out', requestId: 'req-voice-2', ok: false, why: 'dnd',
    })
    await call(host.handler, 'POST', '/dsh-go-companion/v1/event', {
      v: 1, type: 'voice-out', requestId: 'req-voice-3', ok: false,
    })
    const all = ledgerOf(home).filter((e) => e.state === 'voice-out')
    assert.equal(all.length, 2)
    assert.equal(all[0].why, 'dnd')
    assert.equal(all[1].why, 'unknown', '手机没说原因也得有个说法')
  })
})

test('V16 voice-out 不算"他回过话了"：阶梯还在原地，情绪也不动', async () => {
  await withHome(async (home) => {
    seed(home)
    // 6 分钟前发了一球没人接：阶梯该在 lighter
    const serve = {
      v: 1, requestId: 'old-1', sessionId: 'session-abc', state: 'generated',
      at: Date.now() - 6 * 60_000, serveType: 'provoke', text: '我猜你在躲申论',
    }
    writeFileSync(join(home, 'companion', 'ledger.jsonl'), JSON.stringify(serve) + '\n')
    const before = silenceState(ledgerOf(home), { sessionId: 'session-abc', now: Date.now() })
    assert.equal(before.stage, 'lighter')
    const angerBefore = emotionOf(ledgerOf(home), { now: Date.now() }).anger

    const host = makeHost({ agent: makeAgent() })
    await call(host.handler, 'POST', '/dsh-go-companion/v1/event', {
      v: 1, type: 'voice-out', requestId: 'old-1', ok: true, chars: 12,
    })

    const after = silenceState(ledgerOf(home), { sessionId: 'session-abc', now: Date.now() })
    assert.equal(after.stage, 'lighter', '念出来不等于他接了球')
    assert.equal(after.noTake, before.noTake)
    assert.equal(emotionOf(ledgerOf(home), { now: Date.now() }).anger, angerBefore)
  })
})
