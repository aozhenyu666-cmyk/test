package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T4: arbitration, queueing, stale replies/answers, recovery. Numbers refer to the round-2 scenario list. */
class CoordinatorTest {
    private var now = 1_700_000_000_000L
    private var seq = 0
    private val store = MemorySnapshotStore()
    private fun coordinator(s: SnapshotStore = store, cfg: CoordinatorConfig = CoordinatorConfig()) =
        Coordinator(s, cfg, { now }, { "id${++seq}" })

    private fun req(id: String, key: String = id, p: Priority = Priority.NORMAL, agent: Boolean = false, fallback: String? = "hi", ttl: Long = 600_000) =
        EventRequest(id, "TEST", "chat", key, p, now, ttl, agent, fallback)

    private inline fun <reified T : Command> Step.only(): List<T> = commands.filterIsInstance<T>()
    private fun Step.present() = only<Command.Present>().single()

    private fun ready(): Coordinator = coordinator().also { it.recover(Gate()) }

    /** Present → Posted → Shown for the active activity page; returns its token. */
    private fun Coordinator.showActive(step: Step): String {
        val p = step.present()
        onSurface(p.token, SurfaceReport.Posted)
        onSurface(p.token, SurfaceReport.Shown)
        assertEquals(Stage.SHOWN, active()!!.stage)
        return p.token
    }

    // 1. Two events at once
    @Test fun twoEventsAtOnceQueueByPriorityAndShowOneAtATime() {
        val c = ready()
        val a = c.submit(req("a", p = Priority.NORMAL))
        val b = c.submit(req("b", p = Priority.HIGH))
        assertEquals(Submit.Queued("a"), a.first)
        assertEquals(Submit.Queued("b"), b.first)
        assertEquals("a is already on screen", "a", a.second.present().eventId)
        assertTrue("b waits", b.second.only<Command.Present>().isEmpty())
        val t = c.active()!!.token!!
        c.onSurface(t, SurfaceReport.Posted)
        c.onSurface(t, SurfaceReport.Shown)
        val next = c.onSurface(t, SurfaceReport.Answered("ok"))
        assertEquals(Outcome.RESPONDED, next.closed.single().outcome)
        assertEquals("b", next.present().eventId)
    }

    @Test fun waitingEventsAreOrderedByPriorityThenAge() {
        val c = coordinator().also { it.recover(Gate(dshTaskActive = true)) }
        c.submit(req("low", p = Priority.LOW)); now += 1
        c.submit(req("n1")); now += 1
        c.submit(req("high", p = Priority.HIGH)); now += 1
        c.submit(req("n2"))
        assertEquals("high", c.setGate(Gate()).present().eventId)
    }

    @Test fun sameIntentMergesInsteadOfShowingTwice() {
        val c = coordinator().also { it.recover(Gate(dshTaskActive = true)) }
        c.submit(req("a", key = "app:x"))
        val (r, s) = c.submit(req("b", key = "app:x", p = Priority.HIGH))
        assertEquals(Submit.Merged("a"), r)
        assertEquals(Outcome.MERGED, s.closed.single().outcome)
        assertEquals("a", s.closed.single().mergedInto)
        val q = c.queued().single()
        assertEquals(Priority.HIGH, q.priority)
        assertEquals(1, q.mergedCount)
        c.setGate(Gate())
        // Merging into the event already on screen does not show it again.
        val (r2, s2) = c.submit(req("c", key = "app:x"))
        assertEquals(Submit.Merged("a"), r2)
        assertTrue(s2.only<Command.Present>().isEmpty())
    }

    @Test fun fullQueueDropsTheWeakest() {
        val c = coordinator(cfg = CoordinatorConfig(maxQueue = 2)).also { it.recover(Gate(dshTaskActive = true)) }
        c.submit(req("n1")); now += 1
        c.submit(req("n2")); now += 1
        assertEquals(Submit.Rejected("queue_full"), c.submit(req("n3")).first)
        val (r, s) = c.submit(req("h", p = Priority.HIGH))
        assertEquals(Submit.Queued("h"), r)
        assertEquals("n1" to "displaced", s.closed.single().let { it.eventId to it.reason })
        assertEquals(listOf("n2", "h"), c.queued().map { it.eventId })
    }

    // 2. A DSH task is running
    @Test fun aRunningTaskHoldsEverythingAndAPageOnScreenGivesWay() {
        val c = coordinator().also { it.recover(Gate(dshTaskActive = true)) }
        val held = c.submit(req("a", agent = true)).second
        assertTrue("no tokens spent while DSH works", held.commands.isEmpty())
        val start = c.setGate(Gate())
        val rq = start.only<Command.RequestAgent>().single()
        val shown = c.onAgent(rq.requestId, AgentReply.Generated("hello", "t1"))
        val token = c.showActive(shown)

        val busy = c.setGate(Gate(dshTaskActive = true))
        assertEquals(Command.Withdraw("a", token, Surface.ACTIVITY, "dsh_task"), busy.only<Command.Withdraw>().single())
        assertEquals(Stage.READY, c.active()!!.stage)
        assertEquals(1, c.active()!!.yields)
        // The old page cannot answer the event any more.
        assertTrue(c.onSurface(token, SurfaceReport.Answered("late")).closed.isEmpty())
        // DSH done: the same reply comes back on a fresh token, without asking the model again.
        val again = c.setGate(Gate())
        assertTrue(again.only<Command.RequestAgent>().isEmpty())
        assertTrue(again.present().token != token)
        assertEquals(ContentSource.AGENT, again.present().content.source)
    }

    @Test fun yieldingTooOftenClosesTheEvent() {
        val c = ready()
        c.submit(req("a"))
        repeat(2) { c.setGate(Gate(dshTaskActive = true)); c.setGate(Gate()) }
        val last = c.setGate(Gate(dshTaskActive = true))
        assertEquals(Outcome.YIELDED, last.closed.single().outcome)
        assertNull(c.active())
    }

    // 3. The person is answering a DSH approval / a permission page is in front
    @Test fun anOpenDshQuestionOrPermissionPageBlocksAndPreempts() {
        val c = coordinator().also { it.recover(Gate(dshPromptOpen = true)) }
        assertTrue(c.submit(req("a")).second.only<Command.Present>().isEmpty())
        assertEquals("a", c.setGate(Gate()).present().eventId)
        val w = c.setGate(Gate(protectedForeground = true)).only<Command.Withdraw>().single()
        assertEquals("protected_page", w.reason)
        assertTrue(c.tick().only<Command.Present>().isEmpty())
        assertEquals("a", c.setGate(Gate()).present().eventId)
    }

    // 4. The agent times out
    @Test fun agentTimeoutCancelsTheRequestAndUsesTheFallback() {
        val c = ready()
        val rq = c.submit(req("a", agent = true, fallback = "local text")).second.only<Command.RequestAgent>().single()
        c.onAgent(rq.requestId, AgentReply.Accepted)
        assertNotNull(c.active()!!.agentAcceptedAt)
        now = rq.deadline
        val t = c.tick()
        assertEquals(Command.CancelAgent("a", rq.requestId, "timeout"), t.only<Command.CancelAgent>().single())
        assertEquals(Content("local text", ContentSource.FALLBACK), t.present().content)
        assertEquals("timeout", c.active()!!.agentError)
        assertNull("no model text was produced", c.active()!!.generatedAt)
    }

    @Test fun agentFailureWithoutFallbackFails() {
        val c = ready()
        val rq = c.submit(req("a", agent = true, fallback = null)).second.only<Command.RequestAgent>().single()
        val s = c.onAgent(rq.requestId, AgentReply.Failed("agent_busy"))
        assertEquals(Outcome.FAILED to "agent_agent_busy", s.closed.single().let { it.outcome to it.reason })
    }

    // 5. The model answers after the event was cancelled
    @Test fun aReplyForACancelledEventIsNeverShown() {
        val c = ready()
        val rq = c.submit(req("a", agent = true)).second.only<Command.RequestAgent>().single()
        val cancel = c.cancel("a", "left_app")
        assertEquals(rq.requestId, cancel.only<Command.CancelAgent>().single().requestId)
        val late = c.onAgent(rq.requestId, AgentReply.Generated("too late", "t9"))
        assertTrue(late.commands.isEmpty())
        val r = c.find("a")!!
        assertEquals(Outcome.CANCELLED, r.outcome)
        assertNull(r.content)
        assertEquals(1, r.lateResults)
        // A late "accepted" is answered with another cancel.
        assertEquals(rq.requestId, c.onAgent(rq.requestId, AgentReply.Accepted).only<Command.CancelAgent>().single().requestId)
    }

    @Test fun aReplyFromAnOlderAttemptOfTheSameEventIsIgnored() {
        val c = ready()
        val rq = c.submit(req("a", agent = true, fallback = "fb")).second.only<Command.RequestAgent>().single()
        now = rq.deadline
        c.tick() // timed out → fallback shown
        val token = c.active()!!.token!!
        assertTrue(c.onAgent(rq.requestId, AgentReply.Generated("late", null)).commands.isEmpty())
        assertEquals(ContentSource.FALLBACK, c.active()!!.content!!.source)
        assertEquals(token, c.active()!!.token)
        assertEquals(1, c.lateAgentReplies)
    }

    // 6. Process death
    @Test fun restartResumesTheAgentRequestKeepsTheQueueAndAbandonsPagesOnScreen() {
        val c = coordinator().also { it.recover(Gate()) }
        val rq = c.submit(req("a", agent = true)).second.only<Command.RequestAgent>().single()
        c.submit(req("b"))
        now += 5_000

        val c2 = coordinator()
        val r = c2.recover(Gate())
        assertTrue(r.commands.first() == Command.ClearLeftovers)
        assertEquals(Command.ResumeAgent("a", rq.requestId, rq.deadline), r.only<Command.ResumeAgent>().single())
        assertEquals(listOf("b"), c2.queued().map { it.eventId })
        // The result is picked up by the new process with the same requestId.
        val shown = c2.onAgent(rq.requestId, AgentReply.Generated("hi again", "t2"))
        val token = c2.showActive(shown)

        val c3 = coordinator()
        val r3 = c3.recover(Gate())
        val dead = r3.closed.first()
        assertEquals("a" to Outcome.ABANDONED, dead.eventId to dead.outcome)
        // Round 4: being on screen is not being ignored. Only an explicit 「我看到了」 followed by a
        // whole reply window of silence is IGNORED_SEEN; a page nobody acknowledged is UNCONFIRMED.
        assertEquals(Engagement.UNCONFIRMED, dead.engagement)
        assertTrue("old page's buttons are dead", c3.onSurface(token, SurfaceReport.Answered("x")).closed.isEmpty())
        assertEquals("b", r3.present().eventId)
    }

    @Test fun restartAfterTheAgentDeadlineFallsBack() {
        val c = coordinator().also { it.recover(Gate()) }
        val rq = c.submit(req("a", agent = true, fallback = "fb")).second.only<Command.RequestAgent>().single()
        now = rq.deadline + 1
        val r = coordinator().recover(Gate())
        assertTrue(r.only<Command.ResumeAgent>().isEmpty())
        assertEquals(ContentSource.FALLBACK, r.present().content.source)
    }

    @Test fun unreadableStateIsReported() {
        val bad = MemorySnapshotStore(text = "{broken")
        val c = coordinator(bad)
        c.recover(Gate())
        assertTrue(c.recoveryIssue!!.startsWith("state_unreadable"))
        assertEquals(Submit.Queued("a"), c.submit(req("a")).first)
    }

    // 7. Clock changes
    @Test fun clockGoingBackKeepsRemainingTimeInsteadOfStretchingDeadlines() {
        val c = ready()
        c.showActive(c.submit(req("a")).second)
        now += 10_000
        c.tick()
        val remaining = c.active()!!.interactionDeadline - now
        now -= 3_600_000 // set back an hour
        c.tick()
        // Exact up to the gap since the last call (the kernel cannot know when the jump happened).
        assertEquals(remaining, c.active()!!.interactionDeadline - now)
        now += remaining
        assertEquals(Outcome.TIMEOUT, c.tick().closed.single().outcome)
    }

    // 8. Notification posted, never seen
    @Test fun aPostedNotificationThatTimesOutIsUnconfirmedNotIgnored() {
        val c = coordinator().also { it.recover(Gate(a11yConnected = false)) }
        val p = c.submit(req("a")).second.present()
        assertEquals(Surface.NOTIFICATION, p.surface)
        c.onSurface(p.token, SurfaceReport.Posted)
        c.onSurface(p.token, SurfaceReport.Shown) // notifications cannot claim this
        val e = c.active()!!
        assertEquals(Stage.DELIVERED, e.stage)
        assertNull(e.shownAt)
        now = e.interactionDeadline
        val closed = c.tick().closed.single()
        assertEquals(Outcome.TIMEOUT, closed.outcome)
        assertEquals(Engagement.UNCONFIRMED, closed.engagement)
    }

    @Test fun tappingANotificationProvesItWasSeen() {
        val c = coordinator().also { it.recover(Gate(a11yConnected = false)) }
        val p = c.submit(req("a")).second.present()
        c.onSurface(p.token, SurfaceReport.Posted)
        val closed = c.onSurface(p.token, SurfaceReport.Answered("open")).closed.single()
        assertEquals(Engagement.RESPONDED, closed.engagement)
        assertNotNull(closed.shownAt)
    }

    @Test fun aPageThatNeverAcknowledgesFallsBackToANotificationOnce() {
        val c = ready()
        val p = c.submit(req("a")).second.present()
        assertEquals(Surface.ACTIVITY, p.surface)
        now += 3_000
        val t = c.tick()
        assertEquals("not_acknowledged", t.only<Command.Withdraw>().single().reason)
        val n = t.present()
        assertEquals(Surface.NOTIFICATION, n.surface)
        now += 3_000
        assertEquals(Outcome.UNAVAILABLE, c.tick().closed.single().outcome)
    }

    // 10. Accessibility disconnects
    @Test fun withoutAccessibilityPagesBecomeNotificationsAndAShownPageStays() {
        val c = ready()
        val token = c.showActive(c.submit(req("a")).second)
        val off = c.setGate(Gate(a11yConnected = false))
        assertTrue("a page on screen is not withdrawn", off.commands.isEmpty())
        c.onSurface(token, SurfaceReport.Answered("ok"))
        assertEquals(Surface.NOTIFICATION, c.submit(req("b")).second.present().surface)
        val none = coordinator().also { it.recover(Gate(a11yConnected = false, notificationsAllowed = false)) }
        assertTrue(none.submit(req("c")).second.only<Command.Present>().isEmpty())
        assertEquals(Stage.QUEUED, none.find("c")!!.stage)
    }

    // 11. The feature is switched off
    @Test fun switchingOffCancelsEverythingAndRefusesNewEvents() {
        val c = ready()
        val rq = c.submit(req("a", agent = true)).second.only<Command.RequestAgent>().single()
        c.submit(req("b"))
        val off = c.setGate(Gate(featureEnabled = false))
        assertEquals(rq.requestId, off.only<Command.CancelAgent>().single().requestId)
        assertEquals(setOf("a", "b"), off.closed.map { it.eventId }.toSet())
        assertTrue(off.closed.all { it.outcome == Outcome.CANCELLED && it.reason == "disabled" })
        assertEquals(Submit.Rejected("disabled"), c.submit(req("c")).first)
        assertTrue(c.onAgent(rq.requestId, AgentReply.Generated("late", null)).only<Command.Present>().isEmpty())
    }

    @Test fun switchingOffWithAPageUpWithdrawsIt() {
        val c = ready()
        val token = c.showActive(c.submit(req("a")).second)
        val off = c.setGate(Gate(featureEnabled = false))
        assertEquals(token, off.only<Command.Withdraw>().single().token)
    }

    // 12. Duplicate receipts and expired buttons
    @Test fun anEventIdRunsOnceAndOldButtonsAreRefused() {
        val c = ready()
        val token = c.showActive(c.submit(req("a")).second)
        assertEquals(Submit.Rejected("duplicate"), c.submit(req("a")).first)
        val first = c.onSurface(token, SurfaceReport.Answered("yes"))
        assertEquals(Outcome.RESPONDED, first.closed.single().outcome)
        val second = c.onSurface(token, SurfaceReport.Answered("no"))
        assertTrue(second.closed.isEmpty())
        assertEquals("yes", c.find("a")!!.answer)
        assertEquals(1, c.staleReports)
        // Even after it left the receipt history, the id stays known.
        assertEquals(Submit.Rejected("duplicate"), c.submit(req("a")).first)
        // A page that turns up late for a closed presentation is told to go away.
        assertEquals(token, c.onSurface(token, SurfaceReport.Shown).only<Command.Withdraw>().single().token)
        assertTrue(c.onSurface("forged", SurfaceReport.Answered("x")).commands.isEmpty())
    }

    @Test fun expiredEventsLeaveTheQueue() {
        val c = coordinator().also { it.recover(Gate(dshTaskActive = true)) }
        c.submit(req("a", ttl = 60_000))
        now += 60_000
        assertEquals(Outcome.EXPIRED, c.tick().closed.single().outcome)
        assertEquals(Submit.Rejected("expired"), c.submit(req("old").copy(createdAt = now - 700_000)).first)
    }

    @Test fun staleAgentReplyIsNotShownAfterWaitingTooLong() {
        val c = coordinator().also { it.recover(Gate()) }
        val rq = c.submit(req("a", agent = true)).second.only<Command.RequestAgent>().single()
        c.setGate(Gate(dshTaskActive = true))
        c.onAgent(rq.requestId, AgentReply.Generated("hi", null))
        now += 120_000
        val s = c.setGate(Gate())
        assertEquals(Outcome.EXPIRED to "stale_reply", s.closed.single().let { it.outcome to it.reason })
        assertTrue(s.only<Command.Present>().isEmpty())
    }

    @Test fun everythingIsPersistedAndReadsBackTheSame() {
        val c = ready()
        val rq = c.submit(req("a", agent = true, p = Priority.HIGH)).second.only<Command.RequestAgent>().single()
        c.onAgent(rq.requestId, AgentReply.Accepted)
        c.submit(req("b"))
        val c2 = coordinator()
        c2.recover(Gate(dshTaskActive = true))
        assertEquals(c.active(), c2.active())
        assertEquals(c.queued(), c2.queued())
        assertFalse(store.writes == 0)
    }
}
