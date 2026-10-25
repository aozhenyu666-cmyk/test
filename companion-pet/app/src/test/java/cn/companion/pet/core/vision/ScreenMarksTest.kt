package cn.companion.pet.core.vision

import cn.companion.pet.core.device.NodeRef
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMarksTest {
    private fun n(text: String, l: Int, t: Int, r: Int, b: Int, clickable: Boolean = true, editable: Boolean = false, rid: String = "") =
        NodeRef(text + l + t, "o", "com.app", text, rid, "Button", clickable, editable, false, l, t, r, b)

    @Test
    fun numbersClickableAndEditableInReadingOrder() {
        val marks = ScreenMarks.plan(listOf(
            n("发送", 900, 2000, 1060, 2100),
            n("标题", 0, 100, 1080, 200, clickable = false),
            n("", 40, 2000, 860, 2100, clickable = false, editable = true),
            n("返回", 20, 120, 120, 220),
            n("小点", 500, 500, 510, 510),
            n("", 300, 300, 400, 400, rid = ""),
        ))
        assertEquals(listOf("返回", "", "发送"), marks.map { it.label })
        assertEquals(listOf(1, 2, 3), marks.map { it.number })
        assertEquals("输入框", marks[1].kind)
    }

    @Test
    fun capsAtTwentyAndTableMentionsNumbers() {
        val many = (0 until 30).map { n("项$it", 0, it * 100, 500, it * 100 + 80) }
        val marks = ScreenMarks.plan(many)
        assertEquals(20, marks.size)
        val table = ScreenMarks.promptTable(marks)
        assertTrue(table.contains("[1] 按钮“项0”"))
        assertTrue(table.contains("[20]"))
        assertEquals("", ScreenMarks.promptTable(emptyList()))
    }

    @Test
    fun mapsScreenToImageWithOriginAndScale() {
        val m = ScreenMark(1, 100, 300, 300, 500, "x", "按钮")
        // 窗口截图：窗口从屏幕 y=100 开始，图缩小一半。
        assertArrayEquals(intArrayOf(50, 100, 150, 200), ScreenMarks.toImage(m, 0, 100, 0.5f, 540, 1200))
        assertNull(ScreenMarks.toImage(m, 0, 2000, 0.5f, 540, 1200))
    }
}
