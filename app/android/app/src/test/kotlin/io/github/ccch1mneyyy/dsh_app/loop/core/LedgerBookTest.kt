package io.github.ccch1mneyyy.dsh_app.loop.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Round 4, task 2: two accounts, two books.
 *
 * The point of this file is the *independence*: 主动交流账 (A) and 行为拦截账 (B) have their own
 * counters, their own files and their own recovery, and no single ignoredCount can move both chains.
 * It also covers the two failure modes that matter for counters — process death mid-count and
 * repeated delivery of the same eventId — for both books.
 */
class LedgerBookTest {
    private var wall = 1_793_340_000_000L
    private var elapsed = 3_600_000L
    private var seq = 0
    private val zone = 8 * 3_600_000L
    private val min = 60_000L

    private fun minuteOfDay(w: Long) = (((w + zone) % 86_400_000L) / min).toInt()
    private fun dayOf(w: Long) = Math.floorDiv(w + zone, 86_400_000L)
    private fun clocks() = Clocks(wall, elapsed, 1)

    /** Advance both clocks, so a plan made by elapsed time still lines up with the wall clock. */
    private fun advance(ms: Long) { wall += ms; elapsed += ms }

    // ---------------------------------------------------------------- A. 主动交流账

    private fun proactive(store: SnapshotStore = MemorySnapshotStore()) = ProactiveLedgerBook(store).also { it.recover() }

    @Test fun theProactiveBookCountsEachFactOnItsOwn() {
        val book = proactive()
        book.onAcked("e1", wall)
        book.onClosed("e1", Engagement.RESPONDED, wall)
        assertEquals(1, book.state.delivered)
        assertEquals(1, book.state.userConfirmedSeen)
        assertEquals(1, book.state.replied)
        assertEquals("an answer ends the unanswered run", 0, book.state.consecutiveUnanswered)
        assertEquals(0, book.state.ignoredSeen)
    }

    @Test fun ackingWithoutAnsweringIsItsOwnFact() {
        val book = proactive()
        book.onAcked("e1", wall)
        book.onClosed("e1", Engagement.ACKED, wall)
        assertEquals(1, book.state.userConfirmedSeen)
        assertEquals(0, book.state.replied)
        assertEquals(1, book.state.ackedNotReplied)
        assertEquals(0, book.state.consecutiveUnanswered)
    }

    @Test fun aWholeReplyWindowOfSilenceIsWhatMovesTheUnansweredRun() {
        val book = proactive()
        book.onAcked("e1", wall); book.onClosed("e1", Engagement.IGNORED_SEEN, wall)
        book.onAcked("e2", wall); book.onClosed("e2", Engagement.IGNORED_SEEN, wall)
        assertEquals(2, book.state.consecutiveUnanswered)
        assertEquals(2, book.state.ignoredSeen)
        // An answer breaks the run but not the lifetime count.
        book.onClosed("e3", Engagement.RESPONDED, wall)
        assertEquals(0, book.state.consecutiveUnanswered)
        assertEquals(2, book.state.ignoredSeen)
    }

    @Test fun anAcknowledgementWhoseReportWasLostStillCountsAsSeen() {
        val book = proactive()
        // The ack report never arrived (process died first); the close still proves it happened.
        book.onClosed("e1", Engagement.IGNORED_SEEN, wall)
        assertEquals(1, book.state.userConfirmedSeen)
        assertEquals(1, book.state.ignoredSeen)
    }

    @Test fun anUnconfirmedDeliveryCountsAsDeliveredAndNothingElse() {
        val book = proactive()
        book.onClosed("e1", Engagement.UNCONFIRMED, wall)
        assertEquals(1, book.state.delivered)
        assertEquals(0, book.state.userConfirmedSeen)
        assertEquals(0, book.state.ignoredSeen)
        book.onClosed("e2", Engagement.NOT_DELIVERED, wall)
        assertEquals("never reaching a surface is not a delivery", 1, book.state.delivered)
    }

    @Test fun aRepeatedAcknowledgementForOneEventCountsOnce() {
        val book = proactive()
        assertTrue(book.onAcked("e1", wall))
        assertFalse("double tap", book.onAcked("e1", wall))
        assertEquals(1, book.state.userConfirmedSeen)
        // ...and the close that follows does not count the same ack a second time.
        book.onClosed("e1", Engagement.ACKED, wall)
        assertEquals(1, book.state.userConfirmedSeen)
    }

    @Test fun aRepeatedCloseForOneEventCountsOnce() {
        val book = proactive()
        assertTrue(book.onClosed("e1", Engagement.RESPONDED, wall))
        assertFalse("a retried receipt", book.onClosed("e1", Engagement.RESPONDED, wall))
        assertEquals(1, book.state.replied)
        assertEquals(1, book.state.delivered)
    }

    @Test fun theProactiveBookSurvivesAProcessRestart() {
        val store = MemorySnapshotStore()
        val first = proactive(store)
        first.onAcked("e1", wall)
        first.onClosed("e1", Engagement.IGNORED_SEEN, wall)
        first.onClosed("e2", Engagement.RESPONDED, wall)

        val second = proactive(store)
        assertEquals(first.state, second.state)
        // The already-applied ids came back too: a replayed receipt after a restart adds nothing.
        assertFalse(second.onClosed("e1", Engagement.IGNORED_SEEN, wall))
        assertFalse(second.onAcked("e1", wall))
        assertEquals(first.state, second.state)
    }

    @Test fun anUnreadableProactiveBookSaysSoInsteadOfLookingNew() {
        val store = MemorySnapshotStore(text = "{broken")
        val book = proactive(store)
        assertTrue(book.recoveryIssue!!.startsWith("ledger_unreadable"))
        assertEquals(ProactiveLedger(), book.state)
    }

    @Test fun aFailedWriteIsReportedAndKeepsTheCounters() {
        val store = MemorySnapshotStore(failWrites = true)
        val book = proactive(store)
        book.onAcked("e1", wall)
        assertNotNull(book.storageError)
        assertEquals("the book still knows, even if the disk did not take it", 1, book.state.userConfirmedSeen)
    }

    // ---------------------------------------------------------------- B. 行为拦截账

    private fun intercept(store: SnapshotStore = MemorySnapshotStore()) = InterceptLedgerBook(store).also { it.recover() }

    private fun receipt(id: String, outcome: InterceptOutcome, pkg: String = "tv.danmaku.bili") = InterceptReceipt(
        eventId = id, packageName = pkg, triggeredAt = wall, endedAt = wall + 1_000L, outcome = outcome,
    )

    @Test fun theInterceptBookCountsTriggersBlocksReleasesAndExits() {
        val book = intercept()
        book.onTriggered("i1", wall)
        book.onBlocked("i1", wall)
        book.onEnded(receipt("i1", InterceptOutcome.RELEASED))
        book.onTriggered("i2", wall)
        book.onBlocked("i2", wall)
        book.onEnded(receipt("i2", InterceptOutcome.EXITED))
        assertEquals(2, book.state.triggerCount)
        assertEquals(2, book.state.blockedCount)
        assertEquals(1, book.state.userReleasedCount)
        assertEquals(1, book.state.userExitedCount)
        assertEquals(2, book.state.receipts.size)
    }

    @Test fun aBlockedPageThatWasNeverSeenIsNotCountedAsBlocked() {
        val book = intercept()
        book.onTriggered("i1", wall)
        book.onEnded(receipt("i1", InterceptOutcome.NOT_SHOWN))
        assertEquals(1, book.state.triggerCount)
        assertEquals("a page that never came up blocked nothing", 0, book.state.blockedCount)
    }

    @Test fun repeatedInterceptFactsForOneEventCountOnce() {
        val book = intercept()
        assertTrue(book.onTriggered("i1", wall))
        assertFalse(book.onTriggered("i1", wall))
        assertTrue(book.onBlocked("i1", wall))
        assertFalse(book.onBlocked("i1", wall))
        assertTrue(book.onEnded(receipt("i1", InterceptOutcome.RELEASED)))
        assertFalse("a retried button", book.onEnded(receipt("i1", InterceptOutcome.EXITED)))
        assertEquals(1, book.state.triggerCount)
        assertEquals(1, book.state.blockedCount)
        assertEquals(1, book.state.userReleasedCount)
        assertEquals("the first ending wins", 0, book.state.userExitedCount)
    }

    @Test fun theInterceptBookSurvivesAProcessRestart() {
        val store = MemorySnapshotStore()
        val first = intercept(store)
        first.onTriggered("i1", wall); first.onBlocked("i1", wall)
        first.onEnded(receipt("i1", InterceptOutcome.RELEASED))
        val second = intercept(store)
        assertEquals(first.state, second.state)
        assertFalse(second.onEnded(receipt("i1", InterceptOutcome.EXITED)))
        assertEquals(first.state, second.state)
    }

    @Test fun anUnreadableInterceptBookSaysSoInsteadOfLookingNew() {
        val book = intercept(MemorySnapshotStore(text = "not json at all"))
        assertTrue(book.recoveryIssue!!.startsWith("intercept_ledger_unreadable"))
        assertEquals(InterceptLedger(), book.state)
    }

    // ---------------------------------------------------------------- the two books never touch

    @Test fun theTwoBooksAreIndependentFilesWithIndependentCounters() {
        val aStore = MemorySnapshotStore()
        val bStore = MemorySnapshotStore()
        val a = proactive(aStore)
        val b = intercept(bStore)

        // The proactive book learns about silence; the intercept book learns about blocks.
        repeat(3) { i ->
            a.onAcked("e" + i, wall)
            a.onClosed("e" + i, Engagement.IGNORED_SEEN, wall)
            b.onTriggered("i" + i, wall)
        }
        assertEquals(3, a.state.consecutiveUnanswered)
        assertEquals(3, b.state.triggerCount)
        assertEquals("one book's silence does not move the other's counters", 0, b.state.blockedCount)
        assertEquals("the block counter never becomes a released message", 0, b.state.userReleasedCount)
        assertEquals("and the proactive counters stay out of the intercept book", 3, a.state.delivered)
        // Two files, not one: writing A changed nothing in B's store and the other way round.
        assertNotEquals(aStore.text, bStore.text)
    }

    @Test fun pausingTheInterceptsCannotPauseTheProactiveSchedule() {
        val ledger = proactive()
        val cfg = SchedulerConfig(
            enabled = true, minIntervalMs = 30 * min, maxIntervalMs = 60 * min,
            quietStartMinute = 0, quietEndMinute = 0, dailyMax = 0,
        )
        val sched = ProactiveScheduler(
            MemorySnapshotStore(), ::minuteOfDay, ::dayOf, Random(4), { "evt-" + (++seq) }, ledger,
        )
        sched.recover(clocks())
        sched.setConfig(cfg, clocks())
        val guard = InterceptGuard(
            MemorySnapshotStore(), InterceptLedgerBook(MemorySnapshotStore()).also { it.recover() }, { wall },
        ) { "i" + (++seq) }
        guard.recover(wall)
        guard.setConfig(InterceptConfig(enabled = true), wall)
        guard.onPaused(null, 60 * min, wall)

        assertFalse("a paused intercept is not a paused scheduler", sched.state.pausedForIgnored)
        assertEquals(0, ledger.state.ignoredSeen)
    }

    // ---------------------------------------------------------------- the scheduler feeds book A

    @Test fun theSchedulerRecordsEveryReceiptInBookAOnce() {
        val ledger = proactive()
        val cfg = SchedulerConfig(
            enabled = true, minIntervalMs = 30 * min, maxIntervalMs = 60 * min,
            quietStartMinute = 0, quietEndMinute = 0, dailyMax = 0,
        )
        val sched = ProactiveScheduler(
            MemorySnapshotStore(), ::minuteOfDay, ::dayOf, Random(11), { "evt-" + (++seq) }, ledger,
        )
        sched.recover(clocks())
        sched.setConfig(cfg, clocks())

        fun fire(): String {
            repeat(20) {
                when (val d = sched.evaluate(clocks())) {
                    is ProactiveScheduler.Decision.Fire -> {
                        sched.onSubmitted(d.request.eventId, clocks())
                        return d.request.eventId
                    }
                    is ProactiveScheduler.Decision.Wait -> advance((d.untilWall - wall).coerceAtLeast(min))
                }
            }
            error("scheduler never fired")
        }

        val first = fire()
        assertTrue(sched.onAcked(first, clocks()))
        assertTrue(sched.onOutcome(first, Engagement.RESPONDED, clocks()))
        assertEquals(1, ledger.state.delivered)
        assertEquals(1, ledger.state.userConfirmedSeen)
        assertEquals(1, ledger.state.replied)
        // A duplicate receipt for the same event: the scheduler refuses it and the book adds nothing.
        assertFalse(sched.onOutcome(first, Engagement.RESPONDED, clocks()))
        assertEquals(1, ledger.state.replied)
        assertNull(sched.state.pendingEventId)
    }

    @Test fun anAckAloneDoesNotWidenTheGapButAFullSilentWindowDoes() {
        val cfg = SchedulerConfig(
            enabled = true, minIntervalMs = 30 * min, maxIntervalMs = 90 * min,
            quietStartMinute = 0, quietEndMinute = 0, dailyMax = 0, ignoredBackoff = 2.0, pauseAfterIgnored = 3,
        )
        fun newScheduler(): ProactiveScheduler =
            ProactiveScheduler(MemorySnapshotStore(), ::minuteOfDay, ::dayOf, Random(5), { "evt-" + (++seq) })
                .also { it.recover(clocks()); it.setConfig(cfg, clocks()) }

        fun ProactiveScheduler.fire(): String {
            repeat(20) {
                when (val d = evaluate(clocks())) {
                    is ProactiveScheduler.Decision.Fire -> { onSubmitted(d.request.eventId, clocks()); return d.request.eventId }
                    is ProactiveScheduler.Decision.Wait -> advance((d.untilWall - wall).coerceAtLeast(min))
                }
            }
            error("scheduler never fired")
        }

        val s = newScheduler()
        val ackedId = s.fire()
        assertTrue(s.onAcked(ackedId, clocks()))
        assertTrue(s.onOutcome(ackedId, Engagement.ACKED, clocks()))
        assertEquals("a confirmed seen message is not an ignored one", 0, s.state.ignoredStreak)
        assertEquals("the ACKED receipt is what moves the acked streak, once", 1, s.state.ackedStreak)
        assertFalse(s.state.pausedForIgnored)

        val ignoredId = s.fire()
        assertTrue(s.onOutcome(ignoredId, Engagement.IGNORED_SEEN, clocks()))
        assertEquals(1, s.state.ignoredStreak)
        assertEquals(0, s.state.ackedStreak)
    }
}
