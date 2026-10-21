package com.moling.app.overlay

import org.junit.Assert.*
import org.junit.Test

class SessionTimingTest {
    private fun e(at: Long, pkg: String) = SessionTiming.Event(at, pkg)
    @Test fun sumsOnlyActorIntervalsAndCountsLongLeavesOnce() {
        val result = SessionTiming.measure("actor", 0, 600_000, listOf(e(10_000, "actor"), e(70_000, "other"), e(250_000, "actor"), e(310_000, "other")))
        assertEquals(120_000L, result.presentMs)
        assertEquals(2, result.leaves)
    }
    @Test fun shortLeaveAndInitialWaitAreNotCounted() {
        val result = SessionTiming.measure("actor", 0, 400_000, listOf(e(180_000, "actor"), e(240_000, ""), e(300_000, "actor")))
        assertEquals(160_000L, result.presentMs)
        assertEquals(0, result.leaves)
    }
    @Test fun samePackageActivityTransitionsDontResetDuration() {
        val result = SessionTiming.measure("actor", 0, 180_000, listOf(e(0, "actor"), e(30_000, "actor"), e(90_000, "actor")))
        assertEquals(180_000L, result.presentMs)
        assertEquals(0, result.leaves)
    }
    @Test fun emptyHistoryIsUnknownRatherThanAssumedPresent() {
        assertEquals(SessionTiming.Result(0, 0), SessionTiming.measure("actor", 0, 60_000, emptyList()))
    }
}
