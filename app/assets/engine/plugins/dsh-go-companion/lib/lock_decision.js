// dsh-go-companion · 裁决器的唯一输出（2026-11-18）
//
// 统一裁决器（unified_arbiter.js，P0–P6）先定「现在是什么状态」，这里把它变成手机能直接照着执法的一份：
//
//   mode = open       全部放行（紧急通行 / 紧急解除）
//   mode = allowlist  只许 allow 里的（加系统底座）；needPass 里的双用途 App 要先申请通行证
//   mode = free       只锁 deny 里的；needPass 里的要先申请
//
// 手机对任何一个 App 只走一段逻辑：系统底座放行 → open 放行 → allowlist 看 allow → free 看 deny。
// 每一份都带 why（为什么）和 unlockHow（怎么解），锁页显示的就是它，和执法永远一致。
// 原来那些锁（雷达、姐妹力度、切换次数、契约、专时、连麦、深夜）都只是这里的输入。

import { isBasePkg, WORK_DEFAULT } from './app_tiers.js'
import { GEMINI_PACKAGES } from './gemini.js'
import { isNeverLockPkg, PROXY_PACKAGES } from './never_lock.js'

const uniq = (xs) => [...new Set(xs.filter((x) => typeof x === 'string' && x.trim()).map((x) => x.trim()))]
const safeBase = (pkg) => isBasePkg(pkg) || isNeverLockPkg(pkg)
const nonEmpty = (s, fallback) => (typeof s === 'string' && s.trim() ? s.trim() : fallback)

/**
 * @param {object} p
 * @param {object} p.arbiter      computeUnifiedLockArbiter 的结果
 * @param {object} p.work         { active, why, allow[] }：局 / 契约 / 忙时 / 专时 有没有在干活，各自声明的 App
 * @param {Array}  p.installed    手机上的 App [{pkg,label}]
 * @param {function} p.tierOf     (pkg,label) => base|work|dual|fun|game|other
 * @param {object} p.quota        computeQuota 的结果
 * @param {object} p.lease        当前通行证 { app, until, goal }
 * @param {Array}  p.radarDeny    雷达 / 姐妹 / 切换次数临时锁的 [{app, until, why}]
 */
export function buildLockDecision({ arbiter = null, work = null, installed = [], tierOf = () => 'other', quota = null,
  lease = null, radarDeny = [], now = Date.now() } = {}) {
  const a = arbiter || { priority: 'P6', source: 'AUTONOMY_RELAXED', label: '自主推进态' }
  const pkgs = (Array.isArray(installed) ? installed : []).map((x) => ({ pkg: String(x?.pkg || ''), label: String(x?.label || '') })).filter((x) => x.pkg)
  const ofTier = (t) => pkgs.filter((x) => tierOf(x.pkg, x.label) === t).map((x) => x.pkg)
  const leaseApp = lease && Number(lease.until) > now && lease.app ? String(lease.app) : ''
  const base = {
    v: 1,
    priority: a.priority,
    source: a.source,
    label: a.label,
    quota: quota ? { funLeft: quota.funLeft, gameLeft: quota.gameLeft, funOut: quota.funOut, gameOut: quota.gameOut, line: quota.line } : null,
    lease: leaseApp ? { app: leaseApp, until: Number(lease.until), goal: String(lease.goal || '') } : null,
  }

  if (a.priority === 'P0') {
    return { ...base, mode: 'open', allow: [], deny: [], needPass: [], why: nonEmpty(a.why, '紧急放行中'), unlockHow: nonEmpty(a.unlockHow, '倒计时结束后恢复') }
  }

  // P1–P4（含 P3.5 接管）：裁决器已经给了「只许哪些」。连麦舱（P2/GEMINI_LIVE_CELL）补上 Gemini 和代理；接管（P3.5/TAKEOVER_STRICT）补工作应用。
  if (['P1', 'P2', 'P3', 'P3.5', 'P4'].includes(a.priority)) {
    const strictSources = new Set(['NIGHT_CURFEW', 'GEMINI_LIVE_CELL', 'CONTRACT_OVERDUE_STRICT', 'TAKEOVER_STRICT', 'BOUT_STRICT'])
    const role = strictSources.has(a.source)
      ? a.source
      : (a.priority === 'P1' ? 'NIGHT_CURFEW'
        : a.priority === 'P2' ? 'GEMINI_LIVE_CELL'
          : a.priority === 'P3' ? 'CONTRACT_OVERDUE_STRICT'
            : a.priority === 'P3.5' ? 'TAKEOVER_STRICT'
              : a.priority === 'P4' ? 'BOUT_STRICT' : '')
    const extra = role === 'GEMINI_LIVE_CELL'
      ? [...GEMINI_PACKAGES, ...PROXY_PACKAGES]
      : (role === 'TAKEOVER_STRICT' ? [...WORK_DEFAULT, ...ofTier('work'), ...GEMINI_PACKAGES, ...PROXY_PACKAGES] : [])
    const withLease = (role === 'BOUT_STRICT' || role === 'TAKEOVER_STRICT') && leaseApp ? [leaseApp] : []
    const allow = Array.isArray(a.allow) && !a.allow.includes('*') ? a.allow : []
    const needPass = (role === 'BOUT_STRICT' || role === 'TAKEOVER_STRICT')
      ? ofTier('dual').filter((p) => p !== leaseApp && !allow.includes(p) && !safeBase(p))
      : []
    return {
      ...base,
      mode: 'allowlist',
      allow: uniq([...allow, ...extra, ...withLease]),
      deny: [],
      needPass,
      why: nonEmpty(a.why, nonEmpty(a.label, '规则严格锁定中')),
      unlockHow: nonEmpty(a.unlockHow, '完成本轮任务或到期后自动解锁'),
    }
  }

  // 干活态（契约柔性期、局、忙时、专时）：白名单 = 工作层 + 这一段声明的 App + 手上的通行证。
  if (a.priority === 'P5' || work?.active) {
    const allow = uniq([
      ...WORK_DEFAULT, ...ofTier('work'),
      ...(Array.isArray(a.allow) && !a.allow.includes('*') ? a.allow : []),
      ...(Array.isArray(work?.allow) ? work.allow : []),
      ...(leaseApp ? [leaseApp] : []),
    ])
    return {
      ...base,
      mode: 'allowlist',
      allow,
      deny: [],
      needPass: ofTier('dual').filter((p) => !allow.includes(p) && !safeBase(p)),
      why: nonEmpty(work?.why || a.why, '正在干活：只放行工作 App 和这一局声明的 App'),
      unlockHow: '双用途 App 先跟鲸澜说清用途领通行证；娱乐等收工或进自由态再用',
    }
  }

  // 自由态：只锁临时锁、额度用完的那一层（底座与永不锁 App 绝不进 deny / needPass）。
  const q = quota || { funOut: false, gameOut: false }
  const deny = []
  for (const d of Array.isArray(radarDeny) ? radarDeny : []) {
    const pkg = String(d?.app || '').trim()
    if (pkg && !safeBase(pkg) && (!d.until || Number(d.until) > now)) {
      deny.push({ app: pkg, until: Number(d.until) || 0, why: nonEmpty(d.why, '临时锁') })
    }
  }
  if (q.funOut) for (const p of ofTier('fun')) if (!safeBase(p)) deny.push({ app: p, until: 0, why: '今天的娱乐额度用完了' })
  if (q.gameOut) for (const p of ofTier('game')) if (!safeBase(p)) deny.push({ app: p, until: 0, why: '今天的游戏额度用完了' })
  const seen = new Set()
  const denyUniq = deny.filter((d) => (seen.has(d.app) ? false : seen.add(d.app)))
  const needPassFree = q.funOut ? ofTier('dual').filter((p) => p !== leaseApp && !safeBase(p)) : []
  return {
    ...base,
    mode: 'free',
    allow: [],
    deny: denyUniq,
    needPass: needPassFree,
    why: nonEmpty(q.line, denyUniq.length ? (denyUniq[0].why || '临时锁定中') : '自由态'),
    unlockHow: q.funOut
      ? '额度用完：通过一局验收 +15 分钟，或明天再来'
      : (denyUniq.length ? '临时锁：到期自动解除，或回到正事 App / 使用紧急放行' : '额度内随便用'),
  }
}

/** 手机那段逻辑的 JS 版（测试和对比用；Kotlin 侧同构）。返回 {lock, why, pass?}。 */
export function decideApp(decision, pkg, isBase = () => false) {
  if (!pkg || isBase(pkg) || safeBase(pkg)) return { lock: false, why: 'base' }
  const d = decision || { mode: 'open' }
  if (d.mode === 'open') return { lock: false, why: 'open' }
  if ((d.needPass || []).includes(pkg)) return { lock: true, why: '先跟鲸澜申请通行证', pass: true }
  if (d.mode === 'allowlist') return (d.allow || []).includes(pkg) ? { lock: false, why: 'allow' } : { lock: true, why: nonEmpty(d.why, '白名单锁定中') }
  const hit = (d.deny || []).find((x) => x.app === pkg)
  return hit ? { lock: true, why: nonEmpty(hit.why, '临时锁') } : { lock: false, why: 'free' }
}
