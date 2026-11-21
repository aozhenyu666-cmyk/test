// 超级验收者接线：工作时段默认验收（只放常用 App）、花余额让路、到点自动套模板、晚间上限用完锁到 7 点。

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
const ZONES = ['Asia/Shanghai', 'Europe/London', 'America/Sao_Paulo', 'America/New_York', 'America/Chicago', 'America/Denver',
  'America/Los_Angeles', 'America/Anchorage', 'Pacific/Honolulu', 'Asia/Tokyo', 'Europe/Berlin', 'Europe/Moscow', 'Asia/Kolkata',
  'Asia/Dubai', 'Australia/Sydney', 'Pacific/Auckland', 'Atlantic/Azores', 'America/Halifax']
/** 白天这几条用一个此刻当地是白天（9:00–17:00）的时区跑：CI 什么时候跑都不跳过。 */
const TZ = ZONES.find((z) => { const m = minuteOfDayOf(Date.now(), z); return m >= 9 * 60 && m < 17 * 60 }) || 'Asia/Shanghai'
function minuteOfDayOf(now, tz) {
  const p = new Intl.DateTimeFormat('en-GB', { timeZone: tz, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(now))
  return Number(p.find((x) => x.type === 'hour').value) * 60 + Number(p.find((x) => x.type === 'minute').value)
}

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

async function withHome(fn, extra = {}) {
  const home = mkdtempSync(join(tmpdir(), 'companion-verify-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, timeZone: TZ, lockTimeZone: false, quietStart: 12, quietEnd: 12, ...extra }))
    const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    const seals = (body) => call(h(), 'POST', '/seals', body).then((r) => r.body)
    return await fn({ home, seals, h })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}

const json = (home, name) => JSON.parse(readFileSync(join(home, 'companion', name), 'utf8'))
const ALL_DAY = { day: { windows: { workday: [[0, 1440]], restday: [[0, 1440]], outing: [[0, 1440]] } } }
const plan = (h, phone) => call(h(), 'POST', '/plan', { roll: 0, timeZone: TZ, phone })

test('工作时段里什么都不说：/plan 下发「默认验收」会话，只放常用 App；花余额期间让路', async () => {
  await withHome(async ({ h, seals, home }) => {
    const r = await plan(h, { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 3, screenOn: true })
    const s = r.body.session
    assert.ok(s, JSON.stringify(r.body).slice(0, 200))
    assert.equal(s.topic, '默认验收')
    assert.ok(s.allow.includes('md.obsidian'))
    assert.ok(s.allow.includes('com.google.android.apps.bard'), 'Gemini 常驻')
    assert.ok(!s.allow.includes('com.ss.android.ugc.aweme'))
    const list = await seals({ action: 'list' })
    assert.equal(list.verify.active, true)
    // 没余额：花不了
    const poor = await seals({ action: 'spend', minutes: 20, reason: '看一集纪录片放松一下' })
    assert.equal(poor.ok, false)
    // 有余额（昨天结转 60）：说了理由能花，默认验收让路
    writeFileSync(join(home, 'companion', 'fun-wallet.json'), JSON.stringify({ v: 1, day: list.today, carryIn: 60, lastLeft: 60 }))
    const ok = await seals({ action: 'spend', minutes: 20, reason: '看一集纪录片放松一下' })
    assert.equal(ok.ok, true, JSON.stringify(ok).slice(0, 200))
    const r2 = await plan(h, { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 4, screenOn: true })
    assert.notEqual(r2.body.session?.topic, '默认验收')
    assert.equal((await seals({ action: 'list' })).verify.spend.minutes, 20)
  }, ALL_DAY)
})

test('关默认验收：明天才生效，今天照旧', async () => {
  await withHome(async ({ seals }) => {
    const off = await seals({ action: 'verify-off' })
    assert.match(off.text, /明天起/)
    assert.equal(off.verify.enabled, true)
    assert.equal(off.verify.offFrom, off.tomorrow)
  }, ALL_DAY)
})

test('到点没定今天：自动套模板（大肥鱼/模板.md 他自己改的那份），来源 template、不打折', async () => {
  await withHome(async ({ h, home }) => {
    writeFileSync(join(home, 'companion', 'bank.json'), JSON.stringify({ 题库版本: 1, id: 'b', 名称: '行测', 题目: [{ id: 'q-1', 题干: '题', 选项: ['甲', '乙'], 答案: 'A', 解析: '甲' }] }))
    writeFileSync(join(home, 'companion', '模板.md'), '## 工作日（00:00 自动套）\n- 23:58 晚到的一套 · 题库 15 题 · 保底 5\n\n## 休息日（00:00 自动套）\n- 23:58 晚到的一套 · 题库 15 题 · 保底 5\n')
    await plan(h, { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 3, screenOn: true })
    const st = json(home, 'seals.json')
    const t = st.items.find((x) => x.title === '晚到的一套')
    assert.ok(t, JSON.stringify(st).slice(0, 300))
    assert.equal(t.source, 'template')
    assert.equal(t.sameDay, false)
    assert.equal(t.stretch, 15)
    // 同一天不再套第二次
    await plan(h, { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 4, screenOn: true })
    assert.equal(json(home, 'seals.json').items.filter((x) => x.title === '晚到的一套').length, 1)
  }, ALL_DAY)
})

/** 挑一个此刻当地正好是晚上（18:00–22:30）的时区：晚间这条任何时候都真跑，不靠运气。 */
const EVENING_TZ = ZONES.find((z) => { const m = minuteOfDay(Date.now(), z); return m >= 18 * 60 && m < 22 * 60 + 30 })

test('晚间上限：18:00 后娱乐用够了，锁到明早 7 点、余额花不出去、约一次 Gemini', async () => {
  assert.ok(EVENING_TZ, '总有一个时区此刻是晚上')
  await withHome(async ({ seals, home }) => {
    const row = (m) => ({ app: 'com.ss.android.ugc.aweme', label: '抖音', fun: true, minutes: m, screenOn: true, today: [{ app: 'com.ss.android.ugc.aweme', label: '抖音', minutes: m, fun: true }] })
    const hh = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    const p = (phone) => call(hh(), 'POST', '/plan', { roll: 0, timeZone: EVENING_TZ, phone })
    await p(row(10))
    await p(row(45))
    let l = await seals({ action: 'list' })
    assert.equal(l.verify.evening.active, true)
    assert.equal(l.verify.evening.used, 35, '白天的 10 分钟不算')
    assert.equal(l.verify.evening.hit, true)
    const r = await p(row(46))
    const kinds = (r.body.rules?.rules || []).map((x) => x.kind)
    assert.ok(kinds.includes('default'), kinds.join(','))
    const rule = r.body.rules.rules.find((x) => x.kind === 'default' && /娱乐 30 分钟用完/.test(x.why))
    assert.ok(rule, '晚间上限那一条')
    assert.equal(new Date(rule.until).toLocaleString('en-GB', { timeZone: EVENING_TZ, hour: '2-digit', minute: '2-digit', hour12: false }), '07:00', '锁到明早 7 点')
    const sp = await seals({ action: 'spend', minutes: 10, reason: '再看一会儿就睡' })
    assert.equal(sp.error, 'evening-cap')
    assert.equal(json(home, 'gemini-schedule.json').by, 'evening-cap')
    // 调高：今晚不变，明晚起
    const up = await seals({ action: 'evening-cap', minutes: 120 })
    assert.match(up.text, /明晚起 120/)
    assert.equal(up.verify.evening.cap, 30)
  }, { eveningCap: { minutes: 30 }, timeZone: EVENING_TZ, lockTimeZone: false })
})

test('题库空着：模板里的题库类不立（立了只能被锁）、手动加也拒，说清楚先导题库', async () => {
  await withHome(async ({ h, home, seals }) => {
    writeFileSync(join(home, 'companion', '模板.md'), '## 工作日（00:00 自动套）\n- 23:58 一套题 · 题库 15 题 · 保底 5\n- 23:58 截图交付 · 截图 1 张 · 保底 1\n\n## 休息日（00:00 自动套）\n- 23:58 一套题 · 题库 15 题 · 保底 5\n- 23:58 截图交付 · 截图 1 张 · 保底 1\n')
    await plan(h, { app: 'md.obsidian', label: 'Obsidian', fun: false, minutes: 3, screenOn: true })
    const items = json(home, 'seals.json').items
    assert.ok(!items.some((x) => x.kind === 'quiz'), '题库类没立')
    assert.ok(items.some((x) => x.title === '截图交付'), '别的照立')
    const add = await seals({ action: 'add', seal: { title: '手动加题', kind: 'quiz', stretch: 10, dueMinute: 1439, target: 'today' } })
    assert.equal(add.error, 'no-bank')
    assert.match(add.text, /先在「题库」页导入/)
  }, ALL_DAY)
})
