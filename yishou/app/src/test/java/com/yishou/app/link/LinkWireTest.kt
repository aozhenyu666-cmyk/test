package com.yishou.app.link

import com.yishou.app.summary.LedgerEntry
import com.yishou.app.summary.LedgerKind
import com.yishou.app.summary.LedgerMark
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一手 → 总控：今天的状态、每一手的平行数组。 */
class LinkWireTest {

    private fun e(at: Long, mark: LedgerMark, kind: LedgerKind = LedgerKind.CARD, what: String = "题$at") =
        LedgerEntry(at, kind, mark, what, said = "我说$at", note = "判$at")

    @Test fun statusCountsHitsIntoSets() {
        val today = listOf(
            e(1, LedgerMark.HIT), e(2, LedgerMark.HIT), e(3, LedgerMark.MISS),
            e(4, LedgerMark.HIT), e(5, LedgerMark.NEAR), e(6, LedgerMark.HIT, LedgerKind.SCROLL),
        )
        val s = LinkWire.status(today, "0.25.3", gateOn = true)
        assertEquals(6, s["roundsToday"])
        assertEquals(4, s["effectiveToday"])
        assertEquals(1, s["doneSets"])
        assertEquals(1, s["missToday"])
        assertEquals(6L, s["lastAt"])
        assertEquals(true, s["gateOn"])
        assertTrue(s["ledgerLine"] as String, (s["ledgerLine"] as String).startsWith("刷题 5（接住 3）"))
    }

    @Test fun ledgerIsSortedParallelArrays() {
        val m = LinkWire.ledger(listOf(e(20, LedgerMark.MISS), e(10, LedgerMark.HIT, LedgerKind.THREAD)))
        assertArrayEquals(longArrayOf(10, 20), m["at"] as LongArray)
        assertArrayEquals(arrayOf("THREAD", "CARD"), m["kind"] as Array<*>)
        assertArrayEquals(arrayOf("线程", "刷题"), m["kindLabel"] as Array<*>)
        assertArrayEquals(arrayOf("HIT", "MISS"), m["mark"] as Array<*>)
        assertArrayEquals(arrayOf("我说10", "我说20"), m["said"] as Array<*>)
        assertEquals(false, m["truncated"])
    }

    @Test fun ledgerKeepsTheNewestAndCutsLongText() {
        val many = (1..(LinkWire.MAX_ENTRIES + 5)).map { e(it.toLong(), LedgerMark.HIT, what = "长".repeat(400)) }
        val m = LinkWire.ledger(many)
        val at = m["at"] as LongArray
        assertEquals(LinkWire.MAX_ENTRIES, at.size)
        assertEquals(6L, at.first())
        assertEquals(true, m["truncated"])
        assertEquals(LinkWire.MAX_TEXT + 1, ((m["what"] as Array<*>)[0] as String).length)
    }
}
