// dsh-go-companion · 三块固定 Core Memory 装配（M1.1，总预算 <= 1800 tokens）
//
// Block A：静态人设底稿与生物钟状态（不常变，留住前缀缓存稳定性）
// Block B：运行态 Telemetry 快照（严格 ≤5 行：当前状态、正在进行的局、屏幕App概况、租约/哨兵、放风/信用分；绝不存原文截屏）
// Block C：按需挂载的 Lorebook / 记忆槽位（≤3 条）

import { hm } from './tz.js'

export const CORE_TOKEN_LIMIT = 1800
export const MAX_TELEMETRY_LINES = 5
export const MAX_LOREBOOK_SLOTS = 3

/**
 * 粗略但稳定的中英混合 token 估算：
 * 中文字符按 ~1.35 token 计（保守不低估），ASCII 单词按 length/3.5 + 1，换行与标点按 0.6。
 * @param {string} text
 */
export function estimateTokens(text) {
  const s = String(text || '')
  if (!s) return 0
  let cjk = 0
  let other = 0
  for (const ch of s) {
    if (/[\u4e00-\u9fff]/.test(ch)) cjk += 1
    else other += 1
  }
  return Math.ceil(cjk * 1.35 + other * 0.42)
}

/** 生物钟标签：按目标时区小时数判断，不写死时间字符串猜测。 */
export function circadianLabel(now = Date.now(), timeZone = 'Asia/Shanghai') {
  let hour = 12
  try {
    const parts = new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(now))
    const h = Number(parts.find((p) => p.type === 'hour')?.value)
    if (Number.isFinite(h)) hour = h
  } catch {
    hour = new Date(now).getHours()
  }
  if (hour >= 0 && hour < 6) return `深夜(${hm(now)})·高风险走神/应强制收手机`
  if (hour < 11) return `晨间(${hm(now)})·立项启动窗口`
  if (hour < 14) return `午间(${hm(now)})·休整与切换`
  if (hour < 18) return `午后(${hm(now)})·主攻推进期`
  if (hour < 22) return `晚间(${hm(now)})·收尾与对账`
  return `睡前(${hm(now)})·收敛收手机`
}

const cleanOne = (v, max = 90) => String(v == null ? '' : v).replace(/\s+/g, ' ').trim().slice(0, max)

/**
 * Block B：严格 ≤5 行的状态摘要（只放事实数字与状态词，不带屏幕正文或情绪废话）。
 * @returns {string[]}
 */
export function buildTelemetrySnapshot(input = {}, now = Date.now()) {
  const tz = String(input.timeZone || 'Asia/Shanghai')
  const state = cleanOne(input.state || 'standby', 24)
  const dept = cleanOne(input.department || '', 24)
  const bout = input.bout
  const boutText = bout && bout.protocolId
    ? `局:${cleanOne(bout.protocolId, 28)}(${bout.department ? cleanOne(bout.department, 16) : '未分部'})`
    : '无进行中的局'
  const phone = input.phone
  const appText = phone?.app
    ? `前台:${cleanOne(phone.label || phone.app, 24)} ${Math.round(Number(phone.minutes) || 0)}m` +
      (phone.fun ? '[娱乐]' : phone.work ? '[正事]' : '') +
      (Number(phone.funMinutesToday) > 0 ? ` 今日娱乐${Math.round(Number(phone.funMinutesToday))}m` : '')
    : '前台:未知'
  const guardParts = []
  if (input.withdrawalUntil && Number(input.withdrawalUntil) > now) guardParts.push('冷却撤回中')
  if (input.leaseApp) guardParts.push(`租约:${cleanOne(input.leaseApp, 24)}`)
  if (input.emergencyPassUntil && Number(input.emergencyPassUntil) > now) guardParts.push('紧急放行中')
  if (input.contextExempt) guardParts.push(`豁免:${cleanOne(input.contextExempt, 20)}`)
  if (Number.isFinite(Number(input.credit))) guardParts.push(`信用${Math.round(Number(input.credit))}`)
  if (Number.isFinite(Number(input.earnedMin)) && Number(input.earnedMin) > 0) guardParts.push(`挣得娱乐${Math.round(Number(input.earnedMin))}m`)

  const lines = [
    `[状态] ${state}${dept ? ' / ' + dept : ''} · ${circadianLabel(now, tz)}`,
    `[局] ${boutText}`,
    `[设备] ${appText}`,
    `[护栏] ${guardParts.length ? guardParts.join(' · ') : '正常监控'}`,
  ]
  if (input.extraLine) lines.push(`[焦点] ${cleanOne(input.extraLine, 100)}`)
  return lines.slice(0, MAX_TELEMETRY_LINES)
}

/**
 * 装配三块固定 Core Memory 并在 maxTokens（默认 1800）内优雅降级裁剪：
 * 优先保留 Block A + Block B，再按顺序塞入 ≤3 条 Block C Lorebook；超额时先裁 Block C，再压缩 Block A 次要细节。
 */
export function buildCoreMemoryBlocks(input = {}, now = Date.now()) {
  const maxTokens = Math.max(400, Number(input.maxTokens) || CORE_TOKEN_LIMIT)
  const blockALines = [
    input.persona ? cleanOne(input.persona, 900) : '',
    input.selfDigest ? '核心规则：' + cleanOne(input.selfDigest, 500) : '',
    ...(Array.isArray(input.coreConstraints) ? input.coreConstraints.map((l) => cleanOne(l, 220)).filter(Boolean) : []),
  ].filter(Boolean)

  const blockBLines = buildTelemetrySnapshot(input.telemetry || input, now)

  const rawLore = Array.isArray(input.lorebook) ? input.lorebook : []
  const blockCLines = rawLore
    .slice(0, MAX_LOREBOOK_SLOTS)
    .map((item) => {
      const txt = typeof item === 'string' ? item : item?.text
      return txt ? '- ' + cleanOne(txt, 160) : ''
    })
    .filter(Boolean)

  let blockA = blockALines.join('\n')
  const blockB = '【实时快照】\n' + blockBLines.join('\n')
  let loreUsed = [...blockCLines]

  const render = () => {
    const parts = [blockA, blockB]
    if (loreUsed.length) parts.push('【情境规则/记忆】\n' + loreUsed.join('\n'))
    return parts.filter(Boolean).join('\n\n')
  }

  let text = render()
  while (estimateTokens(text) > maxTokens && loreUsed.length > 0) {
    loreUsed.pop()
    text = render()
  }
  while (estimateTokens(text) > maxTokens && blockALines.length > 1) {
    blockALines.pop()
    blockA = blockALines.join('\n')
    text = render()
  }
  if (estimateTokens(text) > maxTokens) {
    const budgetChars = Math.max(200, Math.floor(maxTokens * 0.72) - blockB.length - 12)
    blockA = blockA.slice(0, budgetChars)
    text = render()
  }

  return {
    text,
    tokens: estimateTokens(text),
    withinBudget: estimateTokens(text) <= maxTokens,
    telemetryLines: blockBLines.length,
    lorebookCount: loreUsed.length,
    blocks: { A: blockA, B: blockB, C: loreUsed },
  }
}
