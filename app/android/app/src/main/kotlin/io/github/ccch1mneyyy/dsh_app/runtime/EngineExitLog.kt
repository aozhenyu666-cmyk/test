package io.github.ccch1mneyyy.dsh_app.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why did the engine stop? One JSON line per engine exit, plus the reasons Android gives for
 * this app's own process dying (read at the next launch), in files/runtime/logs/engine-exits.jsonl.
 * Everything also goes to logcat under the tag "DshEngineExit".
 */
object EngineExitLog {
    const val TAG = "DshEngineExit"
    private const val FILE = "engine-exits.jsonl"
    private const val MAX_BYTES = 512L * 1024
    private const val PREFS = "dsh_engine_exits"
    private const val K_SEEN = "lastProcessExitAt"

    fun file(app: Context): File = File(app.filesDir, "runtime/logs/$FILE")

    /** One engine exit as seen by [RuntimeController.onExit]. */
    fun engineExit(
        app: Context, code: Int, expected: Boolean, kind: String?, upMs: Long?,
        restartInMs: Long?, recentRestarts: Int, tail: List<String>,
    ) {
        val o = JSONObject()
        o.put("event", "engineExit")
        o.put("code", code)
        o.put("signal", signalName(code))
        o.put("expected", expected)
        kind?.let { o.put("kind", it) }
        upMs?.let { o.put("upSeconds", it / 1000) }
        o.put("restartInSeconds", restartInMs?.let { it / 1000 } ?: JSONObject.NULL)
        o.put("restartsLast30Min", recentRestarts)
        o.put("device", deviceState(app))
        o.put("tail", JSONArray(tail.takeLast(15).map { CrashClassifier.redact(it).take(300) }))
        write(app, o)
    }

    /**
     * Android 11+: the reasons the system recorded for earlier deaths of this app's process
     * (low memory, killed by the user from recents, an OEM cleaner, a crash...). Each one is
     * written once.
     */
    fun processExits(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(K_SEEN, 0L)
            val infos = am.getHistoricalProcessExitReasons(app.packageName, 0, 20)
                .filter { it.timestamp > seen }
                .sortedBy { it.timestamp }
            for (info in infos) {
                val o = JSONObject()
                o.put("event", "appProcessExit")
                o.put("at", stamp(info.timestamp))
                o.put("process", info.processName)
                o.put("reason", reasonName(info.reason))
                o.put("status", info.status)
                o.put("importance", info.importance)
                o.put("pssKb", info.pss)
                o.put("rssKb", info.rss)
                info.description?.let { o.put("description", it.take(300)) }
                write(app, o)
            }
            infos.lastOrNull()?.let { prefs.edit().putLong(K_SEEN, it.timestamp).apply() }
        }.onFailure { Log.w(TAG, "could not read process exit reasons", it) }
    }

    /** A free-form event: service start/stop, sticky restart, a restart that could not happen. */
    fun note(app: Context, event: String, detail: String? = null) {
        val o = JSONObject()
        o.put("event", event)
        detail?.let { o.put("detail", it.take(300)) }
        write(app, o)
    }

    private fun deviceState(app: Context): JSONObject {
        val d = JSONObject()
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            d.put("availMemMb", mi.availMem / (1024 * 1024))
            d.put("totalMemMb", mi.totalMem / (1024 * 1024))
            d.put("lowMemory", mi.lowMemory)
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            d.put("screenOn", pm.isInteractive)
            d.put("batteryExempt", pm.isIgnoringBatteryOptimizations(app.packageName))
            d.put("idle", pm.isDeviceIdleMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) d.put("bgRestricted", am.isBackgroundRestricted)
            val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
            d.put("appImportance", me.importance)
        }
        d.put("sdk", Build.VERSION.SDK_INT)
        d.put("maker", Build.MANUFACTURER)
        return d
    }

    @Synchronized private fun write(app: Context, o: JSONObject) {
        if (!o.has("at")) o.put("at", stamp(System.currentTimeMillis()))
        val line = o.toString()
        Log.i(TAG, line)
        runCatching {
            val f = file(app)
            f.parentFile?.mkdirs()
            if (f.length() > MAX_BYTES) f.renameTo(File(f.path + ".1"))
            f.appendText(line + "\n")
        }
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))

    /** 128+N from a shell is signal N; the ones that matter here. */
    fun signalName(code: Int): String? = when (code) {
        137, -9 -> "SIGKILL (killed by the system or a cleaner)"
        143, -15 -> "SIGTERM (asked to stop)"
        134, -6 -> "SIGABRT (crashed)"
        139, -11 -> "SIGSEGV (crashed)"
        0 -> "exit 0 (exited by itself)"
        else -> null
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exitSelf"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "lowMemory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashNative"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initFailure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permissionChange"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessiveResourceUsage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "userRequested (force stop / swiped with cleaner)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "userStopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependencyDied"
        ApplicationExitInfo.REASON_OTHER -> "other (often an OEM background cleaner)"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "packageStateChange"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "packageUpdated"
        else -> "unknown($r)"
    }
}
