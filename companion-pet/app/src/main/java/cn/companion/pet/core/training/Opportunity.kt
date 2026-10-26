package cn.companion.pet.core.training

import kotlinx.serialization.Serializable

/**
 * S6：授权材料触发的主动机会。
 *
 * 这是一份**可审计的逻辑记录**，不是新的任务真相：任务与题目的权威状态仍在
 * TrainingController / TrainingStore 里。这里只回答：
 * 这个时刻值不值得主动问一句、问了什么、有没有真的展示出去。
 *
 * 关键约束：
 * - 没有可信材料就不编造；
 * - 同一事件/同一材料版本不重复发问；
 * - 通知入队 != 用户已看见；
 * - 连续无效机会要减少打扰，而不是加码。
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
    SHOWN,
    NOTIFIED,
    BLOCKED_BY_OS,
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
    /** 材料是否真的存在且未过期。 */
    val materialFresh: Boolean,
    /** 同一来源事件是否已处理过。 */
    val alreadyHandled: Boolean,
    /** 最近连续无效机会数（用于降频）。 */
    val recentIneffective: Int = 0,
    /** 本人/本次授权是否允许基于材料的主动提问。 */
    val materialAssistAllowed: Boolean,
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
    /** 连续无效机会达到这个数就降频。 */
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
        if (ctx.trainingActive) {
            // 已有训练：接续原题，不发新问，避免并行会话。
            return OpportunityDecision.Suppress("已有进行中的训练，优先接续")
        }
        if (!ctx.allowInterrupt) {
            return OpportunityDecision.Suppress("当前不允许打扰")
        }
        if (ctx.recentIneffective >= ineffectiveLimit) {
            // 连续没效果：减少打扰，而不是加码。
            return OpportunityDecision.Suppress("连续 " + ctx.recentIneffective + " 次无有效推进，本轮降频")
        }
        val why = if (ctx.focusActive) "材料在专注时段内，结合当前任务问一句" else "材料已导入，指出一个可核验的缺口"
        return OpportunityDecision.Proceed(why)
    }
}

/**
 * 机会仓库：持久化 + 去重 + 连续无效计数。
 *
 * 单次语义：同一 dedupeKey 只产生一个机会；重复事件不重复弹窗。
 */
class OpportunityStore(private val file: java.io.File) {
    private val lock = Any()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    @kotlinx.serialization.Serializable
    data class Snapshot(
        val handledKeys: List<String> = emptyList(),
        val opportunities: List<Opportunity> = emptyList(),
        val recentIneffective: Int = 0,
    )

    fun load(): Snapshot = synchronized(lock) {
        if (!file.exists()) return Snapshot()
        runCatching { json.decodeFromString(Snapshot.serializer(), file.readText()) }
            .onFailure { lastError = it.javaClass.simpleName }
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

    /** 记录一个机会；同一 key 只记一次。返回是否真正新增。 */
    fun record(key: String, opportunity: Opportunity): Boolean = synchronized(lock) {
        val snap = load()
        if (snap.handledKeys.contains(key)) return false
        val next = snap.copy(
            handledKeys = (snap.handledKeys + key).takeLast(200),
            opportunities = (snap.opportunities + opportunity).takeLast(100),
        )
        save(next)
    }

    /** 更新展示结果（PROPOSED → SHOWN/NOTIFIED/BLOCKED_BY_OS）。 */
    fun markDelivery(opportunityId: String, delivery: DeliveryState): Boolean = synchronized(lock) {
        val snap = load()
        val idx = snap.opportunities.indexOfLast { it.opportunityId == opportunityId }
        if (idx < 0) return false
        val list = snap.opportunities.toMutableList()
        list[idx] = list[idx].copy(delivery = delivery)
        save(snap.copy(opportunities = list))
    }

    /** 更新解决结果，并维护连续无效计数。 */
    fun markResolution(opportunityId: String, resolution: ResolutionState): Boolean = synchronized(lock) {
        val snap = load()
        val idx = snap.opportunities.indexOfLast { it.opportunityId == opportunityId }
        if (idx < 0) return false
        val list = snap.opportunities.toMutableList()
        list[idx] = list[idx].copy(resolution = resolution)
        val ineffective = when (resolution) {
            ResolutionState.NO_PROGRESS -> snap.recentIneffective + 1
            ResolutionState.VERIFIED_PROGRESS -> 0
            else -> snap.recentIneffective
        }
        save(snap.copy(opportunities = list, recentIneffective = ineffective))
    }
}
