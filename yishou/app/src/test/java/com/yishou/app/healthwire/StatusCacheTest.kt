package com.yishou.app.healthwire

import com.yishou.app.link.ZongkongStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T04 的核心证据：无障碍事件线程读总控状态时**只能读缓存**，刷新在后台且带超时；
 * 刷新超期 → 失联态 → 按本地规则继续走（不是当成 lockdown=false 去放行）。
 */
class StatusCacheTest {

    private fun status(lockdown: Boolean = false, blocks: Set<String> = emptySet()) =
        ZongkongStatus(lockdown = lockdown, strict = blocks.isNotEmpty(), blocks = blocks)

    private fun snap(s: ZongkongStatus?, at: Long, ok: Boolean = true) =
        StatusSnapshot(s, ok = ok, atWall = at)

    @Test
    fun `从没读到过就是失联态 不是没在严管`() {
        val c = StatusCache(ttlMs = 30_000, lostAfterMs = 8_000)
        val r = c.read(1_000)
        assertEquals(StatusMode.LOST, r.mode)
        assertNull(r.status)
        assertFalse(r.lockdown)
        assertTrue(r.wantsRefresh)
        assertTrue(r.reason.isNotBlank())
    }

    @Test
    fun `新鲜快照可以直接用`() {
        val c = StatusCache(ttlMs = 30_000, lostAfterMs = 8_000)
        c.finishFetch(snap(status(lockdown = true), at = 1_000))
        val r = c.read(5_000)
        assertEquals(StatusMode.FRESH, r.mode)
        assertTrue(r.lockdown)
        assertEquals(4_000L, r.ageMs)
        assertFalse(r.wantsRefresh)
    }

    @Test
    fun `过期但没到失联预算时仍可用 同时要求刷新`() {
        val c = StatusCache(ttlMs = 1_000, lostAfterMs = 8_000)
        c.finishFetch(snap(status(blocks = setOf("com.x")), at = 1_000))
        val r = c.read(3_000)
        assertEquals(StatusMode.STALE, r.mode)
        assertTrue(r.bounces("com.x"))
        assertFalse(r.bounces("com.y"))
        assertTrue(r.wantsRefresh)
    }

    @Test
    fun `超过失联预算后清空并判失联`() {
        val c = StatusCache(ttlMs = 1_000, lostAfterMs = 2_000)
        c.finishFetch(snap(status(lockdown = true), at = 0))
        val r = c.read(10_000)
        assertEquals(StatusMode.LOST, r.mode)
        assertNull(r.status)
        // 关键：失联时绝不能因为「旧快照说 lockdown=true」就永久拦住，也不能变成 false 放行
        assertFalse(r.lockdown)
    }

    @Test
    fun `后台刷新超期时事件线程立刻拿到失联结论`() {
        val c = StatusCache(ttlMs = 30_000, lostAfterMs = 5_000)
        c.finishFetch(snap(status(lockdown = true), at = 0))
        assertTrue(c.beginFetch(1_000))
        // 还没超期：用的是已有快照
        assertEquals(StatusMode.FRESH, c.read(2_000).mode)
        // 超期：立刻失联，不等后台线程
        val late = c.read(7_000)
        assertEquals(StatusMode.LOST, late.mode)
        assertNull(late.status)
        assertTrue(late.reason.contains("没有结果"))
    }

    @Test
    fun `单飞 在途刷新没超期时不重复发起`() {
        val c = StatusCache(ttlMs = 1_000, lostAfterMs = 10_000)
        assertTrue(c.beginFetch(1_000))
        assertFalse(c.beginFetch(1_500))
        // 超期之后可以重新发起
        assertTrue(c.beginFetch(20_000))
    }

    @Test
    fun `读取失败会清掉旧快照 不拿旧数据当现状`() {
        val c = StatusCache(ttlMs = 30_000, lostAfterMs = 8_000)
        c.finishFetch(snap(status(lockdown = true), at = 0))
        assertEquals(StatusMode.FRESH, c.read(1_000).mode)
        c.finishFetch(snap(null, at = 2_000, ok = false))
        val r = c.read(3_000)
        assertEquals(StatusMode.LOST, r.mode)
        assertNull(r.status)
        assertFalse(r.lockdown)
    }

    @Test
    fun `failFetch 之后是失联 而不是继续用旧值`() {
        val c = StatusCache(ttlMs = 30_000, lostAfterMs = 8_000)
        c.finishFetch(snap(status(), at = 0))
        c.failFetch(1_000, "总控抛异常")
        val r = c.read(1_500)
        assertEquals(StatusMode.LOST, r.mode)
        assertEquals("总控抛异常", r.reason)
    }

    @Test
    fun `事件线程读缓存不做任何跨进程 因此可以反复读`() {
        val c = StatusCache(ttlMs = 30_000, lostAfterMs = 8_000)
        c.finishFetch(snap(status(blocks = setOf("com.tencent.mm")), at = 0))
        val started = System.nanoTime()
        repeat(10_000) {
            val r = c.read(1_000)
            assertTrue(r.mode == StatusMode.FRESH)
        }
        val costMs = (System.nanoTime() - started) / 1_000_000
        // 纯内存读，一万次远小于 1 秒（若这里去做了 IPC，会明显慢）
        assertTrue("一万次读用了 ${costMs}ms", costMs < 1_000)
    }

    @Test
    fun `reset 后回到未知 不保留旧快照`() {
        val c = StatusCache(ttlMs = 30_000, lostAfterMs = 8_000)
        c.finishFetch(snap(status(lockdown = true), at = 0))
        c.reset()
        val r = c.read(1_000)
        assertEquals(StatusMode.LOST, r.mode)
        assertNull(r.status)
    }

    @Test
    fun `失联态下 bounces 为 false 让本地规则接手`() {
        val c = StatusCache(ttlMs = 1_000, lostAfterMs = 1_000)
        c.finishFetch(snap(status(lockdown = true), at = 0))
        val r = c.read(100_000)
        assertEquals(StatusMode.LOST, r.mode)
        assertFalse(r.bounces("com.ss.android.ugc.aweme"))
        assertNotNull(r.reason)
    }
}
