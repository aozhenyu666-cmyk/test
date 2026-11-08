// P5 节奏与素材：手机现状进发球、阶梯真的排上闹钟、教练模式高频、回合里接着打不占额度、两张人格卡合一。
//
// 时间全部注入（账本里的 at 自己写），不等真实 5 分钟。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply } from '../lib/index.js'
import { cleanPhone, phoneLines, worthObserving } from '../lib/phone.js'
import { buildPrompt } from '../lib/prompt.js'
import { plan } from '../lib/plan.js'
import { capCount } from '../lib/rally.js'
import { mergePersona, modeLimits, personaLines } from '../lib/persona.js'
import { readCorePersona } from '../lib/context.js'

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

function seed(home, config = {}) {
  const memDir = join(home, 'fish-memory')
  mkdirSync(memDir, { recursive: true })
  const cfgDir = join(home, 'companion')
  mkdirSync(cfgDir, { recursive: true })
  writeFileSync(
    join(cfgDir, 'config.json'),
    JSON.stringify({ sessionId: 'session-abc', quietStart: 12, quietEnd: 12, minGapMs: 0, ...config }),
  )
  const db = new DatabaseSync(join(memDir, 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.prepare('INSERT INTO topics (id, scope, title, detail, status, priority, opened_at, next_ask_at) VALUES (?,?,?,?,?,?,?,?)')
    .run('t1', 'session:session-abc', '面试结果', '她说周五出结果', 'open', 2, Date.now() - 86400000, 0)
  db.close()
}

/** 只写一条"已经发出去但没人回"的账本记录，时间放在过去。 */
function seedSilentServe(home, { requestId, agoMs, serveType }) {
  writeFileSync(
    join(home, 'companion', 'ledger.jsonl'),
    JSON.stringify({
      v: 1,
      requestId,
      sessionId: 'session-abc',
      state: 'generated',
      at: Date.now() - agoMs,
      serveType,
      text: '我猜你在躲申论',
      rallyId: 'rally-old',
      shot: 1,
    }) + '\n',
  )
}

function makeAgent() {
  const sent = []
  return {
    id: 'session-abc',
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    sent,
    followup(msg) {
      sent.push(msg)
    },
  }
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-p2-'))
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

function ledgerOf(home) {
  return readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l))
}

const BILI = {
  app: 'tv.danmaku.bili',
  label: '哔哩哔哩',
  minutes: 23,
  fun: true,
  screenOn: true,
  charging: false,
  local: '21:40',
  today: [
    { app: 'tv.danmaku.bili', label: '哔哩哔哩', minutes: 52, fun: true },
    { app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: 20, fun: true },
    { app: 'com.tencent.mm', label: '微信', minutes: 30, fun: false },
  ],
}

// ---------------------------------------------------------------- 1. 手机现状

test('手机现状：洗掉不认识的字段，算出今日娱乐累计', () => {
  const p = cleanPhone({ ...BILI, app: 'bad pkg!', today: [...BILI.today, { app: 'x', minutes: -3 }] })
  assert.equal(p.app, '', '包名不合法就当不知道')
  assert.equal(p.today.length, 3)
  assert.equal(cleanPhone(null), null)
  assert.equal(cleanPhone({}), null, '什么都没有就是不知道，不是"没在用手机"')
})

test('手机现状：开着娱乐 App 十分钟以上，或者今天刷满一小时，才值得打观察球', () => {
  assert.equal(worthObserving(cleanPhone(BILI)), true)
  assert.equal(worthObserving(cleanPhone({ app: 'tv.danmaku.bili', minutes: 3 })), false)
  assert.equal(worthObserving(cleanPhone({ app: 'com.tencent.mm', minutes: 40 })), false, '微信不算娱乐')
  assert.equal(
    worthObserving(cleanPhone({ app: 'com.tencent.mm', minutes: 1, today: [{ app: 'com.xingin.xhs', minutes: 70 }] })),
    true,
  )
})

test('手机现状进 prompt：只写事实，不写包名，不让它说"我看到你的手机"', () => {
  const prompt = buildPrompt({ serveType: 'observe', phone: cleanPhone(BILI) })
  assert.match(prompt, /现在开着：哔哩哔哩，已经开了 23 分钟/)
  assert.match(prompt, /今天娱乐类累计 72 分钟/)
  assert.match(prompt, /21:40/)
  assert.doesNotMatch(prompt, /tv\.danmaku/)
  assert.doesNotMatch(prompt, /娱乐类累计[^\n]*微信/, '非娱乐类不进累计')
  assert.match(prompt, /今天手机用得最多：.*微信 30 分钟/, '整体用在哪另起一行（巡逻要看）')
  assert.equal(phoneLines(null).length, 0, '没带就一个字都不写')
})

test('/request 带上手机现状：这一球打观察，prompt 里有"B站 23 分钟"', async () => {
  await withHome(async (home) => {
    seed(home)
    const agent = makeAgent()
    const host = makeHost({ agent })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-phone-1',
      eventId: 'evt-phone-1',
      kind: 'random_chat',
      payload: {},
      phone: BILI,
      deadlineMs: 30000,
    })
    assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))
    const prompt = agent.sent[0].content[0].text
    assert.match(prompt, /哔哩哔哩，已经开了 23 分钟/)
    const accepted = ledgerOf(home).find((e) => e.requestId === 'req-phone-1' && e.state === 'accepted')
    // 在娱乐 App 里：要么打观察，要么直接出思维题（越陷越可能出题）。
    assert.ok(['observe', 'drill'].includes(accepted.serveType), accepted.serveType)
  })
})

test('/request 没带手机现状：不打观察球（没东西可观察只能编）', async () => {
  await withHome(async (home) => {
    seed(home, { mode: 'coach' })
    for (let i = 0; i < 5; i += 1) {
      const agent = makeAgent()
      const host = makeHost({ agent })
      const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
        v: 1,
        requestId: 'req-nophone-' + i,
        eventId: 'evt-nophone-' + i,
        kind: 'random_chat',
        payload: {},
        deadlineMs: 30000,
      })
      assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))
      const accepted = ledgerOf(home).find((e) => e.requestId === 'req-nophone-' + i && e.state === 'accepted')
      assert.notEqual(accepted.serveType, 'observe')
      writeFileSync(join(home, 'companion', 'ledger.jsonl'), '')
    }
  })
})

// ---------------------------------------------------------------- 2. 阶梯真的排上闹钟

test('/plan：刚发出去还没人回，下次检查定在 5 分钟那一级，不是 30 分钟冷却', async () => {
  await withHome(async (home) => {
    seed(home, { minGapMs: 30 * 60_000 })
    const sentAt = Date.now() - 60_000
    writeFileSync(
      join(home, 'companion', 'ledger.jsonl'),
      JSON.stringify({ v: 1, requestId: 'r1', sessionId: 'session-abc', state: 'generated', at: sentAt, serveType: 'provoke', rallyId: 'rally-a', shot: 1 }) + '\n',
    )
    const host = makeHost({ agent: makeAgent() })
    const out = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai' })
    assert.equal(out.body.speak, false)
    assert.equal(out.body.reason, 'ladder-wait', JSON.stringify(out.body))
    assert.equal(out.body.nextCheckAt, sentAt + 5 * 60_000)
  })
})

test('plan：掷骰没中时，教练模式 8 分钟后再看，不被写死的 30 分钟吃掉', () => {
  const now = 1_800_000_000_000
  const out = plan({
    config: { minGapMs: 8 * 60_000, diceThreshold: 0, quietStart: 12, quietEnd: 12 },
    now,
    topic: { id: 't' },
    sentToday: 0,
    lastAt: 0,
    roll: 50,
  })
  assert.equal(out.reason, 'dice')
  assert.equal(out.nextCheckAt, now + 8 * 60_000)
})

// ---------------------------------------------------------------- 3. 教练模式高频 + 回合内不占额度

test('教练模式自己一套频率：8 分钟 / 每小时 6 / 每天 40；伙伴按设置原样', () => {
  assert.deepEqual(modeLimits({}, 'coach'), { minGapMs: 8 * 60_000, hourlyMax: 6, dailyMax: 40 })
  assert.deepEqual(modeLimits({ coachHourlyMax: 10 }, 'coach').hourlyMax, 10)
  const partner = modeLimits({ minGapMs: 30 * 60_000, hourlyMax: 3, dailyMax: 6 }, 'partner')
  assert.deepEqual(partner, { minGapMs: 30 * 60_000, hourlyMax: 3, dailyMax: 6 }, '设多少就是多少')
})

test('上限只数新开的球；他接住之后接着打的、阶梯补的都不算', () => {
  const t = 1_800_000_000_000
  const entries = [
    { state: 'generated', requestId: 'a', rallyId: 'R', at: t },
    { state: 'replied', requestId: 'a', rallyId: 'R', at: t + 1000 },
    { state: 'generated', requestId: 'b', rallyId: 'R', at: t + 2000 },
    { state: 'replied', requestId: 'b', rallyId: 'R', at: t + 3000 },
    { state: 'generated', requestId: 'c', rallyId: 'R', at: t + 4000 },
    { state: 'generated', requestId: 'd', rallyId: 'R', at: t + 5000 },
  ]
  // a 新开 → 算；b、c 是接住后接着打 → 不算；d 跟在没接的 c 后面 → 算。
  assert.equal(capCount(entries, 0), 2)
  assert.equal(capCount(entries, t + 4500), 1)
  // 阶梯补的球（lighter / channel）不算：一串没接的球只算开头那个。
  const ladder = [
    { state: 'generated', requestId: 'x', rallyId: 'L', at: t },
    { state: 'generated', requestId: 'y', rallyId: 'L', at: t + 5 * 60_000, silence: 'lighter' },
    { state: 'generated', requestId: 'z', rallyId: 'L', at: t + 15 * 60_000, silence: 'channel' },
  ]
  assert.equal(capCount(ladder, 0), 1)
})

test('/plan 在教练模式下：一小时已经打了 4 球也照样能开口（伙伴模式会被每小时 3 次挡住）', async () => {
  await withHome(async (home) => {
    const now = Date.now()
    const rows = []
    for (let i = 0; i < 4; i += 1) {
      rows.push({ v: 1, requestId: 'h' + i, sessionId: 'session-abc', state: 'generated', at: now - (50 - i * 10) * 60_000, rallyId: 'rally-' + i, shot: 1 })
      rows.push({ v: 1, requestId: 'h' + i, sessionId: 'session-abc', state: 'replied', at: now - (49 - i * 10) * 60_000, rallyId: 'rally-' + i, verdict: 'engaged' })
    }
    for (const mode of ['partner', 'coach']) {
      seed(home, { mode, minGapMs: 0, diceThreshold: 100, hourlyMax: 3, dailyMax: 6 })
      writeFileSync(join(home, 'companion', 'ledger.jsonl'), rows.map((r) => JSON.stringify(r)).join('\n') + '\n')
      const host = makeHost({ agent: makeAgent() })
      const out = await call(host.handler, 'POST', '/dsh-go-companion/v1/plan', { v: 1, timeZone: 'Asia/Shanghai', roll: 0 })
      if (mode === 'partner') assert.equal(out.body.reason, 'hourly_max', JSON.stringify(out.body))
      else assert.equal(out.body.speak, true, JSON.stringify(out.body))
      rmSync(join(home, 'fish-memory'), { recursive: true, force: true })
    }
  })
})

// ---------------------------------------------------------------- 4. 两张人格卡合一

function seedCorePersona(home) {
  mkdirSync(join(home, 'relationship'), { recursive: true })
  const db = new DatabaseSync(join(home, 'relationship', 'relationship.db'))
  db.exec(`CREATE TABLE persona (id INTEGER PRIMARY KEY CHECK (id = 1), name TEXT NOT NULL, values_json TEXT NOT NULL,
    non_negotiables_json TEXT NOT NULL, voice_json TEXT NOT NULL, own_interests_json TEXT NOT NULL,
    boundaries_json TEXT NOT NULL, version INTEGER NOT NULL, updated_by TEXT NOT NULL, updated_at TEXT NOT NULL)`)
  db.prepare('INSERT INTO persona VALUES (1,?,?,?,?,?,?,1,?,?)').run(
    '大肥鱼',
    JSON.stringify(['不为了让你舒服而改变判断', '说过的话要有下文']),
    JSON.stringify(['不撒谎', '不假装记得']),
    JSON.stringify({ tone: '直接、稳、不哄', address: '你' }),
    JSON.stringify(['夜里读一点书']),
    JSON.stringify(['不在你明确说停后继续']),
    'system',
    '2026-11-04T14:58:47.796Z',
  )
  db.close()
}

test('关系库的人物卡当底子，配置里写了的字段覆盖它', async () => {
  await withHome(async (home) => {
    seedCorePersona(home)
    const core = readCorePersona(home)
    assert.equal(core.voice, '直接、稳、不哄')
    assert.equal(core.rules, '不撒谎；不假装记得')
    const merged = mergePersona(core, { voice: '损一点' })
    assert.equal(merged.voice, '损一点', '配置覆盖')
    assert.equal(merged.values, '不为了让你舒服而改变判断；说过的话要有下文', '没写的沿用关系库')
    const lines = personaLines(merged, 'partner').join('\n')
    assert.match(lines, /你的底线（绝不做）：不撒谎/)
    assert.match(lines, /你的边界：不在你明确说停后继续/)
  })
})

test('/request：companion 配置里人格卡是空的，prompt 里照样有关系库的价值观和底线', async () => {
  await withHome(async (home) => {
    seed(home, { dual: false })
    seedCorePersona(home)
    const agent = makeAgent()
    const host = makeHost({ agent })
    const res = await call(host.handler, 'POST', '/dsh-go-companion/v1/request', {
      v: 1,
      requestId: 'req-persona-1',
      eventId: 'evt-persona-1',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
    })
    assert.equal(res.body?.state, 'accepted', JSON.stringify(res.body))
    const prompt = agent.sent[0].content[0].text
    assert.match(prompt, /你在意的：不为了让你舒服而改变判断/)
    assert.match(prompt, /你的底线（绝不做）：不撒谎/)
    assert.match(prompt, /你的口吻：直接、稳、不哄/)
  })
})
