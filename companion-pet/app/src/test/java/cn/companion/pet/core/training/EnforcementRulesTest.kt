package cn.companion.pet.core.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementRulesTest {

    @Test
    fun technicalFailureNeverForcesCoach() {
        val r = EnforcementRules()
        val d = r.evaluate(TriggerReason.TECHNICAL, InteractionMode.WARM, hasPendingQuestion = true)
        assertEquals(EnforcementAction.DOWNGRADE_TECHNICAL, d.action)
        assertFalse(d.countsAsViolation)
        assertEquals(0, r.violationCount)
    }

    @Test
    fun normalAnswerDoesNothing() {
        val r = EnforcementRules()
        val d = r.evaluate(TriggerReason.NORMAL, InteractionMode.WARM, hasPendingQuestion = true)
        assertEquals(EnforcementAction.NONE, d.action)
        assertFalse(d.countsAsViolation)
    }

    @Test
    fun silenceWithoutQuestionIsNotViolation() {
        val r = EnforcementRules()
        val d = r.evaluate(TriggerReason.NO_RESPONSE, InteractionMode.WARM, hasPendingQuestion = false)
        assertEquals(EnforcementAction.NONE, d.action)
        assertEquals(0, r.violationCount)
    }

    @Test
    fun firstViolationOnlyGivesOneMoreChance() {
        val r = EnforcementRules(violationLimit = 2)
        val d = r.evaluate(TriggerReason.REFUSAL, InteractionMode.WARM, hasPendingQuestion = true)
        assertEquals(EnforcementAction.ONE_MORE_CHANCE, d.action)
        assertTrue(d.countsAsViolation)
        assertEquals(1, r.violationCount)
    }

    @Test
    fun secondViolationForcesCoachMode() {
        val r = EnforcementRules(violationLimit = 2)
        r.evaluate(TriggerReason.REFUSAL, InteractionMode.WARM, hasPendingQuestion = true)
        val d = r.evaluate(TriggerReason.NO_RESPONSE, InteractionMode.WARM, hasPendingQuestion = true)
        assertEquals(EnforcementAction.FORCE_COACH, d.action)
        assertEquals(2, r.violationCount)
    }

    @Test
    fun fabricationAlsoCountsAndForces() {
        val r = EnforcementRules(violationLimit = 2)
        r.evaluate(TriggerReason.FABRICATION, InteractionMode.WARM, hasPendingQuestion = true)
        val d = r.evaluate(TriggerReason.FABRICATION, InteractionMode.WARM, hasPendingQuestion = true)
        assertEquals(EnforcementAction.FORCE_COACH, d.action)
    }

    @Test
    fun alreadyCoachLocksSlotAndNeverDegrades() {
        val r = EnforcementRules(violationLimit = 2)
        val d = r.evaluate(TriggerReason.REFUSAL, InteractionMode.COACH, hasPendingQuestion = true)
        assertEquals(EnforcementAction.LOCK_COACH_SLOT, d.action)
        assertTrue(d.countsAsViolation)
        // 再多次也不会退回温和或放弃限制
        repeat(5) {
            val again = r.evaluate(TriggerReason.NO_RESPONSE, InteractionMode.COACH, hasPendingQuestion = true)
            assertEquals(EnforcementAction.LOCK_COACH_SLOT, again.action)
        }
    }

    @Test
    fun resetClearsViolations() {
        val r = EnforcementRules(violationLimit = 2)
        r.evaluate(TriggerReason.REFUSAL, InteractionMode.WARM, hasPendingQuestion = true)
        r.reset()
        assertEquals(0, r.violationCount)
        val d = r.evaluate(TriggerReason.REFUSAL, InteractionMode.WARM, hasPendingQuestion = true)
        assertEquals(EnforcementAction.ONE_MORE_CHANCE, d.action)
    }
}
