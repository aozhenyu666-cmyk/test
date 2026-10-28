package cn.companion.pet.core.partner

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** One durable session shared by the overlay and the full chat page. Caller serializes writes. */
class DialogueStore(root: File, private val backup: (File, File) -> Unit = { source, target -> source.copyTo(target); Unit }) {
    private val file = File(root, "partner/dialogue.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var canWrite = true
    @Volatile var lastError: String? = null
        private set
    fun load(): DialogueSession? = runCatching {
        val session = if (file.exists()) json.decodeFromString(DialogueSession.serializer(), file.readText()) else null
        canWrite = true
        lastError = null
        session
    }.getOrElse {
        val copied = runCatching { if (file.exists()) backup(file, File(file.parentFile, "dialogue.bad-" + System.nanoTime() + ".json")) }.isSuccess
        canWrite = copied
        lastError = if (copied) "对话读取失败，旧文件已保留备份，不能保证接续原会话"
            else "旧对话读取和备份均失败，已暂停覆盖保存；请检查存储空间后重启重试"
        null
    }
    fun save(session: DialogueSession) {
        check(canWrite) { "Cannot replace an unreadable dialogue without a backup" }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".dialogue.tmp")
        temp.writeText(json.encodeToString(DialogueSession.serializer(), session.copy(turns = session.turns.filterNot { it.ephemeral })))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        lastError = null
    }
}
