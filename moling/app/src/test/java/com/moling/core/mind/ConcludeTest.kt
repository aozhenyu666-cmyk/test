package com.moling.core.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ConcludeTest {
    /** 2026-10-19 14:00 北京时间。 */
    private val now = at(2026, 10, 19, 14, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(BEIJING).toInstant().toEpochMilli()

    @Test fun splitsConclusionAndNextStep() {
        val c = Conclude.local("材料分成三类，明天9点写提纲", now)
        assertEquals("材料分成三类", c.conclusion)
        assertEquals("明天9点写提纲", c.nextStep)
        assertEquals(at(2026, 10, 20, 9, 0), c.at)
        assertTrue(c.substantial)
    }

    @Test fun chineseNumeralsFromDictation() {
        assertEquals(at(2026, 10, 20, 9, 0), Conclude.timeOf("明天九点写提纲", now))
        assertEquals(at(2026, 10, 19, 15, 30), Conclude.timeOf("下午三点半开始", now))
        assertEquals(at(2026, 10, 19, 23, 0), Conclude.timeOf("晚上十一点之前睡", now))
        assertEquals(at(2026, 10, 19, 20, 0), Conclude.timeOf("二十点做", now))
    }

    @Test fun doesNotReadBitAsOClock() {
        assertEquals(0L, Conclude.timeOf("思路清楚一点了", now))
        assertEquals(0L, Conclude.timeOf("好一点", now))
    }

    @Test fun pastTimeWithoutDayMeansTomorrow() {
        assertEquals(at(2026, 10, 20, 9, 0), Conclude.timeOf("9点开始", now))
        assertEquals(at(2026, 10, 19, 21, 0), Conclude.timeOf("今晚9点", now))
    }

    @Test fun fillerIsNotSubstantial() {
        assertFalse(Conclude.local("好的", now).substantial)
        assertFalse(Conclude.local("聊完了", now).substantial)
        assertTrue(Conclude.local("第二题的问题是没有归纳出原因", now).substantial)
    }

    @Test fun nextStepMarkerIsStripped() {
        val c = Conclude.local("归纳要先找共同点，下一步是重写第二段", now)
        assertEquals("重写第二段", c.nextStep)
        assertEquals(0L, c.at)
    }

    @Test fun modelTagsOverrideButKeepLocalFallback() {
        val reply = "【结论】归纳题要先找共同原因\n【未解决】无\n【下一步】重写第二题\n【时间】明天 9 点"
        val c = Conclude.fromModel(reply, "归纳要找原因，明天重写", now)
        assertEquals("归纳题要先找共同原因", c.conclusion)
        assertEquals("重写第二题", c.nextStep)
        assertEquals("", c.open)
        assertEquals(at(2026, 10, 20, 9, 0), c.at)
        val broken = Conclude.fromModel("胡言乱语", "材料分成三类，明天9点写提纲", now)
        assertEquals("材料分成三类", broken.conclusion)
    }

    @Test fun tagsParserIsLenient() {
        val t = Tags.parse("前言【问】申论第二题卡在哪？\n【朗读】卡在哪？。")
        assertEquals("申论第二题卡在哪？", t["问"])
        assertEquals("卡在哪？", t["朗读"])
    }
}
