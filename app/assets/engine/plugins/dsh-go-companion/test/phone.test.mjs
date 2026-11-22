import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanPhone, funToday, isFun, worthObserving } from '../lib/phone.js'

test('娱乐类：常见包名，未知包名不算', () => {
  assert.equal(isFun('tv.danmaku.bili'), true)
  assert.equal(isFun('com.ss.android.ugc.aweme'), true)
  assert.equal(isFun('com.tencent.mm'), false)
  assert.equal(isFun(''), false)
  assert.equal(isFun(undefined), false)
})

test('今天的娱乐分钟数只加娱乐类', () => {
  const phone = cleanPhone({
    app: 'tv.danmaku.bili',
    minutes: 20,
    fun: true,
    today: [
      { app: 'tv.danmaku.bili', minutes: 20, fun: true },
      { app: 'com.tencent.mm', minutes: 30 },
    ],
  })
  assert.equal(funToday(phone), 20)
})

test('观察球的门槛：当前娱乐停留够久，或今天娱乐累计够久', () => {
  assert.equal(worthObserving(cleanPhone({ app: 'tv.danmaku.bili', minutes: 9, fun: true })), false)
  assert.equal(worthObserving(cleanPhone({ app: 'tv.danmaku.bili', minutes: 10, fun: true })), true)
  assert.equal(worthObserving(cleanPhone({ app: 'com.tencent.mm', minutes: 3, today: [{ app: 'tv.danmaku.bili', minutes: 60 }] })), true)
})

test('切换序列按包名收：脏的丢掉，超窗口的截到 15 分钟', () => {
  const phone = cleanPhone({
    app: 'com.study',
    switches: {
      in90s: 3,
      apps90s: ['抖音', 'B站'],
      in15m: 9,
      distinct15m: 4,
      recent: [
        { pkg: 'com.douyin', agoMs: 20_000 },
        { pkg: '抖音', agoMs: 10_000 },
        { pkg: '', agoMs: 0 },
        { pkg: 'com.bili', agoMs: 99 * 60_000 },
        'nope',
        { pkg: 'com.study', agoMs: -5 },
      ],
    },
  })
  assert.deepEqual(phone.switches.recent, [
    { app: 'com.douyin', agoMs: 20_000 },
    { app: 'com.bili', agoMs: 15 * 60_000 },
    { app: 'com.study', agoMs: 0 },
  ])
  // 老字段不许被弄坏：给模型看的名字还是名字。
  assert.deepEqual(phone.switches.apps90s, ['抖音', 'B站'])
  assert.equal(phone.switches.in15m, 9)
})

test('手机没报序列时给空表，不是 null', () => {
  const phone = cleanPhone({ app: 'com.study', switches: { in90s: 1 } })
  assert.deepEqual(phone.switches.recent, [])
  assert.equal(cleanPhone({ app: 'com.study' }).switches, null, '整块没报就是 null：和手机没连上是两件事')
})
