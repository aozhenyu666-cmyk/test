import { parseTs, localHourFloat, localDateKey, DAY, HOUR } from './time.js';
import {
  listOpenThreads,
  listCommitments,
  listInterventions,
  listMandates,
  listSelfCorrections,
} from './db.js';
import { PRODUCTIVE_APPS, ENTERTAINMENT_APPS } from './config.js';

export function effectiveMandate(db, thread, now, config) {
  const caps = { ...config.dailyCaps };
  let level = Math.min(thread.mandate_level ?? config.defaultMandateLevel, config.defaultMandateLevel);
  let mandateId = null;

  const active = listMandates(db).filter((m) => {
    if (m.revoked) return false;
    if (!Number.isInteger(m.level) || m.level < 1 || m.level > 5) return false;
    if (m.level >= 4 && !m.thread_id && !m.domain) return false;
    const start = parseTs(m.start_at);
    const end = parseTs(m.end_at);
    if (!Number.isFinite(start) || !Number.isFinite(end)) return false;
    if (start > now || end <= now) return false;
    if (m.thread_id && m.thread_id !== thread.id) return false;
    if (!m.thread_id && m.domain && m.domain !== thread.domain) return false;
    return true;
  });

  for (const m of active) {
    if (m.level > level) {
      level = m.level;
      mandateId = m.id;
    }
    const cap = Math.max(1, Math.min(20, Number(m.daily_cap) || 1));
    caps[m.level] = cap;
  }

  if (level > config.defaultMandateLevel && !mandateId) {
    level = config.defaultMandateLevel;
  }

  return { level, caps, mandateId };
}

export function countToday(db, threadId, level, now, tzOffsetMinutes) {
  const dayKey = localDateKey(now, tzOffsetMinutes);
  return listInterventions(db, threadId).filter(
    (i) => i.level === level && localDateKey(parseTs(i.ts), tzOffsetMinutes) === dayKey,
  ).length;
}

export function countCommitmentDueToday(db, threadId, now, tzOffsetMinutes) {
  const dayKey = localDateKey(now, tzOffsetMinutes);
  return listInterventions(db, threadId).filter(
    (i) =>
      String(i.reason).startsWith('commitment_due') &&
      localDateKey(parseTs(i.ts), tzOffsetMinutes) === dayKey,
  ).length;
}

export function lastIntervention(db, threadId) {
  return listInterventions(db, threadId).at(-1) ?? null;
}

/** Consecutive interventions the user responded to but deflected. */
export function ineffectiveStreak(db, threadId) {
  let streak = 0;
  for (const item of listInterventions(db, threadId).reverse()) {
    if (item.outcome === 'deflected') streak += 1;
    else break;
  }
  return streak;
}

/** Consecutive interventions with no response at all. */
export function noResponseStreak(db, threadId) {
  let streak = 0;
  for (const item of listInterventions(db, threadId).reverse()) {
    if (['ignored', 'dismissed'].includes(item.outcome)) streak += 1;
    else break;
  }
  return streak;
}

export function hasOpenCommitment(db, threadId, now, config) {
  return listCommitments(db, threadId).some((c) => {
    if (c.owner !== 'user') return false;
    if (c.status === 'pending') {
      return parseTs(c.due_at) >= now - config.commitmentEvidenceDays * DAY;
    }
    if (c.status === 'missed') {
      return now - parseTs(c.due_at) <= config.commitmentEvidenceDays * DAY;
    }
    return false;
  });
}

function inQuietHours(hour, config) {
  return hour >= config.quietStart && hour < config.quietEnd;
}

function inLateNight(hour, config) {
  return hour >= config.lateNightStart || hour < config.lateNightEnd;
}

function earliestDueCommitment(db, threadId, now) {
  return (
    listCommitments(db, threadId)
      .filter(
        (c) => c.owner === 'user' && c.status === 'pending' && parseTs(c.due_at) <= now,
      )
      .sort((a, b) => parseTs(a.due_at) - parseTs(b.due_at))[0] ?? null
  );
}

function channelFor(level, streak, shifted) {
  if (level >= 5) return 'intercept';
  if (level >= 3) return 'page';
  if (streak >= 2 || shifted) return 'notification';
  return 'bubble';
}

function hypothesisFor(reason, thread) {
  if (reason.startsWith('late_night_confirmed')) {
    return `他已经多次回避「${thread.title}」且此前有明确承诺，深夜仍在刷，直接指出矛盾。`;
  }
  if (reason.startsWith('late_night')) {
    return `他可能只是放松，先用一句话提醒时间，不把刷视频当成逃避（${thread.title}）。`;
  }
  if (reason.startsWith('avoided_confirmed')) {
    return `他多次避开「${thread.title}」且此前有明确承诺，直接指出是必要的。`;
  }
  if (reason.startsWith('avoided_suspected')) {
    return `他连续转移话题，可能只是临时分心，先用温和确认代替定性（${thread.title}）。`;
  }
  if (reason.includes('strategy_shift')) {
    return '上一轮同样的介入没有效果，换一种方式再试，而不是重复。';
  }
  if (reason.includes('after_correction')) {
    return '上次误判了他，这次先降一级，避免把休息当成逃避。';
  }
  if (reason === 'ask_exam_date') {
    return '考试日期还没定，先把这件事问清楚。';
  }
  return `「${thread.title}」到了约定跟进的时间，轻问一句即可。`;
}

export function decide({ db, now, nowState = {}, config }) {
  const tz = config.tzOffsetMinutes;
  const hour = localHourFloat(now, tz);
  const threads = listOpenThreads(db);
  if (threads.length === 0) return null;

  const app = nowState.app ?? null;
  const appMinutes = Number(nowState.appMinutes ?? 0);

  if (nowState.screenOn !== false && app && PRODUCTIVE_APPS.has(app) && appMinutes >= config.flowMinutes) {
    return null;
  }

  const candidates = [];

  for (const thread of threads) {
    if (thread.state === 'raised' && !earliestDueCommitment(db, thread.id, now)) continue;
    if (thread.muted_until && now < parseTs(thread.muted_until)) continue;

    const dueCommitment = earliestDueCommitment(db, thread.id, now);
    const mandate = effectiveMandate(db, thread, now, config);
    let desired = 0;
    let reason = '';

    if (thread.state === 'seed') {
      if (dueCommitment) {
        desired = 2;
        reason = 'commitment_due';
      } else {
        if (thread.next_due_at && now < parseTs(thread.next_due_at)) continue;
        desired = 1;
        reason = 'seed_ask_date';
      }
    } else if (thread.state === 'avoided' || thread.deflections >= config.avoidanceThreshold) {
      const confirmed =
        thread.deflections >= config.confirmedAvoidanceThreshold &&
        hasOpenCommitment(db, thread.id, now, config);
      if (confirmed && mandate.level >= 3) {
        desired = 3;
        reason = 'avoided_confirmed';
      } else {
        desired = 2;
        reason = 'avoided_suspected';
      }
    } else if (thread.next_due_at && now >= parseTs(thread.next_due_at)) {
      desired = thread.stakes >= config.stakesHigh ? 2 : 1;
      reason = 'due';
    }

    if (
      inLateNight(hour, config) &&
      app &&
      ENTERTAINMENT_APPS.has(app) &&
      appMinutes >= 30 &&
      ['active', 'due', 'raised', 'engaged', 'avoided'].includes(thread.state)
    ) {
      const confirmed =
        thread.deflections >= config.confirmedAvoidanceThreshold &&
        hasOpenCommitment(db, thread.id, now, config);
      if (confirmed && mandate.level >= 3) {
        desired = Math.max(desired, 3);
        reason = 'late_night_confirmed';
      } else if (thread.deflections >= config.avoidanceThreshold) {
        desired = Math.max(desired, 2);
        reason = 'late_night_suspected';
      } else if (thread.domain === 'exam') {
        desired = Math.max(desired, 2);
        reason = reason || 'late_night_entertainment';
      }
    }

    if (
      (desired === 0 || reason === 'due') &&
      thread.domain === 'exam' &&
      !thread.exam_date &&
      thread.state !== 'seed' &&
      !dueCommitment &&
      (!thread.next_due_at || now >= parseTs(thread.next_due_at))
    ) {
      desired = 1;
      reason = 'ask_exam_date';
    }

    if (desired === 0) {
      const pending = listCommitments(db, thread.id)
        .filter((c) => c.owner === 'user' && c.status === 'pending')
        .sort((a, b) => parseTs(a.due_at) - parseTs(b.due_at))[0];
      if (pending && parseTs(pending.due_at) <= now) {
        desired = 2;
        reason = 'commitment_due';
      }
    }

    if (desired === 0) continue;

    if (
      dueCommitment &&
      desired > 0 &&
      desired <= 2 &&
      (reason === 'due' || reason.startsWith('late_night'))
    ) {
      reason = 'commitment_due';
    }
    const isCommitmentDue = reason === 'commitment_due';

    if (inQuietHours(hour, config) && desired < 3) continue;

    const last = lastIntervention(db, thread.id);
    const userSpokeAfter = last && parseTs(thread.last_touch_at) > parseTs(last.ts);
    if (
      !isCommitmentDue &&
      !userSpokeAfter &&
      noResponseStreak(db, thread.id) >= config.pauseAfterIgnored
    ) {
      continue;
    }

    const lastCorrection = listSelfCorrections(db, thread.id).at(-1);
    if (
      !isCommitmentDue &&
      lastCorrection &&
      now - parseTs(lastCorrection.ts) <= config.correctionWindowDays * DAY &&
      !listInterventions(db, thread.id).some((i) => parseTs(i.ts) > parseTs(lastCorrection.ts)) &&
      desired >= 2
    ) {
      desired -= 1;
      reason = `${reason}_after_correction`;
    }

    if (desired > mandate.level) {
      desired = mandate.level;
      reason = `${reason}_capped`;
    }
    if (desired === 0) continue;

    const cap = isCommitmentDue
      ? (config.commitmentDueDailyCap ?? 3)
      : (mandate.caps[desired] ?? 1);
    const used = isCommitmentDue
      ? countCommitmentDueToday(db, thread.id, now, tz)
      : countToday(db, thread.id, desired, now, tz);
    if (used >= cap) continue;

    const cooldown = isCommitmentDue
      ? (config.commitmentDueCooldownMs ?? HOUR)
      : (config.cooldownMs[desired] ?? 0);
    if (last && now - parseTs(last.ts) < cooldown) continue;

    const streak = ineffectiveStreak(db, thread.id);
    let shifted = false;
    if (streak >= config.ineffectiveThreshold) {
      const canEscalate =
        thread.deflections >= config.confirmedAvoidanceThreshold &&
        hasOpenCommitment(db, thread.id, now, config);
      if (canEscalate && desired < mandate.level) {
        const nextLevel = desired + 1;
        const nextCap = mandate.caps[nextLevel] ?? 1;
        const nextUsed = countToday(db, thread.id, nextLevel, now, tz);
        const nextGap = !last || now - parseTs(last.ts) >= (config.cooldownMs[nextLevel] ?? 0);
        if (nextUsed < nextCap && nextGap) {
          desired = nextLevel;
          reason = reason
            .replace('avoided_suspected', 'avoided_confirmed')
            .replace('late_night_suspected', 'late_night_confirmed');
        } else {
          shifted = true;
        }
      } else {
        shifted = true;
      }
      reason = `${reason}_strategy_shift`;
    }

    candidates.push({
      thread,
      level: desired,
      reason,
      mandate,
      streak,
      shifted,
      channel: channelFor(desired, streak, shifted),
      hypothesis: hypothesisFor(reason, thread),
    });
  }

  if (candidates.length === 0) return null;

  candidates.sort((a, b) => {
    if (b.level !== a.level) return b.level - a.level;
    if (b.thread.stakes !== a.thread.stakes) return b.thread.stakes - a.thread.stakes;
    return parseTs(a.thread.last_touch_at) - parseTs(b.thread.last_touch_at);
  });

  const best = candidates[0];
  return {
    thread_id: best.thread.id,
    level: best.level,
    channel: best.channel,
    reason: best.reason,
    hypothesis: best.hypothesis,
    mandate_id: best.mandate.mandateId,
  };
}
