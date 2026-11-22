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

/** Time-window bucket usage summary (e.g. "08:00-10:00"). */
class TimeSlotApp(val packageName: String, val label: String?, val minutes: Long)

class TimeSlotUsage(
    val slot: String,
    val minutes: Long,
    val switches: Int,
    val top: List<TimeSlotApp>,
)

/**
 * Result of reading [UsageStatsManager.queryEvents] for a recent window.
 * Null return from [UsageReader.usage] means USAGE_STATS permission has not been granted.
 */
class UsageReport(
    val rows: List<AppUsage>,
    val switches: List<UsageSwitch>,
    val current: String? = null,
    val currentSince: Long = 0L,
    val timeSlots: List<TimeSlotUsage> = emptyList(),
)

object UsageReader {
    private const val MAX_USAGE_MINUTES = 7 * 24 * 60
    private const val TWO_HOURS_MS = 2 * 3_600_000L

    private fun readEvents(
        usm: UsageStatsManager,
        start: Long,
        now: Long,
        bucketMs: Long,
    ): Quad? {
        val totals = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val switches = ArrayList<UsageSwitch>()
        val slotTotals = HashMap<Int, HashMap<String, Long>>()
        val slotSwitches = HashMap<Int, Int>()
        var open: String? = null
        var since = 0L
        var lastOpened: String? = null
        val e = UsageEvents.Event()
        fun addRange(pkg: String, fromMs: Long, toMs: Long) {
            if (toMs <= fromMs) return
            totals[pkg] = (totals[pkg] ?: 0L) + (toMs - fromMs)
            if (bucketMs <= 0L) return
            var cursor = fromMs.coerceAtLeast(start)
            while (cursor < toMs) {
                val idx = (((cursor - start) / TWO_HOURS_MS).toInt()).coerceIn(0, 11)
                val endOfSlot = start + (idx + 1) * TWO_HOURS_MS
                val segEnd = minOf(toMs, endOfSlot)
                val map = slotTotals.getOrPut(idx) { HashMap() }
                map[pkg] = (map[pkg] ?: 0L) + (segEnd - cursor)
                cursor = segEnd
            }
        }
        fun close(at: Long) {
            val p = open ?: return
            addRange(p, since, at)
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
                    if (bucketMs > 0L) {
                        val idx = (((e.timeStamp - start) / TWO_HOURS_MS).toInt()).coerceIn(0, 11)
                        slotSwitches[idx] = (slotSwitches[idx] ?: 0) + 1
                    }
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
        return Quad(totals, opens, switches, current, currentSince, slotTotals, slotSwitches)
    }

    private class Quad(
        val totals: HashMap<String, Long>,
        val opens: HashMap<String, Int>,
        val switches: ArrayList<UsageSwitch>,
        val current: String?,
        val currentSince: Long,
        val slotTotals: HashMap<Int, HashMap<String, Long>>,
        val slotSwitches: HashMap<Int, Int>,
    )

    fun usage(app: Context, minutes: Int, now: Long): UsageReport? {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return null
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = now - minutes.coerceIn(1, MAX_USAGE_MINUTES) * 60_000L
        val q = readEvents(usm, start, now, 0L) ?: return null
        val pm = app.packageManager
        fun labelOf(pkg: String): String? =
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        val rows = (q.totals.keys + q.opens.keys)
            .map { pkg -> AppUsage(pkg, labelOf(pkg), (q.totals[pkg] ?: 0L) / 60_000L, q.opens[pkg] ?: 0) }
            .filter { it.minutes > 0 || it.opens > 0 }
            .sortedByDescending { it.minutes }
        return UsageReport(rows, q.switches.asReversed(), q.current, q.currentSince)
    }

    /** Full-day usage report combining queryEvents (timeline + opens + 2h slots) and queryUsageStats (kernel daily totals). */
    fun dailyReport(app: Context, dayStart: Long, now: Long): UsageReport? {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return null
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val safeStart = dayStart.coerceAtMost(now)
        val q = readEvents(usm, safeStart, now, TWO_HOURS_MS) ?: return null
        runCatching {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, safeStart, now).orEmpty()
            for (st in stats) {
                val pkg = st.packageName ?: continue
                if (st.lastTimeUsed < safeStart) continue
                val fg = st.totalTimeInForeground
                if (fg > (q.totals[pkg] ?: 0L)) q.totals[pkg] = fg
            }
        }
        val pm = app.packageManager
        val labelCache = HashMap<String, String?>()
        fun labelOf(pkg: String): String? = labelCache.getOrPut(pkg) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
        val rows = (q.totals.keys + q.opens.keys)
            .map { pkg -> AppUsage(pkg, labelOf(pkg), (q.totals[pkg] ?: 0L) / 60_000L, q.opens[pkg] ?: 0) }
            .filter { it.minutes > 0 || it.opens > 0 }
            .sortedByDescending { it.minutes }
        val slots = (0..11).mapNotNull { idx ->
            val pkgMap = q.slotTotals[idx]
            val sw = q.slotSwitches[idx] ?: 0
            if ((pkgMap == null || pkgMap.isEmpty()) && sw < 2) return@mapNotNull null
            val slotTotalMin = (pkgMap?.values?.sum() ?: 0L) / 60_000L
            if (slotTotalMin <= 0L && sw < 2) return@mapNotNull null
            val top = (pkgMap ?: emptyMap()).entries
                .map { (pkg, ms) -> TimeSlotApp(pkg, labelOf(pkg), ms / 60_000L) }
                .filter { it.minutes > 0L }
                .sortedByDescending { it.minutes }
                .take(4)
            val fromH = (idx * 2).toString().padStart(2, '0')
            val toH = (idx * 2 + 2).toString().padStart(2, '0')
            TimeSlotUsage("$fromH:00-$toH:00", slotTotalMin, sw, top)
        }
        return UsageReport(rows, q.switches.asReversed(), q.current, q.currentSince, slots)
    }
}
