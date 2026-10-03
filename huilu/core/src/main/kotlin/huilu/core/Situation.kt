package huilu.core

/**
 * 当前局面：整个系统的工作记忆。只能通过 [apply] 由事件推进，
 * 因此任何时候都可以从日志重建。
 */
data class Situation(
    val mode: Mode = Mode.IDLE,
    val action: Action? = null,
    /** 已经发出、还没得到回答的检查。 */
    val pending: CheckIn? = null,
    val nextCheckAt: Long? = null,
    val nextCheckReason: String? = null,
    val restUntil: Long? = null,
    val muted: Boolean = false,
    val blocking: Boolean = false,
    /** 本轮行动里第几次偏离到娱乐。 */
    val driftCount: Int = 0,
    val handledDriftSince: Long? = null,
    /** 下一次观察窗口的起点。 */
    val windowFrom: Long? = null,
    /** 等待评估行为效果的干预。 */
    val probes: List<Intervention> = emptyList(),
    val lastHomeAt: Long? = null,
    /** 本轮上一次针对偏离的干预等级，以及它的行为效果。用于决定下一次是否升级。 */
    val lastDriftLevel: Level? = null,
    val lastDriftAftermath: Aftermath? = null,
    /** 最近一次关闭的检查，用于界面展示"系统刚才的判断"。 */
    val lastClosed: CheckIn? = null,
    /** 当前被暂停的 App。跨轮次保留：一轮结束后必须确认解除，不能随局面一起清空。 */
    val locked: Set<String> = emptySet(),
) {
    /** 现在是否还应该保持暂停。 */
    val lockWanted: Boolean get() = action != null && mode == Mode.ACTING && blocking && !muted

    fun apply(e: Event): Situation = when (e) {
        is Event.ActionStarted -> Situation(
            mode = Mode.ACTING, action = e.action, windowFrom = e.action.startedAt,
            lastClosed = lastClosed, locked = locked,
        )
        is Event.ActionRevised -> if (action?.id != e.actionId) this else copy(action = action.copy(text = e.text, endAt = e.endAt))
        is Event.ActionEnded -> if (action?.id != e.actionId) this else Situation(lastClosed = lastClosed, locked = locked)
        is Event.CheckInOpened -> copy(pending = e.checkIn)
        is Event.CheckInNote -> if (pending?.id != e.checkInId) this else copy(pending = pending.copy(notes = pending.notes + e.text))
        is Event.CheckInAnswered -> if (pending?.id != e.checkInId) this
            else copy(pending = pending.copy(answeredAt = e.at, answer = e.kind, answerText = e.text, via = e.via))
        is Event.CheckInClosed -> if (pending?.id != e.checkInId) this else {
            val closed = pending.copy(closedAt = e.at, closeReason = e.reason, decision = e.decision)
            copy(pending = null, lastClosed = closed, windowFrom = e.at)
        }
        is Event.NextCheck -> copy(nextCheckAt = e.nextAt, nextCheckReason = e.reason)
        is Event.Rest -> if (e.until != null) copy(mode = Mode.RESTING, restUntil = e.until)
            else copy(mode = if (action != null) Mode.ACTING else Mode.IDLE, restUntil = null)
        is Event.Mute -> copy(muted = true)
        is Event.Block -> copy(blocking = true)
        is Event.DriftSeen -> copy(handledDriftSince = e.since, driftCount = driftCount + 1)
        is Event.InterventionRequested -> {
            val i = e.intervention
            val drift = i.target != null && i.probeAt != null
            copy(
                pending = if (pending != null && pending.id == i.checkInId) pending.copy(prompts = pending.prompts + 1, lastPromptAt = i.at) else pending,
                probes = if (i.probeAt != null) probes + i else probes,
                lastHomeAt = if (i.level == Level.HOME) i.at else lastHomeAt,
                lastDriftLevel = if (drift) i.level else lastDriftLevel,
                lastDriftAftermath = if (drift) null else lastDriftAftermath,
            )
        }
        is Event.Locked -> copy(locked = locked + e.packages)
        is Event.Unlocked -> copy(locked = locked - e.packages)
        is Event.InterventionResult -> this
        is Event.InterventionAftermath -> copy(
            probes = probes.filter { it.id != e.interventionId },
            lastDriftAftermath = if (probes.any { it.id == e.interventionId }) e.aftermath else lastDriftAftermath,
        )
    }

    companion object {
        fun replay(events: Iterable<Event>): Situation = events.fold(Situation()) { s, e -> s.apply(e) }
    }
}

interface Clock { fun now(): Long }

/** 只追加的持久化日志。 */
interface EventLog {
    fun append(e: Event)
    fun readAll(): List<Event>
}

class MemoryLog : EventLog {
    val events = mutableListOf<Event>()
    override fun append(e: Event) { events += e }
    override fun readAll(): List<Event> = events.toList()
}

/** 平台（Android）提供的观察能力和能力边界。 */
interface Platform {
    fun foreground(now: Long): Foreground
    fun observe(from: Long, to: Long): Observation
    /** 当前真正能尝试的干预等级（权限不足、服务未开启的不在其中）。 */
    fun available(): Set<Level>
    fun label(pkg: String): String
    /** 自身、桌面、系统界面等既不算目标也不算偏离的 App。 */
    fun isNeutral(pkg: String): Boolean
}
