package cn.companion.pet.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 崩溃与退出原因记录。真机上 App 卡退、无障碍显示“此服务出现故障”时，
 * 用户在 设置 → 诊断 一键复制这份报告，不用连电脑抓日志。
 *
 * 只记录技术信息（异常堆栈、系统给出的退出原因、版本和机型），不含聊天、屏幕文字或 Key。
 */
object Diagnostics {
    private const val DIR = "diagnostics"
    private const val KEEP = 5
    private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    data class Exit(val at: Long, val reason: String, val description: String, val abnormal: Boolean)

    /** 在 Application.onCreate 最早处调用：记下未捕获异常，再交给系统原来的处理（仍然会崩，但留下证据）。 */
    fun install(context: Context) {
        val dir = File(context.filesDir, DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(dir, "crash-" + System.currentTimeMillis() + ".txt")
                    .writeText("线程：" + thread.name + "\n" + sw.toString().take(12_000))
                dir.listFiles { f -> f.name.startsWith("crash-") }.orEmpty()
                    .sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 系统记录的最近几次进程退出（Android 11+）。 */
    fun recentExits(context: Context, limit: Int = 5): List<Exit> = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        am.getHistoricalProcessExitReasons(context.packageName, 0, limit).map { info ->
            Exit(info.timestamp, reasonLabel(info.reason), info.description.orEmpty(), isAbnormal(info.reason))
        }
    }.getOrDefault(emptyList())

    /** 最近一次不正常的退出（崩溃、卡死、被系统杀）。 */
    fun lastAbnormal(context: Context): Exit? = recentExits(context).firstOrNull { it.abnormal }

    fun lastCrashFile(context: Context): File? =
        File(context.filesDir, DIR).listFiles { f -> f.name.startsWith("crash-") }.orEmpty().maxByOrNull { it.name }

    /** 复制给开发者的诊断报告。 */
    fun report(context: Context, accessibilityConnected: Boolean): String = buildString {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        append("同桌诊断报告\n")
        append("版本：").append(pkg?.versionName).append(" (").append(pkg?.longVersionCode).append(")\n")
        append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("，Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        append("无障碍此刻已连上：").append(accessibilityConnected).append('\n')
        val battery = runCatching {
            context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
        append("电池不受限制：").append(battery?.toString() ?: "未知").append('\n')
        (context.applicationContext as? cn.companion.pet.PetApp)?.let { app ->
            runCatching { app.visionSetup().label() }.getOrNull()?.let { append("看图状态：").append(it).append('\n') }
            runCatching { app.visionEvidence.last()?.label() }.getOrNull()?.let { append("上次看一眼：").append(it).append('\n') }
            runCatching { app.executionHealth?.invoke() }.getOrNull()?.let { append("拦截执行路径：").append(it).append('\n') }
            runCatching { app.practiceStatus?.invoke() }.getOrNull()?.let { append("练习换休息：").append(it).append('\n') }
        }
        append("\n最近的进程退出（新的在前）：\n")
        val exits = recentExits(context, 8)
        if (exits.isEmpty()) append("（系统没有记录）\n")
        exits.forEach { append("- ").append(TIME.format(Instant.ofEpochMilli(it.at))).append(' ').append(it.reason)
            .append(if (it.description.isBlank()) "" else "：" + it.description.take(200)).append('\n') }
        anrTrace(context)?.let { append("\n最近一次卡死时主线程在做什么：\n").append(it).append('\n') }
        lastCrashFile(context)?.let { f ->
            append("\n最近一次崩溃（").append(TIME.format(Instant.ofEpochMilli(f.lastModified()))).append("）：\n")
            append(runCatching { f.readText() }.getOrDefault("（读不到）").take(6_000)).append('\n')
        }
    }

    /** ANR 时系统保存的线程堆栈，只截主线程那一段。 */
    private fun anrTrace(context: Context): String? = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .firstOrNull { it.reason == ApplicationExitInfo.REASON_ANR } ?: return null
        val text = info.traceInputStream?.bufferedReader()?.use { it.readText() } ?: return null
        val start = text.indexOf("\"main\"")
        if (start < 0) text.take(3_000) else text.substring(start).take(3_000)
    }.getOrNull()

    private fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
        ApplicationExitInfo.REASON_ANR -> "卡死无响应（ANR）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被系统回收"
        ApplicationExitInfo.REASON_SIGNALED -> "被系统信号结束（常见于省电/后台清理）"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过多被结束"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动失败"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "在系统设置里被强行停止"
        ApplicationExitInfo.REASON_USER_STOPPED -> "被用户停止"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变化后被结束"
        ApplicationExitInfo.REASON_EXIT_SELF -> "自己正常退出"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的进程结束"
        ApplicationExitInfo.REASON_OTHER -> "其他（系统未说明）"
        else -> "原因代码 $reason"
    }

    private fun isAbnormal(reason: Int): Boolean = reason in setOf(
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_OTHER,
    )
}
