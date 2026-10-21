package com.moling.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/*
 * 墨灵的通知（M9 起）。两个渠道：
 *  - 「墨灵在」：后台心跳的常驻通知，低优先级、不出声；
 *  - 「墨灵提醒」：对账、总控结局、总控掉线、水流偏了（M11 开始往这里发）。
 * 通知只是“她说话”的一种方式，不替代 App 里的提醒条。
 */
object Notify {
    const val CH_HEART = "moling_heart"
    const val CH_ALERT = "moling_alert"

    /** 常驻通知的 id；M11 的提醒另用 kind.hashCode()。 */
    const val ID_HEART = 1001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_HEART) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_HEART, "墨灵在", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "墨灵在后台陪着你时常驻的那条通知"
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CH_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "墨灵提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "对账、总控那边的结局、总控掉线、水流偏了"
                },
            )
        }
    }

    /** 点通知打开「今天」页。 */
    fun todayIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, 1)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 常驻通知：标题固定「墨灵在」，正文是此刻该在的水道。 */
    fun heart(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, CH_HEART)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("墨灵在")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(todayIntent(context))
            .build()

    fun updateHeart(context: Context, text: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(ID_HEART, heart(context, text))
        }
    }
}
