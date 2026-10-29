package cn.companion.pet.core.appointment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 约定解析：时间和话题都明确才能建；缺一样就**只问，不猜**。
 *
 * 这组测试对应阶段 3 要求 3.3-2：
 * 「时间或话题不明确时，只询问必要信息，不能悄悄猜一个重要约定」。
 */
class AppointmentParserTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun `十分钟后问进度能完整解析`() {
        val r = AppointmentParser.parse("十分钟后问问我项目进度")
        assertTrue("应当能直接建约定", r is ParseResult.Ready)
        val ready = r as ParseResult.Ready
        assertEquals(10 * minute, ready.dueInMillis)
        assertTrue("话题应当包含进度相关词", ready.topic.contains("进度") || ready.topic.contains("项目"))
        assertTrue("依据应当保留原话", ready.basis.contains("十分钟后"))
    }

    @Test
    fun `阿拉伯数字也能解析`() {
        val r = AppointmentParser.parse("30分钟后提醒我看文档")
        assertTrue(r is ParseResult.Ready)
        assertEquals(30 * minute, (r as ParseResult.Ready).dueInMillis)
    }

    @Test
    fun `两小时后这种中文数字能解析`() {
        val r = AppointmentParser.parse("两小时后问问我复习得怎么样")
        assertTrue(r is ParseResult.Ready)
        assertEquals(2 * hour, (r as ParseResult.Ready).dueInMillis)
    }

    @Test
    fun `只有意图没有时间时只问时间不猜`() {
        val r = AppointmentParser.parse("待会提醒我一下")
        assertTrue("缺时间时不能直接建", r is ParseResult.NeedTime)
    }

    @Test
    fun `有时间但没有话题时只问话题不猜`() {
        val r = AppointmentParser.parse("十分钟后提醒我")
        assertTrue("缺话题时不能直接建", r is ParseResult.NeedTopic)
        assertEquals(10 * minute, (r as ParseResult.NeedTopic).dueInMillis)
    }

    @Test
    fun `上下文有话题时可以补全`() {
        val r = AppointmentParser.parse("十分钟后提醒我", contextTopic = "项目进度")
        assertTrue("上下文能补话题，应当可以建", r is ParseResult.Ready)
        assertEquals("项目进度", (r as ParseResult.Ready).topic)
    }

    @Test
    fun `普通聊天不是约定`() {
        assertEquals(ParseResult.NotAppointment, AppointmentParser.parse("今天天气不错"))
        assertEquals(ParseResult.NotAppointment, AppointmentParser.parse("我在想那个问题"))
        assertEquals(ParseResult.NotAppointment, AppointmentParser.parse(""))
    }

    @Test
    fun `时间词不会被当成话题`() {
        // "十分钟后问问我" 里，"十分钟后"是时间，不该成为话题
        val r = AppointmentParser.parse("十分钟后问问我")
        assertTrue("没有真实话题时应当问话题", r is ParseResult.NeedTopic)
    }

    @Test
    fun `半小时能正确解析`() {
        // "半"作为数字给出 30 分钟
        val millis = AppointmentParser.parseRelativeMillis("半小时后")
        assertEquals(30 * minute, millis)
    }

    @Test
    fun `各种时间单位都对`() {
        assertEquals(1_000L, AppointmentParser.parseRelativeMillis("1秒后"))
        assertEquals(5 * minute, AppointmentParser.parseRelativeMillis("5分钟后"))
        assertEquals(3 * hour, AppointmentParser.parseRelativeMillis("3小时后"))
        assertEquals(2 * 24 * hour, AppointmentParser.parseRelativeMillis("2天后"))
    }

    @Test
    fun `人话描述不暴露内部枚举`() {
        val ready = ParseResult.Ready("项目进度", 10 * minute, "十分钟后问问我项目进度")
        val text = AppointmentParser.describe(ready)
        assertTrue("要包含时间", text.contains("10 分钟后"))
        assertTrue("要包含话题", text.contains("项目进度"))
        assertTrue("不能出现内部枚举名", !text.contains("Ready") && !text.contains("ParseResult"))
    }
}
