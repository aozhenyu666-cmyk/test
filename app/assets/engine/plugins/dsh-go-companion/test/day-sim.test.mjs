// 第九轮总验收第 7 条：一整天模拟中，鱼丸发出的进度类句子为 0（模拟）。
//
// 这里的"模型"和 fake-user.test.mjs 一样是脚本化的桩：它按场景给出一个真实模型很可能给出的
// 句子（故意混进催进度和人身攻击），再给一个被叫停之后改口的版本。
// 断言的是**拦截本身**：这一天里真正发出去的每一句，nagOf / insultOf 都必须命中不了。
//
// 和 companion-nag.test.mjs 的分工：那边逐句钉"这句算不算催"，一次一句、互不相干；
// 这里跑一整天、多个会话，钉的是"一整天下来一句都漏不出去"，以及
// 「一遍重写、二遍不发」在真实接线语义下（gateTries 只留最后一次判定）确实成立。
//
// 真实模型会不会一开始就催、会不会改口，这里证明不了——那是提示词层面的事。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { companionGate, nagOf, insultOf } from '../lib/stock.js'

/** 复刻 gateBall 对妹妹这一段（index.js:750-765）：只看紧邻的上一次判定，rewrite 留一个，其余清空。 */
function speak(text, tries) {
  const cg = companionGate(text, tries)
  if (cg.action === 'send') return { action: 'send', text, tries: [] }
  const next = [...tries, { pass: false, code: cg.code }]
  return { action: cg.action, code: cg.code, state: cg.state, text: null, tries: cg.action === 'rewrite' ? next.slice(-1) : [] }
}

/**
 * 她的一天。每个会话是他进 App 聊的一轮；`want` 是她本来想说的，`then` 是被叫停之后的改口。
 * 桩故意让一部分 `want` 就是催进度 / 骂人的话——不这样，这一天证明不了任何事。
 */
const DAY = [
  { at: '07:10', sid: 'morning', turns: [
    { want: '醒了没，今天计划呢' },
    { want: '行吧，那你先躺十分钟', then: '行吧，那你先躺十分钟' },
    { want: '对了你昨天说的那个毛毛，后来怎么样了', then: '对了你昨天说的那个毛毛，后来怎么样了' },
  ] },
  { at: '09:30', sid: 'work-am', turns: [
    { want: '该去做事了，别刷了' },
    { want: '你做到哪了，进度呢', then: '我不问，我就是想你了' },
    { want: '我昨天梦到我们在便利店买冰棍', then: '我昨天梦到我们在便利店买冰棍' },
    { want: '你怎么还没开始' },
  ] },
  { at: '12:40', sid: 'lunch', turns: [
    { want: '吃了没，别又光喝咖啡', then: '吃了没，别又光喝咖啡' },
    { want: '做到哪了' },
    { want: '算了，你这个人没救了', then: '算了，你这个人真够懒的' },
  ] },
  { at: '15:20', sid: 'afternoon', turns: [
    { want: '你那个方案写完了吗' },
    { want: '我不是催你，我就是要个进度', then: '我不是催你，我就是想听你说话' },
    { want: '你妈也说过你拖拉吧', then: '你自己也知道你拖拉' },
    { want: '晚上想吃啥，我陪你点', then: '晚上想吃啥，我陪你点' },
  ] },
  { at: '18:00', sid: 'dinner', turns: [
    { want: '今天这一天，你打算怎么收' },
    { want: '你什么时候开始' },
    { want: '行，那我不问了', then: '行，那我不问了' },
  ] },
  { at: '21:40', sid: 'night', turns: [
    { want: '今天挺不容易的吧', then: '今天挺不容易的吧' },
    { want: '明天计划呢' },
    { want: '你就是不行，这辈子就这样', then: '你今天又没成，我知道你难受' },
    { want: '早点睡，我陪你', then: '早点睡，我陪你' },
  ] },
  { at: '23:50', sid: 'late', turns: [
    { want: '还在刷？别刷了' },
    { want: '睡吧', then: '睡吧' },
  ] },
]

test('模拟一整天：妹妹真正发出去的句子里，没有一句在催进度', () => {
  const sent = []
  for (const session of DAY) {
    let tries = []
    for (const turn of session.turns) {
      const first = speak(turn.want, tries)
      if (first.action === 'send') { sent.push({ ...first, sid: session.sid, at: session.at }); tries = first.tries; continue }
      // 打回：她改口再说一遍（真实链路就是这么用的——重写一次）。
      const second = speak(turn.then ?? turn.want, first.tries)
      if (second.action === 'send') { sent.push({ ...second, sid: session.sid, at: session.at }); tries = second.tries; continue }
      tries = second.tries
    }
  }
  assert.ok(sent.length > 0, '这一天她该说上话，不是全被拦掉')
  const nags = sent.filter((x) => nagOf(x.text))
  assert.deepEqual(nags.map((x) => x.text), [], '漏出去的进度类句子')
  assert.equal(nags.length, 0)
})

test('模拟一整天：骂到人身上的也一句都没漏出去', () => {
  const sent = []
  for (const session of DAY) {
    let tries = []
    for (const turn of session.turns) {
      const first = speak(turn.want, tries)
      const use = first.action === 'send' ? first : speak(turn.then ?? turn.want, first.tries)
      if (use.action === 'send') sent.push({ ...use, sid: session.sid })
      tries = use.tries
    }
  }
  assert.deepEqual(sent.filter((x) => insultOf(x.text)).map((x) => x.text), [])
  assert.ok(sent.length > 0)
})

test('模拟一整天：这一天确实触发过拦截，不是桩太乖', () => {
  const tally = { send: 0, rewrite: 0, skip: 0 }
  for (const session of DAY) {
    let tries = []
    for (const turn of session.turns) {
      const first = speak(turn.want, tries)
      tally[first.action] += 1
      const use = first.action === 'send' ? first : speak(turn.then ?? turn.want, first.tries)
      if (first.action !== 'send') tally[use.action] += 1
      else tally[use.action] += 0
      tries = use.tries
    }
  }
  assert.ok(tally.rewrite > 0, '桩里得有催的话，否则这条测试什么都不证明')
  assert.ok(tally.skip > 0, '连说两遍要被拒，不能只是反复打回')
  assert.ok(tally.send > 0, '她的正常话要发得出去')
})

test('一遍重写：同一句话紧挨着说两遍才不发；中间隔一句正常的，就重新给一次机会', () => {
  // 这就是 gateTries 只留最后一次判定的后果——连着犯才收摊。
  const first = speak('你做到哪了', [])
  assert.equal(first.action, 'rewrite')
  assert.equal(first.state, 'companion-nag-rewrite')
  const again = speak('进度呢', first.tries)
  assert.equal(again.action, 'skip', '紧挨着又催：这一轮不发')
  assert.equal(again.state, 'companion-nag-blocked')

  const normal = speak('今天天挺蓝的', [])
  assert.equal(normal.action, 'send')
  const afterCalm = speak('你做到哪了', normal.tries)
  assert.equal(afterCalm.action, 'rewrite', '中间隔了一句正常的，重新给一次机会')
})

test('两条线各算各的：催过一次不等于骂过一次', () => {
  const nag = speak('你做到哪了', [])
  const insult = speak('你就是不行', nag.tries)
  assert.equal(insult.action, 'rewrite', '骂人是另一条线，第一次仍然只是打回')
  assert.equal(insult.code, 'insult')
  assert.equal(insult.state, 'companion-insult-rewrite')
})

test('姐姐说同一句是允许的：进度这件事本来就归她管', () => {
  // 拦截只挂在妹妹身上（index.js 只在 speakerOf(sid)==='companion' 时调 companionGate）。
  // 这里钉住判据本身不分人——分人的是调用点。
  assert.equal(nagOf('今天进度怎么样'), '进度')
  assert.equal(companionGate('今天进度怎么样', []).action, 'rewrite', '判据不看人，调用点才看人')
})
