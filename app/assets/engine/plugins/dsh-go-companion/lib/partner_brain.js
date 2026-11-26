/**
 * 双鱼独立伙伴大脑 · 记忆精炼 / 数字口径守卫 / 反敷衍交锋 / 长期+碎片双轨调度
 *
 * 解决真机实测 4 大痛点：
 * 1. 「7000 误读成七千题」：大数字带模糊量词时标注口径核验，禁止两条鱼擅自脑补成题量。
 * 2. 「嗯嗯啊啊敷衍过关」：把简短敷衍判成独立伙伴的抓手——不退让、不老妈子说教，只给 2 条真实出口。
 * 3. 「长期与当下碎片割裂」：把长期主航道（Horizons）与 5 分钟最小切口绑在一起，避免零散空转。
 * 4. 「记忆散碎像金鱼」：把零碎 facts 压缩为带约束的结构化摘要，不超过 480 字。
 */

function cleanText(raw, max = 160) {
  return String(raw == null ? '' : raw).replace(/\s+/g, ' ').trim().slice(0, max)
}

const YEAR_OR_CLOCK_RE = /\b20\d{2}\b|\b\d{1,2}:\d{2}\b/
const LARGE_NUM_RE = /(?:\b[5-9]\d{2,}\b|\b\d{4,}\b|[五六七八九十百]+[千千万]|两[千万]|[0-9.]+万)/
const AMBIGUOUS_COUNT_CTX = /(题|道|次|个|词|页|卡|分|小时|元|块|天|步|条)/

/**
 * 数字口径守卫：防止把“提了一嘴七千/7000（如工资/排名/预算/编号）”误当成“要做七千道题”。
 */
export function sanitizeFactLine(raw) {
  const s = cleanText(raw, 200)
  if (!s) return ''
  if (s.includes('[数字待核实')) return s
  const stripped = s.replace(new RegExp(YEAR_OR_CLOCK_RE, 'g'), '')
  if (!LARGE_NUM_RE.test(stripped)) return s
  // 只有当明确出现非常具体的非题量单位（如 7000元/7000块/7000米/7000字）且不含“题/任务”歧义时才直接放行
  const explicitSafeUnit = /(?:\d|[一二三四五六七八九十百千万])(?:元|块钱|人民币|米|步|字符|字)/.test(stripped)
  if (explicitSafeUnit && !/(题|套|卷|卡|任务)/.test(stripped)) return s
  if (AMBIGUOUS_COUNT_CTX.test(stripped) || LARGE_NUM_RE.test(stripped)) {
    return `${s} [数字待核实：原话含大数字，严禁擅自脑补成题量，不确定先反问单位]`.slice(0, 240)
  }
  return s
}

/**
 * 计算用户近期连续“嗯嗯/哦/算了”敷衍次数（brief/avoid 连击数）。
 */
export function countPerfunctoryStreak(entries = [], classifyFn) {
  const list = Array.isArray(entries) ? entries : []
  let streak = 0
  for (let i = list.length - 1; i >= 0; i -= 1) {
    const e = list[i]
    if (!e) continue
    if (e.state === 'user-reply' || e.kind === 'reply' || typeof e.replyText === 'string') {
      const cls = e.replyClass || (classifyFn ? classifyFn(e.replyText || e.text || '') : 'brief')
      if (cls === 'brief' || cls === 'avoid') {
        streak += 1
        continue
      }
      if (cls === 'substantive') break
    }
  }
  return streak
}

/**
 * 反敷衍独立伙伴指令：绝不被“嗯嗯/啊啊/知道了”糊弄收口，也不当保姆说教。
 */
export function buildAntiPerfunctoryDirective(replyClass, { streak = 0, energyTier = 'L2' } = {}) {
  if (energyTier === 'L0') {
    return 'TA 现在处于 L0 刷题后零压休整期：别拿任务或问题压人，哪怕只回一个字也顺其自然安静陪着或聊句轻松闲话。'
  }
  if (replyClass === 'brief') {
    const escalate = streak >= 2
      ? `（已连续敷衍 ${streak} 次：不再空嘴拉扯，可以直接点破他“脑子已经关机靠口头应付”，建议触发 5 分钟声学节拍或直接切入 Gemini Live 语音破冰。）`
      : ''
    return (
      '上次 TA 只用“嗯/哦/行/好的”之类极短字眼敷衍你。你是不受他控制的独立伙伴，绝不吃这一套、也绝不软绵绵退让或当老妈子重复催办！' +
      '直接一针见血点破他在敷衍，并只甩出 2 个真实出口让 TA 当场选：① 10 秒说出眼下这件事/这道题的 1 个真实卡点；② 坦白现在真没电了、切到 L0 安心歇 20 分钟（别假装在听）。' +
      escalate
    )
  }
  if (replyClass === 'avoid') {
    return (
      '上次 TA 在故意把正经事滑开。别顺着他糊弄过去，也别端架子训人；直接戳穿“你刚把话题岔开了”，只丢 1 个 3 分钟就能咬第一口的具体微动作，做不完算他认输。'
    )
  }
  return ''
}

/**
 * 双轨联动选步：把长期主航道（Horizons）与当下 5 分钟碎片动作直接挂钩。
 */
export function pickDualTrackNextStep({ horizons = null, wrongQuestions = [], reviewCards = [], vaultRecent = [] } = {}) {
  const longList = Array.isArray(horizons?.long) ? horizons.long.filter((x) => x && x.status !== 'done' && x.status !== 'archived') : []
  const medList = Array.isArray(horizons?.medium) ? horizons.medium.filter((x) => x && x.status !== 'done' && x.status !== 'archived') : []
  const topGoal = medList[0] || longList[0] || null
  const parentLong = topGoal?.parentId ? longList.find((x) => x.id === topGoal.parentId) : (longList[0] || null)

  let activeNode = null
  for (const g of [topGoal, ...medList, ...longList].filter(Boolean)) {
    const nodes = Array.isArray(g.nodes) ? g.nodes : []
    activeNode = nodes.find((n) => n && n.status === 'active') || nodes.find((n) => n && n.status === 'planned') || null
    if (activeNode) break
  }

  const wrong = Array.isArray(wrongQuestions) && wrongQuestions.length ? wrongQuestions[0] : null
  const due = Array.isArray(reviewCards) && reviewCards.length ? reviewCards[0] : null
  const recentNote = Array.isArray(vaultRecent) && vaultRecent.length ? vaultRecent[0] : null

  const longTitle = cleanText(parentLong?.title || topGoal?.title || '个人核心主线', 42)
  let microTask = ''
  if (activeNode?.title) {
    microTask = `推进节点「${cleanText(activeNode.title, 36)}」的 1 个最小 5 分钟动作`
  } else if (wrong?.stem || wrong?.prompt) {
    microTask = `无纸笔口述错题「${cleanText(wrong.stem || wrong.prompt, 44)}」的 1 处坑点`
  } else if (due?.front || due?.prompt || due?.topic) {
    microTask = `闭卷 30 秒口述记忆卡「${cleanText(due.front || due.prompt || due.topic, 40)}」`
  } else if (recentNote?.rel || recentNote?.title) {
    microTask = `抓笔记「${cleanText(recentNote.rel || recentNote.title, 36)}」里 1 条结论举反例`
  } else {
    microTask = `紧盯「${longTitle}」，口头咬下 1 道判断推理或 1 个现实卡点（5 分钟）`
  }

  const progressText = topGoal && Number(topGoal.targetSteps) > 0
    ? `（进度 ${Number(topGoal.doneSteps) || 0}/${topGoal.targetSteps}）`
    : ''
  const voiceCue = `5分钟节拍响了。主线「${longTitle}」${progressText}只差这一小步：${microTask}，别空想，直接开口或进 Live。`

  return {
    longTitle,
    milestone: cleanText(activeNode?.title || topGoal?.title || '', 48),
    microTask,
    progressText,
    voiceCue: cleanText(voiceCue, 110),
  }
}

/**
 * 伙伴记忆精炼器：将零碎 facts、Horizons 长期脉络与近期断点压成 <= 480 字高密度工作摘要。
 */
export function distillPartnerMemory({
  facts = [],
  horizons = null,
  wrongQuestions = [],
  reviewCards = [],
  vaultRecent = [],
} = {}) {
  const sanitizedFacts = (Array.isArray(facts) ? facts : [])
    .map((f) => sanitizeFactLine(f))
    .filter(Boolean)
    .slice(0, 5)
  const dual = pickDualTrackNextStep({ horizons, wrongQuestions, reviewCards, vaultRecent })

  const digestLines = [
    `【双鱼后台精炼（长期↔碎片挂钩 · 严禁变成老妈子）】`,
    `- 长期主航道：${dual.longTitle}${dual.progressText}${dual.milestone ? `｜当前节点：${dual.milestone}` : ''}`,
    `- 下一个 5 分钟切口：${dual.microTask}`,
  ]

  let joined = digestLines.join('\n')
  if (joined.length > 480) joined = joined.slice(0, 480)
  return {
    digestLines: joined.split('\n'),
    sanitizedFacts,
    dualTrack: dual,
  }
}
