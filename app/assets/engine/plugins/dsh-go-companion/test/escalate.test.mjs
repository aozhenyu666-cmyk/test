// P4 升级阶梯：E1/E1′/E2/E3、L1/L2/L3，每条都要有触发、不触发边界、出口、当天重置。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  CELL_GRACE_MS, EXITS, L2_GRACE_MS,
  dayState, evaluate, evaluateE, evaluateEPrime, evaluateL, notePerfunctory, perfunctoryCount, strictTemplate,
} from '../lib/escalate.js'
import { DAY_RESTDAY, DAY_WORKDAY, settingsOf } from '../lib/settings.js'

const TZ = 'Asia/Shanghai'
// 2026-03-10 00:00 当地（周二），日号就是 DAY。
const DAY = Date.parse('2026-03-09T16:00:00Z')
const at = (hour, minute = 0) => DAY + (hour * 60 + minute) * 60_000
const MIN = (hour, minute = 0) => hour * 60 + minute
const S = settingsOf({})
const E = S.escalate

const fresh = () => dayState(null, DAY)

/** 在 now 之前按间隔塞 n 次敷衍。base 传上一次的状态，才能接着攒 e1Count。 */
function withPerfunctory(n, now, gapMs = 60_000, base = fresh()) {
  let s = dayState(base, DAY)
  for (let i = n; i > 0; i -= 1) s = notePerfunctory(s, { now: now - (i - 1) * gapMs, day: DAY, windowMs: E.E1WindowMs })
  return s
}

test('P4 阈值就是施工书那组初值', () => {
  assert.equal(E.E1Count, 3)
  assert.equal(E.E1WindowMs, 30 * 60_000)
  assert.equal(E.cellMin, 10)
  assert.equal(E.cellMinStep, 10)
  assert.equal(E.L1WorkMin, 15)
  assert.equal(E.L1RestMin, 30)
  assert.equal(E.L2Min, 20)
  assert.equal(E.L3Min, 60)
})

test('P4 三个出口一直都在', () => {
  assert.equal(EXITS.length, 3)
  assert.match(EXITS.join(''), /判定/)
  assert.match(EXITS.join(''), /紧急应用/)
  assert.match(EXITS.join(''), /破窗/)
})

test('P4 跨零点：日号变了计数清零', () => {
  const s = { day: DAY, perfunctory: [at(10)], e1Count: 2, funToday: 999, e3: true }
  const n = dayState(s, DAY + 86_400_000)
  assert.equal(n.perfunctory.length, 0)
  assert.equal(n.e1Count, 0)
  assert.equal(n.funToday, 0)
  assert.equal(n.e3, false, 'E3 只管当天')
})

test('P4 同一天不清零', () => {
  const s = { day: DAY, perfunctory: [at(10)], e1Count: 2, funToday: 999 }
  const n = dayState(s, DAY)
  assert.equal(n.perfunctory.length, 1)
  assert.equal(n.e1Count, 2)
  assert.equal(n.funToday, 999)
})

test('P4 计数只算窗口内的', () => {
  const now = at(10)
  const s = { day: DAY, perfunctory: [now - 29 * 60_000, now - 31 * 60_000] }
  assert.equal(perfunctoryCount(s, now, E.E1WindowMs), 1, '31 分钟前那次不算')
})

test('P4 记敷衍会顺手丢掉窗口外的', () => {
  const now = at(10)
  const s = notePerfunctory({ day: DAY, perfunctory: [now - 40 * 60_000] }, { now, day: DAY, windowMs: E.E1WindowMs })
  assert.deepEqual(s.perfunctory, [now])
})

test('P4 E1 不触发：30 分钟内只敷衍 2 次', () => {
  const now = at(10)
  const r = evaluateE({ state: withPerfunctory(2, now), now, day: DAY, settings: S })
  assert.equal(r.rule, '')
  assert.equal(r.action, null)
})

test('P4 E1 触发：30 分钟内敷衍 3 次 → 审讯舱 10 分钟', () => {
  const now = at(10)
  const r = evaluateE({ state: withPerfunctory(3, now), now, day: DAY, settings: S })
  assert.equal(r.rule, 'E1')
  assert.equal(r.minutes, 10)
  assert.equal(r.action.type, 'cell')
  assert.equal(r.action.allowGeminiOnly, true, '除 Gemini 外全锁')
})

test('P4 E1 触发后计数清掉，不会连续重复触发', () => {
  const now = at(10)
  const r = evaluateE({ state: withPerfunctory(3, now), now, day: DAY, settings: S })
  const again = evaluateE({ state: r.state, now: now + 1000, day: DAY, settings: S })
  assert.equal(again.rule, '')
})

test('P4 E1 窗口是滑动的：隔太久的凑不满 3 次', () => {
  const now = at(11)
  const s = withPerfunctory(3, now, 20 * 60_000) // 三次横跨 40 分钟
  assert.equal(evaluateE({ state: s, now, day: DAY, settings: S }).rule, '')
})

test('P4 E2：当天第 2 次触发 → 审讯舱 20 分钟', () => {
  const now = at(10)
  const first = evaluateE({ state: withPerfunctory(3, now), now, day: DAY, settings: S })
  const later = now + 40 * 60_000
  const r = evaluateE({ state: withPerfunctory(3, later, 60_000, first.state), now: later, day: DAY, settings: S })
  assert.equal(r.rule, 'E2')
  assert.equal(r.minutes, 20)
  assert.equal(first.state.e1Count, 1)
})

test('P4 E3：当天第 3 次 → 当天剩余娱乐全锁 + 次日严格模板', () => {
  const now = at(10)
  let s = fresh()
  let last = null
  for (let i = 0; i < 3; i += 1) {
    const t = now + i * 60 * 60_000
    s = withPerfunctory(3, t, 60_000, s)
    last = evaluateE({ state: s, now: t, day: DAY, settings: S })
    s = last.state
  }
  assert.equal(last.rule, 'E3')
  assert.equal(last.action.type, 'fun-lock-day')
  assert.equal(last.state.e3, true)
  assert.equal(strictTemplate(last.state), true, '次日切严格模板')
})

test('P4 E1′：没有 Gemini → 直接全机锁', () => {
  const r = evaluateEPrime({ now: at(10), cellAt: at(10), hasGemini: false })
  assert.equal(r.rule, 'E1′')
  assert.equal(r.action.type, 'all-lock')
  assert.equal(r.action.allowBout, true, '只放行局界面和紧急应用')
})

test('P4 E1′：60 秒内 Gemini 没进前台 → 全机锁', () => {
  const s = at(10)
  assert.equal(evaluateEPrime({ now: s + CELL_GRACE_MS - 1, cellAt: s }).rule, '', '还没到 60 秒')
  assert.equal(evaluateEPrime({ now: s + CELL_GRACE_MS, cellAt: s }).rule, 'E1′')
})

test('P4 E1′：Gemini 进了前台就不锁', () => {
  const s = at(10)
  assert.equal(evaluateEPrime({ now: s + CELL_GRACE_MS * 2, cellAt: s, geminiForeground: true }).rule, '')
})

test('P4 E1′：没有审讯舱就不管', () => {
  assert.equal(evaluateEPrime({ now: at(10), cellAt: 0 }).rule, '')
})

test('P4 L1 工作时段：娱乐连续 15 分钟 → 锁并进计划模态', () => {
  const now = at(10)
  const s = { ...fresh(), funRun: { pkg: 'com.douyin', since: now - 15 * 60_000 } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.douyin', funPackage: true })
  assert.equal(r.rule, 'L1')
  assert.equal(r.action.type, 'fun-lock-plan')
})

test('P4 L1 工作时段：差一分钟不锁（边界）', () => {
  const now = at(10)
  const s = { ...fresh(), funRun: { pkg: 'com.douyin', since: now - 15 * 60_000 + 1 } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.douyin', funPackage: true })
  assert.equal(r.rule, '')
})

test('P4 L1 非工作时段用 30 分钟', () => {
  const now = at(13)
  const s = { ...fresh(), funRun: { pkg: 'com.douyin', since: now - 20 * 60_000 } }
  const r = evaluateL({ state: s, now, min: MIN(13), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.douyin', funPackage: true })
  assert.equal(r.rule, '', '非工作时段 20 分钟还没到 30')
  const r2 = evaluateL({
    state: { ...fresh(), funRun: { pkg: 'com.douyin', since: now - 30 * 60_000 } },
    now, min: MIN(13), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.douyin', funPackage: true,
  })
  assert.equal(r2.rule, 'L1')
})

test('P4 L1：中间切走就重新计时', () => {
  const now = at(10)
  const s = { ...fresh(), funRun: { pkg: 'com.douyin', since: now - 20 * 60_000 } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.work', funPackage: false })
  assert.equal(r.rule, '')
  assert.equal(r.state.funRun, null, '换了应用就清掉连续计时')
})

test('P4 L1：休息日按非工作时段算', () => {
  const now = at(15)
  const s = { ...fresh(), funRun: { pkg: 'com.douyin', since: now - 16 * 60_000 } }
  const r = evaluateL({ state: s, now, min: MIN(15), day: DAY, kind: DAY_RESTDAY, settings: S, foreground: 'com.douyin', funPackage: true })
  assert.equal(r.rule, '', '休息日 16 分钟不该按工作时段 15 分钟锁')
})

test('P4 L2：工作时段用了没声明的应用 20 分钟 → 局官核对', () => {
  const now = at(10)
  const s = { ...fresh(), l2: { pkg: 'com.bilibili', since: now - 20 * 60_000, askedAt: 0 } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.bilibili', declared: ['com.work'] })
  assert.equal(r.rule, 'L2')
  assert.equal(r.action.type, 'confirm')
  assert.equal(r.action.minutes, 2)
})

test('P4 L2：差一分钟不核对（边界）', () => {
  const now = at(10)
  const s = { ...fresh(), l2: { pkg: 'com.bilibili', since: now - 20 * 60_000 + 1, askedAt: 0 } }
  assert.equal(evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.bilibili' }).rule, '')
})

test('P4 L2：局里声明过的应用不算跑偏', () => {
  const now = at(10)
  const s = { ...fresh(), l2: { pkg: 'com.work', since: now - 30 * 60_000, askedAt: 0 } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.work', declared: ['com.work'] })
  assert.equal(r.rule, '')
})

test('P4 L2：问了 2 分钟不答 → 锁那个应用', () => {
  const now = at(10)
  const s = { ...fresh(), l2: { pkg: 'com.bilibili', since: now - 25 * 60_000, askedAt: now - L2_GRACE_MS } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.bilibili' })
  assert.equal(r.rule, 'L2')
  assert.equal(r.action.type, 'lock-package')
  assert.equal(r.action.pkg, 'com.bilibili')
})

test('P4 L2：差一秒不锁（边界）', () => {
  const now = at(10)
  const s = { ...fresh(), l2: { pkg: 'com.bilibili', since: now - 25 * 60_000, askedAt: now - L2_GRACE_MS + 1 } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.bilibili' })
  assert.equal(r.action, null)
})

test('P4 L2：他答了就不锁', () => {
  const now = at(10)
  const s = { ...fresh(), l2: { pkg: 'com.bilibili', since: now - 25 * 60_000, askedAt: now - L2_GRACE_MS } }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.bilibili', answeredAt: now - 1000 })
  assert.equal(r.rule, '')
  assert.equal(r.state.l2, null, '答完重新计时')
})

test('P4 L2：非工作时段不核对', () => {
  const now = at(13)
  const s = { ...fresh(), l2: { pkg: 'com.bilibili', since: now - 40 * 60_000, askedAt: 0 } }
  assert.equal(evaluateL({ state: s, now, min: MIN(13), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.bilibili' }).rule, '')
})

// ---------------------------------------------------------------- P5 双信号提醒（二）
//
// 信号二：前台偏离任务外的 App。局在跑的时候不管几点，开了没声明的应用就是偏离
// ——局本来就规定了这一段做什么。这里只放宽 L2 的观察窗口，work 不动：
// work 还管着 L1/L3 的阈值和娱乐累计，那两档在局里一起放宽就是另一件事了。

test('P5 局里偏离：非工作时段，局在跑时核对照常触发', () => {
  const now = at(13)
  const s = { ...fresh(), l2: { pkg: 'com.bilibili', since: now - 20 * 60_000, askedAt: 0 } }
  const r = evaluateL({
    state: s, now, min: MIN(13), day: DAY, kind: DAY_WORKDAY, settings: S,
    foreground: 'com.bilibili', declared: ['com.work'], inBout: true,
  })
  assert.equal(r.rule, 'L2')
  assert.equal(r.action.type, 'confirm')
})

test('P5 局里偏离：局在跑但用的是声明过的应用，不核对', () => {
  const now = at(13)
  const s = { ...fresh(), l2: { pkg: 'com.work', since: now - 40 * 60_000, askedAt: 0 } }
  const r = evaluateL({
    state: s, now, min: MIN(13), day: DAY, kind: DAY_WORKDAY, settings: S,
    foreground: 'com.work', declared: ['com.work'], inBout: true,
  })
  assert.equal(r.rule, '')
})

test('P5 局里偏离：局在跑不放宽 L1（娱乐累计按原来的时段算）', () => {
  const now = at(13)
  const s = { ...fresh(), funRun: { pkg: 'com.douyin', since: now - 16 * 60_000 } }
  const r = evaluateL({
    state: s, now, min: MIN(13), day: DAY, kind: DAY_WORKDAY, settings: S,
    foreground: 'com.douyin', funPackage: true, declared: [], inBout: true,
  })
  assert.equal(r.rule, '', '非工作时段 16 分钟不该按工作时段 15 分钟锁')
})

test('P4 L3：当天娱乐累计 60 分钟 → 剩余全天锁', () => {
  const now = at(10)
  const s = { ...fresh(), funToday: 50 * 60_000 }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, funDeltaMs: 10 * 60_000 })
  assert.equal(r.rule, 'L3')
  assert.equal(r.action.type, 'fun-lock-day')
})

test('P4 L3：差一分钟不锁（边界）', () => {
  const now = at(10)
  const s = { ...fresh(), funToday: 50 * 60_000 }
  const r = evaluateL({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, funDeltaMs: 10 * 60_000 - 1 })
  assert.equal(r.rule, '')
  assert.equal(r.state.funToday, 60 * 60_000 - 1, '累计照样加上去')
})

test('P4 L3：跨零点累计清零', () => {
  const r = evaluateL({ state: { ...fresh(), funToday: 59 * 60_000 }, now: at(10), min: MIN(10), day: DAY + 86_400_000, kind: DAY_WORKDAY, settings: S, funDeltaMs: 60_000 })
  assert.equal(r.rule, '')
})

test('P4 敷衍优先于沉迷：两串同时成立时报 E', () => {
  const now = at(10)
  const s = {
    ...withPerfunctory(3, now),
    funRun: { pkg: 'com.douyin', since: now - 20 * 60_000 },
  }
  const r = evaluate({ state: s, now, min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: 'com.douyin', funPackage: true })
  assert.equal(r.track, 'E')
  assert.equal(r.rule, 'E1')
})

test('P4 都没有就什么都不做', () => {
  const r = evaluate({ state: fresh(), now: at(10), min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S })
  assert.equal(r.rule, '')
  assert.equal(r.track, '')
})

test('P4 改紧阈值后立刻按新的算', () => {
  const tight = settingsOf({ day: { escalate: { E1Count: 2 } } })
  const now = at(10)
  const r = evaluateE({ state: withPerfunctory(2, now), now, day: DAY, settings: tight })
  assert.equal(r.rule, 'E1', '设成 2 次就 2 次触发')
})

test('P4 明天切严格模板只对 E3 成立', () => {
  assert.equal(strictTemplate({ strictTomorrow: true }), true)
  assert.equal(strictTemplate(fresh()), false)
  assert.equal(strictTemplate(null), false)
})

test('P4 空状态不崩', () => {
  assert.equal(evaluateE({ state: null, now: 0, day: DAY, settings: S }).rule, '')
  assert.equal(evaluateL({ state: undefined, now: 0, min: 0, day: DAY, kind: DAY_WORKDAY, settings: S }).rule, '')
})

test('P4 E1 窗口的左右边界：正好 30 分钟还算', () => {
  const now = at(10)
  const right = { day: DAY, perfunctory: [now - 30 * 60_000, now - 20 * 60_000, now - 10 * 60_000] }
  assert.equal(perfunctoryCount(right, now, E.E1WindowMs), 3, '正好 30 分钟前那次还算在窗口里')
  const out = { day: DAY, perfunctory: [now - 30 * 60_000 - 1, now - 20 * 60_000, now - 10 * 60_000] }
  assert.equal(perfunctoryCount(out, now, E.E1WindowMs), 2, '差一毫秒就出窗口')
})

test('P4 状态里没有 perfunctory 数组也不崩', () => {
  const s = dayState({ day: DAY, e1Count: 1 }, DAY)
  assert.deepEqual(s.perfunctory, [])
  assert.equal(s.e1Count, 1)
})

test('P4 一次攒到 4 次敷衍也还是 E1，不跳级', () => {
  const now = at(10)
  const r = evaluateE({ state: withPerfunctory(4, now, 5 * 60_000), now, day: DAY, settings: S })
  assert.equal(r.rule, 'E1')
  assert.equal(r.state.e1Count, 1)
})

test('P4 L3 累计会把增量加上去', () => {
  const r = evaluateL({ state: { ...fresh(), funToday: 10 * 60_000 }, now: at(10), min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, funDeltaMs: 5 * 60_000 })
  assert.equal(r.state.funToday, 15 * 60_000)
})

test('P4 L1 前面没有娱乐应用就不计时', () => {
  const r = evaluateL({ state: fresh(), now: at(10), min: MIN(10), day: DAY, kind: DAY_WORKDAY, settings: S, foreground: null, funPackage: false })
  assert.equal(r.rule, '')
  assert.equal(r.state.funRun, null)
})

test('P4 E1′ 没明说有没有 Gemini 时按有处理', () => {
  const s = at(10)
  assert.equal(evaluateEPrime({ now: s + CELL_GRACE_MS, cellAt: s, geminiForeground: false }).rule, 'E1′')
  assert.equal(evaluateEPrime({ now: s + CELL_GRACE_MS, cellAt: s, hasGemini: true, geminiForeground: true }).rule, '')
})
