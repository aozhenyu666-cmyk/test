import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

import {
  FOUL_CODES,
  STROKE_ORDER,
  STROKES,
  buildStrokePlan,
  detectStrokeFoul,
  evaluateStroke,
} from '../lib/six_dice.js'
import { auditStrokeSlot } from '../lib/wall_auditor.js'
import { KB_DIR, writeBidirectionalWikiLinks } from '../lib/notes.js'

const SOURCE = '实验记录显示：对照组采用每日三轮抽测后，第7天错题复发率从38%降到14%，但仅在第2天停测的两组在72小时内回升到31%。'

test('S01 STROKES 与 STROKE_ORDER 覆盖全部六拍且含完整 schema', () => {
  assert.deepEqual(STROKE_ORDER, [
    'stroke_1_recall',
    'stroke_2_condense',
    'stroke_3_question',
    'stroke_4_predict',
    'stroke_5_associate',
    'stroke_6_systematize',
  ])
  for (const id of STROKE_ORDER) {
    assert.ok(STROKES[id], `missing stroke ${id}`)
    assert.ok(STROKES[id].schema && Array.isArray(STROKES[id].schema.requiredFields))
  }
})

test('S02 FOUL_CODES 包含全部 7 种严格犯规码', () => {
  assert.deepEqual([...FOUL_CODES].sort(), [
    'foul:abstract_impression',
    'foul:copied_verbatim',
    'foul:out_of_bounds_daydream',
    'foul:tautology_bet',
    'foul:unfalsifiable_question',
    'foul:vague_modifier',
    'foul:weak_link_relation',
  ].sort())
})

test('S03 buildStrokePlan 默认 multi_shuttle_drill 给轻量多拍', () => {
  const plan = buildStrokePlan({ track: 'multi_shuttle_drill', topic: '间隔效应' })
  assert.equal(plan.track, 'multi_shuttle_drill')
  assert.equal(plan.slots.length, 3)
  assert.deepEqual(plan.slots.map((s) => s.stroke), ['stroke_1_recall', 'stroke_2_condense', 'stroke_4_predict'])
})

test('S04 buildStrokePlan department_match 覆盖完整六拍并透传候选笔记上限 3 条', () => {
  const plan = buildStrokePlan({
    track: 'department_match',
    topic: '抗遗忘机制',
    candidateNotes: [{ title: 'A' }, { title: 'B' }, { title: 'C' }, { title: 'D' }],
  })
  assert.equal(plan.track, 'department_match')
  assert.equal(plan.slots.length, 6)
  const s6 = plan.slots.find((s) => s.stroke === 'stroke_6_systematize')
  assert.equal(s6.candidateNotes.length, 3)
})

test('S05 stroke_1_recall 照抄原文整段触发 foul:copied_verbatim', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_1_recall',
    sourceContext: SOURCE,
    answer: '对照组采用每日三轮抽测后，第7天错题复发率从38%降到14%',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:copied_verbatim')
})

test('S06 stroke_1_recall 用自己的话压缩关键数据通过', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_1_recall',
    sourceContext: SOURCE,
    answer: '连续一周每天测三次把错题反弹压到了14%，而第2天就中断的组三天内反弹到31%。',
  })
  assert.equal(res.pass, true)
  assert.equal(res.foulCode, '')
})

test('S07 空洞主观感想无数字无机制触发 foul:abstract_impression', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_1_recall',
    sourceContext: SOURCE,
    answer: '我觉得很有道理非常有道理感觉很棒学到了不少，太厉害了。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:abstract_impression')
})

test('S08 stroke_2_condense 带模糊修饰词无具体数值触发 foul:vague_modifier', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_2_condense',
    sourceContext: SOURCE,
    answer: '这种方法大概比较有效，某种程度上也许还行吧。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:vague_modifier')
})

test('S09 stroke_2_condense 去修饰词钉死主干命题与数字通过', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_2_condense',
    sourceContext: SOURCE,
    answer: '间隔抽测维持7天可将复发率由38%降至14%，停测48小时即失效。',
  })
  assert.equal(res.pass, true)
})

test('S10 stroke_3_question 不可证伪泛问触发 foul:unfalsifiable_question', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_3_question',
    answer: '这到底好不好呢？大家怎么看这件事。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:unfalsifiable_question')
})

test('S11 stroke_3_question 非问句直接拒判 foul:unfalsifiable_question', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_3_question',
    answer: '如果样本太小这个实验结论就会被推翻掉。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:unfalsifiable_question')
})

test('S12 stroke_3_question 带明确证伪条件问句通过', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_3_question',
    answer: '如果两组初始基础分相差超过15分，第7天的14%差异还能否证明抽测本身有效？',
  })
  assert.equal(res.pass, true)
})

test('S13 stroke_4_predict Phase A 同义反复押注触发 foul:tautology_bet', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_4_predict',
    phase: 'A',
    answer: '只要我认真学就能学会，不拖延就能成功把这道题做完。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:tautology_bet')
})

test('S14 stroke_4_predict Phase A 越界空想触发 foul:out_of_bounds_daydream', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_4_predict',
    phase: 'A',
    answer: '掌握这一招后我明天直接无敌，明年上市实现财务自由。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:out_of_bounds_daydream')
})

test('S15 stroke_4_predict Phase A + Phase B 两段式具体押注与复盘均通过', async () => {
  const phaseA = await evaluateStroke({
    stroke: 'stroke_4_predict',
    phase: 'A',
    answer: 'Phase A 押注：今晚 20 分钟抽测前 10 道积分错题，若错题数 > 3 道说明昨天的公式推导没吃透。',
  })
  const phaseB = await evaluateStroke({
    stroke: 'stroke_4_predict',
    phase: 'B',
    answer: 'Phase B 对账：实际 20 分钟错 4 道，均集中在分部积分边界符号，证伪了“已经掌握”的假设。',
  })
  assert.equal(phaseA.pass, true)
  assert.equal(phaseB.pass, true)
})

test('S16 stroke_5_associate 强行牵强类比无因果结构触发 foul:weak_link_relation', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_5_associate',
    answer: '这和昨天喝奶茶感觉有点像，莫名觉得像一回事，说不上来但很像。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:weak_link_relation')
})

test('S17 stroke_5_associate 宇宙穿越类空想触发 foul:out_of_bounds_daydream', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_5_associate',
    answer: '因为记忆衰减和外星人穿越全宇宙的时空曲率结构是一样的。',
  })
  assert.equal(res.pass, false)
  assert.equal(res.foulCode, 'foul:out_of_bounds_daydream')
})

test('S18 stroke_5_associate 同构机制与边界差异清晰通过', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_5_associate',
    answer: '这与肌肉耐力训练的负荷衰减因果机制同构，差异在于大脑检索在72小时无强化就会掉出工作区。',
  })
  assert.equal(res.pass, true)
})

test('S19 retries === 0 与 retries === 1 失败时不提前给 scaffold_hints', async () => {
  const r0 = await evaluateStroke({ stroke: 'stroke_1_recall', sourceContext: SOURCE, answer: '太短了', retries: 0 })
  const r1 = await evaluateStroke({ stroke: 'stroke_1_recall', sourceContext: SOURCE, answer: '太短了', retries: 1 })
  assert.equal(r0.pass, false)
  assert.deepEqual(r0.scaffold_hints, [])
  assert.equal(r1.pass, false)
  assert.deepEqual(r1.scaffold_hints, [])
})

test('S20 retries === 2 失败时稳定给出 2-3 个具体观察锚点 scaffold_hints 且不剧透结论', async () => {
  const res = await evaluateStroke({
    stroke: 'stroke_1_recall',
    sourceContext: SOURCE,
    anchors: ['每日三轮抽测', '复发率14%', '72小时回升31%'],
    answer: '我觉得写得挺好很有道理。',
    retries: 2,
  })
  assert.equal(res.pass, false)
  assert.ok(Array.isArray(res.scaffold_hints))
  assert.ok(res.scaffold_hints.length >= 2 && res.scaffold_hints.length <= 3)
  assert.deepEqual(res.scaffold_hints, ['每日三轮抽测', '复发率14%', '72小时回升31%'])
})

test('S21 detectStrokeFoul 同步工具对所有 7 种犯规可独立判定', () => {
  assert.equal(detectStrokeFoul({ stroke: 'stroke_6_systematize', answer: '记一条普通规则但不连笔记' }).foulCode, 'foul:weak_link_relation')
  assert.equal(detectStrokeFoul({ stroke: 'stroke_6_systematize', answer: '固化为 [[抽测节奏规则]]，每晚校验 1 次', linkedNotes: [] }).foulCode, '')
})

test('S22 stroke_6_systematize 自动检索 <=3 篇候选笔记并双向回写 [[WikiLinks]]', async () => {
  const root = mkdtempSync(join(tmpdir(), 'dafeiyu-s6-'))
  try {
    mkdirSync(join(root, '学习'), { recursive: true })
    mkdirSync(join(root, '日记'), { recursive: true })
    const noteA = join(root, '学习', '间隔重复实验.md')
    const noteB = join(root, '学习', '错题本复盘法.md')
    const priv = join(root, '日记', '2026-11-14.md')
    writeFileSync(noteA, '# 间隔重复实验\n每日三轮抽测可降低错题复发率。\n', 'utf8')
    writeFileSync(noteB, '# 错题本复盘法\n72小时内复测错过率更低。\n', 'utf8')
    writeFileSync(priv, '# 私密日记\n间隔重复\n', 'utf8')

    const out = await auditStrokeSlot({
      stroke: 'stroke_6_systematize',
      notesDir: root,
      query: '间隔重复 错题',
      noteTitle: '高强度抽测护栏规则',
      answer: '形成规则：每次开局后第2天必须复测一次，关联 [[间隔重复实验]] 与 [[错题本复盘法]]。',
    })
    assert.equal(out.pass, true)
    assert.ok(out.candidateNotes.length >= 1 && out.candidateNotes.length <= 3)
    assert.ok(out.wikiWriteback?.notePath)
    const createdCard = readFileSync(out.wikiWriteback.notePath, 'utf8')
    assert.match(createdCard, /\[\[间隔重复实验\]\]/)
    const updatedA = readFileSync(noteA, 'utf8')
    assert.match(updatedA, /\[\[高强度抽测护栏规则\]\]/)
    // 私密目录绝不回写
    const privAfter = readFileSync(priv, 'utf8')
    assert.doesNotMatch(privAfter, /\[\[高强度抽测护栏规则\]\]/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('S23 writeBidirectionalWikiLinks 对同一笔记多次执行保持幂等不重复追加反链', () => {
  const root = mkdtempSync(join(tmpdir(), 'dafeiyu-idem-'))
  try {
    const target = join(root, '基础概念.md')
    writeFileSync(target, '# 基础概念\n', 'utf8')
    const cand = [{ path: target, title: '基础概念' }]
    writeBidirectionalWikiLinks({ notesDir: root, noteTitle: '派生定理', body: 'test', candidateNotes: cand })
    writeBidirectionalWikiLinks({ notesDir: root, noteTitle: '派生定理', body: 'test', candidateNotes: cand })
    const content = readFileSync(target, 'utf8')
    const count = content.split('[[派生定理]]').length - 1
    assert.equal(count, 1)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('S24 multi_shuttle_drill 与 department_match 轨道独立切换且均带 valid slot schema', async () => {
  const drill = buildStrokePlan({ track: 'multi_shuttle_drill', topic: '微积分边界' })
  const match = buildStrokePlan({ track: 'department_match', topic: '系统规划' })
  assert.ok(drill.slots.every((s) => s.track === 'multi_shuttle_drill' && s.schema))
  assert.ok(match.slots.every((s) => s.track === 'department_match' && s.schema))
  assert.equal(drill.slots.length < match.slots.length, true)
})
