package com.dshdriver.core

/** 带宽水位。 */
enum class Cog {
    /** 有效推进 */
    ACTIVE_FLOW,
    /** 虚假专注：回得快但没依据 */
    HIGH_SPEED_SKIMMING,
    /** 已限速：沉默、卡壳、机械敷衍 */
    THROTTLED_EMPTY,
    /** 切屏 / 退出 Live */
    ESCAPED,
}

data class CogState(val state: Cog, val reason: String)

data class Reply(val text: String, val latencyMs: Long, val features: Features, val expects: String, val channel: String)

object Bandwidth {
    data class Config(val fastMs: Long = 3_000, val slowMs: Long = 45_000, val silenceMs: Long = 60_000)

    /**
     * 把"回复耗时 + 回复信息量 + 切屏/Live 状态 + 沉默时长"压成四个状态之一。纯函数。
     * @param liveActive Live 是否还开着；null = 不知道（信号没接上），不算逃逸
     * @param lastReply 最近一次还没被判过的回复
     */
    fun classify(
        now: Long,
        inAnchor: Boolean,
        liveActive: Boolean?,
        lastSignalAt: Long,
        lastReply: Reply?,
        recent: List<Reply>,
        cfg: Config = Config(),
    ): CogState {
        if (!inAnchor) return CogState(Cog.ESCAPED, "left_anchor_app")
        if (liveActive == false) return CogState(Cog.ESCAPED, "live_ended")
        if (lastReply != null) {
            val f = lastReply.features
            if (f.giveUp) return CogState(Cog.THROTTLED_EMPTY, "gave_up")
            if (f.filler) return CogState(Cog.THROTTLED_EMPTY, "filler")
            // 选择题式的原子动作，点了就算完成，不拿速度判它
            if (lastReply.expects == "choice" && f.choice != null) return CogState(Cog.ACTIVE_FLOW, "atomic_done")
            if (f.evidence || f.anchorHit) return CogState(Cog.ACTIVE_FLOW, if (f.anchorHit) "anchor_hit" else "has_basis")
            if (lastReply.latencyMs <= cfg.fastMs) return CogState(Cog.HIGH_SPEED_SKIMMING, "fast_no_basis")
            if (lastReply.latencyMs >= cfg.slowMs && f.score < 0.4) return CogState(Cog.THROTTLED_EMPTY, "slow_thin")
            // 不快不慢、也没依据：这句本身很短，且最近三次里两次没依据，就算在滑
            val thin = recent.count { !it.features.evidence && !it.features.anchorHit && it.features.choice == null }
            if (f.chars < 6 && thin >= 2) return CogState(Cog.HIGH_SPEED_SKIMMING, "thin_trend")
            return CogState(Cog.ACTIVE_FLOW, "plain_reply")
        }
        if (now - lastSignalAt >= cfg.silenceMs) return CogState(Cog.THROTTLED_EMPTY, "silence")
        return CogState(Cog.ACTIVE_FLOW, "talking")
    }
}
