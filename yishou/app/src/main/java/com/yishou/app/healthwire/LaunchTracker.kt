package com.yishou.app.healthwire

/*
 * T18：`startActivity` 返回 true 不算成功。
 *
 * 规范 §0 铁律 4「不把调用返回当成功。锁屏要观察；启动 Activity 要验证前台」。
 * 这里把「发出请求」和「真的在前台」分成两个状态，中间必须有一次真实观测：
 *
 *   REQUESTED      已决定要打开，记下请求（pkg / 目标页 / 时刻）
 *        ↓ 调 startActivity
 *   API_ACCEPTED   系统接受了请求（没抛异常）——**这还不是成功**
 *        ↓ 等目标页自己 onResume（那是「真的在前台」的唯一证据）
 *   VERIFIED_FOREGROUND  → 成功
 *   NOT_FOREGROUND       → 到点没观测到，如实记下（并让调用方改发通知）
 *   API_REJECTED / FAILED→ 系统没接受 / 抛了异常
 *
 * 纯逻辑（时钟与「是否在前台」都由调用方传入），可在 JVM 单测里直接推进。
 * 观测方式：一手自己的页面（GateActivity / WindowActivity）在 onResume 时回调
 * [onForeground]，这比读别人给的回执可靠，也不需要额外权限。
 */
object LaunchTracker {

    const val REQUESTED = "REQUESTED"
    const val API_ACCEPTED = "API_ACCEPTED"
    const val API_REJECTED = "API_REJECTED"
    const val FAILED = "FAILED"
    const val VERIFIED_FOREGROUND = "VERIFIED_FOREGROUND"
    const val NOT_FOREGROUND = "NOT_FOREGROUND"

    /** 默认等待「真的到前台」的预算。 **/
    const val DEFAULT_VERIFY_WITHIN_MS = 3_000L

    data class Receipt(
        val launchId: String,
        /** 目标页面（GateActivity / WindowActivity / …）。 */
        val target: String,
        /** 打开它是因为用户在哪个应用里（诊断用）。 */
        val pkg: String,
        val group: String = "",
        val state: String,
        val requestedAtMono: Long,
        val updatedAtMono: Long,
        val note: String = "",
    ) {
        val verified: Boolean get() = state == VERIFIED_FOREGROUND
        /** 还等着观测。 */
        val awaitingObservation: Boolean get() = state == REQUESTED || state == API_ACCEPTED
    }

    private val receipts = LinkedHashMap<String, Receipt>()
    private var seq = 0L

    /** 记一次「决定要打开」。返回 launchId。 */
    @Synchronized
    fun begin(target: String, pkg: String, group: String, nowMono: Long): String {
        seq += 1
        val id = "L$seq-${nowMono}"
        receipts[id] = Receipt(
            launchId = id,
            target = target,
            pkg = pkg,
            group = group,
            state = REQUESTED,
            requestedAtMono = nowMono,
            updatedAtMono = nowMono,
        )
        trim()
        return id
    }

    /** startActivity 回来之后：accepted=true 只代表系统接了请求，不代表真的到前台了。 */
    @Synchronized
    fun onApiResult(launchId: String, accepted: Boolean, nowMono: Long, note: String = ""): Receipt? {
        val r = receipts[launchId] ?: return null
        val next = r.copy(
            state = if (accepted) API_ACCEPTED else API_REJECTED,
            updatedAtMono = nowMono,
            note = note.ifBlank { if (accepted) "系统接受请求，等待实际前台" else "系统没有接受请求" },
        )
        receipts[launchId] = next
        return next
    }

    /**
     * 观测结果。`foreground=true` 只能来自目标页自己到位（onResume / onNewIntent），
     * 不是在请求方猜的。
     */
    @Synchronized
    fun onObserved(launchId: String, foreground: Boolean, nowMono: Long, note: String = ""): Receipt? {
        val r = receipts[launchId] ?: return null
        val next = r.copy(
            state = if (foreground) VERIFIED_FOREGROUND else NOT_FOREGROUND,
            updatedAtMono = nowMono,
            note = note.ifBlank { if (foreground) "已观测到目标页在前台" else "预算内没观测到目标页在前台" },
        )
        receipts[launchId] = next
        return next
    }

    /** 按「目标页 + 应用」找还在等观测的那一次（页面 onResume 时用它认领）。 */
    @Synchronized
    fun claimAwaiting(target: String, pkg: String, nowMono: Long): Receipt? {
        val hit = receipts.values.lastOrNull { it.awaitingObservation && it.target == target && (it.pkg == pkg || pkg.isBlank()) }
            ?: receipts.values.lastOrNull { it.awaitingObservation && it.target == target }
            ?: return null
        return onObserved(hit.launchId, true, nowMono, "目标页自己报告已在前台")
    }

    /** 超期还没观测到的那些（调用方据此记 NOT_FOREGROUND 并改发通知）。 */
    @Synchronized
    fun overdue(nowMono: Long, verifyWithinMs: Long = DEFAULT_VERIFY_WITHIN_MS): List<Receipt> =
        receipts.values.filter { it.awaitingObservation && nowMono - it.requestedAtMono >= verifyWithinMs }

    @Synchronized
    fun last(): Receipt? = receipts.values.lastOrNull()

    @Synchronized
    fun lastState(): String = last()?.state.orEmpty()

    @Synchronized
    fun lastAtMono(): Long = last()?.updatedAtMono ?: 0L

    @Synchronized
    fun all(): List<Receipt> = receipts.values.toList()

    @Synchronized
    fun reset() {
        receipts.clear()
        seq = 0
    }

    /** 只保留最近 40 条，别让内存随事件无限长。 */
    private fun trim() {
        while (receipts.size > 40) {
            val it = receipts.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else {
                return
            }
        }
    }
}
