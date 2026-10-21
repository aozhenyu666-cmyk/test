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
import android.provider.Settings
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService
import com.zongkong.app.ui.PermissionStatus
import com.zongkong.app.zk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍服务“开着但没在运行”（系统把它停了，设置里还显示开着）：
 *  - 给过“写安全设置”权限（电脑上 adb 执行一次 pm grant，见权限页）：自己把它关掉再打开，系统重新挂上，不用你动手。
 *  - 没给：每 20 分钟大声提醒一次（高优先级通知 + 震动），点开就是权限页。
 * 只修“开着但没在运行”；你自己在设置里关掉的，不替你打开。
 */
object SelfHeal {
    private const val TAG = "SelfHeal"
    const val GRANT_COMMAND = "adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS"
    @Volatile private var lastHeal = 0L
    @Volatile private var heals = 0
    /** 上一次判定出来的状态：running / stale / disabled。只在状态变化时写诊断，免得每分钟刷一条。 */
    @Volatile private var state = ""
    /** 这次掉线是不是已经提醒过了（诊断里只记第一次提醒）。 */
    @Volatile private var reminded = false

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 把总控的无障碍服务从列表里拿掉、再放回去：系统会重新挂上它。 */
    suspend fun rebind(context: Context): Boolean {
        if (!canWrite(context)) return false
        return try {
            val cr = context.contentResolver
            val me = ComponentName(context, GuardService::class.java)
            val others = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            delay(1_200)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (e: Exception) {
            Log.w(TAG, "重新挂上无障碍服务失败", e)
            false
        }
    }

    /** 看一眼：在跑就算了；开着没跑就修（或提醒）；整个关掉了就提醒。每次判定都记进诊断。 */
    suspend fun check(context: Context) {
        val store = context.zk.store
        if (store.config.value.pausedUntil > System.currentTimeMillis()) return
        checkSentinel(context)
        if (PermissionStatus.guardRunning(context)) {
            heals = 0
            state = "running"
            reminded = false
            return
        }
        val now = System.currentTimeMillis()
        val enabled = PermissionStatus.accessibility(context)
        // 诊断：只在“运行 / 开着没跑 / 被关”之间变化时记一条，否则每分钟一条会把时间线刷满
        val nowState = if (enabled) "stale" else "disabled"
        if (state != nowState) {
            state = nowState
            if (enabled) Diag.log(context, "stale", "开着但没在运行")
            else Diag.log(context, "disabled", "无障碍在设置里是关着的")
        }
        // 连着修了三次还不行（多半是系统彻底不让它跑），改成提醒，免得一直开关
        if (enabled && canWrite(context) && heals < 3 && now - lastHeal > 3 * 60_000L) {
            lastHeal = now
            heals++
            if (rebind(context)) {
                Diag.log(context, "rebind_ok", "自动重新挂上（第 $heals 次）")
                state = "running"
                reminded = false
                store.logWake("无障碍开着但没在运行：自动重新挂上（第 $heals 次）")
                return
            }
            Diag.log(context, "rebind_fail", "自动重新挂上没成功（第 $heals 次）")
        }
        warn(context, enabled, now)
    }

    @Volatile private var lastSentinelHeal = 0L

    /** 反过来盯哨兵：开着、心跳却停了 3 分钟以上，有权限就把它重新挂上（10 分钟最多一次）。 */
    private fun checkSentinel(context: Context) {
        val now = System.currentTimeMillis()
        if (!com.zongkong.app.guard.SentinelLink.enabled(context, com.zongkong.app.guard.SentinelService::class.java)) return
        if (now - com.zongkong.app.guard.SentinelLink.sentinelBeat(context) < 3 * 60_000L) return
        if (!canWrite(context) || now - lastSentinelHeal < 10 * 60_000L) return
        lastSentinelHeal = now
        if (com.zongkong.app.guard.SentinelLink.rebind(context, com.zongkong.app.guard.SentinelService::class.java)) {
            Diag.log(context, "rebind_ok", "哨兵开着没在跑：总控把它重新挂上")
        }
    }

    private fun warn(context: Context, enabled: Boolean, now: Long) {
        val store = context.zk.store
        val minute = java.time.Instant.ofEpochMilli(now).atZone(store.zone).let { it.hour * 60 + it.minute }
        if (com.zongkong.core.hq.Hq.quiet(store.config.value.hq, now, store.zone)) return
        if (!store.firstAlert("acc-warn:${minute / 20}")) return
        val title = if (enabled) "总控“开着但没在运行”" else "总控的无障碍服务被关了"
        val text = (if (enabled) "系统把它停了：现在拦不住、锁不住、看不到。" else "现在拦不住、锁不住、看不到。") +
            "点这里去权限页，关掉再打开一次。" +
            if (canWrite(context)) "" else "嫌麻烦：在电脑上执行一次权限页里的命令，以后它自己会修。"
        Notifications.hqCall(context, title, text, fullScreen = false, route = "settings/perm")
        Buzz.once(context, Buzz.Level.FIRM)
        store.logWake("$title：已提醒")
        // 诊断只记这次掉线的第一次提醒（提醒本身每 20 分钟一格，不必每次重复记）
        if (!reminded) {
            reminded = true
            if (enabled) Diag.log(context, "stale", "已提醒：开着但没在运行，去权限页关掉再打开")
            else Diag.log(context, "disabled", "已提醒：无障碍被关掉了，去权限页重新打开")
        }
    }
}

/**
 * 常驻前台服务：让总控所在的进程不容易被系统清掉（无障碍服务也在这个进程里），
 * 并每分钟看一眼无障碍服务还在不在跑（[SelfHeal.check]）。通知就是那条常驻状态条。
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
                        try { SelfHeal.check(this@KeepAliveService) } catch (e: Throwable) { Log.w("KeepAlive", "检查出错", e) }
                        // 看门狗：眼睛没在运行超过 60 秒就提醒（每 60 秒问一次）
                        try { Watchdog.check(this@KeepAliveService) } catch (e: Throwable) { Log.w("KeepAlive", "看门狗出错", e) }
                        // 墨灵授权内的倒计时：总控在倒计时里被杀过的话，这里补上
                        try { com.zongkong.app.link.MolingBridge.runDue(this@KeepAliveService) } catch (e: Throwable) { Log.w("KeepAlive", "墨灵的倒计时出错", e) }
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
