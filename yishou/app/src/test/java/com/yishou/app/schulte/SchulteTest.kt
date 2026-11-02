package com.yishou.app.schulte

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SchulteTest {

    private fun randomShuffler(seed: Int): (MutableList<Int>) -> Unit = { list ->
        val r = Random(seed)
        for (i in list.lastIndex downTo 1) {
            val j = r.nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }

    @Test fun 摆出来是_1到25_不多不少() {
        val b = Schulte.deal(5, randomShuffler(1))
        assertEquals(25, b.order.size)
        assertEquals((1..25).toList(), b.order.sorted())
        assertEquals(25, b.total)
    }

    @Test fun 同一个格子重开不会一样() {
        val a = Schulte.deal(5, randomShuffler(1)).order
        val b = Schulte.deal(5, randomShuffler(2)).order
        assertFalse("两次摆的一模一样，等于能背表", a == b)
    }

    @Test fun 按顺序点就一路推进_点到25收工() {
        val b = Schulte.deal(5, randomShuffler(7))
        var tapped = 0
        for (n in 1..25) {
            when (val r = Schulte.tap(b, tapped, n)) {
                is TapResult.Hit -> { assertEquals(n, r.next); tapped = n; assertEquals(n == 25, r.finished) }
                else -> throw AssertionError("第 $n 下应该点对，实际 $r")
            }
        }
        assertEquals(25, tapped)
    }

    @Test fun 点错不重置进度_只记一次漏点() {
        val b = Schulte.deal(5, randomShuffler(3))
        // 该点 1，却点了别的
        val miss = Schulte.tap(b, 0, 7)
        assertTrue(miss is TapResult.Miss)
        assertEquals(1, (miss as TapResult.Miss).expected)
        assertEquals(7, miss.actual)
        // 进度没动，接着点 1 还是对
        assertTrue(Schulte.tap(b, 0, 1) is TapResult.Hit)
    }

    @Test fun 点已经点过的格子不算错() {
        val b = Schulte.deal(5, randomShuffler(4))
        // 已经点过 1、2，再点 2 属于手滑回头，不算漏
        assertTrue(Schulte.tap(b, 2, 2) is TapResult.Ignored)
        assertTrue(Schulte.tap(b, 2, 1) is TapResult.Ignored)
        // 但点 3 照旧算对
        assertTrue(Schulte.tap(b, 2, 3) is TapResult.Hit)
    }

    @Test fun 分数_越快越高_漏点扣分() {
        val fast = Schulte.score(16_000, 0)
        val normal = Schulte.score(30_000, 0)
        val slow = Schulte.score(60_000, 0)
        assertTrue("快的应该比正常高", fast > normal)
        assertTrue("正常的应该比慢的高", normal > slow)
        // 同样时间，漏点越多分越低
        assertTrue(Schulte.score(30_000, 0) > Schulte.score(30_000, 4))
        // extreme 值不越界
        assertEquals(100, Schulte.score(1_000, 0))
        assertTrue(Schulte.score(600_000, 99) >= 0)
        assertEquals(0, Schulte.score(0, 0))
    }

    @Test fun 评价跟着准和快走() {
        assertTrue(Schulte.verdict(15_000, 0).contains("又准又快"))
        assertTrue(Schulte.verdict(70_000, 6).contains("慢下来"))
    }
}
