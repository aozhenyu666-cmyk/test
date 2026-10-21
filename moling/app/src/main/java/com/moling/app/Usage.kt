package com.moling.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** 此刻前台是谁、连续多久（M10）。 */
data class Foreground(val pkg: String, val label: String, val minutes: Int)

/**
 * 看前台应用（M10）：用系统的「使用情况访问」，不是无障碍。
 *
 * 只读最近 10 分钟的事件：找最后一个“进入前台”的包，再往前数它连续在前台了多久。
 * 没权限 / 读不到一律返回 null，调用方写进 unknown、调试页写清楚，不假装看见。
 */
object Usage {
    /** 往前看多久（分钟）。 */
    private const val WINDOW_MS = 10 * 60_000L

    /** 有没有「使用情况访问」权限（用户在系统设置里手动打开）。 */
    fun allowed(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun foreground(context: Context, now: Long = System.currentTimeMillis()): Foreground? {
        if (!allowed(context)) return null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = runCatching { usm.queryEvents(now - WINDOW_MS, now) }.getOrNull() ?: return null
        val e = UsageEvents.Event()
        var current = ""
        var since = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // MOVE_TO_FOREGROUND 在 API 29 叫 ACTIVITY_RESUMED，值都是 1，用旧常量兼容到 minSdk 26。
            if (e.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND) continue
            val pkg = e.packageName.orEmpty()
            if (pkg.isBlank()) continue
            // 同包连续切 Activity 不重置起点；换了包才重算“连续多久”。
            if (pkg != current) {
                current = pkg
                since = e.timeStamp
            }
        }
        if (current.isBlank()) return null
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(current, 0)).toString()
        }.getOrDefault(current)
        val minutes = ((now - since).coerceAtLeast(0) / 60_000L).toInt()
        return Foreground(current, label, minutes)
    }
}
