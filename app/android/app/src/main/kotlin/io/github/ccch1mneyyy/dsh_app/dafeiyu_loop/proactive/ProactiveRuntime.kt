package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.pm.ApplicationInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.LoopRuntime
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.AgentReply
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.AgentWire
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ArbiterDecision
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.AtomicJsonFile
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.BackgroundCaps
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.BeijingClock
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.BurstPlan
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.VoicePlan
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Clocks
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ClockFireEvent
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ClockPhase
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ClockTask
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ClockTaskKind
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Command
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Coordinator
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.CoordinatorConfig
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.EventRequest
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Gate
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.KernelClockEngine
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ProactiveLedgerBook
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ProactiveScheduler
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ReplyWindow
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.SchedulerConfig
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Step
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Submit
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.VisibleSurface
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.DefianceWindow
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.LockPriority
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.LockRule
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.RuleBook
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.planSnapshot
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Surface
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.Switches
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.SurfaceReport
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.WakeMode
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.phone.PhoneTasks
import io.github.ccch1mneyyy.dsh_app.phone.a11y.FrontChange
import io.github.ccch1mneyyy.dsh_app.phone.a11y.NowFeed
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionFactsOf
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionPolicy
import io.github.ccch1mneyyy.dsh_app.phone.a11y.SessionVerdict
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian.GuardianOfflineIncident
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian.GuardianPolicyMirror
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian.GuardianPolicySnapshot
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.SystemBypassClassifier
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.intercept.InterceptRuntime
import io.github.ccch1mneyyy.dsh_app.runtime.DshService
import io.github.ccch1mneyyy.dsh_app.runtime.RuntimeController
import java.io.File
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

/** Pure JVM-testable cache & extractor for synced fun-time balance (availableFunLeft / timeLedger.funLeft). */
object FunBalanceCache {
    @Volatile private var lastFunLeft: Int? = null

    fun cached(): Int? = lastFunLeft

    fun update(value: Int?) {
        if (value != null && value >= 0) {
            lastFunLeft = value
        }
    }

    fun syncFromResponse(m: Map<*, *>?): Int? {
        val direct = (m?.get("availableFunLeft") as? Number)?.toInt()
        val ledger = ((m?.get("timeLedger") as? Map<*, *>)?.get("funLeft") as? Number)?.toInt()
        val next = direct ?: ledger
        update(next)
        return next
    }

    fun clear() {
        lastFunLeft = null
    }
}

/**
 * Pure JVM-testable tracker for companion lock -> release transitions (1.4).
 * Prevents repeated per-tick releaseCompanionLock() calls when no active companion lock exists
 * (e.g. during hardCapDowngrade where /plan sends release=true every tick).
 */
class CompanionReleaseTracker {
    private var lastWasLocked: Boolean = false
    private var releasedSinceLastLock: Boolean = false

    fun onEnforced() {
        lastWasLocked = true
        releasedSinceLastLock = false
    }

    fun onExplicitRelease() {
        lastWasLocked = false
        releasedSinceLastLock = true
    }

    fun shouldReleaseOnPlan(locked: Boolean, releaseFlag: Boolean, hasActiveCompanionLock: Boolean): Boolean {
        if (locked) {
            lastWasLocked = true
            releasedSinceLastLock = false
            return false
        }
        if (releaseFlag && !releasedSinceLastLock && (lastWasLocked || hasActiveCompanionLock)) {
            lastWasLocked = false
            releasedSinceLastLock = true
            return true
        }
        if (!hasActiveCompanionLock) {
            lastWasLocked = false
        }
        return false
    }
}

/**
 * Pure JVM-testable convergence policy for session foreground evaluation (`sessionFront`).
 * Guarantees unified release/safety gates always win, and when a fresh arbiter verdict is present,
 * legacy session grace/lock rules do not fire when the arbiter allows the app.
 */
object SessionArbiterPolicy {
    enum class Action {
        UNIFIED_RELEASE,
        ARBITER_SHADOW,
        ARBITER_OPEN,
        ARBITER_LOCK,
        LEGACY_SESSION,
    }

    fun decide(
        unifiedRelease: Boolean,
        disarmed: Boolean,
        neverLock: Boolean,
        arbiterShouldLock: Boolean?,
        arbiterReasonTag: String?,
    ): Action {
        if (unifiedRelease || disarmed || neverLock) return Action.UNIFIED_RELEASE
        if (arbiterShouldLock != null) {
            if (arbiterReasonTag == "shadow") return Action.ARBITER_SHADOW
            return if (arbiterShouldLock) Action.ARBITER_LOCK else Action.ARBITER_OPEN
        }
        return Action.LEGACY_SESSION
    }
}

/**
 * Pure JVM-testable defiance classification policy (`defiantIn`).
 * When a fresh arbiter verdict exists, only apps the arbiter locks count as defiant.
 */
object DefiancePolicy {
    fun isDefiant(
        screenOn: Boolean,
        unifiedRelease: Boolean,
        arbiterShouldLock: Boolean?,
        legacyListed: Boolean,
        hasLegacyPass: Boolean,
    ): Boolean {
        if (!screenOn || unifiedRelease) return false
        if (arbiterShouldLock != null) return arbiterShouldLock
        return legacyListed && !hasLegacyPass
    }
}

/**
 * Pure JVM helper merging fresh arbiter allowlist (under allowlist or strict priorities)
 * ahead of legacy session/focus allowlists so work apps remain visible and launchable.
 */
object FocusAllowResolver {
    private val STRICT_PRIORITIES = setOf("P1", "P2", "P3", "P3.5", "P4", "P5")

    fun mergePackages(
        arbiter: ArbiterDecision?,
        sessionAllow: Collection<String>,
        sessionPkg: String?,
        focusTaskPkg: String?,
        focusAllowed: Collection<String>?,
    ): LinkedHashSet<String> {
        val raw = LinkedHashSet<String>()
        if (arbiter != null && (arbiter.mode == ArbiterDecision.MODE_ALLOWLIST || arbiter.priority in STRICT_PRIORITIES)) {
            arbiter.allow.forEach { pkg ->
                val clean = pkg.trim()
                if (clean.isNotEmpty() && clean != "*") raw.add(clean)
            }
        }
        sessionAllow.forEach { pkg ->
            val clean = pkg.trim()
            if (clean.isNotEmpty()) raw.add(clean)
        }
        sessionPkg?.trim()?.takeIf { it.isNotEmpty() }?.let(raw::add)
        focusTaskPkg?.trim()?.takeIf { it.isNotEmpty() }?.let(raw::add)
        focusAllowed?.forEach { pkg ->
            val clean = pkg.trim()
            if (clean.isNotEmpty()) raw.add(clean)
        }
        return raw
    }
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
    private var clockEngine: KernelClockEngine? = null
    private val highFreqClockTick = Runnable { tickClockHighFreq() }
    private val sessionActiveTick = Runnable { tickSessionActive() }
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
        // B5：TTS 只在这里拿 applicationContext，引擎本身第一次要念的时候才绑（默认关，不白占）。
        VoiceOut.install(app)
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
        val ck = KernelClockEngine(AtomicJsonFile(File(dir, "clock.json")), BeijingClock.ZONE)
        ledger = book
        coordinator = c
        scheduler = s
        clockEngine = ck
        s.recover(clocks())
        ck.recover(clocks())
        dispatch(c.recover(gate()))
        runCatching { LoopRuntime.reconcileWithKernel(c::find) }
        applySwitches()
        watchTasks()
        initShakeDice(app)
        if (sessionActive()) scheduleSessionActiveTick()
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

    /** 锁系统在不在：进程里 ProactiveRuntime 起来了就算（锁的判定靠插件 /plan 和本地规则快照，不靠「主动陪伴」开关）。 */
    fun lockWatch(): Boolean = ::app.isInitialized

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
        maybeGentleDiceIntervene()
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

    fun notifySuperSparkMuted15m() {
        if (!::app.isInitialized) return
        fetchPluginRoute(
            ignoreResult,
            "POST",
            "/super-spark",
            linkedMapOf("action" to "mute_15m", "timeZone" to BeijingClock.ZONE.id),
        )
    }

    /** 强制计划模态 ①：确认草案并激活今日 P3 验收锁。 */
    fun confirmPlanModal(
        blocks: List<PlanDraftBlock>? = null,
        done: ((ok: Boolean, message: String?) -> Unit)? = null,
    ) {
        if (!::app.isInitialized) {
            done?.invoke(false, "未初始化")
            return
        }
        main.post {
            val body = linkedMapOf<String, Any?>(
                "action" to "confirm_plan_modal",
                "timeZone" to BeijingClock.ZONE.id,
            )
            if (!blocks.isNullOrEmpty()) {
                body["blocks"] = blocks.map { b ->
                    linkedMapOf<String, Any?>(
                        "id" to b.id,
                        "slot" to b.slot,
                        "startMin" to b.startMin,
                        "endMin" to b.endMin,
                        "title" to b.title,
                        "deliverable" to b.deliverable,
                        "lockMode" to b.lockMode,
                        "allowedApps" to b.allowedApps,
                    )
                }
            }
            fetchPluginRoute(routeCallback { m ->
                val ok = m["ok"] == true
                if (ok) {
                    consultPlanSoon(minGapMs = 0L)
                    tick("confirm_plan_modal")
                }
                val err = (m["error"] as? String)
                    ?: ((m["error"] as? Map<*, *>)?.get("message") as? String)
                    ?: (m["message"] as? String)
                done?.invoke(ok, err)
            }, "POST", "/plan-contract", body)
        }
    }

    /** 强制计划模态 ②：发给鲸澜重构今日计划并激活验收锁。 */
    fun negotiatePlanModal(
        text: String,
        done: ((ok: Boolean, reply: String?, armedCount: Int, error: String?) -> Unit)? = null,
    ) {
        if (!::app.isInitialized) {
            done?.invoke(false, null, 0, "未初始化")
            return
        }
        val clean = text.trim()
        if (clean.isEmpty()) {
            done?.invoke(false, null, 0, "请先输入计划内容")
            return
        }
        main.post {
            fetchPluginRoute(routeCallback { m ->
                val ok = m["ok"] == true
                val reply = (m["reply"] as? String)?.trim()
                val armedCount = (m["armedVerifyCount"] as? Number)?.toInt() ?: 0
                if (ok) {
                    consultPlanSoon(minGapMs = 0L)
                    tick("negotiate_plan_modal")
                }
                val err = (m["error"] as? String)
                    ?: ((m["error"] as? Map<*, *>)?.get("message") as? String)
                done?.invoke(ok, reply, armedCount, err)
            }, "POST", "/plan-contract", linkedMapOf(
                "action" to "negotiate_plan_modal",
                "text" to clean,
                "commit" to true,
                "timeZone" to BeijingClock.ZONE.id,
            ))
        }
    }

    /** 强制计划模态 ③：延后 15 分钟（超限后后端会自动钉入 25m 起手验收锁）。 */
    fun snoozePlanModal(
        minutes: Int = 15,
        done: ((ok: Boolean, autoArmed: Boolean, message: String?) -> Unit)? = null,
    ) {
        if (!::app.isInitialized) {
            done?.invoke(false, false, "未初始化")
            return
        }
        main.post {
            fetchPluginRoute(routeCallback { m ->
                val ok = m["ok"] == true
                val autoArmed = m["autoArmed"] == true
                if (ok) {
                    consultPlanSoon(minGapMs = 0L)
                    if (autoArmed) tick("snooze_plan_modal")
                }
                val err = (m["error"] as? String)
                    ?: ((m["error"] as? Map<*, *>)?.get("message") as? String)
                    ?: (m["message"] as? String)
                done?.invoke(ok, autoArmed, err)
            }, "POST", "/plan-contract", linkedMapOf(
                "action" to "snooze_plan_modal",
                "minutes" to minutes.coerceIn(5, 60),
                "timeZone" to BeijingClock.ZONE.id,
            ))
        }
    }

    /** 反向白名单单开舱（quiz_only / gemini_only / custom）：POST /bouts action=reverse_capsule 并立刻刷新 /plan / tick。 */
    fun activateReverseCapsule(
        mode: String,
        minutes: Int = 20,
        allow: List<String>? = null,
        goal: String? = null,
        done: ((ok: Boolean, message: String?) -> Unit)? = null,
    ) {
        if (!::app.isInitialized) {
            done?.invoke(false, "未初始化")
            return
        }
        main.post {
            val body = linkedMapOf<String, Any?>(
                "action" to "reverse_capsule",
                "mode" to mode,
                "minutes" to minutes.coerceIn(5, 180),
                "timeZone" to BeijingClock.ZONE.id,
            )
            if (!allow.isNullOrEmpty()) body["allow"] = allow
            if (!goal.isNullOrBlank()) body["goal"] = goal.trim().take(80)
            fetchPluginRoute(routeCallback { m ->
                val ok = m["ok"] == true
                val toast = (m["toast"] as? String)?.trim()
                val err = (m["error"] as? String)
                    ?: ((m["error"] as? Map<*, *>)?.get("message") as? String)
                consultPlanSoon(minGapMs = 0L)
                tick("reverse_capsule")
                if (!toast.isNullOrBlank()) {
                    runCatching { Toast.makeText(app, toast, Toast.LENGTH_SHORT).show() }
                }
                done?.invoke(ok, toast ?: err)
            }, "POST", "/bouts", body)
        }
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
        }, "POST", "/say", linkedMapOf("v" to 1, "requestId" to id, "text" to text, "timeZone" to BeijingClock.ZONE.id))
    }

    private fun pollSay(id: String, deadline: Long, done: (String?, String?) -> Unit) {
        if (System.currentTimeMillis() > deadline) { done(null, "timeout"); return }
        fetchPluginRoute(routeCallback { m ->
            val error = ((m["error"] as? Map<*, *>)?.get("code") as? String)
            when (m["state"]) {
                "generated" -> {
                    consultPlanSoon(minGapMs = 0L)
                    done((m["text"] as? String)?.trim(), null)
                }
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
            linkedMapOf<String, Any?>("timeZone" to BeijingClock.ZONE.id)
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
        syncGuardianMirror()
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
        if (!::app.isInitialized || hasUnifiedRelease(now)) return null
        val list = ArrayList(ruleBook()?.active(now).orEmpty())
        val r = refuseUntil()
        if (r > now && list.none { it.until >= r }) list.add(LockRule(LockPriority.KIND_REFUSE, r))
        // 工作模式和预案的严格模式都带自己的一句话；连麦舱没有，走默认文案。
        if (sessionActive()) list.add(LockRule(LockPriority.KIND_SESSION, sessionUntil, workWhy))
        return LockPriority.primary(list)
    }

    /** The one line the lock page shows for the primary lock. */
    private fun lockWhy(now: Long = System.currentTimeMillis(), pkg: String? = null): String? =
        primaryLock(now)?.let { it.why ?: LockPriority.defaultWhy(it.kind) }
            // 第九轮 A6：单个 App 被预案锁住时，没人在规则表里写解释——那句 why 跟着 /plan 的
            // blockWhy 下来。没有就退回一句实话，不让锁页显示成「拒绝模式」（那是另一件事）。
            ?: pkg?.let { blockWhys[it] ?: if (it in blockedNow()) "这个 App 现在锁着：到点自动解锁" else null }

    /** 这一局 /plan 报上来的「哪个 App 被哪条预案锁了」，只在内存里（下一次 /plan 就换一份）。 */
    @Volatile private var blockWhys: Map<String, String> = emptyMap()

    /** Temporary block list: the last /plan answer plus the cached snapshot (survives restarts). */
    private fun blockedNow(): Set<String> = blocked + (ruleBook()?.blocked(System.currentTimeMillis()) ?: emptySet())

    /** Whether a lock in force right now would lock [pkg] (the lock page asks before superseding). */
    fun wouldLock(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (!::app.isInitialized || hasUnifiedRelease(now) || !listed(pkg)) return false
        return now < lockUntil(now) || sessionActive() || (force?.app == pkg && now < (force?.until ?: 0L))
    }

    private fun listed(pkg: String): Boolean {
        if (pkg in NEVER_LOCK) return false
        if (io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist.isSafeSystemOrHome(pkg, SessionFactsOf.home(app)) ||
            io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist.isSelfPackage(pkg, app.packageName)) {
            return false
        }
        // Gemini 互动舱：按分级策略判——只有 LOCK 才算"要拦的"，宽限期里的 GRACE 不锁。
        if (sessionActive()) return sessionVerdict(pkg) == SessionVerdict.LOCK
        if (pkg in blockedNow()) return true
        return runCatching { InterceptRuntime.config().targets.any { it.packageName == pkg } }.getOrDefault(false)
    }

    /**
     * R7 P0-1：舱内这一包的判定。事实由 [SessionFactsOf] 查（Android 那边），规则在 [SessionPolicy]。
     */
    private fun sessionVerdict(pkg: String): SessionVerdict {
        val now = System.currentTimeMillis()
        val dwell = if (pkg == frontPkg) (now - frontSince).coerceAtLeast(0L) else 0L
        val cfg = runCatching { InterceptRuntime.config() }.getOrNull()
        val bounceCount = synchronized(sessionBounceStamps) {
            while (sessionBounceStamps.isNotEmpty() && now - sessionBounceStamps.first() > 30_000L) {
                sessionBounceStamps.removeFirst()
            }
            sessionBounceStamps.size
        }
        val geminiSession = sessionIsGemini() || sessionAllow.any { it in GEMINI_PACKAGES }
        val baseAllow = if (geminiSession) sessionAllow + GEMINI_PACKAGES else sessionAllow
        val baseEntertain = blockedNow() + cfg?.targets?.map { it.packageName }?.toSet().orEmpty()
        val safeEntertain = if (geminiSession) baseEntertain - GEMINI_PACKAGES else baseEntertain
        return SessionPolicy.judge(pkg, SessionFactsOf.of(
            app, pkg,
            allow = baseAllow,
            whitelist = cfg?.whitelist ?: emptySet(),
            entertainment = safeEntertain,
            dwellMs = dwell,
            graceMs = sessionGrace,
            breakGlass = hasUnifiedRelease(now),
            strict = sessionStrict,
            bounceCount = bounceCount,
        ))
    }

    /**
     * 舱内前台变了：同一份判定给两条调用路用，且 30 秒滑窗记横跳次数（回到桌面/任务应用不再洗白计数）。
     * 当单一裁决器处于执法且未过期时，以裁决器作为唯一执法真相源，避免旧伴侣多头规则在裁决器放行时再弹宽限/拉锁。
     */
    private fun sessionFront(pkg: String?, forceCheck: Boolean = false): Boolean {
        if (pkg == null) return false
        if (!forceCheck && !FrontChange.switched(pkg)) return false
        val now = System.currentTimeMillis()
        if (pkg != frontPkg) {
            val isAllowLike = pkg in sessionAllow ||
                io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist.isSelfPackage(pkg, app.packageName)
            val isNeutralOrSafe = io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist.isSafeSystemOrHome(pkg, SessionFactsOf.home(app))
            if (isAllowLike) {
                lastSessionInAllowPkg = pkg
            } else if (lastSessionInAllowPkg != null && pkg !in SessionPolicy.NEVER_LOCK && !isNeutralOrSafe) {
                synchronized(sessionBounceStamps) {
                    sessionBounceStamps.addLast(now)
                    while (sessionBounceStamps.isNotEmpty() && now - sessionBounceStamps.first() > 30_000L) {
                        sessionBounceStamps.removeFirst()
                    }
                }
            }
            frontPkg = pkg
            frontSince = now
            graceHinted = false
        }
        if (!sessionActive()) return false
        val unifiedRelease = hasUnifiedRelease(now)
        val disarmed = LockSafety.disarmed(app, now)
        val neverLock = LockSafety.neverLock(app, pkg)
        val arbiterOutcome = if (unifiedRelease || disarmed || neverLock) {
            null
        } else {
            ArbiterShadow.evaluateForEnforce(app, pkg)
        }
        when (SessionArbiterPolicy.decide(
            unifiedRelease = unifiedRelease,
            disarmed = disarmed,
            neverLock = neverLock,
            arbiterShouldLock = arbiterOutcome?.shouldLock,
            arbiterReasonTag = arbiterOutcome?.reasonTag,
        )) {
            SessionArbiterPolicy.Action.UNIFIED_RELEASE -> return false
            SessionArbiterPolicy.Action.ARBITER_SHADOW -> {
                LockSafety.shadowHit(app, pkg, arbiterOutcome!!.why, "arbiter")
                return false
            }
            SessionArbiterPolicy.Action.ARBITER_OPEN -> {
                if (pkg in sessionAllow || pkg == app.packageName) forcedApp = null
                if (force?.app == pkg) force = null
                return false
            }
            SessionArbiterPolicy.Action.ARBITER_LOCK -> return lockInSession(pkg, arbiterOutcome)
            SessionArbiterPolicy.Action.LEGACY_SESSION -> Unit
        }
        return when (sessionVerdict(pkg)) {
            SessionVerdict.ALLOW -> {
                // 仅在真正回到白名单目标 App 时清防抖，回桌面不洗白
                if (pkg in sessionAllow || pkg == app.packageName) forcedApp = null
                false
            }
            SessionVerdict.GRACE -> {
                graceHint(pkg)
                false
            }
            SessionVerdict.LOCK -> lockInSession(pkg)
        }
    }

    /** 提供给 FocusRuntime 仲裁：专时严格 session 直接用 InterceptPageActivity 统管锁面。 */
    fun onSessionBounce(pkg: String): Boolean {
        if (!sessionActive()) return false
        return sessionFront(pkg, forceCheck = true)
    }

    /** 4 级锁：同一个 App 启动时连发的窗口事件只锁一次（输入法已由 FrontChange 排除）。 */
    private fun lockInSession(pkg: String, arbiterOutcome: ArbiterEnforceOutcome? = null): Boolean {
        val now = System.currentTimeMillis()
        if (hasUnifiedRelease(now)) return false
        if (forcedApp == pkg && now - forcedAt < SESSION_LOCK_DEBOUNCE_MS) return true
        forcedApp = pkg
        forcedAt = now
        val whyText = arbiterOutcome?.why ?: (workWhy ?: lockWhy() ?: "专时锁定中，只许任务应用")
        val f = Force(4, pkg, whyText, now + FORCE_HOLD_MS, refuse = if (arbiterOutcome != null) true else sessionStrict)
        force = f
        enforce(f, fresh = true)
        return true
    }

    /** 宽限期里的那一条：不弹锁屏页，只提示一声，并在宽限到点时复查一次。 */
    private fun graceHint(pkg: String) {
        val secs = (sessionGrace / 1000).coerceAtLeast(1)
        if (!graceHinted) {
            graceHinted = true
            // 提示走主线程：这里可能是无障碍事件的线程。
            main.post {
                runCatching { Toast.makeText(app, "连麦还开着，" + secs + " 秒内回去", Toast.LENGTH_SHORT).show() }
                    .onFailure { Log.w(TAG, "grace hint", it) }
            }
        }
        // 一次性复查：到点还没回去才锁，不轮询。
        graceRecheck?.let { main.removeCallbacks(it) }
        val again = Runnable { sessionFront(pkg) }
        graceRecheck = again
        main.postDelayed(again, sessionGrace.coerceAtLeast(1_000L) + 1_000L)
        Log.i(TAG, "session grace $pkg for ${secs}s")
    }

    /**
     * 急事底线（电话/短信/地图/支付）搬到 [SessionPolicy] 了：定义只有一份，判定在 JVM 上单测。
     * 这里保留同名别名，别处照旧引用。
     */
    private val NEVER_LOCK = SessionPolicy.NEVER_LOCK

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
        // 2026-11-18：本地紧急解除（通知栏 / adb）同样算放风，不依赖引擎。
        if (LockSafety.disarmed(app)) return true
        if (breakUntil < 0) breakUntil = runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).getLong("break", 0L) }.getOrDefault(0L)
        return System.currentTimeMillis() < breakUntil
    }
    private fun setBreakUntil(at: Long) {
        if (at == breakUntil) return
        breakUntil = at
        runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit().putLong("break", at).apply() }
        syncGuardianMirror()
    }

    /** 3 分钟应急急事通行（POST /emergency-pass）：顶部悬浮倒计时胶囊，到点回收权限并回弹。 */
    @Volatile private var emergencyUntilAt: Long = -1L
    @Volatile private var emergencyReasonText: String? = null
    private var emergencyExpiryTask: Runnable? = null

    /** 统一放行（刷题达标 / 花余额放行），独立于 /plan 的 emergencyPass，避免下一次 /plan 巡检把它误清。 */
    @Volatile private var tempReleaseUntilAt: Long = -1L
    @Volatile private var tempReleaseReasonText: String? = null
    private var tempReleaseExpiryTask: Runnable? = null
    private var boutBufferExpiryTask: Runnable? = null

    private val companionReleaseTracker = CompanionReleaseTracker()
    @Volatile private var activeBoutScopeKey: String? = null
    @Volatile private var lastSessionBy: String? = null

    fun inEmergencyPass(now: Long = System.currentTimeMillis()): Boolean {
        if (!::app.isInitialized) return false
        if (emergencyUntilAt < 0) {
            val prefs = runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE) }.getOrNull()
            emergencyUntilAt = prefs?.getLong("emergency_until", 0L) ?: 0L
            emergencyReasonText = prefs?.getString("emergency_reason", null)
        }
        return emergencyUntilAt > now
    }

    fun inTempRelease(now: Long = System.currentTimeMillis()): Boolean {
        if (!::app.isInitialized) return false
        if (tempReleaseUntilAt < 0) {
            val prefs = runCatching { app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE) }.getOrNull()
            tempReleaseUntilAt = prefs?.getLong("temp_release_until", 0L) ?: 0L
            tempReleaseReasonText = prefs?.getString("temp_release_reason", null)
        }
        return tempReleaseUntilAt > now
    }

    /** 统一放行总闸：无论 arbiterEnforce 开/关，均一致认这几条放行通道。 */
    fun hasUnifiedRelease(now: Long = System.currentTimeMillis()): Boolean {
        if (!::app.isInitialized) return false
        if (breaking() || inEmergencyPass(now) || inTempRelease(now)) return true
        return runCatching { InterceptRuntime.isBoutBufferActive() }.getOrDefault(false)
    }

    fun cachedFunLeft(): Int? = FunBalanceCache.cached()

    fun updateCachedFunLeft(value: Int?) = FunBalanceCache.update(value)

    private fun syncFunLeftFromResponse(m: Map<*, *>?) {
        FunBalanceCache.syncFromResponse(m)
    }

    fun currentBoutBufferKey(): String {
        val explicit = activeBoutScopeKey?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val fState = runCatching { io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.FocusRuntime.state() }.getOrNull()
        if (fState?.active == true && fState.startedAt > 0L) return "focus:${fState.startedAt}"
        val day = BeijingClock.dayOf(System.currentTimeMillis())
        if (lastSessionBy == "verify") return "verify:$day"
        if (lastSessionBy == "seal") return "seal:${workWhy?.take(24).orEmpty()}:$day"
        if (sessionActive() && sessionUntil > 0L) return "session:$sessionUntil"
        return "day:$day"
    }

    fun canOfferBoutBuffer(lockMode: String): Boolean {
        if (!::app.isInitialized) return false
        val primary = primaryLock()?.kind
        val freshArbiter = runCatching { ArbiterShadow.currentFresh() }.getOrNull()
        val arbiterPrio = freshArbiter?.priority
        if (primary == LockPriority.KIND_NIGHT || arbiterPrio == "P1") return false
        val focusActive = runCatching { io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.FocusRuntime.isActive() }.getOrDefault(false)
        val arbiterAllowlist = freshArbiter?.mode == io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ArbiterDecision.MODE_ALLOWLIST
        val inBoutOrVerify = focusActive || sessionActive() || lastSessionBy == "verify" || lastSessionBy == "seal" ||
            arbiterAllowlist || lockMode == io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.InterceptRules.MODE_STRICT ||
            arbiterPrio in setOf("P3", "P3.5", "P4", "P5")
        if (!inBoutOrVerify) return false
        return InterceptRuntime.canTriggerBoutBuffer(currentBoutBufferKey())
    }

    fun onBoutBufferActivated(durationMs: Long) {
        if (!::app.isInitialized) return
        force = null
        forcedApp = null
        companionReleaseTracker.onExplicitRelease()
        releaseCompanionLock()
        boutBufferExpiryTask?.let { main.removeCallbacks(it) }
        val r = Runnable { onBoutBufferExpired() }
        boutBufferExpiryTask = r
        main.postDelayed(r, durationMs.coerceAtLeast(1_000L) + 250L)
    }

    private fun onBoutBufferExpired() {
        boutBufferExpiryTask = null
        if (!::app.isInitialized) return
        val fg = runCatching { NowFeed.snapshot(SystemClock.elapsedRealtime()).app }.getOrNull()
        if (!fg.isNullOrBlank()) {
            lastFront = null
            onForeground(fg)
            if (sessionActive()) sessionFront(fg, forceCheck = true)
            runCatching { InterceptRuntime.onForeground(fg) }
        }
    }

    fun applyTempRelease(until: Long, reason: String?) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        if (until <= now) {
            onTempReleaseExpired()
            return
        }
        tempReleaseUntilAt = until
        tempReleaseReasonText = reason?.takeIf { it.isNotBlank() }?.take(60)
        runCatching {
            app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("temp_release_until", until)
                .putString("temp_release_reason", tempReleaseReasonText)
                .apply()
        }
        force = null
        forcedApp = null
        companionReleaseTracker.onExplicitRelease()
        releaseCompanionLock()
        if (!inEmergencyPass(now)) {
            EmergencyCapsule.show(app, until, tempReleaseReasonText) {
                main.post { onTempReleaseExpired() }
            }
        }
        tempReleaseExpiryTask?.let { main.removeCallbacks(it) }
        val expiry = Runnable { onTempReleaseExpired() }
        tempReleaseExpiryTask = expiry
        main.postDelayed(expiry, (until - now + 150L).coerceAtLeast(500L))
        syncGuardianMirror()
        consultPlanSoon(minGapMs = 0L)
    }

    private fun onTempReleaseExpired() {
        tempReleaseExpiryTask?.let { main.removeCallbacks(it) }
        tempReleaseExpiryTask = null
        tempReleaseUntilAt = 0L
        tempReleaseReasonText = null
        runCatching {
            app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("temp_release_until", 0L)
                .remove("temp_release_reason")
                .apply()
        }
        if (!inEmergencyPass()) EmergencyCapsule.hide()
        syncGuardianMirror()
        consultPlanSoon(minGapMs = 0L)
        val fg = runCatching { NowFeed.snapshot(SystemClock.elapsedRealtime()).app }.getOrNull()
        if (!fg.isNullOrBlank()) {
            lastFront = null
            onForeground(fg)
            if (sessionActive()) sessionFront(fg, forceCheck = true)
            runCatching { InterceptRuntime.onForeground(fg) }
        }
    }

    fun emergencyRemainingText(now: Long = System.currentTimeMillis()): String? {
        if (!inEmergencyPass(now)) return null
        return EmergencyCountdown.formatRemaining(emergencyUntilAt, emergencyReasonText, now)
    }

    private fun applyEmergencyPassState(until: Long, reason: String?) {
        val now = System.currentTimeMillis()
        val active = until > now
        if (!active) {
            if (emergencyUntilAt > 0L) onEmergencyPassExpired()
            return
        }
        emergencyUntilAt = until
        emergencyReasonText = reason?.takeIf { it.isNotBlank() }?.take(60)
        runCatching {
            app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("emergency_until", until)
                .putString("emergency_reason", emergencyReasonText)
                .apply()
        }
        EmergencyCapsule.show(app, until, emergencyReasonText) {
            main.post { onEmergencyPassExpired() }
        }
        emergencyExpiryTask?.let { main.removeCallbacks(it) }
        val expiry = Runnable { onEmergencyPassExpired() }
        emergencyExpiryTask = expiry
        main.postDelayed(expiry, (until - now + 150L).coerceAtLeast(500L))
        syncGuardianMirror()
    }

    private fun onEmergencyPassExpired() {
        emergencyExpiryTask?.let { main.removeCallbacks(it) }
        emergencyExpiryTask = null
        emergencyUntilAt = 0L
        emergencyReasonText = null
        runCatching {
            app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("emergency_until", 0L)
                .remove("emergency_reason")
                .apply()
        }
        if (!inTempRelease()) EmergencyCapsule.hide()
        syncGuardianMirror()
        consultPlanSoon(minGapMs = 0L)
        val bouncePkg = sessionApp()
        if (!bouncePkg.isNullOrBlank()) {
            runCatching { InterceptRuntime.openTarget(app, bouncePkg) }
        } else {
            val fg = runCatching { NowFeed.snapshot(SystemClock.elapsedRealtime()).app }.getOrNull()
            if (!fg.isNullOrBlank()) {
                val arbiterOutcome = ArbiterShadow.evaluateForEnforce(app, fg)
                if (arbiterOutcome != null) {
                    when {
                        arbiterOutcome.reasonTag == "shadow" -> {
                            LockSafety.shadowHit(app, fg, arbiterOutcome.why, "arbiter")
                        }
                        arbiterOutcome.shouldLock -> {
                            val f = Force(4, fg, arbiterOutcome.why, System.currentTimeMillis() + FORCE_HOLD_MS, refuse = true)
                            force = f
                            enforce(f, fresh = true)
                        }
                    }
                } else if (listed(fg)) {
                    val f = Force(4, fg, lockWhy(pkg = fg) ?: "应急放行已到期，回到当前任务", System.currentTimeMillis() + FORCE_HOLD_MS, refuse = true)
                    force = f
                    enforce(f, fresh = true)
                }
            }
        }
    }

    fun requestEmergencyPass(reason: String, done: (ok: Boolean, until: Long, message: String?) -> Unit) {
        if (!::app.isInitialized) { done(false, 0L, "未初始化"); return }
        main.post {
            fetchPluginRoute(routeCallback { m ->
                val okFlag = m["ok"] == true
                val until = (m["until"] as? Number)?.toLong() ?: 0L
                val now = System.currentTimeMillis()
                if (okFlag && until > now) {
                    force = null
                    companionReleaseTracker.onExplicitRelease()
                    releaseCompanionLock()
                    applyEmergencyPassState(until, reason)
                    consultPlanSoon(minGapMs = 0L)
                    done(true, until, null)
                } else {
                    val err = m["error"] as? Map<*, *>
                    val msg = (err?.get("message") as? String)
                        ?: if (err?.get("code") == "daily-cap") "今日急事通行次数已用完" else "急事放行未通过"
                    done(false, 0L, msg)
                }
            }, "POST", "/emergency-pass", linkedMapOf(
                "reason" to reason.trim().take(120),
            ))
        }
    }

    /** 专注舱 / 锁屏页可用 App 直达清单（优先合并新鲜裁决器白名单 + sessionAllow + Focus 允许列表），去重后返回 (pkg, label)。 */
    fun allowedAppsForFocus(): List<Pair<String, String>> {
        if (!::app.isInitialized) return emptyList()
        loadSession()
        val fState = runCatching { io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.FocusRuntime.state() }.getOrNull()
        val arbiter = runCatching { ArbiterShadow.currentFresh() }.getOrNull()
        val raw = FocusAllowResolver.mergePackages(
            arbiter = arbiter,
            sessionAllow = sessionAllow,
            sessionPkg = sessionPkg,
            focusTaskPkg = fState?.taskPackage,
            focusAllowed = fState?.allowed,
        )
        val pm = app.packageManager
        return raw.filter { pkg ->
            pkg.isNotBlank() &&
                !io.github.ccch1mneyyy.dsh_app.phone.a11y.SystemSafeAllowlist.isSelfPackage(pkg, app.packageName) &&
                runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }.map { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            pkg to label
        }
    }

    /**
     * 真·紧急退出：挂起当前严格锁 / 专注 / 连麦锁 10 分钟并解除桌面死锁，让用户直接回到 DSH 主控制台调整模式，
     * 同时诚实写入督办台账供晚间复盘（绝不假退出回桌面）。
     */
    fun suspendStrictForEmergency(minutes: Int = 10) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        val until = now + minutes.coerceIn(3, 30) * 60_000L
        force = null
        forcedApp = null
        setSession(0L, emptySet(), null, SessionPolicy.DEFAULT_GRACE_MS)
        setRefuseUntil(0L)
        applyEmergencyPassState(until, "紧急退出回 DSH（${minutes}m 保护）")
        runCatching {
            val fg = io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.focus.FocusRuntime
            if (fg.isActive()) fg.pause(minutes.coerceIn(3, 30))
        }
        companionReleaseTracker.onExplicitRelease()
        runCatching { releaseCompanionLock() }
        postEvent("emergency-exit", "minutes" to minutes, "reason" to "lockpage_longpress_dsh") {
            consultPlanSoon(minGapMs = 0L)
        }
    }

    /** 锁屏刷题解锁：拉取一道未过题或开启闯关会话。 */
    fun fetchBankQuizSession(done: (Map<*, *>?) -> Unit) {
        if (!::app.isInitialized) { done(null); return }
        main.post {
            fetchPluginRoute(routeCallback { m ->
                syncFunLeftFromResponse(m)
                done(m)
            }, "POST", "/bank", linkedMapOf("action" to "quiz_session"))
        }
    }

    /** 锁屏刷题判题：提交用户选择；若达到所需题数且服务端直接放行，立即激活统一临时放行并解除当前锁页。 */
    fun submitBankQuizAnswer(questionId: String, answer: String, done: (Map<*, *>?) -> Unit) {
        if (!::app.isInitialized) { done(null); return }
        main.post {
            fetchPluginRoute(routeCallback { m ->
                syncFunLeftFromResponse(m)
                val ok = m["ok"] == true
                val duplicate = m["duplicate"] == true || m["ignored"] == true
                val unlocked = m["unlocked"] == true
                val earnedOnly = m["earnedOnly"] == true || m["needSpend"] == true
                val passActive = m["passActive"] == true
                val spendUntil = (m["spendUntil"] as? Number)?.toLong() ?: 0L
                val unlockUntil = (m["unlockUntil"] as? Number)?.toLong() ?: 0L
                val unlockMinutes = (m["unlockMinutes"] as? Number)?.toInt() ?: 5
                val now = System.currentTimeMillis()
                val grantedUntil = maxOf(unlockUntil, spendUntil)
                val shouldReleaseNow = ok && !duplicate && (
                    (unlocked && !earnedOnly) ||
                    passActive ||
                    spendUntil > now
                )
                if (shouldReleaseNow) {
                    val effectiveUntil = if (grantedUntil > now) grantedUntil else now + unlockMinutes.coerceIn(1, 60) * 60_000L
                    applyTempRelease(effectiveUntil, "刷题达标放行")
                }
                done(m)
            }, "POST", "/bank", linkedMapOf(
                "action" to "quiz_answer",
                "questionId" to questionId,
                "answer" to answer,
            ))
        }
    }

    /** 花挣来的余额放行（POST /seals action=spend）：与刷题/arbiterEnforce 共用统一放行状态。 */
    fun spendBalance(minutes: Int, reason: String, done: (ok: Boolean, until: Long, message: String?) -> Unit) {
        if (!::app.isInitialized) { done(false, 0L, "未初始化"); return }
        val mins = minutes.coerceIn(5, 60)
        val why = reason.trim().take(120)
        main.post {
            fetchPluginRoute(routeCallback { m ->
                syncFunLeftFromResponse(m)
                val okFlag = m["ok"] == true
                val verifySpendUntil = (((m["verify"] as? Map<*, *>)?.get("spend") as? Map<*, *>)?.get("until") as? Number)?.toLong() ?: 0L
                val rawUntil = (m["until"] as? Number)?.toLong() ?: verifySpendUntil
                val now = System.currentTimeMillis()
                if (okFlag) {
                    val effectiveUntil = if (rawUntil > now) rawUntil else now + mins * 60_000L
                    applyTempRelease(effectiveUntil, "花余额放行：${why.take(24)}")
                    tick("spend_balance", force = true)
                    done(true, effectiveUntil, m["text"] as? String)
                } else {
                    val errMap = m["error"] as? Map<*, *>
                    val msg = (m["text"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (errMap?.get("message") as? String)?.takeIf { it.isNotBlank() }
                        ?: (m["error"] as? String)?.takeIf { it.isNotBlank() }
                        ?: "花余额放行未通过"
                    done(false, 0L, msg)
                }
            }, "POST", "/seals", linkedMapOf(
                "action" to "spend",
                "minutes" to mins,
                "reason" to why,
            ))
        }
    }

    /**
     * Gemini 互动舱（/plan 的 session）：舱内只许 allow 里的包名，外加图库/文件/相机这类系统窗口。
     * 存 prefs，App 进程重启后锁还在；到点自己失效。
     */
    @Volatile private var sessionUntil: Long = -1L
    @Volatile private var sessionAllow: Set<String> = emptySet()
    @Volatile private var sessionPkg: String? = null
    /**
     * 这场 session 是谁开的。插件开的（连麦舱）任何时候都能被 /plan 改写；
     * 他自己跟 AI 谈定、由 AI 用 focus 工具开的「工作模式」不许被 /plan 悄悄解掉。
     */
    @Volatile private var sessionOwner: String = OWNER_PLUGIN
    @Volatile private var workWhy: String? = null
    private const val OWNER_PLUGIN = "plugin"
    private const val OWNER_WORK = "work"
    /** Gemini 连麦用的两个包（和插件 gemini.js 的 GEMINI_PACKAGES 同一张表）。 */
    private val GEMINI_PACKAGES = setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox")
    private const val MIN_WORK_MINUTES = 5
    private const val MAX_WORK_MINUTES = 720
    /** 插件下发的宽限（`session.graceMs`）；没给就 45 秒。 */
    @Volatile private var sessionGrace: Long = SessionPolicy.DEFAULT_GRACE_MS
    /** 当前舱内前台和它连续待了多久——宽限期按"连续停留"算，回白名单 App 就清零。 */
    @Volatile private var frontPkg: String? = null
    @Volatile private var frontSince: Long = 0L
    @Volatile private var graceHinted = false
    @Volatile private var sessionStrict: Boolean = false
    private val sessionBounceStamps = ArrayDeque<Long>()
    @Volatile private var lastSessionInAllowPkg: String? = null
    private var installedAppsCache: Pair<Long, List<Map<String, Any?>>>? = null
    private var graceRecheck: Runnable? = null
    private fun loadSession() {
        if (sessionUntil >= 0) return
        val p = app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE)
        sessionUntil = runCatching { p.getLong("sessionUntil", 0L) }.getOrDefault(0L)
        sessionAllow = runCatching { p.getString("sessionAllow", "").orEmpty() }.getOrDefault("")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        sessionPkg = runCatching { p.getString("sessionApp", "").orEmpty() }.getOrDefault("").ifBlank { null }
        sessionOwner = runCatching { p.getString("sessionOwner", "").orEmpty() }.getOrDefault("").ifBlank { OWNER_PLUGIN }
        workWhy = runCatching { p.getString("workWhy", "").orEmpty() }.getOrDefault("").ifBlank { null }
        sessionSince = runCatching { p.getLong("sessionSince", 0L) }.getOrDefault(0L)
        sessionStrict = runCatching { p.getBoolean("sessionStrict", false) }.getOrDefault(false)
        sessionGrace = runCatching { p.getLong("sessionGraceMs", SessionPolicy.DEFAULT_GRACE_MS) }
            .getOrDefault(SessionPolicy.DEFAULT_GRACE_MS)
    }
    fun sessionActive(): Boolean {
        if (!::app.isInitialized) return false
        loadSession()
        return System.currentTimeMillis() < sessionUntil
    }
    fun isStrictSession(): Boolean {
        if (!sessionActive() || !sessionStrict || hasUnifiedRelease()) return false
        if (::app.isInitialized && ArbiterShadow.enforceEnabled(app)) {
            val fresh = ArbiterShadow.currentFresh()
            if (fresh != null && fresh.mode != io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.ArbiterDecision.MODE_ALLOWLIST) {
                return false
            }
        }
        return true
    }
    /** R9：这个舱是不是 Gemini 连麦（锁页的「回去」按钮按它换文案）。 */
    fun sessionIsGemini(): Boolean = sessionApp()?.let { it in GEMINI_PACKAGES } ?: false

    fun sessionApp(): String? {
        if (!::app.isInitialized) return null
        loadSession()
        return sessionPkg ?: sessionAllow.firstOrNull()
    }
    private fun setSession(until: Long, allow: Set<String>, pkg: String?, graceMs: Long, owner: String = OWNER_PLUGIN, why: String? = null, strict: Boolean = false) {
        // 连麦开始时间：手机要在结束时报 gemini-end 的 minutes，而插件的 until 是我们唯一的时间来源。
        if (until > 0 && sessionUntil <= 0) sessionSince = System.currentTimeMillis()
        if (until <= 0) sessionSince = 0L
        sessionUntil = until
        sessionAllow = allow
        sessionPkg = pkg
        sessionOwner = owner
        workWhy = why
        sessionStrict = strict && until > 0L
        // 0 是合法值，不是「没给」：R8 P4 的升级舱要求「不给第三方应用宽限」，
        // 46 秒的宽限等于让他先白刷一分钟。缺省（没有这个键）由 applySession 填默认值。
        sessionGrace = if (graceMs >= 0) graceMs else SessionPolicy.DEFAULT_GRACE_MS
        // 新一场连麦：上一次的前台停留不算数。
        frontPkg = null
        frontSince = 0L
        graceHinted = false
        synchronized(sessionBounceStamps) { sessionBounceStamps.clear() }
        lastSessionInAllowPkg = null
        runCatching {
            app.getSharedPreferences(REFUSE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("sessionUntil", until)
                .putLong("sessionSince", sessionSince)
                .putString("sessionAllow", allow.joinToString(","))
                .putString("sessionApp", pkg.orEmpty())
                .putLong("sessionGraceMs", sessionGrace)
                .putString("sessionOwner", owner)
                .putString("workWhy", why.orEmpty())
                .putBoolean("sessionStrict", sessionStrict)
                .apply()
        }
        if (until > System.currentTimeMillis()) scheduleSessionActiveTick() else cancelSessionActiveTick()
        syncGuardianMirror()
    }

    private fun scheduleSessionActiveTick() {
        main.removeCallbacks(sessionActiveTick)
        main.postDelayed(sessionActiveTick, 1_000L)
    }

    private fun cancelSessionActiveTick() {
        main.removeCallbacks(sessionActiveTick)
    }

    private fun tickSessionActive() {
        if (!::app.isInitialized || !sessionActive()) {
            cancelSessionActiveTick()
            return
        }
        val front = runCatching { NowFeed.snapshot(SystemClock.elapsedRealtime()).app }.getOrNull()
        if (!front.isNullOrBlank()) {
            sessionFront(front, forceCheck = true)
        }
        main.postDelayed(sessionActiveTick, 1_000L)
    }

    @Volatile private var sessionSince: Long = 0L

    /** `/session` 和 `/plan` 解的是同一份 session：解析只写这一处。 */
    /**
     * `/session` 和 `/plan` 解的是同一份 session：解析只写这一处。
     *
     * [force] 是用户自己在界面上点的那一下（开一场 / 结束连麦）：人的动作永远算数，连 AI 钉住的
     * 工作模式也照改。插件自己的 `/plan` 不能 force，否则每次巡检都会把刚锁上的悄悄解开。
     */
    private fun applySession(s: Map<*, *>?, force: Boolean = false) {
        loadSession()
        // 他自己跟 AI 谈定的工作模式还没到期：插件每次 /plan 都带着 session:null，
        // 不挡住的话「刚锁上」会被下一次巡检悄悄解掉。
        if (!force && sessionOwner == OWNER_WORK && System.currentTimeMillis() < sessionUntil) return
        if (s == null) {
            setSession(0L, emptySet(), null, SessionPolicy.DEFAULT_GRACE_MS)
            return
        }
        val allow = (s["allow"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val isAllLock = (s["cell"] as? String) == "all-lock"
        if (allow.isEmpty() && !isAllLock) {
            setSession(0L, emptySet(), null, SessionPolicy.DEFAULT_GRACE_MS)
            return
        }
        setSession(
            (s["until"] as? Number)?.toLong() ?: 0L,
            allow,
            s["app"] as? String,
            (s["graceMs"] as? Number)?.toLong() ?: SessionPolicy.DEFAULT_GRACE_MS,
            // R9：预案的严格模式带一句解释（哪条预案、为什么、怎么解除），锁页照它说。
            why = (s["why"] as? String)?.take(120),
            strict = s["strict"] == true || s["kind"] == "strict" || ((s["graceMs"] as? Number)?.toLong() ?: 1L) <= 0L,
        )
    }

    /** 这一场连麦已经过了多少分钟（结束时报给插件；不知道就 0，插件按"不足 3 分钟"处理）。 */
    private fun sessionMinutes(): Int {
        if (::app.isInitialized) loadSession()
        val since = sessionSince
        if (since <= 0) return 0
        return ((System.currentTimeMillis() - since) / 60_000L).toInt().coerceAtLeast(0)
    }

    /**
     * 工作模式：AI 把跟他谈好的计划钉住的唯一入口。
     *
     * 执行不另写一套——复用 [sessionFront] 那条白名单锁（电话/短信/地图/支付/桌面/输入法永远放行，
     * 名单外第三方 App 立刻锁页）。区别只有两点：宽限给 0 分钟，且 [sessionOwner] 标成 work，
     * 于是插件的 /plan 不会把它解掉。
     *
     * @param mode "on" 开始 / "off" 结束 / 其他一律按「查状态」处理
     */
    fun workMode(mode: String, allow: List<String>, minutes: Int, why: String?): String {
        // l10n:keep agent tool result
        if (!::app.isInitialized) return "DSH 还没启动好，稍后再说。"
        loadSession()
        val now = System.currentTimeMillis()
        if (mode == "off") {
            return if (now < sessionUntil) {
                setSession(0L, emptySet(), null, SessionPolicy.DEFAULT_GRACE_MS)
                force = null
                forcedApp = null
                companionReleaseTracker.onExplicitRelease()
                runCatching { releaseCompanionLock() }
                // l10n:keep agent tool result
                "当前工作/专注限制已解除，App 现在恢复可用。"
            } else {
                // l10n:keep agent tool result
                "现在没有进行中的工作模式或限制舱。"
            }
        }
        if (mode == "on") {
            // l10n:keep agent tool result
            if (minutes <= 0) return "开工作模式要说清多久（minutes，5–720）。"
            val mins = minutes.coerceIn(MIN_WORK_MINUTES, MAX_WORK_MINUTES)
            val allowed = allow.filter { it.isNotBlank() }.toSet()
            // l10n:keep agent tool result
            if (allowed.isEmpty()) return "开工作模式要先给 allow：至少一个允许使用的 App 包名（用 apps 查）。"
            setSession(now + mins * 60_000L, allowed, null, 0L, OWNER_WORK, why?.take(80), strict = true)
            // 现在就判一次：他可能已经停在名单外的 App 里，不该等到下次切换才锁。
            sessionFront(NowFeed.snapshot(SystemClock.elapsedRealtime()).app, forceCheck = true)
            // l10n:keep agent tool result
            return "已进入工作模式，$mins 分钟后自动结束：只有 ${allowed.joinToString("、")} 能用，" +
                "其余第三方 App 一进去就被锁页挡住；电话、短信、地图、支付和桌面不受影响。"
        }
        return if (now < sessionUntil) {
            val left = ((sessionUntil - now) / 60_000L).coerceAtLeast(1L)
            // l10n:keep agent tool result
            val who = if (sessionOwner == OWNER_WORK) "工作模式" else "连麦舱"
            // l10n:keep agent tool result
            "${who}进行中：还剩 $left 分钟，允许 ${sessionAllow.joinToString("、")}。"
        } else {
            // l10n:keep agent tool result
            "现在没有工作模式，也没有连麦舱。"
        }
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
        if (!::app.isInitialized) return
        // 锁相关的事件（打开被拦的 App、紧急退出、追问锁到点）跟着锁走，不看「主动陪伴」开关；其余照旧。
        if (!proactiveEnabled() && type !in LOCK_EVENTS) return
        val body = linkedMapOf<String, Any?>("type" to type)
        for ((key, value) in fields) if (value != null) body[key] = value
        // onForeground comes off the accessibility thread; routeAnswers is main-thread only.
        main.post { fetchPluginRoute(routeCallback { m -> then?.invoke(m) }, "POST", "/event", body) }
    }

    /** 亮屏：只记账。晨间门开不开由插件按作息判定，所以顺手问一次 `/plan`。 */
    fun onScreenOn() = postEvent("screen-on") {
        updateShakeSensor(true)
        consultPlanSoon(minGapMs = 0L)
    }

    @Volatile private var lastInterceptInfo: Map<String, Any?>? = null

    private fun resolveAppLabel(pkg: String): String {
        if (!::app.isInitialized || pkg.isBlank()) return pkg
        return runCatching {
            val pm = app.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
    }

    private fun recordInterceptInfo(pkg: String, label: String?, reason: String?, priority: String?): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val fresh = runCatching { ArbiterShadow.currentFresh(now) }.getOrNull()
        val resolvedLabel = label?.takeIf { it.isNotBlank() } ?: resolveAppLabel(pkg)
        val resolvedReason = reason?.takeIf { it.isNotBlank() }
            ?: fresh?.why?.takeIf { it.isNotBlank() }
            ?: "专注护栏拦截"
        val resolvedPriority = priority?.takeIf { it.isNotBlank() } ?: fresh?.priority.orEmpty()
        return linkedMapOf<String, Any?>(
            "pkg" to pkg,
            "label" to resolvedLabel,
            "reason" to resolvedReason,
            "priority" to resolvedPriority,
            "at" to now,
        ).also { lastInterceptInfo = it }
    }

    /** 手机端真实触发拦截/锁屏时同步记录并上报给伴侣诊断探针（带 2 秒防重防抖）。 */
    fun onInterceptHit(pkg: String, label: String?, reason: String?, priority: String? = null) {
        if (!::app.isInitialized || pkg.isBlank()) return
        val now = System.currentTimeMillis()
        val prev = lastInterceptInfo
        val prevAt = (prev?.get("at") as? Number)?.toLong() ?: 0L
        val info = recordInterceptInfo(pkg, label, reason, priority)
        if (prev?.get("pkg") == pkg && now - prevAt < 2_000L) return
        postEvent(
            "intercept-hit",
            "app" to pkg,
            "label" to info["label"],
            "reason" to info["reason"],
            "priority" to info["priority"],
            "at" to info["at"],
        )
    }

    /**
     * 他打开了被拦的 App：挂着的义务按这次动作再问一次（插件只问一次，做过 `askedAt`）。
     * `followup` 非空说明插件开了追问锁，界面要靠下一次 `/plan` 才拿到，所以立刻问。
     */
    fun onBlockedOpen(pkg: String, label: String? = null, reason: String? = null, priority: String? = null) {
        val info = recordInterceptInfo(pkg, label, reason, priority)
        postEvent(
            "blocked-open",
            "app" to pkg,
            "label" to info["label"],
            "reason" to info["reason"],
            "priority" to info["priority"],
            "at" to info["at"],
        ) { m ->
            if (m["followup"] != null) consultPlanSoon(minGapMs = 0L)
        }
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

    // ---------------------------------------------------------------- R4-4 定时连麦（接口约定 D4）

    /** 下一场预约连麦的时刻（插件给的绝对毫秒）；0 = 没有预约。 */
    @Volatile private var geminiDueAt: Long = 0L
    @Volatile private var lastAutoPullAt: Long = 0L
    @Volatile private var lastPlanModalShownAt: Long = 0L
    @Volatile private var lastGeminiVoiceActive: Boolean = false
    @Volatile private var lastGeminiVoiceStartedAt: Long = 0L
    @Volatile private var lastGeminiVoiceActiveAt: Long = 0L
    @Volatile private var lastGeminiVoiceDroppedAt: Long = 0L
    @Volatile private var lastLiveDurationSec: Long = 0L
    @Volatile private var lastPostLiveRelayPromptAt: Long = 0L
    @Volatile private var liveCoreAlwaysOn: Boolean = false
    @Volatile private var liveCoreStrict: Boolean = true
    @Volatile private var liveCoreWantShare: Boolean = true
    @Volatile private var liveCoreHeadsetKeepalive: Boolean = true
    @Volatile private var liveCoreTextGraceUntil: Long = 0L
    private val shownSparkIds = LinkedHashSet<String>()
    private const val AUTO_PULL_COOLDOWN_MS = 5 * 60_000L

    private fun rememberSparkId(id: String) {
        if (id.isBlank()) return
        if (shownSparkIds.size >= 64) {
            shownSparkIds.firstOrNull()?.let { shownSparkIds.remove(it) }
        }
        shownSparkIds.add(id)
    }

    /**
     * 到点：取 launch → 简报进剪贴板 → 雾蓝悬浮桥接 / 无障碍破权拉起 Gemini → 马上问一次 /plan，拿连麦舱
     * （插件取 launch 时已经把舱开好了，手机按 /plan 的 session 上"只许 Gemini"）。
     */
    private fun fireGeminiDue() {
        if (!::app.isInitialized || !proactiveEnabled()) return
        main.post {
            fetchPluginRoute(routeCallback { m ->
                val launch = m["launch"] as? Map<*, *> ?: return@routeCallback
                val brief = (launch["brief"] as? String).orEmpty()
                val topic = (launch["topic"] as? String).orEmpty()
                val mode = (launch["mode"] as? String)?.takeIf { it.isNotBlank() } ?: "countdown_3s"
                val preferVoice = launch["preferVoice"] == true
                GeminiAutoLauncher.launch(
                    app = app,
                    topic = topic.ifBlank { "约好的 Gemini 共脑时间到了" },
                    brief = brief,
                    mode = mode,
                    preferVoice = preferVoice,
                ) { opened ->
                    Log.i("DshProactive", "gemini due: topic=$topic opened=$opened")
                }
                consultPlanSoon(minGapMs = 0L)
            }, "POST", "/gemini/due", linkedMapOf<String, Any?>())
        }
    }

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
            while (switches.isNotEmpty() && tNow - switches.first().first > 60 * 60_000L) switches.removeFirst()
        }
        if (coordinator == null) return
        // App switches come in bursts; 15 s is enough to not hammer the plugin and still feel immediate.
        // But a restless burst (5 switches in 90 s) is exactly the moment to ask now.
        val restless = synchronized(switches) { switches.count { tNow - it.first <= 90_000L } >= 5 }
        if (restless) consultPlanSoon(minGapMs = 5_000L)
        val now = System.currentTimeMillis()
        if (hasUnifiedRelease(now)) {
            consultPlanSoon(minGapMs = 15_000L)
            return
        }
        // H5: 当 arbiterEnforce 开启且已有新鲜裁决器输出时，直接按 ArbiterDecision.decide 执法（永不锁/测试模式/紧急解除仍优先于一切）。
        val arbiterOutcome = ArbiterShadow.evaluateForEnforce(app, packageName)
        if (arbiterOutcome != null) {
            when {
                arbiterOutcome.reasonTag == "shadow" -> {
                    LockSafety.shadowHit(app, packageName, arbiterOutcome.why, "arbiter")
                }
                arbiterOutcome.shouldLock -> {
                    val f = Force(4, packageName, arbiterOutcome.why, now + FORCE_HOLD_MS, refuse = true)
                    force = f
                    enforce(f, fresh = true)
                    onBlockedOpen(packageName)
                }
                else -> {
                    if (force?.app == packageName) force = null
                }
            }
            consultPlanSoon(minGapMs = 15_000L)
            return
        }
        // 拒绝模式: lock now, at the door; the /plan answer would come a second later.
        // C2: also with the engine dead — this reads only the cached rules and the local clock.
        if (now < lockUntil(now) && listed(packageName)) {
            val f = Force(4, packageName, lockWhy(now, packageName) ?: "拒绝模式", now + FORCE_HOLD_MS, refuse = true)
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
                    companionReleaseTracker.onExplicitRelease()
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
     * 前台换了：Gemini 互动舱里按分级判定处理，不等下一次 /plan（/plan 有 60 秒节流）。
     * 娱乐 App 立刻锁；系统窗口/桌面/白名单放行；其余第三方 App 先宽限，超时才锁。
     */
    fun onFrontChanged(pkg: String?) {
        if (!::app.isInitialized) return
        sessionFront(pkg)
    }

    /** 应用管理页的「Gemini 互动」：开 / 关一场连麦舱（POST /session）。 */
    fun geminiSession(action: String, topic: String?, minutes: Int, mode: String?, done: (Map<*, *>?) -> Unit) {
        if (!::app.isInitialized) { done(null); return }
        // 连麦结束：先按第三轮契约报 gemini-end（满 3 分钟插件会开追问锁），再收本地舱。
        if (action == "end") onGeminiEnd(sessionMinutes())
        main.post {
            fetchPluginRoute(routeCallback { m ->
                // `/session` 和 `/plan` 解的是同一份 session：解析只写一次（见 applySession）。
                // 这里是用户自己点的：force=true，AI 钉住的工作模式也能被他亲手结束。
                applySession(if (action == "end") null else m["session"] as? Map<*, *>, force = true)
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
        if (pkg == null) return false
        val unifiedRelease = hasUnifiedRelease()
        val arbiterOutcome = if (!screenOn || unifiedRelease) null else ArbiterShadow.evaluateForEnforce(app, pkg)
        val legacyListed = if (arbiterOutcome == null) listed(pkg) else false
        val hasPass = if (arbiterOutcome == null && legacyListed) {
            runCatching { InterceptRuntime.hasPass(pkg) }.getOrDefault(false)
        } else false
        return DefiancePolicy.isDefiant(
            screenOn = screenOn,
            unifiedRelease = unifiedRelease,
            arbiterShouldLock = arbiterOutcome?.shouldLock,
            legacyListed = legacyListed,
            hasLegacyPass = hasPass,
        )
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

    private val LOCK_EVENTS = setOf("blocked-open", "intercept-hit", "emergency-exit", "followup-timeout")

    private fun reportDefiance(front: String, source: String) {
        // 违抗复查是锁的一部分（关掉锁页 15 秒又回娱乐 = 升级拒绝模式），同样不看「主动陪伴」开关。
        if (!::app.isInitialized) return
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

    @Volatile private var lastSafetyNotifAt = 0L

    /**
     * 本地紧急解除（[LockSafety.disarm]）：放风到 [until]，撤掉记着的力度锁，再尽力告诉插件记账。
     * 引擎不在也照样生效：放风只看本地 prefs，离线时先持久化 [disarmId]，重连后幂等补传。
     */
    fun onLocalDisarm(
        until: Long,
        reason: String,
        counted: Boolean,
        disarmId: String = "",
        source: String = if (counted) "notification" else "adb",
    ) {
        if (!::app.isInitialized) return
        setBreakUntil(until)
        force = null
        forcedApp = null
        companionReleaseTracker.onExplicitRelease()
        runCatching { releaseCompanionLock() }
        val pending = LockSafety.pendingDisarms(app)
        if (pending.isNotEmpty()) {
            syncPendingDisarms()
        } else {
            runCatching {
                val body = linkedMapOf<String, Any?>(
                    "type" to "disarm",
                    "reason" to reason.take(80),
                    "until" to until,
                    "counted" to counted,
                    "source" to source,
                )
                if (disarmId.isNotBlank()) body["disarmId"] = disarmId
                fetchPluginRoute(routeCallback { }, "POST", "/event", body)
            }.onFailure { Log.w(TAG, "disarm report", it) }
        }
        consultPlanSoon(minGapMs = 0L)
    }

    /** 幂等补传离线期间本地记录的紧急解除 / Gemini 延后事件。 */
    fun syncPendingDisarms() {
        if (!::app.isInitialized || agentPort == null) return
        val pending = LockSafety.pendingDisarms(app)
        if (pending.isEmpty()) return
        main.post {
            if (agentPort == null) return@post
            for (item in pending) {
                fetchPluginRoute(
                    routeCallback { res ->
                        val errCode = (res["error"] as? Map<*, *>)?.get("code") as? String
                        val acked = res["ok"] == true ||
                            res["idempotent"] == true ||
                            res["error"] == null ||
                            errCode == "daily-cap-reached"
                        if (acked) {
                            LockSafety.ackDisarmSynced(app, item.disarmId)
                        }
                    },
                    "POST",
                    "/event",
                    linkedMapOf(
                        "type" to "disarm",
                        "disarmId" to item.disarmId,
                        "reason" to item.reason.take(80),
                        "until" to item.until,
                        "counted" to item.counted,
                        "source" to item.source,
                    ),
                )
            }
        }
    }

    /** 说到做到: 赶 = back to the launcher first; then 锁 the app behind a drill. */
    private fun enforce(f: Force, fresh: Boolean = false) {
        // 2026-11-19：锁不再看「主动陪伴」开关。那个开关管的是姐妹主动开口（浮层、提醒），
        // 不是锁——以前它默认关，重装后所有锁（严格局、接管、超额封禁）在这里静默返回，表现为「什么都锁不住」。
        // 锁的闸门只有三道：测试模式（shadowHit）、紧急解除、永不锁名单（桌面 / DSH / 系统 / 输入法 / 代理）。
        if (!::app.isInitialized) return
        if (hasUnifiedRelease()) return
        if (LockSafety.disarmed(app)) {
            Log.i(TAG, "disarmed: skip force on ${f.app}")
            return
        }
        // [fresh]: called with the foreground switch itself; NowFeed may not have caught up yet.
        if (f.app in NEVER_LOCK) return
        // 锁机事故总闸：DSH 自己 / 桌面 / 系统界面 / 输入法 / 代理永远不锁（锁页本身就是 DSH）。
        if (LockSafety.neverLock(app, f.app)) {
            Log.i(TAG, "never-lock: skip force on ${f.app}")
            return
        }
        val front = NowFeed.snapshot(SystemClock.elapsedRealtime()).app
        if (!fresh && front != f.app) return
        val arbiterOutcome = ArbiterShadow.evaluateForEnforce(app, f.app)
        if (arbiterOutcome != null) {
            when {
                arbiterOutcome.reasonTag == "shadow" -> {
                    LockSafety.shadowHit(app, f.app, arbiterOutcome.why, "companion-arbiter")
                    return
                }
                !arbiterOutcome.shouldLock -> {
                    if (force?.app == f.app) force = null
                    Log.i(TAG, "arbiterEnforce open: skip force on ${f.app} (${arbiterOutcome.reasonTag})")
                    return
                }
                else -> {
                    if (VisibleSurface.isHeldBy(VisibleSurface.OWNER_PROACTIVE)) {
                        VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, null)
                    }
                    val shown = runCatching {
                        InterceptRuntime.forceBlock(
                            app,
                            f.app,
                            null,
                            arbiterOutcome.why,
                            strict = true,
                            unlockHow = arbiterOutcome.unlockHow,
                        )
                    }.getOrDefault(false)
                    if (shown) companionReleaseTracker.onEnforced()
                    Log.i(TAG, "arbiterEnforce force=${f.level} ${f.app} shown=$shown")
                    return
                }
            }
        }
        // 测试模式：只提示「这里本来会锁」，不真锁。
        if (LockSafety.shadowHit(app, f.app, f.why, "companion")) return
        if (f.refuse && VisibleSurface.isHeldBy(VisibleSurface.OWNER_PROACTIVE)) {
            // An overlay still holds the one-screen slot after its event closed (he chatted in it):
            // 拒绝模式 must not lose to that. The overlay window itself stays where it is.
            VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, null)
        }
        // 直接覆盖展示专属锁屏界面，不再反复发 GLOBAL_ACTION_HOME 把人踢回 vivo 桌面导致与桌面死循环互弹。
        val shown = runCatching { InterceptRuntime.forceBlock(app, f.app, f.why, strict = f.refuse) }.getOrDefault(false)
        if (shown) companionReleaseTracker.onEnforced()
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
        // 测试模式开关跟插件走（插件默认开；他在中枢或对姐妹说「结束测试模式」才关）。
        (map["shadow"] as? Boolean)?.let { LockSafety.setShadow(app, it) }
        (map["neverLock"] as? List<*>)?.mapNotNull { it as? String }?.let { LockSafety.setPluginNeverLock(app, it) }
        // H1/H2：从 /plan 同步强度档对应的每日紧急解除上限（默认 3，严格档 2）。
        val planCap = (map["emergencyDisarmDailyCap"] as? Number)?.toInt()
            ?: ((map["intensity"] as? Map<*, *>)?.get("emergencyDisarmDailyCap") as? Number)?.toInt()
        if (planCap != null && planCap > 0) LockSafety.setDailyCap(app, planCap)
        // H5：同步裁决器执法开关（默认开）并更新裁决器输出。
        (map["arbiterEnforce"] as? Boolean)?.let { ArbiterShadow.setEnforceEnabled(app, it) }
        runCatching { ArbiterShadow.update(map["lock"]) }
        val nowNotif = System.currentTimeMillis()
        if (nowNotif - lastSafetyNotifAt > 5 * 60_000L) {
            lastSafetyNotifAt = nowNotif
            LockSafety.ensureNotification(app)
        }
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
        // A6：锁页的「哪条预案、怎么解除」。插件只给带 why 的那几条（阶梯锁、预案锁），其余为空。
        blockWhys = (map["blockWhy"] as? List<*>)?.mapNotNull { e ->
            val m = e as? Map<*, *> ?: return@mapNotNull null
            val app = m["app"] as? String ?: return@mapNotNull null
            val why = m["why"] as? String ?: return@mapNotNull null
            if (app.isBlank() || why.isBlank()) null else app to why
        }?.toMap() ?: emptyMap()
        // Gemini 互动舱：插件给 allow 就进入"只许 Gemini"，null 就退出（插件明示解锁时可强清 AI 工作模式）。
        val sessionMap = map["session"] as? Map<*, *>
        lastSessionBy = sessionMap?.get("by") as? String
        FunBalanceCache.update(((map["timeLedger"] as? Map<*, *>)?.get("funLeft") as? Number)?.toInt())
        activeBoutScopeKey = ((map["bout"] as? Map<*, *>)?.get("id") as? String)?.takeIf { it.isNotBlank() }
            ?: (sessionMap?.get("boutId") as? String)?.takeIf { it.isNotBlank() }
            ?: (sessionMap?.get("sealId") as? String)?.takeIf { it.isNotBlank() }
            ?: ((map["lockArbiter"] as? Map<*, *>)?.get("boutId") as? String)?.takeIf { it.isNotBlank() }
            ?: ((map["lockArbiter"] as? Map<*, *>)?.get("sealId") as? String)?.takeIf { it.isNotBlank() }
        applySession(sessionMap, force = map["clearWorkSession"] == true)
        val refusing = System.currentTimeMillis() < lockUntil()
        force = if (level >= 3 && !target.isNullOrBlank()) {
            // C5: while a rule lock is in force the page names only the primary lock's requirement.
            Force(level, target, (if (refusing) lockWhy() else null) ?: why, System.currentTimeMillis() + FORCE_HOLD_MS, refuse = refusing)
        } else null
        // 只在 companion 锁刚从锁态释放那一拍解锁，禁止在本来没有活跃 companion 锁时每拍重复调用 releaseCompanionLock()。
        val hasActiveCompanionLock = runCatching { InterceptRuntime.hasOpenCompanionLock() }.getOrDefault(false)
        if (companionReleaseTracker.shouldReleaseOnPlan(force != null, f?.get("release") == true, hasActiveCompanionLock)) {
            releaseCompanionLock()
        }
        val pendingForce = force
        if (pendingForce != null) {
            val forceOutcome = ArbiterShadow.evaluateForEnforce(app, pendingForce.app)
            if (forceOutcome != null && !forceOutcome.shouldLock && forceOutcome.reasonTag != "shadow") {
                force = null
            } else {
                enforce(pendingForce)
            }
        }
        val fgNow = NowFeed.snapshot(SystemClock.elapsedRealtime()).app
        if (!fgNow.isNullOrBlank() && force?.app != fgNow && !hasUnifiedRelease(stamp) && ArbiterShadow.enforceEnabled(app)) {
            val outcome = ArbiterShadow.evaluateForEnforce(app, fgNow)
            if (outcome != null && outcome.shouldLock) {
                enforce(Force(4, fgNow, outcome.why, System.currentTimeMillis() + FORCE_HOLD_MS, refuse = true))
            } else if (outcome?.reasonTag == "shadow") {
                LockSafety.shadowHit(app, fgNow, outcome.why, "companion-arbiter")
            }
        }
        (map["media"] as? String)?.takeIf { it == "pause" || it == "resume" }?.let { PhoneMediaControl.control(app, it) }
        // 连麦舱：同一份分级判定（sessionFront），不再各写一份"非白名单就锁"。
        if (sessionActive()) sessionFront(fgNow)
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
        // R4-4 定时连麦：插件只在预约还没到点时下发 geminiSchedule，所以记住最后一次看到的时间，
        // 到点被唤醒时自己去取 launch（/gemini/due 取完即置已发，重复调用拿到 null）。
        val geminiAt = ((map["geminiSchedule"] as? Map<*, *>)?.get("at") as? Number)?.toLong() ?: 0L
        if (geminiAt > stamp) geminiDueAt = geminiAt
        if (geminiDueAt in 1..stamp) {
            geminiDueAt = 0L
            fireGeminiDue()
        }
        val lcMap = map["liveCore"] as? Map<*, *>
        if (lcMap != null) {
            liveCoreAlwaysOn = lcMap["alwaysOnLive"] == true
            liveCoreStrict = lcMap["keeperStrict"] != false
            liveCoreWantShare = lcMap["wantScreenShare"] != false
            liveCoreHeadsetKeepalive = lcMap["headsetKeepalive"] != false
            liveCoreTextGraceUntil = (lcMap["textGraceUntil"] as? Number)?.toLong() ?: 0L
        }
        val autoPull = map["geminiAutoPull"] as? Map<*, *>
        var autoPulledNow = false
        val interceptHeldForPull = VisibleSurface.isHeldBy(VisibleSurface.OWNER_INTERCEPT) ||
            VisibleSurface.isHeldBy(VisibleSurface.OWNER_FOCUS)
        if (autoPull != null && autoPull["trigger"] != false && autoPull["active"] != false &&
            (!interceptHeldForPull || autoPull["force"] == true)
        ) {
            val pullTopic = (autoPull["topic"] as? String).orEmpty()
            val pullBrief = (autoPull["brief"] as? String).orEmpty()
            val pullMode = (autoPull["mode"] as? String)?.takeIf { it.isNotBlank() }
                ?: (autoPull["pullMode"] as? String)?.takeIf { it.isNotBlank() }
                ?: "countdown_3s"
            val preferVoice = autoPull["preferVoice"] == true
            val voiceOnly = autoPull["voiceOnly"] == true
            val autoClickLive = autoPull["autoClickLive"] == true
            val requestScreenShare = autoPull["requestScreenShare"] == true
            val pullUrgent = autoPull["urgent"] == true || autoPull["earlyBailout"] == true
            val pullCooldownMs = LiveKeeperPolicy.effectivePullCooldownMs(pullUrgent)
            val voiceLine = (autoPull["voiceLine"] as? String).orEmpty()
            val pullStamp = (autoPull["at"] as? Number)?.toLong() ?: stamp
            if ((pullTopic.isNotBlank() || pullBrief.isNotBlank()) &&
                stamp - lastAutoPullAt >= pullCooldownMs &&
                pullStamp > stamp - pullCooldownMs
            ) {
                lastAutoPullAt = stamp
                autoPulledNow = true
                if (voiceOnly) {
                    if (voiceLine.isNotBlank()) {
                        val spoken = VoicePlan.brief(voiceLine) ?: voiceLine.trim().take(VoicePlan.MAX_CHARS)
                        if (spoken.isNotEmpty()) {
                            runCatching {
                                VoiceOut.install(app)
                                VoiceOut.speak(VoicePlan(spoken, VoicePlan.DEFAULT_RATE, VoicePlan.DEFAULT_PITCH)) {}
                            }
                        }
                    }
                    Log.i(TAG, "geminiAutoPull(voiceOnly): topic=$pullTopic")
                } else if (autoClickLive) {
                    GeminiAutoLauncher.launchAndAutomateLive(
                        app = app,
                        topic = pullTopic,
                        brief = pullBrief,
                        voiceLine = voiceLine,
                        mode = pullMode,
                        autoClickLive = true,
                        requestScreenShare = requestScreenShare,
                    ) { opened ->
                        Log.i(TAG, "geminiAutoPull(live): topic=$pullTopic mode=$pullMode share=$requestScreenShare opened=$opened")
                    }
                } else {
                    GeminiAutoLauncher.launch(
                        app = app,
                        topic = pullTopic,
                        brief = pullBrief,
                        mode = pullMode,
                        preferVoice = preferVoice,
                    ) { opened ->
                        Log.i(TAG, "geminiAutoPull: topic=$pullTopic mode=$pullMode opened=$opened")
                    }
                }
            }
        }
        // 超级大肥鱼 v4：强制计划模态弹窗 & 高频战术浮窗（与锁屏页/专注页互斥；planModal 90s 去重；superSpark 按 id 去重）。
        val interceptHeld = VisibleSurface.isHeldBy(VisibleSurface.OWNER_INTERCEPT) ||
            VisibleSurface.isHeldBy(VisibleSurface.OWNER_FOCUS)
        val planModalSpec = PlanModalPolicy.parseSpec(map["planModal"])
        val sparkSpec = SuperSparkOverlayPolicy.parseSpec(map["superSpark"])
        if (PlanModalPolicy.shouldShow(
                spec = planModalSpec,
                lastShownAtMs = lastPlanModalShownAt,
                nowMs = stamp,
                interceptHeld = interceptHeld,
                alreadyShowing = ProactiveOverlay.isPlanModalShowing(),
            )
        ) {
            if (ProactiveOverlay.showPlanModal(planModalSpec!!)) {
                lastPlanModalShownAt = stamp
            }
        } else if (!autoPulledNow && SuperSparkOverlayPolicy.shouldShow(
                spec = sparkSpec,
                shownIds = shownSparkIds,
                interceptHeld = interceptHeld,
                planModalShowing = ProactiveOverlay.isPlanModalShowing(),
                chattingActive = ProactiveOverlay.hasActiveChat(),
            )
        ) {
            val spark = sparkSpec!!
            val sparkToken = "spark:${spark.id}"
            val canClaim = VisibleSurface.isFree() || VisibleSurface.isHeldBy(VisibleSurface.OWNER_PROACTIVE)
            if (canClaim && VisibleSurface.claim(VisibleSurface.OWNER_PROACTIVE, sparkToken)) {
                val shown = runCatching { ProactiveOverlay.showSuperSpark(spark) }.getOrDefault(false)
                if (shown) {
                    rememberSparkId(spark.id)
                    if (spark.voiceLine.isNotBlank()) {
                        val spoken = VoicePlan.brief(spark.voiceLine)
                            ?: spark.voiceLine.trim().take(VoicePlan.MAX_CHARS)
                        if (spoken.isNotEmpty()) {
                            runCatching {
                                VoiceOut.speak(VoicePlan(spoken, VoicePlan.DEFAULT_RATE, VoicePlan.DEFAULT_PITCH)) {}
                            }
                        }
                    }
                } else {
                    VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, sparkToken)
                }
            }
        }
        // 3 分钟应急急事通行（/plan 的 emergencyPass）：跨重启/轮询同步胶囊倒计时，到期立刻撤除并复查。
        val ep = map["emergencyPass"] as? Map<*, *>
        val epUntil = if (ep?.get("active") == true) (ep["until"] as? Number)?.toLong() ?: 0L else 0L
        applyEmergencyPassState(epUntil, ep?.get("reason") as? String)
        // 同步伴侣插件下发的内核时钟任务（Cron / 循环计时 / 循环闹钟）
        val remoteClock = (map["clockTasks"] as? List<*>)?.mapNotNull { ClockTask.fromMap(it as? Map<*, *>) }
        if (remoteClock != null) {
            clockEngine?.syncFromPlugin(remoteClock, clocks())
        }
        val epWake = if (emergencyUntilAt > stamp) emergencyUntilAt else 0L
        val wakeAt = listOf(at, change, followupUntilAt, geminiDueAt, epWake).filter { it > 0 }.minOrNull() ?: 0L
        val speak = map["speak"] == true
        val reason = map["reason"] as? String ?: ""
        syncGuardianMirror()
        flushGuardianOfflineSoon()
        syncPendingDisarms()
        if (wakeAt <= 0 && remoteClock == null) return
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
        if (wakeAt > 0L) {
            companionPlan = CompanionPlan(false, wakeAt, reason, System.currentTimeMillis())
            scheduler?.setNextDueAt(wakeAt, clocks())
        }
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
                        // B4：有连发计划就先只上第一段，其余按 delayMs 补进同一个窗口。
                        val plan = cmd.content.burst
                        val text = plan?.segments?.firstOrNull() ?: cmd.content.text
                        val overlay = runCatching { ProactiveOverlay.show(cmd.token, text, cmd.deadline) }.getOrDefault(false)
                        val ok = overlay || ProactivePageActivity.start(app, cmd.token, cmd.content.text, cmd.deadline)
                        if (!ok) VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, cmd.token)
                        // 连发只走浮层：Activity 是一张一次性页面，追加不了话。
                        else if (overlay && plan != null) scheduleBurst(cmd.token, plan)
                        // B5：念出来。跟浮层成没成功无关——通知兜底时她照样能说话。
                        // 念不念得出来由 VoiceOut 判（勿扰/音量/连麦舱/TTS 没起），结果回账本。
                        cmd.content.voice?.let { v -> speakVoice(cmd.token, v) }
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

    /**
     * B4：把第 2 段起的部分按 delayMs 依次补进同一个浮层。
     *
     * 窗口换了或事件关掉了，[ProactiveOverlay.say] 返回 false，这一段就丢掉——话已经晚了，
     * 不能为了一句补白另开一个窗口。这里不取消已排的回调：它们最多一次 no-op。
     */
    private fun scheduleBurst(token: String, plan: BurstPlan) {
        var at = 0L
        for (i in 1 until plan.segments.size) {
            at += plan.delaysMs.getOrElse(i - 1) { BurstPlan.DEFAULT_DELAY_MS }
            val line = plan.segments[i]
            main.postDelayed({ ProactiveOverlay.say(token, line) }, at)
        }
    }

    /**
     * B5：把这一条念出来，然后把"到底念了没有"记进账本。
     *
     * 一球只念第一句（[VoicePlan.brief] 裁好的那一段）：连发后面几段只上屏——QUEUE_FLUSH 会把
     * 前一段掐掉，几秒内连念三条听着像抢话。这条不在插件那侧判（插件不知道音量和勿扰），
     * 所以回报走 `/event` 的 `voice-out`，面板在账本里看得到每一次降级的原因。
     */
    private fun speakVoice(token: String, plan: VoicePlan) {
        VoiceOut.speak(plan) { why -> reportVoice(token, plan, why) }
    }

    /** B5 设置页试听：绕开插件总开关，直接让系统 TTS 念一句。null = 真的念完了。 */
    fun tryVoice(done: (String?) -> Unit) {
        if (!::app.isInitialized) {
            done(VoiceOut.WHY_NO_TTS)
            return
        }
        main.post { VoiceOut.trySay(done) }
    }

    private fun reportVoice(token: String, plan: VoicePlan, why: String?) {
        if (!::app.isInitialized) return
        main.post {
            fetchPluginRoute(routeCallback { }, "POST", "/event", linkedMapOf(
                "type" to "voice-out",
                "requestId" to token,
                "ok" to (why == null),
                "why" to (why ?: ""),
                "chars" to plan.text.length,
                "rate" to plan.rate,
                "pitch" to plan.pitch,
            ))
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
        syncGuardianMirror()
        flushGuardianOfflineSoon()
        syncPendingDisarms()
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
            protectedForeground = io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.ProtectedForeground.isProtected(fg),
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
        val cNow = clocks()
        val clockRes = clockEngine?.tick(cNow)
        if (clockRes != null && clockRes.fires.isNotEmpty()) {
            handleClockFires(clockRes.fires)
        }
        val plan = companionPlan
        val schedulerWake = if (plan != null && agentPort != null) {
            plan.at.coerceAtLeast(cNow.wall + 5_000L)
        } else {
            s.nextWake(cNow, mode)
        }
        val nextWake = listOfNotNull(schedulerWake, clockRes?.nextWakeWall).filter { it > 0L }.minOrNull()
        WakeAlarm.set(app, nextWake, mode, clockRes?.nextUrgentWakeWall)
        main.removeCallbacks(highFreqClockTick)
        val highFreqMs = clockRes?.highFreqIntervalMs
        if (highFreqMs != null) {
            main.postDelayed(highFreqClockTick, highFreqMs.coerceIn(250L, 5_000L))
        }
    }

    private fun tickClockHighFreq() {
        if (!::app.isInitialized) return
        val ck = clockEngine ?: return
        val res = ck.tick(clocks())
        if (res.fires.isNotEmpty()) {
            handleClockFires(res.fires)
        }
        val hf = res.highFreqIntervalMs
        if (hf != null) {
            main.postDelayed(highFreqClockTick, hf.coerceIn(250L, 5_000L))
        }
        val mode = WakeMode.of(caps())
        val plan = companionPlan
        val cNow = clocks()
        val schedWake = if (plan != null && agentPort != null) plan.at else scheduler?.nextWake(cNow, mode)
        val mergedWake = listOfNotNull(schedWake, res.nextWakeWall).filter { it > 0L }.minOrNull()
        WakeAlarm.set(app, mergedWake, mode, res.nextUrgentWakeWall)
    }

    fun onSystemClockJump(reason: String) {
        if (!::app.isInitialized) return
        clockEngine?.onClockJump(clocks())
        tick(reason)
    }

    fun clockList(): List<Map<String, Any?>> =
        clockEngine?.list()?.map { it.toMap() } ?: emptyList()

    fun upsertClockTask(raw: Map<*, *>): Map<String, Any?>? {
        val ck = clockEngine ?: return null
        val t = ClockTask.fromMap(raw) ?: return null
        val saved = ck.upsert(t, clocks())
        arm()
        return saved.toMap()
    }

    fun controlClockTask(id: String, op: String): Any? {
        val ck = clockEngine ?: return null
        val now = clocks()
        val out = when (op) {
            "pause" -> ck.pause(id, now)?.toMap()
            "resume" -> ck.resume(id, now)?.toMap()
            "delete", "remove" -> ck.remove(id)
            "skip" -> ck.skipCyclePhase(id, now)?.let { ev ->
                handleClockFires(listOf(ev))
                ck.get(id)?.toMap()
            }
            else -> null
        }
        arm()
        return out
    }

    private fun handleClockFires(fires: List<ClockFireEvent>) {
        val now = System.currentTimeMillis()
        for (ev in fires) {
            Log.i(TAG, "clock fire: ${ev.taskId} kind=${ev.kind} action=${ev.action} cycle=${ev.cycleIndex} phase=${ev.phaseName}")
            if (ev.kind == ClockTaskKind.CYCLE_PHASE) {
                val next = ev.nextPhase
                if (next != null && next.strictLock && next.allowApps.isNotEmpty()) {
                    setSession(
                        now + next.durationMs,
                        next.allowApps.toSet(),
                        null,
                        0L,
                        OWNER_WORK,
                        "循环专时 · ${next.name}",
                        strict = true,
                    )
                    sessionFront(NowFeed.snapshot(SystemClock.elapsedRealtime()).app, forceCheck = true)
                } else if (ev.completedPhase?.strictLock == true && (next == null || !next.strictLock)) {
                    if (sessionOwner == OWNER_WORK) {
                        setSession(0L, emptySet(), null, SessionPolicy.DEFAULT_GRACE_MS)
                    }
                }
            } else if (ev.action == "focus_lock") {
                @Suppress("UNCHECKED_CAST")
                val allow = (ev.payload["allow"] as? List<*>)?.mapNotNull { it as? String }?.filter { it.isNotBlank() }.orEmpty()
                val mins = ((ev.payload["minutes"] as? Number)?.toInt() ?: 25).coerceIn(5, 720)
                if (allow.isNotEmpty()) workMode("on", allow, mins, ev.label)
            } else if (ev.action == "unlock_focus" && sessionOwner == OWNER_WORK) {
                setSession(0L, emptySet(), null, SessionPolicy.DEFAULT_GRACE_MS)
            }

            if (ev.urgent || ev.action == "notify" || ev.action == "alarm" || ev.kind == ClockTaskKind.CYCLE_PHASE) {
                val msg = if (ev.kind == ClockTaskKind.CYCLE_PHASE) {
                    val np = ev.nextPhase
                    if (np == null) "${ev.label} 已完成全部循环"
                    else "${ev.label}：已进入「${np.name}」（第 ${ev.cycleIndex} 轮）"
                } else {
                    "闹钟到点：${ev.label}"
                }
                main.post {
                    runCatching { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
                    runCatching { VoiceOut.speak(VoicePlan(text = msg.take(60), rate = 1.05f, pitch = 1.0f)) {} }
                }
            }

            postEvent(
                "clock-fire",
                "taskId" to ev.taskId,
                "label" to ev.label,
                "kind" to ev.kind.name,
                "action" to ev.action,
                "runCount" to ev.runCount,
                "cycleIndex" to ev.cycleIndex,
                "phaseName" to ev.phaseName,
                "nextPhase" to ev.nextPhase?.name,
                "finished" to ev.finished,
            ) { consultPlanSoon(minGapMs = 0L) }
        }
    }

    fun clocks() = Clocks(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(0))

    /**
     * 手边的情况 for the companion: front app, how long, today's totals, screen, charging, local
     * time. From [NowFeed] (accessibility events, no screen text). Null when the service never fed
     * it: unknown is not "not using the phone".
     */
    private var usageDayCache: Pair<Long, io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.UsageReport?>? = null
    private fun usageTodayReport(wall: Long): io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.UsageReport? = runCatching {
        usageDayCache?.let { if (wall - it.first < 20_000L) return@runCatching it.second }
        val dayStart = BeijingClock.startOfDay(wall)
        val report = io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.UsageReader.dailyReport(app, dayStart, wall)
            ?: io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.UsageReader.usage(app, minuteOfDay(wall).coerceAtLeast(1), wall)
        usageDayCache = wall to report
        report
    }.getOrNull()

    private fun isHeadsetConnected(): Boolean = runCatching {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return@runCatching false
        am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { dev ->
            when (dev.type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> true
                else -> Build.VERSION.SDK_INT >= 31 && dev.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }
    }.getOrDefault(false)

    private fun isVoiceModalityActive(frontPkgNow: String?, screenText: String?): Boolean = runCatching {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return@runCatching false
        val inComm = am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || am.mode == android.media.AudioManager.MODE_IN_CALL
        val recActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            am.activeRecordingConfigurations.isNotEmpty()
        } else false
        val textLive = frontPkgNow != null && frontPkgNow in GEMINI_PACKAGES && !screenText.isNullOrBlank() &&
            (screenText.contains("Live") || screenText.contains("正在聆听") || screenText.contains("静音") || screenText.contains("结束语音"))
        inComm || recActive || textLive || LiveKeeperPolicy.isScreenShareText(screenText)
    }.getOrDefault(false)

    private fun isShizukuReady(): Boolean = runCatching {
        rikka.shizuku.Shizuku.getBinder()?.isBinderAlive == true &&
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun phoneContext(): Map<String, Any?>? = runCatching {
        val now = SystemClock.elapsedRealtime()
        val s = NowFeed.snapshot(now)
        val wall = System.currentTimeMillis()
        val usage = usageTodayReport(wall)
        if (s.lastEventAgeMs < 0 && usage == null) return@runCatching null
        val targets = runCatching { InterceptRuntime.config().targets }.getOrNull().orEmpty()
        val funApps = targets.associate { it.packageName to it.label }
        val pm = app.packageManager
        fun label(pkg: String): String? = funApps[pkg]
            ?: usage?.rows?.firstOrNull { it.packageName == pkg }?.label
            ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        val opens = usageOpens(wall)
        val nowTodayMap = if (s.lastEventAgeMs >= 0) NowFeed.today(now, dayOf(wall)).toMap() else emptyMap()
        val usageMap = usage?.rows?.associateBy { it.packageName }.orEmpty()
        val allPkgs = (nowTodayMap.keys + usageMap.keys).distinct()
        val today = allPkgs.mapNotNull { pkg ->
            val uRow = usageMap[pkg]
            val nowMins = (nowTodayMap[pkg] as? Number)?.toLong() ?: 0L
            val mins = maxOf(nowMins, uRow?.minutes ?: 0L)
            val op = opens?.get(pkg) ?: uRow?.opens
            if (mins <= 0L && (op ?: 0) <= 0) return@mapNotNull null
            linkedMapOf<String, Any?>(
                "app" to pkg,
                "label" to label(pkg),
                "minutes" to mins,
                "fun" to (pkg in funApps || pkg in blockedNow()),
                "opens" to op,
            )
        }.sortedWith(compareByDescending<Map<String, Any?>> { (it["minutes"] as? Number)?.toLong() ?: 0L }
            .thenByDescending { (it["opens"] as? Number)?.toInt() ?: 0 })
            .take(24)
        val front = s.app ?: usage?.current
        val frontMinutes = if (s.app != null) s.appMinutes else {
            val cs = usage?.currentSince ?: 0L
            if (usage?.current != null && cs > 0L) ((wall - cs) / 60_000L).toInt().coerceAtLeast(0) else 0
        }
        val mediaPlaying = PhoneMediaControl.isPlaying(app)
        val headsetConnected = isHeadsetConnected()
        val screenText = runCatching {
            io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.MaskedSummaryHelper.maskedSummary(1500)
        }.getOrNull()
        val geminiVoiceActive = isVoiceModalityActive(front, screenText)
        if (geminiVoiceActive) {
            if (!lastGeminiVoiceActive) {
                lastGeminiVoiceStartedAt = wall
            }
            lastGeminiVoiceActiveAt = wall
            lastGeminiVoiceDroppedAt = 0L
        } else if (lastGeminiVoiceActive) {
            lastGeminiVoiceDroppedAt = wall
            if (lastGeminiVoiceStartedAt > 0L) {
                lastLiveDurationSec = ((wall - lastGeminiVoiceStartedAt) / 1000L).coerceAtLeast(0L)
            }
            val durationJustEnded = lastLiveDurationSec
            val modNow = minuteOfDay(wall)
            val nightNow = modNow < 7 * 60 || modNow >= 23 * 60
            val emergNow = inEmergencyPass(wall) || LockSafety.disarmed(app, wall)
            val studyPkgs = allowedAppsForFocus()
                .map { it.first }
                .filter { it !in GEMINI_PACKAGES }
                .toSet()
            val screenShareLikely = LiveKeeperPolicy.isScreenShareText(screenText)
            val inTextGrace = liveCoreTextGraceUntil > wall
            val earlyBailout = LiveKeeperPolicy.shouldTriggerEarlyBailoutGuard(
                alwaysOnLive = liveCoreAlwaysOn,
                strict = liveCoreStrict,
                durationSec = durationJustEnded,
                inTextGrace = inTextGrace,
                emergencyActive = emergNow,
                inNightWindow = nightNow,
                interceptHeld = VisibleSurface.isHeldBy(VisibleSurface.OWNER_INTERCEPT) ||
                    VisibleSurface.isHeldBy(VisibleSurface.OWNER_FOCUS),
                planModalShowing = ProactiveOverlay.isPlanModalShowing(),
                frontPkg = front,
                selfPkg = app.packageName,
                workOrQuizPackages = studyPkgs,
            )
            val promptRelay = PostLiveRelayPolicy.shouldPrompt(
                durationSec = durationJustEnded,
                nowMs = wall,
                lastPromptAtMs = lastPostLiveRelayPromptAt,
                interceptHeld = VisibleSurface.isHeldBy(VisibleSurface.OWNER_INTERCEPT) ||
                    VisibleSurface.isHeldBy(VisibleSurface.OWNER_FOCUS),
                planModalShowing = ProactiveOverlay.isPlanModalShowing(),
                emergencyActive = emergNow,
                inNightWindow = nightNow,
                frontPkg = front,
                selfPkg = app.packageName,
                workOrQuizPackages = studyPkgs,
            )
            if (durationJustEnded >= 3L) {
                logLiveSessionEnd(
                    durationSec = durationJustEnded,
                    screenOn = s.screenOn,
                    headsetConnected = headsetConnected,
                    frontPkg = front,
                    earlyBailout = earlyBailout,
                )
            }
            if (!s.screenOn && headsetConnected && liveCoreAlwaysOn && liveCoreHeadsetKeepalive &&
                durationJustEnded >= 15L && !nightNow && !emergNow && !inTextGrace
            ) {
                runCatching {
                    VoiceOut.install(app)
                    VoiceOut.speak(
                        VoicePlan(
                            "耳机里的 Gemini 连麦刚断了，亮屏可一键续聊，累了就切 L0 歇着",
                            VoicePlan.DEFAULT_RATE,
                            VoicePlan.DEFAULT_PITCH,
                        )
                    ) {}
                }
                consultPlanSoon(minGapMs = 0L)
            } else if (earlyBailout || promptRelay) {
                lastPostLiveRelayPromptAt = wall
                main.post {
                    if (!ProactiveOverlay.isPlanModalShowing() &&
                        !VisibleSurface.isHeldBy(VisibleSurface.OWNER_INTERCEPT) &&
                        !VisibleSurface.isHeldBy(VisibleSurface.OWNER_FOCUS)
                    ) {
                        ProactiveOverlay.showPostLiveRelay(
                            durationSec = durationJustEnded,
                            onCheckpoint25m = { commitLiveRelay(mode = "checkpoint_25m", minutes = 25, nextTitle = "Live 接力 25m 产出") },
                            onQuizCapsule20m = { commitLiveRelay(mode = "quiz_only", minutes = 20, nextTitle = "Live 接力 · 纯考公刷题舱") },
                            onRecapOnly = { commitLiveRelay(mode = "recap_only", minutes = 25, nextTitle = "Live 口头对练已复盘闭环") },
                            earlyBailout = earlyBailout,
                            onReturnToLive = { triggerLiveCoreRoll() },
                            onSwitchToText = { switchLiveMode(mode = "text", minutes = 15) },
                            onPauseKeeper15m = { pauseLiveCore(minutes = 15) },
                        )
                    }
                }
            } else if (screenShareLikely) {
                Log.d(TAG, "screenShareLikely active on hangup boundary")
            }
        }
        lastGeminiVoiceActive = geminiVoiceActive
        val screenShareLikelyNow = LiveKeeperPolicy.isScreenShareText(screenText)
        val liveDroppedAgoMs = if (!geminiVoiceActive && lastGeminiVoiceActiveAt > 0L) {
            (wall - lastGeminiVoiceActiveAt).coerceAtLeast(0L)
        } else 0L
        val a11yConnected = runCatching {
            io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService.isConnected()
        }.getOrDefault(false)
        val shizukuReady = isShizukuReady()
        val geminiMinutesToday = GEMINI_PACKAGES.sumOf { pkg ->
            val uRow = usageMap[pkg]
            val nowMins = (nowTodayMap[pkg] as? Number)?.toLong() ?: 0L
            maxOf(nowMins, uRow?.minutes ?: 0L)
        }
        val totalMinutes = today.sumOf { (it["minutes"] as? Number)?.toLong() ?: 0L }
        val timeSlots = usage?.timeSlots.orEmpty().map { slot ->
            linkedMapOf<String, Any?>(
                "slot" to slot.slot,
                "minutes" to slot.minutes,
                "switches" to slot.switches,
                "top" to slot.top.map { item ->
                    linkedMapOf<String, Any?>(
                        "app" to item.packageName,
                        "label" to (item.label ?: label(item.packageName) ?: item.packageName),
                        "minutes" to item.minutes,
                    )
                },
            )
        }
        val usage60m = usageRecent60m(wall)
        linkedMapOf<String, Any?>(
            "app" to front,
            "label" to front?.let { label(it) },
            "minutes" to frontMinutes,
            "fun" to (front != null && (front in funApps || front in blockedNow())),
            "screenOn" to s.screenOn,
            "charging" to s.charging,
            "mediaPlaying" to mediaPlaying,
            "headsetConnected" to headsetConnected,
            "geminiVoiceActive" to geminiVoiceActive,
            "screenShareLikely" to screenShareLikelyNow,
            "liveDroppedAgoMs" to liveDroppedAgoMs,
            "lastVoiceActiveAt" to lastGeminiVoiceActiveAt,
            "lastLiveDurationSec" to lastLiveDurationSec,
            "a11yConnected" to a11yConnected,
            "shizukuReady" to shizukuReady,
            "geminiMinutesToday" to geminiMinutesToday,
            "screenText" to screenText,
            "foregroundDebugOn" to io.github.ccch1mneyyy.dsh_app.phone.a11y.ForegroundRecorder.enabled(app),
            "local" to String.format(java.util.Locale.US, "%02d:%02d", minuteOfDay(wall) / 60, minuteOfDay(wall) % 60),
            "today" to today,
            "totalMinutes" to totalMinutes,
            "timeSlots" to timeSlots,
            "installedApps" to cachedInstalledApps(wall),
            // 走神前兆：90 秒 / 15 分钟 / 60 分钟内切了几次 App、切过哪些。
            "switches" to synchronized(switches) {
                val recent90 = switches.filter { now - it.first <= 90_000L }
                val win15 = switches.filter { now - it.first <= 15 * 60_000L }
                val recentList = switches.reversed().take(20).map {
                    linkedMapOf<String, Any?>("pkg" to it.second, "agoMs" to (now - it.first).coerceAtLeast(0L))
                }
                val stream60 = if (switches.isNotEmpty()) {
                    switches.reversed().take(60).map {
                        linkedMapOf<String, Any?>("pkg" to it.second, "agoMs" to (now - it.first).coerceAtLeast(0L))
                    }
                } else usage60m.orEmpty()
                linkedMapOf<String, Any?>(
                    "in90s" to recent90.size,
                    "apps90s" to recent90.map { it.second }.distinct().take(8).map { label(it) ?: it },
                    "in15m" to win15.size,
                    "distinct15m" to win15.map { it.second }.distinct().size,
                    "in60m" to maxOf(switches.size, usage60m?.size ?: 0),
                    "distinct60m" to (if (switches.isNotEmpty()) switches.map { it.second }.distinct().size else usage60m?.map { it["pkg"] }?.distinct()?.size ?: 0),
                    // 第九轮：包名序列（新的在前）。apps90s 是给人看的中文名，数「切出」必须用包名——
                    // 两次 /plan 之间切走又切回来的那些，插件只靠前台差集是数不到的。
                    "recent" to recentList,
                    "recent60m" to stream60,
                )
            },
        ).also { ctxMap ->
            lastInterceptInfo?.let { ctxMap["lastIntercept"] = it }
        }
    }.getOrNull()

    /** 每小时缓存一次本机可启动应用目录，供插件 JS 0ms 把中文应用名解析成包名。 */
    private fun cachedInstalledApps(wall: Long): List<Map<String, Any?>> = runCatching {
        installedAppsCache?.let { if (wall - it.first < 3_600_000L) return@runCatching it.second }
        val pm = app.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(intent, 0)
        val bypass = SystemBypassClassifier.resolvedBypasses(app, wall)
        val seen = HashSet<String>()
        val list = ArrayList<Map<String, Any?>>(128)
        for (ri in resolved) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val isSys = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            if (isSys && pkg !in bypass && pkg !in SystemBypassClassifier.KNOWN_SYSTEM_BYPASS) continue
            list.add(linkedMapOf(
                "pkg" to pkg,
                "label" to label.take(36),
                "bypass" to (pkg in bypass),
            ))
            if (list.size >= 160) break
        }
        installedAppsCache = wall to list
        list
    }.getOrDefault(emptyList())

    /** 近 60 分钟 UsageEvents 应用切换流（"使用情况访问"授权后可读，供 ForegroundRecorder 之外补齐历史）。 */
    private var usage60Cache: Pair<Long, List<Map<String, Any?>>>? = null
    private fun usageRecent60m(wall: Long): List<Map<String, Any?>>? = runCatching {
        usage60Cache?.let { if (wall - it.first < 30_000L) return@runCatching it.second }
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), app.packageName)
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) return@runCatching null
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val events = usm.queryEvents((wall - 60 * 60_000L).coerceAtLeast(0L), wall)
        val e = android.app.usage.UsageEvents.Event()
        val list = ArrayList<Map<String, Any?>>(32)
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == 1 && !e.packageName.isNullOrBlank() && e.packageName != last) {
                last = e.packageName
                list.add(linkedMapOf<String, Any?>("pkg" to e.packageName, "agoMs" to (wall - e.timeStamp).coerceAtLeast(0L)))
            }
        }
        val reversed = list.asReversed().take(60)
        usage60Cache = wall to reversed
        reversed
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
        val start = BeijingClock.startOfDay(wall)
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

    private fun minuteOfDay(wall: Long): Int = BeijingClock.minuteOfDay(wall)

    private fun dayOf(wall: Long): Long = BeijingClock.dayOf(wall)

    /** Pushes the current enforcement rule snapshot to [GuardianPolicyMirror] for cross-APK fallback. */
    private fun syncGuardianMirror() {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        val primary = primaryLock(now)
        val allowSet = sessionAllow.toMutableSet()
        runCatching { InterceptRuntime.config().whitelist }.getOrNull()?.let { allowSet.addAll(it) }
        val todayMap = runCatching {
            NowFeed.today(SystemClock.elapsedRealtime(), dayOf(now)).associate { it.first to it.second }
        }.getOrDefault(emptyMap())
        val snapshot = GuardianPolicySnapshot(
            updatedAtWall = now,
            enginePhase = runCatching { RuntimeController.get(app).phase.wire }.getOrDefault("unknown"),
            dshServiceRunning = DshService.isRunning,
            proactiveTransport = agentPort != null,
            a11yConnected = PhoneA11yService.isConnected(),
            planActive = controlActive() || sessionActive() || blockedNow().isNotEmpty(),
            primaryKind = primary?.kind.orEmpty(),
            primaryWhy = primary?.why ?: primary?.let { LockPriority.defaultWhy(it.kind) }.orEmpty(),
            lockUntil = lockUntil(now),
            refuseUntil = refuseUntil(),
            breakUntil = if (breaking()) breakUntil else 0L,
            emergencyUntil = if (inEmergencyPass(now)) emergencyUntilAt else 0L,
            sessionUntil = if (sessionActive()) sessionUntil else 0L,
            sessionGraceMs = sessionGrace,
            blocked = blockedNow().toList().sorted(),
            blockWhys = HashMap(blockWhys),
            allow = allowSet.toList().sorted(),
            todayMinutes = todayMap,
        )
        GuardianPolicyMirror.update(app, snapshot)
    }

    /** Uploads pending offline incidents reported by `大肥鱼哨兵` once the node companion is reachable. */
    fun flushGuardianOfflineSoon() {
        if (!::app.isInitialized || agentPort == null) return
        val pending: List<GuardianOfflineIncident> = GuardianPolicyMirror.pendingOffline(app)
        if (pending.isEmpty()) return
        main.post {
            if (agentPort == null) return@post
            val ids = pending.map { it.id }
            fetchPluginRoute(
                routeCallback { res ->
                    val hasErr = res["error"] != null
                    if (!hasErr) {
                        GuardianPolicyMirror.ackOffline(app, ids)
                    }
                },
                "POST",
                "/event",
                linkedMapOf(
                    "type" to "guardian-offline-batch",
                    "events" to pending.map { it.toMap() },
                ),
            )
        }
    }

    // ------------------------------------------------------------------ 摇一摇六骰子（M2 轻干预）

    private const val DICE_PREFS = "dsh_proactive_dice"
    private const val KEY_SHAKE_ENABLED = "shake_enabled"
    private const val SHAKE_G_THRESHOLD = 1.35f
    private const val SHAKE_GAP_MIN_MS = 120L
    private const val SHAKE_WINDOW_MS = 700L
    private const val SHAKE_COOLDOWN_MS = 2_500L
    private const val GENTLE_DICE_IDLE_MS = 45 * 60_000L

    private val LOCAL_DICE_FALLBACK = listOf(
        Triple(1, "事实提取", "不看原话，用一句说清刚才这条内容到底交待了哪一个事实？"),
        Triple(2, "预判推演", "顺着当前这一步往下走，接下来最可能发生什么结果或坑？"),
        Triple(3, "联想比较", "这跟你做过的哪一类旧题/旧案例最像？关键差别在哪？"),
        Triple(4, "关键提问", "整段里哪一个还没弄清的未知条件，一旦变了结论就会翻盘？"),
        Triple(5, "依据与结论", "你刚才下的判断依据了哪条硬事实？如果只靠它，能稳推出什么？"),
        Triple(6, "替代解释", "别急着认第一个结论：如果最顺嘴那个解释错了，还能怎么解释？"),
    )

    @Volatile private var shakeReceiverReady = false
    @Volatile private var shakeSensorRegistered = false
    @Volatile private var lastDiceRollAt = 0L
    @Volatile private var lastDiceId = 0
    @Volatile private var lastShakeTriggerAt = 0L
    @Volatile private var lastShakeSpikeAt = 0L
    @Volatile private var shakeSpikeCount = 0

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!shakeDiceEnabled()) return
            val x = event.values.getOrNull(0) ?: return
            val y = event.values.getOrNull(1) ?: return
            val z = event.values.getOrNull(2) ?: return
            val gForce = kotlin.math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            val delta = kotlin.math.abs(gForce - 1.0f)
            if (delta < SHAKE_G_THRESHOLD) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastShakeTriggerAt < SHAKE_COOLDOWN_MS) return
            if (now - lastShakeSpikeAt < SHAKE_GAP_MIN_MS) return
            if (now - lastShakeSpikeAt > SHAKE_WINDOW_MS) shakeSpikeCount = 0
            lastShakeSpikeAt = now
            shakeSpikeCount++
            if (shakeSpikeCount >= 2) {
                shakeSpikeCount = 0
                lastShakeTriggerAt = now
                main.post { triggerShakeDice(manual = false) }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun initShakeDice(context: Context) {
        if (!shakeReceiverReady) {
            shakeReceiverReady = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> updateShakeSensor(false)
                        Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> updateShakeSensor(true)
                    }
                }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver, filter)
            }
        }
        val interactive = runCatching { (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false }.getOrDefault(true)
        updateShakeSensor(interactive)
    }

    fun shakeDiceEnabled(): Boolean {
        if (!::app.isInitialized) return true
        return app.getSharedPreferences(DICE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHAKE_ENABLED, true)
    }

    fun setShakeDiceEnabled(context: Context, enabled: Boolean): Boolean {
        ensure(context)
        app.getSharedPreferences(DICE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHAKE_ENABLED, enabled).apply()
        val interactive = runCatching { (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false }.getOrDefault(true)
        updateShakeSensor(interactive && enabled)
        return shakeDiceEnabled()
    }

    fun shakeDiceStatus(context: Context): Map<String, Any?> {
        ensure(context)
        return linkedMapOf(
            "enabled" to shakeDiceEnabled(),
            "listening" to shakeSensorRegistered,
            "lastDice" to lastDiceId,
            "lastRollAt" to lastDiceRollAt,
        )
    }

    private fun updateShakeSensor(screenInteractive: Boolean) {
        if (!::app.isInitialized) return
        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val want = screenInteractive && shakeDiceEnabled()
        if (want && !shakeSensorRegistered) {
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            if (sm.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_UI)) {
                shakeSensorRegistered = true
            }
        } else if (!want && shakeSensorRegistered) {
            runCatching { sm.unregisterListener(shakeListener) }
            shakeSensorRegistered = false
            shakeSpikeCount = 0
        }
    }

    /** 定时温和微干预：亮屏使用久了还没摇过骰子、且没有别的弹窗时，轻提示一面六骰子。 */
    private fun maybeGentleDiceIntervene() {
        if (!::app.isInitialized || !shakeDiceEnabled() || !proactiveEnabled()) return
        val now = System.currentTimeMillis()
        if (lastDiceRollAt > 0L && now - lastDiceRollAt < GENTLE_DICE_IDLE_MS) return
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val kg = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (power?.isInteractive == false || kg?.isKeyguardLocked == true) return
        if (VisibleSurface.holder() != null) return
        val snap = NowFeed.snapshot(SystemClock.elapsedRealtime())
        if (snap.app == null || snap.app == app.packageName || snap.appMinutes * 60_000L < GENTLE_DICE_IDLE_MS) return
        triggerShakeDice(manual = false)
    }

    /** 从桌面摇骰/快捷触发直接走 Live Core 总调度灌料进官方 Gemini Live。 */
    fun triggerLiveCoreRoll(diceHint: Int = 0, done: ((Boolean, String?) -> Unit)? = null) {
        if (!::app.isInitialized) {
            done?.invoke(false, "未初始化")
            return
        }
        val body = linkedMapOf<String, Any?>("action" to "roll_dice")
        if (diceHint in 1..6) body["diceHint"] = diceHint
        fetchPluginRoute(
            routeCallback { res ->
                val payload = (res["payload"] as? Map<*, *>)
                    ?: ((res["liveCore"] as? Map<*, *>)?.get("payload") as? Map<*, *>)
                val fallback = LOCAL_DICE_FALLBACK.firstOrNull { it.first == diceHint } ?: LOCAL_DICE_FALLBACK.first()
                val topic = (payload?.get("topic") as? String)?.takeIf { it.isNotBlank() }
                    ?: "骰${fallback.first}·${fallback.second}"
                val brief = (payload?.get("brief") as? String)?.takeIf { it.isNotBlank() }
                    ?: "【禁聊体系死命令】别聊体系方法论，直接开考：${fallback.third}"
                val voiceLine = (payload?.get("voiceLine") as? String)?.takeIf { it.isNotBlank() }
                    ?: "骰${fallback.first}${fallback.second}，进 Live 直接开答"
                val wantShare = res["wantScreenShare"] != false
                main.post {
                    GeminiAutoLauncher.launchAndAutomateLive(
                        app = app,
                        topic = topic,
                        brief = brief,
                        voiceLine = voiceLine,
                        mode = "instant",
                        autoClickLive = true,
                        requestScreenShare = wantShare,
                    ) { opened ->
                        done?.invoke(opened, topic)
                    }
                }
            },
            "POST",
            "/live-core",
            body,
        )
    }

    /** 在 Live 语音看屏 与 Gemini 文本（MCP 沉淀）之间一键切换，支持注入剪贴板素材与认知手术刀。 */
    fun switchLiveMode(
        mode: String,
        minutes: Int = 15,
        customFeed: String = "",
        scalpelHint: String = "",
        done: ((Boolean, String?) -> Unit)? = null,
    ) {
        if (!::app.isInitialized) {
            done?.invoke(false, "未初始化")
            return
        }
        val normMode = if (mode == "text") "text" else "live"
        val reqBody = linkedMapOf<String, Any?>(
            "action" to "switch_mode",
            "mode" to normMode,
            "minutes" to minutes,
        )
        if (customFeed.isNotBlank()) reqBody["customFeed"] = customFeed.trim().take(900)
        if (scalpelHint.isNotBlank()) reqBody["scalpelHint"] = scalpelHint.trim().take(48)
        fetchPluginRoute(
            routeCallback { res ->
                val payload = (res["payload"] as? Map<*, *>)
                    ?: ((res["liveCore"] as? Map<*, *>)?.get("payload") as? Map<*, *>)
                val topic = (payload?.get("topic") as? String)?.takeIf { it.isNotBlank() }
                    ?: if (normMode == "text") "Gemini 文本（MCP 沉淀）" else "回到 Gemini Live"
                val brief = (payload?.get("brief") as? String)?.takeIf { it.isNotBlank() }
                    ?: "请承接上轮内容继续推进。"
                val voiceLine = (payload?.get("voiceLine") as? String).orEmpty()
                val autoClickLive = normMode == "live" && payload?.get("autoClickLive") != false
                val wantShare = normMode == "live" && (res["wantScreenShare"] != false)
                liveCoreTextGraceUntil = (res["textGraceUntil"] as? Number)?.toLong() ?: 0L
                main.post {
                    GeminiAutoLauncher.launchAndAutomateLive(
                        app = app,
                        topic = topic,
                        brief = brief,
                        voiceLine = voiceLine,
                        mode = "instant",
                        autoClickLive = autoClickLive,
                        requestScreenShare = wantShare,
                    ) { opened ->
                        done?.invoke(opened, topic)
                    }
                }
            },
            "POST",
            "/live-core",
            reqBody,
        )
    }

    private fun logLiveSessionEnd(
        durationSec: Long,
        screenOn: Boolean,
        headsetConnected: Boolean,
        frontPkg: String?,
        earlyBailout: Boolean,
    ) {
        if (!::app.isInitialized) return
        fetchPluginRoute(
            routeCallback { },
            "POST",
            "/live-core",
            linkedMapOf<String, Any?>(
                "action" to "log_session_end",
                "durationSec" to durationSec,
                "screenOn" to screenOn,
                "headsetConnected" to headsetConnected,
                "frontPkg" to frontPkg.orEmpty(),
                "earlyBailout" to earlyBailout,
            ),
        )
    }

    /** 暂停 Live 护航指定分钟数。 */
    fun pauseLiveCore(minutes: Int = 15, done: ((Boolean) -> Unit)? = null) {
        if (!::app.isInitialized) {
            done?.invoke(false)
            return
        }
        fetchPluginRoute(
            routeCallback { res ->
                val ok = res["ok"] == true
                val msg = (res["toast"] as? String)?.takeIf { it.isNotBlank() } ?: "已暂停 Live 护航 $minutes 分钟"
                main.post {
                    runCatching { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }
                    if (ok) consultPlanSoon(minGapMs = 0L)
                    done?.invoke(ok)
                }
            },
            "POST",
            "/live-core",
            linkedMapOf<String, Any?>("action" to "mute", "minutes" to minutes),
        )
    }

    /** 结束一轮 Live 后提交接力单（25m 微验收点 / 20m 纯刷题舱 / 仅记复盘）。 */
    fun commitLiveRelay(
        mode: String,
        minutes: Int = 25,
        nextTitle: String = "",
        summary: String = "",
        done: ((Boolean, String?) -> Unit)? = null,
    ) {
        if (!::app.isInitialized) {
            done?.invoke(false, "未初始化")
            return
        }
        val body = linkedMapOf<String, Any?>(
            "action" to "commit_relay",
            "mode" to mode,
            "minutes" to minutes,
        )
        if (nextTitle.isNotBlank()) body["next"] = nextTitle
        if (summary.isNotBlank()) body["summary"] = summary
        fetchPluginRoute(
            routeCallback { res ->
                val ok = res["ok"] == true
                val msg = (res["toast"] as? String)?.takeIf { it.isNotBlank() }
                    ?: if (ok) "已完成本轮 Live 接力交接" else "接力交接失败，请稍后在图谱页重试"
                main.post {
                    runCatching { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
                    if (ok) consultPlanSoon(minGapMs = 0L)
                    done?.invoke(ok, msg)
                }
            },
            "POST",
            "/live-core",
            body,
        )
    }

    /** 触发一次摇六骰浮窗（manual=true 允许在 App 内或锁屏外手动触发）。 */
    fun triggerShakeDice(manual: Boolean = true, done: ((Map<String, Any?>) -> Unit)? = null) {
        if (!::app.isInitialized) {
            done?.invoke(linkedMapOf("ok" to false, "error" to "not-initialized"))
            return
        }
        val power = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val kg = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (!manual && (power?.isInteractive == false || kg?.isKeyguardLocked == true)) {
            done?.invoke(linkedMapOf("ok" to false, "error" to "screen-locked"))
            return
        }
        if (VisibleSurface.isHeldBy(VisibleSurface.OWNER_INTERCEPT)) {
            done?.invoke(linkedMapOf("ok" to false, "error" to "lock-active"))
            return
        }
        val snap = NowFeed.snapshot(SystemClock.elapsedRealtime())
        val appLabel = snap.app?.let { pkg ->
            runCatching {
                val pm = app.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }.orEmpty()
        fetchPluginRoute(
            routeCallback { res ->
                val hasPluginCard = res["ok"] == true && res["dice"] is Number
                val diceNum = if (hasPluginCard) (res["dice"] as Number).toInt() else {
                    val pool = LOCAL_DICE_FALLBACK.filter { it.first != lastDiceId }
                    pool.random().first
                }
                val fallback = LOCAL_DICE_FALLBACK.firstOrNull { it.first == diceNum } ?: LOCAL_DICE_FALLBACK.first()
                val name = (res["name"] as? String).orEmpty().ifBlank { fallback.second }
                val ask = (res["ask"] as? String).orEmpty().ifBlank { fallback.third }
                val anchor = (res["anchor"] as? String).orEmpty()
                val geminiPrompt = (res["geminiPrompt"] as? String).orEmpty().ifBlank {
                    "[六骰思维干预 · 骰$diceNum $name] 请用这题追问我、别替我代答：$ask"
                }
                lastDiceId = diceNum
                lastDiceRollAt = System.currentTimeMillis()
                main.post {
                    val shown = ProactiveOverlay.showDice(
                        dice = diceNum,
                        name = name,
                        ask = ask,
                        anchor = anchor,
                        appLabel = appLabel,
                        geminiPrompt = geminiPrompt,
                        onSubmitThought = { thought, reply ->
                            fetchPluginRoute(
                                routeCallback { saveRes ->
                                    val saved = saveRes["ok"] == true
                                    reply(saved, (saveRes["error"] as? Map<*, *>)?.get("message") as? String)
                                },
                                "POST",
                                "/dice/thought",
                                linkedMapOf<String, Any?>("dice" to diceNum, "name" to name, "ask" to ask, "thought" to thought, "appLabel" to appLabel),
                            )
                        },
                        onReroll = { triggerShakeDice(manual = true) },
                        onEnterLive = {
                            ProactiveOverlay.dismissDice()
                            triggerLiveCoreRoll(diceHint = diceNum)
                        },
                    )
                    if (!shown && manual) {
                        Toast.makeText(app, "🎲 骰$diceNum · $name：$ask（开启悬浮窗权限可直接在任意界面弹出）", Toast.LENGTH_LONG).show()
                    }
                    done?.invoke(linkedMapOf<String, Any?>(
                        "ok" to true,
                        "shown" to shown,
                        "dice" to diceNum,
                        "name" to name,
                        "ask" to ask,
                        "anchor" to anchor,
                    ))
                }
            },
            "POST",
            "/dice/roll",
            linkedMapOf<String, Any?>("excludeDice" to lastDiceId, "manual" to manual, "appLabel" to appLabel),
        )
    }
}
