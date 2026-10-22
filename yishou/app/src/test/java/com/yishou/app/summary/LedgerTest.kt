package com.yishou.app.summary

import com.yishou.app.data.ScrollQuizRow
import com.yishou.app.data.Soup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LedgerTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun e(at: Long, kind: LedgerKind, mark: LedgerMark, what: String = "题$at") = LedgerEntry(at, kind, mark, what)

    @Test
    fun lineCountsEachKind() {
        val xs = listOf(
            e(1, LedgerKind.CARD, LedgerMark.HIT), e(2, LedgerKind.CARD, LedgerMark.MISS),
            e(3, LedgerKind.SCROLL, LedgerMark.NEAR), e(4, LedgerKind.PARTNER, LedgerMark.NONE),
        )
        assertEquals("刷题 2（接住 1） · 刷着练 1（接住 0） · 伴读 1", Ledger.line(xs))
    }

    @Test
    fun digestPutsMissesFirstWhenTrimmed() {
        val xs = (1..10).map { e(it * 60_000L, LedgerKind.CARD, LedgerMark.HIT) } + e(11 * 60_000L, LedgerKind.SCROLL, LedgerMark.MISS, "刷到的那条")
        val d = Ledger.digest(xs, zone, max = 3)
        assertTrue(d.startsWith("共 11 手"))
        assertTrue(d.contains("刷到的那条"))
        assertTrue(d.contains("只列 3 手"))
        assertEquals("", Ledger.digest(emptyList(), zone))
    }

    @Test
    fun scrollRows() {
        val base = ScrollQuizRow(askedAt = 1, lens = "GAP", seen = "咖啡店", question = "前提是什么？", reference = "需求")
        assertNull(LedgerFrom.scroll(base))
        assertNull(LedgerFrom.scroll(base.copy(answeredAt = 5, verdict = "SKIP")))
        val hit = LedgerFrom.scroll(base.copy(answeredAt = 5, answer = "默认没人喝", verdict = "HIT"))!!
        assertEquals(LedgerMark.HIT, hit.mark)
        assertEquals(5, hit.at)
        assertEquals(LedgerMark.NONE, LedgerFrom.scroll(base.copy(answeredAt = 5, answer = "判卷失败了"))!!.mark)
        assertEquals(LedgerMark.NEAR, LedgerFrom.scrollRedo(base.copy(redoAt = 9, redoVerdict = "NEAR"))!!.mark)
    }

    @Test
    fun soupMarks() {
        val s = Soup(cardId = 1, asks = 6, guesses = 1, solved = true, createdAt = 1)
        assertEquals(LedgerMark.HIT, LedgerFrom.soup(s, "题").mark)
        assertEquals(LedgerMark.NEAR, LedgerFrom.soup(s.copy(solved = false, correct = true), "题").mark)
        assertEquals(LedgerMark.NONE, LedgerFrom.soup(s.copy(asks = -1), "题").mark)
        assertTrue(Ledger.good(LedgerMark.NONE))
    }
}
