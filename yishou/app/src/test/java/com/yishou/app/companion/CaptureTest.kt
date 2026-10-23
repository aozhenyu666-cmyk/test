package com.yishou.app.companion

import com.yishou.app.companion.core.CaptureHolder
import com.yishou.app.companion.core.CaptureLease
import com.yishou.app.companion.core.FrameCadence
import com.yishou.app.companion.core.FrameDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 投屏只能有一个主人；关键帧不能密集调用模型。 */
class CaptureTest {

    @Test
    fun `空闲时谁都能拿`() {
        val lease = CaptureLease()
        assertEquals(CaptureHolder.NONE, lease.holder())
        assertNull(lease.acquire(CaptureHolder.COMPANION))
        assertEquals(CaptureHolder.COMPANION, lease.holder())
    }

    @Test
    fun `同一个主人重复拿算成功`() {
        val lease = CaptureLease()
        assertNull(lease.acquire(CaptureHolder.SCROLL))
        assertNull(lease.acquire(CaptureHolder.SCROLL))
        assertEquals(CaptureHolder.SCROLL, lease.holder())
    }

    @Test
    fun `别人占着要说清是谁`() {
        val lease = CaptureLease()
        lease.acquire(CaptureHolder.SCREEN_LOOK)
        val reason = lease.acquire(CaptureHolder.COMPANION)
        assertNotNull(reason)
        assertTrue(reason!!.contains("看屏"))
        assertEquals(CaptureHolder.SCREEN_LOOK, lease.holder())
    }

    @Test
    fun `不是自己的账不误清`() {
        val lease = CaptureLease()
        lease.acquire(CaptureHolder.SCROLL)
        assertFalse(lease.release(CaptureHolder.COMPANION))
        assertEquals(CaptureHolder.SCROLL, lease.holder())
        assertTrue(lease.release(CaptureHolder.SCROLL))
        assertEquals(CaptureHolder.NONE, lease.holder())
    }

    @Test
    fun `授权被系统收回时整本账清空`() {
        val lease = CaptureLease()
        lease.acquire(CaptureHolder.SCREEN_LOOK)
        lease.reset()
        assertEquals(CaptureHolder.NONE, lease.holder())
        assertNull(lease.blockReason(CaptureHolder.COMPANION))
    }

    @Test
    fun `一模一样的帧不重复留`() {
        val c = FrameCadence(minIntervalMs = 1_000, maxPerWindow = 10)
        assertEquals(FrameDecision.KEEP, c.offer(42L, 0))
        assertEquals(FrameDecision.SAME_AS_LAST, c.offer(42L, 5_000))
        assertEquals(FrameDecision.KEEP, c.offer(43L, 5_000))
    }

    @Test
    fun `间隔太短的帧丢掉`() {
        val c = FrameCadence(minIntervalMs = 1_000, maxPerWindow = 10)
        assertEquals(FrameDecision.KEEP, c.offer(1L, 0))
        assertEquals(FrameDecision.TOO_SOON, c.offer(2L, 300))
        assertEquals(FrameDecision.KEEP, c.offer(3L, 1_200))
    }

    @Test
    fun `一分钟里留够了就不再留`() {
        val c = FrameCadence(minIntervalMs = 100, maxPerWindow = 3)
        assertEquals(FrameDecision.KEEP, c.offer(1L, 0))
        assertEquals(FrameDecision.KEEP, c.offer(2L, 200))
        assertEquals(FrameDecision.KEEP, c.offer(3L, 400))
        assertEquals(FrameDecision.TOO_MANY, c.offer(4L, 600))
        // 窗口滑过去以后又能留
        assertEquals(FrameDecision.KEEP, c.offer(5L, 60_100))
    }

    @Test
    fun `时钟倒退不会把闸门卡死`() {
        val c = FrameCadence(minIntervalMs = 1_000, maxPerWindow = 3)
        assertEquals(FrameDecision.KEEP, c.offer(1L, 10_000))
        assertEquals(FrameDecision.KEEP, c.offer(2L, 5_000))
    }
}
