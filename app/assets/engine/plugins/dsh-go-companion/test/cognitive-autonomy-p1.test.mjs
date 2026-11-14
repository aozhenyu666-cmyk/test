import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import {
  CORE_TOKEN_LIMIT,
  MAX_LOREBOOK_SLOTS,
  MAX_TELEMETRY_LINES,
  buildCoreMemoryBlocks,
  buildTelemetrySnapshot,
  circadianLabel,
} from '../lib/memory_blocks.js'
import {
  WITHDRAWAL_COOLDOWN_MS,
  checkWithdrawalRelease,
  emptyWithdrawalState,
  isInWithdrawal,
  recordReplyOutcome,
} from '../lib/withdrawal.js'
import {
  antiSycophancyRewriteHint,
  detectSycophancy,
  stripStrategistAudit,
} from '../lib/strategist_audit.js'
import {
  evaluateSixDiceSlot,
  extractObservationAnchors,
  parseL2Judge,
  slotsForStage,
} from '../lib/six_dice.js'
import {
  DEPARTMENTS,
  inferDepartment,
  routeBoutDepartment,
} from '../lib/departments.js'
import { listBouts, openBout, openBoutsDb } from '../lib/bouts.js'
import { renderFrontmatter, syncVerdicts, wikiLink, writeObsidianCard } from '../lib/notes.js'

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
  const home = mkdtempSync(join(tmpdir(), 'companion-p1-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    return await fn(home)
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try { rmSync(home, { recursive: true, force: true }) } catch {}
  }
}

test('M1.1: Core Memory 三块装配严格 ≤1800 tokens 且 Telemetry ≤5 行、Lorebook ≤3 槽', () => {
  const now = Date.UTC(2026, 10, 14, 15, 30, 0)
  const lines = buildTelemetrySnapshot({
    state: 'sprint',
    department: 'dept:govern',
    bout: { protocolId: 'video_slice_dice', department: 'dept:govern' },
    phone: { app: 'md.obsidian', label: 'Obsidian', minutes: 14, work: true },
    credit: 82,
    earnedMin: 15,
    extraLine: '当前正在拆第2段视频',
  }, now)
  assert.ok(lines.length <= MAX_TELEMETRY_LINES)
  assert.match(lines[0], /dept:govern/)
  assert.match(circadianLabel(now, 'Asia/Shanghai'), /睡前/)

  const built = buildCoreMemoryBlocks({
    persona: '我是鲸澜。'.repeat(120),
    selfDigest: '长期主线：做完算法复盘并输出可验证笔记。'.repeat(20),
    coreConstraints: ['先核验事实再下结论', '绝不无条件顺从'],
    telemetry: { state: 'sprint', department: 'dept:govern', credit: 82 },
    lorebook: [
      '规则条目1：先看反证再定论',
      '规则条目2：抖音超过15分钟立刻触发锁定',
      '规则条目3：工作包必须写出交付物',
      '规则条目4：这一条超预算先裁且不该超过3条',
    ],
  }, now)
  assert.equal(built.withinBudget, true)
  assert.ok(built.tokens <= CORE_TOKEN_LIMIT)
  assert.ok(built.telemetryLines <= MAX_TELEMETRY_LINES)
  assert.ok(built.lorebookCount <= MAX_LOREBOOK_SLOTS)
})

test('M1.3: withdrawal 状态机与 /plan 冷却抑制及目标 App / 实操解除', async () => {
  const t0 = 1_700_000_000_000
  let s = emptyWithdrawalState()
  const r1 = recordReplyOutcome(s, { verdict: 'brief', now: t0 })
  assert.equal(r1.entered, false)
  assert.equal(isInWithdrawal(r1.state, t0), false)

  const r2 = recordReplyOutcome(r1.state, { verdict: 'evasive', now: t0 + 1000 })
  assert.equal(r2.entered, true)
  assert.equal(isInWithdrawal(r2.state, t0 + 1000), true)
  assert.equal(r2.state.personaCooldownUntil, t0 + 1000 + WITHDRAWAL_COOLDOWN_MS)

  const still = checkWithdrawalRelease(r2.state, { isTargetApp: false, now: t0 + 10 * 60_000 })
  assert.equal(still.released, false)

  const relApp = checkWithdrawalRelease(r2.state, { isTargetApp: true, now: t0 + 11 * 60_000 })
  assert.equal(relApp.released, true)
  assert.equal(relApp.reason, 'target-app')
  assert.equal(isInWithdrawal(relApp.state, t0 + 11 * 60_000), false)

  const relTimeout = checkWithdrawalRelease(r2.state, { now: r2.state.personaCooldownUntil + 1 })
  assert.equal(relTimeout.released, true)
  assert.equal(relTimeout.reason, 'timeout')

  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12,
      apps: [{ pkg: 'md.obsidian', label: 'Obsidian', role: 'work' }],
    }))
    writeFileSync(join(home, 'companion', 'withdrawal.json'), JSON.stringify({
      v: 1, streak: 2, personaCooldownUntil: Date.now() + 30 * 60_000, triggeredAt: Date.now(),
    }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    const h = host({ 'session-sis': sis, 'session-big': big }, 'free')
    const paused = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', {
      roll: 100, timeZone: 'Asia/Shanghai', phone: { app: 'com.ss.android.ugc.aweme', fun: true, screenOn: true },
    })
    assert.equal(paused.body.speak, false)
    assert.equal(paused.body.reason, 'withdrawal')

    const released = await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', {
      roll: 100, timeZone: 'Asia/Shanghai', phone: { app: 'md.obsidian', label: 'Obsidian', screenOn: true },
    })
    assert.notEqual(released.body.reason, 'withdrawal')
  })
})

test('M1.4 & SYS-002: <strategist_audit> 剥离、审计日志提取、反顺从过滤及 /result 端到端脱敏', async () => {
  const raw = '<strategist_audit>{"user_premise":"以为刷题就能过","evidence_tier":"用户自述","falsify_path":"只刷不总结无法迁移","cost_if_wrong":"浪费两周时间"}</strategist_audit>\n\n你先把那三道错题的边界条件拆开，今天只准交这个。'
  const stripped = stripStrategistAudit(raw)
  assert.equal(stripped.hadAudit, true)
  assert.equal(stripped.audit.user_premise, '以为刷题就能过')
  assert.equal(stripped.cleanText, '你先把那三道错题的边界条件拆开，今天只准交这个。')

  const unclosed = '<strategist_audit>{"user_premise":"还没写完'
  assert.equal(stripStrategistAudit(unclosed).cleanText, '')

  assert.equal(detectSycophancy('你说得对，完全赞同你的决定！').sycophantic, true)
  assert.equal(detectSycophancy('你说得对，但是前提是样本量足够，否则反证成立风险很大。').sycophantic, false)
  assert.match(antiSycophancyRewriteHint({ pattern: '你说得对' }), /SYS-002/)

  await withHome(async (home) => {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: 'session-sis', strategistSessionId: 'session-big', quietStart: 12, quietEnd: 12,
    }))
    const sis = agentOf('session-sis')
    const big = agentOf('session-big')
    const h = host({ 'session-sis': sis, 'session-big': big }, 'morning')
    sis.tools.sister_note.execute({ text: '他想跳过复盘直接开新书', urgent: true })
    await call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai' })
    await call(h.handler(), 'POST', '/dsh-go-companion/v1/request', {
      v: 1, requestId: 'req-audit-1', eventId: 'evt-audit-1', kind: 'random_chat', payload: {}, deadlineMs: 30000,
    })
    await finishTurn(h, big, 1, raw)
    const res = await call(h.handler(), 'GET', '/dsh-go-companion/v1/result?requestId=req-audit-1&waitMs=0')
    assert.equal(res.body.state, 'generated')
    assert.doesNotMatch(res.body.text, /strategist_audit/)
    assert.doesNotMatch(res.body.text, /user_premise/)
    assert.match(res.body.text, /〔鲸澜〕你先把那三道错题/)
    const ledgerText = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
    assert.match(ledgerText, /"state":"strategist-audit"/)
    assert.match(ledgerText, /"user_premise":"以为刷题就能过"/)
  })
})

test('M2: FWK-003 分阶槽位释放与 L2 无状态判官及 2 次失败观察锚点提示', async () => {
  const s1 = slotsForStage({ stage: 1, topic: '为什么用户留存突然跌了' })
  assert.equal(s1.length, 2)
  assert.deepEqual(s1.map((x) => x.dice), [6, 1])

  const light = slotsForStage({ stage: 2, light: true })
  assert.equal(light.length, 1)
  assert.equal(light[0].dice, 2)

  assert.equal(extractObservationAnchors('第3分钟缓存命中率掉到12%，数据库连接池打满。', ['缓存命中率', '连接池', '第3分钟']).length, 3)

  const parsed = parseL2Judge('```json\n{"pass":false,"code":"l2:circular","slot":"dice_6","directive":"别同义反复，说出第二个解释"}\n```')
  assert.equal(parsed.pass, false)
  assert.equal(parsed.code, 'l2:circular')

  const mockStreamFail = async function* () {
    yield { type: 'text-delta', text: '{"pass":false,"code":"l2:no-evidence","slot":"dice_6","directive":"只下判断没给依据"}' }
    yield { type: 'finish', reason: { kind: 'stop' } }
  }
  const fail1 = await evaluateSixDiceSlot({
    dice: 6,
    answer: '我觉得大概是因为网络慢吧，系统一直都不太稳定。',
    sourceContext: '日志显示第3分钟缓存失效、连接池排队400个请求、RT飙升到8秒。',
    anchors: ['缓存失效', '连接池排队', 'RT飙升'],
    failures: 0,
    stream: mockStreamFail,
    provider: 'test',
    model: 'test-model',
  })
  assert.equal(fail1.pass, false)
  assert.equal(fail1.failures, 1)
  assert.equal(fail1.anchorHint, '')

  const fail2 = await evaluateSixDiceSlot({
    dice: 6,
    answer: '因为本来就是网络卡住了所以慢，没别的可能了。',
    sourceContext: '日志显示第3分钟缓存失效、连接池排队400个请求、RT飙升到8秒。',
    anchors: ['缓存失效', '连接池排队', 'RT飙升'],
    failures: fail1.failures,
    stream: mockStreamFail,
    provider: 'test',
    model: 'test-model',
  })
  assert.equal(fail2.pass, false)
  assert.equal(fail2.failures, 2)
  assert.match(fail2.anchorHint, /缓存失效.*连接池排队/)
})

test('M4.1 + Obsidian: 四部自动路由写 bouts/ledger 与 YAML + [[WikiLinks]] 卡片', async () => {
  assert.equal(DEPARTMENTS.length, 4)
  assert.equal(inferDepartment({ text: '把这篇论文原文截图里的数字核对一下' }).department, 'dept:collect')
  assert.equal(inferDepartment({ text: '这个前提我觉得可疑，帮我想个反例' }).department, 'dept:strategize')
  assert.equal(inferDepartment({ text: '今晚时间不够，两个工作包先排哪个' }).department, 'dept:dispatch')
  assert.equal(inferDepartment({ mainState: 'review', kind: 'audit' }).department, 'dept:govern')

  await withHome(async (home) => {
    const db = openBoutsDb(join(home, 'memory.sqlite'))
    try {
      const opened = openBout(db, { protocolId: 'sprint_read', home })
      assert.equal(opened.ok, true)
      const sw = routeBoutDepartment({
        db,
        home,
        boutId: opened.id,
        currentDept: '',
        input: { text: '这个结论前提有没有漏洞？找个替代解释' },
      })
      assert.equal(sw.ok, true)
      assert.equal(sw.changed, true)
      assert.equal(sw.department, 'dept:strategize')
      const rows = listBouts(db)
      assert.equal(rows[0].department, 'dept:strategize')
      const ledger = readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8')
      assert.match(ledger, /"state":"department-switch"/)
      assert.match(ledger, /"to":"dept:strategize"/)
    } finally {
      db.close()
    }

    const fm = renderFrontmatter({ title: '测试卡', tags: ['a', 'b'], department: 'dept:strategize' })
    assert.match(fm, /^---\ntitle: "测试卡"/)
    assert.equal(wikiLink('verdicts/card-1.md', '裁决一'), '[[verdicts/card-1|裁决一]]')

    const kb = join(home, 'notes', '大肥鱼')
    const cardPath = writeObsidianCard({
      kb,
      slug: 'cognitive-card-1',
      title: '认知校准卡',
      department: 'dept:strategize',
      links: ['长期规划'],
      body: '前提需要可核验数据支撑。',
    })
    assert.ok(existsSync(cardPath))
    const cardText = readFileSync(cardPath, 'utf8')
    assert.match(cardText, /department: "dept:strategize"/)
    assert.match(cardText, /\[\[长期规划\]\]/)

    const synced = syncVerdicts({
      kb,
      entries: [{ state: 'verdict', at: Date.UTC(2026, 10, 14, 14, 0, 0), kind: '晚间对账', summary: '完成三段视频拆解', longTerm: true, department: 'dept:govern' }],
    })
    assert.equal(synced.count, 1)
  })
})
