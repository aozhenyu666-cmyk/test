package com.yishou.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionLedgerTest {

    @Test
    fun `same question with different punctuation is duplicate`() {
        val a = "你同意这个观点吗？因为什么"
        val b = "你同意这个观点吗，因为什么？"
        assertTrue(QuestionLedger.isDuplicate(b, listOf(a)))
    }

    @Test
    fun `different questions are not duplicate`() {
        val a = "你同意短视频让人注意力下降吗？因为什么"
        val b = "这句话的证据是哪一条？原文怎么说"
        assertFalse(QuestionLedger.isDuplicate(b, listOf(a)))
    }

    @Test
    fun `empty ledger never duplicates`() {
        assertFalse(QuestionLedger.isDuplicate("随便一句", emptyList()))
    }

    @Test
    fun `remember puts the newest first and dedups`() {
        val kept = QuestionLedger.remember(listOf("旧问题", "更旧的问题"), "旧问题", max = 5)
        assertEquals(listOf("旧问题", "更旧的问题"), kept)
    }

    @Test
    fun `remember caps the history`() {
        val kept = QuestionLedger.remember(listOf("a", "b", "c"), "d", max = 2)
        assertEquals(listOf("d", "a"), kept)
    }

    @Test
    fun `similarity is high for a one word change`() {
        val a = "你同意作者的结论吗因为什么"
        val b = "你同意作者的结论吗因为什么理由"
        assertTrue(QuestionLedger.similarity(a, b) > 0.7)
    }

    @Test
    fun `similarity is zero when one side is empty`() {
        assertEquals(0.0, QuestionLedger.similarity("", "有内容"), 0.0001)
    }
}
