/**
 * 超级大肥鱼高频主动提示 × 错题/FSRS/笔记库四源抽背 × 反说教受控 Gemini Live 剧本。
 *
 * 原则：
 * 1. 不问“进度”“该做事了吗”“别玩了”，只抛具体真题、降维单开舱选择、或可直接嘴说的口头考题。
 * 2. 强制去重（最近 8 条不重复），四源加权融合：错题本 > FSRS 到期卡 > 长期目标/Obsidian 片段 > 内置考公无纸笔高频口头题池。
 * 3. Gemini 剧本顶部写死【禁聊体系死命令】，切入第一秒直接开考本次抽中的真题，结束必须输出【复盘】。
 */

export const SPARK_MODES = ['tactical_rescue', 'knowledge_hook', 'commute_audio', 'contrast_push']

export const SUPER_SPARK_DEFAULTS = {
  recentMax: 8,
  muteDurationMs: 15 * 60_000,
  autoCooldownMs: 8 * 60_000,
}

/**
 * 内置考公高频无纸笔口头抽背兜底池（判断推理 / 言语 / 申论 / 资料估算 / 常识）。
 */
export const FALLBACK_EXAM_DRILLS = [
  {
    id: 'exam_logic_strengthen_01',
    topic: '判断推理·加强论证',
    prompt: '题干说“某市推广智慧路灯后夜间交通事故下降，因此智慧路灯有效降低了事故率”。若要最有力地加强，该补“同期其他未装路灯路段事故率上升”，还是“智慧路灯照明亮度提高”？为什么？',
    answerKey: '选“同期未装路灯路段事故率上升/未下降”（对比实验排除他因），单独谈亮度属于解释原因而非对比排他。',
  },
  {
    id: 'exam_logic_weaken_02',
    topic: '判断推理·削弱论证',
    prompt: '题干给出“常喝黑咖啡的人体检脂肪肝比例更低，说明黑咖啡能预防脂肪肝”。用无纸笔口头找一个最快“因果倒置”或“另有他因”的削弱选项。',
    answerKey: '另有他因：常喝黑咖啡人群普遍更关注热量控制或作息运动；因果倒置：已有轻微肝功异常者被医嘱减少饮用咖啡。',
  },
  {
    id: 'exam_logic_necessary_03',
    topic: '判断推理·翻译推理',
    prompt: '口头秒答：“只有持续复盘错题，才能稳过行测 70 分”。哪句话与它等价？A.只要复盘错题就能过 70 分；B.过 70 分的人一定持续复盘了错题；C.不复盘也可能靠运气过。',
    answerKey: '选 B（过 70 分 -> 持续复盘；“只有P才Q”等价“Q->P”）。A 把必要条件偷换成了充分条件。',
  },
  {
    id: 'exam_logic_analogy_04',
    topic: '判断推理·类比推理',
    prompt: '“青蒿素：疟疾：屠呦呦” 与下列哪组关系更贴近？A. 杂交水稻：饥荒：袁隆平；B. 胰岛素：糖尿病：班廷。快速拆二级辨析。',
    answerKey: '选 B（药学/医学具体制剂对应特定疾病及主要发现/提取者；A 为农作物对应宏观问题，词性层级稍弱）。',
  },
  {
    id: 'exam_verbal_idiom_05',
    topic: '言语理解·成语辨析',
    prompt: '10 秒口头区分“南辕北辙”和“背道而驰”：如果题干强调“行动方向与既定目标完全相反，且条件越好偏离越远”，优先用哪个词？为什么？',
    answerKey: '“南辕北辙”强调行动与目的相反、主观条件越好反而离目标越远；“背道而驰”侧重完全背离某条轨道/趋势或他人做法。',
  },
  {
    id: 'exam_verbal_main_06',
    topic: '言语理解·主旨概括',
    prompt: '文段结构是“很多人以为A有效——但实证显示A只是表象——核心变量其实是B——然而当前B资源分配不均”。无纸笔判断作者最想呼吁的对策指向谁？',
    answerKey: '围绕核心变量 B 的资源配置公平与制度优化（转折后的因果+尾句“然而”引出的真问题所在）。',
  },
  {
    id: 'exam_verbal_blank_07',
    topic: '言语理解·逻辑填空',
    prompt: '语境：“数字治理不能只靠技术堆砌，否则容易____于形式，反倒增加基层负担。” 选“流”还是“陷”还是“沦”？说出搭配语感。',
    answerKey: '常搭“流于形式”（表面化、走过场），“沦”多接负面身份/境地，“陷”多接“陷入困境/僵局”。',
  },
  {
    id: 'exam_data_est_08',
    topic: '资料分析·口算速判',
    prompt: '无纸笔口算：今年出口 5240 亿元，同比增长 11.2%，问去年同期基期大概在 4700 左右还是 4600 左右？用截位心算 10 秒报思路。',
    answerKey: '5240 / 1.112 ≈ 5240 × (1 - 0.10) ≈ 4716（偏大一点，因为 1/1.112 ≈ 0.899），落在 4712 附近。',
  },
  {
    id: 'exam_data_ratio_09',
    topic: '资料分析·两期比重',
    prompt: '口头秒判：2025 年高技术产业投资增长 14.6%，全社会固定资产投资增长 5.2%。高技术占比同比上升还是下降？超过还是不足 10 个百分点？',
    answerKey: '部分增速 a(14.6%) > 整体增速 b(5.2%) -> 占比上升；差值 < |a - b| = 9.4 个百分点，绝不超过 10 个百分点。',
  },
  {
    id: 'exam_essay_gov_10',
    topic: '申论·归纳概括高频动词',
    prompt: '材料里基层社区反映“系统要填 6 个表、权责不清、老人都找窗口排长队”。口头凝练成 3 个考场得分短句。',
    answerKey: '①数字化留痕过重，多头重复填报；②部门权责边界不清，协同推诿；③适老化服务供给不足，线下办事效率低。',
  },
  {
    id: 'exam_essay_counter_11',
    topic: '申论·对策针对性',
    prompt: '面对“网红景区配套厕所不够、黄牛倒票、宰客投诉多”，给 3 条有主体有抓手的直接对策（别喊“加强宣传/提高素质”）。',
    answerKey: '①文旅+城管按峰谷客流增设移动公厕与潮汐指引；②实名制分时预约+动态核销打击黄牛倒票；③市场监管设立先行赔付机制与明码标价黑名单。',
  },
  {
    id: 'exam_logic_rule_12',
    topic: '判断推理·定义判断',
    prompt: '“回避型依恋消费”指因担心推销压力而宁愿多花钱走无人自助渠道。下列属不属于：老张为了省下导购寒暄，在自动贩卖机多花 3 元买水？',
    answerKey: '属于（满足核心要件：因回避人际推销/沟通压力而支付额外溢价选择自助渠道）。',
  },
  {
    id: 'exam_common_law_13',
    topic: '常识判断·行政法口头抢答',
    prompt: '公务员考场高频：某市市场监管局委托某行业协会实施行政处罚，被处罚人不服申请行政复议/诉讼，谁当被申请人或被告？',
    answerKey: '委托行政机关（某市市场监管局）作为被告/被申请人；受委托组织不以自己名义担责。',
  },
  {
    id: 'exam_verbal_order_14',
    topic: '言语理解·语句排序秒杀',
    prompt: '无纸笔找首句规律：在“①近年来……”“②随着数字经济的发展……”“③然而他并未止步于此……”“④某技术成为关键引擎”中，哪两句绝对不能做首句？',
    answerKey: '③含“然而”转折及指代词“他/此”，绝不做首句；④通常需前文铺垫或①②大背景句领起后再接具体技术定义。',
  },
]

function cleanIdList(raw, limit = SUPER_SPARK_DEFAULTS.recentMax) {
  return [...new Set((Array.isArray(raw) ? raw : []).map((x) => String(x || '').trim()).filter(Boolean))].slice(-limit)
}

function cleanSnippet(raw, maxLen = 140) {
  return String(raw || '').replace(/\s+/g, ' ').trim().slice(0, maxLen)
}

function normalizeQuestionMap(questionBank) {
  const map = new Map()
  const list = Array.isArray(questionBank)
    ? questionBank
    : (questionBank && typeof questionBank === 'object' ? Object.values(questionBank) : [])
  for (const q of list) {
    if (!q || typeof q !== 'object') continue
    const id = String(q.id || q.qid || '').trim()
    if (id) map.set(id, q)
  }
  return map
}

function collectAnchorCandidates({
  bankState = null,
  questionBank = [],
  reviewCards = [],
  horizons = null,
  vault = null,
  vaultSnippets = [],
  now = Date.now(),
} = {}) {
  const candidates = []
  const seenIds = new Set()
  const qMap = normalizeQuestionMap(questionBank)

  const pushCandidate = (item) => {
    if (!item || !item.id || seenIds.has(item.id)) return
    seenIds.add(item.id)
    candidates.push(item)
  }

  // 1. 错题本源（权重最高 5）
  const wrongIds = [
    ...(Array.isArray(bankState?.wrongBookIds) ? bankState.wrongBookIds : []),
    ...(Array.isArray(bankState?.wrongIds) ? bankState.wrongIds : []),
  ]
  const wrongEntries = Array.isArray(bankState?.wrongQuestions) ? bankState.wrongQuestions : []
  for (const wq of wrongEntries) {
    if (!wq || typeof wq !== 'object') continue
    const qid = String(wq.id || wq.qid || '').trim()
    const stem = cleanSnippet(wq.stem || wq.question || wq.prompt || wq.title)
    if (!qid || !stem) continue
    const topic = cleanSnippet(wq.topic || wq.category || wq.subject || '错题复考', 36)
    const answerKey = cleanSnippet(wq.answerText || wq.explanation || wq.answer || '说明解题切入点与排除干扰项原因', 160)
    pushCandidate({
      id: `wb_${qid}`,
      anchorSource: 'wrong_book',
      sourceLabel: '错题本',
      topic,
      prompt: `错题回炉（${topic}）：${stem}`,
      answerKey,
      weight: 5,
    })
  }
  for (const rawId of wrongIds) {
    const qid = String(rawId || '').trim()
    if (!qid) continue
    const qObj = qMap.get(qid)
    const stem = cleanSnippet(qObj?.stem || qObj?.question || qObj?.prompt || qObj?.title)
    const topic = cleanSnippet(qObj?.topic || qObj?.category || qObj?.subject || '考公错题', 36)
    const answerKey = cleanSnippet(
      qObj?.explanation || qObj?.answerText || (Array.isArray(qObj?.options) && Number.isInteger(qObj?.answer) ? qObj.options[qObj.answer] : qObj?.answer) || '口述为什么错、正确选项强在哪',
      160,
    )
    pushCandidate({
      id: `wb_${qid}`,
      anchorSource: 'wrong_book',
      sourceLabel: '错题本',
      topic,
      prompt: stem ? `错题突击（${topic}）：${stem}` : `错题编号 ${qid}（${topic}）：回忆这道题的坑点和正确判定条件。`,
      answerKey,
      weight: 5,
    })
  }

  // 2. 到期 FSRS review_cards 源（权重 4）
  const cardsList = Array.isArray(reviewCards) ? reviewCards : (Array.isArray(reviewCards?.items) ? reviewCards.items : [])
  for (const card of cardsList) {
    if (!card || typeof card !== 'object') continue
    const dueAt = Number(card.fsrs?.due ?? card.due ?? 0)
    if (dueAt > now + 60_000 && cardsList.length > 3) continue
    const cid = String(card.id || '').trim()
    const front = cleanSnippet(card.front || card.prompt || card.question || card.q || card.title)
    if (!cid || !front) continue
    const topic = cleanSnippet(card.topic || card.tag || (Array.isArray(card.tags) ? card.tags[0] : '') || 'FSRS 到期抽背', 36)
    const back = cleanSnippet(card.back || card.answer || card.a || '说出核心定义、适用边界和一个反例', 160)
    pushCandidate({
      id: `rc_${cid}`,
      anchorSource: 'review_card',
      sourceLabel: '到期记忆卡',
      topic,
      prompt: `到期 FSRS 抽背（${topic}）：${front}`,
      answerKey: back,
      weight: 4,
    })
  }

  // 3. Horizons / Vault 碎片源（权重 3）
  const horizonItems = [
    ...(Array.isArray(horizons?.long) ? horizons.long : []),
    ...(Array.isArray(horizons?.mid) ? horizons.mid : []),
    ...(Array.isArray(horizons?.items) ? horizons.items : []),
  ]
  for (const h of horizonItems) {
    if (!h || typeof h !== 'object' || h.done) continue
    const hid = String(h.id || '').trim()
    const title = cleanSnippet(h.title || h.name, 60)
    if (!title) continue
    const nextStep = cleanSnippet(h.nextStep || h.deliverable || h.why || '', 90)
    pushCandidate({
      id: `hz_${hid || title}`,
      anchorSource: 'vault_horizon',
      sourceLabel: '主线目标/笔记',
      topic: title,
      prompt: nextStep
        ? `主线突击「${title}」：用 30 秒口述今天围绕「${nextStep}」最关键的一个判断点或产出。`
        : `主线突击「${title}」：别空想整套计划，直接口述今天 15 分钟内能落地的最小一步是什么？`,
      answerKey: '必须给出具体题型/要点/下一步实物产物，禁止泛泛而谈“我会好好学”。',
      weight: 3,
    })
  }
  const snippets = [
    ...(Array.isArray(vaultSnippets) ? vaultSnippets : []),
    ...(Array.isArray(vault?.recent) ? vault.recent : []),
  ]
  for (const sn of snippets) {
    if (!sn) continue
    const title = cleanSnippet(typeof sn === 'string' ? '最近笔记' : (sn.title || sn.name || sn.topic || '最近笔记'), 40)
    const body = cleanSnippet(typeof sn === 'string' ? sn : (sn.snippet || sn.excerpt || sn.summary || sn.text || ''), 120)
    if (!body && title === '最近笔记') continue
    pushCandidate({
      id: `vt_${cleanSnippet(title + body, 32)}`,
      anchorSource: 'vault_horizon',
      sourceLabel: 'Obsidian 笔记片段',
      topic: title,
      prompt: body
        ? `笔记回抽「${title}」：结合「${body}」，用自己的话复述核心结论和 1 个容易忽略的反例。`
        : `笔记回抽「${title}」：不翻原文，口述这篇笔记最值钱的 2 条实操规律。`,
      answerKey: '重点看能否脱离原文复述要点并举出应用边界。',
      weight: 3,
    })
  }

  // 4. 内置考公无纸笔高频口头题兜底池（权重 2）
  for (const drill of FALLBACK_EXAM_DRILLS) {
    pushCandidate({
      id: drill.id,
      anchorSource: 'exam_pool',
      sourceLabel: '考公高频口头池',
      topic: drill.topic,
      prompt: drill.prompt,
      answerKey: drill.answerKey,
      weight: 2,
    })
  }

  return candidates
}

function pickWeightedCandidate(candidates, recentIds = [], rng = Math.random) {
  const recentSet = new Set(cleanIdList(recentIds, SUPER_SPARK_DEFAULTS.recentMax))
  let pool = candidates.filter((c) => !recentSet.has(c.id))
  if (!pool.length) {
    // 全部命中最近 8 条时，优先挑最旧/非最近一条，保证不卡死
    const lastId = recentIds[recentIds.length - 1]
    pool = candidates.filter((c) => c.id !== lastId)
    if (!pool.length) pool = candidates
  }
  const totalWeight = pool.reduce((s, item) => s + Math.max(1, Number(item.weight) || 1), 0)
  const rRaw = typeof rng === 'function' ? Number(rng()) : Math.random()
  const r = (Number.isFinite(rRaw) ? Math.min(0.999999, Math.max(0, rRaw)) : 0.5) * totalWeight
  let acc = 0
  for (const item of pool) {
    acc += Math.max(1, Number(item.weight) || 1)
    if (r <= acc) return item
  }
  return pool[0]
}

function chooseMode({
  modeHint = '',
  wandering = false,
  noPen = false,
  commute = false,
  handsBusy = false,
  idleMinutes = 0,
  rng = Math.random,
} = {}) {
  if (SPARK_MODES.includes(modeHint)) return modeHint
  if (commute || handsBusy) return 'commute_audio'
  if (noPen || wandering || idleMinutes >= 5) {
    const r = typeof rng === 'function' ? Number(rng()) : Math.random()
    return r < 0.55 ? 'tactical_rescue' : 'contrast_push'
  }
  const r = typeof rng === 'function' ? Number(rng()) : Math.random()
  if (r < 0.35) return 'knowledge_hook'
  if (r < 0.65) return 'tactical_rescue'
  if (r < 0.85) return 'contrast_push'
  return 'commute_audio'
}

/**
 * 生成严格受控的 Gemini Live 剧本。
 * 顶部死命令强制禁聊方法论/情绪/找地方/自律体系，切入第一秒直接从抽中真题开考，结束必须输出【复盘】。
 */
export function buildControlledGeminiBrief(anchor, {
  mode = 'knowledge_hook',
  topic = '',
  noPen = false,
  deliverable = '',
} = {}) {
  const safeTopic = cleanSnippet(topic || anchor?.topic || '行测无纸笔高频抽考', 60)
  const promptText = cleanSnippet(anchor?.prompt || '用 1 分钟说清判断推理中加强论证与解释现象的核心区别。', 220)
  const answerKey = cleanSnippet(anchor?.answerKey || '能准确指出逻辑缺桥与排除他因，并举出一个例子。', 180)
  const sourceTag = cleanSnippet(anchor?.sourceLabel || anchor?.anchorSource || '高频考题池', 32)
  const modeTip = mode === 'commute_audio'
    ? '当前用户可能腾不出手看屏或正在通勤/做饭，全程纯口语一问一答，每句话短促有力。'
    : (noPen
        ? '当前用户手头没有纸笔，严禁让他草稿纸算题或等回桌前再做，只考口头秒判、逻辑拆解或申论要点提炼。'
        : '全程快节奏口头连环追问，不给水时间。')

  return [
    '【禁聊体系死命令】',
    '严禁陪聊学习方法、找地方、情绪安抚、时间管理哲学或自律体系！',
    '切 Live 后第一秒直接从本次抽中的真题开考，不要寒暄，不要问“准备好了吗”或“今天感觉怎么样”。',
    modeTip,
    '',
    '【本次抽考题源】',
    `- 题源：${sourceTag}`,
    `- 主题：${safeTopic}`,
    `- 首问真题：${promptText}`,
    `- 判分底牌：${answerKey}`,
    deliverable ? `- 验收目标：${cleanSnippet(deliverable, 120)}` : '- 验收目标：用户答对或当场纠正错因后，再补追 1 道同类变式。',
    '',
    '【对话推进节拍】',
    '1. 第一句直接抛上面这道「首问真题」，要求用户 15 秒内作答。',
    '2. 答错直接拆穿陷阱给 1 句核心规律，立刻出 1 道同考点无纸笔变式追问。',
    '3. 聊满或考完必须强制收束并输出【复盘】，不许顺着用户话题聊闲篇。',
    '',
    '【结束必须输出】',
    '【复盘】',
    '- 本轮抽考题点：<1 句话>',
    '- 暴露漏洞/卡壳处：<1 句话>',
    '- 下一步 15 分钟物理动作：<必须是不挑地方、不用找笔也能立刻做的动作>',
  ].join('\n')
}

function buildModeCard(mode, anchor, { noPen = false } = {}) {
  const shortAsk = cleanSnippet(anchor.prompt, 120)
  if (mode === 'tactical_rescue') {
    return {
      badge: '降维救援',
      title: noPen ? '没笔也别停 · 直接降维切判断推理' : '别再闲逛挑状态 · 降维立刻开干',
      ask: `不用找桌子也不需要纸笔，直接拿这道题热手：${shortAsk}`,
      voiceLine: `先别挑地方了，没笔也能做判断推理。听题：${cleanSnippet(anchor.prompt, 72)}`,
      optionALabel: '切判断刷题舱 20m',
      optionAMode: 'quiz_only',
      optionAMinutes: 20,
      optionBLabel: '只开 Gemini 口头考 15m',
      optionBMode: 'gemini_only',
      optionBMinutes: 15,
      autoClickLive: false,
    }
  }
  if (mode === 'commute_audio') {
    return {
      badge: '语音抽背',
      title: `腾不出手也能考 · ${anchor.topic}`,
      ask: `语音开考（${anchor.sourceLabel}）：${shortAsk}`,
      voiceLine: `戴耳机口答一题，不用看屏幕。${cleanSnippet(anchor.prompt, 78)} —— 直接点进 Live 报答案。`,
      optionALabel: '直接进 Gemini Live 15m',
      optionAMode: 'gemini_only',
      optionAMinutes: 15,
      optionBLabel: '改切静默刷题舱 20m',
      optionBMode: 'quiz_only',
      optionBMinutes: 20,
      autoClickLive: true,
    }
  }
  if (mode === 'contrast_push') {
    return {
      badge: '反向单开舱',
      title: '二选一单开窄舱 · 砍掉所有杂 App',
      ask: `现在这 15 分钟只留一个口子，不准多线乱跳。要「切纯刷题舱 20m」，还是「反锁进 Gemini 硬聊抽背「${anchor.topic}」15m」？`,
      voiceLine: `别两头摇摆，二选一单开舱：要么切刷题舱二十分钟，要么反锁进 Gemini 硬聊十五分钟。`,
      optionALabel: '切刷题舱 20m',
      optionAMode: 'quiz_only',
      optionAMinutes: 20,
      optionBLabel: '反锁进 Gemini 硬聊 15m',
      optionBMode: 'gemini_only',
      optionBMinutes: 15,
      autoClickLive: false,
    }
  }
  // knowledge_hook
  return {
    badge: '突袭抽背',
    title: `${anchor.sourceLabel}突袭 · ${anchor.topic}`,
    ask: shortAsk,
    voiceLine: `突袭抽背，${anchor.topic}。${cleanSnippet(anchor.prompt, 78)}`,
    optionALabel: '切 Gemini 口头对答案 15m',
    optionAMode: 'gemini_only',
    optionAMinutes: 15,
    optionBLabel: '切刷题舱 20m',
    optionBMode: 'quiz_only',
    optionBMinutes: 20,
    autoClickLive: false,
  }
}

/**
 * 生成一张超级大肥鱼高频主动提示卡片（支持四源融合 + 最近 8 条去重 + 4 种模式）。
 */
export function generateSuperSpark({
  now = Date.now(),
  bankState = null,
  questionBank = [],
  reviewCards = [],
  horizons = null,
  vault = null,
  vaultSnippets = [],
  recentIds = [],
  modeHint = '',
  wandering = false,
  noPen = false,
  commute = false,
  handsBusy = false,
  idleMinutes = 0,
  mutedUntil = 0,
  force = false,
  rng = Math.random,
} = {}) {
  const ts = Math.max(0, Number(now) || Date.now())
  if (!force && Number(mutedUntil) > ts) {
    return {
      id: '',
      triggered: false,
      reason: 'muted',
      at: ts,
      mutedUntil: Number(mutedUntil),
      recentIds: cleanIdList(recentIds),
    }
  }

  const candidates = collectAnchorCandidates({
    bankState,
    questionBank,
    reviewCards,
    horizons,
    vault,
    vaultSnippets,
    now: ts,
  })
  const anchor = pickWeightedCandidate(candidates, recentIds, rng)
  const mode = chooseMode({
    modeHint,
    wandering,
    noPen,
    commute,
    handsBusy,
    idleMinutes,
    rng,
  })
  const card = buildModeCard(mode, anchor, { noPen })
  const geminiTopic = `${anchor.topic}（${anchor.sourceLabel}抽考）`
  const geminiBrief = buildControlledGeminiBrief(anchor, {
    mode,
    topic: geminiTopic,
    noPen,
  })
  const id = `spark-${ts.toString(36)}-${anchor.id.slice(0, 12)}`
  const nextRecentIds = cleanIdList([...cleanIdList(recentIds), anchor.id], SUPER_SPARK_DEFAULTS.recentMax)

  return {
    id,
    triggered: true,
    at: ts,
    mode,
    badge: card.badge,
    title: card.title,
    ask: card.ask,
    voiceLine: card.voiceLine,
    optionALabel: card.optionALabel,
    optionAMode: card.optionAMode,
    optionAMinutes: card.optionAMinutes,
    optionBLabel: card.optionBLabel,
    optionBMode: card.optionBMode,
    optionBMinutes: card.optionBMinutes,
    geminiTopic,
    geminiBrief,
    autoClickLive: card.autoClickLive,
    anchorSource: anchor.anchorSource,
    anchorId: anchor.id,
    topic: anchor.topic,
    nextRecentIds,
  }
}
