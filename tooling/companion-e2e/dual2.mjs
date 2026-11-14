// 双人格第二轮真模型：姐妹传话 → 姐姐主动、跨会话记忆、活人感（不乱查资料、固定设定）、上下文回忆。
import { readFileSync, writeFileSync } from 'node:fs'
import { rpc, route, sleep, waitResult, ledger } from './app.mjs'
const W = process.env.E2E_WORK
const log = []
const sess = async () => (await rpc('session/create', { request: { cwd: W } })).body?.result?.value?.sessionId
const sis = await sess()
const big = await sess()
await route('POST', '/config', { sessionId: sis, strategistSessionId: big, mode: 'partner', dual: true, quietStart: 3, quietEnd: 3, minGapMs: 60_000, drills: false })
await sleep(800)
const show = (who, text, ms, note = '') => { log.push({ who, text, ms, note }); console.log((note ? '〔' + note + '〕' : '') + who + '：' + String(text).replace(/\n+/g, ' / ') + (ms ? '  (' + (ms / 1000).toFixed(1) + 's)' : '')) }
async function say(text, role) {
  show('我', text, 0, role === 'strategist' ? '对鲸澜' : '对鱼丸')
  const t0 = Date.now()
  const s = await route('POST', '/say', { v: 1, requestId: 'say-' + Date.now() + '-' + Math.floor(Math.random() * 1e6), text, timeZone: 'Asia/Shanghai', role: role || 'companion' })
  const r = s.body?.requestId ? await waitResult(s.body.requestId, 15) : s.body
  show(role === 'strategist' ? '鲸澜' : '鱼丸', r?.text || '（失败 ' + JSON.stringify(r?.failure || r?.error || r) + '）', Date.now() - t0)
  return r?.text || ''
}
console.log('\n== 1. 跟鱼丸说正事，看她会不会递话给姐姐')
await say('我刚吃了螺蛳粉，好臭哈哈')
await say('跟你说个事，我下周三下午要去字节二面，有点慌')
await sleep(1500)
const notes = (() => { try { return JSON.parse(readFileSync(W + '/home/companion/notes.json', 'utf8')).notes } catch { return [] } })()
console.log('  → 递话：' + (notes.length ? notes.map((n) => (n.urgent ? '[急]' : '') + n.text).join(' | ') : '没有递'))
console.log('\n== 2. 手机问 /plan：姐姐会不会主动来')
const plan = await route('POST', '/plan', { roll: 100, timeZone: 'Asia/Shanghai', phone: { app: 'com.tencent.mm', minutes: 2, screenOn: true, local: '15:20' } })
console.log('  → /plan：' + JSON.stringify({ speak: plan.body.speak, reason: plan.body.reason, speaker: plan.body.speaker }))
if (plan.body.speak) {
  const id = 'req-d2-' + Date.now(); const t0 = Date.now()
  await route('POST', '/request', { v: 1, requestId: id, eventId: 'evt-' + id, kind: 'random_chat', payload: {}, deadlineMs: 45000 })
  const r = await waitResult(id, 12)
  show('（主动）', r?.text || JSON.stringify(r), Date.now() - t0, '主动')
}
console.log('\n== 3. 跨会话记忆：问鲸澜我下周有什么安排')
await say('我下周有什么安排来着？', 'strategist')
console.log('\n== 4. 活人感：跟鲸澜闲聊（不该去查资料，应该短）')
await say('今天好累，不想动', 'strategist')
console.log('\n== 5. 鱼丸的设定前后一致 + 上下文回忆')
await say('你养了什么宠物来着')
await say('我中午吃的啥你还记得不')
writeFileSync(W + '/dual2-results.json', JSON.stringify({ at: new Date().toISOString(), model: 'deepseek-flash', notes, log }, null, 2))
