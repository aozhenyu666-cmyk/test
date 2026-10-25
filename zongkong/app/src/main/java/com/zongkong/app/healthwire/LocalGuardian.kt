package com.zongkong.app.healthwire

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.SentinelClient
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.health.LockReceipt
import com.zongkong.app.health.LockState

/**
 * 本地守卫：把「本地规则 + 有限兜底 + 紧急暂停」接进总控的循环里。
 *
 * 责任边界（报告 §8 末段 / §6 / 规范 §5.3、§6）：
 *  - 哨兵在岗 → 不抢着锁，交给哨兵；
 *  - 哨兵失联、自己健康 → 按本地规则**有限**兜底（同一 incidentId 最多 1 次，网络与模型都不参与）；
 *  - 用户按了紧急暂停 → **立刻**停掉自己的自动锁屏（本地可达，不等任何远端确认）；
 *  - 每次锁屏都产生回执（policyId/incidentId/requestId/source/reason）并上报哨兵。
 */
object LocalGuardian {

    private const val TAG = "LocalGuardian"

    private val mono: () -> Long = { SystemClock.elapsedRealtime() }

    private val fallback = LocalFallback(source = "zongkong")

    /** 上次巡检的时刻（执行器不跑在无障碍 Handler 上，用系统单调时钟自己控节奏）。 */
    @Volatile private var lastTickMono = 0L

    /** 当前事件键：哨兵失联这一件事（同一件事不重复锁）。 */
    private const val INCIDENT_SENTINEL_DOWN = "sentinel-unreachable"

    /** 最近一次回执（界面/诊断用）。 */
    @Volatile var lastReceipt: LockReceipt? = null
        private set

    /** 最近一次兜底判定说明（给状态页说人话）。 */
    @Volatile var lastDecision: String = ""
        private set

    /**
     * 从无障碍线程/常驻服务里调用：**只投递，不阻塞**。
     *
     * 报告 §2.1 / T05 要求调度循环不能跑在 AccessibilityService 的 Handler 上：
     * 锁屏要走 `DevicePolicyManager.lockNow()`（跨进程），在 a11y 线程上做同步调用，
     * 一旦系统卡住就会把「无障碍执行线程」自己冻住——那是最不能出事的一条线程。
     */
    fun tick(context: Context, nowWall: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        runCatching { exec.execute { runCatching { tickNow(app, nowWall) } } }
    }

    /** 后台单线程：锁屏与回执上报都在这里，a11y 线程只负责投递。 */
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-guardian").apply { isDaemon = true }
    }

    private fun tickNow(context: Context, nowWall: Long) {
        val nowMono = mono()
        if (nowMono - lastTickMono < 15_000L) return
        lastTickMono = nowMono
        val store = GuardHealth.init(context)
        val policies = store.policies
        if (policies.isEmpty()) {
            lastDecision = "没有本地规则"
            return
        }
        // 紧急暂停：本地立刻生效，不问网络/模型。
        if (store.emergencyStopped()) {
            GuardHealth.setPaused(true)
            lastDecision = "紧急暂停生效中：不会自动锁屏"
            return
        }
        GuardHealth.setPaused(false)
        val v = GuardHealth.verdict(GuardHealth.permissionEnabled())
        val selfHealthy = v.status == HealthStatus.HEALTHY
        // 只读缓存（不跨进程等）：缓存由后台 [refresh] 更新，避免把 a11y 线程挂在 Binder 上（T05）。
        SentinelClient.probeAsync(context)
        val sentinel = SentinelClient.linkCached(context)
        val sentinelHealthy = sentinel == SentinelClient.Link.RESPONDING
        val attempt = fallback.decide(
            policies = policies,
            nowWall = nowWall,
            nowMono = nowMono,
            sentinelHealthy = sentinelHealthy,
            selfHealthy = selfHealthy,
            emergencyStopped = false,
            incidentKey = INCIDENT_SENTINEL_DOWN,
        )
        lastDecision = attempt.reason
        val req = attempt.request ?: return
        val receipt = LocalLockExecutor.lock(context, req, nowWall)
        fallback.record(req, receipt.state, nowMono)
        GuardHealth.noteReceipt(receipt)
        lastReceipt = receipt
        runCatching { Diag.log(context, "local_lock", "${receipt.state}：${receipt.note}（${receipt.reason}）") }
        // 回执上报哨兵（失败不改锁屏结论：本地已经执行并观测过了）。
        val reported = SentinelClient.reportReceipt(context, receipt)
        if (!reported) {
            runCatching { Diag.log(context, "receipt_unsent", "回执没送到哨兵：${receipt.incidentId}") }
        }
        if (receipt.state != LockState.OBSERVED_LOCKED) {
            // 没观测到锁上：在通知里说清楚，不假装成功。
            runCatching {
                com.zongkong.app.system.Notifications.alert(
                    context,
                    "锁屏没被确认",
                    "${receipt.note}。这不等于已经锁上。",
                    "settings/perm",
                )
            }
        }
    }

    /**
     * 紧急暂停（报告 §3.6 / 规范 §6）：本地一键、立即生效、记录原因与时间，然后尽量上报哨兵。
     *
     * **不**以写够字数、等两小时、AI 判断为前提；和「正常提前结束监管」（Release.kt 的冷静期）
     * 完全分开，互不替代。
     */
    fun emergencyStop(context: Context, reason: String, nowWall: Long = System.currentTimeMillis()): String {
        val store = GuardHealth.init(context)
        val rev = store.markEmergencyStopped(reason, nowWall)
        GuardHealth.setPaused(true)
        GuardHealth.noteRuleRevision(rev)
        runCatching { Diag.log(context, "emergency_stop", "本地紧急暂停：${reason.ifBlank { "未填原因" }}") }
        // 上报哨兵：同步规则 + 记一次回执式通告；失败就明说「已本地生效，但哨兵未确认」。
        val (ok, accepted) = SentinelClient.syncPolicy(context, store.policyJson())
        if (ok) {
            store.markEmergencyReported()
            return "已紧急暂停：本地立即生效；哨兵已接受（规则版本 $accepted）"
        }
        return "已紧急暂停：本地立即生效；哨兵未确认（可能失联，规则仍已落盘）"
    }

    /** 解除紧急暂停。 */
    fun resume(context: Context): String {
        val store = GuardHealth.init(context)
        val rev = store.clearEmergency()
        GuardHealth.setPaused(false)
        GuardHealth.noteRuleRevision(rev)
        runCatching { Diag.log(context, "emergency_resume", "解除紧急暂停，恢复监管（规则版本 $rev）") }
        val (ok, accepted) = SentinelClient.syncPolicy(context, store.policyJson())
        return if (ok) "已恢复监管；哨兵已接受（规则版本 $accepted）" else "已恢复监管；哨兵未确认"
    }

    /** 保存规则并同步哨兵（先落盘、再同步）。 */
    fun savePolicies(context: Context, list: List<LocalPolicy>): String {
        val store = GuardHealth.init(context)
        val rev = store.save(list)
        GuardHealth.noteRuleRevision(rev)
        val (ok, accepted) = SentinelClient.syncPolicy(context, store.policyJson())
        return if (ok) "已保存并同步（本地版本 $rev，哨兵接受 $accepted）"
        else "已保存到本机（版本 $rev）；哨兵没确认，仍以本地规则为准"
    }

    fun store(context: Context): ClientPolicyStore = GuardHealth.init(context)

    fun resetForTest() {
        lastTickMono = 0
        lastReceipt = null
        lastDecision = ""
    }

    fun nowMono(): Long = mono()

    init {
        Log.d(TAG, "本地守卫就绪：规则兜底 + 紧急暂停都在本地判定")
    }
}
