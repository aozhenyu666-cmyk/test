package io.github.ccch1mneyyy.dsh_app.loop

import java.util.UUID

/**
 * The first closed loop, platform-free:
 *
 *   verified foreground → [ForegroundTracker] (new visit?) → [TriggerRules] → open event
 *   → InteractionHub shows the page → [answer] closes the event → receipt persisted.
 *
 * Every state change is written before the method returns, so a process death at any point
 * leaves a file [recover] can close honestly (ABANDONED) instead of forgetting the event.
 * Thread-safe: the Android side calls in from the main thread and the plugin thread.
 */
class LoopEngine(
    private val store: LoopStore,
    filter: PackageFilter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    /** Local time facts for quiet hours and the daily cap; the Android side supplies them. */
    private val minuteOfDay: () -> Int = { -1 },
    private val dayKey: () -> String? = { null },
) {
    sealed interface Outcome {
        /** Show the interaction page for this event now. */
        data class Show(val event: LoopEvent, val timeoutMs: Long) : Outcome
        /** A candidate was turned down by a rule; the event is already closed and stored. */
        data class Suppressed(val event: LoopEvent) : Outcome
        /** Not a candidate at all (same visit, neutral window, …). Nothing stored. */
        data class Ignored(val why: String) : Outcome
    }

    private val lock = Any()
    private val tracker = ForegroundTracker(filter)
    private var config = LoopConfig()
    private var lastTriggerAt = 0L
    private val events = ArrayDeque<LoopEvent>()

    /** Triggers already spent, and the device day they belong to (the cap resets when it changes). */
    private var triggerDay: String? = null
    private var triggersToday = 0

    /** Last storage failure, for the settings page. Null once a write succeeds again. */
    @Volatile var storageError: String? = null
        private set

    /** Set by [recover] when the file was unusable and the backup was loaded instead. */
    @Volatile var recoveredFrom: String? = null
        private set

    /**
     * Process start / service (re)connect. Loads the file; any event still open belonged to a
     * process or page that is gone, so it closes as ABANDONED. The visit is kept: a reconnect
     * while the person is still in the test app does not count as a new entry.
     */
    fun recover(): List<LoopEvent> = synchronized(lock) {
        val closed = mutableListOf<LoopEvent>()
        recoveredFrom = null
        when (val r = store.read()) {
            LoopStore.Read.Empty -> Unit
            is LoopStore.Read.Unreadable -> {
                events.clear()
                closed += record(newEvent(EventSource.RECOVERY, EventState.CLOSED, EventResult.ERROR, error = "state_unreadable: ${r.error}"))
            }
            is LoopStore.Read.Loaded -> {
                config = r.snapshot.config
                tracker.restore(r.snapshot.tracker)
                lastTriggerAt = r.snapshot.lastTriggerAt
                triggerDay = r.snapshot.triggerDay
                triggersToday = r.snapshot.triggersToday
                recoveredFrom = r.recoveredFrom
                events.clear()
                events.addAll(r.snapshot.events.takeLast(LoopStore.MAX_EVENTS))
                if (r.recoveredFrom != null) {
                    // The main file was damaged; say so in the receipts rather than looking healthy.
                    closed += record(newEvent(EventSource.RECOVERY, EventState.CLOSED, EventResult.ERROR, error = "state_recovered_from_backup: ${r.recoveredFrom}"))
                }
                val now = clock()
                for (i in events.indices) {
                    val e = events[i]
                    // Delegated to the kernel: the kernel decides how it ended (KernelMirror.reconcile).
                    if (!e.open || e.surface == KernelMirror.DELEGATED) continue
                    val c = e.copy(state = EventState.CLOSED, result = EventResult.ABANDONED, reason = "process_restart", updatedAt = now)
                    events[i] = c
                    closed += c
                }
            }
        }
        persist()
        closed
    }

    fun config(): LoopConfig = synchronized(lock) { config }

    fun setConfig(next: LoopConfig): LoopConfig = synchronized(lock) {
        val n = next.normalized()
        // A different test app is a different loop: drop the visit and close what is open.
        if (n.targetPackage != config.targetPackage || !n.enabled) closeOpenLocked(EventResult.DISMISSED, "config_changed")
        config = n
        persist()
        config
    }

    /** One verified foreground reading. [env] is read by the caller just before. */
    fun onForeground(verified: String?, env: Environment, source: EventSource = EventSource.A11Y_WINDOW): Outcome = synchronized(lock) {
        val target = config.targetPackage.takeIf { config.enabled }
        val before = tracker.state
        val step = tracker.onForeground(verified, target) { ids() }
        if (tracker.state != before) persist()
        val visitId = when (step) {
            is ForegroundTracker.Step.Entered -> step.visitId
            ForegroundTracker.Step.Stayed -> return Outcome.Ignored("same_visit")
            ForegroundTracker.Step.Exited -> return Outcome.Ignored("exited")
            is ForegroundTracker.Step.Ignored -> return Outcome.Ignored(step.why)
        }
        val now = clock()
        if (lastTriggerAt > now) lastTriggerAt = now // the clock went back: restart the cooldown from here
        val base = newEvent(source, EventState.CONFIRMED, packageName = verified, visitId = visitId)
        // The day bucket rolls over at midnight local time, so "today" never inherits yesterday's count.
        val today = env.dayKey ?: dayKey()
        if (today != null && today != triggerDay) { triggerDay = today; triggersToday = 0 }
        val spent = if (today != null && today == triggerDay) triggersToday else 0
        val minute = if (env.minuteOfDay >= 0) env.minuteOfDay else minuteOfDay()
        val moment = env.copy(minuteOfDay = minute, dayKey = today)
        when (val d = TriggerRules.decide(now, config, moment, lastTriggerAt, events.any { it.open }, spent)) {
            is TriggerRules.Decision.Suppress -> Outcome.Suppressed(
                record(base.copy(state = EventState.CLOSED, result = EventResult.SUPPRESSED, reason = d.reason)),
            )
            TriggerRules.Decision.Trigger -> {
                lastTriggerAt = now
                if (today != null) { triggerDay = today; triggersToday = spent + 1 }
                Outcome.Show(record(base.copy(state = EventState.TRIGGERED, surface = "activity")), config.timeoutMs)
            }
        }
    }

    /** The page is on screen. False when the event is already closed (stale page: finish it). */
    fun shown(eventId: String): Boolean = synchronized(lock) {
        val e = find(eventId) ?: return false
        if (e.state == EventState.SHOWN) return true
        if (e.state != EventState.TRIGGERED) return false
        replace(e.copy(state = EventState.SHOWN, updatedAt = clock()))
        true
    }

    /**
     * The kernel took this event (KernelMirror): it stays open here until the kernel's verdict is
     * written back, so the one-open-interaction rule keeps holding. Null when already closed.
     */
    fun delegate(eventId: String): LoopEvent? = synchronized(lock) {
        val e = find(eventId)?.takeIf { it.open } ?: return null
        replace(e.copy(surface = KernelMirror.DELEGATED, updatedAt = clock()))
    }

    /** Open events whose outcome belongs to the kernel. */
    fun openDelegated(): List<String> = synchronized(lock) {
        events.filter { it.open && it.surface == KernelMirror.DELEGATED }.map { it.eventId }
    }

    /** Where [eventId] stands now; null when unknown (evicted or never existed). */
    fun stateOf(eventId: String): EventState? = synchronized(lock) { find(eventId)?.state }

    /**
     * Records which surface is carrying the question right now, without closing the event: the page
     * did not come up, so the notification took over. Null when the event is already closed (nothing
     * is left to ask), which the caller reads as "drop the notification".
     */
    fun markSurface(eventId: String, surface: String): LoopEvent? = synchronized(lock) {
        val e = find(eventId)?.takeIf { it.open } ?: return null
        replace(e.copy(surface = surface, updatedAt = clock()))
    }

    /** Where the event was asked, for the fallback decision; null when unknown. */
    fun surfaceOf(eventId: String): String? = synchronized(lock) { find(eventId)?.surface }

    /**
     * Closes the event with the person's answer (or TIMEOUT / DISMISSED / UNAVAILABLE / ERROR).
     * Idempotent: the first answer wins, later ones (double tap, back + button) return null.
     *
     * [surface] records where the question was actually put, when it is not the page (T3's
     * notification fallback passes `"notification"`). It is written only when the event did not
     * already declare one, so the receipt always names the surface that closed it.
     */
    fun answer(eventId: String, result: EventResult, reason: String? = null, error: String? = null, surface: String? = null): LoopEvent? = synchronized(lock) {
        val e = find(eventId)?.takeIf { it.open } ?: return null
        replace(
            e.copy(
                state = EventState.CLOSED, result = result, reason = reason ?: e.reason, error = error,
                surface = surface ?: e.surface, updatedAt = clock(),
            ),
        )
    }

    /** Newest first. */
    fun events(limit: Int = 50): List<LoopEvent> = synchronized(lock) { events.reversed().take(limit.coerceIn(0, LoopStore.MAX_EVENTS)) }

    fun clearEvents() = synchronized(lock) {
        events.removeAll { !it.open }
        persist()
    }

    fun trackerState(): ForegroundTracker.State = synchronized(lock) { tracker.state }

    /** Triggers spent in the current device day, and that day, for the settings page. */
    fun dailyUsage(): Pair<String?, Int> = synchronized(lock) { triggerDay to triggersToday }

    private fun closeOpenLocked(result: EventResult, reason: String) {
        val now = clock()
        // Delegated twins are closed by the kernel's verdict only (KernelMirror), never here.
        for (i in events.indices) if (events[i].open && events[i].surface != KernelMirror.DELEGATED) {
            events[i] = events[i].copy(state = EventState.CLOSED, result = result, reason = reason, updatedAt = now)
        }
    }

    private fun newEvent(
        source: EventSource, state: EventState, result: EventResult? = null, error: String? = null,
        packageName: String? = null, visitId: String? = null,
    ): LoopEvent {
        val now = clock()
        return LoopEvent(ids(), source, now, state, result, error, packageName = packageName, visitId = visitId, updatedAt = now)
    }

    private fun find(id: String): LoopEvent? = events.lastOrNull { it.eventId == id }

    private fun record(e: LoopEvent): LoopEvent {
        events.addLast(e)
        while (events.size > LoopStore.MAX_EVENTS) {
            // Never evict an open event: its page still needs to close it.
            val idx = events.indexOfFirst { !it.open }.takeIf { it >= 0 } ?: break
            events.removeAt(idx)
        }
        persist()
        return e
    }

    private fun replace(e: LoopEvent): LoopEvent {
        val i = events.indexOfLast { it.eventId == e.eventId }
        if (i >= 0) events[i] = e
        persist()
        return e
    }

    private fun persist() {
        storageError = try {
            store.write(LoopSnapshot(config, tracker.state, lastTriggerAt, triggerDay, triggersToday, events.toList()))
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}".take(300)
        }
    }
}
