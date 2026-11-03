package com.dshdriver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import com.dshdriver.core.BlockRules.Decision.*

class BlockRulesTest {
    @Test fun selectedPackagesAndExpiry() {
        val r = BlockRules(setOf("bili"), mapOf("bili" to 600L))
        assertEquals(Allow, r.decide("camera", 100))
        assertEquals(Block, BlockRules(setOf("bili")).decide("bili", 100))
        assertEquals(Allow, r.decide("bili", 599))
        assertEquals(Block, r.decide("bili", 600))
        assertEquals(Block, r.decide("bili", 601))
        assertEquals(Allow, r.decide(null, 700))
    }
    @Test fun exemptPackagesWinEvenWhenSelected() {
        val protected = setOf("com.dshdriver.app", "ime", "android", "com.android.systemui", "com.android.settings", "oem.launcher")
        val r = BlockRules(protected, inputMethods = setOf("ime"), systemUi = protected - "ime" - "com.dshdriver.app")
        protected.forEach { assertEquals(Allow, r.decide(it, 100), it) }
    }
}
