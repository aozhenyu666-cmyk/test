package cn.companion.pet.core.training

/**
 * 离线核验：只对 [OfflineBank] 里的固定题生效——固定材料、固定判据和可核验选项。
 * 只判定完全匹配的选项或列出的极简答案（忽略空格和标点）；其他表达要求澄清，不声称通用语义评分。
 * 题库与回归样例由 AI 编写，尚未经用户人工标注，不能冒充 R2.1 的黄金测试集。
 */
object OfflineTraining {
    /** 本地判分来源前缀：题组结算只认这些来源产生的通过。 */
    const val SOURCE_PREFIX = "LOCAL_"

    fun supported(q: TrainingQuestion): Boolean = OfflineBank.item(q) != null

    fun ask(q: TrainingQuestion, slot: SlotKind): String? = OfflineBank.item(q)?.ask(slot)

    fun options(q: TrainingQuestion, slot: SlotKind?): List<String>? = OfflineBank.item(q)?.options(slot)

    fun evaluate(q: TrainingQuestion, snapshot: TrainingSession): EvaluationAdvice? {
        val item = OfflineBank.item(q) ?: return null
        if (snapshot.state != TrainingState.EVALUATING) return null
        val slot = snapshot.currentSlotKind ?: return null
        if (slot == SlotKind.EXAMINE) return null
        val attempt = snapshot.attempts.lastOrNull() ?: return null
        val pass = item.accepts(slot, attempt.transcript)
        return EvaluationAdvice(
            sessionId = snapshot.id, revision = snapshot.revision, questionId = q.id,
            slot = slot, requestId = snapshot.pendingRequestId,
            verdict = if (pass) Verdict.PASS else Verdict.NEED_CLARIFICATION,
            evidenceRefs = if (pass) item.evidence(slot) else emptyList(),
            missingStep = if (pass) "" else item.hint,
            assistanceLevel = attempt.assistanceLevel,
            evaluationSource = if (q.id == "sample-scope-01") "LOCAL_SCOPE_V1" else "LOCAL_BANK_V" + item.rubricVersion,
        )
    }
}
