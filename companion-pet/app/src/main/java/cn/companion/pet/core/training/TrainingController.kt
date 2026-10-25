package cn.companion.pet.core.training

/**
 * 训练状态机：纯 Kotlin，所有时间由调用方给。
 *
 * 关键不变量：
 * - 一轮只推进一个槽位；
 * - 旧请求（sessionId/revision/questionId/slot/requestId 不符）一律丢弃；
 * - 提示后完成记 PROMPTED，不写成独立掌握；
 * - 网络/模型失败不改判为用户失败。
 */
class TrainingController(private var session: TrainingSession) {

    val current: TrainingSession get() = session
    private var question: TrainingQuestion? = null

    fun bind(question: TrainingQuestion) {
        this.question = question
        session = session.copy(
            questionId = question.id,
            questionVersion = question.version,
            premisePolicy = question.premisePolicy,
            requiredSlots = question.requiredSlots,
            currentSlot = 0,
        )
    }

    private fun askFor(slot: SlotKind): String = when (slot) {
        SlotKind.LOCATE -> "请点出结论里的范围词，或直接说出哪一句是关键。"
        SlotKind.CONNECT -> "只补一件事：这句话为什么还不足以支持那个结论？"
        SlotKind.EXAMINE -> "再检验一下：举一种没被这次样本覆盖的人。"
    }

    fun present(now: Long): TrainingOutcome {
        val q = question ?: return TrainingOutcome.None
        session = session.copy(state = TrainingState.PRESENTING, updatedAt = now)
        val slot = session.currentSlotKind ?: SlotKind.LOCATE
        session = session.copy(state = TrainingState.WAITING_INPUT, revision = session.revision + 1, updatedAt = now)
        return TrainingOutcome.Present(q, askFor(slot))
    }

    /** 提交一次回答：只进入 EVALUATING，是否通过必须等结构化建议 + 本地校验。 */
    fun submit(
        transcript: String = "",
        selectedSentenceIds: List<Int> = emptyList(),
        modality: String = "text",
        assistance: AssistanceLevel = AssistanceLevel.INDEPENDENT,
        now: Long,
    ): TrainingOutcome {
        if (!session.active) return TrainingOutcome.Rejected("会话已结束")
        if (session.state == TrainingState.EVALUATING) return TrainingOutcome.Rejected("还在核对上一句，等我一下。")
        val slot = session.currentSlotKind ?: return TrainingOutcome.Rejected("没有待完成的槽位")
        if (transcript.isBlank() && selectedSentenceIds.isEmpty()) return TrainingOutcome.None
        val requestId = "req-" + session.revision + "-" + now
        val attempt = AnswerAttempt(
            id = "a-" + session.revision,
            slot = slot,
            modality = modality,
            transcript = transcript.take(1000),
            selectedSentenceIds = selectedSentenceIds.take(4),
            assistanceLevel = assistance,
            sourceRequestId = requestId,
        )
        session = session.copy(
            revision = session.revision + 1,
            state = TrainingState.EVALUATING,
            attempts = session.attempts + attempt,
            assistanceLevel = assistance,
            pendingRequestId = requestId,
            updatedAt = now,
        )
        return TrainingOutcome.None
    }

    /** 模型用不了/超时/解析失败：回到等待，不冒充通过，也不判用户失败（否则会卡在核验中）。 */
    fun evaluationFailed(now: Long): TrainingOutcome {
        if (!session.active || session.state != TrainingState.EVALUATING) return TrainingOutcome.None
        session = session.copy(revision = session.revision + 1, state = TrainingState.WAITING_INPUT, pendingRequestId = "", updatedAt = now)
        return TrainingOutcome.Waiting(askFor(session.currentSlotKind ?: SlotKind.LOCATE))
    }

    /** 当前槽位的问法（恢复/重显时用）。 */
    fun currentAsk(): String = askFor(session.currentSlotKind ?: SlotKind.LOCATE)

    /** 采纳模型建议：schema、会话、题号、槽位、请求号任一不符都要丢弃。 */
    fun acceptAdvice(advice: EvaluationAdvice, now: Long): TrainingOutcome {
        if (!session.active) return TrainingOutcome.Rejected("会话已结束")
        if (advice.sessionId != session.id) return TrainingOutcome.Rejected("会话不匹配")
        if (advice.questionId != session.questionId) return TrainingOutcome.Rejected("题号不匹配")
        if (advice.requestId != session.pendingRequestId) return TrainingOutcome.Rejected("请求号过期")
        if (advice.revision != session.revision) return TrainingOutcome.Rejected("版本过期")
        if (advice.slot != session.currentSlotKind) return TrainingOutcome.Rejected("槽位不匹配")
        if (session.state != TrainingState.EVALUATING) return TrainingOutcome.Rejected("当前不在核验阶段")

        val attempts = session.attempts.toMutableList()
        val idx = attempts.indexOfLast { it.slot == advice.slot }
        if (idx >= 0) {
            attempts[idx] = attempts[idx].copy(
                verdict = advice.verdict,
                missingStep = advice.missingStep.take(160),
                assistanceLevel = advice.assistanceLevel,
            )
        }

        return when (advice.verdict) {
            Verdict.PASS -> {
                val next = session.currentSlot + 1
                if (next >= session.requiredSlots.size) {
                    session = session.copy(
                        revision = session.revision + 1,
                        state = TrainingState.COMPLETED,
                        attempts = attempts,
                        pendingRequestId = "",
                        updatedAt = now,
                    )
                    TrainingOutcome.Completed(summary())
                } else {
                    session = session.copy(
                        revision = session.revision + 1,
                        state = TrainingState.NEXT_SLOT,
                        currentSlot = next,
                        attempts = attempts,
                        pendingRequestId = "",
                        updatedAt = now,
                    )
                    val slot = session.requiredSlots[next]
                    session = session.copy(state = TrainingState.WAITING_INPUT)
                    TrainingOutcome.NextSlot(askFor(slot))
                }
            }
            Verdict.RETRY, Verdict.NEED_CLARIFICATION -> {
                session = session.copy(
                    revision = session.revision + 1,
                    state = TrainingState.WAITING_INPUT,
                    attempts = attempts,
                    pendingRequestId = "",
                    updatedAt = now,
                )
                TrainingOutcome.Waiting(
                    if (advice.nextPrompt.isNotBlank()) advice.nextPrompt
                    else if (advice.missingStep.isNotBlank()) "只差这一点：" + advice.missingStep
                    else "再说具体一点。",
                )
            }
            Verdict.QUESTION_ISSUE -> {
                session = session.copy(
                    revision = session.revision + 1,
                    state = TrainingState.RECOVERY,
                    attempts = attempts,
                    pendingRequestId = "",
                    updatedAt = now,
                )
                TrainingOutcome.Recovery("你说题目有问题，我先停在这里。可以换一题，或者说明哪里不对。")
            }
        }
    }

    /** 超时支架：缩小问题，不动状态机语义。 */
    fun scaffold(now: Long): TrainingOutcome {
        if (!session.active) return TrainingOutcome.None
        if (session.state != TrainingState.WAITING_INPUT) return TrainingOutcome.None
        val q = question ?: return TrainingOutcome.None
        session = session.copy(state = TrainingState.SCAFFOLDED, updatedAt = now)
        val slot = session.currentSlotKind ?: SlotKind.LOCATE
        val options = q.scaffolds[slot]?.filter { it.isNotBlank() }?.take(3)?.takeIf { it.size >= 2 } ?: when (slot) {
            SlotKind.LOCATE -> q.sentences.take(2).takeIf { it.size >= 2 } ?: listOf("结论那一句", "给出依据的那一句")
            SlotKind.CONNECT -> listOf("依据覆盖的范围比结论小", "依据和结论之间缺了一步原因")
            SlotKind.EXAMINE -> listOf("找一个题设没覆盖到的人或情况", "找一个符合条件但结论不成立的情况")
        }
        return TrainingOutcome.Scaffold("先缩小一点：下面两条里，哪一条和当前推理最相关？", options)
    }

    /** 恢复：暂停计时与连续追问。专注会话按自己的截止时间走，不因这里延长。 */
    fun recover(now: Long): TrainingOutcome {
        if (!session.active) return TrainingOutcome.None
        session = session.copy(state = TrainingState.RECOVERY, updatedAt = now)
        return TrainingOutcome.Recovery("先歇一会儿。现场我留着了，回来从这一步继续。")
    }

    fun interrupt(now: Long): TrainingOutcome {
        if (!session.active) return TrainingOutcome.None
        session = session.copy(state = TrainingState.INTERRUPTED, updatedAt = now)
        return TrainingOutcome.None
    }

    fun resume(now: Long): TrainingOutcome {
        // 恢复态（沉默太久/题目有问题）和中断态都能接着来；原实现只认 INTERRUPTED，“继续”按钮点了没反应。
        if (session.state != TrainingState.INTERRUPTED && session.state != TrainingState.RECOVERY) return TrainingOutcome.None
        session = session.copy(state = TrainingState.WAITING_INPUT, updatedAt = now)
        val slot = session.currentSlotKind ?: SlotKind.LOCATE
        return TrainingOutcome.Waiting(askFor(slot))
    }

    /** 形态切换不清任务、不清专注；只改 interactionMode。 */
    fun switchMode(mode: InteractionMode, now: Long): TrainingOutcome {
        if (mode == session.interactionMode) return TrainingOutcome.None
        session = session.copy(interactionMode = mode, revision = session.revision + 1, updatedAt = now)
        return TrainingOutcome.None
    }

    fun end(now: Long): TrainingOutcome {
        if (!session.active) return TrainingOutcome.None
        session = session.copy(state = TrainingState.ENDED, updatedAt = now)
        return TrainingOutcome.Completed(summary())
    }

    fun summary(): String {
        val done = session.attempts.count { it.verdict == Verdict.PASS }
        val independent = session.attempts.count { it.verdict == Verdict.PASS && it.assistanceLevel == AssistanceLevel.INDEPENDENT }
        val prompted = done - independent
        return "完成 " + done + " 步；其中独立通过 " + independent + " 步，提示后通过 " + prompted + " 步。"
    }
}
