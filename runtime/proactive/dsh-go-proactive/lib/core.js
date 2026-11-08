// dsh-go-proactive, pure core: one record per proactive request, driven by session events.
//
// What each state proves (and nothing more):
//   accepted   the message is in the agent's inbox (agent.followup returned without throwing)
//   claimed    the loop took it: its user/message is in the session log and a turn is running
//   generated  that turn ended `completed` (or `max-tokens`) with visible assistant text
//   failed     the turn ended otherwise, the agent went away, or the deadline passed
//   cancelled  the App withdrew it; any later text is never handed out
// None of these says the person saw anything: that is the App's InteractionHub, not the engine.
//
// Verified against @deepseek-ai/dsh 0.2.1-rc.4 (the version dsh-go pins):
//   - agents.get(id) returns only live agents; status is "idle" | "running"
//   - followup(message) is synchronous there (send to the "next-turn" inbox + wake); a running
//     agent queues it for the turn after the current one, so we refuse busy agents instead
//   - session events: turn/start {turn}; user/message = the message itself; assistant/message
//     {turn, step, message:{content}, interrupted?}; turn/end {turn, reason:{kind}}
//   - only role "user" is accepted for inbox messages (dsh-speak-up's finding, see README)

export const SOURCE_KIND = 'dsh-go-proactive'
const ID_RE = /^[A-Za-z0-9_-]{8,128}$/
const TERMINAL = new Set(['generated', 'failed', 'cancelled'])

export const DEFAULT_LIMITS = Object.freeze({
  minDeadlineMs: 5_000,
  maxDeadlineMs: 120_000,
  maxPayloadKeys: 16,
  maxPayloadValue: 200,
  maxText: 2_000,
  keepRecords: 200,
  keepMs: 3_600_000,
})

export const DEFAULT_INSTRUCTION =
  '（下面不是用户说的话，是 DSH Go 手机端发来的一条主动交互事件。请据此主动对用户说一两句自然、简短的话；' +
  '不要调用任何工具，尤其不要操作手机；不要提到“事件”“系统”这些字眼。）'

/** @returns {{code: string, status: number}|null} */
function bad(code, status = 400) { return { code, status } }

export function validateRequest(body, limits = DEFAULT_LIMITS) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) return { error: bad('bad-request') }
  if (body.v !== undefined && body.v !== 1) return { error: bad('unsupported-version') }
  const { requestId, eventId, kind = 'event', sessionId, payload = {}, deadlineMs = 45_000 } = body
  if (typeof requestId !== 'string' || !ID_RE.test(requestId)) return { error: bad('bad-request-id') }
  if (typeof eventId !== 'string' || !ID_RE.test(eventId)) return { error: bad('bad-event-id') }
  if (typeof kind !== 'string' || kind.length === 0 || kind.length > 64) return { error: bad('bad-kind') }
  if (sessionId !== undefined && (typeof sessionId !== 'string' || sessionId.length === 0 || sessionId.length > 256)) return { error: bad('bad-session-id') }
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return { error: bad('bad-payload') }
  const entries = Object.entries(payload)
  if (entries.length > limits.maxPayloadKeys) return { error: bad('too-large', 413) }
  for (const [k, v] of entries) {
    if (typeof v !== 'string' || k.length > 64 || v.length > limits.maxPayloadValue) return { error: bad('bad-payload') }
  }
  if (!Number.isInteger(deadlineMs)) return { error: bad('bad-deadline') }
  return {
    value: {
      requestId, eventId, kind, sessionId, payload: Object.fromEntries(entries),
      deadlineMs: Math.min(Math.max(deadlineMs, limits.minDeadlineMs), limits.maxDeadlineMs),
    },
  }
}

/** The text the agent receives. Facts only; never screen contents (the App never sends any). */
export function composeText(req, instruction = DEFAULT_INSTRUCTION) {
  const facts = [`kind=${req.kind}`, ...Object.entries(req.payload).map(([k, v]) => `${k}=${v}`)]
  return `${instruction}\n${facts.join('\n')}`
}

/** Visible text of an assistant/message event's message (text blocks only). */
export function assistantText(data) {
  const content = data?.message?.content
  if (!Array.isArray(content)) return ''
  return content.filter((b) => b && b.type === 'text' && typeof b.text === 'string').map((b) => b.text).join('').trim()
}

/**
 * The desk: request records and the transitions above. Everything that touches DSH is injected,
 * so the same code runs against the real engine (lib/index.js) and in unit tests.
 */
export class Desk {
  /**
   * @param {object} deps
   * @param {(id: string) => any} deps.findAgent  live agent or undefined
   * @param {(id: string) => boolean} [deps.isArchived]
   * @param {(text: string) => any} deps.makeMessage  a user message with `id`
   * @param {() => number} [deps.now]
   * @param {object} [deps.limits]
   * @param {() => string|undefined} [deps.defaultSession]
   * @param {string} [deps.instruction]
   */
  constructor(deps) {
    this.deps = { now: Date.now, isArchived: () => false, defaultSession: () => undefined, limits: DEFAULT_LIMITS, ...deps }
    /** @type {Map<string, any>} */
    this.records = new Map()
    /** sessionId -> current turn number seen in turn/start */
    this.turns = new Map()
    this.waiters = new Map()
    /** sessionId -> the turn that is running on one of our messages (tools are denied in it). */
    this.proactiveTurns = new Map()
    this.counters = { accepted: 0, refused: 0, generated: 0, failed: 0, cancelled: 0, lateText: 0, toolDenied: 0 }
  }

  now() { return this.deps.now() }

  view(r) {
    if (!r) return undefined
    return {
      v: 1, requestId: r.requestId, eventId: r.eventId, sessionId: r.sessionId, state: r.state,
      acceptedAt: r.acceptedAt, claimedAt: r.claimedAt ?? null, turn: r.turn ?? null,
      generatedAt: r.generatedAt ?? null, text: r.state === 'generated' ? r.text : null,
      truncated: r.truncated ?? false, failure: r.failure ?? null, cancelledAt: r.cancelledAt ?? null,
      deadline: r.deadline,
    }
  }

  /** POST /request. Returns {status, body}. Never calls followup twice for one requestId or eventId. */
  request(raw) {
    const { value: req, error } = validateRequest(raw, this.deps.limits)
    if (error) return this.refuse(error.status, error.code)
    this.sweep()
    const same = this.records.get(req.requestId)
    if (same) {
      if (same.eventId !== req.eventId) return this.refuse(409, 'request-id-conflict')
      return { status: 200, body: this.view(same) } // replay: report, do not send again
    }
    for (const r of this.records.values()) {
      if (r.eventId === req.eventId && !TERMINAL.has(r.state)) return this.refuse(409, 'event-in-flight')
      if (!TERMINAL.has(r.state)) return this.refuse(409, 'desk-busy')
    }
    const sessionId = req.sessionId ?? this.deps.defaultSession()
    if (!sessionId) return this.refuse(409, 'no-session')
    if (this.deps.isArchived(sessionId)) return this.refuse(409, 'archived')
    const agent = this.deps.findAgent(sessionId)
    if (agent === undefined) return this.refuse(409, 'no-agent') // never woken or created here
    if (agent.status !== undefined && agent.status !== 'idle') return this.refuse(409, 'agent-busy')
    if (agent.inbox?.hasPending) return this.refuse(409, 'agent-busy')

    const message = this.deps.makeMessage(composeText(req, this.deps.instruction))
    try {
      const ret = agent.followup(message)
      // Newer DSH versions may return a Promise; never leave its rejection unhandled.
      if (ret && typeof ret.then === 'function') ret.then(undefined, (e) => this.fail(req.requestId, 'followup-rejected', String(e?.message ?? e)))
    } catch (e) {
      return this.refuse(409, 'followup-failed', String(e?.message ?? e))
    }
    const now = this.now()
    const r = {
      ...req, sessionId: agent.id ?? sessionId, messageId: message.id, state: 'accepted',
      acceptedAt: now, deadline: now + req.deadlineMs,
    }
    this.records.set(req.requestId, r)
    this.counters.accepted++
    return { status: 202, body: this.view(r) }
  }

  refuse(status, code, detail) {
    this.counters.refused++
    return { status, body: { v: 1, error: { code, message: detail ?? code } } }
  }

  /** GET /result. */
  result(requestId) {
    this.sweep()
    const r = this.records.get(requestId)
    return r ? { status: 200, body: this.view(r) } : { status: 404, body: { v: 1, error: { code: 'not-found', message: 'unknown request' } } }
  }

  /** Resolves when the record changes or after waitMs; the long poll behind GET /result?waitMs. */
  async wait(requestId, waitMs) {
    const r = this.records.get(requestId)
    if (!r || TERMINAL.has(r.state) || waitMs <= 0) return this.result(requestId)
    await new Promise((resolve) => {
      const list = this.waiters.get(requestId) ?? []
      const timer = setTimeout(done, Math.min(waitMs, 25_000))
      function done() { clearTimeout(timer); resolve() }
      list.push(done)
      this.waiters.set(requestId, list)
    })
    return this.result(requestId)
  }

  /**
   * POST /cancel. The record stops; text that arrives later is dropped. The agent's turn is not
   * aborted: a turn already running belongs to the session, and its text stays in the history.
   */
  cancel(requestId) {
    const r = this.records.get(requestId)
    if (!r) return { status: 404, body: { v: 1, error: { code: 'not-found', message: 'unknown request' } } }
    if (!TERMINAL.has(r.state)) {
      r.state = 'cancelled'
      r.cancelledAt = this.now()
      this.counters.cancelled++
      this.wake(requestId)
    }
    return { status: 200, body: this.view(r) }
  }

  /** One session/event for `sessionId`. */
  onSessionEvent(sessionId, event) {
    if (!event || typeof event.type !== 'string') return
    const data = event.data
    if (event.type === 'turn/start' && Number.isInteger(data?.turn)) { this.turns.set(sessionId, data.turn); return }
    if (event.type === 'turn/end' && this.proactiveTurns.get(sessionId) === data?.turn) this.proactiveTurns.delete(sessionId)
    for (const r of this.records.values()) {
      if (r.sessionId !== sessionId) continue
      if (event.type === 'user/message' && data?.id === r.messageId) {
        // Whatever the record's state (even cancelled), this turn runs on our message: no tools in it.
        const turn = this.turns.get(sessionId)
        if (turn != null) this.proactiveTurns.set(sessionId, turn)
        if (r.state === 'accepted') {
          r.state = 'claimed'
          r.claimedAt = this.now()
          r.turn = this.turns.get(sessionId) ?? null
          this.wake(r.requestId)
        }
      } else if (event.type === 'assistant/message' && r.turn != null && data?.turn === r.turn) {
        const text = assistantText(data)
        if (text) {
          if (r.state === 'claimed') { r.lastText = text.slice(0, this.deps.limits.maxText); r.interrupted = data.interrupted === true }
          else if (r.state === 'cancelled') this.counters.lateText++
        }
      } else if (event.type === 'turn/end' && r.turn != null && data?.turn === r.turn && r.state === 'claimed') {
        const kind = data?.reason?.kind
        if ((kind === 'completed' || kind === 'max-tokens') && r.lastText && !r.interrupted) {
          r.state = 'generated'
          r.text = r.lastText
          r.truncated = kind === 'max-tokens'
          r.generatedAt = this.now()
          this.counters.generated++
        } else {
          this.fail(r.requestId, kind === 'completed' ? 'no-text' : `turn-${kind ?? 'unknown'}`, data?.reason?.error?.message)
        }
        this.wake(r.requestId)
      }
    }
  }

  /**
   * `tools/pre-execute` guard: a turn started by a proactive event may not run any tool —
   * enforced where tools execute, not only asked for in the prompt. Returns the deny decision,
   * or undefined to let the call continue down the chain.
   */
  guardTool(sessionId) {
    if (sessionId == null || !this.proactiveTurns.has(sessionId)) return undefined
    this.counters.toolDenied++
    return { kind: 'deny', reason: 'dsh-go-proactive: tools are disabled in a proactive turn (no phone control, no files, no commands)' }
  }

  /** The agent of a session went away. */
  onAgentGone(sessionId) {
    this.proactiveTurns.delete(sessionId)
    for (const r of this.records.values()) if (r.sessionId === sessionId && !TERMINAL.has(r.state)) this.fail(r.requestId, 'agent-gone')
  }

  fail(requestId, code, detail) {
    const r = this.records.get(requestId)
    if (!r || TERMINAL.has(r.state)) return
    r.state = 'failed'
    r.failure = { code, ...(detail ? { detail: String(detail).slice(0, 300) } : {}) }
    this.counters.failed++
    this.wake(requestId)
  }

  /** Deadlines and history trimming. Cheap; called on every request and by a slow timer. */
  sweep() {
    const now = this.now()
    for (const r of this.records.values()) {
      // The turn may still finish later; the App has stopped waiting, so its text is not handed out.
      if (!TERMINAL.has(r.state) && now >= r.deadline) this.fail(r.requestId, 'deadline')
    }
    const { keepRecords, keepMs } = this.deps.limits
    for (const [id, r] of this.records) {
      if (this.records.size <= keepRecords && now - r.acceptedAt < keepMs) break
      if (TERMINAL.has(r.state)) this.records.delete(id)
    }
  }

  wake(requestId) {
    const list = this.waiters.get(requestId)
    if (!list) return
    this.waiters.delete(requestId)
    for (const done of list) done()
  }
}
