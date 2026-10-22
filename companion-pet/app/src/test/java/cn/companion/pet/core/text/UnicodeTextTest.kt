package cn.companion.pet.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class UnicodeTextTest {

    @Test
    fun count_emptyAndAscii() {
        assertEquals(0, UnicodeText.count(""))
        assertEquals(5, UnicodeText.count("hello"))
    }

    @Test
    fun count_countsCodePointsNotChars() {
        // 补充平面表情是 2 个 UTF-16 char，但只算 1 个码点
        assertEquals(2, "😀".length)
        assertEquals(1, UnicodeText.count("😀"))
        assertEquals(3, UnicodeText.count("a😀b"))
        assertEquals(5, UnicodeText.count("中文表情😀"))
    }

    @Test
    fun truncate_negativeThrows() {
        try {
            UnicodeText.truncate("abc", -1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun truncate_zeroAndNoop() {
        assertEquals("", UnicodeText.truncate("abc", 0))
        assertEquals("", UnicodeText.truncate("", 10))
        assertEquals("abc", UnicodeText.truncate("abc", 10))
    }

    @Test
    fun truncate_exactBoundary() {
        val text = "字".repeat(600)
        assertEquals(600, UnicodeText.count(UnicodeText.truncate(text, 600)))
        assertEquals(text, UnicodeText.truncate(text, 600))
        assertEquals(599, UnicodeText.count(UnicodeText.truncate(text, 599)))
    }

    @Test
    fun truncate_doesNotAppendEllipsis() {
        val result = UnicodeText.truncate("一二三四五六七八九十", 4)
        assertEquals("一二三四", result)
    }

    @Test
    fun truncate_keepsSurrogatePairsIntact() {
        val text = "😀".repeat(10)
        val result = UnicodeText.truncate(text, 3)
        assertEquals(3, UnicodeText.count(result))
        assertEquals("😀😀😀", result)
        // 截断点不能落在代理对中间
        assertEquals(6, result.length)
    }
}
