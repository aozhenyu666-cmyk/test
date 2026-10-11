package com.yishou.app.window

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yishou.app.R
import com.yishou.app.YishouApp
import com.yishou.app.look.ScreenLookService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * 陪练窗口进行中的前台服务。它本身不做陪练，只让进程在窗口期间保持前台优先级：
 * 从最近任务里划掉界面、或系统清理后台时，计时、朗读和无回应提醒不会跟着停。
 * 通知里显示剩余时间，窗口结束自动退出。
 */
class WindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val n = notification(remainingText())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法进入前台", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    delay(30_000)
                    val text = remainingText()
                    if (text == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                    getSystemService(NotificationManager::class.java).notify(ID, notification(text))
                }
            }
        }
        return START_STICKY
    }

    /** “还剩 32 分钟”；没有进行中的窗口时为 null。 */
    private fun remainingText(): String? {
        val app = application as YishouApp
        val now = System.currentTimeMillis()
        val prefs = app.settings.app.value
        val span = WindowClock.active(now, prefs, ZoneId.systemDefault()) ?: return null
        val min = ((span.end - now) / 60_000).coerceAtLeast(0) + 1
        return if (prefs.windowPauseUntil > now) "休息中，窗口还剩 $min 分钟" else "窗口还剩 $min 分钟"
    }

    private fun notification(text: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, WindowActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("陪练窗口进行中")
            .setContentText(text ?: "陪练窗口进行中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (ScreenLookService.running.value) {
            val look = PendingIntent.getService(
                this, 21, Intent(this, ScreenLookService::class.java).setAction("look"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "看一眼", look)
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WindowService"
        private const val CHANNEL = "window_session"
        private const val ID = 7
        private const val ACTION_STOP = "stop"

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "陪练窗口进行中", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "窗口期间的常驻通知，显示剩余时间"
                    setShowBadge(false)
                },
            )
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WindowService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "无法启动窗口服务", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WindowService::class.java))
        }
    }
}
