package cn.companion.pet.core.memory

import cn.companion.pet.core.companion.CompanionEvent
import cn.companion.pet.core.companion.EventKind
import cn.companion.pet.core.companion.EventLog
import cn.companion.pet.core.companion.EventVisibility
import cn.companion.pet.core.companion.LegacyEventImport
import cn.companion.pet.net.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 一个夜间整理批次：固定日期、事件边界和档案版本。 */
@Serializable
data class NightlyBatch(
    val id: String,
    val day: String,
    val eventIds: List<String>,
    val profileVersion: String,
    val personaVersion: String,
    val deletedVersion: String,
    val createdAt: Long,
    val status: String = NightlyStore.STATUS_PREPARED,
    val outputHash: String? = null,
    val factsVersion: String = "",
)

/** 提交日志中的单个目标文件。beforeHash 为 null 表示提交前文件不存在。 */
@Serializable
data class JournalTarget(
    val relativePath: String,
    val beforeHash: String? = null,
    val afterHash: String,
    val afterText: String,
)

/**
 * 一次提交的持久化日志。它先于任何目标文件写入磁盘，所以：
 * - 写目标文件途中崩溃，恢复时能拿同样的内容重写；
 * - 目标文件已经被用户改过（既不是 before 也不是 after），恢复时检测为冲突，
 *   不会覆盖用户的新改动。
 */
@Serializable
data class NightlyJournal(
    val batchId: String,
    val day: String,
    val targets: List<JournalTarget>,
    val archiveLines: List<String>,
    val eventIds: List<String>,
    val createdAt: Long,
    /** journal 写入时的删除版本；恢复前必须一致，否则用户已经忘记过内容。 */
    val deletedVersion: String = "",
)

data class PreparedNightly(val batch: NightlyBatch, val messages: List<ChatMessage>)

enum class NightlyCommitOutcome { COMMITTED, ALREADY_COMMITTED, CONFLICT, MISSING }

/**
 * 夜间整理的准备/提交/恢复。所有文件操作都在 [lock] 内，请求本身在锁外。
 *
 * 关键规则：
 * - 批次只包含准备那一刻的事件；请求期间新增的事件不会进入批次，也不会被归档。
 * - 提交前重新核对 profile 版本；用户改过档案就放弃本次提交，交由下一次重算。
 * - 先写 journal 再写目标文件；恢复时按 after 内容重写，或检测到用户新改动后放弃。
 */
class NightlyStore(
    private val memory: MemoryStore,
    private val events: EventLog,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: ZoneId = clock.zone,
    private val facts: cn.companion.pet.core.companion.FactStore? = null,
) {
    private val nightlyDir = File(memory.root, "nightly")

    /** 与 UI 共用 memory.lock；不能再用独立锁，否则版本检查与用户编辑之间仍有窗口。 */
    private val lock: Any = memory.lock
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 测试用故障注入：在指定步骤前调用；抛异常即模拟崩溃。 */
    var fault: (String) -> Unit = {}

    init {
        nightlyDir.mkdirs()
    }

    fun prepare(today: LocalDate = LocalDate.now(clock)): PreparedNightly? = synchronized(lock) {
        LegacyEventImport.ensure(memory, events, zone)
        val day = events.pendingDays().firstOrNull { !it.isAfter(today) } ?: return null
        val allPending = events.pendingForDay(day)
        if (allPending.isEmpty()) return null
        val profile = memory.profile()
        val persona = memory.persona()
        val deleted = memory.tombstones()
        // 用户忘记的内容不进入夜间整理；被过滤的事件直接标记已处理，避免永远卡在待处理里。
        val visibility = EventVisibility(events.all(), deleted, clock.millis())
        val pending = allPending.filter { visibility.allows(it) }
        if (pending.isEmpty()) {
            events.markArchived(day, allPending.map { it.id })
            return null
        }
        val profileVersion = hash(profile)
        val personaVersion = hash(persona)
        val deletedVersion = hash(deleted.sorted().joinToString("\n"))
        val factsVersion = facts?.versionHash().orEmpty()
        val id = batchId(day, pending, listOf(profileVersion, personaVersion, deletedVersion, factsVersion).joinToString("|"))
        val stored = readBatch(id)
        if (stored != null && stored.status == STATUS_COMMITTED) return null
        val batch = stored ?: NightlyBatch(
            id = id,
            day = day.toString(),
            eventIds = pending.map { it.id },
            profileVersion = profileVersion,
            personaVersion = personaVersion,
            deletedVersion = deletedVersion,
            createdAt = clock.millis(),
            factsVersion = factsVersion,
        ).also { writeJson(batchFile(id), it) }

        // 已经有提交日志：不要重新请求，交给 recover() 完成。
        if (journalFile(batch.id).exists()) return null

        val messages = Nightly.prompt(
            persona = persona,
            profile = ProfileRules.applyTombstones(profile.lines(), deleted).joinToString("\n"),
            todayLines = renderLines(pending),
            yesterdayDiary = diaryForContext(day.minusDays(1), clock.millis()),
            deleted = deleted,
        )
        PreparedNightly(batch, messages)
    }

    fun commit(batch: NightlyBatch, output: NightlyOutput): NightlyCommitOutcome = synchronized(lock) {
        val stored = readBatch(batch.id) ?: return NightlyCommitOutcome.MISSING
        if (stored.status == STATUS_COMMITTED) return NightlyCommitOutcome.ALREADY_COMMITTED
        val currentEvents = events.all()
        val currentVisibility = EventVisibility(currentEvents, memory.tombstones(), clock.millis())
        val visibleIds = currentEvents.filter { currentVisibility.allows(it) }.map { it.id }.toSet()
        if (stored.eventIds.any { it !in visibleIds }) {
            batchFile(stored.id).delete()
            return NightlyCommitOutcome.CONFLICT
        }
        if (stored.profileVersion != hash(memory.profile()) ||
            stored.personaVersion != hash(memory.persona()) ||
            stored.deletedVersion != hash(memory.tombstones().sorted().joinToString("\n")) ||
            stored.factsVersion != facts?.versionHash().orEmpty()) {
            // 用户在请求期间改了档案：不覆盖，作废批次，事件继续保持待处理。
            batchFile(stored.id).delete()
            return NightlyCommitOutcome.CONFLICT
        }

        // 先冻结提交基线，再迭代模型输出。用户可能在输出列表求值期间改档案；
        // 如果那时才读取 beforeHash，会把用户的新版本误当成可覆盖基线（H1）。
        val deletedBefore = memory.tombstones()
        val day = LocalDate.parse(stored.day)
        val diaryRel = "diary/" + day + ".md"
        val letterRel = "letters/" + day + ".md"
        val profileBeforeHash = existingHash("profile.md")
        val diaryBefore = existingHash(diaryRel)
        val letterBefore = existingHash(letterRel)

        val batchSourceEvents = events.all().filter { it.id in stored.eventIds }
        val consentedLines = cn.companion.pet.core.partner.PartnerConsent.nightlyProfile(output.profileLines, memory.profileLines(), batchSourceEvents)
        val protected = facts?.protectUserFacts(consentedLines, deletedBefore) ?: consentedLines
        val profileText = ProfileRules.applyTombstones(protected, deletedBefore).joinToString("\n")
        val diaryText = output.diary.lines().filterNot { EventVisibility.containsForgotten(it, deletedBefore) }.joinToString("\n")
        val letterText = output.letter.lines().filterNot { EventVisibility.containsForgotten(it, deletedBefore) }.joinToString("\n")
        val targets = listOf(
            JournalTarget("profile.md", profileBeforeHash, hash(profileText), profileText),
            JournalTarget(diaryRel, diaryBefore, hash(diaryText), diaryText),
            JournalTarget(letterRel, letterBefore, hash(letterText), letterText),
        )
        val visibility = EventVisibility(events.all(), deletedBefore, clock.millis())
        val batchEvents = events.pendingForDay(day).filter { it.id in stored.eventIds && visibility.allows(it) }
        if (batchEvents.isEmpty()) {
            batchFile(stored.id).delete()
            return NightlyCommitOutcome.CONFLICT
        }
        val journal = NightlyJournal(
            batchId = stored.id,
            day = stored.day,
            targets = targets,
            archiveLines = renderLines(batchEvents),
            eventIds = batchEvents.map { it.id },
            createdAt = clock.millis(),
            deletedVersion = hash(deletedBefore.sorted().joinToString("\n")),
        )
        writeJson(journalFile(stored.id), journal)
        fault("journal-written")

        return try {
            applyJournal(journal)
            facts?.syncFromProfile(profileText.lines().filter { it.isNotBlank() }, stored.eventIds, memory.tombstones())
            writeJson(batchFile(stored.id), stored.copy(status = STATUS_COMMITTED, outputHash = hash(profileText + "\n" + output.diary + "\n" + output.letter)))
            fault("batch-committed")
            journalFile(stored.id).delete()
            NightlyCommitOutcome.COMMITTED
        } catch (conflict: UserEditConflict) {
            journalFile(stored.id).delete()
            batchFile(stored.id).delete()
            NightlyCommitOutcome.CONFLICT
        }
    }

    /**
     * 进程重启后调用：把已经写了 journal 的提交继续做完。
     * 幂等；某一目标被用户改过则作废该批次，不覆盖用户改动。
     */
    fun recover(): List<String> = synchronized(lock) {
        val done = mutableListOf<String>()
        nightlyDir.listFiles { f -> f.isFile && f.name.startsWith("journal-") && f.name.endsWith(".json") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val journal = runCatching {
                    json.decodeFromString(NightlyJournal.serializer(), file.readText())
                }.getOrNull() ?: return@forEach
                try {
                    applyJournal(journal)
                    val profileTarget = journal.targets.firstOrNull { it.relativePath == "profile.md" }
                    if (profileTarget != null) {
                        facts?.syncFromProfile(
                            profileTarget.afterText.lines().filter { it.isNotBlank() },
                            journal.eventIds,
                            memory.tombstones(),
                        )
                    }
                    val batch = readBatch(journal.batchId)
                    if (batch != null) {
                        writeJson(batchFile(batch.id), batch.copy(status = STATUS_COMMITTED))
                    }
                    file.delete()
                    done += journal.batchId
                } catch (conflict: UserEditConflict) {
                    file.delete()
                    batchFile(journal.batchId).delete()
                }
            }
        done
    }

    private fun applyJournal(journal: NightlyJournal) {
        val currentDeleted = hash(memory.tombstones().sorted().joinToString("\n"))
        if (journal.deletedVersion != currentDeleted) throw UserEditConflict()
        val snapshot = events.all()
        val visibility = EventVisibility(snapshot, memory.tombstones(), clock.millis())
        val allowedIds = snapshot.filter { visibility.allows(it) }.map { it.id }.toSet()
        if (journal.eventIds.any { it !in allowedIds }) throw UserEditConflict()
        // Validate every file before the first write; a diary conflict must not leave a new profile behind.
        journal.targets.forEach { target ->
            val current = existingHash(target.relativePath)
            if (current != target.beforeHash && current != target.afterHash) throw UserEditConflict()
        }
        journal.targets.forEach { target ->
            fault("target:" + target.relativePath)
            val file = File(memory.root, target.relativePath)
            val current = if (file.exists()) hash(file.readText()) else null
            when {
                current == target.afterHash -> Unit
                current == target.beforeHash -> writeAtomic(file, target.afterText)
                else -> throw UserEditConflict()
            }
        }
        fault("archive")
        appendArchive(journal)
        fault("mark-archived")
        events.markArchived(LocalDate.parse(journal.day), journal.eventIds)
    }

    /** A day's derived diary is not model evidence once one of its recorded sources was withdrawn. */
    fun diaryForContext(day: LocalDate, now: Long = clock.millis()): String = synchronized(lock) {
        val snapshot = events.all()
        val visibility = EventVisibility(snapshot, memory.tombstones(), now)
        if (snapshot.any { it.day == day.toString() && it.kind != EventKind.FORGET && !visibility.allows(it) }) return ""
        memory.diary(day).orEmpty().lines()
            .filterNot { EventVisibility.containsForgotten(it, memory.tombstones()) }.joinToString("\n")
    }

    fun diarySummary(today: LocalDate, maxChars: Int = 200): String = synchronized(lock) {
        memory.listDates(memory.diaryDir).filter { it < today }.firstNotNullOfOrNull { day ->
            diaryForContext(day).takeIf { it.isNotBlank() }
        }?.let { cn.companion.pet.core.text.UnicodeText.truncate(it.replace('\n', ' '), maxChars) }.orEmpty()
    }

    /** 归档文件内带批次标记，崩溃重试时不会重复追加同一批。 */
    private fun appendArchive(journal: NightlyJournal) {
        val archive = File(memory.archiveDir, journal.day + ".md")
        val existing = if (archive.exists()) archive.readText() else ""
        val marker = "<!-- nightly:" + journal.batchId + " -->"
        if (existing.contains(marker) || journal.archiveLines.isEmpty()) return
        val body = buildString {
            if (existing.isNotEmpty()) append(existing)
            if (existing.isNotEmpty() && !existing.endsWith("\n")) append('\n')
            append(marker).append('\n')
            journal.archiveLines.forEach { append(it).append('\n') }
        }
        writeAtomic(archive, body)
    }

    private fun existingHash(relativePath: String): String? {
        val file = File(memory.root, relativePath)
        return if (file.exists()) hash(file.readText()) else null
    }

    private fun renderLines(events: List<CompanionEvent>): List<String> = events.map { e ->
        when (e.kind) {
            EventKind.EXCHANGE, EventKind.LEGACY_LINE -> e.text
            else -> {
                val time = Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalTime().format(timeFormat)
                "- " + time + " | " + e.kind.name.lowercase() + " | " + e.text
            }
        }
    }

    private fun batchId(day: LocalDate, pending: List<CompanionEvent>, profileVersion: String): String =
        "nb-" + day + "-" + hash(day.toString() + "|" + pending.joinToString(",") { it.id } + "|" + profileVersion).take(16)

    private fun batchFile(id: String) = File(nightlyDir, "batch-" + id + ".json")

    private fun journalFile(id: String) = File(nightlyDir, "journal-" + id + ".json")

    private fun readBatch(id: String): NightlyBatch? {
        val file = batchFile(id)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(NightlyBatch.serializer(), file.readText()) }.getOrNull()
    }

    private fun <T> writeJson(file: File, value: T, serializer: kotlinx.serialization.KSerializer<T>) {
        writeAtomic(file, json.encodeToString(serializer, value))
    }

    private fun writeJson(file: File, value: NightlyBatch) = writeJson(file, value, NightlyBatch.serializer())

    private fun writeJson(file: File, value: NightlyJournal) = writeJson(file, value, NightlyJournal.serializer())

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "rename failed: " + target.name }
        }
    }

    companion object {
        const val STATUS_PREPARED = "prepared"
        const val STATUS_COMMITTED = "committed"

        fun hash(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }
    }

    private class UserEditConflict : RuntimeException()
}
