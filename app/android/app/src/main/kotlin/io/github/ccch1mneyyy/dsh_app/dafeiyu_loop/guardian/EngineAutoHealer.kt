package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian

/**
 * 引擎防掉线分层退避自愈策略（纯 JVM，不依赖 Android Context）。
 *
 * 与上游 `SafePolicy.autoRestartDelay` 的关键区别：
 * 上游在 10 分钟内重启满 3 次就直接封死进 `Phase.ERROR` 并停止服务，导致 Android 12+
 * 幻象进程清杀（Phantom Process Killer）或厂商杀后台后引擎彻底躺平。
 * 这里把「启动期 60 秒内连续崩盘」与「稳定运行后被系统清杀」彻底拆开：
 * - 运行超过 60 秒才被清杀的，永远允许按指数退避重启（5s -> 15s -> 45s -> 120s -> 300s），不锁死 ERROR；
 * - 60 秒内连续启动失败才走退避并在超过上限后交回安全模式/错误卡片处理。
 */
object EngineAutoHealer {
    private const val HEALTHY_UPTIME_MS = 60_000L
    private const val WINDOW_MS = 30 * 60_000L
    private const val MAX_EARLY_FAILURES = 4
    private val BACKOFF_STEPS_MS = longArrayOf(5_000L, 15_000L, 45_000L, 120_000L, 300_000L)

    /**
     * 计算下一次自愈重启延迟（毫秒）；返回 null 表示应交由安全模式/报错流程。
     * @param exitCode 退出码
     * @param upMs 本次存活时长（未到 ready 为 null）
     * @param recentRestartElapsed 最近重启动作的 elapsedRealtime 时间戳列表
     * @param nowElapsed 当前 elapsedRealtime
     */
    fun nextRestartDelayMs(
        exitCode: Int,
        upMs: Long?,
        recentRestartElapsed: List<Long>,
        nowElapsed: Long,
    ): Long? {
        val recent = recentRestartElapsed.filter { nowElapsed - it in 0..WINDOW_MS }
        val wasHealthy = upMs != null && upMs >= HEALTHY_UPTIME_MS
        if (!wasHealthy && recent.size >= MAX_EARLY_FAILURES) {
            return null
        }
        // 运行满 60 秒后被杀（常见 9/137/143 或 OOM/幻象清杀），按退避阶梯无限重试。
        val stepIndex = recent.size.coerceAtMost(BACKOFF_STEPS_MS.lastIndex)
        // exitCode 137/143/9 属于常见外部强杀，首跳用最低延迟 5s 快速拉回。
        val base = BACKOFF_STEPS_MS[stepIndex]
        return if (wasHealthy && recent.isEmpty() && (exitCode == 137 || exitCode == 143 || exitCode == 9)) 5_000L else base
    }
}
