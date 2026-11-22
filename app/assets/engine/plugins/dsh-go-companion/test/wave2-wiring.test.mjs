// Wave 2 统一接线集成测试：
// 1. [1.5/1.3/4.8] 刷题解锁一本账：只挣余额（每日上限 60 分钟），不发通行证，带上下一题，spendPass 同步放行白天墙
// 2. [1.6] 默认验收不受硬干预日上限影响（保持 BOUT_STRICT / P4 及 session）
// 3. [1.4] force.release 只在伴侣强制锁态刚转为可放行那一拍发 true，下一拍回落为 false
// 4. [1.7/4.12] 题库导入预览（previewOnly + 分块上传保留临时目录 + 失败返回 sample）
// 5. [4.9/4.10/4.13] 四部门收件箱 + 早上点头卡 + bridgeSnapshot.inbox + /plan boutId/sealId

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const ZONES = [
  'Asia/Shanghai', 'Europe/London', 'America/Sao_Paulo', 'America/New_York', 'America/Chicago', 'America/Denver',
  'America/Los_Angeles', 'America/Anchorage', 'Pacific/Honolulu', 'Asia/Tokyo', 'Europe/Berlin', 'Europe/Moscow',
  'Asia/Kolkata', 'Asia/Dubai', 'Australia/Sydney', 'Pacific/Auckland', 'Atlantic/Azores', 'America/Halifax',
]
function minuteOfDayOf(now, tz) {
  const p = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(now))
  return Number(p.find((x) => x.type === 'hour').value) * 60 + Number(p.find((x) => x.type === 'minute').value)
}
const DAY_TZ = ZONES.find((z) => { const m = minuteOfDayOf(Date.now(), z); return m >= 9 * 60 && m < 17 * 60 }) || 'Asia/Shanghai'

function agentOf(id) {
  const tools = {}
  const contexts = []
  return {
    id,
    status: 'idle',
    inbox: { hasPending: false },
    sent: [],
    contexts,
    tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context(def) { contexts.push(def); return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents, opts = {}) {
  let handler
  const listeners = new Map()
  const globalTools = {}
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'tools') return { register(def) { globalTools[def.name] = def; return () => { delete globalTools[def.name] } } }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on(name, fn) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn) },
    inject(_d, cb) {
      cb({
        webServer: { register(r) { handler = r.handler } },
        effect(fn) { fn() },
        ...(opts.llmStream ? { llm: { stream: opts.llmStream } } : {}),
      })
    },
    effect(fn) { fn() },
  }
  setStatePin('standby')
  apply(ctx)
  const emit = async (sid, type, data) => {
    for (const fn of listeners.get('session/event') || []) await fn({ id: sid }, { type, data })
  }
  return Object.assign(() => handler, { emit, globalTools })
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = PREFIX + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn, cfg = {}, hostOpts = {}) {
  const home = mkdtempSync(join(tmpdir(), 'companion-w2-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
      sessionId: SIS,
      strategistSessionId: BIG,
      timeZone: DAY_TZ,
      lockTimeZone: false,
      quietStart: 12,
      quietEnd: 12,
      ...cfg,
    }))
    const sis = agentOf(SIS)
    const big = agentOf(BIG)
    const h = host({ [SIS]: sis, [BIG]: big }, hostOpts)
    const get = (path) => call(h(), 'GET', path).then((r) => r.body)
    const post = (path, body) => call(h(), 'POST', path, body).then((r) => r.body)
    return await fn({ home, get, post, h, sis, big })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}

test('[1.5/1.3/4.8] 刷题只挣余额不发通行证、每日上限 60 分钟、带下一题，且 spendPass 同步放行白天墙', async () => {
  await withHome(async ({ home, post }) => {
    const bankQuestions = Array.from({ length: 8 }, (_, i) => ({
      id: `q-${i + 1}`,
      题干: `第 ${i + 1} 题题干内容比较完整`,
      选项: ['选项甲', '选项乙', '选项丙', '选项丁'],
      答案: 'A',
      解析: '解析说明',
    }))
    writeFileSync(join(home, 'companion', 'bank.json'), JSON.stringify({
      题库版本: 1,
      id: 'b-w2',
      名称: '测试题库',
      题目: bankQuestions,
    }))

    await post('/bank', { action: 'set_config', requiredCount: 1, unlockMinutes: 25 })

    // 第 1 轮：+25 分钟（上限 60）
    const s1 = await post('/bank', { action: 'quiz_session' })
    const r1 = await post('/bank', { action: 'quiz_answer', questionId: s1.question.id, answer: 'A' })
    assert.equal(r1.ok, true)
    assert.equal(r1.correct, true)
    assert.equal(r1.unlocked, false, '不再直接发解锁通行证')
    assert.equal(r1.roundComplete, true)
    assert.equal(r1.earnedOnly, true)
    assert.equal(r1.needSpend, true)
    assert.equal(r1.earnedAdded, 25)
    assert.equal(r1.dailyBankEarned, 25)
    assert.equal(r1.dailyBankCap, 60)
    assert.equal(r1.capped, false)
    assert.ok(r1.question && r1.question.id, '达标一轮后必须带上下一题')

    // 确认未向 pass-ledger 写入 earned 通行证
    const plRaw = existsSync(join(home, 'companion', 'pass-ledger.json'))
      ? JSON.parse(readFileSync(join(home, 'companion', 'pass-ledger.json'), 'utf8'))
      : null
    assert.equal(plRaw?.earned?.active || false, false)

    // 第 2 轮：+25 → 50
    const r2 = await post('/bank', { action: 'quiz_answer', questionId: r1.question.id, answer: 'A' })
    assert.equal(r2.earnedAdded, 25)
    assert.equal(r2.dailyBankEarned, 50)
    assert.equal(r2.capped, false)
    assert.ok(r2.question && r2.question.id)

    // 第 3 轮：触顶 60 → 只加 10
    const r3 = await post('/bank', { action: 'quiz_answer', questionId: r2.question.id, answer: 'A' })
    assert.equal(r3.earnedAdded, 10)
    assert.equal(r3.dailyBankEarned, 60)
    assert.equal(r3.dailyBankCap, 60)
    assert.equal(r3.capped, true)

    // 第 4 轮：已满 60 → 加 0
    const r4 = await post('/bank', { action: 'quiz_answer', questionId: r3.question.id, answer: 'A' })
    assert.equal(r4.earnedAdded, 0)
    assert.equal(r4.dailyBankEarned, 60)
    assert.equal(r4.capped, true)

    // 开启「全天默认锁」预案，验证白天墙锁住时花余额也能同步放行
    const pSet = await post('/presets', { action: 'set', preset: { id: 'default-lock', 状态: '生效' } })
    assert.equal(pSet.ok, true)
    const beforeSpend = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 2, screenOn: true },
    })
    assert.equal(beforeSpend.wall.locked, true, '花余额前白天墙锁定')

    const sp = await post('/seals', { action: 'spend', minutes: 15, reason: '刷题达标休息十五分钟' })
    assert.equal(sp.ok, true)
    assert.ok(Number(sp.until) > Date.now(), 'spend 返回必须包含 until')
    assert.equal(sp.verify.active, false)
    assert.equal(sp.verify.why, 'spend')

    const afterSpend = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 3, screenOn: true },
    })
    assert.equal(afterSpend.wall.locked, false, 'spendPass 有效期内白天普通防分心墙同步放行')
    assert.equal(afterSpend.lockArbiter.active, false)
  }, {
    day: { windows: { workday: [[0, 1440]], restday: [[0, 1440]], outing: [[0, 1440]] } },
    wall: { enabled: true, defaultLock: true, meals: [] },
  })
})

test('[1.6] 默认验收不受 12 次硬干预上限影响', async () => {
  await withHome(async ({ home, post }) => {
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: DAY_TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    writeFileSync(join(home, 'companion', 'hard-interventions.json'), JSON.stringify({
      v: 1,
      day: today,
      count: 12,
      items: Array.from({ length: 12 }, (_, i) => ({ kind: 'lock', source: 'radar', at: Date.now() - i * 1000 })),
    }))

    const p = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 2, screenOn: true },
    })
    assert.equal(p.hardInterventions.capped, true)
    assert.equal(p.force.level, 0, '伴侣/雷达硬干预仍降级为 0')
    assert.equal(p.lockArbiter.source, 'BOUT_STRICT')
    assert.equal(p.lockArbiter.priority, 'P4')
    assert.equal(p.lockArbiter.active, true, '默认验收不因 12 次上限降级')
    assert.equal(p.session?.by, 'verify')
    assert.notEqual(p.lock?.mode, 'free')
  }, {
    day: { windows: { workday: [[0, 1440]], restday: [[0, 1440]], outing: [[0, 1440]] } },
    wall: { meals: [] },
  })
})

test('[1.4] force.release 只在伴侣强制锁刚转为可放行那一拍为 true，下一拍立即回落为 false', async () => {
  await withHome(async ({ post }) => {
    // 1) 未锁定前即使处于 hardCap/emergency 也不乱吐 release: true
    const p0 = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 2, screenOn: true },
    })
    assert.equal(p0.force.release, false)

    // 2) 开启拒绝模式并切到娱乐 App → 伴侣强制锁态 (level=4)
    const t1 = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 2, screenOn: true },
    })
    assert.equal(t1.force.level, 4)
    assert.equal(t1.force.release, false)

    // 3) 紧急通行刚放行那一拍 → release: true
    const ep = await post('/emergency-pass', { reason: '取快递验证码' })
    assert.equal(ep.ok, true)
    const t2 = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 2, screenOn: true },
    })
    assert.equal(t2.force.level, 0)
    assert.equal(t2.force.release, true, '刚转可放行那一拍为 true')

    // 4) 下一拍状态没变 → release 立即回落为 false
    const t3 = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: 2, screenOn: true },
    })
    assert.equal(t3.force.level, 0)
    assert.equal(t3.force.release, false, '下一拍不再重复发 release: true')
  }, {
    verifyMode: { enabled: false },
    refuseUntil: Date.now() + 3_600_000,
  })
})

test('[1.7/4.12] 题库导入预览（含 BOM / 标签块 / 分块保留 uploadId / 错误附 sample）', async () => {
  await withHome(async ({ home, post }) => {
    // 失败时附带 sample
    const bad = await post('/bank', { action: 'import', raw: '这是一段完全没有题号的废话', previewOnly: true })
    assert.equal(bad.ok, false)
    assert.equal(bad.count, 0)
    assert.ok(bad.sample && bad.sample.length > 20, '失败返回体必须带标准格式 sample')

    // 小文本预览：不写库，标出可疑问题
    const tagRaw = '\uFEFF' + [
      '【题型】言语理解',
      '【题目】（2024江苏A 29）这是一道完整题干用于测试标签块导入预览。',
      'A. 选项甲',
      'B. 选项乙',
      '【答案】A',
      '【解析】这里有解析',
      '---',
      '【题目】太短',
      'A. 只有一项',
      '【答案】C',
    ].join('\n')
    const prev = await post('/bank', { action: 'import', raw: tagRaw, previewOnly: true })
    assert.equal(prev.ok, true)
    assert.equal(prev.previewOnly, true)
    assert.equal(prev.format, 'tag-blocks')
    assert.equal(prev.count, 2)
    assert.equal(prev.total, 2)
    assert.ok(prev.issueCount >= 1)
    assert.equal(prev.items[1].issues.length > 0, true)
    assert.equal(existsSync(join(home, 'companion', 'bank.json')), false, 'previewOnly 不得写库')

    // 分块预览：import-finish(previewOnly:true) 保留临时目录，再次 previewOnly:false 才写库并清理
    const beg = await post('/bank', { action: 'import-begin', uploadId: 'u-prev-1' })
    assert.equal(beg.ok, true)
    await post('/bank', { action: 'import-chunk', uploadId: 'u-prev-1', index: 0, text: tagRaw.slice(0, 40) })
    await post('/bank', { action: 'import-chunk', uploadId: 'u-prev-1', index: 1, text: tagRaw.slice(40) })

    const cPrev = await post('/bank', { action: 'import-finish', uploadId: 'u-prev-1', previewOnly: true })
    assert.equal(cPrev.ok, true)
    assert.equal(cPrev.previewOnly, true)
    assert.equal(cPrev.uploadId, 'u-prev-1')
    assert.equal(cPrev.count, 2)
    assert.equal(existsSync(join(home, 'companion', 'bank-upload', 'u-prev-1')), true, 'previewOnly:true 不得删临时目录')
    assert.equal(existsSync(join(home, 'companion', 'bank.json')), false)

    const cCommit = await post('/bank', { action: 'import-finish', uploadId: 'u-prev-1', name: '分块题库', previewOnly: false })
    assert.equal(cCommit.ok, true)
    assert.equal(cCommit.importedCount, 2)
    assert.equal(existsSync(join(home, 'companion', 'bank-upload', 'u-prev-1')), false, '正式入库后清理临时目录')
  }, { verifyMode: { enabled: false } })
})

test('[4.9/4.10/4.13] sealsRoute 收件箱 & 早上点头卡 & bridgeSnapshot.inbox & /plan 局标识', async () => {
  await withHome(async ({ home, post }) => {
    writeFileSync(join(home, 'companion', '模板.md'), '## 工作日（09:00 自动套）\n- 23:58 晚到的一套 · 题库 15 题 · 保底 5\n\n## 休息日（09:00 自动套）\n- 23:58 晚到的一套 · 题库 15 题 · 保底 5\n')
    // 1) 收件箱（未配 vaultKb 时自动落到 home()）
    const a1 = await post('/seals', { action: 'inbox_add', text: '资料分析增长量比较总是算错', tag: '卡壳' })
    assert.equal(a1.ok, true)
    assert.equal(a1.entry.tag, '卡壳')
    const a2 = await post('/seals', { action: 'inbox_add', text: '晚上复习一次错题本', tag: '随记' })
    assert.equal(a2.ok, true)

    const list = await post('/seals', { action: 'inbox_list', limit: 10 })
    assert.equal(list.ok, true)
    assert.equal(list.total, 2)
    assert.equal(list.items[0].tag, '卡壳')

    const bundle = await post('/seals', { action: 'inbox_bundle' })
    assert.equal(bundle.ok, true)
    assert.equal(bundle.inboxCount, 2)
    assert.ok(bundle.stuckCount >= 1)
    assert.match(bundle.markdown, /资料分析增长量比较总是算错/)

    // 2) 早上点头卡 get + confirm
    const ng = await post('/seals', { action: 'nod_card_get' })
    assert.equal(ng.ok, true)
    assert.equal(ng.needed, true)
    assert.ok(ng.items.length >= 1)

    const first = ng.items[0]
    const nc = await post('/seals', {
      action: 'nod_card_confirm',
      items: [{ id: first.id, stretch: 25, floor: 12, dueMinute: 23 * 60 + 50 }],
    })
    assert.equal(nc.ok, true)
    assert.ok(nc.added.length >= 1)
    assert.equal(nc.added[0].stretch, 25)
    assert.equal(nc.added[0].floor, 12)

    const ngAfter = await post('/seals', { action: 'nod_card_get' })
    assert.equal(ngAfter.needed, false)

    // 3) bridgeSnapshot 含 inbox 摘要
    let capturedSnap = null
    const oldFetch = globalThis.fetch
    globalThis.fetch = async (_url, init) => {
      capturedSnap = JSON.parse(String(init?.body || '{}')).snapshot
      return { ok: true, status: 200, text: async () => JSON.stringify({ ok: true, commands: [] }) }
    }
    try {
      const bRes = await post('/gemini-bridge', { action: 'config', url: 'http://127.0.0.1:19999', key: 'test-key' })
      assert.equal(bRes.synced, true)
      assert.ok(capturedSnap?.inbox, 'bridgeSnapshot 应包含 inbox 摘要')
      assert.equal(capturedSnap.inbox.count, 2)
      assert.equal(capturedSnap.inbox.stuckCount, 1)
      await post('/gemini-bridge', { action: 'forget' })
    } finally {
      globalThis.fetch = oldFetch
    }

    // 4) 开局后 /plan 附带 boutId
    const started = await post('/bouts', {
      action: 'start',
      protocolId: 'execute',
      title: '测试局标识',
      allow: ['md.obsidian'],
      strict: true,
    })
    assert.equal(started.ok, true)
    const p = await post('/plan', {
      roll: 0,
      timeZone: DAY_TZ,
      phone: { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 1, screenOn: true },
    })
    assert.ok(p.boutId && p.boutId.length > 0, '/plan 返回应包含 boutId')
    assert.equal(p.bout?.id, p.boutId)
    assert.equal(p.session?.boutId, p.boutId)
    await post('/bouts', { action: 'finish' })
  }, { verifyMode: { enabled: false } })
})

test('[1.1/4.8] 锁屏复习卡 rc: 判题防连点 + 达标返回 earnedOnly/needSpend 并带下一题', async () => {
  await withHome(async ({ home, post }) => {
    writeFileSync(join(home, 'companion', 'bank.json'), JSON.stringify({
      题库版本: 1,
      id: 'b-rc',
      名称: '测试题库',
      题目: [{ id: 'q-next', 题干: '题库下一题完整题干内容', 选项: ['甲', '乙'], 答案: 'A', 解析: '解析' }],
    }))
    writeFileSync(join(home, 'companion', 'review-cards.json'), JSON.stringify({
      v: 1,
      items: [{
        id: 'rc-1',
        source: 'gemini_mcp',
        topic: '常识',
        q: '复习卡第一题',
        a: '甲',
        options: ['甲', '乙'],
        answerIndex: 0,
        counts: false,
        sealId: '',
        day: '',
        createdAt: Date.now() - 20 * 3_600_000,
        fsrs: { state: 0, due: Date.now() - 60_000, stability: 0, difficulty: 0, reps: 0, lapses: 0, lastReview: 0 },
        history: [],
      }],
    }))
    await post('/bank', { action: 'set_config', requiredCount: 1, unlockMinutes: 10 })
    const s = await post('/bank', { action: 'quiz_session' })
    assert.equal(s.question.id, 'rc:rc-1')

    // 错题号防连点忽略
    const dup = await post('/bank', { action: 'quiz_answer', questionId: 'rc:wrong-id', answer: 'A' })
    assert.equal(dup.duplicate, true)
    assert.equal(dup.ignored, true)

    // 正常答对达标：不直接解锁，改为只挣余额并带下一题
    const ok = await post('/bank', { action: 'quiz_answer', questionId: 'rc:rc-1', answer: 'A' })
    assert.equal(ok.ok, true)
    assert.equal(ok.review, true)
    assert.equal(ok.correct, true)
    assert.equal(ok.unlocked, false)
    assert.equal(ok.roundComplete, true)
    assert.equal(ok.earnedOnly, true)
    assert.equal(ok.needSpend, true)
    assert.equal(ok.earnedAdded, 10)
    assert.equal(ok.question?.id, 'q-next')
  }, { verifyMode: { enabled: false } })
})

test('[P0 e2e.mjs:53] 工作窗默认验收激活时，game(40) 触发伴侣锁并在讲通理由获 graceUntil 后正确发一次 release: true', async () => {
  await withHome(async ({ home, get, post, h, sis, big }) => {
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: DAY_TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    // 预留结转额度，避免 40m 超额接管掩盖工作窗默认验收（BOUT_STRICT）
    writeFileSync(join(home, 'companion', 'fun-wallet.json'), JSON.stringify({ v: 1, day: today, carryIn: 60, lastLeft: 60 }))
    const game = (minutes) => ({
      app: 'com.tencent.tmgp.sgame',
      label: '王者荣耀',
      minutes,
      fun: true,
      screenOn: true,
      local: '15:00',
      today: [{ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes, fun: true }],
    })

    // 1) 工作窗默认验收激活 (lockArbiter.active === true) 且游戏 40 分钟触发伴侣力度锁 (level>=3)
    const p1 = await post('/plan', { roll: 100, timeZone: DAY_TZ, phone: game(40) })
    assert.equal(p1.lockArbiter.active, true, '工作窗默认验收激活')
    assert.equal(p1.lockArbiter.source, 'BOUT_STRICT')
    assert.equal(p1.force.name, 'lock')
    assert.equal(p1.force.level >= 3, true)
    assert.equal(p1.force.release, false)

    // 2) 发球并模拟一轮会话交互，用户合理反驳获得 graceUntil > now
    const reqId = 'req-p0-grace'
    const reqRes = await post('/request', {
      v: 1,
      requestId: reqId,
      eventId: 'evt-p0-grace',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
      phone: game(40),
    })
    assert.equal(reqRes.requestId, reqId)
    const targetSid = big.sent.length ? BIG : SIS
    const targetAgent = big.sent.length ? big : sis
    const m = targetAgent.sent[targetAgent.sent.length - 1]
    assert.ok(m, '发球应投递到对应会话')
    await h.emit(targetSid, 'turn/start', { turn: 1 })
    await h.emit(targetSid, 'user/message', { id: m.id, content: m.content, source: m.source, turn: 1 })
    await h.emit(targetSid, 'assistant/message', { turn: 1, message: { content: [{ type: 'text', text: '怎么又开一把？' }] } })
    await h.emit(targetSid, 'turn/end', { turn: 1, reason: { kind: 'completed' } })
    const res = await get(`/result?requestId=${reqId}&waitMs=0`)
    assert.equal(res.state, 'generated')
    await post('/outcome', { requestId: reqId, stage: 'displayed' })

    // 讲通理由 -> judgeReply 判 pushback -> graceUntil > now
    await h.emit(targetSid, 'user/message', {
      id: 'u-grace-1',
      content: [{ type: 'text', text: '我打这局是因为作业写完了' }],
      source: { kind: 'user' },
    })

    // 3) 下一拍 /plan：work window 仍开启 (lockArbiter.active 仍为 true)，但伴侣力度锁刚因 graceUntil 放开 -> release 必须为 true！
    const p2 = await post('/plan', { roll: 100, timeZone: DAY_TZ, phone: game(45) })
    assert.equal(p2.lockArbiter.active, true, '默认验收工作窗仍在')
    assert.equal(p2.lockArbiter.source, 'BOUT_STRICT')
    assert.equal(p2.force.level <= 1, true, 'graceUntil 期间伴侣力度降回 <=1')
    assert.equal(p2.force.release, true, '即使工作窗激活，伴侣力度锁刚释放仍必须发一次 release: true')

    // 4) 再下一拍两类锁态均无边沿变化 -> release 立刻回落为 false
    const p3 = await post('/plan', { roll: 100, timeZone: DAY_TZ, phone: game(46) })
    assert.equal(p3.force.level <= 1, true)
    assert.equal(p3.force.release, false, '状态维持后 release 立即回落 false')
  }, {
    mode: 'coach',
    judgeProvider: 'mock',
    judgeModel: 'mock',
    day: { windows: { workday: [[0, 1440]], restday: [[0, 1440]], outing: [[0, 1440]] } },
    wall: { meals: [] },
  }, {
    llmStream: async function* () {
      yield { type: 'text-delta', text: '{"verdict":"pushback","why":"作业写完了","next":"deeper"}' }
    },
  })
})

test('[P0 e2e.mjs:53 cold] 冷启动无结转 game(40) 触发接管+伴侣锁，讲通获 graceUntil 后仍正确发一次 release: true', async () => {
  await withHome(async ({ get, post, h, sis, big }) => {
    const game = (minutes) => ({
      app: 'com.tencent.tmgp.sgame',
      label: '王者荣耀',
      minutes,
      fun: true,
      screenOn: true,
      local: '15:00',
      today: [{ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes, fun: true }],
    })
    const p1 = await post('/plan', { roll: 100, timeZone: DAY_TZ, phone: game(40) })
    assert.equal(p1.lockArbiter.active, true)
    assert.equal(p1.lockArbiter.source, 'TAKEOVER_STRICT')
    assert.equal(p1.force.name, 'lock')
    assert.equal(p1.force.level >= 3, true)
    assert.equal(p1.force.release, false)

    const reqId = 'req-p0-cold-grace'
    const reqRes = await post('/request', {
      v: 1,
      requestId: reqId,
      eventId: 'evt-p0-cold-grace',
      kind: 'random_chat',
      payload: {},
      deadlineMs: 30000,
      phone: game(40),
    })
    assert.equal(reqRes.requestId, reqId)
    const targetSid = big.sent.length ? BIG : SIS
    const targetAgent = big.sent.length ? big : sis
    const m = targetAgent.sent[targetAgent.sent.length - 1]
    assert.ok(m, '发球应投递到对应会话')
    await h.emit(targetSid, 'turn/start', { turn: 1 })
    await h.emit(targetSid, 'user/message', { id: m.id, content: m.content, source: m.source, turn: 1 })
    await h.emit(targetSid, 'assistant/message', { turn: 1, message: { content: [{ type: 'text', text: '打太久了。' }] } })
    await h.emit(targetSid, 'turn/end', { turn: 1, reason: { kind: 'completed' } })
    assert.equal((await get(`/result?requestId=${reqId}&waitMs=0`)).state, 'generated')
    await post('/outcome', { requestId: reqId, stage: 'displayed' })
    await h.emit(targetSid, 'user/message', {
      id: 'u-grace-cold',
      content: [{ type: 'text', text: '我打这局是因为作业写完了' }],
      source: { kind: 'user' },
    })

    const p2 = await post('/plan', { roll: 100, timeZone: DAY_TZ, phone: game(45) })
    assert.equal(p2.lockArbiter.active, true)
    assert.equal(p2.force.level <= 1, true)
    assert.equal(p2.force.release, true)

    const p3 = await post('/plan', { roll: 100, timeZone: DAY_TZ, phone: game(46) })
    assert.equal(p3.force.level <= 1, true)
    assert.equal(p3.force.release, false)
  }, {
    mode: 'coach',
    judgeProvider: 'mock',
    judgeModel: 'mock',
    day: { windows: { workday: [[0, 1440]], restday: [[0, 1440]], outing: [[0, 1440]] } },
    wall: { meals: [] },
  }, {
    llmStream: async function* () {
      yield { type: 'text-delta', text: '{"verdict":"pushback","why":"作业写完了","next":"deeper"}' }
    },
  })
})

test('[locksOverview & recentActions & jsonl compaction] 全量 8 锁大盘、双鱼限制拼多多轨迹、GET /today 与 >512KB 日志压缩', async () => {
  await withHome(async ({ home, get, post, h, big }) => {
    const today = new Intl.DateTimeFormat('en-CA', { timeZone: DAY_TZ, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
    writeFileSync(join(home, 'companion', 'fun-wallet.json'), JSON.stringify({ v: 1, day: today, carryIn: 45, lastLeft: 45 }))
    writeFileSync(join(home, 'companion', 'seals.json'), JSON.stringify({
      v: 1,
      items: [
        { id: 's-slot-2', day: today, title: '下午申论写作', kind: 'file', path: '学习/申论.md', stretch: 800, floor: 400, startMinute: 14 * 60, dueMinute: 16 * 60, status: 'open', actual: 0 },
        { id: 's-slot-1', day: today, title: '上午行测 20 题', kind: 'quiz', stretch: 20, floor: 10, startMinute: 9 * 60, dueMinute: 11 * 60, status: 'open', actual: 6 },
      ],
    }))

    // 触发双鱼工具：鲸澜用 app_tier 把拼多多改严 + block_apps 封禁拼多多
    const tierRes = h.globalTools.app_tier.execute(
      { app: '拼多多', tier: 'fun', why: '白天老刷百亿补贴' },
      { agent: { session: { id: BIG } } },
    )
    assert.equal(tierRes.ok, true)
    const blkRes = big.tools.block_apps.execute({
      apps: ['com.xunmeng.pinduoduo'],
      hours: 2,
      reason: '限制拼多多防冲动逛街',
    })
    assert.equal(blkRes.ok, true)

    // 校验 /seals action=locks_overview 与 GET /today 返回完全一致的大盘字段
    const loRes = await post('/seals', { action: 'locks_overview' })
    assert.equal(loRes.ok, true)
    const ov = loRes.locksOverview
    assert.ok(ov)
    assert.equal(typeof ov.activeLock.active, 'boolean')
    assert.equal(typeof ov.activeLock.verifyOverdue, 'boolean')
    assert.equal(ov.allLocks.length, 8)
    assert.deepEqual(
      ov.allLocks.map((x) => x.priority),
      ['P0', 'P1', 'P2', 'P3', 'P3.5', 'P4', 'P5', 'P6'],
    )
    assert.equal(ov.wallet.carryInMin, 45)
    assert.equal(ov.wallet.earnedBankCap, 60)
    assert.equal(ov.wallet.daytimeCapMin, 30)
    assert.equal(ov.wallet.eveningCapMin, 120)
    assert.equal(ov.wallet.lockShadow, true, '红线：config.lockShadow 默认保持 true')

    // scheduleSlots 按 startMinute 升序排列
    assert.equal(ov.scheduleSlots.length, 2)
    assert.equal(ov.scheduleSlots[0].id, 's-slot-1')
    assert.equal(ov.scheduleSlots[0].startHm, '09:00')
    assert.equal(ov.scheduleSlots[0].dueHm, '11:00')
    assert.equal(ov.scheduleSlots[1].id, 's-slot-2')

    // recentActions 包含可读的双鱼限制拼多多轨迹
    const blockAct = ov.recentActions.find((a) => a.action === '临时封禁' && a.target.includes('拼多多'))
    const tierAct = ov.recentActions.find((a) => a.action === '改严为娱乐' && a.target.includes('拼多多'))
    assert.ok(blockAct, '应包含封禁拼多多可读轨迹')
    assert.equal(blockAct.actor, '鲸澜')
    assert.match(blockAct.reason, /限制拼多多/)
    assert.ok(tierAct, '应包含改严拼多多分层轨迹')
    assert.equal(tierAct.actor, '鲸澜')

    // GET /today 同步返回 locksOverview
    const todayRes = await get('/today')
    assert.ok(todayRes.locksOverview)
    assert.equal(todayRes.locksOverview.allLocks.length, 8)

    // seals.jsonl & ctx-budget.jsonl 超过 512KB 自动压缩保留最新 300 行
    const bigLine = JSON.stringify({ at: Date.now(), role: 'companion', chars: 500, tokens: 120, kept: ['persona', 'pad-' + 'x'.repeat(180)], dropped: [] }) + '\n'
    const countNeed = Math.ceil((560 * 1024) / Buffer.byteLength(bigLine, 'utf8'))
    const payload = bigLine.repeat(countNeed)
    writeFileSync(join(home, 'companion', 'ctx-budget.jsonl'), payload)
    writeFileSync(join(home, 'companion', 'seals.jsonl'), payload)

    // 触发一次人设上下文渲染与封印写入
    big.contexts[0].text()
    await post('/seals', { action: 'add', seal: { title: '触发日志压缩测试', kind: 'screenshots', stretch: 2, dueMinute: 1439, target: 'tomorrow' } })

    const budgetLines = readFileSync(join(home, 'companion', 'ctx-budget.jsonl'), 'utf8').split('\n').filter(Boolean)
    const sealLines = readFileSync(join(home, 'companion', 'seals.jsonl'), 'utf8').split('\n').filter(Boolean)
    assert.equal(budgetLines.length, 301, 'ctx-budget.jsonl 压缩为 300 条旧行 + 1 条新行')
    assert.equal(sealLines.length, 301, 'seals.jsonl 压缩为 300 条旧行 + 1 条新行')
    assert.ok(statSync(join(home, 'companion', 'ctx-budget.jsonl')).size < 200 * 1024)
    assert.ok(statSync(join(home, 'companion', 'seals.jsonl')).size < 200 * 1024)
  }, { verifyMode: { enabled: false } })
})
