// 每日文件 + 按完成物对账（第二轮）。
//
// 对账不看说法只看证据：知识库文件、Gemini 复盘账本、App 使用时长。
// 时间全部注入，不依赖真实时钟。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, utimesSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  blockLine, checkEvidence, clockMin, dailyPath, dayString, fixedBlocksOf, hhmm, keywordsOf,
  lateLine, loadDaily, markBlockDone, normalizeTasks, planSweep, reconcile, renderDaily,
  reschedule, reviewText, schedule, scheduleText, writeDaily,
  MAX_RESCHEDULE_PER_DAY, SCHEDULE_FROM, SCHEDULE_TO,
} from '../lib/daily.js'

const TZ = 'Asia/Shanghai'
// 2026-11-08 20:00 +08:00
const NOW = Date.UTC(2026, 10, 8, 12, 0, 0)

function withDir(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'companion-daily-'))
  try {
    return fn(dir)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

test('按手机时区算日期、拼每日文件路径', () => {
  assert.equal(dayString(NOW, TZ), '2026-11-08')
  // 同一时刻：UTC 还是 7 号 17:00，+08:00 已经是 8 号凌晨——不能串天。
  const edge = Date.UTC(2026, 10, 7, 17, 0, 0)
  assert.equal(dayString(edge, 'UTC'), '2026-11-07')
  assert.equal(dayString(edge, TZ), '2026-11-08')
  const p = dailyPath('/kb', NOW, TZ)
  assert.equal(p.replace(/\\/g, '/'), '/kb/每日/2026-11-08.md')
})

test('早上定事：完成物、截止时刻、预估分钟、归哪个部，缺一样都不收', () => {
  assert.equal(normalizeTasks([]).ok, false)
  assert.equal(normalizeTasks([{ task: '写申论' }]).ok, false, '没完成物不收')
  // A2 起这三样也必填：没有它们排不出时间表，晚上也无从对照这块到底用了多久。
  const onlyEvidence = normalizeTasks([{ task: '写申论', evidence: '大肥鱼/判断.md 里写一段' }])
  assert.equal(onlyEvidence.ok, false)
  assert.match(onlyEvidence.text, /截止时刻/)
  assert.match(onlyEvidence.text, /预估分钟数/)
  assert.match(onlyEvidence.text, /归哪个部/)
  const ok = normalizeTasks([{ task: '写申论', evidence: '大肥鱼/判断.md 里写一段', due: '18:00', minutes: 60, dept: '谋划思考' }])
  assert.equal(ok.ok, true)
  assert.equal(ok.tasks.length, 1)
  assert.equal(ok.tasks[0].id, 't1', '每件事一个稳定 id，重排时按 id 认块')
  assert.equal(ok.tasks[0].due, 18 * 60, 'HH:MM 换成当天第几分钟')
  // 空的 task 整条丢掉，不占额
  assert.equal(normalizeTasks([{ task: '', evidence: 'x' }, { task: 'A', evidence: 'B', due: '9:00', minutes: 30, dept: '信息收集' }]).tasks.length, 1)
  // 没有这个部就是不收（部是写死的四个，不能自己发明）
  assert.equal(normalizeTasks([{ task: 'A', evidence: 'B', due: '9:00', minutes: 30, dept: '打杂' }]).ok, false)
})

test('关键词：去掉标点，最多 4 个', () => {
  assert.deepEqual(keywordsOf('线代第三章，做完课后题'), ['线代第三章', '做完课后题'])
  assert.ok(keywordsOf('一 二 三 四 五 六').length <= 4)
  assert.deepEqual(keywordsOf(''), [])
})

test('证据一：完成物写了文件路径，文件今天改过就算做完', () => {
  withDir((dir) => {
    const f = join(dir, '判断.md')
    writeFileSync(f, '# 判断\n')
    const r = checkEvidence({ task: '写判断', evidence: f }, { now: NOW, tz: TZ, notes: [] })
    assert.equal(r.verdict, 'done', r.why)
  })
})

test('证据一：今天没动过的文件不算证据', () => {
  withDir((dir) => {
    const f = join(dir, '旧.md')
    writeFileSync(f, '# 旧\n')
    // mtime 得钉死：它来自真实时钟，跑测试跨过零点这条断言就会飘。
    utimesSync(f, new Date(NOW - 86_400_000), new Date(NOW - 86_400_000))
    // 一天以后再对账：文件不是今天改的
    const r = checkEvidence({ task: '写笔记', evidence: f }, { now: NOW + 86_400_000, tz: TZ, notes: [] })
    assert.equal(r.verdict, 'miss', r.why)
  })
})

test('证据二：今天动过的笔记里有完成物的关键词', () => {
  const notes = [{ path: '/kb/大肥鱼/判断.md', rel: '大肥鱼/判断.md', text: '今天把线代第三章的课后题做完了，错了两道' }]
  const r = checkEvidence({ task: '线代', evidence: '线代第三章 课后题' }, { now: NOW, tz: TZ, notes })
  assert.equal(r.verdict, 'done', r.why)
  // 只命中一半：记一半
  const half = checkEvidence({ task: 'x', evidence: '线代第三章 面试复盘' }, { now: NOW, tz: TZ, notes })
  assert.equal(half.verdict, 'partial', half.why)
})

test('证据三：今天有一条 Gemini 复盘对得上', () => {
  const ledger = [{ state: 'gemini-recap', at: NOW - 60_000, topic: '线代第三章', summary: '讲完了矩阵' }]
  const r = checkEvidence({ task: '连麦', evidence: '线代第三章' }, { now: NOW, tz: TZ, ledger, notes: [] })
  assert.equal(r.verdict, 'done', r.why)
  // 昨天的复盘不能给今天作证
  const old = checkEvidence({ task: '连麦', evidence: '线代第三章' }, {
    now: NOW, tz: TZ, notes: [], ledger: [{ state: 'gemini-recap', at: NOW - 86_400_000, topic: '线代第三章' }],
  })
  assert.equal(old.verdict, 'miss', old.why)
})

test('证据四：App 使用时长要够完成物里写的分钟数', () => {
  const usage = [{ label: 'Anki', minutes: 40 }]
  const done = checkEvidence({ task: '背单词', evidence: 'Anki 用够 30 分钟' }, { now: NOW, tz: TZ, usage, notes: [] })
  assert.equal(done.verdict, 'done', done.why)
  const part = checkEvidence({ task: '背单词', evidence: 'Anki 用够 60 分钟' }, { now: NOW, tz: TZ, usage, notes: [] })
  assert.equal(part.verdict, 'partial', part.why)
})

test('什么都没有就是没做完', () => {
  const r = checkEvidence({ task: '写申论', evidence: '申论大作文一篇' }, { now: NOW, tz: TZ, notes: [], ledger: [], usage: [] })
  assert.equal(r.verdict, 'miss')
})

test('对账的额度后果只作用于明天：全做完 +1、全没做 -1、一半 0', () => {
  const notes = [{ path: '/kb/a.md', rel: 'a.md', text: '线代第三章 课后题 全做完了' }]
  const all = reconcile(
    { day: '2026-11-08', tasks: [{ task: '线代', evidence: '线代第三章 课后题' }] },
    { now: NOW, tz: TZ, notes },
  )
  assert.equal(all.done, 1)
  assert.equal(all.quotaDelta, 1)

  const none = reconcile(
    { day: '2026-11-08', tasks: [{ task: '写申论', evidence: '申论大作文' }] },
    { now: NOW, tz: TZ, notes: [] },
  )
  assert.equal(none.miss, 1)
  assert.equal(none.quotaDelta, -1)

  const mixed = reconcile(
    { day: '2026-11-08', tasks: [
      { task: '线代', evidence: '线代第三章 课后题' },
      { task: '写申论', evidence: '申论大作文' },
    ] },
    { now: NOW, tz: TZ, notes },
  )
  assert.equal(mixed.done, 1)
  assert.equal(mixed.miss, 1)
  assert.equal(mixed.quotaDelta, 0)
})

test('早上没定事就没有可对的账', () => {
  const out = reconcile({ day: '2026-11-08', tasks: [] }, { now: NOW, tz: TZ, notes: [] })
  assert.equal(out.total, 0)
  assert.equal(out.quotaDelta, 0)
  assert.match(reviewText(out), /没定事/)
})

test('机器状态按天清：昨天的 daily.json 不能带到今天', () => {
  const raw = { day: '2026-11-07', tasks: [{ task: '旧的', evidence: '旧的' }], review: { done: 1 } }
  const s = loadDaily(raw, NOW, TZ)
  assert.equal(s.day, '2026-11-08')
  assert.deepEqual(s.tasks, [], '过了零点就是新的一天')
  assert.equal(s.review, null)
  // 同一天要读回来
  const same = loadDaily({ day: '2026-11-08', tasks: [{ task: 'A', evidence: 'B' }] }, NOW, TZ)
  assert.equal(same.tasks.length, 1)
})

test('每日文件是人看的：写完能看到事、完成物和对账结论', () => {
  withDir((dir) => {
    const state = {
      day: '2026-11-08',
      tasks: [{ task: '线代第三章', evidence: '课后题做完' }],
      review: { done: 1, partial: 0, miss: 0, total: 1, quotaDelta: 1, results: [{ task: '线代第三章', why: '笔记里有', verdict: 'done' }] },
    }
    const w = writeDaily(dir, state)
    assert.equal(w.ok, true, w.text)
    const md = readFileSync(join(dir, '每日', '2026-11-08.md'), 'utf8')
    assert.match(md, /线代第三章/)
    assert.match(md, /完成物：课后题做完/)
    assert.match(md, /做完 1 件/)
    assert.match(md, /\+1 天/)
    // 只渲染不写盘也要稳
    assert.match(renderDaily({ day: '2026-11-08', tasks: [], review: null }), /（还没定）/)
  })
})

// ---- A2：白天的时间表 ------------------------------------------------------------------------
//
// 排程是纯函数：给定"现在几点 + 吃饭时段 + 我.md 的硬安排"，产出装了哪几块、哪几件排不下。
// 时间全部注入（2026-11-08 当天 + 北京时间），不依赖真实时钟。

/** 2026-11-08 当地 h:m 的 ms。 */
const at = (h, m = 0) => Date.UTC(2026, 10, 8, h - 8, m, 0)
const t = (over = {}) => ({ id: 't1', task: '写申论', evidence: '大肥鱼/申论.md', due: 18 * 60, minutes: 60, dept: '谋划思考', ...over })
const MEALS = [
  { from: 7 * 60, to: 7 * 60 + 40, name: '早饭' },
  { from: 11 * 60 + 30, to: 12 * 60 + 30, name: '午饭' },
  { from: 18 * 60, to: 19 * 60, name: '晚饭' },
]
const plan = (tasks, now, extra = {}) => schedule(tasks, { now, tz: TZ, meals: MEALS, ...extra })

test('A2 排程：一件事排进第一个空档', () => {
  const out = plan([t()], at(8))
  assert.equal(out.overflow.length, 0)
  assert.deepEqual(out.blocks.map((b) => [b.start, b.end]), [[8 * 60, 9 * 60]])
  assert.equal(out.blocks[0].taskId, 't1')
  assert.equal(out.blocks[0].dept, '谋划思考')
})

test('A2 排程：避开早饭时段', () => {
  const out = plan([t({ minutes: 30 })], at(7))
  assert.deepEqual(out.blocks.map((b) => [b.start, b.end]), [[7 * 60 + 40, 8 * 60 + 10]])
})

test('A2 排程：避开午饭时段（跨过它往后排，不切断任务）', () => {
  // 11:20 起排 60 分钟：11:20–11:30 只有 10 分钟，装不下，整体挪到 12:30。
  const out = plan([t({ minutes: 60 })], at(11, 20))
  assert.deepEqual(out.blocks.map((b) => [b.start, b.end]), [[12 * 60 + 30, 13 * 60 + 30]])
})

test('A2 排程：不排进深夜（窗口到 23:30 为止）', () => {
  assert.equal(SCHEDULE_TO, 23 * 60 + 30)
  const out = plan([t({ minutes: 90, due: 23 * 60 + 30 })], at(22, 30))
  assert.equal(out.blocks.length, 0, '22:30 之后放不下 90 分钟')
  assert.equal(out.overflow.length, 1)
  assert.match(out.overflow[0].why, /截止 23:30 前放不下 90 分钟/)
})

test('A2 排程：窗口起点是 7:00，凌晨排不出东西（深夜锁在窗口外）', () => {
  assert.equal(SCHEDULE_FROM, 7 * 60)
  const out = plan([t({ minutes: 30 })], at(3))
  assert.equal(out.blocks[0].start, 7 * 60 + 40, '3 点排 ＝ 从早饭后的 7:40 开始')
})

test('A2 排程：截止时刻是硬约束，过了就说放不下', () => {
  const out = plan([t({ minutes: 60, due: 8 * 60 + 30 })], at(8))
  assert.equal(out.blocks.length, 0)
  assert.equal(out.overflow.length, 1)
  assert.match(out.overflow[0].why, /截止 08:30 前放不下 60 分钟/)
  assert.equal(out.overflow[0].task, '写申论', '要说清是哪一件')
})

test('A2 排程：排不下不静默丢弃，blocks + overflow 件数守恒', () => {
  const tasks = [t({ id: 't1', task: 'A', minutes: 300, due: 13 * 60 }), t({ id: 't2', task: 'B', minutes: 300, due: 19 * 60 }), t({ id: 't3', task: 'C', minutes: 300, due: 23 * 60 })]
  const out = plan(tasks, at(8))
  assert.equal(out.blocks.length + out.overflow.length, 3, '一件都不能凭空消失')
  assert.ok(out.overflow.length >= 1, '当天装不下这么多')
})

test('A2 排程：截止早的先排', () => {
  const out = plan([t({ id: 't1', task: '晚的', minutes: 60, due: 20 * 60 }), t({ id: 't2', task: '早的', minutes: 60, due: 9 * 60 })], at(8))
  assert.deepEqual(out.blocks.map((b) => b.taskId), ['t2', 't1'])
})

test('A2 排程：块之间不重叠', () => {
  const out = plan([t({ id: 't1', minutes: 60 }), t({ id: 't2', minutes: 60 }), t({ id: 't3', minutes: 60 })], at(8))
  for (let i = 1; i < out.blocks.length; i++) assert.ok(out.blocks[i].start >= out.blocks[i - 1].end, '第 ' + i + ' 块压到上一块了')
  assert.deepEqual(out.blocks.map((b) => [b.start, b.end]), [[8 * 60, 9 * 60], [9 * 60, 10 * 60], [10 * 60, 11 * 60]])
})

test('A2 排程：我.md 里的硬安排要避开', () => {
  const fixed = fixedBlocksOf('# 我的硬安排\n- 08:00-10:00 上课\n')
  assert.deepEqual(fixed, [{ from: 8 * 60, to: 10 * 60, name: '上课' }])
  const out = plan([t({ minutes: 60 })], at(8), { fixedBlocks: fixed })
  assert.deepEqual(out.blocks.map((b) => [b.start, b.end]), [[10 * 60, 11 * 60]])
})

test('A2 硬安排解析：认半角/全角连字符和「到」「至」，不认单个时刻', () => {
  assert.equal(fixedBlocksOf('14:00~15:30 开会').length, 1)
  assert.equal(fixedBlocksOf('14:00—15:30 开会').length, 1)
  assert.equal(fixedBlocksOf('14:00 到 15:30 开会').length, 1)
  assert.equal(fixedBlocksOf('14:00 到 15:30 开会')[0].from, 14 * 60)
  assert.equal(fixedBlocksOf('23:30 睡觉').length, 0, '单个时刻不是区间')
  assert.equal(fixedBlocksOf('15:30-14:00 写反了').length, 0, '反向区间丢掉')
})

test('A2 排程：blocks 按开始时间升序返回，freeMin 是剩余空档', () => {
  const out = plan([t({ id: 't1', minutes: 60, due: 20 * 60 }), t({ id: 't2', minutes: 60, due: 9 * 60 })], at(8))
  assert.deepEqual(out.blocks.map((b) => b.start), [8 * 60, 9 * 60])
  // 09:00 之后到 23:30，扣掉两件（各 60 分钟）、午饭（11:30–12:30）和晚饭（18:00–19:00）
  assert.equal(out.freeMin, 690)
})

test('A2 排程：预估分钟小于下限按下限算，不排 0 分钟的块', () => {
  const out = plan([t({ minutes: 1 })], at(8))
  assert.equal(out.blocks[0].minutes, 10)
  assert.equal(out.blocks[0].end - out.blocks[0].start, 10)
})

test('A2 排程：没有任务就是空表', () => {
  const out = plan([], at(8))
  assert.deepEqual(out.blocks, [])
  assert.deepEqual(out.overflow, [])
})

test('A2 时刻格式：HH:MM 来回换，坏输入不变成 NaN', () => {
  assert.equal(hhmm(0), '00:00')
  assert.equal(hhmm(14 * 60 + 5), '14:05')
  assert.equal(clockMin('14:05'), 14 * 60 + 5)
  assert.equal(clockMin('9:00'), 9 * 60)
  assert.equal(clockMin('24:00'), null)
  assert.equal(clockMin('14:60'), null)
  assert.equal(clockMin('下午两点'), null)
  assert.equal(hhmm(undefined), '--:--')
})

test('A2 约束层那一行：当前块还剩多少分钟', () => {
  const b = { task: '写申论', dept: '谋划思考', start: 14 * 60, end: 15 * 60 + 30 }
  assert.equal(blockLine(b, 14 * 60 + 48), '[此刻] 14:00–15:30 写申论（谋划思考部）· 还剩 42 分钟')
  assert.equal(blockLine(null, 0), '')
  assert.match(lateLine(b), /该交墙了/)
})

// ---- 重排：额度 + 触发条件 --------------------------------------------------------------------

const stateOf = (tasks, plan, over = {}) => ({ day: '2026-11-08', tasks, plan, review: null, ...over })

test('A2 重排：聊天里说「晚点做」不触发', () => {
  const s = stateOf([t()], null)
  const r = reschedule(s, { now: at(9), tz: TZ, trigger: 'chat', meals: MEALS })
  assert.equal(r.ok, false)
  assert.match(r.text, /不算重排/)
  assert.match(r.text, /看得见的动作/)
})

test('A2 重排：认不出的触发条件一律不认（只有提前交墙 / 到点没交）', () => {
  for (const bad of ['', 'user-says-later', 'reset', 'admin']) {
    assert.equal(reschedule(stateOf([t()], null), { now: at(9), tz: TZ, trigger: bad, meals: MEALS }).ok, false, '「' + bad + '」不该触发')
  }
})

test('A2 重排：到点没交 → 剩下的往后挪，记录追加', () => {
  const first = planSweep(stateOf([t()], null), { now: at(8), tz: TZ, meals: MEALS })
  const r = reschedule(first.state, { now: at(11), tz: TZ, trigger: 'overdue', meals: MEALS })
  assert.equal(r.ok, true, r.text)
  assert.equal(r.state.plan.reschedules.length, 1)
  assert.equal(r.state.plan.reschedules[0].trigger, 'overdue')
  assert.equal(r.state.plan.reschedules[0].atMin, 11 * 60, '记下第几分钟改的（渲染每日文件时不用再算时区）')
  // 11:00 起排 60 分钟：11:00–11:30 只有 30 分钟，装不下；整体挪到午饭后的 12:30。
  assert.equal(r.state.plan.blocks[0].start, 12 * 60 + 30, '从现在的空档重新排')
  assert.match(r.text, /第 1\/2 次/)
})

test('A2 重排：每天 2 次，第 3 次拒绝并说清按原表走', () => {
  let s = planSweep(stateOf([t({ minutes: 60 })], null), { now: at(8), tz: TZ, meals: MEALS }).state
  s = reschedule(s, { now: at(9, 30), tz: TZ, trigger: 'overdue', meals: MEALS }).state
  s = reschedule(s, { now: at(11), tz: TZ, trigger: 'overdue', meals: MEALS }).state
  assert.equal(s.plan.reschedules.length, MAX_RESCHEDULE_PER_DAY)
  const third = reschedule(s, { now: at(13), tz: TZ, trigger: 'overdue', meals: MEALS })
  assert.equal(third.ok, false)
  assert.match(third.text, /用完了（2 次）/)
  assert.match(third.text, /照原表走/)
})

test('A2 重排：已经交墙的块不再排', () => {
  let s = planSweep(stateOf([t({ id: 't1', task: 'A', minutes: 60 }), t({ id: 't2', task: 'B', minutes: 60, due: 19 * 60 })], null), { now: at(8), tz: TZ, meals: MEALS }).state
  s = markBlockDone(s, 't1', 9 * 60)
  const r = reschedule(s, { now: at(9), tz: TZ, trigger: 'submitted-early', meals: MEALS })
  assert.equal(r.ok, true, r.text)
  assert.deepEqual(r.state.plan.blocks.map((b) => b.taskId), ['t2'], 'A 已经收工，不再排')
})

test('A2 重排：全收工就没得排', () => {
  let s = planSweep(stateOf([t({ id: 't1' })], null), { now: at(8), tz: TZ, meals: MEALS }).state
  s = markBlockDone(s, 't1', 9 * 60)
  const r = reschedule(s, { now: at(10), tz: TZ, trigger: 'submitted-early', meals: MEALS })
  assert.equal(r.ok, false)
  assert.match(r.text, /都收工了/)
})

test('A2 重排：没定事就没得重排', () => {
  const r = reschedule(stateOf([], null), { now: at(10), tz: TZ, trigger: 'overdue', meals: MEALS })
  assert.equal(r.ok, false)
  assert.match(r.text, /还没定事/)
})

test('A2 重排：记录只追加不覆盖（一天改两次，两条都留着）', () => {
  let s = planSweep(stateOf([t({ minutes: 60 })], null), { now: at(8), tz: TZ, meals: MEALS }).state
  s = reschedule(s, { now: at(9, 30), tz: TZ, trigger: 'overdue', meals: MEALS }).state
  s = reschedule(s, { now: at(11), tz: TZ, trigger: 'submitted-early', meals: MEALS }).state
  assert.deepEqual(s.plan.reschedules.map((r) => r.trigger), ['overdue', 'submitted-early'])
})

// ---- 边界自动触发：一整天轮询，每条边界各触发一次 ----------------------------------------------

test('A2 边界：第一次看到今天的事 → 排表 + 块开始，各一次', () => {
  const out = planSweep(stateOf([t({ minutes: 60 })], null), { now: at(8), tz: TZ, meals: MEALS })
  assert.deepEqual(out.events.map((e) => e.kind), ['plan-made', 'block-start'])
  assert.equal(out.events[1].taskId, 't1')
  assert.ok(out.current, '此刻正在这一块里')
  assert.equal(out.current.end, 9 * 60)
})

test('A2 边界：同一分钟内反复问，不重复触发', () => {
  let s = planSweep(stateOf([t()], null), { now: at(8), tz: TZ, meals: MEALS }).state
  for (let i = 0; i < 5; i++) {
    const again = planSweep(s, { now: at(8, 10), tz: TZ, meals: MEALS })
    assert.deepEqual(again.events, [], '第 ' + (i + 1) + ' 次不该有新事件')
    s = again.state
  }
})

test('A2 边界：块结束只触发一次；之后到期不再重复', () => {
  let s = planSweep(stateOf([t()], null), { now: at(8), tz: TZ, meals: MEALS }).state
  const first = planSweep(s, { now: at(9), tz: TZ, meals: MEALS })
  assert.deepEqual(first.events.map((e) => e.kind), ['block-end'])
  s = first.state
  const later = planSweep(s, { now: at(9, 40), tz: TZ, meals: MEALS })
  assert.deepEqual(later.events, [], '同一个块不会收两次尾')
})

test('A2 边界：一整天轮询——每块恰好一次 start、一次 end，顺序不乱', () => {
  const tasks = [t({ id: 't1', task: 'A', minutes: 60, due: 18 * 60 }), t({ id: 't2', task: 'B', minutes: 60, due: 19 * 60 })]
  let s = stateOf(tasks, null)
  const events = []
  // 8:00 到 23:00 每 5 分钟问一次（模拟手机闹钟问 /plan）。
  for (let min = 8 * 60; min <= 23 * 60; min += 5) {
    const out = planSweep(s, { now: at(Math.floor(min / 60), min % 60), tz: TZ, meals: MEALS })
    events.push(...out.events)
    s = out.state
  }
  const starts = events.filter((e) => e.kind === 'block-start')
  const ends = events.filter((e) => e.kind === 'block-end')
  assert.equal(starts.length, 2, '两块各开始一次')
  assert.equal(ends.length, 2, '两块各收尾一次')
  assert.deepEqual(starts.map((e) => e.taskId), ['t1', 't2'])
  assert.deepEqual(ends.map((e) => e.taskId), ['t1', 't2'])
  // 每块的 end 必须排在自己的 start 后面
  for (const id of ['t1', 't2']) {
    const si = events.findIndex((e) => e.kind === 'block-start' && e.taskId === id)
    const ei = events.findIndex((e) => e.kind === 'block-end' && e.taskId === id)
    assert.ok(si >= 0 && ei > si, id + ' 的收尾要在开始之后')
  }
})

test('A2 边界：整段错过不补记「开始」（不编造没发生过的事）', () => {
  // 一整天没开手机，晚上 22:00 第一次问。
  const out = planSweep(stateOf([t({ minutes: 60, due: 10 * 60 })], null), { now: at(22), tz: TZ, meals: MEALS })
  assert.equal(out.events.filter((e) => e.kind === 'block-start').length, 0)
  assert.equal(out.events.filter((e) => e.kind === 'block-end').length, 0, '这件事根本没排上，也没有块可收尾')
  assert.equal(out.plan.overflow.length, 1)
  assert.equal(out.current, null)
})

test('A2 边界：到点 10 分钟内还算「该交墙了」，交了墙就消失', () => {
  let s = planSweep(stateOf([t()], null), { now: at(8), tz: TZ, meals: MEALS }).state
  s = planSweep(s, { now: at(9, 5), tz: TZ, meals: MEALS }).state
  const lateOut = planSweep(s, { now: at(9, 5), tz: TZ, meals: MEALS })
  assert.ok(lateOut.late, '9:05 还在 10 分钟宽限里')
  assert.equal(lateOut.late.taskId, 't1')
  const done = markBlockDone(lateOut.state, 't1', 9 * 60 + 5)
  const after = planSweep(done, { now: at(9, 6), tz: TZ, meals: MEALS })
  assert.equal(after.late, null, '交过墙就不再点他')
})

test('A2 每日文件：时间表和重排记录都写给人看', () => {
  withDir((dir) => {
    let s = planSweep(stateOf([t({ minutes: 60 })], null), { now: at(8), tz: TZ, meals: MEALS }).state
    s = reschedule(s, { now: at(9, 30), tz: TZ, trigger: 'overdue', meals: MEALS }).state
    s = { ...s, day: '2026-11-08' }
    const w = writeDaily(dir, s)
    assert.equal(w.ok, true, w.text)
    const md = readFileSync(join(dir, '每日', '2026-11-08.md'), 'utf8')
    assert.match(md, /## 时间表/)
    assert.match(md, /写申论\*\*（谋划思考部，估 60 分）/)
    assert.match(md, /重排 09:30 · 有一件到点没交/, '重排要留痕，不然晚上看不出这一天改过')
    assert.match(md, /截止 18:00 · 估 60 分钟 · 谋划思考部/)
  })
})

test('A2 排程回话：排不下的时候说清是哪一件', () => {
  const out = plan([t({ task: '写 8000 字', minutes: 480, due: 9 * 60 })], at(8))
  const text = scheduleText(out)
  assert.match(text, /排不下：写 8000 字/)
  assert.match(text, /截止 09:00/)
})

// ---------------------------------------------------------------- 第九轮第二项：四个数进日报第一行

test('S16 日报第一行写四个数：主动开局比 / 交付率 / 空局率 / 局内偏离', () => {
  const md = renderDaily({ day: '2026-11-14', tasks: [], review: null }, {}, {
    selfOpenRatio: 0.5, deliverRatio: 0.75, badRatio: 0.25, deviationMin: 12,
  })
  assert.match(md, /主动开局比 50%/)
  assert.match(md, /交付率 75%/)
  assert.match(md, /空局率 25%/)
  assert.match(md, /局内偏离 12 分钟/)
  // 必须在正文最前面（标题行之后第一段），不是藏在文末。
  assert.ok(md.indexOf('主动开局比') < md.indexOf('今天的事'))
})

test('S17 算不出的比率写「—」，不写 0%', () => {
  const md = renderDaily({ day: '2026-11-14', tasks: [], review: null }, {}, {
    selfOpenRatio: null, deliverRatio: null, badRatio: null, deviationMin: 0,
  })
  assert.match(md, /主动开局比 — · 交付率 — · 空局率 — · 局内偏离 0 分钟/)
  assert.doesNotMatch(md, /0%/)
})

test('S18 不传四数就不写那一行，老调用点行为不变', () => {
  const md = renderDaily({ day: '2026-11-14', tasks: [], review: null })
  assert.doesNotMatch(md, /主动开局比/)
})
