package com.yishou.app.round

import com.yishou.app.FakeCoach
import com.yishou.app.FakeDao
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.llm.BreakpointUpdate
import com.yishou.app.llm.Judgement
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.OpeningMove
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundEngineTest {

    private val task = Task(id = 7, title = "资料分析", goal = "算对增长率", material = null, isCurrent = true, createdAt = 0)
    private val dao = FakeDao()
    private val coach = FakeCoach()
    private val engine = RoundEngine(dao, coach, clock = { 1000L })

    private val goodAnswer = "增长率等于增长量除以基期量，所以先算出去年的值再比较"

    @Test
    fun answerRules() {
        assertEquals(3, AnswerRules.countChars("完成了"))
        assertEquals(3, AnswerRules.countChars("完成了！！！   "))
        assertEquals(4, AnswerRules.countChars("ab 12"))
        assertFalse(AnswerRules.isLongEnough("我会努力的，收到，完成了。"))
        assertTrue(AnswerRules.isLongEnough("一二三四五六七八九十一二三四五"))
        assertFalse(AnswerRules.isLongEnough("一二三四五六七八九十一二三四"))
    }

    @Test
    fun usesPendingMoveWithoutRequest() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = "已有的一手", updatedAt = 1)
        assertEquals(RoundEngine.MoveResult.Ready("已有的一手"), engine.currentMove(task))
        assertEquals(0, coach.openingCalls)
    }

    @Test
    fun openingStoresMoveInBreakpoint() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = null, updatedAt = 1)
        coach.openingResult = LlmResult.Ok(OpeningMove("开局一手", "缺关系"))
        assertEquals(RoundEngine.MoveResult.Ready("开局一手"), engine.currentMove(task))
        assertEquals("开局一手", dao.breakpoints[7]!!.pendingCoachMove)
        assertEquals("k", dao.breakpoints[7]!!.known)
    }

    @Test
    fun openingFailureSavesNothing() = runTest {
        coach.openingResult = LlmResult.Err(LlmError.Timeout)
        assertEquals(RoundEngine.MoveResult.Failed(LlmError.Timeout), engine.currentMove(task))
        assertTrue(dao.breakpoints.isEmpty())
    }

    @Test
    fun tooShortSendsNothing() = runTest {
        assertEquals(RoundEngine.AnswerResult.TooShort, engine.answer(task, "一手", "完成了", RoundSource.HOME))
        assertEquals(0, coach.judgeCalls)
        assertTrue(dao.rounds.isEmpty())
    }

    @Test
    fun judgedRoundIsSavedAndBreakpointUpdated() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "旧已知", "旧卡点", "旧下一问", pendingCoachMove = "这一手", updatedAt = 1)
        coach.judgeResult = LlmResult.Ok(
            Judgement(true, "中间结论", "有推导", "基期要用去年", BreakpointUpdate("新已知", "新卡点", "新下一问"), "下一手"),
        )
        val r = engine.answer(task, "这一手", goodAnswer, RoundSource.HOME) as RoundEngine.AnswerResult.Judged

        val saved = dao.rounds.single()
        assertEquals(saved.copy(id = 1), r.round)
        assertEquals("这一手", saved.coachMove)
        assertEquals(goodAnswer, saved.userAnswer)
        assertTrue(saved.effective)
        assertTrue(saved.judged)
        assertEquals("中间结论", saved.moveType)
        assertEquals(RoundSource.HOME, saved.source)
        assertNull(saved.triggerPackage)

        val bp = dao.breakpoints[7]!!
        assertEquals("新已知", bp.known)
        assertEquals("新卡点", bp.stuck)
        assertEquals("新下一问", bp.nextQuestion)
        assertEquals("下一手", bp.pendingCoachMove)
        assertEquals(1000L, bp.updatedAt)
    }

    @Test
    fun blankBreakpointFieldsKeepOldValues() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "旧已知", "旧卡点", "旧下一问", pendingCoachMove = "这一手", updatedAt = 1)
        coach.judgeResult = LlmResult.Ok(
            Judgement(false, "无", "只写了感受", "试着算一下", BreakpointUpdate("", "新卡点", " "), "更具体的支架"),
        )
        engine.answer(task, "这一手", goodAnswer, RoundSource.HOME)
        val bp = dao.breakpoints[7]!!
        assertEquals("旧已知", bp.known)
        assertEquals("新卡点", bp.stuck)
        assertEquals("旧下一问", bp.nextQuestion)
        assertEquals("更具体的支架", bp.pendingCoachMove)
        assertFalse(dao.rounds.single().effective)
    }

    @Test
    fun judgeFailureSavesNothing() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = "这一手", updatedAt = 1)
        coach.judgeResult = LlmResult.Err(LlmError.Network("断网"))
        val r = engine.answer(task, "这一手", goodAnswer, RoundSource.HOME)
        assertTrue(r is RoundEngine.AnswerResult.Failed)
        assertTrue(dao.rounds.isEmpty())
        assertEquals("这一手", dao.breakpoints[7]!!.pendingCoachMove)
    }

    @Test
    fun dontKnowSkipsLengthCheck() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = "这一手", updatedAt = 1)
        coach.judgeResult = LlmResult.Ok(Judgement(false, "无", "说了不知道", "", null, "更具体的支架"))
        val r = engine.answer(task, "这一手", "我不知道", RoundSource.WINDOW, skipLengthCheck = true)
        assertTrue(r is RoundEngine.AnswerResult.Judged)
        assertEquals("我不知道", coach.lastAnswer)
        assertEquals("更具体的支架", dao.breakpoints[7]!!.pendingCoachMove)
    }

    @Test
    fun offlineSaveKeepsBreakpointAndGrantsPass() = runTest {
        val bp = Breakpoint(7, "k", "s", "q", pendingCoachMove = "这一手", updatedAt = 1)
        dao.breakpoints[7] = bp
        val pass = engine.saveOffline(task, "这一手", goodAnswer, "com.ss.android.ugc.aweme", "短视频与社区", 5)

        val round = dao.rounds.single()
        assertFalse(round.judged)
        assertFalse(round.effective)
        assertEquals(goodAnswer, round.userAnswer)
        assertEquals(RoundSource.GATE, round.source)
        assertEquals("com.ss.android.ugc.aweme", round.triggerPackage)
        assertEquals(bp, dao.breakpoints[7])

        assertEquals(round.id, pass.roundId)
        assertEquals(1000L + 5 * 60_000L, pass.endAt)
        assertEquals(1, engine.offlineUsedSince(0))
        assertEquals(pass, dao.activePass("短视频与社区", 1000L + 5 * 60_000L - 1))
        assertNull(dao.activePass("短视频与社区", 1000L + 5 * 60_000L))
    }

    @Test
    fun grantPassUsesMinutes() = runTest {
        val p = engine.grantPass("游戏", 10, roundId = 3)
        assertEquals(1000L, p.startAt)
        assertEquals(1000L + 600_000L, p.endAt)
        assertEquals(3L, p.roundId)
        assertNull(dao.activePass("短视频与社区", 2000))
    }

    @Test
    fun composeAnswerWithImage() {
        assertEquals("文字", AnswerRules.compose("文字", null))
        assertEquals("文字\n【图片内容】算式", AnswerRules.compose(" 文字 ", "算式"))
        assertEquals("【图片内容】算式", AnswerRules.compose("", "算式"))
        assertTrue(AnswerRules.isLongEnough(AnswerRules.compose("短", "增长率等于增长量除以基期量所以先算基期")))
    }

    @Test
    fun contextCarriesRulesAndGap() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = null, updatedAt = 1)
        dao.summaries["2026-10-08"] = com.yishou.app.data.DailySummary("2026-10-08", "基期未知 → 先算基期 → 少错", "", "", 1, 1, 0)
        dao.rounds += Round(1, 7, RoundSource.GATE, null, "m", "上次原话", true, "比较", "", "", true, createdAt = 1000L - 7 * 3_600_000L)
        coach.openingResult = LlmResult.Ok(OpeningMove("开局", "跳步"))
        engine.currentMove(task)
        val c = coach.lastContext!!
        assertEquals(listOf("基期未知 → 先算基期 → 少错"), c.rules)
        assertEquals(7 * 60L, c.gapMinutes)
        assertEquals("上次原话", c.lastAnswer)
    }

    @Test
    fun observeReplacesPendingMoveAndKeepsBreakpoint() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "已知", "卡点", "下一问", pendingCoachMove = "旧的一手", updatedAt = 1)
        coach.observeResult = LlmResult.Ok(com.yishou.app.llm.ObservedMove("在做第 7 题", "第 7 题你先排除了哪个选项？", listOf("我先排除……")))
        val r = engine.observe(task, "粉笔，第 7 题，选项 A-D，未作答")
        assertTrue(r is LlmResult.Ok)
        assertEquals("粉笔，第 7 题，选项 A-D，未作答", coach.lastScreen)
        val bp = dao.breakpoints[7]!!
        assertEquals("第 7 题你先排除了哪个选项？", bp.pendingCoachMove)
        assertEquals("已知", bp.known)
        assertEquals("第 7 题你先排除了哪个选项？" to listOf("我先排除……"), engine.starters.value)
    }

    @Test
    fun judgeStartersFollowNextMove() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = "这一手", updatedAt = 1)
        coach.judgeResult = LlmResult.Ok(Judgement(true, "比较", "", "", null, "下一手", listOf("如果……")))
        engine.answer(task, "这一手", goodAnswer, RoundSource.HOME)
        assertEquals("下一手" to listOf("如果……"), engine.starters.value)
    }

    @Test
    fun rollForcesFaceAndRoundRecordsIt() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = "旧的一手", updatedAt = 1, pendingFace = 1)
        coach.openingResult = LlmResult.Ok(OpeningMove("预测一下第 3 题的走向？", "缺预测", face = 4))
        assertEquals(RoundEngine.MoveResult.Ready("预测一下第 3 题的走向？"), engine.rollFace(task, 2))
        assertEquals(2, coach.lastContext!!.forcedFace)
        val bp = dao.breakpoints[7]!!
        assertEquals("预测一下第 3 题的走向？", bp.pendingCoachMove)
        assertEquals(2, bp.pendingFace)

        coach.judgeResult = LlmResult.Ok(Judgement(true, "预测", "", "", null, "下一手", nextFace = 6))
        engine.answer(task, "预测一下第 3 题的走向？", goodAnswer, RoundSource.HOME)
        assertEquals(2, dao.rounds.single().face)
        assertEquals(6, dao.breakpoints[7]!!.pendingFace)
        assertEquals(listOf(com.yishou.app.data.FaceCount(2, 1, 1)), dao.faceCounts(0))
    }

    @Test
    fun demoKeepsFaceWhenModelOmitsIt() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = "卡住的一手", updatedAt = 1, pendingFace = 5)
        coach.openingResult = LlmResult.Ok(OpeningMove("示范：……现在你来", "跳步"))
        engine.demo(task)
        val c = coach.lastContext!!
        assertTrue(c.demo)
        assertEquals("卡住的一手", c.currentMove)
        assertEquals(5, dao.breakpoints[7]!!.pendingFace)
        assertEquals("示范：……现在你来", dao.breakpoints[7]!!.pendingCoachMove)
    }

    @Test
    fun answeringStaleMoveRecordsNoFace() = runTest {
        dao.breakpoints[7] = Breakpoint(7, "k", "s", "q", pendingCoachMove = "新的一手", updatedAt = 1, pendingFace = 3)
        coach.judgeResult = LlmResult.Ok(Judgement(true, "比较", "", "", null, "下一手"))
        engine.answer(task, "旧的一手", goodAnswer, RoundSource.HOME)
        assertEquals(0, dao.rounds.single().face)
    }
}
