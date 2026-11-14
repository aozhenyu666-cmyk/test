// dsh-go-companion · 本地决策（零模型）
//
// 原生闹钟只负责“到点检查”；这里回答四个问题：开没开、静不静、额度够不够、掷骰中没中。
// 未命中不消耗模型，这是“高频但不烧钱”的关键。

import { randomInt } from 'node:crypto'
import { DEFAULT_TZ, atLocalHour, minuteOfDay as zoneMinuteOfDay, startOfDay } from './tz.js'

export function minuteOfDay(now, tz = DEFAULT_TZ) {
  return zoneMinuteOfDay(now, tz)
}

export function inQuiet(now, startHour, endHour, tz = DEFAULT_TZ) {
  const start = Number.isFinite(Number(startHour)) ? Math.round(Number(startHour)) : 1
  const end = Number.isFinite(Number(endHour)) ? Math.round(Number(endHour)) : 8
  if (start === end) return false
  const m = zoneMinuteOfDay(now, tz)
  const a = start * 60
  const b = end * 60
  return a < b ? m >= a && m < b : m >= a || m < b
}

function quietEndAt(now, startHour, endHour, tz) {
  const end = Number.isFinite(Number(endHour)) ? Math.round(Number(endHour)) : 8
  return atLocalHour(now, end, tz)
}

function nextDayAt(now, tz) {
  return startOfDay(now, tz) + 86_400_000
}

/**
 * @param {{config: object, now: number, topic: object|null, sentToday: number, sentLastHour?: number,
 *          lastAt: number, roll: number, silence?: {stage: string}, mutedUntil?: number}} input
 * @returns {{speak: boolean, reason: string, topic: object|null, nextCheckAt?: number, stage?: string}}
 */
export function plan(input) {
  const c = input.config || {}
  const now = Number(input.now) || Date.now()
  const tz = input.tz || DEFAULT_TZ
  const minGap = Math.max(0, Number(c.minGapMs) || 0)
  const fromTopic = Number(input.nextTopicAt) || 0
  const stage = String(input.silence?.stage || 'none')
  if (c.enabled === false) {
    return { speak: false, reason: 'disabled', topic: null, nextCheckAt: now + 6 * 3_600_000 }
  }
  // 他说"安静一小时""今天别找我"，就准时闭嘴，别再问。
  const muteUntil = Number(input.mutedUntil) || 0
  if (muteUntil > now) {
    return { speak: false, reason: 'muted', topic: null, nextCheckAt: muteUntil }
  }
  if (inQuiet(now, c.quietStart, c.quietEnd, tz)) {
    return { speak: false, reason: 'quiet', topic: null, nextCheckAt: quietEndAt(now, c.quietStart, c.quietEnd, tz) }
  }
  // 阶梯走完还没接：这一轮停下，不再追。歇够（最后一球后 3 小时）再重新开始。
  if (stage === 'stop') {
    const rest = Number(input.silence?.nextAt) > now ? Number(input.silence.nextAt) : now + 3 * 3_600_000
    return { speak: false, reason: 'no-take', topic: null, nextCheckAt: rest, stage }
  }
  const dailyMax = Math.max(0, Number(c.dailyMax) || 0)
  if (dailyMax > 0 && Number(input.sentToday) >= dailyMax) {
    return { speak: false, reason: 'daily_max', topic: null, nextCheckAt: nextDayAt(now, tz) }
  }
  const hourlyMax = Math.max(0, Number(c.hourlyMax) || 0)
  if (hourlyMax > 0 && Number(input.sentLastHour) >= hourlyMax) {
    return { speak: false, reason: 'hourly_max', topic: null, nextCheckAt: now + 30 * 60_000 }
  }
  // 刚发出去、还没到阶梯下一级：闹钟就定在下一级（5 分钟 / 15 分钟），而不是按 minGap 冷却。
  // 否则手机问一次 /plan 拿到的是"30 分钟后"，阶梯那一级永远等不到。
  if (stage === 'wait' && Number(input.silence?.nextAt) > 0) {
    return { speak: false, reason: 'ladder-wait', topic: null, nextCheckAt: Math.max(now + 60_000, Number(input.silence.nextAt)), stage }
  }
  // 阶梯：没接球时换更轻的球 / 换渠道。它压过 minGap —— 5 分钟就是 5 分钟，
  // 否则 minGap（默认 30 分钟）一卡，阶梯根本走不到。
  if (stage === 'lighter' || stage === 'channel') {
    return { speak: true, reason: 'ladder-' + stage, topic: input.topic, nextCheckAt: now + 5 * 60_000, stage }
  }
  if (minGap > 0 && Number(input.lastAt) > 0 && now - Number(input.lastAt) < minGap) {
    return { speak: false, reason: 'cooldown', topic: null, nextCheckAt: Number(input.lastAt) + minGap }
  }
  // 没有话题也能开口（free）：知道他在干什么、或者按人设起个话头就够了。
  // 以前这里一律闭嘴——新装的 App 记忆是空的，大肥鱼就永远不说话。
  if (!input.topic && !input.free) {
    return {
      speak: false,
      reason: 'no-topic',
      topic: null,
      nextCheckAt: fromTopic > now ? fromTopic : now + 6 * 3_600_000,
    }
  }
  const threshold = Math.min(100, Math.max(0, Number(c.diceThreshold ?? 60)))
  const roll = Number.isFinite(Number(input.roll)) ? Number(input.roll) : randomInt(1000) / 10
  // 下一次检查：按 minGap 来（教练 8 分钟、伙伴 45 分钟）；没配 minGap 才退回 30 分钟。最少 5 分钟。
  const next = now + Math.max(minGap > 0 ? minGap : 30 * 60_000, 5 * 60_000)
  if (roll >= threshold) {
    return { speak: false, reason: 'dice', topic: null, nextCheckAt: next }
  }
  return { speak: true, reason: 'ok', topic: input.topic, nextCheckAt: next }
}
