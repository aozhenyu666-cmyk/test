// 第九轮总验收第 1 条：片段和日摘要与快照逐条对得上（回放测试）。
//
// 和 brain.k1-k3.test.mjs 的分工：那边一条规则一个用例，钉的是"某条规则对不对"；
// 这里喂**一整天连续的手机快照**，然后把整张片段表、整张摘要表与手写的期望逐条对齐——
// 期望值是照"手机看到的事实"手算的（几点在哪个 App、隔了多久），不是从实现里抄出来的。
//
// 回放的口径：手机每次 /plan 报一次前台，插件就 observe 一次。真实手机上还有切走又切回、
// 中途息屏、跨天这些情况，这里只覆盖"一天里有 4 段、其中 2 段娱乐、一段跨小时"这一条主线，
// 别的情形各自在 K1 的那 19 条里。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  emptyState, normalizeState, observe, noteLock, segsOf, digestOf, rollDay,
} from '../lib/episodes.js'

const MIN = 60_000
const HOUR = 3_600_000
// 北京时间 2026-11-14 00:00 = UTC 2026-11-13 16:00
const DAY0 = Date.UTC(2026, 10, 13, 16, 0, 0)
const bj = (dayIndex, h, m = 0) => DAY0 + dayIndex * 86_400_000 + h * HOUR + m * MIN
const dayOf = (ms) => new Date(ms + 8 * HOUR).toISOString().slice(0, 10)
const minuteOf = (ms) => { const d = new Date(ms + 8 * HOUR); return d.getUTCHours() * 60 + d.getUTCMinutes() }
const hourOf = (ms) => Math.floor(minuteOf(ms) / 60)
const DAY = '2026-11-14'

const snap = (now, app, extra = {}) => ({ now, day: dayOf(now), minute: minuteOf(now), app, fun: false, minutes: 0, screenOn: true, bout: null, night: false, ...extra })

/** 手机这一天的 /plan 前台序列。手机自报的 minutes 是"这个 App 已经开了多久"。 */
const day = [
  { at: bj(0, 7, 5), app: 'com.tencent.mm', minutes: 1 },              // 起床先看微信
  { at: bj(0, 7, 20), app: 'com.ss.android.ugc.aweme', minutes: 15, fun: true }, // 刷抖音 15 分钟
  { at: bj(0, 8, 5), app: 'com.notes', minutes: 0 },                    // 去记笔记
  { at: bj(0, 8, 30), app: 'com.miui.home', minutes: 0 },               // 回桌面
  { at: bj(0, 21, 40), app: 'com.ss.android.ugc.aweme', minutes: 40, fun: true }, // 晚上又刷
  { at: bj(0, 22, 20), app: 'com.tencent.mm', minutes: 2 },             // 收尾去看微信
  { at: bj(0, 22, 30), app: 'com.miui.home', minutes: 0 },              // 放下手机前回桌面
]

function replay(list, { state = emptyState(), lockAt = 0 } = {}) {
  let s = state
  for (const x of list) {
    if (lockAt && x.at === lockAt) s = noteLock(s, x.at)
    s = observe(s, snap(x.at, x.app, x)).state
  }
  return s
}

/** 期望的片段表：照手机看到的事实手算（起点 / 终点 / 分钟 / 是不是娱乐）。
 *  两处"起点比快照更早"，都是手机自报「这个 App 已经开了几分钟」造成的：
 *  07:20 报 15 分钟本应从 07:05 起，但 07:05 那段是微信，所以被上一段的结束压回 07:20；
 *  21:40 报 40 分钟从 21:00 起，中间没有别的段，就真的从 21:00 算。 */
const EXPECT_SEGS = [
  { app: 'com.tencent.mm', start: bj(0, 7, 4), end: bj(0, 7, 20), min: 16, fun: false },
  { app: 'com.ss.android.ugc.aweme', start: bj(0, 7, 20), end: bj(0, 8, 5), min: 45, fun: true },
  { app: 'com.notes', start: bj(0, 8, 5), end: bj(0, 8, 30), min: 25, fun: false },
  { app: 'com.ss.android.ugc.aweme', start: bj(0, 21, 0), end: bj(0, 22, 20), min: 80, fun: true },
  { app: 'com.tencent.mm', start: bj(0, 22, 20), end: bj(0, 22, 30), min: 10, fun: false },
]

test('回放·整张片段表与手机快照逐条对得上', () => {
  const s = replay(day)
  const segs = segsOf(s, DAY, bj(0, 23))
  assert.equal(segs.length, EXPECT_SEGS.length, '片段条数：' + JSON.stringify(segs.map((x) => x.app)))
  for (const [i, want] of EXPECT_SEGS.entries()) {
    const got = segs[i]
    assert.equal(got.app, want.app, '第 ' + (i + 1) + ' 段的 App')
    assert.equal(got.start, want.start, '第 ' + (i + 1) + ' 段的起点')
    assert.equal(got.end, want.end, '第 ' + (i + 1) + ' 段的终点')
    assert.equal(Math.round((got.end - got.start) / MIN), want.min, '第 ' + (i + 1) + ' 段的分钟')
    assert.equal(Boolean(got.fun), want.fun, '第 ' + (i + 1) + ' 段是不是娱乐')
    assert.equal(got.day, DAY)
  }
})

test('回放·整张摘要表与快照对得上（逐小时、汇总、四个数）', () => {
  const s = replay(day)
  const d = digestOf(s, DAY, { now: bj(0, 23), hourOf })
  // 娱乐分钟按小时拆：07:20–08:05 → 7 点 40 + 8 点 5；21:40–22:20 → 各 20。
  const wantHour = { 7: 40, 8: 5, 21: 60, 22: 20 }
  for (const [h, m] of Object.entries(wantHour)) assert.equal(d.funByHour[Number(h)], m, h + ' 点的娱乐分钟')
  for (let h = 0; h < 24; h += 1) {
    if (!(h in wantHour)) assert.equal(d.funByHour[h], 0, h + ' 点应当是 0')
  }
  assert.equal(d.funMin, 125, '娱乐总分钟')
  assert.deepEqual(d.topFun, [{ app: 'com.ss.android.ugc.aweme', min: 125 }])
  assert.equal(d.segs, 5, '片段数')
  assert.equal(d.funSegs, 2, '娱乐片段数')
  assert.equal(d.day, DAY)
  // 一天没开过局：比率算不出来给 null，不给 0（0 会被读成"一个都没做成"）。
  assert.equal(d.bouts.opened, 0)
  assert.equal(d.selfOpenRatio, null)
  assert.equal(d.deliverRatio, null)
  assert.equal(d.badRatio, null)
  assert.equal(d.deviationMin, 0)
})

test('回放·摘要与片段互相自洽：娱乐分钟就是那两段之和', () => {
  const s = replay(day)
  const segs = segsOf(s, DAY, bj(0, 23))
  const fromSegs = segs.filter((x) => x.fun).reduce((a, x) => a + (x.end - x.start) / MIN, 0)
  const d = digestOf(s, DAY, { now: bj(0, 23), hourOf })
  assert.equal(fromSegs, 125)
  assert.equal(d.funMin, fromSegs, '摘要不能比片段多也不能少')
  const byHour = d.funByHour.reduce((a, n) => a + n, 0)
  assert.equal(byHour, d.funMin, '按小时拆完还是要加得回总数')
})

test('回放·分几批喂进去，结果和一次喂完完全一样', () => {
  const one = replay(day)
  // 模拟真实情况：进程中途重启，状态从盘上读回来（normalize 一遍）再接着喂。
  const batches = replay(day.slice(3), { state: normalizeState(replay(day.slice(0, 3))) })
  assert.deepEqual(segsOf(batches, DAY, bj(0, 23)), segsOf(one, DAY, bj(0, 23)))
  assert.deepEqual(
    digestOf(batches, DAY, { now: bj(0, 23), hourOf }),
    digestOf(one, DAY, { now: bj(0, 23), hourOf }),
  )
})

test('回放·被锁的那一刻记在摘要上，片段不受影响', () => {
  const at = bj(0, 7, 20)
  const s = replay(day, { lockAt: at })
  assert.equal(digestOf(s, DAY, { now: bj(0, 23), hourOf }).locks, 1)
  assert.deepEqual(segsOf(s, DAY, bj(0, 23)).map((x) => x.app), EXPECT_SEGS.map((x) => x.app))
})

test('回放·换天后昨天的摘要定稿，和当时算的一模一样', () => {
  const s = replay(day)
  const live = digestOf(s, DAY, { now: bj(0, 23), hourOf })
  // 先滚到当天一次：lastDay 由 rollDay 记下（observe 不写这个字段）。
  const same = rollDay(s, { day: DAY, now: bj(0, 23), hourOf }).state
  assert.equal(rollDay(s, { day: DAY, now: bj(0, 23), hourOf }).finalized, null, '同一天不算换天')
  const r = rollDay(same, { day: '2026-11-15', now: bj(1, 7), hourOf })
  assert.equal(r.finalized.day, DAY)
  assert.deepEqual(r.state.digests[DAY], live, '定稿的摘要必须等于当天算出的那份')
  assert.equal(r.state.digests[DAY].funMin, 125)
})
