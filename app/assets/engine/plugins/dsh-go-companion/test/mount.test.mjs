// C1 工作记忆挂载卡：卡里只能是他自己的话，同时只有一张 open 卡，断链能测出来。
//
// 这一组用例的重点不是「功能都跑通」，而是**她用代码拿不到的那三条**：
//   ① 她不能替他总结（非原话一律拒）；② 不能两张卡同时开着；③ 结论必须指出由哪几条已知推出。

import assert from 'node:assert/strict'
import test from 'node:test'

import {
  DRIFT_MIN_OVERLAP, DRIFT_STREAK, INJECT_MAX, MAX_GIVENS, MAX_STEPS, MEMORY_LEAK_TRACK,
  MOUNT_SLOTS, MOUNT_STATES, TEXT_MAX,
  acceptProposal, closeCard, driftOf, isUserText, keywordsOf, mountLine, mountStatus, newCard,
  nextStreak, normalizeCard, openCard, overlapOf, parkCard, propose, proposeCap, resumeCard,
  scaffold, shouldShowCard, withGiven, withStep,
} from '../lib/mount.js'
import { normalizeTrack } from '../lib/tracks.js'

const T0 = 1_794_000_000_000
const HIM = ['我想弄明白：内存漏水为什么会让推导断掉', '先假设是工作记忆只有三栏：目标、已知、推到哪']

/** 一张开着的卡，带两条已知、一条结论。sources 给 HIM，所以内容都是他的原话。 */
function card(extra = {}) {
  const made = newCard({ topicId: 'leak', goal: '我想弄明白：内存漏水为什么会让推导断掉', at: T0, sources: HIM })
  assert.equal(made.ok, true, made.text)
  let c = made.card
  c = withGiven(c, { text: '先假设是工作记忆只有三栏：目标、已知、推到哪', at: T0 + 1, src: 'L12', sources: HIM }).card
  c = withGiven(c, { text: '断掉的代价比挂一张卡大得多', at: T0 + 2, sources: [] }).card
  c = withStep(c, { text: '所以卡上必须写清推到哪一步', at: T0 + 3, from: [0, 1], sources: [] }).card
  return { ...c, ...extra }
}

// ---------------------------------------------------------------- ① 她不能替他填

test('C1 目标必须是他自己的原话，命中一句用户消息才放行', () => {
  const ok = newCard({ topicId: 'x', goal: '内存漏水为什么会让推导断掉', at: T0, sources: HIM })
  assert.equal(ok.ok, true)
  assert.equal(ok.card.goal.by, 'user')
  assert.equal(ok.card.status, 'open')
  const bad = newCard({ topicId: 'x', goal: '他在纠结工作记忆的问题', at: T0, sources: HIM })
  assert.equal(bad.ok, false)
  assert.equal(bad.code, 'not-user-text')
})

test('C2 半句可以挂（子串），把两句拼成一句不行', () => {
  assert.equal(isUserText('内存漏水', HIM), true)
  assert.equal(isUserText('内存漏水为什么会让推导断掉', HIM), true)
  assert.equal(isUserText('内存漏水，所以他不专注', HIM), false)
})

test('C3 已知条件非原话直接拒，且卡保持原样', () => {
  const c = card()
  const r = withGiven(c, { text: '她替他总结的一条已知', at: T0 + 9, sources: HIM })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'not-user-text')
  assert.deepEqual(r.card.givens, c.givens)
})

test('C4 结论非原话直接拒', () => {
  const r = withStep(card(), { text: '她替他下的结论', at: T0 + 9, from: [0], sources: HIM })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'not-user-text')
})

test('C5 提议没有账本行号不受理——没有出处就不提议', () => {
  const r = propose({ text: '内存漏水', slot: 'given', at: T0, sources: HIM })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'propose-needs-source')
})

test('C6 提议的正文也必须是他原话，她的总结进不了卡', () => {
  const ok = propose({ text: '内存漏水', slot: 'given', at: T0, src: 'L12', sources: HIM })
  assert.equal(ok.ok, true)
  assert.equal(ok.proposal.src, 'L12')
  assert.equal(ok.proposal.slot, 'given')
  const bad = propose({ text: '他卡在工作记忆上', slot: 'given', at: T0, src: 'L12', sources: HIM })
  assert.equal(bad.ok, false)
  assert.equal(bad.code, 'not-user-text')
  const slot = propose({ text: '内存漏水', slot: 'nope', at: T0, src: 'L12', sources: HIM })
  assert.equal(slot.ok, false)
  assert.equal(slot.code, 'bad-slot')
  assert.deepEqual([...MOUNT_SLOTS], ['goal', 'given', 'step'])
})

test('C7 一个字不算原话；超长的原话照样是原话，落卡时才截', () => {
  assert.equal(isUserText('水', []), false)
  // 长度不是「真假原话」的判据：他一次说了 121 字也是他说的，落卡时 cut 到 120。
  assert.equal(isUserText('水'.repeat(TEXT_MAX + 1), []), true)
  assert.equal(isUserText('水'.repeat(TEXT_MAX), []), true)
})

// ---------------------------------------------------------------- ② 同时只有一张 open 卡

test('C8 已经有一张开着的卡就不能再开，先停放或关掉', () => {
  const r = openCard({ card: card() }, { topicId: 'next', goal: '内存漏水', at: T0 + 99, sources: [] })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'card-open')
  assert.equal(r.card.status, 'open')
})

test('C9 停放之后能开新卡', () => {
  const parked = parkCard(card(), { at: T0 + 10, where: '推到「结论要标已知」那一步' })
  assert.equal(parked.ok, true)
  const r = openCard({ card: parked.card }, { topicId: 'next', goal: '内存漏水', at: T0 + 99, sources: [] })
  assert.equal(r.ok, true)
  assert.equal(r.card.status, 'open')
})

test('C10 关掉之后也能开新卡', () => {
  const closed = closeCard(card(), { at: T0 + 10 })
  assert.equal(closed.ok, true)
  const r = openCard({ card: closed.card }, { topicId: 'next', goal: '内存漏水', at: T0 + 99, sources: [] })
  assert.equal(r.ok, true)
})

// ---------------------------------------------------------------- ③ 上限

test('C11 已知条件最多 5 条，第 6 条拒', () => {
  let c = card()
  const extra = ['第一句他的原话', '第二句他的原话', '第三句他的原话']
  for (const t of extra) {
    const r = withGiven(c, { text: t, at: T0, sources: [] })
    assert.equal(r.ok, true, r.text)
    c = r.card
  }
  assert.equal(c.givens.length, MAX_GIVENS)
  const over = withGiven(c, { text: '第六条', at: T0, sources: [] })
  assert.equal(over.ok, false)
  assert.equal(over.code, 'givens-full')
})

test('C12 中间结论最多 5 条，第 6 条拒', () => {
  let c = card()
  for (let i = 0; i < MAX_STEPS - 1; i += 1) {
    const r = withStep(c, { text: '第 ' + (i + 2) + ' 条结论', at: T0, from: [0], sources: [] })
    assert.equal(r.ok, true, r.text)
    c = r.card
  }
  assert.equal(c.steps.length, MAX_STEPS)
  const over = withStep(c, { text: '第六条结论', at: T0, from: [0], sources: [] })
  assert.equal(over.ok, false)
  assert.equal(over.code, 'steps-full')
})

test('C13 单条文字截到 120，并留一个省略号', () => {
  const long = '很'.repeat(TEXT_MAX + 40)
  const r = withGiven(card(), { text: long, at: T0, sources: [] })
  assert.equal(r.ok, true)
  const one = r.card.givens[r.card.givens.length - 1]
  assert.equal(one.text.length, TEXT_MAX)
  assert.equal(one.text.endsWith('…'), true)
})

// ---------------------------------------------------------------- ④ 断链检测

test('C14 还在同一件事上：不算漂', () => {
  const d = driftOf(card(), '内存漏水这件事我还想接着推，推导断掉很烦')
  assert.equal(d.drift, false)
  assert.equal(d.hit >= DRIFT_MIN_OVERLAP, true)
})

test('C15 完全换了个话题：算漂', () => {
  const d = driftOf(card(), '今天天气不错要不要出去走走')
  assert.equal(d.drift, true)
  assert.equal(d.hit < DRIFT_MIN_OVERLAP, true)
})

test('C16 连续 2 轮才亮卡，第 1 轮不亮', () => {
  let streak = 0
  streak = nextStreak(streak, true)
  assert.equal(streak, 1)
  assert.equal(shouldShowCard(0, true), false)
  streak = nextStreak(streak, true)
  assert.equal(streak, DRIFT_STREAK)
  assert.equal(shouldShowCard(1, true), true)
})

test('C17 中途回到主题，连续计数清零', () => {
  assert.equal(nextStreak(1, false), 0)
  assert.equal(shouldShowCard(1, false), false)
  assert.equal(nextStreak(0, false), 0)
})

test('C18 不是 open 的卡不检测、不注入', () => {
  const parked = parkCard(card(), { at: T0, where: '推到第三步' }).card
  assert.deepEqual(driftOf(parked, '内存漏水'), { drift: false, hit: 0, need: DRIFT_MIN_OVERLAP })
  assert.equal(mountLine(parked), '')
  assert.equal(mountLine(null), '')
})

// ---------------------------------------------------------------- ⑤ 停放与恢复

test('C19 停放必须说一句停在哪，说不出就不许停', () => {
  const r = parkCard(card(), { at: T0 + 5 })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'park-needs-why')
  const ok = parkCard(card(), { at: T0 + 5, where: '推到「结论要标已知」' })
  assert.equal(ok.ok, true)
  assert.equal(ok.card.status, 'parked')
  assert.equal(ok.card.parkedWhy, '推到「结论要标已知」')
})

test('C20 停放 → 恢复，三栏内容一条不少', () => {
  const before = card()
  const parked = parkCard(before, { at: T0 + 5, where: '推到结论那一步' }).card
  const back = resumeCard(parked, { at: T0 + 6 })
  assert.equal(back.ok, true)
  assert.equal(back.card.status, 'open')
  assert.equal(back.card.goal.text, before.goal.text)
  assert.deepEqual(back.card.givens.map((g) => g.text), before.givens.map((g) => g.text))
  assert.deepEqual(back.card.steps.map((s) => s.text), before.steps.map((s) => s.text))
})

test('C21 只有停着的卡能恢复；开着的、关掉的都不行', () => {
  assert.equal(resumeCard(card(), { at: T0 }).code, 'card-not-parked')
  const closed = closeCard(card(), { at: T0 }).card
  assert.equal(resumeCard(closed, { at: T0 }).code, 'card-not-parked')
  // 关掉之后就只读：再挂已知必须被拒
  assert.equal(withGiven(closed, { text: '他的原话', at: T0, sources: [] }).code, 'card-not-open')
  assert.equal(withStep(closed, { text: '他的结论', at: T0, from: [0], sources: [] }).code, 'card-not-open')
  assert.equal(closeCard(closed, { at: T0 }).code, 'card-closed')
})

// ---------------------------------------------------------------- ⑥ 结论必须引用已知

test('C22 结论不标出由哪几条已知推出，直接拒', () => {
  const r = withStep(card(), { text: '所以推导要先写目标', at: T0, sources: [] })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'step-needs-given')
})

test('C23 引用越界的下标也拒', () => {
  const c = card()
  assert.equal(withStep(c, { text: '所以要先写目标', at: T0, from: [99], sources: [] }).code, 'step-needs-given')
  assert.equal(withStep(c, { text: '所以要先写目标', at: T0, from: [-1], sources: [] }).code, 'step-needs-given')
  assert.equal(withStep(c, { text: '所以要先写目标', at: T0, from: ['x'], sources: [] }).code, 'step-needs-given')
})

test('C24 卡上还没有已知条件时，结论无从推起', () => {
  const bare = newCard({ topicId: 'bare', goal: '内存漏水', at: T0, sources: [] }).card
  assert.deepEqual(bare.givens, [])
  assert.equal(withStep(bare, { text: '所以要先写目标', at: T0, from: [0], sources: [] }).code, 'step-needs-given')
})

test('C25 引用去重，并且真的记在结论上', () => {
  const r = withStep(card(), { text: '所以卡上要写推到哪里', at: T0, from: [1, 0, 1], sources: [] })
  assert.equal(r.ok, true, r.text)
  const last = r.card.steps[r.card.steps.length - 1]
  assert.deepEqual(last.from, [1, 0])
})

// ---------------------------------------------------------------- 注入行过预算

test('C26 注入行是规格那个格式，且 ≤ 300 字符', () => {
  const line = mountLine(card(), { hm: '14:20' })
  assert.equal(line.startsWith('[挂载] 目标：'), true)
  assert.equal(line.includes('已知：①'), true)
  assert.equal(line.includes('推到：'), true)
  assert.equal(line.includes('｜ 上次动：14:20'), true)
  assert.equal(line.length <= INJECT_MAX, true, line.length + ' > ' + INJECT_MAX)
})

test('C27 装不下时先丢最后几条已知，目标和结论一定留下', () => {
  // 每条 120 字、5 条已知 + 5 条结论，一定超 300。
  let c = newCard({ topicId: 'big', goal: '内存漏水'.repeat(10), at: T0, sources: [] }).card
  for (let i = 0; i < MAX_GIVENS; i += 1) {
    c = withGiven(c, { text: '已知' + i + '号' + '细节'.repeat(50), at: T0, sources: [] }).card
  }
  for (let i = 0; i < MAX_STEPS; i += 1) {
    c = withStep(c, { text: '结论' + i + '号' + '推论'.repeat(50), at: T0, from: [0], sources: [] }).card
  }
  const line = mountLine(c, { hm: '09:00' })
  assert.equal(line.length <= INJECT_MAX, true, line.length + ' > ' + INJECT_MAX)
  assert.equal(line.startsWith('[挂载] 目标：'), true)
  // 已知被砍了一部分：出现在行里的 ① 还在，但 ⑤ 已经装不下了
  assert.equal((line.match(/[①②③④⑤]/g) || []).length < MAX_GIVENS, true)
})

test('C28 一行都没有的时候不返回半截空壳', () => {
  assert.equal(mountLine(card(), {}).includes('上次动'), false)
  assert.equal(mountLine(normalizeCard({ goal: '内存漏水' }), {}), '')
})

// ---------------------------------------------------------------- 脚手架撤除

test('C29 比值 = 他自己挂的次数 / 她提议才挂的次数', () => {
  const rows = [
    { state: 'mount', action: 'add', by: 'user' },
    { state: 'mount', action: 'add', by: 'user' },
    { state: 'mount', action: 'add', by: 'her' },
    { state: 'mount', action: 'park' },
    { state: 'other', action: 'add', by: 'user' },
  ]
  const s = scaffold(rows)
  assert.equal(s.user, 2)
  assert.equal(s.her, 1)
  assert.equal(s.ratio, 2)
  assert.equal(s.hit, 3)
})

test('C30 比值越好，她一天能提议的次数越少（单调不增）', () => {
  const ladder = [0, 0.4, 0.6, 1.2, 2.5].map((r) => proposeCap(r))
  assert.deepEqual(ladder, [3, 3, 2, 1, 0])
  for (let i = 1; i < ladder.length; i += 1) {
    assert.equal(ladder[i] <= ladder[i - 1], true, '比值上升时提议上限必须下降')
  }
})

test('C31 她一次都没提议过、他却自己挂过：比值无穷大，她不用再提议', () => {
  const s = scaffold([{ state: 'mount', action: 'add', by: 'user' }])
  assert.equal(s.her, 0)
  assert.equal(s.ratio, Number.POSITIVE_INFINITY)
  assert.equal(s.cap, 0)
  // 一条记录都没有：不是无穷大，是最低档
  assert.equal(proposeCap(scaffold([]).ratio), 3)
})

// ---------------------------------------------------------------- 支线四槽位 + 脏数据

test('C32 「内存漏水」是一条合格的支线：四个槽位齐全，能过 normalizeTrack', () => {
  const r = normalizeTrack(MEMORY_LEAK_TRACK, 0)
  assert.equal(r.ok, true, JSON.stringify(r.missing))
  assert.deepEqual(Object.keys(r.track), ['id', 'scene', 'first', 'missed', 'blind', 'name', 'enabled'])
  assert.equal(r.track.id, 'memory-leak')
  for (const key of ['scene', 'first', 'missed', 'blind']) {
    assert.equal(typeof r.track[key], 'string')
    assert.equal(r.track[key].length > 0, true, key + ' 是空的')
  }
})

test('C33 三种状态是协议，别的值一律当关掉的卡', () => {
  assert.deepEqual([...MOUNT_STATES], ['open', 'parked', 'closed'])
  assert.equal(normalizeCard({ goal: { text: '内存漏水' }, status: 'zombie' }).status, 'closed')
  assert.equal(normalizeCard({ goal: { text: '内存漏水' }, status: 'parked' }).status, 'parked')
  assert.equal(normalizeCard({ goal: { text: '内存漏水' }, status: 'open' }).status, 'open')
})

test('C34 脏数据撑得住：缺字段、类型全错都不抛', () => {
  assert.equal(normalizeCard(null), null)
  assert.equal(normalizeCard('字符串'), null)
  assert.equal(normalizeCard({}), null)
  const weird = normalizeCard({
    goal: { text: '  内存漏水  ' }, givens: [null, 3, { text: '' }, { text: '他的原话', at: 'x' }],
    steps: [{ text: '他的结论', from: [5, 'a'] }], status: 1,
  })
  assert.equal(weird.goal.text, '内存漏水')
  assert.equal(weird.givens.length, 1)
  assert.equal(weird.givens[0].at, 0)
  // 引用越界的 from 被清空，但不影响结论文字
  assert.deepEqual(weird.steps[0].from, [])
  assert.equal(weird.status, 'closed')
  assert.equal(normalizeCard({ status: 'open' }), null)
})

test('C35 状态视图能一眼看出三栏各有几条、还能提议几次', () => {
  const s = mountStatus(card(), [{ state: 'mount', action: 'add', by: 'user' }])
  assert.equal(s.status, 'open')
  assert.equal(s.goal.startsWith('我想弄明白'), true)
  // 给的是条目本身（面板要摆原话和出处），条数由界面取 .length。
  assert.equal(s.givens.length, 2)
  assert.equal(s.givens[0].text, card().givens[0].text)
  assert.equal(s.steps.length, 1)
  assert.deepEqual(s.steps[0].from, card().steps[0].from)
  assert.equal(s.maxGivens, MAX_GIVENS)
  assert.equal(s.maxSteps, MAX_STEPS)
  assert.equal(s.scaffold.user, 1)
  // 没有卡时的视图：status 是 none，不冒充一张空卡
  const none = mountStatus(null, [])
  assert.equal(none.status, 'none')
  assert.equal(none.goal, '')
})

test('C36 关键词按二字组算，中英文都要认', () => {
  assert.equal(overlapOf('内存漏水', '内存漏水') > 0, true)
  assert.equal(keywordsOf('memory leak').has('memory'), true)
  assert.equal(keywordsOf('memory leak').has('leak'), true)
  assert.equal(overlapOf('内存漏水', '今天天气不错'), 0)
})

test('C37 确认提议才落卡；目标不再追加', () => {
  const c = card()
  const p = propose({ text: '断掉的代价比挂一张卡大得多', slot: 'given', at: T0, src: 'L15', sources: HIM })
  assert.equal(p.ok, false) // 这句不在 HIM 里，先证明它不是原话
  const q = propose({ text: '内存漏水', slot: 'given', at: T0, src: 'L15', sources: HIM })
  const landed = acceptProposal(c, q.proposal, { at: T0 + 30 })
  assert.equal(landed.ok, true, landed.text)
  assert.equal(landed.card.givens.length, c.givens.length + 1)
  const asGoal = acceptProposal(c, { text: '内存漏水', slot: 'goal', by: 'user' }, { at: T0 })
  assert.equal(asGoal.ok, false)
  assert.equal(asGoal.code, 'bad-slot')
  assert.equal(acceptProposal(c, null, {}).code, 'bad-proposal')
})
