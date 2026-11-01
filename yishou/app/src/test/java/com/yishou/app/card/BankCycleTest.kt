package com.yishou.app.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BankCycleTest {

    private val now = 10_000_000L

    @Test fun 没做过的先出() {
        val fresh = BankSlot(1, null, 100)
        val done = BankSlot(2, now - 10 * 3_600_000L, 50)
        assertEquals(1L, BankCycle.pick(listOf(done, fresh), now)!!.id)
    }

    @Test fun 做过的不报废_过了冷却按最久没做接回来() {
        val old = BankSlot(1, now - 30 * 3_600_000L, 10)
        val recent = BankSlot(2, now - 5 * 3_600_000L, 20)
        assertEquals(1L, BankCycle.pick(listOf(recent, old), now)!!.id)
    }

    @Test fun 刚做完的让开_全在冷却里也照样出题_不返回空() {
        val justNow = BankSlot(1, now - 60_000L, 10)
        val older = BankSlot(2, now - 30 * 60_000L, 20)
        assertEquals(2L, BankCycle.pick(listOf(justNow, older), now)!!.id)
        // 只剩一道、还在冷却：宁可重出，也不能说“没题可做”
        assertEquals(1L, BankCycle.pick(listOf(justNow), now)!!.id)
    }

    @Test fun 这一回用过的题让位给别的题() {
        val a = BankSlot(1, null, 10)
        val b = BankSlot(2, null, 20)
        assertEquals(2L, BankCycle.pick(listOf(a, b), now, exclude = setOf(1))!!.id)
        // 全都用过了：还得给一道，不能卡死
        assertEquals(1L, BankCycle.pick(listOf(a, b), now, exclude = setOf(1, 2))!!.id)
    }

    @Test fun 空题库才返回空() {
        assertNull(BankCycle.pick(emptyList(), now))
        assertTrue(BankCycle.cooling(BankSlot(1, now - 1_000L, 0), now))
        assertFalse(BankCycle.cooling(BankSlot(1, now - BankCycle.COOL_MS - 1, 0), now))
        assertFalse(BankCycle.cooling(BankSlot(1, null, 0), now))
    }
}
