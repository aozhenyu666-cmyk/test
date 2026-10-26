package com.zongkong.app.healthwire

import com.zongkong.app.health.HealthAction
import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.HealthEvaluator
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.health.HealthVerdict
import java.util.UUID

/*
 * 本地健康台账：**只记真实发生过的事**（真实 dispatcherAck、真实无障碍事件、真实连接实例），
 * 再把这些事实喂给共享内核的 HealthEvaluator 做判断。
 *
 * 三条不能破的规则（报告 §3.2 / §8 / 验收 T02）：
 *   1. 没有真实 ack 就不可能是 HEALTHY（内核的 UNKNOWN 分支保证）；
 *   2. 进恢复后必须先清掉旧的 ack，只有「新 instanceId + 恢复开始之后的 ack」才算恢复成功（T11）；
 *   3. 恢复次数有上限（默认 2），超了就给 ESCALATE，不无限重绑。
 *
 * 纯 Kotlin：时钟是注入的 lambda，可以在 JVM 单测里手动推进时间。
 */
class HealthLedger(
    private val mono: () -> Long,
    private val newInstanceId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    /** 当前无障碍实例。null = 没连上。每次重建连接必须换新。 */
    @Volatile var instanceId: String? = null
        private set

    @Volatile var connectedAtMono: Long = 0
        private set

    /** 最近一次真实 dispatcherAck 的序号与时刻。 */
    @Volatile var lastAckSeq: Long = 0
        private set
    @Volatile var lastAckAtMono: Long = 0
        private set

    /** 最近一次可比对的无障碍事件序号与时刻（T03：事件通道是否失灵）。 */
    @Volatile var lastEventSeq: Long = 0
        private set
    @Volatile var lastEventAtMono: Long = 0
        private set

    @Volatile var consecutiveFailures: Int = 0
        private set
    @Volatile var lastFailureReason: String = ""
        private set

    /** 恢复过程：从什么时候开始、试了几次、开始时是哪个实例。 */
    @Volatile var recoveringSinceMono: Long = 0
        private set
    @Volatile var recoveriesTried: Int = 0
        private set
    private var instanceAtRecoveryStart: String? = null

    /** 已向哨兵注册成功过（v2 register 回了 ok）。 */
    @Volatile var registered: Boolean = false
        private set

    /** 本地规则版本（回执里带给哨兵）。 */
    @Volatile var ruleRevision: Int = 0
        private set

    /** 最近一次锁屏回执。 */
    @Volatile var lastReceiptState: String = ""
        private set
    @Volatile var lastReceiptAtMono: Long = 0
        private set

    /** 紧急暂停（本地可达，不依赖网络/模型）。 */
    @Volatile var paused: Boolean = false
        private set

    // ---------- 连接生命周期 ----------

    /** 无障碍服务连上了：换一个新的 instanceId（T11：重建必须换新）。 */
    @Synchronized fun onConnected(nowMono: Long = mono()): String {
        instanceId = newInstanceId()
        connectedAtMono = nowMono
        // 新连接不代表健康：旧的 ack 不算数，等真实挑战回来。
        lastAckAtMono = 0
        lastAckSeq = 0
        // 恢复中重连上了：仍然要等真实 ack 才变绿。实例已经和恢复起点不同，
        // snapshot() 会把新实例交给内核，剩下的由「恢复开始之后的 ack」决定。
        return instanceId!!
    }

    /** 无障碍服务断开/销毁：立刻失去实例，ack 一并失效（不保留“假在线”）。 */
    @Synchronized fun onDisconnected(nowMono: Long = mono()) {
        instanceId = null
        connectedAtMono = 0
        lastAckAtMono = 0
        lastAckSeq = 0
        if (recoveringSinceMono <= 0 && nowMono > 0) {
            // 断开本身就是一次失败事实
            noteFailure("无障碍服务断开")
        }
    }

    // ---------- 事实登记 ----------

    /** 每个可比对的无障碍事件（窗口切换）登记一次。 */
    @Synchronized fun noteEvent(nowMono: Long = mono()): Long {
        lastEventSeq += 1
        lastEventAtMono = nowMono
        return lastEventSeq
    }

    /** 真实 dispatcherAck：只在 a11y 线程处理完挑战后调用。 */
    @Synchronized fun noteAck(seq: Long, nowMono: Long = mono()) {
        lastAckSeq = seq
        lastAckAtMono = nowMono
        consecutiveFailures = 0
        lastFailureReason = ""
    }

    @Synchronized fun noteFailure(reason: String) {
        consecutiveFailures += 1
        lastFailureReason = reason
    }

    @Synchronized fun noteReceipt(state: String, nowMono: Long = mono()) {
        lastReceiptState = state
        lastReceiptAtMono = nowMono
    }

    @Synchronized fun setRegistered(ok: Boolean) {
        registered = ok
    }

    @Synchronized fun setRuleRevision(rev: Int) {
        ruleRevision = rev
    }

    @Synchronized fun setPaused(value: Boolean) {
        paused = value
    }

    // ---------- 恢复 ----------

    /**
     * 申请一次自动恢复。返回 false = 已经到上限 / 已经在恢复 / 没有实例可供对比。
     * 注意：这里**只**记「恢复请求已发出」。
     */
    @Synchronized fun beginRecovery(maxRecoveries: Int = 2, nowMono: Long = mono()): Boolean {
        if (recoveringSinceMono > 0) return false
        if (recoveriesTried >= maxRecoveries) return false
        recoveringSinceMono = nowMono
        instanceAtRecoveryStart = instanceId
        recoveriesTried += 1
        // 旧 ack 一律作废：恢复成功只能靠「新实例 + 恢复之后的真 ack」（T11）。
        lastAckAtMono = 0
        lastAckSeq = 0
        return true
    }

    /** 恢复结束（成功变绿或彻底失败），回到普通判定。 */
    @Synchronized fun endRecovery() {
        recoveringSinceMono = 0
        instanceAtRecoveryStart = null
    }

    /** 确认失联并已经按失效处理完：清掉恢复计数，让下一个故障事件重新计数。 */
    @Synchronized fun resetRecoveries() {
        recoveriesTried = 0
    }

    // ---------- 喂给内核 ----------

    @Synchronized fun snapshot(
        permissionEnabled: Boolean,
        installed: Boolean = true,
        cfg: HealthConfig = HealthConfig(),
    ): HealthSnapshot {
        val now = mono()
        val recovering = recoveringSinceMono > 0
        // 恢复中：只有换了新实例，才把实例当作「新的连接」交给内核。
        val effectiveInstance = when {
            !recovering -> instanceId
            instanceAtRecoveryStart == null -> instanceId
            instanceId != instanceAtRecoveryStart -> instanceId
            else -> null
        }
        return HealthSnapshot(
            installed = installed,
            permissionEnabled = permissionEnabled,
            registered = registered,
            instanceId = effectiveInstance,
            lastAckSeq = lastAckSeq,
            lastAckAtMono = lastAckAtMono,
            lastEventSeq = lastEventSeq,
            lastEventAtMono = lastEventAtMono,
            consecutiveFailures = consecutiveFailures,
            recoveringSinceMono = recoveringSinceMono,
            recoveriesTried = recoveriesTried,
            paused = paused,
            lastFailureReason = lastFailureReason,
        )
    }

    fun verdict(
        permissionEnabled: Boolean,
        installed: Boolean = true,
        cfg: HealthConfig = HealthConfig(),
    ): HealthVerdict = HealthEvaluator.evaluate(
        nowMono = mono(),
        obs = snapshot(permissionEnabled, installed, cfg).toObservation(),
        cfg = cfg,
    )

    /**
     * 事件通道好了、ack 通道却不通（或反之）：给界面和诊断一句人话。
     * 返回 null = 两条通道没有明显矛盾。
     */
    fun channelMismatch(nowMono: Long = mono(), staleAfterMs: Long = 30_000): String? {
        val ackAge = if (lastAckAtMono <= 0) null else nowMono - lastAckAtMono
        val eventAge = if (lastEventAtMono <= 0) null else nowMono - lastEventAtMono
        val eventsFresh = eventAge != null && eventAge <= staleAfterMs
        val ackFresh = ackAge != null && ackAge <= staleAfterMs
        return when {
            eventsFresh && !ackFresh -> "事件还在来，但挑战 ack 不通：执行线程可能被占死"
            ackFresh && eventAge != null && eventAge > staleAfterMs -> "挑战 ack 正常，但窗口事件停了：事件通道失灵"
            else -> null
        }
    }

    /** 给诊断/界面用的一行摘要。 */
    @Synchronized fun summary(): String {
        val ack = if (lastAckAtMono <= 0) "从未 ack" else "ack#${lastAckSeq}"
        val inst = instanceId?.take(8) ?: "未连接"
        return "实例 $inst · $ack · 事件#${lastEventSeq} · 连续失败 $consecutiveFailures" +
            if (recoveriesTried > 0) " · 已试恢复 $recoveriesTried 次" else ""
    }

    /** 便捷：内核动作是不是要求开始恢复。 */
    fun shouldStartRecovery(v: HealthVerdict): Boolean = v.action == HealthAction.START_RECOVERY

    /** 便捷：内核说健康。 */
    fun isHealthy(v: HealthVerdict): Boolean = v.status == HealthStatus.HEALTHY
}


