package com.zongkong.sentinel

import org.junit.Assert.*
import org.junit.Test

class FaultPresentationTest {
    @Test fun failurePresentsWithoutOpeningABlockedApp() {
        val policy = FaultPresentation()
        assertTrue(policy.shouldPresent(true, true, true))
        assertFalse(policy.shouldPresent(true, true, true))
    }
    @Test fun screenOffDefersAndRecoveryRearmsNextIncident() {
        val policy = FaultPresentation()
        assertFalse(policy.shouldPresent(true, true, false))
        assertTrue(policy.shouldPresent(true, true, true))
        assertFalse(policy.shouldPresent(false, true, true))
        assertTrue(policy.shouldPresent(true, true, true))
    }
    @Test fun emergencyAndReleaseSuppressThePage() {
        val policy = FaultPresentation()
        assertFalse(policy.shouldPresent(true, false, true))
        assertTrue(policy.shouldPresent(true, true, true))
    }
}

