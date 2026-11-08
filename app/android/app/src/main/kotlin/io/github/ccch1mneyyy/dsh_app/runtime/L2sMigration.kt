package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

/**
 * Turns PRoot's fake hard links into real files, so an install that ran under
 * PRoot can launch directly. PRoot's --link2symlink (with
 * PROOT_L2S_DIR={ROOTFS}/root/.l2s) moves a file that gets a second name into
 * that store as `.l2s.<name><NNNN>.<COUNT>`, COUNT being its link count, and
 * leaves symlinks with host-absolute targets: the intermediate
 * `.l2s.<name><NNNN>` to the stored file, and every name to the intermediate.
 * Building both Termux releases from source and running them shows 5.1.107.98
 * and 5.1.107.101 write this same layout (the `.proot.l2s.` prefix in .101's
 * source is behind -DUSERLAND and is not in the shipped binary), so one pass
 * covers both.
 *
 * For each stored file with names N1..Nk, N1..Nk-1 get a copy (mode and mtime
 * kept) renamed over them, then the stored file itself is renamed over Nk.
 * Each step is one rename, so every name keeps reading the same content and a
 * killed run resumes on the next start. PRoot also keeps working on a
 * half-done tree: it only lowers a count when a name that is still its symlink
 * goes away, and converting a name never lowers it, so a stored file outlives
 * every symlink that still needs it (at worst it stays as an orphan, removed
 * here next time).
 *
 * PRoot's own decrement path is not atomic: it renames the stored file to its
 * new count, then re-points the intermediate at it. Killed in that window the
 * intermediate names something that is gone, while the stored file -- the only
 * copy of the data -- is named by nothing. [repair] puts that back when the
 * file it must be is the only one it can be; anything less certain is left as
 * found, with the store still non-empty, so [DirectLaunch.compatibilityReason]
 * keeps asking for the compatible launch, where PRoot still reads every name.
 *
 * [flush] makes the copies durable before any of them replaces a name.
 * [guestBinds] are the guest paths PRoot binds over ([bindTargets]): there the
 * guest sees the host, so nothing under them in the rootfs is a PRoot name.
 */
class L2sMigration(
    rootfs: File,
    private val flush: () -> Unit,
    private val freeBytes: () -> Long = { rootfs.usableSpace },
    guestBinds: Collection<String> = emptyList(),
) {
    sealed class Result {
        /**
         * [links] names became real files; [removed] store entries nothing named any more;
         * [repaired] intermediates PRoot's own decrement path had left naming nothing, put
         * back on their stored file first (their names are counted in [links]).
         */
        data class Done(val links: Int, val removed: Int, val repaired: Int = 0) : Result()
        data class NotEnoughSpace(val need: Long, val free: Long) : Result()
    }

    private class Group(val stored: Path, val names: MutableList<Path> = ArrayList(), val chain: MutableSet<Path> = HashSet())

    private val root = rootfs.canonicalFile.toPath()
    private val rootPath = root.toString()
    private val store = root.resolve("root/.l2s")
    private val random = SecureRandom()
    private val binds: Set<Path> = guestBinds.filter { it.startsWith("/") }
        .map { root.resolve(it.trimStart('/')).normalize() }
        .filter { it.startsWith(root) && it != root }
        .toSet()

    /**
     * Throws on any IO error, leaving every name readable; the next run picks
     * up from there. [onSlow] runs once when this takes more than a moment.
     * [beforeRename] is a test seam for interrupting the run.
     */
    fun run(onSlow: () -> Unit = {}, beforeRename: () -> Unit = {}): Result {
        // A symlinked store would point the deletions below somewhere else.
        if (!Files.isDirectory(store, LinkOption.NOFOLLOW_LINKS)) return Result.Done(0, 0)
        val started = System.nanoTime()
        var slow = false
        fun tick() {
            if (!slow && System.nanoTime() - started > SLOW_NANOS) { slow = true; onSlow() }
        }

        val groups = LinkedHashMap<Path, Group>()
        // Names PRoot's own decrement path orphaned: the intermediate they lead through survives, but
        // the count name it points at is gone, because a run was killed between the two renames. The
        // stored file is then named by nothing, so the walk cannot say which file those names were.
        val unreachable = LinkedHashMap<Path, MutableList<Path>>()
        // A placeholder on the way to a bind target could not be read: names could hide
        // there, so every stored file stays, named or not.
        var keepOrphans = false
        // Every name lies in the rootfs: PRoot moves a file into the store by rename, so a
        // file elsewhere (/sdcard, /storage) never becomes a fake link. Other unreadable
        // directories abort the walk: a name missed there would make its file look orphaned.
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) =
                if (dir == store || dir in binds) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            // The directory is opened before preVisitDirectory: an unreadable one lands here.
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                // PRoot's placeholder for a bind (system, apex on Android 16 can't be listed).
                if (file in binds) return FileVisitResult.CONTINUE
                if (binds.any { it.startsWith(file) }) { keepOrphans = true; return FileVisitResult.CONTINUE }
                throw exc
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                tick()
                if (attrs.isSymbolicLink) {
                    when (val through = chain(file)) {
                        is Chain.Found -> groups.getOrPut(through.entries.last()) { Group(through.entries.last()) }.apply {
                            names.add(file)
                            chain += through.entries.dropLast(1)
                        }
                        // PRoot's, but its state lost the name the last link it made pointed at: keep the
                        // names, they have to come back with the file they were made from.
                        is Chain.Broken -> if (through.intermediate != null) {
                            unreachable.getOrPut(through.intermediate) { ArrayList() }.add(file)
                        } else keepOrphans = true
                        Chain.NotOurs -> Unit
                    }
                } else if (attrs.isRegularFile && TEMP.matches(file.fileName.toString())) {
                    Files.delete(file) // a copy from a run that was killed before its rename
                }
                return FileVisitResult.CONTINUE
            }
        })

        val repaired = repair(unreachable, groups)
        // Names whose stored file could not be named beyond doubt stay exactly as they are, and so does
        // everything in the store: the walk may not call any of it an orphan while they are in it.
        if (unreachable.keys.any { key -> groups.values.none { it.chain.contains(key) } }) keepOrphans = true

        val need = groups.values.sumOf { blocks(Files.size(it.stored)) * (it.names.size - 1) }
        if (need > 0) {
            val free = freeBytes()
            if (free < need + MARGIN) return Result.NotEnoughSpace(need, free)
        }

        var links = 0
        val batch = ArrayList<Group>()
        var files = 0
        var bytes = 0L
        fun commit() {
            val copies = ArrayList<Pair<Path, Path>>()
            var renamed = 0
            try {
                for (group in batch) for (name in group.names.dropLast(1)) {
                    tick()
                    val temp = name.resolveSibling(TEMP_PREFIX + "%016x".format(random.nextLong()) + ".tmp")
                    Files.copy(group.stored, temp, StandardCopyOption.COPY_ATTRIBUTES)
                    copies += temp to name
                }
                if (copies.isNotEmpty()) flush()
                for (group in batch) {
                    repeat(group.names.size - 1) {
                        val (temp, name) = copies[renamed]
                        beforeRename()
                        Files.move(temp, name, StandardCopyOption.ATOMIC_MOVE)
                        renamed++
                        links++
                    }
                    beforeRename()
                    Files.move(group.stored, group.names.last(), StandardCopyOption.ATOMIC_MOVE)
                    links++
                    // Right away: PRoot takes a dangling intermediate's name as free for a new
                    // link, then fails to create it after it already moved that file in.
                    for (entry in group.chain) Files.deleteIfExists(entry)
                }
            } finally {
                for (at in renamed until copies.size) runCatching { Files.deleteIfExists(copies[at].first) }
            }
            batch.clear()
            files = 0
            bytes = 0L
        }
        for (group in groups.values.sortedBy { it.stored }) {
            group.names.sort()
            batch += group
            files += group.names.size - 1
            bytes += blocks(Files.size(group.stored)) * (group.names.size - 1)
            if (files >= BATCH_FILES || bytes >= BATCH_BYTES) commit()
        }
        commit()

        // What is left was named by nothing: intermediates of files removed outside PRoot,
        // files whose names were all deleted without it. Symlinks first, for the same reason
        // as above. Directories are not PRoot's; they stay, and so does compatible launch.
        if (keepOrphans) return Result.Done(links, 0, repaired)
        val left = Files.newDirectoryStream(store).use { it.toList() }
            .filterNot { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .sortedByDescending { Files.isSymbolicLink(it) }
        for (entry in left) Files.deleteIfExists(entry)
        return Result.Done(links, left.size, repaired)
    }

    /** What [link] leads to, when it is one of PRoot's. */
    private sealed class Chain {
        /** The store entries, ending with the stored file. */
        class Found(val entries: List<Path>) : Chain()

        /**
         * PRoot's shape, but a name of it is gone: [intermediate] is the link that
         * points at the missing entry, null when even that one is not there.
         */
        class Broken(val intermediate: Path?) : Chain()

        object NotOurs : Chain()
    }

    /** The store entries [link] leads through, ending with the stored file. */
    private fun chain(link: Path): Chain {
        val entries = ArrayList<Path>()
        var entry = target(link)
        while (entry.parent == store && entries.size < MAX_HOPS) {
            entries.add(entry)
            val attrs = try {
                Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                // The entry before the missing one is the link that named it; further out the
                // link is just dangling and nothing in the store belongs to it.
                val namer = entries.getOrNull(entries.size - 2)
                return Chain.Broken(if (namer != null && INTERMEDIATE.matches(namer.fileName.toString())) namer else null)
            }
            if (attrs.isRegularFile) return Chain.Found(entries)
            if (!attrs.isSymbolicLink) return Chain.NotOurs
            entry = target(entry)
        }
        return Chain.NotOurs
    }

    /** The count at the end of a stored file's name, or -1 when it is not one. */
    private fun countOf(stored: Path): Int =
        COUNT.find(stored.fileName.toString())?.groupValues?.get(1)?.toIntOrNull() ?: -1

    /**
     * Point back at its stored file every intermediate PRoot's own decrement path left
     * naming nothing, and hand its names to that file. Only when exactly one stored file
     * of that name is left and its count still covers every name leading through it:
     * anything else is left as found, because guessing there loses data.
     */
    private fun repair(unreachable: Map<Path, List<Path>>, groups: MutableMap<Path, Group>): Int {
        if (unreachable.isEmpty()) return 0
        val stored = Files.newDirectoryStream(store).use { it.toList() }
            .filter { it.parent == store && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        var repaired = 0
        for ((intermediate, names) in unreachable) {
            val prefix = intermediate.fileName.toString() + "."
            val candidates = stored.filter { it.fileName.toString().let { n -> n.startsWith(prefix) && COUNT.containsMatchIn(n) } }
            val only = candidates.singleOrNull() ?: continue
            if (countOf(only) < names.size) continue
            // One atomic rename over the intermediate: it points at the old, missing name or at
            // this file, never at nothing.
            val temp = store.resolve(TEMP_PREFIX + "%016x".format(random.nextLong()) + ".tmp")
            Files.createSymbolicLink(temp, only)
            Files.move(temp, intermediate, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            groups.getOrPut(only) { Group(only) }.apply {
                this.names.addAll(names)
                this.chain.add(intermediate)
            }
            repaired++
        }
        return repaired
    }

    /** Where PRoot resolves [link]: an absolute target inside the rootfs is a host path, any other a guest path. */
    private fun target(link: Path): Path {
        val value = Files.readSymbolicLink(link).toString()
        return when {
            !value.startsWith("/") -> link.resolveSibling(value)
            value == rootPath || value.startsWith("$rootPath/") -> Paths.get(value)
            else -> Paths.get(rootPath + value)
        }.normalize()
    }

    companion object {
        const val TEMP_PREFIX = ".dsh-go-l2s-"
        val TEMP = Regex("""\.dsh-go-l2s-[0-9a-f]{16}\.tmp""")
        /** PRoot's intermediate: `.l2s.<name><NNNN>`, the link every name of a stored file leads through. */
        private val INTERMEDIATE = Regex("""\.l2s\..+\d{4}""")
        /** PRoot's stored file: an intermediate name plus `. <count>`, four digits while the count fits. */
        private val COUNT = Regex("""\.(\d{4,9})$""")
        /** Kept free beyond the copies, for the engine itself. */
        const val MARGIN = 128L shl 20
        private const val SLOW_NANOS = 500_000_000L
        private const val MAX_HOPS = 40
        private const val BATCH_FILES = 2000
        private const val BATCH_BYTES = 256L shl 20

        private fun blocks(size: Long) = 4096 + (size + 4095) / 4096 * 4096

        /** Guest paths of the PRoot `-b host[:guest]` binds in [prootArgv] (before the guest command) and [extra]. */
        fun bindTargets(prootArgv: List<String>, extra: List<String>): List<String> {
            val end = prootArgv.indexOf("/usr/bin/env").let { if (it < 0) prootArgv.size else it }
            val specs = (1 until end).filter { prootArgv[it - 1] == "-b" || prootArgv[it - 1] == "--bind" }.map { prootArgv[it] }
            return (specs + extra).map { it.substringAfter(':') }
        }
    }
}
