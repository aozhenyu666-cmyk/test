package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 4, task 1: the ACK state machine, proven where it can be proven without a device.
 *
 * The rule the whole design hangs on: **being on screen is not being ignored.** Shown records that
 * the person *had* the page in front of them; only an explicit 「我看到了」 (SurfaceReport.Acked)
 * starts the reply window, and only that window running out unanswered makes the event
 * Engagement.IGNORED_SEEN. Everything that used to count as ignored is now UNCONFIRMED.
 *
 * The platform half (a third button on ProactivePageActivity, and the decor-attached/shown/laid-out/
 * focused proof before Shown) cannot run on the JVM; what the kernel promises about the reports the
 * page sends is asserted here.
 */
class AckStateMachineTest {
    private var now = 1_793_340_000_000L
    private var seq = 0
    private val window = 20 * 60_000L

    private fun coordinator(
        store: SnapshotStore = MemorySnapshotStore(),
        cfg: CoordinatorConfig = CoordinatorConfig(replyWindowMs = window),
    ) = Coordinator(store, cfg, { now }, { "id-" + (++seq) })

    private fun request(id: String = "e" + (++seq)) = EventRequest(
        eventId = id, source = "SCHEDULER", kind = "random_chat", dedupeKey = id,
        createdAt = now, fallbackText = "hi",
    )

    /** Submit, Present, Posted, Shown: the page is provably on screen, nobody has touched it. */
    private fun Coordinator.showPage(): String {
        val token = submit(request()).second.commands.filterIsInstance<Command.Present>().single().token
        onSurface(token, SurfaceReport.Posted)
        onSurface(token, SurfaceReport.Shown)
        assertEquals(Stage.SHOWN, active()!!.stage)
        return token
    }

    // ---- 1. sight is not silence ---------------------------------------------------------------

    @Test fun aPageOnScreenNobodyTouchedIsNotIgnored() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        now += 10 * 60_000L
        val closed = c.onSurface(token, SurfaceReport.Dismissed).closed.single()
        assertEquals(Outcome.DISMISSED, closed.outcome)
        assertEquals("closed by the person, not by silence", Engagement.DISMISSED, closed.engagement)
    }

    @Test fun aPageThatTimedOutUnacknowledgedIsUnconfirmedAndPausesNothing() {
        val c = coordinator().also { it.recover(Gate()) }
        c.showPage()
        now += 61_000L // past interactionTimeoutMs
        val closed = c.tick().closed.single()
        assertEquals(Outcome.TIMEOUT, closed.outcome)
        assertEquals("nobody said they saw it", Engagement.UNCONFIRMED, closed.engagement)
    }

    // ---- 2. the acknowledgement ----------------------------------------------------------------

    @Test fun acknowledgedRecordsTheMomentAndTheKernelsReplyWindow() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        now += 5_000L
        c.onSurface(token, SurfaceReport.Acked)
        val e = c.active()!!
        assertEquals(now, e.acknowledgedAt)
        assertEquals("the kernel stamps the window, the page never does", now + window, e.replyDeadline)
        assertEquals("an ack keeps the page up: the answer may still come", Stage.SHOWN, e.stage)
        assertEquals(Engagement.ACKED, e.engagement)
        assertNull("not an answer", e.respondedAt)
    }

    @Test fun anAckExtendsTheAnswerDeadlineToTheReplyWindow() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        val before = c.active()!!.interactionDeadline
        c.onSurface(token, SurfaceReport.Acked)
        assertTrue("the person asked for time and got it", c.active()!!.interactionDeadline > before)
        assertEquals(now + window, c.active()!!.interactionDeadline)
    }

    @Test fun answeringAfterAnAckIsRespondedNotIgnored() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        c.onSurface(token, SurfaceReport.Acked)
        now += 3 * 60_000L // still inside the window
        val closed = c.onSurface(token, SurfaceReport.Answered("reply")).closed.single()
        assertEquals(Outcome.RESPONDED, closed.outcome)
        assertEquals(Engagement.RESPONDED, closed.engagement)
    }

    @Test fun aRepeatedAckMovesNothing() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        now += 1_000L
        c.onSurface(token, SurfaceReport.Acked)
        val first = c.active()!!
        now += 60_000L
        c.onSurface(token, SurfaceReport.Acked) // double tap
        assertEquals("the first acknowledgement wins", first.acknowledgedAt, c.active()!!.acknowledgedAt)
        assertEquals(first.replyDeadline, c.active()!!.replyDeadline)
    }

    @Test fun anAckFromAStalePageIsRefused() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        val eventId = c.active()!!.eventId
        c.cancel(eventId, "left_app")
        assertNull("a closed event cannot be acknowledged", c.find(eventId)!!.acknowledgedAt)
        assertTrue(c.onSurface(token, SurfaceReport.Acked).closed.isEmpty())
        assertTrue("counted as a stale report", c.staleReports >= 1)
    }

    @Test fun aNotificationCanNeverBeAcknowledged() {
        val c = coordinator().also { it.recover(Gate(a11yConnected = false)) }
        val p = c.submit(request()).second.commands.filterIsInstance<Command.Present>().single()
        assertEquals(Surface.NOTIFICATION, p.surface)
        c.onSurface(p.token, SurfaceReport.Posted)
        c.onSurface(p.token, SurfaceReport.Acked)
        assertNull("posting proves nothing about attention", c.active()!!.acknowledgedAt)
        assertEquals(Engagement.UNCONFIRMED, c.active()!!.engagement)
    }

    // ---- 3. silence after an acknowledgement ----------------------------------------------------

    @Test fun anAcknowledgedMessageThatRunsOutTheWindowIsIgnoredSeen() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        c.onSurface(token, SurfaceReport.Acked)
        now += window // exactly the window: unanswered from here on
        val closed = c.tick().closed.single()
        assertEquals(Outcome.TIMEOUT, closed.outcome)
        assertEquals("ACKED + a full reply window of silence", Engagement.IGNORED_SEEN, closed.engagement)
    }

    @Test fun aSilentAckAloneNeverBecomesIgnoredWhileTheWindowIsOpen() {
        val c = coordinator().also { it.recover(Gate()) }
        val token = c.showPage()
        c.onSurface(token, SurfaceReport.Acked)
        now += window - 1
        assertTrue("still inside the reply window", c.tick().closed.isEmpty())
        assertEquals(Engagement.ACKED, c.active()!!.engagement)
    }

    // ---- 4. survives a restart -----------------------------------------------------------------

    @Test fun theAcknowledgementAndItsWindowSurviveAProcessRestart() {
        val store = MemorySnapshotStore()
        val c = coordinator(store).also { it.recover(Gate()) }
        val token = c.showPage()
        c.onSurface(token, SurfaceReport.Acked)
        val acked = c.active()!!

        val c2 = coordinator(store)
        c2.recover(Gate())
        // A page on screen when the process died is abandoned — but the acknowledgement it recorded
        // stays: seen-but-unanswered is a fact about the person, not about the process.
        val dead = c2.receipts().first()
        assertEquals(Outcome.ABANDONED, dead.outcome)
        assertEquals(acked.acknowledgedAt, dead.acknowledgedAt)
        assertEquals(acked.replyDeadline, dead.replyDeadline)
    }

    @Test fun aRestartAfterTheReplyWindowClosesAsIgnoredSeen() {
        val store = MemorySnapshotStore()
        val c = coordinator(store).also { it.recover(Gate()) }
        c.onSurface(c.showPage(), SurfaceReport.Acked)
        now += window + 1_000L
        val c2 = coordinator(store)
        val dead = c2.recover(Gate()).closed.first()
        assertEquals(Outcome.ABANDONED, dead.outcome)
        assertEquals(Engagement.IGNORED_SEEN, dead.engagement)
    }

    @Test fun theReplyWindowIsKernelPolicyWithADebugValue() {
        assertEquals(20 * 60_000L, ReplyWindow.DEFAULT_MS)
        assertEquals(60_000L, ReplyWindow.DEBUG_MS)
        assertEquals(ReplyWindow.DEFAULT_MS, CoordinatorConfig().replyWindowMs)
    }

    @Test fun ackFieldsSurviveTheStoreRoundTrip() {
        val store = MemorySnapshotStore()
        val c = coordinator(store).also { it.recover(Gate()) }
        c.onSurface(c.showPage(), SurfaceReport.Acked)
        val live = c.active()!!
        val back = (store.read() as SnapshotStore.Read.Loaded).map["active"] as Map<*, *>
        val decoded = CoreCodec.event(back)
        assertNotNull(decoded)
        assertEquals(live.acknowledgedAt, decoded!!.acknowledgedAt)
        assertEquals(live.replyDeadline, decoded.replyDeadline)
        assertFalse(decoded.engagement == Engagement.RESPONDED)
    }
}
