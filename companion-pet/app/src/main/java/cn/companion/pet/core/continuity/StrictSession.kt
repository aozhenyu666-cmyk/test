package cn.companion.pet.core.continuity

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 进度与健康分开：到期/中止不等于认知达标。 */
@Serializable
enum class Phase { READY, PROMPTING, WAITING, EVALUATING, SUPPORT, COMPLETED, EXPIRED, ABORTED }

@Serializable
enum class Health { OK, DEGRADED, UNAVAILABLE }

@Serializable
enum class Verdict { PASS, NEED_EVIDENCE, NEED_SUPPORT, INPUT_UNCERTAIN, QUESTION_ISSUE }

@Serializable
enum class SilenceReason { NO_RESPONSE, TECHNICAL_BLOCK, NEED_SUPPORT, THINKING }

@Serializable
enum class StrictEndReason { COMPLETED, EXPIRED, ABORTED, NEVER_ENGAGED }

/** 模型建议：只描述判断，不含任何设备控制或解锁命令。 */
@Serializable
data class Evaluation(
    val sessionId: String,
    val stepId: String,
    val attemptId: String,
    val requestRevision: Int,
    val verdict: Verdict,
    val criteriaMet: List<String> = emptyList(),
    val criteriaMissing: List<String> = emptyList(),
    val evidenceQuote: String = "",
    val feedback: String = "",
    val nextPrompt: String = "",
    /** 是否用了提示/支架才过（按步计，不按整回合）。 */
    val usedHelpThisStep: Boolean = false,
)

/**
 * 严格回合状态。
 *
 * 关键修正：
 * - [endsAt] 是固定截止，`remainingMillis` 必须用传入的 now 计算，
 *   不能用 `endsAt - updatedAt`（updatedAt 每次交互都会变，会把剩余时间算成满值）。
 * - [steps] 是多步接续：PASS 且还有下一步时进入下一步，不是直接 COMPLETED（CL-S3）。
 * - [stepIndex] 记录第几步，供 UI 显示与恢复。
 */
@Serializable
data class StrictSessionState(
    val sessionId: String,
    val revision: Int = 0,
    val stepId: String = "step-1",
    val stepIndex: Int = 1,
    val startedAt: Long,
    val endsAt: Long,
    val phase: Phase = Phase.READY,
    val health: Health = Health.OK,
    val topic: String = "",
    val materialRef: String = "",
    val question: String = "",
    val criteria: List<String> = emptyList(),
    /** 后续步骤的问题；走到最后一步就没有了（CL-S3）。 */
    val upcomingSteps: List<String> = emptyList(),
    val lastAnswer: String = "",
    val gap: String = "",
    val nextAction: String = "",
    val silence: SilenceReason? = null,
    val reminders: Int = 0,
    val attempts: Int = 0,
    /** 当前这一步用了几次尝试（用于“提示后通过”按步统计）（CL-S4）。 */
    val attemptsThisStep: Int = 0,
    /** 当前这一步是否用过“我不会/支架”。 */
    val usedHelpThisStep: Boolean = false,
    val passedAfterHelp: Int = 0,
    val passedIndependently: Int = 0,
    val endReason: String = "",
    val updatedAt: Long = 0,
) {
    val active: Boolean get() = phase !in TERMINAL
    val hasNextStep: Boolean get() = upcomingSteps.isNotEmpty()

    /** 剩余时间：必须用调用方的 now，不能用 updatedAt。 */
    fun remainingMillis(now: Long): Long = (endsAt - now).coerceAtLeast(0)

    companion object {
        val TERMINAL = setOf(Phase.COMPLETED, Phase.EXPIRED, Phase.ABORTED)
        fun new(
            now: Long,
            minutes: Int,
            topic: String,
            materialRef: String,
            criteria: List<String>,
            upcomingSteps: List<String> = emptyList(),
        ): StrictSessionState =
            StrictSessionState(
                sessionId = UUID.randomUUID().toString(),
                startedAt = now,
                endsAt = now + minutes.coerceIn(1, 180) * 60_000L,
                topic = topic.take(120),
                materialRef = materialRef.take(200),
                criteria = criteria.take(4),
                upcomingSteps = upcomingSteps.take(3),
                updatedAt = now,
            )
    }
}

sealed interface StrictOutcome {
    data class Ask(val text: String) : StrictOutcome
    data class Accepted(val verdict: Verdict, val text: String, val nextPrompt: String) : StrictOutcome
    /** PASS 且还有下一步：进入下一步，回合继续（CL-S3）。 */
    data class NextStep(val stepIndex: Int, val question: String) : StrictOutcome
    data class Rejected(val reason: String) : StrictOutcome
    data class Support(val text: String) : StrictOutcome
    data class Finished(val reason: StrictEndReason, val summary: String) : StrictOutcome
    data object None : StrictOutcome
}

/**
 * 严格会话状态机。所有时间由调用方给，纯逻辑、可测。
 * 关键不变量：
 * - 迟到结果（sessionId/stepId/revision 不匹配）一律丢弃；
 * - 到期不等于达标；
 * - 紧急中止不需要网络；
 * - PASS 后若有下一步就继续，不直接结束（CL-S3）；
 * - “提示后通过”按步统计，不按整回合（CL-S4）。
 */
class StrictSessionMachine(var state: StrictSessionState) {

    fun ask(question: String, criteria: List<String>, now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        state = state.copy(
            revision = state.revision + 1,
            phase = Phase.WAITING,
            question = question.take(200),
            criteria = if (criteria.isEmpty()) state.criteria else criteria.take(4),
            silence = null,
            attemptsThisStep = 0,
            usedHelpThisStep = false,
            updatedAt = now,
        )
        return StrictOutcome.Ask(state.question)
    }

    /** 收到回答：只推进到 EVALUATING，是否通过必须等模型建议 + 本地校验。 */
    fun submit(answer: String, now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        val text = answer.trim()
        if (text.isEmpty()) return StrictOutcome.None
        state = state.copy(
            revision = state.revision + 1,
            phase = Phase.EVALUATING,
            lastAnswer = text.take(1000),
            silence = null,
            attempts = state.attempts + 1,
            attemptsThisStep = state.attemptsThisStep + 1,
            updatedAt = now,
        )
        return StrictOutcome.None
    }

    /** “我不会”：按本步缺口给支架，并记本步用过帮助（CL-S4）。 */
    fun needsSupport(now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        val gap = state.gap.ifBlank { state.criteria.firstOrNull().orEmpty() }
        val text = if (gap.isBlank()) {
            "这一步拆小一点：先只说出你的判断，理由我先不问。"
        } else {
            "这一步拆小一点：先只处理这一点——" + gap
        }
        state = state.copy(
            revision = state.revision + 1,
            phase = Phase.SUPPORT,
            silence = SilenceReason.NEED_SUPPORT,
            usedHelpThisStep = true,
            updatedAt = now,
        )
        return StrictOutcome.Support(text)
    }

    /** 本地校验后才采纳；schema/会话/步骤/版本不符一律丢弃。 */
    fun acceptEvaluation(evaluation: Evaluation, now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.Rejected("会话已结束")
        if (evaluation.sessionId != state.sessionId) return StrictOutcome.Rejected("会话不匹配")
        if (evaluation.stepId != state.stepId) return StrictOutcome.Rejected("步骤不匹配")
        if (evaluation.requestRevision != state.revision) return StrictOutcome.Rejected("版本过期")
        if (state.phase != Phase.EVALUATING) return StrictOutcome.Rejected("当前不在核验阶段")
        return when (evaluation.verdict) {
            Verdict.PASS -> {
                // 按步统计：本步多次尝试或用过支架才算“提示后通过”（CL-S4）。
                val afterHelp = state.attemptsThisStep > 1 || state.usedHelpThisStep || evaluation.usedHelpThisStep
                val indep = if (afterHelp) state.passedIndependently else state.passedIndependently + 1
                val helped = if (afterHelp) state.passedAfterHelp + 1 else state.passedAfterHelp
                // 还有下一步：进入下一步，回合继续（CL-S3）。
                if (state.hasNextStep) {
                    val nextQuestion = state.upcomingSteps.first()
                    val nextIndex = state.stepIndex + 1
                    state = state.copy(
                        revision = state.revision + 1,
                        phase = Phase.WAITING,
                        stepId = "step-" + nextIndex,
                        stepIndex = nextIndex,
                        upcomingSteps = state.upcomingSteps.drop(1),
                        question = nextQuestion.take(200),
                        lastAnswer = "",
                        gap = "",
                        nextAction = "",
                        attemptsThisStep = 0,
                        usedHelpThisStep = false,
                        passedIndependently = indep,
                        passedAfterHelp = helped,
                        updatedAt = now,
                    )
                    return StrictOutcome.NextStep(nextIndex, state.question)
                }
                state = state.copy(
                    revision = state.revision + 1,
                    phase = Phase.COMPLETED,
                    gap = "",
                    nextAction = evaluation.nextPrompt.take(160),
                    passedAfterHelp = helped,
                    passedIndependently = indep,
                    endReason = StrictEndReason.COMPLETED.name,
                    updatedAt = now,
                )
                StrictOutcome.Accepted(Verdict.PASS, evaluation.feedback.ifBlank { "这一步成了。" }, state.nextAction)
            }
            Verdict.NEED_SUPPORT -> {
                state = state.copy(
                    revision = state.revision + 1,
                    phase = Phase.SUPPORT,
                    silence = SilenceReason.NEED_SUPPORT,
                    gap = evaluation.criteriaMissing.joinToString("；").take(160),
                    usedHelpThisStep = true,
                    updatedAt = now,
                )
                StrictOutcome.Support(evaluation.feedback.ifBlank { "先把这一步再拆小一点。" })
            }
            Verdict.NEED_EVIDENCE -> {
                state = state.copy(
                    revision = state.revision + 1,
                    phase = Phase.WAITING,
                    gap = evaluation.criteriaMissing.joinToString("；").take(160),
                    nextAction = evaluation.nextPrompt.take(160),
                    updatedAt = now,
                )
                StrictOutcome.Accepted(Verdict.NEED_EVIDENCE, evaluation.feedback.ifBlank { "还差一句原文依据。" }, evaluation.nextPrompt)
            }
            Verdict.INPUT_UNCERTAIN -> {
                state = state.copy(
                    revision = state.revision + 1,
                    phase = Phase.WAITING,
                    silence = SilenceReason.TECHNICAL_BLOCK,
                    updatedAt = now,
                )
                StrictOutcome.Accepted(Verdict.INPUT_UNCERTAIN, "我没听清，能再说一遍吗？", "")
            }
            Verdict.QUESTION_ISSUE -> {
                // 用户指出题目有问题：停下等澄清，不判通过也不判失败（A31）。
                state = state.copy(
                    revision = state.revision + 1,
                    phase = Phase.SUPPORT,
                    silence = SilenceReason.NEED_SUPPORT,
                    gap = "题目前提待澄清",
                    updatedAt = now,
                )
                StrictOutcome.Support(evaluation.feedback.ifBlank { "你说题有问题，我先停在这里。要不要换一个说法？" })
            }
        }
    }

    fun markHealth(health: Health, now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        state = state.copy(health = health, updatedAt = now)
        return StrictOutcome.None
    }

    /**
     * 技术故障：保留答案与步骤，退回等待，不判用户失败（CL-S5 等价）。
     * 与其他“失败”分开：这里不改 attemptsThisStep，也不算帮助。
     */
    fun evaluationUnavailable(reason: String, now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        state = state.copy(
            revision = state.revision + 1,
            phase = Phase.WAITING,
            silence = SilenceReason.TECHNICAL_BLOCK,
            health = Health.DEGRADED,
            gap = reason.take(120),
            updatedAt = now,
        )
        return StrictOutcome.Ask("核对暂时不可用（" + reason + "）。你说的我留着，可以再答一次。")
    }

    /** 沉默分类：技术故障不归咎用户，思考给有限宽限。 */
    fun onSilence(reason: SilenceReason, now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        if (state.phase != Phase.WAITING && state.phase != Phase.PROMPTING) return StrictOutcome.None
        state = state.copy(silence = reason, updatedAt = now)
        return StrictOutcome.None
    }

    fun remind(now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        if (state.reminders >= MAX_REMINDERS) return StrictOutcome.None
        state = state.copy(reminders = state.reminders + 1, updatedAt = now)
        return when (state.reminders) {
            1 -> StrictOutcome.Ask("还在这儿吗？就等你一句。")
            else -> StrictOutcome.Ask(if (state.gap.isNotBlank()) "只差这一点：" + state.gap else "要不要把问题再拆小一点？")
        }
    }

    fun tick(now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        // 注意：不要在这里无条件改 updatedAt 当“当前时间”；到期只用 now 判。
        if (now < state.endsAt) {
            if (state.updatedAt != now) state = state.copy(updatedAt = now)
            return StrictOutcome.None
        }
        val engaged = state.attempts > 0 || state.passedIndependently + state.passedAfterHelp > 0
        val reason = if (engaged) StrictEndReason.EXPIRED else StrictEndReason.NEVER_ENGAGED
        state = state.copy(phase = Phase.EXPIRED, endReason = reason.name)
        return StrictOutcome.Finished(reason, summary())
    }

    /** 紧急结束：不需要网络，迟到结果作废。 */
    fun abort(now: Long): StrictOutcome {
        if (!state.active) return StrictOutcome.None
        state = state.copy(phase = Phase.ABORTED, endReason = StrictEndReason.ABORTED.name, updatedAt = now)
        return StrictOutcome.Finished(StrictEndReason.ABORTED, summary())
    }

    fun summary(): String {
        val mins = ((state.updatedAt - state.startedAt) / 60_000).coerceAtLeast(0)
        return buildString {
            append("用了 ").append(mins).append(" 分钟；走到第 ").append(state.stepIndex).append(" 步")
            append("；独立通过 ").append(state.passedIndependently)
            append(" 步，提示后通过 ").append(state.passedAfterHelp).append(" 步")
            if (state.gap.isNotBlank()) append("；卡在：").append(state.gap)
            if (state.nextAction.isNotBlank()) append("；下次从这里继续：").append(state.nextAction)
        }
    }

    companion object {
        const val MAX_REMINDERS = 2
        const val REMIND_AT_MILLIS = 30_000L
        const val SHRINK_AT_MILLIS = 60_000L
        const val GIVE_UP_AT_MILLIS = 120_000L
    }
}
