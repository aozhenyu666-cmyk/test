package cn.companion.pet.core.training

/**
 * 无障碍执行路径的周期挑战（A13 / A35）。纯逻辑，时间一律用发起端的单调时钟。
 *
 * 一次挑战走的是**拦截真正走的那条路**：投递到无障碍服务主线程 Handler（与前台确认同一队列）
 * → 在那里读一次活动窗口（与前台确认同一次跨进程调用）→ 回执经同一回调交回桌宠。
 * 入口/旁路线程直接回显的回执会被 [HealthChallengeJudge] 拒绝。
 *
 * 只证明“执行路径近期能响应”，**不证明**某次拦截一定生效。
 */
class ExecutionPathMonitor(
    private val judge: HealthChallengeJudge = HealthChallengeJudge(),
    /** 有约束在跑时多久挑战一次。 */
    private val activeIntervalMillis: Long = 60_000,
    /** 没有约束时多久挑战一次（只为诊断页有新鲜数据）。 */
    private val idleIntervalMillis: Long = 5 * 60_000,
) {
    data class Snapshot(
        val state: HealthState,
        val detail: String,
        val lastLatencyMillis: Long = -1,
        val ageMillis: Long = -1,
        val consecutiveFailures: Int = 0,
    )

    private var outstanding: HealthChallenge? = null
    private var lastVerdict: ChallengeVerdict? = null
    private var lastJudgedAt: Long = Long.MIN_VALUE
    private var lastIssuedAt: Long = Long.MIN_VALUE
    private var failures = 0
    private var lastUnavailable = false
    private var seq = 0

    val pending: HealthChallenge? get() = outstanding

    /** 该不该发下一次挑战（已有未决挑战时不发，避免堆积）。 */
    fun due(now: Long, enforcing: Boolean): Boolean {
        if (outstanding != null) return false
        if (lastIssuedAt == Long.MIN_VALUE) return true
        return now - lastIssuedAt >= if (enforcing) activeIntervalMillis else idleIntervalMillis
    }

    fun issue(now: Long, nonce: String): HealthChallenge {
        seq++
        val c = judge.issue("hc-" + now + "-" + seq, nonce, "accessibility.foreground_confirm", now)
        outstanding = c
        lastIssuedAt = now
        return c
    }

    /** 服务根本没连上：这次挑战发不出去。 */
    fun unavailable(now: Long) {
        outstanding = null
        lastIssuedAt = now
        lastUnavailable = true
        lastVerdict = null
        lastJudgedAt = now
        failures++
    }

    /**
     * 收到回执。没有未决挑战或 ID 对不上的（迟到、重放、伪造）直接丢弃，返回 null。
     */
    fun onReceipt(receipt: HealthReceipt, now: Long): ChallengeVerdict? {
        val c = outstanding ?: return null
        if (receipt.challengeId != c.challengeId) return null
        val v = judge.judge(c, receipt, now)
        settle(v, now)
        return v
    }

    /** 每次 tick 调用：未决挑战过了截止就判超时；之后到达的回执会因为没有未决挑战被丢弃。 */
    fun poll(now: Long): ChallengeVerdict? {
        val c = outstanding ?: return null
        if (now <= c.deadline) return null
        val v = judge.judge(c, null, now)
        settle(v, now)
        return v
    }

    private fun settle(v: ChallengeVerdict, now: Long) {
        outstanding = null
        lastVerdict = v
        lastJudgedAt = now
        lastUnavailable = false
        failures = if (v is ChallengeVerdict.Healthy) 0 else failures + 1
    }

    /** 结论只在新鲜期内有效；过期即 UNKNOWN，不拿旧的“健康”撑场面。 */
    fun snapshot(now: Long, enforcing: Boolean): Snapshot {
        if (lastJudgedAt == Long.MIN_VALUE) return Snapshot(HealthState.UNKNOWN, "还没做过执行路径挑战")
        val age = now - lastJudgedAt
        val freshFor = 3 * if (enforcing) activeIntervalMillis else idleIntervalMillis
        if (lastUnavailable) return Snapshot(HealthState.UNAVAILABLE, "无障碍服务没连上，拦截无法执行", ageMillis = age, consecutiveFailures = failures)
        return when (val v = lastVerdict) {
            is ChallengeVerdict.Healthy ->
                if (age > freshFor) Snapshot(HealthState.UNKNOWN, "上次挑战已过期，结果不再可信", v.latencyMillis, age, failures)
                else Snapshot(HealthState.OK, "执行路径 " + v.latencyMillis + " ms 内有响应（不代表每次拦截都生效）", v.latencyMillis, age, failures)
            is ChallengeVerdict.TimedOut ->
                Snapshot(HealthState.DEGRADED, "服务连着，但执行队列 " + v.waitedMillis + " ms 没响应，拦截不可靠", -1, age, failures)
            is ChallengeVerdict.Rejected ->
                Snapshot(HealthState.DEGRADED, "执行路径回执无效：" + v.reason, -1, age, failures)
            null -> Snapshot(HealthState.UNKNOWN, "还没做过执行路径挑战")
        }
    }

    fun healthy(now: Long, enforcing: Boolean): Boolean = snapshot(now, enforcing).state == HealthState.OK
}
