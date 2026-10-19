package com.zongkong.app.guard

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.zongkong.core.DayClock
import java.io.File

/**
 * 眼睛的诊断记录：无障碍连着、掉了、被系统停了，每一次都记一笔。
 *
 * 存成一个纯文本文件（`filesDir/zk/diag.jsonl`，每行 `时刻|种类|说明`），只追加、超过 1500 行滚动保留最近的。
 * 目的是回答一个问题：“这次为什么失效了？”——不必连电脑翻 logcat。
 *
 * 不抛异常：写文件、读文件、看权限，出错都咽掉（诊断日志自己的问题不能拖垮守卫服务）。
 */
object Diag {
    private const val TAG = "Diag"
    private const val MAX_LINES = 1500
    private const val FILE_NAME = "diag.jsonl"

    /** 一条事件。`at` 是毫秒时间戳，`kind` 是固定种类，`detail` 是一句中文说明。 */
    data class DiagEvent(val at: Long, val kind: String, val detail: String = "")

    /**
     * 今天的摘要 + 现在能看到的状态。字段都有默认值，以后加字段不破坏调用方。
     */
    data class DiagSummary(
        /** 逻辑日（凌晨 4 点换日，和总控其它地方一致）。 */
        val date: String = "",
        /** 无障碍服务在系统设置里是开着的吗。 */
        val accessibilityEnabled: Boolean = false,
        /** GuardService 此刻真的在运行吗。 */
        val serviceRunning: Boolean = false,
        /** 电池优化是否已忽略（忽略 = 系统不容易把后台清掉）。 */
        val ignoringBattery: Boolean = false,
        val connected: Int = 0,
        val unbound: Int = 0,
        val destroyed: Int = 0,
        val offline: Int = 0,
        val stale: Int = 0,
        val disabled: Int = 0,
        val rebindOk: Int = 0,
        val rebindFail: Int = 0,
        val watchdog: Int = 0,
        /** 今天失联了几次（一次掉线算一次，重复的告警不重复算）。 */
        val lostCount: Int = 0,
        /** 今天失联的总时长。 */
        val lostTotalMs: Long = 0L,
        /** 今天最长的一次失联时长。 */
        val longestGapMs: Long = 0L,
        /** 最近一次失效的时刻（0 = 今天没有）。 */
        val lastLostAt: Long = 0L,
        /** 最近一次失效的种类（connected 之外的那几种）。 */
        val lastLostKind: String = "",
        /** 最近一次失效原因猜测：直接给用户看的一句话。 */
        val reasonGuess: String = "",
        /** 今天一共记了多少条事件。 */
        val eventsToday: Int = 0,
    )

    /** 固定的事件种类（界面和时间线按这个分好坏）。 */
    val KINDS = setOf(
        "connected", "unbound", "destroyed", "offline", "stale", "rebind_ok",
        "rebind_fail", "disabled", "bounce", "lockdown", "event_error", "watchdog",
    )

    private val lock = Any()

    // ---------- 读写 ----------

    /** 诊断文件：`filesDir/zk/diag.jsonl`。 */
    fun file(context: Context): File = File(File(context.filesDir, "zk").apply { mkdirs() }, FILE_NAME)

    /** 记一笔。任何错误都不会抛给调用方。 */
    fun log(context: Context, kind: String, detail: String = "") {
        try {
            synchronized(lock) {
                val f = file(context)
                val at = System.currentTimeMillis()
                f.appendText("$at|${escape(kind)}|${escape(detail)}\n")
                if (lineCount(f) > MAX_LINES) trim(f)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "写诊断日志失败", e)
        }
    }

    /** 最近的 [limit] 条事件，按时间从旧到新。文件坏了/不存在都当空。 */
    fun recent(context: Context, limit: Int = 300): List<DiagEvent> {
        val all = try {
            readAll(context)
        } catch (e: Throwable) {
            Log.w(TAG, "读诊断日志失败", e)
            emptyList()
        }
        if (limit <= 0) return emptyList()
        return all.takeLast(limit)
    }

    /** 今天的摘要（逻辑日：凌晨 4 点换日）：次数、最长失联、最近一次原因猜测 + 当前状态。 */
    fun summaryToday(context: Context, now: Long = System.currentTimeMillis()): DiagSummary {
        val enabled = accessibilityEnabled(context)
        val running = runCatching { GuardService.running }.getOrDefault(false)
        val ignoring = ignoringBattery(context)
        val zone = DayClock.BEIJING
        val date = DayClock.date(now, zone)
        val dayStart = DayClock.startOf(date, zone)
        val today = try {
            readAll(context).filter { it.at >= dayStart }
        } catch (e: Throwable) {
            emptyList()
        }

        var connected = 0
        var unbound = 0
        var destroyed = 0
        var offline = 0
        var stale = 0
        var disabled = 0
        var rebindOk = 0
        var rebindFail = 0
        var watchdog = 0
        var online = false
        var offAt = -1L
        var lostCount = 0
        var lostTotal = 0L
        var longest = 0L
        var lastLostAt = 0L
        var lastLostKind = ""

        fun closeGap(at: Long) {
            if (offAt < 0) return
            val gap = (at - offAt).coerceAtLeast(0)
            lostTotal += gap
            if (gap > longest) longest = gap
            offAt = -1L
        }

        fun lostAt(at: Long) {
            if (offAt < 0) {
                offAt = at
                lostCount++
            }
        }

        for (e in today) {
            when (e.kind) {
                "connected" -> {
                    if (!online) connected++
                    online = true
                    closeGap(e.at)
                }
                "rebind_ok" -> {
                    rebindOk++
                    online = true
                    closeGap(e.at)
                }
                "unbound" -> { unbound++; online = false; lostAt(e.at); lastLostAt = e.at; lastLostKind = e.kind }
                "destroyed" -> { destroyed++; online = false; lostAt(e.at); lastLostAt = e.at; lastLostKind = e.kind }
                "offline" -> { offline++; online = false; lostAt(e.at); lastLostAt = e.at; lastLostKind = e.kind }
                "stale" -> { stale++; online = false; lostAt(e.at); lastLostAt = e.at; lastLostKind = e.kind }
                "disabled" -> { disabled++; online = false; lostAt(e.at); lastLostAt = e.at; lastLostKind = e.kind }
                "rebind_fail" -> { rebindFail++; online = false; lastLostAt = e.at }
                "watchdog" -> {
                    watchdog++
                    lostAt(e.at)
                    lastLostAt = e.at
                }
            }
        }
        if (offAt >= 0) {
            val gap = (now - offAt).coerceAtLeast(0)
            lostTotal += gap
            if (gap > longest) longest = gap
        }

        return DiagSummary(
            date = date.toString(),
            accessibilityEnabled = enabled,
            serviceRunning = running,
            ignoringBattery = ignoring,
            connected = connected,
            unbound = unbound,
            destroyed = destroyed,
            offline = offline,
            stale = stale,
            disabled = disabled,
            rebindOk = rebindOk,
            rebindFail = rebindFail,
            watchdog = watchdog,
            lostCount = lostCount,
            lostTotalMs = lostTotal,
            longestGapMs = longest,
            lastLostAt = lastLostAt,
            lastLostKind = lastLostKind,
            reasonGuess = guessReason(enabled, running, ignoring, lastLostKind, lastLostAt, watchdog),
            eventsToday = today.size,
        )
    }

    /** 把日志复制成一个能直接分享/发出去的文本文件。返回复制出来的文件（失败也给个空文件，不抛）。 */
    fun export(context: Context): File {
        val dir = File(context.filesDir, "zk").apply { mkdirs() }
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .format(java.time.LocalDateTime.now(DayClock.BEIJING))
        val dst = File(dir, "diag-export-$stamp.txt")
        return try {
            val src = file(context)
            if (src.exists()) src.copyTo(dst, overwrite = true) else dst.writeText("")
            dst
        } catch (e: Throwable) {
            Log.w(TAG, "导出诊断日志失败", e)
            runCatching { dst.writeText("") }
            dst
        }
    }

    /** 清空日志（文件删掉，下次写自动重建）。 */
    fun clear(context: Context) {
        try {
            synchronized(lock) {
                file(context).delete()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "清空诊断日志失败", e)
        }
    }

    // ---------- 看当前状态 ----------

    /** 无障碍服务在系统设置里是不是开着的。 */
    fun accessibilityEnabled(context: Context): Boolean = try {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, GuardService::class.java)
        enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    } catch (e: Throwable) {
        Log.w(TAG, "读无障碍开关失败", e)
        false
    }

    /** 电池优化是否已忽略。 */
    fun ignoringBattery(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } catch (e: Throwable) {
        Log.w(TAG, "读电池优化状态失败", e)
        false
    }

    // ---------- 内部 ----------

    private fun readAll(context: Context): List<DiagEvent> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { parseLine(it) }
    }

    private fun parseLine(line: String): DiagEvent? {
        if (line.isBlank()) return null
        val i = line.indexOf('|')
        if (i <= 0) return null
        val j = line.indexOf('|', i + 1)
        if (j < 0) return null
        val at = line.substring(0, i).toLongOrNull() ?: return null
        val kind = unescape(line.substring(i + 1, j))
        val detail = unescape(line.substring(j + 1))
        return DiagEvent(at, kind, detail)
    }

    private fun lineCount(f: File): Int = try {
        f.readLines().size
    } catch (e: Throwable) {
        MAX_LINES + 1 // 读不了就当超了，交给 trim 去吞错误
    }

    /** 只留最近 MAX_LINES 行。 */
    private fun trim(f: File) {
        try {
            val lines = f.readLines()
            if (lines.size <= MAX_LINES) return
            val keep = lines.subList(lines.size - MAX_LINES, lines.size)
            val text = keep.joinToString("\n", postfix = "\n")
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "滚动裁剪诊断日志失败", e)
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("|", "\\|").replace("\r", "").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> sb.append('\n')
                    '|' -> sb.append('|')
                    '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun clockText(at: Long): String = try {
        val t = java.time.Instant.ofEpochMilli(at).atZone(DayClock.BEIJING).toLocalTime()
        "%02d:%02d".format(t.hour, t.minute)
    } catch (e: Throwable) {
        ""
    }

    private fun kindReason(kind: String): String = when (kind) {
        "destroyed" -> "服务被销毁：进程被系统杀了（省电/后台清理最常见）"
        "unbound" -> "服务被解绑：内存紧张或被省电策略停掉"
        "offline" -> "心跳断了：进程被冻结或被清理"
        "disabled" -> "被关掉了（手动关的，或更新安装后系统把它关掉）"
        "stale" -> "开着但没在运行：系统把服务停了"
        "rebind_fail" -> "自动重新挂上没成功：系统彻底不让它跑"
        else -> "记录到一次失效"
    }

    private fun guessReason(
        enabled: Boolean,
        running: Boolean,
        ignoringBattery: Boolean,
        lastKind: String,
        lastAt: Long,
        watchdog: Int,
    ): String {
        val battery = if (ignoringBattery) "" else "；电池优化没关，系统更容易把后台清掉"
        return when {
            !enabled -> "设置里被关了：去权限页把无障碍重新打开。更新安装后系统也会把它关掉$battery"
            !running -> "in enabled but not running: 系统把服务停了，常见原因是省电/后台清理$battery"
            lastKind.isNotBlank() -> "上次 ${clockText(lastAt)} ${kindReason(lastKind)}；现在已经恢复$battery"
            watchdog > 0 -> "看门狗记录到服务消失过（${clockText(lastAt)}）：多半是进程被系统杀了$battery"
            else -> "今天还没记录到失效，眼睛一直在。"
        }
    }
}
