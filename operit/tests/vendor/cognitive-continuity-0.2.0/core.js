'use strict';

// Pure, JSON-only domain model. The host owns scheduling, durable storage,
// compare-and-swap writes, external tools and the delivery of effects.
const ANGLES = ['describe', 'compare', 'connect', 'reason', 'apply', 'challenge'];
const EVENT_TYPES = new Set(['start', 'answer', 'ai_contribution', 'tick', 'pause',
  'resume', 'finish', 'configure', 'request_action', 'action_result',
  'delivery_result', 'record_action', 'archive_result', 'set_next_entry',
  'attach_material', 'set_activity', 'set_return_point']);
const MAX_EVENTS = 100000;
const LIMIT = 100000;

function fail(code, message) {
  const error = new Error(message);
  error.code = code;
  throw error;
}
function clone(value) {
  const ancestors = new Set();
  function check(item) {
    if (item === null || typeof item === 'string' || typeof item === 'boolean') return;
    if (typeof item === 'number' && Number.isFinite(item)) return;
    if (typeof item !== 'object' || ancestors.has(item)) fail('INVALID_JSON', 'Only finite, acyclic plain JSON values are supported.');
    if (!Array.isArray(item) && Object.getPrototypeOf(item) !== Object.prototype && Object.getPrototypeOf(item) !== null)
      fail('INVALID_JSON', 'Only plain JSON objects are supported.');
    ancestors.add(item);
    for (const child of Object.values(item)) check(child);
    ancestors.delete(item);
  }
  check(value);
  try { return JSON.parse(JSON.stringify(value)); }
  catch (_) { fail('INVALID_JSON', 'State and events must be serializable JSON.'); }
}
function text(value, field, max = LIMIT) {
  if (typeof value !== 'string' || !value.trim() || value.length > max)
    fail('INVALID_EVENT', `${field} must be a nonempty string of at most ${max} characters.`);
  return value.trim();
}
function time(value) {
  const parsed = typeof value === 'string' ? Date.parse(value) : value;
  if (typeof parsed !== 'number' || !Number.isSafeInteger(parsed) || parsed < 0)
    fail('INVALID_TIME', 'now must be an explicit nonnegative integer timestamp in milliseconds.');
  return parsed;
}
function evidence(value) {
  // A reference can be a capture ID, a readback transcript, or a host evidence object.
  if (typeof value === 'string') return value.trim().length > 0;
  return !!value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length > 0;
}
function createState() {
  return {
    schema_version: 1, revision: 0, session: null, history: [], processed: [],
    outbox: [], settings: {mode: 'gentle', response_timeout_ms: 120000,
      max_nudges: 2, nudge_gap_ms: 120000}, actions: []
  };
}
function validateState(state) {
  if (!state || state.schema_version !== 1 || !Number.isSafeInteger(state.revision) || state.revision < 0)
    fail('INVALID_STATE', 'Expected a schema_version 1 state and a nonnegative revision.');
  for (const key of ['history', 'processed', 'outbox', 'actions'])
    if (!Array.isArray(state[key])) fail('INVALID_STATE', `${key} must be an array.`);
  if (!state.settings || typeof state.settings !== 'object') fail('INVALID_STATE', 'Missing settings.');
}
function snapshot(state) {
  validateState(state);
  return clone(state);
}
function sessionFor(state, id, active = false) {
  const session = state.session;
  if (!session) fail('NO_SESSION', 'There is no current session.');
  if (id !== undefined && id !== session.id) fail('STALE_SESSION', 'The event belongs to another session.');
  if (active && session.status !== 'ACTIVE') fail('SESSION_NOT_ACTIVE', 'Resume the session before responding.');
  return session;
}
function findSession(state, id) {
  const session = state.session && state.session.id === id ? state.session : state.history.find(s => s.id === id);
  if (!session) fail('STALE_SESSION', 'The referenced session does not exist.');
  return session;
}
function excerpt(value, max = 140) {
  const line = value.replace(/\s+/g, ' ');
  return line.length > max ? line.slice(0, max) + '…' : line;
}
function reminderText(session) {
  const activity = session.activity || 'thinking';
  if (activity === 'thinking') return `接着处理“${session.title}”：${session.next_entry.question}\n${session.next_entry.scaffold}\n可以回答一点、暂缓，或结束本回合。`;
  const names = {watching:'观看', acting:'行动', resting:'休息'};
  return `“${session.title}”的${names[activity]}检查点到了。${session.activity_note || ''}\n请报告当前进展或选择下一步；原问题与材料位置仍保留。`;
}
function makeQuestion(session, angle, previous, now) {
  const topic = `「${excerpt(session.object)}」`;
  const anchor = previous ? `你刚才写了「${excerpt(previous)}」。` : '';
  const prompts = [
    [`${anchor}关于${topic}，你自己的理解是什么？`, '先写一句自己的理解，再写一个具体细节；不确定时标明“我还不确定”。'],
    [`${anchor}围绕${topic}，哪两个观点、方案或例子值得比较？`, '写 A 和 B、一个相同点、一个不同点，再说明哪一点与你的目标有关。'],
    [`${anchor}${topic}能连接到你已有的哪段经历或知识？`, '写“这让我想到……，连接点是……，两者不一样的地方是……”。'],
    [`${anchor}对于${topic}，你能把一个判断的理由接起来吗？`, '写“我的判断是……，因为……，依据是……，还缺的证据是……”。'],
    [`${anchor}为了“${excerpt(session.goal)}”，你可以把${topic}变成哪一步具体行动？`, '写一个你能做的动作、所需条件和完成后能看见的结果；不要把计划当成已经完成。'],
    [`${anchor}关于${topic}，哪个反例或未知条件可能改变你的判断？`, '写一个反例或不确定条件、它会改变什么、你准备怎样验证。']
  ];
  const [question, scaffold] = prompts[angle - 1];
  const turn = {question_id: `${session.id}:q:${session.turns.length + 1}`,
    angle, angle_name: ANGLES[angle - 1], question, scaffold,
    user_answer: null, ai_contributions: [], asked_at: now, answered_at: null};
  if (previous && (/^(我)?(不(知道|懂|会)|没(想好|思路)|不知道怎么说)[。.!！?？\s]*$/.test(previous.trim()) || previous.trim().length < 8))
    turn.scaffold = `可以从半句话开始，不必一次完成。${scaffold}`;
  session.turns.push(turn);
  session.current_question_id = turn.question_id;
  session.next_entry = {question_id: turn.question_id, angle, angle_name: turn.angle_name, question, scaffold: turn.scaffold};
  session.due_at = (session.activity || 'thinking') === 'thinking' ? now + session.settings.response_timeout_ms : null;
  session.nudge_count = 0;
  session.last_nudge_at = null;
  return session.next_entry;
}
function configure(settings, patch) {
  if (!patch || typeof patch !== 'object' || Array.isArray(patch)) fail('INVALID_EVENT', 'settings must be an object.');
  const next = {...settings};
  for (const key of Object.keys(patch)) {
    if (!Object.prototype.hasOwnProperty.call(next, key)) fail('INVALID_EVENT', `Unsupported setting: ${key}.`);
    next[key] = patch[key];
  }
  if (!['gentle', 'standard', 'strict'].includes(next.mode)) fail('INVALID_EVENT', 'Unknown interaction mode.');
  for (const key of ['response_timeout_ms', 'nudge_gap_ms']) {
    if (!Number.isSafeInteger(next[key]) || next[key] < 1000 || next[key] > 86400000)
      fail('INVALID_EVENT', `${key} must be between 1000 and 86400000 ms.`);
  }
  if (!Number.isInteger(next.max_nudges) || next.max_nudges < 0 || next.max_nudges > 10)
    fail('INVALID_EVENT', 'max_nudges must be an integer between 0 and 10.');
  return next;
}

function transition(input, rawEvent, rawNow) {
  validateState(input);
  if (!rawEvent || typeof rawEvent !== 'object' || Array.isArray(rawEvent)) fail('INVALID_EVENT', 'An event object is required.');
  const event = clone(rawEvent);
  const id = text(event.event_id, 'event_id', 256);
  if (!EVENT_TYPES.has(event.type)) fail('INVALID_EVENT', 'Unknown event type.');
  const now = time(rawNow);
  // Dedupe precedes revision checking so retransmitting a committed request is safe.
  const committed = input.processed.find(item => (typeof item === 'string' ? item : item.event_id) === id);
  if (committed) return {state: snapshot(input), result: {ok: true, duplicate: true,
    ...(typeof committed === 'object' ? {original_result: clone(committed.result)} : {})}, effects: []};
  if (event.expected_revision !== undefined && event.expected_revision !== input.revision)
    fail('STALE_REVISION', 'The persisted state changed; reload before applying this event.');
  if (input.processed.length >= MAX_EVENTS) fail('EVENT_LIMIT', 'Export and rotate this ledger before adding more events.');
  const state = snapshot(input);
  const effects = [];
  let result = {ok: true};

  switch (event.type) {
    case 'configure': {
      state.settings = configure(state.settings, event.settings || event.patch);
      if (state.session && state.session.status !== 'COMPLETE') state.session.settings = clone(state.settings);
      result.settings = clone(state.settings);
      break;
    }
    case 'start': {
      if (state.session && state.session.status !== 'COMPLETE')
        fail('SESSION_IN_PROGRESS', 'Finish the existing session or resume its retained entry first.');
      const sessionId = text(event.session_id, 'session_id', 128);
      if (state.session?.id === sessionId || state.history.some(item => item.id === sessionId))
        fail('SESSION_EXISTS', 'Session IDs must be unique.');
      const angle = event.angle === undefined ? 1 : event.angle;
      if (!Number.isInteger(angle) || angle < 1 || angle > 6) fail('INVALID_EVENT', 'angle must be 1..6.');
      if (state.session) state.history.push(state.session);
      state.session = {id: sessionId, object: text(event.object, 'object'),
        title: text(event.title, 'title', 500), goal: text(event.goal, 'goal', 3000),
        status: 'ACTIVE', started_at: now, updated_at: now, completed_at: null,
        turns: [], cognition: [], settings: clone(state.settings), archive: {status: 'pending'},
        due_at: null, nudge_count: 0, last_nudge_at: null, current_question_id: null,
        next_entry: null, last_step: null, result: null,
        materials: [], activity: 'thinking', activity_note: '', checkpoint_at: null, return_point: null};
      result.next_entry = makeQuestion(state.session, angle, null, now);
      result.session_id = sessionId;
      break;
    }
    case 'answer': {
      text(event.session_id, 'session_id', 128);
      text(event.question_id, 'question_id', 300);
      const session = sessionFor(state, event.session_id, true);
      if (event.source !== 'USER') fail('INVALID_SOURCE', 'Only source USER can record the user’s own processing.');
      if (event.question_id !== session.current_question_id) fail('STALE_QUESTION', 'This answer belongs to a prior question.');
      const answer = text(event.text, 'text');
      const turn = session.turns[session.turns.length - 1];
      if (turn.user_answer !== null) fail('STALE_QUESTION', 'This question already has an answer.');
      turn.user_answer = answer;
      turn.answered_at = now;
      session.last_step = {question_id: turn.question_id, question: turn.question, user_answer: answer, at: now};
      session.cognition.push({question_id: turn.question_id, text: answer, source: 'USER', at: now});
      session.activity = 'thinking';
      session.checkpoint_at = null;
      result.next_entry = makeQuestion(session, turn.angle % 6 + 1, answer, now);
      result.processed_user_answer = true;
      break;
    }
    case 'ai_contribution': {
      const session = sessionFor(state, event.session_id);
      const source = text(event.source, 'source', 128);
      if (source === 'USER') fail('INVALID_SOURCE', 'AI contributions must have a distinct source.');
      const turn = session.turns.find(item => item.question_id === (event.question_id || session.current_question_id));
      if (!turn) fail('STALE_QUESTION', 'Contribution refers to an unknown question.');
      turn.ai_contributions.push({text: text(event.text, 'text'), source, at: now});
      result.processed_user_answer = false;
      break;
    }
    case 'set_next_entry': {
      const session = sessionFor(state, event.session_id, true);
      if (event.question_id !== session.current_question_id) fail('STALE_QUESTION', 'Only the current unanswered entry can be updated.');
      const source = text(event.source || 'OPERIT_AI', 'source', 128);
      if (source === 'USER') fail('INVALID_SOURCE', 'A semantic coaching prompt is an AI contribution, not a user answer.');
      const turn = session.turns[session.turns.length - 1];
      const angle = event.angle === undefined ? turn.angle : event.angle;
      if (!Number.isInteger(angle) || angle < 1 || angle > 6) fail('INVALID_EVENT', 'angle must be 1..6.');
      const question = event.question === undefined ? turn.question : text(event.question, 'question', 10000);
      const scaffold = event.scaffold === undefined ? turn.scaffold : text(event.scaffold, 'scaffold', 10000);
      if (event.question === undefined && event.scaffold === undefined && event.angle === undefined)
        fail('INVALID_EVENT', 'Provide a coaching question, scaffold or angle.');
      const contribution = {source, at: now, grounding_object: session.object,
        before: {question: turn.question, scaffold: turn.scaffold, angle: turn.angle},
        question, scaffold, angle};
      (turn.entry_updates ||= []).push(contribution);
      turn.question = question;
      turn.scaffold = scaffold;
      turn.angle = angle;
      turn.angle_name = ANGLES[angle - 1];
      session.next_entry = {question_id: turn.question_id, angle,
        angle_name: turn.angle_name, question, scaffold};
      result.next_entry = clone(session.next_entry);
      result.processed_user_answer = false;
      break;
    }
    case 'attach_material': {
      const session = sessionFor(state, event.session_id);
      if (session.status === 'COMPLETE') fail('SESSION_COMPLETE', 'No new materials after completion.');
      const m = event.material;
      if (!m || !m.source || !['file','web','page','voice','user','pc'].includes(m.source.kind)) fail('INVALID_EVENT', 'Material needs a source kind.');
      const source = {kind:m.source.kind, ref:text(m.source.ref,'source.ref',3000),
        observed_at:time(m.source.observed_at), device:text(m.source.device,'source.device',200)};
      if (source.observed_at > now) fail('INVALID_TIME', 'Material observation cannot be in the future.');
      const status = m.status || 'reported';
      if (!['reported','observed'].includes(status)) fail('INVALID_EVENT', 'Unknown material status.');
      if (status === 'observed' && !evidence(m.evidence)) fail('EVIDENCE_REQUIRED', 'Observed material requires source evidence.');
      const material = {id:text(m.id,'material.id',256), title:text(m.title,'material.title',500),
        text:text(m.text,'material.text',30000), source, status,
        position:typeof m.position === 'string' ? text(m.position,'material.position',3000) : null,
        evidence:clone(m.evidence || null), saved_at:now};
      session.materials ||= [];
      const index = session.materials.findIndex(item => item.id === material.id);
      if (index !== -1 && session.materials[index].source.observed_at > source.observed_at) fail('STALE_MATERIAL', 'Older observation cannot replace newer material.');
      if (index === -1) {
        if (session.materials.length >= 100) fail('MATERIAL_LIMIT','Export materials before adding more.');
        session.materials.push(material);
      } else session.materials[index] = material;
      result.material = clone(material);
      result.processed_user_answer = false;
      break;
    }
    case 'set_return_point': {
      const session = sessionFor(state,event.session_id);
      if (session.status === 'COMPLETE') fail('SESSION_COMPLETE','Cannot change a completed task.');
      const p = event.return_point;
      if (!p || typeof p !== 'object' || Array.isArray(p)) fail('INVALID_EVENT','Return point must be an object.');
      session.return_point = {ref:text(p.ref,'return_point.ref',3000), position:text(p.position,'return_point.position',3000),
        note:typeof p.note === 'string' ? p.note.slice(0,3000) : '', saved_at:now};
      result.return_point = clone(session.return_point);
      break;
    }
    case 'set_activity': {
      const session = sessionFor(state,event.session_id);
      if (session.status === 'COMPLETE') fail('SESSION_COMPLETE','Cannot change a completed task.');
      if (!['thinking','watching','acting','resting'].includes(event.activity)) fail('INVALID_EVENT','Unknown activity.');
      const checkpoint = event.checkpoint_at === undefined || event.checkpoint_at === null ? null : time(event.checkpoint_at);
      if (checkpoint !== null && (checkpoint <= now || checkpoint > now + 86400000)) fail('INVALID_TIME','Checkpoint must be within the next 24 hours.');
      if (event.activity === 'thinking' && checkpoint !== null) fail('INVALID_EVENT','Thinking uses its response deadline, not a checkpoint.');
      session.activity = event.activity;
      session.activity_note = typeof event.note === 'string' ? event.note.slice(0,3000) : '';
      session.checkpoint_at = checkpoint;
      session.due_at = session.status !== 'ACTIVE' || session.nudge_count >= session.settings.max_nudges ? null :
        event.activity === 'thinking' ? now + session.settings.response_timeout_ms : checkpoint;
      result.activity = session.activity;
      result.due_at = session.due_at;
      // Switching activity changes delivery purpose. Retire old unclaimed reminders.
      for (const item of state.outbox) if (item.session_id === session.id && item.status === 'pending') {
        item.status = 'cancelled'; item.delivery = {status:'cancelled',at:now,reason:'activity_changed'};
      }
      break;
    }
    case 'tick': {
      const session = state.session;
      if (!session || session.status !== 'ACTIVE' || session.due_at === null || now < session.due_at) {
        result.waiting = true;
        break;
      }
      if (session.nudge_count >= session.settings.max_nudges) {
        session.due_at = null;
        result.nudges_exhausted = true;
        break;
      }
      if (session.last_nudge_at !== null && now - session.last_nudge_at < session.settings.nudge_gap_ms) {
        result.waiting = true;
        break;
      }
      const number = session.nudge_count + 1;
      const outboxId = `${session.current_question_id}:nudge:${number}`;
      const item = {id: outboxId, outbox_id: outboxId, type: 'nudge', status: 'pending',
        session_id: session.id, question_id: session.current_question_id, created_at: now,
        mode: session.settings.mode, activity: session.activity || 'thinking', question: session.next_entry.question,
        scaffold: session.next_entry.scaffold,
        text: reminderText(session),
        delivery: {status: 'pending'}, attempts: []};
      if (!state.outbox.some(entry => entry.id === outboxId)) state.outbox.push(item);
      session.nudge_count = number;
      session.last_nudge_at = now;
      session.due_at = number >= session.settings.max_nudges || (session.activity || 'thinking') !== 'thinking' ? null : now + session.settings.nudge_gap_ms;
      result.outbox_id = outboxId;
      result.nudges_exhausted = session.due_at === null;
      break;
    }
    case 'pause': {
      const session = sessionFor(state, event.session_id);
      if (session.status === 'COMPLETE') fail('SESSION_COMPLETE', 'The session is already complete.');
      if (session.status === 'PAUSED') { result.already_paused = true; break; }
      session.status = 'PAUSED';
      session.paused_at = now;
      session.pause_reason = typeof event.reason === 'string' ? event.reason : '';
      session.remaining_ms = session.due_at === null ? null : Math.max(0, session.due_at - now);
      session.due_at = null;
      result.next_entry = clone(session.next_entry);
      break;
    }
    case 'resume': {
      const session = sessionFor(state, event.session_id);
      if (session.status === 'COMPLETE') fail('SESSION_COMPLETE', 'The session is already complete.');
      if (session.status === 'ACTIVE') { result.already_active = true; result.next_entry = clone(session.next_entry); break; }
      session.status = 'ACTIVE';
      session.resumed_at = now;
      // A resume is an explicit new participation window, with the same question.
      // Preserve per-question counts: repeated pause/resume cannot defeat the cap
      // or reuse a deterministic outbox ID that was already delivered/cancelled.
      session.due_at = session.nudge_count >= session.settings.max_nudges ? null :
        (session.activity || 'thinking') === 'thinking' ? now + session.settings.response_timeout_ms :
          (session.checkpoint_at > now ? session.checkpoint_at : null);
      result.next_entry = clone(session.next_entry);
      result.last_step = clone(session.last_step);
      break;
    }
    case 'finish': {
      const session = sessionFor(state, event.session_id);
      if (session.status === 'COMPLETE') { result.already_complete = true; result.artifact = renderArtifact(state); break; }
      session.status = 'COMPLETE';
      session.completed_at = now;
      session.due_at = null;
      session.result = {summary: typeof event.summary === 'string' ? event.summary : null,
        user_answer_count: session.cognition.length,
        next_entry: clone(session.next_entry), source: 'EXPLICIT_FINISH'};
      result.artifact = renderArtifact(state);
      break;
    }
    case 'request_action': {
      const session = sessionFor(state, event.session_id);
      const operationId = text(event.operation_id, 'operation_id', 256);
      const kind = text(event.kind, 'kind', 128);
      const payload = clone(event.payload === undefined ? {} : event.payload);
      const existing = state.actions.find(action => action.operation_id === operationId);
      if (existing) {
        if (existing.session_id !== session.id || existing.kind !== kind || JSON.stringify(existing.payload) !== JSON.stringify(payload))
          fail('OPERATION_CONFLICT', 'An operation ID cannot be reused with a different request.');
        result.reused = true;
        result.action = clone(existing);
        break;
      }
      if (session.status === 'COMPLETE') fail('SESSION_COMPLETE', 'No new execution requests are allowed after completion.');
      const action = {operation_id: operationId, session_id: session.id, kind, payload,
        status: 'requested', requested_at: now, evidence: null, updates: []};
      state.actions.push(action);
      effects.push({type: 'external_action', operation_id: operationId, kind, payload, session_id: session.id});
      result.action = clone(action);
      break;
    }
    case 'action_result': {
      const action = state.actions.find(item => item.operation_id === event.operation_id);
      if (!action) fail('UNKNOWN_OPERATION', 'No matching action request exists.');
      if (event.session_id !== undefined && action.session_id !== event.session_id) fail('STALE_SESSION', 'The action belongs to another session.');
      if (!['accepted', 'verified', 'failed', 'unknown'].includes(event.status)) fail('INVALID_EVENT', 'Unknown action result status.');
      if (event.status === 'verified' && !evidence(event.evidence || event.evidence_ref)) fail('EVIDENCE_REQUIRED', 'Verified actions require an evidence reference.');
      if (action.status === 'verified' && event.status !== 'verified') fail('RESULT_REGRESSION', 'A verified result cannot be downgraded.');
      action.status = event.status;
      action.evidence = clone(event.evidence || event.evidence_ref || null);
      action.updated_at = now;
      action.updates.push({status: event.status, evidence: action.evidence,
        detail: typeof event.detail === 'string' ? event.detail : '', at: now});
      result.action = clone(action);
      break;
    }
    case 'record_action': {
      const session = sessionFor(state, event.session_id);
      const status = event.status || 'reported';
      if (!['reported', 'verified'].includes(status)) fail('INVALID_EVENT', 'Recorded real actions must be reported or verified.');
      if (status === 'verified' && !evidence(event.evidence || event.evidence_ref)) fail('EVIDENCE_REQUIRED', 'Verified real actions require evidence.');
      const operationId = event.operation_id || `reported:${id}`;
      if (state.actions.some(item => item.operation_id === operationId)) fail('OPERATION_CONFLICT', 'This operation has already been recorded.');
      state.actions.push({operation_id: operationId, session_id: session.id, kind: 'real_action',
        text: text(event.text, 'text'), source: event.source || 'USER_REPORT', status,
        evidence: clone(event.evidence || event.evidence_ref || null), requested_at: now, updates: []});
      result.operation_id = operationId;
      break;
    }
    case 'delivery_result': {
      const item = state.outbox.find(entry => entry.id === (event.outbox_id || event.id));
      if (!item) fail('UNKNOWN_DELIVERY', 'No matching outbox entry exists.');
      if (!['accepted', 'observed', 'failed', 'unknown'].includes(event.status)) fail('INVALID_EVENT', 'Unknown delivery status.');
      if (event.status === 'observed' && !evidence(event.evidence || event.evidence_ref)) fail('EVIDENCE_REQUIRED', 'Observed delivery requires evidence.');
      if (item.status === 'observed' && event.status !== 'observed') fail('RESULT_REGRESSION', 'Observed delivery cannot be downgraded.');
      item.status = event.status;
      item.delivery = {status: event.status, at: now, evidence: clone(event.evidence || event.evidence_ref || null)};
      item.attempts.push(clone(item.delivery));
      result.delivery = clone(item.delivery);
      // accepted means host accepted delivery, not that a person saw or heard it.
      break;
    }
    case 'archive_result': {
      const session = findSession(state, event.session_id || state.session?.id);
      if (!['synced', 'pending', 'failed', 'unknown'].includes(event.status)) fail('INVALID_EVENT', 'Unknown archive status.');
      if (event.status === 'synced') {
        text(event.ref, 'ref', 3000);
        if (!evidence(event.evidence || event.readback_evidence)) fail('EVIDENCE_REQUIRED', 'ima synchronization requires readback evidence.');
      }
      session.archive = {status: event.status, ref: event.ref || null,
        evidence: clone(event.evidence || event.readback_evidence || null), updated_at: now};
      result.archive = clone(session.archive);
      break;
    }
  }
  if (state.session && state.session.archive.status === 'synced' &&
      ['answer','ai_contribution','set_next_entry','record_action','request_action','finish','attach_material','set_activity','set_return_point'].includes(event.type) && !result.already_complete) {
    state.session.archive = {status:'pending',previous:clone(state.session.archive),
      reason:'content_changed_since_last_archive',updated_at:now};
  }
  if (state.session) state.session.updated_at = now;
  // Retire obsolete, unclaimed notifications when the continuation changes.
  for (const item of state.outbox) {
    if (item.status === 'pending' && (!state.session || item.session_id !== state.session.id ||
        item.question_id !== state.session.current_question_id || state.session.status !== 'ACTIVE')) {
      item.status = 'cancelled';
      item.delivery = {status: 'cancelled', at: now, reason: 'continuation_changed'};
    } else if (item.status === 'pending' && state.session?.next_entry) {
      // Semantic coaching may update a question before the host claims delivery.
      // The queued prompt must follow that saved entry rather than stale wording.
      item.question = state.session.next_entry.question;
      item.scaffold = state.session.next_entry.scaffold;
      item.mode = state.session.settings.mode;
      item.text = reminderText(state.session);
    }
  }
  state.revision += 1;
  state.processed.push({event_id: id, type: event.type, at: now, result: clone(result)});
  return {state, result: clone(result), effects: clone(effects)};
}

function renderArtifact(state, sessionId) {
  validateState(state);
  const session = sessionId ? findSession(state, sessionId) : state.session;
  if (!session) return '# 认知与行动记录\n\n尚未开始任务。\n';
  const lines = [`# ${session.title}`, '', `会话：${session.id}`, `状态：${session.status}`,
    '', '## 当前处理对象', '', session.object, '', '## 本次目标', '', session.goal,
    '', '## 材料与现场', '', `当前活动：${session.activity || 'thinking'}`, session.activity_note || '', ''];
  for (const material of session.materials || []) lines.push(`### ${material.title}`, '',
    `来源：${material.source.kind} · ${material.source.ref} · ${material.source.device}`,
    `采集时间：${material.source.observed_at} · ${material.status}`, `位置：${material.position || '未提供'}`, '', material.text, '');
  if (session.return_point) lines.push('返回入口：'+session.return_point.ref,'位置：'+session.return_point.position,session.return_point.note,'');
  lines.push('## 我的加工记录','');
  for (const turn of session.turns) {
    lines.push(`### ${turn.question_id} · ${turn.angle_name}`, '', turn.question, '',
      `作答支架：${turn.scaffold}`, '', turn.user_answer === null ? '我的回答：尚未回答。' : `我的回答：\n\n${turn.user_answer}`, '');
  }
  lines.push('## AI 提供的内容（与我的回答分开）', '');
  let contributions = 0;
  for (const turn of session.turns) for (const contribution of turn.ai_contributions) {
    contributions++;
    lines.push(`### ${contribution.source} · ${turn.question_id}`, '', contribution.text, '');
  }
  for (const turn of session.turns) for (const update of turn.entry_updates || []) {
    contributions++;
    lines.push(`### ${update.source} · ${turn.question_id} · 问题与支架调整`, '',
      `原问题：${update.before.question}`, '', `新问题：${update.question}`, '', update.scaffold, '');
  }
  if (!contributions) lines.push('尚无 AI 内容记录。', '');
  lines.push('## 现实行动与执行证据', '');
  const actions = state.actions.filter(action => action.session_id === session.id);
  if (!actions.length) lines.push('尚无现实行动记录；回答与行动计划不等于已经执行。', '');
  for (const action of actions) lines.push(`- ${action.operation_id} · ${action.kind} · ${action.status}`,
    `  内容：${action.text || JSON.stringify(action.payload)}`,
    `  证据：${action.evidence ? JSON.stringify(action.evidence) : '尚无执行验证证据'}`, '');
  lines.push('## 下次接续入口', '');
  if (session.last_step) lines.push(`上一步：${session.last_step.user_answer}`, '');
  if (session.next_entry) lines.push(session.next_entry.question, '', session.next_entry.scaffold, '');
  if (session.result?.summary) lines.push('## 结束时补充', '', session.result.summary, '');
  lines.push('## ima 归档状态', '', `${session.archive.status}${session.archive.ref ? ` · ${session.archive.ref}` : ''}`, '');
  return lines.join('\n');
}

module.exports = {createState, transition, snapshot, renderArtifact};
