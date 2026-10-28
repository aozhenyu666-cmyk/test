package cn.companion.pet.core.partner

import kotlinx.serialization.Serializable

/**
 * 一次连续的对话。取代旧的“开场 → 回一句 → 收尾”两轮制（PetBrain 里“已经聊满两轮”就返回）。
 *
 * 规则：
 * - **没有轮数上限**。结束只有三种：他说结束、闲置超过 [idleCloseMillis]、切到别的会话（训练/严格回合）。
 * - 收起气泡、切到别的 App ≠ 结束：在 [resumeWindowMillis] 内回来，接着原话题、原上下文。
 * - 上下文只带最近 [keepTurns] 轮原话 + 一段滚动摘要；超出时由调用方用模型把最旧的几轮压进摘要，
 *   [needsCompaction] 告诉调用方什么时候该压。
 * - 每条消息记录来源（打字 / 语音 / 它主动开口），语音转写不确定时标记出来，不当作他说的原话去下结论。
 */
@Serializable
enum class Speaker { USER, PET }

@Serializable
enum class TurnChannel { TYPED, VOICE, PROACTIVE, REPLY }

@Serializable
data class DialogueTurn(
    val speaker: Speaker,
    val text: String,
    val at: Long,
    val channel: TurnChannel,
    /** 语音转写可能不准时为 true：提示词里会标注“可能听错”。 */
    val uncertain: Boolean = false,
    /** Full manual screen observations are session-local and must not be serialized. */
    val ephemeral: Boolean = false,
)

@Serializable
enum class SessionState { ACTIVE, AWAY, CLOSED }

@Serializable
data class DialogueSession(
    val id: String,
    val startedAt: Long,
    val threadId: String = "",
    val state: SessionState = SessionState.ACTIVE,
    val turns: List<DialogueTurn> = emptyList(),
    /** 被压缩掉的早期内容的摘要（由模型生成，调用方写回）。 */
    val summary: String = "",
    val lastActivityAt: Long = startedAt,
    val closedReason: String = "",
) {
    val userTurns: Int get() = turns.count { it.speaker == Speaker.USER }
    /** 最后一句是它说的、还在等他接：此时它不应该再抢话。 */
    val awaitingUser: Boolean get() = turns.lastOrNull()?.speaker == Speaker.PET
}

class DialogueRules(
    val idleCloseMillis: Long = 30 * 60_000L,
    val resumeWindowMillis: Long = 30 * 60_000L,
    val keepTurns: Int = 16,
    val maxTurnChars: Int = 1_200,
) {
    fun start(id: String, now: Long, threadId: String = "", opening: DialogueTurn? = null): DialogueSession =
        DialogueSession(id, now, threadId, turns = listOfNotNull(opening?.let(::clip)), lastActivityAt = now)

    fun add(s: DialogueSession, turn: DialogueTurn): DialogueSession {
        if (s.state == SessionState.CLOSED) return s
        return s.copy(turns = s.turns + clip(turn), state = SessionState.ACTIVE, lastActivityAt = turn.at)
    }

    /** Full visual observations are usable for this conversation but never durable history. */
    fun addObservation(s: DialogueSession, text: String, now: Long): DialogueSession =
        add(s, DialogueTurn(Speaker.PET, text, now, TurnChannel.REPLY, ephemeral = true))

    /** 收起气泡 / 切走：标记 AWAY，不清上下文。 */
    fun away(s: DialogueSession, now: Long): DialogueSession =
        if (s.state == SessionState.ACTIVE) s.copy(state = SessionState.AWAY, lastActivityAt = now) else s

    /** 回来时能不能接着聊：没关、在恢复窗口内。 */
    fun canResume(s: DialogueSession, now: Long): Boolean =
        s.state != SessionState.CLOSED && now - s.lastActivityAt <= resumeWindowMillis

    fun resume(s: DialogueSession, now: Long): DialogueSession =
        if (canResume(s, now)) s.copy(state = SessionState.ACTIVE, lastActivityAt = now) else s

    /** 每次 tick 调用：闲置太久就关，原因写清楚。 */
    fun tick(s: DialogueSession, now: Long): DialogueSession =
        if (s.state != SessionState.CLOSED && now - s.lastActivityAt > idleCloseMillis) close(s, "闲置超过 " + idleCloseMillis / 60_000 + " 分钟")
        else s

    fun close(s: DialogueSession, reason: String): DialogueSession =
        if (s.state == SessionState.CLOSED) s else s.copy(state = SessionState.CLOSED, closedReason = reason.take(40))

    /** 原话轮数超过保留上限：该把最旧的那几轮压进摘要了。 */
    fun needsCompaction(s: DialogueSession): Boolean = s.turns.size > keepTurns

    /** 需要被压进摘要的那几轮（最旧的），调用方拿去让模型写摘要。 */
    fun turnsToCompact(s: DialogueSession): List<DialogueTurn> =
        if (!needsCompaction(s)) emptyList() else s.turns.take(s.turns.size - keepTurns).filterNot { it.ephemeral }

    /** 调用方拿到新摘要后写回：丢掉已压缩的原话，只留最近 [keepTurns] 轮。 */
    fun applyCompaction(s: DialogueSession, newSummary: String): DialogueSession =
        s.copy(summary = newSummary.trim().take(800), turns = s.turns.takeLast(keepTurns))

    /** 给提示词的上下文：摘要 + 最近原话；语音不确定的句子带标注。 */
    fun contextLines(s: DialogueSession): List<String> = buildList {
        if (s.summary.isNotBlank()) add("【前面聊过（摘要）】" + s.summary)
        s.turns.takeLast(keepTurns).forEach { t ->
            val who = if (t.speaker == Speaker.USER) "他" else "你"
            add(who + "：" + t.text + if (t.uncertain) "（语音转写，可能听错）" else "")
        }
    }

    private fun clip(t: DialogueTurn) = t.copy(text = t.text.trim().take(maxTurnChars))
}
