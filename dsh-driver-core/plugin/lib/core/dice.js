// 六骰子降维法：脑子空的时候，不问开放题，只给一个秒级原子动作。
// 每个骰子产出一张"念两个词"或"A/B 二选一"的卡。没有模型时，这里就是全部的出题能力。

export const DICE = Object.freeze(['精简', '预判', '回想', '联想', '提问', '体系'])

const BUILD = {
  精简: (a) => ({
    kind: 'read_two_words', expects: 'words',
    text: `别想整段。看着屏幕，把${a ? `【${a}】` : '这一段'}压成两个词，念出来或打出来。`,
  }),
  预判: (a) => ({
    kind: 'ab', expects: 'choice',
    text: `先别推，直接猜：${a ? `【${a}】` : '这一步'}的方向更像哪个？`,
    options: { A: '肯定 / 支持', B: '否定 / 排除' },
  }),
  回想: (a) => ({
    kind: 'ab', expects: 'choice',
    text: `回想一下刚才${a ? `【${a}】` : '那一步'}：你是看到了依据，还是凭感觉？`,
    options: { A: '看到了依据', B: '凭感觉' },
  }),
  联想: (a) => ({
    kind: 'ab', expects: 'choice',
    text: `${a ? `【${a}】` : '这个'}让你想到哪类？`,
    options: { A: '以前做过的同类', B: '完全没见过' },
  }),
  提问: (a) => ({
    kind: 'ab', expects: 'choice',
    text: `你现在卡在哪${a ? `（${a}）` : ''}？`,
    options: { A: '没看懂它在说什么', B: '看懂了但不会选/不会下结论' },
  }),
  体系: (a) => ({
    kind: 'ab', expects: 'choice',
    text: `${a ? `【${a}】` : '这一步'}在整件事里属于哪一段？`,
    options: { A: '还在找条件', B: '已经该下结论了' },
  }),
}

/**
 * 选骰子：不连着用同一个；有历史时优先用对你推进率最高的；平手按固定顺序轮。
 * @param {string[]} usedThisSession 本次已用过的骰子（按时间）
 * @param {Record<string,{tries:number, advanced:number}>} history 跨会话统计
 */
export function pickDie(usedThisSession = [], history = {}) {
  const last = usedThisSession.at(-1)
  const rate = (d) => {
    const h = history[d]
    return h && h.tries >= 3 ? h.advanced / h.tries : 0.5 // 样本太少当中性
  }
  const count = (d) => usedThisSession.filter((x) => x === d).length
  const candidates = DICE.filter((d) => d !== last)
  return candidates.sort((a, b) => rate(b) - rate(a) || count(a) - count(b) || DICE.indexOf(a) - DICE.indexOf(b))[0]
}

export function diceCard(die, anchor) {
  const build = BUILD[die] ?? BUILD['提问']
  return { ...build(anchor), intent: `dice:${die}` }
}
