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

// ---- B1：信息增量守卫 -------------------------------------------------------------------------
//
// 套话不只是"命中模板"这一类。还有两种同样等于没开口：
//   1. 和自己最近说的几句几乎一样（换了个说法，信息量是零）；
//   2. 整句没有一个来自他实际情况的具体名词（当前状态 / 记忆 / 暗思考产物 / 立场都没有）。
// 这一层只做判定，不改台词：判不过就交回调用方去重写一次，重写还不过就这一轮不发。

/** 和"她自己最近几句"比：默认比最近 5 条。 */
export const RECENT_WINDOW = 5
/** 二元组重合率到这个数就算重话（0–1）。 */
export const DUP_MAX = 0.6
/** 短于这个字数的不判重话：短句本身就没几个二元组，重合率没有意义。 */
export const SHORT_OK = 6
/** 名词池最多收多少个，别把整段 prompt 都拿来比。 */
export const NOUNS_MAX = 60
/**
 * 手上至少要有这么多个具体名词才判 no-content。
 * 池子薄的时候（刚装、今天什么都没定、记忆是空的）没东西可说不是她的错，判了就是硬凑。
 */
export const NOUNS_MIN = 8
/** 这一轮不发的账本状态。它既不是失败也不是没接。 */
export const SKIP_STATE = 'skip-no-content'

/**
 * 她自己最近 window 条真发出去的句子（旧→新）。
 * 只看 generated：没发出去的不算她的原话。
 */
export function recentOwnTexts(entries, window = RECENT_WINDOW) {
  const out = []
  for (let i = (Array.isArray(entries) ? entries : []).length - 1; i >= 0 && out.length < window; i -= 1) {
    const e = entries[i]
    if (!e || e.state !== 'generated') continue
    const t = norm(e.text)
    if (t) out.push(t)
  }
  return out.reverse()
}

/** 两句的二元组重合率（交集 / 并集），0–1。比自己和自己 = 1。 */
export function overlapOf(a, b) {
  const x = bigrams(norm(a))
  const y = bigrams(norm(b))
  if (!x.size || !y.size) return 0
  let hit = 0
  for (const g of x) if (y.has(g)) hit += 1
  return hit / (x.size + y.size - hit)
}

/** 和最近几句里最像的那句的重合率。没有可比的就是 0。 */
export function dupOf(text, recent) {
  let worst = 0
  for (const r of Array.isArray(recent) ? recent : []) {
    const v = overlapOf(text, r)
    if (v > worst) worst = v
  }
  return worst
}

const NOUN_STOP = new Set(['我们', '你们', '他们', '什么', '怎么', '为什么', '可以', '就是', '这个', '那个', '现在', '已经'])

/** 从任意来源（字符串 / 字符串数组 / 带 text|task|title 的对象）里收具体名词，去重保序。 */
export function nounsOf(sources) {
  const out = []
  const push = (w) => {
    if (out.length >= NOUNS_MAX) return
    if (!w || w.length < 2 || NOUN_STOP.has(w)) return
    if (!out.includes(w)) out.push(w)
  }
  const walk = (v, depth) => {
    if (v == null || out.length >= NOUNS_MAX) return
    if (typeof v === 'string') {
      // 中文没有空格：按标点和长度切 2–6 字的片段，够用就好，不做分词。
      for (const seg of v.split(/[，。！？、；：""''《》（）()[\]{}<>·…—～~!?,.;:'"`\s/*|\\-]+/)) {
        if (seg.length >= 2 && seg.length <= 6) push(seg)
        else if (seg.length > 6) for (let i = 0; i + 2 <= seg.length && i < 8; i += 2) push(seg.slice(i, i + 2))
      }
      return
    }
    if (depth > 2) return
    if (Array.isArray(v)) {
      for (const x of v) walk(x, depth + 1)
      return
    }
    if (typeof v === 'object') {
      for (const k of ['text', 'task', 'title', 'label', 'app', 'what', 'goal', 'name', 'summary']) {
        if (v[k] != null) walk(v[k], depth + 1)
      }
    }
  }
  walk(sources, 0)
  return out
}

/** 句子里命中了几个具体名词（整词命中，≥1 就算有信息增量）。 */
export function hitsOf(text, nouns) {
  const s = norm(text)
  if (!s) return 0
  let hit = 0
  for (const w of Array.isArray(nouns) ? nouns : []) if (w && s.includes(w)) hit += 1
  return hit
}

/**
 * 句子和名词池"沾不沾边"：共用几个二元组。
 * 判 no-content 用这个而不是整词命中——"那份报告"和池里的"写报告"共用「报告」，
 * 那是正常说话，不是套话；整词命中会把它们全误杀。
 */
export function poolHitOf(text, nouns) {
  const x = bigrams(norm(text))
  if (!x.size) return 0
  const pool = new Set()
  for (const w of Array.isArray(nouns) ? nouns : []) for (const g of bigrams(norm(w))) pool.add(g)
  let hit = 0
  for (const g of x) if (pool.has(g)) hit += 1
  return hit
}

/**
 * 这一句能不能发。判据顺序固定：空 → 和最近重复 → 没有具体名词。
 * 名词池是空的时候不判 no-content：手上本来就没有可说的东西，不能拿它当罪状。
 * @returns {{pass:true} | {pass:false, code:'empty'|'dup'|'no-content'}}
 */
export function contentGate(text, { recent = [], nouns = [] } = {}) {
  const s = norm(text)
  if (!s) return { pass: false, code: 'empty' }
  if (s.length >= SHORT_OK && dupOf(s, recent) >= DUP_MAX) return { pass: false, code: 'dup' }
  const pool = Array.isArray(nouns) ? nouns : []
  if (pool.length >= NOUNS_MIN && poolHitOf(s, pool) === 0) return { pass: false, code: 'no-content' }
  return { pass: true }
}

/**
 * 一次重写机会，按判据分开处理——两条判据的可靠度不一样：
 *
 * - `dup`（和自己最近 5 句几乎一样）是**确定的**"没新东西"：重写一次，还重复就这一轮不发。
 * - `no-content`（整句不沾具体名词）只是**嫌疑**：中文短句的二元组重叠很粗，实测会误杀正常发球
 *   （"我猜你在躲下午那份申论"和手上的素材池没有共同二元组，但它显然不是套话）。
 *   而且"重写"的实现是下一球再生成一次——没有下一球时（他不再触发 /plan）球就被吞了。
 *   所以它**第一次就照发**，只在账本里记一个 soft 标记供统计：宁可发得不够好，也不许她哑掉。
 * - `empty`：空句本来就不该发。
 *
 * @param {({pass:true}|{pass:false,code:string})[]} tries 按时间顺序，含这一遍
 * @returns {{action:'send'|'rewrite'|'skip', code?:string, soft?:string}}
 */
export function gateRound(tries) {
  const list = Array.isArray(tries) ? tries : []
  if (!list.length) return { action: 'send' }
  const last = list[list.length - 1]
  if (last.pass) return { action: 'send' }
  if (last.code === 'empty') return { action: 'skip', code: 'empty' }
  if (last.code === 'no-content') return { action: 'send', soft: 'no-content' }
  const before = list.slice(0, -1)
  if (before.some((t) => t.code === 'dup')) return { action: 'skip', code: 'dup' }
  return { action: 'rewrite', code: 'dup' }
}
