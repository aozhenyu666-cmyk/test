package cn.companion.pet.core.training

import kotlinx.serialization.Serializable

/**
 * S6：授权材料触发的主动机会。
 *
 * 这是一份**可审计的逻辑记录**，不是新的任务真相：任务与题目的权威状态仍在
 * TrainingController / TrainingStore 里。这里只回答：
 * 这个时刻值不值得主动问一句、问了什么、有没有真的展示出去、结果如何。
 *
 * 关键约束：
 * - 没有可信材料就不编造；
 * - 同一事件不重复发问；
 * - 通知入队 != 用户已看见，展示失败 != SHOWN；
 * - 结算按 sessionId 关联，不按列表顺序；
 * - 重复结算不重复计数；
 * - 连续无效后进入有限冷却，到期可恢复；
 * - 存储失败必须可见，不能静默当成空记录。
 */
@Serializable
enum class OpportunitySource {
    /** 用户显式分享/导入了一份材料。 */
    MATERIAL_IMPORTED,
    /** 已绑定材料上出现了可指出的缺口或推进点。 */
    MATERIAL_CHECKPOINT,
    /** 计划到点（不是情境主动，单独统计）。 */
    SCHEDULE,
    /** 断点恢复。 */
    RESUME,
}

@Serializable
enum class DeliveryState {
    PROPOSED,
    QUEUED,
    /** 真的出现在用户眼前。 */
    SHOWN,
    /** 走了通知，不等于看见了。 */
    NOTIFIED,
    /** 系统不让弹（后台限制等）。 */
    BLOCKED_BY_OS,
    /** 展示调用失败。 */
    FAILED,
    EXPIRED,
    SUPPRESSED,
}

@Serializable
enum class ResolutionState {
    UNANSWERED,
    ANSWERED_UNVERIFIED,
    VERIFIED_PROGRESS,
    NO_PROGRESS,
}

@Serializable
data class Opportunity(
    val opportunityId: String,
    val source: OpportunitySource,
    val sourceEventId: String = "",
    val taskId: String = "",
    /** 与之关联的训练会话；结算按这个关联，不按列表顺序。 */
    val sessionId: String = "",
    val materialRef: String = "",
    val materialVersion: Int = 0,
    val questionId: String = "",
    val observedAt: Long = 0,
    val expiresAt: Long = 0,
    /** 只能引用获准使用的新鲜事实，不能编造“我看到你……”。 */
    val whyNow: String = "",
    val expectedOutcome: String = "",
    val delivery: DeliveryState = DeliveryState.PROPOSED,
    val resolution: ResolutionState = ResolutionState.UNANSWERED,
    val suppressionReason: String = "",
)

/** 仲裁输入：只描述当前可观测事实，不做推断。 */
data class OpportunityContext(
    val now: Long,
    /** 是否已有进行中的训练（有则优先接续，不发新问）。 */
    val trainingActive: Boolean,
    /** 当前是否有未完成且未过期的专注契约。 */
    val focusActive: Boolean,
    /** 是否处于允许打扰的状态（非安静时段、非通话、未被用户关闭主动）。 */
    val allowInterrupt: Boolean,
    /** 材料是否真的存在、有正文且未过期。 */
    val materialFresh: Boolean,
    /** 同一来源事件是否已处理过。 */
    val alreadyHandled: Boolean,
    /** 最近连续无效机会数（用于降频）。 */
    val recentIneffective: Int = 0,
    /** 本人/本次授权是否允许基于材料的主动提问。 */
    val materialAssistAllowed: Boolean,
    /** 是否仍在有限冷却期内。 */
    val coolingDown: Boolean = false,
    /** 冷却剩余毫秒，仅用于给出可读原因。 */
    val cooldownLeftMillis: Long = 0,
    /** 本次材料是否真有可读正文（只有链接时为 false）。 */
    val materialHasBody: Boolean = true,
)

sealed interface OpportunityDecision {
    /** 允许主动提问。 */
    data class Proceed(val whyNow: String) : OpportunityDecision

    /** 主动抑制，并记录原因（可审计，不是静默丢弃）。 */
    data class Suppress(val reason: String) : OpportunityDecision
}

/**
 * 本地仲裁器。规则由代码决定，模型无权决定是否展示。
 *
 * 优先级（R2.1 §5.3 的本地部分）：
 * 已有任务接续 > 材料情境提问 > 探索性提问。这里只做“能不能发”的判断。
 */
class OpportunityArbiter(
    /** 连续无效机会达到这个数就降频（兜底；主路径靠 store 的冷却）。 */
    private val ineffectiveLimit: Int = 3,
) {
    fun decide(ctx: OpportunityContext): OpportunityDecision {
        if (!ctx.materialAssistAllowed) {
            return OpportunityDecision.Suppress("没有本次材料协助授权")
        }
        if (ctx.alreadyHandled) {
            return OpportunityDecision.Suppress("同一事件已处理过")
        }
        if (!ctx.materialFresh) {
            return OpportunityDecision.Suppress("材料缺失或已过期")
        }
        // 只有链接、没有正文：不能假装理解文章。
        if (!ctx.materialHasBody) {
            return OpportunityDecision.Suppress("只有链接没有正文，读不到内容就不出理解题")
        }
        if (ctx.trainingActive) {
            // 已有训练：接续原题，不发新问，避免并行会话。
            return OpportunityDecision.Suppress("已有进行中的训练，优先接续")
        }
        if (!ctx.allowInterrupt) {
            return OpportunityDecision.Suppress("当前不允许打扰")
        }
        if (ctx.coolingDown) {
            val mins = (ctx.cooldownLeftMillis / 60_000).coerceAtLeast(0)
            // 有限冷却：到期自然恢复，不需要用户操作。
            return OpportunityDecision.Suppress("连续几次没有推进，先安静 " + mins + " 分钟")
        }
        if (ctx.recentIneffective >= ineffectiveLimit) {
            return OpportunityDecision.Suppress("连续 " + ctx.recentIneffective + " 次无有效推进，本轮降频")
        }
        val why = if (ctx.focusActive) {
            "材料在专注时段内，结合当前任务问一句"
        } else {
            "材料已导入，指出一个可核验的缺口"
        }
        return OpportunityDecision.Proceed(why)
    }
}

/** 记录结果：区分“重复事件”和“真的存不下”。 */
sealed interface RecordResult {
    data object Recorded : RecordResult
    /** 同一 key 已处理过，不重复。 */
    data object Duplicate : RecordResult
    /** 写入失败：必须让调用方知道，不能当成成功。 */
    data class StorageFailed(val error: String) : RecordResult
}

/**
 * 机会仓库：持久化 + 去重 + 连续无效计数 + 有限冷却。
 *
 * 单次语义：同一 dedupeKey 只产生一个机会；重复事件不重复弹窗。
 * 存储失败通过 [RecordResult.StorageFailed] 和 [lastError] 暴露。
 */
class OpportunityStore(private val file: java.io.File) {
    companion object {
        /** 连续无效达到这个数就进入冷却。 */
        const val COOLDOWN_AFTER = 3
        /** 冷却时长：有限，到期自动恢复。 */
        const val COOLDOWN_MILLIS = 15 * 60_000L
    }

    private val lock = Any()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    @kotlinx.serialization.Serializable
    data class Snapshot(
        val handledKeys: List<String> = emptyList(),
        val opportunities: List<Opportunity> = emptyList(),
        val recentIneffective: Int = 0,
        /** 连续无效后的有限冷却截止时间；到期自然恢复。 */
        val cooldownUntil: Long = 0,
    )

    /** 读取；文件损坏时不静默吞掉——记 lastError 并返回空快照。 */
    fun load(): Snapshot = synchronized(lock) {
        if (!file.exists()) return Snapshot()
        runCatching { json.decodeFromString(Snapshot.serializer(), file.readText()) }
            .onFailure { lastError = "机会文件读不了：" + it.javaClass.simpleName }
            .getOrDefault(Snapshot())
    }

    fun save(snapshot: Snapshot): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (t: Exception) {
            lastError = t.javaClass.simpleName + ":" + t.message
            false
        }
    }

    fun handled(key: String): Boolean = load().handledKeys.contains(key)

    /** 记录一个机会。重复返回 Duplicate，写不下返回 StorageFailed。 */
    fun record(key: String, opportunity: Opportunity): RecordResult = synchronized(lock) {
        val snap = load()
        if (snap.handledKeys.contains(key)) return RecordResult.Duplicate
        val next = snap.copy(
            handledKeys = (snap.handledKeys + key).takeLast(200),
            opportunities = (snap.opportunities + opportunity).takeLast(100),
        )
        if (!save(next)) return RecordResult.StorageFailed(lastError ?: "未知写入错误")
        RecordResult.Recorded
    }

    /** 更新展示结果。展示失败/被系统阻挡不能记 SHOWN。 */
    fun markDelivery(opportunityId: String, delivery: DeliveryState): Boolean = synchronized(lock) {
        val snap = load()
        val idx = snap.opportunities.indexOfLast { it.opportunityId == opportunityId }
        if (idx < 0) return false
        val list = snap.opportunities.toMutableList()
        list[idx] = list[idx].copy(delivery = delivery)
        save(snap.copy(opportunities = list))
    }

    /** 按 sessionId 找到机会——结算必须用这个，不能用列表最后一条。 */
    fun findBySession(sessionId: String): Opportunity? = synchronized(lock) {
        if (sessionId.isBlank()) return null
        load().opportunities.lastOrNull { it.sessionId == sessionId }
    }

    /**
     * 按 sessionId 结算。幂等：已结算过的不重复计数。
     * 完成/中止/到期都走这里，按真实结果更新。
     * 返回是否真正发生变化（false = 找不到或已结算过）。
     */
    fun settleBySession(sessionId: String, resolution: ResolutionState, now: Long): Boolean = synchronized(lock) {
        if (sessionId.isBlank()) return false
        val snap = load()
        val idx = snap.opportunities.indexOfLast { it.sessionId == sessionId }
        if (idx < 0) return false
        val target = snap.opportunities[idx]
        // 已经结算过：不重复计数。
        if (target.resolution != ResolutionState.UNANSWERED) return false
        val list = snap.opportunities.toMutableList()
        list[idx] = target.copy(resolution = resolution)
        val ineffective = if (resolution == ResolutionState.NO_PROGRESS) snap.recentIneffective + 1 else 0
        // 连续无效达到上限 → 设有限冷却，到期自然恢复，不需要用户做什么。
        val cooldown = if (ineffective >= COOLDOWN_AFTER) now + COOLDOWN_MILLIS else 0
        save(snap.copy(opportunities = list, recentIneffective = ineffective, cooldownUntil = cooldown))
    }

    /** 冷却是否仍在生效。 */
    fun cooling(now: Long): Boolean = synchronized(lock) { load().cooldownUntil > now }

    /** 冷却剩余毫秒，用于诊断和可读原因。 */
    fun cooldownLeft(now: Long): Long = synchronized(lock) { (load().cooldownUntil - now).coerceAtLeast(0) }

    /**
     * 按 opportunityId 结算（展示失败等没有 sessionId 的场景）。
     * 幂等：已结算过的机会不会被重复计数。
     */
    fun markResolution(
        opportunityId: String,
        resolution: ResolutionState,
        now: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (opportunityId.isBlank()) return false
        val snap = load()
        val idx = snap.opportunities.indexOfLast { it.opportunityId == opportunityId }
        if (idx < 0) return false
        val target = snap.opportunities[idx]
        if (target.resolution != ResolutionState.UNANSWERED) return false
        val list = snap.opportunities.toMutableList()
        list[idx] = target.copy(resolution = resolution)
        val ineffective = if (resolution == ResolutionState.NO_PROGRESS) snap.recentIneffective + 1 else 0
        val cooldown = if (ineffective >= COOLDOWN_AFTER) now + COOLDOWN_MILLIS else 0
        save(snap.copy(opportunities = list, recentIneffective = ineffective, cooldownUntil = cooldown))
    }
}