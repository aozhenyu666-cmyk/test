package huilu.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings as SysSettings
import huilu.core.Foreground
import huilu.core.Level
import huilu.core.Observation
import huilu.core.Platform
import huilu.core.SensorState

/**
 * 观察手机：前台 App 来自 UsageStatsManager 的事件流。
 * 它在所有 Android 8+ 设备上都可用、不需要 root，只需要用户手动授予"使用情况访问权限"。
 */
class AndroidPlatform(private val ctx: Context, private val deviceReady: () -> Boolean) : Platform {
    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val pm = ctx.packageManager
    private val labels = HashMap<String, String>()
    private val neutral: Set<String> by lazy { computeNeutral() }

    fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // -------------------------------------------------------------- 前台

    private var curPkg: String? = null
    private var curSince = 0L
    private var pausedAt = 0L
    private var lastTs = 0L

    @Synchronized
    override fun foreground(now: Long): Foreground {
        if (!hasUsageAccess()) return Foreground(null, now, SensorState.NO_PERMISSION)
        val from = if (lastTs == 0L || now - lastTs > LOOKBACK) now - LOOKBACK else lastTs + 1
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            lastTs = maxOf(lastTs, e.timeStamp)
            step(e.eventType, e.packageName, e.timeStamp)
        }
        // 暂停后很久都没有新的 App 进入前台：当作没在用
        if (curPkg != null && pausedAt > 0 && now - pausedAt > 10_000) { curPkg = null; curSince = pausedAt; pausedAt = 0 }
        return Foreground(curPkg, if (curSince == 0L) now else curSince)
    }

    private fun step(type: Int, pkg: String?, ts: Long) {
        when (type) {
            RESUMED -> {
                if (pkg != curPkg) { curPkg = pkg; curSince = ts }
                pausedAt = 0
            }
            PAUSED -> if (pkg == curPkg) pausedAt = ts
            SCREEN_OFF, KEYGUARD_SHOWN -> if (curPkg != null) { curPkg = null; curSince = ts; pausedAt = 0 }
        }
    }

    // -------------------------------------------------------------- 一段时间的使用情况

    override fun observe(from: Long, to: Long): Observation {
        if (!hasUsageAccess()) return Observation.unknown(from, to, SensorState.NO_PERMISSION)
        val apps = LinkedHashMap<String, Long>()
        val first = LinkedHashMap<String, Long>()
        var pkg: String? = null
        var segStart = from - LOOKBACK
        var paused = 0L
        fun close(end: Long) {
            val p = pkg ?: return
            val a = maxOf(segStart, from)
            val b = minOf(end, to)
            if (b > a) {
                apps[p] = (apps[p] ?: 0) + (b - a)
                if (p !in first) first[p] = a
            }
        }
        val ev = usm.queryEvents(from - LOOKBACK, to)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val ts = e.timeStamp
            if (pkg != null && paused > 0 && ts - paused > 10_000) { close(paused); pkg = null; paused = 0 }
            when (e.eventType) {
                RESUMED -> if (e.packageName != pkg) { close(ts); pkg = e.packageName; segStart = ts; paused = 0 } else paused = 0
                PAUSED -> if (e.packageName == pkg) paused = ts
                SCREEN_OFF, KEYGUARD_SHOWN -> if (pkg != null) { close(ts); pkg = null; paused = 0 }
            }
        }
        if (pkg != null && paused > 0 && to - paused > 10_000) close(paused) else close(to)
        return Observation(from, to, apps, first)
    }

    // -------------------------------------------------------------- 能力边界

    override fun available(): Set<Level> {
        val s = mutableSetOf(Level.NOTIFY)
        if (SysSettings.canDrawOverlays(ctx)) s += Level.INTERRUPT
        val device = deviceReady()
        if (GuardService.instance != null || device) { s += Level.HOME; s += Level.BLOCK }
        if (device) s += Level.DEVICE
        return s
    }

    override fun label(pkg: String): String = labels.getOrPut(pkg) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg.substringAfterLast('.') }
    }

    override fun isNeutral(pkg: String) = pkg in neutral

    private fun computeNeutral(): Set<String> {
        val s = mutableSetOf(ctx.packageName, "android", "com.android.systemui", "com.android.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).forEach { s += it.activityInfo.packageName }
        return s
    }

    /** 可以启动的 App（用于选择目标 App / 娱乐 App）。 */
    fun launchableApps(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != ctx.packageName }
            .map { it to label(it) }.sortedBy { it.second }
    }

    companion object {
        const val LOOKBACK = 3 * 3600_000L
        const val RESUMED = 1        // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
        const val PAUSED = 2         // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
        const val SCREEN_OFF = 16    // SCREEN_NON_INTERACTIVE (API 28)
        const val KEYGUARD_SHOWN = 17
    }
}
