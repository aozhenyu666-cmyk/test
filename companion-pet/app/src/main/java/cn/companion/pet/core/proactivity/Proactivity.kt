package cn.companion.pet.core.proactivity

/**
 * 自动观察策略：默认关闭，只对白名单 App、亮屏、间隔和每日次数内允许。
 * 事件去重/退避复用主线 AgentEventLedger（AgentEventRetry/retryAt），这里不另造第二套状态机。
 */
data class ObservationDecision(val allowed: Boolean, val reason: String)

class ObservationPolicy(
    private val minIntervalMillis: Long = 5 * 60_000,
    private val dailyCap: Int = 20,
) {
    fun canObserve(
        enabled: Boolean,
        screenOn: Boolean,
        foreground: String?,
        allowedPackages: Set<String>,
        lastObservedAt: Long,
        todayCount: Int,
        now: Long,
    ): ObservationDecision = when {
        !enabled -> ObservationDecision(false, "自动观察未开启")
        !screenOn -> ObservationDecision(false, "熄屏")
        foreground.isNullOrBlank() -> ObservationDecision(false, "没有前台应用")
        foreground !in allowedPackages -> ObservationDecision(false, "不在观察白名单")
        todayCount >= dailyCap -> ObservationDecision(false, "今日观察次数已用完")
        lastObservedAt > 0 && now - lastObservedAt < minIntervalMillis -> ObservationDecision(false, "观察间隔未到")
        else -> ObservationDecision(true, "允许观察")
    }
}
