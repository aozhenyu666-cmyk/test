package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian

import org.junit.Assert.assertEquals
import org.junit.Test

/** 引擎保活的频率规矩：在跑不拉；没装不拉；INSTALLED 一分钟一次；ERROR 十分钟一次、一小时最多三次。 */
class EngineKeepPolicyTest {
    private val t0 = 10_000_000L

    @Test fun busyOrNotInstalledNeverStarts() {
        for (p in listOf("running", "starting", "installing", "stopping")) {
            assertEquals(EngineKeepPolicy.Decision.BUSY, EngineKeepPolicy.decide(p, t0, 0L, emptyList()))
        }
        assertEquals(EngineKeepPolicy.Decision.NOT_INSTALLED, EngineKeepPolicy.decide("notInstalled", t0, 0L, emptyList()))
    }

    @Test fun installedStartsAtMostOncePerMinute() {
        assertEquals(EngineKeepPolicy.Decision.START, EngineKeepPolicy.decide("installed", t0, 0L, emptyList()))
        assertEquals(EngineKeepPolicy.Decision.COOLDOWN, EngineKeepPolicy.decide("installed", t0, t0 - 30_000L, emptyList()))
        assertEquals(EngineKeepPolicy.Decision.START, EngineKeepPolicy.decide("installed", t0, t0 - 61_000L, emptyList()))
    }

    @Test fun errorIsRetriedButCapped() {
        assertEquals(EngineKeepPolicy.Decision.START, EngineKeepPolicy.decide("error", t0, 0L, emptyList()))
        // 十分钟内拉过一次：等
        assertEquals(EngineKeepPolicy.Decision.COOLDOWN, EngineKeepPolicy.decide("error", t0, t0 - 5 * 60_000L, listOf(t0 - 5 * 60_000L)))
        assertEquals(EngineKeepPolicy.Decision.START, EngineKeepPolicy.decide("error", t0, t0 - 11 * 60_000L, listOf(t0 - 11 * 60_000L)))
        // 一小时三次：停手，交给他（失败卡 / 安全模式）
        val three = listOf(t0 - 50 * 60_000L, t0 - 35 * 60_000L, t0 - 20 * 60_000L)
        assertEquals(EngineKeepPolicy.Decision.ERROR_CAP, EngineKeepPolicy.decide("error", t0, t0 - 20 * 60_000L, three))
        // 一小时以前的不算
        val old = listOf(t0 - 3 * 60 * 60_000L, t0 - 2 * 60 * 60_000L, t0 - 61 * 60_000L)
        assertEquals(EngineKeepPolicy.Decision.START, EngineKeepPolicy.decide("error", t0, t0 - 61 * 60_000L, old))
    }
}
