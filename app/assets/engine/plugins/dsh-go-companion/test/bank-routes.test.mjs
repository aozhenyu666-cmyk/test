// 题库导入接线：大题库分块传（引擎收请求上限 16 KB），收齐了拼起来解析；小的一次传；报告没进的题。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Readable } from 'node:stream'
import { apply, setStatePin } from '../lib/index.js'
import { GEMINI_IMPORT_PROMPT_TEMPLATE } from '../lib/bank_import.js'
import { judgeQuizAnswer, pickNextQuizQuestion, SELF_JUDGED_WEIGHT } from '../lib/bank_unlock.js'
import { minuteOfDay } from '../lib/tz.js'

const PREFIX = '/dsh-go-companion/v1'
const SIS = 'session-sis'
const BIG = 'session-big'
const TZ = 'Asia/Shanghai'

function agentOf(id) {
  const tools = {}
  return {
    id, status: 'idle', inbox: { hasPending: false }, sent: [], contexts: [], tools,
    followup(m) { this.sent.push(m) },
    ctx: {
      systemPrompt: { context() { return () => {} } },
      tools: { register(def) { tools[def.name] = def; return () => { delete tools[def.name] } } },
    },
  }
}

function host(agents) {
  let handler
  const ctx = {
    get(name) {
      if (name === 'agents') return { get: (id) => agents[id], roots: () => Object.values(agents), list: () => Object.values(agents) }
      if (name === 'connection') return { requestRejection: () => undefined }
      if (name === 'sessionController') return { resolveAgent: async (id) => ({ agent: agents[id] }) }
      if (name === 'workspaceRegistry') return { archivedSessionIds: [] }
      return undefined
    },
    on() {},
    inject(_d, cb) { cb({ webServer: { register(r) { handler = r.handler } }, effect(fn) { fn() } }) },
    effect(fn) { fn() },
  }
  setStatePin('standby')
  apply(ctx)
  return () => handler
}

function call(handler, method, path, body) {
  const req = Readable.from(body === undefined ? [] : [Buffer.from(JSON.stringify(body))])
  req.method = method
  req.url = PREFIX + path
  req.headers = { 'content-type': 'application/json' }
  const res = { statusCode: 0, text: '', writeHead(s) { this.statusCode = s }, end(c) { this.text = c ? String(c) : '' } }
  return Promise.resolve(handler(req, res)).then(() => ({ status: res.statusCode, body: res.text ? JSON.parse(res.text) : null }))
}

async function withHome(fn) {
  const home = mkdtempSync(join(tmpdir(), 'companion-bank-'))
  const old = process.env.DSH_HOME
  process.env.DSH_HOME = home
  try {
    mkdirSync(join(home, 'companion'), { recursive: true })
    writeFileSync(join(home, 'companion', 'config.json'), JSON.stringify({ sessionId: SIS, strategistSessionId: BIG, timeZone: TZ, quietStart: 12, quietEnd: 12 }))
    const h = host({ [SIS]: agentOf(SIS), [BIG]: agentOf(BIG) })
    const bank = (body) => call(h(), 'POST', '/bank', body)
    return await fn({ home, bank, h })
  } finally {
    if (old === undefined) delete process.env.DSH_HOME
    else process.env.DSH_HOME = old
    try {
      rmSync(home, { recursive: true, force: true })
    } catch {}
  }
}


const BIG_BANK = Array.from({ length: 200 }, (_, i) => `${i + 1}. 第 ${i + 1} 题：下列关于宏观经济政策的说法哪一项是正确的？请仔细阅读每个选项。\nA. 甲选项内容比较长\nB. 乙选项内容比较长\nC. 丙选项内容比较长\nD. 丁选项内容比较长`).join('\n') +
  '\n\n参考答案\n' + Array.from({ length: 40 }, (_, k) => `${k * 5 + 1}-${k * 5 + 5} ABCDA`).join('\n')

test('大题库：一次传超过 16 KB 被拒；分块传收齐了拼起来，200 题全进', async () => {
  await withHome(async ({ bank }) => {
    assert.ok(Buffer.byteLength(BIG_BANK) > 16 * 1024, '夹具要大于 16 KB')
    const once = await bank({ action: 'import', raw: BIG_BANK, name: '大' })
    assert.equal(once.status, 413, '一次传会被引擎拒')
    const begin = (await bank({ action: 'import-begin', uploadId: 'up1' })).body
    assert.equal(begin.ok, true)
    const CH = 3000
    let n = 0
    for (let i = 0; i < BIG_BANK.length; i += CH) {
      const r = await bank({ action: 'import-chunk', uploadId: 'up1', index: n++, text: BIG_BANK.slice(i, i + CH) })
      assert.equal(r.status, 200)
      assert.equal(r.body.ok, true)
    }
    const fin = (await bank({ action: 'import-finish', uploadId: 'up1', name: '行测真题' })).body
    assert.equal(fin.ok, true, JSON.stringify(fin).slice(0, 300))
    assert.equal(fin.importedCount, 200)
    assert.equal(fin.bankTotal, 200)
    assert.equal(fin.format, 'text+key')
    const st = (await bank({ action: 'status' })).body
    assert.equal(st.bankTotal, 200)
    assert.equal(st.bankName, '行测真题')
    // 再追加一份小的：编号接着排，总数相加
    const more = (await bank({ action: 'import', raw: '1. 追加题\nA. 甲\nB. 乙\n答案：A', append: true })).body
    assert.equal(more.bankTotal, 201)
    // 收完临时目录被清掉；重复 finish 说清楚
    const again = (await bank({ action: 'import-finish', uploadId: 'up1' })).body
    assert.equal(again.ok, false)
  })
})

test('没进的题逐题报出来', async () => {
  await withHome(async ({ bank }) => {
    const r = (await bank({ action: 'import', raw: '1. 有答案\nA. 甲\nB. 乙\n答案：A\n2. 没答案\nA. 甲\nB. 乙' })).body
    assert.equal(r.ok, true)
    assert.equal(r.importedCount, 1)
    assert.equal(r.skippedCount, 1)
    assert.match(r.warnings[0], /第 2 题缺答案/)
  })
})

test('多题库目录：旧 bank.json 自动迁移、list_banks/get_bank/toggle_bank/delete_question/delete_bank 与同步 bank.json', async () => {
  await withHome(async ({ home, bank }) => {
    // 1) 写入旧格式 bank.json，首次读取自动迁移为 legacy-default
    writeFileSync(join(home, 'companion', 'bank.json'), JSON.stringify({
      题库版本: 1,
      id: 'old',
      名称: '历史老库',
      题目: [
        { id: 'q-old-1', 题干: '老库第一道选择题题干足够长', 选项: ['甲', '乙'], 答案: 'A', 解析: '甲正确' },
      ],
    }))

    const list1 = (await bank({ action: 'list_banks' })).body
    assert.equal(list1.ok, true)
    assert.equal(list1.banks.length, 1)
    assert.equal(list1.banks[0].id, 'legacy-default')
    assert.equal(list1.banks[0].name, '历史老库')
    assert.equal(list1.banks[0].total, 1)

    // 2) 再导一套混合知识卡库（带 mcq + card + short_qa + 异常题）
    const mixedRaw = JSON.stringify({
      题目: [
        { kind: 'card', 题干: '【知识卡】拆解问题先定义边界与不可变契约', 解析: '防止范围蔓延' },
        { kind: 'short_qa', 题干: '小问题：为什么需要防重放机制？', 答案: '避免连点重复计分', 解析: '服务端按 currentId 校验' },
        { kind: 'mcq', 题干: '短题', 选项: ['一', '二'], 答案: 'A', 解析: '' },
      ],
    })
    const imp2 = (await bank({ action: 'import', name: '今日收集卡', raw: mixedRaw })).body
    assert.equal(imp2.ok, true)
    assert.equal(imp2.importedCount, 3)
    assert.equal(imp2.bankTotal, 4)

    const list2 = (await bank({ action: 'list_banks' })).body
    assert.equal(list2.banks.length, 2)
    const cardBank = list2.banks.find((b) => b.name === '今日收集卡')
    assert.ok(cardBank)
    assert.deepEqual(cardBank.kindCounts, { mcq: 1, card: 1, short_qa: 1 })
    assert.ok(cardBank.anomalyCount >= 1, '应统计出短题干/缺解析异常')

    // 3) get_bank 返回明细、异常原因与逐题 stats
    const detail = (await bank({ action: 'get_bank', bankId: cardBank.id })).body
    assert.equal(detail.ok, true)
    assert.equal(detail.questions.length, 3)
    assert.equal(detail.questions[0].kind, 'card')
    assert.equal(detail.questions[1].kind, 'short_qa')
    assert.ok(detail.questions[2].anomalyReasons.length >= 1)

    // 4) 停用第一套库 → bank.json 聚合同步减为 3 题；恢复后变回 4 题
    const togOff = (await bank({ action: 'toggle_bank', bankId: 'legacy-default', enabled: false })).body
    assert.equal(togOff.ok, true)
    assert.equal(togOff.bankTotal, 3)
    const syncedOff = JSON.parse(readFileSync(join(home, 'companion', 'bank.json'), 'utf8'))
    assert.equal(syncedOff.题目.length, 3)

    const togOn = (await bank({ action: 'toggle_bank', bankId: 'legacy-default', enabled: true })).body
    assert.equal(togOn.bankTotal, 4)

    // 5) 删除单题 + 删除整套库
    const delQ = (await bank({ action: 'delete_question', bankId: cardBank.id, questionId: detail.questions[2].id })).body
    assert.equal(delQ.ok, true)
    assert.equal(delQ.bankTotal, 3)

    const delB = (await bank({ action: 'delete_bank', bankId: 'legacy-default' })).body
    assert.equal(delB.ok, true)
    assert.equal(delB.bankTotal, 2)

    // 6) gemini_template 返回完整提示词模板
    const tpl = (await bank({ action: 'gemini_template' })).body
    assert.equal(tpl.ok, true)
    assert.equal(tpl.template, GEMINI_IMPORT_PROMPT_TEMPLATE)
  })
})

test('持久 questionStats + 3 级优先 + Fisher-Yates 真随机（跨轮不重回第 1 题）与 card/short_qa 判题', () => {
  const bank = {
    名称: '混合随机测试库',
    题目: [
      { id: 'q1', kind: 'mcq', 题干: '第 1 题足够长', 选项: ['A', 'B'], 答案: 'A', 解析: 'p1' },
      { id: 'q2', kind: 'mcq', 题干: '第 2 题足够长', 选项: ['A', 'B'], 答案: 'B', 解析: 'p2' },
      { id: 'q3', kind: 'card', 题干: '第 3 题知识展示卡足够长', 答案: '已阅读掌握', 解析: 'p3' },
      { id: 'q4', kind: 'short_qa', 题干: '第 4 题小问题足够长', 答案: '参考答案', 解析: 'p4' },
    ],
  }

  // 不同 rng 种子应抽出不同未刷题（彻底告别每次永远第 1 题）
  const pLow = pickNextQuizQuestion(bank, null, { rng: () => 0.01 })
  const pHigh = pickNextQuizQuestion(bank, null, { rng: () => 0.99 })
  assert.notEqual(pLow.question.id, pHigh.question.id)

  // 完成一轮解锁（requiredCount: 1）后，questionStats 与 lastQuestionId 必须持久化留存
  let st = { config: { requiredCount: 1, unlockMinutes: 5 } }
  const firstPick = pickNextQuizQuestion(bank, st, { rng: () => 0.01 })
  st = firstPick.state
  const ans1 = judgeQuizAnswer(bank, st, { questionId: firstPick.question.id, answer: firstPick.question.kind === 'mcq' ? (firstPick.question.id === 'q1' ? 'A' : 'B') : 'KNOWN', now: 1000, rng: () => 0.25 })
  assert.equal(ans1.unlocked, true)
  st = ans1.state
  assert.equal(st.session.solved, 0)
  assert.equal(st.questionStats[firstPick.question.id].seenCount, 1, '解锁后绝不能清空 questionStats')
  assert.equal(st.lastQuestionId, firstPick.question.id)

  // 下一轮只能从未刷过的 3 题里抽，绝不重复刚出过的题
  const secondPick = pickNextQuizQuestion(bank, st, { rng: () => 0.01 })
  assert.notEqual(secondPick.question.id, firstPick.question.id)

  // 测试 card 不熟 (UNFAMILIAR) 与读懂 (KNOWN) 以及 short_qa (FORGOT / REMEMBER)
  let cardSt = { config: { requiredCount: 3, unlockMinutes: 5 }, session: { currentId: 'q3' } }
  const cardBad = judgeQuizAnswer(bank, cardSt, { questionId: 'q3', answer: 'UNFAMILIAR', now: 2000 })
  assert.equal(cardBad.correct, false)
  assert.equal(cardBad.weight, 0)
  assert.equal(cardBad.state.questionStats.q3.wrongCount, 1)
  assert.ok(cardBad.state.wrongIds.includes('q3'))

  cardSt = { ...cardBad.state, session: { ...cardBad.state.session, currentId: 'q3' } }
  const cardGood = judgeQuizAnswer(bank, cardSt, { questionId: 'q3', answer: 'KNOWN', now: 3000 })
  assert.equal(cardGood.correct, true)
  assert.equal(cardGood.selfJudged, true)
  assert.equal(cardGood.weight, SELF_JUDGED_WEIGHT)
  assert.equal(cardGood.solved, SELF_JUDGED_WEIGHT)
  assert.equal(cardGood.state.questionStats.q3.correctCount, 1)
  assert.equal(cardGood.state.wrongIds.includes('q3'), false)

  const qaGood = judgeQuizAnswer(bank, { config: { requiredCount: 3 }, session: { currentId: 'q4' } }, { questionId: 'q4', answer: 'REMEMBER', now: 4000 })
  assert.equal(qaGood.correct, true)
  assert.equal(qaGood.selfJudged, true)
  assert.equal(qaGood.weight, SELF_JUDGED_WEIGHT)

  // 3 级优先验证：q1/q3 已看且答对，q2 为错题，q4 未刷过 → 先抽 q4 (Tier 1)，全刷过后抽错题 q2 (Tier 2)，答对后从最久未刷梯队抽 (Tier 3)
  const tierState = {
    config: { requiredCount: 5 },
    wrongIds: ['q2'],
    lastQuestionId: 'q1',
    questionStats: {
      q1: { seenCount: 2, correctCount: 2, wrongCount: 0, lastSeenAt: 3000 },
      q2: { seenCount: 1, correctCount: 0, wrongCount: 1, lastSeenAt: 2000 },
      q3: { seenCount: 1, correctCount: 1, wrongCount: 0, lastSeenAt: 1000 },
    },
  }
  const pickT1 = pickNextQuizQuestion(bank, tierState, { rng: () => 0.5 })
  assert.equal(pickT1.question.id, 'q4', 'Tier 1 优先从未刷过的 q4 中抽取')

  const allSeenWithWrong = {
    ...tierState,
    lastQuestionId: 'q4',
    questionStats: {
      ...tierState.questionStats,
      q4: { seenCount: 1, correctCount: 1, wrongCount: 0, lastSeenAt: 4000 },
    },
  }
  const pickT2 = pickNextQuizQuestion(bank, allSeenWithWrong, { rng: () => 0.5 })
  assert.equal(pickT2.question.id, 'q2', 'Tier 2 在全部刷过后优先抽错题/不熟卡 q2')

  const allMastered = {
    ...allSeenWithWrong,
    wrongIds: [],
    lastQuestionId: 'q2',
    questionStats: {
      ...allSeenWithWrong.questionStats,
      q2: { seenCount: 2, correctCount: 2, wrongCount: 0, lastSeenAt: 5000 },
    },
  }
  const pickT3 = pickNextQuizQuestion(bank, allMastered, { rng: () => 0.01 })
  assert.notEqual(pickT3.question.id, 'q2', 'Tier 3 必须避开 lastQuestionId')
  assert.ok(['q1', 'q3'].includes(pickT3.question.id), 'Tier 3 从最久未刷梯队(q3/q1)中随机抽取')
})
