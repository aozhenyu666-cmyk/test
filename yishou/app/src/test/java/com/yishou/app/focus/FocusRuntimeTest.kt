package com.yishou.app.focus

import org.junit.Assert.*
import org.junit.Test

class FocusRuntimeTest {
    private val session = FocusEngine.start(FocusPlan("刷题", "", 10, 3, 1), 1000)!!
    @Test fun focusDecisionRunsBeforePassAndPartnerAllowancesEvenAtRest() {
        val run = FocusRun(session, setOf("gpt"), setOf("wx"))
        fun gate(pkg: String, now: Long, hasPass: Boolean): String {
            val decision = FocusRuntime.gate(run, now, pkg, "self", setOf("home"), setOf("settings"))
            if (decision != null) return decision.name
            return if (hasPass) "PASS" else "ORDINARY_GATE"
        }
        listOf(1001L, 181001L).forEach { now ->
            assertEquals("ALLOW", gate("gpt", now, false))
            assertEquals("ALLOW", gate("wx", now, false))
            assertEquals("ALLOW", gate("self", now, false))
            assertEquals("ALLOW", gate("settings", now, false))
            assertEquals("BLOCK", gate("video", now, true))
            assertEquals("BLOCK", gate("unwatched", now, false))
        }
        assertEquals("PASS", gate("video", session.endsAt, true))
    }
    @Test fun zeroRestAndRetuneAndTruncatedLastWorkAllHaveRoundEnds() {
        val tuned = FocusEngine.retune(session, 3, 0, "改一下", 121000)!!
        assertEquals(listOf(1), FocusRuntime.completedRounds(tuned, 121000))
        assertEquals(listOf(1, 2), FocusRuntime.completedRounds(tuned, 301000))
        assertEquals(listOf(1, 2, 3, 4), FocusRuntime.completedRounds(tuned, tuned.endsAt))
    }
}
