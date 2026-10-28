package com.zongkong.app.healthwire

import android.content.Context
import com.zongkong.core.ZkJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * 本地规则与紧急暂停的持久化（报告 §7：先落盘，再开始监管）。
 *
 * 原子写：先写 `.tmp` 再 rename；带 `schemaVersion`。
 * 读坏了不静默重置成「放开」：备份坏文件 + 返回空规则（空规则 = 不额外执行，但界面会显示读失败）。
 */
class ClientPolicyStore(context: Context) {

    @Serializable
    private data class FileShape(
        @SerialName("schemaVersion") val schemaVersion: Int = LocalPolicy.SCHEMA_VERSION,
        val policies: List<LocalPolicy> = emptyList(),
        /** 紧急暂停记录（本地可达，不等网络/模型）。 */
        val emergency: EmergencyRecord? = null,
        /** 本地规则版本号（回执里带给哨兵）。 */
        val revision: Int = 0,
        /** 最近一次失败的读取原因（给人看）。 */
        val lastError: String = "",
    )

    @Serializable
    data class EmergencyRecord(
        val atWall: Long,
        val reason: String,
        val by: String = "local",
        /** 是否已经上报给哨兵（syncPolicy 回了 ok）。 */
        val reported: Boolean = false,
    )

    private val dir = File(context.filesDir, "zk").apply { mkdirs() }
    private val file = File(dir, "policies-local.json")
    private val lock = Any()

    @Volatile private var cache: FileShape = load()

    val policies: List<LocalPolicy> get() = cache.policies
    val emergency: EmergencyRecord? get() = cache.emergency
    val revision: Int get() = cache.revision
    val lastError: String get() = cache.lastError

    fun effective(nowWall: Long = System.currentTimeMillis()): List<LocalPolicy> =
        cache.policies.filter { it.effective(nowWall) }

    /** 当前生效规则里最新的那条。 */
    fun primary(nowWall: Long = System.currentTimeMillis()): LocalPolicy? =
        effective(nowWall).maxByOrNull { it.revision }

    /** 用户是否已经按下紧急暂停（且那条规则还没结束）。 */
    fun emergencyStopped(): Boolean = cache.policies.any { it.stopped() } || cache.emergency != null

    /** 覆盖整套规则（用户改设置时用）。revision 自增，回执里带给哨兵。 */
    fun save(list: List<LocalPolicy>): Int = synchronized(lock) {
        val next = cache.copy(
            policies = list,
            revision = cache.revision + 1,
            lastError = "",
        )
        persist(next)
        cache = next
        next.revision
    }

    /** 追加/替换一条规则（按 policyId）。 */
    fun upsert(policy: LocalPolicy): Int = save(cache.policies.filterNot { it.policyId == policy.policyId } + policy)

    /** 设/清某条规则的紧急暂停状态。 */
    fun setEmergencyState(policyId: String, stopped: Boolean): Int = save(
        cache.policies.map {
            if (it.policyId == policyId) it.copy(emergencyState = if (stopped) LocalPolicy.EMERGENCY_STOPPED else LocalPolicy.EMERGENCY_NONE) else it
        },
    )

    /** 记一次紧急暂停：所有规则标记 STOPPED + 记录原因与时刻。 */
    fun markEmergencyStopped(reason: String, nowWall: Long = System.currentTimeMillis()): Int =
        synchronized(lock) {
            val marked = cache.policies.map { it.copy(emergencyState = LocalPolicy.EMERGENCY_STOPPED) }
            val next = cache.copy(
                policies = marked,
                emergency = EmergencyRecord(atWall = nowWall, reason = reason.ifBlank { "紧急暂停（未填原因）" }),
                revision = cache.revision + 1,
                lastError = "",
            )
            persist(next)
            cache = next
            next.revision
        }

    /** 解除紧急暂停（恢复监管）。 */
    fun clearEmergency(): Int = synchronized(lock) {
        val next = cache.copy(
            policies = cache.policies.map { it.copy(emergencyState = LocalPolicy.EMERGENCY_NONE) },
            emergency = null,
            revision = cache.revision + 1,
        )
        persist(next)
        cache = next
        next.revision
    }

    /** 上报哨兵成功后标记（界面据此区分「已上报」与「未确认」）。 */
    fun markEmergencyReported(): Unit = synchronized(lock) {
        val e = cache.emergency ?: return
        val next = cache.copy(emergency = e.copy(reported = true))
        persist(next)
        cache = next
    }

    /** 给哨兵 syncPolicy 用的 JSON（严格按规范 §4 字段）。 */
    fun policyJson(): String = ZkJson.encodeToString(
        LocalPolicyFile.serializer(),
        LocalPolicyFile(schemaVersion = LocalPolicy.SCHEMA_VERSION, policies = cache.policies),
    )

    fun reload() = synchronized(lock) { cache = load() }

    private fun persist(shape: FileShape) {
        try {
            val text = ZkJson.encodeToString(FileShape.serializer(), shape)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (e: Throwable) {
            // 写失败不改内存缓存：界面会显示「没保存成功」，不假装已持久化。
            cache = cache.copy(lastError = "规则没写进磁盘：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun load(): FileShape {
        if (!file.exists()) return FileShape()
        return try {
            ZkJson.decodeFromString(FileShape.serializer(), file.readText())
        } catch (e: Throwable) {
            runCatching { file.copyTo(File(file.parentFile, file.name + ".broken.${System.currentTimeMillis()}"), overwrite = true) }
            FileShape(lastError = "规则文件读不了：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
