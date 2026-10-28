package cn.companion.pet.core.partner

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** One durable session shared by the overlay and the full chat page. Caller serializes writes. */
class DialogueStore(root: File) {
    private val file = File(root, "partner/dialogue.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile var lastError: String? = null
        private set
    fun load(): DialogueSession? = runCatching {
        if (file.exists()) json.decodeFromString(DialogueSession.serializer(), file.readText()) else null
    }.getOrElse {
        lastError = "对话读取失败，旧文件已保留备份，不能保证接续原会话"
        runCatching { if (file.exists()) file.copyTo(File(file.parentFile, "dialogue.bad-" + System.nanoTime() + ".json")) }
        null
    }
    fun save(session: DialogueSession) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".dialogue.tmp")
        temp.writeText(json.encodeToString(DialogueSession.serializer(), session.copy(turns = session.turns.filterNot { it.ephemeral })))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        lastError = null
    }
}
