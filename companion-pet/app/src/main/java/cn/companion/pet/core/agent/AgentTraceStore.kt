package cn.companion.pet.core.agent

import kotlinx.serialization.json.Json
import java.io.File

/**
 * 决策轨迹：只存摘要、触发、工具、状态和回执，不存屏幕原文、聊天全文、Key 或动作正文。
 * 追加写 JSONL；超过上限时保留最近 [maxEntries] 条。坏行跳过，不覆盖旧文件。
 */
class AgentTraceStore(
    private val root: File,
    private val maxEntries: Int = 100,
) : AgentTraceSink {
    private val file = File(root, "agent/traces.jsonl")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun append(trace: AgentTrace) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val line = json.encodeToString(AgentTrace.serializer(), trace)
        file.appendText(line + "\n")
        val lines = file.readLines()
        if (lines.size > maxEntries) {
            file.writeText(lines.takeLast(maxEntries).joinToString("\n") + "\n")
        }
    }

    fun recent(limit: Int = 10): List<AgentTrace> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(AgentTrace.serializer(), line) }.getOrNull()
        }.takeLast(limit.coerceIn(1, maxEntries))
    }

    fun lastAt(): Long = recent(1).lastOrNull()?.at ?: 0L
}
