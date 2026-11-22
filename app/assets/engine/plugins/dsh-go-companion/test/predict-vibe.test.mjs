// B 层：预判闭环（predict.js）+ 随机器（vibe.js）
//
// 这两块最容易被人情绕过去，所以测的重点是「谁有权写」而不是「字符串长什么样」：
//   1. 没有 evidenceRefs / 翻不成代理指标的预判，落不了盘；
//   2. verdict 只有 settle 能写，模型给什么都进不去；
//   3. 常驻只读层根本不进随机器；组内互斥只抽 1 个；
//   4. Sticky/Cooldown 真的防抖，7 个 arm 都抽得到。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  OPS, VERDICT_SOURCE, appendPrediction, checkClaim, cleanLedger, dueCount, emptyLedger, judge, predictionLines, settle,
} from '../lib/predict.js'
import { ARMS, GROUPS, READONLY_BLOCKS, STICKY_TURNS, buildVibe, pickedArms, renderVibe, seededRng } from '../lib/vibe.js'

const DAY = '2026-11-10'
const NOW = Date.parse('2026-11-10T21:40:00Z')

/** 假文件系统：只认给出的那几个 path（分隔符随平台，统一规范化再比）。 */
function fakeStat(map) {
  const norm = new Map(Object.entries(map).map(([k, v]) => [k.replaceAll('\\', '/'), v]))
  return (p) => {
    const hit = norm.get(String(p).replaceAll('\\', '/'))
    return hit === undefined ? null : { size: hit, mtimeMs: 0 }
  }
}

function claimOf(over = {}) {
  return checkClaim({
    claim: '明天会先折腾 UI 周边，把接口核心逻辑往后推',
    evidenceRefs: ['审计/2026-11-11.md'],
    metrics: [{ metric: 'tasks_ratio', op: '<', threshold: 0.5 }],
    ...over,
  }, { now: NOW, day: DAY, stat: fakeStat({}) })
}

// ---------------------------------------------------------------- 落盘前验真

test('预判：没有 evidenceRefs 就落不了盘', () => {
  const v = claimOf({ evidenceRefs: [] })
  assert.equal(v.ok, false)
  assert.ok(v.problems.some((p) => p.includes('evidenceRefs')))
})

test('预判：翻不成代理指标就落不了盘（这是 §13.3 的重点，不是拆字数）', () => {
  const v = claimOf({ metrics: [] })
  assert.equal(v.ok, false)
  assert.ok(v.problems.some((p) => p.includes('metrics')))
  // 模型自己编一个指标名也不行——只能用墙真在量的那五个。
  const bad = claimOf({ metrics: [{ metric: 'ui_path_bytes_delta', op: '>', threshold: 0.6 }] })
  assert.equal(bad.ok, false)
  assert.match(bad.problems.join(' '), /不认识/)
})

test('预判：op 必须在白名单里，threshold 必须是数', () => {
  assert.equal(claimOf({ metrics: [{ metric: 'fun_minutes', op: '≈', threshold: 30 }] }).ok, false)
  assert.equal(claimOf({ metrics: [{ metric: 'fun_minutes', op: '>', threshold: '很多' }] }).ok, false)
  for (const op of OPS) {
    assert.equal(claimOf({ metrics: [{ metric: 'fun_minutes', op, threshold: 30 }] }).ok, true, op)
  }
})

test('预判：file_growth 立项时记下当时的字节数，之后只量增量', () => {
  const stat = fakeStat({ '/kb/大肥鱼/周报.md': 1000 })
  const v = checkClaim({
    claim: '明天这份周报不会写满三百字节',
    evidenceRefs: ['file:大肥鱼/周报.md'],
    metrics: [{ metric: 'file_growth', op: '<', threshold: 300, path: '大肥鱼/周报.md' }],
  }, { now: NOW, day: DAY, stat, notesDir: '/kb' })
  assert.equal(v.ok, true, JSON.stringify(v.problems))
  // 分隔符随平台（Windows 上是反斜杠），只断言解析到了笔记库下那份文件。
  assert.equal(v.value.metrics[0].path.replaceAll('\\', '/'), '/kb/大肥鱼/周报.md')
  assert.equal(v.value.metrics[0].baseSize, 1000)
})

test('预判：id 按当天递增，verdict 出生就是 null，来源是墙', () => {
  const v = claimOf()
  assert.equal(v.ok, true)
  const a = appendPrediction(emptyLedger(), v.value)
  const b = appendPrediction(a.ledger, v.value)
  assert.equal(a.record.id, 'P-20261110-01')
  assert.equal(b.record.id, 'P-20261110-02')
  assert.equal(b.record.verdict, null)
  assert.equal(b.record.verdictSource, 'WALL_TELEMETRY')
  assert.equal(VERDICT_SOURCE, 'WALL_TELEMETRY')
})

// ---------------------------------------------------------------- 判分

test('判分：全部成立才算说中，任一不成立就是没说中', () => {
  const rec = {
    metrics: [
      { metric: 'app_minutes', op: '>=', threshold: 40, app: '刷题' },
      { metric: 'fun_minutes', op: '<', threshold: 60 },
    ],
  }
  assert.equal(judge(rec, { appMinutes: { 刷题: 45 }, funMinutes: 30 }).verdict, 'hit')
  assert.equal(judge(rec, { appMinutes: { 刷题: 45 }, funMinutes: 90 }).verdict, 'miss')
  assert.equal(judge(rec, { appMinutes: { 刷题: 10 }, funMinutes: 30 }).verdict, 'miss')
})

test('判分：量不到就是 unknown——没有证据不算说中，也不冤枉他', () => {
  const rec = { metrics: [{ metric: 'app_minutes', op: '>=', threshold: 40, app: '刷题' }] }
  const g = judge(rec, { appMinutes: {} })
  assert.equal(g.verdict, 'unknown')
  assert.equal(g.readings[0].value, null)
  // 有别的指标明确不成立时，照样判 miss（不拿 unknown 混过去）。
  const two = {
    metrics: [
      { metric: 'app_minutes', op: '>=', threshold: 40, app: '刷题' },
      { metric: 'fun_minutes', op: '<', threshold: 60 },
    ],
  }
  assert.equal(judge(two, { appMinutes: {}, funMinutes: 120 }).verdict, 'miss')
})

test('判分：App 名字对不齐时模糊认一次，跟墙的规则一致', () => {
  const rec = { metrics: [{ metric: 'app_minutes', op: '>=', threshold: 40, app: '抖音' }] }
  assert.equal(judge(rec, { appMinutes: { 抖音极速版: 55 } }).verdict, 'hit')
})

test('判分：没到期不许判；到期才由 settle 填 verdict', () => {
  const v = claimOf({ dueInHours: 2 })
  const { ledger } = appendPrediction(emptyLedger(), v.value)
  assert.equal(dueCount(ledger, NOW), 0)
  assert.equal(settle(ledger, { tasksRatio: 0 }, NOW).settled.length, 0)

  const later = NOW + 3 * 3_600_000
  assert.equal(dueCount(ledger, later), 1)
  const out = settle(ledger, { tasksRatio: 0.2 }, later)
  assert.equal(out.settled.length, 1)
  assert.equal(out.settled[0].verdict, 'hit')
  assert.equal(out.ledger.list[0].verdictSource, 'WALL_TELEMETRY')
  assert.equal(out.ledger.list[0].settledAt, later)
})

test('判分：settle 不改已经判过的，也不改没到期的', () => {
  const v = claimOf({ dueInHours: 1 }) // 判据是 tasks_ratio < 0.5
  let { ledger } = appendPrediction(emptyLedger(), v.value)
  const first = settle(ledger, { tasksRatio: 0.1 }, NOW + 2 * 3_600_000)
  assert.equal(first.settled[0].verdict, 'hit')
  ledger = first.ledger
  // 再来一次，实测值反过来了，但已经判过的不许改。
  const again = settle(ledger, { tasksRatio: 1 }, NOW + 4 * 3_600_000)
  assert.equal(again.settled.length, 0)
  assert.equal(again.ledger.list[0].verdict, 'hit')
})

test('判分：账本读坏了不炸，退回空账本', () => {
  assert.deepEqual(cleanLedger(null), emptyLedger())
  assert.deepEqual(cleanLedger({ list: 'nope' }), emptyLedger())
})

test('拼给模型的几行：未到期的说清判据，说错的要认账', () => {
  const v = claimOf({ dueInHours: 2 })
  const { ledger } = appendPrediction(emptyLedger(), v.value)
  const lines = predictionLines(ledger, NOW)
  assert.ok(lines.some((l) => l.includes('还没到期')))
  assert.ok(lines.some((l) => l.includes('tasks_ratio')))

  const settled = settle(ledger, { tasksRatio: 1 }, NOW + 3 * 3_600_000).ledger
  const after = predictionLines(settled, NOW + 3 * 3_600_000)
  assert.ok(after.some((l) => l.includes('说错的')))
})

// ---------------------------------------------------------------- 随机器

test('常驻只读层根本不在 ARMS 里——这是类型级只读，不是纪律', () => {
  for (const block of READONLY_BLOCKS) assert.equal(ARMS.includes(block), false, block)
  // 常驻层也不能被塞进任何一组，否则就进了随机器。
  const inGroups = Object.values(GROUPS).flat()
  for (const block of READONLY_BLOCKS) assert.equal(inGroups.includes(block), false, block)
  // 7 个机制一个不少，且每组至少两个（组内互斥才有意义）。
  assert.equal(ARMS.length, 7)
  assert.deepEqual([...ARMS].sort(), Object.values(GROUPS).flat().sort())
  for (const list of Object.values(GROUPS)) assert.ok(list.length >= 2)
})

test('每轮恰好每组抽 1 个，共 2~3 个，不重复', () => {
  for (let turn = 0; turn < 30; turn += 1) {
    const b = buildVibe({ date: DAY, turn, arms: {}, state: {} })
    assert.equal(b.picks.length, Object.keys(GROUPS).length)
    const arms = pickedArms(b.picks)
    assert.equal(new Set(arms).size, arms.length)
    for (const p of b.picks) assert.ok(GROUPS[p.group].includes(p.arm))
  }
})

test('抽样稳定可复现：同种子同账本给同一批 arm', () => {
  const args = { date: DAY, turn: 7, arms: { innerOS: { win: 3, lose: 1 } }, state: {} }
  assert.deepEqual(buildVibe(args).picks, buildVibe(args).picks)
})

test('账本一变，同一天同一轮的抽样就该变（这就是它在学，不是 bug）', () => {
  const a = buildVibe({ date: DAY, turn: 7, arms: {}, state: {} })
  let changed = false
  for (let win = 0; win < 40 && !changed; win += 1) {
    const arms = { innerOS: { win, lose: 0 }, temper: { win: 0, lose: win } }
    const b = buildVibe({ date: DAY, turn: 7, arms, state: {} })
    if (b.snapshot.betaVersion !== a.snapshot.betaVersion) changed = true
    assert.notEqual(b.snapshot.seed === a.snapshot.seed && b.picks.length === 0, true)
  }
  assert.equal(changed, true)
})

test('Sticky 真的防抖：连着两轮抽到同一个 arm，第三轮才换', () => {
  const state = {}
  const seq = []
  let cur = state
  for (let turn = 0; turn < 12; turn += 1) {
    const b = buildVibe({ date: DAY, turn, arms: {}, state: cur })
    cur = b.state
    seq.push(b.picks.map((p) => p.arm).join(','))
  }
  // 至少有一组出现过"同一 arm 连着两轮"——Sticky 生效的证据。
  const mood = seq.map((s) => s.split(',')[0])
  let streak = 1
  let maxStreak = 1
  for (let i = 1; i < mood.length; i += 1) {
    streak = mood[i] === mood[i - 1] ? streak + 1 : 1
    maxStreak = Math.max(maxStreak, streak)
  }
  assert.ok(maxStreak >= 2, '连着两轮都没出现过同一个 arm：Sticky 没生效')
  assert.ok(maxStreak <= STICKY_TURNS, '同一个 arm 连着太久了，Sticky 没到期')
})

test('Cooldown 不会把整组抽干：全组都在歇的时候退回全组', () => {
  // 把每个 arm 都标成刚抽过，Cooldown 生效时 rested 为空。
  const last = Object.fromEntries(ARMS.map((a) => [a, 100]))
  const b = buildVibe({ date: DAY, turn: 101, arms: {}, state: { last, streak: {} } })
  assert.equal(b.picks.length, Object.keys(GROUPS).length)
  for (const p of b.picks) assert.ok(GROUPS[p.group].includes(p.arm))
})

test('每轮落注入快照：种子、抽中的 arm、采样值、账本版本、时间戳', () => {
  const b = buildVibe({ date: DAY, turn: 3, arms: { samples: { win: 2, lose: 5 } }, state: {} })
  const s = b.snapshot
  assert.equal(typeof s.seed, 'number')
  assert.equal(s.turn, 3)
  assert.equal(s.picks.length, b.picks.length)
  assert.equal(typeof s.betaVersion, 'string')
  assert.ok(s.at > 0)
  for (const p of s.picks) {
    assert.equal(typeof p.arm, 'string')
    assert.equal(typeof p.sampled, 'number')
    assert.ok(p.sampled >= 0 && p.sampled <= 1)
  }
})

test('随机源可注入，且 seededRng 输出在 [0,1)', () => {
  const rng = seededRng(12345)
  for (let i = 0; i < 200; i += 1) {
    const v = rng()
    assert.ok(v >= 0 && v < 1, String(v))
  }
})

test('渲染：常驻层没被删，只往上加；未知脸不硬编语气', () => {
  const withLines = renderVibe([{ arm: 'innerOS' }, { arm: 'ironSplit' }], { roll: 0.5 })
  assert.ok(withLines.length >= 2)
  // ironSplit 只是强调一条，原文必须声明"照旧全部算数"。
  assert.ok(withLines.some((l) => l.includes('照旧全部算数')))

  // 脸不在池子里就跳过示例，不编一条语气。
  assert.deepEqual(renderVibe([{ arm: 'samples' }], { face: 'unknown' }), [])
  assert.ok(renderVibe([{ arm: 'samples' }], { face: 'jab' }).length === 1)

  // depth 只搬最短那一行。
  const depth = renderVibe([{ arm: 'depth' }], { stateLines: ['这一条很长很长很长很长很长很长很长很长很长很长很长很长很长', '锁着'] })
  assert.deepEqual(depth, ['时刻记住这一句：锁着'])
})

test('渲染：没有素材的 arm 就交白卷，不硬凑', () => {
  assert.deepEqual(renderVibe([{ arm: 'prediction' }], { predictions: [] }), [])
  assert.deepEqual(renderVibe([{ arm: 'keywordCard' }], { keywords: [] }), [])
  assert.deepEqual(renderVibe([{ arm: 'temper' }], { temperLine: '' }), [])
})
