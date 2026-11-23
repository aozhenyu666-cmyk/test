// dsh-go-companion · V4 AI 协同「计划模态 (Plan Modality)」与 1 分钟缓冲自动严格锁引擎
// 核心理念（融合 GitHub StateProof / agent-workflow）：
// 1) 和 AI 一起把「用哪些 App、本机现有 App 够不够、交什么验收点、看视频怎么压缩提炼验收」写成结构化契约；
// 2) 契约期内在约定 App 范围内柔性自由（支持看视频网站学习），降低 AI 无脑盯屏与乱打断负担；
// 3) 一旦到达检查点截止时间仍未通过验收：给予 1 分钟 (60s) 明确倒计时提醒，1 分钟后仍未交付自动升级为严格锁！

export const OVERDUE_GRACE_MS = 60_000 // 用户拍板：到点缓冲 1 分钟后转严格锁

const VIDEO_OR_FUN_PKG_RE = /(bili|youtube|douyin|aweme|kuaishou|tiktok|weibo|iqiyi|youku|qqlive|video|game|taptap|mihoyo|netease)/i

/** 高频常用计划预设剧本库（彻底告别每次手写 4 个文本框或自己构思提示词） */
export const COMMON_PLAN_PRESETS = Object.freeze([
  {
    id: 'video_extract_study',
    shortLabel: '网课提炼 45m',
    title: '看网课并提炼 1 张三段因果卡',
    scenario: '用视频/网课 App 听课，在 Obsidian 里提炼三段因果链并经 AI 高强度追问过关',
    mode: 'video_extract',
    minutes: 45,
    checkpointTitle: '提交视频压缩提炼与 2 句因果追问',
    expectedDeliverable: '提交 [触发→机制→行动] 因果链，逾期 1 分钟自动严锁视频 App',
    allowedApps: ['md.obsidian', 'tv.danmaku.bili', 'com.tencent.mm'],
    copyPrompt:
      '我要开启「网课提炼契约（45分钟）」。接下来用 B站 看网课并用 Obsidian 记笔记。请你先调 plan_check_apps 核验可用 App，再调 plan_set_contract 立下 45 分钟后的「三段因果链高强度追问验收点」（逾期缓冲 1 分钟自动严格锁）。期间别频繁打扰我，看完我把提炼内容发你验收。',
  },
  {
    id: 'exam_drill_sprint',
    shortLabel: '考公刷题 60m',
    title: '考公行测/申论专项刷题与错题归档',
    scenario: '集中刷 1 套真题并整理 3 道错题因果原因，支持导入锁屏题库',
    mode: 'standard',
    minutes: 60,
    checkpointTitle: '完成本轮套题并提炼 3 道核心错题原因',
    expectedDeliverable: '提交本轮刷题数量、正确率与 3 条错因规则',
    allowedApps: ['md.obsidian', 'com.tencent.mm'],
    copyPrompt:
      '我要开始「考公真题冲刺（60分钟）」。请调用 plan_set_contract 帮我锁 60 分钟契约，只放行 Obsidian/微信，到点缓冲 1 分钟没交「题量+正确率+3条错因」就自动严格锁。',
  },
  {
    id: 'job_resume_apply',
    shortLabel: '简历投递 45m',
    title: '简历项目打磨与定向岗位投递',
    scenario: '优化简历量化亮点并核对岗位 JD 完成真实投递',
    mode: 'standard',
    minutes: 45,
    checkpointTitle: '提交简历改动要点与本轮投递家数',
    expectedDeliverable: '至少修改 1 处量化成果表述并实际投递/沟通 ≥3 家',
    allowedApps: ['md.obsidian', 'cn.wps.moffice_eng', 'com.tencent.mm'],
    copyPrompt:
      '我要做「就业简历优化与投递（45分钟）」。请用 plan_set_contract 立契约：放行 Obsidian/WPS/微信，45 分钟后验收修改结果与投递记录（逾期 1 分钟自动转严锁）。',
  },
  {
    id: 'anti_scam_decision_gate',
    shortLabel: '防骗防偏航检查',
    title: '付款买课 / 开新坑前红旗与沉没审计',
    scenario: '花钱买资料/私下交易前核查信用红旗，或打算改写工具前对齐主航道',
    mode: 'standard',
    minutes: 15,
    checkpointTitle: '完成红旗一票否决或反沉没归零结论',
    expectedDeliverable: '调 audit_decision_redflag 给出明确放行/止损结论',
    allowedApps: ['md.obsidian', 'com.tencent.mm'],
    copyPrompt:
      '先停手，用信息收集部+谋划思考部帮我审一件事：请调用 audit_decision_redflag 检查下面的情况（【填金额/对方说辞，或我想折腾的新功能】），看有没有信用红旗或借折腾工具逃避考公就业的风险，直接给一票否决或可行边界！',
  },
  {
    id: 'rest_recover_contract',
    shortLabel: '合法放风 20m',
    title: '合法休息放风（到点自动收心回位）',
    scenario: '高强度专注后合法休息 20 分钟，到期确认收心并写下第一动作',
    mode: 'standard',
    minutes: 20,
    checkpointTitle: '确认休息结束并报备下一轮第一动作',
    expectedDeliverable: '一句话写明接下来 45 分钟唯一动作（到点超 1m 未报备自动收紧）',
    allowedApps: ['md.obsidian', 'com.tencent.mm'],
    copyPrompt:
      '我刚完成一轮工作，申请「合法休息 20 分钟」。请用 plan_set_contract 定一个 20 分钟倒计时契约，到点 1 分钟内如果我没回来说下一轮干嘛，自动转严格锁拉回工位。',
  },
])

/** 常用对话指令剧本包（用户一键复制发送给鱼丸/鲸澜/Gemini，彻底免去自己造文字） */
export const AI_PROMPT_SCRIPTS = Object.freeze([
  {
    id: 'script_morning_plan',
    category: '每日安排',
    title: '1. 一早定今日三件事（免手填排局）',
    when: '每天开工前，不想在页面里逐个填表时发这句',
    prompt:
      '帮我定今天的主线计划，不用泛泛鼓励：我今天最重要的主航道是【考公/求职】，现有时间约【3】小时。请你直接调用规划工具帮我拆成 ≤3 件今天必打掉的局，配好允许的 App，并定好晚上验收交什么。',
  },
  {
    id: 'script_video_extract',
    category: '计划模态',
    title: '2. 带着目标去视频网站看课（到期未交自动锁）',
    when: '打开 B站/网课 App 前先发这句，防止刷着刷着变成纯娱乐',
    prompt:
      '我要开启「看网课契约」：接下来看【课程名/B站视频】共【45】分钟，用 Obsidian 做提炼。请调 plan_check_apps 检查 App 并用 plan_set_contract 立下视频提炼检查点（到点缓冲 1 分钟未验收自动严格锁）。先别频繁打扰我，到点或我看完再找你高强度追问。',
  },
  {
    id: 'script_video_verify',
    category: '高强度验收',
    title: '3. 把压好的视频笔记交给你狠狠追问验收',
    when: '你用别的 AI 或自己把视频内容压缩完，发给大肥鱼做真懂检验',
    prompt:
      '以下是我把刚看的课程/资料压缩后的三段因果提炼：【粘贴压缩内容】。请不要直接走过场夸我，按统筹规划部+督办审计部模式，针对底层因果和考场/面试反例高强度追问我 2 个核心问题；只有我答到点子上，才调 plan_verify_checkpoint(aiConfirmed=true) 帮我通过并松绑。',
  },
  {
    id: 'script_redflag_pay',
    category: '防骗止损',
    title: '4. 付款 / 报课 / 闲鱼交易前 10 秒防骗安检',
    when: '想花几十上百买课、买资料或遇到私聊先款时（防 80 元重演）',
    prompt:
      '我要决定要不要付一笔钱：【描述商品/金额/对方是否要求私聊、先付定金或说不退不换】。请用信息收集部的 audit_decision_redflag(kind="redflag") 给我跑红旗一票否决检查，有坑直接喊停！',
  },
  {
    id: 'script_anti_sunk',
    category: '防偏航',
    title: '5. 又想折腾工具/改脚本前做主航道对齐审计',
    when: '发现自己又想花几小时做新功能、改提示词而躲避刷题投简历时',
    prompt:
      '我突然想花时间折腾【想做的工具/新配置】。先用谋划思考部的 audit_decision_redflag(kind="mainline") 对齐我的长期主航道（考公/就业），算算沉没成本和机会成本——如果属于借磨刀逃避正事，直接给我喊停并指派最小可凑合动作！',
  },
  {
    id: 'script_bank_wash',
    category: '题库解锁',
    title: '6. 把杂乱真题文本一键洗进锁屏解锁题库',
    when: '随手复制了一段题，懒得调格式 JSON，直接让 AI 洗进题库',
    prompt:
      '帮我把下面这段题目清洗整理后直接调 bank_import_questions 导入我的锁屏题库（之后锁屏答对 10 题解锁 5 分钟）：【粘贴题目与答案】',
  },
  {
    id: 'script_evening_causal',
    category: '体系沉淀',
    title: '7. 睡前 5 分钟收口：压一张因果卡 + 查时间账本',
    when: '结束一天时，拒绝只记流水账，压出真正可复用的体系规律',
    prompt:
      '帮我调统筹规划部 audit_decision_redflag(kind="causal")，结合我今天干的事和踩的坑，逼着我压成「触发场景 → 底层机制 → 下次唯一行动」1 张可复用卡片，并对照今天的 App 用时分布定明早第一局。',
  },
])

/**
 * 合同未过逾期缓冲转严格锁时的白名单收口：
 * 放行期（pending/grace_warning）尊重全部 allowedApps（例如允许看 B站 网课）；
 * 一旦逾期转严格（strictEscalated=true），主动剔除视频/娱乐软件，只保留笔记与沟通工具让用户提交验收，避免边被锁边继续刷视频。
 */
export function resolveContractSessionAllow(contract, { escalated = false, isFunApp = null } = {}) {
  const raw = Array.isArray(contract?.allowedApps) && contract.allowedApps.length
    ? contract.allowedApps.map(String).filter(Boolean)
    : ['md.obsidian', 'com.tencent.mm']
  if (!escalated) return [...new Set(raw)]
  const workOnly = raw.filter((pkg) => {
    if (VIDEO_OR_FUN_PKG_RE.test(pkg)) return false
    if (typeof isFunApp === 'function' && isFunApp(pkg)) return false
    return true
  })
  return workOnly.length ? [...new Set(workOnly)] : ['md.obsidian', 'com.tencent.mm']
}

export function cleanPlanContract(raw) {
  if (!raw || typeof raw !== 'object' || !raw.id) return null
  const cps = (Array.isArray(raw.checkpoints) ? raw.checkpoints : []).map((cp, i) => {
    if (!cp || typeof cp !== 'object') return null
    const title = String(cp.title || '').trim().slice(0, 120)
    if (!title) return null
    return {
      id: String(cp.id || `cp-${i + 1}`),
      title,
      mode: cp.mode === 'video_extract' ? 'video_extract' : 'standard',
      expectedDeliverable: String(cp.expectedDeliverable || '文字/笔记提炼或高强度问答过关').slice(0, 140),
      dueAt: Number(cp.dueAt) || 0,
      graceMs: Math.max(15_000, Math.min(300_000, Number(cp.graceMs) || OVERDUE_GRACE_MS)),
      status: ['pending', 'grace_warning', 'passed', 'overdue_locked'].includes(cp.status) ? cp.status : 'pending',
      submissionSummary: String(cp.submissionSummary || '').slice(0, 1000),
      probes: Array.isArray(cp.probes) ? cp.probes.map((x) => String(x || '').slice(0, 160)).slice(0, 4) : [],
      verifiedAt: Number(cp.verifiedAt) || 0,
      verdictNote: String(cp.verdictNote || '').slice(0, 240),
    }
  }).filter(Boolean)

  return {
    id: String(raw.id),
    title: String(raw.title || 'AI 协同契约计划').slice(0, 100),
    scenario: String(raw.scenario || '').slice(0, 200),
    allowedApps: Array.isArray(raw.allowedApps) ? raw.allowedApps.map(String).filter(Boolean).slice(0, 20) : [],
    missingAppsHint: String(raw.missingAppsHint || '').slice(0, 200),
    strictOnOverdue: raw.strictOnOverdue !== false,
    strictEscalated: Boolean(raw.strictEscalated),
    overdueGraceStartedAt: Number(raw.overdueGraceStartedAt) || 0,
    horizonId: String(raw.horizonId || ''),
    createdBy: String(raw.createdBy || 'user+ai'),
    createdAt: Number(raw.createdAt) || Date.now(),
    updatedAt: Number(raw.updatedAt) || Date.now(),
    checkpoints: cps,
    energyLevel: normalizeEnergyLevel(raw.energyLevel),
    carryoverItems: cleanCarryoverItems(raw.carryoverItems ?? raw.carryover),
    dayBlocks: cleanDayBlocks(raw.dayBlocks),
  }
}

/**
 * 核验现有 App 是否满足当前学习/工作情景（AI 调用 plan_check_apps 或页面一键检测）。
 */
export function inspectScenarioApps({ scenario = '', declaredApps = [], phoneUsage = [], requestedAllow = [] } = {}) {
  const s = String(scenario || '').toLowerCase()
  const known = new Map()
  for (const a of Array.isArray(declaredApps) ? declaredApps : []) {
    if (a?.app) known.set(a.app, { app: a.app, label: a.label || a.app, role: a.role || 'tool' })
  }
  for (const u of Array.isArray(phoneUsage) ? phoneUsage : []) {
    if (u?.app && !known.has(u.app)) known.set(u.app, { app: u.app, label: u.label || u.app, role: u.fun ? 'fun' : 'tool' })
  }

  const allApps = [...known.values()]
  const notesApps = allApps.filter((x) => /(obsidian|notion|notes|wps|word|anki|marginnote|goodnotes)/i.test(`${x.app} ${x.label}`))
  const videoApps = allApps.filter((x) => /(bili|youtube|xuetang|icourse|study|mooc|browser)/i.test(`${x.app} ${x.label}`))

  const needsVideo = /(视频|网课|b站|bilibili|课程|讲座|公开课)/i.test(s)
  const needsNotes = /(笔记|提炼|体系|总结|写作|简历|复习|做题|刷题|考公)/i.test(s) || true

  const suggested = new Set(Array.isArray(requestedAllow) ? requestedAllow.filter(Boolean) : [])
  for (const n of notesApps.slice(0, 2)) suggested.add(n.app)
  if (needsVideo) {
    for (const v of videoApps.slice(0, 2)) suggested.add(v.app)
  }

  const gaps = []
  if (needsVideo && !videoApps.length) gaps.push('未发现常用视频/网课 App 近期记录（可直接从本机选择 B站/浏览器加入本局允许列表）')
  if (needsNotes && !notesApps.length) gaps.push('建议放行一个笔记或题库工具（如 Obsidian / WPS / DSH 内置题库）用来沉淀提炼输出')

  return {
    ok: true,
    scenario: String(scenario || '').slice(0, 120),
    sufficient: gaps.length === 0,
    gaps,
    recommendedAllow: [...suggested].map((pkg) => ({
      app: pkg,
      label: known.get(pkg)?.label || pkg,
    })),
    knownAppsCount: allApps.length,
  }
}

/**
 * 1 分钟自动升级检查：每次 /plan 或前端轮询调用，零静默卡死。
 * 返回 { contract, graceWarning: { checkpoint, remainSec } | null, triggerStrictNow: boolean, newlyEscalated: boolean }
 */
export function evaluateContractTick(rawContract, now = Date.now()) {
  const c = cleanPlanContract(rawContract)
  if (!c) return { contract: null, graceWarning: null, triggerStrictNow: false, newlyEscalated: false }

  let changed = false
  let newlyEscalated = false
  let graceWarning = null
  const cps = c.checkpoints.map((cp) => ({ ...cp }))

  for (const cp of cps) {
    if (cp.status === 'passed' || cp.dueAt <= 0) continue
    if (now < cp.dueAt) continue

    const overdueMs = now - cp.dueAt
    const graceMs = cp.graceMs || OVERDUE_GRACE_MS
    if (overdueMs < graceMs) {
      if (cp.status !== 'grace_warning') {
        cp.status = 'grace_warning'
        changed = true
      }
      const remainSec = Math.max(1, Math.ceil((graceMs - overdueMs) / 1000))
      if (!graceWarning || remainSec < graceWarning.remainSec) {
        graceWarning = { checkpointId: cp.id, title: cp.title, remainSec }
      }
    } else if (c.strictOnOverdue) {
      if (cp.status !== 'overdue_locked') {
        cp.status = 'overdue_locked'
        changed = true
        newlyEscalated = true
      }
    }
  }

  const anyOverdueLocked = cps.some((cp) => cp.status === 'overdue_locked')
  const triggerStrictNow = c.strictOnOverdue && anyOverdueLocked
  const next = {
    ...c,
    checkpoints: cps,
    strictEscalated: triggerStrictNow,
    ...(changed ? { updatedAt: now } : {}),
  }
  return {
    contract: changed ? next : c,
    changed,
    graceWarning,
    triggerStrictNow,
    newlyEscalated,
  }
}

/**
 * 评估“看视频提炼 / 交付物”是否满足高强度因果链门槛。
 * 若用户仅贴了一句空话或敷衍水字数，生成追问探针要求继续对线；若具备明确机制/结论链，放行或进入 AI 深挖确认。
 */
export function evaluateCheckpointSubmission(rawContract, { checkpointId, summary = '', aiConfirmed = false, now = Date.now() } = {}) {
  const c = cleanPlanContract(rawContract)
  if (!c) return { ok: false, error: '当前没有激活的计划契约' }
  const cps = c.checkpoints.map((cp) => ({ ...cp }))
  const target = cps.find((cp) => cp.id === String(checkpointId || '')) || cps.find((cp) => cp.status !== 'passed')
  if (!target) return { ok: false, error: '所有验收点已全部通过', contract: c }

  const cleanSum = String(summary || '').trim()
  if (cleanSum.length < 20 && !aiConfirmed) {
    return {
      ok: false,
      passed: false,
      needsInteractive: true,
      error: '提炼内容太短（<20字），请粘贴 AI 压缩后的核心结论/因果链，或直接点对线问答过关',
      probes: [
        '这段视频/材料真正解决的痛点或底层机制是什么？',
        '如果明天考场/面试官反问一个反例，你怎么用刚才学到的点回应？',
      ],
      contract: c,
    }
  }

  const hasStructure = /(因为|所以|导致|机制|核心|结论|步骤|注意|区别|对比|→|->|\d+\.)/.test(cleanSum) || cleanSum.length >= 60 || aiConfirmed
  if (!hasStructure) {
    target.submissionSummary = cleanSum.slice(0, 1000)
    target.probes = [
      `针对「${target.title}」：你写到了现象，但缺少因果逻辑链（为什么成立 / 何时失效）？请再补一句机制解释。`,
    ]
    return {
      ok: true,
      passed: false,
      needsInteractive: true,
      probes: target.probes,
      verdictNote: '进入高强度互动追问：请补充机制因果后确认过关',
      contract: { ...c, checkpoints: cps, updatedAt: now },
    }
  }

  target.status = 'passed'
  target.verifiedAt = now
  target.submissionSummary = cleanSum.slice(0, 1000)
  target.verdictNote = aiConfirmed ? 'AI 高强度互动验收通过' : '结构化因果提炼验收通过'
  const stillOverdue = cps.some((cp) => cp.status === 'overdue_locked')
  const next = {
    ...c,
    checkpoints: cps,
    strictEscalated: stillOverdue,
    updatedAt: now,
  }
  return {
    ok: true,
    passed: true,
    needsInteractive: false,
    checkpoint: target,
    contract: next,
  }
}

// ============================================================================
// V5 计划模态三态跃迁：1. 准备（精力/昨日结转/硬骨头）→ 2. 构建（时间盒）→ 3. 观看（24h 轴/偏航/验收）
// ============================================================================

export const ENERGY_LEVELS = Object.freeze(['low', 'mid', 'high'])
export const DAY_SLOTS = Object.freeze(['morning', 'lunch', 'afternoon', 'evening'])
export const BLOCK_LOCK_MODES = Object.freeze(['gentle', 'strict', 'verify'])
export const BLOCK_STATUSES = Object.freeze(['pending', 'running', 'done', 'skipped'])
export const VERIFY_STATUSES = Object.freeze(['none', 'armed', 'grace_warning', 'overdue_locked', 'submitted', 'passed', 'rejected', 'expired_breach'])
export const DAYBLOCK_SOURCES = Object.freeze(['user', 'carryover', 'wall_boss', 'gemini', 'gemini_custody', 'auto_kickoff', 'template'])
export const DAYBLOCK_EVIDENCE_KINDS = Object.freeze(['', 'subjective', 'quiz', 'bout_minutes', 'file', 'screenshots', 'gemini_receipt', 'app_minutes'])
export const DAYBLOCK_LOCK_CAP_MS = 60 * 60_000
export const LUNCH_BREAK_WINDOW = Object.freeze({ startMin: 12 * 60, endMin: 14 * 60 })

export const ENERGY_PROFILES = Object.freeze({
  low: Object.freeze({
    level: 'low',
    label: '低电量保底',
    recommendedBlocks: 2,
    blockMinutes: 30,
    defaultLockMode: 'gentle',
    hint: '低耗能保底：2 个温和短块 + 12:00–14:00 午休合法放风',
  }),
  mid: Object.freeze({
    level: 'mid',
    label: '标准推进',
    recommendedBlocks: 4,
    blockMinutes: 45,
    defaultLockMode: 'strict',
    hint: '标准节奏：4 个专注块 + 12:00–14:00 午休合法放风',
  }),
  high: Object.freeze({
    level: 'high',
    label: '满血冲刺',
    recommendedBlocks: 5,
    blockMinutes: 60,
    defaultLockMode: 'verify',
    hint: '冲刺节奏：5 个高密深水块 + 验收锁强交付',
  }),
})

const intNum = (v, d = 0) => (Number.isFinite(Number(v)) ? Math.round(Number(v)) : d)
const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v))

export function normalizeEnergyLevel(raw) {
  const s = String(raw || '').trim().toLowerCase()
  return ENERGY_LEVELS.includes(s) ? s : 'mid'
}

export function slotFromMinute(minute) {
  const m = clamp(intNum(minute, 540), 0, 1439)
  if (m < LUNCH_BREAK_WINDOW.startMin) return 'morning'
  if (m < LUNCH_BREAK_WINDOW.endMin) return 'lunch'
  if (m < 18 * 60) return 'afternoon'
  return 'evening'
}

function cleanAppsList(raw, fallback = ['md.obsidian', 'com.tencent.mm']) {
  if (!Array.isArray(raw)) return [...fallback]
  const clean = [...new Set(raw.map((x) => String(x || '').trim()).filter(Boolean))].slice(0, 24)
  return clean.length ? clean : [...fallback]
}

export function cleanCarryoverItem(raw, idx = 0) {
  if (typeof raw === 'string') {
    const title = raw.trim().slice(0, 100)
    if (!title) return null
    return {
      id: `carry-${idx + 1}`,
      title,
      deliverable: '补完昨日遗留交付',
      minutes: 45,
      lockMode: 'strict',
      slot: 'morning',
      allowedApps: ['md.obsidian', 'com.tencent.mm'],
      source: 'yesterday',
      moved: false,
      movedBlockId: '',
    }
  }
  if (!raw || typeof raw !== 'object') return null
  const title = String(raw.title ?? raw.task ?? '').trim().slice(0, 100)
  if (!title) return null
  const id = String(raw.id || `carry-${idx + 1}`).trim().slice(0, 60)
  const slot = DAY_SLOTS.includes(raw.slot) ? raw.slot : 'morning'
  const lockMode = BLOCK_LOCK_MODES.includes(raw.lockMode) ? raw.lockMode : 'strict'
  return {
    id,
    title,
    deliverable: String(raw.deliverable ?? raw.expectedDeliverable ?? '补完昨日遗留并给出可验产出').trim().slice(0, 160),
    minutes: clamp(intNum(raw.minutes ?? raw.durationMin, 45), 5, 240),
    lockMode,
    slot,
    allowedApps: cleanAppsList(raw.allowedApps),
    source: String(raw.source || 'yesterday').slice(0, 32),
    moved: Boolean(raw.moved),
    movedBlockId: String(raw.movedBlockId || '').slice(0, 60),
  }
}

export function cleanCarryoverItems(raw) {
  const list = Array.isArray(raw) ? raw : []
  const out = []
  const seen = new Set()
  for (let i = 0; i < list.length && out.length < 24; i += 1) {
    const item = cleanCarryoverItem(list[i], i)
    if (!item) continue
    if (seen.has(item.id)) item.id = `${item.id}-${out.length + 1}`
    seen.add(item.id)
    out.push(item)
  }
  return out
}

export function cleanDayBlock(raw, idx = 0) {
  if (!raw || typeof raw !== 'object') return null
  const title = String(raw.title || '').trim().slice(0, 120)
  if (!title) return null
  const id = String(raw.id || `blk-${idx + 1}`).trim().slice(0, 60)
  const startMin = clamp(intNum(raw.startMin, 9 * 60), 0, 1435)
  const rawDur = Number(raw.durationMin ?? raw.minutes)
  const defaultEnd = Number.isFinite(rawDur) && rawDur >= 5
    ? Math.min(1440, startMin + clamp(Math.round(rawDur), 5, 360))
    : Math.min(1440, startMin + 45)
  const endMin = clamp(intNum(raw.endMin, defaultEnd), startMin + 5, 1440)
  const slot = DAY_SLOTS.includes(raw.slot) ? raw.slot : slotFromMinute(startMin)
  const source = DAYBLOCK_SOURCES.includes(String(raw.source || '')) ? String(raw.source) : 'user'
  const lockedByBoss = raw.lockedByBoss === true || source === 'wall_boss' || source === 'gemini_custody' || source === 'auto_kickoff'
  const isBreak = !lockedByBoss && (raw.isBreak === true || (slot === 'lunch' && /午休|放风|休息/.test(title)))
  const lockMode = lockedByBoss
    ? 'verify'
    : (BLOCK_LOCK_MODES.includes(raw.lockMode) ? raw.lockMode : (isBreak ? 'gentle' : 'strict'))
  const status = BLOCK_STATUSES.includes(raw.status) ? raw.status : 'pending'
  const allowedApps = cleanAppsList(raw.allowedApps)
  const rawVerify = String(raw.verifyStatus || '')
  const verifyStatus = VERIFY_STATUSES.includes(rawVerify)
    ? rawVerify
    : (status === 'done'
        ? 'passed'
        : ((lockMode === 'verify' || lockedByBoss) && !isBreak ? 'armed' : 'none'))
  const evidenceKind = DAYBLOCK_EVIDENCE_KINDS.includes(String(raw.evidenceKind || '')) ? String(raw.evidenceKind || '') : 'subjective'
  const evidenceFloor = Math.max(0, intNum(raw.evidenceFloor, 0))
  const sealId = String(raw.sealId || '').trim().slice(0, 64)
  const submissionText = String(raw.submissionText || '').trim().slice(0, 1200)
  const submittedAt = Math.max(0, Number(raw.submittedAt) || 0)
  const judgeVerdict = raw.judgeVerdict === 'pass' ? 'pass' : (raw.judgeVerdict === 'reject' ? 'reject' : '')
  const judgeNote = String(raw.judgeNote || '').trim().slice(0, 400)
  const judgedBy = ['wall_boss', 'code', 'gemini', 'auto_seal', ''].includes(String(raw.judgedBy || '')) ? String(raw.judgedBy || '') : ''
  const judgedAt = Math.max(0, Number(raw.judgedAt) || 0)
  const overdueLockUntil = Math.max(0, Number(raw.overdueLockUntil) || 0)
  const rewardPaidMin = Math.max(0, intNum(raw.rewardPaidMin, 0))
  const custodyId = String(raw.custodyId || '').trim().slice(0, 64)
  const adaptedFrom = String(raw.adaptedFrom || '').trim().slice(0, 64)

  return {
    id,
    slot,
    startMin,
    endMin,
    title,
    deliverable: String(raw.deliverable || (isBreak ? '到点收心回到工位' : '提交可见产出或通过验收')).trim().slice(0, 180),
    lockMode,
    allowedApps,
    status,
    isBreak,
    source,
    lockedByBoss,
    verifyStatus,
    evidenceKind,
    evidenceFloor,
    sealId,
    submissionText,
    submittedAt,
    judgeVerdict,
    judgeNote,
    judgedBy,
    judgedAt,
    overdueLockUntil,
    rewardPaidMin,
    ...(custodyId ? { custodyId } : {}),
    ...(adaptedFrom ? { adaptedFrom } : {}),
    horizonId: String(raw.horizonId || '').slice(0, 64),
    checkpointId: String(raw.checkpointId || '').slice(0, 64),
    updatedAt: Number(raw.updatedAt) || 0,
  }
}

export function cleanDayBlocks(raw) {
  const list = Array.isArray(raw) ? raw : []
  const out = []
  const seen = new Set()
  for (let i = 0; i < list.length && out.length < 32; i += 1) {
    const blk = cleanDayBlock(list[i], i)
    if (!blk) continue
    if (seen.has(blk.id)) blk.id = `${blk.id}-${out.length + 1}`
    seen.add(blk.id)
    out.push(blk)
  }
  return out.sort((a, b) => (a.startMin - b.startMin) || (a.endMin - b.endMin))
}

/**
 * 统计全天时间块：区分真实专注块 vs 午休合法放风块，计算预计专注分钟与娱乐预算对比。
 */
export function computeDayPlanStats(blocksRaw, { funBudgetMin = 30, energyLevel = 'mid' } = {}) {
  const blocks = cleanDayBlocks(blocksRaw)
  const level = normalizeEnergyLevel(energyLevel)
  const budget = Math.max(0, intNum(funBudgetMin, 30))

  let totalFocusMin = 0
  let completedFocusMin = 0
  let runningFocusMin = 0
  let remainingFocusMin = 0
  let lunchBreakMin = 0
  let focusCount = 0
  let doneCount = 0
  let skippedCount = 0
  let runningCount = 0

  for (const b of blocks) {
    const mins = Math.max(0, b.endMin - b.startMin)
    if (b.isBreak) {
      if (b.status !== 'skipped') lunchBreakMin += mins
      continue
    }
    if (b.status === 'skipped') {
      skippedCount += 1
      continue
    }
    focusCount += 1
    totalFocusMin += mins
    if (b.status === 'done') {
      doneCount += 1
      completedFocusMin += mins
    } else if (b.status === 'running') {
      runningCount += 1
      runningFocusMin += mins
      remainingFocusMin += mins
    } else {
      remainingFocusMin += mins
    }
  }

  return {
    totalFocusMin,
    completedFocusMin,
    runningFocusMin,
    remainingFocusMin,
    lunchBreakMin,
    funBudgetMin: budget,
    focusCount,
    doneCount,
    skippedCount,
    runningCount,
    focusToFunRatio: budget > 0 ? Number((totalFocusMin / budget).toFixed(2)) : null,
    overBudgetWarning: totalFocusMin === 0 && budget > 0,
    energyLevel: level,
    energyProfile: ENERGY_PROFILES[level],
  }
}

/**
 * 根据精力档位生成默认一日推荐时间盒（自动把 12:00–14:00 标注为合法午休放风块）。
 */
export function buildDefaultDayPlan({
  energyLevel = 'mid',
  carryoverItems = [],
  funBudgetMin = 30,
  allowedApps = ['md.obsidian', 'com.tencent.mm'],
  fillCarryover = false,
  now = Date.now(),
} = {}) {
  const level = normalizeEnergyLevel(energyLevel)
  const apps = cleanAppsList(allowedApps)
  const carries = cleanCarryoverItems(carryoverItems)

  const lunchBlock = {
    id: 'blk-lunch-break',
    slot: 'lunch',
    startMin: LUNCH_BREAK_WINDOW.startMin,
    endMin: LUNCH_BREAK_WINDOW.endMin,
    title: '午休合法放风（12:00–14:00 自动免锁）',
    deliverable: '14:00 准时回到工位启动下午块',
    lockMode: 'gentle',
    allowedApps: [...apps],
    status: 'pending',
    isBreak: true,
    updatedAt: now,
  }

  const templates = {
    low: [
      { id: 'blk-low-1', slot: 'morning', startMin: 9 * 60 + 30, endMin: 10 * 60, title: '上午最小启动块（保底破冰）', deliverable: '写下今天唯一核心目标的物理文件/题量开头', lockMode: 'gentle' },
      lunchBlock,
      { id: 'blk-low-2', slot: 'afternoon', startMin: 15 * 60, endMin: 15 * 60 + 35, title: '下午稳态收尾块', deliverable: '完成最小可验收进度并落账', lockMode: 'gentle' },
    ],
    mid: [
      { id: 'blk-mid-1', slot: 'morning', startMin: 9 * 60, endMin: 9 * 60 + 45, title: '上午硬骨头攻坚（第一局）', deliverable: '提交阶段性笔记/题量/代码产出', lockMode: 'strict' },
      { id: 'blk-mid-2', slot: 'morning', startMin: 10 * 60, endMin: 10 * 60 + 45, title: '上午主线推进（第二局）', deliverable: '完成本块目标并记录断点', lockMode: 'strict' },
      lunchBlock,
      { id: 'blk-mid-3', slot: 'afternoon', startMin: 14 * 60 + 30, endMin: 15 * 60 + 15, title: '下午专项深化块', deliverable: '输出错因规则或岗位投递结果', lockMode: 'strict' },
      { id: 'blk-mid-4', slot: 'evening', startMin: 19 * 60 + 30, endMin: 20 * 60 + 15, title: '晚间复盘与验收收口', deliverable: '压成 1 张因果卡或明日题库卡', lockMode: 'verify' },
    ],
    high: [
      { id: 'blk-high-1', slot: 'morning', startMin: 8 * 60 + 45, endMin: 9 * 60 + 45, title: '晨间头号硬骨头（深水验收块）', deliverable: '交硬证据或高强度追问过关', lockMode: 'verify' },
      { id: 'blk-high-2', slot: 'morning', startMin: 10 * 60, endMin: 11 * 60, title: '上午第二深水攻坚块', deliverable: '完成整套训练/核心文稿段落', lockMode: 'strict' },
      lunchBlock,
      { id: 'blk-high-3', slot: 'afternoon', startMin: 14 * 60 + 30, endMin: 15 * 60 + 30, title: '下午主线加速与高强度输出', deliverable: '交物理成果并核对验收点', lockMode: 'verify' },
      { id: 'blk-high-4', slot: 'afternoon', startMin: 16 * 60, endMin: 17 * 60, title: '下午专项巩固与弱项清零', deliverable: '解决 2 个卡点并归档笔记', lockMode: 'strict' },
      { id: 'blk-high-5', slot: 'evening', startMin: 19 * 60 + 30, endMin: 20 * 60 + 15, title: '晚间知识卡压缩与明日封印', deliverable: '压成题库卡并封好明日菜单', lockMode: 'verify' },
    ],
  }

  let rawBlocks = templates[level].map((b) => ({
    ...b,
    allowedApps: b.allowedApps || [...apps],
    status: 'pending',
    isBreak: Boolean(b.isBreak),
    updatedAt: now,
  }))

  let updatedCarries = carries
  if (fillCarryover && carries.length) {
    let ci = 0
    updatedCarries = carries.map((c) => ({ ...c }))
    rawBlocks = rawBlocks.map((b) => {
      if (b.isBreak) return b
      while (ci < updatedCarries.length && updatedCarries[ci].moved) ci += 1
      if (ci >= updatedCarries.length) return b
      const c = updatedCarries[ci]
      updatedCarries[ci] = { ...c, moved: true, movedBlockId: b.id }
      ci += 1
      return {
        ...b,
        title: `结转：${c.title}`,
        deliverable: c.deliverable || b.deliverable,
        allowedApps: c.allowedApps?.length ? c.allowedApps : b.allowedApps,
      }
    })
  }

  const dayBlocks = cleanDayBlocks(rawBlocks)
  return {
    ok: true,
    energyLevel: level,
    carryoverItems: updatedCarries,
    dayBlocks,
    stats: computeDayPlanStats(dayBlocks, { funBudgetMin, energyLevel: level }),
  }
}

export function upsertDayBlock(blocksRaw, input = {}, { now = Date.now() } = {}) {
  const existing = cleanDayBlocks(blocksRaw)
  const rawObj = input && typeof input === 'object' ? input : {}
  const rawId = String(rawObj.id || '').trim()
  const prev = rawId ? existing.find((b) => b.id === rawId) : null
  const hasExplicitEnd = Object.prototype.hasOwnProperty.call(rawObj, 'endMin') && rawObj.endMin != null
  const hasExplicitDuration = rawObj.durationMin != null || rawObj.minutes != null
  const base = {
    ...(prev || {}),
    ...rawObj,
    id: rawId || `blk-${now.toString(36).slice(-5)}-${existing.length + 1}`,
    updatedAt: now,
  }
  if (prev?.lockedByBoss && prev.status !== 'done') {
    base.lockedByBoss = true
    base.source = prev.source || 'wall_boss'
    base.lockMode = 'verify'
    if (base.status === 'skipped') base.status = prev.status
  }
  if (base.lockMode === 'verify' && (!prev || prev.lockMode !== 'verify') && (!base.verifyStatus || base.verifyStatus === 'none')) {
    base.verifyStatus = base.status === 'done' ? 'passed' : 'armed'
  } else if (rawObj.lockMode && rawObj.lockMode !== 'verify' && !base.lockedByBoss) {
    base.verifyStatus = 'none'
    base.overdueLockUntil = 0
  }
  if (!hasExplicitEnd && (hasExplicitDuration || (prev && rawObj.startMin != null))) {
    delete base.endMin
    if (!hasExplicitDuration && prev) {
      base.durationMin = Math.max(5, prev.endMin - prev.startMin)
    }
  }
  const merged = cleanDayBlock(base, existing.length)

  if (!merged) {
    return { ok: false, error: '时间块至少需要有效标题', block: null, dayBlocks: existing }
  }
  const replaced = prev
    ? existing.map((b) => (b.id === merged.id ? merged : b))
    : [...existing, merged]
  const dayBlocks = cleanDayBlocks(replaced)
  return { ok: true, block: dayBlocks.find((b) => b.id === merged.id) || merged, dayBlocks }
}

export function removeDayBlock(blocksRaw, blockId, { force = false } = {}) {
  const existing = cleanDayBlocks(blocksRaw)
  const id = String(blockId || '').trim()
  const removed = existing.find((b) => b.id === id) || null
  if (!removed) return { ok: false, error: '未找到指定时间块', removed: null, dayBlocks: existing }
  if (removed.lockedByBoss && removed.status !== 'done' && !force) {
    return {
      ok: false,
      error: 'boss-directive-locked',
      text: '主管墙下达的止损硬指令不可直接删除，请交付验收过关',
      removed: null,
      dayBlocks: existing,
    }
  }
  return { ok: true, removed, dayBlocks: existing.filter((b) => b.id !== id) }
}

export function setDayBlockStatus(blocksRaw, blockId, status, { allowMultipleRunning = false, force = false, fromJudge = false, now = Date.now() } = {}) {
  const existing = cleanDayBlocks(blocksRaw)
  const id = String(blockId || '').trim()
  const nextStatus = BLOCK_STATUSES.includes(status) ? status : ''
  if (!nextStatus) {
    return { ok: false, error: `无效块状态：${status}`, block: null, dayBlocks: existing }
  }
  const target = existing.find((b) => b.id === id)
  if (!target) {
    return { ok: false, error: '未找到指定时间块', block: null, dayBlocks: existing }
  }
  if (target.lockedByBoss && nextStatus === 'skipped' && !force) {
    return {
      ok: false,
      error: 'boss-directive-locked',
      text: '主管墙下达的止损硬指令不可跳过，请交付验收过关',
      block: null,
      dayBlocks: existing,
    }
  }
  if ((target.lockMode === 'verify' || target.lockedByBoss) && !target.isBreak && nextStatus === 'done' && !fromJudge && !force && target.verifyStatus !== 'passed') {
    return {
      ok: false,
      error: 'needs-verification',
      text: '验收块须提交真实交付物并经终审通过后才算完成',
      block: null,
      dayBlocks: existing,
    }
  }
  const next = existing.map((b) => {
    if (b.id === id) return { ...b, status: nextStatus, updatedAt: now }
    if (nextStatus === 'running' && !allowMultipleRunning && b.status === 'running') {
      return { ...b, status: 'pending', updatedAt: now }
    }
    return b
  })
  return { ok: true, block: next.find((b) => b.id === id), dayBlocks: next }
}

export function promoteCarryoverToBlock(stateRaw = {}, carryoverId, opts = {}) {
  const now = Number(opts.now) || Date.now()
  const carries = cleanCarryoverItems(stateRaw?.carryoverItems ?? stateRaw?.carryover)
  const blocks = cleanDayBlocks(stateRaw?.dayBlocks)
  const id = String(carryoverId || '').trim()
  const item = carries.find((c) => c.id === id)
  if (!item) {
    return { ok: false, error: '未找到昨日未完结条目', block: null, carryoverItems: carries, dayBlocks: blocks }
  }

  const slot = DAY_SLOTS.includes(opts.slot) ? opts.slot : (item.slot || 'morning')
  const durationMin = clamp(intNum(opts.durationMin ?? item.minutes, 45), 5, 240)
  const slotDefaults = { morning: 9 * 60, lunch: 12 * 60, afternoon: 14 * 60 + 30, evening: 19 * 60 + 30 }
  let startMin = opts.startMin !== undefined ? clamp(intNum(opts.startMin, slotDefaults[slot]), 0, 1435) : slotDefaults[slot]

  if (opts.startMin === undefined) {
    const inSlot = blocks.filter((b) => b.slot === slot && !b.isBreak)
    if (inSlot.length) {
      const lastEnd = Math.max(...inSlot.map((b) => b.endMin))
      startMin = Math.min(1435 - durationMin, lastEnd + 15)
    }
  }
  // 默认不挤进 12:00-14:00 午休放风区（除非显式指明 slot === 'lunch'）
  if (slot !== 'lunch' && startMin >= LUNCH_BREAK_WINDOW.startMin && startMin < LUNCH_BREAK_WINDOW.endMin) {
    startMin = LUNCH_BREAK_WINDOW.endMin + 30
  }
  const endMin = Math.min(1440, startMin + durationMin)
  const blockId = `blk-carry-${item.id.slice(0, 18)}-${now.toString(36).slice(-3)}`
  const up = upsertDayBlock(blocks, {
    id: blockId,
    slot: opts.slot || slotFromMinute(startMin),
    startMin,
    endMin,
    title: item.title,
    deliverable: item.deliverable,
    lockMode: opts.lockMode || item.lockMode || 'strict',
    allowedApps: opts.allowedApps || item.allowedApps,
    status: 'pending',
    isBreak: false,
  }, { now })

  const nextCarries = carries.map((c) => (c.id === id ? { ...c, moved: true, movedBlockId: up.block.id } : c))
  return {
    ok: up.ok,
    block: up.block,
    carryoverItems: nextCarries,
    dayBlocks: up.dayBlocks,
    stats: computeDayPlanStats(up.dayBlocks, {
      funBudgetMin: opts.funBudgetMin ?? 30,
      energyLevel: stateRaw?.energyLevel || 'mid',
    }),
  }
}

/**
 * 观看与执行态：计算当前时刻正在进行/命中的时间块、剩余倒计时、偏航警告。
 */
export function detectCurrentBlockDeviation(blocksRaw, {
  minuteOfDay = 0,
  currentApp = '',
  currentAppFun = false,
  boutRunning = false,
} = {}) {
  const blocks = cleanDayBlocks(blocksRaw)
  const m = clamp(intNum(minuteOfDay, 0), 0, 1439)
  const runningBlock = blocks.find((b) => b.status === 'running') || null
  const scheduledNow = blocks.find((b) => b.status !== 'skipped' && b.status !== 'done' && m >= b.startMin && m < b.endMin) || null
  const activeBlock = runningBlock || scheduledNow
  const nextBlock = blocks.find((b) => b.status === 'pending' && b.startMin > m && !b.isBreak) || null

  if (!activeBlock) {
    const overduePending = blocks.filter((b) => !b.isBreak && b.status === 'pending' && m >= b.endMin)
    return {
      activeBlock: null,
      nextBlock,
      remainMin: 0,
      isLateStart: false,
      isOffTrack: Boolean(currentAppFun && overduePending.length > 0),
      overdueCount: overduePending.length,
      warningText: overduePending.length > 0
        ? `已有 ${overduePending.length} 个未开启块过点，建议挑 1 个立即开干或标记跳过`
        : '',
    }
  }

  const remainMin = Math.max(0, activeBlock.endMin - m)
  const elapsedInSlot = Math.max(0, m - activeBlock.startMin)
  const isLateStart = !activeBlock.isBreak && activeBlock.status === 'pending' && elapsedInSlot >= 5 && !boutRunning
  const pkg = String(currentApp || '').trim()
  const appAllowed = !pkg || activeBlock.allowedApps.includes(pkg)
  const isOffTrack = !activeBlock.isBreak && (Boolean(currentAppFun) || (pkg !== '' && !appAllowed))

  let warningText = ''
  if (isOffTrack) {
    warningText = `偏航警告：当前处于「${activeBlock.title}」时段，前台 ${pkg || '娱乐应用'} 不在本块白名单内`
  } else if (isLateStart) {
    warningText = `当前块「${activeBlock.title}」已开始 ${elapsedInSlot} 分钟尚未点开干`
  }

  return {
    activeBlock,
    nextBlock,
    remainMin,
    isLateStart,
    isOffTrack,
    overdueCount: blocks.filter((b) => !b.isBreak && b.status === 'pending' && m >= b.endMin).length,
    warningText,
  }
}

export function buildDayPlanGeminiPrompt({
  energyLevel = 'mid',
  carryoverItems = [],
  dayBlocks = [],
  funBudgetMin = 30,
  horizons = null,
} = {}) {
  const level = normalizeEnergyLevel(energyLevel)
  const profile = ENERGY_PROFILES[level]
  const carries = cleanCarryoverItems(carryoverItems).filter((c) => !c.moved)
  const blocks = cleanDayBlocks(dayBlocks)
  const stats = computeDayPlanStats(blocks, { funBudgetMin, energyLevel: level })

  const lines = [
    '【今日硬骨头共脑规划 · 直接给出 1–3 件主线硬任务】',
    `- 今日精力档位：${profile.label}（${level}），建议块数 ${profile.recommendedBlocks} 块 × ${profile.blockMinutes} 分钟，默认锁强度 ${profile.defaultLockMode}`,
    `- 今日娱乐余额预算：${stats.funBudgetMin} 分钟；当前已排专注块总计：${stats.totalFocusMin} 分钟（${stats.focusCount} 块）`,
  ]
  if (carries.length) {
    lines.push(`- 昨日未完结待处理（${carries.length} 项）：${carries.slice(0, 5).map((c) => c.title).join('；')}`)
  }
  const med = Array.isArray(horizons?.medium) ? horizons.medium : []
  if (med.length) {
    lines.push(`- 中期主线战役：${med.slice(0, 3).map((m) => m.title).filter(Boolean).join('；')}`)
  }
  lines.push(
    '请直接帮我：',
    '1. 从上面选出今天最值得打掉的 1–3 件硬骨头（不要超过 3 件，严禁放水凑数）；',
    '2. 每件写清：建议放进上午/下午哪个时段、持续多少分钟、用什么锁强度（gentle/strict/verify）、允许哪几个 App、到点交什么物理交付物；',
    '3. 如果我今天选的是「低电量保底」，只排最小可启动动作，别一口气列满全天。',
  )
  return lines.join('\n')
}

export const DEFAULT_KICKOFF_MINUTES = Object.freeze([9 * 60 + 15, 14 * 60 + 45])

const hmStrOf = (m) => {
  const c = clamp(intNum(m, 0), 0, 1439)
  return `${String(Math.floor(c / 60)).padStart(2, '0')}:${String(c % 60).padStart(2, '0')}`
}

export function cleanCustodyPolicy(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const rawSlots = Array.isArray(s.kickoffMinutes) ? s.kickoffMinutes : DEFAULT_KICKOFF_MINUTES
  const kickoffMinutes = [...new Set(
    rawSlots
      .map((x) => intNum(x, -1))
      .filter((m) => m >= 7 * 60 && m <= 21 * 60 + 30),
  )].sort((a, b) => a - b).slice(0, 4)
  return {
    autoKickoffEnabled: Boolean(s.autoKickoffEnabled),
    kickoffMinutes: kickoffMinutes.length ? kickoffMinutes : [...DEFAULT_KICKOFF_MINUTES],
    kickoffDurationMin: clamp(intNum(s.kickoffDurationMin, 25), 15, 60),
    adaptiveSplitEnabled: s.adaptiveSplitEnabled !== false,
  }
}

export function cleanCustody(raw, dayBlocks = [], now = Date.now()) {
  if (!raw || typeof raw !== 'object') return null
  const id = String(raw.id || '').trim().slice(0, 64)
  if (!id) return null
  const blockIds = [...new Set((Array.isArray(raw.blockIds) ? raw.blockIds : []).map((x) => String(x || '').trim()).filter(Boolean))].slice(0, 12)
  const idSet = new Set(blockIds)
  const hasOpen = (Array.isArray(dayBlocks) ? dayBlocks : []).some((b) => idSet.has(b.id) && !b.isBreak && b.status !== 'done' && b.status !== 'skipped')
  const untilAt = Math.max(0, Number(raw.untilAt) || 0)
  const active = raw.active === true && (untilAt <= 0 || now < untilAt) && hasOpen
  const rebriefLog = (Array.isArray(raw.rebriefLog) ? raw.rebriefLog : [])
    .filter((x) => x && typeof x === 'object' && String(x.reason || '').trim())
    .slice(-12)
    .map((x) => ({
      at: Math.max(0, Number(x.at) || 0),
      reason: String(x.reason || '').trim().slice(0, 240),
      issuedBy: String(x.issuedBy || 'wall_boss').slice(0, 32),
    }))
  const untilMin = clamp(intNum(raw.untilMin, 0), 0, 1440)
  return {
    active,
    id,
    title: String(raw.title || '托管冲刺轨').trim().slice(0, 100),
    issuedBy: ['wall_boss', 'gemini', 'auto_kickoff', 'user'].includes(String(raw.issuedBy || '')) ? String(raw.issuedBy) : 'wall_boss',
    startedAt: Math.max(0, Number(raw.startedAt) || 0),
    untilAt,
    untilMin,
    untilHm: untilMin >= 1440 ? '24:00' : (untilMin > 0 ? hmStrOf(untilMin) : ''),
    blockIds,
    rebriefCount: Math.max(0, intNum(raw.rebriefCount, rebriefLog.length)),
    rebriefLog,
  }
}

export function isCustodyActive(state, now = Date.now()) {
  if (!state || typeof state !== 'object') return false
  return Boolean(cleanCustody(state.custody, state.dayBlocks, now)?.active)
}

export function cleanDayPlanState(raw, { day = '', now = Date.now() } = {}) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const energyLevel = normalizeEnergyLevel(s.energyLevel)
  const carryoverItems = cleanCarryoverItems(s.carryoverItems ?? s.carryover)
  const dayBlocks = cleanDayBlocks(s.dayBlocks)
  const custodyPolicy = cleanCustodyPolicy(s.custodyPolicy)
  const custody = cleanCustody(s.custody, dayBlocks, now)
  const firedKickoffSlots = [...new Set((Array.isArray(s.firedKickoffSlots) ? s.firedKickoffSlots : []).map((x) => String(x || '').trim()).filter(Boolean))].slice(0, 10)
  const adaptiveLog = (Array.isArray(s.adaptiveLog) ? s.adaptiveLog : [])
    .filter((x) => x && typeof x === 'object' && x.splitBlockId)
    .slice(-20)
    .map((x) => ({
      at: Math.max(0, Number(x.at) || 0),
      triggerBlockId: String(x.triggerBlockId || '').slice(0, 64),
      splitBlockId: String(x.splitBlockId || '').slice(0, 64),
      newBlockIds: Array.isArray(x.newBlockIds) ? x.newBlockIds.map((k) => String(k || '').slice(0, 64)).slice(0, 4) : [],
      reason: String(x.reason || '').slice(0, 48),
    }))
  return {
    v: 1,
    day: String(s.day || day || ''),
    energyLevel,
    carryoverItems,
    dayBlocks,
    custodyPolicy,
    custody,
    firedKickoffSlots,
    adaptiveLog,
    updatedAt: Number(s.updatedAt) || now,
  }
}

export function applyDayPlanAction(rawState, body = {}, { now = Date.now(), day = '', funBudgetMin = 30 } = {}) {
  const cur = cleanDayPlanState(rawState, { day, now })
  const action = String(body?.action || 'get_day_plan')
  const custodyLocked = isCustodyActive(cur, now) && body?.allowSystemMutation !== true
  const custodyIdSet = new Set(cur.custody?.blockIds || [])

  if (action === 'get_day_plan' || action === 'status') {
    return {
      ok: true,
      ...cur,
      stats: computeDayPlanStats(cur.dayBlocks, { funBudgetMin, energyLevel: cur.energyLevel }),
    }
  }
  if (action === 'set_custody_policy') {
    const out = setCustodyPolicy(cur, body, { now })
    return {
      ok: true,
      ...out.state,
      stats: computeDayPlanStats(out.state.dayBlocks, { funBudgetMin, energyLevel: out.state.energyLevel }),
    }
  }
  if (action === 'set_energy') {
    const energyLevel = normalizeEnergyLevel(body?.energyLevel)
    const next = { ...cur, energyLevel, updatedAt: now }
    return {
      ok: true,
      ...next,
      stats: computeDayPlanStats(next.dayBlocks, { funBudgetMin, energyLevel }),
    }
  }
  if (action === 'generate_default') {
    if (custodyLocked) {
      return {
        ok: false,
        error: 'custody-locked',
        text: '当前处于托管冲刺期，不可重建覆盖计划；如遇突发情况请通过主管墙或 Gemini 发起 Re-brief 重签',
        ...cur,
        stats: computeDayPlanStats(cur.dayBlocks, { funBudgetMin, energyLevel: cur.energyLevel }),
      }
    }
    const built = buildDefaultDayPlan({
      energyLevel: body?.energyLevel ?? cur.energyLevel,
      carryoverItems: body?.carryoverItems ?? cur.carryoverItems,
      funBudgetMin,
      allowedApps: body?.allowedApps,
      fillCarryover: Boolean(body?.fillCarryover),
      now,
    })
    return {
      ok: true,
      v: 1,
      day: cur.day,
      energyLevel: built.energyLevel,
      carryoverItems: built.carryoverItems,
      dayBlocks: built.dayBlocks,
      custodyPolicy: cur.custodyPolicy,
      custody: cur.custody,
      firedKickoffSlots: cur.firedKickoffSlots,
      adaptiveLog: cur.adaptiveLog,
      stats: built.stats,
      updatedAt: now,
    }
  }
  if (action === 'upsert_block') {
    const rawBlock = body?.block || body
    const blockId = String(rawBlock?.id || '').trim()
    const prevBlock = blockId ? cur.dayBlocks.find((b) => b.id === blockId) : null
    if (custodyLocked && prevBlock && (prevBlock.lockedByBoss || custodyIdSet.has(prevBlock.id)) && prevBlock.status !== 'done') {
      const requestedEnd = rawBlock?.endMin != null ? intNum(rawBlock.endMin, prevBlock.endMin) : null
      const requestedDur = rawBlock?.durationMin ?? rawBlock?.minutes
      const nextEnd = requestedEnd ?? (requestedDur != null ? prevBlock.startMin + intNum(requestedDur, prevBlock.endMin - prevBlock.startMin) : prevBlock.endMin)
      if ((rawBlock?.lockMode && rawBlock.lockMode !== 'verify') || rawBlock?.status === 'skipped' || nextEnd > prevBlock.endMin) {
        return {
          ok: false,
          error: 'custody-locked',
          text: '托管冲刺期内不可降级验收锁、跳过或延后截止时间；如需重排请发起 Re-brief',
          block: prevBlock,
          ...cur,
          stats: computeDayPlanStats(cur.dayBlocks, { funBudgetMin, energyLevel: cur.energyLevel }),
        }
      }
    }
    const up = upsertDayBlock(cur.dayBlocks, rawBlock, { now })
    const next = { ...cur, dayBlocks: up.dayBlocks, updatedAt: now }
    return {
      ok: up.ok,
      error: up.error,
      block: up.block,
      ...next,
      stats: computeDayPlanStats(next.dayBlocks, { funBudgetMin, energyLevel: next.energyLevel }),
    }
  }
  if (action === 'delete_block') {
    const targetId = String(body?.blockId ?? body?.id ?? '').trim()
    if (custodyLocked && custodyIdSet.has(targetId)) {
      return {
        ok: false,
        error: 'custody-locked',
        text: '托管冲刺期内的时间块不可自行删除；请先交付或通过主管/Gemini 发起 Re-brief',
        removed: null,
        ...cur,
        stats: computeDayPlanStats(cur.dayBlocks, { funBudgetMin, energyLevel: cur.energyLevel }),
      }
    }
    const del = removeDayBlock(cur.dayBlocks, targetId, { force: Boolean(body?.force) })
    const next = { ...cur, dayBlocks: del.dayBlocks, updatedAt: now }
    return {
      ok: del.ok,
      error: del.error,
      ...(del.text ? { text: del.text } : {}),
      removed: del.removed,
      ...next,
      stats: computeDayPlanStats(next.dayBlocks, { funBudgetMin, energyLevel: next.energyLevel }),
    }
  }
  if (action === 'set_block_status') {
    const targetId = String(body?.blockId ?? body?.id ?? '').trim()
    if (custodyLocked && custodyIdSet.has(targetId) && body?.status === 'skipped') {
      return {
        ok: false,
        error: 'custody-locked',
        text: '托管冲刺期内不可跳过验收块，请先交付或走 Re-brief',
        block: null,
        ...cur,
        stats: computeDayPlanStats(cur.dayBlocks, { funBudgetMin, energyLevel: cur.energyLevel }),
      }
    }
    const st = setDayBlockStatus(cur.dayBlocks, targetId, body?.status, {
      allowMultipleRunning: Boolean(body?.allowMultipleRunning),
      force: Boolean(body?.force),
      now,
    })
    const next = { ...cur, dayBlocks: st.dayBlocks, updatedAt: now }
    return {
      ok: st.ok,
      error: st.error,
      ...(st.text ? { text: st.text } : {}),
      block: st.block,
      ...next,
      stats: computeDayPlanStats(next.dayBlocks, { funBudgetMin, energyLevel: next.energyLevel }),
    }
  }
  if (action === 'move_carryover') {
    const mv = promoteCarryoverToBlock(cur, body?.carryoverId ?? body?.id, {
      slot: body?.slot,
      startMin: body?.startMin,
      durationMin: body?.durationMin,
      lockMode: body?.lockMode,
      allowedApps: body?.allowedApps,
      funBudgetMin,
      now,
    })
    const next = { ...cur, carryoverItems: mv.carryoverItems, dayBlocks: mv.dayBlocks, updatedAt: now }
    return {
      ok: mv.ok,
      error: mv.error,
      block: mv.block,
      ...next,
      stats: mv.stats || computeDayPlanStats(next.dayBlocks, { funBudgetMin, energyLevel: next.energyLevel }),
    }
  }
  if (action === 'set_day_plan') {
    const rawIn = body?.dayPlan && typeof body.dayPlan === 'object' ? body.dayPlan : body
    const parsedIn = cleanDayPlanState({
      ...cur,
      ...rawIn,
      ...(body?.allowSystemMutation === true ? {} : {
        custodyPolicy: cur.custodyPolicy,
        custody: cur.custody,
        firedKickoffSlots: cur.firedKickoffSlots,
        adaptiveLog: cur.adaptiveLog,
      }),
      updatedAt: now,
    }, { day: cur.day, now })
    const incomingIds = new Set(parsedIn.dayBlocks.map((b) => b.id))
    const preservedBoss = cur.dayBlocks.filter(
      (b) => (b.lockedByBoss || (custodyLocked && custodyIdSet.has(b.id))) && b.status !== 'done' && !incomingIds.has(b.id),
    )
    const mergedBlocks = preservedBoss.length ? cleanDayBlocks([...parsedIn.dayBlocks, ...preservedBoss]) : parsedIn.dayBlocks
    const next = cleanDayPlanState({ ...parsedIn, dayBlocks: mergedBlocks }, { day: cur.day, now })
    return {
      ok: true,
      ...next,
      stats: computeDayPlanStats(next.dayBlocks, { funBudgetMin, energyLevel: next.energyLevel }),
    }
  }
  return {
    ok: false,
    error: `未知 dayPlan action: ${action}`,
    ...cur,
    stats: computeDayPlanStats(cur.dayBlocks, { funBudgetMin, energyLevel: cur.energyLevel }),
  }
}

/**
 * 计算今日时间块中与「验收锁（verify / 主管指令）」相关的汇总指标。
 */
export function summarizeDayBlocksVerify(blocksRaw, { minuteOfDay = 0 } = {}) {
  const blocks = cleanDayBlocks(blocksRaw)
  const m = clamp(intNum(minuteOfDay, 0), 0, 1439)
  const verifyBlocks = blocks.filter((b) => !b.isBreak && b.status !== 'skipped' && (b.lockMode === 'verify' || b.lockedByBoss === true))
  const totalVerify = verifyBlocks.length
  const passedVerify = verifyBlocks.filter((b) => b.status === 'done' || b.verifyStatus === 'passed').length
  const overdueBlocks = verifyBlocks.filter((b) => b.status !== 'done' && (b.verifyStatus === 'overdue_locked' || (b.overdueLockUntil > 0 && b.verifyStatus !== 'expired_breach')))
  const pendingReviewBlocks = verifyBlocks.filter((b) => b.status !== 'done' && (b.verifyStatus === 'submitted' || b.verifyStatus === 'rejected'))
  const nextCandidate = verifyBlocks
    .filter((b) => b.status !== 'done' && b.verifyStatus !== 'expired_breach')
    .sort((a, b) => a.endMin - b.endMin)[0] || null
  const nextDueBlock = nextCandidate
    ? {
        id: nextCandidate.id,
        title: nextCandidate.title,
        startMin: nextCandidate.startMin,
        endMin: nextCandidate.endMin,
        remainMin: Math.max(0, nextCandidate.endMin - m),
        verifyStatus: nextCandidate.verifyStatus,
        lockedByBoss: nextCandidate.lockedByBoss,
      }
    : null
  return {
    totalVerify,
    passedVerify,
    overdueCount: overdueBlocks.length,
    pendingReviewCount: pendingReviewBlocks.length,
    allVerifyCleared: totalVerify > 0 && passedVerify === totalVerify && overdueBlocks.length === 0,
    nextDueBlock,
  }
}

/**
 * 心跳评估今日时间块（规则 1A + 2A）：
 * - 绑定了 sealId 且对应封印已过保底 → 自动过关（不重复发奖）；
 * - verify / 主管硬指令到 startMin 自动激活护航机位，到 endMin+60s 未通过验收 → 拉起 P3 验收锁（最长 60 分钟防死锁）；
 * - strict 块只在 [startMin, endMin) 自动激活 P4 专注白名单，到点不升 P3。
 */
export function evaluateDayBlocksTick(rawState, {
  now = Date.now(),
  dayStartMs = 0,
  minuteOfDay = 0,
  seals = [],
} = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  const m = clamp(intNum(minuteOfDay, 0), 0, 1439)
  if (!cur.dayBlocks.length) {
    return {
      state: cur,
      changed: false,
      activeVerifyBlock: null,
      activeStrictBlock: null,
      overdueVerifyBlocks: [],
      newlyEscalated: [],
      newlyBreached: [],
      newlyPassed: [],
      graceWarning: null,
      verifySummary: summarizeDayBlocksVerify([], { minuteOfDay: m }),
    }
  }

  const day0 = dayStartMs > 0 ? dayStartMs : (now - m * 60_000)
  const sealsMap = new Map()
  for (const s of Array.isArray(seals) ? seals : []) {
    if (s && s.id) sealsMap.set(String(s.id), s)
  }

  let changed = false
  let activeVerifyBlock = null
  let activeStrictBlock = null
  let graceWarning = null
  const overdueVerifyBlocks = []
  const newlyEscalated = []
  const newlyBreached = []
  const newlyPassed = []

  const nextBlocks = cur.dayBlocks.map((b) => {
    if (b.isBreak || b.status === 'skipped') return b
    let nb = { ...b }
    let dirty = false

    // 1. 绑定 sealId 且对应封印已过档/豁免时同步标为已过关
    if (nb.sealId && nb.status !== 'done') {
      const boundSeal = sealsMap.get(nb.sealId)
      if (boundSeal && ['floor', 'stretch', 'waived'].includes(boundSeal.status)) {
        nb = {
          ...nb,
          status: 'done',
          verifyStatus: 'passed',
          judgeVerdict: 'pass',
          judgedBy: 'auto_seal',
          judgedAt: now,
          judgeNote: nb.judgeNote || '关联封印已达标自动通过',
          overdueLockUntil: 0,
          updatedAt: now,
        }
        dirty = true
        changed = true
        newlyPassed.push({ block: nb, skipReward: true })
        return nb
      }
    }

    if (nb.status === 'done') return nb

    const startAt = day0 + nb.startMin * 60_000
    const dueAt = day0 + nb.endMin * 60_000
    const isVerify = nb.lockMode === 'verify' || nb.lockedByBoss === true

    if (!isVerify) {
      if (nb.lockMode === 'strict' && now >= startAt && now < dueAt) {
        if (nb.status === 'pending') {
          nb.status = 'running'
          nb.updatedAt = now
          dirty = true
        }
        if (!activeStrictBlock || nb.status === 'running') {
          activeStrictBlock = nb
        }
      }
      if (dirty) changed = true
      return dirty ? nb : b
    }

    // verify 块 / 主管硬指令
    if (now < startAt) {
      if (!['armed', 'submitted', 'rejected', 'passed'].includes(nb.verifyStatus)) {
        nb.verifyStatus = 'armed'
        dirty = true
      }
    } else if (now >= startAt && now < dueAt) {
      if (nb.status === 'pending') {
        nb.status = 'running'
        nb.updatedAt = now
        dirty = true
      }
      if (!['armed', 'submitted', 'rejected'].includes(nb.verifyStatus)) {
        nb.verifyStatus = 'armed'
        dirty = true
      }
      if (!activeVerifyBlock || nb.status === 'running') {
        activeVerifyBlock = nb
      }
    } else if (now >= dueAt && now < dueAt + OVERDUE_GRACE_MS) {
      if (!['submitted', 'rejected', 'passed', 'expired_breach'].includes(nb.verifyStatus) && nb.verifyStatus !== 'grace_warning') {
        nb.verifyStatus = 'grace_warning'
        nb.updatedAt = now
        dirty = true
      }
      const remainSec = Math.max(1, Math.ceil((dueAt + OVERDUE_GRACE_MS - now) / 1000))
      if (!graceWarning || remainSec < graceWarning.remainSec) {
        graceWarning = { blockId: nb.id, title: nb.title, remainSec, lockedByBoss: nb.lockedByBoss }
      }
      if (!activeVerifyBlock) {
        activeVerifyBlock = nb
      }
    } else {
      // 已超 endMin + 60s 缓冲
      const capUntil = nb.overdueLockUntil > 0 ? nb.overdueLockUntil : (dueAt + OVERDUE_GRACE_MS + DAYBLOCK_LOCK_CAP_MS)
      if (now < capUntil) {
        const hadOverdueBefore = nb.overdueLockUntil > 0 || nb.verifyStatus === 'overdue_locked' || nb.verifyStatus === 'expired_breach'
        if (nb.overdueLockUntil !== capUntil) {
          nb.overdueLockUntil = capUntil
          nb.updatedAt = now
          dirty = true
        }
        if (!['submitted', 'rejected', 'overdue_locked'].includes(nb.verifyStatus)) {
          nb.verifyStatus = 'overdue_locked'
          nb.updatedAt = now
          dirty = true
        }
        if (!hadOverdueBefore && nb.verifyStatus === 'overdue_locked') {
          newlyEscalated.push(nb)
        }
        overdueVerifyBlocks.push(nb)
      } else if (nb.verifyStatus !== 'expired_breach') {
        nb.verifyStatus = 'expired_breach'
        nb.updatedAt = now
        dirty = true
        newlyBreached.push(nb)
      }
    }

    if (dirty) changed = true
    return dirty ? nb : b
  })

  const nextState = changed ? { ...cur, dayBlocks: nextBlocks, updatedAt: now } : cur
  return {
    state: nextState,
    changed,
    activeVerifyBlock,
    activeStrictBlock,
    overdueVerifyBlocks,
    newlyEscalated,
    newlyBreached,
    newlyPassed,
    graceWarning,
    verifySummary: summarizeDayBlocksVerify(nextBlocks, { minuteOfDay: m }),
  }
}

/**
 * 提交某条计划块的物理交付物/因果结论，进入待终审状态（不会直接松开 P3 验收锁，须经终审 PASS）。
 */
export function submitDayBlockDelivery(rawState, { blockId = '', summary = '', evidenceUrl = '', now = Date.now() } = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  const text = String(summary || '').trim()
  const extra = String(evidenceUrl || '').trim()
  const combined = extra ? `${text}\n[附件/路径] ${extra}`.trim() : text
  if (combined.length < 12) {
    return {
      ok: false,
      error: 'too-short',
      text: '交付说明至少 12 个字，请写清真实产出、文件/题量或核心因果结论',
      block: null,
      state: cur,
    }
  }
  const id = String(blockId || '').trim()
  const target = id
    ? cur.dayBlocks.find((b) => b.id === id)
    : (cur.dayBlocks.find((b) => !b.isBreak && b.status !== 'done' && (b.verifyStatus === 'overdue_locked' || b.verifyStatus === 'rejected'))
        || cur.dayBlocks.find((b) => !b.isBreak && b.status !== 'done' && (b.lockMode === 'verify' || b.lockedByBoss)))
  if (!target) {
    return { ok: false, error: 'no-block', text: '未找到待交付的验收时间块', block: null, state: cur }
  }
  const nextBlocks = cur.dayBlocks.map((b) => (
    b.id === target.id
      ? {
          ...b,
          submissionText: combined.slice(0, 1200),
          submittedAt: now,
          verifyStatus: 'submitted',
          judgeVerdict: '',
          updatedAt: now,
        }
      : b
  ))
  const updatedBlock = nextBlocks.find((b) => b.id === target.id)
  return {
    ok: true,
    block: updatedBlock,
    state: { ...cur, dayBlocks: nextBlocks, updatedAt: now },
  }
}

/**
 * 主管墙 / 冷代码 / Gemini 对时间块交付进行终审裁决：
 * - pass：必须存在 ≥12 字真实交付或受信任的 code/auto_seal/gemini(2组问答以上) 来源，绝不允许空口放水；
 * - reject：必须写明具体漏洞，块维持未完成，若已逾期则继续锁定 P3 验收锁。
 */
export function judgeDayBlockDelivery(rawState, {
  blockId = '',
  verdict = '',
  note = '',
  judgedBy = 'wall_boss',
  now = Date.now(),
  dayStartMs = 0,
  allowAutoFromReceipt = false,
} = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  const v = String(verdict || '').trim().toLowerCase()
  if (!['pass', 'reject'].includes(v)) {
    return { ok: false, error: 'bad-verdict', text: '终审裁决仅支持 pass 或 reject', block: null, rewardMin: 0, state: cur }
  }
  const id = String(blockId || '').trim()
  const target = cur.dayBlocks.find((b) => b.id === id)
  if (!target) {
    return { ok: false, error: 'no-block', text: '未找到指定验收时间块', block: null, rewardMin: 0, state: cur }
  }
  const cleanNote = String(note || '').trim().slice(0, 400)
  const by = ['wall_boss', 'code', 'gemini', 'auto_seal'].includes(String(judgedBy || '')) ? String(judgedBy) : 'wall_boss'

  if (v === 'pass') {
    const hasSubmission = Boolean(target.submissionText && target.submissionText.trim().length >= 12)
    const trustedAuto = ['code', 'auto_seal'].includes(by) || (by === 'gemini' && allowAutoFromReceipt === true)
    if (!hasSubmission && !trustedAuto) {
      return {
        ok: false,
        error: 'no-delivery',
        text: '该验收块尚无用户提交的真实交付物（≥12字），严禁空口判 PASS；请要求用户先提交交付',
        block: null,
        rewardMin: 0,
        state: cur,
      }
    }
    const dueAt = dayStartMs > 0 ? (dayStartMs + target.endMin * 60_000) : 0
    const isLate = Boolean(
      target.verifyStatus === 'overdue_locked' ||
      target.overdueLockUntil > 0 ||
      (dueAt > 0 && now > dueAt + OVERDUE_GRACE_MS),
    )
    const dur = Math.max(10, target.endMin - target.startMin)
    const baseReward = clamp(Math.round(dur * 0.25), 5, 20)
    const computedReward = Math.max(2, isLate ? Math.round(baseReward * 0.5) : baseReward)
    const rewardMin = target.rewardPaidMin > 0 ? 0 : computedReward
    const nextBlocks = cur.dayBlocks.map((b) => (
      b.id === target.id
        ? {
            ...b,
            status: 'done',
            verifyStatus: 'passed',
            judgeVerdict: 'pass',
            judgeNote: cleanNote || '交付核验通过',
            judgedBy: by,
            judgedAt: now,
            overdueLockUntil: 0,
            rewardPaidMin: b.rewardPaidMin > 0 ? b.rewardPaidMin : rewardMin,
            updatedAt: now,
          }
        : b
    ))
    return {
      ok: true,
      verdict: 'pass',
      late: isLate,
      rewardMin,
      block: nextBlocks.find((b) => b.id === target.id),
      state: { ...cur, dayBlocks: nextBlocks, updatedAt: now },
    }
  }

  if (!cleanNote) {
    return {
      ok: false,
      error: 'no-note',
      text: '驳回（reject）必须明确指出具体漏洞或要求补交的物理证据',
      block: null,
      rewardMin: 0,
      state: cur,
    }
  }
  const nextBlocks = cur.dayBlocks.map((b) => (
    b.id === target.id
      ? {
          ...b,
          status: b.status === 'done' ? 'running' : b.status,
          verifyStatus: 'rejected',
          judgeVerdict: 'reject',
          judgeNote: cleanNote,
          judgedBy: by,
          judgedAt: now,
          updatedAt: now,
        }
      : b
  ))
  return {
    ok: true,
    verdict: 'reject',
    late: false,
    rewardMin: 0,
    block: nextBlocks.find((b) => b.id === target.id),
    state: { ...cur, dayBlocks: nextBlocks, updatedAt: now },
  }
}

/**
 * 主管墙下达止损硬验收指令：强制写入 verify + lockedByBoss，不可普通删除/跳过，到点必进 P3 验收锁。
 */
export function issueBossDirectiveBlock(rawState, {
  title = '',
  deliverable = '',
  startMin,
  endMin,
  durationMin = 45,
  allowedApps,
  now = Date.now(),
  minuteOfDay = 0,
} = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  const cleanTitle = String(title || '').trim().slice(0, 100)
  const cleanDeliv = String(deliverable || '').trim().slice(0, 180)
  if (!cleanTitle || !cleanDeliv) {
    return {
      ok: false,
      error: 'missing-fields',
      text: '主管硬指令必须同时写清任务标题（title）和可验物理交付物（deliverable）',
      block: null,
      state: cur,
    }
  }
  const start = startMin !== undefined && startMin !== null && Number.isFinite(Number(startMin))
    ? clamp(Math.round(Number(startMin)), 0, 1425)
    : clamp(intNum(minuteOfDay, 9 * 60), 0, 1425)
  const dur = clamp(intNum(durationMin, 45), 15, 180)
  const end = endMin !== undefined && endMin !== null && Number.isFinite(Number(endMin))
    ? clamp(Math.round(Number(endMin)), start + 15, 1440)
    : Math.min(1440, start + dur)
  const id = `blk-boss-${now.toString(36).slice(-5)}-${cur.dayBlocks.length + 1}`
  const up = upsertDayBlock(cur.dayBlocks, {
    id,
    startMin: start,
    endMin: end,
    title: cleanTitle,
    deliverable: cleanDeliv,
    lockMode: 'verify',
    source: 'wall_boss',
    lockedByBoss: true,
    verifyStatus: 'armed',
    status: 'pending',
    isBreak: false,
    allowedApps: Array.isArray(allowedApps) && allowedApps.length ? allowedApps : ['md.obsidian', 'com.tencent.mm'],
  }, { now })
  return {
    ok: up.ok,
    error: up.error,
    block: up.block,
    state: { ...cur, dayBlocks: up.dayBlocks, updatedAt: now },
  }
}

/**
 * 更新托管冲刺轨与无人破冰哨策略（可跨天保留）。
 */
export function setCustodyPolicy(rawState, patch = {}, { now = Date.now() } = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  const p = patch && typeof patch === 'object' ? patch : {}
  const nextPolicy = cleanCustodyPolicy({
    ...cur.custodyPolicy,
    ...(p.autoKickoffEnabled !== undefined ? { autoKickoffEnabled: p.autoKickoffEnabled } : {}),
    ...(p.kickoffMinutes !== undefined ? { kickoffMinutes: p.kickoffMinutes } : {}),
    ...(p.kickoffDurationMin !== undefined ? { kickoffDurationMin: p.kickoffDurationMin } : {}),
    ...(p.adaptiveSplitEnabled !== undefined ? { adaptiveSplitEnabled: p.adaptiveSplitEnabled } : {}),
  })
  return {
    ok: true,
    custodyPolicy: nextPolicy,
    state: { ...cur, custodyPolicy: nextPolicy, updatedAt: now },
  }
}

/**
 * 路径 2 核心：高强度对齐后一次签发未来 2-4 小时「串联托管冲刺轨」（1-4 个强制 verify 块 + 中间短休）。
 */
export function issueSprintCustody(rawState, {
  title = '高强度托管冲刺轨',
  blocks = [],
  startMin,
  issuedBy = 'wall_boss',
  armAutoKickoff,
  now = Date.now(),
  minuteOfDay = 0,
  dayStartMs = 0,
} = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  const rawList = (Array.isArray(blocks) ? blocks : []).slice(0, 4)
  if (!rawList.length) {
    return {
      ok: false,
      error: 'empty-blocks',
      text: '托管冲刺轨至少需要 1 个带明确交付物的验收块',
      custody: cur.custody,
      blocks: [],
      state: cur,
    }
  }

  let cursor = startMin !== undefined && startMin !== null && Number.isFinite(Number(startMin))
    ? clamp(Math.round(Number(startMin)), 0, 1410)
    : clamp(intNum(minuteOfDay, 9 * 60), 0, 1410)

  const custodyId = `custody-${now.toString(36).slice(-5)}`
  const src = issuedBy === 'gemini' ? 'gemini_custody' : 'wall_boss'
  const builtBlocks = []
  const workIds = []

  for (let i = 0; i < rawList.length; i += 1) {
    const item = rawList[i] && typeof rawList[i] === 'object' ? rawList[i] : {}
    const bTitle = String(item.title || '').trim().slice(0, 100)
    const bDeliv = String(item.deliverable || '').trim().slice(0, 180)
    if (!bTitle || !bDeliv) {
      return {
        ok: false,
        error: 'missing-fields',
        text: `第 ${i + 1} 个冲刺块必须同时写清 title 与 deliverable`,
        custody: cur.custody,
        blocks: [],
        state: cur,
      }
    }
    const dur = clamp(intNum(item.durationMin ?? item.duration_min ?? item.minutes, 45), 15, 120)
    const start = clamp(cursor, 0, 1425)
    const end = Math.min(1440, start + dur)
    const bid = `blk-cust-${now.toString(36).slice(-4)}-${i + 1}`
    const apps = cleanAppsList(item.allowedApps ?? item.allowed_apps)
    const blk = cleanDayBlock({
      id: bid,
      startMin: start,
      endMin: end,
      title: bTitle,
      deliverable: bDeliv,
      lockMode: 'verify',
      source: src,
      lockedByBoss: true,
      verifyStatus: 'armed',
      status: 'pending',
      isBreak: false,
      custodyId,
      allowedApps: apps,
      updatedAt: now,
    }, cur.dayBlocks.length + builtBlocks.length)
    builtBlocks.push(blk)
    workIds.push(blk.id)
    cursor = end

    const isLast = i === rawList.length - 1
    const rawBreak = item.breakAfterMin ?? item.break_after_min
    const gap = isLast ? 0 : (rawBreak !== undefined ? clamp(intNum(rawBreak, 0), 0, 20) : 10)
    if (gap >= 5 && cursor + gap + 15 <= 1440) {
      builtBlocks.push(cleanDayBlock({
        id: `blk-cust-gap-${now.toString(36).slice(-4)}-${i + 1}`,
        startMin: cursor,
        endMin: cursor + gap,
        title: `冲刺段间恢复休整 (${gap}m)`,
        deliverable: '准时回位进入下一冲刺块',
        lockMode: 'gentle',
        source: 'template',
        lockedByBoss: false,
        isBreak: true,
        custodyId,
        status: 'pending',
        updatedAt: now,
      }, cur.dayBlocks.length + builtBlocks.length))
      cursor += gap
    }
  }

  const untilMin = builtBlocks.reduce((mx, b) => Math.max(mx, b.endMin), cursor)
  const remainSpan = Math.max(30, untilMin - intNum(minuteOfDay, 0))
  const untilAt = dayStartMs > 0 ? (dayStartMs + untilMin * 60_000) : (now + remainSpan * 60_000)
  const prevRebriefCount = Number(cur.custody?.rebriefCount) || 0
  const prevRebriefLog = Array.isArray(cur.custody?.rebriefLog) ? cur.custody.rebriefLog : []
  const custody = {
    active: true,
    id: custodyId,
    title: String(title || '高强度托管冲刺轨').trim().slice(0, 100),
    issuedBy: issuedBy === 'gemini' ? 'gemini' : 'wall_boss',
    startedAt: now,
    untilAt,
    untilMin,
    blockIds: workIds,
    rebriefCount: prevRebriefCount,
    rebriefLog: prevRebriefLog,
  }
  const nextPolicy = armAutoKickoff !== undefined
    ? cleanCustodyPolicy({ ...cur.custodyPolicy, autoKickoffEnabled: Boolean(armAutoKickoff) })
    : cur.custodyPolicy
  const nextBlocks = cleanDayBlocks([...cur.dayBlocks, ...builtBlocks])
  const nextState = cleanDayPlanState({
    ...cur,
    dayBlocks: nextBlocks,
    custody,
    custodyPolicy: nextPolicy,
    updatedAt: now,
  }, { day: cur.day, now })
  return {
    ok: true,
    custody: nextState.custody,
    blocks: builtBlocks.filter((b) => !b.isBreak),
    untilHm: hmStrOf(untilMin),
    state: nextState,
  }
}

/**
 * 托管期唯一合法改轨口：须交代 >=6 字客观原因，关闭旧托管未完成块并替换为新轨或低电量 25m 保底块。
 */
export function rebriefSprintCustody(rawState, {
  reason = '',
  blocks = [],
  downgradeLowEnergy = false,
  issuedBy = 'wall_boss',
  now = Date.now(),
  minuteOfDay = 0,
  dayStartMs = 0,
} = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  const cleanReason = String(reason || '').trim().slice(0, 240)
  if (cleanReason.length < 6) {
    return {
      ok: false,
      error: 'need-reason',
      text: 'Re-brief 重签至少写明 6 个字的客观变动原因',
      custody: cur.custody,
      state: cur,
    }
  }
  const oldIds = new Set(cur.custody?.blockIds || [])
  const oldCustodyId = cur.custody?.id || ''
  const nextDayBlocks = cur.dayBlocks.map((b) => {
    if ((oldIds.has(b.id) || (oldCustodyId && b.custodyId === oldCustodyId)) && b.status !== 'done') {
      return { ...b, status: 'skipped', overdueLockUntil: 0, updatedAt: now }
    }
    return b
  })
  const baseWithSkip = {
    ...cur,
    dayBlocks: nextDayBlocks,
    custody: cur.custody
      ? {
          ...cur.custody,
          active: false,
          rebriefCount: (cur.custody.rebriefCount || 0) + 1,
          rebriefLog: [...(cur.custody.rebriefLog || []), { at: now, reason: cleanReason, issuedBy }].slice(-12),
        }
      : {
          active: false,
          id: `custody-rebrief-${now.toString(36).slice(-4)}`,
          title: 'Re-brief 重签轨',
          issuedBy,
          startedAt: now,
          untilAt: now,
          untilMin: clamp(intNum(minuteOfDay, 540), 0, 1440),
          blockIds: [],
          rebriefCount: 1,
          rebriefLog: [{ at: now, reason: cleanReason, issuedBy }],
        },
  }
  const replacement = Array.isArray(blocks) && blocks.length
    ? blocks
    : [{
        title: downgradeLowEnergy ? '低电量保底重签块 (25m)' : 'Re-brief 保底推进块',
        deliverable: '提交 ≥12 字最小物理产出或断点',
        durationMin: 25,
      }]
  return issueSprintCustody(baseWithSkip, {
    title: downgradeLowEnergy ? 'Re-brief 低电量保底轨' : 'Re-brief 重签冲刺轨',
    blocks: replacement,
    issuedBy,
    now,
    minuteOfDay,
    dayStartMs,
  })
}

/**
 * 成绩驱动强度阶梯：块被 REJECT 或 P3 违约后，自动将下一段待办大块（>=45m）拆成 2x25m 高频短促硬验并收窄白名单。
 */
export function applyAdaptiveIntensity(rawState, { triggerBlockId = '', triggerReason = 'reject', now = Date.now() } = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  if (cur.custodyPolicy?.adaptiveSplitEnabled === false) {
    return { adapted: false, state: cur, origBlock: null, splitBlocks: [], note: '' }
  }
  const blocks = cur.dayBlocks
  const candidate = blocks.find(
    (b) =>
      b.id !== triggerBlockId &&
      !b.isBreak &&
      b.status === 'pending' &&
      !b.adaptedFrom &&
      (b.endMin - b.startMin) >= 45,
  )
  if (!candidate) {
    return { adapted: false, state: cur, origBlock: null, splitBlocks: [], note: '' }
  }

  const totalDur = candidate.endMin - candidate.startMin
  const part1Dur = 25
  const part1End = candidate.startMin + part1Dur
  const gapMin = totalDur >= 50 ? 5 : 0
  const part2Start = part1End + gapMin
  const part2Dur = Math.max(15, candidate.endMin - part2Start)
  const part2End = Math.min(1440, part2Start + part2Dur)

  const coreAllow = candidate.allowedApps.filter((p) => p === 'md.obsidian' || /anki|wps|office|pdf|read|notion/i.test(p))
  const narrowedApps = cleanAppsList(coreAllow.length ? coreAllow.slice(0, 2) : candidate.allowedApps.slice(0, 1))

  const p1 = cleanDayBlock({
    ...candidate,
    id: `${candidate.id}-s1`.slice(0, 60),
    startMin: candidate.startMin,
    endMin: part1End,
    title: `${candidate.title} · 拆段 1/2 (25m)`,
    deliverable: `${candidate.deliverable}（前半段物理检查点）`.slice(0, 180),
    lockMode: 'verify',
    lockedByBoss: true,
    verifyStatus: 'armed',
    allowedApps: narrowedApps,
    adaptedFrom: candidate.id,
    updatedAt: now,
  })
  const p2 = cleanDayBlock({
    ...candidate,
    id: `${candidate.id}-s2`.slice(0, 60),
    startMin: part2Start,
    endMin: part2End,
    title: `${candidate.title} · 拆段 2/2 (${part2End - part2Start}m)`,
    deliverable: `${candidate.deliverable}（下半段终验收口）`.slice(0, 180),
    lockMode: 'verify',
    lockedByBoss: true,
    verifyStatus: 'armed',
    allowedApps: narrowedApps,
    adaptedFrom: candidate.id,
    updatedAt: now,
  })
  const gapBreak = gapMin > 0
    ? cleanDayBlock({
        id: `${candidate.id}-sg`.slice(0, 60),
        startMin: part1End,
        endMin: part2Start,
        title: `拆段喘息切换 (${gapMin}m)`,
        deliverable: '准备下半段动作',
        lockMode: 'gentle',
        source: 'template',
        lockedByBoss: false,
        isBreak: true,
        status: 'pending',
        updatedAt: now,
      })
    : null

  const replacement = gapBreak ? [p1, gapBreak, p2] : [p1, p2]
  const nextBlocks = cleanDayBlocks(blocks.flatMap((b) => (b.id === candidate.id ? replacement : [b])))

  let nextCustody = cur.custody
  if (nextCustody && Array.isArray(nextCustody.blockIds) && nextCustody.blockIds.includes(candidate.id)) {
    const newIds = nextCustody.blockIds.flatMap((id) => (id === candidate.id ? [p1.id, p2.id] : [id]))
    nextCustody = { ...nextCustody, blockIds: newIds }
  }

  const nextLog = [
    ...(cur.adaptiveLog || []),
    {
      at: now,
      triggerBlockId,
      splitBlockId: candidate.id,
      newBlockIds: [p1.id, p2.id],
      reason: String(triggerReason || 'reject').slice(0, 48),
    },
  ].slice(-20)

  const nextState = cleanDayPlanState({
    ...cur,
    dayBlocks: nextBlocks,
    custody: nextCustody,
    adaptiveLog: nextLog,
    updatedAt: now,
  }, { day: cur.day, now })
  const note = `因上一块${triggerReason === 'breach' ? '逾期违约' : '验收驳回'}，已自动将「${candidate.title}」拆为 2 段高频短块（${hmStrOf(p1.startMin)}–${hmStrOf(p1.endMin)} / ${hmStrOf(p2.startMin)}–${hmStrOf(p2.endMin)}）并收窄白名单`
  return { adapted: true, state: nextState, origBlock: candidate, splitBlocks: [p1, p2], note }
}

/**
 * 路径 1 无人破冰硬哨：到 09:15 / 14:45 时若当日尚未排入任何 verify 块，不等用户输入直接钉入 25m 起手硬验收块。
 */
export function evaluateAutoKickoffTick(rawState, { now = Date.now(), minuteOfDay = 0 } = {}) {
  const cur = cleanDayPlanState(rawState, { now })
  if (cur.custodyPolicy?.autoKickoffEnabled !== true) {
    return { fired: false, block: null, slotHm: '', state: cur }
  }
  const m = clamp(intNum(minuteOfDay, 0), 0, 1439)
  if (m < 7 * 60 || m >= 22 * 60 || (m >= LUNCH_BREAK_WINDOW.startMin && m < LUNCH_BREAK_WINDOW.endMin)) {
    return { fired: false, block: null, slotHm: '', state: cur }
  }
  const vSum = summarizeDayBlocksVerify(cur.dayBlocks, { minuteOfDay: m })
  if (vSum.totalVerify > 0) {
    return { fired: false, block: null, slotHm: '', state: cur }
  }

  const slots = cur.custodyPolicy.kickoffMinutes || DEFAULT_KICKOFF_MINUTES
  const firedSet = new Set(cur.firedKickoffSlots || [])
  const dueSlot = slots.find((s) => m >= s && m < s + 180 && !firedSet.has(hmStrOf(s)))
  if (dueSlot === undefined) {
    return { fired: false, block: null, slotHm: '', state: cur }
  }

  const slotHm = hmStrOf(dueSlot)
  const dur = clamp(intNum(cur.custodyPolicy.kickoffDurationMin, 25), 15, 60)
  const end = Math.min(1440, m + dur)
  const unmovedCarry = cur.carryoverItems.find((c) => !c.moved && c.title)
  const bid = `blk-kick-${slotHm.replace(':', '')}-${now.toString(36).slice(-4)}`
  const block = cleanDayBlock({
    id: bid,
    startMin: m,
    endMin: end,
    title: unmovedCarry ? `自动破冰：${unmovedCarry.title}` : (dueSlot < 720 ? '上午无人破冰硬起手 (25m)' : '下午无人破冰硬起手 (25m)'),
    deliverable: unmovedCarry?.deliverable || '完成 20 题或主线推进 1 步并提交 ≥12 字物理产出',
    lockMode: 'verify',
    source: 'auto_kickoff',
    lockedByBoss: true,
    verifyStatus: 'armed',
    status: 'pending',
    isBreak: false,
    allowedApps: unmovedCarry?.allowedApps || ['md.obsidian', 'com.tencent.mm'],
    updatedAt: now,
  }, cur.dayBlocks.length)

  const nextCarryover = unmovedCarry
    ? cur.carryoverItems.map((c) => (c.id === unmovedCarry.id ? { ...c, moved: true, movedBlockId: bid } : c))
    : cur.carryoverItems
  const nextState = cleanDayPlanState({
    ...cur,
    carryoverItems: nextCarryover,
    dayBlocks: cleanDayBlocks([...cur.dayBlocks, block]),
    firedKickoffSlots: [...(cur.firedKickoffSlots || []), slotHm],
    updatedAt: now,
  }, { day: cur.day, now })
  return { fired: true, block, slotHm, state: nextState }
}
