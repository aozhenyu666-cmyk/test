// P4 升级阶梯的接线：一条回答怎么走完分类 → 记敷衍 → 判升级 → 落手机。
//
// 这里只测接线，不测 escalate.js 自己的边界（那在 escalate.test.mjs）——
// 但「接线把参数传对了没有」恰恰是最容易悄悄坏掉的一环：键名写错、日号没传、
// 窗口没传，全都会静默退化成默认值，然后每一条规则都变成永远不触发。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  ALL_LOCK, CELL, CONFIRM, FUN_LOCK_DAY, FUN_LOCK_PLAN, LOCK_PACKAGE, PLAN_LOCK_MS,
  funStep, landing, rowOf, step,
} from '../lib/ladder.js'
import { DAY_RESTDAY, DAY_WORKDAY, settingsOf } from '../lib/settings.js'

const DAY = Date.parse('2026-03-09T16:00:00Z') // 2026-03-10 00:00 +08，周二，工作日
const at = (h, m = 0) => DAY + (h * 60 + m) * 60_000
const MIN = (h, m = 0) => h * 60 + m
// 必须过 settingsOf：DEFAULT_SETTINGS.windows 是原始数组，windowsOf 要的是 {from,to}。
// 直接拿 DEFAULT_SETTINGS 会让 inWorkWindow 永远 false，L1 静默退化成非工作时段的 30 分钟。
const S = settingsOf({})
const base = { now: 0, day: DAY, settings: S, min: MIN(10) }

/** 一条敷衍的回答：字数够但一个具体名词都没有。 */
const PADDING = '嗯嗯啊啊那个就是这样然后呢'
/** 一条正经回答。 */
const REAL = '我把行测十题做完了，错了三题，错在资料分析的单位换算'

function shove(state, input) {
  return step(state, { ...base, ...input })
}

test('正经回答：不动手机，也不记敷衍', () => {
  const r = shove({ day: DAY }, { text: REAL, now: at(10), passed: true })
  assert.equal(r.kind, 'ok')
  assert.equal(r.verdict, null)
  assert.deepEqual(r.state.perfunctory, [])
})

test('答错（有作答、不符合依据）：只回一句重打，不动手机', () => {
  const r = shove({ day: DAY }, { text: REAL, now: at(10), passed: false })
  assert.equal(r.kind, 'wrong')
  assert.equal(r.verdict, null)
  assert.equal(r.note, '重打本拍，不罚')
  assert.deepEqual(r.state.perfunctory, [])
})

test('混乱（话题 ≥ 3）：只发压缩指令，不动手机', () => {
  const r = shove({ day: DAY }, { text: '我想先学英语。再练跑步。还要买双鞋。', now: at(10) })
  assert.equal(r.kind, 'chaos')
  assert.equal(r.verdict, null)
  assert.deepEqual(r.state.perfunctory, [])
})

test('超时按敷衍算：沉默比敷衍更贵，不能因为没说话就免罚', () => {
  const r = shove({ day: DAY }, { text: '', timedOut: true, now: at(10) })
  assert.equal(r.kind, 'perfunctory')
  assert.equal(r.state.perfunctory.length, 1)
  assert.equal(r.verdict, null, '一次敷衍还不到升级线')
})

test('第 3 次敷衍：E1，审讯舱 10 分钟', () => {
  let st = { day: DAY }
  let v = null
  for (const m of [0, 5, 10]) {
    const r = shove(st, { text: PADDING, now: at(10, m) })
    st = r.state
    v = r.verdict
  }
  assert.ok(v, '第 3 次要升级')
  assert.equal(v.rule, 'E1')
  assert.equal(v.track, 'E')
  assert.equal(v.minutes, 10, 'cellMin')
  assert.deepEqual(v.action, { type: 'cell', minutes: 10, allowGeminiOnly: true })
  assert.equal(v.state.e1Count, 1)
  assert.deepEqual(v.state.perfunctory, [], '触发后窗口清空，不然下一次会连触发')
})

/**
 * 连着敷衍 n 次（每 5 分钟一次，都在 30 分钟窗口里），返回每次的判定。
 * 第 3 / 6 / 9 次分别是当天第 1 / 2 / 3 次触发 E1。
 */
function shoveMany(n) {
  let st = { day: DAY }
  const out = []
  for (let i = 0; i < n; i += 1) {
    const r = shove(st, { text: PADDING, now: at(10, i * 5) })
    st = r.state
    out.push(r.verdict)
  }
  return out
}

test('当天第 2 次触发 E1：E2，20 分钟（cellMin + cellMinStep）', () => {
  const v = shoveMany(6)
  assert.equal(v[2].rule, 'E1')
  assert.equal(v[5].rule, 'E2')
  assert.equal(v[5].minutes, 20)
  assert.equal(v[5].action.type, 'cell')
})

test('当天第 3 次触发：E3，娱乐锁到当天结束 + 次日严格模板', () => {
  const v = shoveMany(9)
  assert.equal(v[8].rule, 'E3')
  assert.deepEqual(v[8].action, { type: 'fun-lock-day', untilDayEnd: true })
  assert.equal(v[8].state.e3, true)
  assert.equal(v[8].state.strictTomorrow, true)
})

test('窗口外的敷衍不算：30 分钟以前的那几次被丢掉', () => {
  // 第 1、2 次在 09:00，第 3 次在 10:00——已经出了 30 分钟窗口。
  let st = { day: DAY }
  st = shove(st, { text: PADDING, now: at(9, 0) }).state
  st = shove(st, { text: PADDING, now: at(9, 10) }).state
  const r = shove(st, { text: PADDING, now: at(10, 0) })
  assert.equal(r.verdict, null, '60 分钟内只有 1 次，不该升级')
  assert.equal(r.state.perfunctory.length, 1, '旧的两条被丢掉，只留下最新这一次')
})

test('换天就清零：昨天触发过 E1，今天从第一次算起', () => {
  let st = { day: DAY }
  for (const m of [0, 5, 10]) st = shove(st, { text: PADDING, now: at(20, m) }).state
  assert.equal(st.e1Count, 1)
  const next = DAY + 86_400_000
  const r = step(st, { ...base, day: next, now: next + 10 * 3_600_000, text: PADDING })
  assert.equal(r.state.day, next)
  assert.equal(r.state.e1Count, 0)
  assert.equal(r.state.perfunctory.length, 1, '记的是今天这一条')
  assert.equal(r.verdict, null)
})

test('countIt=false：只判升级，不新增一次敷衍', () => {
  const st = { day: DAY, perfunctory: [], e1Count: 0, funToday: 0, e3: false, strictTomorrow: false, funRun: null, l2: null }
  const r = step(st, { ...base, text: PADDING, countIt: false })
  assert.equal(r.kind, 'perfunctory')
  assert.equal(r.state.perfunctory.length, 0)
})

// ---------------------------------------------------------------- 动作落手机

const cellVerdict = { rule: 'E1', action: { type: 'cell', minutes: 10, allowGeminiOnly: true }, why: 'w' }
const endOfDay = at(24)

test('cell → 连麦舱，宽限 0（由手机按 session.cell 取），只放行 Gemini', () => {
  const l = landing(cellVerdict, { now: at(10), endOfDay })
  assert.deepEqual(l, { cell: CELL, until: at(10, 10), tighten: false, plan: false, block: '' })
})

test('all-lock → 同一个舱，放行名单为空；到期是当天结束', () => {
  const l = landing({ rule: 'E1′', action: { type: 'all-lock', allowBout: true } }, { now: at(10), endOfDay })
  assert.equal(l.cell, ALL_LOCK)
  assert.equal(l.until, endOfDay)
  assert.equal(l.tighten, false)
})

test('all-lock 在临近换天时至少给一分钟，不会算出一个已经过期的到期时间', () => {
  const l = landing({ rule: 'E1′', action: { type: 'all-lock' } }, { now: endOfDay - 1_000, endOfDay })
  assert.equal(l.until, endOfDay + 59_000)
})

test('fun-lock-day → 收紧墙，今天娱乐锁到零点', () => {
  const l = landing({ rule: 'E3', action: { type: 'fun-lock-day', untilDayEnd: true } }, { now: at(10), endOfDay })
  assert.equal(l.tighten, true)
  assert.equal(l.cell, '')
})

test('fun-lock-plan → 进计划模态，不用再收紧墙', () => {
  const l = landing({ rule: 'L1', action: { type: 'fun-lock-plan', untilDayEnd: false } }, { now: at(10), endOfDay })
  assert.equal(l.plan, true)
  assert.equal(l.tighten, false)
  assert.equal(l.until, endOfDay)
  assert.ok(PLAN_LOCK_MS > 0)
})

test('lock-package → 只锁那一个包', () => {
  const l = landing({ rule: 'L2', action: { type: LOCK_PACKAGE, pkg: 'com.douyin' } }, { now: at(10), endOfDay })
  assert.equal(l.block, 'com.douyin')
  assert.equal(l.cell, '')
})

test('confirm → 问一句，不锁', () => {
  const l = landing({ rule: 'L2', action: { type: CONFIRM, pkg: 'com.douyin', minutes: 2 } }, { now: at(10), endOfDay })
  assert.equal(l.ask, 'com.douyin')
  assert.equal(l.minutes, 2)
  assert.equal(l.block, '')
})

test('没有动作就没有落地：不编一个默认锁出来', () => {
  assert.equal(landing(null), null)
  assert.equal(landing({ rule: '', action: null }), null)
  assert.equal(landing({ rule: 'X', action: { type: '不认识的形状' } }), null)
})

test('升级表那一行：level 记规则名，reason 记为什么', () => {
  assert.deepEqual(rowOf(cellVerdict), { level: 'E1', reason: 'w' })
  assert.deepEqual(rowOf({ rule: '', why: '' }), { level: 'L1', reason: '' })
})

// ---------------------------------------------------------------- 沉迷（/plan 节拍）

const funBase = { settings: S, min: MIN(10), day: DAY, dayKind: DAY_WORKDAY }

test('L1：工作时段娱乐连续 15 分钟 → 锁 + 进计划模态', () => {
  let st = { day: DAY }
  let hit = null
  for (let i = 0; i <= 14; i += 1) {
    const r = funStep(st, { ...funBase, now: at(10, i), foreground: 'com.douyin', funPackage: true, funDeltaMs: 60_000 })
    st = r.state
    if (r.verdict) hit = r.verdict
  }
  assert.equal(hit, null, '14 分钟还不锁')
  const r = funStep(st, { ...funBase, now: at(10, 15), foreground: 'com.douyin', funPackage: true, funDeltaMs: 60_000 })
  assert.ok(r.verdict, '第 15 分钟必须锁')
  assert.equal(r.verdict.rule, 'L1')
  assert.equal(r.verdict.track, 'L')
  assert.equal(r.verdict.action.type, 'fun-lock-plan')
  assert.equal(landing(r.verdict, { now: at(10, 15), endOfDay }).plan, true)
})

test('L1：非工作时段要连用 30 分钟', () => {
  const restBase = { ...funBase, dayKind: DAY_RESTDAY, min: MIN(20) }
  let st = { day: DAY }
  let hit = null
  for (let i = 0; i <= 20; i += 1) {
    const r = funStep(st, { ...restBase, now: at(20, i), foreground: 'com.douyin', funPackage: true, funDeltaMs: 60_000 })
    st = r.state
    if (r.verdict) hit = r.verdict
  }
  assert.equal(hit, null, '20 分钟还不够非工作时段的 30 分钟线')
})

test('L1：中途回白名单 App 就重新计时', () => {
  let st = { day: DAY }
  for (let i = 0; i <= 10; i += 1) {
    st = funStep(st, { ...funBase, now: at(10, i), foreground: 'com.douyin', funPackage: true }).state
  }
  // 回工作 App 一拍
  st = funStep(st, { ...funBase, now: at(10, 11), foreground: 'com.notion', funPackage: false }).state
  assert.equal(st.funRun, null)
  const r = funStep(st, { ...funBase, now: at(10, 12), foreground: 'com.douyin', funPackage: true })
  assert.equal(r.verdict, null)
  assert.equal(r.state.funRun.since, at(10, 12), '从这一刻重新起算')
})

test('L3：当天娱乐累计到 60 分钟 → 娱乐锁到当天结束', () => {
  let st = { day: DAY }
  let v = null
  // 每分钟一拍，连刷 70 分钟：中间 L1 会先响几次（15/30/45 分钟），累计到 60 分钟时出 L3。
  for (let i = 0; i <= 70; i += 1) {
    const r = funStep(st, { ...funBase, now: at(9, i), foreground: 'com.douyin', funPackage: true, funDeltaMs: 60_000 })
    st = r.state
    if (r.verdict && r.verdict.rule === 'L3') { v = r.verdict; break }
  }
  assert.ok(v, '累计过 60 分钟要出 L3')
  assert.deepEqual(v.action, { type: 'fun-lock-day', untilDayEnd: true })
  assert.equal(landing(v, { now: at(10), endOfDay }).tighten, true)
})

test('L2：工作时段用了没声明的应用 20 分钟 → 先问一句', () => {
  let st = { day: DAY }
  let v = null
  for (let i = 0; i <= 21; i += 1) {
    const r = funStep(st, { ...funBase, now: at(14, i), foreground: 'com.douyin', funPackage: false, declared: ['com.notion'] })
    st = r.state
    if (r.verdict) { v = r.verdict; break }
  }
  assert.ok(v, '20 分钟要问')
  assert.equal(v.rule, 'L2')
  assert.equal(v.action.type, 'confirm')
  assert.equal(v.action.pkg, 'com.douyin')
})

test('L2：问了 2 分钟没答 → 锁那个包', () => {
  let st = { day: DAY }
  let v = null
  for (let i = 0; i <= 23; i += 1) {
    const r = funStep(st, { ...funBase, now: at(14, i), foreground: 'com.douyin', funPackage: false, declared: ['com.notion'] })
    st = r.state
    if (r.verdict && r.verdict.action.type === LOCK_PACKAGE) { v = r.verdict; break }
  }
  assert.ok(v, '问了 2 分钟不答就该锁')
  assert.equal(v.rule, 'L2')
  assert.equal(v.action.pkg, 'com.douyin')
})

test('L2：他回话了，核对结束、重新计时', () => {
  let st = { day: DAY }
  for (let i = 0; i <= 21; i += 1) {
    st = funStep(st, { ...funBase, now: at(14, i), foreground: 'com.douyin', funPackage: false, declared: [] }).state
  }
  assert.ok(st.l2, '应该已经问过')
  const r = funStep(st, { ...funBase, now: at(14, 22), foreground: 'com.douyin', funPackage: false, declared: [], answeredAt: at(14, 22) })
  assert.equal(r.verdict, null)
  assert.equal(r.state.l2, null)
})

test('L2：局里声明的应用不查', () => {
  let st = { day: DAY }
  let hit = null
  for (let i = 0; i <= 25; i += 1) {
    const r = funStep(st, { ...funBase, now: at(14, i), foreground: 'com.notion', funPackage: false, declared: ['com.notion'] })
    st = r.state
    if (r.verdict) hit = r.verdict
  }
  assert.equal(hit, null)
})

test('沉迷串在非工作时段不查 L2', () => {
  let st = { day: DAY }
  let hit = null
  for (let i = 0; i <= 25; i += 1) {
    const r = funStep(st, { ...funBase, min: MIN(20), now: at(20, i), foreground: 'com.douyin', funPackage: false, declared: [] })
    st = r.state
    if (r.verdict) hit = r.verdict
  }
  assert.equal(hit, null)
})
