package cn.companion.pet.core.training

/**
 * 把绑定材料的题装配成训练题，并提供本地可判的核验。
 *
 * **重要能力边界（R2.1 §5.2 / §7）**：
 * 本地规则只能验证**结构与形式**，不能判断语义是否正确。
 * 具体来说这里能可靠判定的只有：
 * - 第一槽：是否点中了正文里的那句证据（这是可枚举的，可靠）；
 * - 第二槽：回答是否属于「空泛表态」「复述题目」「明显残缺」这类可枚举的形式缺陷。
 *
 * 因此第二槽的本地结论只有两种：
 * - 明显不合格（空泛/复述/残缺/过短）→ NEED_CLARIFICATION；
 * - 其余一律 **PASS_FORM_ONLY**，并在 EvaluationAdvice 里标 `evaluationSource = "LOCAL_FORM_ONLY"`，
 *   同时给出 uncertainty，表示“形式合规，语义未经核验”。
 *
 * 严禁把形式合规当成“思考正确”。这也是为什么资格发放必须走迁移题与其他证据，
 * 而不能只靠这一层的 PASS。
 */
object MaterialTraining {

    /** 装配训练题。材料变了（版本不同）就得到不同的题号。 */
    fun toQuestion(plan: MaterialQuestionPlan): TrainingQuestion = TrainingQuestion(
        id = plan.questionId,
        version = 1,
        stem = plan.question + "\n\n材料：" + plan.span.text,
        sentences = plan.allSpans.map { it.text },
        premisePolicy = PremisePolicy.CLOSED,
        requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
        builtInSample = false,
        materialId = plan.materialId,
        materialVersion = plan.materialVersion,
        rubricPoints = plan.rubricPoints,
        nextStep = plan.nextStep,
    )

    /** 第一槽：指出正确的句子。点选是可枚举的，因此这一槽的本地判定可靠。 */
    fun evaluateLocate(
        plan: MaterialQuestionPlan,
        snapshot: TrainingSession,
        selected: List<Int>,
        transcript: String,
    ): EvaluationAdvice? {
        val attempt = snapshot.attempts.lastOrNull() ?: return null
        val cue = plan.span.cue
        val said = transcript.trim()
        val pickedRight = selected.any { idx -> plan.allSpans.getOrNull(idx)?.index == plan.span.index }
        val mentionedCue = said.contains(cue)
        val mentionedText = said.length >= 6 && plan.span.text.contains(said)
        val pass = pickedRight || mentionedCue || mentionedText
        return EvaluationAdvice(
            sessionId = snapshot.id,
            revision = snapshot.revision,
            questionId = snapshot.questionId,
            slot = SlotKind.LOCATE,
            requestId = snapshot.pendingRequestId,
            verdict = if (pass) Verdict.PASS else Verdict.NEED_CLARIFICATION,
            evidenceRefs = if (pass) listOf(plan.span.text) else emptyList(),
            missingStep = if (pass) "" else "请指出材料里带「" + cue + "」的那一句。",
            nextPrompt = if (pass) plan.nextStep else "",
            assistanceLevel = attempt.assistanceLevel,
            evaluationSource = "LOCAL_SPAN_MATCH",
        )
    }

    /**
     * 第二槽：只判形式缺陷，不判语义对错。
     *
     * 通过时 evaluationSource 标 LOCAL_FORM_ONLY，并在 uncertainty 说明语义未经核验。
     */
    fun evaluateConnect(
        plan: MaterialQuestionPlan,
        snapshot: TrainingSession,
        transcript: String,
    ): EvaluationAdvice? {
        val attempt = snapshot.attempts.lastOrNull() ?: return null
        val said = transcript.trim()
        val defect = formDefect(said)
        val ok = defect == null
        return EvaluationAdvice(
            sessionId = snapshot.id,
            revision = snapshot.revision,
            questionId = snapshot.questionId,
            slot = SlotKind.CONNECT,
            requestId = snapshot.pendingRequestId,
            verdict = if (ok) Verdict.PASS else Verdict.NEED_CLARIFICATION,
            evidenceRefs = if (ok) listOf(plan.span.text) else emptyList(),
            missingStep = if (ok) "" else defect!!,
            nextPrompt = "",
            assistanceLevel = attempt.assistanceLevel,
            // 语义是否真的正确，本地判不了，必须留痕。
            uncertainty = if (ok) "只核对了形式（有具体对象、不是复述），语义正确性未经模型或人工确认" else "",
            evaluationSource = if (ok) "LOCAL_FORM_ONLY" else "LOCAL_FORM_REJECT",
        )
    }

    /** 返回形式缺陷描述；null 表示形式合规（不代表语义正确）。 */
    private fun formDefect(said: String): String? {
        if (said.length < 6) return "说清这一句凭什么不能支持那个结论：范围、样本，或别的原因。"
        if (said in VAGUE) return "这句只是表态，没有给出理由。请说清这一句凭什么不能支持结论。"
        // 复述题目：只重复“范围词/这一句/哪一句”，没有给出任何理由成分。
        val restating = (said.contains("范围词") || said.contains("哪一句")) &&
            !said.contains("不能") && !said.contains("不足") && !said.contains("不一定") &&
            !said.contains("代表") && !said.contains("太小") && !said.contains("不全")
        if (restating) return "这是在复述题目。请说清这一句凭什么不能支持结论。"
        // 明显残缺：以连接词或数量词结尾，说明转写被截断。
        if (said.endsWith("因为") || said.endsWith("只有") || said.endsWith("不能代") || said.endsWith("样本不")) {
            return "这句像是没说完，能补完吗？"
        }
        return null
    }

    private val VAGUE = setOf(
        "懂了", "有道理", "知道了", "明白了", "好的", "嗯", "对", "是",
        "不清楚", "不知道",
    )
}
