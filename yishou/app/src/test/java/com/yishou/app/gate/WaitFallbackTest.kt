package com.yishou.app.gate

import org.junit.Assert.*
import org.junit.Test
import com.yishou.app.FakeDao
import kotlinx.coroutines.test.runTest

class WaitFallbackTest {
    @Test fun leavingAfterTimerFinishesStillInvalidatesWait() {
        var now = 0L
        val wait = WaitFallback { now }
        wait.start()
        now = WAIT_MS
        assertTrue(wait.done())
        wait.leave()
        assertFalse(wait.done())
        assertFalse(wait.consume())
    }

    @Test fun waitingMustBeContinuousAndCannotBeConsumedTwice() {
        var now = 1_000L
        val wait = WaitFallback { now }
        assertFalse(wait.done())
        wait.start()
        now += WAIT_MS - 1
        assertFalse(wait.done())
        assertEquals(1L, wait.remainingMs())
        wait.leave()
        now += 2
        assertFalse(wait.done())
        wait.start()
        now += WAIT_MS
        assertTrue(wait.done())
        assertTrue(wait.consume())
        assertFalse(wait.consume())
    }

    @Test fun issuerRequiresCompletedWaitAndGrantsOnlyFiveMinutes() = runTest {
        var mono = 100L
        val wait = WaitFallback { mono }
        val dao = FakeDao()
        val issuer = PassIssuer(dao) { 1_000L }
        wait.start()
        assertNull(issuer.issueAfterWait("游戏", wait))
        mono += WAIT_MS
        val pass = issuer.issueAfterWait("游戏", wait)!!
        assertEquals(1_000L + WAIT_PASS_MS, pass.endAt)
        assertNull(issuer.issueAfterWait("游戏", wait))
    }
}
