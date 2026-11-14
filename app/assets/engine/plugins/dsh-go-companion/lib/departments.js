// dsh-go-companion · 四部内部隐式自动路由（M4.1）
//
// 对齐 aozhenyu666-cmyk/mouhua-thinking 的 operating_protocol.md 第 8 节：
//   dept:collect    信息收集部（未知事实/新材料未核验/现场线索整理）
//   dept:strategize 谋划思考部（前提可疑/因果争议/替代解释与反证）
//   dept:dispatch   统筹规划部（优先级取舍/时间预算冲突/工作包排期与合同调度）
//   dept:govern     督办审计部（局中执行/交付物验收/规则与锁的执行审计）
//
// 原则：用户绝不手工选部门，由系统依据输入缺口与当前状态自动流转并写入 bouts.department 与 ledger。

import { updateBout } from './bouts.js'
import { appendLedger } from './ledger.js'

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
