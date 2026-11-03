package com.dshdriver.core

import kotlin.random.Random

/**
 * 15 分钟锁定思考会话的状态机（APP 里真正跑的那份；插件 plugin/lib/core/driver.js 是参照实现，规则一致）。
 *
 *   IDLE ──start──▶ LOCKED ──切出允许的应用 / Live 结束──▶ INTERRUPTED_BY_SWITCH
 *                    │  ▲                                        │
 *                    │  └──────── 有效微反馈（RESUMED）◀──────────┘
 *                    ├──声明休息(带恢复时间)──▶ PAUSED ──到点──▶ INTERRUPTED_BY_SWITCH（拉回断点）
 *                    ├──到时间──▶ WRAPPING ──给出一句结论──▶ ENDED(completed)
 *                    │                     └──超时──────────▶ ENDED(no_conclusion)
 *                    └──紧急结束（永远放行）──────────────────▶ ENDED(stopped)
 *
 * 主动开口三个时机，都走同一个唤醒账本：
 *   turn_gap  Gemini 说完一段、8–15 秒（随机）你没接 → 一句念给 Live 的话
 *   silence   60 秒没有任何对话迹象，只在"说话 → 沉默"变化时触发一次
 *   periodic  聊得顺时每 2–4 分钟（随机）插一问
 *
 * **不是线程安全的**：所有调用必须在同一个线程上（APP 里是驾驶员专用线程）。
 * 不碰 I/O、不读真实时钟：时间由调用方传入；大脑、随机数、存储由外部注入。
 */
class Driver(
    private val brain: Brain? = null,
    private val profile: Profile = Profile(),
    val history: History = History(),
    val agenda: Agenda? = null,
    private val cfg: Config = Config(),
    private val rng: () -> Double = { Random.nextDouble() },
    newId: (() -> String)? = null,
    sink: (Map<String, Any?>) -> Unit = {},
    private val pickInteraction: (() -> String)? = null,
) {
    data class Config(
        val fastMs: Long = 3_000,
        val slowMs: Long = 45_000,
        val silenceMs: Long = 60_000,
        val minutes: Int = 15,
        val answerMs: Long = 30_000,
        val repeatMs: Long = 20_000,
        val pushMinMs: Long = 120_000,
        val pushMaxMs: Long = 240_000,
        val turnGapMinMs: Long = 8_000,
        val turnGapMaxMs: Long = 15_000,
        val minSpeechMs: Long = 1_500,
        val wakeCooldownMs: Long = 90_000,
        val wakeMinGapMs: Long = 20_000,
        val repeatThreshold: Double = 0.6,
        val awakeMs: Long = 15_000,
        val wrapMs: Long = 180_000,
        val maxPauseMinutes: Int = 30,
    ) {
        val bandwidth get() = Bandwidth.Config(fastMs, slowMs, silenceMs)
    }

    data class Snapshot(
        val phase: Phase, val level: Int, val strikes: Int, val topic: String, val task: String,
        val startedAt: Long, val endsAt: Long, val pausedUntil: Long?, val card: Card?, val breakpoint: Card?,
        val cog: CogState?, val agendaFocus: String?, val conclusion: String?, val endReason: String?,
        val summary: Telemetry.Summary,
    )

    private class Wake(val reason: String, val at: Long)
    private class TurnGap(val endedAt: Long, val due: Long)
    private class Asked(val id: String, val text: String)

    val telemetry = Telemetry(sink)
    private val outbox = mutableListOf<Directive>()
    private var seq = 0
    private val nextId: () -> String = newId ?: run { var n = 0; { "c${++n}" } }

    // ── 会话状态 ──
    private var phase = Phase.IDLE
    private var topic = ""
    private var task = ""
    private var minutes = cfg.minutes
    private var startedAt = 0L
    private var endsAt = 0L
    private var anchors = emptySet<String>()
    var anchorPkg: String? = null; private set
    private var inAnchor = true
    private var liveActive: Boolean? = null
    private var level = 0
    private var strikes = 0
    private var lastSignalAt = 0L
    private var recent = listOf<Reply>()
    private val diceUsed = mutableListOf<String>()
    private var silenceCount = 0
    private var card: Card? = null
    private var shownAt: Long? = null
    private var breakpoint: Card? = null
    private var cog: CogState? = null
    private var nextPushAt = 0L
    private val wakes = mutableListOf<Wake>()
    private var asked = listOf<Asked>()
    private val cards = mutableListOf<Card>()
    private val utterances = mutableListOf<String>()
    private var speakingSince: Long? = null
    private var turnGap: TurnGap? = null
    private var silenceFiredFor: Long? = null
    private var agendaOffer: String? = null
    private var agendaFocus: String? = null
    private var lastStrikeAt: Long? = null
    private var lastStrikeCard: String? = null
    private var pausedUntil: Long? = null
    private var wrapEndsAt = 0L
    private var conclusion: String? = null
    private var endReason: String? = null
    private var pushCount = 0
    private val recentInteractions = mutableListOf<String>()

    val phaseNow get() = phase
    val currentCard get() = card

    fun drain(after: Int = 0): List<Directive> = outbox.filter { it.seq > after }

    fun state() = Snapshot(
        phase, level, strikes, topic, task, startedAt, endsAt, pausedUntil, card, breakpoint, cog, agendaFocus,
        conclusion, endReason, telemetry.summary(),
    )

    private fun emit(type: String, at: Long, level: Int = 0, card: Card? = null, reason: String? = null, until: Long? = null): Directive {
        val d = Directive(++seq, type, at, level, card, if (card != null) anchorPkg else null, reason, until)
        outbox += d
        return d
    }

    // ───────────────────────────── 入口 ─────────────────────────────

    fun start(at: Long, topic: String = "", task: String = "", minutes: Int? = null, anchors: List<String> = emptyList()): List<Directive> {
        if (phase != Phase.IDLE) return emptyList()
        val mins = (minutes ?: cfg.minutes).coerceIn(1, 60)
        phase = Phase.LOCKED
        this.topic = topic
        this.task = task
        this.minutes = mins
        startedAt = at
        endsAt = at + mins * 60_000L
        this.anchors = anchors.toSet()
        anchorPkg = anchors.firstOrNull()
        lastSignalAt = at
        cog = CogState(Cog.ACTIVE_FLOW, "start")
        nextPushAt = at + between(cfg.pushMinMs, cfg.pushMaxMs)
        telemetry.note(at, "session_start", mapOf("topic" to topic, "task" to task, "minutes" to mins))
        // 主动找你：开场就出第一问。上次有没接上的同主题断点，先问要不要接着做
        val offer = agenda?.match(topic, task)
        if (offer != null) {
            val c = register(at, Draft(
                "ab", "上次停在【${offer.anchor}】没接上${offer.missing?.let { "（缺「$it」）" } ?: ""}。", "choice",
                Options("从这接着做", "先做新的"), intent = "agenda", anchor = offer.anchor,
            ))
            agendaOffer = offer.id
            return listOf(emit(Directive.SHOW, at, 0, c))
        }
        val c = compose(at, CogState(Cog.ACTIVE_FLOW, "start"), "open")
        return listOf(emit(Directive.SHOW, at, 0, c))
    }

    /** 前台应用变化。allowed 由手机端按同一份名单算好；null 就按 anchors 算。 */
    fun foreground(at: Long, pkg: String, allowed: Boolean? = null): List<Directive> {
        if (phase !in setOf(Phase.LOCKED, Phase.INTERRUPTED_BY_SWITCH, Phase.WRAPPING)) return emptyList()
        val ok = allowed ?: (pkg in anchors)
        inAnchor = ok
        if (ok && pkg in anchors) anchorPkg = pkg
        if (ok || phase != Phase.LOCKED) return emptyList() // 回到允许的应用也要等一条有效微反馈才算接回
        return interrupt(at, "left_anchor_app", pkg)
    }

    fun live(at: Long, active: Boolean?): List<Directive> {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return emptyList()
        liveActive = active
        if (active == false && phase == Phase.LOCKED) return interrupt(at, "live_ended")
        return emptyList()
    }

    /** Live 那边开始/停止播声音。停止且说了够久 ≈ 轮到你了，开一个随机长度的"等你接话"窗口。 */
    fun audio(at: Long, assistantSpeaking: Boolean): List<Directive> {
        if (phase != Phase.LOCKED && phase != Phase.WRAPPING) return emptyList()
        if (!assistantSpeaking) {
            val since = speakingSince ?: return emptyList()
            speakingSince = null
            lastSignalAt = at
            if (at - since >= cfg.minSpeechMs) turnGap = TurnGap(at, at + between(cfg.turnGapMinMs, cfg.turnGapMaxMs))
            return emptyList()
        }
        if (speakingSince == null) speakingSince = at
        turnGap = null // Gemini 又开口了：说明你接上了，窗口作废
        lastSignalAt = at
        markAwake(at)
        // 你在和 Live 对话，就是在思考：之前因为不应答攒下的升级清零
        strikes = 0
        level = 0
        // "念给 Gemini"的卡：卡亮出之后 Gemini 开口了，推断你已经念了
        val c = card
        val shown = shownAt
        if (c != null && c.kind == "say_to_live" && !c.answered && shown != null && at > shown) {
            c.answered = true
            telemetry.replied(c.id, at, "(推断：已对 Live 开口)", at - shown, 0.5)
            telemetry.advanced(c.id, at, "spoke_to_live")
        }
        return emptyList()
    }

    /** 手机端回报卡片的真实可见性：displayed / not_shown / dismissed。 */
    fun surface(at: Long, cardId: String, status: String, via: String = "page"): List<Directive> {
        if (status == "displayed") {
            if (telemetry.displayed(cardId, at, via) && card?.id == cardId) {
                shownAt = at
                noteAsked(card!!)
            }
        } else {
            telemetry.note(at, "surface_$status", mapOf("card" to cardId, "via" to via))
        }
        return emptyList()
    }

    fun reply(at: Long, cardId: String, text: String? = null, choice: String? = null, channel: String = "tap"): List<Directive> {
        val c = card
        if (c == null || c.id != cardId || c.answered || phase !in setOf(Phase.LOCKED, Phase.INTERRUPTED_BY_SWITCH, Phase.WRAPPING)) {
            telemetry.note(at, "stale_reply", mapOf("card" to cardId))
            return emptyList()
        }
        val said = choice?.uppercase() ?: (text ?: "")
        val f = Text.features(said, c.anchorWords)
        val latency = at - (shownAt ?: c.generatedAt)
        val r = Reply(said, latency, f, c.expects, channel)
        markAwake(at)
        c.answered = true
        noteAsked(c)
        telemetry.replied(c.id, at, said, latency, f.score)
        lastSignalAt = at
        turnGap = null
        recent = (recent + r).takeLast(3)
        if (choice == null && said.isNotBlank()) utterances += said.trim()
        if (latency >= 0) history.latencies.apply { add(latency); while (size > 50) removeAt(0) }

        val advanced = advancedWhy(c, f)
        if (advanced != null) telemetry.advanced(c.id, at, advanced)
        noteDie(c, advanced != null)
        val valid = f.choice != null || f.score >= 0.2 || advanced != null
        // 议程里的题，这次真的给出了依据 → 结掉
        agendaFocus?.let { focus ->
            if (Telemetry.isGrounded(advanced)) {
                agenda?.done(focus, at)
                telemetry.note(at, "agenda_done", mapOf("agenda" to focus))
                agendaFocus = null
            }
        }

        val offer = agendaOffer
        if (c.intent == "agenda" && offer != null) {
            agendaOffer = null
            val item = agenda?.items?.find { it.id == offer }
            nextPushAt = at + between(cfg.pushMinMs, cfg.pushMaxMs)
            if (f.choice == "A" && item != null) {
                agendaFocus = offer
                telemetry.note(at, "agenda_taken", mapOf("agenda" to offer))
                breakpoint = Card("agenda:${item.id}", "open", item.text, null, "evidence", "agenda", "template", item.anchor, item.missing, null, 0, at)
                val next = compose(at, CogState(Cog.ACTIVE_FLOW, "agenda"), "resume")
                return listOf(emit(Directive.SHOW, at, 0, next))
            }
            agenda?.skip(offer, at)
            telemetry.note(at, "agenda_skipped", mapOf("agenda" to offer))
            val next = compose(at, CogState(Cog.ACTIVE_FLOW, "start"), "open")
            return listOf(emit(Directive.SHOW, at, 0, next))
        }

        if (phase == Phase.WRAPPING) {
            if (c.intent == "conclude" && advanced != null) {
                conclusion = said
                return listOf(end(at, "completed"))
            }
            val again = compose(at, CogState(Cog.THROTTLED_EMPTY, "need_conclusion"), "conclude")
            return listOf(emit(Directive.SHOW, at, 1, again))
        }

        val now = Bandwidth.classify(at, inAnchor, liveActive, lastSignalAt, r, recent, cfg.bandwidth)
        cog = now

        if (phase == Phase.INTERRUPTED_BY_SWITCH) {
            if (!valid) {
                // 敷衍不算接回：断点卡原样留着，计时继续
                val again = pullbackCard(at)
                return listOf(emit(Directive.PULLBACK, at, level, again, "not_valid"))
            }
            c.resumes?.let { telemetry.resumed(it, at) }
            phase = Phase.LOCKED
            strikes = 0
            level = 0
            telemetry.note(at, "resumed", mapOf("breakpoint" to c.resumes))
            val next = compose(at, CogState(Cog.ACTIVE_FLOW, "resumed"), "resume")
            return listOf(emit(Directive.SHOW, at, 0, next))
        }

        if (valid) { strikes = 0; level = 0 }
        val intent = when (now.state) {
            Cog.HIGH_SPEED_SKIMMING -> "brake"
            Cog.THROTTLED_EMPTY -> "dice"
            else -> "probe"
        }
        val next = compose(at, now, intent)
        nextPushAt = at + between(cfg.pushMinMs, cfg.pushMaxMs)
        return listOf(emit(Directive.SHOW, at, level, next))
    }

    /** 声明休息：必须带恢复时间。这是除"有效微反馈"外唯一能解除强提醒的方式。 */
    fun pause(at: Long, minutes: Int, reason: String = ""): List<Directive> {
        if (phase != Phase.LOCKED && phase != Phase.INTERRUPTED_BY_SWITCH) return emptyList()
        val m = minutes.coerceIn(1, cfg.maxPauseMinutes)
        if (breakpoint == null) breakpoint = card
        phase = Phase.PAUSED
        pausedUntil = at + m * 60_000L
        endsAt += m * 60_000L // 休息不吃掉思考时间
        level = 0
        strikes = 0
        telemetry.note(at, "paused", mapOf("minutes" to m, "reason" to reason))
        return listOf(emit(Directive.RELEASE, at, until = pausedUntil, reason = "paused"))
    }

    /** 紧急结束：永远立刻放行（来电、急事），但记为主动结束而不是完成。 */
    fun stop(at: Long, reason: String = "user"): List<Directive> {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return emptyList()
        return listOf(end(at, "stopped:$reason"))
    }

    /** 每秒调一次：到时收尾、休息到点、说完窗口、不应答升级、沉默检测、随机插问。 */
    fun tick(at: Long): List<Directive> {
        if (phase == Phase.IDLE || phase == Phase.ENDED) return emptyList()

        if (phase == Phase.PAUSED) {
            val until = pausedUntil ?: return emptyList()
            if (at < until) return emptyList()
            pausedUntil = null
            telemetry.note(at, "pause_over")
            return interrupt(at, "pause_over")
        }

        if (phase == Phase.WRAPPING) {
            return if (at >= wrapEndsAt) listOf(end(at, "no_conclusion")) else checkUnanswered(at)
        }

        if (at >= endsAt) {
            phase = Phase.WRAPPING
            wrapEndsAt = at + cfg.wrapMs
            level = 0
            strikes = 0
            val c = compose(at, CogState(Cog.ACTIVE_FLOW, "time_up"), "conclude")
            return listOf(emit(Directive.SHOW, at, 1, c))
        }

        // ① Gemini 说完了、你一直没接：在这一刻推一下。这期间亮着的驾驶员卡是这段对话之前出的，已经过时：替换它
        val gap = turnGap
        if (phase == Phase.LOCKED && gap != null && at >= gap.due) {
            turnGap = null
            val stale = card?.takeIf { !it.answered }
            val replaceable = stale == null || (stale.generatedAt <= gap.endedAt && stale.kind != "say_to_live")
            if (lastSignalAt <= gap.endedAt && replaceable && wake("turn_gap", at)) {
                if (stale != null) {
                    stale.answered = true
                    telemetry.note(at, "superseded_by_turn_gap", mapOf("card" to stale.id))
                }
                val c = sayToLiveCard(at, "turn_gap")
                return listOf(emit(Directive.SHOW, at, 0, c, "turn_gap"))
            }
        }

        if (card?.answered == false) return checkUnanswered(at)
        if (phase != Phase.LOCKED) return emptyList()

        // ② 长时间没有任何对话迹象：只在"说话 → 沉默"这次变化里触发一次
        if (at - lastSignalAt >= cfg.silenceMs && silenceFiredFor != lastSignalAt && wake("silence", at)) {
            silenceFiredFor = lastSignalAt
            val silent = CogState(Cog.THROTTLED_EMPTY, "silence")
            cog = silent
            silenceCount++
            // 沉默第一次：给一句念给 Live 的话；再沉默：A/B 原子动作
            val cheap = liveActive != false && silenceCount % 2 == 1
            val c = if (cheap) sayToLiveCard(at) else compose(at, silent, "dice")
            return listOf(emit(Directive.SHOW, at, 1, c, "silence"))
        }
        // ③ 聊得顺：随机间隔插一问
        if (at >= nextPushAt) {
            nextPushAt = at + between(cfg.pushMinMs, cfg.pushMaxMs)
            if (wake("periodic", at)) {
                val c = compose(at, CogState(Cog.ACTIVE_FLOW, "periodic"), "probe")
                return listOf(emit(Directive.SHOW, at, 0, c, "periodic"))
            }
        }
        return emptyList()
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    private fun checkUnanswered(at: Long): List<Directive> {
        val c = card ?: return emptyList()
        if (c.answered) return emptyList()
        val since = shownAt ?: c.generatedAt
        // 卡亮着、但你一直在和 Live 说话：这是好事，不算不应答；卡片悄悄过期
        if (phase == Phase.LOCKED && lastSignalAt > since && c.kind != "say_to_live") {
            if (at - since >= cfg.answerMs * 2) {
                c.answered = true
                telemetry.note(at, "superseded_by_live", mapOf("card" to c.id))
            }
            return emptyList()
        }
        val due = if (lastStrikeAt == null || lastStrikeCard != c.id) since + cfg.answerMs
        else lastStrikeAt!! + (if (level >= 2) cfg.repeatMs else cfg.answerMs)
        if (at < due) return emptyList()
        strikes++
        level = if (strikes >= 2) 2 else 1
        lastStrikeAt = at
        lastStrikeCard = c.id
        telemetry.note(at, "unanswered", mapOf("card" to c.id, "strikes" to strikes, "level" to level))
        val type = when {
            level >= 2 -> Directive.ESCALATE
            phase == Phase.INTERRUPTED_BY_SWITCH -> Directive.PULLBACK
            else -> Directive.SHOW
        }
        return listOf(emit(type, at, level, c, "unanswered"))
    }

    private fun interrupt(at: Long, reason: String, pkg: String? = null): List<Directive> {
        phase = Phase.INTERRUPTED_BY_SWITCH
        if (reason == "left_anchor_app") inAnchor = false
        strikes++
        level = if (strikes >= 2) 2 else 1
        // 冻结断点：当前没答完的卡；没有就用最近一张
        val open = card?.takeIf { !it.answered && it.resumes == null }
        if (open != null || breakpoint == null) breakpoint = open ?: card
        breakpoint?.let { telemetry.interrupted(it.id, at, reason) }
        telemetry.note(at, "interrupted", mapOf("reason" to reason, "pkg" to pkg))
        if (reason != "pause_over") {
            val minute = ((at - startedAt) / 60_000L).toInt() + 1
            history.escapeMinutes[minute] = (history.escapeMinutes[minute] ?: 0) + 1
        }
        val c = pullbackCard(at)
        lastStrikeAt = at
        lastStrikeCard = c.id
        return listOf(emit(if (level >= 2) Directive.ESCALATE else Directive.PULLBACK, at, level, c, reason))
    }

    /** 拉回卡永远是 A/B：切回来那一刻成本要最低。断点本身是 A/B 就沿用，否则借一个带选项的骰子。 */
    private fun pullbackCard(at: Long): Card {
        val bp = breakpoint
        val where = bp?.anchor ?: task.ifBlank { "刚才那一步" }
        val options = bp?.options?.takeIf { bp.kind == "ab" } ?: run {
            val die = Dice.pick(diceUsed + "精简", history.dice) // 精简是"念两个词"，没有选项
            Dice.card(if (die == "精简") "回想" else die, null).options
        }
        val tail = if (bp?.kind == "ab") "（${bp.text}）" else ""
        return register(at, Draft(
            "ab", "刚才停在【$where】，不用重头想，只回我 A 还是 B 就行。$tail", "choice", options,
            intent = "pullback", anchor = where, resumes = bp?.id?.takeIf { !it.startsWith("agenda:") },
        ))
    }

    private fun sayToLiveCard(at: Long, intent: String = "silence"): Card {
        val where = card?.anchor ?: task.ifBlank { "这道题" }
        val text = if (intent == "turn_gap") "轮到你了。念给 Live：「先别讲，就${where}问我一个二选一。」"
        else "念给 Live：「我停在${where}了，你问我一个一两个字就能答的问题。」"
        return register(at, Draft("say_to_live", text, "spoken", intent = intent, anchor = where))
    }

    /** 唤醒账本：同一原因冷却，任意两次之间至少隔 wakeMinGapMs；拒绝的也记一笔。 */
    private fun wake(reason: String, at: Long): Boolean {
        val last = wakes.lastOrNull()
        val lastSame = wakes.lastOrNull { it.reason == reason }
        val cooldown = if (reason == "silence") 0L else cfg.wakeCooldownMs
        val why = when {
            last != null && at - last.at < cfg.wakeMinGapMs -> "min_gap"
            lastSame != null && at - lastSame.at < cooldown -> "cooldown"
            else -> null
        }
        if (why != null) {
            telemetry.note(at, "wake_skipped", mapOf("reason" to reason, "why" to why))
            return false
        }
        wakes += Wake(reason, at)
        while (wakes.size > 20) wakes.removeAt(0)
        telemetry.note(at, "wake", mapOf("reason" to reason))
        return true
    }

    private fun between(min: Long, max: Long): Long = min + Math.round((max - min) * rng().coerceIn(0.0, 1.0))

    private fun markAwake(at: Long) {
        val c = card ?: return
        val shown = shownAt ?: return
        if (at >= shown) telemetry.awake(c.id, at, at - shown <= cfg.awakeMs)
    }

    /** 记下"问过"的话：只有真正显示过或被回答过的才算。 */
    private fun noteAsked(c: Card) {
        if (asked.lastOrNull()?.id == c.id) return
        asked = (asked.filter { it.id != c.id } + Asked(c.id, c.text)).takeLast(5)
    }

    private fun compose(at: Long, cog: CogState, intent: String): Card {
        var die = if (cog.state == Cog.THROTTLED_EMPTY && intent != "conclude") Dice.pick(diceUsed, history.dice) else null
        val last = if (intent == "resume") breakpoint else card
        var draft: Draft? = null
        var kind = nextInteraction()
        val material = recent.lastOrNull { it.features.evidence || it.features.chars >= Gate.MIN_CHARS }?.text?.let { "你刚才说：「${Text.clip(it, 80)}」" }
            ?: agenda?.open()?.maxByOrNull { it.lastSeenAt }?.text?.let { "你挂着的断点：${Text.clip(it, 80)}" }
            ?: profile.goals.firstOrNull()?.let { "你写下的主线：${Text.clip(it, 80)}" }
            ?: task.ifBlank { topic }.takeIf { it.isNotBlank() }?.let { "你这次填写的任务：${Text.clip(it, 80)}" }
            ?: "你还没有留下具体学习记录。先选眼前正在学的一段内容。"
        val b = brain
        if (b != null) {
            val req = ComposeRequest(
                intent, cog, die, topic, task, ((at - startedAt) / 60_000L).toInt() + 1, minutes,
                last?.anchor, last?.text?.let { Text.clip(it, 80) }, last?.missing,
                recent.map { Text.clip(it.text, 40) }, asked.takeLast(3).map { Text.clip(it.text, 40) },
                profile.goals.take(3), profile.habits.take(4), history.patterns(),
                agenda?.open()?.filter { it.id != agendaFocus }?.take(3)?.map { it.text } ?: emptyList(),
                kind = kind,
            )
            val raw = runCatching { b.compose(req) }.getOrNull()
            draft = Drafts.validate(raw, cog.state)?.takeUnless { Interaction.formulaic(it.text) }
            if (draft == null) telemetry.note(at, "brain_fallback", mapOf("intent" to intent, "why" to if (raw == null) "timeout_or_unavailable" else "invalid"))
        }
        if (b != null && draft == null) kind = nextInteraction()
        var d = draft ?: if (intent !in setOf("conclude", "resume", "open") && cog.state == Cog.ACTIVE_FLOW)
            Interaction.card(kind, material, last?.anchor) else template(cog, intent, die, last)
        val interactionAlternatives = if (cog.state == Cog.ACTIVE_FLOW || cog.state == Cog.HIGH_SPEED_SKIMMING)
            Interaction.questions(kind).indices.map { Interaction.card(kind, material, last?.anchor, it) } else emptyList()
        dedupe(at, Interaction.withMaterial(d, material), cog, intent, die, last, interactionAlternatives, material).let { d = it.first; die = it.second }
        if (Interaction.formulaic(d.text) && intent != "conclude") {
            d = if (cog.state == Cog.THROTTLED_EMPTY || cog.state == Cog.ESCAPED) Dice.card("精简", last?.anchor)
                else Interaction.card(kind, material, last?.anchor)
        }
        d = Interaction.withMaterial(d, material)
        var finalIntent = when {
            intent == "conclude" -> "conclude"
            die != null && d.kind != "say_to_live" -> { diceUsed += die!!; "dice:$die" }
            else -> d.intent ?: intent
        }
        if (finalIntent.startsWith("dice:") && die == null) finalIntent = intent
        if (intent == "resume") breakpoint = null
        return register(at, d.copy(intent = finalIntent, anchor = d.anchor ?: last?.anchor ?: task.ifBlank { topic }.ifBlank { null }))
    }

    private fun nextInteraction(): String {
        val kind = pickInteraction?.invoke() ?: Interaction.pick(recentInteractions, rng)
        recentInteractions += kind
        while (recentInteractions.size > 3) recentInteractions.removeAt(0)
        return kind
    }

    private fun template(cog: CogState, intent: String, die: String?, last: Card?): Draft {
        val habit = profile.habits.firstOrNull()
        if (intent == "conclude") return Draft("conclusion", "时间到。一句话：这一段你确定了什么？（例：第3题排除C，因为题干说了X）", "conclusion")
        if (intent == "open") {
            return Draft("open", if (task.isNotBlank()) "这一段只钉一件事：$task。你现在卡在哪一步？" else "开始。先说一句：你现在要解决的是哪一题、哪个问题？", "evidence")
        }
        if (die != null) return Dice.card(die, last?.anchor)
        if (cog.state == Cog.HIGH_SPEED_SKIMMING || intent == "brake") {
            return Draft("one_word", "刚才滑过去了${habit?.let { "——你又在「$it」" } ?: ""}。依据是哪个词？只说一个。", "evidence")
        }
        if (intent == "resume" && last != null) {
            val tail = last.missing?.let { "「$it」的依据在哪？" } ?: "它的依据是哪个词？"
            return Draft("one_word", "接上了。回到【${last.anchor ?: "断点"}】：$tail", "evidence", missing = last.missing)
        }
        val lf = recent.lastOrNull()?.features
        val i = if (lf != null && (lf.evidence || lf.anchorHit)) 1 + (++pushCount % (PUSH.size - 1)) else 0
        return Draft("one_word", PUSH[i], "evidence")
    }

    /** 和最近问过的话太像就换：先换模板，再换骰子；都换不开就保留并记一笔。 */
    private fun dedupe(at: Long, d: Draft, cog: CogState, intent: String, die: String?, last: Card?, extra: List<Draft>, material: String): Pair<Draft, String?> {
        if (intent in REPEAT_OK || asked.isEmpty()) return d to die
        val old = asked.map { it.text }
        val oldQuestions = old.map { it.substringAfter('\n', it) }
        fun fresh(candidate: Draft): Boolean =
            !Interaction.formulaic(candidate.text) &&
            Similar.maxSimilarity(candidate.text, old) < cfg.repeatThreshold &&
            Similar.maxSimilarity(candidate.text.substringAfter('\n', candidate.text), oldQuestions) < cfg.repeatThreshold
        if (fresh(d)) return d to die
        for ((raw, altDie) in extra.map { it to die } + alternatives(cog, intent, last)) {
            val alt = Interaction.withMaterial(raw, material)
            if (fresh(alt)) {
                telemetry.note(at, "repeat_replaced", mapOf("intent" to intent, "from" to d.source))
                return alt to altDie
            }
        }
        telemetry.note(at, "repeat_unavoidable", mapOf("intent" to intent))
        return d to die
    }

    private fun alternatives(cog: CogState, intent: String, last: Card?): List<Pair<Draft, String?>> {
        val out = mutableListOf<Pair<Draft, String?>>()
        if (intent == "resume" && last?.anchor != null) {
            val a = "【${last.anchor}】"
            listOf("回到$a：刚才卡住的是哪一步？说一个词。", "回到$a：你现在更倾向哪个结论？一句话。", "回到$a：先把题干里最关键的一个词念出来。")
                .forEach { out += Draft("one_word", it, "evidence", missing = last.missing) to null }
        }
        if (cog.state != Cog.THROTTLED_EMPTY) PUSH.forEach { out += Draft("one_word", it, "evidence") to null }
        val used = diceUsed.toSet()
        for (d in Dice.ALL) if (d !in used || d == diceUsed.lastOrNull()) out += Dice.card(d, last?.anchor) to d
        for (d in Dice.ALL) if (d in used) out += Dice.card(d, last?.anchor) to d
        return out
    }

    private fun register(at: Long, d: Draft): Card {
        val c = Card(nextId(), d.kind, d.text, d.options, d.expects, d.intent ?: "probe", d.source, d.anchor, d.missing, d.resumes, level, at)
        cards += c
        telemetry.generated(c, at)
        card = c
        shownAt = null
        return c
    }

    private fun advancedWhy(c: Card, f: Features): String? = when (c.expects) {
        "choice" -> if (f.choice != null) "atomic_choice" else if (f.evidence || f.anchorHit) "basis_given" else null
        "words" -> if (!f.filler && !f.giveUp && f.chars >= 2) "words_given" else null
        "conclusion" -> if (f.filler || f.giveUp || f.score < 0.3) null else if (f.evidence || f.anchorHit) "conclusion_grounded" else "conclusion_given"
        else -> if (f.evidence || f.anchorHit) "basis_given" else if (f.score >= 0.6) "substantive" else null
    }

    private fun noteDie(c: Card, advanced: Boolean) {
        if (!c.intent.startsWith("dice:")) return
        val h = history.dice.getOrPut(c.intent.removePrefix("dice:")) { DieStat() }
        h.tries++
        if (advanced) h.advanced++
    }

    private fun end(at: Long, reason: String): Directive {
        phase = Phase.ENDED
        endReason = reason
        level = 0
        history.sessions++
        harvestAgenda(at)
        val summary = telemetry.summary()
        telemetry.note(at, "session_end", mapOf("reason" to reason, "conclusion" to conclusion, "grounded" to summary.grounded))
        val d = Directive(++seq, Directive.END, at, reason = reason, conclusion = conclusion, summary = summary)
        outbox += d
        return d
    }

    /** 会话结束时把没解决的东西记进议程：没接回的断点、没补上的依据、没把握的结论。 */
    private fun harvestAgenda(at: Long) {
        val a = agenda ?: return
        var added = 0
        for (c in cards) {
            if (c.intent in REPEAT_OK || c.anchor == null) continue
            val it = telemetry.items[c.id] ?: continue
            if (it.interrupted > 0 && it.resumedAt == null) {
                if (a.add("unresumed", "${c.anchor}：上次在这里被打断，没接上", at, c.anchor, missing = c.missing) != null) added++
            } else if (c.missing != null && !Telemetry.isGrounded(it.advancedWhy)) {
                if (a.add("missing", "${c.anchor}：缺「${c.missing}」的依据", at, c.anchor, missing = c.missing) != null) added++
            }
        }
        val concl = conclusion
        if (concl != null && DOUBT.containsMatchIn(concl)) {
            if (a.add("conclusion_doubt", "结论待确认：$concl".take(80), at, task.ifBlank { topic }.ifBlank { "结论" }, quote = concl, utterances = utterances) != null) added++
        }
        agendaFocus?.let { a.skip(it, at) } // 接了但没解决：计一次未完成
        telemetry.note(at, "agenda_saved", mapOf("added" to added, "open" to a.open().size))
    }

    companion object {
        /** 这些意图的卡本来就该重复（拉回原断点、再要一次结论、沉默提醒、议程开场），不参与去重 */
        private val REPEAT_OK = setOf("pullback", "silence", "turn_gap", "conclude", "agenda")
        private val DOUBT = Regex("不确定|不太确定|好像|可能|也许|再看|没把握|大概")

        /** 推进类模板：有依据之后往前推一格，而不是再要一遍依据 */
        val PUSH = listOf(
            "你刚才那一步，依据是什么？一个词就行。",
            "拿这个依据往下走：它能排除掉哪一个？",
            "下一步要确认的是什么？说一个词。",
            "这个依据够下结论了吗？不够的话还缺哪个词？",
            "换个方向：如果它是错的，会是哪里错？说一个词。",
            "把你的判断压成一句：因为什么，所以什么？",
        )
    }
}
