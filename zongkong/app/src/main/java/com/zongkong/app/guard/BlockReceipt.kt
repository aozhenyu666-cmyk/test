package com.zongkong.app.guard

/**
 * 拦截收据：回答一个问题——「这次真的拦住了吗」。
 *
 * 以前弹回是「发完就当拦住了」：startActivity 之后没有任何证据说明拦截页真的到了前台，
 * 被拦的应用有没有离开前台也不看，失败没人记（P1-04）。这里把两件事分开：
 *
 * - 发请求：[Machine.onRequest] 记一个 [Attempt]（在途）；
 * - 看到窗口切到拦截页 / 做题页：[Machine.onForeground] → [Outcome.Observed]，带延迟；
 * - 被拦的应用又回到前台：[Outcome.Regressed]，调用方重新拦一次；
 * - 到点还没看到：[Outcome.TimedOut]，允许再弹一次（最多一次，见 [MAX_RETRIES]）；
 * - startActivity 直接抛出：[Outcome.FailedNoActivity]，带原因。
 *
 * 全是纯 Kotlin（不 import 任何 Android 类），单元测试在 JVM 上直接驱动。
 * 落盘和界面展示交给调用方：每次结案写一条 [Diag]，眼睛日志里就能看到（进程重启也还在）。
 */
object BlockReceipt {

    /** 请求发出后多久还没看到拦截页，就算这次没拦住。 */
    const val TIMEOUT_MILLIS = 2_500L

    /** 同一个请求最多再弹一次：有界重试，绝不无限循环。 */
    const val MAX_RETRIES = 1

    /** 内存里留最近多少张收据。 */
    const val CAPACITY = 20

    /** 请求刚发出时收到的同一个应用的重复窗口事件，不算「又回来了」。 */
    const val REGRESS_GRACE_MILLIS = 500L

    /** 拦截页 / 做题页的全限定名：和 [BlockActivity] / [GateActivity] 保持一致（测试可换假类名）。 */
    const val BLOCK_SCREEN = "com.zongkong.app.guard.BlockActivity"
    const val GATE_SCREEN = "com.zongkong.app.guard.GateActivity"

    /** 诊断日志的种类（[Diag.KINDS] 里同步登记，眼睛日志按这个上色）。 */
    const val KIND_OK = "block_ok"
    const val KIND_FAIL = "block_fail"
    const val KIND_BACK = "block_back"

    /** 拦的是哪一页：严管的拦截页，还是低屏模式的做题页。 */
    enum class Target { BLOCK, GATE }

    /** 进程里唯一的那台机器：[GuardService]、[Watch] 记请求，眼睛日志读最近一张收据。 */
    val shared = Machine()

    /** 一次拦截请求。 */
    data class Attempt(
        val id: String,
        val pkg: String,
        val requestedAtMono: Long,
        val retries: Int = 0,
        val target: Target = Target.BLOCK,
    )

    /** 结案的结果：只有 [Observed] 才算真的拦住了。 */
    sealed interface Outcome {
        /** 拦截页（做题页）真的到了前台。 */
        data object Observed : Outcome

        /** 压根没弹出任何页面（startActivity 抛了）。 */
        data object FailedNoActivity : Outcome

        /** 被拦的应用又回到前台了。 */
        data object Regressed : Outcome

        /** 到点还没看到拦截页。 */
        data object TimedOut : Outcome
    }

    /** 一张收据：这次请求是什么时候发的、结案成什么、花了多久、为什么。 */
    data class Receipt(
        val seq: Long,
        val id: String,
        val pkg: String,
        val target: Target,
        val outcome: Outcome,
        val requestedAtMono: Long,
        val settledAtMono: Long,
        /** 从发请求到看到页面用了多少毫秒；没看到就是 -1。 */
        val latencyMillis: Long,
        val retries: Int,
        /** 失败原因（一句中文），成功时为空。 */
        val note: String = "",
    ) {
        /** 只有真的看到页面才算成功。 */
        val ok: Boolean get() = outcome == Outcome.Observed

        /** 诊断日志的种类。 */
        fun kind(): String = when (outcome) {
            Outcome.Observed -> KIND_OK
            Outcome.Regressed -> KIND_BACK
            Outcome.FailedNoActivity, Outcome.TimedOut -> KIND_FAIL
        }

        /** 一句话说清楚：结果 + 原因 + 延迟。界面和诊断日志都直接用这句。 */
        fun describe(): String {
            val screen = if (target == Target.GATE) "做题页" else "拦截页"
            val retry = if (retries > 0) "（重试 $retries 次）" else ""
            return when (outcome) {
                Outcome.Observed -> "$screen 已到前台：$pkg · 延迟 ${latencyMillis.coerceAtLeast(0L)}ms$retry"
                Outcome.FailedNoActivity -> "$screen 没弹出来：$pkg · ${note.ifBlank { "打不开" }}$retry"
                Outcome.TimedOut -> "$screen 没看到：$pkg · ${note.ifBlank { "超时" }}$retry"
                Outcome.Regressed -> "$pkg 又回到前台，重新拦$retry"
            }
        }
    }

    /** 一次事件处理完之后给调用方的交代：结了什么案、要不要再弹一次、要不要重新拦。 */
    data class Decision(
        /** 这一步结案的收据（可能不止一张：拦截页和做题页各有一笔在途）。 */
        val receipts: List<Receipt> = emptyList(),
        /** 超时还有额度：再弹一次，用这个请求（[Attempt.retries] 已经 +1）。只对拦截页重试。 */
        val retry: Attempt? = null,
    ) {
        /** 最近结案的那一张（调用方通常只关心这个）。 */
        val receipt: Receipt? get() = receipts.lastOrNull()
    }

    /**
     * 纯状态机：拦截页和做题页各有一个在途请求（分开记，互不顶掉），结案就进环形缓冲。
     *
     * 无障碍回调和 tick 都在主线程，锁只是兜底。
     */
    class Machine(
        private val timeoutMillis: Long = TIMEOUT_MILLIS,
        private val maxRetries: Int = MAX_RETRIES,
        private val capacity: Int = CAPACITY,
        private val blockScreen: String = BLOCK_SCREEN,
        private val gateScreen: String = GATE_SCREEN,
    ) {
        private val lock = Any()

        /** 每一页各自一份在途请求：做题页弹的时候不会把正在等的拦截页顶掉。 */
        private val pending = LinkedHashMap<Target, Attempt>()
        private val receipts = ArrayDeque<Receipt>()
        private var sequence = 0L
        private var counter = 0L

        /** 现在在途的请求（[target] 指定的那一页；没有就是 null）。 */
        fun pendingAttempt(target: Target = Target.BLOCK): Attempt? = synchronized(lock) { pending[target] }

        /** 最近结案的收据，从旧到新。 */
        fun recent(limit: Int = capacity): List<Receipt> = synchronized(lock) {
            if (limit <= 0) emptyList() else receipts.toList().takeLast(limit)
        }

        /** 最近一张收据（没有就是 null）。 */
        fun last(): Receipt? = synchronized(lock) { receipts.lastOrNull() }

        /**
         * 要弹拦截页（做题页）了：记一次请求，返回这次的 [Attempt]。
         *
         * 同一页上一次还没结案就被新请求顶掉（比如换了个应用）：按「从没观测到」结案，不假装成功。
         */
        fun onRequest(pkg: String, nowMono: Long, target: Target = Target.BLOCK): Attempt = synchronized(lock) {
            pending[target]?.let { old ->
                settle(old, Outcome.FailedNoActivity, nowMono, "被「$pkg」的新请求顶掉，没观测到页面")
            }
            val a = Attempt(id = nextId(target), pkg = pkg, requestedAtMono = nowMono, target = target)
            pending[target] = a
            a
        }

        /**
         * 前台窗口变了。
         *
         * - 类名是某笔在途请求该弹的那一页 → [Outcome.Observed]（这就是「真的拦住了」的唯一证据）；
         * - 包名还是被拦的那个应用，且过了 [REGRESS_GRACE_MILLIS] → [Outcome.Regressed]，
         *   这次请求按失败结案，调用方照常再拦一次；
         * - 其它窗口（别的应用、桌面、输入法）不影响这次请求。
         */
        fun onForeground(pkg: String, cls: String, nowMono: Long): Decision = synchronized(lock) {
            val settled = mutableListOf<Receipt>()
            for (a in pending.values.toList()) {
                if (cls == screenOf(a.target)) {
                    settle(a, Outcome.Observed, nowMono, "")?.let { settled += it }
                } else if (pkg == a.pkg) {
                    // 请求刚发出去时，系统还会补发同一个应用的窗口事件：那是「还没走」，不是「又回来了」
                    if (nowMono - a.requestedAtMono < REGRESS_GRACE_MILLIS) continue
                    settle(a, Outcome.Regressed, nowMono, "「$pkg」又回到前台")?.let { settled += it }
                }
            }
            Decision(receipts = settled)
        }

        /** startActivity 就抛了：当场结案成「没弹出任何页面」，带原因，不重试。 */
        fun onLaunchFailed(pkg: String, nowMono: Long, reason: String): Decision = synchronized(lock) {
            val settled = mutableListOf<Receipt>()
            for (a in pending.values.toList()) {
                if (a.pkg != pkg) continue
                settle(a, Outcome.FailedNoActivity, nowMono, reason.ifBlank { "打不开" })?.let { settled += it }
            }
            Decision(receipts = settled)
        }

        /**
         * 到点检查（定时 / tick 都可以调）：在途请求超过 [timeoutMillis] 就结案成 [Outcome.TimedOut]；
         * 拦截页还有重试额度就返回 [Decision.retry]，让调用方再弹一次。
         *
         * 同一次请求只会结案一次：结完就换成重试请求或者清空，再调一次不会重复上报。
         */
        fun onTick(nowMono: Long): Decision = synchronized(lock) {
            val settled = mutableListOf<Receipt>()
            var retry: Attempt? = null
            for (a in pending.values.toList()) {
                if (nowMono - a.requestedAtMono < timeoutMillis) continue
                settle(a, Outcome.TimedOut, nowMono, "${timeoutMillis}ms 内没看到页面")?.let { settled += it }
                // 做题页不自动重试：低屏模式自己有排期
                if (a.target != Target.BLOCK || a.retries >= maxRetries) continue
                val again = a.copy(id = nextId(a.target), requestedAtMono = nowMono, retries = a.retries + 1)
                pending[a.target] = again
                retry = again
            }
            Decision(receipts = settled, retry = retry)
        }

        // ---------- 内部 ----------

        /** 结案：清掉这笔在途请求，收据进环形缓冲。同一笔已经结过就直接返回 null（不重复上报）。 */
        private fun settle(a: Attempt, outcome: Outcome, nowMono: Long, note: String): Receipt? {
            if (pending[a.target]?.id != a.id) return null
            pending.remove(a.target)
            val receipt = Receipt(
                seq = ++sequence,
                id = a.id,
                pkg = a.pkg,
                target = a.target,
                outcome = outcome,
                requestedAtMono = a.requestedAtMono,
                settledAtMono = nowMono,
                latencyMillis = if (outcome == Outcome.Observed) (nowMono - a.requestedAtMono).coerceAtLeast(0L) else -1L,
                retries = a.retries,
                note = note,
            )
            receipts.addLast(receipt)
            while (receipts.size > capacity) receipts.removeFirst()
            return receipt
        }

        private fun screenOf(target: Target): String = if (target == Target.GATE) gateScreen else blockScreen

        private fun nextId(target: Target): String = "${if (target == Target.GATE) "gate" else "block"}-${++counter}"
    }
}
