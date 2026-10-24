package cn.companion.pet.core.action

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * INTERRUPTED：批准后、写回执前进程没了。结果未知，不会自动重做，界面提示用户自己核对。
 */
@Serializable
enum class PendingStatus { PENDING, APPROVED, REJECTED, EXPIRED, EXECUTED, CANCELLED, INTERRUPTED }

@Serializable
data class PendingAction(
    val id: String,
    val kind: ActionKind,
    val args: Map<String, String>,
    /** 参数指纹；批准/重开时核对，防止文件或调用方改参。 */
    val argsHash: String = "",
    val preview: String,
    /** 创建时的前台**包名**（不是显示名）；批准时必须仍是这个应用。 */
    val packageName: String = "",
    val observationId: String = "",
    val sourceIds: List<String> = emptyList(),
    val createdAt: Long,
    val expiresAt: Long,
    val nonce: String,
    val status: PendingStatus = PendingStatus.PENDING,
    val result: ActionResult? = null,
    val approvedAt: Long = 0,
    /** 由目标任务提出时记下任务 ID，执行结果回写到该任务。 */
    val taskId: String = "",
    /** 用户显式“重新确认”的次数；每次重新确认对应一个新的执行 ID。 */
    val attempt: Int = 0,
) {
    fun argsHash(): String = MessageDigest.getInstance("SHA-256")
        .digest((kind.name + args.toSortedMap().entries.joinToString("") { it.key + "=" + it.value })
            .toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 执行 ID：同一次批准无论重试多少次都落在同一个账本条目上，不会重复执行；
     * 用户显式重新确认（[PendingActionStore.reopen]）后换一个新 ID，否则账本会把它当成同一次而永远不执行。
     */
    val executionId: String get() = EXECUTION_PREFIX + id + (if (attempt > 0) "-r$attempt" else "")

    companion object {
        const val EXECUTION_PREFIX = "pending-"
    }
}

/** 待确认动作：一次性 nonce、TTL、持久化、最多 20 条待处理；不自动批准。 */
class PendingActionStore(
    private val root: File,
    private val maxItems: Int = 20,
    /** 已结束（执行/拒绝/过期/中断）的条目只留最近这么多条，文件不会无限变大。 */
    private val maxFinished: Int = 20,
) {
    private val file = File(root, "actions/pending.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile var lastError: String? = null
        private set

    fun list(now: Long): List<PendingAction> = synchronized(lock) {
        val current = read()
        val items = current.map { if (it.status == PendingStatus.PENDING && now > it.expiresAt) it.copy(status = PendingStatus.EXPIRED) else it }
        if (items != current) write(items)
        items
    }

    fun find(id: String): PendingAction? = synchronized(lock) { read().firstOrNull { it.id == id } }

    fun create(kind: ActionKind, args: Map<String, String>, preview: String, packageName: String = "",
               observationId: String = "", sourceIds: List<String> = emptyList(), now: Long,
               ttlMillis: Long = 5 * 60 * 1000, taskId: String = ""): PendingAction? = synchronized(lock) {
        val all = read()
        if (all.count { it.status == PendingStatus.PENDING } >= maxItems) { lastError = "待确认动作已达上限"; return null }
        val base = PendingAction(id = UUID.randomUUID().toString(), kind = kind, args = args.toMap(),
            preview = preview.take(300), packageName = packageName, observationId = observationId,
            sourceIds = sourceIds.take(5), createdAt = now, expiresAt = now + ttlMillis,
            nonce = UUID.randomUUID().toString(), taskId = taskId)
        val action = base.copy(argsHash = base.argsHash())
        if (!write(all + action)) return null
        action
    }

    /**
     * 一次性批准：只有 PENDING 且未过期、前台仍是创建时的应用、nonce 与界面上显示的那一条一致时才成功。
     * nonce 为 null 时不比对（只给旧调用方兼容）；界面调用方都应传入。
     */
    fun approve(id: String, now: Long, foregroundPackage: String? = null, nonce: String? = null): PendingAction? = synchronized(lock) {
        val all = read()
        val item = all.firstOrNull { it.id == id } ?: run { lastError = "找不到这条确认"; return null }
        if (item.status != PendingStatus.PENDING || now > item.expiresAt) {
            write(all.map { if (it.id == id && it.status == PendingStatus.PENDING) it.copy(status = PendingStatus.EXPIRED) else it })
            lastError = "已过期或已处理"
            return null
        }
        if (nonce != null && nonce != item.nonce) {
            lastError = "确认内容已变化，请重新查看"
            return null
        }
        if (item.argsHash.isNotBlank() && item.argsHash != item.argsHash()) {
            lastError = "参数指纹不一致，拒绝执行"
            return null
        }
        if (item.packageName.isNotBlank() && foregroundPackage != item.packageName) {
            lastError = "前台已切换，不能执行旧确认"
            return null
        }
        val approved = item.copy(status = PendingStatus.APPROVED, approvedAt = now)
        if (!write(all.map { if (it.id == id) approved else it })) return null
        approved
    }

    /** 批准后未执行/崩溃：只能显式重开，重新确认，不静默重放。 */
    fun reopen(id: String, now: Long, ttlMillis: Long = 5 * 60 * 1000): PendingAction? = synchronized(lock) {
        val all = read()
        val item = all.firstOrNull { it.id == id && (it.status == PendingStatus.APPROVED || it.status == PendingStatus.INTERRUPTED) }
            ?: return null
        val reopened = item.copy(status = PendingStatus.PENDING, nonce = UUID.randomUUID().toString(),
            createdAt = now, expiresAt = now + ttlMillis, approvedAt = 0, result = null, attempt = item.attempt + 1)
        if (!write(all.map { if (it.id == id) reopened else it })) return null
        reopened
    }

    fun rejectOrCancel(id: String, status: PendingStatus = PendingStatus.REJECTED): Boolean = synchronized(lock) {
        val all = read()
        all.firstOrNull { it.id == id && it.status == PendingStatus.PENDING } ?: return false
        write(all.map { if (it.id == id) it.copy(status = status) else it })
    }

    fun finish(id: String, result: ActionResult): Boolean = synchronized(lock) {
        val all = read()
        all.firstOrNull { it.id == id && it.status == PendingStatus.APPROVED } ?: return false
        write(all.map { if (it.id == id) it.copy(status = PendingStatus.EXECUTED, result = result) else it })
    }

    /**
     * 启动时调用：处理“批准后、写回执前进程被杀”的条目。
     * [lookup] 查动作账本：已有确定结果就补成 EXECUTED；否则标 INTERRUPTED，绝不自动重做。
     */
    fun recoverInterrupted(now: Long, lookup: (executionId: String) -> ActionResult?): List<PendingAction> = synchronized(lock) {
        val all = read()
        val stuck = all.filter { it.status == PendingStatus.APPROVED }
        if (stuck.isEmpty()) return emptyList()
        val updated = all.map { item ->
            if (item.status != PendingStatus.APPROVED) return@map item
            val known = runCatching { lookup(item.executionId) }.getOrNull()
                ?.takeIf { it.data["needsReview"] != "true" }
            if (known != null) item.copy(status = PendingStatus.EXECUTED, result = known)
            else item.copy(status = PendingStatus.INTERRUPTED, result = ActionResult(item.executionId, item.kind,
                ActionStatus.FAILED, "批准后程序中断，结果未知；不会自动重做，请自己核对", mapOf("needsReview" to "true")))
        }
        write(updated)
        updated.filter { u -> stuck.any { it.id == u.id } }
    }

    private fun read(): List<PendingAction> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PendingAction.serializer()), file.readText()) }
            .getOrElse {
                // 损坏文件挪到旁边保留，不让下一次写入把它覆盖掉。
                val backup = File(file.parentFile, "pending.corrupt-" + System.currentTimeMillis() + ".json")
                lastError = if (file.renameTo(backup)) "待确认文件损坏，已另存为 " + backup.name else "待确认文件损坏"
                emptyList()
            }
    }

    private fun write(items: List<PendingAction>): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val open = items.filter { it.status == PendingStatus.PENDING || it.status == PendingStatus.APPROVED }
            val finished = items.filterNot { it.status == PendingStatus.PENDING || it.status == PendingStatus.APPROVED }
                .sortedBy { it.createdAt }.takeLast(maxFinished)
            val kept = items.filter { it in open || it in finished }
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(PendingAction.serializer()), kept))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) return false }
            true
        } catch (_: Exception) { lastError = "待确认写入失败"; false }
    }
}

/** 批准并执行一条待确认动作的唯一入口：设置页和悬浮气泡共用，结果回写待确认表和目标任务。 */
class PendingExecutor(
    private val store: PendingActionStore,
    private val runner: ActionRunner,
    private val onFinished: (PendingAction, ActionResult) -> Unit = { _, _ -> },
    private val onRejected: (PendingAction) -> Unit = {},
) {
    sealed interface Outcome {
        data class Rejected(val reason: String) : Outcome
        data class Executed(val action: PendingAction, val result: ActionResult) : Outcome
    }

    /** 同步执行，调用方放在工作线程。 */
    fun approveAndRun(id: String, now: Long, foregroundPackage: String?, nonce: String): Outcome {
        val approved = store.approve(id, now, foregroundPackage, nonce)
            ?: return Outcome.Rejected(store.lastError ?: "未批准：已过期或前台已切换")
        val result = try {
            runner.execute(ActionRequest(approved.executionId, approved.kind, approved.args))
        } catch (_: Exception) {
            ActionResult(approved.executionId, approved.kind, ActionStatus.FAILED, "动作执行失败")
        }
        store.finish(approved.id, result)
        runCatching { onFinished(approved, result) }
        return Outcome.Executed(approved, result)
    }

    fun reject(id: String): Boolean {
        val item = store.find(id) ?: return false
        if (!store.rejectOrCancel(id)) return false
        runCatching { onRejected(item) }
        return true
    }
}
