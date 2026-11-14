package io.github.ccch1mneyyy.dsh_app.fish.guardian.health

import java.time.Instant
import java.time.ZoneId

/**
 * Pure Kotlin domain core for `大肥鱼哨兵`:
 *  - Engine & accessibility health state machine (`FishHealthEvaluator`)
 *  - Main-thread challenge ack replay/freeze defense (`ChallengeAckValidator`)
 *  - Clock-rollback defense (`ClampedWallClock`)
 *  - Independent 2x/day 3-minute emergency pass budget (`EmergencyPassQuota`)
 *  - Accessibility window decision (`GuardianDecide`)
 *  - Device-admin lock deduplication & observation (`LockArbiter`, `LockClassifier`)
 */

fun interface Mono {
    fun now(): Long
}

fun interface Wall {
    fun now(): Long
}

object GuardianConstants {
    const val PROTOCOL_VERSION = 1
    const val CLIENT_FISH_PKG = "io.github.ccch1mneyyy.dsh_app.fish"
    const val CLIENT_BASE_PKG = "io.github.ccch1mneyyy.dsh_app"
    const val GUARDIAN_PKG = "io.github.ccch1mneyyy.dsh_app.fish.guardian"
    const val AUTHORITY_SUFFIX = ".guardian_health"
    val CANDIDATE_TARGETS = listOf(CLIENT_FISH_PKG, CLIENT_BASE_PKG)

    val TIER0_SAFE_PACKAGES = setOf(
        GUARDIAN_PKG,
        CLIENT_FISH_PKG,
        CLIENT_BASE_PKG,
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.android.camera",
        "com.android.camera2",
        "com.google.android.GoogleCamera",
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.eg.android.AlipayGphone",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
    )

    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.samsung.android.lool",
        "com.iqoo.secure",
    )

    val DANGER_KEYWORDS = listOf("强行停止", "强制停止", "卸载", "停用", "无障碍", "辅助功能", "设备管理")
    val PROTECTED_NAMES = listOf("DSH Go", "DSH", "大肥鱼", "大肥鱼哨兵", "哨兵")
}

enum class EngineState {
    NOT_INSTALLED,
    NEEDS_PAIRING,
    UNKNOWN,
    HEALTHY,
    STARTING_GRACE,
    SUSPECT,
    A11Y_DISABLED,
    ENGINE_DOWN,
    UNRESPONSIVE,
}

enum class EngineHealthAction {
    NONE,
    PROBE,
    ATTEMPT_ENGINE_WAKE,
    ATTEMPT_A11Y_REBIND,
    SHOW_MODAL_AND_LOCK,
}

data class EngineHealthVerdict(
    val state: EngineState,
    val action: EngineHealthAction,
    val reason: String,
) {
    val isHealthy: Boolean get() = state == EngineState.HEALTHY
    val isCritical: Boolean
        get() = state == EngineState.ENGINE_DOWN ||
            state == EngineState.UNRESPONSIVE ||
            state == EngineState.A11Y_DISABLED
}

data class EngineHealthConfig(
    val probePeriodMs: Long = 10_000L,
    val challengeBudgetMs: Long = 4_500L,
    val startingGraceMs: Long = 45_000L,
    val staleAfterMs: Long = 30_000L,
    val suspectAfterFailures: Int = 2,
    val confirmUnresponsiveMs: Long = 30_000L,
    val wakeCooldownMs: Long = 20_000L,
)

data class EngineObservation(
    val targetPkg: String? = null,
    val installed: Boolean = false,
    val paired: Boolean = false,
    val permissionA11yEnabled: Boolean = false,
    val a11yConnected: Boolean = false,
    val enginePhase: String = "unknown",
    val dshServiceRunning: Boolean = false,
    val lastAckAtMono: Long = 0L,
    val lastProbeAtMono: Long = 0L,
    val consecutiveFailures: Int = 0,
    val startingSinceMono: Long = 0L,
    val lastWakeAttemptMono: Long = 0L,
    val lastFailureReason: String = "",
    val guardianPausedByPass: Boolean = false,
)

/**
 * Pure deterministic evaluator distinguishing benign cold startup (`starting` <= 45s)
 * from real outages (`stopped` / `installed` / `error` / hung a11y looper).
 */
object FishHealthEvaluator {
    fun evaluate(
        nowMono: Long,
        obs: EngineObservation,
        cfg: EngineHealthConfig = EngineHealthConfig(),
    ): EngineHealthVerdict {
        if (!obs.installed || obs.targetPkg.isNullOrBlank()) {
            return EngineHealthVerdict(EngineState.NOT_INSTALLED, EngineHealthAction.NONE, "未安装 DSH Go 大肥鱼")
        }
        if (!obs.paired) {
            return EngineHealthVerdict(EngineState.NEEDS_PAIRING, EngineHealthAction.PROBE, "尚未完成与大肥鱼的互信配对")
        }
        if (!obs.permissionA11yEnabled) {
            return EngineHealthVerdict(
                EngineState.A11Y_DISABLED,
                EngineHealthAction.ATTEMPT_A11Y_REBIND,
                "系统无障碍开关已关闭",
            )
        }

        // Starting/installing within 45s is allowed without sounding false alarm.
        if (obs.enginePhase == "starting" || obs.enginePhase == "installing") {
            val since = if (obs.startingSinceMono > 0L) obs.startingSinceMono else nowMono
            val spent = (nowMono - since).coerceAtLeast(0L)
            if (spent <= cfg.startingGraceMs) {
                return EngineHealthVerdict(
                    EngineState.STARTING_GRACE,
                    EngineHealthAction.PROBE,
                    "引擎正在启动（${spent / 1000}s / ${cfg.startingGraceMs / 1000}s）",
                )
            }
            return EngineHealthVerdict(
                EngineState.UNRESPONSIVE,
                EngineHealthAction.SHOW_MODAL_AND_LOCK,
                "引擎启动超过 ${cfg.startingGraceMs / 1000}s 仍未就绪",
            )
        }

        // Engine explicitly stopped, errored, or sitting idle in `installed` without DshService.
        if (obs.enginePhase in setOf("installed", "error", "stopping", "notInstalled")) {
            val canWake = obs.lastWakeAttemptMono <= 0L || (nowMono - obs.lastWakeAttemptMono >= cfg.wakeCooldownMs)
            val action = if (!canWake) {
                EngineHealthAction.SHOW_MODAL_AND_LOCK
            } else {
                EngineHealthAction.ATTEMPT_ENGINE_WAKE
            }
            return EngineHealthVerdict(
                EngineState.ENGINE_DOWN,
                action,
                "DSH 引擎当前状态为 ${obs.enginePhase}（已退出或被关闭）",
            )
        }

        val ackAge = if (obs.lastAckAtMono <= 0L) Long.MAX_VALUE else (nowMono - obs.lastAckAtMono).coerceAtLeast(0L)
        if (obs.consecutiveFailures >= cfg.suspectAfterFailures) {
            val reason = obs.lastFailureReason.ifBlank { "健康探针连续失败 ${obs.consecutiveFailures} 次" }
            if (obs.lastAckAtMono <= 0L || ackAge >= cfg.confirmUnresponsiveMs) {
                val canWake = obs.lastWakeAttemptMono <= 0L || (nowMono - obs.lastWakeAttemptMono >= cfg.wakeCooldownMs)
                return EngineHealthVerdict(
                    EngineState.UNRESPONSIVE,
                    if (canWake) EngineHealthAction.ATTEMPT_ENGINE_WAKE else EngineHealthAction.SHOW_MODAL_AND_LOCK,
                    "$reason（确认已失联）",
                )
            }
            return EngineHealthVerdict(EngineState.SUSPECT, EngineHealthAction.PROBE, reason)
        }

        if (obs.lastAckAtMono <= 0L) {
            return EngineHealthVerdict(EngineState.UNKNOWN, EngineHealthAction.PROBE, "尚未收到真实探针回执")
        }

        if (!obs.a11yConnected) {
            return EngineHealthVerdict(
                EngineState.A11Y_DISABLED,
                EngineHealthAction.ATTEMPT_A11Y_REBIND,
                "无障碍服务未连接",
            )
        }

        if (ackAge > cfg.staleAfterMs) {
            return EngineHealthVerdict(
                EngineState.SUSPECT,
                EngineHealthAction.PROBE,
                "上次真实回执已过 ${ackAge / 1000}s",
            )
        }

        if (obs.enginePhase == "running") {
            return EngineHealthVerdict(
                EngineState.HEALTHY,
                EngineHealthAction.NONE,
                "引擎运行中，无障碍主线程响应正常",
            )
        }

        return EngineHealthVerdict(
            EngineState.SUSPECT,
            EngineHealthAction.PROBE,
            "等待引擎状态同步（${obs.enginePhase}）",
        )
    }
}

data class ProbeChallenge(
    val nonce: String,
    val seq: Long,
    val issuedAtMono: Long,
)

data class ProbeAck(
    val protocolVersion: Int,
    val targetPkg: String,
    val nonce: String,
    val seq: Long,
    val a11yConnected: Boolean,
    val handledAtClientMono: Long,
    val a11yInstanceId: String,
    val enginePhase: String,
    val dshServiceRunning: Boolean,
    val proactiveTransport: Boolean,
    val policyMirrorJson: String,
)

sealed interface ProbeValidation {
    data class Accepted(val ack: ProbeAck) : ProbeValidation
    data class Rejected(val reason: String) : ProbeValidation
}

class ChallengeAckValidator(private val maxRoundTripMs: Long = 15_000L) {
    private var highestSeq: Long = 0L

    fun validate(challenge: ProbeChallenge, ack: ProbeAck, receivedAtMono: Long): ProbeValidation {
        if (ack.protocolVersion < GuardianConstants.PROTOCOL_VERSION) {
            return ProbeValidation.Rejected("协议版本过旧：${ack.protocolVersion}")
        }
        if (ack.nonce != challenge.nonce) return ProbeValidation.Rejected("nonce 不匹配")
        if (ack.seq != challenge.seq) return ProbeValidation.Rejected("seq 不匹配")
        if (ack.seq <= highestSeq) return ProbeValidation.Rejected("重复或过期序号 ${ack.seq}")
        if (receivedAtMono - challenge.issuedAtMono > maxRoundTripMs) {
            return ProbeValidation.Rejected("探针往返超时")
        }
        highestSeq = ack.seq
        return ProbeValidation.Accepted(ack)
    }
}

/** Monotonic-clamped wall clock defending against manual backwards time changes. */
class ClampedWallClock(
    private val wall: Wall,
    private val mono: Mono,
    private val maxJumpMs: Long = 5 * 60_000L,
) {
    private var anchorWall: Long = -1L
    private var anchorMono: Long = -1L

    fun now(): Long {
        val w = wall.now()
        val m = mono.now()
        if (anchorMono < 0L) {
            anchorWall = w
            anchorMono = m
            return w
        }
        val elapsed = (m - anchorMono).coerceAtLeast(0L)
        val expected = anchorWall + elapsed
        val candidate = if (w > expected + maxJumpMs) expected else w
        val clamped = if (candidate < expected - maxJumpMs) expected else candidate
        anchorWall = clamped
        anchorMono = m
        return clamped
    }

    fun monoNow(): Long = mono.now()
}

data class EmergencyPassState(
    val dayKey: String = "",
    val usedToday: Int = 0,
    val activeUntilMono: Long = 0L,
    val reason: String = "",
)

data class EmergencyGrantResult(
    val ok: Boolean,
    val state: EmergencyPassState,
    val error: String = "",
)

/**
 * Independent 3-minute emergency pass in `大肥鱼哨兵` (max 2 times per Beijing day).
 * Uses monotonic [activeUntilMono] for duration so changing wall clock cannot extend a pass.
 */
object EmergencyPassQuota {
    const val MAX_PER_DAY = 2
    const val PASS_DURATION_MS = 3 * 60_000L
    const val MIN_REASON_LEN = 2
    private val SHANGHAI = ZoneId.of("Asia/Shanghai")

    fun dayKeyOf(nowWallMs: Long): String {
        val z = Instant.ofEpochMilli(nowWallMs).atZone(SHANGHAI)
        return "%04d-%02d-%02d".format(z.year, z.monthValue, z.dayOfMonth)
    }

    fun normalizeForDay(state: EmergencyPassState, nowWallMs: Long): EmergencyPassState {
        val key = dayKeyOf(nowWallMs)
        return if (state.dayKey == key) state else state.copy(dayKey = key, usedToday = 0)
    }

    fun isActive(state: EmergencyPassState, nowMonoMs: Long): Boolean =
        state.activeUntilMono > 0L && nowMonoMs < state.activeUntilMono

    fun remainingCount(state: EmergencyPassState, nowWallMs: Long): Int {
        val norm = normalizeForDay(state, nowWallMs)
        return (MAX_PER_DAY - norm.usedToday).coerceAtLeast(0)
    }

    fun grant(
        current: EmergencyPassState,
        reason: String,
        nowWallMs: Long,
        nowMonoMs: Long,
    ): EmergencyGrantResult {
        val clean = reason.trim()
        if (clean.length < MIN_REASON_LEN) {
            return EmergencyGrantResult(false, current, "请填写至少 $MIN_REASON_LEN 个字说明急事用途")
        }
        val norm = normalizeForDay(current, nowWallMs)
        if (norm.usedToday >= MAX_PER_DAY) {
            return EmergencyGrantResult(false, norm, "今日独立急事通行次数已用完（$MAX_PER_DAY/$MAX_PER_DAY）")
        }
        val next = norm.copy(
            usedToday = norm.usedToday + 1,
            activeUntilMono = nowMonoMs + PASS_DURATION_MS,
            reason = clean.take(120),
        )
        return EmergencyGrantResult(true, next)
    }
}

data class MirroredPolicy(
    val updatedAtWall: Long = 0L,
    val enginePhase: String = "unknown",
    val planActive: Boolean = false,
    val primaryKind: String = "",
    val primaryWhy: String = "",
    val lockUntil: Long = 0L,
    val refuseUntil: Long = 0L,
    val breakUntil: Long = 0L,
    val emergencyUntil: Long = 0L,
    val sessionUntil: Long = 0L,
    val blocked: List<String> = emptyList(),
    val blockWhys: Map<String, String> = emptyMap(),
    val allow: List<String> = emptyList(),
) {
    fun hasActiveEnforcement(nowWall: Long): Boolean {
        if (breakUntil > nowWall || emergencyUntil > nowWall) return false
        return planActive || refuseUntil > nowWall || lockUntil > nowWall || sessionUntil > nowWall
    }

    companion object {
        fun fromJson(json: String): MirroredPolicy? {
            if (json.isBlank()) return null
            val m = GuardianJson.map(GuardianJson.parse(json))
            return MirroredPolicy(
                updatedAtWall = GuardianJson.lng(m, "updatedAtWall", 0L),
                enginePhase = GuardianJson.str(m, "enginePhase", "unknown"),
                planActive = GuardianJson.bool(m, "planActive", false),
                primaryKind = GuardianJson.str(m, "primaryKind", ""),
                primaryWhy = GuardianJson.str(m, "primaryWhy", ""),
                lockUntil = GuardianJson.lng(m, "lockUntil", 0L),
                refuseUntil = GuardianJson.lng(m, "refuseUntil", 0L),
                breakUntil = GuardianJson.lng(m, "breakUntil", 0L),
                emergencyUntil = GuardianJson.lng(m, "emergencyUntil", 0L),
                sessionUntil = GuardianJson.lng(m, "sessionUntil", 0L),
                blocked = GuardianJson.strList(m["blocked"]),
                blockWhys = GuardianJson.strMap(m["blockWhys"]),
                allow = GuardianJson.strList(m["allow"]),
            )
        }
    }
}

sealed interface WindowDecision {
    data object Allow : WindowDecision
    data class BlockTamper(val matchedKeyword: String, val inPkg: String) : WindowDecision
    data class BlockApp(val pkg: String, val reason: String) : WindowDecision
    data class BlockEngineDown(val engineState: EngineState, val reason: String) : WindowDecision
}

/**
 * Stateless window arbitration used by [GuardianA11yService] to mirror DSH Go rules
 * when DSH Go is killed or under settings attack.
 */
object GuardianDecide {
    fun evaluateWindow(
        frontPkg: String,
        screenTexts: List<String>,
        homePackages: Set<String>,
        imePackages: Set<String>,
        mirror: MirroredPolicy,
        engineVerdict: EngineHealthVerdict,
        passActive: Boolean,
        modalOnEngineDown: Boolean,
        nowWall: Long,
    ): WindowDecision {
        if (frontPkg.isBlank()) return WindowDecision.Allow
        if (frontPkg in GuardianConstants.TIER0_SAFE_PACKAGES ||
            frontPkg in homePackages ||
            frontPkg in imePackages
        ) {
            return WindowDecision.Allow
        }
        if (passActive || mirror.breakUntil > nowWall || mirror.emergencyUntil > nowWall) {
            return WindowDecision.Allow
        }

        // Settings tamper self-defense: block attempts to disable DSH Go or Fish Guardian.
        if (frontPkg in GuardianConstants.SETTINGS_PACKAGES) {
            val mentionsProtected = screenTexts.any { line ->
                GuardianConstants.PROTECTED_NAMES.any { name -> line.contains(name, ignoreCase = true) }
            }
            if (mentionsProtected) {
                val danger = GuardianConstants.DANGER_KEYWORDS.firstOrNull { kw ->
                    screenTexts.any { line -> line.contains(kw) }
                }
                if (danger != null) {
                    return WindowDecision.BlockTamper(danger, frontPkg)
                }
            }
            return WindowDecision.Allow
        }

        // If engine/a11y is critically down while supervision/monitoring is active, raise modal.
        if (modalOnEngineDown && engineVerdict.isCritical) {
            return WindowDecision.BlockEngineDown(engineVerdict.state, engineVerdict.reason)
        }

        // Mirrored enforcement rules while DSH Go is recovering or enforcing.
        if (mirror.hasActiveEnforcement(nowWall)) {
            if (frontPkg in mirror.blocked) {
                val why = mirror.blockWhys[frontPkg]
                    ?: mirror.primaryWhy.ifBlank { "当前受大肥鱼守护规则管控（${mirror.enginePhase}）" }
                return WindowDecision.BlockApp(frontPkg, why)
            }
            if (mirror.sessionUntil > nowWall && frontPkg !in mirror.allow) {
                val why = mirror.primaryWhy.ifBlank { "严格专注期：仅放行计划允许应用" }
                return WindowDecision.BlockApp(frontPkg, why)
            }
        }
        return WindowDecision.Allow
    }
}

enum class LockState { REQUESTED, OBSERVED_LOCKED, FAILED, UNVERIFIED }

data class KeyguardFacts(
    val keyguardShowing: Boolean,
    val deviceLocked: Boolean,
    val secureLockSet: Boolean,
    val interactive: Boolean,
)

object LockClassifier {
    fun classify(called: Boolean, error: Throwable?, facts: KeyguardFacts?): Pair<LockState, String> {
        if (!called) return LockState.FAILED to (error?.message ?: "lockNow 调用失败")
        if (facts == null) return LockState.UNVERIFIED to "无法读取锁屏状态"
        if (!facts.secureLockSet) return LockState.UNVERIFIED to "未设置系统安全锁屏，无法确认锁定"
        if (facts.deviceLocked && facts.keyguardShowing) return LockState.OBSERVED_LOCKED to "已确认系统锁屏"
        if (facts.deviceLocked) return LockState.UNVERIFIED to "设备锁定但未显示锁屏界面"
        return LockState.UNVERIFIED to "lockNow 返回但未观测到锁定"
    }
}

data class LockDecision(val fire: Boolean, val reason: String)

class LockArbiter(
    private val maxAttemptsPerIncident: Int = 2,
    private val retryCooldownMs: Long = 3 * 60_000L,
    private val minGapMs: Long = 30_000L,
) {
    private val attempts = LinkedHashMap<String, MutableList<Long>>()
    private val states = LinkedHashMap<String, LockState>()
    private var lastFireAtMono = 0L

    fun decide(incidentId: String, nowMono: Long, repairGraceUntilMono: Long = 0L): LockDecision {
        if (repairGraceUntilMono > nowMono) {
            return LockDecision(false, "修复宽限期中")
        }
        if (states[incidentId] == LockState.OBSERVED_LOCKED) {
            return LockDecision(false, "同一事件已确认锁屏")
        }
        val list = attempts[incidentId].orEmpty()
        if (list.size >= maxAttemptsPerIncident) {
            return LockDecision(false, "同一事件已达上限 $maxAttemptsPerIncident 次")
        }
        if (list.isNotEmpty() && nowMono - list.last() < retryCooldownMs) {
            return LockDecision(false, "冷却中")
        }
        if (list.isEmpty() && lastFireAtMono > 0L && nowMono - lastFireAtMono < minGapMs) {
            return LockDecision(false, "全局锁屏冷却中")
        }
        return LockDecision(true, "允许执行")
    }

    fun record(incidentId: String, state: LockState, nowMono: Long) {
        attempts.getOrPut(incidentId) { mutableListOf() }.add(nowMono)
        states[incidentId] = state
        lastFireAtMono = nowMono
    }
}

object EnabledServiceList {
    fun append(current: String?, component: String): String {
        val parts = (current ?: "").split(':').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (component !in parts) parts += component
        return parts.joinToString(":")
    }

    fun remove(current: String?, component: String): String =
        (current ?: "").split(':').map { it.trim() }.filter { it.isNotEmpty() && it != component }.joinToString(":")
}
