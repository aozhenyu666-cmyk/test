package com.zongkong.sentinel

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.zongkong.sentinel.watch.EventProgressFile

/**
 * 哨兵的无障碍服务（默认进程）。
 *
 * v2 之后的定位（规范 §2.1）：**传感与交互通道，不是计时器，也不是唯一锁屏通道。**
 *  - 只做窗口观察、引导拦截、拦截页；
 *  - 每条可比对的窗口切换写入事件进度文件（`:watch` 读它判断事件通道是否失灵，T03）；
 *  - 连接/销毁时**拉起 `:watch`**，健康调度与锁屏执行都在那边；
 *  - 这里只轮询故障页展示，不做健康计时；无障碍主线程冻结时，`:watch` 仍要能发现并锁屏（T05）。
 *
 * v1 兼容：总控老版本只写心跳和名单，没有 v2 挑战能力。这里保留「它掉线就拦名单」的行为，
 * 但健康状态一律由 `:watch` 的挑战 ack 判定，**不再拿 `running` 布尔当健康凭据**（T02）。
 */
class SentinelService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var lastBlockAt = 0L
    private var eventSeq = 0L
    private val events by lazy { EventProgressFile(filesDir) }
    private var eventState = EventProgressFile.State()
    private val pagePrompt by lazy { PagePrompt(this) }
    private val presentation = FaultPresentation()
    private var v2Fault: String? = null
    private var emergency = false
    private var receiverRegistered = false
    private val healthReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_HEALTH) return
            v2Fault = if (intent.getBooleanExtra("known", false)) intent.getStringExtra("fault") else null
            emergency = intent.getBooleanExtra("emergency", false)
            showRepairIfNeeded()
        }
    }
    // 只负责把已经判定的故障显示出来；不替代 :watch 的挑战、计时或锁屏。
    private val presentationTick = object : Runnable {
        override fun run() {
            runCatching { showRepairIfNeeded() }.onFailure { Log.w(TAG, "故障页检查失败", it) }
            main.postDelayed(this, 1_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            running = true
            if (!receiverRegistered) {
                val filter = android.content.IntentFilter(ACTION_HEALTH)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(healthReceiver, filter, HEALTH_PERMISSION, main, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(healthReceiver, filter, HEALTH_PERMISSION, main)
                }
                receiverRegistered = true
            }
            main.removeCallbacks(presentationTick)
            main.post(presentationTick)
            instanceId = "a11y-${SystemClock.elapsedRealtime()}-${(1000..9999).random()}"
            Store.touch()
            Store.log(this, "哨兵连上了（实例 $instanceId）")
            // 跨进程的调度与锁屏执行：拉起独立 :watch 进程（规范 §2.1）。
            WatchBridge.start(this)
            eventState = events.read() ?: EventProgressFile.State()
            eventState = eventState.copy(instanceId = instanceId)
        } catch (e: Throwable) {
            Log.e(TAG, "接入时出错", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName && pagePrompt.showingOverlay && !BlockActivity.isShowing) return
            Store.touch()
            recordEvent(pkg)
            if (pkg == packageName) { lastPkg = pkg; return }
            if (pkg == "com.android.systemui") return
            lastPkg = pkg
            check(pkg)
            showRepairIfNeeded()
        } catch (e: Throwable) {
            Log.w(TAG, "处理事件出错", e)
        }
    }

    /** 事件进度落盘：序号 + 单调时刻 + 最近若干条（`:watch` 靠它区分「线程卡」和「事件不投递」）。 */
    private fun recordEvent(pkg: String) {
        val nowMono = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        eventSeq += 1
        val ev = EventProgressFile.Event(eventSeq, pkg, nowMono, nowWall)
        val base = events.read() ?: eventState
        if (events.append(base.copy(instanceId = instanceId), ev)) {
            eventState = EventProgressFile.State(
                seq = eventSeq,
                atMono = nowMono,
                atWall = nowWall,
                recent = (base.recent.filterNot { it.seq == ev.seq } + ev).takeLast(EventProgressFile.KEEP),
                instanceId = instanceId,
            )
        }
    }

    /**
     * 打开 [pkg] 时要不要拦。
     *
     * v1 兼容路径：总控老版本没有 v2 协议，只能看心跳与名单。这里**不宣布健康**，
     * 只做「掉线就别想畅玩」的拦截；真正的健康结论在 `:watch` 的挑战 ack 里。
     */
    private fun check(pkg: String) {
        if (emergency) return
        val st = Store.state(this)
        val confirmedFault = !v2Fault.isNullOrBlank() || !Sys.enabled(this, Sys.mainGuard())
        if (!Decide.shouldBlock(st, System.currentTimeMillis(), pkg, Sys.mainInstalled(this), released = Store.releaseGranted(this), confirmedFault = confirmedFault)) return
        if (!interactive()) return
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < 1_500) return
        lastBlockAt = now
        Store.log(this, "拦下 ${label(pkg)}")
        pagePrompt.open(pkg, BlockActivity.intent(this, pkg), "总控掉线，请先处理",
            visible = { BlockActivity.isShowing },
            relevant = { (lastPkg == pkg || lastPkg == packageName) && interactive() && !emergency &&
                Decide.shouldBlock(Store.state(this), System.currentTimeMillis(), pkg,
                    Sys.mainInstalled(this), released = Store.releaseGranted(this),
                    confirmedFault = !v2Fault.isNullOrBlank() || !Sys.enabled(this, Sys.mainGuard())) })
    }

    private fun repairAllowed(): Boolean {
        val state = Store.state(this)
        return !emergency && !Store.releaseGranted(this) &&
            (state == null || System.currentTimeMillis() >= state.freeUntil)
    }

    private fun faultReason(): String? {
        if (!Sys.mainInstalled(this)) return null
        val state = Store.state(this)
        val engaged = state != null || v2Fault != null
        if (!engaged) return null
        if (!Sys.enabled(this, Sys.mainGuard())) return "总控的无障碍已关闭"
        v2Fault?.takeIf { it.isNotBlank() }?.let { return it }
        // v2 已有观测时不再用旧心跳判健康；空字符串表示本轮没有确认故障。
        if (v2Fault != null) return null
        if (state != null && !Decide.mainAlive(state, System.currentTimeMillis())) return "总控的连接已中断"
        return null
    }

    private fun showRepairIfNeeded() {
        val reason = faultReason()
        val unlocked = !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val inRepair = lastPkg == packageName || lastPkg == Proto.MAIN_PKG ||
            lastPkg == "com.android.settings" || lastPkg?.contains("permissioncontroller") == true
        if (!presentation.shouldPresent(reason != null, repairAllowed(), interactive() && unlocked && !inRepair)) return
        pagePrompt.open("repair", BlockActivity.repairIntent(this, reason!!), "总控异常，需要处理",
            visible = { BlockActivity.isShowing },
            relevant = { faultReason() != null && repairAllowed() && interactive() &&
                !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked &&
                lastPkg != "com.android.settings" && lastPkg != Proto.MAIN_PKG &&
                lastPkg?.contains("permissioncontroller") != true },
            onShown = { Store.log(this, "已确认故障处理页显示") },
            onFallback = { Store.log(this, "故障页未到前台，显示可点击的处理入口") })
    }

    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    private fun interactive(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    } catch (_: Throwable) {
        true
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        detachPresentation()
        return super.onUnbind(intent)
    }

    private fun detachPresentation() {
        running = false
        instanceId = null
        pagePrompt.dismiss()
        main.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            unregisterReceiver(healthReceiver)
            receiverRegistered = false
        }
    }

    override fun onDestroy() {
        detachPresentation()
        runCatching { Store.log(this, "哨兵停了（:watch 仍在看护）") }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Sentinel"
        const val ACTION_HEALTH = "com.zongkong.sentinel.HEALTH_PRESENTATION"
        const val HEALTH_PERMISSION = "com.zongkong.sentinel.permission.HEALTH_PRESENTATION"

        /** 只表示「无障碍连接还在」。**不能**当健康凭据（T02）。 */
        @Volatile var running = false

        /** 本次无障碍连接实例；重建必须换新（客户端 ack 里也要带同一个）。 */
        @Volatile var instanceId: String? = null
    }
}

/**
 * 默认进程 → `:watch` 的**显式** Intent 通道（规范 §3.3：不许用 static 传状态）。
 *
 * 只用它「拉活 + 传动作」，不传健康数据；健康数据在 `:watch` 自己的文件与 provider 里。
 */
object WatchBridge {
    private const val WATCH_SERVICE = "com.zongkong.sentinel.watch.WatchService"

    fun start(c: Context) { send(c, "com.zongkong.sentinel.watch.REFRESH") }

    fun emergencyStop(c: Context, reason: String) {
        send(c, "com.zongkong.sentinel.watch.EMERGENCY_STOP", reason)
    }

    fun lockNow(c: Context) { send(c, "com.zongkong.sentinel.watch.LOCK_NOW") }

    fun cancelEmergency(c: Context) { send(c, "com.zongkong.sentinel.watch.CANCEL_EMERGENCY") }

    private fun send(c: Context, action: String, reason: String = "") {
        val i = Intent().apply {
            setClassName(c.packageName, WATCH_SERVICE)
            this.action = action
            putExtra("reason", reason)
        }
        try {
            c.startForegroundService(i)
        } catch (_: Throwable) {
            try { c.startService(i) } catch (_: Throwable) {}
        }
    }
}
