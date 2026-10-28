package com.yishou.app.healthwire

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.yishou.app.health.HealthAction
import com.yishou.app.health.HealthConfig
import com.yishou.app.health.HealthEvaluator
import com.yishou.app.health.HealthObservation
import com.yishou.app.health.HealthStatus
import com.yishou.app.health.LockArbiter
import com.yishou.app.health.LockReceipt
import com.yishou.app.health.LockState
import com.yishou.app.health.Mono
import com.yishou.app.health.Wall
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一手的本地执行协调者（规范 §2.2 第 2、3 条）。
 *
 * 它做三件事，全部在**自己的后台线程**上，不占用无障碍事件线程（T05 的教训）：
 *  1. 周期性给哨兵发 register / ping，并把「哨兵是否失联」写成事实；
 *  2. 用共享内核 [HealthEvaluator] 算一手自己的健康（依据是真实 dispatcherAck 和事件进度）；
 *  3. 哨兵失联 + 自己健康 + 有生效规则时，按本地规则做**有限**兜底锁屏（[LocalGuard] + [LockArbiter]）。
 *
 * 不做的事：
 *  - 不宣布「健康」以外的结论，不用布尔代替实测；
 *  - 不做无限重锁，不因一次失败就反复锁；
 *  - 不接管其他应用的规则（只能改自己的，规范 §5.3）。
 */
object YishouGuard {

    private const val TAG = "YishouGuard"

    private val mono = Mono { SystemClock.elapsedRealtime() }
    private val wall = Wall { System.currentTimeMillis() }

    /** 一手自己的健康配置：用的就是规范 §7 给的工程初值。 */
    val config = HealthConfig()

    @Volatile private var policyStore: PolicyStore? = null
    @Volatile private var arbiter: LockArbiter? = null
    @Volatile private var scheduler: ScheduledExecutorService? = null
    @Volatile private var appContext: Context? = null

    /** 自己最近的健康结论（界面与兜底决策都用它）。 */
    @Volatile private var verdict: HealthStatus = HealthStatus.UNKNOWN
    @Volatile private var verdictReason: String = "还没有真实响应"

    /** 修复宽限：最近一次「观察到锁上了又解锁」之后，一段时间内不重锁（T15）。 */
    @Volatile private var repairGraceUntilMono: Long = 0

    /** 上一次观测到「在锁屏」——用来判断「刚解锁」。 */
    @Volatile private var wasLocked: Boolean = false

    private val ticking = AtomicBoolean(false)

    fun install(context: Context) {
        val app = context.applicationContext
        if (appContext != null) return
        appContext = app
        val store = PolicyStore(app)
        policyStore = store
        arbiter = LockArbiter()
        // 规则版本进本地事实，健康 ack 里能带出去。
        Facts.onRuleRevision(store.load().revision)
        startLoop()
    }

    fun policies(context: Context): PolicyStore {
        install(context)
        return policyStore ?: PolicyStore(context.applicationContext)
    }

    fun arbitration(): LockArbiter {
        val a = arbiter ?: LockArbiter().also { arbiter = it }
        return a
    }

    fun resetArbitration() {
        arbiter = LockArbiter()
        repairGraceUntilMono = 0
        wasLocked = false
    }

    fun selfStatus(): HealthStatus = verdict

    fun selfReason(): String = verdictReason

    /** 由无障碍服务在连上时调用：注册到哨兵（带真实 instanceId）。 */
    fun onA11yConnected(context: Context, instanceId: String) {
        install(context)
        Facts.onConnected(instanceId)
        SentinelLink.register(context.applicationContext, instanceId)
        SentinelLink.ping(context.applicationContext)
    }

    fun onA11yDisconnected() {
        Facts.onDisconnected()
    }

    private fun startLoop() {
        if (scheduler != null) return
        val s = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "yishou-guard").apply { isDaemon = true }
        }
        scheduler = s
        // 探测 10s（HealthConfig.probePeriodMs）；用固定延迟，避免任务本身耗时把周期压满。
        s.scheduleWithFixedDelay({ tickSafely() }, 2_000L, config.probePeriodMs, TimeUnit.MILLISECONDS)
        // 紧急暂停与规则同步是另一条更慢的节奏。
        s.scheduleWithFixedDelay({ pingSafely() }, 5_000L, 30_000L, TimeUnit.MILLISECONDS)
    }

    private fun pingSafely() {
        val ctx = appContext ?: return
        try {
            SentinelLink.ping(ctx)
            // 每次 ping 顺手把「用户配置的窗口」翻译成本地规则（先持久化，再算就绪）。
            // 这样断网 / 哨兵失联时，本地规则依然是现成的（规范 §0 铁律 3）。
            val app = ctx as? com.yishou.app.YishouApp
            if (app != null) {
                val saved = RuleBuilder.materialize(
                    store = policies(ctx),
                    prefs = app.settings.app.value,
                    nowWall = wall.now(),
                    zone = java.time.ZoneId.systemDefault(),
                    failureAction = if (app.settings.app.value.windowStrict) "LOCK" else "NOTIFY_ONLY",
                )
                if (saved) SentinelLink.syncPolicy(ctx, policies(ctx).load())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ping 哨兵失败", e)
        }
    }

    private fun tickSafely() {
        if (!ticking.compareAndSet(false, true)) return
        try {
            val ctx = appContext ?: return
            tick(ctx)
        } catch (e: Throwable) {
            Log.w(TAG, "监管节拍出错", e)
        } finally {
            ticking.set(false)
        }
    }

    /** 一个节拍：算健康 → 条件满足就做有限兜底 → 上报回执。 */
    private fun tick(context: Context) {
        val nowMono = mono.now()
        val nowWall = wall.now()
        val facts = Facts.snapshot()
        val link = SentinelLink.state()
        val perms = LocalLockExecutor.perms(context, accessibilityOn = facts.connected)

        updateRepairGrace(context, nowMono)

        // 紧急暂停判定统一走 EmergencyStop.isPaused（三来源合一），与界面、单测同一处逻辑：
        //  a) 内存事实（本次进程内按下的暂停）
        //  b) 规则上的 STOPPED 标记（规范 §4 的 emergencyState 字段）
        //  c) 独立持久化的暂停标记文件（窗口翻页 / 进程重启都不会丢）
        val doc = policies(context).load()
        val emergencyStopped = EmergencyStop.isPaused(
            memoryPaused = Facts.paused,
            doc = doc,
            storeFlag = policies(context).emergencyStopped(),
        )

        // ---- 1. 一手自己的健康 ----
        val obs = HealthObservation(
            installed = true,
            permissionEnabled = perms.accessibilityOn,
            registered = link.reachable || link.registered,
            instanceId = facts.instanceId,
            lastAckSeq = ackSeq,
            lastAckAtMono = lastAckAtMono,
            consecutiveFailures = consecutiveFailures,
            recoveringSinceMono = 0,
            recoveriesTried = 0,
            // 暂停要按三来源合一的结论报给评测器，否则重启后（内存标记已清）会显示成在监管。
            paused = emergencyStopped,
            lastFailureReason = lastFailureReason,
        )
        val v = HealthEvaluator.evaluate(nowMono, obs, config)
        verdict = v.status
        verdictReason = v.reason
        if (v.action == HealthAction.NOTIFY) Log.w(TAG, "健康提醒：${v.reason}")

        // ---- 2. 有限兜底 ----
        val plan = LocalGuard.plan(
            policies = doc.policies,
            facts = GuardFacts(
                nowWall = nowWall,
                nowMono = nowMono,
                sentinelLinked = link.reachable,
                selfHealthy = v.status == HealthStatus.HEALTHY,
                emergencyStopped = emergencyStopped,
                adminActive = perms.adminActive,
                secureLockSet = perms.secureLockSet,
                repairGraceUntilMono = repairGraceUntilMono,
            ),
            arbiter = arbitration(),
            newRequestId = { "yishou-$nowMono" },
        )
        if (!plan.fire) {
            if (plan.notifyOnly) notifyFallback(context, plan.reason)
            return
        }
        val outcome = LocalLockExecutor.lockNow(context, mono, wall)
        LocalGuard.record(arbitration(), plan, outcome.state, mono.now(), outcome.atWall)
        val receipt = LockReceipt(
            policyId = plan.policyId,
            incidentId = plan.incidentId,
            requestId = plan.requestId,
            source = LocalGuard.SOURCE,
            reason = plan.reason,
            state = outcome.state,
            atWall = outcome.atWall,
            note = outcome.note,
        )
        Log.w(TAG, "本地兜底锁屏：${outcome.state}（${outcome.note}）")
        SentinelLink.reportReceipt(context, receipt)
    }

    /** 锁屏 → 解锁 的那一刻开始算修复宽限（T15：别把用户挡在修复权限的路上）。 */
    private fun updateRepairGrace(context: Context, nowMono: Long) {
        val f = LocalLockExecutor.readFacts(context) ?: return
        val lockedNow = f.deviceLocked || f.keyguardShowing
        if (wasLocked && !lockedNow) {
            repairGraceUntilMono = nowMono + config.repairGraceMs
        }
        wasLocked = lockedNow
    }

    private fun notifyFallback(context: Context, reason: String) {
        try {
            com.yishou.app.system.Notifications.showReminder(
                context,
                com.yishou.app.system.Notifications.ID_NUDGE,
                "监管规则生效中，但一手无法可靠锁屏",
                reason,
                com.yishou.app.MainActivity::class.java,
            )
        } catch (e: Exception) {
            Log.w(TAG, "发提醒失败", e)
        }
    }

    // ---- 真实的 dispatcherAck 记账（只有 Provider 在 a11y 线程处理完才算） ----

    @Volatile private var ackSeq: Long = 0
    @Volatile private var lastAckAtMono: Long = 0
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var lastFailureReason: String = ""

    fun onAckHandled(seq: Long, handledAtMono: Long) {
        ackSeq = maxOf(ackSeq, seq)
        lastAckAtMono = handledAtMono
        consecutiveFailures = 0
        lastFailureReason = ""
    }

    fun onChallengeFailed(reason: String) {
        consecutiveFailures += 1
        lastFailureReason = reason
    }

    /** 单测 / 排障：停掉线程、清空事实。 */
    fun shutdown() {
        scheduler?.shutdownNow()
        scheduler = null
        arbiter = null
        policyStore = null
        appContext = null
        ackSeq = 0
        lastAckAtMono = 0
        consecutiveFailures = 0
        lastFailureReason = ""
        verdict = HealthStatus.UNKNOWN
        verdictReason = "还没有真实响应"
        repairGraceUntilMono = 0
        wasLocked = false
    }

    /** 当前状态摘要（界面用）。 */
    data class Snapshot(
        val status: HealthStatus,
        val reason: String,
        val ackAgeMs: Long?,
        val sentinelReachable: Boolean,
        val sentinelError: String,
        val adminActive: Boolean,
        val secureLockSet: Boolean,
        val accessibilityOn: Boolean,
        val emergencyStopped: Boolean,
        val localRules: Int,
        /** 暂停是被哨兵传播过来的（而不是用户自己在这一手按的）——文案要分开说。 */
        val stoppedBySentinel: Boolean = false,
        /**
         * 暂停落盘的时刻与理由（进程重启后也能读出来）。
         * 0 / 空 = 不知道（只用于界面文案，不参与任何判断）。
         */
        val pausedAtWall: Long = 0,
        val pauseReason: String = "",
    )

    fun snapshot(context: Context): Snapshot {
        install(context)
        val store = policies(context)
        val doc = store.load()
        val link = SentinelLink.state()
        val facts = Facts.snapshot()
        val perms = LocalLockExecutor.perms(context, accessibilityOn = facts.connected)
        // 与 tick() 同一处判定（EmergencyStop.isPaused：内存 ∨ 规则 STOPPED ∨ 独立标记）。
        val stopped = EmergencyStop.isPaused(
            memoryPaused = Facts.paused,
            doc = doc,
            storeFlag = store.emergencyStopped(),
        )
        // 优先用落盘标记（重启后还在），退回本次进程内的内存记录。
        val mark = if (stopped) store.emergencyRecord() else null
        val mem = EmergencyStop.last()
        val atWall = mark?.atWall?.takeIf { it > 0 } ?: mem?.atWall ?: 0L
        val reason = mark?.reason?.takeIf { it.isNotBlank() } ?: mem?.reason.orEmpty()
        val bySentinel = mark?.bySentinel ?: (mem == null)
        return Snapshot(
            status = verdict,
            reason = verdictReason,
            ackAgeMs = if (lastAckAtMono <= 0L) null else (mono.now() - lastAckAtMono).coerceAtLeast(0L),
            sentinelReachable = link.reachable,
            sentinelError = link.error,
            adminActive = perms.adminActive,
            secureLockSet = perms.secureLockSet,
            accessibilityOn = perms.accessibilityOn,
            emergencyStopped = stopped,
            localRules = doc.policies.size,
            stoppedBySentinel = stopped && bySentinel,
            pausedAtWall = atWall,
            pauseReason = reason,
        )
    }
}
