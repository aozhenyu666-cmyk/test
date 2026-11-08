package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchdogPolicyTest {
    private val policy = WatchdogPolicy(staleHeartbeatMs = 120_000L)

    @Test
    fun switchedOffNeverChecks() {
        assertEquals(WatchdogPolicy.Action.NONE, policy.decide(false, false, null))
        assertEquals(WatchdogPolicy.Action.NONE, policy.decide(false, true, 999_999L))
    }

    @Test
    fun missingServiceChecksImmediately() {
        assertEquals(WatchdogPolicy.Action.CHECK, policy.decide(true, false, 0L))
    }

    @Test
    fun freshHeartbeatMeansTheMainProcessIsAlive() {
        assertEquals(WatchdogPolicy.Action.NONE, policy.decide(true, true, 10_000L))
        assertEquals(WatchdogPolicy.Action.NONE, policy.decide(true, true, 120_000L))
    }

    @Test
    fun staleOrMissingHeartbeatChecks() {
        assertEquals(WatchdogPolicy.Action.CHECK, policy.decide(true, true, 120_001L))
        assertEquals(WatchdogPolicy.Action.CHECK, policy.decide(true, true, null))
    }
}
