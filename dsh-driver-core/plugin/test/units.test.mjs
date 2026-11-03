import assert from 'node:assert/strict'
import { test } from 'node:test'
import { classify, COG } from '../lib/core/bandwidth.js'
import { synthesize, patternsOf, pickMemories } from '../lib/core/context.js'
import { DICE, diceCard, pickDie } from '../lib/core/dice.js'
import { driverPrompt, parseCard } from '../lib/core/prompt.js'
import { Telemetry } from '../lib/core/telemetry.js'
import { choiceOf, replyFeatures } from '../lib/core/text.js'

test('回复特征：敷衍、放弃、有依据、点选', () => {
  assert.equal(replyFeatures('嗯').filler, true)
  assert.equal(replyFeatures('知道了。').filler, true)
  assert.equal(replyFeatures('不知道').giveUp, true)
  assert.equal(replyFeatures('不知道').score, 0)
  const ev = replyFeatures('因为题干说了只有一个条件成立')
  assert.equal(ev.evidence, true)
  assert.ok(ev.score >= 0.6)
  assert.equal(choiceOf('选A'), 'A')
  assert.equal(choiceOf('b吧'), 'B')
  assert.equal(choiceOf('ABC'), null)
  assert.equal(replyFeatures('排除', { anchors: ['排除'] }).anchorHit, true)
})

const base = { now: 100_000, inAnchor: true, liveActive: true, lastSignalAt: 90_000, recent: [] }
const rep = (text, latencyMs, expects = 'evidence') => ({ latencyMs, features: replyFeatures(text), expects })

test('带宽：四个状态各自的判据', () => {
  assert.equal(classify({ ...base, inAnchor: false }).state, COG.ESCAPED)
  assert.equal(classify({ ...base, liveActive: false }).state, COG.ESCAPED)
  assert.equal(classify({ ...base, lastSignalAt: 0 }).state, COG.THROTTLED_EMPTY)
  assert.equal(classify({ ...base, lastReply: rep('嗯', 1000) }).state, COG.THROTTLED_EMPTY)
  assert.equal(classify({ ...base, lastReply: rep('选D吧这个', 1500) }).state, COG.HIGH_SPEED_SKIMMING)
  assert.equal(classify({ ...base, lastReply: rep('因为第二段说了前提', 1500) }).state, COG.ACTIVE_FLOW)
  assert.equal(classify({ ...base, lastReply: rep('A', 800, 'choice') }).state, COG.ACTIVE_FLOW)
  assert.equal(classify({ ...base, lastReply: rep('好像是那个吧', 60_000) }).state, COG.THROTTLED_EMPTY)
  // 不快不慢、没依据，但最近两次都没依据 → 滑
  const thin = { latencyMs: 10_000, features: replyFeatures('感觉是这个') }
  assert.equal(classify({ ...base, lastReply: rep('感觉是这个', 10_000), recent: [thin, thin] }).reason, 'thin_trend')
  // liveActive 未知不算逃逸
  assert.equal(classify({ ...base, liveActive: null }).state, COG.ACTIVE_FLOW)
})

test('骰子：不连用同一个；有样本时选推进率最高的', () => {
  assert.equal(pickDie([]), DICE[0])
  assert.notEqual(pickDie(['精简']), '精简')
  const h = { 回想: { tries: 4, advanced: 4 }, 精简: { tries: 4, advanced: 0 } }
  assert.equal(pickDie(['预判'], h), '回想')
  assert.notEqual(pickDie(['回想'], h), '回想')
  for (const d of DICE) {
    const c = diceCard(d, '第3题')
    assert.ok(c.kind === 'ab' || c.kind === 'read_two_words')
    if (c.kind === 'ab') assert.ok(c.options.A && c.options.B)
  }
})

test('推进日志：顺序不能跳，没有可见证明的回复会被标出来', () => {
  const t = new Telemetry()
  t.generated({ id: 'a', intent: 'probe', kind: 'one_word' }, 1)
  assert.equal(t.advanced('a', 2, 'x'), false, '没回复就不能算推进')
  t.replied('a', 3, { text: 'x', latencyMs: 1, features: { score: 1 } })
  assert.equal(t.advanced('a', 4, 'basis'), true)
  assert.equal(t.resumed('a', 5), false, '没被中断过就谈不上接回')
  const s = t.summary()
  assert.equal(s.displayed, 0)
  assert.equal(s.displayedUnproven, 1)
  assert.equal(s.advanced, 1)
})

test('隐秘层：挑和当前主题相关、带习惯/目标标签的记忆', () => {
  const mems = [
    { text: '鱼片做行测图形推理时总是抓到一半就下结论', tags: ['习惯'] },
    { text: '今天中午吃了面', tags: [] },
    { text: '近期主线：考公行测、申论', tags: ['目标'] },
  ]
  const picked = pickMemories(mems, { topic: '行测', task: '图形推理' })
  assert.equal(picked.length, 2)
  assert.ok(!picked.some((m) => m.text.includes('吃了面')))
  const p = patternsOf({ sessions: 5, escapeMinutes: { 7: 3, 3: 1 }, dice: { 预判: { tries: 4, advanced: 3 } } })
  assert.ok(p[0].includes('第 7 分钟'))
  assert.ok(p[1].includes('预判'))
})

test('提示词含三层；解析拒绝不合规的卡', () => {
  const ctx = synthesize({
    session: { topic: '行测', task: '第3题', minute: 4, minutes: 15 },
    lastCard: { text: '为什么排除C？', anchor: '第3题·排除C' },
    recentReplies: [{ text: '感觉' }],
    cog: { state: COG.THROTTLED_EMPTY, reason: 'filler' }, die: '预判',
    memories: [{ text: '抓到一半就下结论', tags: ['习惯'] }], profile: { goals: ['考公'], habits: ['抓到一半就下结论'] }, history: {},
  })
  const p = driverPrompt(ctx, { intent: 'dice' })
  for (const s of ['表层', '隐秘层', '驾驶决策', 'THROTTLED_EMPTY', '预判', '第3题·排除C', '抓到一半就下结论']) assert.ok(p.includes(s), s)
  assert.equal(parseCard('{"kind":"open","text":"说说看"}', { state: COG.THROTTLED_EMPTY }), null, '限速时不许开放题')
  assert.equal(parseCard('{"kind":"ab","text":"选哪个","options":{"A":"x"}}'), null, 'ab 缺选项')
  const ok = parseCard('好的：{"kind":"ab","text":"更像哪个？","options":{"A":"支持","B":"排除"},"anchor":"第3题"}', { state: COG.THROTTLED_EMPTY })
  assert.equal(ok.kind, 'ab')
  assert.equal(ok.expects, 'choice')
  assert.equal(ok.anchor, '第3题')
})
