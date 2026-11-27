/**
 * 大肥鱼 V6 主动调度与知识海引擎（ESM 纯模块 · 零重轮询开销）
 *
 * 覆盖双路线核心：
 * 1. 免发版动态时间与严格度框架（scheduler_framework）
 * 2. 统一长期记忆与知识海（剪贴板/分享切片喂鱼、加权随机、18h冷却、FSRS/衰减遗忘清理、储量动效指标）
 * 3. 3刀追问立约工作流 + 【今日契约】剪贴板自动识别入锁
 * 4. 强关联 3x3 九宫格 + 5分钟局严锁后 4 类受限申诉减压阀
 * 5. 《万世铭》长周期碑铭持久化存储
 */

const DAY_MS = 86_400_000
const HOUR_MS = 3_600_000

export const DEFAULT_FRAMEWORK = Object.freeze({
  wakeQuizEnabled: true,
  wakeQuizCooldownMin: 10,
  wakeQuizAntiMashMs: 320,
  liveKeepAliveUnlock: true,
  liveDropGraceSec: 15,
  fiveMinBoutSec: 300,
  strictLockGraceSec: 45,
  dailyAppealLimit: 3,
  dailyPhysioRestLimit: 1,
  physioRestMin: 15,
  poolCooldownHours: 18,
  poolGraduateStreak: 2,
  poolDecayDays: 14,
  stubbornGateEnabled: true,
  stubbornCooldownMin: 20,
  autoInscribeWanshiming: false,
  fragmentedWindows: Object.freeze(['11:30-13:30', '17:30-19:30']),
  deskMoatWindows: Object.freeze(['09:00-11:30', '14:30-17:30', '20:00-22:00']),
})

export const STUBBORN_CATEGORIES = Object.freeze([
  '刷题',
  '错题复盘',
  '看课笔记',
  '申论输出',
  '现实杂事',
  '休息回血',
])

const VAGUE_DECLARATION_RE = /^(?:刷题|学习|复习|看书|写题|做题|干活|休息|摸鱼|玩|ok|done|test|随便|不知道|待定|看看|搞一下)$/i

const STARTER_KNOWLEDGE_CARDS = [
  {
    id: 'k-seed-01',
    claim: '削弱论证题中，指出“存在其他同样导致结果的原因（另有他因）”，比单纯质疑样本量更直接削弱因果。',
    isTrue: true,
    correction: '另有他因直接切断 A->B 唯一因果链，是高频最强的削弱项。',
    source: 'quiz',
    weight: 1.25,
    tags: ['判断推理', '逻辑'],
  },
  {
    id: 'k-seed-02',
    claim: '资料分析计算“基期量 = 现期 / (1 + 增长率)”，若增长率小于 5% 绝不可用“现期 × (1 - r)”估算。',
    isTrue: false,
    correction: '|r| <= 5% 时，1/(1+r) ≈ 1 - r 化除为乘误差极小，是考场核心速算技巧。',
    source: 'quiz',
    weight: 1.2,
    tags: ['资料分析', '速算'],
  },
  {
    id: 'k-seed-03',
    claim: '判断推理“必要条件”P 是 Q 的必要条件（只有 P 才 Q），等价于“Q -> P”和“非 P -> 非 Q”。',
    isTrue: true,
    correction: '“只有 P 才 Q”后推前：Q -> P，逆否等价“非 P -> 非 Q”。',
    source: 'quiz',
    weight: 1.15,
    tags: ['判断推理', '翻译'],
  },
  {
    id: 'k-seed-04',
    claim: '生活或选品判断里，只凭当下故事推注意事项比先查 1000 个同类案例基准率更准。',
    isTrue: false,
    correction: '没有样本基准率支撑的故事正反都能讲通；判断必须先锚定外部 Base Rate。',
    source: 'obsidian',
    weight: 1.3,
    tags: ['认知模型', '基准率'],
  },
  {
    id: 'k-seed-05',
    claim: '言语理解转折词“但是/然而”之后一定是文段主旨，不需要核对后文是否仍在举反例。',
    isTrue: false,
    correction: '转折常只是引子或铺垫，“因此/所以/由此可见”或对策句才常居真主旨。',
    source: 'quiz',
    weight: 1.1,
    tags: ['言语理解'],
  },
  {
    id: 'k-seed-06',
    claim: '类比推理先看“逻辑关系（包容/并列/交叉）”，排不掉时再看二级辨析（必然/或然、词性、褒贬）。',
    isTrue: true,
    correction: '先一级逻辑大类，再二级微观属性，严禁上来硬脑补造句。',
    source: 'quiz',
    weight: 1.1,
    tags: ['判断推理', '类比'],
  },
  {
    id: 'k-seed-07',
    claim: '行为经济学双曲贴现中，延迟 D 越大，当下激励价值 V=A/(1+kD) 越接近于零，所以周末结算不如秒级反馈。',
    isTrue: true,
    correction: '分母 1+kD 会迅速稀释远期奖励，即时 0 延迟反馈才是真启动引擎。',
    source: 'obsidian',
    weight: 1.25,
    tags: ['认知模型', '执行'],
  },
  {
    id: 'k-seed-08',
    claim: '资料分析“增长率比大小”可以直接拿“增长量”代替“增长率”比较，因为分子大分数就大。',
    isTrue: false,
    correction: '错！基期差异大时高增长量可能对应极低增长率，必须比较 增长量/基期量。',
    source: 'quiz',
    weight: 1.25,
    tags: ['资料分析'],
  },
  {
    id: 'k-seed-09',
    claim: '申论大作文抓核心分论点，最稳妥的方式是从给定材料的高频词和官方权威批示里直接提炼，而非自己编文艺词。',
    isTrue: true,
    correction: '申论立足材料，贴近材料问题-原因-对策主轴得分最稳。',
    source: 'quiz',
    weight: 1.15,
    tags: ['申论'],
  },
  {
    id: 'k-seed-10',
    claim: '坐桌前突然急着找 AI 梳理体系，大概率是大脑在用“伪讨论”逃避眼前解题的启动痛苦。',
    isTrue: true,
    correction: '这叫桌前逃避位移：把谈体系移到零碎时段，桌前只咬眼前一道具体动作。',
    source: 'obsidian',
    weight: 1.35,
    tags: ['自我察觉', '桌前护城河'],
  },
  {
    id: 'k-seed-11',
    claim: '图推看到封闭区间极多、且出现明显生活化图标（如电话/叶子），第一反应应数对称轴而非部分数/面。',
    isTrue: false,
    correction: '生活化粗线条图形优先看部分数、笔画与封闭面，切忌死盯对称。',
    source: 'quiz',
    weight: 1.15,
    tags: ['图形推理'],
  },
  {
    id: 'k-seed-12',
    claim: '识别别人真实态度不能看口头表态（Cheap Talk），而要看他付出了什么不可逆时间或机会成本（Costly Signal）。',
    isTrue: true,
    correction: '低成本好话零信噪比，真正作数的是高成本投入与风险共担。',
    source: 'obsidian',
    weight: 1.2,
    tags: ['认知模型', '信号'],
  },
  {
    id: 'k-seed-13',
    claim: '混合增长率口诀“整体增长率必然偏向基期量大的一方，且介于两部分增长率之间”。',
    isTrue: true,
    correction: '混合增长率居中但不正中，更靠近基期权重更大的一端。',
    source: 'quiz',
    weight: 1.2,
    tags: ['资料分析'],
  },
  {
    id: 'k-seed-14',
    claim: '定义判断遇到特别长且绕的法条，应该一口气从头背到尾再看选项，否则一定会看漏。',
    isTrue: false,
    correction: '拆主语、客体、方式、目的关键词，边划要件边排除选项效率翻倍。',
    source: 'quiz',
    weight: 1.1,
    tags: ['定义判断'],
  },
  {
    id: 'k-seed-15',
    claim: '在精力降频态，把目标从“必须搞定一整套”强行拆成“只咬下 1 个具体 5 分钟切片”能绕过拖延死机。',
    isTrue: true,
    correction: '大脑排斥的是瞬时高启动功耗，极小确定切口最易跨过 90 秒惯性阈值。',
    source: 'obsidian',
    weight: 1.25,
    tags: ['自我察觉'],
  },
  {
    id: 'k-seed-16',
    claim: '行测“两期比重比较”，只需比较部分增长率 a 与整体增长率 b：若 a > b，则今年比重比去年上升。',
    isTrue: true,
    correction: 'a > b 比重上升，a < b 比重下降，无需完整算出两个比重再相减。',
    source: 'quiz',
    weight: 1.2,
    tags: ['资料分析', '比重'],
  },
]

const SEED_STELES = [
  {
    id: 'stele-foundation-obsidian',
    title: '立实证底律：先查基准率，不作伪闭环',
    date: '2026-11-19',
    stage: '筑基 · 底律',
    epoch: '丙午 · 筑基',
    category: '悟道',
    body: '一切重大判断先查同类大样本基准率与个人真实行为分布，用反事实剃刀只抓唯一卡脖子变量；缺数据时先跑最小采数实验。',
    echo: '区分廉价口头表态与高成本真实投入，不靠堆砌道理自欺。',
    links: ['stele-v6-scheduler', 'stele-live-keeper'],
    starred: true,
    createdAt: '2026-11-19T20:00:00+08:00',
  },
  {
    id: 'stele-live-keeper',
    title: 'Gemini Live 真通话与硬件感知常驻',
    date: '2026-11-24',
    stage: '破壁 · 外脑',
    epoch: '丙午 · 破壁',
    category: '战役',
    body: '用音频通话态、语音麦、摄像头占用与投影副屏直接锁住 Live 在线状态；掉回文字页或秒退时自动语音破冰拉回。',
    echo: '高强度交互靠硬件真实态护航，而非脆弱文字提示。',
    links: ['stele-foundation-obsidian', 'stele-v6-scheduler', 'stele-final-sprint'],
    starred: true,
    createdAt: '2026-11-24T22:00:00+08:00',
  },
  {
    id: 'stele-v6-scheduler',
    title: '固执调度器与 5m 知识节拍大融合',
    date: '2026-11-27',
    stage: '锻刃 · 体系',
    epoch: '丙午 · 锻刃',
    category: '纪元',
    body: '日常刷题/动作必须申报具体内容方纳入体系；每一棒 5 分钟节拍直接挂接知识海真实切口，产出回流临时数据库而非污染长卷。',
    echo: '日常归日常，长卷只留决定航向的星辰坐标。',
    links: ['stele-foundation-obsidian', 'stele-live-keeper', 'stele-final-sprint'],
    starred: true,
    createdAt: '2026-11-27T16:00:00+08:00',
  },
  {
    id: 'stele-final-sprint',
    title: '行测 80+ 决胜主航道',
    date: '2026-11-27',
    stage: '决胜 · 主线',
    epoch: '丙午 · 决胜',
    category: '战役',
    body: '把所有碎片时间与桌前护城河窗聚拢到资料速算、判断推理逻辑链和申论材料对策主干上。',
    echo: '少而精，咬死每日真题交付。',
    links: ['stele-v6-scheduler', 'stele-live-keeper'],
    starred: true,
    createdAt: '2026-11-27T16:10:00+08:00',
  },
]

function clampInt(v, lo, hi, fallback) {
  const n = Math.round(Number(v))
  return Number.isFinite(n) ? Math.max(lo, Math.min(hi, n)) : fallback
}

function cleanStr(v, max = 180) {
  return String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max)
}

function cleanWindows(list, fallback) {
  const src = Array.isArray(list) ? list : fallback
  const valid = src
    .map((x) => String(x || '').trim())
    .filter((s) => /^\d{1,2}:\d{2}\s*[-~–]\s*\d{1,2}:\d{2}$/.test(s))
    .map((s) => s.replace(/\s*[~–]\s*/, '-').replace(/\s+/g, ''))
  return valid.length ? valid.slice(0, 8) : [...fallback]
}

export function normalizeFramework(raw = {}) {
  const s = raw && typeof raw === 'object' ? raw : {}
  return {
    wakeQuizEnabled: s.wakeQuizEnabled !== undefined ? Boolean(s.wakeQuizEnabled) : DEFAULT_FRAMEWORK.wakeQuizEnabled,
    wakeQuizCooldownMin: clampInt(s.wakeQuizCooldownMin, 1, 180, DEFAULT_FRAMEWORK.wakeQuizCooldownMin),
    wakeQuizAntiMashMs: clampInt(s.wakeQuizAntiMashMs, 150, 2000, DEFAULT_FRAMEWORK.wakeQuizAntiMashMs),
    liveKeepAliveUnlock: s.liveKeepAliveUnlock !== undefined ? Boolean(s.liveKeepAliveUnlock) : DEFAULT_FRAMEWORK.liveKeepAliveUnlock,
    liveDropGraceSec: clampInt(s.liveDropGraceSec, 15, 600, DEFAULT_FRAMEWORK.liveDropGraceSec),
    fiveMinBoutSec: clampInt(s.fiveMinBoutSec, 60, 1800, DEFAULT_FRAMEWORK.fiveMinBoutSec),
    strictLockGraceSec: clampInt(s.strictLockGraceSec, 10, 300, DEFAULT_FRAMEWORK.strictLockGraceSec),
    dailyAppealLimit: clampInt(s.dailyAppealLimit, 0, 12, DEFAULT_FRAMEWORK.dailyAppealLimit),
    dailyPhysioRestLimit: clampInt(s.dailyPhysioRestLimit, 0, 4, DEFAULT_FRAMEWORK.dailyPhysioRestLimit),
    physioRestMin: clampInt(s.physioRestMin, 5, 60, DEFAULT_FRAMEWORK.physioRestMin),
    poolCooldownHours: clampInt(s.poolCooldownHours, 1, 168, DEFAULT_FRAMEWORK.poolCooldownHours),
    poolGraduateStreak: clampInt(s.poolGraduateStreak, 1, 5, DEFAULT_FRAMEWORK.poolGraduateStreak),
    poolDecayDays: clampInt(s.poolDecayDays, 3, 90, DEFAULT_FRAMEWORK.poolDecayDays),
    stubbornGateEnabled: s.stubbornGateEnabled !== undefined ? Boolean(s.stubbornGateEnabled) : DEFAULT_FRAMEWORK.stubbornGateEnabled,
    stubbornCooldownMin: clampInt(s.stubbornCooldownMin, 5, 180, DEFAULT_FRAMEWORK.stubbornCooldownMin),
    autoInscribeWanshiming: s.autoInscribeWanshiming !== undefined ? Boolean(s.autoInscribeWanshiming) : DEFAULT_FRAMEWORK.autoInscribeWanshiming,
    fragmentedWindows: cleanWindows(s.fragmentedWindows, DEFAULT_FRAMEWORK.fragmentedWindows),
    deskMoatWindows: cleanWindows(s.deskMoatWindows, DEFAULT_FRAMEWORK.deskMoatWindows),
  }
}

function parseHmRange(range) {
  const m = String(range || '').match(/^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$/)
  if (!m) return null
  const start = Number(m[1]) * 60 + Number(m[2])
  const end = Number(m[3]) * 60 + Number(m[4])
  return [start, end]
}

function inAnyWindow(minuteOfDay, windows) {
  for (const w of windows || []) {
    const r = parseHmRange(w)
    if (!r) continue
    if (r[1] >= r[0]) {
      if (minuteOfDay >= r[0] && minuteOfDay < r[1]) return w
    } else {
      if (minuteOfDay >= r[0] || minuteOfDay < r[1]) return w
    }
  }
  return null
}

/** 判定当前分钟属于「桌前护城河窗」「零碎清口窗」还是「自由调节窗」。 */
export function evaluateWindowMode(frameworkRaw, minuteOfDay = 0) {
  const fw = normalizeFramework(frameworkRaw)
  const mod = clampInt(minuteOfDay, 0, 1439, 0)
  const deskMatch = inAnyWindow(mod, fw.deskMoatWindows)
  if (deskMatch) {
    return {
      mode: 'desk_moat',
      label: '桌前护城河窗 · 禁谈空泛体系',
      window: deskMatch,
      blockMetaChitChat: true,
      allowDigestPush: false,
    }
  }
  const fragMatch = inAnyWindow(mod, fw.fragmentedWindows)
  if (fragMatch) {
    return {
      mode: 'fragmented_digest',
      label: '零碎清口窗 · 预消化想聊杂念',
      window: fragMatch,
      blockMetaChitChat: false,
      allowDigestPush: true,
    }
  }
  return {
    mode: 'general',
    label: '常规自由调配窗',
    window: '',
    blockMetaChitChat: false,
    allowDigestPush: true,
  }
}

export function seedKnowledgePool(now = Date.now()) {
  return STARTER_KNOWLEDGE_CARDS.map((c, idx) => ({
    id: c.id,
    claim: c.claim,
    isTrue: Boolean(c.isTrue),
    correction: c.correction,
    source: c.source,
    weight: Number(c.weight) || 1.0,
    tags: [...(c.tags || [])],
    seenCount: 0,
    hitStreak: 0,
    lastShownAt: 0,
    createdAt: now - (STARTER_KNOWLEDGE_CARDS.length - idx) * HOUR_MS,
    status: 'active',
  }))
}

export function parseContractBlock(rawText = '') {
  const text = String(rawText || '').trim()
  if (!text) return null
  const hasMarker = /【今日契约】|\[TODAY_CONTRACT\]|契约靶子[:：]/i.test(text)
  if (!hasMarker) return null

  const pick = (re) => {
    const m = text.match(re)
    return m ? cleanStr(m[1], 140) : ''
  }

  const target = pick(/(?:靶子|目标|任务|动作)[:：]\s*([^\n\r]+)/i)
  const durRaw = pick(/(?:时长|时间|用时)[:：]\s*(\d+)\s*(?:分钟|min|m)?/i)
  const verifyRule = pick(/(?:验收|交付|过关标准)[:：]\s*([^\n\r]+)/i)
  const deadline = pick(/(?:截止|最晚)[:：]\s*([^\n\r]+)/i)
  const appsRaw = pick(/(?:放行|白名单)[:：]\s*([^\n\r]+)/i)

  if (!target && !verifyRule) return null
  return {
    target: target || '推进当下 1 个核心切片',
    durationMin: clampInt(durRaw || 25, 5, 180, 25),
    verifyRule: verifyRule || '交出 1 条有信息量的语音复盘或做对 5 题',
    deadline: deadline || '',
    allowApps: appsRaw ? appsRaw.split(/[,，、/\s]+/).filter(Boolean).slice(0, 8) : [],
    raw: text.slice(0, 600),
  }
}

function deriveBinaryCardFromText(sentence, idx, now, source) {
  const s = cleanStr(sentence, 130)
  if (!s || s.length < 10) return null
  const negCue = /错了|误区|不要|禁止|不是|而非|误以为|假动作|陷阱/.test(s)
  return {
    id: `k-${now}-${idx}-${Math.random().toString(36).slice(2, 6)}`,
    claim: s,
    isTrue: !negCue,
    correction: negCue ? '此条含反直觉避坑点，切忌顺惯性踩坑。' : '核心实操结论：遇同类场景直接照做。',
    source: ['gemini', 'quiz', 'obsidian', 'bout'].includes(source) ? source : 'gemini',
    weight: 1.35,
    tags: [source === 'gemini' ? 'Gemini喂入' : '切片入库'],
    seenCount: 0,
    hitStreak: 0,
    lastShownAt: 0,
    createdAt: now,
    status: 'active',
  }
}

const INJECTION_GUARD_RE = /ignore\s+(?:all\s+)?previous\s+instructions|system\s+prompt|<\|im_start\|>|<\|endoftext\|>|you\s+are\s+now\s+in\s+developer\s+mode|bypass\s+safety/i

function isPromptSafeText(text) {
  const s = String(text || '')
  return s.length > 0 && !INJECTION_GUARD_RE.test(s)
}

export function harvestKnowledgeSources(pool = [], sources = {}, now = Date.now()) {
  const existing = Array.isArray(pool) ? pool : []
  const seen = new Set(existing.map((x) => cleanStr(x?.claim, 90).toLowerCase()).filter(Boolean))
  const candidates = []

  const qBank = Array.isArray(sources?.questionBank) ? sources.questionBank : []
  for (const q of qBank) {
    if (!q || typeof q !== 'object') continue
    const tip = cleanStr(q.explanation || q.note || q.answer || '', 80)
    const stem = cleanStr(q.stem || q.prompt || q.q || q.title || '', 72)
    const text = tip && stem ? `${stem} → 关键点：${tip}` : (tip || stem)
    if (text.length >= 12 && isPromptSafeText(text)) {
      candidates.push({ text, source: 'quiz', weight: q.wrongCount > 0 ? 1.5 : 1.25 })
    }
  }

  const cards = Array.isArray(sources?.reviewCards) ? sources.reviewCards : []
  for (const c of cards) {
    if (!c || typeof c !== 'object') continue
    const front = cleanStr(c.front || c.prompt || c.title || '', 64)
    const back = cleanStr(c.back || c.answer || c.summary || '', 64)
    const text = front && back ? `${front}：${back}` : (front || back)
    if (text.length >= 12 && isPromptSafeText(text)) {
      candidates.push({ text, source: 'quiz', weight: 1.4 })
    }
  }

  const vaultSnippets = Array.isArray(sources?.vaultSnippets) ? sources.vaultSnippets : []
  for (const v of vaultSnippets) {
    const raw = typeof v === 'string' ? v : (v?.summary || v?.text || v?.title || '')
    const text = cleanStr(raw, 125)
    if (text.length >= 12 && isPromptSafeText(text)) {
      candidates.push({ text, source: 'obsidian', weight: 1.3 })
    }
  }

  const h = sources?.horizons && typeof sources.horizons === 'object' ? sources.horizons : null
  if (h) {
    for (const g of [...(h.long || []), ...(h.medium || [])]) {
      const title = cleanStr(g?.title || g?.name || '', 48)
      const step = cleanStr(g?.nextAction || g?.microTask || g?.criterion || '', 70)
      if (title && step && isPromptSafeText(`${title}${step}`)) {
        candidates.push({ text: `主线「${title}」当下聚焦：${step}`, source: 'obsidian', weight: 1.35 })
      }
    }
  }

  const memFacts = Array.isArray(sources?.memoryFacts) ? sources.memoryFacts : []
  for (const f of memFacts) {
    const raw = typeof f === 'string' ? f : (f?.text || f?.fact || '')
    const text = cleanStr(raw, 125)
    if (text.length >= 10 && text.length <= 160 && isPromptSafeText(text)) {
      candidates.push({ text, source: 'obsidian', weight: 1.35 })
    }
  }

  const added = []
  for (let i = 0; i < candidates.length && added.length < 6; i += 1) {
    const cand = candidates[i]
    const norm = cleanStr(cand.text, 90).toLowerCase()
    if (!norm || seen.has(norm)) continue
    const card = deriveBinaryCardFromText(cand.text, i, now, cand.source)
    if (!card) continue
    card.weight = cand.weight
    seen.add(norm)
    added.push(card)
  }

  const nextPool = added.length ? [...added, ...existing].slice(0, 400) : existing
  return { addedCount: added.length, added, pool: nextPool }
}

/**
 * 智能吞吐剪贴板或文本：
 * - 若识别到 【今日契约】 区块，返回 `{ kind: 'contract', contract }`
 * - 否则自动切片并去重入知识海，返回 `{ kind: 'knowledge', added, skipped }`
 */
export function ingestClipboardOrText(pool = [], rawText = '', { source = 'gemini', now = Date.now() } = {}) {
  const contract = parseContractBlock(rawText)
  if (contract) {
    return {
      ok: true,
      kind: 'contract',
      contract,
      added: [],
      skipped: 0,
      pool,
    }
  }

  const lines = String(rawText || '')
    .split(/\r?\n|[。！？!?\n]+/)
    .map((line) => line.replace(/^[\s\-*•\d.)、]+/, '').trim())
    .filter((line) => line.length >= 10 && line.length <= 160)

  const existingNorm = new Set((pool || []).map((x) => cleanStr(x.claim, 90).toLowerCase()))
  const added = []
  let skipped = 0

  for (let i = 0; i < lines.length && added.length < 8; i += 1) {
    const key = cleanStr(lines[i], 90).toLowerCase()
    if (existingNorm.has(key)) {
      skipped += 1
      continue
    }
    const card = deriveBinaryCardFromText(lines[i], i, now, source)
    if (card) {
      existingNorm.add(key)
      added.push(card)
    }
  }

  const nextPool = [...added, ...(Array.isArray(pool) ? pool : [])].slice(0, 400)
  return {
    ok: added.length > 0,
    kind: 'knowledge',
    added,
    skipped,
    pool: nextPool,
  }
}

export function sampleKnowledgeItems(pool = [], frameworkRaw = {}, { count = 1, now = Date.now(), markShown = false, seed = null } = {}) {
  const fw = normalizeFramework(frameworkRaw)
  const cooldownMs = fw.poolCooldownHours * HOUR_MS
  const active = (Array.isArray(pool) ? pool : []).filter((c) => c && c.status !== 'archived' && c.status !== 'graduated')
  let eligible = active.filter((c) => !c.lastShownAt || (now - Number(c.lastShownAt)) >= cooldownMs)
  if (!eligible.length) eligible = active

  // Weighted stochastic pick (supports deterministic seed for prompt caching / tests)
  const remaining = [...eligible]
  const picked = []
  const want = Math.max(1, Math.min(20, Number(count) || 1))
  let seedState = typeof seed === 'number' && Number.isFinite(seed) ? ((seed >>> 0) || 1) : null
  const nextUnit = () => {
    if (seedState === null) return Math.random()
    seedState = (Math.imul(seedState, 1664525) + 1013904223) >>> 0
    return seedState / 4294967296
  }

  while (picked.length < want && remaining.length > 0) {
    let totalW = 0
    const weights = remaining.map((c) => {
      const freshBonus = Number(c.seenCount || 0) === 0 ? 1.5 : 1.0
      const w = Math.max(0.2, (Number(c.weight) || 1.0) * freshBonus)
      totalW += w
      return w
    })
    let roll = nextUnit() * totalW
    let hitIdx = 0
    for (let i = 0; i < weights.length; i += 1) {
      roll -= weights[i]
      if (roll <= 0) {
        hitIdx = i
        break
      }
    }
    const chosen = remaining.splice(hitIdx, 1)[0]
    picked.push(chosen)
    if (markShown) {
      chosen.lastShownAt = now
      chosen.seenCount = (Number(chosen.seenCount) || 0) + 1
    }
  }

  return picked
}

export function recordMicroQuizAnswer(pool = [], frameworkRaw = {}, { itemId = '', userTrue = true, elapsedMs = 1500, now = Date.now() } = {}) {
  const fw = normalizeFramework(frameworkRaw)
  const target = (Array.isArray(pool) ? pool : []).find((c) => c && c.id === itemId) || null
  if (!target) {
    return { ok: false, error: '未找到对应知识卡', antiMashPenalty: false, correct: false }
  }
  const correct = Boolean(userTrue) === Boolean(target.isTrue)
  const tooFastMash = Number(elapsedMs) > 0 && Number(elapsedMs) < fw.wakeQuizAntiMashMs && !correct

  target.lastShownAt = now
  target.seenCount = (Number(target.seenCount) || 0) + 1
  if (correct) {
    target.hitStreak = (Number(target.hitStreak) || 0) + 1
    target.weight = Math.max(0.4, Number(target.weight || 1.0) * 0.8)
    if (target.hitStreak >= fw.poolGraduateStreak) {
      target.status = 'graduated'
    }
  } else {
    target.hitStreak = 0
    target.weight = Math.min(2.6, Number(target.weight || 1.0) + 0.45)
  }

  return {
    ok: true,
    itemId: target.id,
    correct,
    expectedTrue: Boolean(target.isTrue),
    correction: target.correction || '',
    graduated: target.status === 'graduated',
    antiMashPenalty: tooFastMash,
    hitStreak: target.hitStreak,
  }
}

export function sweepForgetting(pool = [], frameworkRaw = {}, now = Date.now()) {
  const fw = normalizeFramework(frameworkRaw)
  const decayMs = fw.poolDecayDays * DAY_MS
  let archivedCount = 0
  let decayedCount = 0
  const nextPool = (Array.isArray(pool) ? pool : []).map((card) => {
    if (!card || card.status === 'archived') return card
    const refAt = Math.max(Number(card.lastShownAt) || 0, Number(card.createdAt) || 0)
    const ageMs = Math.max(0, now - refAt)
    if (card.status === 'active' && ageMs >= decayMs && Number(card.seenCount || 0) > 0 && Number(card.hitStreak || 0) === 0) {
      archivedCount += 1
      return { ...card, status: 'archived', archivedReason: '长期沉底遗忘归档' }
    }
    if (card.status === 'active' && ageMs >= decayMs * 0.5) {
      decayedCount += 1
      return { ...card, weight: Math.max(0.5, Number((Number(card.weight || 1.0) * 0.9).toFixed(2))) }
    }
    return card
  })
  return { pool: nextPool, archivedCount, decayedCount }
}

export function computePoolTelemetry(pool = [], now = Date.now()) {
  const list = Array.isArray(pool) ? pool : []
  const bySource = { gemini: 0, quiz: 0, obsidian: 0, bout: 0 }
  let active = 0
  let fresh = 0
  let reviewing = 0
  let graduated = 0
  let archived = 0
  let addedToday = 0
  let shown72h = 0

  for (const c of list) {
    if (!c) continue
    const src = bySource[c.source] !== undefined ? c.source : 'gemini'
    bySource[src] += 1
    if (c.status === 'archived') archived += 1
    else if (c.status === 'graduated') graduated += 1
    else {
      active += 1
      if (!c.seenCount) fresh += 1
      else reviewing += 1
    }
    if (now - (Number(c.createdAt) || 0) <= DAY_MS) addedToday += 1
    if (now - (Number(c.lastShownAt) || 0) <= 3 * DAY_MS) shown72h += 1
  }

  const dedupeRate72h = active > 0
    ? Math.max(60, Math.min(100, Math.round(((active - Math.min(active, Math.max(0, shown72h - 1))) / Math.max(1, active)) * 100)))
    : 100

  return {
    total: list.length,
    active,
    fresh,
    reviewing,
    graduated,
    archived,
    addedToday,
    dedupeRate72h,
    bySource,
    previewSamples: sampleKnowledgeItems(list, DEFAULT_FRAMEWORK, { count: 4, now, markShown: false }).map((x) => ({
      id: x.id,
      claim: x.claim,
      source: x.source,
    })),
  }
}

export function buildInterrogationPrompt({ horizons = null, pool = [], now = Date.now(), energyHint = '未知' } = {}) {
  const activeLong = (horizons?.long || []).find((x) => x && x.status === 'active')?.title || '当前主线突破'
  const activeMed = (horizons?.medium || []).find((x) => x && x.status === 'active')?.title || ''
  const preview = sampleKnowledgeItems(pool, DEFAULT_FRAMEWORK, { count: 2, now, markShown: false })
  const hookLines = preview.map((x, i) => `${i + 1}. [${x.source}] ${x.claim}`).join('\n')

  return [
    '【大肥鱼 · 3刀追问立约插件（严禁漫无目的闲聊）】',
    `背景锚点：主线「${activeLong}」${activeMed ? ` / 战役「${activeMed}」` : ''}，自报电量：${energyHint}。`,
    hookLines ? `知识海顺手弹药：\n${hookLines}` : '',
    '',
    '请立刻用不超过 80 字对我连续追问以下 3 刀（一次只收口不啰嗦）：',
    '1. 接下来这一段你是带纸笔死磕，还是纯手机降维抓判断/口述？',
    '2. 别贪多，这 25 分钟只咬死哪 1 个最小具体动作？（不许写“看书/复习”这种虚词）',
    '3. 超时没交什么实质成果就准大肥鱼立刻锁机？',
    '',
    '等我答完这 3 刀后，你必须严格按下面格式输出，方便我一键复制回大肥鱼自动上锁：',
    '【今日契约】',
    '靶子：<一句话具体任务>',
    '时长：25分钟',
    '验收：<具体可核验交付>',
  ].filter(Boolean).join('\n')
}

const SIX_DICE_MINI = Object.freeze({
  1: { name: '事实提取', ask: '不看原话，用一句说清刚才这条内容到底交待了哪一个硬事实？' },
  2: { name: '预判推演', ask: '顺着当前这一步往下走，接下来最可能发生什么结果或坑？' },
  3: { name: '联想比较', ask: '这跟你做过的哪一类旧题/旧案例最像？关键差别在哪？' },
  4: { name: '关键提问', ask: '整段里哪一个还没弄清的未知条件，一旦变了结论就会翻盘？' },
  5: { name: '依据与结论', ask: '你刚才下的判断依据了哪条硬事实？如果只靠它，能稳推出什么？' },
  6: { name: '替代解释', ask: '别急着认第一个结论：如果最顺嘴那个解释错了，还能怎么解释？' },
})

const FLAW_TEMPLATES = Object.freeze([
  {
    topic: '偷换概念 · 忽略总基数',
    stem: '某市 A 区今年交通事故同比上升 12%，B 区只上升 3%，结论据此判定 A 区今年交通治理比 B 区差得多。',
    steps: [
      'A 区同比增幅 12% 高于 B 区 3%，相对增量确实更显著。',
      '增幅统计口径采用去年的绝对事故数作分母。',
      '因此 A 区今年的实际安全风险与事故绝对总量必定高于 B 区。',
      '若 B 区去年基数极高而 A 区基数极小，增速高不代表总量大。',
    ],
    flawIndex: 2,
    flawLabel: '忽略总基数偷换大小',
    explanation: '第 3 步把“同比增幅高”直接等价为“绝对总量与实际风险高”，漏掉了两区去年基数可能完全不在同一量级。',
  },
  {
    topic: '强加因果 · 相关误当因果',
    stem: '调查显示每天喝黑咖啡的人行测做题正确率高 9%，于是断定喝黑咖啡能直接提升逻辑推理能力。',
    steps: [
      '样本统计确实观察到饮用黑咖啡与高正确率存在正相关。',
      '黑咖啡中的咖啡因可短暂提升部分人群的觉醒度。',
      '所以只要明天考前大量灌黑咖啡，任何人的推理能力都会同步提高 9%。',
      '规律作息与备考基础好的人群本就更常靠咖啡维持早起节律。',
    ],
    flawIndex: 2,
    flawLabel: '相关关系强行外推为因果律',
    explanation: '第 3 步犯了强加因果与忽略混杂变量错误：高正确率可能源自备考人群本身作息与基础差异，且个体剂量存在边际递减。',
  },
  {
    topic: '条件推理 · 肯后误当肯前',
    stem: '规定“只有完成 15 分钟错题复盘，才能解锁当晚游戏权限”。小李今晚解锁了游戏权限（或没完成复盘但开了备用机）。',
    steps: [
      '逻辑等价改写为：解锁权限 → 必须已完成 15 分钟错题复盘。',
      '若小李完成了 15 分钟错题复盘，说明他必然已经解锁游戏权限。',
      '若小李没有完成错题复盘，按规则一定不能解锁游戏权限。',
      '“只有 P 才 Q”中 P 是必要条件，Q 成立必推 P，P 成立未必推 Q。',
    ],
    flawIndex: 1,
    flawLabel: '充分必要条件倒置（肯后/肯必误推）',
    explanation: '第 2 步把“必要条件成立”错当成“充分条件必然成立”：完成了复盘还可能受时段锁或电量限制，未必必然解锁。',
  },
  {
    topic: '样本偏误 · 幸存者偏差',
    stem: '采访 20 位上岸考生都说每天刷题到凌晨 1 点，于是推断熬夜到凌晨 1 点是上岸高胜率策略。',
    steps: [
      '20 位成功上岸者的样本确实普遍存在凌晨睡前刷题习惯。',
      '因此“熬夜到凌晨 1 点”是决定能否上岸的最高收益分界动作。',
      '调查未统计同样熬夜到凌晨 1 点却因疲劳失分落榜的人数。',
      '仅凭成功者共同特征反推因果，遗漏了分母中的未上岸人群。',
    ],
    flawIndex: 1,
    flawLabel: '幸存者偏差（缺失未成功对照样本）',
    explanation: '第 2 步只看成功者交集就下因果结论，没算同样熬夜却效率坍塌的落榜分母。',
  },
  {
    topic: '资料分析 · 部分与整体增速混淆',
    stem: '2025 年全国软件营收增长 11%，其中成都软件营收增长 8%，因此判断成都软件产值今年反而萎缩了。',
    steps: [
      '成都 8% 的增速低于全国平均 11%，全国占比确实微降。',
      '只要当年名义增速大于 0%，绝对产值本身依然是同比扩大的。',
      '增速跑输全国大盘，等价于成都今年软件营收绝对值出现负增长萎缩。',
      '比较两期占比看部分增速 vs 整体增速，比较规模只看自身增速正负。',
    ],
    flawIndex: 2,
    flawLabel: '混淆“占比下降”与“绝对量萎缩”',
    explanation: '第 3 步把“增速低于整体导致占比下降”错当成“绝对量负增长”，成都今年规模仍增长了 8%。',
  },
  {
    topic: '认知护栏 · 低电量硬加锁假动作',
    stem: '昨晚低电量时面对复杂计划瘫痪没动，今天决定把全天可玩时长直接归零、逼自己一口气写完两千字。',
    steps: [
      '高耗起步时大脑会自动寻找最小阻力逃逸或复制粘贴糊弄。',
      '把起步门槛从 10 秒点选拔高到 2000 字无锚点输出，更能彻底治好拖延。',
      '压低第一动作为“0.5 秒挑错/左右盲点”，反而更容易带转思维齿轮。',
      '软件能托举 20%~60% 电量空转者，彻底呆滞期仍须配生理停战与作息底座。',
    ],
    flawIndex: 1,
    flawLabel: '逆反加压假动作（拔高起步瓦数逼出死皮）',
    explanation: '第 2 步违反认知努力折现规律：低工作记忆+低电量下强拔启动峰值，只会加速滑向彻底死皮躺平。',
  },
])

export function buildFlawChallenge(pool = [], now = Date.now()) {
  const active = (Array.isArray(pool) ? pool : []).filter((c) => c && c.status !== 'archived' && c.claim)
  const usePool = active.length > 0 && (Math.abs(Math.floor(now / 1000)) % 2 === 0 || Math.random() < 0.45)

  if (usePool) {
    const picked = sampleKnowledgeItems(active, DEFAULT_FRAMEWORK, { count: 1, now, markShown: false })[0]
    if (picked) {
      const claim = cleanStr(picked.claim, 110)
      const flawIdx = 1 + (Math.abs(Math.floor(now / 137)) % 2) // index 1 or 2
      const baseSteps = [
        `先锁定眼下材料真命题边界：「${cleanStr(claim, 64)}」。`,
        picked.isTrue
          ? '只要照着字面经验顺推，无需核对题目约束和否定词也能稳对。'
          : '顺着第一直觉直接套用“看起来最像常识”的那句绝对表述即可过关。',
        `关键反例拆解：${cleanStr(picked.correction || '需重点核对主体范围、否定限词与对比基数是否偷换。', 72)}`,
        '下一次同屏遇到相似设问，先用 1 秒标出限定词与基数再落手。',
      ]
      if (flawIdx === 2) {
        const tmp = baseSteps[1]
        baseSteps[1] = baseSteps[2]
        baseSteps[2] = tmp
      }
      return {
        id: `flaw-${now}-${Math.random().toString(36).slice(2, 6)}`,
        topic: `知识海实卡挑刺 · ${picked.source || 'quiz'}`,
        stem: `围绕刚沉淀的核心切片「${cleanStr(claim, 72)}」，下列哪一步推理是偷换前提/假动作？`,
        steps: baseSteps,
        flawIndex: flawIdx,
        flawLabel: '知识海实卡反直觉陷阱',
        explanation: picked.correction || '第 ' + (flawIdx + 1) + ' 步跳过边界核验直接顺直觉偷懒，正是常见掉坑点。',
        source: picked.source || 'quiz',
      }
    }
  }

  const tpl = FLAW_TEMPLATES[Math.abs(Math.floor(now / 97) + Math.floor(Math.random() * FLAW_TEMPLATES.length)) % FLAW_TEMPLATES.length]
  return {
    id: `flaw-${now}-${Math.random().toString(36).slice(2, 6)}`,
    topic: tpl.topic,
    stem: tpl.stem,
    steps: [...tpl.steps],
    flawIndex: tpl.flawIndex,
    flawLabel: tpl.flawLabel,
    explanation: tpl.explanation,
    source: 'template',
  }
}

export function verifyFlawAnswer(pickedIndex, expectedIndex, challengeId = '', expectedId = '') {
  if (expectedId && challengeId && String(challengeId) !== String(expectedId)) {
    return { ok: false, correct: false, reason: '题目已刷新，请按最新展示的残局作答' }
  }
  const p = Number(pickedIndex)
  const e = Number(expectedIndex)
  if (!Number.isInteger(p) || p < 0 || p > 3) {
    return { ok: false, correct: false, reason: '无效选项索引' }
  }
  return { ok: true, correct: p === e, pickedIndex: p, expectedIndex: e }
}

export function buildNineGrid({ pool = [], stelesCount = 0, now = Date.now() } = {}) {
  const topKnowledge = sampleKnowledgeItems(pool, DEFAULT_FRAMEWORK, { count: 3, now, markShown: false })
  const activeCount = (Array.isArray(pool) ? pool : []).filter((x) => x && x.status === 'active').length
  const row1 = [0, 1, 2].map((i) => {
    const k = topKnowledge[i]
    const target = k ? cleanStr(k.claim, 72) : '从 Gemini 或剪贴板补入 1 条核心切片并完成 5m 推进'
    return {
      id: `grid-k-${i}`,
      kind: 'knowledge',
      actionKind: 'cadence_5m',
      diceFace: (i * 2) + 1,
      title: k ? cleanStr(k.claim, 18) : '知识海补充新卡',
      subtitle: k ? `${k.source} · 5m攻克` : '复制Gemini回流',
      badge: `弹药 ${activeCount}`,
      itemId: k?.id || '',
      recommendedTarget: target,
    }
  })

  const row2 = [
    {
      id: 'grid-logic',
      kind: 'bout',
      actionKind: 'cadence_5m',
      diceFace: 6,
      title: '判断推理速杀',
      subtitle: '5m 高压一局',
      badge: '行测',
      recommendedTarget: '行测判断推理：连做 5 题并写出每题排除理由',
    },
    {
      id: 'grid-data',
      kind: 'bout',
      actionKind: 'flaw_spot',
      diceFace: 4,
      title: '资料错题重榨',
      subtitle: '抓速算防坑点',
      badge: '提分',
      recommendedTarget: '资料分析错题重榨：找基数偷换/截位误差并改错',
    },
    {
      id: 'grid-essay',
      kind: 'bout',
      actionKind: 'live_dice',
      diceFace: 5,
      title: '申论一句破题',
      subtitle: '先喷1句再接AI',
      badge: '输出',
      recommendedTarget: '申论提炼：先口述 1 句核心对策因果再开 Live 追问',
    },
  ]

  const row3 = [
    {
      id: 'grid-grocery',
      kind: 'grocery',
      actionKind: 'grocery',
      diceFace: 2,
      title: '买菜 9 宫格',
      subtitle: '看图防折返8趟',
      badge: '生活',
      recommendedTarget: '出门看图点选采购单（顺路一次拿齐）',
    },
    {
      id: 'grid-walk',
      kind: 'interrogate',
      actionKind: 'interrogate',
      diceFace: 4,
      title: '3刀追问立约',
      subtitle: '免坐桌前失神',
      badge: '契约',
      recommendedTarget: '3刀追问收口并锁定下一段 25m 任务',
    },
    {
      id: 'grid-wanshiming',
      kind: 'wanshiming',
      actionKind: 'wanshiming',
      diceFace: 3,
      title: '观《万世铭》',
      subtitle: `已铭 ${stelesCount} 通石碑`,
      badge: '长卷',
      recommendedTarget: '校准长期主航道并刻石 1 条进展',
    },
  ]

  return [...row1, ...row2, ...row3]
}

export function rollNineGridTrigger({
  pool = [],
  stelesCount = 0,
  windowMode = null,
  cadence = null,
  excludeTileId = '',
  excludeDiceFace = 0,
  now = Date.now(),
} = {}) {
  const tiles = buildNineGrid({ pool, stelesCount, now })
  const mode = String(windowMode?.mode || 'general')

  const candidates = tiles
    .filter((t) => {
      if (t.id === excludeTileId) return false
      if (mode === 'desk_moat') {
        return t.kind === 'knowledge' || t.kind === 'bout'
      }
      return t.kind !== 'wanshiming'
    })
    .map((t) => {
      let w = 1.0
      if (mode === 'desk_moat') {
        w = t.kind === 'bout' ? 1.8 : 1.4
      } else if (mode === 'fragmented_digest') {
        if (t.id === 'grid-essay' || t.kind === 'grocery') w = 1.7
        else if (t.kind === 'knowledge') w = 1.3
        else w = 0.7
      }
      return { tile: t, weight: w }
    })

  const poolList = candidates.length ? candidates : tiles.slice(0, 6).map((tile) => ({ tile, weight: 1 }))
  let sum = poolList.reduce((acc, x) => acc + x.weight, 0)
  let roll = Math.random() * sum
  let chosen = poolList[0].tile
  for (const entry of poolList) {
    roll -= entry.weight
    if (roll <= 0) {
      chosen = entry.tile
      break
    }
  }

  let diceFace = Number(chosen.diceFace) || 1
  if (diceFace === Number(excludeDiceFace)) {
    diceFace = (diceFace % 6) + 1
  }
  const diceMeta = SIX_DICE_MINI[diceFace] || SIX_DICE_MINI[1]

  let recommendedAction = 'cadence_5m'
  if (mode === 'desk_moat') {
    recommendedAction = chosen.actionKind === 'flaw_spot' || (now % 4 === 0) ? 'flaw_spot' : 'cadence_5m'
  } else if (mode === 'fragmented_digest') {
    recommendedAction = chosen.actionKind === 'live_dice' || chosen.kind === 'knowledge' ? 'live_dice' : 'cadence_5m'
  } else if (chosen.actionKind === 'flaw_spot' || chosen.actionKind === 'live_dice') {
    recommendedAction = chosen.actionKind
  }

  const target = cleanStr(
    chosen.recommendedTarget || cadence?.target || chosen.title || '完成当前 5 分钟核心切片',
    120,
  )
  const brief = [
    `【九宫格×六骰随机落子 · ${mode === 'desk_moat' ? '桌前护城河' : (mode === 'fragmented_digest' ? '零碎清口窗' : '随时破冰')}】`,
    `靶子格：${chosen.title}（${chosen.badge}）`,
    `思维手术刀：骰${diceFace}·${diceMeta.name} — ${diceMeta.ask}`,
    `执行动作：${target}`,
  ].join('\n')

  return {
    tile: chosen,
    diceFace,
    diceName: diceMeta.name,
    diceAsk: diceMeta.ask,
    recommendedAction,
    target,
    brief,
  }
}

export function applyAppeal(usageDayRaw = {}, frameworkRaw = {}, { kind = '', now = Date.now(), day = '' } = {}) {
  const fw = normalizeFramework(frameworkRaw)
  const dayKey = day || new Date(now).toISOString().slice(0, 10)
  const cur = usageDayRaw && usageDayRaw.day === dayKey
    ? { day: dayKey, total: Number(usageDayRaw.total) || 0, physio: Number(usageDayRaw.physio) || 0 }
    : { day: dayKey, total: 0, physio: 0 }

  if (cur.total >= fw.dailyAppealLimit) {
    return {
      ok: false,
      error: `今日申诉额度已用满（${cur.total}/${fw.dailyAppealLimit}），必须正面过局或完成微题。`,
      usage: cur,
    }
  }

  if (kind === 'physio_rest') {
    if (cur.physio >= fw.dailyPhysioRestLimit) {
      return {
        ok: false,
        error: `今日「彻底呆滞·生理停战」额度已用满（${cur.physio}/${fw.dailyPhysioRestLimit}）。若持续极度无力建议线下心身科评估。`,
        usage: cur,
      }
    }
    const next = { day: dayKey, total: cur.total + 1, physio: cur.physio + 1 }
    return {
      ok: true,
      kind,
      grantMinutes: fw.physioRestMin,
      message: `已批准 ${fw.physioRestMin} 分钟生理停战回血，到期轻震重检。`,
      usage: next,
    }
  }

  const next = { day: dayKey, total: cur.total + 1, physio: cur.physio }
  const actionMap = {
    downgrade_micro_quiz: { grantMinutes: 5, message: '申诉降档：连续答对 5 道 #1 亮屏判断微题即解锁。' },
    swap_item: { grantMinutes: 5, message: '已消耗 1 次申诉重抽 1 条知识海素材，继续 5 分钟局。' },
    handoff_gemini_live: { grantMinutes: 10, message: '已携上下文放行进入外部 Gemini 深潜，挂断回来交 1 句结论即销锁。' },
  }
  const hit = actionMap[kind]
  if (!hit) {
    return { ok: false, error: '未知的申诉类型', usage: cur }
  }
  return { ok: true, kind, ...hit, usage: next }
}

export function seedSteles() {
  return SEED_STELES.map((s) => ({ ...s }))
}

export function upsertStele(steles = [], payload = {}, now = Date.now()) {
  const list = Array.isArray(steles) ? steles.map((x) => ({ ...x })) : []
  const title = cleanStr(payload.title, 80)
  const body = String(payload.body || '').trim().slice(0, 1200)
  if (!title || !body) {
    return { ok: false, error: '铭题与举措内容不可为空', steles: list }
  }
  const date = /^\d{4}-\d{2}-\d{2}$/.test(String(payload.date || ''))
    ? String(payload.date)
    : new Date(now).toISOString().slice(0, 10)
  const category = ['抉择', '战役', '悟道', '纪元'].includes(payload.category) ? payload.category : '抉择'
  const stage = cleanStr(payload.stage || payload.epoch || category, 32)
  const epoch = cleanStr(payload.epoch || stage, 32)
  const echo = cleanStr(payload.echo, 240)
  const starred = Boolean(payload.starred)
  const links = Array.isArray(payload.links)
    ? [...new Set(payload.links.map((x) => cleanStr(x, 64)).filter(Boolean))].slice(0, 8)
    : null

  const id = cleanStr(payload.id, 64)
  const idx = id ? list.findIndex((x) => x.id === id) : -1
  if (idx >= 0) {
    const prevLinks = Array.isArray(list[idx].links) ? list[idx].links : []
    list[idx] = {
      ...list[idx],
      title,
      date,
      category,
      stage,
      epoch,
      body,
      echo,
      starred,
      links: (links ?? prevLinks).filter((lid) => lid !== list[idx].id),
      updatedAt: new Date(now).toISOString(),
    }
    return { ok: true, item: list[idx], steles: list }
  }
  const created = {
    id: `stele-${now}-${Math.random().toString(36).slice(2, 6)}`,
    title,
    date,
    category,
    stage,
    epoch,
    body,
    echo,
    starred,
    links: links ?? [],
    createdAt: new Date(now).toISOString(),
  }
  list.unshift(created)
  return { ok: true, item: created, steles: list }
}

export function deleteStele(steles = [], id = '') {
  const list = Array.isArray(steles) ? steles : []
  const next = list
    .filter((x) => x && x.id !== id)
    .map((x) => (Array.isArray(x.links) ? { ...x, links: x.links.filter((l) => l !== id) } : x))
  return { ok: next.length < list.length, steles: next }
}

const PERFUNCTORY_SUBMISSION_RE = /^(?:ok|good|done|pass|test|yes|no|好的?|已完成|完成了?|打卡|已做|弄完了?|搞定了?|随便|敷衍|测试|过了?|行|嗯+|哈+|嘿+|[0-9a-zA-Z.\-_!\s]{1,10})$/i

function normalizeKnowledgeAnchor(raw) {
  if (!raw || typeof raw !== 'object') return null
  const claim = cleanStr(raw.claim || raw.title || '', 120)
  if (!claim) return null
  return {
    id: cleanStr(raw.id || '', 64),
    claim,
    question: cleanStr(raw.question || '用 1 句话说明其成立前提或关键反例。', 120),
    source: cleanStr(raw.source || 'quiz', 24),
  }
}

export function defaultCadence(now = 0) {
  return {
    active: false,
    phase: 'idle',
    intervalSec: DEFAULT_FRAMEWORK.fiveMinBoutSec,
    round: 0,
    streak: 0,
    verifiedToday: 0,
    day: now ? new Date(now).toISOString().slice(0, 10) : '',
    target: '',
    goalId: '',
    goalScope: '',
    category: '',
    declarationId: '',
    knowledgeAnchor: null,
    liveLinked: false,
    startedAt: 0,
    dueAt: 0,
    overdueSince: 0,
    lastVerifiedAt: 0,
    lastSubmission: '',
    lastPromptAt: 0,
    allowApps: [],
  }
}

export function normalizeCadence(raw = {}) {
  const base = defaultCadence()
  if (!raw || typeof raw !== 'object') return base
  const phase = ['idle', 'running', 'awaiting_verify'].includes(raw.phase) ? raw.phase : 'idle'
  const active = Boolean(raw.active) && phase !== 'idle'
  return {
    active,
    phase: active ? phase : 'idle',
    intervalSec: clampInt(raw.intervalSec, 60, 1800, base.intervalSec),
    round: Math.max(0, Math.round(Number(raw.round) || 0)),
    streak: Math.max(0, Math.round(Number(raw.streak) || 0)),
    verifiedToday: Math.max(0, Math.round(Number(raw.verifiedToday) || 0)),
    day: cleanStr(raw.day, 16),
    target: cleanStr(raw.target, 140),
    goalId: cleanStr(raw.goalId, 64),
    goalScope: cleanStr(raw.goalScope, 16),
    category: cleanStr(raw.category, 24),
    declarationId: cleanStr(raw.declarationId, 64),
    knowledgeAnchor: normalizeKnowledgeAnchor(raw.knowledgeAnchor),
    liveLinked: Boolean(raw.liveLinked),
    startedAt: Math.max(0, Number(raw.startedAt) || 0),
    dueAt: Math.max(0, Number(raw.dueAt) || 0),
    overdueSince: Math.max(0, Number(raw.overdueSince) || 0),
    lastVerifiedAt: Math.max(0, Number(raw.lastVerifiedAt) || 0),
    lastSubmission: cleanStr(raw.lastSubmission, 500),
    lastPromptAt: Math.max(0, Number(raw.lastPromptAt) || 0),
    allowApps: Array.isArray(raw.allowApps)
      ? raw.allowApps.map((x) => cleanStr(x, 80)).filter(Boolean).slice(0, 12)
      : [],
  }
}

function buildKnowledgeAnchorFromPool(pool = [], frameworkRaw = {}, now = Date.now()) {
  const picked = sampleKnowledgeItems(pool, frameworkRaw, { count: 1, now, markShown: false })[0]
  if (!picked) return null
  const diceFaces = Object.values(SIX_DICE_MINI)
  const qPick = diceFaces[Math.abs(Math.floor(now / 1000)) % diceFaces.length]
  return {
    id: cleanStr(picked.id, 64),
    claim: cleanStr(picked.claim, 120),
    question: `${qPick.name}：${cleanStr(qPick.ask, 90)}`,
    source: cleanStr(picked.source || 'quiz', 24),
  }
}

export function startCadence(cadenceRaw = {}, frameworkRaw = {}, opts = {}) {
  const now = Number(opts.now) || Date.now()
  const day = cleanStr(opts.day, 16) || new Date(now).toISOString().slice(0, 10)
  const fw = normalizeFramework(frameworkRaw)
  const cur = normalizeCadence(cadenceRaw)
  const intervalSec = clampInt(opts.intervalSec ?? fw.fiveMinBoutSec, 60, 1800, fw.fiveMinBoutSec)
  const sameDay = cur.day === day
  const knowledgeAnchor = normalizeKnowledgeAnchor(opts.knowledgeAnchor) ||
    (Array.isArray(opts.pool) && opts.pool.length ? buildKnowledgeAnchorFromPool(opts.pool, fw, now) : cur.knowledgeAnchor)
  const target = cleanStr(opts.target, 140) || cur.target || (knowledgeAnchor ? `咬定切片：${knowledgeAnchor.claim.slice(0, 60)}` : '5分钟严格切片：咬住眼前这一题或这一句话产出')
  const allowApps = Array.isArray(opts.allowApps)
    ? opts.allowApps.map((x) => cleanStr(x, 80)).filter(Boolean).slice(0, 12)
    : cur.allowApps
  return {
    active: true,
    phase: 'running',
    intervalSec,
    round: (sameDay ? cur.round : 0) + 1,
    streak: sameDay ? cur.streak : 0,
    verifiedToday: sameDay ? cur.verifiedToday : 0,
    day,
    target,
    goalId: cleanStr(opts.goalId ?? cur.goalId, 64),
    goalScope: cleanStr(opts.goalScope ?? cur.goalScope, 16),
    category: cleanStr(opts.category ?? cur.category, 24),
    declarationId: cleanStr(opts.declarationId ?? cur.declarationId, 64),
    knowledgeAnchor,
    liveLinked: Boolean(opts.liveLinked),
    startedAt: now,
    dueAt: now + intervalSec * 1000,
    overdueSince: 0,
    lastVerifiedAt: cur.lastVerifiedAt,
    lastSubmission: cur.lastSubmission,
    lastPromptAt: 0,
    allowApps,
  }
}

export function tickCadence(cadenceRaw = {}, now = Date.now(), day = '') {
  const dayKey = cleanStr(day, 16) || new Date(now).toISOString().slice(0, 10)
  let cur = normalizeCadence(cadenceRaw)
  if (!cur.active) {
    if (cur.day && cur.day !== dayKey) {
      cur = { ...cur, day: dayKey, round: 0, streak: 0, verifiedToday: 0 }
    }
    return {
      cadence: cur,
      phase: 'idle',
      justEnteredOverdue: false,
      dueAlert: false,
      remainingSec: 0,
      overdueSec: 0,
    }
  }
  if (cur.phase === 'running' && cur.dueAt > 0 && now >= cur.dueAt) {
    const next = {
      ...cur,
      phase: 'awaiting_verify',
      overdueSince: cur.dueAt,
      lastPromptAt: now,
    }
    return {
      cadence: next,
      phase: 'awaiting_verify',
      justEnteredOverdue: true,
      dueAlert: true,
      remainingSec: 0,
      overdueSec: Math.max(0, Math.floor((now - cur.dueAt) / 1000)),
    }
  }
  if (cur.phase === 'awaiting_verify') {
    const since = cur.overdueSince || cur.dueAt || now
    const dueAlert = !cur.lastPromptAt || (now - cur.lastPromptAt) >= 45_000
    const next = dueAlert ? { ...cur, lastPromptAt: now } : cur
    return {
      cadence: next,
      phase: 'awaiting_verify',
      justEnteredOverdue: false,
      dueAlert,
      remainingSec: 0,
      overdueSec: Math.max(0, Math.floor((now - since) / 1000)),
    }
  }
  return {
    cadence: cur,
    phase: 'running',
    justEnteredOverdue: false,
    dueAlert: false,
    remainingSec: Math.max(0, Math.ceil((cur.dueAt - now) / 1000)),
    overdueSec: 0,
  }
}

function isPerfunctoryDelivery(text) {
  if (!text || text.length < 6) return true
  if (PERFUNCTORY_SUBMISSION_RE.test(text)) return true
  const uniqueChars = new Set(text.replace(/\s+/g, '').split(''))
  return uniqueChars.size < 4
}

export function submitCadenceDelivery(cadenceRaw = {}, steles = [], frameworkRaw = {}, opts = {}) {
  const now = Number(opts.now) || Date.now()
  const dayKey = cleanStr(opts.day, 16) || new Date(now).toISOString().slice(0, 10)
  const fw = normalizeFramework(frameworkRaw)
  const cur = normalizeCadence(cadenceRaw)
  const quizPassed = Boolean(opts.quizPassed)
  const text = cleanStr(opts.submission, 500)

  if (!quizPassed && isPerfunctoryDelivery(text)) {
    return {
      ok: false,
      error: '交付过短或疑似敷衍：请写清你具体推进了哪一题/得出哪条真实结论（至少 6 字且有效信息 >= 4 字符），或连续答对 3 题解锁。',
      cadence: cur,
      steles: Array.isArray(steles) ? steles : [],
      autoStele: null,
    }
  }

  const sameDay = cur.day === dayKey
  const nextStreak = (sameDay ? cur.streak : 0) + 1
  const nextVerifiedToday = (sameDay ? cur.verifiedToday : 0) + 1
  const summaryBody = quizPassed && !text
    ? '连续答对 3 题硬验收过关（高强度唤醒确认）'
    : text

  let nextSteles = Array.isArray(steles) ? steles : []
  let autoStele = null
  if ((fw.autoInscribeWanshiming === true || opts.forceAutoStele === true) && nextStreak > 0 && nextStreak % 3 === 0) {
    const steleRes = upsertStele(nextSteles, {
      title: `5m严验收·${nextStreak}连胜：${(cur.target || '高强度切片').slice(0, 22)}`,
      date: dayKey,
      category: cur.goalScope ? '战役' : '抉择',
      stage: '高频闭环自动铭刻',
      epoch: '高频闭环自动铭刻',
      body: `目标：${cur.target || '当前核心切片'}\n最新交付：${summaryBody}`,
      echo: `单日完成 ${nextVerifiedToday} 轮 · 连胜 ${nextStreak} 轮自动刻入《万世铭》`,
      starred: nextStreak % 6 === 0,
    }, now)
    if (steleRes.ok) {
      nextSteles = steleRes.steles
      autoStele = steleRes.item
    }
  }

  const stagingEntry = {
    id: `stg-cad-${now.toString(36)}-${Math.random().toString(36).slice(2, 5)}`,
    at: now,
    kind: 'cadence_verify',
    category: cur.category || '5m验收',
    text: `【5m产出】${cur.target ? `${cur.target} -> ` : ''}${summaryBody}`.slice(0, 180),
    blocker: '',
    promoted: false,
    tags: ['5m交付', cur.category || '日常推进'].filter(Boolean),
  }

  const continueNext = opts.continueNext !== false
  const intervalSec = clampInt(cur.intervalSec || fw.fiveMinBoutSec, 60, 1800, fw.fiveMinBoutSec)
  const nextTarget = cleanStr(opts.nextTarget, 140) || cur.target || '继续下一个 5 分钟核心切片'
  const nextAnchor = continueNext && Array.isArray(opts.pool) && opts.pool.length
    ? buildKnowledgeAnchorFromPool(opts.pool, fw, now + 1000)
    : cur.knowledgeAnchor
  const nextCadence = continueNext
    ? {
        ...cur,
        active: true,
        phase: 'running',
        intervalSec,
        round: (sameDay ? cur.round : 0) + 1,
        streak: nextStreak,
        verifiedToday: nextVerifiedToday,
        day: dayKey,
        target: nextTarget,
        knowledgeAnchor: nextAnchor,
        startedAt: now,
        dueAt: now + intervalSec * 1000,
        overdueSince: 0,
        lastVerifiedAt: now,
        lastSubmission: summaryBody,
        lastPromptAt: 0,
      }
    : {
        ...cur,
        active: false,
        phase: 'idle',
        streak: nextStreak,
        verifiedToday: nextVerifiedToday,
        day: dayKey,
        dueAt: 0,
        overdueSince: 0,
        lastVerifiedAt: now,
        lastSubmission: summaryBody,
        lastPromptAt: 0,
      }

  return {
    ok: true,
    cadence: nextCadence,
    steles: nextSteles,
    autoStele,
    stagingEntry,
    message: autoStele
      ? `验收通过（连胜 ${nextStreak} 轮）！已自动铭刻碑石「${autoStele.title}」，下一轮 ${Math.round(intervalSec / 60)} 分钟已开启。`
      : (continueNext
          ? `验收通过（今日第 ${nextVerifiedToday} 轮 · 连胜 ${nextStreak}），产出已记入临时库，下一轮 ${Math.round(intervalSec / 60)} 分钟已换新知识锚点开启。`
          : `本轮验收通过（今日累计 ${nextVerifiedToday} 轮），结论已记入临时数据库。`),
  }
}

export function stopCadence(cadenceRaw = {}) {
  const cur = normalizeCadence(cadenceRaw)
  return {
    ...cur,
    active: false,
    phase: 'idle',
    dueAt: 0,
    overdueSince: 0,
    lastPromptAt: 0,
  }
}

function normalizeStagingEntry(raw) {
  if (!raw || typeof raw !== 'object') return null
  const text = cleanStr(raw.text || '', 220)
  if (!text) return null
  return {
    id: cleanStr(raw.id || `stg-${Date.now().toString(36)}`, 64),
    at: Math.max(0, Number(raw.at) || 0),
    kind: cleanStr(raw.kind || 'note', 24),
    category: cleanStr(raw.category || '随手记', 24),
    text,
    blocker: cleanStr(raw.blocker || '', 120),
    promoted: Boolean(raw.promoted),
    tags: Array.isArray(raw.tags) ? raw.tags.map((t) => cleanStr(t, 24)).filter(Boolean).slice(0, 6) : [],
  }
}

export function addStagingEntry(inbox = [], payload = {}, now = Date.now()) {
  const list = (Array.isArray(inbox) ? inbox : []).map(normalizeStagingEntry).filter(Boolean)
  const text = cleanStr(payload.text || '', 220)
  if (!text || text.length < 4) {
    return { ok: false, error: '内容太短（至少 4 个字），写清具体事或结论再存入临时库', stagingInbox: list }
  }
  const norm = text.toLowerCase()
  const filtered = list.filter((x) => x.text.toLowerCase() !== norm)
  const entry = {
    id: `stg-${now.toString(36)}-${Math.random().toString(36).slice(2, 5)}`,
    at: now,
    kind: cleanStr(payload.kind || 'note', 24),
    category: cleanStr(payload.category || '随手记', 24),
    text,
    blocker: cleanStr(payload.blocker || '', 120),
    promoted: false,
    tags: Array.isArray(payload.tags) ? payload.tags.map((t) => cleanStr(t, 24)).filter(Boolean).slice(0, 6) : ['临时库'],
  }
  return {
    ok: true,
    item: entry,
    stagingInbox: [entry, ...filtered].slice(0, 120),
  }
}

export function promoteStagingEntry(inbox = [], id = '') {
  const list = (Array.isArray(inbox) ? inbox : []).map(normalizeStagingEntry).filter(Boolean)
  let hit = null
  const next = list.map((x) => {
    if (x.id === id) {
      hit = { ...x, promoted: true }
      return hit
    }
    return x
  })
  return { ok: Boolean(hit), item: hit, stagingInbox: next }
}

export function deleteStagingEntry(inbox = [], id = '') {
  const list = (Array.isArray(inbox) ? inbox : []).map(normalizeStagingEntry).filter(Boolean)
  const next = list.filter((x) => x.id !== id)
  return { ok: next.length < list.length, stagingInbox: next }
}

/**
 * 固执强制申报：刷题/学习/做正事/休息必须写明具体内容才能纳入大肥鱼体系与双鱼记忆。
 */
export function declareCurrentActivity(state = {}, payload = {}, now = Date.now(), day = '') {
  const dayKey = cleanStr(day, 16) || new Date(now).toISOString().slice(0, 10)
  const category = STUBBORN_CATEGORIES.includes(payload.category) ? payload.category : '刷题'
  const task = cleanStr(payload.task || payload.text || '', 140)
  const blocker = cleanStr(payload.blocker || '', 100)
  const isRest = category === '休息回血'
  const expectedMin = clampInt(payload.expectedMin ?? (isRest ? 20 : 25), 5, 180, 25)

  if (!isRest) {
    if (!task || task.length < 4 || VAGUE_DECLARATION_RE.test(task)) {
      return {
        ok: false,
        error: '调度器拒绝糊弄收编：请写清具体动作/科目/题型（至少 4 字，不能只填“刷题/学习/随便”）。',
      }
    }
  }

  const finalTask = isRest && !task ? '刷题后 L0 零压回血休息（暂不派新题）' : task
  const decId = `dec-${now.toString(36)}-${Math.random().toString(36).slice(2, 5)}`
  const declaration = {
    id: decId,
    category,
    task: finalTask,
    blocker,
    expectedMin,
    declaredAt: now,
    expiresAt: now + expectedMin * 60_000,
    day: dayKey,
  }
  const stgText = `【正在${category}】${finalTask}${blocker ? `（卡点：${blocker}）` : ''}`
  const added = addStagingEntry(state.stagingInbox, {
    kind: 'declaration',
    category,
    text: stgText,
    blocker,
    tags: ['固执申报', category],
  }, now)

  const bindCadence = payload.bindCadence !== false && !isRest
  const cadenceOpts = bindCadence
    ? {
        target: `[${category}] ${finalTask}`.slice(0, 120),
        category,
        declarationId: decId,
        intervalSec: state.framework?.fiveMinBoutSec || DEFAULT_FRAMEWORK.fiveMinBoutSec,
        pool: state.pool || [],
        now,
        day: dayKey,
      }
    : null

  return {
    ok: true,
    declaration,
    stagingEntry: added.item,
    stagingInbox: added.stagingInbox,
    cadenceOpts,
  }
}

export function evaluateStubbornGate(state = {}, {
  now = Date.now(),
  runningBout = false,
  emergencyActive = false,
  nightLocked = false,
  energyTier = 'L2',
} = {}) {
  const fw = normalizeFramework(state.framework || {})
  const pool = Array.isArray(state.pool) ? state.pool : []
  const suggestedAnchors = sampleKnowledgeItems(pool, fw, { count: 3, now, markShown: false }).map((k) => ({
    id: k.id,
    claim: cleanStr(k.claim, 64),
    source: k.source || 'quiz',
  }))

  const activeDec = state.activeDeclaration && Number(state.activeDeclaration.expiresAt) > now
    ? state.activeDeclaration
    : null

  if (!fw.stubbornGateEnabled) {
    return { required: false, reason: 'disabled', activeDeclaration: activeDec, suggestedAnchors }
  }
  if (emergencyActive || nightLocked || String(energyTier || '').toUpperCase() === 'L0') {
    return { required: false, reason: 'safe_yield', activeDeclaration: activeDec, suggestedAnchors }
  }
  if (activeDec || state.cadence?.active || runningBout) {
    return { required: false, reason: 'already_declared', activeDeclaration: activeDec, suggestedAnchors }
  }
  const lastPrompt = Math.max(0, Number(state.lastStubbornPromptAt) || 0)
  const cooldownMs = fw.stubbornCooldownMin * 60_000
  if (lastPrompt > 0 && now - lastPrompt < cooldownMs) {
    return {
      required: false,
      reason: 'cooldown',
      activeDeclaration: null,
      nextAskAt: lastPrompt + cooldownMs,
      suggestedAnchors,
    }
  }
  return {
    required: true,
    reason: 'missing_declaration',
    activeDeclaration: null,
    suggestedAnchors,
  }
}

export function defaultV6State(now = Date.now()) {
  return {
    v: 1,
    framework: normalizeFramework(DEFAULT_FRAMEWORK),
    pool: seedKnowledgePool(now),
    appeals: { day: new Date(now).toISOString().slice(0, 10), total: 0, physio: 0 },
    steles: seedSteles(),
    lastContract: null,
    cadence: defaultCadence(now),
    pendingFlaw: null,
    stagingInbox: [],
    activeDeclaration: null,
    lastStubbornPromptAt: 0,
    lastMemoryExplain: null,
    recentMemoryDrawIds: [],
  }
}

export function loadV6State(readState, filePath, now = Date.now()) {
  const raw = readState(filePath, null)
  if (!raw || typeof raw !== 'object') {
    return defaultV6State(now)
  }
  return {
    v: 1,
    framework: normalizeFramework(raw.framework || {}),
    pool: Array.isArray(raw.pool) && raw.pool.length ? raw.pool : seedKnowledgePool(now),
    appeals: raw.appeals && typeof raw.appeals === 'object' ? raw.appeals : { day: '', total: 0, physio: 0 },
    steles: Array.isArray(raw.steles) && raw.steles.length ? raw.steles : seedSteles(),
    lastContract: raw.lastContract || null,
    cadence: normalizeCadence(raw.cadence),
    pendingFlaw: raw.pendingFlaw && typeof raw.pendingFlaw === 'object' ? raw.pendingFlaw : null,
    stagingInbox: Array.isArray(raw.stagingInbox) ? raw.stagingInbox.map(normalizeStagingEntry).filter(Boolean).slice(0, 120) : [],
    activeDeclaration: raw.activeDeclaration && typeof raw.activeDeclaration === 'object' ? raw.activeDeclaration : null,
    lastStubbornPromptAt: Math.max(0, Number(raw.lastStubbornPromptAt) || 0),
    lastMemoryExplain: raw.lastMemoryExplain && typeof raw.lastMemoryExplain === 'object' ? raw.lastMemoryExplain : null,
    recentMemoryDrawIds: Array.isArray(raw.recentMemoryDrawIds) ? raw.recentMemoryDrawIds.map((x) => cleanStr(x, 64)).filter(Boolean).slice(-24) : [],
  }
}
