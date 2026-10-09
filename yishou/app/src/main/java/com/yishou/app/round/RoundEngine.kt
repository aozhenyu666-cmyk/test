package com.yishou.app.round

import com.yishou.app.data.AppDao
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.Task
import com.yishou.app.llm.Coach
import com.yishou.app.llm.Judgement
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult

/**
 * “一轮”：读断点 → 陪练出一手 → 用户回答 → 判定 → 更新断点、保存 Round。
 * 主页、入口思考页（M2）、陪练窗口（M3）都用这一个类。
 */
class RoundEngine(
    private val dao: AppDao,
    private val coach: Coach,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface MoveResult {
        data class Ready(val coachMove: String) : MoveResult
        data class Failed(val error: LlmError) : MoveResult
    }

    sealed interface AnswerResult {
        /** 少于 [AnswerRules.MIN_CHARS] 个字，没有发请求 */
        data object TooShort : AnswerResult
        data class Judged(val round: Round, val judgement: Judgement) : AnswerResult
        /** 请求失败，什么都没保存 */
        data class Failed(val error: LlmError) : AnswerResult
    }

    /**
     * 拿到这一轮陪练的一手：断点里已有没回答的一手就直接用，否则发开局请求并存进断点。
     */
    suspend fun currentMove(task: Task): MoveResult {
        val bp = dao.getBreakpoint(task.id) ?: emptyBreakpoint(task.id)
        bp.pendingCoachMove?.takeIf { it.isNotBlank() }?.let { return MoveResult.Ready(it) }

        return when (val r = coach.opening(task, bp)) {
            is LlmResult.Err -> MoveResult.Failed(r.error)
            is LlmResult.Ok -> {
                // 请求期间断点可能被手动改过，以最新的为准，只填上这一手
                val latest = dao.getBreakpoint(task.id) ?: bp
                dao.upsertBreakpoint(latest.copy(pendingCoachMove = r.value.coachMove, updatedAt = clock()))
                MoveResult.Ready(r.value.coachMove)
            }
        }
    }

    /**
     * 提交一次回答。判定成功后，在同一个事务里保存 Round 并覆盖断点；
     * 新断点里带上陪练的下一手，作为下一轮的题目。
     */
    suspend fun answer(
        task: Task,
        coachMove: String,
        answer: String,
        source: String,
        triggerPackage: String? = null,
    ): AnswerResult {
        if (!AnswerRules.isLongEnough(answer)) return AnswerResult.TooShort

        val bp = dao.getBreakpoint(task.id) ?: emptyBreakpoint(task.id)
        val judgement = when (val r = coach.judge(task, bp, coachMove, answer)) {
            is LlmResult.Err -> return AnswerResult.Failed(r.error)
            is LlmResult.Ok -> r.value
        }

        val now = clock()
        val update = judgement.breakpoint
        val newBp = bp.copy(
            // 模型漏给某一项时保留原值，不让断点被清空
            known = update?.known?.ifBlank { null } ?: bp.known,
            stuck = update?.stuck?.ifBlank { null } ?: bp.stuck,
            nextQuestion = update?.nextQuestion?.ifBlank { null } ?: bp.nextQuestion,
            pendingCoachMove = judgement.nextCoachMove,
            updatedAt = now,
        )
        val round = Round(
            taskId = task.id,
            source = source,
            triggerPackage = triggerPackage,
            coachMove = coachMove,
            userAnswer = answer,
            effective = judgement.effective,
            moveType = judgement.moveType,
            reason = judgement.reason,
            feedback = judgement.feedback,
            judged = true,
            createdAt = now,
        )
        val id = dao.saveRound(round, newBp)
        return AnswerResult.Judged(round.copy(id = id), judgement)
    }

    private fun emptyBreakpoint(taskId: Long) =
        Breakpoint(taskId, known = "", stuck = "", nextQuestion = "", pendingCoachMove = null, updatedAt = clock())
}

object AnswerRules {
    const val MIN_CHARS = 15

    /** 只数文字和数字，空格、标点、表情不算。一个汉字算 1。 */
    fun countChars(text: String): Int =
        text.codePoints().filter { Character.isLetterOrDigit(it) }.count().toInt()

    fun isLongEnough(text: String): Boolean = countChars(text) >= MIN_CHARS
}
