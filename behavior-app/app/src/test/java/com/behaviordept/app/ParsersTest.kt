package com.behaviordept.app

import com.behaviordept.app.ai.FormatException
import com.behaviordept.app.ai.Parsers
import com.behaviordept.app.data.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ParsersTest {
    @Test
    fun critiqueKeepsFixedOrder() {
        val text = """
            好的，下面是批改：
            【讲错了】
            - 把开放寻址说成了链地址
            【讲对了】
            - 哈希函数的作用
            - 冲突的定义
            【漏掉的关键点】
            - 装载因子
            【预习问题答到了吗】
            - 问题 1 答到了
            【下一步】
            - 重讲装载因子
        """.trimIndent()
        val sections = Parsers.critique(text)
        assertEquals(Parsers.CRITIQUE_TITLES, sections.map { it.title })
        assertEquals(listOf("哈希函数的作用", "冲突的定义"), sections[0].lines)
    }

    @Test
    fun critiqueMissingSectionFails() {
        assertThrows(FormatException::class.java) { Parsers.critique("【讲对了】\n- 好") }
    }

    @Test
    fun emptySectionBecomesNone() {
        val text = "【讲对了】\n- a\n【讲错了】\n【漏掉的关键点】\n- b\n【预习问题答到了吗】\n- c\n【下一步】\n- d"
        assertEquals(listOf("无"), Parsers.critique(text)[1].lines)
    }

    @Test
    fun questionsFromFencedJson() {
        val q = Parsers.questions("```json\n[\"为什么会冲突？\", \"举个新例子\", \"装载因子是什么\", \"多余的\"]\n```")
        assertEquals(3, q.size)
        assertEquals("为什么会冲突？", q[0])
    }

    @Test
    fun questionsTooFewFails() {
        assertThrows(FormatException::class.java) { Parsers.questions("[\"只有一道\"]") }
        assertThrows(FormatException::class.java) { Parsers.questions("没有数组") }
    }

    @Test
    fun gradeParsesMarksAndSuggestion() {
        val text = """
            【第 1 题】✓ 要点都在
            【第2题】△ 少了装载因子
            【第 3 题】✗ 答反了
            【建议】模糊：第三题需要重看
        """.trimIndent()
        val g = Parsers.grade(text, 3)
        assertEquals(listOf("✓", "△", "✗"), g.items.map { it.mark })
        assertEquals("少了装载因子", g.items[1].comment)
        assertEquals(Rating.FUZZY, g.suggestion)
        assertEquals("第三题需要重看", g.reason)
    }

    @Test
    fun gradeMissingQuestionFails() {
        assertThrows(FormatException::class.java) {
            Parsers.grade("【第 1 题】✓ 好\n【建议】记得 都对", 3)
        }
    }

    @Test
    fun gradeBadSuggestionFails() {
        assertThrows(FormatException::class.java) {
            Parsers.grade("【第 1 题】✓ 好\n【建议】还行", 1)
        }
    }

    @Test
    fun gradeRoundTripsThroughText() {
        val g = Parsers.grade("【第 1 题】✓ 好\n【第 2 题】✗ 错\n【建议】忘了 重学", 2)
        val sections = Parsers.sections(Parsers.gradeToText(g))
        assertEquals(listOf("第1题", "第2题", "建议"), sections.map { it.title })
    }

    @Test
    fun transferNeedsThreeSections() {
        val ok = "【成立的地方】\n- 都是缓存\n【不成立的地方】\n- 淘汰策略不同\n【再远一点】\n- 图书馆的还书车"
        assertEquals(Parsers.TRANSFER_TITLES, Parsers.transfer(ok).map { it.title })
        assertThrows(FormatException::class.java) { Parsers.transfer("【成立的地方】\n- 都是缓存") }
    }
}
