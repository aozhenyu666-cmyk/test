package cn.companion.pet.core.storage

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Single-process Android app, with a file lock for independent journal instances as well. */
internal object DurableFiles {
    private val locks = ConcurrentHashMap<String, Any>()
    fun mutex(dir: File): Any = locks.computeIfAbsent(dir.canonicalPath) { Any() }
    fun <T> locked(dir: File, block: () -> T): T = synchronized(mutex(dir)) {
        check(dir.isDirectory || dir.mkdirs())
        RandomAccessFile(File(dir, ".lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }
    fun write(target: File, text: String) {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val tmp = File.createTempFile(".pending-", ".tmp", target.parentFile)
        try {
            FileOutputStream(tmp).use { out -> out.write(text.toByteArray(Charsets.UTF_8)); out.fd.sync() }
            // Same-directory atomic replacement; never delete the previous receipt first.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
}
