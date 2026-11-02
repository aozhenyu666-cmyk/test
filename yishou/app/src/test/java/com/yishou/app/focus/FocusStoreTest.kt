package com.yishou.app.focus

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val plan = FocusPlan("刷言语题", "两题", 10, 3, 1, listOf("GPT"))

    @Test fun expiredFileIsArchivedOnRestartAndOnlyOnce() {
        var now = 1000L
        val store = FocusStore(temp.root) { now }
        assertTrue(store.start(plan, setOf("gpt"), setOf("wx")))
        val oldActive = File(temp.root, "focus_active.json").readText()
        now += 600001
        val restored = FocusStore(temp.root) { now }
        assertNull(restored.state.value)
        assertEquals(1, restored.history.value.size)
        assertEquals(601000L, restored.history.value.single().session.endedAt)
        File(temp.root, "focus_active.json").writeText(oldActive)
        assertNull(FocusStore(temp.root) { now }.active())
        assertEquals(1, FocusStore(temp.root) { now }.history.value.size)
    }

    @Test fun restartKeepsPackagesAndResetsQuitWaiting() {
        var now = 1000L
        val store = FocusStore(temp.root) { now }
        store.start(plan, setOf("gpt"), setOf("wx"))
        store.update { FocusEngine.askQuit(it, now) }
        now += 5000
        val restored = FocusStore(temp.root) { now }
        assertEquals(setOf("gpt"), restored.active()!!.allowedPackages)
        assertEquals(setOf("wx"), restored.active()!!.messaging)
        assertNull(restored.state.value!!.quitAskedAt)
        assertFalse(restored.start(plan, emptySet(), emptySet()))
    }

    @Test fun marksSurviveEndingAndHistoryStopsAtTwoHundred() {
        var now = 1000L
        val store = FocusStore(temp.root) { now }
        repeat(201) {
            val start = now
            assertTrue(store.start(plan, emptySet(), emptySet()))
            now += 600001
            store.active()
            store.mark(start, 1, true, "补记")
        }
        val restored = FocusStore(temp.root) { now }
        assertEquals(200, restored.history.value.size)
        assertEquals(1, restored.history.value.last().session.doneRounds)
    }

    @Test fun finalRoundCanBeNotifiedAfterTheUiHasAlreadyArchivedTheSession() {
        var now = 1000L
        val store = FocusStore(temp.root) { now }
        store.start(plan, emptySet(), emptySet())
        now += 600001
        store.active()
        assertEquals(listOf(1, 2, 3), store.claimRounds()!!.second)
        assertNull(store.claimRounds())
        assertNotNull(store.claimEnd())
        assertNull(FocusStore(temp.root) { now }.claimEnd())
    }
}
