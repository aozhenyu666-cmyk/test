package com.yishou.app

import com.yishou.app.data.AppDao
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.DailySummary
import com.yishou.app.data.Pass
import com.yishou.app.data.Round
import com.yishou.app.data.Task
import com.yishou.app.llm.Coach
import com.yishou.app.llm.CoachContext
import com.yishou.app.llm.Judgement
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.ObservedMove
import com.yishou.app.llm.OpeningMove
import com.yishou.app.llm.SummaryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class FakeCoach : Coach {
    var openingResult: LlmResult<OpeningMove> = LlmResult.Err(LlmError.NotConfigured)
    var judgeResult: LlmResult<Judgement> = LlmResult.Err(LlmError.NotConfigured)
    var summaryResult: LlmResult<SummaryResult> = LlmResult.Err(LlmError.NotConfigured)
    var observeResult: LlmResult<ObservedMove> = LlmResult.Err(LlmError.NotConfigured)
    var lastScreen: String? = null
    var openingCalls = 0
    var judgeCalls = 0
    var summaryCalls = 0
    var lastAnswer: String? = null
    var lastContext: CoachContext? = null

    override suspend fun opening(task: Task, breakpoint: Breakpoint, context: CoachContext): LlmResult<OpeningMove> {
        openingCalls++
        lastContext = context
        return openingResult
    }

    override suspend fun judge(
        task: Task,
        breakpoint: Breakpoint,
        coachMove: String,
        answer: String,
        context: CoachContext,
    ): LlmResult<Judgement> {
        judgeCalls++
        lastAnswer = answer
        lastContext = context
        return judgeResult
    }

    override suspend fun observe(
        task: Task,
        breakpoint: Breakpoint,
        screen: String,
        context: CoachContext,
    ): LlmResult<ObservedMove> {
        lastScreen = screen
        return observeResult
    }

    override suspend fun summary(task: Task?, breakpoint: Breakpoint?, rounds: List<Round>): LlmResult<SummaryResult> {
        summaryCalls++
        return summaryResult
    }
}

/** 内存版 DAO，实现引擎用到的部分。 */
class FakeDao : AppDao() {
    var currentTask: Task? = null
    val breakpoints = mutableMapOf<Long, Breakpoint>()
    val rounds = mutableListOf<Round>()
    val passes = mutableListOf<Pass>()
    val summaries = mutableMapOf<String, DailySummary>()

    override suspend fun getBreakpoint(taskId: Long) = breakpoints[taskId]
    override suspend fun upsertBreakpoint(breakpoint: Breakpoint) {
        breakpoints[breakpoint.taskId] = breakpoint
    }
    override suspend fun insertRound(round: Round): Long {
        rounds += round.copy(id = rounds.size + 1L)
        return rounds.size.toLong()
    }
    override suspend fun roundsBetween(from: Long, to: Long) =
        rounds.filter { it.createdAt in from until to }.sortedBy { it.createdAt }
    override fun observeRoundsSince(since: Long): Flow<List<Round>> = emptyFlow()
    override suspend fun lastRound() = rounds.maxByOrNull { it.createdAt }
    override suspend fun countRounds(source: String, from: Long, to: Long) =
        rounds.count { it.source == source && it.createdAt in from until to }
    override suspend fun recentRules(limit: Int) =
        summaries.values.filter { it.rule.isNotEmpty() }.sortedByDescending { it.date }.take(limit).map { it.rule }
    override suspend fun faceCounts(since: Long) =
        rounds.filter { it.createdAt >= since }.groupBy { it.face }
            .map { (f, rs) -> com.yishou.app.data.FaceCount(f, rs.size, rs.count { it.effective }) }
    override suspend fun countUnjudgedSince(since: Long) = rounds.count { !it.judged && it.createdAt >= since }
    override suspend fun countEffective(source: String, from: Long, to: Long) =
        rounds.count { it.source == source && it.effective && it.createdAt in from until to }
    override suspend fun insertPass(pass: Pass): Long {
        passes += pass.copy(id = passes.size + 1L)
        return passes.size.toLong()
    }
    override suspend fun activePass(group: String, now: Long) =
        passes.filter { it.packageGroup == group && it.endAt > now }.maxByOrNull { it.endAt }
    override suspend fun upsertSummary(summary: DailySummary) {
        summaries[summary.date] = summary
    }
    override suspend fun getSummary(date: String) = summaries[date]
    override fun observeSummaries(): Flow<List<DailySummary>> = emptyFlow()

    override fun observeCurrentTask(): Flow<Task?> = emptyFlow()
    override suspend fun getCurrentTask(): Task? = currentTask
    override suspend fun getTask(id: Long): Task? = null
    override fun observeAllTasks(): Flow<List<Task>> = emptyFlow()
    override suspend fun insertTask(task: Task): Long = 0
    override suspend fun updateTask(task: Task) {}
    override suspend fun clearCurrentFlag() {}
    override suspend fun setCurrentFlag(id: Long) {}
    override fun observeBreakpoint(taskId: Long): Flow<Breakpoint?> = emptyFlow()
}
