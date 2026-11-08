package io.github.ccch1mneyyy.dsh_app.loop.focus

/**
 * 专注监督的状态与判定，纯 Kotlin、无 Android 依赖，方便在 JVM 上直接测。
 *
 * 和「严格模式」插件的区别：这里不隔 15 分钟发一次提醒，而是**看前台**。
 * 一旦离开任务应用超过 [GRACE_MS]，立刻弹全屏拉回页；页面上的动作只有
 * 回到任务 / 休息 5 分钟 / 结束监督，不需要答题，也不经过模型。
 */
data class FocusState(
    val active: Boolean = false,
    val task: String = "",
    /** 任务应用包名；空表示没有指定，只允许 DSH / 电话 / 输入法。 */
    val taskPackage: String = "",
    val allowed: Set<String> = emptySet(),
    val startedAt: Long = 0L,
    /** 到点自动结束；0 = 不设结束时间。 */
    val endAt: Long = 0L,
    val pausedUntil: Long = 0L,
    val deviations: Int = 0,
    val lastBlockAt: Long = 0L,
    val lastBlockPkg: String = "",
    val stoppedReason: String = "",
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "active" to active,
        "task" to task,
        "taskPackage" to taskPackage,
        "allowed" to allowed.toList(),
        "startedAt" to startedAt,
        "endAt" to endAt,
        "pausedUntil" to pausedUntil,
        "deviations" to deviations,
        "lastBlockAt" to lastBlockAt,
        "lastBlockPkg" to lastBlockPkg,
        "stoppedReason" to stoppedReason,
    )

    companion object {
        fun fromMap(raw: Map<*, *>?): FocusState {
            if (raw == null) return FocusState()
            fun str(key: String) = (raw[key] as? String).orEmpty()
            fun num(key: String) = (raw[key] as? Number)?.toLong() ?: 0L
            val allowed = when (val v = raw["allowed"]) {
                is List<*> -> v.mapNotNull { it as? String }.filter { it.isNotBlank() }.toSet()
                is Set<*> -> v.mapNotNull { it as? String }.filter { it.isNotBlank() }.toSet()
                else -> emptySet()
            }
            return FocusState(
                active = raw["active"] as? Boolean ?: false,
                task = str("task"),
                taskPackage = str("taskPackage"),
                allowed = allowed,
                startedAt = num("startedAt"),
                endAt = num("endAt"),
                pausedUntil = num("pausedUntil"),
                deviations = (raw["deviations"] as? Number)?.toInt() ?: 0,
                lastBlockAt = num("lastBlockAt"),
                lastBlockPkg = str("lastBlockPkg"),
                stoppedReason = str("stoppedReason"),
            )
        }
    }
}

sealed interface FocusDecision {
    data object None : FocusDecision
    data class Block(val packageName: String, val secondsOff: Long) : FocusDecision
}

object FocusPolicy {
    /** 切过去后要停留这么久才算「离开」：避开应用启动、返回桌面时的瞬时窗口。 */
    const val GRACE_MS = 5_000L

    /** 同一个应用一分钟内只拉一次，避免页面关掉又立刻弹。 */
    const val REBLOCK_MS = 60_000L

    /** 电话、系统 UI、输入法永远放行：不能在接电话时把页面盖上去。 */
    val ALWAYS_ALLOWED = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.android.systemui",
    )

    fun isExpired(state: FocusState, now: Long): Boolean =
        state.active && state.endAt > 0 && now >= state.endAt

    fun isPaused(state: FocusState, now: Long): Boolean =
        state.active && state.pausedUntil > now

    /**
     * [deviationSince] 是运行层记下的「第一次看到这个应用」的时间；传 0 表示还没记。
     * 返回 Block 时，调用方负责计数、震动、弹页。
     */
    fun evaluate(
        state: FocusState,
        packageName: String?,
        now: Long,
        deviationSince: Long,
        ownPackage: String,
        imePackages: Set<String>,
    ): FocusDecision {
        if (!state.active) return FocusDecision.None
        if (isExpired(state, now) || isPaused(state, now)) return FocusDecision.None
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return FocusDecision.None
        if (pkg == ownPackage) return FocusDecision.None
        if (pkg in ALWAYS_ALLOWED) return FocusDecision.None
        if (pkg in imePackages) return FocusDecision.None
        if (pkg in state.allowed) return FocusDecision.None
        if (deviationSince <= 0L || now - deviationSince < GRACE_MS) return FocusDecision.None
        // 页面已经盖上去过一次：给一分钟静默，别关掉就立刻再弹。
        if (state.lastBlockPkg == pkg && state.lastBlockAt > 0L && now - state.lastBlockAt < REBLOCK_MS) {
            return FocusDecision.None
        }
        return FocusDecision.Block(pkg, (now - deviationSince) / 1000L)
    }
}
