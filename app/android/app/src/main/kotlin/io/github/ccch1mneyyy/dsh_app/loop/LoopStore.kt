package io.github.ccch1mneyyy.dsh_app.loop

import io.github.ccch1mneyyy.dsh_app.phone.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Everything the loop must remember across a process restart. */
data class LoopSnapshot(
    val config: LoopConfig = LoopConfig(),
    val tracker: ForegroundTracker.State = ForegroundTracker.State(),
    val lastTriggerAt: Long = 0L,
    /** The device day the daily cap counts in, e.g. "2026-10-31"; null until the first trigger. */
    val triggerDay: String? = null,
    /** Triggers spent in [triggerDay]; reset when the day changes or the cap is off. */
    val triggersToday: Int = 0,
    /** Oldest first, at most [LoopStore.MAX_EVENTS]. */
    val events: List<LoopEvent> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "version" to 2, "config" to config.toMap(), "tracker" to tracker.toMap(),
        "lastTriggerAt" to lastTriggerAt, "triggerDay" to triggerDay, "triggersToday" to triggersToday,
        "events" to events.map { it.toMap() },
    )

    companion object {
        fun fromMap(m: Map<*, *>): LoopSnapshot = LoopSnapshot(
            config = LoopConfig.fromMap(m["config"] as? Map<*, *>),
            tracker = ForegroundTracker.State.fromMap(m["tracker"] as? Map<*, *>),
            lastTriggerAt = (m["lastTriggerAt"] as? Number)?.toLong() ?: 0L,
            // Absent in version-1 files: a file written before T5 simply has no cap usage yet.
            triggerDay = m["triggerDay"] as? String,
            triggersToday = (m["triggersToday"] as? Number)?.toInt() ?: 0,
            events = (m["events"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(LoopEvent::fromMap) }.orEmpty(),
        )
    }
}

interface LoopStore {
    sealed interface Read {
        data object Empty : Read

        /**
         * [recoveredFrom] is non-null when the main file was unusable and the copy named there was
         * loaded instead; the engine records that so the settings page can say what happened.
         */
        data class Loaded(val snapshot: LoopSnapshot, val recoveredFrom: String? = null) : Read

        /** A file was there but could not be read: never treated as "nothing saved". */
        data class Unreadable(val error: String) : Read
    }

    fun read(): Read
    fun write(snapshot: LoopSnapshot)

    companion object {
        const val MAX_EVENTS = 200
    }
}

/**
 * Temp file → fsync → atomic replace, with the previous good file kept as `state.json.bak`.
 *
 * The replace is the part that used to be unreliable. `File.renameTo` returns false on a number of
 * real cases (target exists on some filesystems, Windows sharing violations, a stale temp file) and
 * the old code answered by *deleting the live file and renaming again* — a window in which a crash
 * lost both the new and the old state, and a silent data loss when the second rename also failed.
 * Now:
 *
 * 1. the new content goes to `state.json.tmp` and is fsynced;
 * 2. the current `state.json` becomes `state.json.bak` (its last good copy);
 * 3. the temp file is moved onto `state.json` with an atomic move when the filesystem supports it,
 *    falling back to a plain rename only then;
 * 4. the directory is fsynced so the rename itself survives a power cut.
 *
 * [read] therefore has two chances: the main file, then the backup. A file that parses as something
 * other than a snapshot object is not "empty" — it is moved aside as `state.json.bad` (an older
 * `.bad` is kept with a timestamp suffix) and reported, and the backup is tried.
 */
class FileLoopStore(private val file: File) : LoopStore {
    private val backup = File(file.parentFile, file.name + ".bak")
    private val temp = File(file.parentFile, file.name + ".tmp")

    override fun read(): LoopStore.Read {
        val main = readOne(file)
        if (main is LoopStore.Read.Loaded) return main
        // Damaged (or present but unreadable): keep it for diagnosis, then try the backup.
        if (main is LoopStore.Read.Unreadable) quarantine()
        val fromBackup = if (backup.exists()) readOne(backup) else LoopStore.Read.Empty
        return when (fromBackup) {
            is LoopStore.Read.Loaded -> LoopStore.Read.Loaded(fromBackup.snapshot, recoveredFrom = backup.name)
            is LoopStore.Read.Unreadable -> LoopStore.Read.Unreadable((main as? LoopStore.Read.Unreadable)?.error ?: fromBackup.error)
            LoopStore.Read.Empty -> main
        }
    }

    private fun readOne(f: File): LoopStore.Read {
        if (!f.exists()) return LoopStore.Read.Empty
        if (f.length() == 0L) return LoopStore.Read.Unreadable("empty file")
        return try {
            val map = Json.parse(f.readText(Charsets.UTF_8)) as? Map<*, *> ?: error("not an object")
            LoopStore.Read.Loaded(LoopSnapshot.fromMap(map))
        } catch (t: Throwable) {
            LoopStore.Read.Unreadable("${t.javaClass.simpleName}: ${t.message}".take(300))
        }
    }

    override fun write(snapshot: LoopSnapshot) {
        file.parentFile?.mkdirs()
        val bytes = Json.write(snapshot.toMap()).toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (temp.length() != bytes.size.toLong()) throw java.io.IOException("short write ${temp.length()}/${bytes.size}")
        keepPrevious()
        replace(temp, file)
        syncDirectory()
    }

    /** The last good file becomes the backup before it is overwritten. */
    private fun keepPrevious() {
        if (!file.exists()) return
        runCatching { Files.deleteIfExists(backup.toPath()) }
        val moved = runCatching { Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING) }.isSuccess
        if (!moved) {
            // A copy still gives read() its second chance; a failure here must not fail the write.
            runCatching { file.copyTo(backup, overwrite = true) }
        }
    }

    private fun replace(from: File, to: File) {
        // Atomic when the filesystem can do it: a reader sees either the old file or the new one.
        val atomic = runCatching {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        if (atomic.isSuccess) return
        if (atomic.exceptionOrNull() !is AtomicMoveNotSupportedException) {
            // Some other failure (e.g. a sharing violation on Windows): retry once, it is transient.
            val retried = runCatching {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            if (retried.isSuccess) return
        }
        // Last resort: plain rename. Delete the target only now, so the backup above already holds it.
        if (!from.renameTo(to)) {
            val fallback = runCatching {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (fallback.isFailure) throw java.io.IOException("cannot replace ${to.name}: ${fallback.exceptionOrNull()?.javaClass?.simpleName ?: "renameTo false"}")
        }
    }

    /** Make the rename itself durable. Best effort: not every platform allows opening a directory. */
    private fun syncDirectory() {
        val dir = file.parentFile ?: return
        runCatching {
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /** Move the damaged file aside without ever destroying older evidence. */
    private fun quarantine() {
        val bad = File(file.parentFile, file.name + ".bad")
        if (bad.exists()) runCatching { bad.renameTo(File(file.parentFile, file.name + ".bad." + System.currentTimeMillis())) }
        runCatching { Files.move(file.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

/** For tests and for running with storage unavailable. */
class MemoryLoopStore(var saved: LoopSnapshot? = null, var failWrites: Boolean = false) : LoopStore {
    var writes = 0
        private set

    override fun read(): LoopStore.Read = saved?.let { LoopStore.Read.Loaded(it) } ?: LoopStore.Read.Empty
    override fun write(snapshot: LoopSnapshot) {
        if (failWrites) error("disk full")
        saved = snapshot
        writes++
    }
}
