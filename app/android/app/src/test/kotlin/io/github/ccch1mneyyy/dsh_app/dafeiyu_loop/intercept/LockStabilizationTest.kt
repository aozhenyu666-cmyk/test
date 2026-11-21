package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept

import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.CompanionReleaseTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockStabilizationTest {
    @Test
    fun quizAntiDoubleTapIgnoresDuplicateStaleAndKeepsBusyThroughDelay() {
        // Normal non-unlocked answer keeps UI locked (quizBusy=true) until postDelayed renderQuizPayload runs
        val advance = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-1",
            currentQuestionId = "q-1",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to false),
        )
        assertEquals(QuizResponseAction.ADVANCE_NEXT, advance)
        assertTrue(QuizAntiDoubleTapPolicy.keepBusyUntilNextQuestionRendered(advance))

        // Duplicate / ignored response from server must NOT advance or claim correct
        val dup = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-1",
            currentQuestionId = "q-1",
            res = mapOf("ok" to true, "duplicate" to true, "ignored" to true, "correct" to false),
        )
        assertEquals(QuizResponseAction.IGNORE_DUPLICATE, dup)
        assertFalse(QuizAntiDoubleTapPolicy.keepBusyUntilNextQuestionRendered(dup))

        // Stale questionId (e.g. UI already switched to q-2) must be ignored
        val stale = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-1",
            currentQuestionId = "q-2",
            res = mapOf("ok" to true, "correct" to true),
        )
        assertEquals(QuizResponseAction.IGNORE_STALE_QUESTION, stale)

        // Null / failed response allows user to retry
        val err = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-2",
            currentQuestionId = "q-2",
            res = null,
        )
        assertEquals(QuizResponseAction.RETRY_ERROR, err)
        assertFalse(QuizAntiDoubleTapPolicy.keepBusyUntilNextQuestionRendered(err))
    }

    @Test
    fun quizResponseDistinguishesDirectReleaseFromEarnedNeedSpend() {
        // Unlocked directly without earnedOnly -> release immediately
        val directUnlock = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to true, "unlockMinutes" to 5),
        )
        assertEquals(QuizResponseAction.RELEASE_NOW, directUnlock)

        // 4.8: earnedOnly/needSpend -> prompt user to spend balance instead of granting a legacy pass
        val earnedOnly = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to true, "earnedOnly" to true, "earnedAdded" to 5),
        )
        assertEquals(QuizResponseAction.EARNED_NEED_SPEND, earnedOnly)

        val needSpend = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to false, "needSpend" to true, "earnedAdded" to 5),
        )
        assertEquals(QuizResponseAction.EARNED_NEED_SPEND, needSpend)

        // If server also returns spendUntil > 0 or passActive=true -> release now
        val withSpendPass = QuizAntiDoubleTapPolicy.classifyResponse(
            submittedQuestionId = "q-10",
            currentQuestionId = "q-10",
            res = mapOf("ok" to true, "correct" to true, "unlocked" to false, "spendUntil" to 9_999_999_999_000L),
        )
        assertEquals(QuizResponseAction.RELEASE_NOW, withSpendPass)
    }

    @Test
    fun companionReleasePolicyNeverReopensTargetWhenAnotherLockRemains() {
        assertFalse(CompanionReleasePolicy.shouldReopenTarget("tv.danmaku.bili", hasOpenOtherLock = true, neverLock = false))
        assertFalse(CompanionReleasePolicy.shouldReopenTarget("com.bbk.launcher2", hasOpenOtherLock = false, neverLock = true))
        assertFalse(CompanionReleasePolicy.shouldReopenTarget(null, hasOpenOtherLock = false, neverLock = false))
        assertTrue(CompanionReleasePolicy.shouldReopenTarget("tv.danmaku.bili", hasOpenOtherLock = false, neverLock = false))
    }

    @Test
    fun companionReleaseTrackerFiresOnceOnLockToReleaseTransitionOnly() {
        val tracker = CompanionReleaseTracker()

        // Without any prior companion lock, repeated release=true ticks (e.g. hardCapDowngrade) must not fire
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))

        // Companion enforces a lock -> transition to release=true fires exactly once
        assertFalse(tracker.shouldReleaseOnPlan(locked = true, releaseFlag = false, hasActiveCompanionLock = true))
        assertTrue(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = true))
        // Subsequent release=true ticks must NOT fire again (even if hasActiveCompanionLock wasn't cleared)
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = true))
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))

        // Explicit unlock consumes pending release so a later /plan release=true tick does not re-fire
        tracker.onEnforced()
        tracker.onExplicitRelease()
        assertFalse(tracker.shouldReleaseOnPlan(locked = false, releaseFlag = true, hasActiveCompanionLock = false))
    }

    @Test
    fun boutSwitchBufferGrants150SecondsOncePerBoutScope() {
        val buffer = BoutSwitchBuffer()
        assertEquals(150_000L, BoutSwitchBuffer.BUFFER_DURATION_MS)

        var elapsed = 10_000L
        assertTrue(buffer.canTrigger("bout-1", elapsed))
        assertFalse(buffer.isActive(elapsed))

        // Trigger once for bout-1
        assertTrue(buffer.trigger("bout-1", elapsed))
        assertTrue(buffer.isActive(elapsed))
        assertEquals(150, buffer.remainingSeconds(elapsed))

        // While active, neither bout-1 nor another bout can trigger concurrently
        assertFalse(buffer.canTrigger("bout-1", elapsed + 30_000L))
        assertFalse(buffer.canTrigger("bout-2", elapsed + 30_000L))
        assertTrue(buffer.isActive(elapsed + 149_999L))
        assertEquals(1, buffer.remainingSeconds(elapsed + 149_999L))

        // At 150_000ms, buffer expires cleanly
        elapsed += 150_000L
        assertFalse(buffer.isActive(elapsed))
        assertEquals(0, buffer.remainingSeconds(elapsed))

        // Same bout-1 CANNOT re-trigger (max 1 per bout)
        assertFalse(buffer.canTrigger("bout-1", elapsed))
        assertFalse(buffer.trigger("bout-1", elapsed))

        // A new bout-2 CAN trigger once
        assertTrue(buffer.canTrigger("bout-2", elapsed))
        assertTrue(buffer.trigger("bout-2", elapsed))

        // Restoring persisted scopes prevents replay across process restarts
        val restored = BoutSwitchBuffer()
        restored.restoreUsedScopes(buffer.usedScopesSnapshot())
        assertFalse(restored.canTrigger("bout-1", elapsed + 200_000L))
        assertFalse(restored.canTrigger("bout-2", elapsed + 200_000L))
        assertTrue(restored.canTrigger("bout-3", elapsed + 200_000L))
    }
}
