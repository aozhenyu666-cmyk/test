// 行为大脑第一版：K1 片段与日摘要、K2 模式发现、K3 模式卡；法典视图；预案日落。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  emptyState, observe, noteLock, noteBout, digestOf, rollDay, segsOf, MERGE_GAP_MS, STALE_GAP_MS,
} from '../lib/episodes.js'
import { detect, DEFAULTS } from '../lib/patterns.js'
import { emptyCards, update, explain, note, index, list, FADE_DAYS } from '../lib/cards.js'
import { buildCodex, CONSTITUTION, lawsOf } from '../lib/codex.js'
import { emptyDoc, proposeChange, presetOf, settleSunset, renew, SUNSET_MS, validatePreset } from '../lib/presets.js'
import { DEFAULT_SETTINGS } from '../lib/settings.js'

const MIN = 60_000
const HOUR = 3_600_000
// 北京时间 2026-11-14 00:00 = UTC 2026-11-13 16:00
const DAY0 = Date.UTC(2026, 10, 13, 16, 0, 0)
const bj = (dayIndex, h, m = 0) => DAY0 + dayIndex * 86_400_000 + h * HOUR + m * MIN
const dayOf = (ms) => new Date(ms + 8 * HOUR).toISOString().slice(0, 10)
const minuteOf = (ms) => { const d = new Date(ms + 8 * HOUR); return d.getUTCHours() * 60 + d.getUTCMinutes() }
const hourOf = (ms) => Math.floor(minuteOf(ms) / 60)

const snap = (now, app, extra = {}) => ({ now, day: dayOf(now), minute: minuteOf(now), app, fun: false, minutes: 0, screenOn: true, bout: null, night: false, ...extra })

function feed(state, list) {
  let s = state
  for (const x of list) s = observe(s, x).state
  return s
}

// ---------------------------------------------------------------- K1 片段

test('K1：换 App 就切一段，时长按两次快照算', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 9), 'com.douyin', { fun: true }),
    snap(bj(0, 9, 5), 'com.douyin', { fun: true }),
    snap(bj(0, 9, 12), 'com.notes'),
  ])
  assert.equal(s.segs.length, 1)
  assert.equal(s.segs[0].app, 'com.douyin')
  assert.equal(s.segs[0].min, 12)
  assert.equal(s.open.app, 'com.notes')
})

test('K1：桌面不算一段，回到桌面就收尾', () => {
  const s = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true }), snap(bj(0, 9, 3), 'com.miui.home')])
  assert.equal(s.segs.length, 1)
  assert.equal(s.open, null)
})

test('K1：同一个 App 离开不到 2 分钟又回来，接着上一段', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 9), 'com.douyin', { fun: true }),
    snap(bj(0, 9, 5), 'com.wechat'),
    snap(bj(0, 9, 5) + MERGE_GAP_MS - 1000, 'com.douyin', { fun: true }),
    snap(bj(0, 9, 20), 'com.notes'),
  ])
  const dy = s.segs.filter((x) => x.app === 'com.douyin')
  assert.equal(dy.length, 1)
  assert.equal(dy[0].start, bj(0, 9))
})

test('K1：手机太久没报，上一段按最后看见 + 1 分钟收尾', () => {
  const s = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true }), snap(bj(0, 9) + STALE_GAP_MS + 5 * MIN, 'com.notes')])
  assert.equal(s.segs[0].end, bj(0, 9) + MIN)
})

test('K1：手机报的已开分钟数让片段开始得更早，但不早于上一段结束', () => {
  const a = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true, minutes: 7 })])
  assert.equal(a.open.start, bj(0, 9) - 7 * MIN)
  const b = feed(emptyState(), [snap(bj(0, 9), 'com.notes'), snap(bj(0, 9, 2), 'com.douyin', { fun: true, minutes: 7 })])
  assert.equal(b.open.start, bj(0, 9, 2))
})

test('K1：息屏收尾', () => {
  const s = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true }), snap(bj(0, 9, 4), null, { screenOn: false })])
  assert.equal(s.segs.length, 1)
})

test('K1：处境——局内 / 无局 / 深夜', () => {
  let s = feed(emptyState(), [snap(bj(0, 10), 'com.study', { bout: { id: 'b1' } })])
  assert.equal(s.open.context, '局内')
  s = feed(emptyState(), [snap(bj(0, 1), 'com.douyin', { fun: true, night: true })])
  assert.equal(s.open.context, '深夜')
  s = feed(emptyState(), [snap(bj(0, 15), 'com.douyin', { fun: true })])
  assert.equal(s.open.context, '无局')
})

test('K1：前因「刚醒」——当天 5 点后第一次亮屏 15 分钟内开始的', () => {
  const s = feed(emptyState(), [snap(bj(0, 7, 2), 'com.miui.home'), snap(bj(0, 7, 5), 'com.douyin', { fun: true })])
  assert.equal(s.open.cause, '刚醒')
})

test('K1：凌晨 3 点看一眼不算醒', () => {
  const s = feed(emptyState(), [snap(bj(0, 3), 'com.miui.home'), snap(bj(0, 3, 5), 'com.douyin', { fun: true, night: true })])
  assert.equal(s.open.cause, '')
})

test('K1：前因「刚收局」与后果「开局」', () => {
  let s = feed(emptyState(), [
    snap(bj(0, 9), 'com.miui.home'),
    snap(bj(0, 10), 'com.study', { bout: { id: 'b1' } }),
    snap(bj(0, 10, 30), 'com.miui.home'),
    snap(bj(0, 10, 32), 'com.douyin', { fun: true }),
  ])
  assert.equal(s.open.cause, '刚收局')
  s = feed(s, [snap(bj(0, 10, 50), 'com.miui.home'), snap(bj(0, 10, 52), 'com.study', { bout: { id: 'b2' } })])
  const dy = s.segs.find((x) => x.app === 'com.douyin')
  assert.equal(dy.effect, '开局')
})

test('K1：被锁——那一段记「被锁」，接下来的片段前因「刚被锁」', () => {
  let s = feed(emptyState(), [snap(bj(0, 15), 'com.douyin', { fun: true })])
  s = noteLock(s, bj(0, 15, 3))
  s = feed(s, [snap(bj(0, 15, 4), 'com.bili', { fun: true })])
  assert.equal(s.segs[0].effect, '被锁')
  assert.equal(s.open.cause, '刚被锁')
})

test('K1：连着刷两个娱乐 App，前一段后果「换娱乐」', () => {
  const s = feed(emptyState(), [
    snap(bj(0, 15), 'com.douyin', { fun: true }),
    snap(bj(0, 15, 20), 'com.miui.home'),
    snap(bj(0, 15, 21), 'com.bili', { fun: true }),
  ])
  assert.equal(s.segs[0].effect, '换娱乐')
})

test('K1：日摘要——娱乐分钟按小时拆，跨小时的片段分到两个小时', () => {
  const s = feed(emptyState(), [snap(bj(0, 7, 40), 'com.douyin', { fun: true }), snap(bj(0, 8, 20), 'com.notes')])
  const d = digestOf(s, '2026-11-14', { now: bj(0, 9), hourOf })
  assert.equal(d.funByHour[7], 20)
  assert.equal(d.funByHour[8], 20)
  assert.equal(d.funMin, 40)
  assert.equal(d.topFun[0].app, 'com.douyin')
})

test('K1：日摘要的娱乐总分钟取手机报的当天累计与片段的较大值', () => {
  const s = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true, todayFun: 95 }), snap(bj(0, 9, 10), 'com.notes')])
  assert.equal(digestOf(s, '2026-11-14', { now: bj(0, 10), hourOf }).funMin, 95)
})

test('K1：局的计数与主动开局比', () => {
  let s = emptyState()
  s = noteBout(s, { day: '2026-11-14', protocolId: 'study', outcome: 'open', self: true })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'study', outcome: 'open', self: false })
  s = noteBout(s, { day: '2026-11-14', protocolId: 'study', outcome: 'bad' })
  const d = digestOf(s, '2026-11-14', { now: bj(0, 20), hourOf })
  assert.equal(d.bouts.opened, 2)
  assert.equal(d.selfOpenRatio, 0.5)
  assert.equal(d.badRatio, 0.5)
})

test('K1：换天时昨天的摘要定稿', () => {
  let s = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true }), snap(bj(0, 9, 30), 'com.notes')])
  s = rollDay(s, { day: '2026-11-14', now: bj(0, 10), hourOf }).state
  const r = rollDay(s, { day: '2026-11-15', now: bj(1, 7), hourOf })
  assert.equal(r.finalized.day, '2026-11-14')
  assert.equal(r.state.digests['2026-11-14'].funMin, 30)
})

test('K1：片段里没有任何屏幕文字字段', () => {
  const s = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true, text: '不该出现' }), snap(bj(0, 9, 5), 'com.notes')])
  assert.equal(JSON.stringify(s).includes('不该出现'), false)
})

test('K1：segsOf 带上还开着的那一段', () => {
  const s = feed(emptyState(), [snap(bj(0, 9), 'com.douyin', { fun: true }), snap(bj(0, 9, 10), 'com.douyin', { fun: true })])
  assert.equal(segsOf(s, '2026-11-14', bj(0, 9, 10)).length, 1)
})

// ---------------------------------------------------------------- K2 模式发现

/** 造 n 天的摘要：morning=早上 7 点刷多少分钟，其余时段每小时 base 分钟。 */
function digests(n, { morning = 0, morningDays = n, base = 2 } = {}) {
  const out = {}
  for (let i = 0; i < n; i += 1) {
    const day = dayOf(bj(i, 12))
    const funByHour = Array(24).fill(0).map((_, h) => (h >= 9 && h < 22 ? base : 0))
    if (i < morningDays) funByHour[7] = morning
    out[day] = { day, funByHour, funMin: funByHour.reduce((a, b) => a + b, 0), bouts: { opened: 0, self: 0, done: 0, bad: 0, byProtocol: {} }, locks: 0 }
  }
  return out
}
const today = dayOf(bj(20, 12))

test('K2 时段习惯：14 天里 7 天早上 7 点刷 30 分钟 → 确认', () => {
  const c = detect({ digests: digests(14, { morning: 30, morningDays: 7 }), segs: [], today })
  const m = c.find((x) => x.type === '时段习惯')
  assert.ok(m)
  assert.equal(m.level, 'confirmed')
  assert.equal(m.stats.from, 7)
  assert.equal(m.stats.days, 7)
  assert.equal(m.evidence.days.length, 7)
})

test('K2 时段习惯：只出现 3 天 → 观察中', () => {
  const c = detect({ digests: digests(14, { morning: 30, morningDays: 3 }), segs: [], today })
  assert.equal(c.find((x) => x.type === '时段习惯').level, 'watch')
})

test('K2 时段习惯：随处都刷得差不多（没有特别的时段）→ 不确认', () => {
  const c = detect({ digests: digests(14, { morning: 20, base: 18 }), segs: [], today })
  assert.equal(c.filter((x) => x.type === '时段习惯' && x.level === 'confirmed').length, 0)
})

test('K2 时段习惯：相邻小时合并成一个时段', () => {
  const d = digests(10, { morning: 30 })
  for (const v of Object.values(d)) v.funByHour[8] = 25
  const m = detect({ digests: d, segs: [], today }).filter((x) => x.type === '时段习惯')
  assert.equal(m.length, 1)
  assert.equal(m[0].key, 'hour:7-9')
})

test('K2 时段习惯：今天的摘要不算（今天还没过完）', () => {
  const d = digests(14, { morning: 30 })
  const last = Object.keys(d).sort().pop()
  const c = detect({ digests: d, segs: [], today: last })
  assert.equal(c.find((x) => x.type === '时段习惯').stats.days, 13)
})

test('K2 前因链：刚醒就刷，10 天里 7 天 → 确认', () => {
  const d = digests(10)
  const segs = Object.keys(d).slice(0, 7).map((day, i) => ({ id: 'S' + i, day, fun: true, cause: '刚醒', start: bj(i, 7), end: bj(i, 7, 30), app: 'com.douyin' }))
  const m = detect({ digests: d, segs, today }).find((x) => x.key === 'cause:刚醒')
  assert.equal(m.level, 'confirmed')
  assert.equal(m.stats.count, 7)
  assert.equal(m.evidence.segs.length, 7)
})

test('K2 前因链：同一天醒来连刷两个 App 只算一次', () => {
  const d = digests(10)
  const day = Object.keys(d)[0]
  const segs = [
    { id: 'a', day, fun: true, cause: '刚醒', start: bj(0, 7), end: bj(0, 7, 10), app: 'x' },
    { id: 'b', day, fun: true, cause: '刚醒', start: bj(0, 7, 11), end: bj(0, 7, 30), app: 'y' },
    { id: 'c', day: Object.keys(d)[1], fun: true, cause: '刚醒', start: bj(1, 7), end: bj(1, 7, 30), app: 'x' },
  ]
  assert.equal(detect({ digests: d, segs, today }).find((x) => x.key === 'cause:刚醒').stats.count, 2)
})

test('K2 前因链：刚收局按收局次数算比例', () => {
  const d = digests(10)
  for (const v of Object.values(d)) v.bouts = { opened: 1, self: 0, done: 1, bad: 0, byProtocol: {} }
  const segs = Object.keys(d).slice(0, 6).map((day, i) => ({ id: 'S' + i, day, fun: true, cause: '刚收局', start: bj(i, 11), end: bj(i, 11, 20), app: 'x' }))
  const m = detect({ digests: d, segs, today }).find((x) => x.key === 'cause:刚收局')
  assert.equal(m.stats.chances, 10)
  assert.equal(m.stats.ratio, 0.6)
  assert.equal(m.level, 'confirmed')
})

test('K2 绕路：被锁后又刷，8 次里 5 次 → 确认', () => {
  const d = digests(8)
  for (const v of Object.values(d)) v.locks = 1
  const segs = Object.keys(d).slice(0, 5).map((day, i) => ({ id: 'S' + i, day, fun: true, cause: '刚被锁', start: bj(i, 15), end: bj(i, 15, 10), app: 'com.bili' }))
  const m = detect({ digests: d, segs, today }).find((x) => x.type === '绕路')
  assert.equal(m.level, 'confirmed')
  assert.deepEqual(m.stats.apps, ['com.bili'])
})

test('K2 逃避：设计局开了 5 次空了 4 次，其他局都做成 → 确认', () => {
  const d = digests(10)
  const days = Object.keys(d)
  days.slice(0, 5).forEach((day, i) => {
    d[day].bouts = { opened: 2, self: 0, done: 1, bad: i < 4 ? 1 : 0, byProtocol: { design: { opened: 1, self: 0, done: i < 4 ? 0 : 1, bad: i < 4 ? 1 : 0 }, execute: { opened: 1, self: 0, done: 1, bad: 0 } } }
  })
  const m = detect({ digests: d, segs: [], today }).find((x) => x.key === 'avoid:design')
  assert.equal(m.level, 'confirmed')
  assert.equal(m.stats.rate, 0.8)
  assert.equal(detect({ digests: d, segs: [], today }).some((x) => x.key === 'avoid:execute'), false)
})

test('K2：没有数据什么都不出', () => {
  assert.deepEqual(detect({ digests: {}, segs: [], today }), [])
})

test('K2：门槛可调', () => {
  const d = digests(14, { morning: 30, morningDays: 3 })
  const c = detect({ digests: d, segs: [], today, opts: { minDays: 3 } })
  assert.equal(c.find((x) => x.type === '时段习惯').level, 'confirmed')
  assert.equal(DEFAULTS.minDays, 4)
})

// ---------------------------------------------------------------- K3 模式卡

const cand = (level, days = ['2026-11-14', '2026-11-15'], segs = ['S1']) => ({
  key: 'hour:7-8', type: '时段习惯', title: '07:00–08:00 刷娱乐', stats: { days: days.length }, evidence: { days, segs }, level,
})

test('K3：新候选建卡，编号递增，观察中', () => {
  const r = update(emptyCards(), [cand('watch')], { day: '2026-11-16', now: 1 })
  assert.deepEqual(r.created, ['M-01'])
  assert.equal(list(r.cards)[0].status, '观察中')
})

test('K3：过门槛 → 已确认；同一个 key 不再建第二张', () => {
  let c = update(emptyCards(), [cand('watch')], { day: '2026-11-16', now: 1 }).cards
  const r = update(c, [cand('confirmed')], { day: '2026-11-17', now: 2 })
  assert.equal(r.created.length, 0)
  assert.equal(list(r.cards)[0].status, '已确认')
  assert.deepEqual(r.changed, [{ id: 'M-01', from: '观察中', to: '已确认' }])
})

test('K3：有生效预案以它为依据 → 有对策；预案没了退回已确认', () => {
  let c = update(emptyCards(), [cand('confirmed')], { day: '2026-11-16', now: 1 }).cards
  c = update(c, [cand('confirmed')], { day: '2026-11-17', now: 2, backed: ['M-01'] }).cards
  assert.equal(list(c)[0].status, '有对策')
  c = update(c, [cand('confirmed')], { day: '2026-11-18', now: 3, backed: [] }).cards
  assert.equal(list(c)[0].status, '已确认')
})

test('K3：连续 7 天没出现 → 已消退；再出现 → 复发', () => {
  let c = update(emptyCards(), [cand('confirmed')], { day: '2026-11-10', now: 1 }).cards
  c = update(c, [], { day: '2026-11-16', now: 2 }).cards
  assert.equal(list(c)[0].status, '已确认', '6 天还不算')
  c = update(c, [], { day: '2026-11-17', now: 3 }).cards
  assert.equal(list(c)[0].status, '已消退')
  c = update(c, [cand('confirmed')], { day: '2026-11-25', now: 4 }).cards
  assert.equal(list(c)[0].status, '复发')
  assert.equal(FADE_DAYS, 7)
})

test('K3：解释引用卡上没有的编号会被去掉；一个都没引到标「猜测」', () => {
  const c = update(emptyCards(), [cand('confirmed')], { day: '2026-11-16', now: 1 }).cards
  const r = explain(c, 'M-01', { text: '醒来没有计划', refs: ['2026-11-14', 'S999'] }, 10)
  assert.deepEqual(r.dropped, ['S999'])
  assert.equal(r.guess, false)
  assert.deepEqual(list(r.cards)[0].explain.refs, ['2026-11-14'])
  const g = explain(r.cards, 'M-01', { text: '可能是太累', refs: ['瞎编的'] }, 20)
  assert.equal(g.guess, true)
})

test('K3：解释改了，旧的进历史（被取代）', () => {
  let c = update(emptyCards(), [cand('confirmed')], { day: '2026-11-16', now: 1 }).cards
  c = explain(c, 'M-01', { text: '第一版', refs: ['S1'] }, 10).cards
  c = explain(c, 'M-01', { text: '第二版', refs: ['S1'] }, 20).cards
  const card = list(c)[0]
  assert.equal(card.explain.text, '第二版')
  assert.equal(card.history[0].text, '第一版')
  assert.equal(card.history[0].supersededAt, 20)
})

test('K3：他的说法记在卡上，索引里标「他有话说」', () => {
  let c = update(emptyCards(), [cand('confirmed')], { day: '2026-11-16', now: 1 }).cards
  c = note(c, 'M-01', '我早上其实在等快递', 5).cards
  assert.match(index(c).join('\n'), /他有话说/)
})

test('K3：索引只放活着的卡，观察中和已消退不放，总长 ≤ 300', () => {
  const many = Array.from({ length: 30 }, (_, i) => ({ ...cand('confirmed'), key: 'k' + i, title: '一个很长的模式标题'.repeat(3) + i }))
  const c = update(emptyCards(), [...many, { ...cand('watch'), key: 'w' }], { day: '2026-11-16', now: 1 }).cards
  const lines = index(c)
  assert.ok(lines.join('').length <= 300)
  assert.equal(lines.some((l) => l.includes('观察中')), false)
})

// ---------------------------------------------------------------- 法典

test('法典：四级都在，宪法里没做到的照实标出', () => {
  const codex = buildCodex({ settings: DEFAULT_SETTINGS, wall: {}, presets: [], now: 0 })
  assert.equal(codex.constitution.length, CONSTITUTION.length)
  assert.ok(codex.constitution.some((c) => !c.enforced && c.id === 'C-7'), '哨兵没做，C-7 要标尚未执行')
  assert.equal(codex.counts.unenforced, CONSTITUTION.filter((c) => !c.enforced).length)
  assert.ok(codex.laws.find((l) => l.id === 'L-01').value.includes('09:00–12:00'))
})

test('法典：法律跟着设定变', () => {
  const laws = lawsOf({ ...DEFAULT_SETTINGS, minBouts: 6 }, {})
  assert.match(laws.find((l) => l.id === 'L-03').value, /6 局/)
})

test('法典：法规带立法者、依据、日落剩余天数', () => {
  const now = Date.UTC(2026, 10, 14)
  const codex = buildCodex({
    settings: DEFAULT_SETTINGS, presets: [{ id: 'x', name: 'x', status: '生效', explain: 'e', by: 'ai', basis: 'M-01', version: 2, sunsetAt: now + 5 * 86_400_000 }], now,
  })
  const r = codex.regulations[0]
  assert.equal(r.id, 'R-x')
  assert.equal(r.by, '鲸澜')
  assert.equal(r.basis, 'M-01')
  assert.equal(r.daysLeft, 5)
  assert.equal(codex.counts.sunsetSoon, 1)
})

test('法典：判例按时间倒序', () => {
  const codex = buildCodex({ settings: DEFAULT_SETTINGS, presets: [], now: 0, verdicts: [{ id: 'a', at: 1, result: '过' }, { id: 'b', at: 3, result: '不过' }], appeals: [{ at: 2, result: '申诉驳回' }] })
  assert.deepEqual(codex.precedents.map((p) => p.at), [3, 2, 1])
})

// ---------------------------------------------------------------- 预案：依据与日落

const T0 = Date.UTC(2026, 10, 14, 2)
const aiPreset = (over = {}) => ({ id: 'morning-lock', 名称: '早上先定计划', 状态: '生效', 何时: { 事件: '到点', 时刻: '07:00' }, 动作: [{ 进入计划: {} }], 解除: ['用户确认'], 依据: 'M-01', ...over })

test('预案：依据必须是卡号格式', () => {
  assert.equal(validatePreset(aiPreset({ 依据: '早上那个' })).errors[0].code, 'bad-basis')
  assert.equal(validatePreset(aiPreset()).preset.basis, 'M-01')
})

test('预案日落：鲸澜立的 30 天后自动停用；他立的没有日落', () => {
  let d = proposeChange(emptyDoc(), { raw: aiPreset(), by: 'ai', now: T0 }).doc
  d = proposeChange(d, { raw: aiPreset({ id: 'mine', 名称: '我自己的' }), by: 'user', now: T0 }).doc
  assert.equal(settleSunset(d, T0 + SUNSET_MS - 1).expired.length, 0)
  const r = settleSunset(d, T0 + SUNSET_MS)
  assert.deepEqual(r.expired, ['morning-lock'])
  assert.equal(presetOf(r.doc, 'morning-lock').status, '停用')
  assert.equal(presetOf(r.doc, 'mine').status, '生效')
})

test('预案日落：他续期再给 30 天；鲸澜自己不能续', () => {
  const d = proposeChange(emptyDoc(), { raw: aiPreset(), by: 'ai', now: T0 }).doc
  assert.equal(renew(d, 'morning-lock', T0 + 10, 'ai').code, 'needs-user')
  const r = renew(d, 'morning-lock', T0 + 20 * 86_400_000, 'user')
  assert.equal(r.ok, true)
  assert.equal(settleSunset(r.doc, T0 + SUNSET_MS + 1).expired.length, 0)
})
