package com.zongkong.app.system

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService
import com.zongkong.app.guard.RebindCoordinator
import com.zongkong.app.guard.SentinelClient
import com.zongkong.app.health.HealthAction
import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.health.HealthVerdict
import com.zongkong.app.healthwire.GuardHealth
import com.zongkong.app.zk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍自动恢复（T11/T12）。
 *
 * v2 的关键变化（报告 §8）：
 *   **重绑成功 ≠ 修好了。** 重绑只记「恢复请求已发出」；必须等到
 *   ①新的无障碍实例（instanceId 换了）②恢复开始之后的一次真 dispatcherAck，
 *   才能变 HEALTHY。最多两次，超了进入预设的失效处理（ESCALATE）。
 *
 * 另外两条硬要求：
 *   - 重绑前后**重新读取**当前无障碍列表，把其他已启用服务原样保留（T12）；
 *   - 与哨兵**串行**（跨进程文件锁 + 本地锁），避免两边同时改同一份系统配置。
 *
 * 只修「开着但没在运行 / 不再响应」；你自己在设置里关掉的，不替你打开。
 */
object SelfHeal {
    private const val TAG = "SelfHeal"
    const val GRANT_COMMAND = "adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS"

    private val cfg = HealthConfig()

    @Volatile private var lastHealMono = 0L
    @Volatile private var state = ""
    @Volatile private var reminded = false

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * 把总控的无障碍服务从列表里拿掉、再放回去：系统会重新挂上它。
     *
     * 返回 true **只表示「恢复请求已发出」**。真正是否修好由 [HealthLedger] 判：
     * 要新 instanceId + 恢复之后的真 ack。
     */
    suspend fun rebind(context: Context): Boolean {
        if (!canWrite(context)) return false
        val me = ComponentName(context, GuardService::class.java)
        // RebindCoordinator 内部：跨进程文件锁串行 + 写回前重新读取 + 保留其他服务。
        val sent = RebindCoordinator.rebind(context, me)
        if (sent) {
            delay(1_200)
            Diag.log(context, "rebind_sent", "恢复请求已发出（不是已修好）：等新实例 + 真 ack")
        }
        return sent
    }

    /** 看一眼并（在预算内）修一次。每次判定都记进诊断（状态不变不重复刷）。 */
    suspend fun check(context: Context) {
        val store = context.zk.store
        if (store.config.value.pausedUntil > System.currentTimeMillis()) return
        checkSentinel(context)

        if (runCatching { GuardHealth.init(context) }.getOrNull() == null) return
        val permissionEnabled = GuardHealth.permissionEnabled(context)
        val v: HealthVerdict = GuardHealth.verdict(permissionEnabled)
        logStateChange(context, v)

        when (v.status) {
            HealthStatus.HEALTHY -> {
                // 真的在响应：收尾恢复流程。
                if (v.action == HealthAction.NONE) GuardHealth.endRecovery()
                reminded = false
                return
            }
            HealthStatus.DISABLED -> {
                // 用户在设置里关的：不替你打开，只提醒。
                warn(context, enabled = false, verdict = v)
                return
            }
            HealthStatus.UNKNOWN, HealthStatus.PAUSED -> return
            HealthStatus.RECOVERING -> return
            HealthStatus.SUSPECT -> {
                // 还没到确认失联：不重绑（避免抖动），交给下一轮。
                warn(context, enabled = true, verdict = v)
                return
            }
            HealthStatus.UNRESPONSIVE -> {
                if (v.action == HealthAction.START_RECOVERY) {
                    tryRecover(context, v)
                    return
                }
                if (v.action == HealthAction.ESCALATE) {
                    // 恢复预算用完：停止重试，按预先显示的失效处理（本地规则兜底 + 明说）。
                    escalate(context, v)
                    return
                }
            }
            HealthStatus.UNREGISTERED -> return
        }
    }

    /** 有限恢复：最多 [HealthConfig.maxRecoveries] 次，只在预算内，且与哨兵串行。 */
    private suspend fun tryRecover(context: Context, v: HealthVerdict) {
        if (!canWrite(context)) {
            warn(context, enabled = true, verdict = v)
            return
        }
        // 节流：两次重绑之间至少 3 分钟，避免把系统设置刷成抖动。
        val nowMono = SystemClock.elapsedRealtime()
        if (lastHealMono > 0 && nowMono - lastHealMono < 3 * 60_000L) return
        // 只有真的记上「恢复已开始」才发重绑（保证次数上限由台账统一管）。
        if (!GuardHealth.beginRecovery(cfg.maxRecoveries)) {
            escalate(context, v)
            return
        }
        lastHealMono = nowMono
        val sent = rebind(context)
        if (!sent) {
            // 请求都没发出去：不算一次成功尝试，但次数已经计过了（最多两次的约束仍在）。
            Diag.log(context, "rebind_fail", "恢复请求没发出去（没有写安全设置权限或锁被占用）")
            warn(context, enabled = true, verdict = v)
            return
        }
        // 只记「请求已发出」，**不**置健康、**不**清失败计数。
        state = "recovering"
        context.zk.store.logWake("无障碍不再响应：恢复请求已发出（等新实例 + 真 ack）")
        Diag.log(context, "rebind_ok", "恢复请求已发出：等新实例 + 真 ack 才算恢复")
    }

    private fun escalate(context: Context, v: HealthVerdict) {
        val store = context.zk.store
        // 停止重试，进入预先显示的失效处理：说清楚，不宣称已修复。
        if (!reminded) {
            reminded = true
            Diag.log(context, "escalate", "恢复预算用完，停止重试：${v.reason}")
            runCatching {
                Notifications.hqCall(
                    context,
                    "自动恢复用完了",
                    "${v.reason}。已停止重试。本地规则会在自己健康时按规则兜底；请到权限页手动关开一次无障碍。",
                    fullScreen = false,
                    route = "settings/perm",
                )
            }
            store.logWake("恢复预算用完：已停止重试（按预设失效处理）")
        }
    }

    private fun logStateChange(context: Context, v: HealthVerdict) {
        val nowState = "${v.status.name}/${v.action.name}"
        if (state == nowState) return
        state = nowState
        when (v.status) {
            HealthStatus.DISABLED -> Diag.log(context, "disabled", "无障碍在设置里是关着的")
            HealthStatus.UNRESPONSIVE -> Diag.log(context, "unresponsive", "确认失联：${v.reason}")
            HealthStatus.SUSPECT -> Diag.log(context, "stale", "有失败迹象：${v.reason}")
            HealthStatus.RECOVERING -> Diag.log(context, "recovering", "正在等新实例 + 真 ack")
            HealthStatus.HEALTHY -> Diag.log(context, "running", "真实执行线程在响应")
            else -> {}
        }
    }

    @Volatile private var lastSentinelHeal = 0L
    @Volatile private var sentinelState = ""

    /**
     * 反过来盯哨兵（独立应用）：**不再用 GuardService.running 判**，也不再拿「装了+开着」当在岗。
     * 用 v2 的连接分类；不在岗且有权限就把它挂回去（10 分钟最多一次），并说清楚「只是请求已发出」。
     */
    private fun checkSentinel(context: Context) {
        val now = System.currentTimeMillis()
        // link() 自带超时与在途上限，在常驻服务的后台线程上调用是安全的；
        // problem() 复用同一份缓存，不再多探一次。
        val label = runCatching { SentinelClient.link(context) }
            .getOrElse { SentinelClient.Link.UNKNOWN }
        val problem = problemText(label)
        val st = label.name
        if (st != sentinelState) {
            sentinelState = st
            Diag.log(context, if (problem == null) "sentinel_on" else "sentinel_off", problem ?: "哨兵在岗（v2 已接入）")
        }
        if (problem == null || !SentinelClient.installed(context) || !SentinelClient.genuine(context)) return
        if (!canWrite(context) || now - lastSentinelHeal < 10 * 60_000L) return
        lastSentinelHeal = now
        if (SentinelClient.rebind(context)) {
            Diag.log(context, "rebind_sent", "哨兵的恢复请求已发出（不是已修好：等哨兵自己新实例 + ack）")
        }
    }

    /** 从已判定好的分类翻一句人话（不再二次探测，避免一拍两次跨进程）。 */
    private fun problemText(l: SentinelClient.Link): String? = when (l) {
        SentinelClient.Link.RESPONDING -> null
        SentinelClient.Link.NOT_INSTALLED -> "没装「总控哨兵」"
        SentinelClient.Link.NOT_GENUINE -> "装的「总控哨兵」签名不对"
        SentinelClient.Link.DISABLED -> "「总控哨兵」的无障碍没开"
        SentinelClient.Link.UNKNOWN -> "「总控哨兵」还没有 v2 证据（可能不是 v2 版本，健康能力不足）"
        SentinelClient.Link.SILENT -> "「总控哨兵」开着但叫不应"
    }

    private fun warn(context: Context, enabled: Boolean, verdict: HealthVerdict) {
        val store = context.zk.store
        val now = System.currentTimeMillis()
        val minute = java.time.Instant.ofEpochMilli(now).atZone(store.zone).let { it.hour * 60 + it.minute }
        if (com.zongkong.core.hq.Hq.quiet(store.config.value.hq, now, store.zone)) return
        if (!store.firstAlert("acc-warn:${minute / 20}")) return
        val title = when {
            !enabled -> "总控的无障碍服务被关了"
            verdict.status == HealthStatus.SUSPECT -> "总控的执行线程开始不正常"
            else -> "总控的执行线程不再响应"
        }
        val text = buildString {
            append(if (enabled) "现在拦不住、锁不住、看不到。" else "现在拦不住、锁不住、看不到。")
            append("（${verdict.reason}）")
            append("点这里去权限页，关掉再打开一次。")
            if (!canWrite(context)) append("嫌麻烦：在电脑上执行一次权限页里的命令，以后它自己会修。")
        }
        Notifications.hqCall(context, title, text, fullScreen = false, route = "settings/perm")
        Buzz.once(context, Buzz.Level.FIRM)
        store.logWake("$title：已提醒")
        if (!reminded) {
            reminded = true
            Diag.log(context, if (enabled) "stale" else "disabled", "已提醒：${verdict.reason}")
        }
    }
}

/**
 * 常驻前台服务：让总控所在的进程不容易被系统清掉（无障碍服务也在这个进程里），
 * 并每分钟看一眼真实的健康证据（[SelfHeal.check]）与掉线提醒（[Watchdog.check]）。
 * 通知就是那条常驻状态条。
 */
class KeepAliveService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = Notifications.statusNotification(this, "总控在运行", "看着手机和你的计划。")
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notifications.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Notifications.ID_STATUS, n)
        } catch (e: Exception) {
            Log.w("KeepAlive", "开不了前台服务", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
                s.launch {
                    // 刚起来时无障碍服务可能还在连：先等一分钟
                    delay(60_000)
                    while (isActive) {
                        try {
                            SelfHeal.check(this@KeepAliveService)
                        } catch (e: Throwable) {
                            Log.w("KeepAlive", "检查出错", e)
                        }
                        try {
                            Watchdog.check(this@KeepAliveService)
                        } catch (e: Throwable) {
                            Log.w("KeepAlive", "看门狗出错", e)
                        }
                        // 伙伴：无障碍掉了也照样能主动找你（进程被闹钟拉起来时也走这里）
                        try {
                            this@KeepAliveService.zk.buddy.maybeTick()
                        } catch (e: Throwable) {
                            Log.w("KeepAlive", "伙伴出错", e)
                        }
                        // 本地规则兜底与紧急暂停：不依赖无障碍 Handler，也不依赖网络（T05）。
                        try {
                            com.zongkong.app.healthwire.LocalGuardian.tick(this@KeepAliveService)
                        } catch (e: Throwable) {
                            Log.w("KeepAlive", "本地守卫出错", e)
                        }
                        delay(60_000)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        /** 能开就开（后台开不了时系统会拒绝，接住就行：下次从前台或闹钟再开）。 */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w("KeepAlive", "现在开不了常驻服务", e)
            }
        }
    }
}

