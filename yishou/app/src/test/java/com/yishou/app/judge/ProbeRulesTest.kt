package com.yishou.app.judge

import org.junit.Assert.*
import org.junit.Test

class ProbeRulesTest {
    private fun trace(first: Long?) = ProbeTrace(0, first, 60_000, 0, emptyList(), emptyList(), false)
    @Test fun fastFailureBoundary() {
        assertEquals(ProbeAction.FREEZE_15S, probeAction(trace(2_900), "想法", false))
        assertEquals(ProbeAction.NONE, probeAction(trace(3_000), "想法", false))
        assertEquals(ProbeAction.NONE, probeAction(trace(2_900), "想法", true))
    }
    @Test fun longDelayAndSubstanceBoundary() {
        assertEquals(ProbeAction.NONE, probeAction(trace(40_000), "想法", false))
        assertEquals(ProbeAction.ASK_FRAGMENT, probeAction(trace(40_001), "证据", true))
        assertEquals(ProbeAction.NONE, probeAction(trace(40_001), "甲乙丙丁戊己庚辛壬癸子丑寅卯辰", true))
        assertEquals(ProbeAction.NONE, probeAction(trace(null), "", false))
    }
}
