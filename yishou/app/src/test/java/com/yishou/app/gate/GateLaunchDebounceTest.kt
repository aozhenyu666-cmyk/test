package com.yishou.app.gate

import org.junit.Assert.*
import org.junit.Test

class GateLaunchDebounceTest {
    @Test fun rapidReentryMustNotInheritPreviousVisitsCooldown() {
        val gate = GateLaunchDebounce()
        gate.onForeground("video")
        gate.launched("video", 1000)
        assertFalse(gate.ready("video", 1100))
        gate.onForeground("home")
        gate.onForeground("video")
        assertTrue(gate.ready("video", 1200))
    }
    @Test fun sameVisitWindowNoiseIsStillDeduplicated() {
        val gate = GateLaunchDebounce()
        gate.onForeground("video")
        gate.launched("video", 1000)
        gate.onForeground("video")
        assertFalse(gate.ready("video", 2999))
        assertTrue(gate.ready("video", 3000))
    }
}

