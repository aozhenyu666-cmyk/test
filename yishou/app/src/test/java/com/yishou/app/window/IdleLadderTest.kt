package com.yishou.app.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleLadderTest {
    private fun lv(min: Double, pull: Boolean = true) = IdleLadder.level((min * 60_000).toLong(), 3, 1, 2, pull)

    @Test
    fun ladder() {
        assertEquals(0, lv(2.9))
        assertEquals(1, lv(3.0))
        assertEquals(2, lv(4.0))
        assertEquals(2, lv(5.9))
        assertEquals(3, lv(6.0))
        assertTrue(IdleLadder.isPull(lv(6.0)))
        assertEquals(4, lv(8.0))
        assertEquals(2 + IdleLadder.MAX_PULLS, lv(100.0))
        assertEquals(2, lv(100.0, pull = false))
    }
}
