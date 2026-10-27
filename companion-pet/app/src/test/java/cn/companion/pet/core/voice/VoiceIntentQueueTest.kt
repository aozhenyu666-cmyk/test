package cn.companion.pet.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 攒着想说的话：带来源、会过期、说完划掉、被拒就换一件。 */
class VoiceIntentQueueTest {
    private val hour = 3_600_000L

    @Test
    fun `同一件事只入队一次`() {
        val q = VoiceIntentQueue()
        assertNotNull(q.enqueue("own_life", "我今天读到时间尺度那段", listOf("e1"), now = 0L))
        assertNull("重复内容不得再次入队", q.enqueue("own_life", "我今天读到时间尺度那段", listOf("e2"), now = 0L))
    }

    @Test
    fun `过期的意图不再被挑中`() {
        val q = VoiceIntentQueue()
        q.enqueue("own_life", "过期的事", listOf("e1"), now = 0L, ttlMillis = hour)
        assertNull(q.next(now = 2 * hour) { false })
    }

    @Test
    fun `说完就划掉，同一件事不会说第二遍`() {
        val q = VoiceIntentQueue()
        val i = q.enqueue("own_life", "只说一次的事", listOf("e1"), now = 0L)!!
        q.markSpoken(i.id, now = 1L)
        assertNull("已说过的不得再被挑中", q.next(now = 2L) { false })
    }

    @Test
    fun `和最近说过的太像时跳过这件，换下一件`() {
        val q = VoiceIntentQueue()
        q.enqueue("own_life", "被拦住的那件", listOf("e1"), now = 0L)
        q.enqueue("diary", "可以说的那件", listOf("e2"), now = 0L)
        val picked = q.next(now = 1L) { it == "被拦住的那件" }
        assertEquals("应当换下一件", "可以说的那件", picked?.summary)
    }

    @Test
    fun `来源是开放结构，新增来源不需要改主链路`() {
        val q = VoiceIntentQueue()
        assertNotNull("任意新来源字符串都能入队", q.enqueue("notion_page", "以后才有的来源", listOf("e3"), now = 0L))
        assertTrue(q.all().any { it.source == "notion_page" })
    }
}
