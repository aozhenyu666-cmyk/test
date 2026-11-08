package io.github.ccch1mneyyy.dsh_app.loop

// The one event record shared by EventObserver, EventScheduler, InteractionHub and (later)
// AgentBridge. Plain Kotlin so the loop runs in JVM tests.

/** Where an event came from. Only [A11Y_WINDOW] and [RECOVERY] exist in v1. */
enum class EventSource { A11Y_WINDOW, SCHEDULER, RECOVERY }

/**
 * How far an event got. Moves forward only: CONFIRMED → TRIGGERED → SHOWN → CLOSED.
 * A suppressed or failed event goes straight to CLOSED with its [EventResult].
 */
enum class EventState { CONFIRMED, TRIGGERED, SHOWN, CLOSED }

/** Set exactly once, when the event becomes [EventState.CLOSED]. */
enum class EventResult {
    /** The person tapped the page's 返回 button. */
    RETURNED,
    /** The person pressed the system back key / gesture on the page. */
    BACK,
    /** The page left the screen without an answer (home, recents, screen off). */
    DISMISSED,
    /** No answer before the page's deadline. */
    TIMEOUT,
    /** The process or service died while the event was open; closed on recovery. */
    ABANDONED,
    /** The page could not be shown (start refused, never resumed). */
    UNAVAILABLE,
    /** A local rule said no (cooldown, DSH busy, locked, …); see [LoopEvent.reason]. */
    SUPPRESSED,
    /** Something threw; see [LoopEvent.error]. */
    ERROR,
}

data class LoopEvent(
    val eventId: String,
    val source: EventSource,
    /** Wall clock (ms) when the event was created. */
    val timestamp: Long,
    val state: EventState,
    val result: EventResult? = null,
    val error: String? = null,
    /** Why a rule suppressed it, or why it closed the way it did. */
    val reason: String? = null,
    val packageName: String? = null,
    /** One visit = one stay in the test app; at most one trigger per visit. */
    val visitId: String? = null,
    /** "activity" in v1; "overlay" / "notification" later. */
    val surface: String? = null,
    val updatedAt: Long = timestamp,
) {
    val open: Boolean get() = state != EventState.CLOSED

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "eventId" to eventId, "source" to source.name, "timestamp" to timestamp, "state" to state.name,
        "result" to result?.name, "error" to error, "reason" to reason, "packageName" to packageName,
        "visitId" to visitId, "surface" to surface, "updatedAt" to updatedAt,
    )

    companion object {
        fun fromMap(m: Map<*, *>): LoopEvent? = runCatching {
            LoopEvent(
                eventId = m["eventId"] as String,
                source = EventSource.valueOf(m["source"] as String),
                timestamp = (m["timestamp"] as Number).toLong(),
                state = EventState.valueOf(m["state"] as String),
                result = (m["result"] as? String)?.let { EventResult.valueOf(it) },
                error = m["error"] as? String,
                reason = m["reason"] as? String,
                packageName = m["packageName"] as? String,
                visitId = m["visitId"] as? String,
                surface = m["surface"] as? String,
                updatedAt = (m["updatedAt"] as? Number)?.toLong() ?: (m["timestamp"] as Number).toLong(),
            )
        }.getOrNull()
    }
}
