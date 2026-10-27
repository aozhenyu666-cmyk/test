package cn.companion.pet.core.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 它自己的生活：进度按真实时间推进、缺勤有上限、情绪必须有原因。 */
class OwnLifePlannerTest {
    private val min = 60_000L
    private val hour = 60 * min

    private fun act(progress: Int = 0, lastTouchedAt: Long = 0L) = OwnActivity(
        id = "a1", title = "《万物简史》", sourceUrl = "https://example.com/x",
        sourceSite = "example.com", sourceDate = "2026-10-20",
        angleOfInterest = "想弄明白时间尺度", impression = "开头有点难",
        progress = progress, totalHint = 20, startedAt = 0L, lastTouchedAt = lastTouchedAt,
    )

    @Test
    fun `进度按真实经过时间推进，用户不理它也在读`() {
        val s = OwnLifeState(activities = listOf(act()), lastAdvancedAt = 0L)
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        assertTrue("两小时后进度应前进", next.activities.single().progress > 0)
    }

    @Test
    fun `缺勤上限一天，三天没开不会读掉三天的量`() {
        val s = OwnLifeState(activities = listOf(act()), lastAdvancedAt = 0L)
        val oneDay = OwnLifePlanner.advance(s, now = 24 * hour)
        val threeDays = OwnLifePlanner.advance(s, now = 72 * hour)
        assertEquals(
            "三天缺勤的推进必须被 1 天上限夹住，与一天相同",
            oneDay.activities.single().progress,
            threeDays.activities.single().progress,
        )
    }

    @Test
    fun `没有可溯源来源的活动不算真读过`() {
        assertTrue(act().hasTraceableSource())
        assertFalse(act().copy(sourceUrl = "").hasTraceableSource())
    }

    @Test
    fun `情绪改变必须带非空原因`() {
        val s = OwnLifeState(activities = listOf(act()), lastAdvancedAt = 0L)
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        if (next.mood != s.mood) {
            assertTrue("情绪一旦变化，moodReason 不能为空", next.moodReason.isNotBlank())
        }
    }

    /** 上面那条是条件断言，可能空过；这里直接把「跨过 totalHint」这条分支逼出来。 */
    @Test
    fun `进度跨过总篇幅时真的转为 CONTENT 且理由非空`() {
        val s = OwnLifeState(activities = listOf(act(progress = 19)), lastAdvancedAt = 0L)
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        assertEquals("读到头就停在总篇幅，不读过头", 20, next.activities.single().progress)
        assertEquals(OwnMood.CONTENT, next.mood)
        assertTrue("情绪一变，理由必须非空", next.moodReason.isNotBlank())
        assertEquals("理由要能被提示词直接引用", "刚读完《万物简史》", next.moodReason)
        assertEquals("情绪变化时间要记下来", 2 * hour, next.moodSince)
    }

    /** 已读完的不再刷新「最近碰到」时间，后续挑素材才不会一直挑到同一件。 */
    @Test
    fun `已经读完的活动不再刷新最近碰到时间`() {
        val done = act(progress = 20, lastTouchedAt = 7 * min)
        val s = OwnLifeState(activities = listOf(done), lastAdvancedAt = 0L)
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        assertEquals(20, next.activities.single().progress)
        assertEquals("读完的时间点不该被推着往前走", 7 * min, next.activities.single().lastTouchedAt)
        assertEquals(OwnMood.CALM, next.mood)
    }

    /** 没读完就是没读完：情绪和原来的理由都不许被动过。 */
    @Test
    fun `没跨过总篇幅时情绪与原理由都保持不变`() {
        val s = OwnLifeState(
            activities = listOf(act(progress = 0)),
            mood = OwnMood.CURIOUS,
            moodReason = "这篇看起来有意思",
            moodSince = 5 * min,
            lastAdvancedAt = 0L,
        )
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        assertEquals(OwnMood.CURIOUS, next.mood)
        assertEquals("这篇看起来有意思", next.moodReason)
        assertEquals(5 * min, next.moodSince)
    }
}
