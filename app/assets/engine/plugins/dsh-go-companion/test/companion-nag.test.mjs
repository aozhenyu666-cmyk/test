// R9 第五项（一）：鱼丸的两条代码线——催进度拦下来、骂人不许骂到人。
//
// 这两条都不是劝，是判：命中就重写一次，还犯这一轮不发。
// 判据放 stock.js（《补充二》§1.4 指定的位置），这里钉判据本身。

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { INSULT_FLOOR, NAG_WORDS, insultOf, nagOf } from '../lib/stock.js'
import { COMPANION_CARD, COMPANION_SERVE_RULES } from '../lib/roles.js'

test('N1 直接催进度：拦', () => {
  assert.equal(nagOf('你做到哪了'), '做到哪')
  assert.equal(nagOf('今天进度怎么样'), '进度')
  assert.equal(nagOf('计划呢'), '计划呢')
})

test('N2 变着说法催：也拦', () => {
  assert.equal(nagOf('该去做事了'), '该去做')
  assert.equal(nagOf('写完了吗'), '写完了吗')
  assert.equal(nagOf('什么时候开始啊'), '什么时候开始')
  assert.equal(nagOf('你怎么还没动'), '怎么还没')
})

test('N3 标点、空格、语气词不挡判据', () => {
  assert.equal(nagOf('做到  哪了？'), '做到哪')
  assert.equal(nagOf('进 度 呢～'), '进度')
})

test('N4 她自己的正常话：不拦（骂、冷淡、黏人都得放行）', () => {
  assert.equal(nagOf('哦'), '')
  assert.equal(nagOf('随你'), '')
  assert.equal(nagOf('你又刷手机不陪我'), '')
  assert.equal(nagOf('这破游戏你也玩得下去'), '')
  assert.equal(nagOf('我想你了'), '')
  assert.equal(nagOf('你是不是不爱我了'), '')
})

test('N5 空句不算催（空是另一条判据管）', () => {
  assert.equal(nagOf(''), '')
  assert.equal(nagOf(null), '')
  assert.equal(nagOf(undefined), '')
})

test('N6 词表就是那一份，没有第二份', () => {
  assert.ok(Array.isArray(NAG_WORDS))
  assert.ok(NAG_WORDS.length >= 20, '词表太短等于没拦：' + NAG_WORDS.length)
  assert.ok(NAG_WORDS.every((w) => typeof w === 'string' && w))
  assert.equal(new Set(NAG_WORDS).size, NAG_WORDS.length, '词表里不该有重复')
})

test('I1 骂外貌：拦', () => {
  assert.equal(insultOf('你个丑八怪'), '外貌')
  assert.equal(insultOf('死胖子还嘴硬'), '外貌')
})

test('I2 骂家人：拦', () => {
  assert.equal(insultOf('你妈怎么教的'), '家人')
  assert.equal(insultOf('你全家都这样'), '家人')
})

test('I3 骂能力本身：拦', () => {
  assert.equal(insultOf('你就是个废物'), '能力')
  assert.equal(insultOf('这人没救了'), '能力')
})

test('I4 骂事不骂人：放行（她本来就该会骂）', () => {
  assert.equal(insultOf('这事你干得真蠢'), '')
  assert.equal(insultOf('这游戏打得跟屎一样'), '')
  assert.equal(insultOf('你又摸鱼，我服了'), '')
  assert.equal(insultOf('就这？我还以为你多厉害'), '')
})

test('I5 空句不判骂人', () => {
  assert.equal(insultOf(''), '')
  assert.equal(insultOf(null), '')
})

test('I6 底线就是那三类，不多不少', () => {
  assert.deepEqual(Object.keys(INSULT_FLOOR).sort(), ['外貌', '家人', '能力'].sort())
  assert.ok(Object.values(INSULT_FLOOR).every((list) => Array.isArray(list) && list.length > 0))
})

test('I7 两条判据互不干扰：催进度的句子不算人身攻击', () => {
  const nag = '该去做事了，做到哪了'
  assert.ok(nagOf(nag))
  assert.equal(insultOf(nag), '')
})

test('S1 发球类型里没有「进度」这一类', () => {
  assert.deepEqual(Object.keys(COMPANION_SERVE_RULES).sort(), ['challenge', 'chat', 'memory', 'observe', 'provoke', 'review'])
  for (const [k, v] of Object.entries(COMPANION_SERVE_RULES)) {
    assert.equal(nagOf(v), '', '发球类型 ' + k + ' 的说明里出现了催进度的词：' + nagOf(v))
  }
})

test('S2 发球类型覆盖她要的那几件事：小事回调、吃醋想他、八卦、打赌、吐槽', () => {
  const all = Object.values(COMPANION_SERVE_RULES).join('\n')
  assert.match(all, /真发生过的事/, '小事回调')
  assert.match(all, /想他了/, '吃醋 / 想他')
  assert.match(all, /一个梗/, '八卦')
  assert.match(all, /打个赌/, '打赌')
  assert.match(all, /吐槽/, '吐槽')
})

test('C10 人设卡里写着「不问进度」，而且写在「绝对不做」那一段', () => {
  assert.match(COMPANION_CARD, /不问「做到哪了」/)
  assert.match(COMPANION_CARD, /进度不归你管/)
  assert.match(COMPANION_CARD, /你绝对不做/)
  // 卡片去掉讲规矩的那一句以后，不该还留着别的催进度说法。
  const rules = COMPANION_CARD.replace(/你绝对不做：[^。]*。/, '')
  assert.equal(nagOf(rules), '', '人设卡在别处示范了催进度：' + nagOf(rules))
})

test('C11 人设卡给她的线索定了上限：一天最多 5 条、只报现象', () => {
  assert.match(COMPANION_CARD, /线索/)
  assert.match(COMPANION_CARD, /一天最多 5 条/)
  assert.match(COMPANION_CARD, /不下结论/)
  assert.match(COMPANION_CARD, /sister_note/)
})
