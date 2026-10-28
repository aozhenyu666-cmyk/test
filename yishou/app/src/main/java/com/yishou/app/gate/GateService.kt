package com.yishou.app.gate

import com.yishou.app.settings.AppZone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yishou.app.YishouApp
import com.yishou.app.stats.DayStats
import com.yishou.app.system.Notifications
import com.yishou.app.system.ExternalGrace
import com.yishou.app.log.RunLog
import com.yishou.app.window.WindowClock
import com.yishou.app.window.WindowRules
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

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
    private val foregroundTracker = GateForeground()
    private val foregroundPkg get() = foregroundTracker.current
    private var watchJob: Job? = null
    private val querying = mutableSetOf<String>()
    private val launchDebounce = GateLaunchDebounce()
    private val pagePrompt by lazy { PagePrompt(this) }
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

    private var wakeRegistered = false
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            val now = java.time.LocalDateTime.now(AppZone.zone)
            val today = now.toLocalDate().toEpochDay()
            val p = app.settings.app.value
            val cfg = com.yishou.app.reflex.MorningConfig(p.morningEnabled, p.morningFromHour, p.morningToHour)
            if (!com.yishou.app.reflex.Morning.shouldWake(cfg, now.hour, today, p.lastWakeDay)) return
            // 先记今天叫过，再查库，避免两次广播一起打开两个页面。
            app.settings.updateApp { it.copy(lastWakeDay = today) }
            scope.launch {
                try {
                    val open = app.database.strands().open()
                    val target = if (open != null) Intent(this@GateService, com.yishou.app.MainActivity::class.java)
                        .putExtra(com.yishou.app.MainActivity.EXTRA_ROUTE, "strand").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    else GateActivity.morningIntent(this@GateService)
                    if (!GateActivity.isShowing) startActivity(target)
                } catch (e: Exception) { RunLog.e("早上唤起", "没能打开断点页", e) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        _connected.value = true
        _problem.value = null
        if (!wakeRegistered) {
            androidx.core.content.ContextCompat.registerReceiver(this, wakeReceiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            wakeRegistered = true
        }
        Notifications.showRunning(this)
        watchJob?.cancel()
        watchJob = scope.launch {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                delay(1_000)
                val awake = getSystemService(android.os.PowerManager::class.java).isInteractive
                val locked = getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                if (awake && !locked && !GateActivity.isShowing) foregroundPkg?.takeIf { it != packageName }?.let(::check)
            }
        }
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
        try {
            onWindow(event)
        } catch (e: Exception) {
            _problem.value = "这次应用切换没能处理，正在等待下一次检查"
            RunLog.e("收费站", "处理应用切换失败", e)
        }
    }

    private fun onWindow(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName && pagePrompt.showingOverlay && !GateActivity.isShowing) return
        // 旧题页关闭后可能迟到一条窗口事件，不能把刚打开的目标应用覆盖成“一手在前台”。
        if (pkg == packageName && event.className?.toString() == GateActivity::class.java.name && !GateActivity.isShowing) return
        val systemPackages = systemAllowed()
        val transient = pkg != packageName && pkg !in homes && (pkg in systemPackages || pkg in WindowRules.STATIC_SYSTEM)
        foregroundTracker.update(pkg, transient)
        launchDebounce.onForeground(foregroundPkg)
        Log.d(TAG, "窗口包名=$pkg，类=${event.className}，当前=$foregroundPkg，做题页=${GateActivity.isShowing}")
        _lastPackage.value = foregroundPkg
        app.partner.foreground(pkg)
        if (pkg == packageName) {
            ExternalGrace.close()
            return
        }
        if (!transient) check(pkg)
    }

    private fun check(pkg: String) {
        if (app.partner.allows(pkg)) {
            shortCheck?.cancel()
            shortPkg = null
            return
        }
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
        // 一手自己打开的相册、相机、屏幕授权：不拦（回到一手就收回许可）
        if (prefs.groupOf(pkg) == null && ExternalGrace.active(now)) return
        if (prefs.groupOf(pkg) == null && WindowClock.active(now, prefs, AppZone.zone) != null) {
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
        if (!launchDebounce.ready(pkg, android.os.SystemClock.elapsedRealtime())) return

        if (!querying.add(pkg)) return
        scope.launch {
            try {
            if (foregroundPkg != pkg) return@launch
            if (app.partner.pendingClose() != null) {
                launchGate(pkg, group, now)
                return@launch
            }
            val pass = try {
                app.database.dao().activePass(group, now)
            } catch (e: Exception) {
                Log.e(TAG, "查询放行失败", e)
                null
            }
            if (foregroundPkg != pkg) return@launch
            if (pass != null) {
                scheduleExpiryCheck(pass.endAt)
                return@launch
            }
            launchGate(pkg, group, now)
            } finally { querying.remove(pkg) }
        }
    }

    private fun launchGate(pkg: String, group: String, now: Long) {
        if (GateActivity.isShowing) return
        val nowMono = android.os.SystemClock.elapsedRealtime()
        if (!launchDebounce.ready(pkg, nowMono)) return
        launchDebounce.launched(pkg, nowMono)
        Log.i(TAG, "请求做题页：$pkg")
        pagePrompt.open(
            targetKey = "$pkg/$group",
            target = GateActivity.intent(this, pkg, group),
            title = "先回一手完成入口思考",
            visible = { GateActivity.isShowing },
            relevant = {
                val awake = getSystemService(android.os.PowerManager::class.java).isInteractive
                val unlocked = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
                val current = app.settings.app.value
                awake && unlocked && (foregroundPkg == pkg || foregroundPkg == packageName) && !app.partner.allows(pkg) &&
                    (current.groupOf(pkg) == group || (group == GateActivity.WINDOW_GROUP &&
                        WindowClock.active(System.currentTimeMillis(), current, AppZone.zone) != null &&
                        WindowRules.decide(pkg, current, systemAllowed(), System.currentTimeMillis()) != WindowRules.Decision.ALLOW))
            },
            onShown = {
                _problem.value = null
                app.stats.event(if (group == GateActivity.WINDOW_GROUP) DayStats.WINDOW_BLOCK else DayStats.GATE_SHOWN)
            },
            onFallback = {
                _problem.value = "做题页没有自动打开，已显示处理入口。请检查系统的后台弹出界面设置"
                RunLog.e("收费站", "做题页启动后没有到前台", IllegalStateException("未收到页面显示确认"))
            },
        )
    }

    /** 微信、QQ 这类可以短暂切过去的：到时还停在这里就拉回陪练。 */
    private fun scheduleShortCheck(pkg: String, minutes: Int) {
        if (shortPkg == pkg && shortCheck?.isActive == true) return
        shortPkg = pkg
        shortCheck?.cancel()
        shortCheck = scope.launch {
            delay(minutes * 60_000L)
            val now = System.currentTimeMillis()
            if (lastAppPkg == pkg && WindowClock.active(now, app.settings.app.value, AppZone.zone) != null &&
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
        pagePrompt.dismiss()
        // 解绑后不能让旧的到期检查和查询继续启动页面；重新连接会重建检查。
        scope.coroutineContext.cancelChildren()
        if (running === this) {
            running = null
            _connected.value = false
            watchJob?.cancel()
            app.partner.foreground(null)
        }
        Notifications.cancelRunning(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        pagePrompt.dismiss()
        if (wakeRegistered) { unregisterReceiver(wakeReceiver); wakeRegistered = false }

        if (running === this) {
            running = null
            _connected.value = false
            watchJob?.cancel()
            app.partner.foreground(null)
        }
        Notifications.cancelRunning(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GateService"
        private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected
        private val _problem = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val problem: kotlinx.coroutines.flow.StateFlow<String?> = _problem
        private val _lastPackage = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val lastPackage: kotlinx.coroutines.flow.StateFlow<String?> = _lastPackage

        @Volatile
        private var running: GateService? = null

        /** 当前前台应用的包名；服务没开时为 null */
        val foreground: String? get() = running?.foregroundPkg

        /** 不算“离开”也不算“回来”的界面：输入法、通知栏、相机、拨号、系统设置（桌面不算，回桌面就是走了） */
        fun neutral(pkg: String): Boolean {
            if (pkg in WindowRules.STATIC_SYSTEM) return true
            val service = running ?: return false
            return pkg in service.systemAllowed() && pkg !in service.homes
        }

        /**
         * 窗口里的“拉回”能不能把你从 pkg 拉走：窗口允许的学习应用、相机、电话、输入法不拉
         * （你可能正在做题、拍作答、接电话）；桌面和其他应用照拉。
         */
        fun mayPullFrom(pkg: String?): Boolean {
            val service = running ?: return true
            if (pkg == null) return true
            if (pkg in service.app.settings.app.value.windowAllowed) return false
            return pkg !in service.systemAllowed() || pkg in service.homes
        }

        /**
         * 从无障碍服务把一个页面拉到前台（陪练窗口的“拉回”）。系统允许开着的无障碍服务从后台打开页面，
         * 和入口思考页是同一个办法。服务没开或被拦下时返回 false，由调用方改发通知。
         */
        fun bringToFront(intent: Intent): Boolean {
            val service = running ?: return false
            return try {
                service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                RunLog.e("拉回", "无法从后台打开陪练窗口", e)
                false
            }
        }
    }
}
