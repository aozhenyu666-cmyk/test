// dsh-go-companion · V4 AI 协同「计划模态 (Plan Modality)」与 1 分钟缓冲自动严格锁引擎
// 核心理念（融合 GitHub StateProof / agent-workflow）：
// 1) 和 AI 一起把「用哪些 App、本机现有 App 够不够、交什么验收点、看视频怎么压缩提炼验收」写成结构化契约；
// 2) 契约期内在约定 App 范围内柔性自由（支持看视频网站学习），降低 AI 无脑盯屏与乱打断负担；
// 3) 一旦到达检查点截止时间仍未通过验收：给予 1 分钟 (60s) 明确倒计时提醒，1 分钟后仍未交付自动升级为严格锁！

export const OVERDUE_GRACE_MS = 60_000 // 用户拍板：到点缓冲 1 分钟后转严格锁

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
