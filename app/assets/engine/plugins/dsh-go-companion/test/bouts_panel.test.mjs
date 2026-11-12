// 第八轮 W1：局面板的数据与视图。
//
// 面板最容易出的错不是崩，是「显示得挺像那么回事，但数字是编的」：
// 倒计时按假限时走、进度百分比超过 100、没有局的时候还摆一个「进行中」。
// 所以这里只测两件事：空的时候老实说空，有局的时候每个数字都对得上来源。

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { buildBoutsSnapshot, minutesByProtocol } from '../lib/bouts_panel.js'
import { boutsView } from '../lib/bouts_view.js'

/** 视图里所有 data.* 绑定的键，用来对「数据里到底有没有这个字段」。 */
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

const NOW = Date.parse('2026-11-12T09:30:00+08:00')

test('W1 面板：没有局的时候老实说没有局，不摆假倒计时', () => {
  const snap = buildBoutsSnapshot({ now: NOW, bouts: [], todayBouts: [], protocols: [] })
  assert.equal(snap.now.headline, '现在没有局')
  assert.equal(snap.now.left, '—')
  assert.equal(snap.now.percent, 0)
  assert.equal(snap.queue.length, 0)
  assert.equal(snap.queueNote, '今天还没有局。')
})

test('W1 面板：正在跑的那一局，倒计时与百分比都对得上限时', () => {
  const live = {
    boutId: 'bout:1', stepId: '进展', ask: '做到哪了？', limitSec: 600,
    askedAt: NOW - 120_000, writeMode: false, chaosStreak: 0,
  }
  const snap = buildBoutsSnapshot({ now: NOW, live, bouts: [{ id: 'bout:1', status: 'running', protocolId: 'execute' }] })
  assert.equal(snap.now.ask, '做到哪了？')
  assert.equal(snap.now.left, '8:00')
  assert.equal(snap.now.percent, 80)
  assert.equal(snap.open, true)
  assert.equal(snap.boutId, 'bout:1')
})

test('W1 面板：限时早过了也不会出现负倒计时或超过 100 的进度', () => {
  const live = { boutId: 'b', stepId: 's', ask: '？', limitSec: 60, askedAt: NOW - 600_000 }
  const snap = buildBoutsSnapshot({ now: NOW, live })
  assert.equal(snap.now.left, '0:00')
  assert.equal(snap.now.percent, 0)
})

test('W1 面板：写模式下说的是写模式，不说「进行中」', () => {
  const live = { boutId: 'b', stepId: 's', ask: '？', limitSec: 60, askedAt: NOW, writeMode: true, chaosStreak: 2 }
  const snap = buildBoutsSnapshot({ now: NOW, live })
  assert.equal(snap.now.headline, '写模式：这一局只收手写照片')
  assert.equal(snap.now.tone, 'warn')
  assert.ok(snap.now.hint.includes('2'))
})

test('W1 面板：队列按状态分完成 / 进行中 / 待开', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    bouts: [{ id: 'a', status: 'running', protocolId: 'execute' }],
    todayBouts: [
      { id: 'a', status: 'running', protocolId: 'execute', openedAt: NOW - 3_600_000 },
      { id: 'b', status: 'closed', closeReason: 'done', protocolId: 'study', openedAt: NOW - 7_200_000 },
      { id: 'c', status: 'closed', closeReason: 'abandoned', protocolId: 'outing', openedAt: NOW - 10_800_000 },
    ],
    doneToday: 1,
  })
  assert.equal(snap.queue.length, 3)
  assert.ok(snap.queue[0].includes('进行中'))
  assert.ok(snap.queue[1].includes('完成'))
  // 收了没做完的不能写成「完成」，否则账上的空局和完成局会混成一堆。
  assert.ok(snap.queue[2].includes('收了没做完'))
  assert.ok(snap.queueNote.includes('3'))
})

test('W1 账：各局型分钟数只算收了的局，按多到少', () => {
  const bouts = [
    { protocolId: 'execute', openedAt: NOW, closedAt: NOW + 60 * 60_000 },
    { protocolId: 'study', openedAt: NOW, closedAt: NOW + 30 * 60_000 },
    { protocolId: 'execute', openedAt: NOW, closedAt: NOW + 30 * 60_000 },
    { protocolId: 'rest', openedAt: NOW, closedAt: 0 }, // 没收的局不计分钟
  ]
  assert.deepEqual(minutesByProtocol(bouts), [['execute', 90], ['study', 30]])
})

test('W1 账：空局到 3 个就标成 bad（负反馈的起点）', () => {
  const snap = buildBoutsSnapshot({ now: NOW, emptyToday: 3 })
  assert.equal(snap.books.empty, '3')
  assert.equal(snap.books.emptyTone, 'bad')
})

test('W1 协议库：内置协议显示步数与来源', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    protocols: [{ id: 'execute', name: '执行局', steps: [{}, {}, {}] }],
  })
  assert.equal(snap.protocolRows.length, 1)
  assert.ok(snap.protocolRows[0].includes('执行局'))
  assert.ok(snap.protocolRows[0].includes('3 步'))
})

test('W1 视图：所有 data.* 绑定都能在快照里取到值键', () => {
  const snap = buildBoutsSnapshot({
    now: NOW,
    live: { boutId: 'b', stepId: 's', ask: '问？', limitSec: 60, askedAt: NOW, writeMode: false, chaosStreak: 0 },
    protocols: [{ id: 'execute', name: '执行局', steps: [{}] }],
  })
  const keys = [...bindingsOf(boutsView({}, { components: ['form'] }))]
  // 拆成四页之后，单页只绑自己那几个字段；四页合起来才是原来那张长卷。
  assert.ok(keys.length > 5, '现在页应当绑了本拍要用的几个字段')
  for (const key of keys) {
    if (key.startsWith('data.form.')) continue // 表单是动作的入参，不在快照里
    const value = valueAt(snap, key)
    assert.notEqual(value, undefined, key + ' 在快照里没有对应的值')
  }
})

test('W1 视图：没有 form 能力时不渲染表单，其余照常', () => {
  const withForm = JSON.stringify(boutsView({}, { components: ['form'] }))
  const without = JSON.stringify(boutsView({}, { components: [] }))
  assert.ok(withForm.includes('bouts.answer'))
  assert.ok(!without.includes('bouts.answer'))
  assert.ok(!without.includes('bouts.open'))
  assert.ok(without.includes('data.now.ask'))
})
