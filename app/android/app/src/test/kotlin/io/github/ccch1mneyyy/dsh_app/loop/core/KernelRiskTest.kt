package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The three cross-agent risks the round-2 hand-off named, at the layer where they can be proven
 * without a device:
 *
 * 1. an agent request that is merely *waiting for its port* must not be judged a failure;
 * 2. both state stores must survive a damaged file and never drop the last good state;
 * 3. "the page was created / resumed" must not be recorded as "the person saw it".
 *
 * (3)'s platform half lives in ProactivePageActivity, which cannot run on the JVM; what the kernel
 * promises is asserted here: only Shown counts as seen, Posted does not, and a notification can
 * never claim it.
 */
class KernelRiskTest {
    private var now = 1_793_340_000_000L
    private var seq = 0

    private val idleGate = Gate(featureEnabled = true, screenOn = true, unlocked = true, notificationsAllowed = true)

    private fun request(kind: String = "app_enter", dedupe: String = "k", needsAgent: Boolean = true, fallback: String? = null) =
        EventRequest(
            eventId = "evt-${++seq}",
            source = "SCHEDULER",
            kind = kind,
            dedupeKey = dedupe,
            createdAt = now,
            needsAgent = needsAgent,
            fallbackText = fallback,
        )

    // ---- 1. waiting for the port is not a failure ---------------------------------------------

    @Test fun anAgentRequestInsideItsDeadlineIsResumedAfterRestart() {
        // A restart inside the turn's deadline must produce ResumeAgent — the adapter replays it once
        // Dart connects — and never a failure just because no port existed at that instant.
        val store = MemorySnapshotStore()
        val coordinator = Coordinator(store, CoordinatorConfig(), { now }, { "r1" })
        coordinator.recover(idleGate)
        val (_, step) = coordinator.submit(request())
        val req = step.commands.filterIsInstance<Command.RequestAgent>().single()
        coordinator.onAgent(req.requestId, AgentReply.Accepted)

        now += 10_000
        val after = Coordinator(store, CoordinatorConfig(), { now }, { "r2" })
        val recovered = after.recover(idleGate)
        val resume = recovered.commands.filterIsInstance<Command.ResumeAgent>().singleOrNull()
        assertNotNull("a live turn must be resumed, not failed", resume)
        assertEquals(req.requestId, resume!!.requestId)
        // The same request id: the plugin answers a replay from its record and never sends twice.
        assertEquals(req.requestId, after.active()?.agentRequestId)
        assertEquals(Stage.AWAITING_AGENT, after.active()?.stage)
    }

    @Test fun aRestartPastTheDeadlineFailsTheTurnInsteadOfHangingForever() {
        val store = MemorySnapshotStore()
        val coordinator = Coordinator(store, CoordinatorConfig(), { now }, { "r1" })
        coordinator.recover(idleGate)
        val (_, step) = coordinator.submit(request(fallback = "hi"))
        val req = step.commands.filterIsInstance<Command.RequestAgent>().single()
        coordinator.onAgent(req.requestId, AgentReply.Accepted)

        now += 120_000 // well past the agent deadline
        val after = Coordinator(store, CoordinatorConfig(), { now }, { "r2" })
        val recovered = after.recover(idleGate)
        assertTrue("no resume past the deadline", recovered.commands.filterIsInstance<Command.ResumeAgent>().isEmpty())
        // With a fallback it moves on to a local message; without one it fails. Either way it is not stuck.
        assertTrue(after.active()?.stage != Stage.AWAITING_AGENT)
    }

    // ---- 2. both stores survive a damaged file ------------------------------------------------

    @Test fun theKernelStoreFallsBackToItsBackupAndSaysSo() {
        val dir = Files.createTempDirectory("kernel").toFile()
        val file = File(dir, "coordinator.json")
        val store = AtomicJsonFile(file)
        store.write(mapOf("version" to 1, "step" to 1))
        store.write(mapOf("version" to 1, "step" to 2))
        assertTrue("backup exists", File(dir, "coordinator.json.bak").exists())

        // Power cut mid-write.
        file.writeText("{\"version\": 1, \"step\"")
        val read = store.read()
        assertTrue("damaged file falls back", read is SnapshotStore.Read.Loaded)
        val loaded = read as SnapshotStore.Read.Loaded
        assertEquals("coordinator.json.bak", loaded.recoveredFrom)
        // The backup holds the generation before the damaged one, by construction.
        assertEquals(1L, (loaded.map["step"] as Number).toLong())
        assertTrue("damaged file quarantined", File(dir, "coordinator.json.bad").exists())
        dir.deleteRecursively()
    }

    @Test fun theKernelStoreNeverLeavesTheFileMissing() {
        val dir = Files.createTempDirectory("kernel").toFile()
        val file = File(dir, "scheduler.json")
        val store = AtomicJsonFile(file)
        repeat(5) { store.write(mapOf("version" to 1, "n" to it)) }
        assertTrue("state exists", file.exists())
        assertTrue("backup exists", File(dir, "scheduler.json.bak").exists())
        assertTrue("temp cleaned up", !File(dir, "scheduler.json.tmp").exists())
        val loaded = store.read() as SnapshotStore.Read.Loaded
        assertNull("a healthy file is not a recovery", loaded.recoveredFrom)
        assertEquals(4L, (loaded.map["n"] as Number).toLong())
        dir.deleteRecursively()
    }

    @Test fun anEmptyKernelFileIsUnreadableNotEmpty() {
        val dir = Files.createTempDirectory("kernel").toFile()
        val file = File(dir, "coordinator.json").apply { writeText("") }
        assertTrue(AtomicJsonFile(file).read() is SnapshotStore.Read.Unreadable)
        dir.deleteRecursively()
    }

    // ---- 3. only a real Shown counts as seen --------------------------------------------------

    @Test fun postedIsNotShownAndADismissedNeverShownIsNotIgnoredSeen() {
        val coordinator = Coordinator(MemorySnapshotStore(), CoordinatorConfig(), { now }, { "e1" })
        coordinator.recover(idleGate)
        val (_, step) = coordinator.submit(request(needsAgent = false, fallback = "hi"))
        val present = step.commands.filterIsInstance<Command.Present>().single()

        // Handed to the system: delivered, but nobody has seen it.
        coordinator.onSurface(present.token, SurfaceReport.Posted)
        assertNull("delivery is not visibility", coordinator.active()?.shownAt)
        if (present.surface == Surface.NOTIFICATION) {
            assertEquals(Stage.DELIVERED, coordinator.active()?.stage)
        } else {
            // An activity that was posted but never confirmed on screen stays PRESENTING: this is
            // exactly the state the 3-second watchdog uses to fall back to a notification.
            assertEquals(Stage.PRESENTING, coordinator.active()?.stage)
        }

        // Swiped away without ever being shown: not "seen", so the ignored backoff must not count it.
        val closed = coordinator.onSurface(present.token, SurfaceReport.Dismissed).closed.single()
        assertTrue("a never-shown event is not IGNORED_SEEN", closed.engagement != Engagement.IGNORED_SEEN)
    }

    @Test fun aShownReportRecordsTheTimestamp() {
        val coordinator = Coordinator(MemorySnapshotStore(), CoordinatorConfig(), { now }, { "e1" })
        coordinator.recover(idleGate)
        val (_, step) = coordinator.submit(request(needsAgent = false, fallback = "hi"))
        val present = step.commands.filterIsInstance<Command.Present>().single()
        coordinator.onSurface(present.token, SurfaceReport.Posted)
        now += 1_000
        coordinator.onSurface(present.token, SurfaceReport.Shown)
        assertEquals(now, coordinator.active()?.shownAt)
        assertEquals(Stage.SHOWN, coordinator.active()?.stage)
    }

    @Test fun aStaleTokenCannotReportShownForANewerEvent() {
        // Every id the coordinator mints (event ids, request ids, presentation tokens) must be
        // distinct, otherwise a stale page could match a newer token. Give it a real sequence.
        var ids = 0
        val coordinator = Coordinator(MemorySnapshotStore(), CoordinatorConfig(), { now }, { "id-${++ids}" })
        coordinator.recover(idleGate)
        val (_, step) = coordinator.submit(request(needsAgent = false, fallback = "hi"))
        val old = step.commands.filterIsInstance<Command.Present>().single().token
        coordinator.cancel(coordinator.active()!!.eventId, "test")

        val (_, step2) = coordinator.submit(request(dedupe = "k2", needsAgent = false, fallback = "hi2"))
        val fresh = step2.commands.filterIsInstance<Command.Present>().single()
        assertTrue("a new presentation gets a new token", old != fresh.token)

        // The stale page reporting Shown must not touch the new event.
        coordinator.onSurface(old, SurfaceReport.Shown)
        assertNull("a stale page cannot claim the new event was shown", coordinator.active()?.shownAt)
    }
}
