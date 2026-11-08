package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.File

/**
 * The main process writes its health here every probe; the `:watchdog` process reads it.
 * A file is enough because both processes share the app UID, and it survives the main process
 * being killed (the file simply goes stale). Best-effort: a failed write must never break the
 * accessibility service.
 */
object A11yHealthFile {
    private const val DIR = "phone"
    private const val NAME = "a11y-health.json"

    fun write(context: Context, snapshot: A11yHealth.Snapshot, nowWall: Long = System.currentTimeMillis()) {
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val json = JSONObject().apply {
                put("connected", snapshot.connected)
                put("verdict", snapshot.verdict.name)
                put("lastEventAt", snapshot.lastEventAt)
                put("lastProbeAt", snapshot.lastProbeAt)
                put("lastProbeOk", snapshot.lastProbeOk)
                put("failures", snapshot.failures)
                put("pid", Process.myPid())
                put("wallAt", nowWall)
            }
            val tmp = File(dir, "$NAME.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(File(dir, NAME))
        } catch (_: Exception) {
            // best effort
        }
    }

    fun read(context: Context): JSONObject? = try {
        val file = File(File(context.filesDir, DIR), NAME)
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    /** Wall-clock age of the last main-process write; null when never written. */
    fun ageMs(context: Context, nowWall: Long = System.currentTimeMillis()): Long? {
        val json = read(context) ?: return null
        val wall = json.optLong("wallAt", 0L)
        return if (wall <= 0L) null else (nowWall - wall).coerceAtLeast(0L)
    }

    /** The main process that wrote the last heartbeat, if it is still recorded. */
    fun pid(context: Context): Int? {
        val json = read(context) ?: return null
        val pid = json.optInt("pid", -1)
        return if (pid > 0) pid else null
    }
}
