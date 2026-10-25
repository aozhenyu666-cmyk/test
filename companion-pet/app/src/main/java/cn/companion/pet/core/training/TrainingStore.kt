package cn.companion.pet.core.training

import kotlinx.serialization.json.Json
import java.io.File

/**
 * 训练会话持久化。只恢复未结束的会话；结束态保留墓碑，不复活。
 *
 * 进程被杀期间不可观测，恢复时如实记录空窗，不补造期间作答。
 */
class TrainingStore(private val root: File) {
    private val file = File(root, "training/session.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    fun save(session: TrainingSession, question: TrainingQuestion): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val payload = Wrapper(session, question)
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(Wrapper.serializer(), payload))
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

    fun load(): Wrapper? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { json.decodeFromString(Wrapper.serializer(), file.readText()) }
            .onFailure {
                lastError = "训练会话文件读不了：" + it.javaClass.simpleName
                runCatching { file.renameTo(File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())) }
            }
            .getOrNull()
    }

    fun clear(): Boolean = synchronized(lock) { file.delete() }

    sealed interface Restore {
        data class Resume(val session: TrainingSession, val question: TrainingQuestion, val gapMillis: Long) : Restore
        data class Finished(val session: TrainingSession) : Restore
        data object None : Restore
    }

    /**
     * [now] 必须和会话里的 updatedAt 同一个时钟（墙上时间）：单调时钟重启会归零，不能跨进程比较。
     * 空窗按 updatedAt 到 now 计算（调用方定期心跳落盘，误差在心跳间隔内）。
     */
    fun restore(now: Long): Restore = synchronized(lock) {
        val w = load() ?: return Restore.None
        if (!w.session.active) return Restore.Finished(w.session)
        val gap = (now - w.session.updatedAt).coerceAtLeast(0)
        // 重启后不知道输入通道状态；回到等待态，让用户继续同一槽位。
        val resumed = w.session.copy(
            state = TrainingState.WAITING_INPUT,
            pendingRequestId = "",
            updatedAt = now,
        )
        save(resumed, w.question)
        Restore.Resume(resumed, w.question, gap)
    }

    @kotlinx.serialization.Serializable
    data class Wrapper(
        val session: TrainingSession,
        val question: TrainingQuestion,
    )
}
