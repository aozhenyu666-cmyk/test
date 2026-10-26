package com.yishou.app.gate

import com.yishou.app.settings.AppZone

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.yishou.app.YishouApp
import com.yishou.app.healthwire.Facts
import com.yishou.app.healthwire.LaunchTracker
import com.yishou.app.healthwire.YishouGuard
import com.yishou.app.healthwire.ZongkongLink
import com.yishou.app.stats.DayStats
import com.yishou.app.system.Notifications
import com.yishou.app.system.ExternalGrace
import com.yishou.app.log.RunLog
import com.yishou.app.window.WindowClock
import com.yishou.app.window.WindowRules
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 入口思考页的无障碍服务。
 *
 * 透明原则：只订阅 TYPE_WINDOW_STATE_CHANGED，只读事件里的 packageName，
 * 不读取屏幕内容（配置里 canRetrieveWindowContent = false）、输入内容或其他应用的数据。
 * 用户随时可以在系统设置里关闭本服务。
 *
 * **v2 关键修复（报告 §8 / 规范 §8，T04/T05）**：
 * 本文件的 [onAccessibilityEvent] 和 [check] 跑在**无障碍主线程**上，所以这里
 *   - 绝不发起同步跨进程调用（原先是 `ZongkongClient.status(this)`，会把整条事件通道拖死）；
 *   - 只读内存里的缓存快照（[ZongkongLink.read]），顺手请求一次后台刷新；
 *   - 总控失联时进入「失联态」，按本地规则继续走，不阻塞、不猜测。
 *
 * 同时这里是健康协议里**真正承担无障碍执行的那个线程**：哨兵的挑战由
 * [postOnA11yThread] 投到这个 Handler，在 [handleChallengeOnA11yThread] 里处理完再回话（T02）。
 */
class GateService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** a11y 主线程的 Handler：挑战投递和页面回执都走它（规范 §3.2）。 */
    private val a11yHandler = Handler(Looper.getMainLooper())

    private val app get() = application as YishouApp

    /** 本次连接的实例号：重建必须换新（规范 §3.1 instanceId）。 */
    private var instanceId: String = ""

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
        running = this
        instanceId = UUID.randomUUID().toString()
        Facts.onConnected(instanceId)
        Notifications.showRunning(this)
        // 注册到哨兵（后台线程 + 预算；不在这里等结果）。健康仍只由挑战 ack 判定。
        YishouGuard.onA11yConnected(this, instanceId)
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
        // 事件通道进度：这是「无障碍事件还在流动」的唯一证据（T03），写进本地事实给挑战 ack 带出去。
        Facts.onEvent(SystemClock.elapsedRealtime())
        foregroundPkg = pkg
        app.partner.foreground(pkg)
        if (pkg == packageName) {
            ExternalGrace.close()
            return
        }
        check(pkg)
    }

    /**
     * 事件线程上的判断。**这里不允许任何跨进程调用**。
     *
     * 原先的两处阻塞点及修法：
     *  1. `if (ZongkongClient.status(this)?.lockdown == true) return` —— 同步 Binder 调用，
     *     改为读 [ZongkongLink.read] 的缓存快照；快照不可用时按「失联态」继续走本地规则。
     *  2. `scope.launch { withContext(Dispatchers.IO) { ZongkongClient.status(...) } }` ——
     *     同样在事件链路上等 IPC，改为直接用同一份缓存判断，判断本身不阻塞。
     */
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
        // 总控状态：只读缓存，顺手请求后台刷新。失联时 read().status 为 null，
        // 不把「读不到」当成「总控没在严管」——照样按本地规则处理。
        val zk = ZongkongLink.read()
        if (zk.wantsRefresh) ZongkongLink.requestRefresh(this)
        if (zk.lockdown) return
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
        if (pkg == lastLaunchPkg && now - lastLaunchAt < 2_000) return

        // 总控正在严管、会把这个应用弹回去：让总控处理，一手不再叠一层思考页。
        // 用缓存里的「总控会弹回哪些应用」，不再在这里等一次 IPC。
        if (zk.bounces(pkg)) return

        // 查放行记录是 IO（Room 自己的执行器）——放到 IO 上做，事件线程不等它。
        scope.launch {
            if (app.partner.pendingClose() != null) {
                launchGate(pkg, group, now)
                return@launch
            }
            val pass = try {
                withContext(Dispatchers.IO) { app.database.dao().activePass(group, now) }
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

    /**
     * 打开思考页（T18）。
     *
     * `startActivity` 返回 true 只代表「系统接了请求」，**不算成功**：
     * 先记 REQUESTED，再记 API_ACCEPTED，然后等 GateActivity 自己 onResume 报「真的在前台」。
     * 到点没等到就记 NOT_FOREGROUND，并让调用方改发通知——不假装成功。
     */
    private fun launchGate(pkg: String, group: String, now: Long): Boolean {
        if (GateActivity.isShowing) return false
        if (pkg == lastLaunchPkg && now - lastLaunchAt < 2_000) return false
        lastLaunchPkg = pkg
        lastLaunchAt = System.currentTimeMillis()
        app.stats.event(if (group == GateActivity.WINDOW_GROUP) DayStats.WINDOW_BLOCK else DayStats.GATE_SHOWN)
        return launchVerified(
            target = TARGET_GATE,
            pkg = pkg,
            group = group,
            intent = GateActivity.intent(this@GateService, pkg, group),
        )
    }

    /** 统一的「打开 + 观测」：真实成功要等目标页报前台。 */
    private fun launchVerified(target: String, pkg: String, group: String, intent: Intent): Boolean {
        val started = SystemClock.elapsedRealtime()
        val launchId = LaunchTracker.begin(target, pkg, group, started)
        val accepted = try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "无法打开 $target", e)
            RunLog.e("启动", "无法打开 $target（$pkg）", e)
            false
        }
        LaunchTracker.onApiResult(launchId, accepted, SystemClock.elapsedRealtime(), if (accepted) "系统接受请求" else "startActivity 抛异常")
        Facts.onLaunch(if (accepted) LaunchTracker.API_ACCEPTED else LaunchTracker.API_REJECTED, SystemClock.elapsedRealtime())
        if (!accepted) return false
        // 观测：目标页到位后由它自己回调 claimAwaiting；这里只负责「超时就认账」。
        a11yHandler.postDelayed({
            verifyLaunch(launchId)
        }, LaunchTracker.DEFAULT_VERIFY_WITHIN_MS)
        return true
    }

    private fun verifyLaunch(launchId: String) {
        val receipt = LaunchTracker.all().firstOrNull { it.launchId == launchId } ?: return
        if (!receipt.awaitingObservation) return
        val done = LaunchTracker.onObserved(
            launchId,
            foreground = false,
            nowMono = SystemClock.elapsedRealtime(),
            note = "超过 ${LaunchTracker.DEFAULT_VERIFY_WITHIN_MS}ms 没观测到 ${receipt.target} 在前台",
        ) ?: return
        Facts.onLaunch(LaunchTracker.NOT_FOREGROUND, SystemClock.elapsedRealtime())
        RunLog.e("启动", "没有观测到 ${done.target} 真的到前台（pkg=${done.pkg}）：${done.note}")
        // 兜底：发一条通知，让人自己点进来。不把「调用返回」当成功。
        try {
            Notifications.showReminder(
                this,
                Notifications.ID_WINDOW,
                "思考页没能自己弹出来",
                "系统拦下了后台启动。点这里回到一手，或者先在权限页打开「后台弹出界面」。",
                GateActivity::class.java,
            )
        } catch (e: Exception) {
            Log.w(TAG, "发启动失败通知时出错", e)
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
        if (running === this) {
            running = null
            app.partner.foreground(null)
        }
        Facts.onDisconnected()
        Notifications.cancelRunning(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (running === this) {
            running = null
            app.partner.foreground(null)
        }
        Facts.onDisconnected()
        Notifications.cancelRunning(this)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * **健康协议的执行线程就在这个方法里**（T02）。
     *
     * 由 [HealthProvider] 在 Binder 线程上调用；[block] 会被 post 到 a11y 主线程执行。
     * 返回 false = 投不进去（服务没连上 / 已销毁），此时 Provider 必须如实回 ok=false。
     *
     * 这里刻意只做「记录与读取」，不做任何跨进程调用或磁盘 IO：
     * 因为这个线程如果被拖住，整条无障碍事件通道都会停。
     */
    private fun handleChallengeOnA11yThread(block: () -> Unit): Boolean {
        val service = running ?: return false
        return service.a11yHandler.post {
            try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "处理挑战时出错", e)
            }
        }
    }

    companion object {
        private const val TAG = "GateService"

        const val TARGET_GATE = "GateActivity"

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
         * 把一段代码投到 a11y 主线程（健康挑战用，规范 §3.2）。返回 false = 没有可用实例。
         */
        fun postOnA11yThread(block: () -> Unit): Boolean {
            val service = running ?: return false
            return service.handleChallengeOnA11yThread(block)
        }

        /** 当前连接实例号（服务没开时为 null）。 */
        fun instanceIdOrNull(): String? = running?.instanceId

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
         * 从无障碍服务把一个页面拉到前台（陪练窗口的“拉回”）。
         *
         * **T18**：`startActivity` 返回 true 不算成功。这里返回的 Boolean 只表示「请求已发出」；
         * 真正的结论要等 [LaunchTracker] 记下目标页是否真的到了前台。
         * 调用方（WindowViewModel）在 false 时改发通知；在 true 时也不应宣称「已拉回」，只能说「已发出」。
         */
        /**
         * 从无障碍服务把一个页面拉到前台（陪练窗口的“拉回”）。
         *
         * **T18**：`startActivity` 返回 true 不算成功。返回 launchId 让调用方去 [LaunchTracker]
         * 查真结论；返回 null 表示请求根本发不出去（没有无障碍实例 / 抛异常）。
         */
        fun bringToFrontWithReceipt(intent: Intent): String? {
            val service = running ?: return null
            val target = intent.component?.className?.substringAfterLast('.') ?: "unknown"
            val started = SystemClock.elapsedRealtime()
            val launchId = LaunchTracker.begin(target, "", "", started)
            val accepted = try {
                service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                RunLog.e("拉回", "无法从后台打开陪练窗口", e)
                false
            }
            val at = SystemClock.elapsedRealtime()
            LaunchTracker.onApiResult(launchId, accepted, at, if (accepted) "系统接受拉回请求" else "startActivity 抛异常")
            Facts.onLaunch(if (accepted) LaunchTracker.API_ACCEPTED else LaunchTracker.API_REJECTED, at)
            if (!accepted) return null
            // 观测：目标页到位后由它自己 claim；这里只负责「超时就认账」。
            service.a11yHandler.postDelayed({
                val r = LaunchTracker.all().firstOrNull { it.launchId == launchId }
                if (r != null && r.awaitingObservation) {
                    LaunchTracker.onObserved(
                        launchId,
                        foreground = false,
                        nowMono = SystemClock.elapsedRealtime(),
                        note = "超过 ${LaunchTracker.DEFAULT_VERIFY_WITHIN_MS}ms 没观测到 $target 在前台",
                    )
                    Facts.onLaunch(LaunchTracker.NOT_FOREGROUND, SystemClock.elapsedRealtime())
                }
            }, LaunchTracker.DEFAULT_VERIFY_WITHIN_MS)
            return launchId
        }

        /** 兼容旧调用点：只表示「请求已发出」，不代表已经到前台。 */
        fun bringToFront(intent: Intent): Boolean = bringToFrontWithReceipt(intent) != null
    }
}
