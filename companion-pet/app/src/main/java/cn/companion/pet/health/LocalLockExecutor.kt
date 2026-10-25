package cn.companion.pet.health

import java.util.UUID

/*
 * 本地锁屏出口（规范 §5、§8；报告 §8）。
 *
 * 边界写清楚：
 *  - 哨兵在岗时，全局锁屏由哨兵做；这里只在「哨兵确认失联 + 自己仍健康 + 规则仍然生效」时有限兜底。
 *  - 只按自己的规则执行，不能解除别人的规则。
 *  - 同一事件只锁一次（用共享内核 [LockArbiter] 去重 + 冷却 + 次数上限）。
 *  - 紧急暂停、修复宽限期内一律不锁。
 *  - 「调用了 API」和「观察到锁上了」分开记：观测不到就是 UNVERIFIED（T09/T10）。
 */

/** 设备管理员 / 无障碍的锁屏通道。真实实现在 Android 侧；测试用假的。 */
interface LockGateway {
    /** 设备管理员 force-lock 是否已激活。 */
    fun isAdminActive(): Boolean

    /** 调 DevicePolicyManager.lockNow()；返回「调用是否成功返回」，不代表锁上了。 */
    fun lockNow(): Boolean

    /** 无障碍 GLOBAL_ACTION_LOCK_SCREEN（另一条通道，不能当唯一兜底）。 */
    fun globalLockAction(): Boolean

    /** 读 KeyguardManager / PowerManager 的事实；读不到返回 null（→ UNVERIFIED）。 */
    fun keyguardFacts(): KeyguardFacts?
}

/** 一次本地兜底评估的完整上下文，全部由调用方读出后传入，便于单测。 */
data class LocalLockContext(
    /** 这条规则此刻生效吗（Policy.effective）。 */
    val policyEffective: Boolean,
    /** 紧急暂停是否挡住了这条规则。 */
    val emergencyBlocked: Boolean,
    /** 修复宽限截止时刻（单调）；0 = 不在宽限。 */
    val repairGraceUntilMono: Long = 0,
    /** 哨兵是否确认失联（哨兵健康时不该由客户端抢着锁）。 */
    val sentinelUnresponsive: Boolean,
    /** 本应用自己的感知执行线程是否健康（自己不健康就没有资格兜底）。 */
    val selfHealthy: Boolean,
)

/** 评估结果：要么给出要执行的动作和完整标识，要么给出不执行的具体原因。 */
data class LocalLockPlan(
    val fire: Boolean,
    val reason: String,
    val request: LockRequest? = null,
)

/**
 * 本地锁屏的决策器。纯函数式（除了持有 [LockArbiter] 状态），可以在 JVM 里推进时间做故障注入。
 */
class LocalLockPlanner(
    private val arbiter: LockArbiter = LockArbiter(),
    private val requester: () -> String = { UUID.randomUUID().toString() },
) {
    fun plan(ctx: LocalLockContext, policy: Policy, incidentId: String, nowMono: Long, reason: String): LocalLockPlan {
        if (!ctx.selfHealthy) return LocalLockPlan(false, "自己不健康：不做本地兜底锁屏")
        if (!ctx.sentinelUnresponsive) return LocalLockPlan(false, "哨兵在岗：全局锁屏由哨兵统一执行")
        if (!ctx.policyEffective) return LocalLockPlan(false, "规则不在生效窗口内")
        if (ctx.emergencyBlocked) return LocalLockPlan(false, "紧急暂停中：不执行本轮锁屏")
        if (!policy.ownedByPet()) return LocalLockPlan(false, "这条规则不属于本应用，不能执行")
        val req = LockRequest(
            policyId = policy.policyId,
            incidentId = incidentId,
            requestId = requester(),
            source = SOURCE_PET,
            reason = reason,
        )
        // 修复宽限只在两种情况下生效：调用方显式给了截止时刻，或规则自己的 repairGraceMs 用于
        // 「刚锁过再解锁」之后。这里不能凭空造一个「未来宽限」，否则第一次就永远不执行。
        val graceUntil = ctx.repairGraceUntilMono
        val d = arbiter.decide(req, nowMono, graceUntil)
        return if (d.fire) LocalLockPlan(true, d.reason, req) else LocalLockPlan(false, d.reason, req)
    }

    fun record(req: LockRequest, state: LockState, nowMono: Long) {
        synchronized(knownIncidents) { knownIncidents += req.incidentId }
        arbiter.record(req, state, nowMono)
    }

    fun forget(incidentId: String) {
        synchronized(knownIncidents) { knownIncidents -= incidentId }
        arbiter.forget(incidentId)
    }

    /**
     * 清空仲裁器里记过的在途事件（紧急暂停 / 用户恢复后用）。
     *
     * 不清的话，「已锁过一次」的旧账会挡住恢复之后的第一次正常判断。
     * 注意：清旧账**不等于**放行暂停——能不能锁由 [EmergencyGate] 独立判定，两件事分开。
     */
    fun reset() {
        val ids = synchronized(knownIncidents) {
            val copy = knownIncidents.toList()
            knownIncidents.clear()
            copy
        }
        ids.forEach { arbiter.forget(it) }
    }

    /** 可能同时被 Binder 线程（哨兵下发的暂停）和主线程（tick/界面）访问，必须加锁。 */
    private val knownIncidents = mutableSetOf<String>()
}

/** 一次执行尝试的完整结果（用于回执、界面、诊断）。 */
data class LockAttempt(
    val request: LockRequest,
    val state: LockState,
    val note: String,
    val atWall: Long,
    val atMono: Long,
)

/**
 * 执行一次锁屏并如实分级：REQUESTED → API_CALLED → OBSERVED_LOCKED / FAILED / UNVERIFIED。
 *
 * 关键纪律（规范 §5.2、T09/T10）：
 *  - 没设置安全锁屏时 lockNow 可能只是休眠 → UNVERIFIED，不报安全锁定。
 *  - 调用没抛异常 ≠ 成功：必须读 KeyguardManager 观测。
 *  - 观测本身失败 → UNVERIFIED。
 */
class LocalLockExecutor(private val gateway: LockGateway) {
    fun execute(req: LockRequest, nowWall: Long, nowMono: Long): LockAttempt {
        // 权限状态先查一次：没有管理员也没有无障碍通道时，直接 FAILED 并说明原因。
        val adminActive = runCatching { gateway.isAdminActive() }.getOrDefault(false)
        val called = when {
            adminActive -> runCatching { gateway.lockNow() }.getOrElse { false }
            else -> runCatching { gateway.globalLockAction() }.getOrElse { false }
        }
        if (!called) {
            val why = if (adminActive) "设备管理员锁屏调用失败" else "没有设备管理员权限，无障碍锁屏通道也没成功"
            return LockAttempt(req, LockState.FAILED, why, nowWall, nowMono)
        }
        val facts = runCatching { gateway.keyguardFacts() }.getOrNull()
        val (state, note) = LockClassifier.classify(called = true, error = null, facts = facts)
        return LockAttempt(req, state, note, nowWall, nowMono)
    }

    /** 只在测试/诊断里用：直接给出一个分类结果，不碰设备。 */
    fun classifyOnly(facts: KeyguardFacts?): Pair<LockState, String> = LockClassifier.classify(true, null, facts)
}

/** 权限/能力状态：界面上必须如实分开显示，不许合并成一个“已就绪”。 */
data class LockCapability(
    val adminActive: Boolean,
    val a11yConnected: Boolean,
    val secureLockSet: Boolean?,
    /** 本地锁屏此刻可用的通道。 */
    val usable: Boolean,
    val summary: String,
)

fun lockCapability(adminActive: Boolean, a11yConnected: Boolean, secureLockSet: Boolean?): LockCapability {
    val usable = adminActive || a11yConnected
    val summary = buildList {
        add(if (adminActive) "设备管理员锁屏：已激活" else "设备管理员锁屏：未激活")
        add(if (a11yConnected) "无障碍锁屏通道：可用" else "无障碍锁屏通道：服务未连接")
        add(
            when (secureLockSet) {
                true -> "系统安全锁屏：已设置"
                false -> "系统安全锁屏：未设置（调用后可能只是休眠，不会报成安全锁定）"
                null -> "系统安全锁屏：读不到"
            },
        )
    }.joinToString("；")
    return LockCapability(adminActive, a11yConnected, secureLockSet, usable, summary)
}
