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
  liveDropGraceSec: 90,
  fiveMinBoutSec: 300,
  strictLockGraceSec: 45,
  dailyAppealLimit: 3,
  dailyPhysioRestLimit: 1,
  physioRestMin: 15,
  poolCooldownHours: 18,
  poolGraduateStreak: 2,
  poolDecayDays: 14,
  fragmentedWindows: Object.freeze(['11:30-13:30', '17:30-19:30']),
  deskMoatWindows: Object.freeze(['09:00-11:30', '14:30-17:30', '20:00-22:00']),
})

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
    id: 'stele-20261126-v6',
    title: '立《万世铭》与 V6 双模态主动调度总框架',
    date: '2026-11-26',
    epoch: '丙午 · 破壁',
    category: '纪元',
    body: '确立双路并行战略：一边把大肥鱼改造成主动触发与知识海调度中枢（#1 亮屏盲点、#7 强关联九宫格、5m严锁4申诉阀）；一边以麦克风+虚拟屏监测灯保活 Gemini Live，并让剪贴板双向总线打通两翼。',
    echo: '告别改时间重编译 APK，也告别因知识贫血重复念叨惹人厌烦。',
    starred: true,
    createdAt: '2026-11-26T22:00:00+08:00',
  },
  {
    id: 'stele-20261125-dshgo',
    title: '重构 DSH Go 触发与调度：五分钟心跳与防糊弄验收',
    date: '2026-11-25',
    epoch: '丙午 · 破局',
    category: '战役',
    body: '识破“仅靠单一验收锁终会滑向降频敷衍”之困局，补齐 5 分钟声学心跳、planModal 防死锁与伙伴脑核反敷衍校验。',
    echo: '先封逃避退路，再谈精进。',
    starred: false,
    createdAt: '2026-11-25T22:10:00+08:00',
  },
  {
    id: 'stele-20261119-empirical',
    title: '弃道理堆砌，立实证基准率与反事实推理底律',
    date: '2026-11-19',
    epoch: '丙午 · 筑基',
    category: '悟道',
    body: '一切复杂判断先查同类大样本基准率与个人历史真实行为分布，用反事实剃刀抓唯一卡脖子变量；缺数据时做最小采数实验。',
    echo: '真判断区分廉价表态与高成本信号，宁认缺数据，不作伪闭环。',
    starred: true,
    createdAt: '2026-11-19T20:00:00+08:00',
  },
  {
    id: 'stele-20261118-v4capsule',
    title: 'V4 防死锁专注舱与认知 OS 体系拍板',
    date: '2026-11-18',
    epoch: '丙午 · 筑基',
    category: '抉择',
    body: '完成 Wave1 至 Wave3 全量验收，确立 Obsidian Overseer 三层防污染工作流与今日作战账本。',
    echo: '凡未入账本之构想皆属飘萍；唯经过待审与拍板者，方能沉淀为铁律。',
    starred: false,
    createdAt: '2026-11-18T11:20:00+08:00',
  },
  {
    id: 'stele-20261117-today',
    title: 'Today 工作台收编与默认严格锁拍板',
    date: '2026-11-17',
    epoch: '丙午 · 启程',
    category: '抉择',
    body: '拍板走路径二收编 Today 工作台并启用默认严格锁机制，把“先看状态再开闸”变成日常第一道硬件级门槛。',
    echo: '以轻微摩擦换取主权清醒。',
    starred: false,
    createdAt: '2026-11-17T16:00:00+08:00',
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

export function sampleKnowledgeItems(pool = [], frameworkRaw = {}, { count = 1, now = Date.now(), markShown = false } = {}) {
  const fw = normalizeFramework(frameworkRaw)
  const cooldownMs = fw.poolCooldownHours * HOUR_MS
  const active = (Array.isArray(pool) ? pool : []).filter((c) => c && c.status !== 'archived' && c.status !== 'graduated')
  let eligible = active.filter((c) => !c.lastShownAt || (now - Number(c.lastShownAt)) >= cooldownMs)
  if (!eligible.length) eligible = active

  // Weighted stochastic pick (avoids strict linear replay while respecting weights & freshness)
  const remaining = [...eligible]
  const picked = []
  const want = Math.max(1, Math.min(20, Number(count) || 1))

  while (picked.length < want && remaining.length > 0) {
    let totalW = 0
    const weights = remaining.map((c) => {
      const freshBonus = Number(c.seenCount || 0) === 0 ? 1.5 : 1.0
      const w = Math.max(0.2, (Number(c.weight) || 1.0) * freshBonus)
      totalW += w
      return w
    })
    let roll = Math.random() * totalW
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

export function buildNineGrid({ pool = [], stelesCount = 0, now = Date.now() } = {}) {
  const topKnowledge = sampleKnowledgeItems(pool, DEFAULT_FRAMEWORK, { count: 3, now, markShown: false })
  const row1 = [0, 1, 2].map((i) => {
    const k = topKnowledge[i]
    return {
      id: `grid-k-${i}`,
      kind: 'knowledge',
      title: k ? cleanStr(k.claim, 18) : '知识海补充新卡',
      subtitle: k ? `${k.source} · 5m攻克` : '复制Gemini回流',
      badge: `弹药 ${pool.filter((x) => x.status === 'active').length}`,
      itemId: k?.id || '',
    }
  })

  const row2 = [
    { id: 'grid-logic', kind: 'bout', title: '判断推理速杀', subtitle: '5m 高压一局', badge: '行测' },
    { id: 'grid-data', kind: 'bout', title: '资料错题重榨', subtitle: '抓速算防坑点', badge: '提分' },
    { id: 'grid-essay', kind: 'bout', title: '申论一句破题', subtitle: '先喷1句再接AI', badge: '输出' },
  ]

  const row3 = [
    { id: 'grid-grocery', kind: 'grocery', title: '买菜 9 宫格', subtitle: '看图防折返8趟', badge: '生活' },
    { id: 'grid-walk', kind: 'interrogate', title: '3刀追问立约', subtitle: '免坐桌前失神', badge: '契约' },
    { id: 'grid-wanshiming', kind: 'wanshiming', title: '观《万世铭》', subtitle: `已铭 ${stelesCount} 通石碑`, badge: '长卷' },
  ]

  return [...row1, ...row2, ...row3]
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
  const epoch = cleanStr(payload.epoch, 32)
  const echo = cleanStr(payload.echo, 240)
  const starred = Boolean(payload.starred)

  const id = cleanStr(payload.id, 64)
  const idx = id ? list.findIndex((x) => x.id === id) : -1
  if (idx >= 0) {
    list[idx] = { ...list[idx], title, date, category, epoch, body, echo, starred, updatedAt: new Date(now).toISOString() }
    return { ok: true, item: list[idx], steles: list }
  }
  const created = {
    id: `stele-${now}-${Math.random().toString(36).slice(2, 6)}`,
    title,
    date,
    category,
    epoch,
    body,
    echo,
    starred,
    createdAt: new Date(now).toISOString(),
  }
  list.unshift(created)
  return { ok: true, item: created, steles: list }
}

export function deleteStele(steles = [], id = '') {
  const list = Array.isArray(steles) ? steles : []
  const next = list.filter((x) => x && x.id !== id)
  return { ok: next.length < list.length, steles: next }
}

const PERFUNCTORY_SUBMISSION_RE = /^(?:ok|good|done|pass|test|yes|no|好的?|已完成|完成了?|打卡|已做|弄完了?|搞定了?|随便|敷衍|测试|过了?|行|嗯+|哈+|嘿+|[0-9a-zA-Z.\-_!\s]{1,10})$/i

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

export function startCadence(cadenceRaw = {}, frameworkRaw = {}, opts = {}) {
  const now = Number(opts.now) || Date.now()
  const day = cleanStr(opts.day, 16) || new Date(now).toISOString().slice(0, 10)
  const fw = normalizeFramework(frameworkRaw)
  const cur = normalizeCadence(cadenceRaw)
  const intervalSec = clampInt(opts.intervalSec ?? fw.fiveMinBoutSec, 60, 1800, fw.fiveMinBoutSec)
  const sameDay = cur.day === day
  const target = cleanStr(opts.target, 140) || cur.target || '5分钟严格切片：咬住眼前这一题或这一句话产出'
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
  if (nextStreak > 0 && nextStreak % 3 === 0) {
    const steleRes = upsertStele(nextSteles, {
      title: `5m严验收·${nextStreak}连胜：${(cur.target || '高强度切片').slice(0, 22)}`,
      date: dayKey,
      category: cur.goalScope ? '战役' : '抉择',
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

  const continueNext = opts.continueNext !== false
  const intervalSec = clampInt(cur.intervalSec || fw.fiveMinBoutSec, 60, 1800, fw.fiveMinBoutSec)
  const nextTarget = cleanStr(opts.nextTarget, 140) || cur.target || '继续下一个 5 分钟核心切片'
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
    message: autoStele
      ? `验收通过（连胜 ${nextStreak} 轮）！已自动铭刻碑石「${autoStele.title}」，下一轮 ${Math.round(intervalSec / 60)} 分钟已开启。`
      : (continueNext
          ? `验收通过（今日第 ${nextVerifiedToday} 轮 · 连胜 ${nextStreak}），下一轮 ${Math.round(intervalSec / 60)} 分钟倒计时启动。`
          : `本轮验收通过并已收局（今日累计 ${nextVerifiedToday} 轮）。`),
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

export function defaultV6State(now = Date.now()) {
  return {
    v: 1,
    framework: normalizeFramework(DEFAULT_FRAMEWORK),
    pool: seedKnowledgePool(now),
    appeals: { day: new Date(now).toISOString().slice(0, 10), total: 0, physio: 0 },
    steles: seedSteles(),
    lastContract: null,
    cadence: defaultCadence(now),
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
  }
}
