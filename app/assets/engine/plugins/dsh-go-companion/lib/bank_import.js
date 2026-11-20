// 2026-11-21 题库导入第二版（纯函数）。
//
// 真机上「导不进去」的几个原因，这里一个个对上：
//   1. 题目在前、答案集中在最后（「参考答案 1.A 2.B…」「1-5 ABCDA」「答案与解析：1.【答案】B【解析】…」）——
//      老解析器要求答案紧跟每道题，整份一题都认不出来。
//   2. 题号切块太粗：行首「2.5 亿元」「2010.」也被当成新题。现在题号按顺序认（下一题只能是上一题 +1，或者新一节从 1 开始）。
//   3. JSON 只认中文字段、缺解析整份退回。现在中英文字段都认（question / stem / options / answer / analysis…），
//      选项可以是数组、{A:..} 对象或 A/B/C/D 平铺字段；答案可以是字母、多选、数字下标；缺解析也收（标出来）。
//   4. 表格（CSV / TSV / Excel 抽成的制表符文本）：按表头认列。
//   5. 资料分析「根据以下资料，回答 101~105 题」：材料拼到这几题的题干前面（截 600 字）。
// 认不出的题不吞：逐题说为什么（缺答案 / 缺题干），导入方告诉他。
//
// 输出和老格式一致：{ 题干, 选项?, 答案, 解析, 题型? }（bank_unlock / 锁屏刷题照旧用）。

export const MAX_QUESTIONS = 5000
const LETTERS = 'ABCDEFGH'

const clean = (s) => String(s == null ? '' : s).replace(/　/g, ' ').replace(/[ \t]+/g, ' ').trim()
const toHalf = (s) => String(s || '').replace(/[Ａ-Ｚａ-ｚ０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))

/** 答案规整：「B」「b」「BD」「B、D」「正确」「√」「1」（配合选项个数）。 */
export function normalizeAnswer(raw, optionCount = 0, zeroBased = false) {
  if (raw === null || raw === undefined) return ''
  if (Array.isArray(raw)) return raw.map((x) => normalizeAnswer(x, optionCount, zeroBased)).join('')
  if (typeof raw === 'number' && optionCount > 0) {
    const i = zeroBased ? raw : raw - 1
    return i >= 0 && i < optionCount ? LETTERS[i] : ''
  }
  let s = toHalf(clean(raw))
  s = s.replace(/^(?:正确)?答案(?:是|为)?\s*[:：]?\s*/, '').replace(/^选\s*/, '')
  if (/^(对|正确|√|✓|T|True|是)$/i.test(s)) return optionCount ? 'A' : '正确'
  if (/^(错|错误|×|✗|F|False|否)$/i.test(s)) return optionCount ? 'B' : '错误'
  const letters = s.toUpperCase().replace(/[\s,，、;；/和与]+/g, '')
  if (/^[A-H]{1,8}$/.test(letters)) return [...new Set(letters.split(''))].sort().join('')
  if (/^\d+$/.test(s) && optionCount > 0) return normalizeAnswer(Number(s), optionCount, zeroBased)
  return s.slice(0, 120)
}

const pick = (o, keys) => {
  for (const k of keys) {
    if (o[k] !== undefined && o[k] !== null && o[k] !== '') return o[k]
    const hit = Object.keys(o).find((x) => x.toLowerCase() === k.toLowerCase())
    if (hit && o[hit] !== undefined && o[hit] !== null && o[hit] !== '') return o[hit]
  }
  return undefined
}
const STEM_KEYS = ['题干', '题目', '问题', 'question', 'stem', 'title', 'content', 'q', 'text', 'body']
const OPT_KEYS = ['选项', 'options', 'choices', 'option', 'answers_list']
const ANS_KEYS = ['答案', '正确答案', '参考答案', 'answer', 'correct', 'correct_answer', 'key', 'right', 'ans']
const ANA_KEYS = ['解析', '答案解析', '解释', 'analysis', 'explanation', 'explain', 'solution', 'parse', 'note']
const TYPE_KEYS = ['题型', '类型', 'type', 'category', '科目', 'subject']
const MAT_KEYS = ['材料', 'material', 'passage', 'context']

function optionsOf(o) {
  const raw = pick(o, OPT_KEYS)
  if (Array.isArray(raw)) {
    return raw.map((x) => (x && typeof x === 'object' ? clean(pick(x, ['text', 'content', 'value', 'label']) ?? '') : clean(x)))
      .map((x) => x.replace(/^[(（]?[A-Ha-h][)）.、．:：]\s*/, '')).filter(Boolean)
  }
  if (raw && typeof raw === 'object') {
    return Object.keys(raw).sort().map((k) => clean(raw[k])).filter(Boolean)
  }
  if (typeof raw === 'string' && raw.trim()) return splitInlineOptions(raw)
  // 平铺：A / B / C / D 或 选项A / optionA
  const flat = []
  for (const L of LETTERS) {
    const v = pick(o, [L, '选项' + L, 'option' + L, 'option_' + L.toLowerCase(), L.toLowerCase()])
    if (v === undefined) break
    flat.push(clean(v))
  }
  return flat.filter(Boolean)
}

function looksLikeQuestion(o) {
  return o && typeof o === 'object' && !Array.isArray(o) && pick(o, STEM_KEYS) !== undefined
}

/** 从任意 JSON 里把「像题的对象」都找出来（{题目:[…]}、{data:{list:[…]}}、[{…}]、分节嵌套都行）。 */
function collectJsonQuestions(node, out, depth = 0) {
  if (out.length >= MAX_QUESTIONS || depth > 6 || !node) return
  if (Array.isArray(node)) {
    for (const x of node) {
      if (looksLikeQuestion(x)) out.push(x)
      else collectJsonQuestions(x, out, depth + 1)
    }
    return
  }
  if (typeof node === 'object') {
    if (looksLikeQuestion(node) && (pick(node, ANS_KEYS) !== undefined || pick(node, OPT_KEYS) !== undefined)) {
      out.push(node)
      return
    }
    for (const v of Object.values(node)) if (v && typeof v === 'object') collectJsonQuestions(v, out, depth + 1)
  }
}

function fromJson(obj) {
  const raw = []
  collectJsonQuestions(obj, raw)
  const answersNumeric = raw.map((o) => pick(o, ANS_KEYS)).filter((a) => typeof a === 'number' || /^\d+$/.test(String(a ?? '')))
  const zeroBased = answersNumeric.some((a) => Number(a) === 0)
  const questions = []
  const skipped = []
  raw.forEach((o, i) => {
    const material = clean(pick(o, MAT_KEYS) ?? '')
    const stem = clean(pick(o, STEM_KEYS) ?? '')
    const options = optionsOf(o)
    const answer = normalizeAnswer(pick(o, ANS_KEYS), options.length, zeroBased)
    const analysis = clean(pick(o, ANA_KEYS) ?? '')
    const why = !stem ? '缺题干' : !answer ? '缺答案' : ''
    if (why) {
      skipped.push({ n: i + 1, why, preview: stem.slice(0, 30) })
      return
    }
    questions.push(make(stem, options, answer, analysis, clean(pick(o, TYPE_KEYS) ?? ''), material))
  })
  return { questions, skipped, format: 'json' }
}

function make(stem, options, answer, analysis, type = '', material = '') {
  const q = {
    题干: (material ? '【材料】' + material.slice(0, 600) + '\n' : '') + stem,
    ...(options.length >= 2 ? { 选项: options.slice(0, 8) } : {}),
    答案: answer,
    解析: analysis || '（题库没给解析）参考答案：' + answer,
  }
  if (type) q.题型 = type.slice(0, 20)
  return q
}

// ---------------------------------------------------------------- 表格（CSV / TSV）

export function parseDelimited(text) {
  const src = String(text || '').replace(/\r\n?/g, '\n')
  const firstLine = src.split('\n', 1)[0]
  const delim = (firstLine.match(/\t/g) || []).length >= (firstLine.match(/,/g) || []).length && firstLine.includes('\t') ? '\t' : ','
  const rows = []
  let row = []
  let cell = ''
  let q = false
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (q) {
      if (c === '"' && src[i + 1] === '"') {
        cell += '"'
        i++
      } else if (c === '"') q = false
      else cell += c
    } else if (c === '"' && cell === '') q = true
    else if (c === delim) {
      row.push(cell)
      cell = ''
    } else if (c === '\n') {
      row.push(cell)
      rows.push(row)
      row = []
      cell = ''
    } else cell += c
  }
  if (cell || row.length) {
    row.push(cell)
    rows.push(row)
  }
  return rows.filter((r) => r.some((x) => String(x).trim()))
}

function fromTable(rows) {
  if (rows.length < 2) return null
  const head = rows[0].map((h) => clean(h))
  const idx = (keys) => head.findIndex((h) => keys.some((k) => h.toLowerCase() === k.toLowerCase() || h.includes(k)))
  const iStem = idx(STEM_KEYS)
  const iAns = idx(ANS_KEYS)
  if (iStem < 0 || iAns < 0) return null
  const iAna = idx(ANA_KEYS)
  const iType = idx(TYPE_KEYS)
  const iOpts = idx(OPT_KEYS)
  const letterCols = []
  for (const L of LETTERS) {
    const j = head.findIndex((h) => new RegExp('^(?:选项|option)?\\s*' + L + '$', 'i').test(h.replace(/[\s_]/g, '')))
    if (j < 0) break
    letterCols.push(j)
  }
  const questions = []
  const skipped = []
  rows.slice(1).forEach((r, i) => {
    const stem = clean(r[iStem])
    let options = letterCols.map((j) => clean(r[j])).filter(Boolean)
    if (!options.length && iOpts >= 0) options = splitInlineOptions(r[iOpts])
    const answer = normalizeAnswer(r[iAns], options.length)
    if (!stem || !answer) {
      skipped.push({ n: i + 2, why: !stem ? '缺题干' : '缺答案', preview: stem.slice(0, 30) })
      return
    }
    questions.push(make(stem, options, answer, iAna >= 0 ? clean(r[iAna]) : '', iType >= 0 ? clean(r[iType]) : ''))
  })
  return { questions, skipped, format: 'table' }
}

// ---------------------------------------------------------------- 纯文本

const OPT_LINE = /^[(（]?([A-Ha-h])[)）.、．:：]\s*(.*)$/
/** 同一行里的多个选项：「A.甲 B.乙 C.丙 D.丁」或「A．甲B．乙C．丙D．丁」。 */
export function splitInlineOptions(line) {
  const s = toHalf(String(line || ''))
  const marks = []
  const re = /(?:^|[\s　]|(?<=[^A-Za-z]))[(（]?([A-H])[)）.、．:：]/g
  let m
  while ((m = re.exec(s))) marks.push({ letter: m[1], at: m.index + m[0].length, start: m.index })
  // 必须是 A、B、C… 依次出现
  const seq = []
  for (const k of marks) if (k.letter === LETTERS[seq.length]) seq.push(k)
  if (seq.length < 2) return []
  return seq.map((k, i) => clean(s.slice(k.at, i + 1 < seq.length ? seq[i + 1].start : s.length)))
}

const SECTION_HEAD = /^(?:第[一二三四五六七八九十]+部分|[一二三四五六七八九十]+[、.．]\s*\S{2,12}|【?(?:常识判断|言语理解与表达|言语理解|数量关系|判断推理|资料分析|政治理论|申论)】?)\s*$/
const ANSWER_HEAD = /^(?:【?(?:参考答案|答案(?:与|及|和)?解析|答案详解|答案)】?)\s*[:：]?\s*$/
const QNUM = /^(?:第\s*(\d{1,4})\s*题|[(（]\s*(\d{1,4})\s*[)）]|(\d{1,4})\s*[.、．)）](?!\d))\s*(.*)$/
const INLINE_ANS = /^(?:【\s*(?:参考)?答案\s*】|(?:正确|参考)?答案(?:是|为)?)\s*[:：]?\s*(.+)$/
const INLINE_ANA = /^(?:【\s*(?:解析|答案解析|详解)\s*】|解析|详解|思路|解释)\s*[:：]?\s*(.*)$/

/** 答案区里的「1.A 2.B 3.C」「1-5 ABCDA」「1．【答案】B【解析】……」。 */
function parseAnswerKey(lines) {
  const map = new Map()
  const ana = new Map()
  let cur = 0
  for (const line0 of lines) {
    const line = toHalf(line0)
    // 区间：1-5 ABCDA / 1~5：ABCDA
    let any = false
    for (const m of line.matchAll(/(\d{1,4})\s*[-~～—–至到]\s*(\d{1,4})\s*[:：.、]?\s*([A-Ha-h]{2,})/g)) {
      const a = Number(m[1])
      const letters = m[3].toUpperCase()
      for (let k = 0; k < letters.length && a + k <= Number(m[2]); k++) map.set(a + k, letters[k])
      any = true
    }
    if (any) continue
    // 一行多个：1.A 2.B 3.CD
    const pairs = [...line.matchAll(/(\d{1,4})\s*[.、．:：)）]?\s*([A-Ha-h]{1,4}|[√×对错])(?![A-Za-z一-龥])/g)]
    if (pairs.length >= 2) {
      for (const m of pairs) map.set(Number(m[1]), normalizeAnswer(m[2]))
      continue
    }
    // 逐题：1．【答案】B  /  1. 答案：B  / 【解析】……
    const qn = QNUM.exec(line.trim())
    if (qn) {
      cur = Number(qn[1] || qn[2] || qn[3])
      const rest = qn[4] || ''
      const a = /(?:【\s*答案\s*】|答案\s*[:：]?)\s*([A-Ha-h]{1,4}|[√×对错]|正确|错误)/.exec(rest) || /^([A-Ha-h]{1,4})(?:\s|$|[。.【])/.exec(rest)
      if (a) map.set(cur, normalizeAnswer(a[1]))
      const an = /【\s*解析\s*】\s*(.*)$/.exec(rest) || /解析\s*[:：]\s*(.*)$/.exec(rest)
      if (an && an[1]) ana.set(cur, clean(an[1]))
      continue
    }
    if (cur) {
      const a = INLINE_ANS.exec(line.trim())
      if (a && !map.has(cur)) {
        map.set(cur, normalizeAnswer(a[1]))
        continue
      }
      const an = INLINE_ANA.exec(line.trim())
      const t = an ? an[1] : line.trim()
      if (t) ana.set(cur, ((ana.get(cur) || '') + ' ' + t).trim())
    }
  }
  return { map, ana }
}

function fromText(text) {
  const lines = toHalf(String(text || '')).replace(/\r\n?/g, '\n').split('\n').map((l) => l.replace(/　/g, ' ').trimEnd())
  // 答案区：从最后一个「参考答案 / 答案与解析」标题开始，或者末尾连着很多「1.A 2.B」的行。
  let ansAt = -1
  for (let i = lines.length - 1; i >= 0; i--) {
    if (ANSWER_HEAD.test(lines[i].trim())) {
      ansAt = i
      break
    }
  }
  if (ansAt < 0) {
    let j = lines.length - 1
    while (j >= 0 && (!lines[j].trim() || /(\d{1,4})\s*[.、．:：)）]?\s*[A-Ha-h]{1,4}.*(\d{1,4})\s*[.、．:：)）]?\s*[A-Ha-h]/.test(lines[j]) || /\d+\s*[-~～—–]\s*\d+\s*[:：]?\s*[A-Ha-h]{2,}/.test(lines[j]))) j--
    if (j < lines.length - 1 && lines.slice(j + 1).some((l) => l.trim())) ansAt = j + 1
  }
  const body = ansAt >= 0 ? lines.slice(0, ansAt) : lines
  const key = ansAt >= 0 ? parseAnswerKey(lines.slice(ansAt + (ANSWER_HEAD.test(lines[ansAt]?.trim() || '') ? 1 : 0))) : { map: new Map(), ana: new Map() }

  // 按题号顺序切块
  const blocks = []
  let cur = null
  let expected = 0
  let section = ''
  let material = { text: '', from: 0, to: 0 }
  let matBuf = null
  const flush = () => {
    if (cur) blocks.push(cur)
    cur = null
  }
  for (const raw of body) {
    const line = raw.trim()
    if (!line) continue
    if (SECTION_HEAD.test(line)) {
      flush()
      section = line.replace(/^[一二三四五六七八九十]+[、.．]\s*|第[一二三四五六七八九十]+部分\s*|[【】]/g, '').slice(0, 12)
      continue
    }
    const range = /回答\s*(\d{1,4})\s*[~～\-—–至到]\s*(\d{1,4})\s*题/.exec(line)
    if (range) {
      flush()
      material = { text: '', from: Number(range[1]), to: Number(range[2]) }
      matBuf = material
      continue
    }
    const qn = QNUM.exec(line)
    const num = qn ? Number(qn[1] || qn[2] || qn[3]) : 0
    const isNext = qn && (num === expected + 1 || (num === 1 && expected > 0) || (expected === 0 && num >= 1) || (material.from && num === material.from))
    if (isNext) {
      flush()
      matBuf = null
      expected = num
      cur = { n: num, section: 'stem', stem: [], options: [], answer: '', analysis: [], type: section, material: material.from && num >= material.from && num <= material.to ? material.text : '' }
      const rest = qn[4] || ''
      if (rest) cur.stem.push(rest)
      continue
    }
    if (matBuf && !cur) {
      matBuf.text = (matBuf.text + ' ' + line).trim().slice(0, 1200)
      continue
    }
    if (!cur) continue
    const a = INLINE_ANS.exec(line)
    if (a) {
      const ans = /^([A-Ha-h]{1,4}|[√×对错]|正确|错误)/.exec(toHalf(a[1].trim()))
      cur.answer = normalizeAnswer(ans ? ans[1] : a[1], 0)
      const restAna = /【\s*解析\s*】\s*(.*)$/.exec(a[1])
      if (restAna && restAna[1]) cur.analysis.push(restAna[1])
      cur.section = 'answer'
      continue
    }
    const an = INLINE_ANA.exec(line)
    if (an) {
      if (an[1]) cur.analysis.push(an[1])
      cur.section = 'analysis'
      continue
    }
    if (cur.section === 'analysis') {
      cur.analysis.push(line)
      continue
    }
    const multi = splitInlineOptions(line)
    if (multi.length >= 2 && /^[(（]?A[)）.、．:：]/.test(line)) {
      cur.options.push(...multi)
      cur.section = 'options'
      continue
    }
    const om = OPT_LINE.exec(line)
    if (om && om[1].toUpperCase() === LETTERS[cur.options.length]) {
      cur.options.push(clean(om[2]))
      cur.section = 'options'
      continue
    }
    if (cur.section === 'options' && cur.options.length) cur.options[cur.options.length - 1] += ' ' + line
    else if (cur.section === 'stem') cur.stem.push(line)
  }
  flush()

  // 答案区按题号对；题号整卷重复（每节从 1 开始）时按出现顺序对
  const numbersRepeat = new Set(blocks.map((b) => b.n)).size < blocks.length
  const keyList = [...key.map.entries()].sort((a, b) => a[0] - b[0])
  const questions = []
  const skipped = []
  blocks.forEach((b, i) => {
    const stem = clean(b.stem.join(' '))
    let answer = b.answer
    let analysis = clean(b.analysis.join(' '))
    if (!answer) {
      answer = numbersRepeat ? (keyList[i]?.[1] || '') : (key.map.get(b.n) || '')
      if (!analysis) analysis = clean(key.ana.get(b.n) || '')
    }
    answer = normalizeAnswer(answer, b.options.length)
    const why = !stem ? '缺题干' : !answer ? '缺答案' : ''
    if (why) {
      skipped.push({ n: b.n, why, preview: stem.slice(0, 30) })
      return
    }
    questions.push(make(stem, b.options, answer, analysis, b.type, b.material))
  })
  return { questions, skipped, format: ansAt >= 0 ? 'text+key' : 'text' }
}

/**
 * 认格式、解析、编号。
 * @returns {{ok:boolean, questions:object[], skipped:{n,why,preview}[], format:string, error?:string}}
 */
export function parseBankAny(raw, { offset = 0 } = {}) {
  const s = typeof raw === 'string' ? raw.replace(/^﻿/, '').trim() : ''
  let res = null
  if (raw && typeof raw === 'object') res = fromJson(raw)
  else if (s.startsWith('{') || s.startsWith('[')) {
    try {
      res = fromJson(JSON.parse(s))
    } catch {
      res = null
    }
  }
  if (!res || !res.questions.length) {
    const firstLine = s.split('\n', 1)[0]
    if (/\t/.test(firstLine) || (/,/.test(firstLine) && STEM_KEYS.some((k) => firstLine.toLowerCase().includes(k.toLowerCase())))) {
      const t = fromTable(parseDelimited(s))
      if (t && t.questions.length) res = t
    }
  }
  if (!res || !res.questions.length) {
    const t = fromText(s)
    if (!res || t.questions.length > res.questions.length) res = t
  }
  const questions = res.questions.slice(0, MAX_QUESTIONS).map((q, i) => ({ id: 'q-' + (offset + i + 1), ...q }))
  if (!questions.length) {
    return {
      ok: false, questions: [], skipped: res.skipped || [], format: res.format,
      error: (res.skipped || []).length
        ? '找到 ' + res.skipped.length + ' 道题，但都缺答案或题干（答案在别的文件里？把答案页一起导进来，或把答案贴在最后「参考答案 1.A 2.B…」）'
        : '没认出题目：每题要有题号和答案（答案可以紧跟每题，也可以统一放在最后）',
    }
  }
  return { ok: true, questions, skipped: res.skipped || [], format: res.format }
}
