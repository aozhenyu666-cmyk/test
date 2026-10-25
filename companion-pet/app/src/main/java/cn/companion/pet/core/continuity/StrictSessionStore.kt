package cn.companion.pet.core.continuity

import kotlinx.serialization.json.Json
import java.io.File

/**
 * 严格回合的持久化。
 *
 * 只恢复“未结束且未到期”的回合；已结束/已到期保留墓碑，服务重启不得复活。
 * 进程被杀期间不可观测，恢复时必须如实承认这段空窗，不补造期间行为。
 */
class StrictSessionStore(private val root: File) {
    private val file = File(root, "continuity/strict.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    fun load(): StrictSessionState? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { json.decodeFromString(StrictSessionState.serializer(), file.readText()) }
            .onFailure { lastError = "严格会话文件读不了：" + it.javaClass.simpleName }
            .getOrNull()
    }

    fun save(state: StrictSessionState): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(StrictSessionState.serializer(), state))
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (t: Exception) {
            lastError = t.javaClass.simpleName + ":" + t.message
            false
        }
    }

    fun clear(): Boolean = synchronized(lock) { file.delete() }

    /** 恢复结果：要么接着原回合，要么明确为什么不接着。 */
    sealed interface Restore {
        data class Resume(val state: StrictSessionState, val gapMillis: Long) : Restore
        data class Expired(val state: StrictSessionState) : Restore
        data class Finished(val state: StrictSessionState) : Restore
        data object None : Restore
    }

    /** [now] 为本次启动的可信时间；空窗按 updatedAt 到 now 计算，仅用于如实记录。 */
    fun restore(now: Long): Restore = synchronized(lock) {
        val state = load() ?: return Restore.None
        if (!state.active) return Restore.Finished(state)
        if (now >= state.endsAt) {
            val ended = state.copy(phase = Phase.EXPIRED, endReason = StrictEndReason.EXPIRED.name, updatedAt = now)
            save(ended)
            return Restore.Expired(ended)
        }
        val gap = (now - state.updatedAt).coerceAtLeast(0)
        val resumed = state.copy(
            updatedAt = now,
            // 重启后输入通道状态未知，先标降级，等真实信号再升回 OK。
            health = Health.DEGRADED,
            silence = null,
        )
        save(resumed)
        Restore.Resume(resumed, gap)
    }
}
