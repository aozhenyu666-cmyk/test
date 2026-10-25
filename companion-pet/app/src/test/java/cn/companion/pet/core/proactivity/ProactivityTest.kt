package cn.companion.pet.core.proactivity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivityTest {
    @Test
    fun observationPolicyRespectsWhitelistCapAndInterval() {
        val policy = ObservationPolicy(minIntervalMillis = 60_000, dailyCap = 2)
        assertFalse(policy.canObserve(false, true, "com.a", setOf("com.a"), 0, 0, 100_000).allowed)
        assertFalse(policy.canObserve(true, false, "com.a", setOf("com.a"), 0, 0, 100_000).allowed)
        assertFalse(policy.canObserve(true, true, "com.b", setOf("com.a"), 0, 0, 100_000).allowed)
        assertFalse(policy.canObserve(true, true, "com.a", setOf("com.a"), 0, 2, 100_000).allowed)
        assertFalse(policy.canObserve(true, true, "com.a", setOf("com.a"), 90_000, 0, 100_000).allowed)
        assertTrue(policy.canObserve(true, true, "com.a", setOf("com.a"), 0, 0, 100_000).allowed)
    }
}
