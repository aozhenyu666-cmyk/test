package com.yishou.app.round

import com.yishou.app.data.AppDao
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.llm.BreakpointUpdate
import com.yishou.app.llm.Coach
import com.yishou.app.llm.Judgement
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.OpeningMove
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
}

private class FakeCoach : Coach {
    var openingResult: LlmResult<OpeningMove> = LlmResult.Err(LlmError.NotConfigured)
    var judgeResult: LlmResult<Judgement> = LlmResult.Err(LlmError.NotConfigured)
    var openingCalls = 0
    var judgeCalls = 0

    override suspend fun opening(task: Task, breakpoint: Breakpoint): LlmResult<OpeningMove> {
        openingCalls++
        return openingResult
    }

    override suspend fun judge(task: Task, breakpoint: Breakpoint, coachMove: String, answer: String): LlmResult<Judgement> {
        judgeCalls++
        return judgeResult
    }
}

/** 内存版 DAO，只实现 RoundEngine 用到的部分。 */
private class FakeDao : AppDao() {
    val breakpoints = mutableMapOf<Long, Breakpoint>()
    val rounds = mutableListOf<Round>()

    override suspend fun getBreakpoint(taskId: Long) = breakpoints[taskId]
    override suspend fun upsertBreakpoint(breakpoint: Breakpoint) {
        breakpoints[breakpoint.taskId] = breakpoint
    }
    override suspend fun insertRound(round: Round): Long {
        rounds += round
        return rounds.size.toLong()
    }

    override fun observeCurrentTask(): Flow<Task?> = emptyFlow()
    override suspend fun getCurrentTask(): Task? = null
    override suspend fun getTask(id: Long): Task? = null
    override fun observeAllTasks(): Flow<List<Task>> = emptyFlow()
    override suspend fun insertTask(task: Task): Long = 0
    override suspend fun updateTask(task: Task) {}
    override suspend fun clearCurrentFlag() {}
    override suspend fun setCurrentFlag(id: Long) {}
    override fun observeBreakpoint(taskId: Long): Flow<Breakpoint?> = emptyFlow()
}
