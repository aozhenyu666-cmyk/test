// 第八轮 W1：局面板四页（现在 / 队列 / 协议库 / 账）。
//
// 这里测三件事，都是面板最容易骗人的地方：
//   1. 没数据时写「—」，不写 0——0 分和「还没判」不是一件事；
//   2. 通过率的分母只算判过的拍，没判的拍不能算成没通过；
//   3. 四页各自绑的字段，在快照里都必须真有，不能靠视图自己编。

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { boutsTileStatus, buildBoutsSnapshot, isPass, minutesByProtocol, moveRates, todayScore } from '../lib/bouts_panel.js'
import {
  boutsBooksView,
  boutsNowView,
  boutsProtocolsView,
  boutsQueueView,
  boutsView,
} from '../lib/bouts_view.js'

const NOW = Date.parse('2026-11-12T09:30:00+08:00')
const FORM = { components: ['form'] }
const BARE = { components: [] }

function bindingsOf(node, out = new Set()) {
  if (!node || typeof node !== 'object') return out
  if (node.$ && typeof node.$ === 'string') {
    out.add(node.$)
    return out
  }
  if (Array.isArray(node)) {
    for (const x of node) bindingsOf(x, out)
    return out
  }
  for (const [k, v] of Object.entries(node)) {
    if (k === 'options') for (const o of v || []) bindingsOf(o, out)
    else bindingsOf(v, out)
  }
  return out
}

function valueAt(obj, path) {
  return String(path).replace(/^data\./, '').split('.').reduce((acc, key) => (acc == null ? acc : acc[key]), obj)
}

/** 一页的绑定，全部能在快照里取到（data.form.* 是动作入参，不算）。 */
function assertBound(view, snap, label) {
  for (const key of bindingsOf(view)) {
    if (key.startsWith('data.form.')) continue
    assert.notEqual(valueAt(snap, key), undefined, label + ' 绑了快照里没有的字段：' + key)
  }
}

const LIVE = {
  boutId: 'bout:1', stepId: '定位', ask: '决定答案的是哪一句？', limitSec: 600,
  askedAt: NOW - 120_000, writeMode: false, chaosStreak: 0,
}

test('W1 四页：每页都有自己的标题，不是同一张长卷', () => {
  assert.equal(boutsNowView({}, BARE).title, '现在')
  assert.equal(boutsQueueView({}, BARE).title, '队列')
  assert.equal(boutsProtocolsView({}, BARE).title, '协议库')
  assert.equal(boutsBooksView({}, BARE).title, '账')
})

test('W1 四页：局面板默认开在「现在」，与 bouts-open 对应', () => {
  assert.deepEqual(boutsView({}, BARE), boutsNowView({}, BARE))
})

test('W1 四页：四页的绑定各不越界，各自都能在快照里取到', () => {
  const snap = buildBoutsSnapshot({
    now: NOW, live: LIVE,
    bouts: [{ id: 'bout:1', status: 'running', protocolId: 'execute' }],
    todayBouts: [{ id: 'bout:1', status: 'running', protocolId: 'execute', openedAt: NOW - 600_000 }],
    protocols: [{ id: 'execute', name: '执行局', steps: [{}, {}] }],
    beats: [{ stepId: '定位', verdict: 'pass' }],
    todayVerdicts: [{ score: 8 }],
  })
  assertBound(boutsNowView({}, FORM), snap, '现在')
  assertBound(boutsQueueView({}, FORM), snap, '队列')
  assertBound(boutsProtocolsView({}, FORM), snap, '协议库')
  assertBound(boutsBooksView({}, FORM), snap, '账')
})

test('W1 现在页：只收「答这一拍」，不摆加局表单', () => {
  const view = JSON.stringify(boutsNowView({}, FORM))
  assert.ok(view.includes('bouts.answer'))
  assert.ok(!view.includes('bouts.open'))
})

test('W1 队列页：能加局；没有 form 能力时按钮还在、表单不在', () => {
  assert.ok(JSON.stringify(boutsQueueView({}, FORM)).includes('bouts.open'))
  const bare = JSON.stringify(boutsQueueView({}, BARE))
  assert.ok(!bare.includes('bouts.open'))
  assert.ok(bare.includes('bouts.refresh'))
})

test('W1 协议库：只读列表，不给加局入口', () => {
  const view = JSON.stringify(boutsProtocolsView({}, FORM))
  assert.ok(view.includes('data.protocolRows'))
  assert.ok(!view.includes('bouts.answer'))
})

test('W1 账页：判定分与招式通过率都上墙', () => {
  const view = JSON.stringify(boutsBooksView({}, BARE))
  assert.ok(view.includes('data.books.score'))
  assert.ok(view.includes('data.books.moves'))
  assert.ok(view.includes('data.books.minutes'))
})

test('W1 账页：没有判定分时写「—」，不写 0', () => {
  const snap = buildBoutsSnapshot({ now: NOW })
  assert.equal(snap.books.score, '—')
  assert.ok(snap.books.scoreCaption.includes('还没有'))
})

test('W1 账页：真判过 0 分要显示 0，不能显示「—」', () => {
  const snap = buildBoutsSnapshot({ now: NOW, todayVerdicts: [{ score: 0 }] })
  assert.equal(snap.books.score, '0')
})

test('W1 账页：招式名与拍数进列表，格式可读', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    beats: [
      { stepId: '先答后看', verdict: 'pass' },
      { stepId: '先答后看', verdict: 'fail' },
    ],
  })
  assert.deepEqual(snap.books.moves, ['先答后看 50% · 2 拍'])
  assert.ok(snap.books.movesNote.length > 0)
})

test('W1 账页：一招都没判过时说「还没有」，不摆空列表当成绩', () => {
  const snap = buildBoutsSnapshot({ now: NOW, beats: [{ stepId: '定位' }] })
  assert.deepEqual(snap.books.moves, [])
  assert.ok(snap.books.movesNote.includes('还没有'))
})

// ---- moveRates：通过率的分母 ----

test('W1 通过率：没判过的拍不进分母', () => {
  const rows = moveRates([
    { stepId: '定位', verdict: 'pass' },
    { stepId: '定位' },
    { stepId: '定位', verdict: '' },
    { stepId: '定位', verdict: null },
  ])
  assert.deepEqual(rows, [['定位', 100, 1]])
})

test('W1 通过率：全没判过就是空，不是 0%', () => {
  assert.deepEqual(moveRates([{ stepId: '定位' }, { stepId: '续接' }]), [])
})

test('W1 通过率：按招式分开算，不混在一起', () => {
  const rows = moveRates([
    { stepId: '定位', verdict: 'pass' },
    { stepId: '续接', verdict: 'fail' },
    { stepId: '续接', verdict: 'fail' },
  ])
  assert.deepEqual(rows, [['续接', 0, 2], ['定位', 100, 1]])
})

test('W1 通过率：拍数多的排前面（先看量大的）', () => {
  const rows = moveRates([
    { stepId: 'a', verdict: 'pass' },
    { stepId: 'b', verdict: 'pass' },
    { stepId: 'b', verdict: 'pass' },
    { stepId: 'b', verdict: 'fail' },
  ])
  assert.equal(rows[0][0], 'b')
  assert.equal(rows[0][2], 3)
})

test('W1 通过率：没有步骤名的拍不参与（不知道是哪一招）', () => {
  assert.deepEqual(moveRates([{ verdict: 'pass' }, { stepId: '  ', verdict: 'pass' }]), [])
})

test('W1 通过率：三种判词写法都算过', () => {
  assert.deepEqual(
    moveRates([
      { stepId: 'm', verdict: 'pass' },
      { stepId: 'm', verdict: 'ok' },
      { stepId: 'm', verdict: '通过' },
    ]),
    [['m', 100, 3]],
  )
})

test('W1 通过率：四舍五入到整百分比', () => {
  const rows = moveRates([
    { stepId: 'm', verdict: 'pass' },
    { stepId: 'm', verdict: 'fail' },
    { stepId: 'm', verdict: 'fail' },
  ])
  assert.deepEqual(rows, [['m', 33, 3]])
})

test('W1 通过率：接受 snake_case 的 step_id（库里两种都见过）', () => {
  assert.deepEqual(moveRates([{ step_id: '定位', verdict: 'pass' }]), [['定位', 100, 1]])
})

test('W1 判词：isPass 只认这几种，别的一概不算过', () => {
  for (const yes of ['pass', 'ok', 'true', '通过', '对', 'PASS', ' ok ']) assert.equal(isPass(yes), true, yes)
  for (const no of ['fail', 'no', '', '  ', null, undefined, '失败']) assert.equal(isPass(no), false, String(no))
})

// ---- todayScore ----

test('W1 判定分：一条都没判是 null（面板写「—」）', () => {
  assert.equal(todayScore([]), null)
  assert.equal(todayScore([{ result: 'pass' }]), null)
})

test('W1 判定分：合计并保留一位小数', () => {
  assert.equal(todayScore([{ score: 8 }, { score: 7.25 }]), 15.3)
})

test('W1 判定分：非数字的分数不算进合计', () => {
  assert.equal(todayScore([{ score: 'x' }, { score: 5 }]), 5)
})

test('W1 判定分：全是 0 分时合计是 0，不是 null', () => {
  assert.equal(todayScore([{ score: 0 }, { score: 0 }]), 0)
})

// ---- 队列 ----

test('W1 队列：四种收局结局各说各的话', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    todayBouts: [
      { id: '1', status: 'closed', closeReason: 'done', protocolId: 'execute', openedAt: NOW },
      { id: '2', status: 'closed', closeReason: 'timeout', protocolId: 'study', openedAt: NOW },
      { id: '3', status: 'closed', closeReason: 'abandoned', protocolId: 'outing', openedAt: NOW },
      { id: '4', status: 'closed', closeReason: 'escalated', protocolId: 'design', openedAt: NOW },
    ],
  })
  assert.ok(snap.queue[0].includes('完成'))
  assert.ok(snap.queue[1].includes('超时'))
  assert.ok(snap.queue[2].includes('收了没做完'))
  assert.ok(snap.queue[3].includes('升级'))
})

test('W1 队列：没收的局标「待开」，不是「完成」', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    todayBouts: [{ id: 'x', status: 'running', protocolId: 'study', openedAt: NOW }],
  })
  assert.ok(snap.queue[0].includes('待开'))
})

test('W1 队列：正在跑的那一局标「进行中」', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    bouts: [{ id: 'x', status: 'running', protocolId: 'study' }],
    todayBouts: [{ id: 'x', status: 'running', protocolId: 'study', openedAt: NOW }],
  })
  assert.ok(snap.queue[0].includes('进行中'))
})

test('W1 队列：每行带开局时刻，空局不编时间', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    todayBouts: [
      { id: 'x', status: 'running', protocolId: 'study', openedAt: NOW },
      { id: 'y', status: 'running', protocolId: 'study', openedAt: 0 },
    ],
  })
  assert.match(snap.queue[0], /^\d{2}:\d{2} /)
  assert.ok(snap.queue[1].startsWith(' '))
})

test('W1 队列：完成局数进说明文字', () => {
  const snap = buildBoutsSnapshot({ now: NOW, todayBouts: [{ id: 'x', status: 'closed', closeReason: 'done', protocolId: 'study' }], doneToday: 1 })
  assert.ok(snap.queueNote.includes('完成 1'))
})

// ---- 协议库 ----

test('W1 协议库：内置 / 试用 / 正式 / 退役 各说各的状态', () => {
  const rows = buildBoutsSnapshot({
    now: NOW,
    protocols: [
      { id: 'a', name: '执行局', steps: [{}] },
      { id: 'b', name: '试用局', steps: [{}, {}], status: 'trial' },
      { id: 'c', name: '正式局', steps: [{}], status: 'official' },
      { id: 'd', name: '退役局', steps: [{}], status: 'retired' },
    ],
  }).protocolRows
  assert.ok(rows[0].includes('内置'))
  assert.ok(rows[1].includes('trial'))
  assert.ok(rows[2].includes('official'))
  assert.ok(rows[3].includes('retired'))
})

test('W1 协议库：没装协议时说没装，不留空标题', () => {
  const snap = buildBoutsSnapshot({ now: NOW, protocols: [] })
  assert.deepEqual(snap.protocolRows, [])
  assert.ok(snap.protocolNote.includes('一个协议都没装上'))
})

test('W1 协议库：步数取 steps 数组长度', () => {
  const rows = buildBoutsSnapshot({ now: NOW, protocols: [{ id: 'x', name: '局', steps: [{}, {}, {}, {}] }] }).protocolRows
  assert.ok(rows[0].includes('4 步'))
})

// ---- 现在页 ----

test('W1 现在页：没局时点「加一局」，不点不存在的「收局」', () => {
  const snap = buildBoutsSnapshot({ now: NOW })
  assert.equal(snap.now.headline, '现在没有局')
  assert.ok(snap.now.ask.includes('加一局'))
  assert.equal(snap.open, false)
  assert.equal(snap.boutId, '')
})

test('W1 现在页：别的进程开的局要说清楚，不能装作本进程在跑', () => {
  const snap = buildBoutsSnapshot({ now: NOW, bouts: [{ id: 'b', status: 'running', protocolId: 'study' }] })
  assert.ok(snap.now.headline.includes('不是这个进程'))
  assert.equal(snap.open, true)
  assert.equal(snap.boutId, 'b')
})

test('W1 现在页：限时到点显示 0:00，进度 0', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    live: { boutId: 'b', stepId: 's', ask: '？', limitSec: 60, askedAt: NOW - 60_000 },
  })
  assert.equal(snap.now.left, '0:00')
  assert.equal(snap.now.percent, 0)
})

test('W1 现在页：没有限时的步骤写「—」，不假装有时间', () => {
  const snap = buildBoutsSnapshot({ now: NOW, live: { boutId: 'b', stepId: 's', ask: '？' } })
  assert.equal(snap.now.left, '—')
  assert.equal(snap.now.percent, 0)
})

test('W1 现在页：进度百分比就停在 100', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    live: { boutId: 'b', stepId: 's', ask: '？', limitSec: 600, askedAt: NOW + 600_000 },
  })
  assert.equal(snap.now.percent, 100)
})

test('W1 现在页：混乱次数进提示，0 次不提示', () => {
  const messy = buildBoutsSnapshot({ now: NOW, live: { ...LIVE, chaosStreak: 2 } })
  const clean = buildBoutsSnapshot({ now: NOW, live: LIVE })
  assert.ok(messy.now.hint.includes('2'))
  assert.equal(clean.now.hint, '')
})

// ---- 账：分钟数 ----

test('W1 账：没算上分钟时说「还没记上」，不写 0 分', () => {
  const snap = buildBoutsSnapshot({ now: NOW })
  assert.equal(snap.books.minutes, '0')
  assert.ok(snap.books.minutesCaption.includes('还没记上'))
})

test('W1 账：只算收了且有开收时刻的局', () => {
  assert.deepEqual(
    minutesByProtocol([
      { protocolId: 'a', openedAt: NOW, closedAt: NOW + 600_000 },
      { protocolId: 'b', openedAt: 0, closedAt: NOW },
      { protocolId: 'c', openedAt: NOW },
    ]),
    [['a', 10]],
  )
})

test('W1 账：分钟按比例排序，多的在前', () => {
  const rows = minutesByProtocol([
    { protocolId: 'short', openedAt: NOW, closedAt: NOW + 300_000 },
    { protocolId: 'long', openedAt: NOW, closedAt: NOW + 3_600_000 },
  ])
  assert.deepEqual(rows, [['long', 60], ['short', 5]])
})

test('W1 账：空局到 3 才标 bad', () => {
  assert.equal(buildBoutsSnapshot({ now: NOW, emptyToday: 2 }).books.emptyTone, 'muted')
  assert.equal(buildBoutsSnapshot({ now: NOW, emptyToday: 3 }).books.emptyTone, 'bad')
})

test('W1 账：敷衍与升级各自计数，别混成一个数', () => {
  const snap = buildBoutsSnapshot({ now: NOW, slackBeats: 4, escalations: 1 })
  assert.equal(snap.books.slack, '4')
  assert.equal(snap.books.escalations, '1')
})

// ---- 视图契约 ----

test('W1 视图：四页顶层都是 stack，宿主渲染器只认这个', () => {
  for (const view of [boutsNowView, boutsQueueView, boutsProtocolsView, boutsBooksView]) {
    assert.equal(view({}, BARE).type, 'stack')
  }
})

test('W1 视图：没有 form 能力时不出现任何提交动作', () => {
  for (const view of [boutsNowView, boutsQueueView, boutsProtocolsView, boutsBooksView]) {
    const json = JSON.stringify(view({}, BARE))
    assert.ok(!json.includes('bouts.open'), json)
    assert.ok(!json.includes('bouts.answer'), json)
  }
})

test('W1 视图：刷新按钮四页都在', () => {
  for (const view of [boutsNowView, boutsQueueView, boutsProtocolsView, boutsBooksView]) {
    assert.ok(JSON.stringify(view({}, BARE)).includes('bouts.refresh'))
  }
})

test('W1 视图：收局按钮只在现在页', () => {
  assert.ok(JSON.stringify(boutsNowView({}, BARE)).includes('bouts.close'))
  assert.ok(!JSON.stringify(boutsQueueView({}, BARE)).includes('bouts.close'))
  assert.ok(!JSON.stringify(boutsBooksView({}, BARE)).includes('bouts.close'))
})

test('W1 视图：饼图式的百分比只出现在现在页', () => {
  assert.ok(JSON.stringify(boutsNowView({}, BARE)).includes('data.now.percent'))
  assert.ok(!JSON.stringify(boutsQueueView({}, BARE)).includes('data.now.percent'))
})

// 磁贴摘要（W3）：字段名是跨语言契约，Kotlin 的 BoutsTileService.readStatus() 按这个名字取。
// 改名不改 Kotlin 不会报错，磁贴只会静默退回显示 App 名——所以这层必须钉住。
test('W3 磁贴：今日拍数按拍算，running 只看有没有在跑的局', () => {
  const status = boutsTileStatus({
    now: NOW,
    beats: [{ id: 'beat:1' }, { id: 'beat:2' }, { id: 'beat:3' }],
    bouts: [{ id: 'bout:1', status: 'closed' }, { id: 'bout:2', status: 'running' }],
  })
  assert.equal(status.at, NOW)
  assert.equal(status.beats, 3)
  assert.equal(status.running, true)
})

test('W3 常驻通知：局名、当前拍的问题、这一拍的截止时刻', () => {
  const status = boutsTileStatus({
    now: NOW,
    bouts: [{ id: 'bout:2', status: 'running', protocolId: 'study' }],
    protocols: [{ id: 'study', title: '学习局' }],
    live: { boutId: 'bout:2', ask: '这一步要交什么', askedAt: NOW - 60_000, limitSec: 300 },
  })
  assert.equal(status.bout, '学习局')
  assert.equal(status.ask, '这一步要交什么')
  // 截止 = 问的时刻 + 这一拍的限时
  assert.equal(status.until, NOW - 60_000 + 300_000)
})

test('W3 常驻通知：进程里没有这局时只报局名，不编问题和截止', () => {
  const status = boutsTileStatus({
    now: NOW,
    bouts: [{ id: 'bout:2', status: 'running', protocolId: 'study' }],
    protocols: [{ id: 'study', title: '学习局' }],
    live: null,
  })
  assert.equal(status.bout, '学习局')
  assert.equal(status.ask, '')
  assert.equal(status.until, 0)
})

test('W3 常驻通知：已经过期的截止时刻不往系统倒计时里塞', () => {
  const status = boutsTileStatus({
    now: NOW,
    bouts: [{ id: 'bout:2', status: 'running', protocolId: 'study' }],
    live: { boutId: 'bout:2', ask: '交', askedAt: NOW - 400_000, limitSec: 300 },
  })
  assert.equal(status.until, 0)
})

test('W3 常驻通知：live 是别的局时不算到这一局头上', () => {
  const status = boutsTileStatus({
    now: NOW,
    bouts: [{ id: 'bout:2', status: 'running' }],
    live: { boutId: 'bout:9', ask: '别的局的问', askedAt: NOW - 1000, limitSec: 300 },
  })
  assert.equal(status.ask, '')
  assert.equal(status.until, 0)
})

test('W3 磁贴：没有在跑的局就是 false，没拍就是 0', () => {
  const status = boutsTileStatus({ now: NOW, beats: [], bouts: [{ id: 'bout:1', status: 'closed' }] })
  assert.equal(status.beats, 0)
  assert.equal(status.running, false)
  assert.equal(status.bout, '')
  assert.equal(status.ask, '')
})

test('W3 磁贴：什么数据都没有时也不抛，字段形状不变', () => {
  const status = boutsTileStatus()
  assert.deepEqual(Object.keys(status).sort(), ['ask', 'at', 'beats', 'bout', 'running', 'until'])
  assert.equal(status.beats, 0)
  assert.equal(status.running, false)
})
