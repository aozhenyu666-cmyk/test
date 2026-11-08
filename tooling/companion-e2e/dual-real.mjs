// 姐妹双人格真模型试跑：妹妹的高频互动一轮 + 闲聊；姐姐的谋划对话。结果写 $E2E_WORK/dual-results.json
import { writeFileSync } from 'node:fs'
import { rpc, route, sleep, waitResult } from './app.mjs'
const W = process.env.E2E_WORK
const log = []
const app = (pkg, label, m) => ({ app: pkg, label, minutes: m, fun: true, screenOn: true, local: '21:30',
  today: [{ app: pkg, label, minutes: m + 40, fun: true }] })
const douyin = (m) => app('com.ss.android.ugc.aweme', '抖音', m)
const game = (m) => app('com.tencent.tmgp.sgame', '王者荣耀', m)
async function session() { const c = await rpc('session/create', { request: { cwd: W } }); return c.body?.result?.value?.sessionId }
const sis = await session()
const big = await session()
await route('POST', '/config', { sessionId: sis, strategistSessionId: big, mode: 'partner', dual: true, quietStart: 3, quietEnd: 3, minGapMs: 60_000, drills: false })
await sleep(500)
function show(who, text, ms, note = '') { const line = { who, text, ms, note }; log.push(line); console.log((who === 'me' ? '我' : who) + (note ? '〔' + note + '〕' : '') + '：' + String(text).replace(/\n+/g, ' / ') + (ms ? '  (' + (ms / 1000).toFixed(1) + 's)' : '')) }
async function ball(phone, note) {
  const id = 'req-dual-' + Date.now(); const t0 = Date.now()
  await route('POST', '/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 45000, phone })
  const r = await waitResult(id, 12)
  show('小肥鱼', r?.text || JSON.stringify(r), Date.now() - t0, '主动 · ' + note)
}
async function say(text, role) {
  show('me', text, 0, role === 'strategist' ? '对姐姐' : '')
  const t0 = Date.now()
  const s = await route('POST', '/say', { v: 1, requestId: 'say-' + Date.now() + '-' + Math.floor(Math.random() * 1e6), text, timeZone: 'Asia/Shanghai', ...(role ? { role } : {}) })
  const r = s.body?.requestId ? await waitResult(s.body.requestId, 15) : s.body
  show(role === 'strategist' ? '大肥鱼' : '小肥鱼', r?.text || '（失败 ' + JSON.stringify(r?.failure || r?.error || r) + '）', Date.now() - t0)
}
console.log('\n===== 妹妹 · 高频互动一轮（温和）')
await ball(douyin(25), '抖音 25 分钟')
await say('嗯')
await say('等会儿，刷完这个')
await ball(douyin(38), '抖音 38 分钟，上一球没真理她')
await say('我好烦，工作一直找不到')
await say('你帮我分析下考研还是工作')
await route('POST', '/config', { mode: 'coach' })
await ball(game(65), '严格 · 王者 65 分钟（锁）')
await say('我就再打一把，打完就去投简历')
await say('别说了')
console.log('\n===== 妹妹 · 闲聊')
await route('POST', '/config', { mode: 'partner' })
await say('今天好无聊')
await say('你今天干嘛了')
console.log('\n===== 姐姐 · 谋划')
await say('我计算机大四，秋招到现在一个 offer 都没有，怎么办', 'strategist')
await say('我其实也想过考公，但不知道来不来得及', 'strategist')
await say('你直接告诉我选哪个', 'strategist')
writeFileSync(W + '/dual-results.json', JSON.stringify({ at: new Date().toISOString(), model: 'deepseek-flash', log }, null, 2))
