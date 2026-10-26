package cn.companion.pet.core.training

import kotlinx.serialization.Serializable

/**
 * 能力健康不是一个布尔值。R2.1 §8.1 要求至少分开：
 * 权限状态、Binder 状态、服务连接、真实执行路径响应、最近动作结果。
 *
 * 这里只做**执行路径挑战**这一层：证明“承担实际工作的那条队列”近期能响应。
 * 它**不证明**每一次设备动作一定成功。
 */
@Serializable
enum class HealthLayer {
    /** 权限开关看起来是开的。 */
    PERMISSION,
    /** Binder 连接存在（Shizuku 等）。 */
    BINDER,
    /** 无障碍服务已连接。 */
    SERVICE,
    /** 真正承担执行的队列有没有响应。 */
    EXECUTION_PATH,
    /** 最近一次真实动作的结果。 */
    LAST_ACTION,
}

@Serializable
enum class HealthState { OK, DEGRADED, UNAVAILABLE, UNKNOWN }

/** 一次挑战：发起端用自己的单调时钟判超时，迟到响应作废。 */
@Serializable
data class HealthChallenge(
    val challengeId: String,
    val nonce: String,
    val capability: String,
    val protocolVersion: Int = 1,
    val issuedAt: Long,
    val deadline: Long,
)

@Serializable
data class HealthReceipt(
    val challengeId: String,
    val nonce: String,
    /** 处理阶段：用于确认它真的走完执行路径，而不是入口直接回显。 */
    val stage: String,
    val instanceId: String = "",
    val ok: Boolean,
    val completedAt: Long,
)

sealed interface ChallengeVerdict {
    /** 执行路径确认真实响应过。 */
    data class Healthy(val latencyMillis: Long) : ChallengeVerdict
    /** 超时：执行队列卡住或旁路线程代答。 */
    data class TimedOut(val waitedMillis: Long) : ChallengeVerdict
    /** nonce 不符：可能是伪造或迟到响应。 */
    data class Rejected(val reason: String) : ChallengeVerdict
}

/**
 * 挑战判定器。
 *
 * 关键规则（对应 A13/A35）：
 * 1. 回执必须带同一 challengeId 与 nonce，否则拒绝。
 * 2. 回执必须声称走完执行阶段（stage 非空且不是“入口直答”）。
 * 3. 超时由**发起端**的单调时钟判断，不看回执自报时间。
 * 4. 健康通过只表示“执行路径近期可响应”，不表示动作一定成功。
 */
class HealthChallengeJudge(
    private val timeoutMillis: Long = 3_000L,
    /** 入口直答的 stage 标记，视为无效健康证明。 */
    private val echoStages: Set<String> = setOf("binder_echo", "provider_echo", "entry"),
) {

    fun issue(
        challengeId: String,
        nonce: String,
        capability: String,
        now: Long,
    ): HealthChallenge = HealthChallenge(
        challengeId = challengeId,
        nonce = nonce,
        capability = capability,
        issuedAt = now,
        deadline = now + timeoutMillis,
    )

    fun judge(challenge: HealthChallenge, receipt: HealthReceipt?, now: Long): ChallengeVerdict {
        if (receipt == null) {
            val waited = now - challenge.issuedAt
            return if (waited >= timeoutMillis) ChallengeVerdict.TimedOut(waited) else ChallengeVerdict.TimedOut(waited)
        }
        if (receipt.challengeId != challenge.challengeId) {
            return ChallengeVerdict.Rejected("挑战 ID 不匹配")
        }
        if (receipt.nonce != challenge.nonce) {
            return ChallengeVerdict.Rejected("nonce 不匹配，可能是伪造响应")
        }
        // 入口直接回显不算走通执行路径。
        if (receipt.stage.isBlank() || receipt.stage.lowercase() in echoStages) {
            return ChallengeVerdict.Rejected("只经过入口回显，未走执行路径")
        }
        if (!receipt.ok) return ChallengeVerdict.Rejected("执行路径返回失败")
        val latency = receipt.completedAt - challenge.issuedAt
        // 用发起端时钟判超时：迟到响应作废。
        if (now > challenge.deadline) return ChallengeVerdict.TimedOut(now - challenge.issuedAt)
        return ChallengeVerdict.Healthy(latency.coerceAtLeast(0))
    }

    val timeout: Long get() = timeoutMillis
}

/**
 * 动作回执层次：API 说成功 ≠ 观察到效果。
 *
 * REQUESTED → DISPATCHED → API_REPORTED_SUCCESS → OBSERVED_CONFIRMED
 * 任何一步失败记 FAILED；无法观察记 UNKNOWN，不能冒充确认成功。
 */
@Serializable
enum class ActionReceiptState {
    REQUESTED,
    DISPATCHED,
    API_REPORTED_SUCCESS,
    OBSERVED_CONFIRMED,
    FAILED,
    UNKNOWN,
}

@Serializable
data class ActionReceipt(
    val actionId: String,
    val capability: String,
    val state: ActionReceiptState,
    val at: Long,
    val detail: String = "",
    /** 是否由独立观察确认了效果。 */
    val observed: Boolean = false,
)

/** 综合能力表：把多层状态汇总成一个人能看懂的结论，但不掩盖未知。 */
data class CapabilityReport(
    val layer: HealthLayer,
    val state: HealthState,
    val detail: String,
)

object CapabilitySummary {

    /**
     * 只有执行路径健康 + 最近动作不是失败，才算“可用”。
     * 权限开着但执行路径不响应 → DEGRADED，不是 OK。
     */
    fun summarize(
        permission: Boolean,
        serviceConnected: Boolean,
        executionPathHealthy: Boolean,
        lastAction: ActionReceiptState?,
    ): List<CapabilityReport> = listOf(
        CapabilityReport(
            HealthLayer.PERMISSION,
            if (permission) HealthState.OK else HealthState.UNAVAILABLE,
            if (permission) "权限已授予" else "权限未授予",
        ),
        CapabilityReport(
            HealthLayer.SERVICE,
            if (serviceConnected) HealthState.OK else HealthState.UNAVAILABLE,
            if (serviceConnected) "服务已连接" else "服务未连接",
        ),
        CapabilityReport(
            HealthLayer.EXECUTION_PATH,
            when {
                executionPathHealthy -> HealthState.OK
                serviceConnected -> HealthState.DEGRADED
                else -> HealthState.UNAVAILABLE
            },
            when {
                executionPathHealthy -> "执行路径有响应"
                serviceConnected -> "服务连着但执行路径没响应"
                else -> "无服务，无法执行"
            },
        ),
        CapabilityReport(
            HealthLayer.LAST_ACTION,
            when (lastAction) {
                ActionReceiptState.OBSERVED_CONFIRMED -> HealthState.OK
                ActionReceiptState.API_REPORTED_SUCCESS -> HealthState.UNKNOWN
                ActionReceiptState.FAILED -> HealthState.DEGRADED
                ActionReceiptState.UNKNOWN, null -> HealthState.UNKNOWN
                else -> HealthState.UNKNOWN
            },
            when (lastAction) {
                ActionReceiptState.OBSERVED_CONFIRMED -> "上次动作已观察到效果"
                ActionReceiptState.API_REPORTED_SUCCESS -> "上次动作 API 说成功，但没观察到效果"
                ActionReceiptState.FAILED -> "上次动作失败"
                else -> "上次动作结果未知"
            },
        ),
    )

    /** 是否可以向用户声称“监管可靠”。只有执行路径 OK 且动作已观察确认才行。 */
    fun canClaimReliable(
        permission: Boolean,
        serviceConnected: Boolean,
        executionPathHealthy: Boolean,
        lastAction: ActionReceiptState?,
    ): Boolean = permission && serviceConnected && executionPathHealthy &&
        lastAction == ActionReceiptState.OBSERVED_CONFIRMED
}
