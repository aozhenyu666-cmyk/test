package com.yishou.app.partner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.YishouApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 只持有用户主动开始的外部伴读计时，不录音，不在外部界面盖浮窗。 */
class PartnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val partner get() = (application as YishouApp).partner
    private var started = false
    private var sessionId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            scope.launch {
                partner.finish()
                showClosing()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        ensureChannel(this)
        try {
            val notification = notification(partner.state.value.text, ongoing = true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(ID, notification)
        } catch (e: Exception) {
            partner.serviceStopped(sessionId ?: partner.state.value.row?.id)
            stopSelf()
            return START_NOT_STICKY
        }
        sessionId = partner.state.value.row?.id
        started = true
        scope.launch {
            var lastText = ""
            var ticks = 0
            while (isActive) {
                if (!notificationsVisible(this@PartnerService)) partner.finish()
                partner.tick(checkpoint = ticks++ % 60 == 0)
                val state = partner.state.value
                if (state.closing || state.row == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (state.closing) showClosing()
                    stopSelf()
                    break
                }
                if (state.text != lastText) {
                    post(ID, notification(state.text, ongoing = true))
                    lastText = state.text
                }
                delay(1_000)
            }
        }
        return START_NOT_STICKY
    }

    private fun showClosing() {
        post(CLOSE_ID, notification("伴读结束了，回来留一句收线", ongoing = false))
    }

    private fun post(id: Int, notification: Notification) {
        // 会话中撤回通知权限时照常停表，不能因为发不出通知而卡在服务里。
        try { getSystemService(NotificationManager::class.java).notify(id, notification) }
        catch (e: SecurityException) { }
    }

    private fun notification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 90,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "partner"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val backIntent = packageManager.getLaunchIntentForPackage(PartnerSessions.EXTERNAL_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(PartnerSessions.EXTERNAL_URL))
        val back = PendingIntent.getActivity(this, 91, backIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("一手 · 语音伙伴")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (ongoing) {
            val stop = PendingIntent.getService(this, 92, Intent(this, PartnerService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, "回 Gemini", back).addAction(0, "结束并收线", stop)
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        partner.serviceStopped(sessionId)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "partner_session"
        private const val ID = 90
        private const val CLOSE_ID = 91
        private const val STOP = "stop"
        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "语音伙伴计时", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
        private fun notificationsVisible(context: Context): Boolean {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)
            return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                channel?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun start(context: Context): Boolean = try {
            ensureChannel(context)
            if (!notificationsVisible(context)) {
                false
            } else {
                clearClosing(context)
                ContextCompat.startForegroundService(context, Intent(context, PartnerService::class.java))
                true
            }
        } catch (e: Exception) { false }
        fun stop(context: Context) { context.stopService(Intent(context, PartnerService::class.java)) }
        fun clearClosing(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(CLOSE_ID) }
    }
}
