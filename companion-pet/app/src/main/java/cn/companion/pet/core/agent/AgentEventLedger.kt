package cn.companion.pet.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AgentEventRecord(val id: String, val at: Long)

/**
 * 处理事件账本：与 UI 轨迹分离。除有界已处理 ID 外，还持久化高水位，
 * 防止 7 天内事件数超过上限后，被裁掉的旧事件再次触发副作用。
 */
class AgentEventLedger(
    private val root: File,
    private val maxEntries: Int = 2_000,
    private val maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    private val file = File(root, "agent/event-ledger.jsonl")
    private val cursor = File(root, "agent/event-cursor.txt")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun mark(id: String, at: Long) = synchronized(lock) {
        if (id.isBlank()) return
        val records = read(pruneBefore = at - maxAgeMillis)
        if (records.any { it.id == id }) {
            updateCursor(maxOf(highWaterLocked(), at))
            return
        }
        val next = (records + AgentEventRecord(id, at)).takeLast(maxEntries)
        write(next)
        updateCursor(maxOf(highWaterLocked(), at))
    }

    fun processed(now: Long): Set<String> = synchronized(lock) {
        read(pruneBefore = now - maxAgeMillis).map { it.id }.toSet()
    }

    fun highWater(): Long = synchronized(lock) { highWaterLocked() }

    fun count(now: Long): Int = synchronized(lock) { read(pruneBefore = now - maxAgeMillis).size }

    private fun highWaterLocked(): Long {
        val fromFile = runCatching { cursor.readText().trim().toLong() }.getOrDefault(0L)
        val fromRecords = read(pruneBefore = 0).maxOfOrNull { it.at } ?: 0L
        return maxOf(fromFile, fromRecords)
    }

    private fun updateCursor(value: Long) {
        cursor.parentFile?.mkdirs()
        val tmp = File(cursor.parentFile, "." + cursor.name + ".tmp")
        tmp.writeText(value.toString())
        if (!tmp.renameTo(cursor)) { tmp.delete(); cursor.writeText(value.toString()) }
    }

    private fun read(pruneBefore: Long): List<AgentEventRecord> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(AgentEventRecord.serializer(), line) }.getOrNull()
        }.filter { it.at >= pruneBefore }.distinctBy { it.id }.takeLast(maxEntries)
    }

    private fun write(records: List<AgentEventRecord>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        tmp.writeText(records.joinToString("\n") { json.encodeToString(AgentEventRecord.serializer(), it) } + "\n")
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "rename failed: event-ledger" }
        }
    }
}
