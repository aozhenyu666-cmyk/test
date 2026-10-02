package com.behaviordept.app

import com.behaviordept.app.data.Rating
import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.reminder.ReminderScheduler
import com.behaviordept.app.study.Spacing
import com.behaviordept.app.study.UnitStep
import com.behaviordept.app.study.currentStep
import com.behaviordept.app.today.NextAction
import com.behaviordept.app.today.computeNextAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StudyLogicTest {
    private val today = LocalDate.of(2026, 10, 2)

    @Test
    fun firstReviewIsNextDay() {
        assertEquals(0 to today.plusDays(1), Spacing.afterExplain(today))
    }

    @Test
    fun rememberMovesUpFuzzyStaysForgotResets() {
        assertEquals(1 to today.plusDays(3), Spacing.afterReview(0, Rating.REMEMBER, today))
        assertEquals(3 to today.plusDays(14), Spacing.afterReview(2, Rating.REMEMBER, today))
        assertEquals(2 to today.plusDays(7), Spacing.afterReview(2, Rating.FUZZY, today))
        assertEquals(0 to today.plusDays(1), Spacing.afterReview(4, Rating.FORGOT, today))
        assertEquals(5 to today.plusDays(60), Spacing.afterReview(5, Rating.REMEMBER, today))
    }

    private fun unit(id: Long, created: Long = id, pre: String = "", studied: Long? = null, explained: Long? = null, transferred: Long? = null, next: Long? = null) =
        StudyUnit(id = id, title = "u$id", material = "m", preQuestions = pre, studiedAt = studied, explainedAt = explained, transferredAt = transferred, nextReviewAt = next, createdAt = created)

    @Test
    fun stepsFollowFields() {
        assertEquals(UnitStep.PREVIEW, unit(1).currentStep())
        assertEquals(UnitStep.STUDY, unit(1, pre = "q").currentStep())
        assertEquals(UnitStep.EXPLAIN, unit(1, pre = "q", studied = 1).currentStep())
        assertEquals(UnitStep.TRANSFER, unit(1, pre = "q", studied = 1, explained = 1).currentStep())
        assertEquals(UnitStep.DONE, unit(1, pre = "q", studied = 1, explained = 1, transferred = 1).currentStep())
    }

    @Test
    fun dueReviewComesFirstThenFurthestStepThenNew() {
        val now = 1_000L
        val done = unit(1, pre = "q", studied = 1, explained = 1, transferred = 1, next = 2_000)
        val early = unit(2, pre = "q")
        val far = unit(3, pre = "q", studied = 1, explained = 1, next = 5_000)
        val due = unit(4, pre = "q", studied = 1, explained = 1, transferred = 1, next = 900)

        assertEquals(NextAction.Review(due), computeNextAction(listOf(done, early, far, due), now))
        val step = computeNextAction(listOf(done, early, far), now)
        assertTrue(step is NextAction.Step && step.unit.id == 3L && step.step == UnitStep.TRANSFER)
        assertEquals(NextAction.NewUnit, computeNextAction(listOf(done), now))
        assertEquals(NextAction.NewUnit, computeNextAction(emptyList(), now))
    }

    @Test
    fun reminderRollsToTomorrowWhenPassed() {
        val morning = LocalDateTime.of(2026, 10, 2, 7, 0)
        assertEquals(LocalDateTime.of(2026, 10, 2, 8, 30), ReminderScheduler.nextTrigger(8, 30, morning))
        val evening = LocalDateTime.of(2026, 10, 2, 21, 0)
        assertEquals(LocalDateTime.of(2026, 10, 3, 8, 30), ReminderScheduler.nextTrigger(8, 30, evening))
    }
}
