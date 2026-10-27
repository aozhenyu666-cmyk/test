package cn.companion.pet.core.presence

import cn.companion.pet.core.rhythm.ActivityPlan
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId

data class PresenceConfig(
    /** 主动开口（本机提示 + 模型开口）每天上限；用户主动发起的、纯动画不算。 */
    val dailyCap: Int = 20,
    /** 两次主动开口之间至少隔这么久。 */
    val minIntervalMillis: Long = 3 * 60_000L,
    /** 连续这么多次没回应就冷却。 */
    val ignoreLimit: Int = 3,
    val ignoreCooldownMillis: Long = 30 * 60_000L,
    /** 明确点 × / 收起后这么久不主动开口。 */
    val dismissCooldownMillis: Long = 30 * 60_000L,
    /**
     * 是否按星期与时段浮动活跃度（见核心层 core.rhythm.ActivityBudget）。
     *
     * 默认开启。**它只会替换上限与间隔这两个数字，永远不会缩短 dismissedUntil**：
     * 用户明确拒绝的冷却优先级最高，自适应逻辑碰不到它。
     */
    val useAdaptiveBudget: Boolean = true,
) {
    companion object {
        val CAP_CHOICES = listOf(10, 20, 30, 50)
    }
}

/** 持久化的克制状态。只在开口/反馈时写盘，评估时不写。 */
@Serializable
data class PresencePolicyState(
    val day: String = "",
    val spokenToday: Int = 0,
    val consecutiveIgnores: Int = 0,
    /** 连续忽略触发的冷却截止时间。 */
    val ignoreCooldownUntil: Long = 0,
    /** 明确拒绝触发的冷却截止时间；自动逻辑不能缩短它。 */
    val dismissedUntil: Long = 0,
    val lastProactiveAt: Long = 0,
    val lastFeedback: String = "",
    val lastFeedbackAt: Long = 0,
)

data class PresenceGate(val allowed: Boolean, val reason: String)

/** 克制状态的存储：同目录原子替换，失败保留旧文件。 */
class PresencePolicyStore(
    root: File,
    private val replace: (File, File) -> Boolean = { from, to ->
        runCatching {
            java.nio.file.Files.move(from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrElse {
            runCatching {
                java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        }
    },
) {
    private val file = File(root, "presence/policy.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** 写盘次数（测试用来确认评估不写盘）。 */
    @Volatile var writes = 0
        private set
    @Volatile var lastError: String? = null
        private set

    fun read(): PresencePolicyState = runCatching {
        if (!file.exists()) PresencePolicyState() else json.decodeFromString(PresencePolicyState.serializer(), file.readText())
    }.getOrElse { lastError = "克制状态文件读不了，按默认处理"; PresencePolicyState() }

    fun write(state: PresencePolicyState): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(PresencePolicyState.serializer(), state))
        val ok = replace(tmp, file)
        if (ok) { writes++; lastError = null } else { tmp.delete(); lastError = "克制状态写入失败，保留旧状态" }
        ok
    } catch (e: Exception) { lastError = "克制状态写入失败，保留旧状态"; false }
}

/**
 * 克制策略：每日上限、最短间隔、连续忽略冷却、明确拒绝冷却。是代码，不是人设。
 * 明确拒绝（DISMISSED）和暂停优先级最高：自动的忽略退避、用户回话都不会缩短拒绝冷却。
 */
class PresencePolicy(
    var config: PresenceConfig,
    private val store: PresencePolicyStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    @Volatile private var state: PresencePolicyState = store.read()

    fun snapshot(): PresencePolicyState = state

    private fun dayOf(now: Long): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    /** 今天已主动开口次数（跨午夜自动归零，只读不写盘）。 */
    fun spokenToday(now: Long): Int = if (state.day == dayOf(now)) state.spokenToday else 0

    /**
     * 这一刻能不能主动开口（本机提示或模型开口）。只读，不写盘。
     *
     * [plan] 是按星期与时段算出的活跃度预算；为 null 时用 [PresenceConfig] 里的固定值，
     * 便于不关心时段的调用方与既有测试保持原样。
     *
     * **优先级不可重排**：paused → dismissedUntil → ignoreCooldownUntil → 上限 → 间隔。
     * 自适应预算只替换「上限」和「间隔」两个数字，绝不会退回到缩短 dismissedUntil。
     */
    fun check(now: Long, paused: Boolean = false, plan: ActivityPlan? = null): PresenceGate {
        if (paused) return PresenceGate(false, "已暂停")
        if (state.dismissedUntil > now) return PresenceGate(false, "你刚关掉过，安静一会儿")
        if (state.ignoreCooldownUntil > now) return PresenceGate(false, "连续没回应，冷却中")
        val adaptive = config.useAdaptiveBudget
        val cap = if (adaptive && plan != null) plan.dailyCap else config.dailyCap
        val interval = if (adaptive && plan != null) plan.minIntervalMillis else config.minIntervalMillis
        // 时段预算为 0 表示这个点完全不主动开口（凌晨陪熬夜、不吵睡觉）。
        if (cap <= 0) return PresenceGate(false, "这个点不开口，安静陪你")
        if (spokenToday(now) >= cap) return PresenceGate(false, "今天主动开口已到上限")
        if (state.lastProactiveAt > 0 && now - state.lastProactiveAt < interval) return PresenceGate(false, "离上次开口太近")
        return PresenceGate(true, "允许")
    }

    /** 主动开口真的显示出来了才调用。 */
    fun recordShown(now: Long) {
        val today = dayOf(now)
        val base = if (state.day == today) state else state.copy(day = today, spokenToday = 0)
        update(base.copy(spokenToday = base.spokenToday + 1, lastProactiveAt = now))
    }

    fun feedback(feedback: PresenceFeedback, now: Long) {
        val s = state
        val next = when (feedback) {
            PresenceFeedback.INTERACTED -> s.copy(consecutiveIgnores = 0, ignoreCooldownUntil = 0)
            PresenceFeedback.IGNORED -> {
                val count = s.consecutiveIgnores + 1
                if (count >= config.ignoreLimit) s.copy(consecutiveIgnores = 0, ignoreCooldownUntil = maxOf(s.ignoreCooldownUntil, now + config.ignoreCooldownMillis))
                else s.copy(consecutiveIgnores = count)
            }
            // 明确拒绝不算“被忽略”，单独冷却；只能延长，不能被缩短。
            PresenceFeedback.DISMISSED -> s.copy(dismissedUntil = maxOf(s.dismissedUntil, now + config.dismissCooldownMillis))
            PresenceFeedback.PAUSED -> s.copy(dismissedUntil = maxOf(s.dismissedUntil, now + config.dismissCooldownMillis))
        }
        update(next.copy(lastFeedback = feedback.name, lastFeedbackAt = now))
    }

    /** 用户自己找它说话：忽略计数清零、解除忽略冷却（拒绝冷却不动）。 */
    fun userInitiated(now: Long) {
        if (state.consecutiveIgnores == 0 && state.ignoreCooldownUntil <= now) return
        update(state.copy(consecutiveIgnores = 0, ignoreCooldownUntil = 0))
    }

    private fun update(next: PresencePolicyState) {
        state = next
        store.write(next)
    }
}
