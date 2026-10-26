package cn.companion.pet.core.training

import kotlinx.serialization.Serializable

/** 双形态：同一个同桌，两套行为策略。与专注锁是两个独立维度。 */
@Serializable
enum class InteractionMode { WARM, COACH }

/** 题目环境：CLOSED 为题设推理，OPEN 为现实分析（允许质疑前提）。 */
@Serializable
enum class PremisePolicy { CLOSED, OPEN }

@Serializable
enum class TrainingState {
    READY, PRESENTING, WAITING_INPUT, EVALUATING, SCAFFOLDED,
    NEXT_SLOT, RECOVERY, INTERRUPTED, COMPLETED, ENDED,
}

@Serializable
enum class Verdict { PASS, RETRY, NEED_CLARIFICATION, QUESTION_ISSUE }

/** 完成程度：提示后选对不能写成独立掌握。 */
@Serializable
enum class AssistanceLevel { INDEPENDENT, PROMPTED, RECOGNIZED_WITH_CHOICES, DEMONSTRATED_ONLY }

/** 槽位：本次只要求一个可观察动作。 */
@Serializable
enum class SlotKind { LOCATE, CONNECT, EXAMINE }

@Serializable
data class TrainingQuestion(
    val id: String,
    val version: Int = 1,
    val stem: String,
    /** 题干可被点选的句子。 */
    val sentences: List<String> = emptyList(),
    val premisePolicy: PremisePolicy = PremisePolicy.CLOSED,
    /** 本题需要完成哪些槽位；首批默认前两槽。 */
    val requiredSlots: List<SlotKind> = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
    /** 是否内置示例（不是真实题库，必须在 UI 上标明）。 */
    val builtInSample: Boolean = true,
    /** 来自绑定材料时：句子就是真实正文的原句。 */
    val materialId: String = "",
    val materialVersion: Int = 0,
    /** 评分依据与下一步随题保存，恢复后仍可用。 */
    val rubricPoints: List<String> = emptyList(),
    val nextStep: String = "",
)

@Serializable
data class AnswerAttempt(
    val id: String,
    val slot: SlotKind,
    val modality: String,
    val transcript: String = "",
    val selectedSentenceIds: List<Int> = emptyList(),
    val verdict: Verdict? = null,
    val missingStep: String = "",
    val assistanceLevel: AssistanceLevel = AssistanceLevel.INDEPENDENT,
    val sourceRequestId: String = "",
    val evidenceRefs: List<String> = emptyList(),
    val evaluationSource: String = "",
)

@Serializable
data class TrainingSession(
    val id: String,
    val revision: Int = 0,
    val interactionMode: InteractionMode = InteractionMode.COACH,
    val questionId: String = "",
    val questionVersion: Int = 1,
    val premisePolicy: PremisePolicy = PremisePolicy.CLOSED,
    val requiredSlots: List<SlotKind> = emptyList(),
    val currentSlot: Int = 0,
    val state: TrainingState = TrainingState.READY,
    val attempts: List<AnswerAttempt> = emptyList(),
    val assistanceLevel: AssistanceLevel = AssistanceLevel.INDEPENDENT,
    val pendingRequestId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 有限提示预算随任务保存，恢复不能重新发一轮提示。 */
    val remindersUsed: Int = 0,
    /** 绑定的材料与版本；空表示这题不来自材料（例如内置示例）。 */
    val materialId: String = "",
    val materialVersion: Int = 0,
    /** 关联的机会 ID；结算按它对齐，不按列表顺序。 */
    val opportunityId: String = "",
    val recoveryReason: String = "",
    val nextAction: String = "",
    val resumeTrigger: String = "",
) {
    val active: Boolean get() = state !in TERMINAL
    val currentSlotKind: SlotKind? get() = requiredSlots.getOrNull(currentSlot)

    companion object {
        val TERMINAL = setOf(TrainingState.COMPLETED, TrainingState.ENDED)
    }
}

sealed interface TrainingOutcome {
    data class Present(val question: TrainingQuestion, val ask: String) : TrainingOutcome
    data class Waiting(val ask: String) : TrainingOutcome
    data class Scaffold(val text: String, val options: List<String>) : TrainingOutcome
    data class NextSlot(val ask: String) : TrainingOutcome
    data class Completed(val summary: String) : TrainingOutcome
    data class Recovery(val text: String) : TrainingOutcome
    data class Rejected(val reason: String) : TrainingOutcome
    data object None : TrainingOutcome
}

/** 模型建议：只描述判断，不含任何设备控制或放行命令。 */
@Serializable
data class EvaluationAdvice(
    val sessionId: String,
    val revision: Int,
    val questionId: String,
    val slot: SlotKind,
    val requestId: String,
    val verdict: Verdict,
    val evidenceRefs: List<String> = emptyList(),
    val missingStep: String = "",
    val nextPrompt: String = "",
    val assistanceLevel: AssistanceLevel = AssistanceLevel.INDEPENDENT,
    val uncertainty: String = "",
    val evaluationSource: String = "MODEL",
)
