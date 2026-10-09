package com.yishou.app.gate

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yishou.app.YishouApp
import com.yishou.app.stats.DayStats
import com.yishou.app.system.Notifications
import com.yishou.app.window.WindowClock
import com.yishou.app.window.WindowRules
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 入口思考页的无障碍服务。
 *
 * 透明原则：只订阅 TYPE_WINDOW_STATE_CHANGED，只读事件里的 packageName，
 * 不读取屏幕内容（配置里 canRetrieveWindowContent = false）、输入内容或其他应用的数据。
 * 用户随时可以在系统设置里关闭本服务。
 */
class GateService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as YishouApp

    /** 最近一次切到前台的应用包名 */
    private var foregroundPkg: String? = null
    private var lastLaunchPkg: String? = null
    private var lastLaunchAt = 0L
    /** 放行到期时再检查一次：到期时还停留在该应用，就弹出思考页 */
    private var expiryCheck: Job? = null

    /** 短暂停留应用的检查：到时还停在这个应用，就拉回陪练 */
    private var shortCheck: Job? = null
    private var shortPkg: String? = null
    /** 最近一个不是系统界面（输入法、通知栏、桌面）的前台应用 */
    private var lastAppPkg: String? = null
    /** 桌面、输入法、相机、拨号等系统应用，开局规则里永远放行 */
    private var systemAllowed: Set<String> = emptySet()
    private var systemAllowedAt = 0L
    private var homes: Set<String> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
        Notifications.showRunning(this)
    }

    /** 查询桌面、输入法、相机、拨号对应的应用（换了输入法或桌面也能跟上，10 分钟刷新一次）。 */
    private fun systemAllowed(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - systemAllowedAt < 10 * 60_000 && systemAllowed.isNotEmpty()) return systemAllowed
        val pm = packageManager
        fun handlers(i: Intent) = try {
            pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        homes = handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)).toSet()
        val set = buildSet {
            addAll(homes)
            addAll(handlers(Intent(MediaStore.ACTION_IMAGE_CAPTURE)))
            addAll(handlers(Intent(Intent.ACTION_DIAL)))
            try {
                getSystemService(InputMethodManager::class.java).enabledInputMethodList.forEach { add(it.packageName) }
            } catch (e: Exception) {
                Log.w(TAG, "读取输入法列表失败", e)
            }
        }
        systemAllowed = set
        systemAllowedAt = now
        return set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        foregroundPkg = pkg
        if (pkg == packageName) return
        check(pkg)
    }

    private fun check(pkg: String) {
        val prefs = app.settings.app.value
        val now = System.currentTimeMillis()
        val isSystem = pkg in systemAllowed() || pkg in WindowRules.STATIC_SYSTEM
        val isHome = pkg in homes
        if (!isSystem) lastAppPkg = pkg
        if (isHome) lastAppPkg = null
        // 离开了短暂停留的应用（回桌面或切到别的应用；弹出输入法、下拉通知栏不算离开）
        if (pkg != shortPkg && (!isSystem || isHome)) {
            shortCheck?.cancel()
            shortPkg = null
        }
        // 陪练窗口进行中：先按开局规则看这个应用能不能用（关注的应用另由下面的思考页处理）
        if (prefs.groupOf(pkg) == null && WindowClock.active(now, prefs, ZoneId.systemDefault()) != null) {
            when (WindowRules.decide(pkg, prefs, systemAllowed(), now)) {
                WindowRules.Decision.ALLOW -> {}
                WindowRules.Decision.SHORT -> scheduleShortCheck(pkg, prefs.windowShortMinutes)
                WindowRules.Decision.BLOCK -> launchGate(pkg, GateActivity.WINDOW_GROUP, now)
            }
            return
        }
        val group = prefs.groupOf(pkg) ?: return
        if (GateActivity.isShowing) return
        // 同一个应用打开时会连续发出好几次切换事件，2 秒内只处理一次
        if (pkg == lastLaunchPkg && now - lastLaunchAt < 2_000) return

        scope.launch {
            val pass = try {
                app.database.dao().activePass(group, now)
            } catch (e: Exception) {
                Log.e(TAG, "查询放行失败", e)
                null
            }
            if (pass != null) {
                scheduleExpiryCheck(pass.endAt)
                return@launch
            }
            launchGate(pkg, group, now)
        }
    }

    private fun launchGate(pkg: String, group: String, now: Long) {
        if (GateActivity.isShowing) return
        if (pkg == lastLaunchPkg && now - lastLaunchAt < 2_000) return
        lastLaunchPkg = pkg
        lastLaunchAt = System.currentTimeMillis()
        app.stats.event(if (group == GateActivity.WINDOW_GROUP) DayStats.WINDOW_BLOCK else DayStats.GATE_SHOWN)
        try {
            startActivity(GateActivity.intent(this@GateService, pkg, group))
        } catch (e: Exception) {
            Log.e(TAG, "无法打开思考页", e)
        }
    }

    /** 微信、QQ 这类可以短暂切过去的：到时还停在这里就拉回陪练。 */
    private fun scheduleShortCheck(pkg: String, minutes: Int) {
        if (shortPkg == pkg && shortCheck?.isActive == true) return
        shortPkg = pkg
        shortCheck?.cancel()
        shortCheck = scope.launch {
            delay(minutes * 60_000L)
            val now = System.currentTimeMillis()
            if (lastAppPkg == pkg && WindowClock.active(now, app.settings.app.value, ZoneId.systemDefault()) != null &&
                app.settings.app.value.windowPauseUntil <= now
            ) {
                shortPkg = null
                launchGate(pkg, GateActivity.WINDOW_GROUP, now)
            }
        }
    }

    private fun scheduleExpiryCheck(endAt: Long) {
        expiryCheck?.cancel()
        expiryCheck = scope.launch {
            delay((endAt - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
            foregroundPkg?.takeIf { it != packageName }?.let { check(it) }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        Notifications.cancelRunning(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Notifications.cancelRunning(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GateService"
    }
}
