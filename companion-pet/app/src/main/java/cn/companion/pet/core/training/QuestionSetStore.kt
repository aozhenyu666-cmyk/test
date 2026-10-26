package cn.companion.pet.core.training

import kotlinx.serialization.json.Json
import java.io.File

/**
 * 题组状态的持久化（D04）。
 *
 * 为什么必须落盘：第三题之后重启，不能把前两题的凭据丢掉；
 * 也不能因为重启而重复发券或重新计时休息。
 *
 * 单次语义：cycleId 标识一轮题组；grantId 与 cycleId 绑定，重启后按它判重。
 */
class QuestionSetStore(private val root: File) {
    private val file = File(root, "training/question-set.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    @kotlinx.serialization.Serializable
    data class Persisted(
        /** 本轮题组 ID；换新轮时变。 */
        val cycleId: String = "",
        val state: SetState = SetState(),
        /** 已发券 ID，用于重启判重。 */
        val grantId: String = "",
        val updatedAt: Long = 0,
    )

    fun load(): Persisted? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { json.decodeFromString(Persisted.serializer(), file.readText()) }
            .onFailure { lastError = "题组文件读不了：" + it.javaClass.simpleName }
            .getOrNull()
    }

    fun save(p: Persisted): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(Persisted.serializer(), p))
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

    /** 开始新一轮题组：清空凭据，换 cycleId。 */
    fun newCycle(cycleId: String, now: Long): Persisted = Persisted(
        cycleId = cycleId,
        state = SetState(),
        grantId = "",
        updatedAt = now,
    )
}
