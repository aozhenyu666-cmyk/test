package cn.companion.pet.core.training

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 休息许可：练完一个微练习，换一段限定时长的娱乐时间。
 *
 * 关键约束：
 * - 一次训练只能兑换一次，靠 eventId 幂等；
 * - 到期自动失效，不能累积；
 * - 进程重启后不重复发放；
 * - 固定专注模式下不产生许可（那是另一套策略）。
 */
@Serializable
enum class FocusPolicyType { FIXED_FOCUS, PRACTICE_FOR_BREAK }

@Serializable
data class RestGrant(
    val sessionId: String,
    val eventId: String,
    val allowedPackages: Set<String>,
    val grantedAt: Long,
    val expiresAt: Long,
    val revoked: Boolean = false,
) {
    fun active(now: Long): Boolean = !revoked && now < expiresAt
}

/** 许可发放器：纯逻辑 + 原子持久化。 */
class RestGrantStore(private val root: File) {
    private val file = File(root, "training/rest-grant.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile var lastError: String? = null
        private set

    fun load(): RestGrant? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { json.decodeFromString(RestGrant.serializer(), file.readText()) }
            .onFailure { lastError = it.javaClass.simpleName }
            .getOrNull()
    }

    /**
     * 发放许可。同一 sessionId + eventId 只生效一次；
     * 已有未过期许可时不再叠加，返回已有的。
     */
    fun grant(
        policy: FocusPolicyType,
        sessionId: String,
        eventId: String,
        allowedPackages: Set<String>,
        durationMillis: Long,
        now: Long,
    ): RestGrant? = synchronized(lock) {
        if (policy == FocusPolicyType.FIXED_FOCUS) {
            // 固定专注不产生娱乐许可。
            return null
        }
        val existing = runCatching {
            if (file.exists()) json.decodeFromString(RestGrant.serializer(), file.readText()) else null
        }.getOrNull()
        if (existing != null && existing.sessionId == sessionId && existing.eventId == eventId) {
            return existing
        }
        if (existing != null && existing.active(now)) {
            return existing
        }
        val grant = RestGrant(
            sessionId = sessionId,
            eventId = eventId,
            allowedPackages = allowedPackages,
            grantedAt = now,
            expiresAt = now + durationMillis.coerceIn(60_000, 60 * 60_000),
        )
        if (!write(grant)) return null
        grant
    }

    /** 到期检查：已过期则标记撤销并返回 false。 */
    fun active(now: Long): RestGrant? = synchronized(lock) {
        val g = load() ?: return null
        if (g.active(now)) return g
        if (!g.revoked) write(g.copy(revoked = true))
        null
    }

    fun revoke(): Boolean = synchronized(lock) {
        val g = load() ?: return true
        write(g.copy(revoked = true))
    }

    private fun write(grant: RestGrant): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(RestGrant.serializer(), grant))
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
}
