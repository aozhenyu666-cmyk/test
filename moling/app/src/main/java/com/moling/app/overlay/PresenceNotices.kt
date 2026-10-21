package com.moling.app.overlay

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.moling.app.Notify
import com.moling.app.R

object PresenceNotices {
    const val SESSION = 21500
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
    fun cancel(context: Context, id: Int) { runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id) } }
}
