package io.github.ccch1mneyyy.dsh_app.diagnostics

import android.content.Context
import io.github.ccch1mneyyy.dsh_app.files.GuestFiles
import java.io.File
import java.io.RandomAccessFile

/**
 * The small engine-side files a bug report needs, read for the diagnostics bundle.
 *
 * They all live under `$DSH_HOME` inside the rootfs: the interaction ledger, the companion
 * config and the per-module state files. `DSH_HOME` is `/root/.dsh` both under proot and under
 * the direct launcher; the older `/root` layout is still probed so a device that never migrated
 * gives us something to look at. App-private logs (`engine-exits.jsonl`, the accessibility
 * watchdog, the foreground recorder) are read from `filesDir`.
 *
 * Only an allowlist is read, each file is capped, and total size is capped. Nothing here scans
 * directories: a new file must be listed on purpose.
 *
 * Deliberately not included: the memory/relationship SQLite databases
 * (`fish-memory.sqlite`, `relationship.db`). They are chat-derived personal data, can be large,
 * and file-level redaction cannot be applied inside SQLite; the small JSON state files above
 * carry what a bug report needs.
 */
object EngineDiagnosticsFiles {
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_TOTAL_CHARS = 4 * 1024 * 1024

    /** `zip path` → path relative to `$DSH_HOME`. */
    private val ENGINE_FILES = listOf(
        "companion/ledger.jsonl",
        "companion/config.json",
        "companion/brain.json",
        "companion/temper.json",
        "companion/rhythm.json",
        "companion/gear.json",
        "companion/gemini.json",
        "companion/notes.json",
        "companion/notifs.json",
        "companion/topics.json",
        "fish-strict.json",
        "feiyu-mood/state.json",
        "feiyu-mood/config.json",
        "feiyu-mood/diag.log",
    )

    /** `zip path` → file under `filesDir`. Rotated `.1` logs ride along: a long session can age
     * the beginning of the record out of the current file. */
    private val APP_FILES = listOf(
        "engine/engine-exits.jsonl" to "runtime/logs/engine-exits.jsonl",
        "engine/engine-exits.jsonl.1" to "runtime/logs/engine-exits.jsonl.1",
        "phone/foreground.jsonl" to "phone/foreground.jsonl",
        "phone/foreground.jsonl.1" to "phone/foreground.jsonl.1",
        "phone/a11y-watchdog.jsonl" to "phone/a11y-watchdog.jsonl",
        "phone/a11y-watchdog.jsonl.1" to "phone/a11y-watchdog.jsonl.1",
        "phone/a11y-health.json" to "phone/a11y-health.json",
    )

    fun collect(context: Context): Map<String, String> {
        val appFiles = APP_FILES.map { (name, path) -> name to File(context.filesDir, path) }
        // A symlinked /root (or a bind mount) still resolves through the guest mapping.
        val extra = { relative: String ->
            listOfNotNull(
                GuestFiles.resolve(context, "/root/.dsh/$relative"),
                GuestFiles.resolve(context, "/root/$relative"),
            )
        }
        return collect(homes(context), appFiles, extra)
    }

    /**
     * [homes] are tried in order; the first file that exists wins. [appFiles] are read directly.
     * [extra] supplies guest-mapped candidates after [homes]. Kept free of Android so the
     * allowlist, caps and ordering are unit-tested.
     */
    fun collect(
        homes: List<File>,
        appFiles: List<Pair<String, File>>,
        extra: (String) -> List<File> = { emptyList() },
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        for ((name, file) in appFiles) {
            val text = read(file) ?: continue
            if (total + text.length > MAX_TOTAL_CHARS) break
            out[name] = text
            total += text.length
        }
        for (relative in ENGINE_FILES) {
            val file = candidate(homes, relative, extra) ?: continue
            val text = read(file) ?: continue
            if (total + text.length > MAX_TOTAL_CHARS) break
            out["engine/$relative"] = text
            total += text.length
        }
        return out
    }

    private fun candidate(homes: List<File>, relative: String, extra: (String) -> List<File>): File? {
        for (home in homes) {
            val file = File(home, relative)
            if (file.isFile && file.canRead()) return file
        }
        for (file in extra(relative)) if (file.isFile && file.canRead()) return file
        return null
    }

    private fun homes(context: Context): List<File> {
        val files = context.filesDir
        return listOf(
            File(files, "runtime/rootfs/root/.dsh"),
            File(files, ".dsh"),
            File(files, "runtime/rootfs/root"),
            files,
        ).distinct()
    }

    /** The file's text, capped at [MAX_FILE_BYTES]; a cap keeps the newest lines of a log. */
    private fun read(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            if (file.length() <= MAX_FILE_BYTES) {
                file.readText()
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    val start = raf.length() - MAX_FILE_BYTES
                    raf.seek(start)
                    val bytes = ByteArray(MAX_FILE_BYTES)
                    raf.readFully(bytes)
                    // A cut first line is half a line: drop it.
                    String(bytes, Charsets.UTF_8).substringAfter('\n', "")
                }
            }
        }.getOrNull()
    }

    /** The zip paths this object can produce, for tests and documentation. */
    fun names(): List<String> = APP_FILES.map { it.first } + ENGINE_FILES.map { "engine/$it" }
}
