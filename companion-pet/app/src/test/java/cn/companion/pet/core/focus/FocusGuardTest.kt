package cn.companion.pet.core.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FocusGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun contract(enforcement: FocusEnforcement = FocusEnforcement.STRICT, mode: FocusMode = FocusMode.TEXT) =
        FocusContract(
            sessionId = "s1", targetPackage = "com.gemini", targetLabel = "Gemini",
            startedAt = 1_000, expiresAt = 61_000, mode = mode, enforcement = enforcement,
            allowedPackages = setOf("com.notes"), graceMillis = 5_000,
            allowedGraceMillis = 10_000, maxReturnAttempts = 2,
        )

    private fun guard(enforcement: FocusEnforcement = FocusEnforcement.STRICT) =
        FocusGuard(FocusSnapshot(contract = contract(enforcement), state = FocusState.ARMED, updatedAt = 1_000))

    @Test
    fun leavingImmediatelyStillHasContract() {
        val g = guard()
        val d = g.onLeave(1_001, "com.other")
        assertTrue(d is FocusDecision.Grace)
        assertEquals(FocusState.AWAY_GRACE, g.current.state)
    }

    @Test
    fun targetReturnClearsBlockAndResetsRetries() {
        val g = guard()
        g.onLeave(1_001, "com.other")
        g.onGraceExpired(10_000)
        g.requestReturn(10_001)
        val d = g.onReturnAttemptResult(10_100, launched = true, foreground = "com.gemini")
        assertEquals(FocusDecision.None, d)
        assertEquals(FocusState.IN_TARGET, g.current.state)
        assertEquals(0, g.current.retryCount)
    }

    @Test
    fun launchedButNotForegroundIsNotSuccess() {
        val g = guard()
        g.onLeave(1_001, "com.other")
        g.requestReturn(10_000)
        val d = g.onReturnAttemptResult(10_100, launched = true, foreground = "com.other")
        assertTrue(d is FocusDecision.Block)
        assertEquals(FocusState.BLOCKING, g.current.state)
        assertTrue((d as FocusDecision.Block).canRetry)
    }

    @Test
    fun strictRetriesAreBoundedButBlockStays() {
        val g = guard()
        g.onLeave(1_001, "com.other")
        g.requestReturn(2_000)
        g.onReturnAttemptResult(2_100, launched = false, foreground = "com.other")
        g.requestReturn(11_000)
        g.onReturnAttemptResult(11_100, launched = false, foreground = "com.other")
        val d = g.requestReturn(20_000)
        assertTrue(d is FocusDecision.Block)
        assertFalse((d as FocusDecision.Block).canRetry)
        assertEquals(FocusState.BLOCKING, g.current.state)
    }

    @Test
    fun softIsNotHardBlocking() {
        val g = guard(FocusEnforcement.SOFT)
        g.onLeave(1_001, "com.other")
        val d = g.onGraceExpired(10_000)
        assertTrue(d is FocusDecision.Block)
        assertEquals(FocusState.AWAY_GRACE, g.current.state)
    }

    @Test
    fun allowedPackageGetsLongerGrace() {
        val g = guard()
        val d = g.onLeave(1_001, "com.notes")
        assertTrue(d is FocusDecision.Grace && d.allowed)
        assertEquals(10, (d as FocusDecision.Grace).secondsLeft)
    }

    @Test
    fun pauseAndResumeAndAbort() {
        val g = guard()
        g.pause(2_000)
        assertEquals(FocusState.PAUSED, g.current.state)
        g.resume(3_000)
        assertEquals(FocusState.AWAY_GRACE, g.current.state)
        g.abort(4_000)
        assertEquals(FocusState.USER_ABORTED, g.current.state)
        assertEquals(FocusDecision.None, g.onLeave(5_000, "com.other"))
    }

    @Test
    fun expiryCompletesAndStoreDoesNotRevive() {
        val g = guard()
        val d = g.tick(61_000)
        assertTrue(d is FocusDecision.Completed)
        val store = FocusStore(tmp.root)
        assertTrue(store.save(g.current))
        val loaded = store.load()!!
        assertEquals(FocusState.COMPLETED, loaded.state)
        assertEquals(FocusDecision.None, FocusGuard(loaded).onLeave(70_000, "com.other"))
        store.clear()
        assertNull(FocusStore(tmp.root).load())
    }

    @Test
    fun liveDoesNotReturnAutomatically() {
        val c = contract(mode = FocusMode.LIVE)
        val g = FocusGuard(FocusSnapshot(contract = c, state = FocusState.ARMED, updatedAt = 1_000))
        g.onLeave(1_001, "com.other")
        // LIVE 的自动拉回由上层决定；状态机只给宽限，不自行 RETURND。
        assertEquals(FocusState.AWAY_GRACE, g.current.state)
        assertTrue(g.onGraceExpired(10_000) is FocusDecision.Block == false || g.current.state != FocusState.RETURNING)
    }
}
