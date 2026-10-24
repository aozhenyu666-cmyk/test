package cn.companion.pet.core.action

import java.io.File
import cn.companion.pet.core.storage.DurableFiles
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class WriteReceipt(
    val id: String,
    val fingerprint: String,
    val kind: ActionKind,
    val preparedAt: Long,
    val phase: String = "prepared",
    val result: ActionResult? = null,
)

sealed interface WriteClaim {
    data object Prepared : WriteClaim
    data class Existing(val receipt: WriteReceipt) : WriteClaim
    data class Rejected(val reason: String) : WriteClaim
}

data class ActionHistoryEntry(val id: String, val kind: ActionKind, val preparedAt: Long, val status: ActionStatus?)

/** Metadata only. No expiry/eviction: old identities remain protected until app data is removed. */
enum class JournalCheckpoint { AFTER_PREPARE, BEFORE_RECEIPT }

class FileActionJournal(
    private val dir: File,
    private val capacity: Int = 4096,
    private val checkpoint: (JournalCheckpoint) -> Unit = {},
) {
    private val file = File(dir, "writes.json")
    private val json = Json { encodeDefaults = true }
    init { require(capacity > 0) }

    fun prepare(request: ActionRequest, fingerprint: String): WriteClaim = DurableFiles.locked(dir) {
        val entries = read()
        entries.firstOrNull { it.id == request.id }?.let {
            return@locked if (it.fingerprint != fingerprint || it.kind != request.kind)
                WriteClaim.Rejected("同一执行 ID 不能更换动作或参数") else WriteClaim.Existing(it)
        }
        if (entries.size >= capacity) return@locked WriteClaim.Rejected("执行记录已满，请先核对已有操作；不会清除旧记录重做")
        write(entries + WriteReceipt(request.id, fingerprint, request.kind, System.currentTimeMillis()))
        checkpoint(JournalCheckpoint.AFTER_PREPARE)
        WriteClaim.Prepared
    }

    fun commit(request: ActionRequest, fingerprint: String, result: ActionResult): ActionResult = DurableFiles.locked(dir) {
        val entries = read()
        val old = entries.single { it.id == request.id }
        check(old.fingerprint == fingerprint && old.kind == request.kind)
        old.result?.let { return@locked it }
        val safe = receiptResult(result)
        checkpoint(JournalCheckpoint.BEFORE_RECEIPT)
        write(entries.map { if (it.id == request.id) it.copy(phase = "committed", result = safe) else it })
        safe
    }

    fun receipt(id: String): WriteReceipt? = DurableFiles.locked(dir) { read().find { it.id == id } }

    fun history(limit: Int): List<ActionHistoryEntry> = DurableFiles.locked(dir) {
        require(limit in 1..50)
        read().takeLast(limit).asReversed().map { ActionHistoryEntry(it.id, it.kind, it.preparedAt, it.result?.status) }
    }

    private fun read(): List<WriteReceipt> {
        if (!file.exists()) return emptyList()
        val list = json.decodeFromString(ListSerializer(WriteReceipt.serializer()), file.readText())
        check(list.map { it.id }.distinct().size == list.size)
        check(list.all { it.phase in setOf("prepared", "committed") &&
            ((it.phase == "committed") == (it.result != null)) &&
            it.kind != ActionKind.READ_MEMORY && it.kind != ActionKind.READ_MATERIALS &&
            (it.result == null || (it.result.actionId == it.id && it.result.kind == it.kind)) })
        return list
    }
    private fun write(entries: List<WriteReceipt>) = DurableFiles.write(file,
        json.encodeToString(ListSerializer(WriteReceipt.serializer()), entries))

    private fun receiptResult(result: ActionResult): ActionResult {
        val reason = when (result.status) {
            ActionStatus.SUCCESS -> when (result.kind) {
                ActionKind.SAVE_NOTE -> "已记录保存结果；当前内容可到记忆页核对"
                ActionKind.OPEN_ASSISTANT -> "系统已接受打开请求，请核对目标应用"
                ActionKind.SPEAK -> "语音引擎已接受请求，不代表已播完"
                else -> "已完成"
            }
            ActionStatus.UNAVAILABLE -> "能力暂不可用，请检查设置或应用是否安装"
            ActionStatus.CANCELLED -> "已取消；已开始的操作不回滚"
            ActionStatus.FAILED -> "操作未完成，请核对后再决定下一步"
        }
        val data = result.data.filter { (key, value) -> key in setOf("eventId", "noteId", "day", "deleted") &&
            value.matches(Regex("[A-Za-z0-9._:-]{1,100}")) }
        return result.copy(reason = reason, data = data)
    }

    companion object {
        fun unknown(id: String, kind: ActionKind) = ActionResult(id, kind, ActionStatus.FAILED,
            "操作结果未确认，需要核对；不会自动重做", mapOf("needsReview" to "true"))
    }
}
