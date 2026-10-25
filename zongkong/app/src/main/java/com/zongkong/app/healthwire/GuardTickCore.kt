package com.zongkong.app.healthwire

import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.LockReceipt
import com.zongkong.app.health.LockRequest
import com.zongkong.app.health.LockState

/*
 * 巡检核心（t14）。
 *
 * 为什么单独抽出来：t13 的回归测试直接调 `LocalFallback.decide(...)` 并**自己把宽限当参数传进去**，
 * 于是完全绕过了 `LocalGuardian.tickNow()` 里那一行真实接线 —— 把 bug 注入回去，测试也不会红
 * （captain 的注入实验：286 用例仍全绿）。这是「判据正确 ≠ 接线正确」的典型陷阱。
 *
 * 这里的做法：把 `tickNow()` 的**全部逻辑本体**搬进 [GuardTickCore.tick]，外部依赖全部走 [GuardTickPorts]。
 *   - 生产：`LocalGuardian.tickNow()` 用 Android 实现填 ports，再调 `core.tick(...)`；
 *   - 测试：用假 ports 直接驱动**同一个** `core.tick(...)`。
 * 所以「宽限有没有被传下去」这件事，测试和生产走的是**同一行代码**，注入 bug 必然红。
 */

/**
 * 一次 tick 的全部外部依赖。
 *
 * 每个 lambda 都对应 tickNow 里原本的一次调用，没有测试专用分支。
 */
class GuardTickPorts(
    /** 单调时钟（生产 `SystemClock.elapsedRealtime()`；测试可手动推进）。 */
    val mono: () -> Long,
    /** 本地规则。 */
    val policies: () -> List<LocalPolicy>,
    /** 用户是否按了紧急暂停。 */
    val emergencyStopped: () -> Boolean,
    /** 自己是否有真实 dispatcherAck 且新鲜。 */
    val selfHealthy: () -> Boolean,
    /** 后台触发一次哨兵探测（不阻塞）。 */
    val probeSentinel: () -> Unit,
    /** 哨兵是否在岗（只读缓存）。 */
    val sentinelHealthy: () -> Boolean,
    /** Keyguard 当前是否锁着；null = 读不到。 */
    val keyguardLocked: () -> Boolean?,
    /** 真正执行锁屏。 */
    val lock: (LockRequest, Long) -> LockReceipt,
    /** 上报回执给哨兵。 */
    val reportReceipt: (LockReceipt) -> Boolean,
    /** 没观测到锁上时的提醒。 */
    val notifyUnverified: (LockReceipt) -> Unit,
    /** 诊断记录。 */
    val diag: (String, String) -> Unit,
)

/** 一次 tick 的结论。 */
sealed interface TickOutcome {
    /** 被节流（距上次不足 throttleMs）。 */
    data object Throttled : TickOutcome

    /** 没有本地规则。 */
    data object NoPolicies : TickOutcome

    /** 紧急暂停生效中。 */
    data object EmergencyStopped : TickOutcome

    /** 跑了判定但决定不锁（理由在 core.lastDecision）。 */
    data object NotFired : TickOutcome

    /** 执行了锁屏（回执在 core.lastReceipt）。 */
    data class Fired(val receipt: LockReceipt) : TickOutcome
}

/**
 * 巡检核心。**这是 `LocalGuardian.tickNow()` 唯一调用的那份逻辑**，不是测试副本。
 *
 * @param fallback 兜底判定器（含 LockArbiter）。测试可注入更宽松的仲裁器，把「是否被宽限拦住」
 *   单独隔离出来 —— 否则仲裁的次数上限会把注入的 bug 掩盖掉。
 */
class GuardTickCore(
    private val fallback: LocalFallback = LocalFallback(source = "zongkong"),
    val grace: RepairGrace = RepairGrace(HealthConfig().repairGraceMs),
    private val throttleMs: Long = 15_000L,
    private val incidentKey: String = "sentinel-unreachable",
    private val fallbackGraceMs: Long = HealthConfig().repairGraceMs,
) {
    /** 最近一次判定说明（生产里就是 `LocalGuardian.lastDecision`）。 */
    @Volatile
    var lastDecision: String = ""
        private set

    /** 最近一次回执（生产里就是 `LocalGuardian.lastReceipt`）。 */
    @Volatile
    var lastReceipt: LockReceipt? = null
        private set

    /** 累计真正执行过几次锁屏（测试用它断言「没有新回执」）。 */
    @Volatile
    var lockCount: Int = 0
        private set

    private var lastTickMono = 0L

    /**
     * 本 core 自己的时钟推进记录（只用于节流）。
     *
     * 注意：`fallback` 与 `grace` 都是**构造时注入的同一份对象**，
     * 所以「宽限有没有被传下去」这件事，测试和生产走的是同一行代码。
     */
    fun lastTickMonoForTest(): Long = lastTickMono

    /** 修复宽限到期时刻（单调）。 */
    val repairGraceUntilMono: Long get() = grace.untilMono

    fun tick(ports: GuardTickPorts, nowWall: Long): TickOutcome {
        val nowMono = ports.mono()
        // 第一次 tick 不节流：进程刚起来 / Core 刚建好时必须立刻判一次。
        // （以前用 `nowMono - 0 < 15_000` 判断，在单调时钟刚开机不久时会误把第一次也节流掉。）
        if (lastTickMono > 0L && nowMono - lastTickMono < throttleMs) return TickOutcome.Throttled
        lastTickMono = nowMono

        val policies = ports.policies()
        if (policies.isEmpty()) {
            lastDecision = "没有本地规则"
            return TickOutcome.NoPolicies
        }

        // 紧急暂停：本地立刻生效，不问网络/模型；暂停期间不保留宽限。
        if (ports.emergencyStopped()) {
            grace.clear()
            lastDecision = "紧急暂停生效中：不会自动锁屏"
            return TickOutcome.EmergencyStopped
        }

        // T15：先观测 Keyguard（真解锁信号），再让到期宽限清 0（不能变永久豁免）。
        ports.keyguardLocked()?.let { locked ->
            if (grace.observe(locked, nowMono)) {
                ports.diag("repair_grace", "观测到解锁：重新起算修复宽限")
            }
        }
        grace.expire(nowMono)

        // 只读缓存，不在这里跨进程等哨兵（T05）。
        ports.probeSentinel()

        val attempt = fallback.decide(
            policies = policies,
            nowWall = nowWall,
            nowMono = nowMono,
            sentinelHealthy = ports.sentinelHealthy(),
            selfHealthy = ports.selfHealthy(),
            emergencyStopped = false,
            incidentKey = incidentKey,
            // ⚠️ T15 的唯一接线点（t13 的 bug 就在这里：曾经恒用默认值 0）。
            // 注入实验请改这一行 —— 改了它 [RepairGraceWiringTest] 必须变红。
            repairGraceUntilMono = grace.untilMono,
        )
        lastDecision = attempt.reason
        val req = attempt.request ?: return TickOutcome.NotFired

        val receipt = ports.lock(req, nowWall)
        lockCount += 1
        fallback.record(req, receipt.state, nowMono)
        lastReceipt = receipt
        ports.diag("local_lock", "${receipt.state}：${receipt.note}（${receipt.reason}）")

        // 锁屏确实发生过之后，立刻起修复宽限（退化策略，见 armGraceAfterLock 注释）。
        val until = armGraceAfterLock(
            grace = grace,
            policies = policies,
            receiptState = receipt.state,
            nowWall = nowWall,
            nowMono = nowMono,
            fallbackGraceMs = fallbackGraceMs,
        )
        if (until > 0L) {
            ports.diag("repair_grace", "锁屏后开始修复宽限（至 mono=$until）：${grace.describe(nowMono)}")
        }

        // 回执上报哨兵（失败不改锁屏结论：本地已经执行并观测过了）。
        if (!ports.reportReceipt(receipt)) {
            ports.diag("receipt_unsent", "回执没送到哨兵：${receipt.incidentId}")
        }
        // 没观测到锁上：说清楚，不假装成功。
        if (receipt.state != LockState.OBSERVED_LOCKED) {
            ports.notifyUnverified(receipt)
        }
        return TickOutcome.Fired(receipt)
    }

    /** 单测 / 重置：清空节流与状态，**不清宽限**（宽限有自己的语义）。 */
    fun resetTickClock() {
        lastTickMono = 0L
    }

    /** 完全重置（含宽限与计数）。 */
    fun resetAll() {
        lastTickMono = 0L
        lastDecision = ""
        lastReceipt = null
        lockCount = 0
        grace.clear()
    }

    companion object {
        /**
         * T15：锁屏之后起修复宽限。**纯函数**，被 [tick] 真实调用（不是测试副本）。
         *
         * @return 新的宽限到期时刻（单调）；0 = 这次不起宽限。
         */
        fun armGraceAfterLock(
            grace: RepairGrace,
            policies: List<LocalPolicy>,
            receiptState: com.zongkong.app.health.LockState,
            nowWall: Long,
            nowMono: Long,
            fallbackGraceMs: Long = HealthConfig().repairGraceMs,
        ): Long {
            if (!RepairGrace.armAfterReceipt(receiptState)) return 0L
            // 与一手 / 同桌同口径：优先用生效规则里的 repairGraceMs，没有就用内核默认值。
            val policy = policies.filter { it.effective(nowWall) }.maxByOrNull { it.revision }
            val graceMs = policy?.repairGraceMs?.takeIf { it > 0L } ?: fallbackGraceMs
            return grace.onLockConfirmed(nowMono, graceMs)
        }
    }
}
