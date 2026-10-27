package cn.companion.pet.core.life

import cn.companion.pet.net.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一次「找东西读」的编排：只有真实来源能变成活动；最多两件同时在读；情绪不受它影响。 */
class OwnLifeCycleTest {

    private val hour = 60 * 60_000L

    private fun hit(
        url: String = "https://a.example/1",
        title: String = "万物简史",
        siteName: String = "A站",
        date: String? = "2026-10-01",
        summary: String = "摘要",
    ) = SearchHit(title = title, url = url, siteName = siteName, date = date, summary = summary)

    private fun act(id: String, lastTouchedAt: Long) = OwnActivity(
        id = id,
        title = "《" + id + "》",
        sourceUrl = "https://example.com/" + id,
        sourceSite = "example.com",
        progress = 3,
        totalHint = 20,
        startedAt = 0L,
        lastTouchedAt = lastTouchedAt,
    )

    // ---------- fromSearch ----------

    @Test
    fun `搜索结果为空时没有来源`() {
        assertEquals(
            ReadCycleOutcome.NoSource,
            OwnLifeCycle.fromSearch(emptyList(), "https://a.example/1", "角度", "感想", now = 100L),
        )
    }

    @Test
    fun `模型选了不在结果里的 url 时没有来源，防止编造`() {
        assertEquals(
            "幻觉出来的 url 一个都不许变成活动",
            ReadCycleOutcome.NoSource,
            OwnLifeCycle.fromSearch(listOf(hit()), "https://fake.example/nope", "角度", "感想", now = 100L),
        )
    }

    @Test
    fun `正常时产生活动且来源等于真实 hit 的 url`() {
        val hits = listOf(
            hit(url = "https://a.example/1", title = "第一篇"),
            hit(url = "https://a.example/2", title = "第二篇"),
        )
        val out = OwnLifeCycle.fromSearch(hits, "https://a.example/2", "时间尺度", "开头有点难", now = 555L)
        assertTrue("有真实来源时必须产生活动", out is ReadCycleOutcome.Got)
        val a = (out as ReadCycleOutcome.Got).activity
        assertEquals("来源必须是真实 hit 的 url，不能是别的", "https://a.example/2", a.sourceUrl)
        assertEquals("第二篇", a.title)
        assertEquals("角度原样透传", "时间尺度", a.angleOfInterest)
        assertEquals("感想原样透传", "开头有点难", a.impression)
        assertEquals(555L, a.startedAt)
        assertTrue("必须可溯源", a.hasTraceableSource())
    }

    // ---------- adopt ----------

    @Test
    fun `同一 id 覆盖，不产生重复条目`() {
        val state = OwnLifeState(activities = listOf(act("a", lastTouchedAt = 1 * hour)))
        val updated = act("a", lastTouchedAt = 2 * hour).copy(progress = 9, impression = "越读越有意思")

        val next = OwnLifeCycle.adopt(state, updated, now = 3 * hour)

        assertEquals("同一篇只应有一条记录", 1, next.activities.size)
        assertEquals(9, next.activities.single().progress)
        assertEquals("越读越有意思", next.activities.single().impression)
        assertEquals("最近触碰时间要更新到现在", 3 * hour, next.activities.single().lastTouchedAt)
    }

    @Test
    fun `同一 id 覆盖时进度不倒退`() {
        val state = OwnLifeState(activities = listOf(act("a", lastTouchedAt = 1 * hour).copy(progress = 12)))
        val next = OwnLifeCycle.adopt(state, act("a", lastTouchedAt = 2 * hour).copy(progress = 4), now = 3 * hour)
        assertEquals("重挑同一篇不该把已读的页数吐回去", 12, next.activities.single().progress)
    }

    @Test
    fun `超过两个活动时丢弃最久没碰的，只留两个`() {
        val state = OwnLifeState(
            activities = listOf(
                act("old", lastTouchedAt = 1 * hour),
                act("mid", lastTouchedAt = 5 * hour),
                // 第三件是刚挑中的，调用方给的 lastTouchedAt 比已有的旧，用来证明排序看的是最新触碰而不是入参。
                act("fresh", lastTouchedAt = 0L),
            ),
        )
        val next = OwnLifeCycle.adopt(state, act("brand-new", lastTouchedAt = 0L), now = 9 * hour)

        assertEquals("最多同时读两件", 2, next.activities.size)
        val ids = next.activities.map { it.id }.toSet()
        assertTrue("刚挑中的那件必须留下", ids.contains("brand-new"))
        assertTrue("第二近的 mid 留下", ids.contains("mid"))
        assertTrue("最久没碰的 old 必须被丢掉", !ids.contains("old"))
    }

    @Test
    fun `从两个变三个时只丢一条，留下刚挑中的`() {
        val state = OwnLifeState(
            activities = listOf(act("a", lastTouchedAt = 1 * hour), act("b", lastTouchedAt = 2 * hour)),
        )
        val next = OwnLifeCycle.adopt(state, act("c", lastTouchedAt = 0L), now = 3 * hour)
        assertEquals("三个变两个：只该丢一条", 2, next.activities.size)
        assertTrue(next.activities.any { it.id == "c" })
    }

    @Test
    fun `adopt 不修改情绪与情绪理由`() {
        val state = OwnLifeState(
            activities = listOf(act("a", lastTouchedAt = 1 * hour)),
            mood = OwnMood.DOWN,
            moodReason = "今天有点低落",
            moodSince = 7 * hour,
            lastAdvancedAt = 42L,
        )
        val next = OwnLifeCycle.adopt(state, act("b", lastTouchedAt = 0L), now = 9 * hour)

        assertEquals("情绪只能由 OwnLifePlanner 改", OwnMood.DOWN, next.mood)
        assertEquals("今天有点低落", next.moodReason)
        assertEquals("情绪起点不许被动过", 7 * hour, next.moodSince)
        assertEquals("推进时间戳也不归它管", 42L, next.lastAdvancedAt)
    }

    // ---------- promptLine ----------

    @Test
    fun `提示词行带书名、进度与感想`() {
        val state = OwnLifeState(
            activities = listOf(
                act("a", lastTouchedAt = 5 * hour).copy(title = "万物简史", progress = 3, totalHint = 20, impression = "开头有点难"),
            ),
        )
        val line = OwnLifeCycle.promptLine(state)
        assertEquals("正在读《万物简史》，读到 3/20，感想：开头有点难", line)
    }

    @Test
    fun `心情不平静时在提示词行里补一句`() {
        val state = OwnLifeState(
            activities = listOf(act("a", lastTouchedAt = 5 * hour).copy(title = "万物简史", progress = 3, totalHint = 20, impression = "")),
            mood = OwnMood.TIRED,
            moodReason = "有点累",
        )
        assertTrue(OwnLifeCycle.promptLine(state).contains("我现在有点累"))
    }

    @Test
    fun `没有活动时提示词行为空，不留空块`() {
        assertEquals("", OwnLifeCycle.promptLine(OwnLifeState()))
    }

    @Test
    fun `没有来源的活动不进提示词`() {
        val state = OwnLifeState(activities = listOf(act("a", lastTouchedAt = 1 * hour).copy(sourceUrl = "")))
        assertEquals("没有 url 就是没读过，不许进提示词", "", OwnLifeCycle.promptLine(state))
    }
}
