// dsh-driver-core 主控：15 分钟锁定思考会话的状态机。
//
//   IDLE ──start──▶ LOCKED ──切出允许的应用 / Live 结束──▶ INTERRUPTED_BY_SWITCH
//                    │  ▲                                        │
//                    │  └──────── 有效微反馈（RESUMED）◀──────────┘
//                    ├──声明休息(带恢复时间)──▶ PAUSED ──到点──▶ INTERRUPTED_BY_SWITCH（拉回断点）
//                    ├──到时间──▶ WRAPPING ──给出一句结论──▶ ENDED(completed)
//                    │                     └──超时──────────▶ ENDED(no_conclusion)
//                    └──紧急结束（永远放行）──────────────────▶ ENDED(stopped)
//
// 切屏只会进入 INTERRUPTED_BY_SWITCH，从不自动结案。升级：第 1 次温和拉回（level 1），
// 连续第 2 次不应答或强行切走（level 2）：全屏 + 语音，直到有效微反馈或声明休息。
//
// 什么时候主动开口（都走同一个唤醒账本，同一原因有冷却、任意两次之间至少隔 wakeMinGapMs）：
//   turn_gap  Gemini 刚说完一段，8–15 秒（随机）里你没接话 → 一句念给 Live 的话
//   silence   60 秒没有任何对话迹象；只在"说话 → 沉默"变化时触发一次，后续交给不应答升级
//   periodic  聊得顺时，每 2–4 分钟（随机）插一问
//
// 这里不碰 I/O、不读真实时钟：时间都由调用方传入，模型（brain）、随机数和存储由外部注入。

import { classify, COG, DEFAULT_BANDWIDTH } from './bandwidth.js'
import { synthesize } from './context.js'
import { DICE as DICE_ORDER, diceCard, pickDie } from './dice.js'
import { driverPrompt, EXPECTS, parseCard } from './prompt.js'
import { maxSimilarity } from './similar.js'
import { Telemetry } from './telemetry.js'
import { replyFeatures } from './text.js'

export const PHASE = Object.freeze({
  IDLE: 'IDLE',
  LOCKED: 'LOCKED',
  INTERRUPTED_BY_SWITCH: 'INTERRUPTED_BY_SWITCH',
  PAUSED: 'PAUSED',
  WRAPPING: 'WRAPPING',
  ENDED: 'ENDED',
})

export const DEFAULT_DRIVER = Object.freeze({
  ...DEFAULT_BANDWIDTH,
  minutes: 15,
  answerMs: 30_000, // 卡片亮出后这么久没回，算一次不应答
  repeatMs: 20_000, // level 2 时重复提醒的间隔
  pushMinMs: 120_000, // 和 Live 聊得顺时，驾驶员隔多久插一问：在这个区间里随机取
  pushMaxMs: 240_000,
  turnGapMinMs: 8_000, // Gemini 说完后等你接话的窗口：在这个区间里随机取
  turnGapMaxMs: 15_000,
  minSpeechMs: 1_500, // Gemini 至少说了这么久才算"一段话说完"
  wakeCooldownMs: 90_000, // turn_gap / periodic 同一原因的冷却
  wakeMinGapMs: 20_000, // 任意两次主动开口之间的最小间隔
  repeatThreshold: 0.6, // 和最近问过的话字面相似度达到这个值就换一问
  awakeMs: 15_000, // 卡亮出后这么久内有任何对话迹象，算"醒了"
  wrapMs: 180_000, // 时间到后等结论的时长
  brainTimeoutMs: 8_000, // 等模型出题的上限，超时就用模板
  maxPauseMinutes: 30,
})

// 这些意图的卡本来就该重复（拉回原断点、再要一次结论、沉默提醒），不参与去重
const REPEAT_OK = new Set(['pullback', 'silence', 'conclude', 'agenda'])
const DOUBT = /不确定|不太确定|好像|可能|也许|再看|没把握|大概/

const DIRECTIVE = Object.freeze({ SHOW: 'show_card', PULLBACK: 'pullback', ESCALATE: 'escalate', RELEASE: 'release', END: 'end' })
export { DIRECTIVE }

function withTimeout(p, ms) {
  let t
  return Promise.race([p, new Promise((resolve) => { t = setTimeout(() => resolve(null), ms) })]).finally(() => clearTimeout(t))
}

export class Driver {
  /**
   * @param {object} deps
   * @param {(prompt:string)=>Promise<string|null>} [deps.brain] 交给 DSH agent 出题；没有或失败就用模板
   * @param {{memories?:Array, profile?:object, history?:object}} [deps.hidden] 隐秘层数据
   * @param {(row:object)=>void} [deps.append] 推进日志落盘
   * @param {(history:object)=>void} [deps.saveHistory]
   * @param {()=>string} [deps.newId]
   * @param {()=>number} [deps.rng] 0–1 的随机数；测试里固定
   * @param {import('./agenda.js').Agenda} [deps.agenda] 跨会话议程
   * @param {(items:Array)=>void} [deps.saveAgenda]
   * @param {object} [deps.cfg]
   */
  constructor(deps = {}) {
    const cfg = { ...DEFAULT_DRIVER, ...(deps.cfg ?? {}) }
    if (deps.cfg?.pushEveryMs) { cfg.pushMinMs = deps.cfg.pushEveryMs; cfg.pushMaxMs = deps.cfg.pushEveryMs }
    this.cfg = cfg
    this.rng = deps.rng ?? Math.random
    this.agenda = deps.agenda
    this.saveAgenda = deps.saveAgenda ?? (() => {})
    this.brain = deps.brain
    this.memories = deps.hidden?.memories ?? []
    this.profile = deps.hidden?.profile ?? {}
    this.history = { dice: {}, escapeMinutes: {}, sessions: 0, latencies: [], ...(deps.hidden?.history ?? {}) }
    this.saveHistory = deps.saveHistory ?? (() => {})
    let n = 0
    this.newId = deps.newId ?? (() => `c${++n}`)
    this.telemetry = new Telemetry({ append: deps.append })
    this.outbox = []
    this.seq = 0
    this.s = { phase: PHASE.IDLE }
    this.queue = Promise.resolve()
  }

  // 所有入口串行执行：一次只处理一件事，状态不会被并发改乱。
  #serial(fn) {
    const run = this.queue.then(fn, fn)
    this.queue = run.catch(() => {})
    return run
  }

  #emit(type, at, extra = {}) {
    const d = { seq: ++this.seq, type, at, ...extra }
    this.outbox.push(d)
    return d
  }

  drain(after = 0) {
    return this.outbox.filter((d) => d.seq > after)
  }

  state() {
    const s = this.s
    return {
      phase: s.phase, level: s.level ?? 0, strikes: s.strikes ?? 0, topic: s.topic, task: s.task,
      startedAt: s.startedAt, endsAt: s.endsAt, pausedUntil: s.pausedUntil ?? null,
      card: s.card ?? null, breakpoint: s.breakpoint ?? null, cog: s.cog ?? null, agendaFocus: s.agendaFocus ?? null,
      conclusion: s.conclusion ?? null, endReason: s.endReason ?? null,
      summary: this.telemetry.summary(),
    }
  }

  // ───────────────────────────── 入口 ─────────────────────────────

  start({ at, topic = '', task = '', minutes, anchors = [] }) {
    return this.#serial(async () => {
      if (this.s.phase !== PHASE.IDLE) return []
      const mins = Math.min(Math.max(Number(minutes ?? this.cfg.minutes) || this.cfg.minutes, 1), 60)
      this.s = {
        phase: PHASE.LOCKED, topic, task, minutes: mins, startedAt: at, endsAt: at + mins * 60_000,
        anchors: new Set(anchors), anchorPkg: anchors[0] ?? null, inAnchor: true, liveActive: null,
        level: 0, strikes: 0, lastSignalAt: at, recent: [], diceUsed: [], silenceCount: 0,
        card: null, breakpoint: null, cog: { state: COG.ACTIVE_FLOW, reason: 'start' },
        nextPushAt: at + this.#between(this.cfg.pushMinMs, this.cfg.pushMaxMs),
        wakes: [], asked: [], cards: [], utterances: [], speakingSince: null, turnGap: null,
        silenceFiredFor: null, agendaOffer: null, agendaFocus: null,
      }
      this.telemetry.note(at, 'session_start', { topic, task, minutes: mins })
      // 主动找你：开场就出第一问，不等你开口。上次有没接上的同主题断点，先问要不要接着做
      const offer = this.agenda?.match({ topic, task })
      if (offer) {
        const card = this.#register(at, {
          kind: 'ab', expects: 'choice', intent: 'agenda', source: 'template', anchor: offer.anchor,
          text: `上次停在【${offer.anchor}】没接上${offer.missing ? `（缺「${offer.missing}」）` : ''}。`,
          options: { A: '从这接着做', B: '先做新的' },
        })
        this.s.agendaOffer = offer.id
        return [this.#emit(DIRECTIVE.SHOW, at, { level: 0, card })]
      }
      const card = await this.#compose(at, { state: COG.ACTIVE_FLOW, reason: 'start' }, 'open')
      return [this.#emit(DIRECTIVE.SHOW, at, { level: 0, card })]
    })
  }

  /** 前台应用变化。allowed 由手机端按同一份名单算好；没给就按 anchors 算。 */
  foreground({ at, pkg, allowed }) {
    return this.#serial(async () => {
      const s = this.s
      if (![PHASE.LOCKED, PHASE.INTERRUPTED_BY_SWITCH, PHASE.WRAPPING].includes(s.phase)) return []
      const ok = allowed ?? s.anchors.has(pkg)
      s.inAnchor = ok
      if (ok && s.anchors.has(pkg)) s.anchorPkg = pkg
      if (ok || s.phase !== PHASE.LOCKED) return [] // 回到允许的应用也要等一条有效微反馈才算接回
      return this.#interrupt(at, 'left_anchor_app', pkg)
    })
  }

  /** Live 会话开/关（手机端用麦克风占用或常驻通知推断）。 */
  live({ at, active }) {
    return this.#serial(async () => {
      const s = this.s
      if (s.phase === PHASE.IDLE || s.phase === PHASE.ENDED) return []
      s.liveActive = active
      if (active === false && s.phase === PHASE.LOCKED) return this.#interrupt(at, 'live_ended')
      return []
    })
  }

  /**
   * Live 那边开始/停止播声音。开始 ≈ Gemini 在回你（你刚说过话）；
   * 停止且说了够久 ≈ 一段话说完、轮到你了，开一个随机长度的"等你接话"窗口。
   */
  audio({ at, assistantSpeaking }) {
    return this.#serial(async () => {
      const s = this.s
      if (![PHASE.LOCKED, PHASE.WRAPPING].includes(s.phase)) return []
      if (!assistantSpeaking) {
        if (s.speakingSince == null) return []
        const spoke = at - s.speakingSince
        s.speakingSince = null
        s.lastSignalAt = at
        if (spoke >= this.cfg.minSpeechMs) {
          s.turnGap = { endedAt: at, due: at + this.#between(this.cfg.turnGapMinMs, this.cfg.turnGapMaxMs) }
        }
        return []
      }
      s.speakingSince ??= at
      s.turnGap = null // Gemini 又开口了：说明你接上了，窗口作废
      s.lastSignalAt = at
      this.#markAwake(at)
      // 你在和 Live 对话，就是在思考：之前因为不应答攒下的升级清零
      s.strikes = 0
      s.level = 0
      // "念给 Gemini"的卡：卡亮出之后 Gemini 开口了，推断你已经念了
      const c = s.card
      if (c && c.kind === 'say_to_live' && !c.answered && s.shownAt != null && at > s.shownAt) {
        c.answered = true
        this.telemetry.replied(c.id, at, { text: '(推断：已对 Live 开口)', latencyMs: at - s.shownAt, features: { score: 0.5 } })
        this.telemetry.advanced(c.id, at, 'spoke_to_live')
      }
      return []
    })
  }

  /** 手机端回报卡片的真实可见性：displayed / not_shown / dismissed。 */
  surface({ at, cardId, status, via = 'page' }) {
    return this.#serial(async () => {
      const s = this.s
      if (status === 'displayed') {
        if (this.telemetry.displayed(cardId, at, via) && s.card?.id === cardId) {
          s.shownAt = at
          this.#noteAsked(s.card)
        }
      } else {
        this.telemetry.note(at, `surface_${status}`, { card: cardId, via })
      }
      return []
    })
  }

  reply({ at, cardId, text, choice, channel = 'tap' }) {
    return this.#serial(async () => {
      const s = this.s
      const c = s.card
      if (!c || c.id !== cardId || c.answered || ![PHASE.LOCKED, PHASE.INTERRUPTED_BY_SWITCH, PHASE.WRAPPING].includes(s.phase)) {
        this.telemetry.note(at, 'stale_reply', { card: cardId })
        return []
      }
      const said = choice ? String(choice).toUpperCase() : String(text ?? '')
      const features = replyFeatures(said, { anchors: c.anchorWords ?? [] })
      const latencyMs = at - (s.shownAt ?? c.generatedAt)
      const reply = { text: said, latencyMs, features, expects: c.expects, channel }
      this.#markAwake(at)
      c.answered = true
      this.#noteAsked(c)
      this.telemetry.replied(c.id, at, reply)
      s.lastSignalAt = at
      s.turnGap = null
      s.recent = [...s.recent, reply].slice(-3)
      if (!choice && said.trim()) s.utterances.push(said.trim())
      this.#noteLatency(latencyMs)

      const advanced = this.#advanced(c, features)
      if (advanced) this.telemetry.advanced(c.id, at, advanced)
      this.#noteDie(c, !!advanced)
      const valid = !!(features.choice || features.score >= 0.2 || advanced)
      // 议程里的题，这次真的给出了依据 → 结掉
      if (s.agendaFocus && Telemetry.isGrounded(advanced)) {
        this.agenda?.done(s.agendaFocus, at)
        this.telemetry.note(at, 'agenda_done', { agenda: s.agendaFocus })
        s.agendaFocus = null
      }

      if (c.intent === 'agenda' && s.agendaOffer) {
        const id = s.agendaOffer
        s.agendaOffer = null
        const item = this.agenda?.items.find((x) => x.id === id)
        s.nextPushAt = at + this.#between(this.cfg.pushMinMs, this.cfg.pushMaxMs)
        if (features.choice === 'A' && item) {
          s.agendaFocus = id
          this.telemetry.note(at, 'agenda_taken', { agenda: id })
          s.breakpoint = { anchor: item.anchor, text: item.text, missing: item.missing }
          const next = await this.#compose(at, { state: COG.ACTIVE_FLOW, reason: 'agenda' }, 'resume')
          return [this.#emit(DIRECTIVE.SHOW, at, { level: 0, card: next })]
        }
        this.agenda?.skip(id, at)
        this.telemetry.note(at, 'agenda_skipped', { agenda: id })
        const next = await this.#compose(at, { state: COG.ACTIVE_FLOW, reason: 'start' }, 'open')
        return [this.#emit(DIRECTIVE.SHOW, at, { level: 0, card: next })]
      }

      if (s.phase === PHASE.WRAPPING) {
        if (c.intent === 'conclude' && advanced) {
          s.conclusion = said
          return [this.#end(at, 'completed')]
        }
        const again = await this.#compose(at, { state: COG.THROTTLED_EMPTY, reason: 'need_conclusion' }, 'conclude')
        return [this.#emit(DIRECTIVE.SHOW, at, { level: 1, card: again })]
      }

      const cog = classify({ now: at, inAnchor: s.inAnchor, liveActive: s.liveActive, lastSignalAt: s.lastSignalAt, lastReply: reply, recent: s.recent }, this.cfg)
      s.cog = cog

      if (s.phase === PHASE.INTERRUPTED_BY_SWITCH) {
        if (!valid) {
          // 敷衍不算接回：断点卡原样留着，计时继续
          const again = this.#pullbackCard(at)
          return [this.#emit(DIRECTIVE.PULLBACK, at, { level: s.level, card: again, relaunch: s.anchorPkg, reason: 'not_valid' })]
        }
        if (c.resumes) this.telemetry.resumed(c.resumes, at)
        s.phase = PHASE.LOCKED
        s.strikes = 0
        s.level = 0
        this.telemetry.note(at, 'resumed', { breakpoint: c.resumes })
        // 接回之后，回到断点本身继续追问
        const next = await this.#compose(at, { state: COG.ACTIVE_FLOW, reason: 'resumed' }, 'resume')
        return [this.#emit(DIRECTIVE.SHOW, at, { level: 0, card: next })]
      }

      if (valid) { s.strikes = 0; s.level = 0 }
      const intent = cog.state === COG.HIGH_SPEED_SKIMMING ? 'brake' : cog.state === COG.THROTTLED_EMPTY ? 'dice' : 'probe'
      const next = await this.#compose(at, cog, intent)
      s.nextPushAt = at + this.#between(this.cfg.pushMinMs, this.cfg.pushMaxMs)
      return [this.#emit(DIRECTIVE.SHOW, at, { level: s.level, card: next })]
    })
  }

  /** 声明休息：必须带恢复时间。这是除"有效微反馈"外唯一能解除强提醒的方式。 */
  pause({ at, minutes, reason = '' }) {
    return this.#serial(async () => {
      const s = this.s
      if (![PHASE.LOCKED, PHASE.INTERRUPTED_BY_SWITCH].includes(s.phase)) return []
      const m = Math.min(Math.max(Math.round(Number(minutes) || 0), 1), this.cfg.maxPauseMinutes)
      s.breakpoint ??= s.card
      s.phase = PHASE.PAUSED
      s.pausedUntil = at + m * 60_000
      s.endsAt += m * 60_000 // 休息不吃掉思考时间
      s.level = 0
      s.strikes = 0
      this.telemetry.note(at, 'paused', { minutes: m, reason })
      return [this.#emit(DIRECTIVE.RELEASE, at, { until: s.pausedUntil, reason: 'paused' })]
    })
  }

  /** 紧急结束：永远立刻放行（来电、急事），但记为主动结束而不是完成。 */
  stop({ at, reason = 'user' }) {
    return this.#serial(async () => {
      if (this.s.phase === PHASE.IDLE || this.s.phase === PHASE.ENDED) return []
      return [this.#end(at, `stopped:${reason}`)]
    })
  }

  /** 每秒调一次。负责：到时收尾、休息到点、不应答升级、沉默检测、定时插问。 */
  tick({ at }) {
    return this.#serial(async () => {
      const s = this.s
      const out = []
      if (s.phase === PHASE.IDLE || s.phase === PHASE.ENDED) return out

      if (s.phase === PHASE.PAUSED) {
        if (at < s.pausedUntil) return out
        s.pausedUntil = null
        this.telemetry.note(at, 'pause_over')
        return this.#interrupt(at, 'pause_over')
      }

      if (s.phase === PHASE.WRAPPING) {
        if (at >= s.wrapEndsAt) out.push(this.#end(at, 'no_conclusion'))
        else out.push(...this.#checkUnanswered(at))
        return out
      }

      if (at >= s.endsAt) {
        s.phase = PHASE.WRAPPING
        s.wrapEndsAt = at + this.cfg.wrapMs
        s.level = 0
        s.strikes = 0
        const card = await this.#compose(at, { state: COG.ACTIVE_FLOW, reason: 'time_up' }, 'conclude')
        out.push(this.#emit(DIRECTIVE.SHOW, at, { level: 1, card }))
        return out
      }

      // ① Gemini 说完了、你一直没接：在这一刻推一下，开口成本最低。
      //    这期间亮着的驾驶员卡是这段对话之前出的，已经过时：用这句替换它
      if (s.phase === PHASE.LOCKED && s.turnGap && at >= s.turnGap.due) {
        const gap = s.turnGap
        s.turnGap = null
        const stale = s.card && !s.card.answered
        const replaceable = !stale || (s.card.generatedAt <= gap.endedAt && s.card.kind !== 'say_to_live')
        if (s.lastSignalAt <= gap.endedAt && replaceable && this.#wake('turn_gap', at)) {
          if (stale) {
            s.card.answered = true
            this.telemetry.note(at, 'superseded_by_turn_gap', { card: s.card.id })
          }
          const card = this.#sayToLiveCard(at, 'turn_gap')
          out.push(this.#emit(DIRECTIVE.SHOW, at, { level: 0, card, reason: 'turn_gap' }))
          return out
        }
      }

      const pending = s.card && !s.card.answered
      if (pending) return this.#checkUnanswered(at)
      if (s.phase !== PHASE.LOCKED) return out
      // ② 长时间没有任何对话迹象：只在"说话 → 沉默"这次变化里触发一次
      if (at - s.lastSignalAt >= this.cfg.silenceMs && s.silenceFiredFor !== s.lastSignalAt) {
        if (this.#wake('silence', at)) {
          s.silenceFiredFor = s.lastSignalAt
          s.cog = { state: COG.THROTTLED_EMPTY, reason: 'silence' }
          s.silenceCount++
          // 沉默第一次：给一句念给 Live 的话；再沉默：A/B 原子动作
          const cheap = s.liveActive !== false && s.silenceCount % 2 === 1
          const card = cheap ? this.#sayToLiveCard(at) : await this.#compose(at, s.cog, 'dice')
          out.push(this.#emit(DIRECTIVE.SHOW, at, { level: 1, card, reason: 'silence' }))
          return out
        }
      }
      // ③ 聊得顺：随机间隔插一问
      if (at >= s.nextPushAt) {
        s.nextPushAt = at + this.#between(this.cfg.pushMinMs, this.cfg.pushMaxMs)
        if (this.#wake('periodic', at)) {
          const card = await this.#compose(at, { state: COG.ACTIVE_FLOW, reason: 'periodic' }, 'probe')
          out.push(this.#emit(DIRECTIVE.SHOW, at, { level: 0, card, reason: 'periodic' }))
        }
      }
      return out
    })
  }

  // ───────────────────────────── 内部 ─────────────────────────────

  #checkUnanswered(at) {
    const s = this.s
    const c = s.card
    if (!c || c.answered) return []
    const since = s.shownAt ?? c.generatedAt
    // 卡亮着、但你一直在和 Live 说话：这是好事，不算不应答；卡片悄悄过期
    if (s.phase === PHASE.LOCKED && s.lastSignalAt > since && c.kind !== 'say_to_live') {
      if (at - since >= this.cfg.answerMs * 2) {
        c.answered = true
        this.telemetry.note(at, 'superseded_by_live', { card: c.id })
      }
      return []
    }
    const due = s.lastStrikeAt == null || s.lastStrikeCard !== c.id
      ? since + this.cfg.answerMs
      : s.lastStrikeAt + (s.level >= 2 ? this.cfg.repeatMs : this.cfg.answerMs)
    if (at < due) return []
    s.strikes++
    s.level = s.strikes >= 2 ? 2 : 1
    s.lastStrikeAt = at
    s.lastStrikeCard = c.id
    this.telemetry.note(at, 'unanswered', { card: c.id, strikes: s.strikes, level: s.level })
    const type = s.level >= 2 ? DIRECTIVE.ESCALATE : s.phase === PHASE.INTERRUPTED_BY_SWITCH ? DIRECTIVE.PULLBACK : DIRECTIVE.SHOW
    return [this.#emit(type, at, { level: s.level, card: c, relaunch: s.anchorPkg, reason: 'unanswered' })]
  }

  #interrupt(at, reason, pkg) {
    const s = this.s
    s.phase = PHASE.INTERRUPTED_BY_SWITCH
    s.inAnchor = reason === 'left_anchor_app' ? false : s.inAnchor
    s.strikes++
    s.level = s.strikes >= 2 ? 2 : 1
    // 冻结断点：当前没答完的卡；没有就用最近一张
    const open = s.card && !s.card.answered && !s.card.resumes ? s.card : null
    if (open || !s.breakpoint) s.breakpoint = open ?? s.card ?? null
    if (s.breakpoint) this.telemetry.interrupted(s.breakpoint.id, at, reason)
    this.telemetry.note(at, 'interrupted', { reason, pkg })
    if (reason !== 'pause_over') {
      const minute = Math.floor((at - s.startedAt) / 60_000) + 1
      this.history.escapeMinutes[minute] = (this.history.escapeMinutes[minute] ?? 0) + 1
    }
    const card = this.#pullbackCard(at)
    s.lastStrikeAt = at
    s.lastStrikeCard = card.id
    const type = s.level >= 2 ? DIRECTIVE.ESCALATE : DIRECTIVE.PULLBACK
    return [this.#emit(type, at, { level: s.level, card, relaunch: s.anchorPkg, reason })]
  }

  #pullbackCard(at) {
    const s = this.s
    const bp = s.breakpoint
    const where = bp?.anchor ?? s.task ?? '刚才那一步'
    // 拉回卡永远是 A/B：切回来那一刻成本要最低。断点本身是 A/B 就沿用，否则借一个带选项的骰子
    let options = bp?.kind === 'ab' && bp.options ? bp.options : null
    if (!options) {
      const die = pickDie([...s.diceUsed, '精简'], this.history.dice) // 精简是"念两个词"，没有选项
      options = diceCard(die === '精简' ? '回想' : die, null).options
    }
    const kind = 'ab'
    const tail = '只回我 A 还是 B 就行。'
    const card = {
      kind, expects: EXPECTS[kind], options,
      text: `刚才停在【${where}】，不用重头想，${tail}${bp?.kind === 'ab' ? `（${bp.text}）` : ''}`,
      anchor: where, intent: 'pullback', source: 'template', resumes: bp?.id,
    }
    return this.#register(at, card)
  }

  #sayToLiveCard(at, intent = 'silence') {
    const s = this.s
    const where = s.card?.anchor ?? s.task ?? '这道题'
    const text = intent === 'turn_gap'
      ? `轮到你了。念给 Live：「先别讲，就${where}问我一个二选一。」`
      : `念给 Live：「我停在${where}了，你问我一个一两个字就能答的问题。」`
    return this.#register(at, { kind: 'say_to_live', expects: 'spoken', intent, source: 'template', anchor: where, text })
  }

  /** 唤醒账本：同一原因冷却，任意两次之间至少隔 wakeMinGapMs；拒绝的也记一笔。 */
  #wake(reason, at) {
    const s = this.s
    const last = s.wakes.at(-1)
    const lastSame = s.wakes.findLast((w) => w.reason === reason)
    const cooldown = reason === 'silence' ? 0 : this.cfg.wakeCooldownMs
    let why = null
    if (last && at - last.at < this.cfg.wakeMinGapMs) why = 'min_gap'
    else if (lastSame && at - lastSame.at < cooldown) why = 'cooldown'
    if (why) {
      this.telemetry.note(at, 'wake_skipped', { reason, why })
      return false
    }
    s.wakes = [...s.wakes, { reason, at }].slice(-20)
    this.telemetry.note(at, 'wake', { reason })
    return true
  }

  #between(min, max) {
    const r = Math.min(Math.max(Number(this.rng()) || 0, 0), 1)
    return Math.round(min + (max - min) * r)
  }

  #markAwake(at) {
    const s = this.s
    const c = s.card
    if (c && s.shownAt != null && at >= s.shownAt) this.telemetry.awake(c.id, at, at - s.shownAt <= this.cfg.awakeMs)
  }

  /** 记下"问过"的话：只有真正显示过或被回答过的才算（没亮出来的不算问过）。 */
  #noteAsked(card) {
    const s = this.s
    if (!card?.text || s.asked.at(-1)?.id === card.id) return
    s.asked = [...s.asked.filter((x) => x.id !== card.id), { id: card.id, text: card.text }].slice(-5)
  }

  /** 和最近问过的话太像就换：先换模板，再换骰子；都换不开就保留并记一笔。 */
  #dedupe(at, card, cog, intent, die, lastCard) {
    const s = this.s
    if (REPEAT_OK.has(intent) || !s.asked.length) return { card, die }
    const asked = s.asked.map((x) => x.text)
    const th = this.cfg.repeatThreshold
    if (maxSimilarity(card.text, asked) < th) return { card, die }
    const alts = this.#alternatives(cog, intent, lastCard)
    for (const alt of alts) {
      if (maxSimilarity(alt.card.text, asked) < th) {
        this.telemetry.note(at, 'repeat_replaced', { intent, from: card.source })
        return alt
      }
    }
    this.telemetry.note(at, 'repeat_unavoidable', { intent })
    return { card, die }
  }

  #alternatives(cog, intent, lastCard) {
    const s = this.s
    const out = []
    if (intent === 'resume' && lastCard?.anchor) {
      // 接回时换说法也要钉在断点上
      const at = `【${lastCard.anchor}】`
      for (const text of [`回到${at}：刚才卡住的是哪一步？说一个词。`, `回到${at}：你现在更倾向哪个结论？一句话。`, `回到${at}：先把题干里最关键的一个词念出来。`]) {
        out.push({ card: { kind: 'one_word', expects: 'evidence', source: 'template', text, missing: lastCard.missing }, die: undefined })
      }
    }
    if (cog.state !== COG.THROTTLED_EMPTY) {
      for (const text of PUSH) out.push({ card: { kind: 'one_word', expects: 'evidence', source: 'template', text }, die: undefined })
    }
    const used = new Set(s.diceUsed)
    for (const d of DICE_ORDER) {
      if (used.has(d) && d !== s.diceUsed.at(-1)) continue
      out.push({ card: { ...diceCard(d, lastCard?.anchor), source: 'template' }, die: d })
    }
    for (const d of DICE_ORDER) if (used.has(d)) out.push({ card: { ...diceCard(d, lastCard?.anchor), source: 'template' }, die: d })
    return out
  }

  async #compose(at, cog, intent) {
    const s = this.s
    let die = cog.state === COG.THROTTLED_EMPTY && intent !== 'conclude' ? pickDie(s.diceUsed, this.history.dice) : undefined
    const lastCard = intent === 'resume' ? s.breakpoint : s.card
    let card = null
    if (this.brain) {
      const ctx = synthesize({
        session: { topic: s.topic, task: s.task, minute: Math.floor((at - s.startedAt) / 60_000) + 1, minutes: s.minutes },
        lastCard, recentReplies: s.recent, cog, die,
        memories: this.memories, profile: this.profile, history: this.historyView(),
        asked: s.asked.map((x) => x.text),
        unresolved: (this.agenda?.open() ?? []).filter((x) => x.id !== s.agendaFocus).slice(0, 3).map((x) => x.text),
      })
      try {
        const raw = await withTimeout(Promise.resolve(this.brain(driverPrompt(ctx, { intent }))), this.cfg.brainTimeoutMs)
        card = parseCard(raw, { state: cog.state })
        if (card) card.source = 'llm'
        else this.telemetry.note(at, 'brain_fallback', { intent, why: raw == null ? 'timeout_or_unavailable' : 'unparseable' })
      } catch (e) {
        this.telemetry.note(at, 'brain_fallback', { intent, why: String(e?.message ?? e).slice(0, 80) })
      }
    }
    card ??= this.#template(cog, intent, die, lastCard)
    ;({ card, die } = this.#dedupe(at, card, cog, intent, die, lastCard))
    if (die && card.kind !== 'say_to_live') { s.diceUsed.push(die); card.intent = `dice:${die}` }
    else card.intent ??= intent
    if (intent === 'conclude') card.intent = 'conclude'
    card.anchor ??= lastCard?.anchor ?? (s.task || s.topic || undefined)
    if (intent === 'resume') s.breakpoint = null
    return this.#register(at, card)
  }

  #template(cog, intent, die, lastCard) {
    const s = this.s
    const habit = this.profile?.habits?.[0]
    if (intent === 'conclude') {
      return { kind: 'conclusion', expects: 'conclusion', source: 'template', text: '时间到。一句话：这一段你确定了什么？（例：第3题排除C，因为题干说了X）' }
    }
    if (intent === 'open') {
      return {
        kind: 'open', expects: 'evidence', source: 'template',
        text: s.task ? `这一段只钉一件事：${s.task}。你现在卡在哪一步？` : '开始。先说一句：你现在要解决的是哪一题、哪个问题？',
      }
    }
    if (die) return { ...diceCard(die, lastCard?.anchor), source: 'template' }
    if (cog.state === COG.HIGH_SPEED_SKIMMING || intent === 'brake') {
      return {
        kind: 'one_word', expects: 'evidence', source: 'template',
        text: `刚才滑过去了${habit ? `——你又在「${habit}」` : ''}。依据是哪个词？只说一个。`,
      }
    }
    if (intent === 'resume' && lastCard) {
      return {
        kind: 'one_word', expects: 'evidence', source: 'template', missing: lastCard.missing,
        text: `接上了。回到【${lastCard.anchor ?? '断点'}】：${lastCard.missing ? `「${lastCard.missing}」的依据在哪？` : '它的依据是哪个词？'}`,
      }
    }
    // 有依据之后往前推一格，而不是再要一遍依据
    const last = s.recent?.at(-1)?.features
    const i = last?.evidence || last?.anchorHit ? 1 + ((s.pushCount = (s.pushCount ?? 0) + 1) % (PUSH.length - 1)) : 0
    return { kind: 'one_word', expects: 'evidence', source: 'template', text: PUSH[i] }
  }

  #register(at, card) {
    const s = this.s
    card.id = this.newId()
    s.cards.push(card)
    card.generatedAt = at
    card.level = s.level ?? 0
    if (!card.anchorWords) card.anchorWords = card.missing ? [card.missing] : []
    this.telemetry.generated(card, at)
    s.card = card
    s.shownAt = null
    return card
  }

  #advanced(card, f) {
    switch (card.expects) {
      case 'choice': return f.choice ? 'atomic_choice' : (f.evidence || f.anchorHit) ? 'basis_given' : null
      case 'words': return !f.filler && !f.giveUp && f.chars >= 2 ? 'words_given' : null
      case 'conclusion':
        if (f.filler || f.giveUp || f.score < 0.3) return null
        return f.evidence || f.anchorHit ? 'conclusion_grounded' : 'conclusion_given'
      default: return f.evidence || f.anchorHit ? 'basis_given' : f.score >= 0.6 ? 'substantive' : null
    }
  }

  #noteDie(card, advanced) {
    const m = /^dice:(.+)$/.exec(card.intent ?? '')
    if (!m) return
    const h = (this.history.dice[m[1]] ??= { tries: 0, advanced: 0 })
    h.tries++
    if (advanced) h.advanced++
  }

  #noteLatency(ms) {
    if (!(ms >= 0)) return
    this.history.latencies = [...(this.history.latencies ?? []), ms].slice(-50)
  }

  historyView() {
    const l = [...(this.history.latencies ?? [])].sort((a, b) => a - b)
    return { ...this.history, medianLatencyMs: l.length >= 5 ? l[Math.floor(l.length / 2)] : undefined }
  }

  #end(at, reason) {
    const s = this.s
    s.phase = PHASE.ENDED
    s.endReason = reason
    s.level = 0
    this.history.sessions = (this.history.sessions ?? 0) + 1
    this.#harvestAgenda(at)
    const summary = this.telemetry.summary()
    this.telemetry.note(at, 'session_end', { reason, conclusion: s.conclusion ?? null, summary })
    try { this.saveHistory(this.history) } catch { /* 不影响结束 */ }
    return this.#emit(DIRECTIVE.END, at, { reason, conclusion: s.conclusion ?? null, summary })
  }

  /** 会话结束时把没解决的东西记进议程：没接回的断点、没补上的依据、没把握的结论。 */
  #harvestAgenda(at) {
    const s = this.s
    const a = this.agenda
    if (!a) return
    let added = 0
    for (const c of s.cards) {
      if (REPEAT_OK.has(c.intent) || !c.anchor) continue
      const it = this.telemetry.items.get(c.id)
      if (!it) continue
      if (it.interrupted && !it.resumedAt && a.add({ source: 'unresumed', anchor: c.anchor, missing: c.missing, text: `${c.anchor}：上次在这里被打断，没接上`, at })) added++
      else if (c.missing && !Telemetry.isGrounded(it.advancedWhy) && a.add({ source: 'missing', anchor: c.anchor, missing: c.missing, text: `${c.anchor}：缺「${c.missing}」的依据`, at })) added++
    }
    const doubt = s.conclusion && DOUBT.exec(s.conclusion)
    if (doubt && a.add({ source: 'conclusion_doubt', anchor: s.task || s.topic || '结论', quote: s.conclusion, text: `结论待确认：${s.conclusion}`.slice(0, 80), at }, { utterances: s.utterances })) added++
    if (s.agendaFocus) a.skip(s.agendaFocus, at) // 接了但没解决：计一次未完成，不算解决
    this.telemetry.note(at, 'agenda_saved', { added, open: a.open().length })
    try { this.saveAgenda(a.toJSON()) } catch { /* 不影响结束 */ }
  }
}

// 推进类模板：有依据之后往前推一格，而不是再要一遍依据
const PUSH = [
  '你刚才那一步，依据是什么？一个词就行。',
  '拿这个依据往下走：它能排除掉哪一个？',
  '下一步要确认的是什么？说一个词。',
  '这个依据够下结论了吗？不够的话还缺哪个词？',
  '换个方向：如果它是错的，会是哪里错？说一个词。',
  '把你的判断压成一句：因为什么，所以什么？',
]
