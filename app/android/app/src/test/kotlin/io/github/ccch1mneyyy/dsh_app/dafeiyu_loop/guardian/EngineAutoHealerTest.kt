package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineAutoHealerTest {
    @Test fun healthyUptimeKillsAlwaysHealWithExponentialBackoff() {
        val t0 = 1_000_000L
        assertEquals(5_000L, EngineAutoHealer.nextRestartDelayMs(137, 120_000L, emptyList(), t0))
        assertEquals(15_000L, EngineAutoHealer.nextRestartDelayMs(137, 120_000L, listOf(t0 - 20_000L), t0))
        assertEquals(45_000L, EngineAutoHealer.nextRestartDelayMs(137, 120_000L, listOf(t0 - 40_000L, t0 - 20_000L), t0))
        assertEquals(
            300_000L,
            EngineAutoHealer.nextRestartDelayMs(
                137,
                120_000L,
                listOf(t0 - 50_000L, t0 - 40_000L, t0 - 30_000L, t0 - 20_000L, t0 - 10_000L),
                t0,
            ),
        )
    }

    @Test fun repeatedEarlyBootCrashesStopAutoHealAfterFourStrikes() {
        val t0 = 1_000_000L
        assertEquals(15_000L, EngineAutoHealer.nextRestartDelayMs(1, 5_000L, listOf(t0 - 10_000L), t0))
        assertNull(
            EngineAutoHealer.nextRestartDelayMs(
                1,
                null,
                listOf(t0 - 80_000L, t0 - 60_000L, t0 - 40_000L, t0 - 20_000L),
                t0,
            ),
        )
    }
}
