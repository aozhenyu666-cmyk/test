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
 *
 * P0-6b（`docs/AUDIT-V2.md` §2）：**落盘失败不再假装成功**。
 *   旧实现里 `persist()` 把异常吞成一句 `lastError`，调用方照样拿到一个新 revision，
 *   `HealthWireProvider` 照样回 `ok=true`，哨兵记成「客户端已确认暂停」——
 *   而磁盘上其实什么都没写。现在写失败返回 `ok=false` 且 **revision 不前进**，
 *   调用方必须据 [WriteOutcome.ok] 说话。
 */
class ClientPolicyStore(dir: File) {

    /** 生产入口：`context.filesDir/zk`。 */
    constructor(context: Context) : this(File(context.filesDir, "zk"))

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
        /**
         * 这次暂停的到期时刻（墙钟）。P0-6a 新增。
         *
         * 0 = 老版本数据（升级前按下的暂停）——读取时按 [EmergencyLapse] 补算，**绝不当作永久**。
         */
        val untilWall: Long = 0,
    )

    /** 一次写入的结论。`ok=false` 时 [revision] 是**旧**版本号（没前进），[error] 说明原因。 */
    data class WriteOutcome(val revision: Int, val ok: Boolean, val error: String = "") {
        val failed: Boolean get() = !ok
    }

    private val root: File = dir.apply { mkdirs() }
    private val file = File(root, "policies-local.json")
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

    /**
     * 紧急暂停的当前状态（含**到期判定**，P0-6a）。
     *
     * 以前这里只回答「有没有 STOPPED 记录」，于是客户端一旦被暂停就永久暂停。
     * 现在按 [EmergencyLapse] 判：窗口结束或到上限就自动失效。
     */
    fun emergencyStatus(
        nowWall: Long = System.currentTimeMillis(),
        capMs: Long = EmergencyLapse.DEFAULT_MAX_MS,
    ): EmergencyLapse.Verdict = EmergencyLapse.eval(
        recordAtWall = cache.emergency?.atWall ?: 0L,
        recordUntilWall = cache.emergency?.untilWall ?: 0L,
        policies = cache.policies,
        nowWall = nowWall,
        capMs = capMs,
    )

    /**
     * 用户（或哨兵）是不是已经按下紧急暂停**且还没到期**。
     *
     * 过期时顺手清一次盘：不然界面会一直显示「已暂停」，而实际早就该按规则执行了。
     */
    fun emergencyStopped(
        nowWall: Long = System.currentTimeMillis(),
        capMs: Long = EmergencyLapse.DEFAULT_MAX_MS,
    ): Boolean {
        val v = emergencyStatus(nowWall, capMs)
        if (v.staleOnDisk) {
            val out = clearEmergency(nowWall, "紧急暂停到期，自动恢复（${v.why}）")
            if (out.failed) {
                // 清不掉也不能假装还在暂停：内存里已经不认它了，只是磁盘还是旧值。
                // 下次读取仍会再算一次到期，结论一致。
            }
        }
        return v.active
    }

    /** 覆盖整套规则（用户改设置时用）。revision 自增，回执里带给哨兵。 */
    fun save(list: List<LocalPolicy>): WriteOutcome = synchronized(lock) {
        commit(
            cache.copy(
                policies = list,
                revision = cache.revision + 1,
                lastError = "",
            ),
        )
    }

    /** 追加/替换一条规则（按 policyId）。 */
    fun upsert(policy: LocalPolicy): WriteOutcome =
        save(cache.policies.filterNot { it.policyId == policy.policyId } + policy)

    /** 设/清某条规则的紧急暂停状态。 */
    fun setEmergencyState(policyId: String, stopped: Boolean): WriteOutcome = save(
        cache.policies.map {
            if (it.policyId == policyId) it.copy(emergencyState = if (stopped) LocalPolicy.EMERGENCY_STOPPED else LocalPolicy.EMERGENCY_NONE) else it
        },
    )

    /**
     * 记一次紧急暂停：所有规则标记 STOPPED + 记录原因、时刻与**到期时刻**。
     *
     * 到期时刻在按下这一刻就算死（[EmergencyLapse.deadline]），之后不再漂移 ——
     * 否则「还差 5 分钟到期」会被每秒重算成「还差 2 小时」。
     */
    fun markEmergencyStopped(
        reason: String,
        nowWall: Long = System.currentTimeMillis(),
        capMs: Long = EmergencyLapse.DEFAULT_MAX_MS,
    ): WriteOutcome = synchronized(lock) {
        val marked = cache.policies.map { it.copy(emergencyState = LocalPolicy.EMERGENCY_STOPPED) }
        val until = EmergencyLapse.deadline(cache.policies, nowWall, capMs)
        commit(
            cache.copy(
                policies = marked,
                emergency = EmergencyRecord(
                    atWall = nowWall,
                    reason = reason.ifBlank { "紧急暂停（未填原因）" },
                    untilWall = until,
                ),
                revision = cache.revision + 1,
                lastError = "",
            ),
        )
    }

    /** 解除紧急暂停（恢复监管）。 */
    fun clearEmergency(
        nowWall: Long = System.currentTimeMillis(),
        @Suppress("UNUSED_PARAMETER") why: String = "",
    ): WriteOutcome = synchronized(lock) {
        commit(
            cache.copy(
                policies = cache.policies.map { it.copy(emergencyState = LocalPolicy.EMERGENCY_NONE) },
                emergency = null,
                revision = cache.revision + 1,
                lastError = "",
            ),
        )
    }

    /** 上报哨兵成功后标记（界面据此区分「已上报」与「未确认」）。 */
    fun markEmergencyReported(): WriteOutcome = synchronized(lock) {
        val e = cache.emergency ?: return WriteOutcome(cache.revision, ok = false, error = "没有紧急暂停记录")
        commit(cache.copy(emergency = e.copy(reported = true)))
    }

    /** 给哨兵 syncPolicy 用的 JSON（严格按规范 §4 字段）。 */
    fun policyJson(): String = ZkJson.encodeToString(
        LocalPolicyFile.serializer(),
        LocalPolicyFile(schemaVersion = LocalPolicy.SCHEMA_VERSION, policies = cache.policies),
    )

    fun reload() = synchronized(lock) { cache = load() }

    /**
     * 先落盘，再换内存：**写不进去就不换**。
     *
     * @return 成功就返回新版本号；失败返回旧版本号 + 原因，且磁盘与内存都保持旧状态。
     */
    private fun commit(next: FileShape): WriteOutcome {
        val error = persist(next)
        return if (error == null) {
            cache = next
            WriteOutcome(next.revision, ok = true)
        } else {
            // 只补一条给人看的错误，不动 policies / revision / emergency。
            cache = cache.copy(lastError = error)
            WriteOutcome(cache.revision, ok = false, error = error)
        }
    }

    /** @return null = 写成功；否则是给人看的失败原因。 */
    private fun persist(shape: FileShape): String? = try {
        val text = ZkJson.encodeToString(FileShape.serializer(), shape)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        null
    } catch (e: Throwable) {
        // 不假装已持久化：内存缓存里的规则与版本号都不动。
        "规则没写进磁盘：${e.message ?: e.javaClass.simpleName}"
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
