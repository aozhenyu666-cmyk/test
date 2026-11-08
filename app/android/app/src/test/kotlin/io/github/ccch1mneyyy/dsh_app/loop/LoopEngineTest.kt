package io.github.ccch1mneyyy.dsh_app.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The first closed loop on the JVM: these are the scenario checks from the task
 * (20 switches, foreground/background, person leaves, reconnect, process restart, bad receipts),
 * driven with verified foreground readings the way LoopRuntime feeds them.
 */
class LoopEngineTest {
    private var now = 1_700_000_000_000L
    private var seq = 0
    private val self = "io.github.ccch1mneyyy.dsh_app"
    private val target = "com.example.test"
    private val other = "com.example.other"
    private val launcher = "com.android.launcher3"
    private val ime = "com.sohu.inputmethod.sogou"
    private val idle = Environment()

    private fun filter() = PackageFilter(self) { setOf(ime) }
    private fun engine(store: LoopStore = MemoryLoopStore()) = LoopEngine(store, filter(), { now }, { "id-${++seq}" })
    private fun LoopEngine.armed(cooldownMs: Long = 0) = apply {
        recover()
        setConfig(LoopConfig(enabled = true, targetPackage = target, cooldownMs = cooldownMs, timeoutMs = 30_000))
    }

    private fun LoopEngine.see(pkg: String?, env: Environment = idle) = onForeground(pkg, env)

    /** Page shown and the person taps 返回. */
    private fun LoopEngine.answerReturn(show: LoopEngine.Outcome.Show): LoopEvent {
        assertTrue(shown(show.event.eventId))
        return answer(show.event.eventId, EventResult.RETURNED)!!
    }

    @Test fun twentySwitchesTriggerTwentyTimesWithOneReceiptEach() {
        val e = engine().armed()
        repeat(20) { i ->
            now += 5_000
            val out = e.see(target)
            assertTrue("switch $i: $out", out is LoopEngine.Outcome.Show)
            // More events from the same switch and the page itself: no second trigger.
            assertEquals(LoopEngine.Outcome.Ignored("same_visit"), e.see(target))
            assertEquals(LoopEngine.Outcome.Ignored("self"), e.see(self))
            e.answerReturn(out as LoopEngine.Outcome.Show)
            assertEquals(LoopEngine.Outcome.Ignored("same_visit"), e.see(target))
            e.see(if (i % 2 == 0) other else launcher)
        }
        val receipts = e.events(100)
        assertEquals(20, receipts.size)
        assertTrue(receipts.all { it.state == EventState.CLOSED && it.result == EventResult.RETURNED })
        assertEquals(20, receipts.map { it.visitId }.toSet().size)
        assertEquals(20, receipts.map { it.eventId }.toSet().size)
    }

    @Test fun keyboardsShadesAndPermissionDialogsNeverStartANewVisit() {
        val e = engine().armed()
        val first = e.see(target) as LoopEngine.Outcome.Show
        e.answerReturn(first)
        for (p in listOf(ime, "com.android.systemui", "com.android.permissioncontroller", "com.miui.securitycenter", self, "android")) {
            assertTrue(p, e.see(p) is LoopEngine.Outcome.Ignored)
            assertTrue(p, e.see(target) is LoopEngine.Outcome.Ignored)
        }
        assertEquals(1, e.events().size)
    }

    @Test fun backgroundAndBackWithinTheCooldownIsSuppressedThenTriggersAgain() {
        val e = engine().armed(cooldownMs = 60_000)
        e.answerReturn(e.see(target) as LoopEngine.Outcome.Show)
        e.see(launcher)
        now += 10_000
        val again = e.see(target) as LoopEngine.Outcome.Suppressed
        assertEquals("cooldown", again.event.reason)
        assertEquals(EventResult.SUPPRESSED, again.event.result)
        // The suppressed entry still opened the visit: staying does not re-roll.
        now += 120_000
        assertTrue(e.see(target) is LoopEngine.Outcome.Ignored)
        e.see(other)
        assertTrue(e.see(target) is LoopEngine.Outcome.Show)
    }

    @Test fun leavingThePageBackKeyAndTimeoutAreReceiptsAndTheFirstAnswerWins() {
        val e = engine().armed()
        val a = e.see(target) as LoopEngine.Outcome.Show
        assertTrue(e.shown(a.event.eventId))
        assertEquals(EventResult.DISMISSED, e.answer(a.event.eventId, EventResult.DISMISSED)!!.result)
        assertNull("double answer", e.answer(a.event.eventId, EventResult.RETURNED))
        assertFalse("stale page", e.shown(a.event.eventId))

        e.see(other); val b = e.see(target) as LoopEngine.Outcome.Show
        e.shown(b.event.eventId)
        e.answer(b.event.eventId, EventResult.BACK)
        e.see(other); val c = e.see(target) as LoopEngine.Outcome.Show
        e.shown(c.event.eventId)
        e.answer(c.event.eventId, EventResult.TIMEOUT)
        assertEquals(listOf(EventResult.TIMEOUT, EventResult.BACK, EventResult.DISMISSED), e.events().map { it.result })
    }

    @Test fun anOpenPageBlocksAnotherTrigger() {
        val e = engine().armed()
        val a = e.see(target) as LoopEngine.Outcome.Show
        e.see(other)
        val b = e.see(target) as LoopEngine.Outcome.Suppressed
        assertEquals("interaction_open", b.event.reason)
        e.answerReturn(a)
    }

    @Test fun serviceReconnectWhileStillInTheAppDoesNotTriggerAgain() {
        val e = engine().armed()
        e.answerReturn(e.see(target) as LoopEngine.Outcome.Show)
        // Service unbinds (no readings), then reconnects: LoopRuntime.attach reads once.
        assertEquals(LoopEngine.Outcome.Ignored("unknown"), e.see(null))
        assertEquals(LoopEngine.Outcome.Ignored("same_visit"), e.see(target))
        assertEquals(1, e.events().size)
    }

    @Test fun processRestartClosesOpenEventsAsAbandonedAndKeepsTheVisit() {
        val store = MemoryLoopStore()
        val before = engine(store).armed(cooldownMs = 60_000)
        val shown = before.see(target) as LoopEngine.Outcome.Show
        before.shown(shown.event.eventId)
        val triggeredOnly = run { before.answerReturn(shown); before.see(other); now += 61_000; before.see(target) as LoopEngine.Outcome.Show }

        // Process dies here. A new process loads the same file.
        now += 5_000
        val after = engine(store)
        val closed = after.recover()
        assertEquals(listOf(triggeredOnly.event.eventId), closed.map { it.eventId })
        val receipt = after.events().first()
        assertEquals(EventResult.ABANDONED, receipt.result)
        assertEquals("process_restart", receipt.reason)
        assertNull("the page of the dead process cannot answer", after.answer(receipt.eventId, EventResult.RETURNED))
        assertFalse(after.shown(receipt.eventId))
        // Config, visit and cooldown survived.
        assertEquals(target, after.config().targetPackage)
        assertTrue(after.see(target) is LoopEngine.Outcome.Ignored)
        after.see(other)
        assertEquals("cooldown", (after.see(target) as LoopEngine.Outcome.Suppressed).event.reason)
    }

    @Test fun unreadableStateIsReportedNotTreatedAsEmpty() {
        val dir = Files.createTempDirectory("loop").toFile()
        val file = File(dir, "state.json").apply { writeText("{\"config\": {broken") }
        val e = engine(FileLoopStore(file))
        val closed = e.recover()
        assertEquals(1, closed.size)
        assertEquals(EventResult.ERROR, closed[0].result)
        assertTrue(closed[0].error!!.startsWith("state_unreadable"))
        assertTrue(File(dir, "state.json.bad").exists())
        assertFalse("starts switched off", e.config().enabled)
        dir.deleteRecursively()
    }

    @Test fun storageFailureIsSurfacedAndTheLoopKeepsWorking() {
        val store = MemoryLoopStore()
        val e = engine(store).armed()
        store.failWrites = true
        val out = e.see(target) as LoopEngine.Outcome.Show
        assertNotNull(e.storageError)
        store.failWrites = false
        e.answerReturn(out)
        assertNull(e.storageError)
        assertEquals(EventResult.RETURNED, store.saved!!.events.single().result)
    }

    @Test fun fileStoreRoundTripsEverything() {
        val dir = Files.createTempDirectory("loop").toFile()
        val store = FileLoopStore(File(File(dir, "loop"), "state.json"))
        val e = engine(store).armed(cooldownMs = 7_000)
        e.answerReturn(e.see(target) as LoopEngine.Outcome.Show)
        val loaded = (store.read() as LoopStore.Read.Loaded).snapshot
        assertEquals(e.config(), loaded.config)
        assertEquals(e.trackerState(), loaded.tracker)
        assertEquals(now, loaded.lastTriggerAt)
        assertEquals(e.events(), loaded.events.reversed())
        assertFalse(File(File(dir, "loop"), "state.json.tmp").exists())
        dir.deleteRecursively()
    }

    @Test fun dshBusyLockedAndScreenOffSuppress() {
        val e = engine().armed()
        assertEquals("dsh_busy", (e.see(target, Environment(dshBusy = true)) as LoopEngine.Outcome.Suppressed).event.reason)
        e.see(other)
        assertEquals("locked", (e.see(target, Environment(keyguardLocked = true)) as LoopEngine.Outcome.Suppressed).event.reason)
        e.see(other)
        assertEquals("screen_off", (e.see(target, Environment(screenInteractive = false)) as LoopEngine.Outcome.Suppressed).event.reason)
    }

    @Test fun clockGoingBackRestartsTheCooldownInsteadOfBlockingForever() {
        val e = engine().armed(cooldownMs = 60_000)
        e.answerReturn(e.see(target) as LoopEngine.Outcome.Show)
        e.see(other)
        now -= 3_600_000
        assertEquals("cooldown", (e.see(target) as LoopEngine.Outcome.Suppressed).event.reason)
        e.see(other)
        now += 61_000
        assertTrue(e.see(target) is LoopEngine.Outcome.Show)
    }

    @Test fun disabledOrNoTargetNeverTriggers() {
        val e = engine()
        e.recover()
        assertTrue(e.see(target) is LoopEngine.Outcome.Ignored)
        e.setConfig(LoopConfig(enabled = true, targetPackage = null))
        assertTrue(e.see(target) is LoopEngine.Outcome.Ignored)
        e.setConfig(LoopConfig(enabled = false, targetPackage = target))
        assertTrue(e.see(target) is LoopEngine.Outcome.Ignored)
        assertTrue(e.events().isEmpty())
    }

    @Test fun changingTheTargetClosesTheOpenPageAndResetsTheVisit() {
        val e = engine().armed()
        val a = e.see(target) as LoopEngine.Outcome.Show
        e.setConfig(e.config().copy(targetPackage = other))
        assertEquals(EventResult.DISMISSED, e.events().first().result)
        assertEquals("config_changed", e.events().first().reason)
        assertNull(e.answer(a.event.eventId, EventResult.RETURNED))
        assertTrue(e.see(other) is LoopEngine.Outcome.Show)
    }

    @Test fun historyIsCappedButNeverDropsAnOpenEvent() {
        val e = engine().armed()
        val open = e.see(target) as LoopEngine.Outcome.Show
        repeat(LoopStore.MAX_EVENTS + 20) { e.see(other); e.see(target) }
        val all = e.events(LoopStore.MAX_EVENTS)
        assertEquals(LoopStore.MAX_EVENTS, all.size)
        assertTrue(all.any { it.eventId == open.event.eventId && it.open })
    }

    @Test fun configIsNormalized() {
        val c = LoopConfig(enabled = true, targetPackage = "  ", cooldownMs = -5, timeoutMs = 1).normalized()
        assertNull(c.targetPackage)
        assertEquals(0, c.cooldownMs)
        assertEquals(5_000, c.timeoutMs)
    }

    @Test fun eventMapsRoundTrip() {
        val ev = LoopEvent("x", EventSource.A11Y_WINDOW, 5, EventState.CLOSED, EventResult.TIMEOUT, "boom", "r", target, "v", "activity", 9)
        assertEquals(ev, LoopEvent.fromMap(ev.toMap()))
        assertNull(LoopEvent.fromMap(mapOf("eventId" to "x")))
    }
}
