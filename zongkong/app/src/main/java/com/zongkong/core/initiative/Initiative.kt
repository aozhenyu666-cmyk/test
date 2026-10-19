package com.zongkong.core.initiative

import kotlinx.serialization.Serializable

/**
 * 主动性引擎：决定「现在要不要主动找用户、找哪一件、用什么力度」。
 *
 * 这一层是纯函数，不碰 Android、不碰存储，所以可以单测：
 * 输入一份当下的状况（[Signal]）和一份设置（[InitiativeConfig]），输出一个 [Push] 或 null。
 *
 * 设计原则（为什么不是“每 10 分钟弹一次”）：
 *  1. 主动性不是频率，是**时机**。正在专注、正在总控时间、刚说完话、睡觉时段，都不该打断。
 *  2. 催不动就退避。连着几次没回应还按固定间隔弹，只会把通知变成噪音，用户会直接关掉通知权限——
 *     那是比“不主动”更坏的结果。所以被忽略会指数退避，忍到上限就当天收手，交给失联阶梯（更硬的一级）去处理。
 *  3. 收集箱是“欠账”：空着就少催，压到 3 条以上就多催——催的是“该还账了”，不是“打卡”。
 *  4. 一切频率都有硬下限（[MIN_GAP_MIN] 分钟）和每日上限，用户随时能关。
 */
@Serializable
data class InitiativeConfig(
    /** 总开关。关掉后总控只保留到点提醒和失联阶梯，不再主动拉收集。 */
    val enabled: Boolean = true,
    /**
     * 「标准」主动程度下，信息收集部两次主动拉取的间隔（分钟）。
     * 黏人档会缩短到 2/3，战友档缩到 1/2；任何档位都不会低于 [captureFloorMin]。0 = 关掉这一路。
     */
    val captureEveryMin: Int = 20,
    /** 间隔的硬下限。10 分钟是“人还能忍受”的极限，再密就成骚扰了。 */
    val captureFloorMin: Int = 10,
    /** 每天最多主动拉几次（不含用户自己点「收一条」）。 */
    val capturePerDay: Int = 6,
    /** 连续被忽略几次就当天收手。 */
    val capturePatience: Int = 3,
)

/** 当下的状况。全部由调用方（[com.zongkong.app.system.InitiativeRunner]）从 Store 里取，保持这一层纯函数。 */
data class Signal(
    val now: Long,
    /** 当地时间的分钟数（0..1439）。 */
    val minute: Int,
    /** 一天的活动时段（分钟数）。 */
    val activeStart: Int = 8 * 60,
    val activeEnd: Int = 23 * 60,
    /** 睡觉时段（Hq.quiet 算出来的）。 */
    val quiet: Boolean = false,
    /** 总控休假中。 */
    val paused: Boolean = false,
    /** 正在总控时间里（里面有“收”这一环，不另外拉）。 */
    val hqActive: Boolean = false,
    /** 有一件行动正在专注计时。 */
    val focusing: Boolean = false,
    /** 用户最后一次真正交流的时刻。 */
    val lastContactAt: Long = 0,
    /** 这一路（收集）上一次主动找的时刻；0 = 今天还没找过。 */
    val lastPushAt: Long = 0,
    /** 这一路今天已经主动找过几次。 */
    val pushCountToday: Int = 0,
    /** 这一路连续几次没得到回应（用户在你发完之后没说过话）。 */
    val ignoredStreak: Int = 0,
    /** 收集箱积压条数。 */
    val backlog: Int = 0,
    /** 主动程度：0 安静 / 1 标准 / 2 黏人 / 3 战友。 */
    val intensity: Int = 1,
)

/** 主动发起的一路。以后要加“谋划/统筹”的主动发起，在这里加一个枚举值即可。 */
enum class Kind(val key: String, val title: String) {
    CAPTURE("capture", "收一条"),
}

/** 一次主动发起。 */
data class Push(
    val kind: Kind,
    val title: String,
    val text: String,
    /** 为什么是现在（写进叫醒记录，方便回头查“它怎么又弹了”）。 */
    val reason: String,
    /** 催过了还没回应：语气更硬一点。 */
    val firm: Boolean = false,
)

object Initiative {
    /** 执行层持久化的计数；状态流转同样保持纯函数，方便验证连续提醒和跨日恢复。 */
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

    /** 两次主动之间至少隔这么久，任何设置都突破不了。 */
    const val MIN_GAP_MIN = 10

    /** 刚说完话、刚主动过，都要等这么久才允许下一次（避免抢话、连着响）。 */
    const val COOLDOWN_MIN = 10

    /**
     * 当前这一路的实际间隔（分钟）。0 = 这一路关着。
     * 顺序：主动程度定基础间隔 → 收集箱欠账缩放 → 被忽略退避 → 夹到 [硬下限, 基础间隔 × 4]。
     */
    fun intervalMin(cfg: InitiativeConfig, sig: Signal): Int {
        if (!cfg.enabled || cfg.captureEveryMin <= 0) return 0
        val floor = maxOf(cfg.captureFloorMin, MIN_GAP_MIN)
        val base = when (sig.intensity) {
            0 -> 0
            1 -> cfg.captureEveryMin
            2 -> cfg.captureEveryMin * 2 / 3
            else -> cfg.captureEveryMin / 2
        }
        if (base <= 0) return 0
        // 收集箱是欠账：空着少催，积压多催。催的是“该还账了”，不是“打卡”。
        val pressure = when {
            sig.backlog >= 3 -> 0.7
            sig.backlog == 0 -> 1.5
            else -> 1.0
        }
        // 被忽略：1.5 倍逐次退避，最多 4 倍。催不动就别催了。
        val backoff = Math.pow(1.5, sig.ignoredStreak.toDouble()).coerceAtMost(4.0)
        return (base * pressure * backoff).toInt().coerceAtLeast(floor)
    }

    /** 现在该不该主动找用户；不该就返回 null。 */
    fun decide(cfg: InitiativeConfig, sig: Signal): Push? {
        if (!cfg.enabled) return null
        if (sig.paused) return null
        if (sig.quiet) return null
        if (sig.hqActive) return null
        if (sig.intensity <= 0) return null
        if (sig.minute !in sig.activeStart until sig.activeEnd) return null
        if (sig.pushCountToday >= cfg.capturePerDay) return null
        if (sig.ignoredStreak >= cfg.capturePatience) return null
        val gap = sig.now - sig.lastPushAt
        if (sig.lastPushAt > 0 && gap < COOLDOWN_MIN * 60_000L) return null
        // 人刚说完话，别抢话
        if (sig.now - sig.lastContactAt < COOLDOWN_MIN * 60_000L) return null
        // 正在专注：除非收集箱已经压到 3 条，否则不打断
        if (sig.focusing && sig.backlog < 3) return null
        val iv = intervalMin(cfg, sig)
        if (iv <= 0) return null
        if (sig.lastPushAt > 0 && gap < iv * 60_000L) return null

        val firm = sig.ignoredStreak > 0
        val text = when {
            sig.backlog >= 3 -> "收集箱压着 ${sig.backlog} 条了。别攒：现在补一条，一句话就行。"
            sig.backlog > 0 -> "空两分钟，把刚才想到的东西记下来（还压着 ${sig.backlog} 条）。"
            else -> "现在在做什么？一句话扔进收集箱，别让念头飘走。"
        }
        val reason = "间隔 ${iv} 分钟 · ${if (sig.backlog > 0) "欠 ${sig.backlog} 条" else "收集箱空"} · " +
            "主动程度 ${sig.intensity}${if (sig.ignoredStreak > 0) " · 第 ${sig.ignoredStreak + 1} 次" else ""}"
        return Push(Kind.CAPTURE, "收一条", text, reason, firm)
    }

    /**
     * 下一次可能主动的时刻（给系统闹钟兜底用）。返回 null 表示今天不会再拉。
     * 不追求精确：闹钟只负责把进程叫醒，真正的判断还在 [decide]。
     */
    fun nextAlarmAt(cfg: InitiativeConfig, sig: Signal): Long? {
        if (!cfg.enabled || sig.intensity <= 0) return null
        if (sig.pushCountToday >= cfg.capturePerDay) return null
        if (sig.ignoredStreak >= cfg.capturePatience) return null
        val iv = intervalMin(cfg, sig)
        if (iv <= 0) return null
        val base = if (sig.lastPushAt > 0) sig.lastPushAt + iv * 60_000L else sig.now
        // 现在的时刻对应的“活动开始时间”；已经过了活动时段就推到明天
        val todayStart = sig.now - (sig.minute - sig.activeStart) * 60_000L
        val windowStart = if (sig.minute >= sig.activeEnd) todayStart + 86_400_000L else todayStart
        return maxOf(base, windowStart, sig.now + 60_000L)
    }
}
