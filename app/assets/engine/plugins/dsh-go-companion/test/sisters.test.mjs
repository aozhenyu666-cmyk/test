// R4-5 姐妹界面：卡片头部与今日审计面板的纯函数。
import assert from 'node:assert/strict'
import test from 'node:test'

import { COMPANION_LIFE, DEFAULT_NAMES, STRATEGIST_LIFE } from '../lib/roles.js'
import { SISTERS, auditOf, cardsOf, personaOf, sisterLine } from '../lib/sisters.js'

test('卡片：两张，妹妹在前姐姐在后，名字默认取组合里的默认名', () => {
  const cards = cardsOf()
  assert.equal(cards.length, 2)
  assert.deepEqual(cards.map((c) => c.role), SISTERS)
  assert.deepEqual(cards.map((c) => c.role), ['companion', 'strategist'])
  assert.equal(cards[0].name, DEFAULT_NAMES.companion)
  assert.equal(cards[1].name, DEFAULT_NAMES.strategist)
})

test('卡片：/config 改了名字，卡片跟着改，且不会串号', () => {
  const cards = cardsOf({ companion: '小鱼丸', strategist: '鲸姐' })
  assert.equal(cards[0].name, '小鱼丸')
  assert.equal(cards[1].name, '鲸姐')
  // 只改一个，另一个回落到默认名
  const half = cardsOf({ companion: '小鱼丸' })
  assert.equal(half[0].name, '小鱼丸')
  assert.equal(half[1].name, DEFAULT_NAMES.strategist)
})

test('卡片：名字超长截到 20 字，避免撑坏卡面', () => {
  const cards = cardsOf({ companion: '鱼'.repeat(50) })
  assert.equal(cards[0].name.length, 20)
})

test('定位：两人各有一句，墙不是角色卡', () => {
  assert.ok(sisterLine('companion').includes('陪你玩'))
  assert.ok(sisterLine('strategist').includes('出主意'))
  assert.equal(sisterLine('wall'), '')
  assert.ok(!SISTERS.includes('wall'))
})

test('人设：去掉写给模型看的抬头，两人取各自那一份', () => {
  const yuwan = personaOf('companion')
  const jinglan = personaOf('strategist')
  assert.ok(yuwan && jinglan)
  assert.ok(!yuwan.includes('你的设定'))
  assert.ok(!jinglan.includes('你的设定'))
  // 各自的设定必须来自各自的常量，不能两份一样
  assert.notEqual(yuwan, jinglan)
  assert.ok(COMPANION_LIFE.includes(yuwan.slice(0, 8)))
  assert.ok(STRATEGIST_LIFE.includes(jinglan.slice(0, 8)))
  assert.equal(personaOf('wall'), personaOf('companion'))
})

test('审计：今天立了几件、有证据完成几件', () => {
  const memory = { days: { '2026-11-09': { promised: 4, done: 1 } } }
  const audit = auditOf({ memory, today: '2026-11-09' })
  assert.equal(audit.promised, 4)
  assert.equal(audit.done, 1)
  assert.equal(audit.day, '2026-11-09')
})

test('审计：近 14 天完成率按窗口算，窗口外的不计', () => {
  const memory = {
    days: {
      '2026-11-09': { promised: 3, done: 3 }, // 今天
      '2026-11-08': { promised: 2, done: 1 }, // 昨天
      '2026-10-20': { promised: 9, done: 9 }, // 20 天前，窗口外
    },
  }
  const audit = auditOf({ memory, today: '2026-11-09' })
  assert.equal(audit.rateDays, 14)
  assert.equal(audit.rateDone, 4)
  assert.equal(audit.rateTotal, 5)
  assert.equal(audit.ratePct, 80)
})

test('审计：窗口边界——第 14 天算进去，第 15 天不算', () => {
  // 2026-11-09 往前数 13 天 = 2026-10-27 是窗口第一天
  const inside = auditOf({ memory: { days: { '2026-10-27': { promised: 2, done: 2 } } }, today: '2026-11-09' })
  assert.equal(inside.rateTotal, 2)
  const outside = auditOf({ memory: { days: { '2026-10-26': { promised: 2, done: 2 } } }, today: '2026-11-09' })
  assert.equal(outside.rateTotal, 0)
})

test('审计：没有记忆、没有审计文件时全是 0，不抛', () => {
  const empty = auditOf({})
  assert.equal(empty.promised, 0)
  assert.equal(empty.done, 0)
  assert.equal(empty.rateTotal, 0)
  assert.equal(empty.ratePct, 0)
  assert.equal(empty.text, '')
  // 今天没登记，但历史有 → 今天两项为 0，完成率仍按历史算
  const onlyHistory = auditOf({ memory: { days: { '2026-11-08': { promised: 2, done: 1 } } }, today: '2026-11-09' })
  assert.equal(onlyHistory.promised, 0)
  assert.equal(onlyHistory.done, 0)
  assert.equal(onlyHistory.ratePct, 50)
})

test('审计：脏数据不炸，负数和小数都收干净', () => {
  const audit = auditOf({
    memory: { days: { '2026-11-09': { promised: -3, done: 'abc' } } },
    today: '2026-11-09',
  })
  assert.equal(audit.promised, 0)
  assert.equal(audit.done, 0)
  const rounded = auditOf({ memory: { days: { '2026-11-09': { promised: 2.6, done: 1.4 } } }, today: '2026-11-09' })
  assert.equal(rounded.promised, 3)
  assert.equal(rounded.done, 1)
})

test('审计：墙的原话原样带出来，超长截断', () => {
  const audit = auditOf({ auditText: '  14:05 验收不过：还缺 120 字节  ', today: '2026-11-09' })
  assert.equal(audit.text, '14:05 验收不过：还缺 120 字节')
  const long = auditOf({ auditText: 'x'.repeat(5000), today: '2026-11-09' })
  assert.equal(long.text.length, 2000)
})
