package com.zongkong.app.healthwire

import com.zongkong.app.health.LockArbiter
import com.zongkong.app.health.LockDecision
import com.zongkong.app.health.LockRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * 客户端本地规则（报告 §7 / WIRE-SPEC §4）。
 *
 * 立场：
 *   - 规则由自己持久化，先落盘再开始监管；哨兵回执只是「哨兵收到了」，不代替本地规则。
 *   - 窗口过期一定停止执行（[LocalPolicy.effective]）。
 *   - 不替用户编造每天几点锁屏：默认不下发 lockAtWall，除非用户明确设置。
 *   - 单个 APP 只能改自己的规则（本文件里没有「改别人规则」的入口）。
 */

@Serializable
data class LocalPolicy(
    val policyId: String,
    val revision: Int = 1,
    /** user / zongkong / yishou / pet / sentinel。本客户端只会写 user/zongkong。 */
    val issuer: String = "zongkong",
    val requiredApps: List<String> = listOf("com.zongkong.app", "com.yishou.app", "cn.companion.pet"),
    val startsAtWall: Long = 0,
    val endsAtWall: Long = 0,
    /** 可选的到期锁屏事件；0 = 没有。默认没有，不猜用户作息。 */
    val lockAtWall: Long = 0,
    val failureAction: String = ACTION_LOCK,
    val repairGraceMs: Long = 2 * 60_000,
    val emergencyState: String = EMERGENCY_NONE,
    val enabled: Boolean = false,
) {
    /** 窗口内、且已启用，才算生效。 */
    fun effective(nowWall: Long): Boolean = enabled && nowWall >= startsAtWall && nowWall < endsAtWall

    /** 到点锁屏是否到点（去重交给 LockArbiter）。 */
    fun lockDue(nowWall: Long): Boolean {
        if (lockAtWall <= 0L) return false
        return enabled && nowWall >= lockAtWall && nowWall < endsAtWall
    }

    /** 紧急暂停是否已生效（本地可达，不等网络/模型）。 */
    fun stopped(): Boolean = emergencyState == EMERGENCY_STOPPED

    /** 剩余时间（毫秒）；未启用或已过期 = 0。 */
    fun remainingMs(nowWall: Long): Long = (endsAtWall - nowWall).coerceAtLeast(0)

    /** 失效时该锁屏还是只提醒。 */
    fun failureLock(): Boolean = failureAction == ACTION_LOCK

    companion object {
        const val ACTION_LOCK = "LOCK"
        const val ACTION_NOTIFY_ONLY = "NOTIFY_ONLY"
        const val EMERGENCY_NONE = "NONE"
        const val EMERGENCY_STOPPED = "STOPPED"
        const val SCHEMA_VERSION = 2
    }
}

@Serializable
data class LocalPolicyFile(
    @SerialName("schemaVersion") val schemaVersion: Int = LocalPolicy.SCHEMA_VERSION,
    val policies: List<LocalPolicy> = emptyList(),
)

/**
 * 本地兜底判定（报告 §8 末段 / WIRE-SPEC §5.3）：
 * 哨兵失联、自己健康时，按本地规则做**有限**兜底锁屏。
 *
 * 有限指的是：同一 incidentId 去重 + 冷却 + 每次数上限，全部由共享内核 [LockArbiter] 决定；
 * 模型和网络都不参与。
 */
class LocalFallback(
    private val arbiter: LockArbiter = LockArbiter(maxAttemptsPerIncident = 1),
    private val source: String = "zongkong",
) {
    /** 一次兜底尝试的结论，带上给哨兵回执用的四个 id。 */
    data class Attempt(
        val fire: Boolean,
        val reason: String,
        val request: LockRequest?,
        val incidentId: String,
        val policyId: String,
    )

    /**
     * @param sentinelHealthy 哨兵是否在岗（真实心跳 + 真实 ack，见 SentinelClient）。
     * @param selfHealthy 自己是否健康（有真实 dispatcherAck 且新鲜）。
     * @param emergencyStopped 用户是否按了紧急暂停。
     */
    fun decide(
        policies: List<LocalPolicy>,
        nowWall: Long,
        nowMono: Long,
        sentinelHealthy: Boolean,
        selfHealthy: Boolean,
        emergencyStopped: Boolean,
        incidentKey: String,
        repairGraceUntilMono: Long = 0,
    ): Attempt {
        val policy = policies.filter { it.effective(nowWall) }.maxByOrNull { it.revision }
        if (policy == null) {
            return Attempt(false, "没有生效中的规则", null, incidentKey, "")
        }
        if (policy.stopped() || emergencyStopped) {
            return Attempt(false, "已紧急暂停", null, incidentKey, policy.policyId)
        }
        if (!policy.failureLock()) {
            return Attempt(false, "规则是只提醒（NOTIFY_ONLY）", null, incidentKey, policy.policyId)
        }
        // 自己都不健康：没有资格替别人兜底（也避免「失联时又锁一次」叠加故障）。
        if (!selfHealthy) {
            return Attempt(false, "自己也没有真实响应，不做兜底", null, incidentKey, policy.policyId)
        }
        // 哨兵在岗时听哨兵的，本地不抢着锁。
        if (sentinelHealthy) {
            return Attempt(false, "哨兵在岗，由哨兵执行", null, incidentKey, policy.policyId)
        }
        val incidentId = "$incidentKey/${policy.policyId}/${policy.revision}"
        val request = LockRequest(
            policyId = policy.policyId,
            incidentId = incidentId,
            requestId = "zk-$incidentId-$nowMono",
            source = source,
            reason = "哨兵失联且本地规则生效：$incidentKey",
        )
        val decision: LockDecision = arbiter.decide(request, nowMono, repairGraceUntilMono)
        return Attempt(decision.fire, decision.reason, if (decision.fire) request else null, incidentId, policy.policyId)
    }

    /** 执行后登记（去重的另一半，必须和 decide 成对调用）。 */
    fun record(request: LockRequest, state: com.zongkong.app.health.LockState, nowMono: Long) {
        arbiter.record(request, state, nowMono)
    }

    /** 事件结束后清掉该事件的计数（例如已确认锁屏并解锁进入修复）。 */
    fun forget(incidentId: String) = arbiter.forget(incidentId)
}
