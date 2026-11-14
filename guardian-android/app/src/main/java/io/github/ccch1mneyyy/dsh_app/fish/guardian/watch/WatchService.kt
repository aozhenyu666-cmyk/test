package io.github.ccch1mneyyy.dsh_app.fish.guardian.watch

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
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import io.github.ccch1mneyyy.dsh_app.fish.guardian.a11y.GuardianA11yService
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ChallengeAckValidator
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ClampedWallClock
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyPassQuota
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineHealthAction
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineHealthConfig
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineState
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianConstants
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.LockArbiter
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.Mono
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeChallenge
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeValidation
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.Wall
import io.github.ccch1mneyyy.dsh_app.fish.guardian.ui.GuardianModalActivity
import io.github.ccch1mneyyy.dsh_app.fish.guardian.ui.MainActivity
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Isolated `:watch` process foreground service monitoring DSH Go's runtime phase & accessibility
 * main-thread responsiveness independently of DSH Go's own process lifecycle.
 */
class WatchService : Service() {

    private var loopThread: Thread? = null
    private lateinit var stores: GuardianStores
    private lateinit var clock: ClampedWallClock
    private lateinit var lockExec: LockExecutor
    private val validator = ChallengeAckValidator()
    private val lockArbiter = LockArbiter()
    private val seqCounter = AtomicLong(1L)

    private val healthConfig = EngineHealthConfig()
    private var lastModalAtMono = 0L
    private var lastAlertState: EngineState? = null
    private var lastProbeNowSignal = 0L

    override fun onCreate() {
        super.onCreate()
        stores = GuardianStores(filesDir)
        clock = ClampedWallClock(
            wall = Wall { System.currentTimeMillis() },
            mono = Mono { SystemClock.elapsedRealtime() },
        )
        lockExec = LockExecutor(this)
        ensureChannels()
        startInForeground("大肥鱼哨兵看护中", "正在初始化探针")
        WatchBus.service = this
        WatchBus.stores = stores
        WatchBus.clock = clock
        WatchBus.lockExecutor = lockExec

        running = true
        loopThread = Thread({ runLoop() }, "fish-guardian-watch").apply {
            isDaemon = true
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROBE_NOW -> lastProbeNowSignal = SystemClock.elapsedRealtime()
            ACTION_WAKE_NOW -> Thread({ performWake(allowActivityFallback = true) }, "fish-manual-wake").start()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        WatchBus.service = null
        loopThread?.interrupt()
        super.onDestroy()
    }

    private fun runLoop() {
        while (running) {
            val tickStart = SystemClock.elapsedRealtime()
            runCatching { evaluateCycle(forceProbe = (tickStart - lastProbeNowSignal) < 12_000L) }
                .onFailure { ArcLog.append(this, "看护循环异常：${it.message ?: it.javaClass.simpleName}") }
            val sleepMs = if (isInteractive()) healthConfig.probePeriodMs else 25_000L
            val elapsed = SystemClock.elapsedRealtime() - tickStart
            val remaining = (sleepMs - elapsed).coerceAtLeast(1_500L)
            try {
                Thread.sleep(remaining)
            } catch (_: InterruptedException) {
                if (!running) return
            }
        }
    }

    internal fun evaluateCycle(forceProbe: Boolean = false): GuardianSnapshot {
        val nowMono = clock.monoNow()
        val nowWall = clock.now()
        var snap = stores.loadSnapshot()
        val settings = stores.loadSettings()
        val pass = stores.loadPass()
        val passActive = EmergencyPassQuota.isActive(pass, nowMono)

        val targetPkg = FishClientBridge.resolveInstalledTarget(this)
        if (targetPkg == null) {
            val empty = snap.copy(
                targetPkg = "",
                installed = false,
                paired = false,
                sameCert = false,
                enginePhase = "notInstalled",
                dshServiceRunning = false,
                a11yConnected = false,
                stateName = EngineState.NOT_INSTALLED.name,
                statusReason = "未检测到 DSH Go 大肥鱼客户端",
                updatedAtWall = nowWall,
                pendingOfflineCount = stores.loadIncidents().size,
                adminActive = lockExec.adminActive(),
                writeSecureGranted = FishClientBridge.canWriteSecureSettings(this),
                overlayGranted = Settings.canDrawOverlays(this),
            )
            stores.saveSnapshot(empty)
            updateNotification("未检测到 DSH Go 大肥鱼", empty.statusReason)
            return empty
        }

        val enabledList = enabledA11yServices()
        val targetA11yEnabled = enabledList.any { it.startsWith("$targetPkg/") }
        val selfA11yEnabled = enabledList.any { it.startsWith("$packageName/") }
        val sameCert = FishClientBridge.sameCert(this, targetPkg)
        var paired = FishClientBridge.ensureAutoPairIfSameCert(this, targetPkg)
        if (!paired) {
            val bond = FishPairingStore.load(this)
            val peerCert = FishClientBridge.certSha256(this, targetPkg).orEmpty()
            paired = bond.targetPkg == targetPkg &&
                bond.pairToken.isNotBlank() &&
                bond.pinnedCertSha256.equals(peerCert, ignoreCase = true)
        }

        snap = snap.copy(
            targetPkg = targetPkg,
            installed = true,
            paired = paired,
            sameCert = sameCert,
            permissionA11yEnabled = targetA11yEnabled,
            selfA11yEnabled = selfA11yEnabled,
            adminActive = lockExec.adminActive(),
            writeSecureGranted = FishClientBridge.canWriteSecureSettings(this),
            overlayGranted = Settings.canDrawOverlays(this),
        )

        // Probe when paired & interval elapsed (or forced).
        val shouldProbe = paired && (forceProbe || nowMono - snap.lastProbeAtMono >= healthConfig.probePeriodMs - 800L)
        if (shouldProbe) {
            val seq = seqCounter.getAndIncrement()
            val challenge = ProbeChallenge(
                nonce = UUID.randomUUID().toString(),
                seq = seq,
                issuedAtMono = nowMono,
            )
            snap = snap.copy(lastProbeAtMono = nowMono)
            when (val res = FishClientBridge.challenge(this, targetPkg, challenge, healthConfig.challengeBudgetMs)) {
                is FishClientBridge.ChallengeOutcome.Success -> {
                    when (val valid = validator.validate(challenge, res.ack, res.receivedAtMono)) {
                        is ProbeValidation.Accepted -> {
                            snap = stores.applyAck(snap, valid.ack, res.receivedAtMono, nowWall)
                            if (valid.ack.policyMirrorJson.isNotBlank()) {
                                stores.saveMirrorJson(valid.ack.policyMirrorJson)
                            }
                            if (valid.ack.enginePhase == "running" && valid.ack.a11yConnected) {
                                FishClientBridge.uploadOfflineIncidents(this, targetPkg, stores)
                            }
                        }
                        is ProbeValidation.Rejected -> {
                            snap = snap.copy(
                                consecutiveFailures = snap.consecutiveFailures + 1,
                                lastFailureReason = valid.reason,
                            )
                        }
                    }
                }
                is FishClientBridge.ChallengeOutcome.Failure -> {
                    if (res.authMissing) {
                        FishPairingStore.save(this, FishPairingStore.Bond())
                        snap = snap.copy(paired = false, lastFailureReason = "配对凭据失效，需重新配对")
                    } else {
                        snap = snap.copy(
                            consecutiveFailures = snap.consecutiveFailures + 1,
                            lastFailureReason = res.reason,
                        )
                    }
                }
            }
        }

        val verdict = snap.verdict(nowMono, passActive, healthConfig)
        snap = snap.copy(
            stateName = verdict.state.name,
            statusReason = verdict.reason,
            updatedAtWall = nowWall,
            pendingOfflineCount = stores.loadIncidents().size,
        )

        if (!passActive) {
            snap = executeAction(snap, verdict.state, verdict.action, verdict.reason, settings, nowMono, nowWall)
        }

        if (verdict.isHealthy && lastAlertState != null && lastAlertState != EngineState.HEALTHY) {
            ArcLog.append(this, "大肥鱼已恢复健康（${snap.enginePhase}）")
            stores.enqueueIncident(
                OfflineIncident(
                    id = "recovered-$nowWall",
                    type = "recovery_succeeded",
                    atWall = nowWall,
                    detail = "大肥鱼恢复运行并重新通过无障碍探针",
                    enginePhase = snap.enginePhase,
                    healthStatus = verdict.state.name,
                    pkg = snap.targetPkg,
                ),
            )
            FishClientBridge.uploadOfflineIncidents(this, targetPkg, stores)
        }
        lastAlertState = verdict.state

        snap = snap.copy(pendingOfflineCount = stores.loadIncidents().size)
        stores.saveSnapshot(snap)
        updateNotification(notificationTitle(verdict.state, passActive), verdict.reason)
        return snap
    }

    private fun executeAction(
        snap: GuardianSnapshot,
        state: EngineState,
        action: EngineHealthAction,
        reason: String,
        settings: GuardianSettings,
        nowMono: Long,
        nowWall: Long,
    ): GuardianSnapshot {
        var next = snap
        when (action) {
            EngineHealthAction.ATTEMPT_A11Y_REBIND -> {
                if (settings.autoRebindA11y && FishClientBridge.canWriteSecureSettings(this)) {
                    val ok = FishClientBridge.rebindTargetA11y(this, snap.targetPkg)
                    ArcLog.append(this, "尝试写回大肥鱼无障碍：$ok")
                    if (!ok && isInteractive() && settings.modalOnEngineDown) {
                        raiseModal(state, reason, snap.targetPkg, snap.enginePhase, nowMono)
                    }
                } else if (isInteractive() && settings.modalOnEngineDown && nowMono - lastModalAtMono >= 45_000L) {
                    raiseModal(state, reason, snap.targetPkg, snap.enginePhase, nowMono)
                }
            }
            EngineHealthAction.ATTEMPT_ENGINE_WAKE -> {
                next = next.copy(lastWakeAttemptMono = nowMono)
                stores.enqueueIncident(
                    OfflineIncident(
                        id = "down-${nowWall / 60_000}",
                        type = "engine_down",
                        atWall = nowWall,
                        detail = reason,
                        enginePhase = snap.enginePhase,
                        healthStatus = state.name,
                        pkg = snap.targetPkg,
                    ),
                )
                val wake = FishClientBridge.wakeEngine(this, snap.targetPkg, allowActivityFallback = false)
                ArcLog.append(this, "检测到引擎异常（${snap.enginePhase}），尝试拉起：${wake.detail}")
                if (!wake.ok && isInteractive() && settings.modalOnEngineDown) {
                    raiseModal(state, reason, snap.targetPkg, snap.enginePhase, nowMono)
                }
            }
            EngineHealthAction.SHOW_MODAL_AND_LOCK -> {
                if (isInteractive() && settings.modalOnEngineDown && nowMono - lastModalAtMono >= 30_000L) {
                    raiseModal(state, reason, snap.targetPkg, snap.enginePhase, nowMono)
                }
                if (isInteractive() && settings.lockOnEngineDown && lockExec.adminActive()) {
                    val incId = "${state.name}-${nowWall / 60_000}"
                    val dec = lockArbiter.decide(incId, nowMono)
                    if (dec.fire) {
                        val out = lockExec.lockNow()
                        lockArbiter.record(incId, out.state, nowMono)
                        ArcLog.append(this, "联动锁屏结果：${out.state} / ${out.note}")
                        next = next.copy(lastLockNote = "${out.state.name}：${out.note}")
                        stores.enqueueIncident(
                            OfflineIncident(
                                id = "lock-$nowWall",
                                type = "screen_lock_attempted",
                                atWall = nowWall,
                                detail = out.note,
                                healthStatus = out.state.name,
                                pkg = snap.targetPkg,
                            ),
                        )
                    }
                }
            }
            else -> Unit
        }
        return next
    }

    fun performWake(allowActivityFallback: Boolean): FishClientBridge.WakeOutcome {
        val pkg = FishClientBridge.resolveInstalledTarget(this)
            ?: return FishClientBridge.WakeOutcome(false, "未安装 DSH Go 大肥鱼")
        val nowWall = clock.now()
        stores.enqueueIncident(
            OfflineIncident(
                id = "wake-$nowWall",
                type = "recovery_requested",
                atWall = nowWall,
                detail = "用户或哨兵触发主动唤醒",
                pkg = pkg,
            ),
        )
        val out = FishClientBridge.wakeEngine(this, pkg, allowActivityFallback)
        ArcLog.append(this, "手动/模态唤醒结果：${out.detail}")
        lastProbeNowSignal = SystemClock.elapsedRealtime()
        return out
    }

    private fun raiseModal(
        state: EngineState,
        reason: String,
        targetPkg: String,
        enginePhase: String,
        nowMono: Long,
    ) {
        lastModalAtMono = nowMono
        postUrgentAlert("大肥鱼守护警报", reason)
        runCatching {
            val intent = GuardianModalActivity.buildIntent(
                context = this,
                mode = GuardianModalActivity.MODE_ENGINE_DOWN,
                title = if (state == EngineState.A11Y_DISABLED) "无障碍通道被关闭" else "大肥鱼引擎已关闭或失联",
                reason = reason,
                targetPkg = targetPkg,
                enginePhase = enginePhase,
            )
            startActivity(intent)
        }.onFailure {
            ArcLog.append(this, "弹出全屏模态失败，已改发紧急通知：${it.message}")
        }
    }

    private fun enabledA11yServices(): List<String> = runCatching {
        (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun isInteractive(): Boolean = runCatching {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }.getOrDefault(true)

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "大肥鱼哨兵 · 常驻看护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "每 10 秒核验 DSH Go 引擎与无障碍状态"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "大肥鱼哨兵 · 掉线告警", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "引擎停止或被绕过时的即时提示"
                enableVibration(true)
            },
        )
    }

    private fun startInForeground(title: String, body: String) {
        val n = buildWatchNotification(title, body)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, n)
        }
    }

    private fun updateNotification(title: String, body: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFY_ID, buildWatchNotification(title, body))
        }
    }

    private fun postUrgentAlert(title: String, body: String) {
        val pi = PendingIntent.getActivity(
            this, 202,
            GuardianModalActivity.buildIntent(this, GuardianModalActivity.MODE_ENGINE_DOWN, title, body, "", "unknown"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(ALERT_ID, n)
        }
    }

    private fun notificationTitle(st: EngineState, passActive: Boolean): String {
        if (passActive) return "大肥鱼哨兵 · 独立急事通行中（3分钟）"
        return when (st) {
            EngineState.HEALTHY -> "大肥鱼运行中 · 引擎与无障碍均健康"
            EngineState.STARTING_GRACE -> "大肥鱼正在冷启动（保护宽限中）"
            EngineState.ENGINE_DOWN -> "警告：DSH 引擎已关闭或退出"
            EngineState.A11Y_DISABLED -> "警告：大肥鱼无障碍开关已关闭"
            EngineState.UNRESPONSIVE -> "警告：大肥鱼主线程无响应"
            EngineState.NEEDS_PAIRING -> "待配对：请打开哨兵完成配对"
            EngineState.NOT_INSTALLED -> "未检测到 DSH Go 大肥鱼"
            EngineState.SUSPECT -> "正在复检大肥鱼状态"
            EngineState.UNKNOWN -> "正在初始化大肥鱼探针"
        }
    }

    private fun buildWatchNotification(title: String, body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CH_WATCH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CH_WATCH = "fish_guardian_watch"
        private const val CH_ALERT = "fish_guardian_alert"
        private const val NOTIFY_ID = 701
        private const val ALERT_ID = 702
        const val ACTION_REFRESH = "io.github.ccch1mneyyy.dsh_app.fish.guardian.REFRESH"
        const val ACTION_PROBE_NOW = "io.github.ccch1mneyyy.dsh_app.fish.guardian.PROBE_NOW"
        const val ACTION_WAKE_NOW = "io.github.ccch1mneyyy.dsh_app.fish.guardian.WAKE_NOW"

        @Volatile var running = false

        fun start(c: Context) {
            val i = Intent(c, WatchService::class.java).setAction(ACTION_REFRESH)
            runCatching { c.startForegroundService(i) }
                .onFailure { runCatching { c.startService(i) } }
        }
    }
}

object WatchBus {
    @Volatile var service: WatchService? = null
    @Volatile var stores: GuardianStores? = null
    @Volatile var clock: ClampedWallClock? = null
    @Volatile var lockExecutor: LockExecutor? = null
}
