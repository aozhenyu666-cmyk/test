package com.yishou.app.companion

import com.yishou.app.companion.core.AddResult
import com.yishou.app.companion.core.Channel
import com.yishou.app.companion.core.ContextTimeline
import com.yishou.app.companion.core.MediaPosition
import com.yishou.app.companion.core.Observation
import com.yishou.app.companion.core.PositionSource
import com.yishou.app.companion.core.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间轴：去重、作废、有界、跳播 */
class TimelineTest {

    private var seq = 0L

    private fun obs(
        start: Long,
        end: Long,
        text: String,
        epoch: Int = 1,
        channel: Channel = Channel.VIDEO_AUDIO,
        gaps: Long = 0,
    ) = Observation(
        id = ++seq,
        sessionId = 7,
        sourceEpoch = epoch,
        appPackage = "com.example.video",
        channel = channel,
        elapsedStartMs = start,
        elapsedEndMs = end,
        wallTimeMs = 1_700_000_000_000 + start,
        text = text,
        quality = Quality.GOOD,
        gapsBeforeMs = gaps,
    )

    @Test
    fun `分块识别的重叠只留一遍`() {
        val t = ContextTimeline(sessionId = 7)
        assertEquals(AddResult.ADDED, t.add(obs(0, 2_000, "他从样本A推出全部人")))
        assertEquals(AddResult.DUPLICATE, t.add(obs(500, 2_500, "他从样本A推出全部人")))
        assertEquals(1, t.size())
    }

    @Test
    fun `同一句话隔得远算两次`() {
        val t = ContextTimeline(sessionId = 7)
        assertEquals(AddResult.ADDED, t.add(obs(0, 2_000, "举个例子")))
        assertEquals(AddResult.ADDED, t.add(obs(30_000, 32_000, "举个例子")))
        assertEquals(2, t.size())
    }

    @Test
    fun `别的场次的东西不许进来`() {
        val t = ContextTimeline(sessionId = 7)
        val alien = obs(0, 1_000, "别人的").copy(sessionId = 8)
        assertEquals(AddResult.WRONG_SESSION, t.add(alien))
    }

    @Test
    fun `换段以后旧段的东西一律丢`() {
        val t = ContextTimeline(sessionId = 7)
        t.add(obs(0, 1_000, "旧视频里的话", epoch = 1))
        t.bumpEpoch()
        assertEquals(2, t.epoch())
        assertEquals(AddResult.STALE, t.add(obs(2_000, 3_000, "旧段晚到的转写", epoch = 1)))
        assertEquals(1, t.size())
    }

    @Test
    fun `切片只包含当前段`() {
        val t = ContextTimeline(sessionId = 7)
        t.add(obs(0, 1_000, "旧视频", epoch = 1))
        t.bumpEpoch()
        t.add(obs(1_000, 2_000, "新视频第一句", epoch = 2))
        val s = t.slice(2_500, expiresAtMs = 10_000)
        assertEquals(2, s.epoch)
        assertEquals(listOf(2L), s.evidenceIds)
        assertTrue(s.text.contains("新视频第一句"))
        assertFalse(s.text.contains("旧视频"))
    }

    @Test
    fun `窗口外的会被淘汰`() {
        val t = ContextTimeline(sessionId = 7, windowMs = 10_000)
        t.add(obs(0, 1_000, "很久以前"))
        t.add(obs(12_000, 13_000, "刚刚"))
        val s = t.slice(13_000, 20_000)
        assertEquals(1, s.evidenceIds.size)
        assertTrue(s.text.contains("刚刚"))
    }

    @Test
    fun `缺口要能看见`() {
        val t = ContextTimeline(sessionId = 7)
        t.add(obs(0, 1_000, "第一段"))
        t.add(obs(9_000, 10_000, "接上了", gaps = 8_000))
        assertEquals(8_000L, t.gapsMs())
        assertTrue(t.slice(10_000, 20_000).gapsMs > 0)
    }

    @Test
    fun `按停顿切段`() {
        val t = ContextTimeline(sessionId = 7, segmentGapMs = 1_200)
        t.add(obs(0, 1_000, "第一个条件"))
        t.add(obs(1_200, 2_200, "还是第一段"))
        t.add(obs(20_000, 21_000, "另起一段"))
        val s = t.slice(21_500, 30_000)
        assertEquals(2, s.segments.size)
        assertTrue(s.segments.first().text.contains("第一个条件"))
        assertEquals(2, s.segments.first().evidenceIds.size)
    }

    @Test
    fun `回看算跳播`() {
        val t = ContextTimeline(sessionId = 7)
        t.add(obs(0, 1_000, "开头"))
        assertFalse(t.observePosition(MediaPosition(120_000, PositionSource.MEDIA_SESSION), 60_000))
        assertTrue(t.observePosition(MediaPosition(20_000, PositionSource.MEDIA_SESSION), 61_000))
        assertEquals(2, t.epoch())
    }

    @Test
    fun `快进算跳播，正常播放不算`() {
        val t = ContextTimeline(sessionId = 7)
        t.observePosition(MediaPosition(0, PositionSource.MEDIA_SESSION), 0)
        assertFalse(t.observePosition(MediaPosition(5_000, PositionSource.MEDIA_SESSION), 5_000))
        assertTrue(t.observePosition(MediaPosition(90_000, PositionSource.MEDIA_SESSION), 6_000))
    }

    @Test
    fun `拿不到进度时不乱猜跳播`() {
        val t = ContextTimeline(sessionId = 7)
        assertFalse(t.observePosition(MediaPosition(null, PositionSource.NONE), 1_000))
        assertFalse(t.observePosition(MediaPosition(null, PositionSource.NONE), 2_000))
        assertEquals(1, t.epoch())
    }

    @Test
    fun `没有进度时只说本次观察第几分钟`() {
        val label = MediaPosition(null, PositionSource.NONE).label(125_000)
        assertEquals("本次观察的第 2 分 5 秒", label)
    }

    @Test
    fun `有进度时给时间码并注明来源`() {
        val label = MediaPosition(125_000, PositionSource.MEDIA_SESSION).label(0)
        assertEquals("2:05（播放器）", label)
    }

    @Test
    fun `画面和原文分开标来源`() {
        val t = ContextTimeline(sessionId = 7)
        t.add(obs(0, 1_000, "他说了这句话", channel = Channel.VIDEO_AUDIO))
        t.add(obs(1_000, 2_000, "图表上有个 37%", channel = Channel.SCREEN))
        val text = t.slice(2_500, 9_000).text
        assertTrue(text.contains("（画面）图表上有个 37%"))
        assertTrue(text.contains("他说了这句话"))
    }
}
