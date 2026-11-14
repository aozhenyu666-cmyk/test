package io.github.ccch1mneyyy.dsh_app.fish.guardian.watch

import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyGrantResult
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyPassQuota
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EmergencyPassState
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineHealthConfig
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineHealthVerdict
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.EngineObservation
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.FishHealthEvaluator
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianJson
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.MirroredPolicy
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.ProbeAck
import java.io.File
import java.io.FileOutputStream

data class GuardianSettings(
    val modalOnEngineDown: Boolean = true,
    val lockOnEngineDown: Boolean = false,
    val autoRebindA11y: Boolean = true,
)

data class OfflineIncident(
    val id: String,
    val type: String,
    val atWall: Long,
    val detail: String = "",
    val reason: String = "",
    val enginePhase: String = "",
    val healthStatus: String = "",
    val pkg: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "type" to type,
        "atWall" to atWall,
        "detail" to detail,
        "reason" to reason,
        "enginePhase" to enginePhase,
        "healthStatus" to healthStatus,
        "pkg" to pkg,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): OfflineIncident? {
            val id = GuardianJson.str(m, "id")
            val type = GuardianJson.str(m, "type")
            if (id.isBlank() || type.isBlank()) return null
            return OfflineIncident(
                id = id,
                type = type,
                atWall = GuardianJson.lng(m, "atWall", 0L),
                detail = GuardianJson.str(m, "detail"),
                reason = GuardianJson.str(m, "reason"),
                enginePhase = GuardianJson.str(m, "enginePhase"),
                healthStatus = GuardianJson.str(m, "healthStatus"),
                pkg = GuardianJson.str(m, "pkg"),
            )
        }
    }
}

data class GuardianSnapshot(
    val targetPkg: String = "",
    val installed: Boolean = false,
    val paired: Boolean = false,
    val sameCert: Boolean = false,
    val permissionA11yEnabled: Boolean = false,
    val selfA11yEnabled: Boolean = false,
    val a11yConnected: Boolean = false,
    val enginePhase: String = "unknown",
    val dshServiceRunning: Boolean = false,
    val proactiveTransport: Boolean = false,
    val lastAckAtMono: Long = 0L,
    val lastAckAtWall: Long = 0L,
    val lastProbeAtMono: Long = 0L,
    val consecutiveFailures: Int = 0,
    val startingSinceMono: Long = 0L,
    val lastWakeAttemptMono: Long = 0L,
    val lastFailureReason: String = "",
    val stateName: String = "UNKNOWN",
    val statusReason: String = "等待首次检测",
    val updatedAtWall: Long = 0L,
    val pendingOfflineCount: Int = 0,
    val lastLockNote: String = "",
    val adminActive: Boolean = false,
    val writeSecureGranted: Boolean = false,
    val overlayGranted: Boolean = false,
) {
    fun toObservation(nowMono: Long, passActive: Boolean): EngineObservation = EngineObservation(
        targetPkg = targetPkg.ifBlank { null },
        installed = installed,
        paired = paired,
        permissionA11yEnabled = permissionA11yEnabled,
        a11yConnected = a11yConnected,
        enginePhase = enginePhase,
        dshServiceRunning = dshServiceRunning,
        lastAckAtMono = lastAckAtMono,
        lastProbeAtMono = lastProbeAtMono,
        consecutiveFailures = consecutiveFailures,
        startingSinceMono = startingSinceMono,
        lastWakeAttemptMono = lastWakeAttemptMono,
        lastFailureReason = lastFailureReason,
        guardianPausedByPass = passActive,
    )

    fun verdict(nowMono: Long, passActive: Boolean, cfg: EngineHealthConfig = EngineHealthConfig()): EngineHealthVerdict =
        FishHealthEvaluator.evaluate(nowMono, toObservation(nowMono, passActive), cfg)

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "targetPkg" to targetPkg,
        "installed" to installed,
        "paired" to paired,
        "sameCert" to sameCert,
        "permissionA11yEnabled" to permissionA11yEnabled,
        "selfA11yEnabled" to selfA11yEnabled,
        "a11yConnected" to a11yConnected,
        "enginePhase" to enginePhase,
        "dshServiceRunning" to dshServiceRunning,
        "proactiveTransport" to proactiveTransport,
        "lastAckAtMono" to lastAckAtMono,
        "lastAckAtWall" to lastAckAtWall,
        "lastProbeAtMono" to lastProbeAtMono,
        "consecutiveFailures" to consecutiveFailures,
        "startingSinceMono" to startingSinceMono,
        "lastWakeAttemptMono" to lastWakeAttemptMono,
        "lastFailureReason" to lastFailureReason,
        "stateName" to stateName,
        "statusReason" to statusReason,
        "updatedAtWall" to updatedAtWall,
        "pendingOfflineCount" to pendingOfflineCount,
        "lastLockNote" to lastLockNote,
        "adminActive" to adminActive,
        "writeSecureGranted" to writeSecureGranted,
        "overlayGranted" to overlayGranted,
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): GuardianSnapshot = GuardianSnapshot(
            targetPkg = GuardianJson.str(m, "targetPkg"),
            installed = GuardianJson.bool(m, "installed"),
            paired = GuardianJson.bool(m, "paired"),
            sameCert = GuardianJson.bool(m, "sameCert"),
            permissionA11yEnabled = GuardianJson.bool(m, "permissionA11yEnabled"),
            selfA11yEnabled = GuardianJson.bool(m, "selfA11yEnabled"),
            a11yConnected = GuardianJson.bool(m, "a11yConnected"),
            enginePhase = GuardianJson.str(m, "enginePhase", "unknown"),
            dshServiceRunning = GuardianJson.bool(m, "dshServiceRunning"),
            proactiveTransport = GuardianJson.bool(m, "proactiveTransport"),
            lastAckAtMono = GuardianJson.lng(m, "lastAckAtMono"),
            lastAckAtWall = GuardianJson.lng(m, "lastAckAtWall"),
            lastProbeAtMono = GuardianJson.lng(m, "lastProbeAtMono"),
            consecutiveFailures = GuardianJson.int(m, "consecutiveFailures"),
            startingSinceMono = GuardianJson.lng(m, "startingSinceMono"),
            lastWakeAttemptMono = GuardianJson.lng(m, "lastWakeAttemptMono"),
            lastFailureReason = GuardianJson.str(m, "lastFailureReason"),
            stateName = GuardianJson.str(m, "stateName", "UNKNOWN"),
            statusReason = GuardianJson.str(m, "statusReason", "等待首次检测"),
            updatedAtWall = GuardianJson.lng(m, "updatedAtWall"),
            pendingOfflineCount = GuardianJson.int(m, "pendingOfflineCount"),
            lastLockNote = GuardianJson.str(m, "lastLockNote"),
            adminActive = GuardianJson.bool(m, "adminActive"),
            writeSecureGranted = GuardianJson.bool(m, "writeSecureGranted"),
            overlayGranted = GuardianJson.bool(m, "overlayGranted"),
        )
    }
}

/**
 * Pure JVM testable atomic storage backing health snapshot, mirrored policy,
 * guardian settings, emergency pass quota, and offline incident queue.
 */
class GuardianStores(private val dir: File) {
    private val snapshotFile = File(dir, "health_snapshot.json")
    private val mirrorFile = File(dir, "mirrored_policy.json")
    private val settingsFile = File(dir, "guardian_settings.json")
    private val passFile = File(dir, "emergency_pass.json")
    private val incidentsFile = File(dir, "offline_incidents.json")
    private val lock = Object()

    fun loadSnapshot(): GuardianSnapshot = synchronized(lock) {
        readJson(snapshotFile)?.let { GuardianSnapshot.fromMap(GuardianJson.map(it)) } ?: GuardianSnapshot()
    }

    fun saveSnapshot(s: GuardianSnapshot) = synchronized(lock) {
        writeAtomic(snapshotFile, GuardianJson.encode(s.toMap()))
    }

    fun loadMirror(): MirroredPolicy = synchronized(lock) {
        if (!mirrorFile.exists()) return@synchronized MirroredPolicy()
        runCatching { MirroredPolicy.fromJson(mirrorFile.readText(Charsets.UTF_8)) }.getOrNull() ?: MirroredPolicy()
    }

    fun saveMirrorJson(json: String) = synchronized(lock) {
        if (json.isBlank()) return@synchronized
        val parsed = runCatching { MirroredPolicy.fromJson(json) }.getOrNull() ?: return@synchronized
        writeAtomic(mirrorFile, GuardianJson.encode(
            linkedMapOf(
                "updatedAtWall" to parsed.updatedAtWall,
                "enginePhase" to parsed.enginePhase,
                "planActive" to parsed.planActive,
                "primaryKind" to parsed.primaryKind,
                "primaryWhy" to parsed.primaryWhy,
                "lockUntil" to parsed.lockUntil,
                "refuseUntil" to parsed.refuseUntil,
                "breakUntil" to parsed.breakUntil,
                "emergencyUntil" to parsed.emergencyUntil,
                "sessionUntil" to parsed.sessionUntil,
                "blocked" to parsed.blocked,
                "blockWhys" to parsed.blockWhys,
                "allow" to parsed.allow,
            ),
        ))
    }

    fun loadSettings(): GuardianSettings = synchronized(lock) {
        val m = readJson(settingsFile)?.let { GuardianJson.map(it) } ?: return@synchronized GuardianSettings()
        GuardianSettings(
            modalOnEngineDown = GuardianJson.bool(m, "modalOnEngineDown", true),
            lockOnEngineDown = GuardianJson.bool(m, "lockOnEngineDown", false),
            autoRebindA11y = GuardianJson.bool(m, "autoRebindA11y", true),
        )
    }

    fun saveSettings(s: GuardianSettings) = synchronized(lock) {
        writeAtomic(
            settingsFile,
            GuardianJson.encode(
                linkedMapOf(
                    "modalOnEngineDown" to s.modalOnEngineDown,
                    "lockOnEngineDown" to s.lockOnEngineDown,
                    "autoRebindA11y" to s.autoRebindA11y,
                ),
            ),
        )
    }

    fun loadPass(): EmergencyPassState = synchronized(lock) {
        val m = readJson(passFile)?.let { GuardianJson.map(it) } ?: return@synchronized EmergencyPassState()
        EmergencyPassState(
            dayKey = GuardianJson.str(m, "dayKey"),
            usedToday = GuardianJson.int(m, "usedToday"),
            activeUntilMono = GuardianJson.lng(m, "activeUntilMono"),
            reason = GuardianJson.str(m, "reason"),
        )
    }

    fun savePass(p: EmergencyPassState) = synchronized(lock) {
        writeAtomic(
            passFile,
            GuardianJson.encode(
                linkedMapOf(
                    "dayKey" to p.dayKey,
                    "usedToday" to p.usedToday,
                    "activeUntilMono" to p.activeUntilMono,
                    "reason" to p.reason,
                ),
            ),
        )
    }

    fun grantEmergencyPass(reason: String, nowWall: Long, nowMono: Long): EmergencyGrantResult = synchronized(lock) {
        val cur = loadPass()
        val res = EmergencyPassQuota.grant(cur, reason, nowWall, nowMono)
        if (res.ok) {
            savePass(res.state)
            enqueueIncident(
                OfflineIncident(
                    id = "pass-$nowWall-${res.state.usedToday}",
                    type = "guardian_emergency_pass",
                    atWall = nowWall,
                    detail = "大肥鱼哨兵独立急事通行 3 分钟（今日第 ${res.state.usedToday}/${EmergencyPassQuota.MAX_PER_DAY} 次）",
                    reason = res.state.reason,
                ),
            )
        }
        res
    }

    fun loadIncidents(): List<OfflineIncident> = synchronized(lock) {
        val arr = GuardianJson.list(readJson(incidentsFile))
        arr.mapNotNull { OfflineIncident.fromMap(GuardianJson.map(it)) }
    }

    fun enqueueIncident(item: OfflineIncident) = synchronized(lock) {
        val list = loadIncidents().toMutableList()
        if (list.any { it.id == item.id }) return@synchronized
        list.add(item)
        val capped = if (list.size > 200) list.takeLast(200) else list
        writeAtomic(incidentsFile, GuardianJson.encode(capped.map { it.toMap() }))
    }

    fun ackIncidents(ids: Set<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val remaining = loadIncidents().filterNot { it.id in ids }
        writeAtomic(incidentsFile, GuardianJson.encode(remaining.map { it.toMap() }))
    }

    fun applyAck(prev: GuardianSnapshot, ack: ProbeAck, receivedMono: Long, nowWall: Long): GuardianSnapshot {
        val startingSince = when {
            ack.enginePhase == "starting" || ack.enginePhase == "installing" ->
                if (prev.startingSinceMono > 0L) prev.startingSinceMono else receivedMono
            else -> 0L
        }
        return prev.copy(
            targetPkg = ack.targetPkg.ifBlank { prev.targetPkg },
            paired = true,
            a11yConnected = ack.a11yConnected,
            enginePhase = ack.enginePhase,
            dshServiceRunning = ack.dshServiceRunning,
            proactiveTransport = ack.proactiveTransport,
            lastAckAtMono = receivedMono,
            lastAckAtWall = nowWall,
            lastProbeAtMono = receivedMono,
            consecutiveFailures = 0,
            startingSinceMono = startingSince,
            lastFailureReason = "",
        )
    }

    private fun readJson(f: File): Any? {
        if (!f.exists()) return null
        return runCatching { GuardianJson.parse(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun writeAtomic(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, "${target.name}.tmp")
        runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { tmp.delete() }
    }
}
