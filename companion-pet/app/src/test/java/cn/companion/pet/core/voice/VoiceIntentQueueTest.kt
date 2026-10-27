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

    /**
     * 过期只是「不再被挑中」，不是「这件事永远不许再说」。
     *
     * 旧实现用「历史上入过队」去做重，一条意图过期未说之后，同一内容再也进不来，
     * 等于这件事被静默永久禁言。
     *
     * 这一条**中途不调用 [VoiceIntentQueue.next]**：`next` 会顺带 prune，
     * 那样就测不到「去重口径本身是否只看有效条目」了——把去重改回「看全部历史」
     * 也可能因为被 prune 擦干净而蒙过去。直接入队，去重口径才真正被钉住。
     */
    @Test
    fun `过期之后再次入队同一内容应当成功`() {
        val q = VoiceIntentQueue()
        assertNotNull(q.enqueue("own_life", "过期没说的事", listOf("e1"), now = 0L, ttlMillis = hour))

        // 过期时刻之后直接再入队，不先调 next()，所以过期条目还躺在队列里。
        val later = 2 * hour
        val again = q.enqueue("own_life", "过期没说的事", listOf("e1"), now = later, ttlMillis = hour)
        assertNotNull("过期条目还占着去重名额就错了：同一件事应当能重新入队", again)
        assertEquals("重新入队后立刻能说", "过期没说的事", q.next(now = later + 1) { false }?.summary)
    }

    /** 队列不能只增不减：过期未说的条目要被清掉。 */
    @Test
    fun `next 会顺手清掉过期未说的条目`() {
        val q = VoiceIntentQueue()
        repeat(5) { q.enqueue("own_life", "会过期的事 $it", listOf("e$it"), now = 0L, ttlMillis = hour) }
        assertEquals(5, q.all().size)

        q.next(now = 2 * hour) { false }
        assertEquals("过期未说的条目应当被清理", 0, q.all().size)
    }

    @Test
    fun `prune 单独调用也能清掉过期未说的条目，说过的不清`() {
        val q = VoiceIntentQueue()
        val spoken = q.enqueue("own_life", "说过的", listOf("e1"), now = 0L, ttlMillis = hour)!!
        q.enqueue("own_life", "没说的", listOf("e2"), now = 0L, ttlMillis = hour)
        q.markSpoken(spoken.id, now = 1L)

        assertEquals("只清过期且没说的那条", 1, q.prune(now = 2 * hour))
        assertEquals("说过的那条留作痕迹", listOf("说过的"), q.all().map { it.summary })
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

    /** 同一毫秒入队的多件事也要有确定顺序，不靠 `sortedBy` 的稳定性。 */
    @Test
    fun `同一时刻入队时按入队顺序挑最早的那件`() {
        val q = VoiceIntentQueue()
        q.enqueue("own_life", "第一件事", listOf("e1"), now = 7L)
        q.enqueue("diary", "第二件事", listOf("e2"), now = 7L)
        q.enqueue("rule", "第三件事", listOf("e3"), now = 7L)

        assertEquals("第一件事", q.next(now = 8L) { false }?.summary)
        // avoid 为 true 表示「这件跳过」，所以第一件被跳过时必须落到第二件。
        assertEquals("第二件事", q.next(now = 8L) { it == "第一件事" }?.summary)
        assertEquals("第三件事", q.next(now = 8L) { it != "第三件事" }?.summary)
    }

    /** 纯标点、纯语气词归一化后为空，没有语义，不该进队。 */
    @Test
    fun `归一化后为空的内容不能入队`() {
        val q = VoiceIntentQueue()
        assertNull("纯语气词", q.enqueue("own_life", "嗯", now = 0L))
        assertNull("纯标点", q.enqueue("own_life", "……", now = 0L))
        assertNull("语气词加标点", q.enqueue("own_life", "啊吧呢呀嘛？！", now = 0L))
        assertTrue("队列里不该有东西", q.all().isEmpty())
        assertNotNull("正常内容照常入队", q.enqueue("own_life", "嗯我今天有点累", now = 0L))
    }

    @Test
    fun `来源是开放结构，新增来源不需要改主链路`() {
        val q = VoiceIntentQueue()
        assertNotNull("任意新来源字符串都能入队", q.enqueue("notion_page", "以后才有的来源", listOf("e3"), now = 0L))
        assertTrue(q.all().any { it.source == "notion_page" })
    }
}
