package cn.companion.pet.core.memory

import cn.companion.pet.core.probe.DefaultPersona
import cn.companion.pet.core.text.UnicodeText
import java.io.File
import java.time.LocalDate

/**
 * 本机 Markdown 记忆。全部放在 [root]（应用私有目录）下：
 *
 * persona.md、profile.md、today.md、diary/日期.md、letters/日期.md、archive/日期.md，
 * 以及 deleted.txt（用户删掉的档案条目，每晚整理后再过滤一次，保证不被写回）。
 *
 * 写入一律先写临时文件再改名，避免写一半崩溃留下坏文件。非线程安全，调用方串行访问。
 */
class MemoryStore(val root: File) {

    /** 与 UI、夜间整理共享的事务锁：所有 Markdown/事件写入都应经过它。 */
    val lock = Any()

    val personaFile = File(root, "persona.md")
    val profileFile = File(root, "profile.md")
    val todayFile = File(root, "today.md")
    val diaryDir = File(root, "diary")
    val lettersDir = File(root, "letters")
    val archiveDir = File(root, "archive")
    private val deletedFile = File(root, "deleted.txt")
    private val letterReadFile = File(root, "letter-read.txt")

    init {
        root.mkdirs(); diaryDir.mkdirs(); lettersDir.mkdirs(); archiveDir.mkdirs()
    }

    // ---------- 人设 ----------

    fun persona(): String {
        if (!personaFile.exists()) writeAtomic(personaFile, DefaultPersona.TEXT)
        return personaFile.readText()
    }

    fun savePersona(text: String) = synchronized(lock) {
        writeAtomic(personaFile, text.ifBlank { DefaultPersona.TEXT })
    }

    // ---------- 档案 ----------

    fun profile(): String = if (profileFile.exists()) profileFile.readText() else ""

    fun profileLines(): List<String> = profile().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** 用户在 App 里改档案：整份覆盖；被删掉的行记入删除清单。 */
    fun saveProfileByUser(text: String) { synchronized(lock) {
        val before = profileLines().toSet()
        val after = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val removed = before - after.toSet()
        if (removed.isNotEmpty()) addTombstones(removed)
        writeAtomic(profileFile, ProfileRules.clamp(after).joinToString("\n"))
    } }

    fun deleteProfileLine(line: String) { synchronized(lock) {
        val lines = profileLines()
        addTombstones(listOf(line))
        writeAtomic(profileFile, lines.filter { it != line.trim() }.joinToString("\n"))
    } }

    /** 把一条 [我推测] 改成 [他说过]（用户确认推测正确）。 */
    fun confirmProfileLine(line: String) { synchronized(lock) {
        val fixed = line.trim().replaceFirst(ProfileRules.GUESS, ProfileRules.SAID)
        writeAtomic(profileFile, profileLines().map { if (it == line.trim()) fixed else it }.joinToString("\n"))
    } }

    /** 一段文本是否包含任一已删除内容；用于交流行、摘要、话题等带前缀的文本。 */
    fun forgottenMatches(text: String): Boolean {
        val normalized = ProfileRules.normalize(text)
        if (normalized.isEmpty()) return false
        return tombstones().any { it.isNotEmpty() && normalized.contains(it) }
    }

    fun tombstones(): Set<String> =
        if (deletedFile.exists()) deletedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()

    /** 用户点“忘记”一条事实时，把内容写入删除清单，防止旧来源重放复活。 */
    fun addDeletedText(text: String) = synchronized(lock) { addTombstones(listOf(text)) }

    private fun addTombstones(lines: Collection<String>) {
        val all = tombstones() + lines.map { ProfileRules.normalize(it) }.filter { it.isNotEmpty() }
        writeAtomic(deletedFile, all.joinToString("\n"))
    }

    // ---------- 今日流水 ----------

    /**
     * 追加一行交流记录。第一行是日期头，用来让每晚整理知道这是哪一天的。
     * 只记交流本身和回应，屏幕原文不进来。
     *
     * 跨午夜保护：如果文件头是另一天，先把旧内容归档到 archive/<旧日期>.md，
     * 再以新日期重写头部。旧内容不删除，避免跨日时把两天的交流写进同一个文件。
     */
    fun appendToday(today: LocalDate, line: String) { synchronized(lock) {
        val clean = line.replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        val current = todayDate()
        if (current != null && current != today && todayFile.exists()) {
            val old = todayFile.readText()
            val archive = File(archiveDir, "$current.md")
            if (archive.exists()) archive.appendText("\n$old") else writeAtomic(archive, old)
            writeAtomic(todayFile, "# $today\n")
        } else if (!todayFile.exists() || todayFile.length() == 0L) {
            writeAtomic(todayFile, "# $today\n")
        }
        todayFile.appendText("$clean\n")
    } }

    fun todayLines(): List<String> =
        if (todayFile.exists()) todayFile.readLines().filter { it.isNotBlank() && !it.startsWith("# ") } else emptyList()

    fun todayRecent(n: Int = 20): List<String> = todayLines().takeLast(n)

    /** today.md 记录的是哪一天；文件为空时为 null。 */
    fun todayDate(): LocalDate? {
        val first = if (todayFile.exists()) todayFile.useLines { it.firstOrNull() } else null
        return first?.removePrefix("# ")?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    // ---------- 日记和信 ----------

    fun diary(date: LocalDate): String? = File(diaryDir, "$date.md").takeIf { it.exists() }?.readText()

    fun letter(date: LocalDate): String? = File(lettersDir, "$date.md").takeIf { it.exists() }?.readText()

    /** 最近一篇日记（今天之前），探针拿它的开头当“昨天的日记摘要”。 */
    fun latestDiaryBefore(today: LocalDate): Pair<LocalDate, String>? =
        diaryDir.listFiles().orEmpty()
            .mapNotNull { f -> runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull()?.let { it to f } }
            .filter { it.first < today }
            .maxByOrNull { it.first }
            ?.let { it.first to it.second.readText() }

    fun diarySummary(today: LocalDate, maxChars: Int = 200): String =
        latestDiaryBefore(today)?.second?.let { UnicodeText.truncate(it.replace('\n', ' ').trim(), maxChars) } ?: ""

    /** 还没读过的最新一封信。 */
    fun unreadLetter(): Pair<LocalDate, String>? {
        val read = if (letterReadFile.exists()) letterReadFile.readText().trim() else ""
        return lettersDir.listFiles().orEmpty()
            .mapNotNull { f -> runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull()?.let { it to f } }
            .maxByOrNull { it.first }
            ?.takeIf { it.first.toString() > read }
            ?.let { it.first to it.second.readText() }
    }

    fun markLetterRead(date: LocalDate) = writeAtomic(letterReadFile, date.toString())

    fun listDates(dir: File): List<LocalDate> =
        dir.listFiles().orEmpty().mapNotNull { runCatching { LocalDate.parse(it.nameWithoutExtension) }.getOrNull() }.sortedDescending()

    /** 删除 [keepDays] 天以前的归档。 */
    fun pruneArchive(today: LocalDate, keepDays: Long = 30) {
        archiveDir.listFiles().orEmpty().forEach { f ->
            val d = runCatching { LocalDate.parse(f.nameWithoutExtension) }.getOrNull() ?: return@forEach
            if (d.isBefore(today.minusDays(keepDays))) f.delete()
        }
    }

    fun writeAtomic(target: File, text: String) { synchronized(lock) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(text)
        replace(tmp, target)
    } }

    private fun replace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "rename failed: ${target.name}" }
        }
    }
}

/** 档案每行的来源标记和上限。 */
object ProfileRules {
    const val SAID = "[他说过]"
    const val GUESS = "[我推测]"
    const val MAX_CHARS = 3000

    fun hasTag(line: String): Boolean = line.startsWith(SAID) || line.startsWith(GUESS)

    /** 去掉标记、空白和行首列表符号后的内容，用来比对删除清单。 */
    fun normalize(line: String): String =
        line.trim().removePrefix("-").trim().removePrefix(SAID).removePrefix(GUESS).replace(Regex("\\s+"), "").trim()

    fun applyTombstones(lines: List<String>, tombstones: Set<String>): List<String> =
        lines.filter { normalize(it) !in tombstones }

    /** 保留前面的行直到 3000 码点（行之间的换行也算）。 */
    fun clamp(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var total = 0
        for (l in lines) {
            val add = UnicodeText.count(l) + if (out.isEmpty()) 0 else 1
            if (total + add > MAX_CHARS) break
            out += l
            total += add
        }
        return out
    }
}