package com.zongkong.app.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 题库导入的纯逻辑：一行一题，题干||答案（|| 或全角｜），纯题干也行，重复的只留一条。 */
class BankTest {

    @Test
    fun plainLineBecomesQuestion() {
        val list = BankParse.parse("现在打开它要做什么？回来第一步是什么？")
        assertEquals(1, list.size)
        assertEquals("现在打开它要做什么？回来第一步是什么？", list[0].stem)
        assertEquals("", list[0].answer)
    }

    @Test
    fun doubleBarSplitsStemAndAnswer() {
        val list = BankParse.parse("这一趟准备看多久？||十分钟，然后去写代码")
        assertEquals(1, list.size)
        assertEquals("这一趟准备看多久？", list[0].stem)
        assertEquals("十分钟，然后去写代码", list[0].answer)
    }

    @Test
    fun fullWidthBarAlsoSplits() {
        val list = BankParse.parse("看完先做什么？｜先记一句话再关掉")
        assertEquals(1, list.size)
        assertEquals("看完先做什么？", list[0].stem)
        assertEquals("先记一句话再关掉", list[0].answer)
    }

    @Test
    fun blankLinesAndFencesAreSkipped() {
        val text = """
            # 我自己的题库

            - 第一题：这趟为什么要打开它？
            ```
            1. 第二题：做完怎么停？
            ```

            ---
            第三题：如果只看两分钟，先确认什么？
        """.trimIndent()
        val list = BankParse.parse(text)
        assertEquals(3, list.size)
        assertEquals("第一题：这趟为什么要打开它？", list[0].stem)
        assertEquals("第二题：做完怎么停？", list[1].stem)
        assertEquals("第三题：如果只看两分钟，先确认什么？", list[2].stem)
    }

    @Test
    fun duplicatesKeepFirstOne() {
        val list = BankParse.parse(
            """
                同一道题？
                同一道题？||换了答案
                另一道不同的题。
            """.trimIndent(),
        )
        assertEquals(2, list.size)
        assertEquals("同一道题？", list[0].stem)
        assertEquals("", list[0].answer)
        assertEquals("另一道不同的题。", list[1].stem)
    }

    @Test
    fun tooShortLinesAreIgnored() {
        val list = BankParse.parse("答案\n1\n\n这行有十二个字符所以算一道题吗")
        assertEquals(1, list.size)
        assertTrue(list[0].stem.startsWith("这行"))
    }

    @Test
    fun idsAreStableAcrossWhitespace() {
        val a = BankParse.parseLine("先做什么？ 然后呢")
        val b = BankParse.parseLine("先做什么？然后呢")
        requireNotNull(a)
        requireNotNull(b)
        assertEquals(a.id, b.id)
    }

    @Test
    fun parseLineRejectsEmpty() {
        assertNull(BankParse.parseLine("   "))
        assertNull(BankParse.parseLine("```"))
        assertNull(BankParse.parseLine("---"))
    }
}
