// dsh-go-companion · V4 题库智能导入与锁屏刷题解锁模块
// 兼容已有 bank.js ({ 题库版本: 1, id, 名称, 题目 })，支持：
// 1) 标准 JSON 与宽松纯文本/Markdown（1. 题干 A. B. C. D. 答案：A 解析：...）或【题目】【答案】【解析】智能导入；
// 2) 锁屏闯关：默认答对 10 题解锁 5 分钟（题数与分钟数可配置），答错即时展示解析并收入错题池。

import { parseBank, BANK_VERSION } from './bank.js'

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
    wrongIds: Array.isArray(s.wrongIds) ? [...new Set(s.wrongIds.map(String))].slice(-200) : [],
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

export function pickNextQuizQuestion(rawBank, stateRaw) {
  const state = cleanBankUnlockState(stateRaw)
  const list = Array.isArray(rawBank?.题目) ? rawBank.题目 : []
  if (!list.length) {
    return {
      ok: true,
      bankTotal: 0,
      solved: state.session.solved,
      required: state.config.requiredCount,
      unlockMinutes: state.config.unlockMinutes,
      question: null,
    }
  }
  const seen = new Set(state.session.seenIds)
  const wrongSet = new Set(state.wrongIds)
  // 优先抽错题或未刷过的题，防止原地重复同一题
  let pool = list.filter((q) => !seen.has(String(q.id || '')))
  if (!pool.length) pool = list
  const prioritized = pool.find((q) => wrongSet.has(String(q.id || ''))) || pool[0]
  const qid = String(prioritized.id || `idx-${list.indexOf(prioritized)}`)
  const nextState = {
    ...state,
    session: {
      ...state.session,
      currentId: qid,
    },
  }
  return {
    ok: true,
    bankTotal: list.length,
    bankName: String(rawBank?.名称 || '我的题库'),
    solved: state.session.solved,
    required: state.config.requiredCount,
    unlockMinutes: state.config.unlockMinutes,
    wrongCount: state.wrongIds.length,
    question: {
      id: qid,
      stem: String(prioritized.题干 || ''),
      options: Array.isArray(prioritized.选项) ? prioritized.选项 : [],
      answer: Array.isArray(prioritized.选项) && prioritized.选项.length ? undefined : String(prioritized.答案 || ''),
      type: String(prioritized.题型 || ''),
    },
    state: nextState,
  }
}

export function judgeQuizAnswer(rawBank, stateRaw, { questionId, answer, now = Date.now() } = {}) {
  const state = cleanBankUnlockState(stateRaw)
  const list = Array.isArray(rawBank?.题目) ? rawBank.题目 : []
  const target = list.find((q, idx) => String(q.id || `idx-${idx}`) === String(questionId || state.session.currentId))
  if (!target) {
    return { ok: false, error: '题库里找不到这道题', state }
  }
  const qid = String(target.id || questionId)
  const expectedRaw = String(target.答案 || '').trim()
  const expectedNorm = normalizeAnswerToken(expectedRaw)
  const gotNorm = normalizeAnswerToken(answer)
  const opts = Array.isArray(target.选项) ? target.选项 : []

  // 支持按字母（A/B/C/D）或按选项正文命中；无选项题传入 PASS/原文均可
  let correct = false
  if (!opts.length && (gotNorm === 'PASS' || gotNorm === expectedNorm)) {
    correct = true
  } else if (gotNorm && gotNorm === expectedNorm) {
    correct = true
  } else if (opts.length && /^[A-F]$/.test(gotNorm)) {
    const idx = gotNorm.charCodeAt(0) - 65
    if (opts[idx] && normalizeAnswerToken(opts[idx]) === expectedNorm) correct = true
  }

  const nextSeen = [...state.session.seenIds.filter((x) => x !== qid), qid].slice(-120)
  const nextWrong = correct
    ? state.wrongIds.filter((x) => x !== qid)
    : [...new Set([...state.wrongIds, qid])].slice(-200)
  const selfJudged = !opts.length
  const nextSolved = round1(state.session.solved + (correct ? (selfJudged ? SELF_JUDGED_WEIGHT : 1) : 0))
  const unlocked = nextSolved >= state.config.requiredCount
  const unlockMinutes = state.config.unlockMinutes
  const unlockUntil = unlocked ? now + unlockMinutes * 60_000 : 0

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
    stats: {
      ...state.stats,
      answered: state.stats.answered + 1,
      correct: state.stats.correct + (correct && !selfJudged ? 1 : 0),
      selfPassed: state.stats.selfPassed + (correct && selfJudged ? 1 : 0),
      unlocksTotal: state.stats.unlocksTotal + (unlocked ? 1 : 0),
      lastUnlockAt: unlocked ? now : state.stats.lastUnlockAt,
    },
  }

  const preview = pickNextQuizQuestion(rawBank, nextState)
  return {
    ok: true,
    correct,
    selfJudged,
    weight: correct ? (selfJudged ? SELF_JUDGED_WEIGHT : 1) : 0,
    unlocked,
    unlockUntil,
    unlockMinutes,
    correctAnswer: expectedRaw,
    explain: String(target.解析 || ''),
    solved: nextSolved,
    required: state.config.requiredCount,
    bankTotal: list.length,
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
export function creditReviewAnswer(stateRaw, { weight = 0, now = Date.now() } = {}) {
  const state = cleanBankUnlockState(stateRaw)
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
