package com.zongkong.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 一手刷题记录导入总控：解码、去重合并、一行账、给参谋的那一段。 */
class YishouLedgerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = 86_400_000L

    private fun e(at: Long, mark: String, kind: String = "CARD", label: String = "刷题", what: String = "题$at") =
        YishouEntry(at, kind, label, mark, what)

    @Test fun decodeParallelArrays() {
        val out = YishouLedger.decode(
            longArrayOf(1, 2, 3), arrayOf("CARD", "SCROLL", null), arrayOf("刷题", "刷着练", "x"),
            arrayOf("HIT", "MISS", "HIT"), arrayOf("a", "b", "c"), null, arrayOf("n1"),
        )
        assertEquals(2, out.size)
        assertEquals(YishouEntry(2, "SCROLL", "刷着练", "MISS", "b", "", ""), out[1])
        assertEquals("n1", out[0].note)
        assertEquals(emptyList<YishouEntry>(), YishouLedger.decode(null, null, null, null, null, null, null))
    }

    @Test fun mergeDedupesNewestWinsAndDropsOld() {
        val now = 100 * day
        val old = YishouBook(listOf(e(now - 40 * day, "HIT"), e(now - 1000, "MISS")))
        val merged = YishouLedger.merge(old, listOf(e(now - 1000, "HIT"), e(now - 10, "NEAR")), now)
        assertEquals(listOf(now - 1000, now - 10), merged.entries.map { it.at })
        assertEquals("HIT", merged.entries.first().mark)
        assertEquals(now, merged.pulledAt)
    }

    @Test fun sinceBacksOffAnHour() {
        assertEquals(0L, YishouLedger.since(YishouBook()))
        assertEquals(10 * 3_600_000L - 3_600_000L, YishouLedger.since(YishouBook(listOf(e(10 * 3_600_000L, "HIT")))))
    }

    @Test fun lineAndSets() {
        val xs = listOf(e(1, "HIT"), e(2, "HIT"), e(3, "MISS"), e(4, "HIT"), e(5, "NONE", "PARTNER", "伴读"))
        assertEquals("刷题 4（接住 3） · 伴读 1", YishouLedger.line(xs))
        assertEquals(1, YishouLedger.sets(xs))
        assertEquals("还没有记录", YishouLedger.line(emptyList()))
    }

    @Test fun digestListsMisses() {
        val d = YishouLedger.digest(listOf(e(1, "HIT"), e(2, "MISS", what = "论证缺了哪一层").copy(said = "不知道")), zone)
        assertTrue(d, d.startsWith("一手今天 2 手：刷题 2（接住 1）"))
        assertTrue(d, "没接住：论证缺了哪一层｜说的：不知道" in d)
        assertEquals("一手今天还没有刷题记录。", YishouLedger.digest(emptyList(), zone))
    }
}
