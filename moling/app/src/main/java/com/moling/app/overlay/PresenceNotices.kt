package com.moling.app.overlay

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.moling.app.MainActivity
import com.moling.app.Notify
import com.moling.app.R

object PresenceNotices {
    const val SESSION = 21500
    const val ACTOR = 21600
    fun show(context: Context, id: Int, title: String, text: String, ongoing: Boolean = false) {
        Notify.ensureChannels(context)
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(id,
                NotificationCompat.Builder(context, if (ongoing) Notify.CH_HEART else Notify.CH_ALERT)
                    .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(ongoing)
                    .setContentIntent(if (ongoing) PendingIntent.getActivity(context, SESSION,
                        Intent(context, PresenceActivity::class.java).putExtra("mode", "conclude"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else Notify.todayIntent(context)).build())
        }.onFailure { Presence.error(context, "通知不可用：${it.javaClass.simpleName}") }
    }
    /** 第十二批：外脑会话的常驻通知，点一下回对话页的「外脑」那条收线。 */
    fun showOutbrain(context: Context, title: String, text: String) {
        Notify.ensureChannels(context)
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(ACTOR,
                NotificationCompat.Builder(context, Notify.CH_HEART)
                    .setSmallIcon(R.drawable.ic_notify).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(PendingIntent.getActivity(context, ACTOR,
                        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 0),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }.onFailure { Presence.error(context, "通知不可用：${it.javaClass.simpleName}") }
    }
    fun cancel(context: Context, id: Int) { runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) } }
}
