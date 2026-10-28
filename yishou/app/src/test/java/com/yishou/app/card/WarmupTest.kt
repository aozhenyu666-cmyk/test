package com.yishou.app.card

import com.yishou.app.coach.Warmup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WarmupTest {
    @Test
    fun chainStaysInRange() {
        repeat(200) { seed ->
            val c = Warmup.chain(Random(seed))
            var v = c.start
            c.steps.forEach { st ->
                val n = st.op.drop(1).toInt()
                v = when (st.op[0]) {
                    '+' -> v + n
                    '−' -> v - n
                    '×' -> v * n
                    else -> v / n
                }
                assertEquals(v, st.result)
                assertTrue(v in 1..99)
            }
        }
    }
}
