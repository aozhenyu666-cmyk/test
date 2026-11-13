package io.github.ccch1mneyyy.dsh_app.tile

import io.github.ccch1mneyyy.dsh_app.phone.Json
import java.io.File

/**
 * The 局 summary the plugin writes on every 局 snapshot (`$DSH_HOME/companion/bouts-status.json`,
 * see `boutsTileStatus` + `boutsSnapshot` in dsh-go-companion/lib/index.js). Kept out of the
 * Service so the parsing is testable on the JVM.
 *
 * Which `$DSH_HOME` the engine used depends on how it was started (runtime/DshProcess sets HOME
 * to filesDir, runtime/DirectLaunch runs inside the rootfs), so both candidates are tried — the
 * same pair diagnostics resolves.
 */
object BoutsTileStatus {
    private val HOMES = listOf("runtime/rootfs/root/.dsh", ".dsh")

    /** The last summary, or null when no 局 panel has been rendered yet / nothing parsed. */
    fun read(filesDir: File): Map<*, *>? {
        for (home in HOMES) {
            val file = File(File(filesDir, home), "companion/bouts-status.json")
            if (!file.isFile || !file.canRead()) continue
            val parsed = runCatching { Json.parse(file.readText(Charsets.UTF_8)) }.getOrNull()
            if (parsed is Map<*, *>) return parsed
        }
        return null
    }

    /** Today's beat count; null when the summary predates the field or holds a non-number. */
    fun beats(status: Map<*, *>?): Int? = (status?.get("beats") as? Number)?.toInt()

    /** Strictly `true`: a string "true" is not a running 局. */
    fun running(status: Map<*, *>?): Boolean = status?.get("running") == true

    /** The running 局's name; blank when there is none or the plugin had no title. */
    fun bout(status: Map<*, *>?): String = (status?.get("bout") as? String).orEmpty()

    /** This beat's question; blank when the plugin process no longer holds the 局. */
    fun ask(status: Map<*, *>?): String = (status?.get("ask") as? String).orEmpty()

    /** When this beat runs out, in epoch millis; 0 when unknown or already past. */
    fun until(status: Map<*, *>?): Long = (status?.get("until") as? Number)?.toLong() ?: 0L

    /** The 「还剩 N 秒。交。」 line the plugin writes just before a beat runs out; blank when there is nothing to nudge about. */
    fun nudge(status: Map<*, *>?): String = (status?.get("nudge") as? String).orEmpty()
}
