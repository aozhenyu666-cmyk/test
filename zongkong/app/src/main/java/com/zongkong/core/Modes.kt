package com.zongkong.core

/**
 * 两档模式（普通 / 严格）的用户操作（报告 §3）。
 *
 * 三条硬规则，写在这里而不是散在各处：
 *  1. **只有用户能改**。任何 AI 模型响应、任何远端推送都不得调用这里（报告 §5）。
 *  2. **窗口只减不增**。结束时间只能由用户显式设置；到期自动结束，绝不自动延长
 *     （报告 §3：「不得未经用户明确授权自动延长窗口」）。
 *  3. **紧急暂停是独立通道**。不写理由、不等冷静期、不经过模型、不需要网络，
 *     不需要满足任何字数/答题门槛（报告 §3.5）。
 *
 * 纯函数：不碰 Android、不读写盘，JVM 单测直接喂 now。
 */
object Modes {

    /** 严格窗口的合法时长范围（分钟）。默认 6 小时，与报告建议一致。 */
    const val MIN_WINDOW_MIN = 5
    const val DEFAULT_WINDOW_MIN = 6 * 60
    const val MAX_WINDOW_MIN = 24 * 60

    /** 开始严格模式：从现在起 [minutes] 分钟。返回新的 ModeConfig（revision +1）。 */
    fun startStrict(mode: ModeConfig, now: Long, minutes: Int = DEFAULT_WINDOW_MIN): ModeConfig {
        val m = minutes.coerceIn(MIN_WINDOW_MIN, MAX_WINDOW_MIN)
        return mode.copy(
            tier = ModeConfig.Tier.STRICT,
            startsAt = now,
            endsAt = now + m * 60_000L,
            // 新开窗口时清掉上一次的紧急暂停，否则新窗口会带着旧暂停一起开始。
            emergencyAt = 0,
            revision = mode.revision + 1,
        )
    }

    /** 回到普通模式：立即结束窗口（不再拦截）。 */
    fun toNormal(mode: ModeConfig): ModeConfig = mode.copy(
        tier = ModeConfig.Tier.NORMAL,
        startsAt = 0,
        endsAt = 0,
        emergencyAt = 0,
        revision = mode.revision + 1,
    )

    /**
     * 提前结束严格窗口（用户主动）。
     *
     * 与 [emergency] 的区别：这条是「正常提前结束」，报告允许冷却期和二次确认；
     * 真正紧急时应该用 [emergency]，那条没有任何门槛。
     */
    fun endStrict(mode: ModeConfig): ModeConfig = toNormal(mode)

    /**
     * 本地紧急暂停：立刻停止强制拦截，不需要任何门槛。
     *
     * 只熄灭**当前窗口**的强制力：窗口本身和它的结束时间不变，
     * 所以到点照样自动结束，不会因为按了暂停就把监管期无限跳过。
     */
    fun emergency(mode: ModeConfig, now: Long): ModeConfig =
        if (mode.tier != ModeConfig.Tier.STRICT || mode.endsAt <= now) mode
        else mode.copy(emergencyAt = now, revision = mode.revision + 1)

    /** 解除紧急暂停，恢复本窗口的监管（用户主动）。 */
    fun resume(mode: ModeConfig): ModeConfig =
        if (mode.emergencyAt == 0L) mode
        else mode.copy(emergencyAt = 0, revision = mode.revision + 1)

    /**
     * 到期归一：窗口到点后把 tier 落回 NORMAL，避免界面一直显示「严格」但实际不拦。
     *
     * 为什么需要它：`windowActive(now)` 已经能算对「现在是否在窗口内」，
     * 但如果没人把 tier 改回来，界面上会长期挂着一个「严格」标签、用户以为还在监管。
     * 这里显式归一，让状态和事实一致。
     */
    fun expire(mode: ModeConfig, now: Long): ModeConfig =
        if (mode.tier == ModeConfig.Tier.STRICT && mode.endsAt in 1..now) toNormal(mode) else mode

    /** 一句话说明现在是什么状态（界面和日志共用，避免各说各话）。 */
    fun describe(mode: ModeConfig, now: Long): String = when {
        mode.tier != ModeConfig.Tier.STRICT -> "普通模式"
        mode.endsAt <= now -> "严格窗口已结束"
        mode.emergencyAt > 0 -> "严格窗口内·已紧急暂停（到点照样结束）"
        else -> "严格监管中"
    }
}
