package com.yishou.app.companion

import com.yishou.app.companion.core.AudioChunker
import com.yishou.app.companion.core.Chunk
import com.yishou.app.companion.core.Channel
import com.yishou.app.companion.core.ContextBudget
import com.yishou.app.companion.core.ContextTimeline
import com.yishou.app.companion.core.FrameDiff
import com.yishou.app.companion.core.FramePick
import com.yishou.app.companion.core.FrameSelector
import com.yishou.app.companion.core.Observation
import com.yishou.app.companion.core.Quality
import com.yishou.app.companion.core.Segment
import com.yishou.app.companion.core.SummaryParse
import com.yishou.app.companion.core.TranscriptAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分块转写、关键帧筛选、带出处的摘要、上下文预算 */
class TranscriptTest {

    private var ids = 0L
    private fun assembler() = TranscriptAssembler(
        sessionId = 1,
        appPackage = "com.example.video",
        epoch = 1,
        nextId = { ++ids },
        wallClock = { 1_700_000_000_000L },
    )

    @Test
    fun `分块累积到整块才交出去`() {
        val got = ArrayList<Triple<Long, Long, Int>>()
        val chunker = AudioChunker(sampleRate = 16_000, chunkMs = 1_000, overlapMs = 200) { s, e, pcm ->
            got += Triple(s, e, pcm.size / 2)
        }
        chunker.feed(ShortArray(8_000), 8_000, 0)   // 0.5 秒
        assertEquals(0, got.size)
        chunker.feed(ShortArray(8_000), 8_000, 500) // 攒够 1 秒
        assertEquals(1, got.size)
        assertEquals(0L, got[0].first)
        assertEquals(1_000L, got[0].second)
        assertEquals(16_000, got[0].third)
    }

    @Test
    fun `重叠的尾巴会在下一次开头补回来`() {
        val starts = ArrayList<Long>()
        val chunker = AudioChunker(sampleRate = 1_000, chunkMs = 1_000, overlapMs = 300) { s, _, _ -> starts += s }
        // 喂够两块
        chunker.feed(ShortArray(1_000), 1_000, 0)
        chunker.feed(ShortArray(1_000), 1_000, 1_000)
        assertEquals(listOf(0L, 700L), starts)
    }

    @Test
    fun `收尾把不足一块的也交出去`() {
        val got = ArrayList<Int>()
        val chunker = AudioChunker(sampleRate = 1_000, chunkMs = 1_000, overlapMs = 0) { _, _, pcm -> got += pcm.size / 2 }
        chunker.feed(ShortArray(400), 400, 0)
        chunker.flush()
        assertEquals(listOf(400), got)
        chunker.flush()
        assertEquals(1, got.size)
    }

    @Test
    fun `拼出来的观察只保留新增的部分`() {
        val a = assembler()
        val first = a.accept(Chunk(0, 8_000, "他从样本A推出全部人群"))
        val second = a.accept(Chunk(7_000, 15_000, "这里没有依据"))
        assertNotNull(first)
        assertEquals("他从样本A推出全部人群", first!!.text)
        assertEquals("这里没有依据", second!!.text)
    }

    @Test
    fun `真正的重叠会被削掉`() {
        val a = assembler()
        a.accept(Chunk(0, 8_000, "样本能不能代表整体"))
        val second = a.accept(Chunk(7_000, 15_000, "代表整体这件事他没说"))
        assertEquals("这件事他没说", second!!.text)
    }

    @Test
    fun `空白块不产生观察但时间往后推`() {
        val a = assembler()
        a.accept(Chunk(0, 5_000, "第一句"))
        assertNull(a.accept(Chunk(5_000, 10_000, "   ")))
        val next = a.accept(Chunk(10_000, 15_000, "接着说"))
        assertEquals(0L, next!!.gapsBeforeMs)   // 5 秒间隔里面有块，只是没文字
    }

    @Test
    fun `中间识别失败要并进下一段的缺口`() {
        val a = assembler()
        a.accept(Chunk(0, 5_000, "第一句"))
        a.fail(5_000, 12_000)
        assertEquals(7_000L, a.failedMs())
        val next = a.accept(Chunk(12_000, 18_000, "又接上了"))
        assertEquals(7_000L, next!!.gapsBeforeMs)
    }

    @Test
    fun `识别成空也算缺口`() {
        val a = assembler()
        a.accept(Chunk(0, 5_000, "第一句"))
        val next = a.accept(Chunk(20_000, 26_000, "过了很久"))
        assertEquals(15_000L, next!!.gapsBeforeMs)
    }

    @Test
    fun `画面没变化就不留帧`() {
        val sel = FrameSelector(minChange = 0.12, minIntervalMs = 1_000, maxPerWindow = 10)
        assertEquals(FramePick.NO_CHANGE, sel.offer(1L, changeScore = 0.02, atMs = 0))
        assertEquals(FramePick.PICK, sel.offer(2L, changeScore = 0.5, atMs = 0))
    }

    @Test
    fun `变化够大但太密也不留`() {
        val sel = FrameSelector(minChange = 0.12, minIntervalMs = 1_500, maxPerWindow = 10)
        assertEquals(FramePick.PICK, sel.offer(1L, 0.4, 0))
        assertEquals(FramePick.TOO_SOON, sel.offer(2L, 0.4, 800))
        assertEquals(FramePick.PICK, sel.offer(3L, 0.4, 1_600))
    }

    @Test
    fun `一分钟留够了就不再留`() {
        val sel = FrameSelector(minChange = 0.1, minIntervalMs = 100, maxPerWindow = 2)
        assertEquals(FramePick.PICK, sel.offer(1L, 0.5, 0))
        assertEquals(FramePick.PICK, sel.offer(2L, 0.5, 200))
        assertEquals(FramePick.TOO_MANY, sel.offer(3L, 0.5, 400))
    }

    @Test
    fun `帧间变化量：同一帧是零，翻页接近一`() {
        val a = IntArray(FrameDiff.SAMPLES) { 100 }
        val same = IntArray(FrameDiff.SAMPLES) { 105 }
        val other = IntArray(FrameDiff.SAMPLES) { 200 }
        assertEquals(0.0, FrameDiff.changeRatio(a, same), 0.001)
        assertEquals(1.0, FrameDiff.changeRatio(a, other), 0.001)
    }

    @Test
    fun `解析带出处的摘要`() {
        val raw = """
            主张：他从样本 A 推到全部人群｜[11,12]
            条件：样本必须是随机抽的｜[13]
            未解：抽样方法始终没说｜[11]
        """.trimIndent()
        val s = SummaryParse.parse(raw, allowedIds = setOf(11L, 12L, 13L), segmentId = 5)
        assertNotNull(s)
        assertEquals(1, s!!.claims.size)
        assertEquals(listOf(11L, 12L), s.claims[0].evidenceIds)
        assertEquals(1, s.conditions.size)
        assertEquals(1, s.openQuestions.size)
        assertTrue(s.usable)
    }

    @Test
    fun `引用到不存在的编号那条不要`() {
        val raw = """
            主张：材料提到了三点｜[99]
            主张：样本不随机｜[11]
        """.trimIndent()
        val s = SummaryParse.parse(raw, allowedIds = setOf(11L), segmentId = 5)
        assertEquals(1, s!!.claims.size)
        assertEquals("样本不随机", s.claims[0].text)
    }

    @Test
    fun `一条引用都没有就当没摘要`() {
        val raw = "主张：某种一般人都知道的道理"
        assertNull(SummaryParse.parse(raw, allowedIds = setOf(11L), segmentId = 5))
        assertNull(SummaryParse.parse("完全不成格式的一段话", allowedIds = setOf(11L), segmentId = 5))
    }

    @Test
    fun `上下文预算从最新往回取，丢掉的说清楚`() {
        val segs = listOf("一" to 1L, "二" to 2L, "三" to 3L).map { (t, id) ->
            Segment(id = id, channel = Channel.VIDEO_AUDIO, fromMs = id * 1_000, toMs = id * 1_000 + 500,
                text = t.repeat(20), evidenceIds = listOf(id))
        }
        val slice = com.yishou.app.companion.core.ContextSlice(
            epoch = 1, evidenceIds = listOf(1, 2, 3), fromMs = 1_000, toMs = 3_500,
            text = "", segments = segs, gapsMs = 0, expiresAtMs = 99_000,
        )
        val fitted = ContextBudget(maxChars = 50).fit(slice)
        assertEquals(1, fitted.segments.size)
        assertEquals(3L, fitted.segments.first().id)   // 留最新的那条
        assertTrue(fitted.truncated)
        assertEquals(2, fitted.droppedSegments)
    }

    @Test
    fun `预算够的时候全带上`() {
        val segs = listOf(
            Segment(1, Channel.VIDEO_AUDIO, 0, 500, "短短一句", listOf(1L)),
        )
        val slice = com.yishou.app.companion.core.ContextSlice(1, listOf(1), 0, 500, "", segs, 0, 99_000)
        val fitted = ContextBudget(maxChars = 500).fit(slice)
        assertEquals(1, fitted.segments.size)
        assertTrue(!fitted.truncated)
    }

    @Test
    fun `拼好的观察能进时间轴并按停顿切段`() {
        val a = assembler()
        val tl = ContextTimeline(sessionId = 1, segmentGapMs = 1_200)
        a.accept(Chunk(0, 8_000, "第一步是拿到样本"))?.let { tl.add(it) }
        a.accept(Chunk(20_000, 28_000, "第二步才是推全体"))?.let { tl.add(it) }
        val slice = tl.slice(28_500, 40_000)
        assertEquals(2, slice.evidenceIds.size)
        assertEquals(2, slice.segments.size)
    }

    @Test
    fun `质量标记跟着观察走`() {
        val a = assembler()
        val o = a.accept(Chunk(0, 1_000, "半截话", Quality.PARTIAL))
        assertEquals(Quality.PARTIAL, o!!.quality)
    }
}
