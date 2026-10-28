package com.yishou.app.partner

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.YishouApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 语音伙伴在跑时让麦克风在别的 App 上面也能用（麦克风类型的前台服务）。
 * 常驻通知写明“在听”，带“停”和“结束并收线”；会话结束服务就停，不开机自启、不后台偷听。
 */
class PartnerVoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val voice get() = (application as YishouApp).partnerVoice
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            INTERRUPT -> { voice.interrupt(); return START_NOT_STICKY }
            END -> { voice.end(); return START_NOT_STICKY }
            CLOSE -> { voice.stop(); stopSelf(); return START_NOT_STICKY }
        }
        if (started) return START_NOT_STICKY
        ensureChannel(this)
        try {
            val n = notification("语音伙伴准备中")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            voice.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        scope.launch {
            var last = ""
            voice.running.collectLatest { r ->
                if (r == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val text = describe(r.live)
                if (text != last) {
                    last = text
                    try { getSystemService(NotificationManager::class.java).notify(ID, notification(text)) } catch (e: SecurityException) { }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun describe(s: LiveState): String {
        val min = (s.remainingMs + 59_999) / 60_000
        val doing = when {
            s.stage == Stage.CLOSE -> "收线中"
            s.phase == Phase.SPEAKING -> "在说"
            s.phase == Phase.THINKING -> "在想"
            else -> "在听"
        }
        return "$doing · 还剩 $min 分钟" + if (s.missed > 0) " · 没接 ${s.missed}" else ""
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 95, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "partner"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PartnerVoiceService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("一手 · 语音伙伴（麦克风在用）")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停", action(INTERRUPT, 96))
            .addAction(0, "结束并收线", action(END, 97))
            .addAction(0, "关掉", action(CLOSE, 98))
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        // 被系统杀掉或用户关掉：这一场停下，没收线的留着下次补
        if (voice.active) voice.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "partner_voice"
        private const val ID = 93
        private const val INTERRUPT = "interrupt"
        private const val END = "end"
        private const val CLOSE = "close"

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "语音伙伴（麦克风）", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }

        fun start(context: Context): Boolean = try {
            ensureChannel(context)
            ContextCompat.startForegroundService(context, Intent(context, PartnerVoiceService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }
}
