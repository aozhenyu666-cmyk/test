package com.yishou.app.initiative

/**
 * 一手的主动性：什么时候该主动来叫你「走一手」。
 *
 * 为什么不是简单的「每小时响一次」：
 *  用户要的是**被推着动**，不是被闹钟追着跑。一手已经有的三个提醒（每日打卡、停太久、窗口开场）
 *  都是「到点」逻辑，缺的是「合不合适」：刚走完一手、刚自己关掉窗口、正在窗口里、已经达标——
 *  这些时候再叫，只会让人烦，然后把通知关掉，反而更推不动。
 *
 * 所以判断分三层：
 *  1. **硬门**：关掉、夜里、窗口进行中、刚自己关了窗口、刚走完一手 → 一律不叫。
 *  2. **节奏**：今天一手都没走 → 按 [InitiativeConfig.gapMinutes] 催；走了但没达标 → 间隔翻倍，
 *     不打断节奏；已达标 → 只留一次收尾提醒。
 *  3. **退避**：叫了没反应（他连应用都没打开）就 1.5 倍退避，忍到 [InitiativeConfig.patience] 次当天收手，
 *     把「叫醒」交给窗口和每日打卡兜底，而不是制造噪音。
 *
 * 纯函数，不碰 Android，可以单测。
 */
data class InitiativeConfig(
    /** 总开关。 */
    val enabled: Boolean = true,
    /** 今天还没走一手时，两次主动之间的基础间隔（分钟）。 */
    val gapMinutes: Int = 60,
    /** 任何情况下都不比这更频繁。 */
    val floorMinutes: Int = 30,
    /** 只在白天叫。 */
    val dayStartHour: Int = 8,
    val dayEndHour: Int = 22,
    /** 连续几次没回应就当天收手。 */
    val patience: Int = 3,
)

/** 一手当下知道的全部情况。 */
data class Signal(
    val now: Long,
    val hour: Int,
    val minute: Int,
    /** 今天走了几手。 */
    val roundsToday: Int = 0,
    /** 今天的目标组数×每手，用来判断有没有达标。 */
    val goal: Int = 6,
    /** 最近一手的时间；0 = 今天还没走。 */
    val lastRoundAt: Long = 0,
    /** 上一次主动叫他的时间。 */
    val lastPushAt: Long = 0,
    val pushCountToday: Int = 0,
    /** 连续几次叫了没反应。 */
    val ignoredStreak: Int = 0,
    /** 正在陪练窗口里（窗口有自己的一套提醒）。 */
    val windowActive: Boolean = false,
    /** 最近一次他自己关掉窗口的时间。 */
    val windowStoppedAt: Long = 0,
    /** 断点里还卡着的那句话。 */
    val stuck: String = "",
)

data class Push(
    val title: String,
    val text: String,
    /** 为什么是现在（写日志用）。 */
    val reason: String,
    val firm: Boolean = false,
)

object Initiative {
    /** 提醒的状态流转独立于 Android，重复提醒和跨日恢复也可以单测。 */
    internal data class State(
        val date: String,
        val lastAt: Long = 0,
        val count: Int = 0,
        val streak: Int = 0,
        val pendingAt: Long = 0,
    ) {
        /** 忍到上限只在当天停手，下一天重新开始。 */
        fun onDay(today: String): State = if (date == today) this else State(today)

        /** 重发不能推迟最早一条尚未结算的提醒。 */
        fun pushed(now: Long): State = copy(
            lastAt = now,
            count = count + 1,
            pendingAt = if (pendingAt > 0) pendingAt else now,
        )

        fun settle(lastResponseAt: Long, now: Long, ackWindowMs: Long): State {
            if (pendingAt <= 0) return this
            return when {
                lastResponseAt > pendingAt -> copy(streak = 0, pendingAt = 0)
                now - pendingAt > ackWindowMs -> copy(streak = streak + 1, pendingAt = 0)
                else -> this
            }
        }
    }

    /** 硬下限：两次主动之间至少 15 分钟。 */
    const val MIN_GAP_MIN = 15
    /** 自己关掉窗口后，这么久之内不追。 */
    const val AFTER_STOP_MIN = 45

    /** 当前的实际间隔（分钟）。 */
    fun intervalMin(cfg: InitiativeConfig, sig: Signal): Int {
        if (!cfg.enabled || cfg.gapMinutes <= 0) return 0
        val floor = maxOf(cfg.floorMinutes, MIN_GAP_MIN)
        val met = sig.roundsToday >= sig.goal && sig.goal > 0
        val base = when {
            met -> cfg.gapMinutes * 3          // 达标了：只留一次收尾，不催进度
            sig.roundsToday == 0 -> cfg.gapMinutes
            else -> cfg.gapMinutes * 2         // 已经在走，别打断节奏
        }
        // 被忽略：1.5 倍逐次退避，最多 4 倍
        val backoff = Math.pow(1.5, sig.ignoredStreak.toDouble()).coerceAtMost(4.0)
        return (base * backoff).toInt().coerceAtLeast(floor)
    }

    fun decide(cfg: InitiativeConfig, sig: Signal): Push? {
        if (!cfg.enabled) return null
        if (sig.hour !in cfg.dayStartHour until cfg.dayEndHour) return null
        if (sig.windowActive) return null
        if (sig.windowStoppedAt > 0 && sig.now - sig.windowStoppedAt < AFTER_STOP_MIN * 60_000L) return null
        if (sig.ignoredStreak >= cfg.patience) return null
        val gap = sig.now - sig.lastPushAt
        if (sig.lastPushAt > 0 && gap < MIN_GAP_MIN * 60_000L) return null
        // 刚走完一手：让他喘口气，半小时内不叫
        if (sig.lastRoundAt > 0 && sig.now - sig.lastRoundAt < MIN_GAP_MIN * 60_000L) return null
        val met = sig.roundsToday >= sig.goal && sig.goal > 0
        // 达标后一天只再提一次（收尾）
        if (met && sig.pushCountToday >= 1) return null
        val iv = intervalMin(cfg, sig)
        if (iv <= 0) return null
        if (sig.lastPushAt > 0 && gap < iv * 60_000L) return null

        val firm = sig.ignoredStreak > 0
        val left = (sig.goal - sig.roundsToday).coerceAtLeast(0)
        val title = when {
            met -> "今天已经够了"
            sig.roundsToday == 0 -> "今天还没应一手"
            else -> "还差 $left 手"
        }
        val text = buildString {
            if (sig.stuck.isNotBlank()) append("上次卡在：${sig.stuck}\n")
            append(
                when {
                    met -> "今天的目标够了。要不要走最后一手，把断点收干净？"
                    sig.roundsToday == 0 -> "现在走一手，五分钟就够了。点开就是上一手的断点。"
                    else -> "今天的 ${sig.goal} 手里走了 ${sig.roundsToday} 手。再来一手，别攒到晚上。"
                },
            )
        }
        val reason = "间隔 ${iv} 分钟 · 今天 ${sig.roundsToday} 手 · " +
            "主动程度${if (firm) " · 第 ${sig.ignoredStreak + 1} 次" else ""}"
        return Push(title, text, reason, firm)
    }

    fun nextAlarmAt(cfg: InitiativeConfig, sig: Signal): Long? {
        if (!cfg.enabled) return null
        if (sig.ignoredStreak >= cfg.patience) return null
        val iv = intervalMin(cfg, sig)
        if (iv <= 0) return null
        val base = if (sig.lastPushAt > 0) sig.lastPushAt + iv * 60_000L else sig.now
        return maxOf(base, sig.now + 60_000L)
    }
}
