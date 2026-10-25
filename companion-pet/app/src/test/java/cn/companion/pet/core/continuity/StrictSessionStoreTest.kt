package cn.companion.pet.core.continuity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StrictSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun active(minutes: Int = 10, startAt: Long = 0): StrictSessionState =
        StrictSessionState.new(startAt, minutes, "读一篇文章", "screen", listOf("指出转折后的重点"))

    @Test
    fun roundTripKeepsProgress() {
        val store = StrictSessionStore(tmp.root)
        val s = active().copy(question = "转折后强调什么？", phase = Phase.WAITING, attempts = 1, updatedAt = 1_000)
        assertTrue(store.save(s))
        val loaded = StrictSessionStore(tmp.root).load()
        assertNotNull(loaded)
        assertEquals("转折后强调什么？", loaded!!.question)
        assertEquals(Phase.WAITING, loaded.phase)
        assertEquals(1, loaded.attempts)
    }

    @Test
    fun resumeCarriesQuestionAndGap() {
        val store = StrictSessionStore(tmp.root)
        store.save(active().copy(question = "转折后强调什么？", phase = Phase.WAITING, updatedAt = 5_000))
        val r = StrictSessionStore(tmp.root).restore(65_000)
        assertTrue(r is StrictSessionStore.Restore.Resume)
        val resume = r as StrictSessionStore.Restore.Resume
        assertEquals("转折后强调什么？", resume.state.question)
        assertEquals(60_000, resume.gapMillis)
        assertEquals(Health.DEGRADED, resume.state.health)
    }

    @Test
    fun expiredSessionIsNotResumed() {
        val store = StrictSessionStore(tmp.root)
        store.save(active(minutes = 1).copy(phase = Phase.WAITING, updatedAt = 1_000))
        val r = StrictSessionStore(tmp.root).restore(120_000)
        assertTrue(r is StrictSessionStore.Restore.Expired)
        assertEquals(Phase.EXPIRED, (r as StrictSessionStore.Restore.Expired).state.phase)
    }

    @Test
    fun finishedSessionIsTombstoned() {
        val store = StrictSessionStore(tmp.root)
        store.save(active().copy(phase = Phase.COMPLETED, updatedAt = 1_000))
        assertTrue(StrictSessionStore(tmp.root).restore(2_000) is StrictSessionStore.Restore.Finished)
    }

    @Test
    fun abortedSessionIsNotResumed() {
        val store = StrictSessionStore(tmp.root)
        store.save(active().copy(phase = Phase.ABORTED, updatedAt = 1_000))
        assertTrue(StrictSessionStore(tmp.root).restore(2_000) is StrictSessionStore.Restore.Finished)
    }

    @Test
    fun emptyStoreRestoresNothing() {
        assertTrue(StrictSessionStore(tmp.root).restore(1_000) is StrictSessionStore.Restore.None)
    }

    @Test
    fun corruptFileIsReportedNotCrashed() {
        val store = StrictSessionStore(tmp.root)
        store.save(active())
        java.io.File(tmp.root, "continuity/strict.json").writeText("{ not json")
        val fresh = StrictSessionStore(tmp.root)
        assertNull(fresh.load())
        assertNotNull(fresh.lastError)
    }

    @Test
    fun abortedByUserIsNotResumedEvenIfNotExpired() {
        val store = StrictSessionStore(tmp.root)
        val m = StrictSessionMachine(active(minutes = 30))
        m.ask("问一", emptyList(), 1_000)
        m.abort(2_000)
        store.save(m.state)
        val r = StrictSessionStore(tmp.root).restore(3_000)
        assertTrue(r is StrictSessionStore.Restore.Finished)
    }

    @Test
    fun resumedStateKeepsRevisionSoStaleResultsStillFail() {
        val store = StrictSessionStore(tmp.root)
        val m = StrictSessionMachine(active())
        m.ask("问一", emptyList(), 1_000)
        val rev = m.state.revision
        store.save(m.state)
        val r = StrictSessionStore(tmp.root).restore(2_000) as StrictSessionStore.Restore.Resume
        assertEquals(rev, r.state.revision)
        assertEquals("问一", r.state.question)
    }

    @Test
    fun clearRemovesTombstone() {
        val store = StrictSessionStore(tmp.root)
        store.save(active())
        assertTrue(store.clear())
        assertTrue(StrictSessionStore(tmp.root).restore(1_000) is StrictSessionStore.Restore.None)
    }
}