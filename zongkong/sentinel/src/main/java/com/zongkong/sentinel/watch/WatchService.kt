package com.zongkong.sentinel.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.zongkong.sentinel.Arc
import com.zongkong.sentinel.MainActivity
import com.zongkong.sentinel.health.Challenge
import com.zongkong.sentinel.health.HealthAction
import com.zongkong.sentinel.health.HealthConfig
import com.zongkong.sentinel.health.HealthStatus
import com.zongkong.sentinel.health.LockArbiter
import com.zongkong.sentinel.health.LockRequest
import com.zongkong.sentinel.health.LockState
import com.zongkong.sentinel.health.Mono
import com.zongkong.sentinel.health.Wall

/**
 * `:watch` 的前台服务（规范 §2.1）。
 *
 * 职责只有四件，全部跑在**这个进程自己的线程**上，绝不跑在 AccessibilityService 的 Handler 上：
 *  1. 每 10s 推进 [HealthScheduler]（探测 / 确认失联 / 恢复预算）；
 *  2. 执行：规则到点或确认失联时 `lockNow()`，经 [LockArbiter] 去重与冷却，回执如实记账；
 *  3. 落盘：健康快照、策略（PolicyStore）、事件进度（EventProgressFile，由 a11y 写这里读）；
 *  4. 前台通知：如实写「在看护」+ 最近一次结论，不涂绿。
 *
 * 明确边界（T06）：整包被系统强制停止后，哨兵**无法自我保证恢复**。不做层层互拉的无限看门狗。
 */
class WatchService : Service() {

    private var thread: Thread? = null

    private lateinit var clock: WatchClock
    private lateinit var policies: PolicyStore
    private lateinit var health: HealthStore
    private lateinit var events: EventProgressFile
    private lateinit var lock: LockExecutor
    private lateinit var scheduler: HealthScheduler
    private var probe: ClientProbe? = null

    private val arbiter = LockArbiter()
    private var repairGraceUntilMono = 0L

    override fun onCreate() {
        super.onCreate()
        startInForeground(createChannel())

        // 与 WatchProvider 共用同一份实例（都在 :watch 进程内）；不跨进程共享。
        WatchBus.ensure(this)
        clock = WatchBus.clock!!
        policies = WatchBus.policies!!
        health = WatchBus.health!!
        events = EventProgressFile(filesDir)
        lock = LockExecutor(this)

        // 先给 scheduler 一个可空 sink，probe 建好后再回填（避开构造循环）。
        val sink = LateSink()
        scheduler = HealthScheduler(
            store = health,
            sink = sink,
            mono = Mono { SystemClock.elapsedRealtime() },
            wall = Wall { clock.now() },
            cfg = HealthConfig(),
            activeEnforcement = { activeEnforcementNow() },
            onAction = { pkg, action, reason -> onHealthAction(pkg, action, reason) },
        )
        probe = ClientProbe(this, scheduler)
        sink.inner = probe
        // 把调度器也交回总线，provider 的 register 会用到它（换 instanceId 时重置校验基线）。
        WatchBus.bind(scheduler, policies, health, clock)

        running = true
        thread = Thread({ loop() }, "sentinel-watch").also { it.isDaemon = true; it.start() }
    }

    /** 延迟绑定，绕开 ProbeSink ↔ Scheduler 的构造循环。 */
    private class LateSink : HealthScheduler.ProbeSink {
        var inner: HealthScheduler.ProbeSink? = null
        override fun dispatch(pkg: String, challenge: Challenge, budgetMs: Long) {
            inner?.dispatch(pkg, challenge, budgetMs)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // 这几件事都会跨进程调用或等锁屏观测（最多几秒），不放在服务主线程上做。
            ACTION_EMERGENCY -> {
                val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
                background("emergency") { emergencyStop(reason) }
            }
            ACTION_CANCEL_EMERGENCY -> background("cancel-emergency") { cancelEmergency() }
            ACTION_LOCK_NOW -> background("lock-now") {
                attemptLock(
                    policyId = policies.load().doc.policies.firstOrNull()?.policyId ?: "manual",
                    incidentId = "manual-${SystemClock.elapsedRealtime()}",
                    reason = "手动测试锁屏（用户在界面上点的）",
                )
            }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun background(name: String, job: () -> Unit) {
        Thread({
            try { job() } catch (e: Throwable) { Log.w(TAG, "$name 出错", e) }
        }, "sentinel-$name").apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        runCatching { health.flush(clock.now()) }
        probe?.shutdown()
        super.onDestroy()
    }

    // ---------- 主循环（:watch 自己的线程） ----------

    private fun loop() {
        // 休眠唤醒 / 首次启动：先重建观测，不把断档期间漏掉的心跳全算作失败（规范 §7）。
        refreshSystemFacts()
        while (running) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                refreshSystemFacts()
                val tick = scheduler.tick()
                for ((pkg, reason) in tick.needLock) {
                    val now = clock.now()
                    val policy = policies.load().doc.policies.firstOrNull { it.effective(now) }
                    if (policy?.emergencyState == PolicyStore.EMERGENCY_STOPPED) continue
                    if (emergencyActive()) continue
                    attemptLock(
                        policyId = policy?.policyId ?: NO_POLICY,
                        // 一次失联 = 一个事件：在拿到下一个真实 ack 之前 incidentId 不变，
                        // 仲裁器按它限次数（每事件最多 2 次）。以前按「分钟」编号，
                        // 失联多久就每分钟锁一次，修都来不及修（T07/T15）。
                        incidentId = incidentFor(pkg),
                        reason = "客户端 $pkg ${reason}",
                    )
                }
                dueLocks()
                notifyStatus(tick.statuses)
                health.flush(clock.now())
            } catch (e: Throwable) {
                Log.w(TAG, "watch 循环出错", e)
            }
            val spent = SystemClock.elapsedRealtime() - startedAt
            try {
                Thread.sleep((HealthScheduler.TICK_MS - spent).coerceAtLeast(500))
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** 「装没装 / 无障碍开没开」一律读系统设置，不采信客户端自报（报告 §6）。 */
    private fun refreshSystemFacts() {
        val enabled = enabledAccessibilityComponents()
        val now = clock.now()
        for (pkg in HealthStore.CLIENTS) {
            health.observeSystem(pkg, isInstalled(pkg), enabled.any { it.startsWith("$pkg/") }, now)
        }
        health.observeSystem(HealthStore.SELF, true, enabled.any { it.startsWith("${HealthStore.SELF}/") }, now)

        // 事件通道（T03）的**唯一判定点**：读 a11y 进程写的 events.json → EventChannelWiring.refresh
        // → 落盘。调度器不再算这一项（F-01：两处都算时，坏的那份会把好结论覆盖成「待验证」）。
        EventChannelWiring.refresh(
            store = health,
            progress = events.read(),
            nowWall = now,
            nowMono = SystemClock.elapsedRealtime(),
            suppressed = !isInteractive() || !activeEnforcementNow(),
        )
    }

    private fun enabledAccessibilityComponents(): List<String> = try {
        (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private fun isInteractive(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
    } catch (_: Throwable) {
        true
    }

    /** 只有「亮屏 + 有生效规则 + 没紧急暂停」才允许把失联升级为锁屏（T13/T15）。 */
    private fun activeEnforcementNow(): Boolean {
        if (!isInteractive()) return false
        if (emergencyActive()) return false
        if (repairGraceUntilMono > 0 && SystemClock.elapsedRealtime() < repairGraceUntilMono) return false
        val now = clock.now()
        return policies.load().doc.activeAt(now).any { it.emergencyState != PolicyStore.EMERGENCY_STOPPED }
    }

    /**
     * 紧急暂停是否还在生效：只管「按下时正在生效的那些窗口」，窗口结束就自然失效（规范 §6）。
     * 以前只看有没有记录，按过一次之后就永远不再锁屏，直到有人手动取消。
     */
    private fun emergencyActive(): Boolean {
        val rec = health.snapshot().emergency ?: return false
        val now = clock.now()
        val stillStopped = policies.load().doc.policies.any {
            it.emergencyState == PolicyStore.EMERGENCY_STOPPED && it.effective(now)
        }
        if (!stillStopped) {
            health.setEmergency(null, now)
            Arc.log(this, "紧急暂停所在的监管窗口已结束，暂停自然失效（${rec.reason}）")
        }
        return stillStopped
    }

    /** 当前这次失联的事件号：同一个「最后一次真实 ack」之后的失联都算同一个事件。 */
    private fun incidentFor(pkg: String): String = "$pkg-after-ack-${health.get(pkg).lastAckSeq}"

    // ---------- 锁屏执行：仲裁 + 观测 + 回执 ----------

    @Synchronized
    fun attemptLock(policyId: String, incidentId: String, reason: String) {
        if (emergencyActive()) {
            Arc.log(this, "紧急暂停中，不锁：$reason")
            return
        }
        val nowMono = SystemClock.elapsedRealtime()
        val nowWall = clock.now()
        val req = LockRequest(
            policyId = policyId,
            incidentId = incidentId,
            requestId = "$incidentId-$nowMono",
            source = "sentinel",
            reason = reason,
        )
        val decision = arbiter.decide(req, nowMono, repairGraceUntilMono)
        if (!decision.fire) {
            Log.i(TAG, "不锁：${decision.reason}")
            return
        }
        val outcome = lock.lockNow()
        arbiter.record(req, outcome.state, nowMono)
        health.recordLock(outcome.state, policyId, incidentId, outcome.note, nowWall)
        if (outcome.state == LockState.OBSERVED_LOCKED) {
            // 锁上了：给用户一段修复宽限，不立刻再锁（T15）。
            repairGraceUntilMono = nowMono + HealthConfig().repairGraceMs
        }
        Arc.log(this, "锁屏回执：${outcome.state} / ${outcome.note}")
    }

    /** 规则自带的「到期锁屏」（`lockAtWall`）。 */
    private fun dueLocks() {
        if (emergencyActive()) return
        val now = clock.now()
        for (p in policies.lockDue(now)) {
            if (p.emergencyState == PolicyStore.EMERGENCY_STOPPED) continue
            attemptLock(p.policyId, "policy-${p.policyId}-lockAt", "规则到期锁屏（failureAction=${p.failureAction}）")
        }
    }

    // ---------- 健康动作：只记账 + 发恢复请求，不宣布成功 ----------

    private fun onHealthAction(pkg: String, action: HealthAction, reason: String) {
        when (action) {
            HealthAction.START_RECOVERY -> {
                val sent = Arc.requestRebind(this, pkg)
                // 次数已经由调度器记过一次，这里只写说明（以前两边都 +1，一次恢复算两次，预算立刻用完）。
                health.noteRecovery(
                    pkg,
                    if (sent) "恢复请求已发出（等待新实例 + 真 ack）" else "没有写安全设置权限，恢复请求未发出",
                    clock.now(),
                )
                Arc.log(this, "$pkg：$reason → ${if (sent) "已发出恢复请求" else "只能引导手动修复"}")
            }
            HealthAction.ESCALATE -> Arc.log(this, "$pkg 恢复预算用完：$reason（不再重试）")
            HealthAction.NOTIFY -> Arc.log(this, "$pkg $reason")
            else -> Unit
        }
    }

    // ---------- 紧急暂停（与 Release.kt 的正常解除分开，规范 §6） ----------

    @Synchronized
    fun emergencyStop(reason: String) {
        val nowWall = clock.now()
        val nowMono = SystemClock.elapsedRealtime()
        val applied = policies.setEmergency(nowWall, null, PolicyStore.EMERGENCY_STOPPED)
        if (!applied.ok) Arc.log(this, "紧急暂停没落到策略文件：${applied.error}")
        val targets = health.snapshot().clients.values.filter { it.registered }.map { it.pkg }
            .ifEmpty { HealthStore.CLIENTS.filter { isInstalled(it) } }
        // 本地先生效、先落盘（界面马上能看到），再逐个通知客户端；通知结果回来再补上确认情况。
        health.setEmergency(Emergency.trigger(nowWall, nowMono, reason, null, targets, emptyList()), nowWall)
        health.flush(nowWall)
        val reached = ArrayList<String>()
        for (pkg in targets) {
            // 每个客户端独立 + 独立超时：一个不回不影响其他（T16/T04）。
            if (ClientCall.notifyEmergency(this, pkg, reason)) reached += pkg
        }
        val rec = Emergency.trigger(nowWall, nowMono, reason, null, targets, reached)
        health.setEmergency(rec, nowWall)
        health.flush(nowWall)
        Arc.log(this, "紧急暂停已生效：${Emergency.describe(rec, targets)}")
        notifyStatus(emptyMap())
    }

    @Synchronized
    fun cancelEmergency() {
        val now = clock.now()
        policies.setEmergency(now, null, PolicyStore.EMERGENCY_NONE)
        health.setEmergency(null, now)
        health.flush(now)
        Arc.log(this, "取消紧急暂停，按规则恢复监管")
        notifyStatus(emptyMap())
    }

    // ---------- 前台通知 ----------

    private fun createChannel(): NotificationChannel {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "哨兵看护", NotificationManager.IMPORTANCE_LOW).apply {
            description = "哨兵在独立进程里看护三个应用的运行与执行状态"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
        return ch
    }

    private fun startInForeground(channel: NotificationChannel) {
        val n = buildNotification("正在看护三个应用", "每 10 秒一次真实挑战；异常按规则处理")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun notifyStatus(statuses: Map<String, HealthStatus> = emptyMap()) {
        val text = if (statuses.isEmpty()) "等一次探测" else
            statuses.entries.joinToString(" · ") { (pkg, st) -> "${short(pkg)}:${label(st)}" }
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFY_ID, buildNotification("哨兵在看护", text))
        } catch (_: Throwable) {}
    }

    private fun label(st: HealthStatus): String = when (st) {
        HealthStatus.HEALTHY -> "正常"
        HealthStatus.UNREGISTERED -> "未接入"
        HealthStatus.UNKNOWN -> "未知"
        HealthStatus.SUSPECT -> "可疑"
        HealthStatus.DISABLED -> "无障碍关"
        HealthStatus.UNRESPONSIVE -> "失联"
        HealthStatus.RECOVERING -> "恢复中"
        HealthStatus.PAUSED -> "暂停"
    }

    private fun short(pkg: String) = when (pkg) {
        HealthStore.ZONGKONG -> "总控"
        HealthStore.YISHOU -> "一手"
        HealthStore.PET -> "同桌"
        else -> "哨兵"
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "SentinelWatch"
        private const val CHANNEL = "watch"
        private const val NOTIFY_ID = 11
        private const val NO_POLICY = "no-policy"

        const val ACTION_REFRESH = "com.zongkong.sentinel.watch.REFRESH"
        const val ACTION_EMERGENCY = "com.zongkong.sentinel.watch.EMERGENCY_STOP"
        const val ACTION_CANCEL_EMERGENCY = "com.zongkong.sentinel.watch.CANCEL_EMERGENCY"
        const val ACTION_LOCK_NOW = "com.zongkong.sentinel.watch.LOCK_NOW"
        const val EXTRA_REASON = "reason"

        @Volatile var running = false

        fun start(c: Context) {
            val i = Intent(c, WatchService::class.java).setAction(ACTION_REFRESH)
            try {
                c.startForegroundService(i)
            } catch (_: Throwable) {
                try { c.startService(i) } catch (_: Throwable) {}
            }
        }

        fun send(c: Context, action: String, reason: String = "") {
            val i = Intent(c, WatchService::class.java).setAction(action).putExtra(EXTRA_REASON, reason)
            try {
                c.startForegroundService(i)
            } catch (_: Throwable) {
                try { c.startService(i) } catch (_: Throwable) {}
            }
        }

        fun startEmergency(c: Context, reason: String) = send(c, ACTION_EMERGENCY, reason)
    }
}
