package cn.companion.pet.health

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import cn.companion.pet.a11y.PerceptionHub
import java.io.File
import java.security.MessageDigest

/**
 * 同桌侧「监管运行器」：把已有能力接成 v2，而不新造专注/拦截能力。
 *
 * 复用关系（报告 §3.5 要求）：
 *  - **专注/拦截/拉回**仍然只有 `core/focus/FocusGuard` + `FocusController` + `ForegroundGate` 那一套。
 *    这里不新增任何拦截或计时状态机，只在它们之上接“健康事实”与“锁屏出口”。
 *  - **感知执行线程**仍然是 `PerceptionService`；健康挑战由 [PetHealthProvider] 投到它的 Handler。
 *  - 计时、健康判断、去重、锁屏次数全部走共享内核（[HealthEvaluator]/[LockArbiter]/[LockClassifier]）。
 *
 * 边界（报告 §3.5，必须守住）：
 *  - 健康协议只统计“运行与执行”；专注计时结束**不等于**思考任务通过。
 *  - 收到一句回应**不**自动解除认知任务。
 *  - 不读、不改任何目标/任务状态（[cn.companion.pet.core.goal.GoalStore] 完全不参与）。
 */
class PetGuardRuntime(private val context: Context, root: File) {

    private val mono: Mono = Mono { SystemClock.elapsedRealtime() }
    private val wall: Wall = Wall { System.currentTimeMillis() }

    val policyStore = PetPolicyStore(root, wall, mono)
    val receiptStore = PetReceiptStore(root)
    val emergencyStore = EmergencyPauseStore(root)
    val callerPinStore = CallerPinStore(root)
    val link = SentinelLink(context)
    val lockGateway = AndroidLockGateway(context)
    val executor = LocalLockExecutor(lockGateway)
    val planner = LocalLockPlanner()

    /** 最近一次本地兜底评估的结论，诊断页显示。 */
    @Volatile
    var lastPlanReason: String = ""
        private set

    @Volatile
    var lastAttempt: LockAttempt? = null
        private set

    /** 修复宽限截止（单调）：锁屏并解锁后给用户 120s 修权限，不立刻重锁（T15）。 */
    @Volatile
    private var repairGraceUntilMono = 0L

    /** 本应用自己是否“健康”由真实执行线程的 ack 判定，这里只在本地兜底时用它把关。 */
    @Volatile
    var selfHealthy: Boolean = false

    /** 最近一次自检的真实结果（诊断页用）。 */
    @Volatile
    var lastSelfProbe: String = "还没有自检"
        private set

    @Volatile
    private var lastSelfProbeAtMono = 0L

    @Volatile
    private var selfProbeRunning = false

    /** 自检用的单线程：主线程上 await 会死锁，所以固定放到这条后台线程上等。 */
    private val selfProbeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "pet-self-probe").apply { isDaemon = true }
    }

    /**
     * 哨兵是否确认失联。**只在确认过的情况下**才允许本地兜底：
     * 从没接通过（哨兵没装）不算失联，那属于“未接入”，不能让客户端抢着锁屏。
     */
    @Volatile
    var sentinelUnresponsive: Boolean = false

    // ---------- 能力/权限状态（界面必须如实分开显示） ----------

    fun capability(): LockCapability {
        val admin = runCatching { lockGateway.isAdminActive() }.getOrDefault(false)
        val a11y = PerceptionHub.isConnected()
        val secure = runCatching { lockGateway.keyguardFacts()?.secureLockSet }.getOrNull()
        return lockCapability(admin, a11y, secure)
    }

    fun lockCapabilitySummary(): String = capability().summary

    /** 当前生效的本地规则（界面用）。 */
    fun effectivePolicies(): List<Policy> = policyStore.effective()

    // ---------- 规则 ----------

    /** 用户设置的一个监管窗口。issuer 固定 user（本应用只能改自己的规则，规范 §5.3）。 */
    fun saveUserWindow(
        policyId: String = "pet-window",
        startWall: Long,
        endWall: Long,
        lockAtWall: Long = 0,
        enabled: Boolean,
        failureAction: String = "LOCK",
        requiredApps: List<String> = listOf(context.packageName),
    ): Result<Int> = policyStore.upsert(
        PolicyDto(
            policyId = policyId,
            revision = 0,
            issuer = ISSUER_USER,
            requiredApps = requiredApps,
            startsAtWall = startWall,
            endsAtWall = endWall,
            lockAtWall = lockAtWall,
            failureAction = failureAction,
            repairGraceMs = HealthConfig().repairGraceMs,
            emergencyState = if (emergencyStore.current().stopped) "STOPPED" else "NONE",
            enabled = enabled,
        ),
    )

    /** 同步给哨兵：先落盘再同步，只有拿到 acceptedRevision 才算「已就绪」。 */
    fun syncToSentinel() {
        val policies = policyStore.policies()
        if (policies.isEmpty()) return
        link.syncPolicy(encodePolicies(policies))
    }

    /** 注册到哨兵。instanceId 必须是当前无障碍实例；哨兵没装/没在岗时只是记下来，不影响本地规则。 */
    fun registerToSentinel() {
        val instance = PerceptionHub.instanceId ?: return
        val info = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = info?.versionName ?: ""
        val versionCode = if (info != null) {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        } else 0L
        link.register(instance, versionName, versionCode, ownCertDigest())
    }

    private fun ownCertDigest(): String = runCatching {
        val pm = context.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val certs = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty()
        else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty()
        }
        val md = MessageDigest.getInstance("SHA-256")
        val computed = certs.joinToString("|") { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        computed.ifBlank { PetCert.SELF_SHA256 }
    }.getOrDefault(PetCert.SELF_SHA256)

    // ---------- 自适应自检 ----------

    /**
     * 自检：用与哨兵**完全相同**的挑战通路向自己的执行线程发一次挑战。
     *
     * 这是“自己是否健康”的唯一凭据：不看 `PetService.running`、不看 `PerceptionHub.connected`。
     * 必须在后台线程调用（内部会 await，主线程会死锁），所以固定丢到 selfProbeExecutor。
     */
    fun requestSelfProbe(budgetMs: Long = 2_000) {
        if (selfProbeRunning) return
        selfProbeRunning = true
        val dispatcher = ChallengeDispatcher(
            clientPkg = context.packageName,
            mono = mono,
            executorConnected = { PerceptionHub.isConnected() },
            currentInstanceId = { PerceptionHub.instanceId },
            postToExecutor = { task -> PerceptionHub.healthExecutorHandler?.post(task) ?: false },
            readFactsOnExecutor = { executorFacts() },
        )
        runCatching {
            selfProbeExecutor.execute {
                val r = runCatching { dispatcher.handle(java.util.UUID.randomUUID().toString(), 1L, budgetMs) }
                    .getOrElse { ChallengeResult(false, it.javaClass.simpleName) }
                selfHealthy = r.ok
                lastSelfProbe = if (r.ok) "执行线程在响应" else ("执行线程没有回应：" + (r.error ?: "未知"))
                lastSelfProbeAtMono = mono.now()
                selfProbeRunning = false
            }.also { selfProbeRunning = false }
        }.onFailure { selfProbeRunning = false }
    }

    /** 供给挑战通路的只读事实（不碰任何屏幕内容）。 */
    fun executorFacts(): ExecutorFacts {
        val (seqNo, atMono) = PerceptionHub.eventProgress()
        val receipt = receiptStore.load()
        return ExecutorFacts(
            ruleRevision = policyStore.revision(),
            lastReceiptState = receipt?.state.orEmpty(),
            lastReceiptAtMono = receipt?.atMono ?: 0,
            eventSeq = seqNo,
            eventAtMono = atMono,
        )
    }

    // ---------- 锁屏出口 ----------

    /**
     * 周期性检查：规则到期（lockAtWall）或哨兵失联且自己健康时，按本地规则有限兜底。
     * 由 PetBrain 的每秒 tick 调用（主线程，只做判断 + 一次设备调用）。
     */
    fun tick() {
        val nowWall = policyStore.nowWall()
        val nowMono = mono.now()
        // 每轮先按真实调用记录更新“哨兵是否确认失联”，不靠自报的布尔。
        sentinelUnresponsive = link.sentinelUnresponsive(nowMono)
        // 硬门槛：暂停是三条来源的并集，跨窗口翻页与进程重启都成立。
        // 这一句必须在任何锁屏判定之前，且不允许被下面的分支绕过。
        val paused = isPaused()
        val policies = policyStore.effective(nowWall)
        if (policies.isEmpty()) {
            lastPlanReason = "没有生效中的本地规则"
            return
        }
        if (paused) {
            // 暂停期间连「评估」都不做：不碰设备、不动仲裁器。
            lastPlanReason = "紧急暂停中：" + EmergencyGate.describe(pauseSources())
            return
        }
        for (p in policies) {
            if (p.failureAction == "NOTIFY_ONLY") {
                // 只提醒不锁：仍然记录，但不能悄悄锁屏。
                lastPlanReason = "规则 ${p.policyId} 的 failureAction=NOTIFY_ONLY，不执行锁屏"
                continue
            }
            val due = p.lockDue(nowWall)
            val fallback = sentinelUnresponsive && selfHealthy
            if (!due && !fallback) {
                lastPlanReason = if (!sentinelUnresponsive) "哨兵在岗，全局锁屏交给哨兵"
                else "哨兵失联，但自己也不健康：不做本地兜底"
                continue
            }
            val incidentId = if (due) "lock-at:${p.policyId}:${p.lockAtWall}" else "sentinel-down:${p.policyId}"
            val reason = if (due) "规则到期锁屏" else "哨兵失联，按本地规则有限兜底"
            val decision = planner.plan(
                LocalLockContext(
                    policyEffective = true,
                    // 暂停状态已经在上面拦掉了；这里再传一次，防止将来有人调整顺序时漏掉。
                    emergencyBlocked = pauseSources().let { EmergencyGate.isPaused(it) },
                    repairGraceUntilMono = repairGraceUntilMono,
                    sentinelUnresponsive = sentinelUnresponsive,
                    selfHealthy = if (due) true else selfHealthy,
                ),
                p, incidentId, nowMono, reason,
            )
            lastPlanReason = decision.reason
            if (!decision.fire) continue
            val req = decision.request ?: continue
            val attempt = executor.execute(req, nowWall, nowMono)
            lastAttempt = attempt
            planner.record(req, attempt.state, nowMono)
            receiptStore.save(
                ReceiptDto(
                    policyId = req.policyId,
                    incidentId = req.incidentId,
                    requestId = req.requestId,
                    source = req.source,
                    reason = req.reason,
                    state = attempt.state.name,
                    atWall = attempt.atWall,
                    atMono = attempt.atMono,
                    note = attempt.note,
                ),
            )
            link.reportReceipt(receiptStore.last ?: ReceiptDto())
            // 锁屏并解锁后给 120s 修复宽限（T15）。
            // C-3：只有 OBSERVED_LOCKED 才给宽限。API_CALLED 是「调用了但还没观测」，
            // 而且 LocalLockExecutor（走 LockClassifier）实际只会产 FAILED / OBSERVED_LOCKED /
            // UNVERIFIED —— 原来这里 || API_CALLED 是不可达分支，留着像在「把调用返回当成功」，
            // 报告 §8 明令禁止，所以删掉。
            if (attempt.state == LockState.OBSERVED_LOCKED) {
                repairGraceUntilMono = nowMono + p.repairGraceMs
            }
            Log.i(TAG, "local lock attempt state=${attempt.state} note=${attempt.note}")
        }
    }

    /** 解锁后重新给修复宽限：不立刻再锁。 */
    fun onUserUnlocked() {
        val p = effectivePolicies().firstOrNull() ?: return
        repairGraceUntilMono = mono.now() + p.repairGraceMs
    }

    // ---------- 紧急暂停（本地、立即、不联网） ----------

    /**
     * 三条来源的原始事实。**tick() 和界面都走这一处**，不准各写一份（会漂移）。
     *
     * 规则用的是 `policyStore.policies()`（**全部**规则，不只是生效中的），
     * 因为被标 STOPPED 的规则可能已经不在当前窗口里，但它的 STOPPED 仍然是暂停证据。
     */
    fun pauseSources(): PauseSources = PauseSources(
        memoryPaused = emergencyStore.current().stopped,
        policyStopped = EmergencyGate.anyPolicyStopped(policyStore.policies().map { it.toDto() }),
        markerStopped = emergencyStore.readFromDisk()?.stopped == true,
    )

    /** 现在是否处于紧急暂停（三条来源任一成立）。 */
    fun isPaused(): Boolean = EmergencyGate.isPaused(pauseSources())

    /**
     * 立即停止本地自动锁屏：规则置 STOPPED + 写独立标记 + 读回核对。
     *
     * 这是**本地按钮**和**收到哨兵的 emergencyStop** 共用的唯一入口，
     * 保证两条路径的语义完全一致（一手也是这么做的）。
     *
     * 全程不联网、不请求模型、不等冷静期；写完读回核对才认 `ok=true`。
     */
    fun stopLocal(reason: String): StopOutcome {
        val now = wall.now()
        val outcome = EmergencyStopLocal.stopLocal(policyStore.asStopSink(emergencyStore), now, reason)
        // 停掉之后清掉仲裁器在途记忆：不留下「已锁过一次」的旧账影响恢复后的第一次判断。
        planner.reset()
        lastPlanReason = if (outcome.ok) {
            "紧急暂停：已停止本轮自动锁屏（$reason）"
        } else {
            "紧急暂停未确认：${outcome.note}"
        }
        Log.i(TAG, "local emergency stop ok=${outcome.ok} note=${outcome.note}")
        return outcome
    }

    /**
     * 上报给哨兵：**走 syncPolicy**（规范 §3.1 冻结的方法集，与总控/一手一致）。
     *
     * 过去这里发的是 "emergencyStop"，哨兵 `WatchProvider` 只认
     * `register / syncPolicy / reportReceipt / ping`，所以那条路根本不通。
     * 现在把 emergencyState=STOPPED 随规则一起同步过去，那条路是通的。
     */
    fun reportPauseToSentinel() {
        if (policyStore.policies().isEmpty()) {
            // 没有规则也要让哨兵知道我们停了：同步一份空清单 + STOPPED 标记说明。
            link.syncPolicy(encodePolicies(emptyList(), forcedEmergencyState = "STOPPED"))
        } else {
            link.syncPolicy(encodePolicies(policyStore.policies(), forcedEmergencyState = "STOPPED"))
        }
    }

    /**
     * 用户点「紧急暂停」：本地停 + 尽力上报。
     *
     * 与「正常提前结束监管」分开：这里不需要写理由、不等冷静期、不问模型。
     */
    fun emergencyStop(reason: String): StopOutcome {
        val outcome = stopLocal(reason)
        if (outcome.ok) {
            // 独立标记里补上暂停的发起者，界面上能说清是谁按的。
            emergencyStore.stop(reason, outcome.atWall, mono.now())
            // 上报是“尽力而为”：拿不到回执就保持「暂停未确认」，不谎称全部停止。
            link.onSyncResult { accepted -> emergencyStore.markSentinelAck(wall.now()) }
            reportPauseToSentinel()
        }
        return outcome
    }

    /** 用户显式恢复：清标记 + 规则标回 NONE + 重新同步规则。 */
    fun emergencyResume(): Boolean {
        val ok = EmergencyStopLocal.resumeLocal(policyStore.asStopSink(emergencyStore))
        repairGraceUntilMono = mono.now() + HealthConfig().repairGraceMs
        lastPlanReason = if (ok) "已重新开启本地监管" else "恢复未完全生效，请看诊断"
        planner.reset()
        Log.i(TAG, "emergency resume ok=$ok")
        // 把 NONE 状态的规则同步给哨兵，让那边也看到我们恢复了。
        if (policyStore.policies().isNotEmpty()) {
            link.syncPolicy(encodePolicies(policyStore.policies(), forcedEmergencyState = "NONE"))
        }
        return ok
    }

    // ---------- 诊断 ----------

    data class Status(
        val policies: Int = 0,
        val effective: Int = 0,
        val revision: Int = 0,
        val capability: LockCapability? = null,
        val emergency: EmergencyDto = EmergencyDto(),
        val sentinel: SentinelLink.LinkState = SentinelLink.LinkState(),
        val lastPlanReason: String = "",
        val lastAttempt: LockAttempt? = null,
        val storeError: String = "",
    )

    fun status(): Status = Status(
        policies = policyStore.policies().size,
        effective = policyStore.effective().size,
        revision = policyStore.revision(),
        capability = runCatching { capability() }.getOrNull(),
        emergency = emergencyStore.current(),
        sentinel = link.state,
        lastPlanReason = lastPlanReason,
        lastAttempt = lastAttempt,
        storeError = policyStore.lastError.orEmpty(),
    )

    fun shutdown() {
        link.shutdown()
        runCatching { selfProbeExecutor.shutdownNow() }
    }

    companion object {
        private const val TAG = "PetGuardRuntime"

        /**
         * 规则清单编码成规范 §4 的形状，供 syncPolicy 传给哨兵。
         *
         * **F-06 修复点**：issuer 在这里做 `user → pet` 归一化（[wireIssuer]）。
         * 哨兵 `issuerForPackage("cn.companion.pet")` 只认 `"pet"`，别的值一律
         * `Merge(false, "叫 X 的调用方不能写 Y 的规则")`；本地存 `user` 不变，
         * 只是**发出去的报文**必须写成哨兵认的值。
         *
         * @param forcedEmergencyState 非 null 时覆盖每条规则的 emergencyState。
         *   紧急暂停上报走 syncPolicy 时用它写 STOPPED：哨兵只认这个字段，
         *   这样不需要为「暂停」新增一个规范以外的方法（§3.1 方法集冻结）。
         */
        fun encodePolicies(policies: List<Policy>, forcedEmergencyState: String? = null): String {
            val body = policies.joinToString(",") { p ->
                val dto = p.toDto()
                val state = forcedEmergencyState ?: dto.emergencyState
                // 发出去的 issuer 恒为哨兵认的值（F-06）。
                val issuer = wireIssuer(dto.issuer)
                buildString {
                    append("{")
                    append("\"policyId\":\"").append(esc(dto.policyId)).append("\",")
                    append("\"revision\":").append(dto.revision).append(",")
                    append("\"issuer\":\"").append(esc(issuer)).append("\",")
                    append("\"requiredApps\":")
                    append(dto.requiredApps.joinToString(",", "[", "]") { "\"" + esc(it) + "\"" })
                    append(",\"startsAtWall\":").append(dto.startsAtWall)
                    append(",\"endsAtWall\":").append(dto.endsAtWall)
                    append(",\"lockAtWall\":").append(dto.lockAtWall)
                    append(",\"failureAction\":\"").append(esc(dto.failureAction)).append("\",")
                    append("\"repairGraceMs\":").append(dto.repairGraceMs)
                    append(",\"emergencyState\":\"").append(esc(state)).append("\",")
                    append("\"enabled\":").append(dto.enabled)
                    append("}")
                }
            }
            return "{\"schemaVersion\":${PolicyFileDto.SCHEMA},\"policies\":[$body]}"
        }

        private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
