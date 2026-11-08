package io.github.ccch1mneyyy.dsh_app.loop

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.loop.core.EventRequest
import io.github.ccch1mneyyy.dsh_app.loop.core.ProactiveEvent
import io.github.ccch1mneyyy.dsh_app.loop.core.Switches
import io.github.ccch1mneyyy.dsh_app.loop.focus.FocusRuntime
import io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.loop.proactive.TamperGuard
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ProtectedPages
import io.github.ccch1mneyyy.dsh_app.phone.a11y.TreeCapture
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EventObserver, Android half, and the process-wide owner of the loop.
 *
 * [PhoneA11yService] forwards window events here. They are only hints: after a short quiet gap
 * the real foreground is read back from the window list (package name only — never page
 * content), and only that reading reaches [LoopEngine]. Everything runs on the main thread,
 * where accessibility events arrive.
 *
 * Every failure on this path is counted by [diagnostics] instead of being swallowed by
 * `runCatching` + `Log.w`: a phone where the window list always throws, or where every verification
 * fails, used to look exactly like a phone with the loop switched off. [LoopPlugin] exposes the
 * counters and the last few faults to the settings page.
 */
object LoopRuntime {
    private const val TAG = "DshLoop"
    /** Quiet gap before reading the foreground: one app switch fires several window events. */
    private const val SETTLE_MS = 350L
    /** How long an app-enter event may wait in the kernel before it is no longer worth showing. */
    const val APP_ENTER_TTL_MS = 120_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var engine: LoopEngine? = null
    private var service: WeakReference<AccessibilityService>? = null
    private val verify = Runnable { verifyPending = false; verifyNow() }

    /** Failure counters and the last few faults, for the settings page. */
    val diagnostics = LoopDiagnostics()

    /** The local AgentBridge (v0). Null until the loop first runs, i.e. no agent turn is wired. */
    @Volatile private var localBridge: AgentBridge? = null

    fun bridge(): AgentBridge? = localBridge

    /** Counters for the settings page; reset with the process. */
    @Volatile var eventsSeen = 0L
        private set
    @Volatile var readings = 0L
        private set
    @Volatile var lastForeground: String? = null
        private set

    /** One engine per process. The first call recovers what the last process left open. */
    fun engine(context: Context): LoopEngine = engine ?: synchronized(this) {
        engine ?: run {
            val app = context.applicationContext
            val imm = app.getSystemService(InputMethodManager::class.java)
            val filter = PackageFilter(app.packageName) {
                imm?.enabledInputMethodList?.map { it.packageName }?.toSet().orEmpty()
            }
            val pm = app.packageManager
            LoopEngine(
                FileLoopStore(File(File(app.filesDir, "loop"), "state.json")),
                filter,
                minuteOfDay = { localMinuteOfDay() },
                dayKey = { localDayKey() },
            ).also {
                val abandoned = it.recover()
                if (abandoned.isNotEmpty()) Log.i(TAG, "recovered: closed ${abandoned.size} open event(s)")
                // Delegated twins stay open through recover(); the kernel's record settles them.
                ProactiveRuntime.kernelOrNull()?.let { k -> KernelMirror.reconcile(it, k::find) }
                it.recoveredFrom?.let { b -> Log.w(TAG, "state file damaged, loaded backup $b") }
                localBridge = LocalTemplateBridge(
                    labels = { p -> p?.let { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() } },
                    template = { app -> app },
                )
                engine = it
            }
        }
    }

    fun attach(s: AccessibilityService) {
        runCatching { engine(s) }.onFailure { diagnostics.fault(LoopDiagnostics.KIND_ATTACH, it, "engine") }
        // 娱乐 APP 拦截 recovers its own guard and its own ledger here, before the first window event.
        runCatching { io.github.ccch1mneyyy.dsh_app.loop.intercept.InterceptRuntime.ensure(s) }
            .onFailure { diagnostics.fault(LoopDiagnostics.KIND_ATTACH, it, "intercept") }
        runCatching { ProactiveRuntime.ensure(s); ProactiveRuntime.refreshGate("a11y_connected") }
        // 原生专注监督：不依赖引擎，只依赖这次前台读数。
        runCatching { FocusRuntime.ensure(s); FocusRuntime.onA11yBack() }
            .onFailure { diagnostics.fault(LoopDiagnostics.KIND_ATTACH, it, "focus") }
        service = WeakReference(s)
        runCatching { InteractionHub.ensureChannel(s) }
        // The person may already be in the test app; a still-open visit makes this a no-op.
        schedule()
    }

    fun detach(s: AccessibilityService) {
        if (service?.get() === s) service = null
        main.removeCallbacks(verify)
        verifyPending = false
        // No more readings: a stale "protected page in front" must not hold the kernel forever.
        lastForeground = null
        // Pages can no longer start from the background: the kernel falls back to notifications.
        main.post { runCatching { ProactiveRuntime.refreshGate("a11y_gone") } }
        main.post { runCatching { FocusRuntime.onA11yGone() } }
    }

    fun onEvent(s: AccessibilityService, event: AccessibilityEvent?) {
        proactive(event)
        // 管控期间去设置里拆管控：读一眼设置页，拦下并报 tamper（不依赖下面两个功能的开关）。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            runCatching { TamperGuard.onWindow(s, event.packageName?.toString()) }
                .onFailure { Log.w("LoopRuntime", "tamper guard", it) }
            // Gemini 互动舱：切到别的 App 就当场锁，不等 /plan 的 60 秒节流。
            runCatching { ProactiveRuntime.onFrontChanged(event.packageName?.toString()) }
                .onFailure { Log.w("LoopRuntime", "session lock", it) }
        }
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // 专注监督只靠前台读数：没有 DSH 引擎时也要继续观察。
        if (engine == null && !runCatching { FocusRuntime.isActive() }.getOrDefault(false)) return
        // Observation runs for either feature (review r3, C): protected-page detection must not
        // depend on the trigger-test switch.
        if (!switches().observeForeground) return
        if (service?.get() !== s) service = WeakReference(s)
        eventsSeen++
        diagnostics.success(LoopDiagnostics.KIND_EVENT)
        schedule()
    }

    /** Hooks for the proactive kernel; every window event is also its opportunistic clock. */
    private fun proactive(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        runCatching { ProactiveRuntime.onWindowActivity() }
    }

    /**
     * D5 (review): hand one first-round trigger to the proactive kernel instead of starting the
     * page directly. The kernel owns the arbitration (DSH busy, notifications, tokens), so this is
     * the only place a page can come from — the round-1 [InteractionHub] path would bypass it.
     *
     * The fallback text is the round-1 fixed sentence, which the integration guide allows for
     * `app_enter`; there is no fabricated agent text anywhere.
     */
    private fun submitToKernel(
        s: AccessibilityService,
        engine: LoopEngine,
        show: LoopEngine.Outcome.Show,
        pkg: String?,
    ) {
        val label = runCatching { appLabel(s, pkg) }.getOrDefault(pkg.orEmpty())
        val fallback = io.github.ccch1mneyyy.dsh_app.i18n.AppStrings.get(
            s, io.github.ccch1mneyyy.dsh_app.R.string.loop_page_text, label,
        )
        val request = EventRequest(
            eventId = show.event.eventId,
            source = "A11Y_WINDOW",
            kind = "app_enter",
            dedupeKey = "app_enter:$pkg",
            createdAt = show.event.timestamp,
            // "You just opened X" is stale after a couple of minutes; the round-1 timeout is how long
            // the page waits for an answer, which the kernel's own interaction timeout governs.
            ttlMs = APP_ENTER_TTL_MS,
            needsAgent = false,
            fallbackText = fallback,
            payload = linkedMapOf(
                "packageName" to (pkg ?: ""),
                // The person's own clock, as text; the kernel never asks for screen content.
                "localTime" to SimpleDateFormat("HH:mm", Locale.US).format(Date()),
            ),
        )
        // Review r3, A: the kernel is the only owner of how this event ends. Queued → the twin is
        // delegated and the kernel's verdict is written back (KernelMirror); merged / refused → the
        // twin closes now. Either way nothing stays open without an owner.
        val r = ProactiveRuntime.submit(request)
        KernelMirror.onSubmit(engine, show.event.eventId, r)
        Log.i(TAG, "handed ${show.event.eventId} to the kernel: $r")
    }

    /** Whether 触发测试 is on (it may be on while 主动搭话 is off, and the other way round). */
    fun triggerEnabled(): Boolean = engine?.config()?.enabled == true

    fun triggerTarget(): String? = engine?.config()?.targetPackage

    private fun switches() = Switches(
        triggerEnabled(), ProactiveRuntime.proactiveEnabled(),
        // 娱乐拦截 is its own feature with its own book; it only needs the foreground reading.
        runCatching { io.github.ccch1mneyyy.dsh_app.loop.intercept.InterceptRuntime.enabled() }.getOrDefault(false),
        // 原生专注监督也一样：它只要前台读数，不需要引擎或模型。
        runCatching { FocusRuntime.isActive() }.getOrDefault(false),
    )

    /** The kernel closed an event: write its verdict onto the round-1 twin (no-op for other sources). */
    fun onKernelClosed(closed: ProactiveEvent) { engine?.let { KernelMirror.onClosed(it, closed) } }

    fun onKernelShown(event: ProactiveEvent?) { engine?.let { KernelMirror.onShown(it, event) } }

    /** After the kernel recovered: settle round-1 twins it may have closed meanwhile. */
    fun reconcileWithKernel(kernelFind: (String) -> ProactiveEvent?) { engine?.let { KernelMirror.reconcile(it, kernelFind) } }

    /** Settings / system pages in front, by package name only (no page content is read). */
    fun protectedForeground(): Boolean = ProtectedForeground.isProtected(lastForeground)

    /** Settings changed: read the foreground again so the new target is judged from now on. */
    fun configChanged() {
        schedule()
        // Turning 触发测试 off cancels only its own kernel events (review r3, B).
        main.post { runCatching { ProactiveRuntime.refreshGate("trigger_config") } }
    }

    /** A verify is already queued: later events ride on it instead of pushing it back. */
    private var verifyPending = false

    /**
     * At most [SETTLE_MS] after the first window event, the foreground is read once.
     *
     * It used to be a pure debounce (every event pushed the read back by 350 ms). Short-video apps
     * and games fire window events non-stop while they play, so the read kept slipping and the block
     * page came 5–6 s late (真机测出来的). Now later events never delay a pending read; the next
     * event after it runs schedules a fresh one.
     */
    private fun schedule() {
        if (verifyPending) return
        verifyPending = true
        main.postDelayed(verify, SETTLE_MS)
    }

    private fun verifyNow() {
        val s = service?.get() ?: return
        val e = engine
        val sw = switches()
        if (!sw.observeForeground) return
        val previousForeground = lastForeground
        val pkg = try {
            foreground(s).also {
                readings++
                lastForeground = it
                diagnostics.success(LoopDiagnostics.KIND_READ_FOREGROUND)
            }
        } catch (t: Throwable) {
            // A throwing window list used to vanish into Log.w; now it is a countable fault.
            Log.w(TAG, "read foreground", t)
            diagnostics.fault(LoopDiagnostics.KIND_READ_FOREGROUND, t)
            return
        }
        try {
            // The proactive gate cares whether a permission/security page took the foreground:
            // it must yield while one is up, and reopen once it is gone.
            if (ProtectedForeground.isProtected(pkg) != ProtectedForeground.isProtected(previousForeground)) {
                runCatching { ProactiveRuntime.refreshGate("protected_page") }
            }
            // Every switch of front app is a decision point for the companion (sliding into a game,
            // coming back out); it also re-applies a lock the companion already asked for.
            if (pkg != previousForeground) runCatching { ProactiveRuntime.onForeground(pkg) }
            // 原生专注监督先判：它和拦截/主动各自记账，不经过 coordinator。
            if (sw.focus) runCatching { FocusRuntime.onForeground(pkg) }
                .onFailure { Log.w(TAG, "focus", it) }
            // 娱乐 APP 拦截 (round 4): decided from this same verified reading, with its own ledger
            // and its own page. It never goes through the coordinator, so one feature's silence can
            // never pause the other.
            if (sw.intercept) runCatching {
                io.github.ccch1mneyyy.dsh_app.loop.intercept.InterceptRuntime.onForeground(pkg)
            }.onFailure { Log.w(TAG, "intercept", it) }
            // Observation may be running only for 主动搭话 / 娱乐拦截: then there is no app-enter trigger.
            if (!sw.triggerTest) { diagnostics.success(LoopDiagnostics.KIND_VERIFY); return }
            val eng = e ?: run { diagnostics.success(LoopDiagnostics.KIND_VERIFY); return }
            when (val out = eng.onForeground(pkg, environment(s))) {
                // D5 (review): the trigger itself is handed to the proactive kernel, so one
                // arbiter decides about every surface. The first round's fixed test sentence is
                // the only fallback allowed here (integration guide §4: no fabricated agent text).
                is LoopEngine.Outcome.Show -> submitToKernel(s, eng, out, pkg)
                is LoopEngine.Outcome.Suppressed -> Log.i(TAG, "suppressed ${out.event.packageName}: ${out.event.reason}")
                is LoopEngine.Outcome.Ignored -> Unit
            }
            diagnostics.success(LoopDiagnostics.KIND_VERIFY)
        } catch (t: Throwable) {
            Log.w(TAG, "verify", t)
            diagnostics.fault(LoopDiagnostics.KIND_VERIFY, t)
        }
    }

    /**
     * The app the person is looking at: the focused application window first (keyboards,
     * DSH's own overlay pill and the status bar are other window types and never win), then
     * DSH's general foreground reading as a fallback. Null when unreadable.
     */
    private fun foreground(s: AccessibilityService): String? {
        val windows = try { s.windows } catch (t: Throwable) {
            diagnostics.fault(LoopDiagnostics.KIND_READ_FOREGROUND, t, "windows")
            null
        }
        try {
            windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isFocused || it.isActive) }
                ?.let { w ->
                    val root = w.root ?: return@let null
                    try { root.packageName?.toString() } finally { release(root) }
                }
                ?.let { return it }
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) windows?.forEach { runCatching { it.recycle() } }
        }
        return TreeCapture.foregroundPackage(s, screenBox(s))
    }

    private fun environment(s: AccessibilityService): Environment {
        val power = s.getSystemService(PowerManager::class.java)
        val keyguard = s.getSystemService(KeyguardManager::class.java)
        val core = PhoneControl.peek()
        val busy = core != null && (core.tasks.isActive || core.tasks.callsInFlight > 0 || core.prompts.busy())
        return Environment(
            screenInteractive = power?.isInteractive != false,
            keyguardLocked = keyguard?.isKeyguardLocked == true,
            dshBusy = busy,
            minuteOfDay = localMinuteOfDay(),
            dayKey = localDayKey(),
        )
    }

    private fun localMinuteOfDay(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    private fun localDayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun screenBox(s: AccessibilityService): Box {
        val p = Point()
        @Suppress("DEPRECATION")
        s.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        return Box(0, 0, p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
    }

    @Suppress("DEPRECATION")
    private fun release(node: android.view.accessibility.AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) runCatching { node.recycle() }
    }

    /** The label the person sees for a package; used by the page and the notification. */
    fun appLabel(context: Context, pkg: String?): String =
        pkg?.let { p -> runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() } ?: pkg.orEmpty()
}
