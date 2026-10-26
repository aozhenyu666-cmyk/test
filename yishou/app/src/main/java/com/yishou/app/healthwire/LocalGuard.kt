package com.yishou.app.healthwire

import com.yishou.app.health.LockArbiter
import com.yishou.app.health.LockDecision
import com.yishou.app.health.LockRequest
import com.yishou.app.health.LockState
import com.yishou.app.health.Policy

/*
 * 一手的「有限兜底」决策（规范 §2.2 第 2 条 + §5.3）。
 *
 * 什么时候才由一手自己锁屏：
 *   1. 有生效中的本地规则（窗口内、enabled、且没被紧急暂停）；
 *   2. **哨兵失联**（哨兵在的时候由哨兵统管，一手不叠一层，避免四个应用一起锁）；
 *   3. 一手自己**健康**（无障碍在线、挑战能处理）——自己不健康就没有能力可靠执行；
 *   4. 规则允许执行（failureAction=LOCK），且设备具备锁屏能力（设备管理员或安全锁屏）；
 *   5. 通过共享内核的 [LockArbiter]：同一 incidentId 不重复锁、冷却、每事件次数上限、修复宽限。
 *
 * 「有限」是字面意思：一个事件最多 1–2 次，有冷却，有宽限，绝不做无限重锁（规范 §0 铁律 5）。
 * 纯逻辑，时钟由调用方传入，可在 JVM 单测里推进。
 */

/** 做出一次锁屏决策所需要的事实。全部由调用方从系统/本地读取，本类只判断。 */
data class GuardFacts(
    val nowWall: Long,
    val nowMono: Long,
    /** 哨兵此刻可达吗（watch 进程的 ping/注册回执）。不可达 = 失联。 */
    val sentinelLinked: Boolean,
    /** 一手自己健康吗（有真实 dispatcherAck + 事件通道在走）。 */
    val selfHealthy: Boolean,
    /** 用户按了紧急暂停。 */
    val emergencyStopped: Boolean,
    /** 设备管理员已激活（force-lock 路线）。 */
    val adminActive: Boolean,
    /** 系统已设置安全锁屏（PIN/图案/密码）。没设时 lockNow 可能只是休眠。 */
    val secureLockSet: Boolean,
    /** 修复宽限到什么时候（解锁后的一段时间不重锁）。 */
    val repairGraceUntilMono: Long = 0,
)

/** 决策结论。`fire=false` 时 [reason] 说明为什么不动手（要能给人看）。 */
data class LockPlan(
    val fire: Boolean,
    val reason: String,
    val policyId: String = "",
    val incidentId: String = "",
    val requestId: String = "",
    /** 判定为「只提醒、不锁」时置 true。 */
    val notifyOnly: Boolean = false,
    val attempt: Int = 0,
)

object LocalGuard {

    const val SOURCE = "yishou"

    /**
     * @param policies 本地规则（已解析）。
     * @param arbiter 共享内核的仲裁器（每个进程一个实例即可）。
     */
    fun plan(
        policies: List<Policy>,
        facts: GuardFacts,
        arbiter: LockArbiter,
        newRequestId: () -> String,
    ): LockPlan {
        if (facts.emergencyStopped) {
            return LockPlan(false, "紧急暂停中：一手不会自动锁屏")
        }
        val active = policies.filter { it.effective(facts.nowWall) }
        if (active.isEmpty()) {
            return LockPlan(false, "没有生效中的本地规则（窗口外或未启用）")
        }
        val stopped = active.firstOrNull { it.emergencyState == "STOPPED" }
        if (stopped != null) {
            return LockPlan(false, "规则 ${stopped.policyId} 处于紧急暂停", policyId = stopped.policyId)
        }
        if (!active.any { it.requiredApps.isNotEmpty() }) {
            return LockPlan(false, "生效规则里没有要求监管的应用")
        }
        // 按最严格的一条来：先锁的优先，稳定选 policyId 最小的那条，避免每次决策跳来跳去。
        val policy = active.sortedWith(
            compareBy({ if (it.failureAction == "LOCK") 0 else 1 }, { it.policyId }),
        ).first()

        if (policy.failureAction == "NOTIFY_ONLY") {
            return LockPlan(
                fire = false,
                reason = "规则 ${policy.policyId} 要求只提醒不锁屏",
                policyId = policy.policyId,
                notifyOnly = true,
                incidentId = incidentIdFor(policy, facts),
            )
        }
        if (facts.sentinelLinked) {
            // 哨兵在线：锁屏由哨兵统管，一手不叠一层（规范 §5.3 去重）。
            return LockPlan(false, "哨兵在线，由哨兵统管执行（一手不重复锁）", policyId = policy.policyId)
        }
        if (!facts.selfHealthy) {
            return LockPlan(
                false,
                "一手自身不健康：不做兜底锁屏（否则只是假装执行了）",
                policyId = policy.policyId,
            )
        }
        if (!facts.adminActive && !facts.secureLockSet) {
            return LockPlan(
                false,
                "没有设备管理员、也没设安全锁屏：无法可靠锁屏（只能提醒）",
                policyId = policy.policyId,
                notifyOnly = true,
            )
        }

        val incidentId = incidentIdFor(policy, facts)
        val req = LockRequest(
            policyId = policy.policyId,
            incidentId = incidentId,
            requestId = newRequestId(),
            source = SOURCE,
            reason = "哨兵失联，一手按本地规则兜底（规则 ${policy.policyId}）",
        )
        val decision: LockDecision = arbiter.decide(req, facts.nowMono, facts.repairGraceUntilMono)
        return LockPlan(
            fire = decision.fire,
            reason = decision.reason,
            policyId = policy.policyId,
            incidentId = incidentId,
            requestId = req.requestId,
            attempt = decision.attempt,
        )
    }

    /**
     * 同一故障事件用同一个 incidentId：同一规则 + 同一个窗口，就视为同一个事件。
     * 这样进程重启、重复决策都能被仲裁器认出来（规范 §5.3）。
     */
    fun incidentIdFor(policy: Policy, facts: GuardFacts): String {
        val windowKey = "${policy.startsAtWall}-${policy.endsAtWall}"
        val day = facts.nowWall / 86_400_000L
        return "${policy.policyId}@$windowKey#d$day"
    }

    /** 记录执行结果（回执状态进仲裁器 + 本地事实）。 */
    fun record(arbiter: LockArbiter, plan: LockPlan, state: LockState, nowMono: Long, atWall: Long) {
        if (plan.incidentId.isBlank()) return
        arbiter.record(
            LockRequest(
                policyId = plan.policyId,
                incidentId = plan.incidentId,
                requestId = plan.requestId,
                source = SOURCE,
                reason = plan.reason,
            ),
            state,
            nowMono,
        )
        Facts.onReceipt(state.name, atWall)
    }
}
