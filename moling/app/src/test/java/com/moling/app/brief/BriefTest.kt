package com.moling.app.brief

import org.junit.Assert.*
import org.junit.Test

class BriefTest {
    @Test fun emptyFieldsStillOfferDraft() {
        assertEquals("今天还没排水道，我先排一个草稿？", Brief.text())
    }
    @Test fun keepsOnlyFirstDiarySentenceAndOmitsMissingSections() {
        assertEquals("昨天：完成了报告。 今天：写提纲。", Brief.text("完成了报告。后来去散步。", "写提纲"))
    }
    @Test fun allSectionsHaveLabelsAndBoundedLength() {
        val text = Brief.text("昨".repeat(500), "今".repeat(500), "问".repeat(500), "预".repeat(500))
        assertTrue(text.contains("第一问：")); assertTrue(text.contains("预言：")); assertTrue(text.length <= 300)
    }
    @Test fun blanksAreOmitted() {
        val text = Brief.text(" \n", "", " ", " ")
        assertFalse(text.contains("昨天")); assertFalse(text.contains("第一问")); assertFalse(text.contains("预言"))
    }
}
