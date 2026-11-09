// Portions derived from Phase (https://github.com/CikeSeven/phase), used with the author's permission. See THIRD_PARTY_NOTICES.md.
// Local changes: registers into PhoneRegistry; stops the task on screen off, interrupt and
// unbind through ControlListener; debug-only probe.

package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.ccch1mneyyy.dsh_app.loop.LoopRuntime
import io.github.ccch1mneyyy.dsh_app.loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry

/**
 * DSH's accessibility service (same process as the app). While connected it is the MAIN
 * [io.github.ccch1mneyyy.dsh_app.phone.PhoneDriver] and the overlay
 * [io.github.ccch1mneyyy.dsh_app.phone.UserGate]. It reads nothing on its own: the tree is
 * read only when the bridge calls the driver.
 */
class PhoneA11yService : AccessibilityService() {
    internal val clock = EventClock()
    private var overlay: ControlOverlay? = null
    private var driver: MainScreenDriver? = null
    private var probe: A11yProbe? = null
    private var receiverRegistered = false
    private var overlayContext: Context? = null

    private var healthThread: HandlerThread? = null
    private var healthHandler: Handler? = null

    /**
     * A cheap liveness probe on its own thread. A hung main thread must not stop the probe,
     * otherwise a frozen service would look healthy forever. AccessibilityService's tree calls
     * are Binder calls and are safe off the main thread.
     *
     * Accessibility events only arrive when the screen changes, so a quiet screen is not a dead
     * service. A null root and zero windows for several probes while the screen is on and
     * unlocked is the "bound but silently dead" state the keeper must repair.
     */
    private val healthTick = object : Runnable {
        override fun run() {
            if (instance !== this@PhoneA11yService) return
            val now = SystemClock.elapsedRealtime()
            val screenOn = runCatching {
                getSystemService(PowerManager::class.java)?.isInteractive == true
            }.getOrDefault(true)
            val locked = runCatching {
                getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            }.getOrDefault(false)

            if (screenOn && !locked) {
                // Window events can be swallowed on some ROMs; reconcile with the focused window.
                runCatching { driver?.foregroundPackage() }.getOrNull()?.let { NowFeed.onApp(it, now) }
                val ok = runCatching {
                    rootInActiveWindow != null || windows.isNotEmpty()
                }.getOrDefault(false)
                A11yHealth.onProbe(now, ok)
                A11yHealthFile.write(this@PhoneA11yService, A11yHealth.snapshot(now, true))
                if (A11yHealth.snapshot(now, true).verdict == A11yHealthPlan.Verdict.DEAD) {
                    Thread({
                        runCatching { A11yKeeper.check(applicationContext) }
                    }, "dsh-a11y-heal").start()
                }
            }
            healthHandler?.postDelayed(this, HEALTH_TICK_MS)
        }
    }

    /** A dedicated manager whose default token comes from the live accessibility connection. */
    internal fun overlayWindowContext(): Context = overlayContext ?: run {
        val context = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            // AccessibilityService's display/window wrappers obtain getOverlayWindowToken(displayId).
            // An application-derived window context cannot supply that accessibility token.
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        } else this
        context.also { overlayContext = it }
    }

    internal fun invalidateOverlayWindowContext() { overlayContext = null }

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = SystemClock.elapsedRealtime()
            when (intent.action) {
                // l10n:keep agent stop reason
                Intent.ACTION_SCREEN_OFF -> {
                    NowFeed.onScreen(false, now)
                    stopAll("屏幕关闭了")
                }
                Intent.ACTION_SCREEN_ON -> {
                    NowFeed.onScreen(true, now)
                    // 第三轮 screen-on：只记账；晨间门开不开仍由插件按 /plan 的作息判定。
                    runCatching { ProactiveRuntime.onScreenOn() }
                }
                Intent.ACTION_POWER_CONNECTED -> NowFeed.onCharging(true, now)
                Intent.ACTION_POWER_DISCONNECTED -> NowFeed.onCharging(false, now)
            }
        }
    }

    private fun isCharging(): Boolean = runCatching {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        invalidateOverlayWindowContext()
        val o = ControlOverlay(this)
        val d = MainScreenDriver(this, o)
        overlay = o
        driver = d
        instance = this
        PhoneRegistry.mainDriver = d
        PhoneRegistry.overlayGate = o
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(power, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(power, filter)
            receiverRegistered = true
        } catch (e: Exception) { Log.w(TAG, "power receiver", e) }
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) probe = A11yProbe(this, d, o).also { it.register() }
        val now = SystemClock.elapsedRealtime()
        NowFeed.onScreen(
            runCatching { getSystemService(PowerManager::class.java)?.isInteractive == true }.getOrDefault(true),
            now,
        )
        NowFeed.onCharging(isCharging(), now)
        A11yHealth.onConnected(now)
        A11yHealthFile.write(this, A11yHealth.snapshot(now, true))
        healthHandler?.removeCallbacks(healthTick)
        healthThread?.quitSafely()
        val health = HandlerThread("dsh-a11y-health").also { it.start() }
        healthThread = health
        healthHandler = Handler(health.looper)
        healthHandler?.postDelayed(healthTick, HEALTH_TICK_MS)
        Log.i(TAG, "accessibility service connected")
        PhoneRegistry.controlListener?.onAccessibilityChanged(true)
        // The service can start at boot without the Flutter process; bring the watchdog up too.
        if (PhoneControl.switchedOn(this)) runCatching { A11yWatchdogService.start(this) }
        // 触发测试 (loop/): reads the foreground package only, and only while switched on.
        runCatching { LoopRuntime.attach(this) }.onFailure { Log.w(TAG, "loop attach", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        clock.tick(event?.packageName)
        A11yHealth.onEvent(now, event?.packageName)
        NowFeed.onApp(event?.packageName?.toString(), now)
        // D2: opt-in foreground package/activity log; it reads nothing and changes no decision.
        runCatching { ForegroundRecorder.onEvent(this, event) }
        runCatching { LoopRuntime.onEvent(this, event) }
    }

    /**
     * The system interrupts feedback (rare for a feedbackGeneric service). The service stays
     * bound, so the registration stays; only the running task stops.
     */
    override fun onInterrupt() {
        A11yHealth.onInterrupt(SystemClock.elapsedRealtime())
        // l10n:keep agent stop reason
        stopAll("无障碍服务被系统打断")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // l10n:keep agent stop reason
        teardown("无障碍服务已关闭")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // l10n:keep agent stop reason
        teardown("无障碍服务已停止")
        super.onDestroy()
    }

    /** Screen off, lock, interrupt: abort what runs, answer pending questions with STOP, tell the core. */
    internal fun stopAll(reason: String) {
        driver?.abort()
        overlay?.stopAll()
        PhoneRegistry.controlListener?.onStopRequested(reason)
    }

    private fun teardown(reason: String) {
        healthHandler?.removeCallbacks(healthTick)
        healthThread?.quitSafely()
        healthHandler = null
        healthThread = null
        runCatching { LoopRuntime.detach(this) }
        if (instance !== this && driver == null) return
        if (instance === this) {
            A11yHealth.onUnbound(SystemClock.elapsedRealtime(), reason)
            A11yHealthFile.write(this, A11yHealth.snapshot(SystemClock.elapsedRealtime(), true))
            NowFeed.reset()
        }
        if (instance !== this && driver == null) return
        val d = driver
        val o = overlay
        if (PhoneRegistry.mainDriver === d) PhoneRegistry.mainDriver = null
        if (PhoneRegistry.overlayGate === o) PhoneRegistry.overlayGate = null
        if (instance === this) instance = null
        invalidateOverlayWindowContext()
        d?.abort()
        o?.destroy()
        d?.close()
        probe?.unregister()
        probe = null
        if (receiverRegistered) { runCatching { unregisterReceiver(power) }; receiverRegistered = false }
        driver = null
        overlay = null
        Log.i(TAG, "accessibility service gone: $reason")
        PhoneRegistry.controlListener?.let { it.onStopRequested(reason); it.onAccessibilityChanged(false) }
    }

    companion object {
        private const val TAG = "DshA11y"
        private const val HEALTH_TICK_MS = 30_000L

        /** The connected service, if any. */
        @Volatile var instance: PhoneA11yService? = null
            private set

        fun isConnected(): Boolean = instance != null

        /** Connected and not known-dead: the keeper may wait instead of rebinding. */
        fun isAlive(now: Long = SystemClock.elapsedRealtime(), screenOn: Boolean = true): Boolean =
            instance != null && A11yHealth.isAlive(now, screenOn)

        /** Connected and answering right now; STALE means "bound but not delivering". */
        fun isHealthy(now: Long = SystemClock.elapsedRealtime(), screenOn: Boolean = true): Boolean =
            instance != null && A11yHealth.isHealthy(now, screenOn)

        fun health(now: Long = SystemClock.elapsedRealtime(), screenOn: Boolean = true): A11yHealth.Snapshot =
            A11yHealth.snapshot(now, screenOn)

        fun component(context: Context) = ComponentName(context.packageName, PhoneA11yService::class.java.name)

        /** Whether the person switched the service on in system settings (it may not be bound yet). */
        fun isEnabled(context: Context): Boolean {
            val list = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Exception) { null } ?: return false
            val me = component(context)
            return list.split(':').any { entry -> ComponentName.unflattenFromString(entry.trim()) == me }
        }
    }
}
