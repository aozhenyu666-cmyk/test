// dsh-go-companion · 思维题
//
// 他沉进去的时候，症状是：精准度掉、开始凭感觉说话、用沉默应对一切、看完视频只剩"似是而非"。
// 所以思维题不考知识，考**精准**：一句话说清、给数字、给反例、给前提、下注。
// 每一题由模型按"手边的情况 + 记得的事"现编成一个只属于他的问题；这里只给方法、合格线和答不上来时怎么说他。

import { keywordsOf, overlapOf } from './mount.js'

export const DRILLS = [
  {
    id: 'thesis',
    name: '一句话论点',
    ask: '让他用一句话说出他刚才看/玩的东西到底在主张什么或者好在哪，不许用"就是那种""挺有意思"。',
    pass: '一句完整的主张，有主语有判断，不是感受词',
    scold: '他在用感受代替判断，一句话都说不清，说明刚才那段时间是被动灌进去的。',
  },
  {
    id: 'number',
    name: '给个数字',
    ask: '逼他给一个具体数字：花了多少分钟、值几分、有几成把握、这件事要多久——不许说"挺久""还行"。',
    pass: '有一个具体数字，并能说出这个数怎么来的',
    scold: '连个数都不敢给，就是在模糊里躲着。',
  },
  {
    id: 'counter',
    name: '找反例',
    ask: '拿他最近说过或刚看到的一个结论，让他自己举一个反例。',
    pass: '一个具体的反例（具体的人、事、场景），不是"也不一定"',
    scold: '举不出反例说明他只是接受了结论，没想过它。',
  },
  {
    id: 'premise',
    name: '挖前提',
    ask: '指着一个他理所当然的判断，问它成立需要哪个前提，这个前提靠不靠谱。',
    pass: '说出一个具体前提，并对它表态',
    scold: '前提都说不出，判断就是借来的。',
  },
  {
    id: 'bet',
    name: '下注',
    ask: '让他对一件马上能验证的事下注：给出预测和概率（比如"这局能不能赢""这个视频后面会不会反转""今晚几点能睡"），之后你会回来对账。',
    pass: '一个明确的预测 + 一个概率',
    scold: '不敢下注就是不想被验证，这正是他一直以来的毛病。',
  },
  {
    id: 'feynman',
    name: '讲给小孩听',
    ask: '让他把刚才看的/在做的东西讲给一个十岁小孩听，三句以内。',
    pass: '三句内讲清楚，没有术语堆砌',
    scold: '讲不简单就是没懂，只是看过。',
  },
  {
    id: 'invert',
    name: '反过来想',
    ask: '问他：如果想让今天彻底废掉，最有效的三件事是什么？然后指出他现在在做其中哪一件。',
    pass: '列出具体的事，并承认自己现在在做的是哪一件',
    scold: '他知道答案，只是不想说出口。',
  },
  {
    id: 'cost',
    name: '机会成本',
    ask: '把他在这里已经花掉的时间换算成一件他真正在乎的事，问他换不换、为什么。',
    pass: '说出具体在乎的那件事，并给出取舍理由',
    scold: '不算账就永远觉得"再刷一会儿也没什么"。',
  },
  {
    id: 'second',
    name: '二阶后果',
    ask: '问他现在这个选择，一小时后、明天早上分别会导致什么，要具体到一件事。',
    pass: '两个时间点各一个具体后果',
    scold: '只看眼前这一分钟，是水往低处流，不是人在做决定。',
  },
]

const BY_ID = Object.fromEntries(DRILLS.map((d) => [d.id, d]))

export function drillOf(id) {
  return BY_ID[String(id || '')] || null
}

/** 最近用过的几种（从账本 generated 里的 drill 字段看），新→旧。 */
export function recentDrills(entries, n = 3) {
  const out = []
  for (let i = (entries?.length || 0) - 1; i >= 0 && out.length < n; i -= 1) {
    const e = entries[i]
    if (e?.state === 'generated' && e.drill) out.push(String(e.drill))
  }
  return out
}

/**
 * 挑一种思维题。`score(id)` 给每种一个抽样分（来自 brain：他越答不上来的分越高——
 * 弱项要多练），最近三次用过的不挑。全被挡了就放开。
 */
export function pickDrill(entries, score) {
  const recent = new Set(recentDrills(entries, 3))
  let pool = DRILLS.filter((d) => !recent.has(d.id))
  if (!pool.length) pool = DRILLS.slice()
  let best = pool[0]
  let bestScore = -Infinity
  for (const d of pool) {
    const s = typeof score === 'function' ? Number(score(d.id)) : Math.random()
    if (s > bestScore) {
      best = d
      bestScore = s
    }
  }
  return best
}

/** 拼进 prompt：这一球是一道思维题。 */
export function drillLines(d) {
  if (!d) return []
  return [
    '这一球是一道思维题（' + d.name + '）：' + d.ask,
    '题目要现编，只属于他：用上手边的情况和你记得的事，别出通用题。一次只问一个问题，问得窄、能马上答。',
    '合格的回答必须是：' + d.pass + '。你心里要有这条线，他答不到就别放过。',
  ]
}

/**
 * 他上一题答成什么样。答糊弄了 / 没答，这一球就要好好说说他：点出差在哪，然后逼他重答一次、问得更窄。
 */
export function scoldLines(d, { answer, verdict, silent } = {}) {
  if (!d) return []
  if (silent) {
    return [
      '你上一题（' + d.name + '）他没回。沉默不是答案。' + d.scold +
        '这一球说清楚你注意到了他在用沉默躲，然后把同一道题换个更窄的问法再丢过去。',
    ]
  }
  if (!['brief', 'evasive', 'stock'].includes(String(verdict || ''))) return []
  return [
    '你上一题（' + d.name + '）他答的是："' + String(answer || '').slice(0, 120) + '"。这没过线（合格线：' + d.pass + '）。',
    '好好说说他：' + d.scold + '具体指出这句哪里不精准，然后逼他重答，问得比上次更窄。不要客气，也不要人身攻击。',
  ]
}

// ─────────────────────────────────────────────────────────────────────────────
// C2：四个基本操作 —— 精简 / 预判 / 提问 / 回想
//
// 和上面那九种思维题不是一回事：思维题问的是「你想清楚了吗」，这四个练的是「你怎么想的」这个动作本身。
// 关键区别是**合格线全在代码里**，不问模型。所以这一节的每个 judge 都是纯函数，
// 输入是「他答的那句话 + 卡上的素材」，输出是「过没过 + 差在哪条线」。
//
// 为什么必须代码判：判断题由模型判，模型就会开始安慰人。这条线一软，练了半年也不知道有没有长进。
// ─────────────────────────────────────────────────────────────────────────────

/** 固定顺序：回想 → 精简 → 预判 → 提问。出题不能跳到后面的步骤。 */
export const OP_ORDER = Object.freeze(['recall', 'brief', 'predict', 'ask'])

/** 精简：压成一句的上限（规格 30 字）。 */
export const BRIEF_MAX = 30

/** 精简：与原文的二字组重合率区间。低于下限是跑题，高于上限是没压（照抄了一段）。 */
export const BRIEF_SHARE_MIN = 0.2
export const BRIEF_SHARE_MAX = 0.9

/** 回想：卡上结论的关键词被他覆盖到多少才算想起来。 */
export const RECALL_COVER_MIN = 0.3

/** 每天合计 ≤6 题；每个操作至少 1 题（靠顺序自然满足，不需要单独调度）。 */
export const OP_DAILY_MAX = 6

export const OP_META = Object.freeze({
  recall: Object.freeze({
    name: '回想',
    ask: '不看任何东西，说出上次推到哪。',
    pass: '与卡上结论的关键词召回率 ≥ ' + RECALL_COVER_MIN + '，且作答时挂载卡是遮住的',
    source: '已停放的卡、昨天的四部记录',
    // 示范只在第二次没过之后给：第一次就给，他会照着抄。
    demo: '停在「订阅没解绑」那条，下一步查本地版本号有没有存。',
  }),
  brief: Object.freeze({
    name: '精简',
    ask: '把刚才那段话压成一句。',
    pass: '≤ ' + BRIEF_MAX + ' 字；含目标里至少 1 个关键词；与原文二字组重合率 ' + BRIEF_SHARE_MIN + '–' + BRIEF_SHARE_MAX,
    source: '他最近一段超过 100 字的话',
    demo: '增量同步卡在订阅没解绑，先查本地版本号。',
  }),
  predict: Object.freeze({
    name: '预判',
    ask: '动手之前写下「我猜结果是 X，依据是 Y」。',
    pass: '含一个可核对的断言：数字 / 是非 / 先后',
    source: '时间块开始时、连麦开始前',
    demo: '我猜这一版 20 分钟内能跑通，依据是上次只差一个空指针。',
  }),
  ask: Object.freeze({
    name: '提问',
    ask: '只许问一个问题，问那个能改变答案的。',
    pass: '恰好 1 个问句；不是是非题；含卡上一条已知或结论的关键词',
    source: '暗思考攒下的 gaps',
    demo: '订阅没解绑这条，如果先存本地版本号，哪个环节的日志能证明它生效？',
  }),
})

const TXT_MAX = 500
const txt = (v) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, TXT_MAX)
const chars = (v) => [...txt(v)].length

const fail = (code, text) => ({ ok: false, code, text })
const pass = (text, data) => ({ ok: true, code: 'pass', text, ...(data ? { data } : {}) })

/**
 * `a` 的关键词有多大比例能在 `b` 里找到（0–1）。
 *
 * 注意 `overlapOf` 给的是**个数**，不是比例——这个模块里所有「重合率」都由这两个函数算，
 * 免得某处拿个数直接跟阈值比（那样的 bug 不会报错，只会永远通过或永远不通过）。
 */
export function shareOf(a, b) {
  const A = keywordsOf(a)
  if (!A.size) return 0
  return overlapOf(a, b) / A.size
}

/** `b` 的关键词被 `a` 覆盖到多少（0–1）。回想用的是这个方向：卡上的内容他想起多少。 */
export function coverOf(a, b) {
  const B = keywordsOf(b)
  if (!B.size) return 0
  return overlapOf(a, b) / B.size
}

/** 预判：可核对的断言。数字 / 中文数字带单位 / 是非 / 先后——三类命中一类就算。 */
const ASSERT_PATTERNS = [
  /\d/,
  /[一二三四五六七八九十百千万两][分秒时点次个成天周块元岁步条篇行字%]/,
  /会不会|是不是|能不能|对不对|会|不会|能|不能|赢|输|通过|不通过|成|不成/,
  /先|后|之前|之后|以前|以后|然后|接着/,
]

/**
 * 含糊词。这正是这个模块要治的病：**用感觉代替判断**。
 *
 * 「大概会好一点吧，感觉是这样」里有「会」，按字面算「是非断言」，但它什么也没断言。
 * 所以带含糊词又没有具体数字的，一律不算断言；带数字时允许带「大概」（「大概 30 分钟」是可核对的）。
 */
const HEDGE = /感觉|大概|可能|也许|好像|似乎|差不多|说不准|不知道|看看吧/

export function hasAssertion(text) {
  const t = txt(text)
  if (!t) return false
  if (HEDGE.test(t) && !/\d/.test(t)) return false
  return ASSERT_PATTERNS.some((re) => re.test(t))
}

/** 是非题：问的是「会不会 / 是不是 / 能不能 / 吗」这种问完只剩两个答案的问题。 */
const YES_NO = /(会不会|是不是|能不能|对不对|好不好|行不行|要不要|有没有|吗)/

/** 问句个数：问号和全角问号都算。 */
export function questionCount(text) {
  const t = txt(text)
  return (t.match(/[?？]/g) || []).length
}

/**
 * 精简。素材是 `source`（他刚说过的一段话）和 `goal`（卡上的目标）。
 *
 * 两头的线都是必要的：只卡长度，他会把原文一句照抄（那不算压）；
 * 只卡重合率，他会写成「就是那个事」（那不算话）。
 */
export function judgeBrief(answer, { source = '', goal = '' } = {}) {
  const a = txt(answer)
  if (!a) return fail('empty', '这一题是空的。')
  const src = txt(source)
  if (!src) return fail('no-source', '没有原文可比，这一题出不了。')
  const n = chars(a)
  if (n > BRIEF_MAX) return fail('too-long', '压完还有 ' + n + ' 字，合格线是 ≤ ' + BRIEF_MAX + ' 字。')
  const gw = keywordsOf(goal)
  if (gw.size && ![...gw].some((w) => a.toLowerCase().includes(w))) {
    return fail('no-goal-word', '这句和卡上的目标没有一个共同的关键词。')
  }
  const share = shareOf(a, src)
  if (share < BRIEF_SHARE_MIN) return fail('off-topic', '和原文几乎没有重合（' + share.toFixed(2) + '），这是另起一件说。')
  if (share > BRIEF_SHARE_MAX) return fail('no-compress', '和原文重得太厉害（' + share.toFixed(2) + '），这是照抄不是压缩。')
  return pass('压住了：' + n + ' 字，重合 ' + share.toFixed(2) + '。', { chars: n, share })
}

/**
 * 预判。必须含一个可核对的断言——事后要靠它结算，写不出断言就没法对账。
 */
export function judgePredict(answer) {
  const a = txt(answer)
  if (!a) return fail('empty', '这一题是空的。')
  if (!hasAssertion(a)) {
    return fail('no-assertion', '里面没有一个能核对的断言：要给数字、给出是非、或者给先后。')
  }
  return pass('有可核对的断言，事后回来对账。')
}

/**
 * 提问。只许一个问题，问那个能改变答案的：不是是非题，还得咬住卡上已有的内容。
 */
export function judgeAsk(answer, { cardWords = '' } = {}) {
  const a = txt(answer)
  if (!a) return fail('empty', '这一题是空的。')
  const ref = txt(cardWords)
  if (!keywordsOf(ref).size) return fail('no-card', '卡上还没有已知或结论，这一问无从咬起。')
  const q = questionCount(a)
  if (q !== 1) return fail('not-one-question', '这一题要求恰好 1 个问句，你写了 ' + q + ' 个。')
  if (YES_NO.test(a)) return fail('yes-no', '这是是非题，问完只剩两个答案——换一个能问出东西的。')
  const hit = [...keywordsOf(ref)].filter((w) => a.toLowerCase().includes(w))
  if (!hit.length) return fail('no-card-word', '这一问和卡上的已知 / 结论没有一个共同的关键词。')
  return pass('这一问咬住了卡上的内容。', { hit: hit.length })
}

/**
 * 回想。素材是卡上的结论（`cardWords`，一般就是 `steps` 连起来的文本）。
 *
 * `blind` 是手机侧上报的「作答时挂载卡遮住了没有」。不遮就不算回想——看着抄谁都答得上。
 */
export function judgeRecall(answer, { cardWords = '', blind } = {}) {
  const a = txt(answer)
  if (!a) return fail('empty', '这一题是空的。')
  if (blind !== true) return fail('not-blind', '作答时挂载卡没遮住：这一题不算。')
  const ref = txt(cardWords)
  if (!keywordsOf(ref).size) return fail('no-card', '卡上还没有结论，没有东西可回想。')
  const cover = coverOf(a, ref)
  if (cover < RECALL_COVER_MIN) {
    return fail('too-little', '卡上的结论只想起 ' + cover.toFixed(2) + '（合格线 ≥ ' + RECALL_COVER_MIN + '）。')
  }
  return pass('想起来了：卡上结论覆盖 ' + cover.toFixed(2) + '。', { cover })
}

/** 四个操作共用一个入口，路由只认这一个。 */
export function judgeOp(op, answer, ctx = {}) {
  switch (String(op || '')) {
    case 'recall': return judgeRecall(answer, ctx)
    case 'brief': return judgeBrief(answer, ctx)
    case 'predict': return judgePredict(answer, ctx)
    case 'ask': return judgeAsk(answer, ctx)
    default: return fail('bad-op', '只认 recall / brief / predict / ask 四个操作。')
  }
}

/** 今天这个操作过了没有（`since` 是当天零点，由调用方按北京时间算好传进来）。 */
function opsPassedToday(entries, since) {
  const done = new Set()
  for (const e of entries || []) {
    if (!e || e.state !== 'drill-op' || !e.ok) continue
    if (Number(e.at) < Number(since) || Number.isNaN(Number(e.at))) continue
    done.add(String(e.op))
  }
  return done
}

/**
 * 下一个该出哪个操作，没有就是 `null`。
 *
 * 链路是固定顺序，**上一步今天没过就不出下一步**：这道题的存在意义是往前推，不是挑软柿子。
 */
export function nextOp(entries, since) {
  const done = opsPassedToday(entries, since)
  for (const op of OP_ORDER) if (!done.has(op)) return op
  return null
}

/** 今天的额度还剩几题（合计 ≤ `OP_DAILY_MAX`）。 */
export function opQuota(entries, since) {
  let used = 0
  for (const e of entries || []) {
    if (!e || e.state !== 'drill-op') continue
    if (Number(e.at) < Number(since) || Number.isNaN(Number(e.at))) continue
    used += 1
  }
  return { used, max: OP_DAILY_MAX, left: Math.max(0, OP_DAILY_MAX - used) }
}

/**
 * 每个操作在窗口内的通过率（`since` 传 14 天前那天零点）。面板和周报都读这一张表。
 * 没出过题的操作 `asked` 是 0、`rate` 是 0——不是 1，没练过 ≠ 满分。
 */
export function opStats(entries, since) {
  const out = {}
  for (const op of OP_ORDER) out[op] = { asked: 0, passed: 0, rate: 0 }
  for (const e of entries || []) {
    if (!e || e.state !== 'drill-op') continue
    if (Number(e.at) < Number(since) || Number.isNaN(Number(e.at))) continue
    const row = out[String(e.op)]
    if (!row) continue
    row.asked += 1
    if (e.ok) row.passed += 1
  }
  for (const op of OP_ORDER) {
    const row = out[op]
    row.rate = row.asked ? row.passed / row.asked : 0
  }
  return out
}

/** 今天这个操作已经没过几次（不含这一次）。第二次没过才给示范，所以要先数。 */
export function failsToday(entries, since, op) {
  let n = 0
  for (const e of entries || []) {
    if (!e || e.state !== 'drill-op' || e.ok) continue
    if (String(e.op) !== String(op)) continue
    if (Number(e.at) < Number(since) || Number.isNaN(Number(e.at))) continue
    n += 1
  }
  return n
}

/**
 * 示范。规格：第一次没过只说他差在哪条线，**第二次没过才给一个示范**。
 *
 * 第一次就给等于替他答，他会照着抄；给太晚（第三次起）他已经不知道怎么改了。
 */
export function demoOf(op) {
  const meta = OP_META[String(op || '')]
  return meta ? '示范：' + meta.demo : ''
}

/** 拼进 prompt：这一题是哪个操作、合格线是什么。 */
export function opLines(op) {  const meta = OP_META[String(op || '')]
  if (!meta) return []
  return [
    '这一球是「' + meta.name + '」：' + meta.ask,
    '合格线（代码判，你说不算）：' + meta.pass + '。他只答一次，不要提示答案。',
  ]
}

