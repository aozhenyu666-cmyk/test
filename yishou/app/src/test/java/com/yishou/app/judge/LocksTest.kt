package com.yishou.app.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocksTest {

    @Test
    fun needRule() {
        assertEquals(2, Locks.need(2))
        assertEquals(2, Locks.need(3))
        assertEquals(3, Locks.need(4))
    }

    @Test
    fun bankCardBecomesLockedWithOptionsHidden() {
        val l = Locks.fromBank(
            id = "b1",
            passage = "研究发现，喝咖啡的人平均寿命更长。",
            question = "以下哪项最能削弱上述结论？",
            options = listOf("喝咖啡的人收入普遍更高、医疗条件更好", "咖啡含有抗氧化物质", "有人不喝咖啡也长寿", "咖啡价格上涨"),
            answer = 0,
            explain = "削弱要找他因：收入高同时导致喝咖啡和长寿。A 指出了这个共同原因；C 是个例，削弱力度弱。",
        )
        assertNotNull(l)
        l!!
        assertFalse(l.prompt.contains("收入普遍更高"))
        assertTrue(l.prompt.contains("先别看选项"))
        assertTrue(l.points.size >= 2)
        assertEquals(Locks.need(l.points.size), l.need)
        assertTrue(l.reference.startsWith("正确答案 A"))
    }

    @Test
    fun bankCardWithoutRealExplainIsSkipped() {
        assertNull(Locks.fromBank("b2", "", "选哪个？", listOf("甲", "乙"), 1, "选 B。"))
    }

    @Test
    fun generatedQuestionParses() {
        val text = """
            好的，题目如下：
            【题目】周末想和朋友去爬山，怎么定出发时间最稳妥？
            【要点】
            1. 查清楚山上当天的降雨时段，避开下雨的窗口 | 天上那件事你查了吗
            2. 问到每个同伴最早能出门的时间，取最晚的那个 | 人齐不齐
            3. 倒推回程交通的末班时间 | 回来怎么办
            【参考】先看雨的时段，再看人，再倒推末班车。
        """.trimIndent()
        val l = Locks.parseGen("g1", text)
        assertNotNull(l)
        assertEquals(3, l!!.points.size)
        assertEquals("人齐不齐", l.points[1].hint)
        assertEquals(2, l.need)
    }

    @Test
    fun generatedQuestionThatLeaksCriterionIsDropped() {
        val text = """
            【题目】为什么说县城的人根本不喝咖啡这个结论站不住？
            【要点】
            1. 县城的人根本不喝咖啡 | 想想
            2. 结论站不住 | 想想
            【参考】……
        """.trimIndent()
        assertNull(Locks.parseGen("g2", text))
    }

    @Test
    fun hintThatRepeatsCriterionIsBlanked() {
        val text = """
            【题目】一家店开业三个月就关了，你会先查哪几件事？
            【要点】
            1. 先查房租占营业额的比例是不是过高 | 先查房租占营业额的比例是不是过高
            2. 查周边同类店的数量和开店时间 | 旁边都有谁
            【参考】……
        """.trimIndent()
        val l = Locks.parseGen("g3", text)!!
        assertEquals("", l.points[0].hint)
        assertEquals("旁边都有谁", l.points[1].hint)
    }
}
