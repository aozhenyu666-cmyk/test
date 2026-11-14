// dsh-go-companion · 外部 Agent 解耦适配、Scoped App Lease 租约、3 分钟 Emergency Pass 与弹性缓冲降级（M3.3）
//
// 1. 外部 AI 适配层：抽象 Gemini Live / ChatGPT Voice / 自定义应用（包名、引导文本生成、复盘解析统一接口）
// 2. Scoped App Lease：授信期内仅放行租约绑定的目标包 + 紧急白名单；超时未回自动回收严格模式
// 3. 3 分钟 Emergency Pass：临时突发应急通行（每天有上限、全程落账可审计）
// 4. 弹性缓冲期（15–20 分钟）：超出预留起动缓冲仍未启动，标为 scheduling_failure 并自动降级为 3 分钟微局着陆

export const DEFAULT_AGENT_PROFILES = Object.freeze({
  gemini_live: {
    id: 'gemini_live',
    label: 'Gemini Live',
    packages: ['com.google.android.apps.bard', 'com.google.android.googlequicksearchbox'],
    modes: ['study', 'think', 'interrogate'],
  },
  chatgpt: {
    id: 'chatgpt',
    label: 'ChatGPT',
    packages: ['com.openai.chatgpt'],
    modes: ['study', 'think', 'interrogate'],
  },
  custom_app: {
    id: 'custom_app',
    label: '自定义外部Agent',
    packages: [],
    modes: ['study', 'think'],
  },
})

export const DEFAULT_LEASE_MS = 30 * 60_000
export const MAX_LEASE_MS = 120 * 60_000
export const EMERGENCY_PASS_MS = 3 * 60_000
export const DAILY_EMERGENCY_PASS_CAP = 3
export const MIN_ELASTIC_BUFFER_MIN = 15
export const MAX_ELASTIC_BUFFER_MIN = 20
export const MICRO_BOUT_MINUTES = 3

const pkgValid = (p) => typeof p === 'string' && /^[A-Za-z0-9_.]{1,160}$/.test(p.trim())

export function emptyExternalState() {
  return {
    v: 1,
    lease: null,
    emergencyPass: { until: 0, grantedAt: 0, reason: '', day: '', countToday: 0 },
  }
}

export function cleanExternalState(raw) {
  const s = raw && typeof raw === 'object' ? raw : {}
  const leaseRaw = s.lease && typeof s.lease === 'object' ? s.lease : null
  const lease = leaseRaw && Number(leaseRaw.expiresAt) > 0
    ? {
        id: String(leaseRaw.id || 'lease'),
        agentId: String(leaseRaw.agentId || 'custom_app'),
        packages: (Array.isArray(leaseRaw.packages) ? leaseRaw.packages : []).filter(pkgValid),
        topic: String(leaseRaw.topic || '').slice(0, 120),
        issuedAt: Number(leaseRaw.issuedAt) || 0,
        expiresAt: Number(leaseRaw.expiresAt) || 0,
      }
    : null
  const ep = s.emergencyPass && typeof s.emergencyPass === 'object' ? s.emergencyPass : {}
  return {
    v: 1,
    lease,
    emergencyPass: {
      until: Math.max(0, Number(ep.until) || 0),
      grantedAt: Math.max(0, Number(ep.grantedAt) || 0),
      reason: String(ep.reason || '').slice(0, 80),
      day: String(ep.day || '').slice(0, 16),
      countToday: Math.max(0, Math.round(Number(ep.countToday) || 0)),
    },
  }
}

/** 根据 profileId 与自定义配置解析外部 Agent 适配器信息。 */
export function resolveExternalAgent(agentId = 'gemini_live', customConfigs = {}) {
  const id = String(agentId || 'gemini_live').trim()
  const base = DEFAULT_AGENT_PROFILES[id] || DEFAULT_AGENT_PROFILES.custom_app
  const custom = customConfigs && typeof customConfigs === 'object' ? customConfigs[id] : null
  const customPkgs = Array.isArray(custom?.packages) ? custom.packages.filter(pkgValid) : []
  return {
    id,
    label: String(custom?.label || base.label),
    packages: customPkgs.length ? customPkgs : [...base.packages],
    modes: Array.isArray(custom?.modes) && custom.modes.length ? custom.modes : [...base.modes],
  }
}

/** 生成与特定外部 Agent 适配的简要协同指令与复盘提取格式。 */
export function buildAgentHandoffPrompt({ agentId = 'gemini_live', topic = '', minutes = 25, mode = 'think', customConfigs = {} } = {}) {
  const profile = resolveExternalAgent(agentId, customConfigs)
  return [
    `【交给 ${profile.label} 的协同任务】`,
    `主题：${String(topic || '完成当前主线攻坚').trim().slice(0, 120)}`,
    `模式：${mode} · 租约时长：${Math.max(5, Math.min(120, Math.round(Number(minutes) || 25)))} 分钟`,
    '结尾请输出一行复盘：〔复盘〕完成：... 卡点：... 下一步：...',
  ].join('\n')
}

export function extractAgentRecap(text = '') {
  const s = String(text || '')
  const m = /(?:〔|【)复盘(?:〕|】)\s*([\s\S]{4,400})/.exec(s)
  return m ? m[1].trim() : ''
}

/** 颁发一条带作用域和 TTL 的 App 临时访问租约（Scoped App Lease）。 */
export function issueAppLease(rawState, { agentId = 'gemini_live', packages = [], ttlMs = DEFAULT_LEASE_MS, topic = '', now = Date.now(), customConfigs = {} } = {}) {
  const state = cleanExternalState(rawState)
  const profile = resolveExternalAgent(agentId, customConfigs)
  const pkgs = [...new Set([...(Array.isArray(packages) ? packages.filter(pkgValid) : []), ...profile.packages])]
  if (!pkgs.length) return { ok: false, error: 'no-packages', state }
  const duration = Math.max(60_000, Math.min(MAX_LEASE_MS, Number(ttlMs) || DEFAULT_LEASE_MS))
  const lease = {
    id: `lease-${now}`,
    agentId: profile.id,
    packages: pkgs,
    topic: String(topic || '').trim().slice(0, 120),
    issuedAt: now,
    expiresAt: now + duration,
  }
  return { ok: true, lease, state: { ...state, lease } }
}

export function activeAppLease(rawState, now = Date.now()) {
  const state = cleanExternalState(rawState)
  if (!state.lease || state.lease.expiresAt <= now) return null
  return state.lease
}

export function isAppLeased(rawState, pkg, now = Date.now()) {
  const lease = activeAppLease(rawState, now)
  if (!lease || !pkg) return false
  return lease.packages.includes(String(pkg).trim())
}

export function revokeAppLease(rawState, reason = 'manual-revoke') {
  const state = cleanExternalState(rawState)
  const prev = state.lease
  return { hadLease: Boolean(prev), reason, prevLease: prev, state: { ...state, lease: null } }
}

/** 3 分钟应急通行（Emergency Pass），超限额或无原因时拒绝。 */
export function grantEmergencyPass(rawState, { reason = '', day = '', maxPerDay = DAILY_EMERGENCY_PASS_CAP, durationMs = EMERGENCY_PASS_MS, now = Date.now() } = {}) {
  const state = cleanExternalState(rawState)
  const cleanReason = String(reason || '').trim().slice(0, 80)
  if (!cleanReason) return { ok: false, error: 'reason-required', state }
  const dayKey = day || new Date(now).toISOString().slice(0, 10)
  const used = state.emergencyPass.day === dayKey ? state.emergencyPass.countToday : 0
  const cap = Math.max(1, Math.min(10, Number(maxPerDay) || DAILY_EMERGENCY_PASS_CAP))
  if (used >= cap) return { ok: false, error: 'daily-cap-reached', countToday: used, cap, state }
  const until = now + Math.max(30_000, Math.min(5 * 60_000, Number(durationMs) || EMERGENCY_PASS_MS))
  const nextEp = { until, grantedAt: now, reason: cleanReason, day: dayKey, countToday: used + 1 }
  return {
    ok: true,
    until,
    countToday: nextEp.countToday,
    remainingToday: cap - nextEp.countToday,
    state: { ...state, emergencyPass: nextEp },
  }
}

export function isEmergencyPassActive(rawState, now = Date.now()) {
  const state = cleanExternalState(rawState)
  return state.emergencyPass.until > now
}

/**
 * 弹性缓冲期（15–20 分钟）与 scheduling_failure 降级：
 * 到计划起跑时间超出 bufferMin 仍未开局，判定为调度过载（scheduling_failure），
 * 直接自动降级出一个 3 分钟微局着陆包，避免陷入无效说教。
 */
export function evaluateElasticBuffer({ plannedAt = 0, startedAt = 0, now = Date.now(), bufferMin = MAX_ELASTIC_BUFFER_MIN } = {}) {
  const planned = Number(plannedAt) || 0
  const started = Number(startedAt) || 0
  if (planned <= 0 || started > 0) return { exceeded: false, overdueMin: 0, classification: '', fallbackBout: null }
  const clampedMin = Math.max(MIN_ELASTIC_BUFFER_MIN, Math.min(MAX_ELASTIC_BUFFER_MIN, Number(bufferMin) || MAX_ELASTIC_BUFFER_MIN))
  const overdueMs = now - planned
  if (overdueMs < clampedMin * 60_000) {
    return { exceeded: false, overdueMin: Math.max(0, Math.round(overdueMs / 60_000)), classification: '', fallbackBout: null }
  }
  return {
    exceeded: true,
    overdueMin: Math.round(overdueMs / 60_000),
    classification: 'scheduling_failure',
    fallbackBout: {
      protocolId: 'micro_landing_3m',
      minutes: MICRO_BOUT_MINUTES,
      department: 'dept:dispatch',
      why: `超出 ${clampedMin} 分钟弹性缓冲未启动，已归因 scheduling_failure，降级为 3 分钟微局着陆。`,
    },
  }
}
