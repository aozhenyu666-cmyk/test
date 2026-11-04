package com.dshdriver.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 常驻守护：拦截开着或监督开着时，用它把进程从 cached 提到前台服务级别。
 *
 * 为什么必须有它：无障碍服务是绑在进程上的。只开拦截、没开陪练时，进程没有前台服务，
 * 系统一回收，无障碍就跟着死，而 JobScheduler 最快只能 15 分钟后再救。
 * 这里 15 秒（亮屏）/60 秒（熄屏）查一次，掉了就写回，同时给 UsageStats 兜底留一个活着的进程。
 *
 * 它不常驻在"什么都没开"的时候：`sync()` 会按开关自动起停。
 */
class GuardService : Service() {
    companion object {
        private const val CHANNEL = "guard"
        private const val NOTE_ID = 4
        private const val ON_MS = 15_000L
        private const val OFF_MS = 60_000L
        private const val BACKSTOP_MS = 5 * 60_000L

        @Volatile var running = false
            private set

        fun needed(context: Context): Boolean =
            Intercept.enabled(context) || AwarenessRuntime.active || Engine.store.settings().peerGuard

        fun sync(context: Context) {
            if (needed(context)) start(context) else stop(context)
        }

        fun start(context: Context) {
            if (running) return
            val i = Intent(context, GuardService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, GuardService::class.java)) }
        }

        /** 进程被杀后的兜底：5 分钟一次的闹钟会把服务再拉起来（系统限制下可能延后）。 */
        fun scheduleBackstop(context: Context) {
            runCatching {
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                val pi = PendingIntent.getBroadcast(
                    context, 0, Intent(context, GuardAlarmReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + BACKSTOP_MS, pi)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var screenOn = true
    private var lastLabel = ""
    @Volatile private var lastPeerAt = 0L
    private val checking = AtomicBoolean(false)

    private val triggers = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> { screenOn = true; checkOnce() }
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; checkOnce() }
                // 解锁、装包、改时间、充电：都顺手复查一次，不用等下一个 15 秒
                else -> checkOnce()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            checkOnce()
            main.postDelayed(this, if (screenOn) ON_MS else OFF_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        registerTriggers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startGuardNotification()
        running = true
        main.removeCallbacks(ticker)
        main.post(ticker)
        scheduleBackstop(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉后仍然常驻；进程真被杀还有闹钟和 JobScheduler 兜底
        scheduleBackstop(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(ticker)
        runCatching { unregisterReceiver(triggers) }
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) }
        super.onDestroy()
    }

    private fun checkOnce() {
        if (!needed(this)) {
            stopSelf()
            return
        }
        if (!checking.compareAndSet(false, true)) return
        Thread {
            try {
                val on = A11yGuard.isOn(this)
                if (!on) {
                    val healed = A11yGuard.ensure(this)
                    if (!healed && !A11yGuard.canHeal(this)) A11yGuard.notifyDown(this)
                } else {
                    A11yGuard.clearDownNotice(this)
                }
                val now = System.currentTimeMillis()
                if (now - lastPeerAt > 5 * 60_000L) {
                    lastPeerAt = now
                    A11yGuard.ensurePeers(this)
                }
                UsageMonitor.check(this)
                updateGuardNotification(on)
                scheduleBackstop(this)
            } finally {
                checking.set(false)
            }
        }.start()
    }

    private fun registerTriggers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(triggers, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(triggers, filter)
    }

    private fun startGuardNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "拦截守护", NotificationManager.IMPORTANCE_LOW))
        val n = notification("守护中")
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTE_ID, n)
    }

    private fun updateGuardNotification(a11yOn: Boolean) {
        // 服务已经被停掉（needed=false 触发的 stopSelf）时别再补通知
        if (!running || !needed(this)) return
        val label = if (a11yOn) "无障碍已开" else if (A11yGuard.canHeal(this)) "正在把无障碍写回去" else "无障碍没开，需要手动授权"
        if (label == lastLabel) return
        lastLabel = label
        main.post {
            runCatching {
                getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, notification(label))
            }
        }
    }

    private fun notification(label: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_driver)
            .setContentTitle("思维驾驶员 · 拦截守护")
            .setContentText(label)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}

/** 5 分钟兜底闹钟：服务被系统清掉后，尽量把它再拉起来。 */
class GuardAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!GuardService.needed(context)) return
        GuardService.start(context)
    }
}
