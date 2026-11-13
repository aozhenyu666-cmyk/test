// 第七轮 P0-2 · 时间基准固定北京时间
//
// 守两件事：
//   ① 提示词里不许再出现 UTC 时刻（模型看到 UTC、用户看北京时间，差 8 小时，这是「说错时间」的根）。
//     做法照 tracks.test.mjs A1：直接扫源码，不靠人肉 review。
//   ② tz.js 的 hm/md/ymd 给的是「目标时区的墙上时间」，不是 UTC。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DEFAULT_TZ, hm, md, minuteOfDay, ymd } from '../lib/tz.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const LIB = join(HERE, '..', 'lib')

/** lib/ 下每个 .js 的源码（文件名 → 内容）。 */
function libSources() {
  const out = new Map()
  for (const name of readdirSync(LIB)) {
    if (!name.endsWith('.js')) continue
    out.set(name, readFileSync(join(LIB, name), 'utf8'))
  }
  return out
}

// ---------------------------------------------------------------- ① 源码里不许再拼 UTC

test('B1 lib/ 里没有 toISOString().slice(11 这种时刻切片', () => {
  for (const [name, src] of libSources()) {
    assert.equal(src.includes('toISOString().slice(11'), false, 'lib/' + name + ' 还在用 toISOString 切时刻')
  }
})

test('B2 lib/ 里没有带字面的 UTC 后缀', () => {
  for (const [name, src] of libSources()) {
    assert.equal(src.includes("' UTC'"), false, 'lib/' + name + ' 还带着 UTC 字样')
  }
})

// ---------------------------------------------------------------- ② 墙上时间算对

// 2026-11-11T13:00Z：北京 21:00、纽约 08:00、UTC 13:00。三个都不一样，拿它当探针。
const AT = Date.UTC(2026, 10, 11, 13, 0, 0)

test('B3 hm 默认给北京时间，不是 UTC', () => {
  assert.equal(DEFAULT_TZ, 'Asia/Shanghai')
  assert.equal(hm(AT), '21:00')
  assert.notEqual(hm(AT), '13:00')
})

test('B4 hm 按传入时区给墙上时间（同一时刻三个时区三个答案）', () => {
  assert.equal(hm(AT, 'Asia/Shanghai'), '21:00')
  assert.equal(hm(AT, 'America/New_York'), '08:00')
  assert.equal(hm(AT, 'UTC'), '13:00')
})

test('B5 md / ymd 也跟着时区走，跨零点不会串天', () => {
  // 北京时间 11-12 01:30，此时 UTC 还是 11-11 17:30——按 UTC 切就会写成前一天。
  const late = Date.UTC(2026, 10, 11, 17, 30)
  assert.equal(ymd(late), '2026-11-12')
  assert.equal(md(late), '11-12')
  assert.equal(hm(late), '01:30')
  assert.equal(ymd(late, 'UTC'), '2026-11-11')
})

test('B6 非法时区回落到北京时间，不抛', () => {
  assert.equal(hm(AT, 'Not/AZone'), '21:00')
})

test('B7 minuteOfDay 与 hm 同源，深夜锁窗口不会偏', () => {
  // 北京时间 02:00（安静时段内）；UTC 是前一天 18:00。用 UTC 判会当白天放行。
  const quiet = Date.UTC(2026, 10, 11, 18, 0)
  assert.equal(hm(quiet), '02:00')
  assert.equal(minuteOfDay(quiet, DEFAULT_TZ), 120)
  assert.equal(minuteOfDay(quiet, 'UTC'), 18 * 60)
})
