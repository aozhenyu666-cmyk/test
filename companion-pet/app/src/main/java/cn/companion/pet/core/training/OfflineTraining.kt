package cn.companion.pet.core.training

/**
 * 一个明确限定的离线示例：固定材料、固定判据和可核验选项。
 * 只判定完全匹配的选项或极简答案；其他表达要求澄清，不声称通用语义评分。
 * AI 编写的回归样例尚未经用户人工标注，不能冒充 R2.1 的黄金测试集。
 */
object OfflineTraining {
    private fun supported(q: TrainingQuestion) = q == BuiltInQuestions.SAMPLES.first()

    fun ask(q: TrainingQuestion, slot: SlotKind): String? = if (!supported(q)) null else when (slot) {
        SlotKind.LOCATE -> "结论把效果推广到了谁？请指出原文里的范围词。"
        SlotKind.CONNECT -> "只调查了自愿参加的人，能保证任何人都会提高吗？选出依据，或说出缺口。"
        SlotKind.EXAMINE -> null
    }

    fun options(q: TrainingQuestion, slot: SlotKind?): List<String>? = if (!supported(q)) null else when (slot) {
        SlotKind.LOCATE -> listOf("任何人", "16 人")
        SlotKind.CONNECT -> listOf("自愿参加的样本不能代表任何人", "只要多数人提高，就能保证任何人提高")
        else -> null
    }

    fun evaluate(q: TrainingQuestion, snapshot: TrainingSession): EvaluationAdvice? {
        if (!supported(q) || snapshot.state != TrainingState.EVALUATING) return null
        val slot = snapshot.currentSlotKind ?: return null
        val attempt = snapshot.attempts.lastOrNull() ?: return null
        val answers = when (slot) {
            SlotKind.LOCATE -> setOf("任何人", "所有人", "范围词是任何人", "结论是任何人都会提高")
            SlotKind.CONNECT -> setOf("自愿参加的样本不能代表任何人", "样本不能代表所有人", "自愿参加者不代表所有人")
            else -> return null
        }
        val text = attempt.transcript.trim().trimEnd('。', '！', '.', '!')
        val pass = text in answers
        return EvaluationAdvice(
            sessionId = snapshot.id, revision = snapshot.revision, questionId = q.id,
            slot = slot, requestId = snapshot.pendingRequestId,
            verdict = if (pass) Verdict.PASS else Verdict.NEED_CLARIFICATION,
            evidenceRefs = if (!pass) emptyList() else when (slot) {
                SlotKind.LOCATE -> listOf("任何人")
                else -> listOf("20 名自愿参加训练的人", "任何人使用该训练都会提高")
            },
            missingStep = if (pass) "" else "请指出材料中的范围或样本缺口；离线核验也可以直接选一个选项。",
            assistanceLevel = attempt.assistanceLevel,
            evaluationSource = "LOCAL_SCOPE_V1",
        )
    }
}
