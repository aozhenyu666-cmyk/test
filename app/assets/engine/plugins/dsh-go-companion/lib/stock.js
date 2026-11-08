// dsh-go-companion · 套话守卫
//
// 大肥鱼自己生成的句子如果落在这类模板上，就不是"发球"而是填空：
// "你在做什么""下一步是什么""依据是什么""加油""你可以的"。
// 命中就重写一次，还像就换一种球（类型换掉，见 serve.js）。

/** 任务书 3.3 点名的模板。改这里就是改口径，别在别处再写一份。 */
export const STOCK_TEMPLATES = ['你在做什么', '下一步是什么', '依据是什么', '加油', '你可以的']

/** 相似度门槛：≥ 它就是套话。 */
export const STOCK_THRESHOLD = 0.6

/** 只剩字和数字：标点、空白、语气助词不去管，字面比较才对得上。 */
function norm(text) {
  return String(text == null ? '' : text)
    .replace(/[\s\u3000]/g, '')
    .replace(/[，。！？、；：""''《》（）()[\]{}<>·…—～~!?,.;:'"`]/g, '')
}

function bigrams(s) {
  const out = new Set()
  for (let i = 0; i < s.length - 1; i += 1) out.add(s.slice(i, i + 2))
  return out
}

/**
 * 字面相似度，0–1。
 *
 * 先看整句相等，再看互相包含（"你在做什么呢？"包含模板，就是同一句加了语气词），
 * 最后看二元组重合度。包含判据要求短的那边至少三个字：
 * 否则一个字或两个字的回复（"你""好的"）会因为它恰好是模板的片段而被判成套话。
 * 两个字的模板（"加油"）反过来也一样，只认整句相等。
 */
export function similarity(text, template) {
  const a = norm(text)
  const b = norm(template)
  if (!a || !b) return 0
  if (a === b) return 1
  if (b.length < 3) return 0
  if (Math.min(a.length, b.length) >= 3 && (a.includes(b) || b.includes(a))) return 1
  const x = bigrams(a)
  const y = bigrams(b)
  if (!x.size || !y.size) return 0
  let hit = 0
  for (const g of x) if (y.has(g)) hit += 1
  return hit / (x.size + y.size - hit)
}

/** 和任一模板的相似度到门槛就算套话。 */
export function isStock(text) {
  for (const t of STOCK_TEMPLATES) if (similarity(text, t) >= STOCK_THRESHOLD) return true
  return false
}
