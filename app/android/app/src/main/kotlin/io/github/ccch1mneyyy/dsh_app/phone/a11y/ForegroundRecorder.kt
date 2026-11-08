package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.util.concurrent.Executors

/**
 * D2: an opt-in recorder for every foreground change (package, activity class, time).
 *
 * It exists to answer "which packages actually appear during Gemini Live / WeChat" with real
 * data, so the allowlists are decided by evidence instead of guesses. Recording never changes
 * interception: [onEvent] only appends a line, and only while the debug switch is on.
 *
 * The switch lives in the app's own preferences (`dsh_foreground_debug`); the log is
 * `filesDir/phone/foreground.jsonl`, capped and rotated, and rides along in the D1 bundle.
 */
object ForegroundRecorder {
    private const val PREFS = "dsh_foreground_debug"
    private const val KEY = "enabled"
    private const val DIR = "phone"
    private const val NAME = "foreground.jsonl"
    private const val MAX_BYTES = 1024L * 1024

    /** What the last written line said, so a burst of events for one screen is one line. */
    @Volatile private var last = ""

    /** Appends happen off the accessibility thread: a slow disk must not hold up event handling. */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dsh-foreground-log").apply { isDaemon = true }
    }

    fun file(context: Context): File = File(File(context.filesDir, DIR), NAME)

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        // Switching off and on again starts a new record: the first line after it must not be
        // swallowed by a stale "same screen" from before.
        last = ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }

    /** The pure gate: recording only while switched on, and only for a foreground change. */
    fun shouldRecord(enabled: Boolean, eventType: Int?): Boolean =
        enabled && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    /** Called from the accessibility service for every event; cheap when the switch is off. */
    fun onEvent(context: Context, event: AccessibilityEvent?) {
        if (!shouldRecord(enabled(context), event?.eventType)) return
        record(context, event?.packageName?.toString(), event?.className?.toString(), System.currentTimeMillis())
    }

    @Synchronized
    fun record(context: Context, packageName: String?, activity: String?, at: Long) {
        if (packageName.isNullOrBlank()) return
        val next = "$packageName|${activity.orEmpty()}"
        if (next == last) return
        last = next
        val target = file(context)
        val body = line(packageName, activity, at)
        writer.execute { append(target, body) }
    }

    /** One JSON line. Hand-escaped: the unit tests run without Android's org.json. */
    fun line(packageName: String, activity: String?, at: Long): String {
        fun escape(raw: String): String = buildString {
            for (c in raw) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
        return "{\"at\":$at,\"app\":\"${escape(packageName)}\",\"activity\":\"${escape(activity.orEmpty())}\"}"
    }

    private fun append(file: File, line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) file.renameTo(File(file.path + ".1"))
            file.appendText(line + "\n")
        }
    }
}
