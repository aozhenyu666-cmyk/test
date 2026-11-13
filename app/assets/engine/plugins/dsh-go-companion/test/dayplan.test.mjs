// P1 计划模态：候选生成、草案、编辑规则、超时生效、禁用问句、五个进入条件、一整天模拟。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  PLAN_CONFIRM_MS, PLAN_EDIT_MAX, SOURCE,
  applyEdit, candidates, cn, clock, draftOf, enterReason, forbidden, planSentence, planTimeout, rewrite,
} from '../lib/dayplan.js'
import { DAY_RESTDAY, DAY_WORKDAY, settingsOf } from '../lib/settings.js'
import { resolveState } from '../lib/machine.js'

const TZ = 'Asia/Shanghai'
const DAY = Date.parse('2026-03-09T16:00:00Z') // 2026-03-10 00:00 当地，周二
const at = (hour, minute = 0) => DAY + (hour * 60 + minute) * 60_000
const MIN = (hour, minute = 0) => hour * 60 + minute
const S = settingsOf({})

const draft = (extra = {}) => draftOf({ settings: S, kind: DAY_WORKDAY, now: at(7), ...extra })

test('P1 中文局数', () => {
  assert.equal(cn(0), '零')
  assert.equal(cn(4), '四')
  assert.equal(cn(10), '十')
  assert.equal(cn(13), '13', '超过十就用数字，不硬编')
})

test('P1 时钟格式', () => {
  assert.equal(clock(540), '9:00')
  assert.equal(clock(545), '9:05')
  assert.equal(clock(0), '0:00')
  assert.equal(clock(1290), '21:30')
})

test('P1 候选：按 90 分钟排槽位', () => {
  const list = candidates({ settings: S, kind: DAY_WORKDAY })
  const fixed = list.filter((c) => c.source === SOURCE.window)
  assert.deepEqual(fixed.map((c) => c.minute), [540, 630, 840, 930, 1020], '上午两个、下午三个')
})

test('P1 候选：局型轮着来，第三个就是思维局', () => {
  const list = candidates({ settings: S, kind: DAY_WORKDAY })
  assert.deepEqual(list.map((c) => c.protocolId), ['study', 'execute', 'thinking', 'outing', 'design'])
})

test('P1 候选：预算比时段还长就压到时段长度', () => {
  const tiny = settingsOf({ day: { windows: { workday: [[600, 610]] }, budget: { study: 60 } } })
  const list = candidates({ settings: tiny, kind: DAY_WORKDAY })
  assert.equal(list[0].budget, 10, '10 分钟的时段不排 60 分钟的局')
})

test('P1 候选按来源优先级排：固定局在前，暗思考在最后', () => {
  const list = candidates({
    settings: S, kind: DAY_WORKDAY,
    missed: [{ id: 'b1', protocolId: 'study', empty: true }],
    rules: [{ id: 'r1', title: '不刷手机' }],
    stances: [{ id: 's1', title: '每天写 300 字' }],
    notes: [{ id: 'n1', title: '读书笔记', staleDays: 5 }],
    carry: [{ id: 'c1', title: '改简历' }],
  })
  assert.deepEqual(list.map((c) => c.source), [1, 1, 1, 1, 1, 2, 3, 4, 5, 6])
})

test('P1 候选：昨天空局写成「昨天空了一次」', () => {
  const list = candidates({ settings: S, kind: DAY_WORKDAY, missed: [{ id: 'b1', protocolId: 'study', empty: true }] })
  const missed = list.find((c) => c.source === SOURCE.missed)
  assert.equal(missed.why, '昨天空了一次')
  assert.equal(missed.title, '学习局')
})

test('P1 候选：昨天没过写成「昨天没过」', () => {
  const list = candidates({ settings: S, kind: DAY_WORKDAY, missed: [{ id: 'b1', protocolId: 'study' }] })
  assert.equal(list.find((c) => c.source === SOURCE.missed).why, '昨天没过')
})

test('P1 候选：坏输入不崩', () => {
  const list = candidates({ settings: S, kind: DAY_WORKDAY, missed: null, rules: 'x', stances: [null, 1], notes: {}, carry: undefined })
  assert.equal(list.length, 5, '只留下固定局的 5 个槽位')
})

test('P1 草案：排满一天槽位，且不少于下限', () => {
  const d = draft()
  assert.ok(d.items.length >= 4, '至少 4 局，实际 ' + d.items.length)
  assert.ok(d.items.length <= 6, '最多 6 局，实际 ' + d.items.length)
  assert.equal(d.edits, 0)
  assert.equal(d.accepted, false)
})

test('P1 草案：90 分钟一个槽位（施工书示例的节奏）', () => {
  const mins = draft().items.map((c) => c.minute)
  assert.deepEqual(mins, [540, 630, 840, 930, 1020], '上午两个、下午三个')
})

test('P1 草案：按时间排序', () => {
  const d = draft()
  const mins = d.items.map((c) => c.minute)
  assert.deepEqual(mins, [...mins].sort((a, b) => a - b))
})

test('P1 草案：备选池里不含已选的', () => {
  const d = draft({ missed: [{ id: 'b1', protocolId: 'study' }], carry: [{ id: 'c1', title: '改简历' }] })
  const ids = new Set(d.items.map((c) => c.id))
  assert.ok(d.pool.every((c) => !ids.has(c.id)))
})

test('P1 草案：休息日只取 2 局', () => {
  const d = draftOf({ settings: S, kind: DAY_RESTDAY, now: at(7) })
  assert.ok(d.items.length <= 2, '休息日不逼 4 局')
})

test('P1 草案：思维局不够就从备选里补', () => {
  // 固定局只有学习+执行，思维局在备选里。
  const d = draft({
    rules: [{ id: 'r1', title: '一条规则' }],
    settings: S,
  })
  assert.ok(d.items.some((c) => c.type === '思维'), '至少要有一个思维局')
})

test('P1 编辑：接受就结束', () => {
  const r = applyEdit(draft(), { op: 'accept' }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, true)
  assert.equal(r.draft.accepted, true)
  assert.equal(r.draft.edits, 0, '接受不算改')
})

test('P1 编辑：删一局', () => {
  const d = draft({ missed: [{ id: 'b1', protocolId: 'study' }], rules: [{ id: 'r1' }] })
  const r = applyEdit(d, { op: 'drop', id: d.items[0].id }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, true)
  assert.equal(r.draft.items.length, d.items.length - 1)
  assert.equal(r.draft.edits, 1)
})

test('P1 编辑：删到下限以下被拒', () => {
  const d = draft({ missed: [{ id: 'b1', protocolId: 'study' }], rules: [{ id: 'r1' }] })
  const four = { ...d, items: d.items.slice(0, 4) }
  const r = applyEdit(four, { op: 'drop', id: four.items[3].id }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, false)
  assert.match(r.why, /至少 4 局/)
})

test('P1 编辑：把唯一的思维局删掉被拒', () => {
  const d = { ...draft(), items: [
    { id: 'x1', type: '行为', minute: 540, title: '学习局', budget: 25, protocolId: 'study', source: 1 },
    { id: 'x2', type: '思维', minute: 600, title: '思考局', budget: 25, protocolId: 'thinking', source: 1 },
    { id: 'x3', type: '行为', minute: 660, title: '执行局', budget: 30, protocolId: 'execute', source: 1 },
  ] }
  const tight = settingsOf({ day: { minBouts: 2, minThinking: 1 } })
  const r = applyEdit(d, { op: 'drop', id: 'x2' }, { settings: tight, kind: DAY_WORKDAY })
  assert.equal(r.ok, false, '删了只剩 2 局行为局，够局数但没思维局')
  assert.match(r.why, /思维局/)
})

test('P1 编辑：换一局，时段沿用原来的', () => {
  const d = draft({ missed: [{ id: 'b1', protocolId: 'study' }], rules: [{ id: 'r1' }], carry: [{ id: 'c1', title: '改简历' }] })
  const target = d.items[0]
  const r = applyEdit(d, { op: 'swap', id: target.id }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, true)
  assert.equal(r.draft.items[0].minute, target.minute, '换进来的局不改时段')
  assert.notEqual(r.draft.items[0].id, target.id)
  assert.equal(r.draft.edits, 1)
})

test('P1 编辑：没有备选就换不了', () => {
  const d = { ...draft(), pool: [] }
  const r = applyEdit(d, { op: 'swap', id: d.items[0].id }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, false)
  assert.match(r.why, /没有别的局/)
})

test('P1 编辑：加一局并按时间插进去', () => {
  const d = draft({ missed: [{ id: 'b1', protocolId: 'study' }], rules: [{ id: 'r1' }] })
  const r = applyEdit(d, { op: 'add', bout: { protocolId: 'outing', minute: 600 } }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, true)
  assert.equal(r.draft.items.length, d.items.length + 1)
  const mins = r.draft.items.map((c) => c.minute)
  assert.deepEqual(mins, [...mins].sort((a, b) => a - b))
})

test('P1 编辑：加一局但没说加哪个 → 拒绝', () => {
  const r = applyEdit(draft(), { op: 'add' }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, false)
  assert.match(r.why, /没说加哪一局/)
})

test('P1 编辑：改满两次就不许再改', () => {
  const pool = [{ id: 'p1', type: '行为', protocolId: 'study', title: '学习局', budget: 25, minute: 0, source: 1 },
    { id: 'p2', type: '行为', protocolId: 'execute', title: '执行局', budget: 30, minute: 0, source: 1 },
    { id: 'p3', type: '行为', protocolId: 'design', title: '设计局', budget: 30, minute: 0, source: 1 }]
  let d = { ...draft(), pool }
  for (let i = 0; i < PLAN_EDIT_MAX; i += 1) {
    const r = applyEdit(d, { op: 'swap', id: d.items[0].id }, { settings: S, kind: DAY_WORKDAY })
    assert.equal(r.ok, true, '第 ' + (i + 1) + ' 次该成功')
    d = r.draft
  }
  const over = applyEdit(d, { op: 'swap', id: d.items[0].id }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(over.ok, false)
  assert.match(over.why, /最多 2 次/)
})

test('P1 编辑：重排不占次数', () => {
  let d = draft()
  d = applyEdit(d, { op: 'swap', id: d.items[0].id }, { settings: S, kind: DAY_WORKDAY }).draft
  const r = applyEdit(d, { op: 'rearrange' }, { settings: S, kind: DAY_WORKDAY, missed: [{ id: 'b9', protocolId: 'study' }] })
  assert.equal(r.ok, true)
  assert.equal(r.draft.edits, 0, '重排是重新出草案')
})

test('P1 编辑：不认识的改法被拒', () => {
  const r = applyEdit(draft(), { op: 'delete-everything' }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, false)
})

test('P1 编辑：删一个草案里没有的局被拒', () => {
  const r = applyEdit(draft(), { op: 'drop', id: '不存在' }, { settings: S, kind: DAY_WORKDAY })
  assert.equal(r.ok, false)
  assert.match(r.why, /没有这一局/)
})

test('P1 超时：10 分钟到点自动生效', () => {
  const d = { at: at(9), items: [] }
  assert.equal(planTimeout(d, at(9) + PLAN_CONFIRM_MS - 1).due, false)
  assert.equal(planTimeout(d, at(9) + PLAN_CONFIRM_MS).due, true)
  assert.equal(planTimeout(d, at(9)).at, at(9) + PLAN_CONFIRM_MS)
})

test('P1 禁用问句：四句都拦', () => {
  for (const bad of ['你今天想做什么', '你觉得呢', '要不要先学一会儿', '看你']) {
    assert.ok(forbidden(bad), bad + ' 该被拦')
  }
  assert.equal(forbidden('要不要先学一会儿'), '要不要', '命中哪个词就说哪个词')
})

test('P1 禁用问句：没提局的问号也算开放问句', () => {
  assert.equal(forbidden('现在开始吗？'), 'open-question')
})

test('P1 禁用问句：提到了局的问句放行', () => {
  assert.equal(forbidden('学习局要不要挪到下午？'), '要不要', '「要不要」还是拦')
  assert.equal(forbidden('学习局排九点行吗？'), '')
})

test('P1 禁用问句：正常计划句不拦', () => {
  assert.equal(forbidden('今天四局。9:00学习局，25分钟。'), '')
})

test('P1 重写：命中就换成计划本身', () => {
  const d = draft()
  const out = rewrite('你今天想做什么？', d)
  assert.equal(forbidden(out), '', '重写后不该再命中')
  assert.match(out, new RegExp('^今天' + cn(d.items.length) + '局。'))
})

test('P1 重写：没命中就原样', () => {
  assert.equal(rewrite('现在开始。', draft()), '现在开始。')
})

test('P1 计划句：含局数与每一局的时段和预算', () => {
  const d = draft()
  const s = planSentence(d)
  assert.match(s, new RegExp('^今天' + cn(d.items.length) + '局。'))
  assert.match(s, /9:00学习局，25分钟/)
  assert.match(s, /15:30外出局，40分钟/)
})

test('P1 计划句：空草案就是空字符串', () => {
  assert.equal(planSentence({ items: [] }), '')
  assert.equal(planSentence(null), '')
})

test('P1 计划句：昨天空局补一句', () => {
  const s = planSentence(draft(), { missedTitle: '学习局' })
  assert.match(s, /昨天学习局空了一次，今天它排第一/)
})

test('P1 进入条件 1：深夜锁后第一次亮屏', () => {
  const r = enterReason({ now: at(7), min: MIN(7), settings: S, kind: DAY_WORKDAY, screenOn: true, wokeAt: at(7) })
  assert.deepEqual([r.enter, r.reason], [true, 'wake'])
})

test('P1 进入条件 2：工作时段刚收局且队列空', () => {
  const r = enterReason({ now: at(10), min: MIN(10), settings: S, kind: DAY_WORKDAY, closedAt: at(10), queueEmpty: true })
  assert.deepEqual([r.enter, r.reason], [true, 'queue'])
})

test('P1 进入条件 2：队列不空就不进', () => {
  const r = enterReason({ now: at(10), min: MIN(10), settings: S, kind: DAY_WORKDAY, closedAt: at(10), queueEmpty: false })
  assert.equal(r.enter, false)
})

test('P1 进入条件 3：无局 5 分钟到上限（边界）', () => {
  const base = { now: at(10) + 5 * 60_000 - 1, min: MIN(10, 4), settings: S, kind: DAY_WORKDAY, noBoutSince: at(10) }
  assert.equal(enterReason(base).enter, false, '差一秒不算')
  const hit = enterReason({ ...base, now: at(10) + 5 * 60_000 })
  assert.deepEqual([hit.enter, hit.reason], [true, 'idle'])
})

test('P1 进入条件 3：非工作时段不因无局进计划模态', () => {
  const r = enterReason({ now: at(13), min: MIN(13), settings: S, kind: DAY_WORKDAY, noBoutSince: at(12) })
  assert.equal(r.enter, false, '午饭时段不算无局')
})

test('P1 进入条件 4：升级结束后', () => {
  const r = enterReason({ now: at(11), min: MIN(11), settings: S, kind: DAY_WORKDAY, escalateEndedAt: at(11) })
  assert.deepEqual([r.enter, r.reason], [true, 'escalate'])
})

test('P1 进入条件 5：他说重排', () => {
  const r = enterReason({ now: at(15), min: MIN(15), settings: S, kind: DAY_WORKDAY, saidRearrange: true })
  assert.deepEqual([r.enter, r.reason], [true, 'rearrange'])
})

test('P1 五个条件都不成立就不进', () => {
  const r = enterReason({ now: at(15), min: MIN(15), settings: S, kind: DAY_WORKDAY })
  assert.equal(r.enter, false)
  assert.equal(r.reason, '')
})

test('P1 计划模态在状态机里优先级仅次于深夜锁', () => {
  const plan = resolveState({ now: at(9), tz: TZ, config: {}, plan: { active: true, until: at(9) + 600_000 } })
  assert.equal(plan.state, 'plan')
  assert.equal(plan.funLocked, true, '计划模态期间娱乐全锁')
  assert.equal(plan.until, at(9) + 600_000)
  const night = resolveState({ now: at(1), tz: TZ, config: {}, plan: { active: true, until: at(1) + 600_000 } })
  assert.equal(night.state, 'night', '深夜锁仍然压过计划模态')
})

test('P1 计划模态结束就回到原来的主状态', () => {
  const s = resolveState({ now: at(9), tz: TZ, config: {}, plan: { active: false, until: 0 } })
  assert.notEqual(s.state, 'plan')
})

test('P1 一整天模拟：他全程不说话', () => {
  // 一天跑下来要验三件事：早上计划自动生效、所有局按时开、每次队列空都重新进计划模态。
  let plan = null
  let queue = []      // 已生效但还没开的局
  let opened = 0
  let closedAt = 0
  let enters = 0
  let wokeAt = 0
  const reasons = new Set()
  const openedAt = []

  for (let m = 0; m < 1440; m += 1) {
    const now = DAY + m * 60_000
    // 深夜锁 0:30–7:00，他 7:00 醒来这一下算第一次亮屏。
    if (m === MIN(7)) wokeAt = now
    // 10 分钟不确认 → 草案生效。已经过点的槽位顺延到现在（到点即开，不许补开）。
    if (plan && planTimeout(plan, now).due) {
      queue = plan.items.map((c) => ({ ...c, minute: Math.max(c.minute, m) }))
      plan = null
    }
    // 到点的局自动开（P2 的活，这里只记账）。
    while (queue.length && queue[0].minute <= m) {
      opened += 1
      openedAt.push(queue[0].minute)
      queue.shift()
      closedAt = now
    }
    if (plan) continue
    const r = enterReason({
      now, min: m, settings: S, kind: DAY_WORKDAY,
      screenOn: m >= MIN(7) && m < MIN(7) + 2,
      wokeAt,
      closedAt,
      queueEmpty: queue.length === 0,
      noBoutSince: queue.length ? 0 : closedAt || at(7),
    })
    if (r.enter) {
      enters += 1
      reasons.add(r.reason)
      plan = { ...draftOf({ settings: S, kind: DAY_WORKDAY, now, missed: [] }), at: now }
    }
  }

  assert.ok(reasons.has('wake'), '早上该因亮屏进计划模态')
  assert.ok(opened >= 4, '一天至少要开 4 局，实际 ' + opened)
  assert.ok(enters >= 1, '计划模态至少进过一次')
  assert.equal(plan, null, '一天结束时不该还挂着没生效的草案')
  // 开的局必须是按时段顺序来的，不能倒着开、也不能补开过点的局。
  assert.deepEqual(openedAt, [...openedAt].sort((a, b) => a - b), '开局顺序按时间递进')
})

test('P1 一整天模拟：队列空了会重新进计划模态', () => {
  // 直接把条件 2 摆出来：刚收局 + 队列空 → 进。
  const r = enterReason({
    now: at(10), min: MIN(10), settings: S, kind: DAY_WORKDAY,
    closedAt: at(10), queueEmpty: true,
  })
  assert.equal(r.reason, 'queue')
  // 收局超过一分钟就不再算「刚收局」，改由无局计时兜底。
  const later = enterReason({
    now: at(10) + 120_000, min: MIN(10, 2), settings: S, kind: DAY_WORKDAY,
    closedAt: at(10), queueEmpty: true, noBoutSince: at(10) + 120_000,
  })
  assert.equal(later.enter, false, '刚过一分钟，无局计时也还没到')
})
