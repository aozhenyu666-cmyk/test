// dsh-go-companion · 题库（第八轮 J6）
//
// 一道题至少要能撑起一次判定：有答案、有解析。没有这两样的题不许进库——
// 缺了就会在局里变成「让模型临场补一个标准答案」，那等于自己出题自己判，分数没有意义。
//
// 字段（施工书 §3 J6 那张表）：
//   题干    原文，必填
//   选项    可选；有则「先答后看」可用
//   答案    必填
//   解析    必填；导入时拆成有序步骤（打分靠这条参考链示范「怎么思考」）
//   定位句  可选；有则「定位」可用。必须是题干里的原文片段，否则定位判不出来
//   题型 / 来源 / 年份   用于统计
//
// 只做格式与完整性，不判对错——判在 thinking.js。也不出题。
//
// ---- 上游参考（施工书 §3 J6 说的「一手」，已核对过实际字段）--------------------------
//
//   changliu/legacy/yishou/app/src/main/java/com/yishou/app/bank/Bank.kt（BankItem + BankParser）
//   同目录 BankImport.kt、judge/LockedJson.kt；测试夹具见 app/src/test/.../bank/BankTest.kt
//
// 上游 BankItem 的真实字段：
//   Choice: type, material, question, options(List<String>), answer(Int? 0–3),
//           evidence(Set<Int> 句号序号), explain(String), number(Int?), flaws(Map<Int,String>), points
//   Essay:  type, material, question, points(List<String>), limit(Int?), explain
//
// 上游的导入入口是粘贴文本而不是 JSON，认【题型】【材料】【题目】【答案】【依据】【解析】【要点】
// 【错因】【字数】这些标签，选项认 A. / A、/ A．/ (A) / A)，分题认 --- 或「第 3 题」「3.」。
//
// 本文件当前与上游还有三处不一致，是下一步要改的（不是没想到，是这一轮没改）：
//   1. 答案：这里存文本（'B' / '600 吨'），上游存选项下标 Int? 0–3。
//   2. 依据：这里存一句定位句原文，上游存 evidence:Set<Int>——材料第几句。
//      「定位」这一招真正要比的是句号，不是字符串。
//   3. 没答案的题：这里整份退回，上游保留（answer=null，做完再点官方答案）。
//      更贴上游的做法是保留题目、只把「先答后看」对这道题标为不可用。
// 改这三处要同时改 thinking.js 的判定与两侧测试，所以单开一轮，不夹在这里。

export const BANK_VERSION = 1

const QUESTION_KEYS = new Set(['id', '题干', '选项', '答案', '解析', '定位句', '题型', '来源', '年份'])
const TOP_KEYS = new Set(['题库版本', 'id', '名称', '题目'])

function isPlainObject(v) {
  return Boolean(v) && typeof v === 'object' && !Array.isArray(v)
}

function text(v) {
  return typeof v === 'string' ? v.trim() : ''
}

/**
 * 解析拆成有序步骤。一行一步；整段没换行就按句号类标点切。
 * 拆出来是空的就是没有解析，不许过。
 */
export function splitAnalysis(raw) {
  const list = Array.isArray(raw)
    ? raw.map(text)
    : String(raw ?? '').split(/\r?\n/).map(text)
  const flat = list.flatMap((line) => (line ? line.split(/(?<=[。！？；])/).map(text).filter(Boolean) : []))
  return flat.length ? flat : (Array.isArray(raw) ? [] : (text(raw) ? [text(raw)] : []))
}

/**
 * 洗一道题。坏题不吞、不猜、不改写：原样把错误带回去，导入方决定要不要整份退回。
 * @returns {{ok:true, question:object, warnings:string[]}|{ok:false, errors:{code:string,where:string,message:string}[]}}
 */
export function cleanQuestion(raw, index = 0) {
  const errors = []
  const warnings = []
  const err = (code, where, message) => errors.push({ code, where, message })
  if (!isPlainObject(raw)) {
    err('not-object', '题目[' + index + ']', '每道题必须是对象')
    return { ok: false, errors }
  }
  for (const key of Object.keys(raw)) {
    if (!QUESTION_KEYS.has(key)) err('unknown-key', '题目[' + index + '].' + key, '题里不认识的字段：' + key)
  }
  const stem = text(raw['题干'])
  if (!stem) err('no-stem', '题目[' + index + '].题干', '缺题干')
  const answer = text(raw['答案'])
  if (!answer) err('no-answer', '题目[' + index + '].答案', '缺答案——没有答案的题判不了，也不许让模型临场补')
  const analysis = splitAnalysis(raw['解析'])
  if (!analysis.length) err('no-analysis', '题目[' + index + '].解析', '缺解析——打分要靠参考推理链，没有就不许进库')

  let options = []
  if (raw['选项'] !== undefined) {
    if (!Array.isArray(raw['选项'])) err('bad-options', '题目[' + index + '].选项', '选项必须是数组')
    else {
      options = raw['选项'].map(text).filter(Boolean)
      if (options.length < 2) err('bad-options', '题目[' + index + '].选项', '选项至少两个')
    }
  }

  // 定位句必须是题干里的原文，否则「定位」这一招没有可判的依据。
  let locate = text(raw['定位句'])
  if (locate && stem && !stem.includes(locate)) {
    warnings.push('定位句不在题干里，这一招对这道题不可用：' + locate.slice(0, 20))
    locate = ''
  }

  if (errors.length) return { ok: false, errors }
  return {
    ok: true,
    warnings,
    question: {
      id: text(raw.id) || 'q' + (index + 1),
      stem,
      options,
      answer,
      analysis,
      locate,
      type: text(raw['题型']),
      source: text(raw['来源']),
      year: Number.isInteger(Number(raw['年份'])) && Number(raw['年份']) > 0 ? Number(raw['年份']) : 0,
    },
  }
}

/**
 * 洗一份题库。解析不了或字段不认识就整份退回——半份题库进库比不进更麻烦。
 * @param {string|object|object[]} input JSON 文本 / {题目:[...]} / [...]
 * @returns {{ok:true, bank:object, warnings:string[]}|{ok:false, errors:object[]}}
 */
export function parseBank(input) {
  let raw = input
  if (typeof input === 'string') {
    const t = input.trim()
    if (!t) return { ok: false, errors: [{ code: 'empty', where: '', message: '题库是空的' }] }
    try {
      raw = JSON.parse(t)
    } catch (error) {
      return { ok: false, errors: [{ code: 'bad-json', where: '', message: '题库只收 JSON：' + String((error && error.message) || error) }] }
    }
  }
  const errors = []
  const warnings = []
  let rows = raw
  let id = ''
  let name = ''
  if (isPlainObject(raw)) {
    for (const key of Object.keys(raw)) {
      if (!TOP_KEYS.has(key)) errors.push({ code: 'unknown-key', where: key, message: '题库里不认识的字段：' + key })
    }
    const version = raw['题库版本']
    if (version !== undefined && Number(version) !== BANK_VERSION) {
      errors.push({ code: 'bad-version', where: '题库版本', message: '只认 ' + BANK_VERSION })
    }
    id = text(raw.id)
    name = text(raw['名称'])
    rows = raw['题目']
  }
  if (!Array.isArray(rows)) {
    errors.push({ code: 'no-questions', where: '题目', message: '题库里要有 题目 数组' })
    return { ok: false, errors }
  }
  if (!rows.length) errors.push({ code: 'no-questions', where: '题目', message: '题库里一道题都没有' })

  const questions = []
  const seen = new Set()
  rows.forEach((row, i) => {
    const r = cleanQuestion(row, i)
    if (!r.ok) {
      errors.push(...r.errors)
      return
    }
    warnings.push(...r.warnings)
    if (seen.has(r.question.id)) {
      errors.push({ code: 'dup-id', where: '题目[' + i + '].id', message: '题号重复：' + r.question.id })
      return
    }
    seen.add(r.question.id)
    questions.push(r.question)
  })
  if (errors.length) return { ok: false, errors }
  return { ok: true, warnings, bank: { version: BANK_VERSION, id: id || 'bank', name: name || id || '题库', questions } }
}

/** 按题型 / 年份数一遍（面板和统计用）。 */
export function bankStats(bank) {
  const questions = Array.isArray(bank?.questions) ? bank.questions : []
  const byType = {}
  const byYear = {}
  for (const q of questions) {
    const t = q.type || '未分类'
    byType[t] = (byType[t] || 0) + 1
    if (q.year) byYear[q.year] = (byYear[q.year] || 0) + 1
  }
  return { total: questions.length, byType, byYear }
}

/** 取一道题（没有就 null）。 */
export function questionOf(bank, id) {
  const want = String(id || '')
  return (Array.isArray(bank?.questions) ? bank.questions : []).find((q) => q.id === want) || null
}
