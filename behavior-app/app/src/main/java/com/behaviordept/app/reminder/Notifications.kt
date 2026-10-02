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
    const val CHANNEL_GUARD = "guard"
    private const val ID_DAILY = 1001
    private const val ID_REVIEW = 1002
    private const val ID_GUARD = 1003
    private const val ID_WEEKLY = 1004

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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, "防线", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "娱乐用时超过上限、周日复盘"
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 点通知直达对应页面（默认“今日”）。每种通知用自己的 requestCode，互不覆盖。 */
    private fun open(context: Context, id: Int, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showDaily(context: Context, title: String, text: String) = show(context, CHANNEL_DAILY, ID_DAILY, title, text)

    fun showReviewDue(context: Context, title: String, text: String) = show(context, CHANNEL_REVIEW, ID_REVIEW, title, text)

    fun showGuard(context: Context, title: String, text: String) = show(context, CHANNEL_GUARD, ID_GUARD, title, text)

    fun showWeekly(context: Context, title: String, text: String) =
        show(context, CHANNEL_GUARD, ID_WEEKLY, title, text, MainActivity.ROUTE_WEEKLY)

    private fun show(context: Context, channel: String, id: Int, title: String, text: String, route: String = MainActivity.ROUTE_TODAY) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open(context, id, route))
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
