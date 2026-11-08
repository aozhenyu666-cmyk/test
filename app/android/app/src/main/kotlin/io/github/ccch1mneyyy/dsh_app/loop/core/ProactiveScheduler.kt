package io.github.ccch1mneyyy.dsh_app.loop.core

import kotlin.math.pow
import kotlin.random.Random

data class SchedulerConfig(
    val enabled: Boolean = false,
    /** A random gap in [minIntervalMs, maxIntervalMs] between attempts. */
    val minIntervalMs: Long = 45 * 60_000L,
    val maxIntervalMs: Long = 3 * 3_600_000L,
    /** Quiet hours as minutes of the local day; equal values switch them off. Crossing midnight is fine. */
    val quietStartMinute: Int = 23 * 60,
    val quietEndMinute: Int = 8 * 60,
    /** Deliveries per local day (posted surfaces, seen or not). 0 = no limit. */
    val dailyMax: Int = 6,
    /** Right after the person used DSH or answered, wait at least this long. */
    val userActiveDeferMs: Long = 20 * 60_000L,
    /** Each confirmed-but-ignored message multiplies the next gap by this… */
    val ignoredBackoff: Double = 2.0,
    /** …up to this factor. */
    val maxBackoffFactor: Double = 8.0,
    /** After this many confirmed-and-ignored in a row, stop until the person shows up. 0 = never. */
    val pauseAfterIgnored: Int = 3,
    val priority: Priority = Priority.NORMAL,
    /** How long a scheduled attempt may wait in the coordinator. */
    val eventTtlMs: Long = 15 * 60_000L,
) {
    fun normalized(): SchedulerConfig {
        val min = minIntervalMs.coerceIn(60_000L, 7 * 86_400_000L)
        return copy(
            minIntervalMs = min, maxIntervalMs = maxIntervalMs.coerceIn(min, 7 * 86_400_000L),
            quietStartMinute = quietStartMinute.coerceIn(0, 1439), quietEndMinute = quietEndMinute.coerceIn(0, 1439),
            dailyMax = dailyMax.coerceIn(0, 100), ignoredBackoff = ignoredBackoff.coerceIn(1.0, 10.0),
            maxBackoffFactor = maxBackoffFactor.coerceIn(1.0, 100.0), pauseAfterIgnored = pauseAfterIgnored.coerceIn(0, 50),
            userActiveDeferMs = userActiveDeferMs.coerceIn(0, 86_400_000L), eventTtlMs = eventTtlMs.coerceIn(60_000L, 86_400_000L),
        )
    }
}

/** Both clocks: wall for the person's day, elapsed (since boot) for intervals the wall cannot bend. */
data class Clocks(val wall: Long, val elapsed: Long, val boot: Int = 0)

/** What the system lets the adapter do in the background. */
data class BackgroundCaps(
    val alarmsAvailable: Boolean = true,
    val exactAlarms: Boolean = true,
    /** "Restrict background activity" / OEM battery killers: alarms may never arrive. */
    val backgroundRestricted: Boolean = false,
)

enum class WakeMode {
    /** One exact alarm at the next decision time. */
    EXACT,
    /** One inexact alarm; the attempt may come late, never early. */
    INEXACT,
    /** No alarms: checks happen only when DSH is opened or its accessibility service sees a window change. */
    OPPORTUNISTIC,
    ;

    companion object {
        fun of(caps: BackgroundCaps): WakeMode = when {
            caps.backgroundRestricted || !caps.alarmsAvailable -> OPPORTUNISTIC
            caps.exactAlarms -> EXACT
            else -> INEXACT
        }
    }
}

data class SchedulerState(
    val nextDueWall: Long = 0,
    val nextDueElapsed: Long = 0,
    val planBoot: Int = 0,
    val planWall: Long = 0,
    val planElapsed: Long = 0,
    /** Local day of [countToday]; only ever moves forward. */
    val dayKey: Long = Long.MIN_VALUE,
    val countToday: Int = 0,
    val ignoredStreak: Int = 0,
    /** Acknowledgements that are still inside their reply window: seen, not yet ignored. */
    val ackedStreak: Int = 0,
    val pausedForIgnored: Boolean = false,
    val lastUserActivityWall: Long = 0,
    val lastUserActivityElapsed: Long = 0,
    val lastUserActivityBoot: Int = 0,
    /** The attempt the coordinator is working on; only its receipt is accepted. */
    val pendingEventId: String? = null,
    val pendingSinceWall: Long = 0,
    val notDeliveredStreak: Int = 0,
    val lastReason: String? = null,
)

/**
 * T6: when to *try* a random proactive interaction. Randomness lives only in [plan]; whether an
 * attempt actually starts is [evaluate], which is deterministic. An attempt becomes an
 * [EventRequest] for the [Coordinator], which still applies every gate; its receipt comes back
 * through [onOutcome] and steers the next gap.
 *
 * No loop: the adapter sets one alarm at the time [evaluate] returns (or none in
 * [WakeMode.OPPORTUNISTIC]) and calls in again when it fires.
 */
class ProactiveScheduler(
    private val store: SnapshotStore,
    /** Minute of the local day (0–1439) for a wall time: the time zone stays with the caller. */
    private val minuteOfDay: (Long) -> Int,
    /** Local calendar day number for a wall time. */
    private val dayOf: (Long) -> Long,
    private val rng: Random = Random.Default,
    private val ids: () -> String = { java.util.UUID.randomUUID().toString() },
    /**
     * A. 主动交流账. The scheduler is its only writer, because it is the only place that knows what
     * an engagement means for the schedule; the book is a separate file with its own counters and
     * cannot affect 行为拦截账 (see [InterceptLedgerBook]).
     */
    private val ledger: ProactiveLedgerBook? = null,
) {
    sealed interface Decision {
        data class Fire(val request: EventRequest) : Decision
        data class Wait(val untilWall: Long, val reason: String) : Decision
    }

    private val lock = Any()
    var config = SchedulerConfig()
        private set
    var state = SchedulerState()
        private set
    @Volatile var storageError: String? = null
        private set
    @Volatile var recoveryIssue: String? = null
        private set

    fun recover(now: Clocks): SchedulerState = synchronized(lock) {
        when (val r = store.read()) {
            SnapshotStore.Read.Empty -> Unit
            is SnapshotStore.Read.Unreadable -> recoveryIssue = "state_unreadable: ${r.error}"
            is SnapshotStore.Read.Loaded -> {
                M.map(r.map, "config")?.let { config = configFrom(it) }
                M.map(r.map, "state")?.let { state = stateFrom(it) }
            }
        }
        val s = state
        when {
            !config.enabled -> Unit
            s.nextDueWall == 0L -> plan(now, "first_plan")
            now.boot != s.planBoot || now.elapsed < s.planElapsed -> {
                // Rebooted: elapsed is gone, the wall is all we have. Sanity-check it.
                val far = s.nextDueWall - now.wall > maxGap() + DAY
                val missed = now.wall - s.nextDueWall > config.maxIntervalMs
                if (far) plan(now, "clock_jump") else if (missed) plan(now, "missed_while_off")
                else state = s.copy(nextDueElapsed = now.elapsed + (s.nextDueWall - now.wall).coerceAtLeast(0), planBoot = now.boot,
                    planWall = now.wall, planElapsed = now.elapsed)
            }
        }
        // A pending attempt whose receipt can no longer come back must not block forever.
        if (s.pendingEventId != null && now.wall - s.pendingSinceWall > config.eventTtlMs + PENDING_GRACE_MS) {
            state = state.copy(pendingEventId = null, lastReason = "pending_lost")
        }
        persist()
        state
    }

    fun setConfig(next: SchedulerConfig, now: Clocks) = synchronized(lock) {
        val n = next.normalized()
        val replan = n.enabled && (!config.enabled || n.minIntervalMs != config.minIntervalMs || n.maxIntervalMs != config.maxIntervalMs)
        config = n
        if (!n.enabled) state = state.copy(pendingEventId = null) else if (replan) plan(now, "config")
        persist()
    }

    /**
     * Companion /plan says the next topic is eligible at [at]. Keep one scheduler and one alarm:
     * move the existing next check there instead of adding a second timer.
     */
    fun setNextDueAt(at: Long, now: Clocks) = synchronized(lock) {
        if (!config.enabled || state.pendingEventId != null) return
        val wall = at.coerceAtLeast(now.wall + 60_000)
        state = state.copy(
            nextDueWall = wall,
            nextDueElapsed = now.elapsed + (wall - now.wall),
            planBoot = now.boot,
            planWall = now.wall,
            planElapsed = now.elapsed,
            lastReason = "companion_plan",
        )
        persist()
    }

    /** Deterministic: the same state and clocks give the same answer. [force] is the user's 「现在试一次」. */
    fun evaluate(now: Clocks, force: Boolean = false): Decision = synchronized(lock) {
        val s = state
        val c = config
        rollDay(now)
        val d = when {
            !c.enabled -> Decision.Wait(0, "disabled")
            s.pausedForIgnored -> Decision.Wait(0, "paused_ignored")
            s.pendingEventId != null -> Decision.Wait(now.wall + RECHECK_MS, "pending")
            !force && inQuiet(now.wall) -> Decision.Wait(quietEndAfter(now.wall), "quiet")
            !force && c.dailyMax > 0 && state.countToday >= c.dailyMax -> Decision.Wait(nextDayStart(now.wall), "daily_max")
            !force && !due(now) -> Decision.Wait(dueWall(now), "not_due")
            !force && userRecentlyActive(now) -> Decision.Wait(userActiveUntil(now), "user_active")
            else -> Decision.Fire(EventRequest(
                eventId = ids(), source = "SCHEDULER", kind = "random_chat", dedupeKey = "scheduler:random",
                priority = c.priority, createdAt = now.wall, ttlMs = c.eventTtlMs, needsAgent = true,
                payload = mapOf("streak" to s.ignoredStreak.toString(), "countToday" to state.countToday.toString()),
            ))
        }
        if (state != s) persist()
        d
    }

    /** The coordinator took (or merged) the attempt: wait for its receipt. */
    fun onSubmitted(eventId: String, now: Clocks) = synchronized(lock) {
        state = state.copy(pendingEventId = eventId, pendingSinceWall = now.wall)
        persist()
    }

    /** The coordinator refused it outright (disabled, duplicate…): plan again without counting. */
    fun onRejected(reason: String, now: Clocks) = synchronized(lock) {
        state = state.copy(pendingEventId = null)
        retrySoon(now, "rejected:$reason")
        persist()
    }

    /**
     * 这次不说 (409 no-topic / agent-busy / in-conversation / desk-busy): the attempt is over, but
     * nothing failed. No delivery count, no not-delivered streak, no quick retry — the companion's
     * next /plan decides when to look again.
     */
    fun onSkipped(eventId: String, reason: String, now: Clocks) = synchronized(lock) {
        if (eventId != state.pendingEventId) return@synchronized
        state = state.copy(pendingEventId = null, lastReason = "skipped:$reason")
        persist()
    }

    /**
     * The person pressed 「我看到了」 on the pending attempt. Attention is confirmed but nothing is
     * answered yet, so the gap is *not* widened and nothing is paused: the attempt stays pending
     * until the coordinator's reply window runs out ([Engagement.IGNORED_SEEN]) or an answer comes.
     *
     * A. 主动交流账 is told at once, so a crash inside the reply window still keeps the
     * confirmation; [onOutcome] will not count it twice.
     */
    fun onAcked(eventId: String, now: Clocks): Boolean = synchronized(lock) {
        if (eventId != state.pendingEventId) {
            // Not this scheduler's attempt, but still a confirmation worth keeping in the book.
            ledger?.onAcked(eventId, now.wall)
            return@synchronized false
        }
        // The streak is driven by the receipt (ACKED / IGNORED_SEEN), so a crash between the ack and
        // the close still ends up counted exactly once, from the record. Here only the book is told.
        state = state.copy(lastReason = "acked")
        ledger?.onAcked(eventId, now.wall)
        persist()
        true
    }

    /** A receipt from the coordinator. Only the pending attempt's receipt counts; repeats are ignored. */
    fun onOutcome(eventId: String, engagement: Engagement, now: Clocks): Boolean = synchronized(lock) {        if (eventId != state.pendingEventId) return@synchronized false
        rollDay(now)
        val s = state.copy(pendingEventId = null)
        val delivered = engagement != Engagement.NOT_DELIVERED
        state = when (engagement) {
            Engagement.RESPONDED, Engagement.DISMISSED ->
                s.copy(ignoredStreak = 0, ackedStreak = 0, pausedForIgnored = false, notDeliveredStreak = 0)
            // 「我看到了」 but no answer yet: the person is engaged, so no backoff, no pause. Only the
            // reply window running out unanswered (IGNORED_SEEN) may widen the gap or pause.
            Engagement.ACKED -> s.copy(ackedStreak = s.ackedStreak + 1, notDeliveredStreak = 0)
            Engagement.IGNORED_SEEN -> {
                val streak = s.ignoredStreak + 1
                s.copy(ignoredStreak = streak, ackedStreak = 0, notDeliveredStreak = 0,
                    pausedForIgnored = config.pauseAfterIgnored in 1..streak)
            }
            // Posted but never confirmed seen: no penalty, no reward.
            Engagement.UNCONFIRMED -> s.copy(notDeliveredStreak = 0)
            Engagement.NOT_DELIVERED -> s.copy(notDeliveredStreak = s.notDeliveredStreak + 1)
        }.let { if (delivered) it.copy(countToday = it.countToday + 1) else it }
        // A. 主动交流账: the same receipt, recorded once, in its own book.
        ledger?.onClosed(eventId, engagement, now.wall)
        if (engagement == Engagement.NOT_DELIVERED && state.notDeliveredStreak <= MAX_QUICK_RETRIES) retrySoon(now, "not_delivered")
        else plan(now, "after_${engagement.name.lowercase()}")
        persist()
        true
    }

    /** The person used DSH (chat, an answer): reset the ignore streak and keep out of their way for a while. */
    fun onUserActivity(now: Clocks) = synchronized(lock) {
        state = state.copy(lastUserActivityWall = now.wall, lastUserActivityElapsed = now.elapsed, lastUserActivityBoot = now.boot,
            ignoredStreak = 0, ackedStreak = 0, pausedForIgnored = false)
        if (config.enabled && dueWall(now) < now.wall + config.userActiveDeferMs) {
            setDue(now, config.userActiveDeferMs + (rng.nextDouble() * config.minIntervalMs / 2).toLong(), "user_active")
        }
        persist()
    }

    /** Where to set the single alarm; null for none (disabled, paused, or no alarms allowed). */
    fun nextWake(now: Clocks, mode: WakeMode): Long? {
        if (mode == WakeMode.OPPORTUNISTIC) return null
        val d = evaluate(now)
        return when (d) {
            is Decision.Fire -> now.wall
            is Decision.Wait -> d.untilWall.takeIf { it > 0 }
        }
    }

    // ---------------------------------------------------------------- planning (the only randomness)

    private fun plan(now: Clocks, reason: String) {
        val c = config
        val base = c.minIntervalMs + (rng.nextDouble() * (c.maxIntervalMs - c.minIntervalMs)).toLong()
        val factor = c.ignoredBackoff.pow(state.ignoredStreak).coerceAtMost(c.maxBackoffFactor)
        var delay = (base * factor).toLong()
        val target = now.wall + delay
        if (inQuiet(target)) delay = quietEndAfter(target) - now.wall + (rng.nextDouble() * QUIET_JITTER_MS).toLong()
        setDue(now, delay, reason)
    }

    private fun retrySoon(now: Clocks, reason: String) {
        val c = config
        setDue(now, c.minIntervalMs / 3 + (rng.nextDouble() * c.minIntervalMs / 6).toLong(), reason)
    }

    private fun setDue(now: Clocks, delayMs: Long, reason: String) {
        state = state.copy(nextDueWall = now.wall + delayMs, nextDueElapsed = now.elapsed + delayMs, planBoot = now.boot,
            planWall = now.wall, planElapsed = now.elapsed, lastReason = reason)
    }

    // ---------------------------------------------------------------- deterministic helpers

    private fun sameBoot(now: Clocks) = now.boot == state.planBoot && now.elapsed >= state.planElapsed && state.planElapsed > 0

    /** Within one boot, elapsed decides: moving the wall clock neither delays nor rushes an attempt. */
    private fun due(now: Clocks) = if (sameBoot(now)) now.elapsed >= state.nextDueElapsed else now.wall >= state.nextDueWall

    private fun dueWall(now: Clocks) = if (sameBoot(now)) now.wall + (state.nextDueElapsed - now.elapsed) else state.nextDueWall

    private fun userRecentlyActive(now: Clocks): Boolean {
        val s = state
        if (s.lastUserActivityWall == 0L) return false
        val since = if (now.boot == s.lastUserActivityBoot && now.elapsed >= s.lastUserActivityElapsed) now.elapsed - s.lastUserActivityElapsed
        else now.wall - s.lastUserActivityWall
        return since in 0 until config.userActiveDeferMs
    }

    private fun userActiveUntil(now: Clocks): Long {
        val s = state
        val since = if (now.boot == s.lastUserActivityBoot && now.elapsed >= s.lastUserActivityElapsed) now.elapsed - s.lastUserActivityElapsed
        else now.wall - s.lastUserActivityWall
        return now.wall + (config.userActiveDeferMs - since).coerceAtLeast(60_000L)
    }

    /** The local day only moves forward: setting the clock back cannot reset the daily count. */
    private fun rollDay(now: Clocks) {
        val today = dayOf(now.wall)
        if (today > state.dayKey) state = state.copy(dayKey = today, countToday = 0)
    }

    private fun inQuiet(wall: Long): Boolean {
        val s = config.quietStartMinute
        val e = config.quietEndMinute
        if (s == e) return false
        val m = minuteOfDay(wall)
        return if (s < e) m in s until e else m >= s || m < e
    }

    private fun quietEndAfter(wall: Long): Long {
        val m = minuteOfDay(wall)
        val mins = ((config.quietEndMinute - m) % 1440 + 1440) % 1440
        return wall - (wall % MINUTE) + mins * MINUTE
    }

    private fun nextDayStart(wall: Long): Long {
        val mins = 1440 - minuteOfDay(wall)
        val midnight = wall - (wall % MINUTE) + mins * MINUTE
        return if (inQuiet(midnight)) quietEndAfter(midnight) else midnight
    }

    private fun maxGap(): Long = (config.maxIntervalMs * config.maxBackoffFactor).toLong()

    // ---------------------------------------------------------------- persistence

    private fun persist() {
        storageError = try {
            store.write(linkedMapOf("version" to 1, "config" to configMap(config), "state" to stateMap(state))); null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}".take(300)
        }
    }

    companion object {
        const val MINUTE = 60_000L
        const val DAY = 86_400_000L
        const val RECHECK_MS = 5 * MINUTE
        const val QUIET_JITTER_MS = 30 * MINUTE
        const val PENDING_GRACE_MS = 15 * MINUTE
        const val MAX_QUICK_RETRIES = 2

        fun configMap(c: SchedulerConfig): Map<String, Any?> = linkedMapOf(
            "enabled" to c.enabled, "minIntervalMs" to c.minIntervalMs, "maxIntervalMs" to c.maxIntervalMs,
            "quietStartMinute" to c.quietStartMinute, "quietEndMinute" to c.quietEndMinute, "dailyMax" to c.dailyMax,
            "userActiveDeferMs" to c.userActiveDeferMs, "ignoredBackoff" to c.ignoredBackoff, "maxBackoffFactor" to c.maxBackoffFactor,
            "pauseAfterIgnored" to c.pauseAfterIgnored, "priority" to c.priority.name, "eventTtlMs" to c.eventTtlMs,
        )

        fun configFrom(m: Map<*, *>): SchedulerConfig {
            val d = SchedulerConfig()
            return SchedulerConfig(
                enabled = M.bool(m, "enabled"), minIntervalMs = M.long(m, "minIntervalMs", d.minIntervalMs),
                maxIntervalMs = M.long(m, "maxIntervalMs", d.maxIntervalMs), quietStartMinute = M.int(m, "quietStartMinute", d.quietStartMinute),
                quietEndMinute = M.int(m, "quietEndMinute", d.quietEndMinute), dailyMax = M.int(m, "dailyMax", d.dailyMax),
                userActiveDeferMs = M.long(m, "userActiveDeferMs", d.userActiveDeferMs),
                ignoredBackoff = (m["ignoredBackoff"] as? Number)?.toDouble() ?: d.ignoredBackoff,
                maxBackoffFactor = (m["maxBackoffFactor"] as? Number)?.toDouble() ?: d.maxBackoffFactor,
                pauseAfterIgnored = M.int(m, "pauseAfterIgnored", d.pauseAfterIgnored),
                priority = M.enum<Priority>(m, "priority") ?: d.priority, eventTtlMs = M.long(m, "eventTtlMs", d.eventTtlMs),
            ).normalized()
        }

        fun stateMap(s: SchedulerState): Map<String, Any?> = linkedMapOf(
            "nextDueWall" to s.nextDueWall, "nextDueElapsed" to s.nextDueElapsed, "planBoot" to s.planBoot, "planWall" to s.planWall,
            "planElapsed" to s.planElapsed, "dayKey" to s.dayKey, "countToday" to s.countToday, "ignoredStreak" to s.ignoredStreak,
            "ackedStreak" to s.ackedStreak,
            "pausedForIgnored" to s.pausedForIgnored, "lastUserActivityWall" to s.lastUserActivityWall,
            "lastUserActivityElapsed" to s.lastUserActivityElapsed, "lastUserActivityBoot" to s.lastUserActivityBoot,
            "pendingEventId" to s.pendingEventId, "pendingSinceWall" to s.pendingSinceWall,
            "notDeliveredStreak" to s.notDeliveredStreak, "lastReason" to s.lastReason,
        )

        fun stateFrom(m: Map<*, *>) = SchedulerState(
            nextDueWall = M.long(m, "nextDueWall"), nextDueElapsed = M.long(m, "nextDueElapsed"), planBoot = M.int(m, "planBoot"),
            planWall = M.long(m, "planWall"), planElapsed = M.long(m, "planElapsed"), dayKey = M.long(m, "dayKey", Long.MIN_VALUE),
            countToday = M.int(m, "countToday"), ignoredStreak = M.int(m, "ignoredStreak"),
            ackedStreak = M.int(m, "ackedStreak"), pausedForIgnored = M.bool(m, "pausedForIgnored"),
            lastUserActivityWall = M.long(m, "lastUserActivityWall"), lastUserActivityElapsed = M.long(m, "lastUserActivityElapsed"),
            lastUserActivityBoot = M.int(m, "lastUserActivityBoot"), pendingEventId = M.str(m, "pendingEventId"),
            pendingSinceWall = M.long(m, "pendingSinceWall"), notDeliveredStreak = M.int(m, "notDeliveredStreak"),
            lastReason = M.str(m, "lastReason"),
        )
    }
}
