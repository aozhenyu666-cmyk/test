package com.zongkong.app.healthwire

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.zongkong.app.health.LockReceipt
import com.zongkong.app.health.LockRequest
import com.zongkong.app.health.LockState
import com.zongkong.app.health.HealthAction
import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.health.HealthVerdict
import com.zongkong.app.guard.Diag

/**
 * 总控进程内的健康运行时（T02 的落地点）。
 *
 * 三件事：
 *  1. 持有 [HealthLedger]：只记真实发生过的事实（连接实例、真实 ack、真实窗口事件）；
 *  2. 处理哨兵的 `challenge`：**把挑战 post 到无障碍服务的主线程 Handler**，在那里处理完再带回，
 *     Binder 线程只等 + 带超时（绝不在这里造 ack）；
 *  3. 状态与文案：一律走共享内核的 HealthEvaluator，界面只翻译不判断。
 *
 * 进程内共享用静态单例是安全的：HealthWireProvider 与 GuardService 都在默认进程。
 */
object GuardHealth {

    private const val TAG = "GuardHealth"

    private val mono: () -> Long = { SystemClock.elapsedRealtime() }

    val ledger: HealthLedger = HealthLedger(mono = mono)

    private val handler = A11yChallengeHandler(maxInFlight = 2)

    /** 无障碍服务在跑时才有：把 Runnable 投到 a11y 主线程（就是它处理窗口事件的那条线程）。 */
    @Volatile private var a11yPost: ((() -> Unit) -> Boolean)? = null

    /** 无障碍服务是否还活着（destroy 后置 false，投递直接失败而不是空等）。 */
    @Volatile private var a11yAlive = false

    @Volatile private var ctx: Context? = null

    /** 本地规则（含紧急暂停状态）。第一次用时按需创建。 */
    @Volatile private var policyStore: ClientPolicyStore? = null

    /** 最近一次挑战的处理结果（诊断/界面用）。 */
    @Volatile var lastChallenge: String = ""
        private set

    fun init(context: Context): ClientPolicyStore {
        val app = context.applicationContext
        ctx = app
        return policyStore ?: synchronized(this) {
            policyStore ?: ClientPolicyStore(app).also { policyStore = it }
        }
    }

    fun policies(): ClientPolicyStore = policyStore ?: init(ctx ?: error("GuardHealth 未初始化"))

    // ---------- 无障碍生命周期（由 GuardService 调用） ----------

    /** 无障碍连上：换新的 instanceId（T11），并把 a11y 投递口挂上。 */
    fun onA11yConnected(owner: Any, handler: Handler? = null, nowMono: Long = mono()): String {
        // 投递目标**必须**是真正承担无障碍执行的那条线程（T02）。
        val target = handler ?: Handler(Looper.getMainLooper())
        a11yAlive = true
        a11yPost = { runnable ->
            try {
                if (!a11yAlive) {
                    // 服务已经销毁：投进去也不会被执行，直接判「没连上」，别让 Binder 线程空等。
                    false
                } else {
                    target.post(runnable)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "投递到 a11y 主线程失败", e)
                false
            }
        }
        val id = ledger.onConnected(nowMono)
        runCatching { Diag.log(context(), "health_connected", "新的无障碍实例：${id.take(8)}") }
        // 一被系统挂载就落盘记下「这个服务确实被运行过」。
        //
        // 为什么放在这里而不是等 HEALTHY：真机上最常见的坏时机是「装完新版本，系统顺手把
        // 无障碍从列表里摘掉」，那时进程刚重启、一次 HEALTHY 都还没跑到。如果只在 HEALTHY
        // 时记，这种摘除就会被判成「用户还没开启」而永远不修。
        // 连接成功是这件事最早的可靠证据。
        runCatching {
            context().getSharedPreferences("selfheal", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("ever_ran", true).apply()
        }
        return id
    }

    fun onA11yDisconnected() {
        a11yAlive = false
        a11yPost = null
        ledger.onDisconnected()
        runCatching { Diag.log(context(), "health_disconnected", "无障碍断开：旧实例作废，等新实例 + 真 ack") }
    }

    /** 每个可比对的窗口事件登记一次（T03 事件通道）。 */
    fun noteWindowEvent() {
        ledger.noteEvent()
    }

    val instanceId: String? get() = ledger.instanceId

    fun isA11yAttached(): Boolean = a11yPost != null && ledger.instanceId != null

    // ---------- 挑战（T02） ----------

    /**
     * 处理哨兵的 challenge。
     *
     * @return 结果；调用方（provider）只负责把它塞进 Bundle，不做任何判断。
     */
    fun challenge(v: Int, nonce: String, seq: Long, budgetMs: Long): ChallengeOutcome {
        val post = a11yPost
        val pkg = ctx?.packageName ?: "com.zongkong.app"
        val out = handler.handle(
            req = A11yChallengeRequest(v = v, nonce = nonce, seq = seq, budgetMs = budgetMs),
            pkg = pkg,
            instanceId = ledger.instanceId,
            post = post ?: { false },
            mono = mono,
            facts = { facts() },
            onHandled = { at ->
                // 真的在 a11y 线程上处理完了：这时候才算一次 dispatcherAck。
                ledger.noteAck(seq = seq, nowMono = at)
            },
            onFailed = { reason ->
                ledger.noteFailure(reason)
                lastChallenge = reason
                runCatching { Diag.log(context(), "challenge_fail", reason) }
            },
        )
        if (out is ChallengeOutcome.Ack) lastChallenge = "ok@${out.handledAtMono}"
        return out
    }

    private fun facts(): AckFacts {
        val st = policyStore
        return AckFacts(
            ruleRevision = st?.revision ?: 0,
            lastReceiptState = ledger.lastReceiptState,
            lastReceiptAtMono = ledger.lastReceiptAtMono,
            eventSeq = ledger.lastEventSeq,
            eventAtMono = ledger.lastEventAtMono,
        )
    }

    // ---------- 状态 ----------

    fun verdict(permissionEnabled: Boolean, cfg: HealthConfig = HealthConfig()): HealthVerdict =
        ledger.verdict(permissionEnabled = permissionEnabled, installed = true, cfg = cfg)

    fun status(): HealthStatus = verdict(permissionEnabled = permissionEnabled()).status

    fun permissionEnabled(): Boolean = try {
        val c = ctx ?: return false
        com.zongkong.app.ui.PermissionStatus.accessibility(c)
    } catch (e: Throwable) {
        Log.w(TAG, "读无障碍开关失败", e)
        false
    }

    /** 带上下文的版本（调用方没有先 init 时也能用）。 */
    fun permissionEnabled(context: Context): Boolean = try {
        init(context)
        com.zongkong.app.ui.PermissionStatus.accessibility(context)
    } catch (e: Throwable) {
        Log.w(TAG, "读无障碍开关失败", e)
        false
    }

    /** 界面用：一行如实的状态说明。 */
    fun describe(): String {
        val v = verdict(permissionEnabled())
        return "${HealthLabels.status(v.status)}（${v.reason}）"
    }

    /** 锁屏回执登记（本地执行完必须记，界面与哨兵都看这个）。 */
    fun noteReceipt(r: LockReceipt) {
        ledger.noteReceipt(r.state.name, mono())
    }

    fun config(): HealthConfig = HealthConfig()

    /** 本地规则版本变化后同步给哨兵（由 SelfHeal 的循环调用）。 */
    fun noteRuleRevision(rev: Int) = ledger.setRuleRevision(rev)

    fun markRegistered(ok: Boolean) = ledger.setRegistered(ok)

    fun setPaused(paused: Boolean) = ledger.setPaused(paused)

    /** 恢复请求已发出：只记事实，不算修好（T11）。 */
    fun beginRecovery(maxRecoveries: Int = 2): Boolean {
        val ok = ledger.beginRecovery(maxRecoveries, mono())
        if (ok) runCatching { Diag.log(context(), "recover_requested", "恢复请求已发出（等新实例 + 真 ack，最多 $maxRecoveries 次）") }
        return ok
    }

    fun endRecovery() = ledger.endRecovery()

    /** 内核说该恢复了？ */
    fun shouldStartRecovery(): Boolean = ledger.shouldStartRecovery(verdict(permissionEnabled()))

    fun escalates(): Boolean = verdict(permissionEnabled()).action == HealthAction.ESCALATE

    fun context(): Context = ctx ?: error("GuardHealth 未初始化")

    fun hasContext(): Boolean = ctx != null

    fun resetForTest() {
        a11yPost = null
    }
}
