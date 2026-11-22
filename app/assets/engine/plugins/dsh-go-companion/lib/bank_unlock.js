// dsh-go-companion · V4 题库智能导入与锁屏刷题解锁模块
// 兼容已有 bank.js ({ 题库版本: 1, id, 名称, 题目 })，支持：
// 1) 标准 JSON 与宽松纯文本/Markdown（1. 题干 A. B. C. D. 答案：A 解析：...）或【题目】【答案】【解析】智能导入；
// 2) 锁屏闯关：默认答对 10 题解锁 5 分钟（题数与分钟数可配置），答错即时展示解析并收入错题池。

import { parseBank, BANK_VERSION } from './bank.js'
import { normalizeImages, normalizeQuestionKind } from './bank_import.js'

export const DEFAULT_QUIZ_REQUIRED = 10
export const DEFAULT_UNLOCK_MINUTES = 5
/**
 * 2026-11-21 没有选项的题（简答）只能自己对答案：算 0.7 题解锁进度、不进题库答对数（题库类验收点只认代码判的题）。
 * 个人复习卡同理：4 选 1 答对算 1 题，自己翻答案说「记得」算 0.7 题；都不进题库答对数。
 */
export const SELF_JUDGED_WEIGHT = 0.7
const round1 = (v) => Math.round(v * 10) / 10

function cleanLine(s) {
  return String(s || '').trim()
}

/**
 * 宽松解析纯文本或 JSON 题库：既收标准 parseBank JSON，也收考公/网课直接复制的纯文本题。
 * 返回标准化后的单个题库对象 { 题库版本: 1, id, 名称, 题目: [...] } 供存盘。
 */
export function parseBankSmart(rawInput, { name = '我的备考题库', id = 'custom-bank' } = {}) {
  const rawStr = typeof rawInput === 'string' ? rawInput.trim() : ''
  if (!rawStr && (!rawInput || typeof rawInput !== 'object')) {
    return { ok: false, error: '导入内容为空，请粘贴 JSON 或带答案的题目文本' }
  }

  // 1. 优先尝试 JSON
  if (typeof rawInput === 'object' || rawStr.startsWith('{') || rawStr.startsWith('[')) {
    try {
      const obj = typeof rawInput === 'object' ? rawInput : JSON.parse(rawStr)
      const targetObj = Array.isArray(obj)
        ? { 题库版本: BANK_VERSION, id, 名称: name, 题目: obj }
        : obj
      const res = parseBank(targetObj)
      if (res.ok) {
        return {
          ok: true,
          rawBank: {
            题库版本: BANK_VERSION,
            id: res.bank.id,
            名称: res.bank.name,
            题目: res.bank.questions.map((q) => ({
              id: q.id,
              题干: q.stem,
              ...(q.options?.length ? { 选项: q.options } : {}),
              答案: q.answer,
              解析: q.analysis.join('；'),
              ...(q.locate ? { 定位句: q.locate } : {}),
              ...(q.type ? { 题型: q.type } : {}),
              ...(q.kind ? { kind: q.kind } : {}),
              ...(q.images?.length ? { images: q.images } : {}),
            })),
          },
          count: res.bank.questions.length,
          warnings: res.warnings || [],
        }
      }
    } catch {
      // fallback to text parser
    }
  }

  // 2. 宽松纯文本分割解析：按 ---、空行+数字题号 或 【题目】 切块
  const normalized = rawStr.replace(/\r\n/g, '\n')
  const blocks = normalized
    .split(/\n\s*---+\s*\n|\n(?=(?:第?\s*\d+\s*[题.、．)]|【题目】))/)
    .map((b) => b.trim())
    .filter(Boolean)

  const questions = []
  for (let i = 0; i < blocks.length; i += 1) {
    const parsed = parseLooseBlock(blocks[i], i + 1)
    if (parsed) questions.push(parsed)
  }

  if (!questions.length) {
    return {
      ok: false,
      error: '未能识别出有效题目（每题至少需包含题干 + 答案，选项可用 A./B./C./D. 列出）',
    }
  }

  return {
    ok: true,
    rawBank: {
      题库版本: BANK_VERSION,
      id: String(id || 'custom-bank').slice(0, 48),
      名称: String(name || '我的备考题库').slice(0, 60),
      题目: questions,
    },
    count: questions.length,
    warnings: [],
  }
}

function parseLooseBlock(block, index) {
  const lines = block.split('\n').map(cleanLine).filter(Boolean)
  if (!lines.length) return null

  const stemLines = []
  const options = []
  let answer = ''
  const analysisLines = []
  let section = 'stem'

  for (let rawLine of lines) {
    // 剥离【题目】前缀
    rawLine = rawLine.replace(/^【题目】\s*/, '').replace(/^第?\s*\d+\s*[题.、．)]\s*/, '')
    if (/^(?:【?答案】?|参考答案|正确答案|Answer)\s*[:：]?\s*/i.test(rawLine)) {
      answer = rawLine.replace(/^(?:【?答案】?|参考答案|正确答案|Answer)\s*[:：]?\s*/i, '').trim()
      section = 'answer'
      continue
    }
    if (/^(?:【?解析】?|思路|解释|详解|Explain)\s*[:：]?\s*/i.test(rawLine)) {
      const rest = rawLine.replace(/^(?:【?解析】?|思路|解释|详解|Explain)\s*[:：]?\s*/i, '').trim()
      if (rest) analysisLines.push(rest)
      section = 'analysis'
      continue
    }
    if (/^(?:【选项】|Options)\s*[:：]?$/i.test(rawLine)) {
      section = 'options'
      continue
    }

    // 匹配同行内含多个选项：A. xxx B. xxx C. xxx D. xxx
    const inlineOpts = rawLine.match(/[A-Fa-f][.、．)]\s*[^A-Fa-f]+(?=\s+[A-Fa-f][.、．)]|$)/g)
    if (inlineOpts && inlineOpts.length >= 2) {
      for (const m of inlineOpts) {
        options.push(m.replace(/^[A-Fa-f][.、．)]\s*/, '').trim())
      }
      section = 'options'
      continue
    }

    const optMatch = rawLine.match(/^[(（]?([A-Fa-f])[)）.、．:]\s*(.+)$/)
    if (optMatch) {
      options.push(optMatch[2].trim())
      section = 'options'
      continue
    }

    if (section === 'analysis') analysisLines.push(rawLine)
    else if (section === 'stem') stemLines.push(rawLine)
    else if (section === 'options') options.push(rawLine)
  }

  const stem = stemLines.join(' ').trim()
  if (!stem || !answer) return null
  const explain = analysisLines.join('；').trim() || `本题参考答案为 ${answer}`
  return {
    id: `q-${index}`,
    题干: stem,
    ...(options.length >= 2 ? { 选项: options } : {}),
    答案: answer,
    解析: explain,
  }
}

function cleanQuestionStats(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const entries = []
  for (const [k, v] of Object.entries(raw)) {
    const qid = String(k || '').trim()
    if (!qid || !v || typeof v !== 'object') continue
    entries.push([qid, {
      seenCount: Math.max(0, Number(v.seenCount) || 0),
      correctCount: Math.max(0, Number(v.correctCount) || 0),
      wrongCount: Math.max(0, Number(v.wrongCount) || 0),
      lastSeenAt: Math.max(0, Number(v.lastSeenAt) || 0),
    }])
  }
  // 防止极端长周期无限膨胀，保留最近刷过的最多 10000 条记录
  if (entries.length > 10000) {
    entries.sort((a, b) => b[1].lastSeenAt - a[1].lastSeenAt)
    entries.length = 10000
  }
  return Object.fromEntries(entries)
}

export function cleanBankUnlockState(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const cfg = s.config && typeof s.config === 'object' ? s.config : {}
  const session = s.session && typeof s.session === 'object' ? s.session : {}
  const stats = s.stats && typeof s.stats === 'object' ? s.stats : {}
  return {
    config: {
      requiredCount: Math.max(1, Math.min(30, Number(cfg.requiredCount) || DEFAULT_QUIZ_REQUIRED)),
      unlockMinutes: Math.max(1, Math.min(30, Number(cfg.unlockMinutes) || DEFAULT_UNLOCK_MINUTES)),
    },
    session: {
      solved: Math.max(0, Number(session.solved) || 0),
      reviewDone: Math.max(0, Number(session.reviewDone) || 0),
      currentCard: String(session.currentCard || ''),
      seenIds: Array.isArray(session.seenIds) ? session.seenIds.map(String).slice(-120) : [],
      currentId: String(session.currentId || ''),
    },
    wrongIds: Array.isArray(s.wrongIds) ? [...new Set(s.wrongIds.map(String))].slice(-500) : [],
    lastQuestionId: String(s.lastQuestionId || ''),
    questionStats: cleanQuestionStats(s.questionStats),
    stats: {
      answered: Math.max(0, Number(stats.answered) || 0),
      correct: Math.max(0, Number(stats.correct) || 0),
      unlocksTotal: Math.max(0, Number(stats.unlocksTotal) || 0),
      selfPassed: Math.max(0, Number(stats.selfPassed) || 0),
      reviewed: Math.max(0, Number(stats.reviewed) || 0),
      lastUnlockAt: Number(stats.lastUnlockAt) || 0,
    },
  }
}

export function normalizeAnswerToken(ans) {
  return String(ans || '').trim().toUpperCase().replace(/^[（(]/, '').replace(/[）).、．\s]+$/g, '')
}

function resolveKind(q) {
  const opts = Array.isArray(q?.选项) ? q.选项 : (Array.isArray(q?.options) ? q.options : [])
  return normalizeQuestionKind(q?.kind ?? q?.形态, opts, q?.题型 ?? q?.type, q?.答案 ?? q?.answer)
}

function extractQuestionPool(rawOrCatalog) {
  if (Array.isArray(rawOrCatalog)) {
    const pool = rawOrCatalog.map((q, i) => ({
      qid: String(q?.id || `idx-${i}`),
      bankId: String(q?._bankId || ''),
      bankName: String(q?._bankName || '我的题库'),
      raw: q,
    }))
    return { pool, defaultBankName: pool[0]?.bankName || '我的题库' }
  }
  if (Array.isArray(rawOrCatalog?.banks)) {
    const enabledBanks = rawOrCatalog.banks.filter((b) => b && b.enabled !== false)
    const pool = []
    for (const b of enabledBanks) {
      const qs = Array.isArray(b.questions) ? b.questions : (Array.isArray(b.题目) ? b.题目 : [])
      const bName = String(b.name || b.名称 || '我的题库')
      const bId = String(b.id || '')
      qs.forEach((q, i) => {
        pool.push({
          qid: String(q?.id || `${bId || 'bank'}:idx-${i}`),
          bankId: bId,
          bankName: bName,
          raw: q,
        })
      })
    }
    const defaultBankName = enabledBanks.length === 1
      ? String(enabledBanks[0].name || enabledBanks[0].名称 || '我的题库')
      : (enabledBanks.length > 1 ? `${enabledBanks[0].name || enabledBanks[0].名称} 等 ${enabledBanks.length} 套` : '未启用题库')
    return { pool, defaultBankName }
  }
  const list = Array.isArray(rawOrCatalog?.题目) ? rawOrCatalog.题目 : []
  const bName = String(rawOrCatalog?.名称 || '我的题库')
  return {
    pool: list.map((q, i) => ({
      qid: String(q?.id || `idx-${i}`),
      bankId: String(rawOrCatalog?.id || ''),
      bankName: bName,
      raw: q,
    })),
    defaultBankName: bName,
  }
}

function fisherYatesPick(items, rng = Math.random) {
  if (!items.length) return null
  if (items.length === 1) return items[0]
  const copy = items.slice()
  const rand = typeof rng === 'function' ? rng : Math.random
  for (let i = copy.length - 1; i > 0; i -= 1) {
    const raw = Number(rand())
    const u = Number.isFinite(raw) ? Math.min(0.999999999999, Math.max(0, raw)) : Math.random()
    const j = Math.floor(u * (i + 1))
    const tmp = copy[i]
    copy[i] = copy[j]
    copy[j] = tmp
  }
  return copy[0]
}

function pickFromStaleSlice(items, questionStats, rng) {
  if (items.length <= 1) return items[0] || null
  const sorted = items
    .map((item) => ({ item, lastSeenAt: Number(questionStats[item.qid]?.lastSeenAt || 0) }))
    .sort((a, b) => a.lastSeenAt - b.lastSeenAt)
  const minSeen = sorted[0].lastSeenAt
  const tiedAtMin = sorted.filter((x) => x.lastSeenAt === minSeen).map((x) => x.item)
  if (tiedAtMin.length >= 2) {
    return fisherYatesPick(tiedAtMin, rng)
  }
  const sliceCount = Math.min(sorted.length, Math.max(2, Math.ceil(sorted.length / 2)))
  return fisherYatesPick(sorted.slice(0, sliceCount).map((x) => x.item), rng)
}

export function pickNextQuizQuestion(rawOrCatalog, stateRaw, { rng = Math.random } = {}) {
  const state = cleanBankUnlockState(stateRaw)
  const { pool, defaultBankName } = extractQuestionPool(rawOrCatalog)
  if (!pool.length) {
    return {
      ok: true,
      bankTotal: 0,
      bankName: defaultBankName,
      solved: state.session.solved,
      required: state.config.requiredCount,
      unlockMinutes: state.config.unlockMinutes,
      wrongCount: state.wrongIds.length,
      question: null,
    }
  }
  const seen = new Set(state.session.seenIds)
  const wrongSet = new Set(state.wrongIds)
  const qStats = state.questionStats || {}

  // 题池大于 1 题时优先避开刚刚出过的 lastQuestionId
  let candidates = pool.length > 1 && state.lastQuestionId
    ? pool.filter((x) => x.qid !== state.lastQuestionId)
    : pool
  if (!candidates.length) candidates = pool

  const roundUnseen = candidates.filter((x) => !seen.has(x.qid))
  if (roundUnseen.length) candidates = roundUnseen

  // 3 级优先 + Fisher-Yates 真随机：
  // ① 从未刷过（seenCount === 0）→ 随机抽取
  // ② 错题 / 不熟卡（wrongIds 或 wrongCount > correctCount）→ 最久未刷 + 随机抽取
  // ③ 全部刷过后 → 最久未刷梯队中随机抽取
  const tier1 = candidates.filter((x) => !((qStats[x.qid]?.seenCount || 0) > 0))
  let chosen = null
  if (tier1.length) {
    chosen = fisherYatesPick(tier1, rng)
  } else {
    const tier2 = candidates.filter((x) => {
      const st = qStats[x.qid]
      return wrongSet.has(x.qid) || (st && st.wrongCount > st.correctCount)
    })
    if (tier2.length) {
      chosen = pickFromStaleSlice(tier2, qStats, rng)
    } else {
      chosen = pickFromStaleSlice(candidates, qStats, rng)
    }
  }

  const q = chosen.raw
  const opts = Array.isArray(q.选项) ? q.选项 : (Array.isArray(q.options) ? q.options : [])
  const kind = resolveKind(q)
  const isCodeMcq = kind === 'mcq' && opts.length >= 2
  const images = normalizeImages(q.images ?? q.图片)
  const analysis = Array.isArray(q.解析) ? q.解析.join('\n') : String(q.解析 || q.analysis || '')

  const nextState = {
    ...state,
    session: {
      ...state.session,
      currentId: chosen.qid,
    },
  }
  return {
    ok: true,
    bankTotal: pool.length,
    bankName: chosen.bankName || defaultBankName,
    solved: state.session.solved,
    required: state.config.requiredCount,
    unlockMinutes: state.config.unlockMinutes,
    wrongCount: state.wrongIds.length,
    question: {
      id: chosen.qid,
      kind,
      stem: String(q.题干 || q.stem || ''),
      options: opts,
      answer: isCodeMcq ? undefined : String(q.答案 || q.answer || ''),
      analysis,
      ...(images.length ? { images } : {}),
      bankName: chosen.bankName || defaultBankName,
      type: String(q.题型 || q.type || ''),
      ...(isCodeMcq ? {} : { selfWeight: SELF_JUDGED_WEIGHT }),
    },
    state: nextState,
  }
}

const SELF_POSITIVE_TOKENS = new Set(['PASS', 'REMEMBER', 'KNOWN', 'UNDERSTOOD', 'READ', 'YES', 'OK', '我读懂了', '答上了', '记得'])
const SELF_NEGATIVE_TOKENS = new Set(['FORGOT', 'UNKNOWN', 'UNFAMILIAR', 'MISSED', 'FAIL', 'NO', '还不熟', '没答上', '忘了'])

export function judgeQuizAnswer(rawOrCatalog, stateRaw, { questionId, answer, now = Date.now(), rng = Math.random } = {}) {
  const state = cleanBankUnlockState(stateRaw)
  const { pool } = extractQuestionPool(rawOrCatalog)
  const submittedId = String(questionId ?? '').trim()
  if ((state.session.currentId && submittedId !== state.session.currentId) || (!state.session.currentId && state.session.currentCard)) {
    return {
      ok: true,
      duplicate: true,
      ignored: true,
      correct: false,
      unlocked: false,
      solved: state.session.solved,
      required: state.config.requiredCount,
      bankTotal: pool.length,
      state,
    }
  }
  const wantId = String(submittedId || state.session.currentId)
  const targetEntry = pool.find((x) => x.qid === wantId)
  if (!targetEntry) {
    return { ok: false, error: '题库里找不到这道题', state }
  }
  const target = targetEntry.raw
  const qid = targetEntry.qid
  const expectedRaw = String(target.答案 || target.answer || '').trim()
  const expectedNorm = normalizeAnswerToken(expectedRaw)
  const gotNorm = normalizeAnswerToken(answer)
  const opts = Array.isArray(target.选项) ? target.选项 : (Array.isArray(target.options) ? target.options : [])
  const kind = resolveKind(target)
  const selfJudged = kind !== 'mcq' || !opts.length

  // 支持按字母（A-H）或按选项正文命中；知识卡/小问题/无选项简答自评 REMEMBER/KNOWN/PASS 与 FORGOT/UNFAMILIAR
  let correct = false
  if (selfJudged) {
    if (SELF_NEGATIVE_TOKENS.has(gotNorm)) {
      correct = false
    } else if (SELF_POSITIVE_TOKENS.has(gotNorm) || (gotNorm && expectedNorm && gotNorm === expectedNorm)) {
      correct = true
    }
  } else if (gotNorm && gotNorm === expectedNorm) {
    correct = true
  } else if (opts.length && /^[A-H]$/.test(gotNorm)) {
    const idx = gotNorm.charCodeAt(0) - 65
    if (opts[idx] && normalizeAnswerToken(opts[idx]) === expectedNorm) correct = true
  }

  const nextSeen = [...state.session.seenIds.filter((x) => x !== qid), qid].slice(-120)
  const nextWrong = correct
    ? state.wrongIds.filter((x) => x !== qid)
    : [...new Set([...state.wrongIds, qid])].slice(-500)
  const prevQStat = state.questionStats[qid] || { seenCount: 0, correctCount: 0, wrongCount: 0, lastSeenAt: 0 }
  const nextQuestionStats = {
    ...state.questionStats,
    [qid]: {
      seenCount: prevQStat.seenCount + 1,
      correctCount: prevQStat.correctCount + (correct ? 1 : 0),
      wrongCount: prevQStat.wrongCount + (correct ? 0 : 1),
      lastSeenAt: now,
    },
  }

  const weight = correct ? (selfJudged ? SELF_JUDGED_WEIGHT : 1) : 0
  const nextSolved = round1(state.session.solved + weight)
  const unlocked = nextSolved >= state.config.requiredCount
  const unlockMinutes = state.config.unlockMinutes
  const unlockUntil = unlocked ? now + unlockMinutes * 60_000 : 0

  // 完成一轮解锁时绝不清空 questionStats 与 lastQuestionId，仅重置当轮 session 计数与去重窗
  const nextState = {
    config: state.config,
    session: {
      solved: unlocked ? 0 : nextSolved,
      reviewDone: unlocked ? 0 : state.session.reviewDone,
      currentCard: unlocked ? '' : state.session.currentCard,
      seenIds: unlocked ? [] : nextSeen,
      currentId: '',
    },
    wrongIds: nextWrong,
    lastQuestionId: qid,
    questionStats: nextQuestionStats,
    stats: {
      ...state.stats,
      answered: state.stats.answered + 1,
      correct: state.stats.correct + (correct && !selfJudged ? 1 : 0),
      selfPassed: state.stats.selfPassed + (correct && selfJudged ? 1 : 0),
      unlocksTotal: state.stats.unlocksTotal + (unlocked ? 1 : 0),
      lastUnlockAt: unlocked ? now : state.stats.lastUnlockAt,
    },
  }

  const preview = pickNextQuizQuestion(rawOrCatalog, nextState, { rng })
  return {
    ok: true,
    kind,
    correct,
    selfJudged,
    weight,
    unlocked,
    unlockUntil,
    unlockMinutes,
    correctAnswer: expectedRaw,
    explain: Array.isArray(target.解析) ? target.解析.join('\n') : String(target.解析 || target.analysis || ''),
    solved: nextSolved,
    required: state.config.requiredCount,
    bankTotal: pool.length,
    wrongCount: nextWrong.length,
    question: unlocked ? null : preview.question,
    state: unlocked ? nextState : preview.state,
  }
}

/** 锁屏这一轮最多先出几张到期复习卡（剩下的用题库补齐）。 */
export const reviewCapFor = (required) => Math.max(1, Math.floor(Math.max(1, Number(required) || DEFAULT_QUIZ_REQUIRED) / 2))

/**
 * 锁屏答了一张个人复习卡：按权重记解锁进度（不进题库答对数），够了就解锁。
 * weight：4 选 1 答对 1、自己说记得 0.7、答错 / 忘了 0。
 */
export function creditReviewAnswer(stateRaw, { weight = 0, cardId = '', now = Date.now() } = {}) {
  const state = cleanBankUnlockState(stateRaw)
  const cleanCardId = String(cardId || '').replace(/^rc:/, '').trim()
  const currentCardId = String(state.session.currentCard || '').replace(/^rc:/, '').trim()
  if (cleanCardId && ((currentCardId && cleanCardId !== currentCardId) || (!currentCardId && state.session.currentId))) {
    return {
      duplicate: true,
      ignored: true,
      unlocked: false,
      unlockUntil: 0,
      unlockMinutes: state.config.unlockMinutes,
      solved: state.session.solved,
      required: state.config.requiredCount,
      state,
    }
  }
  const nextSolved = round1(state.session.solved + Math.max(0, Number(weight) || 0))
  const unlocked = nextSolved >= state.config.requiredCount
  const unlockMinutes = state.config.unlockMinutes
  return {
    unlocked,
    unlockUntil: unlocked ? now + unlockMinutes * 60_000 : 0,
    unlockMinutes,
    solved: nextSolved,
    required: state.config.requiredCount,
    state: {
      ...state,
      session: unlocked
        ? { solved: 0, reviewDone: 0, currentCard: '', seenIds: [], currentId: '' }
        : { ...state.session, solved: nextSolved, reviewDone: state.session.reviewDone + 1, currentCard: '' },
      stats: {
        ...state.stats,
        reviewed: state.stats.reviewed + 1,
        unlocksTotal: state.stats.unlocksTotal + (unlocked ? 1 : 0),
        lastUnlockAt: unlocked ? now : state.stats.lastUnlockAt,
      },
    },
  }
}
