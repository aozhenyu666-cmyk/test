package com.yishou.app.stats

import com.yishou.app.settings.AppPrefs
import com.yishou.app.window.WindowRules
import org.junit.Assert.assertEquals
import org.junit.Test

class DayStatsTest {

    @Test
    fun accumulatesAndRoundTrips() {
        val d = DayStats("2026-10-09")
            .plusUsage("判定", true, 100, 20, 1500, 800)
            .plusUsage("判定", false, 0, 0, 30000, 900)
            .plusUsage("识图", true, 900, 200, 4000, 100)
            .plusEvent(DayStats.GATE_SHOWN)
            .plusEvent(DayStats.GATE_SHOWN)
        assertEquals(KindStats(2, 1, 100, 20, 31500, 1700), d.kinds["判定"])
        assertEquals(1220L, d.total.tokens)
        assertEquals(2, d.event(DayStats.GATE_SHOWN))
        assertEquals(15750L, d.kinds["判定"]!!.avgMillis)
        assertEquals(d, DayStats.fromJson(d.toJson(), "x"))
        assertEquals(DayStats("2026-10-09"), DayStats.fromJson("坏的", "2026-10-09"))
    }

    @Test
    fun windowRules() {
        val p = AppPrefs(windowAllowed = setOf("com.fenbi"), windowShortApps = setOf("com.tencent.mm"))
        val sys = setOf("com.bbk.launcher2")
        assertEquals(WindowRules.Decision.ALLOW, WindowRules.decide("com.fenbi", p, sys, 0))
        assertEquals(WindowRules.Decision.ALLOW, WindowRules.decide("com.bbk.launcher2", p, sys, 0))
        assertEquals(WindowRules.Decision.ALLOW, WindowRules.decide("com.android.settings", p, sys, 0))
        assertEquals(WindowRules.Decision.SHORT, WindowRules.decide("com.tencent.mm", p, sys, 0))
        assertEquals(WindowRules.Decision.BLOCK, WindowRules.decide("com.taobao", p, sys, 0))
        assertEquals(WindowRules.Decision.ALLOW, WindowRules.decide("com.taobao", p.copy(windowPauseUntil = 10), sys, 5))
        assertEquals(WindowRules.Decision.BLOCK, WindowRules.decide("com.taobao", p.copy(windowPauseUntil = 10), sys, 10))
        assertEquals(WindowRules.Decision.ALLOW, WindowRules.decide("com.taobao", p.copy(windowStrict = false), sys, 0))
    }
}
