package io.github.ccch1mneyyy.dsh_app.loop

// EventScheduler: the local rules that decide whether a confirmed entry may trigger.
// v1 (T5) adds the focusmate SchedulePolicy ideas that need no new permissions: quiet hours that
// may cross midnight, and a per-day trigger cap that resets on the next day. Random proactive
// scheduling, alarms and boot re-scheduling stay out (T6, needs Claude review) and will feed the
// same [TriggerRules.decide] so every trigger passes the same gates.

data class LoopConfig(
    val enabled: Boolean = false,
    val targetPackage: String? = null,
    /** Minimum gap between two triggers, across restarts (wall clock). */
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    /** How long the interaction page waits for an answer. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Quiet hours: minutes after midnight, inclusive. Null = no quiet window. */
    val quietStartMinute: Int? = null,
    /** Quiet hours end, exclusive. Smaller than [quietStartMinute] means the window crosses midnight. */
    val quietEndMinute: Int? = null,
    /** Maximum triggers per day; 0 = unlimited. Counted per device day, reset when the day changes. */
    val dailyCap: Int = 0,
) {
    /** True when both ends are set and they describe a non-empty window. */
    val quietHoursSet: Boolean
        get() = quietStartMinute != null && quietEndMinute != null && quietStartMinute != quietEndMinute

    fun normalized(): LoopConfig = copy(
        targetPackage = targetPackage?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 },
        cooldownMs = cooldownMs.coerceIn(0, 24 * 3_600_000L),
        timeoutMs = timeoutMs.coerceIn(5_000, 10 * 60_000L),
        quietStartMinute = quietStartMinute?.takeIf { it in 0..1439 },
        quietEndMinute = quietEndMinute?.takeIf { it in 0..1439 },
        dailyCap = dailyCap.coerceIn(0, 200),
    )

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled, "targetPackage" to targetPackage, "cooldownMs" to cooldownMs, "timeoutMs" to timeoutMs,
        "quietStartMinute" to quietStartMinute, "quietEndMinute" to quietEndMinute, "dailyCap" to dailyCap,
    )

    companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
        const val DEFAULT_TIMEOUT_MS = 60_000L

        fun fromMap(m: Map<*, *>?): LoopConfig = if (m == null) LoopConfig() else LoopConfig(
            enabled = m["enabled"] == true,
            targetPackage = m["targetPackage"] as? String,
            cooldownMs = (m["cooldownMs"] as? Number)?.toLong() ?: DEFAULT_COOLDOWN_MS,
            timeoutMs = (m["timeoutMs"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_MS,
            quietStartMinute = (m["quietStartMinute"] as? Number)?.toInt(),
            quietEndMinute = (m["quietEndMinute"] as? Number)?.toInt(),
            dailyCap = (m["dailyCap"] as? Number)?.toInt() ?: 0,
        ).normalized()
    }
}

/** Facts the Android side supplies at decision time. */
data class Environment(
    val screenInteractive: Boolean = true,
    val keyguardLocked: Boolean = false,
    /** A DSH phone task is running or a DSH question is on screen: never compete with it. */
    val dshBusy: Boolean = false,
    /** Local minutes after midnight (0..1439); -1 when the caller does not know. */
    val minuteOfDay: Int = -1,
    /** Local calendar day, e.g. "2026-10-31" — the bucket the daily cap counts in. Null = not counted. */
    val dayKey: String? = null,
)

object TriggerRules {
    sealed interface Decision {
        data object Trigger : Decision
        data class Suppress(val reason: String) : Decision
    }

    /**
     * The order is the rule: the first failing gate names the reason.
     *
     * [triggersToday] is the count already spent in [Environment.dayKey]; the caller keeps it across
     * restarts, so a reboot at noon does not hand out a second day's worth.
     */
    fun decide(
        now: Long,
        config: LoopConfig,
        env: Environment,
        lastTriggerAt: Long,
        hasOpenEvent: Boolean,
        triggersToday: Int = 0,
    ): Decision = when {
        !config.enabled -> Decision.Suppress("disabled")
        !env.screenInteractive -> Decision.Suppress("screen_off")
        env.keyguardLocked -> Decision.Suppress("locked")
        env.dshBusy -> Decision.Suppress("dsh_busy")
        hasOpenEvent -> Decision.Suppress("interaction_open")
        lastTriggerAt > now -> Decision.Suppress("clock_back")
        inQuietHours(config, env.minuteOfDay) -> Decision.Suppress("quiet_hours")
        overDailyCap(config, triggersToday) -> Decision.Suppress("daily_cap")
        lastTriggerAt != 0L && now - lastTriggerAt < config.cooldownMs -> Decision.Suppress("cooldown")
        else -> Decision.Trigger
    }

    /**
     * Quiet hours, possibly crossing midnight. [minuteOfDay] < 0 (unknown) never suppresses:
     * a caller that cannot tell the time must not silently silence the loop.
     *
     * 23:00→08:00 = [1380,1440) ∪ [0,480); 12:00→14:00 = [720,840).
     */
    fun inQuietHours(config: LoopConfig, minuteOfDay: Int): Boolean {
        if (!config.quietHoursSet || minuteOfDay !in 0..1439) return false
        val start = config.quietStartMinute!!
        val end = config.quietEndMinute!!
        return if (start < end) minuteOfDay >= start && minuteOfDay < end
        else minuteOfDay >= start || minuteOfDay < end
    }

    private fun overDailyCap(config: LoopConfig, triggersToday: Int): Boolean =
        config.dailyCap > 0 && triggersToday >= config.dailyCap
}
