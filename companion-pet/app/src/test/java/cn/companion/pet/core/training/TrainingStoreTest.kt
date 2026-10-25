package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrainingStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun session(slot: Int = 1) = TrainingSession(
        id = "s1",
        interactionMode = InteractionMode.COACH,
        questionId = "sample-scope-01",
        requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
        currentSlot = slot,
        state = TrainingState.WAITING_INPUT,
        createdAt = 0,
        updatedAt = 1_000,
    )

    @Test
    fun roundTripKeepsSlotAndAttempts() {
        val store = TrainingStore(tmp.root)
        val s = session().copy(attempts = listOf(
            AnswerAttempt("a1", SlotKind.LOCATE, "text", verdict = Verdict.PASS),
        ))
        assertTrue(store.save(s, BuiltInQuestions.SAMPLES.first()))
        val loaded = TrainingStore(tmp.root).load()
        assertNotNull(loaded)
        assertEquals(1, loaded!!.session.currentSlot)
        assertEquals(1, loaded.session.attempts.size)
        assertEquals(Verdict.PASS, loaded.session.attempts[0].verdict)
    }

    @Test
    fun restoreResumesWaitingWithGap() {
        val store = TrainingStore(tmp.root)
        store.save(session(), BuiltInQuestions.SAMPLES.first())
        val r = TrainingStore(tmp.root).restore(61_000)
        assertTrue(r is TrainingStore.Restore.Resume)
        val resume = r as TrainingStore.Restore.Resume
        assertEquals(60_000, resume.gapMillis)
        assertEquals(TrainingState.WAITING_INPUT, resume.session.state)
        assertEquals(1, resume.session.currentSlot)
        assertEquals("sample-scope-01", resume.question.id)
    }

    @Test
    fun restoreClearsStalePendingRequest() {
        val store = TrainingStore(tmp.root)
        store.save(session().copy(pendingRequestId = "req-old", state = TrainingState.EVALUATING), BuiltInQuestions.SAMPLES.first())
        val r = TrainingStore(tmp.root).restore(2_000) as TrainingStore.Restore.Resume
        assertEquals("", r.session.pendingRequestId)
        assertEquals(TrainingState.WAITING_INPUT, r.session.state)
    }

    @Test
    fun finishedSessionIsNotResumed() {
        val store = TrainingStore(tmp.root)
        store.save(session().copy(state = TrainingState.COMPLETED), BuiltInQuestions.SAMPLES.first())
        assertTrue(TrainingStore(tmp.root).restore(2_000) is TrainingStore.Restore.Finished)
    }

    @Test
    fun endedSessionIsNotResumed() {
        val store = TrainingStore(tmp.root)
        store.save(session().copy(state = TrainingState.ENDED), BuiltInQuestions.SAMPLES.first())
        assertTrue(TrainingStore(tmp.root).restore(2_000) is TrainingStore.Restore.Finished)
    }

    @Test
    fun emptyStoreRestoresNothing() {
        assertTrue(TrainingStore(tmp.root).restore(1_000) is TrainingStore.Restore.None)
    }

    @Test
    fun corruptFileReportedNotCrash() {
        val store = TrainingStore(tmp.root)
        store.save(session(), BuiltInQuestions.SAMPLES.first())
        java.io.File(tmp.root, "training/session.json").writeText("{ bad")
        val fresh = TrainingStore(tmp.root)
        assertNull(fresh.load())
        assertNotNull(fresh.lastError)
    }
}