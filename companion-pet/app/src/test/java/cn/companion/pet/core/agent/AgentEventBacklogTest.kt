package cn.companion.pet.core.agent

import cn.companion.pet.core.companion.CompanionEvent
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 审计 P1-2 / P2-4：积压事件全部处理；临时失败退避重试，有上限；没调模型不记账。 */
class AgentEventBacklogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun ev(id: String, at: Long) = CompanionEvent(id, at, "2026-10-24", EventKind.NOTE, "n$id", source = "note")

    @Test
    fun backlogIsProcessedInOrderWhenMarkedWithOccurrenceTime() {
        val ledger = AgentEventLedger(tmp.root)
        val scanner = AgentEventScanner()
        val events = listOf(ev("a", 10_000), ev("b", 11_000), ev("c", 12_000))
        val seen = mutableListOf<String>()
        var now = 50_000L
        while (true) {
            val vis = EventVisibility(events, emptySet(), now)
            val next = scanner.next(events, ledger.processed(now), now, vis, ledger.highWater()) ?: break
            seen += next.id
            ledger.mark(next.id, next.occurredAt)
            now += 1_000
        }
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun transientFailureBacksOffThenGivesUpAfterThreeAttempts() {
        val ledger = AgentEventLedger(tmp.root)
        assertFalse(ledger.recordFailure("a", 10_000, now = 100_000))
        assertEquals(160_000, ledger.retryAt("a"))
        assertFalse(ledger.recordFailure("a", 10_000, now = 200_000))
        assertEquals(500_000, ledger.retryAt("a"))
        assertTrue(ledger.recordFailure("a", 10_000, now = 600_000))
        assertTrue("given up events are marked processed", "a" in ledger.processed(600_000))
        assertEquals(0, AgentEventLedger(tmp.root).retryAt("a"))
    }

    @Test
    fun successAfterFailureClearsRetry() {
        val ledger = AgentEventLedger(tmp.root)
        ledger.recordFailure("a", 10_000, now = 100_000)
        ledger.mark("a", 10_000)
        assertEquals(0, ledger.retryAt("a"))
    }

    @Test
    fun dispositionMatchesWhetherModelWasCalled() {
        assertEquals(EventDisposition.RETRY, EventDisposition.of(AgentTurnStatus.MODEL_FAILED))
        assertEquals(EventDisposition.RETRY, EventDisposition.of(AgentTurnStatus.TIMEOUT))
        assertEquals(EventDisposition.RETRY, EventDisposition.of(AgentTurnStatus.CANCELLED))
        assertEquals(EventDisposition.HOLD, EventDisposition.of(AgentTurnStatus.BUDGET_EXHAUSTED))
        assertEquals(EventDisposition.HOLD, EventDisposition.of(AgentTurnStatus.DISABLED))
        listOf(AgentTurnStatus.EXECUTED, AgentTurnStatus.NO_ACTION, AgentTurnStatus.NEEDS_CONFIRMATION,
            AgentTurnStatus.DENIED, AgentTurnStatus.INVALID).forEach { assertEquals(EventDisposition.DONE, EventDisposition.of(it)) }
        assertEquals(EventDisposition.DONE, EventDisposition.of(null))
    }

    @Test
    fun oldPrunedEventsStillDoNotReplay() {
        val ledger = AgentEventLedger(tmp.root, maxEntries = 2)
        ledger.mark("a", 1_000); ledger.mark("b", 2_000); ledger.mark("c", 3_000)
        val events = listOf(ev("a", 1_000))
        assertNull(AgentEventScanner().next(events, ledger.processed(4_000), 4_000, EventVisibility(events, emptySet(), 4_000), ledger.highWater()))
    }
}
