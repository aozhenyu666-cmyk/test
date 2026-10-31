package com.zongkong.app.guard

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.io.File

/**
 * 死因日志：每次进程起来、每次无障碍接上，把系统记下的「上次进程是怎么死的」读出来，写进眼睛日志。
 *
 * - 只读系统已有的记录（Android 11+ 的 ApplicationExitInfo），不需要任何新权限；
 * - 读过的不重复记（按时间戳去重）；第一次装上这一版时，会把系统还留着的历史死亡一次性补上；
 * - ANR 和原生崩溃，系统会附带现场（线程栈），存到 filesDir/zk/exits/，分享日志时一起带上路径；
 * - 任何错误都咽掉：日志自己出问题不能拖垮总控。
 */
object ExitLog {
    private const val TAG = "ExitLog"
    private const val PREFS = "zk_exit"
    private const val KEY_SEEN = "last_seen"
    private const val KEY_RECENT = "recent"
    private const val KEEP = 40
    private const val TRACE_MAX = 512 * 1024
    private val lock = Any()

    fun dir(context: Context): File = File(File(context.filesDir, "zk"), "exits").apply { mkdirs() }

    /** 在后台线程里读一遍（进程启动时用，不拖慢启动）。 */
    fun collectAsync(context: Context, where: String) {
        val app = context.applicationContext
        runCatching { Thread({ collect(app, where) }, "exit-log").apply { isDaemon = true }.start() }
    }

    /** 读新的死亡记录，写进眼睛日志。返回这次新记下的条数。 */
    fun collect(context: Context, where: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return try {
            synchronized(lock) { collectLocked(context, where) }
        } catch (e: Throwable) {
            Log.w(TAG, "读死因失败", e)
            0
        }
    }

    private fun collectLocked(context: Context, where: String): Int {
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_SEEN, 0L)
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 32)
        val byTime = infos.associateBy { it.timestamp }
        val fresh = ExitWhy.fresh(infos.map { toExit(it, "") }, seen)
        if (fresh.isEmpty()) return 0
        val recent = loadRecent(prefs).toMutableList()
        for (raw in fresh) {
            val info = byTime[raw.at]
            val (file, hint) = info?.let { saveTrace(context, it) } ?: ("" to "")
            val e = raw.copy(
                traceFile = file,
                description = listOf(raw.description, hint).filter { it.isNotBlank() }.joinToString("；"),
            )
            Diag.log(context, "exit", "${ExitWhy.line(e)}｜发生在 ${clock(e.at)}，${where}时读到")
            recent += e
        }
        val trimmed = recent.sortedBy { it.at }.takeLast(KEEP)
        prefs.edit()
            .putLong(KEY_SEEN, fresh.maxOf { it.at })
            .putString(KEY_RECENT, trimmed.joinToString("\n") { encode(it) })
            .apply()
        pruneTraces(context)
        return fresh.size
    }

    /** 最近记下的死亡记录（从旧到新）。 */
    fun recent(context: Context): List<ExitWhy.Exit> = try {
        loadRecent(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    } catch (e: Throwable) {
        emptyList()
    }

    /** 一句话结论：最近几次进程死亡，主凶是谁、该怎么修。没有记录返回空串。 */
    fun verdict(context: Context): String = runCatching {
        ExitWhy.verdict(recent(context), context.packageName)
    }.getOrDefault("")

    /**
     * 系统自己给这个无障碍服务打的「出过故障」标记（设置里显示「无法运行」的那个）。
     * 这是隐藏字段，有的系统读不到：读不到返回 null。
     */
    fun systemMarkedCrashed(context: Context): Boolean? = try {
        val am = context.getSystemService(AccessibilityManager::class.java)
        val me = android.content.ComponentName(context, GuardService::class.java)
        val info = am?.installedAccessibilityServiceList?.firstOrNull {
            it.resolveInfo?.serviceInfo?.let { s -> s.packageName == me.packageName && s.name == me.className } == true
        }
        info?.let { it.javaClass.getField("crashed").getBoolean(it) }
    } catch (e: Throwable) {
        null
    }

    /** 手机和总控此刻的环境：分享日志时放在最前面，排查不用再来回问。 */
    fun snapshot(context: Context): String = buildString {
        fun row(k: String, v: Any?) = append(k).append("：").append(v ?: "读不到").append('\n')
        val pm = context.packageManager
        val version = runCatching { pm.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        row("手机", "${Build.BRAND} ${Build.MODEL}（${Build.MANUFACTURER}）")
        row("系统", "Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）· ${Build.DISPLAY}")
        row("总控版本", version)
        row("无障碍开关", if (Diag.accessibilityEnabled(context)) "开着" else "关着")
        row(
            "系统标记无法运行",
            when (systemMarkedCrashed(context)) {
                true -> "是（进程死过、系统还没重新拉起）"
                false -> "否"
                null -> "这个系统读不到"
            },
        )
        row("电池优化", if (Diag.ignoringBattery(context)) "已忽略" else "没忽略（容易被清）")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(ActivityManager::class.java)
            row("后台受限", runCatching { if (am.isBackgroundRestricted) "是（系统限制后台，必死）" else "否" }.getOrNull())
            val usm = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
            row("待机分组", runCatching { bucketName(usm.appStandbyBucket) }.getOrNull())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarm = context.getSystemService(android.app.AlarmManager::class.java)
            row("准点闹钟", runCatching { if (alarm.canScheduleExactAlarms()) "允许" else "不允许" }.getOrNull())
        }
        row("自动修复权限", if (com.zongkong.app.system.SelfHeal.canWrite(context)) "已授权" else "没授权（adb 一次就行）")
        row("哨兵", runCatching { SentinelClient.describe(context) }.getOrNull())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) row("死因记录", "Android 11 以下系统不提供")
        val v = verdict(context)
        row("死因结论", v.ifBlank { "还没有可判的死亡记录" })
    }

    // ---------- 内部 ----------

    private fun toExit(i: ApplicationExitInfo, traceFile: String) = ExitWhy.Exit(
        at = i.timestamp,
        process = i.processName.orEmpty(),
        reason = i.reason,
        status = i.status,
        importance = i.importance,
        description = i.description.orEmpty(),
        pssKb = i.pss,
        rssKb = i.rss,
        traceFile = traceFile,
    )

    /** ANR / 原生崩溃：把系统附带的现场存下来，返回（文件名, 一句「主线程卡在哪」）。 */
    private fun saveTrace(context: Context, i: ApplicationExitInfo): Pair<String, String> {
        if (i.reason != ApplicationExitInfo.REASON_ANR && i.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return "" to ""
        return try {
            val bytes = i.traceInputStream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < TRACE_MAX) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } ?: return "" to ""
            val name = "${i.timestamp}-${if (i.reason == ApplicationExitInfo.REASON_ANR) "anr" else "native"}.txt"
            File(dir(context), name).writeBytes(bytes)
            val hint = if (i.reason == ApplicationExitInfo.REASON_ANR) ExitWhy.mainThreadHint(String(bytes)) else ""
            name to hint
        } catch (e: Throwable) {
            Log.w(TAG, "存现场失败", e)
            "" to ""
        }
    }

    private fun pruneTraces(context: Context) {
        runCatching {
            dir(context).listFiles()?.sortedByDescending { it.name }?.drop(12)?.forEach { it.delete() }
        }
    }

    private fun bucketName(b: Int): String = when (b) {
        5 -> "豁免（最好）"
        10 -> "活跃"
        20 -> "工作集"
        30 -> "常用"
        40 -> "很少用（后台会被严格限制）"
        45 -> "受限（后台基本跑不了）"
        50 -> "从不"
        else -> "未知($b)"
    }

    private fun clock(at: Long): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .format(java.time.Instant.ofEpochMilli(at).atZone(com.zongkong.core.DayClock.BEIJING))
    }.getOrDefault(at.toString())

    private fun encode(e: ExitWhy.Exit): String = listOf(
        e.at, e.reason, e.status, e.importance, e.pssKb, e.rssKb, e.process, e.traceFile, e.description,
    ).joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') }

    private fun loadRecent(prefs: android.content.SharedPreferences): List<ExitWhy.Exit> =
        (prefs.getString(KEY_RECENT, "") ?: "").lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 9) return@mapNotNull null
            ExitWhy.Exit(
                at = p[0].toLongOrNull() ?: return@mapNotNull null,
                reason = p[1].toIntOrNull() ?: 0,
                status = p[2].toIntOrNull() ?: 0,
                importance = p[3].toIntOrNull() ?: 0,
                pssKb = p[4].toLongOrNull() ?: 0,
                rssKb = p[5].toLongOrNull() ?: 0,
                process = p[6],
                traceFile = p[7],
                description = p.drop(8).joinToString(" "),
            )
        }
}
