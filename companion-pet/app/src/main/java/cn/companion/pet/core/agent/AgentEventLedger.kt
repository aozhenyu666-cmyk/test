package cn.companion.pet.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AgentEventRecord(val id: String, val at: Long)

/** 临时失败（模型失败/超时/被取消）的事件：记次数和下次可重试时间。 */
@Serializable
data class AgentEventRetry(val id: String, val attempts: Int, val nextAt: Long)

/** 一轮 Agent 结束后，这个事件该怎么记账。 */
enum class EventDisposition {
    /** 有了确定结果（执行、无动作、被拒、需确认……）：记为已处理。 */
    DONE,
    /** 临时失败：退避后重试，最多 [AgentEventLedger.MAX_ATTEMPTS] 次。 */
    RETRY,
    /** 根本没调用模型（预算用完、自主模式关）：不记账、不计次，条件满足后再来。 */
    HOLD,
    ;

    companion object {
        fun of(status: AgentTurnStatus?): EventDisposition = when (status) {
            AgentTurnStatus.MODEL_FAILED, AgentTurnStatus.TIMEOUT, AgentTurnStatus.CANCELLED -> RETRY
            AgentTurnStatus.BUDGET_EXHAUSTED, AgentTurnStatus.DISABLED -> HOLD
            else -> DONE
        }
    }
}

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
    private val retryFile = File(root, "agent/event-retry.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 记为已处理。[at] 必须是事件的**发生时间**：高水位和扫描器比较的是发生时间，
     * 用处理时间会让同一批积压里后面的事件被永久跳过（审计 P1-2）。
     */
    fun mark(id: String, at: Long) = synchronized(lock) {
        if (id.isBlank()) return
        clearRetry(id)
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

    /**
     * 记一次临时失败。返回 true 表示已到上限、放弃并记为已处理；false 表示稍后重试。
     * 退避：第 1 次失败后 1 分钟，第 2 次后 5 分钟。
     */
    fun recordFailure(id: String, occurredAt: Long, now: Long): Boolean = synchronized(lock) {
        val all = readRetries()
        val attempts = (all.firstOrNull { it.id == id }?.attempts ?: 0) + 1
        if (attempts >= MAX_ATTEMPTS) {
            mark(id, occurredAt)
            return true
        }
        val delay = if (attempts == 1) 60_000L else 5 * 60_000L
        writeRetries(all.filterNot { it.id == id } + AgentEventRetry(id, attempts, now + delay))
        false
    }

    /** 这个事件下次最早什么时候可以再试；没有失败记录返回 0。 */
    fun retryAt(id: String): Long = synchronized(lock) { readRetries().firstOrNull { it.id == id }?.nextAt ?: 0L }

    private fun clearRetry(id: String) {
        val all = readRetries()
        if (all.any { it.id == id }) writeRetries(all.filterNot { it.id == id })
    }

    private fun readRetries(): List<AgentEventRetry> = runCatching {
        if (!retryFile.exists()) emptyList()
        else json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AgentEventRetry.serializer()), retryFile.readText())
    }.getOrDefault(emptyList())

    private fun writeRetries(list: List<AgentEventRetry>) {
        retryFile.parentFile?.mkdirs()
        val tmp = File(retryFile.parentFile, "." + retryFile.name + ".tmp")
        tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AgentEventRetry.serializer()), list.takeLast(200)))
        if (!tmp.renameTo(retryFile)) { retryFile.delete(); tmp.renameTo(retryFile) }
    }

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

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
