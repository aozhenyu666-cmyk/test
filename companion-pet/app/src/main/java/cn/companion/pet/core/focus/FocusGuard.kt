package cn.companion.pet.core.focus

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class FocusMode { TEXT, LIVE }

@Serializable
enum class FocusEnforcement { SOFT, STRICT }

@Serializable
enum class FocusState {
    DISARMED,
    ARMED,
    IN_TARGET,
    AWAY_GRACE,
    BLOCKING,
    RETURNING,
    VERIFYING,
    PAUSED,
    COMPLETED,
    USER_ABORTED,
}

@Serializable
enum class FocusEndReason { EXPIRED, COMPLETED, USER_ABORTED, TARGET_UNAVAILABLE, SERVICE_LOST }

@Serializable
data class FocusContract(
    val sessionId: String,
    val targetPackage: String,
    val targetLabel: String,
    val startedAt: Long,
    val expiresAt: Long,
    val mode: FocusMode,
    val enforcement: FocusEnforcement,
    val allowedPackages: Set<String> = emptySet(),
    val graceMillis: Long = 10_000,
    val allowedGraceMillis: Long = 90_000,
    val pauseMillis: Long = 5 * 60_000,
    val maxReturnAttempts: Int = 5,
    val retryBackoffMillis: Long = 8_000,
)

@Serializable
data class FocusSnapshot(
    val contract: FocusContract,
    val state: FocusState,
    val lastConfirmedForeground: String? = null,
    val lastTargetSeenAt: Long = 0,
    val lastActionAt: Long = 0,
    val retryCount: Int = 0,
    val pauseUntil: Long = 0,
    val nextRetryAt: Long = 0,
    val endReason: String = "",
    val updatedAt: Long,
)

sealed interface FocusDecision {
    data object None : FocusDecision
    data class Grace(val secondsLeft: Int, val allowed: Boolean) : FocusDecision
    data class Block(val reason: String, val canRetry: Boolean) : FocusDecision
    data class ReturnToTarget(val reason: String) : FocusDecision
    data class Verify(val reason: String) : FocusDecision
    data class Paused(val until: Long) : FocusDecision
    data class Completed(val reason: FocusEndReason) : FocusDecision
    data class Aborted(val reason: FocusEndReason) : FocusDecision
}

/** 纯 Kotlin 状态机：输入前台包名/时间/权限，输出该做什么；不直接操作 Android。 */
class FocusGuard(private var snapshot: FocusSnapshot) {
    val current: FocusSnapshot get() = snapshot

    fun onTargetForeground(now: Long, foreground: String?): FocusDecision {
        if (snapshot.state in TERMINAL) return FocusDecision.None
        if (foreground == null) return FocusDecision.None
        snapshot = snapshot.copy(lastConfirmedForeground = foreground, updatedAt = now)
        val isTarget = foreground == snapshot.contract.targetPackage ||
            foreground in snapshot.contract.allowedPackages
        return when {
            foreground == snapshot.contract.targetPackage -> {
                snapshot = snapshot.copy(state = FocusState.IN_TARGET, lastTargetSeenAt = now,
                    retryCount = 0, nextRetryAt = 0, updatedAt = now)
                FocusDecision.None
            }
            snapshot.state == FocusState.PAUSED && now < snapshot.pauseUntil -> FocusDecision.Paused(snapshot.pauseUntil)
            foreground in snapshot.contract.allowedPackages -> {
                val deadline = now + snapshot.contract.allowedGraceMillis
                snapshot = snapshot.copy(state = FocusState.AWAY_GRACE, updatedAt = now)
                FocusDecision.Grace(((deadline - now) / 1000).toInt(), true)
            }
            snapshot.state == FocusState.AWAY_GRACE || snapshot.state == FocusState.IN_TARGET ||
                snapshot.state == FocusState.RETURNING || snapshot.state == FocusState.VERIFYING -> {
                val deadline = now + snapshot.contract.graceMillis
                snapshot = snapshot.copy(state = FocusState.AWAY_GRACE, updatedAt = now)
                FocusDecision.Grace(((deadline - now) / 1000).toInt(), false)
            }
            else -> FocusDecision.None
        }
    }

    fun onLeave(now: Long, foreground: String?): FocusDecision {
        if (snapshot.state in TERMINAL) return FocusDecision.None
        if (foreground == null || foreground == snapshot.contract.targetPackage) return FocusDecision.None
        if (snapshot.state == FocusState.PAUSED && now < snapshot.pauseUntil) return FocusDecision.Paused(snapshot.pauseUntil)
        val allowed = foreground in snapshot.contract.allowedPackages
        val grace = if (allowed) snapshot.contract.allowedGraceMillis else snapshot.contract.graceMillis
        snapshot = snapshot.copy(state = FocusState.AWAY_GRACE, updatedAt = now,
            lastConfirmedForeground = foreground, lastTargetSeenAt = snapshot.lastTargetSeenAt)
        return FocusDecision.Grace(((grace) / 1000).toInt(), allowed)
    }

    fun onGraceExpired(now: Long): FocusDecision {
        if (snapshot.state in TERMINAL) return FocusDecision.None
        if (snapshot.contract.enforcement == FocusEnforcement.SOFT) {
            return FocusDecision.Block("离开目标应用，是否回去继续？", canRetry = true)
        }
        if (snapshot.retryCount >= snapshot.contract.maxReturnAttempts) {
            return FocusDecision.Block("多次尝试仍未回到目标；拦截层保留，等待手动返回或结束会话", canRetry = false)
        }
        if (now < snapshot.nextRetryAt) return FocusDecision.Block("等待退避后再尝试返回", canRetry = true)
        snapshot = snapshot.copy(state = FocusState.BLOCKING, updatedAt = now)
        return FocusDecision.Block("STRICT：已离开目标，必须回到目标或结束会话", canRetry = true)
    }

    fun requestReturn(now: Long, userInitiated: Boolean = false): FocusDecision {
        if (snapshot.state in TERMINAL) return FocusDecision.None
        if (!userInitiated && snapshot.retryCount >= snapshot.contract.maxReturnAttempts) {
            return FocusDecision.Block("自动返回次数已达上限；请手动返回或结束会话", canRetry = false)
        }
        val attempts = if (userInitiated) 0 else snapshot.retryCount + 1
        snapshot = snapshot.copy(state = FocusState.RETURNING, retryCount = attempts, lastActionAt = now, updatedAt = now)
        return FocusDecision.ReturnToTarget("请求打开目标应用")
    }

    fun onReturnAttemptResult(now: Long, launched: Boolean, foreground: String?): FocusDecision {
        if (snapshot.state != FocusState.RETURNING) return FocusDecision.None
        if (launched && foreground == snapshot.contract.targetPackage) {
            snapshot = snapshot.copy(state = FocusState.IN_TARGET, lastTargetSeenAt = now, retryCount = 0,
                nextRetryAt = 0, updatedAt = now)
            return FocusDecision.None
        }
        snapshot = snapshot.copy(
            state = if (snapshot.contract.enforcement == FocusEnforcement.STRICT) FocusState.BLOCKING else FocusState.AWAY_GRACE,
            nextRetryAt = now + snapshot.contract.retryBackoffMillis,
            updatedAt = now,
        )
        return FocusDecision.Block(
            if (launched) "系统接受了启动请求，但目标仍未在前台" else "启动请求被拒绝或失败",
            canRetry = snapshot.retryCount < snapshot.contract.maxReturnAttempts,
        )
    }

    fun pause(now: Long): FocusDecision {
        if (snapshot.state in TERMINAL) return FocusDecision.None
        val until = now + snapshot.contract.pauseMillis
        snapshot = snapshot.copy(state = FocusState.PAUSED, pauseUntil = until, updatedAt = now)
        return FocusDecision.Paused(until)
    }

    fun resume(now: Long): FocusDecision {
        if (snapshot.state != FocusState.PAUSED) return FocusDecision.None
        snapshot = snapshot.copy(state = FocusState.AWAY_GRACE, pauseUntil = 0, updatedAt = now)
        return FocusDecision.Grace((snapshot.contract.graceMillis / 1000).toInt(), false)
    }

    fun complete(now: Long, reason: FocusEndReason): FocusDecision {
        snapshot = snapshot.copy(state = FocusState.COMPLETED, endReason = reason.name, updatedAt = now)
        return FocusDecision.Completed(reason)
    }

    fun abort(now: Long, reason: FocusEndReason = FocusEndReason.USER_ABORTED): FocusDecision {
        snapshot = snapshot.copy(state = FocusState.USER_ABORTED, endReason = reason.name, updatedAt = now)
        return FocusDecision.Aborted(reason)
    }

    fun tick(now: Long): FocusDecision {
        if (snapshot.state in TERMINAL) return FocusDecision.None
        if (now >= snapshot.contract.expiresAt) return complete(now, FocusEndReason.EXPIRED)
        if (snapshot.state == FocusState.PAUSED && now >= snapshot.pauseUntil) return resume(now)
        return FocusDecision.None
    }

    companion object {
        val TERMINAL = setOf(FocusState.DISARMED, FocusState.COMPLETED, FocusState.USER_ABORTED)
    }
}

/** 跨进程持久化；结束时保留墓碑，服务重启不得复活。 */
class FocusStore(private val root: File) {
    private val file = File(root, "focus/session.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): FocusSnapshot? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { json.decodeFromString(FocusSnapshot.serializer(), file.readText()) }.getOrNull()
    }

    fun save(snapshot: FocusSnapshot): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(FocusSnapshot.serializer(), snapshot))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) { false }
    }

    fun clear() = synchronized(lock) { file.delete() }
}
