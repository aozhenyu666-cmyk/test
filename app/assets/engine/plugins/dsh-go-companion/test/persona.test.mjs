// P3：人格卡与两种模式。
//
// 断言的是接线：人格卡进 prompt、模式换掉候选球、说一句就切模式、到点自己回伙伴。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanPersona, effectiveMode, modeGap, modeOr, modeServeOrder, modeSwitch, personaLines } from '../lib/persona.js'
import { pickServeType } from '../lib/serve.js'
import { judgeSystem } from '../lib/judge.js'

const NOW = 1793000000000

test('人格卡只留认识的字段，各自限长', () => {
  const p = cleanPersona({ voice: '  直说不绕  ', bite: 'x'.repeat(500), junk: 'drop me' })
  assert.equal(p.voice, '直说不绕')
  assert.equal(p.bite.length, 200)
  assert.equal('junk' in p, false)
  assert.deepEqual(cleanPersona(null), {})
})

test('空的人格卡不硬凑 prompt', () => {
  const lines = personaLines({}, 'partner')
  assert.equal(lines.filter((l) => l.startsWith('你的口吻')).length, 0)
  assert.equal(lines.length, 1, '只留模式那一行')
})

test('人格卡进了 prompt，模式那一行也在', () => {
  const lines = personaLines({ voice: '不哄', avoid: '家里的事' }, 'coach').join('\n')
  assert.match(lines, /你的口吻：不哄/)
  assert.match(lines, /这些话题不碰：家里的事/)
  assert.match(lines, /教练模式/)
  assert.doesNotMatch(lines, /伙伴模式/)
})

test('两种模式挑球的偏好不一样', () => {
  const partner = modeServeOrder('partner').slice(0, 3)
  const coach = modeServeOrder('coach').slice(0, 3)
  assert.deepEqual(partner, ['chat', 'memory', 'observe'])
  assert.deepEqual(coach, ['observe', 'provoke', 'challenge'])

  // 同样的 roll，两种模式落到的球都在自己的偏好里，而且是不同类别。
  for (const roll of [0, 20, 40, 60, 80, 99]) {
    const p = pickServeType([], roll, { only: partner })
    const c = pickServeType([], roll, { only: coach })
    assert.ok(partner.includes(p), '伙伴挑出了 ' + p)
    assert.ok(coach.includes(c), '教练挑出了 ' + c)
  }
})

test('教练模式判球更严，system 里说了', () => {
  assert.match(judgeSystem('coach'), /教练模式/)
  assert.doesNotMatch(judgeSystem('partner'), /教练模式/)
})

test('伙伴模式的间隔更松，教练更勤', () => {
  const base = 30 * 60_000
  assert.equal(modeGap(base, 'coach'), base)
  assert.ok(modeGap(base, 'partner') > base)
  assert.equal(modeGap(0, 'partner'), 0)
})

test('说一句就切模式，带不带时长都认', () => {
  assert.deepEqual(modeSwitch('进入教练模式 2 小时'), { mode: 'coach', hours: 2 })
  assert.deepEqual(modeSwitch('切到教练模式'), { mode: 'coach', hours: 0 })
  assert.deepEqual(modeSwitch('换回伙伴模式'), { mode: 'partner', hours: 0 })
  assert.equal(modeSwitch('我在改简历'), null)
  assert.equal(modeSwitch(''), null)
  // 一句话里同时喊两个模式：认不出来就不动，别猜。
  assert.equal(modeSwitch('教练模式和伙伴模式哪个好'), null)
})

test('到点自己回到伙伴模式', () => {
  assert.equal(effectiveMode({ mode: 'coach', modeUntil: 0 }, NOW), 'coach', '没给时长就一直开着')
  assert.equal(effectiveMode({ mode: 'coach', modeUntil: NOW + 1000 }, NOW), 'coach')
  assert.equal(effectiveMode({ mode: 'coach', modeUntil: NOW }, NOW), 'partner', '到点回到伙伴')
  assert.equal(modeOr('乱写'), 'partner')
})
