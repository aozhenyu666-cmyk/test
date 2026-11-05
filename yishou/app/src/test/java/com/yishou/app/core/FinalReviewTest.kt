package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalReviewTest {

    private val source = "我国实行社会主义市场经济体制，市场在资源配置中起决定性作用。"
    private val quote = "市场在资源配置中起决定性作用"

    private fun input(
        quote: String = this.quote,
        other: String = "B",
        reason: String = "B 项说成政府起决定性作用，把主体换了。",
    ) = FinalReviewInput(source = source, quote = quote, otherOption = other, reason = reason)

    @Test
    fun `a model quote that trims the span still counts as quoting the source`() {
        val long = "这个视频说：短视频让人的注意力越来越差，因为大家每天都在刷，但视频只给了一条数据。"
        val question = "「只给了一条数据」就断言注意力变差，这个因果站得住吗？"
        assertTrue(FinalReviewRules.quotesSource(question, long))
    }

    @Test
    fun `a question with no overlap does not count`() {
        val long = "这个视频说短视频让人注意力下降"
        assertFalse(FinalReviewRules.quotesSource("你午饭吃了什么", long))
    }

    @Test
    fun `too short an overlap does not count`() {
        val long = "这个视频说短视频让人注意力下降"
        assertFalse(FinalReviewRules.quotesSource("你看这个", long))
    }

    @Test
    fun `a proper final review passes`() {
        val v = FinalReviewRules.check(input())
        assertTrue(v.hint, v.ok)
    }

    @Test
    fun `quote must come from the source`() {
        val v = FinalReviewRules.check(input(quote = "市场起基础性作用"))
        assertEquals(FinalReviewProblem.QUOTE_NOT_IN_SOURCE, v.problem)
    }

    @Test
    fun `quote must be long enough to be evidence`() {
        val v = FinalReviewRules.check(input(quote = "市场"))
        assertEquals(FinalReviewProblem.QUOTE_TOO_SHORT, v.problem)
    }

    @Test
    fun `punctuation and spaces do not break the quote match`() {
        val v = FinalReviewRules.check(
            input(quote = "市场 在资源配置中，起决定性作用"),
        )
        assertTrue(v.ok)
    }

    @Test
    fun `filler words do not count towards the reason`() {
        val v = FinalReviewRules.check(
            input(reason = "嗯，就是吧，反正我觉得这个不对"),
        )
        assertEquals(FinalReviewProblem.REASON_TOO_SHORT, v.problem)
    }

    @Test
    fun `reason has to be about the other option`() {
        val v = FinalReviewRules.check(
            input(reason = "它把原因和结果说反了，方向错了"),
        )
        assertEquals(FinalReviewProblem.REASON_NOT_ON_OTHER, v.problem)
    }

    @Test
    fun `chinese ordinal also targets the other option`() {
        val v = FinalReviewRules.check(
            input(reason = "第二项把充分条件说成了必要条件，方向反了"),
        )
        assertTrue(v.ok)
    }

    @Test
    fun `empty quote is rejected before anything else`() {
        val v = FinalReviewRules.check(input(quote = " "))
        assertEquals(FinalReviewProblem.QUOTE_EMPTY, v.problem)
    }

    @Test
    fun `a long sentence falls back to its last clause`() {
        val long = "第一句很短。第二句特别长长长长长长长长长长长长长长长长长长长长长长长"
        val span = FinalReviewRules.quotableSpan(long)
        assertTrue(span.length <= 12)
        assertTrue("取出来的片段必须还在原文里", long.contains(span))
        assertFalse(span.contains("第二句特别长长长长长长长"))
    }

    @Test
    fun `a short sentence is quoted whole`() {
        assertEquals("很短一句", FinalReviewRules.quotableSpan("很短一句"))
    }

    @Test
    fun `fallback question always quotes a real span`() {
        val text = "这道题说市场起基础性作用，但原文写的是起决定性作用"
        val q = FinalReviewRules.fallbackQuestion(text)
        assertTrue(q.contains("原文"))
        val span = FinalReviewRules.quotableSpan(text)
        assertTrue(q.contains(span))
    }

    @Test
    fun `no quotable text means no fallback question`() {
        assertEquals("", FinalReviewRules.fallbackQuestion("   "))
    }
}
