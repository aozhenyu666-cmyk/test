package com.behaviordept.app.guard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId

/**
 * 读取各 App 每天在前台的时长（UsageStatsManager）。需要用户在系统设置里打开“使用情况访问权限”。
 * 用事件流自己算时长，而不是用系统的日汇总：汇总的边界不准，跨零点会算错。
 */
object UsageReader {
    fun hasPermission(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 某一天各 App 的前台分钟数（四舍五入）。没有权限时返回空表。 */
    fun minutesOn(context: Context, date: LocalDate, now: Long = System.currentTimeMillis()): Map<String, Int> {
        if (!hasPermission(context)) return emptyMap()
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        if (end <= start) return emptyMap()
        return foregroundMillis(context, start, end).mapValues { ((it.value + 30_000) / 60_000).toInt() }.filterValues { it > 0 }
    }

    private fun foregroundMillis(context: Context, start: Long, end: Long): Map<String, Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        // 往前多读几个小时，接上零点前就已经打开的 App。
        val events = usm.queryEvents(start - LOOKBACK_MS, end)
        val e = UsageEvents.Event()
        val open = HashMap<String, Long>() // key = 包名/类名
        val intervals = HashMap<String, MutableList<LongArray>>()

        fun close(key: String, at: Long) {
            val from = open.remove(key) ?: return
            val pkg = key.substringBefore('/')
            val s = maxOf(from, start)
            val t = minOf(at, end)
            if (t > s) intervals.getOrPut(pkg) { mutableListOf() }.add(longArrayOf(s, t))
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val key = "${e.packageName}/${e.className}"
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open.putIfAbsent(key, e.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> close(key, e.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    open.keys.toList().forEach { close(it, e.timeStamp) }
            }
        }
        open.keys.toList().forEach { close(it, end) }
        // 同一个 App 的多个页面时间段可能重叠，合并后再求和。
        return intervals.mapValues { (_, list) -> mergedLength(list) }
    }

    private fun mergedLength(list: List<LongArray>): Long {
        val sorted = list.sortedBy { it[0] }
        var total = 0L
        var curS = -1L
        var curE = -1L
        for (iv in sorted) {
            if (iv[0] > curE) {
                if (curE > curS) total += curE - curS
                curS = iv[0]
                curE = iv[1]
            } else if (iv[1] > curE) {
                curE = iv[1]
            }
        }
        if (curE > curS) total += curE - curS
        return total
    }

    /** 能从桌面打开的 App（用于选择规则管哪些 App）。 */
    fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    fun appLabel(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private const val LOOKBACK_MS = 6 * 60 * 60 * 1000L
}
