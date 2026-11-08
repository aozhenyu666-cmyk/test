// 真模型试跑：同样的手机调用顺序，但大肥鱼接的是真 DeepSeek。打印原话和每一步的耗时，人来判内容好不好。
import { readFileSync } from 'node:fs'
import { rpc, route, sleep, ledger, waitResult, prompt } from './app.mjs'
const W = process.env.E2E_WORK
const t = () => Date.now()
const game = (m) => ({ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes: m, fun: true, screenOn: true, local: '22:40',
  today: [{ app: 'com.tencent.tmgp.sgame', label: '王者荣耀', minutes: m + 30, fun: true }, { app: 'tv.danmaku.bili', label: '哔哩哔哩', minutes: 50, fun: true }] })
const c = await rpc('session/create', { request: { cwd: W } })
const sid = c.body?.result?.value?.sessionId
await route('POST', '/config', { sessionId: sid, mode: 'partner', drills: false, quietStart: 3, quietEnd: 3, minGapMs: 60_000 })
async function ball(label, phone) {
  const id = 'req-real-' + t(); const t0 = t()
  const r = await route('POST', '/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 45000, phone })
  const res = r.status === 202 ? await waitResult(id, 12) : r.body
  const acc = ledger().find((e) => e.requestId === id && e.state === 'accepted') || {}
  console.log(`\n【${label}】${((t() - t0) / 1000).toFixed(1)}s  [${acc.serveType || '-'} · 脸色 ${acc.face || '-'}${acc.surprise ? '(翻脸)' : ''} · 水位 ${acc.band || '-'} · 力度 ${acc.force ?? 0}]`)
  console.log('大肥鱼：' + (res?.text || JSON.stringify(res?.failure || res?.error || res)))
  return id
}
async function reply(id, text) {
  const t0 = t(); const before = ledger().length
  await prompt(sid, text)
  const rep = await (async () => { for (let i = 0; i < 120; i++) { const r = ledger().slice(before).find((e) => e.requestId === id && e.state === 'replied'); if (r) return r; await sleep(250) } })()
  console.log(`我：${text}`)
  console.log(`  判球 ${rep ? rep.verdict + (rep.judged ? '' : '(规则兜底)') + ' · ' + (rep.why || '') : '没判出来'}  ${((t() - t0) / 1000).toFixed(1)}s`)
  // 等大肥鱼在会话里回这句（从会话读最后一条助手消息比较麻烦，这里看引擎跑完这一轮）
  const running = async () => ((await route('GET', '/status')).body?.agents || []).some((a) => a.status === 'running')
  for (let i = 0; i < 20 && !(await running()); i++) await sleep(250)
  for (let i = 0; i < 240 && (await running()); i++) await sleep(500)
  console.log(`  这一轮结束 ${((t() - t0) / 1000).toFixed(1)}s`)
  return rep
}
async function say(text) {
  const t0 = t()
  const s = await route('POST', '/say', { v: 1, requestId: 'say-' + t(), text })
  const r = s.body?.requestId ? await waitResult(s.body.requestId, 15) : s.body
  console.log(`我（浮层）：${text}\n大肥鱼：${r?.text || JSON.stringify(r?.failure || r?.error || r)}   ${((t() - t0) / 1000).toFixed(1)}s`)
}
const a = await ball('游戏 40 分钟，温和', game(40))
await reply(a, '我今天作业都写完了，打两把放松一下')
await say('那你觉得我打几把合适')
await say('行，我再打一把就去洗澡')
await route('POST', '/config', { mode: 'coach', drills: true })
const b = await ball('严格 + 做题，游戏 65 分钟', game(65))
await reply(b, '挺久的吧')
const c2 = await ball('上一题糊弄之后', game(68))
await reply(c2, '差不多一个半小时，90 分钟左右')
const pl = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: game(70) })
console.log('\n/plan:', JSON.stringify({ speak: pl.body.speak, reason: pl.body.reason, tide: pl.body.tide?.band, force: pl.body.force?.name, why: pl.body.force?.why }))
