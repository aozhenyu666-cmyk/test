package com.moling.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R5：选择赌局的开启 / 去重 / 结算 / 过期 / 无法观测 / 取消 / Brier。 */
class BetTest {
    private val t0 = 2_000_000L
    private fun open(id: String = "b-1", kind: BetKind = BetKind.FUN, p: Double = -1.0): Pair<List<ChoiceBet>, ChoiceBet> =
        Bets.open(emptyList(), id, "radio-1", "我赌你会选 A", listOf("A", "B", "退出"), "A", t0 + 60_000, t0, kind, p)!!

    @Test fun sameIdShownThreeTimesIsStillOneBet() {
        var list = emptyList<ChoiceBet>()
        repeat(3) {
            val (next, b) = Bets.open(list, "b-1", "radio-1", "我赌你会选 A", listOf("A", "B"), "A", t0 + 60_000, t0)!!
            list = next
            assertEquals("b-1", b.id)
        }
        assertEquals(1, list.size)
    }

    @Test fun invalidGuessOrTooFewOptionsIsRejected() {
        assertNull(Bets.open(emptyList(), "b-1", "s", "我赌", listOf("A"), "A", t0 + 1, t0))
        assertNull(Bets.open(emptyList(), "b-1", "s", "我赌", listOf("A", "B"), "C", t0 + 1, t0))
        assertNull(Bets.open(emptyList(), "b-1", "s", "  ", listOf("A", "B"), "A", t0 + 1, t0))
    }

    @Test fun settleOnceAndRepeatedSettleNeverDoubleCounts() {
        val (_, b) = open()
        val won = Bets.settle(b, b.pick, t0 + 10_000, "他点了 A")
        assertEquals(BetState.WON, won.state)
        val again = Bets.settle(won, won.options.first { it.id != won.pick }.id, t0 + 20_000)
        assertEquals("重复结算不改结果，也不会补记第二笔", won, again)
    }

    @Test fun observedOtherOptionIsLossAndStillSettles() {
        val (_, b) = open()
        val lost = Bets.settle(b, b.options.first { it.label == "B" }.id, t0 + 20_000)
        assertEquals(BetState.LOST, lost.state)
        assertEquals("B", lost.options.first { it.id == lost.observedOptionId }.label)
    }

    @Test fun noObservationBecomesUnobservableNotLoss() {
        val (_, b) = open()
        assertEquals(BetState.UNOBSERVABLE, Bets.settle(b, null, t0 + 20_000).state)
        assertEquals(BetState.UNOBSERVABLE, Bets.settle(b, "not-in-this-bet", t0 + 20_000).state)
    }

    @Test fun deadlineExpiresToUnobservable() {
        val (_, b) = open()
        assertEquals(BetState.OPEN, Bets.expire(b, t0 + 59_000).state)
        val late = Bets.expire(b, t0 + 60_000)
        assertEquals(BetState.UNOBSERVABLE, late.state)
        assertTrue(late.evidence.contains("截止"))
    }

    @Test fun cancelIsNotLoss() {
        val (_, b) = open()
        val c = Bets.cancel(b, t0 + 1_000)
        assertEquals(BetState.CANCELLED, c.state)
        assertFalse(c.settled && c.state == BetState.LOST)
        assertTrue(Bets.line(c).contains("不算输"))
    }

    @Test fun brierOnlyCountsScoredSettledBets() {
        var list = emptyList<ChoiceBet>()
        val (l1, funBet) = Bets.open(list, "fun", "s", "猜", listOf("A", "B"), "A", t0 + 60_000, t0)!!
        list = l1
        val (l2, scored) = Bets.open(list, "scored", "s", "猜", listOf("A", "B"), "A", t0 + 60_000, t0, BetKind.SCORED, 0.8)!!
        list = l2
        assertEquals(-1.0, Bets.brier(list), 0.0001)
        list = list.map {
            when (it.id) {
                funBet.id -> Bets.settle(it, it.pick, t0 + 10)
                else -> Bets.settle(it, it.pick, t0 + 10)
            }
        }
        assertEquals("0.8 猜对 → (0.8-1)^2 = 0.04；玩笑局不进 Brier", 0.04, Bets.brier(list), 0.0001)
        // 旧娱乐分钟预测继续走 Predict.brier，这里只负责可评分赌局
        assertEquals(1, Bets.scoredOnly(list).size)
    }

    @Test fun funBetNeverShowsFakeProbability() {
        val (_, b) = open(kind = BetKind.FUN, p = 0.9)
        assertTrue("玩笑局不显示概率", !Bets.line(b).contains("%"))
        assertEquals(-1.0, b.p, 0.0001)
        val (_, s) = open(id = "b-2", kind = BetKind.SCORED, p = 0.9)
        assertTrue(Bets.line(s).contains("90%"))
    }
}