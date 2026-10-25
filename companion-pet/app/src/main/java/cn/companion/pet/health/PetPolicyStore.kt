package cn.companion.pet.health

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * 同桌的本地规则、锁屏回执、紧急暂停、调用方指纹。
 *
 * 全部落盘 + 原子替换（写 .tmp 再 rename），单进程内加锁；
 * 用共享内核的 [Policy] / [ClampedClock] 做判断，不另造规则语义（WIRE-SPEC-v2 §4）。
 */

/** 规范 §4 的 JSON 形状。字段名逐字对齐，四边要能互读。 */
@Serializable
data class PolicyDto(
    val policyId: String,
    val revision: Int,
    val issuer: String,
    val requiredApps: List<String> = emptyList(),
    val startsAtWall: Long = 0,
    val endsAtWall: Long = 0,
    val lockAtWall: Long = 0,
    val failureAction: String = "LOCK",
    val repairGraceMs: Long = 120_000,
    val emergencyState: String = "NONE",
    val enabled: Boolean = false,
)

@Serializable
data class PolicyFileDto(
    @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA,
    val policies: List<PolicyDto> = emptyList(),
    /** 落盘时的墙钟 / 单调时钟，用于重启后被改时间也不能延长监管。 */
    val anchoredWall: Long = 0,
    val anchoredMono: Long = 0,
) {
    companion object {
        const val SCHEMA = 2
    }
}

/** 本应用只能改自己的规则（issuer = pet / user），不能解除别的产品的规则（规范 §5.3）。 */
fun Policy.ownedByPet(): Boolean = issuer == ISSUER_PET || issuer == ISSUER_USER

/**
 * 本地自己的 issuer 标记（用户在本机设的窗口）。
 * **只用于本地存储**：发给哨兵时必须归一化成 [ISSUER_PET]，见 [wireIssuer]。
 */
const val ISSUER_USER = "user"

/**
 * 哨兵认的 issuer 值：`sentinel/.../watch/PolicyStore.kt:161` 的
 * `issuerForPackage("cn.companion.pet")` 返回 `"pet"`，且 merge 里
 * `if (p.issuer != issuer)` 会拒掉任何别的值。
 *
 * 所以「发出去的报文」里 issuer 必须恒为 `"pet"`——这是跨应用契约，不是本地习惯。
 */
const val ISSUER_PET = "pet"

/**
 * F-06 修复：把本地 issuer 归一化成**哨兵认的值**。
 *
 * 根因：本地存的是 `user`（本机用户设的窗口），哨兵只接受 `pet`
 * （`issuerForPackage("cn.companion.pet") == "pet"`，别的一律 Merge(false)）。
 * 两者不归一化 → `syncPolicy` 恒被拒 → 规则永远进不了哨兵的 `policies.json`
 * → 哨兵侧的 `lockDue()` / `failureWindows()` 永不为同桌触发。
 *
 * 纪律（不要越界）：
 *  - **只改发出去的报文**；本地存储仍是 `user`，不动本地数据；
 *  - 不动 [Policy.ownedByPet] 的语义（本地用 user 是合法的）；
 *  - 不改哨兵、也不要求哨兵放宽接受 `user`——那等于允许同桌往共享的 user
 *    命名空间写规则，是越权，且规范 §3 契约冻结。
 */
fun wireIssuer(localIssuer: String): String = when (localIssuer) {
    ISSUER_USER -> ISSUER_PET
    ISSUER_PET -> ISSUER_PET
    // 别的产品的 issuer 压根不该出现在本应用发出去的报文里；
    // 保持原样，让哨兵照规矩拒掉（宁可被拒，也不要冒名顶替别人）。
    else -> localIssuer
}

fun PolicyDto.toPolicy(): Policy = Policy(
    policyId = policyId,
    revision = revision,
    issuer = issuer,
    requiredApps = requiredApps.toSet(),
    startsAtWall = startsAtWall,
    endsAtWall = endsAtWall,
    lockAtWall = if (lockAtWall <= 0) null else lockAtWall,
    failureAction = failureAction,
    repairGraceMs = repairGraceMs,
    emergencyState = emergencyState,
    enabled = enabled,
)

fun Policy.toDto(): PolicyDto = PolicyDto(
    policyId = policyId,
    revision = revision,
    issuer = issuer,
    requiredApps = requiredApps.toList(),
    startsAtWall = startsAtWall,
    endsAtWall = endsAtWall,
    lockAtWall = lockAtWall ?: 0,
    failureAction = failureAction,
    repairGraceMs = repairGraceMs,
    emergencyState = emergencyState,
    enabled = enabled,
)

/**
 * 规则持久化。先落盘再宣布生效：写盘失败就不说“已就绪”（报告 §7）。
 *
 * 时间口径：内部持有一个 [ClampedClock]，启动时用磁盘上的锚点 [ClampedClock.reanchor] 重新立锚，
 * 所以改系统时间 / 重启不会让监管窗口被拉长（规范 §4、T14）。
 */
class PetPolicyStore(
    root: File,
    private val wall: Wall,
    private val mono: Mono,
    private val replace: (File, File) -> Boolean = ATOMIC_REPLACE,
) {
    private val dir = File(root, "policy")
    private val file = File(dir, "policies.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val clock = ClampedClock(wall, mono)
    private var loaded = false

    /**
     * 内存里的当前快照。挑战回应要在无障碍主线程上读 revision，
     * 不能在主线程反复读文件，所以首次读盘后一直用缓存，写盘时同步更新。
     */
    @Volatile
    private var cache: PolicyFileDto? = null

    @Volatile
    var lastError: String? = null
        private set

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val dto = readFromDisk()
            cache = dto
            if (dto != null && dto.anchoredMono > 0) {
                // 重启后重新立锚：墙上时间只允许按单调流逝前进。
                clock.reanchor(dto.anchoredWall, dto.anchoredMono)
            }
            loaded = true
        }
    }

    private fun read(): PolicyFileDto? {
        ensureLoaded()
        return cache
    }

    private fun readFromDisk(): PolicyFileDto? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(PolicyFileDto.serializer(), file.readText()) }.getOrElse {
            val backup = File(dir, "policies.corrupt-" + wall.now() + ".json")
            lastError = if (file.renameTo(backup)) {
                "规则文件损坏，已另存为 " + backup.name + "；没有沿用任何规则"
            } else "规则文件损坏；没有沿用任何规则"
            null
        }
    }

    /** 当前（压紧后的）墙上时间，界面和判断都用它。 */
    fun nowWall(): Long {
        ensureLoaded()
        return clock.now()
    }

    fun policies(): List<Policy> {
        ensureLoaded()
        return read()?.policies.orEmpty().map { it.toPolicy() }
    }

    /** 当前规则版本号（挑战回应里要带）。没有任何规则时为 0。 */
    fun revision(): Int {
        ensureLoaded()
        return read()?.policies.orEmpty().maxOfOrNull { it.revision } ?: 0
    }

    /** 生效中的窗口（enabled 且落在此刻窗口内）。规范 §4：过期必须停止执行。 */
    fun effective(nowWall: Long = nowWall()): List<Policy> = policies().filter { it.effective(nowWall) }

    /**
     * 写入一份本地规则。只允许改自己的规则；拒绝时返回失败原因，不静默。
     * 成功返回新的 revision（= 该规则的 revision）。
     */
    fun upsert(dto: PolicyDto): Result<Int> {
        ensureLoaded()
        if (dto.issuer != ISSUER_PET && dto.issuer != ISSUER_USER) {
            return Result.failure(IllegalArgumentException("不能修改 issuer=${dto.issuer} 的规则"))
        }
        if (dto.policyId.isBlank()) return Result.failure(IllegalArgumentException("policyId 不能为空"))
        if (dto.endsAtWall <= dto.startsAtWall) return Result.failure(IllegalArgumentException("窗口结束时间必须晚于开始时间"))
        val next = maxOf(dto.revision, (read()?.policies.orEmpty().maxOfOrNull { it.revision } ?: 0) + 1)
        val body = dto.copy(revision = next)
        return writeWith { current ->
            val others = current.filter { it.policyId != body.policyId }
            others + body
        }.map { next }
    }

    /** 紧急暂停只改状态位，不动窗口，下次启用时窗口仍然照原样（不替用户延长）。 */
    fun setEmergencyState(policyId: String, state: String): Result<Unit> = writeWith { current ->
        current.map { if (it.policyId == policyId) it.copy(emergencyState = state) else it }
    }

    /** 把所有本地规则标 STOPPED（紧急暂停用）。空清单也返回成功。 */
    fun stopAll(nowWall: Long): Boolean = writeWith { current ->
        current.map { it.copy(emergencyState = "STOPPED") }
    }.isSuccess

    /** 把所有规则标回 NONE（用户显式恢复）。 */
    fun resumeAll(): Boolean = writeWith { current ->
        current.map { it.copy(emergencyState = "NONE") }
    }.isSuccess

    /**
     * 组合成 [StopSink]：规则文件 + 独立暂停标记两条来源一起管。
     *
     * 注意这两条**必须一起写**（见 EmergencyStopLocal 的注释）：
     * 规则里的 STOPPED 会被「窗口翻页生成新 policyId」顶掉，独立标记不会。
     */
    fun asStopSink(markers: EmergencyPauseStore): StopSink = object : StopSink {
        override fun load(): List<PolicyDto> = this@PetPolicyStore.policies().map { it.toDto() }

        override fun stopAll(nowWall: Long): Boolean = this@PetPolicyStore.stopAll(nowWall)

        override fun resumeAll(): Boolean = this@PetPolicyStore.resumeAll()

        override fun markEmergencyStopped(nowWall: Long, reason: String): Boolean =
            markers.stop(reason, nowWall, nowWall).let {
                // stop() 之后读回核对：只有真的读得回来才算写上。
                markers.current().stopped
            }

        override fun clearEmergencyStopped(): Boolean = markers.resume(atWall = 0L, atMono = 0L).let {
            !markers.current().stopped
        }

        override fun emergencyStopped(): Boolean = markers.current().stopped
    }

    fun remove(policyId: String): Result<Unit> = writeWith { current -> current.filter { it.policyId != policyId } }

    private fun writeWith(change: (List<PolicyDto>) -> List<PolicyDto>): Result<Unit> {
        ensureLoaded()
        return synchronized(lock) {
            val current = cache
            val next = change(current?.policies.orEmpty())
            val w = nowWall()
            val dto = PolicyFileDto(
                schemaVersion = PolicyFileDto.SCHEMA,
                policies = next,
                anchoredWall = w,
                anchoredMono = mono.now(),
            )
            val ok = write(dto)
            if (ok) {
                cache = dto
                lastError = null
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(lastError ?: "规则写入失败"))
            }
        }
    }

    private fun write(dto: PolicyFileDto): Boolean = try {
        dir.mkdirs()
        val tmp = File(dir, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(PolicyFileDto.serializer(), dto))
        if (replace(tmp, file)) true else {
            tmp.delete()
            lastError = "规则写入失败（原子替换被拒绝）"
            false
        }
    } catch (e: Exception) {
        lastError = "规则写入失败：" + e.javaClass.simpleName
        false
    }

    companion object {
        val ATOMIC_REPLACE: (File, File) -> Boolean = { from, to ->
            runCatching {
                java.nio.file.Files.move(
                    from.toPath(), to.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                true
            }.getOrElse {
                runCatching {
                    java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    true
                }.getOrDefault(false)
            }
        }
    }
}

/** 最近一次锁屏回执（挑战回应里要报的状态，规范 §3.2）。 */
@Serializable
data class ReceiptDto(
    val policyId: String = "",
    val incidentId: String = "",
    val requestId: String = "",
    val source: String = SOURCE_PET,
    val reason: String = "",
    val state: String = "",
    val atWall: Long = 0,
    val atMono: Long = 0,
    val note: String = "",
)

const val SOURCE_PET = "pet"

class PetReceiptStore(
    root: File,
    private val replace: (File, File) -> Boolean = PetPolicyStore.ATOMIC_REPLACE,
) {
    private val dir = File(root, "policy")
    private val file = File(dir, "last-receipt.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    var last: ReceiptDto? = null
        private set

    fun load(): ReceiptDto? {
        if (last != null) return last
        if (!file.exists()) return null
        last = runCatching { json.decodeFromString(ReceiptDto.serializer(), file.readText()) }.getOrNull()
        return last
    }

    fun save(receipt: ReceiptDto): Boolean = try {
        last = receipt
        dir.mkdirs()
        val tmp = File(dir, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(ReceiptDto.serializer(), receipt))
        if (replace(tmp, file)) true else { tmp.delete(); false }
    } catch (e: Exception) {
        false
    }
}

/** 紧急暂停：本地、立即、不依赖网络与模型（规范 §6、报告 §3.6）。 */
@Serializable
data class EmergencyDto(
    val stopped: Boolean = false,
    val reason: String = "",
    val atWall: Long = 0,
    val atMono: Long = 0,
    /** 只停这一份规则；空 = 停全部本地规则。 */
    val policyId: String = "",
    /** 哨兵确认收到暂停的时刻（0 = 未确认）。界面据此说“暂停未确认”。 */
    val sentinelAckAtWall: Long = 0,
)

class EmergencyPauseStore(
    root: File,
    private val replace: (File, File) -> Boolean = PetPolicyStore.ATOMIC_REPLACE,
) {
    private val dir = File(root, "policy")
    private val file = File(dir, "emergency.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var cached: EmergencyDto? = null

    fun current(): EmergencyDto {
        cached?.let { return it }
        val loaded = readFromDisk()
        val v = loaded ?: EmergencyDto()
        cached = v
        return v
    }

    /**
     * 从**磁盘**读（不信缓存）。停止动作的「读回核对」必须走这条，
     * 否则缓存会把「没写进去」掩盖成「已暂停」。
     */
    fun readFromDisk(): EmergencyDto? =
        if (file.exists()) runCatching { json.decodeFromString(EmergencyDto.serializer(), file.readText()) }.getOrNull()
        else null

    /**
     * 立即停止本轮锁屏；调用方必须能在没有任何网络/模型的情况下走到这里。
     * 返回的 [EmergencyDto] 是从磁盘读回核对过的结果，写不进去时 `stopped=false`。
     */
    fun stop(reason: String, atWall: Long, atMono: Long, policyId: String = ""): EmergencyDto {
        val v = EmergencyDto(stopped = true, reason = reason, atWall = atWall, atMono = atMono, policyId = policyId)
        val wrote = persist(v)
        // 读回核对：只有磁盘上确实写着 stopped=true 才认。
        val verified = readFromDisk()
        val result = if (wrote && verified?.stopped == true) v else (verified ?: EmergencyDto(policyId = policyId))
        cached = result
        return result
    }

    /** 用户重新开启监管（显式操作）。 */
    fun resume(atWall: Long, atMono: Long): EmergencyDto {
        val v = current().copy(stopped = false, reason = "", atWall = atWall, atMono = atMono, policyId = "", sentinelAckAtWall = 0)
        persist(v)
        val verified = readFromDisk() ?: v
        cached = verified
        return verified
    }

    fun markSentinelAck(atWall: Long): EmergencyDto {
        val v = current().copy(sentinelAckAtWall = atWall)
        persist(v)
        val verified = readFromDisk() ?: v
        cached = verified
        return verified
    }

    /** 这份规则此刻是否被紧急暂停挡住。 */
    fun blocks(policyId: String): Boolean {
        val v = current()
        if (!v.stopped) return false
        return v.policyId.isBlank() || v.policyId == policyId
    }

    /** 三条来源里的第 3 条：独立标记文件是否处于暂停。 */
    fun emergencyStopped(): Boolean = current().stopped

    private fun persist(v: EmergencyDto): Boolean = runCatching {
        dir.mkdirs()
        val tmp = File(dir, "." + file.name + ".tmp")
        tmp.writeText(json.encodeToString(EmergencyDto.serializer(), v))
        if (replace(tmp, file)) true else {
            tmp.delete()
            false
        }
    }.getOrDefault(false)
}

/** 调用方指纹（TOFU）：第一次见到哨兵的证书摘要就记住，之后不一致一律拒绝。 */
class CallerPinStore(
    root: File,
    private val replace: (File, File) -> Boolean = PetPolicyStore.ATOMIC_REPLACE,
) {
    private val dir = File(root, "policy")
    private val file = File(dir, "sentinel-pin.txt")

    fun pinned(): String? = if (file.exists()) runCatching { file.readText().trim() }.getOrNull()?.ifBlank { null } else null

    fun pin(digest: String): Boolean = runCatching {
        dir.mkdirs()
        val tmp = File(dir, ".sentinel-pin.tmp")
        tmp.writeText(digest)
        if (replace(tmp, file)) true else { tmp.delete(); false }
    }.getOrDefault(false)
}
