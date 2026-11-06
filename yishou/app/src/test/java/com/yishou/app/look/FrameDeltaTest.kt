package com.yishou.app.look

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 高频看屏的本机过滤：同一屏不重复识图。 */
class FrameDeltaTest {

    private fun frame(w: Int, h: Int, color: Int) = IntArray(w * h) { color }

    @Test fun `same frame is not a change`() {
        val f = frame(72, 160, 0xFF223344.toInt())
        val a = FrameDelta.signature(f, 72, 160)
        val b = FrameDelta.signature(f, 72, 160)
        assertFalse(FrameDelta.changed(a, b))
        assertEquals(0.0, FrameDelta.ratio(a, b), 0.0)
    }

    @Test fun `different frame is a change`() {
        val a = FrameDelta.signature(frame(72, 160, 0xFF000000.toInt()), 72, 160)
        val b = FrameDelta.signature(frame(72, 160, 0xFFFFFFFF.toInt()), 72, 160)
        assertTrue(FrameDelta.changed(a, b))
        assertEquals(1.0, FrameDelta.ratio(a, b), 0.0)
    }

    @Test fun `tiny tweak stays under the threshold`() {
        // 只改一个网格点：1/256 ≈ 0.4%，低于 2%
        val w = 72
        val h = 160
        val base = frame(w, h, 0xFF101010.toInt())
        val tweaked = base.copyOf()
        val pts = FrameDelta.grid(w, h)
        tweaked[pts[1] * w + pts[0]] = 0xFFFFFFFF.toInt()
        val ratio = FrameDelta.ratio(FrameDelta.signature(base, w, h), FrameDelta.signature(tweaked, w, h))
        assertTrue("ratio=$ratio", ratio in 0.0..0.02)
        assertFalse(FrameDelta.changed(FrameDelta.signature(base, w, h), FrameDelta.signature(tweaked, w, h)))
    }

    @Test fun `first frame always counts as a change`() {
        assertTrue(FrameDelta.changed(null, FrameDelta.signature(frame(10, 10, 0), 10, 10)))
        assertTrue(FrameDelta.changed(FrameDelta.signature(frame(10, 10, 0), 10, 10), IntArray(0)))
    }

    @Test fun `size mismatch counts as a change`() {
        val a = FrameDelta.signature(frame(10, 10, 0), 10, 10)
        assertTrue(FrameDelta.changed(a, IntArray(9)))
    }

    @Test fun `grid samples the corners`() {
        val pts = FrameDelta.grid(100, 200)
        assertEquals(16 * 16 * 2, pts.size)
        assertEquals(0, pts[0])
        assertEquals(0, pts[1])
        assertEquals(99, pts[pts.size - 2])
        assertEquals(199, pts[pts.size - 1])
    }

    @Test fun `bad bitmap size yields empty signature`() {
        assertEquals(0, FrameDelta.signature(IntArray(4), 10, 10).size)
    }
}
