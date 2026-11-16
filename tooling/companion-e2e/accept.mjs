// 功能验收：每一个做过的插件功能，在真引擎上按手机的调用方式走一遍，核对效果和这一球真正带进 prompt 的内容。
import { readFileSync, writeFileSync, mkdirSync, appendFileSync, rmSync } from 'node:fs'
import { DatabaseSync } from 'node:sqlite'
import { rpc, route, sleep, ledger, waitResult, prompt } from './app.mjs'
const W = process.env.E2E_WORK
const H = W + '/home'
let failed = 0
function check(name, ok, detail) {
  console.log((ok ? '  ✓ ' : '  ✗ ') + name + (ok ? '' : '  ← ' + String(typeof detail === 'string' ? detail : JSON.stringify(detail) ?? '').slice(0, 400)))
  if (!ok) failed += 1
}
async function waitFor(fn, ms = 15000) { const end = Date.now() + ms; while (Date.now() < end) { const v = fn(); if (v) return v; await sleep(200) } return null }
function prompts() { try { return readFileSync(W + '/prompts.jsonl', 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) } catch { return [] } }
const lastServePrompt = () => prompts().filter((p) => p.kind === 'serve').pop()?.text || ''
async function newSession(cfg = {}) {
  const c = await rpc('session/create', { request: { cwd: W } })
  const sid = c.body?.result?.value?.sessionId
  await route('POST', '/config', { sessionId: sid, mode: 'partner', drills: false, muteUntil: 0, quietStart: 3, quietEnd: 3, minGapMs: 60_000, ...cfg })
  return sid
}
async function serve(phone, label) {
  const id = 'req-' + label + '-' + Date.now()
  const r = await route('POST', '/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 45000, phone })
  const res = r.status === 202 ? await waitResult(id) : null
  return { id, status: r.status, body: r.body, res, accepted: ledger().find((e) => e.requestId === id && e.state === 'accepted') }
}
async function say(sid, text) {
  const before = ledger().length
  await prompt(sid, text)
  // 等这句话的那一轮跑完（假模型 1.5 秒）再往下走
  await waitFor(() => readFileSync(W + '/llm.log', 'utf8').includes(text.slice(0, 8)), 10000)
  await sleep(2200)
  return ledger().slice(before)
}
/** 每段开始前清掉按"人"算的状态（账本、大脑、脾气）：额度、宽限、糊弄连击都是跨会话的，不清会互相干扰。 */
function reset() {
  for (const f of ['ledger.jsonl', 'brain.json', 'temper.json']) rmSync(H + '/companion/' + f, { force: true })
}
// 15:00：避开第 8 节作息的早上门（5–12 点）、晚上对账（21:30 起）和深夜锁（0:30–7 点）。
const app = (pkg, label, minutes, fun, extra = {}) => ({ app: pkg, label, minutes, fun, screenOn: true, local: '15:00', today: fun ? [{ app: pkg, label, minutes, fun }] : [], ...extra })
const bili = (m) => app('tv.danmaku.bili', '哔哩哔哩', m, true)
const game = (m) => app('com.tencent.tmgp.sgame', '王者荣耀', m, true)
const wechat = () => app('com.tencent.mm', '微信', 1, false)
/**
 * 第 8 节的作息会盖过力度：0:30–7:00 娱乐锁死、5:00–10:00 早上开门。
 * 测力度 / 阶梯时挑一个现在正好是 11–20 点的时区，免得 CI 跑到半夜就挂。
 */
function dayZone() {
  for (let off = -12; off <= 14; off += 1) {
    const name = off === 0 ? 'UTC' : off > 0 ? 'Etc/GMT-' + off : 'Etc/GMT+' + (-off)
    const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: name, hour: 'numeric', hourCycle: 'h23' }).format(new Date()))
    if (hour >= 11 && hour < 20) return name
  }
  return 'UTC'
}

// ---------------------------------------------------------------- A. 一球里到底带了什么
console.log('【A. 发球内容（单人格）】')
reset()
// 第三轮 D3 的笔记库夹具（$WORK/home/vault-e2e，notesPath 由 run.sh 在引擎启动前写好）——
// 插件的笔记库路径缓存 10 分钟，这里再改设置读不到。
let sid = await newSession({ dual: false })
{
  // 记忆：往记忆库钉一条事实（用记忆插件自己的表结构）
  const db = new DatabaseSync(H + '/fish-memory/fish-memory.sqlite')
  const cols = db.prepare('PRAGMA table_info(facts)').all().map((c) => c.name)
  const row = { id: 'fact_accept_cat', scope: 'global', text: '我养的猫叫毛毛（Maomao）。', importance: 5, pinned: 1, status: 'active', created_at: Date.now(), updated_at: Date.now(), key: '我的猫.名字', source: 'accept', hits: 0 }
  const use = cols.filter((c) => c in row)
  db.prepare(`INSERT OR REPLACE INTO facts (${use.join(',')}) VALUES (${use.map(() => '?').join(',')})`).run(...use.map((c) => row[c]))
  db.close()
  // 心情
  mkdirSync(H + '/feiyu-mood', { recursive: true })
  writeFileSync(H + '/feiyu-mood/state.json', JSON.stringify({ moods: { main: { mood: 41 } }, lastActive: 'main' }))
}
let s = await serve(bili(25), 'content')
check('发球成功', s.res?.state === 'generated', s.body)
let p = lastServePrompt()
check('带上现在开着的 App 和时长', /现在开着：哔哩哔哩，已经开了 25 分钟/.test(p), p.slice(0, 200))
check('不暴露包名', !/tv\.danmaku/.test(p), '')
check('带上记忆里的事实（毛毛）', /毛毛/.test(p), p)
check('带上心情（只影响语气）', /心情值 41\/100/.test(p), '')
check('关系库人格卡进了 prompt（底线）', /你的底线（绝不做）：.*不撒谎/.test(p), p.match(/你的.{0,80}/g))
check('有这一球的脸色', /这一球的脸色：/.test(p), '')
check('约定回话那一轮先听懂理由', /先把理由弄懂/.test(p), '')
check('做题默认关：不出题', !/思维题/.test(p) && s.accepted?.serveType !== 'drill', s.accepted)
check('账本记下水位/脸色', Number.isFinite(s.accepted?.tide) && !!s.accepted?.face, s.accepted)

// ---------------------------------------------------------------- A2. 姐妹双人格
console.log('【A2. 姐妹双人格】')
reset()
{
  const sis = (await rpc('session/create', { request: { cwd: W } })).body?.result?.value?.sessionId
  const big = (await rpc('session/create', { request: { cwd: W } })).body?.result?.value?.sessionId
  await route('POST', '/config', { sessionId: sis, strategistSessionId: big, dual: true, mode: 'partner', drills: false, muteUntil: 0, quietStart: 3, quietEnd: 3, minGapMs: 60_000 })
  await sleep(300)
  const s2 = await serve(bili(25), 'dual')
  const bp = lastServePrompt()
  check('主动发球由妹妹说（朋友，不是助手）', /你是鱼丸——他的朋友，不是助手/.test(bp), bp.slice(0, 200))
  check('妹妹发球不劝做事、不讲道理', /别劝他"该去做事了"/.test(bp) && !/思维教练/.test(bp), '')
  check('妹妹也拿到手机现状', /哔哩哔哩，已经开了 25 分钟/.test(bp), '')
  void s2
  // 只看回这句话的那一次（引擎还会顺手调一次模型起会话标题，那次不带人设）
  let said = ''
  const sysOf = () => prompts().filter((x) => x.kind === 'chat' && x.text.includes(said) && !/session title|concise title/i.test(x.text)).pop()?.all || ''
  said = '在吗，陪我聊会'
  const a = await route('POST', '/say', { v: 1, requestId: 'say-sis-' + Date.now(), text: said })
  await waitResult(a.body?.requestId)
  check('跟妹妹聊：每轮带着她的人设', /你是鱼丸/.test(sysOf()) && /不碰正事/.test(sysOf()), sysOf().slice(0, 200))
  said = '我该考研还是工作'
  const b = await route('POST', '/say', { v: 1, requestId: 'say-big-' + Date.now(), text: said, role: 'strategist' })
  await waitResult(b.body?.requestId)
  check('跟姐姐聊：每轮带着谋士人设（往前推两步）', /谋士/.test(sysOf()) && /往前推两步/.test(sysOf()), sysOf().slice(0, 200))
  check('姐姐的人设并入了关系库底线', /不撒谎/.test(sysOf()), '')
  // 测完换回 A 段的单人格会话，后面几段接着用它
  await route('POST', '/config', { sessionId: sid, strategistSessionId: null, dual: false })
}

// ---------------------------------------------------------------- B. 一句话指令
console.log('【B. 一句话指令】')
await say(sid, '安静一小时')
let pl = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: bili(30) })
check('"安静一小时" → /plan 不开口（muted）', pl.body.reason === 'muted', pl.body)
await route('POST', '/config', { muteUntil: 0 })
await say(sid, '切到严格模式')
let st = await route('GET', '/status')
check('"切到严格模式" → 教练模式', st.body.config?.mode === 'coach', st.body.config)
await say(sid, '温柔点')
st = await route('GET', '/status')
check('"温柔点" → 伙伴模式', st.body.config?.mode === 'partner', st.body.config)
await say(sid, '开始做题')
st = await route('GET', '/status')
check('"开始做题" → 做题打开', st.body.config?.drills === true, st.body.config)

// ---------------------------------------------------------------- C. 做题与"说他"
console.log('【C. 做题与说他】')
reset()
await route('POST', '/config', { mode: 'coach' })
let d1 = await serve(game(35), 'drill1')
p = lastServePrompt()
check('在游戏里 + 做题开 + 要锁 → 这一球是思维题', d1.accepted?.serveType === 'drill' && /思维题/.test(p), d1.accepted || d1.body)
check('思维题带合格线', /合格的回答必须是/.test(p), '')
const drill = d1.accepted?.drill
await say(sid, '挺久的吧')
const vr = ledger().find((e) => e.requestId === d1.id && e.state === 'replied')
check('糊弄的回答按合格线判成没过（brief）', vr?.verdict === 'brief', vr)
const judgeIn = prompts().filter((x) => x.kind === 'judge').pop()?.text || ''
check('判球时带上了合格线', /合格的回答必须是/.test(judgeIn), judgeIn.slice(0, 200))
let d2 = await serve(game(38), 'drill2')
p = lastServePrompt()
check('下一球先说他（引用他的原话、没过线）', /挺久的吧.*没过线/s.test(p), p.slice(0, 300))
check('同一类题重问', d2.accepted?.drill === drill, { before: drill, after: d2.accepted?.drill })
await say(sid, '别出题了')
st = await route('GET', '/status')
check('"别出题了" → 做题关闭', st.body.config?.drills === false, st.body.config)

// ---------------------------------------------------------------- D. 大脑：拉回来没有
console.log('【D. 大脑】')
reset()
sid = await newSession({ mode: 'partner' })
const pull = await serve(game(20), 'pull')
check('发球（在游戏里）', pull.res?.state === 'generated', pull.body)
console.log('    …等 31 秒（发出去 30 秒内离开不算数）')
await sleep(31_000)
await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: wechat() })
const pulled = ledger().filter((e) => e.state === 'pulled').pop()
check('切到微信 → 记一次"拉回来了"', pulled?.pulled === true, pulled)
const brain = JSON.parse(readFileSync(H + '/companion/brain.json', 'utf8'))
check('这张脸记了一分', Object.values(brain.faces || {}).some((c) => c.win >= 1), brain.faces)

// ---------------------------------------------------------------- E. 阶梯全程（时间注入）
console.log('【E. 阶梯】')
reset()
sid = await newSession({ mode: 'partner', minGapMs: 30 * 60_000 })
const inject = (rows) => appendFileSync(H + '/companion/ledger.jsonl', rows.map((r) => JSON.stringify({ v: 1, sessionId: sid, state: 'generated', text: 'x', serveType: 'provoke', ...r })).join('\n') + '\n')
inject([{ requestId: sid + '-a', at: Date.now() - 60_000, rallyId: 'r-' + sid }])
pl = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: wechat() })
check('刚发 1 分钟：闹钟定在 5 分钟那一级（不是 45 分钟冷却）', pl.body.reason === 'ladder-wait' && Math.abs(pl.body.nextCheckAt - (Date.now() + 4 * 60_000)) < 15_000, pl.body)
reset()
sid = await newSession({ mode: 'partner' })
inject([{ requestId: sid + '-a', at: Date.now() - 6 * 60_000 }])
pl = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: wechat() })
check('6 分钟没接 → 换轻球', pl.body.speak && pl.body.reason === 'ladder-lighter', pl.body)
const lighter = await serve(wechat(), 'lighter')
p = lastServePrompt()
check('轻球：类型在小挑战/闲聊里，prompt 认"没有回音"', ['challenge', 'chat'].includes(lighter.accepted?.serveType) && /没有回音/.test(p), lighter.accepted)
check('账本记阶梯级别 lighter', lighter.accepted?.silence === 'lighter', lighter.accepted)
reset()
sid = await newSession({ mode: 'partner' })
inject([{ requestId: sid + '-a', at: Date.now() - 16 * 60_000 }, { requestId: sid + '-b', at: Date.now() - 11 * 60_000, silence: 'lighter' }])
pl = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: wechat() })
check('第一球后 15 分钟还没接 → 换渠道再问一句（不再每十分钟催）', pl.body.reason === 'ladder-channel' && pl.body.silence?.stage === 'channel', pl.body)
inject([{ requestId: sid + '-c', at: Date.now() - 60_000, silence: 'channel' }])
pl = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: wechat() })
check('三球都没接 → 停下，3 小时后再看', pl.body.reason === 'no-take' && pl.body.nextCheckAt > Date.now() + 2.9 * 3_600_000, pl.body)

// ---------------------------------------------------------------- F. 水位、节奏、力度
console.log('【F. 水位与力度】')
reset()
const tzF = dayZone()
// R7 P0-2 之后时间基准默认锁北京，这条「挑一个正好 11–20 点的时区」的逃生口就失效了——
// CI 跑到北京凌晨时会撞上作息表的深夜锁。这一段测的是力度和水位，不是时区，所以这里显式
// 关掉 lockTimeZone 并用 tzF，让它跟墙上时钟无关。G 段再显式锁回去，两段各自自洽。
sid = await newSession({ mode: 'coach', minGapMs: 30 * 60_000, lockTimeZone: false, timeZone: tzF })
const pClear = await route('POST', '/plan', { roll: 100, timeZone: tzF, phone: wechat() })
const pTrap = await route('POST', '/plan', { roll: 100, timeZone: tzF, phone: game(70) })
check('打游戏 70 分钟（严格）→ 赶出去', pTrap.body.force?.name === 'home', pTrap.body.force)
// 锁屏页弹出后前台是 DSH 自己 / 赶回桌面后前台是桌面：力度为 0，但绝不能叫手机放行
const pLauncher = await route('POST', '/plan', { roll: 100, timeZone: tzF, phone: app('com.miui.home', '桌面', 0, false) })
check('被锁 / 被赶回桌面后，力度为 0 也不放行', pLauncher.body.force?.release === false, pLauncher.body.force)
check('陷住时一分钟内再看', pTrap.body.nextCheckAt <= Date.now() + 61_000, pTrap.body)
check('在微信里 → 力度"说"、水位清醒', pClear.body.force?.name === 'talk' && pClear.body.tide?.band === 'clear', pClear.body)
await route('POST', '/config', { mode: 'partner' })
const pWarm = await route('POST', '/plan', { roll: 100, timeZone: tzF, phone: game(35) })
check('温和模式 35 分钟 → 还不锁（屏）', pWarm.body.force?.name === 'screen', pWarm.body.force)
await route('POST', '/config', { control: false, mode: 'coach' })
const pNoCtl = await route('POST', '/plan', { roll: 100, timeZone: tzF, phone: game(70) })
check('关掉控机权限 → 最多到浮层', pNoCtl.body.force?.name === 'screen', pNoCtl.body.force)
await route('POST', '/config', { control: true })

// ---------------------------------------------------------------- G. 时区与安静时段
console.log('【G. 时区】')
const shHour = Number(new Intl.DateTimeFormat('en-US', { timeZone: 'Asia/Shanghai', hour: 'numeric', hourCycle: 'h23' }).format(new Date()))
sid = await newSession({ quietStart: shHour, quietEnd: (shHour + 1) % 24, lockTimeZone: true })
pl = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: wechat() })
check('按手机时区（上海 ' + shHour + ' 点）算安静时段', pl.body.reason === 'quiet', pl.body)
// R7 P0-2：时间基准固定北京时间，请求体里的时区不再影响判定。
// 这条以前断言「换个时区就不在安静时段」，现在反过来断言「换哪个时区判定都一样」——
// 契约变了，断言跟着变严：不只看纽约，还要看三家（含 UTC+14）全都和北京完全一致。
const zones = ['America/New_York', 'Pacific/Kiritimati', 'UTC', 'Asia/Tokyo']
const reasons = []
for (const tz of zones) {
  const r = await route('POST', '/plan', { roll: 0, timeZone: tz, phone: wechat() })
  reasons.push(r.body.reason)
}
check('换任何时区请求体都不影响安静时段（都是北京 ' + shHour + ' 点）', reasons.every((x) => x === 'quiet'), reasons)
// 开关不是死代码：显式关掉 lockTimeZone 之后，请求体时区恢复生效（纽约差 12 小时，不可能落进这一小时）
await route('POST', '/config', { lockTimeZone: false, timeZone: 'America/New_York' })
pl = await route('POST', '/plan', { roll: 0, timeZone: 'America/New_York', phone: wechat() })
check('关掉 lockTimeZone 后请求体时区恢复生效', pl.body.reason !== 'quiet', pl.body)
pl = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: wechat() })
check('关掉 lockTimeZone 后上海时区仍算安静时段', pl.body.reason === 'quiet', pl.body)
await route('POST', '/config', { lockTimeZone: true })

// ---------------------------------------------------------------- G2. 手机真实的请求体
console.log('【G2. 手机真实请求体】')
reset()
sid = await newSession({ mode: 'coach', minGapMs: 60_000 })
{
  // 手机（ProactiveRuntime.consultPlanSoon）发的就是这个形状：不带 roll，由插件自己掷骰子。
  // 以前手机带 roll:100，在"插件说开口才开口"之后等于永远不开口。
  let spoke = 0
  for (let i = 0; i < 12; i++) {
    const r = await route('POST', '/plan', { timeZone: 'Asia/Shanghai', phone: bili(12) })
    if (r.body.speak) spoke += 1
  }
  check('按手机真实请求体问 12 次 /plan，至少有一次说开口', spoke > 0, { spoke })
  // 有一球正在生成时，再问 /plan 不能又说开口（否则切 App 时会连发两球）
  const id = 'req-inflight-' + Date.now()
  await route('POST', '/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 45000, phone: bili(12) })
  const during = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: bili(12) })
  check('一球正在生成时 /plan 不再说开口', during.body.speak === false && ['in-flight', 'in-conversation'].includes(during.body.reason), during.body)
  await waitResult(id)
}

// ---------------------------------------------------------------- H. 设置页要的数据
console.log('【H. /status】')
st = await route('GET', '/status')
check('/status 有人格卡（关系库打底）', /不撒谎/.test(JSON.stringify(st.body.config?.personaEffective || st.body.personaEffective || '')), Object.keys(st.body))
await serve(wechat(), 'status')
st = await route('GET', '/status')
check('/status 有脾气底色（发过一球之后）', Number.isFinite(st.body.config?.temper?.edge), st.body.config?.temper)
check('/status 有大脑判断列表', Array.isArray(st.body.config?.beliefs), st.body.config?.beliefs)

// ---------------------------------------------------------------- I. 第三轮（状态机 / 七种事件 / D4 取货 / D3 笔记）
console.log('【I. 第三轮】')
reset()
await route('POST', '/config', { mode: 'partner', control: true })
sid = await newSession({ mode: 'partner' })

// I1. R3-5：笔记库里的具体内容进这一球，日记/私密目录不读
{
  await serve(bili(22), 'vault')
  const vp = lastServePrompt()
  check('I1.1 这一球提到那篇笔记的具体内容（R3-5）', /第三轮的引擎接线收尾/.test(vp), vp.slice(0, 400))
  check('I1.2 私密目录不会被读出来（R3-5）', !/私密内容不该出现/.test(vp), '')
}

// I1b. R3-2 的前半段：预约只给"下一场 + 绝对时间"，手机直接 arm 闹钟
{
  writeFileSync(
    H + '/companion/gemini-schedule.json',
    JSON.stringify({ at: Date.now() + 5 * 60_000, topic: '为什么这周进度卡住', minutes: 20, mode: 'thinking', fired: false }),
  )
  const pl2 = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: wechat() })
  const gs = pl2.body.geminiSchedule
  check('I1b.1 /plan 给下一场的绝对时间（不是 HH:MM）', !!gs && Number.isFinite(gs.at) && gs.at > Date.now() && gs.topic === '为什么这周进度卡住', gs)
  // 到点：手机会调 /gemini/due 取货，同一场只能发一次
  writeFileSync(
    H + '/companion/gemini-schedule.json',
    JSON.stringify({ at: Date.now() - 1_000, topic: '为什么这周进度卡住', minutes: 20, mode: 'thinking', fired: false }),
  )
  const d1 = await route('POST', '/gemini/due', {})
  check('I1b.2 到点取货拿到 launch（简报 + 分钟数，R3-2）', !!d1.body.launch && d1.body.launch.minutes === 20 && /卡住/.test(String(d1.body.launch.brief)), d1.body)
  const d2 = await route('POST', '/gemini/due', {})
  check('I1b.3 同一场重复调用返回 null（§7.2 第 9 条）', d2.body.launch === null, d2.body)
  await route('POST', '/session', { action: 'end' })
}

// I2. /plan 带的四块就是手机原生层读的形状（接口约定 §1/§2）
{
  const r = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: game(45) })
  const b = r.body
  const layers = b.state?.layers
  check('I2.1 /plan 有 state（主状态 + layers + nextChangeAt）',
    typeof b.state?.state === 'string' && Array.isArray(layers) && layers.length > 0 && Number.isFinite(b.state?.nextChangeAt), b.state)
  check('I2.2 裁判层 layers[0] 带 kind/until（§7.2 第 1 条）',
    typeof layers?.[0]?.kind === 'string' && Number.isFinite(layers?.[0]?.until), layers?.[0])
  check('I2.3 有 followup 块（active 形状固定，§2）',
    !!b.followup && typeof b.followup.active === 'boolean', b.followup)
  check('I2.4 有 obligations 数组（§5）', Array.isArray(b.obligations), b.obligations)
  check('I2.5 wall 带本周放松额度（面板的"额度"那行）',
    !!b.wall && Number.isFinite(b.wall.relaxRemaining) && typeof b.wall.locked === 'boolean', b.wall)
}

// I3. 七种新事件都有回执，形状按 §3（手机只按回执里的 until/followup 动）
{
  const ev = async (type, extra = {}) => (await route('POST', '/event', { type, ...extra })).body
  check('I3.1 screen-on 只记账', (await ev('screen-on')).ok === true)
  const sprint = await ev('start', { task: '写周报', minutes: 25 })
  check('I3.2 start 进冲刺且给绝对 until', !!sprint.sprint && Number.isFinite(sprint.sprint.until) && sprint.sprint.minutes === 25, sprint)
  const hit = await ev('wechat-hit', { page: 'finder', dwellMs: 4_200 })
  check('I3.3 wechat-hit 只记账，不升级成违抗', hit.ok === true && !hit.refuse, hit)
  const blocked = await ev('blocked-open', { app: 'com.tencent.tmgp.sgame' })
  check('I3.4 blocked-open 只按这次动作问（ask 是布尔）', blocked.ok === true && typeof blocked.ask === 'boolean', blocked)
  const gem = await ev('gemini-end', { minutes: 5, by: 'phone' })
  check('I3.5 gemini-end 满 3 分钟开追问锁', gem.counted === true && !!gem.followup, gem)
  const reviewAsk = await ev('request-review')
  check('I3.6 request-review 开追问锁，不是当场放行', reviewAsk.ok === true && !!reviewAsk.followup, reviewAsk)
  const pl3 = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: game(45) })
  const fu = pl3.body.followup
  // 不清「主状态一定是 followup」：CI 可能正好跑在深夜/饭点，那时追问锁只是叠在上面的一层。
  check('I3.7 追问锁进了 /plan 且未到点（窗口不可划走）', fu?.active === true && fu.until > Date.now(), fu)
  // 未到点就报 followup-timeout（手机时钟快了、提前触发）：门不能因此关掉（§7.2 第 4 条）。
  // 到点后那一支（expired:true + 挂义务）要等真到期，插件单测里覆盖，这里只钉"提前报不算数"。
  const early = await ev('followup-timeout')
  check('I3.8 未到点报超时不会提前关门（§7.2 第 4 条）', early.ok === true && early.expired === false, early)
  const pl4 = await route('POST', '/plan', { roll: 0, timeZone: 'Asia/Shanghai', phone: game(45) })
  check('I3.9 这一声没把追问锁报掉，窗口还在', pl4.body.followup?.active === true && pl4.body.followup.until > Date.now(), pl4.body.followup)
}

// ---------------------------------------------------------------- J. v3.0 认知自主核心特性 + 三条标杆协议真引擎回放
console.log('【J. v3.0 标杆协议 + 造局 + 四部 SOP + 六骰/六拍 + 租约豁免 + 弹性降级】')
reset()
{
  const tzDay = dayZone()
  // J1. 三条标杆协议真引擎开局 -> 问 -> 答 -> 收局 + 动态造局 (/protocols/compose)
  for (const pid of ['multi_shuttle_drill', 'video_slice_standard', 'commute_anti_daydream']) {
    const o = await route('POST', '/bouts', { action: 'open', protocolId: pid })
    check(`J1.${pid} 在真引擎上可开局`, o.status === 200 && o.body?.ok === true, o.body)
    const asked = await route('POST', '/bouts', { action: 'ask' })
    check(`J1.${pid} /bouts ask 给出首拍提问`, asked.body?.ok === true && String(asked.body?.say || '').length > 0, asked.body)
    const closed = await route('POST', '/bouts', { action: 'close' })
    check(`J1.${pid} /bouts close cleanly closes bout`, closed.body?.ok === true, closed.body)
  }

  const badWall = await route('POST', '/protocols/compose', {
    role: 'strategist',
    protocol: { id: 'e2e-bad-wall', name: '越权墙', needs: ['全机锁'], steps: [{ id: 's1', ask: '说一句', limit: '2m', timeout: '收局' }] },
  })
  check('J1.compose 声明墙能力被拒 (400)', badWall.status === 400 && badWall.body?.ok === false, badWall.body)

  const badLoop = await route('POST', '/protocols/compose', {
    role: 'strategist',
    protocol: { id: 'e2e-bad-loop', name: '死循环', steps: [{ id: 's1', ask: '一直卡住', limit: '2m', timeout: '重打' }] },
  })
  check('J1.compose 无确定终局出口被拒 (400)', badLoop.status === 400 && badLoop.body?.ok === false, badLoop.body)

  const goodCompose = await route('POST', '/protocols/compose', {
    role: 'strategist',
    protocol: { id: 'e2e-weakness-drill', name: 'E2E 缓存反例专攻', goal: '补齐 SQLite 缓存失效反例', minutes: 20, department: 'dept:strategize' },
  })
  check('J1.compose 合法动态协议通过校验并入队', goodCompose.status === 200 && goodCompose.body?.ok === true && goodCompose.body?.protocol?.id === 'e2e-weakness-drill', goodCompose.body)
  const openComposed = await route('POST', '/bouts', { action: 'open', protocolId: 'e2e-weakness-drill' })
  check('J1.compose 生成的动态协议可立即开局', openComposed.body?.ok === true, openComposed.body)
  await route('POST', '/bouts', { action: 'close' })

  // J2. 四部门隐式 SOP（无原始事实卡时 ERR_NO_RAW_FACTS 拦截 -> 收集建卡 -> 谋划通过 -> 统筹 -> 督办）
  const stratDeny = await route('POST', '/departments', {
    action: 'strategize', rawFactCards: [], decision: '直接下结论跳过取证',
  })
  check('J2.1 谋划思考部无 raw_fact_cards 直接拒单 ERR_NO_RAW_FACTS', stratDeny.status === 400 && stratDeny.body?.code === 'ERR_NO_RAW_FACTS', stratDeny.body)

  const collectOk = await route('POST', '/departments', {
    action: 'collect',
    targetQuestion: '为什么缓存写入在并发下偶尔读到旧快照',
    rawFacts: ['WAL 模式下读事务持有旧视图直到 COMMIT 后新事务开启', '连接池复用未重置 read snapshot'],
    source: 'vault-e2e',
  })
  check('J2.2 信息收集部产出 raw_fact_cards 与 Obsidian 卡', collectOk.status === 200 && collectOk.body?.ok === true && (collectOk.body?.raw_fact_cards || []).length === 2, collectOk.body)

  const stratOk = await route('POST', '/departments', {
    action: 'strategize',
    rawFactCards: collectOk.body?.raw_fact_cards || [],
    decision: '将写入与只读查询拆到显式短事务并在提交后刷新版本戳',
    falsifyPath: '并发压测 100 次后仍出现旧版本号即推翻该方案',
  })
  check('J2.3 谋划思考部绑定 raw_fact_cards 后通过', stratOk.status === 200 && stratOk.body?.ok === true && String(stratOk.body?.single_point_decision?.falsify_path || '').length > 0, stratOk.body)

  const dispOk = await route('POST', '/departments', {
    action: 'dispatch',
    boutsHistory: [{ status: 'closed', closedAt: Date.now() - 86_400_000 }],
    proposedQueue: [{ protocolId: 'multi_shuttle_drill', title: '多球机制拆解' }],
  })
  check('J2.4 统筹规划部锁定可执行队列', dispOk.status === 200 && dispOk.body?.ok === true && (dispOk.body?.locked_plan_modality_queue || []).length === 1, dispOk.body)

  const govOk = await route('POST', '/departments', {
    action: 'govern',
    lockedQueue: dispOk.body?.locked_plan_modality_queue || [],
    verdicts: [{ pass: true }],
    telemetry: { workMinutesToday: 40, funMinutesToday: 5 },
  })
  check('J2.5 督办审计部产出行为审计与履约率', govOk.status === 200 && govOk.body?.ok === true && govOk.body?.daily_behavior_audit?.adherenceRate === 1, govOk.body)

  // J3. 六骰 & 六拍认知槽（含连续 2 次失败给出观测锚点提示 + 通过写入 ledger）
  const dSlots = await route('POST', '/wall', { action: 'dice-slots', stage: 1, query: '缓存' })
  check('J3.1 /wall dice-slots 返回当前阶段骰子槽', dSlots.body?.ok === true && Array.isArray(dSlots.body?.slots) && dSlots.body.slots.length >= 1, dSlots.body)

  const dFail1 = await route('POST', '/wall', { action: 'dice-judge', dice: 6, prompt: '换个解释？', answer: '太短', sourceContext: 'WAL 事务在 COMMIT 前仅对当前连接可见，其它连接读旧快照。', anchors: ['事务隔离', '旧快照', 'COMMIT 可见性'] })
  const dFail2 = await route('POST', '/wall', { action: 'dice-judge', dice: 6, prompt: '换个解释？', answer: '大概吧', sourceContext: 'WAL 事务在 COMMIT 前仅对当前连接可见，其它连接读旧快照。', anchors: ['事务隔离', '旧快照', 'COMMIT 可见性'] })
  check('J3.2 六骰连续 2 次失败触发脚手架观测锚点提示', dFail1.body?.pass === false && dFail2.body?.pass === false && dFail2.body?.failures === 2 && String(dFail2.body?.anchorHint || '').length > 0, dFail2.body)

  const dPass = await route('POST', '/wall', { action: 'dice-judge', dice: 6, prompt: '换个解释？', answer: '如果缓存写入前并发事务还没提交，因为 WAL 快照隔离导致读连接看到旧版本号而不只是网络延迟。', sourceContext: 'WAL 事务在 COMMIT 前仅对当前连接可见。' })
  check('J3.3 具体因果解释通过六骰判官并落账', dPass.body?.pass === true && ledger().some((e) => e.state === 'wall-dice-judge' && e.pass === true), dPass.body)

  const stSlots = await route('POST', '/wall', { action: 'stroke-slots', track: 'multi_shuttle_drill', topic: 'WAL 快照隔离' })
  const stMatch = await route('POST', '/wall', { action: 'stroke-slots', track: 'department_match', topic: 'WAL 快照隔离' })
  check('J3.4 /wall stroke-slots 返回多球(3拍)与部门比赛(6拍)槽位', stSlots.body?.ok === true && stSlots.body?.track === 'multi_shuttle_drill' && stSlots.body?.slots?.length === 3 && stMatch.body?.track === 'department_match' && stMatch.body?.slots?.length === 6, { drill: stSlots.body, match: stMatch.body })
  const stPass = await route('POST', '/wall', { action: 'stroke-judge', stroke: 'stroke_2_condense', track: 'multi_shuttle_drill', answer: '依据 WAL 文档第3节，未提交事务对其他连接保持快照隔离，因此并发读必然命中旧页。', sourceContext: 'WAL 文档第3节', noteTitle: 'WAL隔离复盘' })
  check('J3.5 /wall stroke-judge 验收第二拍并落账', stPass.body?.pass === true && ledger().some((e) => e.state === 'wall-stroke-judge' && e.pass === true), stPass.body)

  // J4. 外部 Agent 租约 (/lease) + recap-fallback + 3 分钟紧急通行证 (/emergency-pass)
  const leaseIssued = await route('POST', '/lease', { action: 'issue', agentId: 'chatgpt', topic: '排查 WAL 锁冲突', ttlMs: 15 * 60_000 })
  check('J4.1 /lease issue 下发外部 Agent 白名单租约', leaseIssued.status === 200 && leaseIssued.body?.ok === true && (leaseIssued.body?.lease?.packages || []).includes('com.openai.chatgpt'), leaseIssued.body)
  const planLeased = await route('POST', '/plan', { roll: 100, timeZone: tzDay, phone: game(10) })
  check('J4.2 /plan 租约期允许白名单外部 Agent 包名', (planLeased.body?.session?.allow || []).includes('com.openai.chatgpt'), planLeased.body?.session)
  const recapFb = await route('POST', '/lease', { action: 'recap-fallback', agentId: 'chatgpt', captureFailed: true, spokenOrTextRecap: '刚确认并发事务未提交前读旧快照，卡点在连接池未重置版本戳。' })
  check('J4.3 /lease recap-fallback 录屏降级复盘可受理并落账', recapFb.body?.ok === true && ledger().some((e) => e.state === 'external-lease-recap'), recapFb.body)

  const ep = await route('POST', '/emergency-pass', { reason: '楼下取快递扫码验证' })
  check('J4.4 /emergency-pass 授予 3 分钟无惩罚应急通行证', ep.status === 200 && ep.body?.ok === true && ep.body?.until > Date.now(), ep.body)
  const planEp = await route('POST', '/plan', { roll: 100, timeZone: tzDay, phone: game(10) })
  check('J4.5 /plan 通行证有效期内放行', planEp.body?.emergencyPass?.active === true && planEp.body?.force?.level === 0, planEp.body)
  await route('POST', '/lease', { action: 'revoke', reason: 'e2e-done' })
  await route('POST', '/emergency-pass', { action: 'revoke' })

  // J5. 弹性缓冲降级 -> 3 次 scheduling_failure 触发低功耗保底日 (/elastic-check) + PC 心跳 (/event pc-activity)
  let el3 = null
  for (let i = 1; i <= 3; i += 1) {
    el3 = await route('POST', '/elastic-check', { plannedAt: Date.now() - 25 * 60_000, startedAt: 0, bufferMin: 20 })
    if (i === 1) {
      check('J5.1 第1次超时记 scheduling_failure 且降级 micro_landing_3m', el3.body?.exceeded === true && el3.body?.classification === 'scheduling_failure' && el3.body?.fallbackBout?.protocolId === 'micro_landing_3m' && el3.body?.schedulingFloor?.lowPowerFloor === false, el3.body)
    }
  }
  check('J5.2 当日第3次超时触发低功耗保底日 (lowPowerFloor + ledger)', el3?.body?.schedulingFloor?.lowPowerFloor === true && ledger().some((e) => e.state === 'low-power-floor'), el3?.body)

  const pcHb = await route('POST', '/event', { type: 'pc-activity', activeApp: 'code.exe', activeTitle: 'accept.mjs - dsh', category: 'coding', focusMinutes: 18, entertainmentMinutes: 0, isAfk: false })
  check('J5.3 /event pc-activity 记录桌面端心跳并落账', pcHb.body?.ok === true && pcHb.body?.pc?.category === 'coding' && ledger().some((e) => e.state === 'pc-heartbeat' && e.category === 'coding'), pcHb.body)

  // J6. 内核高精度时钟 (/clock) + 零延迟行为雷达 (/plan)
  const clkUp = await route('POST', '/clock', { op: 'upsert', task: { id: 'e2e_cyc', label: 'E2E 番茄钟', kind: 'CYCLE_PHASE', maxRuns: 2, phases: [{ name: '专注', durationMs: 1500000, strictLock: true, allowApps: ['md.obsidian'] }, { name: '休息', durationMs: 300000, strictLock: false, allowApps: [] }] } })
  check('J6.1 /clock upsert 创建多阶段循环番茄成功', clkUp.status === 200 && clkUp.body?.ok === true && Array.isArray(clkUp.body?.tasks) && clkUp.body.tasks.some((t) => t.id === 'e2e_cyc'), clkUp.body)
  await route('POST', '/clock', { op: 'pause', id: 'e2e_cyc' })
  const clkAfterPause = await route('GET', '/clock')
  check('J6.2 /clock GET 持久可读 paused 状态', clkAfterPause.status === 200 && clkAfterPause.body?.tasks?.find((t) => t.id === 'e2e_cyc')?.paused === true, clkAfterPause.body)
  await route('POST', '/clock', { op: 'delete', id: 'e2e_cyc' })

  const feedRadar = await route('POST', '/plan', {
    roll: 100,
    timeZone: tzDay,
    phone: { app: 'com.vendor.shortvideo.feed', label: '某某短视频', minutes: 14, fun: false, screenOn: true, installedApps: [{ pkg: 'com.vendor.shortvideo.feed', label: '某某短视频', bypass: true }] },
  })
  check('J6.3 零延迟行为雷达在 /plan 中自动识别未收录短视频流并落锁', feedRadar.status === 200 && feedRadar.body?.behaviorRadar?.kind === 'feed-drift' && feedRadar.body?.force?.level >= 4, feedRadar.body)
}

console.log(failed ? '✗ ' + failed + ' 项失败' : '✓ 全部通过')
process.exit(failed ? 1 : 0)
