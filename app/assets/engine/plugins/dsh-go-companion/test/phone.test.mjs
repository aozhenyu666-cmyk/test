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
