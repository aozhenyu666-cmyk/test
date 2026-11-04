package com.dshdriver.app

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.dshdriver.core.Verdict

/**
 * 无障碍之外的第二条命：用系统「使用情况访问」判断前台 App。
 *
 * 无障碍被系统摘掉、还没写回去的那段时间，如果这里能看到目标 App 在前台，仍然把答题门拉起来。
 * 它也是"无障碍到底还活着没有"的旁证：查询本身不需要无障碍服务。
 *
 * 需要用户在系统设置里给「使用情况访问」；没给就静默跳过，不影响无障碍那条主路。
 */
object UsageMonitor {
    private const val NOTE_ID = 5
    private const val CHANNEL = "guard_block"
    private const val LOOKBACK_MS = 30 * 60_000L
    private val main = Handler(Looper.getMainLooper())

    fun hasPermission(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 最近一次回到前台的应用；读不到返回 null，绝不猜。 */
    fun foreground(context: Context): String? = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - LOOKBACK_MS, end) ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        var visible = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val resumed = if (Build.VERSION.SDK_INT >= 29) {
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val paused = if (Build.VERSION.SDK_INT >= 29) {
                e.eventType == UsageEvents.Event.ACTIVITY_PAUSED
            } else {
                @Suppress("DEPRECATION")
                e.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
            }
            if (resumed && e.packageName.isNotBlank()) {
                last = e.packageName
                visible = true
            } else if (paused && e.packageName == last) {
                // 最后一条是"它被暂停了"就别再当成前台；锁屏、回桌面、被别的应用盖住都可能走到这里
                visible = false
            }
        }
        last.takeIf { visible }
    }.getOrNull()

    /**
     * 由 [GuardService] 的常驻循环调用。
     * 只在无障碍真正不可用时才走这条：它比无障碍反应慢、也更耗电，不该当第二条主路。
     */
    fun check(context: Context) {
        if (!Intercept.enabled(context)) return
        if (!hasPermission(context)) return
        if (GateActivity.visible) return
        if (!AwarenessRuntime.screenAvailable()) return
        // 无障碍开着且服务已连上时，主路比这里快得多
        if (A11yGuard.isOn(context) && Engine.watchConnected) return
        val pkg = foreground(context) ?: return
        if (pkg == context.packageName) return
        val now = System.currentTimeMillis()
        if (Intercept.decide(context, pkg, now) != Verdict.BLOCK) return
        if (now - GateActivity.lastShownAt < 5_000L) return
        if (!Intercept.claimTrigger(pkg, now)) return
        val app = context.applicationContext
        // 后台启动 Activity 在 Android 10+ 可能被静默丢弃：先尝试拉起，1.2 秒后没看到门就补通知。
        runCatching { app.startActivity(GateActivity.intent(app, pkg)) }
        main.postDelayed({ if (!GateActivity.visible) notifyBlocked(app, pkg) }, 1_200L)
    }

    /**
     * 后台启动 Activity 被系统拦下时的退路：发一条高优先级通知，点一下进答题页。
     * 不做 full-screen intent——Android 14 对非通话类应用的强弹限制很严，通知更稳。
     */
    fun notifyBlocked(context: Context, pkg: String) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "拦截提醒", NotificationManager.IMPORTANCE_HIGH))
            val label = runCatching {
                context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val pi = PendingIntent.getActivity(
                context, pkg.hashCode(), GateActivity.intent(context, pkg),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_driver)
                .setContentTitle("拦下了 $label")
                .setContentText("点开答一道题才能继续。")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTE_ID, n)
        }
    }

    /** 拦截页真的起来了就撤掉兜底通知。 */
    fun clearBlocked(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) }
    }
}
