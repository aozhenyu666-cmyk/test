package cn.companion.pet.core.training

/**
 * 明确限定的离线判分：只对**内置题**生效，判据写死在题上。
 *
 * 能力边界（必须保留）：
 * - 只判定“点中了哪一句 / 是否命中该题列出的关键依据词”；
 * - 不是通用中文语义评分，判不了“意思对不对”；
 * - 每道内置题都带 rubricPoints，离线判分只做**关键词命中 + 形式检查**。
 *
 * 但这足以让五题流程在**断网状态下**跑通：每题都有确定的本地判据。
 */
object OfflineTraining {

    private fun supported(q: TrainingQuestion) =
        q.builtInSample && BuiltInQuestions.SAMPLES.any { it.id == q.id }

    fun ask(q: TrainingQuestion, slot: SlotKind): String? {
        if (!supported(q)) return null
        return when (slot) {
            SlotKind.LOCATE -> "这句话把结论推到了哪一步？请指出材料里的那一句。"
            SlotKind.CONNECT -> "只补一件事：这一句凭什么还不能支持那个结论？"
            SlotKind.EXAMINE -> "再检验一下：举一个材料没覆盖的情况。"
        }
    }

    fun options(q: TrainingQuestion, slot: SlotKind?): List<String>? {
        if (!supported(q)) return null
        return when (slot) {
            SlotKind.LOCATE -> q.sentences.take(2)
            SlotKind.CONNECT -> listOf(
                "样本或范围不足以支持这个结论",
                "只要多数如此，就能推广到全部",
            )
            else -> null
        }
    }

    /**
     * 离线判分。返回 null 表示这题不在离线支持范围（调用方转联网）。
     *
     * 判定规则：
     * - LOCATE：点中正确句，或回答命中该题的某个证据词；
     * - CONNECT：命中该题 rubric 里的关键依据词，且不是空泛表态。
     */
    fun evaluate(q: TrainingQuestion, snapshot: TrainingSession): EvaluationAdvice? {
        if (!supported(q) || snapshot.state != TrainingState.EVALUATING) return null
        val slot = snapshot.currentSlotKind ?: return null
        val attempt = snapshot.attempts.lastOrNull() ?: return null
        val text = attempt.transcript.trim().trimEnd('。', '！', '.', '!')
        val pickedCorrect = attempt.selectedSentenceIds.any { idx ->
            q.sentences.getOrNull(idx) == q.sentences.lastOrNull()
        }
        val pass: Boolean
        val missing: String
        when (slot) {
            SlotKind.LOCATE -> {
                val cue = q.sentences.lastOrNull().orEmpty()
                // 只有三件事算“指出了那一句”：
                // 1) 真的点选了材料里的那一句；
                // 2) 回答几乎就是那一句（作为引用）；
                // 3) 明确引用了那句里的完整说法，而不是只提到里面某个词。
                // 只提到“任何人”这类词不等于指出了句子——那可能是在否定结论。
                val hitCue = cue.isNotBlank() && text.length >= 8 && cue.contains(text)
                val quoted = cue.isNotBlank() && text.length >= 8 &&
                    cue.replace(" ", "").contains(text.replace(" ", ""))
                pass = pickedCorrect || hitCue || quoted
                missing = "请指出材料里那句把结论推得太满的话，最好直接把那句说出来。"
            }
            SlotKind.CONNECT -> {
                val hasReason = CONNECT_KEYWORDS.any { text.contains(it) }
                val vague = text.isEmpty() || text in VAGUE || text.length < 6
                pass = hasReason && !vague
                missing = "说清这一句凭什么不能支持结论：样本、范围，或别的原因。"
            }
            else -> return null
        }
        return EvaluationAdvice(
            sessionId = snapshot.id,
            revision = snapshot.revision,
            questionId = q.id,
            slot = slot,
            requestId = snapshot.pendingRequestId,
            verdict = if (pass) Verdict.PASS else Verdict.NEED_CLARIFICATION,
            evidenceRefs = if (pass) q.sentences.takeLast(1) else emptyList(),
            missingStep = if (pass) "" else missing,
            nextPrompt = if (pass) q.nextStep else "",
            assistanceLevel = attempt.assistanceLevel,
            // 离线判分只验形式与关键词，语义未经确认，必须留痕。
            uncertainty = if (pass) "离线判分只核对了关键词与形式，语义未经模型确认" else "",
            evaluationSource = "OFFLINE_KEYWORD_V1",
        )
    }

    private val CONNECT_KEYWORDS = listOf(
        "样本", "人数", "个例", "代表", "范围", "推广", "外推", "因果", "先后",
        "别的解释", "其他原因", "不一定", "以偏概全", "太小", "不全", "覆盖",
    )
    private val VAGUE = setOf(
        "懂了", "有道理", "知道了", "明白了", "好的", "嗯", "对", "是", "不知道",
    )
}
