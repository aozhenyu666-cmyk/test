package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.Context
import java.io.File

/**
 * D1: one line per accessibility-watchdog check, so a bundle can show what the watchdog saw
 * while the service was dying. Logcat alone is gone after a reboot; this file stays until it is
 * rotated. Best-effort: a failed write must never break the check.
 */
object A11yWatchdogLog {
    const val NAME = "a11y-watchdog.jsonl"
    private const val MAX_BYTES = 256L * 1024

    fun file(context: Context): File = File(File(context.filesDir, "phone"), NAME)

    fun note(
        context: Context,
        listed: Boolean,
        heartbeatAgeMs: Long?,
        granted: Boolean?,
        nowListed: Boolean?,
        action: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val line = line(listed, heartbeatAgeMs, granted, nowListed, action, now)
        runCatching {
            val file = file(context)
            file.parentFile?.mkdirs()
            if (file.length() > MAX_BYTES) file.renameTo(File(file.path + ".1"))
            file.appendText(line + "\n")
        }
    }

    /** One JSON line; hand-built so it is unit-tested without Android's org.json. */
    fun line(
        listed: Boolean,
        heartbeatAgeMs: Long?,
        granted: Boolean?,
        nowListed: Boolean?,
        action: String,
        now: Long,
    ): String = "{\"at\":$now,\"listed\":$listed,\"ageMs\":${heartbeatAgeMs ?: "null"}," +
        "\"granted\":${granted ?: "null"},\"nowListed\":${nowListed ?: "null"},\"action\":\"${escape(action)}\"}"

    private fun escape(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")
}
