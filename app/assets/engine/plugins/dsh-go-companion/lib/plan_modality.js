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
