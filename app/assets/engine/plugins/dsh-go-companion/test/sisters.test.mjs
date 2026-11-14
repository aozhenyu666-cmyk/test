// R4-5 姐妹界面：卡片头部与今日审计面板的纯函数。
import assert from 'node:assert/strict'
import test from 'node:test'

import { COMPANION_LIFE, DEFAULT_NAMES, STRATEGIST_LIFE } from '../lib/roles.js'
import { buildSnapshot, sistersView } from '../lib/panel.js'
import { SISTERS, auditOf, cardsOf, groupPrompts, personaOf, sisterLine } from '../lib/sisters.js'

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

test('群聊：两个人拿到的是同一句开场，谁都没被告知「该谁先说」', () => {
  const p = groupPrompts({ said: '今晚吃啥' })
  assert.ok(p.startsWith('（家庭群，他、'))
  assert.ok(p.includes(DEFAULT_NAMES.companion))
  assert.ok(p.includes(DEFAULT_NAMES.strategist))
  assert.ok(p.endsWith('今晚吃啥'))
  // 还没人说话，就该只有这一条
  assert.equal(groupPrompts({ said: '今晚吃啥', first: '' }), p)
  assert.equal(groupPrompts({ said: '今晚吃啥', first: '   ' }), p)
})

test('群聊：后说的那个只多一条聊天记录，不该收到导演提示', () => {
  const p = groupPrompts({ said: '今晚吃啥', first: '别问，问就是泡面', firstName: '鱼丸' })
  assert.ok(p.endsWith('鱼丸：别问，问就是泡面'))
  // 以前那句「你接着说：可以接她的话，也可以反驳她，别重复她」就是「太刻意」的来源
  for (const word of ['你接着说', '可以接', '也可以反驳', '别重复']) {
    assert.ok(!p.includes(word), '不该再出现导演词：' + word)
  }
})

test('群聊：先说的那句把引擎的〔标签〕摘掉，超长截到 300', () => {
  assert.equal(
    groupPrompts({ said: 'a', first: '〔鱼丸〕我回来了', firstName: '鱼丸' }),
    groupPrompts({ said: 'a', first: '我回来了', firstName: '鱼丸' }),
  )
  const long = groupPrompts({ said: 'a', first: 'x'.repeat(5000), firstName: '鱼丸' })
  assert.equal(long.split('\n\n')[1].length, 3 + 300)
  // 先说的那个没名字时不拼一个空的冒号出来
  assert.ok(groupPrompts({ said: 'a', first: '嗯' }).endsWith('\n\n嗯'))
})

test('群聊：名字走 /config 覆盖，缺一个只回落那一个', () => {
  const p = groupPrompts({ names: { companion: '小鱼丸' }, said: 'x' })
  assert.ok(p.includes('小鱼丸'))
  assert.ok(p.includes(DEFAULT_NAMES.strategist))
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

test('原生姐妹面板：视图包含两位姐妹角色卡列表且快照携带鱼丸与鲸澜人设', () => {
  const view = sistersView()
  const listNode = view.children.find((n) => n.type === 'list' && n.items?.$ === 'data.sisterCards')
  assert.ok(listNode, 'sistersView 应包含 data.sisterCards 列表组件')
  const snap = buildSnapshot({ names: DEFAULT_NAMES })
  assert.equal(snap.sisterCards.length, 2)
  assert.ok(snap.sisterCards[0].title.includes('鱼丸'))
  assert.ok(snap.sisterCards[1].title.includes('鲸澜'))
  assert.ok(snap.sisterPersonaText.includes('鱼丸') && snap.sisterPersonaText.includes('鲸澜'))
})
