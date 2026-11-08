package io.github.ccch1mneyyy.dsh_app.loop

import io.github.ccch1mneyyy.dsh_app.loop.core.Coordinator
import io.github.ccch1mneyyy.dsh_app.loop.core.CoordinatorConfig
import io.github.ccch1mneyyy.dsh_app.loop.core.EventRequest
import io.github.ccch1mneyyy.dsh_app.loop.core.Gate
import io.github.ccch1mneyyy.dsh_app.loop.core.MemorySnapshotStore
import io.github.ccch1mneyyy.dsh_app.loop.core.Outcome
import io.github.ccch1mneyyy.dsh_app.loop.core.Command
import io.github.ccch1mneyyy.dsh_app.loop.core.Step
import io.github.ccch1mneyyy.dsh_app.loop.core.Submit
import io.github.ccch1mneyyy.dsh_app.loop.core.SurfaceReport
import io.github.ccch1mneyyy.dsh_app.loop.core.Switches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review round 3, defects A and B: one owner per app-enter event, and the two feature switches
 * not depending on each other. Real LoopEngine + real Coordinator, wired the way
 * LoopRuntime / ProactiveRuntime wire them.
 */
class KernelMirrorTest {
    private var now = 1_700_000_000_000L
    private var seq = 0
    private val target = "com.example.test"
    private val other = "com.example.other"
    private val loopStore = MemoryLoopStore()
    private val kernelStore = MemorySnapshotStore()

    private fun engine() = LoopEngine(loopStore, PackageFilter("io.github.ccch1mneyyy.dsh_app"), { now }, { "evt-${++seq}-00000" })
    private fun kernel() = Coordinator(kernelStore, CoordinatorConfig(), { now }, { "tok-${++seq}" })

    private fun armed(e: LoopEngine) = e.apply {
        recover()
        setConfig(LoopConfig(enabled = true, targetPackage = target, cooldownMs = 0, timeoutMs = 30_000))
    }

    /** What D5's LoopRuntime.submitToKernel builds. */
    private fun request(show: LoopEngine.Outcome.Show) = EventRequest(
        eventId = show.event.eventId, source = Switches.SOURCE_APP_ENTER, kind = "app_enter",
        dedupeKey = "app_enter:${show.event.packageName}", createdAt = show.event.timestamp, ttlMs = 120_000,
        needsAgent = false, fallbackText = "你打开了测试应用",
    )

    /** The fixed runtime: every closed kernel event is mirrored. */
    private fun Step.mirror(e: LoopEngine): Step = also { s -> s.closed.forEach { KernelMirror.onClosed(e, it) } }

    private fun enterAndHandOff(e: LoopEngine, k: Coordinator): Pair<LoopEngine.Outcome.Show, Command.Present> {
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val (r, step) = k.submit(request(show))
        KernelMirror.onSubmit(e, show.event.eventId, r)
        step.mirror(e)
        return show to step.commands.filterIsInstance<Command.Present>().single()
    }

    @Test fun defectA_withoutWriteBackTheFirstRoundEventStaysOpenAndBlocksEveryLaterVisit() {
        // D5 as submitted: submit, no write-back.
        val e = armed(engine())
        val k = kernel().also { it.recover(Gate()) }
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val (_, step) = k.submit(request(show))
        val p = step.commands.filterIsInstance<Command.Present>().single()
        k.onSurface(p.token, SurfaceReport.Posted)
        k.onSurface(p.token, SurfaceReport.Shown)
        assertEquals(Outcome.RESPONDED, k.onSurface(p.token, SurfaceReport.Answered("ok")).closed.single().outcome)
        assertEquals("the round-1 twin is still open", EventState.TRIGGERED, e.stateOf(show.event.eventId))
        e.onForeground(other, Environment())
        now += 3_600_000
        val next = e.onForeground(target, Environment())
        assertEquals("every later visit is suppressed", "interaction_open", (next as LoopEngine.Outcome.Suppressed).event.reason)
    }

    @Test fun theKernelVerdictIsWrittenBackOnceAndTheNextVisitTriggers() {
        val e = armed(engine())
        val k = kernel().also { it.recover(Gate()) }
        val (show, p) = enterAndHandOff(e, k)
        val id = show.event.eventId
        assertEquals(KernelMirror.DELEGATED, e.events().first().surface)
        k.onSurface(p.token, SurfaceReport.Posted)
        k.onSurface(p.token, SurfaceReport.Shown).mirror(e)
        KernelMirror.onShown(e, k.find(id))
        assertEquals(EventState.SHOWN, e.stateOf(id))
        val closed = k.onSurface(p.token, SurfaceReport.Answered("ok")).mirror(e).closed.single()
        val twin = e.events().first()
        assertEquals(id, twin.eventId)
        assertEquals(EventResult.RETURNED, twin.result)
        assertEquals("kernel_responded", twin.reason)
        assertNull("written once", KernelMirror.onClosed(e, closed))
        assertNull("the old round-1 page cannot answer it either", e.answer(id, EventResult.BACK))
        e.onForeground(other, Environment())
        assertTrue(e.onForeground(target, Environment()) is LoopEngine.Outcome.Show)
    }

    @Test fun everyKernelEndingHasOneConsistentTwinResult() {
        val cases = listOf(
            Outcome.DISMISSED to EventResult.DISMISSED, Outcome.TIMEOUT to EventResult.TIMEOUT,
            Outcome.ABANDONED to EventResult.ABANDONED, Outcome.UNAVAILABLE to EventResult.UNAVAILABLE,
            Outcome.FAILED to EventResult.ERROR, Outcome.CANCELLED to EventResult.SUPPRESSED,
            Outcome.YIELDED to EventResult.SUPPRESSED, Outcome.EXPIRED to EventResult.SUPPRESSED,
        )
        for ((o, r) in cases) assertEquals(o.name, r, KernelMirror.resultOf(o))
        // Timeout end to end.
        val e = armed(engine())
        val k = kernel().also { it.recover(Gate()) }
        val (show, p) = enterAndHandOff(e, k)
        k.onSurface(p.token, SurfaceReport.Posted)
        k.onSurface(p.token, SurfaceReport.Shown)
        now += 60_000
        k.tick().mirror(e)
        assertEquals(EventResult.TIMEOUT, e.events().first { it.eventId == show.event.eventId }.result)
    }

    @Test fun refusedAndMergedHandOffsCloseTheTwinImmediately() {
        val e = armed(engine())
        val k = kernel().also { it.recover(Gate(featureEnabled = false)) }
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val (r, _) = k.submit(request(show))
        assertEquals(Submit.Rejected("disabled"), r)
        assertEquals(EventResult.UNAVAILABLE to "kernel_disabled", KernelMirror.onSubmit(e, show.event.eventId, r)!!.let { it.result to it.reason })
        val merged = KernelMirror.onSubmit(e, "nope", Submit.Merged("x"))
        assertNull("unknown twin: nothing to write", merged)
    }

    @Test fun restartWhileTheKernelStillHoldsTheEventKeepsBothSidesInStep() {
        var e = armed(engine())
        var k = kernel().also { it.recover(Gate(dshTaskActive = true)) } // DSH busy: held in the kernel queue
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val (r, _) = k.submit(request(show))
        KernelMirror.onSubmit(e, show.event.eventId, r)
        // Process dies.
        e = engine(); e.recover()
        assertEquals("not abandoned by round 1 alone", EventState.TRIGGERED, e.stateOf(show.event.eventId))
        k = kernel()
        val rec = k.recover(Gate()).mirror(e)
        assertTrue(KernelMirror.reconcile(e, k::find).isEmpty())
        val p = rec.commands.filterIsInstance<Command.Present>().single()
        assertEquals(show.event.eventId, p.eventId)
        k.onSurface(p.token, SurfaceReport.Posted)
        k.onSurface(p.token, SurfaceReport.Dismissed).mirror(e)
        assertEquals(EventResult.DISMISSED, e.events().first { it.eventId == show.event.eventId }.result)
    }

    @Test fun restartWhileTheEventWasOnScreenAbandonsItOnBothSides() {
        var e = armed(engine())
        var k = kernel().also { it.recover(Gate()) }
        val (show, p) = enterAndHandOff(e, k)
        k.onSurface(p.token, SurfaceReport.Posted)
        k.onSurface(p.token, SurfaceReport.Shown)
        e = engine(); e.recover()
        k = kernel()
        // Round 1 recovered first, kernel second (ProactiveRuntime.ensure mirrors its recovery step).
        k.recover(Gate()).mirror(e)
        val twin = e.events().first { it.eventId == show.event.eventId }
        assertEquals(EventResult.ABANDONED, twin.result)
        assertEquals("kernel_abandoned:process_restart", twin.reason)
        assertEquals(Outcome.ABANDONED, k.find(show.event.eventId)!!.outcome)
    }

    @Test fun kernelRecoveredFirstThenRoundOneReconciles() {
        var e = armed(engine())
        var k = kernel().also { it.recover(Gate()) }
        val (show, p) = enterAndHandOff(e, k)
        k.onSurface(p.token, SurfaceReport.Posted)
        k = kernel(); k.recover(Gate()) // no round-1 engine yet: nothing mirrored
        e = engine(); e.recover()
        val settled = KernelMirror.reconcile(e, k::find)
        assertEquals(show.event.eventId to EventResult.ABANDONED, settled.single().let { it.eventId to it.result })
    }

    @Test fun aKernelThatLostTheEventClosesTheTwinAsLost() {
        val e = armed(engine())
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        e.delegate(show.event.eventId)
        val settled = KernelMirror.reconcile(e) { null }
        assertEquals(EventResult.ABANDONED to "kernel_lost", settled.single().let { it.result to it.reason })
    }

    // Defect B
    @Test fun triggerTestWorksWithProactiveChatOffAndEachSwitchCancelsOnlyItsOwnEvents() {
        val onlyTrigger = Switches(triggerTest = true, proactive = false)
        assertTrue(onlyTrigger.arbitrate)
        assertTrue(onlyTrigger.observeForeground)
        assertTrue(onlyTrigger.accepts(Switches.SOURCE_APP_ENTER))
        val k = kernel().also { it.recover(Gate(featureEnabled = onlyTrigger.arbitrate)) }
        val e = armed(engine())
        val (_, p) = enterAndHandOff(e, k)
        assertEquals("app-enter is shown while proactive chat is off", Switches.SOURCE_APP_ENTER, k.find(p.eventId)!!.request.source)
        // A scheduler event queued behind it, then proactive turned off: only that one is cancelled.
        k.submit(EventRequest("sched-0001", Switches.SOURCE_SCHEDULER, "random_chat", "scheduler:random", createdAt = now, needsAgent = true))
        val off = Switches(triggerTest = true, proactive = false).disabledSources()
        val step = off.fold(Step()) { acc, (src, why) -> acc + k.cancelSource(src, why) }.mirror(e)
        assertEquals(listOf("sched-0001"), step.closed.map { it.eventId })
        assertEquals(Outcome.CANCELLED to "proactive_off", step.closed.single().let { it.outcome to it.reason })
        assertEquals(p.token, k.active()!!.token)
        // Trigger test off: its page is withdrawn and the twin closes, consistently.
        val t = k.cancelSource(Switches.SOURCE_APP_ENTER, "trigger_test_off").mirror(e)
        assertEquals(p.token, t.commands.filterIsInstance<Command.Withdraw>().single().token)
        assertEquals(EventResult.SUPPRESSED to "kernel_cancelled:trigger_test_off", e.events().first().let { it.result to it.reason })
    }

    @Test fun changingOrDisablingTheTriggerLeavesDelegatedTwinsToTheKernel() {
        val e = armed(engine())
        val k = kernel().also { it.recover(Gate(dshTaskActive = true)) }
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        KernelMirror.onSubmit(e, show.event.eventId, k.submit(request(show)).first)
        e.setConfig(LoopConfig(enabled = false, targetPackage = target))
        assertEquals("not closed by LoopEngine itself", EventState.TRIGGERED, e.stateOf(show.event.eventId))
        // ProactiveRuntime.applySwitches: trigger test off → the kernel cancels, the twin mirrors it.
        k.cancelSource(Switches.SOURCE_APP_ENTER, "trigger_test_off").mirror(e)
        val twin = e.events().first { it.eventId == show.event.eventId }
        val kernelSide = k.find(show.event.eventId)!!
        assertEquals(Outcome.CANCELLED, kernelSide.outcome)
        assertEquals(KernelMirror.resultOf(kernelSide.outcome!!), twin.result)
        assertEquals("kernel_cancelled:trigger_test_off", twin.reason)
    }

    @Test fun switchesTable() {
        assertEquals(false, Switches(false, false).observeForeground)
        assertEquals(true, Switches(false, true).observeForeground)
        assertEquals(false, Switches(false, true).accepts(Switches.SOURCE_APP_ENTER))
        assertEquals(true, Switches(false, true).accepts(Switches.SOURCE_SCHEDULER))
        assertEquals(listOf(Switches.SOURCE_APP_ENTER, Switches.SOURCE_SCHEDULER), Switches(false, false).disabledSources().map { it.first })
    }
}

/** Review round 3, defect C: what the proactive gate treats as a protected foreground. */
class ProtectedForegroundTest {
    @Test fun settingsAndSecurityPagesAreProtectedByPackageAlone() {
        for (p in listOf("com.android.settings", "com.miui.securitycenter", "com.android.permissioncontroller", "com.google.android.packageinstaller")) {
            assertTrue(p, ProtectedForeground.isProtected(p))
        }
        for (p in listOf(null, "com.example.test", "com.android.systemui", "io.github.ccch1mneyyy.dsh_app")) {
            assertEquals("$p", false, ProtectedForeground.isProtected(p))
        }
    }
}
