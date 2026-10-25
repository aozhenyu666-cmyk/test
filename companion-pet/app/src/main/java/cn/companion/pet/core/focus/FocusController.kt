package cn.companion.pet.core.focus

/** 设备端口（真实实现在 PetBrain；测试用假的）。 */
interface FocusDevicePort {
    /** 发出打开请求，返回系统是否接受（不代表已在前台）。 */
    fun launch(pkg: String): Boolean
    /** 真实活动窗口包名（已过滤系统窗口/输入法/自己）；读不到为 null。 */
    fun activePackage(): String?
}

/** 拦截层显示的真实结果（F-05）。 */
data class OverlayResult(val shown: Boolean, val reason: String = "")

interface FocusUiPort {
    /** STRICT 全屏拦截层；必须如实返回是否显示成功。 */
    fun showBlock(targetLabel: String, reason: String): OverlayResult
    fun hideBlock()
    /** SOFT 轻提醒（桌宠气泡），不全屏。 */
    fun remind(targetLabel: String, text: String)
    /** 守护结束（到期/紧急退出/打不开）。 */
    fun ended(targetLabel: String, reason: FocusEndReason) {}
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

    val active: Boolean get() = guard?.current?.active == true
    val snapshot: FocusSnapshot? get() = guard?.current

    fun status(): FocusStatus {
        val s = guard?.current ?: return FocusStatus(false, storeError = store.lastError.orEmpty())
        return FocusStatus(s.active, s.state, s.contract.targetLabel, s.contract.enforcement, s.contract.mode,
            ((s.contract.expiresAt - now()) / 1000).coerceAtLeast(0), s.returnAttempts, s.lastResult,
            overlayShown, overlayError, store.lastError.orEmpty())
    }

    /** 服务重启后恢复；已结束/已过期/损坏的不复活。被拦截/返回中的会话恢复成“等待下一次动作”。 */
    fun restore(): FocusSnapshot? {
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
        guard = FocusGuard(resumed)
        persist()
        if (resumed.state == FocusState.BLOCKING) apply(listOf(FocusAction.Block(resumed.lastResult.ifBlank { "守护恢复：你还没回到「${resumed.contract.targetLabel}」" })))
        return resumed
    }

    fun start(contract: FocusContract, foreground: String?): StartResult {
        if (active) return StartResult.Failed("已经有一个守护在进行中")
        val snap = FocusSnapshot(contract, FocusState.ARMED, lastForeground = foreground, updatedAt = now())
        // F-04：契约存不下就不打开目标，也不宣称已建立约束。
        if (!store.save(snap)) return StartResult.Failed("守护状态保存失败（" + store.lastError.orEmpty() + "），没有打开 " + contract.targetLabel)
        guard = FocusGuard(snap)
        overlayShown = false
        overlayError = ""
        if (!device.launch(contract.targetPackage)) {
            val g = guard!!
            g.abort(now(), FocusEndReason.TARGET_UNAVAILABLE)
            persist()
            guard = null
            return StartResult.Failed("打不开 " + contract.targetLabel + "，没有建立守护")
        }
        return StartResult.Started
    }

    fun onForeground(pkg: String?) {
        val g = guard ?: return
        apply(g.onForeground(now(), pkg))
    }

    fun tick(interactive: Boolean = true) {
        val g = guard ?: return
        if (!g.current.active) return
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
        for (a in actions) {
            when (a) {
                is FocusAction.Remind -> ui.remind(label, a.text)
                is FocusAction.Block -> {
                    val r = runCatching { ui.showBlock(label, a.reason) }.getOrElse { OverlayResult(false, it.javaClass.simpleName) }
                    overlayShown = r.shown
                    overlayError = if (r.shown) "" else r.reason.ifBlank { "拦截层没能显示" }
                }
                FocusAction.HideBlock -> { runCatching { ui.hideBlock() }; overlayShown = false }
                is FocusAction.Launch -> {
                    val accepted = runCatching { device.launch(g.current.contract.targetPackage) }.getOrDefault(false)
                    val follow = g.onLaunchResult(now(), a.generation, accepted)
                    persist()
                    apply(follow)
                    if (accepted) scheduler.post(VERIFY_DELAY) {
                        val cur = guard ?: return@post
                        if (cur.current.contract.sessionId != sessionId) return@post
                        apply(cur.onVerify(now(), a.generation, device.activePackage()))
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
    private fun persist(): Boolean = guard?.let { store.save(it.current) } ?: true

    companion object {
        /** 发出启动请求后等这么久再核验前台。 */
        const val VERIFY_DELAY = 1_200L
    }
}
