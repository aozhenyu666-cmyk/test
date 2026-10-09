package com.yishou.app.gate

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yishou.app.YishouApp
import com.yishou.app.system.Notifications
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        Notifications.showRunning(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        foregroundPkg = pkg
        if (pkg == packageName) return
        check(pkg)
    }

    private fun check(pkg: String) {
        val group = app.settings.app.value.groupOf(pkg) ?: return
        if (GateActivity.isShowing) return
        val now = System.currentTimeMillis()
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
            if (GateActivity.isShowing) return@launch
            lastLaunchPkg = pkg
            lastLaunchAt = System.currentTimeMillis()
            try {
                startActivity(GateActivity.intent(this@GateService, pkg, group))
            } catch (e: Exception) {
                Log.e(TAG, "无法打开思考页", e)
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
