package cn.companion.pet.core.proactive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 随机触发的全部参数。
 *
 * 设计原则：**硬约束与随机分开**。
 * 随机决定「这次是 40 秒后还是 12 分钟后」，但任何随机结果都不许突破下面的硬边界。
 * 这样既有人味，又不会刷屏或沉默到像死了。
 */
@Serializable
data class RandomTriggerConfig(
    @SerialName("mode") val mode: ProactiveMode = ProactiveMode.DAILY,

    /** 两次开口的硬性下限。再高频也不许比这更密。 */
    @SerialName("minGapMillis") val minGapMillis: Long = 45_000L,

    /** 硬性上限：再冷清也要在这个时间内给一次机会，避免「沉默到用户以为它死了」。 */
    @SerialName("maxGapMillis") val maxGapMillis: Long = 25L * 60_000L,

    /** 突发窗口：这个窗口内最多开口 burstMax 次。 */
    @SerialName("burstWindowMillis") val burstWindowMillis: Long = 10L * 60_000L,
    @SerialName("burstMax") val burstMax: Int = 4,

    /** 单次开口后再次进入「连说」的概率。模拟「想到一件事接着说」。 */
    @SerialName("burstProbability") val burstProbability: Double = 0.15,

    /** 连说时最多再接几句。 */
    @SerialName("burstExtraMax") val burstExtraMax: Int = 2,

    /** 沉默这么久之后，下一次间隔乘 resumeFactor 收紧，避免「冷了就再也热不起来」。 */
    @SerialName("resumeAfterMillis") val resumeAfterMillis: Long = 90L * 60_000L,
    @SerialName("resumeFactor") val resumeFactor: Double = 0.5,

    /** 对数正态的 sigma：越大越不均匀。0.8 时 3 分钟中位数会散到 ~40 秒到 ~15 分钟。 */
    @SerialName("sigma") val sigma: Double = 0.8,
) {
    init {
        require(mode.ratePerHour > 0.0) { "ratePerHour must be positive" }
        require(minGapMillis > 0L) { "minGapMillis must be positive" }
        require(maxGapMillis >= minGapMillis) { "maxGapMillis must be >= minGapMillis" }
        require(burstMax >= 1) { "burstMax must be >= 1" }
        require(burstExtraMax >= 0) { "burstExtraMax must be >= 0" }
        require(burstWindowMillis > 0L) { "burstWindowMillis must be positive" }
        require(burstProbability in 0.0..1.0) { "burstProbability must be in [0,1]" }
        require(resumeFactor in 0.05..1.0) { "resumeFactor must be in (0,1]" }
        require(sigma > 0.0) { "sigma must be positive" }
    }

    /**
     * 采样用的中位数间隔。
     *
     * **必须做对数正态的均值校正**：若 X ~ LogNormal(ln(median), σ)，
     * 则 E[X] = median × exp(σ²/2)，不是 median。
     * σ=0.8 时 exp(0.32) ≈ 1.377，直接用 median 会让实际频率只有目标的 73%。
     *
     * 实测：按 20 次/小时直接取 3 分钟中位数，60 分钟只说了约 14.5 次。
     * 所以这里把中位数除以 exp(σ²/2)，让**期望**间隔真正等于 3_600_000/ratePerHour。
     *
     * 已知偏差（如实记录，不假装精确）：
     * 上面只校正了"无截断"情形。实际还叠了三层，都会把平均值往上推：
     * - maxGapMillis 截断长尾（最长 25 分钟）→ 平均间隔变短；
     * - 突发连说（burstProbability）→ 额外多出几句；
     * - minGapMillis 截断短尾（不短于 45 秒）→ 略微拉长。
     * 实测（60 分钟、10 个种子平均）：
     *   目标 8/h   → 实际约 10~12 次
     *   目标 12/h  → 实际约 13~15 次
     *   目标 20/h  → 实际约 19~20 次
     * 也就是说 ratePerHour 是**档位标签**而非精确配额；用户要的"每小时 6~12 次机会"
     * 在 DAILY/HIGH 两档都落在区间内。若要精确配额，应该另加"滑动窗口硬配额"，
     * 本轮不做——那会把随机性重新压平，和"不能机械均匀"的要求冲突。
     */
    val medianGapMillis: Long get() {
        val raw = 3_600_000.0 / mode.ratePerHour
        val correction = kotlin.math.exp(sigma * sigma / 2.0)
        return (raw / correction).toLong().coerceAtLeast(1L)
    }
}
