package cn.companion.pet.core.agent

import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.EventVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentEventLedgerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun highWaterPreventsReplayAfterIdCapacityIsExceeded() {
        val ledger = AgentEventLedger(tmp.root, maxEntries = 3)
        ledger.mark("e1", 1_000)
        ledger.mark("e2", 1_001)
        ledger.mark("e3", 1_002)
        ledger.mark("e4", 1_003)
        assertEquals(3, ledger.processed(2_000).size)
        assertEquals(1_003, ledger.highWater())
        val events = EventLog(tmp.root)
        val old = events.append(EventKind.NOTE, "旧事件", occurredAt = 1_000, source = "note")
        val visibility = EventVisibility(events.all(), emptySet(), 2_000)
        assertNull(AgentEventScanner().next(events.all(), emptySet(), 2_000, visibility, ledger.highWater()))
        val fresh = events.append(EventKind.NOTE, "新事件", occurredAt = 1_004, source = "note")
        assertEquals(fresh.id, AgentEventScanner().next(events.all(), emptySet(), 2_000, visibility, ledger.highWater())?.id)
    }
}
