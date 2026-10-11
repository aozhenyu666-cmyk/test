package com.yishou.app.round

import com.yishou.app.data.AppDao
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Pass
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import com.yishou.app.llm.Coach
import com.yishou.app.llm.CoachContext
import com.yishou.app.llm.Judgement
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.ObservedMove
import com.yishou.app.llm.Prompts
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * “一轮”：读断点 → 陪练出一手 → 用户回答 → 判定 → 更新断点、保存 Round。
 * 主页、入口思考页（M2）、陪练窗口（M3）都用这一个类。
 */
class RoundEngine(
    private val dao: AppDao,
    private val coach: Coach,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 最近一手配的起手式（这一手的原文 → 两个半句话）。只放在内存里，
     * 页面拿到的这一手和这里的原文一致时才显示。
     */
    val starters = MutableStateFlow<Pair<String, List<String>>?>(null)

    /** 最近一手的朗读版（这一手的原文 → 口语问题），同样只在内存里。 */
    val spoken = MutableStateFlow<Pair<String, String>?>(null)

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

        return when (val r = coach.opening(task, bp, context())) {
            is LlmResult.Err -> MoveResult.Failed(r.error)
            is LlmResult.Ok -> {
                // 请求期间断点可能被手动改过，以最新的为准，只填上这一手
                val latest = dao.getBreakpoint(task.id) ?: bp
                dao.upsertBreakpoint(
                    latest.copy(pendingCoachMove = r.value.coachMove, pendingFace = r.value.face, updatedAt = clock()),
                )
                starters.value = r.value.coachMove to r.value.starters
                spoken.value = r.value.coachMove to r.value.say
                MoveResult.Ready(r.value.coachMove)
            }
        }
    }

    /**
     * 提交一次回答。判定成功后，在同一个事务里保存 Round 并覆盖断点；
     * 新断点里带上陪练的下一手，作为下一轮的题目。
     * skipLengthCheck：陪练窗口的“我不知道”按钮用，原话就是“我不知道”。
     */
    suspend fun answer(
        task: Task,
        coachMove: String,
        answer: String,
        source: String,
        triggerPackage: String? = null,
        skipLengthCheck: Boolean = false,
    ): AnswerResult {
        if (!skipLengthCheck && !AnswerRules.isLongEnough(answer)) return AnswerResult.TooShort

        val bp = dao.getBreakpoint(task.id) ?: emptyBreakpoint(task.id)
        val judgement = when (val r = coach.judge(task, bp, coachMove, answer, context())) {
            is LlmResult.Err -> return AnswerResult.Failed(r.error)
            is LlmResult.Ok -> r.value
        }

        val now = clock()
        // 这一轮练的面：回答的正是断点里那一手时才算数
        val face = if (bp.pendingCoachMove == coachMove) bp.pendingFace else 0
        val update = judgement.breakpoint
        val newBp = bp.copy(
            // 模型漏给某一项时保留原值，不让断点被清空
            known = update?.known?.ifBlank { null } ?: bp.known,
            stuck = update?.stuck?.ifBlank { null } ?: bp.stuck,
            nextQuestion = update?.nextQuestion?.ifBlank { null } ?: bp.nextQuestion,
            pendingCoachMove = judgement.nextCoachMove,
            pendingFace = judgement.nextFace,
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
            face = face,
        )
        val id = dao.saveRound(round, newBp)
        starters.value = judgement.nextCoachMove to judgement.starters
        spoken.value = judgement.nextCoachMove to judgement.say
        return AnswerResult.Judged(round.copy(id = id), judgement)
    }

    /**
     * 看了一眼屏幕之后出一手：替换掉当前待回答的一手（断点其他内容不变）。
     * screen 是识图模型对屏幕的描述。
     */
    suspend fun observe(task: Task, screen: String): LlmResult<ObservedMove> {
        val bp = dao.getBreakpoint(task.id) ?: emptyBreakpoint(task.id)
        val r = coach.observe(task, bp, screen, context())
        if (r is LlmResult.Ok) {
            val latest = dao.getBreakpoint(task.id) ?: bp
            dao.upsertBreakpoint(latest.copy(pendingCoachMove = r.value.coachMove, pendingFace = r.value.face, updatedAt = clock()))
            starters.value = r.value.coachMove to r.value.starters
            spoken.value = r.value.coachMove to r.value.say
        }
        return r
    }

    /** 掷骰换一手：出一手专练掷出的那一面，替换当前待回答的一手。 */
    suspend fun rollFace(task: Task, face: Int): MoveResult = replaceMove(task, context().copy(forcedFace = face), face)

    /** 请陪练示范：先在别的小例子上示范这一面，再请用户在自己的题上做。 */
    suspend fun demo(task: Task): MoveResult {
        val bp = dao.getBreakpoint(task.id)
        return replaceMove(task, context().copy(demo = true, currentMove = bp?.pendingCoachMove), bp?.pendingFace)
    }

    private suspend fun replaceMove(task: Task, ctx: CoachContext, keepFace: Int?): MoveResult {
        val bp = dao.getBreakpoint(task.id) ?: emptyBreakpoint(task.id)
        return when (val r = coach.opening(task, bp, ctx)) {
            is LlmResult.Err -> MoveResult.Failed(r.error)
            is LlmResult.Ok -> {
                val latest = dao.getBreakpoint(task.id) ?: bp
                // 掷骰时以掷出的面为准；示范时模型没标面就沿用原来的
                val face = ctx.forcedFace ?: r.value.face.takeIf { it > 0 } ?: keepFace ?: 0
                dao.upsertBreakpoint(latest.copy(pendingCoachMove = r.value.coachMove, pendingFace = face, updatedAt = clock()))
                starters.value = r.value.coachMove to r.value.starters
                spoken.value = r.value.coachMove to r.value.say
                MoveResult.Ready(r.value.coachMove)
            }
        }
    }

    /** 出一手时附带的背景：最近 5 条规则、距离上一轮多久、上一轮原话。 */
    private suspend fun context(): CoachContext {
        val last = dao.lastRound()
        return CoachContext(
            rules = dao.recentRules(5),
            gapMinutes = last?.let { (clock() - it.createdAt) / 60_000 },
            lastAnswer = last?.userAnswer,
        )
    }

    /** 判定有效后发放一次放行。 */
    suspend fun grantPass(group: String, minutes: Int, roundId: Long?): Pass {
        val now = clock()
        val pass = Pass(packageGroup = group, startAt = now, endAt = now + minutes * 60_000L, roundId = roundId)
        return pass.copy(id = dao.insertPass(pass))
    }

    /** 今天（since 为当天零点）已用掉的离线放行次数。 */
    suspend fun offlineUsedSince(since: Long): Int = dao.countUnjudgedSince(since)

    /**
     * 离线“保存并通过”：回答原样保存，judged = false，发放一次放行。断点和陪练的一手都不变。
     * 次数限制由调用方先检查。
     */
    suspend fun saveOffline(
        task: Task,
        coachMove: String,
        answer: String,
        triggerPackage: String?,
        group: String,
        minutes: Int,
    ): Pass {
        val now = clock()
        val round = Round(
            taskId = task.id,
            source = RoundSource.GATE,
            triggerPackage = triggerPackage,
            coachMove = coachMove,
            userAnswer = answer,
            effective = false,
            moveType = Prompts.MOVE_NONE,
            reason = OFFLINE_REASON,
            feedback = "",
            judged = false,
            createdAt = now,
            face = dao.getBreakpoint(task.id)?.takeIf { it.pendingCoachMove == coachMove }?.pendingFace ?: 0,
        )
        val pass = Pass(packageGroup = group, startAt = now, endAt = now + minutes * 60_000L, roundId = null)
        return dao.saveOfflineRound(round, pass)
    }

    companion object {
        const val OFFLINE_REASON = "大模型暂时无法判定，离线保存"
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

    const val IMAGE_MARK = "【图片内容】"

    /** 文字回答和图片转写合成一条原话。图片转写也算字数。 */
    fun compose(text: String, imageText: String?): String {
        if (imageText.isNullOrBlank()) return text
        val t = text.trim()
        return if (t.isEmpty()) "$IMAGE_MARK$imageText" else "$t\n$IMAGE_MARK$imageText"
    }
}
