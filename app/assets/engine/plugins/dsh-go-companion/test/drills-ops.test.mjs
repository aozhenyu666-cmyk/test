// C2 四个基本操作：精简 / 预判 / 提问 / 回想。
//
// 这组用例守的是一件事：**合格线在代码里，不在模型嘴里**。
// 所以每个 judge 都拿最贴近真实的那种「看着像过了、其实没答」的句子来试——
// 用感觉代替判断、照抄原文一段、问一个是非题、看着卡抄一遍再说是回想起来的。
// 判定一软，练半年也不知道有没有长进，而界面还会告诉他已经通过了。

import assert from 'node:assert/strict'
import test from 'node:test'

import {
  BRIEF_MAX, BRIEF_SHARE_MAX, BRIEF_SHARE_MIN, OP_DAILY_MAX, OP_META, OP_ORDER, RECALL_COVER_MIN,
  coverOf, hasAssertion, judgeAsk, judgeBrief, judgeOp, judgePredict, judgeRecall, nextOp, opLines,
  opQuota, opStats, questionCount, shareOf,
} from '../lib/drills.js'

// 一段像他真的会说出来的话：够长（>100 字），有目标、有猜测、有现状。
const SOURCE =
  '这段时间我一直在琢磨增量同步这件事，服务端已经有版本号了，但是客户端每次还是全量拉一遍，' +
  '所以我怀疑是订阅没解绑，也可能是本地没有存lastVersion，反正现在每次打开都要等十几秒，' +
  '这个体验很差，我想把它改成只拉变化的部分。'

const GOAL = '把增量同步做出来'

const CARD = '所以是订阅没解绑，本地也没有存 lastVersion'

const row = (op, ok, at) => ({ state: 'drill-op', op, ok, at })

// ── 回想 ──────────────────────────────────────────────────────────────────────

test('R1 遮住卡、说得出卡上那几条 → 过', () => {
  const r = judgeRecall('增量同步卡在订阅没解绑，本地也没存版本号', { cardWords: CARD, blind: true })
  assert.equal(r.ok, true, r.text)
  assert.equal(r.code, 'pass')
  assert.equal(r.data.cover >= RECALL_COVER_MIN, true)
})

test('R2 没遮住卡就不算：看着抄谁都答得上', () => {
  const r = judgeRecall(CARD, { cardWords: CARD, blind: false })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'not-blind')
})

test('R3 遮没遮住这件事没上报（undefined）也不给过——不报就是没遮', () => {
  const r = judgeRecall(CARD, { cardWords: CARD })
  assert.equal(r.code, 'not-blind')
})

test('R4 空答案', () => {
  assert.equal(judgeRecall('   ', { cardWords: CARD, blind: true }).code, 'empty')
})

test('R5 卡上没有结论就没有可回想的，如实说', () => {
  const r = judgeRecall('订阅没解绑', { cardWords: '', blind: true })
  assert.equal(r.code, 'no-card')
})

test('R6 答的全是别的事 → 召回不够', () => {
  const r = judgeRecall('今天想早点睡，明天还要早起去排队', { cardWords: CARD, blind: true })
  assert.equal(r.code, 'too-little')
})

test('R7 阈值卡在边界上：差一点点也不给过', () => {
  const r = judgeRecall('订阅', { cardWords: CARD, blind: true })
  assert.equal(r.ok, false)
  assert.equal(r.data, undefined)
  assert.equal(r.code, 'too-little')
  // 阈值本身是协议，改它得知道自己在改什么。
  assert.equal(RECALL_COVER_MIN > 0 && RECALL_COVER_MIN < 1, true)
})

test('R8 回想用的是「卡上内容被覆盖多少」，方向和精简相反', () => {
  // 一句很长、把卡上那几条都包住的话：覆盖率高。
  const long = '卡上说订阅没解绑，本地也没有存 lastVersion，所以还得先把这两处都查一遍'
  assert.equal(judgeRecall(long, { cardWords: CARD, blind: true }).ok, true)
  // 一句很短、只沾一个词的话：覆盖率低。
  assert.equal(judgeRecall('订阅', { cardWords: CARD, blind: true }).ok, false)
})

// ── 精简 ──────────────────────────────────────────────────────────────────────

test('B1 真压住了 → 过，并报字数与重合率', () => {
  const r = judgeBrief('增量同步只拉变化的部分，先查订阅和本地版本号', { source: SOURCE, goal: GOAL })
  assert.equal(r.ok, true, r.text)
  assert.equal(r.data.chars <= BRIEF_MAX, true)
  assert.equal(r.data.share >= BRIEF_SHARE_MIN && r.data.share <= BRIEF_SHARE_MAX, true)
})

test('B2 空答案', () => {
  assert.equal(judgeBrief('  ', { source: SOURCE, goal: GOAL }).code, 'empty')
})

test('B3 没有原文可比，这一题出不了（不假装判过了）', () => {
  const r = judgeBrief('增量同步先拉变化的部分', { source: '', goal: GOAL })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'no-source')
})

test('B4 超一个字也是超', () => {
  const src = '增量同步增量同步增量同步'
  const ok30 = '增量同步' + '啊'.repeat(26)
  const bad31 = '增量同步' + '啊'.repeat(27)
  assert.equal([...ok30].length, 30)
  assert.equal([...bad31].length, 31)
  assert.notEqual(judgeBrief(ok30, { source: src, goal: GOAL }).code, 'too-long')
  assert.equal(judgeBrief(bad31, { source: src, goal: GOAL }).code, 'too-long')
})

test('B5 和卡上的目标没有一个共同的关键词 → 不认', () => {
  const r = judgeBrief('今天天气不错适合出去走走看看', { source: SOURCE, goal: GOAL })
  assert.equal(r.code, 'no-goal-word')
})

test('B6 照抄原文一段（重得太厉害）→ 没压', () => {
  const r = judgeBrief('增量同步服务端已经有版本号了，但是客户端每次还是全量拉一遍', { source: SOURCE, goal: GOAL })
  assert.equal(r.code, 'no-compress')
  assert.equal(r.data, undefined)
})

test('B7 另起一件说（和原文几乎不重合）→ 跑题', () => {
  // 必须带上目标里那个词，否则会被 B5 挡住、走不到重合率这一步；
  // 而只要带上「版本号」，短答案的重合率就很难低到 0.2 以下——所以这条用例的答案要长一点、话题要远一点。
  const r = judgeBrief('版本号之外的东西先搁着，今晚得去球场溜达一圈', { source: SOURCE, goal: '版本号' })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'off-topic')
})

test('B8 正规的压缩不会被误判成照抄（这条是校准过的）', () => {
  // 压缩天然要复用原文的词，所以「重合率高」本身不是罪；这条钉住上限别定得太紧。
  for (const s of ['增量同步卡住，怀疑订阅和本地缓存', '增量同步要先确认订阅和本地版本号']) {
    const r = judgeBrief(s, { source: SOURCE, goal: GOAL })
    assert.equal(r.ok, true, s + ' → ' + r.code)
  }
})

test('B9 目标为空时不拿「没有共同关键词」去卡人', () => {
  const r = judgeBrief('增量同步只拉变化的部分，先查订阅', { source: SOURCE, goal: '' })
  assert.equal(r.code, 'pass')
})

// ── 预判 ──────────────────────────────────────────────────────────────────────

test('P1 数字断言 → 过', () => {
  assert.equal(judgePredict('我猜 30 分钟能做完，依据是上次用了 25 分钟').code, 'pass')
})

test('P2 中文数字带单位也算数字', () => {
  assert.equal(judgePredict('我猜三次就够').code, 'pass')
})

test('P3 先后也算可核对', () => {
  assert.equal(judgePredict('先跑一遍再改，之后看日志').code, 'pass')
})

test('P4 是非也算', () => {
  assert.equal(judgePredict('我猜这局能赢').code, 'pass')
})

test('P5 「大概会好一点吧，感觉是这样」不算断言——这正是要治的病', () => {
  const r = judgePredict('我猜大概会好一点吧，感觉是这样')
  assert.equal(r.ok, false)
  assert.equal(r.code, 'no-assertion')
})

test('P6 但带具体数字时允许「大概」', () => {
  assert.equal(judgePredict('大概 30 分钟能做完').code, 'pass')
})

test('P7 空答案', () => {
  assert.equal(judgePredict('').code, 'empty')
})

test('P8 纯感想，一个可核对的词都没有', () => {
  assert.equal(judgePredict('这次应该还不错，挺顺利的').code, 'no-assertion')
})

test('P9 hasAssertion 是纯函数，三种断言各自独立成立', () => {
  assert.equal(hasAssertion('五成把握'), true)
  assert.equal(hasAssertion('会通过'), true)
  assert.equal(hasAssertion('之后再说'), true)
  assert.equal(hasAssertion(''), false)
})

// ── 提问 ──────────────────────────────────────────────────────────────────────

test('A1 一个问题、不是是非题、咬住卡上的内容 → 过', () => {
  const r = judgeAsk('订阅解绑之后，本地还需要保留哪几个字段？', { cardWords: CARD })
  assert.equal(r.ok, true, r.text)
  assert.equal(r.data.hit >= 1, true)
})

test('A2 两个问句 → 不认（只许问一个）', () => {
  assert.equal(judgeAsk('订阅怎么查？版本号存哪？', { cardWords: CARD }).code, 'not-one-question')
})

test('A3 一个问号都没有 → 不是提问', () => {
  assert.equal(judgeAsk('订阅要去查一下', { cardWords: CARD }).code, 'not-one-question')
})

test('A4 是非题不认：问完只剩两个答案', () => {
  assert.equal(judgeAsk('订阅是不是没解绑？', { cardWords: CARD }).code, 'yes-no')
  assert.equal(judgeAsk('那要不要回滚？', { cardWords: CARD }).code, 'yes-no')
})

test('A5 空答案', () => {
  assert.equal(judgeAsk('   ', { cardWords: CARD }).code, 'empty')
})

test('A6 卡上什么都没有 → 这一问无从咬起', () => {
  assert.equal(judgeAsk('订阅怎么查？', { cardWords: '' }).code, 'no-card')
})

test('A7 问了但是和卡无关 → 不认', () => {
  assert.equal(judgeAsk('下一步该做什么？', { cardWords: CARD }).code, 'no-card-word')
})

test('A8 全角问号也算问句', () => {
  assert.equal(questionCount('订阅怎么查？'), 1)
  assert.equal(questionCount('订阅怎么查?版本号存哪？'), 2)
  assert.equal(questionCount('没有问号'), 0)
})

test('A9 问句里带着卡上的词，问的是能改变答案的那一个', () => {
  const r = judgeAsk('订阅重订之前，本地版本号该存到哪一层？', { cardWords: '本地版本号;订阅重订' })
  assert.equal(r.ok, true, r.text)
})

// ── 统一入口 ──────────────────────────────────────────────────────────────────

test('J1 judgeOp 只认四个操作，别的如实拒', () => {
  assert.equal(judgeOp('nope', 'x').code, 'bad-op')
  assert.equal(judgeOp('', 'x').code, 'bad-op')
  assert.equal(judgeOp('predict', '我猜 3 分钟').code, 'pass')
})

test('J2 四个操作都有名字、问法、合格线、素材来源', () => {
  for (const op of OP_ORDER) {
    const meta = OP_META[op]
    assert.equal(typeof meta.name, 'string')
    assert.equal(meta.name.length > 0, true)
    assert.equal(meta.ask.length > 0, true)
    assert.equal(meta.pass.length > 0, true)
    assert.equal(meta.source.length > 0, true)
  }
  assert.equal(Object.keys(OP_META).length, OP_ORDER.length)
})

test('J3 opLines 给的是「这一球练什么」+ 合格线，不掺别的', () => {
  const lines = opLines('recall')
  assert.equal(lines.length, 2)
  assert.equal(lines[0].includes('回想'), true)
  assert.equal(lines[1].includes('代码判'), true)
  assert.deepEqual(opLines('nope'), [])
})

// ── 链路顺序 ──────────────────────────────────────────────────────────────────

test('L1 链路是固定顺序：回想 → 精简 → 预判 → 提问', () => {
  assert.deepEqual(OP_ORDER, ['recall', 'brief', 'predict', 'ask'])
})

test('L2 今天什么都没过 → 从第一步开始', () => {
  assert.equal(nextOp([], 0), 'recall')
})

test('L3 上一步今天没过，就不出下一步', () => {
  const entries = [row('recall', false, 100), row('brief', true, 101)]
  // brief 过了也没用：回想没过，链路还卡在第一步。
  assert.equal(nextOp(entries, 0), 'recall')
})

test('L4 过了第一步 → 出第二步', () => {
  assert.equal(nextOp([row('recall', true, 100)], 0), 'brief')
})

test('L5 四步全过 → 今天不出题了', () => {
  const entries = OP_ORDER.map((op, i) => row(op, true, 100 + i))
  assert.equal(nextOp(entries, 0), null)
})

test('L6 昨天的成绩不算今天（窗口外一律忽略）', () => {
  const entries = [row('recall', true, 100)]
  assert.equal(nextOp(entries, 200), 'recall')
})

test('L7 脏记录撑得住：op 不认识就不影响链路', () => {
  const entries = [{ state: 'drill-op', op: 'nope', ok: true, at: 100 }, { state: 'drill-op', ok: true, at: 100 }, null]
  assert.equal(nextOp(entries, 0), 'recall')
})

// ── 每日额度 ──────────────────────────────────────────────────────────────────

test('Q1 一天合计 ≤ 6 题', () => {
  assert.equal(OP_DAILY_MAX, 6)
  const entries = [row('recall', true, 100), row('brief', false, 101)]
  const q = opQuota(entries, 0)
  assert.equal(q.used, 2)
  assert.equal(q.max, 6)
  assert.equal(q.left, 4)
})

test('Q2 用完了就是 0，不给负数', () => {
  const entries = Array.from({ length: 9 }, (_, i) => row('recall', false, 100 + i))
  const q = opQuota(entries, 0)
  assert.equal(q.used, 9)
  assert.equal(q.left, 0)
})

test('Q3 窗口外的题不占今天的额度', () => {
  const entries = Array.from({ length: 9 }, (_, i) => row('recall', false, 100 + i))
  assert.equal(opQuota(entries, 200).left, 6)
})

// ── 进度 ──────────────────────────────────────────────────────────────────────

test('G1 每个操作近 14 天的通过率', () => {
  const entries = [
    row('recall', true, 100), row('recall', true, 101), row('recall', false, 102), row('recall', false, 103),
    row('brief', true, 104),
  ]
  const s = opStats(entries, 0)
  assert.equal(s.recall.asked, 4)
  assert.equal(s.recall.passed, 2)
  assert.equal(s.recall.rate, 0.5)
  assert.equal(s.brief.asked, 1)
  assert.equal(s.brief.rate, 1)
})

test('G2 没练过的操作 rate 是 0，不是 1——没练过 ≠ 满分', () => {
  const s = opStats([], 0)
  for (const op of OP_ORDER) {
    assert.equal(s[op].asked, 0)
    assert.equal(s[op].rate, 0)
  }
})

test('G3 窗口外的记录不进进度表', () => {
  const s = opStats([row('recall', true, 100)], 200)
  assert.equal(s.recall.asked, 0)
})

test('G4 不认识的 op 不污染这张表', () => {
  const s = opStats([row('nope', true, 100)], 0)
  assert.equal(Object.keys(s).length, OP_ORDER.length)
  assert.equal(s.recall.asked, 0)
})

// ── 两个方向的重合率 ──────────────────────────────────────────────────────────

test('S1 shareOf 是「a 有多少能在 b 里找到」，coverOf 是反过来', () => {
  assert.equal(shareOf('增量同步', '增量同步卡住'), 1)
  // 反过来：b 里多出来那些词，a 没覆盖到。
  assert.equal(coverOf('增量同步', '增量同步卡住'), coverOf('增量同步', '增量同步卡住'))
  assert.equal(coverOf('增量同步', '增量同步卡住') < 1, true)
  assert.equal(coverOf('增量同步卡住', '增量同步'), 1)
})

test('S2 空串不给 NaN 也不给 1（除零要挡住）', () => {
  assert.equal(shareOf('', '增量同步'), 0)
  assert.equal(coverOf('增量同步', ''), 0)
  assert.equal(Number.isNaN(shareOf('', '')), false)
  assert.equal(Number.isNaN(coverOf('', '')), false)
})
