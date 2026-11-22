// 2026-11-18 切换次数限制：只在干正事时算；桌面 / DSH / 白名单 / 工作类不算；阈值来自 规则.md。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { evalBehaviorRadar, evalSwitchLimit } from '../lib/behavior_radar.js'
import { classifyRules, parseRules, renderRules } from '../lib/vault_wall.js'
import { wallConfig } from '../lib/wall.js'
import { cleanPhone } from '../lib/phone.js'

const rule = { switchWindowMin: 10, switchDiceAt: 3, switchLockAt: 5 }
const hop = (apps, front = 'com.ss.android.ugc.aweme') => cleanPhone({
  app: front, label: '抖音', minutes: 1, screenOn: true, local: '10:10',
  switches: { in15m: apps.length, distinct15m: 3, recent60m: apps.map((a, i) => ({ pkg: a, agoMs: (i + 1) * 60_000 })) },
})

test('桌面、DSH、白名单、工作类切再多次也不算', () => {
  const p = hop(['com.bbk.launcher2', 'io.github.ccch1mneyyy.dsh_app.fish', 'md.obsidian', 'com.bbk.launcher2', 'com.android.systemui', 'moe.nb4a'], 'md.obsidian')
  assert.equal(evalSwitchLimit({ phone: p, allow: new Set(['md.obsidian']), rule }), null)
})

test('10 分钟内切出去 3 次 → 摇骰拉 Gemini；5 次 → 锁当前白名单外的 App', () => {
  const three = hop(['com.ss.android.ugc.aweme', 'tv.danmaku.bili', 'com.ss.android.ugc.aweme'])
  assert.equal(evalSwitchLimit({ phone: three, rule }).level, 'dice')
  const five = hop(['com.ss.android.ugc.aweme', 'tv.danmaku.bili', 'com.ss.android.ugc.aweme', 'com.xingin.xhs', 'tv.danmaku.bili'])
  const r = evalSwitchLimit({ phone: five, rule })
  assert.equal(r.level, 'lock')
  assert.equal(r.target, 'com.ss.android.ugc.aweme')
})

test('窗口外的切换不算', () => {
  const p = cleanPhone({ app: 'tv.danmaku.bili', screenOn: true, switches: { recent60m: [
    { pkg: 'tv.danmaku.bili', agoMs: 11 * 60_000 }, { pkg: 'com.xingin.xhs', agoMs: 20 * 60_000 }, { pkg: 'tv.danmaku.bili', agoMs: 30 * 60_000 },
  ] } })
  assert.equal(evalSwitchLimit({ phone: p, rule }), null)
})

test('雷达：只有在干正事时才按切换次数出手', () => {
  const p = hop(['com.ss.android.ugc.aweme', 'tv.danmaku.bili', 'com.ss.android.ugc.aweme'])
  const now = Date.parse('2026-11-18T06:00:00Z') // 14:00 北京
  const idle = evalBehaviorRadar({ phone: p, now, morningPlanned: true, switchRule: rule, inWork: false })
  assert.notEqual(idle.kind, 'switch-limit-dice')
  const work = evalBehaviorRadar({ phone: p, now, morningPlanned: true, switchRule: rule, inWork: true })
  assert.equal(work.kind, 'switch-limit-dice')
  assert.equal(work.pullGemini, true)
})

test('规则.md：切换三项可读可写；次数调小 / 窗口调大是改严，反过来要冷却', () => {
  const cur = wallConfig({})
  assert.equal(cur.switchDiceAt, 3)
  const back = parseRules(renderRules(cur))
  assert.equal(back.wall.switchLockAt, 5)
  assert.deepEqual(classifyRules(cur, back.wall), { tighten: {}, loosen: {} })
  const t = classifyRules(cur, { ...back.wall, switchDiceAt: 2, switchWindowMin: 15 })
  assert.deepEqual(t.tighten, { switchDiceAt: 2, switchWindowMin: 15 })
  const l = classifyRules(cur, { ...back.wall, switchLockAt: 8, switchWindowMin: 5 })
  assert.deepEqual(l.loosen, { switchLockAt: 8, switchWindowMin: 5 })
})
