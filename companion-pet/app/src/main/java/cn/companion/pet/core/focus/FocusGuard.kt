package cn.companion.pet.core.focus

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * 专注守护（移植自 deepseek/c3-focus-guard 的设计，按审计 F-01—F-08 重写）。
 * 纯 Kotlin：输入前台包名/时间，输出要做的动作；不直接碰 Android。
 */

@Serializable
enum class FocusMode { TEXT, LIVE }

@Serializable
enum class FocusEnforcement { SOFT, STRICT }

@Serializable
enum class FocusState {
    /** 契约已建立，等待确认目标到前台。 */
    ARMED,
    IN_TARGET,
    /** 离开了目标，宽限倒计时中（截止时间固定，重复事件不顺延）。 */
    AWAY_GRACE,
    /** SOFT：已经提醒过，等用户自己回去。 */
    REMINDED,
    /** STRICT：拦截层显示中，等待下一次有界自动返回或用户操作。 */
    BLOCKING,
    /** STRICT：已发出启动请求，等待核验前台。 */
    RETURNING,
    PAUSED,
    COMPLETED,
    ABORTED,
}

@Serializable
enum class FocusEndReason(val label: String) {
    EXPIRED("时间到了"),
    USER_ABORTED("你结束了守护"),
    TARGET_UNAVAILABLE("打不开目标应用"),
}

@Serializable
data class FocusContract(
    val sessionId: String,
    val targetPackage: String,
    val targetLabel: String,
    val startedAt: Long,
    val expiresAt: Long,
    val mode: FocusMode,
    val enforcement: FocusEnforcement,
    val allowedPackages: Set<String> = emptySet(),
    val graceMillis: Long = 10_000,
    val allowedGraceMillis: Long = 90_000,
    val pauseMillis: Long = 5 * 60_000,
    /** STRICT 自动返回的总次数上限（用户手动点“返回”不计）。 */
    val maxReturnAttempts: Int = 3,
    val retryBackoffMillis: Long = 8_000,
    /** 建立后这么久还没确认目标在前台，就按“已离开”处理（F-01）。 */
    val armConfirmMillis: Long = 6_000,
    /** SOFT 每次离开最多提醒几次。 */
    val maxReminders: Int = 3,
    /**
     * 目标所在的 Android 用户 / 实例标识。
     *
     * 0 = 机主；非 0（例如 vivo 的 999「应用分身」）表示分身。
     * null 表示身份不确定 —— 这种情况不能假装守护成功。
     */
    val targetUserId: Int? = 0,    /**
     * 也算作「在目标里」的托管包（例如 Gemini 的界面跑在 Google App 里）。
     *
     * 没有这一项会出现：用户明明在 Gemini 里，却因为前台是 googlequicksearchbox 被判成"离开了然后被拉回"。
     */
    val targetHostPackages: Set<String> = emptySet(),
) {
    /**
     * 身份是否可靠。
     *
     * 为什么必须区分：同一个包名可能在机主与分身里各有一个实例（实测 com.tencent.mm
     * 同时有 u0_a410 与 u999_a410 两个进程）。只比包名会把两个实例当成同一个，
     * 导致「我点分身它还控制着」或「我在主实例里被判定已离开」。
     *
     * 拿不到 userId 时一律视为不确定：不确定就不自动拉回，宁可少管也不乱管。
     */
    val identityCertain: Boolean get() = targetUserId != null
}

@Serializable
data class FocusSnapshot(
    val contract: FocusContract,
    val state: FocusState,
    val lastForeground: String? = null,
    val lastTargetSeenAt: Long = 0,
    /** 离开宽限的截止时间：只在进入离开状态时设定，重复前台事件不会顺延（F-07）。 */
    val awayDeadline: Long = 0,
    /** 下一次自动动作（提醒/重试）的时间。 */
    val nextActionAt: Long = 0,
    val returnAttempts: Int = 0,
    val reminders: Int = 0,
    val pauseUntil: Long = 0,
    /** 每次发出启动请求或状态重置都 +1；延迟回调必须带着它，旧回调不生效（F-06）。 */
    val generation: Int = 0,
    val lastResult: String = "",
    val endReason: String = "",
    val updatedAt: Long,
) {
    val active: Boolean get() = state !in FocusGuard.TERMINAL
}

/** 守护要求外部执行的动作。 */
sealed interface FocusAction {
    /** SOFT：轻提醒（气泡），不全屏。 */
    data class Remind(val text: String) : FocusAction
    /** STRICT：显示/更新全屏拦截层。 */
    data class Block(val reason: String) : FocusAction
    data object HideBlock : FocusAction
    /** 发出启动目标的请求；结果和核验都要带上 [generation]。 */
    data class Launch(val generation: Int) : FocusAction
    data class Ended(val reason: FocusEndReason) : FocusAction
}

class FocusGuard(snapshot: FocusSnapshot) {
    var current: FocusSnapshot = snapshot
        private set
    private val c get() = current.contract
    private var screenWasOff = false

    /**
     * 前台是不是「要守护的那个实例」。
     *
     * 只有包名匹配且身份确定时才严格比对 userId；身份不确定时退回包名匹配，
     * 但调用方必须同时检查 [FocusContract.identityCertain] 才允许自动拉回。
     */
    private fun isTarget(pkg: String?, userId: Int? = null): Boolean {
        if (pkg == null) return false
        // 目标主包或它的托管包都算"在目标里"。
        if (pkg != c.targetPackage && pkg !in c.targetHostPackages) return false
        val want = c.targetUserId ?: return true
        val got = userId ?: return true
        return want == got
    }
    private fun isAllowed(pkg: String?) = pkg != null && pkg in c.allowedPackages

    /** 防抖确认过的真实前台（调用方已过滤系统窗口/输入法/自己）。 */
    fun onForeground(now: Long, pkg: String?): List<FocusAction> {
        if (!current.active || pkg == null) return emptyList()
        val s = current
        if (isTarget(pkg)) {
            // F-03：回到目标就收起拦截/提醒、停掉旧的重试（generation +1）。
            val wasAway = s.state != FocusState.IN_TARGET && s.state != FocusState.ARMED
            current = s.copy(state = if (s.state == FocusState.PAUSED) FocusState.PAUSED else FocusState.IN_TARGET,
                lastForeground = pkg, lastTargetSeenAt = now, awayDeadline = 0, nextActionAt = 0,
                returnAttempts = 0, reminders = 0, generation = s.generation + 1,
                lastResult = if (wasAway) "已回到${c.targetLabel}" else s.lastResult, updatedAt = now)
            return if (wasAway) listOf(FocusAction.HideBlock) else emptyList()
        }
        if (c.mode == FocusMode.LIVE) {
            current = s.copy(lastForeground = pkg, updatedAt = now)
            return emptyList()
        }
        return when (s.state) {
            // F-01：刚建立（还没确认目标）就切走，同样开始宽限。
            FocusState.ARMED, FocusState.IN_TARGET -> {
                current = s.copy(state = FocusState.AWAY_GRACE, lastForeground = pkg,
                    awayDeadline = now + if (isAllowed(pkg)) c.allowedGraceMillis else c.graceMillis, updatedAt = now)
                emptyList()
            }
            // F-07：已经在离开中，重复事件不顺延；从允许的 App 换到别的 App，宽限缩回普通长度。
            FocusState.AWAY_GRACE -> {
                val deadline = if (isAllowed(pkg)) s.awayDeadline else minOf(s.awayDeadline, now + c.graceMillis)
                current = s.copy(lastForeground = pkg, awayDeadline = deadline, updatedAt = now)
                emptyList()
            }
            else -> {
                current = s.copy(lastForeground = pkg, updatedAt = now)
                emptyList()
            }
        }
    }

    /**
     * 每秒调用。[activePackage] 是真实活动窗口（已过滤系统/自身，可能为 null）；
     * [interactive] 为 false（熄屏/锁屏）时不做任何拉回或提醒。
     */
    fun tick(now: Long, activePackage: String?, interactive: Boolean = true): List<FocusAction> {
        if (!current.active) return emptyList()
        val s = current
        if (now >= c.expiresAt) {
            current = s.copy(state = FocusState.COMPLETED, endReason = FocusEndReason.EXPIRED.name,
                generation = s.generation + 1, updatedAt = now)
            return listOf(FocusAction.HideBlock, FocusAction.Ended(FocusEndReason.EXPIRED))
        }
        if (!interactive) { screenWasOff = true; return emptyList() }
        if (screenWasOff) {
            // 刚亮屏：先给一个普通宽限，别一解锁就拉回/提醒。
            screenWasOff = false
            if (s.state == FocusState.AWAY_GRACE) current = s.copy(awayDeadline = maxOf(s.awayDeadline, now + c.graceMillis))
            if (s.state == FocusState.REMINDED || s.state == FocusState.BLOCKING) current = s.copy(nextActionAt = maxOf(s.nextActionAt, now + c.graceMillis))
            return emptyList()
        }
        return when (s.state) {
            FocusState.PAUSED -> if (now < s.pauseUntil) emptyList() else {
                current = if (isTarget(activePackage ?: s.lastForeground)) s.copy(state = FocusState.IN_TARGET, pauseUntil = 0, updatedAt = now)
                else s.copy(state = FocusState.AWAY_GRACE, pauseUntil = 0, awayDeadline = now + c.graceMillis, updatedAt = now)
                emptyList()
            }
            FocusState.ARMED -> if (now - c.startedAt < c.armConfirmMillis) emptyList() else when {
                isTarget(activePackage) -> onForeground(now, activePackage)
                // F-01：启动后一直没确认到目标（例如被系统拦了后台启动），按离开处理。
                else -> {
                    current = s.copy(state = FocusState.AWAY_GRACE, lastForeground = activePackage ?: s.lastForeground,
                        awayDeadline = now + c.graceMillis, lastResult = "没确认到${c.targetLabel}在前台", updatedAt = now)
                    emptyList()
                }
            }
            FocusState.AWAY_GRACE -> if (c.mode == FocusMode.LIVE || now < s.awayDeadline) emptyList() else graceExpired(now)
            FocusState.REMINDED -> if (now < s.nextActionAt || s.reminders >= c.maxReminders) emptyList() else {
                current = s.copy(reminders = s.reminders + 1, nextActionAt = now + c.retryBackoffMillis * 2, updatedAt = now)
                listOf(FocusAction.Remind(remindText(now)))
            }
            FocusState.BLOCKING -> if (now < s.nextActionAt || s.returnAttempts >= c.maxReturnAttempts) emptyList() else autoReturn(now)
            else -> emptyList()
        }
    }

    /** F-08：SOFT 只提醒；STRICT 才拦截，并立刻开始有界的自动返回（F-02）。 */
    private fun graceExpired(now: Long): List<FocusAction> {
        val s = current
        if (c.enforcement == FocusEnforcement.SOFT) {
            current = s.copy(state = FocusState.REMINDED, reminders = 1, nextActionAt = now + c.retryBackoffMillis * 2, updatedAt = now)
            return listOf(FocusAction.Remind(remindText(now)))
        }
        current = s.copy(state = FocusState.BLOCKING, updatedAt = now)
        return listOf(FocusAction.Block("你离开了「${c.targetLabel}」，守护还剩 ${minutesLeft(now)} 分钟，正在带你回去。")) + autoReturn(now)
    }

    private fun autoReturn(now: Long): List<FocusAction> {
        val s = current
        val gen = s.generation + 1
        current = s.copy(state = FocusState.RETURNING, returnAttempts = s.returnAttempts + 1, generation = gen, updatedAt = now)
        return listOf(FocusAction.Launch(gen))
    }

    /** Screen off / a call invalidates in-flight return callbacks without extending the contract. */
    fun interruptInteractive(now: Long): List<FocusAction> {
        if (!current.active) return emptyList()
        screenWasOff = true
        if (current.state == FocusState.RETURNING) current = current.copy(state = FocusState.BLOCKING,
            generation = current.generation + 1, nextActionAt = now + c.graceMillis, updatedAt = now)
        return if (current.state in setOf(FocusState.BLOCKING, FocusState.REMINDED)) listOf(FocusAction.HideBlock) else emptyList()
    }

    /** 用户点“返回目标”：不计入自动次数上限，但同样要核验。 */
    fun userReturn(now: Long): List<FocusAction> {
        if (!current.active) return emptyList()
        val gen = current.generation + 1
        current = current.copy(state = FocusState.RETURNING, generation = gen, updatedAt = now)
        return listOf(FocusAction.Launch(gen))
    }

    /** 启动请求的真实结果（F-06：不再硬编码 launched=true）。 */
    fun onLaunchResult(now: Long, generation: Int, accepted: Boolean): List<FocusAction> {
        val s = current
        if (!s.active || s.state != FocusState.RETURNING || generation != s.generation) return emptyList()
        if (accepted) {
            current = s.copy(lastResult = "系统接受了启动请求，正在核验前台", updatedAt = now)
            return emptyList()
        }
        return failedReturn(now, "系统拒绝了启动请求（vivo/iQOO 上要允许“后台弹出界面”）")
    }

    /** 启动后重新读前台：只有真的看到目标在前台才算回去了。 */
    fun onVerify(now: Long, generation: Int, activePackage: String?): List<FocusAction> {
        val s = current
        if (!s.active || s.state != FocusState.RETURNING || generation != s.generation) return emptyList()
        if (isTarget(activePackage)) return onForeground(now, activePackage)
        return failedReturn(now, "系统接受了启动请求，但「${c.targetLabel}」没有回到前台")
    }

    private fun failedReturn(now: Long, why: String): List<FocusAction> {
        val s = current
        val exhausted = s.returnAttempts >= c.maxReturnAttempts
        val text = if (exhausted) "$why。已自动尝试 ${s.returnAttempts} 次，不再自动重试：请手动返回，或暂停/结束守护。"
        else "$why。${c.retryBackoffMillis / 1000} 秒后再试（第 ${s.returnAttempts}/${c.maxReturnAttempts} 次）。"
        current = s.copy(state = FocusState.BLOCKING, nextActionAt = now + c.retryBackoffMillis, lastResult = text, updatedAt = now)
        return listOf(FocusAction.Block(text))
    }

    fun pause(now: Long): List<FocusAction> {
        if (!current.active) return emptyList()
        current = current.copy(state = FocusState.PAUSED, pauseUntil = now + c.pauseMillis,
            generation = current.generation + 1, lastResult = "暂停 ${c.pauseMillis / 60_000} 分钟", updatedAt = now)
        return listOf(FocusAction.HideBlock)
    }

    /**
     * 练习换来的休息（U07）：暂停到 [until]，到点按普通暂停的规则恢复。
     * 不延长契约：[until] 超过契约到期时截到到期；已经在更长的暂停里就不缩短。
     */
    fun pauseUntil(now: Long, until: Long, reason: String): List<FocusAction> {
        if (!current.active) return emptyList()
        val end = minOf(until, c.expiresAt)
        if (end <= now) return emptyList()
        if (current.state == FocusState.PAUSED && current.pauseUntil >= end) return emptyList()
        current = current.copy(state = FocusState.PAUSED, pauseUntil = end,
            generation = current.generation + 1, lastResult = reason, updatedAt = now)
        return listOf(FocusAction.HideBlock)
    }

    /** 紧急退出：任何状态都立即结束，旧回调全部作废。 */
    fun abort(now: Long, reason: FocusEndReason = FocusEndReason.USER_ABORTED): List<FocusAction> {
        if (!current.active) return emptyList()
        current = current.copy(state = FocusState.ABORTED, endReason = reason.name, generation = current.generation + 1, updatedAt = now)
        return listOf(FocusAction.HideBlock, FocusAction.Ended(reason))
    }

    private fun minutesLeft(now: Long) = ((c.expiresAt - now) / 60_000).coerceAtLeast(1)

    private fun remindText(now: Long) = "守护还剩 ${minutesLeft(now)} 分钟，回「${c.targetLabel}」继续？"

    companion object {
        val TERMINAL = setOf(FocusState.COMPLETED, FocusState.ABORTED)
    }
}

/** 守护持久化：同目录原子替换；结束后保留墓碑（不删除），重启不会复活；损坏文件另存备份（F-04）。 */
class FocusStore(
    root: File,
    private val replace: (File, File) -> Boolean = { from, to ->
        runCatching {
            java.nio.file.Files.move(from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrElse {
            runCatching {
                java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrDefault(false)
        }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file = File(root, "focus/session.json")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile var lastError: String? = null
        private set

    fun load(): FocusSnapshot? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching { json.decodeFromString(FocusSnapshot.serializer(), file.readText()) }.getOrElse {
            val backup = File(file.parentFile, "session.corrupt-" + clock() + ".json")
            lastError = if (file.renameTo(backup)) "守护文件损坏，已另存为 " + backup.name + "，没有恢复守护" else "守护文件损坏，没有恢复守护"
            null
        }
    }

    fun save(snapshot: FocusSnapshot): Boolean = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "." + file.name + ".tmp")
            tmp.writeText(json.encodeToString(FocusSnapshot.serializer(), snapshot))
            if (replace(tmp, file)) { lastError = null; true }
            else { tmp.delete(); lastError = "守护状态写入失败"; false }
        } catch (e: Exception) { lastError = "守护状态写入失败"; false }
    }
}
