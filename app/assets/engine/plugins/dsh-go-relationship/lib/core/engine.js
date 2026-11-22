import { randomUUID } from 'node:crypto';
import {
  openDb,
  closeDb,
  getPersona,
  savePersona,
  getThread,
  listThreads,
  insertIntervention,
  getIntervention,
  listCommitments,
  listInterventions,
} from './db.js';
import { MemoryWriter } from './memory-writer.js';
import { decide } from './intervention.js';
import { stateLabel } from './threads.js';
import { parseTs, toIso, atLocal, DAY } from './time.js';
import { DEFAULT_CONFIG, DEFAULT_PERSONA } from './config.js';

export class Engine {
  constructor({ dbPath = ':memory:', config = {}, persona = DEFAULT_PERSONA } = {}) {
    this.db = openDb(dbPath);
    this.config = {
      ...DEFAULT_CONFIG,
      ...config,
      dailyCaps: { ...DEFAULT_CONFIG.dailyCaps, ...(config.dailyCaps ?? {}) },
      cooldownMs: { ...DEFAULT_CONFIG.cooldownMs, ...(config.cooldownMs ?? {}) },
    };
    if (!getPersona(this.db)) {
      savePersona(this.db, {
        ...persona,
        updated_at: new Date().toISOString(),
      });
    }
    this.memory = new MemoryWriter(this.db, this.config);
  }

  onUserTurn(text, ts) {
    return this.memory.onUserTurn(text, ts);
  }

  tick(nowState, ts) {
    const now = parseTs(ts);
    const action = decide({ db: this.db, now, nowState, config: this.config });
    if (!action) return null;

    const intervention = {
      id: randomUUID(),
      ts: toIso(now),
      thread_id: action.thread_id,
      level: action.level,
      channel: action.channel,
      reason: action.reason,
      hypothesis: action.hypothesis,
      outcome: null,
      outcome_at: null,
      mandate_id: action.mandate_id ?? null,
    };
    insertIntervention(this.db, intervention);

    let thread = getThread(this.db, action.thread_id);
    if (thread) {
      this.memory.closeAgentCommitmentsOnFollowUp(thread.id, now, now);

      if (action.level >= 2) {
        thread = this.memory.markRaised(thread, now, `intervention_${action.level}`);
        if (!thread.next_due_at) {
          thread = this.memory.scheduleThread(
            thread,
            now,
            atLocal(now, 1, 20, 0, this.config.tzOffsetMinutes),
            '跟进刚才的提醒',
          );
        }
        this.memory.addAgentCommitment(
          thread.id,
          `跟进「${thread.title}」`,
          atLocal(now, 1, 20, 0, this.config.tzOffsetMinutes),
          now,
        );
      }

      if (action.reason === 'seed_ask_date' || action.reason === 'ask_exam_date') {
        thread = this.memory.scheduleThread(
          thread,
          now,
          atLocal(now, 2, 20, 0, this.config.tzOffsetMinutes),
          '再问一次考试日期',
        );
      }

      if (
        action.reason === 'commitment_due' &&
        thread.domain === 'exam' &&
        !thread.exam_date
      ) {
        thread = this.memory.updateIntent(
          thread,
          '到点问一下承诺，同时确认考试日期',
          now,
        );
      }

      this.memory.addEpisode(thread, now, {
        summary: `主动介入 L${action.level}（${action.reason}）`,
        whatAgentDid: action.hypothesis,
        salience: 0.6,
      });
    }

    return intervention;
  }

  recordOutcome(id, outcome, ts) {
    const intervention = getIntervention(this.db, id);
    if (!intervention) return null;
    return this.memory.applyOutcome(intervention, outcome, ts);
  }

  resolveThread(threadId, ts) {
    const thread = getThread(this.db, threadId);
    if (!thread) return null;
    return this.memory.resolve(thread, ts);
  }

  newSessionBrief(ts) {
    const now = parseTs(ts);
    const priority = { avoided: 0, due: 1, active: 2, seed: 3, raised: 4, engaged: 5, resolved: 6 };

    const relevant = listThreads(this.db).filter((t) => {
      if (['active', 'due', 'raised', 'engaged', 'avoided', 'seed'].includes(t.state)) return true;
      if (t.state === 'resolved' && t.resolved_at) {
        return now - parseTs(t.resolved_at) <= 7 * DAY;
      }
      return false;
    });

    relevant.sort((a, b) => {
      const pa = priority[a.state] ?? 9;
      const pb = priority[b.state] ?? 9;
      if (pa !== pb) return pa - pb;
      if (b.stakes !== a.stakes) return b.stakes - a.stakes;
      return parseTs(b.last_touch_at) - parseTs(a.last_touch_at);
    });

    const top = relevant.slice(0, 3);
    const lines = top.map((t) => {
      const exam = t.exam_date ? `，考试 ${t.exam_date}` : '';
      return `- ${t.title}（${stateLabel(t.state)}${exam}）：${t.next_intent ?? '暂无下一步'}`;
    });

    return { lines, threads: top, text: lines.join('\n') };
  }

  nightly(ts) {
    return this.memory.nightly(ts);
  }

  snapshot() {
    return {
      threads: listThreads(this.db).length,
      interventions: listInterventions(this.db).length,
      commitments: listCommitments(this.db).length,
    };
  }

  close() {
    closeDb(this.db);
  }
}
