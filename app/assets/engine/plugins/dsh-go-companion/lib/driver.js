// dsh-go-companion · 推进器（2026-11-18）
//
// 代码决定「此刻体系该往前推哪一步」，两条鱼决定怎么说、怎么做。纯函数，不调模型。
//
// 三档：
//   partner  伙伴：清醒、在推进——不推，交给平时的随机发球
//   push     推手：在滑 / 局之间空着 / 计划没排——推进器给一件事，能撤回的直接做
//   takeover 接管：陷住 / 额度用完还在刷 / 通行证连续偏航 / 契约逾期——不闭嘴、换语音、锁先落、给回位动作
//
// 每一种推进都有冷却，不会每分钟推一次；所有推进都进账本（state: 'drive'）。

export const TAKEOVER_MAX_MS = 45 * 60_000
export const TAKEOVER_MAX_PER_DAY = 3
export const TAKEOVER_COOLDOWN_MS = 30 * 60_000
export const TAKEOVER_SPEAK_GAP_MS = 4 * 60_000
export const RETURN_DWELL_MIN = 3
export const BOUT_GAP_PUSH_MS = 5 * 60_000
export const BOUT_GAP_AUTO_MS = 10 * 60_000
export const CHECKPOINT_WARN_MS = 10 * 60_000
export const WORK_FROM_MIN = 9 * 60
export const WORK_TO_MIN = 21 * 60 + 30

const n = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0)

/**
 * 档位。
 * @param {object} s
 * @param {string} s.tideBand clear | slide | sink | trap
 * @param {string} s.frontTier 前台 App 的分层（base/work/dual/fun/game/other）
 * @param {number} s.frontMinutes 前台停留分钟
 * @param {boolean} s.quotaOut 娱乐或游戏额度用完
 * @param {number} s.leaseDrifts30m 30 分钟内通行证偏航次数
 * @param {boolean} s.contractOverdue 契约逾期转严格
 * @param {boolean} s.boutGap 工作时段里局之间空着
 * @param {boolean} s.noPlan 还没有今天的计划
 * @param {boolean} s.switchDice 切换次数到了摇骰线
 */
export function driveMode(s = {}) {
  const inFun = s.frontTier === 'fun' || s.frontTier === 'game'
  const reasons = []
  if (s.tideBand === 'trap') reasons.push('陷住了（水位 ' + (s.tideLevel ?? '高') + '）')
  if (s.quotaOut && inFun && n(s.frontMinutes) >= 5) reasons.push('额度用完还在刷 ' + Math.round(n(s.frontMinutes)) + ' 分钟')
  if (n(s.leaseDrifts30m) >= 2) reasons.push('通行证 30 分钟内偏航 ' + n(s.leaseDrifts30m) + ' 次')
  if (s.contractOverdue) reasons.push('契约验收点逾期')
  if (reasons.length) return { mode: 'takeover', why: reasons.join('；') }
  const push = []
  if (s.tideBand === 'sink' || s.tideBand === 'slide') push.push('在滑（' + (s.tideBand === 'sink' ? '在沉' : '在滑') + '）')
  if (s.boutGap) push.push('局之间空着')
  if (s.noPlan) push.push('今天还没计划')
  if (s.switchDice) push.push('来回切 App')
  if (push.length) return { mode: 'push', why: push.join('；') }
  return { mode: 'partner', why: '' }
}

/**
 * 接管状态机：开始 / 继续 / 解除。
 * @param {object} prev 上一次的接管状态 {active, since, reason, countDay, count, releasedAt}
 * @param {object} p { now, day, mode, why, frontTier, frontMinutes, boutRunning, receiptJustNow, blocked }
 *   blocked：紧急解除 / 紧急通行 / 深夜 / 安静时段 / 连麦中 —— 这些时候不进接管
 */
export function stepTakeover(prev, p = {}) {
  const now = n(p.now)
  const day = String(p.day || '')
  const s = { active: false, since: 0, reason: '', count: 0, countDay: day, releasedAt: 0, ...(prev || {}) }
  if (s.countDay !== day) Object.assign(s, { countDay: day, count: 0 })
  if (s.active) {
    const returned = p.boutRunning === true || p.receiptJustNow === true ||
      ((p.frontTier === 'work') && n(p.frontMinutes) >= RETURN_DWELL_MIN)
    if (p.blocked) return { state: { ...s, active: false, releasedAt: now }, event: 'end', why: '让路（紧急解除 / 深夜 / 连麦）' }
    if (returned) return { state: { ...s, active: false, releasedAt: now }, event: 'end', why: '回位了' }
    if (now - n(s.since) >= TAKEOVER_MAX_MS) return { state: { ...s, active: false, releasedAt: now }, event: 'end', why: '到 45 分钟上限' }
    return { state: s, event: 'hold', why: s.reason }
  }
  if (p.mode !== 'takeover' || p.blocked) return { state: s, event: null, why: '' }
  if (s.count >= TAKEOVER_MAX_PER_DAY) return { state: s, event: 'capped', why: '今天已经接管 ' + s.count + ' 次' }
  if (n(s.releasedAt) && now - n(s.releasedAt) < TAKEOVER_COOLDOWN_MS) return { state: s, event: null, why: '刚解除，冷却中' }
  return { state: { ...s, active: true, since: now, reason: String(p.why || ''), count: s.count + 1 }, event: 'start', why: String(p.why || '') }
}

/**
 * 此刻该推的一件事（伙伴档返回 null）。
 * @param {object} s
 *   now, minuteOfDay, mode,
 *   boutRunning, lastBoutEndAt,
 *   planItems: [{id, title, minute, openedAt, missed}]（今天的局表）
 *   checkpoints: [{id, title, dueAt, status}]
 *   deadlines: [{id, title, daysLeft}]
 *   boutForDeadlineToday: Set/Array of deadline ids 今天已经为它开过局 / 推过
 *   memo: {kind:key → at} 已经推过的（冷却）
 */
export function nextDrive(s = {}) {
  const now = n(s.now)
  const min = n(s.minuteOfDay)
  const memo = s.memo || {}
  const seen = (key, ms) => n(memo[key]) && now - n(memo[key]) < ms
  const inWork = min >= WORK_FROM_MIN && min < WORK_TO_MIN

  // 1. 契约验收点 10 分钟内到点
  for (const cp of Array.isArray(s.checkpoints) ? s.checkpoints : []) {
    if (!cp || cp.status === 'passed' || !n(cp.dueAt)) continue
    const left = n(cp.dueAt) - now
    if (left > 0 && left <= CHECKPOINT_WARN_MS && !seen('cp:' + cp.id, 60 * 60_000)) {
      return { kind: 'checkpoint', key: 'cp:' + cp.id, role: 'strategist',
        text: '契约验收点「' + cp.title + '」还有 ' + Math.max(1, Math.round(left / 60_000)) + ' 分钟到点：说清交什么、交到哪，现在就开始收尾。' }
    }
  }
  if (s.mode === 'partner') return null

  // 2. 截止日 0 / 1 / 3 / 7 天，且今天还没为它推过
  const pushedDl = new Set(Array.isArray(s.deadlineDone) ? s.deadlineDone : [])
  for (const d of Array.isArray(s.deadlines) ? s.deadlines : []) {
    if (!d || ![0, 1, 3, 7].includes(n(d.daysLeft)) || pushedDl.has(d.id)) continue
    if (seen('dl:' + d.id, 20 * 3_600_000)) continue
    return { kind: 'deadline', key: 'dl:' + d.id, role: 'strategist',
      text: '截止日「' + d.title + '」' + (n(d.daysLeft) === 0 ? '就是今天' : '还剩 ' + n(d.daysLeft) + ' 天') + '：问他今天为它做了什么；没做就现在排一局（open_bout 或 plan_bouts），第一步要具体。' }
  }

  // 3. 局之间空着（工作时段、没有局在跑）
  if (inWork && !s.boutRunning) {
    const items = Array.isArray(s.planItems) ? s.planItems : []
    const next = items.filter((i) => i && !i.openedAt && !i.missed && n(i.minute) >= min - 5).sort((a, b) => n(a.minute) - n(b.minute))[0]
    const endAt = n(s.lastBoutEndAt)
    const gapKey = 'gap:' + (endAt || 'none') + ':' + (next?.id || 'none')
    const soon = next && n(next.minute) - min <= 10
    if (!soon && endAt && now - endAt >= BOUT_GAP_PUSH_MS) {
      if (next && now - endAt >= BOUT_GAP_AUTO_MS && !seen('auto:' + next.id, 3 * 3_600_000)) {
        // 推过了还没开：代码直接把今天计划里的下一局提前开掉。
        return { kind: 'auto-open', key: 'auto:' + next.id, role: 'strategist', itemId: next.id,
          text: '下一局「' + next.title + '」已经提前开了（上一局收了 ' + Math.round((now - endAt) / 60_000) + ' 分钟没动）：一句话告诉他第一步。' }
      }
      if (!seen(gapKey, 60 * 60_000)) {
        return { kind: 'bout-gap', key: gapKey, role: 'strategist', itemId: next?.id || '',
          text: next
            ? '上一局收了 ' + Math.round((now - endAt) / 60_000) + ' 分钟，下一局「' + next.title + '」排在 ' + hmOf(next.minute) + '：问他要不要现在就开（5 分钟内不回就直接提前开）。'
            : '上一局收了 ' + Math.round((now - endAt) / 60_000) + ' 分钟，今天后面没排局：从计划、截止日、笔记里挑一件，用 open_bout 直接开（工作包写全），开完告诉他第一步。' }
      }
    }
  }

  // 4. 推手档的空档：鱼丸来（题 / 骰子 / 闲聊三选一，交给她）
  if (s.mode === 'push' && !s.boutRunning && !seen('fill', 40 * 60_000)) {
    return { kind: 'fill', key: 'fill', role: 'companion',
      text: '他在滑、手上没局：随机挑一种把他拉一下——一道思维题、摇一骰子（roll_six_dice）连 Gemini、或者只是损他两句；别催进度。' }
  }
  return null
}

function hmOf(min) {
  const m = Math.max(0, Math.round(n(min)))
  return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0')
}

/** 接管时给的回位动作（一句话，代码定，不让模型现编）。 */
export function returnAction({ nextItem = null, workApp = '' } = {}) {
  if (nextItem?.title) return '开「' + nextItem.title + '」这一局，做完第一步' + (nextItem.first ? '：' + String(nextItem.first).slice(0, 40) : '')
  if (workApp) return '打开' + workApp + '，待满 3 分钟做一件具体的事'
  return '连 Gemini 说清楚接下来 30 分钟唯一要做的事，并出回执'
}
