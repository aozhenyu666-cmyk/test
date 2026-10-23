package cn.companion.pet.core.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class AtomicBudgetTest {
    private class MemoryPersistence : BudgetPersistence {
        private val map = ConcurrentHashMap<String, Int>()
        override fun load(day: String): Int = map[day] ?: 0
        override fun save(day: String, used: Int): Boolean { map[day] = used; return true }
    }

    @Test
    fun onlyOneCallerGetsTheLastSlot() {
        val budget = AtomicBudget(MemoryPersistence())
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val successes = AtomicInteger(0)
        repeat(8) {
            thread {
                start.await()
                if (budget.reserve(1, "2026-10-23")) successes.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        assertEquals(1, successes.get())
        assertEquals(1, budget.used("2026-10-23"))
    }

    @Test
    fun restartRestoresUsedCount() {
        val persistence = MemoryPersistence()
        assertTrue(AtomicBudget(persistence).reserve(2, "2026-10-23"))
        val restarted = AtomicBudget(persistence)
        assertTrue(restarted.reserve(2, "2026-10-23"))
        assertFalse(restarted.reserve(2, "2026-10-23"))
        assertEquals(2, restarted.used("2026-10-23"))
    }

    @Test
    fun daysAreIndependent() {
        val budget = AtomicBudget(MemoryPersistence())
        assertTrue(budget.reserve(1, "2026-10-23"))
        assertFalse(budget.reserve(1, "2026-10-23"))
        assertTrue(budget.reserve(1, "2026-10-24"))
    }

    @Test
    fun zeroLimitNeverReserves() {
        val budget = AtomicBudget(MemoryPersistence())
        assertFalse(budget.reserve(0, "2026-10-23"))
        assertEquals(0, budget.used("2026-10-23"))
    }

    @Test
    fun failedPersistDoesNotReserve() {
        val failing = object : BudgetPersistence {
            override fun load(day: String): Int = 0
            override fun save(day: String, used: Int): Boolean = false
        }
        val budget = AtomicBudget(failing)
        assertFalse(budget.reserve(10, "2026-10-23"))
        assertEquals(0, budget.used("2026-10-23"))
    }
}