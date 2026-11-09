package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.pm.ApplicationInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.ccch1mneyyy.dsh_app.loop.LoopRuntime
import io.github.ccch1mneyyy.dsh_app.loop.core.AgentReply
import io.github.ccch1mneyyy.dsh_app.loop.core.AgentWire
import io.github.ccch1mneyyy.dsh_app.loop.core.AtomicJsonFile
import io.github.ccch1mneyyy.dsh_app.loop.core.BackgroundCaps
import io.github.ccch1mneyyy.dsh_app.loop.core.Clocks
import io.github.ccch1mneyyy.dsh_app.loop.core.Command
import io.github.ccch1mneyyy.dsh_app.loop.core.Coordinator
import io.github.ccch1mneyyy.dsh_app.loop.core.CoordinatorConfig
import io.github.ccch1mneyyy.dsh_app.loop.core.EventRequest
import io.github.ccch1mneyyy.dsh_app.loop.core.Gate
import io.github.ccch1mneyyy.dsh_app.loop.core.ProactiveLedgerBook
import io.github.ccch1mneyyy.dsh_app.loop.core.ProactiveScheduler
import io.github.ccch1mneyyy.dsh_app.loop.core.ReplyWindow
import io.github.ccch1mneyyy.dsh_app.loop.core.SchedulerConfig
import io.github.ccch1mneyyy.dsh_app.loop.core.Step
import io.github.ccch1mneyyy.dsh_app.loop.core.Submit
import io.github.ccch1mneyyy.dsh_app.loop.core.VisibleSurface
import io.github.ccch1mneyyy.dsh_app.loop.core.DefianceWindow
import io.github.ccch1mneyyy.dsh_app.loop.core.LockPriority
import io.github.ccch1mneyyy.dsh_app.loop.core.LockRule
import io.github.ccch1mneyyy.dsh_app.loop.core.RuleBook
import io.github.ccch1mneyyy.dsh_app.loop.core.planSnapshot
import io.github.ccch1mneyyy.dsh_app.loop.core.Surface
import io.github.ccch1mneyyy.dsh_app.loop.core.Switches
import io.github.ccch1mneyyy.dsh_app.loop.core.SurfaceReport
import io.github.ccch1mneyyy.dsh_app.loop.core.WakeMode
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.phone.PhoneTasks
import io.github.ccch1mneyyy.dsh_app.phone.a11y.FrontChange
import io.github.ccch1mneyyy.dsh_app.phone.a11y.NowFeed
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.loop.intercept.InterceptRuntime
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/** Carries agent commands to the engine. Implemented over Flutter (Dart holds the engine cookie). */
interface AgentPort {
    /** POST /request (also used to resume: the plugin replays an existing requestId without resending). */
    fun request(requestId: String, body: Map<String, Any?>)
    /** GET /result long poll for [requestId]; answer through [ProactiveRuntime.onAgentHttp]. */
    fun poll(requestId: String)
    fun cancel(requestId: String)

    /**
     * One arbitrary plugin route, for the settings page's session read/config write. Separate from
     * the three agent ops so the agent pipeline's semantics cannot be confused with a one-off
     * read; both still go through the same authenticated Dart connection.
     */
    fun requestVia(requestId: String, path: String, body: Map<String, Any?>) = request(requestId, body)
    fun pollVia(requestId: String, path: String) = poll(requestId)
}

/** A notification surface (DeepSeek T3). Must report through [ProactiveRuntime.onSurface] with the token. */
interface NotificationPort {
    fun post(token: String, text: String, deadline: Long): Boolean
    fun cancel(token: String)
    fun cancelAll()
}

/**
 * Android adapter for the proactive kernel (loop/core). Owns one [Coordinator] and one
 * [ProactiveScheduler] per process, builds the [Gate] from DSH's own state (read only), runs the
 * kernel's commands, and keeps exactly one timer: a main-thread callback at the coordinator's next
 * deadline, plus one alarm at the scheduler's next decision (none when the system forbids it).
 *
 * Main thread only.
 */
object ProactiveRuntime {
    private const val TAG = "DshProactive"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var coordinator: Coordinator? = null
    private var scheduler: ProactiveScheduler? = null
    /** A. 主动交流账: its own file, its own counters; 行为拦截账 lives in InterceptRuntime. */
    private var ledger: ProactiveLedgerBook? = null
    @Volatile var agentPort: AgentPort? = null
    @Volatile var notificationPort: NotificationPort? = null
    private val deadlineTick = Runnable { tick("deadline") }
    private val taskListener: (PhoneTasks.Snapshot) -> Unit = { main.post { refreshGate("dsh_task") } }
    private var listening: PhoneTasks? = null
    private var lastOpportunistic = 0L

    /**
     * The companion's last /plan answer. When it is present the plugin is the only scheduler: the
     * phone side's random gap, daily cap, reply defer and ignore backoff stay dormant (they are the
     * fallback for "plugin unreachable" only). [speak] at [at] means one attempt, nothing earlier.
     */
    private data class CompanionPlan(val speak: Boolean, val at: Long, val reason: String, val receivedAt: Long)
    private var companionPlan: CompanionPlan? = null
    private var lastPlanError: String? = null

    /** Agent commands that arrived before Dart connected; see [holdAgent]. */
    private data class HeldAgent(val requestId: String, val body: Map<String, Any?>?, val deadline: Long)
    private val heldAgent = LinkedHashMap<String, HeldAgent>()

    /** How often the adapter retries a held agent command while Flutter is still starting. */
    private const val AGENT_RETRY_MS = 5_000L

    /** How long a one-off plugin route may take before the page is told it timed out. */
    private const val ROUTE_TIMEOUT_MS = 12_000L
    /** How long one line in the overlay may wait for 大肥鱼's answer. */
    private const val SAY_DEADLINE_MS = 120_000L
    /** Each long poll stays under the 12 s route timeout. */
    private const val SAY_POLL_WAIT_MS = 8_000L
    /** How long a 锁 / 赶 holds without a fresh /plan saying so again. */
    private const val FORCE_HOLD_MS = 3 * 60_000L
    /**
     * R4-0 去抖：上一次连麦舱当场锁的包与时刻。只滤掉同一个 App 启动时连发的几个窗口事件，
     * 窗口很短：他从锁页经最近任务切回同一个 App，过了这几秒照样再锁。
     */
    @Volatile private var forcedApp: String? = null
    @Volatile private var forcedAt: Long = 0L
    private const val SESSION_LOCK_DEBOUNCE_MS = 2_000L
    private const val REFUSE_PREFS = "dsh_companion_refuse"
    /** A plan older than its own next-check time by this much is stale; fall back to the local scheduler. */
    private const val PLAN_STALE_MS = 15 * 60_000L

    fun ensure(context: Context): Coordinator {
        coordinator?.let { return it }
        app = context.applicationContext
        ProactiveOverlay.install(app)
        // Load 触发测试's switch before anything reads it: if Flutter starts before the accessibility
        // service, an unloaded LoopEngine would read as "off" and applySwitches would cancel live
        // app-enter events with the wrong reason (review r3, startup race).
        runCatching { LoopRuntime.engine(app) }
        val dir = File(File(app.filesDir, "loop"), "proactive")
        // The reply window is kernel policy: 20 minutes, 60 seconds in a debug build so the
        // ACK → ignored → pause chain can be walked by hand on a phone. Read from the manifest
        // flag rather than BuildConfig, which this build does not generate.
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val replyWindow = if (debuggable) ReplyWindow.DEBUG_MS else ReplyWindow.DEFAULT_MS
        val c = Coordinator(
            AtomicJsonFile(File(dir, "coordinator.json")),
            CoordinatorConfig(replyWindowMs = replyWindow),
            clock = System::currentTimeMillis, ids = { UUID.randomUUID().toString() },
        )
        val book = ProactiveLedgerBook(AtomicJsonFile(File(dir, "ledger.json")))
        book.recover()
        val s = ProactiveScheduler(AtomicJsonFile(File(dir, "scheduler.json")), ::minuteOfDay, ::dayOf, ledger = book)
        ledger = book
        coordinator = c
        scheduler = s
        s.recover(clocks())
        dispatch(c.recover(gate()))
        // Round-1 twins whose kernel event closed while no LoopEngine was alive (review r3, A).
        runCatching { LoopRuntime.reconcileWithKernel(c::find) }
        applySwitches()
        watchTasks()
        arm()
        return c
    }

    fun schedulerConfig(): SchedulerConfig = scheduler?.config ?: SchedulerConfig()

    fun setSchedulerConfig(context: Context, cfg: SchedulerConfig) {
        ensure(context)
        scheduler!!.setConfig(cfg, clocks())
        refreshGate("config")
        // 主动搭话 alone still needs foreground readings for the protected-page gate (review r3, C).
        runCatching { LoopRuntime.configChanged() }
        companionConfigChanged()
    }

    /** The plugin's target/config changed: throw away the old plan and ask it again right now. */
    fun companionConfigChanged() {
        companionPlan = null
        lastPlanConsult = 0L
        consultPlanSoon(minGapMs = 0)
    }

    fun proactiveEnabled(): Boolean = scheduler?.config?.enabled == true

    /** The coordinator if this process created it; never creates one. */
    fun kernelOrNull(): Coordinator? = coordinator

    /** Each feature switch cancels only its own events (review r3, B). */
    private fun applySwitches() {
        val c = coordinator ?: return
        val sw = Switches(LoopRuntime.triggerEnabled(), proactiveEnabled())
        for ((source, reason) in sw.disabledSources()) dispatch(c.cancelSource(source, reason))
        // A different test app: app-enter events about the old one are no longer wanted.
        val target = LoopRuntime.triggerTarget()
        if (sw.triggerTest) {
            (c.queued() + listOfNotNull(c.active()))
                .filter { it.request.source == Switches.SOURCE_APP_ENTER && it.request.payload["packageName"] != target }
                .forEach { dispatch(c.cancel(it.eventId, "target_changed")) }
        }
    }

    /** Alarm, boot, app open: one evaluation, no loop. [force] = the user's 「现在试一次」. */
    fun tick(reason: String, force: Boolean = false) {
        val c = coordinator ?: return
        val s = scheduler ?: return
        watchTasks()
        applySwitches()
        dispatch(c.setGate(gate()))
        // P2: while the companion answers /plan, its answer is the whole schedule. The local
        // scheduler still owns the single alarm and the fallback, but it does not get to vote.
        val plan = companionPlan
        if (!force && plan != null && planAuthoritative(plan)) {
            tickByPlan(plan, reason)
            arm()
            Log.d(TAG, "tick $reason by companion plan (${plan.reason})")
            return
        }
        when (val d = s.evaluate(clocks(), force)) {
            is ProactiveScheduler.Decision.Fire -> submit(d.request, fromScheduler = true)
            is ProactiveScheduler.Decision.Wait -> {
                arm()
                // 让 companion 的 nextEligibleAt 决定下一次检查；仍复用这一个 scheduler/alarm。
                if (d.reason != "pending") consultPlanSoon()
            }
        }
        arm()
        Log.d(TAG, "tick $reason")
    }

    /**
     * One alarm tick under the companion's plan: speak at [CompanionPlan.at] exactly once, otherwise
     * just move the alarm. No random gap, no daily cap, no reply defer — those live in the plugin now.
     */
    private fun tickByPlan(plan: CompanionPlan, reason: String) {
        val now = System.currentTimeMillis()
        if (now + 1_000L < plan.at) {
            scheduler?.setNextDueAt(plan.at, clocks())
            return
        }
        if (!plan.speak) {
            // The plugin's own "look again" time has passed (it was, say, quiet-hours end). Ask again
            // rather than inventing a gap here.
            companionPlan = null
            consultPlanSoon(minGapMs = 0)
            return
        }
        // Fire once. Clearing first also covers a coordinator rejection: the next /plan decides.
        companionPlan = null
        if (ballInFlight()) return
        val at = clocks()
        val request = EventRequest(
            eventId = java.util.UUID.randomUUID().toString(),
            source = "SCHEDULER", kind = "random_chat", dedupeKey = "companion:plan",
            priority = schedulerConfig().priority, createdAt = at.wall, ttlMs = schedulerConfig().eventTtlMs,
            needsAgent = true,
            payload = mapOf("plan" to plan.reason),
        )
        submit(request, fromScheduler = true)
        Log.i(TAG, "companion plan fired ($reason, ${plan.reason})")
    }

    /** A scheduler ball is already queued or on its way: a second /plan "speak" must not add another. */
    private fun ballInFlight(): Boolean {
        val c = coordinator ?: return false
        return c.active()?.request?.source == "SCHEDULER" || c.queued().any { it.request.source == "SCHEDULER" }
    }

    /** The plugin is reachable and this answer is still the current one (not a stale minute-old one). */
    private fun planAuthoritative(plan: CompanionPlan): Boolean =
        agentPort != null && plan.receivedAt > 0 && System.currentTimeMillis() <= plan.at + PLAN_STALE_MS

    /** Window events from LoopRuntime: cheap, throttled; the only clock in OPPORTUNISTIC mode. */
    fun onWindowActivity() {
        val now = SystemClock.elapsedRealtime()
        if (coordinator == null || now - lastOpportunistic < 60_000) return
        lastOpportunistic = now
        tick("window")
    }

    fun submit(request: EventRequest, fromScheduler: Boolean = false): Submit {
        val c = coordinator ?: return Submit.Rejected("not_ready")
        val (r, step) = c.submit(request)
        if (fromScheduler) when (r) {
            is Submit.Rejected -> scheduler?.onRejected(r.reason, clocks())
            else -> scheduler?.onSubmitted(request.eventId, clocks())
        }
        dispatch(step)
        return r
    }

    fun refreshGate(reason: String) {
        val c = coordinator ?: return
        watchTasks()
        applySwitches()
        dispatch(c.setGate(gate()))
        Log.d(TAG, "gate $reason")
    }

    fun onSurface(token: String, report: SurfaceReport) {
        val c = coordinator ?: return
        val before = c.active()?.takeIf { it.token == token }
        dispatch(c.onSurface(token, report))
        // 展示回执交给 companion 账本：posted = 送达，shown = 真正显示，dismissed = 用户划掉。
        val agentRequestId = before?.agentRequestId
        if (agentRequestId != null) {
            when (report) {
                SurfaceReport.Posted -> reportOutcome(agentRequestId, "delivered")
                SurfaceReport.Shown -> reportOutcome(agentRequestId, "displayed")
                SurfaceReport.Dismissed -> reportOutcome(agentRequestId, "dismissed")
                else -> Unit
            }
        }
        if (report == SurfaceReport.Shown && before != null) runCatching { LoopRuntime.onKernelShown(c.find(before.eventId)) }
        // 「我看到了」: the scheduler learns about the confirmation (and book A records it) at once,
        // but the attempt stays pending until the reply window decides answered vs ignored. It is
        // deliberately *not* user activity: acknowledging is not answering, so it must not defer
        // the next attempt the way an answer does.
        if (report == SurfaceReport.Acked && before != null) scheduler?.onAcked(before.eventId, clocks())
        if (report is SurfaceReport.Answered || report == SurfaceReport.Dismissed) {
            scheduler?.onUserActivity(clocks())
            // 人的回应会改变 garden 的 nextEligibleAt（2h / 12h / done），马上重新问一次。
            consultPlanSoon()
        }
    }

    /** Fire-and-forget: one delivery receipt for the companion ledger; a missing plugin is harmless. */
    private val reportedOutcomes = mutableSetOf<String>()
    private val ignoreResult = object : io.flutter.plugin.common.MethodChannel.Result {
        override fun success(result: Any?) {}
        override fun error(code: String, message: String?, details: Any?) {}
        override fun notImplemented() {}
    }

    private fun reportOutcome(requestId: String, stage: String) {
        if (requestId.isBlank() || !reportedOutcomes.add(requestId + ":" + stage)) return
        fetchPluginRoute(ignoreResult, "POST", "/outcome", linkedMapOf("requestId" to requestId, "stage" to stage))
    }

    /** 「别烦我 1 小时」: the plugin's own mute, so /plan stops saying speak until it ends. */
    fun muteFor(ms: Long) {
        fetchPluginRoute(
            ignoreResult, "POST", "/config",
            linkedMapOf("v" to 1, "muteUntil" to (System.currentTimeMillis() + ms.coerceIn(60_000L, 24 * 3_600_000L))),
        )
        companionPlan = CompanionPlan(false, System.currentTimeMillis() + ms, "muted", System.currentTimeMillis())
        arm()
    }

    /**
     * 浮层里直接聊: hand one line to the companion's /say (it goes into the session as the person's
     * own words), then long-poll /result until 大肥鱼 has answered. [done] runs on the main thread
     * with the reply, or with an error code (agent-busy, timeout, no_transport…).
     */
    fun say(text: String, done: (reply: String?, error: String?) -> Unit) {
        val id = "say-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val deadline = System.currentTimeMillis() + SAY_DEADLINE_MS
        fetchPluginRoute(routeCallback { m ->
            val error = ((m["error"] as? Map<*, *>)?.get("code") as? String)
            if (error != null) done(null, error) else pollSay(id, deadline, done)
        }, "POST", "/say", linkedMapOf("v" to 1, "requestId" to id, "text" to text, "timeZone" to TimeZone.getDefault().id))
    }

    private fun pollSay(id: String, deadline: Long, done: (String?, String?) -> Unit) {
        if (System.currentTimeMillis() > deadline) { done(null, "timeout"); return }
        fetchPluginRoute(routeCallback { m ->
            val error = ((m["error"] as? Map<*, *>)?.get("code") as? String)
            when (m["state"]) {
                "generated" -> done((m["text"] as? String)?.trim(), null)
                "failed" -> done(null, ((m["failure"] as? Map<*, *>)?.get("code") as? String) ?: "failed")
                "cancelled" -> done(null, "cancelled")
                // Still thinking (accepted / claimed), or this poll's own transport timed out: ask again.
                else -> if (error != null && error != "timeout" && error != "no_answer") done(null, error)
                else main.postDelayed({ pollSay(id, deadline, done) }, 300)
            }
        }, "GET", "/result?requestId=$id&waitMs=$SAY_POLL_WAIT_MS", null)
    }

    private fun routeCallback(on: (Map<*, *>) -> Unit) = object : io.flutter.plugin.common.MethodChannel.Result {
        override fun success(result: Any?) { main.post { on(result as? Map<*, *> ?: emptyMap<Any, Any>()) } }
        override fun error(code: String, message: String?, details: Any?) { main.post { on(mapOf("error" to mapOf("code" to code))) } }
        override fun notImplemented() { main.post { on(mapOf("error" to mapOf("code" to "not_implemented"))) } }
    }

    private var lastPlanConsult = 0L
    private val planResult = object : io.flutter.plugin.common.MethodChannel.Result {
        override fun success(result: Any?) {
            main.post { applyPlan(result) }
        }
        override fun error(code: String, message: String?, details: Any?) {
            main.post {
                // No /plan: the local scheduler is the fallback again, honestly labelled.
                lastPlanError = code
                companionPlan = null
                arm()
            }
        }
        override fun notImplemented() {
            main.post { lastPlanError = "not_implemented"; companionPlan = null; arm() }
        }
    }

    /**
     * Ask the companion when the next topic is eligible and move the single scheduler alarm there.
     * This is the only bridge between garden.nextEligibleAt and AlarmManager; no second timer.
     */
    private fun consultPlanSoon(minGapMs: Long = 60_000L) {
        if (agentPort == null) return
        val now = System.currentTimeMillis()
        if (now - lastPlanConsult < minGapMs) return
        lastPlanConsult = now
        // The engine runs on TZ=GMT; quiet hours and the daily reset are the phone's wall clock,
        // so the phone's own zone goes with the request. The plugin falls back to Asia/Shanghai.
        fetchPluginRoute(
            planResult,
            "POST",
            "/plan",
            // 不带 roll：骰子由插件自己掷。以前带 roll=100（只拿 /plan 定时间时的写法），
            // 在"插件说开口才开口"之后等于永远掷不中，大肥鱼就不主动说话了。
            linkedMapOf<String, Any?>("timeZone" to TimeZone.getDefault().id)
                .also { body -> phoneContext()?.let { body["phone"] = it } },
        )
    }

    /** The companion's last 锁 / 赶, kept for a short while so re-entering the app meets it at once. */
    private data class Force(val level: Int, val app: String, val why: String?, val until: Long, val refuse: Boolean = false)
    private var force: Force? = null

    /**
     * 拒绝模式 until this wall time (from /plan). While it runs the phone locks a listed app the
     * moment it comes to the front, without waiting for a /plan round trip. Kept in prefs so a
     * restarted process still refuses before the first /plan answer.
     */
    private var refuseUntil: Long = -1L
    private fun refuseUntil(): Long {
        if (refuseUntil < 0) refuseUntil = runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).getLong("until", 0L) }.getOrDefault(0L)
        return refuseUntil
    }
    private fun setRefuseUntil(at: Long) {
        if (at == refuseUntil()) return
        refuseUntil = at
        runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit().putLong("until", at).apply() }
        Log.i(TAG, "refuse mode " + if (at > System.currentTimeMillis()) "on until $at" else "off")
    }

    /**
     * C2 本地规则缓存: the plugin's last rule snapshot (拒绝 / 任务 / 早上对线 / 晚上对账 / 深夜锁 /
     * 封禁名单), each with its own expiry, on disk. With the engine gone the phone keeps enforcing
     * exactly these until they run out on the local clock; it never adds a rule of its own.
     */
    private var rules: RuleBook? = null
    private fun ruleBook(): RuleBook? {
        rules?.let { return it }
        if (!::app.isInitialized) return null
        val book = RuleBook(AtomicJsonFile(File(File(File(app.filesDir, "loop"), "proactive"), "rules.json")))
        runCatching { book.recover() }.onFailure { Log.w(TAG, "rules recover", it) }
        rules = book
        return book
    }

    /** Until when some lock is in force: the cached refuse time or any cached rule (0 = none). */
    private fun lockUntil(now: Long = System.currentTimeMillis()): Long =
        maxOf(refuseUntil(), ruleBook()?.lockedUntil(now) ?: 0L)

    /**
     * C5 仲裁: of every lock in force, the one whose unlock requirement the lock page shows
     * ([LockPriority.ORDER] is the table). Null when nothing is in force.
     */
    fun primaryLock(now: Long = System.currentTimeMillis()): LockRule? {
        if (!::app.isInitialized || breaking()) return null
        val list = ArrayList(ruleBook()?.active(now).orEmpty())
        val r = refuseUntil()
        if (r > now && list.none { it.until >= r }) list.add(LockRule(LockPriority.KIND_REFUSE, r))
        if (sessionActive()) list.add(LockRule(LockPriority.KIND_SESSION, sessionUntil, null))
        return LockPriority.primary(list)
    }

    /** The one line the lock page shows for the primary lock. */
    private fun lockWhy(now: Long = System.currentTimeMillis()): String? =
        primaryLock(now)?.let { it.why ?: LockPriority.defaultWhy(it.kind) }

    /** Temporary block list: the last /plan answer plus the cached snapshot (survives restarts). */
    private fun blockedNow(): Set<String> = blocked + (ruleBook()?.blocked(System.currentTimeMillis()) ?: emptySet())

    /** Whether a lock in force right now would lock [pkg] (the lock page asks before superseding). */
    fun wouldLock(pkg: String): Boolean {
        if (!::app.isInitialized || breaking() || !listed(pkg)) return false
        val now = System.currentTimeMillis()
        return now < lockUntil(now) || sessionActive() || (force?.app == pkg && now < (force?.until ?: 0L))
    }

    private fun listed(pkg: String): Boolean {
        if (pkg in NEVER_LOCK) return false
        // Gemini 互动舱：只许白名单里的 Gemini，其他 App 一律算"要拦的"。
        if (sessionActive()) return !sessionAllows(pkg)
        if (pkg in blockedNow()) return true
        return runCatching { InterceptRuntime.config().targets.any { it.packageName == pkg } }.getOrDefault(false)
    }

    /** 电话、短信、地图、支付：任何模式都不锁（真急事的底线）。 */
    private val NEVER_LOCK = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.android.phone", "com.android.server.telecom", "com.android.mms",
        "com.google.android.apps.messaging", "com.android.contacts", "com.autonavi.minimap", "com.baidu.BaiduMap",
        "com.google.android.apps.maps", "com.tencent.map", "com.eg.android.AlipayGphone", "com.unionpay",
    )

    /** 桌面 / 系统界面：连麦舱里不算"别的 App"，否则他连回 Gemini 的那一下都点不到。 */
    private val NEUTRAL = setOf(
        "android", "com.android.systemui", "com.miui.home", "com.google.android.apps.nexuslauncher",
        "com.android.launcher3", "com.oppo.launcher", "com.huawei.android.launcher",
        "com.sec.android.app.launcher", "com.vivo.launcher", "com.bbk.launcher2",
    )

    /** 插件的临时封禁名单（/plan 的 block）：这些也当娱乐 App 管。 */
    @Volatile private var blocked: Set<String> = emptySet()

    /** 通知防波堤开到什么时候（/plan 的 shield）；存 prefs，App 进程重启后通知服务也知道。 */
    @Volatile private var shieldUntil: Long = -1L
    fun shieldActive(): Boolean {
        if (shieldUntil < 0) shieldUntil = runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).getLong("shield", 0L) }.getOrDefault(0L)
        return System.currentTimeMillis() < shieldUntil
    }
    private fun setShieldUntil(at: Long) {
        if (at == shieldUntil) return
        shieldUntil = at
        runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit().putLong("shield", at).apply() }
    }

    /** 破窗放风到什么时候（/event breakglass 回的 breakUntil）；存 prefs，进程重启后仍然放过。 */
    @Volatile private var breakUntil: Long = -1L
    fun breaking(): Boolean {
        if (!::app.isInitialized) return false
        if (breakUntil < 0) breakUntil = runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).getLong("break", 0L) }.getOrDefault(0L)
        return System.currentTimeMillis() < breakUntil
    }
    private fun setBreakUntil(at: Long) {
        if (at == breakUntil) return
        breakUntil = at
        runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit().putLong("break", at).apply() }
    }

    /**
     * Gemini 互动舱（/plan 的 session）：连麦期间只许 allow 里的包名。
     * 存 prefs，App 进程重启后锁还在；到点自己失效。
     */
    @Volatile private var sessionUntil: Long = -1L
    @Volatile private var sessionAllow: Set<String> = emptySet()
    @Volatile private var sessionPkg: String? = null
    private fun loadSession() {
        if (sessionUntil >= 0) return
        val p = app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE)
        sessionUntil = runCatching { p.getLong("sessionUntil", 0L) }.getOrDefault(0L)
        sessionAllow = runCatching { p.getString("sessionAllow", "").orEmpty() }.getOrDefault("")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        sessionPkg = runCatching { p.getString("sessionApp", "").orEmpty() }.getOrDefault("").ifBlank { null }
        sessionSince = runCatching { p.getLong("sessionSince", 0L) }.getOrDefault(0L)
    }
    fun sessionActive(): Boolean {
        if (!::app.isInitialized) return false
        loadSession()
        return System.currentTimeMillis() < sessionUntil
    }
    fun sessionApp(): String? {
        if (!::app.isInitialized) return null
        loadSession()
        return sessionPkg ?: sessionAllow.firstOrNull()
    }
    private fun sessionAllows(pkg: String): Boolean {
        loadSession()
        if (pkg in sessionAllow) return true
        // 桌面 / 系统界面 / DSH 自己不算"别的 App"，不然他连"回 Gemini"都点不到。
        if (pkg in NEUTRAL) return true
        // R4-0: 用户自己的放行名单在连麦舱期间同样作数（原来只读插件白名单，所以"放行都不行"）。
        if (runCatching { pkg in InterceptRuntime.config().whitelist }.getOrDefault(false)) return true
        return pkg == app.packageName || pkg == app.packageName + ".fish"
    }
    private fun setSession(until: Long, allow: Set<String>, pkg: String?) {
        // 连麦开始时间：手机要在结束时报 gemini-end 的 minutes，而插件的 until 是我们唯一的时间来源。
        if (until > 0 && sessionUntil <= 0) sessionSince = System.currentTimeMillis()
        if (until <= 0) sessionSince = 0L
        sessionUntil = until
        sessionAllow = allow
        sessionPkg = pkg
        runCatching {
            app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("sessionUntil", until)
                .putLong("sessionSince", sessionSince)
                .putString("sessionAllow", allow.joinToString(","))
                .putString("sessionApp", pkg.orEmpty())
                .apply()
        }
    }

    @Volatile private var sessionSince: Long = 0L

    /** 这一场连麦已经过了多少分钟（结束时报给插件；不知道就 0，插件按"不足 3 分钟"处理）。 */
    private fun sessionMinutes(): Int {
        if (::app.isInitialized) loadSession()
        val since = sessionSince
        if (since <= 0) return 0
        return ((System.currentTimeMillis() - since) / 60_000L).toInt().coerceAtLeast(0)
    }

    /** 管控期间：设置页自卫和破窗只在此时生效（拒绝模式、锁 / 赶、任务模式、防波堤）。 */
    fun controlActive(): Boolean {
        if (!::app.isInitialized) return false
        return System.currentTimeMillis() < lockUntil() || force != null || shieldActive()
    }

    /** NotificationShield 拦下一条：报给插件攒着（不带正文）。 */
    fun onNotificationSwallowed(pkg: String, label: String, title: String, group: Boolean) {
        if (!::app.isInitialized) return
        main.post {
            fetchPluginRoute(routeCallback { }, "POST", "/event", linkedMapOf(
                "type" to "notif", "app" to pkg, "label" to label, "title" to title, "group" to group,
            ))
        }
    }
    // ---------------------------------------------------------------- 第三轮 7 种事件（接口约定 §3）

    /**
     * One `/event` post for the seven round-3 events. The answer decides nothing here: each caller
     * says what to do with it (a lock opened/closed is followed by a fresh `/plan`), so this stays a
     * transport, not a decision point.
     */
    private fun postEvent(type: String, vararg fields: Pair<String, Any?>, then: ((Map<*, *>) -> Unit)? = null) {
        if (!::app.isInitialized || !proactiveEnabled()) return
        val body = linkedMapOf<String, Any?>("type" to type)
        for ((key, value) in fields) if (value != null) body[key] = value
        // onForeground comes off the accessibility thread; routeAnswers is main-thread only.
        main.post { fetchPluginRoute(routeCallback { m -> then?.invoke(m) }, "POST", "/event", body) }
    }

    /** 亮屏：只记账。晨间门开不开由插件按作息判定，所以顺手问一次 `/plan`。 */
    fun onScreenOn() = postEvent("screen-on") { consultPlanSoon(minGapMs = 0L) }

    /**
     * 他打开了被拦的 App：挂着的义务按这次动作再问一次（插件只问一次，做过 `askedAt`）。
     * `followup` 非空说明插件开了追问锁，界面要靠下一次 `/plan` 才拿到，所以立刻问。
     */
    fun onBlockedOpen(pkg: String) = postEvent("blocked-open", "app" to pkg) { m ->
        if (m["followup"] != null) consultPlanSoon(minGapMs = 0L)
    }

    /** 微信里误入视频号 / 朋友圈 / 小说页：只记账，**不升级成违抗**。 */
    fun onWechatHit(page: String, dwellMs: Long) =
        postEvent("wechat-hit", "page" to page, "dwellMs" to dwellMs)

    /** 他点「申请验收」：插件开一把 review 追问锁，回来问 `/plan` 拿新状态。 */
    fun requestReview() = postEvent("request-review") { consultPlanSoon(minGapMs = 0L) }

    /** 他点「开始」：进冲刺，娱乐锁上、通知进防波堤。 */
    fun startSprint(task: String?, minutes: Int, long: Boolean) =
        postEvent("start", "task" to (task ?: ""), "minutes" to minutes, "long" to long) {
            consultPlanSoon(minGapMs = 0L)
        }

    /** 连麦结束：满 `brainMinMin` 分钟插件会开一把 `gemini` 追问锁。 */
    fun onGeminiEnd(minutes: Int, by: String = "phone") =
        postEvent("gemini-end", "minutes" to minutes, "by" to by) { consultPlanSoon(minGapMs = 0L) }

    // ---------------------------------------------------------------- 追问锁（接口约定 §2、§5）

    /**
     * 追问锁到点前，界面靠这三个值决定「窗口不许划走」和「问完回到哪」。
     * 到期时间取 `state.followup.until` 与 `state.until` 里更晚的那个：契约示例里 `followup.until`
     * 可能是 0，那时真正的到期时间在主状态上。
     */
    @Volatile private var followupUntilAt: Long = 0L
    @Volatile private var followupQuestionText: String? = null
    @Volatile private var followupBackToState: String? = null

    /** 已经报过 followup-timeout 的那个 `until`：同一把追问锁只报到点一次。 */
    @Volatile private var followupTimeoutReported: Long = 0L

    /** 追问锁还剩多少（0 = 此刻没有追问锁）。 */
    fun followupUntil(): Long {
        val until = followupUntilAt
        return if (until > System.currentTimeMillis()) until else 0L
    }

    fun followupQuestion(): String? = followupQuestionText

    /** 问完回到哪个主状态（`state.backTo`）；锁页用它说「问完回到哪里」。 */
    fun followupBackTo(): String? = followupBackToState

    // ---------------------------------------------------------------- D7 只读面板

    /**
     * 面板正文（最多三行），每次 `/plan` 重新算一次（见 `Panel.kt`）。
     * 一次 `/plan` 都没拿到时是 null，界面就不画这块。
     */
    @Volatile private var panelTextCache: String? = null

    fun panelText(): String? = panelTextCache

    /** `state`/`layers.kind` 的中文名；锁页的 `backTo` 也用它，免得两处各写一套。 */
    fun stateLabel(kind: String?): String? = stateLabelOf(kind)

    private var lastFront: String? = null

    /**
     * LoopRuntime read a new front app. Every switch is a decision point (that is when the person
     * slides into a game or comes back out): ask the companion now instead of waiting for the alarm,
     * and if it already said lock for this app, lock again before the next minute.
     */
    /** Every foreground switch (not just the throttled /plan ones): restless switching is the tell before a game. */
    private val switches = ArrayDeque<Pair<Long, String>>()

    fun onForeground(packageName: String?) {
        if (packageName.isNullOrBlank() || packageName == lastFront) return
        lastFront = packageName
        val tNow = SystemClock.elapsedRealtime()
        synchronized(switches) {
            switches.addLast(tNow to packageName)
            while (switches.isNotEmpty() && tNow - switches.first().first > 15 * 60_000L) switches.removeFirst()
        }
        if (coordinator == null) return
        // App switches come in bursts; 15 s is enough to not hammer the plugin and still feel immediate.
        // But a restless burst (5 switches in 90 s) is exactly the moment to ask now.
        val restless = synchronized(switches) { switches.count { tNow - it.first <= 90_000L } >= 5 }
        if (restless) consultPlanSoon(minGapMs = 5_000L)
        val now = System.currentTimeMillis()
        // 拒绝模式: lock now, at the door; the /plan answer would come a second later.
        // C2: also with the engine dead — this reads only the cached rules and the local clock.
        if (now < lockUntil(now) && listed(packageName)) {
            val f = Force(4, packageName, lockWhy(now) ?: "拒绝模式", now + FORCE_HOLD_MS, refuse = true)
            force = f
            enforce(f, fresh = true)
            // 打开被拦的 App = 插件再问义务那一次机会（只问一次；重复开关不会每次都堵）。
            onBlockedOpen(packageName)
        }
        consultPlanSoon(minGapMs = 15_000L)
        val f = force ?: return
        if (f.app == packageName && now < f.until && !f.refuse) enforce(f)
    }

    /**
     * C4 违抗复查: after a reminder or a lock page closes, watch 5 minutes; 15 s in an entertainment
     * app in total (going back to the launcher in between does not reset it) is defiance.
     */
    private val defiance = DefianceWindow()
    private val defianceTick = Runnable { sampleDefiance() }
    private const val DEFIANCE_SAMPLE_MS = 1_000L

    /** The chat overlay was closed by him (收起 / 别烦我 / 在忙). */
    fun onOverlayClosed(chatted: Boolean, muted: Boolean, busy: Boolean = false) {
        if (!::app.isInitialized) return
        val front = NowFeed.snapshot(SystemClock.elapsedRealtime()).app
        fetchPluginRoute(routeCallback { }, "POST", "/event", linkedMapOf(
            "type" to "overlay-closed", "chatted" to chatted, "muted" to muted, "busy" to busy, "app" to front,
        ))
        watchDefiance(if (busy) "busy" else "overlay")
    }

    /**
     * 设置页自卫：管控期间他去设置里找"强行停止 / 卸载 / 关无障碍"。插件按规矩清零信用分、
     * 开 60 分钟拒绝模式、叫鲸澜来收尾，并把 refuse / force 一起回给手机照着锁。
     */
    fun reportTamper(what: String, pkg: String) {
        if (!::app.isInitialized) return
        main.post {
            fetchPluginRoute(routeCallback { m ->
                ((m["refuse"] as? Map<*, *>)?.get("until") as? Number)?.toLong()?.let { setRefuseUntil(it) }
                val f = m["force"] as? Map<*, *>
                val level = (f?.get("level") as? Number)?.toInt() ?: 0
                if (level >= 3) {
                    val fx = Force(level, pkg, f?.get("why") as? String, System.currentTimeMillis() + FORCE_HOLD_MS, refuse = true)
                    force = fx
                    enforce(fx)
                }
                consultPlanSoon(minGapMs = 0L)
            }, "POST", "/event", linkedMapOf("type" to "tamper", "what" to what.take(60), "app" to pkg))
        }
    }

    /**
     * 计步破窗：走够步数 + 手打违约说明后换 10 分钟放风。插件把 breakUntil 写进配置，
     * 之后的 /plan 不会再给 force / refuse；手机本地也记一份，到点自动恢复。
     */
    fun breakglass(steps: Int, note: String, done: (ok: Boolean, used: Boolean) -> Unit) {
        if (!::app.isInitialized) { done(false, false); return }
        main.post {
            fetchPluginRoute(routeCallback { m ->
                val until = (m["breakUntil"] as? Number)?.toLong() ?: 0L
                val ok = until > System.currentTimeMillis()
                if (ok) {
                    setBreakUntil(until)
                    force = null
                    releaseCompanionLock()
                    consultPlanSoon(minGapMs = 0L)
                }
                // D5：每天只能破窗一次，第二次插件回 breakglass-used——要跟"没接上插件"分开说。
                val used = ((m["error"] as? Map<*, *>)?.get("code") as? String) == "breakglass-used"
                done(ok, used)
            }, "POST", "/event", linkedMapOf(
                "type" to "breakglass", "minutes" to 10, "steps" to steps, "note" to note.take(300),
            ))
        }
    }

    /**
     * 前台换了：Gemini 互动舱里，非白名单 App 立刻锁，不等下一次 /plan（/plan 有 60 秒节流）。
     */
    fun onFrontChanged(pkg: String?) {
        if (!::app.isInitialized) return
        if (pkg == null || !FrontChange.switched(pkg)) return
        if (!sessionActive()) return
        if (!listed(pkg)) {
            // 离开被拦的 App（回桌面、进 DSH）：去抖清掉，下次再进来要重新锁。
            forcedApp = null
            return
        }
        if (breaking()) return
        val now = System.currentTimeMillis()
        // 去抖：同一个 App 启动时连发的窗口事件只锁一次（输入法已由 FrontChange 排除）。
        if (forcedApp == pkg && now - forcedAt < SESSION_LOCK_DEBOUNCE_MS) return
        forcedApp = pkg
        forcedAt = now
        val f = Force(4, pkg, lockWhy() ?: "Gemini 互动中，只许 Gemini", now + FORCE_HOLD_MS, refuse = true)
        force = f
        enforce(f, fresh = true)
    }

    /** 应用管理页的「Gemini 互动」：开 / 关一场连麦舱（POST /session）。 */
    fun geminiSession(action: String, topic: String?, minutes: Int, mode: String?, done: (Map<*, *>?) -> Unit) {
        if (!::app.isInitialized) { done(null); return }
        // 连麦结束：先按第三轮契约报 gemini-end（满 3 分钟插件会开追问锁），再收本地舱。
        if (action == "end") onGeminiEnd(sessionMinutes())
        main.post {
            fetchPluginRoute(routeCallback { m ->
                val s = m["session"] as? Map<*, *>
                if (action == "end" || s == null) setSession(0L, emptySet(), null)
                else {
                    val until = (s["until"] as? Number)?.toLong() ?: 0L
                    val allow = (s["allow"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
                    setSession(until, allow, s["app"] as? String)
                }
                consultPlanSoon(minGapMs = 0L)
                done(m)
            }, "POST", "/session", linkedMapOf(
                "action" to action, "topic" to (topic ?: ""), "minutes" to minutes,
                "mode" to (mode ?: ""), "by" to "ui",
            ))
        }
    }

    /**
     * Open (or reopen) the defiance window. Called whenever a reminder or a lock page closes, by
     * whichever way (收起、在忙、紧急退出、急事直达、回 Gemini、破窗、放行).
     */
    fun watchDefiance(source: String) {
        if (!::app.isInitialized) return
        defiance.open(SystemClock.elapsedRealtime(), source)
        main.removeCallbacks(defianceTick)
        main.postDelayed(defianceTick, DEFIANCE_SAMPLE_MS)
    }

    /** In front and it counts: listed, screen on, and no pass (破窗放风 / 训练放行 / 白名单 / 互动舱) allows it. */
    private fun defiantIn(pkg: String?, screenOn: Boolean): Boolean {
        if (pkg == null || !screenOn) return false
        if (!listed(pkg) || breaking()) return false
        return runCatching { !InterceptRuntime.hasPass(pkg) }.getOrDefault(true)
    }

    private fun sampleDefiance() {
        if (!defiance.isOpen) return
        val t = SystemClock.elapsedRealtime()
        val snap = NowFeed.snapshot(t)
        val source = defiance.source
        if (defiance.sample(t, defiantIn(snap.app, snap.screenOn))) {
            snap.app?.let { reportDefiance(it, source) }
            return
        }
        if (defiance.isOpen) main.postDelayed(defianceTick, DEFIANCE_SAMPLE_MS)
    }

    private fun reportDefiance(front: String, source: String) {
        if (!proactiveEnabled()) return
        val label = runCatching { InterceptRuntime.config().targets.firstOrNull { it.packageName == front }?.label }.getOrNull() ?: front
        Log.i(TAG, "defiance: ${defiance.limitMs / 1000}s in $front within ${defiance.windowMs / 60_000} min after a close ($source)")
        fetchPluginRoute(routeCallback { m ->
            // The plugin escalates by rule (拒绝模式 on) and answers with it: lock now, don't wait for /plan.
            ((m["refuse"] as? Map<*, *>)?.get("until") as? Number)?.toLong()?.let { setRefuseUntil(it) }
            val f = m["force"] as? Map<*, *>
            val level = (f?.get("level") as? Number)?.toInt() ?: 0
            if (level >= 3) {
                val fx = Force(level, front, lockWhy() ?: f?.get("why") as? String, System.currentTimeMillis() + FORCE_HOLD_MS, refuse = true)
                force = fx
                enforce(fx)
            }
            // 鲸澜 comes in right away (the plugin queued her).
            consultPlanSoon(minGapMs = 0L)
        }, "POST", "/event", linkedMapOf("type" to "defied", "app" to front, "label" to label, "source" to source))
    }

    /** 说到做到: 赶 = back to the launcher first; then 锁 the app behind a drill. */
    private fun enforce(f: Force, fresh: Boolean = false) {
        if (!proactiveEnabled()) return
        if (breaking()) return
        // [fresh]: called with the foreground switch itself; NowFeed may not have caught up yet.
        if (f.app in NEVER_LOCK) return
        val front = NowFeed.snapshot(SystemClock.elapsedRealtime()).app
        if (!fresh && front != f.app) return
        if (f.refuse && VisibleSurface.isHeldBy(VisibleSurface.OWNER_PROACTIVE)) {
            // An overlay still holds the one-screen slot after its event closed (he chatted in it):
            // 拒绝模式 must not lose to that. The overlay window itself stays where it is.
            VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, null)
        }
        if (f.level >= 4) {
            runCatching { PhoneA11yService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) }
                .onFailure { Log.w(TAG, "home", it) }
        }
        val shown = runCatching { InterceptRuntime.forceBlock(app, f.app, f.why, strict = f.refuse) }.getOrDefault(false)
        Log.i(TAG, "companion force=${f.level} ${f.app} shown=$shown")
    }

    /**
     * 讲通了: the companion dropped the force below 3. If the page on screen is the one its own lock
     * opened (never the person's own interception rule), release it and put the app back.
     */
    private fun releaseCompanionLock() {
        runCatching { InterceptRuntime.releaseCompanion(app) }
            .onSuccess { if (it) Log.i(TAG, "companion force dropped: released its lock") }
            .onFailure { Log.w(TAG, "release companion lock", it) }
    }

    private fun applyPlan(raw: Any?) {
        val map = raw as? Map<*, *> ?: return
        lastPlanError = (map["error"] as? Map<*, *>)?.get("code") as? String
        // C2: only a real plan answer (v = 1) may change the rules. Anything else - a gateway error
        // page, an engine that is half up - used to read as "no refuse" and wiped the cached lock.
        if (lastPlanError == null && (map["v"] as? Number)?.toInt() != 1) lastPlanError = "bad-answer"
        if (lastPlanError != null) { companionPlan = null; arm(); return }
        // 力度：插件说"锁 / 赶"就执行（插件只决定，手机执行）；低于 3 就撤掉记着的那一次。
        val f = map["force"] as? Map<*, *>
        val level = (f?.get("level") as? Number)?.toInt() ?: 0
        val target = f?.get("app") as? String
        val why = f?.get("why") as? String
        setRefuseUntil(((map["refuse"] as? Map<*, *>)?.get("until") as? Number)?.toLong() ?: 0L)
        // The full snapshot, each rule with its own expiry, for when the engine is gone (C2).
        // D2: 新引擎只认 state.layers，rules 只为老引擎兜底（见 planSnapshot）。
        val stamp = System.currentTimeMillis()
        ruleBook()?.replace(planSnapshot(map, stamp), stamp)
        setShieldUntil(((map["shield"] as? Map<*, *>)?.get("until") as? Number)?.toLong() ?: 0L)
        blocked = (map["block"] as? List<*>)?.mapNotNull { it as? String }?.filter { it !in NEVER_LOCK }?.toSet() ?: emptySet()
        // Gemini 互动舱：插件给 allow 就进入"只许 Gemini"，null 就退出。
        val s = map["session"] as? Map<*, *>
        if (s == null) setSession(0L, emptySet(), null)
        else {
            val until = (s["until"] as? Number)?.toLong() ?: 0L
            val allow = (s["allow"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
            setSession(until, allow, s["app"] as? String)
        }
        val refusing = System.currentTimeMillis() < lockUntil()
        force = if (level >= 3 && !target.isNullOrBlank()) {
            // C5: while a rule lock is in force the page names only the primary lock's requirement.
            Force(level, target, (if (refusing) lockWhy() else null) ?: why, System.currentTimeMillis() + FORCE_HOLD_MS, refuse = refusing)
        } else null
        // 只在插件明说"讲通了，放行"时解锁。力度低于 3 不算：锁屏页弹出后前台是 DSH 自己、
        // 赶回桌面后前台是桌面，插件看到的力度本来就是 0——那时解锁等于锁几秒就自己打开游戏。
        if (level < 3 && f?.get("release") == true) releaseCompanionLock()
        force?.let { enforce(it) }
        // 连麦舱：插件判的 force 只管娱乐 App；这里把"非 Gemini"的其他 App 一并锁掉。
        if (sessionActive()) {
            val front = NowFeed.snapshot(SystemClock.elapsedRealtime()).app
            if (front != null && FrontChange.switched(front) && listed(front)) {
                val now = System.currentTimeMillis()
                if (forcedApp != front || now - forcedAt >= SESSION_LOCK_DEBOUNCE_MS) {
                    forcedApp = front
                    forcedAt = now
                    val sf = Force(4, front, lockWhy() ?: "Gemini 互动中，只许 Gemini", now + FORCE_HOLD_MS, refuse = true)
                    force = sf
                    enforce(sf, fresh = true)
                }
            }
        }
        val at = (map["nextCheckAt"] as? Number)?.toLong() ?: 0L
        // 接口约定 §2: a layer's `until` running out changes the lock by itself, and the phone is the
        // one holding that cache. Wake at the earlier of the two and ask again, instead of polling or
        // sleeping through the change until nextCheckAt.
        val change = ((map["state"] as? Map<*, *>)?.get("nextChangeAt") as? Number)?.toLong() ?: 0L
        // 追问锁（§2 `state.followup` / §5）：界面要 know 到点时间，手机也要在到点时报一次
        // followup-timeout（插件每次 /plan 也兜一次，谁先到都算）。
        val st = map["state"] as? Map<*, *>
        val stateName = st?.get("state") as? String
        // 契约两处都给了 followup：§1 顶层一份、§2 塞在 state 里。哪份在就用哪份。
        val fu = (st?.get("followup") as? Map<*, *>) ?: (map["followup"] as? Map<*, *>)
        val followupOn = fu?.get("active") == true || stateName == "followup"
        val expiry = if (!followupOn) 0L else maxOf(
            (fu?.get("until") as? Number)?.toLong() ?: 0L,
            if (stateName == "followup") (st?.get("until") as? Number)?.toLong() ?: 0L else 0L,
        )
        val expiredFollowup = followupUntilAt
        followupUntilAt = if (followupOn) expiry else 0L
        followupQuestionText = if (followupOn) fu?.get("currentQuestion") as? String ?: why else null
        followupBackToState = if (followupOn) st?.get("backTo") as? String else null
        panelTextCache = panelTextOf(map)
        if (expiredFollowup > 0 && stamp >= expiredFollowup && followupTimeoutReported != expiredFollowup) {
            // 到点没裁决：手机本地先报一次；插件的答案可能带来新的追问锁，所以报完再问 /plan。
            followupTimeoutReported = expiredFollowup
            postEvent("followup-timeout") { consultPlanSoon(minGapMs = 0L) }
        }
        val wakeAt = listOf(at, change, followupUntilAt).filter { it > 0 }.minOrNull() ?: 0L
        val speak = map["speak"] == true
        val reason = map["reason"] as? String ?: ""
        if (wakeAt <= 0) return
        // P2: the plugin decides, the phone executes. speak=true means this ball now; nextCheckAt is
        // when to look again afterwards. speak=false means only move the single alarm.
        if (speak && !ballInFlight()) {
            val now = clocks()
            val request = EventRequest(
                eventId = UUID.randomUUID().toString(),
                source = "SCHEDULER", kind = "random_chat", dedupeKey = "companion:plan",
                priority = schedulerConfig().priority, createdAt = now.wall, ttlMs = schedulerConfig().eventTtlMs,
                needsAgent = true, payload = mapOf("plan" to reason),
            )
            submit(request, fromScheduler = true)
        }
        // Keep the plugin's next-check time authoritative even after this ball: no local vote until
        // the next /plan or until the plugin goes away. speak=false on a nextChangeAt wake, so the
        // tick asks again instead of speaking.
        companionPlan = CompanionPlan(false, wakeAt, reason, System.currentTimeMillis())
        scheduler?.setNextDueAt(wakeAt, clocks())
        arm()
    }

    /** Dart reports one HTTP answer from the plugin; status 0 = no answer. */
    fun onAgentHttp(requestId: String, status: Int, body: Any?) {
        // A one-off plugin route (sessions / config) rather than a kernel agent request: hand the
        // raw answer to whoever asked, and keep it away from the coordinator, which would otherwise
        // see an unknown request id.
        routeAnswers.remove(requestId)?.let { deliver ->
            main.post { deliver(status, body) }
            return
        }
        val c = coordinator ?: return
        when (val d = AgentWire.decode(status, body)) {
            is AgentWire.Decoded.Reply -> {
                dispatch(c.onAgent(requestId, d.reply))
                if (!d.terminal && c.active()?.agentRequestId == requestId) agentPort?.poll(requestId)
            }
            is AgentWire.Decoded.Retry -> main.postDelayed({
                if (c.active()?.agentRequestId == requestId) agentPort?.poll(requestId)
            }, d.afterMs)
            is AgentWire.Decoded.Skip -> {
                // "Not this time" is a decision, not a delivery failure (P2): close quietly rather
                // than feeding notDeliveredStreak and retrying within the minute.
                val eventId = c.active()?.takeIf { it.agentRequestId == requestId }?.eventId
                if (eventId != null) dispatch(c.cancel(eventId, "skip:" + d.code))
            }
        }
    }

    /** One pending one-off plugin route: `requestId -> answer handler`. */
    private val routeAnswers = LinkedHashMap<String, (Int, Any?) -> Unit>()

    /**
     * Fetches one plugin route over the same Dart transport the agent uses, and answers the
     * MethodChannel call with the plugin's own JSON (or an error map when there is no transport).
     *
     * Deliberately one-shot: this serves the settings page's session read/config write, not a
     * second agent pipeline. `GET` goes out through the transport's poll op (which is the GET one),
     * `POST` through its request op; both are ops the plugin contract already defines.
     */
    fun fetchPluginRoute(
        result: io.flutter.plugin.common.MethodChannel.Result,
        method: String,
        route: String,
        body: Map<String, Any?>?,
    ) {
        val port = agentPort
        if (port == null) {
            result.success(linkedMapOf("error" to linkedMapOf("code" to "no_transport")))
            return
        }
        val requestId = "route-${UUID.randomUUID().toString().take(8)}"
        val path = "${AgentWire.PREFIX}$route"
        routeAnswers[requestId] = { status, raw ->
            result.success(
                when {
                    // status 0 = the transport never got an answer. Kept distinct from an HTTP
                    // error so the page can say "not connected" rather than "plugin refused".
                    status == 0 -> linkedMapOf("error" to linkedMapOf("code" to "no_answer"))
                    raw is Map<*, *> -> raw
                    else -> linkedMapOf("error" to linkedMapOf("code" to "bad-answer", "status" to status))
                },
            )
        }
        main.postDelayed({
            if (routeAnswers.remove(requestId) != null) {
                result.success(linkedMapOf("error" to linkedMapOf("code" to "timeout")))
            }
        }, ROUTE_TIMEOUT_MS)
        if (method == "GET") port.pollVia(requestId, path) else port.requestVia(requestId, path, body ?: emptyMap())
    }

    /** The person used DSH (sent a message): keep proactive attempts out of the way for a while. */
    fun onUserActivity() { scheduler?.onUserActivity(clocks()) }

    private fun dispatch(step: Step) {
        for (cmd in step.commands) runCatching { run(cmd) }.onFailure { Log.w(TAG, "command $cmd", it) }
        for (closed in step.closed) {
            val skip = closed.reason?.takeIf { it.startsWith("skip:") }?.substringAfter("skip:")
            if (skip != null) {
                scheduler?.onSkipped(closed.eventId, skip, clocks())
            } else {
                scheduler?.onOutcome(closed.eventId, closed.engagement, clocks())
            }
            // The kernel owns the outcome; the round-1 twin mirrors it (review r3, A).
            runCatching { LoopRuntime.onKernelClosed(closed) }
            Log.i(TAG, "closed ${closed.eventId} ${closed.outcome} ${closed.reason} engagement=${closed.engagement}")
        }
        main.removeCallbacks(deadlineTick)
        coordinator?.nextDeadline()?.let { main.postDelayed(deadlineTick, (it - System.currentTimeMillis()).coerceIn(50, 10 * 60_000L)) }
        if (step.closed.isNotEmpty()) {
            arm()
            // A closed attempt makes the scheduler pick a random 45 min–3 h gap; the companion's
            // ladder (5 min lighter, 15 min channel) must win, so ask it right away.
            consultPlanSoon()
        }
    }

    private fun run(cmd: Command) {
        val c = coordinator!!
        when (cmd) {
            // The Dart port is only wired once Flutter is up. At process start the coordinator's
            // recovery emits ResumeAgent *before* that happens, so "no port yet" must not be turned
            // into a failure: an agent turn that is still inside its deadline is kept and replayed
            // when the port arrives. Only a port that is still missing after the deadline fails.
            is Command.RequestAgent -> {
                val port = agentPort
                val body = AgentWire.requestBody(cmd, System.currentTimeMillis(), phoneContext())
                if (port == null) holdAgent(cmd, body)
                else port.request(cmd.requestId, body)
            }
            // The plugin answers a replayed requestId with its record and never sends twice.
            is Command.ResumeAgent -> {
                val port = agentPort
                if (port == null) holdAgent(Command.ResumeAgent(cmd.eventId, cmd.requestId, cmd.deadline), null)
                else port.poll(cmd.requestId)
            }
            is Command.CancelAgent -> agentPort?.cancel(cmd.requestId)
            is Command.Present -> when (cmd.surface) {
                Surface.ACTIVITY -> {
                    // One visible DSH surface at a time (round-4 integration): the intercept page is
                    // its own activity in its own task, so without this slot the two stack. A
                    // refusal is reported as a surface failure, which the coordinator already knows
                    // how to handle (it falls back to the notification when one is allowed).
                    val claimed = VisibleSurface.claim(VisibleSurface.OWNER_PROACTIVE, cmd.token)
                    if (!claimed) {
                        Log.i(TAG, "activity surface busy (" + VisibleSurface.holder() + "); not stacking")
                        dispatch(c.onSurface(cmd.token, SurfaceReport.Failed("surface_busy")))
                    } else {
                        // P1: a real window, not an Activity — switching apps must not dismiss it.
                        // The Activity stays as the fallback when no overlay host is available.
                        val text = cmd.content.text
                        val ok = runCatching { ProactiveOverlay.show(cmd.token, text, cmd.deadline) }.getOrDefault(false) ||
                            ProactivePageActivity.start(app, cmd.token, text, cmd.deadline)
                        if (!ok) VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, cmd.token)
                        dispatch(c.onSurface(cmd.token, if (ok) SurfaceReport.Posted else SurfaceReport.Failed("start_failed")))
                    }
                }
                Surface.NOTIFICATION -> {
                    val ok = notificationPort?.post(cmd.token, cmd.content.text, cmd.deadline) == true
                    dispatch(c.onSurface(cmd.token, if (ok) SurfaceReport.Posted else SurfaceReport.Failed("no_notification")))
                }
            }
            is Command.Withdraw -> when (cmd.surface) {
                Surface.ACTIVITY -> {
                    ProactiveOverlay.withdraw(cmd.token)
                    ProactivePageActivity.withdraw(cmd.token)
                    VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, cmd.token)
                }
                Surface.NOTIFICATION -> notificationPort?.cancel(cmd.token)
            }
            Command.ClearLeftovers -> {
                ProactiveOverlay.withdrawAll()
                ProactivePageActivity.withdrawAll()
                VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, null)
                notificationPort?.cancelAll()
            }
        }
    }

    /** Dart started listening: replay agent commands held while Flutter was still coming up. */
    fun onAgentPortReady() {
        main.removeCallbacks(agentRetry)
        flushHeldAgent()
        // The gate depends on the port (notificationsAllowed), so the decision has to be revisited.
        refreshGate("agent_port")
        // The plugin is reachable now: let its /plan take over the one alarm again.
        consultPlanSoon(minGapMs = 0)
    }

    /**
     * An agent command that arrived before the Dart port existed. [body] is non-null for a fresh
     * request: replaying a fresh request is safe because the plugin answers a repeated requestId
     * with its existing record and never sends the message twice.
     */
    private fun holdAgent(cmd: Command, body: Map<String, Any?>?) {
        val requestId = when (cmd) {
            is Command.RequestAgent -> cmd.requestId
            is Command.ResumeAgent -> cmd.requestId
            else -> return
        }
        val deadline = when (cmd) {
            is Command.RequestAgent -> cmd.deadline
            is Command.ResumeAgent -> cmd.deadline
            else -> 0L
        }
        heldAgent[requestId] = HeldAgent(requestId, body, deadline)
        Log.i(TAG, "agent port not ready, holding $requestId until Flutter connects")
        main.removeCallbacks(agentRetry)
        main.postDelayed(agentRetry, AGENT_RETRY_MS)
    }

    /** Replays what was held once the port exists; drops it only when its deadline has passed. */
    private fun flushHeldAgent() {
        val port = agentPort ?: return
        if (heldAgent.isEmpty()) return
        val now = System.currentTimeMillis()
        val pending = heldAgent.values.toList()
        heldAgent.clear()
        for (h in pending) {
            if (now >= h.deadline) {
                // The turn's window is gone: let the kernel fail it honestly.
                dispatch(coordinator!!.onAgent(h.requestId, AgentReply.Failed("no_transport")))
                continue
            }
            if (h.body != null) port.request(h.requestId, h.body!!) else port.poll(h.requestId)
        }
    }

    private val agentRetry = Runnable { flushHeldAgent() }

    /** DSH's own state, read only. Never takes a PhoneTasks owner or a PromptArbiter slot. */
    fun gate(): Gate {
        val core = PhoneControl.peek()
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val nm = app.getSystemService(NotificationManager::class.java)
        val fg = LoopRuntime.lastForeground
        return Gate(
            // Either feature keeps arbitration on; each one's events are cancelled by applySwitches.
            featureEnabled = Switches(LoopRuntime.triggerEnabled(), proactiveEnabled()).arbitrate,
            dshTaskActive = core != null && (core.tasks.isActive || core.tasks.callsInFlight > 0),
            dshPromptOpen = core?.prompts?.busy() == true,
            protectedForeground = io.github.ccch1mneyyy.dsh_app.loop.ProtectedForeground.isProtected(fg),
            screenOn = power?.isInteractive != false,
            unlocked = keyguard?.isKeyguardLocked != true,
            a11yConnected = PhoneA11yService.isConnected(),
            notificationsAllowed = nm?.areNotificationsEnabled() == true && notificationPort != null,
        )
    }

    private fun watchTasks() {
        PhoneControl.peek()?.prompts?.onBusyChanged = { refreshGate("dsh_prompt") }
        val tasks = PhoneControl.peek()?.tasks ?: return
        if (listening === tasks) return
        listening?.removeListener(taskListener)
        tasks.addListener(taskListener)
        listening = tasks
    }

    fun caps(): BackgroundCaps {
        val am = app.getSystemService(ActivityManager::class.java)
        val alarms = app.getSystemService(AlarmManager::class.java)
        return BackgroundCaps(
            alarmsAvailable = alarms != null,
            exactAlarms = Build.VERSION.SDK_INT < 31 || alarms?.canScheduleExactAlarms() == true,
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 && am?.isBackgroundRestricted == true,
        )
    }

    private fun arm() {
        val s = scheduler ?: return
        val mode = WakeMode.of(caps())
        val plan = companionPlan
        if (plan != null && agentPort != null) {
            // One alarm, at the companion's own next check. The local random gap/cap is not asked.
            val now = System.currentTimeMillis()
            WakeAlarm.set(app, plan.at.coerceAtLeast(now + 60_000L), mode)
            return
        }
        WakeAlarm.set(app, s.nextWake(clocks(), mode), mode)
    }

    fun clocks() = Clocks(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0))

    /**
     * 手边的情况 for the companion: front app, how long, today's totals, screen, charging, local
     * time. From [NowFeed] (accessibility events, no screen text). Null when the service never fed
     * it: unknown is not "not using the phone".
     */
    private fun phoneContext(): Map<String, Any?>? = runCatching {
        val now = SystemClock.elapsedRealtime()
        val s = NowFeed.snapshot(now)
        if (s.lastEventAgeMs < 0) return@runCatching null
        val wall = System.currentTimeMillis()
        val targets = runCatching { InterceptRuntime.config().targets }.getOrNull().orEmpty()
        val funApps = targets.associate { it.packageName to it.label }
        val pm = app.packageManager
        fun label(pkg: String): String? = funApps[pkg]
            ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        val opens = usageOpens(wall)
        val today = NowFeed.today(now, dayOf(wall)).map { (pkg, minutes) ->
            linkedMapOf<String, Any?>("app" to pkg, "label" to label(pkg), "minutes" to minutes, "fun" to (pkg in funApps || pkg in blockedNow()),
                "opens" to opens?.get(pkg))
        }
        val front = s.app
        linkedMapOf<String, Any?>(
            "app" to front,
            "label" to front?.let { label(it) },
            "minutes" to s.appMinutes,
            "fun" to (front != null && (front in funApps || front in blockedNow())),
            "screenOn" to s.screenOn,
            "charging" to s.charging,
            "local" to String.format(java.util.Locale.US, "%02d:%02d", minuteOfDay(wall) / 60, minuteOfDay(wall) % 60),
            "today" to today,
            // 走神前兆：90 秒 / 15 分钟里切了几次 App、切过哪些。
            "switches" to synchronized(switches) {
                val recent = switches.filter { now - it.first <= 90_000L }
                linkedMapOf<String, Any?>(
                    "in90s" to recent.size,
                    "apps90s" to recent.map { it.second }.distinct().take(8).map { label(it) ?: it },
                    "in15m" to switches.size,
                    "distinct15m" to switches.map { it.second }.distinct().size,
                )
            },
        )
    }.getOrNull()

    /**
     * 今天每个 App 打开了几次（"使用情况访问"授权了才有；没授权返回 null）。一分钟算一次，别每次 /plan 都扫一天的事件。
     */
    private var opensCache: Pair<Long, Map<String, Int>>? = null
    private fun usageOpens(wall: Long): Map<String, Int>? = runCatching {
        opensCache?.let { if (wall - it.first < 60_000L) return@runCatching it.second }
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), app.packageName)
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) return@runCatching null
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val start = Calendar.getInstance(TimeZone.getDefault()).run {
            timeInMillis = wall; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); timeInMillis
        }
        val events = usm.queryEvents(start, wall)
        val e = android.app.usage.UsageEvents.Event()
        val counts = HashMap<String, Int>()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            // ACTIVITY_RESUMED（老名字 MOVE_TO_FOREGROUND，同一个值 1）；同一个 App 里切页面不重复算。
            if (e.eventType == 1 && e.packageName != last) {
                counts[e.packageName] = (counts[e.packageName] ?: 0) + 1
                last = e.packageName
            }
        }
        opensCache = wall to counts
        counts
    }.getOrNull()

    private fun minuteOfDay(wall: Long): Int = Calendar.getInstance(TimeZone.getDefault()).run {
        timeInMillis = wall; get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
    }

    private fun dayOf(wall: Long): Long = Calendar.getInstance(TimeZone.getDefault()).run {
        timeInMillis = wall; get(Calendar.YEAR) * 1000L + get(Calendar.DAY_OF_YEAR)
    }
}
