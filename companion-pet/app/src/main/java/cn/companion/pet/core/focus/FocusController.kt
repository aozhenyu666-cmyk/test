package cn.companion.pet.core.focus

/** 设备端口（真实实现在 PetBrain；测试用假的）。 */
interface FocusDevicePort {
    /** 发出打开请求，返回系统是否接受（不代表已在前台）。 */
    fun launch(pkg: String): Boolean
    /** 真实活动窗口包名（已过滤系统窗口/输入法/自己）；读不到为 null。 */
    fun activePackage(): String?
    /** Independent periodic sample; null means unavailable, never reuse an old window event. */
    fun observeForeground(): String? = null
}

/** 拦截层显示的真实结果（F-05）。 */
data class OverlayResult(val shown: Boolean, val reason: String = "")

interface FocusUiPort {
    /** STRICT 全屏拦截层；必须如实返回是否显示成功。 */
    fun showBlock(targetLabel: String, reason: String): OverlayResult
    fun hideBlock()
    fun observeBlock(): BlockObservation? = null
    /** SOFT 轻提醒（桌宠气泡），不全屏。 */
    fun remind(targetLabel: String, text: String)
    /** 守护结束（到期/紧急退出/打不开）。 */
    fun ended(targetLabel: String, reason: FocusEndReason) {}
    /** 离开后**确认**回到了目标（看到目标在前台）。调用方可借此把未完成的训练原题带回来。 */
    fun returnedToTarget(targetLabel: String) {}
}

interface FocusScheduler {
    fun post(delayMillis: Long, task: () -> Unit)
}

sealed interface StartResult {
    data object Started : StartResult
    data class Failed(val reason: String) : StartResult
}

/** 诊断/界面用的状态快照（不含任何屏幕内容）。 */
data class FocusStatus(
    val active: Boolean,
    val state: FocusState? = null,
    val targetLabel: String = "",
    val enforcement: FocusEnforcement? = null,
    val mode: FocusMode? = null,
    val secondsLeft: Long = 0,
    val returnAttempts: Int = 0,
    val lastResult: String = "",
    val overlayShown: Boolean = false,
    val overlayError: String = "",
    val storeError: String = "",
    val lastAction: cn.companion.pet.core.training.ActionReceipt? = null,
)

/**
 * 把守护状态机接到设备：前台确认 → 决策 → 拦截/提醒 → 启动 → 核验。
 * - 先存契约再启动；存不下就不启动（F-04）。
 * - 启动请求被接受 ≠ 回去了；必须核验前台（F-06）。
 * - 所有延迟回调核对会话 ID 和 generation，旧回调不影响新会话（F-06）。
 * - 拦截层显示失败要记下来，不能宣称已拦截（F-05）。
 * 只在主线程调用。
 */
class FocusController(
    private val store: FocusStore,
    private val device: FocusDevicePort,
    private val ui: FocusUiPort,
    private val scheduler: FocusScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var guard: FocusGuard? = null
    private var overlayShown = false
    private var overlayError = ""
    private val evidence = FocusActionEvidence()
    private var returnReceipt: String? = null
    private var lastPollAt: Long? = null
    private var interactive = true
    private var lastPersisted: FocusSnapshot? = null
    private var lifecycle = 0L
    private var notifiedReturn: Pair<Long, Int>? = null
    private var lastHideAttemptAt: Long? = null

    fun lastAction(): cn.companion.pet.core.training.ActionReceipt? = evidence.latest(now())
    fun actionHistory(): List<cn.companion.pet.core.training.ActionReceipt> = evidence.history(now())


    val active: Boolean get() = guard?.current?.active == true
    val snapshot: FocusSnapshot? get() = guard?.current

    fun status(): FocusStatus {
        val s = guard?.current ?: return FocusStatus(false, storeError = store.lastError.orEmpty(), lastAction = lastAction())
        return FocusStatus(s.active, s.state, s.contract.targetLabel, s.contract.enforcement, s.contract.mode,
            ((s.contract.expiresAt - now()) / 1000).coerceAtLeast(0), s.returnAttempts, s.lastResult,
            overlayShown, overlayError, store.lastError.orEmpty(), lastAction())
    }

    /** 服务重启后恢复；已结束/已过期/损坏的不复活。被拦截/返回中的会话恢复成“等待下一次动作”。 */
    fun restore(interactive: Boolean = true): FocusSnapshot? {
        this.interactive = interactive
        val snap = store.load() ?: return null
        if (!snap.active) return null
        if (now() >= snap.contract.expiresAt) {
            store.save(snap.copy(state = FocusState.COMPLETED, endReason = FocusEndReason.EXPIRED.name, updatedAt = now()))
            return null
        }
        val resumed = when (snap.state) {
            // 进程死时正在返回：结果未知，按“被拦截、马上可以再试”恢复，generation 作废旧回调。
            FocusState.RETURNING -> snap.copy(state = FocusState.BLOCKING, nextActionAt = now(), generation = snap.generation + 1)
            else -> snap
        }
        lifecycle++
        evidence.reset(resumed.contract.sessionId)
        returnReceipt = null
        lastPersisted = snap
        guard = FocusGuard(resumed)
        persist()
        if (interactive && resumed.state == FocusState.BLOCKING) apply(listOf(FocusAction.Block(resumed.lastResult.ifBlank { "守护恢复：你还没回到「${resumed.contract.targetLabel}」" })))
        return resumed
    }

    fun start(contract: FocusContract, foreground: String?): StartResult {
        if (active) return StartResult.Failed("已经有一个守护在进行中")
        val snap = FocusSnapshot(contract, FocusState.ARMED, lastForeground = foreground, updatedAt = now())
        // F-04：契约存不下就不打开目标，也不宣称已建立约束。
        if (!store.save(snap)) return StartResult.Failed("守护状态保存失败（" + store.lastError.orEmpty() + "），没有打开 " + contract.targetLabel)
        lastPersisted = snap
        lifecycle++
        evidence.reset(contract.sessionId)
        returnReceipt = null
        lastPollAt = null
        guard = FocusGuard(snap)
        overlayShown = false
        overlayError = ""
        val opening = evidence.requested("focus_open", now())
        returnReceipt = opening
        val accepted = runCatching { device.launch(contract.targetPackage) }.getOrDefault(false)
        evidence.reported(opening, accepted, now(), if (accepted) "系统接受打开请求，未确认前台" else "系统拒绝打开请求")
        if (!accepted) {
            val g = guard!!
            g.abort(now(), FocusEndReason.TARGET_UNAVAILABLE)
            persist()
            guard = null
            return StartResult.Failed("打不开 " + contract.targetLabel + "，没有建立守护")
        }
        return StartResult.Started
    }

    fun onForeground(pkg: String?) {
        if (!interactive) return
        val g = guard ?: return
        val before = g.current.state
        apply(g.onForeground(now(), pkg))
        observeReturn(before)
        notifyReturn(before)
    }

    /** 从离开类状态变成 IN_TARGET：告诉界面“已确认回到目标”。 */
    private fun notifyReturn(before: FocusState) {
        val g = guard ?: return
        val away = before in setOf(FocusState.AWAY_GRACE, FocusState.REMINDED, FocusState.BLOCKING, FocusState.RETURNING)
        val token = lifecycle to g.current.generation
        if (away && g.current.state == FocusState.IN_TARGET && notifiedReturn != token &&
            runCatching { ui.observeBlock()?.attached }.getOrNull() != true) {
            notifiedReturn = token
            runCatching { ui.returnedToTarget(g.current.contract.targetLabel) }
        }
    }

    private fun observeReturn(before: FocusState) {
        val g = guard ?: return
        if (before == FocusState.IN_TARGET || g.current.state != FocusState.IN_TARGET) return
        val id = if (before != FocusState.ARMED && before != FocusState.RETURNING) {
            evidence.requested("focus_foreground_return", now()).also {
                evidence.reported(it, true, now(), "收到回到目标的前台事件，等待独立重读")
                returnReceipt = it
            }
        } else returnReceipt ?: return
        val sessionId = g.current.contract.sessionId
        val epoch = lifecycle
        if (runCatching { ui.observeBlock() }.getOrNull() == null) {
            evidence.observed(id, null, now(), "当前显示端不提供独立观察，返回效果未知")
            return
        }
        // Let removeView finish, then independently re-read both foreground and the blocker.
        scheduler.post(DRAW_VERIFY_DELAY) {
            val cur = guard ?: return@post
            if (!interactive || lifecycle != epoch || cur.current.contract.sessionId != sessionId || returnReceipt != id ||
                cur.current.state != FocusState.IN_TARGET) return@post
            val foreground = runCatching { device.activePackage() }.getOrNull()
            val block = runCatching { ui.observeBlock() }.getOrNull()
            val confirmed = when {
                foreground == null || block == null -> null
                foreground != cur.current.contract.targetPackage || block.attached -> false
                else -> true
            }
            evidence.observed(id, confirmed, now(), when (confirmed) {
                true -> "独立重读确认目标在前台，且拦截层已移除"
                false -> "返回未确认：目标不在前台或拦截层仍然附着"
                null -> "无法独立观察前台或拦截层，不能确认返回效果"
            })
            if (confirmed == true) notifyReturn(before)
        }
    }

    /** 练习换来的休息：暂停到 [until]（不超过契约到期），到点自动恢复。 */
    fun pauseUntil(until: Long, reason: String) { guard?.let { apply(it.pauseUntil(now(), until, reason)) } }

    fun tick(interactive: Boolean = true) {
        val g = guard ?: return
        if (!g.current.active) return
        this.interactive = interactive
        if (!interactive) {
            apply(g.tick(now(), null, false))
            if (guard != null) apply(g.interruptInteractive(now()))
            return
        }
        if (g.current.state == FocusState.IN_TARGET && overlayShown && lastHideAttemptAt?.let { now() - it >= FOREGROUND_POLL_INTERVAL } != false) {
            lastHideAttemptAt = now()
            apply(listOf(FocusAction.HideBlock))
            if (!overlayShown) observeReturn(FocusState.BLOCKING)
        }
        if (g.current.state == FocusState.BLOCKING && now() >= g.current.nextActionAt && !overlayShown)
            apply(listOf(FocusAction.Block(g.current.lastResult.ifBlank { "守护恢复，等待返回目标" })))
        if (interactive && (lastPollAt?.let { now() - it >= FOREGROUND_POLL_INTERVAL } != false)) {
            lastPollAt = now()
            val observed = runCatching { device.observeForeground() }.getOrNull()
            if (observed != null && observed != g.current.lastForeground) onForeground(observed)
        }
        val needsActive = g.current.state == FocusState.ARMED || g.current.state == FocusState.PAUSED
        apply(g.tick(now(), if (needsActive) device.activePackage() else null, interactive))
    }

    fun userReturn() { guard?.let { apply(it.userReturn(now())) } }
    fun pause() { guard?.let { apply(it.pause(now())) } }
    fun abort() { guard?.let { apply(it.abort(now())) } }

    private fun apply(actions: List<FocusAction>) {
        val g = guard ?: return
        val label = g.current.contract.targetLabel
        val sessionId = g.current.contract.sessionId
        val epoch = lifecycle
        for (a in actions) {
            when (a) {
                is FocusAction.Remind -> ui.remind(label, a.text)
                is FocusAction.Block -> {
                    val id = evidence.requested("focus_block_draw", now())
                    val r = runCatching { ui.showBlock(label, a.reason) }.getOrElse { OverlayResult(false, it.javaClass.simpleName) }
                    overlayShown = r.shown
                    overlayError = if (r.shown) "" else r.reason.ifBlank { "拦截层没能显示" }
                    evidence.reported(id, r.shown, now(), if (r.shown) "系统接受了全屏层请求，等待绘制观察" else overlayError)
                    if (r.shown && runCatching { ui.observeBlock() }.getOrNull() != null) scheduler.post(DRAW_VERIFY_DELAY) {
                        val cur = guard ?: return@post
                        if (!interactive || lifecycle != epoch || cur.current.contract.sessionId != sessionId || cur.current.state !in setOf(FocusState.BLOCKING, FocusState.RETURNING)) return@post
                        val observation = runCatching { ui.observeBlock() }.getOrNull()
                        val confirmed = observation?.let { it.attached && it.drawn && it.fillsScreen }
                        evidence.observed(id, confirmed, now(), when (confirmed) {
                            true -> "全屏层已附着并完成绘制；实际触摸拦截仍需真机验收"
                            false -> "未观察到完整全屏层，不能声称已拦截"
                            null -> "无法独立读取全屏层状态"
                        })
                    }
                }
                FocusAction.HideBlock -> {
                    runCatching { ui.hideBlock() }
                    overlayShown = runCatching { ui.observeBlock()?.attached }.getOrNull() == true
                    overlayError = if (overlayShown) "请求移除后拦截层仍然附着" else ""
                }
                is FocusAction.Launch -> {
                    if (!interactive) continue
                    if (!overlayShown && g.current.contract.enforcement == FocusEnforcement.STRICT)
                        apply(listOf(FocusAction.Block("守护仍在进行，正在返回目标")))
                    val id = evidence.requested("focus_return", now())
                    returnReceipt = id
                    val accepted = runCatching { device.launch(g.current.contract.targetPackage) }.getOrDefault(false)
                    evidence.reported(id, accepted, now(), if (accepted) "系统接受拉回请求，等待前台核验" else "系统拒绝拉回请求")
                    val follow = g.onLaunchResult(now(), a.generation, accepted)
                    persist()
                    apply(follow)
                    if (accepted) scheduler.post(VERIFY_DELAY) {
                        val cur = guard ?: return@post
                        if (cur.current.contract.sessionId != sessionId || lifecycle != epoch) return@post
                        if (!interactive || cur.current.state != FocusState.RETURNING || cur.current.generation != a.generation) return@post
                        val before = cur.current.state
                        val observed = runCatching { device.activePackage() }.getOrNull()
                        apply(cur.onVerify(now(), a.generation, observed))
                        if (cur.current.state == FocusState.IN_TARGET) observeReturn(before)
                        else evidence.observed(id, if (observed == null) null else false, now(), "拉回后没有独立确认目标前台")
                        notifyReturn(before)
                    }
                }
                is FocusAction.Ended -> {
                    persist() // 墓碑：重启不会复活
                    runCatching { ui.ended(label, a.reason) }
                    guard = null
                    overlayShown = false
                    return
                }
            }
        }
        persist()
    }

    /** 写盘失败不再吞掉：记在 store.lastError，状态页能看到。 */
    private fun persist(): Boolean = guard?.let {
        val snapshot = it.current
        if (snapshot == lastPersisted && store.lastError == null) true
        else store.save(snapshot).also { saved -> if (saved) lastPersisted = snapshot }
    } ?: true

    companion object {
        /** 发出启动请求后等这么久再核验前台。 */
        const val VERIFY_DELAY = 1_200L
        const val DRAW_VERIFY_DELAY = 200L
        const val FOREGROUND_POLL_INTERVAL = 2_000L
    }
}
