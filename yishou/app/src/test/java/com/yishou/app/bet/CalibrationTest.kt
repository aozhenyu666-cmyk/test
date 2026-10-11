package com.yishou.app.bet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    @Test
    fun emptyReport() {
        val r = Calibration.of(emptyList())
        assertNull(r.gap)
        assertTrue(Calibration.verdict(r).startsWith("还没有"))
    }

    @Test
    fun overconfident() {
        val pairs = List(5) { 90 to (it == 0) } + List(5) { 80 to (it < 2) }
        val r = Calibration.of(pairs)
        assertEquals(listOf(80, 90), r.buckets.map { it.confidence })
        assertEquals(40, r.buckets[0].rate)
        assertEquals(3, r.hits)
        assertEquals(55, r.gap)
        assertTrue(Calibration.verdict(r).startsWith("自信过头"))
    }

    @Test
    fun wellCalibratedAndSnap() {
        val pairs = List(10) { 70 to (it < 7) }
        val r = Calibration.of(pairs)
        assertEquals(0, r.gap)
        assertEquals(0.21, r.brier!!, 1e-9)
        assertTrue(Calibration.verdict(r).contains("校得不错"))
        assertEquals(50, Calibration.snap(30))
        assertEquals(80, Calibration.snap(75))
        assertEquals(100, Calibration.snap(100))
    }
}
