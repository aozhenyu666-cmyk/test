import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  build7dQuizStats,
  buildPracticeSession,
  cleanBankUnlockState,
  getWrongBookList,
  judgeQuizAnswer,
  recordPracticeAnswer,
  toggleQuestionStar,
  WRONG_BOOK_GRADUATE_STREAK,
} from '../lib/bank_unlock.js'
import { AGAIN_DELAY_MS } from '../lib/fsrs.js'

const T0 = Date.parse('2026-11-22T02:00:00Z') // 10:00 Asia/Shanghai
const DAY = 86_400_000

function makeCatalog() {
  return {
    v: 2,
    banks: [
      {
        id: 'b-main',
        name: '行测核心库',
        enabled: true,
        questions: [
          {
            id: 'b-main:q1',
            kind: 'mcq',
            题型: '数量关系',
            题干: '某产品今年销量增长 25%，去年为 400 件，今年是多少件？',
            选项: ['450 件', '500 件', '520 件', '550 件'],
            答案: 'B',
            解析: '400 × 1.25 = 500 件',
            images: [{ path: 'assets/q1.png', role: 'stem', caption: '销量柱图' }],
          },
          {
            id: 'b-main:q2',
            kind: 'mcq',
            题型: '判断推理',
            题干: '下列哪组词逻辑关系与「笔：写字」最一致？',
            选项: ['刀：切菜', '书：阅读', '灯：开关', '碗：筷子'],
            答案: 'A',
            解析: '工具与用途的对应关系',
          },
          {
            id: 'b-main:q3',
            kind: 'card',
            题型: '知识卡',
            题干: '【核心规律】基期比重与现期比重差值公式：A/B × (a-b)/(1+a)',
            答案: '记住 (a-b) 符号定升降',
            解析: '部分增速 a > 整体增速 b 则比重上升',
          },
          {
            id: 'b-main:q4',
            kind: 'short_qa',
            题型: '小问题',
            题干: '要点：先排除绝对化表述。\n思考：遇到「完全」「彻底」选项时如何核验？',
            答案: '回到原文定位句找是否有范围限定词或反例',
            解析: '绝对化措辞往往偷换范围',
          },
        ],
      },
      {
        id: 'b-off',
        name: '已停用专项库',
        enabled: false,
        questions: [
          {
            id: 'b-off:q5',
            kind: 'mcq',
            题型: '常识',
            题干: '已停用套库里的一道题目内容足够长？',
            选项: ['甲项', '乙项'],
            答案: 'A',
            解析: '甲项正确',
          },
        ],
      },
    ],
  }
}

test('buildPracticeSession：分卷/题型筛选、严格去重、优先级排序与跨批不重复', () => {
  const catalog = makeCatalog()

  // 1) 未指定 bankId 时只抽启用套库（4 题），绝不包含停用库与重复题
  const sAll = buildPracticeSession(catalog, null, { limit: 10, now: T0, rng: () => 0.3 })
  assert.equal(sAll.ok, true)
  assert.equal(sAll.count, 4)
  assert.equal(new Set(sAll.questions.map((q) => q.id)).size, 4)
  assert.ok(sAll.questions.every((q) => q.bankId === 'b-main'))

  // 检查字段完整性
  const q1 = sAll.questions.find((q) => q.id === 'b-main:q1')
  assert.equal(q1.bankName, '行测核心库')
  assert.equal(q1.kind, 'mcq')
  assert.equal(q1.options.length, 4)
  assert.equal(q1.answer, 'B')
  assert.match(q1.explain, /500/)
  assert.equal(q1.images.length, 1)
  assert.equal(q1.starred, false)
  assert.equal(q1.inWrongBook, false)
  assert.equal(q1.streakCorrect, 0)
  assert.equal(q1.dueAt, 0)

  // 2) 指定 bankId 可单刷指定套库（哪怕当前处于停用状态）
  const sSingleBank = buildPracticeSession(catalog, null, { bankId: 'b-off', now: T0 })
  assert.equal(sSingleBank.count, 1)
  assert.equal(sSingleBank.questions[0].id, 'b-off:q5')

  // 3) 按 kind 筛选（mcq / card / short_qa）
  assert.deepEqual(
    buildPracticeSession(catalog, null, { kind: 'mcq', now: T0 }).questions.map((q) => q.kind),
    ['mcq', 'mcq'],
  )
  assert.equal(buildPracticeSession(catalog, null, { kind: 'card', now: T0 }).questions[0].id, 'b-main:q3')
  assert.equal(buildPracticeSession(catalog, null, { kind: 'short_qa', now: T0 }).questions[0].id, 'b-main:q4')

  // 4) 连续两轮抽取 limit=2：第一批答完后，第二批必须抽出剩余未刷题，绝不原地重复吐第一批
  const batch1 = buildPracticeSession(catalog, null, { limit: 2, now: T0, rng: () => 0.1 })
  assert.equal(batch1.count, 2)
  let st = null
  for (const q of batch1.questions) {
    const rec = recordPracticeAnswer(catalog, st, {
      questionId: q.id,
      answer: q.kind === 'mcq' ? q.answer : 'KNOWN',
      elapsedMs: 4200,
      now: T0 + 5000,
    })
    assert.equal(rec.ok, true)
    st = rec.state
  }
  const batch2 = buildPracticeSession(catalog, st, { limit: 2, now: T0 + 10_000, rng: () => 0.1 })
  assert.equal(batch2.count, 2)
  const overlap = batch2.questions.filter((q) => batch1.questions.some((b) => b.id === q.id))
  assert.equal(overlap.length, 0, '未刷完前第二批绝不重复第一批已刷题')

  // 5) 全刷过后严格按：unseen → fsrs_due → inWrongBook → 最久未刷 排序
  const orderedState = cleanBankUnlockState({
    wrongBookIds: ['b-main:q2'],
    questionStats: {
      'b-main:q1': { seenCount: 1, correctCount: 1, wrongCount: 0, streakCorrect: 1, lastSeenAt: T0 - 2 * DAY, fsrs: { dueAt: T0 + 3 * DAY, reps: 1, stability: 3, difficulty: 5, lapses: 0 } },
      'b-main:q2': { seenCount: 2, correctCount: 0, wrongCount: 2, streakCorrect: 0, lastSeenAt: T0 - 1000, inWrongBook: true, fsrs: { dueAt: T0 + 60_000, reps: 2, stability: 1, difficulty: 6, lapses: 1 } },
      'b-main:q3': { seenCount: 1, correctCount: 1, wrongCount: 0, streakCorrect: 1, lastSeenAt: T0 - 3 * DAY, fsrs: { dueAt: T0 - 60_000, reps: 1, stability: 1, difficulty: 5, lapses: 0 } },
      // q4 unseen
    },
  })
  const prioritized = buildPracticeSession(catalog, orderedState, { limit: 4, now: T0 })
  assert.deepEqual(
    prioritized.questions.map((q) => q.id),
    ['b-main:q4', 'b-main:q3', 'b-main:q2', 'b-main:q1'],
    '顺序应为：未刷(q4) → FSRS已到期(q3) → 错题本(q2) → 其余最久未刷(q1)',
  )
})

test('recordPracticeAnswer：错题自动入本、连续答对 >= 2 次毕业、FSRS 调度与锁屏状态隔离', () => {
  const catalog = makeCatalog()
  const initState = {
    config: { requiredCount: 10, unlockMinutes: 5 },
    session: { solved: 3, currentId: 'other-lock-id' },
  }

  // 1) 答错 q1（哪怕锁屏当前停在 other-lock-id 也能独立专练）→ 收入错题本，streak=0，FSRS 10 分钟后再问
  const wrongRes = recordPracticeAnswer(catalog, initState, {
    questionId: 'b-main:q1',
    answer: 'A',
    elapsedMs: 6500,
    now: T0,
  })
  assert.equal(wrongRes.ok, true)
  assert.equal(wrongRes.correct, false)
  assert.equal(wrongRes.streakCorrect, 0)
  assert.equal(wrongRes.inWrongBook, true)
  assert.equal(wrongRes.addedToWrongBook, true)
  assert.equal(wrongRes.graduatedFromWrongBook, false)
  assert.equal(wrongRes.nextDueAt, T0 + AGAIN_DELAY_MS)
  assert.equal(wrongRes.fsrs.lapses, 1)
  assert.equal(wrongRes.state.session.solved, 3, '专练不影响锁屏解锁计数')
  assert.ok(wrongRes.state.wrongBookIds.includes('b-main:q1'))
  assert.ok(wrongRes.state.wrongIds.includes('b-main:q1'))

  // 2) 错题第 1 次答对：streak=1 < 2，仍留在错题本，不毕业！
  assert.equal(WRONG_BOOK_GRADUATE_STREAK, 2)
  const right1 = recordPracticeAnswer(catalog, wrongRes.state, {
    questionId: 'b-main:q1',
    answer: 'B',
    rating: 'good',
    elapsedMs: 3100,
    now: T0 + 15 * 60_000,
  })
  assert.equal(right1.correct, true)
  assert.equal(right1.streakCorrect, 1)
  assert.equal(right1.inWrongBook, true)
  assert.equal(right1.graduatedFromWrongBook, false)
  assert.ok(right1.state.wrongBookIds.includes('b-main:q1'))
  assert.ok(right1.scheduledDays >= 1)

  // 中途再错一次：连续次数清零
  const relapse = recordPracticeAnswer(catalog, right1.state, {
    questionId: 'b-main:q1',
    answer: 'C',
    now: T0 + 20 * 60_000,
  })
  assert.equal(relapse.streakCorrect, 0)
  assert.equal(relapse.inWrongBook, true)

  // 重新连续答对 2 次：第 1 次不毕业，第 2 次自动毕业移出错题本！
  const recover1 = recordPracticeAnswer(catalog, relapse.state, {
    questionId: 'b-main:q1',
    answer: 'B',
    now: T0 + 35 * 60_000,
  })
  assert.equal(recover1.streakCorrect, 1)
  assert.equal(recover1.inWrongBook, true)

  const recover2 = recordPracticeAnswer(catalog, recover1.state, {
    questionId: 'b-main:q1',
    answer: 'B',
    rating: 'easy',
    now: T0 + 2 * DAY,
  })
  assert.equal(recover2.streakCorrect, 2)
  assert.equal(recover2.inWrongBook, false)
  assert.equal(recover2.graduatedFromWrongBook, true)
  assert.equal(recover2.state.wrongBookIds.includes('b-main:q1'), false)
  assert.equal(recover2.state.wrongIds.includes('b-main:q1'), false)
  assert.ok(recover2.scheduledDays > recover1.scheduledDays)

  // 3) 自评卡片（card & short_qa）与 FSRS Easy/Hard 调度
  const cardRes = recordPracticeAnswer(catalog, recover2.state, {
    questionId: 'b-main:q3',
    answer: 'KNOWN',
    rating: 'easy',
    now: T0 + 2 * DAY + 1000,
  })
  assert.equal(cardRes.correct, true)
  assert.equal(cardRes.selfJudged, true)
  assert.equal(cardRes.state.stats.selfPassed, 1)
  assert.ok(cardRes.fsrs.dueAt > T0 + 2 * DAY)

  const qaBad = recordPracticeAnswer(catalog, cardRes.state, {
    questionId: 'b-main:q4',
    answer: '没答上',
    now: T0 + 2 * DAY + 2000,
  })
  assert.equal(qaBad.correct, false)
  assert.equal(qaBad.inWrongBook, true)
  const wrongList = getWrongBookList(catalog, qaBad.state, { now: T0 + 2 * DAY + 3000 })
  assert.equal(wrongList.total, 1)
  assert.equal(wrongList.items[0].id, 'b-main:q4')
})

test('toggleQuestionStar + dailyHistory 30 天滚动 + build7dQuizStats 7 日统计', () => {
  const catalog = makeCatalog()

  // 收藏与取消收藏，以及 scope='starred'
  const starOn = toggleQuestionStar(null, 'b-main:q2')
  assert.equal(starOn.ok, true)
  assert.equal(starOn.starred, true)
  assert.equal(starOn.starredCount, 1)

  const starredSession = buildPracticeSession(catalog, starOn.state, { scope: 'starred', now: T0 })
  assert.equal(starredSession.count, 1)
  assert.equal(starredSession.questions[0].id, 'b-main:q2')
  assert.equal(starredSession.questions[0].starred, true)

  const starOff = toggleQuestionStar(starOn.state, 'b-main:q2')
  assert.equal(starOff.starred, false)
  assert.equal(starOff.starredCount, 0)

  // 构造 35 天历史，验证 cleanBankUnlockState 只保留最近 30 天
  const manyDays = {}
  for (let i = 0; i < 35; i += 1) {
    const d = new Date(Date.UTC(2026, 9, 19 + i)).toISOString().slice(0, 10) // 2026-10-19 .. 2026-11-22
    manyDays[d] = { answered: 2, correct: 1, selfPassed: 0 }
  }
  const cleaned = cleanBankUnlockState({ stats: { dailyHistory: manyDays } })
  assert.equal(Object.keys(cleaned.stats.dailyHistory).length, 30)
  assert.equal(cleaned.stats.dailyHistory['2026-10-19'], undefined, '超过 30 天的早期记录应裁掉')
  assert.ok(cleaned.stats.dailyHistory['2026-11-22'])

  // 真实跨日作答 + 星标 + 错题 + FSRS 到期统计
  let st = toggleQuestionStar(null, 'b-main:q1', true).state
  // 昨天答错 q2 -> 10 分钟后即已到期（到今天肯定 FSRS 到期）
  st = recordPracticeAnswer(catalog, st, {
    questionId: 'b-main:q2',
    answer: 'B',
    now: T0 - DAY,
  }).state
  // 今天答对 q1（MCQ）和 q3（card）
  st = recordPracticeAnswer(catalog, st, {
    questionId: 'b-main:q1',
    answer: 'B',
    now: T0,
  }).state
  st = recordPracticeAnswer(catalog, st, {
    questionId: 'b-main:q3',
    answer: 'KNOWN',
    now: T0 + 1000,
  }).state

  const stats7d = build7dQuizStats(st, T0 + 2000, 'Asia/Shanghai', catalog)
  assert.equal(stats7d.days.length, 7)
  assert.equal(stats7d.today, '2026-11-22')
  assert.equal(stats7d.todayAnswered, 2)
  assert.equal(stats7d.todayCorrect, 1)
  assert.equal(stats7d.todaySelfPassed, 1)
  assert.equal(stats7d.todayAccuracyPct, 100)
  assert.equal(stats7d.totalAnswered7d, 3)
  assert.equal(stats7d.accuracy7d, 67)
  assert.equal(stats7d.starredCount, 1)
  assert.equal(stats7d.wrongBookCount, 1)
  assert.equal(stats7d.fsrsDueCount, 1, '昨天答错的 q2 已经过 24h，应计入 FSRS 待复习')

  const fsrsDueSession = buildPracticeSession(catalog, st, { scope: 'fsrs_due', now: T0 + 2000 })
  assert.equal(fsrsDueSession.count, 1)
  assert.equal(fsrsDueSession.questions[0].id, 'b-main:q2')
})

test('向后兼容：老 state 只有 wrongIds 时仍接入 wrongBook，锁屏与专练协同不破坏老契约', () => {
  const legacy = cleanBankUnlockState({
    wrongIds: ['q-old'],
    questionStats: {
      'q-old': { seenCount: 1, correctCount: 0, wrongCount: 1, lastSeenAt: T0 - 5000 },
    },
  })
  assert.equal(legacy.questionStats['q-old'].inWrongBook, true)
  assert.deepEqual(legacy.wrongBookIds, ['q-old'])
  assert.equal(legacy.questionStats['q-old'].streakCorrect, 0)
  assert.equal(legacy.questionStats['q-old'].starred, false)
  assert.equal(legacy.questionStats['q-old'].fsrs.dueAt, 0)

  // 锁屏 judgeQuizAnswer 答错也同步进 wrongBook 与 dailyHistory
  const bank = { 题目: [{ id: 'q1', 题干: '锁屏错题测试', 选项: ['A', 'B'], 答案: 'B', 解析: 'p' }] }
  const wrongLock = judgeQuizAnswer(bank, { session: { currentId: 'q1' } }, { questionId: 'q1', answer: 'A', now: T0 })
  assert.equal(wrongLock.state.questionStats.q1.inWrongBook, true)
  assert.ok(wrongLock.state.wrongBookIds.includes('q1'))
  assert.equal(wrongLock.state.stats.dailyHistory['2026-11-22'].answered, 1)
})

test('recordPracticeAnswer：独立累计专练轮次、达标触发 roundComplete 且不污染锁屏 session', () => {
  const catalog = makeCatalog()
  let st = cleanBankUnlockState({
    config: { requiredCount: 2, unlockMinutes: 5 },
    session: { solved: 4, currentId: 'lock-q' },
  })
  const r1 = recordPracticeAnswer(catalog, st, { questionId: 'b-main:q1', answer: 'B', now: T0 })
  assert.equal(r1.ok, true)
  assert.equal(r1.practiceSolved, 1)
  assert.equal(r1.roundComplete, false)
  assert.equal(r1.state.practiceSession.solved, 1)
  assert.equal(r1.state.session.solved, 4, '专练进度不得篡改锁屏解锁进度')

  const r2 = recordPracticeAnswer(catalog, r1.state, { questionId: 'b-main:q2', answer: 'A', now: T0 + 2000 })
  assert.equal(r2.ok, true)
  assert.equal(r2.practiceSolved, 2)
  assert.equal(r2.roundComplete, true)
  assert.equal(r2.unlockMinutes, 5)
  assert.equal(r2.state.practiceSession.solved, 0, '满轮次后专练轮次清零重开')
  assert.equal(r2.state.session.solved, 4, '达标后依然不影响锁屏解锁计数')
})
