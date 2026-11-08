package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.*
import org.junit.Test

class AutoRestartPolicyTest {
    private val now = 10_000_000L
    private fun delay(n: Int, kind: FailureKind = FailureKind.KILLED, code: Int = 137, up: Long? = 60_001L) =
        SafePolicy.autoRestartDelay(kind, code, up, List(n) { now - 1 - it }, now)

    @Test fun backsOffAndNeverGivesUp() {
        assertEquals(5_000L, delay(0))
        assertEquals(15_000L, delay(1))
        assertEquals(45_000L, delay(2))
        assertEquals(120_000L, delay(3))
        assertEquals(300_000L, delay(4))
        assertEquals(300_000L, delay(40))
    }

    @Test fun countsOnlyTheLastHalfHour() {
        val old = List(10) { now - SafePolicy.RESTART_WINDOW_MS - it }
        assertEquals(5_000L, SafePolicy.autoRestartDelay(FailureKind.KILLED, 137, 60_001L, old, now))
    }

    @Test fun coversOutsideKillsOfAnyCodeAndLateCrashes() {
        assertEquals(5_000L, delay(0, code = 0))
        assertEquals(5_000L, delay(0, code = 143))
        assertEquals(5_000L, delay(0, kind = FailureKind.CRASHED_LATE, code = 1))
        assertEquals(5_000L, delay(0, up = 20_001L))
    }

    @Test fun leavesStartupProblemsToSafeMode() {
        for (up in listOf(null, 0L, 20_000L)) assertNull(delay(0, up = up))
        for (kind in FailureKind.entries.filter { it != FailureKind.KILLED && it != FailureKind.CRASHED_LATE }) {
            assertNull(delay(0, kind = kind, code = 1))
        }
    }
}
