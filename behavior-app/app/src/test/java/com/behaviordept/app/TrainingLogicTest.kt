package com.behaviordept.app

import com.behaviordept.app.data.Drill
import com.behaviordept.app.data.Event
import com.behaviordept.app.data.EventType
import com.behaviordept.app.data.ExamSection
import com.behaviordept.app.data.ExamSitting
import com.behaviordept.app.data.MatchLog
import com.behaviordept.app.data.MatchResult
import com.behaviordept.app.data.Skill
import com.behaviordept.app.data.StudyUnit
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.data.Template
import com.behaviordept.app.today.NextAction
import com.behaviordept.app.today.computeNextAction
import com.behaviordept.app.training.Diagnosis
import com.behaviordept.app.training.ESSAY
import com.behaviordept.app.training.FocusRules
import com.behaviordept.app.training.Retest
import com.behaviordept.app.training.RetestState
import com.behaviordept.app.training.SkillPlan
import com.behaviordept.app.training.drillFeedback
import com.behaviordept.app.training.pickNextDrill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrainingLogicTest {
    private val day = 24 * 60 * 60 * 1000L
    private val cats = listOf("信息", "枪法", "身法", "决策")

    private fun death(t: Long, cause: String) = MatchLog(skillId = 1, time = t, result = MatchResult.DIED, causeCategory = cause)

    @Test
    fun causeSharesCountOnlyDeathsInWindow() {
        val matches = List(6) { death(it.toLong(), "身法") } + List(3) { death(10L + it, "枪法") } +
            MatchLog(skillId = 1, time = 20, result = MatchResult.EXTRACTED) + death(30, "信息")
        val shares = Diagnosis.causeShares(matches, cats)
        assertEquals(listOf(1, 3, 6, 0), shares.map { it.count })
        assertEquals("身法", Diagnosis.suggestion(shares)?.name)
        assertEquals(0.6, shares[2].share, 1e-9)
        // 窗口只看最近 4 次阵亡：信息 1、枪法 3
        val recent = Diagnosis.causeShares(matches, cats, window = 4)
        assertEquals(listOf(1, 3, 0, 0), recent.map { it.count })
    }

    @Test
    fun noDiagnosisWithoutData() {
        assertNull(Diagnosis.suggestion(Diagnosis.causeShares(emptyList(), cats)))
        assertFalse(Diagnosis.competitiveReady(List(19) { death(it.toLong(), "身法") }))
        assertTrue(Diagnosis.competitiveReady(List(20) { death(it.toLong(), "身法") }))
    }

    @Test
    fun examLostSharesUseRecentSittings() {
        val sittings = listOf(ExamSitting(id = 1, skillId = 2, time = 1, kind = "mock"), ExamSitting(id = 2, skillId = 2, time = 2, kind = "mock"))
        val sections = listOf(
            ExamSection(sittingId = 1, module = "资料分析", correct = 10, total = 20),
            ExamSection(sittingId = 1, module = "言语理解与表达", correct = 35, total = 40),
            ExamSection(sittingId = 2, module = "资料分析", correct = 12, total = 20),
        )
        val shares = Diagnosis.examLostShares(sittings, sections, listOf("言语理解与表达", "资料分析"))
        assertEquals(listOf(5, 18), shares.map { it.count })
        assertEquals("资料分析", Diagnosis.suggestion(shares)?.name)
        assertEquals(22.0 / 40, Diagnosis.moduleAccuracy(sections, "资料分析", setOf(1L, 2L))!!, 1e-9)
    }

    @Test
    fun focusLockedForSevenDays() {
        val s = Skill(id = 1, name = "x", template = Template.COMPETITIVE, focusSubSkillId = 3, focusSince = 0)
        assertFalse(FocusRules.canChange(s, 6 * day))
        assertTrue(FocusRules.canChange(s, 7 * day))
        assertTrue(FocusRules.canChange(s.copy(focusSubSkillId = null, focusSince = null), 0))
    }

    @Test
    fun competitiveRetestFlow() {
        val since = 100 * day
        val skill = Skill(id = 1, name = "三角洲", template = Template.COMPETITIVE, focusSubSkillId = 3, focusSince = since)
        val before = List(10) { death(since - (it + 1) * 1000, if (it < 5) "身法" else "枪法") }
        // 锁定期内 / 练得不够：还不到复测
        assertEquals(RetestState.NotDue, Retest.competitive(skill, "身法", 5, before, since + 3 * day))
        assertEquals(RetestState.NotDue, Retest.competitive(skill, "身法", 2, before, since + 8 * day))
        // 到期但复测数据不够
        val after = List(4) { death(since + 7 * day + it, "身法") }
        assertEquals(RetestState.Collecting(4, 10), Retest.competitive(skill, "身法", 3, before + after, since + 8 * day))
        // 复测数据够了：身法占比 50% → 20%
        val more = after.take(2) + List(8) { death(since + 7 * day + 100 + it, "枪法") }
        val r = Retest.competitive(skill, "身法", 3, before + more, since + 9 * day) as RetestState.Ready
        assertEquals(0.5, r.before!!, 1e-9)
        assertEquals(0.2, r.after, 1e-9)
        assertTrue(r.improved)
    }

    @Test
    fun examRetestComparesModuleAccuracyAndEssay() {
        val since = 50 * day
        val skill = Skill(id = 2, name = "考公", template = Template.EXAM, focusSubSkillId = 5, focusSince = since)
        val sittings = listOf(
            ExamSitting(id = 1, skillId = 2, time = since - 1, kind = "mock", essayScore = 55.0),
            ExamSitting(id = 2, skillId = 2, time = since + 8 * day, kind = "mock", essayScore = 63.0),
        )
        val sections = listOf(
            ExamSection(sittingId = 1, module = "资料分析", correct = 10, total = 20),
            ExamSection(sittingId = 2, module = "资料分析", correct = 15, total = 20),
        )
        val r = Retest.exam(skill, "资料分析", 4, sittings, sections, since + 9 * day) as RetestState.Ready
        assertEquals(0.5, r.before!!, 1e-9)
        assertEquals(0.75, r.after, 1e-9)
        assertTrue(r.improved)
        val essay = Retest.exam(skill, ESSAY, 4, sittings, sections, since + 9 * day) as RetestState.Ready
        assertEquals(0.63, essay.after, 1e-9)
        assertEquals(RetestState.Collecting(0, 1), Retest.exam(skill, "资料分析", 4, sittings.take(1), sections, since + 9 * day))
    }

    @Test
    fun drillFeedbackRespectsDirection() {
        val higher = Drill(id = 1, subSkillId = 1, method = "", minutes = 10, source = "x", metric = "命中率%")
        val lower = higher.copy(id = 2, metric = "被反打次数（越低越好）")
        fun ev(id: Long, t: Long, v: Double) = Event(time = t, type = EventType.DRILL_DONE, refId = id, value = v)
        val fb = drillFeedback(higher, listOf(ev(1, 1, 50.0), ev(1, 2, 70.0), ev(1, 3, 60.0)))
        assertEquals(60.0, fb.current)
        assertEquals(70.0, fb.previous)
        assertEquals(70.0, fb.best)
        assertFalse(fb.isBest)
        val fb2 = drillFeedback(lower, listOf(ev(2, 1, 6.0), ev(2, 2, 3.0)))
        assertEquals(3.0, fb2.best)
        assertTrue(fb2.isBest)
    }

    @Test
    fun nextDrillIsTheLeastRecentlyPracticed() {
        val a = Drill(id = 1, subSkillId = 1, method = "", minutes = 10, source = "x")
        val b = a.copy(id = 2)
        val events = listOf(Event(time = 5, type = EventType.DRILL_DONE, refId = 1), Event(time = 3, type = EventType.DRILL_DONE, refId = 2))
        assertEquals(2L, pickNextDrill(listOf(a, b), events)?.id)
        assertEquals(1L, pickNextDrill(listOf(a, b), events + Event(time = 9, type = EventType.DRILL_DONE, refId = 2))?.id)
    }

    @Test
    fun nextActionPrefersLearningThenRetestThenDrill() {
        val skill = Skill(id = 1, name = "三角洲", template = Template.COMPETITIVE, focusSubSkillId = 3, focusSince = 0)
        val focus = SubSkill(id = 3, skillId = 1, name = "身法", standard = "a")
        val drill = Drill(id = 9, subSkillId = 3, method = "m", minutes = 10, source = "x", title = "单向 peek")
        fun plan(retest: RetestState, drilled: Boolean = false) =
            SkillPlan(skill, focus, listOf(drill), drill, drilled, setOf(LocalDate.of(2026, 10, 1)), retest)
        val inProgress = StudyUnit(id = 1, title = "u", material = "m", createdAt = 1)

        assertTrue(computeNextAction(listOf(inProgress), 10, listOf(plan(RetestState.NotDue))) is NextAction.Step)
        assertTrue(computeNextAction(emptyList(), 10, listOf(plan(RetestState.NotDue))) is NextAction.Drill)
        assertEquals(NextAction.NewUnit, computeNextAction(emptyList(), 10, listOf(plan(RetestState.NotDue, drilled = true))))
        assertTrue(computeNextAction(emptyList(), 10, listOf(plan(RetestState.Collecting(1, 10)))) is NextAction.Retest)
    }
}
