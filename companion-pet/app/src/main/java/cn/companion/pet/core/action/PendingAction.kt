package cn.companion.pet.core.action

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable
enum class PendingStatus { PENDING, APPROVED, REJECTED, EXPIRED, EXECUTED, CANCELLED }

@Serializable
data class PendingAction(
    val id: String,
    val kind: ActionKind,
    val args: Map<String, String>,
    val preview: String,
    val packageName: String = "",
    val observationId: String = "",
    val sourceIds: List<String> = emptyList(),
    val createdAt: Long,
    val expiresAt: Long,
    val nonce: String,
    val status: PendingStatus = PendingStatus.PENDING,
    val result: ActionResult? = null,
) {
    fun argsHash(): String = MessageDigest.getInstance("SHA-256")
        .digest((kind.name + args.toSortedMap().entries.joinToString("") { it.key + "=" + it.value })
            .toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** 待确认动作：一次性 nonce、TTL、持久化、最多 20 条；不自动批准。 */
class PendingActionStore(private val root: File, private val maxItems: Int = 20) {
    private val file = File(root, "actions/pending.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile var lastError: String? = null
        private set

    fun list(now: Long): List<PendingAction> = synchronized(lock) {
        val items = read().map { if (it.status == PendingStatus.PENDING && now > it.expiresAt) it.copy(status = PendingStatus.EXPIRED) else it }
        if (items != read()) write(items)
        items
    }

    fun create(kind: ActionKind, args: Map<String, String>, preview: String, packageName: String = "",
               observationId: String = "", sourceIds: List<String> = emptyList(), now: Long,
               ttlMillis: Long = 5 * 60 * 1000): PendingAction? = synchronized(lock) {
        val all = read()
        if (all.count { it.status == PendingStatus.PENDING } >= maxItems) { lastError = "待确认动作已达上限"; return null }
        val action = PendingAction(UUID.randomUUID().toString(), kind, args.toMap(), preview.take(300), packageName,
            observationId, sourceIds.take(5), now, now + ttlMillis, UUID.randomUUID().toString())
        if (!write(all + action)) return null
        action
    }

    /** Atomic one-time transition; returns the approved action only once. */
    fun approve(id: String, now: Long, foregroundPackage: String? = null): PendingAction? = synchronized(lock) {
        val all = read()
        val item = all.firstOrNull { it.id == id } ?: return null
        if (item.status != PendingStatus.PENDING || now > item.expiresAt) {
            write(all.map { if (it.id == id && it.status == PendingStatus.PENDING) it.copy(status = PendingStatus.EXPIRED) else it })
            return null
        }
        if (item.packageName.isNotBlank() && foregroundPackage != item.packageName) {
            lastError = "前台已切换，不能执行旧确认"
            return null
        }
        val approved = item.copy(status = PendingStatus.APPROVED)
        if (!write(all.map { if (it.id == id) approved else it })) return null
        approved
    }

    fun rejectOrCancel(id: String, status: PendingStatus = PendingStatus.REJECTED): Boolean = synchronized(lock) {
        val all = read()
        val item = all.firstOrNull { it.id == id && it.status == PendingStatus.PENDING } ?: return false
        write(all.map { if (it.id == id) it.copy(status = status) else it })
    }

    fun finish(id: String, result: ActionResult): Boolean = synchronized(lock) {
        val all = read()
        val item = all.firstOrNull { it.id == id && it.status == PendingStatus.APPROVED } ?: return false
        write(all.map { if (it.id == id) it.copy(status = PendingStatus.EXECUTED, result = result) else it })
    }

    private fun read(): List<PendingAction> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PendingAction.serializer()), file.readText()) }
            .getOrElse { lastError = "待确认文件损坏"; emptyList() }
    }

    private fun write(items: List<PendingAction>): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PendingAction.serializer()), items))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) return false }
            lastError = null; true
        } catch (_: Exception) { lastError = "待确认写入失败"; false }
    }
}
