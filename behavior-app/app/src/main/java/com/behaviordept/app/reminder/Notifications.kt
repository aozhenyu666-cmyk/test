package com.behaviordept.app.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.behaviordept.app.MainActivity
import com.behaviordept.app.R

object Notifications {
    const val CHANNEL_DAILY = "daily"
    const val CHANNEL_REVIEW = "review_due"
    private const val ID_DAILY = 1001
    private const val ID_REVIEW = 1002

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, "每日提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "每天固定时间提醒今日一件事"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REVIEW, "自测到期", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "当天到期的间隔自测还没做时提醒一次"
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 点通知直达“今日”。 */
    private fun openToday(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_TODAY, true)
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showDaily(context: Context, title: String, text: String) = show(context, CHANNEL_DAILY, ID_DAILY, title, text)

    fun showReviewDue(context: Context, title: String, text: String) = show(context, CHANNEL_REVIEW, ID_REVIEW, title, text)

    private fun show(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openToday(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // 权限在检查之后被收回：忽略这一次。
        }
    }
}
