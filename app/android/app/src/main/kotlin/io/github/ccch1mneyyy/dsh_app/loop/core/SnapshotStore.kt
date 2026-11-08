package io.github.ccch1mneyyy.dsh_app.loop.core

import io.github.ccch1mneyyy.dsh_app.phone.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Where the kernel keeps its state across process death. One JSON object per store. */
interface SnapshotStore {
    sealed interface Read {
        data object Empty : Read

        /**
         * [recoveredFrom] is non-null when the main file was unusable and the copy named there was
         * loaded instead, so the kernel can record that instead of looking healthy.
         */
        data class Loaded(val map: Map<*, *>, val recoveredFrom: String? = null) : Read

        /** Something was there and could not be read: never the same as [Empty]. */
        data class Unreadable(val error: String) : Read
    }

    fun read(): Read
    fun write(map: Map<String, Any?>)
}

/**
 * Temp file → fsync → atomic replace, with the previous generation kept as `.bak`.
 *
 * The replace used to be `renameTo`, and when that returned false it *deleted the live file* and
 * tried again — the exact window in which a crash leaves neither the old state nor the new one, and
 * a silent loss when the second rename also failed. Now the current file becomes the backup before
 * it is replaced, the replacement is an atomic move where the filesystem supports it, and the
 * directory is fsynced so the rename itself survives a power cut. [read] therefore has two chances:
 * the main file, then the backup.
 */
class AtomicJsonFile(private val file: File) : SnapshotStore {
    private val backup = File(file.parentFile, file.name + ".bak")
    private val temp = File(file.parentFile, file.name + ".tmp")

    override fun read(): SnapshotStore.Read {
        val main = readOne(file)
        if (main is SnapshotStore.Read.Loaded) return main
        if (main is SnapshotStore.Read.Unreadable) quarantine()
        if (!backup.exists()) return main
        return when (val fromBackup = readOne(backup)) {
            is SnapshotStore.Read.Loaded -> SnapshotStore.Read.Loaded(fromBackup.map, recoveredFrom = backup.name)
            is SnapshotStore.Read.Unreadable -> SnapshotStore.Read.Unreadable(
                (main as? SnapshotStore.Read.Unreadable)?.error ?: fromBackup.error,
            )
            SnapshotStore.Read.Empty -> main
        }
    }

    private fun readOne(f: File): SnapshotStore.Read {
        if (!f.exists()) return SnapshotStore.Read.Empty
        if (f.length() == 0L) return SnapshotStore.Read.Unreadable("empty file")
        return try {
            val parsed = Json.parse(f.readText(Charsets.UTF_8)) as? Map<*, *> ?: error("not an object")
            SnapshotStore.Read.Loaded(parsed)
        } catch (t: Throwable) {
            SnapshotStore.Read.Unreadable("${t.javaClass.simpleName}: ${t.message}".take(300))
        }
    }

    override fun write(map: Map<String, Any?>) {
        file.parentFile?.mkdirs()
        val bytes = Json.write(map).toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (temp.length() != bytes.size.toLong()) error("short write ${temp.length()}/${bytes.size}")
        keepPrevious()
        replace()
        syncDirectory()
    }

    /** The last good file becomes the backup before it is overwritten. */
    private fun keepPrevious() {
        if (!file.exists()) return
        runCatching { Files.deleteIfExists(backup.toPath()) }
        val moved = runCatching { Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING) }.isSuccess
        if (!moved) runCatching { file.copyTo(backup, overwrite = true) }
    }

    private fun replace() {
        val atomic = runCatching {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        if (atomic.isSuccess) return
        if (atomic.exceptionOrNull() !is AtomicMoveNotSupportedException) {
            // A sharing violation on Windows is usually transient: one retry, then give up on atomicity.
            val retried = runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            if (retried.isSuccess) return
        }
        // Last resort: plain rename. The backup above already holds the old state, so this is safe.
        if (!temp.renameTo(file)) {
            val plain = runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            if (plain.isFailure) {
                error("cannot replace ${file.name}: ${plain.exceptionOrNull()?.javaClass?.simpleName ?: "renameTo false"}")
            }
        }
    }

    /** Make the rename durable. Best effort: not every platform allows opening a directory. */
    private fun syncDirectory() {
        val dir = file.parentFile ?: return
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    /** Move a damaged file aside without destroying older evidence. */
    private fun quarantine() {
        val bad = File(file.parentFile, file.name + ".bad")
        if (bad.exists()) runCatching { bad.renameTo(File(file.parentFile, file.name + ".bad." + System.currentTimeMillis())) }
        runCatching { Files.move(file.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
}

/** Tests, and running with storage unavailable. Round-trips through JSON like the file does. */
class MemorySnapshotStore(var text: String? = null, var failWrites: Boolean = false) : SnapshotStore {
    var writes = 0
        private set

    override fun read(): SnapshotStore.Read {
        val t = text ?: return SnapshotStore.Read.Empty
        return try {
            SnapshotStore.Read.Loaded(Json.parse(t) as Map<*, *>)
        } catch (e: Throwable) {
            SnapshotStore.Read.Unreadable(e.toString())
        }
    }

    override fun write(map: Map<String, Any?>) {
        if (failWrites) error("disk full")
        text = Json.write(map)
        writes++
    }
}

/** Small typed readers for maps parsed by [Json] (integers come back as Long). */
internal object M {
    fun str(m: Map<*, *>, k: String): String? = m[k] as? String
    fun long(m: Map<*, *>, k: String, d: Long = 0): Long = (m[k] as? Number)?.toLong() ?: d
    fun longOrNull(m: Map<*, *>, k: String): Long? = (m[k] as? Number)?.toLong()
    fun int(m: Map<*, *>, k: String, d: Int = 0): Int = (m[k] as? Number)?.toInt() ?: d
    fun bool(m: Map<*, *>, k: String, d: Boolean = false): Boolean = m[k] as? Boolean ?: d
    fun map(m: Map<*, *>, k: String): Map<*, *>? = m[k] as? Map<*, *>
    fun list(m: Map<*, *>, k: String): List<*> = m[k] as? List<*> ?: emptyList<Any?>()
    inline fun <reified E : Enum<E>> enum(m: Map<*, *>, k: String): E? = (m[k] as? String)?.let { v -> enumValues<E>().firstOrNull { it.name == v } }
}
