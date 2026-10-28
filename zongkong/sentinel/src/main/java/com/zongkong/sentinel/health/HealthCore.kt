package com.zongkong.sentinel.health

/*
 * 三 APP 强制监管 · 健康与执行协议 v2 —— 纯 Kotlin 内核（不含任何 Android 依赖）。
 *
 * 这份文件是四个应用（总控 / 一手 / 同桌 / 哨兵）共同的权威副本。每个应用复制一份，
 * 只改第一行的包名，其余逐字保留；改动必须先改这一份，再同步到四处。
 *
 * 报告 §6/§7/§8 要求：模型只解释，计时、健康判断、去重、锁屏次数由本地确定性代码决定。
 * 所以这里全部是纯函数 / 纯状态机，可以在 JVM 单元测试里直接推进时间，不依赖真机。
 *
 * 关键立场（对应验收 T02）：
 *   Provider 能响应、Binder 通、后台线程代答、读旧的 running=true，都不算健康。
 *   只有「挑战被投递到真正承担无障碍执行的线程、并在那里处理后带回来」才算 dispatcherAck。
 */

/** 单调时钟（毫秒）。生产环境用 SystemClock.elapsedRealtime()；测试用可手动推进的假钟。 */
fun interface Mono {
    fun now(): Long
}

/** 墙上时钟（毫秒，UTC）。生产环境用 System.currentTimeMillis()。 */
fun interface Wall {
    fun now(): Long
}

enum class HealthStatus {
    /** 没装，或者从没注册过。 */
    UNREGISTERED,

    /** 挑战在真实执行线程里完成了，且新鲜。 */
    HEALTHY,

    /** 有失败迹象，但还没到确认失联的时间预算。 */
    SUSPECT,

    /** 系统设置里无障碍是关的。 */
    DISABLED,

    /** 确认失联：挑战超时 / 事件通道异常，超过确认预算。 */
    UNRESPONSIVE,

    /** 正在自动恢复，等待新的连接实例与真实 dispatcherAck。 */
    RECOVERING,

    /** 证据不足（刚开机、刚休眠唤醒、从没探测过）。不能涂成绿色。 */
    UNKNOWN,

    /** 用户/规则主动暂停。 */
    PAUSED,
}

/** 评测器建议调用方做的动作。 */
enum class HealthAction {
    NONE,

    /** 该发一次挑战了。 */
    PROBE,

    /** 该尝试有限自动恢复（重绑无障碍）。 */
    START_RECOVERY,

    /** 恢复预算用完：停止重试，进入预先显示的失效处理。 */
    ESCALATE,

    /** 状态变化需要提醒用户。 */
    NOTIFY,
}

data class HealthVerdict(
    val status: HealthStatus,
    val action: HealthAction,
    val reason: String,
) {
    val healthy: Boolean get() = status == HealthStatus.HEALTHY
}

/** 一次观察到的客户端体检事实。全部由调用方从系统读取，评测器只做判断。 */
data class HealthObservation(
    /** 包可见且已安装。 */
    val installed: Boolean = true,
    /** 系统设置允许无障碍。 */
    val permissionEnabled: Boolean = true,
    /** 客户端已经向哨兵注册（v2 register 过）。 */
    val registered: Boolean = true,
    /** 客户端当前无障碍服务实例；重建连接必须换新。null = 还没连上。 */
    val instanceId: String? = null,
    /** 最近一次真实 dispatcherAck 的序号（递增）。0 = 从没有过。 */
    val lastAckSeq: Long = 0,
    /** 哨兵收到该 ack 的时刻（单调）。0 = 从没有过。 */
    val lastAckAtMono: Long = 0,
    /** 连续失败次数（超时 / 陈旧实例 / 重复序号都算失败）。 */
    val consecutiveFailures: Int = 0,
    /** 进入恢复的时刻（单调）；0 = 没在恢复。 */
    val recoveringSinceMono: Long = 0,
    /** 本轮已经尝试恢复的次数。 */
    val recoveriesTried: Int = 0,
    /** 规则/用户要求的暂停。 */
    val paused: Boolean = false,
    /** 最近一次失败原因（给人看）。 */
    val lastFailureReason: String = "",
)

data class HealthConfig(
    /** 活跃监管、亮屏期间的探测周期。 */
    val probePeriodMs: Long = 10_000,
    /** 单次挑战等待预算。 */
    val challengeBudgetMs: Long = 5_000,
    /** 连续失败几次进入 SUSPECT。 */
    val suspectAfterFailures: Int = 2,
    /** 调度正常、亮屏条件下，确认失联的时间预算。 */
    val confirmUnresponsiveMs: Long = 30_000,
    /** 允许自动恢复的总预算。 */
    val recoveryBudgetMs: Long = 30_000,
    /** 自动恢复最多尝试几次。 */
    val maxRecoveries: Int = 2,
    /** 解锁后的故障修复宽限：这段时间内不立即再次锁屏。 */
    val repairGraceMs: Long = 2 * 60_000,
    /** 多久没成功 ack 就至少算 SUSPECT（探测周期 × 若干）。 */
    val staleAfterMs: Long = 30_000,
)

/**
 * 健康状态机。纯函数：给同样的观察和时间，永远得到同样的结论。
 *
 * 判断顺序刻意固定：没装 > 权限关 > 没注册 > 暂停 > 恢复中 > 失败计数 > 新鲜度 > 健康。
 * 顺序错了就会出现报告 §3.2 那种「假在线」：某个弱证据把强证据的否定盖掉。
 */
object HealthEvaluator {

    fun evaluate(
        nowMono: Long,
        obs: HealthObservation,
        cfg: HealthConfig = HealthConfig(),
    ): HealthVerdict {
        if (!obs.installed) {
            return HealthVerdict(HealthStatus.UNREGISTERED, HealthAction.NONE, "未安装")
        }
        if (!obs.permissionEnabled) {
            // T01：无障碍开关关掉，必须在确认预算内进入 DISABLED，不能无限等。
            return HealthVerdict(HealthStatus.DISABLED, HealthAction.NOTIFY, "无障碍未开启")
        }
        if (!obs.registered) {
            return HealthVerdict(HealthStatus.UNREGISTERED, HealthAction.NONE, "未接入")
        }
        if (obs.paused) {
            return HealthVerdict(HealthStatus.PAUSED, HealthAction.NONE, "已暂停")
        }

        // 恢复中：只有拿到**新的**连接实例 + 真实 ack 才能变绿（T11）。
        if (obs.recoveringSinceMono > 0) {
            val spent = nowMono - obs.recoveringSinceMono
            if (obs.lastAckAtMono >= obs.recoveringSinceMono && obs.instanceId != null) {
                return HealthVerdict(HealthStatus.HEALTHY, HealthAction.NONE, "恢复后已重新确认")
            }
            if (spent > cfg.recoveryBudgetMs || obs.recoveriesTried >= cfg.maxRecoveries) {
                return HealthVerdict(
                    HealthStatus.UNRESPONSIVE,
                    HealthAction.ESCALATE,
                    "恢复到预算上限仍未重连（已试 ${obs.recoveriesTried} 次）",
                )
            }
            return HealthVerdict(HealthStatus.RECOVERING, HealthAction.NONE, "正在重新挂上无障碍")
        }

        val ackAge = if (obs.lastAckAtMono <= 0) Long.MAX_VALUE else nowMono - obs.lastAckAtMono
        val neverAcked = obs.lastAckAtMono <= 0

        // 连续失败达到阈值：进入 SUSPECT，然后用时间预算收敛到 UNRESPONSIVE。
        if (obs.consecutiveFailures >= cfg.suspectAfterFailures) {
            val confirmSpent = if (neverAcked) 0L else ackAge
            val reason = obs.lastFailureReason.ifBlank { "挑战连续失败 ${obs.consecutiveFailures} 次" }
            if (!neverAcked && confirmSpent >= cfg.confirmUnresponsiveMs) {
                return HealthVerdict(
                    HealthStatus.UNRESPONSIVE,
                    HealthAction.START_RECOVERY,
                    "$reason；已超 ${cfg.confirmUnresponsiveMs / 1000}s 确认预算",
                )
            }
            if (neverAcked) {
                // 从来没 ack 过：给一次恢复机会，但同样有预算上限。
                return HealthVerdict(HealthStatus.UNRESPONSIVE, HealthAction.START_RECOVERY, reason)
            }
            return HealthVerdict(HealthStatus.SUSPECT, HealthAction.PROBE, reason)
        }

        if (neverAcked) {
            // 刚注册、还没探测过：UNKNOWN，不许当健康。
            return HealthVerdict(HealthStatus.UNKNOWN, HealthAction.PROBE, "还没有真实响应")
        }
        if (ackAge > cfg.staleAfterMs) {
            return HealthVerdict(
                HealthStatus.SUSPECT,
                HealthAction.PROBE,
                "最近真实响应已是 ${ackAge / 1000}s 前",
            )
        }
        return HealthVerdict(HealthStatus.HEALTHY, HealthAction.NONE, "真实执行线程在响应")
    }
}

/**
 * 挑战与回执。
 *
 * [Challenge] 由哨兵生成；[ChallengeAck] 必须由客户端**真正承担无障碍执行的线程**处理后填回。
 * 协议明确禁止：Provider 直接回显 nonce、后台线程代答、读旧 running 标志。
 */
data class Challenge(
    val nonce: String,
    val seq: Long,
    val issuedAtMono: Long,
    val expectedInstanceId: String? = null,
)

data class ChallengeAck(
    val protocolVersion: Int,
    val clientPkg: String,
    val instanceId: String,
    val nonce: String,
    val seq: Long,
    /** 客户端在真实执行线程上处理完这次挑战的时刻（客户端单调时钟）。 */
    val handledAtClientMono: Long,
    val ruleRevision: Int = 0,
    val lastReceiptState: String = "",
    val lastReceiptAtClientMono: Long = 0,
    /** 事件通道进度：最近处理的可比对事件序号与时刻。 */
    val lastEventSeq: Long = 0,
    val lastEventAtClientMono: Long = 0,
)

sealed interface AckResult {
    data class Accepted(val ack: ChallengeAck) : AckResult
    data class Rejected(val reason: String) : AckResult
}

/**
 * 回执校验器：拒绝旧实例、重复/倒序序号、错 nonce、过期回应（T14）。
 * 只认「哨兵自己记录接收时刻」算新鲜度，不信客户端自报的时间。
 */
class AckValidator(
    private val maxRoundTripMs: Long = 15_000,
) {
    private var highestSeq: Long = 0
    private var currentInstance: String? = null

    fun resetForNewInstance(instanceId: String) {
        currentInstance = instanceId
        highestSeq = 0
    }

    fun validate(challenge: Challenge, ack: ChallengeAck, receivedAtMono: Long): AckResult {
        if (ack.protocolVersion < PROTOCOL_VERSION) {
            return AckResult.Rejected("协议版本过旧：${ack.protocolVersion}")
        }
        if (ack.nonce != challenge.nonce) return AckResult.Rejected("nonce 不匹配")
        if (ack.seq != challenge.seq) return AckResult.Rejected("序号不匹配")
        if (ack.seq <= highestSeq) return AckResult.Rejected("重复或过期序号：${ack.seq}")

        val expected = challenge.expectedInstanceId ?: currentInstance
        if (expected != null && ack.instanceId != expected) {
            // 连接重建必须换 instanceId；旧实例的回应一律拒绝。
            return AckResult.Rejected("旧实例回应：${ack.instanceId}")
        }
        if (receivedAtMono - challenge.issuedAtMono > maxRoundTripMs) {
            return AckResult.Rejected("回应过期：${receivedAtMono - challenge.issuedAtMono}ms")
        }

        if (currentInstance == null) currentInstance = ack.instanceId
        highestSeq = ack.seq
        return AckResult.Accepted(ack)
    }

    companion object {
        const val PROTOCOL_VERSION = 2
    }
}

/** 锁屏执行回执状态。区分「调用返回」与「真的锁上了」（T10）。 */
enum class LockState { REQUESTED, API_CALLED, OBSERVED_LOCKED, FAILED, UNVERIFIED }

/** 从 KeyguardManager / PowerManager 读到的事实。 */
data class KeyguardFacts(
    val keyguardShowing: Boolean,
    val deviceLocked: Boolean,
    /** 用户是否设置了 PIN / 图案 / 密码。没设的时候 lockNow 可能只是休眠。 */
    val secureLockSet: Boolean,
    val interactive: Boolean,
)

data class LockReceipt(
    val policyId: String,
    val incidentId: String,
    val requestId: String,
    /** sentinel / zongkong / yishou / pet。 */
    val source: String,
    val reason: String,
    val state: LockState,
    val atWall: Long,
    val note: String = "",
)

/**
 * 把「调用了 API」和「观察到锁上了」分开。
 *
 * 报告 §8：[deviceLocked] 但没有安全锁屏，只是休眠，不能报成安全锁定；
 * 拿不到可靠观测就报 UNVERIFIED，不把「没抛异常」当成功。
 */
object LockClassifier {

    fun classify(called: Boolean, error: Throwable?, facts: KeyguardFacts?): Pair<LockState, String> {
        if (!called) {
            return LockState.FAILED to (error?.message ?: "锁屏 API 没有调用成功")
        }
        if (facts == null) {
            return LockState.UNVERIFIED to "无法读取锁屏状态"
        }
        if (!facts.secureLockSet) {
            return LockState.UNVERIFIED to "设备没有设置安全锁屏：可能只是休眠，未安全锁定"
        }
        if (facts.deviceLocked && facts.keyguardShowing) {
            return LockState.OBSERVED_LOCKED to "已观察到系统锁屏界面与设备锁定"
        }
        if (facts.deviceLocked) {
            return LockState.UNVERIFIED to "设备已锁定但没看到锁屏界面"
        }
        return LockState.UNVERIFIED to "调用已返回，但没观测到锁定"
    }
}

data class LockRequest(
    val policyId: String,
    val incidentId: String,
    val requestId: String,
    val source: String,
    val reason: String,
)

data class LockDecision(
    val fire: Boolean,
    val reason: String,
    val attempt: Int = 0,
)

/**
 * 执行仲裁：一个故障事件只自动锁一次，四个应用不许同时反复锁（T07）。
 *
 * 用 incidentId 去重 + 全局冷却 + 每事件次数上限。分区或 IPC 故障下无法保证「绝不重复」，
 * 所以这里做的是「把重复压到可接受范围」，而不是承诺绝对互斥。
 */
class LockArbiter(
    private val maxAttemptsPerIncident: Int = 2,
    private val retryCooldownMs: Long = 3 * 60_000,
    private val minGapBetweenLocksMs: Long = 30_000,
) {
    private val attempts = LinkedHashMap<String, MutableList<Long>>()
    private val states = LinkedHashMap<String, LockState>()
    private var lastFireAtMono = 0L

    fun decide(req: LockRequest, nowMono: Long, repairGraceUntilMono: Long = 0): LockDecision {
        // 解锁后正在修权限：宽限期内不重锁，免得挡住修复（T15）。
        if (repairGraceUntilMono > 0 && nowMono < repairGraceUntilMono) {
            return LockDecision(false, "修复宽限中，剩余 ${(repairGraceUntilMono - nowMono) / 1000}s")
        }
        val done = states[req.incidentId]
        val tried = attempts[req.incidentId].orEmpty()

        if (done == LockState.OBSERVED_LOCKED) {
            return LockDecision(false, "同一事件已确认锁屏，不重复执行")
        }
        if (tried.size >= maxAttemptsPerIncident) {
            return LockDecision(false, "同一事件已达 ${maxAttemptsPerIncident} 次上限")
        }
        if (tried.isNotEmpty() && nowMono - tried.last() < retryCooldownMs) {
            return LockDecision(false, "重试冷却中，剩余 ${(retryCooldownMs - (nowMono - tried.last())) / 1000}s")
        }
        if (tried.isEmpty() && lastFireAtMono > 0 && nowMono - lastFireAtMono < minGapBetweenLocksMs) {
            return LockDecision(false, "距上次锁屏不足 ${minGapBetweenLocksMs / 1000}s")
        }
        return LockDecision(true, "允许执行", attempt = tried.size + 1)
    }

    fun record(req: LockRequest, state: LockState, nowMono: Long) {
        attempts.getOrPut(req.incidentId) { mutableListOf() }.add(nowMono)
        states[req.incidentId] = state
        lastFireAtMono = nowMono
    }

    fun forget(incidentId: String) {
        attempts.remove(incidentId)
        states.remove(incidentId)
    }
}

/**
 * 监管窗口规则（报告 §7）。先持久化，再开始监管。
 *
 * [requiredApps] 是这个窗口必须在线的应用；「三体系共同监管」明确包含三个包。
 * 窗口过期必须停止执行，不能靠客户端反复心跳续命。
 */
data class Policy(
    val policyId: String,
    val revision: Int,
    val issuer: String,
    val requiredApps: Set<String>,
    val startsAtWall: Long,
    val endsAtWall: Long,
    /** 可选的到期锁屏事件。 */
    val lockAtWall: Long? = null,
    val failureAction: String = "LOCK",
    val repairGraceMs: Long = 2 * 60_000,
    val emergencyState: String = "NONE",
    val enabled: Boolean = false,
) {
    /** 窗口内、且已启用，才算生效。 */
    fun effective(nowWall: Long): Boolean = enabled && nowWall >= startsAtWall && nowWall < endsAtWall

    /** 到期锁屏是否到点（可重复调用，由仲裁器去重）。 */
    fun lockDue(nowWall: Long): Boolean {
        val at = lockAtWall ?: return false
        return enabled && nowWall >= at && nowWall < endsAtWall
    }
}

/**
 * 单调压紧的墙钟。
 *
 * 报告 §7/§14：改系统时间、切时区、重启不能让监管无限延长。
 * 做法：以单调时钟为锚，墙钟只允许按单调流逝前进；检测到往回跳就忽略跳变，
 * 检测到往前跳也给每次观察设上限。
 */
class ClampedClock(
    private val wall: Wall,
    private val mono: Mono,
    /** 单次观察允许的最大合理跳变（默认 5 分钟）。 */
    private val maxJumpMs: Long = 5 * 60_000,
) {
    private var anchorWall: Long = -1
    private var anchorMono: Long = -1

    fun now(): Long {
        val w = wall.now()
        val m = mono.now()
        if (anchorMono < 0) {
            anchorWall = w
            anchorMono = m
            return w
        }
        val elapsed = (m - anchorMono).coerceAtLeast(0)
        val expected = anchorWall + elapsed
        // 墙钟比「锚点 + 单调流逝」快很多（用户往前调时间 / 换时区）：不认。
        val candidate = if (w > expected + maxJumpMs) expected else w
        // 墙钟往回跳：也不认，最多认单调流逝得到的值。
        val clamped = if (candidate < expected - maxJumpMs) expected else candidate
        anchorWall = clamped
        anchorMono = m
        return clamped
    }

    /** 重启后重新校验：用磁盘上的墙钟 + 本次开机单调时间重新立锚。 */
    fun reanchor(persistedWall: Long, bootMono: Long) {
        anchorWall = persistedWall
        anchorMono = bootMono
    }
}

/** 监管窗口的剩余时间，用来在界面上说真话。 */
object PolicyClock {

    fun remainingMs(policy: Policy, nowWall: Long): Long =
        (policy.endsAtWall - nowWall).coerceAtLeast(0)

    fun nextDeadline(policy: Policy, nowWall: Long): Long? {
        if (!policy.enabled) return null
        val at = policy.lockAtWall ?: return null
        if (at <= nowWall || nowWall >= policy.endsAtWall) return null
        return at
    }

    /**
     * 已启用规则到了开始时间、所需应用却没接入：必须按预先显示的失效处理，
     * 不能无限停在「等你开启」（T01/T17）。
     */
    fun missingApps(policy: Policy, healthyApps: Set<String>, nowWall: Long): Set<String> {
        if (!policy.effective(nowWall)) return emptySet()
        return policy.requiredApps - healthyApps
    }
}

/** 首页/统计页要说的四句话（报告 §10）。 */
enum class OverallReadiness {
    ALL_READY,
    PARTIAL_FAILURE,
    SENTINEL_UNAVAILABLE,
    NO_ACTIVE_WINDOW,
}

object Readiness {

    /**
     * 不做总体修辞：只要有一个必需应用没在真实响应，就不能说「三体系已就绪」。
     */
    fun overall(
        policies: List<Policy>,
        nowWall: Long,
        requiredApps: Set<String>,
        appStatus: Map<String, HealthStatus>,
        sentinelHealthy: Boolean,
    ): OverallReadiness {
        val active = policies.filter { it.effective(nowWall) }
        if (active.isEmpty()) return OverallReadiness.NO_ACTIVE_WINDOW
        if (!sentinelHealthy) return OverallReadiness.SENTINEL_UNAVAILABLE
        val unhealthy = requiredApps.any { appStatus[it] != HealthStatus.HEALTHY }
        return if (unhealthy) OverallReadiness.PARTIAL_FAILURE else OverallReadiness.ALL_READY
    }
}

/** 统计页口径：没观测的数据标记缺失，不能塞进在线率分母（报告 §10）。 */
data class HealthStats(
    val monitoredMs: Long = 0,
    val outageCount: Int = 0,
    val outageTotalMs: Long = 0,
    val recoveryAttempts: Int = 0,
    val recoverySuccesses: Int = 0,
    val lockAttempts: Int = 0,
    val lockConfirmed: Int = 0,
    val lockUnverified: Int = 0,
    val detectLatencyTotalMs: Long = 0,
    val detectLatencySamples: Int = 0,
) {
    /** 只有拿到过 ack 的时间才计入分母；从没观测过返回 null，界面显示「数据缺失」。 */
    val availability: Double?
        get() = if (monitoredMs <= 0) null else
            ((monitoredMs - outageTotalMs).coerceAtLeast(0).toDouble() / monitoredMs)

    val recoveryRate: Double?
        get() = if (recoveryAttempts <= 0) null else recoverySuccesses.toDouble() / recoveryAttempts

    val avgDetectLatencyMs: Long?
        get() = if (detectLatencySamples <= 0) null else detectLatencyTotalMs / detectLatencySamples
}

