// 回复特征：只看"有没有依据"，不评判对错。
// 输入是你回给驾驶员的一句话（打字、点选或语音转写），输出是能耗特征。

const FILLERS = new Set([
  '嗯', '嗯嗯', '恩', '哦', '噢', '喔', '啊', '好', '好的', '好吧', '行', '行吧', '对', '对的', '对对',
  '是', '是的', '知道了', '知道', '明白', '明白了', '懂了', '懂', '了解', 'ok', 'okay', '可以', '随便',
  '都行', '还行', '差不多', '应该是', '大概', '嗯对', '嗯好',
])

const GIVE_UP = /^(不知道|不懂|不清楚|不会|忘了|没想法|想不出|不想|算了|跳过|pass|不确定)/

// 带"依据"的标记：因果词、引原文、指位置、举例、数字，以及描述具体规律的词。
const EVIDENCE = /(因为|所以|由于|根据|题干|原文|材料|说了|提到|写着|第[一二三四五六七八九十\d]+|关键|依据|条件|前提|比如|例如|而是|排除|对比|说明|意味着|规律|每[个格次条行列]|递增|递减|增加|减少|相同|不同|一样|对称|[“”"「」]|\d)/

const PUNCT = /[\s,，.。!！?？、;；:：~～…—\-()（）【】\[\]]/g

export function normalize(text) {
  return String(text ?? '').replace(PUNCT, '').toLowerCase()
}

/** 回复里点选了 A 还是 B（"A"、"选a"、"b吧" 都算）。 */
export function choiceOf(text) {
  const t = normalize(text)
  const m = t.match(/^(选)?([ab])(吧|的|项)?$/)
  return m ? m[2].toUpperCase() : null
}

/**
 * @param {string} text
 * @param {{anchors?: string[]}} opts anchors 是这张卡期望你说出的关键词（可为空）
 */
export function replyFeatures(text, { anchors = [] } = {}) {
  const raw = String(text ?? '').trim()
  const t = normalize(raw)
  const chars = [...t].length
  const choice = choiceOf(raw)
  const filler = chars === 0 || FILLERS.has(t)
  const giveUp = GIVE_UP.test(t)
  const evidence = !filler && EVIDENCE.test(raw)
  const anchorHit = anchors.some((a) => a && t.includes(normalize(a)))
  let score
  if (choice) score = 0.5
  else if (giveUp || chars === 0) score = 0
  else if (filler) score = 0.05
  else score = Math.min(1, Math.min(chars / 20, 0.5) + (evidence ? 0.3 : 0) + (anchorHit ? 0.3 : 0))
  return { chars, choice, filler, giveUp, evidence, anchorHit, score: Math.round(score * 100) / 100 }
}

/** 截短给提示词用，避免把整段对话塞进去。 */
export function clip(text, max = 60) {
  const s = String(text ?? '').replace(/\s+/g, ' ').trim()
  return [...s].length <= max ? s : [...s].slice(0, max).join('') + '…'
}
