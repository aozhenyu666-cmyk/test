package cn.companion.pet.core.companion

import java.io.File
import java.io.RandomAccessFile
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 按月追加的 JSONL 事件日志。每行一个事件，写坏的最后一行在读取时跳过。
 *
 * 只负责“记录事实”，不负责预算、不负责模型请求。所有读写都在同一把锁里，
 * 以适配应用内服务、WorkManager 和设置页共用一个进程的情况。
 */
class EventLog(
    root: File,
    private val clock: Clock = Clock.systemDefaultZone(),
    val zone: ZoneId = clock.zone,
) {
    val eventsDir = File(root, "events")
    private val nightlyDir = File(root, "nightly")
    private val lock = cn.companion.pet.core.storage.DurableFiles.mutex(eventsDir)
    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    init {
        eventsDir.mkdirs()
        nightlyDir.mkdirs()
    }

    fun dayOf(occurredAt: Long): LocalDate = Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate()

    fun append(
        kind: EventKind,
        text: String,
        occurredAt: Long = clock.millis(),
        source: String = "chat",
        conversationId: String? = null,
        sourceIds: List<String> = emptyList(),
        factKey: String? = null,
        confidence: Confidence = Confidence.SAID,
        expiresAt: Long? = null,
    ): CompanionEvent {
        val clean = sanitize(text)
        if (clean.isEmpty()) {
            // 空文本也返回一条可识别的事件，但不落盘，避免产生“空事实”。
            return CompanionEvent(
                id = UUID.randomUUID().toString(),
                occurredAt = occurredAt,
                day = dayOf(occurredAt).toString(),
                kind = kind,
                text = "",
                source = source,
                conversationId = conversationId,
                sourceIds = sourceIds,
                factKey = factKey,
                confidence = confidence,
                expiresAt = expiresAt,
            )
        }
        val event = CompanionEvent(
            id = UUID.randomUUID().toString(),
            occurredAt = occurredAt,
            day = dayOf(occurredAt).toString(),
            kind = kind,
            text = clean,
            source = source,
            conversationId = conversationId,
            sourceIds = sourceIds,
            factKey = factKey,
            confidence = confidence,
            expiresAt = expiresAt,
        )
        appendEvent(event)
        return event
    }

    fun appendEvent(event: CompanionEvent) {
        synchronized(lock) {
            val file = monthlyFile(event.day)
            repairTruncatedTail(file)
            file.appendText(EventJson.encodeToString(CompanionEvent.serializer(), event) + "\n")
        }
    }

    /** Stable tool receipts are repairable after interruption without duplicating their NOTE. */
    fun appendEventOnce(event: CompanionEvent): CompanionEvent = synchronized(lock) {
        all().firstOrNull { it.id == event.id }?.let { existing ->
            check(existing == event) { "Event identity conflict" }
            return@synchronized existing
        }
        val file = monthlyFile(event.day)
        repairTruncatedTail(file)
        java.io.FileOutputStream(file, true).use { out ->
            out.write((EventJson.encodeToString(CompanionEvent.serializer(), event) + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        event
    }

    /**
     * 上次崩溃可能留下没有换行的半行 JSON。直接 append 会把新事件接到坏行后面，
     * 变成一条无法解析的 JSONL，下一次读取时连新事件一起丢掉。
     * 这里在同一把写锁里把坏尾行隔离到 broken-tails.log，再截断到最后一个完整换行。
     */
    private fun repairTruncatedTail(file: File) {
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { data ->
            val end = data.length()
            data.seek(end - 1)
            if (data.read() == '\n'.code) return
            var boundary = end - 1
            while (boundary >= 0) {
                data.seek(boundary)
                if (data.read() == '\n'.code) break
                boundary--
            }
            val start = boundary + 1
            val bytes = ByteArray((end - start).toInt())
            data.seek(start)
            data.readFully(bytes)
            val tail = bytes.toString(Charsets.UTF_8)
            val complete = runCatching { EventJson.decodeFromString(CompanionEvent.serializer(), tail) }.isSuccess
            if (complete) {
                // A crash between JSON and newline must not discard an otherwise complete event.
                data.seek(end)
                data.write('\n'.code)
            } else {
                File(eventsDir, "broken-tails.log").appendText(clock.millis().toString() + " | " + tail + "\n")
                data.setLength(start)
            }
        }
    }

    /** 全部事件，按发生时间和 id 排成确定顺序。 */
    fun all(): List<CompanionEvent> = synchronized(lock) {
        eventsDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { readFile(it) }
            .sortedWith(compareBy({ it.occurredAt }, { it.id }))
    }

    fun forDay(day: LocalDate): List<CompanionEvent> = synchronized(lock) {
        val key = day.toString()
        val file = monthlyFile(key)
        if (!file.exists()) emptyList() else readFile(file).filter { it.day == key }
            .sortedWith(compareBy({ it.occurredAt }, { it.id }))
    }

    /** 还没被夜间批次确认的事件。已归档 id 写在 archived-<day>.txt 里。 */
    fun pendingForDay(day: LocalDate): List<CompanionEvent> {
        val archived = archivedIds(day)
        return forDay(day).filter { it.id !in archived }
    }

    fun pendingDays(): List<LocalDate> = all()
        .groupBy { it.day }
        .filterKeys { runCatching { LocalDate.parse(it) }.isSuccess }
        .filter { (day, list) -> list.any { it.id !in archivedIds(LocalDate.parse(day)) } }
        .keys
        .map { LocalDate.parse(it) }
        .sorted()

    fun archivedIds(day: LocalDate): Set<String> = synchronized(lock) {
        val f = archiveIdsFile(day)
        if (!f.exists()) emptySet()
        else f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** 幂等：同一批 id 重复执行不会产生额外记录。 */
    fun markArchived(day: LocalDate, ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val all = archivedIds(day) + ids
            writeAtomic(archiveIdsFile(day), all.joinToString("\n"))
        }
    }

    /** 用户主动清空某一天的记录。只影响事件日志和该天归档标记，不动档案/日记/信。 */
    fun clearDay(day: LocalDate) {
        synchronized(lock) {
            val file = monthlyFile(day.toString())
            if (file.exists()) {
                val kept = file.useLines { seq -> seq.filter { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@filter false
                    val event = runCatching { EventJson.decodeFromString(CompanionEvent.serializer(), trimmed) }.getOrNull()
                    event != null && event.day != day.toString()
                }.toList() }
                writeAtomic(file, if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
            }
            archiveIdsFile(day).delete()
        }
    }

    fun recent(limit: Int): List<CompanionEvent> = all().takeLast(limit)

    private fun monthlyFile(day: String): File {
        val month = runCatching { LocalDate.parse(day).format(monthFormat) }.getOrElse { day.take(7) }
        return File(eventsDir, "events-" + month + ".jsonl")
    }

    private fun archiveIdsFile(day: LocalDate): File = File(nightlyDir, "archived-" + day + ".txt")

    private fun readFile(file: File): List<CompanionEvent> = file.useLines { lines ->
        lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            runCatching {
                EventJson.decodeFromString(CompanionEvent.serializer(), trimmed)
            }.getOrNull()
        }.toList()
    }

    private fun sanitize(text: String): String =
        text.replace("\r", " ").replace("\n", " ").trim()

    private fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "." + target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "rename failed: " + target.name }
        }
    }
}
