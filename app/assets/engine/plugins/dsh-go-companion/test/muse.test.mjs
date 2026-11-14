// 第六轮 C · 暗思考
//
// 这里守的是任务书 §2.2 的四条硬边界，和硬验收 ④：
//   「暗思考一整天无人交互仍能留痕，且这一天它不产生任何新消息」。
// 所以断言的重心不是"想得好不好"，而是：
//   ① 它写进账本的那条不带任何能发出去的话；
//   ② 它不参与发球判定（summarize 的计数里没有它）；
//   ③ 没有客观变化就不产出、不写空账；
//   ④ 屏幕上的字一个都不进来（只吃白名单字段）。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  MUSE_MAX_PER_DAY, MUSE_MIN_GAP_MS,
  museChanges, museDigest, museDue, museSignal, museStatus, museSweep,
} from '../lib/muse.js'
import { summarize } from '../lib/ledger.js'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = readFileSync(join(HERE, '..', 'lib', 'muse.js'), 'utf8')

const NOW = new Date(2026, 10, 10, 14, 30, 0).getTime()

/** 一份合法的手机存量快照（字段与 phone.js cleanPhone 的返回一致）。 */
function phone(extra = {}) {
  return {
    app: 'com.example.reader',
    label: '阅读',
    minutes: 40,
    fun: false,
    screenOn: true,
    charging: false,
    local: '14:30',
    today: [{ app: 'com.example.reader', label: '阅读', minutes: 40, fun: false, opens: 3 }],
    switches: { m15: 4, h1: 12 },
    ...extra,
  }
}

/** 一条已有的 muse 记录。 */
function entry(at, signal) {
  return { v: 1, state: 'muse', at, why: ['first'], summary: 's', next: 'n', signal }
}

// ---------------------------------------------------------------- 边界一：只整理，不开口

test('C1 写进账本的那条不带任何能发出去的话', () => {
  const e = museSweep([], phone(), NOW, 'Asia/Shanghai')
  assert.ok(e, '第一次应当产出')
  // 只允许这几个键：多一个都可能被下游当成"要发的话"。
  assert.deepEqual(Object.keys(e).sort(), ['at', 'next', 'signal', 'state', 'summary', 'why'])
  // 话术类字段一律不存在。
  for (const k of ['say', 'speak', 'text', 'message', 'prompt', 'topic', 'topicId', 'force']) {
    assert.equal(Object.hasOwn(e, k), false, '不该有字段：' + k)
  }
})

test('C2 它不参与发球判定（summarize 不数 muse）', () => {
  const dayStart = new Date(2026, 10, 10, 0, 0, 0).getTime()
  const entries = [entry(NOW, null), entry(NOW + 1, null), { v: 1, state: 'generated', at: NOW, topicId: 't1' }]
  const s = summarize(entries, dayStart)
  assert.equal(s.counts.muse, undefined, 'muse 不该进计数白名单')
  assert.equal(s.counts.generated, 1, '真实的发球照常计')
  // 也不该把 muse 记录当成"最近聊过的话题"，否则会静音真空。
  assert.equal(s.lastTopicId, 't1')
})

test('C3 源码里没有把话说出去的口子', () => {
  for (const bad of ['sendMessage', 'speak(', 'chat(', 'deliver', 'agent.send', 'systemPrompt']) {
    assert.equal(SRC.includes(bad), false, 'lib/muse.js 里出现了出口：' + bad)
  }
})

// ---------------------------------------------------------------- 边界二：没有客观变化就不产出

test('C4 手机什么都没报 → 不产出（不写空账）', () => {
  assert.equal(museSignal(null, NOW, 'Asia/Shanghai'), null)
  assert.equal(museSignal({}, NOW, 'Asia/Shanghai'), null)
  assert.equal(museSignal({ text: '屏幕上的字' }, NOW, 'Asia/Shanghai'), null)
  assert.equal(museSweep([], null, NOW, 'Asia/Shanghai'), null)
})

test('C5 两次快照一模一样 → 第二次不产出', () => {
  const prev = museSignal(phone(), NOW, 'Asia/Shanghai')
  assert.deepEqual(museChanges(prev, museSignal(phone(), NOW, 'Asia/Shanghai')), [])
  const first = museSweep([], phone(), NOW, 'Asia/Shanghai')
  const again = museSweep([entry(NOW, first.signal)], phone(), NOW + MUSE_MIN_GAP_MS + 1, 'Asia/Shanghai')
  assert.equal(again, null, '没变化就不该再写一条')
})

test('C6 五条客观变化都能单独触发', () => {
  const base = museSignal(phone(), NOW, 'Asia/Shanghai')
  assert.deepEqual(museChanges(base, museSignal(phone({ app: 'com.example.chat' }), NOW, 'Asia/Shanghai')), ['app'])
  assert.deepEqual(museChanges(base, museSignal(phone({ screenOn: false }), NOW, 'Asia/Shanghai')), ['screen'])
  assert.deepEqual(museChanges(base, museSignal(phone({ switches: { m15: 9, h1: 12 } }), NOW, 'Asia/Shanghai')), ['switches'])
  assert.deepEqual(museChanges(base, museSignal(phone({ local: '15:01' }), NOW, 'Asia/Shanghai')), ['hour'])
  const longer = museSignal(phone({ today: [{ app: 'com.example.reader', minutes: 47 }] }), NOW, 'Asia/Shanghai')
  assert.deepEqual(museChanges(base, longer), ['minutes'])
  // 差一档（4 分钟）不算变化：门槛是 5 分钟。
  const tiny = museSignal(phone({ today: [{ app: 'com.example.reader', minutes: 44 }] }), NOW, 'Asia/Shanghai')
  assert.deepEqual(museChanges(base, tiny), [])
})

test('C7 第一次没有前一次 → 起点本身可查', () => {
  assert.deepEqual(museChanges(null, museSignal(phone(), NOW, 'Asia/Shanghai')), ['first'])
})

// ---------------------------------------------------------------- 边界三：隐私（只吃白名单）

test('C8 屏幕上的字一个都不进来', () => {
  const dirty = phone({
    text: '微信聊天记录',
    title: '某个页面标题',
    ocr: '识别出来的字',
    content: '一段正文',
    notification: '某人发来一条消息',
  })
  const sig = museSignal(dirty, NOW, 'Asia/Shanghai')
  const flat = JSON.stringify(sig)
  for (const bad of ['微信聊天记录', '某个页面标题', '识别出来的字', '一段正文', '某人发来一条消息']) {
    assert.equal(flat.includes(bad), false, '敏感内容漏进了快照：' + bad)
  }
  // 白名单字段就这些，不多不少。
  assert.deepEqual(Object.keys(sig).sort(), ['app', 'at', 'charging', 'fun', 'local', 'minutes', 'screenOn', 'switches', 'today'])
})

test('C9 单个 App 的时长有上限，不会记成天文数字', () => {
  const sig = museSignal(phone({ minutes: 999999, today: [{ app: 'a', minutes: 999999 }] }), NOW, 'Asia/Shanghai')
  assert.equal(sig.minutes, 1440)
  assert.equal(sig.today[0].minutes, 1440)
  // 负数和脏值归零。
  assert.equal(museSignal(phone({ minutes: -5 }), NOW, 'Asia/Shanghai').minutes, 0)
})

// ---------------------------------------------------------------- 边界四：节奏与上限

test('C10 45 分钟内不重复跑', () => {
  const first = museSweep([], phone(), NOW, 'Asia/Shanghai')
  const soon = museDue([entry(NOW, first.signal)], NOW + 60_000, 'Asia/Shanghai')
  assert.equal(soon.due, false)
  assert.equal(soon.why, 'too-soon')
  const ok = museDue([entry(NOW, first.signal)], NOW + MUSE_MIN_GAP_MS, 'Asia/Shanghai')
  assert.equal(ok.due, true)
})

test('C11 一天最多 12 次，超了就不再跑', () => {
  const dayStart = new Date(2026, 10, 10, 0, 0, 0).getTime()
  const entries = []
  for (let i = 0; i < MUSE_MAX_PER_DAY; i += 1) entries.push(entry(dayStart + i * MUSE_MIN_GAP_MS, null))
  const gate = museDue(entries, NOW, 'Asia/Shanghai')
  assert.equal(gate.due, false)
  assert.equal(gate.why, 'daily-cap')
  // 昨天的次数不算今天的。
  const yesterday = entries.map((e) => ({ ...e, at: e.at - 24 * 3_600_000 }))
  assert.equal(museDue(yesterday, NOW, 'Asia/Shanghai').due, true)
})

test('C12 状态面板只数今天，并带最近一条', () => {
  const dayStart = new Date(2026, 10, 10, 0, 0, 0).getTime()
  const entries = [entry(dayStart - 3_600_000, null), entry(dayStart + 60_000, null), entry(NOW - 60_000, null)]
  const st = museStatus(entries, NOW)
  assert.equal(st.today, 2)
  assert.equal(st.max, MUSE_MAX_PER_DAY)
  assert.equal(st.minGapMs, MUSE_MIN_GAP_MS)
  assert.ok(st.last)
  assert.equal(st.last.at, NOW - 60_000, '最近一条取时间最大的')
  assert.equal(museStatus([], NOW).last, null)
})

// ---------------------------------------------------------------- 整理出来的东西是纯陈述

test('C13 整理结果只陈述客观量，不评价人', () => {
  const prev = museSignal(phone(), NOW, 'Asia/Shanghai')
  const next = museSignal(phone({ app: 'com.example.chat', switches: { m15: 9, h1: 20 }, local: '15:01' }), NOW + 3_600_000, 'Asia/Shanghai')
  const d = museDigest(next, museChanges(prev, next), { prev })
  assert.ok(d.summary.includes('com.example.chat'))
  assert.ok(d.summary.includes('15:01'))
  // 不出现"懒/又/总是/堕落"这类评价词。
  for (const bad of ['懒', '又', '总是', '堕落', '失败', '废物']) {
    assert.equal(d.summary.includes(bad) + d.next.includes(bad), 0, '整理里出现了评价词：' + bad)
  }
  assert.ok(d.next.length > 0)
})

test('C14 没有可提的变化时也给一句老实话，不编推断', () => {
  const sig = museSignal(phone(), NOW, 'Asia/Shanghai')
  const d = museDigest(sig, ['first'], { prev: null })
  assert.ok(d.summary.length > 0)
  assert.equal(typeof d.next, 'string')
  assert.ok(d.next.length > 0)
})

test('C15 一整天无人交互：照样留下可查的痕迹，且不产生任何新消息', () => {
  // 模拟一天 07:00→23:45 手机每 15 分钟来问一次 /plan，人在但一句话没说。上限是按天的，所以不能跨午夜。
  let entries = []
  let phoneState = phone({ local: '07:00', minutes: 0, today: [], switches: { m15: 0, h1: 0 } })
  let wrote = 0
  for (let i = 0; i <= 67; i += 1) {
    const at = new Date(2026, 10, 10, 7, 0, 0).getTime() + i * 15 * 60_000
    const hh = String(new Date(at).getHours()).padStart(2, '0')
    const mm = String(new Date(at).getMinutes()).padStart(2, '0')
    // 每小时干一小时的活，切屏偶尔变密。
    phoneState = phone({
      local: hh + ':' + mm,
      minutes: (i * 3) % 120,
      today: [{ app: 'com.example.reader', minutes: (i * 3) % 120 }],
      switches: { m15: i % 7, h1: (i * 2) % 20 },
    })
    const e = museSweep(entries, phoneState, at, 'Asia/Shanghai')
    if (e) {
      wrote += 1
      entries = entries.concat([e])
    }
  }
  assert.ok(wrote > 0, '一整天无人交互也该留下痕迹')
  assert.ok(wrote <= MUSE_MAX_PER_DAY, '不该超过一天 12 次：' + wrote)
  // 没有任何一条带发球字段。
  for (const e of entries) {
    assert.equal(e.state, 'muse')
    assert.equal(Object.hasOwn(e, 'topicId'), false)
    assert.equal(Object.hasOwn(e, 'say'), false)
  }
  // 全都查得到。
  const st = museStatus(entries, new Date(2026, 10, 10, 23, 59, 0).getTime())
  assert.equal(st.today, wrote)
})
