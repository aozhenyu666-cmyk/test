package cn.companion.pet.core.life

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 它自己的生活：进度按真实时间推进、缺勤有上限、情绪必须有原因。 */
class OwnLifePlannerTest {
    private val min = 60_000L
    private val hour = 60 * min

    private fun act(progress: Int = 0, lastTouchedAt: Long = 0L, totalHint: Int? = 20) = OwnActivity(
        id = "a1", title = "《万物简史》", sourceUrl = "https://example.com/x",
        sourceSite = "example.com", sourceDate = "2026-10-20",
        angleOfInterest = "想弄明白时间尺度", impression = "开头有点难",
        progress = progress, totalHint = totalHint, startedAt = 0L, lastTouchedAt = lastTouchedAt,
    )

    /**
     * 篇幅未知的活动（`totalHint == null`）。
     *
     * 这种活动身上**没有任何别的机制能让进度停下来**，所以它是检验
     * 「缺勤上限」与「不封顶增长」的干净样本：有 `totalHint` 的活动会被
     * “不超过总篇幅”夹平，两个原因混在一起就分不清到底是谁在起作用。
     */
    private fun openEndedAct(progress: Int = 0, lastTouchedAt: Long = 0L) =
        act(progress = progress, lastTouchedAt = lastTouchedAt, totalHint = null)

    @Test
    fun `进度按真实经过时间推进，用户不理它也在读`() {
        val s = OwnLifeState(activities = listOf(act()), lastAdvancedAt = 0L)
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        assertEquals(
            "30 分钟一档，两小时正好推进 4 档",
            4,
            next.activities.single().progress,
        )
    }

    @Test
    fun `缺勤上限一天，三天没开不会读掉三天的量`() {
        // 用 totalHint == null 的活动：进度不会被总篇幅夹平，于是「24h 与 72h 相等」
        // 这件事只可能由 elapsedCapMillis 造成 —— 删掉上限，72h 就会推进到 144 档。
        val s = OwnLifeState(activities = listOf(openEndedAct()), lastAdvancedAt = 0L)
        val oneDay = OwnLifePlanner.advance(s, now = 24 * hour).activities.single().progress
        val threeDays = OwnLifePlanner.advance(s, now = 72 * hour).activities.single().progress

        assertEquals("一天 = 24h / 30min = 48 档", 48, oneDay)
        assertEquals("三天缺勤也只补算一天的量，仍是 48 档", 48, threeDays)
        assertEquals(
            "三天缺勤的推进必须被 1 天上限夹住，与一天相同",
            oneDay,
            threeDays,
        )
        assertNotEquals("72 小时若真按 144 档推进，说明缺勤上限失效了", 144, threeDays)
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

    /** 篇幅未知时不能偷偷封顶：进度要一直涨，否则「它在读」会变成假的。 */
    @Test
    fun `篇幅未知时进度没有上限，会继续往前读`() {
        val s = OwnLifeState(activities = listOf(openEndedAct(progress = 100)), lastAdvancedAt = 0L)
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        assertEquals("totalHint 为 null 就不封顶：100 + 4 档 = 104", 104, next.activities.single().progress)

        val longRun = OwnLifePlanner.advance(s, now = 24 * hour)
        assertEquals("存量已超出任何常识篇幅也不该被夹回去，100 + 48 = 148", 148, longRun.activities.single().progress)
    }

    /** 时钟回拨（now 早于 lastAdvancedAt）时进度只许原地，不许倒退。 */
    @Test
    fun `时钟回拨时进度不倒退`() {
        val s = OwnLifeState(activities = listOf(openEndedAct(progress = 10)), lastAdvancedAt = 10 * hour)
        val next = OwnLifePlanner.advance(s, now = 2 * hour)
        assertEquals("now 早于上次推进时间，已读进度不能被吐回去", 10, next.activities.single().progress)
        assertEquals("时间基准仍然落到 now，从这一刻继续算", 2 * hour, next.lastAdvancedAt)
        assertEquals("没有推进就不该动「最近碰到」时间", 0L, next.activities.single().lastTouchedAt)
    }
}
