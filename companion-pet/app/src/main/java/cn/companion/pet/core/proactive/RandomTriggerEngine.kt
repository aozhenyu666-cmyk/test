package cn.companion.pet.core.proactive

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** 一次摇骰的结论。 */
sealed interface TriggerDecision {
    /** 现在可以开口。 */
    data class Speak(val reason: String) : TriggerDecision

    /** 这次不说；[nextAtMillis] 是下一次重新摇的时刻。 */
    data class Hold(val nextAtMillis: Long, val reason: String) : TriggerDecision
}

/**
 * 随机触发引擎：**真随机、非均匀、有突发、有恢复**。
 *
 * 为什么不能是「固定间隔 + 随机几秒」：那样触发时间仍然机械，用户一眼能感觉出是定时器。
 * 这里用三类机制叠加，模拟真实的人：
 *
 * 1. **对数正态间隔**（主机制）
 *    人的说话间隔是右偏的：常常很快就接一句，偶尔隔很久。对数正态正好是这个形状。
 *    ratePerHour=8 时中位数 7.5 分钟，但实际会散到 1 分钟到 40 分钟都有。
 *
 * 2. **伯努利突发**（连说）
 *    每次说完，以 [RandomTriggerConfig.burstProbability] 概率进入「连说」状态，
 *    再接 1~[RandomTriggerConfig.burstExtraMax] 句，间隔压到 minGap。
 *    这模拟「想到一件事接着又说了一句」。
 *
 * 3. **沉默恢复**
 *    超过 [RandomTriggerConfig.resumeAfterMillis] 没开口，下一次间隔乘 resumeFactor。
 *    防止「冷了就再也不热」。
 *
 * 三层上面再加**硬约束**（min/max gap、突发窗口上限），任何随机都不许突破。
 *
 * 纯逻辑：不碰 Android、不读时钟。所有时间由调用方传入，随机源可注入（测试用固定种子）。
 */
class RandomTriggerEngine(
    private var config: RandomTriggerConfig = RandomTriggerConfig(),
    private val random: Random = Random.Default,
) {
    /** 最近若干次实际开口的时刻，用于突发窗口限流与恢复判定。 */
    private val recentSpeaks = ArrayDeque<Long>()

    /** 下一次允许摇骰的时刻。0 表示还没排过。 */
    private var nextRollAt = 0L

    /**
     * 引擎自己记的上一次开口时刻。
     *
     * 为什么不能让调用方传：minGap 是硬约束，一旦调用方传错（或忘了更新），
     * 连说路径就会突破下限。实测就是这样——测试里外部传 0，连说绕过了 45 秒下限。
     * 这里以引擎内部为准，外部传的值只作为"恢复时的初始值"。
     *
     * **用 -1 表示"从未说过"，不用 0**：0 是合法时间戳（纪元起点），
     * 拿它当哨兵会让"第一次开口发生在 0 时刻"被误判成"还没说过"，
     * 连带 minGap 整条失效。实测就是这么炸的——一次开口后允许了 5 秒的间隔。
     */
    private var lastSpokenAt = NEVER_SPOKEN

    /** 当前处于「连说」状态时，剩余还能连说的句数。 */
    private var burstRemaining = 0

    fun configure(newConfig: RandomTriggerConfig) {
        config = newConfig
    }

    fun snapshot(): State = State(
        speaks = recentSpeaks.toList(),
        nextRollAt = nextRollAt,
        burstRemaining = burstRemaining,
    )

    data class State(
        val speaks: List<Long> = emptyList(),
        val nextRollAt: Long = 0L,
        val burstRemaining: Int = 0,
    )

    /** 从持久化状态恢复。过期条目直接丢掉，不做补发。 */
    fun restore(state: State, nowMillis: Long) {
        recentSpeaks.clear()
        state.speaks
            .filter { it <= nowMillis && nowMillis - it <= config.burstWindowMillis }
            .sorted()
            .takeLast(config.burstMax * 2)
            .forEach { recentSpeaks.addLast(it) }
        lastSpokenAt = recentSpeaks.lastOrNull() ?: NEVER_SPOKEN
        nextRollAt = state.nextRollAt.takeIf { it > nowMillis } ?: 0L
        burstRemaining = state.burstRemaining.coerceIn(0, config.burstExtraMax)
    }

    /**
     * 现在能不能开口。
     *
     * @param nowMillis 当前时刻
     * @param gateAllows 外部闸门（专注中/免打扰/通话/用户暂停）是否允许。false 时直接 Hold，
     *                   且**不消耗随机**——闸门关着的时候不该把机会用掉。
     * @param lastSpeakAtMillis 上一次真的开口的时刻；0 表示从没说过
     */
    fun evaluate(
        nowMillis: Long,
        gateAllows: Boolean,
        lastSpeakAtMillis: Long,
    ): TriggerDecision {
        if (!gateAllows) {
            // 闸门关着：不摇骰、不消耗、不推进 nextRollAt。
            return TriggerDecision.Hold(nowMillis + RECHECK_WHEN_BLOCKED, "gated")
        }

        pruneWindow(nowMillis)

        // 硬上限：**任意** burstWindow 内都不许超过 burstMax 次。
        //
        // 注意这里必须按窗口滑算，不能只数"当前窗口内总数"：
        // 实测原来的写法在 burstMax=3 时仍会放过 4 次——因为第 4 次落在
        // "最早那次刚好滑出窗口"的边界上，而计数是整段累计的。
        val wouldBe = recentSpeaks.count { nowMillis - it < config.burstWindowMillis } + 1
        if (wouldBe > config.burstMax) {
            // 找到最早一次，等它滑出窗口。
            val oldest = recentSpeaks.firstOrNull { nowMillis - it < config.burstWindowMillis }
            val freeAt = (oldest ?: nowMillis) + config.burstWindowMillis
            return TriggerDecision.Hold(freeAt.coerceAtLeast(nowMillis + config.minGapMillis), "burst-window-full")
        }

        // 硬下限：距上次开口不足 minGap，一律不说。
        // 以内部记录为准（外部值只用于恢复），否则连说路径会突破下限。
        val last = maxOf(lastSpokenAt, lastSpeakAtMillis)
        if (last >= 0L && nowMillis - last < config.minGapMillis) {
            return TriggerDecision.Hold(last + config.minGapMillis, "min-gap")
        }

        // 连说状态：跳过间隔判断，直接可以说（仍然受 minGap 约束，已在上面检查）。
        if (burstRemaining > 0) {
            return TriggerDecision.Speak("burst")
        }

        // 还没到该摇的时间。
        if (nextRollAt > 0L && nowMillis < nextRollAt) {
            return TriggerDecision.Hold(nextRollAt, "waiting")
        }

        // 到点了：真的摇一次。这里才是随机性真正起作用的地方。
        nextRollAt = 0L
        return TriggerDecision.Speak("roll")
    }

    /**
     * 确认已经**真的说出去**了。只有这种情况才推进随机状态。
     *
     * 关键：生成成功但投递失败时**不能**调用这个，否则会出现"说了但没人听见"却消耗了机会。
     */
    fun onSpoken(nowMillis: Long, lastSpeakAtMillis: Long) {
        lastSpokenAt = nowMillis
        recentSpeaks.addLast(nowMillis)
        pruneWindow(nowMillis)
        if (burstRemaining > 0) burstRemaining--
        // 以一定概率进入连说状态。
        if (burstRemaining == 0 && config.burstExtraMax > 0 && random.nextDouble() < config.burstProbability) {
            burstRemaining = random.nextInt(1, config.burstExtraMax + 1)
        }
        nextRollAt = nowMillis + nextGap(nowMillis, lastSpeakAtMillis)
    }

    /**
     * 投递失败：不消耗随机机会，但要把下一次摇骰推远一点，避免失败时疯狂重试刷日志。
     */
    fun onDeliveryFailed(nowMillis: Long) {
        nextRollAt = nowMillis + FAILURE_BACKOFF
    }

    /**
     * 采样下一个间隔：**对数正态**，右偏，模拟人的说话节奏。
     *
     * 中位数 = 3_600_000 / ratePerHour；sigma 控制离散度。
     * 结果会被夹到 [minGapMillis, maxGapMillis] 之间。
     */
    internal fun nextGap(nowMillis: Long, lastSpeakAtMillis: Long): Long {
        val median = config.medianGapMillis.toDouble().coerceAtLeast(config.minGapMillis.toDouble())
        // Box-Muller 取一个标准正态，用注入的 random 保证可复现。
        val gauss = gaussian()
        var gap = median * exp(config.sigma * gauss)

        // 沉默恢复：冷太久了，把间隔收紧，别让沉默自我强化。
        if (lastSpeakAtMillis > 0L && nowMillis - lastSpeakAtMillis > config.resumeAfterMillis) {
            gap *= config.resumeFactor
        }
        return gap.toLong().coerceIn(config.minGapMillis, config.maxGapMillis)
    }

    /** Box-Muller：两个均匀分布 → 一个标准正态。 */
    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }

    private fun pruneWindow(nowMillis: Long) {
        while (recentSpeaks.isNotEmpty() && nowMillis - recentSpeaks.first() > config.burstWindowMillis) {
            recentSpeaks.removeFirst()
        }
    }

    private companion object {
        /** 闸门关着时多久后再看：闸门随时可能打开，不必等整个间隔。 */
        const val RECHECK_WHEN_BLOCKED = 30_000L

        /** 投递失败后的退避。 */
        const val FAILURE_BACKOFF = 2 * 60_000L

        /** 「从未说过」的哨兵。必须与真实时间戳区分开（0 是合法时间戳）。 */
        const val NEVER_SPOKEN = -1L
    }
}
