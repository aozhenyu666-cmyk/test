package cn.companion.pet.core.partner

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProactiveState(
    val shownAt: List<Long> = emptyList(),
    val awaitingSince: Long? = null,
    val ignored: Int = 0,
    val nextAttemptAt: Long = 0,
    val consecutiveFailures: Int = 0,
    val lastOutcome: String = "尚未尝试",
    val recentObservations: List<String> = emptyList(),
)

/** Durable cadence and retry state. Never stores screen text, chat, or credentials. */
class ProactiveStateStore(root: File) {
    private val file = File(root, "partner/proactive.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    var lastError: String? = null
        private set
    fun load(): ProactiveState = try {
        (if (file.exists()) json.decodeFromString(ProactiveState.serializer(), file.readText()) else ProactiveState())
            .also { lastError = null }
    } catch (_: Exception) { lastError = "主动节奏读取失败"; ProactiveState() }
    fun save(state: ProactiveState): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".proactive.tmp")
        tmp.writeText(json.encodeToString(ProactiveState.serializer(), state))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        lastError = null
        true
    } catch (_: Exception) { lastError = "主动节奏保存失败"; false }
}

data class ProactiveProgress(
    val stage: String = "等待启动",
    val detail: String = "启动桌宠后检查主动交流。",
    val at: Long = 0,
    val nextAt: Long = 0,
    val attempts: Int = 0,
    val delivered: Int = 0,
)

/** Failures back off; normal quiet periods never increase the failure counter. */
object ProactiveRetry {
    fun failed(state: ProactiveState, now: Long, reason: String): ProactiveState {
        val n = (state.consecutiveFailures + 1).coerceAtMost(6)
        val gap = (30_000L * (1L shl (n - 1))).coerceAtMost(15 * 60_000L)
        return state.copy(nextAttemptAt = now + gap, consecutiveFailures = n, lastOutcome = reason.take(160))
    }
    fun success(state: ProactiveState) = state.copy(nextAttemptAt = 0, consecutiveFailures = 0, lastOutcome = "已显示或播出")
}
