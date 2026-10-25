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
 *  - 这里**没有**循环计时器：无障碍主线程冻结时，`:watch` 仍要能发现并锁屏（T05）。
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            running = true
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
            Store.touch()
            recordEvent(pkg)
            if (pkg == packageName || pkg == "com.android.systemui") return
            lastPkg = pkg
            check(pkg)
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
        val st = Store.state(this)
        if (!Decide.shouldBlock(st, System.currentTimeMillis(), pkg, Sys.mainInstalled(this), released = Store.releaseGranted(this))) return
        if (!interactive()) return
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < 1_500) return
        lastBlockAt = now
        Store.log(this, "拦下 ${label(pkg)}")
        try {
            startActivity(BlockActivity.intent(this, pkg))
        } catch (e: Throwable) {
            Log.e(TAG, "哨兵打不开拦截页", e)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
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

    override fun onDestroy() {
        running = false
        instanceId = null
        main.removeCallbacksAndMessages(null)
        runCatching { Store.log(this, "哨兵停了（:watch 仍在看护）") }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Sentinel"

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
