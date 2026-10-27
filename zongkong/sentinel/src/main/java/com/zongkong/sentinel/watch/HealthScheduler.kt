package com.zongkong.sentinel.watch

import com.zongkong.sentinel.health.AckResult
import com.zongkong.sentinel.health.AckValidator
import com.zongkong.sentinel.health.Challenge
import com.zongkong.sentinel.health.ChallengeAck
import com.zongkong.sentinel.health.HealthAction
import com.zongkong.sentinel.health.HealthConfig
import com.zongkong.sentinel.health.HealthEvaluator
import com.zongkong.sentinel.health.HealthStatus
import com.zongkong.sentinel.health.Mono
import com.zongkong.sentinel.health.Wall
import java.util.UUID

/**
 * 健康调度（规范 §2.1/§7：10s 探测 / 5s 预算 / 30s 确认）。
 *
 * 这个类**只在 `:watch` 进程的线程里跑**，不碰 `AccessibilityService` 的 Handler ——
 * 无障碍主线程冻结时它照样推进（T05）。所有判断都走共享内核，自己不发明规则（T02）。
 *
 * 关键结构：
 *  - 每个客户端一个 [AckValidator]：旧实例、重复序号、过期回应一律拒（T14）。
 *  - 每个客户端**独立**记录、独立超时、最多 1 个在途：[ProbeSink] 必须立即返回，
 *    真正的 Binder 调用在客户端专属线程里做。某个客户端卡死只会锁住它自己（T04）。
 *  - 超预算的探测标成 wedged，只记一次失败；**不再新建线程去重试**。
 */
class HealthScheduler(
    private val store: HealthStore,
    private val sink: ProbeSink,
    private val mono: Mono,
    private val wall: Wall,
    private val cfg: HealthConfig = HealthConfig(),
    /** 明确处于「亮屏 + 活跃监管」才算可确认失联；否则只降级不锁屏（T13）。 */
    private val activeEnforcement: () -> Boolean = { true },
    /** 交给上层去做的动作（恢复 / 升级 / 提醒），调度器自己不执行副作用。 */
    private val onAction: (pkg: String, action: HealthAction, reason: String) -> Unit = { _, _, _ -> },
) {

    /** 探测派发口。实现**必须非阻塞**：把调用交给客户端专属线程，用预算等待。 */
    interface ProbeSink {
        fun dispatch(pkg: String, challenge: Challenge, budgetMs: Long)
    }

    private val validators = HashMap<String, AckValidator>()

    /** 上一轮每个客户端的状态与动作：只在「变了」时才通知上层，免得每 10 秒刷一遍记录。 */
    private val lastStatus = HashMap<String, HealthStatus>()
    private val lastAction = HashMap<String, HealthAction>()

    /** 已经记过「卡死」的探测序号：一个探测只记一次失败，不按 tick 重复膨胀计数。 */
    private val wedgedCounted = HashMap<String, Long>()

    /** 一次 [tick] 的产出，给 WatchService 做副作用（恢复、提醒、计统计）。 */
    data class Tick(
        val statuses: Map<String, HealthStatus>,
        val dispatched: List<String> = emptyList(),
        val timedOut: List<String> = emptyList(),
        val needLock: List<Pair<String, String>> = emptyList(),
    )

    // tick 在 :watch 主循环线程、onAck 在各客户端探测线程、onNewInstance 在 provider 的 Binder 线程：
    // validator 不是线程安全的，统一加锁。
    @Synchronized
    fun validatorFor(pkg: String): AckValidator = validators.getOrPut(pkg) { AckValidator() }

    /**
     * 推进一次。返回本轮的结论；调度器只做「记录 + 决定」，不自己锁屏、不自己重绑。
     */
    @Synchronized
    fun tick(): Tick {
        val nowMono = mono.now()
        val nowWall = wall.now()
        val statuses = LinkedHashMap<String, HealthStatus>()
        val dispatched = ArrayList<String>()
        val timedOut = ArrayList<String>()
        val needLock = ArrayList<Pair<String, String>>()

        for (pkg in HealthStore.CLIENTS) {
            val rec = store.get(pkg, nowWall)
            if (!rec.registered && !rec.installed) {
                // 没装 / 从没接入：什么都不做，界面显示「未接入」。
                statuses[pkg] = HealthEvaluator.evaluate(nowMono, rec.observation(), cfg).status
                continue
            }

            // 1) 超预算的在途探测：记一次失败（每轮探测只记一次），线程继续卡着也不新建线程。
            if (rec.inFlight > 0 && rec.probeStartedAtMono > 0 &&
                nowMono - rec.probeStartedAtMono > cfg.challengeBudgetMs + BUDGET_SLACK_MS
            ) {
                if (wedgedCounted[pkg] != rec.probeSeq) {
                    wedgedCounted[pkg] = rec.probeSeq
                    store.markProbeWedged(pkg, "挑战超时（${cfg.challengeBudgetMs}ms 预算内没有回应）", nowWall)
                    timedOut += pkg
                }
            }

            val cur = store.get(pkg, nowWall)
            val verdict = HealthEvaluator.evaluate(nowMono, cur.observation(), cfg)
            statuses[pkg] = verdict.status

            // 什么时候发挑战：内核说 PROBE 时；另外「恢复中 / 恢复用完」也必须继续发——
            // 内核在恢复分支只认「恢复后的真实 ack」才转绿，不发挑战就永远拿不到 ack，
            // 客户端修好了也会被永久钉死在「失联」上。
            val wantProbe = verdict.action == HealthAction.PROBE ||
                (cur.recoveringSinceMono > 0 && cur.registered && cur.permissionEnabled && !cur.paused)
            if (wantProbe) {
                val seq = store.beginProbe(pkg, nowWall, nowMono)
                if (seq > 0) {
                    val challenge = Challenge(
                        nonce = UUID.randomUUID().toString(),
                        seq = seq,
                        issuedAtMono = nowMono,
                        expectedInstanceId = cur.instanceId,
                    )
                    // 非阻塞派发：真正 Binder 调用在客户端专属线程里，带 5s 预算等。
                    sink.dispatch(pkg, challenge, cfg.challengeBudgetMs)
                    dispatched += pkg
                }
            }

            val changed = lastStatus[pkg] != verdict.status || lastAction[pkg] != verdict.action
            if (verdict.status == HealthStatus.UNRESPONSIVE && lastStatus[pkg] != HealthStatus.UNRESPONSIVE) {
                store.countOutage(pkg)
            }
            lastStatus[pkg] = verdict.status
            lastAction[pkg] = verdict.action

            when (verdict.action) {
                HealthAction.PROBE, HealthAction.NONE -> Unit
                HealthAction.START_RECOVERY -> {
                    // 记账只在这里做一次（WatchService 只负责真的去发恢复请求）。
                    store.onRecoveryAttempt(pkg, "请求重新挂上无障碍（第 ${cur.recoveriesTried + 1} 次）", nowMono, nowWall)
                    onAction(pkg, verdict.action, verdict.reason)
                }
                // 提醒 / 升级：状态不变就不重复通知（否则每 10 秒写一次记录文件）。
                HealthAction.ESCALATE, HealthAction.NOTIFY -> if (changed) onAction(pkg, verdict.action, verdict.reason)
            }

            // 2) 只有「确认失联」+ 活跃监管 + 亮屏，才把锁屏事件交给仲裁。
            if (verdict.status == HealthStatus.UNRESPONSIVE && activeEnforcement()) {
                needLock += pkg to verdict.reason
            }

            // 注意（F-01）：这里**不算**事件通道。T03 的判定只有一处：
            // `WatchService.refreshSystemFacts()` → `EventChannel.evaluate()`。
            // 之前这里也算一遍，且 compareRefMono 硬编码 0、又拿上次结论当输入，
            // 把正确结论覆盖成「待验证」，T03 在真实链路上永远出不了「异常」。
            // 调度器的职责只有两个：健康状态、锁屏事件。

            // 3) 统计：只有拿到过真实 ack 的时间才计入有效监测（stats.availability 自己保证）。
            val outage = verdict.status != HealthStatus.HEALTHY
            store.addStats(
                pkg = pkg,
                deltaMonitoredMs = TICK_MS,
                deltaOutageMs = if (outage) TICK_MS else 0L,
                detectLatencyMs = 0,
                nowWall = nowWall,
            )
        }
        return Tick(statuses, dispatched, timedOut, needLock)
    }

    /** 探测结果回来（在客户端专属线程里调用，之后要 `endProbe`）。 */
    @Synchronized
    fun onAck(pkg: String, challenge: Challenge, ack: ChallengeAck, receivedAtMono: Long) {
        val nowWall = wall.now()
        val result = validatorFor(pkg).validate(challenge, ack, receivedAtMono)
        when (result) {
            is AckResult.Accepted -> {
                store.onAckAccepted(
                    pkg,
                    HealthStore.ClientRecordData(
                        instanceId = result.ack.instanceId,
                        seq = result.ack.seq,
                        receivedAtMono = receivedAtMono,
                        ruleRevision = result.ack.ruleRevision,
                        lastReceiptState = result.ack.lastReceiptState,
                        lastReceiptAtMono = result.ack.lastReceiptAtClientMono,
                        eventSeq = result.ack.lastEventSeq,
                        eventAtMono = result.ack.lastEventAtClientMono,
                    ),
                    nowWall,
                )
            }
            is AckResult.Rejected -> store.onAckRejected(pkg, result.reason, nowWall)
        }
    }

    /** 探测线程无回 / 抛异常 / 回包残缺。 */
    fun onProbeFailure(pkg: String, reason: String) {
        store.onAckRejected(pkg, reason, wall.now())
    }

    /** 连接重建：validator 必须换新基线，否则会拿旧实例/旧序号说事（T14）。 */
    @Synchronized
    fun onNewInstance(pkg: String, instanceId: String) {
        validatorFor(pkg).resetForNewInstance(instanceId)
    }

    companion object {
        /** 调度 tick 间隔（探测周期 10s，规范 §7 初值）。 */
        const val TICK_MS = 10_000L
        /** 判超时给的一点余量：Binder 回来得慢一点不算超时。 */
        const val BUDGET_SLACK_MS = 500L
    }
}
