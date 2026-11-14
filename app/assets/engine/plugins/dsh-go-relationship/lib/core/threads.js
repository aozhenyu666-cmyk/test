import { DAY, toIso, atLocal } from './time.js';
import { updateThread } from './db.js';

export const ALLOWED_TRANSITIONS = {
  seed: ['active', 'raised', 'resolved', 'dropped', 'archived'],
  active: ['due', 'raised', 'engaged', 'avoided', 'dropped', 'resolved', 'archived'],
  due: ['raised', 'active', 'engaged', 'avoided', 'dropped', 'archived'],
  raised: ['engaged', 'avoided', 'active', 'due', 'dropped', 'resolved', 'archived'],
  engaged: ['resolved', 'active', 'due', 'raised', 'avoided', 'dropped', 'archived'],
  avoided: ['raised', 'engaged', 'escalated', 'active', 'resolved', 'dropped', 'archived'],
  escalated: ['engaged', 'resolved', 'dropped', 'archived'],
  resolved: ['archived', 'active'],
  dropped: ['active', 'archived'],
  archived: ['active'],
};

export function canTransition(from, to) {
  return (ALLOWED_TRANSITIONS[from] ?? []).includes(to);
}

export function transition(db, thread, to, ts, reason = null) {
  if (thread.state === to) {
    thread.last_touch_at = toIso(ts);
    updateThread(db, thread);
    return thread;
  }

  if (!canTransition(thread.state, to)) {
    throw new Error(`illegal thread transition: ${thread.state} -> ${to} (${thread.id})`);
  }

  const nowIso = toIso(ts);
  const next = { ...thread, state: to, last_touch_at: nowIso };

  if (to === 'resolved') {
    next.resolved_at = nowIso;
    next.muted_until = toIso(ts + 7 * DAY);
    next.outcome = 'resolved';
    next.next_due_at = null;
    next.next_intent = null;
  }

  if (to === 'dropped') {
    next.archive_reason = reason ?? 'dropped';
    next.next_due_at = null;
  }

  if (to === 'archived') {
    next.next_due_at = null;
  }

  if (to === 'avoided') {
    next.next_due_at = toIso(ts + DAY);
  }

  if (to === 'active') {
    next.outcome = null;
    next.resolved_at = null;
    next.muted_until = null;
    if (['resolved', 'dropped', 'archived'].includes(thread.state)) {
      next.deflections = 0;
    }
  }

  updateThread(db, next);
  return next;
}

export function touch(db, thread, ts) {
  const next = { ...thread, last_touch_at: toIso(ts) };
  updateThread(db, next);
  return next;
}

export function schedule(db, thread, nextDueAt, nextIntent = null) {
  const next = {
    ...thread,
    next_due_at: toIso(nextDueAt),
    next_intent: nextIntent ?? thread.next_intent,
  };
  updateThread(db, next);
  return next;
}

export function pauseForRest(db, thread, ts, delayDays, nextIntent, tzOffsetMinutes = 480) {
  let next = thread;
  if (thread.state !== 'active') {
    if (!canTransition(thread.state, 'active')) return thread;
    next = transition(db, thread, 'active', ts, 'rest');
  }

  next = {
    ...next,
    next_due_at: toIso(atLocal(ts, delayDays, 20, 0, tzOffsetMinutes)),
    next_intent: nextIntent ?? '先不催，等他状态好一点',
    last_touch_at: toIso(ts),
  };
  updateThread(db, next);
  return next;
}

export function resolveThread(db, thread, ts) {
  return transition(db, thread, 'resolved', ts, 'resolved');
}

export function dropThread(db, thread, ts, reason = 'user_abandoned') {
  return transition(db, thread, 'dropped', ts, reason);
}

export function stateLabel(state) {
  const labels = {
    seed: '刚提起',
    active: '进行中',
    due: '该跟进',
    raised: '已提出',
    engaged: '在推进',
    avoided: '疑似回避',
    escalated: '已升级',
    resolved: '已解决',
    dropped: '已搁置',
    archived: '已归档',
  };
  return labels[state] ?? state;
}
