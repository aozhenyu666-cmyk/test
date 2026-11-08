// dsh-go-companion · 回合状态机
//
// 与 dsh-go-proactive 的协议保持一致（accepted / claimed / generated / failed / cancelled），
// 但多了三件事：
//   1. 冷会话：会话不活着时先 sessionController.resolveAgent()，而不是直接 409；
//   2. 话题：compose() 从本地记忆里选一个未完成话题/近期经历，再拼 prompt；
//   3. 账本：每次状态变化通过 onChange() 记一行。
//
// 状态只证明引擎侧发生了什么：generated 只代表模型出了文字，不代表用户看到。

export const SOURCE_KIND = 'dsh-go-companion'
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

function bad(code, status = 400) {
  return { code, status }
}

export function validateRequest(body, limits = DEFAULT_LIMITS) {
  if (body === null || typeof body !== 'object' || Array.isArray(body)) return { error: bad('bad-request') }
  if (body.v !== undefined && body.v !== 1) return { error: bad('unsupported-version') }
  const { requestId, eventId, kind = 'event', sessionId, payload = {}, deadlineMs = 45_000 } = body
  if (typeof requestId !== 'string' || !ID_RE.test(requestId)) return { error: bad('bad-request-id') }
  if (typeof eventId !== 'string' || !ID_RE.test(eventId)) return { error: bad('bad-event-id') }
  if (typeof kind !== 'string' || kind.length === 0 || kind.length > 64) return { error: bad('bad-kind') }
  if (sessionId !== undefined && (typeof sessionId !== 'string' || sessionId.length === 0 || sessionId.length > 256)) {
    return { error: bad('bad-session-id') }
  }
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return { error: bad('bad-payload') }
  const entries = Object.entries(payload)
  if (entries.length > limits.maxPayloadKeys) return { error: bad('too-large', 413) }
  for (const [k, v] of entries) {
    if (typeof v !== 'string' || k.length > 64 || v.length > limits.maxPayloadValue) return { error: bad('bad-payload') }
  }
  if (!Number.isInteger(deadlineMs)) return { error: bad('bad-deadline') }
  return {
    value: {
      requestId,
      eventId,
      kind,
      sessionId,
      payload: Object.fromEntries(entries),
      deadlineMs: Math.min(Math.max(deadlineMs, limits.minDeadlineMs), limits.maxDeadlineMs),
      // 手机现状（前台 App、停留、今日累计）。只透传对象，字段由 phone.js 的 cleanPhone 洗。
      phone: body.phone && typeof body.phone === 'object' && !Array.isArray(body.phone) ? body.phone : undefined,
    },
  }
}

export function assistantText(data) {
  const content = data?.message?.content
  if (!Array.isArray(content)) return ''
  return content
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('')
    .trim()
}

export class Desk {
  /**
   * @param {object} deps
   * @param {(id: string) => Promise<any|undefined>} deps.resolveAgent
   * @param {(req: object, sessionId: string) => Promise<{text?: string, prompt?: string, topic?: object, error?: string}>} deps.compose
   * @param {(text: string) => any} deps.makeMessage
   * @param {(id: string) => boolean} [deps.isArchived]
   * @param {() => string|undefined} [deps.defaultSession]
   * @param {(record: object) => void} [deps.onChange]
   * @param {() => number} [deps.now]
   * @param {object} [deps.limits]
   */
  constructor(deps) {
    this.deps = {
      now: Date.now,
      isArchived: () => false,
      defaultSession: () => undefined,
      onChange: () => {},
      limits: DEFAULT_LIMITS,
      ...deps,
    }
    this.records = new Map()
    this.turns = new Map()
    this.waiters = new Map()
    this.proactiveTurns = new Map()
    this.counters = {
      accepted: 0,
      refused: 0,
      generated: 0,
      failed: 0,
      cancelled: 0,
      lateText: 0,
      toolDenied: 0,
      coldAgent: 0,
    }
  }

  now() {
    return this.deps.now()
  }

  view(r) {
    if (!r) return undefined
    return {
      v: 1,
      requestId: r.requestId,
      eventId: r.eventId,
      sessionId: r.sessionId,
      state: r.state,
      acceptedAt: r.acceptedAt,
      claimedAt: r.claimedAt ?? null,
      turn: r.turn ?? null,
      generatedAt: r.generatedAt ?? null,
      text: r.state === 'generated' ? r.text : null,
      truncated: r.truncated ?? false,
      failure: r.failure ?? null,
      cancelledAt: r.cancelledAt ?? null,
      deadline: r.deadline,
      topicId: r.topicId ?? null,
      topicKind: r.topicKind ?? null,
      serveType: r.serveType ?? null,
      rallyId: r.rallyId ?? null,
      shot: r.shot ?? null,
      // 漠视阶梯走到哪一级（none/wait/lighter/channel/stop）：事后排查和 P4 换渠道都要看。
      silence: r.silence ?? null,
      // 大脑这一球的决定（水位、脸色、思维题、力度）：只给账本和排查看，手机不用解析。
      extra: r.extra ?? null,
      cold: r.cold === true,
    }
  }

  async request(raw) {
    const { value: req, error } = validateRequest(raw, this.deps.limits)
    if (error) return this.refuse(error.status, error.code)
    this.sweep()
    const same = this.records.get(req.requestId)
    if (same) {
      if (same.eventId !== req.eventId) return this.refuse(409, 'request-id-conflict')
      return { status: 200, body: this.view(same) }
    }
    for (const r of this.records.values()) {
      if (r.eventId === req.eventId && !TERMINAL.has(r.state)) return this.refuse(409, 'event-in-flight')
      // 主动发球同一时间只走一球；直接聊（allowBusy）可以排队，一句卡住不该把后面的话都挡掉。
      if (!TERMINAL.has(r.state) && !this.deps.allowBusy) return this.refuse(409, 'desk-busy')
    }
    const sessionId = req.sessionId ?? this.deps.defaultSession()
    if (!sessionId) return this.refuse(409, 'no-session')
    if (this.deps.isArchived(sessionId)) return this.refuse(409, 'archived')

    let agent
    let cold = false
    try {
      const resolved = await this.deps.resolveAgent(sessionId)
      // The resolver may return the agent itself or {agent, cold}; support both.
      agent = resolved && resolved.agent !== undefined ? resolved.agent : resolved
      cold = resolved?.cold === true
      if (cold && agent !== undefined) this.counters.coldAgent++
    } catch (e) {
      return this.refuse(409, 'agent-resolve-failed', String(e?.message ?? e))
    }
    if (agent === undefined) return this.refuse(409, 'no-agent')
    // 主动发球：他和大肥鱼正在说话就不插嘴。直接聊（allowBusy）：排进下一轮，等它说完这句再接。
    if (!this.deps.allowBusy) {
      if (agent.status !== undefined && agent.status !== 'idle') return this.refuse(409, 'agent-busy')
      if (agent.inbox?.hasPending) return this.refuse(409, 'agent-busy')
    }

    let built
    try {
      built = await this.deps.compose(req, agent.id ?? sessionId)
    } catch (e) {
      return this.refuse(409, 'compose-failed', String(e?.message ?? e))
    }
    if (!built || built.error) return this.refuse(409, built?.error || 'compose-failed')
    const text = String(built.text || '').trim()
    if (!text) return this.refuse(409, 'no-topic')

    const message = this.deps.makeMessage(text)
    try {
      const ret = agent.followup(message)
      if (ret && typeof ret.then === 'function') {
        ret.then(undefined, (e) => this.fail(req.requestId, 'followup-rejected', String(e?.message ?? e)))
      }
    } catch (e) {
      return this.refuse(409, 'followup-failed', String(e?.message ?? e))
    }

    const now = this.now()
    const r = {
      ...req,
      sessionId: agent.id ?? sessionId,
      messageId: message.id,
      state: 'accepted',
      acceptedAt: now,
      deadline: now + req.deadlineMs,
      topicId: built.topic?.id ?? null,
      topicKind: built.topic?.kind ?? null,
      serveType: built.serveType ?? null,
      rallyId: built.rallyId ?? null,
      shot: built.shot ?? null,
      silence: built.silence?.stage ?? null,
      extra: built.extra ?? null,
      cold,
      prompt: built.prompt,
    }
    this.records.set(req.requestId, r)
    this.counters.accepted++
    this.deps.onChange(this.view(r))
    return { status: 202, body: this.view(r) }
  }

  refuse(status, code, detail) {
    this.counters.refused++
    return { status, body: { v: 1, error: { code, message: detail ?? code } } }
  }

  result(requestId) {
    this.sweep()
    const r = this.records.get(requestId)
    return r
      ? { status: 200, body: this.view(r) }
      : { status: 404, body: { v: 1, error: { code: 'not-found', message: 'unknown request' } } }
  }

  async wait(requestId, waitMs) {
    const r = this.records.get(requestId)
    if (!r || TERMINAL.has(r.state) || waitMs <= 0) return this.result(requestId)
    await new Promise((resolve) => {
      const list = this.waiters.get(requestId) ?? []
      const timer = setTimeout(done, Math.min(waitMs, 25_000))
      function done() {
        clearTimeout(timer)
        resolve()
      }
      list.push(done)
      this.waiters.set(requestId, list)
    })
    return this.result(requestId)
  }

  cancel(requestId) {
    const r = this.records.get(requestId)
    if (!r) return { status: 404, body: { v: 1, error: { code: 'not-found', message: 'unknown request' } } }
    if (!TERMINAL.has(r.state)) {
      r.state = 'cancelled'
      r.cancelledAt = this.now()
      this.counters.cancelled++
      this.deps.onChange(this.view(r))
      this.wake(requestId)
    }
    return { status: 200, body: this.view(r) }
  }

  onSessionEvent(sessionId, event) {
    if (!event || typeof event.type !== 'string') return
    const data = event.data
    if (event.type === 'turn/start' && Number.isInteger(data?.turn)) {
      this.turns.set(sessionId, data.turn)
      return
    }
    if (event.type === 'turn/end' && this.proactiveTurns.get(sessionId) === data?.turn) {
      this.proactiveTurns.delete(sessionId)
    }
    for (const r of this.records.values()) {
      if (r.sessionId !== sessionId) continue
      if (event.type === 'user/message' && data?.id === r.messageId) {
        const turn = this.turns.get(sessionId)
        if (turn != null) this.proactiveTurns.set(sessionId, turn)
        if (r.state === 'accepted') {
          r.state = 'claimed'
          r.claimedAt = this.now()
          r.turn = this.turns.get(sessionId) ?? null
          this.deps.onChange(this.view(r))
          this.wake(r.requestId)
        }
      } else if (event.type === 'assistant/message' && r.turn != null && data?.turn === r.turn) {
        const text = assistantText(data)
        if (text) {
          if (r.state === 'claimed') {
            r.lastText = text.slice(0, this.deps.limits.maxText)
            r.interrupted = data.interrupted === true
          } else if (r.state === 'cancelled') this.counters.lateText++
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
          this.fail(r.requestId, kind === 'completed' ? 'no-text' : 'turn-' + String(kind ?? 'unknown'), data?.reason?.error?.message)
          continue
        }
        this.deps.onChange(this.view(r))
        this.wake(r.requestId)
      }
    }
  }

  guardTool(sessionId) {
    if (sessionId == null || !this.proactiveTurns.has(sessionId)) return undefined
    // 他给了高权限：主动找他的那一轮也可以动手（设提醒、退出 App）。DSH 自己的权限审批照常生效。
    if (typeof this.deps.allowTools === 'function' && this.deps.allowTools()) return undefined
    this.counters.toolDenied++
    return {
      kind: 'deny',
      reason: 'dsh-go-companion: tools are disabled in a proactive turn (no phone control, no files, no commands)',
    }
  }

  onAgentGone(sessionId) {
    this.proactiveTurns.delete(sessionId)
    for (const r of this.records.values()) {
      if (r.sessionId === sessionId && !TERMINAL.has(r.state)) this.fail(r.requestId, 'agent-gone')
    }
  }

  fail(requestId, code, detail) {
    const r = this.records.get(requestId)
    if (!r || TERMINAL.has(r.state)) return
    r.state = 'failed'
    r.failure = { code, ...(detail ? { detail: String(detail).slice(0, 300) } : {}) }
    this.counters.failed++
    this.deps.onChange(this.view(r))
    this.wake(requestId)
  }

  /** 有一球已经接下、还没出结果（accepted / claimed）。 */
  inFlight() {
    for (const r of this.records.values()) if (!TERMINAL.has(r.state)) return true
    return false
  }

  sweep() {
    const now = this.now()
    for (const r of this.records.values()) {
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
