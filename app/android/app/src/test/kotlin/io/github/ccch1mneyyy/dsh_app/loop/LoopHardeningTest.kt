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
 * The three defects that made the prototype unsafe to trust, each pinned by a test:
 *
 * 1. **SHOWN too early** — `stateOf` is read-only, so a page that was never drawn no longer gets a
 *    SHOWN receipt, and the engine only accepts SHOWN from a page that proves it is visible.
 * 2. **Silent accessibility failures** — [LoopDiagnostics] counts, streaks and keeps the last faults
 *    instead of swallowing them in `Log.w`.
 * 3. **Unreliable state-file replace** — [FileLoopStore] keeps a backup, tries the backup on read,
 *    quarantines the damaged file, and never leaves a window where both files are gone.
 *
 * Plus the notification surface's receipt contract (T3) and the AgentBridge fallback rule (T7).
 */
class LoopHardeningTest {
    private var now = 1_700_000_000_000L
    private var seq = 0
    private val self = "io.github.ccch1mneyyy.dsh_app"
    private val target = "com.example.test"
    private val other = "com.example.other"

    private fun filter() = PackageFilter(self)
    private fun engine(store: LoopStore = MemoryLoopStore()) = LoopEngine(store, filter(), { now }, { "id-${++seq}" })
    private fun LoopEngine.armed(cooldownMs: Long = 0) = apply {
        recover()
        setConfig(LoopConfig(enabled = true, targetPackage = target, cooldownMs = cooldownMs, timeoutMs = 30_000))
    }

    // ---- defect 1: SHOWN only when the page really appeared --------------------------------

    @Test fun askingForTheStateDoesNotMarkThePageShown() {
        val e = engine().armed()
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val id = show.event.eventId
        // What InteractionActivity does on a start it must judge before building the view.
        assertEquals(EventState.TRIGGERED, e.stateOf(id))
        assertEquals(EventState.TRIGGERED, e.stateOf(id))
        // A page that never became visible leaves the event TRIGGERED, so the watchdog can still
        // close it honestly as UNAVAILABLE(not_shown).
        assertEquals(EventState.TRIGGERED, e.stateOf(id))
        assertEquals(EventResult.UNAVAILABLE, e.answer(id, EventResult.UNAVAILABLE, reason = "not_shown")!!.result)
        assertEquals("not_shown", e.events().first().reason)
        assertEquals(EventState.CLOSED, e.stateOf(id))
    }

    @Test fun onlyAVisiblePageMovesTheEventToShown() {
        val e = engine().armed()
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val id = show.event.eventId
        assertTrue(e.shown(id))
        assertEquals(EventState.SHOWN, e.stateOf(id))
        assertEquals("activity", e.events().first().surface)
    }

    @Test fun shownAfterTheEventWasClosedIsRefused() {
        val e = engine().armed()
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val id = show.event.eventId
        // The watchdog (or a config change) closed it before the page proved itself visible.
        e.answer(id, EventResult.UNAVAILABLE, reason = "not_shown")
        assertFalse("a late page must not resurrect a closed event", e.shown(id))
        assertNull("the person's answer can no longer be written", e.answer(id, EventResult.RETURNED))
        assertEquals(1, e.events().size)
        assertEquals(EventResult.UNAVAILABLE, e.events().first().result)
    }

    // ---- defect 2: accessibility failures are diagnosable -----------------------------------

    @Test fun diagnosticsCountStreakAndKeepTheLatestFaults() {
        var t = 1_000L
        val d = LoopDiagnostics(limit = 3) { t }
        d.fault(LoopDiagnostics.KIND_READ_FOREGROUND, IllegalStateException("windows threw"))
        t += 10
        d.fault(LoopDiagnostics.KIND_READ_FOREGROUND, null, "windows null")
        assertEquals(2, d.count(LoopDiagnostics.KIND_READ_FOREGROUND))
        assertEquals(2, d.streak(LoopDiagnostics.KIND_READ_FOREGROUND))
        assertEquals(0, d.count(LoopDiagnostics.KIND_VERIFY))
        // A success resets the streak but not the history.
        d.success(LoopDiagnostics.KIND_READ_FOREGROUND)
        assertEquals(0, d.streak(LoopDiagnostics.KIND_READ_FOREGROUND))
        assertEquals(2, d.count(LoopDiagnostics.KIND_READ_FOREGROUND))
        assertEquals(1_010L, d.lastSuccessAt(LoopDiagnostics.KIND_READ_FOREGROUND))
        // The ring keeps the newest `limit` faults, newest first.
        d.fault(LoopDiagnostics.KIND_VERIFY, RuntimeException("a"))
        d.fault(LoopDiagnostics.KIND_VERIFY, RuntimeException("b"))
        val recent = d.recent()
        assertEquals(3, recent.size)
        assertEquals("RuntimeException: b", recent[0].detail)
        assertTrue(recent.all { it.detail.isNotBlank() })
        val m = d.toMap()
        assertNotNull(m["faults"])
        assertEquals(1_010L, m["lastReadAt"])
        assertNotNull(m["streaks"])
        d.clear()
        assertEquals(0, d.count(LoopDiagnostics.KIND_VERIFY))
        assertTrue(d.recent().isEmpty())
    }

    @Test fun diagnosticsDistinguishNeverRanFromNoFailures() {
        val d = LoopDiagnostics()
        val m = d.toMap()
        assertNull(m["lastEventAt"])
        assertEquals(0, d.count(LoopDiagnostics.KIND_EVENT))
        d.success(LoopDiagnostics.KIND_EVENT)
        assertNotNull(d.toMap()["lastEventAt"])
    }

    // ---- defect 3: the state file is replaced reliably ---------------------------------------

    @Test fun aDamagedStateFileFallsBackToTheBackupAndSaysSo() {
        val dir = Files.createTempDirectory("loop").toFile()
        val file = File(File(dir, "loop"), "state.json")
        val store = FileLoopStore(file)
        val e = engine(store).armed(cooldownMs = 7_000)
        e.onForeground(target, Environment())
        // A second write promotes the first good file to the backup.
        e.setConfig(e.config().copy(cooldownMs = 9_000))
        assertTrue(File(file.parentFile, "state.json.bak").exists())

        // Power cut in the middle of a write: half a JSON document.
        file.writeText("{\"config\": {\"enabled\": tru")
        val restarted = engine(store)
        val closed = restarted.recover()
        // The damaged file is kept for diagnosis, the backup is loaded, and the recovery is honest.
        assertTrue("damaged file quarantined", File(file.parentFile, "state.json.bad").exists())
        // The backup holds the generation *before* the current one, by construction: that is the
        // whole point — the newest good write is 9_000, the one before it 7_000.
        assertEquals(7_000L, restarted.config().cooldownMs)
        assertTrue(closed.any { it.error?.startsWith("state_recovered_from_backup") == true })
        assertEquals("state.json.bak", restarted.recoveredFrom)
        assertEquals(target, restarted.config().targetPackage)
        dir.deleteRecursively()
    }

    @Test fun aWriteNeverLeavesTheFileMissing() {
        val dir = Files.createTempDirectory("loop").toFile()
        val file = File(dir, "state.json")
        val store = FileLoopStore(file)
        val e = engine(store).armed()
        repeat(5) { i -> e.setConfig(e.config().copy(cooldownMs = 1_000L * (i + 1))) }
        assertTrue("state.json exists", file.exists())
        assertTrue("backup exists", File(dir, "state.json.bak").exists())
        assertFalse("temp file cleaned up", File(dir, "state.json.tmp").exists())
        // Every intermediate state parsed: the last one wins.
        val loaded = store.read() as LoopStore.Read.Loaded
        assertEquals(5_000L, loaded.snapshot.config.cooldownMs)
        assertNull(loaded.recoveredFrom)
        dir.deleteRecursively()
    }

    @Test fun anEmptyFileIsUnreadableNotEmpty() {
        val dir = Files.createTempDirectory("loop").toFile()
        val file = File(dir, "state.json").apply { writeText("") }
        val e = engine(FileLoopStore(file))
        val closed = e.recover()
        assertEquals(1, closed.size)
        assertEquals(EventResult.ERROR, closed[0].result)
        assertTrue(closed[0].error!!.startsWith("state_unreadable"))
        dir.deleteRecursively()
    }

    @Test fun theDailyCapUsageSurvivesARestart() {
        val store = MemoryLoopStore()
        val first = engine(store).armed()
        first.onForeground(target, Environment(dayKey = "2026-10-31", minuteOfDay = 600))
        assertEquals("2026-10-31" to 1, first.dailyUsage())
        // Process restart on the same day: the count is not handed out again.
        val second = engine(store)
        second.recover()
        second.setConfig(second.config().copy(dailyCap = 1))
        assertEquals("2026-10-31" to 1, second.dailyUsage())
        second.onForeground(other, Environment(dayKey = "2026-10-31", minuteOfDay = 600))
        val again = second.onForeground(target, Environment(dayKey = "2026-10-31", minuteOfDay = 600))
        assertEquals("daily_cap", (again as LoopEngine.Outcome.Suppressed).event.reason)
        // The next day starts with a fresh count.
        second.onForeground(other, Environment(dayKey = "2026-11-01", minuteOfDay = 600))
        val tomorrow = second.onForeground(target, Environment(dayKey = "2026-11-01", minuteOfDay = 600))
        assertTrue(tomorrow is LoopEngine.Outcome.Show)
        assertEquals("2026-11-01" to 1, second.dailyUsage())
    }

    // ---- T3: the notification surface's receipt contract ------------------------------------

    @Test fun theNotificationSurfaceRecordsItselfAndKeepsTheEventOpen() {
        val e = engine().armed()
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val id = show.event.eventId
        // The page was swallowed: InteractionHub moves the event to the notification surface.
        val marked = e.markSurface(id, InteractionHub.SURFACE_NOTIFICATION)!!
        assertEquals("notification", marked.surface)
        assertTrue("still answerable", marked.open)
        assertEquals(EventState.TRIGGERED, e.stateOf(id))
        // The notification's 返回 button answers it, and the receipt names the surface.
        val closed = e.answer(id, EventResult.RETURNED, surface = InteractionHub.SURFACE_NOTIFICATION)!!
        assertEquals(EventResult.RETURNED, closed.result)
        assertEquals("notification", e.events().first().surface)
        assertEquals("notification", closed.surface)
        // A second answer (swipe after tapping, expiry after tapping) changes nothing.
        assertNull(e.answer(id, EventResult.DISMISSED, surface = InteractionHub.SURFACE_NOTIFICATION))
        assertNull(e.answer(id, EventResult.TIMEOUT, surface = InteractionHub.SURFACE_NOTIFICATION))
        assertEquals(1, e.events().size)
        assertEquals(EventResult.RETURNED, e.events().first().result)
    }

    @Test fun theNotificationSurfaceCannotAttachToAClosedEvent() {
        val e = engine().armed()
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val id = show.event.eventId
        e.answer(id, EventResult.UNAVAILABLE, reason = "not_shown")
        assertNull("a stale notification is dropped", e.markSurface(id, InteractionHub.SURFACE_NOTIFICATION))
        assertEquals(EventState.CLOSED, e.stateOf(id))
        assertNull(e.surfaceOf(id)?.takeIf { it == InteractionHub.SURFACE_NOTIFICATION })
    }

    @Test fun theNotificationSurfaceReceiptReadsThroughTheWholeSequence() {
        // page did not appear → notification → 返回: the state sequence T3 asks for.
        val e = engine().armed()
        val show = e.onForeground(target, Environment()) as LoopEngine.Outcome.Show
        val id = show.event.eventId
        val states = mutableListOf<String>()
        states += e.stateOf(id).toString()
        e.markSurface(id, InteractionHub.SURFACE_NOTIFICATION)
        states += e.stateOf(id).toString()
        e.answer(id, EventResult.RETURNED, surface = InteractionHub.SURFACE_NOTIFICATION)
        states += e.stateOf(id).toString()
        assertEquals(listOf("TRIGGERED", "TRIGGERED", "CLOSED"), states)
        val receipt = e.events().single()
        assertEquals(EventResult.RETURNED, receipt.result)
        assertEquals("notification", receipt.surface)
        assertNull(receipt.reason)
    }

    // ---- T7: the AgentBridge is a local template that never throws ---------------------------

    @Test fun theLocalTemplateBridgeNamesTheApp() {
        val bridge = LocalTemplateBridge(labels = { p -> if (p == target) "测试应用" else null })
        val event = LoopEvent("e1", EventSource.A11Y_WINDOW, 5, EventState.TRIGGERED, packageName = target, visitId = "v1")
        val d = DeliveryGuard.run(bridge, event, 1_000)
        assertEquals(AgentBridge.Delivery.Reply("测试应用"), d)
        // Unknown package: the package name itself, still a Reply.
        val unlabeled = event.copy(packageName = other)
        assertEquals(AgentBridge.Delivery.Reply(other), DeliveryGuard.run(bridge, unlabeled, 1_000))
        // No package at all, no bridge, no time: all Fallback, never an exception.
        assertEquals(AgentBridge.Delivery.Fallback("no_package"), DeliveryGuard.run(bridge, event.copy(packageName = null), 1_000))
        assertEquals(AgentBridge.Delivery.Fallback("no_bridge"), DeliveryGuard.run(null, event, 1_000))
        assertEquals(AgentBridge.Delivery.Fallback("no_time"), DeliveryGuard.run(bridge, event, 0))
    }

    @Test fun aThrowingBridgeAndABlankAnswerBothFallBack() {
        val event = LoopEvent("e1", EventSource.A11Y_WINDOW, 5, EventState.TRIGGERED, packageName = target)
        val throwing = object : AgentBridge {
            override fun deliver(event: LoopEvent, context: Map<String, Any?>, timeoutMs: Long): AgentBridge.Delivery = error("boom")
        }
        val d = DeliveryGuard.run(throwing, event, 1_000)
        assertTrue("expected a Fallback, got $d", d is AgentBridge.Delivery.Fallback)
        assertTrue((d as AgentBridge.Delivery.Fallback).reason.startsWith("agent_error"))
        // The local bridge reports its own simulated remote failure.
        assertEquals(AgentBridge.Delivery.Fallback("timeout"), DeliveryGuard.run(LocalTemplateBridge(failWith = "timeout"), event, 1_000))
        // A blank reply is not text worth showing.
        val blank = object : AgentBridge {
            override fun deliver(event: LoopEvent, context: Map<String, Any?>, timeoutMs: Long) = AgentBridge.Delivery.Reply("   ")
        }
        assertEquals(AgentBridge.Delivery.Fallback("blank_reply"), DeliveryGuard.run(blank, event, 1_000))
    }

    @Test fun theBridgeOnlySeesScreenFreeFacts() {
        val event = LoopEvent("e1", EventSource.SCHEDULER, 5, EventState.TRIGGERED, packageName = target, visitId = "v1")
        val ctx = AgentContext.of(event)
        assertEquals(setOf("eventId", "source", "packageName", "timestamp", "visitId"), ctx.keys)
        assertFalse("no screen content is ever passed", ctx.keys.any { it.contains("text") || it.contains("node") || it.contains("screen") })
    }
}
