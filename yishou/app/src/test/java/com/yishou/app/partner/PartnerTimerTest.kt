package com.yishou.app.partner

import org.junit.Assert.*
import org.junit.Test

class PartnerTimerTest {
    @Test fun `材料和外部应用不拦 其他应用照常`() {
        val timer = PartnerTimer(10, 100, setOf("self", "voice", "book"))
        assertTrue(timer.allows("book", 101))
        assertFalse(timer.allows("video", 101))
        assertFalse(timer.allows("book", 600_100))
    }

    @Test fun `通知按向上取整显示剩余分钟 到点只收线`() {
        val timer = PartnerTimer(10, 100, setOf("self"))
        assertEquals("伴读中 · 还剩 10 分钟", timer.text(100))
        assertEquals("伴读中 · 还剩 1 分钟", timer.text(600_099))
        assertTrue(timer.expired(600_100))
        assertEquals("到点了，回来收线", timer.text(600_100))
    }

    @Test fun `长期离开更新通知 跨多个应用只算一次 回来恢复`() {
        val timer = PartnerTimer(10, 0, setOf("self", "book"))
        timer.foreground("self", 0)
        timer.foreground("video", 1_000)
        assertEquals("伴读中 · 还剩 8 分钟", timer.text(121_000))
        timer.foreground("game", 121_001)
        assertEquals("离开 2 分钟了，回去吗？", timer.text(121_001))
        assertEquals(1, timer.presence.leaves)
        timer.foreground("book", 181_000)
        assertEquals("伴读中 · 还剩 7 分钟", timer.text(181_000))
        timer.finish(200_000)
        assertEquals(20_000L, timer.presence.presentMs)
    }

    @Test fun `到点之后的观察不多算时长`() {
        val timer = PartnerTimer(10, 0, setOf("self"))
        timer.foreground("self", 0)
        timer.finish(700_000)
        assertEquals(600_000L, timer.presence.presentMs)
    }

    @Test fun `未开观察或中途撤回不会伪造在场与离开`() {
        val timer = PartnerTimer(10, 0, setOf("self", "book"))
        timer.foreground(null, 0)
        timer.text(150_000)
        assertEquals(0L, timer.presence.presentMs)
        assertEquals(0, timer.presence.leaves)
        timer.foreground("book", 150_000)
        timer.foreground(null, 160_000)
        timer.finish(300_000)
        assertEquals(10_000L, timer.presence.presentMs)
        assertEquals(0, timer.presence.leaves)
    }
}
