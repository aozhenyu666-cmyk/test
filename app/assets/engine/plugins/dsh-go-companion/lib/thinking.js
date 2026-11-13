// dsh-go-companion · 思维局六招（第八轮 J5）
//
// 思维局的单位不是时间段，是一题多拍：一道题拆成 4–5 拍乒乓，每拍只答一句。
// 六招写死在引擎里——协议只能「用哪几招」，不能自己造招。
//
// 三条不能松的线：
// 1. 依据前置检查：一道题进队列前先查它撑不撑得住这一招（有答案 / 有定位句 / 有他的记录）。
//    缺任何一项，这道题对这一招就是不可用，不许让模型临场补一个依据出来。
// 2. 代码判的招式必须占多数：只有「复述」靠模型判，最容易被糊弄，所以别的招都得是代码判。
// 3. 答错 ≠ 敷衍：答错只是这一拍重打（不罚不锁，糊弄也不省时间）；敷衍（口水话 / 不作答 / 超时）
//    才走 P4 的升级阶梯。两者在账本里分开记。

import { concreteNouns, countChars, fillerRatio } from './capabilities.js'

export const MOVES = Object.freeze(['先答后看', '定位', '复述', '续接', '找茬', '一条规则'])

/** 谁判。只有「复述」交给模型——它就是被设计成练概括的，代码判不了。 */
export const JUDGE = Object.freeze({
  先答后看: 'code',
  定位: 'code',
  复述: 'model',
  续接: 'code',
  找茬: 'code',
  一条规则: 'code',
})

/** 强度：低 = 前两招；中 = 再加复述与续接；高 = 再加找茬（再由鲸澜反驳一轮，那一步在引擎里）。 */
export const INTENSITY = Object.freeze({
  低: ['先答后看', '定位'],
  中: ['先答后看', '定位', '复述', '续接'],
  高: ['先答后看', '定位', '复述', '续接', '找茬'],
})

/** 「一条规则」的复考间隔：1 天、3 天。 */
export const RULE_RETEST_MS = [86_400_000, 3 * 86_400_000]

/** 引原文至少引这么多个字，不然引一个「的」也算定位。 */
export const MIN_QUOTE = 6

/** 「一条规则」最多这么长，写长了就不是一条规则。 */
export const RULE_MAX = 40

const norm = (s) => String(s ?? '').replace(/[\s，。、；：！？,.;:!?"'“”‘’（）()]/g, '')

/** 强度 → 招式表。认不出的强度直接拒，不猜一个默认的。 */
export function movesOf(intensity) {
  const key = String(intensity ?? '')
  const moves = INTENSITY[key]
  if (!moves) return { ok: false, error: 'bad-intensity', list: Object.keys(INTENSITY) }
  return { ok: true, moves: [...moves] }
}

/**
 * 代码判的招式有没有占多数。加载期检查（施工书 §3 J5「弱点控制」）。
 * @param {string[]} moves
 */
export function codeMajority(moves) {
  const list = Array.isArray(moves) ? moves : []
  const code = list.filter((m) => JUDGE[m] === 'code').length
  const model = list.filter((m) => JUDGE[m] === 'model').length
  const unknown = list.filter((m) => !MOVES.includes(m))
  return { ok: !unknown.length && code > model, code, model, unknown }
}

/**
 * 这一招要什么依据。缺了就把这道题从这一招里排除，而不是让它带着缺口上场。
 * @returns {string[]} 缺的项（空数组＝这一招可用）
 */
export function missingFor(question, move, context = {}) {
  const q = question || {}
  const missing = []
  if (!MOVES.includes(move)) return ['招式不存在：' + String(move)]
  if (!String(q.answer ?? '').trim()) missing.push('答案')
  if (move === '复述' && !(Array.isArray(q.analysis) ? q.analysis.length : String(q.analysis ?? '').trim())) missing.push('解析')
  if (move === '定位' && !String(q.locate ?? '').trim()) missing.push('定位句')
  if (move === '续接' && !String(context.record ?? '').trim()) missing.push('他的记录')
  if (move === '找茬' && !faultStep(q)) missing.push('埋的错')
  if (move === '一条规则' && !context.wrong) missing.push('一道答错的题')
  return missing
}

export function ready(question, move, context = {}) {
  const missing = missingFor(question, move, context)
  return { ok: !missing.length, missing }
}

/** 这道题能用的招式（依据齐全的那些）。 */
export function usableMoves(question, context = {}) {
  return MOVES.filter((m) => ready(question, m, context).ok)
}

/** 解析里被标了错的第几步（1 起）。没有就 null。 */
export function faultStep(question) {
  const steps = Array.isArray(question?.analysis) ? question.analysis : []
  const i = steps.findIndex((s) => String(s ?? '').includes('[错]'))
  return i < 0 ? null : i + 1
}

/**
 * 敷衍只认两种：没作答、口水话。（超时由调用方按 P4 记，不在这里判。）
 * 短不等于敷衍——「B」是正经答案，长度要求由各招自己提。
 */
function perfunctory(answer, options = {}) {
  const text = String(answer ?? '').trim()
  if (!text) return 'empty'
  if (fillerRatio(text) >= Number(options.fillerMax ?? 0.5)) return 'filler'
  return ''
}

const result = (verdict, judgedBy, why) => ({ verdict, judgedBy, why })

/**
 * 判一拍。
 *
 * @param {{move:string, question:object, answer:string, record?:string}} input
 * @returns {{verdict:'pass'|'wrong'|'perfunctory'|'pending', judgedBy:'code'|'model', why:string}}
 *   pending 只有「复述」会给：代码把能查的查完，剩下的交给局官判。
 */
export function judgeAnswer(input = {}) {
  const move = String(input.move ?? '')
  const q = input.question || {}
  const answer = String(input.answer ?? '').trim()
  if (!MOVES.includes(move)) return result('perfunctory', 'code', '不是认得的招式：' + move)

  // 敷衍先判：答错要重打、敷衍要升级，两条路必须分开，所以敷衍不能走进「答错」。
  const bad = perfunctory(answer, input)
  if (bad) return result('perfunctory', 'code', bad === 'filler' ? '填充词太多' : '没有作答')

  if (move === '先答后看') {
    const want = norm(q.answer)
    const got = norm(answer)
    if (!want) return result('perfunctory', 'code', '这道题没有答案，判不了')
    let ok = got === want
    // 选择题：答案是单个字母时，他写「选B」「B 吧」都算，但只认这一个字母。
    if (!ok && /^[A-Da-d]$/.test(want)) {
      const hit = got.match(/[A-Da-d]/)
      ok = Boolean(hit) && hit[0].toUpperCase() === want.toUpperCase()
    }
    // 其余按原文比对。两边都得够长才谈得上包含——否则答「1」会把「12」判成对。
    if (!ok && got.length >= 2) ok = want.includes(got) || got.includes(want)
    return result(ok ? 'pass' : 'wrong', 'code', ok ? '对上了' : '和答案不一致')
  }

  if (move === '定位') {
    const locate = norm(q.locate)
    if (!locate) return result('perfunctory', 'code', '这道题没有定位句，这一招不该用')
    if (norm(answer).length < MIN_QUOTE) return result('wrong', 'code', '引得太短：要引出决定答案的那段原文')
    const ok = locate.includes(norm(answer))
    return result(ok ? 'pass' : 'wrong', 'code', ok ? '就是这句' : '这句不是决定答案的那句')
  }

  if (move === '复述') {
    if (countChars(answer) > 60) return result('wrong', 'code', '一句话说清，别说这么长')
    if (!concreteNouns(answer).length) return result('wrong', 'code', '没说到题目里的任何东西')
    // 代码能查的查完了；概括得到不到位只有模型判得了，判完仍要过代码的措辞检查。
    return result('pending', 'model', '交给局官判概括')
  }

  if (move === '续接') {
    const record = String(input.record ?? '')
    if (!record.trim()) return result('perfunctory', 'code', '没有他的记录，判不了')
    const nouns = concreteNouns(record)
    const hit = nouns.filter((n) => answer.includes(n))
    const next = /下一步|然后|接着|接下来|再/.test(answer)
    if (!hit.length) return result('wrong', 'code', '没回忆出上一步说了什么')
    return result(next ? 'pass' : 'wrong', 'code', next ? '接着上了' : '想起了上一步，但没说出下一步')
  }

  if (move === '找茬') {
    const step = faultStep(q)
    if (!step) return result('perfunctory', 'code', '这道题没有埋错，这一招不该用')
    const byNumber = norm(answer) === String(step) || new RegExp('第\\s*' + step + '\\s*步').test(answer)
    const planted = norm(String((q.analysis || [])[step - 1] ?? '').replace('[错]', ''))
    const byQuote = planted.length >= MIN_QUOTE && norm(answer).length >= MIN_QUOTE && planted.includes(norm(answer))
    return result(byNumber || byQuote ? 'pass' : 'wrong', 'code', byNumber || byQuote ? '找对了' : '错在这一步')
  }

  // 一条规则
  if (countChars(answer) < 4) return result('wrong', 'code', '这不算一条规则')
  if (countChars(answer) > RULE_MAX) return result('wrong', 'code', '一条规则不能写这么长')
  return result('pass', 'code', '记下了')
}

/**
 * 这一拍判完往哪走。答错和敷衍在这里彻底分开记账。
 * @returns {{action:'next'|'retry'|'escalate'|'ask-model', verdict:string, escalate:boolean}}
 */
export function route(verdict) {
  if (verdict === 'pass') return { action: 'next', verdict, escalate: false }
  if (verdict === 'wrong') return { action: 'retry', verdict, escalate: false }
  if (verdict === 'pending') return { action: 'ask-model', verdict, escalate: false }
  return { action: 'escalate', verdict: 'perfunctory', escalate: true }
}

/** 「一条规则」存档：答错写下的规则，1 天、3 天后各考一次。 */
export function archiveRule(input = {}) {
  const text = String(input.text ?? '').trim()
  const at = Number(input.now) || Date.now()
  if (!text) return { ok: false, error: 'empty' }
  return {
    ok: true,
    rule: {
      text,
      questionId: String(input.questionId ?? ''),
      at,
      due: RULE_RETEST_MS.map((ms) => at + ms),
      passed: [],
    },
  }
}

/** 到点该复考的规则（同一个时间点只考一次）。 */
export function rulesDue(rules, now = Date.now()) {
  const out = []
  for (const r of Array.isArray(rules) ? rules : []) {
    if (!r || typeof r !== 'object') continue
    const due = Array.isArray(r.due) ? r.due : []
    const passed = Array.isArray(r.passed) ? r.passed : []
    const i = due.findIndex((t, k) => Number(t) <= now && !passed.includes(k))
    if (i >= 0) out.push({ rule: r, index: i, at: Number(due[i]) })
  }
  return out
}

/**
 * 一套评分单：每题一条参考推理链，逐拍对照。
 * @param {{graded:{questionId:string, move:string, verdict:string, judgedBy:string}[], questions?:object[]}} input
 */
export function scoreSheet(input = {}) {
  const graded = (Array.isArray(input.graded) ? input.graded : []).filter((g) => g && typeof g === 'object')
  const byId = new Map((Array.isArray(input.questions) ? input.questions : []).map((q) => [q.id, q]))
  const count = (v) => graded.filter((g) => g.verdict === v).length
  const pass = count('pass')
  const rows = graded.map((g) => ({
    questionId: String(g.questionId ?? ''),
    move: String(g.move ?? ''),
    verdict: String(g.verdict ?? ''),
    judgedBy: String(g.judgedBy ?? 'code'),
    chain: (byId.get(String(g.questionId ?? ''))?.analysis || []).slice(0, 8),
  }))
  return {
    total: graded.length,
    pass,
    wrong: count('wrong'),
    perfunctory: count('perfunctory'),
    pending: count('pending'),
    code: graded.filter((g) => g.judgedBy === 'code').length,
    model: graded.filter((g) => g.judgedBy === 'model').length,
    score: graded.length ? Math.round((100 * pass) / graded.length) : 0,
    rows,
  }
}

/**
 * 一份思维局协议能不能装（施工书 §3 J5 那四样：题库 / 招式 / 强度 / 题数）。
 * 只查协议自身；题库有没有货在别处查。
 */
export function validateThinking(raw) {
  const errors = []
  const err = (code, where, message) => errors.push({ code, where, message })
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    err('not-object', '', '协议必须是对象')
    return { ok: false, errors }
  }
  const known = new Set(['协议版本', 'id', '名称', '类型', '题库', '招式', '强度', '题数'])
  for (const key of Object.keys(raw)) if (!known.has(key)) err('unknown-key', key, '思维局协议里不认识的字段：' + key)
  if (!String(raw.id || '').trim()) err('bad-id', 'id', '缺 id')
  if (!String(raw['名称'] || '').trim()) err('no-name', '名称', '缺名称')
  if (String(raw['类型'] || '') !== '思维') err('bad-type', '类型', '类型必须是 思维')
  if (!String(raw['题库'] || '').trim()) err('no-bank', '题库', '缺题库 id')

  const used = Array.isArray(raw['招式']) ? raw['招式'].map((m) => String(m)) : []
  if (!used.length) err('no-moves', '招式', '至少写一招')
  const unknown = used.filter((m) => !MOVES.includes(m))
  if (unknown.length) err('bad-move', '招式', '没这一招：' + unknown.join('、'))

  const intensity = movesOf(raw['强度'])
  if (!intensity.ok) err('bad-intensity', '强度', '强度只能是 ' + intensity.list.join(' / '))
  else if (used.length) {
    const extra = used.filter((m) => !intensity.moves.includes(m))
    if (extra.length) err('move-out-of-intensity', '招式', '这些招不在「' + raw['强度'] + '」的强度里：' + extra.join('、'))
  }

  const n = Number(raw['题数'])
  if (!Number.isInteger(n) || n <= 0) err('bad-count', '题数', '题数必须是正整数')

  const majority = codeMajority(used)
  if (used.length && !majority.ok) {
    err('model-majority', '招式', '代码判的招式必须占多数（现在代码 ' + majority.code + ' 招、模型 ' + majority.model + ' 招）')
  }
  return { ok: !errors.length, errors }
}

// ---- 展开成普通步骤（第八轮 P4 的第二条）------------------------------------------------------
//
// 思维局的单位是「一题」，J2 的执行器单位是「一步」。两者不打架的办法是：开局时把一题摊成一步，
// 摊完的局跟学习局、执行局长得一模一样，执行器一行不改。
//
// 摊出来的每一步只多一样东西：过关条件是一张「招式判定」，期望值是这一招的名字，
// 再带上这道题和「他的记录」（checkPass 只把 text 交出去，题从哪来它不需要知道）。

/** 展开出来的协议版本：跟 J2 是同一个 1。这里不 import protocol.js——它 import 本文件，会成环。 */
const EXPANDED_VERSION = 1
/** 一拍的限时：默认 2 分钟。答一题够了，也不给他拖到下一局。 */
export const DEFAULT_STEP_SEC = 120

/** 这一拍问什么：题干，有选项就把选项另起一行附上。 */
function askOf(question) {
  const stem = String(question?.stem ?? '').trim()
  const options = Array.isArray(question?.options)
    ? question.options.map((o) => String(o ?? '').trim()).filter(Boolean)
    : []
  return options.length ? stem + '\n' + options.join('\n') : stem
}

/**
 * 把一份思维局协议展开成普通步骤（J2 的格式）。
 *
 * 一题一拍，第 i 题用 招式[i % 招数]。这一招在这道题上站不住（没答案 / 没定位句 / 没他的记录）
 * 就换一招；一招都站不住就整份拒绝——不拿别的招顶替这道题，也不让它带着缺口上场。
 *
 * @param {object} raw 思维局协议（题库 / 招式 / 强度 / 题数）
 * @param {{questions?:object[], limitSec?:number}} options
 *   questions：题库洗过的题（cleanQuestion 的输出）；`record` 字段是「他的记录」（续接要用）
 * @returns {{ok:true, protocol:object, plan:{id:string,questionId:string,move:string}[]}|
 *           {ok:false, errors:{code:string,where:string,message:string}[]}}
 */
export function expandThinking(raw, options = {}) {
  const check = validateThinking(raw)
  if (!check.ok) return { ok: false, errors: check.errors }
  const errors = []
  const err = (code, where, message) => errors.push({ code, where, message })

  const moves = raw['招式'].map((m) => String(m))
  // shortcut: 靠模型判的招式（复述）摊不进普通步骤——那一层没有「等模型判」的入口，
  // 摊出去只会让这一拍永远 pending。什么时候该补：boutrun 把模型判词接回步骤流转那天。
  const byModel = moves.filter((m) => JUDGE[m] === 'model')
  if (byModel.length) {
    err('need-code-judge', '招式', '这些招靠模型判，展开成普通步骤没有判它的入口：' + byModel.join('、'))
  }

  const count = Number(raw['题数'])
  const pool = (Array.isArray(options.questions) ? options.questions : [])
    .filter((q) => q && typeof q === 'object' && String(q.stem ?? '').trim())
  if (pool.length < count) {
    err('not-enough-questions', '题库', '这一局要 ' + count + ' 道题，库里只有 ' + pool.length + ' 道')
  }
  const limitSec = Math.max(10, Math.round(Number(options.limitSec) || DEFAULT_STEP_SEC))
  if (errors.length) return { ok: false, errors }

  const steps = []
  const plan = []
  for (let i = 0; i < count; i += 1) {
    const question = pool[i]
    const record = String(question.record ?? '')
    const preferred = moves[i % moves.length]
    const move = ready(question, preferred, { record }).ok
      ? preferred
      : moves.find((m) => ready(question, m, { record }).ok)
    if (!move) {
      err('no-move', '题目[' + i + ']', '这道题没有一招站得住：' + String(question.id ?? ''))
      continue
    }
    const id = 'q' + (i + 1) + '-' + move
    steps.push({
      id,
      问: askOf(question),
      限时: limitSec + 's',
      过关: { 招式判定: { move, question, record } },
      不过: '重打',
      超时: '升级',
    })
    plan.push({ id, questionId: String(question.id ?? ''), move })
  }
  if (errors.length) return { ok: false, errors }

  return {
    ok: true,
    plan,
    protocol: {
      协议版本: EXPANDED_VERSION,
      id: String(raw.id) + '-expanded',
      名称: String(raw['名称']),
      类型: '行为',
      步骤: steps,
      收局: { 判定: '按评分单收：每题一条参考链，逐拍对照' },
    },
  }
}

