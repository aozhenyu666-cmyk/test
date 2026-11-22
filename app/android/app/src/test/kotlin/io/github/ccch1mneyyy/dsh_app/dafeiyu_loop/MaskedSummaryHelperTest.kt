package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskedSummaryHelperTest {

    @Test
    fun triggersOcrFallbackWhenAccessibleCjkBelowThreshold() {
        assertTrue(MaskedSummaryHelper.needsOcrFallback(""))
        assertTrue(MaskedSummaryHelper.needsOcrFallback("Canvas view 12345 应用 画"))
        assertEquals(14, MaskedSummaryHelper.countCjkChars("一二三四五六七八九十甲乙丙丁"))
        assertTrue(MaskedSummaryHelper.needsOcrFallback("一二三四五六七八九十甲乙丙丁"))
        assertFalse(MaskedSummaryHelper.needsOcrFallback("一二三四五六七八九十甲乙丙丁戊"))
    }

    @Test
    fun mergeAccessibleAndOcrDeduplicatesMasksAndRespectsBudget() {
        val merged = MaskedSummaryHelper.mergeAccessibleAndOcr(
            accessibleText = "App: Canvas",
            ocrLines = listOf(
                "第3题 图形推理：对称轴数量规律",
                "第3题 图形推理：对称轴数量规律",
                "卡号 4111 1111 1111 1111",
            ),
            maxChars = 500,
        )
        requireNotNull(merged)
        assertTrue(merged.contains("[OCR]"))
        assertTrue(merged.contains("第3题 图形推理：对称轴数量规律"))
        assertTrue(merged.contains("«银行卡 ****1111»"))
        assertFalse(merged.contains("4111 1111 1111 1111"))

        val capped = MaskedSummaryHelper.mergeAccessibleAndOcr("base", listOf("a".repeat(300)), maxChars = 200)
        requireNotNull(capped)
        assertEquals(200, capped.length)
        assertNull(MaskedSummaryHelper.mergeAccessibleAndOcr("  ", listOf("   ", "")))
    }
}
