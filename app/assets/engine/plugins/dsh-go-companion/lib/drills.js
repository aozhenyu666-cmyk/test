// dsh-go-companion · 思维题
//
// 他沉进去的时候，症状是：精准度掉、开始凭感觉说话、用沉默应对一切、看完视频只剩"似是而非"。
// 所以思维题不考知识，考**精准**：一句话说清、给数字、给反例、给前提、下注。
// 每一题由模型按"手边的情况 + 记得的事"现编成一个只属于他的问题；这里只给方法、合格线和答不上来时怎么说他。

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
