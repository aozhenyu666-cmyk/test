import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  VERDICTS,
  NEXT_ACTIONS,
  gardenClass,
  judgeReply,
  judgeSystem,
  judgeUser,
  parseJudge,
  ruleVerdict,
} from '../lib/judge.js'

/** 假的 ctx.llm.stream：一次吐出 [text]，或者直接卡住不动。 */
function streamOf(text, { chunks } = {}) {
  return async function* () {
    for (const t of chunks || [text]) yield { type: 'text-delta', text: t }
  }
}

const hang = async function* () {
  await new Promise(() => {})
  yield { type: 'text-delta', text: '' }
}

test('判词与回击方式的词表就是任务书那六个 / 四个', () => {
  assert.deepEqual(VERDICTS, ['engaged', 'brief', 'evasive', 'stock', 'refuse', 'pushback'])
  assert.deepEqual(NEXT_ACTIONS, ['deeper', 'angle', 'callout', 'stop'])
})

test('只认 JSON，字段不在词表里就当没判出来', () => {
  assert.deepEqual(parseJudge('{"verdict":"pushback","why":"他给出反例","next":"deeper"}'), {
    verdict: 'pushback',
    why: '他给出反例',
    next: 'deeper',
  })
  assert.equal(parseJudge('这句是接住了'), null)
  assert.equal(parseJudge('{"verdict":"很好","next":"deeper"}'), null)
  assert.equal(parseJudge(''), null)
})

test('模型没给 next 就按判词补一个默认回击', () => {
  assert.equal(parseJudge('{"verdict":"stock","why":"复述"}').next, 'callout')
  assert.equal(parseJudge('{"verdict":"refuse","why":"不想聊"}').next, 'stop')
  assert.equal(parseJudge('{"verdict":"engaged","why":"给细节"}').next, 'deeper')
})

test('套话：规则只认字面模板，非字面的空话要靠模型', () => {
  assert.equal(ruleVerdict('下一步是什么').verdict, 'stock')
  assert.equal(ruleVerdict('加油').verdict, 'stock')
  assert.equal(ruleVerdict('下一步是什么').next, 'callout')
  // 已知上限：自己组织的空话，规则兜底仍会当成 engaged（judge.js 里写了 shortcut）
  assert.equal(ruleVerdict('这个问题的答案其实非常重要，我们应当全面地看待它并及时总结').verdict, 'engaged')
})

test('规则兜底和老的分类口径对得上', () => {
  assert.equal(ruleVerdict('因为图形是对称的，所以选 D').verdict, 'engaged')
  assert.equal(ruleVerdict('嗯').verdict, 'brief')
  assert.equal(ruleVerdict('以后再说').verdict, 'evasive')
  assert.equal(ruleVerdict('不想聊这个').verdict, 'refuse')
})

test('判词能落到话题花园认识的四种分类', () => {
  assert.equal(gardenClass('engaged'), 'substantive')
  assert.equal(gardenClass('pushback'), 'substantive')
  assert.equal(gardenClass('brief'), 'brief')
  assert.equal(gardenClass('stock'), 'brief')
  assert.equal(gardenClass('evasive'), 'avoid')
  assert.equal(gardenClass('refuse'), 'refuse')
})

test('判断题带上发球原文、回球和最近几拍', () => {
  const user = judgeUser({
    serve: '我猜你在躲申论',
    reply: '我没有，我在做行测',
    recent: [
      { from: 'me', text: '早上好' },
      { from: 'user', text: '嗯' },
    ],
  })
  assert.match(user, /我猜你在躲申论/)
  assert.match(user, /我没有，我在做行测/)
  assert.match(user, /早上好/)
  assert.match(user, /你：早上好/)
  assert.match(user, /对方：嗯/)
  assert.match(judgeSystem(), /只输出一行 JSON/)
})

test('模型判出来就用模型的', async () => {
  const out = await judgeReply({
    stream: streamOf('{"verdict":"pushback","why":"他给了反例","next":"deeper"}'),
    provider: 'p',
    model: 'm',
    serve: '我猜你在躲',
    reply: '不，是你记错了',
  })
  assert.deepEqual(out, { verdict: 'pushback', why: '他给了反例', next: 'deeper', judged: true })
})

test('输出被切成几块也能拼起来', async () => {
  const out = await judgeReply({
    stream: streamOf(null, { chunks: ['{"verdict":"eng', 'aged","why":"有细', '节","next":"deeper"}'] }),
    provider: 'p',
    model: 'm',
    reply: '因为左右对称所以选 D',
  })
  assert.equal(out.verdict, 'engaged')
  assert.equal(out.judged, true)
})

test('超时就退回规则，不卡住这一轮', async () => {
  const started = Date.now()
  const out = await judgeReply({ stream: hang, provider: 'p', model: 'm', reply: '嗯', timeoutMs: 30 })
  assert.equal(out.judged, false)
  assert.equal(out.verdict, 'brief')
  assert.ok(Date.now() - started < 2000)
})

test('拿不到模型就直接用规则', async () => {
  const out = await judgeReply({ stream: undefined, provider: '', model: '', reply: '不想聊这个' })
  assert.equal(out.judged, false)
  assert.equal(out.verdict, 'refuse')
})

test('模型吐了不是 JSON 的东西也退回规则', async () => {
  const out = await judgeReply({
    stream: streamOf('我觉得他这一球接得不错'),
    provider: 'p',
    model: 'm',
    reply: '因为对称，所以选 D',
  })
  assert.equal(out.judged, false)
  assert.equal(out.verdict, 'engaged')
})

test('假用户脚本：一直敷衍 / 一直套话 / 真反驳', async () => {
  // 规则兜底这条路不花模型，正好用来钉住三种假用户的判球口径
  const brief = ['嗯', '哦', '还行', '随便']
  for (const t of brief) assert.equal(ruleVerdict(t).verdict, 'brief', t)

  // 套话型：只放字面模板，因为规则兜底只认这些（非字面的要靠模型判）
  const stock = ['下一步是什么', '依据是什么', '加油']
  for (const t of stock) assert.equal(ruleVerdict(t).verdict, 'stock', t)

  const pushback = [
    '不对，你说的那个数据是三月的，四月的报告里已经改了',
    '我不同意，刷题量解决不了审题，上次我就是审错了',
  ]
  for (const t of pushback) assert.equal(ruleVerdict(t).verdict, 'engaged', t)
})

test('假用户脚本：套话型由模型判时落到 stock 并点破', async () => {
  const out = await judgeReply({
    stream: streamOf('{"verdict":"stock","why":"正确的废话","next":"callout"}'),
    provider: 'p',
    model: 'm',
    serve: '你昨天说行测靠刷题量',
    reply: '任何事情都有两面性，关键是要找到适合自己的方式方法才行',
  })
  assert.equal(out.verdict, 'stock')
  assert.equal(out.next, 'callout')
})

test('真模型在 why 里用了没转义的双引号：照样抠出判词', () => {
  const raw = '{"verdict":"evasive","why":"没回答最重要的那件事，用"作业写完了"给自己开了张通行证。","next":"callout"}'
  const out = parseJudge(raw)
  assert.equal(out.verdict, 'evasive')
  assert.equal(out.next, 'callout')
  assert.match(out.why, /作业写完了/)
})
