// dsh-go-companion · V4 题库智能导入、锁屏刷题解锁与独立专练 FSRS 引擎
// 兼容已有 bank.js ({ 题库版本: 1, id, 名称, 题目 })，支持：
// 1) 标准 JSON 与宽松纯文本/Markdown（1. 题干 A. B. C. D. 答案：A 解析：...）或【题目】【答案】【解析】智能导入；
// 2) 锁屏闯关：默认答对 10 题解锁 5 分钟（题数与分钟数可配置），答错即时展示解析并收入错题池；
// 3) 独立专练（FSRS 排期 + 收藏星标 + 错题本连续答对 2 次毕业 + 7 日刷题统计）。

import {
  BANK_VERSION,
  extractQuestionPool,
  formatPracticeQuestion,
  parseBank,
  resolveQuestionKind,
} from './bank.js'
import { normalizeImages } from './bank_import.js'
import { cleanFsrs, createFsrsCard, previewIntervals, ratingOf, reviewFsrsCard } from './fsrs.js'
import { DEFAULT_TZ, startOfDay, ymd, zoneOr } from './tz.js'

export const DEFAULT_QUIZ_REQUIRED = 10
export const DEFAULT_UNLOCK_MINUTES = 5
export const WRONG_BOOK_GRADUATE_STREAK = 2
export const DAILY_HISTORY_DAYS = 30
/**
 * 2026-11-21 没有选项的题（简答）只能自己对答案：算 0.7 题解锁进度、不进题库答对数（题库类验收点只认代码判的题）。
 * 个人复习卡同理：4 选 1 答对算 1 题，自己翻答案说「记得」算 0.7 题；都不进题库答对数。
 */
export const SELF_JUDGED_WEIGHT = 0.7
const DAY_MS = 86_400_000
const round1 = (v) => Math.round(v * 10) / 10
const VALID_SCOPES = new Set(['all', 'unseen', 'wrong_book', 'starred', 'fsrs_due'])
const VALID_KINDS = new Set(['mcq', 'card', 'short_qa'])

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

function emptyQuestionFsrs() {
  return {
    state: 0,
    due: 0,
    dueAt: 0,
    stability: 0,
    difficulty: 0,
    elapsedDays: 0,
    scheduledDays: 0,
    reps: 0,
    lapses: 0,
    lastReview: 0,
  }
}

function cleanQuestionFsrs(rawFsrs) {
  if (!rawFsrs || typeof rawFsrs !== 'object') return emptyQuestionFsrs()
  const rawDue = Number.isFinite(Number(rawFsrs.dueAt))
    ? Number(rawFsrs.dueAt)
    : (Number.isFinite(Number(rawFsrs.due)) ? Number(rawFsrs.due) : 0)
  const cleaned = cleanFsrs({ ...rawFsrs, due: Math.max(0, rawDue) }, 0)
  return {
    ...cleaned,
    due: cleaned.due,
    dueAt: cleaned.due,
  }
}

function defaultQuestionStat(inWrongBook = false) {
  return {
    seenCount: 0,
    correctCount: 0,
    wrongCount: 0,
    streakCorrect: 0,
    lastSeenAt: 0,
    starred: false,
    inWrongBook,
    fsrs: emptyQuestionFsrs(),
  }
}

function cleanQuestionStats(raw, { wrongSet = new Set(), wrongBookSet = new Set() } = {}) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const entries = []
  for (const [k, v] of Object.entries(raw)) {
    const qid = String(k || '').trim()
    if (!qid || !v || typeof v !== 'object') continue
    const hasExplicitWrong = typeof v.inWrongBook === 'boolean'
    const inWrongBook = hasExplicitWrong
      ? v.inWrongBook
      : (wrongBookSet.has(qid) || wrongSet.has(qid))
    entries.push([qid, {
      seenCount: Math.max(0, Number(v.seenCount) || 0),
      correctCount: Math.max(0, Number(v.correctCount) || 0),
      wrongCount: Math.max(0, Number(v.wrongCount) || 0),
      streakCorrect: Math.max(0, Math.round(Number(v.streakCorrect) || 0)),
      lastSeenAt: Math.max(0, Number(v.lastSeenAt) || 0),
      starred: Boolean(v.starred),
      inWrongBook,
      fsrs: cleanQuestionFsrs(v.fsrs),
    }])
  }
  // 防止极端长周期无限膨胀，优先保留收藏与错题，其余保留最近刷过的最多 10000 条记录
  if (entries.length > 10000) {
    entries.sort((a, b) => {
      const keepA = a[1].starred || a[1].inWrongBook ? 1 : 0
      const keepB = b[1].starred || b[1].inWrongBook ? 1 : 0
      return (keepB - keepA) || (b[1].lastSeenAt - a[1].lastSeenAt)
    })
    entries.length = 10000
  }
  return Object.fromEntries(entries)
}

function cleanDailyHistory(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return {}
  const entries = []
  for (const [dayKey, val] of Object.entries(raw)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(dayKey) || !val || typeof val !== 'object') continue
    entries.push([dayKey, {
      answered: Math.max(0, Math.round(Number(val.answered) || 0)),
      correct: Math.max(0, Math.round(Number(val.correct) || 0)),
      selfPassed: Math.max(0, Math.round(Number(val.selfPassed) || 0)),
    }])
  }
  entries.sort((a, b) => a[0].localeCompare(b[0]))
  return Object.fromEntries(entries.slice(-DAILY_HISTORY_DAYS))
}

export function cleanBankUnlockState(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const cfg = s.config && typeof s.config === 'object' ? s.config : {}
  const session = s.session && typeof s.session === 'object' ? s.session : {}
  const practiceSession = s.practiceSession && typeof s.practiceSession === 'object' ? s.practiceSession : {}
  const stats = s.stats && typeof s.stats === 'object' ? s.stats : {}
  const wrongIds = Array.isArray(s.wrongIds)
    ? [...new Set(s.wrongIds.map((x) => String(x || '').trim()).filter(Boolean))].slice(-500)
    : []
  const wrongSet = new Set(wrongIds)
  const rawWrongBookIds = Array.isArray(s.wrongBookIds)
    ? [...new Set(s.wrongBookIds.map((x) => String(x || '').trim()).filter(Boolean))].slice(-500)
    : null
  const wrongBookSeed = new Set(rawWrongBookIds ?? wrongIds)
  const questionStats = cleanQuestionStats(s.questionStats, { wrongSet, wrongBookSet: wrongBookSeed })

  const wrongBookMerged = new Set(rawWrongBookIds ?? wrongIds)
  for (const [qid, qs] of Object.entries(questionStats)) {
    if (qs.inWrongBook) wrongBookMerged.add(qid)
    else wrongBookMerged.delete(qid)
  }
  const wrongBookIds = [...wrongBookMerged].slice(-500)

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
    practiceSession: {
      solved: Math.max(0, Number(practiceSession.solved) || 0),
    },
    wrongIds,
    wrongBookIds,
    lastQuestionId: String(s.lastQuestionId || ''),
    recentIds: Array.isArray(s.recentIds)
      ? [...new Set(s.recentIds.map((x) => String(x || '').trim()).filter(Boolean))].slice(-50)
      : [],
    questionStats,
    stats: {
      answered: Math.max(0, Number(stats.answered) || 0),
      correct: Math.max(0, Number(stats.correct) || 0),
      unlocksTotal: Math.max(0, Number(stats.unlocksTotal) || 0),
      selfPassed: Math.max(0, Number(stats.selfPassed) || 0),
      reviewed: Math.max(0, Number(stats.reviewed) || 0),
      lastUnlockAt: Number(stats.lastUnlockAt) || 0,
      dailyHistory: cleanDailyHistory(stats.dailyHistory),
    },
  }
}

export function normalizeAnswerToken(ans) {
  return String(ans || '').trim().toUpperCase().replace(/^[（(]/, '').replace(/[）).、．\s]+$/g, '')
}

function isQuestionFsrsDue(qStat, now = Date.now()) {
  const dueAt = Number(qStat?.fsrs?.dueAt ?? qStat?.fsrs?.due ?? 0)
  return dueAt > 0 && dueAt <= now
}

function isQuestionInWrongBook(qid, state) {
  const st = state.questionStats?.[qid]
  if (st && typeof st.inWrongBook === 'boolean') return st.inWrongBook
  return state.wrongBookIds.includes(qid) || state.wrongIds.includes(qid)
}

function applyQuestionFsrsUpdate(prevFsrs, rating, now = Date.now()) {
  const g = ratingOf(rating) || 3
  const prev = cleanQuestionFsrs(prevFsrs)
  const base = prev.reps > 0 && prev.stability > 0
    ? cleanFsrs({ ...prev, due: prev.dueAt || prev.due || now }, now)
    : createFsrsCard(now)
  const next = reviewFsrsCard(base, g, now)
  return {
    ...next,
    due: next.due,
    dueAt: next.due,
  }
}

function bumpDailyHistory(dailyHistory, now, tz, { correct, selfJudged }) {
  const dayKey = ymd(now, zoneOr(tz))
  const cur = dailyHistory?.[dayKey] || { answered: 0, correct: 0, selfPassed: 0 }
  return cleanDailyHistory({
    ...dailyHistory,
    [dayKey]: {
      answered: cur.answered + 1,
      correct: cur.correct + (correct && !selfJudged ? 1 : 0),
      selfPassed: cur.selfPassed + (correct && selfJudged ? 1 : 0),
    },
  })
}

function fisherYatesShuffle(items, rng = Math.random) {
  const copy = items.slice()
  if (copy.length <= 1) return copy
  const rand = typeof rng === 'function' ? rng : Math.random
  for (let i = copy.length - 1; i > 0; i -= 1) {
    const raw = Number(rand())
    const u = Number.isFinite(raw) ? Math.min(0.999999999999, Math.max(0, raw)) : Math.random()
    const j = Math.floor(u * (i + 1))
    const tmp = copy[i]
    copy[i] = copy[j]
    copy[j] = tmp
  }
  return copy
}

function fisherYatesPick(items, rng = Math.random) {
  if (!items.length) return null
  if (items.length === 1) return items[0]
  return fisherYatesShuffle(items, rng)[0]
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

export function pickNextQuizQuestion(rawOrCatalog, stateRaw, { rng = Math.random, now = Date.now() } = {}) {
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
  // ② FSRS 到期 / 错题 / 不熟卡（wrongIds / inWrongBook / fsrs_due / wrongCount > correctCount）→ 最久未刷 + 随机抽取
  // ③ 全部刷过后 → 最久未刷梯队中随机抽取
  const tier1 = candidates.filter((x) => !((qStats[x.qid]?.seenCount || 0) > 0))
  let chosen = null
  if (tier1.length) {
    chosen = fisherYatesPick(tier1, rng)
  } else {
    const tier2 = candidates.filter((x) => {
      const st = qStats[x.qid]
      return wrongSet.has(x.qid)
        || isQuestionInWrongBook(x.qid, state)
        || isQuestionFsrsDue(st, now)
        || (st && st.wrongCount > st.correctCount)
    })
    if (tier2.length) {
      chosen = pickFromStaleSlice(tier2, qStats, rng)
    } else {
      chosen = pickFromStaleSlice(candidates, qStats, rng)
    }
  }

  const q = chosen.raw
  const opts = Array.isArray(q.选项) ? q.选项 : (Array.isArray(q.options) ? q.options : [])
  const kind = resolveQuestionKind(q)
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

const SELF_POSITIVE_TOKENS = new Set(['PASS', 'REMEMBER', 'KNOWN', 'UNDERSTOOD', 'READ', 'YES', 'OK', 'HARD', 'GOOD', 'EASY', '我读懂了', '答上了', '记得'])
const SELF_NEGATIVE_TOKENS = new Set(['FORGOT', 'UNKNOWN', 'UNFAMILIAR', 'MISSED', 'FAIL', 'NO', 'AGAIN', '还不熟', '没答上', '忘了'])
const LETTERS = 'ABCDEFGH'

function evaluateQuestionAnswer(target, answer, rating) {
  const expectedRaw = String(target.答案 || target.answer || '').trim()
  const expectedNorm = normalizeAnswerToken(expectedRaw)
  const gotNorm = normalizeAnswerToken(answer)
  const opts = Array.isArray(target.选项) ? target.选项 : (Array.isArray(target.options) ? target.options : [])
  const kind = resolveQuestionKind(target)
  const selfJudged = kind !== 'mcq' || !opts.length
  const parsedRating = ratingOf(rating)

  let correct = false
  if (selfJudged) {
    if (SELF_NEGATIVE_TOKENS.has(gotNorm)) {
      correct = false
    } else if (SELF_POSITIVE_TOKENS.has(gotNorm) || (gotNorm && expectedNorm && gotNorm === expectedNorm)) {
      correct = true
    } else if (!gotNorm && parsedRating > 0) {
      correct = parsedRating >= 2
    }
  } else if (gotNorm && gotNorm === expectedNorm) {
    correct = true
  } else if (opts.length && /^[A-H]$/.test(gotNorm)) {
    const idx = gotNorm.charCodeAt(0) - 65
    if (opts[idx] && normalizeAnswerToken(opts[idx]) === expectedNorm) correct = true
  } else if (opts.length && gotNorm) {
    const gotLetters = gotNorm.replace(/[^A-H]/g, '')
    const expLetters = expectedNorm.replace(/[^A-H]/g, '')
    if (
      gotLetters.length >= 2
      && gotLetters.length === gotNorm.length
      && expLetters.length === expectedNorm.length
      && [...new Set(gotLetters.split(''))].sort().join('') === [...new Set(expLetters.split(''))].sort().join('')
    ) {
      correct = true
    } else if (/^[A-H]$/.test(expectedNorm)) {
      const expIdx = expectedNorm.charCodeAt(0) - 65
      if (opts[expIdx] && normalizeAnswerToken(opts[expIdx]) === gotNorm) correct = true
    }
  }

  return {
    kind,
    opts,
    selfJudged,
    expectedRaw,
    correct,
    explain: Array.isArray(target.解析) ? target.解析.join('\n') : String(target.解析 || target.analysis || ''),
  }
}

export function judgeQuizAnswer(rawOrCatalog, stateRaw, { questionId, answer, now = Date.now(), tz = DEFAULT_TZ, rng = Math.random } = {}) {
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
  const qid = targetEntry.qid
  const judged = evaluateQuestionAnswer(targetEntry.raw, answer)
  const { kind, selfJudged, expectedRaw, correct, explain } = judged

  const nextSeen = [...state.session.seenIds.filter((x) => x !== qid), qid].slice(-120)
  // 保持锁屏旧契约：judgeQuizAnswer 答对即从 wrongIds 移出，同时用 inWrongBook + wrongBookIds 严格执行连对 2 次毕业
  const nextWrong = correct
    ? state.wrongIds.filter((x) => x !== qid)
    : [...new Set([...state.wrongIds, qid])].slice(-500)

  const prevQStat = state.questionStats[qid] || defaultQuestionStat(isQuestionInWrongBook(qid, state))
  const nextStreak = correct ? prevQStat.streakCorrect + 1 : 0
  const wasInWrongBook = isQuestionInWrongBook(qid, state)
  const nextInWrongBook = correct
    ? (wasInWrongBook && nextStreak < WRONG_BOOK_GRADUATE_STREAK)
    : true
  const nextWrongBookIds = nextInWrongBook
    ? [...new Set([...state.wrongBookIds, qid])].slice(-500)
    : state.wrongBookIds.filter((x) => x !== qid)
  const nextFsrs = applyQuestionFsrsUpdate(prevQStat.fsrs, correct ? 3 : 1, now)

  const nextQuestionStats = {
    ...state.questionStats,
    [qid]: {
      seenCount: prevQStat.seenCount + 1,
      correctCount: prevQStat.correctCount + (correct ? 1 : 0),
      wrongCount: prevQStat.wrongCount + (correct ? 0 : 1),
      streakCorrect: nextStreak,
      lastSeenAt: now,
      starred: prevQStat.starred,
      inWrongBook: nextInWrongBook,
      fsrs: nextFsrs,
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
    wrongBookIds: nextWrongBookIds,
    lastQuestionId: qid,
    recentIds: [...state.recentIds.filter((x) => x !== qid), qid].slice(-50),
    questionStats: nextQuestionStats,
    stats: {
      ...state.stats,
      answered: state.stats.answered + 1,
      correct: state.stats.correct + (correct && !selfJudged ? 1 : 0),
      selfPassed: state.stats.selfPassed + (correct && selfJudged ? 1 : 0),
      unlocksTotal: state.stats.unlocksTotal + (unlocked ? 1 : 0),
      lastUnlockAt: unlocked ? now : state.stats.lastUnlockAt,
      dailyHistory: bumpDailyHistory(state.stats.dailyHistory, now, tz, { correct, selfJudged }),
    },
  }

  const preview = pickNextQuizQuestion(rawOrCatalog, nextState, { rng, now })
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
    explain,
    solved: nextSolved,
    required: state.config.requiredCount,
    bankTotal: pool.length,
    wrongCount: nextWrong.length,
    streakCorrect: nextStreak,
    inWrongBook: nextInWrongBook,
    fsrs: nextFsrs,
    question: unlocked ? null : preview.question,
    state: unlocked ? nextState : preview.state,
  }
}

/** 排序并去重专练题目：从未刷过 → FSRS 已到期 → 错题本 → 最久未刷（同档 Fisher-Yates 打散）。 */
function orderPracticeEntries(entries, state, { now = Date.now(), rng = Math.random } = {}) {
  const tier1Unseen = []
  const tier2Due = []
  const tier3Wrong = []
  const tier4Rest = []

  for (const entry of entries) {
    const qStat = state.questionStats[entry.qid]
    const seenCount = qStat?.seenCount || 0
    const inWrongBook = isQuestionInWrongBook(entry.qid, state)
    const due = isQuestionFsrsDue(qStat, now)
    if (seenCount === 0) {
      tier1Unseen.push({ entry, qStat, tier: 1 })
    } else if (due) {
      tier2Due.push({ entry, qStat, tier: 2, dueAt: Number(qStat?.fsrs?.dueAt ?? qStat?.fsrs?.due ?? 0), lapses: Number(qStat?.fsrs?.lapses || 0), lastSeenAt: Number(qStat?.lastSeenAt || 0) })
    } else if (inWrongBook) {
      tier3Wrong.push({ entry, qStat, tier: 3, lastSeenAt: Number(qStat?.lastSeenAt || 0), wrongCount: Number(qStat?.wrongCount || 0) })
    } else {
      tier4Rest.push({ entry, qStat, tier: 4, lastSeenAt: Number(qStat?.lastSeenAt || 0) })
    }
  }

  const shuffleTiedBy = (items, keyFn) => {
    const groups = new Map()
    for (const item of items) {
      const key = keyFn(item)
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key).push(item)
    }
    const out = []
    for (const key of groups.keys()) {
      out.push(...fisherYatesShuffle(groups.get(key), rng))
    }
    return out
  }

  const t1 = fisherYatesShuffle(tier1Unseen, rng)
  tier2Due.sort((a, b) => (a.dueAt - b.dueAt) || (b.lapses - a.lapses) || (a.lastSeenAt - b.lastSeenAt))
  const t2 = shuffleTiedBy(tier2Due, (x) => `${x.dueAt}:${x.lapses}:${x.lastSeenAt}`)
  tier3Wrong.sort((a, b) => (a.lastSeenAt - b.lastSeenAt) || (b.wrongCount - a.wrongCount))
  const t3 = shuffleTiedBy(tier3Wrong, (x) => `${x.lastSeenAt}:${x.wrongCount}`)
  tier4Rest.sort((a, b) => a.lastSeenAt - b.lastSeenAt)
  const t4 = shuffleTiedBy(tier4Rest, (x) => `${x.lastSeenAt}`)

  return [...t1, ...t2, ...t3, ...t4]
}

/**
 * 分卷 / 专项练习抽题引擎（绝不原地重复吐同一批题）：
 * - bankId: 指定单套题库（留空=全部启用题库）
 * - kind: 'mcq' | 'card' | 'short_qa'（留空=混合）
 * - scope: 'all' | 'unseen' | 'wrong_book' | 'starred' | 'fsrs_due'
 */
export function buildPracticeSession(
  rawOrCatalog,
  stateRaw,
  {
    bankId = '',
    kind = '',
    scope = 'all',
    limit = 20,
    now = Date.now(),
    rng = Math.random,
    excludeIds = [],
  } = {},
) {
  const state = cleanBankUnlockState(stateRaw)
  const cleanBankId = String(bankId || '').trim()
  const cleanKind = VALID_KINDS.has(String(kind || '').trim()) ? String(kind || '').trim() : ''
  const cleanScope = VALID_SCOPES.has(String(scope || '').trim()) ? String(scope || '').trim() : 'all'
  const cap = Math.max(1, Math.min(200, Math.round(Number(limit) || 20)))

  const { pool, defaultBankName } = extractQuestionPool(rawOrCatalog, {
    bankId: cleanBankId,
    includeDisabled: Boolean(cleanBankId),
  })

  const filtered = pool.filter((entry) => {
    if (cleanKind && resolveQuestionKind(entry.raw) !== cleanKind) return false
    const qStat = state.questionStats[entry.qid]
    if (cleanScope === 'unseen') return (qStat?.seenCount || 0) === 0
    if (cleanScope === 'wrong_book') return isQuestionInWrongBook(entry.qid, state)
    if (cleanScope === 'starred') return Boolean(qStat?.starred)
    if (cleanScope === 'fsrs_due') return isQuestionFsrsDue(qStat, now)
    return true
  })

  const excludeSet = new Set((Array.isArray(excludeIds) ? excludeIds : []).map((x) => String(x || '').trim()).filter(Boolean))
  const primary = excludeSet.size ? filtered.filter((x) => !excludeSet.has(x.qid)) : filtered
  const fallback = excludeSet.size ? filtered.filter((x) => excludeSet.has(x.qid)) : []

  const ordered = [
    ...orderPracticeEntries(primary, state, { now, rng }),
    ...orderPracticeEntries(fallback, state, { now, rng }),
  ]

  // 若有多题且首题刚好是上一题且第二题属同档，避开连出同一题
  if (ordered.length > 1 && state.lastQuestionId && ordered[0].entry.qid === state.lastQuestionId && ordered[0].tier === ordered[1].tier) {
    const first = ordered[0]
    ordered[0] = ordered[1]
    ordered[1] = first
  }

  const seenOut = new Set()
  const questions = []
  for (const item of ordered) {
    if (seenOut.has(item.entry.qid)) continue
    seenOut.add(item.entry.qid)
    const baseStat = item.qStat || defaultQuestionStat(isQuestionInWrongBook(item.entry.qid, state))
    questions.push(formatPracticeQuestion(item.entry, {
      ...baseStat,
      inWrongBook: isQuestionInWrongBook(item.entry.qid, state),
    }))
    if (questions.length >= cap) break
  }

  return {
    ok: true,
    bankId: cleanBankId,
    bankName: defaultBankName,
    kind: cleanKind,
    scope: cleanScope,
    totalAvailable: filtered.length,
    count: questions.length,
    questions,
  }
}

/**
 * 独立专练作答记录器（不强依赖锁屏解锁流程）：
 * 判定对错 / 自评、更新连续答对次数（连对 >= 2 从错题本毕业）、FSRS 排期与 30 天每日统计。
 */
export function recordPracticeAnswer(
  rawOrCatalog,
  stateRaw,
  { questionId, answer = '', rating, elapsedMs = 0, now = Date.now(), tz = DEFAULT_TZ } = {},
) {
  const state = cleanBankUnlockState(stateRaw)
  const qid = String(questionId ?? '').trim()
  if (!qid) return { ok: false, error: '缺题目 ID', state }

  const { pool } = extractQuestionPool(rawOrCatalog, { includeDisabled: true })
  const targetEntry = pool.find((x) => x.qid === qid)
  if (!targetEntry) {
    return { ok: false, error: '题库里找不到这道题', state }
  }

  const evaluated = evaluateQuestionAnswer(targetEntry.raw, answer, rating)
  const { kind, selfJudged, expectedRaw, correct, explain } = evaluated
  const requestedRating = ratingOf(rating)
  const resolvedRating = correct ? (requestedRating >= 2 ? requestedRating : 3) : 1

  const prevQStat = state.questionStats[qid] || defaultQuestionStat(isQuestionInWrongBook(qid, state))
  const nextStreak = correct ? prevQStat.streakCorrect + 1 : 0
  const wasInWrongBook = isQuestionInWrongBook(qid, state)
  const nextInWrongBook = correct
    ? (wasInWrongBook && nextStreak < WRONG_BOOK_GRADUATE_STREAK)
    : true
  const addedToWrongBook = !correct && !wasInWrongBook
  const graduatedFromWrongBook = correct && wasInWrongBook && !nextInWrongBook

  const nextFsrs = applyQuestionFsrsUpdate(prevQStat.fsrs, resolvedRating, now)
  const nextWrongBookIds = nextInWrongBook
    ? [...new Set([...state.wrongBookIds, qid])].slice(-500)
    : state.wrongBookIds.filter((x) => x !== qid)
  const nextWrongIds = nextInWrongBook
    ? [...new Set([...state.wrongIds, qid])].slice(-500)
    : state.wrongIds.filter((x) => x !== qid)

  const nextQuestionStats = {
    ...state.questionStats,
    [qid]: {
      seenCount: prevQStat.seenCount + 1,
      correctCount: prevQStat.correctCount + (correct ? 1 : 0),
      wrongCount: prevQStat.wrongCount + (correct ? 0 : 1),
      streakCorrect: nextStreak,
      lastSeenAt: now,
      starred: prevQStat.starred,
      inWrongBook: nextInWrongBook,
      fsrs: nextFsrs,
    },
  }

  const weight = correct ? (selfJudged ? SELF_JUDGED_WEIGHT : 1) : 0
  const prevPracticeSolved = Math.max(0, Number(state.practiceSession?.solved) || 0)
  const nextPracticeSolved = round1(prevPracticeSolved + weight)
  const roundComplete = nextPracticeSolved >= state.config.requiredCount

  const nextState = {
    ...state,
    practiceSession: {
      solved: roundComplete ? 0 : nextPracticeSolved,
    },
    wrongIds: nextWrongIds,
    wrongBookIds: nextWrongBookIds,
    lastQuestionId: qid,
    recentIds: [...state.recentIds.filter((x) => x !== qid), qid].slice(-50),
    questionStats: nextQuestionStats,
    stats: {
      ...state.stats,
      answered: state.stats.answered + 1,
      correct: state.stats.correct + (correct && !selfJudged ? 1 : 0),
      selfPassed: state.stats.selfPassed + (correct && selfJudged ? 1 : 0),
      dailyHistory: bumpDailyHistory(state.stats.dailyHistory, now, tz, { correct, selfJudged }),
    },
  }

  return {
    ok: true,
    questionId: qid,
    kind,
    correct,
    selfJudged,
    weight,
    practiceSolved: nextPracticeSolved,
    required: state.config.requiredCount,
    unlockMinutes: state.config.unlockMinutes,
    roundComplete,
    expectedAnswer: expectedRaw,
    correctAnswer: expectedRaw,
    explain,
    rating: resolvedRating,
    elapsedMs: Math.max(0, Math.round(Number(elapsedMs) || 0)),
    streakCorrect: nextStreak,
    inWrongBook: nextInWrongBook,
    addedToWrongBook,
    graduatedFromWrongBook,
    starred: prevQStat.starred,
    fsrs: nextFsrs,
    nextDueAt: nextFsrs.dueAt,
    scheduledDays: nextFsrs.scheduledDays,
    intervals: previewIntervals(nextFsrs, now),
    wrongCount: nextWrongBookIds.length,
    stats7d: build7dQuizStats(nextState, now, tz, rawOrCatalog),
    state: nextState,
  }
}

/** 切换或设置题目星标收藏状态。 */
export function toggleQuestionStar(stateRaw, questionId, starred) {
  const state = cleanBankUnlockState(stateRaw)
  const qid = String(questionId ?? '').trim()
  if (!qid) return { ok: false, error: '缺题目 ID', state }
  const prev = state.questionStats[qid] || defaultQuestionStat(isQuestionInWrongBook(qid, state))
  const nextStarred = starred === undefined ? !prev.starred : Boolean(starred)
  const nextQuestionStats = {
    ...state.questionStats,
    [qid]: {
      ...prev,
      starred: nextStarred,
    },
  }
  const nextState = {
    ...state,
    questionStats: nextQuestionStats,
  }
  const starredCount = Object.values(nextQuestionStats).filter((x) => x.starred).length
  return {
    ok: true,
    questionId: qid,
    starred: nextStarred,
    starredCount,
    state: nextState,
  }
}

/** 输出近 7 日每日刷题量、正确率、累计收藏数、错题本待消灭数、FSRS 待复习数。 */
export function build7dQuizStats(stateRaw, now = Date.now(), tz = DEFAULT_TZ, rawOrCatalog = null) {
  const state = cleanBankUnlockState(stateRaw)
  const zone = zoneOr(tz)
  const noonToday = startOfDay(now, zone) + 12 * 3_600_000
  const history = state.stats.dailyHistory || {}

  const days = []
  let totalAnswered7d = 0
  let totalCorrect7d = 0
  let totalSelfPassed7d = 0

  for (let offset = 6; offset >= 0; offset -= 1) {
    const dayKey = ymd(noonToday - offset * DAY_MS, zone)
    const rec = history[dayKey] || { answered: 0, correct: 0, selfPassed: 0 }
    const answered = Math.max(0, Number(rec.answered) || 0)
    const correct = Math.max(0, Number(rec.correct) || 0)
    const selfPassed = Math.max(0, Number(rec.selfPassed) || 0)
    const passed = Math.min(answered, correct + selfPassed)
    totalAnswered7d += answered
    totalCorrect7d += correct
    totalSelfPassed7d += selfPassed
    days.push({
      day: dayKey,
      label: dayKey.slice(5),
      answered,
      correct,
      selfPassed,
      passed,
      accuracyPct: answered > 0 ? Math.round((passed * 100) / answered) : null,
    })
  }

  const totalPassed7d = Math.min(totalAnswered7d, totalCorrect7d + totalSelfPassed7d)
  const todayItem = days[days.length - 1]

  let existingIds = null
  if (rawOrCatalog) {
    const { pool } = extractQuestionPool(rawOrCatalog, { includeDisabled: true })
    existingIds = new Set(pool.map((x) => x.qid))
  }

  let starredCount = 0
  let fsrsDueCount = 0
  let seenTotal = 0
  const wrongSet = new Set()

  for (const [qid, qs] of Object.entries(state.questionStats || {})) {
    if (existingIds && !existingIds.has(qid)) continue
    if (qs.seenCount > 0) seenTotal += 1
    if (qs.starred) starredCount += 1
    if (isQuestionFsrsDue(qs, now)) fsrsDueCount += 1
    if (qs.inWrongBook) wrongSet.add(qid)
  }
  for (const qid of state.wrongBookIds) {
    if (existingIds && !existingIds.has(qid)) continue
    if (isQuestionInWrongBook(qid, state)) wrongSet.add(qid)
  }
  for (const qid of state.wrongIds) {
    if (existingIds && !existingIds.has(qid)) continue
    if (isQuestionInWrongBook(qid, state)) wrongSet.add(qid)
  }

  return {
    tz: zone,
    today: todayItem.day,
    days,
    todayAnswered: todayItem.answered,
    todayCorrect: todayItem.correct,
    todaySelfPassed: todayItem.selfPassed,
    todayAccuracyPct: todayItem.accuracyPct,
    totalAnswered7d,
    totalCorrect7d,
    totalSelfPassed7d,
    totalPassed7d,
    accuracy7d: totalAnswered7d > 0 ? Math.round((totalPassed7d * 100) / totalAnswered7d) : null,
    starredCount,
    wrongBookCount: wrongSet.size,
    fsrsDueCount,
    seenTotal,
  }
}

/** 导出错题本清单（供 /bank action: 'wrong_book_list' 调用）。 */
export function getWrongBookList(rawOrCatalog, stateRaw, { bankId = '', kind = '', limit = 200, now = Date.now() } = {}) {
  const session = buildPracticeSession(rawOrCatalog, stateRaw, {
    bankId,
    kind,
    scope: 'wrong_book',
    limit,
    now,
  })
  return {
    ok: true,
    total: session.totalAvailable,
    count: session.count,
    items: session.questions,
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
