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
console.log('\n== 鲸澜闲聊（不该查资料、应该短）')
await say('今天好累，不想动', 'strategist')
await say('周末想去爬山，你觉得咋样', 'strategist')
console.log('\n== 鱼丸上下文回忆')
await say('我中午吃了螺蛳粉，好臭')
await say('你说我刚刚中午吃的啥')
const notes = []
writeFileSync(W + '/dual2-results.json', JSON.stringify({ at: new Date().toISOString(), model: 'deepseek-flash', notes, log }, null, 2))
