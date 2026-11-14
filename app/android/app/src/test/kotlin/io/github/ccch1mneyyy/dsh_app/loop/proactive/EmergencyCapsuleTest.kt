package io.github.ccch1mneyyy.dsh_app.loop.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyCapsuleTest {
    @Test
    fun formatRemainingDisplaysMinutesAndSecondsUntilExpiry() {
        val now = 1_700_000_000_000L
        val until = now + 180_000L
        assertTrue(EmergencyCountdown.isActive(until, now))
        assertEquals("急事(回紧急消息) 03:00", EmergencyCountdown.formatRemaining(until, "回紧急消息", now))
        assertEquals("急事放行 01:05", EmergencyCountdown.formatRemaining(now + 65_000L, null, now))
    }

    @Test
    fun formatRemainingTruncatesLongReasonAndReturnsNullOnExpiry() {
        val now = 1_700_000_000_000L
        val longText = "非常紧急需要处理的一件事情超越十二字"
        val formatted = EmergencyCountdown.formatRemaining(now + 10_000L, longText, now)!!
        assertTrue(formatted.startsWith("急事("))
        assertTrue(formatted.endsWith(" 00:10"))

        assertFalse(EmergencyCountdown.isActive(now, now))
        assertNull(EmergencyCountdown.formatRemaining(now, "回紧急消息", now))
        assertNull(EmergencyCountdown.formatRemaining(now - 1_000L, null, now))
    }
}
