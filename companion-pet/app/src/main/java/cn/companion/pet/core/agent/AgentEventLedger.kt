package cn.companion.pet.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AgentEventRecord(val id: String, val at: Long)

/**
 * 处理事件账本：与供 UI 阅读的轨迹分离，只记“这个事件已经给 Agent 处理过”。
 * 跨进程重启保留；按事件生命周期窗口清理，避免 500 条轨迹上限导致旧事件重放。
 */
class AgentEventLedger(
    private val root: File,
    private val maxEntries: Int = 2_000,
    private val maxAgeMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    private val file = File(root, "agent/event-ledger.jsonl")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun mark(id: String, at: Long) = synchronized(lock) {
        if (id.isBlank()) return
        val records = read(pruneBefore = at - maxAgeMillis)
        if (records.any { it.id == id }) return
        val next = (records + AgentEventRecord(id, at)).takeLast(maxEntries)
        write(next)
    }

    fun processed(now: Long): Set<String> = synchronized(lock) {
        read(pruneBefore = now - maxAgeMillis).map { it.id }.toSet()
    }

    fun count(now: Long): Int = synchronized(lock) { read(pruneBefore = now - maxAgeMillis).size }

    private fun read(pruneBefore: Long): List<AgentEventRecord> {
        if (!file.exists()) return emptyList()
        val records = file.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(AgentEventRecord.serializer(), line) }.getOrNull()
        }.filter { it.at >= pruneBefore }.distinctBy { it.id }
        return records.takeLast(maxEntries)
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
