package huilu.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val MIN = 60_000L

/** 引擎要求平台去做的事。平台做完要把真实结果报回来（[Engine.interventionResult]）。 */
sealed class Effect {
    /** 执行一次干预。checkIn 不为空时，同时要把这次检查呈现给用户。 */
    data class Intervene(val intervention: Intervention, val checkIn: CheckIn?) : Effect()
    /** 这次检查已经结束，撤掉它的通知 / 页面。 */
    data class Dismiss(val checkInId: String) : Effect()
}

/**
 * 确定性的规则层：间隔、升级、降级都在这里，每个决定都附带人能读懂的理由。
 * 大模型只在规则处理不了的地方（理解自由文本）出场，见 [LlmJudge]。
 */
object Policy {
    fun firstCheck(a: Action, s: Settings): Long? {
        if (a.plannedMin < 6) return null
        val gap = (a.plannedMin * MIN * s.firstCheckRatio).toLong().coerceIn(2 * MIN, 20 * MIN)
        val at = a.startedAt + gap
        return if (at >= a.endAt - MIN) null else at
    }

    fun nextAfterOnTrack(now: Long, a: Action): Long? {
        val remaining = a.endAt - now
        if (remaining <= 6 * MIN) return null
        return now + (remaining / 2).coerceIn(3 * MIN, 20 * MIN)
    }

    fun nextSoon(now: Long, a: Action, s: Settings): Long? {
        val at = now + s.recheckAfterDriftMin * MIN
        return if (at >= a.endAt - 30_000) null else at
    }

    fun choices(t: Trigger): List<AnswerKind> = when (t) {
        Trigger.SCHEDULED, Trigger.USER -> listOf(AnswerKind.ON_TRACK, AnswerKind.RECOMMIT, AnswerKind.DONE, AnswerKind.SHRINK, AnswerKind.REST, AnswerKind.SWITCH, AnswerKind.MUTE)
        Trigger.DRIFT -> listOf(AnswerKind.RECOMMIT, AnswerKind.ON_TRACK, AnswerKind.REST, AnswerKind.SHRINK, AnswerKind.SWITCH, AnswerKind.MUTE)
        Trigger.END -> listOf(AnswerKind.DONE, AnswerKind.EXTEND, AnswerKind.ABANDON, AnswerKind.SWITCH)
        Trigger.REST_OVER -> listOf(AnswerKind.ON_TRACK, AnswerKind.REST, AnswerKind.SWITCH, AnswerKind.ABANDON)
    }

    data class LevelChoice(val level: Level, val reason: String)

    /** 根据当前局面选干预强度：先定想要的强度，再被用户上限和真实能力削到能做的强度。 */
    fun level(t: Trigger, dev: Deviation, sit: Situation, a: Action, s: Settings, available: Set<Level>): LevelChoice {
        val (desired, why) = when (t) {
            Trigger.DRIFT -> {
                val last = sit.lastDriftLevel
                if (last != null && sit.lastDriftAftermath == Aftermath.RETURNED) last to "上次「${last.label}」之后回到了目标，保持同样强度"
                else when (sit.driftCount) {
                    0, 1 -> Level.NOTIFY to "本轮第 1 次偏离，先轻提醒"
                    2 -> Level.INTERRUPT to "本轮第 2 次偏离，要求重新判断"
                    3 -> Level.HOME to "本轮第 3 次偏离，离开娱乐 App"
                    else -> Level.BLOCK to "本轮第 ${sit.driftCount} 次偏离，本轮屏蔽娱乐 App"
                }
            }
            Trigger.SCHEDULED -> if (dev.kind == DevKind.DRIFT) Level.INTERRUPT to "定时检查发现偏离" else Level.NOTIFY to "定时检查"
            Trigger.USER -> Level.INTERRUPT to "用户主动检查"
            Trigger.END, Trigger.REST_OVER -> Level.NOTIFY to t.label
        }
        val cap = if (a.strict) s.maxLevel else minOf(s.maxLevel, Level.INTERRUPT, compareBy { it.rank })
        val allowed = minOf(desired, cap, compareBy { it.rank })
        val chosen = Level.values().filter { it.rank <= allowed.rank && (it == Level.NOTIFY || it in available) }
            .maxByOrNull { it.rank } ?: Level.NOTIFY
        val notes = mutableListOf(why)
        if (allowed.rank < desired.rank) notes += if (a.strict) "上限是「${cap.label}」" else "本轮未允许强干预"
        if (chosen.rank < allowed.rank) notes += "「${allowed.label}」当前不可用，降为「${chosen.label}」"
        return LevelChoice(chosen, notes.joinToString("；"))
    }
}

class Engine(
    private val log: EventLog,
    private val clock: Clock,
    private val platform: Platform,
    private val settings: () -> Settings,
    private val newId: () -> String = { UUID.randomUUID().toString().substring(0, 8) },
) {
    @Volatile
    var situation: Situation = Situation.replay(log.readAll())
        private set

    val assessor = Assessor(platform, settings)
    private val out = mutableListOf<Effect>()

    private fun emit(e: Event) {
        log.append(e)
        situation = situation.apply(e)
    }

    private inline fun run(block: () -> Unit): List<Effect> {
        out.clear()
        block()
        return out.toList()
    }

    // ---------------------------------------------------------------- 用户发起

    @Synchronized
    fun start(text: String, why: String, minutes: Int, expect: Expectation, strict: Boolean): List<Effect> = run {
        val now = clock.now()
        if (situation.action != null) endAction(now, Outcome.REPLACED)
        val a = Action(newId(), text.trim(), why.trim(), minutes, now, now + minutes * MIN, expect, strict)
        emit(Event.ActionStarted(a))
        val first = Policy.firstCheck(a, settings())
        emit(Event.NextCheck(now, first, if (first == null) "时长较短，到点时一起确认" else "第一次检查：计划时长的一半处"))
    }

    @Synchronized
    fun checkNow(): List<Effect> = run {
        val s = situation
        if (s.action != null && s.pending == null) {
            if (s.mode == Mode.RESTING) emit(Event.Rest(clock.now(), null))
            open(Trigger.USER, clock.now(), null)
        }
    }

    @Synchronized
    fun stop(outcome: Outcome): List<Effect> = run {
        if (situation.action != null) endAction(clock.now(), outcome)
    }

    // ---------------------------------------------------------------- 回答

    @Synchronized
    fun answer(checkInId: String, kind: AnswerKind, text: String? = null, via: Via = Via.SCREEN,
               minutes: Int? = null, newText: String? = null): List<Effect> = run {
        val s = situation
        val p = s.pending
        val a = s.action
        if (p == null || p.id != checkInId || a == null) return@run
        val now = clock.now()
        val st = settings()
        emit(Event.CheckInAnswered(now, p.id, kind, text?.takeIf { it.isNotBlank() }, via))
        var end: Outcome? = null
        val decision: String = when (kind) {
            AnswerKind.ON_TRACK -> {
                val next = Policy.nextAfterOnTrack(now, a)
                emit(Event.NextCheck(now, next, "回答在做"))
                val mismatch = p.deviation.kind == DevKind.DRIFT
                (if (mismatch) "记下了：自报在做，但观察到「${p.deviation.topDistractor?.let(platform::label)}」。" else "好。") +
                    (if (next == null) "到点时再确认结果。" else "${hm(next)} 再看一眼。")
            }
            AnswerKind.RECOMMIT -> {
                val next = Policy.nextSoon(now, a, st)
                emit(Event.NextCheck(now, next, "承诺回到目标，短间隔复查"))
                val fg = platform.foreground(now)
                if (a.strict && fg.pkg != null && assessor.category(fg.pkg, a) == AppCat.DISTRACTOR && Level.HOME in platform.available()) {
                    intervene(Level.HOME, null, fg.pkg, "回答要回去，但「${platform.label(fg.pkg)}」仍在前台", now)
                }
                "好，回到「${a.text}」。" + (if (next == null) "到点时确认结果。" else "${hm(next)} 看看是不是真的回去了。")
            }
            AnswerKind.DONE -> { end = Outcome.DONE; "记下了：完成。接下来做什么？" }
            AnswerKind.ABANDON -> { end = Outcome.ABANDONED; "先停下。偏差已经记下，之后可以复盘。" }
            AnswerKind.SWITCH -> { end = Outcome.REPLACED; "这一项先结束。说一下接下来要做什么。" }
            AnswerKind.EXTEND -> {
                val m = minutes ?: st.extendMin
                val newEnd = maxOf(a.endAt, now) + m * MIN
                emit(Event.ActionRevised(now, a.id, a.text, newEnd, "延长 $m 分钟"))
                emit(Event.NextCheck(now, null, "延长后到点确认"))
                "延长 $m 分钟，${hm(newEnd)} 再确认。"
            }
            AnswerKind.SHRINK -> {
                val m = minutes ?: 5
                val t = newText?.trim()?.takeIf { it.isNotEmpty() } ?: "先只做 $m 分钟：${a.text}"
                val newEnd = now + m * MIN
                emit(Event.ActionRevised(now, a.id, t, newEnd, "降低难度"))
                val next = now + maxOf(2, m / 2) * MIN
                emit(Event.NextCheck(now, if (next >= newEnd - 30_000) null else next, "降低难度后短间隔复查"))
                "改成「$t」，${hm(newEnd)} 确认。"
            }
            AnswerKind.REST -> {
                val m = minutes ?: st.restMin
                emit(Event.ActionRevised(now, a.id, a.text, a.endAt + m * MIN, "休息 $m 分钟，结束时间顺延"))
                emit(Event.Rest(now, now + m * MIN))
                emit(Event.NextCheck(now, null, "休息中"))
                "休息 $m 分钟，${hm(now + m * MIN)} 叫你回来。"
            }
            AnswerKind.MUTE -> {
                emit(Event.Mute(now, a.id))
                emit(Event.NextCheck(now, null, "本轮不再主动检查"))
                "这一轮不再主动打扰，${hm(a.endAt)} 到点时确认结果。"
            }
        }
        emit(Event.CheckInClosed(now, p.id, CloseReason.ANSWERED, decision))
        out += Effect.Dismiss(p.id)
        end?.let { endAction(now, it) }
    }

    /** 没选按钮、只写了一句话：先记下，检查保持打开。 */
    @Synchronized
    fun note(checkInId: String, text: String, via: Via): List<Effect> = run {
        val p = situation.pending
        if (p != null && p.id == checkInId && text.isNotBlank()) emit(Event.CheckInNote(clock.now(), p.id, text.trim(), via))
    }

    @Synchronized
    fun interventionResult(id: String, result: ActResult, detail: String): List<Effect> = run {
        emit(Event.InterventionResult(clock.now(), id, result, detail))
    }

    // ---------------------------------------------------------------- 时间推进

    /** 由前台服务每隔十几秒、闹钟、无障碍事件调用。没有需要做的事时什么都不发生。 */
    @Synchronized
    fun tick(): List<Effect> = run {
        val now = clock.now()
        for (p in situation.probes) if (p.probeAt != null && p.probeAt <= now) aftermath(p, now)
        val s = situation
        val a = s.action ?: return@run
        val st = settings()
        val fg = platform.foreground(now)
        val fgCat = fg.pkg?.let { assessor.category(it, a) }

        if (s.blocking && fgCat == AppCat.DISTRACTOR && (s.lastHomeAt == null || now - s.lastHomeAt >= 15_000) && Level.HOME in platform.available()) {
            intervene(Level.HOME, null, fg.pkg, "屏蔽模式：打开了「${platform.label(fg.pkg!!)}」", now)
        }

        s.pending?.let { p ->
            when {
                p.trigger != Trigger.END && now >= a.endAt -> {
                    emit(Event.CheckInClosed(now, p.id, CloseReason.SUPERSEDED, "到点了，转为结束确认"))
                    out += Effect.Dismiss(p.id)
                    open(Trigger.END, now, fg)
                }
                now - p.openedAt >= st.giveUpMin * MIN -> unanswered(p, a, now)
                p.prompts < 2 && now - p.lastPromptAt >= st.renotifyMin * MIN -> {
                    val canInterrupt = st.maxLevel.rank >= Level.INTERRUPT.rank && Level.INTERRUPT in platform.available()
                    val lvl = if (canInterrupt) Level.INTERRUPT else Level.NOTIFY
                    intervene(lvl, p, null, "${st.renotifyMin} 分钟没有回应，再问一次（${lvl.label}）", now, prompt = true)
                }
            }
            return@run
        }

        if (s.mode == Mode.RESTING) {
            if (s.restUntil != null && now >= s.restUntil) {
                emit(Event.Rest(now, null))
                open(Trigger.REST_OVER, now, fg)
            }
            return@run
        }

        when {
            now >= a.endAt -> open(Trigger.END, now, fg)
            !s.muted && fgCat == AppCat.DISTRACTOR && fg.since != s.handledDriftSince && now - fg.since >= st.driftThresholdSec * 1000L -> {
                emit(Event.DriftSeen(now, fg.pkg!!, fg.since))
                open(Trigger.DRIFT, now, fg)
            }
            !s.muted && s.nextCheckAt != null && now >= s.nextCheckAt -> open(Trigger.SCHEDULED, now, fg)
        }
    }

    /** 平台应该在这个时间之前再调用一次 tick（用于设置兜底闹钟）。 */
    fun nextWakeAt(): Long? {
        val s = situation
        val st = settings()
        val c = mutableListOf<Long>()
        s.probes.forEach { p -> p.probeAt?.let(c::add) }
        val p = s.pending
        if (p != null) {
            if (p.prompts < 2) c += p.lastPromptAt + st.renotifyMin * MIN
            c += p.openedAt + st.giveUpMin * MIN
            if (p.trigger != Trigger.END) s.action?.let { c += it.endAt }
        } else if (s.mode == Mode.RESTING) {
            s.restUntil?.let(c::add)
        } else if (s.action != null) {
            c += s.action.endAt
            if (!s.muted) s.nextCheckAt?.let(c::add)
        }
        return c.minOrNull()
    }

    // ---------------------------------------------------------------- 内部

    private fun open(t: Trigger, now: Long, fgIn: Foreground?) {
        val s = situation
        val a = s.action ?: return
        val fg = fgIn ?: platform.foreground(now)
        val from = if (t == Trigger.END) a.startedAt else (s.windowFrom ?: a.startedAt)
        val obs = platform.observe(from, now)
        var dev = assessor.deviation(a, obs)
        if (t == Trigger.DRIFT && fg.pkg != null) {
            dev = dev.copy(kind = DevKind.DRIFT, topDistractor = fg.pkg,
                onsetMin = ((fg.since - a.startedAt) / MIN).toInt().coerceAtLeast(0))
        }
        val choice = Policy.level(t, dev, s, a, settings(), platform.available())
        val c = CheckIn(
            id = newId(), actionId = a.id, actionText = a.text, trigger = t, openedAt = now,
            minuteOfAction = Assessor.minuteOf(a, now), observation = obs, deviation = dev,
            question = assessor.question(a, t, dev, now, fg), level = choice.level, choices = Policy.choices(t),
        )
        emit(Event.CheckInOpened(c))
        emit(Event.NextCheck(now, null, "等待回答"))
        val driftPkg = if (t == Trigger.DRIFT) fg.pkg else null
        val prompt = minOf(choice.level, Level.INTERRUPT, compareBy { it.rank })
        if (choice.level.rank >= Level.HOME.rank && driftPkg != null) {
            if (choice.level == Level.BLOCK) emit(Event.Block(now, a.id))
            intervene(prompt, c, null, choice.reason, now, prompt = true)
            intervene(Level.HOME, null, driftPkg, choice.reason, now)
        } else {
            intervene(prompt, c, driftPkg, choice.reason, now, prompt = true)
        }
    }

    private fun intervene(level: Level, c: CheckIn?, target: String?, reason: String, now: Long, prompt: Boolean = false) {
        val probe = if (target != null) now + settings().effectProbeSec * 1000L else null
        val i = Intervention(newId(), now, level, c?.id, target, reason, probe)
        emit(Event.InterventionRequested(i))
        out += Effect.Intervene(i, if (prompt) (situation.pending ?: c) else null)
    }

    private fun unanswered(p: CheckIn, a: Action, now: Long) {
        val st = settings()
        if (p.trigger == Trigger.END) {
            emit(Event.CheckInClosed(now, p.id, CloseReason.UNANSWERED, "没有回应，按未确认结束"))
            out += Effect.Dismiss(p.id)
            endAction(now, Outcome.UNCONFIRMED)
            return
        }
        val drifting = p.deviation.kind == DevKind.DRIFT
        val next = if (drifting) Policy.nextSoon(now, a, st) else Policy.nextAfterOnTrack(now, a)
        emit(Event.NextCheck(now, next, if (drifting) "没有回应且观察到偏离，短间隔复查" else "没有回应，按观察继续"))
        emit(Event.CheckInClosed(now, p.id, CloseReason.UNANSWERED,
            "${st.giveUpMin} 分钟没有回应。" + (if (drifting) "客观上仍在偏离。" else "按客观观察继续。")))
        out += Effect.Dismiss(p.id)
    }

    private fun endAction(now: Long, outcome: Outcome) {
        val s = situation
        val a = s.action ?: return
        s.pending?.let {
            emit(Event.CheckInClosed(now, it.id, CloseReason.SUPERSEDED, "行动结束"))
            out += Effect.Dismiss(it.id)
        }
        val obs = platform.observe(a.startedAt, now)
        emit(Event.ActionEnded(now, a.id, outcome, obs, assessor.deviation(a, obs)))
    }

    private fun aftermath(i: Intervention, now: Long) {
        val fg = platform.foreground(now)
        val a = situation.action
        val (am, detail) = when {
            fg.state != SensorState.OK -> Aftermath.OTHER to "无法观察"
            fg.pkg == null -> Aftermath.LEFT_PHONE to "息屏 / 锁屏"
            fg.pkg == i.target -> Aftermath.STAYED to "仍在「${platform.label(fg.pkg)}」"
            else -> when (assessor.category(fg.pkg, a)) {
                AppCat.DISTRACTOR -> Aftermath.SHIFTED to "转到「${platform.label(fg.pkg)}」"
                AppCat.TARGET -> Aftermath.RETURNED to "回到「${platform.label(fg.pkg)}」"
                AppCat.NEUTRAL -> Aftermath.OTHER to "停在「${platform.label(fg.pkg)}」"
                AppCat.OTHER -> if (a?.expect?.effectiveMode == EnvMode.IN_APPS) Aftermath.OTHER to "在「${platform.label(fg.pkg)}」"
                    else Aftermath.RETURNED to "离开娱乐，在「${platform.label(fg.pkg)}」"
            }
        }
        emit(Event.InterventionAftermath(now, i.id, am, detail))
    }

    companion object {
        fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(t))
    }
}
