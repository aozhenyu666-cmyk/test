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

    /**
     * 修复宽限（T15）：锁屏确认之后的一段时间内不重锁，别把用户挡在修权限的路上。
     *
     * 与 `LockArbiter` 的分工：仲裁防「同一事件反复锁」；这里防「用户正在修权限时被打扰」。
     * 两件事不能互相替代 —— 仲裁次数用完之后仍可能换个事件再锁。
     */
    private val repairGrace = RepairGrace(defaultGraceMs = com.zongkong.app.health.HealthConfig().repairGraceMs)

    /**
     * 巡检核心：**`tickNow()` 与测试驱动的是同一个实例、同一份 `tick(...)` 逻辑**（t14）。
     *
     * 以前 `tickNow` 自己内联着整段判定，测试只能手搓 `fallback.decide(...)` 并自己传参，
     * 于是「宽限到底有没有被传下去」这条接线根本没被覆盖 —— 把 bug 注入回去测试照样全绿。
     */
    private val core = GuardTickCore(
        fallback = fallback,
        grace = repairGrace,
        incidentKey = INCIDENT_SENTINEL_DOWN,
    )

    /** 修复宽限到期时刻（对外只读，便于诊断与单测断言）。 */
    val repairGraceUntilMono: Long get() = core.repairGraceUntilMono

    // ---------- 状态（由 core 持有，这里只转发，避免两份真相） ----------

    /** 最近一次回执（界面/诊断用）。 */
    val lastReceipt: LockReceipt? get() = core.lastReceipt

    /** 最近一次兜底判定说明（给状态页说人话）。 */
    val lastDecision: String get() = core.lastDecision

    /** 累计真正执行过几次锁屏（诊断/单测用）。 */
    val lockCount: Int get() = core.lockCount

    /** 当前事件键：哨兵失联这一件事（同一件事不重复锁）。 */
    private const val INCIDENT_SENTINEL_DOWN = "sentinel-unreachable"

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

    /**
     * 生产环境的 ports：把 tickNow 原本内联的每一次外部调用原样搬进来。
     *
     * 这里**只做接线**，不含任何判定逻辑 —— 判定全在 [GuardTickCore.tick] 里，
     * 测试驱动的也是那同一份。
     */
    private fun ports(context: Context): GuardTickPorts = GuardTickPorts(
        mono = mono,
        policies = { GuardHealth.init(context).policies },
        emergencyStopped = {
            val stopped = GuardHealth.init(context).emergencyStopped()
            GuardHealth.setPaused(stopped)
            stopped
        },
        selfHealthy = {
            GuardHealth.verdict(GuardHealth.permissionEnabled()).status == HealthStatus.HEALTHY
        },
        probeSentinel = { SentinelClient.probeAsync(context) },
        sentinelHealthy = { SentinelClient.linkCached(context) == SentinelClient.Link.RESPONDING },
        keyguardLocked = {
            runCatching { LocalLockExecutor.facts(context) }.getOrNull()?.let {
                it.deviceLocked || it.keyguardShowing
            }
        },
        lock = { req, nowWall -> LocalLockExecutor.lock(context, req, nowWall) },
        reportReceipt = { receipt ->
            GuardHealth.noteReceipt(receipt)
            SentinelClient.reportReceipt(context, receipt)
        },
        notifyUnverified = { receipt ->
            runCatching {
                com.zongkong.app.system.Notifications.alert(
                    context,
                    "锁屏没被确认",
                    "${receipt.note}。这不等于已经锁上。",
                    "settings/perm",
                )
            }
            Unit
        },
        diag = { kind, detail -> runCatching { Diag.log(context, kind, detail) } },
    )

    private fun tickNow(context: Context, nowWall: Long) {
        // 全部判定与副作用编排都在 core.tick 里；这里只提供生产端口的实现。
        core.tick(ports(context), nowWall)
    }

    /**
     * T15：锁屏之后起修复宽限。**纯函数**，便于单测直接钉住"生产代码真的计算并写入这个时刻"。
     *
     * 退化策略说明（不要假装能精确感知解锁）：
     *  - 理想信号是 Keyguard 的 locked→unlocked 跳变，由 [observeKeyguard] 处理；
     *  - 但巡检是 15 秒一个 tick，锁屏→解锁→进设置常常整个落在两个 tick 之间，**观测不到**；
     *  - 所以只要这次锁屏确实发生过（见 [RepairGrace.armAfterReceipt]），就**立即**开始计宽限。
     *    代价是「本该锁的时候少锁一次」，远小于「把用户挡在修复权限的界面外面」。
     *  - 真机必须验证：宽限是否够用户走完 设置→无障碍→关开 的路程。
     *
     * @return 新的宽限到期时刻（单调）；0 = 这次不起宽限。
     */
    internal fun armGraceAfterLock(
        grace: RepairGrace,
        policies: List<LocalPolicy>,
        receiptState: LockState,
        nowWall: Long,
        nowMono: Long,
        fallbackGraceMs: Long = com.zongkong.app.health.HealthConfig().repairGraceMs,
    ): Long = GuardTickCore.armGraceAfterLock(grace, policies, receiptState, nowWall, nowMono, fallbackGraceMs)

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
        // 用户主动恢复监管：清掉修复宽限，立刻按真实事实重新判断（一手 :284 同口径）。
        repairGrace.clear()
        runCatching { Diag.log(context, "emergency_resume", "解除紧急暂停，恢复监管（规则版本 $rev）；清空修复宽限") }
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
        core.resetAll()
    }

    /** 单测/诊断：当前修复宽限的说明。 */
    fun repairGraceText(): String = repairGrace.describe(mono())

    /** 单测：把宽限状态机暴露出来（只读用途，不改语义）。 */
    fun repairGraceState(): RepairGrace = repairGrace

    /**
     * **测试入口**：用注入的 ports 驱动**生产同一个** [GuardTickCore]（t14）。
     *
     * 关键：这里不是副本 —— `tickNow()` 调的就是 `core.tick(...)`，
     * 所以「宽限有没有被传进 decide」这条接线在这里被真实覆盖。
     * 把 `GuardTickCore.tick` 里那行 `repairGraceUntilMono = grace.untilMono` 改成 0，
     * `RepairGraceWiringTest` 必须变红。
     */
    internal fun tickWithPortsForTest(ports: GuardTickPorts, nowWall: Long): TickOutcome =
        core.tick(ports, nowWall)

    /** 测试用：重置 core（含宽限）。 */
    internal fun resetCoreForTest() = core.resetAll()

    fun nowMono(): Long = mono()

    init {
        Log.d(TAG, "本地守卫就绪：规则兜底 + 紧急暂停都在本地判定")
    }
}
