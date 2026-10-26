package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 五题换休息的结算规则（纯逻辑）。 */
class QuestionSetSettlementTest {

    @Test
    fun countsOnlyDistinctCredits() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        var st = SetState()
        repeat(6) { i ->
            val r = s.record(st, "q1", 1, PassKind.FIRST_TRY_INDEPENDENT, false, i.toLong())
            if (r is CreditResult.Counted) st = r.state
        }
        assertEquals(1, st.credits.size)
        assertFalse(s.eligible(st))
    }

    @Test
    fun fiveDistinctCreditsAreEligible() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        var st = SetState()
        repeat(5) { i ->
            st = (s.record(st, "q$i", 1, PassKind.FIRST_TRY_INDEPENDENT, false, i.toLong())
                as CreditResult.Counted).state
        }
        assertEquals(5, st.credits.size)
        assertTrue(s.eligible(st))
    }

    @Test
    fun newRubricVersionCountsAsNewCredit() {
        // 量表版本变了，等于一道不同的题。
        val s = QuestionSetSettlement(requiredCredits = 5)
        val a = (s.record(SetState(), "q1", 1, PassKind.FIRST_TRY_INDEPENDENT, false, 1_000)
            as CreditResult.Counted).state
        val b = s.record(a, "q1", 2, PassKind.FIRST_TRY_INDEPENDENT, false, 2_000)
        assertTrue(b is CreditResult.Counted)
        assertEquals(2, (b as CreditResult.Counted).state.credits.size)
    }

    @Test
    fun alreadyIssuedStopsEligibility() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        var st = SetState()
        repeat(5) { i ->
            st = (s.record(st, "q$i", 1, PassKind.FIRST_TRY_INDEPENDENT, false, i.toLong())
                as CreditResult.Counted).state
        }
        assertTrue(s.eligible(st))
        st = st.copy(grantIssued = true)
        assertFalse("已发过券就不再 eligible", s.eligible(st))
    }

    @Test
    fun independentRateIgnoresPrompted() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        var st = SetState()
        st = (s.record(st, "q1", 1, PassKind.FIRST_TRY_INDEPENDENT, false, 1_000)
            as CreditResult.Counted).state
        st = (s.record(st, "q2", 1, PassKind.PROMPTED_PASS, false, 2_000)
            as CreditResult.Counted).state
        // 两题里只有一题是独立通过。
        assertEquals(0.5, s.independentRate(st), 0.001)
    }

    @Test
    fun transferPassCountedSeparately() {
        val s = QuestionSetSettlement(requiredCredits = 5)
        val r = s.record(SetState(), "q1t", 1, PassKind.TRANSFER_PASS, false, 1_000)
        val st = (r as CreditResult.Counted).state
        assertEquals(1, st.transferDone)
        assertEquals(1, s.stats(st)[PassKind.TRANSFER_PASS])
    }
}
