// 借 DSH agent 出题：往一个活着、空闲的会话推一条 followup，收它这一回合的文字。
// 和 dsh-go-proactive 用同一套已在真实引擎上核实过的事件：
//   turn/start {turn} → user/message（就是我们那条，带 id）→ assistant/message {turn, message} → turn/end {turn, reason}
// 不新建会话、不唤醒冷会话；拿不到就返回 null，由驾驶员退回模板。

export const SOURCE_KIND = 'dsh-driver-core'

function assistantText(data) {
  const content = data?.message?.content
  if (!Array.isArray(content)) return ''
  return content.filter((b) => b && b.type === 'text' && typeof b.text === 'string').map((b) => b.text).join('').trim()
}

export class AgentBrain {
  /**
   * @param {object} deps
   * @param {(sid:string)=>any} deps.findAgent 只返回活着的 agent
   * @param {(text:string)=>object} deps.makeMessage 造 role=user、source.kind=SOURCE_KIND 的消息
   * @param {()=>string|undefined} deps.sessionId 出题用哪个会话
   */
  constructor(deps) {
    this.deps = deps
    this.turns = new Map() // sid → 当前回合号
    this.pending = null // 一次只出一题
    this.driverTurns = new Map() // sid → 我们发起的回合（这期间禁用工具）
    this.counters = { asked: 0, answered: 0, unavailable: 0, failed: 0, toolDenied: 0 }
  }

  ask(prompt, timeoutMs = 8_000, {rememberLimit = 0} = {}) {
    const sid = this.deps.sessionId()
    const agent = sid ? this.deps.findAgent(sid) : undefined
    if (!agent || (agent.status !== undefined && agent.status !== 'idle') || this.pending || this.driverTurns.has(sid)) {
      this.counters.unavailable++
      return Promise.resolve(null)
    }
    const message = this.deps.makeMessage(prompt)
    return new Promise((resolve) => {
      const done = (text) => {
        if (this.pending?.messageId !== message.id) return
        clearTimeout(this.pending.timer)
        this.pending = null
        resolve(text)
      }
      this.pending = { sid, messageId: message.id, turn: null, text: '', done, rememberLimit, timer: setTimeout(() => { this.counters.failed++; done(null) }, timeoutMs) }
      this.counters.asked++
      try {
        const r = agent.followup(message)
        if (r && typeof r.then === 'function') r.then(undefined, () => { this.counters.failed++; done(null) })
      } catch {
        this.counters.failed++
        done(null)
      }
    })
  }

  onSessionEvent(sid, event) {
    if (!event || typeof event.type !== 'string') return
    const data = event.data
    if (event.type === 'turn/start' && Number.isInteger(data?.turn)) { this.turns.set(sid, data.turn); return }
    if (event.type === 'turn/end' && this.driverTurns.get(sid)?.turn === data?.turn) this.driverTurns.delete(sid)
    const p = this.pending
    if (!p || p.sid !== sid) return
    if (event.type === 'user/message' && data?.id === p.messageId) {
      p.turn = this.turns.get(sid) ?? null
      if (p.turn != null) this.driverTurns.set(sid, {turn:p.turn,rememberLeft:p.rememberLimit})
    } else if (event.type === 'assistant/message' && p.turn != null && data?.turn === p.turn) {
      const t = assistantText(data)
      if (t) p.text = t
    } else if (event.type === 'turn/end' && p.turn != null && data?.turn === p.turn) {
      const kind = data?.reason?.kind
      if ((kind === 'completed' || kind === 'max-tokens') && p.text) { this.counters.answered++; p.done(p.text) } else { this.counters.failed++; p.done(null) }
    }
  }

  /** 驾驶员发起的回合里不许调工具（不操作手机、不读写文件）。 */
  guardTool(sid, tool) {
    if (sid == null || !this.driverTurns.has(sid)) return undefined
    const turn=this.driverTurns.get(sid)
    const name=typeof tool==='string'?tool:tool?.name
    if (name==='remember' && turn.rememberLeft>0) {turn.rememberLeft--;return undefined}
    this.counters.toolDenied++
    return { kind: 'deny', reason: 'dsh-driver-core: tools are disabled in a driver turn' }
  }

  onAgentGone(sid) {
    this.driverTurns.delete(sid)
    if (this.pending?.sid === sid) { this.counters.failed++; this.pending.done(null) }
  }
}
