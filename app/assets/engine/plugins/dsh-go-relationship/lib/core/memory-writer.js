import { randomUUID } from 'node:crypto';
import { parseTs, toIso, atLocal, DAY, HOUR, localHourFloat } from './time.js';
import {
  detectDomain,
  makeTitle,
  extractExamDate,
  extractCommitment,
  extractCommitments,
  isProgress,
  isAffirmative,
  isRestExplanation,
  isGoalAbandon,
  isDeflection,
} from './text.js';
import {
  insertThread,
  updateThread,
  getThread,
  findLatestThreadByDomain,
  listThreads,
  insertCommitment,
  listCommitments,
  updateCommitment,
  closeDueCommitments,
  markMissedCommitments,
  insertEpisode,
  listEpisodes,
  listInterventions,
  updateInterventionOutcome,
  insertSelfCorrection,
  insertUserModel,
  listUserModel,
  updateUserModelStatus,
} from './db.js';
import { transition, pauseForRest, dropThread, schedule } from './threads.js';

const DOMAIN_STAKES = {
  exam: 0.8,
  job: 0.7,
  sleep: 0.6,
  project: 0.6,
};

export class MemoryWriter {
  constructor(db, config) {
    this.db = db;
    this.config = config;
  }

  onUserTurn(text, ts) {
    const now = parseTs(ts);
    const nowIso = toIso(now);
    const tz = this.config.tzOffsetMinutes;
    const events = [];
    const hit = detectDomain(text);

    let thread = null;
    if (hit) {
      thread = findLatestThreadByDomain(this.db, hit.domain);
      if (thread && ['resolved', 'dropped', 'archived'].includes(thread.state)) {
        thread = transition(this.db, thread, 'active', now, 'user_reopened');
        events.push('reopened');
      }
      if (!thread) {
        thread = this.createThread(hit.domain, text, now);
        events.push('thread_created');
      }
    } else {
      const pending = this.findPendingThread();
      if (pending && ['raised', 'due', 'avoided'].includes(pending.state)) {
        thread = pending;
      } else if (isProgress(text) || isGoalAbandon(text) || isRestExplanation(text)) {
        thread = this.findRecentOpenThread();
      } else if (this.findExamThreadAwaitingDate() && extractExamDate(text, now, tz)) {
        thread = this.findExamThreadAwaitingDate();
      } else if (
        !isDeflection(text) &&
        !isRestExplanation(text) &&
        !isGoalAbandon(text) &&
        extractCommitment(text, now, tz)
      ) {
        thread = this.findRecentOpenThread();
      } else if (isDeflection(text)) {
        const recent = this.findRecentOpenThread();
        if (recent && recent.deflections > 0) thread = recent;
      }
    }

    if (thread && thread.domain === 'exam') {
      thread = this.fillExamDate(thread, text, now, events);
    }

    if (thread) {
      thread = { ...thread, last_touch_at: nowIso };
      updateThread(this.db, thread);
    }

    if (thread && isGoalAbandon(text)) {
      thread = dropThread(this.db, thread, now, 'user_abandoned');
      this.cancelAgentCommitments(thread.id, now);
      this.addEpisode(thread, now, {
        summary: `用户明确表示不想继续：${text}`,
        userResponse: text,
        outcome: 'neutral',
        lesson: '该线程不再主动提起，除非用户重新打开',
      });
      return { threadId: thread.id, events: [...events, 'abandoned'] };
    }

    if (thread && isRestExplanation(text)) {
      if (thread.state === 'seed') {
        for (const c of listCommitments(this.db, thread.id).filter(
          (item) => item.owner === 'user' && ['pending', 'missed'].includes(item.status),
        )) {
          updateCommitment(this.db, {
            ...c,
            status: 'renegotiated',
            closed_at: nowIso,
          });
        }
        thread = this.scheduleThread(
          thread,
          now,
          atLocal(now, 2, 20, 0, tz),
          '再问一次考试日期',
        );
        this.addEpisode(thread, now, {
          summary: `用户说明在休息，尚未给出考试日期：${text}`,
          userResponse: text,
          outcome: 'neutral',
          lesson: 'seed 线程只顺延追问，不激活',
        });
        return { threadId: thread.id, events: [...events, 'rest_seed'] };
      }

      const last = listInterventions(this.db, thread.id).at(-1);
      const withinDay = last && now - parseTs(last.ts) <= DAY;
      if (withinDay && (!last.outcome || ['ignored', 'dismissed', 'deflected'].includes(last.outcome))) {
        if (!last.outcome || last.outcome === 'ignored') {
          updateInterventionOutcome(this.db, last.id, 'dismissed', nowIso);
        }
        insertSelfCorrection(this.db, {
          id: randomUUID(),
          ts: nowIso,
          intervention_id: last.id,
          thread_id: thread.id,
          claim: last.hypothesis,
          correction: '用户说明自己在休息/调整计划，上一轮主动属于误判；下次先降一级并顺延。',
        });
        events.push('self_correction');
      }
      thread = pauseForRest(
        this.db,
        thread,
        now,
        this.config.restDelayDays,
        '先不催，等他状态好一点再问',
        this.config.tzOffsetMinutes,
      );
      thread.deflections = 0;
      updateThread(this.db, thread);

      for (const c of listCommitments(this.db, thread.id).filter(
        (item) => item.owner === 'user' && ['pending', 'missed'].includes(item.status),
      )) {
        updateCommitment(this.db, {
          ...c,
          status: 'renegotiated',
          closed_at: nowIso,
        });
      }
      this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
      this.addEpisode(thread, now, {
        summary: `用户说明在休息/调整：${text}`,
        userResponse: text,
        outcome: 'neutral',
        lesson: '休息不是回避，暂停推进',
      });
      return { threadId: thread.id, events: [...events, 'rest_pause'] };
    }

    if (thread) {
      const canCountDeflection =
        ['raised', 'due', 'avoided'].includes(thread.state) || thread.deflections > 0;

      if (isProgress(text) || isAffirmative(text)) {
        if (isProgress(text)) {
          closeDueCommitments(this.db, thread.id, nowIso, 'user');
        }
        if (['raised', 'due', 'avoided', 'active', 'engaged'].includes(thread.state)) {
          thread = transition(this.db, thread, 'engaged', now, 'progress');
        }
        thread.deflections = 0;
        thread.next_due_at = toIso(
          this.mergeNextDue(thread.id, atLocal(now, 2, 20, 0, tz)),
        );
        thread.next_intent = '过两天问一下进展';
        updateThread(this.db, thread);
        this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
        events.push('progress');
      } else if (
        thread.state === 'raised' &&
        !/再说/.test(text) &&
        /(等会|等一会儿|等一下|稍等|稍后|晚点|晚一点|一会|待会|待会儿|等等|等我|过会|过会儿|回头|马上)/.test(text)
      ) {
        thread = transition(this.db, thread, 'engaged', now, 'later_reply');
        thread.next_due_at = toIso(now + 2 * HOUR);
        thread.next_intent = '他说等会，两小时后再问';
        updateThread(this.db, thread);
        this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
        events.push('answered_later');
      } else if (canCountDeflection && isDeflection(text)) {
        const deflections = thread.deflections + 1;
        thread.deflections = deflections;
        if (deflections >= this.config.avoidanceThreshold) {
          thread = transition(this.db, thread, 'avoided', now, 'deflection_threshold');
          thread.next_due_at = toIso(
            this.mergeNextDue(thread.id, atLocal(now, 1, 10, 0, tz)),
          );
          thread.next_intent = '先温和确认，不直接定性为逃避';
          updateThread(this.db, thread);
          this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
          events.push('avoided_suspected');
        } else {
          thread = transition(this.db, thread, 'active', now, 'deflection');
          thread.next_due_at = toIso(
            this.mergeNextDue(thread.id, atLocal(now, 1, 20, 0, tz)),
          );
          thread.next_intent = '明天再轻轻问一次';
          updateThread(this.db, thread);
          this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
          events.push('deflection');
        }
      } else if (thread.state === 'raised') {
        const relevant =
          isAffirmative(text) ||
          /(我(在|已经|还没|马上|现在)|忙|等会|等一下|稍等|等等|晚一点|待会|等我|过会|没做|做了|背了|看了|学了|写了|改了|投了|交了|发了|好的|好啊|好吧|没有|还没|不知道|我不在|不在|不想|哦|嗯|噢|喔|啊|哎|呀|唉)/.test(
            text,
          ) ||
          /(^|[，,。！？\s])哈{1,3}([，,。！？\s]|$)/.test(text) ||
          detectDomain(text)?.domain === thread.domain;

        if (relevant) {
          thread = transition(this.db, thread, 'engaged', now, 'answered');
          thread.next_due_at = toIso(
            this.mergeNextDue(thread.id, atLocal(now, 2, 20, 0, tz)),
          );
          updateThread(this.db, thread);
          this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
          events.push('answered');
        } else {
          thread = transition(this.db, thread, 'active', now, 'unrelated_reply');
          thread.next_due_at = toIso(
            this.mergeNextDue(thread.id, atLocal(now, 1, 20, 0, tz)),
          );
          thread.next_intent = '刚才那条不像回应，明天再问';
          updateThread(this.db, thread);
          this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
          events.push('released_unrelated');
        }
      }
    }

    if (thread) {
      const commitments = !isDeflection(text) && !isRestExplanation(text) && !isGoalAbandon(text)
        ? extractCommitments(text, now, tz)
        : [];
      for (const commitment of commitments) {
        if (this.hasPendingCommitment(thread.id, commitment.statement)) continue;
        insertCommitment(this.db, {
          id: randomUUID(),
          owner: 'user',
          thread_id: thread.id,
          statement: commitment.statement,
          due_at: toIso(commitment.dueAt),
          status: 'pending',
          evidence: null,
          follow_up_at: null,
          closed_at: null,
          created_at: nowIso,
        });
        if (!thread.next_due_at || parseTs(thread.next_due_at) > commitment.dueAt) {
          thread = schedule(this.db, thread, commitment.dueAt, `到点问一下：${commitment.statement}`);
        }
        events.push('commitment');
      }
      this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
    }

    if (hit && thread) {
      this.rememberGoal(thread, now);
    }

    if (thread) {
      const outcome = events.includes('progress')
        ? 'engaged'
        : events.includes('avoided_suspected')
          ? 'avoided'
          : 'neutral';
      this.addEpisode(thread, now, {
        summary: text,
        userResponse: text,
        outcome,
      });
    }

    return { threadId: thread?.id ?? null, events };
  }

  fillExamDate(thread, text, now, events) {
    const parsed = extractExamDate(text, now, this.config.tzOffsetMinutes);
    if (!parsed) return thread;

    const hasDateQuestion = (thread.open_questions ?? []).some((q) => q.includes('考试日期'));
    const shouldUpdate =
      !thread.exam_date ||
      parsed.iso !== thread.exam_date ||
      (parsed.assumed === false && hasDateQuestion);
    if (!shouldUpdate) return thread;

    const next = {
      ...thread,
      exam_date: parsed.iso,
      open_questions: (thread.open_questions ?? []).filter((q) => !q.includes('考试日期')),
      last_touch_at: toIso(now),
      next_intent: (thread.next_intent ?? '').includes('考试日期')
        ? '先确认他打算怎么做'
        : thread.next_intent,
    };

    if (next.state === 'seed') {
      const pending = this.earliestPendingCommitment(thread.id);
      const pendingDue = pending ? parseTs(pending.due_at) : null;
      next.next_due_at = Number.isFinite(pendingDue) ? toIso(pendingDue) : null;
      next.next_intent = pending ? `到点问一下：${pending.statement}` : '先确认他打算怎么做';

      const active = transition(this.db, next, 'active', now, 'exam_date_known');
      active.exam_date = parsed.iso;
      active.next_due_at = next.next_due_at;
      active.next_intent = next.next_intent;
      updateThread(this.db, active);
      events.push('exam_date');
      return active;
    }

    updateThread(this.db, next);
    events.push('exam_date');
    return next;
  }

  createThread(domain, text, now) {
    const nowIso = toIso(now);
    const exam = domain === 'exam' ? extractExamDate(text, now, this.config.tzOffsetMinutes) : null;
    const missingExamDate = domain === 'exam' && !exam;

    const thread = {
      id: randomUUID(),
      title: makeTitle(domain),
      domain,
      state: missingExamDate ? 'seed' : 'active',
      stakes: DOMAIN_STAKES[domain] ?? 0.5,
      created_at: nowIso,
      last_touch_at: nowIso,
      next_due_at: null,
      next_intent: missingExamDate ? '先问清楚考试日期' : '先确认他打算怎么做',
      mandate_level: this.config.defaultMandateLevel,
      mandate_expires_at: null,
      deflections: 0,
      open_questions: missingExamDate
        ? ['考试日期是什么时候？']
        : exam?.assumed
          ? ['具体考试日期确认一下（暂按 15 日）']
          : [],
      outcome: null,
      archive_reason: null,
      resolved_at: null,
      muted_until: null,
      exam_date: exam?.iso ?? null,
    };
    insertThread(this.db, thread);
    return thread;
  }

  findPendingThread() {
    return (
      listThreads(this.db)
        .filter((t) => ['raised', 'due', 'avoided'].includes(t.state))
        .sort((a, b) => parseTs(b.last_touch_at) - parseTs(a.last_touch_at))[0] ?? null
    );
  }

  // P0 assumes one open thread per domain; progress without a domain keyword
  // is attributed to the most recently touched open thread.
  findRecentOpenThread() {
    return (
      listThreads(this.db)
        .filter((t) => !['archived', 'dropped', 'resolved'].includes(t.state))
        .sort((a, b) => parseTs(b.last_touch_at) - parseTs(a.last_touch_at))[0] ?? null
    );
  }

  findExamThreadAwaitingDate() {
    const candidates = listThreads(this.db).filter(
      (t) =>
        t.domain === 'exam' &&
        !['resolved', 'dropped', 'archived'].includes(t.state) &&
        (t.state === 'seed' ||
          (t.open_questions ?? []).some(
            (q) => q.includes('考试日期') || q.includes('确认一下'),
          )),
    );
    candidates.sort((a, b) => {
      const seedRank = (t) => (t.state === 'seed' ? 0 : 1);
      if (seedRank(a) !== seedRank(b)) return seedRank(a) - seedRank(b);
      return parseTs(b.last_touch_at) - parseTs(a.last_touch_at);
    });
    return candidates[0] ?? null;
  }

  scheduleThread(thread, ts, dueAt, nextIntent) {
    return schedule(this.db, thread, dueAt, nextIntent);
  }

  updateIntent(thread, intent, ts) {
    const next = {
      ...thread,
      next_intent: intent,
      last_touch_at: toIso(parseTs(ts)),
    };
    updateThread(this.db, next);
    return next;
  }

  hasPendingCommitment(threadId, statement) {
    return listCommitments(this.db, threadId).some(
      (c) => c.status === 'pending' && c.statement === statement,
    );
  }

  earliestPendingCommitment(threadId) {
    return (
      listCommitments(this.db, threadId)
        .filter((c) => c.owner === 'user' && c.status === 'pending')
        .sort((a, b) => parseTs(a.due_at) - parseTs(b.due_at))[0] ?? null
    );
  }

  mergeNextDue(threadId, candidateTs) {
    const commitment = this.earliestPendingCommitment(threadId);
    if (!commitment) return candidateTs;
    const due = parseTs(commitment.due_at);
    return Number.isFinite(due) && due < candidateTs ? due : candidateTs;
  }

  rememberGoal(thread, now) {
    const statement = `正在推进「${thread.title}」`;
    const exists = listUserModel(this.db).some(
      (u) => u.status === 'active' && u.domain === 'goal' && u.statement === statement,
    );
    if (exists) return;
    insertUserModel(this.db, {
      id: randomUUID(),
      type: 'fact',
      domain: 'goal',
      statement,
      confidence: 0.9,
      source_episode_ids: [],
      first_seen: toIso(now),
      last_confirmed: toIso(now),
      expires_at: toIso(now + 90 * DAY),
      status: 'active',
    });
  }

  addEpisode(thread, now, { summary, whatAgentDid = null, userResponse = null, outcome = 'neutral', lesson = null, salience = 0.5 }) {
    insertEpisode(this.db, {
      id: randomUUID(),
      ts: toIso(now),
      thread_ids: thread ? [thread.id] : [],
      summary,
      what_agent_did: whatAgentDid,
      user_response: userResponse,
      outcome,
      lesson,
      salience,
    });
  }

  addCommitment(threadId, owner, statement, dueAt, ts) {
    const nowIso = toIso(parseTs(ts));
    const commitment = {
      id: randomUUID(),
      owner,
      thread_id: threadId,
      statement,
      due_at: toIso(dueAt),
      status: 'pending',
      evidence: null,
      follow_up_at: null,
      closed_at: null,
      created_at: nowIso,
    };
    insertCommitment(this.db, commitment);
    return commitment;
  }

  addAgentCommitment(threadId, statement, dueAt, ts) {
    const existing = listCommitments(this.db, threadId).find(
      (c) => c.owner === 'agent' && c.status === 'pending' && c.statement === statement,
    );
    if (existing) return existing;
    const nowIso = toIso(parseTs(ts));
    const commitment = {
      id: randomUUID(),
      owner: 'agent',
      thread_id: threadId,
      statement,
      due_at: toIso(dueAt),
      status: 'pending',
      evidence: null,
      follow_up_at: null,
      closed_at: null,
      created_at: nowIso,
    };
    insertCommitment(this.db, commitment);
    return commitment;
  }

  closeAgentCommitmentsOnFollowUp(threadId, now, ts) {
    const pending = listCommitments(this.db, threadId).filter(
      (c) => c.owner === 'agent' && c.status === 'pending',
    );
    for (const c of pending) {
      updateCommitment(this.db, {
        ...c,
        status: 'done',
        evidence: 'followed_up',
        closed_at: toIso(parseTs(ts)),
      });
    }
    return pending.length;
  }

  syncAgentCommitmentDue(threadId, dueIso) {
    if (!dueIso) return;
    for (const c of listCommitments(this.db, threadId).filter(
      (item) => item.owner === 'agent' && item.status === 'pending',
    )) {
      updateCommitment(this.db, { ...c, due_at: dueIso });
    }
  }

  cancelAgentCommitments(threadId, ts) {
    for (const c of listCommitments(this.db, threadId).filter(
      (item) => item.owner === 'agent' && item.status === 'pending',
    )) {
      updateCommitment(this.db, {
        ...c,
        status: 'cancelled',
        closed_at: toIso(parseTs(ts)),
      });
    }
  }

  markRaised(thread, ts, reason) {
    try {
      return transition(this.db, thread, 'raised', parseTs(ts), reason);
    } catch {
      return thread;
    }
  }

  applyOutcome(intervention, outcome, ts) {
    const now = parseTs(ts);
    const nowIso = toIso(now);
    updateInterventionOutcome(this.db, intervention.id, outcome, nowIso);

    let thread = getThread(this.db, intervention.thread_id);
    if (thread) {
      if (outcome === 'engaged') {
        if (thread.state !== 'engaged') {
          try {
            thread = transition(this.db, thread, 'engaged', now, 'outcome_engaged');
          } catch {
            // keep current state
          }
        }
        thread.deflections = 0;
        thread.next_due_at = toIso(atLocal(now, 2, 20, 0, this.config.tzOffsetMinutes));
        updateThread(this.db, thread);
        this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
      } else if (outcome === 'ignored' || outcome === 'dismissed') {
        if (thread.state === 'raised') {
          try {
            thread = transition(this.db, thread, 'active', now, 'no_response');
          } catch {
            // keep current state
          }
          thread.next_due_at = toIso(atLocal(now, 1, 20, 0, this.config.tzOffsetMinutes));
          updateThread(this.db, thread);
          this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
        }
      }

      this.addEpisode(thread, now, {
        summary: `介入结果：${outcome}`,
        whatAgentDid: intervention.hypothesis,
        outcome: outcome === 'engaged' ? 'engaged' : outcome === 'deflected' ? 'avoided' : 'neutral',
        lesson:
          outcome === 'ignored'
            ? '这次没有回应，下一次换方式或降低频率'
            : outcome === 'deflected'
              ? '他在回避这个话题，先确认再决定是否升级'
              : null,
      });
    }

    return thread;
  }

  resolve(thread, ts) {
    const now = parseTs(ts);
    const next = transition(this.db, thread, 'resolved', now, 'resolved');
    this.cancelAgentCommitments(next.id, now);
    for (const c of listCommitments(this.db, next.id).filter(
      (item) => item.owner === 'user' && ['pending', 'missed'].includes(item.status),
    )) {
      updateCommitment(this.db, {
        ...c,
        status: 'cancelled',
        closed_at: toIso(now),
      });
    }
    this.addEpisode(next, now, {
      summary: '线程已解决',
      outcome: 'resolved',
      lesson: '7 天内不重复提醒，用户主动提起可重开',
      salience: 0.8,
    });
    return next;
  }

  nightly(ts) {
    const now = parseTs(ts);
    const nowIso = toIso(now);
    const hour = localHourFloat(now, this.config.tzOffsetMinutes);
    const quiet = hour >= this.config.quietStart && hour < this.config.quietEnd;

    if (!quiet) {
      markMissedCommitments(this.db, nowIso, 'user');

      for (const c of listCommitments(this.db).filter(
        (item) => item.owner === 'agent' && item.status === 'pending',
      )) {
        const followed = listInterventions(this.db, c.thread_id).some(
          (i) => parseTs(i.ts) > parseTs(c.created_at),
        );
        if (followed) {
          updateCommitment(this.db, {
            ...c,
            status: 'done',
            evidence: 'followed_up',
            closed_at: nowIso,
          });
        } else if (parseTs(c.due_at) < now) {
          updateCommitment(this.db, { ...c, status: 'missed', closed_at: nowIso });
        }
      }
    }

    const open = listThreads(this.db).filter(
      (t) => !['archived', 'dropped', 'resolved'].includes(t.state),
    );

    for (const source of open) {
      let thread = source;

      if (thread.state === 'raised') {
        const last = listInterventions(this.db, thread.id).at(-1);
        const userSpokeAfter = last && parseTs(thread.last_touch_at) > parseTs(last.ts);
        if (
          last &&
          !last.outcome &&
          !userSpokeAfter &&
          now - parseTs(last.ts) >= this.config.raisedTimeoutDays * DAY
        ) {
          thread = transition(this.db, thread, 'active', now, 'raised_timeout');
          thread.next_due_at = toIso(atLocal(now, 1, 20, 0, this.config.tzOffsetMinutes));
          thread.next_intent = '上次问了没回，隔天再轻轻问一次';
          updateThread(this.db, thread);
        }
      }

      if (!thread.next_due_at && thread.state !== 'seed') {
        thread = schedule(
          this.db,
          thread,
          atLocal(now, 2, 20, 0, this.config.tzOffsetMinutes),
          '过两天看看进展',
        );
        this.syncAgentCommitmentDue(thread.id, thread.next_due_at);
      }

      const episodes = listEpisodes(this.db, thread.id);
      const statement = `在「${thread.title}」上，他通常在被直接问到时愿意回应。`;
      const exists = listUserModel(this.db).some(
        (u) => u.status === 'active' && u.statement === statement,
      );
      const engaged = episodes.filter((e) => e.outcome === 'engaged').length;
      if (!exists && episodes.length >= 3 && engaged >= 2) {
        insertUserModel(this.db, {
          id: randomUUID(),
          type: 'observation',
          domain: 'pattern',
          statement,
          confidence: 0.6,
          source_episode_ids: episodes.slice(-3).map((e) => e.id),
          first_seen: nowIso,
          last_confirmed: nowIso,
          expires_at: toIso(now + 30 * DAY),
          status: 'active',
        });
      }
    }

    const stale = listUserModel(this.db).filter(
      (u) => u.status === 'active' && u.expires_at && parseTs(u.expires_at) < now,
    );
    for (const item of stale) {
      updateUserModelStatus(this.db, item.id, 'stale', nowIso);
    }

    return { openThreads: open.length, staleItems: stale.length };
  }

  correct(itemId, correction, ts) {
    const now = parseTs(ts);
    updateUserModelStatus(this.db, itemId, 'retracted', toIso(now));
    insertEpisode(this.db, {
      id: randomUUID(),
      ts: toIso(now),
      thread_ids: [],
      summary: `用户更正：${correction}`,
      what_agent_did: null,
      user_response: correction,
      outcome: 'neutral',
      lesson: '用户更正过的理解必须失效，不能继续引用',
      salience: 0.8,
    });
  }
}
