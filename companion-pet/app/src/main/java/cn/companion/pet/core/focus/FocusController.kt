package cn.companion.pet.core.focus

import java.io.File

/** 设备端口：便于纯 JVM 测试与替换真实实现。 */
interface FocusDevicePort {
    /** 发出打开请求，返回是否被系统接受（不代表已在前台）。 */
    fun launch(pkg: String): Boolean
    /** 读当前真正活动窗口包名。 */
    fun activePackage(): String?
}

interface FocusOverlayPort {
    fun show(targetLabel: String, reason: String)
    fun hide()
}

/** 异步调度端口：真实实现用 Handler/协程，测试可立即执行。 */
interface FocusScheduler {
    fun post(delayMillis: Long, task: () -> Unit)
}

sealed interface StartResult {
    data object Started : StartResult
    data class Failed(val reason: String) : StartResult
}

/**
 * 把纯状态机接到设备：前台确认 -> 决策 -> 拦截层 -> 启动 -> 核验。
 *
 * 关键约束：
 * - 启动请求成功不等于回到目标；必须重新读活动窗口确认。
 * - 自动重试有上限和退避；失败保持拦截层，不假装成功。
 * - 用户结束/暂停后立即停止自动拉回。
 */
class FocusController(
    private val root: File,
    private val device: FocusDevicePort,
    private val overlay: FocusOverlayPort,
    private val scheduler: FocusScheduler,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val store = FocusStore(root)
    private var guard: FocusGuard? = null

    val contract: FocusContract? get() = guard?.current?.contract
    val state: FocusState? get() = guard?.current?.state

    /** 服务重启后恢复；过期或已结束的会话不复活。 */
    fun restore(): FocusSnapshot? {
        val snap = store.load() ?: return null
        if (snap.state in FocusGuard.TERMINAL) return null
        if (now() >= snap.contract.expiresAt) {
            store.save(snap.copy(state = FocusState.COMPLETED,
                endReason = FocusEndReason.EXPIRED.name, updatedAt = now()))
            return null
        }
        guard = FocusGuard(snap)
        return snap
    }

    /** 先持久化契约，再启动目标；启动失败回滚并清除，不留下虚假会话。 */
    fun start(contract: FocusContract, foreground: String?): StartResult {
        val snap = FocusSnapshot(contract = contract, state = FocusState.ARMED,
            lastConfirmedForeground = foreground, updatedAt = now())
        guard = FocusGuard(snap)
        store.save(snap)
        if (device.launch(contract.targetPackage)) {
            persist()
            return StartResult.Started
        }
        guard?.abort(now(), FocusEndReason.TARGET_UNAVAILABLE)
        persist()
        store.clear()
        guard = null
        return StartResult.Failed("打不开 " + contract.targetLabel + "，没有建立守护")
    }

    fun onForeground(pkg: String) {
        val g = guard ?: return
        handle(g.onTargetForeground(now(), pkg))
        persist()
    }

    fun onConfirmedLeave(pkg: String) {
        val g = guard ?: return
        if (g.current.contract.mode == FocusMode.LIVE) return
        handle(g.onLeave(now(), pkg))
        persist()
    }

    fun onGraceElapsed() {
        val g = guard ?: return
        if (g.current.contract.mode == FocusMode.LIVE) return
        handle(g.onGraceExpired(now()))
        persist()
    }

    /** 用户点“返回目标”：重置重试次数并核验结果。 */
    fun returnNow() {
        val g = guard ?: return
        handle(g.requestReturn(now(), userInitiated = true))
        persist()
        verifyReturn()
    }

    fun pause() {
        val g = guard ?: return
        handle(g.pause(now()))
        persist()
    }

    fun abort() {
        val g = guard ?: return
        handle(g.abort(now()))
        persist()
        store.clear()
        guard = null
    }

    /** 每秒调用：到期结束、宽限期到点拦截。 */
    fun tick() {
        val g = guard ?: return
        val decision = g.tick(now())
        if (decision is FocusDecision.Completed) {
            overlay.hide()
            store.clear()
            guard = null
            return
        }
        val snap = g.current
        if (snap.state != FocusState.AWAY_GRACE) return
        val grace = if (snap.lastConfirmedForeground in snap.contract.allowedPackages)
            snap.contract.allowedGraceMillis else snap.contract.graceMillis
        if (now() >= snap.updatedAt + grace) onGraceElapsed()
    }

    /** 启动后重新观察，只有确认真回到目标才算成功。 */
    private fun verifyReturn() {
        scheduler.post(VERIFY_DELAY) {
            val g = guard ?: return@post
            val fg = device.activePackage()
            val decision = g.onReturnAttemptResult(now(), launched = true, foreground = fg)
            if (decision is FocusDecision.None) overlay.hide() else handle(decision)
            persist()
            val s = g.current
            if (s.state == FocusState.BLOCKING && s.retryCount < s.contract.maxReturnAttempts) {
                scheduler.post(s.contract.retryBackoffMillis) {
                    val cur = guard ?: return@post
                    if (cur.current.state != FocusState.BLOCKING) return@post
                    handle(cur.requestReturn(now()))
                    persist()
                    verifyReturn()
                }
            }
        }
    }

    private fun handle(decision: FocusDecision) {
        when (decision) {
            is FocusDecision.Grace, is FocusDecision.Paused -> overlay.hide()
            is FocusDecision.Block -> {
                val c = guard?.current?.contract ?: return
                overlay.show(c.targetLabel, decision.reason)
            }
            is FocusDecision.ReturnToTarget -> {
                val pkg = guard?.current?.contract?.targetPackage ?: return
                device.launch(pkg)
            }
            is FocusDecision.Completed, is FocusDecision.Aborted -> overlay.hide()
            FocusDecision.None, is FocusDecision.Verify -> Unit
        }
    }

    private fun persist() {
        guard?.let { store.save(it.current) }
    }

    companion object {
        /** 启动后给系统进入目标的时间，再核验。 */
        const val VERIFY_DELAY = 1_000L
    }
}
