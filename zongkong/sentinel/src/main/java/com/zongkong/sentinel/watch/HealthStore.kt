package com.zongkong.sentinel.watch

import com.zongkong.sentinel.health.HealthConfig
import com.zongkong.sentinel.health.HealthObservation
import com.zongkong.sentinel.health.HealthStats
import com.zongkong.sentinel.health.HealthStatus
import com.zongkong.sentinel.health.LockState
import java.io.File
import java.io.FileOutputStream

/**
 * `:watch` 的健康记录（报告 §6/§10，规范 §2.1/§7）。
 *
 * 三件事在这里做死：
 *  1. **每个客户端独立记录**：instanceId、最近真实 ack、连续失败、恢复预算、事件进度各记各的。
 *     一个客户端不返回只会把它自己那一行变成「失联」，不会传染别人（T04）。
 *  2. **每个客户端独立超时与有限在途**：`beginProbe` 在途已满就拒绝再发，绝不为卡死的调用新建线程。
 *  3. **落盘**：`health.json` 原子写，重启后统计与状态不丢，也不用默认 SharedPreferences 做跨进程缓存。
 *
 * 纯 Kotlin（只有 java.io + 共享内核），可以在 JVM 单测里推进时间验证。
 */
class HealthStore(private val dir: File?) {

    /** 一个客户端的全部健康事实，逐字段对应报告 §6 的表格。 */
    data class ClientRecord(
        val pkg: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        /** 本次无障碍连接实例；重建必须换新。 */
        val instanceId: String? = null,
        /** 自报的证书摘要（哨兵另做校验，这里只留档）。 */
        val certSha256: String = "",
        /** 是否在 / 是否接入。 */
        val installed: Boolean = false,
        val registered: Boolean = false,
        /** 系统设置里这个客户端的无障碍服务是否启用（由 :watch 读系统设置判断）。 */
        val permissionEnabled: Boolean = false,
        /** 最近一次真实 dispatcherAck 的序号与哨兵收到它的时刻（单调）。 */
        val lastAckSeq: Long = 0,
        val lastAckAtMono: Long = 0,
        /** 连续失败次数与最近原因。 */
        val consecutiveFailures: Int = 0,
        val lastFailureReason: String = "",
        /** 恢复相关。 */
        val recoveringSinceMono: Long = 0,
        val recoveriesTried: Int = 0,
        val lastRecoveryNote: String = "",
        /** 暂停（用户/规则）。 */
        val paused: Boolean = false,
        /** 客户端自报的规则版本与最近回执。 */
        val ruleRevision: Int = 0,
        val lastReceiptState: String = "",
        val lastReceiptAtMono: Long = 0,
        /** 事件通道进度（客户端侧的窗口事件序号与时刻）。 */
        val eventSeq: Long = 0,
        val eventAtMono: Long = 0,
        /** 在途探测数（0/1，上限固定为 1）。 */
        val inFlight: Int = 0,
        /** 探测发出的序号（单调递增，跨实例不重置，用于拒绝重复/倒序）。 */
        val probeSeq: Long = 0,
        /** 最近一次探测发出的时刻（判断本次是否超预算）。 */
        val probeStartedAtMono: Long = 0,
        /** 探测线程是否仍卡在调用里（在途未回收）。 */
        val wedged: Boolean = false,
        val registeredAtWall: Long = 0,
        /** 事件通道结论。 */
        val eventChannel: String = EVENT_PENDING,
        val stats: HealthStats = HealthStats(),
    ) {
        /** 桥接到共享内核的观察结构。评测全交给 `HealthEvaluator`，这里不自己发明判断。 */
        fun observation(): HealthObservation = HealthObservation(
            installed = installed || registered,
            permissionEnabled = permissionEnabled,
            registered = registered,
            instanceId = instanceId,
            lastAckSeq = lastAckSeq,
            lastAckAtMono = lastAckAtMono,
            consecutiveFailures = consecutiveFailures,
            recoveringSinceMono = recoveringSinceMono,
            recoveriesTried = recoveriesTried,
            paused = paused,
            lastFailureReason = lastFailureReason,
        )
    }

    /** 通知/统计用的快照。 */
    data class Snapshot(
        val clients: Map<String, ClientRecord> = emptyMap(),
        val locks: LockLog = LockLog(),
        val emergency: Emergency.Record? = null,
        val updatedAtWall: Long = 0,
    )

    /** 锁屏尝试的计数（报告 §10 统计：尝试 / 确认成功 / 未验证）。 */
    data class LockLog(
        val attempts: Int = 0,
        val confirmed: Int = 0,
        val unverified: Int = 0,
        val failed: Int = 0,
        val lastAtWall: Long = 0,
        val lastState: String = "",
        val lastPolicyId: String = "",
        val lastIncidentId: String = "",
        val lastNote: String = "",
    )

    private var clients = LinkedHashMap<String, ClientRecord>()
    private var locks = LockLog()
    private var emergency: Emergency.Record? = null
    private var updatedAtWall = 0L
    private var loaded = false

    private val file: File? = dir?.let { File(it, FILE_NAME) }
    private val tmp: File? = dir?.let { File(it, "$FILE_NAME.tmp") }

    @Synchronized
    fun snapshot(nowWall: Long = 0): Snapshot {
        ensureLoaded(nowWall)
        return Snapshot(LinkedHashMap(clients), locks, emergency, updatedAtWall)
    }

    @Synchronized
    fun get(pkg: String, nowWall: Long = 0): ClientRecord {
        ensureLoaded(nowWall)
        return clients[pkg] ?: ClientRecord(pkg = pkg)
    }

    // ---------- 注册 ----------

    data class RegisterResult(val ok: Boolean, val serverVersion: Int = PROTOCOL_VERSION, val error: String? = null)

    @Synchronized
    fun register(
        pkg: String,
        versionName: String,
        versionCode: Long,
        instanceId: String,
        certSha256: String,
        ruleRevision: Int = 0,
        nowWall: Long,
        nowMono: Long,
    ): RegisterResult {
        ensureLoaded(nowWall)
        if (instanceId.isBlank()) return RegisterResult(false, error = "instanceId 不能为空")
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        val sameInstance = old.instanceId == instanceId
        val rec = old.copy(
            pkg = pkg,
            versionName = versionName,
            versionCode = versionCode,
            certSha256 = certSha256,
            installed = true,
            registered = true,
            // register 只能由「无障碍服务刚连上」的客户端发起，所以此刻它的无障碍一定是开的。
            // 之后开/关一律以 :watch 从系统设置读到的为准（observeSystem），不采信自报（报告 §6）。
            permissionEnabled = true,
            instanceId = instanceId,
            registeredAtWall = if (sameInstance) old.registeredAtWall else nowWall,
            ruleRevision = maxOf(ruleRevision, old.ruleRevision),
            paused = false,
            // 新实例：清掉「在途卡死」标记，但 ack 与失败计数保留，
            // 只有拿到新实例的真实 ack 才能变绿（T11）。
            inFlight = if (sameInstance) old.inFlight else 0,
            wedged = if (sameInstance) old.wedged else false,
            probeStartedAtMono = if (sameInstance) old.probeStartedAtMono else 0,
        )
        clients[pkg] = rec
        dirty(nowWall)
        return RegisterResult(true)
    }

    @Synchronized
    fun markUnregistered(pkg: String, nowWall: Long = 0) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: return
        clients[pkg] = old.copy(registered = false)
        dirty(nowWall)
    }

    /** 由 :watch 从系统设置读到的事实（安装、无障碍开关、事件进度），不采信客户端自报。 */
    @Synchronized
    fun observeSystem(pkg: String, installed: Boolean, permissionEnabled: Boolean, nowWall: Long = 0) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        if (old.installed == installed && old.permissionEnabled == permissionEnabled) return
        clients[pkg] = old.copy(installed = installed, permissionEnabled = permissionEnabled)
        dirty(nowWall)
    }

    // ---------- 探测（有限在途） ----------

    /** 在途已满返回 0（本轮不发），否则返回本次探测序号。 */
    @Synchronized
    fun beginProbe(pkg: String, nowWall: Long, nowMono: Long): Long {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        if (!old.registered) return 0
        if (old.inFlight >= MAX_IN_FLIGHT) return 0
        val seq = old.probeSeq + 1
        clients[pkg] = old.copy(
            probeSeq = seq,
            inFlight = old.inFlight + 1,
            probeStartedAtMono = nowMono,
            wedged = false,
        )
        dirty(nowWall)
        return seq
    }

    /** 探测线程真正回来了（无论成败），回收在途。 */
    @Synchronized
    fun endProbe(pkg: String, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: return
        clients[pkg] = old.copy(inFlight = (old.inFlight - 1).coerceAtLeast(0), wedged = false)
        dirty(nowWall)
    }

    /**
     * 探测超预算：线程可能永远卡着（T04：不新建线程、不阻塞别人）。
     *
     * 这里**释放这个在途槽位**，让下一轮还能发一次探测——不然一个卡死的线程会把这个客户端
     * 永久钉死在「失联」上、连恢复的机会都没有。真正防线程泄漏的是 [ClientProbe] 的
     * 「每客户端最多 2 条线程」上限：第 3 次派发会直接失败，不会无限新建。
     */
    @Synchronized
    fun markProbeWedged(pkg: String, reason: String, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: return
        clients[pkg] = old.copy(
            wedged = true,
            inFlight = 0,
            consecutiveFailures = old.consecutiveFailures + 1,
            lastFailureReason = reason,
        )
        dirty(nowWall)
    }

    // ---------- ack ----------

    @Synchronized
    fun onAckAccepted(pkg: String, rec: ClientRecordData, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        clients[pkg] = old.copy(
            instanceId = rec.instanceId,
            lastAckSeq = rec.seq,
            lastAckAtMono = rec.receivedAtMono,
            consecutiveFailures = 0,
            lastFailureReason = "",
            recoveringSinceMono = 0,
            inFlight = (old.inFlight - 1).coerceAtLeast(0),
            wedged = false,
            ruleRevision = rec.ruleRevision,
            lastReceiptState = rec.lastReceiptState,
            lastReceiptAtMono = rec.lastReceiptAtMono,
            eventSeq = rec.eventSeq,
            eventAtMono = rec.eventAtMono,
        )
        dirty(nowWall)
    }

    /** ack 被拒（旧实例 / 重复序号 / 过期 / 超时）。拒绝也是失败证据，不能当健康。 */
    @Synchronized
    fun onAckRejected(pkg: String, reason: String, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        clients[pkg] = old.copy(
            consecutiveFailures = old.consecutiveFailures + 1,
            lastFailureReason = reason,
            inFlight = (old.inFlight - 1).coerceAtLeast(0),
            wedged = false,
        )
        dirty(nowWall)
    }

    /** 客户端自己上报的执行回执（`reportReceipt`）：只记录，不据此判断健康。 */
    @Synchronized
    fun onReceipt(pkg: String, state: String, atWall: Long, note: String, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        clients[pkg] = old.copy(
            lastReceiptState = if (note.isBlank()) state else "$state（$note）",
            lastReceiptAtMono = old.lastReceiptAtMono, // 时刻以 ack 携带的单调时钟为准
        )
        dirty(nowWall)
    }

    @Synchronized
    fun onRecoveryAttempt(pkg: String, note: String, nowMono: Long, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        clients[pkg] = old.copy(
            recoveringSinceMono = if (old.recoveringSinceMono > 0) old.recoveringSinceMono else nowMono,
            recoveriesTried = old.recoveriesTried + 1,
            lastRecoveryNote = note,
        )
        dirty(nowWall)
    }

    @Synchronized
    fun setPaused(pkg: String, paused: Boolean, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        clients[pkg] = old.copy(paused = paused)
        dirty(nowWall)
    }

    @Synchronized
    fun setEventChannel(pkg: String, channel: String, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        if (old.eventChannel == channel) return
        clients[pkg] = old.copy(eventChannel = channel)
        dirty(nowWall)
    }

    /** 统计累加（有效监测时长、失效时长、发现延迟）。由调度器每个 tick 调用。 */
    @Synchronized
    fun addStats(pkg: String, deltaMonitoredMs: Long, deltaOutageMs: Long, detectLatencyMs: Long, nowWall: Long) {
        ensureLoaded(nowWall)
        val old = clients[pkg] ?: ClientRecord(pkg = pkg)
        val s = old.stats
        clients[pkg] = old.copy(
            stats = s.copy(
                monitoredMs = s.monitoredMs + deltaMonitoredMs,
                outageTotalMs = s.outageTotalMs + deltaOutageMs,
                detectLatencyTotalMs = s.detectLatencyTotalMs + if (detectLatencyMs > 0) detectLatencyMs else 0,
                detectLatencySamples = s.detectLatencySamples + if (detectLatencyMs > 0) 1 else 0,
            ),
        )
        // 统计每秒都在变，别每个 tick 都落盘：由 WatchService 定时 flush。
    }

    @Synchronized
    fun countOutage(pkg: String) {
        val old = clients[pkg] ?: return
        clients[pkg] = old.copy(stats = old.stats.copy(outageCount = old.stats.outageCount + 1))
    }

    @Synchronized
    fun countRecovery(pkg: String, success: Boolean) {
        val old = clients[pkg] ?: return
        clients[pkg] = old.copy(
            stats = old.stats.copy(
                recoveryAttempts = old.stats.recoveryAttempts + 1,
                recoverySuccesses = old.stats.recoverySuccesses + if (success) 1 else 0,
            ),
        )
    }

    // ---------- 锁屏记录 ----------

    @Synchronized
    fun recordLock(state: LockState, policyId: String, incidentId: String, note: String, nowWall: Long) {
        ensureLoaded(nowWall)
        locks = locks.copy(
            attempts = locks.attempts + 1,
            confirmed = locks.confirmed + if (state == LockState.OBSERVED_LOCKED) 1 else 0,
            unverified = locks.unverified + if (state == LockState.UNVERIFIED || state == LockState.API_CALLED || state == LockState.REQUESTED) 1 else 0,
            failed = locks.failed + if (state == LockState.FAILED) 1 else 0,
            lastAtWall = nowWall,
            lastState = state.name,
            lastPolicyId = policyId,
            lastIncidentId = incidentId,
            lastNote = note,
        )
        dirty(nowWall)
    }

    @Synchronized
    fun setEmergency(rec: Emergency.Record?, nowWall: Long) {
        ensureLoaded(nowWall)
        emergency = rec
        dirty(nowWall)
    }

    /** ack 里带回的字段（避免 HealthStore 依赖 Android Bundle）。 */
    data class ClientRecordData(
        val instanceId: String,
        val seq: Long,
        val receivedAtMono: Long,
        val ruleRevision: Int,
        val lastReceiptState: String,
        val lastReceiptAtMono: Long,
        val eventSeq: Long,
        val eventAtMono: Long,
    )

    // ---------- 落盘 ----------

    private var lastSaveWall = 0L

    private fun dirty(nowWall: Long) {
        updatedAtWall = nowWall
        // 每秒都在改（统计），落盘按 5 秒节流；关键状态用 flush() 立即写。
        if (nowWall - lastSaveWall < SAVE_THROTTLE_MS) return
        lastSaveWall = nowWall
        save()
    }

    @Synchronized
    fun flush(nowWall: Long = updatedAtWall) {
        ensureLoaded(nowWall)
        save()
        lastSaveWall = nowWall
    }

    private fun save() {
        val f = file ?: return
        val t = tmp ?: return
        try {
            f.parentFile?.let { if (!it.exists()) it.mkdirs() }
            val text = encode(Snapshot(clients, locks, emergency, updatedAtWall))
            FileOutputStream(t).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!t.renameTo(f)) {
                f.writeText(text)
                t.delete()
            }
        } catch (_: Throwable) {
            try { t.delete() } catch (_: Throwable) {}
        }
    }

    private fun ensureLoaded(nowWall: Long) {
        if (loaded || dir == null) { loaded = true; return }
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        try {
            val snap = decode(f.readText())
            clients = LinkedHashMap(snap.clients)
            locks = snap.locks
            emergency = snap.emergency
            updatedAtWall = snap.updatedAtWall
        } catch (_: Throwable) {
            // 文件坏了：从空开始，但不删除，界面会显示「记录读取失败」。
        }
        lastSaveWall = nowWall
    }

    companion object {
        const val FILE_NAME = "health.json"
        const val PROTOCOL_VERSION = 2
        /** 每个客户端最多 1 个在途探测：不为卡死的调用新建线程（T04）。 */
        const val MAX_IN_FLIGHT = 1
        private const val SAVE_THROTTLE_MS = 5_000L

        const val EVENT_VERIFIED = "已验证"
        const val EVENT_PENDING = "待验证"
        const val EVENT_ABNORMAL = "异常"

        val ZONGKONG = "com.zongkong.app"
        val YISHOU = "com.yishou.app"
        val PET = "cn.companion.pet"
        val SELF = "com.zongkong.sentinel"

        /** 三个业务客户端（真正要发挑战、要有本地执行器的）。 */
        val CLIENTS = listOf(ZONGKONG, YISHOU, PET)

        /** 三业务客户端 + 哨兵自己。统计页固定四行（报告 §10）。 */
        val ROWS = CLIENTS + SELF

        fun encode(s: Snapshot): String = MiniJson.encode(
            MiniJson.obj(
                "schemaVersion" to 2,
                "updatedAtWall" to s.updatedAtWall,
                "clients" to s.clients.map { (_, r) -> encodeRecord(r) },
                "locks" to MiniJson.obj(
                    "attempts" to s.locks.attempts,
                    "confirmed" to s.locks.confirmed,
                    "unverified" to s.locks.unverified,
                    "failed" to s.locks.failed,
                    "lastAtWall" to s.locks.lastAtWall,
                    "lastState" to s.locks.lastState,
                    "lastPolicyId" to s.locks.lastPolicyId,
                    "lastIncidentId" to s.locks.lastIncidentId,
                    "lastNote" to s.locks.lastNote,
                ),
                "emergency" to s.emergency?.let {
                    MiniJson.obj(
                        "atWall" to it.atWall,
                        "atMono" to it.atMono,
                        "reason" to it.reason,
                        "policyId" to it.policyId,
                        "propagated" to it.confirmed.map { (k, v) -> MiniJson.obj("pkg" to k, "ok" to v) },
                    )
                },
            ),
        )

        private fun encodeRecord(r: ClientRecord): Map<String, Any?> = MiniJson.obj(
            "pkg" to r.pkg,
            "versionName" to r.versionName,
            "versionCode" to r.versionCode,
            "instanceId" to r.instanceId,
            "certSha256" to r.certSha256,
            "installed" to r.installed,
            "registered" to r.registered,
            "permissionEnabled" to r.permissionEnabled,
            "lastAckSeq" to r.lastAckSeq,
            "lastAckAtMono" to r.lastAckAtMono,
            "consecutiveFailures" to r.consecutiveFailures,
            "lastFailureReason" to r.lastFailureReason,
            "recoveringSinceMono" to r.recoveringSinceMono,
            "recoveriesTried" to r.recoveriesTried,
            "lastRecoveryNote" to r.lastRecoveryNote,
            "paused" to r.paused,
            "ruleRevision" to r.ruleRevision,
            "lastReceiptState" to r.lastReceiptState,
            "lastReceiptAtMono" to r.lastReceiptAtMono,
            "eventSeq" to r.eventSeq,
            "eventAtMono" to r.eventAtMono,
            "probeSeq" to r.probeSeq,
            "registeredAtWall" to r.registeredAtWall,
            "eventChannel" to r.eventChannel,
            "stats" to MiniJson.obj(
                "monitoredMs" to r.stats.monitoredMs,
                "outageCount" to r.stats.outageCount,
                "outageTotalMs" to r.stats.outageTotalMs,
                "recoveryAttempts" to r.stats.recoveryAttempts,
                "recoverySuccesses" to r.stats.recoverySuccesses,
                "lockAttempts" to r.stats.lockAttempts,
                "lockConfirmed" to r.stats.lockConfirmed,
                "lockUnverified" to r.stats.lockUnverified,
                "detectLatencyTotalMs" to r.stats.detectLatencyTotalMs,
                "detectLatencySamples" to r.stats.detectLatencySamples,
            ),
        )

        fun decode(text: String): Snapshot {
            val root = MiniJson.map(MiniJson.parse(text))
            val cs = LinkedHashMap<String, ClientRecord>()
            for (item in MiniJson.list(root["clients"])) {
                val m = MiniJson.map(item)
                val rec = decodeRecord(m)
                if (rec.pkg.isNotBlank()) cs[rec.pkg] = rec
            }
            val lm = MiniJson.map(root["locks"])
            val em = (root["emergency"] as? Map<*, *>)?.let {
                val mm = MiniJson.map(it)
                val conf = LinkedHashMap<String, Boolean>()
                for (p in MiniJson.list(mm["propagated"])) {
                    val pm = MiniJson.map(p)
                    conf[MiniJson.str(pm, "pkg")] = MiniJson.bool(pm, "ok")
                }
                Emergency.Record(
                    atWall = MiniJson.lng(mm, "atWall"),
                    atMono = MiniJson.lng(mm, "atMono"),
                    reason = MiniJson.str(mm, "reason"),
                    policyId = MiniJson.nullableStr(mm, "policyId"),
                    confirmed = conf,
                )
            }
            return Snapshot(
                clients = cs,
                locks = LockLog(
                    attempts = MiniJson.int(lm, "attempts"),
                    confirmed = MiniJson.int(lm, "confirmed"),
                    unverified = MiniJson.int(lm, "unverified"),
                    failed = MiniJson.int(lm, "failed"),
                    lastAtWall = MiniJson.lng(lm, "lastAtWall"),
                    lastState = MiniJson.str(lm, "lastState"),
                    lastPolicyId = MiniJson.str(lm, "lastPolicyId"),
                    lastIncidentId = MiniJson.str(lm, "lastIncidentId"),
                    lastNote = MiniJson.str(lm, "lastNote"),
                ),
                emergency = em,
                updatedAtWall = MiniJson.lng(root, "updatedAtWall"),
            )
        }

        private fun decodeRecord(m: Map<String, Any?>): ClientRecord {
            val s = MiniJson.map(m["stats"])
            return ClientRecord(
                pkg = MiniJson.str(m, "pkg"),
                versionName = MiniJson.str(m, "versionName"),
                versionCode = MiniJson.lng(m, "versionCode"),
                instanceId = MiniJson.nullableStr(m, "instanceId"),
                certSha256 = MiniJson.str(m, "certSha256"),
                installed = MiniJson.bool(m, "installed"),
                registered = MiniJson.bool(m, "registered"),
                permissionEnabled = MiniJson.bool(m, "permissionEnabled"),
                lastAckSeq = MiniJson.lng(m, "lastAckSeq"),
                lastAckAtMono = MiniJson.lng(m, "lastAckAtMono"),
                consecutiveFailures = MiniJson.int(m, "consecutiveFailures"),
                lastFailureReason = MiniJson.str(m, "lastFailureReason"),
                recoveringSinceMono = MiniJson.lng(m, "recoveringSinceMono"),
                recoveriesTried = MiniJson.int(m, "recoveriesTried"),
                lastRecoveryNote = MiniJson.str(m, "lastRecoveryNote"),
                paused = MiniJson.bool(m, "paused"),
                ruleRevision = MiniJson.int(m, "ruleRevision"),
                lastReceiptState = MiniJson.str(m, "lastReceiptState"),
                lastReceiptAtMono = MiniJson.lng(m, "lastReceiptAtMono"),
                eventSeq = MiniJson.lng(m, "eventSeq"),
                eventAtMono = MiniJson.lng(m, "eventAtMono"),
                probeSeq = MiniJson.lng(m, "probeSeq"),
                registeredAtWall = MiniJson.lng(m, "registeredAtWall"),
                eventChannel = MiniJson.str(m, "eventChannel", EVENT_PENDING),
                stats = HealthStats(
                    monitoredMs = MiniJson.lng(s, "monitoredMs"),
                    outageCount = MiniJson.int(s, "outageCount"),
                    outageTotalMs = MiniJson.lng(s, "outageTotalMs"),
                    recoveryAttempts = MiniJson.int(s, "recoveryAttempts"),
                    recoverySuccesses = MiniJson.int(s, "recoverySuccesses"),
                    lockAttempts = MiniJson.int(s, "lockAttempts"),
                    lockConfirmed = MiniJson.int(s, "lockConfirmed"),
                    lockUnverified = MiniJson.int(s, "lockUnverified"),
                    detectLatencyTotalMs = MiniJson.lng(s, "detectLatencyTotalMs"),
                    detectLatencySamples = MiniJson.int(s, "detectLatencySamples"),
                ),
            )
        }

        /** 界面口径：状态到文案（规范 §7「宁可显示未知，不要涂绿」）。 */
        fun label(status: HealthStatus, nowMono: Long, lastAckAtMono: Long): String = when (status) {
            HealthStatus.HEALTHY -> "真实响应中"
            HealthStatus.UNREGISTERED -> "未接入"
            HealthStatus.UNKNOWN -> "未知（还没有真实响应）"
            HealthStatus.SUSPECT -> "可疑：最近响应已过期"
            HealthStatus.DISABLED -> "无障碍未开启"
            HealthStatus.UNRESPONSIVE -> "确认失联"
            HealthStatus.RECOVERING -> "正在恢复"
            HealthStatus.PAUSED -> "已暂停"
        }.let { base ->
            if (lastAckAtMono <= 0) base else "$base（${((nowMono - lastAckAtMono).coerceAtLeast(0)) / 1000}s 前）"
        }
    }
}
