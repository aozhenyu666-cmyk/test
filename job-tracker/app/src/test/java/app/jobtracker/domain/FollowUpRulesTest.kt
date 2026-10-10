package app.jobtracker.domain

import app.jobtracker.config.AppConfig
import app.jobtracker.data.db.ApplicationEntity
import app.jobtracker.data.model.ApplicationStatus
import app.jobtracker.data.model.EndResult
import app.jobtracker.data.model.MatchLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FollowUpRulesTest {
    private val rules = FollowUpRules(AppConfig.Default)
    private val today = LocalDate.of(2026, 10, 10)

    private fun app(
        status: ApplicationStatus = ApplicationStatus.PENDING,
        appliedAt: LocalDate? = null,
        interviewAt: LocalDate? = null,
        next: LocalDate? = null,
        count: Int = 0,
        endResult: EndResult? = null,
    ) = ApplicationEntity(
        id = 1, company = "甲", title = "乙", status = status, endResult = endResult, jdText = "jd",
        matchLevel = MatchLevel.HIGH, analysisJson = "{}", appliedAt = appliedAt, interviewAt = interviewAt,
        nextFollowUpAt = next, followUpCount = count, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )

    @Test
    fun appliedFillsTodayAndFollowsUpThreeDaysLater() {
        val r = rules.changeStatus(app(), ApplicationStatus.APPLIED, today)
        assertEquals(ApplicationStatus.APPLIED, r.status)
        assertEquals(today, r.appliedAt)
        assertEquals(today.plusDays(3), r.nextFollowUpAt)
    }

    @Test
    fun appliedKeepsExistingAppliedDate() {
        val r = rules.changeStatus(app(appliedAt = today.minusDays(1)), ApplicationStatus.APPLIED, today)
        assertEquals(today.minusDays(1), r.appliedAt)
        assertEquals(today.plusDays(2), r.nextFollowUpAt)
    }

    @Test
    fun editingAppliedDateRecomputesAutoFollowUp() {
        val applied = rules.changeStatus(app(), ApplicationStatus.APPLIED, today)
        val r = rules.changeAppliedAt(applied, today.minusDays(2))
        assertEquals(today.plusDays(1), r.nextFollowUpAt)
    }

    @Test
    fun editingAppliedDateKeepsManualFollowUp() {
        val manual = rules.changeStatus(app(), ApplicationStatus.APPLIED, today).copy(nextFollowUpAt = today.plusDays(10))
        val r = rules.changeAppliedAt(manual, today.minusDays(2))
        assertEquals(today.minusDays(2), r.appliedAt)
        assertEquals(today.plusDays(10), r.nextFollowUpAt)
    }

    @Test
    fun interviewingWithDateFollowsUpNextDayAndResetsCount() {
        val start = app(ApplicationStatus.APPLIED, appliedAt = today, interviewAt = today.plusDays(5), next = today.plusDays(3), count = 2)
        val r = rules.changeStatus(start, ApplicationStatus.INTERVIEWING, today)
        assertEquals(today.plusDays(6), r.nextFollowUpAt)
        assertEquals(0, r.followUpCount)
    }

    @Test
    fun interviewingWithoutDateKeepsFollowUpUntilDateIsFilled() {
        val start = app(ApplicationStatus.APPLIED, appliedAt = today, next = today.plusDays(3))
        val r = rules.changeStatus(start, ApplicationStatus.INTERVIEWING, today)
        assertEquals(today.plusDays(3), r.nextFollowUpAt)

        val filled = rules.changeInterviewAt(r, today.plusDays(7))
        assertEquals(today.plusDays(8), filled.nextFollowUpAt)
    }

    @Test
    fun movingInterviewDateKeepsManualFollowUp() {
        val start = app(ApplicationStatus.INTERVIEWING, interviewAt = today, next = today.plusDays(9))
        val r = rules.changeInterviewAt(start, today.plusDays(2))
        assertEquals(today.plusDays(9), r.nextFollowUpAt)
    }

    @Test
    fun interviewDateFillsEmptyFollowUp() {
        val start = app(ApplicationStatus.INTERVIEWING)
        val r = rules.changeInterviewAt(start, today.plusDays(2))
        assertEquals(today.plusDays(3), r.nextFollowUpAt)
        val moved = rules.changeInterviewAt(r, today.plusDays(4))
        assertEquals(today.plusDays(5), moved.nextFollowUpAt)
    }

    @Test
    fun closedClearsFollowUpAndRequiresResult() {
        val start = app(ApplicationStatus.APPLIED, appliedAt = today, next = today.plusDays(3))
        val r = rules.changeStatus(start, ApplicationStatus.CLOSED, today, EndResult.REJECTED)
        assertEquals(EndResult.REJECTED, r.endResult)
        assertNull(r.nextFollowUpAt)

        val missing = runCatching { rules.changeStatus(start, ApplicationStatus.CLOSED, today) }
        assertEquals(IllegalArgumentException::class, missing.exceptionOrNull()!!::class)
    }

    @Test
    fun reopeningClearsResultAndAppliesRules() {
        val closed = app(ApplicationStatus.CLOSED, appliedAt = today.minusDays(1), endResult = EndResult.NO_RESPONSE)
        val r = rules.changeStatus(closed, ApplicationStatus.APPLIED, today)
        assertNull(r.endResult)
        assertEquals(today.plusDays(2), r.nextFollowUpAt)
    }

    @Test
    fun sameStatusIsNoOp() {
        val start = app(ApplicationStatus.APPLIED, appliedAt = today, next = today.plusDays(9))
        assertSame(start, rules.changeStatus(start, ApplicationStatus.APPLIED, today))
    }

    @Test
    fun dueStateDescribesFollowUpDate() {
        assertEquals(DueState.None, DueState.of(app(), today))
        assertEquals(DueState.Today, DueState.of(app(next = today), today))
        assertEquals(DueState.Overdue(2), DueState.of(app(next = today.minusDays(2)), today))
        assertEquals(DueState.Upcoming(3), DueState.of(app(next = today.plusDays(3)), today))
        assertEquals(DueState.None, DueState.of(app(ApplicationStatus.CLOSED, next = today, endResult = EndResult.OFFER), today))
    }
}
