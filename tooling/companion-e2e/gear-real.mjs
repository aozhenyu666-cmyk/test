// 真模型：聊天里直接管他（dafeiyu_control）、Gemini 去（gemini_live）和回（gemini_log）、忙时人设。
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { rpc, route, sleep, waitResult } from './app.mjs'
const W = process.env.E2E_WORK
const HOME = W + '/home'
const log = []
const sess = async () => (await rpc('session/create', { request: { cwd: W } })).body?.result?.value?.sessionId
const sis = await sess()
const big = await sess()
await route('POST', '/config', { sessionId: sis, strategistSessionId: big, dual: true, quietStart: 3, quietEnd: 3, drills: false, owner: '阿鱼' })
await sleep(800)
const show = (who, text, ms, note = '') => { log.push({ who, text, ms, note }); console.log((note ? '〔' + note + '〕' : '') + who + '：' + String(text).replace(/\n+/g, ' / ') + (ms ? '  (' + (ms / 1000).toFixed(1) + 's)' : '')) }
async function say(text, role) {
  show('我', text, 0, role === 'strategist' ? '对鲸澜' : '对鱼丸')
  const t0 = Date.now()
  const s = await route('POST', '/say', { v: 1, requestId: 'say-' + Date.now() + '-' + Math.floor(Math.random() * 1e6), text, timeZone: 'Asia/Shanghai', role: role || 'companion' })
  const r = s.body?.requestId ? await waitResult(s.body.requestId, 25) : s.body
  show(role === 'strategist' ? '鲸澜' : '鱼丸', r?.text || '（失败 ' + JSON.stringify(r?.failure || r?.error || r) + '）', Date.now() - t0)
  return r?.text || ''
}
const ledgerTail = () => readFileSync(join(HOME, 'companion', 'ledger.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l))
const cfg = () => JSON.parse(readFileSync(join(HOME, 'companion', 'config.json'), 'utf8'))
const gem = () => { try { return JSON.parse(readFileSync(join(HOME, 'companion', 'gemini.json'), 'utf8')) } catch { return {} } }
const notes = []
const check = (ok, what) => { notes.push((ok ? '✓ ' : '✗ ') + what); console.log((ok ? '  ✓ ' : '  ✗ ') + what) }

console.log('\n== 聊天里让她管：应该直接切忙时')
await say('帮我盯着，接下来一个小时我要写周报，别让我玩游戏')
check(cfg().gear === 'busy' && Number(cfg().gearUntil) > Date.now() + 40 * 60_000, '切到了忙时（gear=' + cfg().gear + '）')
check(ledgerTail().some((e) => e.state === 'gear' && e.by === 'tool'), '是她用 dafeiyu_control 切的')
await say('我就玩一把，五分钟')

console.log('\n== 让姐姐安排 Gemini 连麦')
await say('我想跟 Gemini 连麦把线代第三章过一遍，半小时，你帮我弄一下', 'strategist')
const g = gem()
check(Boolean(g.session?.topic), 'gemini_live 开了一场（' + (g.session?.topic || '无') + '）')
check(Boolean(g.session?.until) && g.session.until - g.session.startedAt <= 35 * 60_000, '时长对（' + Math.round(((g.session?.until || 0) - (g.session?.startedAt || 0)) / 60000) + ' 分钟）')

console.log('\n== 连完回来口头复盘：她该存进笔记')
await say('跟 Gemini 聊完了，过了一遍特征值，我卡在对角化的条件，下一步明早做习题 3.2，帮我记一下', 'strategist')
const g2 = gem()
check(Array.isArray(g2.recaps) && g2.recaps.length > 0, 'gemini_log 存了复盘')
const dir = join(HOME, 'companion', 'gemini')
check(existsSync(dir) && readdirSync(dir).length > 0, '复盘写成了 md 文件')

console.log('\n== 妹妹知道 Gemini 那边聊了啥；收工')
await say('我刚跟 Gemini 学到哪了来着')
await say('周报写完了，忙完了')
check(cfg().gear === 'free', '忙完了 → 回闲时')
writeFileSync(W + '/gear-real-results.json', JSON.stringify({ at: new Date().toISOString(), model: 'deepseek-flash', notes, log }, null, 2))
