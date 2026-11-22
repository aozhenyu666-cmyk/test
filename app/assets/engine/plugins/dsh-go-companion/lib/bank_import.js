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

// 图片相对路径解析约定：绝对路径（/ 或 Windows 盘符）直接使用；
// 相对路径优先相对于 Obsidian Vault 根目录或 companion/bank-assets/ 解析。
export const GEMINI_IMPORT_PROMPT_TEMPLATE = `请把我今天收集的零散资料/笔记/错题/截图内容，整理加工成一份【混合知识卡与防遗忘题库 JSON】，方便我直接导入 DSH Go 题库中心并在锁屏刷题巩固。

请严格按以下三种形态（kind）按内容特点混排：
1. "mcq"（客观选择题，适合概念辨析、计算、真题演练，答对计 1 题进度）：
   - 必须包含 2~4 个 "选项"（纯选项正文，不要在每项里硬塞解析）
   - "答案" 填大写字母（如 "B" 或多选 "BD"）
   - "解析" 说明为什么选对及常见干扰项错因
2. "card"（知识展示/规律卡，适合方法论、思维模型、金句总结，自评「我读懂了/还不熟」计 0.7 题进度）：
   - 无需 "选项"
   - "题干" 用结构化分段写出核心规律/触发条件/易错边界
   - "答案" 可写一句核心记忆锚点（也可省略）
   - "解析" 补充典型应用或反例提醒
3. "short_qa"（总结+小问题卡，适合长材料理解与主动回忆，先想后看答案再自评，计 0.7 题进度）：
   - 无需 "选项"
   - "题干" 上半部分给精华总结，下半部分提 1 个直击本质的小问题
   - "答案" 给出清晰参考答案，"解析" 补充延伸思路

输出要求：只输出一个 \`\`\`json 代码块，格式如下（如有本地配图可加可选字段 "images"）：
\`\`\`json
{
  "名称": "今日收集 · 主题名称",
  "题目": [
    {
      "kind": "mcq",
      "题型": "概念辨析",
      "题干": "……",
      "选项": ["选项A内容", "选项B内容", "选项C内容", "选项D内容"],
      "答案": "B",
      "解析": "……"
    },
    {
      "kind": "card",
      "题型": "知识卡",
      "题干": "【核心结论】……\\n【触发场景】……\\n【避坑边界】……",
      "答案": "一句话记忆锚点",
      "解析": "补充案例或适用条件"
    },
    {
      "kind": "short_qa",
      "题型": "小问题",
      "题干": "【要点总结】……\\n\\n【思考小问题】为什么……？",
      "答案": "参考答案要点……",
      "解析": "延伸思考与评分标准"
    }
  ]
}
\`\`\``

const clean = (s) => String(s == null ? '' : s).replace(/　/g, ' ').replace(/[ \t]+/g, ' ').trim()
const toHalf = (s) => String(s || '').replace(/[Ａ-Ｚａ-ｚ０-９]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0xfee0))

export function normalizeQuestionKind(rawKind, options = [], type = '', answer = '') {
  const k = clean(rawKind).toLowerCase()
  if (['card', 'knowledge_card', 'flashcard', '展示卡', '知识卡', '规律卡', '笔记卡', '概念卡', '卡片'].includes(k)) return 'card'
  if (['short_qa', 'short-qa', 'shortqa', 'qa', 'short', '小问题', '简答', '简答题', '简答卡', '问答', '问答题', '思考题'].includes(k)) return 'short_qa'
  if (['mcq', 'choice', 'single', 'multiple', '选择', '选择题', '单选', '单选题', '多选', '多选题', '判断', '判断题'].includes(k)) return 'mcq'
  if (Array.isArray(options) && options.length >= 2) return 'mcq'
  const t = clean(type)
  if (/展示卡|知识卡|规律卡|笔记卡|卡片/.test(t)) return 'card'
  if (/小问题|简答|问答|思考题/.test(t)) return 'short_qa'
  if (/选择|单选|多选/.test(t)) return 'mcq'
  const ans = clean(answer).toUpperCase()
  if ((!Array.isArray(options) || options.length < 2) && /^[A-H]{1,4}$/.test(ans)) return 'mcq'
  return 'short_qa'
}

export function normalizeImages(rawImages) {
  if (!rawImages) return []
  const arr = Array.isArray(rawImages) ? rawImages : [rawImages]
  const out = []
  const seen = new Set()
  for (const item of arr) {
    let path = ''
    let role = 'stem'
    let caption = ''
    if (typeof item === 'string') {
      path = item.trim()
    } else if (item && typeof item === 'object') {
      path = String(item.path ?? item.url ?? item.src ?? item.file ?? '').trim()
      role = String(item.role || '').trim().toLowerCase() === 'option' ? 'option' : 'stem'
      caption = clean(item.caption ?? item.alt ?? item.title ?? '')
    }
    path = path.replace(/^<|>$/g, '').trim()
    if (!path) continue
    const key = `${role}:${path}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ path, role, caption })
  }
  return out
}

export function extractMarkdownImages(rawText) {
  const src = String(rawText ?? '')
  const images = []
  const cleaned = src.replace(/!\[([^\]]*)\]\(([^)\n]+)\)/g, (_m, alt, rawPath) => {
    const caption = clean(alt)
    let path = String(rawPath || '').trim()
    // 支持 ![alt](path "title") 语法中的标题剥离
    const titleSplit = /^<([^>]+)>/.exec(path) || /^(\S+)(?:\s+["'][^"']*["'])?$/.exec(path)
    if (titleSplit) path = titleSplit[1].trim()
    if (path) images.push({ path, role: 'stem', caption })
    return caption ? `[图：${caption}]` : '[附图]'
  })
  return { text: cleaned, images: normalizeImages(images) }
}

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
const KIND_KEYS = ['kind', '形态', '模式', 'cardType', 'questionKind']
const IMG_KEYS = ['images', 'image', '图片', '配图', 'img']
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
  return o && typeof o === 'object' && !Array.isArray(o) && typeof pick(o, STEM_KEYS) === 'string'
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
    if (looksLikeQuestion(node) && (pick(node, ANS_KEYS) !== undefined || pick(node, OPT_KEYS) !== undefined || pick(node, KIND_KEYS) !== undefined)) {
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
    const material = String(pick(o, MAT_KEYS) ?? '').trim()
    const stem = String(pick(o, STEM_KEYS) ?? '').trim()
    const options = optionsOf(o)
    const type = clean(pick(o, TYPE_KEYS) ?? '')
    const rawKind = pick(o, KIND_KEYS) ?? ''
    const rawAns = pick(o, ANS_KEYS)
    const preliminaryKind = normalizeQuestionKind(rawKind, options, type, typeof rawAns === 'string' ? rawAns : '')
    const answer = preliminaryKind === 'mcq'
      ? normalizeAnswer(rawAns, options.length, zeroBased)
      : clean(rawAns ?? '')
    const analysis = String(pick(o, ANA_KEYS) ?? '').trim()
    const images = normalizeImages(pick(o, IMG_KEYS))
    const why = !stem ? '缺题干' : (preliminaryKind !== 'card' && !answer ? '缺答案' : '')
    if (why) {
      skipped.push({ n: i + 1, why, preview: clean(stem).slice(0, 30) })
      return
    }
    questions.push(make(stem, options, answer, analysis, type, material, { kind: preliminaryKind, images }))
  })
  return { questions, skipped, format: 'json' }
}

function make(stem, options, answer, analysis, type = '', material = '', { kind = '', images = [] } = {}) {
  const matImg = extractMarkdownImages(material)
  const stemImg = extractMarkdownImages(stem)
  const matClean = clean(matImg.text)
  const stemClean = clean(stemImg.text)
  const allImages = normalizeImages([...(Array.isArray(images) ? images : []), ...matImg.images, ...stemImg.images])
  const resolvedKind = normalizeQuestionKind(kind, options, type, answer)
  const finalAnswer = resolvedKind === 'card' ? (clean(answer) || '已阅读掌握') : clean(answer)
  const finalAnalysis = resolvedKind === 'card'
    ? (clean(analysis) || '（知识展示卡）巩固理解核心规律即可')
    : (clean(analysis) || '（题库没给解析）参考答案：' + finalAnswer)
  const q = {
    题干: (matClean ? '【材料】' + matClean.slice(0, 600) + '\n' : '') + stemClean,
    ...(options.length >= 2 ? { 选项: options.slice(0, 8) } : {}),
    答案: finalAnswer,
    解析: finalAnalysis,
    kind: resolvedKind,
  }
  if (type) q.题型 = type.slice(0, 20)
  if (allImages.length) q.images = allImages
  return q
}

// ---------------------------------------------------------------- 表格（CSV / TSV）

export function parseDelimited(text) {
  const src = String(text || '').replace(/^\uFEFF/, '').replace(/\r\n?/g, '\n')
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
  const iKind = idx(KIND_KEYS)
  if (iStem < 0 || (iAns < 0 && iKind < 0)) return null
  const iAna = idx(ANA_KEYS)
  const iType = idx(TYPE_KEYS)
  const iOpts = idx(OPT_KEYS)
  const iImg = idx(IMG_KEYS)
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
    const type = iType >= 0 ? clean(r[iType]) : ''
    const rawKind = iKind >= 0 ? clean(r[iKind]) : ''
    const rawAns = iAns >= 0 ? r[iAns] : ''
    const resolvedKind = normalizeQuestionKind(rawKind, options, type, clean(rawAns))
    const answer = resolvedKind === 'mcq' ? normalizeAnswer(rawAns, options.length) : clean(rawAns)
    const images = iImg >= 0 ? normalizeImages(r[iImg]) : []
    if (!stem || (resolvedKind !== 'card' && !answer)) {
      skipped.push({ n: i + 2, why: !stem ? '缺题干' : '缺答案', preview: stem.slice(0, 30) })
      return
    }
    questions.push(make(stem, options, answer, iAna >= 0 ? clean(r[iAna]) : '', type, '', { kind: resolvedKind, images }))
  })
  return { questions, skipped, format: 'table' }
}

// ---------------------------------------------------------------- 纯文本

const OPT_LINE = /^[(（]?([A-Ha-h])[)）.、．:：]\s*(.*)$/
/** 同一行里的多个选项（可指定起始字母下标，支持两行两列排布如 A/B 后接 C/D）。 */
function splitInlineOptionsFrom(line, startIdx = 0) {
  const s = toHalf(String(line || ''))
  const marks = []
  const re = /(?:^|[\s　]|(?<=[^A-Za-z]))[(（]?([A-H])[)）.、．:：]/g
  let m
  while ((m = re.exec(s))) marks.push({ letter: m[1], at: m.index + m[0].length, start: m.index })
  const seq = []
  for (const k of marks) {
    if (k.letter === LETTERS[startIdx + seq.length]) seq.push(k)
  }
  if (seq.length < 2) return []
  return seq.map((k, i) => clean(s.slice(k.at, i + 1 < seq.length ? seq[i + 1].start : s.length)))
}

/** 同一行里的多个选项：「A.甲 B.乙 C.丙 D.丁」或「A．甲B．乙C．丙D．丁」。 */
export function splitInlineOptions(line) {
  return splitInlineOptionsFrom(line, 0)
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
  const lines = toHalf(String(text || '').replace(/^\uFEFF/, '')).replace(/\r\n?/g, '\n').split('\n').map((l) => l.replace(/　/g, ' ').trimEnd())
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

// ---------------------------------------------------------------- 标签块（--- 分隔 / 【题型】【材料】【题目】【选项】【答案】【解析】）

const TAG_SEP = /^-{3,}$/
const TAG_TYPE = /^【\s*(?:题型|类型)\s*】\s*[:：]?\s*(.*)$/
const TAG_KIND = /^(?:【\s*(?:形态|模式|kind)\s*】|kind\s*[:：])\s*(.*)$/i
const TAG_CARD = /^【\s*(?:展示卡|知识卡|规律卡|笔记卡)\s*】\s*[:：]?\s*(.*)$/
const TAG_SHORT_QA = /^【\s*(?:小问题|简答卡|简答题|问答题)\s*】\s*[:：]?\s*(.*)$/
const TAG_IMG = /^【\s*(?:图片|配图)\s*】\s*[:：]?\s*(.*)$/
const TAG_MAT = /^【\s*材料\s*】\s*[:：]?\s*(.*)$/
const TAG_STEM = /^【\s*(?:题目|题干)\s*】\s*[:：]?\s*(.*)$/
const TAG_OPT = /^【\s*选项\s*】\s*[:：]?\s*(.*)$/
const TAG_ANS = /^(?:【\s*(?:参考|正确)?答案\s*】|(?:正确|参考)?答案(?:是|为)?\s*[:：])\s*(.*)$/
const TAG_ANA = /^(?:【\s*(?:解析|答案解析|详解)\s*】|(?:解析|详解|思路|解释)\s*[:：])\s*(.*)$/

function fromTagBlocks(text) {
  const lines = toHalf(String(text || '').replace(/^\uFEFF/, ''))
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map((l) => l.replace(/　/g, ' ').trim())
  const blocks = []
  let cur = null
  const ensure = () => {
    if (!cur) cur = { section: 'stem', kind: '', type: '', material: [], stem: [], options: [], answer: '', analysis: [], images: [] }
    return cur
  }
  const hasContent = (b) => Boolean(b && (b.stem.length || b.options.length || b.answer))
  const flush = () => {
    if (cur && (cur.stem.length || cur.options.length || cur.answer || cur.material.length)) {
      blocks.push(cur)
    }
    cur = null
  }
  const pushOptionLine = (b, line) => {
    const startIdx = b.options.length
    const firstLetter = LETTERS[startIdx]
    if (firstLetter && new RegExp(`^[(（]?${firstLetter}[)）.、．:：]`).test(line)) {
      const multi = splitInlineOptionsFrom(line, startIdx)
      if (multi.length >= 2) {
        b.options.push(...multi)
        b.section = 'options'
        return true
      }
    }
    const om = OPT_LINE.exec(line)
    if (om && om[1].toUpperCase() === LETTERS[startIdx]) {
      b.options.push(clean(om[2]))
      b.section = 'options'
      return true
    }
    return false
  }

  for (const line of lines) {
    if (!line) continue
    if (TAG_SEP.test(line)) {
      flush()
      continue
    }
    let m
    if ((m = TAG_KIND.exec(line))) {
      const b = ensure()
      b.kind = clean(m[1])
      continue
    }
    if ((m = TAG_CARD.exec(line))) {
      if (hasContent(cur)) flush()
      const b = ensure()
      b.kind = 'card'
      if (!b.type) b.type = '知识卡'
      b.section = 'stem'
      if (m[1]) b.stem.push(clean(m[1]))
      continue
    }
    if ((m = TAG_SHORT_QA.exec(line))) {
      if (hasContent(cur)) flush()
      const b = ensure()
      b.kind = 'short_qa'
      if (!b.type) b.type = '小问题'
      b.section = 'stem'
      if (m[1]) b.stem.push(clean(m[1]))
      continue
    }
    if ((m = TAG_IMG.exec(line))) {
      const b = ensure()
      const rawImg = clean(m[1])
      const md = extractMarkdownImages(rawImg)
      if (md.images.length) b.images.push(...md.images)
      else if (rawImg) b.images.push({ path: rawImg, role: 'stem', caption: '' })
      continue
    }
    if ((m = TAG_TYPE.exec(line))) {
      if (hasContent(cur)) flush()
      const b = ensure()
      b.type = clean(m[1])
      b.section = 'stem'
      continue
    }
    if ((m = TAG_MAT.exec(line))) {
      if (hasContent(cur)) flush()
      const b = ensure()
      b.section = 'material'
      if (m[1]) b.material.push(clean(m[1]))
      continue
    }
    if ((m = TAG_STEM.exec(line))) {
      if (hasContent(cur)) flush()
      const b = ensure()
      b.section = 'stem'
      if (m[1]) b.stem.push(clean(m[1]))
      continue
    }
    if ((m = TAG_OPT.exec(line))) {
      const b = ensure()
      b.section = 'options'
      if (m[1] && !pushOptionLine(b, clean(m[1]))) b.options.push(clean(m[1]))
      continue
    }
    if ((m = TAG_ANS.exec(line))) {
      const b = ensure()
      const rawAns = m[1] || ''
      const anaSplit = rawAns.split(/【\s*(?:解析|答案解析|详解)\s*】\s*[:：]?\s*/)
      const ansPart = clean(anaSplit[0])
      const ansMatch = /^([A-Ha-h]{1,8}|[√×对错]|正确|错误)/.exec(ansPart)
      b.answer = b.kind === 'card' || b.kind === 'short_qa'
        ? ansPart
        : normalizeAnswer(ansMatch ? ansMatch[1] : ansPart, 0)
      if (anaSplit.length > 1) {
        const restAna = clean(anaSplit.slice(1).join(' '))
        if (restAna) b.analysis.push(restAna)
        b.section = 'analysis'
      } else {
        b.section = 'answer'
      }
      continue
    }
    if ((m = TAG_ANA.exec(line))) {
      const b = ensure()
      b.section = 'analysis'
      if (m[1]) b.analysis.push(clean(m[1]))
      continue
    }

    const b = ensure()
    if (b.section === 'material') {
      b.material.push(line)
      continue
    }
    if (b.section === 'analysis') {
      b.analysis.push(line)
      continue
    }
    if (pushOptionLine(b, line)) continue
    if (b.section === 'options' && b.options.length) {
      b.options[b.options.length - 1] += ' ' + line
    } else if (b.section === 'stem') {
      b.stem.push(line)
    }
  }
  flush()

  const questions = []
  const skipped = []
  blocks.forEach((b, i) => {
    const preliminaryKind = normalizeQuestionKind(b.kind, b.options, b.type, b.answer)
    const joiner = preliminaryKind === 'card' || preliminaryKind === 'short_qa' ? '\n' : ' '
    const stem = clean(b.stem.join(joiner))
    const material = clean(b.material.join(' '))
    const answer = preliminaryKind === 'mcq' ? normalizeAnswer(b.answer, b.options.length) : clean(b.answer)
    const analysis = clean(b.analysis.join(joiner))
    const why = !stem ? '缺题干' : (preliminaryKind !== 'card' && !answer ? '缺答案' : '')
    if (why) {
      skipped.push({ n: i + 1, why, preview: stem.slice(0, 30) })
      return
    }
    questions.push(make(stem, b.options, answer, analysis, b.type, material, { kind: preliminaryKind, images: b.images }))
  })
  return { questions, skipped, format: 'tag-blocks' }
}

const IMPORT_FORMAT_GUIDE =
  '【标准文本样例 1（编号式）】\n' +
  '1. 下列属于扩张性财政政策的是：\nA. 提高税率\nB. 增加政府支出\nC. 减少转移支付\nD. 提高准备金率\n答案：B\n解析：增加政府支出属于扩张性财政政策。\n' +
  '【标准文本样例 2（--- 标签块，支持选择题 / 展示卡 / 小问题）】\n' +
  '【题型】常识判断\n【材料】可选材料段落\n【题目】题干内容\nA. 选项一\nB. 选项二\n【答案】A\n【解析】解析内容\n---\n' +
  '【展示卡】核心结论：触发场景 → 因果链 → 下次行动\n【解析】补充记忆点'

/**
 * 认格式、解析、编号。
 * @returns {{ok:boolean, questions:object[], skipped:{n,why,preview}[], format:string, error?:string}}
 */
export function parseBankAny(raw, { offset = 0 } = {}) {
  const sRaw = typeof raw === 'string' ? raw.replace(/^\uFEFF/, '').trim() : ''
  const fenceMatch = /^```(?:json|javascript|js|markdown|md|txt)?\s*\n([\s\S]*?)\n```\s*$/i.exec(sRaw)
    || /```json\s*\n([\s\S]*?)\n```/i.exec(sRaw)
  const s = fenceMatch ? fenceMatch[1].trim() : sRaw
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
  const hasDashSep = /^\s*---+\s*$/m.test(s)
  const hasStructTag = /【\s*(?:题目|题干|题型|材料|选项|展示卡|知识卡|规律卡|笔记卡|小问题|简答卡|简答题|问答题|图片|配图|形态|模式|kind)\s*】|^\s*kind\s*[:：]/im.test(s)
  const hasAnyTag = /【\s*(?:题目|题干|题型|材料|选项|答案|参考答案|正确答案|解析|答案解析|详解|展示卡|知识卡|规律卡|小问题|简答卡)\s*】/i.test(s)
  if ((!res || !res.questions.length) && (hasStructTag || (hasDashSep && hasAnyTag))) {
    const tb = fromTagBlocks(s)
    if (tb && tb.questions.length) res = tb
  }
  if (!res || !res.questions.length) {
    const t = fromText(s)
    if (!res || t.questions.length > res.questions.length) res = t
  }
  if ((!res || !res.questions.length) && hasDashSep) {
    const tb = fromTagBlocks(s)
    if (tb && (tb.questions.length > (res?.questions?.length || 0) || (!res?.skipped?.length && tb.skipped.length))) {
      res = tb
    }
  }
  const questions = (res?.questions || []).slice(0, MAX_QUESTIONS).map((q, i) => ({ id: 'q-' + (offset + i + 1), ...q }))
  if (!questions.length) {
    const skipped = res?.skipped || []
    return {
      ok: false, questions: [], skipped, format: res?.format || 'text',
      sample: IMPORT_FORMAT_GUIDE,
      error: skipped.length
        ? '未能识别出题目格式：找到 ' + skipped.length + ' 道题，但都缺答案或题干（答案在别的文件里？把答案页一起导进来，或把答案贴在最后「参考答案 1.A 2.B…」）\n' + IMPORT_FORMAT_GUIDE
        : '未能识别出题目格式：每题要有题号和答案，或使用 --- 分隔的标签块（【题目】【答案】或【展示卡】）。\n' + IMPORT_FORMAT_GUIDE,
    }
  }
  return { ok: true, questions, skipped: res.skipped || [], format: res.format }
}

/**
 * 检测单道题的格式异常（用于导入前预览 & 已入库题库质检筛查）。
 * @returns {string[]}
 */
export function detectQuestionAnomalies(q) {
  const stem = String(q?.题干 ?? q?.stem ?? q?.question ?? '').trim()
  const rawOpts = q?.选项 ?? q?.options
  const options = Array.isArray(rawOpts) ? rawOpts.map((o) => String(o ?? '').trim()).filter(Boolean) : []
  const answer = String(q?.答案 ?? q?.answer ?? '').trim()
  const analysis = Array.isArray(q?.解析 ?? q?.analysis)
    ? (q?.解析 ?? q?.analysis).join('\n').trim()
    : String(q?.解析 ?? q?.analysis ?? '').trim()
  const type = String(q?.题型 ?? q?.type ?? '').trim()
  const images = normalizeImages(q?.images ?? q?.图片)
  const kind = normalizeQuestionKind(q?.kind ?? q?.形态, options, type, answer)
  const issues = []

  const stemBody = stem.replace(/^【材料】[^\n]*\n?/, '').trim()
  if (stem.length < 6 || (stemBody && stemBody.length < 6 && images.length === 0)) {
    issues.push('题干过短（少于 6 字）')
  }

  const ansUpper = answer.toUpperCase()
  const looksLikeChoice = kind === 'mcq' || options.length > 0 || /^[A-H]{1,4}$/.test(ansUpper) || /选择|单选|多选/.test(type)
  if (kind !== 'card' && kind !== 'short_qa' && looksLikeChoice && options.length < 2) {
    if (/(?:^|\s)[A-D][.、．)）]\s*\S+.*(?:^|\s)[B-D][.、．)）]\s*\S+/ms.test(stem)) {
      issues.push('疑似选项混入题干')
    }
    issues.push('选择题选项少于 2 个')
  }

  if (!answer) {
    issues.push('缺少答案')
  } else if (options.length >= 2) {
    const validLetters = LETTERS.slice(0, options.length)
    if (/^[A-H]+$/.test(ansUpper)) {
      const outOfRange = ansUpper.split('').some((ch) => !validLetters.includes(ch))
      if (outOfRange) issues.push('答案不在现有选项字母范围内')
    } else if (!options.some((o) => clean(o) === clean(answer))) {
      issues.push('答案不在现有选项字母范围内')
    }
  }

  if (kind !== 'card' && (!analysis || analysis.startsWith('（题库没给解析）') || /^本题参考答案为\s/.test(analysis))) {
    issues.push('缺解析')
  }

  return issues
}

/**
 * 逐题导入预览（纯函数）：生成结构化预览列表并标出可疑问题 issues[]。
 * @returns {{ok:boolean, format:string, total:number, issueCount:number, skipped:object[], error:string, items:Array<{index:number, id:string, kind:string, type:string, stem:string, options:string[], answer:string, analysis:string, images:object[], issues:string[]}>}}
 */
export function buildImportPreview(parsedResult) {
  const list = Array.isArray(parsedResult)
    ? parsedResult
    : Array.isArray(parsedResult?.questions)
      ? parsedResult.questions
      : Array.isArray(parsedResult?.题目)
        ? parsedResult.题目
        : []
  const items = list.map((q, i) => {
    const stem = String(q?.题干 ?? q?.stem ?? q?.question ?? '').trim()
    const rawOpts = q?.选项 ?? q?.options
    const options = Array.isArray(rawOpts) ? rawOpts.map((o) => String(o ?? '').trim()).filter(Boolean) : []
    const answer = String(q?.答案 ?? q?.answer ?? '').trim()
    const analysis = Array.isArray(q?.解析 ?? q?.analysis)
      ? (q?.解析 ?? q?.analysis).join('\n').trim()
      : String(q?.解析 ?? q?.analysis ?? '').trim()
    const type = String(q?.题型 ?? q?.type ?? '').trim()
    const images = normalizeImages(q?.images ?? q?.图片)
    const kind = normalizeQuestionKind(q?.kind ?? q?.形态, options, type, answer)
    const issues = detectQuestionAnomalies(q)

    return {
      index: i + 1,
      id: String(q?.id || `q-${i + 1}`),
      kind,
      type,
      stem,
      options,
      answer,
      analysis,
      ...(images.length ? { images } : {}),
      issues,
    }
  })

  return {
    ok: Boolean(parsedResult?.ok ?? items.length > 0),
    format: String(parsedResult?.format || ''),
    total: items.length,
    issueCount: items.filter((x) => x.issues.length > 0).length,
    skipped: Array.isArray(parsedResult?.skipped) ? parsedResult.skipped : [],
    error: String(parsedResult?.error || ''),
    items,
  }
}
