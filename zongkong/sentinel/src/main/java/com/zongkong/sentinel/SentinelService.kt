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
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 哨兵的无障碍服务（独立应用 com.zongkong.sentinel）。
 *   - 总控在跑：什么都不做，只看总控的心跳。
 *   - 总控掉了（心跳停了 90 秒，或者它下线前说了一声）：拦截名单里的应用一律拦；发一条通知；
 *     有“写安全设置”权限就把总控的无障碍重新挂上；再碰一下总控把它的进程拉起来。
 */
class SentinelService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var lastBlockAt = 0L
    private var lastHealAt = 0L
    private var down = false

    private val loop = object : Runnable {
        override fun run() {
            try {
                Store.touch()
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
            Store.touch()
            Store.log(this, "哨兵连上了")
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
            val alive = try { Sys.askMain(this) } catch (_: Throwable) { false }
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
        if (!Decide.shouldBlock(st, System.currentTimeMillis(), pkg, Sys.mainInstalled(this))) return
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        main.removeCallbacks(loop)
        runCatching { Store.log(this, "哨兵停了") }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Sentinel"
        private const val CHANNEL = "down"
        private const val NOTIFY_ID = 7
        @Volatile var running = false
    }
}
