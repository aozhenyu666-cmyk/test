package io.github.ccch1mneyyy.dsh_app.loop

import io.github.ccch1mneyyy.dsh_app.loop.core.Outcome
import io.github.ccch1mneyyy.dsh_app.loop.core.ProactiveEvent
import io.github.ccch1mneyyy.dsh_app.loop.core.Stage
import io.github.ccch1mneyyy.dsh_app.loop.core.Submit
import io.github.ccch1mneyyy.dsh_app.loop.core.Switches

/**
 * One owner per app-enter event (review round 3, defect A).
 *
 * Once LoopEngine hands an event to the kernel, the kernel's [io.github.ccch1mneyyy.dsh_app.loop.core.Coordinator]
 * is the only thing that decides how it ends. LoopEngine keeps a *delegated* twin with the same
 * eventId (surface = [DELEGATED]) so its visit rule ("one open interaction at a time") still holds,
 * and this object writes the kernel's verdict back onto it exactly once:
 *
 *   submit Queued      → twin delegated, stays open
 *   submit Merged      → twin closes SUPPRESSED (merged into another event)
 *   submit Rejected    → twin closes UNAVAILABLE (kernel_<reason>)
 *   kernel SHOWN       → twin SHOWN
 *   kernel CLOSED      → twin closes with the mapped result (first write wins; LoopEngine.answer is idempotent)
 *   process restart    → LoopEngine.recover leaves delegated twins open; [reconcile] then copies
 *                        the kernel's result, or closes ABANDONED(kernel_lost) if the kernel has no record.
 *
 * Plain Kotlin: tested on the JVM with the real LoopEngine and Coordinator.
 */
object KernelMirror {
    const val DELEGATED = "kernel"

    fun resultOf(o: Outcome): EventResult = when (o) {
        Outcome.RESPONDED -> EventResult.RETURNED
        Outcome.DISMISSED -> EventResult.DISMISSED
        Outcome.TIMEOUT -> EventResult.TIMEOUT
        Outcome.ABANDONED -> EventResult.ABANDONED
        Outcome.UNAVAILABLE -> EventResult.UNAVAILABLE
        Outcome.FAILED -> EventResult.ERROR
        // Never reached the person: the kernel held, merged, dropped or cancelled it.
        Outcome.CANCELLED, Outcome.YIELDED, Outcome.EXPIRED, Outcome.DROPPED, Outcome.MERGED -> EventResult.SUPPRESSED
    }

    fun onSubmit(engine: LoopEngine, eventId: String, submit: Submit): LoopEvent? = when (submit) {
        is Submit.Queued -> engine.delegate(eventId)
        is Submit.Merged -> engine.answer(eventId, EventResult.SUPPRESSED, reason = "kernel_merged:${submit.into}", surface = DELEGATED)
        is Submit.Rejected -> engine.answer(eventId, EventResult.UNAVAILABLE, reason = "kernel_${submit.reason}", surface = DELEGATED)
    }

    /** A kernel event closed. Only app-enter events have a twin; anything else is ignored. */
    fun onClosed(engine: LoopEngine, closed: ProactiveEvent): LoopEvent? {
        if (closed.request.source != Switches.SOURCE_APP_ENTER || closed.outcome == null) return null
        val reason = "kernel_${closed.outcome.name.lowercase()}" + (closed.reason?.let { ":$it" } ?: "")
        return engine.answer(closed.eventId, resultOf(closed.outcome), reason = reason, surface = DELEGATED)
    }

    fun onShown(engine: LoopEngine, event: ProactiveEvent?) {
        if (event == null || event.request.source != Switches.SOURCE_APP_ENTER || event.stage != Stage.SHOWN) return
        engine.shown(event.eventId)
    }

    /** After both sides recovered: settle every delegated twin that is still open. */
    fun reconcile(engine: LoopEngine, kernelFind: (String) -> ProactiveEvent?): List<LoopEvent> =
        engine.openDelegated().mapNotNull { id ->
            val k = kernelFind(id)
            when {
                k == null -> engine.answer(id, EventResult.ABANDONED, reason = "kernel_lost", surface = DELEGATED)
                !k.open -> onClosed(engine, k)
                else -> null
            }
        }
}
