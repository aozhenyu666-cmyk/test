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

    private var cover: GateCover? = null
    private val labels = mutableMapOf<String, String>()
    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

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
        GateTrace.init(this)
        cover = GateCover(this)
        val watched = app.settings.app.value.watched
        GateTrace.log("无障碍服务已连接；关注 ${watched.size} 个应用：" + watched.keys.joinToString("、") { label(it) })
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
                if (GateActivity.isShowing) cover?.hide()
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
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val systemPackages = systemAllowed()
        val transient = pkg != packageName && pkg !in homes && (pkg in systemPackages || pkg in WindowRules.STATIC_SYSTEM)
        lastEventAt = System.currentTimeMillis()
        if (!transient && pkg != foregroundPkg) GateTrace.log("切到前台：${label(pkg)}" + if (pkg in homes) "（桌面）" else "", pkg)
        // 换到别的应用或桌面：遮罩收起（一手自己的界面不算换走）
        if (!transient && pkg != packageName && cover?.showing == true && cover?.pkg != pkg) cover?.hide()
        foregroundTracker.update(pkg, transient)
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
            GateTrace.log("不拦：伴读中，这个应用是登记的材料应用", pkg)
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
        val group = prefs.groupOf(pkg)
        if (group == null) {
            if (!isSystem && pkg != packageName) GateTrace.log("不拦：不在关注名单（${label(pkg)}）", pkg)
            return
        }
        if (GateActivity.isShowing) return
        if (cover?.showing == true && cover?.pkg == pkg) return
        // 同一个应用打开时会连续发出好几次切换事件，2 秒内只处理一次
        if (pkg == lastLaunchPkg && now - lastLaunchAt < 2_000) return

        if (!querying.add(pkg)) return
        scope.launch {
            try {
            if (foregroundPkg != pkg) { GateTrace.log("不拦：查的时候已经换走了", pkg); return@launch }
            if (app.partner.pendingClose() != null) {
                launchGate(pkg, group, now)
                return@launch
            }
            val pass = try {
                app.database.dao().activePass(group, now)
            } catch (e: Exception) {
                Log.e(TAG, "查询放行失败", e)
                GateTrace.log("查放行出错：${e.javaClass.simpleName}，按没放行处理", pkg)
                null
            }
            if (foregroundPkg != pkg) return@launch
            if (pass != null) {
                GateTrace.log("不拦：「$group」有放行，到 ${GateTrace.stamp(pass.endAt)}", pkg)
                scheduleExpiryCheck(pass.endAt)
                return@launch
            }
            launchGate(pkg, group, now)
            } finally { querying.remove(pkg) }
        }
    }

    private fun launchGate(pkg: String, group: String, now: Long) {
        if (GateActivity.isShowing) return
        if (pkg == lastLaunchPkg && now - lastLaunchAt < 2_000) return
        lastLaunchPkg = pkg
        lastLaunchAt = System.currentTimeMillis()
        app.stats.event(if (group == GateActivity.WINDOW_GROUP) DayStats.WINDOW_BLOCK else DayStats.GATE_SHOWN)
        GateTrace.log("拦下：弹出入口页（$group）", pkg)
        val opened = if (pretendBlockedForTest) {
            GateTrace.log("测试：假装系统悄悄拦下了后台弹出", pkg)
            true
        } else try {
            startActivity(GateActivity.intent(this@GateService, pkg, group))
            true
        } catch (e: Exception) {
            RunLog.e("收费站", "无法打开思考页", e)
            Log.e(TAG, "无法打开思考页", e)
            GateTrace.log("弹出失败：${e.javaClass.simpleName} ${e.message.orEmpty()}", pkg)
            false
        }
        // 很多国产系统不给“后台弹出界面”时不报错、只是不显示：过一会儿看页面到底出来没有
        scope.launch {
            if (opened) delay(2_500)
            if (GateActivity.isShowing) { _problem.value = null; return@launch }
            if (foregroundPkg != pkg) return@launch
            _problem.value = "系统没让入口页弹出来（多半是没开“后台弹出界面”权限），已改用遮罩挡住"
            GateTrace.log("入口页没出来：系统拦了后台弹出。改用遮罩挡住", pkg)
            showCover(pkg, group)
        }
    }

    private fun showCover(pkg: String, group: String) {
        cover?.show(pkg, label(pkg)) {
            GateTrace.log("遮罩：点了去做题", pkg)
            runCatching { startActivity(GateActivity.intent(this@GateService, pkg, group)) }
                .onFailure { GateTrace.log("从遮罩打开入口失败：${it.javaClass.simpleName}", pkg) }
            scope.launch {
                delay(2_000)
                if (GateActivity.isShowing) cover?.hide()
                else {
                    GateTrace.log("从遮罩也没能打开入口页", pkg)
                    cover?.say("系统还是不让一手弹出来。请点下面“去开权限”，在权限里打开「后台弹出界面」（有的手机叫「后台弹出页面」「悬浮窗/显示在其他应用上层」），再回来。")
                }
            }
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
        GateTrace.log("无障碍服务断开（被关闭或被系统停掉）")
        cover?.hide()
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
        cover?.hide()
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

        /** 最近一次收到窗口切换的时间；体检页用来判断服务是不是还活着 */
        @Volatile
        var lastEventAt = 0L
            private set

        /** 只给模拟器测试用：假装系统不报错地拦下了后台弹出（vivo、小米等没开“后台弹出界面”时就是这样） */
        @Volatile
        var pretendBlockedForTest = false

        /** 入口页出来了：遮罩可以收起 */
        fun hideCover() { running?.let { s -> s.scope.launch { s.cover?.hide() } } }

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
