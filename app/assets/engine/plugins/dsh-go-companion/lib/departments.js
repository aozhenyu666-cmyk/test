// dsh-go-companion · 四部内部隐式自动路由（M4.1）
//
// 对齐 aozhenyu666-cmyk/mouhua-thinking 的 operating_protocol.md 第 8 节：
//   dept:collect    信息收集部（未知事实/新材料未核验/现场线索整理）
//   dept:strategize 谋划思考部（前提可疑/因果争议/替代解释与反证）
//   dept:dispatch   统筹规划部（优先级取舍/时间预算冲突/工作包排期与合同调度）
//   dept:govern     督办审计部（局中执行/交付物验收/规则与锁的执行审计）
//
// 原则：用户绝不手工选部门，由系统依据输入缺口与当前状态自动流转并写入 bouts.department 与 ledger。

import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { listBouts, updateBout } from './bouts.js'
import { dayString } from './daily.js'
import { appendLedger } from './ledger.js'
import { JUDGE_FILE, writeObsidianCard } from './notes.js'

export const DEPARTMENTS = Object.freeze(['dept:collect', 'dept:strategize', 'dept:dispatch', 'dept:govern'])

export const DEPT_LABELS = Object.freeze({
  'dept:collect': '信息收集部',
  'dept:strategize': '谋划思考部',
  'dept:dispatch': '统筹规划部',
  'dept:govern': '督办审计部',
})

export function isValidDepartment(dept) {
  return DEPARTMENTS.includes(String(dept || ''))
}

/**
 * 依据当前状态与文本/事件缺口自动推导目标部门。
 * 优先级：执行与验收（有进行中的局且处于验收/锁/交付核查）> 关键信息缺口/未核验材料（collect）
 *         > 前提/因果判断冲突（strategize）> 资源/时段/日程取舍（dispatch）> 默认 govern。
 * @returns {{department:string, reason:string}}
 */
export function inferDepartment(input = {}) {
  const mainState = String(input.mainState || '')
  const kind = String(input.kind || '')
  const text = String(input.text || '')

  if (kind === 'audit' || kind === 'deliverable' || ['morning', 'review', 'evening', 'followup'].includes(mainState)) {
    if (/(为什么|值不值得|前提|反证|怎么看|要不要选)/.test(text)) {
      return { department: 'dept:strategize', reason: 'premise-or-tradeoff-question' }
    }
    return { department: 'dept:govern', reason: 'gate-or-audit-state' }
  }

  if (input.hasNewMaterial || kind === 'clue' || /(查一下|是什么|真假|原文|截图|资料|笔记|线索|没看过)/.test(text)) {
    return { department: 'dept:collect', reason: 'missing-evidence-or-material' }
  }

  if (input.premiseDisputed || /(前提|因果|搞不懂|为什么|反例|解释|判断|卡在哪|替代)/.test(text)) {
    return { department: 'dept:strategize', reason: 'causal-or-premise-gap' }
  }

  if (input.scheduleConflict || /(排期|什么时候|冲突|时间不够|先做哪个|预算|工作包|计划|请假|租约)/.test(text)) {
    return { department: 'dept:dispatch', reason: 'resource-or-schedule-tradeoff' }
  }

  if (mainState === 'sprint' || mainState === 'brain' || input.runningBout) {
    return { department: 'dept:govern', reason: 'active-bout-execution' }
  }

  return { department: 'dept:collect', reason: 'default-intake' }
}

/**
 * 流转当前局的内部部门并同时记录到 bouts 表和 ledger.jsonl。
 * 若 target === current，幂等跳过不写多余账本行。
 */
export function routeBoutDepartment({ db, home = '', boutId = '', currentDept = '', input = {}, now = Date.now() } = {}) {
  const next = isValidDepartment(input.explicitDepartment)
    ? { department: input.explicitDepartment, reason: 'explicit-route' }
    : inferDepartment(input)

  if (!isValidDepartment(next.department)) {
    return { ok: false, error: 'bad-department', department: currentDept || '' }
  }
  if (currentDept === next.department) {
    return { ok: true, changed: false, department: next.department, reason: next.reason }
  }
  if (db && boutId) {
    const r = updateBout(db, boutId, { department: next.department }, { now, home })
    if (!r.ok) return { ok: false, error: r.error, department: currentDept || '' }
  }
  if (home) {
    appendLedger(home, {
      at: now,
      state: 'department-switch',
      boutId: String(boutId || ''),
      from: String(currentDept || ''),
      to: next.department,
      reason: next.reason,
    })
  }
  return { ok: true, changed: true, department: next.department, reason: next.reason }
}

// ---------------------------------------------------------------- V3 四部 SOP 入口/出口硬门禁

export const COLLECT_QUESTION_MAX_CHARS = 25
export const DISPATCH_WINDOW_MS = 5 * 60_000
export const ERR_NO_RAW_FACTS = 'ERR_NO_RAW_FACTS'

/**
 * 信息收集部 SOP：target_question (≤25 字) → raw_fact_cards (≥1 张) + 写入 Obsidian 收集箱/。
 */
export function collectDepartmentSop({
  targetQuestion = '',
  rawFacts = [],
  source = '',
  kb = '',
  at = Date.now(),
  tz = 'Asia/Shanghai',
} = {}) {
  const q = String(targetQuestion || '').trim()
  if (!q) return { ok: false, department: 'dept:collect', code: 'ERR_EMPTY_QUESTION', message: '信息收集部必须给出明确目标问题。' }
  if (q.length > COLLECT_QUESTION_MAX_CHARS) {
    return {
      ok: false,
      department: 'dept:collect',
      code: 'ERR_QUESTION_TOO_LONG',
      limit: COLLECT_QUESTION_MAX_CHARS,
      message: `target_question 超过 ${COLLECT_QUESTION_MAX_CHARS} 字上限（当前 ${q.length} 字）。`,
    }
  }
  const items = (Array.isArray(rawFacts) ? rawFacts : [rawFacts])
    .map((f, idx) => {
      if (f && typeof f === 'object') {
        const text = String(f.fact || f.text || f.summary || '').trim()
        if (!text) return null
        return {
          id: String(f.id || `fact_${at}_${idx + 1}`),
          target_question: q,
          fact: text,
          source: String(f.source || source || 'obsidian').trim(),
          at: Number(f.at) || at,
        }
      }
      const text = String(f || '').trim()
      if (!text) return null
      return {
        id: `fact_${at}_${idx + 1}`,
        target_question: q,
        fact: text,
        source: String(source || 'obsidian').trim(),
        at,
      }
    })
    .filter(Boolean)

  if (items.length < 1) {
    return { ok: false, department: 'dept:collect', code: ERR_NO_RAW_FACTS, message: '未产出至少 1 张原始事实卡（raw_fact_cards）。' }
  }

  let obsidianPath = ''
  if (kb) {
    obsidianPath = writeObsidianCard({
      kb,
      folder: '收集箱',
      slug: `${dayString(at, tz)}-${q.slice(0, 16)}`,
      title: q,
      tags: ['collect', 'raw-fact'],
      department: 'dept:collect',
      body: items.map((c, i) => `${i + 1}. ${c.fact}${c.source ? `（来源：${c.source}）` : ''}`).join('\n'),
      at,
      tz,
    })
  }

  return {
    ok: true,
    department: 'dept:collect',
    target_question: q,
    raw_fact_cards: items,
    obsidianPath,
  }
}

/**
 * 谋划思考部 SOP 硬门禁：没有绑定 ≥1 张 raw_fact_cards 或未决问题卡时直接拒单 ERR_NO_RAW_FACTS。
 */
export function strategizeDepartmentSop({
  rawFactCards = [],
  openQuestionCard = null,
  decision = '',
  falsifyPath = '',
  rationale = '',
} = {}) {
  const cards = Array.isArray(rawFactCards) ? rawFactCards.filter((c) => c && (c.fact || c.text || c.id)) : []
  const hasQuestionCard = Boolean(
    openQuestionCard &&
      (String(openQuestionCard.id || '').trim() ||
        String(openQuestionCard.target_question || openQuestionCard.question || '').trim()),
  )
  if (cards.length < 1 && !hasQuestionCard) {
    return {
      ok: false,
      department: 'dept:strategize',
      code: ERR_NO_RAW_FACTS,
      message: '谋划思考部硬门禁拦截：缺少关联 raw_fact_cards 或未决问题卡，禁止无据决策。',
    }
  }
  const coreDecision = String(decision || '').trim()
  if (!coreDecision) {
    return {
      ok: false,
      department: 'dept:strategize',
      code: 'ERR_EMPTY_DECISION',
      message: '单点决策结论不能为空。',
    }
  }
  return {
    ok: true,
    department: 'dept:strategize',
    single_point_decision: {
      decision: coreDecision,
      falsify_path: String(falsifyPath || '').trim(),
      rationale: String(rationale || '').trim(),
      linked_card_ids: cards.map((c, i) => String(c.id || `card_${i + 1}`)),
      open_question_id: hasQuestionCard ? String(openQuestionCard.id || openQuestionCard.target_question || openQuestionCard.question) : '',
    },
  }
}

/** 计算近 7 天真实完成局数与每日排期上限（防过载排期）。 */
export function rolling7dCompletedCap({ db = null, boutsHistory = [], now = Date.now(), minCap = 1, maxCap = 6 } = {}) {
  const since = Number(now) - 7 * 86_400_000
  const rows = db ? listBouts(db, { status: 'closed', limit: 200 }) : Array.isArray(boutsHistory) ? boutsHistory : []
  const completed7d = rows.filter((b) => {
    if (!b || b.status !== 'closed') return false
    const closedAt = Number(b.closedAt ?? b.closed_at) || 0
    const reason = String(b.closeReason ?? b.close_reason ?? 'done')
    return closedAt >= since && closedAt <= Number(now) && reason === 'done'
  }).length
  const dailyCap = completed7d <= 0 ? minCap : Math.max(minCap, Math.min(maxCap, Math.ceil(completed7d / 7)))
  return { completed7d, dailyCap }
}

/**
 * 统筹规划部 SOP：≤ 5 分钟排期窗口 + 读取近 7 天真实完成局上限，拦截过载排期。
 */
export function dispatchDepartmentSop({
  db = null,
  boutsHistory = [],
  proposedQueue = [],
  windowStartMs = 0,
  now = Date.now(),
} = {}) {
  if (Number(windowStartMs) > 0 && Number(now) - Number(windowStartMs) > DISPATCH_WINDOW_MS) {
    return {
      ok: false,
      department: 'dept:dispatch',
      code: 'ERR_DISPATCH_WINDOW_EXPIRED',
      elapsedMs: Number(now) - Number(windowStartMs),
      message: '统筹排期窗口已超过 5 分钟上限，需重新收敛后排期。',
    }
  }
  const items = Array.isArray(proposedQueue) ? proposedQueue.filter(Boolean) : []
  if (!items.length) {
    return { ok: false, department: 'dept:dispatch', code: 'ERR_EMPTY_QUEUE', message: '排期队列为空。' }
  }
  const { completed7d, dailyCap } = rolling7dCompletedCap({ db, boutsHistory, now })
  if (items.length > dailyCap) {
    return {
      ok: false,
      department: 'dept:dispatch',
      code: 'ERR_OVERSCHEDULED',
      dailyCap,
      completed7d,
      requested: items.length,
      message: `过载排期已拦截：近 7 天完成 ${completed7d} 局，当日上限 ${dailyCap} 局，请求了 ${items.length} 局。`,
    }
  }
  return {
    ok: true,
    department: 'dept:dispatch',
    dailyCap,
    completed7d,
    locked_plan_modality_queue: items.map((item, idx) => ({
      ...(typeof item === 'object' ? item : { protocolId: String(item) }),
      order: idx + 1,
      locked: true,
      lockedAt: now,
    })),
  }
}

export const JUDGE_ARCHIVE_DAYS = 30

function judgeDaysBetween(a, b) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(a || '')) || !/^\d{4}-\d{2}-\d{2}$/.test(String(b || ''))) return 0
  return Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86_400_000)
}

function splitJudgeSections(text) {
  const lines = String(text || '').split(/\r?\n/)
  const sections = []
  let current = { day: '', marker: '', lines: [] }
  const pushCurrent = () => {
    if (current.lines.some((l) => l.trim().length > 0)) {
      sections.push(current)
    }
    current = { day: '', marker: '', lines: [] }
  }

  for (const line of lines) {
    const trim = line.trim()
    const markerMatch = /^<!--\s*[^:>]+:\s*(\d{4}-\d{2}-\d{2})\s*-->$/.exec(trim)
    if (markerMatch) {
      pushCurrent()
      current = { day: markerMatch[1], marker: trim, lines: [line] }
      continue
    }
    const headingDateMatch = /^#{2,3}\s+(\d{4}-\d{2}-\d{2})\b/.exec(trim)
    if (headingDateMatch) {
      if (current.day === headingDateMatch[1] && current.lines.length <= 2) {
        current.lines.push(line)
      } else {
        pushCurrent()
        current = { day: headingDateMatch[1], marker: '', lines: [line] }
      }
      continue
    }
    if (/^#{1,3}\s+/.test(trim)) {
      if (current.day && current.lines.length === 1 && /^<!--/.test(current.lines[0].trim())) {
        current.lines.push(line)
      } else {
        pushCurrent()
        current = { day: '', marker: '', lines: [line] }
      }
      continue
    }
    const bulletDateMatch = /^[-*]\s+(\d{4}-\d{2}-\d{2})\b/.exec(trim)
    if (!current.day && bulletDateMatch) {
      pushCurrent()
      sections.push({ day: bulletDateMatch[1], marker: '', lines: [line] })
      continue
    }
    current.lines.push(line)
  }
  pushCurrent()
  return sections
}

/**
 * 判断.md 写入前月度归档裁剪：
 * 活跃 判断.md 仅保留未标日期规则与最近 30 天记录，旧月份按月移入 大肥鱼/归档/判断-YYYY-MM.md。
 */
export function archiveJudgeFile(kb, { now = Date.now(), tz = 'Asia/Shanghai', maxDays = JUDGE_ARCHIVE_DAYS } = {}) {
  const rawKb = String(kb || '').trim()
  if (!rawKb) return { ok: false, changed: false, archivedCount: 0, archiveFiles: [] }
  const targetKb = !existsSync(join(rawKb, JUDGE_FILE)) && existsSync(join(rawKb, '大肥鱼', JUDGE_FILE))
    ? join(rawKb, '大肥鱼')
    : rawKb
  const judgePath = join(targetKb, JUDGE_FILE)
  if (!existsSync(judgePath)) {
    return { ok: true, changed: false, judgePath, archivedCount: 0, archiveFiles: [] }
  }

  let cur = ''
  try {
    cur = readFileSync(judgePath, 'utf8')
  } catch {
    return { ok: false, changed: false, judgePath, archivedCount: 0, archiveFiles: [] }
  }

  const sections = splitJudgeSections(cur)
  const ts = Number.isFinite(Number(now)) ? Number(now) : Date.now()
  const nowDay = dayString(ts, tz)
  let refDay = nowDay
  for (const s of sections) {
    if (s.day && s.day > refDay) refDay = s.day
  }

  const kept = []
  const archived = []
  for (const s of sections) {
    if (s.day && judgeDaysBetween(s.day, refDay) > maxDays) {
      archived.push(s)
    } else {
      kept.push(s)
    }
  }

  if (archived.length === 0) {
    return { ok: true, changed: false, judgePath, archivedCount: 0, archiveFiles: [] }
  }

  const archiveDir = join(targetKb, '归档')
  const archiveFiles = []
  try {
    mkdirSync(archiveDir, { recursive: true })
    const byMonth = new Map()
    for (const s of archived) {
      const month = s.day.slice(0, 7)
      if (!byMonth.has(month)) byMonth.set(month, [])
      byMonth.get(month).push(s)
    }
    for (const [month, list] of byMonth.entries()) {
      const archFile = join(archiveDir, `判断-${month}.md`)
      let archCur = existsSync(archFile) ? readFileSync(archFile, 'utf8') : ''
      if (!archCur.trim()) {
        archCur = `# 大肥鱼 · 判断归档（${month}）\n\n> 从 大肥鱼/判断.md 自动归档的历史判断与督办审计。\n`
      }
      const chunks = []
      for (const s of list) {
        if (s.marker && archCur.includes(s.marker)) continue
        const block = s.lines.join('\n').trim()
        if (block) chunks.push(block)
      }
      if (chunks.length) {
        const prefix = archCur.endsWith('\n\n') ? '' : (archCur.endsWith('\n') ? '\n' : '\n\n')
        writeFileSync(archFile, archCur + prefix + chunks.join('\n\n') + '\n', 'utf8')
      }
      archiveFiles.push(archFile)
    }

    const keptText = kept
      .map((s) => s.lines.join('\n').trimEnd())
      .filter((t) => t.length > 0)
      .join('\n\n')
    writeFileSync(judgePath, (keptText || '# 判断') + '\n', 'utf8')
    return {
      ok: true,
      changed: true,
      judgePath,
      archivedCount: archived.length,
      archiveFiles,
    }
  } catch (error) {
    return { ok: false, changed: false, judgePath, error: String((error && error.message) || error), archivedCount: 0, archiveFiles: [] }
  }
}

/**
 * 督办审计部 SOP：绑定 locked_plan_modality_queue -> daily_behavior_audit，晚间回写 判断.md。
 */
export function governDepartmentSop({
  lockedQueue = [],
  verdicts = [],
  telemetry = {},
  kb = '',
  writeNightly = true,
  at = Date.now(),
  tz = 'Asia/Shanghai',
} = {}) {
  const queue = Array.isArray(lockedQueue) ? lockedQueue : []
  const list = Array.isArray(verdicts) ? verdicts : []
  const passed = list.filter((v) => v && v.pass === true).length
  const failed = list.filter((v) => v && v.pass === false).length
  const plannedCount = queue.length
  const adherenceRate = plannedCount > 0 ? Number((passed / plannedCount).toFixed(2)) : 1
  const audit = {
    day: dayString(at, tz),
    plannedCount,
    passedCount: passed,
    failedCount: failed,
    adherenceRate,
    funMinutes: Math.max(0, Math.round(Number(telemetry?.funMinutesToday ?? telemetry?.entertainmentMinutes) || 0)),
    workMinutes: Math.max(0, Math.round(Number(telemetry?.workMinutesToday) || 0)),
  }
  let judgePath = ''
  if (kb && writeNightly) {
    try {
      mkdirSync(kb, { recursive: true })
      archiveJudgeFile(kb, { now: at, tz })
      judgePath = join(kb, JUDGE_FILE)
      const marker = `<!-- govern-audit:${audit.day} -->`
      const cur = existsSync(judgePath) ? readFileSync(judgePath, 'utf8') : ''
      if (!cur.includes(marker)) {
        const line = `\n${marker}\n### ${audit.day} · 督办行为审计\n- 计划局数：${audit.plannedCount} | 通过：${audit.passedCount} | 未过：${audit.failedCount} | 履约率：${Math.round(audit.adherenceRate * 100)}%\n- 客观时长：正事 ${audit.workMinutes} 分钟 / 娱乐 ${audit.funMinutes} 分钟\n`
        if (cur) appendFileSync(judgePath, line, 'utf8')
        else writeFileSync(judgePath, `# 判断\n${line}`, 'utf8')
      }
    } catch {
      judgePath = ''
    }
  }
  return {
    ok: true,
    department: 'dept:govern',
    daily_behavior_audit: audit,
    judgePath,
  }
}
