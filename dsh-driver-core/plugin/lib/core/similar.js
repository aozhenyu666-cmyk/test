// 字面相似度：判断两句问话是不是"又问了一遍"。
// 切词思路来自 Mika 的 MemoryIndex/Tokenize（Apache-2.0）：汉字两两相邻成词，英文数字按单词；这里按本项目风格重写。
// 只抓字面重复，抓不到"意思一样、说法不同"——这是已知局限。

const CN_NUM = { 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 }

/** "第三题" → "第3题"；只处理"第 X"这种最常见的写法。 */
export function normalizeNumbers(text) {
  return String(text ?? '').replace(/第([一二两三四五六七八九十]{1,3})/g, (_, w) => {
    if (w.length === 1) return `第${CN_NUM[w]}`
    if (w[0] === '十') return `第${10 + (CN_NUM[w[1]] ?? 0)}`
    if (w[1] === '十') return `第${CN_NUM[w[0]] * 10 + (CN_NUM[w[2]] ?? 0)}`
    return `第${w}`
  })
}

const HAN = /\p{Script=Han}/u
const WORD = /[A-Za-z0-9]+/y

/** 切成词集合：连续汉字取相邻两字（单字成段时保留单字），英文数字取整词小写。 */
export function terms(text) {
  const s = normalizeNumbers(text)
  const out = new Set()
  let han = ''
  const flush = () => {
    if (han.length === 1) out.add(han)
    for (let i = 0; i + 1 < han.length; i++) out.add(han.slice(i, i + 2))
    han = ''
  }
  for (let i = 0; i < s.length;) {
    const c = s[i]
    if (HAN.test(c)) { han += c; i++; continue }
    flush()
    WORD.lastIndex = i
    const m = WORD.exec(s)
    if (m) { out.add(m[0].toLowerCase()); i += m[0].length } else i++
  }
  flush()
  return out
}

export function jaccard(a, b) {
  const A = a instanceof Set ? a : terms(a)
  const B = b instanceof Set ? b : terms(b)
  if (!A.size && !B.size) return 1
  let inter = 0
  for (const x of A) if (B.has(x)) inter++
  return inter / (A.size + B.size - inter)
}

/** 和一组旧问话里最像的那句的相似度。 */
export function maxSimilarity(text, others) {
  const t = terms(text)
  let best = 0
  for (const o of others ?? []) best = Math.max(best, jaccard(t, terms(o)))
  return best
}
