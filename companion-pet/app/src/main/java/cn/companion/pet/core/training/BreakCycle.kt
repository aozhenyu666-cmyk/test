package cn.companion.pet.core.training

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * S4：五题换休息的一整轮（R2.1 §6）。
 *
 * 一轮 = 启动时固定的配置快照（几题、休息几分钟、硬截止）+ 已得凭据 + 休息资格。
 * - 只有**本地确定性判分**（[OfflineBank] 题）的通过才产生凭据；模型判的通过算“训练完成”，不发券。
 * - 答案被选项说破（RECOGNIZED_WITH_CHOICES 及以上）的题不给凭据，要求同类迁移题独立做对。
 * - 凭据按 questionId@rubricVersion 去重（[QuestionSetSettlement]）；重复回调、重启、双击都不重复计。
 * - 资格**先落盘再发放**，同一轮只发一次；休息结束时刻在发放时算定，重启不重新计时。
 * - 硬截止到了就结束本轮，未完成的记下来，不加题、不延长。
 */
@Serializable
enum class CycleState { COLLECTING, ON_BREAK, ENDED }

@Serializable
data class BreakCycleState(
    val cycleId: String,
    val startedAt: Long,
    val requiredCredits: Int,
    val restMillis: Long,
    /** 日历时间的硬截止；到了就结束，不因任何原因延长。 */
    val hardDeadline: Long,
    val state: CycleState = CycleState.COLLECTING,
    val set: SetState = SetState(),
    /** 本轮出过的题（含没过的）：迁移题必须是没见过的。 */
    val seenQuestionIds: List<String> = emptyList(),
    /** 答案被说破、等待迁移题的 family。 */
    val pendingTransferFamilies: List<String> = emptyList(),
    /** 已处理过的完成事件（sessionId），重放直接忽略。 */
    val settledSessions: List<String> = emptyList(),
    /** 统计用：提示后完成但不发券的次数、模型判的完成次数。 */
    val promptedOnly: Int = 0,
    val modelJudged: Int = 0,
    val restUntil: Long = 0,
    val grantEventId: String = "",
    val endReason: String = "",
)

/** 一次完成的结算结果，给界面一句人话。 */
sealed interface SettleResult {
    data class Credited(val state: BreakCycleState, val kind: PassKind, val have: Int, val need: Int) : SettleResult
    /** 达到门槛：这一轮的休息资格已经落盘，调用方据此发放。 */
    data class GrantReady(val state: BreakCycleState, val kind: PassKind, val restUntil: Long) : SettleResult
    data class NeedsTransfer(val state: BreakCycleState, val have: Int, val need: Int) : SettleResult
    data class NotCounted(val state: BreakCycleState, val reason: String) : SettleResult
    data class Ignored(val reason: String) : SettleResult
}

class BreakCycleEngine(private val settlement: QuestionSetSettlement = QuestionSetSettlement()) {

    fun start(cycleId: String, now: Long, required: Int, restMillis: Long, hardDeadline: Long): BreakCycleState =
        BreakCycleState(
            cycleId = cycleId,
            startedAt = now,
            requiredCredits = required.coerceIn(1, 20),
            restMillis = restMillis.coerceIn(60_000, 60 * 60_000),
            hardDeadline = hardDeadline,
        )

    /** 记下本轮出过这题。 */
    fun markSeen(s: BreakCycleState, questionId: String): BreakCycleState =
        if (questionId in s.seenQuestionIds) s else s.copy(seenQuestionIds = s.seenQuestionIds + questionId)

    /**
     * 下一题：优先补迁移题（同类、本轮没见过），否则挑本轮没做过的类别，最后才是没见过的任意一题。
     * 全部做过返回 null——不重复刷同一题凑数。
     */
    fun nextQuestion(s: BreakCycleState): TrainingQuestion? {
        val unseen = OfflineBank.ITEMS.filter { it.question.id !in s.seenQuestionIds }
        s.pendingTransferFamilies.firstNotNullOfOrNull { fam -> unseen.firstOrNull { it.family == fam } }
            ?.let { return it.question }
        val creditedFamilies = s.set.credits.mapNotNull { OfflineBank.byId(it.questionId)?.family }.toSet()
        val triedFamilies = s.seenQuestionIds.mapNotNull { OfflineBank.byId(it)?.family }.toSet()
        return (unseen.firstOrNull { it.family !in triedFamilies }
            ?: unseen.firstOrNull { it.family !in creditedFamilies }
            ?: unseen.firstOrNull())?.question
    }

    /** 从一次完成的训练推出这题的通过类型；不是本地题库题返回 null。 */
    fun classify(q: TrainingQuestion, session: TrainingSession): Pair<PassKind, Boolean>? {
        if (OfflineBank.item(q) == null) return null
        val attempts = session.attempts
        val maxAssist = attempts.maxOfOrNull { it.assistanceLevel } ?: AssistanceLevel.INDEPENDENT
        val revealed = maxAssist >= AssistanceLevel.RECOGNIZED_WITH_CHOICES
        val retried = attempts.any { it.verdict != null && it.verdict != Verdict.PASS }
        val kind = when {
            maxAssist == AssistanceLevel.INDEPENDENT && !retried -> PassKind.FIRST_TRY_INDEPENDENT
            else -> PassKind.PROMPTED_PASS
        }
        return kind to revealed
    }

    /**
     * 结算一次完成。[session] 必须是 COMPLETED 的训练会话。
     * 同一 sessionId 只结算一次；非本轮、已结束、已在休息的一律忽略。
     */
    fun settle(s: BreakCycleState, q: TrainingQuestion, session: TrainingSession, now: Long): SettleResult {
        if (s.state != CycleState.COLLECTING) return SettleResult.Ignored("本轮不在攒题阶段")
        if (now >= s.hardDeadline) return SettleResult.Ignored("本轮已到硬截止")
        if (session.state != TrainingState.COMPLETED) return SettleResult.Ignored("题目没有完成")
        if (session.id in s.settledSessions) return SettleResult.Ignored("这次完成已经结算过")
        val base = markSeen(s, q.id).copy(settledSessions = (s.settledSessions + session.id).takeLast(50))
        val item = OfflineBank.item(q)
        // 只认本地确定性判分；每一槽都必须由本地判过。
        val allLocal = session.attempts.filter { it.verdict == Verdict.PASS }
            .all { it.evaluationSource.startsWith(OfflineTraining.SOURCE_PREFIX) && it.evaluationSource != "LOCAL_FORM_ONLY" }
        if (item == null || !allLocal) {
            return SettleResult.NotCounted(base.copy(modelJudged = base.modelJudged + 1),
                "这题不是本地可判题，算完成训练，但不换休息")
        }
        val (rawKind, revealed) = classify(q, session)!!
        val isTransfer = item.family in base.pendingTransferFamilies
        if (revealed) {
            // 说破了答案：这题不给凭据；同类还没排上迁移题就排上。
            val pending = if (isTransfer || item.family in base.pendingTransferFamilies) base.pendingTransferFamilies
                else base.pendingTransferFamilies + item.family
            val next = base.copy(pendingTransferFamilies = pending, promptedOnly = base.promptedOnly + 1)
            return SettleResult.NeedsTransfer(next, next.set.credits.size, next.requiredCredits)
        }
        val kind = if (isTransfer) PassKind.TRANSFER_PASS else rawKind
        return when (val r = settlement.record(base.set, q.id, item.rubricVersion, kind, answerRevealed = false, now = now)) {
            is CreditResult.Duplicate -> SettleResult.NotCounted(base, "这题本轮已经算过")
            is CreditResult.NeedsTransfer -> SettleResult.NotCounted(base, r.reason)
            is CreditResult.Counted -> {
                var next = base.copy(
                    set = r.state,
                    pendingTransferFamilies = if (isTransfer) base.pendingTransferFamilies - item.family else base.pendingTransferFamilies,
                )
                if (next.set.credits.size >= next.requiredCredits && !next.set.grantIssued) {
                    // 休息结束不能晚于硬截止。
                    val until = minOf(now + next.restMillis, next.hardDeadline)
                    next = next.copy(
                        state = CycleState.ON_BREAK,
                        set = next.set.copy(grantIssued = true, grantIssuedAt = now),
                        restUntil = until,
                        grantEventId = "cycle-" + next.cycleId,
                    )
                    SettleResult.GrantReady(next, kind, until)
                } else SettleResult.Credited(next, kind, next.set.credits.size, next.requiredCredits)
            }
        }
    }

    /**
     * 每次 tick：休息到点 → 若硬截止未到，开新一轮继续攒；硬截止到了 → 结束本轮。
     * 返回 null 表示没变化。
     */
    fun tick(s: BreakCycleState, now: Long, newCycleId: () -> String): BreakCycleState? = when {
        s.state == CycleState.ENDED -> null
        now >= s.hardDeadline -> s.copy(state = CycleState.ENDED,
            endReason = if (s.state == CycleState.ON_BREAK) "DEADLINE_DURING_BREAK" else "DEADLINE_INCOMPLETE")
        s.state == CycleState.ON_BREAK && now >= s.restUntil ->
            // 新一轮沿用原硬截止与配置快照，不延长。
            start(newCycleId(), now, s.requiredCredits, s.restMillis, s.hardDeadline)
                .copy(seenQuestionIds = s.seenQuestionIds)
        else -> null
    }

    fun end(s: BreakCycleState, reason: String): BreakCycleState =
        if (s.state == CycleState.ENDED) s else s.copy(state = CycleState.ENDED, endReason = reason)
}

/** 原子落盘；读坏了返回 null 并记错误，不臆造状态。 */
class BreakCycleStore(private val root: File) {
    private val file = File(root, "training/break-cycle.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    fun load(): BreakCycleState? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { json.decodeFromString(BreakCycleState.serializer(), file.readText()) }
            .onFailure { lastError = "读取失败：" + it.javaClass.simpleName }
            .getOrNull()
    }

    fun save(s: BreakCycleState): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(BreakCycleState.serializer(), s))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            lastError = null
            true
        } catch (t: Exception) {
            lastError = t.javaClass.simpleName + ":" + t.message
            false
        }
    }
}
