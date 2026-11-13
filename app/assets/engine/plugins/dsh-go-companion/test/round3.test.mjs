// 第三轮：D3 知识库、D4 定时连麦、D6 人设与议程。
//
// 这里只测这两块自己产出的东西：笔记库只读检索/裁决同步/记忆导出、预约连麦到点产出 launch、
// 按主状态切议程。墙和状态机本身在 wall/machine 的测试里。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { appendVerdictDaily, longPlanDigest, syncVerdicts, vaultRecentNotes, vaultSearch } from '../lib/notes.js'
import { exportImportantMemories } from '../lib/context.js'
import { agendaLines } from '../lib/roles.js'
import { buildPrompt } from '../lib/prompt.js'

function tmp(prefix) {
  return mkdtempSync(join(tmpdir(), prefix))
}

function vault(root) {
  mkdirSync(join(root, '大肥鱼', '每日'), { recursive: true })
  mkdirSync(join(root, '日记'), { recursive: true })
  mkdirSync(join(root, '项目'), { recursive: true })
  writeFileSync(join(root, '项目', '换工作.md'), '# 换工作\n\n打算三月前投十家，先把作品集做完。\n')
  writeFileSync(join(root, '项目', '作息.md'), '# 作息\n\n想把起床时间挪到六点半。\n')
  writeFileSync(join(root, '日记', '2026-11-07.md'), '# 日记\n\n今天很烂，投了简历没人理。\n')
  writeFileSync(join(root, '大肥鱼', '长期规划.md'), '# 长期规划\n\n今年把工作稳定下来，年底前还清信用卡。\n')
  writeFileSync(join(root, '大肥鱼', '每日', '2026-11-08.md'), '# 2026-11-08 · 今日\n')
  writeFileSync(join(root, '大肥鱼', '记忆.md'), '# 记忆（自动导出）\n')
}

const older = new Date(Date.now() - 3 * 86_400_000)
const newer = new Date(Date.now() - 60_000)

test('D3 vault_search：文件名和正文都命中，日记目录不读', () => {
  const root = tmp('vault-')
  try {
    vault(root)
    const byBody = vaultSearch(root, '作品集')
    assert.equal(byBody.length, 1)
    assert.equal(byBody[0].rel, '项目/换工作.md')
    assert.match(byBody[0].snippet, /作品集/)

    const byName = vaultSearch(root, '作息')
    assert.ok(byName.some((r) => r.rel === '项目/作息.md'))

    // 日记目录里的内容搜不到，也不能因为关键词出现在日记里而漏出来。
    assert.equal(vaultSearch(root, '没人理').length, 0)
    assert.equal(vaultSearch(root, '').length, 0)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('D3 vault_recent_notes：按修改时间取最近的，跳过每日文件和记忆.md', () => {
  const root = tmp('vault-recent-')
  try {
    vault(root)
    // 把"换工作"改旧，让"作息"排第一。
    writeFileSync(join(root, '项目', '换工作.md'), '# 换工作\n\n打算三月前投十家，先把作品集做完。\n')
    const fsMod = { atime: older, mtime: older }
    utimesSync(join(root, '项目', '换工作.md'), fsMod.atime, fsMod.mtime)
    utimesSync(join(root, '项目', '作息.md'), newer, newer)

    const rows = vaultRecentNotes(root, { limit: 3 })
    assert.equal(rows[0].rel, '项目/作息.md')
    const rels = rows.map((r) => r.rel)
    assert.ok(!rels.includes('大肥鱼/每日/2026-11-08.md'), '每日文件不进最近笔记')
    assert.ok(!rels.includes('大肥鱼/记忆.md'), '自动导出的记忆不进最近笔记')
    assert.ok(!rels.some((r) => r.startsWith('日记/')), '日记不读')

    // 墙自己的审计日志也是系统产出：设成最新也不能把真笔记挤出前三，
    // 否则球会拿一条审计记录当话题素材（e2e I1.1 就是这么红的）。
    const auditAt = new Date(newer.getTime() + 60_000)
    mkdirSync(join(root, '大肥鱼', '墙', '审计'), { recursive: true })
    writeFileSync(join(root, '大肥鱼', '墙', '审计', '2026-11-08.md'), '〔墙〕晨间立项：缺席——今天没有立项\n')
    utimesSync(join(root, '大肥鱼', '墙', '审计', '2026-11-08.md'), auditAt, auditAt)
    const after = vaultRecentNotes(root, { limit: 3 })
    assert.ok(!after.some((r) => r.rel.startsWith('大肥鱼/墙/审计/')), '墙的审计日志不进最近笔记')
    assert.equal(after[0].rel, '项目/作息.md', '审计日志再新也不顶掉真笔记')
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('D3 长期规划摘要进 prompt，且不逐条复述', () => {
  const root = tmp('vault-plan-')
  try {
    vault(root)
    const digest = longPlanDigest(root)
    assert.match(digest, /还清信用卡/)

    const prompt = buildPrompt({
      role: 'companion',
      vault: { longPlan: digest, tasks: [{ task: '改作品集', evidence: '作品集.md 提交记录' }], recent: [{ rel: '项目/换工作.md', summary: '三月前投十家' }] },
    })
    assert.match(prompt, /长期规划/)
    assert.match(prompt, /还清信用卡/)
    assert.match(prompt, /改作品集/)
    assert.match(prompt, /作品集\.md 提交记录/)
    assert.match(prompt, /项目\/换工作\.md/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('D3 裁决同步：只写增量，写进当日文件；长期目标才追加长期规划', () => {
  const root = tmp('vault-sync-')
  try {
    vault(root)
    const kb = join(root, '大肥鱼')
    const tz = 'Asia/Shanghai'
    const now = Date.now()
    const entries = [
      { state: 'verdict', at: now - 60_000, kind: 'review', satisfied: true, summary: '作品集做完了', nextStep: '明天投三家' },
      { state: 'verdict', at: now, kind: 'morning', satisfied: true, summary: '定下两件事', nextStep: '按计划走', tasks: [{ task: '改简历', evidence: '简历.pdf' }], longTerm: true },
      { state: 'reply', at: now },
    ]
    const first = syncVerdicts({ kb, entries, now, tz, state: {} })
    assert.equal(first.count, 2)
    const dailyText = () => readdirSync(join(kb, '每日')).map((f) => readFileSync(join(kb, '每日', f), 'utf8')).join('\n')
    assert.match(dailyText(), /裁决/)
    assert.match(dailyText(), /作品集做完了/)
    assert.match(dailyText(), /改简历/)
    assert.match(readFileSync(join(kb, '长期规划.md'), 'utf8'), /改简历/)

    // 同一批不重复写。
    const second = syncVerdicts({ kb, entries, now: now + 1000, tz, state: first.state })
    assert.equal(second.count, 0)
    // 新的裁决接着写。
    const third = syncVerdicts({
      kb, entries: [...entries, { state: 'verdict', at: now + 2000, kind: 'evening', satisfied: false, summary: '晚上没做完' }],
      now: now + 3000, tz, state: second.state,
    })
    assert.equal(third.count, 1)
    assert.match(dailyText(), /晚上没做完/)
    // 涉不涉及长期目标，写在长期规划里的内容不一样。
    assert.ok(!readFileSync(join(kb, '长期规划.md'), 'utf8').includes('晚上没做完'))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('D3 记忆导出：只收钉住和重要的，单向覆盖写', () => {
  const root = tmp('vault-mem-')
  try {
    const kb = join(root, '大肥鱼')
    const rows = [
      { text: '他要三月前换工作', importance: 5, pinned: 0 },
      { text: '他不喜欢早起', importance: 4, pinned: 0 },
      { text: '早饭爱吃包子', importance: 2, pinned: 0 },
      { text: '他对花生过敏', importance: 3, pinned: 1 },
    ]
    const db = { prepare: () => ({ all: () => rows.filter((r) => r.pinned === 1 || r.importance >= 4) }) }
    assert.equal(exportImportantMemories(db, kb, Date.now()), true)
    const body = readFileSync(join(kb, '记忆.md'), 'utf8')
    assert.match(body, /他要三月前换工作/)
    assert.match(body, /他对花生过敏/)
    assert.ok(!body.includes('早饭爱吃包子'), 'importance 2 不进')

    // 再导出一次就是覆盖，不追加。
    exportImportantMemories(db, kb, Date.now() + 1000)
    assert.equal((readFileSync(join(kb, '记忆.md'), 'utf8').match(/他要三月前换工作/g) || []).length, 1)

    // 空库不动已有文件。
    assert.equal(exportImportantMemories({ prepare: () => ({ all: () => [] }) }, kb, Date.now()), false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('D6 议程：每个主状态都有对应议程，裁决格式在每个过关状态里', () => {
  const start = agendaLines('standby').join('\n')
  assert.match(start, /submit_to_wall/)
  assert.match(start, /不能放/)
  assert.match(start, /「冲刺」/)

  for (const state of ['morning', 'review', 'evening', 'followup']) {
    const text = agendaLines(state).join('\n')
    assert.match(text, /submit_to_wall/, state + ' 要提交墙的工具')
    assert.match(text, /墙只看证据/, state + ' 要说清墙的口径')
    assert.match(text, /tasks/, state + ' 要写清 tasks')
  }
  assert.match(agendaLines('morning').join('\n'), /完成物/)
  assert.match(agendaLines('review').join('\n'), /完成物/)

  assert.match(agendaLines('sprint').join('\n'), /少打扰/)
  assert.match(agendaLines('brain').join('\n'), /少打扰/)
  assert.match(agendaLines('break').join('\n'), /纯陪聊/)
  assert.match(agendaLines('meal').join('\n'), /纯陪聊/)
  assert.match(agendaLines('night').join('\n'), /去睡/)
  assert.match(agendaLines('followup').join('\n'), /拉回当前那个问题/)
  // 未知状态只给底线那句，不乱加。
  assert.equal(agendaLines('nope').length, 1)
})

// ---------------------------------------------------------------- 路由：D4 预约连麦

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
  const home = mkdtempSync(join(tmpdir(), 'companion-round3-'))
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
  // R7 P0-3：`state` 钉主状态，不能摊进 config.json（那是真配置）。
  const { state = 'standby', ...rest } = extra
  mkdirSync(join(home, 'companion'), { recursive: true })
  const notes = join(home, 'vault')
  vault(notes)
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: SIS, strategistSessionId: BIG, notesPath: notes, ...rest,
  }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big }, state)
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const due = () => call(h.handler(), 'POST', '/dsh-go-companion/v1/gemini/due', {})
  return { sis, big, h, plan, due, notes }
}

test('D4 工具：schedule_gemini_live 两个角色都有，到点出 launch 并进共舱', async () => {
  await withHome(async (home) => {
    // R7 P0-3：约连麦只在共脑（brain）状态下挂；两把检索工具在吃饭时段一起挂着，顺路核对共用。
    const t = setup(home, { state: 'brain' })
    assert.ok(t.sis.tools.schedule_gemini_live, '鱼丸也能约')
    assert.ok(t.big.tools.schedule_gemini_live, '鲸澜也能约')
    setStatePin('meal')
    assert.ok(t.sis.tools.vault_search, '检索是共用的')
    assert.ok(t.big.tools.vault_recent_notes, '检索是共用的')
    setStatePin('brain')

    const r = await t.sis.tools.schedule_gemini_live.execute({ delayMinutes: 5, topic: '把简历改完', minutes: 15 })
    assert.equal(r.ok, true, JSON.stringify(r))

    // 还没到点：/plan 带上预约，/gemini/due 什么都不给。
    const p1 = await t.plan({ app: 'com.game', fun: true, screenOn: true, local: '15:00' })
    assert.ok(p1.body.geminiSchedule, '/plan 带 geminiSchedule 让手机 arm')
    assert.equal(p1.body.geminiSchedule.topic, '把简历改完')
    const early = await t.due()
    assert.equal(early.body.launch, null)

    // 把预约时间改到过去，模拟到点。
    const schedPath = join(home, 'companion', 'gemini-schedule.json')
    const sched = JSON.parse(readFileSync(schedPath, 'utf8'))
    writeFileSync(schedPath, JSON.stringify({ ...sched, at: Date.now() - 1000 }))

    const dueNow = await t.due()
    const launch = dueNow.body.launch
    assert.ok(launch, '到点要有 launch')
    assert.equal(launch.topic, '把简历改完')
    assert.equal(launch.minutes, 15)
    assert.match(launch.brief, /简历改完/, '简报里要有这次的主题')

    // 只发一次。
    const again = await t.due()
    assert.equal(again.body.launch, null)

    // 进去以后 /plan 带 session 白名单。
    const p2 = await t.plan({ app: 'com.game', fun: true, screenOn: true, local: '15:05' })
    assert.ok(p2.body.session, '/plan 要报共舱')
    assert.ok(Array.isArray(p2.body.session.allow) && p2.body.session.allow.length)
  })
})

test('D4 时间：at 用当地 HH:MM，过去的时间顺延到明天', async () => {
  await withHome(async (home) => {
    const t = setup(home, { state: 'brain' })
    const r = await t.sis.tools.schedule_gemini_live.execute({ at: '07:00', topic: '早上过一遍计划' })
    assert.equal(r.ok, true, JSON.stringify(r))
    const sched = JSON.parse(readFileSync(join(home, 'companion', 'gemini-schedule.json'), 'utf8'))
    assert.ok(Number(sched.at) > Date.now(), '约的时间在未来')

    const bad = await t.sis.tools.schedule_gemini_live.execute({ topic: '' })
    assert.equal(bad.ok, false)
  })
})

test('D6 人设：两个角色都带上当前议程，且不越权动墙', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    await t.plan({ app: 'com.game', fun: true, screenOn: true, local: '15:00' })
    for (const agent of [t.sis, t.big]) {
      const def = agent.contexts.find((c) => typeof c.text === 'function')
      assert.ok(def, '人设挂上了')
      const text = def.text()
      assert.match(text, /规则由墙执行/)
      assert.match(text, /submit_to_wall/)
      assert.match(text, /不能放/)
    }
  })
})
