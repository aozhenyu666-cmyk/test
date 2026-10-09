package com.yishou.app.summary

import com.yishou.app.FakeCoach
import com.yishou.app.FakeDao
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.llm.CoachParser
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.SummaryResult
import com.yishou.app.settings.AppPrefs
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SummaryEngineTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 9)
    private val now = WindowClock.at(day, 22, 30, zone)
    private val dao = FakeDao()
    private val coach = FakeCoach()
    private val engine = SummaryEngine(dao, coach, { AppPrefs() }, { now }, { zone })
    private val task = Task(id = 1, title = "资料分析", goal = "算对增长率", material = "材料", isCurrent = true, createdAt = 0)

    private fun round(h: Int, effective: Boolean, d: LocalDate = day) = Round(
        taskId = 1, source = RoundSource.GATE, triggerPackage = null, coachMove = "陪练问$h", userAnswer = "我答$h",
        effective = effective, moveType = if (effective) "比较" else "无", reason = "理由$h", feedback = "反馈$h",
        judged = true, createdAt = WindowClock.at(d, h, 0, zone),
    )

    @Test
    fun noRoundsMeansNoRequest() = runTest {
        dao.rounds += round(10, true, day.minusDays(1))
        val r = engine.run(day)
        assertTrue(r is SummaryEngine.Outcome.NoRecord)
        assertEquals(0, coach.summaryCalls)
        assertEquals(SummaryEngine.NO_RECORD, dao.summaries[day.toString()]!!.note)
    }

    @Test
    fun savesSummaryAndWritesTomorrowQuestion() = runTest {
        dao.currentTask = task
        dao.breakpoints[1] = Breakpoint(1, "已知", "卡点", "旧下一问", pendingCoachMove = "旧的一手", updatedAt = 0)
        dao.rounds += round(9, true)
        dao.rounds += round(15, false)
        coach.summaryResult = LlmResult.Ok(SummaryResult("基期未知 → 先算基期 → 少错", "你说“我答9”，那基期怎么求？", "推进到基期"))

        val r = engine.run(day) as SummaryEngine.Outcome.Saved
        assertEquals(2, r.summary.roundCount)
        assertEquals(1, r.summary.effectiveCount)
        assertEquals("基期未知 → 先算基期 → 少错", r.summary.rule)
        val bp = dao.breakpoints[1]!!
        assertEquals("你说“我答9”，那基期怎么求？", bp.nextQuestion)
        assertEquals("你说“我答9”，那基期怎么求？", bp.pendingCoachMove)
        assertEquals("已知", bp.known)
    }

    @Test
    fun failureSavesNothing() = runTest {
        dao.currentTask = task
        dao.rounds += round(9, true)
        coach.summaryResult = LlmResult.Err(LlmError.Timeout)
        assertEquals(SummaryEngine.Outcome.Failed(LlmError.Timeout), engine.run(day))
        assertTrue(dao.summaries.isEmpty())
    }

    @Test
    fun parsesSummary() {
        val s = CoachParser.parseSummary("""{"rule": "", "tomorrow_question": "问", "note": "进展"}""")
        assertEquals(SummaryResult("", "问", "进展"), s)
    }

    @Test
    fun markdownContainsEverything() {
        val rounds = listOf(round(9, true), round(15, false))
        val summary = com.yishou.app.data.DailySummary(day.toString(), "规则", "明天问", "进展", 2, 1, 45)
        val md = SummaryMarkdown.build(summary, task, Breakpoint(1, "已知", "卡点", "下一问", null, 0), rounds, zone)
        listOf("2026-10-09", "资料分析", "规则", "明天问", "进展", "陪练问9", "我答15", "09:00", "有效 · 比较", "无效", "45 分钟")
            .forEach { assertTrue(it, md.contains(it)) }
    }
}
