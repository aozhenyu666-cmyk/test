package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yHealthPlanTest {
    private val plan = A11yHealthPlan(staleEventMs = 60_000L, probeFailLimit = 3)

    @Test
    fun recentEventIsHealthyAndClearsFailures() {
        val r = plan.evaluate(
            now = 100_000,
            connected = true,
            screenOn = true,
            lastEventAt = 90_000,
            probeOk = false,
            previousFailures = 2,
        )
        assertEquals(A11yHealthPlan.Verdict.HEALTHY, r.verdict)
        assertEquals(0, r.failures)
    }

    @Test
    fun screenOffIsNotJudged() {
        val r = plan.evaluate(
            now = 100_000,
            connected = true,
            screenOn = false,
            lastEventAt = 0,
            probeOk = false,
            previousFailures = 2,
        )
        assertEquals(A11yHealthPlan.Verdict.HEALTHY, r.verdict)
        assertEquals(2, r.failures)
    }

    @Test
    fun neverHadAnEventIsUnknown() {
        val r = plan.evaluate(100_000, true, true, 0, false, 0)
        assertEquals(A11yHealthPlan.Verdict.UNKNOWN, r.verdict)
    }

    @Test
    fun staleEventsWithFailedProbeBecomeStaleThenDead() {
        val first = plan.evaluate(100_000, true, true, 10_000, false, 0)
        assertEquals(A11yHealthPlan.Verdict.STALE, first.verdict)
        assertEquals(1, first.failures)

        val second = plan.evaluate(100_000, true, true, 10_000, false, first.failures)
        assertEquals(A11yHealthPlan.Verdict.STALE, second.verdict)
        assertEquals(2, second.failures)

        val third = plan.evaluate(100_000, true, true, 10_000, false, second.failures)
        assertEquals(A11yHealthPlan.Verdict.DEAD, third.verdict)
        assertEquals(3, third.failures)
    }

    @Test
    fun aWorkingTreeClearsTheStaleState() {
        val r = plan.evaluate(100_000, true, true, 10_000, true, 2)
        assertEquals(A11yHealthPlan.Verdict.HEALTHY, r.verdict)
        assertEquals(0, r.failures)
    }

    @Test
    fun disconnectedIsDeadImmediately() {
        val r = plan.evaluate(100_000, false, true, 90_000, true, 0)
        assertEquals(A11yHealthPlan.Verdict.DEAD, r.verdict)
        assertEquals(0, r.failures)
    }
}
