// 本次真实问题回归测试：
// 1. 局插件 listBeats 缺失修复 & GET /bouts + POST /bouts (open/start/strict/close/finish) 完整可用
// 2. 空 allow 局不再触发全机锁；strictNow 无 App 时拒绝而非锁死全机
// 3. Gemini 训练模式永不自锁 Gemini，不被 ladder E1′ 劫持；gemini_end / dispatch_control 彻底解锁
// 4. phone.today 保留至多 24 项（含高打开次数的 0 分钟 App）；behavior_radar 自主触发并唤醒发球

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { DatabaseSync } from 'node:sqlite'
import { apply, setStatePin } from '../lib/index.js'
import { GEMINI_PACKAGES } from '../lib/gemini.js'
import { evalBehaviorRadar } from '../lib/behavior_radar.js'
import { cleanPhone, phoneLines } from '../lib/phone.js'
import { createPresetRuntime } from '../lib/presetwire.js'
import { listScreenshots } from '../lib/wall_auditor.js'
import { collectVaultMemoryFacts, ensureStarterVaultFiles, vaultRecentNotes } from '../lib/notes.js'
import { rollDiceCard } from '../lib/six_dice.js'
import { loadCandidates } from '../lib/topics.js'

const SIS = 'session-sis'
const BIG = 'session-big'

function agentOf(id) {
  const sent = []
  const tools = {}
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    config: { provider: 'test', model: 'test-model' },
    sent,
    tools,
    followup(m) { sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents, state = 'brain') {
  let handler
  const globalTools = {}
  const ctx = {
    get(n) {
      if (n === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (n === 'connection') return { requestRejection: () => undefined }
      if (n === 'sessionController') return { resolveAgent: async ({ sessionId }) => ({ agent: agents[sessionId || SIS] }) }
      if (n === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    tools: { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(f) { f() } }) },
    effect(f) { f() },
  }
  setStatePin(state)
  apply(ctx)
  return { handler: () => handler, globalTools }
}

function call(handler, method, path, body) {
  const url = path.startsWith('/') ? '/dsh-go-companion/v1' + path : '/dsh-go-companion/v1/' + path
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = url
  req.headers = { 'content-type': 'application/json' }
  const res = {
    statusCode: 0, text: '',
    writeHead(s) { this.statusCode = s },
    end(c) { this.text = c ? String(c) : '' },
  }
  return Promise.resolve(handler(req, res)).then(() => ({
    status: res.statusCode,
    body: res.text ? JSON.parse(res.text) : null,
  }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-fixes-'))
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
      // Windows 下部分测试持有 sqlite 句柄时忽略临时目录清理错误
    }
  }
}

function setup(home, cfg = {}) {
  mkdirSync(join(home, 'fish-memory'), { recursive: true })
  mkdirSync(join(home, 'companion'), { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: SIS,
    strategistSessionId: BIG,
    dual: true,
    quietStart: 12,
    quietEnd: 12,
    minGapMs: 0,
    diceThreshold: 100,
    ...cfg,
  }))
  const db = new DatabaseSync(join(home, 'fish-memory', 'fish-memory.sqlite'))
  db.exec('CREATE TABLE topics (id TEXT PRIMARY KEY, scope TEXT, title TEXT, detail TEXT, status TEXT, priority INTEGER, opened_at INTEGER, updated_at INTEGER, last_touch_at INTEGER, next_ask_at INTEGER, attempts INTEGER, due_at INTEGER, closed_at INTEGER, reason TEXT)')
  db.exec('CREATE TABLE facts (id TEXT PRIMARY KEY, scope TEXT, text TEXT, importance INTEGER, pinned INTEGER, updated_at INTEGER, status TEXT)')
  db.exec('CREATE TABLE episodes (id TEXT PRIMARY KEY, scope TEXT, text TEXT, tags TEXT, at INTEGER)')
  db.close()

  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big })
  return { h, sis, big, plan: (phone) => call(h.handler(), 'POST', '/plan', { phone }) }
}

test('GET /bouts 与 POST /bouts 支持开局/切严格/收局，且 listBeats 已导入不会 ReferenceError', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const opened = await call(t.h.handler(), 'POST', '/bouts', {
      action: 'start',
      protocolId: 'execute',
      title: '写周报',
      allow: ['md.obsidian'],
      strict: true,
    })
    assert.equal(opened.status, 200)
    assert.equal(opened.body.ok, true, JSON.stringify(opened.body))

    const status = await call(t.h.handler(), 'GET', '/bouts')
    assert.equal(status.status, 200)
    assert.equal(status.body.ok, true)
    assert.ok(status.body.running, 'GET /bouts 应返回 running 局')
    assert.equal(status.body.strict, true, 'strict=true 开局后应处于严格模式')
    assert.ok(Array.isArray(status.body.protocols) && status.body.protocols.length > 0)
    assert.ok(status.body.snapshot, 'boutsSnapshot 不应再因缺 listBeats 崩溃')

    const closed = await call(t.h.handler(), 'POST', '/bouts', { action: 'finish' })
    assert.equal(closed.status, 200)
    assert.equal(closed.body.ok, true)
  })
})

test('presetwire：未声明 App 的局绝不下发 allow=[] 的全机锁', async () => {
  let doc = {}
  const blocks = []
  let bout = { id: 'b-empty', kind: 'execute', openedAt: Date.now() }
  const rt = createPresetRuntime({
    load: () => doc,
    save: (d) => { doc = d },
    record: () => {},
    runningBout: () => bout,
    ignore: () => [],
    day: () => '2026-11-17',
    minute: () => 600,
    funToday: () => 0,
    apply: {
      block: (app, until, why) => blocks.push({ app, until, why }),
      allLock: () => {},
      tool: () => {},
      plan: () => {},
      tighten: () => {},
    },
  })
  const noAppsStrict = rt.strictNow(Date.now(), '手动严格', 'panel')
  assert.equal(noAppsStrict.ok, false, '没有声明任何 App 时不应启动全机锁')
  assert.equal(rt.sessionView(Date.now()), null)

  // 即使触发 off-dwell 停留娱乐，也只封娱乐 App，不生成 allow=[] 全机 session
  const now = Date.now()
  rt.tick({ app: 'com.ss.android.ugc.aweme', fun: true, minutes: 3, switches: { recent: [] } }, now)
  assert.equal(rt.sessionView(now), null)
  assert.ok(blocks.some((b) => b.app === 'com.ss.android.ugc.aweme'))
})

test('Gemini 训练模式永不自锁 Gemini，且 unlock_focus / unlock_gemini / gemini_end 可完整解锁', async () => {
  await withHome(async (home) => {
    // 先预置脏的阶梯 all-lock 记录，验证主动开 gemini_live 会清空它
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'ladder.json'), JSON.stringify({
      cell: 'all-lock',
      cellUntil: Date.now() + 3_600_000,
      tickAt: Date.now(),
    }))
    const t = setup(home, { ladderOk: true })
    const startRes = t.sis.tools.gemini_live.execute({ topic: '线性代数复盘', minutes: 30, mode: 'study' })
    assert.equal(startRes.ok, true)

    // 1) 在 think 态下，gemini_end 可完整清掉 Gemini + ladder + strict
    const endRes = t.sis.tools.gemini_end.execute({ reason: '学完了' })
    assert.equal(endRes.ok, true)

    // 2) 重开 gemini_live，再发 /plan（非 Gemini 前台），ladderE1Prime 不得劫持为 all-lock
    const startAgain = t.sis.tools.gemini_live.execute({ topic: '线性代数复盘', minutes: 30, mode: 'study' })
    assert.equal(startAgain.ok, true)
    const p1 = await t.plan({
      app: 'com.tencent.mm', label: '微信', minutes: 2, fun: false, screenOn: true, local: '10:30',
      today: [{ app: 'com.tencent.mm', label: '微信', minutes: 2 }],
    })
    assert.ok(p1.body.session, '应存在 Gemini session')
    assert.ok(p1.body.session.allow.includes(GEMINI_PACKAGES[0]), 'allow 必须包含 Gemini 本体')
    assert.notEqual(p1.body.session.cell, 'all-lock')

    const dc = t.h.globalTools.dispatch_control || t.big.tools.dispatch_control
    assert.ok(dc, '应注册 dispatch_control 工具')
    // 小鱼在任意状态下都可用 unlock_gemini 直接关闭误锁与 Gemini 连麦舱
    const ug = dc.execute({ action: 'unlock_gemini', why: '用户让小鱼关掉' }, { sessionId: SIS })
    assert.equal(ug.ok, true, ug.text)

    // 再开一个 strict bout + focus_lock，由大鱼用 dispatch_control(unlock_focus) 一并解掉
    await call(t.h.handler(), 'POST', '/bouts', { action: 'open', protocolId: 'execute', apps: { need: ['md.obsidian'], allow: ['md.obsidian'] }, strict: true })
    const u = dc.execute({ action: 'unlock_focus', why: '用户收工' }, { sessionId: BIG })
    assert.equal(u.ok, true, u.text)
    // 小鱼调 unlock_focus 仍按非对称权限被拒绝
    assert.equal(dc.execute({ action: 'unlock_focus' }, { sessionId: SIS }).ok, false)
    const after = await t.plan({ app: 'md.obsidian', label: 'Obsidian', minutes: 1, fun: false, screenOn: true, local: '10:40' })
    assert.equal(after.body.session, null, '所有 session 锁应当已清零')
    assert.equal(after.body.clearWorkSession, true, '应当下发 clearWorkSession 让手机释放工作锁')
  })
})

test('phone.today 保留 24 项高打开记录，行为雷达在累计娱乐超 60 分钟与上午未立项时一定触发发球', async () => {
  const rawToday = Array.from({ length: 24 }, (_, i) => ({
    app: `com.example.app${i}`,
    label: `应用${i}`,
    minutes: i === 23 ? 0 : i + 1,
    opens: i === 23 ? 5 : 1,
    fun: i === 0,
  }))
  const cleaned = cleanPhone({ app: 'md.obsidian', label: 'Obsidian', minutes: 2, screenOn: true, local: '09:30', today: rawToday })
  assert.equal(cleaned.today.length, 24)

  const budgetHit = evalBehaviorRadar({
    phone: { app: 'tv.danmaku.bili', label: 'B站', minutes: 2, fun: true, screenOn: true },
    funTotalToday: 65,
    now: Date.UTC(2026, 10, 17, 6, 0, 0), // 北京时间 14:00
    runningBout: null,
  })
  assert.equal(budgetHit.triggered, true)
  assert.equal(budgetHit.kind, 'daily-fun-budget')

  const morningHit = evalBehaviorRadar({
    phone: { app: 'com.tencent.mm', label: '微信', minutes: 7, fun: false, screenOn: true, switches: { in15m: 3 } },
    now: Date.UTC(2026, 10, 17, 1, 30, 0), // 北京时间 09:30
    morningPlanned: false,
    runningBout: null,
  })
  assert.equal(morningHit.triggered, true)
  assert.equal(morningHit.kind, 'unplanned-nudge')
  assert.equal(morningHit.forceLevel, 0)

  await withHome(async (home) => {
    const t = setup(home, { diceThreshold: 0 }) // 即使骰子为 0，一定触发器仍须置 speak=true
    const p = await t.plan({
      app: 'com.miHoYo.Yuanshen',
      label: '原神',
      minutes: 6,
      fun: true,
      screenOn: true,
      local: '14:30',
      today: [{ app: 'com.miHoYo.Yuanshen', label: '原神', minutes: 6, fun: true }],
    })
    assert.equal(p.body.speak, true, '游戏连玩行为雷达应当绕过 dice=0 必定发球')
    assert.equal(p.body.behaviorRadar?.kind, 'game-binge')
  })
})

test('分时段使用报告、declare_app_intent 审批与重罚限额、neutral-hop 防误杀、Gemini 语音模态检测及截图去重', async () => {
  await withHome(async (home) => {
    const t = setup(home, { diceThreshold: 100 })
    const slotPhone = {
      app: 'md.obsidian',
      label: 'Obsidian',
      minutes: 15,
      fun: false,
      screenOn: true,
      local: '15:10',
      totalMinutes: 85,
      geminiVoiceActive: false,
      today: [
        { app: 'md.obsidian', label: 'Obsidian', minutes: 45, opens: 4 },
        { app: 'tv.danmaku.bili', label: 'B站', minutes: 25, opens: 6, fun: true },
        { app: 'com.tencent.mm', label: '微信', minutes: 15, opens: 8 },
      ],
      timeSlots: [
        { slot: '12:00-14:00', minutes: 35, switches: 5, top: [{ app: 'tv.danmaku.bili', label: 'B站', minutes: 25 }] },
        { slot: '14:00-16:00', minutes: 50, switches: 3, top: [{ app: 'md.obsidian', label: 'Obsidian', minutes: 45 }] },
      ],
      switches: {
        in90s: 2,
        apps90s: ['微信', 'Obsidian'],
        in15m: 9,
        distinct15m: 5,
        in60m: 12,
        distinct60m: 5,
        recent: [
          { pkg: 'com.tencent.mm', agoMs: 5_000 },
          { pkg: 'tv.danmaku.bili', agoMs: 15_000 },
          { pkg: 'com.xingin.xhs', agoMs: 30_000 },
          { pkg: 'com.eg.android.AlipayGphone', agoMs: 45_000 },
        ],
      },
    }
    const cleaned = cleanPhone(slotPhone)
    assert.equal(cleaned.totalMinutes, 85)
    assert.equal(cleaned.timeSlots.length, 2)
    assert.ok(phoneLines(cleaned).some((l) => l.includes('分时段调用') && l.includes('12:00-14:00')))

    // 上报一次全量使用数据，再通过 GET /usage/report 与 app_usage_report 工具检索
    await t.plan(slotPhone)
    const repRoute = await call(t.h.handler(), 'GET', '/usage/report')
    assert.equal(repRoute.status, 200)
    assert.equal(repRoute.body.totalMinutes, 85)
    assert.equal(repRoute.body.timeSlots.length, 2)
    const repTool = t.h.globalTools.app_usage_report.execute({})
    assert.equal(repTool.ok, true)
    assert.match(repTool.text, /12:00-14:00/)

    // 触发一次 game-binge 重罚后，declare_app_intent 审批时长被限缩
    await t.plan({
      app: 'com.miHoYo.Yuanshen',
      label: '原神',
      minutes: 6,
      fun: true,
      screenOn: true,
      local: '15:12',
      today: [{ app: 'com.miHoYo.Yuanshen', label: '原神', minutes: 6, fun: true }],
    })
    const intentTool = t.h.globalTools.declare_app_intent
    assert.ok(intentTool, '应注册 declare_app_intent 全局工具')
    const gameIntent = intentTool.execute({ app: '原神', intent: '打活动', requestedMinutes: 20, decision: 'approve', reason: '想放松' })
    assert.equal(gameIntent.ok, false, '纯游戏申报必须硬拒绝')
    const biliIntent = intentTool.execute({
      app: 'B站',
      intent: '看高数网课第三章',
      requestedMinutes: 30,
      deliverable: '提交笔记截图',
      decision: 'approve',
      reason: '需要看公开课但因今日已有严重违规仅限缩放行',
    }, { sessionId: BIG })
    assert.equal(biliIntent.ok, true)
    assert.match(biliIntent.text, /15 分钟|限额|压缩/, '重罚后高危上限应将 30 分钟申报缩减到 15 分钟')

    // 中立 App 碎片游走：仅冷却游走软件，不发 allow=[] 全机锁
    const hopRes = evalBehaviorRadar({
      phone: {
        app: 'com.xingin.xhs',
        label: '小红书',
        minutes: 2,
        fun: false,
        screenOn: true,
        switches: { in15m: 9, distinct15m: 5, recent: [{ app: 'com.tencent.mm' }, { app: 'com.xingin.xhs' }] },
      },
      runningBout: null,
      now: Date.UTC(2026, 10, 17, 7, 15, 0),
    })
    assert.equal(hopRes.triggered, true)
    assert.equal(hopRes.kind, 'neutral-hop')
    assert.equal(hopRes.forceLevel, 3)

    // 截图 SHA-256 去重：同一内容两份截图仅通过一次
    const shotDir = join(home, 'shots')
    mkdirSync(shotDir, { recursive: true })
    const f1 = join(shotDir, 'a.png')
    const f2 = join(shotDir, 'b.png')
    writeFileSync(f1, Buffer.from('same-image-bytes'))
    writeFileSync(f2, Buffer.from('same-image-bytes'))
    const firstPass = listScreenshots([shotDir], 0)
    assert.equal(firstPass.length, 1, '同哈希截图在同批扫描中应去重')
    const secondPass = listScreenshots([shotDir], 0, { seenHashes: [firstPass[0].sha256] })
    assert.equal(secondPass.length, 0, '已提交过的截图哈希再提交应被拦截')
  })
})

test('摇一摇六骰抽卡与思考保存、Obsidian 个人记忆模板生成与去重导入 fish-memory.sqlite', async () => {
  await withHome(async (home) => {
    // 1) six_dice.rollDiceCard 避开刚摇过的一骰并带出 Gemini 话术
    const firstCard = rollDiceCard({ excludeDice: 2, topic: '高数错题', appLabel: 'Obsidian', anchor: '每周二复盘积分表', seed: 0 })
    assert.notEqual(firstCard.dice, 2)
    assert.match(firstCard.geminiPrompt, /高数错题/)
    assert.match(firstCard.geminiPrompt, /每周二复盘积分表/)

    // 2) ensureStarterVaultFiles 幂等生成模板，绝不覆盖用户已写过的 我.md
    const notesRoot = join(home, 'obsidian-vault')
    const kb = join(notesRoot, '大肥鱼')
    mkdirSync(kb, { recursive: true })
    writeFileSync(join(kb, '我.md'), '# 我.md\n- 📌 每天 19:00 前完成两道算法题\n', 'utf8')
    const init1 = ensureStarterVaultFiles(kb)
    assert.equal(init1.ok, true)
    assert.ok(readFileSync(join(kb, '我.md'), 'utf8').includes('19:00 前完成两道算法题'), '已有 我.md 绝对不可覆盖')
    assert.ok(init1.createdFiles.some((p) => p.endsWith('判断.md')))
    assert.ok(init1.createdFiles.some((p) => p.endsWith('记忆模板_个人画像与偏好.md')))

    // 放入隐私目录验证绝不被收集，放入 导入/ 验证会被导入
    mkdirSync(join(kb, '导入', '日记'), { recursive: true })
    writeFileSync(join(kb, '导入', '日记', 'secret.md'), '- 绝不能导入的隐私条目，超过八个字测试\n', 'utf8')
    writeFileSync(join(kb, '导入', '我的学习规律.md'), '- 📌 我在早上清醒期适合先啃硬代码架构而不是看微信\n- 连续刷两个以上工具栏容易滑向信息松弛\n', 'utf8')
    const collected = collectVaultMemoryFacts(kb)
    assert.ok(collected.some((f) => f.text.includes('早上清醒期')))
    assert.ok(!collected.some((f) => f.text.includes('隐私条目')), '日记目录必须被跳过')

    // 确认系统生成的 大肥鱼/导入/ 等模板文件不会挤占 vaultRecentNotes 的真实笔记前排
    mkdirSync(join(notesRoot, '项目'), { recursive: true })
    writeFileSync(join(notesRoot, '项目', '真实笔记.md'), '# 真实笔记\n\n这是用户自己在写的核心笔记正文，不应被模板挤出前三。\n', 'utf8')
    const recent = vaultRecentNotes(notesRoot, { limit: 3 })
    assert.ok(recent.some((r) => r.rel === '项目/真实笔记.md'), '真实笔记必须在 recent 前列')
    assert.ok(!recent.some((r) => r.rel.startsWith('大肥鱼/导入/') || r.rel === '大肥鱼/我.md' || r.rel === '大肥鱼/判断.md'), '模板/系统文件不占最近笔记位')

    // 3) 启动插件，将 notesPath 指向 notesRoot，测试 /memory/import + /dice/roll + /dice/thought + roll_six_dice
    const t = setup(home, { notesPath: notesRoot })
    const h = t.h.handler()
    const imp1 = await call(h, 'POST', '/memory/import', {})
    assert.equal(imp1.status, 200, JSON.stringify(imp1.body))
    assert.equal(imp1.body.ok, true)
    assert.ok(imp1.body.imported >= 4, '至少应导入模板与自定义条目')

    // 再次导入必须严格去重
    const imp2 = await call(h, 'POST', '/memory/import', {})
    assert.equal(imp2.status, 200)
    assert.equal(imp2.body.imported, 0, '重复导入应全部去重跳过')

    // 验证两姐妹共用记忆库 loadCandidates 已立即读到导入的事实
    const memDb = new DatabaseSync(join(home, 'fish-memory', 'fish-memory.sqlite'))
    try {
      const cands = loadCandidates(memDb, { sessionId: SIS, now: Date.now() })
      assert.ok(cands.facts.some((f) => f.text.includes('19:00 前完成两道算法题') || f.text.includes('早上清醒期')))
    } finally {
      memDb.close()
    }

    // 测试 /dice/roll 与 /dice/thought 以及全局工具 roll_six_dice
    const rolled = await call(h, 'POST', '/dice/roll', { manual: true, appLabel: 'B站' })
    assert.equal(rolled.status, 200)
    assert.ok(rolled.body.dice >= 1 && rolled.body.dice <= 6)

    const saved = await call(h, 'POST', '/dice/thought', {
      dice: rolled.body.dice,
      name: rolled.body.name,
      thought: '我刚刚其实是在逃避写线性代数的第三问',
      appLabel: 'B站',
    })
    assert.equal(saved.status, 200)
    assert.equal(saved.body.saved, true)
    assert.ok(readFileSync(join(kb, '思维碎记.md'), 'utf8').includes('逃避写线性代数的第三问'))

    const diceTool = t.h.globalTools.roll_six_dice || t.big.tools.roll_six_dice
    assert.ok(diceTool, '应注册全局工具 roll_six_dice')
    const toolOut = diceTool.execute({ topic: '线性代数第三问' })
    assert.equal(toolOut.ok, true)
    assert.match(toolOut.text, /骰\d/)
  })
})


