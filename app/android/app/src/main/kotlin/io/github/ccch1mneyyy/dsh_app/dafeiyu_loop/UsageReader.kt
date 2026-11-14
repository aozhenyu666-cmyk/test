package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/** One app's foreground statistics read from Android's [UsageStatsManager]. */
class AppUsage(val packageName: String, val label: String?, val minutes: Long, val opens: Int)

/** One foreground switch timestamp -> package name. */
class UsageSwitch(val at: Long, val packageName: String)

/**
 * Result of reading [UsageStatsManager.queryEvents] for a recent window.
 * Null return from [UsageReader.usage] means USAGE_STATS permission has not been granted.
 */
class UsageReport(
    val rows: List<AppUsage>,
    val switches: List<UsageSwitch>,
    val current: String? = null,
    val currentSince: Long = 0L,
)

object UsageReader {
    private const val MAX_USAGE_MINUTES = 7 * 24 * 60

    fun usage(app: Context, minutes: Int, now: Long): UsageReport? {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return null
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = now - minutes.coerceIn(1, MAX_USAGE_MINUTES) * 60_000L
        val totals = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val switches = ArrayList<UsageSwitch>()
        var open: String? = null
        var since = 0L
        var lastOpened: String? = null
        val e = UsageEvents.Event()
        fun close(at: Long) {
            val p = open ?: return
            if (at > since) totals[p] = (totals[p] ?: 0L) + (at - since)
            open = null
        }
        val events = usm.queryEvents(start, now)
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                // ACTIVITY_RESUMED (1)
                1 -> {
                    close(e.timeStamp)
                    open = pkg
                    since = e.timeStamp
                    switches.add(UsageSwitch(e.timeStamp, pkg))
                    if (pkg != lastOpened) {
                        opens[pkg] = (opens[pkg] ?: 0) + 1
                        lastOpened = pkg
                    }
                }
                // ACTIVITY_PAUSED (2), SCREEN_NON_INTERACTIVE (16), DEVICE_SHUTDOWN (26)
                2, 16, 26 -> {
                    if (open == pkg || e.eventType != 2) close(e.timeStamp)
                }
            }
        }
        val current = open
        val currentSince = if (current != null) since else 0L
        close(now)
        val pm = app.packageManager
        val rows = (totals.keys + opens.keys)
            .map { pkg ->
                val lbl = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                AppUsage(pkg, lbl, (totals[pkg] ?: 0L) / 60_000L, opens[pkg] ?: 0)
            }
            .filter { it.minutes > 0 || it.opens > 0 }
            .sortedByDescending { it.minutes }
        return UsageReport(rows, switches.asReversed(), current, currentSince)
    }
}
