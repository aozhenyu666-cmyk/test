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
 * 哨兵的无障碍服务（独立应用 com.zongkong.sentinel）。
 *   - 总控在跑：什么都不做，只看总控的心跳。
 *   - 总控掉了（心跳停了 90 秒，或者它下线前说了一声）：拦截名单里的应用一律拦；发一条通知；
 *     有“写安全设置”权限就把总控的无障碍重新挂上；再碰一下总控把它的进程拉起来。
 *
 * v2 起另外：
 *   - 每条窗口切换写进事件进度文件（`:watch` 进程靠它判断事件通道是否失灵）；
 *   - 连上时拉起 `:watch`（健康挑战、本地规则、锁屏兜底都在那边，这里卡死它也照样跑）；
 *   - 判断总控在不在时看总控回的 v2 健康结论（healthStatus），不再只看 GuardService.running。
 */
class SentinelService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var lastBlockAt = 0L
    private var lastHealAt = 0L
    private var down = false
    private var eventSeq = 0L
    private val events by lazy { EventProgressFile(filesDir) }
    private var eventState = EventProgressFile.State()

    private val loop = object : Runnable {
        override fun run() {
            try {
                Store.touch()
                DeviceLock.sync(this@SentinelService) // 解除「准备卸载」结束后，把卸载锁重新锁上
                watch()
                // 一直停在一个被拦的应用里（没有新的窗口事件）：也再看一眼
                lastPkg?.let { check(it, probed = false) }
            } catch (e: Throwable) {
                Log.w(TAG, "哨兵循环出错", e)
            }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            running = true
            instanceId = "a11y-${SystemClock.elapsedRealtime()}-${(1000..9999).random()}"
            Store.touch()
            Store.log(this, "哨兵连上了（实例 $instanceId）")
            // 跨进程的健康调度与锁屏执行：拉起独立 :watch 进程
            WatchBridge.start(this)
            eventState = events.read() ?: EventProgressFile.State()
            eventState = eventState.copy(instanceId = instanceId)
            main.post(loop)
        } catch (e: Throwable) {
            Log.e(TAG, "哨兵连接时出错", e)
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
            check(pkg, probed = false)
        } catch (e: Throwable) {
            Log.w(TAG, "哨兵处理事件出错", e)
        }
    }

    /**
     * 心跳停了不一定是掉了：手机深睡醒来的头几秒，总控的心跳也会“过期”。所以先直接问总控一句（后台线程），
     * 它说眼睛在，就当一次心跳；问不应或者它说不在，才算掉线。问完回到主线程接着走 [then]。
     */
    private var probing = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    /** 同一时间只问一次；问的过程中别处也想问，就排在后面一起拿结果。全在主线程上调用。 */
    private fun probe(then: (alive: Boolean) -> Unit) {
        waiting += then
        if (probing) return
        probing = true
        Thread {
            val alive = try { Sys.mainGuarding(this) } catch (_: Throwable) { false }
            if (alive) Store.refresh(this, System.currentTimeMillis())
            main.post {
                probing = false
                val all = waiting.toList()
                waiting.clear()
                all.forEach { cb -> try { cb(alive) } catch (e: Throwable) { Log.w(TAG, "探问后出错", e) } }
            }
        }.start()
    }

    /** 每 30 秒：总控掉没掉。掉了就记一笔、提醒、修；回来了就收起通知。 */
    private fun watch() {
        if (!Sys.mainInstalled(this)) return
        val st = Store.state(this) ?: return // 总控从没连上过：还没开始守
        if (Decide.downFor(st, System.currentTimeMillis()) == null) {
            if (down) {
                down = false
                Store.log(this, "总控回来了")
                cancelAlert()
            }
            return
        }
        if (!interactive() && !down) return // 熄屏时不急，亮屏再说（亮屏的第一个窗口事件会触发检查）
        probe { alive -> if (!alive) takeover() }
    }

    private fun takeover() {
        val now = System.currentTimeMillis()
        if (!down) {
            down = true
            Store.countTakeover(this)
            val gone = Decide.downFor(Store.state(this), now)
            Store.log(this, "总控掉线（${if (gone == null || gone == 0L) "它下线时说了一声" else "心跳停了 ${Decide.ago(gone)}"}），哨兵接管")
            alert()
        }
        if (Decide.shouldHeal(lastHealAt, now)) {
            lastHealAt = now
            if (Sys.rebind(this, Sys.mainGuard())) Store.log(this, "把总控的无障碍挂回去")
        }
    }

    /**
     * 打开 [pkg] 时：总控在就不管；心跳过期了先问总控一句（[probed] = 问过了），确实不在才拦。
     */
    private fun check(pkg: String, probed: Boolean) {
        val st = Store.state(this)
        if (!Decide.shouldBlock(st, System.currentTimeMillis(), pkg, Sys.mainInstalled(this), released = Store.releaseGranted(this))) return
        if (!interactive()) return
        if (!probed) {
            // 刚切过来：总控也同时收到了这个窗口事件，给它 1.5 秒写心跳，再去问
            main.postDelayed({
                try { if (lastPkg == pkg) probe { alive -> if (!alive && lastPkg == pkg) check(pkg, probed = true) } } catch (_: Throwable) {}
            }, 1_500)
            return
        }
        takeover()
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

    // ---------- 通知 ----------

    private fun alert() {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "总控掉线", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("总控掉线了，哨兵在拦")
                .setContentText("娱乐应用先关着。点这里看怎么把总控修好。")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (e: Throwable) {
            Log.w(TAG, "发不出通知", e)
        }
    }

    private fun cancelAlert() {
        try { getSystemService(NotificationManager::class.java)?.cancel(NOTIFY_ID) } catch (_: Throwable) {}
    }

    /** 事件进度落盘：序号 + 单调时刻 + 最近若干条（`:watch` 靠它区分「线程卡」和「事件不投递」）。 */
    private fun recordEvent(pkg: String) {
        try {
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
        } catch (e: Throwable) {
            Log.w(TAG, "写事件进度出错", e)
        }
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
        private const val CHANNEL = "down"
        private const val NOTIFY_ID = 7
        /** 只表示「无障碍连接还在」，不能当健康凭据。 */
        @Volatile var running = false

        /** 本次无障碍连接实例；重建必须换新。 */
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
