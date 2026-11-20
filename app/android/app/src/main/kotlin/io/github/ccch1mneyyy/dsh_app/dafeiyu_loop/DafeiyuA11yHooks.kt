package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive.ProactiveRuntime
import io.github.ccch1mneyyy.dsh_app.phone.PhoneControl
import io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yHealth
import io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yHealthFile
import io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yHealthPlan
import io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yKeeper
import io.github.ccch1mneyyy.dsh_app.phone.a11y.A11yWatchdogService
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ForegroundRecorder
import io.github.ccch1mneyyy.dsh_app.phone.a11y.FrontChange
import io.github.ccch1mneyyy.dsh_app.phone.a11y.NowFeed
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.phone.a11y.WechatStay

/**
 * Accessibility-service lifecycle, health-probe, power/charging, and foreground telemetry
 * hooks delegated from upstream [PhoneA11yService].
 */
object DafeiyuA11yHooks {
    private const val TAG = "DshA11y"
    private const val HEALTH_TICK_MS = 30_000L

    private var healthThread: HandlerThread? = null
    private var healthHandler: Handler? = null
    private val wechat = WechatStay()
    @Volatile private var lastEngineHealAttemptAt = 0L
    private const val ENGINE_HEAL_COOLDOWN_MS = 60_000L

    private fun tryHealEngine(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEngineHealAttemptAt < ENGINE_HEAL_COOLDOWN_MS) return
        lastEngineHealAttemptAt = now
        // 2026-11-20：原来只在 INSTALLED 时拉；ERROR 也交给保活（有频率上限），他点了停止就不拉。
        runCatching {
            io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.guardian.EngineKeeper.ensureIfWanted(context.applicationContext, "a11y")
        }
    }

    private fun isCharging(context: Context): Boolean = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    fun onServiceConnected(service: PhoneA11yService, foregroundPackageProvider: () -> String?) {
        runCatching {
            val imm = service.getSystemService(InputMethodManager::class.java)
            FrontChange.setIme(imm?.enabledInputMethodList?.map { it.packageName }?.toSet() ?: emptySet())
        }
        FrontChange.setSelf(setOf(service.packageName, "${service.packageName}.fish"))
        val now = SystemClock.elapsedRealtime()
        NowFeed.onScreen(
            runCatching { service.getSystemService(PowerManager::class.java)?.isInteractive == true }.getOrDefault(true),
            now,
        )
        NowFeed.onCharging(isCharging(service), now)
        A11yHealth.onConnected(now)
        A11yHealthFile.write(service, A11yHealth.snapshot(now, true))

        healthHandler?.removeCallbacksAndMessages(null)
        healthThread?.quitSafely()
        val thread = HandlerThread("dsh-a11y-health").also { it.start() }
        val handler = Handler(thread.looper)
        healthThread = thread
        healthHandler = handler
        val tick = object : Runnable {
            override fun run() {
                if (PhoneA11yService.instance !== service) return
                val t = SystemClock.elapsedRealtime()
                val screenOn = runCatching {
                    service.getSystemService(PowerManager::class.java)?.isInteractive == true
                }.getOrDefault(true)
                val locked = runCatching {
                    service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
                }.getOrDefault(false)
                if (screenOn && !locked) {
                    runCatching { foregroundPackageProvider() }.getOrNull()?.let { NowFeed.onApp(it, t) }
                    val ok = runCatching {
                        service.rootInActiveWindow != null || service.windows.isNotEmpty()
                    }.getOrDefault(false)
                    A11yHealth.onProbe(t, ok)
                    A11yHealthFile.write(service, A11yHealth.snapshot(t, true))
                    if (A11yHealth.snapshot(t, true).verdict == A11yHealthPlan.Verdict.DEAD) {
                        Thread({ runCatching { A11yKeeper.check(service.applicationContext) } }, "dsh-a11y-heal").start()
                    }
                    tryHealEngine(service)
                }
                handler.postDelayed(this, HEALTH_TICK_MS)
            }
        }
        handler.postDelayed(tick, HEALTH_TICK_MS)

        if (PhoneControl.switchedOn(service)) runCatching { A11yWatchdogService.start(service) }
        runCatching { LoopRuntime.attach(service) }.onFailure { Log.w(TAG, "loop attach", it) }
    }

    fun onPowerBroadcast(intent: Intent, onScreenOffStop: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                NowFeed.onScreen(false, now)
                wechat.onScreenOff(now)?.let { hit -> runCatching { ProactiveRuntime.onWechatHit(hit.page, hit.dwellMs) } }
                onScreenOffStop()
            }
            Intent.ACTION_SCREEN_ON -> {
                NowFeed.onScreen(true, now)
                runCatching { ProactiveRuntime.onScreenOn() }
                PhoneA11yService.instance?.let { tryHealEngine(it) }
            }
            Intent.ACTION_POWER_CONNECTED -> NowFeed.onCharging(true, now)
            Intent.ACTION_POWER_DISCONNECTED -> NowFeed.onCharging(false, now)
        }
    }

    fun onAccessibilityEvent(service: PhoneA11yService, event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        A11yHealth.onEvent(now, event?.packageName)
        NowFeed.onApp(event?.packageName?.toString(), now)
        runCatching { ForegroundRecorder.onEvent(service, event) }
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val hit = wechat.onWindow(event.packageName?.toString(), event.className?.toString(), now)
            if (hit != null) runCatching { ProactiveRuntime.onWechatHit(hit.page, hit.dwellMs) }
        }
        runCatching { LoopRuntime.onEvent(service, event) }
    }

    fun onInterrupt() {
        A11yHealth.onInterrupt(SystemClock.elapsedRealtime())
    }

    fun onTeardown(service: PhoneA11yService, reason: String, isActiveInstance: Boolean) {
        healthHandler?.removeCallbacksAndMessages(null)
        healthThread?.quitSafely()
        healthHandler = null
        healthThread = null
        runCatching { LoopRuntime.detach(service) }
        if (isActiveInstance) {
            val now = SystemClock.elapsedRealtime()
            A11yHealth.onUnbound(now, reason)
            A11yHealthFile.write(service, A11yHealth.snapshot(now, true))
            NowFeed.reset()
        }
    }
}
