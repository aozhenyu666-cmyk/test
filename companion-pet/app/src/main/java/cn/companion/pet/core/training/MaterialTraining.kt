package cn.companion.pet.core.training

/**
 * 把绑定材料的题计划装配成训练题，并提供本地可判的核验。
 *
 * 诚实边界：
 * - 句子全部来自真实正文原句，不改写、不编造；
 * - 只有链接没有正文时 **不出题**（返回 null）；
 * - 本地判分只认“点对了那句 / 说出了范围词或因果词”，不做通用中文语义评分；
 * - 判不了就要求澄清，不冒充通过。
 */
object MaterialTraining {

    /** 装配训练题。材料变了（版本不同）就得到不同的题号。 */
    fun toQuestion(plan: MaterialQuestionPlan): TrainingQuestion = TrainingQuestion(
        id = plan.questionId,
        version = 1,
        stem = plan.question + "\n\n材料：" + plan.span.text,
        // 干扰项 + 正确句，都是原句。
        sentences = plan.allSpans.map { it.text },
        premisePolicy = PremisePolicy.CLOSED,
        requiredSlots = listOf(SlotKind.LOCATE, SlotKind.CONNECT),
        builtInSample = false,
        materialId = plan.materialId,
        materialVersion = plan.materialVersion,
        rubricPoints = plan.rubricPoints,
        nextStep = plan.nextStep,
    )

    /** 第一槽：指出正确的句子。 */
    fun evaluateLocate(
        plan: MaterialQuestionPlan,
        snapshot: TrainingSession,
        selected: List<Int>,
        transcript: String,
    ): EvaluationAdvice? {
        val attempt = snapshot.attempts.lastOrNull() ?: return null
        val cue = plan.span.cue
        val said = transcript.trim()
        // 允许两种证据：点选了正确句，或回答里提到那句的关键词。
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
            evaluationSource = "LOCAL_MATERIAL_V1",
        )
    }

    /** 第二槽：说出为什么这一句支撑不了它自己的结论。 */
    fun evaluateConnect(
        plan: MaterialQuestionPlan,
        snapshot: TrainingSession,
        transcript: String,
    ): EvaluationAdvice? {
        val attempt = snapshot.attempts.lastOrNull() ?: return null
        val said = transcript.trim()
        // 本地可判的最低要求：提到范围/样本/其他解释中的任一可核查点，且不是空泛表态。
        val markers = listOf("范围", "所有", "任何", "样本", "代表", "其他原因", "别的原因", "不一定", "只能说明")
        val hasMarker = markers.any { said.contains(it) }
        val tooVague = said.length < 6 || said in VAGUE
        val pass = hasMarker && !tooVague
        return EvaluationAdvice(
            sessionId = snapshot.id,
            revision = snapshot.revision,
            questionId = snapshot.questionId,
            slot = SlotKind.CONNECT,
            requestId = snapshot.pendingRequestId,
            verdict = if (pass) Verdict.PASS else Verdict.NEED_CLARIFICATION,
            evidenceRefs = if (pass) listOf(plan.span.text) else emptyList(),
            missingStep = if (pass) "" else "说清这一句凭什么不能支持那个结论：范围、样本，或别的原因。",
            nextPrompt = if (pass) "" else "",
            assistanceLevel = attempt.assistanceLevel,
            evaluationSource = "LOCAL_MATERIAL_V1",
        )
    }

    /** “懂了”“有道理”这类空泛表态，不能当通过。 */
    private val VAGUE = setOf(
        "懂了", "有道理", "知道了", "明白了", "好的", "嗯", "对", "是",
        "不清楚", "不知道",
    )
}