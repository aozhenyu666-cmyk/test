// 2026-11 主动性：她们手里有开局、排局、连麦、封 App 的工具；计划草案没内容时鲸澜自己来填；
// 笔记库每轮随手翻一篇；墙交验收时会读新写的内容。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync, existsSync, utimesSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { toolsFor } from '../lib/machine.js'
import { vaultPick } from '../lib/notes.js'
import { eyeCheck, newContent, parseEye } from '../lib/wall_eye.js'
import { minuteOfDay, startOfDay } from '../lib/tz.js'
import { STRATEGIST_CARD, capabilityLines, toolsLine } from '../lib/roles.js'

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

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-fullperm-'))
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
  const vault = join(home, 'vault')
  mkdirSync(vault, { recursive: true })
  writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({
    sessionId: SIS, strategistSessionId: BIG, quietStart: 12, quietEnd: 12, notesPath: vault, ...rest,
  }))
  const sis = agentOf(SIS)
  const big = agentOf(BIG)
  const h = host({ [SIS]: sis, [BIG]: big }, state)
  const plan = (phone) => call(h.handler(), 'POST', '/dsh-go-companion/v1/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone })
  const ledger = () => readFileSync(join(home, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
  const cfg = () => JSON.parse(readFileSync(join(home, 'companion', 'config.json'), 'utf8'))
  return { sis, big, h, plan, ledger, cfg, vault }
}

const wechat = { app: 'com.tencent.mm', label: '微信', minutes: 1, screenOn: true, local: '15:00' }
const TZ = 'Asia/Shanghai'

const goodItem = (at, extra = {}) => ({
  at, protocol: 'execute', minutes: 30, title: '求职局',
  goal: '投出 3 份简历', first: '打开 Boss 直聘，筛上海后端前 10 个', deliverable: '3 张投递成功截图',
  accept_kind: 'evidence', accept_what: '截图时间在开局后、能看到已投递、3 张', need: ['com.hpbr.bosszhipin'], ...extra,
})
const hm = (min) => String(Math.floor(min / 60)).padStart(2, '0') + ':' + String(min % 60).padStart(2, '0')
const soon = (plus) => Math.min(1430, minuteOfDay(Date.now(), TZ) + plus)

test('待命里她们拿得到开局、排局、连麦、封 App、递话', () => {
  const sb = toolsFor('standby')
  for (const n of ['open_bout', 'plan_bouts', 'gemini_live', 'block_apps', 'sister_note', 'dafeiyu_control']) assert.ok(sb.includes(n), n)
  assert.ok(toolsFor('plan').includes('plan_bouts'))
  assert.ok(toolsFor('morning').includes('plan_bouts'))
})

test('人设：可逆的事直接做、不等点头；鲸澜的卡结论先行、话少', () => {
  assert.match(toolsLine(true), /直接动手，不用先问/)
  assert.doesNotMatch(capabilityLines('strategist'), /点头/)
  assert.match(capabilityLines('companion'), /open_bout/)
  assert.match(STRATEGIST_CARD, /先给结论/)
  assert.match(STRATEGIST_CARD, /直接做/)
  assert.ok(STRATEGIST_CARD.length < 1100, '卡要短：' + STRATEGIST_CARD.length)
})

test('plan_bouts：缺 App、第一步不是动作、时间重叠都打回；合格就写进今天的计划、十分钟后生效', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const tool = t.big.tools.plan_bouts
    assert.ok(tool, '鲸澜在待命里有 plan_bouts')
    const a = soon(30)
    assert.equal(tool.execute({ items: [goodItem(hm(a), { need: [] })] }).ok, false)
    const badFirst = tool.execute({ items: [goodItem(hm(a), { first: '好好想想人生' })] })
    assert.equal(badFirst.ok, false)
    assert.match(badFirst.text, /动作|第一步/)
    const overlap = tool.execute({ items: [goodItem(hm(a)), goodItem(hm(Math.min(1435, a + 10)), { title: '第二局' })] })
    assert.equal(overlap.ok, false)
    const ok = tool.execute({ items: [goodItem(hm(a))] })
    assert.equal(ok.ok, true, ok.text)
    assert.match(ok.text, /Boss 直聘/)
    const plan = JSON.parse(readFileSync(join(home, 'companion', 'plan.json'), 'utf8'))
    assert.equal(plan.accepted, false, '交上去先不生效，给他十分钟换')
    assert.equal(plan.items.length, 1)
    assert.equal(plan.items[0].packet.deliverable, '3 张投递成功截图')
    assert.deepEqual(plan.items[0].allow, ['com.hpbr.bosszhipin'], '没写允许应用就等于必需应用')
    assert.ok(t.ledger().some((e) => e.state === 'plan-ai' && e.by === 'strategist'))
  })
})

test('open_bout：她现在就能开一局，第一句就是工作包的第一步；同时只能一局', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const tool = t.sis.tools.open_bout
    assert.ok(tool, '鱼丸在待命里也有 open_bout')
    assert.equal(tool.execute({ protocol: 'nope', first: 'x', deliverable: 'y', need: ['a.b'] }).ok, false)
    const r = tool.execute({ protocol: 'execute', why: '他刷了半小时', goal: '改简历', first: '打开 WPS 改简历第二段', deliverable: '简历.docx 改完', accept_kind: 'evidence', accept_what: '文件变长', need: ['cn.wps.moffice_eng'] })
    assert.equal(r.ok, true, r.text)
    assert.match(r.text, /第一步：打开 WPS 改简历第二段/)
    assert.equal(tool.execute({ protocol: 'execute', first: '打开 WPS 改简历第二段', deliverable: '简历', accept_kind: 'evidence', accept_what: '文件', need: ['cn.wps.moffice_eng'] }).ok, false)
    assert.ok(t.ledger().some((e) => e.state === 'ai-open-bout' && e.ok === true))
  })
})

test('计划草案只有时段没有内容：鲸澜自己来填（一小时最多一次），填过就不再来', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    const now = Date.now()
    const day = startOfDay(now, TZ)
    const m = soon(60)
    writeFileSync(join(home, 'companion', 'plan.json'), JSON.stringify({ v: 1, day, items: [{ id: 'x', protocolId: 'execute', title: '执行局', minute: m, budget: 30 }], edits: 0, accepted: false, at: now }))
    const first = await t.plan(wechat)
    assert.equal(first.body.reason, 'plan-draft', JSON.stringify(first.body))
    const req = await call(t.h.handler(), 'POST', '/dsh-go-companion/v1/request', { v: 1, requestId: 'req-plan-draft-1', eventId: 'evt-pd-1', kind: 'random_chat', payload: {}, deadlineMs: 30000 })
    assert.equal(req.status, 202, JSON.stringify(req.body))
    const ball = t.big.sent.at(-1)?.content?.[0]?.text || ''
    assert.match(ball, /plan_bouts/)
    assert.match(ball, /不用问他/)
    const again = await t.plan(wechat)
    assert.notEqual(again.body.reason, 'plan-draft', '一小时内不再来')
    const done = t.big.tools.plan_bouts.execute({ items: [goodItem(hm(m))] })
    assert.equal(done.ok, true, done.text)
  })
})

test('笔记库：随手翻一篇进人设（系统产出不翻、太短的不翻），新旧都有机会', async () => {
  await withHome(async (home) => {
    const t = setup(home)
    mkdirSync(join(t.vault, '大肥鱼', '每日'), { recursive: true })
    writeFileSync(join(t.vault, '大肥鱼', '每日', '2026-11-15.md'), '系统写的每日文件'.repeat(10))
    writeFileSync(join(t.vault, '短.md'), '太短')
    writeFileSync(join(t.vault, '考研还是工作.md'), '# 考研还是工作\n我现在倾向工作，但是数学一直是短板，如果春招没结果就转考研，最晚三月要决定。')
    const old = join(t.vault, '吉他.md')
    writeFileSync(old, '想学吉他，先买一把入门的，每天练二十分钟和弦转换，坚持一个月看看手感。')
    const ago = (Date.now() - 60 * 86_400_000) / 1000
    utimesSync(old, ago, ago)
    const seen = new Set()
    for (let seed = 0; seed < 6; seed++) seen.add(vaultPick(t.vault, { seed })?.rel)
    assert.ok(seen.has('考研还是工作.md') && seen.has('吉他.md'), [...seen].join(','))
    assert.ok(!seen.has('短.md') && ![...seen].some((r) => r && r.startsWith('大肥鱼/')))
    const text = t.big.contexts.map((c) => c.text()).join('\n')
    assert.match(text, /笔记库里翻到的/)
  })
})

test('墙的眼睛：判不过必须引得出原文；引不出就按代码结论；只读立项后新写的那段', async () => {
  const body = '今天投了三家：字节、美团、小红书，第二段改成了项目经历。'
  assert.deepEqual(parseEye('{"pass":false,"quote":"随便写写","why":"凑字数"}', body).pass, true, '引不到原文的「不过」不算')
  const f = parseEye('前面废话 {"pass":false,"quote":"第二段改成了项目经历","why":"只改了一句，没投"} 后面', body)
  assert.equal(f.pass, false)
  assert.equal(f.quote, '第二段改成了项目经历')
  assert.equal(parseEye('不是 JSON', body), null)
  const dir = mkdtempSync(join(tmpdir(), 'eye-'))
  try {
    const p = join(dir, '周报.md')
    writeFileSync(p, '旧内容旧内容\n' + body)
    assert.equal(newContent(p, Buffer.byteLength('旧内容旧内容\n')), body)
    async function* say(text) { yield { type: 'text-delta', text } }
    const ok = await eyeCheck({ stream: () => say('{"pass":true,"quote":"投了三家","why":"在做"}'), provider: 'p', model: 'm', task: '投简历', evidence: p, text: body })
    assert.equal(ok.judged, true)
    assert.equal(ok.pass, true)
    const none = await eyeCheck({ stream: undefined, provider: '', model: '', task: 't', evidence: 'e', text: body })
    assert.equal(none.judged, false, '没模型就不读，按代码结论')
    const slow = await eyeCheck({ stream: async function* () { await new Promise((r) => setTimeout(r, 200)); yield { type: 'text-delta', text: '{}' } }, provider: 'p', model: 'm', task: 't', evidence: 'e', text: body, timeoutMs: 20 })
    assert.equal(slow.judged, false)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
